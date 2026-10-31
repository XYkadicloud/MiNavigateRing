package com.minavigatering.probe.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM test for the in-memory runtime timeline (work orders W-707, W-711).
 *
 * Everything here is an in-memory synthetic fixture: the clocks are supplied by the test,
 * so no device, SDK or real Maps notification is involved. It proves the two rings (state
 * capacity, traffic capacity, newest-first merge, oldest-first drop), the per-category
 * last-seen/cumulative counters that survive ring eviction, and the exact 60 s window
 * counters (notifications, frames, acks and the ack round trip).
 *
 * Deliberate assertion changes of W-711 (the rest are untouched):
 * - the 250-notification burst now fills the **traffic** ring, so the capacity assertion
 *   reads [RuntimeTimeline.TRAFFIC_RING_CAPACITY] and the oldest kept stamp is 130, not 50;
 * - the window is exactly [RuntimeTimeline.WINDOW_MS] ending at the query instant, so a
 *   record exactly 60 000 ms old is still inside it and the notification window count is
 *   the baseline 2; only a future stamp (even one in the same millisecond as the query)
 *   or a stamp older than the window is excluded.
 *
 * The single test method above delegates to the private scenes below, one per boundary, so
 * no function grows past the 40 line budget.
 */
class RuntimeTimelineTest {

    private val wall = 1_700_000_000_000L

    @Test
    fun `two rings keep state and traffic apart and the window counters follow their stamps`() {
        assertRingCapacityAndNewestFirstOrder()
        assertDetailIsCappedAtTheLimit()
        assertWindowCountersAndAckRtt()
        assertFutureStampsInTheSameMillisecondAreExcluded()
        assertOlderStampAppendedLaterKeepsBothWindowsAnswerable()
        assertStateRingSurvivesATrafficFlood()
        assertLastSeenAndTotalCountIgnoreRingEviction()
        assertWindowCountsTenFramesPerSecond()
        assertObserverSeesEntriesOutsideTheLock()
    }

    private fun assertRingCapacityAndNewestFirstOrder() {
        val timeline = RuntimeTimeline()

        // 250 notification events: notifications are traffic, so only the newest
        // TRAFFIC_RING_CAPACITY of them stay and the oldest ones are dropped.
        repeat(250) { index ->
            timeline.record(
                wallMs = wall + index,
                elapsedMs = index.toLong(),
                event = TimelineEvent.MAPS_NOTIFICATION_ACTIVE,
                detail = "synthetic #$index",
            )
        }

        val all = timeline.trafficSnapshot(RuntimeTimeline.TRAFFIC_RING_CAPACITY + 50)
        assertEquals("traffic capacity", RuntimeTimeline.TRAFFIC_RING_CAPACITY, all.size)
        // Newest first: the last recorded entry comes first, 130 old notifications are gone.
        assertEquals(249L, all.first().timestamp.elapsedMs)
        assertEquals(130L, all.last().timestamp.elapsedMs)
        assertTrue("newest first", all.first().timestamp.elapsedMs > all.last().timestamp.elapsedMs)
        assertEquals("no state entry was recorded", 0, timeline.stateSnapshot().size)
        // The merged snapshot keeps the same newest-first order and limit semantics.
        val merged = timeline.snapshot(RuntimeTimeline.TOTAL_RING_CAPACITY)
        assertEquals(RuntimeTimeline.TRAFFIC_RING_CAPACITY, merged.size)
        assertEquals(249L, merged.first().timestamp.elapsedMs)
        assertEquals("a negative limit keeps nothing", 0, timeline.snapshot(-3).size)
        assertEquals(0, timeline.snapshot(0).size)
        assertEquals("a negative state limit keeps nothing", 0, timeline.stateSnapshot(-1).size)
        assertEquals("a negative traffic limit keeps nothing", 0, timeline.trafficSnapshot(-1).size)
    }

    private fun assertDetailIsCappedAtTheLimit() {
        val timeline = RuntimeTimeline()
        // Detail stays under the 60 character cap even for a long synthetic text.
        timeline.record(wall, 1_000L, TimelineEvent.RECOVERY_RESULT, "x".repeat(200))
        val longest = timeline.snapshot(1).first().detail
        assertTrue("detail length ${longest.length}", longest.length <= RuntimeTimeline.MAX_DETAIL_CHARS)
    }

