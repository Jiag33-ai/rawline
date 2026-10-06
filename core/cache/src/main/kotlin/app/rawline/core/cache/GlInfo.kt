package app.rawline.core.cache

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES20

/**
 * What the phone's GPU driver actually exposes (the engine needs OpenGL ES 3.2 and half float render targets). Read once, on
 * demand, from a throwaway 1 by 1 pixel context on the calling thread. The EGL display is left initialised on purpose:
 * terminating it would also tear down the engine's own contexts.
 */
object GlInfo {
    @Volatile private var cached: String? = null

    /** Query on a background thread that has no GL context current. */
    fun describe(): String = cached ?: synchronized(this) { cached ?: query().also { cached = it } }

    private fun query(): String = runCatching {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        check(EGL14.eglInitialize(d, v, 0, v, 1)) { "eglInitialize failed" }
        val cfg = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        val attrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(d, attrs, 0, cfg, 0, 1, n, 0) && n[0] > 0) { "no ES3 config" }
        val surf = EGL14.eglCreatePbufferSurface(d, cfg[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        val ctx = EGL14.eglCreateContext(d, cfg[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        try {
            check(EGL14.eglMakeCurrent(d, surf, surf, ctx)) { "eglMakeCurrent failed" }
            val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
            val tex = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, tex, 0)
            "Renderer: ${GLES20.glGetString(GLES20.GL_RENDERER)} (${GLES20.glGetString(GLES20.GL_VENDOR)})\n" +
                "Version: ${GLES20.glGetString(GLES20.GL_VERSION)}\n" +
                "EGL ${v[0]}.${v[1]}, max texture ${tex[0]}, float render targets: " +
                (if ("GL_EXT_color_buffer_float" in ext) "float " else "") + (if ("GL_EXT_color_buffer_half_float" in ext) "half" else "")
        } finally {
            EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(d, ctx)
            EGL14.eglDestroySurface(d, surf)
        }
    }.getOrElse { "unavailable (${it.javaClass.simpleName}: ${it.message})" }
}
