package app.rawline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

/** Foreground service so a batch export keeps running when the app is in the background. */
class ExportService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runner = graph().exportRunner
        if (intent?.action == ACTION_CANCEL) { runner.cancelCurrent(); return START_NOT_STICKY }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Export queue", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIF_ID, build(0, 0, "Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY    // already working through the queue; new jobs are picked up
        thread(name = "export") {
            val watcher = thread(name = "export-progress") {
                while (!Thread.currentThread().isInterrupted) {
                    val p = runner.progress.value
                    if (p.running) nm.notify(NOTIF_ID, build(p.done, p.total, p.current))
                    try { Thread.sleep(700) } catch (e: InterruptedException) { break }
                }
            }
            var ok = 0
            try {
                // jobs added while working are picked up by the loop; look again once more before stopping
                do { ok += runner.processQueue() } while (runBlockingActive())
            } finally {
                watcher.interrupt(); runCatching { watcher.join(1500) }
                running.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                nm.cancel(NOTIF_ID)
            }
            if (ok > 0) nm.notify(NOTIF_ID + 1, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Export finished").setContentText("$ok photos saved").setAutoCancel(true).build())
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun runBlockingActive() = kotlinx.coroutines.runBlocking { graph().db.exports().nextWaiting() != null }

    private fun graph() = (application as RawlineApplication).graph

    private fun build(done: Int, total: Int, name: String): Notification {
        val cancel = android.app.PendingIntent.getService(this, 0, Intent(this, ExportService::class.java).setAction(ACTION_CANCEL), android.app.PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Exporting photos").setContentText("$done of $total  $name").setOngoing(true)
            .setProgress(total, done, total == 0).addAction(0, "Cancel", cancel).build()
    }

    companion object {
        const val ACTION_CANCEL = "app.rawline.CANCEL_EXPORT"
        private const val CHANNEL = "export"
        private const val NOTIF_ID = 42
        private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
