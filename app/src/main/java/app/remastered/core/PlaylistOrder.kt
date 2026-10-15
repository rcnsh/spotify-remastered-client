package app.remastered.core

/**
 * Index arithmetic for moving one playlist item, in Spotify's terms: positions count every item
 * (local files too, which the app hides), and a move is "the item at `from`, to before the item
 * now at `insertBefore`" (`PUT /playlists/{id}/items`).
 */
object PlaylistOrder {
    /**
     * Where to insert an item dropped between the items at positions [above] and [below] (either
     * may be null at the ends of the list; not both). Returns null when the drop leaves the item
     * at [from], so there's nothing to send.
     */
    fun insertBefore(from: Int, above: Int?, below: Int?): Int? {
        val before = below ?: (above ?: return null) + 1
        return before.takeUnless { it == from || it == from + 1 }
    }

    /** The position of the item that was at [position], after moving [from] to before [insertBefore]. */
    fun shift(position: Int, from: Int, insertBefore: Int): Int = when {
        position == from -> if (insertBefore > from) insertBefore - 1 else insertBefore
        position in (from + 1) until insertBefore -> position - 1
        position in insertBefore until from -> position + 1
        else -> position
    }
}
