# KotliGram authentication

Authentication is handled by TDLib authorization updates. The UI must react to TDLib state updates; it must not implement Telegram's cryptography or send login codes itself.

## Supported user flows

### Phone number
1. TDLib requests application parameters.
2. TDLib requests the local database encryption key.
3. User enters a phone number in international format.
4. TDLib sends a Telegram login code using the delivery method selected by Telegram.
5. User enters the code.
6. If Telegram requests it, user enters the 2-step verification password.
7. On authorization success, load chats and profile.

### QR code
1. User chooses the QR login option.
2. KotliGram asks TDLib to request QR authentication.
3. TDLib returns an authorization link/token for display as a QR code.
4. The user scans it in an already logged-in Telegram app.
5. KotliGram handles QR refresh/expiry and authorization updates until success or cancellation.

QR authentication is not a way to avoid Telegram authorization. It authorizes the new client through an already logged-in Telegram session.

## TDLib API operations to wire in the adapter

- setTdlibParameters
- checkDatabaseEncryptionKey
- setAuthenticationPhoneNumber
- checkAuthenticationCode
- checkAuthenticationPassword
- requestQrCodeAuthentication
- authorizationStateWaitOtherDeviceConfirmation
- authorizationStateWaitCode
- authorizationStateWaitPassword
- authorizationStateReady

Exact authorization transitions are controlled by Telegram/TDLib updates. Do not assume a fixed code delivery method or a fixed sequence after phone number entry.

## Credentials policy

Telegram requires an API ID and API hash for each application. KotliGram must use credentials registered for KotliGram by its maintainers. Do not copy credentials from Telegram X, Telegram Android, or another third-party client. Do not commit credentials, user sessions, database encryption keys, or signing keys.

For local builds, credentials can be supplied through untracked local.properties or environment variables. For CI, use repository Actions secrets. No credentials are needed to compile the UI-only project scaffold.

Official references:
- https://core.telegram.org/api/obtaining_api_id
- https://core.telegram.org/api/auth
- https://core.telegram.org/tdlib/getting-started
- https://core.telegram.org/tdlib/docs/
