package com.kevxngg.monitorroot

/**
 * Which app a process belongs to, from its Linux user ("u0_a153") instead of its name:
 * many apps run processes whose name is not their package (android.process.media,
 * com.google.android.gms.ui...), and those used to appear as Android processes.
 */
object ProcessMapping {
    private val APP_USER = Regex("""^u(\d+)_a(\d+)$""")

    /** uid of an app user ("u10_a5" → 1010005); null for isolated, system or native users. */
    fun uidOf(user: String): Int? {
        val m = APP_USER.matchEntire(user.trim()) ?: return null
        val androidUser = m.groupValues[1].toIntOrNull() ?: return null
        val appId = m.groupValues[2].toIntOrNull() ?: return null
        return androidUser * 100_000 + 10_000 + appId
    }

    /**
     * Package for a process among the packages of its uid: the one its name starts with
     * (several apps can share a uid), otherwise the first one.
     */
    fun pick(processName: String, candidates: List<String>): String? {
        val base = processName.substringBefore(':')
        return candidates.firstOrNull { it == base } ?: candidates.firstOrNull()
    }

    /** A package lookup that failed is tried again after this long (the app may have been installed since). */
    private const val RETRY_MS = 60_000L

    fun retryDue(failedAtMs: Long, nowMs: Long): Boolean = nowMs - failedAtMs >= RETRY_MS
}
