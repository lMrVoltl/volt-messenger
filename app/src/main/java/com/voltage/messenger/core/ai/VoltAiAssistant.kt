package com.voltage.messenger.core.ai

import com.voltage.messenger.model.AppThemeConfig

sealed interface AiAppAction {
    data class SetNickname(val nickname: String) : AiAppAction
    data class SetServerName(val serverName: String) : AiAppAction
    data class SetThemeMode(val mode: Int) : AiAppAction // 0=system, 1=light, 2=dark
    data class SetAccentPreset(val preset: Int) : AiAppAction // 0..4
    data class CreateGroup(val name: String) : AiAppAction
    data class CreateChannel(val name: String) : AiAppAction
    data object OpenSettings : AiAppAction
}

data class AiParsedResult(
    val cleanText: String,
    val actions: List<AiAppAction>
)

object VoltAiAssistant {

    fun buildSystemPrompt(
        basePrompt: String,
        nickname: String,
        serverName: String,
        themeConfig: AppThemeConfig,
        peersOnline: Int
    ): String {
        val themeName = when (themeConfig.themeMode) {
            1 -> "Светлая"
            2 -> "Тёмная"
            else -> "Системная"
        }
        val accentName = listOf("Material You", "Индиго", "Изумруд", "Аметист", "Закат")
            .getOrElse(themeConfig.accentPreset) { "Material You" }

        val commandsBlock = if (themeConfig.aiControlEnabled) {
            """
УПРАВЛЕНИЕ ПРИЛОЖЕНИЕМ:
Если пользователь просит изменить настройку или создать чат/канал, ОБЯЗАТЕЛЬНО добавь в конец ответа нужную команду в двойных квадратных скобках (пользователь её не увидит, приложение выполнит её само):
- Сменить ник: [[CMD:SET_NICK|НовоеИмя]]
- Сменить имя сервера: [[CMD:SET_SERVER|НовоеИмяСервера]]
- Сменить тему: [[CMD:SET_THEME|system]] или [[CMD:SET_THEME|light]] или [[CMD:SET_THEME|dark]]
- Сменить палитру (0=Material You, 1=Индиго, 2=Изумруд, 3=Аметист, 4=Закат): [[CMD:SET_ACCENT|2]]
- Создать группу: [[CMD:CREATE_GROUP|Название]]
- Создать канал: [[CMD:CREATE_CHANNEL|Название]]
- Открыть настройки: [[CMD:OPEN_SETTINGS]]
Пример: Пользователь пишет «Поменяй ник на Макс». Твой ответ: «Готово, изменил твой ник на Макс! [[CMD:SET_NICK|Макс]]»
            """.trimIndent()
        } else {
            "Управление приложением через ИИ сейчас выключено в Настройках → Локальный ИИ."
        }

        return """
$basePrompt

ТЕКУЩЕЕ СОСТОЯНИЕ VOLT MESSENGER:
- Ник пользователя: $nickname
- Название локального сервера: $serverName
- Тема: $themeName, палитра: $accentName, скругление пузырей: ${themeConfig.bubbleRadius}dp
- Устройств рядом в сети: $peersOnline

ТОЧНАЯ КАРТА ИНТЕРФЕЙСА (отвечай СТРОГО по ней, ничего не выдумывай!):
1. Изменить ник или название локального сервера: на главном экране нажми на иконку меню (три полоски ☰) в ЛЕВОМ верхнем углу, ИЛИ нажми на шестерёнку (Настройки) в правом верхнем углу → «Профиль и узел».
2. Настройки дизайна, ИИ, общего сервера и обновлений: иконка шестерёнки в правом верхнем углу главного экрана.
3. Создать группу, канал или подключиться по IP вручную: кнопка карандаша в правом нижнем углу главного экрана.
4. Поставить реакцию, ответить, закрепить или удалить сообщение: сделай КОРОТКИЙ ТАП по нужному сообщению в чате.
5. Закрепить или открепить чат в списке: зажми (долгое нажатие) чат на главном экране.
6. Отправить файл, фото или модель .gguf: нажми скрепку слева от поля ввода. Модели .gguf сами сохраняются в папку VoltModels.
7. Голосовые сообщения: нажми кнопку микрофона справа от поля ввода. Аудиозвонок — кнопка трубки в шапке локального чата.
8. Обновление приложения: Настройки → Обновления → Проверить → Обновить.

$commandsBlock
        """.trimIndent()
    }

    private val cmdRegex = Regex("""\[\[CMD:([A-Z_]+)(?:\|([^\]]+))?]]""")

