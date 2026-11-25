package com.lumaxr.player.music

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class LumaHaptics(context: Context) {

    private val vibrator: Vibrator? = context.applicationContext
        .getSystemService(VibratorManager::class.java)?.defaultVibrator

    fun playTactileClick() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 36 && v.areEnvelopeEffectsSupported()) {
            runCatching {
                val envInfo = v.envelopeEffectInfo
                val freqProfile = v.frequencyProfile
                val minFreq = freqProfile?.minFrequencyHz ?: 80f
                val maxFreq = (freqProfile?.maxFrequencyHz ?: 220f).coerceAtLeast(minFreq)
                val minStep = envInfo?.minControlPointDurationMillis ?: 10L
                val maxStep = (envInfo?.maxControlPointDurationMillis ?: 100L).coerceAtLeast(minStep)
                val stepMs = 12L.coerceIn(minStep, maxStep)

                val effect = VibrationEffect.WaveformEnvelopeBuilder()
                    .setInitialFrequencyHz(minFreq)
                    .addControlPoint(0.85f, ((minFreq + maxFreq) * 0.55f).coerceIn(minFreq, maxFreq), stepMs)
                    .addControlPoint(0.0f, minFreq, stepMs)
                    .build()
                v.vibrate(effect)
                return
            }
        }
        if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
            val comp = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f)
                .compose()
            v.vibrate(comp)
        } else {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        }
    }

    fun playSeekDetent() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 36 && v.areEnvelopeEffectsSupported()) {
            runCatching {
                val envInfo = v.envelopeEffectInfo
                val freqProfile = v.frequencyProfile
                val minFreq = freqProfile?.minFrequencyHz ?: 70f
                val maxFreq = (freqProfile?.maxFrequencyHz ?: 200f).coerceAtLeast(minFreq)
                val minStep = envInfo?.minControlPointDurationMillis ?: 8L
                val maxStep = (envInfo?.maxControlPointDurationMillis ?: 100L).coerceAtLeast(minStep)
                val stepMs = 10L.coerceIn(minStep, maxStep)

                val effect = VibrationEffect.WaveformEnvelopeBuilder()
                    .setInitialFrequencyHz(minFreq)
                    .addControlPoint(0.45f, (minFreq * 1.4f).coerceIn(minFreq, maxFreq), stepMs)
                    .addControlPoint(0.0f, minFreq, stepMs)
                    .build()
                v.vibrate(effect)
                return
            }
        }
        if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
            val comp = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.45f)
                .compose()
            v.vibrate(comp)
        } else {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
    }
}
