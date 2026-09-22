package com.cekavis.rtspcamera.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacerTest {
    @Test fun preservesRequestedAverageForNonDivisorFrameRates() {
        for (fps in listOf(10, 15, 20, 24, 25, 30)) {
            val pacer = FramePacer(fps)
            val accepted = (0 until 300).count { pacer.shouldRender(1_000_000_000L + it * 1_000_000_000L / 30) }
            assertEquals("30 FPS sensor at requested $fps FPS", fps * 10, accepted)
        }
    }

    @Test fun toleratesSensorJitterWithoutAccumulatingDrops() {
        val pacer = FramePacer(30)
        val accepted = (0 until 300).count {
            pacer.shouldRender(1_000_000_000L + it * 1_000_000_000L / 30 + if (it % 2 == 0) 400_000 else -400_000)
        }
        assertEquals(300, accepted)
    }

    @Test fun longGapResumesWithoutAnUnboundedCatchUpLoop() {
        val pacer = FramePacer(24)
        assertTrue(pacer.shouldRender(1_000_000_000L))
        assertTrue(pacer.shouldRender(3_601_000_000_000L))
        assertFalse(pacer.shouldRender(3_601_000_000_001L))
        assertTrue(pacer.shouldRender(3_601_050_000_000L))
    }
}
