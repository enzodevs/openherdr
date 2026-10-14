package dev.cambraia.openherdr.protocol

/** A terminal color: one of the 256 indexed colors or a 24-bit RGB value. */
sealed interface TermColor {
    data class Indexed(val index: Int) : TermColor
    data class Rgb(val r: Int, val g: Int, val b: Int) : TermColor
}

data class AnsiStyle(
    val fg: TermColor? = null,
    val bg: TermColor? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
)

data class AnsiSpan(val start: Int, val end: Int, val style: AnsiStyle)

data class AnsiText(val text: String, val spans: List<AnsiSpan>)

/**
 * Parses terminal output from herdr's `format: "ansi"` reads. SGR sequences become styled
 * spans; every other escape (cursor moves, OSC titles, charset switches) is dropped.
 */
object Ansi {
    private const val ESC = '\u001b'
    private const val BEL = '\u0007'

    fun parse(input: String): AnsiText {
        val out = StringBuilder(input.length)
        val spans = mutableListOf<AnsiSpan>()
        var style = AnsiStyle()
        var spanStart = 0
        var i = 0

        fun flush() {
            if (out.length > spanStart && style != AnsiStyle()) spans += AnsiSpan(spanStart, out.length, style)
            spanStart = out.length
        }

        while (i < input.length) {
            val c = input[i]
            when {
                c == ESC && i + 1 < input.length && input[i + 1] == '[' -> {
                    var j = i + 2
                    while (j < input.length && input[j].code in 0x30..0x3f) j++ // parameter bytes
                    while (j < input.length && input[j].code in 0x20..0x2f) j++ // intermediate bytes
                    if (j >= input.length) break
                    if (input[j] == 'm') {
                        flush()
                        style = applySgr(style, input.substring(i + 2, j))
                    }
                    i = j + 1
                }
                c == ESC && i + 1 < input.length && input[i + 1] == ']' -> {
                    // OSC: terminated by BEL or ESC \
                    var j = i + 2
                    while (j < input.length && input[j] != BEL && !(input[j] == ESC && j + 1 < input.length && input[j + 1] == '\\')) j++
                    i = if (j < input.length && input[j] == BEL) j + 1 else j + 2
                }
                c == ESC -> i += if (i + 1 < input.length && input[i + 1] in "()*+") 3 else 2
                c == '\r' -> i++
                c.code < 0x20 && c != '\n' && c != '\t' -> i++
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        flush()
        return AnsiText(out.toString(), spans)
    }

    private fun applySgr(current: AnsiStyle, params: String): AnsiStyle {
        val codes = if (params.isEmpty()) listOf(0) else params.split(';', ':').map { it.toIntOrNull() ?: 0 }
        var s = current
        var k = 0
        while (k < codes.size) {
            when (val code = codes[k]) {
                0 -> s = AnsiStyle()
                1 -> s = s.copy(bold = true)
                2 -> s = s.copy(dim = true)
                3 -> s = s.copy(italic = true)
                4 -> s = s.copy(underline = true)
                7 -> s = s.copy(inverse = true)
                22 -> s = s.copy(bold = false, dim = false)
                23 -> s = s.copy(italic = false)
                24 -> s = s.copy(underline = false)
                27 -> s = s.copy(inverse = false)
                in 30..37 -> s = s.copy(fg = TermColor.Indexed(code - 30))
                in 90..97 -> s = s.copy(fg = TermColor.Indexed(code - 90 + 8))
                in 40..47 -> s = s.copy(bg = TermColor.Indexed(code - 40))
                in 100..107 -> s = s.copy(bg = TermColor.Indexed(code - 100 + 8))
                39 -> s = s.copy(fg = null)
                49 -> s = s.copy(bg = null)
                38, 48 -> {
                    val color = when (codes.getOrNull(k + 1)) {
                        5 -> codes.getOrNull(k + 2)?.takeIf { it in 0..255 }?.let { TermColor.Indexed(it) }.also { k += 2 }
                        2 -> {
                            val rgb = codes.subList(minOf(k + 2, codes.size), minOf(k + 5, codes.size))
                            k += 4
                            if (rgb.size == 3 && rgb.all { it in 0..255 }) TermColor.Rgb(rgb[0], rgb[1], rgb[2]) else null
                        }
                        else -> null
                    }
                    s = if (code == 38) s.copy(fg = color) else s.copy(bg = color)
                }
            }
            k++
        }
        return s
    }

    /** xterm 256-color palette as 0xRRGGBB, for indices 16..255; 0..15 come from the theme. */
    fun xterm256(index: Int): Int = when {
        index < 16 -> error("theme color")
        index < 232 -> {
            val i = index - 16
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            (steps[i / 36] shl 16) or (steps[(i / 6) % 6] shl 8) or steps[i % 6]
        }
        else -> {
            val v = 8 + (index - 232) * 10
            (v shl 16) or (v shl 8) or v
        }
    }
}
