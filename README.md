<p align="center">
  <img src="assets/logo/logo.png" alt="Voice Keyboard Logo" width="120" height="120">
</p>

<h1 align="center">Voice Keyboard</h1>

<p align="center">
  <strong>A powerful Android keyboard with voice-to-text transcription powered by Wispr Flow</strong>
</p>

<p align="center">
  <a href="#features">Features</a> •
  <a href="#architecture">Architecture</a> •
  <a href="#getting-started">Getting Started</a> •
  <a href="#backend-setup">Backend Setup</a> •
  <a href="#android-app">Android App</a> •
  <a href="#building-for-production">Production Build</a>
</p>

---

## Features

- **Voice Input** - Hold to record, release to transcribe using Wispr Flow SDK
- **Text Keyboard** - Full QWERTY keyboard with symbols and numbers
- **Space Bar Joystick** - Swipe on space bar to move cursor left/right
- **Swipe-to-Delete** - Hold backspace and swipe to select and delete words
- **Terminal Support** - Works with terminal apps including TUI applications
- **Haptic Feedback** - Configurable vibration on key press
- **Auto-Send** - Optionally auto-send text after transcription

## Architecture

```
voice-keyboard/
├── android/          # Native Android IME (Input Method Editor)
├── backend/          # Bun.js transcription server (Wispr Flow SDK)
├── src/              # React Native app (settings UI)
└── assets/           # App assets and logo
```

### Components

| Component            | Description                                                   |
| -------------------- | ------------------------------------------------------------- |
| **Android IME**      | Native Kotlin keyboard service with Jetpack Compose UI        |
| **Backend Server**   | Bun.js server that handles audio transcription via Wispr Flow |
| **React Native App** | Configuration app for keyboard settings                       |

## Getting Started

### Prerequisites

- Node.js 18+ or Bun
- Android Studio with SDK 24+
- React Native CLI
- JDK 17

### Clone the Repository

```bash
git clone https://github.com/sahilchouksey/voice-keyboard.git
cd voice-keyboard
```

## Backend Setup

The backend server handles audio transcription using the Wispr Flow SDK.

### 1. Install Dependencies

```bash
cd backend
bun install
```

### 2. Configure Environment

Create a `.env` file in the `backend/` directory:

```env
# API Key for authentication (required for production)
# Generate: openssl rand -hex 32
API_KEY=your_secure_api_key_here

# Wispr Flow SDK credentials
WISPR_EMAIL=your_email
WISPR_PASSWORD=your_password
SUPABASE_URL=https://your-supabase-url.supabase.co
SUPABASE_ANON_KEY=your_supabase_anon_key
BASETEN_URL=https://your-baseten-url.api.baseten.co
BASETEN_API_KEY=your_baseten_api_key
```

### 3. Run the Server

```bash
# Development (no API key required)
bun run server.ts

# Production (API_KEY required)
API_KEY=your_key bun run server.ts
```

The server will start on `http://localhost:3002`

### API Endpoints

| Endpoint      | Method | Auth | Description                                  |
| ------------- | ------ | ---- | -------------------------------------------- |
| `/health`     | GET    | No   | Health check                                 |
| `/transcribe` | POST   | Yes  | Transcribe audio (body: `{ audio: base64 }`) |

### Authentication

For production, send the API key via:

- Header: `X-API-Key: your_api_key`
- Header: `Authorization: Bearer your_api_key`
- Query param: `?api_key=your_api_key`

## Android App

### Development Build

```bash
# Install dependencies
npm install

# Start Metro bundler
npm start

# Build and run on device/emulator
npm run android
```

### Enable the Keyboard

1. Open the Voice Keyboard app
2. Grant microphone permission
3. Go to Settings > System > Keyboard > Manage keyboards
4. Enable "Voice Keyboard"
5. Select Voice Keyboard as your input method

### Configure Server URL

In the app settings, set the API URL:

- Local: `http://192.168.x.x:3002`
- Production: `https://your-server.com?api_key=your_key`

## Building for Production

### Android Release APK

```bash
cd android

# Clean previous builds
./gradlew clean

# Build release APK
./gradlew assembleRelease
```

The APK will be at: `android/app/build/outputs/apk/release/app-release.apk`

### Android Release Bundle (AAB)

For Play Store submission:

```bash
cd android
./gradlew bundleRelease
```

The bundle will be at: `android/app/build/outputs/bundle/release/app-release.aab`

### Signing Configuration

For release builds, configure signing in `android/app/build.gradle`:

```gradle
android {
    signingConfigs {
        release {
            storeFile file('your-keystore.jks')
            storePassword 'your-store-password'
            keyAlias 'your-key-alias'
            keyPassword 'your-key-password'
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
}
```

## Configuration

### Environment Variables (Android App)

The Android app reads the server URL from SharedPreferences. Set it via:

1. The Settings screen in the app
2. Or programmatically via `KeyboardStateManager.apiUrl`

### Backend Environment Variables

| Variable            | Required   | Description                 |
| ------------------- | ---------- | --------------------------- |
| `PORT`              | No         | Server port (default: 3002) |
| `API_KEY`           | Production | API key for authentication  |
| `WISPR_EMAIL`       | Yes        | Wispr Flow account email    |
| `WISPR_PASSWORD`    | Yes        | Wispr Flow account password |
| `SUPABASE_URL`      | Yes        | Supabase project URL        |
| `SUPABASE_ANON_KEY` | Yes        | Supabase anonymous key      |
| `BASETEN_URL`       | Yes        | Baseten API URL             |
| `BASETEN_API_KEY`   | Yes        | Baseten API key             |

## Troubleshooting

### Keyboard not appearing

- Ensure the keyboard is enabled in system settings
- Check that microphone permission is granted

### Transcription failing

- Verify the backend server is running and accessible
- Check the API URL in settings (use your computer's IP, not localhost)
- Ensure API key is correct for production servers

### Cursor movement not working in terminal apps

- The keyboard uses Ctrl+B/F for cursor movement in terminals
- This works with most TUI apps including OpenCode

## License

MIT License - see [LICENSE](LICENSE) for details.

## Contributing

Contributions are welcome! Please open an issue or submit a pull request.
