# KotliGram

A native Telegram client for Android, written from scratch with Kotlin and Jetpack Compose Material 3.

## Stack
- Kotlin, Jetpack Compose, Material 3
- MVVM, Coroutines and StateFlow
- TDLib (Telegram Database Library) for MTProto, authorization, chats and media
- GitHub Actions for Android CI

## Status
Project bootstrap. The UI shell is being built before connecting the TDLib runtime.

## Telegram API credentials
Create an application at https://my.telegram.org/apps. Put `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` in your local `local.properties` (never commit this file) or GitHub Actions secrets. Do not share these credentials.

## Build
Open the project in Android Studio (JDK 17, Android SDK 35) and run the `:app:assembleDebug` task.

TDLib native binaries must be built or supplied for each Android ABI. They are not checked into this initial source scaffold; see `docs/TDLIB.md`.
