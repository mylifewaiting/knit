package app.getknit.knit.mesh.wifihop

/**
 * Who hosts the one Wi-Fi Direct group, from the hop peers Bluetooth can see (ADR 2026-11.tz2v).
 *
 * Both modes assume every phone runs the same rule on its own view of those peers. Star covers a clique:
 * one owner, and the owner's flood is the hop between clients. Relay covers a line: a phone with two
 * neighbours alternates which one it links, and store-and-forward carries a frame across the epoch it
 * was not linked. A phone with one neighbour links that neighbour every epoch, because its lowest and
 * highest peer are the same phone.
 */
object HopRolePolicy {
    /** How long one relay slot lasts. Long enough to form a group and exchange, short enough to try the other hop. */
    const val EPOCH_MS = 60_000L

    /** The relay slot [nowMs] falls in. Wall clock, so two phones with ordinary time agree. */
    fun epoch(nowMs: Long): Long = nowMs / EPOCH_MS

    /**
     * The lowest id hosts, and everyone else joins that phone. [canHost] false drops this phone out of the
     * election: a phone that must not call `createGroup` (ADR 2026-10.w3dw) joins the lowest peer it can see
     * instead of becoming an anchor nobody can attach to.
     */
    fun star(
        self: String,
        peers: Set<String>,
        canHost: Boolean = true,
    ): HopRole {
        if (self.isEmpty() || peers.isEmpty()) return HopRole.Idle
        val eligible = if (canHost) peers + self else peers
        val anchor = eligible.minOrNull() ?: return HopRole.Idle
        return if (anchor == self) HopRole.Host else HopRole.Join(anchor)
    }

    /**
     * Even epochs link toward the lowest visible peer, odd epochs toward the highest. The lower id of
     * (this phone, that peer) hosts. With one peer the two ends are the same, so the link stays up.
     */
    fun relay(
        self: String,
        peers: Set<String>,
        epoch: Long,
    ): HopRole {
        if (self.isEmpty() || peers.isEmpty()) return HopRole.Idle
        val sorted = peers.sorted()
        val target = if (epoch % 2L == 0L) sorted.first() else sorted.last()
        return if (self <= target) HopRole.Host else HopRole.Join(target)
    }
}
