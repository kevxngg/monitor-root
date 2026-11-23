package com.kevxngg.monitorroot

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/** App palette: Windows 11 style in light, dark or pure black (AMOLED). */
object UiStyles {
    const val WINDOWS = 0
    const val MACOS = 1
    val NAMES = listOf("Windows 11", "macOS")

    fun palette(style: Int, dark: Boolean, pureBlack: Boolean): Win = when {
        style == MACOS -> if (!dark) MAC_LIGHT else if (pureBlack) black(MAC_DARK) else MAC_DARK
        pureBlack -> black(DarkWin)
        dark -> DarkWin
        else -> LightWin
    }

    private val MAC_LIGHT = LightWin.copy(
        mac = true, radiusScale = 1f,
        mica = Color(0xFFECECEC),
        content = Color(0xFFFFFFFF),
        card = Color(0xFFF6F6F6),
        text = Color(0xFF1D1D1F),
        sub = Color(0xFF636366),   // 8A8A8E was below 4.5:1 on the grey backgrounds
        border = Color(0xFFD6D6D6),
        divider = Color(0xFFE8E8E8),
        hover = Color(0xFFE4E4E6),        // gray of the active tab/button (as in the cutout)
        select = Color(0xFF0A66FF),      // blue of the selected row
        accent = Color(0xFF0A66FF),
    )
    private val MAC_DARK = DarkWin.copy(
        mac = true, radiusScale = 1f,
        mica = Color(0xFF232323),
        content = Color(0xFF1E1E1E),
        card = Color(0xFF282828),
        text = Color(0xFFF2F2F2),
        sub = Color(0xFF9A9A9E),
        border = Color(0xFF3A3A3A),
        divider = Color(0xFF333333),
        hover = Color(0xFF3A3A3C),        // gray of the active tab/button
        select = Color(0xFF0A66FF),      // blue of the selected row
        accent = Color(0xFF3B82F6),
    )

    /** Pure black: absolute-black backgrounds, barely visible borders. */
    private fun black(w: Win) = w.copy(
        mica = Color.Black,
        content = Color.Black,
        card = Color(0xFF0B0B0B),
        hover = Color(0xFF1A1A1A),
        border = Color(0xFF262626),
        divider = Color(0xFF1C1C1C),
        heatLow = lerp(Color.Black, w.heatLow, 0.6f),
    )
}

/** App corners (the roundness comes from the palette). */
@Composable
@ReadOnlyComposable
fun corner(dp: Int): RoundedCornerShape = RoundedCornerShape((dp * LocalWin.current.radiusScale).dp)

@Composable
@ReadOnlyComposable
fun cornerTopStart(dp: Int): RoundedCornerShape = RoundedCornerShape(topStart = (dp * LocalWin.current.radiusScale).dp)

// macOS-style typography: Inter, very similar to SF Pro and freely licensed (SIL).
private val InterFamily = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(R.font.inter),
)

@androidx.compose.runtime.Composable
fun macTypography(): androidx.compose.material3.Typography {
    val t = androidx.compose.material3.Typography()
    fun androidx.compose.ui.text.TextStyle.f() = copy(fontFamily = InterFamily)
    return t.copy(
        displayLarge = t.displayLarge.f(), displayMedium = t.displayMedium.f(), displaySmall = t.displaySmall.f(),
        headlineLarge = t.headlineLarge.f(), headlineMedium = t.headlineMedium.f(), headlineSmall = t.headlineSmall.f(),
        titleLarge = t.titleLarge.f(), titleMedium = t.titleMedium.f(), titleSmall = t.titleSmall.f(),
        bodyLarge = t.bodyLarge.f(), bodyMedium = t.bodyMedium.f(), bodySmall = t.bodySmall.f(),
        labelLarge = t.labelLarge.f(), labelMedium = t.labelMedium.f(), labelSmall = t.labelSmall.f(),
    )
}
