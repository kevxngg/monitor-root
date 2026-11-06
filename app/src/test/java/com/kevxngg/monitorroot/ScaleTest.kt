package com.kevxngg.monitorroot

import org.junit.Assert.assertEquals
import org.junit.Test

class ScaleTest {
    @Test(timeout = 1000)
    fun invalidValuesDoNotFreeze() {
        // Antes estos valores dejaban un bucle sin fin que congelaba la app
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
