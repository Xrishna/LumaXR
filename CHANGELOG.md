# Changelog

## [1.0.0] - Initial Unified LumaXR Architecture (Phases A, B, C, D)

### Added
- Phase A: `libmpv + libplacebo` (`vo=gpu-next`) + Hard-Gated `Media3 1.11.1` Dolby HAL + Sony BRAVIA XR backlight physics (`RuanBacklightSpline`, `RuanBacklightController`, `xr_lcd_master.glsl`, `hdr_toys_pipeline.glsl`).
- Phase B: Single-root `SharedTransitionLayout` + `NavDisplay` with strict `30f > 20f > 10f > 0f` overlay Z-order and two-phase `PredictiveBackContainer`.
- Phase C: Audiophile Hi-Res & Dolby Music Player (`LumaMusicLibraryService`, `UsbBitPerfectManager`, `LoudnessAndReplayGainManager`, `SpatialAudioManager`, `AudioDspController`, `AutoEqParser`, `AudioDeviceProfileRouter`, `CueSheetParser`, `MusicGlanceWidget`).
- Phase D: Native 120 Hz Kawarp `GL_RGBA16F` Visualizer Studio + MilkDrop feedback shaders + Apple Music `AmLyricsView` + `RangeChunkCache` SMB/WebDAV/Jellyfin/Emby/Plex + `ScrobbleManager` + `PipController`.
- CI: GitHub Actions `arm64-v8a` APK build with dynamic package name rotation, 16 KB ELF alignment verification, and automatic GitHub Releases publishing.
