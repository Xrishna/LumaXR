package com.lumaxr.player.music

import kotlin.math.abs
import kotlin.math.ln

data class PeqFilter(
    val frequencyHz: Float,
    val gainDb: Float,
    val qFactor: Float = 1.41f,
)

data class AutoEqPreset(
    val name: String,
    val preampDb: Float,
    val bandGainsDb: FloatArray, // 15-band ISO centers
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

/**
 * AutoEQ target curve & parametric PEQ / GraphicEQ CSV parser.
 * Maps arbitrary AutoEQ parametric filters onto LumaXR's 15-band ISO centers.
 */
object AutoEqParser {

    val ISO_15_BANDS_HZ = floatArrayOf(
        25f, 40f, 63f, 100f, 160f, 250f, 400f, 630f,
        1000f, 1600f, 2500f, 4000f, 6300f, 10000f, 16000f,
    )

    val BUILTIN_PRESETS: List<AutoEqPreset> = listOf(
        AutoEqPreset(
            name = "Flat Reference",
            preampDb = 0f,
            bandGainsDb = FloatArray(15) { 0f },
        ),
        AutoEqPreset(
            name = "Harman Over-Ear 2018",
            preampDb = -2.5f,
            bandGainsDb = floatArrayOf(3.2f, 2.8f, 2.1f, 1.0f, -0.4f, -0.8f, -0.3f, 0.2f, 0.6f, 1.4f, 2.2f, 1.8f, 0.9f, -0.5f, -1.2f),
        ),
        AutoEqPreset(
            name = "Harman In-Ear 2019",
            preampDb = -3.0f,
            bandGainsDb = floatArrayOf(4.0f, 3.4f, 2.5f, 1.2f, -0.5f, -1.0f, -0.4f, 0.3f, 1.0f, 2.1f, 3.0f, 2.0f, 0.5f, -0.8f, -1.5f),
        ),
        AutoEqPreset(
            name = "Redmi Pad Quad Speaker Warmth",
            preampDb = -2.0f,
            bandGainsDb = floatArrayOf(2.5f, 3.0f, 2.4f, 1.6f, 0.5f, -0.4f, -0.8f, 0.2f, 0.8f, 1.2f, 0.6f, -0.4f, -0.9f, -1.2f, -1.5f),
        ),
    )

    /**
     * Parses AutoEQ ParametricEQ.txt (`Filter 1: ON PK Fc 105 Hz Gain -3.1 dB Q 0.70`)
     * or GraphicEQ CSV (`frequency,raw,error,...,gain`) into a 15-band AutoEqPreset.
     */
    fun parseAutoEqText(name: String, rawText: String): AutoEqPreset {
        var preamp = 0f
        val filters = mutableListOf<PeqFilter>()

        val lines = rawText.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("Preamp:", ignoreCase = true)) {
                preamp = trimmed.substringAfter(':').replace("dB", "", ignoreCase = true).trim().toFloatOrNull() ?: 0f
                continue
            }
            if (trimmed.startsWith("Filter", ignoreCase = true) && trimmed.contains("Fc", ignoreCase = true)) {
                val fc = Regex("""Fc\s+([0-9.]+)""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)?.toFloatOrNull()
                val gain = Regex("""Gain\s+(-?[0-9.]+)""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)?.toFloatOrNull()
                val q = Regex("""Q\s+([0-9.]+)""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)?.toFloatOrNull() ?: 1.41f
                if (fc != null && gain != null) {
                    filters += PeqFilter(fc, gain, q)
                }
            } else if (trimmed.first().isDigit() && trimmed.contains(",")) {
                val cols = trimmed.split(",")
                val fc = cols.getOrNull(0)?.trim()?.toFloatOrNull()
                val gain = cols.lastOrNull()?.trim()?.toFloatOrNull()
                if (fc != null && gain != null) {
                    filters += PeqFilter(fc, gain, 1.41f)
                }
            }
        }

        val bands = FloatArray(ISO_15_BANDS_HZ.size) { idx ->
            val center = ISO_15_BANDS_HZ[idx]
            var acc = 0f
            for (f in filters) {
                val octDist = abs(ln((center / f.frequencyHz).coerceAtLeast(0.01f)) / ln(2f))
                val bw = 1.0f / f.qFactor.coerceIn(0.3f, 8f)
                val weight = kotlin.math.exp(-0.5f * (octDist / bw) * (octDist / bw))
                acc += f.gainDb * weight
            }
            acc.coerceIn(-12f, 12f)
        }
        return AutoEqPreset(name = name, preampDb = preamp.coerceIn(-12f, 6f), bandGainsDb = bands)
    }
}
