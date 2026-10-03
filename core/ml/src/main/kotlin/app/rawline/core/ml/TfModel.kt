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
    private var interp: Interpreter? = null
    private var gpu: GpuDelegate? = null
    var accel: Accel = Accel.CPU
        private set

    @Synchronized
    fun interpreter(): Interpreter {
        interp?.let { return it }
        val saved = prefs.getString("accel_$name", null)?.let { runCatching { Accel.valueOf(it) }.getOrNull() }
        val order = if (saved != null) listOf(saved, Accel.CPU).distinct() else listOf(Accel.GPU, Accel.CPU)
        var last: Throwable? = null
        for (a in order) {
            try {
                val t0 = System.nanoTime()
                val i = build(a)
                // Warm up with zeros so a delegate that cannot run the graph fails here, not in the middle of an edit.
                warm(i)
                PerfLog.record("ai_${name}_${a.name}_first_ms", (System.nanoTime() - t0) / 1_000_000)
                accel = a; interp = i
                prefs.edit().putString("accel_$name", a.name).apply()
                return i
            } catch (e: Throwable) {
                last = e
                PerfLog.error("ai $name ${a.name}: ${e.message}")
                release()
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

    @Synchronized
    fun run(inputs: Array<Any>, outputs: Map<Int, Any>) {
        val i = interpreter()
        val t0 = System.nanoTime()
        i.runForMultipleInputsOutputs(inputs, outputs)
        PerfLog.record("ai_${name}_${accel.name}_run_ms", (System.nanoTime() - t0) / 1_000_000)
    }

    fun inputIndex(n: String, fallback: Int): Int = runCatching { interpreter().getInputIndex(n) }.getOrDefault(fallback)

    @Synchronized
    fun release() { runCatching { interp?.close() }; runCatching { gpu?.close() }; interp = null; gpu = null }

    companion object {
        fun zeros(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        fun floats(count: Int): ByteBuffer = zeros(count * 4)
    }
}
