package app.getknit.knit.mesh.wifihop

import app.getknit.knit.transfer.GroupCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Group name and passphrase derived from the anchor id (ADR 2026-11.tz2v). */
class HopCredentialsTest {
    private val anchor = "abcdefghijklmnopquvwx234"

    @Test
    fun theSameAnchorAlwaysDerivesTheSameGroup() {
        assertEquals(HopCredentials.of(anchor), HopCredentials.of(anchor))
    }

    @Test
    fun aDifferentAnchorDerivesADifferentGroup() {
        val other = HopCredentials.of("zzzzzzzzzzzzzzzzzzzzzzzzzz")
        assertNotEquals(HopCredentials.of(anchor).ssid, other.ssid)
        assertNotEquals(HopCredentials.of(anchor).passphrase, other.passphrase)
    }

    @Test
    fun thePlatformWouldAcceptTheGroupAndASweepWouldRecogniseIt() {
        val group = HopCredentials.of(anchor)
        assertTrue(GroupCredentials.isValid(group.ssid, group.passphrase))
        assertTrue(GroupCredentials.isOurs(group.ssid))
        assertTrue(group.ssid.startsWith("DIRECT-kn-"))
        assertTrue(group.ssid.length <= GroupCredentials.SSID_MAX_BYTES)
        assertEquals(HopCredentials.PORT, 38_741)
    }
}
