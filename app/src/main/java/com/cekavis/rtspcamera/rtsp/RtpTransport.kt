package com.cekavis.rtspcamera.rtsp

import com.cekavis.rtspcamera.model.CodecConfig
import com.cekavis.rtspcamera.model.VideoCodec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/** Control responses and complete interleaved packets share this lock and buffered stream. */
internal class SocketWriter(socket: Socket) {
    private val output = BufferedOutputStream(socket.getOutputStream(), 32 * 1024)
    private val mutex = Mutex()
    val writeStartedNanos = AtomicLong(0)

    suspend fun response(bytes: ByteArray) = write { output.write(bytes) }

    suspend fun interleaved(channel: Int, packets: List<ByteArray>) = write {
        packets.forEach { packet ->
            require(packet.size <= 65535)
            output.write('$'.code)
            output.write(channel)
            output.write(packet.size ushr 8)
            output.write(packet.size and 0xff)
            output.write(packet)
        }
    }

    private suspend fun write(block: () -> Unit) = mutex.withLock {
        writeStartedNanos.set(System.nanoTime())
        try { block(); output.flush() } finally { writeStartedNanos.set(0) }
    }
}

internal sealed class RtpTransport {
    abstract suspend fun sendRtp(packets: List<ByteArray>)
    abstract suspend fun sendRtcp(packet: ByteArray)
    abstract fun responseHeader(ssrc: Long): String
    abstract fun close()

    class Tcp(val request: TransportRequest, private val writer: SocketWriter) : RtpTransport() {
        override suspend fun sendRtp(packets: List<ByteArray>) = writer.interleaved(request.first, packets)
        override suspend fun sendRtcp(packet: ByteArray) = writer.interleaved(request.second, listOf(packet))
        override fun responseHeader(ssrc: Long) =
            "RTP/AVP/TCP;unicast;interleaved=${request.first}-${request.second};ssrc=${ssrc.toString(16).padStart(8, '0')};mode=\"PLAY\""
        override fun close() = Unit // The RTSP connection also carries control responses.
    }

    class Udp private constructor(
        val request: TransportRequest,
        private val peer: InetAddress,
        private val rtp: DatagramSocket,
        val rtcp: DatagramSocket,
    ) : RtpTransport() {
        override suspend fun sendRtp(packets: List<ByteArray>) {
            packets.forEach { rtp.send(DatagramPacket(it, it.size, peer, request.first)) }
        }
        override suspend fun sendRtcp(packet: ByteArray) = rtcp.send(DatagramPacket(packet, packet.size, peer, request.second))
        override fun responseHeader(ssrc: Long) =
            "RTP/AVP/UDP;unicast;client_port=${request.first}-${request.second};server_port=${rtp.localPort}-${rtcp.localPort};ssrc=${ssrc.toString(16).padStart(8, '0')};mode=\"PLAY\""
        override fun close() { rtp.close(); rtcp.close() }

        companion object {
            fun open(request: TransportRequest, peer: InetAddress): Udp {
                val random = SecureRandom()
                repeat(64) {
                    val port = 20000 + random.nextInt(20000) * 2
                    val rtp = DatagramSocket(null)
                    val rtcp = DatagramSocket(null)
                    try {
                        rtp.bind(InetSocketAddress("0.0.0.0", port))
                        rtcp.bind(InetSocketAddress("0.0.0.0", port + 1))
                        rtcp.connect(peer, request.second)
                        rtcp.soTimeout = 1000
                        return Udp(request, peer, rtp, rtcp)
                    } catch (_: java.io.IOException) { rtp.close(); rtcp.close() }
                }
                throw RtspProtocolException(503, "UDP ports unavailable")
            }
        }
    }
}

