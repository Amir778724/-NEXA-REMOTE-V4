# NEXA Remote V4

Android app and temporary relay for owner-approved screen sharing and remote taps.

## Features in this build

- Android MediaProjection system consent before capture.
- Persistent foreground notification and in-app stop button.
- 8-digit temporary pairing code.
- Cloudflare Workers + SQLite-backed Durable Object WebSocket relay.
- Browser viewer at `/viewer`.
- JPEG screen frames sent only to the paired viewer.
- Optional single-tap input. The device owner must explicitly enable the NEXA accessibility service in Android Settings. Tap events are accepted only while the owner-approved sharing session is active.

## Deploy the relay on Cloudflare (free plan)

1. Create a free Cloudflare account. The free Workers plan is advertised as requiring no credit card.
2. In the Cloudflare dashboard, open **Workers & Pages** and choose **Create application** / **Connect to Git**.
3. Connect GitHub and select `Amir778724/-NEXA-REMOTE-V4`.
4. Set the project root directory to `worker`.
5. Set the build command to `npm install` and the deploy command to `npm run deploy`.
6. Start the first deployment. Wrangler will create the Worker and its SQLite-backed Durable Object binding from `wrangler.toml`.
7. Copy the assigned `https://...workers.dev` address.
8. In the Android app, enter `wss://YOUR-WORKER.workers.dev/signal`.
9. Start sharing and approve Android's screen-capture prompt. Give the 8-digit code only to the intended viewer.
10. Open `https://YOUR-WORKER.workers.dev/viewer` on the viewer device and enter the code.

The relay does not store screen frames. Pairing rooms are held in the Durable Object connection state and expire after 30 minutes. Use HTTPS/WSS only. Free-plan quotas and service terms can change; check Cloudflare's current usage page.

## Build

- JDK 17
- Android SDK 35
- Gradle 8.9
- Package: `com.nexa.remote`
- Minimum Android: API 26

GitHub Actions builds a debug APK on pushes to `main` and uploads it as an artifact.

## Current limitations

Remote input currently supports **single taps only**. Swipe, keyboard input, file transfer, unattended access, and background/hidden operation are not implemented. The Android owner must keep the app's visible consent session active and can stop it at any time.


## NEXA VIEW on a free Pages subdomain (Iran-friendly routing test)

The browser viewer can be deployed separately on Cloudflare Pages at `https://nexa-viewer.pages.dev/viewer`. The Pages Function at `/signal` forwards WebSocket upgrades to the existing `nexa-remote-relay` Worker through a private Cloudflare Service Binding, so the browser and Android app do not need to contact the public `workers.dev` hostname directly.

1. In Cloudflare, open **Workers & Pages** → **Create application** → **Pages** → **Connect to Git**.
2. Connect this repository: `Amir778724/-NEXA-REMOTE-V4`.
3. Set the project name to `nexa-viewer`, production branch to `main`, and the root directory to `web` (framework preset: None; build command empty; output directory: `.`).
4. Ensure the Pages project uses `web/wrangler.toml` as its Wrangler configuration. It declares the `RELAY` Service Binding to the existing Worker named `nexa-remote-relay`. Both projects must be in the same Cloudflare account. If the binding is not picked up automatically, add it in Pages → Settings → Bindings → Add → Service binding, variable name `RELAY`, service `nexa-remote-relay`, then redeploy.
5. After the first successful deployment, open `https://nexa-viewer.pages.dev/viewer` from the viewer phone. The Android app's default relay address is now `wss://nexa-viewer.pages.dev/signal`.

This uses Cloudflare's free Pages subdomain and does not require buying a domain. Availability of `pages.dev` on Iranian networks is not guaranteed; test it from the target mobile network. The old `workers.dev` URL remains the fallback until the new Pages site and Service Binding are deployed and tested.
