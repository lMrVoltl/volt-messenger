package com.voltage.messenger.core.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.voltage.messenger.core.crypto.VoltCrypto
import com.voltage.messenger.model.AttachmentInfo
import com.voltage.messenger.model.ChatMessage
import com.voltage.messenger.model.ReplyPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ChatStorage(private val context: Context) {
    private val prefs = context.getSharedPreferences("volt_messenger_prefs", Context.MODE_PRIVATE)
    private val messagesFile = File(context.filesDir, "messages_v3.json")
    private val peersFile = File(context.filesDir, "known_peers_v1.json")
    val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }

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
        if (clean.isNotEmpty()) prefs.edit().putString("nickname", clean).apply()
    }

    fun getServerName(): String {
        val existing = prefs.getString("server_name", null)
        if (!existing.isNullOrBlank()) return existing
        val modelPart = Build.MODEL.replace(Regex("[^a-zA-Z0-9]"), "").take(8).ifEmpty { "Node" }
        return "Volt-$modelPart"
    }

    fun setServerName(name: String) {
        val clean = name.trim().take(24)
        if (clean.isNotEmpty()) prefs.edit().putString("server_name", clean).apply()
    }

    fun getKeyFingerprintEmojis(seed: String = getDeviceId()): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return VoltCrypto.emojisFromHash(bytes)
    }

    // Мгновенное чтение метаданных файла без копирования (не тормозит UI даже на файлах 4+ ГБ)
    fun readAttachmentMeta(uri: Uri): AttachmentInfo? {
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
                    if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
                }
            }
            val mime = cr.getType(uri) ?: "application/octet-stream"
            AttachmentInfo(
                name = displayName,
                localPath = "",
                mimeType = mime,
                sizeBytes = sizeBytes,
                uriString = uri.toString()
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun copyUriToLocal(info: AttachmentInfo): AttachmentInfo = withContext(Dispatchers.IO) {
        if (info.localPath.isNotEmpty() && File(info.localPath).exists()) return@withContext info
        val uriStr = info.uriString ?: return@withContext info
        val uri = Uri.parse(uriStr)
        val safeName = "${System.currentTimeMillis()}_${info.name.replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ._-]"), "_")}"
        val outFile = File(attachmentsDir, safeName)
        context.contentResolver.openInputStream(uri)?.use { inp ->
            outFile.outputStream().use { out ->
                inp.copyTo(out, 256 * 1024)
            }
        }
        info.copy(localPath = outFile.absolutePath, sizeBytes = outFile.length())
    }

    fun createIncomingFile(fileName: String): File {
        val safe = "${System.currentTimeMillis()}_${fileName.replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ._-]"), "_")}"
        return File(attachmentsDir, safe)
    }

    // Копируем принятый файл в публичные Загрузки/VoltMessenger (Android 10+)
    fun exportToPublicDownloads(sourceFile: File, displayName: String, mimeType: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VoltMessenger")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { inp -> inp.copyTo(out, 256 * 1024) }
            }
        } catch (_: Exception) {
        }
    }

    fun saveKnownPeerName(peerId: String, nickname: String, serverName: String) {
        try {
            val root = if (peersFile.exists()) JSONObject(peersFile.readText()) else JSONObject()
            root.put(peerId, JSONObject().put("nick", nickname).put("srv", serverName))
            peersFile.writeText(root.toString())
        } catch (_: Exception) {
        }
    }

    fun loadKnownPeers(): Map<String, Pair<String, String>> {
        val result = mutableMapOf<String, Pair<String, String>>()
        if (!peersFile.exists()) return result
        try {
            val root = JSONObject(peersFile.readText())
            val keys = root.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val o = root.optJSONObject(id) ?: continue
                result[id] = o.optString("nick", id) to o.optString("srv", "Volt-Node")
            }
        } catch (_: Exception) {
        }
        return result
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
                        ReplyPreview(replyObj.optString("senderName"), replyObj.optString("text"))
                    } else null

                    val attObj = o.optJSONObject("attachment")
                    val att = if (attObj != null) {
                        AttachmentInfo(
                            name = attObj.optString("name"),
                            localPath = attObj.optString("localPath"),
                            mimeType = attObj.optString("mimeType", "application/octet-stream"),
                            sizeBytes = attObj.optLong("sizeBytes", 0L),
                            isVoice = attObj.optBoolean("isVoice", false),
                            durationSec = attObj.optInt("durationSec", 0)
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
                            attachment = att,
                            isEncrypted = o.optBoolean("isEncrypted", false)
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
                        .put("isEncrypted", msg.isEncrypted)
                    if (msg.replyTo != null) {
                        o.put("replyTo", JSONObject().put("senderName", msg.replyTo.senderName).put("text", msg.replyTo.text))
                    }
                    if (msg.attachment != null) {
                        o.put(
                            "attachment",
                            JSONObject()
                                .put("name", msg.attachment.name)
                                .put("localPath", msg.attachment.localPath)
                                .put("mimeType", msg.attachment.mimeType)
                                .put("sizeBytes", msg.attachment.sizeBytes)
                                .put("isVoice", msg.attachment.isVoice)
                                .put("durationSec", msg.attachment.durationSec)
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
