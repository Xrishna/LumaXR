package com.lumaxr.player.video

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.view.SurfaceView
import com.lumaxr.player.hardware.BacklightXrState
import com.lumaxr.player.hardware.DisplayRefreshRateManager
import com.lumaxr.player.hardware.FrameLuminanceMetrics
import com.lumaxr.player.hardware.RefreshRatePolicy
import com.lumaxr.player.hardware.RuanBacklightController
import com.lumaxr.player.hardware.RuanBacklightSpline
import com.lumaxr.player.hardware.ThermalGovernor
import com.lumaxr.player.hardware.ThermalTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.math.abs

data class VideoHudTelemetry(
    val engineLabel: String = "MPV Vulkan",
    val decoderLabel: String = "mediacodec-copy",
    val formatLabel: String = "SDR",
    val outputLabel: String = "BT.709 / sRGB / 240 nits",
    val refreshRateHz: Int = 120,
    val physicalNits: Float = 180f,
    val inverseGainX: Float = 1.0f,
    val thermalTier: ThermalTier = ThermalTier.OPTIMAL,
    val droppedFrames: Long = 0L,
    val routingReason: String = "",
    val splitWipe: Float = 1.0f,
    val bypassOriginal: Boolean = false,
    val experimentalHbmWarning: String? = null,
)

class DualEngineCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prober: MediaFormatProber,
    private val guard: DisplayCapabilityGuard,
    private val advisor: PlaybackConfigAdvisor,
    val backlightController: RuanBacklightController,
    val refreshRateManager: DisplayRefreshRateManager,
    val thermalGovernor: ThermalGovernor,
    val smartAspectManager: SmartAspectManager,
    private val onAmbientFrameSampled: (Bitmap) -> Unit = {},
) {
    private val prefs = context.getSharedPreferences("lumaxr_engine_prefs", Context.MODE_PRIVATE)
    private var useVulkan: Boolean
        get() = !prefs.getBoolean("force_gles_fallback", false)
        set(value) { prefs.edit().putBoolean("force_gles_fallback", !value).apply() }

    private val playbackMutex = Mutex()
    private val _hudTelemetry = MutableStateFlow(VideoHudTelemetry())
    val hudTelemetry: StateFlow<VideoHudTelemetry> = _hudTelemetry.asStateFlow()

    private val _currentMedia = MutableStateFlow<ProbedMediaInfo?>(null)
    val currentMedia: StateFlow<ProbedMediaInfo?> = _currentMedia.asStateFlow()

    private val _activeEngineKind = MutableStateFlow(VideoEngineKind.LIBMPV_PLACEBO_XR)
    val activeEngineKind: StateFlow<VideoEngineKind> = _activeEngineKind.asStateFlow()

    private var currentPreset: XrPreset = XrPreset.CINEMA_MASTERING_XR
    private var currentPolicy: RefreshRatePolicy = RefreshRatePolicy.AUTO_CINEMA_120HZ
    private var currentAspectState: SmartAspectState = SmartAspectState()
    private var currentPlan: MpvConfigPlan? = null
    private var currentUri: String? = null
    private var syncJob: Job? = null
    private var splitWipe: Float = 1.0f
    private var bypassOriginal: Boolean = false
    private var lastAppliedBrightness: Float = -1f

    val mpvEngine: MpvPlaceboEngine = MpvPlaceboEngine(
        context = context,
        scope = scope,
        onVulkanInitFailed = {
            useVulkan = false
            val uri = currentUri
            val view = currentSurfaceView?.get()
            if (uri != null && view != null) {
                scope.launch(Dispatchers.Main) {
                    startPlayback(view, uri, currentPreset, currentPolicy, EngineSwitchPreference.AUTO)
                }
            }
        },
        onFrameMetered = { metrics: FrameLuminanceMetrics ->
            val xrState = backlightController.onFrameMetered(metrics)
            updateBacklightAndUniforms(xrState)
        },
        onAmbientFrameSampled = onAmbientFrameSampled,
    )

    val media3Engine: Media3DolbyHalEngine = Media3DolbyHalEngine(context)
    private var currentSurfaceView: WeakReference<SurfaceView>? = null
    private var attachedActivity: WeakReference<Activity>? = null

    fun bindActivity(activity: Activity) {
        attachedActivity = WeakReference(activity)
    }

    fun unbindActivity(activity: Activity) {
        if (attachedActivity?.get() === activity) {
            attachedActivity = null
        }
    }

    suspend fun startPlayback(
        surfaceView: SurfaceView,
        uri: String,
        preset: XrPreset = XrPreset.CINEMA_MASTERING_XR,
        refreshPolicy: RefreshRatePolicy = RefreshRatePolicy.AUTO_CINEMA_120HZ,
        enginePreference: EngineSwitchPreference = EngineSwitchPreference.AUTO,
        experimentalHbm: Boolean = false,
        hdrToysMapper: HdrToysToneMapper = HdrToysToneMapper.OFF,
        subtitlePeakNits: Int = 140,
        manualTargetPeakNits: Float? = null,
    ): Unit {
        playbackMutex.withLock {
        currentUri = uri
        currentSurfaceView = WeakReference(surfaceView)
        currentPreset = preset
        currentPolicy = refreshPolicy
        thermalGovernor.start()

        val media = prober.probe(uri)
        _currentMedia.value = media
        val act = attachedActivity?.get()
        val displayCaps = guard.inspectDisplay(act ?: context)
        val hasXrOrFilter = preset != XrPreset.RAW_BYPASS || hdrToysMapper != HdrToysToneMapper.OFF
        val decision = guard.evaluateRouting(
            media = media,
            displayCaps = displayCaps,
            hasMpvFilterOrHdrToysOrXrPreset = hasXrOrFilter,
            hasLibDovi = true,
            userPreference = enginePreference,
        )
        _activeEngineKind.value = decision.engine

        val estDisplayNits = RuanBacklightSpline.brightnessToNits(
            act?.window?.attributes?.screenBrightness?.takeIf { it > 0f } ?: 0.55f,
        )

        val plan = advisor.buildConfigPlan(
            media = media,
            preset = preset,
            useVulkan = useVulkan,
            estimatedDisplayNits = estDisplayNits,
            manualTargetPeakNits = manualTargetPeakNits,
            experimentalHbmHdrMode = experimentalHbm,
            displayHighestHdrSdrRatio = displayCaps.highestHdrSdrRatio,
            hdrToysMapper = hdrToysMapper,
            subtitlePeakNits = subtitlePeakNits,
            thermalTier = thermalGovernor.snapshot.value.tier,
        )
        currentPlan = plan

        val enableXrBacklight = preset != XrPreset.RAW_BYPASS && decision.engine == VideoEngineKind.LIBMPV_PLACEBO_XR
        backlightController.configure(
            userCeilingNits = preset.defaultCeilingNits,
            dimmingFloorDMin = preset.dimmingFloorDMin,
            localContrastStrength = preset.localContrastDefault,
            enabled = enableXrBacklight,
        )

        currentAspectState = smartAspectManager.computeLetterboxBounds(
            media.width,
            media.height,
            currentAspectState,
        )

        withContext(Dispatchers.Main) {
            act?.let { a ->
                val lp = a.window.attributes
                lp.desiredHdrHeadroom = if (decision.engine == VideoEngineKind.MEDIA3_DOLBY_HAL) 0.0f else plan.desiredHdrHeadroom
                if (!enableXrBacklight) {
                    lp.screenBrightness = -1f
                    lastAppliedBrightness = -1f
                }
                a.window.attributes = lp
                val hz = refreshRateManager.applyToWindow(a, media.fps, refreshPolicy)
                _hudTelemetry.value = _hudTelemetry.value.copy(refreshRateHz = hz)
            }

            if (decision.engine == VideoEngineKind.LIBMPV_PLACEBO_XR) {
                media3Engine.destroy()
                mpvEngine.attachAndStart(surfaceView, uri, plan)
            } else {
                mpvEngine.destroy()
                media3Engine.attachAndStart(surfaceView, uri)
            }
        }

        _hudTelemetry.value = _hudTelemetry.value.copy(
            engineLabel = if (decision.engine == VideoEngineKind.LIBMPV_PLACEBO_XR) {
                if (plan.gpuApi == "vulkan") "MPV Vulkan" else "MPV GLES 3.2"
            } else {
                "Media3 Dolby HAL"
            },
            decoderLabel = if (decision.engine == VideoEngineKind.LIBMPV_PLACEBO_XR) {
                if (media.videoCodec.contains("av1")) "dav1d SW" else plan.hwdec
            } else {
                media3Engine.telemetry.value.decoderName
            },
            formatLabel = media.hdrFormat.name.replace('_', ' '),
            outputLabel = if (decision.engine == VideoEngineKind.LIBMPV_PLACEBO_XR) {
                "${plan.targetPrim.uppercase()} / ${plan.targetTrc.uppercase()} / ${plan.targetPeakNits} nits"
            } else {
                "Native Dolby HAL Surface"
            },
            routingReason = decision.reason,
            experimentalHbmWarning = plan.hbmWarningMessage,
        )

        startTelemetryLoop()
        }
    }

    fun updateLiveMasteringOptions(
        preset: XrPreset,
        hdrToysMapper: HdrToysToneMapper,
        subtitlePeakNits: Int,
    ) {
        currentPreset = preset
        val media = _currentMedia.value ?: return
        val act = attachedActivity?.get()
        val displayCaps = guard.inspectDisplay(act ?: context)
        val estDisplayNits = RuanBacklightSpline.brightnessToNits(
            act?.window?.attributes?.screenBrightness?.takeIf { it > 0f } ?: 0.55f,
        )
        val plan = advisor.buildConfigPlan(
            media = media,
            preset = preset,
            useVulkan = useVulkan,
            estimatedDisplayNits = estDisplayNits,
            manualTargetPeakNits = null,
            experimentalHbmHdrMode = currentPlan?.targetColorspaceHint == "yes",
            displayHighestHdrSdrRatio = displayCaps.highestHdrSdrRatio,
            hdrToysMapper = hdrToysMapper,
            subtitlePeakNits = subtitlePeakNits,
            thermalTier = thermalGovernor.snapshot.value.tier,
        )
        currentPlan = plan
        backlightController.configure(
            userCeilingNits = preset.defaultCeilingNits,
            dimmingFloorDMin = preset.dimmingFloorDMin,
            localContrastStrength = preset.localContrastDefault,
            enabled = preset != XrPreset.RAW_BYPASS && _activeEngineKind.value == VideoEngineKind.LIBMPV_PLACEBO_XR,
        )
        if (_activeEngineKind.value == VideoEngineKind.LIBMPV_PLACEBO_XR) {
            mpvEngine.applyRuntimePlanUpdate(plan)
            updateBacklightAndUniforms(backlightController.state.value)
        }
    }

    private fun startTelemetryLoop() {
        syncJob?.cancel()
        syncJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val gov = thermalGovernor.snapshot.value
                if (_activeEngineKind.value == VideoEngineKind.LIBMPV_PLACEBO_XR) {
                    val t = mpvEngine.telemetry.value
                    val isSwAv1Over1080 = t.videoCodec.contains("av1", ignoreCase = true) && t.videoHeight > 1080
                    thermalGovernor.updatePlaybackTelemetry(t.delayedFrames, t.droppedFrames, isSwAv1Over1080)
                    val latestGov = thermalGovernor.snapshot.value
                    _hudTelemetry.value = _hudTelemetry.value.copy(
                        thermalTier = latestGov.tier,
                        droppedFrames = t.droppedFrames,
                        decoderLabel = if (t.hwdecCurrent.isNotBlank()) t.hwdecCurrent else _hudTelemetry.value.decoderLabel,
                    )
                    currentPlan?.let { plan ->
                        if (latestGov.tier.scalerOverride != null && plan.scaler != latestGov.tier.scalerOverride) {
                            val updated = plan.copy(
                                scaler = latestGov.tier.scalerOverride,
                                debandEnabled = plan.debandEnabled && latestGov.tier.allowDeband,
                            )
                            currentPlan = updated
                            mpvEngine.applyRuntimePlanUpdate(updated)
                        }
                    }
                } else {
                    media3Engine.refreshPosition()
                    thermalGovernor.updatePlaybackTelemetry(0L, 0L, false)
                    _hudTelemetry.value = _hudTelemetry.value.copy(
                        thermalTier = gov.tier,
                        decoderLabel = media3Engine.telemetry.value.decoderName,
                    )
                }
                delay(250L)
            }
        }
    }

    private fun updateBacklightAndUniforms(xr: BacklightXrState) {
        if (_activeEngineKind.value != VideoEngineKind.LIBMPV_PLACEBO_XR) return
        val govTier = thermalGovernor.snapshot.value.tier
        mpvEngine.applyLiveBacklightAndWipeUniforms(
            backlight = xr,
            splitWipe = splitWipe,
            bypassOriginal = bypassOriginal,
            bilateralRadius = govTier.bilateralRadius,
            aspectState = currentAspectState,
        )
        _hudTelemetry.value = _hudTelemetry.value.copy(
            physicalNits = xr.estimatedPhysicalNits,
            inverseGainX = xr.inverseShaderGain,
            splitWipe = splitWipe,
            bypassOriginal = bypassOriginal,
        )
        if (xr.enabled && abs(xr.targetScreenBrightness - lastAppliedBrightness) >= 0.004f) {
            lastAppliedBrightness = xr.targetScreenBrightness
            attachedActivity?.get()?.let { act ->
                act.runOnUiThread {
                    val lp = act.window.attributes
                    lp.screenBrightness = xr.targetScreenBrightness
                    act.window.attributes = lp
                }
            }
        }
    }

    fun setSplitWipe(fraction: Float) {
        splitWipe = fraction.coerceIn(0f, 1f)
        updateBacklightAndUniforms(backlightController.state.value)
    }

    fun setBypassOriginal(bypass: Boolean) {
        bypassOriginal = bypass
        updateBacklightAndUniforms(backlightController.state.value)
    }

    fun setAspectMode(mode: AspectMode, subPlacement: SubtitlePlacement) {
        val media = _currentMedia.value
        currentAspectState = smartAspectManager.computeLetterboxBounds(
            media?.width ?: 1920,
            media?.height ?: 1080,
            currentAspectState.copy(aspectMode = mode, subtitlePlacement = subPlacement),
        )
        if (_activeEngineKind.value == VideoEngineKind.LIBMPV_PLACEBO_XR) {
            mpvEngine.setVideoZoom(smartAspectManager.toMpvVideoZoom(currentAspectState, media?.width ?: 1920, media?.height ?: 1080))
            mpvEngine.setSubPos(smartAspectManager.toMpvSubPos(subPlacement, currentAspectState))
        }
        updateBacklightAndUniforms(backlightController.state.value)
    }

    fun setPaused(paused: Boolean) {
        if (_activeEngineKind.value == VideoEngineKind.LIBMPV_PLACEBO_XR) {
            mpvEngine.setPaused(paused)
        } else {
            media3Engine.setPaused(paused)
        }
    }

    fun isPaused(): Boolean {
        return if (_activeEngineKind.value == VideoEngineKind.LIBMPV_PLACEBO_XR) {
            mpvEngine.telemetry.value.paused
        } else {
            !media3Engine.telemetry.value.isPlaying
        }
    }

    fun currentPositionSec(): Double {
        return if (_activeEngineKind.value == VideoEngineKind.LIBMPV_PLACEBO_XR) {
            mpvEngine.telemetry.value.positionSec
        } else {
            media3Engine.telemetry.value.positionMs / 1000.0
        }
    }

    fun currentDurationSec(): Double {
        return if (_activeEngineKind.value == VideoEngineKind.LIBMPV_PLACEBO_XR) {
            mpvEngine.telemetry.value.durationSec
        } else {
            media3Engine.telemetry.value.durationMs / 1000.0
        }
    }

    fun seekToSec(seconds: Double) {
        if (_activeEngineKind.value == VideoEngineKind.LIBMPV_PLACEBO_XR) {
            mpvEngine.seekToSeconds(seconds)
        } else {
            media3Engine.seekToMs((seconds * 1000.0).toLong())
        }
    }

    fun seekRelative(deltaSeconds: Double) {
        if (_activeEngineKind.value == VideoEngineKind.LIBMPV_PLACEBO_XR) {
            mpvEngine.seekRelative(deltaSeconds)
        } else {
            val cur = media3Engine.telemetry.value.positionMs
            media3Engine.seekToMs(cur + (deltaSeconds * 1000).toLong())
        }
    }

    fun stopAndRestoreDisplay() {
        syncJob?.cancel()
        syncJob = null
        currentUri = null
        currentSurfaceView = null
        lastAppliedBrightness = -1f
        thermalGovernor.stop()
        mpvEngine.destroy()
        media3Engine.destroy()
        attachedActivity?.get()?.let { act ->
            act.runOnUiThread {
                refreshRateManager.restore120Hz(act)
                val lp = act.window.attributes
                lp.screenBrightness = -1f
                lp.desiredHdrHeadroom = 0.0f
                act.window.attributes = lp
            }
        }
    }
}
