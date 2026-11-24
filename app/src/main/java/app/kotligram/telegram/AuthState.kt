package app.kotligram.telegram

/**
 * UI-facing authorization states. These are independent of TDLib classes,
 * so Compose screens and ViewModels do not depend on JNI or generated TDLib types.
 */
sealed interface AuthState {
    data object Initializing : AuthState
    data object WaitingForDatabaseKey : AuthState
    data object WaitingForPhoneNumber : AuthState
    data class WaitingForCode(val phoneNumber: String, val deliveryHint: String? = null) : AuthState
    data object WaitingForPassword : AuthState
    data class WaitingForQrScan(val qrLink: String, val expiresAtEpochSeconds: Long? = null) : AuthState
    data object WaitingForQrConfirmation : AuthState
    data object Authorized : AuthState
    data class Error(val message: String, val canRetry: Boolean = true) : AuthState
}

/** Contract implemented by the real TDLib adapter. */
interface TelegramAuthRepository {
    val state: kotlinx.coroutines.flow.StateFlow<AuthState>

    suspend fun submitDatabaseKey(key: ByteArray)
    suspend fun submitPhoneNumber(phoneNumber: String)
    suspend fun submitCode(code: String)
    suspend fun submitTwoStepPassword(password: String)
    suspend fun requestQrLogin()
    suspend fun cancelQrLogin()
}
