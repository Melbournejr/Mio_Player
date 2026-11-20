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

What it does (see `data/audio/DsdSupport.kt`):
1. Detects `.dsf` / `.dff` (DSD64 2.8224 MHz, DSD128 5.6448 MHz, DSD256 11.2896 MHz).
2. Decodes DSD → high-res PCM with the official **Media3 FFmpeg audio decoder extension** (`media3-decoder-ffmpeg:1.4.1`, prebuilt AAR, no NDK build): DSD64 → PCM 176.4 kHz, DSD128 → 352.8 kHz, DSD256 → 705.6 kHz.
3. Plays that PCM at the highest rate the device supports. If the device caps at 48/96 kHz, ExoPlayer resamples — Now Playing shows the source honestly, e.g. `DSD64 → PCM 176.4kHz (device 96kHz)`.

Why this FFmpeg choice and not ffmpeg-kit: `ffmpeg-kit-full` (arthenica) is unmaintained, very large, and breaks repeatedly on AGP 8.x / SDK 34. The Media3 extension tracks the Media3 version and is a prebuilt AAR. Do not add ffmpeg-kit alongside it — two FFmpeg copies conflict at link time.

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
Kotlin 1.9.24 · Jetpack Compose + Material 3 · Navigation Compose · Media3 ExoPlayer 1.4.1 (+ FFmpeg decoder) · MediaSession · Room 2.6.1 · DataStore · Coil · Retrofit/OkHttp (LRCLIB) · Manual DI (no Hilt, deliberately) · minSdk 26, targetSdk 34, JDK 17, AGP 8.5.2, Gradle 8.7.

## Build / verification status (honest)
- **Verified in this workspace:** full file tree present; all XML well-formed; Kotlin/Gradle brace balance checked; GitHub Actions YAML parses; launcher icons generated from the final logo (`mipmap-*`, adaptive icon).
- **NOT built here:** this VM has **no Java and no Android SDK**, so `./gradlew assembleDebug` could **not** be run locally and **no APK was produced**. The first real build must be the GitHub Actions run (or Android Studio on your machine). If that run fails, the log will name the exact file — the most likely risks are: (a) a Media3 FFmpeg renderer API mismatch in `PlayerManager.kt`, (b) a Compose import missing in a screen, (c) Room/KSP version drift. Fix the named file, push again, and Actions rebuilds.
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
