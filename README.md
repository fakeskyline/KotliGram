# KotliGram

An independent Android Telegram client built with Kotlin, Jetpack Compose, Material 3 and TDLib.

## Current implementation

- Telegram sign-in using phone number, verification code, cloud password and QR confirmation.
- Live chat list and message history.
- Text, photo, document and recorded voice-note sending.
- Chat and channel avatars loaded through TDLib file downloads.
- Photo previews and manual download of incoming media to the device's Downloads/KotliGram folder.
- Channel posting permissions checked against the current user's channel membership status and administrator rights.
- Basic appearance and media auto-download settings.
- GitHub Actions debug APK build.

## Telegram API credentials

The app reads its own Telegram API credentials from local.properties or the TELEGRAM_API_ID and TELEGRAM_API_HASH environment variables. CI uses GitHub Actions repository secrets. Never commit credentials, Telegram session databases or encryption keys.

API HASH is required by the Telegram client and is embedded in the built APK. It is not stored in Git, but a determined person can extract it from a distributed APK; do not treat a client-side secret as impossible to recover.

## Build

- JDK 17
- Android SDK 35
- Gradle 8.9

Run:

```bash
gradle :app:assembleDebug
```

TDLib Java bindings and native libraries are vendored in app/libs/ and app/src/main/jniLibs/. GitHub Actions verifies the native payload and builds the debug APK.

## Current limitations

KotliGram is an early independent client, not a feature-complete Telegram replacement. Media playback, albums, message editing/forwarding/reactions, search across messages, chat folders, notifications, proxy controls, and multi-account management still need implementation. Build success does not by itself verify live Telegram behavior on a device.