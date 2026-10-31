package com.minavigatering.probe.transport

/**
 * Kinds of events the in-memory runtime timeline keeps (work orders W-707, W-711).
 *
 * The set is deliberately closed and coarse, and no category carries a node id, account
 * name, secret, notification text, road name or coordinate. `SEND_ROUND` (one heartbeat
 * round) and `FRAME_SENT` (one queued payload) stay apart: a frame count must never be
 * derived from the number of loop rounds.
 */
enum class TimelineEvent {
    SCREEN_ON,
    SCREEN_OFF,
    SCREEN_UNLOCK,
    DEVICE_IDLE_CHANGED,
    POWER_SAVE_CHANGED,
    LISTENER_CONNECTED,
    LISTENER_DISCONNECTED,
    MAPS_NOTIFICATION_ACTIVE,
    MAPS_NOTIFICATION_UNRECOGNIZED,
    MAPS_NOTIFICATION_ENDED,
    MAPS_NOTIFICATION_IGNORED,
    SEND_ROUND,
    FRAME_SENT,
    SDK_TASK_SUCCEEDED,
    SDK_TASK_FAILED,
    ACK_RECEIVED,
    RECOVERY_RESULT,
    ;

    /** True for the four Maps categories that count as "one notification arrived". */
    val isMapsNotification: Boolean
        get() = this == MAPS_NOTIFICATION_ACTIVE ||
            this == MAPS_NOTIFICATION_UNRECOGNIZED ||
            this == MAPS_NOTIFICATION_ENDED ||
            this == MAPS_NOTIFICATION_IGNORED
}

/** The two clocks of one entry: wall clock and monotonic elapsedRealtime, both in ms. */
data class EntryTimestamp(val wallMs: Long, val elapsedMs: Long)

/** One heartbeat-loop round: only the four booleans the diagnostic reader needs. */
data class SendRound(
    val deviceIdle: Boolean,
    val interactive: Boolean,
    val wakeLockHeld: Boolean,
    val nodePresent: Boolean,
)

/**
 * One SDK `Task` outcome. [messageId] is shortened to its last six characters when the
 * entry detail is built; [reasonCategory] is always a [taskReasonCategory] value.
 */
data class SdkTaskOutcome(val succeeded: Boolean, val messageId: String, val reasonCategory: String)

/**
 * One watch ack. Only the last six characters of [refId] reach the entry detail; [rttMs]
 * is the measured send-to-ack round trip.
 */
data class AckRecord(val refId: String, val rttMs: Long)

/**
 * One immutable timeline entry. [detail] is already de-identified when it is built;
 * [ackRttMs] is set only for [TimelineEvent.ACK_RECEIVED], so the statistics average real
 * measurements instead of parsing text.
 */
data class TimelineEntry(
    val timestamp: EntryTimestamp,
    val event: TimelineEvent,
    val detail: String,
    val ackRttMs: Long? = null,
)

/** Event counts of the last [RuntimeTimeline.WINDOW_MS] before a given elapsed stamp. */
data class TimelineWindowCounts(val notifications: Int, val framesSent: Int, val acks: Int)

/** Round-trip measurements in the same window; latest/average are null when it is empty. */
data class AckRttStatistics(val sampleCount: Int, val latestMs: Long?, val averageMs: Long?)

/** Result of [RuntimeTimeline.statistics], composed so no constructor needs five parameters. */
data class TimelineStatistics(val window: TimelineWindowCounts, val ackRtt: AckRttStatistics)

/** Width of the id fragment a timeline entry may carry: the trailing characters only. */
private const val ID_SUFFIX_CHARS = 6

/** Last [ID_SUFFIX_CHARS] characters of an id: the only id fragment an entry may carry. */
internal fun shortTimelineId(id: String): String =
    if (id.length <= ID_SUFFIX_CHARS) id else id.takeLast(ID_SUFFIX_CHARS)

/** True for the eight categories the state ring owns; every other category is traffic. */
private fun isStateEvent(event: TimelineEvent): Boolean = when (event) {
    TimelineEvent.SCREEN_ON,
    TimelineEvent.SCREEN_OFF,
    TimelineEvent.SCREEN_UNLOCK,
    TimelineEvent.DEVICE_IDLE_CHANGED,
    TimelineEvent.POWER_SAVE_CHANGED,
    TimelineEvent.LISTENER_CONNECTED,
    TimelineEvent.LISTENER_DISCONNECTED,
    TimelineEvent.RECOVERY_RESULT,
    -> true

    else -> false
}

