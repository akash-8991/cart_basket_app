package com.smartcart.designsystem

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Design tokens (frozen at frontend-freeze-v1.4). Colours meet WCAG AA contrast for text on
 * their paired surfaces in both themes. Provider identity is shown with neutral monograms, never
 * partner logos or brand colours.
 */
object Tokens {
    // Brand
    val Teal10 = Color(0xFF00201A)
    val Teal30 = Color(0xFF00513F)
    val Teal40 = Color(0xFF006B54)
    val Teal80 = Color(0xFF6FD8B7)
    val Teal90 = Color(0xFF8CF5D2)
    val Amber40 = Color(0xFF7A5900)
    val Amber90 = Color(0xFFFFDEA6)
    val Red40 = Color(0xFFBA1A1A)
    val Red80 = Color(0xFFFFB4AB)
    val Neutral10 = Color(0xFF191C1B)
    val Neutral95 = Color(0xFFF0F4F1)
    val Neutral99 = Color(0xFFFAFDFA)
}

@Immutable
data class SemanticColors(
    val success: Color,
    val onSuccessContainer: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val travel: Color,
    val food: Color,
    val grocery: Color,
    val shopping: Color,
)

private val LightSemantic = SemanticColors(
    success = Color(0xFF1B6C3A), successContainer = Color(0xFFD3F5D9), onSuccessContainer = Color(0xFF00210C),
    warning = Color(0xFF7A5900), warningContainer = Color(0xFFFFE8B8), onWarningContainer = Color(0xFF261900),
    danger = Color(0xFFBA1A1A), dangerContainer = Color(0xFFFFDAD6), onDangerContainer = Color(0xFF410002),
    info = Color(0xFF235FA6), infoContainer = Color(0xFFD6E3FF), onInfoContainer = Color(0xFF001B3E),
    travel = Color(0xFF235FA6), food = Color(0xFFA0410D), grocery = Color(0xFF1B6C3A), shopping = Color(0xFF6B4FA0),
)

private val DarkSemantic = SemanticColors(
    success = Color(0xFF8CD89C), successContainer = Color(0xFF00522A), onSuccessContainer = Color(0xFFD3F5D9),
    warning = Color(0xFFF2C14E), warningContainer = Color(0xFF5C4200), onWarningContainer = Color(0xFFFFE8B8),
    danger = Color(0xFFFFB4AB), dangerContainer = Color(0xFF93000A), onDangerContainer = Color(0xFFFFDAD6),
    info = Color(0xFFA9C7FF), infoContainer = Color(0xFF00468A), onInfoContainer = Color(0xFFD6E3FF),
    travel = Color(0xFFA9C7FF), food = Color(0xFFFFB68F), grocery = Color(0xFF8CD89C), shopping = Color(0xFFD3BBFF),
)

private val LightColors = lightColorScheme(
    primary = Tokens.Teal40, onPrimary = Color.White, primaryContainer = Tokens.Teal90, onPrimaryContainer = Tokens.Teal10,
    secondary = Color(0xFF4B635A), secondaryContainer = Color(0xFFCDE9DC), onSecondaryContainer = Color(0xFF072018),
    tertiary = Tokens.Amber40, tertiaryContainer = Tokens.Amber90, onTertiaryContainer = Color(0xFF261900),
    error = Tokens.Red40, background = Tokens.Neutral99, onBackground = Tokens.Neutral10,
    surface = Tokens.Neutral99, onSurface = Tokens.Neutral10, surfaceVariant = Color(0xFFDBE5DF), onSurfaceVariant = Color(0xFF3F4945),
    surfaceContainerLow = Tokens.Neutral95, surfaceContainer = Color(0xFFEAEFEB), surfaceContainerHigh = Color(0xFFE4E9E5),
    outline = Color(0xFF6F7975), outlineVariant = Color(0xFFBFC9C3),
)

private val DarkColors = darkColorScheme(
    primary = Tokens.Teal80, onPrimary = Color(0xFF00382B), primaryContainer = Tokens.Teal30, onPrimaryContainer = Tokens.Teal90,
    secondary = Color(0xFFB1CCC0), secondaryContainer = Color(0xFF334B42), onSecondaryContainer = Color(0xFFCDE9DC),
    tertiary = Color(0xFFF2C14E), tertiaryContainer = Color(0xFF5C4200), onTertiaryContainer = Tokens.Amber90,
    error = Tokens.Red80, background = Color(0xFF0F1513), onBackground = Color(0xFFDEE4E0),
    surface = Color(0xFF0F1513), onSurface = Color(0xFFDEE4E0), surfaceVariant = Color(0xFF3F4945), onSurfaceVariant = Color(0xFFBFC9C3),
    surfaceContainerLow = Color(0xFF171D1B), surfaceContainer = Color(0xFF1B211F), surfaceContainerHigh = Color(0xFF252B29),
    outline = Color(0xFF89938E), outlineVariant = Color(0xFF3F4945),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** Minimum interactive size (accessibility). */
    val touchTarget = 48.dp
}

@Immutable
data class Motion(val reduced: Boolean) {
    val shortMs: Int get() = if (reduced) 0 else 150
    val mediumMs: Int get() = if (reduced) 0 else 250
}

val LocalSemanticColors = staticCompositionLocalOf { LightSemantic }
val LocalMotion = staticCompositionLocalOf { Motion(reduced = false) }

object SmartCartTheme {
    val semantic: SemanticColors @Composable get() = LocalSemanticColors.current
    val motion: Motion @Composable get() = LocalMotion.current
}

@Composable
fun SmartCartTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Respect the system "Remove animations" setting.
    val reduced = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
    CompositionLocalProvider(
        LocalSemanticColors provides if (darkTheme) DarkSemantic else LightSemantic,
        LocalMotion provides Motion(reduced),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
