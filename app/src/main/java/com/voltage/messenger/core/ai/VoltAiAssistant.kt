package com.voltage.messenger.core.ai

import com.voltage.messenger.model.AppThemeConfig

sealed interface AiAppAction {
    data class SetNickname(val nickname: String) : AiAppAction
    data class SetServerName(val serverName: String) : AiAppAction
    data class SetThemeMode(val mode: Int) : AiAppAction
    data class SetAccentPreset(val preset: Int) : AiAppAction
    data class CreateGroup(val name: String) : AiAppAction
    data class CreateChannel(val name: String) : AiAppAction
    data object OpenSettings : AiAppAction
}

data class AiParsedResult(
    val cleanText: String,
    val actions: List<AiAppAction>
)

object VoltAiAssistant {

    // Компактный системный промпт (~350 символов), чтобы не перегружать локальный ServerSocket и контекст модели
    fun buildSystemPrompt(
        basePrompt: String,
        nickname: String,
        serverName: String,
        themeConfig: AppThemeConfig,
        peersOnline: Int
    ): String {
        return buildString {
            append(basePrompt.take(180))
            append(" Ник: ").append(nickname)
            append(", сервер: ").append(serverName)
            append(", рядом: ").append(peersOnline).append(". ")
            append("Интерфейс: ☰ слева сверху = профиль/ник/сервер; шестерёнка справа = настройки/дизайн/обновления; карандаш внизу = группы/каналы/IP; короткий тап на соо = реакции/закрепить/ИИ; скрепка = файлы и опросы.")
        }
    }

    private val cmdRegex = Regex("""\[\[CMD:([A-Z_]+)(?:\|([^\]]+))?]]""")

    // Мгновенный перехват команд управления до обращения к модели
    fun detectDirectAction(userPrompt: String, controlEnabled: Boolean): AiAppAction? {
        if (!controlEnabled) return null
        val text = userPrompt.trim()
        val lower = text.lowercase()
        if (lower.startsWith("как ") || lower.startsWith("где ") || lower.contains("?")) return null

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

        if (Regex("""(?:палитр|цвет|акцент).*(индиго)""", RegexOption.IGNORE_CASE).containsMatchIn(lower)) return AiAppAction.SetAccentPreset(1)
        if (Regex("""(?:палитр|цвет|акцент).*(изумруд|зел[её]н)""", RegexOption.IGNORE_CASE).containsMatchIn(lower)) return AiAppAction.SetAccentPreset(2)
        if (Regex("""(?:палитр|цвет|акцент).*(аметист|фиолет)""", RegexOption.IGNORE_CASE).containsMatchIn(lower)) return AiAppAction.SetAccentPreset(3)
        if (Regex("""(?:палитр|цвет|акцент).*(закат|оранж)""", RegexOption.IGNORE_CASE).containsMatchIn(lower)) return AiAppAction.SetAccentPreset(4)
        if (Regex("""(?:палитр|цвет|акцент).*(материал|material)""", RegexOption.IGNORE_CASE).containsMatchIn(lower)) return AiAppAction.SetAccentPreset(0)

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

        if (lower == "открой настройки" || lower == "настройки") return AiAppAction.OpenSettings
        return null
    }

    fun parseResponse(rawText: String): AiParsedResult {
        return AiParsedResult(rawText.replace(cmdRegex, "").trim(), emptyList())
    }
}
