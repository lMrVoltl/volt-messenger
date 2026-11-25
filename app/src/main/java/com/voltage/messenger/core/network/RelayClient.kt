package com.voltage.messenger.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

enum class RelayConnectionState(val title: String) {
    DISABLED("Выключен • работает только локальный P2P"),
    STANDBY("Зачаток готов • сервер подключим в будущей версии"),
    CONNECTING("Подключение к общему серверу..."),
    CONNECTED("Подключён к общему серверу (E2EE)"),
    ERROR("Нет связи с сервером")
}

data class EncryptedRelayEnvelope(
    val messageId: String,
    val targetChatId: String,
    val senderId: String,
    val ciphertextBase64: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Зачаток клиента общего сервера (Relay) для будущего общения через мобильный интернет.
 * Все сообщения будут уходить сюда уже зашифрованными (E2EE) и буферизироваться при офлайне.
 */
class RelayClient {
    private val _state = MutableStateFlow(RelayConnectionState.DISABLED)
    val state: StateFlow<RelayConnectionState> = _state.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val outboxQueue = ConcurrentLinkedQueue<EncryptedRelayEnvelope>()

    fun configure(enabled: Boolean, url: String) {
        _serverUrl.value = url.trim()
        _state.value = if (enabled) {
            RelayConnectionState.STANDBY
        } else {
            RelayConnectionState.DISABLED
        }
    }

    fun enqueueForSync(envelope: EncryptedRelayEnvelope) {
        if (_state.value == RelayConnectionState.DISABLED) return
        outboxQueue.offer(envelope)
    }

    fun pendingCount(): Int = outboxQueue.size
}
