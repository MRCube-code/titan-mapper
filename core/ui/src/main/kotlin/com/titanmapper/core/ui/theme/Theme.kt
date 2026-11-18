package com.titanmapper.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.titanmapper.core.model.ProfileColor
import com.titanmapper.core.model.ThemeConfig
import com.titanmapper.core.model.ThemeMode

enum class DensityMode { COMFORTABLE, COMPACT }

val LocalDensityMode = staticCompositionLocalOf { DensityMode.COMFORTABLE }

/** Spacing scale that tightens in [DensityMode.COMPACT] (pointer-driven, desktop-like layouts). */
object TitanSpacing {
    val xs: Dp @Composable @ReadOnlyComposable get() = scaled(4)
    val s: Dp @Composable @ReadOnlyComposable get() = scaled(8)
    val m: Dp @Composable @ReadOnlyComposable get() = scaled(12)
    val l: Dp @Composable @ReadOnlyComposable get() = scaled(16)
    val xl: Dp @Composable @ReadOnlyComposable get() = scaled(24)

    @Composable
    @ReadOnlyComposable
    private fun scaled(base: Int): Dp =
        if (LocalDensityMode.current == DensityMode.COMPACT) (base * 0.75f).dp else base.dp
}

fun ProfileColor.toColor(): Color = when (this) {
    ProfileColor.BLUE -> Color(0xFF3B82F6)
    ProfileColor.GREEN -> Color(0xFF22C55E)
    ProfileColor.RED -> Color(0xFFEF4444)
    ProfileColor.PURPLE -> Color(0xFF8B5CF6)
    ProfileColor.ORANGE -> Color(0xFFF97316)
    ProfileColor.TEAL -> Color(0xFF14B8A6)
    ProfileColor.PINK -> Color(0xFFEC4899)
    ProfileColor.GRAY -> Color(0xFF6B7280)
}

private val TitanShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

private val TitanTypography: Typography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium)
    )
}

private fun schemeFor(accent: Color, dark: Boolean): ColorScheme =
    if (dark) {
        val base = Color(0xFF0F1115)
        darkColorScheme(
            primary = lerp(accent, Color.White, 0.25f),
            onPrimary = Color(0xFF0B1020),
            primaryContainer = lerp(accent, base, 0.6f),
            onPrimaryContainer = lerp(accent, Color.White, 0.85f),
            secondary = lerp(accent, Color(0xFF9CA3AF), 0.65f),
            onSecondary = Color(0xFF0B1020),
            secondaryContainer = Color(0xFF262C37),
            onSecondaryContainer = Color(0xFFDDE2EA),
            background = base,
            onBackground = Color(0xFFE6E8EC),
            surface = Color(0xFF14171D),
            onSurface = Color(0xFFE6E8EC),
            surfaceVariant = Color(0xFF1E232C),
            onSurfaceVariant = Color(0xFFA7AFBC),
            surfaceContainerLowest = base,
            surfaceContainerLow = Color(0xFF161A20),
            surfaceContainer = Color(0xFF181C23),
            surfaceContainerHigh = Color(0xFF1E232C),
            surfaceContainerHighest = Color(0xFF252B35),
            outline = Color(0xFF3A414D),
            outlineVariant = Color(0xFF2A303A),
            error = Color(0xFFF87171),
            onError = Color(0xFF3B0A0A),
            errorContainer = Color(0xFF5A1C1C),
            onErrorContainer = Color(0xFFFFDAD6)
        )
    } else {
        lightColorScheme(
            primary = lerp(accent, Color.Black, 0.12f),
            onPrimary = Color.White,
            primaryContainer = lerp(accent, Color.White, 0.82f),
            onPrimaryContainer = lerp(accent, Color.Black, 0.6f),
            secondary = lerp(accent, Color(0xFF4B5563), 0.65f),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE3E7EE),
            onSecondaryContainer = Color(0xFF1B2130),
            background = Color(0xFFF5F6F8),
            onBackground = Color(0xFF16181D),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF16181D),
            surfaceVariant = Color(0xFFE8EBF0),
            onSurfaceVariant = Color(0xFF4A5160),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF8F9FB),
            surfaceContainer = Color(0xFFF1F3F6),
            surfaceContainerHigh = Color(0xFFEBEEF3),
            surfaceContainerHighest = Color(0xFFE5E8EE),
            outline = Color(0xFF9AA2B1),
            outlineVariant = Color(0xFFD3D8E0),
            error = Color(0xFFB3261E),
            onError = Color.White,
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B)
        )
    }

/**
 * App theme. Dynamic (Material You) colors are used on Android 12+ when requested; otherwise a
 * desktop-style neutral palette tinted with [ThemeConfig.accent]. [compact] shrinks spacing and
 * the minimum touch target for mouse-driven use.
 */
@Composable
fun TitanTheme(
    config: ThemeConfig = ThemeConfig(),
    compact: Boolean = false,
    content: @Composable () -> Unit
) {
    val dark = when (config.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = if (config.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        schemeFor(config.accent.toColor(), dark)
    }
    CompositionLocalProvider(
        LocalDensityMode provides if (compact) DensityMode.COMPACT else DensityMode.COMFORTABLE,
        LocalMinimumInteractiveComponentSize provides if (compact) 32.dp else 48.dp
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = TitanTypography,
            shapes = TitanShapes,
            content = content
        )
    }
}
