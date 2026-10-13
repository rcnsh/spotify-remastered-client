package app.remastered.core.lyrics

/**
 * Time-synced lyrics for a song. Implemented by [app.remastered.spicylyrics.SpicyLyrics].
 * Never throws: every failure is a [LyricsResult].
 */
interface LyricsSource {
    /** Lyrics for a `spotify:track:` uri. Anything else is [LyricsResult.Unsupported]. */
    suspend fun lyrics(trackUri: String): LyricsResult
}

sealed interface LyricsResult {
    data class Found(val lyrics: Lyrics) : LyricsResult
    /** The source has no lyrics for this song. */
    data object NotFound : LyricsResult
    /** Not a Spotify track (an episode or a local file). */
    data object Unsupported : LyricsResult
    /** This build has no Spicy Lyrics key. */
    data object NotConfigured : LyricsResult
    data class Failed(val problem: Problem, val retryAfterMs: Long? = null) : LyricsResult

    enum class Problem { Offline, RateLimited, KeyRejected, ServiceDown, BadResponse }
}

/** Times are milliseconds from the start of the track. Lines are sorted by start time. */
data class Lyrics(
    val trackId: String,
    val sync: Sync,
    val lines: List<LyricLine>,
    val songWriters: List<String>,
    val attribution: Attribution,
) {
    enum class Sync {
        /** Word (syllable) timing: each syllable lights up as it's sung. */
        Syllable,
        /** Line timing only. */
        Line,
        /** No timing. */
        Static,
    }
}

data class LyricLine(
    val startMs: Long,
    /** Includes background vocals that end after the lead. */
    val endMs: Long,
    val lead: Vocal,
    /** Backing vocals sung alongside [lead], shown smaller underneath. */
    val background: List<Vocal> = emptyList(),
    /** Sung by a second voice: drawn on the opposite side. */
    val oppositeAligned: Boolean = false,
    val translation: String? = null,
)

data class Vocal(
    val startMs: Long,
    val endMs: Long,
    /** Line-timed and static lyrics have one syllable holding the whole line. */
    val syllables: List<Syllable>,
    /** Romanization, for scripts that have one. */
    val transliteration: String? = null,
) {
    val text: String get() = buildString {
        syllables.forEachIndexed { i, s ->
            append(s.text)
            if (!s.joinsNext && i < syllables.lastIndex) append(' ')
        }
    }
}

data class Syllable(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** Part of the same word as the next syllable: no space between them. */
    val joinsNext: Boolean = false,
)

/** Who to credit for a response. Spicy Lyrics' terms require showing this next to the lyrics. */
data class Attribution(
    val provider: Provider,
    /** Community syncs only: who uploaded the sync. */
    val uploader: Contributor? = null,
    /** Community syncs only, and only when someone other than the uploader made it. */
    val maker: Contributor? = null,
)

enum class Provider(val displayName: String) {
    SpicyLyrics("Spicy Lyrics"),
    AppleMusic("Apple Music"),
    Spotify("Spotify"),
    Unknown("Unknown"),
}

/** [url] is only ever https. */
data class Contributor(val username: String, val url: String?)

sealed interface CreditPart {
    val text: String

    data class Plain(override val text: String) : CreditPart
    data class Link(override val text: String, val url: String) : CreditPart
}

/**
 * The credit line for these lyrics, per Spicy Lyrics' attribution rules: always the provider; for
 * community syncs also the maker and uploader, linked. Fields a source doesn't send are left out,
 * never shown empty.
 */
fun Attribution.credit(): List<CreditPart> {
    fun who(c: Contributor) = c.url?.let { CreditPart.Link(c.username, it) } ?: CreditPart.Plain(c.username)
    return when (provider) {
        Provider.SpicyLyrics -> when {
            uploader == null -> listOf(CreditPart.Plain("Lyrics from Spicy Lyrics"))
            maker == null -> listOf(CreditPart.Plain("Synced by "), who(uploader), CreditPart.Plain(" · Spicy Lyrics"))
            else -> listOf(
                CreditPart.Plain("Synced by "), who(maker),
                CreditPart.Plain(", uploaded by "), who(uploader),
                CreditPart.Plain(" · Spicy Lyrics"),
            )
        }
        Provider.AppleMusic, Provider.Spotify -> listOf(CreditPart.Plain("Lyrics from ${provider.displayName}"))
        Provider.Unknown -> listOf(CreditPart.Plain("Lyrics source unknown"))
    }
}

fun Attribution.creditText(): String = credit().joinToString("") { it.text }
