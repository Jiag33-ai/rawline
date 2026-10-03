package app.rawline.core.ml

import android.content.Context
import android.graphics.Bitmap
import app.rawline.core.cache.PerfLog
import app.rawline.core.render.EditorSession
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** On-device AI selections. Each returns an 8 bit alpha at the requested layer size, in the frame the masks use. */
interface MaskAi {
    suspend fun subject(w: Int, h: Int): ByteArray?
    suspend fun sky(w: Int, h: Int): ByteArray?
    suspend fun people(w: Int, h: Int): List<Pair<String, ByteArray>>
    suspend fun objectAt(nx: Float, ny: Float, w: Int, h: Int): ByteArray?
    /** Runs the heavy per-photo encoder ahead of time so taps feel instant. */
    suspend fun prepare()
    fun invalidate()
}

class AiMasksImpl(private val context: Context, private val store: ModelStore, private val session: EditorSession) : MaskAi {
    private val skyModel = lazy { TfModel(context, store.file("segformer_base.tflite"), "sky") }
    private val peopleModel = lazy { TfModel(context, store.file("selfie_multiclass.tflite"), "people") }
    private val samEnc = lazy { TfModel(context, store.file("sam_encoder.tflite"), "sam_encoder") }
    private val samDec = lazy { TfModel(context, store.file("sam_decoder.tflite"), "sam_decoder") }

    private var embedding: FloatArray? = null
    private var embedFrame = intArrayOf(0, 0, 0, 0)   // frame w, h, scaled w, scaled h
    private var embedKey = ""

    override fun invalidate() { embedding = null; embedKey = "" }

    private suspend fun frame(maxEdge: Int = 1024): Bitmap? = session.renderFrame(maxEdge)

