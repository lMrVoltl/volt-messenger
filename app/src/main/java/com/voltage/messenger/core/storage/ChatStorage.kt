package com.voltage.messenger.core.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.voltage.messenger.model.AttachmentInfo
import com.voltage.messenger.model.ChatMessage
import com.voltage.messenger.model.ReplyPreview
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ChatStorage(private val context: Context) {
    private val prefs = context.getSharedPreferences("volt_messenger_prefs", Context.MODE_PRIVATE)
    private val messagesFile = File(context.filesDir, "messages_v2.json")
    private val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }

    fun getDeviceId(): String {
        val existing = prefs.getString("device_id", null)
        if (existing != null) return existing
        val created = UUID.randomUUID().toString().replace("-", "").take(10)
        prefs.edit().putString("device_id", created).apply()
        return created
    }

    fun getNickname(): String {
        return prefs.getString("nickname", null)
            ?: Build.MODEL.take(16).ifBlank { "Volt User" }
    }

    fun setNickname(name: String) {
        val clean = name.trim().take(24)
        if (clean.isNotEmpty()) {
            prefs.edit().putString("nickname", clean).apply()
        }
    }

    fun getServerName(): String {
        val existing = prefs.getString("server_name", null)
        if (!existing.isNullOrBlank()) return existing
        val modelPart = Build.MODEL.replace(Regex("[^a-zA-Z0-9]"), "").take(8).ifEmpty { "Node" }
        return "Volt-$modelPart"
    }

    fun setServerName(name: String) {
        val clean = name.trim().take(24)
        if (clean.isNotEmpty()) {
            prefs.edit().putString("server_name", clean).apply()
        }
    }

    fun getKeyFingerprintEmojis(seed: String = getDeviceId()): String {
        val pool = listOf("⚡", "🔒", "🔥", "🦊", "🌊", "🚀", "💎", "🛰️", "🌙", "🎯", "🛡️", "🎧", "❄️", "🍀", "🔑", "🪐")
        val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return (0 until 4).joinToString(" ") { idx ->
            val b = bytes[idx].toInt() and 0xFF
            pool[b % pool.size]
        }
    }

    fun copyUriToAttachment(uri: Uri): AttachmentInfo? {
        return try {
            val cr = context.contentResolver
            var displayName = "file_${System.currentTimeMillis()}"
            var sizeBytes = 0L

            cr.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                    }
                    if (sizeIdx >= 0) {
                        sizeBytes = cursor.getLong(sizeIdx)
                    }
                }
            }

            val mime = cr.getType(uri) ?: "application/octet-stream"
            val safeFileName = "${System.currentTimeMillis()}_${displayName.replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ._-]"), "_")}"
            val outFile = File(attachmentsDir, safeFileName)

            cr.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            val actualSize = if (sizeBytes > 0L) sizeBytes else outFile.length()
            AttachmentInfo(
                name = displayName,
                localPath = outFile.absolutePath,
                mimeType = mime,
                sizeBytes = actualSize
            )
        } catch (_: Exception) {
            null
        }
    }

    fun loadMessages(): MutableMap<String, MutableList<ChatMessage>> {
        val result = mutableMapOf<String, MutableList<ChatMessage>>()
        if (!messagesFile.exists()) return result
        try {
            val root = JSONObject(messagesFile.readText())
            val keys = root.keys()
            while (keys.hasNext()) {
                val chatId = keys.next()
                val arr = root.optJSONArray(chatId) ?: continue
                val list = mutableListOf<ChatMessage>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val replyObj = o.optJSONObject("replyTo")
                    val reply = if (replyObj != null) {
                        ReplyPreview(
                            senderName = replyObj.optString("senderName"),
                            text = replyObj.optString("text")
                        )
                    } else null

                    val attObj = o.optJSONObject("attachment")
                    val att = if (attObj != null) {
                        AttachmentInfo(
                            name = attObj.optString("name"),
                            localPath = attObj.optString("localPath"),
                            mimeType = attObj.optString("mimeType", "application/octet-stream"),
                            sizeBytes = attObj.optLong("sizeBytes", 0L)
                        )
                    } else null

                    val ts = o.optLong("timestamp", System.currentTimeMillis())
                    list.add(
                        ChatMessage(
                            id = o.optString("id"),
                            chatId = chatId,
                            senderName = o.optString("senderName"),
                            text = o.optString("text"),
                            isFromMe = o.optBoolean("isFromMe"),
                            timestamp = ts,
                            timeLabel = ChatMessage.formatTime(ts),
                            isRead = o.optBoolean("isRead", true),
                            isMediaDemo = o.optBoolean("isMediaDemo", false),
                            replyTo = reply,
                            attachment = att
                        )
                    )
                }
                result[chatId] = list
            }
        } catch (_: Exception) {
        }
        return result
    }

    fun saveMessages(map: Map<String, List<ChatMessage>>) {
        try {
            val root = JSONObject()
            map.forEach { (chatId, list) ->
                val arr = JSONArray()
                list.takeLast(150).forEach { msg ->
                    val o = JSONObject()
                        .put("id", msg.id)
                        .put("senderName", msg.senderName)
                        .put("text", msg.text)
                        .put("isFromMe", msg.isFromMe)
                        .put("timestamp", msg.timestamp)
                        .put("isRead", msg.isRead)
                        .put("isMediaDemo", msg.isMediaDemo)
                    if (msg.replyTo != null) {
                        o.put(
                            "replyTo",
                            JSONObject()
                                .put("senderName", msg.replyTo.senderName)
                                .put("text", msg.replyTo.text)
                        )
                    }
                    if (msg.attachment != null) {
                        o.put(
                            "attachment",
                            JSONObject()
                                .put("name", msg.attachment.name)
                                .put("localPath", msg.attachment.localPath)
                                .put("mimeType", msg.attachment.mimeType)
                                .put("sizeBytes", msg.attachment.sizeBytes)
                        )
                    }
                    arr.put(o)
                }
                root.put(chatId, arr)
            }
            messagesFile.writeText(root.toString())
        } catch (_: Exception) {
        }
    }
}
