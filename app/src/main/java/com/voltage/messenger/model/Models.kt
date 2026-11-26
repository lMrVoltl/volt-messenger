package com.voltage.messenger.model

import androidx.compose.runtime.Immutable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

const val DEFAULT_AI_SYSTEM_PROMPT =
    "Ты — Volt AI, локальный ассистент Volt Messenger. Не называй себя ChatGPT. Отвечай кратко на русском."

const val DEFAULT_RELAY_URL = "wss://volt-relay.mrvolt.workers.dev/ws"

enum class ChatType {
    SAVED,
    AI,
    LOCAL_LAN,
    GROUP_LAN,
    CHANNEL_LAN
}

enum class ChatFolder(val title: String) {
    ALL("Все"),
    LOCAL("Личные"),
    GROUPS("Группы"),
    CHANNELS("Каналы"),
    AI("ИИ")
}

@Immutable
data class AppThemeConfig(
    val themeMode: Int = 0,
    val accentPreset: Int = 0,
    val bubbleRadius: Int = 20,
    val wallpaperStyle: Int = 1,
    val aiSystemPrompt: String = DEFAULT_AI_SYSTEM_PROMPT,
    val aiControlEnabled: Boolean = true,
    val relayEnabled: Boolean = true,
    val relayUrl: String = DEFAULT_RELAY_URL
)

@Immutable
data class ChannelMeta(
    val id: String,
    val title: String,
    val ownerId: String
)

@Immutable
data class PollData(
    val question: String,
    val options: List<String>,
    val votes: Map<Int, Int> = emptyMap(),
    val myVotes: Set<Int> = emptySet(),
    val isAnonymous: Boolean = true,
    val allowsMultiple: Boolean = false
) {
    val totalVotes: Int get() = votes.values.sum()
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
    val sizeBytes: Long,
    val uriString: String? = null,
    val isVoice: Boolean = false,
    val durationSec: Int = 0
) {
    val isAiModel: Boolean
        get() = name.endsWith(".gguf", ignoreCase = true)

    val isImage: Boolean
        get() = mimeType.startsWith("image/") ||
            name.endsWith(".jpg", true) ||
            name.endsWith(".jpeg", true) ||
            name.endsWith(".png", true) ||
            name.endsWith(".webp", true)

    fun formattedSize(): String = when {
        sizeBytes < 1024L -> "$sizeBytes Б"
        sizeBytes < 1024L * 1024L -> "${sizeBytes / 1024L} КБ"
        sizeBytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f МБ", sizeBytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f ГБ", sizeBytes / (1024.0 * 1024.0 * 1024.0))
    }

    fun formattedDuration(): String {
        val m = durationSec / 60
        val s = durationSec % 60
        return String.format(Locale.US, "%d:%02d", m, s)
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
    val attachment: AttachmentInfo? = null,
    val poll: PollData? = null,
    val transferProgress: Float? = null,
    val transferSpeedMbps: Float? = null,
    val isEncrypted: Boolean = false,
    val reactions: Map<String, Int> = emptyMap(),
    val myReactions: Set<String> = emptySet()
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
    val isCloudOnline: Boolean = false,
    val isPinned: Boolean = false,
    val unreadCount: Int = 0,
    val avatarColorIndex: Int = 0,
    val peerHost: String? = null,
    val peerPort: Int? = null,
    val serverName: String? = null,
    val isChannelOwner: Boolean = true
)

@Immutable
data class LanPeer(
    val id: String,
    val nickname: String,
    val serverName: String,
    val host: String,
    val port: Int,
    val lastSeenMs: Long = System.currentTimeMillis()
)

@Immutable
data class RelayPeer(
    val id: String,
    val nickname: String,
    val serverName: String
)

enum class CallStatus {
    IDLE,
    OUTGOING,
    INCOMING,
    CONNECTED
}

@Immutable
data class LanCallState(
    val status: CallStatus = CallStatus.IDLE,
    val peerId: String = "",
    val peerName: String = "",
    val isMuted: Boolean = false,
    val isSpeaker: Boolean = false,
    val connectedAtMs: Long = 0L,
    val sessionEmojis: String = ""
)
