package dev.glowcow.mnt.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class TransferKind { COPY, MOVE, DELETE, ZIP }

/** A copy, a move, a delete or an archive in the making that is running: what it works on now, how far it has got, 0–1, and for bytes how fast, a second. */
data class TransferProgress(val kind: TransferKind, val name: String, val fraction: Float, val speed: Long? = null)

/** How a transfer ended: how many items it could not do, and whether it was stopped. */
data class TransferResult(val kind: TransferKind, val failed: Int, val stopped: Boolean)

/** Files picked in one folder to be put into another, or packed there into an archive called [zip]. */
data class Clip(val paths: List<String>, val move: Boolean, val zip: String? = null)

/** Runs one transfer at a time, outside any screen, and tells what it is doing. */
class TransferManager(private val scope: CoroutineScope, private val files: FileStore, private val trash: Trash) {
    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress

    private val _results = MutableSharedFlow<TransferResult>(extraBufferCapacity = 4)
    val results: SharedFlow<TransferResult> = _results

    /** Grows whenever files may have changed, for the lists that show them. */
    val changes = MutableStateFlow(0)

    /** What waits to be pasted. */
    val clip = MutableStateFlow<Clip?>(null)

    private var job: Job? = null

    /** False when another transfer is running: this one was not started. */
    fun copy(sources: List<String>, dest: String, move: Boolean, conflict: Conflict): Boolean =
        begin(if (move) TransferKind.MOVE else TransferKind.COPY) { copy(sources, dest, move, conflict) }

    fun delete(paths: List<String>): Boolean = begin(TransferKind.DELETE) { delete(paths) }

    /** Deletes into the bin. */
    fun discard(paths: List<String>): Boolean = begin(TransferKind.DELETE) { discard(paths, trash) }

    /** Packs [sources] into the archive [target]. */
    fun zip(sources: List<String>, target: String): Boolean = begin(TransferKind.ZIP) { zip(sources, target) }

    fun stop() {
        job?.cancel()
    }

    private fun begin(kind: TransferKind, work: suspend Transfer.() -> Unit): Boolean {
        if (_progress.value != null) return false
        _progress.value = TransferProgress(kind, "", 0f)
        val meter = Speedometer()
        var told = 0L
        val transfer = Transfer(files) { name, done, total ->
            // A fast drive reports thousands of times a second; a screen needs it a few times.
            val now = System.nanoTime() / 1_000_000
            if (now - told < REPORT_MS && done < total) return@Transfer
            told = now
            val speed = if (kind == TransferKind.DELETE) null else meter.at(now, done)
            _progress.value = TransferProgress(kind, name, if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f, speed)
        }
        job = scope.launch {
            var stopped = false
            var broken = false
            try {
                transfer.work()
            } catch (_: CancellationException) {
                stopped = true
            } catch (_: Exception) {
                // A drive pulled out half way, a file system that gave up: the rest was not done.
                broken = true
            } finally {
                _progress.value = null
                changes.value++
                _results.tryEmit(TransferResult(kind, if (broken) transfer.failed.coerceAtLeast(1) else transfer.failed, stopped))
            }
        }
        return true
    }

    private companion object {
        const val REPORT_MS = 100L
    }
}
