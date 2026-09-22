package com.cekavis.rtspcamera.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cekavis.rtspcamera.model.AppConfig
import com.cekavis.rtspcamera.model.AudioConfig
import com.cekavis.rtspcamera.model.AudioSource
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory migration tests; never reads or replaces the user's saved configuration. */
@RunWith(AndroidJUnit4::class)
class SettingsAudioMigrationTest {
    @Test fun legacySettingsKeepAudioOff() {
        val original = AppConfig()
        for (version in 1..2) {
            val legacy = JSONObject(encodeSettings(original) { error("No credentials expected") }).apply {
                put("version", version)
                remove("audio")
            }
            val restored = decodeSettings(legacy.toString()) { error("No credentials expected") }
            assertEquals(original, restored)
            assertFalse(restored.audio.enabled)
        }
    }

    @Test fun audioChoicesAndExplicitDeviceSurviveSerialization() {
        listOf(
            AudioConfig(),
            AudioConfig(AudioSource.MICROPHONE),
            AudioConfig(AudioSource.MICROPHONE, "synthetic-usb-input"),
            AudioConfig(AudioSource.OFF, "synthetic-usb-input"),
        ).forEach { audio ->
            val original = AppConfig(audio = audio)
            val serialized = encodeSettings(original) { error("No credentials expected") }
            val restored = decodeSettings(serialized) { error("No credentials expected") }
            assertEquals(original, restored)
        }
    }
}
