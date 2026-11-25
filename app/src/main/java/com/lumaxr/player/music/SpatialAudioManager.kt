package com.lumaxr.player.music

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.Spatializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SpatialAudioState(
    val immersiveLevel: Int = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE,
    val isAvailable: Boolean = false,
    val isEnabled: Boolean = false,
    val isHeadTrackerAvailable: Boolean = false,
    val canSpatializeMultichannel: Boolean = false,
    val sofaHrtfActive: Boolean = false,
    val dolbyDeviceRoutingActive: Boolean = false,
) {
    val systemSpatializerActive: Boolean
        get() = immersiveLevel != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE &&
            isAvailable && isEnabled
}

class SpatialAudioManager(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val spatializer: Spatializer? = audioManager?.spatializer
    private val lock = Any()

    private val _state = MutableStateFlow(SpatialAudioState())
    val state: StateFlow<SpatialAudioState> = _state.asStateFlow()

    private var isStarted = false
    private var userSofaHrtfRequested = false

    private val stateListener = object : Spatializer.OnSpatializerStateChangedListener {
        override fun onSpatializerEnabledChanged(spat: Spatializer, enabled: Boolean) = refresh()
        override fun onSpatializerAvailableChanged(spat: Spatializer, available: Boolean) = refresh()
    }

    private val headTrackerListener = Spatializer.OnHeadTrackerAvailableListener { _, _ -> refresh() }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    fun start() {
        synchronized(lock) {
            if (isStarted) return
            isStarted = true
            spatializer?.let { s ->
                runCatching { s.addOnSpatializerStateChangedListener(appContext.mainExecutor, stateListener) }
                runCatching { s.addOnHeadTrackerAvailableListener(appContext.mainExecutor, headTrackerListener) }
            }
            runCatching { audioManager?.registerAudioDeviceCallback(deviceCallback, null) }
        }
        refresh()
    }

    fun stop() {
        synchronized(lock) {
            if (!isStarted) return
            isStarted = false
            spatializer?.let { s ->
                runCatching { s.removeOnSpatializerStateChangedListener(stateListener) }
                runCatching { s.removeOnHeadTrackerAvailableListener(headTrackerListener) }
            }
            runCatching { audioManager?.unregisterAudioDeviceCallback(deviceCallback) }
        }
    }

    fun setSofaHrtfRequested(requested: Boolean) {
        synchronized(lock) { userSofaHrtfRequested = requested }
        refresh()
    }

    fun refresh() {
        val wantSofa = synchronized(lock) { userSofaHrtfRequested }
        val s = spatializer
        if (s == null) {
            _state.value = SpatialAudioState(sofaHrtfActive = wantSofa)
            return
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format51 = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
            .build()

        val level = s.immersiveAudioLevel
        val avail = s.isAvailable
        val enabled = s.isEnabled
        val headTracker = s.isHeadTrackerAvailable
        val canMultichannel = runCatching { s.canBeSpatialized(attrs, format51) }.getOrDefault(false)

        val sysActive = level != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE && avail && enabled
        val sofaActive = wantSofa && !sysActive

        _state.value = SpatialAudioState(
            immersiveLevel = level,
            isAvailable = avail,
            isEnabled = enabled,
            isHeadTrackerAvailable = headTracker,
            canSpatializeMultichannel = canMultichannel,
            sofaHrtfActive = sofaActive,
            dolbyDeviceRoutingActive = sysActive,
        )
    }

    fun buildAudioAttributes(
        isPreRenderedBinaural: Boolean = _state.value.sofaHrtfActive,
        bypassSystemSpatializer: Boolean = _state.value.sofaHrtfActive,
    ): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setIsContentSpatialized(isPreRenderedBinaural)
            .setSpatializationBehavior(
                if (bypassSystemSpatializer) AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER
                else AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO,
            )
            .build()
    }
}
