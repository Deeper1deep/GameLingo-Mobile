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

class ContextMemorySceneTest {

    @Test
    fun `a gap longer than the scene gap starts a new scene and drops old context`() {
        val memory = ContextMemory(maxLines = 10, sceneGapMillis = 60_000L)
        memory.add("Marlene is looking for Robert.", 1_000L)
        memory.add("What do the Fireflies need with Robert?", 5_000L)
        assertEquals(2, memory.size())
        val newScene = memory.add("The gate is open.", 5_000L + 61_000L)
        assertEquals(true, newScene)
        assertEquals(listOf("The gate is open."), memory.snapshot())
    }

    @Test
    fun `lines within the scene gap keep the conversation together`() {
        val memory = ContextMemory(maxLines = 10, sceneGapMillis = 60_000L)
        assertEquals(false, memory.add("first", 1_000L))
        assertEquals(false, memory.add("second", 30_000L))
        assertEquals(listOf("first", "second"), memory.snapshot())
    }

    @Test
    fun `timestamp-less lines never trigger a scene break`() {
        val memory = ContextMemory(maxLines = 10, sceneGapMillis = 60_000L)
        memory.add("first", 1_000L)
        memory.add("second") // legacy add: no timestamp
        assertEquals(2, memory.size())
    }

    @Test
    fun `each line keeps its OCR or audio origin and untracked lines stay unknown`() {
        val memory = ContextMemory(maxLines = 5)
        memory.add("on screen", 1_000L, ContextMemory.Source.OCR)
        memory.add("spoken", 2_000L, ContextMemory.Source.AUDIO)
        memory.add("legacy")
        assertEquals(
            listOf(ContextMemory.Source.OCR, ContextMemory.Source.AUDIO, ContextMemory.Source.UNKNOWN),
            memory.sources(),
        )
    }
}
