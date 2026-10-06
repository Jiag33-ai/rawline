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

/**
 * Foreground service so a batch export keeps running when the app is in the background.
 *
 * Android 15 and later give dataSync and mediaProcessing services a time limit and then call [onTimeout]; the service must
 * stop within seconds or the system throws. On timeout or destroy the current job is abandoned and goes back to waiting
 * (never cancelled or failed), and the queue is picked up again the next time the app opens (see ExportRunner.recoverAfterStart).
 * Type: mediaProcessing on Android 15+ (this is image processing), dataSync before that, where mediaProcessing does not exist.
 */
class ExportService : Service() {
    /** True only while this instance's worker thread is alive, so a late onDestroy never stops a newer instance's run. */
    @Volatile private var workerActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        graph().exportRunner.stopForLater()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (workerActive) graph().exportRunner.stopForLater()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runner = graph().exportRunner
        if (intent?.action == ACTION_CANCEL) { runner.cancelCurrent(); return START_NOT_STICKY }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Export queue", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIF_ID, build(0, 0, "Starting"),
            if (android.os.Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY    // already working through the queue; new jobs are picked up
        runner.stopRequested = false
        workerActive = true
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
                do { ok += runner.processQueue() } while (!runner.stopRequested && runBlockingActive())
            } finally {
                watcher.interrupt(); runCatching { watcher.join(1500) }
                running.set(false)
                workerActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                nm.cancel(NOTIF_ID)
                // a job queued after the last check but before running was cleared would otherwise wait for the next export
                if (!runner.stopRequested && runCatching { runBlockingActive() }.getOrDefault(false)) runCatching { androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, ExportService::class.java)) }
            }
            if (ok > 0) nm.notify(NOTIF_ID + 1, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Export finished").setContentText("$ok photos saved").setAutoCancel(true).build())
            stopSelf(startId)
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
        val isRunning: Boolean get() = running.get()
    }
}
