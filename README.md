# KotliGram

An independent Android Telegram client built with Kotlin, Jetpack Compose and Material 3.

## Current implementation

- TDLib Android runtime dependency: Java/JNI bindings plus precompiled `libtdjni.so` for ARM and x86 ABIs, resolved from Maven Central.
- A Kotlin TDLib client wrapper with coroutine requests and a Flow update stream.
- Authorization state handling for phone number, verification code, cloud password, database encryption key and QR confirmation.
- Live chat list and message history through TDLib updates.
- Text, photo, document and recorded voice-note sending.
- Basic Telegram profile details.
- GitHub Actions debug APK build.

## Telegram API credentials

KotliGram intentionally contains **no API ID or API HASH**. Telegram requires every client application to use its own credentials. Do not copy credentials from BirGram, Telegram X or another app.

To enable a real Telegram connection, register your app at https://my.telegram.org/apps and place these values in the root `local.properties` file:

```properties
telegram.apiId=YOUR_API_ID
telegram.apiHash=YOUR_API_HASH
```

Alternatively, provide `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` as environment variables in CI. Never commit credentials, Telegram session databases or encryption keys. Without credentials, KotliGram builds and shows the configuration screen, but it cannot authenticate or load account data.

## Build

- JDK 17
- Android SDK 35
- Gradle 8.9

Run:

```bash
gradle :app:assembleDebug
```

TDLib is consumed as a pinned Maven Central Android AAR (`io.github.tdlib-android:core:0.1.1`). The AAR supplies the generated Java API and native libraries; no local NDK build is needed. The binary library is resolved by Gradle and is not copied into the Git repository.

## Architecture notes

See [TDLib integration](docs/TDLIB.md), [authentication](docs/AUTHENTICATION.md), and [protocol decision](docs/PROTOCOL_DECISION.md).

BirGram was studied as an architectural reference: its separate TDLib client manager, update dispatching, repository/service boundary and StateFlow-driven UI informed this implementation. KotliGram does not copy BirGram's application source.
