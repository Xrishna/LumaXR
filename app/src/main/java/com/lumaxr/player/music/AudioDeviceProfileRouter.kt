package com.lumaxr.player.music

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.media.session.MediaSession
import com.lumaxr.player.data.DeviceEqProfileEntity
import com.lumaxr.player.data.MusicLibraryDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ActiveAudioRoute(
    val deviceKey: String = "SPEAKER_QUAD",
    val displayName: String = "Built-in Quad Speakers",
    val isUsbDac: Boolean = false,
    val isBluetooth: Boolean = false,
    val isWiredHeadset: Boolean = false,
)

/**
 * Automatic per-output-device EQ/AutoEQ/Crossfeed/Bit-Perfect profile switcher + MediaRouter2 Output Switcher.
 */
class AudioDeviceProfileRouter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val musicDao: MusicLibraryDao,
    private val dspController: AudioDspController,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val _activeRoute = MutableStateFlow(ActiveAudioRoute())
    val activeRoute: StateFlow<ActiveAudioRoute> = _activeRoute.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = evaluateActiveRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = evaluateActiveRoute()
    }

    fun start() {
        audioManager?.registerAudioDeviceCallback(callback, null)
        evaluateActiveRoute()
    }

    fun stop() {
        runCatching { audioManager?.unregisterAudioDeviceCallback(callback) }
    }

    fun classifyRoute(outputs: List<AudioDeviceInfo>): ActiveAudioRoute {
        val usb = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        if (usb != null) {
            val name = usb.productName?.toString()?.ifBlank { "USB-C DAC" } ?: "USB-C DAC"
            return ActiveAudioRoute("USB:$name", name, isUsbDac = true)
        }
        val bt = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        if (bt != null) {
            val name = bt.productName?.toString()?.ifBlank { "Bluetooth Audio" } ?: "Bluetooth Audio"
            val addr = bt.address?.takeIf { it.isNotBlank() } ?: name
            return ActiveAudioRoute("BT:$addr", name, isBluetooth = true)
        }
        val wired = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }
        if (wired != null) {
            return ActiveAudioRoute("WIRED_HEADPHONES", "Wired Headphones", isWiredHeadset = true)
        }
        return ActiveAudioRoute("SPEAKER_QUAD", "Built-in Quad Speakers")
    }

    fun evaluateActiveRoute() {
        val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList().orEmpty()
        val route = classifyRoute(outputs)
        _activeRoute.value = route

        scope.launch {
            val saved = musicDao.getDeviceEqProfile(route.deviceKey)
            if (saved != null) {
                val gains = saved.bandGainsDbCsv.split(",").mapNotNull { it.toFloatOrNull() }
                val padded = List(15) { idx -> gains.getOrElse(idx) { 0f } }
                dspController.applyAutoEqPreset(
                    AutoEqPreset(saved.autoEqName, saved.preampDb, padded.toFloatArray()),
                )
                dspController.updateToggles(
                    crossfeedEnabled = saved.crossfeedEnabled,
                    crossfeedStrength = saved.crossfeedStrength,
                    balanceLR = saved.balanceLR,
                )
            } else if (route.deviceKey == "SPEAKER_QUAD") {
                val speakerPreset = AutoEqParser.BUILTIN_PRESETS.last()
                dspController.applyAutoEqPreset(speakerPreset)
            }
        }
    }

    suspend fun saveCurrentProfileForActiveRoute(bitPerfectPreferred: Boolean) {
        val route = _activeRoute.value
        val s = dspController.state.value
        musicDao.upsertDeviceEqProfile(
            DeviceEqProfileEntity(
                deviceKey = route.deviceKey,
                displayName = route.displayName,
                autoEqName = s.activePresetName,
                bandGainsDbCsv = s.bandGainsDb.joinToString(",") { "%.2f".format(it) },
                preampDb = s.preampDb,
                crossfeedEnabled = s.crossfeedEnabled,
                crossfeedStrength = s.crossfeedStrength,
                bitPerfectPreferred = bitPerfectPreferred,
                monoEnabled = s.diagnosticMode == DiagnosticAudioMode.MONO_SUM,
                balanceLR = s.balanceLR,
            ),
        )
    }

    fun showSystemOutputSwitcher(platformToken: MediaSession.Token? = null): Boolean {
        val router = MediaRouter2.getInstance(context)
        return runCatching {
            if (platformToken != null) {
                router.showSystemOutputSwitcher(platformToken)
            } else {
                router.showSystemOutputSwitcher()
            }
        }.getOrDefault(false)
    }
}
