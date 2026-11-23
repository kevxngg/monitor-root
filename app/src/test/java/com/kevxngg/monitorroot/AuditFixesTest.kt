package com.kevxngg.monitorroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditFixesTest {
    // ---- Update signature: the APK must be signed with the installed app's CURRENT key ----
    @Test
    fun apkSignedOnlyWithRetiredKeyIsRejected() {
        // Installed: rotated from "old" to "new". An APK signed only with the old (leaked) key must not pass.
        assertFalse(SignerCheck.matches(archiveHistory = listOf("old"), installedHistory = listOf("old", "new")))
    }

    @Test
    fun apkWithSameRotationIsAccepted() {
        assertTrue(SignerCheck.matches(listOf("old", "new"), listOf("old", "new")))
        assertTrue(SignerCheck.matches(listOf("new"), listOf("new")))
    }

    @Test
    fun firstRotationIsAccepted() {
        // Installed app signed with the old key; the new version rotates to a new key (lineage old -> new)
        assertTrue(SignerCheck.matches(listOf("old", "new"), listOf("old")))
        assertTrue(SignerCheck.matches(listOf("old", "new", "newer"), listOf("old", "new")))
    }

    @Test
    fun installScriptCleansOldCopiesAndHasHashFallbacks() {
        val s = Updater.installScript("/x/u.apk", "/data/local/tmp/monitorroot-update-1.apk", "ab12", "com.kevxngg.monitorroot")
        assertTrue(s.startsWith("rm -f /data/local/tmp/monitorroot-update-*.apk"))
        assertTrue(s.contains("toybox sha256sum"))
        assertTrue(s.contains("busybox sha256sum"))
    }

    @Test
    fun noSignersIsRejected() {
        assertFalse(SignerCheck.matches(emptyList(), listOf("new")))
        assertFalse(SignerCheck.matches(listOf("new"), emptyList()))
    }

    // ---- Install: the copied file is checked again right before installing ----
    @Test
    fun installScriptChecksHashOfTheFinalFile() {
        val s = Updater.installScript("/data/user/0/x/cache/u.apk", "/data/local/tmp/m-1.apk", "ab12", "com.kevxngg.monitorroot")
        assertTrue(s.contains("rm -f '/data/local/tmp/m-1.apk'"))
        assertTrue(s.indexOf("rm -f") < s.indexOf("cp "))
        assertTrue(s.contains("sha256sum '/data/local/tmp/m-1.apk'"))
        assertTrue(s.contains("= 'ab12'"))
        assertTrue(s.indexOf("sha256sum") < s.indexOf("pm install"))
    }

    // ---- Battery history: one bad line must not wipe the whole history ----
    @Test
    fun badBatteryLineIsSkipped() {
        assertNull(BatteryLog.parseLine("garbage"))
        assertNull(BatteryLog.parseLine("12,abc,30.5,1"))
        val e = BatteryLog.parseLine("1700000000000,85,31.5,1")!!
        assertEquals(85, e.level)
        assertTrue(e.charging)
    }

    // ---- Floating monitor: battery values are read by file name, not by line position ----
    @Test
    fun batteryParsedByPath() {
        val both = QuickStats.parseBattery(listOf("/sys/class/power_supply/battery/temp:345", "/sys/class/power_supply/battery/capacity:85"))
        assertEquals(34.5f, both.first!!, 0.001f)
        assertEquals(85, both.second)
        // temp missing: the capacity must not be taken as a temperature
        val noTemp = QuickStats.parseBattery(listOf("/sys/class/power_supply/battery/capacity:85"))
        assertNull(noTemp.first)
        assertEquals(85, noTemp.second)
    }

    // ---- CPU restore: a cluster in power-save mode keeps its low maximum ----
    @Test
    fun restoreKeepsPowerSaveMaximum() {
        val (min, max) = CpuPower.restoreLimits(CpuPower.Kind.FIXED_MIN, custom = false, min = 500, max = 500, hwMin = 500, hwMax = 2000)
        assertEquals(500L, min)
        assertEquals(500L, max)
    }

    @Test
    fun restoreDropsTemporarySystemCap() {
        // Dynamic governor, no user limits: a thermal cap seen in the snapshot (1200) is not made permanent
        val (min, max) = CpuPower.restoreLimits(CpuPower.Kind.DYNAMIC, custom = false, min = 900, max = 1200, hwMin = 500, hwMax = 2000)
        assertEquals(500L, min)
        assertEquals(2000L, max)
    }

    // ---- Root shell: no "su" storm when root is denied ----
    @Test
    fun rootRetryWaits() {
        assertFalse(RootShell.retryAllowed(lastFailMs = 1_000L, nowMs = 5_000L))
        assertTrue(RootShell.retryAllowed(lastFailMs = 1_000L, nowMs = 11_000L))
        assertTrue(RootShell.retryAllowed(lastFailMs = 0L, nowMs = 1L))
    }
}
