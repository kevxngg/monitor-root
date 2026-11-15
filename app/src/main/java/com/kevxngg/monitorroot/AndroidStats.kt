package com.kevxngg.monitorroot

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import kotlin.math.abs

/**
 * Alternative ways to read data when Android blocks the /sys files
 * (happens with Shizuku, which runs as the "shell" user):
 *  - battery: the Android API, which any app can use without permissions
 *  - temperatures: the system thermal service (dumpsys thermalservice)
 * With root they are not used: /sys gives more sensors and better precision.
 */
object AndroidStats {
    class Battery(
        val level: Int?, val tempC: Float?, val voltageMv: Int?,
        val currentMa: Int?, val status: String?, val health: String?,
        val chargeMah: Int? = null, val technology: String? = null,
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
        // Current in µA; the sign depends on the manufacturer: normalized (+ charging, − discharging)
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
            // Remaining charge in µAh (not every phone reports it)
            chargeMah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                ?.takeIf { it != Int.MIN_VALUE && it > 0 }?.let { if (it > 100_000) it / 1000 else it },
            technology = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() },
        )
    }

    /** Thermal service sensor: name, °C and type (0 = CPU, 1 = GPU, 2 = battery, 3 = case). */
    class Sensor(val name: String, val celsius: Float, val type: Int)

    private val TEMP_RE = Regex("""Temperature\{mValue=([\d.\-]+), mType=(-?\d+), mName=([^,}]+)""")
    private var cached: List<Sensor> = emptyList()
    private var cachedAt = 0L

    /** Reads the thermal service (at most every 2 s, because dumpsys is somewhat heavy). */
    fun thermalSensors(): List<Sensor> {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedAt < 2000) return cached
        cachedAt = now
        // Only the current values section (not the threshold or cached ones)
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

    /** CPU temperature according to the thermal service (the highest of CPU type). */
    fun cpuTemp(): Float? = thermalSensors().filter { it.type == 0 }.maxOfOrNull { it.celsius }
}
