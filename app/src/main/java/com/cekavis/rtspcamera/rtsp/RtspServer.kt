package com.cekavis.rtspcamera.rtsp

import com.cekavis.rtspcamera.model.AudioCodecConfig
import com.cekavis.rtspcamera.model.CodecConfig
import com.cekavis.rtspcamera.model.EncodedAudioFrame
import com.cekavis.rtspcamera.model.EncodedFrame
import com.cekavis.rtspcamera.model.RtspCallbacks
import com.cekavis.rtspcamera.model.ServerConfig
import com.cekavis.rtspcamera.model.VideoCodec
import com.pedro.common.frame.MediaFrame
import com.pedro.rtsp.rtp.packets.AacPacket
import com.pedro.rtsp.rtp.packets.BasePacket
import com.pedro.rtsp.rtp.packets.H264Packet
import com.pedro.rtsp.rtp.packets.H265Packet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Listener lifetime is independent of camera/encoder lifetime. The callbacks own media leases.
 * Only /live and RTSP 1.0 PLAY are exposed, with video and optional AAC. No publishing or multicast.
 * Protocol design was checked against pedroSG94/RTSP-Server 1.4.3; see third_party/README.md.
 */
class RtspServer(private val config: ServerConfig, private val callbacks: RtspCallbacks) {
    private val lifecycle = Mutex()
    private val registryLock = Any()
    private val notificationLock = Any()
    private val clients = linkedMapOf<String, Client>()
    private val playing = mutableSetOf<String>()
    private val running = AtomicBoolean(false)
    private val lastKeyframeRequest = AtomicLong(0)
    private var listener: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private var acceptJob: Job? = null

    suspend fun start() = lifecycle.withLock {
        if (running.get()) return@withLock
        require(config.port in 1..65535) { "Invalid RTSP port" }
        require(!config.authEnabled || (config.username.isNotBlank() && config.password.isNotEmpty())) {
            "RTSP authentication requires credentials"
        }
        val server = withContext(Dispatchers.IO) {
            ServerSocket().apply {
                try { reuseAddress = true; bind(InetSocketAddress("0.0.0.0", config.port), 16) }
                catch (error: Exception) { close(); throw error }
            }
        }
        listener = server
        running.set(true)
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = serverScope
        acceptJob = serverScope.launch {
            try {
                while (isActive && running.get()) {
                    val socket = server.accept()
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.soTimeout = 65_000
                    val client = Client(socket, serverScope)
                    val admitted = synchronized(registryLock) {
                        if (clients.size >= MAX_CONNECTIONS || !running.get()) false
                        else { clients[client.id] = client; true }
                    }
                    if (!admitted) socket.close()
                    else { client.start(); reportCounts() }
                }
            } catch (error: IOException) {
                failListener("RTSP 监听失败，请重新启动服务")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failListener("RTSP 服务发生异常，请重新启动服务")
            }
        }
        reportCounts()
    }

    suspend fun stop() = lifecycle.withLock {
        running.set(false)
        withContext(Dispatchers.IO) { listener?.close() }
        listener = null
        val snapshot = synchronized(registryLock) { clients.values.toList() }
        snapshot.forEach(Client::requestClose) // Close sockets before awaiting blocked reader/writer jobs.
        acceptJob?.cancelAndJoin()
        acceptJob = null
        snapshot.forEach { it.join() }
        scope?.cancel()
        scope = null
        synchronized(registryLock) { clients.clear(); playing.clear() }
        reportCounts()
    }

    /** Takes an owned copy once, shared immutably across the independent client queues. Never writes to a socket. */
    fun publish(frame: EncodedFrame) {
        val targets = synchronized(registryLock) { playing.mapNotNull { clients[it] } }
        if (targets.isEmpty() || frame.data.isEmpty()) return
        val owned = frame.copy(data = frame.data.copyOf())
        targets.forEach { it.offer(owned) }
    }

    /** Like video, publishing audio only appends to bounded queues and never writes to a socket. */
    fun publishAudio(frame: EncodedAudioFrame) {
        val targets = synchronized(registryLock) { playing.mapNotNull { clients[it] } }
        if (targets.isEmpty() || frame.data.isEmpty()) return
        val owned = frame.copy(data = frame.data.copyOf())
        targets.forEach { it.offerAudio(owned) }
    }

