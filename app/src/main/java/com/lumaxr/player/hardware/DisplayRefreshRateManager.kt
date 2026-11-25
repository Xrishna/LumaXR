package com.lumaxr.player.hardware

import android.app.Activity
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

enum class RefreshRatePolicy {
    AUTO_CINEMA_120HZ,
    AUTO_CINEMA_48HZ,
    EXACT_MATCH_ONLY,
    OFF,
}

class DisplayRefreshRateManager {

    private val _activeRefreshHz = MutableStateFlow(120)
    val activeRefreshHz: StateFlow<Int> = _activeRefreshHz.asStateFlow()

    fun selectTargetRefreshHz(
        videoFps: Double,
        policy: RefreshRatePolicy,
        supportedRatesHz: IntArray = RuanHardwareProfile.DISCRETE_REFRESH_RATES_HZ,
    ): Int {
        if (policy == RefreshRatePolicy.OFF || !videoFps.isFinite() || videoFps <= 0.0) {
            return pickNearest(120, supportedRatesHz)
        }

        val desired = when (policy) {
            RefreshRatePolicy.AUTO_CINEMA_120HZ -> when {
                isApprox(videoFps, 23.976, 0.25) || isApprox(videoFps, 24.0, 0.25) -> 120
                isApprox(videoFps, 25.0, 0.25) || isApprox(videoFps, 50.0, 0.5) -> 50
                isApprox(videoFps, 29.97, 0.25) || isApprox(videoFps, 30.0, 0.25) ||
                    isApprox(videoFps, 59.94, 0.5) || isApprox(videoFps, 60.0, 0.5) -> 120
                isApprox(videoFps, 48.0, 0.5) -> 48
                isApprox(videoFps, 90.0, 1.0) -> 90
                else -> 120
            }
            RefreshRatePolicy.AUTO_CINEMA_48HZ -> when {
                isApprox(videoFps, 23.976, 0.25) || isApprox(videoFps, 24.0, 0.25) -> 48
                isApprox(videoFps, 25.0, 0.25) || isApprox(videoFps, 50.0, 0.5) -> 50
                isApprox(videoFps, 29.97, 0.25) || isApprox(videoFps, 30.0, 0.25) -> 60
                isApprox(videoFps, 59.94, 0.5) || isApprox(videoFps, 60.0, 0.5) -> 120
                isApprox(videoFps, 48.0, 0.5) -> 48
                isApprox(videoFps, 90.0, 1.0) -> 90
                else -> 120
            }
            RefreshRatePolicy.EXACT_MATCH_ONLY -> {
                val nearest = supportedRatesHz.minByOrNull { abs(it - videoFps) } ?: 120
                if (abs(nearest - videoFps) <= 0.5) nearest else 120
            }
            RefreshRatePolicy.OFF -> 120
        }
        return pickNearest(desired, supportedRatesHz)
    }

    fun applyToWindow(
        activity: Activity,
        videoFps: Double,
        policy: RefreshRatePolicy,
    ): Int {
        val display: Display? = activity.display
        val supported = display?.supportedModes
            ?.map { kotlin.math.round(it.refreshRate).toInt() }
            ?.distinct()
            ?.toIntArray()
            ?.takeIf { it.isNotEmpty() }
            ?: RuanHardwareProfile.DISCRETE_REFRESH_RATES_HZ

        val targetHz = selectTargetRefreshHz(videoFps, policy, supported)
        applyExactHz(activity, targetHz, display)
        return targetHz
    }

    fun restore120Hz(activity: Activity) {
        applyExactHz(activity, 120, activity.display)
    }

    private fun applyExactHz(activity: Activity, targetHz: Int, display: Display?) {
        val lp = activity.window.attributes
        val bestMode = display?.supportedModes
            ?.filter { abs(it.refreshRate - targetHz) < 1.5f }
            ?.maxByOrNull { it.physicalWidth * it.physicalHeight }

        if (bestMode != null) {
            if (lp.preferredDisplayModeId != bestMode.modeId) {
                lp.preferredDisplayModeId = bestMode.modeId
                activity.window.attributes = lp
            }
            _activeRefreshHz.value = kotlin.math.round(bestMode.refreshRate).toInt()
            return
        }

        lp.preferredDisplayModeId = 0
        lp.preferredRefreshRate = targetHz.toFloat()
        activity.window.attributes = lp
        _activeRefreshHz.value = targetHz
    }

    private fun pickNearest(desired: Int, supported: IntArray): Int {
        return supported.minByOrNull { abs(it - desired) } ?: 120
    }

    private fun isApprox(value: Double, target: Double, tol: Double): Boolean {
        return abs(value - target) <= tol
    }
}
