# Mio Player — Android

A local, high-fidelity music player for your own library. No streaming service, no account server, no ads.

**Theme:** Mio Deep Wave — dark `#0B1216`, surface `#121A20`, gradient teal `#14B8A6` → cyan `#22D3EE`, white text. See `app/src/main/java/com/mio/player/ui/theme/Theme.kt` to change tokens.

## What it does
- **Local library:** scans MediaStore + folders you pick (SAF). Formats: FLAC, WAV, MP3, AAC, ALAC (m4a), APE, OGG, DSD (.dsf/.dff). Albums, artists, playlists, search, favorites, recently / most played.
- **Playback:** ExoPlayer (Media3) + MediaSession — notification / lockscreen / Bluetooth controls, gapless playback, queue, shuffle, repeat off/all/one, playback speed, sleep timer, seek.
- **Sound quality:** high-res AudioTrack output at the device's highest sample rate, ReplayGain (off / track / album), crossfade option.
- **Audio tuning (Equalizer & Sound screen):** 10-band EQ (31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz) with presets Flat / Bass / Treble / Vocal / Rock / Jazz / Classical / Lo-Fi + Custom, Bass Boost, Virtualizer/3D, Loudness Enhancer, Left/Right Balance, Preamp. Uses Android `AudioEffect` on ExoPlayer's audio session; unsupported effects are skipped silently, never crash playback.
- **Lyrics:** auto-finds synced lyrics from LRCLIB (free, no key), falls back to plain lyrics, embedded lyrics, and sidecar `.lrc` files next to the audio file. Lyrics are cached in Room.
- **Screens (12):** Splash, Onboarding, Login (local profile only — no server), Pick Genres, Home, Now Playing, Library, Search, Album Detail, Artist Page, Lyrics, Profile/Settings — plus Equalizer & Sound. Bottom navigation Home / Search / Library / Profile + persistent mini-player.

## DSD — read this first
Android has **no native DSD passthrough on most phones**. Mio Player does **not** claim native DSD output.

What it does (see `data/audio/DsdSupport.kt` + `data/audio/DsdDecoder.kt`):
1. Detects `.dsf` / `.dff` (DSD64 2.8224 MHz, DSD128 5.6448 MHz, DSD256 11.2896 MHz).
2. Decodes DSD → PCM 176.4 kHz 16-bit WAV **once, cached**, with FFmpeg via the maintained fork `dev.ffmpegkit-maintained:ffmpeg-kit-full:6.0.3` (verified HTTP 200 on Maven Central, 2026-11-20). First play of a DSD file takes a few seconds to convert; later plays use the cache instantly.
3. Plays that WAV at the highest rate the device supports. If the device caps at 48/96 kHz, ExoPlayer resamples — Now Playing shows the source honestly, e.g. `DSD64 → PCM 176.4kHz (device 96kHz)`.

Why this FFmpeg choice: `androidx.media3:media3-decoder-ffmpeg` is **not published** by Google to Google Maven or Maven Central (404 for every version, including 1.4.1) — depending on it broke CI run #2 with `ModuleVersionNotFoundException`. `com.arthenica:ffmpeg-kit-*` was removed from Maven Central in April 2025. The maintained fork above is the drop-in replacement with the same `com.arthenica.ffmpegkit` API. Do NOT re-add either dead dependency. (Alternative that also exists: Jellyfin's `org.jellyfin.media3:media3-ffmpeg-decoder`, but only for Media3 1.2/1.3/1.5/1.6/1.8/1.9 — not 1.4.1 — and it needs a custom renderer.)

## Push to GitHub
```bash
cd mio-player-android
git init
git add .
git commit -m "Mio Player 1.0.0 — Deep Wave"
git branch -M main
git remote add origin https://github.com/<your-username>/mio-player.git
git push -u origin main
```
Replace `<your-username>/mio-player` with your repo. Do **not** commit `local.properties` or a keystore (see `.gitignore`).

## Build the APK (GitHub Actions)
On every push to `main`/`master`, `.github/workflows/build-apk.yml` runs:
JDK 17 → Android SDK → Gradle 8.7 → `gradle assembleDebug` + `gradle assembleRelease` → uploads both APKs as artifacts.
1. Go to your repo → **Actions** → latest run → **Artifacts**.
2. Download `mio-player-debug-apk` (installable immediately) or `mio-player-release-apk` (unsigned — sign it before distributing; see below).
3. On your phone: allow install from browser/files, install the APK.

> The workflow uses system Gradle 8.7 (`gradle/actions/setup-gradle`), not `./gradlew`, because this repo does not ship the binary `gradle-wrapper.jar`. Locally, run `gradle wrapper --gradle-version 8.7` once to generate it, then `./gradlew assembleDebug` works too.

