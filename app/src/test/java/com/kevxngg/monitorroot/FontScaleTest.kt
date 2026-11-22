package com.kevxngg.monitorroot

import org.junit.Assert.assertEquals
import org.junit.Test

class FontScaleTest {
    @Test
    fun levels() {
        assertEquals(listOf(1.0f, 1.15f, 1.3f), FontScale.LEVELS)
    }

    @Test
    fun appliedOnceOverSystem() {
        // Android's own size (1.3) times "Large" (1.15), multiplied once
        assertEquals(1.3f * 1.15f, FontScale.effective(1.3f, 1), 0.0001f)
    }

    @Test
    fun normalKeepsSystem() {
        assertEquals(0.85f, FontScale.effective(0.85f, 0), 0.0001f)
    }

    @Test
    fun invalidLevelIsNormal() {
        // A corrupt saved value must not break the app at start-up
        assertEquals(1.0f, FontScale.level(7), 0f)
        assertEquals(1.0f, FontScale.level(-1), 0f)
    }

    @Test
    fun effectiveIsCapped() {
        // Android already at 2.0 + "Extra large" would be 2.6; never more than that
        assertEquals(2.6f, FontScale.effective(2.0f, 2), 0.0001f)
        assertEquals(2.6f, FontScale.effective(2.5f, 2), 0.0001f)
    }
}
