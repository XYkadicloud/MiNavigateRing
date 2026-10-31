package com.minavigatering.probe.navigation

import com.minavigatering.probe.protocol.NavProtocol
import com.minavigatering.probe.protocol.StateMessage

/**
 * Pure mapping from probe inputs to the eight daily navigation phases.
 *
 * No Android or Xiaomi SDK dependency: this layer renders nothing and matches no Maps
 * text. Missing fields stay missing — an unknown direction is never coerced to
 * `straight` and no road name, distance or roundabout exit is ever invented
 * (PROJECT_SPEC.md section 4; design/NAVIGATION_ICONS.md).
 *
 * "Connecting" is driven by whether the foreground session runs and whether the phone
 * side currently has a watch node. The message payload `link` field is not consulted:
 * it is carried defaulted to `connected` and would make the condition vacuously false.
 */
enum class NavigationPhase {
    PERMISSION_OFF, BRIDGE_OFF, WAITING, CONNECTING, ACTIVE, UNAVAILABLE, ENDED, LISTENER_DISCONNECTED
}

/**
 * How long the notification listener may stay unconnected before the screen stops trusting the
 * last instruction. The access permission being granted does not mean the OS keeps the listener
 * bound; a cold start needs a short grace window before this is treated as a real drop.
 */
const val LISTENER_DOWN_GRACE_MS = 5_000L

data class NavigationUiModel(
    val phase: NavigationPhase,
    val headline: String,
    val actionLabel: String?,
    val actionId: String?,
    val distanceText: String?,
    val roadName: String?,
    val exitText: String?,
    val modeLabel: String,
    val guidance: String,
    val showAsValidInstruction: Boolean,
    /**
     * Two-line bridge/node status for the main screen. `bandLine` is null when the bridge is
     * off or the foreground session is not running. Node presence is reported as presence
     * only: a band ACK is not verified, so no line claims receipt.
     */
    val bridgeLine: String,
    val bandLine: String?,
)

/** The 14 confirmed Chinese action labels, see design/NAVIGATION_ICONS.md. */
private val ACTION_LABELS: Map<String, String> = mapOf(
    "left" to "左转",
    "right" to "右转",
    "straight" to "直行",
    "slight-left" to "向左前方",
    "slight-right" to "向右前方",
    "sharp-left" to "向左后方",
    "sharp-right" to "向右后方",
    "keep-left" to "靠左行驶",
    "keep-right" to "靠右行驶",
    "uturn-left" to "左侧掉头",
    "uturn-right" to "右侧掉头",
    "roundabout-enter" to "进入环岛",
    "roundabout-exit" to "驶出环岛",
    "arrive" to "到达目的地",
)

/**
 * One user-facing next step per phase. Guardrail wording aimed at reviewers lives in
 * docs/notification-product-polish.md and is never shown on screen.
 */
private val PHASE_SENTENCES: Map<NavigationPhase, String> = mapOf(
    NavigationPhase.PERMISSION_OFF to "请先允许读取 Google 地图通知。",
    NavigationPhase.BRIDGE_OFF to "导航桥已关闭，请开启后再开始。",
    NavigationPhase.WAITING to "请在手环打开导航页，再在 Google 地图开始导航。",
    NavigationPhase.CONNECTING to "正在连接手环。",
    NavigationPhase.ACTIVE to "按画面上的方向继续。",
    NavigationPhase.UNAVAILABLE to "暂时没有可用的转向信息。",
    NavigationPhase.ENDED to "需要时请在 Google 地图重新开始。",
    NavigationPhase.LISTENER_DISCONNECTED to
        "已允许读取通知，但系统没有把 Google 地图通知交给本应用。请在通知读取设置里把“MapRing Google 地图导航”关闭再开启一次，然后回到这里。",
)

/** Waiting guidance when the home switch promises the band page will open by itself. */
private const val WAITING_AUTO_LAUNCH_SENTENCE =
    "在 Google 地图开始步行或骑行导航，手环导航页会自动打开。"

/** Unavailable: Maps is navigating but the action is empty or outside the 14 allowed labels. */
private const val UNAVAILABLE_UNKNOWN_ACTION_SENTENCE =
    "Google 地图给出的转向动作无法识别，手环不会显示猜测的方向。"

/** Unavailable: Maps is navigating but this notice carried no readable direction. */
private const val UNAVAILABLE_ACTION_MISSING_SENTENCE =
    "Google 地图正在导航，但这条提示读不出转向方向。手环不会显示猜测的方向，请看 Google 地图或等下一条提示。"

/** Unavailable: no Maps navigation notice at all, so there is nothing to read yet. */
private const val UNAVAILABLE_NO_NOTICE_SENTENCE =
    "没有读到 Google 地图的导航提示：导航可能已结束，或 Google 地图已退出。要继续请回到 Google 地图重新开始导航。"

/** One guidance sentence per phase; the two phases whose reason varies pick it themselves. */
private fun guidanceFor(
    phase: NavigationPhase,
    latest: StateMessage?,
    mapsNavigationPresent: Boolean,
    autoLaunchBand: Boolean,
): String = when (phase) {
    NavigationPhase.WAITING -> if (autoLaunchBand) WAITING_AUTO_LAUNCH_SENTENCE else PHASE_SENTENCES.getValue(phase)
    NavigationPhase.UNAVAILABLE -> unavailableSentence(latest, mapsNavigationPresent)
    else -> PHASE_SENTENCES.getValue(phase)
}

