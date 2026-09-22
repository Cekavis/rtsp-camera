package com.cekavis.rtspcamera.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import com.cekavis.rtspcamera.model.MicrophoneOption
import java.util.Base64
import java.util.UUID

/** Lists microphone inputs only; null in AudioConfig means Android's default microphone route. */
class MicrophoneInputs(context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun enumerate(): List<MicrophoneOption> = devices().map { device ->
        MicrophoneOption(key(device), label(device))
    }.sortedWith(compareBy({ it.label }, { it.key }))

    internal fun resolve(deviceKey: String?): AudioDeviceInfo? {
        if (deviceKey == null) return null
        val matches = devices().filter { key(it) == deviceKey }
        check(matches.size == 1) {
            if (matches.isEmpty()) "所选麦克风已断开或设备标识已改变，请重新选择音频来源"
            else "无法唯一识别所选麦克风，请重新连接设备或选择系统默认麦克风"
        }
        return matches.single()
    }

    private fun devices(): List<AudioDeviceInfo> = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        .filter { it.isSource && isMicrophoneType(it.type) }
        .filter { !isBluetoothMicrophone(it.type) || bluetoothSupported(it) }

    private fun bluetoothSupported(device: AudioDeviceInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 31) communicationDevice(manager, device) != null
        else device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && manager.isBluetoothScoAvailableOffCall

    private fun key(device: AudioDeviceInfo): String = microphoneDeviceKey(
        device.type, deviceAddress(device), device.productName.toString(), device.id, bootCount,
    )

    private fun label(device: AudioDeviceInfo): String {
        val kind = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机麦克风"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 麦克风"
            else -> "蓝牙麦克风"
        }
        val name = device.productName.toString().trim()
        val location = if (device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) deviceAddress(device) else ""
        return listOf(kind, name, location).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    }
}

/** Never persist a bare AudioDeviceInfo.id: Android can reuse it after a reboot. */
internal fun microphoneDeviceKey(type: Int, address: String, productName: String, portId: Int, bootCount: Int): String {
    fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    val boot = if (bootCount >= 0) bootCount.toString() else unidentifiedBoot
    val identity = if (address.isNotBlank()) "address:${encode(address)}" else "port:$boot:$portId"
    return "$type:$identity:${encode(productName)}"
}

// A vendor that does not expose BOOT_COUNT cannot offer a persistent identity for unnamed ports.
private val unidentifiedBoot = UUID.randomUUID().toString()

internal fun isMicrophoneType(type: Int): Boolean = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> true
    else -> false
}

internal fun isBluetoothMicrophone(type: Int): Boolean =
    type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET

internal fun deviceAddress(device: AudioDeviceInfo): String =
    if (Build.VERSION.SDK_INT >= 28) device.address else ""

/** Communication routing selects an output port; recording uses its corresponding input. */
internal fun communicationDevice(manager: AudioManager, input: AudioDeviceInfo): AudioDeviceInfo? {
    if (Build.VERSION.SDK_INT < 31) return null
    val address = deviceAddress(input)
    return manager.availableCommunicationDevices.filter { output ->
        output.type == input.type && if (address.isNotBlank()) deviceAddress(output) == address
        else output.productName.toString() == input.productName.toString()
    }.singleOrNull()
}
