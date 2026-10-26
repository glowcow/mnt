package dev.glowcow.mnt.video

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Where each video was left, for it to start there when opened again; the latest [KEPT] are kept. */
class Resume(context: Context) {
    private val file = File(context.filesDir, "video-positions.json")

    @Serializable
    private data class Spot(val path: String, val at: Long, val length: Long)

    private val spots: MutableList<Spot> by lazy {
        runCatching { json.decodeFromString<List<Spot>>(file.readText()) }.getOrNull().orEmpty().toMutableList()
    }

    /** Where to start [path]: where it was left, unless that was at its very start or end. */
    @Synchronized
    fun at(path: String): Long = spots.firstOrNull { it.path == path }?.at ?: 0L

    /** Keeps [at] of [length] for [path]; near its start or end the video is taken as not begun, or as seen. */
    @Synchronized
    fun keep(path: String, at: Long, length: Long) {
        spots.removeAll { it.path == path }
        val kept = length > 0 && at > EDGE_MS && at < length - EDGE_MS
        if (kept) spots.add(0, Spot(path, at, length))
        while (spots.size > KEPT) spots.removeAt(spots.lastIndex)
        runCatching { file.writeText(json.encodeToString(spots.toList())) }
    }

    private companion object {
        const val KEPT = 500
        const val EDGE_MS = 30_000L
        val json = Json { ignoreUnknownKeys = true }
    }
}
