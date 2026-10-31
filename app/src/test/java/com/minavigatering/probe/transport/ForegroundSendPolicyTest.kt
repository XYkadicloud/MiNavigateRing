package com.minavigatering.probe.transport

import com.minavigatering.probe.navigation.strippedRecovery
import com.minavigatering.probe.protocol.AckMessage
import com.minavigatering.probe.protocol.NavProtocol
import com.minavigatering.probe.protocol.StateMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM test for the foreground content policy (work order W-643).
 *
 * Every state below is a **synthetic fixture** built in this file — it is neither a real
 * Maps notification sample nor a watch acknowledgement. The test only proves the local
 * bookkeeping: which snapshot would be handed to the transport, and that heartbeats can
 * never be mistaken for navigation content or a receipt.
 */
class ForegroundSendPolicyTest {

    private fun content(id: String, distanceM: Int?) = StateMessage(
        id = id,
        sentAtMs = 1_700_000_000_000,
        link = NavProtocol.LINK_CONNECTED,
        navStatus = NavProtocol.STATUS_ACTIVE,
        action = "right",
        distanceM = distanceM,
        roadName = "合成道路",
        actionId = "synthetic-turn-1",
        mode = NavProtocol.MODE_WALK,
        heartbeat = false,
    )

    @Test
    fun `newest content only, heartbeats never pose as navigation or receipts`() {
        val policy = ForegroundSendPolicy()

        // Two concurrent non-heartbeat offers: the later one replaces the earlier one.
        policy.offer(content("syn-1", 300))
        policy.offer(content("syn-2", 280))
        val first = policy.takeContent(nowMs = 1_000, nodeKnown = true, latest = null)
        assertEquals("the older pending snapshot must be overwritten", "syn-2", first?.id)
        assertFalse(first!!.heartbeat)

        // No new pending and well inside the replay interval: nothing to send.
        assertNull(
            "a fresh content round within 10s must not repeat itself",
            policy.takeContent(nowMs = 5_000, nodeKnown = true, latest = content("syn-2", 280)),
        )

        // A session restart forces a resend of the latest non-heartbeat content.
        policy.onSessionRestart()
        assertEquals(
            "restart must resend the last content",
            "syn-2",
            policy.takeContent(nowMs = 6_000, nodeKnown = true, latest = content("syn-2", 280))?.id,
        )

        // A heartbeat offer is ignored: it is not content and cannot become pending.
        policy.onSessionRestart()
        policy.offer(content("hb-1", 1).copy(heartbeat = true))
        assertNull(
            "a heartbeat shape is not pending content",
            policy.takeContent(nowMs = 20_000, nodeKnown = true, latest = content("hb-1", 1).copy(heartbeat = true)),
        )

        // An unknown node consumes nothing: the pending slot survives for the next round.
        policy.offer(content("syn-3", 120))
        assertNull(policy.takeContent(nowMs = 30_000, nodeKnown = false, latest = null))
        assertTrue("node known with pending content must skip the node wait", policy.shouldSkipNodeWait(true))
        assertFalse("an unknown node has nothing to short-circuit", policy.shouldSkipNodeWait(false))
        assertEquals(
            "the pending snapshot must survive an unknown node",
            "syn-3",
            policy.takeContent(nowMs = 30_000, nodeKnown = true, latest = null)?.id,
        )

        // Heartbeat UPDATE stays out of latestState and instructionIds; SDK task success and
        // a heartbeat-shaped inbound ack are not band receipts.
        val session = ProbeSession()
        val start = content("syn-1", 300)
        session.start(start)
        assertEquals(300, session.latestState?.distanceM)
        applyServiceCommand(session, ProbeActions.UPDATE, start.copy(id = "hb-9", heartbeat = true, distanceM = 1))
        assertEquals("a heartbeat UPDATE must not move the stored distance", 300, session.latestState?.distanceM)
        assertFalse("hb-9 must not be remembered as an instruction", "hb-9" in session.instructionIds)
        session.onTaskSuccess("hb-9")
        assertEquals(ProbeSession.NO_ACK_YET, session.lastAckText)
        assertFalse(session.onInbound(AckMessage(id = "ack-hb", refId = "hb-9", sentAtMs = 1)))
        assertEquals(ProbeSession.NO_ACK_YET, session.lastAckText)

        // The cold-start snapshot keeps no action identity at all.
        val restored = strippedRecovery(start, now = 1_700_000_009_000, mode = NavProtocol.MODE_CYCLE)
        assertNull(restored.action)
        assertNull(restored.exitNumber)
        assertNull(restored.distanceM)
        assertNull(restored.roadName)
        assertNull(restored.actionId)
        assertEquals(NavProtocol.STATUS_UNRECOGNIZED, restored.navStatus)
        assertFalse(restored.heartbeat)
        assertEquals(NavProtocol.MODE_CYCLE, restored.mode)
        assertTrue("restored id: ${restored.id}", restored.id.startsWith("restore-"))
    }
}
