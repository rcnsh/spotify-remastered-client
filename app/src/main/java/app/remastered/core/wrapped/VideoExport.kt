package app.remastered.core.wrapped

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The pure parts of the MP4 export (web exporter.ts and soundtrack.ts): frame timing, the
 * soundtrack's hook and its volume envelope. The soundtrack is always a file the listener picks;
 * Spotify's audio never goes into an export.
 */
object VideoExport {
    const val WIDTH = WrappedTimeline.WIDTH
    const val HEIGHT = WrappedTimeline.HEIGHT
    const val FPS = WrappedTimeline.FPS

    /** Presentation time of [frame] in microseconds. Exact for every frame, no drift. */
    fun frameTimeUs(frame: Int): Long = frame * 1_000_000L / FPS

    /** Length of a [frames]-frame video in microseconds. */
    fun durationUs(frames: Int): Long = frameTimeUs(frames)

    /** The frame showing at [timeUs]. */
    fun frameAt(timeUs: Long): Int = floor(timeUs * FPS / 1_000_000.0).toInt()

    /** Audio samples (per channel) that cover [frames] frames at [sampleRate]. */
    fun samplesFor(frames: Int, sampleRate: Int): Long = (frames.toLong() * sampleRate + FPS - 1) / FPS

    /** Time of the [sample]th sample (per channel) in microseconds. */
    fun sampleTimeUs(sample: Long, sampleRate: Int): Long = sample * 1_000_000L / sampleRate

    /**
     * Where the song starts, as the web story trims it: Remotion's `trimBefore` is a whole number
     * of frames, so the hook start is rounded to a frame.
     */
    fun trimStartUs(hookStartSeconds: Double): Long = frameTimeUs((hookStartSeconds * FPS).roundToInt())

    /**
     * The soundtrack's volume at [frame] (fractional, for per-sample gain) of a [frames]-frame
     * video: up to 0.9 over 20 frames, held, then down to silence over the last 45 (web Wrapped.tsx).
     */
    fun volumeAt(frame: Double, frames: Int): Double = interpolate(
        frame,
        doubleArrayOf(0.0, 20.0, (frames - 45).toDouble(), frames.toDouble()),
        doubleArrayOf(0.0, 0.9, 0.9, 0.0),
        left = Extrapolate.Clamp,
        right = Extrapolate.Clamp,
    )

    /** Scales interleaved 16-bit [pcm] in place, [firstSample] being its first per-channel index. */
    fun applyEnvelope(pcm: ShortArray, count: Int, channels: Int, firstSample: Long, sampleRate: Int, frames: Int) {
        var i = 0
        while (i < count) {
            val sample = firstSample + i / channels
            val gain = volumeAt(sample.toDouble() * FPS / sampleRate, frames)
            for (c in 0 until channels) {
                if (i + c >= count) break
                pcm[i + c] = (pcm[i + c] * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            i += channels
        }
    }
}

/** Finds a song's hook from its loudness, like the web soundtrack.ts. */
object Hook {
    /** RMS energy is measured in windows this long. */
    const val WINDOW_SECONDS = 0.5
    /** How much of the song after a candidate start must be loud for it to count as the hook. */
    const val HOOK_SECONDS = 20.0

    /**
     * The start (seconds) whose next [HOOK_SECONDS] are loudest, among starts that still leave the
     * whole video covered when the song is long enough to. [energy] is mean square per window.
     */
    fun start(energy: DoubleArray, durationSeconds: Double, videoSeconds: Double): Double {
        val hookWindows = (HOOK_SECONDS / WINDOW_SECONDS).roundToInt()
        val latest = max(0.0, durationSeconds - videoSeconds)
        val lastStart = min(energy.size - hookWindows, floor(latest / WINDOW_SECONDS).toInt())
        if (lastStart <= 0) return 0.0
        fun at(i: Int) = energy.getOrElse(i) { 0.0 }
        var windowSum = 0.0
        for (i in 0 until hookWindows) windowSum += at(i)
        var best = 0
        var bestSum = windowSum
        for (start in 1..lastStart) {
            windowSum += at(start + hookWindows - 1) - at(start - 1)
            if (windowSum > bestSum) {
                bestSum = windowSum
                best = start
            }
        }
        return best * WINDOW_SECONDS
    }
}

/**
 * Mean square energy per [Hook.WINDOW_SECONDS] window of the first channel, fed decoded PCM in
 * order. Windows are placed by each buffer's timestamp, as the web version does.
 */
class EnergyMeter {
    private val windows = ArrayList<Double>()
    private var sum = 0.0
    private var count = 0
    private var windowEndUs = (Hook.WINDOW_SECONDS * 1_000_000).roundToLong()

    /** Adds [count] interleaved 16-bit samples starting at [timestampUs]. */
    fun add(pcm: ShortArray, count: Int, channels: Int, sampleRate: Int, timestampUs: Long) {
        val step = (Hook.WINDOW_SECONDS * 1_000_000).roundToLong()
        var i = 0
        var n = 0L
        while (i < count) {
            val t = timestampUs + n * 1_000_000L / sampleRate
            while (t >= windowEndUs) {
                windows += if (this.count > 0) sum / this.count else 0.0
                sum = 0.0
                this.count = 0
                windowEndUs += step
            }
            val s = pcm[i] / 32768.0
            sum += s * s
            this.count++
            i += channels
            n++
        }
    }

    fun energy(): DoubleArray = (if (count > 0) windows + sum / count else windows).toDoubleArray()
}
