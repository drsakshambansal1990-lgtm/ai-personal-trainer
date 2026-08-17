package com.coach.ai.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Routes the live Coach conversation like a communication/VoIP session.
 *
 * Wake-word detection remains separate and local. When a live turn starts we
 * prefer an attached headset microphone/output; otherwise we fall back to the
 * handset. Android 13+ guidance favors setCommunicationDevice() for BLE/HFP.
 */
class AudioRouteManager(
    context: Context,
    private val onRouteChanged: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private var callbackRegistered = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = routeBestDevice()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = routeBestDevice()
    }

    fun startCommunicationRouting() {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (!callbackRegistered) {
            audio.registerAudioDeviceCallback(deviceCallback, null)
            callbackRegistered = true
        }
        routeBestDevice()
    }

    fun stopCommunicationRouting() {
        if (callbackRegistered) {
            runCatching { audio.unregisterAudioDeviceCallback(deviceCallback) }
            callbackRegistered = false
        }
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { audio.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audio.stopBluetoothSco()
                audio.isBluetoothScoOn = false
            }
        }
        audio.mode = AudioManager.MODE_NORMAL
        onRouteChanged("System default")
    }

    fun currentRouteLabel(): String {
        if (Build.VERSION.SDK_INT >= 31) {
            return audio.communicationDevice?.let(::label) ?: "System default"
        }
        @Suppress("DEPRECATION")
        return if (audio.isBluetoothScoOn) "Bluetooth headset" else "System default"
    }

    private fun routeBestDevice() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (!hasBluetoothPermission()) {
                onRouteChanged("Bluetooth permission needed")
                return
            }
            val devices = audio.availableCommunicationDevices
            val preferred = devices.minByOrNull { priority(it.type) }
            if (preferred != null) {
                val ok = runCatching { audio.setCommunicationDevice(preferred) }.getOrDefault(false)
                onRouteChanged(if (ok) label(preferred) else "System default")
            } else {
                onRouteChanged("System default")
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                if (audio.isBluetoothScoAvailableOffCall) {
                    audio.startBluetoothSco()
                    audio.isBluetoothScoOn = true
                    onRouteChanged("Bluetooth headset / handset")
                } else {
                    onRouteChanged("Handset")
                }
            }.onFailure { onRouteChanged("Handset") }
        }
    }

    private fun priority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 0
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 1
        AudioDeviceInfo.TYPE_USB_HEADSET -> 2
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 3
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 4
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 5
        else -> 99
    }

    private fun label(device: AudioDeviceInfo): String = when (device.type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        else -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Audio device"
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}
