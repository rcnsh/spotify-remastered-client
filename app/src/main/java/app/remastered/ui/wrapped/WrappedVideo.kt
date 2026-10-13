package app.remastered.ui.wrapped

import android.content.ContentValues
import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import app.remastered.core.warehouse.Wrapped
import app.remastered.core.wrapped.EnergyMeter
import app.remastered.core.wrapped.Hook
import app.remastered.core.wrapped.VideoExport
import app.remastered.core.wrapped.WrappedTimeline
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A failure the listener should read as is. */
class ExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A song file the listener picked for the video, and where its hook starts (web soundtrack.ts). */
data class SongFile(val uri: Uri, val name: String, val durationSeconds: Double, val startSeconds: Double) {
    val startLabel get() = "%d:%02d".format(startSeconds.toInt() / 60, Math.round(startSeconds % 60).toInt())

    companion object {
        /** Decodes [uri] once to find its loudest [Hook.HOOK_SECONDS] that still cover [videoSeconds]. */
        suspend fun analyze(context: Context, uri: Uri, videoSeconds: Double): SongFile = withContext(Dispatchers.Default) {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "Song"
            val meter = EnergyMeter()
            val durationUs = decodePcm(context, uri, 0L) { pcm, count, channels, rate, timeUs ->
                ensureActive()
                meter.add(pcm, count, channels, rate, timeUs)
                true
            }
            val duration = durationUs / 1_000_000.0
            SongFile(uri, name, duration, Hook.start(meter.energy(), duration, videoSeconds))
        }
    }
}

private const val TAG = "WrappedVideo"
private const val TIMEOUT_US = 10_000L
/**
 * Asked of the encoder. Android's software encoder (emulators) overshoots it about 2× on the
 * frame-to-frame grain; phone hardware encoders hold it far more closely.
 */
private const val VIDEO_BITRATE = 10_000_000
private const val AUDIO_BITRATE = 192_000

/**
 * Decodes the first audio track of [uri] from [startUs] to 16-bit PCM, handing each buffer to
 * [onPcm] (interleaved samples, count, channels, sample rate, timestamp) until it returns false or
 * the track ends. Returns the track's duration in microseconds.
 */
private suspend fun decodePcm(
    context: Context,
    uri: Uri,
    startUs: Long,
    onPcm: suspend (ShortArray, Int, Int, Int, Long) -> Boolean,
): Long {
    val extractor = MediaExtractor()
    var decoder: MediaCodec? = null
    try {
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            throw ExportException("Couldn't open that file.", e)
        }
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: throw ExportException("That file has no audio track.")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        decoder = try {
            MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply { configure(format, null, null, 0); start() }
        } catch (e: Exception) {
            throw ExportException("This phone can't decode that audio format.", e)
        }
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var pcm = ShortArray(0)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        while (true) {
            if (!inputDone) {
                // Don't wait for input: when the decoder is full, the output below is what frees it.
                val index = decoder.dequeueInputBuffer(0)
                if (index >= 0) {
                    val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val out = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> decoder.outputFormat.let {
                    channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (it.containsKey(MediaFormat.KEY_PCM_ENCODING) && it.getInteger(MediaFormat.KEY_PCM_ENCODING) != android.media.AudioFormat.ENCODING_PCM_16BIT) {
                        throw ExportException("This phone decodes that file in a format the export can't use.")
                    }
                }
                out >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0) {
                        val buffer = decoder.getOutputBuffer(out)!!
                        buffer.position(info.offset).limit(info.offset + info.size)
                        val count = info.size / 2
                        if (pcm.size < count) pcm = ShortArray(count)
                        buffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(pcm, 0, count)
                        decoder.releaseOutputBuffer(out, false)
                        if (!onPcm(pcm, count, channels, rate, info.presentationTimeUs)) return durationUs
                    } else {
                        decoder.releaseOutputBuffer(out, false)
                    }
                    if (eos) return durationUs
                }
            }
        }
    } finally {
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        extractor.release()
    }
}

/** One encoded sample, copied out of the codec so it can be muxed later. */
private class Sample(val data: ByteArray, val timeUs: Long, val flags: Int)

private class EncodedAudio(val format: MediaFormat, val samples: List<Sample>)

/**
 * The song from its hook for the length of the video, faded like the web story, as AAC.
 * At most two channels go in (the first two of a surround file).
 */
