# LumaXR — HDR / Dolby Vision Video & Audiophile Music Player

**LumaXR** is a production-grade Android 16 (API 36) video and audiophile music player architected specifically for **2560 × 1600 120 Hz IPS LCD tablets** (calibrated to **Xiaomi Redmi Pad Pro 5G / `24074RPD2I` / `ruan_in`**, Qualcomm `SM7435`, Adreno 710 Vulkan 1.1 / GLES 3.2).

---

## 1. Engineering Spec & Hardware Telemetry

### Verified Hardware Profile (`ruan_in`)
- **SoC & GPU:** Qualcomm `SM7435` (`parrot`), Adreno 710 — **Vulkan 1.1** (`4198400`, compute supported) with automatic persistent fallback to **OpenGL ES 3.2** (`GL_EXT_color_buffer_half_float`, `GL_EXT_color_buffer_float`, `GL_EXT_texture_norm16`).
- **Panel:** `2560 × 1600` (16:10), `320 dpi`, `19dp` (`38px`) corner radius, `1200:1` native IPS contrast, discrete refresh rates `[120, 90, 60, 50, 48, 30] Hz` (`hasArrSupport = false`).
- **Verified Codecs:** `c2.qti.hevc.decoder` (HW HEVC 10-bit), `c2.dolby.decoder.hevc` (HW Dolby Vision), `c2.dolby.eac3.decoder` (AC3 / EAC3 / EAC3-JOC Atmos 16ch), `c2.dolby.ac4.decoder` (AC-4), `c2.android.av1-dav1d.decoder` (SW AV1).

### Hard-Gate Routing Table (`DisplayCapabilityGuard`)
| Condition | Engine Selected | Reason |
|---|---|---|
| **DV Profile 7** (UHD Remux MEL/FEL) | `libmpv + libplacebo` (`vo=gpu-next`) | Media3 blocked unconditionally |
| `!display.isWideColorGamut` or `HDR_TYPE_DOLBY_VISION (1)` without `HDR_TYPE_HDR10 (2)` | `libmpv + libplacebo` (`vo=gpu-next`) | Catches `ruan` HWC `desiredRatio(10.00)` elevated-black quirk |
| Any MPV filter, `hdr-toys`, or `Cinema Mastering XR` preset active | `libmpv + libplacebo` (`vo=gpu-next`) | Zero duplicate GLSL parity layer in Media3 |
| **DV Profile 5** when `libdovi` is absent | `Media3 1.11.1` (`c2.dolby.decoder.hevc`) | Prevents IPTPQc2 green/magenta rendering |
| User HUD Manual Override ("Native Dolby HAL") on non-P7 streams | `Media3 1.11.1` Dolby HAL | Battery-saving Dolby Vision + `c2.dolby.eac3` / `ac4` A/B testing |

### `RuanBacklightSpline` & Ramp Physics Estimator
Measured piecewise backlight spline:
- Normal indoor/outdoor: `0.001709819 ↔ 2.0 nits` to `0.49975574 ↔ 500.0 nits`
- Sunlight-gated HBM (`minLux = 6001.0`): `0.49975574 ↔ 500.0 nits` to `1.0 ↔ 600.0 nits`
- **Ambient Auto-Exposure `target-peak`:** Smooth S-curve mapping indoor range (`30–245.6 nits`) into **`180–280 nits`**, clamped to **`[203, 300]` nits** (manual default `240 nits`).
- **Ramp Physics Sync (`RuanBacklightController`):** 20–30 Hz subtitle-masked metering (excluding bottom 18% band), `±3%` hysteresis deadband, asymmetric filter ($\tau_{\text{up}} \approx 110\text{ ms}$, $\tau_{\text{down}} \approx 1600\text{ ms}$), integrated through HyperOS ramp rates (`0.6021006/s` fast, `0.4816805/s` slow) + `~24 ms` latency into $L_{\text{est}}(t)$ and inverse gain $G(t) = 1.0 / D_{\text{actual}}(t)$ with **C1-continuous soft-shoulder roll-off above `0.85`** in `xr_lcd_master.glsl`.

### Source-Adaptive Cinema Mastering XR Rules (`PlaybackConfigAdvisor`)
- **4K UHD Remux / High-Bitrate 10-Bit (`>= 2160p` or `> 20 Mbps`):** `sharpen=0`, `deband=no` (zero 35mm grain destruction), `dscale=mitchell`, `spline` / `bt.2446a`, `hdr-compute-peak=yes`, `hdr-peak-percentile=99.8`, `hdr-contrast-recovery=0.28`.
- **720p / 1080p Streaming, 8-Bit, or Anime (`< 1600p` or `< 12 Mbps`):** Auto-ON `deband=yes` (`2/36/16/12`), `scale=ewa_lanczossharp`, `cscale=bilinear`, subtle micro-sharpen (`0.08`).
- **Presets:** `Cinema Mastering XR`, `Reference Creator`, `Night Theatre`, `Raw / Bypass`.

