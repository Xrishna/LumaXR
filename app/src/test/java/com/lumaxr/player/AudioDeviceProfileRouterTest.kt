package com.lumaxr.player

import android.media.AudioDeviceInfo
import com.google.common.truth.Truth.assertThat
import com.lumaxr.player.data.LumaDatabase
import com.lumaxr.player.music.AudioDeviceProfileRouter
import com.lumaxr.player.music.AudioDspController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioDeviceProfileRouterTest {

    @Test
    fun defaultsToBuiltInQuadSpeakersWhenNoExternalOutputConnected() {
        val ctx = RuntimeEnvironment.getApplication()
        val db = LumaDatabase.getInstance(ctx)
        val router = AudioDeviceProfileRouter(
            context = ctx,
            scope = CoroutineScope(Dispatchers.Unconfined),
            musicDao = db.musicLibraryDao(),
            dspController = AudioDspController(),
        )
        val route = router.classifyRoute(emptyList<AudioDeviceInfo>())
        assertThat(route.deviceKey).isEqualTo("SPEAKER_QUAD")
        assertThat(route.displayName).contains("Quad Speakers")
        assertThat(route.isUsbDac).isFalse()
    }
}
