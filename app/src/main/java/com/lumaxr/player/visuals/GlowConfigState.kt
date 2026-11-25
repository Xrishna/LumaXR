package com.lumaxr.player.visuals

import kotlinx.serialization.Serializable

enum class VisualizerMode(val label: String) {
    KAWARP_FLUID("Kawarp Fluid"),
    MILKDROP_PLASMA("MilkDrop / Plasma Flow"),
    HARMONIC_SPECTRUM("Harmonic Spectrum"),
    OSCILLOSCOPE_LISSAJOUS("Oscilloscope & Lissajous"),
    MINIMAL_STATIC("Minimal Static / Black"),
}

enum class MilkDropPreset(val id: Int, val label: String) {
    NEBULA_DRIFT(0, "Nebula Drift"),
    LIQUID_CHROME(1, "Liquid Chrome"),
    SOLAR_FLARE(2, "Solar Flare"),
    SYNTH_GRID(3, "Synth Grid"),
    WAVEFORM_WARP(4, "Waveform Warp"),
    DEEP_OCEAN(5, "Deep Ocean"),
}

enum class GlowType(val id: Int, val label: String) {
    OFF(0, "Off"),
    SOFT_BLOOM(1, "Soft Bloom"),
    NEON_CORE(2, "Neon Core"),
    CHROMATIC_PRISM(3, "Chromatic Prism"),
    CRISP_RIM(4, "Crisp Rim"),
    ADAPTIVE_ARTWORK(5, "Adaptive Artwork"),
}

@Serializable
data class GlowConfigState(
    val visualizerMode: VisualizerMode = VisualizerMode.KAWARP_FLUID,
    val milkDropPreset: MilkDropPreset = MilkDropPreset.NEBULA_DRIFT,
    val glowType: GlowType = GlowType.SOFT_BLOOM,
    val glowIntensity: Float = 0.85f,
    val glowRadius: Float = 1.25f,
    val beatReactivity: Float = 0.85f,
    val warpSpeed: Float = 1.0f,
    val warpDistortion: Float = 0.90f,
    val kawaseBlurPasses: Int = 5,
    val colorSaturation: Float = 1.35f,
    val darkTintColorArgb: Long = 0xFF0A1020L,
    val darkTintIntensity: Float = 0.45f,
    val backdropDimmer: Float = 0.72f,
    val ambientCinemaGlow: Boolean = false,
) {
    fun clamped(): GlowConfigState = copy(
        glowIntensity = safeClamp(glowIntensity, 0.85f, 0f, 2.0f),
        glowRadius = safeClamp(glowRadius, 1.25f, 0f, 3.0f),
        beatReactivity = safeClamp(beatReactivity, 0.85f, 0f, 1.5f),
        warpSpeed = safeClamp(warpSpeed, 1.0f, 0.1f, 3.0f),
        warpDistortion = safeClamp(warpDistortion, 0.90f, 0f, 2.0f),
        kawaseBlurPasses = kawaseBlurPasses.coerceIn(2, 8),
        colorSaturation = safeClamp(colorSaturation, 1.35f, 0.5f, 2.0f),
        darkTintIntensity = safeClamp(darkTintIntensity, 0.45f, 0f, 1.0f),
        backdropDimmer = safeClamp(backdropDimmer, 0.72f, 0.15f, 1.0f),
    )

    private fun safeClamp(v: Float, fallback: Float, min: Float, max: Float): Float {
        return (if (v.isFinite()) v else fallback).coerceIn(min, max)
    }

    companion object {
        fun default() = GlowConfigState()
    }
}
