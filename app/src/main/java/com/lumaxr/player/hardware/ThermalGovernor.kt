package com.lumaxr.player.hardware

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.health.GpuHeadroomParams
import android.os.health.SystemHealthManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThermalTier(
    val scalerOverride: String?,
    val bilateralRadius: Int,
    val allowDeband: Boolean,
    val visualizerMaxFps: Int,
) {
    OPTIMAL(null, bilateralRadius = 4, allowDeband = true, visualizerMaxFps = 120),
    MODERATE("spline36", bilateralRadius = 2, allowDeband = true, visualizerMaxFps = 60),
    THROTTLED("bilinear", bilateralRadius = 2, allowDeband = false, visualizerMaxFps = 30),
    CRITICAL("bilinear", bilateralRadius = 0, allowDeband = false, visualizerMaxFps = 0),
}

data class GovernorSnapshot(
    val tier: ThermalTier = ThermalTier.OPTIMAL,
    val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
    val gpuHeadroomPercent: Float? = null,
    val delayedFrames: Long = 0L,
    val droppedFrames: Long = 0L,
    val softwareAv1Active: Boolean = false,
)

/**
 * General Thermal, API 36 GPU Headroom & Frame-Drop Governor.
 * Fixes android.util.Range<Int> (.lower/.upper), monotonic elapsedRealtime(), and synchronized state.
 */
class ThermalGovernor(context: Context) {

    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val systemHealthManager: SystemHealthManager? =
        appContext.getSystemService(SystemHealthManager::class.java)

    private val lock = Any()
    private val _snapshot = MutableStateFlow(GovernorSnapshot())
    val snapshot: StateFlow<GovernorSnapshot> = _snapshot.asStateFlow()

    private var isStarted = false
    private var lastGpuPollMs: Long = 0L
    private var gpuMinIntervalMs: Long = 1000L
    private var currentThermalStatus: Int = PowerManager.THERMAL_STATUS_NONE
    private var prevDelayedFrames: Long = 0L
    private var prevDroppedFrames: Long = 0L
    private var pressureHoldUntilMs: Long = 0L

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        synchronized(lock) {
            currentThermalStatus = status
            recomputeTierLocked()
        }
    }

    fun start() {
        synchronized(lock) {
            if (isStarted) return
            isStarted = true
            runCatching {
                currentThermalStatus = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                powerManager?.addThermalStatusListener(appContext.mainExecutor, thermalListener)
            }
            if (Build.VERSION.SDK_INT >= 36 && systemHealthManager != null) {
                runCatching {
                    gpuMinIntervalMs = systemHealthManager.gpuHeadroomMinIntervalMillis.coerceAtLeast(500L)
                }
            }
            recomputeTierLocked()
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!isStarted) return
            isStarted = false
            runCatching { powerManager?.removeThermalStatusListener(thermalListener) }
        }
    }

    fun updatePlaybackTelemetry(
        delayedFrames: Long,
        droppedFrames: Long,
        isSoftwareAv1Over1080p: Boolean,
    ) {
        val nowMs = SystemClock.elapsedRealtime()
        var headroom: Float?
        val shouldPollGpu: Boolean
        synchronized(lock) {
            headroom = _snapshot.value.gpuHeadroomPercent
            shouldPollGpu = Build.VERSION.SDK_INT >= 36 &&
                systemHealthManager != null &&
                nowMs - lastGpuPollMs >= gpuMinIntervalMs
            if (shouldPollGpu) lastGpuPollMs = nowMs
        }

        if (shouldPollGpu && Build.VERSION.SDK_INT >= 36 && systemHealthManager != null) {
            headroom = queryApi36GpuHeadroom(systemHealthManager)
        }

        synchronized(lock) {
            val delayedDelta = (delayedFrames - prevDelayedFrames).coerceAtLeast(0L)
            val droppedDelta = (droppedFrames - prevDroppedFrames).coerceAtLeast(0L)
            prevDelayedFrames = delayedFrames
            prevDroppedFrames = droppedFrames

            if (delayedDelta >= 4 || droppedDelta >= 2) {
                pressureHoldUntilMs = nowMs + 4000L
            }
            recomputeTierLocked(
                gpuHeadroom = headroom,
                delayedFrames = delayedFrames,
                droppedFrames = droppedFrames,
                softwareAv1Active = isSoftwareAv1Over1080p,
            )
        }
    }

    @RequiresApi(36)
    private fun queryApi36GpuHeadroom(shm: SystemHealthManager): Float? {
        return try {
            val range = shm.gpuHeadroomCalculationWindowRange
            val windowMs = 2000.coerceIn(range.lower, range.upper)
            val params = GpuHeadroomParams.Builder()
                .setCalculationType(GpuHeadroomParams.GPU_HEADROOM_CALCULATION_TYPE_AVERAGE)
                .setCalculationWindowMillis(windowMs)
                .build()
            val value = shm.getGpuHeadroom(params)
            if (value.isNaN()) null else value.coerceIn(0f, 100f)
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun recomputeTierLocked(
        gpuHeadroom: Float? = _snapshot.value.gpuHeadroomPercent,
        delayedFrames: Long = _snapshot.value.delayedFrames,
        droppedFrames: Long = _snapshot.value.droppedFrames,
        softwareAv1Active: Boolean = _snapshot.value.softwareAv1Active,
    ) {
        val recentFramePressure = SystemClock.elapsedRealtime() <= pressureHoldUntilMs
        val tier = when {
            currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ||
                (gpuHeadroom != null && gpuHeadroom < 10f) -> ThermalTier.CRITICAL
            currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ||
                (gpuHeadroom != null && gpuHeadroom < 22f) ||
                (softwareAv1Active && recentFramePressure) -> ThermalTier.THROTTLED
            currentThermalStatus >= PowerManager.THERMAL_STATUS_LIGHT ||
                (gpuHeadroom != null && gpuHeadroom < 38f) ||
                softwareAv1Active ||
                recentFramePressure -> ThermalTier.MODERATE
            else -> ThermalTier.OPTIMAL
        }
        _snapshot.value = GovernorSnapshot(
            tier = tier,
            thermalStatus = currentThermalStatus,
            gpuHeadroomPercent = gpuHeadroom,
            delayedFrames = delayedFrames,
            droppedFrames = droppedFrames,
            softwareAv1Active = softwareAv1Active,
        )
    }
}
