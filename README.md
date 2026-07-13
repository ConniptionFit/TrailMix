# TrailMix

Android voice-dictation notes app with on-device Gemini Nano processing and Obsidian vault export.

## Requirements

- JDK 17+
- Android SDK 35
- Pixel (or other device) with AICore / Gemini Nano for on-device summarization & merge

## Build

```bash
export JAVA_HOME="$HOME/.jdks/jdk-17.0.19+10/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:assembleDebug
```

Install on a device:

```bash
./gradlew :app:installDebug
```

## Features

- Record voice notes with live on-device speech recognition
- Type notes during recording
- Merge transcript + typed notes via Gemini Nano (ML Kit GenAI)
- Save locally (Room) and export Markdown into a linked Obsidian vault (SAF)
