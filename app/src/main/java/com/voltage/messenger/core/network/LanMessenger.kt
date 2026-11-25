package com.voltage.messenger.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.voltage.messenger.core.crypto.EncryptedChannel
import com.voltage.messenger.core.crypto.VoltCrypto
import com.voltage.messenger.core.storage.ChatStorage
import com.voltage.messenger.model.AttachmentInfo
import com.voltage.messenger.model.CallStatus
import com.voltage.messenger.model.ChannelMeta
import com.voltage.messenger.model.LanCallState
import com.voltage.messenger.model.LanPeer
import com.voltage.messenger.model.PollData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class IncomingLanEvent(
    val messageId: String,
    val peerId: String,
    val senderName: String,
    val serverName: String,
    val text: String,
    val timestamp: Long,
    val attachment: AttachmentInfo? = null,
    val poll: PollData? = null,
    val progress: Float? = null,
    val speedMbps: Float? = null,
    val isEncrypted: Boolean = true,
    val groupId: String? = null,
    val groupName: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val channelOwnerId: String? = null,
    val reactionEmoji: String? = null,
    val reactionDelta: Int = 0,
    val pollVoteOption: Int? = null,
    val pollVoteDelta: Int = 0
)

class LanMessenger(
    private val context: Context,
    private val storage: ChatStorage,
    private val myDeviceId: String,
    private var myNickname: String,
    private var myServerName: String,
    private val onEvent: (IncomingLanEvent) -> Unit
) {
    private val shortId = myDeviceId.take(6)
    private val serviceType = "_voltmessenger._tcp."
    private val udpPort = 45888
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registeredName: String? = null
    private var isStarted = false

    private val peersMap = ConcurrentHashMap<String, LanPeer>()
    private val _peers = MutableStateFlow<List<LanPeer>>(emptyList())
    val peers: StateFlow<List<LanPeer>> = _peers.asStateFlow()

    private val _isLanActive = MutableStateFlow(false)
    val isLanActive: StateFlow<Boolean> = _isLanActive.asStateFlow()

    private val _localPort = MutableStateFlow(0)
    val localPort: StateFlow<Int> = _localPort.asStateFlow()

    private val _localIp = MutableStateFlow("127.0.0.1")
    val localIp: StateFlow<String> = _localIp.asStateFlow()

    private val _callState = MutableStateFlow(LanCallState())
    val callState: StateFlow<LanCallState> = _callState.asStateFlow()

    private var activeCallSocket: Socket? = null
    private var incomingCallDecision: CompletableDeferred<Boolean>? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (isStarted) return
        isStarted = true
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("volt_lan_lock")?.apply { setReferenceCounted(true); acquire() }
        } catch (_: Exception) {}

        scope.launch {
            try {
                val socket = ServerSocket(0)
                serverSocket = socket
                val port = socket.localPort
                _localPort.value = port
                _localIp.value = detectLocalIpv4()
                _isLanActive.value = true

                registerNsdService(port)
                startNsdDiscovery()
                startUdpDiscovery(port)

                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    launch { handleIncomingTcp(client) }
                }
            } catch (_: Exception) { _isLanActive.value = false }
        }
    }

    fun updateIdentity(newNickname: String, newServerName: String) {
        myNickname = newNickname
        myServerName = newServerName
        stop()
        start()
    }

    fun addManualPeer(ip: String, port: Int, label: String = "Устройство $ip") {
        val cleanIp = ip.trim()
        if (cleanIp.isEmpty() || port !in 1..65535) return
        val id = "ip_${cleanIp.replace(".", "_")}"
        val peer = LanPeer(id, label, cleanIp, cleanIp, port, Long.MAX_VALUE)
        peersMap[id] = peer
        storage.saveKnownPeerName(id, peer.nickname, peer.serverName)
        publishPeers()
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        _isLanActive.value = false
        _localPort.value = 0
        endCall()
        try { discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        try { registrationListener?.let { nsdManager?.unregisterService(it) } } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        try { if (multicastLock?.isHeld == true) multicastLock?.release() } catch (_: Exception) {}
        serverSocket = null; udpSocket = null; discoveryListener = null; registrationListener = null
    }

    fun destroy() { stop(); scope.cancel() }

    private fun startUdpDiscovery(tcpPort: Int) {
        scope.launch {
            try {
                val ds = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(udpPort)) }
                udpSocket = ds
                val buf = ByteArray(1024)
                while (isActive && !ds.isClosed) {
                    val packet = DatagramPacket(buf, buf.size)
                    try { ds.receive(packet) } catch (_: Exception) { break }
                    val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val host = packet.address?.hostAddress ?: continue
                    try {
                        val json = JSONObject(raw)
                        if (json.optString("magic") != "VOLT_LAN_V1") continue
                        val peerId = json.optString("id")
                        if (peerId.isEmpty() || peerId == shortId) continue
                        val nick = json.optString("nick", "Volt User")
                        val srv = json.optString("srv", "Volt-Node")
                        val port = json.optInt("port", 0)
                        if (port !in 1..65535) continue

                        val isNew = !peersMap.containsKey(peerId)
                        peersMap[peerId] = LanPeer(peerId, nick, srv, host, port, System.currentTimeMillis())
                        storage.saveKnownPeerName(peerId, nick, srv)
                        publishPeers()

                        if (isNew || json.optBoolean("needReply", false)) {
                            val reply = JSONObject().put("magic", "VOLT_LAN_V1").put("id", shortId).put("nick", myNickname).put("srv", myServerName).put("port", tcpPort).put("needReply", false).toString().toByteArray(Charsets.UTF_8)
                            try { ds.send(DatagramPacket(reply, reply.size, packet.address, udpPort)) } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        scope.launch {
            while (isActive && isStarted) {
                _localIp.value = detectLocalIpv4()
                val data = JSONObject().put("magic", "VOLT_LAN_V1").put("id", shortId).put("nick", myNickname).put("srv", myServerName).put("port", tcpPort).put("needReply", true).toString().toByteArray(Charsets.UTF_8)
                val ds = udpSocket
                if (ds != null && !ds.isClosed) {
                    for (addr in collectBroadcastAddresses()) {
                        try { ds.send(DatagramPacket(data, data.size, addr, udpPort)) } catch (_: Exception) {}
                    }
                }
                val now = System.currentTimeMillis()
                var changed = false
                val it = peersMap.entries.iterator()
                while (it.hasNext()) {
                    val entry = it.next()
                    if (entry.value.lastSeenMs != Long.MAX_VALUE && now - entry.value.lastSeenMs > 15_000L) { it.remove(); changed = true }
                }
                if (changed) publishPeers()
                delay(2200L)
            }
        }
    }

    private fun collectBroadcastAddresses(): Set<InetAddress> {
        val result = linkedSetOf<InetAddress>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val bcast = ia.broadcast
                    if (bcast is Inet4Address) result.add(bcast)
                }
            }
        } catch (_: Exception) {}
        try { result.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
        try { result.add(InetAddress.getByName("192.168.43.255")) } catch (_: Exception) {}
        return result
    }

    private fun detectLocalIpv4(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            var fallback = "127.0.0.1"
            while (ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) return ip
                        fallback = ip
                    }
                }
            }
            return fallback
        } catch (_: Exception) { return "127.0.0.1" }
    }

    private fun publishPeers() { _peers.value = peersMap.values.sortedBy { it.nickname.lowercase() } }

    suspend fun sendTextMessage(peer: LanPeer, messageId: String, text: String, groupId: String? = null, groupName: String? = null, channelId: String? = null, channelName: String? = null): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(peer.host, peer.port), 3000)
                val ch = VoltCrypto.handshake(DataInputStream(BufferedInputStream(socket.getInputStream(), 32768)), DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 32768)), true)
                val header = JSONObject().put("type", "text").put("msgId", messageId).put("peerId", shortId).put("senderName", myNickname).put("serverName", myServerName).put("text", text).put("ts", System.currentTimeMillis())
                if (groupId != null) header.put("groupId", groupId)
                if (groupName != null) header.put("groupName", groupName)
                if (channelId != null) { header.put("channelId", channelId); header.put("channelName", channelName ?: "Канал"); header.put("channelOwnerId", shortId) }
                ch.writeFrame(header.toString().toByteArray(Charsets.UTF_8)); ch.flush()
            }
            true
        } catch (_: Exception) { false }
    }

    suspend fun sendPollMessage(peer: LanPeer, messageId: String, poll: PollData, groupId: String? = null, groupName: String? = null, channelId: String? = null, channelName: String? = null): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(peer.host, peer.port), 3000)
                val ch = VoltCrypto.handshake(DataInputStream(BufferedInputStream(socket.getInputStream(), 32768)), DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 32768)), true)
                val header = JSONObject()
                    .put("type", "poll")
                    .put("msgId", messageId)
                    .put("peerId", shortId)
                    .put("senderName", myNickname)
                    .put("serverName", myServerName)
                    .put("question", poll.question)
                    .put("options", JSONArray().apply { poll.options.forEach { put(it) } })
                    .put("isAnonymous", poll.isAnonymous)
                    .put("allowsMultiple", poll.allowsMultiple)
                    .put("ts", System.currentTimeMillis())
                if (groupId != null) header.put("groupId", groupId)
                if (groupName != null) header.put("groupName", groupName)
                if (channelId != null) { header.put("channelId", channelId); header.put("channelName", channelName ?: "Канал"); header.put("channelOwnerId", shortId) }
                ch.writeFrame(header.toString().toByteArray(Charsets.UTF_8)); ch.flush()
            }
            true
        } catch (_: Exception) { false }
    }

    suspend fun sendPollVote(peer: LanPeer, messageId: String, optionIndex: Int, delta: Int, groupId: String? = null, channelId: String? = null): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(peer.host, peer.port), 2500)
                val ch = VoltCrypto.handshake(DataInputStream(BufferedInputStream(socket.getInputStream(), 16384)), DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 16384)), true)
                val header = JSONObject().put("type", "poll_vote").put("msgId", messageId).put("peerId", shortId).put("senderName", myNickname).put("optionIndex", optionIndex).put("delta", delta)
                if (groupId != null) header.put("groupId", groupId)
                if (channelId != null) header.put("channelId", channelId)
                ch.writeFrame(header.toString().toByteArray(Charsets.UTF_8)); ch.flush()
            }
            true
        } catch (_: Exception) { false }
    }

    suspend fun sendReaction(peer: LanPeer, messageId: String, emoji: String, delta: Int, groupId: String? = null, channelId: String? = null): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(peer.host, peer.port), 2500)
                val ch = VoltCrypto.handshake(DataInputStream(BufferedInputStream(socket.getInputStream(), 16384)), DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 16384)), true)
                val header = JSONObject().put("type", "reaction").put("msgId", messageId).put("peerId", shortId).put("senderName", myNickname).put("emoji", emoji).put("delta", delta)
                if (groupId != null) header.put("groupId", groupId)
                if (channelId != null) header.put("channelId", channelId)
                ch.writeFrame(header.toString().toByteArray(Charsets.UTF_8)); ch.flush()
            }
            true
        } catch (_: Exception) { false }
    }

    suspend fun sendFileMessage(peer: LanPeer, messageId: String, caption: String, attachment: AttachmentInfo, groupId: String? = null, groupName: String? = null, channelId: String? = null, channelName: String? = null, onProgress: (Float, Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val totalSize = when {
                attachment.localPath.isNotEmpty() && File(attachment.localPath).exists() -> File(attachment.localPath).length()
                attachment.sizeBytes > 0L -> attachment.sizeBytes
                else -> 0L
            }
            Socket().use { socket ->
                socket.tcpNoDelay = true; socket.sendBufferSize = 512 * 1024
                socket.connect(InetSocketAddress(peer.host, peer.port), 4000)
                val ch = VoltCrypto.handshake(DataInputStream(BufferedInputStream(socket.getInputStream(), 65536)), DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 256 * 1024)), true)
                val header = JSONObject().put("type", "file").put("msgId", messageId).put("peerId", shortId).put("senderName", myNickname).put("serverName", myServerName).put("text", caption).put("fileName", attachment.name).put("mimeType", attachment.mimeType).put("fileSize", totalSize).put("isVoice", attachment.isVoice).put("durationSec", attachment.durationSec).put("ts", System.currentTimeMillis())
                if (groupId != null) header.put("groupId", groupId)
                if (groupName != null) header.put("groupName", groupName)
                if (channelId != null) { header.put("channelId", channelId); header.put("channelName", channelName ?: "Канал"); header.put("channelOwnerId", shortId) }
                ch.writeFrame(header.toString().toByteArray(Charsets.UTF_8)); ch.flush()

                val stream = if (attachment.localPath.isNotEmpty() && File(attachment.localPath).exists()) FileInputStream(attachment.localPath)
                else context.contentResolver.openInputStream(Uri.parse(attachment.uriString ?: return@withContext false)) ?: return@withContext false

                stream.use { fileIn ->
                    val buffer = ByteArray(256 * 1024)
                    var sent = 0L; val startMs = System.currentTimeMillis(); var lastReportMs = 0L
                    while (true) {
                        val read = fileIn.read(buffer)
                        if (read <= 0) break
                        ch.writeFrame(buffer, read); sent += read
                        val now = System.currentTimeMillis()
                        if (now - lastReportMs >= 120L) {
                            val mbps = (sent / (1024f * 1024f)) / (((now - startMs).coerceAtLeast(1L)) / 1000f)
                            onProgress(if (totalSize > 0L) (sent.toFloat() / totalSize.toFloat()).coerceIn(0f, 0.99f) else 0.5f, mbps)
                            lastReportMs = now
                        }
                    }
                    ch.writeFrame(ByteArray(0), 0); ch.flush()
                }
                onProgress(1f, 0f)
            }
            true
        } catch (_: Exception) { false }
    }

    fun startCall(peer: LanPeer) {
        if (_callState.value.status != CallStatus.IDLE) return
        _callState.value = LanCallState(CallStatus.OUTGOING, peer.id, peer.nickname)
        scope.launch {
            try {
                val socket = Socket(); socket.tcpNoDelay = true; socket.connect(InetSocketAddress(peer.host, peer.port), 4000)
                activeCallSocket = socket
                val ch = VoltCrypto.handshake(DataInputStream(BufferedInputStream(socket.getInputStream(), 32768)), DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 32768)), true)
                ch.writeFrame(JSONObject().put("type", "call_audio").put("peerId", shortId).put("senderName", myNickname).put("serverName", myServerName).toString().toByteArray(Charsets.UTF_8)); ch.flush()
                _callState.value = _callState.value.copy(sessionEmojis = ch.sessionEmojis)
                if (JSONObject(String(ch.readFrame(), Charsets.UTF_8)).optString("status") != "accept") { endCall(); return@launch }
                _callState.value = _callState.value.copy(status = CallStatus.CONNECTED, connectedAtMs = System.currentTimeMillis())
                runFullDuplexAudioCall(socket, ch)
            } catch (_: Exception) { endCall() }
        }
    }

    fun acceptIncomingCall() { incomingCallDecision?.complete(true) }
    fun declineIncomingCall() { incomingCallDecision?.complete(false); endCall() }
    fun toggleMute() { _callState.value = _callState.value.copy(isMuted = !_callState.value.isMuted) }
    @Suppress("DEPRECATION") fun toggleSpeaker() {
        val next = !_callState.value.isSpeaker
        _callState.value = _callState.value.copy(isSpeaker = next)
        try { val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager; am?.mode = AudioManager.MODE_IN_COMMUNICATION; am?.isSpeakerphoneOn = next } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION") fun endCall() {
        incomingCallDecision?.complete(false); incomingCallDecision = null
        try { activeCallSocket?.close() } catch (_: Exception) {}
        activeCallSocket = null
        try { val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager; am?.mode = AudioManager.MODE_NORMAL; am?.isSpeakerphoneOn = false } catch (_: Exception) {}
        _callState.value = LanCallState()
    }

    @SuppressLint("MissingPermission")
    private suspend fun runFullDuplexAudioCall(socket: Socket, ch: EncryptedChannel) {
        val sampleRate = 16000
        val minRec = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2560)
        val minPlay = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2560)
        var recorder: AudioRecord? = null; var track: AudioTrack? = null
        try {
            (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode = AudioManager.MODE_IN_COMMUNICATION
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minRec * 2)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(minPlay * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
            recorder.startRecording(); track.play()
            val sendJob = scope.launch {
                val pcm = ByteArray(1280); val silence = ByteArray(1280)
                while (!socket.isClosed && _callState.value.status == CallStatus.CONNECTED) {
                    val read = recorder.read(pcm, 0, pcm.size)
                    if (read > 0) { try { ch.writeFrame(if (_callState.value.isMuted) silence else pcm, read); ch.flush() } catch (_: Exception) { break } }
                }
            }
            while (!socket.isClosed && _callState.value.status == CallStatus.CONNECTED) {
                val frame = try { ch.readFrame(maxSize = 65536) } catch (_: Exception) { break }
                if (frame.isNotEmpty()) track.write(frame, 0, frame.size)
            }
            sendJob.cancel()
        } catch (_: Exception) {
        } finally {
            try { recorder?.stop() } catch (_: Exception) {}; try { recorder?.release() } catch (_: Exception) {}
            try { track?.stop() } catch (_: Exception) {}; try { track?.release() } catch (_: Exception) {}
            endCall()
        }
    }

    private suspend fun handleIncomingTcp(client: Socket) {
        try {
            client.tcpNoDelay = true; client.receiveBufferSize = 512 * 1024
            val ch = VoltCrypto.handshake(DataInputStream(BufferedInputStream(client.getInputStream(), 256 * 1024)), DataOutputStream(BufferedOutputStream(client.getOutputStream(), 65536)), false)
            val headerJson = JSONObject(String(ch.readFrame(), Charsets.UTF_8))
            val type = headerJson.optString("type")
            val msgId = headerJson.optString("msgId").ifBlank { UUID.randomUUID().toString() }
            val peerId = headerJson.optString("peerId")
            val senderName = headerJson.optString("senderName", "Собеседник")
            val serverName = headerJson.optString("serverName", "Volt-Node")
            val groupId = headerJson.optString("groupId").ifBlank { null }
            val groupName = headerJson.optString("groupName").ifBlank { null }
            val channelId = headerJson.optString("channelId").ifBlank { null }
            val channelName = headerJson.optString("channelName").ifBlank { null }
            val channelOwnerId = headerJson.optString("channelOwnerId").ifBlank { null }
            val ts = headerJson.optLong("ts", System.currentTimeMillis())

            if (peerId.isNotEmpty()) storage.saveKnownPeerName(peerId, senderName, serverName)
            if (groupId != null && groupName != null) storage.saveKnownGroup(groupId, groupName)
            if (channelId != null && channelName != null) storage.saveChannel(ChannelMeta(channelId, channelName, channelOwnerId ?: peerId))

            when (type) {
                "text" -> {
                    val text = headerJson.optString("text")
                    if (peerId.isNotEmpty() && text.isNotEmpty()) {
                        onEvent(IncomingLanEvent(msgId, peerId, senderName, serverName, text, ts, groupId = groupId, groupName = groupName, channelId = channelId, channelName = channelName, channelOwnerId = channelOwnerId))
                    }
                    client.close()
                }

                "poll" -> {
                    val question = headerJson.optString("question")
                    val opts = mutableListOf<String>()
                    headerJson.optJSONArray("options")?.let { arr -> for (i in 0 until arr.length()) opts.add(arr.optString(i)) }
                    if (peerId.isNotEmpty() && question.isNotEmpty() && opts.size >= 2) {
                        val poll = PollData(question, opts, isAnonymous = headerJson.optBoolean("isAnonymous", true), allowsMultiple = headerJson.optBoolean("allowsMultiple", false))
                        onEvent(IncomingLanEvent(msgId, peerId, senderName, serverName, "", ts, poll = poll, groupId = groupId, groupName = groupName, channelId = channelId, channelName = channelName, channelOwnerId = channelOwnerId))
                    }
                    client.close()
                }

                "poll_vote" -> {
                    val optIdx = headerJson.optInt("optionIndex", -1)
                    val delta = headerJson.optInt("delta", 1)
                    if (peerId.isNotEmpty() && optIdx >= 0) {
                        onEvent(IncomingLanEvent(msgId, peerId, senderName, serverName, "", ts, groupId = groupId, channelId = channelId, pollVoteOption = optIdx, pollVoteDelta = delta))
                    }
                    client.close()
                }

                "reaction" -> {
                    val emoji = headerJson.optString("emoji")
                    val delta = headerJson.optInt("delta", 1)
                    if (peerId.isNotEmpty() && emoji.isNotEmpty()) {
                        onEvent(IncomingLanEvent(msgId, peerId, senderName, serverName, "", ts, groupId = groupId, channelId = channelId, reactionEmoji = emoji, reactionDelta = delta))
                    }
                    client.close()
                }

                "file" -> {
                    val caption = headerJson.optString("text", "")
                    val fileName = headerJson.optString("fileName", "file.bin")
                    val mimeType = headerJson.optString("mimeType", "application/octet-stream")
                    val expectedSize = headerJson.optLong("fileSize", 0L)
                    val isVoice = headerJson.optBoolean("isVoice", false)
                    val durationSec = headerJson.optInt("durationSec", 0)
                    val outFile = storage.createIncomingFile(fileName)
                    val baseAtt = AttachmentInfo(fileName, outFile.absolutePath, mimeType, expectedSize, isVoice = isVoice, durationSec = durationSec)

                    onEvent(IncomingLanEvent(msgId, peerId, senderName, serverName, caption, ts, baseAtt, progress = 0f, speedMbps = 0f, groupId = groupId, groupName = groupName, channelId = channelId, channelName = channelName, channelOwnerId = channelOwnerId))

                    var received = 0L; val startMs = System.currentTimeMillis(); var lastReportMs = 0L
                    FileOutputStream(outFile).use { fos ->
                        while (true) {
                            val chunk = ch.readFrame(maxSize = 2 * 1024 * 1024)
                            if (chunk.isEmpty()) break
                            fos.write(chunk); received += chunk.size
                            val now = System.currentTimeMillis()
                            if (now - lastReportMs >= 140L) {
                                val mbps = (received / (1024f * 1024f)) / (((now - startMs).coerceAtLeast(1L)) / 1000f)
                                val frac = if (expectedSize > 0L) (received.toFloat() / expectedSize.toFloat()).coerceIn(0f, 0.99f) else 0.5f
                                onEvent(IncomingLanEvent(msgId, peerId, senderName, serverName, caption, ts, baseAtt.copy(sizeBytes = maxOf(expectedSize, received)), progress = frac, speedMbps = mbps, groupId = groupId, groupName = groupName, channelId = channelId, channelName = channelName, channelOwnerId = channelOwnerId))
                                lastReportMs = now
                            }
                        }
                    }
                    if (!isVoice) storage.exportToPublicDownloads(outFile, fileName, mimeType)
                    onEvent(IncomingLanEvent(msgId, peerId, senderName, serverName, caption, ts, baseAtt.copy(sizeBytes = outFile.length()), progress = null, speedMbps = null, groupId = groupId, groupName = groupName, channelId = channelId, channelName = channelName, channelOwnerId = channelOwnerId))
                    client.close()
                }

                "call_audio" -> {
                    if (_callState.value.status != CallStatus.IDLE) { ch.writeFrame(JSONObject().put("status", "busy").toString().toByteArray(Charsets.UTF_8)); ch.flush(); client.close(); return }
                    activeCallSocket = client
                    val decision = CompletableDeferred<Boolean>()
                    incomingCallDecision = decision
                    _callState.value = LanCallState(CallStatus.INCOMING, peerId, senderName, sessionEmojis = ch.sessionEmojis)
                    val accepted = decision.await()
                    if (!accepted) { try { ch.writeFrame(JSONObject().put("status", "reject").toString().toByteArray(Charsets.UTF_8)); ch.flush() } catch (_: Exception) {}; endCall(); return }
                    ch.writeFrame(JSONObject().put("status", "accept").toString().toByteArray(Charsets.UTF_8)); ch.flush()
                    _callState.value = _callState.value.copy(status = CallStatus.CONNECTED, connectedAtMs = System.currentTimeMillis())
                    runFullDuplexAudioCall(client, ch)
                }

                else -> client.close()
            }
        } catch (_: Exception) { try { client.close() } catch (_: Exception) {} }
    }

    private fun sanitizePart(raw: String, fallback: String): String =
        raw.replace("--", "-").replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ _-]"), "").trim().take(14).ifEmpty { fallback }

    private fun registerNsdService(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "VM_${shortId}_${sanitizePart(myServerName, "VoltNode")}--${sanitizePart(myNickname, "User")}"
            serviceType = this@LanMessenger.serviceType; setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) { registeredName = nsdServiceInfo.serviceName }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        try { nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) } catch (_: Exception) {}
    }

    private fun startNsdDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.startsWith("_voltmessenger") || service.serviceName == registeredName || service.serviceName.startsWith("VM_${shortId}_")) return
                resolveNsdPeer(service)
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        try { nsdManager?.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun resolveNsdPeer(service: NsdServiceInfo) {
        try {
            nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress ?: return
                    val parts = serviceInfo.serviceName.split("_", limit = 3)
                    if (parts.size < 3 || parts[0] != "VM") return
                    val peerId = parts[1]
                    if (peerId == shortId) return
                    val tail = parts[2].split("--", limit = 2)
                    val srv = tail.getOrNull(0)?.ifBlank { "Volt-Node" } ?: "Volt-Node"
                    val nick = tail.getOrNull(1)?.ifBlank { srv } ?: srv
                    peersMap[peerId] = LanPeer(peerId, nick, srv, host, serviceInfo.port, System.currentTimeMillis())
                    storage.saveKnownPeerName(peerId, nick, srv)
                    publishPeers()
                }
            })
        } catch (_: Exception) {}
    }
}
