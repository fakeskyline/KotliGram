package app.kotligram

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class ChatPreview(val title: String, val message: String, val time: String, val unread: Int = 0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KotliGramApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KotliGramApp() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val chats = listOf(
        ChatPreview("Избранное", "Заметки и сохранённые сообщения", "12:42"),
        ChatPreview("Telegram", "Добро пожаловать в KotliGram", "12:30", 2),
        ChatPreview("Разработка", "Начинаем собирать клиент", "Вчера")
    )

    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("KotliGram", fontWeight = FontWeight.SemiBold)
                            Text("Сообщения", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        IconButton(onClick = {}) { Icon(Icons.Default.Search, "Поиск") }
                        IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, "Меню") }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selectedTab == 0, { selectedTab = 0 },
                        icon = { Icon(Icons.Default.ChatBubble, null) }, label = { Text("Чаты") })
                    NavigationBarItem(selectedTab == 1, { selectedTab = 1 },
                        icon = { Icon(Icons.Default.People, null) }, label = { Text("Контакты") })
                    NavigationBarItem(selectedTab == 2, { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Настройки") })
                }
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {}) {
                    Icon(Icons.Default.Edit, contentDescription = "Новое сообщение")
                }
            }
        ) { padding ->
            when (selectedTab) {
                0 -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(chats) { chat ->
                        ListItem(
                            headlineContent = { Text(chat.title, fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text(chat.message, maxLines = 1) },
                            leadingContent = {
                                Surface(
                                    shape = MaterialTheme.shapes.extraLarge,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.size(52.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(chat.title.take(1), style = MaterialTheme.typography.titleLarge)
                                    }
                                }
                            },
                            trailingContent = {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(chat.time, style = MaterialTheme.typography.labelSmall)
                                    if (chat.unread > 0) {
                                        Spacer(Modifier.height(6.dp))
                                        Badge { Text(chat.unread.toString()) }
                                    }
                                }
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
                1 -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                    Text("Контакты появятся после подключения Telegram")
                }
                else -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                    Text("Настройки KotliGram")
                }
            }
        }
    }
}
