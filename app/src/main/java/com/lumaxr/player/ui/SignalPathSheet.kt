package com.lumaxr.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumaxr.player.music.SignalPathTelemetry
import java.util.Locale

/**
 * Roon / Poweramp-style Interactive Signal-Path Telemetry Sheet showing every live audio stage
 * plus True-Peak LUFS metering.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalPathSheet(
    telemetry: SignalPathTelemetry,
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
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Audiophile Signal Path", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            SignalStageCard("1. Source Bitstream", telemetry.sourceSummary, Color(0xFF38BDF8))
            SignalStageCard("2. Engine & Hardware Decoder", telemetry.engineAndDecoder, Color(0xFFA78BFA))
            SignalStageCard("3. DSP & Loudness Processing", telemetry.processingStage, Color(0xFF34D399))
            SignalStageCard("4. Android Output Route", telemetry.outputPath, Color(0xFFFBBF24))

            Surface(
                color = Color(0xFF1E293B),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Integrated Loudness: ${String.format(Locale.US, "%.1f", telemetry.lufs)} LUFS",
                        color = Color(0xFFE2E8F0),
                        fontSize = 13.sp,
                    )
                    Text(
                        "True-Peak: ${String.format(Locale.US, "%.1f", telemetry.truePeakDbfs)} dBFS",
                        color = Color(0xFF38BDF8),
                        fontSize = 13.sp,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SignalStageCard(title: String, detail: String, accent: Color) {
    Surface(
        color = Color(0xFF111827),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(detail, color = Color.White, fontSize = 14.sp)
        }
    }
}
