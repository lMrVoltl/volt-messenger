package com.voltage.messenger.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voltage.messenger.core.ai.VoltAiStatus
import com.voltage.messenger.model.ChatFolder
import com.voltage.messenger.model.ChatMessage
import com.voltage.messenger.model.ChatRoom
import com.voltage.messenger.model.ChatType
import com.voltage.messenger.model.LanPeer
import com.voltage.messenger.ui.theme.AppSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    chats: List<ChatRoom>,
    messagesByChat: Map<String, List<ChatMessage>>,
    aiStatus: VoltAiStatus,
    isLanActive: Boolean,
    lanIp: String,
    lanPort: Int,
    lanPeers: List<LanPeer>,
    nickname: String,
    serverName: String,
    fingerprintEmojis: String,
    onIdentityChange: (newNick: String, newServerName: String) -> Unit,
    onAddManualPeer: (ip: String, port: Int) -> Unit,
    onCreateGroup: (groupName: String) -> Unit,
    onOpenSettings: () -> Unit,
    onChatClick: (ChatRoom) -> Unit,
    onOpenAiClick: () -> Unit,
    onOpenSavedClick: () -> Unit
) {
    var selectedFolder by remember { mutableStateOf(ChatFolder.ALL) }
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showProfileSheet by remember { mutableStateOf(false) }
    var showNewChatSheet by remember { mutableStateOf(false) }
    var showManualIpDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }

    val filteredChats = remember(chats, selectedFolder, searchQuery, messagesByChat) {
        chats.filter { chat ->
            val folderOk = when (selectedFolder) {
                ChatFolder.ALL -> true
                ChatFolder.LOCAL -> chat.type == ChatType.LOCAL_LAN || chat.type == ChatType.SAVED
                ChatFolder.GROUPS -> chat.type == ChatType.GROUP_LAN
                ChatFolder.AI -> chat.type == ChatType.AI
            }
            val queryOk = searchQuery.isBlank() ||
                chat.title.contains(searchQuery, ignoreCase = true) ||
                (chat.serverName?.contains(searchQuery, ignoreCase = true) == true) ||
                (messagesByChat[chat.id]?.lastOrNull()?.text?.contains(searchQuery, ignoreCase = true) == true)
            folderOk && queryOk
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showProfileSheet = true }) {
                        Icon(Icons.Rounded.Menu, contentDescription = "Профиль и узел")
                    }

                    AnimatedContent(
                        targetState = isSearching,
                        transitionSpec = {
                            (fadeIn(tween(180)) + scaleIn(initialScale = 0.96f, animationSpec = spring(stiffness = 500f))) togetherWith
                                (fadeOut(tween(120)) + scaleOut(targetScale = 0.96f, animationSpec = tween(120)))
                        },
                        modifier = Modifier.weight(1f),
                        label = "searchBar"
                    ) { searching ->
                        if (searching) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Поиск чатов и групп...",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else {
                            Column(modifier = Modifier.padding(start = 6.dp)) {
                                Text("Volt", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(if (isLanActive) Color(0xFF22C55E) else Color(0xFFF59E0B))
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = if (isLanActive) "«$serverName» • рядом: ${lanPeers.size}" else "Запуск узла...",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    if (!isSearching) {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Настройки и обновления")
                        }
                    }

                    IconButton(
                        onClick = {
                            if (isSearching) {
                                searchQuery = ""
                                isSearching = false
                            } else {
                                isSearching = true
                            }
                        }
                    ) {
                        Icon(if (isSearching) Icons.Rounded.Close else Icons.Rounded.Search, contentDescription = "Поиск")
                    }
                }

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ChatFolder.entries) { folder ->
                        FilterChip(
                            selected = selectedFolder == folder,
                            onClick = { selectedFolder = folder },
                            label = { Text(folder.title) },
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNewChatSheet = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.navigationBarsPadding()
            ) {
                Icon(Icons.Rounded.Edit, contentDescription = "Новый чат")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(top = 4.dp, bottom = 92.dp)
        ) {
            items(filteredChats, key = { it.id }) { chat ->
                val lastMsg = messagesByChat[chat.id]?.lastOrNull()
                Box(
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(200),
                        placementSpec = spring(dampingRatio = 0.88f, stiffness = 420f)
                    )
                ) {
                    ChatListItemRow(
                        chat = chat,
                        lastMessage = lastMsg,
                        aiStatus = aiStatus,
                        onClick = { onChatClick(chat) }
                    )
                }
            }
        }
    }

    if (showNewChatSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNewChatSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 18.dp)
            ) {
                Text(
                    text = "Новое сообщение",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )

                ListItem(
                    headlineContent = { Text("Создать локальную группу") },
                    supportingContent = { Text("Общий зашифрованный чат для всех рядом") },
                    leadingContent = { Icon(Icons.Rounded.GroupAdd, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable {
                        showNewChatSheet = false
                        showCreateGroupDialog = true
                    }
                )

                ListItem(
                    headlineContent = { Text("Подключиться по IP вручную") },
                    supportingContent = { Text("Если точка доступа блокирует автопоиск") },
                    leadingContent = { Icon(Icons.Rounded.AddLink, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable {
                        showNewChatSheet = false
                        showManualIpDialog = true
                    }
                )

                ListItem(
                    headlineContent = { Text("Написать Volt AI") },
                    supportingContent = { Text("Локальная нейросеть + анализ картинок") },
                    leadingContent = { Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable {
                        showNewChatSheet = false
                        onOpenAiClick()
                    }
                )

                ListItem(
                    headlineContent = { Text("Избранное") },
                    supportingContent = { Text("Личные заметки и файлы") },
                    leadingContent = { Icon(Icons.Rounded.Bookmark, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable {
                        showNewChatSheet = false
                        onOpenSavedClick()
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp))

                Text(
                    text = "Найдено рядом (${lanPeers.size}) • твой адрес: $lanIp:$lanPort",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )

                if (lanPeers.isEmpty()) {
                    Text(
                        text = "Включи Wi-Fi или точку доступа и открой Volt Messenger на втором телефоне.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                } else {
                    lanPeers.forEach { peer ->
                        ListItem(
                            headlineContent = { Text(peer.nickname, fontWeight = FontWeight.Medium) },
                            supportingContent = { Text("Сервер: ${peer.serverName} • ${peer.host}:${peer.port}") },
                            leadingContent = { Icon(Icons.Rounded.Wifi, null, tint = Color(0xFF22C55E)) },
                            modifier = Modifier.clickable {
                                showNewChatSheet = false
                                chats.firstOrNull { it.id == "lan_${peer.id}" }?.let(onChatClick)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showCreateGroupDialog) {
        var groupName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateGroupDialog = false },
            icon = { Icon(Icons.Rounded.GroupAdd, null) },
            title = { Text("Новая локальная группа") },
            text = {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("Название группы") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (groupName.isNotBlank()) {
                            onCreateGroup(groupName.trim())
                            showCreateGroupDialog = false
                        }
                    }
                ) { Text("Создать") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateGroupDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (showManualIpDialog) {
        var ipText by remember { mutableStateOf("192.168.43.1") }
        var portText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManualIpDialog = false },
            title = { Text("Подключиться по IP") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Посмотри IP и порт в боковом меню на втором телефоне.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = ipText, onValueChange = { ipText = it }, label = { Text("IP-адрес") }, singleLine = true)
                    OutlinedTextField(value = portText, onValueChange = { portText = it.filter { c -> c.isDigit() } }, label = { Text("Порт") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val p = portText.toIntOrNull() ?: 0
                    if (ipText.isNotBlank() && p in 1..65535) {
                        onAddManualPeer(ipText.trim(), p)
                        showManualIpDialog = false
                    }
                }) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { showManualIpDialog = false }) { Text("Отмена") } }
        )
    }

    if (showProfileSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var draftNick by remember(nickname) { mutableStateOf(nickname) }
        var draftServer by remember(serverName) { mutableStateOf(serverName) }

        ModalBottomSheet(
            onDismissRequest = { showProfileSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text("Профиль и локальный сервер", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isLanActive) "Твой адрес: $lanIp:$lanPort (E2EE AES-256-GCM)" else "Сервер выключен",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = draftNick,
                    onValueChange = { draftNick = it },
                    label = { Text("Твой ник (виден в чатах)") },
                    leadingIcon = { Icon(Icons.Rounded.Person, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = draftServer,
                    onValueChange = { draftServer = it },
                    label = { Text("Название локального сервера") },
                    leadingIcon = { Icon(Icons.Rounded.Dns, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                AppSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Отпечаток ключа устройства", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(fingerprintEmojis, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = {
                        onIdentityChange(draftNick.trim().ifEmpty { nickname }, draftServer.trim().ifEmpty { serverName })
                        showProfileSheet = false
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Сохранить и обновить узел") }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ChatListItemRow(
    chat: ChatRoom,
    lastMessage: ChatMessage?,
    aiStatus: VoltAiStatus,
    onClick: () -> Unit
) {
    val avatarColors = listOf(
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer,
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    )
    val (bg, fg) = avatarColors[chat.avatarColorIndex % avatarColors.size]

    val isOnlineNow = when (chat.type) {
        ChatType.AI -> aiStatus == VoltAiStatus.READY
        ChatType.LOCAL_LAN, ChatType.GROUP_LAN -> chat.isOnline
        ChatType.SAVED -> false
    }

    val previewText = when {
        lastMessage != null -> {
            val base = when {
                lastMessage.attachment?.isVoice == true -> "🎤 Голосовое (${lastMessage.attachment.formattedDuration()})"
                lastMessage.attachment?.isAiModel == true -> "🧠 Модель ИИ: ${lastMessage.attachment.name}"
                lastMessage.attachment != null && lastMessage.text.isBlank() -> "📎 ${lastMessage.attachment.name}"
                lastMessage.attachment != null -> "📎 ${lastMessage.text}"
                else -> lastMessage.text
            }
            when {
                lastMessage.isFromMe -> "Вы: $base"
                chat.type == ChatType.GROUP_LAN -> "${lastMessage.senderName}: $base"
                else -> base
            }
        }
        chat.type == ChatType.LOCAL_LAN -> "Сервер: ${chat.serverName ?: "Volt-Node"} • ${if (chat.isOnline) "рядом" else "офлайн"}"
        else -> chat.subtitle
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(54.dp)) {
                Box(
                    modifier = Modifier.fillMaxSize().clip(CircleShape).background(bg),
                    contentAlignment = Alignment.Center
                ) {
                    when (chat.type) {
                        ChatType.AI -> Icon(Icons.Rounded.AutoAwesome, null, tint = fg, modifier = Modifier.size(26.dp))
                        ChatType.SAVED -> Icon(Icons.Rounded.Bookmark, null, tint = fg, modifier = Modifier.size(26.dp))
                        ChatType.GROUP_LAN -> Icon(Icons.Rounded.Group, null, tint = fg, modifier = Modifier.size(26.dp))
                        ChatType.LOCAL_LAN -> Text(chat.title.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = fg)
                    }
                }

                if (isOnlineNow) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF22C55E))
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = chat.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (lastMessage != null) {
                        if (lastMessage.isFromMe) {
                            Icon(Icons.Rounded.DoneAll, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 4.dp).size(16.dp))
                        }
                        Text(lastMessage.timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = previewText.replace("\n", " "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    if (chat.unreadCount > 0) {
                        Box(
                            modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 7.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(chat.unreadCount.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        }
                    } else if (chat.isPinned) {
                        Icon(Icons.Rounded.PushPin, "Закреплён", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(start = 84.dp, end = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    }
}
