package app.remastered.core.wrapped

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Measures a single line of text in one font, in the story's logical pixels. Width is taken to
 * scale linearly with size (as Remotion's fitText assumes), so implementations measure once at
 * [SAMPLE_SIZE] and scale.
 */
fun interface TextWidth {
    /** Width of [text] at [fontSize] logical px. */
    fun width(text: String, fontSize: Double): Double

    companion object {
        const val SAMPLE_SIZE = 100.0
    }
}

/** Remotion's `fitText`: the font size at which [text] exactly fills [withinWidth]. */
fun fitText(text: String, withinWidth: Double, measure: TextWidth): Double {
    val estimate = measure.width(text, TextWidth.SAMPLE_SIZE)
    return if (estimate <= 0) TextWidth.SAMPLE_SIZE else withinWidth / estimate * TextWidth.SAMPLE_SIZE
}

data class FitLines(val fontSize: Double, val lines: List<String>)

/**
 * Remotion's `fitTextOnNLines`: the largest size (to 0.01, at most [maxFontSize]) at which [text]
 * word-wraps onto at most [maxLines] lines of [maxBoxWidth], and those lines. Ported with its
 * quirks (a word is measured with its leading space, trimmed when it starts a line).
 */
fun fitTextOnNLines(text: String, maxBoxWidth: Double, maxLines: Int, maxFontSize: Double, measure: TextWidth): FitLines {
    val precision = 100
    val minFontSize = 0.1
    var left = floor(minFontSize * precision).toInt()
    var right = floor(maxFontSize * precision).toInt()
    var optimalFontSize = minFontSize
    var optimalLines = emptyList<String>()
    val words = text.split(' ')
    while (left <= right) {
        val mid = (left + right) / 2
        val fontSize = mid.toDouble() / precision
        val box = Array(maxLines) { ArrayList<String>() }
        var exceeds = false
        var currentLine = 0
        val lines = arrayListOf(StringBuilder())
        for (word in words) {
            val piece = if (lines[currentLine].isEmpty()) word else " $word"
            val result = addToBox(box, maxBoxWidth, maxLines, piece, fontSize, measure)
            if (result == Add.Exceeds) {
                exceeds = true
                break
            }
            if (result == Add.NewLine) {
                lines.add(StringBuilder())
                currentLine++
            }
            lines[currentLine].append(word).append(' ')
        }
        if (!exceeds && currentLine < maxLines) {
            optimalFontSize = fontSize
            optimalLines = lines.map { it.toString() }
            left = mid + 1
        } else {
            right = mid - 1
        }
    }
    return FitLines(optimalFontSize, optimalLines.map { it.trimEnd() })
}

private enum class Add { Fits, NewLine, Exceeds }

/** Remotion's `fillTextBox().add`. */
private fun addToBox(lines: Array<ArrayList<String>>, maxBoxWidth: Double, maxLines: Int, text: String, fontSize: Double, measure: TextWidth): Add {
    val lastIndex = lines.indexOfLast { it.isNotEmpty() }
    val currentlyAt = if (lastIndex == -1) 0 else lastIndex
    val line = lines[currentlyAt]
    val widthWithWord = line.sumOf { measure.width(it, fontSize) } + measure.width(text, fontSize)
    if (ceil(widthWithWord) <= maxBoxWidth) {
        line.add(if (line.isEmpty()) text.trimStart() else text)
        return Add.Fits
    }
    if (currentlyAt == maxLines - 1) return Add.Exceeds
    val alone = text.trimStart()
    if (ceil(measure.width(alone, fontSize)) > maxBoxWidth) return Add.Exceeds
    lines[currentlyAt + 1] = arrayListOf(alone)
    return Add.NewLine
}
