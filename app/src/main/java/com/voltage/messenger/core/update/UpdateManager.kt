package com.voltage.messenger.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.voltage.messenger.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val buildNumber: Int,
    val versionName: String,
    val downloadUrl: String,
    val notes: String,
    val isNewer: Boolean
)

object UpdateManager {
    private const val REPO_BASE = "https://github.com/lMrVoltl/volt-messenger"

    private val noRedirectClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun checkLatestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$REPO_BASE/releases/latest")
                .header("User-Agent", "Mozilla/5.0 (Android) VoltMessenger/${BuildConfig.VERSION_NAME}")
                .build()

            noRedirectClient.newCall(req).execute().use { resp ->
                val location = resp.header("Location").orEmpty()
                if (location.isBlank() || !location.contains("/releases/tag/")) {
                    error("Не удалось получить последний релиз (HTTP ${resp.code}).")
                }
                val tag = location.substringAfterLast("/").trim()
                val buildNum = tag.removePrefix("build-").toIntOrNull()
                    ?: Regex("\\d+").findAll(tag).lastOrNull()?.value?.toIntOrNull()
                    ?: 0
                if (buildNum <= 0) error("Некорректный тег релиза: $tag")

                val apkUrl = "$REPO_BASE/releases/download/$tag/app-release.apk"
                ReleaseInfo(
                    buildNumber = buildNum,
                    versionName = "Volt Messenger (build $buildNum)",
                    downloadUrl = apkUrl,
                    notes = "Релиз $tag",
                    isNewer = buildNum > BuildConfig.VERSION_CODE
                )
            }
        }
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Android) VoltMessenger/${BuildConfig.VERSION_NAME}")
                .build()

            val outFile = File(context.cacheDir, "volt_update.apk")
            if (outFile.exists()) outFile.delete()

            downloadClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Ошибка скачивания: HTTP ${resp.code}")
                val body = resp.body ?: error("Пустой поток загрузки")
                val total = body.contentLength()
                val buf = ByteArray(64 * 1024)
                var downloaded = 0L

                body.byteStream().use { inp ->
                    FileOutputStream(outFile).use { fos ->
                        while (true) {
                            val read = inp.read(buf)
                            if (read <= 0) break
                            fos.write(buf, 0, read)
                            downloaded += read
                            if (total > 0L) {
                                onProgress((downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            onProgress(1f)
            outFile
        }
    }

    fun installApk(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            return false
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }
}
