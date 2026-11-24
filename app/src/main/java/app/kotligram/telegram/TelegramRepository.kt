package app.kotligram.telegram

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

data class TelegramChat(
    val id: Long,
    val title: String,
    val lastMessage: String = "",
    val unreadCount: Int = 0
)

data class TelegramMessage(
    val id: Long,
    val chatId: Long,
    val text: String,
    val outgoing: Boolean,
    val date: Int
)

data class TelegramProfile(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val username: String,
    val phoneNumber: String
)

sealed interface ConnectionState {
    data object Starting : ConnectionState
    data object CredentialsRequired : ConnectionState
    data object WaitingForDatabaseKey : ConnectionState
    data object WaitingForPhone : ConnectionState
    data class WaitingForCode(val phone: String) : ConnectionState
    data object WaitingForPassword : ConnectionState
    data class WaitingForQr(val link: String) : ConnectionState
    data object Authorized : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

class TelegramRepository(
    context: Context,
    apiId: Int = 0,
    apiHash: String = ""
) {
    private val client = TdLibClient(context, apiId, apiHash)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Starting)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _chats = MutableStateFlow<List<TelegramChat>>(emptyList())
    val chats: StateFlow<List<TelegramChat>> = _chats.asStateFlow()

    private val _messages = MutableStateFlow<List<TelegramMessage>>(emptyList())
    val messages: StateFlow<List<TelegramMessage>> = _messages.asStateFlow()

    private val _profile = MutableStateFlow<TelegramProfile?>(null)
    val profile: StateFlow<TelegramProfile?> = _profile.asStateFlow()

    private val _activeChat = MutableStateFlow<Long?>(null)
    val activeChat: StateFlow<Long?> = _activeChat.asStateFlow()

    init {
        client.start()
        if (!client.hasCredentials) _connection.value = ConnectionState.CredentialsRequired
        scope.launch {
            client.updates.collect(::handleUpdate)
        }
        if (client.hasCredentials) {
            scope.launch {
                try {
                    client.request(TdApi.GetAuthorizationState())
                } catch (e: Exception) {
                    _connection.value = ConnectionState.Failed(e.message ?: "TDLib initialization error")
                }
            }
        }
    }

