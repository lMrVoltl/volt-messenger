package com.voltage.messenger.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.voltage.messenger.BuildConfig
import com.voltage.messenger.core.network.RelayConnectionState
import com.voltage.messenger.core.update.ReleaseInfo
import com.voltage.messenger.core.update.UpdateManager
import com.voltage.messenger.model.AppThemeConfig
import com.voltage.messenger.model.DEFAULT_AI_SYSTEM_PROMPT
import com.voltage.messenger.ui.theme.AppSurface
import kotlinx.coroutines.launch

private enum class SettingsCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector
) {
    PROFILE("Профиль и узел", "Ник, название локального сервера, ключи", Icons.Rounded.Person),
    APPEARANCE("Внешний вид и дизайн", "Тема, палитра, скругление пузырей, фон чата", Icons.Rounded.Palette),
    AI("Локальный ИИ (Volt AI)", "Управление приложением через ИИ и характер", Icons.Rounded.AutoAwesome),
    NETWORK("Сеть и общий сервер", "Локальный P2P и зачаток общего сервера", Icons.Rounded.CloudQueue),
    UPDATES("Обновления", "Проверка и установка новых сборок с GitHub", Icons.Rounded.SystemUpdate)
}

@Composable
fun SettingsScreen(
    config: AppThemeConfig,
    nickname: String,
    serverName: String,
    lanIp: String,
    lanPort: Int,
    fingerprintEmojis: String,
    relayState: RelayConnectionState,
    onIdentityChange: (newNick: String, newServer: String) -> Unit,
    onConfigChange: (AppThemeConfig) -> Unit,
    onBack: () -> Unit
) {
    var activeCategory by remember { mutableStateOf<SettingsCategory?>(null) }

    BackHandler(enabled = activeCategory != null) {
        activeCategory = null
    }

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
                IconButton(onClick = {
                    if (activeCategory != null) activeCategory = null else onBack()
                }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                }
                Text(
                    text = activeCategory?.title ?: "Настройки",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = activeCategory,
            transitionSpec = {
                val spec = spring<IntOffset>(dampingRatio = 0.92f, stiffness = 440f)
                if (targetState != null) {
                    (slideInHorizontally(spec) { it } + fadeIn(tween(160))) togetherWith
                        (slideOutHorizontally(spec) { -it / 5 } + fadeOut(tween(140)))
                } else {
                    (slideInHorizontally(spec) { -it / 5 } + fadeIn(tween(160))) togetherWith
                        (slideOutHorizontally(spec) { it } + fadeOut(tween(140)))
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            label = "settingsCategoryNav"
        ) { cat ->
            when (cat) {
                null -> CategoriesListView(onSelect = { activeCategory = it })
                SettingsCategory.PROFILE -> ProfileCategoryView(
                    nickname = nickname,
                    serverName = serverName,
                    lanIp = lanIp,
                    lanPort = lanPort,
                    fingerprintEmojis = fingerprintEmojis,
                    onSave = onIdentityChange
                )
                SettingsCategory.APPEARANCE -> AppearanceCategoryView(
                    config = config,
                    onConfigChange = onConfigChange
                )
                SettingsCategory.AI -> AiCategoryView(
                    config = config,
                    onConfigChange = onConfigChange
                )
                SettingsCategory.NETWORK -> NetworkCategoryView(
                    config = config,
                    lanIp = lanIp,
                    lanPort = lanPort,
                    relayState = relayState,
                    onConfigChange = onConfigChange
                )
                SettingsCategory.UPDATES -> UpdatesCategoryView()
            }
        }
    }
}

@Composable
private fun CategoriesListView(onSelect: (SettingsCategory) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsCategory.entries.forEach { category ->
            AppSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .clickable { onSelect(category) },
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = category.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(category.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(category.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.NavigateNext,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileCategoryView(
    nickname: String,
    serverName: String,
    lanIp: String,
    lanPort: Int,
    fingerprintEmojis: String,
    onSave: (String, String) -> Unit
) {
    val context = LocalContext.current
    var draftNick by remember(nickname) { mutableStateOf(nickname) }
    var draftServer by remember(serverName) { mutableStateOf(serverName) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OutlinedTextField(
            value = draftNick,
            onValueChange = { draftNick = it },
            label = { Text("Твой ник (виден в чатах)") },
            leadingIcon = { Icon(Icons.Rounded.Person, null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = draftServer,
            onValueChange = { draftServer = it },
            label = { Text("Название локального сервера") },
            leadingIcon = { Icon(Icons.Rounded.Dns, null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )

        AppSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Локальный адрес узла", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$lanIp:$lanPort", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        AppSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Отпечаток ключа устройства", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(fingerprintEmojis, style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        Button(
            onClick = {
                onSave(draftNick.trim().ifEmpty { nickname }, draftServer.trim().ifEmpty { serverName })
                Toast.makeText(context, "Профиль и узел обновлены", Toast.LENGTH_SHORT).show()
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сохранить") }
    }
}

@Composable
private fun AppearanceCategoryView(
    config: AppThemeConfig,
    onConfigChange: (AppThemeConfig) -> Unit
) {
    val themeModes = listOf("Системная", "Светлая", "Тёмная")
    val accentNames = listOf("Material You", "Индиго", "Изумруд", "Аметист", "Закат")
    val wallpapers = listOf("Чистый", "Градиент", "Сетка")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AppSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val r = config.bubbleRadius.dp
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(topStart = r, topEnd = r, bottomEnd = r, bottomStart = 5.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("Входящее сообщение", style = MaterialTheme.typography.bodyMedium) }
                Box(
                    modifier = Modifier
                        .align(Alignment.End)
                        .clip(RoundedCornerShape(topStart = r, topEnd = r, bottomStart = r, bottomEnd = 5.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("Твой ответ ⚡", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        }

        Text("Режим темы", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            themeModes.forEachIndexed { idx, label ->
                FilterChip(selected = config.themeMode == idx, onClick = { onConfigChange(config.copy(themeMode = idx)) }, label = { Text(label) }, shape = RoundedCornerShape(14.dp))
            }
        }

        Text("Акцентная палитра", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(accentNames) { idx, label ->
                FilterChip(selected = config.accentPreset == idx, onClick = { onConfigChange(config.copy(accentPreset = idx)) }, label = { Text(label) }, shape = RoundedCornerShape(14.dp))
            }
        }

        Text("Фон чата", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            wallpapers.forEachIndexed { idx, label ->
                FilterChip(selected = config.wallpaperStyle == idx, onClick = { onConfigChange(config.copy(wallpaperStyle = idx)) }, label = { Text(label) }, shape = RoundedCornerShape(14.dp))
            }
        }

        Column {
            Text("Скругление пузырей: ${config.bubbleRadius} dp", style = MaterialTheme.typography.labelLarge)
            Slider(value = config.bubbleRadius.toFloat(), onValueChange = { onConfigChange(config.copy(bubbleRadius = it.toInt())) }, valueRange = 10f..26f)
        }
    }
}

@Composable
private fun AiCategoryView(
    config: AppThemeConfig,
    onConfigChange: (AppThemeConfig) -> Unit
) {
    val context = LocalContext.current
    var promptDraft by remember(config.aiSystemPrompt) { mutableStateOf(config.aiSystemPrompt) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AppSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Управление приложением через ИИ", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("Позволяет писать в чат «измени ник на...», «включи светлую тему», «создай канал...»", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = config.aiControlEnabled,
                    onCheckedChange = { onConfigChange(config.copy(aiControlEnabled = it)) }
                )
            }
        }

        OutlinedTextField(
            value = promptDraft,
            onValueChange = { promptDraft = it },
            label = { Text("Базовый системный промпт Volt AI") },
            minLines = 3,
            maxLines = 6,
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

@Composable
private fun NetworkCategoryView(
    config: AppThemeConfig,
    lanIp: String,
    lanPort: Int,
    relayState: RelayConnectionState,
    onConfigChange: (AppThemeConfig) -> Unit
) {
    val context = LocalContext.current
    var urlDraft by remember(config.relayUrl) { mutableStateOf(config.relayUrl) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AppSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Локальный режим (Wi-Fi / Точка доступа)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Активен • $lanIp:$lanPort • E2EE AES-256-GCM", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }

        AppSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Общий сервер (зачаток)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(relayState.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = config.relayEnabled,
                        onCheckedChange = { onConfigChange(config.copy(relayEnabled = it)) }
                    )
                }

                OutlinedTextField(
                    value = urlDraft,
                    onValueChange = { urlDraft = it },
                    label = { Text("Адрес релей-сервера (на будущее)") },
                    placeholder = { Text("wss://relay.example.workers.dev") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                FilledTonalButton(
                    onClick = {
                        onConfigChange(config.copy(relayUrl = urlDraft.trim()))
                        Toast.makeText(context, "Конфиг общего сервера сохранён", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Сохранить адрес сервера") }
            }
        }
    }
}

@Composable
private fun UpdatesCategoryView() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var updateStatus by remember { mutableStateOf("Текущая версия: v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})") }
    var availableRelease by remember { mutableStateOf<ReleaseInfo?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AppSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Обновления с GitHub", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(updateStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (isDownloading) {
                    LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        enabled = !isChecking && !isDownloading,
                        onClick = {
                            isChecking = true
                            updateStatus = "Проверяю новые релизы..."
                            scope.launch {
                                UpdateManager.checkLatestRelease()
                                    .onSuccess { rel ->
                                        availableRelease = rel
                                        updateStatus = if (rel.isNewer) "Доступно обновление: ${rel.versionName}" else "Установлена последняя версия (${rel.versionName})"
                                    }
                                    .onFailure { err -> updateStatus = "Ошибка: ${err.message}" }
                                isChecking = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (isChecking) "Проверка..." else "Проверить") }

                    if (availableRelease?.isNewer == true) {
                        Button(
                            enabled = !isDownloading,
                            onClick = {
                                val rel = availableRelease ?: return@Button
                                isDownloading = true
                                downloadProgress = 0f
                                updateStatus = "Скачивание ${rel.versionName}..."
                                scope.launch {
                                    UpdateManager.downloadApk(context, rel.downloadUrl) { p -> downloadProgress = p }
                                        .onSuccess { file ->
                                            isDownloading = false
                                            updateStatus = "Скачано! Запускаю установку..."
                                            if (!UpdateManager.installApk(context, file)) {
                                                Toast.makeText(context, "Разреши установку для Volt Messenger и нажми снова", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                        .onFailure { err ->
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
    }
}
