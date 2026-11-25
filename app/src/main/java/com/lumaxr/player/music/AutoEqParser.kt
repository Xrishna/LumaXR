package com.lumaxr.player.music

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

data class PeqFilter(
    val frequencyHz: Float,
    val gainDb: Float,
    val qFactor: Float = 1.41f,
)

data class AutoEqPreset(
    val name: String,
    val preampDb: Float,
    val bandGainsDb: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AutoEqPreset) return false
        return name == other.name && preampDb == other.preampDb && bandGainsDb.contentEquals(other.bandGainsDb)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + preampDb.hashCode()
        result = 31 * result + bandGainsDb.contentHashCode()
        return result
    }
}

object AutoEqParser {

    val ISO_15_BANDS_HZ = floatArrayOf(
        25f, 40f, 63f, 100f, 160f, 250f, 400f, 630f,
        1000f, 1600f, 2500f, 4000f, 6300f, 10000f, 16000f,
    )

    val BUILTIN_PRESETS: List<AutoEqPreset> = listOf(
        AutoEqPreset("Flat Reference", 0f, FloatArray(15) { 0f }),
        AutoEqPreset(
            "Harman Over-Ear 2018",
            -2.5f,
            floatArrayOf(3.2f, 2.8f, 2.1f, 1.0f, -0.4f, -0.8f, -0.3f, 0.2f, 0.6f, 1.4f, 2.2f, 1.8f, 0.9f, -0.5f, -1.2f),
        ),
        AutoEqPreset(
            "Harman In-Ear 2019",
            -3.0f,
            floatArrayOf(4.0f, 3.4f, 2.5f, 1.2f, -0.5f, -1.0f, -0.4f, 0.3f, 1.0f, 2.1f, 3.0f, 2.0f, 0.5f, -0.8f, -1.5f),
        ),
        AutoEqPreset(
            "Redmi Pad Quad Speaker Warmth",
            -2.0f,
            floatArrayOf(2.5f, 3.0f, 2.4f, 1.6f, 0.5f, -0.4f, -0.8f, 0.2f, 0.8f, 1.2f, 0.6f, -0.4f, -0.9f, -1.2f, -1.5f),
        ),
    )

    fun parseAutoEqText(name: String, rawText: String): AutoEqPreset {
        var preamp = 0f
        val filters = mutableListOf<PeqFilter>()

        for (line in rawText.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("Preamp:", ignoreCase = true)) {
                val parsed = trimmed.substringAfter(':').replace("dB", "", ignoreCase = true).trim().toFloatOrNull()
                if (parsed != null && parsed.isFinite()) preamp = parsed
                continue
            }
            if (trimmed.startsWith("Filter", ignoreCase = true) && trimmed.contains("Fc", ignoreCase = true)) {
                if (Regex("""\bOFF\b""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) continue
                val fc = Regex("""Fc\s+([0-9.]+)""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)?.toFloatOrNull()
                val gain = Regex("""Gain\s+(-?[0-9.]+)""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)?.toFloatOrNull()
                val q = Regex("""Q\s+([0-9.]+)""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)?.toFloatOrNull() ?: 1.41f
                if (fc != null && fc.isFinite() && fc > 0f && gain != null && gain.isFinite() && q.isFinite()) {
                    filters += PeqFilter(fc, gain, q)
                }
            } else if (trimmed.first().isDigit() && trimmed.contains(",")) {
                val cols = trimmed.split(",")
                val fc = cols.getOrNull(0)?.trim()?.toFloatOrNull()
                val gain = cols.lastOrNull()?.trim()?.toFloatOrNull()
                if (fc != null && fc.isFinite() && fc > 0f && gain != null && gain.isFinite()) {
                    filters += PeqFilter(fc, gain, 1.41f)
                }
            }
        }

        val bands = FloatArray(ISO_15_BANDS_HZ.size) { idx ->
            val center = ISO_15_BANDS_HZ[idx]
            var acc = 0f
            var weightSum = 0f
            for (f in filters) {
                val octDist = abs(ln((center / f.frequencyHz).coerceAtLeast(0.01f)) / ln(2f))
                val bw = 1.0f / f.qFactor.coerceIn(0.3f, 8f)
                val weight = exp(-0.5f * (octDist / bw) * (octDist / bw))
                acc += f.gainDb * weight
                weightSum += weight
            }
            val normalized = if (weightSum > 1.0f) acc / weightSum else acc
            normalized.coerceIn(-12f, 12f)
        }
        return AutoEqPreset(name = name, preampDb = preamp.coerceIn(-12f, 6f), bandGainsDb = bands)
    }
}
