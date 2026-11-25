package com.lumaxr.player.video

import android.view.KeyEvent as AndroidKeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput

data class DesktopShortcutCallbacks(
    val onTogglePlayPause: () -> Unit = {},
    val onSeekRelativeSec: (Double) -> Unit = {},
    val onAdjustVolumeDelta: (Float) -> Unit = {},
    val onToggleTelemetryPill: () -> Unit = {},
    val onToggleDeband: () -> Unit = {},
    val onCycleToneMap: () -> Unit = {},
    val onTakeSnapshot: () -> Unit = {},
    val onNextTrack: () -> Unit = {},
    val onPrevTrack: () -> Unit = {},
    val onToggleLyrics: () -> Unit = {},
    val onToggleEqualizer: () -> Unit = {},
    val onToggleMute: () -> Unit = {},
    val onOpenContextMenu: () -> Unit = {},
)

object DesktopInputHandler {

    fun Modifier.lumaDesktopShortcuts(callbacks: DesktopShortcutCallbacks): Modifier =
        this.onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return@onKeyEvent false
            when (event.key) {
                Key.Spacebar -> { callbacks.onTogglePlayPause(); true }
                Key.DirectionLeft -> { callbacks.onSeekRelativeSec(-10.0); true }
                Key.DirectionRight -> { callbacks.onSeekRelativeSec(10.0); true }
                Key.DirectionUp -> { callbacks.onAdjustVolumeDelta(0.05f); true }
                Key.DirectionDown -> { callbacks.onAdjustVolumeDelta(-0.05f); true }
                Key.Menu -> { callbacks.onOpenContextMenu(); true }
                Key.I -> { callbacks.onToggleTelemetryPill(); true }
                Key.D -> { callbacks.onToggleDeband(); true }
                Key.T -> { callbacks.onCycleToneMap(); true }
                Key.S -> { callbacks.onTakeSnapshot(); true }
                Key.N -> { callbacks.onNextTrack(); true }
                Key.P -> { callbacks.onPrevTrack(); true }
                Key.L -> { callbacks.onToggleLyrics(); true }
                Key.E -> { callbacks.onToggleEqualizer(); true }
                Key.M -> { callbacks.onToggleMute(); true }
                else -> false
            }
        }

    fun Modifier.lumaMouseAndTrackpadCanvas(
        onScrollSeekOrVolume: (deltaY: Float, isRightHalf: Boolean) -> Unit,
        onSecondaryContextMenu: () -> Unit,
    ): Modifier = this
        .pointerHoverIcon(PointerIcon.Default)
        .pointerInput(onScrollSeekOrVolume, onSecondaryContextMenu) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    when (event.type) {
                        PointerEventType.Press -> {
                            if (event.buttons.isSecondaryPressed) {
                                event.changes.forEach { it.consume() }
                                onSecondaryContextMenu()
                            }
                        }
                        PointerEventType.Scroll -> {
                            val change = event.changes.firstOrNull() ?: continue
                            val dy = change.scrollDelta.y
                            if (dy != 0f) {
                                change.consume()
                                val isRight = change.position.x > (size.width * 0.5f)
                                onScrollSeekOrVolume(dy, isRight)
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }

    fun buildSystemKeyboardShortcuts(): List<KeyboardShortcutGroup> {
        return listOf(
            KeyboardShortcutGroup(
                "LumaXR Video & Cinema",
                listOf(
                    KeyboardShortcutInfo("Play / Pause", AndroidKeyEvent.KEYCODE_SPACE, 0),
                    KeyboardShortcutInfo("Seek -10s / +10s", AndroidKeyEvent.KEYCODE_DPAD_LEFT, 0),
                    KeyboardShortcutInfo("Seek +10s", AndroidKeyEvent.KEYCODE_DPAD_RIGHT, 0),
                    KeyboardShortcutInfo("Toggle M3 Telemetry Pill", AndroidKeyEvent.KEYCODE_I, 0),
                    KeyboardShortcutInfo("Toggle Deband", AndroidKeyEvent.KEYCODE_D, 0),
                    KeyboardShortcutInfo("Cycle Tone-Map", AndroidKeyEvent.KEYCODE_T, 0),
                    KeyboardShortcutInfo("Scene Snapshot", AndroidKeyEvent.KEYCODE_S, 0),
                ),
            ),
            KeyboardShortcutGroup(
                "LumaXR Audiophile Music",
                listOf(
                    KeyboardShortcutInfo("Next Track", AndroidKeyEvent.KEYCODE_N, 0),
                    KeyboardShortcutInfo("Previous Track", AndroidKeyEvent.KEYCODE_P, 0),
                    KeyboardShortcutInfo("Toggle Lyrics Studio", AndroidKeyEvent.KEYCODE_L, 0),
                    KeyboardShortcutInfo("Toggle Parametric EQ", AndroidKeyEvent.KEYCODE_E, 0),
                    KeyboardShortcutInfo("Mute / Unmute", AndroidKeyEvent.KEYCODE_M, 0),
                ),
            ),
        )
    }
}
