package com.cekavis.rtspcamera.media

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCaptureTimingTest {
    @Test fun hardwareTimestampPlacesSamplesOnTheSharedMonotonicClock() {
        val clock = AudioSampleClock(48_000)
        val captureStartUs = 9_000_000_000L
        // Reading can lag capture: timestamp position is independent of the read's completion time.
        clock.anchor(2_048, captureStartUs + 90_000, 4_800, (captureStartUs + 100_000) * 1_000)
        assertEquals(captureStartUs, clock.presentationTimeUs(0))
        assertEquals(captureStartUs + 21_333, clock.presentationTimeUs(1_024))
        assertEquals(captureStartUs + 1_000_000, clock.presentationTimeUs(48_000))
    }

    @Test fun fallbackAccountsForAllSamplesAlreadyReadIncludingStartupDiscard() {
        val clock = AudioSampleClock(48_000)
        clock.anchor(12_000, 20_250_000, null, null)
        assertEquals(20_000_000, clock.presentationTimeUs(0))
        assertEquals(20_228_666, clock.presentationTimeUs(10_976))
    }

    @Test fun laterReadsCannotReanchorAndIntroduceClockJumps() {
        val clock = AudioSampleClock(48_000)
        clock.anchor(4_800, 1_100_000, null, null)
        clock.anchor(9_600, 90_000_000, 9_600, 80_000_000_000)
        assertEquals(1_200_000, clock.presentationTimeUs(9_600))
    }

    @Test fun timestampRoundingDoesNotAccumulateAcrossAacFrames() {
        val clock = AudioSampleClock(48_000)
        clock.anchor(0, 10_000_000, null, null)
        assertEquals(10_000_000L + 86_400_000_000L, clock.presentationTimeUs(48_000L * 86_400))
        assertTrue((0 until 100).all { clock.presentationTimeUs((it + 1L) * 1_024) > clock.presentationTimeUs(it * 1_024L) })
    }

    @Test fun addressedMicrophoneIdentitySurvivesPortRenumbering() {
        assertEquals(
            microphoneDeviceKey(AudioDeviceInfo.TYPE_USB_DEVICE, "usb:1", "Microphone", 3, 5),
            microphoneDeviceKey(AudioDeviceInfo.TYPE_USB_DEVICE, "usb:1", "Microphone", 40, 6),
        )
        assertNotEquals(
            microphoneDeviceKey(AudioDeviceInfo.TYPE_USB_DEVICE, "usb:1", "Microphone", 3, 5),
            microphoneDeviceKey(AudioDeviceInfo.TYPE_USB_DEVICE, "usb:2", "Microphone", 3, 5),
        )
    }

    @Test fun unidentifiedPortsCannotMatchReusedIdentifiersAfterReboot() {
        val first = microphoneDeviceKey(AudioDeviceInfo.TYPE_BUILTIN_MIC, "", "Phone", 3, 5)
        assertNotEquals(first, microphoneDeviceKey(AudioDeviceInfo.TYPE_BUILTIN_MIC, "", "Phone", 3, 6))
        assertNotEquals(first, microphoneDeviceKey(AudioDeviceInfo.TYPE_BUILTIN_MIC, "", "Phone", 4, 5))
    }

    @Test fun microphoneListExcludesPlaybackLoopbackAndTelephonyInputs() {
        listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET).forEach { assertTrue(isMicrophoneType(it)) }
        listOf(AudioDeviceInfo.TYPE_REMOTE_SUBMIX, AudioDeviceInfo.TYPE_TELEPHONY,
            AudioDeviceInfo.TYPE_FM_TUNER, AudioDeviceInfo.TYPE_TV_TUNER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).forEach { assertFalse(isMicrophoneType(it)) }
    }
}
