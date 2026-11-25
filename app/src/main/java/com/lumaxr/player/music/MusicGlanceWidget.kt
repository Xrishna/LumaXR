package com.lumaxr.player.music

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.lumaxr.player.LumaXrApplication
import com.lumaxr.player.MainActivity
import kotlinx.coroutines.delay
import java.util.Locale

class MusicGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MusicGlanceWidget()
}

class MusicGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as? LumaXrApplication
        val stateFlow = app?.container?.musicCoordinator?.state

        provideContent {
            val state = stateFlow?.collectAsState()?.value
            val track = state?.currentTrack

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(Color(0xFF0E131F))
                    .clickable(actionStartActivity<MainActivity>())
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = track?.title ?: "LumaXR Audiophile",
                    style = TextStyle(
                        color = ColorProvider(Color.White),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = if (track != null) "${track.artist} • ${track.codec.uppercase(Locale.US)}" else "Tap to play Hi-Res & Dolby",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF94A3B8)),
                        fontSize = 12.sp,
                    ),
                    maxLines = 1,
                )
                Spacer(modifier = GlanceModifier.height(10.dp))
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "⏮ Prev",
                        style = TextStyle(color = ColorProvider(Color(0xFF7DD3FC)), fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        modifier = GlanceModifier
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .background(Color(0xFF1E293B))
                            .clickable(actionRunCallback<GlancePrevAction>()),
                    )
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    Text(
                        text = if (state?.isPlaying == true) "⏸ Pause" else "▶ Play",
                        style = TextStyle(color = ColorProvider(Color.Black), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        modifier = GlanceModifier
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .background(Color(0xFF38BDF8))
                            .clickable(actionRunCallback<GlancePlayPauseAction>()),
                    )
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    Text(
                        text = "Next ⏭",
                        style = TextStyle(color = ColorProvider(Color(0xFF7DD3FC)), fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        modifier = GlanceModifier
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .background(Color(0xFF1E293B))
                            .clickable(actionRunCallback<GlanceNextAction>()),
                    )
                }
            }
        }
    }
}

class GlancePlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as? LumaXrApplication
        app?.container?.musicCoordinator?.togglePlayPause()
        delay(80L)
        MusicGlanceWidget().update(context, glanceId)
    }
}

class GlancePrevAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as? LumaXrApplication
        app?.container?.musicCoordinator?.prevTrack()
        delay(80L)
        MusicGlanceWidget().update(context, glanceId)
    }
}

class GlanceNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as? LumaXrApplication
        app?.container?.musicCoordinator?.nextTrack()
        delay(80L)
        MusicGlanceWidget().update(context, glanceId)
    }
}
