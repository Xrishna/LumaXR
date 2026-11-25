package com.lumaxr.player.hardware

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.exp

data class FrameLuminanceMetrics(
    val apl: Float,           // Average Picture Level [0..1] (bottom 18% excluded if subtitles visible)
    val p99Luma: Float,       // 99th percentile linear luma [0..1]
    val darkPixelRatio: Float,// Fraction of pixels < 0.05 linear luma [0..1]
    val subtitlesVisible: Boolean,
    val timestampNs: Long = System.nanoTime(),
)

data class BacklightXrState(
    val userCeilingNits: Float = 180f,
    val targetNits: Float = 180f,
    val estimatedPhysicalNits: Float = 180f,
    val targetScreenBrightness: Float = RuanBacklightSpline.nitsToBrightness(180f),
    val dimmingFactorActual: Float = 1.0f,
    val inverseShaderGain: Float = 1.0f,
    val dimmingFloorDMin: Float = 0.42f,
    val localContrastStrength: Float = 0.28f,
    val enabled: Boolean = true,
)

/**
 * Sony BRAVIA XR-inspired physics-synchronized IPS backlight dimming controller.
 *
 * 1. Asymmetric temporal filter with +/-3% hysteresis deadband:
 *    - Fast attack (tau_up ~ 110 ms) on bright scene cuts
 *    - Slow cinema decay (tau_down ~ 1600 ms) on fades to black
 * 2. Hardware ramp physics estimator L_est(t):
 *    - Accounts for HyperOS finite backlight ramp speed (0.6021006/s fast, 0.4816805/s slow)
 *      plus ~24 ms pipeline latency so inverse shader gain G(t) = 1 / D_actual(t)
 *      never flashes on scene cuts.
 */
class RuanBacklightController {

    companion object {
        private const val TAU_UP_SEC = 0.110f
        private const val TAU_DOWN_SEC = 1.600f
        private const val HYSTERESIS_DEADBAND = 0.03f
        private const val PIPELINE_LATENCY_SEC = 0.024f
    }

    private val _state = MutableStateFlow(BacklightXrState())
    val state: StateFlow<BacklightXrState> = _state.asStateFlow()

    private var userCeilingNits: Float = 180f
    private var dimmingFloorDMin: Float = 0.42f
    private var localContrastStrength: Float = 0.28f
    private var enabled: Boolean = true

    private var filteredTargetDim: Float = 1.0f
    private var estimatedBrightnessNorm: Float = RuanBacklightSpline.nitsToBrightness(180f)
    private var lastUpdateNs: Long = 0L

    // Small ring buffer to model ~24 ms physical backlight driver latency
    private var delayedTargetBrightnessNorm: Float = estimatedBrightnessNorm

    fun configure(
        userCeilingNits: Float,
        dimmingFloorDMin: Float,
        localContrastStrength: Float,
        enabled: Boolean,
    ) {
        this.userCeilingNits = userCeilingNits.coerceIn(30f, RuanHardwareProfile.KNOT_NORMAL_NITS)
        this.dimmingFloorDMin = dimmingFloorDMin.coerceIn(0.35f, 1.0f)
        this.localContrastStrength = localContrastStrength.coerceIn(0f, 1f)
        this.enabled = enabled
        if (!enabled) {
            val userNorm = RuanBacklightSpline.nitsToBrightness(this.userCeilingNits)
            filteredTargetDim = 1.0f
            estimatedBrightnessNorm = userNorm
            delayedTargetBrightnessNorm = userNorm
            _state.value = BacklightXrState(
                userCeilingNits = this.userCeilingNits,
                targetNits = this.userCeilingNits,
                estimatedPhysicalNits = this.userCeilingNits,
                targetScreenBrightness = userNorm,
                dimmingFactorActual = 1.0f,
                inverseShaderGain = 1.0f,
                dimmingFloorDMin = this.dimmingFloorDMin,
                localContrastStrength = 0f,
                enabled = false,
            )
        }
    }

    /**
     * Step the metering + physics estimator at 20-30 Hz.
     * Subtitles in the bottom 18% band are excluded upstream when computing [metrics].
     */
    fun onFrameMetered(metrics: FrameLuminanceMetrics): BacklightXrState {
        if (!enabled) return _state.value

        val nowNs = metrics.timestampNs
        val dtSec = if (lastUpdateNs == 0L) 0.033f
        else ((nowNs - lastUpdateNs).coerceIn(1_000_000L, 250_000_000L)) / 1_000_000_000f
        lastUpdateNs = nowNs

        // Scene dimming demand: dark scenes with low P99 & high dark-pixel ratio lower backlight toward D_min
        val peakDrive = (metrics.p99Luma * 0.72f + metrics.apl * 0.28f).coerceIn(0f, 1f)
        val darkBoost = (1f - metrics.darkPixelRatio * 0.35f).coerceIn(0.65f, 1f)
        val desiredDim = (dimmingFloorDMin + (1f - dimmingFloorDMin) * (peakDrive * darkBoost))
            .coerceIn(dimmingFloorDMin, 1.0f)

        // +/- 3% hysteresis deadband prevents micro-oscillations on static shots
        val candidateDim = if (abs(desiredDim - filteredTargetDim) < HYSTERESIS_DEADBAND) {
            filteredTargetDim
        } else {
            desiredDim
        }

        // Asymmetric exponential temporal filter
        val tau = if (candidateDim > filteredTargetDim) TAU_UP_SEC else TAU_DOWN_SEC
        val alpha = 1f - exp(-dtSec / tau)
        filteredTargetDim = (filteredTargetDim + alpha * (candidateDim - filteredTargetDim))
            .coerceIn(dimmingFloorDMin, 1.0f)

        val targetNits = (userCeilingNits * filteredTargetDim).coerceAtLeast(RuanHardwareProfile.KNOT_MIN_NITS)
        val targetNorm = RuanBacklightSpline.nitsToBrightness(targetNits)

        // Model ~24 ms pipeline delay before LED driver starts ramping toward targetNorm
        val latencyAlpha = (dtSec / (dtSec + PIPELINE_LATENCY_SEC)).coerceIn(0f, 1f)
        delayedTargetBrightnessNorm += latencyAlpha * (targetNorm - delayedTargetBrightnessNorm)

        // Integrate physical backlight state L_est(t) using HyperOS measured ramp rates
        val rampRatePerSec = if (delayedTargetBrightnessNorm >= estimatedBrightnessNorm) {
            RuanHardwareProfile.RAMP_RATE_FAST_PER_SEC
        } else {
            RuanHardwareProfile.RAMP_RATE_SLOW_PER_SEC
        }
        val maxStep = rampRatePerSec * dtSec
        val delta = delayedTargetBrightnessNorm - estimatedBrightnessNorm
        estimatedBrightnessNorm += delta.coerceIn(-maxStep, maxStep)

        val estPhysicalNits = RuanBacklightSpline.brightnessToNits(estimatedBrightnessNorm)
        val dActual = (estPhysicalNits / userCeilingNits).coerceIn(dimmingFloorDMin, 1.0f)
        val invGain = (1.0f / dActual).coerceIn(1.0f, 1.0f / dimmingFloorDMin)

        val next = BacklightXrState(
            userCeilingNits = userCeilingNits,
            targetNits = targetNits,
            estimatedPhysicalNits = estPhysicalNits,
            targetScreenBrightness = targetNorm,
            dimmingFactorActual = dActual,
            inverseShaderGain = invGain,
            dimmingFloorDMin = dimmingFloorDMin,
            localContrastStrength = localContrastStrength,
            enabled = true,
        )
        _state.value = next
        return next
    }
}
