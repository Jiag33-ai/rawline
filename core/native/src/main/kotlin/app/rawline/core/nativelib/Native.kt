package app.rawline.core.nativelib

object Native {
    init {
        System.loadLibrary("raw")
        System.loadLibrary("rawline_jni")
    }

    external fun librawVersion(): String

    /** Returns [offset, length, tiffOrientation] of the biggest embedded JPEG, or null. */
    external fun findPreview(fd: Int): LongArray?

    /** pread() of exactly [len] bytes at [off]; null if the file is shorter. */
    external fun readBytes(fd: Int, off: Long, len: Int): ByteArray?
}
