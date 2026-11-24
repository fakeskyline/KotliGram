package app.kotligram.telegram

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TdLibClient(
    context: Context,
    private val apiId: Int,
    private val apiHash: String
) {
    private val appContext = context.applicationContext
    private var client: Client? = null
    private val _updates = MutableSharedFlow<TdApi.Update>(
        replay = 1,
        extraBufferCapacity = 255,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val updates = _updates.asSharedFlow()

    val hasCredentials: Boolean
        get() = apiId > 0 && apiHash.isNotBlank()

    fun start() {
        if (client != null) return
        client = Client.create(
            { obj -> if (obj is TdApi.Update) _updates.tryEmit(obj) },
            { error -> android.util.Log.e("KotliGram-TDLib", "Update callback", error) },
            { error -> android.util.Log.e("KotliGram-TDLib", "TDLib callback", error) }
        )
        if (!hasCredentials) return
        val root = appContext.filesDir.resolve("kotligram-tdlib")
        val parameters = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = root.absolutePath
            filesDirectory = root.resolve("files").absolutePath
            databaseEncryptionKey = ByteArray(0)
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            this.apiId = this@TdLibClient.apiId
            this.apiHash = this@TdLibClient.apiHash
            systemLanguageCode = "ru"
            deviceModel = Build.MODEL ?: "Android"
            systemVersion = Build.VERSION.RELEASE ?: "Android"
            applicationVersion = "0.1.0"
        }
        send(parameters) { result ->
            if (result is TdApi.Error) {
                android.util.Log.e("KotliGram-TDLib", "setTdlibParameters: ${result.code} ${result.message}")
            }
        }
    }

    fun <T : TdApi.Object> send(
        function: TdApi.Function<T>,
        callback: (TdApi.Object) -> Unit
    ) {
        val active = client ?: throw IllegalStateException("TDLib client is not started")
        active.send(function) { result -> callback(result) }
    }

    suspend fun <T : TdApi.Object> request(function: TdApi.Function<T>): T =
        withContext(Dispatchers.IO) {
            val active = client ?: throw IllegalStateException("TDLib client is not started")
            suspendCancellableCoroutine { continuation ->
                active.send(function) { result ->
                    if (continuation.isActive) {
                        if (result is TdApi.Error) {
                            continuation.resumeWithException(TdLibException(result.code, result.message))
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            continuation.resume(result as T)
                        }
                    }
                }
            }
        }

    fun close() {
        val active = client ?: return
        client = null
        try {
            active.send(TdApi.Close(), null)
        } catch (_: Exception) {
        }
    }
}

class TdLibException(val code: Int, override val message: String) : Exception(message)
