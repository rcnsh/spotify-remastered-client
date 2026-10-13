package app.remastered.spicylyrics

import app.remastered.core.lyrics.Attribution
import app.remastered.core.lyrics.Contributor
import app.remastered.core.lyrics.LyricLine
import app.remastered.core.lyrics.Lyrics
import app.remastered.core.lyrics.Provider
import app.remastered.core.lyrics.Syllable
import app.remastered.core.lyrics.Vocal
import kotlin.math.roundToLong
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/** A line with no usable end time lasts until the next line, or this long after the last one. */
private const val OPEN_LINE_MS = 5_000L

/** Parses a 200 response body. Null when it isn't lyrics we can show. */
internal fun parseLyrics(body: String, trackId: String): Lyrics? {
    val dto = try {
        json.decodeFromString<EnvelopeDto>(body).body
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } ?: return null
    val sync = when (dto.type) {
        "Syllable" -> Lyrics.Sync.Syllable
        "Line" -> Lyrics.Sync.Line
        "Static" -> Lyrics.Sync.Static
        else -> return null
    }
    val lines = when (sync) {
        Lyrics.Sync.Syllable -> dto.content.mapNotNull { it?.toSyllableLine() }.timed()
        Lyrics.Sync.Line -> dto.content.mapNotNull { it?.toLineLine() }.timed()
        Lyrics.Sync.Static -> dto.lines.mapNotNull { it?.toStaticLine() }
    }
    if (lines.isEmpty()) return null
    return Lyrics(
        trackId = trackId,
        sync = sync,
        lines = lines,
        songWriters = dto.songWriters.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) },
        attribution = dto.attribution(),
    )
}

private fun ms(seconds: Double?): Long? = seconds?.takeIf { it.isFinite() && it >= 0 }?.let { (it * 1000).roundToLong() }

private fun VocalDto.toVocal(): Vocal? {
    val syllables = syllables.mapNotNull { s ->
        val text = s?.text?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
        val start = ms(s.startTime) ?: return@mapNotNull null
        Syllable(text, start, ms(s.endTime)?.coerceAtLeast(start) ?: start, s.isPartOfWord)
    }
    if (syllables.isEmpty()) return null
    val start = ms(startTime) ?: syllables.first().startMs
    val end = ms(endTime) ?: syllables.maxOf { it.endMs }
    // Prefer the group's romanization; otherwise join the syllables' own, if they all have one.
    val perSyllable = this.syllables.filterNotNull().filter { it.text.isNotBlank() }
    val joined = perSyllable.takeIf { list -> list.isNotEmpty() && list.all { !it.transliteratedText.isNullOrBlank() } }
        ?.let { list -> buildString { list.forEachIndexed { i, s -> append(s.transliteratedText!!.trim()); if (!s.isPartOfWord && i < list.lastIndex) append(' ') } } }
    return Vocal(start, end.coerceAtLeast(start), syllables, transliteratedText.clean() ?: joined)
}

private fun LineDto.toSyllableLine(): LyricLine? {
    val lead = lead?.toVocal() ?: return null
    val background = background.mapNotNull { it?.toVocal() }
    return LyricLine(
        startMs = lead.startMs,
        endMs = (listOf(lead) + background).maxOf { it.endMs },
        lead = lead,
        background = background,
        oppositeAligned = oppositeAligned,
        translation = this.lead.translatedText.clean(),
    )
}

private fun LineDto.toLineLine(): LyricLine? {
    val text = text?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val start = ms(startTime) ?: return null
    val end = ms(endTime)?.coerceAtLeast(start) ?: start
    return LyricLine(start, end, Vocal(start, end, listOf(Syllable(text, start, end)), transliteratedText.clean()),
        oppositeAligned = oppositeAligned, translation = translatedText.clean())
}

private fun StaticLineDto.toStaticLine(): LyricLine? {
    val text = text.trim().takeIf(String::isNotEmpty) ?: return null
    return LyricLine(0, 0, Vocal(0, 0, listOf(Syllable(text, 0, 0)), transliteratedText.clean()), translation = translatedText.clean())
}

/** Sorts by start and gives lines without a real end time one (up to the next line). */
private fun List<LyricLine>.timed(): List<LyricLine> {
    val sorted = sortedBy { it.startMs }
    return sorted.mapIndexed { i, line ->
        if (line.endMs > line.startMs) line
        else line.copy(endMs = sorted.getOrNull(i + 1)?.startMs?.takeIf { it > line.startMs } ?: (line.startMs + OPEN_LINE_MS))
    }
}

private fun String?.clean(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun BodyDto.attribution(): Attribution {
    val provider = when (source) {
        "spicy_lyrics" -> Provider.SpicyLyrics
        "apple_music" -> Provider.AppleMusic
        "spotify" -> Provider.Spotify
        else -> Provider.Unknown
    }
    // Uploader and maker exist only on community syncs; ignore them anywhere else.
    if (provider != Provider.SpicyLyrics) return Attribution(provider)
    return Attribution(provider, uploadAttribution?.uploader?.toModel(), uploadAttribution?.maker?.toModel())
}

private fun ContributorDto.toModel(): Contributor? {
    val name = username.clean() ?: return null
    return Contributor(name, url?.trim()?.takeIf { it.startsWith("https://") })
}
