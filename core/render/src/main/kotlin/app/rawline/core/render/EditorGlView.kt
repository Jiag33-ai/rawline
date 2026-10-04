package app.rawline.core.render

import android.content.Context
import android.opengl.GLSurfaceView

class EditorGlView(context: Context, val session: EditorSession) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        session.glView = this
        setRenderer(session.renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** The GL thread stops in super, so the engine is torn down first while that thread can still run it. */
    override fun onDetachedFromWindow() {
        session.destroyEngine()
        super.onDetachedFromWindow()
    }
}
