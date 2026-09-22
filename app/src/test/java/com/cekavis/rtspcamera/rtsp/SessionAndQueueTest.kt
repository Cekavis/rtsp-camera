package com.cekavis.rtspcamera.rtsp

import com.cekavis.rtspcamera.model.AudioCodecConfig
import com.cekavis.rtspcamera.model.CodecConfig
import com.cekavis.rtspcamera.model.EncodedAudioFrame
import com.cekavis.rtspcamera.model.EncodedFrame
import com.cekavis.rtspcamera.model.VideoCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionAndQueueTest {
    @Test fun playCannotBypassDescribeAndSetup() {
        val state = SessionState()
        assertInvalid { state.play() }
        state.describe()
        assertInvalid { state.play() }
        state.setup()
        state.play()
        assertEquals(SessionPhase.PLAYING, state.phase)
    }

    @Test fun pauseResumeAndRepeatedPlayAreIdempotent() {
        val state = SessionState()
        state.describe(); state.setup(); state.play(); state.play(); state.pause(); state.pause(); state.play()
        assertEquals(SessionPhase.PLAYING, state.phase)
        assertInvalid { state.describe() }
        state.close()
        assertInvalid { state.play() }
    }

    @Test fun overflowDiscardsDependentFramesUntilNewKeyframe() = runBlocking {
        val queue = ClientFrameQueue(maxFrames = 2, maxBytes = 12)
        assertEquals(ClientFrameQueue.Offer.WAITING_FOR_KEYFRAME, queue.offer(frame(1, false)))
        queue.offer(frame(2, true)); queue.offer(frame(3, false))
        assertEquals(ClientFrameQueue.Offer.RESYNC_REQUIRED, queue.offer(frame(4, false)))
        assertEquals(0, queue.bufferedFrames())
        assertEquals(ClientFrameQueue.Offer.WAITING_FOR_KEYFRAME, queue.offer(frame(5, false)))
        queue.offer(frame(6, true))
        assertEquals(6L, queue.take()!!.presentationTimeUs)
        queue.close()
        assertNull(queue.take())
    }

    @Test fun slowQueueCannotExhaustBytesOrAffectHealthyQueue() = runBlocking {
        val slow = ClientFrameQueue(maxFrames = 100, maxBytes = 10)
        val healthy = ClientFrameQueue(maxFrames = 100, maxBytes = 10)
        for (index in 1..100) {
            val value = frame(index, true)
            slow.offer(value)
            assertEquals(ClientFrameQueue.Offer.ACCEPTED, healthy.offer(value))
            assertEquals(index.toLong(), healthy.take()!!.presentationTimeUs)
            assertTrue(slow.bufferedBytes() <= 10)
        }
        assertEquals(ClientFrameQueue.Offer.RESYNC_REQUIRED, slow.offer(EncodedFrame(ByteArray(11), 101, true)))
        assertEquals(0, slow.bufferedBytes())
    }

    @Test fun hevcSdpContainsActualParameterSetsWithoutAnnexBPrefixes() {
        val codec = CodecConfig(VideoCodec.HEVC, 1280, 720, byteArrayOf(0, 0, 0, 1, 66, 1), byteArrayOf(0, 0, 1, 68, 1), byteArrayOf(64, 1))
        val sdp = codecSdp(codec, "192.168.1.1", 1)
        assertTrue(sdp.contains("H265/90000"))
        assertTrue(sdp.contains("sprop-vps=QAE=;sprop-sps=QgE=;sprop-pps=RAE="))
        assertFalse(sdp.contains("m=audio"))
        assertTrue(sdp.contains("a=control:trackID=0"))
    }

    @Test fun aacSdpUsesConfiguredSamplingAndAudioSpecificConfig() {
        val codec = CodecConfig(VideoCodec.H264, 1280, 720, byteArrayOf(0x67, 0x42, 0, 0x1e), byteArrayOf(0x68, 0x26))
        val sdp = codecSdp(codec, "127.0.0.1", 1, AudioCodecConfig())
        assertTrue(sdp.contains("m=video 0 RTP/AVP 96\r\n"))
        assertTrue(sdp.contains("m=audio 0 RTP/AVP 97\r\n"))
        assertTrue(sdp.contains("a=rtpmap:97 MPEG4-GENERIC/48000/1\r\n"))
        assertTrue(sdp.contains("mode=AAC-hbr;config=1188;SizeLength=13;IndexLength=3;IndexDeltaLength=3\r\n"))
        assertTrue(sdp.contains("a=control:trackID=1\r\n"))
        val stereo = codecSdp(codec, "127.0.0.1", 1, AudioCodecConfig(44_100, 2, byteArrayOf(0x12, 0x10)))
        assertTrue(stereo.contains("MPEG4-GENERIC/44100/2"))
        assertTrue(stereo.contains("config=1210;"))
    }

    @Test fun audioQueueDropsOldAccessUnitsWithinFrameAndByteLimits() = runBlocking {
        val queue = ClientAudioQueue(maxFrames = 3, maxBytes = 10)
        repeat(100) { index ->
            queue.offer(EncodedAudioFrame(ByteArray(4), index.toLong()))
            assertTrue(queue.bufferedFrames() <= 3)
            assertTrue(queue.bufferedBytes() <= 10)
        }
        assertEquals(98L, queue.take()!!.presentationTimeUs)
        assertEquals(99L, queue.take()!!.presentationTimeUs)
        queue.offer(EncodedAudioFrame(ByteArray(11), 100))
        assertEquals(0, queue.bufferedFrames())
        queue.close()
        assertNull(queue.take())
    }

    @Test fun sharedRtpClockKeepsCaptureOffsetsAcrossPausesAndTimestampWrap() {
        val clock = RtpClock(anchorUs = 1_000_000, anchorMillis = 100_000)
        assertEquals(101_234L, clock.wallTimeMillis(2_234_000))
        assertEquals(90_000L, RtpClock.timestamp(1_000_000, 90_000))
        assertEquals(53_760L, RtpClock.timestamp(1_120_000, 48_000))
        assertEquals(270_000L, RtpClock.timestamp(3_000_000, 90_000))
        assertEquals(149_760L, RtpClock.timestamp(3_120_000, 48_000))
        assertEquals(32_774L, RtpClock.timestamp(47_722_223_000, 90_000))
    }

    @Test fun compoundRtcpSenderReportIsValidAndRejectsTruncation() {
        val report = Rtcp.senderReport(5, 90000, 20, 1000, 1000)
        assertTrue(Rtcp.isValid(report))
        assertFalse(Rtcp.hasBye(report))
        assertFalse(Rtcp.isValid(report.copyOf(report.size - 1)))
        assertEquals(200, report[1].toInt() and 0xff)
        assertEquals(202, report[29].toInt() and 0xff)
    }

    private fun frame(time: Int, key: Boolean) = EncodedFrame(ByteArray(4), time.toLong(), key)
    private fun assertInvalid(block: () -> Unit) {
        try { block(); fail("Illegal state accepted") } catch (error: RtspProtocolException) { assertEquals(455, error.status) }
    }
}
