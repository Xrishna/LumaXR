package com.lumaxr.player.hardware

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.health.GpuHeadroomParams
import android.os.health.SystemHealthManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThermalTier(
    val scalerOverride: String?,
    val bilateralRadius: Int, // 4 = 9-tap, 2 = 5-tap, 0 = off
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
 * Monitors PowerManager.OnThermalStatusChangedListener (SKIN), API 36 SystemHealthManager.getGpuHeadroom(),
 * software dav1d AV1 decode (>1080p), and MPV vo-delayed-frame-count / frame-drop-count.
 */
class ThermalGovernor(private val context: Context) {

    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val systemHealthManager: SystemHealthManager? =
        context.getSystemService(SystemHealthManager::class.java)

    private val _snapshot = MutableStateFlow(GovernorSnapshot())
    val snapshot: StateFlow<GovernorSnapshot> = _snapshot.asStateFlow()

    private var lastGpuPollMs: Long = 0L
    private var gpuMinIntervalMs: Long = 1000L
    private var currentThermalStatus: Int = PowerManager.THERMAL_STATUS_NONE
    private var prevDelayedFrames: Long = 0L
    private var prevDroppedFrames: Long = 0L

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        currentThermalStatus = status
        recomputeTier()
    }

    fun start() {
        runCatching {
            currentThermalStatus = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
            powerManager?.addThermalStatusListener(context.mainExecutor, thermalListener)
        }
        if (Build.VERSION.SDK_INT >= 36 && systemHealthManager != null) {
            runCatching {
                gpuMinIntervalMs = systemHealthManager.gpuHeadroomMinIntervalMillis.coerceAtLeast(500L)
            }
        }
        recomputeTier()
    }

    fun stop() {
        runCatching {
            powerManager?.removeThermalStatusListener(thermalListener)
        }
    }

    fun updatePlaybackTelemetry(
        delayedFrames: Long,
        droppedFrames: Long,
        isSoftwareAv1Over1080p: Boolean,
    ) {
        val nowMs = System.currentTimeMillis()
        var headroom: Float? = _snapshot.value.gpuHeadroomPercent

        if (Build.VERSION.SDK_INT >= 36 &&
            systemHealthManager != null &&
            nowMs - lastGpuPollMs >= gpuMinIntervalMs
        ) {
            lastGpuPollMs = nowMs
            headroom = queryApi36GpuHeadroom(systemHealthManager)
        }

        val delayedDelta = (delayedFrames - prevDelayedFrames).coerceAtLeast(0L)
        val droppedDelta = (droppedFrames - prevDroppedFrames).coerceAtLeast(0L)
        prevDelayedFrames = delayedFrames
        prevDroppedFrames = droppedFrames

        recomputeTier(
            gpuHeadroom = headroom,
            delayedFrames = delayedFrames,
            droppedFrames = droppedFrames,
            recentFramePressure = delayedDelta >= 4 || droppedDelta >= 2,
            softwareAv1Active = isSoftwareAv1Over1080p,
        )
    }

    private fun queryApi36GpuHeadroom(shm: SystemHealthManager): Float? {
        if (Build.VERSION.SDK_INT < 36) return null
        return try {
            val range = shm.gpuHeadroomCalculationWindowRange
            val windowMs = 2000.coerceIn(range.first, range.second)
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

    private fun recomputeTier(
        gpuHeadroom: Float? = _snapshot.value.gpuHeadroomPercent,
        delayedFrames: Long = _snapshot.value.delayedFrames,
        droppedFrames: Long = _snapshot.value.droppedFrames,
        recentFramePressure: Boolean = false,
        softwareAv1Active: Boolean = _snapshot.value.softwareAv1Active,
    ) {
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
