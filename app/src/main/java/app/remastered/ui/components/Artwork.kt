package app.remastered.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette as AndroidPalette
import app.remastered.spotify.remote.AppRemotePlayer
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Artwork from the Spotify app itself (App Remote), keyed by its image id. */
data class RemoteImage(val raw: String)

/** Coil fetcher for [RemoteImage], so App Remote artwork caches like any other image. */
class RemoteImageFetcher(private val data: RemoteImage, private val load: suspend (String) -> Bitmap?) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val bmp = load(data.raw) ?: return null
        return ImageFetchResult(bmp.asImage(), isSampled = false, dataSource = DataSource.NETWORK)
    }

    class Factory(private val load: suspend (String) -> Bitmap?) : Fetcher.Factory<RemoteImage> {
        override fun create(data: RemoteImage, options: Options, imageLoader: ImageLoader) = RemoteImageFetcher(data, load)
    }

    object Key : Keyer<RemoteImage> {
        override fun key(data: RemoteImage, options: Options) = "appremote:${data.raw}"
    }
}

/** Turns an image url from the model into something Coil understands. */
private fun model(url: String): Any = when {
    url.startsWith(AppRemotePlayer.IMAGE_SCHEME) -> RemoteImage(url.removePrefix(AppRemotePlayer.IMAGE_SCHEME))
    else -> url
}

/** [url]'s artwork (https or App Remote) decoded to a software bitmap about [sizePx] across, for drawing on a Canvas. Null if it can't load. */
suspend fun loadArtworkBitmap(context: Context, url: String, sizePx: Int): Bitmap? {
    val request = ImageRequest.Builder(context).data(model(url)).size(sizePx).allowHardware(false).build()
    return (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
}

/**
 * Cover art for any [url]: https or App Remote. A null url (a playlist with no cover yet)
 * gets a quiet disc tile.
 */
@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, shape: Shape = RectangleShape) {
    Box(modifier.clip(shape).background(Palette.raised), contentAlignment = Alignment.Center) {
        if (url == null) Icon(Icons.Disc, null, Modifier.fillMaxSize(.4f), tint = Palette.dim)
        else AsyncImage(model = model(url), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

private val tintCache = mutableStateMapOf<String, Color>()

/** Forgets the analysed artwork colours (Settings → Storage). */
fun clearArtworkTints() = tintCache.clear()

/** How many artwork colours are cached. */
val artworkTintCount get() = tintCache.size

/**
 * The dominant color of an artwork, for tinting headers and Now Playing. Returns
 * [fallback] until the image has been analysed; results are cached per url.
 */
@Composable
fun rememberArtworkTint(url: String?, fallback: Color = Palette.selected): Color {
    if (url == null) return fallback
    val cached = tintCache[url]
    val context = LocalContext.current
    val loader = remember { coil3.SingletonImageLoader.get(context) }
    LaunchedEffect(url) {
        if (url in tintCache) return@LaunchedEffect
        val result = loader.execute(ImageRequest.Builder(context).data(model(url)).size(128).allowHardware(false).build())
        val bmp = result.image?.toBitmap() ?: return@LaunchedEffect
        // Palette scans every pixel: off the main thread, where it would cost frames mid-scroll.
        val swatch = withContext(Dispatchers.Default) {
            val p = AndroidPalette.from(bmp).generate()
            p.vibrantSwatch ?: p.dominantSwatch ?: p.mutedSwatch
        } ?: return@LaunchedEffect
        tintCache[url] = Color(swatch.rgb)
    }
    return cached ?: fallback
}
