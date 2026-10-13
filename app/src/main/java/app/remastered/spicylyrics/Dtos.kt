package app.remastered.spicylyrics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire shapes of `GET /v1/lyrics/{trackId}` (developers.spicylyrics.org/docs). Every response is
// an envelope { Body, Status, Type }. Body.Type picks the shape: "Syllable" and "Line" use Content,
// "Static" uses Lines. One LineDto covers both Content shapes. Times are seconds.

@Serializable
internal data class EnvelopeDto(@SerialName("Body") val body: BodyDto? = null)

@Serializable
internal data class BodyDto(
    @SerialName("Type") val type: String? = null,
    @SerialName("Content") val content: List<LineDto?> = emptyList(),
    @SerialName("Lines") val lines: List<StaticLineDto?> = emptyList(),
    @SerialName("SongWriters") val songWriters: List<String?> = emptyList(),
    @SerialName("UploadAttribution") val uploadAttribution: UploadAttributionDto? = null,
    val id: String? = null,
    val source: String? = null,
)

@Serializable
internal data class LineDto(
    @SerialName("OppositeAligned") val oppositeAligned: Boolean = false,
    // Syllable lines
    @SerialName("Lead") val lead: VocalDto? = null,
    @SerialName("Background") val background: List<VocalDto?> = emptyList(),
    // Line lines
    @SerialName("Text") val text: String? = null,
    @SerialName("StartTime") val startTime: Double? = null,
    @SerialName("EndTime") val endTime: Double? = null,
    @SerialName("TransliteratedText") val transliteratedText: String? = null,
    @SerialName("TranslatedText") val translatedText: String? = null,
)

@Serializable
internal data class VocalDto(
    @SerialName("Syllables") val syllables: List<SyllableDto?> = emptyList(),
    @SerialName("StartTime") val startTime: Double? = null,
    @SerialName("EndTime") val endTime: Double? = null,
    @SerialName("TransliteratedText") val transliteratedText: String? = null,
    @SerialName("TranslatedText") val translatedText: String? = null,
)

@Serializable
internal data class SyllableDto(
    @SerialName("Text") val text: String = "",
    @SerialName("StartTime") val startTime: Double? = null,
    @SerialName("EndTime") val endTime: Double? = null,
    @SerialName("IsPartOfWord") val isPartOfWord: Boolean = false,
    @SerialName("TransliteratedText") val transliteratedText: String? = null,
)

@Serializable
internal data class StaticLineDto(
    @SerialName("Text") val text: String = "",
    @SerialName("TransliteratedText") val transliteratedText: String? = null,
    @SerialName("TranslatedText") val translatedText: String? = null,
)

@Serializable
internal data class UploadAttributionDto(
    @SerialName("Uploader") val uploader: ContributorDto? = null,
    @SerialName("Maker") val maker: ContributorDto? = null,
)

@Serializable
internal data class ContributorDto(val username: String? = null, val url: String? = null)
