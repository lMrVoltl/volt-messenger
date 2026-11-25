package com.voltage.messenger.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.voltage.messenger.core.ai.AiChatMessage
import com.voltage.messenger.core.ai.VoltAiClient
import com.voltage.messenger.core.ai.VoltAiStatus
import com.voltage.messenger.model.AttachmentInfo
import com.voltage.messenger.model.ChatMessage
import com.voltage.messenger.model.ChatRoom
import com.voltage.messenger.model.ChatType
import com.voltage.messenger.model.ReplyPreview
import com.voltage.messenger.ui.components.WavySeekBar
import com.voltage.messenger.ui.components.bounceClick
import com.voltage.messenger.ui.theme.AppSurface
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chat: ChatRoom,
    messages: List<ChatMessage>,
    aiStatus: VoltAiStatus,
    isAiGenerating: Boolean,
    onBack: () -> Unit,
    onSendMessage: (text: String, replyTo: ReplyPreview?, attachment: AttachmentInfo?) -> Unit,
    onPickAttachment: (android.net.Uri) -> AttachmentInfo?,
    onStopAi: () -> Unit,
    onDeleteMessage: (ChatMessage) -> Unit,
    onOpenVoltAi: () -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    var inputText by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<ReplyPreview?>(null) }
    var pendingAttachment by remember { mutableStateOf<AttachmentInfo?>(null) }
    var selectedMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var aiPopupMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var showCallDialog by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val info = onPickAttachment(uri)
            if (info != null) {
                pendingAttachment = info
            } else {
                Toast.makeText(context, "Не удалось прочитать файл", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openAttachmentFile(att: AttachmentInfo) {
        try {
            val file = File(att.localPath)
            if (!file.exists()) {
                Toast.makeText(context, "Файл не найден на устройстве", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, att.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Открыть файл"))
        } catch (_: Exception) {
            Toast.makeText(context, "Нет приложения для открытия этого файла", Toast.LENGTH_SHORT).show()
        }
    }

    val reversedMessages = remember(messages) { messages.asReversed() }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    val dynamicSubtitle = when (chat.type) {
        ChatType.AI -> when {
            isAiGenerating -> "печатает..."
            aiStatus == VoltAiStatus.READY -> "локальный ИИ • онлайн"
            aiStatus == VoltAiStatus.NOT_INSTALLED -> "нужен пакет com.voltage.ai"
            else -> "сервер выключен • напиши «инструкция»"
        }
        ChatType.LOCAL_LAN -> if (chat.isOnline) {
            "Сервер: ${chat.serverName ?: "Volt"} • ${chat.peerHost ?: "Wi-Fi"}"
        } else {
            "не в сети"
        }
        ChatType.SAVED -> "облако на этом устройстве"
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                }

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    when (chat.type) {
                        ChatType.AI -> Icon(
                            Icons.Rounded.AutoAwesome,
                            null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        ChatType.SAVED -> Icon(
                            Icons.Rounded.Bookmark,
                            null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        ChatType.LOCAL_LAN -> Text(
                            text = chat.title.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = chat.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = dynamicSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isAiGenerating || (chat.type == ChatType.AI && aiStatus == VoltAiStatus.READY) || chat.isOnline) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (chat.type == ChatType.LOCAL_LAN) {
                    IconButton(onClick = { showCallDialog = true }) {
                        Icon(Icons.Rounded.Call, contentDescription = "Аудиозвонок")
                    }
                    IconButton(onClick = { showCallDialog = true }) {
                        Icon(Icons.Rounded.Videocam, contentDescription = "Видеозвонок")
                    }
                }
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
            ) {
                // Превью прикреплённого файла перед отправкой
                AnimatedVisibility(
                    visible = pendingAttachment != null,
                    enter = expandVertically(spring(dampingRatio = Spring.DampingRatioNoBouncy)) + fadeIn(tween(140)),
                    exit = shrinkVertically(spring(dampingRatio = Spring.DampingRatioNoBouncy)) + fadeOut(tween(120))
                ) {
                    pendingAttachment?.let { att ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = attachmentIconFor(att.mimeType),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = att.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Файл готов к отправке • ${att.formattedSize()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { pendingAttachment = null },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Убрать", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // Превью ответа на сообщение
                AnimatedVisibility(
                    visible = replyingTo != null,
                    enter = expandVertically(spring(dampingRatio = Spring.DampingRatioNoBouncy)) + fadeIn(tween(140)),
                    exit = shrinkVertically(spring(dampingRatio = Spring.DampingRatioNoBouncy)) + fadeOut(tween(120))
                ) {
                    replyingTo?.let { reply ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Reply,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = reply.senderName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = reply.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { replyingTo = null },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Отмена", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    AppSurface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(26.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { filePickerLauncher.launch(arrayOf("*/*")) }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AttachFile,
                                    contentDescription = "Прикрепить файл",
                                    tint = if (pendingAttachment != null) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 10.dp)
                            ) {
                                if (inputText.isEmpty()) {
                                    Text(
                                        text = if (pendingAttachment != null) "Подпись к файлу..." else "Сообщение",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                BasicTextField(
                                    value = inputText,
                                    onValueChange = { inputText = it },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    maxLines = 5,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    val canSend = inputText.isNotBlank() || pendingAttachment != null
                    val buttonState = when {
                        chat.type == ChatType.AI && isAiGenerating -> 2
                        canSend -> 1
                        else -> 0
                    }

                    FilledTonalIconButton(
                        onClick = {
                            when (buttonState) {
                                2 -> onStopAi()
                                1 -> {
                                    onSendMessage(inputText.trim(), replyingTo, pendingAttachment)
                                    inputText = ""
                                    replyingTo = null
                                    pendingAttachment = null
                                }
                                else -> {}
                            }
                        },
                        modifier = Modifier.size(50.dp)
                    ) {
                        AnimatedContent(
                            targetState = buttonState,
                            transitionSpec = {
                                (scaleIn(initialScale = 0.72f, animationSpec = spring(dampingRatio = 0.65f)) + fadeIn(tween(140))) togetherWith
                                    (scaleOut(targetScale = 0.72f, animationSpec = tween(100)) + fadeOut(tween(100)))
                            },
                            label = "sendMorph"
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
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (chat.type == ChatType.AI && (aiStatus == VoltAiStatus.NOT_INSTALLED || aiStatus == VoltAiStatus.SERVER_OFFLINE)) {
                AppSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (aiStatus == VoltAiStatus.NOT_INSTALLED) {
                                "Установи Volt AI и скачай хотя бы 1 модель"
                            } else {
                                "Запусти сервер в Volt AI для ответов"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        if (aiStatus == VoltAiStatus.SERVER_OFFLINE) {
                            Spacer(modifier = Modifier.width(8.dp))
                            FilledTonalButton(onClick = onOpenVoltAi) {
                                Text("Открыть")
                            }
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.Bottom)
            ) {
                itemsIndexed(reversedMessages, key = { _, m -> m.id }) { index, msg ->
                    val newerMsg = reversedMessages.getOrNull(index - 1)
                    val olderMsg = reversedMessages.getOrNull(index + 1)
                    val isLastInGroup = newerMsg == null || newerMsg.isFromMe != msg.isFromMe
                    val isFirstInGroup = olderMsg == null || olderMsg.isFromMe != msg.isFromMe

                    Box(
                        modifier = Modifier
                            .padding(
                                top = if (isFirstInGroup) 6.dp else 0.dp,
                                bottom = if (isLastInGroup) 2.dp else 0.dp
                            )
                            .animateItem(
                                fadeInSpec = tween(160),
                                placementSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            )
                    ) {
                        if (msg.isMediaDemo) {
                            WavyMediaMessageBubble(
                                message = msg,
                                onTap = { selectedMessage = msg }
                            )
                        } else {
                            TelegramMessageBubble(
                                message = msg,
                                isFirstInGroup = isFirstInGroup,
                                isLastInGroup = isLastInGroup,
                                onTap = { selectedMessage = msg },
                                onOpenAttachment = { openAttachmentFile(it) }
                            )
                        }
                    }
                }

                item(key = "date_today") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.75f))
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Сегодня",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // Меню действий по простому ТАПУ на сообщение
    selectedMessage?.let { msg ->
        ModalBottomSheet(
            onDismissRequest = { selectedMessage = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                msg.attachment?.let { att ->
                    ListItem(
                        headlineContent = { Text("Открыть файл") },
                        supportingContent = { Text("${att.name} • ${att.formattedSize()}") },
                        leadingContent = { Icon(Icons.Rounded.OpenInNew, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable {
                            selectedMessage = null
                            openAttachmentFile(att)
                        }
                    )
                }
                ListItem(
                    headlineContent = { Text("Ответить") },
                    leadingContent = { Icon(Icons.AutoMirrored.Rounded.Reply, null) },
                    modifier = Modifier.clickable {
                        val preview = msg.text.ifBlank { msg.attachment?.name ?: "Вложение" }
                        replyingTo = ReplyPreview(msg.senderName, preview)
                        selectedMessage = null
                    }
                )
                if (chat.type != ChatType.AI && msg.text.isNotBlank()) {
                    ListItem(
                        headlineContent = { Text("Спросить у Volt AI") },
                        supportingContent = { Text("Открыть мини-окно с ответом прямо здесь") },
                        leadingContent = { Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable {
                            selectedMessage = null
                            aiPopupMessage = msg
                        }
                    )
                }
                if (msg.text.isNotBlank()) {
                    ListItem(
                        headlineContent = { Text("Копировать текст") },
                        leadingContent = { Icon(Icons.Rounded.ContentCopy, null) },
                        modifier = Modifier.clickable {
                            clipboard.setText(AnnotatedString(msg.text))
                            selectedMessage = null
                        }
                    )
                }
                ListItem(
                    headlineContent = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable {
                        onDeleteMessage(msg)
                        selectedMessage = null
                    }
                )
            }
        }
    }

    // Встроенное мини-окно Volt AI прямо поверх текущего чата
    aiPopupMessage?.let { targetMsg ->
        InChatAiMiniWindow(
            sourceMessage = targetMsg,
            onDismiss = { aiPopupMessage = null },
            onOpenVoltAi = onOpenVoltAi,
            onInsertIntoChat = { aiAnswer ->
                onSendMessage(
                    aiAnswer,
                    ReplyPreview(targetMsg.senderName, targetMsg.text),
                    null
                )
                aiPopupMessage = null
            }
        )
    }

    if (showCallDialog) {
        AlertDialog(
            onDismissRequest = { showCallDialog = false },
            icon = { Icon(Icons.Rounded.Videocam, null) },
            title = { Text("Видеозвонки 30/60 fps") },
            text = { Text("Модуль WebRTC + захват экрана подключим следующим этапом прямо поверх локального соединения с ${chat.title}.") },
            confirmButton = {
                TextButton(onClick = { showCallDialog = false }) {
                    Text("Ок")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InChatAiMiniWindow(
    sourceMessage: ChatMessage,
    onDismiss: () -> Unit,
    onOpenVoltAi: () -> Unit,
    onInsertIntoChat: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var aiReply by remember(sourceMessage.id) { mutableStateOf("") }
    var isStreaming by remember(sourceMessage.id) { mutableStateOf(true) }
    var hasError by remember(sourceMessage.id) { mutableStateOf(false) }
    var customQuestion by remember(sourceMessage.id) { mutableStateOf("") }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    fun runGeneration(extraPrompt: String?) {
        activeJob?.cancel()
        aiReply = ""
        isStreaming = true
        hasError = false

        val prompt = if (extraPrompt.isNullOrBlank()) {
            "Кратко и по делу ответь или объясни это сообщение из чата:\n«${sourceMessage.text}»"
        } else {
            "Контекст сообщения из чата: «${sourceMessage.text}»\n\nЗапрос пользователя: $extraPrompt"
        }

        activeJob = scope.launch {
            val buffer = StringBuilder()
            var lastUpdate = 0L
            VoltAiClient.streamCompletion(listOf(AiChatMessage("user", prompt)))
                .catch {
                    hasError = true
                    aiReply = "Локальный сервер Volt AI (127.0.0.1:8080) сейчас выключен. Открой Volt AI и включи сервер."
                }
                .collect { chunk ->
                    buffer.append(chunk)
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 35L) {
                        aiReply = buffer.toString()
                        lastUpdate = now
                    }
                }
            if (buffer.isNotEmpty()) {
                aiReply = buffer.toString()
            }
            isStreaming = false
        }
    }

    LaunchedEffect(sourceMessage.id) {
        runGeneration(null)
    }

    DisposableEffect(Unit) {
        onDispose { activeJob?.cancel() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Volt AI • Быстрый ответ",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isStreaming) "Генерирует ответ локально..." else "Готово",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (isStreaming) {
                    IconButton(onClick = {
                        activeJob?.cancel()
                        isStreaming = false
                    }) {
                        Icon(Icons.Rounded.Stop, contentDescription = "Остановить")
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Цитата исходного сообщения
            AppSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(modifier = Modifier.padding(12.dp)) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(34.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = sourceMessage.senderName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = sourceMessage.text,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (isStreaming) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Область с генерируемым ответом ИИ
            AppSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 90.dp, max = 260.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp)
                ) {
                    Text(
                        text = aiReply.ifEmpty { "Думает..." },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Поле уточнения вопроса к этому же сообщению
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppSurface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                        if (customQuestion.isEmpty()) {
                            Text(
                                text = "Уточнить у ИИ по этому сообщению...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        BasicTextField(
                            value = customQuestion,
                            onValueChange = { customQuestion = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalIconButton(
                    onClick = {
                        if (customQuestion.isNotBlank()) {
                            val q = customQuestion.trim()
                            customQuestion = ""
                            runGeneration(q)
                        }
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Спросить")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasError) {
                    FilledTonalButton(
                        onClick = onOpenVoltAi,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Открыть Volt AI")
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            if (aiReply.isNotBlank()) {
                                clipboard.setText(AnnotatedString(aiReply))
                            }
                        },
                        enabled = aiReply.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Копировать")
                    }
                    FilledTonalButton(
                        onClick = { onInsertIntoChat(aiReply) },
                        enabled = !isStreaming && aiReply.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Отправить в чат")
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

private fun attachmentIconFor(mimeType: String) = when {
    mimeType.startsWith("image/") -> Icons.Rounded.Image
    mimeType.startsWith("video/") -> Icons.Rounded.VideoFile
    mimeType.startsWith("audio/") -> Icons.Rounded.AudioFile
    else -> Icons.Rounded.InsertDriveFile
}

@Composable
private fun TelegramMessageBubble(
    message: ChatMessage,
    isFirstInGroup: Boolean,
    isLastInGroup: Boolean,
    onTap: () -> Unit,
    onOpenAttachment: (AttachmentInfo) -> Unit
) {
    val isMe = message.isFromMe
    val shape = if (isMe) {
        RoundedCornerShape(
            topStart = 20.dp,
            topEnd = if (isFirstInGroup) 20.dp else 8.dp,
            bottomStart = 20.dp,
            bottomEnd = if (isLastInGroup) 5.dp else 8.dp
        )
    } else {
        RoundedCornerShape(
            topStart = if (isFirstInGroup) 20.dp else 8.dp,
            topEnd = 20.dp,
            bottomStart = if (isLastInGroup) 5.dp else 8.dp,
            bottomEnd = 20.dp
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        AppSurface(
            modifier = Modifier
                .widthIn(min = 76.dp, max = 315.dp)
                .clip(shape)
                .bounceClick(pressedScale = 0.97f, onClick = onTap),
            shape = shape,
            color = if (isMe) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            contentColor = if (isMe) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        ) {
            Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp)) {
                message.replyTo?.let { reply ->
                    Row(
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(28.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                        Column {
                            Text(
                                text = reply.senderName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = reply.text,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                message.attachment?.let { att ->
                    Row(
                        modifier = Modifier
                            .padding(bottom = if (message.text.isNotBlank()) 6.dp else 2.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f))
                            .clickable { onOpenAttachment(att) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = attachmentIconFor(att.mimeType),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = att.name,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = att.formattedSize(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (message.text.isNotBlank()) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isMe) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        }
                    )
                    if (isMe) {
                        Spacer(modifier = Modifier.width(3.dp))
                        Icon(
                            imageVector = Icons.Rounded.DoneAll,
                            contentDescription = "Доставлено",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WavyMediaMessageBubble(
    message: ChatMessage,
    onTap: () -> Unit
) {
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0.32f) }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            delay(32L)
            progress = if (progress >= 1f) 0f else progress + 0.004f
        }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        AppSurface(
            modifier = Modifier
                .widthIn(max = 315.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 5.dp, bottomEnd = 20.dp))
                .bounceClick(pressedScale = 0.98f, onClick = onTap),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 5.dp, bottomEnd = 20.dp),
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
                            (scaleIn(initialScale = 0.75f, animationSpec = tween(140)) + fadeIn(tween(140))) togetherWith
                                (scaleOut(targetScale = 0.75f, animationSpec = tween(100)) + fadeOut(tween(100)))
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = message.timeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }
    }
}
