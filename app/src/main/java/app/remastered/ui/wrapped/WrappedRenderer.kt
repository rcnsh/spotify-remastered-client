package app.remastered.ui.wrapped

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.core.content.FileProvider
import app.remastered.core.warehouse.Wrapped
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Renders [WrappedFrame] off screen, one frame at a time, at any pixel size: for share images now
 * and the MP4 exporter. It composes the same [WrappedFrame] the story shows, so a rendered
 * frame is the frame on screen.
 *
 * Compose needs a live composition to draw in, so place [WrappedRendererHost] once in the screen
 * that renders (it takes no space and shows nothing), then call [renderWrappedFrame]. Calls are
 * serialised: one frame is composed, drawn into a GraphicsLayer and read back at a time.
 */
@Stable
class WrappedRenderer {
    internal class Job(val data: Wrapped, val frame: Int, val assets: WrappedAssets, val widthPx: Int, val heightPx: Int) {
        val result = CompletableDeferred<ImageBitmap>()
    }

    internal var job by mutableStateOf<Job?>(null)
        private set
    private val mutex = Mutex()

    /** Draws [frame] of [data]'s story at [widthPx] × [heightPx]. Suspends until the host has drawn it. */
    suspend fun renderWrappedFrame(data: Wrapped, frame: Int, assets: WrappedAssets, widthPx: Int, heightPx: Int): ImageBitmap = mutex.withLock {
        val next = Job(data, frame, assets, widthPx, heightPx)
        withContext(Dispatchers.Main) { job = next }
        try {
            next.result.await()
        } finally {
            withContext(Dispatchers.Main) { if (job === next) job = null }
        }
    }
}

@Composable
fun rememberWrappedRenderer() = remember { WrappedRenderer() }

/** Where a [WrappedRenderer] composes its frames. Zero-sized and never visible. */
@Composable
fun WrappedRendererHost(renderer: WrappedRenderer) {
    val job = renderer.job ?: return
    val layer = rememberGraphicsLayer()
    val drawn = remember(job) { booleanArrayOf(false) }
    Box(
        Modifier
            .layout { measurable, _ ->
                val p = measurable.measure(Constraints.fixed(job.widthPx, job.heightPx))
                layout(0, 0) { p.place(0, 0) }
            }
            .drawWithContent {
                // Recorded, never drawn here: the layer is read back below.
                layer.record { this@drawWithContent.drawContent() }
                drawn[0] = true
            },
    ) {
        WrappedFrame(job.data, job.frame, job.assets, Modifier.fillMaxSize())
    }
    LaunchedEffect(job) {
        // A frame boundary after the draw pass that recorded this job.
        while (!drawn[0]) withFrameNanos { }
        runCatching { layer.toImageBitmap() }.fold(job.result::complete, job.result::completeExceptionally)
    }
}

/** PNG files written for sharing, and the share sheet for them. */
object WrappedShare {
    private fun dir(context: Context) = File(context.cacheDir, "wrapped").apply { mkdirs() }

    /** Writes [image] as a PNG in the app's cache. Old shares are cleared first. */
    suspend fun savePng(context: Context, image: ImageBitmap, name: String): File = withContext(Dispatchers.IO) {
        dir(context).listFiles()?.forEach { it.delete() }
        File(dir(context), "$name.png").also { file -> file.outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    /** Opens the system share sheet for a file in the app's cache: a PNG from [savePng], or a video. */
    fun share(context: Context, file: File, title: String, mime: String = "image/png") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.wrapped", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(title, uri)
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
