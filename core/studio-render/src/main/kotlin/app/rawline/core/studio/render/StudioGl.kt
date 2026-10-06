package app.rawline.core.studio.render

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import app.rawline.core.nativelib.StudioNative
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Timers, gauges and errors go to the app's PerfLog; this module does not depend on it. */
interface StudioPerf {
    fun record(name: String, value: Long)
    fun error(message: String)

    companion object { val None = object : StudioPerf { override fun record(name: String, value: Long) {}; override fun error(message: String) {} } }
}

/**
 * The GL side of Studio: owns the native compositor and its context, runs every queued session job on the GL thread, and draws the compositor's frame
 * on screen (the separate studio context of spec 3.3; Develop's engine is not touched). Same queue rules as EditorSession.post.
 *
 * A frame is `StudioNative.render` into a screen sized byte array, then a small display pass puts it over a checkerboard inside the canvas rectangle (black outside it).
 * That round trip through the CPU is the S1b way; a display pass inside the compositor is the later fix if the Copy report's `studio_frame_ms` says it matters.
 */
class StudioGl(private val perf: StudioPerf) : GLSurfaceView.Renderer, GpuExecutor {
    override var listener: SurfaceListener? = null
    /** Set by the screen: returns the uptime of the newest input drawn since the last call (0 for none). */
    var inputStamp: (() -> Long)? = null

    @Volatile private var frame: FrameSpec? = null
    @Volatile private var view: GLSurfaceView? = null
    @Volatile private var glReady = false
    @Volatile private var released = false
    private val pending = ArrayList<Queued>()
    private var handle = 0L
    private var gpu: NativeStudioGpu? = null
    private var everCreated = false
    private var w = 0
    private var h = 0
    private var out = ByteArray(0)
    private var display: Display? = null
    private var failureShown = false

    private class Queued(val block: (StudioGpu) -> Unit, val onDrop: (() -> Unit)?)

    fun attach(v: GLSurfaceView) { view = v }

    override fun setFrame(frame: FrameSpec?) { this.frame = frame }
    override fun requestRender() { view?.requestRender() }

    override fun post(onDrop: (() -> Unit)?, block: (StudioGpu) -> Unit) {
        val q = Queued(block, onDrop)
        synchronized(pending) {
            if (released) { drop(q); return }
            if (!glReady) { pending.add(q); return }
        }
        val v = view
        if (v == null) drop(q) else v.queueEvent { run(q) }
    }

    private fun run(q: Queued) { val g = gpu; if (g == null) drop(q) else q.block(g) }
    private fun drop(q: Queued) { runCatching { q.onDrop?.invoke() } }

    /** The screen is gone for good: whatever waits in the queue is dropped (a model thread waiting on a result is released). */
    fun release() {
        val dropped = synchronized(pending) { released = true; glReady = false; ArrayList(pending).also { pending.clear() } }
        dropped.forEach { drop(it) }
    }

    /** The GL thread stops in the view's detach, so the compositor is torn down first while that thread can still run it. */
    fun destroy(v: GLSurfaceView) {
        synchronized(pending) { glReady = false }
        val done = CountDownLatch(1)
        v.queueEvent {
            try {
                display?.release(); display = null
                if (handle != 0L) { StudioNative.destroy(handle); handle = 0; gpu = null }
            } finally { done.countDown() }
        }
        done.await(500, TimeUnit.MILLISECONDS)
    }

    // ---- GLSurfaceView.Renderer (GL thread) ----------------------------------------------------------------------------------

    override fun onSurfaceCreated(g: GL10?, config: EGLConfig?) {
        if (released) return
        synchronized(pending) { glReady = false }
        // Only a new context calls this: the old compositor's GL names died with the old context, so it is freed without GL calls.
        if (handle != 0L) StudioNative.abandon(handle)
        display = null
        handle = StudioNative.create()
        val err = StudioNative.init(handle)
        if (err != null) { perf.error("studio gpu init: $err"); handle = 0; gpu = null; return }
        gpu = NativeStudioGpu(handle)
        display = Display.create()
        val restored = everCreated
        everCreated = true
        val queued = synchronized(pending) { glReady = !released; ArrayList(pending).also { pending.clear() } }
        queued.forEach { run(it) }
        if (restored) listener?.onContextRestored()
    }

    override fun onSurfaceChanged(g: GL10?, width: Int, height: Int) {
        w = width; h = height
        out = ByteArray(width * height * 4)
        GLES30.glViewport(0, 0, width, height)
        listener?.onSurfaceSize(width, height)
    }