    // ---------------- subject (ML Kit) ----------------
    override suspend fun subject(w: Int, h: Int): ByteArray? {
        val ref = frame() ?: return null
        val options = SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build()
        val client = SubjectSegmentation.getClient(options)
        try {
            val result = suspendCancellableCoroutine { c ->
                client.process(InputImage.fromBitmap(ref, 0)).addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resumeWithException(it) }
            }
            val buf = result.foregroundConfidenceMask ?: return null
            val m = FloatArray(ref.width * ref.height)
            buf.rewind(); buf.get(m)
            return refine(m, ref.width, ref.height, ref, w, h, radius = 6, soft = 1f)
        } finally { client.close() }
    }

    // ---------------- sky (SegFormer ADE20K, class 2) ----------------
    override suspend fun sky(w: Int, h: Int): ByteArray? {
        if (!store.ensure(Models.SKY)) return null
        val ref = frame() ?: return null
        val inp = rgbBuffer(ref, 512, 512)
        val out = TfModel.floats(128 * 128 * 150)
        skyModel.value.run(arrayOf(inp), mapOf(0 to out))
        out.rewind()
        val logits = FloatArray(128 * 128 * 150); out.asFloatBuffer().get(logits)
        val sky = FloatArray(128 * 128)
        for (i in 0 until 128 * 128) {
            var mx = -1e9f
            val o = i * 150
            for (c in 0 until 150) mx = max(mx, logits[o + c])
            var sum = 0f
            for (c in 0 until 150) sum += exp(logits[o + c] - mx)
            sky[i] = exp(logits[o + 2] - mx) / sum
        }
        return refine(sky, 128, 128, ref, w, h, radius = 10, soft = 1f)
    }

    // ---------------- people (MediaPipe selfie multiclass) ----------------
    override suspend fun people(w: Int, h: Int): List<Pair<String, ByteArray>> {
        if (!store.ensure(Models.PEOPLE)) return emptyList()
        val ref = frame() ?: return emptyList()
        val out = TfModel.floats(256 * 256 * 6)
        peopleModel.value.run(arrayOf(rgbBuffer(ref, 256, 256)), mapOf(0 to out))
        out.rewind()
        val raw = FloatArray(256 * 256 * 6); out.asFloatBuffer().get(raw)
        val probs = Array(6) { FloatArray(256 * 256) }
        for (i in 0 until 256 * 256) {
            var mx = -1e9f
            for (c in 0 until 6) mx = max(mx, raw[i * 6 + c])
            var sum = 0f
            for (c in 0 until 6) sum += exp(raw[i * 6 + c] - mx)
            for (c in 0 until 6) probs[c][i] = exp(raw[i * 6 + c] - mx) / sum
        }
        val person = FloatArray(256 * 256) { 1f - probs[0][it] }
        val parts = listOf("Person" to person, "Hair" to probs[1], "Body skin" to probs[2], "Face skin" to probs[3], "Clothes" to probs[4])
        val result = ArrayList<Pair<String, ByteArray>>()
        for ((name, p) in parts) {
            val area = p.count { it > 0.5f } / p.size.toFloat()
            if (area < 0.002f) continue
            result.add(name to refine(p, 256, 256, ref, w, h, radius = 8, soft = 1f))
        }
        return result
    }

    // ---------------- object under a tap (MobileSAM) ----------------
    override suspend fun prepare() {
        if (!store.ensure(Models.SAM)) return
        val ref = frame() ?: return
        encode(ref)
    }

    private fun encode(ref: Bitmap) {
        // A cheap content probe so a changed frame (rotate, straighten) re-encodes
        val probe = (0 until 16).fold(0L) { a, i -> a * 31 + ref.getPixel((i * 97) % ref.width, (i * 61) % ref.height) }
        val k = "${ref.width}x${ref.height}:$probe"
        if (embedding != null && embedKey == k) return
        val s = 1024f / max(ref.width, ref.height)
        val nw = (ref.width * s).toInt().coerceIn(1, 1024); val nh = (ref.height * s).toInt().coerceIn(1, 1024)
        val scaled = Bitmap.createScaledBitmap(ref, nw, nh, true)
        val px = IntArray(nw * nh); scaled.getPixels(px, 0, nw, 0, 0, nw, nh)
        val inp = TfModel.floats(1024 * 1024 * 3)
        val f = inp.asFloatBuffer()
        val line = FloatArray(1024 * 3)
        for (y in 0 until 1024) {
            line.fill(0f)
            if (y < nh) for (x in 0 until nw) { val c = px[y * nw + x]; line[x * 3] = (c shr 16 and 255) / 255f; line[x * 3 + 1] = (c shr 8 and 255) / 255f; line[x * 3 + 2] = (c and 255) / 255f }
            f.put(line)
        }
        val out = TfModel.floats(64 * 64 * 256)
        samEnc.value.run(arrayOf(inp), mapOf(0 to out))
        out.rewind()
        val e = FloatArray(64 * 64 * 256); out.asFloatBuffer().get(e)
        embedding = e; embedKey = k; embedFrame = intArrayOf(ref.width, ref.height, nw, nh)
    }

    override suspend fun objectAt(nx: Float, ny: Float, w: Int, h: Int): ByteArray? {
        if (!store.ensure(Models.SAM)) return null
        val ref = frame() ?: return null
        encode(ref)
        val e = embedding ?: return null
        val nw = embedFrame[2]; val nh = embedFrame[3]
        val dec = samDec.value
        val embIn = TfModel.floats(e.size).also { it.asFloatBuffer().put(e) }
        val pts = TfModel.floats(2).also { it.asFloatBuffer().put(floatArrayOf(nx * nw, ny * nh)) }
        val lab = TfModel.floats(1).also { it.asFloatBuffer().put(floatArrayOf(1f)) }
        val masks = TfModel.floats(256 * 256)
        val scores = TfModel.floats(1)
        val ie = dec.inputIndex("image_embeddings", 0); val ip = dec.inputIndex("point_coords", 1); val il = dec.inputIndex("point_labels", 2)
        val inputs = arrayOfNulls<Any>(3)
        inputs[ie] = embIn; inputs[ip] = pts; inputs[il] = lab
        // Output order follows the model: masks, scores
        dec.run(inputs.requireNoNulls(), mapOf(0 to masks, 1 to scores))
        masks.rewind()
        val logits = FloatArray(256 * 256); masks.asFloatBuffer().get(logits)
        // keep the valid (unpadded) part of the 256 x 256 frame
        val vw = max(1, (nw / 4f).toInt()); val vh = max(1, (nh / 4f).toInt())
        val m = FloatArray(vw * vh) { i -> val x = i % vw; val y = i / vw; sigmoid(logits[y * 256 + x] * 0.7f) }
        return refine(m, vw, vh, ref, w, h, radius = 6, soft = 1f)
    }

    private fun sigmoid(x: Float) = 1f / (1f + exp(-x))

    // ---------------- helpers ----------------
    private fun rgbBuffer(b: Bitmap, w: Int, h: Int) = TfModel.floats(w * h * 3).also { buf ->
        val s = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h); s.getPixels(px, 0, w, 0, 0, w, h)
        val f = buf.asFloatBuffer()
        for (c in px) { f.put((c shr 16 and 255) / 255f); f.put((c shr 8 and 255) / 255f); f.put((c and 255) / 255f) }
    }

    /** Bilinear upsample of a probability map to layer size, then edge refinement against the picture. */
    private fun refine(p: FloatArray, sw: Int, sh: Int, ref: Bitmap, w: Int, h: Int, radius: Int, soft: Float): ByteArray {
        val up = FloatArray(w * h)
        for (y in 0 until h) {
            val fy = (y + 0.5f) * sh / h - 0.5f
            val y0 = fy.toInt().coerceIn(0, sh - 1); val y1 = min(y0 + 1, sh - 1); val ty = (fy - y0).coerceIn(0f, 1f)
            for (x in 0 until w) {
                val fx = (x + 0.5f) * sw / w - 0.5f
                val x0 = fx.toInt().coerceIn(0, sw - 1); val x1 = min(x0 + 1, sw - 1); val tx = (fx - x0).coerceIn(0f, 1f)
                val a = p[y0 * sw + x0] * (1 - tx) + p[y0 * sw + x1] * tx
                val b = p[y1 * sw + x0] * (1 - tx) + p[y1 * sw + x1] * tx
                up[y * w + x] = a * (1 - ty) + b * ty
            }
        }
        // Guided filter against the picture, done at half size to keep it quick, then applied back
        val gw = max(64, w / 2); val gh = max(64, h / 2)
        val g = Bitmap.createScaledBitmap(ref, gw, gh, true)
        val gp = IntArray(gw * gh); g.getPixels(gp, 0, gw, 0, 0, gw, gh)
        val guide = FloatArray(gw * gh) { val c = gp[it]; ((c shr 16 and 255) * 0.299f + (c shr 8 and 255) * 0.587f + (c and 255) * 0.114f) / 255f }
        val half = FloatArray(gw * gh) { i -> up[min(h - 1, (i / gw) * h / gh) * w + min(w - 1, (i % gw) * w / gw)] }
        val filtered = GuidedFilter.apply(guide, half, gw, gh, radius, 1e-3f)
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val gx = min(gw - 1, x * gw / w); val gy = min(gh - 1, y * gh / h)
            val fv = filtered[gy * gw + gx]
            // trust the filter at edges, the plain map elsewhere
            val v = (up[y * w + x] * 0.35f + fv * 0.65f)
            val s = ((v - 0.5f) * 3.2f + 0.5f).coerceIn(0f, 1f)
            out[y * w + x] = (s * 255f * soft).toInt().coerceIn(0, 255).toByte()
        }
        PerfLog.record("ai_refine_px", (w * h).toLong())
        return out
    }
}
