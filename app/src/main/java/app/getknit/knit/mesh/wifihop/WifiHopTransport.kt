package app.getknit.knit.mesh.wifihop

import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.crypto.LinkProver
import app.getknit.knit.mesh.link.LandingFiles
import app.getknit.knit.mesh.link.LinkClaims
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.transfer.DirectWifi
import app.getknit.knit.transfer.DirectWifiException
import app.getknit.knit.transfer.DirectWifiLease
import app.getknit.knit.transfer.TransferRefusal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Wi-Fi Direct as a mesh plane for phones that have no Wi-Fi Aware (ADR 2026-11.tz2v). One group at a
 * time, through the same [DirectWifiLease] a transfer and a call use. Hosting pauses Aware, because that
 * is what [app.getknit.knit.transfer.AndroidDirectWifi] does for every group; releasing it resumes Aware.
 * Bluetooth keeps running and is how hop peers are found.
 *
 * [pause] is a no-op on purpose. The pause a group triggers is the Aware hand-over, and it runs on the
 * coroutine that is inside `host()`. Releasing the radio from [pause] would take the mutex `host()` still
 * holds. A transfer that needs the radio asks [DirectWifiLease.yields] instead, and this plane drops the
 * group.
 */
@Suppress("TooManyFunctions", "LongParameterList") // the radio seam, plus the lease and the peers it elects over
class WifiHopTransport(
    private val leaseOf: () -> DirectWifiLease,
    private val mode: StateFlow<HopMode>,
    private val hopPeers: StateFlow<Set<String>>,
    private val selfId: suspend () -> String,
    private val scope: CoroutineScope,
    private val metrics: MeshMetrics,
    private val partials: PartialBlobs,
    private val claims: LinkClaims,
    private val prover: LinkProver,
    private val cacheDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : MeshTransport {
    private val landing = LandingFiles()
    private val links =
        HopLinks(
            scope = scope,
            cacheDir = cacheDir,
            partials = partials,
            metrics = metrics,
            claims = claims,
            prover = prover,
            landing = landing,
            now = clock,
            log = log,
        )
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val healthFlow = MutableStateFlow(TransportHealth.Unavailable)
    private val switchedOffFlow = MutableStateFlow(true)

    private var driveJob: Job? = null
    private var holdJob: Job? = null
    private var radio: DirectWifi? = null
    private var lease: DirectWifiLease? = null
    private var self: String = ""
    private var applied: HopRole = HopRole.Idle
    private var pending: HopRole = HopRole.Idle
    private var pendingSince: Long = 0L
    private var failWait = FAIL_MIN_MS

    override val neighbors: StateFlow<Set<Peer>> = links.neighbors
    override val health: StateFlow<TransportHealth> = healthFlow.asStateFlow()
    override val inbound: Flow<InboundFrame> = links.inbound
    override val incomingFiles: Flow<ReceivedFile> = links.incomingFiles
    override val incomingDigests: Flow<ReceivedDigest> = links.incomingDigests
    override val kind: TransportKind = TransportKind.WifiHop
    override val switchedOff: StateFlow<Boolean> = switchedOffFlow.asStateFlow()

    /**
     * High-throughput only while a hop socket is up. While this is false, Wi-Fi Aware stays the composite's
     * fast plane and can still arm a data path. While it is true, this child is first in the composite, so
     * frames and files to a hop neighbour ride the group.
     */
    override val highThroughput: Boolean get() = neighbors.value.isNotEmpty()

    override fun start() {
        if (driveJob?.isActive == true) return
        driveJob =
            scope.launch {
                self = runCatching { selfId() }.getOrDefault("")
                val held = leaseOf()
                lease = held
                radio = held.leasedTo(DirectWifiLease.HOP)
                launch {
                    var seen = held.yields.value
                    held.yields.collect { tick ->
                        if (tick != seen) {
                            seen = tick
                            // A transfer or a call is waiting on the lease. Drop the group now, even mid-formation.
                            log("yielding the radio")
                            holdJob?.cancel()
                            wake.trySend(Unit)
                        }
                    }
                }
                drive()
            }
    }

    override fun stop() {
        driveJob?.cancel()
        driveJob = null
        links.closeAll()
        landing.clear()
        applied = HopRole.Idle
        scope.launch { release() }
    }

    override fun heal() {
        wake.trySend(Unit)
    }

    override suspend fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) = links.send(wire, to)

    override suspend fun sendFile(
        file: File,
        to: Peer,
        meta: FileMeta,
    ): Boolean = links.sendFile(file, to, meta)

    override suspend fun sendDigest(
        to: Peer,
        ids: List<String>,
    ) = links.sendDigest(to, ids)

    override fun arrivingFiles(): Map<String, ArrivingFile> = links.arrivingFiles()

    override fun fileTaken(
        fromNodeId: String,
        key: String,
    ) = links.fileTaken(fromNodeId, key)

    override fun fileArrivingFrom(nodeId: String): Boolean = links.fileArrivingFrom(nodeId)

    override fun fileArrivingEtaMs(nodeId: String): Long? = links.fileArrivingEtaMs(nodeId)

    override fun cancelQueuedFile(
        nodeId: String,
        key: String,
    ): Boolean = links.cancelQueuedFile(nodeId, key)

    override fun fileInFlightTo(
        nodeId: String,
        key: String,
    ): Boolean = links.fileInFlightTo(nodeId, key)

    override fun feedingLoad(nodeId: String): Int = if (links.contains(nodeId)) links.feedingLoad() else 0

    private suspend fun drive() {
        while (scope.isActive) {
            refreshHealth()
            val role = awaitRole()
            if (role is HopRole.Idle) continue
            applied = role
            log("up ${describe(role)}")
            var failed = false
            try {
                supervisorScope {
                    val job =
                        launch {
                            try {
                                hold(role)
                            } finally {
                                withContext(NonCancellable) {
                                    links.closeAll()
                                    release()
                                }
                            }
                        }
                    holdJob = job
                    try {
                        job.join()
                    } catch (e: CancellationException) {
                        // A yield cancels the hold only. Stopping the plane cancels this drive.
                        if (!isActive) throw e
                    } finally {
                        holdJob = null
                    }
                }
                failWait = FAIL_MIN_MS
            } catch (e: CancellationException) {
                throw e
            } catch (e: DirectWifiException) {
                failed = true
                log("group failed: ${e.message ?: e.javaClass.simpleName}")
            } catch (e: IOException) {
                failed = true
                log("group failed: ${e.message ?: e.javaClass.simpleName}")
            } catch (e: IllegalStateException) {
                failed = true
                log("group failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                applied = HopRole.Idle
            }
            refreshHealth()
            if (failed) {
                pauseMs(failWait)
                failWait = (failWait * 2).coerceAtMost(FAIL_MAX_MS)
            } else {
                pauseMs(REFORM_GAP_MS)
            }
        }
    }

    /** Waits until the role to hold has been stable, or the mode is off. */
    private suspend fun awaitRole(): HopRole {
        while (scope.isActive) {
            val role = stable(desired())
            if (role !is HopRole.Idle) return role
            pauseMs(POLL_MS)
        }
        return HopRole.Idle
    }

    private suspend fun hold(role: HopRole) {
        val anchor = if (role is HopRole.Join) role.anchor else self
        val credentials = HopCredentials.of(anchor)
        val radio = checkNotNull(this.radio)
        when (role) {
            HopRole.Host -> {
                val group = radio.host(credentials, FORM_MS)
                listen(group.addresses.map { it.address })
            }

            is HopRole.Join -> {
                val joined = radio.join(credentials, FORM_MS, ipv4 = true)
                val socket = connect(joined.ownerAddress) ?: return
                if (!links.dial(socket, role.anchor)) return
                while (scope.isActive && links.contains(role.anchor) && stable(desired()) == role) {
                    pauseMs(POLL_MS)
                }
            }

            HopRole.Idle -> {
                return
            }
        }
    }

    /** Accepts clients until the role changes, a yield lands, or the listeners die. */
    private suspend fun listen(addresses: List<InetAddress>) {
        val servers = bind(addresses)
        if (servers.isEmpty()) error("no group address to listen on")
        try {
            coroutineScope {
                val accepts =
                    servers.map { server ->
                        launch(Dispatchers.IO) {
                            while (isActive) {
                                val socket = runCatching { server.accept() }.getOrNull() ?: break
                                launch(Dispatchers.IO) { links.accept(socket) }
                            }
                        }
                    }
                while (isActive && stable(desired()) is HopRole.Host) pauseMs(POLL_MS)
                accepts.forEach { it.cancel() }
            }
        } finally {
            servers.forEach { runCatching { it.close() } }
        }
    }

    private fun bind(addresses: List<InetAddress>): List<ServerSocket> {
        val bound = mutableListOf<ServerSocket>()
        for (address in addresses) {
            val server =
                runCatching {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(address, HopCredentials.PORT), HopLinks.MAX_LINKS)
                    }
                }.getOrNull()
            if (server != null) bound += server else log("cannot listen on ${address.hostAddress}")
        }
        return bound
    }

    private suspend fun connect(owner: InetAddress): Socket? {
        val deadline = clock() + CONNECT_MS
        var last: Exception? = null
        while (clock() < deadline) {
            val socket = Socket()
            val connected =
                runCatching { socket.connect(InetSocketAddress(owner, HopCredentials.PORT), CONNECT_ATTEMPT_MS) }
            if (connected.isSuccess) return socket
            last = connected.exceptionOrNull() as? Exception
            runCatching { socket.close() }
            pauseMs(CONNECT_RETRY_MS)
        }
        log("no socket to ${owner.hostAddress}: ${last?.javaClass?.simpleName}")
        return null
    }

    private fun desired(): HopRole {
        val selected = mode.value
        switchedOffFlow.value = selected == HopMode.OFF
        if (selected == HopMode.OFF || self.isEmpty()) return HopRole.Idle
        val owner = lease?.owner
        if (owner != null && owner != DirectWifiLease.HOP) return HopRole.Idle
        val peers = hopPeers.value
        val canHost = lease?.mayHost() != false
        return when (selected) {
            HopMode.OFF -> HopRole.Idle
            HopMode.STAR -> HopRolePolicy.star(self, peers, canHost)
            HopMode.RELAY -> HopRolePolicy.relay(self, peers, HopRolePolicy.epoch(clock()))
        }
    }

    /**
     * A role that just appeared is taken at once when nothing is up. A change away from the group we hold
     * waits [STABLE_MS], so one Bluetooth sighting does not tear the group down and up.
     */
    private fun stable(want: HopRole): HopRole {
        val now = clock()
        if (want == applied) {
            pending = want
            pendingSince = now
            return want
        }
        if (want != pending) {
            pending = want
            pendingSince = now
        }
        val settled = now - pendingSince >= STABLE_MS
        val first = applied is HopRole.Idle && want !is HopRole.Idle
        return if (settled || first) want else applied
    }

    private fun refreshHealth() {
        val selected = mode.value
        switchedOffFlow.value = selected == HopMode.OFF
        healthFlow.value =
            when {
                selected == HopMode.OFF -> {
                    TransportHealth.Unavailable
                }

                radio?.refusal() == TransferRefusal.WifiOff -> {
                    TransportHealth.Unavailable
                }

                radio?.refusal() != null && radio?.refusal() != TransferRefusal.Busy && neighbors.value.isEmpty() -> {
                    TransportHealth.Degraded
                }

                else -> {
                    TransportHealth.Healthy
                }
            }
    }

    private suspend fun release() {
        runCatching { radio?.release() }
    }

    private suspend fun pauseMs(ms: Long) {
        withTimeoutOrNull(ms) { wake.receive() }
    }

    private fun describe(role: HopRole): String =
        when (role) {
            HopRole.Idle -> "idle"
            HopRole.Host -> "host"
            is HopRole.Join -> "join ${role.anchor.take(SHORT)}"
        }

    companion object {
        const val FORM_MS = 20_000L
        const val STABLE_MS = 5_000L
        private const val POLL_MS = 1_000L
        private const val REFORM_GAP_MS = 1_000L
        private const val CONNECT_MS = 10_000L
        private const val CONNECT_ATTEMPT_MS = 2_000
        private const val CONNECT_RETRY_MS = 400L
        private const val FAIL_MIN_MS = 2_000L
        private const val FAIL_MAX_MS = 60_000L
        private const val SHORT = 8
    }
}