    fun parseResponse(rawText: String, userPrompt: String, controlEnabled: Boolean): AiParsedResult {
        if (!controlEnabled) {
            return AiParsedResult(rawText.replace(cmdRegex, "").trim(), emptyList())
        }

        val actions = mutableListOf<AiAppAction>()
        cmdRegex.findAll(rawText).forEach { match ->
            val cmd = match.groupValues[1].trim()
            val arg = match.groupValues.getOrNull(2)?.trim().orEmpty()
            when (cmd) {
                "SET_NICK" -> if (arg.isNotEmpty()) actions.add(AiAppAction.SetNickname(arg))
                "SET_SERVER" -> if (arg.isNotEmpty()) actions.add(AiAppAction.SetServerName(arg))
                "SET_THEME" -> when (arg.lowercase()) {
                    "system", "системная", "0" -> actions.add(AiAppAction.SetThemeMode(0))
                    "light", "светлая", "1" -> actions.add(AiAppAction.SetThemeMode(1))
                    "dark", "тёмная", "темная", "2" -> actions.add(AiAppAction.SetThemeMode(2))
                }
                "SET_ACCENT" -> {
                    val idx = arg.toIntOrNull() ?: when (arg.lowercase()) {
                        "material", "material you" -> 0
                        "индиго", "indigo" -> 1
                        "изумруд", "emerald", "зелёный", "зеленый" -> 2
                        "аметист", "фиолетовый", "amethyst" -> 3
                        "закат", "оранжевый", "sunset" -> 4
                        else -> -1
                    }
                    if (idx in 0..4) actions.add(AiAppAction.SetAccentPreset(idx))
                }
                "CREATE_GROUP" -> if (arg.isNotEmpty()) actions.add(AiAppAction.CreateGroup(arg))
                "CREATE_CHANNEL" -> if (arg.isNotEmpty()) actions.add(AiAppAction.CreateChannel(arg))
                "OPEN_SETTINGS" -> actions.add(AiAppAction.OpenSettings)
            }
        }

        // Страховка на случай, если маленькая локальная модель забыла написать тег [[CMD:...]]
        if (actions.isEmpty()) {
            detectFallbackAction(userPrompt)?.let { actions.add(it) }
        }

        val clean = rawText.replace(cmdRegex, "").trim()
        return AiParsedResult(clean, actions)
    }

    private fun detectFallbackAction(userPrompt: String): AiAppAction? {
        val text = userPrompt.trim()
        val lower = text.lowercase()
        if (lower.startsWith("как ") || lower.contains("?")) return null

        val nickMatch = Regex("""(?:измени|поменяй|смени|поставь|сделай)\s+(?:мне\s+)?(?:мой\s+)?(?:ник|имя)\s+(?:на\s+)?(.+)""", RegexOption.IGNORE_CASE).find(text)
        if (nickMatch != null) {
            val v = nickMatch.groupValues[1].trim().trim('"', '«', '»', '.', '!')
            if (v.isNotEmpty()) return AiAppAction.SetNickname(v.take(24))
        }

        val srvMatch = Regex("""(?:измени|поменяй|смени|назови)\s+(?:название\s+|имя\s+)?сервер[а]?\s+(?:на\s+)?(.+)""", RegexOption.IGNORE_CASE).find(text)
        if (srvMatch != null) {
            val v = srvMatch.groupValues[1].trim().trim('"', '«', '»', '.', '!')
            if (v.isNotEmpty()) return AiAppAction.SetServerName(v.take(24))
        }

        if (Regex("""(?:включи|поставь|смени|измени).*(светл\w+\s+тем)""", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            return AiAppAction.SetThemeMode(1)
        }
        if (Regex("""(?:включи|поставь|смени|измени).*(т[её]мн\w+\s+тем)""", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            return AiAppAction.SetThemeMode(2)
        }
        if (Regex("""(?:включи|поставь|смени|измени).*(системн\w+\s+тем)""", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            return AiAppAction.SetThemeMode(0)
        }

        val grpMatch = Regex("""создай\s+(?:локальную\s+)?группу\s+(.+)""", RegexOption.IGNORE_CASE).find(text)
        if (grpMatch != null) {
            val v = grpMatch.groupValues[1].trim().trim('"', '«', '»', '.', '!')
            if (v.isNotEmpty()) return AiAppAction.CreateGroup(v.take(28))
        }

        val chMatch = Regex("""создай\s+(?:локальный\s+)?канал\s+(.+)""", RegexOption.IGNORE_CASE).find(text)
        if (chMatch != null) {
            val v = chMatch.groupValues[1].trim().trim('"', '«', '»', '.', '!')
            if (v.isNotEmpty()) return AiAppAction.CreateChannel(v.take(28))
        }

        if (lower == "открой настройки" || lower == "настройки") {
            return AiAppAction.OpenSettings
        }

        return null
    }
}
