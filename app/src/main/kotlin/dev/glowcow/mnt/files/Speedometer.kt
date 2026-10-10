package dev.glowcow.mnt.files

/** The rate of a transfer over its last few seconds, told from how much was done by which moment. */
class Speedometer(private val windowMs: Long = 3_000) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** Bytes a second at [timeMs] with [done] bytes behind; null until there is half a second to tell by. */
    fun at(timeMs: Long, done: Long): Long? {
        samples.addLast(timeMs to done)
        while (samples.size > 2 && timeMs - samples[1].first >= windowMs) samples.removeFirst()
        val (since, before) = samples.first()
        val span = timeMs - since
        return if (span >= MIN_SPAN_MS) (done - before) * 1000 / span else null
    }

    private companion object {
        const val MIN_SPAN_MS = 500L
    }
}
