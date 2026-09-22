package com.cekavis.rtspcamera.rtsp

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Connection-bound RFC 7616 Digest (MD5, qop=auth). No credential-bearing value is logged. */
internal class DigestAuthentication(
    private val username: String,
    private val password: String,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val nonceLifetimeMillis: Long = 5 * 60_000,
) {
    enum class Result { ACCEPTED, REJECTED, STALE }

    private data class Nonce(val value: String, val opaque: String, val created: Long, var lastCount: Long = 0)
    private val random = SecureRandom()
    private var nonce: Nonce? = null

    fun challenge(stale: Boolean = false): String {
        val current = nonce?.takeIf { !stale && nowMillis() - it.created < nonceLifetimeMillis }
            ?: Nonce(token(), token(), nowMillis()).also { nonce = it }
        return "Digest realm=\"$REALM\", nonce=\"${current.value}\", opaque=\"${current.opaque}\", algorithm=MD5, qop=\"auth\", charset=UTF-8" +
            if (stale) ", stale=true" else ""
    }

    fun verify(authorization: String?, method: String, target: String): Result {
        val fields = authorization?.let(::parseDigestFields) ?: return Result.REJECTED
        val current = nonce ?: return Result.REJECTED
        if (fields["nonce"] != current.value) return Result.REJECTED
        if (nowMillis() - current.created >= nonceLifetimeMillis) return Result.STALE
        if (fields["username"] != username || fields["realm"] != REALM || fields["uri"] != target ||
            fields["opaque"] != current.opaque || fields["qop"]?.lowercase() != "auth" ||
            !fields.getOrDefault("algorithm", "MD5").equals("MD5", true)
        ) return Result.REJECTED
        val nc = fields["nc"]?.takeIf { it.matches(Regex("[0-9a-fA-F]{8}")) } ?: return Result.REJECTED
        val count = nc.toLong(16)
        if (count <= current.lastCount) return Result.REJECTED
        val cnonce = fields["cnonce"]?.takeIf { it.length in 1..256 } ?: return Result.REJECTED
        val supplied = fields["response"]?.takeIf { it.matches(Regex("[0-9a-fA-F]{32}")) } ?: return Result.REJECTED
        val ha1 = md5("$username:$REALM:$password")
        val ha2 = md5("$method:$target")
        val expected = md5("$ha1:${current.value}:$nc:$cnonce:auth:$ha2")
        if (!MessageDigest.isEqual(expected.toByteArray(UTF_8), supplied.lowercase().toByteArray(UTF_8))) return Result.REJECTED
        // Advance only after a valid signature; a forged high nc cannot invalidate valid requests.
        current.lastCount = count
        return Result.ACCEPTED
    }

    private fun token() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(random::nextBytes))

    companion object {
        const val REALM = "RTSP Camera"
        internal fun md5(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray(UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        internal fun parseDigestFields(value: String): Map<String, String>? {
            val space = value.indexOf(' ')
            if (space < 0 || !value.substring(0, space).equals("Digest", true)) return null
            val fields = linkedMapOf<String, String>()
            var index = space + 1
            while (index < value.length) {
                while (index < value.length && (value[index] == ' ' || value[index] == '\t')) index++
                val start = index
                while (index < value.length && (value[index].isLetterOrDigit() || value[index] in "_-")) index++
                if (index == start) return null
                val key = value.substring(start, index).lowercase()
                while (index < value.length && value[index].isWhitespace()) index++
                if (index >= value.length || value[index++] != '=') return null
                while (index < value.length && value[index].isWhitespace()) index++
                if (index >= value.length) return null
                val part = StringBuilder()
                if (value[index] == '"') {
                    index++
                    var closed = false
                    while (index < value.length) {
                        val character = value[index++]
                        if (character == '"') { closed = true; break }
                        if (character == '\\') {
                            if (index >= value.length) return null
                            part.append(value[index++])
                        } else part.append(character)
                    }
                    if (!closed) return null
                } else {
                    while (index < value.length && value[index] != ',' && !value[index].isWhitespace()) part.append(value[index++])
                }
                if (part.isEmpty() || part.any { it.code < 32 } || fields.put(key, part.toString()) != null) return null
                while (index < value.length && value[index].isWhitespace()) index++
                if (index < value.length && value[index++] != ',') return null
                if (index == value.length && value.lastOrNull() == ',') return null
            }
            return fields
        }
    }
}
