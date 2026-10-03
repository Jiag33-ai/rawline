package app.rawline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.rawline.core.model.Photo
import app.rawline.core.render.ExportSettings
import kotlin.concurrent.thread

/** Foreground service so a batch export keeps running when the app is in the background. */
class ExportService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { graph().exportRunner.cancelled = true; return START_NOT_STICKY }
        val job = pending ?: run { stopSelf(); return START_NOT_STICKY }
        pending = null
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Export", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIF_ID, build(0, job.photos.size, "Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val runner = graph().exportRunner
        thread(name = "export") {
            // keep the notification in step with the runner's progress
            val watcher = thread(name = "export-progress") {
                while (!Thread.currentThread().isInterrupted) {
                    val p = runner.progress.value
                    if (p.running) nm.notify(NOTIF_ID, build(p.done, p.total, p.current))
                    try { Thread.sleep(700) } catch (e: InterruptedException) { break }
                }
            }
            val ok = runner.exportAll(job.photos, job.settings)
            watcher.interrupt()
            stopForeground(STOP_FOREGROUND_REMOVE)
            val done = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Export finished").setContentText("$ok of ${job.photos.size} photos saved").setAutoCancel(true).build()
            nm.notify(NOTIF_ID + 1, done)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun graph() = (application as RawlineApplication).graph

    private fun build(done: Int, total: Int, name: String): Notification {
        val cancel = android.app.PendingIntent.getService(this, 0, Intent(this, ExportService::class.java).setAction(ACTION_CANCEL), android.app.PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Exporting photos").setContentText("$done of $total  $name").setOngoing(true)
            .setProgress(total, done, total == 0).addAction(0, "Cancel", cancel).build()
    }

    class Job(val photos: List<Photo>, val settings: ExportSettings)

    companion object {
        const val ACTION_CANCEL = "app.rawline.CANCEL_EXPORT"
        private const val CHANNEL = "export"
        private const val NOTIF_ID = 42
        @Volatile var pending: Job? = null
    }
}
