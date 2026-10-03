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
}
