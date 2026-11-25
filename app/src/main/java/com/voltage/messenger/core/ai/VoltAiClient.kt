package com.voltage.messenger.core.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class VoltAiStatus {
    CHECKING,
    NOT_INSTALLED,
    SERVER_OFFLINE,
    READY
}

data class AiChatMessage(
    val role: String,
    val content: String
)

object VoltAiClient {
    const val PACKAGE_NAME = "com.voltage.ai"
    private const val BASE_URL = "http://127.0.0.1:8080/v1"
    private const val API_KEY = "volt-local"

    private val pingClient = OkHttpClient.Builder()
        .connectTimeout(500, TimeUnit.MILLISECONDS)
        .readTimeout(800, TimeUnit.MILLISECONDS)
        .build()

    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    fun isInstalled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    PACKAGE_NAME,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun launchVoltAi(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    suspend fun checkStatus(context: Context): VoltAiStatus = withContext(Dispatchers.IO) {
        if (!isInstalled(context)) return@withContext VoltAiStatus.NOT_INSTALLED

        val request = Request.Builder()
            .url("$BASE_URL/models")
            .header("Authorization", "Bearer $API_KEY")
            .get()
            .build()

        return@withContext try {
            pingClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) VoltAiStatus.READY else VoltAiStatus.SERVER_OFFLINE
            }
        } catch (_: Exception) {
            VoltAiStatus.SERVER_OFFLINE
        }
    }

    fun streamCompletion(history: List<AiChatMessage>): Flow<String> = flow {
        val messagesJson = JSONArray()
        history.forEach { msg ->
            messagesJson.put(
                JSONObject()
                    .put("role", msg.role)
                    .put("content", msg.content)
            )
        }

        val payload = JSONObject()
            .put("model", "volt-local")
            .put("messages", messagesJson)
            .put("stream", true)
            .put("temperature", 0.7)

        val request = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .header("Authorization", "Bearer $API_KEY")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        streamClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                emit("Ошибка сервера Volt AI: HTTP ${response.code}")
                return@use
            }

            val source = response.body?.source() ?: return@use
            val nonSseBuffer = StringBuilder()
            var isSseMode = false

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                if (trimmed.startsWith("data:")) {
                    isSseMode = true
                    val data = trimmed.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    try {
                        val json = JSONObject(data)
                        val choices = json.optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue
                        val first = choices.getJSONObject(0)
                        val delta = first.optJSONObject("delta")
                        val token = delta?.optString("content")
                            ?: first.optJSONObject("message")?.optString("content")
                            ?: ""
                        if (token.isNotEmpty()) emit(token)
                    } catch (_: Exception) {
                    }
                } else if (!isSseMode) {
                    nonSseBuffer.append(trimmed)
                }
            }

            if (!isSseMode && nonSseBuffer.isNotEmpty()) {
                val raw = nonSseBuffer.toString()
                try {
                    val json = JSONObject(raw)
                    val content = json.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                        .orEmpty()
                    emit(content.ifEmpty { raw })
                } catch (_: Exception) {
                    emit(raw)
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
