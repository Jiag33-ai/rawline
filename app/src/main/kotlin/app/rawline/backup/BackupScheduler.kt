package app.rawline.backup

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import app.rawline.RawlineApplication
import app.rawline.core.cache.PerfLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Daily and after-edits triggers (W06 D5) on the platform JobScheduler: no extra library, no extra permission. Jobs are not persisted across a
 * reboot (that needs RECEIVE_BOOT_COMPLETED), so [ensure] runs at every app start and puts the daily job back when it is missing.
 */
object BackupScheduler {
    private const val DAILY = 7101
    private const val AFTER_EDITS = 7102

    private fun js(c: Context) = c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    private fun component(c: Context) = ComponentName(c, BackupJobService::class.java)

    /** Daily job on (when [auto]) or off. A job that is already pending is left alone, so its schedule is not pushed back at every start. */
    fun ensure(c: Context, auto: Boolean) {
        runCatching {
            val s = js(c)
            if (!auto) { s.cancel(DAILY); s.cancel(AFTER_EDITS); return }
            if (s.getPendingJob(DAILY) != null) return
            val job = JobInfo.Builder(DAILY, component(c)).setPeriodic(TimeUnit.HOURS.toMillis(24), TimeUnit.HOURS.toMillis(4)).setRequiresBatteryNotLow(true).setPersisted(false).build()
            s.schedule(job)
        }.onFailure { PerfLog.error("backup schedule: ${it.javaClass.simpleName} ${it.message}") }
    }

    /** One run in about 2 minutes; asking again while one is waiting does nothing. */
    fun afterEdits(c: Context) {
        runCatching {
            val s = js(c)
            if (s.getPendingJob(AFTER_EDITS) != null) return
            s.schedule(JobInfo.Builder(AFTER_EDITS, component(c)).setMinimumLatency(TimeUnit.MINUTES.toMillis(2)).setPersisted(false).build())
        }.onFailure { PerfLog.error("backup schedule: ${it.javaClass.simpleName} ${it.message}") }
    }
}

/** Runs [BackupCoordinator] for a scheduled job. The coordinator decides whether a backup is actually due. */
class BackupJobService : JobService() {
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val g = (application as RawlineApplication).graph
        running = g.appScope.launch {
            val failed = try { BackupCoordinator.run(applicationContext, g, manual = false)?.ok == false } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Throwable) { PerfLog.error("backup job: ${e.message}"); true }
            jobFinished(params, false)      // a failure is shown in Settings and tried again by the next trigger
            if (failed) PerfLog.event("scheduled backup failed")
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { running?.cancel(); return true }    // true: run it again later
}
