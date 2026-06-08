# My Translator (Android)

A real-time **speech translation** app for Android. It listens through the
phone's microphone, transcribes and translates the speech in the cloud, shows
the result as on-screen **subtitles**, and optionally reads the translation
aloud as **voice**.

It is an Android adaptation of the desktop
[my-translator](https://github.com/phuc-nt/my-translator) (a Tauri app for
macOS/Windows). The desktop app relies on platform-specific system-audio
capture; this version is a native Android (Kotlin) app that captures the mic
and uses the same proven cloud engine.

## How it works

```
Microphone ──16 kHz PCM──▶ Soniox real-time WebSocket ──▶ subtitle (source + translation)
                                                       └─▶ Android Text-to-Speech ──▶ 🔊 voice
```

- **Speech-to-text + translation:** [Soniox](https://soniox.com) real-time
  WebSocket (`stt-rt-v4`) — returns the original transcript *and* the
  translation in a single stream. 70+ source languages, one-way translation to
  the target language you pick.
- **Voice output:** Android's built-in Text-to-Speech reads the translation
  aloud in the target language (toggle on/off).

## Setup

1. Get a Soniox API key (free credits to start) at
   [console.soniox.com](https://console.soniox.com).
2. Install the APK (see below), open the app, paste your API key.
3. Pick the **From** (source, or auto-detect) and **To** (target) languages.
4. Optionally enable **Speak translation (voice)**.
5. Press **Start** and grant microphone permission.

> Your audio goes directly from the phone to Soniox using your own API key.
> The key is stored locally on the device only.

## Getting the APK

This repository builds the APK automatically with **GitHub Actions**
(`.github/workflows/android-build.yml`):

1. Push to the repo (or run the *Build Android APK* workflow manually from the
   Actions tab).
2. Open the completed workflow run → **Artifacts** → download
   `my-translator-debug-apk`.
3. Unzip and install `app-debug.apk` on your phone (enable *Install unknown
   apps* for your file manager / browser).

## Build from source

```bash
git clone <this-repo>
cd <this-repo>
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17, Android SDK (API 34). Android Studio handles both.

- **minSdk:** 26 (Android 8.0+)
- **targetSdk / compileSdk:** 34

## Project layout

```
app/src/main/java/com/phucnt/mytranslator/
  MainActivity.kt    UI, wiring, permissions, smart-scroll subtitles
  SonioxClient.kt    Soniox real-time STT + translation over WebSocket
  AudioCapture.kt    16 kHz mono PCM microphone capture
  TtsManager.kt      Android Text-to-Speech (voice output)
  Languages.kt       Language picker list
  Prefs.kt           Persisted settings
```

## License

MIT
