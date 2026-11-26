---
name: lumaxr-video-crash-hunter
description: Mandatory crash-hunting, logcat triage, and pre-flight verification skill for LumaXR Android video/music playback (libmpv, Media3, Vulkan 1.1, GLES 3.2, Android 16 API 36). Invoke whenever playback crashes, shows black/green screen, drops frames, or before marking any playback task complete.
---

# LumaXR Video & Audio Crash Hunter

## Non-Negotiable Pre-Flight Checks (Run Before Claiming "Done")
1. **Verify Real `mpv-android-lib` Signatures:** Inspect the resolved `.aar`
   classes (`is.xyz.mpv.MPV` / actual package in `mpv-android-lib:0.2.2`)
   via `javap` or Gradle cache before writing any JNI call. NEVER guess method
   names or static initializers.
2. **Verify `content://` URI Handling:** Android SAF URIs MUST be converted
   via `contentResolver.openFileDescriptor(uri, "r")` -> `fd://${pfd.detachFd()}`
   (or real path when `MANAGE_EXTERNAL_STORAGE` is granted) and closed cleanly
   on release. Never pass raw `content://` directly to `libmpv` `loadfile`.
3. **Verify Surface Lifecycle Ordering:**
   - Initialize `nativeCreate()` + `nativeInit()` + `initSession()` BEFORE `attachSurface()`.
   - Call `attachSurface(holder.surface)` ONLY inside `surfaceCreated()`.
   - Call `setPropertyString("vo", "null")` + `detachSurface()` inside
     `surfaceDestroyed()` BEFORE the surface is released.
   - Guard all MPV calls behind a single serialized thread/mutex to prevent
     `SIGSEGV` on rotation or quick back-press.
4. **Verify Vulkan 1.1 & Shader Compilation Safety:**
   - Check GLSL shader syntax (no WebGL-only qualifiers inside mpv `.glsl`
     hooks; valid `//!HOOK`, `//!BIND`, `//!DESC` headers).
   - Provide automatic fallback from `gpu-api=vulkan` to `gpu-api=opengl` if
     `vo=gpu-next` fails to initialize on Adreno 710.
   - Always pass `target-colorspace-hint=no` by default on `ruan` (sRGB HWC).
5. **Verify Android 16 (API 36) Packaging & Permissions:**
   - `jniLibs.useLegacyPackaging = false`
   - Run ELF alignment check (`readelf -l <lib>.so | grep LOAD`) verifying
     `0x4000` (16 KB) alignment for every `arm64-v8a` `.so`.
   - Foreground service `LumaMusicLibraryService` declares
     `android:foregroundServiceType="mediaPlayback"`.

## Crash Investigation Protocol (When Video Crashes on Device)
1. Capture exact crash trace (never guess):
   `adb logcat -d -b crash -b main *:E | grep -E "FATAL|AndroidRuntime|mpv|ffmpeg|libplacebo|MediaCodec|ExoPlayer|SIGSEGV|SIGABRT|UnsatisfiedLink"`
2. Classify the failure strictly into one root cause:
   - [A] `UnsatisfiedLinkError` / ABI mismatch -> check `abiFilters` & `.aar`.
   - [B] `SIGSEGV` / `SIGABRT` in `libmpv.so` -> check thread safety,
     `attachSurface`/`detachSurface` order, or bad `mpv.conf` option.
   - [C] `MediaCodec.CodecException` -> check `hwdec=mediacodec-copy` fallback
     or `c2.dolby.decoder.hevc` profile mismatch.
   - [D] Black screen with audio playing -> check `SurfaceView` Z-order,
     `vo=gpu-next` Vulkan 1.1 fallback to `opengl`, or broken custom shader.
   - [E] `SecurityException` / `FileNotFoundException` -> fix SAF `fd://` bridge.
3. Write a unit/instrumentation test reproducing the exact failure condition,
   apply the minimal root-cause fix, and run `testDebugUnitTest assembleRelease`
   with zero warnings/errors before reporting success.
