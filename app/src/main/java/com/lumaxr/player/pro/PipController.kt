package com.lumaxr.player.pro

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.util.Rational
import androidx.core.content.ContextCompat
import com.lumaxr.player.hardware.DisplayRefreshRateManager

/**
 * Android 16 Auto-Enter Picture-in-Picture 2.0 Manager:
 * - setAutoEnterEnabled(isPlaying) + setSeamlessResizeEnabled(true)
 * - Exact sourceRectHint + expanded 16:10 / video aspect ratio
 * - Custom RemoteActions (Seek -10s, Play/Pause, Seek +10s)
 * - Restores 120 Hz refresh rate cleanly when entering/exiting PiP
 */
class PipController(
    private val activity: Activity,
    private val refreshRateManager: DisplayRefreshRateManager,
    private val onPlayPause: () -> Unit,
    private val onSeekDeltaSec: (Double) -> Unit,
) {
    companion object {
        const val ACTION_PIP_CONTROL = "com.lumaxr.player.PIP_CONTROL"
        const val EXTRA_CMD = "cmd"
        const val CMD_REWIND = 1
        const val CMD_PLAY_PAUSE = 2
        const val CMD_FORWARD = 3
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PIP_CONTROL) return
            when (intent.getIntExtra(EXTRA_CMD, 0)) {
                CMD_REWIND -> onSeekDeltaSec(-10.0)
                CMD_PLAY_PAUSE -> onPlayPause()
                CMD_FORWARD -> onSeekDeltaSec(10.0)
            }
        }
    }

    fun register() {
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter(ACTION_PIP_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun unregister() {
        runCatching { activity.unregisterReceiver(receiver) }
    }

    fun updateAutoPipParams(
        isPlayingVideo: Boolean,
        videoWidth: Int,
        videoHeight: Int,
        sourceRect: Rect?,
    ) {
        val ratio = if (videoWidth > 0 && videoHeight > 0) {
            Rational(videoWidth, videoHeight).let { r ->
                val f = r.toFloat()
                if (f in 0.42f..2.39f) r else Rational(16, 10)
            }
        } else {
            Rational(16, 10)
        }

        val actions = listOf(
            buildAction(CMD_REWIND, android.R.drawable.ic_media_rew, "Rewind 10s"),
            buildAction(
                CMD_PLAY_PAUSE,
                if (isPlayingVideo) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlayingVideo) "Pause" else "Play",
            ),
            buildAction(CMD_FORWARD, android.R.drawable.ic_media_ff, "Forward 10s"),
        )

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setExpandedAspectRatio(ratio)
            .setAutoEnterEnabled(isPlayingVideo)
            .setSeamlessResizeEnabled(true)
            .setActions(actions)
        if (sourceRect != null && !sourceRect.isEmpty) {
            builder.setSourceRectHint(sourceRect)
        }
        runCatching { activity.setPictureInPictureParams(builder.build()) }
    }

    fun onPipModeChanged(isInPip: Boolean) {
        if (isInPip) {
            refreshRateManager.restore120Hz(activity)
        }
    }

    private fun buildAction(cmd: Int, iconRes: Int, title: String): RemoteAction {
        val intent = Intent(ACTION_PIP_CONTROL)
            .setPackage(activity.packageName)
            .putExtra(EXTRA_CMD, cmd)
        val pending = PendingIntent.getBroadcast(
            activity,
            cmd,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(Icon.createWithResource(activity, iconRes), title, title, pending)
    }
}
