package com.voltage.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
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
                            text = "Личное пространство для заметок, идей и пересылки сообщений.",
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
                            text = "Локальный ИИ готов. Можно писать сюда напрямую или пересылать сообщения из других чатов долгим нажатием.",
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
                    subtitle = "Заметки на устройстве",
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
                        title = peer.name,
                        subtitle = "Wi-Fi • ${peer.host}",
                        type = ChatType.LOCAL_LAN,
                        isOnline = true,
                        unreadCount = unreadCounts[cid] ?: 0,
                        avatarColorIndex = (idx + 2) % 3,
                        peerHost = peer.host,
                        peerPort = peer.port
                    )
                )
            }
        }
    }

    fun sendToAi(promptText: String, replyTo: ReplyPreview?) {
        if (isAiGenerating) return
        appendMessage(
            CHAT_ID_AI,
            ChatMessage(
                chatId = CHAT_ID_AI,
                senderName = nickname,
                text = promptText,
                isFromMe = true,
                replyTo = replyTo
            )
        )

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
            .filter { !it.isMediaDemo && it.id != replyId }
            .takeLast(12)
            .map { AiChatMessage(if (it.isFromMe) "user" else "assistant", it.text) }

        aiJob = scope.launch {
            val buffer = StringBuilder()
            var lastUiUpdate = 0L

            VoltAiClient.streamCompletion(history)
                .catch {
                    buffer.append("Нет связи с сервером Volt AI (127.0.0.1:8080). Открой Volt AI и включи сервер.")
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
            if (targetState != null) {
                (slideInHorizontally(tween(240)) { it } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(tween(240)) { -it / 4 } + fadeOut(tween(160)))
            } else {
                (slideInHorizontally(tween(240)) { -it / 4 } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(tween(240)) { it } + fadeOut(tween(160)))
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
                lanPeerCount = lanPeers.size,
                nickname = nickname,
                fingerprintEmojis = fingerprint,
                onNicknameChange = { newName ->
                    nickname = newName
                    storage.setNickname(newName)
                    lanMessenger.restartWithNickname(newName)
                },
                onChatClick = { clicked ->
                    unreadCounts[clicked.id] = 0
                    activeChatId = clicked.id
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
                onSendMessage = { text, reply ->
                    when (chat.type) {
                        ChatType.AI -> sendToAi(text, reply)
                        ChatType.SAVED -> {
                            appendMessage(
                                chat.id,
                                ChatMessage(
                                    chatId = chat.id,
                                    senderName = nickname,
                                    text = text,
                                    isFromMe = true,
                                    replyTo = reply
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
                                    replyTo = reply
                                )
                            )
                            val peer = lanPeers.firstOrNull { "lan_${it.id}" == chat.id }
                            if (peer != null) {
                                scope.launch { lanMessenger.sendMessage(peer, text) }
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
                onAskAiAboutMessage = { msg ->
                    activeChatId = CHAT_ID_AI
                    sendToAi(
                        promptText = "Объясни или ответь на сообщение:\n«${msg.text}»",
                        replyTo = ReplyPreview(msg.senderName, msg.text)
                    )
                },
                onOpenVoltAi = { VoltAiClient.launchVoltAi(context) }
            )
        }
    }
}
