package app.rawline.core.nativelib

object Native {
    init {
        System.loadLibrary("raw")
        System.loadLibrary("rawline_jni")
    }

    external fun librawVersion(): String
}
