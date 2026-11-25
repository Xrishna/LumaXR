package com.lumaxr.player.visuals

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES32
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import javax.microedition.khronos.opengles.GL10

class VisualStudioRenderer(
    private val context: Context,
    private val fftProvider: () -> AudioFftFrame,
    private val configProvider: () -> GlowConfigState,
    private val maxFpsProvider: () -> Int,
) : GLSurfaceView.Renderer {

    companion object {
        private const val EGL_GL_COLORSPACE_KHR = 0x309D
        private const val EGL_GL_COLORSPACE_SRGB_KHR = 0x3089

        private const val VERT_SHADER = """#version 320 es
            layout(location = 0) in vec2 a_pos;
            out vec2 v_uv;
            void main() {
                v_uv = a_pos * 0.5 + 0.5;
                gl_Position = vec4(a_pos, 0.0, 1.0);
            }
        """

        fun createSrgbWindowFactory(): GLSurfaceView.EGLWindowSurfaceFactory {
            return object : GLSurfaceView.EGLWindowSurfaceFactory {
                override fun createWindowSurface(
                    egl: EGL10,
                    display: EGLDisplay,
                    config: EGLConfig,
                    nativeWindow: Any,
                ): EGLSurface {
                    val attribs = intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_SRGB_KHR, EGL10.EGL_NONE)
                    val srgb = runCatching { egl.eglCreateWindowSurface(display, config, nativeWindow, attribs) }.getOrNull()
                    if (srgb != null && srgb != EGL10.EGL_NO_SURFACE) return srgb
                    return egl.eglCreateWindowSurface(display, config, nativeWindow, null)
                }

                override fun destroySurface(egl: EGL10, display: EGLDisplay, surface: EGLSurface) {
                    egl.eglDestroySurface(display, surface)
                }
            }
        }
    }

    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        .apply { position(0) }

    private var width = 1280
    private var height = 800
    private var startTimeMs = SystemClock.uptimeMillis()
    private var prevFrameMs = startTimeMs
    private var frameIndex = 0f

    private var progDown = 0
    private var progUp = 0
    private var progWarp = 0
    private var progMilk = 0

    private var sourceTex = 0
    private var sourceTexW = 4
    private var sourceTexH = 4
    private val pendingBitmapRef = AtomicReference<Bitmap?>(null)
    private val accentColorRef = AtomicReference(floatArrayOf(0.36f, 0.84f, 1.0f))

    private val fboIds = IntArray(2)
    private val texIds = IntArray(2)
    private var pingIdx = 0

    fun submitArtworkOrVideoFrame(bitmap: Bitmap, accentRgb: FloatArray? = null) {
        val uploadSafe = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        pendingBitmapRef.set(uploadSafe)
        if (accentRgb != null && accentRgb.size == 3) {
            accentColorRef.set(accentRgb.copyOf())
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES32.glClearColor(0f, 0f, 0f, 1f)
        fboIds.fill(0)
        texIds.fill(0)
        pingIdx = 0

        progDown = compileProgram(loadAsset("shaders/kawarp_downsample.glsl"))
        progUp = compileProgram(loadAsset("shaders/kawarp_kawase.glsl"))
        progWarp = compileProgram(loadAsset("shaders/kawarp_warp_glow.glsl"))
        progMilk = compileProgram(loadAsset("shaders/milkdrop_flow.glsl"))

        val tex = IntArray(1)
        GLES32.glGenTextures(1, tex, 0)
        sourceTex = tex[0]
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, sourceTex)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_CLAMP_TO_EDGE)
        val fallback = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF102038.toInt()) }
        GLUtils.texImage2D(GLES32.GL_TEXTURE_2D, 0, fallback, 0)
        fallback.recycle()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        allocateFloat16PingPong(256, 256)
    }

    private fun allocateFloat16PingPong(w: Int, h: Int) {
        if (fboIds[0] != 0) {
            GLES32.glDeleteFramebuffers(2, fboIds, 0)
            GLES32.glDeleteTextures(2, texIds, 0)
        }
        GLES32.glGenFramebuffers(2, fboIds, 0)
        GLES32.glGenTextures(2, texIds, 0)
        for (i in 0..1) {
            GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, texIds[i])
            GLES32.glTexStorage2D(GLES32.GL_TEXTURE_2D, 1, GLES32.GL_RGBA16F, w, h)
            GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
            GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
            GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_CLAMP_TO_EDGE)
            GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_CLAMP_TO_EDGE)

            GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, fboIds[i])
            GLES32.glFramebufferTexture2D(
                GLES32.GL_FRAMEBUFFER,
                GLES32.GL_COLOR_ATTACHMENT0,
                GLES32.GL_TEXTURE_2D,
                texIds[i],
                0,
            )
            GLES32.glViewport(0, 0, w, h)
            GLES32.glClearColor(0f, 0f, 0f, 1f)
            GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)
        }
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val maxFps = maxFpsProvider()
        val nowMs = SystemClock.uptimeMillis()
        val dtSec = ((nowMs - prevFrameMs).coerceIn(1L, 100L)) / 1000.0f
        prevFrameMs = nowMs

        if (maxFps <= 0) {
            GLES32.glViewport(0, 0, width, height)
            GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)
            return
        }

        val cfg = configProvider().clamped()
        if (cfg.visualizerMode == VisualizerMode.MINIMAL_STATIC) {
            GLES32.glViewport(0, 0, width, height)
            GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)
            return
        }

        pendingBitmapRef.getAndSet(null)?.let { bmp ->
            if (!bmp.isRecycled) {
                sourceTexW = bmp.width.coerceAtLeast(1)
                sourceTexH = bmp.height.coerceAtLeast(1)
                GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, sourceTex)
                GLUtils.texImage2D(GLES32.GL_TEXTURE_2D, 0, bmp, 0)
            }
        }

        val fft = fftProvider()
        val accentColor = accentColorRef.get()
        val elapsedSec = ((nowMs - startTimeMs) % 3_600_000L) / 1000.0f
        frameIndex = (frameIndex + 1f) % 1024f

        val compositeTexId: Int
        if (cfg.visualizerMode == VisualizerMode.MILKDROP_PLASMA) {
            val writeIdx = 1 - pingIdx
            GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, fboIds[writeIdx])
            GLES32.glViewport(0, 0, 256, 256)
            GLES32.glUseProgram(progMilk)
            GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
            GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, texIds[pingIdx])
            GLES32.glUniform1i(GLES32.glGetUniformLocation(progMilk, "u_prev_tex"), 0)
            GLES32.glUniform1f(GLES32.glGetUniformLocation(progMilk, "u_time"), elapsedSec)
            GLES32.glUniform1f(GLES32.glGetUniformLocation(progMilk, "u_dt"), dtSec)
            GLES32.glUniform1i(GLES32.glGetUniformLocation(progMilk, "u_preset"), cfg.milkDropPreset.id)
            GLES32.glUniform1f(GLES32.glGetUniformLocation(progMilk, "u_bass"), fft.bass)
            GLES32.glUniform1f(GLES32.glGetUniformLocation(progMilk, "u_mid"), fft.mid)
            GLES32.glUniform1f(GLES32.glGetUniformLocation(progMilk, "u_treble"), fft.treble)
            GLES32.glUniform1f(GLES32.glGetUniformLocation(progMilk, "u_react"), cfg.beatReactivity)
            GLES32.glUniform3fv(GLES32.glGetUniformLocation(progMilk, "u_accent"), 1, accentColor, 0)
            GLES32.glUniform2f(GLES32.glGetUniformLocation(progMilk, "u_resolution"), width.toFloat(), height.toFloat())
            drawQuad()
            pingIdx = writeIdx
            compositeTexId = texIds[pingIdx]
        } else {
            var inputTex = sourceTex
            var curW = sourceTexW
            var curH = sourceTexH
            val passes = cfg.kawaseBlurPasses
            var lastWriteTex = texIds[0]
            for (p in 0 until passes) {
                val writeIdx = pingIdx
                val prog = if (p < passes / 2) progDown else progUp
                GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, fboIds[writeIdx])
                GLES32.glViewport(0, 0, 256, 256)
                GLES32.glUseProgram(prog)
                GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
                GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, inputTex)
                GLES32.glUniform1i(GLES32.glGetUniformLocation(prog, "u_tex"), 0)
                GLES32.glUniform2f(GLES32.glGetUniformLocation(prog, "u_half_pixel"), 0.5f / curW, 0.5f / curH)
                GLES32.glUniform1f(GLES32.glGetUniformLocation(prog, "u_offset"), 1f + p * 0.65f)
                drawQuad()
                lastWriteTex = texIds[writeIdx]
                inputTex = lastWriteTex
                curW = 256
                curH = 256
                pingIdx = 1 - pingIdx
            }
            compositeTexId = lastWriteTex
        }

        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)
        GLES32.glViewport(0, 0, width, height)
        GLES32.glUseProgram(progWarp)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, compositeTexId)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(progWarp, "u_tex"), 0)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(progWarp, "u_time"), elapsedSec)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(progWarp, "u_frame_index"), frameIndex)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(progWarp, "u_warp_speed"), cfg.warpSpeed)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(progWarp, "u_warp_scale"), cfg.warpDistortion)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(progWarp, "u_saturation"), cfg.colorSaturation)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(progWarp, "u_dimmer"), cfg.backdropDimmer)
        val r = ((cfg.darkTintColorArgb ushr 16) and 0xFF) / 255f
        val g = ((cfg.darkTintColorArgb ushr 8) and 0xFF) / 255f
        val b = (cfg.darkTintColorArgb and 0xFF) / 255f
        GLES32.glUniform3f(GLES32.glGetUniformLocation(progWarp, "u_dark_tint"), r, g, b)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(progWarp, "u_dark_tint_intensity"), cfg.darkTintIntensity)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(progWarp, "u_bass"), fft.bass)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(progWarp, "u_mid"), fft.mid)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(progWarp, "u_treble"), fft.treble)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(progWarp, "u_beat_reactivity"), cfg.beatReactivity)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(progWarp, "u_glow_type"), cfg.glowType.id)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(progWarp, "u_glow_intensity"), cfg.glowIntensity)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(progWarp, "u_glow_radius"), cfg.glowRadius)
        GLES32.glUniform3fv(GLES32.glGetUniformLocation(progWarp, "u_accent_color"), 1, accentColor, 0)
        GLES32.glUniform2f(GLES32.glGetUniformLocation(progWarp, "u_resolution"), width.toFloat(), height.toFloat())
        drawQuad()
    }

    private fun drawQuad() {
        GLES32.glEnableVertexAttribArray(0)
        GLES32.glVertexAttribPointer(0, 2, GLES32.GL_FLOAT, false, 0, quadBuffer)
        GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, 4)
        GLES32.glDisableVertexAttribArray(0)
    }

    private fun loadAsset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    private fun compileProgram(fragSrc: String): Int {
        val vs = compileShader(GLES32.GL_VERTEX_SHADER, VERT_SHADER)
        val fs = compileShader(GLES32.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES32.glCreateProgram()
        GLES32.glAttachShader(prog, vs)
        GLES32.glAttachShader(prog, fs)
        GLES32.glLinkProgram(prog)
        GLES32.glDeleteShader(vs)
        GLES32.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES32.glCreateShader(type)
        GLES32.glShaderSource(shader, src)
        GLES32.glCompileShader(shader)
        return shader
    }
}
