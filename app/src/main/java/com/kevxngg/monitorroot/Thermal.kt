package com.kevxngg.monitorroot

import kotlin.math.abs

/**
 * Picks the CPU temperature from the kernel's thermal zones, the same way for the Performance page,
 * the floating monitor and game mode.
 *
 * Several zones have temperature-like names but are not temperatures: on Snapdragon "soc" is the
 * battery charge (%), "socd" a power limit, "vbat"/"ibat" battery voltage and current, the "bcl" and
 * "-step" zones throttling levels. Mixed in with real sensors they gave wrong readings.
 */
object Thermal {
    private val NOT_TEMP = Regex("^(soc|socd)$|vbat|ibat|bcl|-step$|lmh|limit|droop|cdev|-lvl|_lvl", RegexOption.IGNORE_CASE)
    private val CPU = Regex("cpu|apc|mtktscpu|cluster", RegexOption.IGNORE_CASE)
    private val SOC = Regex("soc|tsens|mtktsap", RegexOption.IGNORE_CASE)

    /** A zone value in °C: most kernels report m°C. */
    fun celsius(raw: Float): Float = if (abs(raw) >= 1000f) raw / 1000f else raw

    /** False for zones that report something other than a temperature. */
    fun isTemperature(type: String): Boolean = !NOT_TEMP.containsMatchIn(type.trim())

    /** Zones worth reading for the CPU temperature (CPU sensors, or the SoC's if the chip has none). */
    fun isCpuCandidate(type: String): Boolean =
        isTemperature(type) && (CPU.containsMatchIn(type) || SOC.containsMatchIn(type))

    /** The result and which sensor gave it (for the diagnostics report). */
    data class Pick(val celsius: Float, val sensor: String, val ignored: String? = null)

    /**
     * CPU temperature from (zone type, °C) readings: the hottest CPU sensor. If the chip has no CPU
     * sensor, the hottest SoC sensor. A single sensor more than 20 °C above all the others is a
     * faulty reading and is skipped.
     */
    fun cpu(readings: List<Pair<String, Float>>): Pick? {
        val valid = readings.filter { (t, c) -> c > 0f && c < 125f && isTemperature(t) }
        val pool = valid.filter { CPU.containsMatchIn(it.first) }.ifEmpty { valid.filter { SOC.containsMatchIn(it.first) } }
        val s = pool.sortedByDescending { it.second }
        if (s.isEmpty()) return null
        return if (s.size >= 3 && s[0].second - s[1].second > 20f) Pick(s[1].second, s[1].first, ignored = "${s[0].first} ${s[0].second}")
        else Pick(s[0].second, s[0].first)
    }
}
