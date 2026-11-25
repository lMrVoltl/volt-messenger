package com.voltage.messenger.core.storage

import android.content.Context
import android.os.Build
import com.voltage.messenger.model.ChatMessage
import com.voltage.messenger.model.ReplyPreview
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ChatStorage(context: Context) {
    private val prefs = context.getSharedPreferences("volt_messenger_prefs", Context.MODE_PRIVATE)
    private val messagesFile = File(context.filesDir, "messages_v1.json")

    fun getDeviceId(): String {
        val existing = prefs.getString("device_id", null)
        if (existing != null) return existing
        val created = UUID.randomUUID().toString().replace("-", "").take(10)
        prefs.edit().putString("device_id", created).apply()
        return created
    }

    fun getNickname(): String {
        return prefs.getString("nickname", null)
            ?: Build.MODEL.take(18).ifBlank { "Volt User" }
    }

    fun setNickname(name: String) {
        val clean = name.trim().take(24)
        if (clean.isNotEmpty()) {
            prefs.edit().putString("nickname", clean).apply()
        }
    }

    // 4 эмодзи-отпечатка для будущей сверки ключей E2EE как в Telegram
    fun getKeyFingerprintEmojis(seed: String = getDeviceId()): String {
        val pool = listOf("⚡", "🔒", "🔥", "🦊", "🌊", "🚀", "💎", "🛰️", "🌙", "🎯", "🛡️", "🎧", "❄️", "🍀", "🔑", "🪐")
        val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return (0 until 4).joinToString(" ") { idx ->
            val b = bytes[idx].toInt() and 0xFF
            pool[b % pool.size]
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
                    list.add(
                        ChatMessage(
                            id = o.optString("id"),
                            chatId = chatId,
                            senderName = o.optString("senderName"),
                            text = o.optString("text"),
                            isFromMe = o.optBoolean("isFromMe"),
                            timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                            isRead = o.optBoolean("isRead", true),
                            isMediaDemo = o.optBoolean("isMediaDemo", false),
                            replyTo = reply
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
                    arr.put(o)
                }
                root.put(chatId, arr)
            }
            messagesFile.writeText(root.toString())
        } catch (_: Exception) {
        }
    }
}