    private fun reservePlayer(clientId: String): Boolean = synchronized(registryLock) {
        if (clientId in playing) true else if (playing.size >= MAX_PLAYERS) false else { playing.add(clientId); true }
    }

    private fun removePlayer(clientId: String) {
        synchronized(registryLock) { playing.remove(clientId) }
        reportCounts()
    }

    private fun requestKeyframe() {
        val now = System.nanoTime()
        val previous = lastKeyframeRequest.get()
        if ((previous == 0L || now - previous >= 500_000_000) && lastKeyframeRequest.compareAndSet(previous, now)) callbacks.requestKeyFrame()
    }

    private fun failListener(message: String) {
        if (!running.getAndSet(false)) return
        try { listener?.close() } catch (_: IOException) { }
        synchronized(registryLock) { clients.values.toList() }.forEach(Client::requestClose)
        reportError(message)
    }

    private fun reportCounts() {
        synchronized(notificationLock) {
            // Concurrent disconnects must not deliver an older count after the final zero count.
            val counts = synchronized(registryLock) { clients.size to playing.size }
            try { callbacks.onClientCounts(counts.first, counts.second) }
            catch (_: Exception) { reportError("RTSP 状态回调失败") }
        }
    }

    private fun reportError(message: String) {
        // Messages are fixed strings: request targets, Authorization, username and password are never included.
        try { callbacks.onError(message) } catch (_: Exception) { /* An observer cannot break cleanup. */ }
    }

