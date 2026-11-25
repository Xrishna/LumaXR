package com.lumaxr.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumaxr.player.music.AutoEqParser
import com.lumaxr.player.music.DiagnosticAudioMode
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerScreen(viewModel: MainViewModel) {
    val dsp = viewModel.container.dspController
    val dspState by dsp.state.collectAsState()
    val usbState by viewModel.container.usbBitPerfectManager.state.collectAsState()
    val spatState by viewModel.container.spatialAudioManager.state.collectAsState()
    val route by viewModel.container.deviceProfileRouter.activeRoute.collectAsState()
    val musicState by viewModel.container.musicCoordinator.state.collectAsState()
    val scope = rememberCoroutineScope()
    val dspEnabled = !usbState.activeBitPerfect

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Active Output: ${route.displayName}", color = Color(0xFF38BDF8), fontWeight = FontWeight.SemiBold)
        if (!dspEnabled) {
            Text(
                "Bit-Perfect USB Direct is active — PEQ, Crossfeed, and DRC are bypassed.",
                color = Color(0xFFFBBF24),
                fontSize = 12.sp,
            )
        }

        if (usbState.connected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("USB DAC Bit-Perfect Mode (${usbState.deviceName})", color = Color.White, fontSize = 14.sp)
                    Text(
                        text = if (usbState.activeBitPerfect) "Active • Hardware Volume Only • DSP Bypassed"
                        else if (usbState.supportsBitPerfect) "Supported (${usbState.supportedRatesHz.joinToString()})"
                        else "Not reported by HAL for this DAC",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = musicState.bitPerfectPreferred,
                    onCheckedChange = { viewModel.container.musicCoordinator.setBitPerfectPreferred(it) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Spatializer: ${if (spatState.systemSpatializerActive) "ON (Dolby/Multichannel)" else "OFF"} • HeadTrack: ${spatState.isHeadTrackerAvailable}",
                color = Color(0xFFCBD5E1),
                fontSize = 12.sp,
            )
            FilterChip(
                selected = spatState.sofaHrtfActive,
                onClick = { viewModel.container.spatialAudioManager.setSofaHrtfRequested(!spatState.sofaHrtfActive) },
                label = { Text("SOFA HRTF") },
            )
        }

        Text("AutoEQ Target Presets", color = Color.White, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AutoEqParser.BUILTIN_PRESETS.forEach { preset ->
                FilterChip(
                    enabled = dspEnabled,
                    selected = dspState.activePresetName == preset.name,
                    onClick = { dsp.applyAutoEqPreset(preset) },
                    label = { Text(preset.name, fontSize = 11.sp) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("BS2B Crossfeed", color = Color.White)
            Switch(
                enabled = dspEnabled,
                checked = dspState.crossfeedEnabled,
                onCheckedChange = { dsp.updateToggles(crossfeedEnabled = it) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Night Mode DRC Dialogue Boost", color = Color.White)
            Switch(
                enabled = dspEnabled,
                checked = dspState.nightModeDrcEnabled,
                onCheckedChange = { dsp.updateToggles(nightModeDrcEnabled = it) },
            )
        }

        Text("Karaoke Vocal-Reduce (Mid/Side): ${(dspState.karaokeVocalReduceStrength * 100).toInt()}%", color = Color.White, fontSize = 13.sp)
        Slider(
            enabled = dspEnabled,
            value = dspState.karaokeVocalReduceStrength,
            onValueChange = { dsp.updateToggles(karaokeStrength = it) },
        )

        Text("Headphone / DAC Diagnostic", color = Color.White, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DiagnosticAudioMode.entries.forEach { mode ->
                FilterChip(
                    enabled = dspEnabled,
                    selected = dspState.diagnosticMode == mode,
                    onClick = { dsp.updateToggles(diagnosticMode = mode) },
                    label = { Text(mode.name.replace('_', ' '), fontSize = 11.sp) },
                )
            }
        }

        Text(
            "15-Band Parametric EQ (Preamp ${String.format(Locale.US, "%.1f", dspState.preampDb)} dB)",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
        AutoEqParser.ISO_15_BANDS_HZ.forEachIndexed { idx, hz ->
            val gain = dspState.bandGainsDb.getOrElse(idx) { 0f }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${hz.toInt()}Hz", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
                Slider(
                    enabled = dspEnabled,
                    value = gain,
                    onValueChange = { dsp.updateBand(idx, it) },
                    valueRange = -12f..12f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    String.format(Locale.US, "%.1f", gain),
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        Button(
            onClick = {
                scope.launch {
                    viewModel.container.deviceProfileRouter.saveCurrentProfileForActiveRoute(musicState.bitPerfectPreferred)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save Profile for ${route.displayName}")
        }
        Spacer(Modifier.height(16.dp))
    }
}
