package app.rawline.core.studio.model

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** [Fs] on real files under [base]. [write] syncs before returning, so a renamed file is complete even after a power cut. */
class JavaFs(private val base: File) : Fs {
    private fun f(path: String) = File(base, path)

    override fun exists(path: String) = f(path).exists()
    override fun read(path: String): ByteArray? = f(path).takeIf { it.isFile }?.readBytes()

    override fun write(path: String, data: ByteArray) {
        val file = f(path)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(data); it.fd.sync() }
    }

    override fun rename(from: String, to: String) {
        Files.move(f(from).toPath(), f(to).toPath(), StandardCopyOption.ATOMIC_MOVE)   // rename(2): replaces the target atomically
    }

    override fun delete(path: String) { f(path).delete() }
    override fun list(dir: String): List<String> = f(dir).list()?.toList() ?: emptyList()
}
