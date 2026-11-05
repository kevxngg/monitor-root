package com.kevxngg.monitorroot

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/** Paleta de la app: estilo Windows 11 en claro, oscuro o negro puro (AMOLED). */
object UiStyles {
    fun palette(dark: Boolean, pureBlack: Boolean): Win = when {
        pureBlack -> black(DarkWin)
        dark -> DarkWin
        else -> LightWin
    }

    /** Negro puro: fondos en negro absoluto, bordes apenas visibles. */
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

/** Esquinas de la app (la redondez sale de la paleta). */
@Composable
@ReadOnlyComposable
fun corner(dp: Int): RoundedCornerShape = RoundedCornerShape((dp * LocalWin.current.radiusScale).dp)

@Composable
@ReadOnlyComposable
fun cornerTopStart(dp: Int): RoundedCornerShape = RoundedCornerShape(topStart = (dp * LocalWin.current.radiusScale).dp)
