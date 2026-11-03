package app.getknit.knit.mesh.wifihop

/**
 * How this phone uses Wi-Fi Direct as a mesh plane (ADR 2026-11.tz2v). [OFF] is the shipped radios.
 * [STAR] is one group for phones that can all see the group owner. [RELAY] alternates links so a phone
 * in the middle of a line can pass traffic on.
 */
enum class HopMode {
    OFF,
    STAR,
    RELAY,
    ;

    companion object {
        /** A stored or bridge value; anything else, including null, is [OFF]. */
        fun parse(raw: String?): HopMode = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: OFF
    }
}

/**
 * The one Wi-Fi Direct role to hold right now. A phone is in one group or none: stock Android will not
 * put it in two, so a chain is not two live sockets (ADR 2026-11.tz2v).
 */
sealed interface HopRole {
    /** No group. The radio stays with Aware, a transfer, or a call. */
    data object Idle : HopRole

    /** Host the group whose credentials come from our own node id. */
    data object Host : HopRole

    /** Join [anchor]'s group and open one socket to that group owner. */
    data class Join(
        val anchor: String,
    ) : HopRole
}
