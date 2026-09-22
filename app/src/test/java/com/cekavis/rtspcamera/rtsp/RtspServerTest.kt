package com.cekavis.rtspcamera.rtsp

import com.cekavis.rtspcamera.model.CodecConfig
import com.cekavis.rtspcamera.model.EncodedFrame
import com.cekavis.rtspcamera.model.RtspCallbacks
import com.cekavis.rtspcamera.model.ServerConfig
import com.cekavis.rtspcamera.model.VideoCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RtspServerTest {
    @Test fun ffmpegFloatingZeroRangeAndHhmmssZeroStartLivePlayback() = runBlocking {
        val callbacks = FakeCallbacks()
        val port = freePort()
        val server = RtspServer(ServerConfig(port), callbacks)
        server.start()
        try {
            WireClient(port).use { client ->
                client.describeAndSetup()
                // FFmpeg sends the aggregate URL with a trailing slash and Range: npt=0.000-.
                listOf("npt=0.000-", "npt=0:00:00.000-", "npt=now-").forEach { range ->
                    assertEquals(200, client.request("PLAY", "${client.url}/", mapOf("Range" to range)).status)
                    awaitCondition { callbacks.playing.get() == 1 }
                    assertEquals(200, client.request("PAUSE").status)
                    assertTrue(callbacks.leases.isEmpty())
                }
            }
        } finally { server.stop() }
    }

    @Test fun invalidLiveRangesAreRejectedBeforeAndDuringPlayback() = runBlocking {
        val callbacks = FakeCallbacks()
        val port = freePort()
        val server = RtspServer(ServerConfig(port), callbacks)
        val ranges = listOf("npt=1-", "npt=0-0", "npt=now-5", "npt=0:00:00.001-", "npt=0.000garbage-")
        server.start()
        try {
            WireClient(port).use { client ->
                client.describeAndSetup()
                ranges.forEach { assertEquals(455, client.request("PLAY", headers = mapOf("Range" to it)).status) }
                assertEquals(1, callbacks.acquisitions.get())
                assertEquals(200, client.request("PLAY", headers = mapOf("Range" to "npt=0.000-")).status)
                awaitCondition { callbacks.playing.get() == 1 }
                ranges.forEach { assertEquals(455, client.request("PLAY", headers = mapOf("Range" to it)).status) }
                assertEquals(2, callbacks.acquisitions.get())
                assertEquals(1, callbacks.playing.get())
                assertEquals(200, client.request("TEARDOWN").status)
            }
        } finally { server.stop() }
    }

    @Test fun directSetupAndPlayCannotBypassDigestOrAcquireCamera() = runBlocking {
        val callbacks = FakeCallbacks()
        val port = freePort()
        val server = RtspServer(ServerConfig(port, true, "camera", "test-password", true), callbacks)
        server.start()
        try {
            WireClient(port).use { client ->
                listOf("DESCRIBE", "SETUP", "PLAY", "PAUSE", "TEARDOWN", "GET_PARAMETER").forEach { method ->
                    val response = client.request(method, if (method == "SETUP") "${client.url}/trackID=0" else client.url)
                    assertEquals("Unauthenticated $method", 401, response.status)
                    assertTrue(response.headers.getValue("www-authenticate").startsWith("Digest "))
                }
                assertEquals(0, callbacks.acquisitions.get())
                val challenge = client.request("DESCRIBE").headers.getValue("www-authenticate")
                val auth = DigestAuthenticationTest.authorization(challenge, "DESCRIBE", target = client.url)
                assertEquals(200, client.request("DESCRIBE", headers = mapOf("Authorization" to auth)).status)
                assertEquals(1, callbacks.acquisitions.get())
                assertEquals(401, client.request("DESCRIBE", headers = mapOf("Authorization" to auth)).status)
            }
            awaitCondition { callbacks.leases.isEmpty() }
        } finally { server.stop() }
    }

    @Test fun pauseReleasesCameraAndChangedCsdRequiresNewDescribe() = runBlocking {
        val callbacks = FakeCallbacks()
        val port = freePort()
        val server = RtspServer(ServerConfig(port), callbacks)
        server.start()
        try {
            WireClient(port).use { client ->
                client.describeAndSetup()
                assertEquals(200, client.request("PLAY").status)
                awaitCondition { callbacks.playing.get() == 1 }
                assertEquals(200, client.request("PAUSE").status)
                assertTrue(callbacks.leases.isEmpty())
                assertEquals(0, callbacks.playing.get())
                callbacks.codec = callbacks.codec.copy(pps = byteArrayOf(0, 0, 0, 1, 0x68, 0x25), generation = 2)
                val changed = client.request("PLAY")
                assertEquals(455, changed.status)
                assertTrue(changed.body.contains("DESCRIBE"))
                assertTrue(callbacks.leases.isEmpty())
                client.describeAndSetup()
                assertEquals(200, client.request("PLAY").status)
                assertEquals(200, client.request("TEARDOWN").status)
                assertTrue(callbacks.leases.isEmpty())
            }
        } finally { server.stop() }
    }

    @Test fun fifthPlayingClientIsRefusedWithoutInterruptingOthers() = runBlocking {
        val callbacks = FakeCallbacks()
        val port = freePort()
        val server = RtspServer(ServerConfig(port), callbacks)
        val clients = mutableListOf<WireClient>()
        server.start()
        try {
            repeat(5) { index ->
                val client = WireClient(port).also(clients::add)
                client.describeAndSetup()
                assertEquals(if (index < 4) 200 else 453, client.request("PLAY").status)
            }
            awaitCondition { callbacks.playing.get() == 4 }
            clients.first().request("TEARDOWN")
            assertEquals(200, clients.last().request("PLAY").status)
        } finally {
            clients.forEach(WireClient::close)
            server.stop()
        }
        assertTrue(callbacks.leases.isEmpty())
        assertEquals(0, callbacks.connected.get())
    }

    @Test fun tcpStreamingUsesNegotiatedChannelAndKeepaliveStillParses() = runBlocking {
        val callbacks = FakeCallbacks()
        val port = freePort()
        val server = RtspServer(ServerConfig(port), callbacks)
        server.start()
        try {
            WireClient(port).use { client ->
                client.describeAndSetup("RTP/AVP/TCP;unicast;interleaved=4-5")
                assertEquals(200, client.request("PLAY").status)
                server.publish(EncodedFrame(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3), 1_000_000, true))
                val frame = client.readInterleaved()
                assertEquals(4, frame.first)
                assertEquals(0x80, frame.second[0].toInt() and 0xc0)
                assertEquals(96, frame.second[1].toInt() and 0x7f)
                val rr = byteArrayOf(0x80.toByte(), 201.toByte(), 0, 1, 0, 0, 0, 7)
                client.sendInterleaved(5, rr)
                // The reader skips the remaining RTP and sender report while awaiting the control response.
                assertEquals(200, client.request("GET_PARAMETER").status)
                assertEquals(200, client.request("TEARDOWN").status)
            }
        } finally { server.stop() }
    }

    @Test fun udpSetupReservesBothServerPortsAndDeliversRtp() = runBlocking {
        val callbacks = FakeCallbacks()
        val port = freePort()
        val server = RtspServer(ServerConfig(port), callbacks)
        val (rtp, rtcp) = udpPair()
        server.start()
        try {
            WireClient(port).use { client ->
                assertEquals(200, client.request("DESCRIBE").status)
                val setup = client.request("SETUP", "${client.url}/trackID=0", mapOf("Transport" to "RTP/AVP;unicast;client_port=${rtp.localPort}-${rtcp.localPort}"))
                assertEquals(200, setup.status)
                assertTrue(setup.headers.getValue("transport").contains("server_port="))
                assertEquals(200, client.request("PLAY").status)
                server.publish(EncodedFrame(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3), 1_000_000, true))
                val incoming = DatagramPacket(ByteArray(2048), 2048)
                rtp.receive(incoming)
                assertTrue(incoming.length >= 12)
                assertEquals(96, incoming.data[1].toInt() and 0x7f)
                assertEquals(200, client.request("TEARDOWN").status)
            }
        } finally { rtp.close(); rtcp.close(); server.stop() }
    }

    @Test fun eofAndServerStopReleaseLeasesWithoutWaitingForSocketReadTimeout() = runBlocking {
        val callbacks = FakeCallbacks()
        val port = freePort()
        val server = RtspServer(ServerConfig(port), callbacks)
        server.start()
        try {
            WireClient(port).use { assertEquals(200, it.request("DESCRIBE").status) }
            awaitCondition { callbacks.leases.isEmpty() }
            WireClient(port).use { client ->
                assertEquals(200, client.request("DESCRIBE").status)
                val start = System.nanoTime()
                server.stop()
                assertTrue("stop waited for an idle socket", System.nanoTime() - start < 2_000_000_000)
                assertTrue(callbacks.leases.isEmpty())
            }
        } finally { server.stop() }
    }

    private class FakeCallbacks : RtspCallbacks {
        val leases: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val acquisitions = AtomicInteger()
        val connected = AtomicInteger()
        val playing = AtomicInteger()
        @Volatile var codec = CodecConfig(VideoCodec.H264, 1280, 720,
            byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1e), byteArrayOf(0, 0, 0, 1, 0x68, 0x26))
        override suspend fun acquireVideo(clientId: String): CodecConfig { acquisitions.incrementAndGet(); leases.add(clientId); return codec }
        override suspend fun releaseVideo(clientId: String) { leases.remove(clientId) }
        override fun requestKeyFrame() = Unit
        override fun onClientCounts(connected: Int, playing: Int) { this.connected.set(connected); this.playing.set(playing) }
        override fun onError(message: String) = Unit
    }

    private data class Response(val status: Int, val headers: Map<String, String>, val body: String)

    private class WireClient(port: Int) : Closeable {
        val url = "rtsp://127.0.0.1:$port/live"
        private val socket = Socket("127.0.0.1", port).apply { soTimeout = 3000; tcpNoDelay = true }
        private val input = socket.getInputStream().buffered()
        private val output = socket.getOutputStream()
        private var sequence = 0
        private var session: String? = null

        fun request(method: String, target: String = url, headers: Map<String, String> = emptyMap()): Response {
            val allHeaders = linkedMapOf("CSeq" to (++sequence).toString())
            session?.let { allHeaders["Session"] = it }
            allHeaders.putAll(headers)
            val wire = "$method $target RTSP/1.0\r\n" + allHeaders.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
            output.write(wire.toByteArray()); output.flush()
            var first = requiredByte()
            while (first == 36) { readBinaryAfterDollar(); first = requiredByte() }
            val bytes = ByteArrayOutputStream().apply { write(first) }
            while (!bytes.toString("US-ASCII").endsWith("\r\n\r\n")) bytes.write(requiredByte())
            val lines = bytes.toString("US-ASCII").trimEnd().split("\r\n")
            val responseHeaders = lines.drop(1).associate { val parts = it.split(':', limit = 2); parts[0].lowercase() to parts[1].trim() }
            val body = readExactly(responseHeaders["content-length"]?.toInt() ?: 0).toString(Charsets.UTF_8)
            responseHeaders["session"]?.let { session = it.substringBefore(';') }
            return Response(lines.first().split(' ')[1].toInt(), responseHeaders, body)
        }

        fun describeAndSetup(transport: String = "RTP/AVP/TCP;unicast;interleaved=0-1") {
            assertEquals(200, request("DESCRIBE").status)
            assertEquals(200, request("SETUP", "$url/trackID=0", mapOf("Transport" to transport)).status)
        }

        fun readInterleaved(): Pair<Int, ByteArray> { assertEquals(36, requiredByte()); return readBinaryAfterDollar() }
        fun sendInterleaved(channel: Int, bytes: ByteArray) {
            output.write(byteArrayOf(36, channel.toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()) + bytes); output.flush()
        }
        private fun readBinaryAfterDollar(): Pair<Int, ByteArray> {
            val channel = requiredByte()
            val length = (requiredByte() shl 8) or requiredByte()
            return channel to readExactly(length)
        }
        private fun requiredByte(): Int = input.read().also { if (it < 0) throw java.io.EOFException() }
        private fun readExactly(length: Int): ByteArray = ByteArray(length).also { bytes ->
            var position = 0
            while (position < length) {
                val count = input.read(bytes, position, length - position)
                if (count < 0) throw java.io.EOFException()
                position += count
            }
        }
        override fun close() { socket.close() }
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }
    private suspend fun awaitCondition(condition: () -> Boolean) {
        val limit = System.nanoTime() + 2_000_000_000
        while (!condition() && System.nanoTime() < limit) delay(10)
        assertTrue("Condition did not become true", condition())
    }
    private fun udpPair(): Pair<DatagramSocket, DatagramSocket> {
        repeat(100) {
            val first = DatagramSocket(0)
            if (first.localPort % 2 != 0 || first.localPort == 65535) { first.close(); return@repeat }
            val second = DatagramSocket(null)
            try {
                second.bind(InetSocketAddress("127.0.0.1", first.localPort + 1))
                first.soTimeout = 3000
                return first to second
            } catch (_: java.io.IOException) { first.close(); second.close() }
        }
        error("Could not reserve a UDP test port pair")
    }
}
