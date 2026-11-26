package dev.cambraia.openherdr.transcript

/**
 * Answers an agent's choice question by key presses, as the user would in the terminal. Both
 * Pi and Claude Code draw a cursor before the selected option (Pi `› `, Claude `❯ `) and select
 * with Down and Enter, so option `i` is `down × i, enter` when the cursor is on the first one.
 *
 * Keys are sent blind, so they are only computed when the screen proves the state: the
 * question is visible near the bottom and the cursor sits on its first option. Anything else
 * (answered on the PC already, cursor moved, a different prompt) returns null.
 */
object QuestionKeys {
    private const val TAIL_LINES = 80
    private const val PROBE = 24
    private val CURSORS = charArrayOf('❯', '›')

    fun choose(ask: ChatItem.Ask, index: Int, screen: String): List<String>? {
        if (index !in ask.options.indices) return null
        val tail = screen.lines().takeLast(TAIL_LINES)
        val probe = normalize(ask.question).take(PROBE)
        if (probe.isEmpty() || probe !in normalize(tail.joinToString("\n"))) return null
        val first = normalize(ask.options[0].label).take(PROBE)
        val onFirst = tail.any { line ->
            val cursor = line.indexOfAny(CURSORS)
            cursor >= 0 && first.isNotEmpty() && normalize(line.substring(cursor)).contains(first)
        }
        if (!onFirst) return null
        return List(index) { "down" } + "enter"
    }

    /** Wrapping, borders and numbering differ per agent; letters and digits do not. */
    private fun normalize(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()
}
