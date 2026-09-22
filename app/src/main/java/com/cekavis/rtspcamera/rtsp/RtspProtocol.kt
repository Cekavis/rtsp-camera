package com.cekavis.rtspcamera.rtsp

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets

internal class RtspProtocolException(val status: Int, message: String) : IOException(message)

internal data class RtspRequest(
    val method: String,
    val target: String,
    val cSeq: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]
    override fun toString() = "RtspRequest(method=$method, cSeq=$cSeq, headers=<redacted>)"
}

internal sealed interface RtspInput {
    data class Request(val value: RtspRequest) : RtspInput
    data class Interleaved(val channel: Int, val data: ByteArray) : RtspInput
}

/** One reader owns the socket input. Binary RTCP never passes through a text decoder. */
internal class RtspMessageReader(
    private val input: InputStream,
    private val maxHeaderBytes: Int = 16 * 1024,
    private val maxBodyBytes: Int = 8 * 1024,
    private val maxInterleavedBytes: Int = 16 * 1024,
) {
    fun read(): RtspInput? {
        val first = input.read()
        if (first == -1) return null
        if (first == '$'.code) {
            val channel = requiredByte()
            val length = (requiredByte() shl 8) or requiredByte()
            if (length > maxInterleavedBytes) throw RtspProtocolException(413, "Interleaved packet too large")
            return RtspInput.Interleaved(channel, readExactly(length))
        }
        val bytes = ByteArrayOutputStream()
        bytes.write(first)
        var ending = if (first == '\r'.code) 1 else 0
        while (ending != 4) {
            if (bytes.size() >= maxHeaderBytes) throw RtspProtocolException(413, "Headers too large")
            val value = requiredByte()
            if (value == 0 || value > 127) throw RtspProtocolException(400, "Invalid header encoding")
            bytes.write(value)
            ending = when {
                ending == 0 && value == '\r'.code -> 1
                ending == 1 && value == '\n'.code -> 2
                ending == 2 && value == '\r'.code -> 3
                ending == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
        }
        val lines = bytes.toString(StandardCharsets.US_ASCII.name()).removeSuffix("\r\n\r\n").split("\r\n")
        val start = lines.first().split(' ')
        if (start.size != 3 || !start[0].matches(Regex("[A-Z_]+")) || start[1].isEmpty()) {
            throw RtspProtocolException(400, "Invalid request line")
        }
        if (start[2] != "RTSP/1.0") throw RtspProtocolException(505, "Unsupported RTSP version")
        val headers = linkedMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val colon = line.indexOf(':')
            if (colon <= 0 || line.startsWith(' ') || line.startsWith('\t')) {
                throw RtspProtocolException(400, "Malformed header")
            }
            val name = line.substring(0, colon).lowercase()
            val value = line.substring(colon + 1).trim()
            if (!name.matches(Regex("[a-z0-9!#$%&'*+.^_`|~-]+")) ||
                value.any { it.code < 32 && it != '\t' } || headers.put(name, value) != null
            ) throw RtspProtocolException(400, "Invalid or duplicate header")
        }
        val cSeqText = headers["cseq"] ?: throw RtspProtocolException(400, "CSeq required")
        val cSeq = cSeqText.takeIf { it.matches(Regex("[0-9]{1,10}")) }?.toIntOrNull()
            ?: throw RtspProtocolException(400, "Invalid CSeq")
        val length = headers["content-length"]?.let {
            it.takeIf { value -> value.matches(Regex("[0-9]{1,10}")) }?.toIntOrNull()
                ?: throw RtspProtocolException(400, "Invalid Content-Length")
        } ?: 0
        if (length > maxBodyBytes) throw RtspProtocolException(413, "Request body too large")
        return RtspInput.Request(RtspRequest(start[0], start[1], cSeq, headers, readExactly(length)))
    }

    private fun requiredByte(): Int = input.read().also { if (it == -1) throw EOFException("Truncated RTSP message") }

    private fun readExactly(length: Int): ByteArray {
        val bytes = ByteArray(length)
        var position = 0
        while (position < length) {
            val count = input.read(bytes, position, length - position)
            if (count == -1) throw EOFException("Truncated RTSP payload")
            if (count == 0) continue
            position += count
        }
        return bytes
    }
}