private suspend fun encodeSoundtrack(context: Context, song: SongFile, frames: Int): EncodedAudio {
    val startUs = VideoExport.trimStartUs(song.startSeconds)
    var encoder: MediaCodec? = null
    val samples = ArrayList<Sample>()
    var outFormat: MediaFormat? = null
    val info = MediaCodec.BufferInfo()
    var written = 0L // per-channel samples handed to the encoder
    var total = Long.MAX_VALUE
    var inChannels = 0
    var outChannels = 0
    var outRate = 0

    fun drain(endOfStream: Boolean) {
        val codec = encoder ?: return
        while (true) {
            val out = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            when {
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                out >= 0 -> {
                    val buffer = codec.getOutputBuffer(out)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes)
                        samples += Sample(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun queue(bytes: ByteBuffer, timeUs: Long, flags: Int) {
        val codec = encoder!!
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val input = codec.getInputBuffer(index)!!
                val n = min(input.remaining(), bytes.remaining())
                val slice = bytes.duplicate().apply { limit(position() + n) }
                input.put(slice)
                bytes.position(bytes.position() + n)
                codec.queueInputBuffer(index, 0, n, timeUs, flags)
                return
            }
            drain(false)
        }
    }

    try {
        var scratch = ShortArray(0)
        var bytes = ByteBuffer.allocateDirect(0)
        decodePcm(context, song.uri, startUs) { pcm, count, channels, rate, timeUs ->
            coroutineContextCheck()
            if (encoder == null) {
                inChannels = channels
                outChannels = min(channels, 2)
                outRate = rate
                total = VideoExport.samplesFor(frames, rate)
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, outChannels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                }
                encoder = try {
                    MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
                } catch (e: Exception) {
                    throw ExportException("This phone can't encode that song's audio ($rate Hz, $channels channels).", e)
                }
            }
            if (channels != inChannels || rate != outRate) {
                throw ExportException("That file changes format partway through.")
            }
            // Skip what the seek landed before the hook.
            val frameCount = count / channels
            val skip = if (timeUs >= startUs) 0 else min(frameCount.toLong(), (startUs - timeUs) * rate / 1_000_000L).toInt()
            val take = min((frameCount - skip).toLong(), total - written).toInt()
            if (take > 0) {
                if (scratch.size < take * outChannels) scratch = ShortArray(take * outChannels)
                for (s in 0 until take) for (c in 0 until outChannels) scratch[s * outChannels + c] = pcm[(skip + s) * channels + c]
                VideoExport.applyEnvelope(scratch, take * outChannels, outChannels, written, rate, frames)
                if (bytes.capacity() < take * outChannels * 2) bytes = ByteBuffer.allocateDirect(take * outChannels * 2).order(ByteOrder.nativeOrder())
                bytes.clear()
                bytes.asShortBuffer().put(scratch, 0, take * outChannels)
                bytes.limit(take * outChannels * 2)
                var sent = 0L
                while (bytes.hasRemaining()) {
                    val before = bytes.position()
                    queue(bytes, VideoExport.sampleTimeUs(written + sent, rate), 0)
                    sent += (bytes.position() - before) / (2 * outChannels)
                    drain(false)
                }
                written += take
            }
            written < total
        }
        val codec = encoder ?: throw ExportException("That file has no audio to use.")
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, VideoExport.sampleTimeUs(written, outRate), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain(false)
        }
        drain(true)
        return EncodedAudio(outFormat ?: codec.outputFormat, samples)
    } finally {
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
    }
}

private suspend fun coroutineContextCheck() = kotlin.coroutines.coroutineContext.ensureActive()

/** Where finished videos go: the app's cache, shared through the same FileProvider as images. */
private fun videoDir(context: Context) = File(context.cacheDir, "wrapped-video").apply { mkdirs() }

/**
 * Renders the whole story to an MP4 (web exporter.ts): every frame through [WrappedRenderer] at
 * 1080×1920, 30 fps, H.264 from the encoder's input surface, muxed with the [song]'s hook as AAC
 * or silent. Runs off the main thread except for composing each frame, and is cancellable.
 * [onProgress] gets 0..1. Returns the file in the app's cache.
 */
