package app.remastered.ui.wrapped

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.remastered.core.Catalog
import app.remastered.core.model.TimeRange
import app.remastered.core.warehouse.Wrapped
import app.remastered.core.wrapped.random
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Everything a frame draws that isn't in [Wrapped]: decoded images, artist photos and the scenes'
 * tone-mapped textures. Loaded once, before playback starts (the web story's delayRender), so no
 * frame ever depends on when an image arrived and an exported frame matches the one on screen.
 */
class WrappedAssets internal constructor(
    /** Image url → decoded image. A url that failed to load is absent and draws as a flat block. */
    private val images: Map<String, ImageBitmap>,
    /** Lowercased artist name → photo url, from Spotify's live top-artist lists. */
    private val artistPhotos: Map<String, String>,
    /** Scene textures: url (+ [DIM] for the carousel's unlit faces) → art as three.js would show it. */
    private val sceneImages: Map<String, ImageBitmap>,
) {
    fun image(url: String?): ImageBitmap? = url?.let(images::get)

    /** The best picture of an artist: a live photo if Spotify gave one, else [fallback] (their top song's art). */
    fun artistImageUrl(name: String, fallback: String?): String? = artistPhotos[name.lowercase(Locale.ROOT)] ?: fallback

    fun artistImage(name: String, fallback: String?): ImageBitmap? = image(artistImageUrl(name, fallback))

    /** [url]'s art through three's colour pipeline (ACES), optionally tinted grey as an unlit carousel face. */
    internal fun sceneImage(url: String?, dim: Boolean = false): ImageBitmap? = url?.let { sceneImages[if (dim) it + DIM else it] }

    companion object {
        internal const val DIM = "#dim"

        /** No images at all: every picture draws as its fallback block. */
        val Empty = WrappedAssets(emptyMap(), emptyMap(), emptyMap())

        /**
         * Lowercased artist name → photo url from the user's top artists (medium and long term), as
         * the web app gets them. Best effort: photos are decoration, so failures give an empty map.
         */
        suspend fun artistPhotos(catalog: Catalog): Map<String, String> {
            val out = HashMap<String, String>()
            for (range in listOf(TimeRange.Medium, TimeRange.Long)) {
                val artists = runCatching { catalog.topArtists(range, 50) }.getOrNull() ?: continue
                for (a in artists) {
                    // Largest first: 640 px, about the size the reveal shows it.
                    val url = a.images.maxByOrNull { it.width ?: 0 }?.url ?: continue
                    out[a.name.lowercase(Locale.ROOT)] = url
                }
            }
            return out
        }

        /** The image urls [data]'s story draws, given [artistPhotos]. */
        fun urls(data: Wrapped, artistPhotos: Map<String, String>): Set<String> {
            fun artist(name: String, fallback: String?) = artistPhotos[name.lowercase(Locale.ROOT)] ?: fallback
            return buildSet {
                data.topSongs.take(5).forEach { it.imageUrl?.let(::add) }
                data.topAlbums.firstOrNull()?.imageUrl?.let(::add)
                data.topArtists.take(5).forEach { a -> artist(a.name, a.imageUrl)?.let(::add) }
                data.discoveries.top.firstOrNull()?.let { d -> artist(d.name, d.imageUrl)?.let(::add) }
                sampleMonths(data.months).forEach { it.imageUrl?.let(::add) }
            }
        }

        /**
         * Decodes every image the story uses and prepares the scenes' textures. [onProgress] gets
         * 0..1. Never throws for a bad image: that picture just draws as its fallback.
         */
        suspend fun load(context: Context, data: Wrapped, artistPhotos: Map<String, String>, onProgress: (Float) -> Unit = {}): WrappedAssets = coroutineScope {
            val loader = SingletonImageLoader.get(context)
            val urls = urls(data, artistPhotos).toList()
            var done = 0
            val decoded = urls.map { url ->
                async {
                    val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
                    val bitmap = (loader.execute(request) as? SuccessResult)?.image?.toBitmap()
                    synchronized(this@coroutineScope) { done++; onProgress(done.toFloat() / (urls.size + 1)) }
                    bitmap?.let { url to it }
                }
            }.awaitAll().filterNotNull().toMap()
            val scene = withContext(Dispatchers.Default) {
                val discArt = listOfNotNull(data.topSongs.firstOrNull()?.imageUrl ?: data.topAlbums.firstOrNull()?.imageUrl)
                val carousel = data.topSongs.take(5).mapNotNull { it.imageUrl }
                val jobs = (discArt + carousel).distinct().mapNotNull { url -> decoded[url]?.let { url to it } }.flatMap { (url, bmp) ->
                    listOfNotNull(
                        async { url to toneMapped(bmp, 1.0) },
                        if (url in carousel) async { url + DIM to toneMapped(bmp, ThreeColor.linear(0x8a)) } else null,
                    )
                }
                jobs.awaitAll().toMap()
            }
            onProgress(1f)
            WrappedAssets(decoded.mapValues { it.value.asImageBitmap() }, artistPhotos, scene)
        }

        /** [source] as a three.js `meshBasicMaterial` map shows it: sRGB → linear × [tint] → ACES → sRGB. */
        private fun toneMapped(source: Bitmap, tint: Double): ImageBitmap {
            val w = source.width
            val h = source.height
            val px = IntArray(w * h)
            source.getPixels(px, 0, w, 0, 0, w, h)
            val out = DoubleArray(3)
            for (i in px.indices) {
                val c = px[i]
                ThreeColor.aces(ThreeColor.linear(c shr 16) * tint, ThreeColor.linear(c shr 8) * tint, ThreeColor.linear(c) * tint, out)
                px[i] = (c and 0xFF000000.toInt()) or (ThreeColor.encode(out[0]) shl 16) or (ThreeColor.encode(out[1]) shl 8) or ThreeColor.encode(out[2])
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
        }

        /** The photocopy grain tile (web collage.tsx `grain()`): seeded, so every frame grains the same way. */
        internal val grain: ImageBitmap by lazy {
            val size = 256
            val px = IntArray(size * size) { i ->
                val v = (random("grain$i") * 255).toInt()
                val a = (random("grain-a$i") * 70).toInt()
                (a shl 24) or (v shl 16) or (v shl 8) or v
            }
            Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
}

/** At most twelve months, evenly spread over a longer range (web Months `sampleMonths`). */
internal fun <T> sampleMonths(months: List<T>): List<T> {
    if (months.size <= 12) return months
    return List(12) { i -> months[app.remastered.core.wrapped.jsRound(i * (months.size - 1) / 11.0)] }
}
