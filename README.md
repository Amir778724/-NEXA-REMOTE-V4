# NEXA Remote V4

Android app and temporary relay for owner-approved screen sharing and remote taps.

## Features in this build

- Android MediaProjection system consent before capture.
- Persistent foreground notification and in-app stop button.
- 8-digit temporary pairing code.
- TLS WebSocket relay with in-memory rooms and 30-minute expiry.
- Browser viewer at `/viewer`.
- JPEG screen frames sent only to the paired viewer.
- Optional single-tap input. The device owner must explicitly enable the NEXA accessibility service in Android Settings. Tap events are accepted only while the owner-approved sharing session is active.

## Deploy the relay

1. Open Render and create a new **Blueprint** from this GitHub repository.
2. Render reads the root `render.yaml` and creates the `nexa-remote-relay` web service.
3. After deployment, copy the service HTTPS address, for example `https://YOUR-SERVICE.onrender.com`.
4. In the Android app, enter `wss://YOUR-SERVICE.onrender.com/signal`.
5. Start sharing and approve Android's screen-capture prompt. Give the 8-digit code only to the intended viewer.
6. Open `https://YOUR-SERVICE.onrender.com/viewer` on the viewer device and enter the code.

The relay does not store screen frames. Pairing rooms are held in memory and expire after 30 minutes. Use HTTPS/WSS only.

## Build

- JDK 17
- Android SDK 35
- Gradle 8.9
- Package: `com.nexa.remote`
- Minimum Android: API 26

GitHub Actions builds a debug APK on pushes to `main` and uploads it as an artifact.

## Current limitations

Remote input currently supports **single taps only**. Swipe, keyboard input, file transfer, unattended access, and background/hidden operation are not implemented. The Android owner must keep the app's visible consent session active and can stop it at any time.
