# TDLib integration

KotliGram resolves TDLib through the pinned Maven Central Android AAR:

`io.github.tdlib-android:core:0.1.1`

The AAR contains:
- `org.drinkless.tdlib.Client` and generated `TdApi` Java bindings;
- `libtdjni.so` native JNI library for `arm64-v8a`, `armeabi-v7a`, `x86_64` and `x86`.

This avoids manually committing multi-megabyte binary files and keeps the Java API and JNI library packaged together. Gradle downloads the AAR during the Android build. The published package documents its source provenance, ABI set and license notices: https://github.com/AkashPriyadarshii/tdlib-android

## KotliGram adapter

- `TdLibClient.kt` owns the Java TDLib client, sends asynchronous requests and exposes a hot Flow of TDLib updates.
- `TelegramRepository.kt` maps authorization, chat and message updates into app-facing StateFlows.
- Compose screens observe those StateFlows; UI code does not call TDLib directly.
- The repository handles phone login, code, 2FA password, database encryption key, QR login, chat loading, history, text, photo, document and voice-note sending.

## Credentials are deliberately absent

Telegram requires a unique `api_id` and `api_hash` for each application. They are not optional TDLib parameters. KotliGram does not reuse BirGram, Telegram X or any other app's credentials.

For local development, add these lines to the root `local.properties` file (already ignored by Git):

```properties
telegram.apiId=YOUR_API_ID
telegram.apiHash=YOUR_API_HASH
```

Or provide `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` environment variables to Gradle. BuildConfig defaults to `0` and an empty string when they are absent. In that case the app deliberately shows a credentials-required screen and does not start Telegram authorization.

## Compatibility and limitations

- The app's configured minSdk is 23. TDLib AAR supplies four ABIs; Gradle packages the matching binary for the target device.
- Voice recording uses Android MediaRecorder to create an AAC/M4A file. TDLib accepts M4A as a voice-note input format.
- The current app UI is an early functional layer, not a complete Telegram feature set. Download/playback of incoming media, pagination, search, replies, edits, reactions, calls and push notifications remain future work.

## References

- Official TDLib documentation: https://core.telegram.org/tdlib/
- Official Java API: https://core.telegram.org/tdlib/Java_API
- Official Android build example: https://github.com/tdlib/td/tree/master/example/android
- BirGram: https://github.com/GazizVR/BirGram
- Prebuilt Android AAR and ABI packaging: https://github.com/AkashPriyadarshii/tdlib-android

TDLib is distributed under the Boost Software License 1.0. The Android AAR project also provides its own license and provenance notices; review them before distributing KotliGram.
