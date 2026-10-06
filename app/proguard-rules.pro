# Used only when the release build is minified (-PminifyRelease=true). Minifying is OFF by default, see docs/DECISIONS.md.

# JNI: the C++ side finds these by their exact names (Java_app_rawline_core_nativelib_Native_*). Nothing in C++ calls back
# into Kotlin by name (no FindClass or GetMethodID), so only the native declarations and their class must keep their names.
-keep class app.rawline.core.nativelib.Native { native <methods>; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# Enums are saved by name (recipe JSON, export settings, model delegate choice) and read back with valueOf.
-keepclassmembers enum app.rawline.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Room finds its generated implementation by name; entities and DAOs are referenced by generated code.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface *

# Manifest components and the service the system instantiates.
-keep class app.rawline.RawlineApplication
-keep class app.rawline.ExportService

# Readable stack traces in the Copy report need line numbers; ship mapping.txt with the release to decode names.
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
