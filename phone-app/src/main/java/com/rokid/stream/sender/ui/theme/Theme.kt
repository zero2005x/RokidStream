package com.rokid.stream.sender.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Rokid brand colors
val RokidBlue = Color(0xFF2196F3)
val RokidBlueLight = Color(0xFF64B5F6)
val RokidBlueDark = Color(0xFF1976D2)
val RokidPurple = Color(0xFF7C4DFF)
val RokidGreen = Color(0xFF4CAF50)
val RokidOrange = Color(0xFFFF9800)

// Light Theme Colors
private val LightColorScheme = lightColorScheme(
    primary = RokidBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = RokidPurple,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8DDFF),
    onSecondaryContainer = Color(0xFF22005D),
    tertiary = RokidGreen,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB8F5B8),
    onTertiaryContainer = Color(0xFF002204),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8FAFF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8FAFF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C6CF)
)

// Dark Theme Colors
private val DarkColorScheme = darkColorScheme(
    primary = RokidBlueLight,
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF004880),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFCDBDFF),
    onSecondary = Color(0xFF3A0093),
    secondaryContainer = Color(0xFF5300CD),
    onSecondaryContainer = Color(0xFFE8DDFF),
    tertiary = Color(0xFF8DD88D),
    onTertiary = Color(0xFF003909),
    tertiaryContainer = Color(0xFF005313),
    onTertiaryContainer = Color(0xFFA8F4A8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E)
)

@Composable
fun RokidStreamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
