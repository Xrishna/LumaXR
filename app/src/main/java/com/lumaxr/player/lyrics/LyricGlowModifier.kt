package com.lumaxr.player.lyrics

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.lumaxr.player.visuals.GlowType

data class LyricStyleConfig(
    val glowType: GlowType = GlowType.SOFT_BLOOM,
    val glowIntensity: Float = 0.90f, // 0.0 .. 2.0
    val highlightColor: Color = Color.White,
    val fontSizeSp: Float = 28f,
    val lineSpacingDp: Float = 18f,
    val inactiveBlurStrength: Float = 0.55f, // 0.0 .. 1.0
    val showTranslation: Boolean = true,
    val showRomanization: Boolean = true,
)

/**
 * Computes continuous Apple Music-style per-word horizontal gradient wipe brushes
 * and sustained held-note (> 700 ms) bloom intensity.
 */
object LyricGlowModifier {

    fun wordProgress(word: LyricWord, clockMs: Long): Float {
        if (clockMs <= word.startMs) return 0f
        if (clockMs >= word.endMs) return 1f
        return ((clockMs - word.startMs).toFloat() / word.durationMs.toFloat()).coerceIn(0f, 1f)
    }

    fun heldNoteBloomFactor(word: LyricWord, clockMs: Long, style: LyricStyleConfig): Float {
        if (style.glowType == GlowType.OFF || style.glowIntensity <= 0.01f) return 0f
        if (word.durationMs <= 700L || clockMs < word.startMs || clockMs > word.endMs + 250L) {
            return 0f
        }
        val p = wordProgress(word, clockMs)
        val wave = kotlin.math.sin(p * kotlin.math.PI).toFloat().coerceIn(0f, 1f)
        return wave * style.glowIntensity.coerceIn(0f, 2f)
    }

    fun buildWordSweepBrush(
        progress: Float,
        highlightColor: Color,
        inactiveAlpha: Float = 0.36f,
    ): Brush {
        val feather = 0.14f
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0f) {
            val dim = highlightColor.copy(alpha = inactiveAlpha)
            return Brush.horizontalGradient(listOf(dim, dim))
        }
        if (p >= 1f) {
            return Brush.horizontalGradient(listOf(highlightColor, highlightColor))
        }
        val leftStop = (p - feather * 0.5f).coerceIn(0f, 1f)
        val rightStop = (p + feather * 0.5f).coerceIn(0f, 1f)
        val dim = highlightColor.copy(alpha = inactiveAlpha)
        return Brush.horizontalGradient(
            0.0f to highlightColor,
            leftStop to highlightColor,
            rightStop to dim,
            1.0f to dim,
        )
    }
}