/** Unavailable always says which of the three reasons applies, never a guessed direction. */
private fun unavailableSentence(latest: StateMessage?, mapsNavigationPresent: Boolean): String = when {
    latest?.navStatus == NavProtocol.STATUS_ACTIVE -> UNAVAILABLE_UNKNOWN_ACTION_SENTENCE
    mapsNavigationPresent -> UNAVAILABLE_ACTION_MISSING_SENTENCE
    else -> UNAVAILABLE_NO_NOTICE_SENTENCE
}

fun mapNavigationUi(
    listenerAccessGranted: Boolean,
    bridgeEnabled: Boolean,
    sessionRunning: Boolean,
    bandNodePresent: Boolean,
    selectedMode: String,
    latest: StateMessage?,
    /** Milliseconds the notification listener has been continuously unconnected; 0 while connected. */
    listenerDownMs: Long = 0L,
    /** Whether Maps currently shows a navigation notice; only explains an unavailable phase. */
    mapsNavigationPresent: Boolean = false,
    /** Home switch: whether starting navigation also launches the band navigation page. */
    autoLaunchBand: Boolean = false,
): NavigationUiModel {
    val modeLabel = if (selectedMode == NavProtocol.MODE_CYCLE) "骑行" else "步行"

    // Bridge first, then foreground session, then node presence. Deliberately independent of
    // the permission/latest/link/ACK inputs.
    val bridgeLine = when {
        !bridgeEnabled -> "开启后自动同步 Google 地图导航"
        !sessionRunning -> "导航桥启动中"
        else -> "导航桥已开启"
    }
    val bandLine = when {
        !bridgeEnabled || !sessionRunning -> null
        !bandNodePresent -> "手环：未发现，正在连接"
        else -> "手环：已发现，尚未确认收到"
    }

    val phase = when {
        !listenerAccessGranted -> NavigationPhase.PERMISSION_OFF
        !bridgeEnabled -> NavigationPhase.BRIDGE_OFF
        listenerDownMs >= LISTENER_DOWN_GRACE_MS -> NavigationPhase.LISTENER_DISCONNECTED
        !sessionRunning || !bandNodePresent -> NavigationPhase.CONNECTING
        latest == null || latest.navStatus == NavProtocol.STATUS_IDLE -> NavigationPhase.WAITING
        latest.navStatus == NavProtocol.STATUS_ENDED -> NavigationPhase.ENDED
        latest.id.startsWith(NavigationSettings.RESTORE_ID_PREFIX) -> NavigationPhase.WAITING
        latest.navStatus == NavProtocol.STATUS_UNRECOGNIZED -> NavigationPhase.UNAVAILABLE
        latest.navStatus == NavProtocol.STATUS_ACTIVE &&
            !latest.action.isNullOrEmpty() && latest.action in NavProtocol.ALLOWED_ACTIONS ->
            NavigationPhase.ACTIVE
        latest.navStatus == NavProtocol.STATUS_ACTIVE -> NavigationPhase.UNAVAILABLE
        else -> NavigationPhase.UNAVAILABLE
    }

    val sentence = guidanceFor(phase, latest, mapsNavigationPresent, autoLaunchBand)

    if (phase != NavigationPhase.ACTIVE || latest == null) {
        val headline = when (phase) {
            NavigationPhase.PERMISSION_OFF -> "通知读取未开启"
            NavigationPhase.BRIDGE_OFF -> "导航桥已关闭"
            NavigationPhase.WAITING -> "等待 Google 地图导航"
            NavigationPhase.CONNECTING -> "通信连接中"
            NavigationPhase.UNAVAILABLE -> "信息暂不可用"
            NavigationPhase.ENDED -> "导航已结束"
            NavigationPhase.LISTENER_DISCONNECTED -> "通知读取未连接"
            NavigationPhase.ACTIVE -> "信息暂不可用"
        }
        return NavigationUiModel(
            phase = phase,
            headline = headline,
            actionLabel = null,
            actionId = null,
            distanceText = null,
            roadName = null,
            exitText = null,
            modeLabel = modeLabel,
            guidance = sentence,
            showAsValidInstruction = false,
            bridgeLine = bridgeLine,
            bandLine = bandLine,
        )
    }

    val action = latest.action ?: ""
    val label = ACTION_LABELS[action]

    val distanceText = when {
        action == "arrive" -> null
        latest.distanceM == null || latest.distanceM < 0 -> "距离未知"
        else -> "${latest.distanceM} 米"
    }

    val roadName = if (latest.roadName.isNullOrBlank()) null else latest.roadName

    val exitText = when (action) {
        "roundabout-exit" ->
            if (latest.exitNumber == null || latest.exitNumber <= 0) "出口未知"
            else "第 ${latest.exitNumber} 出口"
        else -> null
    }

    return NavigationUiModel(
        phase = NavigationPhase.ACTIVE,
        headline = label ?: "信息暂不可用",
        actionLabel = label,
        // Kept for the future icon lookup; it is never rendered as user-facing text.
        actionId = action,
        distanceText = distanceText,
        roadName = roadName,
        exitText = exitText,
        modeLabel = modeLabel,
        guidance = sentence,
        showAsValidInstruction = true,
        bridgeLine = bridgeLine,
        bandLine = bandLine,
    )
}
