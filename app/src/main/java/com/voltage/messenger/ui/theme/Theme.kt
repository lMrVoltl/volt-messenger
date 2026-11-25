package com.voltage.messenger.ui.theme

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
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

enum class UiDesignStyle {
    MATERIAL_YOU,
    LIQUID_GLASS
}

val LocalDesignStyle = staticCompositionLocalOf { UiDesignStyle.MATERIAL_YOU }

private val FallbackDarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondaryContainer = Color(0xFF282A2F),
    surface = Color(0xFF111318),
    surfaceContainer = Color(0xFF1D2026)
)

private val FallbackLightColors = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondaryContainer = Color(0xFFE8EDF5),
    surface = Color(0xFFF8F9FC),
    surfaceContainer = Color(0xFFEDF1F7)
)

@Composable
fun VoltMessengerTheme(
    designStyle: UiDesignStyle = UiDesignStyle.MATERIAL_YOU,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> FallbackDarkColors
        else -> FallbackLightColors
    }

    CompositionLocalProvider(LocalDesignStyle provides designStyle) {
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
