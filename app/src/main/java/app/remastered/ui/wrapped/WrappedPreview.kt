package app.remastered.ui.wrapped

import java.time.LocalDate

/** A debug request to open a Wrapped story directly: a range, and optionally a frame to open paused at. */
data class WrappedPreview(
    val from: LocalDate,
    val to: LocalDate,
    val frame: Int? = null,
    /** Frames to render off screen at 1080 × 1920 into the app's external files (`wrapped/`), for comparing stills. */
    val dump: List<Int>? = null,
) {
    companion object {
        /** `2025-01-01:2025-12-31`, or null if it isn't a range. */
        fun parse(text: String): WrappedPreview? {
            val parts = text.trim().split(':')
            if (parts.size != 2) return null
            val from = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return null
            val to = runCatching { LocalDate.parse(parts[1]) }.getOrNull() ?: return null
            return if (to < from) null else WrappedPreview(from, to)
        }
    }
}