/**
 * One ring slot: the entry plus its append order, which breaks ties when two entries share
 * one elapsed millisecond so the newest recorded one still comes first.
 */
private class RingSlot(val sequence: Long, val entry: TimelineEntry)

/**
 * One ack round trip kept for the statistics window: when it arrived and how long it took.
 * The stamp is the same monotonic elapsed millisecond the window filter compares against.
 */
private class RttSample(val elapsedMs: Long, val rttMs: Long)

/**
 * Thread-safe, in-memory event timeline with an exact 60 s window (W-707, W-711).
 *
 * Pure Kotlin on purpose: no `android.*` import, so host JVM tests drive every boundary
 * without a device, and the Android sink (`Log.i MiRingTimeline`) is installed later
 * through [setObserver]. Two independent rings keep a traffic flood from evicting system
 * history: the state ring holds the eight lifecycle categories, the traffic ring the nine
 * transport categories; [snapshot] merges both, newest first. The window counters live in
 * per-category stamp rings, so they never depend on the history rings either. Clocks are
 * supplied by the caller, so the timeline never depends on a platform clock.
 */
class RuntimeTimeline {

    companion object {
        /** Capacity of the state ring (system lifecycle categories). */
        const val STATE_RING_CAPACITY = 200

        /** Capacity of the traffic ring (rounds, frames, tasks, acks, notifications). */
        const val TRAFFIC_RING_CAPACITY = 120

        /** Capacity of both rings together: the upper bound of a merged [snapshot]. */
        const val TOTAL_RING_CAPACITY = STATE_RING_CAPACITY + TRAFFIC_RING_CAPACITY

        /** Compatible alias of the state ring capacity, kept for existing callers. */
        const val MAX_ENTRIES = STATE_RING_CAPACITY

        /** Default length of [snapshot]; callers may ask for any length up to the capacity. */
        const val DEFAULT_SNAPSHOT_LIMIT = 60

        /** Width of the statistics window: exactly this span of elapsed milliseconds counts. */
        const val WINDOW_MS = 60_000L

        /**
         * Capacity of each per-category window stamp ring (notifications, frames, acks).
         * It bounds memory without affecting the common case; more than this many stamps of
         * one category inside [WINDOW_MS] saturates the ring (its oldest stamps are dropped)
         * and that category's window count may then undercount. Twenty stamps per second for
         * a whole minute fill it exactly; anything beyond that saturates.
         */
        const val WINDOW_RING_CAPACITY = 1_200

        /**
         * Capacity of the ack round-trip sample ring. It saturates independently of the
         * ack stamp ring, so latest/average fall back to the newest retained samples when a
         * minute holds more than this many round trips.
         */
        const val RTT_SAMPLE_RING_CAPACITY = 600

        /** Hard cap of an entry detail; anything longer is truncated. */
        const val MAX_DETAIL_CHARS = 60

        /**
         * The one process-wide timeline. Android components install the log sink on it and
         * the shared [ProbeSession] records into it; it never touches Android itself.
         */
        val shared = RuntimeTimeline()
    }

    private val lock = Any()
    private val stateRing = ArrayDeque<RingSlot>(STATE_RING_CAPACITY)
    private val trafficRing = ArrayDeque<RingSlot>(TRAFFIC_RING_CAPACITY)
    private val notificationStamps = ArrayDeque<Long>(WINDOW_RING_CAPACITY)
    private val frameStamps = ArrayDeque<Long>(WINDOW_RING_CAPACITY)
    private val ackStamps = ArrayDeque<Long>(WINDOW_RING_CAPACITY)
    private val rttSamples = ArrayDeque<RttSample>(RTT_SAMPLE_RING_CAPACITY)
    private val totalCounts = LongArray(TimelineEvent.entries.size)
    private val lastSeenStamps = arrayOfNulls<EntryTimestamp>(TimelineEvent.entries.size)
    private var appendSequence = 0L

    @Volatile
    private var observer: (TimelineEntry) -> Unit = {}

    /**
     * Installs the diagnostic sink that receives every recorded entry. It runs outside the
     * timeline lock, so it may snapshot or record freely; the default sink does nothing.
     */
    fun setObserver(sink: (TimelineEntry) -> Unit) {
        observer = sink
    }

    /** Appends one category event with a short, already de-identified description. */
    fun record(wallMs: Long, elapsedMs: Long, event: TimelineEvent, detail: String = "") {
        append(TimelineEntry(EntryTimestamp(wallMs, elapsedMs), event, shorten(detail)))
    }

