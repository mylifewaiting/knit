package app.getknit.knit.mesh.wifihop

import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.crypto.LinkProver
import app.getknit.knit.mesh.furthestByKey
import app.getknit.knit.mesh.link.FramedLink
import app.getknit.knit.mesh.link.LandingFiles
import app.getknit.knit.mesh.link.LinkCallbacks
import app.getknit.knit.mesh.link.LinkClaims
import app.getknit.knit.mesh.link.LinkHandshake
import app.getknit.knit.mesh.link.LinkSocket
import app.getknit.knit.mesh.link.NetSocketLink
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * The framed sockets on one Wi-Fi hop group (ADR 2026-11.tz2v). The group passphrase is derived from a
 * public node id, so a socket is kept only after [LinkHandshake] proves the id it claims. An unproven
 * hello is closed with no reply. At most [MAX_LINKS] peers, so a group full of strangers cannot pin a
 * reader each.
 */
internal class HopLinks(
    private val scope: CoroutineScope,
    private val cacheDir: File,
    private val partials: PartialBlobs,
    private val metrics: MeshMetrics,
    private val claims: LinkClaims,
    private val prover: LinkProver,
    private val landing: LandingFiles,
    private val now: () -> Long,
    private val log: (String) -> Unit,
) {
    private val links = ConcurrentHashMap<String, FramedLink>()

    val inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 64)
    val incomingFiles = MutableSharedFlow<ReceivedFile>(extraBufferCapacity = 8)
    val incomingDigests = MutableSharedFlow<ReceivedDigest>(extraBufferCapacity = 8)
    val neighbors = MutableStateFlow<Set<Peer>>(emptySet())

    /** The group owner accepted [socket]. The dialer writes first. */
    fun accept(socket: Socket) {
        socket.tcpNoDelay = true
        val link = NetSocketLink(socket)
        val shaken =
            handshake(socket) {
                val hello = LinkHandshake.receiveHello(link.input) ?: return@handshake null
                if (!hello.offersProof || hello.malformedOffer) return@handshake null
                val answered = LinkHandshake.answer(link.input, link.output, prover, hello)
                val proven = answered is LinkHandshake.Answered.Linked && answered.proven
                if (!proven) null else Peer(hello.wire.nodeId, hello.wire.protoVersion, hello.wire.capabilities)
            }
        if (shaken == null) {
            socket.closeQuietly()
            return
        }
        register(shaken, link)
    }

    /** We dialed the anchor [expectedId]. Anything else, or an unproven reply, is closed. */
    fun dial(
        socket: Socket,
        expectedId: String,
    ): Boolean {
        socket.tcpNoDelay = true
        val link = NetSocketLink(socket)
        val shaken =
            handshake(socket) {
                val dialed = LinkHandshake.dial(link.input, link.output, prover, expectedId)
                val linked = dialed as? LinkHandshake.Dialed.Linked
                if (linked == null || !linked.proven) {
                    null
                } else {
                    Peer(expectedId, linked.reply.protoVersion, linked.reply.capabilities)
                }
            }
        if (shaken == null) {
            socket.closeQuietly()
            return false
        }
        register(shaken, link)
        return true
    }

    fun closeAll() {
        val held = links.values.toList()
        links.clear()
        held.forEach { runCatching { it.close() } }
        publish()
    }

    fun contains(nodeId: String): Boolean = links.containsKey(nodeId)

    fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) {
        val bytes = WireCodec.encodeWire(wire)
        val targets = if (to == null) links.values.toList() else listOfNotNull(links[to.nodeId])
        targets.forEach { it.send(bytes) }
    }

    fun sendFile(
        file: File,
        to: Peer,
        meta: FileMeta,
    ): Boolean {
        val link = links[to.nodeId] ?: return false
        val accepted = link.sendFile(file, meta)
        if (accepted) metrics.onFileSent(app.getknit.knit.mesh.TransportKind.WifiHop)
        return accepted
    }

    fun sendDigest(
        to: Peer,
        ids: List<String>,
    ) {
        links[to.nodeId]?.sendDigest(ids)
    }

    fun arrivingFiles(): Map<String, ArrivingFile> = (links.values.mapNotNull { it.rxFile } + landing.all).furthestByKey()

    fun fileTaken(
        fromNodeId: String,
        key: String,
    ) = landing.take(fromNodeId, key)

    fun fileArrivingFrom(nodeId: String): Boolean = links[nodeId]?.rxInProgress ?: false

    fun fileArrivingEtaMs(nodeId: String): Long? = links[nodeId]?.rxEtaMs()

    fun cancelQueuedFile(
        nodeId: String,
        key: String,
    ): Boolean = links[nodeId]?.cancelQueued(key) ?: false

    fun fileInFlightTo(
        nodeId: String,
        key: String,
    ): Boolean = links[nodeId]?.hasPendingFile(key) ?: false

    fun feedingLoad(): Int = links.values.count { it.feeding }

    private fun handshake(
        socket: Socket,
        block: () -> Peer?,
    ): Peer? {
        socket.soTimeout = HANDSHAKE_MS
        val peer =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                log("handshake failed: ${e.javaClass.simpleName}")
                null
            } finally {
                runCatching { socket.soTimeout = 0 }
            }
        return peer
    }

    private fun register(
        peer: Peer,
        socket: LinkSocket,
    ) {
        if (links.size >= MAX_LINKS && !links.containsKey(peer.nodeId)) {
            log("refusing ${peer.nodeId.take(SHORT)}: $MAX_LINKS links already")
            socket.close()
            return
        }
        val held = arrayOfNulls<FramedLink>(1)
        val callbacks =
            object : LinkCallbacks {
                override fun onInbound(frame: InboundFrame) {
                    scope.launch { inbound.emit(frame) }
                }

                override fun onDigest(digest: ReceivedDigest) {
                    scope.launch { incomingDigests.emit(digest) }
                }

                override fun onFile(file: ReceivedFile) {
                    // The link already landed the file. With nobody collecting, drop it so it cannot sit in the cache.
                    scope.launch {
                        if (incomingFiles.subscriptionCount.value == 0) {
                            landing.take(file.fromNodeId, file.key)
                            runCatching { File(file.path).delete() }
                        } else {
                            incomingFiles.emit(file)
                        }
                    }
                }

                override fun onLinkDown(nodeId: String) {
                    held[0]?.let { drop(nodeId, it) }
                }
            }
        val framed =
            FramedLink(
                nodeId = peer.nodeId,
                peer = peer,
                socket = socket,
                scope = scope,
                cacheDir = cacheDir,
                partials = partials,
                metrics = metrics,
                callbacks = callbacks,
                now = now,
                log = log,
                landing = landing,
                proven = true,
            )
        held[0] = framed
        links.put(peer.nodeId, framed)?.close()
        claims.proven(peer.nodeId, PLANE)
        publish()
        framed.start()
        log("link ${peer.nodeId.take(SHORT)}")
    }

    private fun drop(
        nodeId: String,
        link: FramedLink,
    ) {
        if (!links.remove(nodeId, link)) return
        publish()
        log("link down ${nodeId.take(SHORT)}")
    }

    private fun publish() {
        neighbors.value = links.values.map { it.peer }.toSet()
    }

    private fun Socket.closeQuietly() {
        runCatching { close() }
    }

    companion object {
        const val MAX_LINKS = 8
        const val HANDSHAKE_MS = 15_000
        private const val SHORT = 8

        /** [LinkClaims]'s plane name for a hop socket. */
        const val PLANE = "wifihop"
    }
}
