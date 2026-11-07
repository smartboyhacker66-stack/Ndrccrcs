# BITS Tandem Centre

Developed by Blind Indian Tech Support. All Rights Reserved © 2026 Blind Indian Tech Support Team. Lead Developer: Aman.

An accessible Android remote-assistance app. A helper generates a Meeting ID; the person who needs help enters it under **Allow access to my phone**, agrees to screen sharing, and the helper can then see the screen and tap, swipe, and press Back, Home and Recents.

## Contents

| Path | Purpose |
|---|---|
| `server.js`, `package.json` | WebSocket signaling server (relays WebRTC setup only; no screen data) |
| `render.yaml` | Render Blueprint: `npm install` / `node server.js`, free plan, `/health` check |
| `app/` | Android app (Java, WebRTC, AccessibilityService, MediaProjection) |
| `.github/workflows/build-apk.yml` | Builds the APK on every push to `main`; uploads it as an artifact and a GitHub Release |
| `push_to_github.bat` | Windows one-click script (needs `git` and a logged-in `gh`): Step 1 push, Step 2 build and auto-download APK to `dist\`, Step 3 server check. On a failed build it saves the exact error to `build-error-log.txt` |
| `scripts/finish.ps1` | Speaks and prints the final message |
| `.gitattributes` | Fixed line endings so Git prints no CRLF warnings |

## How it works

1. **Connect Another Device** → **Generate Meeting ID** makes 7 digits + 1 capital letter (for example `1234567A`), copies it, reads it aloud character by character, and waits.
2. On the other phone, **Allow access to my phone** → enter the ID → **Connect**. Android asks for the accessibility service (once) and for screen capture (every session). A notification with a Disconnect action stays visible while sharing.
3. **Disconnect Connection** is on every screen and ends the session on both sides.

## Notes

- The server URL defaults to `bits-tandem-centre.onrender.com`. If Render gives your service a different name, set a repository variable `SERVER_HOST` (Settings → Secrets and variables → Actions → Variables) and rebuild, or edit `serverHost` in `gradle.properties`.
- Connections use Google STUN servers. Some mobile or corporate networks need a TURN server for the video to connect.
- The APK is a debug-signed build, suitable for sideloading.