    override fun onDrawFrame(g: GL10?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val f = frame
        val d = display
        if (handle == 0L || f == null || d == null || w <= 0 || h <= 0 || out.size < w * h * 4) return
        val t0 = SystemClock.elapsedRealtimeNanos()
        val ok = StudioNative.render(handle, f.layers, f.vx, f.vy, f.zoom, w, h, out)
        if (!ok) { if (!failureShown) { failureShown = true; perf.error("studio render failed (GL error or out of GPU memory)") }; return }
        d.draw(out, w, h, f)
        perf.record("studio_frame_ms", (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000)
        val stamp = inputStamp?.invoke() ?: 0L
        if (stamp > 0L) perf.record("studio_input_to_pixel_ms", (SystemClock.uptimeMillis() - stamp).coerceAtLeast(0))
    }

    /** Draws the compositor's straight RGBA8 frame over a checkerboard inside the canvas rectangle. Row 0 of the frame is the top of the screen. */
    private class Display private constructor(private val prog: Int, private val tex: Int) {
        private var texW = 0
        private var texH = 0
        private val uSize = GLES30.glGetUniformLocation(prog, "uSize")
        private val uDoc = GLES30.glGetUniformLocation(prog, "uDoc")
        private val uTex = GLES30.glGetUniformLocation(prog, "uTex")
        private var vao = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]

        fun draw(pixels: ByteArray, w: Int, h: Int, f: FrameSpec) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            val buf = ByteBuffer.wrap(pixels)
            if (w != texW || h != texH) {
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                texW = w; texH = h
            } else GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDisable(GLES30.GL_DEPTH_TEST); GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glUseProgram(prog)
            GLES30.glUniform1i(uTex, 0)
            GLES30.glUniform2f(uSize, w.toFloat(), h.toFloat())
            GLES30.glUniform4f(uDoc, (0f - f.vx) * f.zoom, (0f - f.vy) * f.zoom, (f.docW - f.vx) * f.zoom, (f.docH - f.vy) * f.zoom)
            GLES30.glBindVertexArray(vao)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindVertexArray(0)
        }

        fun release() {
            GLES30.glDeleteProgram(prog); GLES30.glDeleteTextures(1, intArrayOf(tex), 0); GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        }

        companion object {
            private const val VERT = """#version 300 es
void main() {
    vec2 c = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    gl_Position = vec4(c * 2.0 - 1.0, 0.0, 1.0);
}"""
            // Straight alpha frame over a 16 px two grey checkerboard inside the canvas rectangle (screen px, origin top left); black outside it.
            private const val FRAG = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec2 uSize;
uniform vec4 uDoc;
out vec4 oColor;
void main() {
    vec2 p = vec2(gl_FragCoord.x, uSize.y - gl_FragCoord.y);
    if (p.x < uDoc.x || p.y < uDoc.y || p.x >= uDoc.z || p.y >= uDoc.w) { oColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    vec4 t = texelFetch(uTex, ivec2(int(p.x), int(p.y)), 0);
    ivec2 q = ivec2(floor((p - uDoc.xy) / 16.0));
    float chk = (((q.x + q.y) & 1) == 0) ? 0.78 : 0.62;
    oColor = vec4(t.rgb * t.a + vec3(chk) * (1.0 - t.a), 1.0);
}"""

            fun create(): Display? {
                val vs = compile(GLES30.GL_VERTEX_SHADER, VERT); val fs = compile(GLES30.GL_FRAGMENT_SHADER, FRAG)
                if (vs == 0 || fs == 0) return null
                val p = GLES30.glCreateProgram()
                GLES30.glAttachShader(p, vs); GLES30.glAttachShader(p, fs); GLES30.glLinkProgram(p)
                GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
                val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
                if (ok[0] == 0) { GLES30.glDeleteProgram(p); return null }
                val t = IntArray(1); GLES30.glGenTextures(1, t, 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                return Display(p, t[0])
            }

            private fun compile(type: Int, src: String): Int {
                val s = GLES30.glCreateShader(type)
                GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
                val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) { GLES30.glDeleteShader(s); return 0 }
                return s
            }
        }
    }
}

/** The surface. The session's pointer handling sits above it in Compose, so this view takes no touches of its own. */
class StudioGlView(context: Context, private val gl: StudioGl) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        gl.attach(this)
        setRenderer(gl)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onDetachedFromWindow() {
        gl.destroy(this)
        super.onDetachedFromWindow()
    }
}