---

## 2. Official Android 14–16 Compliance Checklist (Phase C & D)

| Official Platform / Media3 API | LumaXR Implementation Class |
|---|---|
| `MediaLibraryService` & `MediaLibrarySession` (`1.11.1`) | `LumaMusicLibraryService.kt` (`onPlaybackResumption` 3-arg, `onConnectAsync`) |
| `AudioMixerAttributes` (`MIXER_BEHAVIOR_BIT_PERFECT`, API 34+) | `UsbBitPerfectManager.kt` |
| `LoudnessCodecController` (CTA-2075, API 35+) | `LoudnessAndReplayGainManager.kt` |
| `AudioManager.spatializer` (API 33+) | `SpatialAudioManager.kt` |
| `android.media.audiofx.DynamicsProcessing` | `AudioDspController.kt` |
| `AudioDeviceCallback` & `MediaRouter2.showSystemOutputSwitcher` | `AudioDeviceProfileRouter.kt` |
| `SystemHealthManager.getGpuHeadroom` (API 36) & `ThermalStatus` | `ThermalGovernor.kt` |
| `VibrationEffect.WaveformEnvelopeBuilder` (API 36) | `LumaHaptics.kt` |
| AGSL `RuntimeShader` & API 36 `RuntimeColorFilter` | `AgslAlbumBackdrop.kt` |
| `androidx.navigation3.ui.NavDisplay` + `SharedTransitionLayout` | `LumaNavigation.kt`, `PredictiveBackContainer.kt` |
| `androidx.glance` Home Widget (`1.1.1`) | `MusicGlanceWidget.kt` |

---

## 3. Kawarp `GL_RGBA16F`, `AmLyricsView` & `RangeChunkCache`

- **Kawarp Studio (`VisualStudioRenderer.kt`):** Allocates immutable `GL_RGBA16F` FBO targets via `glTexStorage2D`, runs 5-tap Dual Kawase downsample + 8-tap upsample passes, applies Justin Zhang *Diffuse* domain-warped fluid advection + `AudioFftAnalyzer` bass/mid/treble energy + 6 Glow Modes (`Off`, `Soft Bloom`, `Neon Core`, `Chromatic Prism`, `Crisp Rim`, `Adaptive Artwork`) + TPDF sRGB dithering (`kawarp_warp_glow.glsl`), plus MilkDrop ping-pong FBOs (`milkdrop_flow.glsl`).
- **AM Lyrics (`AmLyricsView.kt` & `WordSyncedLyricsParser.kt`):** Full AMLL TTML (`v1`/`v2` duets, `x-bg`, `x-translation`, `x-roman`), Enhanced LRC, YRC, QRC, LRCLIB; 120 Hz `withFrameNanos` interpolated sweep + elastic lift + `> 700 ms` held-note bloom + `> 4 s` interlude breathing dots + distance-graded `RenderEffect` blur.
- **Network Streaming (`RangeChunkCache.kt` & `NetworkMediaRepository.kt`):** Local `127.0.0.1` HTTP/1.1 `206 Partial Content` proxy backed by 2 MiB prefetch chunk cache for SMB v2/v3 (`smbj` + BouncyCastle), WebDAV (`sardine-android`), HTTP, and Jellyfin/Emby/Plex (with Trickplay/BIF/MediaSegments IntroSkipper).

---

## 4. CI & 16 KB ELF Page Alignment

Every push to `main`/`master` triggers [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml):
1. Generates a unique dynamic `applicationId` (`com.lumaxr.player.b<run>.r<sha>`) so builds can be installed side-by-side without overwriting config (exported/restored via `ConfigBackupManager`).
2. Runs unit tests across playback routing, XR spline calibration, refresh cadence, USB bit-perfect, Dolby selector, lyrics parser, CUE parser, track language rules, and range chunk caching.
3. Builds the `arm64-v8a` release APK with `useLegacyPackaging = false`.
4. Verifies every `.so` ELF `LOAD` alignment `>= 0x4000` (16 KB) via `readelf -l` and `zipalign -c -P 16 -v 4`.
5. Publishes the signed APK and categorized changelog to **GitHub Releases**.
