package app.getknit.knit.mesh.wifihop

import app.getknit.knit.transfer.GroupCredentials
import java.security.MessageDigest

/**
 * The Wi-Fi Direct group a hop anchor hosts (ADR 2026-11.tz2v). Any phone that knows the anchor's node id
 * can derive the name and the passphrase: the id is already on the Bluetooth advert, and the passphrase is
 * a bearer, the way the Aware PMK is. The socket on top of the group still requires a link proof, so joining
 * the group is not joining the mesh.
 *
 * The name starts with `DIRECT-kn-`, which [GroupCredentials.isOurs] already treats as a group this app
 * raised, so a leftover hop group is swept with a leftover transfer group.
 */
object HopCredentials {
    /** The TCP port both ends use. Below the transfer's ephemeral range, so a transfer never picks it. */
    const val PORT = 38_741

    private const val SALT = "knit-wifi-hop-v1"
    private const val SSID_TAIL = 16
    private const val PASS_BYTES = 12

    /** Credentials for the group [anchorNodeId] hosts. Stable for that id. */
    fun of(anchorNodeId: String): GroupCredentials {
        val tail =
            anchorNodeId
                .filter { it.isLetterOrDigit() }
                .lowercase()
                .take(SSID_TAIL)
                .padEnd(SSID_TAIL, '0')
        val digest = MessageDigest.getInstance("SHA-256").digest("$SALT:$anchorNodeId".toByteArray(Charsets.UTF_8))
        val passphrase = digest.take(PASS_BYTES).joinToString("") { "%02x".format(it) }
        return GroupCredentials(ssid = "${GroupCredentials.SSID_PREFIX}kn-$tail", passphrase = passphrase)
    }
}
