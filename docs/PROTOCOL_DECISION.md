# Protocol decision: TDLib

## Decision

KotliGram uses the official Telegram Database Library (TDLib) as its Telegram client engine. The app UI and product logic remain our own Kotlin + Jetpack Compose implementation.

MTProto is the Telegram client-server protocol. TDLib is a client library that implements the protocol and provides a higher-level asynchronous API, ordered updates, encrypted local storage, networking, file transfers and Java/JNI bindings.

## Why TDLib

- Avoid reimplementing MTProto transport, encryption, data center migration, update sequencing and retry logic.
- Use the official Java interface and documented authorization state machine.
- Spend project effort on KotliGram UI, accessibility, chat experience and product features.
- Keep the protocol layer behind a Kotlin repository interface so the UI does not depend on TDLib classes.

## Trade-offs

- Native binaries and Java bindings must be built or bundled for each supported Android ABI.
- TDLib increases APK size and native build complexity.
- TDLib's API ID and API hash are still required. TDLib does not bypass Telegram application registration.

## References

- https://core.telegram.org/tdlib/
- https://core.telegram.org/tdlib/getting-started
- https://core.telegram.org/mtproto
- https://github.com/TGX-Android/Telegram-X
