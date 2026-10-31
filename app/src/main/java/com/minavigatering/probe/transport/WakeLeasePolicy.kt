package com.minavigatering.probe.transport

import com.minavigatering.probe.protocol.NavProtocol
import com.minavigatering.probe.protocol.StateMessage

/**
 * Decides whether the CPU wakelock must stay held (work order W-731).
 *
 * Pure Kotlin on purpose: no `android.*` and no `com.xiaomi.*`, and no system clock read —
 * the caller passes `nowElapsedMs`, so the hold/release boundary is host-testable with
 * synthetic fixtures. A hold reason is one of: a recognised Maps navigation notification is
 * currently showing, the caller forces it, or the newest snapshot is a concrete active
 * navigation frame that is not a synthetic regression probe frame.
 */
class WakeLeasePolicy {

    /** Written by the notification listener: at least one recognised Maps navigation notice shows. */
    @Volatile
    var mapsNavigationPresent: Boolean = false

    /** Elapsed timestamp of the most recent evaluation that had a hold reason; null if none. */
    private var lastReasonElapsedMs: Long? = null

    /**
     * True when the recent past already justifies holding; it does not start a new reason.
     * The grace window is exclusive at the far end: `RELEASE_GRACE_MS - 1` still holds,
     * `RELEASE_GRACE_MS` no longer does.
     */
    @Synchronized
    fun shouldHold(nowElapsedMs: Long, latest: StateMessage?, forced: Boolean): Boolean {
        if (hasHoldReason(latest, forced)) {
            lastReasonElapsedMs = nowElapsedMs
            return true
        }
        val lastReason = lastReasonElapsedMs ?: return false
        return nowElapsedMs - lastReason < RELEASE_GRACE_MS
    }

    /** Maps notice present, forced hold, or a real active navigation frame (not a probe frame). */
    private fun hasHoldReason(latest: StateMessage?, forced: Boolean): Boolean =
        mapsNavigationPresent || forced || isLiveActiveFrame(latest)

    private fun isLiveActiveFrame(latest: StateMessage?): Boolean =
        latest != null &&
            !latest.heartbeat &&
            latest.navStatus == NavProtocol.STATUS_ACTIVE &&
            !latest.id.startsWith(TimedProbeSequence.ID_PREFIX)

    companion object {
        /** How long the lease survives after the last reason; boundary is exclusive. */
        const val RELEASE_GRACE_MS = 30_000L
    }
}