suspend fun exportWrappedVideo(
    context: Context,
    renderer: WrappedRenderer,
    data: Wrapped,
    assets: WrappedAssets,
    song: SongFile?,
    onProgress: (Float) -> Unit,
): File = withContext(Dispatchers.Default) {
    val frames = WrappedTimeline.of(data).durationInFrames
    val dir = videoDir(context)
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, "wrapped-${data.from}-to-${data.to}.mp4")
    val started = System.nanoTime()

    val audio = song?.let { encodeSoundtrack(context, it, frames) }
    ensureActive()

    val video = setUpVideoEncoder()
    val codec = video.codec
    val surface: Surface = video.surface
    var muxer: MediaMuxer? = null
    var muxing = false
    var videoTrack = -1
    var audioTrack = -1
    var audioNext = 0
    var videoOut = 0
    val info = MediaCodec.BufferInfo()
    val bufferInfo = MediaCodec.BufferInfo()
    var ok = false

    fun writeAudioUpTo(timeUs: Long) {
        val samples = audio?.samples ?: return
        while (audioNext < samples.size && samples[audioNext].timeUs <= timeUs) {
            val s = samples[audioNext++]
            bufferInfo.set(0, s.data.size, s.timeUs, s.flags)
            muxer!!.writeSampleData(audioTrack, ByteBuffer.wrap(s.data), bufferInfo)
        }
    }

    fun drain(endOfStream: Boolean) {
        while (true) {
            val out = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            when {
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxing) { "Video format changed twice" }
                    val m = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxer = m
                    if (video.rotated) m.setOrientationHint(90)
                    videoTrack = m.addTrack(codec.outputFormat)
                    if (audio != null) audioTrack = m.addTrack(audio.format)
                    m.start()
                    muxing = true
                }
                out >= 0 -> {
                    val buffer = codec.getOutputBuffer(out)!!
                    // Past the last frame is only the padding copy (below).
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && videoOut < frames) {
                        check(muxing) { "Video data before its format" }
                        // The surface stamps frames with the wall clock; the video's own clock is
                        // the frame number. B-frames are off, so output order is frame order.
                        info.presentationTimeUs = VideoExport.frameTimeUs(videoOut++)
                        writeAudioUpTo(info.presentationTimeUs)
                        buffer.position(info.offset).limit(info.offset + info.size)
                        muxer!!.writeSampleData(videoTrack, buffer, info)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    try {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val target = Rect(0, 0, VideoExport.WIDTH, VideoExport.HEIGHT)
        // One frame more than the story: an input surface drops a frame still queued when the end
        // of stream arrives (seen on a Pixel 10 Pro: one export in two lost its last frame), so
        // the last frame goes in twice and only the first [frames] outputs are written.
        for (n in 0..frames) {
            ensureActive()
            val frame = min(n, frames - 1)
            val image = renderer.renderWrappedFrame(data, frame, assets, VideoExport.WIDTH, VideoExport.HEIGHT)
            val bitmap = image.asAndroidBitmap()
            val canvas = surface.lockHardwareCanvas()
            try {
                if (video.rotated) {
                    // Landscape-only encoder: draw the portrait frame turned a quarter left; the
                    // orientation hint turns it back on playback.
                    canvas.rotate(-90f)
                    canvas.translate(-VideoExport.WIDTH.toFloat(), 0f)
                }
                canvas.drawBitmap(bitmap, null, target, paint)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            bitmap.recycle()
            drain(false)
            onProgress((frame + 1f) / frames)
        }
        codec.signalEndOfInputStream()
        drain(true)
        check(videoOut > 0) { "The encoder produced no frames" }
        writeAudioUpTo(Long.MAX_VALUE)
        muxer?.stop()
        ok = true
        Log.i(TAG, "exported $frames frames ($videoOut encoded) in ${(System.nanoTime() - started) / 1_000_000} ms, audio ${audio?.samples?.size ?: 0} samples")
        file
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: IllegalStateException) {
        throw ExportException("The video encoder stopped partway: ${e.message}", e)
    } finally {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { surface.release() }
        if (!ok) runCatching { if (muxing) muxer?.stop() }
        runCatching { muxer?.release() }
        if (!ok) file.delete()
    }
}

private class VideoEncoder(val codec: MediaCodec, val surface: Surface, val rotated: Boolean)

/**
 * An H.264 encoder taking frames on a Surface: 1080×1920 if the phone's encoder does portrait,
 * else 1920×1080 drawn sideways with a rotation hint. Throws [ExportException] with a readable
 * reason if neither can be set up.
 */
private fun setUpVideoEncoder(): VideoEncoder {
    val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
        info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
    }.sortedBy { if (it.isHardwareAccelerated) 0 else 1 }
    var lastError: Exception? = null
    for (rotated in listOf(false, true)) {
        val w = if (rotated) VideoExport.HEIGHT else VideoExport.WIDTH
        val h = if (rotated) VideoExport.WIDTH else VideoExport.HEIGHT
        for (info in encoders) {
            val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val video = caps.videoCapabilities ?: continue
            if (!video.areSizeAndRateSupported(w, h, VideoExport.FPS.toDouble())) continue
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                // The software encoder tops out at 12 Mbps; ask for what the encoder can do.
                setInteger(MediaFormat.KEY_BIT_RATE, video.bitrateRange.clamp(VIDEO_BITRATE))
                setInteger(MediaFormat.KEY_FRAME_RATE, VideoExport.FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createByCodecName(info.name)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = codec.createInputSurface()
                codec.start()
                Log.i(TAG, "video encoder ${info.name} ${w}x$h ${format.getInteger(MediaFormat.KEY_BIT_RATE)} bps")
                return VideoEncoder(codec, surface, rotated)
            } catch (e: Exception) {
                Log.w(TAG, "encoder ${info.name} ${w}x$h failed", e)
                lastError = e
                runCatching { codec?.release() }
            }
        }
    }
    throw ExportException("This phone's video encoder couldn't be set up for 1080×1920 H.264.", lastError)
}

/** Copies a finished video into Movies/Remastered through MediaStore (no permission needed). */
suspend fun saveVideoToMovies(context: Context, file: File): Uri = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Remastered")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        ?: throw ExportException("Couldn't save to Movies.")
    try {
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        uri
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw ExportException("Couldn't save to Movies.", e)
    }
}
