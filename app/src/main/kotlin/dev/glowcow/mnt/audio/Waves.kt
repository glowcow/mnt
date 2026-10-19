package dev.glowcow.mnt.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.LruCache
import dev.glowcow.mnt.files.FileStore
import dev.glowcow.mnt.files.Remote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * How loud a track is along its length, for the bar of time: [BINS] values 0–1, made by decoding it once and kept in
 * [dir]. A long track is heard at [BINS] places only, so a server's file is not read whole.
 */
class Waves(private val files: FileStore, private val cache: AudioCache, private val dir: File) {
    private val known = LruCache<String, FloatArray>(64)
    private val one = Mutex()

    fun known(id: String): FloatArray? = known[id]

    suspend fun of(id: String): FloatArray? {
        known[id]?.let { return it }
        if (Sounds.web(id)) return null
        return one.withLock {
            known[id]?.let { return@withLock it }
            withContext(Dispatchers.Default) {
                val node = files.stat(id) ?: return@withContext null
                val kept = File(dir, key("$KIND\u0000$id\u0000${node.size}\u0000${node.modified}"))
                val wave = runCatching { kept.readBytes() }.getOrNull()?.takeIf { it.size == BINS }?.let { b -> FloatArray(BINS) { (b[it].toInt() and 0xFF) / 255f } }
                    ?: measure(id, node.size)?.also { w ->
                        dir.mkdirs()
                        runCatching { kept.writeBytes(ByteArray(BINS) { (w[it] * 255).toInt().toByte() }) }
                    }
                wave
            }?.also { known.put(id, it) }
        }
    }

    private suspend fun measure(path: String, size: Long): FloatArray? {
        val extractor = MediaExtractor()
        var source: MediaDataSource? = null
        var codec: MediaCodec? = null
        try {
            if (Remote.id(path) == null) {
                extractor.setDataSource(path)
            } else {
                source = cache.media(path, size)
                extractor.setDataSource(source)
            }
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            val format = extractor.getTrackFormat(track)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else return null
            if (duration <= 0) return null
            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val sums = DoubleArray(BINS)
            val counts = IntArray(BINS)
            // A long track is heard for a moment at each of the places its bars stand for.
            val sampled = duration > WHOLE_US
            val glance = duration / BINS
            var bin = 0
            var heardFrom = Long.MIN_VALUE
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            if (sampled) extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val at = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (at >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(at)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(at, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(at, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (out >= 0) {
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val floats = codec.outputFormat.let { it.containsKey(MediaFormat.KEY_PCM_ENCODING) && it.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT }
                        val b = ((info.presentationTimeUs * BINS) / duration).toInt().coerceIn(0, BINS - 1)
                        add(buffer, floats, sums, counts, b)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (end) break
                    if (sampled && info.presentationTimeUs - heardFrom >= GLANCE_US && heardFrom != Long.MIN_VALUE) {
                        // Heard enough of this place: on to the next one.
                        bin++
                        if (bin >= BINS) break
                        codec.flush()
                        extractor.seekTo(bin * glance, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        inputDone = false
                        heardFrom = Long.MIN_VALUE
                    } else if (sampled && heardFrom == Long.MIN_VALUE && info.size > 0) {
                        heardFrom = info.presentationTimeUs
                    }
                }
            }
            val rms = FloatArray(BINS) { if (counts[it] > 0) sqrt(sums[it] / counts[it]).toFloat() else 0f }
            // Bins that heard nothing take their neighbour's level.
            for (i in 1 until BINS) if (counts[i] == 0) rms[i] = rms[i - 1]
            // Loudness as heard, to the loudest part but for a stray peak: a quiet verse stands well below a chorus.
            val top = rms.sorted()[(BINS * TOP_AT).toInt()].takeIf { it > 0f } ?: return FloatArray(BINS)
            return FloatArray(BINS) { (rms[it] / top).coerceAtMost(1f) }
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
            source?.close()
        }
    }

    private fun add(buffer: ByteBuffer, floats: Boolean, sums: DoubleArray, counts: IntArray, bin: Int) {
        var sum = 0.0
        var n = 0
        if (floats) {
            val f = buffer.asFloatBuffer()
            while (f.hasRemaining()) {
                val v = f.get().toDouble()
                sum += v * v
                n++
            }
        } else {
            val s = buffer.asShortBuffer()
            while (s.hasRemaining()) {
                val v = s.get() / 32768.0
                sum += v * v
                n++
            }
        }
        sums[bin] += sum
        counts[bin] += n
    }

    private fun key(text: String): String = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val BINS = 160

        // What a kept wave was measured by, for one measured another way to be made again; and the part of the bins below the top.
        private const val KIND = "rms"
        private const val TOP_AT = 0.98f
        private const val TIMEOUT_US = 10_000L

        // Up to this long a track is decoded whole; past it, a moment at each bar.
        private const val WHOLE_US = 15 * 60 * 1_000_000L
        private const val GLANCE_US = 300_000L
    }
}
