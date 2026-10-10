package dev.glowcow.mnt.files

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import dev.glowcow.mnt.container
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Keeps a daily run that empties the bin of what has been there too long, also while the app is not open. */
object TrashScheduler {
    private const val JOB_ID = 4
    private const val DAY_MS = 24 * 3_600_000L

    fun apply(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        // Rescheduling restarts the period, so a pending job is left alone.
        if (scheduler.getPendingJob(JOB_ID) != null) return
        scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, TrashService::class.java))
                .setPeriodic(DAY_MS)
                .setPersisted(true)
                .build(),
        )
    }
}

class TrashService : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = container.scope.launch {
            if (container.trash.purge()) container.transfers.changes.value++
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }
}
