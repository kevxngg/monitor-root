package com.kevxngg.monitorroot

/**
 * Una actualización solo es obligatoria cuando la versión publicada lo indica con
 * una build mínima (archivo MIN_BUILD del repositorio). Por defecto nunca bloquea.
 */
object UpdatePolicy {
    fun isRequired(installed: Int, minBuild: Int): Boolean =
        installed > 0 && minBuild > installed

    fun isOffered(installed: Int, latest: Int, skipped: Int, ignoreSkipped: Boolean = false, minBuild: Int = 0): Boolean =
        latest > installed && (ignoreSkipped || latest > skipped || isRequired(installed, minBuild))
}
