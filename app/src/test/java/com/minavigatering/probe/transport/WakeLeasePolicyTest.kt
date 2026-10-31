package com.minavigatering.probe.transport

import com.minavigatering.probe.protocol.NavProtocol
import com.minavigatering.probe.protocol.StateMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM test for the wakelock lease policy (work order W-731).
 *
 * Every [StateMessage] below is a **synthetic fixture** built in this file: it is neither a
 * real Maps notification sample nor a device reading. The test proves only the local hold
 * rule, driven entirely by caller-supplied elapsed times so no system clock is involved.
 */
class WakeLeasePolicyTest {

    /** Synthetic frame shape; [id] selects live-looking vs probe-looking identity. */
    private fun frame(
        id: String = "syn-turn-1",
        navStatus: String = NavProtocol.STATUS_ACTIVE,
        heartbeat: Boolean = false,
    ) = StateMessage(
        id = id,
        sentAtMs = 1_700_000_000_000,
        link = NavProtocol.LINK_CONNECTED,
        navStatus = navStatus,
        action = "right",
        distanceM = 300,
        roadName = "合成道路",
        actionId = "syn-action-1",
        mode = NavProtocol.MODE_WALK,
        heartbeat = heartbeat,
    )

    /** One isolated evaluation on a fresh instance, so an earlier reason cannot leak grace in. */
    private fun holds(latest: StateMessage?, forced: Boolean = false, noticeShowing: Boolean = false): Boolean {
        val policy = WakeLeasePolicy()
        policy.mapsNavigationPresent = noticeShowing
        return policy.shouldHold(nowElapsedMs = 1_000, latest = latest, forced = forced)
    }

    @Test
    fun `only a live active frame, a showing notice or a forced call justifies a hold`() {
        assertFalse("no reason at all must not hold", holds(null))
        assertTrue("a live active navigation frame holds", holds(frame()))
        assertFalse("idle alone is not a reason", holds(frame(navStatus = NavProtocol.STATUS_IDLE)))
        assertFalse("ended alone is not a reason", holds(frame(navStatus = NavProtocol.STATUS_ENDED)))
        assertFalse(
            "unrecognized alone is not a reason",
            holds(frame(navStatus = NavProtocol.STATUS_UNRECOGNIZED)),
        )
        assertFalse("a heartbeat is not navigation content", holds(frame(heartbeat = true)))
        assertFalse(
            "a synthetic probe frame is not a real navigation identity",
            holds(frame(id = TimedProbeSequence.ID_PREFIX + "runA-1")),
        )
        assertTrue(
            "a showing Maps notice holds even when the latest snapshot is unrecognized",
            holds(frame(navStatus = NavProtocol.STATUS_UNRECOGNIZED), noticeShowing = true),
        )
        assertTrue("a forced call holds with no snapshot at all", holds(null, forced = true))
    }

    @Test
    fun `the lease survives the grace window and expires exactly at its boundary`() {
        val policy = WakeLeasePolicy()
        val reasonAt = 5_000L
        assertTrue(policy.shouldHold(nowElapsedMs = reasonAt, latest = frame(), forced = false))
        assertTrue(
            "one millisecond inside the grace window still holds",
            policy.shouldHold(reasonAt + WakeLeasePolicy.RELEASE_GRACE_MS - 1, latest = null, forced = false),
        )
        assertFalse(
            "exactly at the grace boundary the lease is released",
            policy.shouldHold(reasonAt + WakeLeasePolicy.RELEASE_GRACE_MS, latest = null, forced = false),
        )

        val nextReasonAt = reasonAt + WakeLeasePolicy.RELEASE_GRACE_MS + 60_000
        assertTrue(
            "a new reason re-acquires the lease immediately",
            policy.shouldHold(nowElapsedMs = nextReasonAt, latest = frame(), forced = false),
        )
        assertTrue(
            "the renewed grace window is counted from the new reason, not the old one",
            policy.shouldHold(nextReasonAt + WakeLeasePolicy.RELEASE_GRACE_MS - 1, latest = null, forced = false),
        )
        assertFalse(
            "the renewed grace window ends at its own boundary",
            policy.shouldHold(nextReasonAt + WakeLeasePolicy.RELEASE_GRACE_MS, latest = null, forced = false),
        )
        assertFalse(
            "a fresh instance that never had a reason does not hold",
            WakeLeasePolicy().shouldHold(nowElapsedMs = 1L, latest = null, forced = false),
        )
    }
}
