package app.rawline.ingest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.rawline.MainActivity
import app.rawline.RawlineApplication
import app.rawline.core.cache.PerfLog
import app.rawline.core.data.ingest.CopyEngine
import app.rawline.core.data.ingest.ImportLedger
import app.rawline.core.data.ingest.ImportReport
import app.rawline.core.data.ingest.LinkSpeed
import app.rawline.core.data.ingest.Outcome
import app.rawline.core.data.ingest.SpeedClass
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Copies the RAW files of a card into `DCIM/Rawline/<date>/` (plain files: needs All files access, which Settings already links to) with the
 * engine in core/data/ingest. Foreground service of type dataSync (W15 D8) with a progress notification and a Cancel action; finished files are
 * kept on cancel, and a rerun of the same card carries on. On the dataSync time limit (Android 15) it stops and says so. The card is never written to.
 */
class ImportService : Service() {
    private val cancel = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {      // Android 15 time limit for dataSync: stop within seconds; what was copied stays, a rerun carries on
        cancel.set(true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancel.set(true)
            if (!running.get()) stopSelf(startId)                                  // a stale Cancel tap must not leave an idle service
            return START_NOT_STICKY
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Card import", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIF_ID, build("Reading the card", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val tree = intent?.getStringExtra(EXTRA_TREE)?.let { Uri.parse(it) }
        if (tree == null || !running.compareAndSet(false, true)) { if (tree == null) stopSelf(startId); return START_NOT_STICKY }
        cancel.set(false)
        thread(name = "card-import") {
            var result: String
            try { result = work(nm, tree) } catch (e: Throwable) { PerfLog.error("card import: ${e.javaClass.simpleName} ${e.message}"); result = "Import stopped: ${e.message ?: e.javaClass.simpleName}" }
            finally { running.set(false) }
            stopForeground(STOP_FOREGROUND_REMOVE); nm.cancel(NOTIF_ID)
            nm.notify(NOTIF_ID + 1, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Card import").setContentText(result).setStyle(NotificationCompat.BigTextStyle().bigText(result)).setContentIntent(openApp()).setAutoCancel(true).build())
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun work(nm: NotificationManager, tree: Uri): String {
        if (!Environment.isExternalStorageManager()) return "Allow All files access in Settings first. Rawline needs it to save the photos in DCIM/Rawline."
        val files = SafCardSource(contentResolver, tree).list()
        if (files.isEmpty()) return "No RAW photos were found on the card."
        val dest = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Rawline"), LocalDate.now().toString())
        val ledger = ImportLedger.load(File(filesDir, "import-ledger.txt"))
        val total = files.sumOf { maxOf(it.size, 0L) }
        val t0 = System.nanoTime()
        var doneBytes = 0L
        val report = CopyEngine(dest, ledger).run(files, cancelled = { cancel.get() }, progress = { done, n ->
            doneBytes = files.take(done).sumOf { maxOf(it.size, 0L) }
            val secs = (System.nanoTime() - t0) / 1e9
            val rate = if (secs >= 2 && doneBytes > 0) doneBytes / 1048576.0 / secs else 0.0
            val slow = rate > 0 && SpeedClass.classify(rate) == LinkSpeed.SLOW
            val eta = SpeedClass.etaSeconds(total - doneBytes, rate)
            val text = if (slow && eta > 0) "Slow reader or card, this will take about ${maxOf(1, (eta + 59) / 60)} minutes" else "$done of $n"
            if (done < n) nm.notify(NOTIF_ID, build(text, done, n))
        })
        val copied = report.results.filter { it.outcome == Outcome.COPIED }.map { File(dest, it.name).path }
        if (copied.isNotEmpty()) MediaScannerConnection.scanFile(this, copied.toTypedArray(), null, null)      // so the library finds them (its MediaStore observer then rescans)
        remember(report)
        return report.summary()
    }

    /** The text the Copy report shows for the last run (the numbers come from the run itself). */
    private fun remember(r: ImportReport) {
        val g = (application as RawlineApplication).graph
        g.prefs.edit().putString(PREF_LAST, r.reportText()).putLong(PREF_LAST_AT, System.currentTimeMillis()).apply()
        PerfLog.event("card import: ${r.summary()}")
    }

    private fun openApp() = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun build(text: String, done: Int, total: Int): Notification {
        val stop = PendingIntent.getService(this, 3, Intent(this, ImportService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Importing from the card").setContentText(text)
            .setOngoing(true).setContentIntent(openApp()).setProgress(total, done, total == 0).addAction(0, "Cancel", stop).build()
    }

    companion object {
        const val ACTION_CANCEL = "app.rawline.CANCEL_IMPORT"
        const val EXTRA_TREE = "tree"
        const val PREF_LAST = "import_last"
        const val PREF_LAST_AT = "import_last_at"
        private const val CHANNEL = "import"
        private const val NOTIF_ID = 52
        private val running = AtomicBoolean(false)
        val isRunning: Boolean get() = running.get()

        fun start(c: Context, tree: Uri) = androidx.core.content.ContextCompat.startForegroundService(c, Intent(c, ImportService::class.java).putExtra(EXTRA_TREE, tree.toString()))
    }
}
