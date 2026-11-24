package app.kotligram.telegram

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import org.drinkless.tdlib.TdApi

data class TelegramChat(
    val id: Long,
    val title: String,
    val lastMessage: String = "",
    val unreadCount: Int = 0,
    val avatarFileId: Int? = null,
    val avatarPath: String? = null,
    val canSendMessages: Boolean = true,
    val isChannel: Boolean = false
)

data class TelegramMessage(
    val id: Long,
    val chatId: Long,
    val text: String,
    val outgoing: Boolean,
    val date: Int,
    val mediaKind: String? = null,
    val mediaFileId: Int? = null,
    val mediaPath: String? = null,
    val caption: String = ""
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
    private val preferences = context.applicationContext.getSharedPreferences("kotligram_settings", Context.MODE_PRIVATE)
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
    private var myUserId: Long = 0L
    private val requestedFileDownloads = ConcurrentHashMap.newKeySet<Int>()

    init {
        client.start()
        if (!client.hasCredentials) _connection.value = ConnectionState.CredentialsRequired
        scope.launch { client.updates.collect(::handleUpdate) }
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
            is TdApi.UpdateChatPhoto -> scope.launch {
                runCatching { client.request(TdApi.GetChat(update.chatId)) }
                    .onSuccess { chat ->
                        mergeChat(chat)
                    }
            }
            is TdApi.UpdateChatPermissions -> _chats.value = _chats.value.map {
                if (it.id == update.chatId && !it.isChannel) it.copy(canSendMessages = update.permissions.canSendBasicMessages) else it
            }
            is TdApi.UpdateChatLastMessage -> {
                val preview = update.lastMessage?.let(::messagePreview).orEmpty()
                _chats.value = _chats.value.map {
                    if (it.id == update.chatId) it.copy(lastMessage = preview) else it
                }
            }
            is TdApi.UpdateNewMessage -> addMessage(update.message)
            is TdApi.UpdateMessageSendSucceeded -> replaceMessage(update.oldMessageId, update.message)
            is TdApi.UpdateMessageContent -> updateMessageContent(update)
            is TdApi.UpdateDeleteMessages -> if (update.chatId == _activeChat.value) {
                val deleted = update.messageIds.toSet()
                _messages.value = _messages.value.filterNot { it.id in deleted }
            }
            is TdApi.UpdateUser -> if (update.user.id == _profile.value?.id) {
                _profile.value = update.user.toProfile()
            }
            is TdApi.UpdateFile -> updateDownloadedFile(update.file)
        }
    }

    private fun handleAuth(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                if (!client.hasCredentials) _connection.value = ConnectionState.CredentialsRequired
            }
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
            is TdApi.AuthorizationStateLoggingOut,
            is TdApi.AuthorizationStateClosing,
            is TdApi.AuthorizationStateClosed -> _connection.value = ConnectionState.Starting
        }
    }

    suspend fun submitPhone(phone: String) {
        client.request(TdApi.SetAuthenticationPhoneNumber().apply {
            phoneNumber = phone
            settings = null
        })
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
                if (myUserId == 0L) {
                    runCatching { client.request(TdApi.GetMe()) }.getOrNull()?.let { user ->
                        myUserId = user.id
                        _profile.value = user.toProfile()
                    }
                }
                client.request(TdApi.LoadChats().apply {
                    chatList = TdApi.ChatListMain()
                    limit = 100
                })
                val result = client.request(TdApi.GetChats().apply {
                    chatList = TdApi.ChatListMain()
                    limit = 100
                })
                result.chatIds.forEach { id ->
                    runCatching { client.request(TdApi.GetChat(id)) }
                        .onSuccess { chat ->
                            mergeChat(chat)
                            refreshChatPermissions(chat)
                        }
                }
            } catch (e: Exception) {
                _connection.value = ConnectionState.Failed(e.message ?: "Не удалось загрузить чаты")
            }
        }
    }

    private suspend fun refreshChatPermissions(chat: TdApi.Chat) {
        val supergroupType = chat.type as? TdApi.ChatTypeSupergroup
        if (supergroupType == null) {
            val allowed = chat.permissions.canSendBasicMessages
            updateChat(chat.id) { it.copy(canSendMessages = allowed, isChannel = false) }
            return
        }

        val supergroup = runCatching {
            client.request(TdApi.GetSupergroup(supergroupType.supergroupId))
        }.getOrNull()
        val isChannel = supergroup?.isChannel == true
        if (!isChannel) {
            updateChat(chat.id) {
                it.copy(canSendMessages = chat.permissions.canSendBasicMessages, isChannel = false)
            }
            return
        }

        val allowed = if (myUserId == 0L) false else {
            val member = runCatching {
                client.request(
                    TdApi.GetChatMember(
                        chat.id,
                        TdApi.MessageSenderUser(myUserId)
                    )
                )
            }.getOrNull()
            when (val status = member?.status) {
                is TdApi.ChatMemberStatusCreator -> true
                is TdApi.ChatMemberStatusAdministrator -> status.rights.canPostMessages
                else -> false
            }
        }
        updateChat(chat.id) { it.copy(canSendMessages = allowed, isChannel = true) }
    }

    private fun updateChat(chatId: Long, transform: (TelegramChat) -> TelegramChat) {
        _chats.value = _chats.value.map { if (it.id == chatId) transform(it) else it }
    }

    fun openChat(chatId: Long) {
        _activeChat.value = chatId
        _messages.value = emptyList()
        scope.launch {
            try {
                client.request(TdApi.OpenChat().apply { this.chatId = chatId })
                val history = client.request(TdApi.GetChatHistory().apply {
                    this.chatId = chatId
                    fromMessageId = 0L
                    offset = 0
                    limit = 50
                    onlyLocal = false
                })
                _messages.value = history.messages.map(::toMessage).sortedBy { it.date }
                if (preferences.getBoolean("auto_download_photos", true)) {
                    _messages.value.forEach { message ->
                        message.mediaFileId?.let { if (message.mediaKind == "photo") scope.launch { runCatching { downloadFile(it) } } }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("KotliGram", "Unable to open chat", e)
            }
        }
    }

    fun closeChat(chatId: Long) {
        scope.launch { runCatching { client.request(TdApi.CloseChat().apply { this.chatId = chatId }) } }
        _activeChat.value = null
        _messages.value = emptyList()
    }

    suspend fun sendText(chatId: Long, text: String) {
        requireCanSend(chatId)
        val content = TdApi.InputMessageText().apply {
            this.text = TdApi.FormattedText(text, emptyArray())
        }
        client.request(TdApi.SendMessage().apply {
            this.chatId = chatId
            inputMessageContent = content
        })
    }

    suspend fun sendPhoto(chatId: Long, path: String) {
        requireCanSend(chatId)
        val content = TdApi.InputMessagePhoto().apply {
            photo = TdApi.InputPhoto().apply {
                photo = TdApi.InputFileLocal(path)
                addedStickerFileIds = intArrayOf()
                width = 0
                height = 0
            }
        }
        client.request(TdApi.SendMessage(chatId, null, null, null, null, content))
    }

    suspend fun sendDocument(chatId: Long, path: String) {
        requireCanSend(chatId)
        val content = TdApi.InputMessageDocument().apply {
            document = TdApi.InputDocument().apply {
                document = TdApi.InputFileLocal(path)
                disableContentTypeDetection = false
            }
        }
        client.request(TdApi.SendMessage(chatId, null, null, null, null, content))
    }

    suspend fun sendVoiceNote(chatId: Long, path: String, durationSeconds: Int) {
        requireCanSend(chatId)
        val content = TdApi.InputMessageVoiceNote().apply {
            voiceNote = TdApi.InputVoiceNote().apply {
                voiceNote = TdApi.InputFileLocal(path)
                duration = durationSeconds.coerceAtLeast(1)
                waveform = ByteArray(0)
            }
        }
        client.request(TdApi.SendMessage(chatId, null, null, null, null, content))
    }

    private fun requireCanSend(chatId: Long) {
        val chat = _chats.value.firstOrNull { it.id == chatId }
            ?: throw IllegalStateException("Чат ещё не загружен")
        if (!chat.canSendMessages) {
            throw IllegalStateException(
                if (chat.isChannel) "В этом канале публиковать могут только администраторы с правом публикации."
                else "У вас нет разрешения отправлять сообщения в этот чат."
            )
        }
    }

    fun ensureFileDownloaded(fileId: Int) {
        if (!requestedFileDownloads.add(fileId)) return
        scope.launch {
            try {
                val file = client.request(TdApi.GetFile(fileId))
                if (file.local.isDownloadingCompleted) {
                    updateDownloadedFile(file)
                } else if (!file.local.isDownloadingActive) {
                    client.request(TdApi.DownloadFile(fileId, 32, 0L, 0L, false))
                }
            } catch (error: Exception) {
                requestedFileDownloads.remove(fileId)
                android.util.Log.w("KotliGram", "Avatar download request failed: $fileId", error)
            }
        }
    }

    suspend fun downloadFile(fileId: Int): String? {
        var file = runCatching { client.request(TdApi.GetFile(fileId)) }.getOrNull() ?: return null
        if (file.local.isDownloadingCompleted) return file.local.path
        client.request(TdApi.DownloadFile(fileId, 16, 0L, 0L, false))
        repeat(600) {
            delay(500)
            file = client.request(TdApi.GetFile(fileId))
            if (file.local.isDownloadingCompleted) {
                updateDownloadedFile(file)
                return file.local.path.takeIf(String::isNotBlank)
            }
        }
        return null
    }

    private fun updateDownloadedFile(file: TdApi.File) {
        if (file.local.isDownloadingCompleted) requestedFileDownloads.remove(file.id)
        val path = file.local.path.takeIf { file.local.isDownloadingCompleted && it.isNotBlank() } ?: return
        _chats.value = _chats.value.map {
            if (it.avatarFileId == file.id) it.copy(avatarPath = path) else it
        }
        _messages.value = _messages.value.map {
            if (it.mediaFileId == file.id) it.copy(mediaPath = path) else it
        }
    }

    fun loadProfile() {
        scope.launch {
            try {
                val user = client.request(TdApi.GetMe())
                myUserId = user.id
                _profile.value = user.toProfile()
                _chats.value.toList().forEach { item ->
                    val chat = runCatching { client.request(TdApi.GetChat(item.id)) }.getOrNull()
                    if (chat != null) refreshChatPermissions(chat)
                }
            } catch (e: Exception) {
                android.util.Log.e("KotliGram", "Unable to load profile", e)
            }
        }
    }

    private fun mergeChat(chat: TdApi.Chat) {
        val previous = _chats.value.firstOrNull { it.id == chat.id }
        val avatarId = chat.photo?.small?.id
        val item = TelegramChat(
            id = chat.id,
            title = chat.title,
            lastMessage = chat.lastMessage?.let(::messagePreview).orEmpty(),
            unreadCount = chat.unreadCount,
            avatarFileId = avatarId,
            avatarPath = chat.photo?.small?.local?.let { local ->
                local.path.takeIf { local.isDownloadingCompleted && it.isNotBlank() }
            } ?: previous?.takeIf { it.avatarFileId == avatarId }?.avatarPath,
            canSendMessages = previous?.canSendMessages ?: chat.permissions.canSendBasicMessages,
            isChannel = previous?.isChannel ?: false
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

    private fun updateMessageContent(update: TdApi.UpdateMessageContent) {
        if (update.chatId != _activeChat.value) return
        val current = _messages.value.firstOrNull { it.id == update.messageId } ?: return
        scope.launch {
            runCatching { client.request(TdApi.GetMessage(update.chatId, update.messageId)) }
                .onSuccess { message ->
                    _messages.value = _messages.value.map {
                        if (it.id == current.id) toMessage(message) else it
                    }
                }
        }
    }

    private fun toMessage(message: TdApi.Message): TelegramMessage {
        val content = message.content
        val media = when (content) {
            is TdApi.MessagePhoto -> "photo" to content.photo.sizes.maxByOrNull { it.width.toLong() * it.height }?.photo
            is TdApi.MessageVideo -> "video" to content.video.video
            is TdApi.MessageDocument -> "document" to content.document.document
            is TdApi.MessageVoiceNote -> "voice" to content.voiceNote.voice
            is TdApi.MessageAudio -> "audio" to content.audio.audio
            is TdApi.MessageAnimation -> "animation" to content.animation.animation
            is TdApi.MessageSticker -> "sticker" to content.sticker.sticker
            else -> null to null
        }
        val caption = when (content) {
            is TdApi.MessagePhoto -> content.caption.text
            is TdApi.MessageVideo -> content.caption.text
            is TdApi.MessageDocument -> content.caption.text
            is TdApi.MessageAnimation -> content.caption.text
            else -> ""
        }
        val mediaFile = media.second
        return TelegramMessage(
            id = message.id,
            chatId = message.chatId,
            text = messagePreview(message),
            outgoing = message.isOutgoing,
            date = message.date,
            mediaKind = media.first,
            mediaFileId = mediaFile?.id,
            mediaPath = mediaFile?.local?.let { local ->
                local.path.takeIf { local.isDownloadingCompleted && it.isNotBlank() }
            },
            caption = caption
        )
    }

    private fun messagePreview(message: TdApi.Message): String = when (val content = message.content) {
        is TdApi.MessageText -> content.text.text
        is TdApi.MessagePhoto -> "📷 Фото"
        is TdApi.MessageVideo -> "🎬 Видео"
        is TdApi.MessageDocument -> "📎 ${content.document.fileName}"
        is TdApi.MessageVoiceNote -> "🎤 Голосовое сообщение"
        is TdApi.MessageAudio -> "🎵 ${content.audio.title.ifBlank { "Аудио" }}"
        is TdApi.MessageAnimation -> "GIF"
        is TdApi.MessageSticker -> content.sticker.emoji.ifBlank { "Стикер" }
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
