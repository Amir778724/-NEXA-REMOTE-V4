# NEXA Remote V4

Android app and temporary relay for owner-approved screen sharing and remote input.

## Features

- Android MediaProjection system consent before capture.
- Persistent foreground notification and in-app stop button.
- 8-digit temporary pairing code.
- Cloudflare Workers + SQLite-backed Durable Object WebSocket relay.
- Browser viewer at `/viewer`.
- JPEG screen frames sent only to the paired viewer.
- Tap and swipe input only while the owner-approved sharing session is active. The device owner must explicitly enable the NEXA accessibility service in Android Settings.

## Relay behavior

Each phone connects independently to the same HTTPS/WSS relay. The phones do **not** need to use the same VPN configuration. They each need a working route to the relay; if either network blocks the relay hostname, the session cannot connect.

The relay does not store screen frames. Pairing rooms are held in Durable Object connection state and expire after 48 hours. Use HTTPS/WSS only.

## Cloudflare deployment

The Worker project is in `worker/` and the Pages viewer is in `web/`.

- Worker name: `nexa-remote-relay`
- Pages project: `nexa-viewer`
- Pages viewer: `https://nexa-viewer.pages.dev/viewer`
- Android relay address: `wss://nexa-viewer.pages.dev/signal`

The Pages project requires a Service Binding named `RELAY` targeting the Worker `nexa-remote-relay`. Both projects must be in the same Cloudflare account. If the binding is missing, `/health` returns HTTP 503 with a clear error and WebSocket connection cannot work.

After deploying both projects, test:

1. Open `https://nexa-viewer.pages.dev/health`. A working Pages-to-Worker binding should return JSON containing `"ok": true`.
2. Open `https://nexa-viewer.pages.dev/viewer`.
3. Start screen sharing on the host phone and approve Android's system capture prompt.
4. Enter the 8-digit code on the viewer phone.

The `/health` Pages Function forwards the health request through the same private Service Binding used by `/signal`, so it checks the actual Pages-to-Worker path.

Cloudflare Pages availability on a particular mobile network is not guaranteed; test it from both target networks. A different VPN configuration on each phone is supported as long as both can reach the relay.

## Build

- JDK 17
- Android SDK 35
- Gradle 8.9
- Package: `com.nexa.remote`
- Minimum Android: API 26

GitHub Actions builds a debug APK on pushes to `main` and uploads it as an artifact.

## Limitations

- Remote input is limited to taps and swipes; keyboard input and file transfer are not implemented.
- Screen sharing requires the Android owner's visible system consent session to remain active. The owner can stop sharing at any time.
- A successful APK build does not mean the Cloudflare Worker or Pages project has been deployed. Cloudflare deployment and Service Binding must be checked separately.
