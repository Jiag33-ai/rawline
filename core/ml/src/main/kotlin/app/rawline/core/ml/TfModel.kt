package app.rawline.core.ml

import android.content.Context
import app.rawline.core.cache.PerfLog
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class Accel { GPU, CPU, NNAPI }

/** A LiteRT interpreter that tries the GPU first and falls back to the CPU. The choice is remembered per model. */
class TfModel(private val context: Context, private val file: File, private val name: String) {
    private val prefs = context.getSharedPreferences("rawline_ml", Context.MODE_PRIVATE)
    private val life = ModelLifecycle<Interpreter>(executor) { i -> runCatching { i.close() }; closeGpu() }
    private var gpu: GpuDelegate? = null
    var accel: Accel = Accel.CPU
        private set

    /** The GPU delegate must be created and used on one thread, so every model call runs on this executor. */
    private fun <T> onGpuThread(f: () -> T): T = life.call(f)

    fun interpreter(): Interpreter = onGpuThread { interpreterOnThread() }

    private fun closeGpu() { runCatching { gpu?.close() }; gpu = null }

    /** Runs on the ai-model thread only. Throws once released, so a late call never builds a second interpreter. */
    private fun interpreterOnThread(): Interpreter = life.current { create() }

    private fun create(): Interpreter {
        val saved = prefs.getString("accel_$name", null)?.let { runCatching { Accel.valueOf(it) }.getOrNull() }
        val order = if (saved != null) listOf(saved, Accel.CPU).distinct() else listOf(Accel.GPU, Accel.CPU)
        var last: Throwable? = null
        for (a in order) {
            try {
                val t0 = System.nanoTime()
                val i = build(a)
                // Warm up with zeros so a delegate that cannot run the graph fails here, not in the middle of an edit.
                try { warm(i) } catch (e: Throwable) { runCatching { i.close() }; throw e }
                PerfLog.record("ai_${name}_${a.name}_first_ms", (System.nanoTime() - t0) / 1_000_000)
                accel = a
                prefs.edit().putString("accel_$name", a.name).apply()
                return i
            } catch (e: Throwable) {
                last = e
                PerfLog.error("ai $name ${a.name}: ${e.message}")
                closeGpu()
                if (a == Accel.GPU) prefs.edit().putString("accel_$name", Accel.CPU.name).apply()
            }
        }
        throw IllegalStateException("Could not start $name: ${last?.message}")
    }

    private fun build(a: Accel): Interpreter {
        val o = Interpreter.Options()
        when (a) {
            Accel.GPU -> { val g = GpuDelegate(); gpu = g; o.addDelegate(g) }
            Accel.NNAPI -> o.setUseNNAPI(true)
            Accel.CPU -> o.setNumThreads(4)
        }
        return Interpreter(file, o)
    }

    private fun warm(i: Interpreter) {
        val ins = Array<Any>(i.inputTensorCount) { k -> zeros(i.getInputTensor(k).numBytes()) }
        val outs = HashMap<Int, Any>()
        for (k in 0 until i.outputTensorCount) outs[k] = zeros(i.getOutputTensor(k).numBytes())
        i.runForMultipleInputsOutputs(ins, outs)
    }

    fun run(inputs: Array<Any>, outputs: Map<Int, Any>) = onGpuThread {
        val i = interpreterOnThread()
        val t0 = System.nanoTime()
        i.runForMultipleInputsOutputs(inputs, outputs)
        PerfLog.record("ai_${name}_${accel.name}_run_ms", (System.nanoTime() - t0) / 1_000_000)
    }

    fun inputIndex(n: String, fallback: Int): Int = runCatching { interpreter().getInputIndex(n) }.getOrDefault(fallback)

    /** Marks the model released at once; the native objects are closed on the ai-model thread, after any run in flight. */
    fun release() = life.release()

    companion object {
        private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ai-model") }
        fun zeros(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        fun floats(count: Int): ByteBuffer = zeros(count * 4)
    }
}

/**
 * Owns one native model on a single executor thread. Release flips a flag immediately (so later calls fail instead of
 * rebuilding the model) and closes the object on the same thread, so it can never overlap a running inference.
 */
internal class ModelLifecycle<T : Any>(private val executor: java.util.concurrent.ExecutorService, private val close: (T) -> Unit) {
    @Volatile private var released = false
    private var value: T? = null // touched only on the executor thread

    val isReleased: Boolean get() = released

    fun <R> call(f: () -> R): R = try {
        executor.submit<R> { f() }.get()
    } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }

    /** Executor thread only. */
    fun current(create: () -> T): T {
        check(!released) { "model released" }
        value?.let { return it }
        val v = create()
        value = v
        return v
    }

    fun release() {
        released = true
        try {
            executor.execute { value?.let { runCatching { close(it) } }; value = null }
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }
}
