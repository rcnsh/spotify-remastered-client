package app.remastered.media

import android.content.Context
import android.graphics.Bitmap
import app.remastered.spotify.remote.AppRemotePlayer
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap

/**
 * A software bitmap of [url] (App Remote artwork or https) for a notification or widget, which
 * can't take hardware bitmaps. Null when it can't be loaded.
 */
internal suspend fun loadArtwork(context: Context, player: AppRemotePlayer?, url: String, px: Int): Bitmap? =
    if (url.startsWith(AppRemotePlayer.IMAGE_SCHEME)) player?.image(url.removePrefix(AppRemotePlayer.IMAGE_SCHEME), large = false)
    else (SingletonImageLoader.get(context).execute(
        ImageRequest.Builder(context).data(url).size(Size(px, px)).allowHardware(false).build(),
    ) as? SuccessResult)?.image?.toBitmap()
