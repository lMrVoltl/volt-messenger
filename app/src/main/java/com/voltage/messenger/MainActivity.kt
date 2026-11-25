package com.voltage.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.voltage.messenger.core.ai.AiChatMessage
import com.voltage.messenger.core.ai.VoltAiClient
import com.voltage.messenger.core.ai.VoltAiStatus
import com.voltage.messenger.ui.components.WavySeekBar
import com.voltage.messenger.ui.theme.AppSurface
import com.voltage.messenger.ui.theme.VoltMessengerTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatUiMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isFromUser: Boolean,
    val isMediaDemo: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoltMessengerTheme {
                VoltChatScreen()
            }
        }
    }
}

@Composable
fun VoltChatScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var aiStatus by remember { mutableStateOf(VoltAiStatus.CHECKING) }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    val messages = remember {
        mutableStateListOf(
            ChatUiMessage(
                text = "Привет! Я подключён напрямую к твоему локальному серверу Volt AI (127.0.0.1:8080).",
                isFromUser = false
            ),
            ChatUiMessage(
                text = "Демо-плеер Material You • 0:24",
                isFromUser = false,
                isMediaDemo = true
            )
        )
    }

    fun refreshStatus() {
        scope.launch {
            aiStatus = VoltAiStatus.CHECKING
            aiStatus = VoltAiClient.checkStatus(context)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun sendMessage() {
        val prompt = inputText.trim()
        if (prompt.isEmpty() || isGenerating) return

        inputText = ""
        messages.add(ChatUiMessage(text = prompt, isFromUser = true))

        val replyId = UUID.randomUUID().toString()
        messages.add(ChatUiMessage(id = replyId, text = "...", isFromUser = false))
        isGenerating = true

        val history = messages
            .filter { !it.isMediaDemo && it.id != replyId }
            .takeLast(12)
            .map { AiChatMessage(if (it.isFromUser) "user" else "assistant", it.text) }

        activeJob = scope.launch {
            listState.animateScrollToItem(messages.lastIndex)
            val buffer = StringBuilder()

            VoltAiClient.streamCompletion(history)
                .catch { e ->
                    buffer.append("Нет связи с Volt AI (${e.message ?: "сервер выключен"}). Запусти сервер в Настройках Volt AI.")
                    val idx = messages.indexOfFirst { it.id == replyId }
                    if (idx >= 0) messages[idx] = messages[idx].copy(text = buffer.toString())
                    aiStatus = VoltAiStatus.SERVER_OFFLINE
                }
                .collect { chunk ->
                    buffer.append(chunk)
                    val idx = messages.indexOfFirst { it.id == replyId }
                    if (idx >= 0) {
                        messages[idx] = messages[idx].copy(text = buffer.toString())
                    }
                    listState.scrollToItem(messages.lastIndex)
                }

            isGenerating = false
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            ChatTopBar(
                status = aiStatus,
                onRefresh = { refreshStatus() },
                onOpenVoltAi = { VoltAiClient.launchVoltAi(context) }
            )
        },
        bottomBar = {
            ChatInputBar(
                text = inputText,
                isGenerating = isGenerating,
                onTextChange = { inputText = it },
                onSend = { sendMessage() },
                onStop = {
                    activeJob?.cancel()
                    isGenerating = false
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedVisibility(
                visible = aiStatus == VoltAiStatus.NOT_INSTALLED || aiStatus == VoltAiStatus.SERVER_OFFLINE,
                enter = expandVertically(spring(dampingRatio = 0.75f)) + fadeIn(),
                exit = shrinkVertically(spring(dampingRatio = 0.8f)) + fadeOut()
            ) {
                StatusBanner(
                    status = aiStatus,
                    onOpenVoltAi = { VoltAiClient.launchVoltAi(context) }
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    Box(modifier = Modifier.animateItem(
                        fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        placementSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )) {
                        if (msg.isMediaDemo) {
                            WavyMediaBubble(title = msg.text)
                        } else {
                            MessageBubble(message = msg)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatTopBar(
    status: VoltAiStatus,
    onRefresh: () -> Unit,
    onOpenVoltAi: () -> Unit
) {
    val dotColor by animateColorAsState(
        targetValue = when (status) {
            VoltAiStatus.READY -> Color(0xFF22C55E)
            VoltAiStatus.SERVER_OFFLINE -> Color(0xFFF59E0B)
            VoltAiStatus.NOT_INSTALLED -> Color(0xFFEF4444)
            VoltAiStatus.CHECKING -> MaterialTheme.colorScheme.outline
        },
        label = "statusDot"
    )

    val subtitle = when (status) {
        VoltAiStatus.READY -> "Онлайн • 127.0.0.1:8080"
        VoltAiStatus.SERVER_OFFLINE -> "Сервер спит • Запусти в Volt AI"
        VoltAiStatus.NOT_INSTALLED -> "Нужен пакет com.voltage.ai"
        VoltAiStatus.CHECKING -> "Проверка локального ИИ..."
    }

    AppSurface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Volt AI",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Обновить")
            }

            if (status == VoltAiStatus.SERVER_OFFLINE) {
                IconButton(onClick = onOpenVoltAi) {
                    Icon(Icons.Rounded.OpenInNew, contentDescription = "Открыть Volt AI")
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(
    status: VoltAiStatus,
    onOpenVoltAi: () -> Unit
) {
    AppSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (status == VoltAiStatus.NOT_INSTALLED) {
                    "Установи Volt AI и скачай хотя бы 1 модель"
                } else {
                    "Открой Volt AI → Настройки → Подключение"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            if (status == VoltAiStatus.SERVER_OFFLINE) {
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(onClick = onOpenVoltAi) {
                    Text("Открыть")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatUiMessage) {
    val isUser = message.isFromUser
    val bubbleShape = if (isUser) {
        RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 6.dp, bottomEnd = 22.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        AppSurface(
            modifier = Modifier.widthIn(max = 310.dp),
            shape = bubbleShape,
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            contentColor = if (isUser) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun WavyMediaBubble(title: String) {
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0.35f) }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            delay(32L)
            progress = if (progress >= 1f) 0f else progress + 0.004f
        }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        AppSurface(
            modifier = Modifier.widthIn(max = 310.dp),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 6.dp, bottomEnd = 22.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(
                    onClick = { isPlaying = !isPlaying },
                    modifier = Modifier.size(44.dp)
                ) {
                    AnimatedContent(
                        targetState = isPlaying,
                        transitionSpec = {
                            (scaleIn(spring(dampingRatio = 0.6f)) + fadeIn()) togetherWith
                                (scaleOut() + fadeOut())
                        },
                        label = "playPause"
                    ) { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "Воспроизвести"
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    WavySeekBar(
                        progress = progress,
                        isPlaying = isPlaying,
                        onSeek = { progress = it }
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    isGenerating: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        AppSurface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = "Сообщение локальному ИИ...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        val buttonState = when {
            isGenerating -> 2
            text.isNotBlank() -> 1
            else -> 0
        }

        FilledTonalIconButton(
            onClick = {
                when (buttonState) {
                    2 -> onStop()
                    1 -> onSend()
                    else -> {}
                }
            },
            modifier = Modifier.size(52.dp)
        ) {
            AnimatedContent(
                targetState = buttonState,
                transitionSpec = {
                    (scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) + fadeIn()) togetherWith
                        (scaleOut() + fadeOut())
                },
                label = "actionButton"
            ) { state ->
                when (state) {
                    2 -> Icon(Icons.Rounded.Stop, contentDescription = "Стоп")
                    1 -> Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Отправить")
                    else -> Icon(Icons.Rounded.Mic, contentDescription = "Голосовое")
                }
            }
        }
    }
}
