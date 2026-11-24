package app.kotligram

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.kotligram.telegram.ConnectionState
import app.kotligram.telegram.TelegramChat
import app.kotligram.telegram.TelegramMessage
import app.kotligram.telegram.TelegramProfile
import app.kotligram.telegram.TelegramRepository
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val preferences = remember { context.getSharedPreferences("kotligram_settings", Context.MODE_PRIVATE) }
    val repository = remember {
        TelegramRepository(
            context = context,
            apiId = BuildConfig.TELEGRAM_API_ID,
            apiHash = BuildConfig.TELEGRAM_API_HASH
        )
    }
    DisposableEffect(repository) { onDispose { repository.close() } }

    val connection by repository.connection.collectAsState()
    val chats by repository.chats.collectAsState()
    val messages by repository.messages.collectAsState()
    val activeChat by repository.activeChat.collectAsState()
    val profile by repository.profile.collectAsState()
    var error by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var darkTheme by remember { mutableStateOf(preferences.getBoolean("dark_theme", true)) }
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        when (val state = connection) {
            ConnectionState.Starting -> CenterMessage("Запуск KotliGram…")
            ConnectionState.CredentialsRequired -> CredentialsScreen()
            ConnectionState.WaitingForPhone -> AuthForm(
                title = "Вход в Telegram",
                hint = "Номер телефона в международном формате, например +380…",
                button = "Получить код",
                error = error,
                onQrLogin = {
                    scope.launch {
                        runCatching { repository.requestQrLogin() }
                            .onFailure { error = it.message.orEmpty() }
                    }
                },
                onSubmit = { value ->
                    scope.launch {
                        runCatching { repository.submitPhone(value) }
                            .onSuccess { error = "" }
                            .onFailure { error = it.message.orEmpty() }
                    }
                }
            )
            is ConnectionState.WaitingForCode -> AuthForm(
                title = "Код подтверждения",
                hint = "Введите код из Telegram или SMS",
                button = "Подтвердить",
                error = error,
                onSubmit = { value ->
                    scope.launch {
                        runCatching { repository.submitCode(value) }
                            .onSuccess { error = "" }
                            .onFailure { error = it.message.orEmpty() }
                    }
                }
            )
            ConnectionState.WaitingForPassword -> AuthForm(
                title = "Облачный пароль",
                hint = "Пароль двухэтапной аутентификации",
                button = "Войти",
                error = error,
                password = true,
                onSubmit = { value ->
                    scope.launch {
                        runCatching { repository.submitPassword(value) }
                            .onSuccess { error = "" }
                            .onFailure { error = it.message.orEmpty() }
                    }
                }
            )
            is ConnectionState.WaitingForQr -> QrScreen(state.link)
            ConnectionState.Authorized -> {
                if (showSettings) {
                    SettingsScreen(
                        profile = profile,
                        darkTheme = darkTheme,
                        autoDownloadPhotos = preferences.getBoolean("auto_download_photos", true),
                        onBack = { showSettings = false },
                        onDarkTheme = {
                            darkTheme = it
                            preferences.edit().putBoolean("dark_theme", it).apply()
                        },
                        onAutoDownloadPhotos = {
                            preferences.edit().putBoolean("auto_download_photos", it).apply()
                        }
                    )
                } else {
                    val selected = chats.firstOrNull { it.id == activeChat }
                    if (selected == null) {
                        ChatListScreen(
                            chats = chats,
                            profileName = profile?.let {
                                listOf(it.firstName, it.lastName).filter(String::isNotBlank).joinToString(" ")
                            }.orEmpty(),
                            profile = profile,
                            onOpen = repository::openChat,
                            onRefresh = repository::loadChats,
                            onSettings = { showSettings = true }
                        )
                    } else {
                        ChatScreen(
                            chat = selected,
                            messages = messages,
                            onBack = { repository.closeChat(selected.id) },
                            onSend = { value ->
                                scope.launch {
                                    runCatching { repository.sendText(selected.id, value) }
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
                            onDownload = { fileId ->
                                scope.launch {
                                    runCatching {
                                        val path = repository.downloadFile(fileId)
                                            ?: error("Файл не удалось загрузить")
                                        saveToDownloads(context, path)
                                    }.onFailure { error = it.message.orEmpty() }
                                }
                            },
                            error = error
                        )
                    }
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
            Text("Для подключения Telegram необходимы API ID и API HASH приложения.")
            Spacer(Modifier.height(12.dp))
            Text("Секреты уже передаются в сборку через GitHub Actions. Не добавляй их в исходный код или Git.")
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
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(hint) },
            singleLine = true,
            visualTransformation = if (password) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            }
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
    val qrBitmap = remember(link) {
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 640, 640)
        val pixels = IntArray(640 * 640)
        for (y in 0 until 640) for (x in 0 until 640) {
            pixels[y * 640 + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, 640, 0, 0, 640, 640)
        }
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Подтверждение входа", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("Открой Telegram на уже авторизованном устройстве и отсканируй этот код:")
        Spacer(Modifier.height(12.dp))
        Image(qrBitmap.asImageBitmap(), contentDescription = "QR-код входа", modifier = Modifier.size(260.dp))
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
    onRefresh: () -> Unit,
    onSettings: () -> Unit
) {
    var showProfile by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val visibleChats = remember(chats, search) {
        if (search.isBlank()) chats else chats.filter { it.title.contains(search, ignoreCase = true) }
    }
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
                    IconButton(onClick = { showProfile = true }) {
                        Icon(Icons.Default.Person, contentDescription = "Профиль")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                placeholder = { Text("Поиск чатов") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )
            if (visibleChats.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(if (chats.isEmpty()) "Синхронизация чатов…" else "Ничего не найдено")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visibleChats, key = { it.id }) { chat ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(chat.id) }.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PeerAvatar(chat = chat, size = 54.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(chat.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    chat.lastMessage.ifBlank { "Нет сообщений" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (chat.unreadCount > 0) {
                                Badge { Text(chat.unreadCount.toString()) }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(start = 80.dp))
                    }
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

@Composable
private fun PeerAvatar(chat: TelegramChat, size: androidx.compose.ui.unit.Dp) {
    val bitmap by produceState<Bitmap?>(initialValue = null, chat.avatarPath) {
        value = if (chat.avatarPath.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(chat.avatarPath)
        }
    }
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(bitmap!!.asImageBitmap(), contentDescription = chat.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(chat.title.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    profile: TelegramProfile?,
    darkTheme: Boolean,
    autoDownloadPhotos: Boolean,
    onBack: () -> Unit,
    onDarkTheme: (Boolean) -> Unit,
    onAutoDownloadPhotos: (Boolean) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            item {
                ListItem(
                    headlineContent = { Text(profile?.let { "${it.firstName} ${it.lastName}".trim() } ?: "Мой профиль") },
                    supportingContent = { Text(profile?.username?.let { "@$it" } ?: "Telegram") },
                    leadingContent = {
                        Surface(Modifier.size(48.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, contentDescription = null) }
                        }
                    }
                )
            }
            item { SettingsHeader("Внешний вид") }
            item {
                ListItem(
                    headlineContent = { Text("Тёмная тема") },
                    supportingContent = { Text("Переключение светлого и тёмного оформления") },
                    trailingContent = { Switch(checked = darkTheme, onCheckedChange = onDarkTheme) }
                )
            }
            item { SettingsHeader("Данные и память") }
            item {
                ListItem(
                    headlineContent = { Text("Автозагрузка фотографий") },
                    supportingContent = { Text("Загружать фотографии при открытии переписки") },
                    trailingContent = { Switch(checked = autoDownloadPhotos, onCheckedChange = onAutoDownloadPhotos) }
                )
            }
            item { SettingsHeader("Аккаунт") }
            item {
                ListItem(
                    headlineContent = { Text("Номер телефона") },
                    supportingContent = { Text(profile?.phoneNumber?.ifBlank { "Скрыт" } ?: "Загрузка…") }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Имя пользователя") },
                    supportingContent = { Text(profile?.username?.takeIf(String::isNotBlank)?.let { "@$it" } ?: "Не задано") }
                )
            }
            item { SettingsHeader("О приложении") }
            item {
                ListItem(
                    headlineContent = { Text("KotliGram") },
                    supportingContent = { Text("Версия 0.2.0 · Независимый клиент Telegram на TDLib") }
                )
            }
        }
    }
}

@Composable
private fun SettingsHeader(title: String) {
    Text(
        title,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
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
    onDownload: (Int) -> Unit,
    error: String
) {
    var text by remember(chat.id) { mutableStateOf("") }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingPath by remember { mutableStateOf<String?>(null) }
    var recordingStartedAt by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceRecording(context) { active, path ->
            recorder = active
            recordingPath = path
            recordingStartedAt = System.currentTimeMillis()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(onAttach) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (chat.isChannel) {
                            Text(if (chat.canSendMessages) "Канал · можно публиковать" else "Канал · только чтение", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        },
        bottomBar = {
            if (chat.canSendMessages) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { picker.launch("*/*") }) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Прикрепить файл")
                    }
                    TextButton(onClick = {
                        if (recorder == null) {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
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
                        onClick = {
                            val value = text.trim()
                            if (value.isNotEmpty()) {
                                onSend(value)
                                text = ""
                            }
                        },
                        enabled = text.isNotBlank()
                    ) { Icon(Icons.Default.Send, contentDescription = "Отправить") }
                }
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(
                        if (chat.isChannel) "Публиковать сообщения могут только администраторы канала."
                        else "У вас нет разрешения отправлять сообщения.",
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (error.isNotBlank()) {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message = message, onDownload = onDownload)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: TelegramMessage, onDownload: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            color = if (message.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (message.mediaKind == "photo" && !message.mediaPath.isNullOrBlank()) {
                    LocalMediaImage(message.mediaPath!!, Modifier.fillMaxWidth().heightIn(max = 280.dp))
                } else if (message.mediaFileId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(message.text, fontWeight = FontWeight.Medium)
                            Text(
                                when (message.mediaKind) {
                                    "video" -> "Видеофайл"
                                    "voice" -> "Голосовое сообщение"
                                    "audio" -> "Аудиофайл"
                                    "animation" -> "Анимация"
                                    "sticker" -> "Стикер"
                                    else -> "Вложение"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (message.mediaPath.isNullOrBlank()) {
                            IconButton(onClick = { onDownload(message.mediaFileId) }) {
                                Icon(Icons.Default.Download, contentDescription = "Скачать файл")
                            }
                        } else {
                            Text("Скачано", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    Text(message.text)
                }
                if (message.caption.isNotBlank()) Text(message.caption, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun LocalMediaImage(path: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
    }
    if (bitmap != null) {
        Image(bitmap!!.asImageBitmap(), contentDescription = "Медиафайл", modifier = modifier.clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
    } else {
        Box(modifier.height(160.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(18.dp))
            Text(text)
        }
    }
}

private fun startVoiceRecording(context: Context, onStarted: (MediaRecorder, String) -> Unit) {
    runCatching {
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val active = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        active.setAudioSource(MediaRecorder.AudioSource.MIC)
        active.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        active.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        active.setAudioChannels(1)
        active.setAudioSamplingRate(44100)
        active.setOutputFile(file.absolutePath)
        active.prepare()
        active.start()
        onStarted(active, file.absolutePath)
    }
}

private fun copyUriToCache(context: Context, uri: Uri): File {
    val target = File(context.cacheDir, "upload_${System.currentTimeMillis()}")
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Не удалось открыть выбранный файл" }.copyTo(target.outputStream())
    }
    return target
}

private fun saveToDownloads(context: Context, sourcePath: String) {
    val source = File(sourcePath)
    require(source.exists()) { "Файл не найден после загрузки" }
    val name = source.name.ifBlank { "kotligram_${System.currentTimeMillis()}" }
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/KotliGram")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
            "Не удалось создать файл в папке Downloads"
        }
        context.contentResolver.openOutputStream(uri).use { output ->
            requireNotNull(output).use { stream -> source.inputStream().use { it.copyTo(stream) } }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
    } else {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("Папка загрузок недоступна")
        directory.mkdirs()
        source.copyTo(File(directory, name), overwrite = true)
    }
}
