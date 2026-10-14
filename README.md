# Live TV

Android TV app for watching an M3U / IPTV playlist.

Default playlist: `https://hilaytv.xyz/play.m3u` (change it in **Settings**).

## Features
- Channel groups (from `group-title`) with logos (`tvg-logo`)
- ★ Favourites row
- Change channel while watching with ▲ / ▼ (or CH+ / CH−)
- Change playlist URL on the TV, or scan the QR code and send it from a phone
- Saves the last playlist so it still opens when the server is down
- HLS, DASH and direct streams; per-channel User-Agent / Referer (`#EXTVLCOPT` and `url|User-Agent=…`)

## Remote controls
| Where | Button | Action |
|---|---|---|
| List | OK | Play channel |
| List | Hold OK / Menu | Add/remove favourite |
| Player | ▲ / CH− | Previous channel |
| Player | ▼ / CH+ | Next channel |
| Player | OK / ◀ / ▶ / Info | Show channel info (OK retries a failed channel) |
| Player | Hold OK / Menu | Add/remove favourite |
| Player | Back | Back to list |

## Download
Every push to `main` builds the APK with GitHub Actions and publishes it under **Releases** as `LiveTV.apk`.

## Build locally
Open in Android Studio, or run `./gradlew assembleRelease` (JDK 17, Android SDK 35).