    /**
     * Appends one payload that the local channel accepted: only the last six characters of
     * [messageId] are stored. The [TimelineEvent.FRAME_SENT] counter is driven by this call
     * only, never by loop rounds.
     */
    fun recordFrameSent(wallMs: Long, elapsedMs: Long, messageId: String) {
        record(wallMs, elapsedMs, TimelineEvent.FRAME_SENT, "frame id=${shortTimelineId(messageId)}")
    }

    /** Appends one heartbeat-loop round; it never doubles as a frame count. */
    fun recordSendRound(wallMs: Long, elapsedMs: Long, round: SendRound) {
        append(TimelineEntry(EntryTimestamp(wallMs, elapsedMs), TimelineEvent.SEND_ROUND, roundDetail(round)))
    }

    /** Appends one SDK `Task` outcome; a Task success is still not a watch receipt. */
    fun recordTask(wallMs: Long, elapsedMs: Long, outcome: SdkTaskOutcome) {
        val event = if (outcome.succeeded) TimelineEvent.SDK_TASK_SUCCEEDED else TimelineEvent.SDK_TASK_FAILED
        append(TimelineEntry(EntryTimestamp(wallMs, elapsedMs), event, taskDetail(outcome)))
    }

    /** Appends one watch ack together with its measured round trip. */
    fun recordAck(wallMs: Long, elapsedMs: Long, ack: AckRecord) {
        val rttMs = ack.rttMs.coerceAtLeast(0L)
        append(
            TimelineEntry(
                EntryTimestamp(wallMs, elapsedMs),
                TimelineEvent.ACK_RECEIVED,
                ackDetail(ack, rttMs),
                rttMs,
            )
        )
    }

    /**
     * The newest entries of both rings merged, newest first. [limit] is clamped into
     * `0..TOTAL_RING_CAPACITY`: a larger limit returns everything still retained, a
     * non-positive one returns nothing.
     */
    fun snapshot(limit: Int = DEFAULT_SNAPSHOT_LIMIT): List<TimelineEntry> = synchronized(lock) {
        newestFirst(stateRing + trafficRing, limit.coerceIn(0, TOTAL_RING_CAPACITY))
    }

    /** The newest state entries, newest first; [limit] is clamped into the state capacity. */
    fun stateSnapshot(limit: Int = STATE_RING_CAPACITY): List<TimelineEntry> = synchronized(lock) {
        newestFirst(stateRing, limit.coerceIn(0, STATE_RING_CAPACITY))
    }

    /** The newest traffic entries, newest first; [limit] is clamped into the traffic capacity. */
    fun trafficSnapshot(limit: Int = TRAFFIC_RING_CAPACITY): List<TimelineEntry> = synchronized(lock) {
        newestFirst(trafficRing, limit.coerceIn(0, TRAFFIC_RING_CAPACITY))
    }

    /**
     * Wall and elapsed stamp of the most recent [event], or null when it never happened.
     * Kept per ordinal in a plain array, independent of both rings, so a traffic flood
     * cannot make a system event look like it never occurred.
     */
    fun lastSeen(event: TimelineEvent): EntryTimestamp? = synchronized(lock) {
        lastSeenStamps[event.ordinal]
    }

    /** How often [event] was recorded since this timeline was created; ring independent. */
    fun totalCount(event: TimelineEvent): Long = synchronized(lock) {
        totalCounts[event.ordinal]
    }

    /**
     * Counters of the exact [WINDOW_MS] window ending at [nowElapsedMs], read sample by
     * sample from the per-category stamp rings. A record counts only while
     * `elapsed <= now && now - elapsed <= WINDOW_MS`, so a future stamp (even one in the
     * same millisecond as the query) and an expired stamp are both ignored, and a stamp
     * exactly [WINDOW_MS] old is still counted. The query instant is arbitrary: past,
     * future and out-of-order queries never evict anything, so two stamps more than
     * [WINDOW_MS] apart keep answering for their own windows. The ack round trip uses the
     * same window; latest is the window's greatest elapsed sample and average its mean,
     * both null while the window holds no sample.
     */
    fun statistics(nowElapsedMs: Long): TimelineStatistics = synchronized(lock) {
        val windowStartMs = nowElapsedMs - WINDOW_MS
        val inWindow = rttSamples.filter { it.elapsedMs in windowStartMs..nowElapsedMs }
        val latest = latestSample(inWindow)
        TimelineStatistics(
            window = TimelineWindowCounts(
                notifications = countInWindow(notificationStamps, windowStartMs, nowElapsedMs),
                framesSent = countInWindow(frameStamps, windowStartMs, nowElapsedMs),
                acks = countInWindow(ackStamps, windowStartMs, nowElapsedMs),
            ),
            ackRtt = AckRttStatistics(
                sampleCount = inWindow.size,
                latestMs = latest?.rttMs,
                averageMs = if (inWindow.isEmpty()) null else inWindow.sumOf { it.rttMs } / inWindow.size,
            ),
        )
    }

