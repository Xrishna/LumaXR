package com.lumaxr.player.music

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UsbDacState(
    val connected: Boolean = false,
    val deviceName: String = "",
    val supportsBitPerfect: Boolean = false,
    val activeBitPerfect: Boolean = false,
    val activeSampleRateHz: Int = 0,
    val activeBitDepth: Int = 0,
    val hardwareVolumeOnly: Boolean = false,
    val supportedRatesHz: List<Int> = emptyList(),
)

/**
 * Official Android 14+ (API 34+) Bit-Perfect USB DAC Output Manager via AudioMixerAttributes.
 * - Detects TYPE_USB_DEVICE / TYPE_USB_HEADSET via AudioDeviceCallback
 * - Queries AudioManager.getSupportedMixerAttributes(usbDevice)
 * - Calls AudioManager.setPreferredMixerAttributes(audioAttributes, usbDevice, attr)
 *   with MIXER_BEHAVIOR_BIT_PERFECT matching track's exact sample rate & encoding
 * - Clears preferred mixer attributes on pause or USB disconnect
 */
class UsbBitPerfectManager(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val _state = MutableStateFlow(UsbDacState())
    val state: StateFlow<UsbDacState> = _state.asStateFlow()

    private var activeUsbDevice: AudioDeviceInfo? = null
    private var activeAudioAttributes: AudioAttributes? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshUsbDevice()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.id == activeUsbDevice?.id }) {
                clearBitPerfect()
            }
            refreshUsbDevice()
        }
    }

    fun start() {
        audioManager?.registerAudioDeviceCallback(deviceCallback, null)
        refreshUsbDevice()
    }

    fun stop() {
        clearBitPerfect()
        runCatching { audioManager?.unregisterAudioDeviceCallback(deviceCallback) }
    }

    fun refreshUsbDevice() {
        val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        val usb = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        activeUsbDevice = usb
        if (usb == null) {
            _state.value = UsbDacState()
            return
        }
        val mixerAttrs = runCatching { audioManager?.getSupportedMixerAttributes(usb).orEmpty() }.getOrDefault(emptyList())
        val bitPerfectAttrs = mixerAttrs.filter {
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
        }
        val rates = bitPerfectAttrs.map { it.format.sampleRate }.distinct().sorted()
        _state.value = _state.value.copy(
            connected = true,
            deviceName = usb.productName?.toString()?.ifBlank { "USB-C Audio DAC" } ?: "USB-C Audio DAC",
            supportsBitPerfect = bitPerfectAttrs.isNotEmpty(),
            supportedRatesHz = rates,
        )
    }

    fun selectBestBitPerfectAttributes(
        supported: List<AudioMixerAttributes>,
        desiredSampleRateHz: Int,
        desiredBitDepth: Int,
    ): AudioMixerAttributes? {
        val bp = supported.filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
        if (bp.isEmpty()) return null
        val targetEncoding = when {
            desiredBitDepth >= 32 -> AudioFormat.ENCODING_PCM_32BIT
            desiredBitDepth >= 24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            else -> AudioFormat.ENCODING_PCM_16BIT
        }
        return bp.firstOrNull { it.format.sampleRate == desiredSampleRateHz && it.format.encoding == targetEncoding }
            ?: bp.firstOrNull { it.format.sampleRate == desiredSampleRateHz }
            ?: bp.maxByOrNull { it.format.sampleRate }
    }

    fun engageForTrack(
        audioAttributes: AudioAttributes,
        sampleRateHz: Int,
        bitDepth: Int,
        userEnabled: Boolean,
    ): Boolean {
        val usb = activeUsbDevice ?: return false
        if (!userEnabled) {
            clearBitPerfect()
            return false
        }
        val supported = runCatching { audioManager?.getSupportedMixerAttributes(usb).orEmpty() }.getOrDefault(emptyList())
        val chosen = selectBestBitPerfectAttributes(supported, sampleRateHz, bitDepth)
        if (chosen == null) {
            _state.value = _state.value.copy(
                activeBitPerfect = false,
                hardwareVolumeOnly = false,
            )
            return false
        }
        val ok = runCatching {
            audioManager?.setPreferredMixerAttributes(audioAttributes, usb, chosen) ?: false
        }.getOrDefault(false)

        if (ok) {
            activeAudioAttributes = audioAttributes
            _state.value = _state.value.copy(
                activeBitPerfect = true,
                activeSampleRateHz = chosen.format.sampleRate,
                activeBitDepth = bitDepth,
                hardwareVolumeOnly = true,
            )
        }
        return ok
    }

    fun clearBitPerfect() {
        val usb = activeUsbDevice
        val attrs = activeAudioAttributes
        if (usb != null && attrs != null) {
            runCatching { audioManager?.clearPreferredMixerAttributes(attrs, usb) }
        }
        activeAudioAttributes = null
        _state.value = _state.value.copy(
            activeBitPerfect = false,
            activeSampleRateHz = 0,
            activeBitDepth = 0,
            hardwareVolumeOnly = false,
        )
    }
}
