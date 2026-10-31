package com.minavigatering.probe.navigation

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.SystemClock
import com.minavigatering.probe.protocol.NavCodec
import com.minavigatering.probe.protocol.StateMessage
import com.minavigatering.probe.transport.ProbeActions
import com.minavigatering.probe.transport.ProbeForegroundService
import com.minavigatering.probe.transport.ProbeRuntime
import com.minavigatering.probe.transport.TimelineEvent
import com.minavigatering.probe.transport.WearAutoLaunch

/**
 * Reads only Google Maps navigation notifications after the user grants notification access.
 *
 * Unlike Amap, Google Maps encodes the turn direction mostly in the notification's maneuver
 * icon, so this service also extracts [Notification.EXTRA_LARGE_ICON] and passes its pixels to
 * [MapsParser] for a local brightness hash (the icon is never logged or sent off-device). Text
 * fields still feed distance / road-name parsing as a fallback.
 */
class MapsNotificationService : NotificationListenerService() {
    companion object {
        @Volatile private var connected: MapsNotificationService? = null

        /**
         * True only while the OS has this listener bound and connected. A granted notification
         * access permission alone is not a live connection.
         */
        val isConnected: Boolean get() = connected != null
        fun refreshCurrent() { connected?.readCurrent() }

        /**
         * Minimum gap between two background START attempts while no session runs.
         * A refused attempt still occupies its window, so a rejecting OS cannot be retried
         * into a tight loop; the first attempt after a process start is always allowed.
         */
        private const val RESTORE_START_INTERVAL_MS = 30_000L

        /** Delay after a successful auto launch before the newest state is re-sent as UPDATE. */
        private const val RESEND_AFTER_LAUNCH_MS = 3_000L
    }
    private val handler = Handler(Looper.getMainLooper())
    private val turns = MapsTurnTracker(System.currentTimeMillis().toString(36))
    private val tracked = mutableSetOf<String>()
    private var removal: Runnable? = null

    /** The newest published state, kept in memory so a resend needs no restore pass. */
    private var latestPublished: StateMessage? = null
    private val resendAfterLaunch = Runnable { resendLatestUpdate() }

    /** Elapsed stamp of the last background START attempt; null means none since process start. */
    private var lastRestoreAttemptElapsedMs: Long? = null

