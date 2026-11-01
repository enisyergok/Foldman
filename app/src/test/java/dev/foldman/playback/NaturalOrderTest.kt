package dev.foldman.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderTest {
    @Test
    fun numbersSortAsNumbers() {
        val names = listOf("10 - j", "2 - b", "1 - a", "02 - bb", "100 - z", "9 - i")
        assertEquals(listOf("1 - a", "2 - b", "02 - bb", "9 - i", "10 - j", "100 - z"), names.sortedWith(NaturalOrder))
    }

    @Test
    fun ignoresCase() {
        assertEquals(0, NaturalOrder.compare("Abba", "abba"))
        assertTrue(NaturalOrder.compare("a", "B") < 0)
    }

    @Test
    fun isConsistent() {
        val xs = listOf("a1", "a10", "a2", "b", "a", "", "10", "9", "09")
        for (a in xs) for (b in xs) {
            assertEquals("$a vs $b", -Integer.signum(NaturalOrder.compare(a, b)), Integer.signum(NaturalOrder.compare(b, a)))
        }
    }

    @Test
    fun shorterPrefixComesFirst() {
        assertTrue(NaturalOrder.compare("track", "track 2") < 0)
    }
}
