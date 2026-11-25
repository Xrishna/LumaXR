package com.lumaxr.player.video

enum class AspectMode(val label: String) {
    ORIGINAL("Original Aspect"),
    SOFT_FILL_16_10("16:10 Soft-Fill (7%)"),
    FILL_16_10("Crop Fill 16:10"),
}

enum class SubtitlePlacement(val label: String) {
    ABOVE_LETTERBOX("Above Black Bar"),
    INSIDE_LETTERBOX("Inside Black Bar"),
}

data class SmartAspectState(
    val aspectMode: AspectMode = AspectMode.ORIGINAL,
    val softFillPercent: Float = 0.07f, // 5-10% soft-fill for 2.39:1 scope films
    val subtitlePlacement: SubtitlePlacement = SubtitlePlacement.ABOVE_LETTERBOX,
    val letterboxTopNorm: Float = 0.0f,
    val letterboxBottomNorm: Float = 1.0f,
)

/**
 * Smart Black-Bar & 16:10 Aspect Ratio Manager for 2560x1600 Redmi Pad Pro panel.
 */
class SmartAspectManager {

    fun computeLetterboxBounds(
        videoWidth: Int,
        videoHeight: Int,
        state: SmartAspectState,
        panelAspect: Float = 2560f / 1600f,
    ): SmartAspectState {
        if (videoWidth <= 0 || videoHeight <= 0) return state
        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        if (videoAspect <= panelAspect + 0.05f || state.aspectMode == AspectMode.FILL_16_10) {
            return state.copy(letterboxTopNorm = 0f, letterboxBottomNorm = 1f)
        }
        val effectiveZoom = if (state.aspectMode == AspectMode.SOFT_FILL_16_10) {
            1f + state.softFillPercent.coerceIn(0.05f, 0.10f)
        } else {
            1f
        }
        val occupiedHeightFraction = ((panelAspect / videoAspect) * effectiveZoom).coerceIn(0.3f, 1.0f)
        val bar = (1f - occupiedHeightFraction) * 0.5f
        return state.copy(
            letterboxTopNorm = bar.coerceIn(0f, 0.45f),
            letterboxBottomNorm = (1f - bar).coerceIn(0.55f, 1f),
        )
    }

    fun toMpvVideoZoom(state: SmartAspectState, videoWidth: Int, videoHeight: Int): Double {
        if (videoWidth <= 0 || videoHeight <= 0) return 0.0
        val panelAspect = 1.6
        val videoAspect = videoWidth.toDouble() / videoHeight.toDouble()
        return when (state.aspectMode) {
            AspectMode.ORIGINAL -> 0.0
            AspectMode.SOFT_FILL_16_10 -> {
                val factor = 1.0 + state.softFillPercent.coerceIn(0.05f, 0.10f)
                kotlin.math.log2(factor)
            }
            AspectMode.FILL_16_10 -> {
                val ratio = (videoAspect / panelAspect).coerceAtLeast(1.0)
                kotlin.math.log2(ratio)
            }
        }
    }

    fun toMpvSubPos(placement: SubtitlePlacement): Int = when (placement) {
        SubtitlePlacement.ABOVE_LETTERBOX -> 86
        SubtitlePlacement.INSIDE_LETTERBOX -> 98
    }
}
