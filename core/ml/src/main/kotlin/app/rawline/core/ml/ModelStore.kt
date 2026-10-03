package app.rawline.core.ml

import android.content.Context
import app.rawline.core.cache.PerfLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * One downloadable group of model files. Models come from official sources (Qualcomm AI Hub public releases of the
 * original open models, and Google MediaPipe) and are kept in app storage. Nothing is sent anywhere: files are only fetched.
 */
class ModelPack(
    val id: String, val title: String, val approxMb: Int, val url: String,
    /** zip entry name -> local file name; empty when [url] is the tflite file itself */
    val files: Map<String, String>, val localName: String? = null,
    val licence: String,
)

object Models {
    private const val AI_HUB = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models"
    val SKY = ModelPack("sky", "Sky selection (SegFormer-B0, ADE20K)", 14, "$AI_HUB/segformer_base/releases/v0.63.0/segformer_base-tflite-float.zip",
        mapOf("segformer_base-tflite-float/segformer_base.tflite" to "segformer_base.tflite"), licence = "Apache-2.0 (weights: NVIDIA SegFormer, ADE20K)")
    val PEOPLE = ModelPack("people", "People parts (MediaPipe selfie multiclass)", 16, "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite",
        emptyMap(), localName = "selfie_multiclass.tflite", licence = "Apache-2.0 (Google MediaPipe)")
    val SAM = ModelPack("sam", "Select object (MobileSAM)", 40, "$AI_HUB/mobilesam/releases/v0.63.0/mobilesam-tflite-float.zip",
        mapOf("mobilesam-tflite-float/encoder.tflite" to "sam_encoder.tflite", "mobilesam-tflite-float/decoder.tflite" to "sam_decoder.tflite"), licence = "Apache-2.0 (MobileSAM)")
    val LAMA = ModelPack("lama", "Remove objects (LaMa)", 170, "$AI_HUB/lama_dilated/releases/v0.63.0/lama_dilated-tflite-float.zip",
        mapOf("lama_dilated-tflite-float/lama_dilated.tflite" to "lama_dilated.tflite"), licence = "Apache-2.0 (LaMa)")
    val DENOISE = ModelPack("denoise", "AI denoise (NAFNet)", 430, "$AI_HUB/nafnet_denoise/releases/v0.63.0/nafnet_denoise-tflite-float.zip",
        mapOf("nafnet_denoise-tflite-float/nafnet_denoise.tflite" to "nafnet_denoise.tflite"), licence = "MIT (NAFNet)")
    val ALL = listOf(SKY, PEOPLE, SAM, LAMA, DENOISE)
}

data class PackState(val ready: Boolean, val downloading: Boolean = false, val progress: Float = 0f, val error: String? = null)

class ModelStore(context: Context) {
    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val lock = Mutex()
    private val _state = MutableStateFlow(Models.ALL.associate { it.id to PackState(isReady(it)) })
    val state: StateFlow<Map<String, PackState>> = _state
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun file(name: String) = File(dir, name)

    private fun expected(p: ModelPack) = if (p.files.isEmpty()) listOf(p.localName!!) else p.files.values.toList()
    fun isReady(p: ModelPack) = expected(p).all { file(it).let { f -> f.exists() && f.length() > 100_000 } }

    private fun set(p: ModelPack, s: PackState) { _state.value = _state.value + (p.id to s) }

    /** Downloads the pack if needed. Returns true when the files are present afterwards. */
    suspend fun ensure(p: ModelPack): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (isReady(p)) { set(p, PackState(true)); return@withLock true }
            var lastError: String? = null
            repeat(3) { attempt ->
                try {
                    set(p, PackState(false, true, 0f))
                    _message.value = "Downloading ${p.title} (${p.approxMb} MB)"
                    download(p)
                    set(p, PackState(true))
                    _message.value = null
                    PerfLog.record("model_download_${p.id}_ok", attempt.toLong())
                    return@withLock true
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    PerfLog.error("download ${p.id}: $lastError")
                }
            }
            set(p, PackState(false, false, 0f, lastError))
            _message.value = "Could not download ${p.title}: $lastError"
            false
        }
    }

    private fun download(p: ModelPack) {
        var conn = URL(p.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000; conn.readTimeout = 30000
        var redirects = 0
        while (conn.responseCode in 300..399 && redirects++ < 5) {
            val loc = conn.getHeaderField("Location") ?: break
            conn = URL(loc).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000; conn.readTimeout = 30000
        }
        if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: (p.approxMb * 1_000_000L)
        var read = 0L
        val progress = { n: Int -> read += n; set(p, PackState(false, true, (read.toFloat() / total).coerceIn(0f, 1f))) }
        conn.inputStream.use { raw ->
            if (p.files.isEmpty()) {
                val tmp = File(dir, p.localName + ".part")
                tmp.outputStream().use { out -> copy(raw, out, progress) }
                if (!tmp.renameTo(file(p.localName!!))) throw IllegalStateException("rename failed")
            } else {
                ZipInputStream(raw).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val local = p.files[e.name]
                        if (local != null) {
                            val tmp = File(dir, "$local.part")
                            tmp.outputStream().use { out -> copy(z, out, progress) }
                            if (!tmp.renameTo(file(local))) throw IllegalStateException("rename failed")
                        }
                        e = z.nextEntry
                    }
                }
            }
        }
        if (!isReady(p)) throw IllegalStateException("files missing after download")
    }

    private fun copy(i: java.io.InputStream, o: java.io.OutputStream, progress: (Int) -> Unit) {
        val buf = ByteArray(256 * 1024)
        while (true) { val n = i.read(buf); if (n <= 0) break; o.write(buf, 0, n); progress(n) }
    }

    fun delete(p: ModelPack) { expected(p).forEach { file(it).delete() }; set(p, PackState(false)) }
}
