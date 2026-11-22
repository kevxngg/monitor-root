package com.kevxngg.monitorroot

/**
 * App text size (Settings > Text size). The chosen level multiplies Android's own font
 * scale exactly once, so the user's accessibility setting is kept and never applied twice.
 */
object FontScale {
    /** SharedPreferences key in "prefs". */
    const val KEY = "fontSize"

    /** Normal, Large, Extra large. */
    val LEVELS = listOf(1.0f, 1.15f, 1.3f)

    /** Never larger than this, even with Android's font already at its maximum. */
    private const val MAX = 2.6f

    /** Multiplier of a saved level; anything unknown is Normal. */
    fun level(index: Int): Float = LEVELS.getOrElse(index) { 1.0f }

    /** Font scale to use: Android's scale × the app level, capped. */
    fun effective(system: Float, index: Int): Float = (system * level(index)).coerceAtMost(MAX)
}
