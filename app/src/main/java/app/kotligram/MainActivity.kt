package app.kotligram

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kotligram.telegram.ConnectionState
import app.kotligram.telegram.TelegramChat
import app.kotligram.telegram.TelegramMessage
import app.kotligram.telegram.TelegramRepository
import app.kotligram.telegram.TelegramProfile
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KotliGramRoot() }
    }
}

@Composable
private fun KotliGramRoot() {
    val context = LocalContext.current
    val repository = remember {
        TelegramRepository(
            context = context,
            apiId = BuildConfig.TELEGRAM_API_ID,
            apiHash = BuildConfig.TELEGRAM_API_HASH
        )
    }
    DisposableEffect(repository) {
        onDispose { repository.close() }
    }
    val connection by repository.connection.collectAsState()
    val chats by repository.chats.collectAsState()
    val messages by repository.messages.collectAsState()
    val activeChat by repository.activeChat.collectAsState()
    val profile by repository.profile.collectAsState()
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = darkColorScheme()) {
        when (val state = connection) {
            ConnectionState.Starting -> CenterMessage("Запуск KotliGram…")
            ConnectionState.CredentialsRequired -> CredentialsScreen()
            ConnectionState.WaitingForDatabaseKey -> AuthForm(
                title = "Ключ базы данных",
                hint = "Если вы ранее задавали ключ шифрования, введите его",
                button = "Продолжить",
                error = error,
                onSubmit = { value -> scope.launch { runCatching { repository.submitDatabaseKey(value) }.onFailure { error = it.message.orEmpty() } } }
            )
            ConnectionState.WaitingForPhone -> AuthForm(
                title = "Вход в Telegram",
                hint = "Номер телефона в международном формате, например +380…",
                button = "Получить код",
                error = error,
                onQrLogin = { scope.launch { runCatching { repository.requestQrLogin() }.onFailure { error = it.message.orEmpty() } } },
                onSubmit = { value -> scope.launch { runCatching { repository.submitPhone(value) }.onSuccess { error = "" }.onFailure { error = it.message.orEmpty() } } }
            )
            is ConnectionState.WaitingForCode -> AuthForm(
                title = "Код подтверждения",
                hint = "Введите код из Telegram или SMS",
                button = "Подтвердить",
                error = error,
                onSubmit = { value -> scope.launch { runCatching { repository.submitCode(value) }.onSuccess { error = "" }.onFailure { error = it.message.orEmpty() } } }
            )
            ConnectionState.WaitingForPassword -> AuthForm(
                title = "Облачный пароль",
                hint = "Пароль двухэтапной аутентификации",
                button = "Войти",
                error = error,
                password = true,
                onSubmit = { value -> scope.launch { runCatching { repository.submitPassword(value) }.onSuccess { error = "" }.onFailure { error = it.message.orEmpty() } } }
            )
            is ConnectionState.WaitingForQr -> QrScreen(state.link)
            ConnectionState.Authorized -> {
                val selected = chats.firstOrNull { it.id == activeChat }
                if (selected == null) {
                    ChatListScreen(
                        chats = chats,
                        profileName = profile?.let { listOf(it.firstName, it.lastName).filter(String::isNotBlank).joinToString(" ") }.orEmpty(),
                        profile = profile,
                        onOpen = repository::openChat,
                        onRefresh = repository::loadChats
                    )
                } else {
                    ChatScreen(
                        chat = selected,
                        messages = messages,
                        onBack = { repository.closeChat(selected.id) },
                        onSend = { text ->
                            scope.launch {
                                runCatching { repository.sendText(selected.id, text) }
                                    .onFailure { error = it.message.orEmpty() }
                            }
                        },
                        onAttach = { uri ->
                            scope.launch {
                                val file = copyUriToCache(context, uri)
                                val mime = context.contentResolver.getType(uri).orEmpty()
                                runCatching {
                                    if (mime.startsWith("image/")) repository.sendPhoto(selected.id, file.absolutePath)
                                    else repository.sendDocument(selected.id, file.absolutePath)
                                }.onFailure { error = it.message.orEmpty() }
                            }
                        },
                        onVoice = { path, duration ->
                            scope.launch {
                                runCatching { repository.sendVoiceNote(selected.id, path, duration) }
                                    .onFailure { error = it.message.orEmpty() }
                            }
                        },
                        error = error
                    )
                }
            }
            is ConnectionState.Failed -> CenterMessage("Ошибка TDLib: ${state.reason}")
        }
    }
}

