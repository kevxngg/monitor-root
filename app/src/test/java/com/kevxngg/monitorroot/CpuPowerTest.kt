package com.kevxngg.monitorroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpuPowerTest {
    private val govs = listOf("schedutil", "performance", "powersave", "userspace", "conservative")
    private val freqs = listOf(300_000L, 1_000_000L, 1_800_000L)

    private fun cluster(gov: String, min: Long = 300_000, max: Long = 1_800_000) = CpuPower.Cluster(
        path = "/sys/devices/system/cpu/cpufreq/policy0", cpus = "0–5", governor = gov, governors = govs,
        minKhz = min, maxKhz = max, hwMinKhz = 300_000, hwMaxKhz = 1_800_000, setspeedKhz = null, freqsKhz = freqs,
    )

    private fun mode(vararg cs: CpuPower.Cluster, custom: Boolean = false) =
        CpuPower.modeOf(cs.toList(), "schedutil", custom)

    @Test fun governorKinds() {
        assertEquals(CpuPower.Kind.FIXED_MAX, CpuPower.kindOf("performance"))
        assertEquals(CpuPower.Kind.FIXED_MIN, CpuPower.kindOf("powersave"))
        assertEquals(CpuPower.Kind.MANUAL, CpuPower.kindOf("userspace"))
        assertEquals(CpuPower.Kind.DYNAMIC, CpuPower.kindOf("schedutil"))
        assertEquals(CpuPower.Kind.DYNAMIC, CpuPower.kindOf("walt"))
    }

    @Test fun eachModeIsRecognized() {
        assertEquals(CpuPower.Mode.SAVER, mode(cluster("powersave"), cluster("powersave")))
        assertEquals(CpuPower.Mode.BALANCED, mode(cluster("schedutil"), cluster("schedutil")))
        assertEquals(CpuPower.Mode.PERFORMANCE, mode(cluster("performance"), cluster("performance")))
    }

    @Test fun systemChangesDoNotBreakTheMode() {
        // Touch boost sube la mínima: sigue siendo Equilibrado
        assertEquals(CpuPower.Mode.BALANCED, mode(cluster("schedutil", min = 1_800_000)))
        // El control térmico baja la máxima: sigue siendo Mejor rendimiento
        assertEquals(CpuPower.Mode.PERFORMANCE, mode(cluster("performance", max = 1_000_000)))
    }

    @Test fun manualLimitsAreCustom() {
        assertNull(mode(cluster("schedutil", max = 1_000_000), custom = true))
        assertNull(mode(cluster("performance"), custom = true))
    }

    @Test fun mixedGovernorsAreCustom() {
        assertNull(mode(cluster("powersave"), cluster("schedutil")))
        assertNull(mode(cluster("userspace")))
    }

    @Test fun systemCapOnlyWhenNotChosenByUser() {
        assertEquals(1_000_000L, CpuPower.systemCapKhz(cluster("performance", max = 1_000_000), customLimits = false))
        assertNull(CpuPower.systemCapKhz(cluster("performance", max = 1_000_000), customLimits = true))
        assertNull(CpuPower.systemCapKhz(cluster("performance"), customLimits = false))
    }

    @Test fun noClustersHasNoMode() {
        assertNull(CpuPower.modeOf(emptyList(), "schedutil", false))
    }
}
