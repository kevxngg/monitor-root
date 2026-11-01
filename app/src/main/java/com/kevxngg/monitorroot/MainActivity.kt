package com.kevxngg.monitorroot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

const val HISTORY = 60          // 60 puntos = 60 segundos
const val REFRESH_MS = 1000L    // se actualiza cada segundo

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val win = if (isSystemInDarkTheme()) DarkWin else LightWin
            val base = if (win.dark) darkColorScheme() else lightColorScheme()
            val scheme = base.copy(
                background = win.bg,
                surface = win.bg,
                onBackground = win.text,
                onSurface = win.text,
                primary = Res.CPU.color(win),
            )
            CompositionLocalProvider(LocalWin provides win) {
                MaterialTheme(colorScheme = scheme) {
                    Surface(Modifier.fillMaxSize(), color = win.bg) { App() }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) RootShell.close()
    }
}

/** Últimos 60 valores de cada gráfica. */
data class History(
    val cpu: List<Float> = emptyList(),
    val mem: List<Float> = emptyList(),
    val gpu: List<Float> = emptyList(),
    val rx: List<Float> = emptyList(),
    val tx: List<Float> = emptyList(),
    val temp: List<Float> = emptyList(),
) {
    fun add(s: Snapshot): History {
        fun List<Float>.push(v: Float) = (this + v).takeLast(HISTORY)
        val memPct = if (s.memTotalKb > 0) (s.memTotalKb - s.memAvailKb) * 100f / s.memTotalKb else 0f
        return History(
            cpu = cpu.push(s.cpuTotal),
            mem = mem.push(memPct),
            gpu = gpu.push((s.gpuLoad ?: 0).toFloat()),
            rx = rx.push(s.wifiRxBps * 8f),
            tx = tx.push(s.wifiTxBps * 8f),
            temp = temp.push(s.thermals.firstOrNull()?.celsius ?: 0f),
        )
    }
}

@Composable
fun App() {
    var hasRoot by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { hasRoot = withContext(Dispatchers.IO) { RootShell.open() } }
    when (hasRoot) {
        null -> CenterText("Pidiendo acceso root…\nAcepta el aviso de Magisk / KernelSU.")
        false -> CenterText("No se obtuvo acceso root.\nDale permiso a la app en Magisk / KernelSU y vuelve a abrirla.")
        else -> Monitor()
    }
}

@Composable
fun CenterText(text: String) {
    val win = LocalWin.current
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = win.text, fontSize = 14.sp)
    }
}

@Composable
fun Monitor() {
    val context = LocalContext.current
    val reader = remember { SystemReader(context.applicationContext) }
    var snap by remember { mutableStateOf<Snapshot?>(null) }
    var hist by remember { mutableStateOf(History()) }

    // Solo actualiza mientras la app está en pantalla
    LaunchedEffect(Unit) {
        (context as LifecycleOwner).repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val s = withContext(Dispatchers.IO) { reader.sample() }
                snap = s
                hist = hist.add(s)
                delay(REFRESH_MS)
            }
        }
    }

    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        WinTabs(listOf("Procesos", "Rendimiento", "Detalles"), tab) { tab = it }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val s = snap
            if (s == null) {
                CenterText("Leyendo datos…")
            } else {
                when (tab) {
                    0 -> ProcessesScreen(s)
                    1 -> PerformanceScreen(s, hist)
                    else -> DetailsScreen(s)
                }
            }
        }
    }
}