    private fun handleUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> handleAuth(update.authorizationState)
            is TdApi.UpdateNewChat -> mergeChat(update.chat)
            is TdApi.UpdateChatTitle -> _chats.value = _chats.value.map {
                if (it.id == update.chatId) it.copy(title = update.title) else it
            }
            is TdApi.UpdateChatLastMessage -> {
                val preview = update.lastMessage?.let(::messagePreview).orEmpty()
                _chats.value = _chats.value.map {
                    if (it.id == update.chatId) it.copy(lastMessage = preview) else it
                }
            }
            is TdApi.UpdateNewMessage -> addMessage(update.message)
            is TdApi.UpdateMessageSendSucceeded -> replaceMessage(update.oldMessageId, update.message)
            is TdApi.UpdateMessageContent -> Unit
            is TdApi.UpdateDeleteMessages -> if (update.chatId == _activeChat.value) {
                _messages.value = _messages.value.filterNot { it.id in update.messageIds.toSet() }
            }
            is TdApi.UpdateUser -> if (update.user.id == _profile.value?.id) _profile.value = update.user.toProfile()
        }
    }

    private fun handleAuth(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                if (!client.hasCredentials) _connection.value = ConnectionState.CredentialsRequired
            }
            is TdApi.AuthorizationStateWaitEncryptionKey -> _connection.value = ConnectionState.WaitingForDatabaseKey
            is TdApi.AuthorizationStateWaitPhoneNumber -> _connection.value = ConnectionState.WaitingForPhone
            is TdApi.AuthorizationStateWaitCode -> _connection.value = ConnectionState.WaitingForCode("")
            is TdApi.AuthorizationStateWaitPassword -> _connection.value = ConnectionState.WaitingForPassword
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> _connection.value =
                ConnectionState.WaitingForQr(state.link)
            is TdApi.AuthorizationStateReady -> {
                _connection.value = ConnectionState.Authorized
                loadChats()
                loadProfile()
            }
            is TdApi.AuthorizationStateLoggingOut -> _connection.value = ConnectionState.Starting
            is TdApi.AuthorizationStateClosing -> _connection.value = ConnectionState.Starting
            is TdApi.AuthorizationStateClosed -> _connection.value = ConnectionState.Starting
        }
    }

    suspend fun submitDatabaseKey(key: String) {
        client.request(TdApi.CheckDatabaseEncryptionKey().apply { encryptionKey = key.toByteArray() })
    }

    suspend fun submitPhone(phone: String) {
        client.request(TdApi.SetAuthenticationPhoneNumber().apply { phoneNumber = phone; settings = null })
    }

    suspend fun submitCode(code: String) {
        client.request(TdApi.CheckAuthenticationCode().apply { this.code = code })
    }

    suspend fun submitPassword(password: String) {
        client.request(TdApi.CheckAuthenticationPassword().apply { this.password = password })
    }

    suspend fun requestQrLogin() {
        client.request(TdApi.RequestQrCodeAuthentication().apply { otherUserIds = longArrayOf() })
    }

    fun loadChats() {
        scope.launch {
            try {
                client.request(TdApi.LoadChats().apply { chatList = TdApi.ChatListMain(); limit = 100 })
                val result = client.request(TdApi.GetChats().apply { chatList = TdApi.ChatListMain(); limit = 100 })
                result.chatIds.forEach { id ->
                    try {
                        val chat = client.request(TdApi.GetChat(id))
                        mergeChat(chat)
                    } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                _connection.value = ConnectionState.Failed(e.message ?: "Не удалось загрузить чаты")
            }
        }
    }

    fun openChat(chatId: Long) {
        _activeChat.value = chatId
        _messages.value = emptyList()
        scope.launch {
            try {
                client.request(TdApi.OpenChat().apply { this.chatId = chatId })
                val history = client.request(TdApi.GetChatHistory().apply { this.chatId = chatId; fromMessageId = 0L; offset = 0; limit = 50; onlyLocal = false })
                _messages.value = history.messages.map(::toMessage).sortedBy { it.date }
            } catch (_: Exception) { }
        }
    }

    suspend fun sendText(chatId: Long, text: String) {
        val content = TdApi.InputMessageText().apply {
            this.text = TdApi.FormattedText(text, emptyArray())
        }
        client.request(TdApi.SendMessage().apply { this.chatId = chatId; inputMessageContent = content })
    }

    suspend fun sendPhoto(chatId: Long, path: String) {
        val content = TdApi.InputMessagePhoto().apply {
            photo = TdApi.InputFileLocal(path)
        }
        client.request(TdApi.SendMessage(chatId, null, null, null, null, content))
    }

    suspend fun sendDocument(chatId: Long, path: String) {
        val content = TdApi.InputMessageDocument().apply {
            document = TdApi.InputFileLocal(path)
        }
        client.request(TdApi.SendMessage(chatId, null, null, null, null, content))
    }

    fun loadProfile() {
        scope.launch {
            try {
                _profile.value = client.request(TdApi.GetMe()).toProfile()
            } catch (_: Exception) { }
        }
    }

    private fun mergeChat(chat: TdApi.Chat) {
        val item = TelegramChat(
            id = chat.id,
            title = chat.title,
            lastMessage = chat.lastMessage?.let(::messagePreview).orEmpty(),
            unreadCount = chat.unreadCount
        )
        _chats.value = (listOf(item) + _chats.value.filterNot { it.id == item.id })
            .sortedByDescending { it.id == item.id }
    }

    private fun addMessage(message: TdApi.Message) {
        if (message.chatId != _activeChat.value) return
        _messages.value = (_messages.value.filterNot { it.id == message.id } + toMessage(message))
            .sortedBy { it.date }
    }

    private fun replaceMessage(oldId: Long, message: TdApi.Message) {
        if (message.chatId != _activeChat.value) return
        _messages.value = (_messages.value.filterNot { it.id == oldId || it.id == message.id } + toMessage(message))
            .sortedBy { it.date }
    }

    private fun toMessage(message: TdApi.Message) = TelegramMessage(
        id = message.id,
        chatId = message.chatId,
        text = messagePreview(message),
        outgoing = message.isOutgoing,
        date = message.date
    )

    private fun messagePreview(message: TdApi.Message): String = when (val content = message.content) {
        is TdApi.MessageText -> content.text.text
        is TdApi.MessagePhoto -> "📷 Фото"
        is TdApi.MessageVideo -> "🎬 Видео"
        is TdApi.MessageDocument -> "📎 Файл"
        is TdApi.MessageVoiceNote -> "🎤 Голосовое сообщение"
        else -> "Сообщение"
    }

    private fun TdApi.User.toProfile() = TelegramProfile(
        id = id,
        firstName = firstName,
        lastName = lastName,
        username = usernames?.activeUsernames?.firstOrNull().orEmpty(),
        phoneNumber = phoneNumber
    )

    fun close() = client.close()
}
