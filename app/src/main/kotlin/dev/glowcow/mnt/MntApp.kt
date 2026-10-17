package dev.glowcow.mnt

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import dev.glowcow.mnt.archive.Archives
import dev.glowcow.mnt.backup.Backup
import dev.glowcow.mnt.data.SettingsStore
import dev.glowcow.mnt.files.FileStore
import dev.glowcow.mnt.files.LocalStore
import dev.glowcow.mnt.files.MediaIndex
import dev.glowcow.mnt.files.Roots
import dev.glowcow.mnt.files.Search
import dev.glowcow.mnt.files.Stores
import dev.glowcow.mnt.files.TransferManager
import dev.glowcow.mnt.files.TransferService
import dev.glowcow.mnt.files.Trash
import dev.glowcow.mnt.files.TrashScheduler
import dev.glowcow.mnt.files.Volumes
import dev.glowcow.mnt.net.Connections
import dev.glowcow.mnt.net.MtpDevices
import dev.glowcow.mnt.net.Remotes
import dev.glowcow.mnt.ui.files.FolderViewModel
import dev.glowcow.mnt.update.AppUpdateScheduler
import dev.glowcow.mnt.update.AppUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import kotlin.concurrent.thread

class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val volumes = Volumes(context)
    val connections = Connections(context)
    val mtp = MtpDevices(context)
    val remotes = Remotes(connections, mtp)
    val archives = Archives({ files }, { spare(context) }, File(context.cacheDir, "archives"))
    val files: FileStore = Stores(LocalStore(MediaIndex(context)::added)) { id -> if (Archives.owns(id)) archives.store(id) else remotes.store(id) }
    val roots = Roots(volumes, remotes)
    val search = Search(files)
    val trash = Trash(files, { volumes.list.map { it.path } })
    val transfers = TransferManager(scope, files, trash)
    val appUpdater = AppUpdater(context, scope)
    val backup = Backup(context, settings)
    val haptics = Haptics(context.applicationContext)
    val cache: File = context.cacheDir

    private var purging: Job? = null

    // A decoder may take a quarter of the memory the phone has free.
    private fun spare(context: Context): Long =
        ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.availMem / 4

    /** Empties the bin of what has been there too long; the lists read again when anything went. */
    fun purge() {
        if (purging?.isActive == true) return
        purging = scope.launch { if (trash.purge()) transfers.changes.value++ }
    }
}

class MntApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // The PDF renderer runs in an isolated process of the app, which may not touch the rest of it.
        if (android.os.Process.isIsolated()) return
        dev.glowcow.mnt.ui.theme.FileIcons.init(this)
        container = AppContainer(this)
        container.scope.launch {
            container.settings.settings.map { it.appUpdate }.distinctUntilChanged()
                .collect { AppUpdateScheduler.apply(this@MntApp, it && container.appUpdater.supported) }
        }
        TrashScheduler.apply(this)
        // What was copied out of archives for other apps is theirs only while they show it.
        thread(name = "opened-clear") { File(cacheDir, FolderViewModel.OPENED).deleteRecursively() }
        // A transfer starts from a screen, so the app is in front and may start the service that keeps it going.
        container.scope.launch {
            container.transfers.progress.map { it != null }.distinctUntilChanged().collect { running ->
                if (running) TransferService.start(this@MntApp)
            }
        }
        // A transfer the user waited for is felt when it ends, not when they stopped it themselves.
        container.scope.launch {
            container.transfers.results.collect { if (!it.stopped && it.took >= Haptics.WAIT_MS) container.haptics.done(it.failed == 0 && !it.full) }
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as MntApp).container
