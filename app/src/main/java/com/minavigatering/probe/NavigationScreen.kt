package com.minavigatering.probe

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.minavigatering.probe.navigation.MapsNotificationService
import com.minavigatering.probe.navigation.NavigationSettings
import com.minavigatering.probe.navigation.NavigationUiModel
import com.minavigatering.probe.navigation.isIgnoringBatteryOptimizations
import com.minavigatering.probe.navigation.mapNavigationUi
import com.minavigatering.probe.navigation.miFitnessAppDetailsIntent
import com.minavigatering.probe.navigation.requestIgnoreBatteryOptimizations
import com.minavigatering.probe.navigation.startActivitySafely
import com.minavigatering.probe.protocol.NavCodec
import com.minavigatering.probe.protocol.StateMessage
import com.minavigatering.probe.transport.ProbeActions
import com.minavigatering.probe.transport.ProbeForegroundService
import com.minavigatering.probe.transport.ProbeRuntime
import com.minavigatering.probe.transport.XiaomiWearableTransport
import kotlinx.coroutines.delay

/**
 * The daily navigation page (PROJECT_SPEC.md section 5).
 *
 * State, side effects and callbacks only: the seven blocks are drawn by the stateless
 * [NavigationContent] and the band mirror by [NavigationHeroCard], both consuming the pure
 * [mapNavigationUi] output. Notification parsing, transport and the watch layout keep their own
 * layers — this page never matches Maps text and never invents a direction.
 */
@Composable
fun NavigationScreen(transport: XiaomiWearableTransport) {
    val context = LocalContext.current
    val prefs = remember { NavigationSettings(context) }
    val state = remember { NavigationScreenState(prefs, transport) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val start = state.pendingStart
        state.pendingStart = null
        if (granted) start?.invoke() else denyNotificationPermission(prefs, state)
    }
    val launchOrStart: (() -> Unit) -> Unit = { action ->
        if (needsNotificationPermission(context)) {
            state.pendingStart = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else action()
    }
    LaunchedEffect(Unit) {
        restoreSessionOnEntry(context, prefs, state, launchOrStart)
        pollNavigationState(context, prefs, transport, state)
    }
    if (state.debug) {
        DebugPage(transport) { state.debug = false }
        return
    }
    val ui = navigationUiModel(state, prefs)
    NavigationContent(
        state = contentState(ui, state),
        actions = contentActions(context, prefs, state, launchOrStart),
    ) { NavigationHeroCard(ui) }
}

/** The seven block container inputs: the mapped model plus the three plain boolean groups. */
private fun contentState(ui: NavigationUiModel, state: NavigationScreenState) = NavigationContentState(
    ui = ui,
    status = NavigationStatus(state.enabled, ProbeRuntime.session.running, state.nodePresent),
    settings = NavigationChecklist(state.access, state.batteryExempt, state.restoreFailed),
)

/** The component callbacks; every Android route stays inside these Screen-owned lambdas. */
private fun contentActions(
    context: Context,
    prefs: NavigationSettings,
    state: NavigationScreenState,
    launchOrStart: (() -> Unit) -> Unit,
) = NavigationContentActions(
    onMode = { value -> selectMode(context, prefs, state, value) },
    onMain = { toggleBridge(context, prefs, state, launchOrStart) },
    onDiagnostics = { state.debug = true },
    settings = navigationSettingActions(context),
)

/** The diagnostics page and its "返回导航" branch, unchanged from the previous main page. */
@Composable
private fun DebugPage(transport: XiaomiWearableTransport, onBack: () -> Unit) {
    Column(Modifier.safeDrawingPadding()) {
        Button(onClick = onBack) { Text("返回导航") }
        ProbeScreen(transport)
    }
}

/**
 * Page state, held outside the composable body so the screen itself only wires it up.
 *
 * [mode], [enabled], [access], [latest], [nodePresent], [listenerDownMs] and [pendingStart] keep
 * exactly the roles they had as function-local state. [batteryExempt] and [restoreFailed] are the
 * two checklist inputs the redesign added.
 */
private class NavigationScreenState(prefs: NavigationSettings, transport: XiaomiWearableTransport) {
    var mode by mutableStateOf(prefs.mode)
    var enabled by mutableStateOf(prefs.enabled)
    var debug by mutableStateOf(false)
    var access by mutableStateOf(false)

    // Battery exemption, refreshed by the same 1 s poll: it can be granted in system settings
    // while this page stays open, so it must never be read once at composition time.
    var batteryExempt by mutableStateOf(false)

    // Background restore failure, surfaced as "需要打开一次应用恢复同步".
    var restoreFailed by mutableStateOf(prefs.lastRestoreText.startsWith("failed"))

    var latest by mutableStateOf<StateMessage?>(null)

    // Phone-side truth for "is a watch node reachable right now", refreshed by the same poll
    // loop. Deliberately not read from the message payload link field.
    var nodePresent by mutableStateOf(transport.currentNodeId != null)

    // Milliseconds the notification listener has been continuously unconnected (0 when
    // connected). Distinguishes a granted access permission from an actual live listener binding.
    var listenerDownMs by mutableStateOf(0L)

    // Start of the current listener-down window; cleared as soon as the listener is connected.
    var listenerDownSince: Long? = null

    // Both start paths wait for POST_NOTIFICATIONS on API 33+ before starting the foreground
    // session; the pending action is captured so the same start runs on grant.
    var pendingStart by mutableStateOf<(() -> Unit)?>(null)
}

/**
 * Poll-driven inputs include Maps presence and the auto-launch preference; the screen only
 * renders the result.
 */
private fun navigationUiModel(state: NavigationScreenState, prefs: NavigationSettings): NavigationUiModel = mapNavigationUi(
    listenerAccessGranted = state.access,
    bridgeEnabled = state.enabled,
    sessionRunning = ProbeRuntime.session.running,
    bandNodePresent = state.nodePresent,
    selectedMode = state.mode,
    latest = state.latest,
    listenerDownMs = state.listenerDownMs,
    mapsNavigationPresent = ProbeRuntime.wakePolicy.mapsNavigationPresent,
    autoLaunchBand = prefs.autoLaunchBand,
)

/**
 * The four settings rows, each handing its real system target to the component. Android routing
 * lives here only, so the stateless components never touch a Context.
 */
private fun navigationSettingActions(context: Context): NavigationSettingActions = NavigationSettingActions(
    notifications = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
    // The composed request first, its settings-page fallback inside it; never the bare intent.
    battery = { requestIgnoreBatteryOptimizations(context) },
    appDetails = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
    },
    miFitness = {
        // Resolved at tap time: com.mi.health and com.xiaomi.wearable are different installs.
        val intent = miFitnessAppDetailsIntent(context)
        if (intent == null) {
            startActivitySafely(context, Intent(Settings.ACTION_APPLICATION_SETTINGS))
        } else {
            startActivitySafely(context, intent)
        }
    },
)