    override fun onListenerConnected() {
        connected = this
        recordTimelineEvent(System.currentTimeMillis(), TimelineEvent.LISTENER_CONNECTED, "connected")
        readCurrent()
    }
    private fun readCurrent() {
        activeNotifications?.filter { it.packageName == MapsParser.PACKAGE }
            ?.sortedBy { it.postTime }?.forEach(::onNotificationPosted)
    }
    override fun onListenerDisconnected() {
        if (connected === this) connected = null
        recordTimelineEvent(System.currentTimeMillis(), TimelineEvent.LISTENER_DISCONNECTED, "disconnected")
        tracked.clear()
        updateNavigationPresent(tracked.isNotEmpty())
        WearAutoLaunch.onNavigationEnded()
        handler.removeCallbacks(resendAfterLaunch)
        removal?.let(handler::removeCallbacks)
        publish(MapsCloseout.listenerDisconnected())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != MapsParser.PACKAGE) return // Other apps never reach the timeline.
        if (!NavigationSettings(this).enabled) {
            recordTimelineEvent(System.currentTimeMillis(), TimelineEvent.MAPS_NOTIFICATION_IGNORED, "bridge-off")
            return
        }
        val extras = sbn.notification.extras
        val fields = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT).mapNotNull { extras.getCharSequence(it)?.toString() } +
            (extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() } ?: emptyList())
        // 转向图标：Android 10+ 的 largeIcon 是 Icon（typ=BITMAP），不是裸 Bitmap。
        // 先兼容旧版裸 Bitmap，再走 Icon.getBitmap()。
        val icon: Bitmap? = (extras.get(Notification.EXTRA_LARGE_ICON) as? Bitmap)
            ?: (extras.get(Notification.EXTRA_LARGE_ICON) as? Icon)?.bitmapOrNull()
            ?: sbn.notification.largeIcon?.bitmapOrNull()
        val (pixels, w, h) = if (icon != null) {
            val px = IntArray(icon.width * icon.height)
            icon.getPixels(px, 0, icon.width, 0, 0, icon.width, icon.height)
            Triple(px, icon.width, icon.height)
        } else {
            Triple(null, 0, 0)
        }
        // 图标哈希标定：把当前图标的哈希、尺寸、文字打到 logcat，并把原图存到
        // 应用外部目录（adb pull 可取）。用于在陌生机型上补 iconHashMap。
        if (icon != null && pixels != null) {
            val hash = MapsParser.hashIcon(pixels, w, h)
            android.util.Log.i("MapsIcon", "hash=$hash w=$w h=$h text=${fields.joinToString(" | ")}")
            try {
                val dir = java.io.File(getExternalFilesDir(null), "maps-icons")
                dir.mkdirs()
                java.io.File(dir, "icon_${hash}_${System.currentTimeMillis()}.png").outputStream().use {
                    icon.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } catch (_: Exception) {
                // 标定辅助，失败不影响主流程
            }
        }
        val instruction = MapsParser.parse(sbn.packageName, fields, pixels, w, h)
        if (instruction == null) {
            recordTimelineEvent(System.currentTimeMillis(), TimelineEvent.MAPS_NOTIFICATION_IGNORED, "not-navigation")
            return
        }
        recordInstruction(instruction)
        removal?.let(handler::removeCallbacks)
        removal = null
        if (instruction.status == "ended") {
            tracked.clear()
            updateNavigationPresent(tracked.isNotEmpty())
            WearAutoLaunch.onNavigationEnded()
            handler.removeCallbacks(resendAfterLaunch)
            publish(MapsCloseout.navigationEnded())
        } else {
            tracked.add(sbn.key)
            updateNavigationPresent(tracked.isNotEmpty())
            publish(instruction)
        }
    }

    /** One category event per handled Maps notification: active, unrecognized or ended. */
    private fun recordInstruction(instruction: MapsInstruction) {
        val event = when (instruction.status) {
            "active" -> TimelineEvent.MAPS_NOTIFICATION_ACTIVE
            "ended" -> TimelineEvent.MAPS_NOTIFICATION_ENDED
            else -> TimelineEvent.MAPS_NOTIFICATION_UNRECOGNIZED
        }
        recordTimelineEvent(System.currentTimeMillis(), event, instruction.status)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != MapsParser.PACKAGE) return
        // A removal must update the wake-lease flag before the early return below.
        val removed = tracked.remove(sbn.key)
        updateNavigationPresent(tracked.isNotEmpty())
        if (!removed || tracked.isNotEmpty()) return
        removal?.let(handler::removeCallbacks)
        // A removal alone is not proof of arrival/end. Stop displaying old directions after a grace period.
        removal = Runnable {
            // Only an expired grace with nothing tracked left is a real session end.
            val closeout = MapsCloseout.afterRemovalGrace(tracked.isEmpty()) ?: return@Runnable
            WearAutoLaunch.onNavigationEnded()
            handler.removeCallbacks(resendAfterLaunch)
            publish(closeout)
        }.also { handler.postDelayed(it, MapsCloseout.REMOVAL_GRACE_MS) }
    }
    private fun publish(instruction: MapsInstruction) {
        val settings = NavigationSettings(this)
        if (!settings.enabled) return
        val now = System.currentTimeMillis()
        val state = StateMessage(id = "maps-$now", sentAtMs = now, navStatus = instruction.status,
            action = instruction.action, distanceM = instruction.distanceM, roadName = instruction.roadName,
            exitNumber = instruction.exitNumber, actionId = turns.actionId(instruction), mode = settings.mode)
        settings.save(state)
        latestPublished = state
        if (ProbeRuntime.session.running) {
            // Running session: the unchanged UPDATE branch, throttled by the send policy itself.
            try {
                startService(Intent(this, ProbeForegroundService::class.java).setAction(ProbeActions.UPDATE)
                    .putExtra(ProbeForegroundService.EXTRA_STATE, NavCodec.encodeToString(state)))
            } catch (e: RuntimeException) { ProbeRuntime.session.onSubmitFailed("后台更新未启动，请打开导航桥") }
        } else {
            tryRestoreStart(settings, state)
        }
    }

    /**
     * Attempts one background START carrying the freshly saved state, at most once per
     * [RESTORE_START_INTERVAL_MS]. The attempt itself consumes the window, so an OS that
     * rejects the start is not retried in a loop; the outcome is persisted as a word plus,
     * on failure, only the exception class name.
     */
    private fun tryRestoreStart(settings: NavigationSettings, state: StateMessage) {
        val elapsed = SystemClock.elapsedRealtime()
        val last = lastRestoreAttemptElapsedMs
        if (last != null && elapsed - last < RESTORE_START_INTERVAL_MS) return
        lastRestoreAttemptElapsedMs = elapsed
        val wallMs = System.currentTimeMillis()
        val outcome = try {
            startForegroundService(Intent(this, ProbeForegroundService::class.java).setAction(ProbeActions.START)
                .putExtra(ProbeForegroundService.EXTRA_STATE, NavCodec.encodeToString(state)))
            RESTORE_OK
        } catch (e: RuntimeException) {
            RESTORE_FAILED_PREFIX + e.javaClass.simpleName
        }
        settings.lastRestoreText = outcome
        settings.lastRestoreAtMs = wallMs
        recordTimelineEvent(wallMs, TimelineEvent.RECOVERY_RESULT, outcome)
    }
    /** Writes the wake-lease flag; only a false-to-true edge asks for one auto launch. */
    private fun updateNavigationPresent(present: Boolean) {
        val previous = ProbeRuntime.wakePolicy.mapsNavigationPresent
        ProbeRuntime.wakePolicy.mapsNavigationPresent = present
        if (!previous && present) WearAutoLaunch.onNavigationStarted(this, ::scheduleResendAfterLaunch)
    }

    /** One pending resend per launch; a newer launch replaces the older timer. */
    private fun scheduleResendAfterLaunch() {
        handler.removeCallbacks(resendAfterLaunch)
        handler.postDelayed(resendAfterLaunch, RESEND_AFTER_LAUNCH_MS)
    }

    /** Re-sends the newest in-memory state as UPDATE; restore would strip the navigation content. */
    private fun resendLatestUpdate() {
        val state = latestPublished ?: return
        try {
            startService(Intent(this, ProbeForegroundService::class.java).setAction(ProbeActions.UPDATE)
                .putExtra(ProbeForegroundService.EXTRA_STATE, NavCodec.encodeToString(state)))
        } catch (e: RuntimeException) { ProbeRuntime.session.onSubmitFailed("后台更新未启动，请打开导航桥") }
    }

    override fun onDestroy() {
        updateNavigationPresent(false)
        WearAutoLaunch.onNavigationEnded()
        handler.removeCallbacks(resendAfterLaunch)
        if (connected === this) {
            connected = null
            publish(MapsCloseout.listenerDisconnected())
        }
        removal?.let(handler::removeCallbacks)
        super.onDestroy()
    }
}

/** 只有 BITMAP 类型的 Icon 才有可用的位图；其他类型返回 null。 */
private fun Icon.bitmapOrNull(): Bitmap? = if (type == Icon.TYPE_BITMAP) bitmap else null
