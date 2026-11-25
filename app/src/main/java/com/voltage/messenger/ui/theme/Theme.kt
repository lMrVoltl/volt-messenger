package com.voltage.messenger.ui.theme

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.voltage.messenger.model.AppThemeConfig

enum class UiDesignStyle {
    MATERIAL_YOU,
    LIQUID_GLASS
}

val LocalDesignStyle = staticCompositionLocalOf { UiDesignStyle.MATERIAL_YOU }
val LocalThemeConfig = staticCompositionLocalOf { AppThemeConfig() }

private fun presetColorScheme(preset: Int, dark: Boolean): ColorScheme = when (preset) {
    2 -> if (dark) darkColorScheme(
        primary = Color(0xFF6EE7B7), onPrimary = Color(0xFF003826),
        primaryContainer = Color(0xFF065F46), onPrimaryContainer = Color(0xFFD1FAE5),
        secondaryContainer = Color(0xFF1F2F2A), surface = Color(0xFF0E1412), surfaceContainer = Color(0xFF19221F)
    ) else lightColorScheme(
        primary = Color(0xFF059669), onPrimary = Color.White,
        primaryContainer = Color(0xFFD1FAE5), onPrimaryContainer = Color(0xFF022C22),
        secondaryContainer = Color(0xFFE6F4EE), surface = Color(0xFFF6FBF9), surfaceContainer = Color(0xFFEAF3EF)
    )

    3 -> if (dark) darkColorScheme(
        primary = Color(0xFFD8B4FE), onPrimary = Color(0xFF3B0764),
        primaryContainer = Color(0xFF581C87), onPrimaryContainer = Color(0xFFF3E8FF),
        secondaryContainer = Color(0xFF2B2233), surface = Color(0xFF131017), surfaceContainer = Color(0xFF1E1924)
    ) else lightColorScheme(
        primary = Color(0xFF7C3AED), onPrimary = Color.White,
        primaryContainer = Color(0xFFEDE9FE), onPrimaryContainer = Color(0xFF2E1065),
        secondaryContainer = Color(0xFFF3EFFB), surface = Color(0xFFFAF8FE), surfaceContainer = Color(0xFFF0EBF9)
    )

    4 -> if (dark) darkColorScheme(
        primary = Color(0xFFFDBA74), onPrimary = Color(0xFF431407),
        primaryContainer = Color(0xFF9A3412), onPrimaryContainer = Color(0xFFFFEDD5),
        secondaryContainer = Color(0xFF31241E), surface = Color(0xFF16110F), surfaceContainer = Color(0xFF221B18)
    ) else lightColorScheme(
        primary = Color(0xFFEA580C), onPrimary = Color.White,
        primaryContainer = Color(0xFFFFEDD5), onPrimaryContainer = Color(0xFF431407),
        secondaryContainer = Color(0xFFFBEFE8), surface = Color(0xFFFFFAF7), surfaceContainer = Color(0xFFF7EEE8)
    )

    else -> if (dark) darkColorScheme(
        primary = Color(0xFFA8C7FA), onPrimary = Color(0xFF062E6F),
        primaryContainer = Color(0xFF1E3A8A), onPrimaryContainer = Color(0xFFD3E3FD),
        secondaryContainer = Color(0xFF232834), surface = Color(0xFF101319), surfaceContainer = Color(0xFF1B202A)
    ) else lightColorScheme(
        primary = Color(0xFF2563EB), onPrimary = Color.White,
        primaryContainer = Color(0xFFDBEAFE), onPrimaryContainer = Color(0xFF172554),
        secondaryContainer = Color(0xFFE8EEF7), surface = Color(0xFFF8FAFE), surfaceContainer = Color(0xFFEDF2FA)
    )
}

@Composable
fun VoltMessengerTheme(
    config: AppThemeConfig = AppThemeConfig(),
    designStyle: UiDesignStyle = UiDesignStyle.MATERIAL_YOU,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = when (config.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }

    val colorScheme = if (config.accentPreset == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        presetColorScheme(config.accentPreset, darkTheme)
    }

    CompositionLocalProvider(
        LocalDesignStyle provides designStyle,
        LocalThemeConfig provides config
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}

@Composable
fun AppSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tonalElevation: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    val style = LocalDesignStyle.current
    val border = if (style == UiDesignStyle.LIQUID_GLASS) {
        BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
    } else null

    Surface(
        modifier = modifier,
        shape = shape,
        color = if (style == UiDesignStyle.LIQUID_GLASS) color.copy(alpha = 0.55f) else color,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        border = border,
        content = content
    )
}
