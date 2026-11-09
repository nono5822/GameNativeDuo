package app.gamenative.duo.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnityScreenArgsTest {
    @Test
    fun `add to empty args returns only the screen pair`() {
        assertEquals(
            "-screen-width 1920 -screen-height 2160",
            UnityScreenArgs.add("", 1920, 2160),
        )
    }

    @Test
    fun `add to unrelated args appends the screen pair`() {
        assertEquals(
            "-nolog -screen-width 1920 -screen-height 2160",
            UnityScreenArgs.add("-nolog", 1920, 2160),
        )
    }

    @Test
    fun `add replaces an older screen pair and keeps other args`() {
        val existing = "-nolog -screen-width 1280 -screen-height 720 -force-d3d11"

        assertEquals(
            "-nolog -force-d3d11 -screen-width 1920 -screen-height 2160",
            UnityScreenArgs.add(existing, 1920, 2160),
        )
    }

    @Test
    fun `remove strips the screen pair from the middle of args`() {
        assertEquals(
            "-nolog -force-d3d11",
            UnityScreenArgs.remove("-nolog -screen-width 1920 -screen-height 2160 -force-d3d11"),
        )
    }

    @Test
    fun `remove strips the whole args string when it is only the screen pair`() {
        assertEquals(
            "",
            UnityScreenArgs.remove("-screen-width 1920 -screen-height 2160"),
        )
    }

    @Test
    fun `remove leaves args without a screen pair unchanged`() {
        assertEquals(
            "-popupwindow",
            UnityScreenArgs.remove("-popupwindow"),
        )
    }

    @Test
    fun `remove leaves a lone screen width unchanged`() {
        assertEquals(
            "-screen-width 1920",
            UnityScreenArgs.remove("-screen-width 1920"),
        )
    }

    @Test
    fun `parse returns the screen pair when present`() {
        assertEquals(
            Pair(1440, 1620),
            UnityScreenArgs.parse("-a -screen-width 1440 -screen-height 1620"),
        )
    }

    @Test
    fun `parse returns null when no screen pair is present`() {
        assertNull(UnityScreenArgs.parse("-a"))
    }

    @Test
    fun `size parses a valid width by height string`() {
        assertEquals(
            Pair(1920, 2160),
            UnityScreenArgs.size("1920x2160"),
        )
    }

    @Test
    fun `size returns null for invalid screen sizes`() {
        assertNull(UnityScreenArgs.size(null))
        assertNull(UnityScreenArgs.size(""))
        assertNull(UnityScreenArgs.size("1920"))
        assertNull(UnityScreenArgs.size("0x10"))
        assertNull(UnityScreenArgs.size("axb"))
    }
}
