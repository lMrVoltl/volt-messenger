package com.voltage.messenger.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voltage.messenger.BuildConfig
import com.voltage.messenger.core.update.ReleaseInfo
import com.voltage.messenger.core.update.UpdateManager
import com.voltage.messenger.model.AppThemeConfig
import com.voltage.messenger.model.DEFAULT_AI_SYSTEM_PROMPT
import com.voltage.messenger.ui.theme.AppSurface
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    config: AppThemeConfig,
    onConfigChange: (AppThemeConfig) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var updateStatus by remember { mutableStateOf("Текущая версия: v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})") }
    var availableRelease by remember { mutableStateOf<ReleaseInfo?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

    var promptDraft by remember(config.aiSystemPrompt) { mutableStateOf(config.aiSystemPrompt) }

    val themeModes = listOf("Системная", "Светлая", "Тёмная")
    val accentNames = listOf("Material You", "Индиго", "Изумруд", "Аметист", "Закат")
    val wallpapers = listOf("Чистый", "Градиент", "Сетка")

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                }
                Text(
                    text = "Настройки и дизайн",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Автообновление с GitHub
            AppSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Обновления с GitHub", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text(updateStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    if (isDownloading) {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            enabled = !isChecking && !isDownloading,
                            onClick = {
                                isChecking = true
                                updateStatus = "Проверяю GitHub Releases..."
                                scope.launch {
                                    UpdateManager.checkLatestRelease()
                                        .onSuccess { rel ->
                                            availableRelease = rel
                                            updateStatus = if (rel.isNewer) {
                                                "Доступно обновление: ${rel.versionName}"
                                            } else {
                                                "У тебя уже последняя версия (${rel.versionName})"
                                            }
                                        }
                                        .onFailure { err ->
                                            updateStatus = "Ошибка: ${err.message}"
                                        }
                                    isChecking = false
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isChecking) "Проверка..." else "Проверить")
                        }

                        if (availableRelease?.isNewer == true) {
                            Button(
                                enabled = !isDownloading,
                                onClick = {
                                    val rel = availableRelease ?: return@Button
                                    isDownloading = true
                                    downloadProgress = 0f
                                    updateStatus = "Скачивание ${rel.versionName}..."
                                    scope.launch {
                                        UpdateManager.downloadApk(context, rel.downloadUrl) { p ->
                                            downloadProgress = p
                                        }.onSuccess { file ->
                                            isDownloading = false
                                            updateStatus = "Скачано! Запускаю установку..."
                                            val started = UpdateManager.installApk(context, file)
                                            if (!started) {
                                                Toast.makeText(context, "Разреши установку для Volt Messenger и нажми снова", Toast.LENGTH_LONG).show()
                                            }
                                        }.onFailure { err ->
                                            isDownloading = false
                                            updateStatus = "Ошибка загрузки: ${err.message}"
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Обновить")
                            }
                        }
                    }
                }
            }

            // 2. Живое превью пузырей + Внешний вид
            AppSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Palette, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Внешний вид и тема", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }

                    // Превью пузырей
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val r = config.bubbleRadius.dp
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(topStart = r, topEnd = r, bottomEnd = r, bottomStart = 5.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("Так выглядят входящие сообщения", style = MaterialTheme.typography.bodyMedium)
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.End)
                                .clip(RoundedCornerShape(topStart = r, topEnd = r, bottomStart = r, bottomEnd = 5.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                "А так — твои ответы ⚡",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Text("Режим темы", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        themeModes.forEachIndexed { idx, label ->
                            FilterChip(
                                selected = config.themeMode == idx,
                                onClick = { onConfigChange(config.copy(themeMode = idx)) },
                                label = { Text(label) },
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                    }

                    Text("Акцентная палитра", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(accentNames) { idx, label ->
                            FilterChip(
                                selected = config.accentPreset == idx,
                                onClick = { onConfigChange(config.copy(accentPreset = idx)) },
                                label = { Text(label) },
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                    }

                    Text("Фон чата", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        wallpapers.forEachIndexed { idx, label ->
                            FilterChip(
                                selected = config.wallpaperStyle == idx,
                                onClick = { onConfigChange(config.copy(wallpaperStyle = idx)) },
                                label = { Text(label) },
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = "Скругление сообщений: ${config.bubbleRadius} dp",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Slider(
                            value = config.bubbleRadius.toFloat(),
                            onValueChange = { onConfigChange(config.copy(bubbleRadius = it.toInt())) },
                            valueRange = 10f..26f
                        )
                    }
                }
            }

            // 3. Системный промпт для Volt AI (чтобы не считал себя ChatGPT)
            AppSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Характер Volt AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedTextField(
                        value = promptDraft,
                        onValueChange = { promptDraft = it },
                        label = { Text("Системный промпт") },
                        minLines = 3,
                        maxLines = 5,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                promptDraft = DEFAULT_AI_SYSTEM_PROMPT
                                onConfigChange(config.copy(aiSystemPrompt = DEFAULT_AI_SYSTEM_PROMPT))
                                Toast.makeText(context, "Сброшено на Volt AI", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Сбросить") }
                        Button(
                            onClick = {
                                onConfigChange(config.copy(aiSystemPrompt = promptDraft.trim().ifEmpty { DEFAULT_AI_SYSTEM_PROMPT }))
                                Toast.makeText(context, "Промпт сохранён", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Сохранить")
                        }
                    }
                }
            }
        }
    }
}
