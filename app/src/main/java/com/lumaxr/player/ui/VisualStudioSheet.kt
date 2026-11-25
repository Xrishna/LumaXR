package com.lumaxr.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumaxr.player.visuals.GlowConfigState
import com.lumaxr.player.visuals.GlowType
import com.lumaxr.player.visuals.MilkDropPreset
import com.lumaxr.player.visuals.VisualizerMode
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VisualStudioSheet(
    config: GlowConfigState,
    onUpdate: (GlowConfigState) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F172A),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Kawarp & Visualizer Studio (120 Hz)", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { onUpdate(GlowConfigState.default()) }) {
                    Text("Reset")
                }
            }

            Text("Visualizer Mode", color = Color(0xFFCBD5E1), fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VisualizerMode.entries.forEach { mode ->
                    FilterChip(
                        selected = config.visualizerMode == mode,
                        onClick = { onUpdate(config.copy(visualizerMode = mode)) },
                        label = { Text(mode.label, fontSize = 11.sp) },
                    )
                }
            }

            if (config.visualizerMode == VisualizerMode.MILKDROP_PLASMA) {
                Text("MilkDrop / Plasma Preset", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MilkDropPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = config.milkDropPreset == preset,
                            onClick = { onUpdate(config.copy(milkDropPreset = preset)) },
                            label = { Text(preset.label, fontSize = 11.sp) },
                        )
                    }
                }
            }

            Text("Glow Type", color = Color(0xFFCBD5E1), fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowType.entries.forEach { g ->
                    FilterChip(
                        selected = config.glowType == g,
                        onClick = { onUpdate(config.copy(glowType = g)) },
                        label = { Text(g.label, fontSize = 11.sp) },
                    )
                }
            }

            StudioSlider("Glow Intensity", "${(config.glowIntensity * 100).roundToInt()}%", config.glowIntensity, 0f..2f) {
                onUpdate(config.copy(glowIntensity = it))
            }
            StudioSlider("Glow Radius / Spread", String.format(Locale.US, "%.2f", config.glowRadius), config.glowRadius, 0f..3f) {
                onUpdate(config.copy(glowRadius = it))
            }
            StudioSlider("Audio Beat Reactivity", "${(config.beatReactivity * 100).roundToInt()}%", config.beatReactivity, 0f..1.5f) {
                onUpdate(config.copy(beatReactivity = it))
            }
            StudioSlider("Kawarp Warp Speed", String.format(Locale.US, "%.2fx", config.warpSpeed), config.warpSpeed, 0.1f..3f) {
                onUpdate(config.copy(warpSpeed = it))
            }
            StudioSlider("Warp Distortion / Scale", String.format(Locale.US, "%.2f", config.warpDistortion), config.warpDistortion, 0f..2f) {
                onUpdate(config.copy(warpDistortion = it))
            }
            StudioSlider(
                label = "Kawase Blur Passes",
                readout = "${config.kawaseBlurPasses}",
                value = config.kawaseBlurPasses.toFloat(),
                range = 2f..8f,
                steps = 5,
            ) {
                onUpdate(config.copy(kawaseBlurPasses = it.roundToInt().coerceIn(2, 8)))
            }
            StudioSlider("Color Saturation", String.format(Locale.US, "%.2fx", config.colorSaturation), config.colorSaturation, 0.5f..2f) {
                onUpdate(config.copy(colorSaturation = it))
            }
            StudioSlider("Dark Area Tint Intensity", "${(config.darkTintIntensity * 100).roundToInt()}%", config.darkTintIntensity, 0f..1f) {
                onUpdate(config.copy(darkTintIntensity = it))
            }
            StudioSlider("Backdrop Dimmer", "${(config.backdropDimmer * 100).roundToInt()}%", config.backdropDimmer, 0.15f..1f) {
                onUpdate(config.copy(backdropDimmer = it))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ambient Cinema Glow (Video Mode)", color = Color.White, fontSize = 13.sp)
                Switch(
                    checked = config.ambientCinemaGlow,
                    onCheckedChange = { onUpdate(config.copy(ambientCinemaGlow = it)) },
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StudioSlider(
    label: String,
    readout: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, fontSize = 12.sp)
            Text(readout, color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}