### Signing a release APK (optional, for distribution)
```bash
keytool -genkey -v -keystore mio-release.keystore -alias mio -keyalg RSA -keysize 2048 -validity 10000
# then in app/build.gradle.kts add a signingConfigs.release block, or sign with:
apksigner sign --ks mio-release.keystore --out app-release-signed.apk app/build/outputs/apk/release/app-release-unsigned.apk
```
Never commit the `.keystore` or its passwords.

## Add music
1. Copy music into `Music/` (or any folder) on the phone.
2. Open Mio Player → Library → **Scan music folders**. Grant audio permission when asked (Android 13+: Media audio).
3. For extra folders outside MediaStore, pick them in Settings (SAF folder picker — folders are stored in DataStore, rescanned on demand).
4. DSD files: just copy `.dsf` / `.dff` in — they appear with a `DSD64 → PCM 176.4kHz` badge.
5. Lyrics: put a `.lrc` file with the same name next to a track for guaranteed synced lyrics; otherwise LRCLIB is queried automatically (needs internet, once — then cached).

## Change the theme
Edit `ui/theme/Theme.kt` (`MioBg`, `MioSurface`, `MioTeal`, `MioCyan`, …) and `res/values/colors.xml`. All screens read those tokens; do not hard-code colors in screens.

## Tech stack
Kotlin 1.9.24 · Jetpack Compose + Material 3 · Navigation Compose · Media3 ExoPlayer 1.4.1 + ffmpeg-kit-maintained 6.0.3 (DSD decode-to-WAV cache) · MediaSession · Room 2.6.1 · DataStore · Coil · Retrofit/OkHttp (LRCLIB) · Manual DI (no Hilt, deliberately) · minSdk 26, targetSdk 34, JDK 17, AGP 8.5.2, Gradle 8.7.

## Build / verification status (honest)
- **Verified in this workspace:** full file tree present; all XML well-formed; Kotlin/Gradle brace balance checked; GitHub Actions YAML parses; launcher icons generated from the final logo (`mipmap-*`, adaptive icon).
- **NOT built here:** this VM has **no Java and no Android SDK**, so `./gradlew assembleDebug` could **not** be run locally and **no APK was produced**. The first real build must be the GitHub Actions run (or Android Studio on your machine). If that run fails, the log will name the exact file — the most likely risks are: (a) a Compose import missing in a screen, (b) Room/KSP version drift, (c) a dependency that no longer resolves on Maven Central — always verify a new dependency's POM returns HTTP 200 before adding it. Fix the named file, push again, and Actions rebuilds.
- Known simplifications: crossfade is a volume-ramp best-effort (ExoPlayer has no built-in crossfade); EQ presets are applied live but Custom preset persistence is via DataStore keys in `SettingsStore` (wire the save button in the EQ screen to `KEY_EQ_BANDS` if you add one); album art is a placeholder icon — wire Coil + `MediaStore.Audio.Albums` art URI in `TrackRow` for real covers; sleep-timer UI is in `PlayerManager.setSleepTimer()` — add a picker dialog on Now Playing to expose it.

## Project layout
```
app/src/main/java/com/mio/player/
  MainActivity.kt  MioApp.kt            # NavHost, bottom nav, mini-player
  data/model/Models.kt                  # Track, Album, Artist, Playlist, Lyrics
  data/db/AppDatabase.kt               # Room: tracks, lyrics cache, playlists
  data/repo/MusicScanner.kt  LyricsRepository.kt
  data/audio/DsdSupport.kt  AudioEffectsManager.kt
  data/prefs/SettingsStore.kt
  playback/PlayerManager.kt  PlayerService.kt
  ui/theme/Theme.kt  ui/navigation/  ui/components/Common.kt
  ui/screens/  Onboarding.kt (Splash/Login/Genres)  Home.kt  NowPlaying.kt  Detail.kt  Equalizer.kt
  ui/viewmodel/ViewModels.kt
```

## OTA update (added 2026-11-20)

- The app checks GitHub Releases on start and in Profile > Check for updates.
- It calls `https://api.github.com/repos/Melbournejr/Mio_Player/releases/latest` (public, no key), compares `tag_name` with the installed `versionName`, and if newer downloads the `.apk` asset and prompts install via FileProvider.
- To publish an update: bump `versionCode` / `versionName` in `app/build.gradle.kts`, commit, then push a tag, e.g. `git tag v1.0.1 && git push origin v1.0.1`. The `Release Mio Player APK (OTA)` workflow builds the release APK and creates the GitHub Release automatically.
- First install from an APK needs Android to allow "Install unknown apps" for Mio Player once; the app opens that settings page when needed.

## Build fix 2026-11-20

- `android-actions/setup-android@v3` failed in run #1: it runs `sdkmanager --licenses` interactively and hangs on "Review licenses that have not been accepted (y/N)?".
- Fixed by removing that action and using the SDK preinstalled on `ubuntu-latest` (`/usr/local/lib/android/sdk`), accepting licenses non-interactively with `yes | sdkmanager --licenses`.
