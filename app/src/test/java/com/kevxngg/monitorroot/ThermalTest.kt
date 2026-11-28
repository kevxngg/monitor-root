package com.kevxngg.monitorroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalTest {
    @Test
    fun snapdragonBatteryAndLimitZonesAreNotTemperatures() {
        assertFalse(Thermal.isTemperature("soc"))
        assertFalse(Thermal.isTemperature("socd"))
        assertFalse(Thermal.isTemperature("vbat"))
        assertFalse(Thermal.isTemperature("pm8550b-bcl-lvl0"))
        assertFalse(Thermal.isTemperature("cpu-0-0-0-step"))
        assertTrue(Thermal.isTemperature("cpu-1-3-1"))
        // MediaTek SoC sensors are real temperatures
        assertTrue(Thermal.isTemperature("soc_max"))
        assertTrue(Thermal.isTemperature("soc1"))
    }

    @Test
    fun hottestCpuSensorIgnoringOtherZones() {
        val p = Thermal.cpu(listOf("cpu-0-0-0" to 48f, "cpu-1-2-0" to 61f, "socd" to 90f, "soc" to 16f, "gpuss-0" to 70f, "cpuss-0" to 55f))
        assertEquals(61f, p!!.celsius)
        assertEquals("cpu-1-2-0", p.sensor)
    }

    @Test
    fun singleFaultySensorIsSkipped() {
        val p = Thermal.cpu(listOf("cpu-0-0-0" to 45f, "cpu-0-1-0" to 47f, "cpu-1-0-0" to 99f))
        assertEquals(47f, p!!.celsius)
    }

    @Test
    fun socSensorsWhenNoCpuSensor() {
        assertEquals(52f, Thermal.cpu(listOf("soc_max" to 52f, "battery" to 33f))!!.celsius)
        assertNull(Thermal.cpu(listOf("battery" to 33f, "soc" to 40f)))
    }

    @Test
    fun milliDegrees() {
        assertEquals(45.5f, Thermal.celsius(45500f))
        assertEquals(45f, Thermal.celsius(45f))
    }
}
