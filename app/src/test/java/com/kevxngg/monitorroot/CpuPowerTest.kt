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

    @Test fun governorKinds() {
        assertEquals(CpuPower.Kind.FIXED_MAX, CpuPower.kindOf("performance"))
        assertEquals(CpuPower.Kind.FIXED_MIN, CpuPower.kindOf("powersave"))
        assertEquals(CpuPower.Kind.MANUAL, CpuPower.kindOf("userspace"))
        assertEquals(CpuPower.Kind.DYNAMIC, CpuPower.kindOf("schedutil"))
        assertEquals(CpuPower.Kind.DYNAMIC, CpuPower.kindOf("walt"))
    }

    @Test fun allClustersPowersaveIsSaver() {
        assertEquals(CpuPower.Mode.SAVER, CpuPower.modeOf(listOf(cluster("powersave"), cluster("powersave")), "schedutil"))
    }

    @Test fun performanceNeedsFullMaximum() {
        assertEquals(CpuPower.Mode.PERFORMANCE, CpuPower.modeOf(listOf(cluster("performance")), "schedutil"))
        // Con el máximo limitado ya no es "mejor rendimiento": es personalizado
        assertNull(CpuPower.modeOf(listOf(cluster("performance", max = 1_000_000)), "schedutil"))
    }

    @Test fun factoryGovernorWithFullRangeIsBalanced() {
        assertEquals(CpuPower.Mode.BALANCED, CpuPower.modeOf(listOf(cluster("schedutil"), cluster("schedutil")), "schedutil"))
    }

    @Test fun mixedOrLimitedClustersAreCustom() {
        // El caso de la captura: un modo arriba y otro gobernador abajo
        assertNull(CpuPower.modeOf(listOf(cluster("powersave"), cluster("schedutil")), "schedutil"))
        assertNull(CpuPower.modeOf(listOf(cluster("schedutil", max = 1_000_000)), "schedutil"))
        assertNull(CpuPower.modeOf(listOf(cluster("userspace")), "schedutil"))
    }

    @Test fun noClustersHasNoMode() {
        assertNull(CpuPower.modeOf(emptyList(), "schedutil"))
    }
}
