package com.voltage.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.voltage.messenger.core.ai.AiChatMessage
import com.voltage.messenger.core.ai.VoltAiClient
import com.voltage.messenger.core.ai.VoltAiStatus
import com.voltage.messenger.core.network.LanMessenger
import com.voltage.messenger.core.storage.ChatStorage
import com.voltage.messenger.model.AttachmentInfo
import com.voltage.messenger.model.ChatMessage
import com.voltage.messenger.model.ChatRoom
import com.voltage.messenger.model.ChatType
import com.voltage.messenger.model.ReplyPreview
import com.voltage.messenger.ui.screens.ChatListScreen
import com.voltage.messenger.ui.screens.ChatScreen
import com.voltage.messenger.ui.theme.VoltMessengerTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.UUID

private const val CHAT_ID_SAVED = "saved_messages"
private const val CHAT_ID_AI = "volt_ai_local"

private val VOLT_AI_GUIDE_TEXT = """
📘 Как пользоваться локальным ИИ (Volt AI)

1. Настройка в приложении Volt AI:
• Установи приложение Volt AI (com.voltage.ai) и скачай хотя бы 1 языковую модель.
• Загрузи модель в память.
• Открой Настройки → Подключение и включи локальный сервер (127.0.0.1:8080).

2. Использование в Volt Messenger:
• В этом чате — просто пиши любые вопросы, ответы печатаются локально прямо на телефоне без интернета.
• В любом другом чате — просто нажми (короткий тап) на любое сообщение → «Спросить у Volt AI». Откроется компактное окно прямо поверх чата с готовым ответом, который можно сразу отправить собеседнику.

💡 Чтобы снова открыть эту памятку, просто напиши здесь слово: инструкция
""".trimIndent()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoltMessengerTheme {
                VoltMessengerApp()
            }
        }
    }
}

