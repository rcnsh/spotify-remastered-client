package app.remastered.ui.theme

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color

/**
 * The mockups' icon set (24 px grid), from the same SVG path data. Stroke icons are 2 px
 * round-capped outlines; filled ones are solid. Tint with Icon(tint = …).
 */
object Icons {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"
    private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float) =
        "M${x + rx},${y}h${w - 2 * rx}a$rx,$rx 0 0 1 $rx,${rx}v${h - 2 * rx}a$rx,$rx 0 0 1 ${-rx},${rx}h${-(w - 2 * rx)}a$rx,$rx 0 0 1 ${-rx},${-rx}v${-(h - 2 * rx)}a$rx,$rx 0 0 1 $rx,${-rx}z"

    private fun icon(name: String, stroke: List<String> = emptyList(), fill: List<String> = emptyList(), width: Float = 2f) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            stroke.forEach {
                addPath(PathParser().parsePathString(it).toNodes(), stroke = SolidColor(Color.Black),
                    strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
            fill.forEach { addPath(PathParser().parsePathString(it).toNodes(), fill = SolidColor(Color.Black)) }
        }.build()

    val Home = icon("home", listOf("M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"))
    val HomeFilled = icon("homeF", fill = listOf("M12 2.6 2.4 10.6a1 1 0 0 0-.4.8V20a2 2 0 0 0 2 2h5v-7h6v7h5a2 2 0 0 0 2-2v-8.6a1 1 0 0 0-.4-.8z"))
    val Search = icon("search", listOf(circle(11f, 11f, 7f), "m20.5 20.5-4.3-4.3"))
    val SearchBold = icon("searchB", listOf(circle(11f, 11f, 7f), "m20.5 20.5-4.3-4.3"), width = 3f)
    val Library = icon("lib", listOf("M5 3.5v17M10 3.5v17M14.5 4.2l5 15.6"))
    val LibraryBold = icon("libB", listOf("M5 3.5v17M10 3.5v17M14.5 4.2l5 15.6"), width = 3f)
    val Play = icon("play", fill = listOf("M7 4.6v14.8a1 1 0 0 0 1.5.9l12.2-7.4a1 1 0 0 0 0-1.8L8.5 3.7A1 1 0 0 0 7 4.6z"))
    val Pause = icon("pause", fill = listOf(rect(6f, 4f, 4.2f, 16f, 1.4f), rect(13.8f, 4f, 4.2f, 16f, 1.4f)))
    val Next = icon("next", fill = listOf("M5 5.3v13.4a1 1 0 0 0 1.6.8l9-6.7a1 1 0 0 0 0-1.6l-9-6.7A1 1 0 0 0 5 5.3z", rect(17f, 4.5f, 2.6f, 15f, 1.2f)))
    val Previous = icon("prev", fill = listOf("M19 5.3v13.4a1 1 0 0 1-1.6.8l-9-6.7a1 1 0 0 1 0-1.6l9-6.7a1 1 0 0 1 1.6.8z", rect(4.4f, 4.5f, 2.6f, 15f, 1.2f)))
    val Shuffle = icon("shuffle", listOf("M2 18h1.4c1.3 0 2.5-.6 3.3-1.7l6.1-8.6C13.5 6.6 14.7 6 16 6h6M18 2l4 4-4 4M2 6h1.9c1.5 0 2.9.9 3.6 2.2M22 18h-5.9c-1.3 0-2.6-.7-3.3-1.8l-.5-.8M18 14l4 4-4 4"))
    val Repeat = icon("repeat", listOf("m17 2 4 4-4 4M3 11v-1a4 4 0 0 1 4-4h14M7 22l-4-4 4-4M21 13v1a4 4 0 0 1-4 4H3"))
    val RepeatOne = icon("repeat1", listOf("m17 2 4 4-4 4M3 11v-1a4 4 0 0 1 4-4h14M7 22l-4-4 4-4M21 13v1a4 4 0 0 1-4 4H3", "M11 10.5l1.2-.8v4.6"))
    private const val HEART = "M19 14c1.5-1.5 3-3.2 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.8 0-3 .5-4.5 2-1.5-1.5-2.7-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4 3 5.5l7 7z"
    val Heart = icon("heart", listOf(HEART))
    val HeartFilled = icon("heartF", fill = listOf(HEART))
    val Plus = icon("plus", listOf("M12 5v14M5 12h14"))
    val More = icon("more", fill = listOf(circle(5f, 12f, 1.8f), circle(12f, 12f, 1.8f), circle(19f, 12f, 1.8f)))
    val ChevronDown = icon("down", listOf("m6 9 6 6 6-6"))
    val Back = icon("back", listOf("m15 18-6-6 6-6"))
    val Forward = icon("fwd", listOf("m9 18 6-6-6-6"))
    val Devices = icon("devices", listOf(rect(2f, 5f, 13f, 10f, 1.6f), "M1.5 18.5h14", rect(17.5f, 8f, 5f, 11f, 1.3f)))
    val Queue = icon("queue", listOf("M3 6h13M3 12h9M3 18h9M21 15V5.5", circle(18.5f, 17.5f, 2.5f)))
    val Share = icon("share", listOf("M4 12v7a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7M16 6l-4-4-4 4M12 2v13"))
    val Edit = icon("edit", listOf("M17 3a2.8 2.8 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5z"))
    val Sort = icon("sort", listOf("M3 6h18M6 12h12M10 18h4"))
    val Grid = icon("grid", listOf(rect(3.5f, 3.5f, 7f, 7f, 1.5f), rect(13.5f, 3.5f, 7f, 7f, 1.5f), rect(3.5f, 13.5f, 7f, 7f, 1.5f), rect(13.5f, 13.5f, 7f, 7f, 1.5f)))
    val List = icon("list", listOf("M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"))
    val Moon = icon("moon", listOf("M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9z"))
    val Pin = icon("pin", fill = listOf("M15.5 2.5a1 1 0 0 0-1.6.3l-2 3.9-4.4 1.2a1 1 0 0 0-.4 1.7l2.8 2.8-5.6 5.6a1 1 0 0 0 1.4 1.4l5.6-5.6 2.8 2.8a1 1 0 0 0 1.7-.4l1.2-4.4 3.9-2a1 1 0 0 0 .3-1.6z"))
    val Close = icon("x", listOf("M18 6 6 18M6 6l12 12"))
    val VolumeLow = icon("volLo", listOf("M11 5 6 9H2v6h4l5 4z"))
    val Volume = icon("vol", listOf("M11 5 6 9H2v6h4l5 4z", "M15.5 8.5a5 5 0 0 1 0 7M19 5a10 10 0 0 1 0 14"))
    val Phone = icon("phone", listOf(rect(6f, 2f, 12f, 20f, 2.5f), "M11 18h2"))
    val Speaker = icon("speaker", listOf(rect(5f, 2f, 14f, 20f, 2.5f), circle(12f, 14f, 4f)), fill = listOf(circle(12f, 6.5f, 1.2f)))
    val Laptop = icon("laptop", listOf(rect(3.5f, 4.5f, 17f, 11f, 1.6f), "M1.5 19.5h21"))
    val Tv = icon("tv", listOf(rect(2f, 4f, 20f, 13f, 2f), "M8 21h8"))
    val Disc = icon("disc", listOf(circle(12f, 12f, 9.5f), circle(12f, 12f, 2.5f)))
    val User = icon("user", listOf(circle(12f, 8f, 4f), "M4 21a8 8 0 0 1 16 0"))
    val AddQueue = icon("addq", listOf("M3 6h13M3 12h9M3 18h7M18 13v8M14 17h8"))
    val AddPlaylist = icon("addpl", listOf(rect(3f, 3f, 18f, 18f, 3f), "M12 8v8M8 12h8"))
    val Remove = icon("minus", listOf(circle(12f, 12f, 9.5f), "M8 12h8"))
    val Check = icon("check", listOf("m5 12.5 4.5 4.5L19 7"))
    val Folder = icon("folder", listOf("M3 7.5V18a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-7l-2-2.5H5a2 2 0 0 0-2 2z"))
    val Settings = icon("sliders", listOf("M20 7h-9M14 17H5", circle(17f, 17f, 3f), circle(7f, 7f, 3f)))
}
