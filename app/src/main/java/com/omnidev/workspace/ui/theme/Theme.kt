package com.omnidev.workspace.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.omnidev.workspace.ui.motion.LocalOmniMotion
import com.omnidev.workspace.ui.motion.rememberMotionPolicy

private val DarkColorScheme = darkColorScheme(
    primary = OmniPrimary,
    secondary = OmniSecondary,
    tertiary = OmniTertiary,
    background = OmniBackgroundDark,
    surface = OmniSurfaceDark,
    onBackground = OmniOnSurfaceDark,
    onSurface = OmniOnSurfaceDark,
    error = OmniError
)

private val LightColorScheme = lightColorScheme(
    primary = OmniPrimary,
    secondary = OmniSecondary,
    tertiary = OmniTertiary,
    background = OmniBackground,
    surface = OmniSurface,
    onBackground = OmniOnSurface,
    onSurface = OmniOnSurface,
    error = OmniError
)

@Composable
fun OmniDevTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val motionPolicy = rememberMotionPolicy()
    val colorScheme = remember(context, darkTheme, dynamicColor) {
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }
    }

    CompositionLocalProvider(LocalOmniMotion provides motionPolicy) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = OmniShapes,
            content = content
        )
    }
}
