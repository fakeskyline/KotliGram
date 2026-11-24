# TDLib integration

KotliGram will use the official TDLib Java interface and Android native libraries.

References:
- https://core.telegram.org/tdlib
- https://github.com/tdlib/td
- https://github.com/tdlib/td/tree/master/example/android
- https://github.com/TGX-Android/tdlib (Telegram X's prebuilt bundle; its maintainers explicitly state it is intended for Telegram X and may contain unstable changes)

We should build the official TDLib Android example or consume a pinned, reproducible build for each ABI (armeabi-v7a, arm64-v8a, x86_64). Do not copy arbitrary .so files from another APK: the Java bindings and native library versions must match.

The app's first TDLib milestone is an authentication state machine:
WaitTdlibParameters -> setTdlibParameters -> CheckDatabaseEncryptionKey -> phone number -> code -> optional password (2FA).
QR login is a separate TDLib authorization flow using a link token that must be displayed as a QR code and refreshed when TDLib requests it.

Never commit API credentials, account data, encryption keys, session databases, or signing keys.
