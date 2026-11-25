package com.lumaxr.player.visuals

import android.graphics.RenderEffect
import android.graphics.RuntimeColorFilter
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Android 14-16 AGSL RuntimeShader Mesh Gradient & API 36 RuntimeColorFilter helper.
 */
object AgslAlbumBackdrop {

    private const val MESH_AGSL = """
        uniform float2 u_res;
        uniform float u_time;
        uniform float3 u_c1;
        uniform float3 u_c2;
        uniform float u_dim;
        half4 main(float2 fragCoord) {
            float2 uv = fragCoord / max(u_res, float2(1.0));
            float w1 = 0.5 + 0.5 * sin(uv.x * 3.1 + u_time * 0.4 + cos(uv.y * 2.4 - u_time * 0.25));
            float w2 = 0.5 + 0.5 * cos(uv.y * 3.5 - u_time * 0.35 + sin(uv.x * 2.2 + u_time * 0.3));
            float3 col = mix(u_c1, u_c2, clamp(0.5 * w1 + 0.5 * w2, 0.0, 1.0));
            col *= u_dim;
            return half4(col, 1.0);
        }
    """

    private const val API36_GLOW_COLOR_FILTER_AGSL = """
        uniform float u_sat;
        vec4 main(half4 in_color) {
            float luma = dot(in_color.rgb, half3(0.2126, 0.7152, 0.0722));
            half3 boosted = mix(half3(luma), in_color.rgb, half(u_sat));
            return vec4(clamp(boosted, 0.0, 1.0), in_color.a);
        }
    """

    fun createApi36SaturationColorFilter(saturation: Float): RuntimeColorFilter? {
        if (Build.VERSION.SDK_INT < 36) return null
        return runCatching {
            RuntimeColorFilter(API36_GLOW_COLOR_FILTER_AGSL).apply {
                setFloatUniform("u_sat", saturation.coerceIn(0.5f, 2.0f))
            }
        }.getOrNull()
    }

    fun createBlurRenderEffect(radiusPx: Float): androidx.compose.ui.graphics.RenderEffect? {
        if (radiusPx <= 0.5f) return null
        return RenderEffect.createBlurEffect(radiusPx, radiusPx, android.graphics.Shader.TileMode.CLAMP)
            .asComposeRenderEffect()
    }

    @Composable
    fun AgslMeshBackdrop(
        primaryColor: Color,
        secondaryColor: Color,
        dimmer: Float,
        modifier: Modifier = Modifier,
    ) {
        val shader = remember { RuntimeShader(MESH_AGSL) }
        var timeSec by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(Unit) {
            val start = System.nanoTime()
            while (true) {
                withFrameNanos { now ->
                    timeSec = (now - start) / 1_000_000_000f
                }
            }
        }

        Canvas(modifier = modifier.fillMaxSize()) {
            shader.setFloatUniform("u_res", size.width, size.height)
            shader.setFloatUniform("u_time", timeSec)
            shader.setFloatUniform("u_c1", primaryColor.red, primaryColor.green, primaryColor.blue)
            shader.setFloatUniform("u_c2", secondaryColor.red, secondaryColor.green, secondaryColor.blue)
            shader.setFloatUniform("u_dim", dimmer.coerceIn(0.15f, 1f))
            drawRect(brush = ShaderBrush(shader))
        }
    }
}
