package com.kevxngg.monitorroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessMappingTest {
    @Test
    fun appUser() {
        assertEquals(10153, ProcessMapping.uidOf("u0_a153"))
    }

    @Test
    fun otherUsers() {
        // Work profile (user 10) and Xiaomi's second space / app twins (user 999)
        assertEquals(1010005, ProcessMapping.uidOf("u10_a5"))
        assertEquals(99910020, ProcessMapping.uidOf("u999_a20"))
    }

    @Test
    fun notAnApp() {
        assertNull(ProcessMapping.uidOf("u0_i12"))   // isolated process: no package of its own
        assertNull(ProcessMapping.uidOf("system"))
        assertNull(ProcessMapping.uidOf("root"))
        assertNull(ProcessMapping.uidOf("u0_a"))
    }

    @Test
    fun sharedUid() {
        assertEquals("com.b", ProcessMapping.pick("com.b:remote", listOf("com.a", "com.b")))
        assertEquals("com.a", ProcessMapping.pick("android.process.media", listOf("com.a", "com.b")))
        assertNull(ProcessMapping.pick("x", emptyList()))
    }

    @Test
    fun failedLookupRetriedAfterAMinute() {
        assertFalse(ProcessMapping.retryDue(0L, 59_999L))
        assertTrue(ProcessMapping.retryDue(0L, 60_000L))
    }
}
