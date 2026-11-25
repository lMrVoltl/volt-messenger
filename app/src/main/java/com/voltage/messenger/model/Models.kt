package com.voltage.messenger.model

import androidx.compose.runtime.Immutable
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

@Immutable
data class ReplyPreview(
    val senderName: String,
    val text: String
)

@Immutable
data class AttachmentInfo(
    val name: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long
) {
    fun formattedSize(): String = when {
        sizeBytes < 1024L -> "$sizeBytes Б"
        sizeBytes < 1024L * 1024L -> "${sizeBytes / 1024L} КБ"
        else -> String.format(Locale.US, "%.1f МБ", sizeBytes / (1024.0 * 1024.0))
    }
}

@Immutable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val chatId: String,
    val senderName: String,
    val text: String,
    val isFromMe: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val timeLabel: String = formatTime(timestamp),
    val isRead: Boolean = true,
    val isMediaDemo: Boolean = false,
    val replyTo: ReplyPreview? = null,
    val attachment: AttachmentInfo? = null
) {
    companion object {
        private val timeFormat = ThreadLocal.withInitial {
            SimpleDateFormat("HH:mm", Locale.getDefault())
        }

        fun formatTime(ts: Long): String {
            return timeFormat.get()?.format(Date(ts)) ?: ""
        }
    }
}

@Immutable
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
    val peerPort: Int? = null,
    val serverName: String? = null
)

@Immutable
data class LanPeer(
    val id: String,
    val nickname: String,
    val serverName: String,
    val host: String,
    val port: Int
)
