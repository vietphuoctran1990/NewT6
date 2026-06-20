# My Translator (Android)

A real-time **speech translation** app for Android. It listens to the
microphone *or* the phone's media audio, translates in the cloud, shows the
result as on-screen **subtitles** (including a **floating overlay over other
apps**), and reads the translation aloud as **voice**.

Android adaptation of the desktop
[my-translator](https://github.com/phuc-nt/my-translator) (Tauri, macOS/Windows).

## How it works

```
Mic / System audio ──PCM──▶ Engine ──▶ subtitles (in-app + floating overlay)
                            │
                            ├─ Soniox  → text, + Android TTS voice
                            └─ OpenAI  → text + native translated voice
```

Everything runs inside a **foreground service**, so translation continues while
you switch to YouTube, a browser, or any other app and read the floating
subtitles on top.

## Features

- **Two cloud engines:**
  - **Soniox** (`stt-rt-v4`) — STT + translation in one stream; voice via
    Android Text-to-Speech. 70+ languages, ~$0.12/hr.
  - **OpenAI Realtime** (`gpt-realtime-translate`) — returns translated text
    *and* native translated speech in one stream (lowest latency, ~$4/hr).
- **DeepSeek refinement (optional, Soniox engine):** Soniox shows a fast draft
  subtitle, then DeepSeek (`deepseek-chat`) re-translates the segment into more
  fluent target-language text — applying your glossary — and that improved
  version is what gets spoken. Each segment upgrades in place.
- **Two-way conversation (Soniox):** bilingual mode (A ↔ B) — Soniox detects who
  is speaking and translates to the other language. Shows an interleaved chat
  transcript; optional voice speaks each side in its own language and mutes the
  mic while speaking (half-duplex) to avoid echo. Mic-only.
- **Floating subtitles** over other apps (draggable, font +/−).
- **Audio source: Microphone or System audio.**
  - ⚠️ Android only allows capturing **media/game** audio (YouTube, video,
    music). It **cannot** capture voice-call audio (Zoom/Meet/phone) or DRM
    content (Netflix) — a platform limitation, unlike the desktop app.
- **Low-latency tuning:** live (provisional) subtitles shown as you speak,
  shorter endpoint delay, adjustable voice speed, small audio chunks.

## Setup

1. Get an API key:
   - Soniox: [console.soniox.com](https://console.soniox.com), or
   - OpenAI: [platform.openai.com](https://platform.openai.com).
2. Install the APK (below), open the app, pick the **Engine**, paste its key.
3. Choose **Audio source** (Microphone / System audio), **From** → **To**.
4. Optionally enable **Speak translation (voice)** and **Floating subtitles**.
5. Press **Start** and grant the requested permissions (microphone /
   notifications / display-over-other-apps / screen-capture for system audio).

> Audio goes directly from the phone to the engine using your own API key,
> stored locally on the device only.

## Getting the APK (GitHub Actions)

1. Repo → **Actions** tab → open a successful **Build Android APK** run.
2. **Artifacts** → download **`my-translator-debug-apk`** → unzip →
   install `app-debug.apk` (enable *Install unknown apps*).

## Build from source

```bash
./gradlew assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17, Android SDK (API 34). **minSdk 26** (Android 8.0+).

## Project layout

```
app/src/main/java/com/phucnt/mytranslator/
  MainActivity.kt          UI, settings, permission flows
  TranslationService.kt    Foreground service orchestrating the pipeline
  TranslationEngine.kt     Engine interface + listener
  SonioxClient.kt          Soniox real-time STT + translation
  OpenAiRealtimeClient.kt  OpenAI Realtime (text + native voice)
  DeepSeekRefiner.kt       DeepSeek re-translation/refinement of Soniox segments
  AudioCapture.kt          Mic / system (MediaProjection) PCM capture
  AudioTrackPlayer.kt      Plays OpenAI's native voice
  TtsManager.kt            Android Text-to-Speech (voice for Soniox)
  OverlayController.kt      Floating subtitle window
  EngineBus.kt / Prefs.kt / Languages.kt
```

## License

MIT
