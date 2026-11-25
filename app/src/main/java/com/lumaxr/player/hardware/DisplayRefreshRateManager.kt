package com.lumaxr.player.hardware

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

enum class RefreshRatePolicy {
    AUTO_CINEMA_120HZ, // 23.976/24 -> 120Hz (5x), 25/50 -> 50Hz, 29.97/30/60 -> 120Hz
    AUTO_CINEMA_48HZ,  // 23.976/24 -> 48Hz (2x battery saver), 25/50 -> 50Hz, 30/60 -> 120Hz
    EXACT_MATCH_ONLY,
    OFF,               // Always 120Hz
}

/**
 * Zero-judder refresh rate manager locked to Redmi Pad Pro 5G discrete modes:
 * [120, 90, 60, 50, 48, 30] Hz (hasArrSupport = false).
 */
class DisplayRefreshRateManager {

    private val _activeRefreshHz = MutableStateFlow(120)
    val activeRefreshHz: StateFlow<Int> = _activeRefreshHz.asStateFlow()

    fun selectTargetRefreshHz(
        videoFps: Double,
        policy: RefreshRatePolicy,
        supportedRatesHz: IntArray = RuanHardwareProfile.DISCRETE_REFRESH_RATES_HZ,
    ): Int {
        if (policy == RefreshRatePolicy.OFF || videoFps <= 0.0) {
            return pickNearest(120, supportedRatesHz)
        }

        val desired = when (policy) {
            RefreshRatePolicy.AUTO_CINEMA_120HZ -> when {
                isApprox(videoFps, 23.976, 0.25) || isApprox(videoFps, 24.0, 0.25) -> 120 // 5x integer cadence
                isApprox(videoFps, 25.0, 0.25) || isApprox(videoFps, 50.0, 0.5) -> 50     // exact PAL lock
                isApprox(videoFps, 29.97, 0.25) || isApprox(videoFps, 30.0, 0.25) ||
                    isApprox(videoFps, 59.94, 0.5) || isApprox(videoFps, 60.0, 0.5) -> 120
                isApprox(videoFps, 48.0, 0.5) -> 48
                isApprox(videoFps, 90.0, 1.0) -> 90
                else -> 120
            }
            RefreshRatePolicy.AUTO_CINEMA_48HZ -> when {
                isApprox(videoFps, 23.976, 0.25) || isApprox(videoFps, 24.0, 0.25) -> 48  // 2x cinema battery saver
                isApprox(videoFps, 25.0, 0.25) || isApprox(videoFps, 50.0, 0.5) -> 50
                isApprox(videoFps, 29.97, 0.25) || isApprox(videoFps, 30.0, 0.25) -> 60
                isApprox(videoFps, 59.94, 0.5) || isApprox(videoFps, 60.0, 0.5) -> 120
                else -> 120
            }
            RefreshRatePolicy.EXACT_MATCH_ONLY -> {
                supportedRatesHz.minByOrNull { abs(it - videoFps) } ?: 120
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
        _activeRefreshHz.value = targetHz
        val lp = activity.window.attributes
        val bestMode = display?.supportedModes
            ?.filter { abs(it.refreshRate - targetHz) < 1.5f }
            ?.maxByOrNull { it.physicalWidth * it.physicalHeight }

        if (bestMode != null && lp.preferredDisplayModeId != bestMode.modeId) {
            lp.preferredDisplayModeId = bestMode.modeId
            activity.window.attributes = lp
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.preferredRefreshRate = targetHz.toFloat()
            activity.window.attributes = lp
        }
    }

    private fun pickNearest(desired: Int, supported: IntArray): Int {
        return supported.minByOrNull { abs(it - desired) } ?: 120
    }

    private fun isApprox(value: Double, target: Double, tol: Double): Boolean {
        return abs(value - target) <= tol
    }
}
