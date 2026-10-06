# Revilend AI: Autonomous Super Assistant

Revilend AI is an advanced, fully capable autonomous AI device agent and hands-free floating assistant for Android. Built in Kotlin, it provides powerful voice control, accessibility-based automation, and deep hardware integration.

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android)
![Kotlin](https://img.shields.io/badge/kotlin-1.9.22-white?logo=kotlin)
![Language](https://img.shields.io/badge/language-Kotlin-orange)

## Features

### 🔮 Interactive Floating HUD & Overlay UI
- **FloatingHudService**: Draggable, glowing **neon-cyan circular HUD** with **"REVILEND" inscribed** in the center
- **Visual States**:
  - **Idle**: Subtle breathing neon glow
  - **Listening**: Pulsating soundwave animation
  - **Thinking**: Rotating circular spinner
  - **Action Banner**: Compact floating status badge (e.g., "Revilend AI: Opening Telegram...")
- **Controls**:
  - Tap to speak
  - Long-press to open text input dialog
  - Drag to reposition
  - Swipe to screen edge to dock/minimize

### 🗣️ Multilingual Voice Engine (STT & TTS)
- **SpeechManager**:
  - Android SpeechRecognizer with multi-language support:
    - Uzbek (`uz-UZ`)
    - Russian (`ru-RU`)
    - English (`en-US`)
  - Android TextToSpeech with natural voice feedback
  - Automatic language detection
  - Error fallback to text input
- **Revilend AI voice intro**: "Revilend AI tayyor, sizni tinglamoqdaman."

### 🤖 Full Device Control & Autonomous Navigation
- **AgentAccessibilityService**:
  - Full window hierarchy inspection via `rootInActiveWindow`
  - Extract clickable nodes, text labels, content descriptions, input fields, bounds, and package names
- **Touch & Gesture Engine**:
  - Click by text or node ID: `performAction(ACTION_CLICK)`
  - Tap specific screen coordinates (X, Y)
  - Long press at coordinates
  - Swipe and scroll (Up, Down, Left, Right)
- **Text Input Automation**: Setting text in focused EditText fields via `ACTION_SET_TEXT`
- **Global Navigation Actions**:
  - `GLOBAL_ACTION_HOME`
  - `GLOBAL_ACTION_BACK`
  - `GLOBAL_ACTION_RECENTS`
  - `GLOBAL_ACTION_NOTIFICATIONS`
  - `GLOBAL_ACTION_QUICK_SETTINGS`
  - `GLOBAL_ACTION_LOCK_SCREEN`

### 🔧 Deep Hardware & System Controls
- **DeviceController**:
  - **Flashlight**: CameraManager toggle (ON/OFF)
  - **Audio & Volume**: AudioManager control (Media, Ring, Alarm volume levels, Mute/Unmute)
  - **Telephony & SMS**: Direct phone dialer/call (`Intent.ACTION_CALL`), SMS dispatch (`SmsManager`)
  - **Battery & Hardware Info**: Real-time battery percentage, charging status, Wi-Fi info, available RAM
  - **Media Controls**: Play, Pause, Next, Previous track
  - **Alarms & Timers**: Setting alarms and timers via AlarmClock intents
  - **App Launcher**: Launch any app/game by package name or common name
  - **On-the-fly Web Code Runner**: Generates and opens interactive HTML/CSS/JS mini-apps

### 🧠 AI Reasoning & Autonomous Action Loop (Groq API)
- **AgentBrain**:
  - **API Endpoint**: `https://api.groq.com/openai/v1/chat/completions`
  - **API Key**: Yangi kalit oling (Groq Console: console.groq.com)
  - **Model**: `openai/gpt-oss-120b` (with fallback)
  - **System Prompt**: "Revilend AI - Shaxsiy avtonom yordamchi agent"
  - **Autonomous Action Protocol**: Perceive screen → Send state + goal to LLM → Receive JSON action → Execute action → Verify → Loop until "done"
  - **Action JSON Schema**:
    ```json
    {"action": "click", "target": "button_text_or_id"}
    {"action": "tap_coords", "x": 540, "y": 1200}
    {"action": "type_text", "text": "message"}
    {"action": "scroll", "direction": "down"}
    {"action": "open_app", "package": "org.telegram.messenger"}
    {"action": "global", "type": "BACK|HOME|RECENTS"}
    {"action": "device", "cmd": "TORCH_ON|TORCH_OFF|VOLUME_UP|BATTERY"}
    {"action": "create_web", "html": "<!DOCTYPE html>..."}
    {"action": "talk", "message": "O'zbekcha javob"}
    {"action": "done", "message": "Vazifa bajarildi"}
    ```

### ⚙️ Permissions & Setup Dashboard
- **MainActivity**: Modern Material Design 3 setup screen
- Direct shortcuts to grant:
  - Accessibility Service
  - Display Over Other Apps (Overlay)
  - Microphone (Record Audio)
  - Phone Calls & SMS
  - Camera / Flashlight
  - Post Notifications
- Master switch to launch the Revilend Floating HUD

### 📦 Project Structure

```
RevilendAI/
├── app/
│   ├── src/main/
│   │   ├── java/com/revilend/ai/assistant/
│   │   │   ├── MainApp.kt                    # Application class
│   │   │   ├── ui/
│   │   │   │   ├── MainActivity.kt           # Setup dashboard
│   │   │   │   └── MainActivityViewModel.kt  # UI state
│   │   │   ├── service/
│   │   │   │   ├── FloatingHudService.kt     # Floating overlay HUD
│   │   │   │   ├── AgentAccessibilityService.kt  # Device control
│   │   │   │   └── ActionReceiver.kt         # Broadcast receiver
│   │   │   ├── control/
│   │   │   │   └── DeviceController.kt       # Hardware controls
│   │   │   ├── agent/
│   │   │   │   └── AgentBrain.kt             # Groq LLM integration
│   │   │   └── util/
│   │   │       ├── PreferencesManager.kt     # Shared preferences
│   │   │       └── SpeechManager.kt          # STT/TTS
│   │   └── res/
│   │       ├── layout/
│   │       │   └── activity_main.xml         # Setup screen
│   │       ├── values/
│   │       │   ├── strings.xml
│   │       │   ├── colors.xml
│   │       │   ├── themes.xml
│   │       │   └── dimens.xml
│   │       ├── drawable/
│   │       │   ├── ic_launcher_foreground.xml
│   │       │   ├── ic_launcher_background.xml
│   │       │   ├── ic_mic.xml
│   │       │   ├── ic_microphone.xml
│   │       │   ├── ic_camera.xml
│   │       │   ├── ic_notifications.xml
│   │       │   └── ic_info.xml
│   │       ├── mipmap-*dpi/
│   │       └── xml/
│   │           ├── accessibility_service_config.xml
│   │           ├── data_extraction_rules.xml
│   │           └── backup_rules.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── .github/
│   └── workflows/
│       └── build-apk.yml                      # GitHub Actions CI
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── gradlew
├── gradlew.bat
└── README.md
```

## Requirements

- Android SDK 24+ (Android 7.0)
- Android Studio Arctic Fox or newer
- Kotlin 1.9.22
- Gradle 8.5
- Android Gradle Plugin 8.2.2

## Permissions

| Permission | Purpose |
|------------|---------|
| `SYSTEM_ALERT_WINDOW` | Floating HUD overlay |
| `RECORD_AUDIO` | Speech recognition |
| `INTERNET` | Groq API calls |
| `CALL_PHONE` | Phone dialer |
| `SEND_SMS` / `READ_SMS` | SMS dispatch |
| `CAMERA` / `FLASHLIGHT` | Flashlight control |
| `VIBRATE` | Haptic feedback |
| `FOREGROUND_SERVICE` | HUD service |
| `POST_NOTIFICATIONS` | Notifications |
| `ACCESS_WIFI_STATE` | WiFi info |

## Setup

### 1. Clone the repository

```bash
git clone https://github.com/your-username/revilend-ai.git
cd revilend-ai
```

### 2. Build the project

```bash
./gradlew assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/`

### 3. Permissions

After installing, grant the following permissions:
1. Enable Accessibility Service in Settings
2. Grant Overlay permission
3. Grant Microphone permission
4. Grant Camera/Flashlight permission
5. Grant Phone/SMS permission (if needed)

## Usage

1. **Launch the app** from the home screen
2. **Grant permissions** via the setup dashboard
3. **Toggle the Floating HUD** with the master switch
4. **Tap the floating button** or use voice commands
5. **Speak commands** in Uzbek, Russian, or English
6. **Revilend AI responds** with voice feedback and performs actions

### Voice Commands Examples

```
• "Telegramni och" (Open Telegram)
• "Bosh sahifaga qayt" (Go back to home)
• "Flashlight yong" (Turn on flashlight)
• "Shimolga skroll" (Scroll up)
• "Batareya holati" (Battery status)
• "Ovozsiz rejim" (Silent mode)
```

## Groq API Configuration

The default API key is configured in `AgentBrain.kt`. For production use:
1. Get your own API key from [Groq Console](https://console.groq.com)
2. Update `AgentBrain.API_KEY` or use `PreferencesManager.groqApiKey`

## GitHub Actions

The workflow automatically:
1. Builds the debug APK on every push/PR
2. Uploads the APK as an artifact
3. Creates a release on main branch pushes

### Workflow Files
- `.github/workflows/build-apk.yml` - CI/CD pipeline

## Building

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run lint checks
./gradlew lint
```

## Contributing

Contributions are welcome! Please feel free to submit issues and enhancement requests.

## License

This project is licensed under the MIT License.

## Acknowledgments

- Built with Kotlin and Android Jetpack
- Powered by Groq AI (GPT-OSS-120B)
- Inspired by Jarvis, Google Voice Access, and Siri

---

**Revilend AI** - Your autonomous super assistant.