internal object Rtcp {
    fun isValid(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        var position = 0
        while (position < bytes.size) {
            if (position + 4 > bytes.size || (bytes[position].toInt() and 0xc0) != 0x80) return false
            val type = bytes[position + 1].toInt() and 0xff
            if (type !in 200..207) return false
            val size = ((((bytes[position + 2].toInt() and 0xff) shl 8) or (bytes[position + 3].toInt() and 0xff)) + 1) * 4
            if (size < 4 || position + size > bytes.size) return false
            if ((type == 200 && size < 28) || (type == 201 && size < 8)) return false
            position += size
        }
        return position == bytes.size
    }

    fun hasBye(bytes: ByteArray): Boolean {
        if (!isValid(bytes)) return false
        var offset = 0
        while (offset < bytes.size) {
            if ((bytes[offset + 1].toInt() and 0xff) == 203) return true
            offset += ((((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)) + 1) * 4
        }
        return false
    }

    /** RFC 3550 compound SR + SDES. Each client's SSRC, counters, and RTP timeline are independent. */
    fun senderReport(ssrc: Long, rtpTimestamp: Long, packets: Long, octets: Long, nowMillis: Long): ByteArray {
        val cname = "rtsp-camera-${ssrc.toString(16)}".toByteArray(Charsets.US_ASCII)
        val sdesSize = ((4 + 4 + 2 + cname.size + 1 + 3) / 4) * 4
        val buffer = ByteBuffer.allocate(28 + sdesSize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(0x80.toByte()).put(200.toByte()).putShort(6)
        buffer.putInt(ssrc.toInt())
        buffer.putInt((nowMillis / 1000 + 2_208_988_800L).toInt())
        buffer.putInt(((nowMillis % 1000) * 0x1_0000_0000L / 1000).toInt())
        buffer.putInt(rtpTimestamp.toInt()).putInt(packets.toInt()).putInt(octets.toInt())
        buffer.put(0x81.toByte()).put(202.toByte()).putShort((sdesSize / 4 - 1).toShort())
        buffer.putInt(ssrc.toInt()).put(1).put(cname.size.toByte()).put(cname).put(0)
        return buffer.array()
    }
}

internal fun codecSdp(config: CodecConfig, address: String, origin: Long): String {
    val sps = withoutStartCode(config.sps)
    val pps = withoutStartCode(config.pps)
    if (sps.isEmpty() || pps.isEmpty()) throw RtspProtocolException(503, "Codec configuration unavailable")
    val base64 = Base64.getEncoder()
    val format = when (config.codec) {
        VideoCodec.H264 -> {
            if (sps.size < 4) throw RtspProtocolException(503, "Invalid H264 SPS")
            val profile = sps.copyOfRange(1, 4).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            "a=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;profile-level-id=$profile;sprop-parameter-sets=${base64.encodeToString(sps)},${base64.encodeToString(pps)}\r\n"
        }
        VideoCodec.HEVC -> {
            val vps = config.vps?.let(::withoutStartCode)?.takeIf { it.isNotEmpty() }
                ?: throw RtspProtocolException(503, "HEVC VPS unavailable")
            "a=rtpmap:96 H265/90000\r\na=fmtp:96 sprop-vps=${base64.encodeToString(vps)};sprop-sps=${base64.encodeToString(sps)};sprop-pps=${base64.encodeToString(pps)}\r\n"
        }
    }
    val ipFamily = if (address.contains(':')) "IP6" else "IP4"
    return "v=0\r\no=- $origin ${config.generation} IN $ipFamily $address\r\ns=RTSP Camera\r\n" +
        "c=IN $ipFamily $address\r\nt=0 0\r\na=control:*\r\na=range:npt=0-\r\n" +
        "m=video 0 RTP/AVP 96\r\n$format" +
        "a=framesize:96 ${config.width}-${config.height}\r\na=control:trackID=0\r\na=sendonly\r\n"
}

internal fun withoutStartCode(bytes: ByteArray): ByteArray = when {
    bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0.toByte() && bytes[3] == 1.toByte() -> bytes.copyOfRange(4, bytes.size)
    bytes.size >= 3 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte() -> bytes.copyOfRange(3, bytes.size)
    else -> bytes.copyOf()
}
