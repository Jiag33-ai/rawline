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
    /** SHA-256 of the downloaded file (the zip, for zip packs). Null only for a link that is not versioned. */
    val sha256: String? = null,
)

object Models {
    private const val AI_HUB = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models"
    val SKY = ModelPack("sky", "Sky selection (SegFormer-B0, ADE20K)", 14, "$AI_HUB/segformer_base/releases/v0.63.0/segformer_base-tflite-float.zip",
        mapOf("segformer_base-tflite-float/segformer_base.tflite" to "segformer_base.tflite"), licence = "Apache-2.0 (weights: NVIDIA SegFormer, ADE20K)", sha256 = "b0e33bf0f570d7914cef1aeac32cf29ddaaf2a7a8f8d7c1bbbfd79c4b5ae8f91")
    val PEOPLE = ModelPack("people", "People parts (MediaPipe selfie multiclass)", 16, "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite",
        emptyMap(), localName = "selfie_multiclass.tflite", licence = "Apache-2.0 (Google MediaPipe)")
    val SAM = ModelPack("sam", "Select object (MobileSAM)", 40, "$AI_HUB/mobilesam/releases/v0.63.0/mobilesam-tflite-float.zip",
        mapOf("mobilesam-tflite-float/encoder.tflite" to "sam_encoder.tflite", "mobilesam-tflite-float/decoder.tflite" to "sam_decoder.tflite"), licence = "Apache-2.0 (MobileSAM)", sha256 = "45e320f177fe4ce552b47f0ef47e5b071f1942cb3116e97fd4396efc7a7d956e")
    val LAMA = ModelPack("lama", "Remove objects (LaMa)", 170, "$AI_HUB/lama_dilated/releases/v0.63.0/lama_dilated-tflite-float.zip",
        mapOf("lama_dilated-tflite-float/lama_dilated.tflite" to "lama_dilated.tflite"), licence = "Apache-2.0 (LaMa)", sha256 = "dfa642cdbf04d04d8968752f4b90cae9e7a3219da3a8512a855fcf596d6c808f")
    val DENOISE = ModelPack("denoise", "AI denoise (NAFNet)", 430, "$AI_HUB/nafnet_denoise/releases/v0.63.0/nafnet_denoise-tflite-float.zip",
        mapOf("nafnet_denoise-tflite-float/nafnet_denoise.tflite" to "nafnet_denoise.tflite"), licence = "MIT (NAFNet)", sha256 = "07fb400df30fe54f0a4c51c3d790a82768953c9c77c86695046ebe2d82e8be03")
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
        fun open(u: String): HttpURLConnection {
            require(u.startsWith("https://")) { "Refusing a non-https model link" }
            return (URL(u).openConnection() as HttpURLConnection).apply { connectTimeout = 20000; readTimeout = 30000; instanceFollowRedirects = false }
        }
        var conn = open(p.url)
        var redirects = 0
        while (conn.responseCode in 300..399 && redirects++ < 5) {
            val loc = conn.getHeaderField("Location") ?: break
            conn = open(URL(URL(p.url), loc).toString())
        }
        if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: (p.approxMb * 1_000_000L)
        var read = 0L
        val progress = { n: Int -> read += n; set(p, PackState(false, true, (read.toFloat() / total).coerceIn(0f, 1f))) }
        val declared = conn.contentLengthLong
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val parts = ArrayList<Pair<File, File>>()   // finished .part file -> final name, renamed only after the checksum matches
        try {
            conn.inputStream.use { rawIn ->
                val raw = java.security.DigestInputStream(rawIn, digest)
                if (p.files.isEmpty()) {
                    val tmp = File(dir, p.localName + ".part")
                    tmp.outputStream().use { out -> copy(raw, out, progress) }
                    parts += tmp to file(p.localName!!)
                } else {
                    ZipInputStream(raw).use { z ->
                        var e = z.nextEntry
                        while (e != null) {
                            val local = p.files[e.name]
                            if (local != null) {
                                val tmp = File(dir, "$local.part")
                                tmp.outputStream().use { out -> copy(z, out, progress) }
                                parts += tmp to file(local)
                            }
                            e = z.nextEntry
                        }
                        copy(raw, DiscardStream) {}   // the checksum covers the whole zip, including its trailer
                    }
                }
            }
            val received = parts.sumOf { it.first.length() }
            if (p.files.isEmpty() && declared > 0 && received != declared) throw IllegalStateException("download cut short ($received of $declared bytes)")
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (p.sha256 != null && !got.equals(p.sha256, ignoreCase = true)) throw IllegalStateException("download does not match the expected checksum")
            parts.forEach { (tmp, final) -> if (!tmp.renameTo(final)) throw IllegalStateException("rename failed") }
        } catch (e: Exception) {
            // never leave half written files behind: they would count as ready models on the next launch
            dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
            throw e
        }
        if (!isReady(p)) throw IllegalStateException("files missing after download")
    }

    /** OutputStream.nullOutputStream() needs API 33 and minSdk is 31 (it crashed a model download on Android 12). */
    private object DiscardStream : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    private fun copy(i: java.io.InputStream, o: java.io.OutputStream, progress: (Int) -> Unit) {
        val buf = ByteArray(256 * 1024)
        while (true) { val n = i.read(buf); if (n <= 0) break; o.write(buf, 0, n); progress(n) }
    }

    fun delete(p: ModelPack) { expected(p).forEach { file(it).delete() }; set(p, PackState(false)) }
}
