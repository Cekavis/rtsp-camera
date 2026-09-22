package com.cekavis.rtspcamera.rtsp

import com.cekavis.rtspcamera.model.CodecConfig
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
