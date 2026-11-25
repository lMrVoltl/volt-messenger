package com.voltage.messenger.ui.screens

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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voltage.messenger.core.ai.VoltAiStatus
import com.voltage.messenger.model.ChatMessage
import com.voltage.messenger.model.ChatRoom
import com.voltage.messenger.model.ChatType
import com.voltage.messenger.model.ReplyPreview
import com.voltage.messenger.ui.components.WavySeekBar
import com.voltage.messenger.ui.theme.AppSurface
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    chat: ChatRoom,
    messages: List<ChatMessage>,
    aiStatus: VoltAiStatus,
    isAiGenerating: Boolean,
    onBack: () -> Unit,
    onSendMessage: (text: String, replyTo: ReplyPreview?) -> Unit,
    onStopAi: () -> Unit,
    onDeleteMessage: (ChatMessage) -> Unit,
    onAskAiAboutMessage: (ChatMessage) -> Unit,
    onOpenVoltAi: () -> Unit
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    var inputText by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<ReplyPreview?>(null) }
    var selectedMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var showCallDialog by remember { mutableStateOf(false) }

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
            else -> "сервер выключен"
        }
        ChatType.LOCAL_LAN -> if (chat.isOnline) "Wi-Fi • ${chat.peerHost ?: "онлайн"}" else "не в сети"
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
                        maxLines = 1
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
                            IconButton(onClick = { /* Вложения добавим на следующем этапе */ }) {
                                Icon(
                                    imageVector = Icons.Rounded.AttachFile,
                                    contentDescription = "Вложение",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 10.dp)
                            ) {
                                if (inputText.isEmpty()) {
                                    Text(
                                        text = "Сообщение",
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

                    val buttonState = when {
                        chat.type == ChatType.AI && isAiGenerating -> 2
                        inputText.isNotBlank() -> 1
                        else -> 0
                    }

                    FilledTonalIconButton(
                        onClick = {
                            when (buttonState) {
                                2 -> onStopAi()
                                1 -> {
                                    onSendMessage(inputText.trim(), replyingTo)
                                    inputText = ""
                                    replyingTo = null
                                }
                                else -> {}
                            }
                        },
                        modifier = Modifier.size(50.dp)
                    ) {
                        AnimatedContent(
                            targetState = buttonState,
                            transitionSpec = {
                                (scaleIn(initialScale = 0.78f, animationSpec = tween(150)) + fadeIn(tween(150))) togetherWith
                                    (scaleOut(targetScale = 0.78f, animationSpec = tween(110)) + fadeOut(tween(110)))
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
                                onLongClick = { selectedMessage = msg }
                            )
                        } else {
                            TelegramMessageBubble(
                                message = msg,
                                isFirstInGroup = isFirstInGroup,
                                isLastInGroup = isLastInGroup,
                                onLongClick = { selectedMessage = msg }
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
                ListItem(
                    headlineContent = { Text("Ответить") },
                    leadingContent = { Icon(Icons.AutoMirrored.Rounded.Reply, null) },
                    modifier = Modifier.clickable {
                        replyingTo = ReplyPreview(msg.senderName, msg.text)
                        selectedMessage = null
                    }
                )
                ListItem(
                    headlineContent = { Text("Копировать текст") },
                    leadingContent = { Icon(Icons.Rounded.ContentCopy, null) },
                    modifier = Modifier.clickable {
                        clipboard.setText(AnnotatedString(msg.text))
                        selectedMessage = null
                    }
                )
                if (chat.type != ChatType.AI) {
                    ListItem(
                        headlineContent = { Text("Спросить у Volt AI") },
                        supportingContent = { Text("Переслать локальной нейросети") },
                        leadingContent = { Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable {
                            onAskAiAboutMessage(msg)
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

    if (showCallDialog) {
        AlertDialog(
            onDismissRequest = { showCallDialog = false },
            icon = { Icon(Icons.Rounded.Videocam, null) },
            title = { Text("Видеозвонки 30/60 fps") },
            text = { Text("Модуль WebRTC + захват экрана подключим следующим этапом прямо поверх этого локального соединения с ${chat.title}.") },
            confirmButton = {
                TextButton(onClick = { showCallDialog = false }) {
                    Text("Ок")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TelegramMessageBubble(
    message: ChatMessage,
    isFirstInGroup: Boolean,
    isLastInGroup: Boolean,
    onLongClick: () -> Unit
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
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongClick
                ),
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

                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge
                )

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.formattedTime(),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WavyMediaMessageBubble(
    message: ChatMessage,
    onLongClick: () -> Unit
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
                .combinedClickable(onClick = {}, onLongClick = onLongClick),
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
                            (scaleIn(initialScale = 0.78f, animationSpec = tween(150)) + fadeIn(tween(150))) togetherWith
                                (scaleOut(targetScale = 0.78f, animationSpec = tween(110)) + fadeOut(tween(110)))
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
                            text = message.formattedTime(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }
    }
}
