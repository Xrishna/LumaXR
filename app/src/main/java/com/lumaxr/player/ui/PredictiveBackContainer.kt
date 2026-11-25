package com.lumaxr.player.ui

import android.content.Context
import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lumaxr.player.hardware.RuanHardwareProfile
import kotlinx.coroutines.CancellationException
import kotlin.math.pow

enum class PredictiveBackStyle(val label: String) {
    PREDICTIVE_DETACH_SHARED("Predictive Detach + Shared"),
    MATERIAL_FADE_THROUGH("Material Fade Through"),
    NONE("None (Reduce Motion)"),
}

fun isReduceMotionEnabled(context: Context): Boolean {
    val scale = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    return scale == 0f
}

/**
 * Zero-relayout 120 Hz (< 8.3 ms) press feedback modifier scaling strictly inside graphicsLayer.
 */
@Composable
fun Modifier.lumaPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.975f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessHigh),
        label = "pressScale120Hz",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Two-Phase NavDisplay Predictive Back Handoff Container:
 * - Phase 1 (InProgress drag): Suppresses shared elements (`onSharedVisibilityChange(false)`),
 *   detaches foreground screen smoothly (90% scale, hardware-matched 19dp corners, 8dp margin,
 *   edge-aware left/right shift, STANDARD_DECELERATE curve; crisp spring rebound on cancel).
 * - Phase 2 (Committed / tap-back): Flips shared element visibility to true so SharedTransitionLayout
 *   glides cleanly from detached bounds into the origin card (or cleanly dissolves if off-screen).
 */
@Composable
fun PredictiveBackContainer(
    enabled: Boolean,
    style: PredictiveBackStyle,
    onSharedVisibilityChange: (Boolean) -> Unit,
    onBackCommitted: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val effectiveStyle = if (isReduceMotionEnabled(context)) PredictiveBackStyle.NONE else style

    var isDragging by remember { mutableStateOf(false) }
    var rawProgress by remember { mutableFloatStateOf(0f) }
    var swipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }

    val animatedProgress by animateFloatAsState(
        targetValue = if (isDragging) rawProgress else 0f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMedium),
        label = "predictiveBackProgress",
    )

    DisposableEffect(Unit) {
        onDispose {
            if (isDragging) {
                isDragging = false
                rawProgress = 0f
                onSharedVisibilityChange(true)
            }
        }
    }

    PredictiveBackHandler(enabled = enabled && effectiveStyle != PredictiveBackStyle.NONE) { progressFlow ->
        isDragging = true
        onSharedVisibilityChange(false) // Phase 1: suppress shared elements so ApproachLayout never fights detach scale
        try {
            progressFlow.collect { backEvent ->
                rawProgress = backEvent.progress.coerceIn(0f, 1f)
                swipeEdge = backEvent.swipeEdge
            }
            // Phase 2: Committed -> re-enable shared element visibility & pop
            isDragging = false
            rawProgress = 0f
            onSharedVisibilityChange(true)
            onBackCommitted()
        } catch (e: CancellationException) {
            isDragging = false
            rawProgress = 0f
            onSharedVisibilityChange(true)
            throw e
        } finally {
            if (isDragging) {
                isDragging = false
                rawProgress = 0f
                onSharedVisibilityChange(true)
            }
        }
    }

    // STANDARD_DECELERATE approximation: 1 - (1 - t)^3
    val decel = 1f - (1f - animatedProgress).pow(3)
    val scale = when (effectiveStyle) {
        PredictiveBackStyle.PREDICTIVE_DETACH_SHARED -> 1f - 0.10f * decel // 100% -> 90%
        PredictiveBackStyle.MATERIAL_FADE_THROUGH -> 1f - 0.04f * decel
        PredictiveBackStyle.NONE -> 1f
    }
    val cornerDp = (RuanHardwareProfile.CORNER_RADIUS_DP * decel).dp
    val edgeDir = if (swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = if (effectiveStyle == PredictiveBackStyle.PREDICTIVE_DETACH_SHARED) {
                    edgeDir * 16.dp.toPx() * decel
                } else 0f
                alpha = if (effectiveStyle == PredictiveBackStyle.MATERIAL_FADE_THROUGH) {
                    1f - 0.25f * decel
                } else 1f
            }
            .clip(RoundedCornerShape(cornerDp)),
    ) {
        content()
    }
}
