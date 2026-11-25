package com.voltage.messenger.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ChatType {
    SAVED,
    AI,
    LOCAL_LAN
}

enum class ChatFolder(val title: String) {
    ALL("Все"),
    LOCAL("Локальные"),
    AI("ИИ")
}

data class ReplyPreview(
    val senderName: String,
    val text: String
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val chatId: String,
    val senderName: String,
    val text: String,
    val isFromMe: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = true,
    val isMediaDemo: Boolean = false,
    val replyTo: ReplyPreview? = null
) {
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

data class ChatRoom(
    val id: String,
    val title: String,
    val subtitle: String,
    val type: ChatType,
    val isOnline: Boolean = false,
    val isPinned: Boolean = false,
    val unreadCount: Int = 0,
    val avatarColorIndex: Int = 0,
    val peerHost: String? = null,
    val peerPort: Int? = null
)

data class LanPeer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int
)
