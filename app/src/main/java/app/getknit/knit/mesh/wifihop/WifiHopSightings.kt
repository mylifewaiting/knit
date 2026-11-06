package app.getknit.knit.mesh.wifihop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Node ids whose Bluetooth advert still says they are running the Wi-Fi hop (ADR 2026-11.tz2v). A sighting
 * with the flag sets the stamp; one without it clears the id at once, so turning the mode off drops the
 * phone out of everyone else's election on the next advert. [prune] drops a stamp older than [lingerMs].
 * The Bluetooth plane calls both from its own threads, so the map is locked.
 */
class WifiHopSightings(
    private val lingerMs: Long = LINGER_MS,
) {
    private val seen = HashMap<String, Long>()
    private val peersFlow = MutableStateFlow<Set<String>>(emptySet())

    /** The ids to elect over. Empty until a flagged advert is seen. */
    val peers: StateFlow<Set<String>> = peersFlow.asStateFlow()

    /** [nodeId] was sighted at [now]. [flagged] false forgets it. */
    fun note(
        nodeId: String,
        flagged: Boolean,
        now: Long,
    ) {
        synchronized(this) {
            if (flagged) seen[nodeId] = now else seen.remove(nodeId)
            publish(now)
        }
    }

    /** Drops stamps older than [lingerMs] at [now]. A scan window with no new sighting still ages the set. */
    fun prune(now: Long) {
        synchronized(this) { publish(now) }
    }

    /** Adapter off, or the plane stopped: nobody is a hop peer until the next sighting. */
    fun clear() {
        synchronized(this) {
            seen.clear()
            peersFlow.value = emptySet()
        }
    }

    private fun publish(now: Long) {
        seen.entries.removeIf { now - it.value > lingerMs }
        val next = seen.keys.toSet()
        if (next != peersFlow.value) peersFlow.value = next
    }

    companion object {
        /** Matches the Aware-candidate linger: a peer quiet for 90 s is gone, not merely between scan windows. */
        const val LINGER_MS = 90_000L
    }
}
