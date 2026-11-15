package com.kevxngg.monitorroot

/**
 * An update is mandatory only when the published release says so with
 * a minimum build (the repository's MIN_BUILD file). By default it never blocks.
 */
object UpdatePolicy {
    fun isRequired(installed: Int, minBuild: Int): Boolean =
        installed > 0 && minBuild > installed

    fun isOffered(installed: Int, latest: Int, skipped: Int, ignoreSkipped: Boolean = false, minBuild: Int = 0): Boolean =
        latest > installed && (ignoreSkipped || latest > skipped || isRequired(installed, minBuild))
}
