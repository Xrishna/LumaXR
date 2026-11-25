package com.lumaxr.player.video

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.lumaxr.player.hardware.BacklightXrState
import com.lumaxr.player.hardware.FrameLuminanceMetrics
import is.xyz.mpv.MPV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

data class MpvTelemetry(
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val paused: Boolean = false,
    val videoCodec: String = "",
    val hwdecCurrent: String = "",
    val delayedFrames: Long = 0L,
    val droppedFrames: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val fps: Double = 0.0,
    val colormatrix: String = "",
    val gamma: String = "",
    val libdoviDetected: Boolean = true,
)

class MpvPlaceboEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onVulkanInitFailed: () -> Unit,
    private val onFrameMetered: (FrameLuminanceMetrics) -> Unit,
    private val onAmbientFrameSampled: (Bitmap) -> Unit = {},
) : MPV.EventObserver, SurfaceHolder.Callback {

    private val configDir = File(context.filesDir, "mpv_config").apply { mkdirs() }
    private val cacheDir = File(context.cacheDir, "mpv_shader_cache").apply { mkdirs() }

    private val lock = Any()
    private var mpv: MPV? = null
    private var attachedSurfaceView: SurfaceView? = null
    private var meteringJob: Job? = null
    private var currentPlan: MpvConfigPlan? = null
    private var abLoopA: Double? = null
    private var abLoopB: Double? = null
    private var fileLoadedSuccessfully = false

    private val _telemetry = MutableStateFlow(MpvTelemetry())
    val telemetry: StateFlow<MpvTelemetry> = _telemetry.asStateFlow()

    @Volatile
    private var isDestroyed = false

    fun prepareShadersToConfigDir() {
        val shadersOut = File(configDir, "shaders").apply { mkdirs() }
        listOf("xr_lcd_master.glsl", "hdr_toys_pipeline.glsl").forEach { name ->
            runCatching {
                context.assets.open("shaders/$name").use { input ->
                    FileOutputStream(File(shadersOut, name)).use { out -> input.copyTo(out) }
                }
            }
        }
    }

    fun attachAndStart(surfaceView: SurfaceView, uri: String, plan: MpvConfigPlan) {
        destroy()
        isDestroyed = false
        fileLoadedSuccessfully = false
        abLoopA = null
        abLoopB = null
        _telemetry.value = MpvTelemetry()
        currentPlan = plan
        prepareShadersToConfigDir()

        val instance = MPV(context, configDir.absolutePath, cacheDir.absolutePath)
        try {
            instance.nativeCreate(context)
            applyPreInitOptions(instance, plan)
            instance.nativeInit()
            instance.initSession()
            instance.addObserver(this)
            observeCoreProperties(instance)
        } catch (_: Throwable) {
            runCatching { instance.nativeDestroy(); instance.close() }
            if (plan.gpuApi == "vulkan") onVulkanInitFailed()
            return
        }

        synchronized(lock) {
            mpv = instance
            attachedSurfaceView = surfaceView
        }

        surfaceView.holder.addCallback(this)
        val surface = surfaceView.holder.surface
        if (surface != null && surface.isValid) {
            instance.attachSurface(surface)
            val w = surfaceView.width
            val h = surfaceView.height
            if (w > 0 && h > 0) {
                instance.setPropertyString("android-surface-size", "${w}x$h")
            }
        }

        instance.command("loadfile", uri)
        startMeteringLoop()
    }

    private fun applyPreInitOptions(instance: MPV, plan: MpvConfigPlan) {
        instance.setOptionString("vo", plan.vo)
        instance.setOptionString("gpu-api", plan.gpuApi)
        instance.setOptionString("gpu-context", plan.gpuContext)
        instance.setOptionString("gpu-shader-cache", "yes")
        instance.setOptionString("gpu-shader-cache-dir", cacheDir.absolutePath)
        instance.setOptionString("hwdec", plan.hwdec)
        instance.setOptionString("hwdec-codecs", "h264,hevc,vp8,vp9")
        instance.setOptionString("video-output-levels", "full")
        instance.setOptionString("target-colorspace-hint", plan.targetColorspaceHint)
        instance.setOptionString("target-prim", plan.targetPrim)
        instance.setOptionString("target-trc", plan.targetTrc)
        instance.setOptionString("target-contrast", plan.targetContrast.toString())
        instance.setOptionString("target-peak", plan.targetPeakNits.toString())
        instance.setOptionString("gamut-mapping-mode", "perceptual")
        instance.setOptionString("dither-depth", "8")
        instance.setOptionString("dither", "fruit")

        instance.setOptionString("demuxer-max-bytes", "150MiB")
        instance.setOptionString("demuxer-readahead-secs", "20")
        instance.setOptionString("cache", "yes")

        instance.setOptionString("tone-mapping", plan.toneMapping)
        instance.setOptionString("hdr-compute-peak", if (plan.hdrComputePeak) "yes" else "no")
        instance.setOptionString("hdr-peak-percentile", String.format(Locale.US, "%.3f", plan.hdrPeakPercentile))
        instance.setOptionString("hdr-contrast-recovery", String.format(Locale.US, "%.2f", plan.hdrContrastRecovery))
        instance.setOptionString("scale", plan.scaler)
        instance.setOptionString("dscale", plan.downscaler)
        instance.setOptionString("cscale", plan.cscale)
        instance.setOptionString("correct-downscaling", if (plan.correctDownscaling) "yes" else "no")
        instance.setOptionString("linear-downscaling", if (plan.linearDownscaling) "yes" else "no")
        instance.setOptionString("sharpen", String.format(Locale.US, "%.2f", plan.sharpen))

        instance.setOptionString("deband", if (plan.debandEnabled) "yes" else "no")
        if (plan.debandEnabled) {
            instance.setOptionString("deband-iterations", plan.debandIterations.toString())
            instance.setOptionString("deband-threshold", plan.debandThreshold.toString())
            instance.setOptionString("deband-range", plan.debandRange.toString())
            instance.setOptionString("deband-grain", plan.debandGrain.toString())
        }

        instance.setOptionString("blend-subtitles", "video")
        instance.setOptionString("sub-hdr-peak", plan.subHdrPeakNits.toString())
        instance.setOptionString("sub-auto", "fuzzy")
        instance.setOptionString("keep-open", "yes")
        if (plan.stripDolbyVisionFallback) {
            instance.setOptionString("vf", "format:dolbyvision=no")
        }

        val shaders = mutableListOf<String>()
        if (plan.hdrToysMapper != HdrToysToneMapper.OFF) {
            shaders += File(configDir, "shaders/hdr_toys_pipeline.glsl").absolutePath
        }
        shaders += File(configDir, "shaders/xr_lcd_master.glsl").absolutePath
        instance.setOptionString("glsl-shaders", shaders.joinToString(":"))
        instance.setOptionString(
            "glsl-shader-opts",
            "hdr_toys_pipeline/u_tm_mode=${plan.hdrToysMapper.id},hdr_toys_pipeline/u_dst_peak=${plan.targetPeakNits}.0",
        )
    }

    private fun observeCoreProperties(instance: MPV) {
        val dbl = MPV.mpvFormat.MPV_FORMAT_DOUBLE
        val flag = MPV.mpvFormat.MPV_FORMAT_FLAG
        val str = MPV.mpvFormat.MPV_FORMAT_STRING
        val i64 = MPV.mpvFormat.MPV_FORMAT_INT64
        instance.observeProperty("time-pos", dbl)
        instance.observeProperty("duration", dbl)
        instance.observeProperty("pause", flag)
        instance.observeProperty("video-codec", str)
        instance.observeProperty("hwdec-current", str)
        instance.observeProperty("vo-delayed-frame-count", i64)
        instance.observeProperty("frame-drop-count", i64)
        instance.observeProperty("video-params/w", i64)
        instance.observeProperty("video-params/h", i64)
        instance.observeProperty("container-fps", dbl)
        instance.observeProperty("video-params/colormatrix", str)
        instance.observeProperty("video-params/gamma", str)
    }

    fun applyLiveBacklightAndWipeUniforms(
        backlight: BacklightXrState,
        splitWipe: Float,
        bypassOriginal: Boolean,
        bilateralRadius: Int,
        aspectState: SmartAspectState,
    ) {
        synchronized(lock) {
            val m = mpv ?: return
            if (!m.isInitialized) return
            val plan = currentPlan
            val isPqOrHlgOut = plan?.targetTrc == "pq"
            val opts = buildString {
                append(String.format(Locale.US, "xr_lcd_master/u_inv_gain=%.4f,", backlight.inverseShaderGain))
                append(String.format(Locale.US, "xr_lcd_master/u_local_contrast=%.4f,", backlight.localContrastStrength))
                append("xr_lcd_master/u_bilateral_radius=$bilateralRadius,")
                append(String.format(Locale.US, "xr_lcd_master/u_letterbox_top=%.4f,", aspectState.letterboxTopNorm))
                append(String.format(Locale.US, "xr_lcd_master/u_letterbox_bottom=%.4f,", aspectState.letterboxBottomNorm))
                append(String.format(Locale.US, "xr_lcd_master/u_split_wipe=%.4f,", splitWipe))
                append("xr_lcd_master/u_bypass_original=${if (bypassOriginal) "1.0" else "0.0"},")
                append("xr_lcd_master/u_is_linear_bypass=${if (isPqOrHlgOut) "1.0" else "0.0"}")
                if (plan != null && plan.hdrToysMapper != HdrToysToneMapper.OFF) {
                    append(",hdr_toys_pipeline/u_tm_mode=${plan.hdrToysMapper.id}")
                    append(",hdr_toys_pipeline/u_dst_peak=${plan.targetPeakNits}.0")
                }
            }
            runCatching { m.setPropertyString("glsl-shader-opts", opts) }
        }
    }

    fun applyRuntimePlanUpdate(plan: MpvConfigPlan) {
        synchronized(lock) {
            val m = mpv ?: return
            if (!m.isInitialized) return
            currentPlan = plan
            runCatching {
                m.setPropertyString("target-peak", plan.targetPeakNits.toString())
                m.setPropertyString("target-prim", plan.targetPrim)
                m.setPropertyString("target-trc", plan.targetTrc)
                m.setPropertyString("tone-mapping", plan.toneMapping)
                m.setPropertyString("hdr-compute-peak", if (plan.hdrComputePeak) "yes" else "no")
                m.setPropertyString("hdr-contrast-recovery", String.format(Locale.US, "%.2f", plan.hdrContrastRecovery))
                m.setPropertyString("scale", plan.scaler)
                m.setPropertyString("deband", if (plan.debandEnabled) "yes" else "no")
                m.setPropertyString("sharpen", String.format(Locale.US, "%.2f", plan.sharpen))
                m.setPropertyString("sub-hdr-peak", plan.subHdrPeakNits.toString())
            }
        }
    }

    fun setPaused(paused: Boolean) {
        synchronized(lock) { mpv?.takeIf { it.isInitialized }?.setPropertyBoolean("pause", paused) }
    }

    fun seekToSeconds(seconds: Double) {
        synchronized(lock) { mpv?.takeIf { it.isInitialized }?.command("seek", seconds.coerceAtLeast(0.0).toString(), "absolute") }
    }

    fun seekRelative(deltaSeconds: Double) {
        synchronized(lock) { mpv?.takeIf { it.isInitialized }?.command("seek", deltaSeconds.toString(), "relative") }
    }

    fun frameStepForward() {
        synchronized(lock) { mpv?.takeIf { it.isInitialized }?.command("frame-step") }
    }

    fun frameStepBackward() {
        synchronized(lock) { mpv?.takeIf { it.isInitialized }?.command("frame-back-step") }
    }

    fun setAudioDelayMs(offsetMs: Int) {
        synchronized(lock) { mpv?.takeIf { it.isInitialized }?.setPropertyDouble("audio-delay", offsetMs / 1000.0) }
    }

    fun setSpeed(speed: Double) {
        synchronized(lock) { mpv?.takeIf { it.isInitialized }?.setPropertyDouble("speed", speed.coerceIn(0.25, 4.0)) }
    }

    fun setSubPos(pos: Int) {
        synchronized(lock) { mpv?.takeIf { it.isInitialized }?.setPropertyInt("sub-pos", pos.coerceIn(0, 100)) }
    }

    fun setVideoZoom(zoomLog2: Double) {
        synchronized(lock) { mpv?.takeIf { it.isInitialized }?.setPropertyDouble("video-zoom", zoomLog2) }
    }

    fun selectTrack(type: String, id: Int) {
        synchronized(lock) { mpv?.takeIf { it.isInitialized }?.setPropertyInt(type, id) }
    }

    fun addExternalSubtitle(pathOrUrl: String) {
        synchronized(lock) { mpv?.takeIf { it.isInitialized }?.command("sub-add", pathOrUrl, "select") }
    }

    fun setAbLoop(aSec: Double?, bSec: Double?) {
        synchronized(lock) {
            abLoopA = aSec
            abLoopB = bSec
            val m = mpv?.takeIf { it.isInitialized } ?: return
            m.setPropertyString("ab-loop-a", aSec?.toString() ?: "no")
            m.setPropertyString("ab-loop-b", bSec?.toString() ?: "no")
        }
    }

    fun takeScreenshot(outFile: File, withSubtitles: Boolean): Boolean {
        synchronized(lock) {
            val m = mpv ?: return false
            if (!m.isInitialized) return false
            return runCatching {
                m.command("screenshot-to-file", outFile.absolutePath, if (withSubtitles) "subtitles" else "video")
                true
            }.getOrDefault(false)
        }
    }

    fun setDolbyVisionFallbackStrip(strip: Boolean) {
        synchronized(lock) {
            val m = mpv ?: return
            if (!m.isInitialized) return
            m.setPropertyString("vf", if (strip) "format:dolbyvision=no" else "")
        }
    }

    private fun startMeteringLoop() {
        meteringJob?.cancel()
        meteringJob = scope.launch(Dispatchers.Default) {
            while (isActive && !isDestroyed) {
                var thumb: Bitmap? = null
                var subVisible = false
                synchronized(lock) {
                    val m = mpv
                    if (m != null && m.isInitialized && !_telemetry.value.paused) {
                        thumb = runCatching { m.grabThumbnail(64) }.getOrNull()
                        subVisible = runCatching { m.getPropertyString("sub-text") }.getOrNull()?.isNotBlank() == true
                    }
                }
                val bmp = thumb
                if (bmp != null) {
                    val metrics = analyzeThumbnailLuma(bmp, subVisible)
                    onFrameMetered(metrics)
                    onAmbientFrameSampled(bmp)
                }
                delay(40L)
            }
        }
    }

    private fun analyzeThumbnailLuma(bmp: Bitmap, subtitlesVisible: Boolean): FrameLuminanceMetrics {
        val w = bmp.width
        val h = bmp.height
        val yLimit = if (subtitlesVisible) (h * 0.82f).toInt().coerceAtLeast(1) else h
        val pixels = IntArray(w * yLimit)
        bmp.getPixels(pixels, 0, w, 0, 0, w, yLimit)

        val hist = IntArray(64)
        var sumY = 0.0
        var darkCount = 0
        for (px in pixels) {
            val r = ((px ushr 16) and 0xFF) / 255.0f
            val g = ((px ushr 8) and 0xFF) / 255.0f
            val b = (px and 0xFF) / 255.0f
            val y = (0.2126f * r * r + 0.7152f * g * g + 0.0722f * b * b).coerceIn(0f, 1f)
            sumY += y
            if (y < 0.05f) darkCount++
            hist[(y * 63f).toInt().coerceIn(0, 63)]++
        }
        val n = pixels.size.coerceAtLeast(1)
        val p99Threshold = (n * 0.99f).toInt()
        var acc = 0
        var p99Bin = 63
        for (b in 0..63) {
            acc += hist[b]
            if (acc >= p99Threshold) {
                p99Bin = b
                break
            }
        }
        return FrameLuminanceMetrics(
            apl = (sumY / n).toFloat(),
            p99Luma = p99Bin / 63f,
            darkPixelRatio = darkCount.toFloat() / n,
            subtitlesVisible = subtitlesVisible,
        )
    }

    override fun eventProperty(property: String) {}

    override fun eventProperty(property: String, value: Long) {
        val cur = _telemetry.value
        _telemetry.value = when (property) {
            "vo-delayed-frame-count" -> cur.copy(delayedFrames = value)
            "frame-drop-count" -> cur.copy(droppedFrames = value)
            "video-params/w" -> cur.copy(videoWidth = value.toInt())
            "video-params/h" -> cur.copy(videoHeight = value.toInt())
            else -> cur
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        if (property == "pause") _telemetry.value = _telemetry.value.copy(paused = value)
    }

    override fun eventProperty(property: String, value: String) {
        val cur = _telemetry.value
        _telemetry.value = when (property) {
            "video-codec" -> cur.copy(videoCodec = value)
            "hwdec-current" -> cur.copy(hwdecCurrent = value)
            "video-params/colormatrix" -> cur.copy(colormatrix = value)
            "video-params/gamma" -> cur.copy(gamma = value)
            else -> cur
        }
    }

    override fun eventProperty(property: String, value: Double) {
        val cur = _telemetry.value
        _telemetry.value = when (property) {
            "time-pos" -> cur.copy(positionSec = value)
            "duration" -> cur.copy(durationSec = value)
            "container-fps" -> cur.copy(fps = value)
            else -> cur
        }
    }

    override fun eventProperty(property: String, value: is.xyz.mpv.MPVNode) {}

    override fun event(eventId: Int, data: is.xyz.mpv.MPVNode) {
        if (eventId == MPV.mpvEvent.MPV_EVENT_FILE_LOADED) {
            fileLoadedSuccessfully = true
        } else if (eventId == MPV.mpvEvent.MPV_EVENT_SHUTDOWN &&
            currentPlan?.gpuApi == "vulkan" &&
            !fileLoadedSuccessfully &&
            !isDestroyed
        ) {
            onVulkanInitFailed()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val surface: Surface = holder.surface
        if (surface.isValid) {
            synchronized(lock) { mpv?.attachSurface(surface) }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width > 0 && height > 0) {
            synchronized(lock) { mpv?.setPropertyString("android-surface-size", "${width}x$height") }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(lock) { mpv?.detachSurface() }
    }

    fun destroy() {
        isDestroyed = true
        meteringJob?.cancel()
        meteringJob = null
        synchronized(lock) {
            attachedSurfaceView?.holder?.removeCallback(this)
            attachedSurfaceView = null
            mpv?.let { instance ->
                runCatching {
                    instance.removeObserver(this)
                    instance.detachSurface()
                    instance.destroySession()
                    instance.nativeDestroy()
                    instance.close()
                }
            }
            mpv = null
        }
    }
}
