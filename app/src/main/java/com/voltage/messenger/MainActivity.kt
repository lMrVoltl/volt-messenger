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
import androidx.compose.runtime.LaunchedEffect
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
import com.voltage.messenger.core.ai.AiAppAction
import com.voltage.messenger.core.ai.AiChatMessage
import com.voltage.messenger.core.ai.VoltAiAssistant
import com.voltage.messenger.core.ai.VoltAiClient
import com.voltage.messenger.core.ai.VoltAiStatus
import com.voltage.messenger.core.media.VoiceAudioManager
import com.voltage.messenger.core.network.IncomingLanEvent
import com.voltage.messenger.core.network.LanMessenger
import com.voltage.messenger.core.network.RelayClient
import com.voltage.messenger.core.network.RelayConnectionState
import com.voltage.messenger.core.storage.ChatStorage
import com.voltage.messenger.model.AppThemeConfig
import com.voltage.messenger.model.AttachmentInfo
import com.voltage.messenger.model.CallStatus
import com.voltage.messenger.model.ChannelMeta
import com.voltage.messenger.model.ChatMessage
import com.voltage.messenger.model.ChatRoom
import com.voltage.messenger.model.ChatType
import com.voltage.messenger.model.ReplyPreview
import com.voltage.messenger.ui.screens.CallOverlay
import com.voltage.messenger.ui.screens.ChatListScreen
import com.voltage.messenger.ui.screens.ChatScreen
import com.voltage.messenger.ui.screens.SettingsScreen
import com.voltage.messenger.ui.theme.VoltMessengerTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.UUID

private const val CHAT_ID_SAVED = "saved_messages"
private const val CHAT_ID_AI = "volt_ai_local"
private const val GROUP_ID_ROOM_ALL = "room_all"

private val VOLT_AI_GUIDE_TEXT = """
📘 Как пользоваться локальным ИИ (Volt AI)

1. Настройка в приложении Volt AI:
• Установи приложение Volt AI и скачай хотя бы одну языковую модель (или прими файл .gguf от друга прямо здесь — он сам сохранится в папку VoltModels).
• Загрузи модель в память и включи локальный сервер в настройках Volt AI.

2. Возможности в Volt Messenger:
• Пиши вопросы или прикрепляй фото (скрепка).
• Управляй приложением текстом: напиши «измени ник на ...», «включи светлую тему», «создай канал ...» или «открой настройки».
• В любом другом чате нажми коротким тапом на сообщение → «Спросить у Volt AI».

💡 Напиши слово «инструкция», чтобы открыть эту памятку снова.
""".trimIndent()

private sealed interface AppRoute {
    data object List : AppRoute
    data class Chat(val chatId: String) : AppRoute
    data object Settings : AppRoute
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        unlockHighRefreshRate()
        setContent {
            val storage = remember { ChatStorage(this) }
            var themeConfig by remember { mutableStateOf(storage.loadThemeConfig()) }
            VoltMessengerTheme(config = themeConfig) {
                VoltMessengerApp(
                    storage = storage,
                    themeConfig = themeConfig,
                    onThemeConfigChange = { themeConfig = it; storage.saveThemeConfig(it) }
                )
            }
        }
    }

    private fun unlockHighRefreshRate() {
        try {
            val modes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.supportedModes
            else { @Suppress("DEPRECATION") windowManager.defaultDisplay?.supportedModes }
            val maxRate = modes?.maxOfOrNull { it.refreshRate } ?: return
            val lp = window.attributes; lp.preferredRefreshRate = maxRate; window.attributes = lp
        } catch (_: Exception) {}
    }
}

