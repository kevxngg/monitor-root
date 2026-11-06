package com.kevxngg.monitorroot

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20

/**
 * Nombre real de la GPU (p. ej. "Mali-G57 MC2" o "Adreno (TM) 610") leído del
 * driver gráfico. Funciona en cualquier fabricante, se consulta una sola vez.
 */
object GlInfo {
    @Volatile private var tried = false
    @Volatile var renderer: String? = null
        private set

    /** Versión de OpenGL ES y del driver, por ejemplo "OpenGL ES 3.2 V@0615.0". */
    @Volatile var version: String? = null
        private set

    fun load(): String? {
        if (tried) return renderer
        tried = true
        try {
            val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return null
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(dpy, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) return null
            val cfg = configs[0] ?: return null
            val ctx = EGL14.eglCreateContext(
                dpy, cfg, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            val surf = EGL14.eglCreatePbufferSurface(
                dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            if (EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) {
                renderer = GLES20.glGetString(GLES20.GL_RENDERER)?.trim()?.takeIf { it.isNotEmpty() }
                version = GLES20.glGetString(GLES20.GL_VERSION)?.trim()?.takeIf { it.isNotEmpty() }
            }
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf)
            EGL14.eglDestroyContext(dpy, ctx)
            // No se llama a eglTerminate: la pantalla de la app usa la misma conexión EGL.
        } catch (_: Throwable) {
        }
        return renderer
    }
}

/** Versión de Vulkan que declara el teléfono, por ejemplo "1.3" (null si no tiene). */
fun vulkanVersion(pm: android.content.pm.PackageManager): String? {
    for (minor in 4 downTo 0) {
        val code = (1 shl 22) or (minor shl 12)
        if (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, code)) return "1.$minor"
    }
    return null
}
