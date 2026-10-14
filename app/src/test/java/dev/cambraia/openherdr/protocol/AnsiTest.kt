package dev.cambraia.openherdr.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiTest {
    @Test
    fun `real herdr ansi read keeps text and colors`() {
        // Captured from herdr 0.9.5 pane.read with format=ansi.
        val raw = "\u001b[0m\u001b[1m\u001b[38;5;8m└─\u001b[0m\u001b[1m\u001b[38;5;4m❯\u001b[0m \u001b[0m\u001b[38;5;2mfake-agent.sh\u001b[0m\r\nfake> hi\r\n"
        val parsed = Ansi.parse(raw)
        assertEquals("└─❯ fake-agent.sh\nfake> hi\n", parsed.text)
        assertEquals(3, parsed.spans.size)
        assertEquals(AnsiStyle(fg = TermColor.Indexed(8), bold = true), parsed.spans[0].style)
        assertEquals("└─", parsed.text.substring(parsed.spans[0].start, parsed.spans[0].end))
        assertEquals(TermColor.Indexed(2), parsed.spans[2].style.fg)
        assertEquals("fake-agent.sh", parsed.text.substring(parsed.spans[2].start, parsed.spans[2].end))
    }

    @Test
    fun `basic, bright and truecolor foregrounds`() {
        val parsed = Ansi.parse("\u001b[31mr\u001b[92mg\u001b[38;2;10;20;30mt\u001b[39mx")
        assertEquals("rgtx", parsed.text)
        assertEquals(listOf<TermColor?>(TermColor.Indexed(1), TermColor.Indexed(10), TermColor.Rgb(10, 20, 30)), parsed.spans.map { it.style.fg })
    }

    @Test
    fun `reset clears every attribute`() {
        val parsed = Ansi.parse("\u001b[1;4;31ma\u001b[mb")
        assertEquals(1, parsed.spans.size)
        assertEquals(AnsiStyle(fg = TermColor.Indexed(1), bold = true, underline = true), parsed.spans[0].style)
    }

    @Test
    fun `non-SGR escapes and control bytes are dropped`() {
        val parsed = Ansi.parse("\u001b]0;title\u0007a\u001b[2Kb\u001b(Bc\u0008d\u001b]8;;http://x\u001b\\e")
        assertEquals("abcde", parsed.text)
        assertTrue(parsed.spans.isEmpty())
    }

    @Test
    fun `truncated sequences at the end do not crash`() {
        assertEquals("ok", Ansi.parse("ok\u001b[38;5").text)
        assertEquals("ok", Ansi.parse("ok\u001b").text)
        assertEquals("a", Ansi.parse("\u001b[38;5;999ma").text)
    }

    @Test
    fun `xterm palette cube and grays`() {
        assertEquals(0x000000, Ansi.xterm256(16))
        assertEquals(0xffffff, Ansi.xterm256(231))
        assertEquals(0x5f87af, Ansi.xterm256(67))
        assertEquals(0x080808, Ansi.xterm256(232))
        assertEquals(0xeeeeee, Ansi.xterm256(255))
    }
}
