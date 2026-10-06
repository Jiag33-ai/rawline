package app.rawline.core.cache

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock

/** Facts about the phone that change how fast Rawline runs or whether it works. No identifiers, no paths. */
object DeviceReport {
    fun thermalName(s: Int) = when (s) {
        0 -> "none"; 1 -> "light"; 2 -> "moderate"; 3 -> "severe"; 4 -> "critical"; 5 -> "emergency"; 6 -> "shutdown"
        else -> "unknown ($s)"
    }

    fun describe(context: Context): String = buildString {
        fun line(s: String) { appendLine(s) }
        line("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ABI ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
        runCatching {
            val up = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000
            line("App process running for: ${up / 60} min ${up % 60} s")
        }
        val rt = Runtime.getRuntime()
        line("Java heap: ${ReportText.mb(rt.totalMemory() - rt.freeMemory())} used of ${ReportText.mb(rt.maxMemory())} max; native heap ${ReportText.mb(Debug.getNativeHeapAllocatedSize())}")
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            line("Phone memory: ${ReportText.mb(mi.availMem)} free of ${ReportText.mb(mi.totalMem)}, low memory flag ${mi.lowMemory}, threshold ${ReportText.mb(mi.threshold)}")
            line("Heap class: ${am.memoryClass} MB normal, ${am.largeMemoryClass} MB large")
        }
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            line("Thermal: ${thermalName(pm.currentThermalStatus)}; battery saver ${if (pm.isPowerSaveMode) "ON" else "off"}")
        }
        runCatching {
            val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (b != null) {
                val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                line("Battery: $level%${if (charging) " charging" else ""}, ${b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0} C")
            }
        }
        runCatching {
            val fs = StatFs(context.filesDir.path)
            line("Storage: ${"%.1f".format(fs.availableBytes / 1e9)} GB free of ${"%.1f".format(fs.totalBytes / 1e9)} GB")
        }
        fun granted(p: String) = if (context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) "yes" else "NO"
        line("Permissions: photos ${granted(Manifest.permission.READ_MEDIA_IMAGES)}, all files ${if (runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)) "yes" else "NO"}, notifications ${granted(Manifest.permission.POST_NOTIFICATIONS)}")
    }.trimEnd()
}
