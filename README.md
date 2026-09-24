# JARVIS — build this on your PC

This is an Android Studio project.

Includes:
- Home, Chat, Memory, Skills, Tasks, Files, Settings, Connect, Voice
- **Made by Veer** label
- **Settings → AI Model → Import .gguf**
- After import, chat uses the local llama.cpp engine (`llama-android`)
- Connect other apps and say “open YouTube”
- Galaxy A17 friendly (arm64, small 0.5B GGUF)

## Build on your computer (best)

1. Install [Android Studio](https://developer.android.com/studio) (Hedgehog or newer).
2. Unzip this folder.
3. Open Android Studio → **Open** → select the `JARVIS_Buildable` folder.
4. Wait for Gradle sync (needs internet).
5. Plug in your phone, enable USB debugging, or use an emulator.
6. Click **Run**.

Or in a terminal inside this folder:

```bash
# Linux / macOS
chmod +x gradlew
./gradlew assembleDebug
```

```bat
REM Windows
gradlew.bat assembleDebug
```

APK output:

`app/build/outputs/apk/debug/app-debug.apk`

Install that on the Galaxy A17.

## After install — connect your model today

1. Put `qwen2.5-0.5b-instruct-q4_k_m.gguf` on the phone (Downloads).
2. Open JARVIS → **More → Settings → AI Model**.
3. Tap **Import .gguf file**.
4. Pick the file.
5. Wait until it says imported.
6. Open Chat and ask something.

Use a **small** model (0.5B Q4). Do not import 7B on a 6 GB phone.

## Voice

Phone **Settings → Accessibility → Text-to-speech**  
Pick Google TTS + an English voice. JARVIS uses that.

## If Gradle wrapper JAR is missing

Android Studio will generate it. Or download:

https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar

Save as `gradle/wrapper/gradle-wrapper.jar`.

## Not for this tiny chat builder

Sites that only run Python/HTML cannot build this APK. You need Android Studio or a real Android CI (GitHub Actions with macOS/Ubuntu + Android SDK).
