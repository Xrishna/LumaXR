package com.lumaxr.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumaxr.player.lyrics.LoadedLyricsState
import com.lumaxr.player.lyrics.LyricStyleConfig
import com.lumaxr.player.visuals.GlowType
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LyricsStudioSheet(
    lyricsState: LoadedLyricsState,
    style: LyricStyleConfig,
    onUpdateStyle: (LyricStyleConfig) -> Unit,
    onUpdateOffset: (Int) -> Unit,
    onSearchManual: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Apple Music Lyrics Studio", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Source: ${lyricsState.provider.ifBlank { "None" }} • Format: ${lyricsState.document.sourceFormat}",
                color = Color(0xFF38BDF8),
                fontSize = 12.sp,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Match Fixer: Title - Artist") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = searchQuery.isNotBlank(),
                    onClick = { onSearchManual(searchQuery.trim()) },
                ) {
                    Text("Search")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sync Offset: ${lyricsState.offsetMs} ms", color = Color.White, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { onUpdateOffset(lyricsState.offsetMs - 50) }) { Text("-50") }
                    OutlinedButton(onClick = { onUpdateOffset(0) }) { Text("0") }
                    OutlinedButton(onClick = { onUpdateOffset(lyricsState.offsetMs + 50) }) { Text("+50") }
                }
            }
            Slider(
                value = lyricsState.offsetMs.toFloat(),
                onValueChange = { onUpdateOffset((it / 50f).roundToInt() * 50) },
                valueRange = -3000f..3000f,
                steps = 119,
            )

            Text("Lyric Glow Type", color = Color(0xFFCBD5E1), fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowType.entries.forEach { g ->
                    FilterChip(
                        selected = style.glowType == g,
                        onClick = { onUpdateStyle(style.copy(glowType = g)) },
                        label = { Text(g.label, fontSize = 11.sp) },
                    )
                }
            }

            Text("Lyric Glow Intensity: ${(style.glowIntensity * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
            Slider(
                value = style.glowIntensity,
                onValueChange = { onUpdateStyle(style.copy(glowIntensity = it)) },
                valueRange = 0f..2f,
            )

            Text("Font Size: ${style.fontSizeSp.toInt()} sp", color = Color.White, fontSize = 12.sp)
            Slider(
                value = style.fontSizeSp,
                onValueChange = { onUpdateStyle(style.copy(fontSizeSp = it)) },
                valueRange = 18f..42f,
            )

            Text("Inactive DOF Blur Strength: ${(style.inactiveBlurStrength * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
            Slider(
                value = style.inactiveBlurStrength,
                onValueChange = { onUpdateStyle(style.copy(inactiveBlurStrength = it)) },
                valueRange = 0f..1f,
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Show Translation Sub-lines", color = Color.White)
                Switch(checked = style.showTranslation, onCheckedChange = { onUpdateStyle(style.copy(showTranslation = it)) })
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Show Romanization", color = Color.White)
                Switch(checked = style.showRomanization, onCheckedChange = { onUpdateStyle(style.copy(showRomanization = it)) })
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
