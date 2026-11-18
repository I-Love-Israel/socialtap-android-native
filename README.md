# SocialTap for Android

Native Android client for SocialTap: sign in, read and send chat messages, get message notifications, and make or answer one-to-one voice calls.

## What it does

- **Sign in** with SocialTap through OAuth 2.0 authorization code + PKCE (S256), opened in a Chrome Custom Tab. The app never sees your password.
- **Chats**: lists your chats, shows history, sends messages, and marks chats as read.
- **Live updates**: a foreground service keeps a Socket.IO connection open (Engine.IO 4), so new messages and calls arrive while the app is in the background.
- **Message notifications**: new messages from other people are posted as notifications.
- **Voice calls**: start, ring, accept, decline and hang up calls in two-person chats. Audio goes through SocialTap's server relay as mono IMA ADPCM (8 kHz, 50 ms packets), the same format the web client uses. Calls keep running with the microphone foreground service.
- **Incoming calls**: a full-screen call notification with Answer and Decline, shown over the lock screen.

## OAuth client

The public OAuth client ID is hardcoded in
[`app/src/main/java/com/socialtap/android/Config.kt`](app/src/main/java/com/socialtap/android/Config.kt):

```kotlin
const val OAUTH_CLIENT_ID = "stu_A49-g2KYxxXPJQL-OfuawbvqF5FARwCx5dsT7zGqhD0"
const val REDIRECT_URI = "socialtap://oauth/callback"
```

It is a public client, so it has no secret and is safe to ship. It was created in the SocialTap developer dashboard as **SocialTap Android**, with required scopes `chat.read`, `chat.send`, `voice.call`, `voice.audio` and optional scopes `account.read`, `friends.read`.

## Background behaviour and limits

- While the app has been opened and the connection service is running, messages and incoming calls arrive in the background. The service shows a quiet "SocialTap is connected" notification.
- A background worker also checks for new messages about every 15 minutes (the Android minimum), even if the connection service has stopped.
- SocialTap's public API has no push-notification (FCM) endpoint. So if Android has stopped the app completely (force-stopped, or killed and not reopened), incoming calls cannot ring until the app is opened again. Adding FCM would need server support.

## Build

Requirements: JDK 17 and the Android SDK (platform 35, build-tools 35.0.0).

```powershell
.\gradlew.bat :app:testDebugUnitTest     # codec unit tests
.\gradlew.bat :app:assembleDebug         # debug APK
.\gradlew.bat :app:assembleRelease       # signed release APK (needs keystore.properties)
```

Release signing reads `keystore.properties` in the project root (git-ignored):

```properties
storeFile=C:/Users/you/.socialtap-keys/socialtap-release.jks
storePassword=...
keyAlias=socialtap
keyPassword=...
```

Keep the keystore and its passwords out of git. Losing the keystore means you cannot publish updates that install over the existing app.

Release APK output: `app/build/outputs/apk/release/app-release.apk`.

## Layout

| File | Purpose |
| --- | --- |
| `Config.kt` | Base URL, OAuth client ID, redirect URI, scopes |
| `SocialTapApi.kt` | REST calls, token refresh |
| `RealtimeClient.kt` | Socket.IO 4 client over OkHttp WebSocket |
| `AdpcmCodec.kt` | IMA ADPCM codec matching the web relay (unit-tested with golden bytes) |
| `CallManager.kt` | Call state machine, audio capture and playback |
| `MessageSync.kt` | Finds new messages and posts notifications |
| `RealtimeService.kt` / `CallService.kt` | Foreground services |
| `SyncWorker.kt` | 15-minute background sync |
| `MainActivity.kt` / `CallActivity.kt` | UI entry points, OAuth redirect, call screen |
