package com.minavigatering.probe.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.minavigatering.probe.protocol.NavCodec
import com.minavigatering.probe.protocol.NavProtocol
import com.minavigatering.probe.protocol.StateMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Process-wide probe runtime.
 *
 * The page and the foreground service must share one transport instance, otherwise the
 * service would send through a transport whose node was never refreshed (W-618).
 *
 * Moved here verbatim from `ProbeSession.kt` (work order W-707) so the session file stays
 * pure Kotlin. The shared session additionally receives the Android monotonic clock and
 * the process-wide [RuntimeTimeline.shared]; every existing reference is unchanged.
 */
object ProbeRuntime {

    val session = ProbeSession(RuntimeTimeline.shared, System::currentTimeMillis, SystemClock::elapsedRealtime)

    /** The one wakelock policy for the process (W-733): the listener writes the flag, the loop reads it. */
    val wakePolicy = WakeLeasePolicy()

    @Volatile
    private var instance: XiaomiWearableTransport? = null

    /** Returns the single in-process transport, wired to the shared session. */
    fun transport(context: Context): XiaomiWearableTransport {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: XiaomiWearableTransport(context.applicationContext).also { created ->
                created.setInboundHandler { message -> session.onInbound(message) }
                instance = created
            }
        }
    }
}

/**
 * Keeps the link alive while a probe session runs, sending a heartbeat every 5 seconds
 * (PROJECT_SPEC.md section 7).
 *
 * The phone notification below exists only to satisfy the Android foreground-service
 * requirement. It is *not* the watch channel — probe data always goes through
 * [XiaomiWearableTransport] and the SDK custom message API.
 *
 * Session state lives in [ProbeRuntime.session], so a repeated start command updates the
 * latest state instead of being dropped (work order W-618).
 */
class ProbeForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "probe_session"
        private const val NOTIFICATION_ID = 4711
        const val EXTRA_STATE = "state"
        const val ACTION_SCREEN_OFF_TEST = "com.minavigatering.probe.action.SCREEN_OFF_TEST"
        private const val NODE_WAIT_MS = 4_000L

        /** The one timeline log tag; every recording component writes the same short sink text. */
        private const val TIMELINE_TAG = "MiRingTimeline"
    }

    private var thread: Thread? = null
    private var cpuLease: CpuLease? = null
    @Volatile private var testSequence: TimedProbeSequence? = null

    /** Tracks the dynamic registration so unregister never runs twice (onDestroy races STOP). */
    @Volatile private var systemEventsRegistered = false

    /**
     * Forwards the five watched system broadcasts. The receiver only maps the action to a
     * timeline category; it never reads an extra, a node id or any notification text.
     */
    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            recordSystemEvent(intent.action)
        }
    }

    /**
     * Last-write-wins content slot plus the restart/replay throttle (work order W-643).
     * Heartbeats never pass through it, so a beat can be neither queued nor replayed as
     * a new navigation instruction.
     */
    private val sendPolicy = ForegroundSendPolicy()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        installTimelineObserver()
        registerSystemEventReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = ProbeRuntime.session
        if (intent == null && session.latestState == null) {
            session.update(com.minavigatering.probe.navigation.NavigationSettings(this).restore(System.currentTimeMillis())
                ?: StateMessage(id = "idle-${System.currentTimeMillis()}", sentAtMs = System.currentTimeMillis()))
        }
        val decoded = intent?.getStringExtra(EXTRA_STATE)?.let { raw ->
            runCatching { decodeState(raw) }.getOrNull()
        }
        if (intent?.action == ProbeActions.UPDATE && decoded != null && !decoded.heartbeat) {
            sendPolicy.offer(decoded)
            thread?.interrupt() // Wake a sleeping sender; interruption is handled below.
        }
        val command = applyServiceCommand(session, intent?.action, decoded)
        if (intent?.action == ACTION_SCREEN_OFF_TEST) {
            testSequence = TimedProbeSequence(SystemClock.elapsedRealtime(), System.currentTimeMillis().toString())
        }

        if (command == ServiceCommandResult.Stop) {
            testSequence = null
            stopHeartbeatThread()
            ProbeRuntime.transport(applicationContext).stopListening()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        if (thread == null) {
            // A newly (re)started session must resend its latest content even when the
            // last send happened less than the replay interval ago.
            sendPolicy.onSessionRestart()
            // A foreground notification keeps the service important, but does not keep its
            // sleeping Java thread scheduled while the CPU suspends; the policy owns the
            // lease (W-733), so nothing is acquired unconditionally here.
            val lease = CpuLease.create(
                getSystemService(Context.POWER_SERVICE) as PowerManager,
                ProbeRuntime.wakePolicy,
            )
            cpuLease = lease
            thread = Thread {
                try {
                    heartbeatLoop(session, lease)
                } finally {
                    // Capture this thread's own lease: a stopped old thread must
                    // not release the lease belonging to a newly started session.
                    lease.release()
                }
            }.also {
                it.isDaemon = true
                it.start()
            }
        }
        syncCpuLease()
        return START_STICKY
    }

    /**
     * One heartbeat round: resolve the node, make sure the inbound listener is registered
     * for it, then send the current state as a heartbeat.
     */
    private fun heartbeatLoop(session: ProbeSession, lease: CpuLease) {
        val transport = ProbeRuntime.transport(applicationContext)
        while (session.running) {
            try {
                syncCpuLease()
                recordSendRound(transport, lease)
                Log.d("MiRingService", "heartbeat round elapsedMs=${SystemClock.elapsedRealtime()} cpuHeld=${lease.isHeld}")
                val nodeKnown = transport.currentNodeId != null
                // With a node already resolved and content waiting, an interrupted round
                // must not block again on the node latch: send the newest snapshot now.
                if (!sendPolicy.shouldSkipNodeWait(nodeKnown)) {
                    val latch = CountDownLatch(1)
                    transport.refreshNode { node ->
                        if (node != null) transport.ensureListening()
                        latch.countDown()
                    }
                    latch.await(NODE_WAIT_MS, TimeUnit.MILLISECONDS)
                }

                if (transport.currentNodeId != null && session.running) {
                    val update = sendPolicy.takeContent(
                        System.currentTimeMillis(),
                        transport.currentNodeId != null,
                        session.latestState,
                    )
                    if (update != null) {
                        session.rememberInstruction(update.id)
                        transport.sendState(update) { phase ->
                            when (phase) {
                                is SendPhase.Queued -> session.onQueued(phase.messageId, phase.nodeId)
                                is SendPhase.TaskSucceeded -> session.onTaskSuccess(phase.messageId)
                                is SendPhase.TaskFailed -> session.onTaskFailure(phase.messageId, phase.reason)
                            }
                        }
                    }
                    testSequence?.due(SystemClock.elapsedRealtime(), System.currentTimeMillis())?.forEach { state ->
                        session.rememberInstruction(state.id)
                        session.update(state)
                        Log.i("MiRingService", "synthetic background step id=${state.id}")
                        transport.sendState(state) { phase ->
                            when (phase) {
                                is SendPhase.Queued -> session.onQueued(phase.messageId, phase.nodeId)
                                is SendPhase.TaskSucceeded -> session.onTaskSuccess(phase.messageId)
                                is SendPhase.TaskFailed -> session.onTaskFailure(phase.messageId, phase.reason)
                            }
                        }
                    }
                }

                val heartbeat = session.heartbeat(System.currentTimeMillis())
                Log.d("MiRingService", "heartbeat nodePresent=${transport.currentNodeId != null} statePresent=${heartbeat != null}")
                if (transport.currentNodeId != null && heartbeat != null) {
                    transport.sendState(heartbeat) { phase ->
                        when (phase) {
                            is SendPhase.Queued ->
                                session.onQueued(phase.messageId, phase.nodeId)

                            is SendPhase.TaskSucceeded ->
                                session.onTaskSuccess(phase.messageId).also {
                                    Log.d("MiRingService", "heartbeat Task success id=${phase.messageId}")
                                }

                            is SendPhase.TaskFailed ->
                                session.onTaskFailure(phase.messageId, phase.reason).also {
                                    Log.w("MiRingService", "heartbeat Task failed id=${phase.messageId}")
                                }
                        }
                    }
                }
                Thread.sleep(NavProtocol.HEARTBEAT_PERIOD_MS)
            } catch (interrupted: InterruptedException) {
                if (!session.running || Thread.currentThread() !== thread) return
                // UPDATE interrupted a sleep/node wait: send the newest snapshot next round.
            } catch (t: Throwable) {
                android.util.Log.w("MiRingService", "heartbeat round failed: ${t.message}")
            }
        }
    }

    /** Re-evaluates the heartbeat lease; both reason suppliers run inside the lease's lock. */
    private fun syncCpuLease() {
        val lease = cpuLease ?: return
        lease.sync(SystemClock.elapsedRealtime(), { ProbeRuntime.session.latestState }) {
            val sequence = testSequence
            sequence != null && !sequence.isFinished()
        }
    }

    private fun stopHeartbeatThread() {
        val current = thread
        thread = null
        current?.interrupt()
        // Stop and the worker's finally can race; CpuLease.release is idempotent and only
        // frees its own lock. No lease survives STOP/onDestroy.
        cpuLease?.release()
        cpuLease = null
    }

    override fun onDestroy() {
        stopHeartbeatThread()
        unregisterSystemEventReceiver()
        super.onDestroy()
    }

    /**
     * Installs the de-identified log sink on the process-wide timeline. The sink is
     * re-installed with the identical lambda on purpose: several components record into the
     * one shared timeline, and this keeps `Log.i MiRingTimeline` alive regardless of order.
     */
    private fun installTimelineObserver() {
        RuntimeTimeline.shared.setObserver { entry ->
            Log.i(TIMELINE_TAG, "${entry.event} ${entry.detail}")
        }
    }

    /** Registers the screen/idle/power-save receiver, not exported to any other app on API 33+. */
    private fun registerSystemEventReceiver() {
        if (systemEventsRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(systemEventReceiver, filter)
        }
        systemEventsRegistered = true
    }

    /**
     * Paired teardown of [registerSystemEventReceiver]. The shared timeline observer is left
     * installed so other components keep logging after this service stops.
     */
    private fun unregisterSystemEventReceiver() {
        if (!systemEventsRegistered) return
        systemEventsRegistered = false
        runCatching { unregisterReceiver(systemEventReceiver) }
    }

    /** Maps one watched system broadcast to its timeline category plus the current flag. */
    private fun recordSystemEvent(action: String?) {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val event = when (action) {
            Intent.ACTION_SCREEN_ON -> TimelineEvent.SCREEN_ON
            Intent.ACTION_SCREEN_OFF -> TimelineEvent.SCREEN_OFF
            Intent.ACTION_USER_PRESENT -> TimelineEvent.SCREEN_UNLOCK
            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> TimelineEvent.DEVICE_IDLE_CHANGED
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> TimelineEvent.POWER_SAVE_CHANGED
            else -> return
        }
        RuntimeTimeline.shared.record(
            System.currentTimeMillis(),
            SystemClock.elapsedRealtime(),
            event,
            systemEventDetail(event, power),
        )
    }

    /** Category-level detail for a system event: one boolean at most, never free text. */
    private fun systemEventDetail(event: TimelineEvent, power: PowerManager): String = when (event) {
        TimelineEvent.DEVICE_IDLE_CHANGED -> "idle=" + flag(power.isDeviceIdleMode)
        TimelineEvent.POWER_SAVE_CHANGED -> "save=" + flag(power.isPowerSaveMode)
        else -> ""
    }

    /** Records the four booleans of one heartbeat round; it is not a frame count. */
    private fun recordSendRound(transport: XiaomiWearableTransport, lease: CpuLease) {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        RuntimeTimeline.shared.recordSendRound(
            System.currentTimeMillis(),
            SystemClock.elapsedRealtime(),
            SendRound(
                deviceIdle = power.isDeviceIdleMode,
                interactive = power.isInteractive,
                wakeLockHeld = lease.isHeld,
                nodePresent = transport.currentNodeId != null,
            ),
        )
    }

    /** Short boolean for a timeline detail, matching the shared timeline's own encoding. */
    private fun flag(value: Boolean): String = if (value) "1" else "0"

    private fun decodeState(raw: String): StateMessage =
        NavCodec.decodeFromString(raw) as StateMessage

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("MapRing 导航桥")
            .setContentText("Google 地图导航同步已开启")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "导航同步",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "保持探针通信会话" }
            )
        }
    }
}
