package app.getknit.knit.mesh.wifihop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which Bluetooth sightings still count as hop peers (ADR 2026-11.tz2v). */
class WifiHopSightingsTest {
    private val sightings = WifiHopSightings(lingerMs = 90)

    @Test
    fun aFlaggedSightingIsAPeerAndAClearBitForgetsIt() {
        sightings.note("aaa", flagged = true, now = 0)
        assertEquals(setOf("aaa"), sightings.peers.value)
        sightings.note("aaa", flagged = false, now = 10)
        assertTrue(sightings.peers.value.isEmpty())
    }

    @Test
    fun aQuietPeerAgesOutAndAFreshOneStays() {
        sightings.note("aaa", flagged = true, now = 0)
        sightings.note("bbb", flagged = true, now = 50)
        sightings.prune(100)
        assertEquals(setOf("bbb"), sightings.peers.value)
    }

    @Test
    fun clearDropsEveryone() {
        sightings.note("aaa", flagged = true, now = 0)
        sightings.clear()
        assertTrue(sightings.peers.value.isEmpty())
        sightings.prune(1_000)
        assertTrue(sightings.peers.value.isEmpty())
    }
}
