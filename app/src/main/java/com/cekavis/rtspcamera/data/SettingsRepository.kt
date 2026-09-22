package com.cekavis.rtspcamera.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cekavis.rtspcamera.model.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.settingsStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private val key = stringPreferencesKey("config_v1")
    private val alias = "rtsp_camera_credentials_v1"

    suspend fun read(): AppConfig {
        val raw = context.settingsStore.data.first()[key] ?: return AppConfig()
        return decodeSettings(raw, ::decrypt)
    }

    suspend fun save(config: AppConfig) {
        val serialized = encodeSettings(config, ::encrypt)
        context.settingsStore.edit { it[key] = serialized }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size >= 28) { "凭据数据损坏，请重新设置密码" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
}

/** Reads legacy fixed-FPS values as the same fixed range, without changing any stored setting. */
internal fun decodeSettings(raw: String, decryptCredential: (String) -> String): AppConfig {
    val json = JSONObject(raw)
    val video = json.getJSONObject("video")
    val server = json.getJSONObject("server")
    val fps = video.getInt("fps")
    val password = server.optString("credential").takeIf { it.isNotEmpty() }?.let(decryptCredential).orEmpty()
    return AppConfig(
        video = VideoConfig(
            cameraId = video.getString("camera"), width = video.getInt("width"), height = video.getInt("height"),
            fps = fps, bitrate = video.getInt("bitrate"), codec = VideoCodec.valueOf(video.getString("codec")),
            rotation = video.getInt("rotation"), mirror = video.getBoolean("mirror"),
            showTimestamp = video.getBoolean("time"), showBattery = video.getBoolean("battery"),
            fpsMin = if (video.has("fpsMin")) video.getInt("fpsMin") else fps,
        ),
        server = ServerConfig(server.getInt("port"), server.getBoolean("auth"), server.getString("user"), password, server.getBoolean("configured")),
        keepScreenOn = json.getBoolean("keepScreenOn"),
        audio = json.optJSONObject("audio")?.let { audio ->
            AudioConfig(
                source = AudioSource.valueOf(audio.getString("source")),
                deviceKey = audio.optString("device").takeIf { it.isNotEmpty() },
            )
        } ?: AudioConfig(),
    )
}

internal fun encodeSettings(config: AppConfig, encryptCredential: (String) -> String): String {
    val video = config.video
    val server = config.server
    return JSONObject().put("version", 3).put("keepScreenOn", config.keepScreenOn)
        .put("audio", JSONObject().put("source", config.audio.source.name).put("device", config.audio.deviceKey.orEmpty()))
        .put("video", JSONObject().put("camera", video.cameraId).put("width", video.width).put("height", video.height)
            .put("fps", video.fps).put("fpsMin", video.fpsMin).put("bitrate", video.bitrate)
            .put("codec", video.codec.name).put("rotation", video.rotation)
            .put("mirror", video.mirror).put("time", video.showTimestamp).put("battery", video.showBattery))
        .put("server", JSONObject().put("port", server.port).put("auth", server.authEnabled).put("user", server.username)
            .put("credential", if (server.password.isEmpty()) "" else encryptCredential(server.password))
            .put("configured", server.authConfigured))
        .toString()
}
