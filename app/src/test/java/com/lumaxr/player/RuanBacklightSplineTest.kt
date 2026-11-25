package com.lumaxr.player

import com.google.common.truth.Truth.assertThat
import com.lumaxr.player.hardware.RuanBacklightSpline
import com.lumaxr.player.hardware.RuanHardwareProfile
import org.junit.Test

class RuanBacklightSplineTest {

    @Test
    fun splineMatchesVerifiedHardwareKnotsExactly() {
        assertThat(RuanBacklightSpline.brightnessToNits(RuanHardwareProfile.KNOT_MIN_NORM)).isWithin(0.01f).of(2.0f)
        assertThat(RuanBacklightSpline.brightnessToNits(RuanHardwareProfile.KNOT_NORMAL_NORM)).isWithin(0.05f).of(500.0f)
        assertThat(RuanBacklightSpline.brightnessToNits(RuanHardwareProfile.KNOT_HBM_NORM)).isWithin(0.05f).of(600.0f)
    }

    @Test
    fun roundTripNitsToBrightnessIsInvertible() {
        listOf(30f, 120f, 245.6f, 500f, 580f).forEach { nits ->
            val b = RuanBacklightSpline.nitsToBrightness(nits)
            val back = RuanBacklightSpline.brightnessToNits(b)
            assertThat(back).isWithin(0.1f).of(nits)
        }
    }

    @Test
    fun ambientTargetPeakStaysClampedWithin203To300Nits() {
        assertThat(RuanBacklightSpline.computeAmbientTargetPeakNits(15f)).isAtLeast(203)
        assertThat(RuanBacklightSpline.computeAmbientTargetPeakNits(350f)).isAtMost(300)
        assertThat(RuanBacklightSpline.computeAmbientTargetPeakNits(150f, manualOverrideNits = 240f)).isEqualTo(240)
    }
}
