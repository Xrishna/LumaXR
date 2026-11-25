package com.lumaxr.player

import com.google.common.truth.Truth.assertThat
import com.lumaxr.player.visuals.GlowConfigState
import org.junit.Test

class GlowConfigStateTest {

    @Test
    fun clampsOutOfRangeGlowAndKawarpSlidersStrictly() {
        val dirty = GlowConfigState(
            glowIntensity = 5.5f,
            glowRadius = -2.0f,
            beatReactivity = 3.0f,
            warpSpeed = 0.01f,
            warpDistortion = 9.0f,
            kawaseBlurPasses = 20,
            colorSaturation = 0.1f,
            darkTintIntensity = 2.5f,
            backdropDimmer = 0.0f,
        )
        val clamped = dirty.clamped()
        assertThat(clamped.glowIntensity).isEqualTo(2.0f)
        assertThat(clamped.glowRadius).isEqualTo(0.0f)
        assertThat(clamped.beatReactivity).isEqualTo(1.5f)
        assertThat(clamped.warpSpeed).isEqualTo(0.1f)
        assertThat(clamped.warpDistortion).isEqualTo(2.0f)
        assertThat(clamped.kawaseBlurPasses).isEqualTo(8)
        assertThat(clamped.colorSaturation).isEqualTo(0.5f)
        assertThat(clamped.darkTintIntensity).isEqualTo(1.0f)
        assertThat(clamped.backdropDimmer).isEqualTo(0.15f)
    }
}
