package com.voltage.messenger.core.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.voltage.messenger.model.LanPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

data class IncomingLanMessage(
    val peerId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long
)

class LanMessenger(
    private val context: Context,
    private val myDeviceId: String,
    private var myNickname: String,
    private val onMessageReceived: (IncomingLanMessage) -> Unit
) {
    private val serviceType = "_voltmessenger._tcp."
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registeredName: String? = null
    private var isStarted = false

    private val peersMap = ConcurrentHashMap<String, LanPeer>()
    private val _peers = MutableStateFlow<List<LanPeer>>(emptyList())
    val peers: StateFlow<List<LanPeer>> = _peers.asStateFlow()

    private val _isLanActive = MutableStateFlow(false)
    val isLanActive: StateFlow<Boolean> = _isLanActive.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (isStarted) return
        isStarted = true

        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("volt_lan_lock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {
        }

        scope.launch {
            try {
                val socket = ServerSocket(0)
                serverSocket = socket
                val localPort = socket.localPort
                _isLanActive.value = true

                registerNsdService(localPort)
                startDiscovery()

                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    launch { handleClient(client) }
                }
            } catch (_: Exception) {
                _isLanActive.value = false
            }
        }
    }

    fun restartWithNickname(newName: String) {
        myNickname = newName
        stop()
        start()
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        _isLanActive.value = false

        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {
        }
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {
        }
        serverSocket = null
        discoveryListener = null
        registrationListener = null
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    suspend fun sendMessage(peer: LanPeer, text: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(peer.host, peer.port), 2500)
                val payload = JSONObject()
                    .put("peerId", myDeviceId)
                    .put("senderName", myNickname)
                    .put("text", text)
                    .put("timestamp", System.currentTimeMillis())
                val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                writer.write(payload.toString() + "\n")
                writer.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.use { s ->
                s.soTimeout = 3000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val line = reader.readLine() ?: return
                val json = JSONObject(line)
                val peerId = json.optString("peerId")
                val senderName = json.optString("senderName", "Сосед по Wi-Fi")
                val text = json.optString("text")
                val ts = json.optLong("timestamp", System.currentTimeMillis())
                if (peerId.isNotEmpty() && text.isNotEmpty()) {
                    onMessageReceived(IncomingLanMessage(peerId, senderName, text, ts))
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun sanitizeName(raw: String): String {
        return raw.replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ _-]"), "").trim().take(16).ifEmpty { "Volt" }
    }

    private fun registerNsdService(port: Int) {
        val cleanNick = sanitizeName(myNickname)
        val info = NsdServiceInfo().apply {
            serviceName = "VM_${myDeviceId.take(6)}_$cleanNick"
            serviceType = this@LanMessenger.serviceType
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                registeredName = nsdServiceInfo.serviceName
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        try {
            nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    private fun startDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.startsWith("_voltmessenger")) return
                if (service.serviceName == registeredName) return
                if (service.serviceName.startsWith("VM_${myDeviceId.take(6)}_")) return
                resolvePeer(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val id = parsePeerId(service.serviceName) ?: return
                peersMap.remove(id)
                _peers.value = peersMap.values.toList()
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        try {
            nsdManager?.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun resolvePeer(service: NsdServiceInfo) {
        try {
            nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress ?: return
                    val port = serviceInfo.port
                    val peerId = parsePeerId(serviceInfo.serviceName) ?: serviceInfo.serviceName
                    val peerName = parsePeerName(serviceInfo.serviceName)
                    if (peerId == myDeviceId.take(6)) return
                    peersMap[peerId] = LanPeer(
                        id = peerId,
                        name = peerName,
                        host = host,
                        port = port
                    )
                    _peers.value = peersMap.values.toList()
                }
            })
        } catch (_: Exception) {
        }
    }

    private fun parsePeerId(serviceName: String): String? {
        val parts = serviceName.split("_", limit = 3)
        return if (parts.size >= 3 && parts[0] == "VM") parts[1] else null
    }

    private fun parsePeerName(serviceName: String): String {
        val parts = serviceName.split("_", limit = 3)
        return if (parts.size >= 3 && parts[0] == "VM") parts[2] else serviceName
    }
}