    private fun assertWindowCountersAndAckRtt() {
        // Window counters: every record no older than WINDOW_MS at the query instant counts,
        // so a record exactly WINDOW_MS old is still inside the window.
        val window = RuntimeTimeline()
        window.record(wall, 1_000L, TimelineEvent.MAPS_NOTIFICATION_ENDED)
        window.record(wall, 2_000L, TimelineEvent.MAPS_NOTIFICATION_IGNORED)
        window.record(wall, 3_000L, TimelineEvent.SEND_ROUND)
        window.recordFrameSent(wall, 4_000L, "frame-abcdef")
        window.recordFrameSent(wall, 5_000L, "frame-222222")
        window.recordAck(wall, 6_000L, AckRecord("probe-1", 120L))
        window.recordAck(wall, 7_000L, AckRecord("probe-2", 240L))
        // Stale (older than the window) and future (ahead of now) entries must be excluded.
        window.record(wall, 500L, TimelineEvent.MAPS_NOTIFICATION_ACTIVE)
        window.record(wall, 500_000L, TimelineEvent.MAPS_NOTIFICATION_ACTIVE)
        window.recordAck(wall, 500L, AckRecord("probe-3", 9_999L))

        val now = 61_000L
        val stats = window.statistics(now)
        assertEquals("notifications in window", 2, stats.window.notifications)
        assertEquals("frames sent in window", 2, stats.window.framesSent)
        assertEquals("acks in window", 2, stats.window.acks)
        assertEquals(2, stats.ackRtt.sampleCount)
        assertEquals("latest rtt", 240L, stats.ackRtt.latestMs)
        assertEquals("average rtt", (120L + 240L) / 2, stats.ackRtt.averageMs)

        // One minute later the whole window is empty: no counter, no rtt.
        val empty = window.statistics(now + RuntimeTimeline.WINDOW_MS + 1)
        assertEquals(TimelineWindowCounts(0, 0, 0), empty.window)
        assertNull(empty.ackRtt.latestMs)
        assertNull(empty.ackRtt.averageMs)
    }

    /**
     * Stamps that lie in the *same millisecond* as the query count, but a stamp recorded one
     * millisecond later must not: the future is excluded for notifications, frames, acks and
     * the ack round trip alike, and it starts counting only once the query reaches it.
     */
    private fun assertFutureStampsInTheSameMillisecondAreExcluded() {
        val timeline = RuntimeTimeline()
        timeline.record(wall, 10_000L, TimelineEvent.MAPS_NOTIFICATION_ACTIVE)
        timeline.recordFrameSent(wall, 10_000L, "now-frame")
        timeline.recordAck(wall, 10_000L, AckRecord("now-ack", 50L))
        // Same second, but a later elapsed stamp: it does not belong to the query instant.
        timeline.record(wall, 10_001L, TimelineEvent.MAPS_NOTIFICATION_ENDED)
        timeline.recordFrameSent(wall, 10_001L, "future-frame")
        timeline.recordAck(wall, 10_001L, AckRecord("future-ack", 700L))

        val stats = timeline.statistics(10_000L)
        assertEquals("same-ms notifications count", 1, stats.window.notifications)
        assertEquals("future frame excluded", 1, stats.window.framesSent)
        assertEquals("future ack excluded", 1, stats.window.acks)
        assertEquals("future rtt excluded", 1, stats.ackRtt.sampleCount)
        assertEquals(50L, stats.ackRtt.latestMs)
        assertEquals(50L, stats.ackRtt.averageMs)

        val reached = timeline.statistics(10_001L)
        assertEquals("the future stamp counts once reached", 2, reached.window.notifications)
        assertEquals(2, reached.window.framesSent)
        assertEquals(2, reached.window.acks)
        assertEquals("the newer rtt becomes latest", 700L, reached.ackRtt.latestMs)
    }

    /**
     * Two frames more than one window apart, appended in reverse order. Under the old design
     * both landed in one shared second-bucket slot, so the later older append erased the
     * newer stamp; the stamp rings must not. Neither stamp overwrites the other, and a query
     * never evicts anything, in any query order. Stamps here reproduce the r1 regressions.
     */
    private fun assertOlderStampAppendedLaterKeepsBothWindowsAnswerable() {
        val timeline = RuntimeTimeline()
        timeline.recordFrameSent(wall, 61_900L, "valid-frame")
        timeline.recordFrameSent(wall, 1_000L, "older-frame")

        // r1: a frame at 61 900 must not be counted when the query instant is 61 100 (future),
        // and the later older append at 1 000 must not erase it when the query reaches 61 900.
        assertEquals("the future frame is excluded at 61 100", 0, timeline.statistics(61_100L).window.framesSent)
        assertEquals("the newer stamp survived the later older append",
            1, timeline.statistics(61_900L).window.framesSent)
        // Queried out of order, the older stamp still answers for its own window while the
        // newer one is future there, so the two records never overwrite each other.
        assertEquals("the older stamp answers for its own window", 1, timeline.statistics(1_000L).window.framesSent)
        assertEquals("before both stamps", 0, timeline.statistics(999L).window.framesSent)

        // A third stamp far after both: it too overwrites neither earlier window.
        timeline.recordFrameSent(wall, 200_000L, "far-frame")
        assertEquals("the far stamp does not touch the pair", 1, timeline.statistics(61_900L).window.framesSent)
        assertEquals("the pair does not touch the far stamp", 1, timeline.statistics(200_000L).window.framesSent)
    }

