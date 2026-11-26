package com.voltage.messenger.core.network

import android.util.Base64
import com.voltage.messenger.core.storage.ChatStorage
import com.voltage.messenger.model.ChannelMeta
import com.voltage.messenger.model.PollData
import com.voltage.messenger.model.RelayPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class RelayConnectionState(val title: String) {
    DISABLED("Выключен • только локальный P2P"),
    CONNECTING("Подключение к Cloudflare Relay..."),
    CONNECTED("Подключён к облаку (E2EE)"),
    ERROR("Нет связи с облаком • повтор...")
}

class RelayClient(
    private val storage: ChatStorage,
    private val myShortId: String,
    private var myNickname: String,
    private var myServerName: String,
    private val onEvent: (IncomingLanEvent) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(RelayConnectionState.DISABLED)
    val state: StateFlow<RelayConnectionState> = _state.asStateFlow()

    private val _onlinePeers = MutableStateFlow<List<RelayPeer>>(emptyList())
    val onlinePeers: StateFlow<List<RelayPeer>> = _onlinePeers.asStateFlow()

    private var enabled = false
    private var baseUrl = ""
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private val outbox = ConcurrentLinkedQueue<String>()

    fun configure(isEnabled: Boolean, url: String, nick: String = myNickname, srv: String = myServerName) {
        val cleanUrl = url.trim().replace("https://", "wss://").replace("http://", "ws://")
        val changed = (isEnabled != enabled) || (cleanUrl != baseUrl) || (nick != myNickname) || (srv != myServerName)
        enabled = isEnabled
        baseUrl = cleanUrl
        myNickname = nick
        myServerName = srv

        if (!enabled || baseUrl.isEmpty()) {
            disconnect()
            _state.value = RelayConnectionState.DISABLED
            _onlinePeers.value = emptyList()
            return
        }
        if (changed || webSocket == null) {
            disconnect()
            connectNow()
        }
    }

    private fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        try { webSocket?.close(1000, "reconfigure") } catch (_: Exception) {}
        webSocket = null
    }

    private fun connectNow() {
        if (!enabled || baseUrl.isEmpty()) return
        _state.value = RelayConnectionState.CONNECTING

        val fullUrl = buildString {
            append(baseUrl)
            append(if (baseUrl.contains("?")) "&" else "?")
            append("id=").append(URLEncoder.encode(myShortId, "UTF-8"))
            append("&nick=").append(URLEncoder.encode(myNickname, "UTF-8"))
            append("&srv=").append(URLEncoder.encode(myServerName, "UTF-8"))
        }

        val req = Request.Builder().url(fullUrl).build()
        webSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                _state.value = RelayConnectionState.CONNECTED
                // Подтягиваем пропущенные сообщения комнат
                val syncReq = JSONObject()
                    .put("type", "sync_rooms")
                    .put("since", storage.getLastRoomSyncMs())
                ws.send(syncReq.toString())
                ws.send(JSONObject().put("type", "who").toString())

                // Отправляем накопленную очередь
                while (true) {
                    val queued = outbox.poll() ?: break
                    if (!ws.send(queued)) {
                        outbox.offer(queued)
                        break
                    }
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerPacket(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (webSocket == ws) webSocket = null
                _onlinePeers.value = emptyList()
                if (enabled) {
                    _state.value = RelayConnectionState.ERROR
                    scheduleReconnect()
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (webSocket == ws) webSocket = null
                _onlinePeers.value = emptyList()
                if (enabled && code != 1000) {
                    _state.value = RelayConnectionState.ERROR
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(4000L)
            if (isActive && enabled && webSocket == null) {
                connectNow()
            }
        }
    }

    private fun sendRaw(packet: JSONObject): Boolean {
        val str = packet.toString()
        val ws = webSocket
        if (ws != null && _state.value == RelayConnectionState.CONNECTED) {
            if (ws.send(str)) return true
        }
        if (enabled) {
            if (outbox.size < 100) outbox.offer(str)
        }
        return false
    }

    // ==================== E2EE AES-256-GCM ДЛЯ ОБЛАЧНЫХ ПАКЕТОВ ====================

    private fun directSeed(otherId: String): String {
        val a = minOf(myShortId, otherId)
        val b = maxOf(myShortId, otherId)
        return "volt_e2ee_dm_${a}_${b}_v1"
    }

    private fun roomSeed(roomId: String): String = "volt_e2ee_room_${roomId}_v1"

    private fun encrypt(seed: String, plain: String): String {
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ct, 0, combined, iv.size, ct.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(seed: String, b64: String): String? {
        return try {
            val combined = Base64.decode(b64, Base64.NO_WRAP)
            if (combined.size <= 16) return null
            val iv = combined.copyOfRange(0, 12)
            val ct = combined.copyOfRange(12, combined.size)
            val keyBytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    // ==================== ПУБЛИЧНЫЕ МЕТОДЫ ОТПРАВКИ ====================

    fun sendDirectText(targetId: String, msgId: String, text: String): Boolean {
        val inner = JSONObject().put("type", "text").put("text", text).toString()
        val packet = JSONObject()
            .put("type", "direct")
            .put("to", targetId)
            .put("msgId", msgId)
            .put("ts", System.currentTimeMillis())
            .put("payload", encrypt(directSeed(targetId), inner))
        return sendRaw(packet)
    }

    fun sendDirectPoll(targetId: String, msgId: String, poll: PollData): Boolean {
        val inner = JSONObject()
            .put("type", "poll")
            .put("question", poll.question)
            .put("options", JSONArray().apply { poll.options.forEach { put(it) } })
            .put("isAnonymous", poll.isAnonymous)
            .put("allowsMultiple", poll.allowsMultiple)
            .toString()
        val packet = JSONObject()
            .put("type", "direct")
            .put("to", targetId)
            .put("msgId", msgId)
            .put("ts", System.currentTimeMillis())
            .put("payload", encrypt(directSeed(targetId), inner))
        return sendRaw(packet)
    }

    fun sendDirectReaction(targetId: String, msgId: String, emoji: String, delta: Int): Boolean {
        val inner = JSONObject().put("type", "reaction").put("emoji", emoji).put("delta", delta).toString()
        val packet = JSONObject()
            .put("type", "direct")
            .put("to", targetId)
            .put("msgId", "r_${msgId}_${System.nanoTime()}")
            .put("ts", System.currentTimeMillis())
            .put("payload", encrypt(directSeed(targetId), JSONObject(inner).put("targetMsgId", msgId).toString()))
        return sendRaw(packet)
    }

    fun sendDirectPollVote(targetId: String, msgId: String, optionIndex: Int, delta: Int): Boolean {
        val inner = JSONObject()
            .put("type", "poll_vote")
            .put("targetMsgId", msgId)
            .put("optionIndex", optionIndex)
            .put("delta", delta)
            .toString()
        val packet = JSONObject()
            .put("type", "direct")
            .put("to", targetId)
            .put("msgId", "v_${msgId}_${System.nanoTime()}")
            .put("ts", System.currentTimeMillis())
            .put("payload", encrypt(directSeed(targetId), inner))
        return sendRaw(packet)
    }

    fun sendRoomText(roomId: String, roomName: String, isChannel: Boolean, msgId: String, text: String): Boolean {
        val inner = JSONObject()
            .put("type", "text")
            .put("text", text)
            .put("roomName", roomName)
            .put("isChannel", isChannel)
            .put("ownerId", myShortId)
            .toString()
        val packet = JSONObject()
            .put("type", "room")
            .put("roomId", roomId)
            .put("msgId", msgId)
            .put("ts", System.currentTimeMillis())
            .put("payload", encrypt(roomSeed(roomId), inner))
        return sendRaw(packet)
    }

    fun sendRoomPoll(roomId: String, roomName: String, isChannel: Boolean, msgId: String, poll: PollData): Boolean {
        val inner = JSONObject()
            .put("type", "poll")
            .put("question", poll.question)
            .put("options", JSONArray().apply { poll.options.forEach { put(it) } })
            .put("isAnonymous", poll.isAnonymous)
            .put("allowsMultiple", poll.allowsMultiple)
            .put("roomName", roomName)
            .put("isChannel", isChannel)
            .put("ownerId", myShortId)
            .toString()
        val packet = JSONObject()
            .put("type", "room")
            .put("roomId", roomId)
            .put("msgId", msgId)
            .put("ts", System.currentTimeMillis())
            .put("payload", encrypt(roomSeed(roomId), inner))
        return sendRaw(packet)
    }

    fun sendRoomReaction(roomId: String, isChannel: Boolean, msgId: String, emoji: String, delta: Int): Boolean {
        val inner = JSONObject()
            .put("type", "reaction")
            .put("targetMsgId", msgId)
            .put("emoji", emoji)
            .put("delta", delta)
            .put("isChannel", isChannel)
            .toString()
        val packet = JSONObject()
            .put("type", "room")
            .put("roomId", roomId)
            .put("msgId", "r_${msgId}_${System.nanoTime()}")
            .put("ts", System.currentTimeMillis())
            .put("payload", encrypt(roomSeed(roomId), inner))
        return sendRaw(packet)
    }

    fun sendRoomPollVote(roomId: String, isChannel: Boolean, msgId: String, optionIndex: Int, delta: Int): Boolean {
        val inner = JSONObject()
            .put("type", "poll_vote")
            .put("targetMsgId", msgId)
            .put("optionIndex", optionIndex)
            .put("delta", delta)
            .put("isChannel", isChannel)
            .toString()
        val packet = JSONObject()
            .put("type", "room")
            .put("roomId", roomId)
            .put("msgId", "v_${msgId}_${System.nanoTime()}")
            .put("ts", System.currentTimeMillis())
            .put("payload", encrypt(roomSeed(roomId), inner))
        return sendRaw(packet)
    }

    // ==================== ПРИЁМ ПАКЕТОВ ОТ CLOUDFLARE ====================

    private fun handleServerPacket(raw: String) {
        try {
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "peers" -> {
                    val arr = json.optJSONArray("peers") ?: return
                    val list = mutableListOf<RelayPeer>()
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        val id = p.optString("id")
                        if (id.isEmpty() || id == myShortId) continue
                        val nick = p.optString("nick", "Volt User")
                        val srv = p.optString("srv", "Cloud")
                        list.add(RelayPeer(id, nick, srv))
                        storage.saveKnownPeerName(id, nick, srv)
                    }
                    _onlinePeers.value = list.sortedBy { it.nickname.lowercase() }
                }

                "event" -> {
                    val from = json.optString("from")
                    if (from.isEmpty() || from == myShortId) return
                    val nick = json.optString("nick", "Собеседник")
                    val srv = json.optString("srv", "Cloud")
                    val msgId = json.optString("msgId")
                    val ts = json.optLong("ts", System.currentTimeMillis())
                    val roomId = json.optString("roomId").ifBlank { null }
                    val encPayload = json.optString("payload")

                    val seed = if (roomId != null) roomSeed(roomId) else directSeed(from)
                    val plain = decrypt(seed, encPayload) ?: return
                    val inner = JSONObject(plain)
                    val innerType = inner.optString("type")

                    storage.saveKnownPeerName(from, nick, srv)
                    if (roomId != null && ts > storage.getLastRoomSyncMs()) {
                        storage.setLastRoomSyncMs(ts)
                    }

                    val isChannel = inner.optBoolean("isChannel", false) || (roomId?.startsWith("ch_") == true)
                    val groupId = if (roomId != null && !isChannel) roomId.removePrefix("grp_") else null
                    val channelId = if (roomId != null && isChannel) roomId.removePrefix("ch_") else null
                    val roomName = inner.optString("roomName").ifBlank { null }
                    val ownerId = inner.optString("ownerId").ifBlank { from }

                    if (groupId != null && roomName != null) storage.saveKnownGroup(groupId, roomName)
                    if (channelId != null && roomName != null) storage.saveChannel(ChannelMeta(channelId, roomName, ownerId))

                    when (innerType) {
                        "text" -> {
                            val text = inner.optString("text")
                            if (text.isNotEmpty()) {
                                onEvent(
                                    IncomingLanEvent(
                                        messageId = msgId, peerId = from, senderName = nick, serverName = srv,
                                        text = text, timestamp = ts, isEncrypted = true,
                                        groupId = groupId, groupName = roomName,
                                        channelId = channelId, channelName = roomName, channelOwnerId = ownerId
                                    )
                                )
                            }
                        }

                        "poll" -> {
                            val q = inner.optString("question")
                            val opts = mutableListOf<String>()
                            inner.optJSONArray("options")?.let { a -> for (i in 0 until a.length()) opts.add(a.optString(i)) }
                            if (q.isNotEmpty() && opts.size >= 2) {
                                val poll = PollData(q, opts, isAnonymous = inner.optBoolean("isAnonymous", true), allowsMultiple = inner.optBoolean("allowsMultiple", false))
                                onEvent(
                                    IncomingLanEvent(
                                        messageId = msgId, peerId = from, senderName = nick, serverName = srv,
                                        text = "", timestamp = ts, poll = poll, isEncrypted = true,
                                        groupId = groupId, groupName = roomName,
                                        channelId = channelId, channelName = roomName, channelOwnerId = ownerId
                                    )
                                )
                            }
                        }

                        "reaction" -> {
                            val targetMsgId = inner.optString("targetMsgId").ifBlank { msgId }
                            val emoji = inner.optString("emoji")
                            val delta = inner.optInt("delta", 1)
                            if (emoji.isNotEmpty()) {
                                onEvent(
                                    IncomingLanEvent(
                                        messageId = targetMsgId, peerId = from, senderName = nick, serverName = srv,
                                        text = "", timestamp = ts, groupId = groupId, channelId = channelId,
                                        reactionEmoji = emoji, reactionDelta = delta
                                    )
                                )
                            }
                        }

                        "poll_vote" -> {
                            val targetMsgId = inner.optString("targetMsgId").ifBlank { msgId }
                            val optIdx = inner.optInt("optionIndex", -1)
                            val delta = inner.optInt("delta", 1)
                            if (optIdx >= 0) {
                                onEvent(
                                    IncomingLanEvent(
                                        messageId = targetMsgId, peerId = from, senderName = nick, serverName = srv,
                                        text = "", timestamp = ts, groupId = groupId, channelId = channelId,
                                        pollVoteOption = optIdx, pollVoteDelta = delta
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun destroy() {
        enabled = false
        disconnect()
        scope.cancel()
    }
}
