package app.getknit.knit.mesh.wifihop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who hosts the one Wi-Fi Direct group (ADR 2026-11.tz2v). */
class HopRolePolicyTest {
    private val a = "a"
    private val b = "b"
    private val c = "c"

    @Test
    fun starWithNobodyNearbyIsIdle() {
        assertEquals(HopRole.Idle, HopRolePolicy.star(a, emptySet()))
    }

    @Test
    fun starElectsTheLowestId() {
        assertEquals(HopRole.Host, HopRolePolicy.star(a, setOf(b, c)))
        assertEquals(HopRole.Join(a), HopRolePolicy.star(b, setOf(a, c)))
        assertEquals(HopRole.Join(a), HopRolePolicy.star(c, setOf(a, b)))
    }

    @Test
    fun starWithOnePeerHasTheLowerIdHost() {
        assertEquals(HopRole.Host, HopRolePolicy.star(a, setOf(b)))
        assertEquals(HopRole.Join(a), HopRolePolicy.star(b, setOf(a)))
    }

    @Test
    fun aPhoneThatCannotHostDoesNotBecomeTheAnchor() {
        // a is the lowest id, but it must not host, so it joins b. b, with no lower peer of its own, hosts.
        assertEquals(HopRole.Join(b), HopRolePolicy.star(a, setOf(b, c), canHost = false))
        assertEquals(HopRole.Host, HopRolePolicy.star(b, setOf(c), canHost = true))
    }

    @Test
    fun relayWithOnePeerStaysOnThatPeer() {
        assertEquals(HopRole.Host, HopRolePolicy.relay(a, setOf(b), epoch = 0))
        assertEquals(HopRole.Host, HopRolePolicy.relay(a, setOf(b), epoch = 1))
        assertEquals(HopRole.Join(a), HopRolePolicy.relay(b, setOf(a), epoch = 0))
        assertEquals(HopRole.Join(a), HopRolePolicy.relay(b, setOf(a), epoch = 1))
    }

    @Test
    fun relayAlternatesSoAMiddlePhoneLinksEachEnd() {
        // A sees only B, C sees only B, B sees both. Even epochs are the A–B link, odd epochs the B–C link.
        assertEquals(HopRole.Host, HopRolePolicy.relay(a, setOf(b), epoch = 0))
        assertEquals(HopRole.Join(a), HopRolePolicy.relay(b, setOf(a, c), epoch = 0))
        assertEquals(HopRole.Host, HopRolePolicy.relay(b, setOf(a, c), epoch = 1))
        assertEquals(HopRole.Join(b), HopRolePolicy.relay(c, setOf(b), epoch = 1))
    }

    @Test
    fun relayEvenEpochPutsACliqueOnTheLowestAnchor() {
        assertEquals(HopRole.Host, HopRolePolicy.relay(a, setOf(b, c), epoch = 0))
        assertEquals(HopRole.Join(a), HopRolePolicy.relay(b, setOf(a, c), epoch = 0))
        assertEquals(HopRole.Join(a), HopRolePolicy.relay(c, setOf(a, b), epoch = 0))
    }

    @Test
    fun epochIsAMinuteOfWallClock() {
        assertEquals(0L, HopRolePolicy.epoch(0))
        assertEquals(0L, HopRolePolicy.epoch(HopRolePolicy.EPOCH_MS - 1))
        assertEquals(1L, HopRolePolicy.epoch(HopRolePolicy.EPOCH_MS))
        assertTrue(HopRolePolicy.EPOCH_MS >= 60_000L)
    }
}
