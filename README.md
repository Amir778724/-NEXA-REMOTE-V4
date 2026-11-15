# NEXA Remote V4

Android/Kotlin starter for explicit, user-approved remote support.

## Included in this build

- Android app shell built with Jetpack Compose.
- Android MediaProjection consent prompt before screen capture.
- MediaProjection foreground service with a persistent notification.
- In-app and notification actions to stop capture.
- GitHub Actions workflow to build a debug APK and publish it as an artifact.

## Important status

This repository currently implements the consent-based screen-capture foundation only. It does **not** yet stream the screen over a network or accept remote touch/keyboard input. Those features require an authenticated signaling/transport service and a separately consented input-control design. No hidden access, permission bypass, or silent capture is implemented.

## Build

- Android Studio
- JDK 17
- Android SDK 35
- Gradle 8.9

Package: `com.nexa.remote`
Minimum Android: API 26
