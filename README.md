# PPT Runner

A low-latency presentation remote for Windows and Android.

PPT Runner allows a presenter to control a presentation running on
a Windows computer using the physical Volume Up and Volume Down
buttons on an Android phone.

## Download

[Latest Release](../../releases/latest)

- Windows: PPT-Runner-Setup.exe
- Android: PPT-Runner.apk

## Architecture

Android Phone
      │
      ├── Local WebSocket
      │
      └── Cloud WSS Relay
                │
                ▼
        Windows Desktop Agent
                │
                ▼
        Keyboard Input Layer

## Features

- QR-based pairing
- Local-first WebSocket communication
- Cloud relay fallback
- Physical Android volume-button controls
- Automatic local/relay transport selection
- Sequence-numbered commands
- Heartbeat and connection monitoring
- Windows 10/11 support
- No presentation-content upload
- No persistent session database

## Technologies

### Android
- Kotlin
- Android MediaSession
- VolumeProvider
- OkHttp WebSocket
- ML Kit Code Scanner

### Windows
- C#
- .NET 10
- WPF
- Fleck WebSocket server
- ClientWebSocket
- Windows keyboard input

### Relay
- Node.js
- WebSocket (`ws`)

## Downloads

See the Releases page for:

- Windows installer
- Android APK

## Developer

Hemant Verma  
India

LinkedIn: https://www.linkedin.com/in/hemant-verma-ind/