@Composable
fun VoltMessengerApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val storage = remember { ChatStorage(context) }
    var nickname by remember { mutableStateOf(storage.getNickname()) }
    var serverName by remember { mutableStateOf(storage.getServerName()) }
    val fingerprint = remember { storage.getKeyFingerprintEmojis() }

    var activeChatId by remember { mutableStateOf<String?>(null) }
    var aiStatus by remember { mutableStateOf(VoltAiStatus.CHECKING) }
    var isAiGenerating by remember { mutableStateOf(false) }
    var aiJob by remember { mutableStateOf<Job?>(null) }

    val unreadCounts = remember { mutableStateMapOf<String, Int>() }
    val messagesByChat = remember {
        mutableStateMapOf<String, List<ChatMessage>>().apply {
            val saved = storage.loadMessages()
            saved.forEach { (k, v) -> put(k, v) }
            if (get(CHAT_ID_SAVED).isNullOrEmpty()) {
                put(
                    CHAT_ID_SAVED,
                    listOf(
                        ChatMessage(
                            chatId = CHAT_ID_SAVED,
                            senderName = "Избранное",
                            text = "Личное пространство для заметок и файлов. Нажми на любое сообщение коротким тапом, чтобы открыть меню или спросить у Volt AI.",
                            isFromMe = false
                        ),
                        ChatMessage(
                            chatId = CHAT_ID_SAVED,
                            senderName = "Избранное",
                            text = "Голосовое • 0:24",
                            isFromMe = false,
                            isMediaDemo = true
                        )
                    )
                )
            }
            if (get(CHAT_ID_AI).isNullOrEmpty()) {
                put(
                    CHAT_ID_AI,
                    listOf(
                        ChatMessage(
                            chatId = CHAT_ID_AI,
                            senderName = "Volt AI",
                            text = VOLT_AI_GUIDE_TEXT,
                            isFromMe = false
                        )
                    )
                )
            }
        }
    }

    fun persistAll() {
        storage.saveMessages(messagesByChat.toMap())
    }

    fun appendMessage(chatId: String, msg: ChatMessage) {
        val updated = (messagesByChat[chatId] ?: emptyList()) + msg
        messagesByChat[chatId] = updated
        if (activeChatId != chatId && !msg.isFromMe) {
            unreadCounts[chatId] = (unreadCounts[chatId] ?: 0) + 1
        }
        persistAll()
    }

    fun updateMessageText(chatId: String, msgId: String, newText: String) {
        val current = messagesByChat[chatId] ?: return
        messagesByChat[chatId] = current.map {
            if (it.id == msgId) it.copy(text = newText) else it
        }
    }

    val lanMessenger = remember {
        LanMessenger(
            context = context,
            myDeviceId = storage.getDeviceId(),
            myNickname = nickname,
            myServerName = serverName,
            onMessageReceived = { incoming ->
                scope.launch {
                    val chatId = "lan_${incoming.peerId}"
                    appendMessage(
                        chatId,
                        ChatMessage(
                            chatId = chatId,
                            senderName = incoming.senderName,
                            text = incoming.text,
                            isFromMe = false,
                            timestamp = incoming.timestamp
                        )
                    )
                }
            }
        )
    }

    val lanPeers by lanMessenger.peers.collectAsState()
    val isLanActive by lanMessenger.isLanActive.collectAsState()
    val lanPort by lanMessenger.localPort.collectAsState()

    DisposableEffect(Unit) {
        lanMessenger.start()
        onDispose { lanMessenger.destroy() }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    aiStatus = VoltAiClient.checkStatus(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val allChats = remember(lanPeers, unreadCounts.toMap(), aiStatus) {
        buildList {
            add(
                ChatRoom(
                    id = CHAT_ID_SAVED,
                    title = "Избранное",
                    subtitle = "Заметки и файлы на устройстве",
                    type = ChatType.SAVED,
                    isPinned = true,
                    unreadCount = unreadCounts[CHAT_ID_SAVED] ?: 0,
                    avatarColorIndex = 0
                )
            )
            add(
                ChatRoom(
                    id = CHAT_ID_AI,
                    title = "Volt AI",
                    subtitle = "Локальная нейросеть",
                    type = ChatType.AI,
                    isOnline = aiStatus == VoltAiStatus.READY,
                    isPinned = true,
                    unreadCount = unreadCounts[CHAT_ID_AI] ?: 0,
                    avatarColorIndex = 1
                )
            )
            lanPeers.forEachIndexed { idx, peer ->
                val cid = "lan_${peer.id}"
                add(
                    ChatRoom(
                        id = cid,
                        title = peer.nickname,
                        subtitle = "Сервер: ${peer.serverName} • ${peer.host}",
                        type = ChatType.LOCAL_LAN,
                        isOnline = true,
                        unreadCount = unreadCounts[cid] ?: 0,
                        avatarColorIndex = (idx + 2) % 3,
                        peerHost = peer.host,
                        peerPort = peer.port,
                        serverName = peer.serverName
                    )
                )
            }
        }
    }

    fun sendToAi(promptText: String, replyTo: ReplyPreview?, attachment: AttachmentInfo?) {
        if (isAiGenerating) return

        val effectiveText = promptText.ifBlank {
            attachment?.let { "Прикреплён файл: ${it.name}" }.orEmpty()
        }

        appendMessage(
            CHAT_ID_AI,
            ChatMessage(
                chatId = CHAT_ID_AI,
                senderName = nickname,
                text = promptText,
                isFromMe = true,
                replyTo = replyTo,
                attachment = attachment
            )
        )

        // Встроенная команда «инструкция» работает всегда, даже офлайн
        val normalized = effectiveText.trim().lowercase().trimEnd('!', '.', '?')
        if (normalized == "инструкция" || normalized == "/help" || normalized == "помощь") {
            appendMessage(
                CHAT_ID_AI,
                ChatMessage(
                    chatId = CHAT_ID_AI,
                    senderName = "Volt AI",
                    text = VOLT_AI_GUIDE_TEXT,
                    isFromMe = false
                )
            )
            return
        }

        val replyId = UUID.randomUUID().toString()
        appendMessage(
            CHAT_ID_AI,
            ChatMessage(
                id = replyId,
                chatId = CHAT_ID_AI,
                senderName = "Volt AI",
                text = "Печатает...",
                isFromMe = false
            )
        )
        isAiGenerating = true

        val history = (messagesByChat[CHAT_ID_AI] ?: emptyList())
            .filter { !it.isMediaDemo && it.id != replyId && it.text != VOLT_AI_GUIDE_TEXT && it.text.isNotBlank() }
            .takeLast(12)
            .map { AiChatMessage(if (it.isFromMe) "user" else "assistant", it.text) }

        aiJob = scope.launch {
            val buffer = StringBuilder()
            var lastUiUpdate = 0L

            VoltAiClient.streamCompletion(history)
                .catch {
                    buffer.append("Нет связи с сервером Volt AI (127.0.0.1:8080). Открой Volt AI и включи сервер, или напиши «инструкция».")
                    updateMessageText(CHAT_ID_AI, replyId, buffer.toString())
                    aiStatus = VoltAiStatus.SERVER_OFFLINE
                }
                .collect { chunk ->
                    buffer.append(chunk)
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdate >= 35L) {
                        updateMessageText(CHAT_ID_AI, replyId, buffer.toString())
                        lastUiUpdate = now
                    }
                }

            if (buffer.isNotEmpty()) {
                updateMessageText(CHAT_ID_AI, replyId, buffer.toString())
            }
            persistAll()
            isAiGenerating = false
        }
    }

    val currentChat = allChats.firstOrNull { it.id == activeChatId }

    BackHandler(enabled = currentChat != null) {
        activeChatId = null
    }

    AnimatedContent(
        targetState = currentChat,
        transitionSpec = {
            val spec = tween<androidx.compose.ui.unit.IntOffset>(240, easing = FastOutSlowInEasing)
            if (targetState != null) {
                (slideInHorizontally(spec) { it } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(spec) { -it / 4 } + fadeOut(tween(160)))
            } else {
                (slideInHorizontally(spec) { -it / 4 } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(spec) { it } + fadeOut(tween(160)))
            }
        },
        label = "screenNav"
    ) { chat ->
        if (chat == null) {
            ChatListScreen(
                chats = allChats,
                messagesByChat = messagesByChat,
                aiStatus = aiStatus,
                isLanActive = isLanActive,
                lanPort = lanPort,
                lanPeers = lanPeers,
                nickname = nickname,
                serverName = serverName,
                fingerprintEmojis = fingerprint,
                onIdentityChange = { newNick, newServer ->
                    nickname = newNick
                    serverName = newServer
                    storage.setNickname(newNick)
                    storage.setServerName(newServer)
                    lanMessenger.updateIdentity(newNick, newServer)
                },
                onChatClick = { clicked ->
                    unreadCounts[clicked.id] = 0
                    activeChatId = clicked.id
                },
                onOpenAiClick = {
                    unreadCounts[CHAT_ID_AI] = 0
                    activeChatId = CHAT_ID_AI
                },
                onOpenSavedClick = {
                    unreadCounts[CHAT_ID_SAVED] = 0
                    activeChatId = CHAT_ID_SAVED
                }
            )
        } else {
            ChatScreen(
                chat = chat,
                messages = messagesByChat[chat.id] ?: emptyList(),
                aiStatus = aiStatus,
                isAiGenerating = isAiGenerating,
                onBack = { activeChatId = null },
                onPickAttachment = { uri -> storage.copyUriToAttachment(uri) },
                onSendMessage = { text, reply, attachment ->
                    when (chat.type) {
                        ChatType.AI -> sendToAi(text, reply, attachment)
                        ChatType.SAVED -> {
                            appendMessage(
                                chat.id,
                                ChatMessage(
                                    chatId = chat.id,
                                    senderName = nickname,
                                    text = text,
                                    isFromMe = true,
                                    replyTo = reply,
                                    attachment = attachment
                                )
                            )
                        }
                        ChatType.LOCAL_LAN -> {
                            appendMessage(
                                chat.id,
                                ChatMessage(
                                    chatId = chat.id,
                                    senderName = nickname,
                                    text = text,
                                    isFromMe = true,
                                    replyTo = reply,
                                    attachment = attachment
                                )
                            )
                            val peer = lanPeers.firstOrNull { "lan_${it.id}" == chat.id }
                            if (peer != null) {
                                val wireText = if (attachment != null && text.isBlank()) {
                                    "📎 ${attachment.name} (${attachment.formattedSize()})"
                                } else if (attachment != null) {
                                    "$text\n📎 ${attachment.name}"
                                } else {
                                    text
                                }
                                scope.launch { lanMessenger.sendMessage(peer, wireText) }
                            }
                        }
                    }
                },
                onStopAi = {
                    aiJob?.cancel()
                    isAiGenerating = false
                },
                onDeleteMessage = { msg ->
                    val current = messagesByChat[chat.id] ?: emptyList()
                    messagesByChat[chat.id] = current.filterNot { it.id == msg.id }
                    persistAll()
                },
                onOpenVoltAi = { VoltAiClient.launchVoltAi(context) }
            )
        }
    }
}
