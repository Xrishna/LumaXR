# Changelog

## [1.0.8] - Deep Runtime Fix & Navigation 3 / Visualizer / SAF Hardening

### Fixed
- **MPV JNI SIGABRT Crash:** Removed duplicate `nativeCreate()`/`nativeInit()`/`initSession()` calls in `MpvPlaceboEngine` and `MpvAudioEngine`, and removed duplicate `destroySession()`/`nativeDestroy()` before `close()`.
- **Surface Lifecycle & Black Screen Races:** Added `force-window="no"` -> `surfaceCreated` (`attachSurface` + `vo=plan.vo` + `force-window="yes"`) -> `surfaceDestroyed` (`vo="null"` + `force-window="no"` + `detachSurface`) gating matching `mpvKt`.
- **25 Hz UI/Decoder Stall:** Replaced synchronous lock-holding `mpv.grabThumbnail(64)` with async `PixelCopy.request(surfaceView, ...)` on a dedicated `HandlerThread` + observed `sub-text`.
- **libplacebo Shader Recompilation & Uniforms:** Switched `xr_lcd_master.glsl` and `hdr_toys_pipeline.glsl` parameters to `//!TYPE DYNAMIC` and bare `glsl-shader-opts` names (`MpvShaderOptsFormatter`).
- **Scoped Storage `content://` Playback:** Added `MpvUriResolver` (`Utils.findRealPath` + `"fd://$fd"`) across video playback, external subtitles, MPV audio, and `MediaFormatProber` / `MusicScannerRepository`.
- **Vulkan Fallback Detection:** Added `MPV.LogObserver` + `END_FILE` error detection for automatic Adreno 710 GLES 3.2 fallback (`MpvPlaceboEngine`).
- **Audio Visualizer & MediaService:** Added permission-free ExoPlayer `TeeAudioProcessor` PCM tap (`Media3MusicEngine` -> 256-pt Radix-2 FFT `AudioFftAnalyzer`), active `MediaController` binding to `LumaMusicLibraryService`, vibrant ambient gradient art fallback (`VisualStudioRenderer`), and non-polling `MusicGlanceWidget` snapshots.
- **Navigation 3 & Compose 1.10+ Compliance:** Upgraded to `rememberNavBackStack` + `NavDisplay` `predictivePopTransitionSpec` (removing conflicting double `PredictiveBackHandler`), `collectAsStateWithLifecycle()`, ProGuard keep rules for serialized `LumaRoute`, `OpenDocument` file pickers in Cinema/Music libraries, and swipe brightness/volume/seek/track-sheet controls in `VideoPlayerScreen`.

## [1.0.0] - Initial Unified LumaXR Architecture (Phases A, B, C, D)

### Added
- Phase A: `libmpv + libplacebo` (`vo=gpu-next`) + Hard-Gated `Media3 1.11.1` Dolby HAL + Sony BRAVIA XR backlight physics (`RuanBacklightSpline`, `RuanBacklightController`, `xr_lcd_master.glsl`, `hdr_toys_pipeline.glsl`).
- Phase B: Single-root `SharedTransitionLayout` + `NavDisplay` with strict `30f > 20f > 10f > 0f` overlay Z-order and two-phase `PredictiveBackContainer`.
- Phase C: Audiophile Hi-Res & Dolby Music Player (`LumaMusicLibraryService`, `UsbBitPerfectManager`, `LoudnessAndReplayGainManager`, `SpatialAudioManager`, `AudioDspController`, `AutoEqParser`, `AudioDeviceProfileRouter`, `CueSheetParser`, `MusicGlanceWidget`).
- Phase D: Native 120 Hz Kawarp `GL_RGBA16F` Visualizer Studio + MilkDrop feedback shaders + Apple Music `AmLyricsView` + `RangeChunkCache` SMB/WebDAV/Jellyfin/Emby/Plex + `ScrobbleManager` + `PipController`.
- CI: GitHub Actions `arm64-v8a` APK build with dynamic package name rotation, 16 KB ELF alignment verification, and automatic GitHub Releases publishing.