/** Reopening the page while the bridge preference is on resumes the foreground session once. */
private fun restoreSessionOnEntry(
    context: Context,
    prefs: NavigationSettings,
    state: NavigationScreenState,
    launchOrStart: (() -> Unit) -> Unit,
) {
    if (!prefs.enabled || ProbeRuntime.session.running) return
    launchOrStart { startForegroundSession(context, restoreStartState(prefs, state.mode)) }
}

/**
 * The once-per-second poll: access, latest state, node presence, the battery exemption and the
 * listener-down window. Behaviour and cadence are unchanged from the previous page.
 */
private suspend fun pollNavigationState(
    context: Context,
    prefs: NavigationSettings,
    transport: XiaomiWearableTransport,
    state: NavigationScreenState,
) {
    while (true) {
        val manager = context.getSystemService(NotificationManager::class.java)
        state.access = manager.isNotificationListenerAccessGranted(
            ComponentName(context, MapsNotificationService::class.java)
        )
        state.latest = ProbeRuntime.session.latestState
        state.nodePresent = transport.currentNodeId != null
        state.batteryExempt = isIgnoringBatteryOptimizations(context)
        state.restoreFailed = prefs.lastRestoreText.startsWith("failed")
        updateListenerWindow(state)
        delay(1000)
    }
}

/** Accumulates the continuous listener-down window in elapsed time; zero once it is connected. */
private fun updateListenerWindow(state: NavigationScreenState) {
    val now = SystemClock.elapsedRealtime()
    if (MapsNotificationService.isConnected) {
        state.listenerDownSince = null
        state.listenerDownMs = 0L
        return
    }
    val since = state.listenerDownSince ?: now
    state.listenerDownSince = since
    state.listenerDownMs = now - since
}

/**
 * Enabling flips the preference first, then starts the session with a fresh identity; disabling
 * sends STOP. The toggle semantics are unchanged.
 */
private fun toggleBridge(
    context: Context,
    prefs: NavigationSettings,
    state: NavigationScreenState,
    launchOrStart: (() -> Unit) -> Unit,
) {
    val next = !state.enabled
    state.enabled = next
    prefs.enabled = next
    if (!next) {
        context.startService(Intent(context, ProbeForegroundService::class.java).setAction(ProbeActions.STOP))
        return
    }
    val now = System.currentTimeMillis()
    launchOrStart {
        startForegroundSession(context, StateMessage(id = "start-$now", sentAtMs = now, mode = state.mode))
    }
}

/** Mode preference write plus the live UPDATE send, unchanged from the previous page. */
private fun selectMode(
    context: Context,
    prefs: NavigationSettings,
    state: NavigationScreenState,
    value: String,
) {
    state.mode = value
    prefs.mode = value
    val current = ProbeRuntime.session.latestState ?: return
    val updated = current.copy(id = "mode-${System.currentTimeMillis()}",
        sentAtMs = System.currentTimeMillis(), mode = value, heartbeat = false)
    prefs.save(updated)
    if (!ProbeRuntime.session.running) return
    context.startService(
        Intent(context, ProbeForegroundService::class.java)
            .setAction(ProbeActions.UPDATE)
            .putExtra(ProbeForegroundService.EXTRA_STATE, NavCodec.encodeToString(updated))
    )
}

/** The restored snapshot, or a neutral idle state, for a session started by page entry. */
private fun restoreStartState(prefs: NavigationSettings, mode: String): StateMessage =
    prefs.restore(System.currentTimeMillis())
        ?: StateMessage(id = "idle-${System.currentTimeMillis()}", sentAtMs = System.currentTimeMillis(), mode = mode)

/** Starts the foreground session with [state] and refreshes the listener, as before. */
private fun startForegroundSession(context: Context, state: StateMessage) {
    context.startForegroundService(
        Intent(context, ProbeForegroundService::class.java)
            .setAction(ProbeActions.START)
            .putExtra(ProbeForegroundService.EXTRA_STATE, NavCodec.encodeToString(state))
    )
    MapsNotificationService.refreshCurrent()
}

/** Denied POST_NOTIFICATIONS: clear the bridge preference and report the failure unchanged. */
private fun denyNotificationPermission(prefs: NavigationSettings, state: NavigationScreenState) {
    state.enabled = false
    prefs.enabled = false
    ProbeRuntime.session.onSubmitFailed("通知权限未授予，未启动前台会话")
}

/** API 33+ needs the notification permission before a foreground session may start. */
private fun needsNotificationPermission(context: Context): Boolean = Build.VERSION.SDK_INT >= 33 &&
    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
    PackageManager.PERMISSION_GRANTED