@Composable
private fun CredentialsScreen() {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("KotliGram", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("TDLib подключена как зависимость: Java/JNI bindings и нативные библиотеки поставляются Android AAR для ARM и x86.")
            Spacer(Modifier.height(18.dp))
            Text("Для соединения Telegram нужны собственные API ID и API HASH приложения. Ты просил не добавлять их — поэтому приложение не отправляет запросы авторизации и не использует чужие ключи.")
            Spacer(Modifier.height(18.dp))
            Text("Когда решишь подключить свои ключи, добавь в local.properties:")
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                Text(
                    "telegram.apiId=ТВОЙ_API_ID\ntelegram.apiHash=ТВОЙ_API_HASH",
                    modifier = Modifier.padding(14.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("Файл local.properties не должен попадать в Git. После добавления ключей пересобери приложение.")
        }
    }
}

@Composable
private fun AuthForm(
    title: String,
    hint: String,
    button: String,
    error: String,
    password: Boolean = false,
    onQrLogin: (() -> Unit)? = null,
    onSubmit: (String) -> Unit
) {
    var value by remember(title) { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(hint) },
            singleLine = true,
            visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
        )
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onSubmit(value) }, modifier = Modifier.fillMaxWidth(), enabled = value.isNotBlank()) {
            Text(button)
        }
        if (onQrLogin != null) {
            TextButton(onClick = onQrLogin, modifier = Modifier.fillMaxWidth()) { Text("Войти по QR-коду") }
        }
    }
}

@Composable
private fun QrScreen(link: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Подтверждение входа", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("Открой Telegram на уже авторизованном устройстве и подтверди вход по ссылке:")
        Spacer(Modifier.height(12.dp))
        SelectionContainer { Text(link) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListScreen(
    chats: List<TelegramChat>,
    profileName: String,
    profile: TelegramProfile?,
    onOpen: (Long) -> Unit,
    onRefresh: () -> Unit
) {
    var showProfile by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("KotliGram", fontWeight = FontWeight.SemiBold)
                        Text(profileName.ifBlank { "Telegram" }, style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    TextButton(onClick = onRefresh) { Text("Обновить") }
                    IconButton(onClick = { showProfile = true }) { Icon(Icons.Default.Person, contentDescription = "Профиль") }
                }
            )
        }
    ) { padding ->
        if (chats.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text("Чатов пока нет. Потяни обновление или подожди синхронизации.")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(chats, key = { it.id }) { chat ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(chat.id) },
                        headlineContent = { Text(chat.title, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(chat.lastMessage.ifBlank { "Нет сообщений" }, maxLines = 1) },
                        leadingContent = {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(48.dp)) {
                                Box(contentAlignment = Alignment.Center) { Text(chat.title.take(1).uppercase()) }
                            }
                        },
                        trailingContent = { if (chat.unreadCount > 0) Badge { Text(chat.unreadCount.toString()) } }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    if (showProfile) {
        AlertDialog(
            onDismissRequest = { showProfile = false },
            title = { Text("Профиль Telegram") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(profile?.let { "${it.firstName} ${it.lastName}".trim() } ?: "Профиль загружается")
                    Text(profile?.username?.let { "@$it" }?.takeIf(String::isNotBlank) ?: "Имя пользователя не задано")
                    Text(profile?.phoneNumber?.takeIf(String::isNotBlank) ?: "Номер телефона скрыт")
                    Text("ID: ${profile?.id ?: "—"}")
                }
            },
            confirmButton = { TextButton(onClick = { showProfile = false }) { Text("Закрыть") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    chat: TelegramChat,
    messages: List<TelegramMessage>,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onAttach: (Uri) -> Unit,
    onVoice: (String, Int) -> Unit,
    error: String
) {
    var text by remember(chat.id) { mutableStateOf("") }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingPath by remember { mutableStateOf<String?>(null) }
    var recordingStartedAt by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && recorder == null) {
            runCatching {
                val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
                val active = MediaRecorder()
                active.setAudioSource(MediaRecorder.AudioSource.MIC)
                active.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                active.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                active.setAudioChannels(1)
                active.setAudioSamplingRate(44100)
                active.setOutputFile(file.absolutePath)
                active.prepare()
                active.start()
                recorder = active
                recordingPath = file.absolutePath
                recordingStartedAt = System.currentTimeMillis()
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), onResult = { it?.let(onAttach) })
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chat.title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") } }
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { picker.launch("*/*") }) { Icon(Icons.Default.AttachFile, "Прикрепить файл") }
                TextButton(onClick = {
                    if (recorder == null) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        val active = recorder
                        val path = recordingPath
                        runCatching { active?.stop() }
                        active?.release()
                        recorder = null
                        if (path != null) {
                            val duration = ((System.currentTimeMillis() - recordingStartedAt) / 1000L).toInt()
                            if (duration > 0) onVoice(path, duration) else File(path).delete()
                        }
                    }
                }) { Text(if (recorder == null) "Голос" else "Стоп") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение") },
                    maxLines = 4
                )
                IconButton(
                    onClick = { val value = text.trim(); if (value.isNotEmpty()) { onSend(value); text = "" } },
                    enabled = text.isNotBlank()
                ) { Icon(Icons.Default.Send, "Отправить") }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) {
                        Surface(
                            color = if (message.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.large
                        ) {
                            Text(message.text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(); Text(text, modifier = Modifier.padding(top = 84.dp)) }
}

private fun copyUriToCache(context: android.content.Context, uri: Uri): File {
    val name = "upload_${System.currentTimeMillis()}"
    val target = File(context.cacheDir, name)
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Не удалось открыть выбранный файл" }.copyTo(target.outputStream())
    }
    return target
}
