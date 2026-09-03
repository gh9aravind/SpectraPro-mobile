# Spectra (Android)

Native rewrite of the Spectra audio analyzer: pick a track, get a spectrogram
plus a full technical report (peak/RMS/dynamic range/clipping, spectral
cutoff / "fake lossless" detection).

## Status: Phase 1

This is a first pass, written without access to the Android SDK / a real
device (no compiler in the environment it was built in), so treat it as a
strong draft to build and iterate on rather than a finished, tested app.

**What's implemented:**
- Jetpack Compose UI (dark theme, matches the earlier web version's look)
- File picking via Storage Access Framework (`OpenDocument`)
- Metadata via `MediaMetadataRetriever` — title/artist/album/genre/year/
  track/disc/bitrate/duration/cover art
- Decode via Android's built-in `MediaExtractor` + `MediaCodec` — covers
  every format Android guarantees: AAC, MP3, FLAC, Vorbis, Opus, WAV/PCM
- Spectrogram (STFT), peak/RMS/dynamic range, clipping detection, and the
  spectral-cutoff "fake lossless" heuristic — pure-Kotlin DSP core in
  `dsp/SpectrogramAnalyzer.kt`, ported from the web version's `worker.js`
  and cross-checked against it with matching synthetic-signal tests
  (same sine/noise/clipping test cases, same results to 3+ decimal places)

**Deliberately deferred to Phase 2** (see chat for the reasoning):
- **ALAC support.** Stock `MediaCodec` has no guaranteed ALAC decoder.
  Plan: add [`org.jellyfin.media3:media3-ffmpeg-decoder`](https://github.com/jellyfin/media3-ffmpeg-decoder)
  as a Gradle dependency (a prebuilt AAR — no NDK build step needed) and
  wire it in as the decode path for formats `MediaCodec` can't handle.
  Commented-out dependency lines are already left in `app/build.gradle.kts`
  as a starting point.
- **LUFS (integrated loudness) + True Peak.** The web version got these
  for free from ffmpeg's `ebur128` filter. Once the FFmpeg decoder extension
  is wired in for ALAC anyway, reuse the same FFmpeg binary to run
  `ebur128` for loudness measurement — one dependency, two features.
- **Decoder consistency.** The original design decision (one decoder for
  every format, for reproducible results across devices) is fully
  restored once Phase 2 lands, since everything will route through
  FFmpeg the same way the web version does.
- **Extended tags** (ISRC, composer, freeform comments). `MediaMetadataRetriever`
  doesn't expose these; would need a dedicated tag-parsing library
  (e.g. `jaudiotagger`) layered on top of `metadata/MetadataReader.kt`.

## Confidence level, file by file

- `dsp/SpectrogramAnalyzer.kt` — **compiled and tested** in this
  environment (`kotlinc`), with results cross-checked against the web
  version's test suite. Highest confidence.
- `decode/AudioDecoder.kt`, `metadata/MetadataReader.kt` — plain Android
  SDK APIs (`MediaCodec`/`MediaExtractor`/`MediaMetadataRetriever`),
  written against long-stable, well-documented patterns, but **not
  compiled** (no Android SDK available here).
- `ui/*.kt`, `MainActivity.kt` — Jetpack Compose, **not compiled** (no
  Compose libraries available here to check against). Reviewed by hand
  for type/import consistency, but this is the most likely place for a
  small mistake (an import, a parameter name) to surface on first build.

If the first `gradle assembleDebug` run fails, it's most likely a minor
Compose API/import mismatch — paste the error back and it's a quick fix.

## Build

```
gradle assembleDebug
```
GitHub Actions (`.github/workflows/build.yml`) does this automatically on
every push and uploads the APK as a build artifact — no local Android
Studio needed, matching the android-music-app workflow.

## Version pins

`build.gradle.kts` files pin specific AGP/Kotlin/Compose/AndroxdX versions
that were current and known-good at write time. If Android Studio or
Gradle suggests newer ones when you open this, that's normal — bump them.
