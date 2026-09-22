package com.cekavis.rtspcamera.rtsp

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

class RtspProtocolTest {
    @Test fun binaryRtcpIsConsumedBeforeFollowingRtspRequest() {
        val payload = "\r\nPLAY /live RTSP/1.0\r\n".toByteArray()
        val first = "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray()
        val second = "GET_PARAMETER /live RTSP/1.0\r\nCSeq: 2\r\nContent-Length: 3\r\n\r\nabc".toByteArray()
        val wire = first + byteArrayOf(36, 1, 0, payload.size.toByte()) + payload + second
        val reader = RtspMessageReader(ByteArrayInputStream(wire))
        assertEquals("OPTIONS", (reader.read() as RtspInput.Request).value.method)
        val binary = reader.read() as RtspInput.Interleaved
        assertEquals(1, binary.channel)
        assertArrayEquals(payload, binary.data)
        val request = (reader.read() as RtspInput.Request).value
        assertEquals(2, request.cSeq)
        assertArrayEquals("abc".toByteArray(), request.body)
        assertNull(reader.read())
    }

    @Test fun duplicateContentLengthAndAuthorizationAreRejected() {
        listOf("Content-Length: 0\r\nContent-Length: 5", "Authorization: a\r\nAuthorization: b").forEach {
            assertProtocolError(400, "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n$it\r\n\r\n")
        }
    }

    @Test fun oversizedHeaderAndBodyAreRejectedBeforeAllocation() {
        assertProtocolError(413, "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nX-Fill: ${"x".repeat(17 * 1024)}\r\n\r\n")
        assertProtocolError(413, "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 99999\r\n\r\n")
    }

    @Test fun negativeLengthAndInvalidCseqAreRejected() {
        assertProtocolError(400, "OPTIONS * RTSP/1.0\r\nCSeq: -1\r\n\r\n")
        assertProtocolError(400, "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nContent-Length: -1\r\n\r\n")
    }

    @Test(expected = EOFException::class) fun truncatedBinaryPayloadIsEof() {
        RtspMessageReader(ByteArrayInputStream(byteArrayOf(36, 1, 0, 8, 1, 2))).read()
    }

    @Test fun transportSelectsSupportedAlternativeAndPreservesClientChannels() {
        assertEquals(TransportRequest(true, 4, 5), TransportRequest.parse("RTP/AVP;multicast,RTP/AVP/TCP;unicast;interleaved=4-5"))
        assertEquals(TransportRequest(false, 5000, 5001), TransportRequest.parse("RTP/AVP;unicast;client_port=5000-5001"))
    }

    @Test fun transportRejectsReflectionAndInvalidPairs() {
        listOf("RTP/AVP;client_port=5000-5001;destination=8.8.8.8", "RTP/AVP;client_port=5001-5002", "RTP/AVP/TCP;interleaved=0-256").forEach {
            try { TransportRequest.parse(it); fail("Transport must be rejected") }
            catch (error: RtspProtocolException) { assertEquals(461, error.status) }
        }
    }

    @Test fun responseLengthCountsUtf8Bytes() {
        val wire = responseBytes(455, 7, body = "重新协商").toString(Charsets.UTF_8)
        assertTrue(wire.contains("Content-Length: 12\r\n"))
    }

    @Test fun liveNptRangeAcceptsEquivalentDecimalAndHhmmssZeroForms() {
        val accepted = listOf(null, "npt=0-", "npt=0.000-", "npt=000.000-", "npt=0.-",
            "npt=0:00:00.000-", "npt=00:0:0-", "npt=0:00:00.-", "npt=now-", "NPT=NOW-", " npt = 0.000 -\t")
        accepted.forEach { assertTrue("Valid live range rejected: $it", isLiveNptRange(it)) }
    }

    @Test fun liveNptRangeRejectsNonzeroSeeksFiniteEndsAndMalformedValues() {
        val rejected = listOf("", "npt=1-", "npt=0.0001-", "npt=0:00:01-", "npt=0:01:00-", "npt=1:00:00-",
            "npt=-0-", "npt=+0-", "npt=0-0", "npt=now-1", "npt=0-now", "npt=-", "npt=-0", "npt=0--",
            "npt=.0-", "npt=0..0-", "npt=0e0-", "npt=NaN-", "npt=Infinity-", "npt=0:60:00-", "npt=0:00:60-",
            "npt=0:000:00-", "npt=0:00:000-", "npt=0:00-", "npt=0.000junk-", "npt=now-;time=20260922T000000Z",
            "npt=0-,npt=now-", "clock=0-", "npt=0-${'\n'}", "npt=0.${"0".repeat(400)}1-")
        rejected.forEach { assertFalse("Invalid live range accepted: $it", isLiveNptRange(it)) }
    }

    private fun assertProtocolError(status: Int, wire: String) {
        try { RtspMessageReader(ByteArrayInputStream(wire.toByteArray())).read(); fail("Malformed input accepted") }
        catch (error: RtspProtocolException) { assertEquals(status, error.status) }
    }
}
