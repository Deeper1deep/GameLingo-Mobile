package com.gamelingo.mobile.core.context

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextMemoryTest {

    @Test
    fun `keeps only the last maxLines lines, oldest first`() {
        val memory = ContextMemory(maxLines = 3)
        listOf("one", "two", "three", "four", "five").forEach(memory::add)
        assertEquals(listOf("three", "four", "five"), memory.snapshot())
    }

    @Test
    fun `blank lines are ignored`() {
        val memory = ContextMemory(maxLines = 5)
        memory.add("   ")
        memory.add("hello")
        assertEquals(listOf("hello"), memory.snapshot())
    }

    @Test
    fun `consecutive duplicate is not stored twice`() {
        val memory = ContextMemory(maxLines = 5)
        memory.add("hello")
        memory.add("hello")
        assertEquals(1, memory.size())
    }

    @Test
    fun `zero capacity stores nothing`() {
        val memory = ContextMemory(maxLines = 0)
        memory.add("hello")
        assertEquals(0, memory.size())
    }

    @Test
    fun `clear empties the memory`() {
        val memory = ContextMemory(maxLines = 5)
        memory.add("hello")
        memory.clear()
        assertEquals(0, memory.size())
    }
}
