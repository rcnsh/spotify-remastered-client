package app.remastered.core.wrapped

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoExportTest {
    @Test fun frameTimesAreExactAndMonotonic() {
        assertEquals(0L, VideoExport.frameTimeUs(0))
        assertEquals(33_333L, VideoExport.frameTimeUs(1))
        assertEquals(1_000_000L, VideoExport.frameTimeUs(30))
        assertEquals(80_000_000L, VideoExport.durationUs(2400))
        var last = -1L
        for (f in 0 until 3000) {
            val t = VideoExport.frameTimeUs(f)
            assert(t > last)
            assertEquals(f, VideoExport.frameAt(t + 1))
            last = t
        }
    }

    @Test fun samplesCoverTheVideo() {
        assertEquals(44_100L, VideoExport.samplesFor(30, 44_100))
        assertEquals(1_470L, VideoExport.samplesFor(1, 44_100))
        assertEquals(1_600L, VideoExport.samplesFor(1, 48_000))
        assertEquals(1_000_000L, VideoExport.sampleTimeUs(48_000, 48_000))
    }

    @Test fun trimStartRoundsToAFrame() {
        assertEquals(VideoExport.frameTimeUs(1395), VideoExport.trimStartUs(46.5))
        assertEquals(VideoExport.frameTimeUs(1), VideoExport.trimStartUs(0.02))
        assertEquals(0L, VideoExport.trimStartUs(0.0))
    }

    @Test fun volumeFadesInAndOut() {
        val frames = 300
        assertEquals(0.0, VideoExport.volumeAt(0.0, frames), 1e-9)
        assertEquals(0.45, VideoExport.volumeAt(10.0, frames), 1e-9)
        assertEquals(0.9, VideoExport.volumeAt(20.0, frames), 1e-9)
        assertEquals(0.9, VideoExport.volumeAt(150.0, frames), 1e-9)
        assertEquals(0.9, VideoExport.volumeAt(255.0, frames), 1e-9)
        assertEquals(0.0, VideoExport.volumeAt(300.0, frames), 1e-9)
        assertEquals(0.0, VideoExport.volumeAt(400.0, frames), 1e-9)
        assertEquals(0.0, VideoExport.volumeAt(-5.0, frames), 1e-9)
    }

    @Test fun envelopeScalesEveryChannelBySampleTime() {
        val rate = 300 // 10 samples per frame
        val pcm = ShortArray(2 * 400) { 10_000 }
        VideoExport.applyEnvelope(pcm, pcm.size, 2, 0, rate, 300)
        assertEquals(0, pcm[0].toInt())
        assertEquals(0, pcm[1].toInt())
        // Sample 100 is frame 10: half of 0.9.
        assertEquals(4_500, pcm[200].toInt())
        assertEquals(4_500, pcm[201].toInt())
        assertEquals(9_000, pcm[2 * 399].toInt())
        // Offsets carry across buffers.
        val tail = ShortArray(2) { 10_000 }
        VideoExport.applyEnvelope(tail, 2, 2, 100, rate, 300)
        assertArrayEquals(shortArrayOf(4_500, 4_500), tail)
    }

    @Test fun hookIsTheLoudestTwentySecondsThatStillCoverTheVideo() {
        // 3 minutes, loud from 90 s to 110 s.
        val energy = DoubleArray(360) { if (it in 180 until 220) 1.0 else 0.01 }
        assertEquals(90.0, Hook.start(energy, 180.0, 60.0), 1e-9)
        // A video too long to start at the hook pushes the start back to the latest that covers it.
        assertEquals(80.0, Hook.start(energy, 180.0, 100.0), 1e-9)
        // A song shorter than the video starts at the top.
        assertEquals(0.0, Hook.start(energy, 180.0, 200.0), 1e-9)
        // Shorter than the hook itself.
        assertEquals(0.0, Hook.start(DoubleArray(10) { 1.0 }, 5.0, 1.0), 1e-9)
    }

    @Test fun meterFindsTheLoudMiddleOfSyntheticPcm() {
        val rate = 8_000
        val seconds = 120
        val pcm = ShortArray(rate * seconds * 2)
        for (n in 0 until rate * seconds) {
            val t = n.toDouble() / rate
            val amplitude = if (t in 50.0..70.0) 0.8 else 0.05
            val v = (amplitude * 32767 * sin(2 * PI * 440 * t)).toInt().toShort()
            pcm[2 * n] = v
            pcm[2 * n + 1] = 0 // only the first channel counts
        }
        val meter = EnergyMeter()
        // Fed in uneven buffers, like a decoder.
        var offset = 0
        var sample = 0L
        while (offset < pcm.size) {
            val count = minOf(2 * 1_234, pcm.size - offset)
            meter.add(pcm.copyOfRange(offset, offset + count), count, 2, rate, sample * 1_000_000L / rate)
            offset += count
            sample += count / 2
        }
        val energy = meter.energy()
        assertEquals(seconds * 2, energy.size)
        assertEquals(0.8 * 0.8 / 2, energy[120], 0.01)
        assertEquals(50.0, Hook.start(energy, seconds.toDouble(), 30.0), 1e-9)
    }
}
