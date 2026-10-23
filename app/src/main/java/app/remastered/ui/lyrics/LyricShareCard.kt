package app.remastered.ui.lyrics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import app.remastered.R
import app.remastered.core.lyrics.Lyrics
import app.remastered.core.lyrics.creditText
import app.remastered.ui.components.loadArtworkBitmap
import app.remastered.ui.theme.Palette
import app.remastered.ui.wrapped.WrappedShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The image shared from the lyrics view: a few lines over the song's artwork colour, with the
 * artwork, title, artist and the lyrics credit (ADR 0005: the credit goes wherever lyrics do).
 * Drawn with a plain Canvas, off the main thread, and shared through the Wrapped share plumbing.
 */
object LyricShareCard {
    const val WIDTH = 1080
    const val HEIGHT = 1350
    private const val PAD = 88f
    private const val ART = 144f

    /** Draws the card, writes it to the cache and opens the share sheet. Throws if it can't. */
    suspend fun share(context: Context, lyrics: Lyrics, lines: List<String>, title: String, artist: String?, imageUrl: String?, tint: Color) {
        val art = imageUrl?.let { runCatching { loadArtworkBitmap(context, it, ART.toInt() * 2) }.getOrNull() }
        val figtree = withContext(Dispatchers.IO) { ResourcesCompat.getFont(context, R.font.figtree) } ?: Typeface.DEFAULT
        val image = withContext(Dispatchers.Default) { draw(figtree, lines, title, artist, lyrics.attribution.creditText(), art, tint) }
        val file = WrappedShare.savePng(context, image.asImageBitmap(), "lyrics-${lyrics.trackId}")
        WrappedShare.share(context, file, title)
    }

    private fun paint(face: Typeface, weight: Int, size: Float, color: Color) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        // Figtree is a variable font: pick the weight on its axis.
        fontVariationSettings = "'wght' $weight"
        textSize = size
        this.color = color.toArgb()
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int, spacing: Float = 1f) =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    private fun draw(face: Typeface, lines: List<String>, title: String, artist: String?, credit: String, art: Bitmap?, tint: Color): Bitmap {
        val bitmap = createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(bitmap)
        val width = (WIDTH - PAD * 2).toInt()

        // The lyrics view's own gradient, so the card looks like the screen it came from.
        val top = lerp(tint, Color.Black, .3f).toArgb()
        val bottom = lerp(tint, Palette.base, .65f).toArgb()
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), top, bottom, Shader.TileMode.CLAMP) })

        // Header: artwork, then title and artist beside it.
        val artRect = RectF(PAD, PAD, PAD + ART, PAD + ART)
        if (art != null) {
            val scale = maxOf(ART / art.width, ART / art.height)
            val shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(PAD + (ART - art.width * scale) / 2, PAD + (ART - art.height * scale) / 2)
                })
            }
            canvas.drawRoundRect(artRect, 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
        } else {
            canvas.drawRoundRect(artRect, 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.raised.toArgb() })
        }
        val textLeft = PAD + ART + 32f
        val textWidth = (WIDTH - PAD - textLeft).toInt()
        val titleLayout = layout(title, paint(face, 800, 44f, Color.White), textWidth, 2)
        val artistLayout = artist?.takeIf { it.isNotBlank() }?.let { layout(it, paint(face, 600, 34f, Color.White.copy(alpha = .72f)), textWidth, 1) }
        val headerText = titleLayout.height + (artistLayout?.let { it.height + 8 } ?: 0)
        canvas.withTranslation(textLeft, PAD + (ART - headerText) / 2) {
            titleLayout.draw(this)
            artistLayout?.let { translate(0f, titleLayout.height + 8f); it.draw(this) }
        }

        // Footer: the credit, at the bottom.
        val creditLayout = layout(credit, paint(face, 600, 30f, Color.White.copy(alpha = .75f)), width, 2)
        val creditTop = HEIGHT - PAD - creditLayout.height

        // The lyrics, as big as fits between the two, centred in the space.
        val lyricsTop = PAD + ART + 80f
        val room = creditTop - 64f - lyricsTop
        val text = lines.joinToString("\n")
        val size = ShareCardText.fit(room) { layout(text, paint(face, 800, it, Color.White), width, Int.MAX_VALUE, 1.12f).height.toFloat() }
        val lyricsPaint = paint(face, 800, size, Color.White)
        val maxLines = ShareCardText.maxLines(room, lyricsPaint.fontSpacing * 1.12f)
        val lyricsLayout = layout(text, lyricsPaint, width, maxLines, 1.12f)
        canvas.withTranslation(PAD, lyricsTop + maxOf(0f, room - lyricsLayout.height) / 2) { lyricsLayout.draw(this) }
        canvas.withTranslation(PAD, creditTop.toFloat()) { creditLayout.draw(this) }
        return bitmap
    }
}
