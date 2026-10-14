package dev.cambraia.openherdr.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerInitialsTest {
    @Test
    fun parenthesesDistinguishTheSameMachine() {
        assertEquals("WF", serverInitials("PC (Wi-Fi)"))
        assertEquals("WG", serverInitials("PC (WireGuard)"))
    }

    @Test
    fun plainNames() {
        assertEquals("HP", serverInitials("Home PC"))
        assertEquals("LA", serverInitials("laptop"))
        assertEquals("?", serverInitials("--"))
    }
}
