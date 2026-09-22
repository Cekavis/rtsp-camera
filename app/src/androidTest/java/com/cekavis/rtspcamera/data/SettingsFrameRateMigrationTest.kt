package com.cekavis.rtspcamera.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cekavis.rtspcamera.model.AppConfig
import com.cekavis.rtspcamera.model.ServerConfig
import com.cekavis.rtspcamera.model.VideoCodec
import com.cekavis.rtspcamera.model.VideoConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises in-memory serialization only. Does not touch DataStore, Android Keystore or user settings. */
@RunWith(AndroidJUnit4::class)
class SettingsFrameRateMigrationTest {
    @Test
    fun legacyFixedRateRemainsFixedAndPreservesAuthenticationAndOtherSettings() {
        val original = configured(fps = 25, fpsMin = 25)
        val legacy = JSONObject(encodeSettings(original, ::encryptSyntheticCredential)).apply {
            put("version", 1)
            getJSONObject("video").remove("fpsMin")
        }
        val restored = decodeSettings(legacy.toString(), ::decryptSyntheticCredential)
        assertEquals(25, restored.video.fps)
        assertEquals(25, restored.video.fpsMin)
        assertTrue("旧配置必须逐项保留，不得映射到相近的原生范围或重置认证", restored == original)
        assertTrue(restored.server.authEnabled && restored.server.authConfigured)
    }

    @Test
    fun newVariableRangeRoundTripsWithoutPlaintextCredentials() {
        val original = configured(fps = 30, fpsMin = 5)
        val serialized = encodeSettings(original, ::encryptSyntheticCredential)
        val json = JSONObject(serialized)
        assertEquals(2, json.getInt("version"))
        assertEquals(5, json.getJSONObject("video").getInt("fpsMin"))
        assertEquals(30, json.getJSONObject("video").getInt("fps"))
        assertFalse("序列化结果不能包含明文凭据", serialized.contains(original.server.password))
        assertTrue("范围、认证与其他配置应完整往返", decodeSettings(serialized, ::decryptSyntheticCredential) == original)
    }

    @Test
    fun explicitlyStoredRangeIsNotSilentlyNormalizedDuringRead() {
        val original = configured(fps = 29, fpsMin = 17)
        val restored = decodeSettings(encodeSettings(original, ::encryptSyntheticCredential), ::decryptSyntheticCredential)
        assertEquals(17, restored.video.fpsMin)
        assertEquals(29, restored.video.fps)
        assertTrue("不支持的范围应留给能力验证提示，不得在读取时替换用户配置", restored == original)
    }

    private fun configured(fps: Int, fpsMin: Int) = AppConfig(
        video = VideoConfig(cameraId = "0", width = 2592, height = 1944, fps = fps, bitrate = 10_000_000,
            codec = VideoCodec.HEVC, rotation = 0, mirror = false, showTimestamp = true, showBattery = true, fpsMin = fpsMin),
        server = ServerConfig(port = 8559, authEnabled = true, username = "synthetic-test-user",
            password = SYNTHETIC_PASSWORD, authConfigured = true),
        keepScreenOn = true,
    )

    private fun encryptSyntheticCredential(value: String): String {
        assertTrue("测试只允许内存中的合成凭据", value == SYNTHETIC_PASSWORD)
        return SYNTHETIC_CIPHERTEXT
    }

    private fun decryptSyntheticCredential(value: String): String {
        assertTrue("测试不应读取用户凭据", value == SYNTHETIC_CIPHERTEXT)
        return SYNTHETIC_PASSWORD
    }

    companion object {
        private const val SYNTHETIC_PASSWORD = "temporary-in-memory-migration-test-value"
        private const val SYNTHETIC_CIPHERTEXT = "synthetic-ciphertext-placeholder"
    }
}
