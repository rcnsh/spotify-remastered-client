package app.remastered.core.wrapped

import app.remastered.core.warehouse.Wrapped
import kotlin.math.max

/** The story's slides, in order (web: remotion/timeline.ts `SlideId`). */
enum class SlideId(val key: String, val seconds: Double, val supports: (Wrapped) -> Boolean) {
    Intro("intro", 5.0, { true }),
    Minutes("minutes", 6.5, { it.totals.minutes > 0 }),
    Clock("clock", 6.0, { d -> d.hours.any { it > 0 } }),
    ArtistTease("artist-tease", 3.0, { it.topArtists.isNotEmpty() }),
    Artist("artist", 6.5, { it.topArtists.isNotEmpty() }),
    Artists("artists", 6.5, { it.topArtists.size >= 3 }),
    Song("song", 6.5, { it.topSongs.isNotEmpty() }),
    Songs("songs", 8.0, { it.topSongs.size >= 3 }),
    Album("album", 5.5, { it.topAlbums.isNotEmpty() }),
    Age("age", 6.5, { it.listeningAge != null }),
    // When the range starts with the history, everything is "new".
    Discoveries("discoveries", 6.5, { it.discoveries.artists > 0 && it.discoveries.share < 0.9 }),
    Months("months", 8.0, { it.months.size >= 3 }),
    Streak("streak", 5.5, { (it.streak?.days ?: 0) >= 3 }),
    Summary("summary", 9.0, { it.topArtists.isNotEmpty() }),
}

/** A slide placed on the story's frame line. */
data class PlacedSlide(
    val id: SlideId,
    /** Its position among the slides this story shows. */
    val index: Int,
    /** First frame of this slide: its entrance transition starts here, over the previous slide's last frames. */
    val from: Int,
    val durationInFrames: Int,
) {
    /** One past its last frame. */
    val end get() = from + durationInFrames
    fun contains(frame: Int) = frame >= from && frame < end
}

/**
 * The slides [Wrapped] data supports and where each starts once transitions overlap them (web:
 * `timeline()`). Every frame of the story is a pure function of this and the frame number.
 */
class WrappedTimeline private constructor(val slides: List<PlacedSlide>, val durationInFrames: Int) {

    /**
     * The slide being shown at [frame]: the last one to have started (web Story.tsx). During a
     * transition that's the incoming slide.
     */
    fun indexAt(frame: Int): Int = max(0, slides.indexOfLast { frame >= it.from })

    fun slideAt(frame: Int): PlacedSlide = slides[indexAt(frame)]

    /** First frame of the slide at [index], clamped to the story. */
    fun startOf(index: Int): Int = slides[index.coerceIn(0, slides.lastIndex)].from

    /** How far through its progress bar the slide at [index] is at [frame], 0..1 (web Story.tsx bars). */
    fun barFill(index: Int, frame: Int): Double {
        val current = indexAt(frame)
        return when {
            index < current -> 1.0
            index > current -> 0.0
            else -> {
                val slide = slides[index]
                val end = slides.getOrNull(index + 1)?.from ?: durationInFrames
                ((frame - slide.from).toDouble() / (end - slide.from)).coerceIn(0.0, 1.0)
            }
        }
    }

    /** Slides drawn at [frame] with their local frame, bottom first (at most two, during a transition). */
    fun visibleAt(frame: Int): List<Pair<PlacedSlide, Int>> = slides.filter { it.contains(frame) }.map { it to frame - it.from }

    fun find(id: SlideId): PlacedSlide? = slides.firstOrNull { it.id == id }

    /** The frame to export the summary card at, once everything on it has landed (web exportCard). */
    val summaryCardFrame: Int? get() = find(SlideId.Summary)?.let { it.from + SUMMARY_SETTLED_FRAME }

    companion object {
        /** Logical size of the story (web theme.ts WIDTH × HEIGHT): everything is laid out in these units. */
        const val WIDTH = 1080
        const val HEIGHT = 1920
        const val FPS = 30
        /** Frames each transition overlaps the slides either side of it. */
        const val TRANSITION_FRAMES = 16
        /** Frame of the summary slide at which everything on it has landed (web Root.tsx). */
        const val SUMMARY_SETTLED_FRAME = 110

        fun of(data: Wrapped): WrappedTimeline {
            val slides = ArrayList<PlacedSlide>()
            var at = 0
            for (id in SlideId.entries) {
                if (!id.supports(data)) continue
                val duration = jsRound(id.seconds * FPS)
                // Each slide after the first starts while the previous one is leaving.
                val from = if (slides.isEmpty()) 0 else at - TRANSITION_FRAMES
                slides.add(PlacedSlide(id, slides.size, from, duration))
                at = from + duration
            }
            return WrappedTimeline(slides, max(1, at))
        }
    }
}

/** JavaScript's Math.round: halves go up (towards +∞), unlike Kotlin's roundToInt for negatives. */
fun jsRound(x: Double): Int = if (x.isNaN()) 0 else kotlin.math.floor(x + 0.5).toInt()
