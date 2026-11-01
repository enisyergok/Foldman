package dev.foldman.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RackLogicTest {
    @Test
    fun swapExchangesTwoItems() {
        assertEquals(listOf("b", "a", "c"), listOf("a", "b", "c").swap(0, 1))
        assertEquals(listOf("a", "c", "b"), listOf("a", "b", "c").swap(2, 1))
    }

    @Test
    fun swapOutsideTheListChangesNothing() {
        val list = listOf("a", "b")
        assertEquals(list, list.swap(0, -1))
        assertEquals(list, list.swap(1, 2))
        assertEquals(emptyList<String>(), emptyList<String>().swap(0, 1))
    }
}
