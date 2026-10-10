package dev.glowcow.mnt.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.text.format.Formatter
import dev.glowcow.mnt.MainActivity
import dev.glowcow.mnt.R
import dev.glowcow.mnt.container
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps a transfer going with the app closed: an ongoing notification with its progress and a way to stop it. */
class TransferService : Service() {
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) container.transfers.stop()
        val now = container.transfers.progress.value
        startForeground(NOTIFICATION_ID, notification(now), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (watch == null) {
            watch = container.scope.launch {
                container.transfers.progress.collect { progress ->
                    if (progress == null) return@collect finish()
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(progress))
                    // A state flow hands over only its latest value, so this wait is the pace of the updates.
                    delay(REFRESH_MS)
                }
            }
        }
        return START_NOT_STICKY
    }

    // The system gives a data sync service a few hours a day and then asks it to end.
    override fun onTimeout(startId: Int, fgsType: Int) {
        container.transfers.stop()
        finish()
    }

    override fun onDestroy() {
        watch?.cancel()
        super.onDestroy()
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(progress: TransferProgress?): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.transfer_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TRANSFER, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            NOTIFICATION_ID,
            Intent(this, TransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mnt)
            .setContentTitle(getString((progress?.kind ?: TransferKind.COPY).label))
            .setContentText(progress?.name)
            .setSubText(progress?.speed?.let { getString(R.string.transfer_rate, Formatter.formatShortFileSize(this, it)) })
            .setProgress(100, ((progress?.fraction ?: 0f) * 100).toInt(), progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, getString(R.string.action_stop), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "transfers"
        private const val NOTIFICATION_ID = 3
        private const val ACTION_STOP = "dev.glowcow.mnt.STOP_TRANSFER"
        private const val REFRESH_MS = 500L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TransferService::class.java))
        }
    }
}

/** The word a running transfer goes by. */
val TransferKind.label: Int
    get() = when (this) {
        TransferKind.COPY -> R.string.transfer_copying
        TransferKind.MOVE -> R.string.transfer_moving
        TransferKind.DELETE -> R.string.transfer_deleting
    }
