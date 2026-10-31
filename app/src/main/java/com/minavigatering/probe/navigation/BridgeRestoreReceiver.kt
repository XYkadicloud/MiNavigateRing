package com.minavigatering.probe.navigation

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import com.minavigatering.probe.protocol.NavCodec
import com.minavigatering.probe.protocol.StateMessage
import com.minavigatering.probe.transport.ProbeActions
import com.minavigatering.probe.transport.ProbeForegroundService
import com.minavigatering.probe.transport.RuntimeTimeline
import com.minavigatering.probe.transport.TimelineEvent

/** The one timeline log tag; every recording component writes the same short sink text. */
internal const val TIMELINE_TAG = "MiRingTimeline"

/** Outcome words for the persisted restore result: categories, never a raw message. */
internal const val RESTORE_OK = "ok"
internal const val RESTORE_FAILED_PREFIX = "failed:"
internal const val RESTORE_SKIPPED = "skipped"

/**
 * True only for the two broadcast actions a restored bridge reacts to, and only while the
 * user left the bridge on. No other action may start a session from a background broadcast.
 */
fun shouldRestoreBridge(action: String?, bridgeEnabled: Boolean): Boolean =
    bridgeEnabled && (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED)

/**
 * Installs the de-identified log sink and records one category event on the shared timeline.
 *
 * The sink is installed before every record on purpose: several components write to the one
 * process-wide timeline, and re-installing the identical lambda keeps logging alive without
 * depending on which component happened to start first.
 */
internal fun recordTimelineEvent(wallMs: Long, event: TimelineEvent, detail: String) {
    RuntimeTimeline.shared.setObserver { entry -> Log.i(TIMELINE_TAG, "${entry.event} ${entry.detail}") }
    RuntimeTimeline.shared.record(wallMs, SystemClock.elapsedRealtime(), event, detail)
}

/**
 * Restores the bridge after boot or an in-place update — only while the user left it on.
 *
 * The listener rebind is best effort and is deliberately not claimed to work: a refusal
 * (Android 12+ background restriction) is swallowed and the foreground START still runs, so
 * a failed rebind is never reported as a live listener. Only an outcome word plus an
 * exception class name is persisted and written to the timeline; the receiver never posts a
 * notification, opens an activity or defers with `goAsync`.
 */
class BridgeRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val settings = NavigationSettings(context)
        val wallMs = System.currentTimeMillis()
        val outcome = if (shouldRestoreBridge(intent.action, settings.enabled)) {
            restoreBridge(context, settings)
        } else {
            RESTORE_SKIPPED
        }
        settings.lastRestoreText = outcome
        settings.lastRestoreAtMs = wallMs
        recordTimelineEvent(wallMs, TimelineEvent.RECOVERY_RESULT, outcome)
    }

    /** Best-effort rebind, then the foreground START. Returns the persisted outcome word. */
    private fun restoreBridge(context: Context, settings: NavigationSettings): String {
        rebindListenerQuietly(context)
        val now = System.currentTimeMillis()
        return try {
            val state = settings.restore(now)
                ?: StateMessage(id = "idle-$now", sentAtMs = now, mode = settings.mode)
            context.startForegroundService(Intent(context, ProbeForegroundService::class.java)
                .setAction(ProbeActions.START)
                .putExtra(ProbeForegroundService.EXTRA_STATE, NavCodec.encodeToString(state)))
            RESTORE_OK
        } catch (e: RuntimeException) {
            RESTORE_FAILED_PREFIX + e.javaClass.simpleName
        }
    }

    /** Rebinds the notification listener and keeps the restore going whatever Android decides. */
    private fun rebindListenerQuietly(context: Context) {
        try {
            NotificationListenerService.requestRebind(ComponentName(context, MapsNotificationService::class.java))
        } catch (e: RuntimeException) {
            // A refused rebind is not a restore failure: the START below still runs.
        }
    }
}
