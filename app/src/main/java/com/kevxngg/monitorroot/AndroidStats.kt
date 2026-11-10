package com.kevxngg.monitorroot

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import kotlin.math.abs

/**
 * Caminos alternativos para leer datos cuando Android bloquea los archivos de /sys
 * (pasa con Shizuku, que funciona como el usuario "shell"):
 *  - batería: la API de Android, que cualquier app puede usar sin permisos
 *  - temperaturas: el servicio de temperaturas del sistema (dumpsys thermalservice)
 * Con root no se usan: /sys da más sensores y más precisión.
 */
object AndroidStats {
    class Battery(
        val level: Int?, val tempC: Float?, val voltageMv: Int?,
        val currentMa: Int?, val status: String?, val health: String?,
    )

    fun battery(ctx: Context): Battery {
        val i = ctx.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return Battery(null, null, null, null, null, null)
        val raw = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val status = when (st) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Cargando"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Descargando"
            BatteryManager.BATTERY_STATUS_FULL -> "Completa"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "No se está cargando"
            else -> null
        }
        val health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Buena"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Sobrecalentada"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Agotada"
            BatteryManager.BATTERY_HEALTH_COLD -> "Fría"
            else -> null
        }
        // Corriente en µA; el signo depende del fabricante: se normaliza (+ carga, − descarga)
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val microA = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeIf { it != Int.MIN_VALUE && it != 0 }
        val ma = microA?.let { if (abs(it) > 10_000) it / 1000 else it }?.let {
            when (st) {
                BatteryManager.BATTERY_STATUS_CHARGING -> abs(it)
                BatteryManager.BATTERY_STATUS_DISCHARGING -> -abs(it)
                else -> it
            }
        }
        return Battery(
            level = if (raw >= 0) raw * 100 / scale else null,
            tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.div(10f),
            voltageMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 },
            currentMa = ma,
            status = status,
            health = health,
        )
    }

    /** Sensor del servicio de temperaturas: nombre, °C y tipo (0 = CPU, 1 = GPU, 2 = batería, 3 = carcasa). */
    class Sensor(val name: String, val celsius: Float, val type: Int)

    private val TEMP_RE = Regex("""Temperature\{mValue=([\d.\-]+), mType=(-?\d+), mName=([^,}]+)""")
    private var cached: List<Sensor> = emptyList()
    private var cachedAt = 0L

    /** Lee el servicio de temperaturas (como mucho cada 2 s, porque dumpsys es algo pesado). */
    fun thermalSensors(): List<Sensor> {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedAt < 2000) return cached
        cachedAt = now
        // Solo la sección de valores actuales (no los de umbral ni los guardados)
        val out = RootShell.run("dumpsys thermalservice")
        val current = out.dropWhile { !it.contains("Current temperatures from HAL") }
            .drop(1).takeWhile { it.contains("Temperature{") }
        val src = current.ifEmpty { out }
        cached = src.mapNotNull { l ->
            val m = TEMP_RE.find(l) ?: return@mapNotNull null
            val c = m.groupValues[1].toFloatOrNull() ?: return@mapNotNull null
            if (c <= 0f || c >= 125f) return@mapNotNull null
            Sensor(m.groupValues[3].trim(), c, m.groupValues[2].toIntOrNull() ?: -1)
        }.distinctBy { it.name }
        return cached
    }

    /** Temperatura de la CPU según el servicio de temperaturas (la más alta de tipo CPU). */
    fun cpuTemp(): Float? = thermalSensors().filter { it.type == 0 }.maxOfOrNull { it.celsius }
}