    /** 1000 traffic entries must not evict any of the eight system lifecycle categories. */
    private fun assertStateRingSurvivesATrafficFlood() {
        val timeline = RuntimeTimeline()
        for ((index, event) in STATE_EVENTS.withIndex()) {
            timeline.record(wall, index.toLong(), event, "state #$index")
        }
        repeat(1_000) { index ->
            timeline.recordFrameSent(wall, 10_000L + index, "flood-$index")
        }

        val states = timeline.stateSnapshot(RuntimeTimeline.STATE_RING_CAPACITY)
        assertEquals("all eight state categories survive", STATE_EVENTS.size, states.size)
        assertEquals(STATE_EVENTS.toSet(), states.map { it.event }.toSet())
        assertEquals("traffic ring is still capped", RuntimeTimeline.TRAFFIC_RING_CAPACITY,
            timeline.trafficSnapshot(RuntimeTimeline.TRAFFIC_RING_CAPACITY).size)
    }

    /** Last seen stamp and cumulative count stay exact even after the traffic ring evicted. */
    private fun assertLastSeenAndTotalCountIgnoreRingEviction() {
        val timeline = RuntimeTimeline()
        repeat(500) { index -> timeline.record(wall + index, index.toLong(), TimelineEvent.SEND_ROUND) }
        timeline.record(wall + 700, 700L, TimelineEvent.SCREEN_OFF, "screen-off")

        assertEquals("every round is counted", 500L, timeline.totalCount(TimelineEvent.SEND_ROUND))
        assertEquals("newest round stamp survives eviction",
            EntryTimestamp(wall + 499, 499L), timeline.lastSeen(TimelineEvent.SEND_ROUND))
        assertEquals(1L, timeline.totalCount(TimelineEvent.SCREEN_OFF))
        assertEquals(EntryTimestamp(wall + 700, 700L), timeline.lastSeen(TimelineEvent.SCREEN_OFF))
        assertNull("a category that never happened has no stamp",
            timeline.lastSeen(TimelineEvent.ACK_RECEIVED))
        assertEquals(0L, timeline.totalCount(TimelineEvent.ACK_RECEIVED))
    }

    /** Ten frames per second for 60 seconds: the window counts 600 while the ring keeps 120. */
    private fun assertWindowCountsTenFramesPerSecond() {
        val timeline = RuntimeTimeline()
        for (second in 1..FRAME_BURST_SECONDS) {
            repeat(10) { index -> timeline.recordFrameSent(wall, second * 1_000L + index, "f$second-$index") }
        }

        val frames = timeline.statistics(FRAME_BURST_END_ELAPSED_MS)
        assertEquals("ten frames a second for sixty seconds", 600, frames.window.framesSent)
        assertEquals("the traffic ring itself still holds only its capacity",
            RuntimeTimeline.TRAFFIC_RING_CAPACITY,
            timeline.trafficSnapshot(RuntimeTimeline.TRAFFIC_RING_CAPACITY).size)
        assertEquals(TimelineWindowCounts(0, 0, 0), timeline.statistics(EMPTY_WINDOW_ELAPSED_MS).window)
    }

    private fun assertObserverSeesEntriesOutsideTheLock() {
        // The observer sees every entry and is called outside the lock, so it may snapshot.
        val observed = mutableListOf<TimelineEvent>()
        val seen = RuntimeTimeline()
        seen.setObserver { entry -> observed.add(entry.event) }
        seen.record(wall, 10L, TimelineEvent.SCREEN_ON)
        seen.recordSendRound(
            wall,
            11L,
            SendRound(deviceIdle = true, interactive = false, wakeLockHeld = true, nodePresent = false),
        )
        assertEquals(listOf(TimelineEvent.SCREEN_ON, TimelineEvent.SEND_ROUND), observed)
        assertEquals(2, seen.snapshot().size)
        assertEquals(1, seen.stateSnapshot().size)
        assertEquals(1, seen.trafficSnapshot().size)
    }

    private companion object {
        /** Named duration of the frame burst: 60 seconds, the full width of one window. */
        const val FRAME_BURST_SECONDS = 60

        /** Query instant just after the last frame of the burst. */
        const val FRAME_BURST_END_ELAPSED_MS = 60_010L

        /** Query instant one window width after the burst, when its window is already empty. */
        const val EMPTY_WINDOW_ELAPSED_MS = 120_011L

        /** The eight categories the state ring owns, in enum order. */
        val STATE_EVENTS = listOf(
            TimelineEvent.SCREEN_ON,
            TimelineEvent.SCREEN_OFF,
            TimelineEvent.SCREEN_UNLOCK,
            TimelineEvent.DEVICE_IDLE_CHANGED,
            TimelineEvent.POWER_SAVE_CHANGED,
            TimelineEvent.LISTENER_CONNECTED,
            TimelineEvent.LISTENER_DISCONNECTED,
            TimelineEvent.RECOVERY_RESULT,
        )
    }
}
