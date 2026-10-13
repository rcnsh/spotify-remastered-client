package app.remastered.ui.wrapped

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.remastered.core.warehouse.Wrapped
import app.remastered.core.wrapped.SlideId
import app.remastered.core.wrapped.WrappedTimeline
import kotlin.math.min

/**
 * The whole Wrapped story at exactly [frame] (web: remotion/Wrapped.tsx), scaled to fit this
 * composable's bounds at 9:16 and centred.
 *
 * A pure function of its arguments: no clocks, no animate*AsState, no image loading. Every moving
 * value is computed from [frame] with the Remotion-semantics helpers in core.wrapped, and every
 * picture comes from [assets], loaded beforehand. The same call draws a frame on screen, into a
 * share PNG, or (later) into an MP4 encoder via [WrappedRenderer].
 *
 * Inside, one dp is one logical pixel of the [WrappedTimeline.WIDTH] × [WrappedTimeline.HEIGHT]
 * story and font scale is fixed at 1, so the system's text size can't reflow a slide.
 */
@Composable
fun WrappedFrame(data: Wrapped, frame: Int, assets: WrappedAssets, modifier: Modifier = Modifier) {
    val timeline = remember(data) { WrappedTimeline.of(data) }
    val context = LocalContext.current
    val measure = remember(context) { PosterMeasure(context) }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val outer = LocalDensity.current
        val scale = with(outer) { min(maxWidth.toPx() / WrappedTimeline.WIDTH, maxHeight.toPx() / WrappedTimeline.HEIGHT) }
        CompositionLocalProvider(LocalDensity provides Density(scale, 1f)) {
            Box(Modifier.requiredSize(WrappedTimeline.WIDTH.dp, WrappedTimeline.HEIGHT.dp).clipToBounds().background(Ink)) {
                for ((slide, local) in timeline.visibleAt(frame)) {
                    key(slide.id) {
                        val wipe = if (slide.index > 0 && local < WrappedTimeline.TRANSITION_FRAMES) {
                            val (shape, turns) = transitionFor(slide.index)
                            // linearTiming: progress runs 0→1 over the transition's frames.
                            shapeWipe(shape, turns, local.toDouble() / WrappedTimeline.TRANSITION_FRAMES)
                        } else null
                        Box(Modifier.fillMaxSize().then(if (wipe != null) Modifier.clip(wipe) else Modifier)) {
                            SlideFrame(local, slide.durationInFrames, data, assets, measure).Slide(slide.id)
                        }
                    }
                }
                Grain(frame, 0.45f)
            }
        }
    }
}

@Composable
private fun SlideFrame.Slide(id: SlideId) = when (id) {
    SlideId.Intro -> Intro()
    SlideId.Minutes -> Minutes()
    SlideId.Clock -> Clock()
    SlideId.ArtistTease -> ArtistTease()
    SlideId.Artist -> Artist()
    SlideId.Artists -> Artists()
    SlideId.Song -> Song()
    SlideId.Songs -> Songs()
    SlideId.Album -> Album()
    SlideId.Age -> Age()
    SlideId.Discoveries -> Discoveries()
    SlideId.Months -> Months()
    SlideId.Streak -> Streak()
    SlideId.Summary -> Summary()
}
