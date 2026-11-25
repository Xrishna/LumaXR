package com.lumaxr.player.music

import android.content.Context
import android.media.AudioAttributes
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

/**
 * Android 13-16 Official Spatializer API Manager.
 * Coordinates cleanly with Dolby Audio / bluetooth_device:dolby so SOFA HRTF binaural rendering
 * is offered ONLY when system Spatializer is unavailable or disabled (never double-virtualized).
 */
class SpatialAudioManager(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val spatializer: Spatializer? = audioManager?.spatializer

    private val _state = MutableStateFlow(SpatialAudioState())
    val state: StateFlow<SpatialAudioState> = _state.asStateFlow()

    private var userSofaHrtfRequested: Boolean = false

    private val stateListener = object : Spatializer.OnSpatializerStateChangedListener {
        override fun onSpatializerEnabledChanged(spat: Spatializer, enabled: Boolean) = refresh()
        override fun onSpatializerAvailableChanged(spat: Spatializer, available: Boolean) = refresh()
    }

    private val headTrackerListener = Spatializer.OnHeadTrackerAvailableListener { _, _ -> refresh() }

    fun start() {
        spatializer?.let { s ->
            runCatching {
                s.addOnSpatializerStateChangedListener(context.mainExecutor, stateListener)
                s.addOnHeadTrackerAvailableListener(context.mainExecutor, headTrackerListener)
            }
        }
        refresh()
    }

    fun stop() {
        spatializer?.let { s ->
            runCatching {
                s.removeOnSpatializerStateChangedListener(stateListener)
                s.removeOnHeadTrackerAvailableListener(headTrackerListener)
            }
        }
    }

    fun setSofaHrtfRequested(requested: Boolean) {
        userSofaHrtfRequested = requested
        refresh()
    }

    fun refresh() {
        val s = spatializer
        if (s == null) {
            _state.value = SpatialAudioState(sofaHrtfActive = userSofaHrtfRequested)
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
        // Never double-virtualize: only engage SOFA HRTF if system spatializer is inactive
        val sofaActive = userSofaHrtfRequested && !sysActive

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

    fun buildAudioAttributes(isPreRenderedBinaural: Boolean, bypassSystemSpatializer: Boolean): AudioAttributes {
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