@Composable
fun VoltMessengerApp(
    storage: ChatStorage,
    themeConfig: AppThemeConfig,
    onThemeConfigChange: (AppThemeConfig) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val myShortId = remember { storage.getShortId() }
    val voiceManager = remember { VoiceAudioManager(context) }
    val voicePlayback by voiceManager.playback.collectAsState()

    var nickname by remember { mutableStateOf(storage.getNickname()) }
    var serverName by remember { mutableStateOf(storage.getServerName()) }
    val fingerprint = remember { storage.getKeyFingerprintEmojis() }

    var route by remember { mutableStateOf<AppRoute>(AppRoute.List) }
    var aiStatus by remember { mutableStateOf(VoltAiStatus.CHECKING) }
    var isAiGenerating by remember { mutableStateOf(false) }
    var aiJob by remember { mutableStateOf<Job?>(null) }

    val initialPins = remember { storage.loadPins() }
    var pinnedChats by remember {
        mutableStateOf(if (initialPins.first.isEmpty()) setOf(CHAT_ID_SAVED, CHAT_ID_AI, "grp_$GROUP_ID_ROOM_ALL") else initialPins.first)
    }
    val pinnedMessages = remember { mutableStateMapOf<String, String>().apply { putAll(initialPins.second) } }

    val unreadCounts = remember { mutableStateMapOf<String, Int>() }
    val knownPeers = remember { mutableStateMapOf<String, Pair<String, String>>().apply { putAll(storage.loadKnownPeers()) } }
    val knownGroups = remember { mutableStateMapOf<String, String>().apply { put(GROUP_ID_ROOM_ALL, "Общая комната"); putAll(storage.loadKnownGroups()) } }
    val knownChannels = remember { mutableStateMapOf<String, ChannelMeta>().apply { putAll(storage.loadChannels()) } }

    val messagesByChat = remember {
        mutableStateMapOf<String, List<ChatMessage>>().apply {
            storage.loadMessages().forEach { (k, v) -> put(k, v) }
            if (get(CHAT_ID_SAVED).isNullOrEmpty()) {
                put(CHAT_ID_SAVED, listOf(ChatMessage(chatId = CHAT_ID_SAVED, senderName = "Избранное", text = "Личное пространство для заметок, файлов, опросов и голосовых.", isFromMe = false)))
            }
            if (get(CHAT_ID_AI).isNullOrEmpty()) {
                put(CHAT_ID_AI, listOf(ChatMessage(chatId = CHAT_ID_AI, senderName = "Volt AI", text = VOLT_AI_GUIDE_TEXT, isFromMe = false)))
            }
        }
    }

    fun persistAll() { storage.saveMessages(messagesByChat.toMap()) }
    fun persistPins() { storage.savePins(pinnedChats, pinnedMessages.toMap()) }

    fun upsertMessage(chatId: String, msg: ChatMessage, countUnread: Boolean = true, preserveIfExists: Boolean = false) {
        val current = messagesByChat[chatId] ?: emptyList()
        val idx = current.indexOfFirst { it.id == msg.id }
        if (idx >= 0) {
            if (preserveIfExists && msg.transferProgress == null && current[idx].transferProgress == null) return
            val copy = current.toMutableList(); copy[idx] = msg; messagesByChat[chatId] = copy
        } else {
            messagesByChat[chatId] = current + msg
            val activeChat = (route as? AppRoute.Chat)?.chatId
            if (countUnread && activeChat != chatId && !msg.isFromMe) unreadCounts[chatId] = (unreadCounts[chatId] ?: 0) + 1
        }
        if (msg.transferProgress == null) persistAll()
    }

    fun applyRemoteReaction(chatId: String, msgId: String, emoji: String, delta: Int) {
        val list = messagesByChat[chatId] ?: return
        messagesByChat[chatId] = list.map { m ->
            if (m.id == msgId) {
                val updated = m.reactions.toMutableMap()
                val next = ((updated[emoji] ?: 0) + delta).coerceAtLeast(0)
                if (next == 0) updated.remove(emoji) else updated[emoji] = next
                m.copy(reactions = updated)
            } else m
        }
        persistAll()
    }

    fun applyRemotePollVote(chatId: String, msgId: String, optionIndex: Int, delta: Int) {
        val list = messagesByChat[chatId] ?: return
        messagesByChat[chatId] = list.map { m ->
            val p = m.poll
            if (m.id == msgId && p != null) {
                val updated = p.votes.toMutableMap()
                val next = ((updated[optionIndex] ?: 0) + delta).coerceAtLeast(0)
                if (next == 0) updated.remove(optionIndex) else updated[optionIndex] = next
                m.copy(poll = p.copy(votes = updated))
            } else m
        }
        persistAll()
    }

    fun handleIncomingNetworkEvent(ev: IncomingLanEvent) {
        scope.launch {
            val chatId = when {
                ev.channelId != null -> {
                    knownChannels[ev.channelId] = ChannelMeta(ev.channelId, ev.channelName ?: knownChannels[ev.channelId]?.title ?: "Канал", ev.channelOwnerId ?: ev.peerId)
                    "ch_${ev.channelId}"
                }
                ev.groupId != null -> {
                    knownGroups[ev.groupId] = ev.groupName ?: knownGroups[ev.groupId] ?: "Группа"
                    "grp_${ev.groupId}"
                }
                else -> {
                    knownPeers[ev.peerId] = ev.senderName to ev.serverName
                    "lan_${ev.peerId}"
                }
            }

            when {
                ev.reactionEmoji != null -> applyRemoteReaction(chatId, ev.messageId, ev.reactionEmoji, ev.reactionDelta)
                ev.pollVoteOption != null -> applyRemotePollVote(chatId, ev.messageId, ev.pollVoteOption, ev.pollVoteDelta)
                else -> upsertMessage(
                    chatId = chatId,
                    msg = ChatMessage(
                        id = ev.messageId, chatId = chatId, senderName = ev.senderName, text = ev.text,
                        isFromMe = false, timestamp = ev.timestamp, attachment = ev.attachment, poll = ev.poll,
                        transferProgress = ev.progress, transferSpeedMbps = ev.speedMbps, isEncrypted = ev.isEncrypted
                    ),
                    preserveIfExists = true
                )
            }
        }
    }

    fun updateMessageText(chatId: String, msgId: String, newText: String) {
        val current = messagesByChat[chatId] ?: return
        messagesByChat[chatId] = current.map { if (it.id == msgId) it.copy(text = newText) else it }
    }

    fun updateTransferProgress(chatId: String, msgId: String, progress: Float?, speedMbps: Float?, attachment: AttachmentInfo? = null) {
        val current = messagesByChat[chatId] ?: return
        messagesByChat[chatId] = current.map {
            if (it.id == msgId) it.copy(transferProgress = progress, transferSpeedMbps = speedMbps, attachment = attachment ?: it.attachment) else it
        }
        if (progress == null) persistAll()
    }

    val lanMessenger = remember {
        LanMessenger(context, storage, storage.getDeviceId(), nickname, serverName, onEvent = { handleIncomingNetworkEvent(it) })
    }

    val relayClient = remember {
        RelayClient(storage, myShortId, nickname, serverName, onEvent = { handleIncomingNetworkEvent(it) })
    }

    val lanPeers by lanMessenger.peers.collectAsState()
    val isLanActive by lanMessenger.isLanActive.collectAsState()
    val lanPort by lanMessenger.localPort.collectAsState()
    val lanIp by lanMessenger.localIp.collectAsState()
    val callState by lanMessenger.callState.collectAsState()

    val relayState by relayClient.state.collectAsState()
    val relayPeers by relayClient.onlinePeers.collectAsState()

    LaunchedEffect(themeConfig.relayEnabled, themeConfig.relayUrl) {
        relayClient.configure(themeConfig.relayEnabled, themeConfig.relayUrl, nickname, serverName)
    }

    val acceptCallPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) lanMessenger.acceptIncomingCall()
        else { Toast.makeText(context, "Нужен доступ к микрофону", Toast.LENGTH_SHORT).show(); lanMessenger.declineIncomingCall() }
    }

    DisposableEffect(Unit) {
        lanMessenger.start()
        onDispose { lanMessenger.destroy(); relayClient.destroy(); voiceManager.destroy() }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { aiStatus = VoltAiClient.checkStatus(context) }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun updateIdentity(newNick: String, newServer: String) {
        nickname = newNick; serverName = newServer
        storage.setNickname(newNick); storage.setServerName(newServer)
        lanMessenger.updateIdentity(newNick, newServer)
        relayClient.configure(themeConfig.relayEnabled, themeConfig.relayUrl, newNick, newServer)
    }

    val allChats = remember(lanPeers, relayPeers, relayState, unreadCounts.toMap(), aiStatus, messagesByChat.keys.toSet(), knownPeers.toMap(), knownGroups.toMap(), knownChannels.toMap(), pinnedChats) {
        val totalOnlineCount = (lanPeers.map { it.id } + relayPeers.map { it.id }).toSet().size
        val cloudConnected = relayState == RelayConnectionState.CONNECTED

        buildList {
            add(ChatRoom(CHAT_ID_SAVED, "Избранное", "Заметки, файлы и голосовые", ChatType.SAVED, isPinned = CHAT_ID_SAVED in pinnedChats, unreadCount = unreadCounts[CHAT_ID_SAVED] ?: 0, avatarColorIndex = 0))
            add(ChatRoom(CHAT_ID_AI, "Volt AI", "Локальная нейросеть", ChatType.AI, isOnline = aiStatus == VoltAiStatus.READY, isPinned = CHAT_ID_AI in pinnedChats, unreadCount = unreadCounts[CHAT_ID_AI] ?: 0, avatarColorIndex = 1))

            knownGroups.forEach { (gid, gname) ->
                val cid = "grp_$gid"
                val sub = if (cloudConnected) "Облако + LAN • в сети: ${totalOnlineCount + 1}" else "Локальная группа • рядом: ${lanPeers.size + 1}"
                add(ChatRoom(cid, gname, sub, ChatType.GROUP_LAN, isOnline = totalOnlineCount > 0 || cloudConnected, isCloudOnline = cloudConnected, isPinned = cid in pinnedChats, unreadCount = unreadCounts[cid] ?: 0, avatarColorIndex = 2))
            }

            knownChannels.values.forEach { ch ->
                val cid = "ch_${ch.id}"
                val isOwner = ch.ownerId == myShortId
                add(ChatRoom(cid, ch.title, if (isOwner) "Твой канал • вещание" else "Канал", ChatType.CHANNEL_LAN, isOnline = totalOnlineCount > 0 || cloudConnected, isCloudOnline = cloudConnected, isPinned = cid in pinnedChats, unreadCount = unreadCounts[cid] ?: 0, avatarColorIndex = 0, isChannelOwner = isOwner))
            }

            val addedIds = mutableSetOf<String>()

            // 1. Устройства рядом по локальной сети (Wi-Fi / Точка доступа)
            lanPeers.forEachIndexed { idx, peer ->
                val cid = "lan_${peer.id}"
                addedIds.add(cid)
                val alsoCloud = relayPeers.any { it.id == peer.id }
                add(ChatRoom(cid, peer.nickname, "Wi-Fi • ${peer.serverName}", ChatType.LOCAL_LAN, isOnline = true, isCloudOnline = alsoCloud, isPinned = cid in pinnedChats, unreadCount = unreadCounts[cid] ?: 0, avatarColorIndex = (idx + 2) % 3, peerHost = peer.host, peerPort = peer.port, serverName = peer.serverName))
            }

            // 2. Пользователи онлайн через Cloudflare Relay (мобильный интернет / другой Wi-Fi)
            relayPeers.forEachIndexed { idx, rPeer ->
                val cid = "lan_${rPeer.id}"
                if (cid !in addedIds) {
                    addedIds.add(cid)
                    add(ChatRoom(cid, rPeer.nickname, "☁️ Облако • онлайн", ChatType.LOCAL_LAN, isOnline = true, isCloudOnline = true, isPinned = cid in pinnedChats, unreadCount = unreadCounts[cid] ?: 0, avatarColorIndex = (idx + 1) % 3, serverName = "☁️ ${rPeer.serverName}"))
                }
            }

            // 3. Офлайн-чаты с историей (можно писать — уйдут в SQLite-буфер на Cloudflare)
            messagesByChat.keys.filter { it.startsWith("lan_") && it !in addedIds }.forEachIndexed { idx, cid ->
                val pid = cid.removePrefix("lan_")
                val meta = knownPeers[pid]
                add(ChatRoom(cid, meta?.first ?: pid, if (cloudConnected) "Офлайн • доставка через облако" else "Не в сети", ChatType.LOCAL_LAN, isOnline = false, isCloudOnline = false, isPinned = cid in pinnedChats, unreadCount = unreadCounts[cid] ?: 0, avatarColorIndex = (idx + 2) % 3, serverName = meta?.second))
            }
        }
    }

    fun executeAiAction(act: AiAppAction): String = when (act) {
        is AiAppAction.SetNickname -> { updateIdentity(act.nickname, serverName); "Ник изменён на «${act.nickname}»" }
        is AiAppAction.SetServerName -> { updateIdentity(nickname, act.serverName); "Сервер переименован в «${act.serverName}»" }
        is AiAppAction.SetThemeMode -> { onThemeConfigChange(themeConfig.copy(themeMode = act.mode)); "Включена ${listOf("системная", "светлая", "тёмная").getOrElse(act.mode) { "" }} тема" }
        is AiAppAction.SetAccentPreset -> { onThemeConfigChange(themeConfig.copy(accentPreset = act.preset)); "Палитра изменена на «${listOf("Material You", "Индиго", "Изумруд", "Аметист", "Закат").getOrElse(act.preset) { "" }}»" }
        is AiAppAction.CreateGroup -> { val gid = UUID.randomUUID().toString().take(8); knownGroups[gid] = act.name; storage.saveKnownGroup(gid, act.name); "Создана группа «${act.name}»" }
        is AiAppAction.CreateChannel -> { val chId = UUID.randomUUID().toString().take(8); val meta = ChannelMeta(chId, act.name, myShortId); knownChannels[chId] = meta; storage.saveChannel(meta); "Создан канал «${act.name}»" }
        is AiAppAction.OpenSettings -> { route = AppRoute.Settings; "Открыл настройки" }
    }

    fun sendToAi(promptText: String, replyTo: ReplyPreview?, attachment: AttachmentInfo?) {
        if (isAiGenerating) return
        val effectiveText = promptText.ifBlank { attachment?.let { "Прикреплён файл: ${it.name}" }.orEmpty() }
        val userMsgId = UUID.randomUUID().toString()
        upsertMessage(CHAT_ID_AI, ChatMessage(id = userMsgId, chatId = CHAT_ID_AI, senderName = nickname, text = promptText, isFromMe = true, replyTo = replyTo, attachment = attachment))

        val normalized = effectiveText.trim().lowercase().trimEnd('!', '.', '?')
        if (normalized == "инструкция" || normalized == "/help" || normalized == "помощь") {
            upsertMessage(CHAT_ID_AI, ChatMessage(chatId = CHAT_ID_AI, senderName = "Volt AI", text = VOLT_AI_GUIDE_TEXT, isFromMe = false))
            return
        }

        val directAction = VoltAiAssistant.detectDirectAction(effectiveText, themeConfig.aiControlEnabled)
        if (directAction != null) {
            val report = executeAiAction(directAction)
            upsertMessage(CHAT_ID_AI, ChatMessage(chatId = CHAT_ID_AI, senderName = "Volt AI", text = "⚡ Готово! $report.", isFromMe = false))
            return
        }

        val replyId = UUID.randomUUID().toString()
        upsertMessage(CHAT_ID_AI, ChatMessage(id = replyId, chatId = CHAT_ID_AI, senderName = "Volt AI", text = "Печатает...", isFromMe = false))
        isAiGenerating = true

        aiJob = scope.launch {
            var readyImagePath: String? = null
            if (attachment != null && attachment.isImage) {
                val localAtt = storage.copyUriToLocal(attachment)
                updateTransferProgress(CHAT_ID_AI, userMsgId, null, null, localAtt)
                readyImagePath = localAtt.localPath
            }

            val compactSystem = VoltAiAssistant.buildSystemPrompt(themeConfig.aiSystemPrompt, nickname, serverName, themeConfig, lanPeers.size + relayPeers.size)
            val history = (messagesByChat[CHAT_ID_AI] ?: emptyList())
                .filter { !it.isMediaDemo && it.id != replyId && it.text != VOLT_AI_GUIDE_TEXT && (it.text.isNotBlank() || it.id == userMsgId) }
                .takeLast(6)
                .map { msg ->
                    AiChatMessage(if (msg.isFromMe) "user" else "assistant", msg.text.ifBlank { "Опиши это изображение." }, if (msg.id == userMsgId) readyImagePath else null)
                }

            val buffer = StringBuilder(); var lastUiUpdate = 0L
            VoltAiClient.streamCompletion(history, compactSystem)
                .catch { e ->
                    buffer.append("Нет связи с сервером Volt AI (${e.message ?: "выключен"}). Включи сервер в приложении Volt AI.")
                    updateMessageText(CHAT_ID_AI, replyId, buffer.toString())
                    aiStatus = VoltAiStatus.SERVER_OFFLINE
                }
                .collect { chunk ->
                    buffer.append(chunk)
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdate >= 35L) { updateMessageText(CHAT_ID_AI, replyId, buffer.toString()); lastUiUpdate = now }
                }

            updateMessageText(CHAT_ID_AI, replyId, VoltAiAssistant.parseResponse(buffer.toString()).cleanText.ifBlank { "Нет ответа от модели." })
            persistAll()
            isAiGenerating = false
        }
    }

    BackHandler(enabled = route !is AppRoute.List && callState.status == CallStatus.IDLE) { route = AppRoute.List }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                val springSpec = spring<IntOffset>(dampingRatio = 0.92f, stiffness = 420f)
                if (targetState !is AppRoute.List) (slideInHorizontally(springSpec) { it } + fadeIn(tween(180))) togetherWith (slideOutHorizontally(springSpec) { -it / 5 } + fadeOut(tween(160)))
                else (slideInHorizontally(springSpec) { -it / 5 } + fadeIn(tween(180))) togetherWith (slideOutHorizontally(springSpec) { it } + fadeOut(tween(160)))
            },
            label = "appNav"
        ) { currentRoute ->
            when (currentRoute) {
                is AppRoute.List -> ChatListScreen(
                    chats = allChats,
                    messagesByChat = messagesByChat,
                    aiStatus = aiStatus,
                    isLanActive = isLanActive || relayState == RelayConnectionState.CONNECTED,
                    lanIp = lanIp,
                    lanPort = lanPort,
                    lanPeers = lanPeers,
                    nickname = nickname,
                    serverName = if (relayState == RelayConnectionState.CONNECTED) "$serverName • ☁️" else serverName,
                    fingerprintEmojis = fingerprint,
                    onIdentityChange = { n, s -> updateIdentity(n, s) },
                    onAddManualPeer = { ip, port -> lanMessenger.addManualPeer(ip, port) },
                    onCreateGroup = { name -> val gid = UUID.randomUUID().toString().take(8); knownGroups[gid] = name; storage.saveKnownGroup(gid, name); route = AppRoute.Chat("grp_$gid") },
                    onCreateChannel = { name -> val chId = UUID.randomUUID().toString().take(8); val meta = ChannelMeta(chId, name, myShortId); knownChannels[chId] = meta; storage.saveChannel(meta); route = AppRoute.Chat("ch_$chId") },
                    onTogglePinChat = { cid -> pinnedChats = if (cid in pinnedChats) pinnedChats - cid else pinnedChats + cid; persistPins() },
                    onOpenSettings = { route = AppRoute.Settings },
                    onChatClick = { clicked -> unreadCounts[clicked.id] = 0; route = AppRoute.Chat(clicked.id) },
                    onOpenAiClick = { unreadCounts[CHAT_ID_AI] = 0; route = AppRoute.Chat(CHAT_ID_AI) },
                    onOpenSavedClick = { unreadCounts[CHAT_ID_SAVED] = 0; route = AppRoute.Chat(CHAT_ID_SAVED) }
                )

                is AppRoute.Settings -> SettingsScreen(
                    config = themeConfig,
                    nickname = nickname,
                    serverName = serverName,
                    lanIp = lanIp,
                    lanPort = lanPort,
                    fingerprintEmojis = fingerprint,
                    relayState = relayState,
                    onIdentityChange = { n, s -> updateIdentity(n, s) },
                    onConfigChange = onThemeConfigChange,
                    onBack = { route = AppRoute.List }
                )

                is AppRoute.Chat -> {
                    val chat = allChats.firstOrNull { it.id == currentRoute.chatId }
                    if (chat == null) route = AppRoute.List
                    else ChatScreen(
                        chat = chat,
                        messages = messagesByChat[chat.id] ?: emptyList(),
                        pinnedMessageId = pinnedMessages[chat.id],
                        aiStatus = aiStatus,
                        isAiGenerating = isAiGenerating,
                        voicePlayback = voicePlayback,
                        aiSystemPrompt = themeConfig.aiSystemPrompt,
                        onBack = { route = AppRoute.List },
                        onPickAttachmentMeta = { uri -> storage.readAttachmentMeta(uri) },
                        onStartVoiceRecord = { voiceManager.startRecording() },
                        onStopVoiceRecord = { discard ->
                            voiceManager.stopRecording(discard)?.let { (file, sec) -> AttachmentInfo(file.name, file.absolutePath, "audio/mp4", file.length(), isVoice = true, durationSec = sec) }
                        },
                        onToggleVoicePlay = { msgId, path -> voiceManager.togglePlay(msgId, path) },
                        onSeekVoice = { msgId, frac -> voiceManager.seekTo(msgId, frac) },
                        onPinMessage = { msgId -> if (msgId == null) pinnedMessages.remove(chat.id) else pinnedMessages[chat.id] = msgId; persistPins() },
                        onSendPoll = { poll ->
                            val msgId = UUID.randomUUID().toString()
                            upsertMessage(chat.id, ChatMessage(id = msgId, chatId = chat.id, senderName = if (chat.type == ChatType.CHANNEL_LAN) chat.title else nickname, text = "", isFromMe = true, poll = poll, isEncrypted = chat.type != ChatType.SAVED))
                            scope.launch {
                                when (chat.type) {
                                    ChatType.LOCAL_LAN -> {
                                        val pid = chat.id.removePrefix("lan_")
                                        val lanPeer = lanPeers.firstOrNull { it.id == pid }
                                        val sentLan = lanPeer != null && lanMessenger.sendPollMessage(lanPeer, msgId, poll)
                                        if (!sentLan && themeConfig.relayEnabled) relayClient.sendDirectPoll(pid, msgId, poll)
                                    }
                                    ChatType.GROUP_LAN -> {
                                        val gid = chat.id.removePrefix("grp_")
                                        lanPeers.forEach { lanMessenger.sendPollMessage(it, msgId, poll, groupId = gid, groupName = chat.title) }
                                        if (themeConfig.relayEnabled) relayClient.sendRoomPoll(chat.id, chat.title, false, msgId, poll)
                                    }
                                    ChatType.CHANNEL_LAN -> {
                                        val cid = chat.id.removePrefix("ch_")
                                        lanPeers.forEach { lanMessenger.sendPollMessage(it, msgId, poll, channelId = cid, channelName = chat.title) }
                                        if (themeConfig.relayEnabled) relayClient.sendRoomPoll(chat.id, chat.title, true, msgId, poll)
                                    }
                                    else -> Unit
                                }
                            }
                        },
                        onVotePoll = { msg, optIdx ->
                            val p = msg.poll ?: return@ChatScreen
                            val already = optIdx in p.myVotes
                            val changes = mutableListOf<Pair<Int, Int>>()
                            val nextVotes = p.votes.toMutableMap()
                            val nextMy = p.myVotes.toMutableSet()
                            if (already) {
                                nextMy.remove(optIdx)
                                val v = ((nextVotes[optIdx] ?: 0) - 1).coerceAtLeast(0)
                                if (v == 0) nextVotes.remove(optIdx) else nextVotes[optIdx] = v
                                changes.add(optIdx to -1)
                            } else {
                                if (!p.allowsMultiple && nextMy.isNotEmpty()) {
                                    nextMy.toList().forEach { prev ->
                                        nextMy.remove(prev)
                                        val v = ((nextVotes[prev] ?: 0) - 1).coerceAtLeast(0)
                                        if (v == 0) nextVotes.remove(prev) else nextVotes[prev] = v
                                        changes.add(prev to -1)
                                    }
                                }
                                nextMy.add(optIdx)
                                nextVotes[optIdx] = (nextVotes[optIdx] ?: 0) + 1
                                changes.add(optIdx to 1)
                            }
                            upsertMessage(chat.id, msg.copy(poll = p.copy(votes = nextVotes, myVotes = nextMy)), countUnread = false)
                            scope.launch {
                                changes.forEach { (idx, delta) ->
                                    when (chat.type) {
                                        ChatType.LOCAL_LAN -> {
                                            val pid = chat.id.removePrefix("lan_")
                                            val lanPeer = lanPeers.firstOrNull { it.id == pid }
                                            val ok = lanPeer != null && lanMessenger.sendPollVote(lanPeer, msg.id, idx, delta)
                                            if (!ok && themeConfig.relayEnabled) relayClient.sendDirectPollVote(pid, msg.id, idx, delta)
                                        }
                                        ChatType.GROUP_LAN -> {
                                            val gid = chat.id.removePrefix("grp_")
                                            lanPeers.forEach { lanMessenger.sendPollVote(it, msg.id, idx, delta, groupId = gid) }
                                            if (themeConfig.relayEnabled) relayClient.sendRoomPollVote(chat.id, false, msg.id, idx, delta)
                                        }
                                        ChatType.CHANNEL_LAN -> {
                                            val cid = chat.id.removePrefix("ch_")
                                            lanPeers.forEach { lanMessenger.sendPollVote(it, msg.id, idx, delta, channelId = cid) }
                                            if (themeConfig.relayEnabled) relayClient.sendRoomPollVote(chat.id, true, msg.id, idx, delta)
                                        }
                                        else -> Unit
                                    }
                                }
                            }
                        },
                        onToggleReaction = { msg, emoji ->
                            val already = emoji in msg.myReactions
                            val delta = if (already) -1 else 1
                            val nextMy = if (already) msg.myReactions - emoji else msg.myReactions + emoji
                            val nextMap = msg.reactions.toMutableMap()
                            val nextCnt = ((nextMap[emoji] ?: 0) + delta).coerceAtLeast(0)
                            if (nextCnt == 0) nextMap.remove(emoji) else nextMap[emoji] = nextCnt
                            upsertMessage(chat.id, msg.copy(reactions = nextMap, myReactions = nextMy), countUnread = false)
                            scope.launch {
                                when (chat.type) {
                                    ChatType.LOCAL_LAN -> {
                                        val pid = chat.id.removePrefix("lan_")
                                        val lanPeer = lanPeers.firstOrNull { it.id == pid }
                                        val ok = lanPeer != null && lanMessenger.sendReaction(lanPeer, msg.id, emoji, delta)
                                        if (!ok && themeConfig.relayEnabled) relayClient.sendDirectReaction(pid, msg.id, emoji, delta)
                                    }
                                    ChatType.GROUP_LAN -> {
                                        val gid = chat.id.removePrefix("grp_")
                                        lanPeers.forEach { lanMessenger.sendReaction(it, msg.id, emoji, delta, groupId = gid) }
                                        if (themeConfig.relayEnabled) relayClient.sendRoomReaction(chat.id, false, msg.id, emoji, delta)
                                    }
                                    ChatType.CHANNEL_LAN -> {
                                        val cid = chat.id.removePrefix("ch_")
                                        lanPeers.forEach { lanMessenger.sendReaction(it, msg.id, emoji, delta, channelId = cid) }
                                        if (themeConfig.relayEnabled) relayClient.sendRoomReaction(chat.id, true, msg.id, emoji, delta)
                                    }
                                    else -> Unit
                                }
                            }
                        },
                        onStartAudioCall = {
                            val peer = lanPeers.firstOrNull { "lan_${it.id}" == chat.id }
                            if (peer != null) lanMessenger.startCall(peer)
                            else Toast.makeText(context, "Звонки пока работают только в одной Wi-Fi сети", Toast.LENGTH_SHORT).show()
                        },
                        onSendMessage = { text, reply, attachment ->
                            when (chat.type) {
                                ChatType.AI -> sendToAi(text, reply, attachment)
                                ChatType.SAVED -> {
                                    val msgId = UUID.randomUUID().toString()
                                    upsertMessage(chat.id, ChatMessage(id = msgId, chatId = chat.id, senderName = nickname, text = text, isFromMe = true, replyTo = reply, attachment = attachment))
                                    if (attachment != null && attachment.localPath.isEmpty()) {
                                        scope.launch { updateTransferProgress(chat.id, msgId, null, null, storage.copyUriToLocal(attachment)) }
                                    }
                                }
                                ChatType.LOCAL_LAN -> {
                                    val pid = chat.id.removePrefix("lan_")
                                    val lanPeer = lanPeers.firstOrNull { it.id == pid }
                                    val msgId = UUID.randomUUID().toString()
                                    upsertMessage(chat.id, ChatMessage(id = msgId, chatId = chat.id, senderName = nickname, text = text, isFromMe = true, replyTo = reply, attachment = attachment, transferProgress = if (attachment != null && lanPeer != null) 0f else null, isEncrypted = true))
                                    scope.launch {
                                        if (attachment != null) {
                                            if (lanPeer != null) {
                                                val ok = lanMessenger.sendFileMessage(lanPeer, msgId, text, attachment) { p, mbps -> scope.launch { updateTransferProgress(chat.id, msgId, if (p >= 1f) null else p, mbps) } }
                                                updateTransferProgress(chat.id, msgId, null, null)
                                                if (!ok) Toast.makeText(context, "Ошибка передачи файла", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Файлы и голосовые пока передаются напрямую в одной Wi-Fi сети", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            val sentLan = lanPeer != null && lanMessenger.sendTextMessage(lanPeer, msgId, text)
                                            if (!sentLan) {
                                                if (themeConfig.relayEnabled) {
                                                    relayClient.sendDirectText(pid, msgId, text)
                                                } else {
                                                    Toast.makeText(context, "Собеседник не в локальной сети, а облако выключено", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                }
                                ChatType.GROUP_LAN -> {
                                    val groupId = chat.id.removePrefix("grp_")
                                    val msgId = UUID.randomUUID().toString()
                                    upsertMessage(chat.id, ChatMessage(id = msgId, chatId = chat.id, senderName = nickname, text = text, isFromMe = true, replyTo = reply, attachment = attachment, transferProgress = if (attachment != null && lanPeers.isNotEmpty()) 0f else null, isEncrypted = true))
                                    scope.launch {
                                        if (attachment != null) {
                                            lanPeers.forEach { peer ->
                                                lanMessenger.sendFileMessage(peer, msgId, text, attachment, groupId = groupId, groupName = chat.title) { p, mbps -> scope.launch { updateTransferProgress(chat.id, msgId, if (p >= 1f) null else p, mbps) } }
                                            }
                                            updateTransferProgress(chat.id, msgId, null, null)
                                        } else {
                                            lanPeers.forEach { peer -> lanMessenger.sendTextMessage(peer, msgId, text, groupId = groupId, groupName = chat.title) }
                                            if (themeConfig.relayEnabled) relayClient.sendRoomText(chat.id, chat.title, false, msgId, text)
                                        }
                                    }
                                }
                                ChatType.CHANNEL_LAN -> {
                                    val chId = chat.id.removePrefix("ch_")
                                    val msgId = UUID.randomUUID().toString()
                                    upsertMessage(chat.id, ChatMessage(id = msgId, chatId = chat.id, senderName = chat.title, text = text, isFromMe = true, replyTo = reply, attachment = attachment, transferProgress = if (attachment != null && lanPeers.isNotEmpty()) 0f else null, isEncrypted = true))
                                    scope.launch {
                                        if (attachment != null) {
                                            lanPeers.forEach { peer ->
                                                lanMessenger.sendFileMessage(peer, msgId, text, attachment, channelId = chId, channelName = chat.title) { p, mbps -> scope.launch { updateTransferProgress(chat.id, msgId, if (p >= 1f) null else p, mbps) } }
                                            }
                                            updateTransferProgress(chat.id, msgId, null, null)
                                        } else {
                                            lanPeers.forEach { peer -> lanMessenger.sendTextMessage(peer, msgId, text, channelId = chId, channelName = chat.title) }
                                            if (themeConfig.relayEnabled) relayClient.sendRoomText(chat.id, chat.title, true, msgId, text)
                                        }
                                    }
                                }
                            }
                        },
                        onStopAi = { aiJob?.cancel(); isAiGenerating = false },
                        onDeleteMessage = { msg ->
                            messagesByChat[chat.id] = (messagesByChat[chat.id] ?: emptyList()).filterNot { it.id == msg.id }
                            if (pinnedMessages[chat.id] == msg.id) { pinnedMessages.remove(chat.id); persistPins() }
                            persistAll()
                        },
                        onOpenVoltAi = { VoltAiClient.launchVoltAi(context) }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = callState.status != CallStatus.IDLE,
            enter = slideInVertically(spring(dampingRatio = 0.88f, stiffness = 400f)) { it } + fadeIn(tween(180)),
            exit = slideOutVertically(spring(dampingRatio = 0.9f, stiffness = 420f)) { it } + fadeOut(tween(160))
        ) {
            CallOverlay(
                state = callState,
                onAccept = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) lanMessenger.acceptIncomingCall()
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