    private inner class Client(private val socket: Socket, private val owner: CoroutineScope) {
        val id: String = UUID.randomUUID().toString()
        private val sessionId = UUID.randomUUID().toString().replace("-", "")
        private val state = SessionState()
        private val closed = AtomicBoolean(false)
        private val writer = SocketWriter(socket)
        private val authentication = DigestAuthentication(config.username, config.password)
        private val queue = ClientFrameQueue()
        private val audioQueue = ClientAudioQueue()
        private val lastActivity = AtomicLong(System.nanoTime())
        private val negotiationDeadline = AtomicLong(System.nanoTime() + NEGOTIATION_NANOS)
        private val origin = System.currentTimeMillis()
        private val clock = RtpClock()
        private val canonicalName = "rtsp-camera-$sessionId"
        private val videoTrack = Track(90_000)
        private val audioTrack = Track(48_000)
        private val tracks = listOf(videoTrack, audioTrack)
        private var clientJob: Job? = null
        private var watchdogJob: Job? = null
        @Volatile private var acceptingFrames = false
        private var videoLease = false
        private var audioLease = false
        private var describedCodec: CodecConfig? = null
        private var describedAudio: AudioCodecConfig? = null
        private var baseUri = localBaseUri()
        private var congestionSinceNanos = 0L
        private var lastResyncNanos = 0L

        private inner class Track(var clockRate: Int) {
            var ssrc = SecureRandom().nextInt().toLong() and 0xffff_ffffL
            @Volatile var transport: RtpTransport? = null
            var requestedTransport: TransportRequest? = null
            var packetizer: BasePacket? = null
            var senderJob: Job? = null
            var receiverJob: Job? = null
            var packetCount = 0L
            var octetCount = 0L
            var lastReportNanos = 0L

            fun reset() {
                ssrc = SecureRandom().nextInt().toLong() and 0xffff_ffffL
                packetizer = null
                packetCount = 0
                octetCount = 0
                lastReportNanos = 0
            }
        }

        fun start() {
            clientJob = owner.launch {
                watchdogJob = launch { watchDeadlines() }
                try {
                    val reader = RtspMessageReader(socket.getInputStream().buffered(32 * 1024))
                    while (isActive && !closed.get()) {
                        when (val incoming = reader.read() ?: break) {
                            is RtspInput.Interleaved -> handleInterleaved(incoming)
                            is RtspInput.Request -> handle(incoming.value)
                        }
                    }
                } catch (_: EOFException) {
                    // A peer may disconnect without TEARDOWN, including halfway through a request.
                } catch (error: RtspProtocolException) {
                    if (!closed.get()) try { writer.response(responseBytes(error.status, 0)) } catch (_: IOException) { }
                } catch (_: IOException) {
                    // Socket errors and the watchdog closing a blocked socket both release this session.
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (!closed.get()) reportError("RTSP 客户端会话发生异常")
                } finally {
                    requestClose()
                    withContext(NonCancellable) {
                        tracks.forEach { it.senderJob?.cancelAndJoin(); it.receiverJob?.cancelAndJoin() }
                        watchdogJob?.cancelAndJoin()
                        releaseLease()
                        synchronized(registryLock) { clients.remove(id); playing.remove(id) }
                        reportCounts()
                    }
                }
            }
        }

        fun requestClose() {
            if (closed.compareAndSet(false, true)) {
                acceptingFrames = false
                state.close()
                try { socket.close() } catch (_: IOException) { }
                tracks.forEach { it.transport?.close() }
                queue.close()
                audioQueue.close()
                clientJob?.cancel()
            }
        }

        suspend fun join() { clientJob?.join() }

        @Synchronized fun offer(frame: EncodedFrame) {
            if (!acceptingFrames || closed.get() || videoTrack.transport == null) return
            when (queue.offer(frame)) {
                ClientFrameQueue.Offer.RESYNC_REQUIRED -> {
                    val now = System.nanoTime()
                    if (congestionSinceNanos == 0L || now - lastResyncNanos > 2_000_000_000) congestionSinceNanos = now
                    lastResyncNanos = now
                    if (now - congestionSinceNanos > 5_000_000_000) requestClose() else requestKeyframe()
                }
                ClientFrameQueue.Offer.ACCEPTED -> Unit
                ClientFrameQueue.Offer.WAITING_FOR_KEYFRAME -> requestKeyframe()
                ClientFrameQueue.Offer.CLOSED -> Unit
            }
        }

        fun offerAudio(frame: EncodedAudioFrame) {
            if (acceptingFrames && !closed.get() && audioTrack.transport != null) audioQueue.offer(frame)
        }

        private suspend fun handle(request: RtspRequest) {
            if (config.authEnabled) {
                val result = authentication.verify(request.header("authorization"), request.method, request.target)
                if (result != DigestAuthentication.Result.ACCEPTED) {
                    respond(request, 401, mapOf("WWW-Authenticate" to authentication.challenge(result == DigestAuthentication.Result.STALE)))
                    return
                }
            }
            lastActivity.set(System.nanoTime())
            try {
                val path = targetPath(request.target)
                val isAggregate = path == "/live" || path == "/live/"
                val track = when (path) {
                    "/live/trackID=0", "/live/streamid=0" -> videoTrack
                    "/live/trackID=1", "/live/streamid=1" -> audioTrack.takeIf { describedAudio != null }
                    else -> null
                }
                if (!isAggregate && track == null && !(request.method == "OPTIONS" && path == "*")) {
                    respond(request, 404); return
                }
                if (request.method !in setOf("OPTIONS", "DESCRIBE", "SETUP") &&
                    request.header("session")?.substringBefore(';')?.trim() != sessionId
                ) { respond(request, 454); return }
                request.header("session")?.let {
                    if (it.substringBefore(';').trim() != sessionId) throw RtspProtocolException(454, "Unknown session")
                }
                when (request.method) {
                    "OPTIONS" -> respond(request, 200, mapOf("Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER, SET_PARAMETER"))
                    "DESCRIBE" -> {
                        if (!isAggregate) throw RtspProtocolException(404, "Unknown stream")
                        describe(request)
                    }
                    "SETUP" -> {
                        if (track == null) throw RtspProtocolException(404, "Unknown track")
                        setup(request, track)
                    }
                    "PLAY" -> {
                        if (!isAggregate) throw RtspProtocolException(455, "Aggregate PLAY required")
                        play(request)
                    }
                    "PAUSE" -> pause(request)
                    "TEARDOWN" -> {
                        acceptingFrames = false
                        stopSender()
                        removePlayer(id)
                        releaseLease()
                        respond(request, 200, sessionHeader())
                        requestClose()
                    }
                    "GET_PARAMETER", "SET_PARAMETER" -> {
                        if (request.body.isNotEmpty()) respond(request, 451) else respond(request, 200, sessionHeader())
                    }
                    else -> respond(request, 405, mapOf("Allow" to "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER, SET_PARAMETER"))
                }
            } catch (error: RtspProtocolException) {
                respond(request, error.status)
            }
        }

        private suspend fun describe(request: RtspRequest) {
            if (state.phase == SessionPhase.PLAYING) throw RtspProtocolException(455, "Pause before DESCRIBE")
            val accept = request.header("accept")
            if (accept != null && !accept.contains("application/sdp", true) && !accept.contains("*/*")) {
                throw RtspProtocolException(400, "SDP response required")
            }
            beginNegotiation()
            val codec = acquire()
            val audio = callbacks.audioConfig()?.let { it.copy(config = it.config.copyOf()) }
            val body = codecSdp(codec, socket.localAddress.hostAddress ?: "0.0.0.0", origin, audio)
            stopTransport()
            describedCodec = codec
            describedAudio = audio
            tracks.forEach { it.reset() }
            videoTrack.packetizer = makePacketizer(codec)
            audio?.let {
                audioTrack.clockRate = it.sampleRate
                audioTrack.packetizer = AacPacket(1).apply { setAudioInfo(it.sampleRate); setSSRC(audioTrack.ssrc) }
            }
            baseUri = advertisedBaseUri(request.target)
            state.describe()
            respond(request, 200, mapOf("Content-Type" to "application/sdp", "Content-Base" to "$baseUri/", "Cache-Control" to "no-cache"), body)
        }

        private suspend fun setup(request: RtspRequest, track: Track) {
            if (state.phase !in setOf(SessionPhase.DESCRIBED, SessionPhase.READY)) throw RtspProtocolException(455, "DESCRIBE required")
            val requested = TransportRequest.parse(request.header("transport"))
            if (tracks.any { other ->
                    other !== track && other.requestedTransport?.let {
                        it.tcp == requested.tcp && (requested.first in it.first..it.second || requested.second in it.first..it.second)
                    } == true
                }) throw RtspProtocolException(461, "Track transports overlap")
            if (track.requestedTransport != requested) {
                // Allocate first so failed SETUP does not destroy a previously working track.
                val replacement = if (requested.tcp) RtpTransport.Tcp(requested, writer) else RtpTransport.Udp.open(requested, socket.inetAddress)
                try {
                    track.transport?.close()
                    track.receiverJob?.cancelAndJoin()
                    currentCoroutineContext().ensureActive()
                    track.transport = replacement
                    track.requestedTransport = requested
                    track.receiverJob = (replacement as? RtpTransport.Udp)?.let(::startRtcpReceiver)
                } finally {
                    if (track.transport !== replacement) replacement.close()
                }
            }
            state.setup()
            respond(request, 200, sessionHeader() + ("Transport" to requireNotNull(track.transport).responseHeader(track.ssrc)))
        }

        private suspend fun play(request: RtspRequest) {
            if (state.phase !in setOf(SessionPhase.READY, SessionPhase.PAUSED, SessionPhase.PLAYING)) throw RtspProtocolException(455, "SETUP required")
            if (tracks.none { it.transport != null }) throw RtspProtocolException(455, "SETUP required")
            if (!isLiveNptRange(request.header("range"))) {
                throw RtspProtocolException(455, "Live stream cannot seek")
            }
            if (state.phase == SessionPhase.PLAYING) {
                respond(request, 200, sessionHeader()); return
            }
            if (!reservePlayer(id)) { respond(request, 453, sessionHeader()); return }
            try {
                beginNegotiation()
                val previous = describedCodec ?: throw RtspProtocolException(455, "DESCRIBE required")
                val videoChanged = if (videoTrack.transport != null) !previous.sameParameters(acquire()) else false
                val audioChanged = if (audioTrack.transport != null) {
                    val current = callbacks.audioConfig()
                    val described = describedAudio
                    current == null || described == null || current.sampleRate != described.sampleRate ||
                        current.channels != described.channels || !current.config.contentEquals(described.config)
                } else false
                if (videoChanged || audioChanged) {
                    stopTransport()
                    describedCodec = null
                    describedAudio = null
                    tracks.forEach { it.packetizer = null }
                    state.renegotiate()
                    releaseLease()
                    removePlayer(id)
                    respond(request, 455, mapOf("Content-Type" to "text/plain"), "Codec parameters changed. Send DESCRIBE and SETUP again.\n")
                    return
                }
                if (videoTrack.transport == null) releaseVideoLease()
                if (audioTrack.transport != null) acquireAudio()
                queue.clear()
                audioQueue.clear()
                state.play()
                negotiationDeadline.set(0)
                acceptingFrames = true
                // The sender starts after the PLAY response. Queued frames cannot overtake it.
                respond(request, 200, sessionHeader() + mapOf("Range" to "npt=now-"))
                if (videoTrack.transport != null) {
                    videoTrack.senderJob = owner.launch { sendFrames() }
                    requestKeyframe()
                }
                if (audioTrack.transport != null) audioTrack.senderJob = owner.launch { sendAudioFrames() }
                reportCounts()
            } catch (error: Exception) {
                acceptingFrames = false
                removePlayer(id)
                releaseLease()
                throw error
            }
        }

        private suspend fun pause(request: RtspRequest) {
            state.pause()
            acceptingFrames = false
            stopSender()
            removePlayer(id)
            releaseLease()
            negotiationDeadline.set(0)
            respond(request, 200, sessionHeader())
        }

        private fun beginNegotiation() {
            if (negotiationDeadline.get() == 0L) negotiationDeadline.set(System.nanoTime() + NEGOTIATION_NANOS)
        }

        private suspend fun acquire(): CodecConfig {
            videoLease = true // Also release when a callback allocates resources and then fails/cancels.
            return try {
                val value = withTimeout(10_000) { callbacks.acquireVideo(id) }
                value.copy(sps = value.sps.copyOf(), pps = value.pps.copyOf(), vps = value.vps?.copyOf())
            } catch (error: CancellationException) {
                if (!currentCoroutineContext().isActive) throw error
                releaseLease()
                throw RtspProtocolException(503, "Camera start timed out")
            } catch (_: Exception) {
                releaseLease()
                throw RtspProtocolException(503, "Camera unavailable")
            }
        }

        private suspend fun releaseLease() {
            withContext(NonCancellable) {
                if (audioLease) {
                    audioLease = false
                    try { callbacks.releaseAudio(id) }
                    catch (_: Exception) { reportError("无法释放音频采集会话") }
                }
                releaseVideoLease()
            }
        }

        private suspend fun releaseVideoLease() {
            if (!videoLease) return
            withContext(NonCancellable) {
                videoLease = false
                try { callbacks.releaseVideo(id) }
                catch (_: Exception) { reportError("无法释放视频采集会话") }
            }
        }

        private suspend fun acquireAudio() {
            audioLease = true // Release partial initialization if permission/device/encoder startup fails.
            try { withTimeout(10_000) { callbacks.acquireAudio(id) } }
            catch (error: CancellationException) {
                if (!currentCoroutineContext().isActive) throw error
                releaseLease()
                throw RtspProtocolException(503, "Microphone start timed out")
            } catch (_: Exception) {
                releaseLease()
                throw RtspProtocolException(503, "Microphone unavailable")
            }
        }

        private fun makePacketizer(codec: CodecConfig): BasePacket = when (codec.codec) {
            VideoCodec.H264 -> H264Packet(0).apply { sendVideoInfo(ByteBuffer.wrap(codec.sps), ByteBuffer.wrap(codec.pps)) }
            VideoCodec.HEVC -> H265Packet(0).apply {
                sendVideoInfo(ByteBuffer.wrap(codec.sps), ByteBuffer.wrap(codec.pps), ByteBuffer.wrap(requireNotNull(codec.vps)))
            }
        }.apply { setSSRC(videoTrack.ssrc) }

        private suspend fun sendFrames() {
            try {
                while (currentCoroutineContext().isActive && acceptingFrames && !closed.get()) {
                    val frame = queue.take() ?: break
                    val media = MediaFrame(ByteBuffer.wrap(frame.data), MediaFrame.Info(0, frame.data.size, frame.presentationTimeUs, frame.isKeyFrame), MediaFrame.Type.VIDEO)
                    sendPacket(videoTrack, media)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                requestClose()
            }
        }

        private suspend fun sendAudioFrames() {
            try {
                while (currentCoroutineContext().isActive && acceptingFrames && !closed.get()) {
                    val frame = audioQueue.take() ?: break
                    val media = MediaFrame(ByteBuffer.wrap(frame.data), MediaFrame.Info(0, frame.data.size, frame.presentationTimeUs, false), MediaFrame.Type.AUDIO)
                    sendPacket(audioTrack, media)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                requestClose()
            }
        }

        private suspend fun sendPacket(track: Track, media: MediaFrame) {
            requireNotNull(track.packetizer).createAndSendPacket(media) { packets ->
                currentCoroutineContext().ensureActive()
                val target = track.transport ?: return@createAndSendPacket
                val buffers = packets.map { if (it.length == it.buffer.size) it.buffer else it.buffer.copyOf(it.length) }
                if (track === audioTrack) {
                    // RFC 3640 AU-size is the whole access unit, including when it spans RTP packets.
                    // RootEncoder 2.8.1 puts fragment size here; keep its framing but correct this field.
                    buffers.forEach {
                        it[14] = (media.info.size shr 5).toByte()
                        it[15] = (media.info.size shl 3).toByte()
                    }
                }
                target.sendRtp(buffers)
                packets.forEach {
                    track.packetCount++
                    track.octetCount += maxOf(0, it.length - 12)
                }
                val now = System.nanoTime()
                if (track.lastReportNanos == 0L || now - track.lastReportNanos >= 5_000_000_000) {
                    val nowUs = now / 1000
                    // SR's RTP and NTP timestamps describe the same instant, rather than pairing
                    // a queued frame's capture time with the wall clock at the moment of sending.
                    target.sendRtcp(Rtcp.senderReport(track.ssrc, RtpClock.timestamp(nowUs, track.clockRate),
                        track.packetCount, track.octetCount, clock.wallTimeMillis(nowUs), canonicalName))
                    track.lastReportNanos = now
                }
            }
        }

        private suspend fun stopSender() {
            acceptingFrames = false
            tracks.forEach { it.senderJob?.cancelAndJoin(); it.senderJob = null }
            queue.clear()
            audioQueue.clear()
        }

        private suspend fun stopTransport() {
            stopSender()
            tracks.forEach {
                it.transport?.close()
                it.transport = null
                it.requestedTransport = null
                it.receiverJob?.cancelAndJoin()
                it.receiverJob = null
            }
        }

        private fun startRtcpReceiver(udp: RtpTransport.Udp): Job = owner.launch {
            val data = ByteArray(16 * 1024)
            try {
                while (isActive && !closed.get()) {
                    val packet = DatagramPacket(data, data.size)
                    try { udp.rtcp.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    val bytes = data.copyOf(packet.length)
                    if (Rtcp.isValid(bytes)) {
                        lastActivity.set(System.nanoTime())
                        if (Rtcp.hasBye(bytes)) { requestClose(); break }
                    }
                }
            } catch (_: IOException) {
                // Closing/replacing UDP transport intentionally wakes this receiver.
            }
        }

        private fun handleInterleaved(packet: RtspInput.Interleaved) {
            val knownChannel = tracks.any { (it.transport as? RtpTransport.Tcp)?.request?.second == packet.channel }
            if (!knownChannel || !Rtcp.isValid(packet.data)) throw RtspProtocolException(400, "Invalid RTCP")
            lastActivity.set(System.nanoTime())
            if (Rtcp.hasBye(packet.data)) requestClose()
        }

        private suspend fun watchDeadlines() {
            while (currentCoroutineContext().isActive && !closed.get()) {
                delay(250)
                val now = System.nanoTime()
                val deadline = negotiationDeadline.get()
                val writeStarted = writer.writeStartedNanos.get()
                if ((deadline != 0L && now >= deadline) || now - lastActivity.get() >= KEEPALIVE_NANOS ||
                    (writeStarted != 0L && now - writeStarted > 5_000_000_000)
                ) { requestClose(); return }
            }
        }

        private fun sessionHeader() = mapOf("Session" to "$sessionId;timeout=60")
        private suspend fun respond(request: RtspRequest, status: Int, headers: Map<String, String> = emptyMap(), body: String = "") {
            writer.response(responseBytes(status, request.cSeq, headers, body))
        }
        private fun localBaseUri(): String = URI("rtsp", null, socket.localAddress.hostAddress, config.port, "/live", null, null).toASCIIString()
        private fun advertisedBaseUri(target: String): String {
            val uri = URI(target)
            return if (uri.isAbsolute) URI("rtsp", null, uri.host, uri.port, "/live", null, null).toASCIIString() else localBaseUri()
        }
    }

    private companion object {
        const val MAX_PLAYERS = 4
        const val MAX_CONNECTIONS = 16
        const val NEGOTIATION_NANOS = 10_000_000_000L
        const val KEEPALIVE_NANOS = 60_000_000_000L
    }
}
