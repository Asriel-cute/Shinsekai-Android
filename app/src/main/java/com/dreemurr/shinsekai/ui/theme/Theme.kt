package com.dreemurr.shinsekai.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = ShinsekaiAccent,
    onPrimary = Color(0xFF3B101E),
    primaryContainer = Color(0xFF552133),
    onPrimaryContainer = Color(0xFFFFD9E2),
    secondary = ShinsekaiPurple,
    onSecondary = Color(0xFF281347),
    secondaryContainer = Color(0xFF3F2662),
    onSecondaryContainer = Color(0xFFEADBFF),
    tertiary = ShinsekaiHighlight,
    onTertiary = Color(0xFF490B2F),
    tertiaryContainer = Color(0xFF671C45),
    onTertiaryContainer = Color(0xFFFFD8E9),
    background = ShinsekaiDarkAppBg,
    onBackground = ShinsekaiDarkTextPrimary,
    surface = ShinsekaiDarkPanel,
    onSurface = ShinsekaiDarkTextPrimary,
    surfaceVariant = ShinsekaiDarkSurface,
    onSurfaceVariant = ShinsekaiDarkTextMuted,
    outline = ShinsekaiDarkBorder,
    outlineVariant = ShinsekaiDarkBorderHover,
    error = ShinsekaiError,
    onError = Color(0xFF3E0A12),
)

private val LightColorScheme = lightColorScheme(
    primary = ShinsekaiAccent,
    onPrimary = Color(0xFF3B101E),
    primaryContainer = Color(0xFFFFD9E2),
    onPrimaryContainer = Color(0xFF3D0017),
    secondary = Color(0xFF7B56B9),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEADBFF),
    onSecondaryContainer = Color(0xFF25005A),
    tertiary = Color(0xFFB52B75),
    onTertiary = Color.White,
    background = ShinsekaiLightAppBg,
    onBackground = ShinsekaiLightTextPrimary,
    surface = ShinsekaiLightPanel,
    onSurface = ShinsekaiLightTextPrimary,
    surfaceVariant = Color(0xFFEBDDE5),
    onSurfaceVariant = ShinsekaiLightTextMuted,
    outline = ShinsekaiLightBorder,
    outlineVariant = Color(0xFFCEBAC7),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

@Composable
fun ShinsekaiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 默认关闭 Android 12+ 壁纸取色，锁定 Shinsekai 专属樱粉暗夜设计语言
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

