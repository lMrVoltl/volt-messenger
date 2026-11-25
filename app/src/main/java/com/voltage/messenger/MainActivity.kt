package com.voltage.messenger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.voltage.messenger.core.ai.AiChatMessage
import com.voltage.messenger.core.ai.VoltAiClient
import com.voltage.messenger.core.ai.VoltAiStatus
import com.voltage.messenger.core.media.VoiceAudioManager
import com.voltage.messenger.core.network.LanMessenger
import com.voltage.messenger.core.storage.ChatStorage
import com.voltage.messenger.model.AttachmentInfo
import com.voltage.messenger.model.CallStatus
import com.voltage.messenger.model.ChatMessage
import com.voltage.messenger.model.ChatRoom
import com.voltage.messenger.model.ChatType
import com.voltage.messenger.model.ReplyPreview
import com.voltage.messenger.ui.screens.CallOverlay
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
• В этом чате — пиши любые вопросы, ответы печатаются локально прямо на телефоне.
• В любом другом чате — нажми коротким тапом на сообщение → «Спросить у Volt AI». Откроется мини-окно поверх чата с готовым ответом.

💡 Чтобы снова открыть эту памятку, напиши слово: инструкция
""".trimIndent()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        unlockHighRefreshRate()
        setContent {
            VoltMessengerTheme {
                VoltMessengerApp()
            }
        }
    }

    // Запрашиваем максимальную герцовку экрана (120 Гц), чтобы система не резала анимации до 60 Гц
    private fun unlockHighRefreshRate() {
        try {
            val modes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.supportedModes
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay?.supportedModes
            }
            val maxRate = modes?.maxOfOrNull { it.refreshRate } ?: return
            val lp = window.attributes
            lp.preferredRefreshRate = maxRate
            window.attributes = lp
        } catch (_: Exception) {
        }
    }
}

@Composable
fun VoltMessengerApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val storage = remember { ChatStorage(context) }
    val voiceManager = remember { VoiceAudioManager(context) }
    val voicePlayback by voiceManager.playback.collectAsState()

    var nickname by remember { mutableStateOf(storage.getNickname()) }
    var serverName by remember { mutableStateOf(storage.getServerName()) }
    val fingerprint = remember { storage.getKeyFingerprintEmojis() }

    var activeChatId by remember { mutableStateOf<String?>(null) }
    var aiStatus by remember { mutableStateOf(VoltAiStatus.CHECKING) }
    var isAiGenerating by remember { mutableStateOf(false) }
    var aiJob by remember { mutableStateOf<Job?>(null) }

    val unreadCounts = remember { mutableStateMapOf<String, Int>() }
    val knownPeers = remember { mutableStateMapOf<String, Pair<String, String>>().apply { putAll(storage.loadKnownPeers()) } }
    val messagesByChat = remember {
        mutableStateMapOf<String, List<ChatMessage>>().apply {
            storage.loadMessages().forEach { (k, v) -> put(k, v) }
            if (get(CHAT_ID_SAVED).isNullOrEmpty()) {
                put(
                    CHAT_ID_SAVED,
                    listOf(
                        ChatMessage(
                            chatId = CHAT_ID_SAVED,
                            senderName = "Избранное",
                            text = "Личное пространство для заметок, файлов и голосовых. Нажми коротким тапом на сообщение для меню и Volt AI.",
                            isFromMe = false
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

    fun upsertMessage(chatId: String, msg: ChatMessage, countUnread: Boolean = true) {
        val current = messagesByChat[chatId] ?: emptyList()
        val idx = current.indexOfFirst { it.id == msg.id }
        if (idx >= 0) {
            val copy = current.toMutableList()
            copy[idx] = msg
            messagesByChat[chatId] = copy
        } else {
            messagesByChat[chatId] = current + msg
            if (countUnread && activeChatId != chatId && !msg.isFromMe) {
                unreadCounts[chatId] = (unreadCounts[chatId] ?: 0) + 1
            }
        }
        if (msg.transferProgress == null) persistAll()
    }

    fun updateMessageText(chatId: String, msgId: String, newText: String) {
        val current = messagesByChat[chatId] ?: return
        messagesByChat[chatId] = current.map { if (it.id == msgId) it.copy(text = newText) else it }
    }

    fun updateTransferProgress(chatId: String, msgId: String, progress: Float?, speedMbps: Float?, attachment: AttachmentInfo? = null) {
        val current = messagesByChat[chatId] ?: return
        messagesByChat[chatId] = current.map {
            if (it.id == msgId) {
                it.copy(
                    transferProgress = progress,
                    transferSpeedMbps = speedMbps,
                    attachment = attachment ?: it.attachment
                )
            } else it
        }
        if (progress == null) persistAll()
    }

    val lanMessenger = remember {
        LanMessenger(
            context = context,
            storage = storage,
            myDeviceId = storage.getDeviceId(),
            myNickname = nickname,
            myServerName = serverName,
            onEvent = { ev ->
                scope.launch {
                    val chatId = "lan_${ev.peerId}"
                    knownPeers[ev.peerId] = ev.senderName to ev.serverName
                    upsertMessage(
                        chatId = chatId,
                        msg = ChatMessage(
                            id = ev.messageId,
                            chatId = chatId,
                            senderName = ev.senderName,
                            text = ev.text,
                            isFromMe = false,
                            timestamp = ev.timestamp,
                            attachment = ev.attachment,
                            transferProgress = ev.progress,
                            transferSpeedMbps = ev.speedMbps,
                            isEncrypted = ev.isEncrypted
                        )
                    )
                }
            }
        )
    }

    val lanPeers by lanMessenger.peers.collectAsState()
    val isLanActive by lanMessenger.isLanActive.collectAsState()
    val lanPort by lanMessenger.localPort.collectAsState()
    val lanIp by lanMessenger.localIp.collectAsState()
    val callState by lanMessenger.callState.collectAsState()

    val acceptCallPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) lanMessenger.acceptIncomingCall()
        else {
            Toast.makeText(context, "Нужен доступ к микрофону для звонка", Toast.LENGTH_SHORT).show()
            lanMessenger.declineIncomingCall()
        }
    }

    DisposableEffect(Unit) {
        lanMessenger.start()
        onDispose {
            lanMessenger.destroy()
            voiceManager.destroy()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { aiStatus = VoltAiClient.checkStatus(context) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val allChats = remember(lanPeers, unreadCounts.toMap(), aiStatus, messagesByChat.keys.toSet(), knownPeers.toMap()) {
        buildList {
            add(
                ChatRoom(
                    id = CHAT_ID_SAVED,
                    title = "Избранное",
                    subtitle = "Заметки, файлы и голосовые",
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

            val activeIds = mutableSetOf<String>()
            lanPeers.forEachIndexed { idx, peer ->
                val cid = "lan_${peer.id}"
                activeIds.add(cid)
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

            // Сохраняем в списке чаты с теми, кто временно вышел из сети
            messagesByChat.keys.filter { it.startsWith("lan_") && it !in activeIds }.forEachIndexed { idx, cid ->
                val pid = cid.removePrefix("lan_")
                val meta = knownPeers[pid]
                add(
                    ChatRoom(
                        id = cid,
                        title = meta?.first ?: pid,
                        subtitle = "Не в сети",
                        type = ChatType.LOCAL_LAN,
                        isOnline = false,
                        unreadCount = unreadCounts[cid] ?: 0,
                        avatarColorIndex = (idx + 2) % 3,
                        serverName = meta?.second
                    )
                )
            }
        }
    }

    fun sendToAi(promptText: String, replyTo: ReplyPreview?, attachment: AttachmentInfo?) {
        if (isAiGenerating) return

        val effectiveText = promptText.ifBlank { attachment?.let { "Прикреплён файл: ${it.name}" }.orEmpty() }
        upsertMessage(
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

        val normalized = effectiveText.trim().lowercase().trimEnd('!', '.', '?')
        if (normalized == "инструкция" || normalized == "/help" || normalized == "помощь") {
            upsertMessage(
                CHAT_ID_AI,
                ChatMessage(chatId = CHAT_ID_AI, senderName = "Volt AI", text = VOLT_AI_GUIDE_TEXT, isFromMe = false)
            )
            return
        }

        val replyId = UUID.randomUUID().toString()
        upsertMessage(
            CHAT_ID_AI,
            ChatMessage(id = replyId, chatId = CHAT_ID_AI, senderName = "Volt AI", text = "Печатает...", isFromMe = false)
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
                    if (now - lastUiUpdate >= 32L) {
                        updateMessageText(CHAT_ID_AI, replyId, buffer.toString())
                        lastUiUpdate = now
                    }
                }

            if (buffer.isNotEmpty()) updateMessageText(CHAT_ID_AI, replyId, buffer.toString())
            persistAll()
            isAiGenerating = false
        }
    }

    val currentChat = allChats.firstOrNull { it.id == activeChatId }

    BackHandler(enabled = currentChat != null && callState.status == CallStatus.IDLE) {
        activeChatId = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = currentChat,
            transitionSpec = {
                val springSpec = spring<IntOffset>(dampingRatio = 0.92f, stiffness = 420f)
                if (targetState != null) {
                    (slideInHorizontally(springSpec) { it } + fadeIn(tween(180))) togetherWith
                        (slideOutHorizontally(springSpec) { -it / 5 } + fadeOut(tween(160)))
                } else {
                    (slideInHorizontally(springSpec) { -it / 5 } + fadeIn(tween(180))) togetherWith
                        (slideOutHorizontally(springSpec) { it } + fadeOut(tween(160)))
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
                    lanIp = lanIp,
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
                    onAddManualPeer = { ip, port ->
                        lanMessenger.addManualPeer(ip, port)
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
                    voicePlayback = voicePlayback,
                    onBack = { activeChatId = null },
                    onPickAttachmentMeta = { uri -> storage.readAttachmentMeta(uri) },
                    onStartVoiceRecord = { voiceManager.startRecording() },
                    onStopVoiceRecord = { discard ->
                        val res = voiceManager.stopRecording(discard)
                        if (res != null) {
                            val (file, sec) = res
                            AttachmentInfo(
                                name = file.name,
                                localPath = file.absolutePath,
                                mimeType = "audio/mp4",
                                sizeBytes = file.length(),
                                isVoice = true,
                                durationSec = sec
                            )
                        } else null
                    },
                    onToggleVoicePlay = { msgId, path -> voiceManager.togglePlay(msgId, path) },
                    onSeekVoice = { msgId, frac -> voiceManager.seekTo(msgId, frac) },
                    onStartAudioCall = {
                        val peer = lanPeers.firstOrNull { "lan_${it.id}" == chat.id }
                        if (peer != null) lanMessenger.startCall(peer)
                    },
                    onSendMessage = { text, reply, attachment ->
                        when (chat.type) {
                            ChatType.AI -> sendToAi(text, reply, attachment)

                            ChatType.SAVED -> {
                                val msgId = UUID.randomUUID().toString()
                                upsertMessage(
                                    chat.id,
                                    ChatMessage(
                                        id = msgId,
                                        chatId = chat.id,
                                        senderName = nickname,
                                        text = text,
                                        isFromMe = true,
                                        replyTo = reply,
                                        attachment = attachment
                                    )
                                )
                                if (attachment != null && attachment.localPath.isEmpty()) {
                                    scope.launch {
                                        val copied = storage.copyUriToLocal(attachment)
                                        updateTransferProgress(chat.id, msgId, null, null, copied)
                                    }
                                }
                            }

                            ChatType.LOCAL_LAN -> {
                                val peer = lanPeers.firstOrNull { "lan_${it.id}" == chat.id }
                                val msgId = UUID.randomUUID().toString()
                                upsertMessage(
                                    chat.id,
                                    ChatMessage(
                                        id = msgId,
                                        chatId = chat.id,
                                        senderName = nickname,
                                        text = text,
                                        isFromMe = true,
                                        replyTo = reply,
                                        attachment = attachment,
                                        transferProgress = if (attachment != null) 0f else null,
                                        isEncrypted = true
                                    )
                                )

                                if (peer == null) {
                                    Toast.makeText(context, "Собеседник сейчас не в сети", Toast.LENGTH_SHORT).show()
                                    updateTransferProgress(chat.id, msgId, null, null)
                                    return@ChatScreen
                                }

                                scope.launch {
                                    if (attachment != null) {
                                        val ok = lanMessenger.sendFileMessage(peer, text, attachment) { p, mbps ->
                                            scope.launch {
                                                updateTransferProgress(chat.id, msgId, if (p >= 1f) null else p, mbps)
                                            }
                                        }
                                        updateTransferProgress(chat.id, msgId, null, null)
                                        if (!ok) {
                                            Toast.makeText(context, "Ошибка передачи файла", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        val ok = lanMessenger.sendTextMessage(peer, text)
                                        if (!ok) {
                                            Toast.makeText(context, "Не удалось доставить сообщение", Toast.LENGTH_SHORT).show()
                                        }
                                    }
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

        // Полноэкранный экран входящего / активного зашифрованного аудиозвонка
        AnimatedVisibility(
            visible = callState.status != CallStatus.IDLE,
            enter = slideInVertically(spring(dampingRatio = 0.88f, stiffness = 400f)) { it } + fadeIn(tween(180)),
            exit = slideOutVertically(spring(dampingRatio = 0.9f, stiffness = 420f)) { it } + fadeOut(tween(160))
        ) {
            CallOverlay(
                state = callState,
                onAccept = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (granted) lanMessenger.acceptIncomingCall()
                    else acceptCallPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onDecline = { lanMessenger.declineIncomingCall() },
                onEnd = { lanMessenger.endCall() },
                onToggleMute = { lanMessenger.toggleMute() },
                onToggleSpeaker = { lanMessenger.toggleSpeaker() }
            )
        }
    }
}
