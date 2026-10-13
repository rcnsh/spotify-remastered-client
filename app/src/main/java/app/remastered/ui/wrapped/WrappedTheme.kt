package app.remastered.ui.wrapped

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import app.remastered.R
import app.remastered.core.wrapped.TextWidth

/*
 * The 2025 Wrapped look, as the web story (music-warehouse-wrapped theme.ts) has it: a "visual
 * mixtape" in the black and white of photocopied zines, with colour held back for key moments.
 * Spotify Mix is proprietary, so the poster face is Anton, with Archivo for running text, Permanent
 * Marker for the handwritten asides and Space Mono for receipt-like figures — the same Google Fonts
 * the web story loads, bundled (OFL / Apache 2.0; licences in assets/licenses).
 */

val Ink = Color(0xFF0B0B0B)
val Paper = Color(0xFFF1EDE4)

/** Pops of colour, one per moment. */
object Pop {
    val green = Color(0xFF1ED760)
    val acid = Color(0xFFD4FF3A)
    val pink = Color(0xFFFF4FBF)
    val blue = Color(0xFF3B5BFF)
    val orange = Color(0xFFFF6A1F)
    val yellow = Color(0xFFFFE14A)
    val violet = Color(0xFF9B5CFF)
    val red = Color(0xFFFF3B30)
}

/** CSS `rgba(r, g, b, a)`. */
internal fun rgba(r: Int, g: Int, b: Int, a: Double) = Color(r, g, b, (a * 255).toInt().coerceIn(0, 255))

object WrappedFonts {
    val poster = FontFamily(Font(R.font.wrapped_anton))

    @OptIn(ExperimentalTextApi::class)
    private fun archivo(weight: Int) = Font(
        R.font.wrapped_archivo, FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(100f)),
    )

    /**
     * The web story loads Archivo at 500, 700, 800 and 900 only; a 600 asked for there renders at
     * 700 (CSS picks the nearest heavier weight), so 600 maps to 700 here too.
     */
    val body = FontFamily(archivo(500), Font(R.font.wrapped_archivo, FontWeight(600), variationSettings = FontVariation.Settings(FontVariation.weight(700))), archivo(700), archivo(800), archivo(900))
    val marker = FontFamily(Font(R.font.wrapped_marker))
    val mono = FontFamily(Font(R.font.wrapped_space_mono, FontWeight.Normal), Font(R.font.wrapped_space_mono_bold, FontWeight.Bold))
}

/**
 * Measures poster type for fitText through the same Compose text stack that draws it, so the fit
 * and the drawing can't disagree. Widths are measured once per string at a large size and scaled.
 */
class PosterMeasure(fontFamilyResolver: FontFamily.Resolver) : TextWidth {
    private val measurer = TextMeasurer(fontFamilyResolver, Density(1f, 1f), LayoutDirection.Ltr, cacheSize = 0)
    private val style = TextStyle(fontFamily = WrappedFonts.poster, fontSize = SAMPLE.sp)
    private val cache = HashMap<String, Double>()

    override fun width(text: String, fontSize: Double): Double {
        val atSample = cache.getOrPut(text) { measurer.measure(text, style, softWrap = false).multiParagraph.width.toDouble() }
        return atSample * fontSize / SAMPLE
    }

    private companion object {
        const val SAMPLE = 1000f
    }
}
