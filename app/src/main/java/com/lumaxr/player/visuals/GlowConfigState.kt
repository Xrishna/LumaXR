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
    val glowIntensity: Float = 0.85f,       // 0.0 .. 2.0 (0% .. 200%)
    val glowRadius: Float = 1.25f,          // 0.0 .. 3.0
    val beatReactivity: Float = 0.85f,      // 0.0 .. 1.5 (0% .. 150%)
    val warpSpeed: Float = 1.0f,            // 0.1 .. 3.0
    val warpDistortion: Float = 0.90f,      // 0.0 .. 2.0
    val kawaseBlurPasses: Int = 5,          // 2 .. 8
    val colorSaturation: Float = 1.35f,     // 0.5 .. 2.0
    val darkTintColorArgb: Long = 0xFF0A1020L,
    val darkTintIntensity: Float = 0.45f,   // 0.0 .. 1.0 (0% .. 100%)
    val backdropDimmer: Float = 0.72f,      // 0.15 .. 1.0
    val ambientCinemaGlow: Boolean = false,
) {
    fun clamped(): GlowConfigState = copy(
        glowIntensity = glowIntensity.coerceIn(0f, 2.0f),
        glowRadius = glowRadius.coerceIn(0f, 3.0f),
        beatReactivity = beatReactivity.coerceIn(0f, 1.5f),
        warpSpeed = warpSpeed.coerceIn(0.1f, 3.0f),
        warpDistortion = warpDistortion.coerceIn(0f, 2.0f),
        kawaseBlurPasses = kawaseBlurPasses.coerceIn(2, 8),
        colorSaturation = colorSaturation.coerceIn(0.5f, 2.0f),
        darkTintIntensity = darkTintIntensity.coerceIn(0f, 1.0f),
        backdropDimmer = backdropDimmer.coerceIn(0.15f, 1.0f),
    )

    companion object {
        fun default() = GlowConfigState()
    }
}