internal data class TransportRequest(
    val tcp: Boolean,
    val first: Int,
    val second: Int,
) {
    companion object {
        fun parse(value: String?): TransportRequest {
            if (value == null) throw RtspProtocolException(461, "Transport required")
            // A client may offer alternatives. Select the first supported unicast transport.
            value.split(',').forEach { alternative ->
                val fields = alternative.trim().split(';').map(String::trim)
                val protocol = fields.first().uppercase()
                val tcp = protocol == "RTP/AVP/TCP"
                if (!tcp && protocol !in setOf("RTP/AVP", "RTP/AVP/UDP")) return@forEach
                if (fields.drop(1).any { it.equals("multicast", true) }) return@forEach
                val parameters = fields.drop(1).mapNotNull {
                    val pair = it.split('=', limit = 2)
                    if (pair.size == 2) pair[0].lowercase() to pair[1].trim('"') else null
                }
                if (parameters.map { it.first }.distinct().size != parameters.size) return@forEach
                val options = parameters.toMap()
                if (options["mode"]?.equals("play", true) == false) return@forEach
                // A requester cannot direct video at an unrelated host (UDP reflection).
                if (options.containsKey("destination")) return@forEach
                val pair = options[if (tcp) "interleaved" else "client_port"] ?: if (tcp) "0-1" else return@forEach
                val match = Regex("([0-9]{1,5})-([0-9]{1,5})").matchEntire(pair) ?: return@forEach
                val first = match.groupValues[1].toInt()
                val second = match.groupValues[2].toInt()
                if (tcp && first in 0..254 && second == first + 1) return TransportRequest(true, first, second)
                if (!tcp && first in 1024..65534 && first % 2 == 0 && second == first + 1) {
                    return TransportRequest(false, first, second)
                }
            }
            throw RtspProtocolException(461, "Unsupported transport")
        }
    }
}

internal fun targetPath(target: String): String {
    if (target == "*") return target
    val uri = try { URI(target) } catch (_: Exception) { throw RtspProtocolException(400, "Invalid URI") }
    if ((uri.isAbsolute && (uri.scheme?.lowercase() != "rtsp" || uri.host == null)) || uri.rawQuery != null || uri.rawFragment != null) {
        throw RtspProtocolException(400, "Invalid RTSP URI")
    }
    return uri.rawPath ?: ""
}

// RFC 2326 section 3.6: decimal seconds or h:mm:ss, with an optional decimal fraction.
// Only an open-ended range starting at zero or "now" can be served by this live source.
private val nptNumber = Regex("[0-9]+(?:\\.[0-9]*)?|[0-9]+:[0-5]?[0-9]:[0-5]?[0-9](?:\\.[0-9]*)?")

internal fun isLiveNptRange(value: String?): Boolean {
    if (value == null) return true
    val parts = value.split('=', limit = 2)
    if (parts.size != 2 || !parts[0].trim(' ', '\t').equals("npt", true)) return false
    val bounds = parts[1].split('-', limit = 3)
    if (bounds.size != 2 || bounds[1].trim(' ', '\t').isNotEmpty()) return false
    val start = bounds[0].trim(' ', '\t')
    if (start.equals("now", true)) return true
    // Check exact decimal zero after validating the grammar. Double conversion could round a
    // very small nonzero seek to zero; hours also need not fit in a machine integer.
    return nptNumber.matches(start) && start.none { it in '1'..'9' }
}

internal fun responseBytes(
    status: Int,
    cSeq: Int,
    headers: Map<String, String> = emptyMap(),
    body: String = "",
): ByteArray {
    val reason = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Request Entity Too Large"
        451 -> "Parameter Not Understood"
        453 -> "Not Enough Bandwidth"
        454 -> "Session Not Found"
        455 -> "Method Not Valid in This State"
        461 -> "Unsupported Transport"
        503 -> "Service Unavailable"
        505 -> "RTSP Version Not Supported"
        else -> "Internal Server Error"
    }
    val payload = body.toByteArray(StandardCharsets.UTF_8)
    val head = buildString {
        append("RTSP/1.0 $status $reason\r\nCSeq: $cSeq\r\nServer: RTSP Camera\r\n")
        headers.forEach { (key, value) ->
            require(!key.contains('\r') && !key.contains('\n') && !value.contains('\r') && !value.contains('\n'))
            append("$key: $value\r\n")
        }
        append("Content-Length: ${payload.size}\r\n\r\n")
    }.toByteArray(StandardCharsets.US_ASCII)
    return head + payload
}
