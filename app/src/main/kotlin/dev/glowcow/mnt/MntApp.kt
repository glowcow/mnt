package dev.glowcow.mnt

import android.app.Application
import android.content.Context
import dev.glowcow.mnt.backup.Backup
import dev.glowcow.mnt.data.SettingsStore
import dev.glowcow.mnt.files.FileStore
import dev.glowcow.mnt.files.LocalStore
import dev.glowcow.mnt.files.Search
import dev.glowcow.mnt.files.TransferManager
import dev.glowcow.mnt.files.TransferService
import dev.glowcow.mnt.files.Volumes
import dev.glowcow.mnt.update.AppUpdateScheduler
import dev.glowcow.mnt.update.AppUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val files: FileStore = LocalStore()
    val volumes = Volumes(context)
    val search = Search(files)
    val transfers = TransferManager(scope, files)
    val appUpdater = AppUpdater(context, scope)
    val backup = Backup(context, settings)
}

class MntApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scope.launch {
            container.settings.settings.map { it.appUpdate }.distinctUntilChanged()
                .collect { AppUpdateScheduler.apply(this@MntApp, it && container.appUpdater.supported) }
        }
        // A transfer starts from a screen, so the app is in front and may start the service that keeps it going.
        container.scope.launch {
            container.transfers.progress.map { it != null }.distinctUntilChanged().collect { running ->
                if (running) TransferService.start(this@MntApp)
            }
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as MntApp).container
