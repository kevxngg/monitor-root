package com.kevxngg.monitorroot

import android.content.Context
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Estilos de interfaz. Cada uno define colores, redondez de esquinas, tipo de
 * letra y colores de las gráficas. La estructura de la app es la misma en todos.
 */
object UiStyles {
    const val WINDOWS_11 = 0
    const val WINDOWS_10 = 1
    const val MATERIAL_YOU = 2
    const val TERMINAL = 3

    val NAMES = listOf("Windows 11", "Windows 10", "Material You", "Terminal")

    /** El estilo Terminal siempre es oscuro. */
    fun forcesDark(style: Int) = style == TERMINAL

    fun palette(ctx: Context, style: Int, dark: Boolean, pureBlack: Boolean): Win {
        val base = when (style) {
            WINDOWS_10 -> if (dark) WIN10_DARK else WIN10_LIGHT
            MATERIAL_YOU -> materialYou(materialScheme(ctx, dark), dark)
            TERMINAL -> TERMINAL_PALETTE
            else -> if (dark) DarkWin else LightWin
        }
        return if (pureBlack && base.dark && style != TERMINAL) black(base) else base
    }

    /** Esquema de Material 3: con los colores del fondo de pantalla en Android 12 o superior. */
    fun materialScheme(ctx: Context, dark: Boolean): ColorScheme =
        if (Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        } else {
            if (dark) darkColorScheme() else lightColorScheme()
        }

    /** Negro puro (AMOLED): fondos en negro absoluto, bordes apenas visibles. */
    private fun black(w: Win) = w.copy(
        mica = Color.Black,
        content = Color.Black,
        card = Color(0xFF0B0B0B),
        hover = Color(0xFF1A1A1A),
        border = Color(0xFF262626),
        divider = Color(0xFF1C1C1C),
        heatLow = lerp(Color.Black, w.heatLow, 0.6f),
    )

    private fun materialYou(c: ColorScheme, dark: Boolean) = Win(
        dark = dark,
        mica = c.surfaceContainerLow,
        content = c.surface,
        card = c.surfaceContainer,
        text = c.onSurface,
        sub = c.onSurfaceVariant,
        border = c.outlineVariant,
        divider = c.outlineVariant.copy(alpha = 0.5f),
        hover = c.surfaceContainerHighest,
        select = c.secondaryContainer,
        accent = c.primary,
        heatLow = lerp(c.surface, c.primaryContainer, 0.35f),
        heatMid = c.primaryContainer,
        heatHigh = c.errorContainer,
        eco = c.tertiary,
        warn = if (dark) Color(0xFFFCE100) else Color(0xFFB3800A),
        radiusScale = 3f,
    )

    private val WIN10_LIGHT = Win(
        dark = false,
        mica = Color(0xFFF0F0F0),
        content = Color.White,
        card = Color.White,
        text = Color.Black,
        sub = Color(0xFF6D6D6D),
        border = Color(0xFFDADADA),
        divider = Color(0xFFE5E5E5),
        hover = Color(0xFFE5F3FF),
        select = Color(0xFFCCE8FF),
        accent = Color(0xFF0078D7),
        heatLow = Color(0xFFFFF4C4),
        heatMid = Color(0xFFFFD34F),
        heatHigh = Color(0xFFFF8C00),
        eco = Color(0xFF107C10),
        warn = Color(0xFFB3800A),
        radiusScale = 0f,
    )

    private val WIN10_DARK = Win(
        dark = true,
        mica = Color(0xFF191919),
        content = Color(0xFF1F1F1F),
        card = Color(0xFF2B2B2B),
        text = Color.White,
        sub = Color(0xFF9D9D9D),
        border = Color(0xFF3C3C3C),
        divider = Color(0xFF333333),
        hover = Color(0xFF2D3B47),
        select = Color(0xFF264F78),
        accent = Color(0xFF429CE3),
        heatLow = Color(0xFF2E2A16),
        heatMid = Color(0xFF6B5A12),
        heatHigh = Color(0xFF9A5B00),
        eco = Color(0xFF6CCB5F),
        warn = Color(0xFFFCE100),
        radiusScale = 0f,
    )

    // Inspirado en las herramientas de consola de Linux (htop, top)
    private val TERMINAL_PALETTE = Win(
        dark = true,
        mica = Color.Black,
        content = Color.Black,
        card = Color(0xFF040404),
        text = Color(0xFF3CFF6B),
        sub = Color(0xFF22A24A),
        border = Color(0xFF145A2A),
        divider = Color(0xFF0E3A1D),
        hover = Color(0xFF0B2A15),
        select = Color(0xFF11421F),
        accent = Color(0xFF3CFF6B),
        heatLow = Color(0xFF06170B),
        heatMid = Color(0xFF14532A),
        heatHigh = Color(0xFF7A1A1A),
        eco = Color(0xFF3CFF6B),
        warn = Color(0xFFFFD23F),
        radiusScale = 0f,
        mono = true,
        resColors = mapOf(
            "CPU" to Color(0xFF2CE6E6),
            "MEM" to Color(0xFFFF4FD8),
            "DISK" to Color(0xFFFFD23F),
            "WIFI" to Color(0xFF4F9BFF),
            "MOBILE" to Color(0xFFFF9F43),
            "GPU" to Color(0xFF3CFF6B),
            "TEMP" to Color(0xFFFF5555),
        ),
    )

    /** Toda la tipografía en letra monoespaciada (estilo Terminal). */
    fun monoTypography(): Typography {
        val t = Typography()
        val m = FontFamily.Monospace
        return t.copy(
            displayLarge = t.displayLarge.copy(fontFamily = m),
            displayMedium = t.displayMedium.copy(fontFamily = m),
            displaySmall = t.displaySmall.copy(fontFamily = m),
            headlineLarge = t.headlineLarge.copy(fontFamily = m),
            headlineMedium = t.headlineMedium.copy(fontFamily = m),
            headlineSmall = t.headlineSmall.copy(fontFamily = m),
            titleLarge = t.titleLarge.copy(fontFamily = m),
            titleMedium = t.titleMedium.copy(fontFamily = m),
            titleSmall = t.titleSmall.copy(fontFamily = m),
            bodyLarge = t.bodyLarge.copy(fontFamily = m),
            bodyMedium = t.bodyMedium.copy(fontFamily = m),
            bodySmall = t.bodySmall.copy(fontFamily = m),
            labelLarge = t.labelLarge.copy(fontFamily = m),
            labelMedium = t.labelMedium.copy(fontFamily = m),
            labelSmall = t.labelSmall.copy(fontFamily = m),
        )
    }
}

/** Esquinas que siguen el estilo de interfaz elegido. */
@Composable
@ReadOnlyComposable
fun corner(dp: Int): RoundedCornerShape = RoundedCornerShape((dp * LocalWin.current.radiusScale).dp)

@Composable
@ReadOnlyComposable
fun cornerTopStart(dp: Int): RoundedCornerShape = RoundedCornerShape(topStart = (dp * LocalWin.current.radiusScale).dp)