    /** How many stamps of [ring] fall into the inclusive window `windowStartMs..nowElapsedMs`. */
    private fun countInWindow(ring: ArrayDeque<Long>, windowStartMs: Long, nowElapsedMs: Long): Int =
        ring.count { it >= windowStartMs && it <= nowElapsedMs }

    /** The window sample with the greatest elapsed stamp; equal stamps keep the newest append. */
    private fun latestSample(samples: List<RttSample>): RttSample? {
        var latest: RttSample? = null
        for (sample in samples) {
            if (latest == null || sample.elapsedMs >= latest.elapsedMs) latest = sample
        }
        return latest
    }

    private fun append(entry: TimelineEntry) {
        synchronized(lock) {
            val slot = RingSlot(++appendSequence, entry)
            if (isStateEvent(entry.event)) {
                stateRing.addLast(slot)
                trim(stateRing, STATE_RING_CAPACITY)
            } else {
                trafficRing.addLast(slot)
                trim(trafficRing, TRAFFIC_RING_CAPACITY)
            }
            lastSeenStamps[entry.event.ordinal] = entry.timestamp
            totalCounts[entry.event.ordinal]++
            recordInWindow(entry)
        }
        // Outside the lock: a sink may call snapshot()/record() without deadlocking.
        observer(entry)
    }

    private fun trim(ring: ArrayDeque<RingSlot>, capacity: Int) {
        while (ring.size > capacity) ring.removeFirst()
    }

    /**
     * Appends one entry to its own category stamp ring, which is independent of both history
     * rings. Nothing is decided at append time beyond capacity: the window is evaluated only
     * when [statistics] is called, so no query can evict a stamp.
     */
    private fun recordInWindow(entry: TimelineEntry) {
        val elapsedMs = entry.timestamp.elapsedMs
        when (entry.event) {
            TimelineEvent.FRAME_SENT -> appendStamp(frameStamps, elapsedMs)
            TimelineEvent.ACK_RECEIVED -> recordAckInWindow(entry, elapsedMs)
            else -> if (entry.event.isMapsNotification) appendStamp(notificationStamps, elapsedMs)
        }
    }

    private fun recordAckInWindow(entry: TimelineEntry, elapsedMs: Long) {
        appendStamp(ackStamps, elapsedMs)
        val rttMs = entry.ackRttMs ?: return
        rttSamples.addLast(RttSample(elapsedMs, rttMs))
        while (rttSamples.size > RTT_SAMPLE_RING_CAPACITY) rttSamples.removeFirst()
    }

    /** Appends one stamp to a window ring, dropping the oldest append once past capacity. */
    private fun appendStamp(ring: ArrayDeque<Long>, elapsedMs: Long) {
        ring.addLast(elapsedMs)
        while (ring.size > WINDOW_RING_CAPACITY) ring.removeFirst()
    }

    /** Newest-first projection of [slots]: elapsed descending, same millisecond by append order. */
    private fun newestFirst(slots: List<RingSlot>, limit: Int): List<TimelineEntry> {
        if (limit <= 0) return emptyList()
        return slots
            .sortedWith(
                compareByDescending<RingSlot> { it.entry.timestamp.elapsedMs }
                    .thenByDescending { it.sequence }
            )
            .take(limit)
            .map { it.entry }
    }

    private fun shorten(detail: String): String =
        if (detail.length <= MAX_DETAIL_CHARS) detail else detail.substring(0, MAX_DETAIL_CHARS)

    private fun flag(value: Boolean): String = if (value) "1" else "0"

    private fun roundDetail(round: SendRound): String =
        "idle=${flag(round.deviceIdle)} interactive=${flag(round.interactive)}" +
            " wakeLock=${flag(round.wakeLockHeld)} node=${flag(round.nodePresent)}"

    private fun taskDetail(outcome: SdkTaskOutcome): String =
        "ok=${flag(outcome.succeeded)} id=${shortTimelineId(outcome.messageId)}" +
            " reason=${outcome.reasonCategory}"

    private fun ackDetail(ack: AckRecord, rttMs: Long): String =
        "ref=${shortTimelineId(ack.refId)} rttMs=$rttMs"
}
