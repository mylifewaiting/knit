package app.getknit.knit.transfer

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The one Wi-Fi Direct radio, shared by whoever needs a group — a file transfer, or a call between neighbours
 * (`call/DirectCallPath`) — and owned by one of them at a time. [radio] has a single channel and a global
 * [DirectWifi.release]: it removes whatever group is up and hands the radio back to the mesh, and the transfer's
 * manager calls it on every end path, whether or not it ever hosted. So nobody uses [radio] directly; each user
 * holds the view [leasedTo] returns, which takes the lease on its first host or join and gives it back on its own
 * release. A view whose owner does not hold the lease is refused ([TransferRefusal.Busy]) and releases nothing.
 *
 * It is also where [gate] keeps a phone from hosting where hosting takes the phone down (ADR 2026-10.w3dw): every
 * group goes up through here, so a host the gate refuses never reaches `createGroup`, and the gate hears when a group
 * of ours goes up and comes down. A join is never refused for it.
 */
class DirectWifiLease(
    private val radio: DirectWifi,
    private val gate: HostGate = HostGate.Open,
    private val yieldWaitMs: Long = YIELD_WAIT_MS,
) {
    private val holder = AtomicReference<String?>(null)

    // Bumped when a transfer or a call asks the Wi-Fi hop plane to drop its group (ADR 2026-11.tz2v).
    private val yieldTick = MutableStateFlow(0)

    /**
     * Increments when someone other than [HOP] wants the radio and the hop plane holds it. The hop plane
     * collects this and releases; [host] and [join] then wait out [yieldWaitMs] for that release.
     */
    val yields: StateFlow<Int> = yieldTick.asStateFlow()

    // Whether the gate was told a group of ours is up, so the release that takes it down tells it back, once.
    private val hostingTold = AtomicBoolean(false)

    /** Who holds the radio right now, or null when it is free. */
    val owner: String? get() = holder.get()

    /** Whether this phone may host a group at all; joining someone else's is never refused for it. */
    fun mayHost(): Boolean = gate.mayHost() && radio.mayHost()

    /** The radio as [owner] sees it: [owner]'s groups only, and nothing of anyone else's. */
    fun leasedTo(owner: String): DirectWifi = Leased(owner)

    private inner class Leased(
        private val owner: String,
    ) : DirectWifi {
        private fun heldByOther(): Boolean = holder.get().let { it != null && it != owner }

        private fun take() {
            if (!holder.compareAndSet(null, owner) && holder.get() != owner) {
                throw DirectWifiException("Wi-Fi Direct is in use (${holder.get()})", TransferRefusal.Busy)
            }
        }

        override fun refusal(): TransferRefusal? = if (heldByOther()) TransferRefusal.Busy else radio.refusal()

        override fun mayHost(): Boolean = this@DirectWifiLease.mayHost()

        override suspend fun host(
            credentials: GroupCredentials,
            timeoutMs: Long,
        ): HostedGroup {
            if (!mayHost()) throw cannotHost()
            yieldHop(owner)
            take()
            // After take(): a host refused Busy must leave no marker, since its release is a no-op for this owner.
            if (!gate.hosting()) throw cannotHost()
            hostingTold.set(true)
            return radio.host(credentials, timeoutMs)
        }

        override suspend fun join(
            credentials: GroupCredentials,
            attemptMs: Long,
            ipv4: Boolean,
        ): JoinedGroup {
            yieldHop(owner)
            take()
            return radio.join(credentials, attemptMs, ipv4)
        }

        override suspend fun release() {
            if (holder.get() != owner) return
            try {
                radio.release()
            } finally {
                holder.compareAndSet(owner, null)
                if (hostingTold.compareAndSet(true, false)) gate.hosted()
            }
        }

        override suspend fun sweep() {
            // A held radio has a live group of ours on it; the sweep is for one nobody holds.
            if (holder.get() == null) radio.sweep()
        }
    }

    /**
     * The hop plane holds the radio until it is asked. A transfer or a call waits for that release instead of
     * failing [TransferRefusal.Busy] at once (ADR 2026-11.tz2v). The hop plane itself never waits on its own ask.
     */
    private suspend fun yieldHop(requester: String) {
        if (requester == HOP || holder.get() != HOP) return
        yieldTick.value = yieldTick.value + 1
        withTimeoutOrNull(yieldWaitMs) {
            while (holder.get() == HOP) delay(YIELD_POLL_MS)
        }
    }

    private fun cannotHost() = DirectWifiException("this phone does not host Wi-Fi Direct groups", TransferRefusal.CannotHost)

    companion object {
        /** The owner name the file transfer holds the radio under; a call holds it under its call id. */
        const val TRANSFER = "transfer"

        /** The owner name the Wi-Fi hop plane holds the radio under (ADR 2026-11.tz2v). */
        const val HOP = "wifihop"

        /** How long a transfer or a call waits for the hop plane to drop its group. */
        const val YIELD_WAIT_MS = 12_000L

        private const val YIELD_POLL_MS = 50L
    }
}
