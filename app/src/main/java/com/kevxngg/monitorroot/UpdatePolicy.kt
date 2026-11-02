package com.kevxngg.monitorroot

/** Three build numbers behind the latest verified release requires an update. */
object UpdatePolicy {
    fun isRequired(installed: Int, latest: Int): Boolean =
        installed > 0 && latest.toLong() - installed.toLong() >= 3L

    fun isOffered(installed: Int, latest: Int, skipped: Int, ignoreSkipped: Boolean = false): Boolean =
        latest > installed && (ignoreSkipped || latest > skipped || isRequired(installed, latest))
}
