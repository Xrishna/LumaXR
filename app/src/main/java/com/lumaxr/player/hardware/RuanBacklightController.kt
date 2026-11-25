package com.lumaxr.player.hardware

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.exp

data class FrameLuminanceMetrics(
    val apl: Float,
    val p99Luma: Float,
    val darkPixelRatio: Float,
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
 * Fixes input-side +/-3% hysteresis latching, timestamped ~24 ms transport delay ring buffer,
 * thread-safe synchronization, and NaN metering guards.
 */
class RuanBacklightController {

    companion object {
        private const val TAU_UP_SEC = 0.110f
        private const val TAU_DOWN_SEC = 1.600f
        private const val HYSTERESIS_DEADBAND = 0.03f
        private const val PIPELINE_LATENCY_NS = 24_000_000L
    }

    private val lock = Any()
    private val _state = MutableStateFlow(BacklightXrState())
    val state: StateFlow<BacklightXrState> = _state.asStateFlow()

    private var userCeilingNits: Float = 180f
    private var dimmingFloorDMin: Float = 0.42f
    private var localContrastStrength: Float = 0.28f
    private var enabled: Boolean = true

    private var latchedDesiredDim: Float = 1.0f
    private var filteredTargetDim: Float = 1.0f
    private var estimatedBrightnessNorm: Float = RuanBacklightSpline.nitsToBrightness(180f)
    private var lastUpdateNs: Long = 0L

    private data class DelaySample(val timestampNs: Long, val targetNorm: Float)
    private val delayQueue = ArrayDeque<DelaySample>(16)

    fun configure(
        userCeilingNits: Float,
        dimmingFloorDMin: Float,
        localContrastStrength: Float,
        enabled: Boolean,
    ) {
        synchronized(lock) {
            val safeCeiling = if (userCeilingNits.isFinite()) userCeilingNits else 180f
            val safeFloor = if (dimmingFloorDMin.isFinite()) dimmingFloorDMin else 0.42f
            val safeLc = if (localContrastStrength.isFinite()) localContrastStrength else 0.28f

            this.userCeilingNits = safeCeiling.coerceIn(30f, RuanHardwareProfile.KNOT_NORMAL_NITS)
            this.dimmingFloorDMin = safeFloor.coerceIn(0.35f, 1.0f)
            this.localContrastStrength = safeLc.coerceIn(0f, 1f)
            this.enabled = enabled

            val userNorm = RuanBacklightSpline.nitsToBrightness(this.userCeilingNits)
            latchedDesiredDim = 1.0f
            filteredTargetDim = 1.0f
            estimatedBrightnessNorm = userNorm
            lastUpdateNs = 0L
            delayQueue.clear()

            _state.value = BacklightXrState(
                userCeilingNits = this.userCeilingNits,
                targetNits = this.userCeilingNits,
                estimatedPhysicalNits = this.userCeilingNits,
                targetScreenBrightness = userNorm,
                dimmingFactorActual = 1.0f,
                inverseShaderGain = 1.0f,
                dimmingFloorDMin = this.dimmingFloorDMin,
                localContrastStrength = if (enabled) this.localContrastStrength else 0f,
                enabled = enabled,
            )
        }
    }

    fun onFrameMetered(metrics: FrameLuminanceMetrics): BacklightXrState {
        synchronized(lock) {
            if (!enabled) return _state.value

            val apl = if (metrics.apl.isFinite()) metrics.apl.coerceIn(0f, 1f) else 0.25f
            val p99 = if (metrics.p99Luma.isFinite()) metrics.p99Luma.coerceIn(0f, 1f) else 0.5f
            val dark = if (metrics.darkPixelRatio.isFinite()) metrics.darkPixelRatio.coerceIn(0f, 1f) else 0.3f

            val nowNs = metrics.timestampNs
            val dtSec = if (lastUpdateNs == 0L) 0.033f
            else ((nowNs - lastUpdateNs).coerceIn(1_000_000L, 250_000_000L)) / 1_000_000_000f
            lastUpdateNs = nowNs

            val peakDrive = (p99 * 0.72f + apl * 0.28f).coerceIn(0f, 1f)
            val darkBoost = (1f - dark * 0.35f).coerceIn(0.65f, 1f)
            val desiredDim = (dimmingFloorDMin + (1f - dimmingFloorDMin) * (peakDrive * darkBoost))
                .coerceIn(dimmingFloorDMin, 1.0f)

            // +/-3% input hysteresis so filteredTargetDim still converges to 1.0 and D_min
            if (abs(desiredDim - latchedDesiredDim) >= HYSTERESIS_DEADBAND ||
                desiredDim >= 0.99f || desiredDim <= dimmingFloorDMin + 0.01f
            ) {
                latchedDesiredDim = desiredDim
            }

            val tau = if (latchedDesiredDim > filteredTargetDim) TAU_UP_SEC else TAU_DOWN_SEC
            val alpha = 1f - exp(-dtSec / tau)
            filteredTargetDim = (filteredTargetDim + alpha * (latchedDesiredDim - filteredTargetDim))
                .coerceIn(dimmingFloorDMin, 1.0f)

            val targetNits = (userCeilingNits * filteredTargetDim).coerceAtLeast(RuanHardwareProfile.KNOT_MIN_NITS)
            val targetNorm = RuanBacklightSpline.nitsToBrightness(targetNits)

            // Timestamped ~24 ms transport delay queue
            delayQueue.addLast(DelaySample(nowNs, targetNorm))
            var activeDelayedTarget = delayQueue.first().targetNorm
            while (delayQueue.size > 1 && nowNs - delayQueue.first().timestampNs >= PIPELINE_LATENCY_NS) {
                activeDelayedTarget = delayQueue.removeFirst().targetNorm
            }

            val rampRatePerSec = if (activeDelayedTarget >= estimatedBrightnessNorm) {
                RuanHardwareProfile.RAMP_RATE_FAST_PER_SEC
            } else {
                RuanHardwareProfile.RAMP_RATE_SLOW_PER_SEC
            }
            val maxStep = rampRatePerSec * dtSec
            val delta = activeDelayedTarget - estimatedBrightnessNorm
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
}
