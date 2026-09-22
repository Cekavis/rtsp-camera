package com.cekavis.rtspcamera.rtsp

import org.junit.Assert.*
import org.junit.Test

class DigestAuthenticationTest {
    @Test fun everyProtectedMethodRequiresItsOwnValidSignature() {
        listOf("DESCRIBE", "SETUP", "PLAY", "PAUSE", "TEARDOWN", "GET_PARAMETER").forEach { method ->
            val auth = DigestAuthentication("camera", "test-password")
            val challenge = auth.challenge()
            assertEquals(DigestAuthentication.Result.REJECTED, auth.verify(null, method, TARGET))
            assertEquals(DigestAuthentication.Result.ACCEPTED, auth.verify(authorization(challenge, method), method, TARGET))
        }
    }

    @Test fun signatureCannotBeReusedForDifferentMethodOrTarget() {
        val auth = DigestAuthentication("camera", "test-password")
        val header = authorization(auth.challenge(), "DESCRIBE")
        assertEquals(DigestAuthentication.Result.REJECTED, auth.verify(header, "PLAY", TARGET))
        assertEquals(DigestAuthentication.Result.REJECTED, auth.verify(header, "DESCRIBE", "$TARGET/trackID=0"))
        assertEquals(DigestAuthentication.Result.ACCEPTED, auth.verify(header, "DESCRIBE", TARGET))
    }

    @Test fun replayedNonceCountIsRejectedAndForgedHighCountDoesNotAdvanceIt() {
        val auth = DigestAuthentication("camera", "test-password")
        val challenge = auth.challenge()
        val header = authorization(challenge, "DESCRIBE")
        assertEquals(DigestAuthentication.Result.ACCEPTED, auth.verify(header, "DESCRIBE", TARGET))
        assertEquals(DigestAuthentication.Result.REJECTED, auth.verify(header, "DESCRIBE", TARGET))
        val forged = authorization(challenge, "PLAY", "ffffffff", password = "wrong")
        assertEquals(DigestAuthentication.Result.REJECTED, auth.verify(forged, "PLAY", TARGET))
        assertEquals(DigestAuthentication.Result.ACCEPTED, auth.verify(authorization(challenge, "PLAY", "00000002"), "PLAY", TARGET))
    }

    @Test fun nonceIsBoundToConnectionAndExpires() {
        var time = 0L
        val auth = DigestAuthentication("camera", "test-password", { time }, 1000)
        val header = authorization(auth.challenge(), "DESCRIBE")
        val other = DigestAuthentication("camera", "test-password")
        other.challenge()
        assertEquals(DigestAuthentication.Result.REJECTED, other.verify(header, "DESCRIBE", TARGET))
        time = 1001
        assertEquals(DigestAuthentication.Result.STALE, auth.verify(header, "DESCRIBE", TARGET))
        val newChallenge = auth.challenge(stale = true)
        assertTrue(newChallenge.contains("stale=true"))
        assertEquals(DigestAuthentication.Result.REJECTED, auth.verify(header, "DESCRIBE", TARGET))
        assertEquals(DigestAuthentication.Result.ACCEPTED, auth.verify(authorization(newChallenge, "DESCRIBE"), "DESCRIBE", TARGET))
    }

    @Test fun duplicateFieldsAndMissingQopAreRejected() {
        val auth = DigestAuthentication("camera", "test-password")
        val header = authorization(auth.challenge(), "DESCRIBE")
        assertEquals(DigestAuthentication.Result.REJECTED, auth.verify("$header, username=\"camera\"", "DESCRIBE", TARGET))
        assertEquals(DigestAuthentication.Result.REJECTED, auth.verify(header.replace(", qop=auth", ""), "DESCRIBE", TARGET))
    }

    companion object {
        const val TARGET = "rtsp://127.0.0.1:8554/live"
        fun authorization(challenge: String, method: String, nc: String = "00000001", target: String = TARGET, password: String = "test-password"): String {
            val fields = requireNotNull(DigestAuthentication.parseDigestFields(challenge))
            val nonce = fields.getValue("nonce")
            val opaque = fields.getValue("opaque")
            val cnonce = "test-client-nonce"
            val ha1 = DigestAuthentication.md5("camera:${DigestAuthentication.REALM}:$password")
            val ha2 = DigestAuthentication.md5("$method:$target")
            val response = DigestAuthentication.md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
            return "Digest username=\"camera\", realm=\"${DigestAuthentication.REALM}\", nonce=\"$nonce\", uri=\"$target\", response=\"$response\", opaque=\"$opaque\", qop=auth, nc=$nc, cnonce=\"$cnonce\""
        }
    }
}
