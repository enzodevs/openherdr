package dev.cambraia.openherdr.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DraftsTest {
    @Test
    fun dictationIsAddedAfterWhatWasAlreadyWritten() {
        assertEquals("first take", Drafts.join("", " first take "))
        assertEquals("first take second take", Drafts.join("first take \n", "second take"))
        assertEquals("kept", Drafts.join("kept", "  "))
    }
}
