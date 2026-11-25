package com.lumaxr.player

import com.google.common.truth.Truth.assertThat
import com.lumaxr.player.hardware.DisplayRefreshRateManager
import com.lumaxr.player.hardware.RefreshRatePolicy
import org.junit.Test

class DisplayRefreshRateManagerTest {

    private val mgr = DisplayRefreshRateManager()

    @Test
    fun autoCinema120HzLocks24FpsTo120HzAnd25FpsTo50Hz() {
        assertThat(mgr.selectTargetRefreshHz(23.976, RefreshRatePolicy.AUTO_CINEMA_120HZ)).isEqualTo(120)
        assertThat(mgr.selectTargetRefreshHz(24.0, RefreshRatePolicy.AUTO_CINEMA_120HZ)).isEqualTo(120)
        assertThat(mgr.selectTargetRefreshHz(25.0, RefreshRatePolicy.AUTO_CINEMA_120HZ)).isEqualTo(50)
        assertThat(mgr.selectTargetRefreshHz(50.0, RefreshRatePolicy.AUTO_CINEMA_120HZ)).isEqualTo(50)
        assertThat(mgr.selectTargetRefreshHz(29.97, RefreshRatePolicy.AUTO_CINEMA_120HZ)).isEqualTo(120)
        assertThat(mgr.selectTargetRefreshHz(60.0, RefreshRatePolicy.AUTO_CINEMA_120HZ)).isEqualTo(120)
    }

    @Test
    fun autoCinema48HzLocks24FpsTo48Hz() {
        assertThat(mgr.selectTargetRefreshHz(23.976, RefreshRatePolicy.AUTO_CINEMA_48HZ)).isEqualTo(48)
        assertThat(mgr.selectTargetRefreshHz(25.0, RefreshRatePolicy.AUTO_CINEMA_48HZ)).isEqualTo(50)
    }

    @Test
    fun offPolicyAlwaysRestores120Hz() {
        assertThat(mgr.selectTargetRefreshHz(25.0, RefreshRatePolicy.OFF)).isEqualTo(120)
    }
}
