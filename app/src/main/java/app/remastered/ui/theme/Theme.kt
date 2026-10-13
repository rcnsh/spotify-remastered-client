package app.remastered.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import app.remastered.R

/** Palette sampled from the user's Spicetify theme (docs/design/README.md). */
object Palette {
    val bg = Color(0xFF1A1E2E)
    val base = Color(0xFF1E2233)
    val surface = Color(0xFF23283D)
    val raised = Color(0xFF2B3049)
    val selected = Color(0xFF32364A)
    val line = Color(0x12FFFFFF)
    val text = Color(0xFFF3F4F8)
    val sub = Color(0xFFA3A9BF)
    val dim = Color(0xFF6F7690)
    val accent = Color(0xFF7289DA)
    val accentLight = Color(0xFFA9B8FF)
    val accentSoft = Color(0x2E7289DA)
    val progress = Color(0xFF1ED760)
    val track = Color(0xFF485686)
    val heart = Color(0xFFEF5B74)
    val chipOn = Color(0xFFE9EBF3)
    val onChipOn = Color(0xFF151827)
}

@OptIn(ExperimentalTextApi::class)
private fun figtree(weight: Int) = Font(
    R.font.figtree, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Figtree = FontFamily(listOf(400, 500, 600, 700, 800, 900).map(::figtree))

/** Type scale from the mockups. Japanese falls back to the system CJK font. */
object Type {
    private fun s(size: Int, weight: Int, tracking: Double = 0.0, line: Int? = null) = TextStyle(
        fontFamily = Figtree, fontSize = size.sp, fontWeight = FontWeight(weight),
        letterSpacing = (tracking * size).sp, lineHeight = (line ?: (size * 1.25).toInt()).sp,
    )
    val display = s(30, 900, -0.03, 32)
    val title = s(28, 800, -0.025)
    val headline = s(22, 800, -0.02)
    val section = s(21, 800, -0.015)
    val sectionSmall = s(18, 800, -0.01)
    val body = s(15, 600)
    val bodyRegular = s(15, 500)
    val label = s(13, 600)
    val caption = s(13, 500, line = 17)
    val tiny = s(11, 700, 0.12)
}

@Composable
fun RemasteredTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent, onPrimary = Color.White,
            background = Palette.base, onBackground = Palette.text,
            surface = Palette.surface, onSurface = Palette.text,
            surfaceVariant = Palette.raised, onSurfaceVariant = Palette.sub,
            surfaceContainer = Palette.surface, surfaceContainerHigh = Palette.raised,
            surfaceContainerLow = Palette.base, outline = Palette.line,
            secondary = Palette.accentLight, error = Palette.heart,
        ),
        typography = Typography(
            bodyLarge = Type.bodyRegular, bodyMedium = Type.caption, labelLarge = Type.label,
            titleLarge = Type.headline, titleMedium = Type.body, headlineMedium = Type.title,
        ),
        content = content,
    )
}
