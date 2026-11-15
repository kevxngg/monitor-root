package com.kevxngg.monitorroot

import org.junit.Assert.assertEquals
import org.junit.Test

class ScaleTest {
    @Test(timeout = 1000)
    fun invalidValuesDoNotFreeze() {
        // These values used to cause an endless loop that froze the app
        assertEquals(100_000f, niceScale(Float.NaN))
        assertEquals(100_000f, niceScale(Float.POSITIVE_INFINITY))
        assertEquals(100_000f, niceScale(-5f))
    }

    @Test
    fun roundScales() {
        assertEquals(100_000f, niceScale(50_000f))
        assertEquals(250_000f, niceScale(200_000f))
        assertEquals(5_000_000f, niceScale(3_000_000f))
    }
}
