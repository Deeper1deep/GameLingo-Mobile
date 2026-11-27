package com.gamelingo.mobile.core.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSelectionTest {

    private fun memory(vararg lines: String) = ContextMemory(maxLines = 20).also { m ->
        lines.forEachIndexed { i, l -> m.add(l, 1_000L + i * 2_000L) }
    }

    @Test fun `the previous line is kept so a reply sees its question`() {
        val m = memory("Where did he go?")
        assertEquals(listOf("Where did he go?"), m.select("I don't know.", 3, 400))
    }

    @Test fun `unrelated older lines are dropped`() {
        val m = memory("The weather is nice today.", "Marcus took the truck.", "Where did he go?")
        val picked = m.select("Marcus is not back yet.", 3, 400)
        assertEquals(listOf("Marcus took the truck.", "Where did he go?"), picked)
    }

    @Test fun `the line budget is respected newest first`() {
        val m = memory("Marcus one.", "Marcus two.", "Marcus three.", "Marcus four.")
        val picked = m.select("Marcus again", 2, 400)
        assertEquals(listOf("Marcus three.", "Marcus four."), picked)
    }

    @Test fun `the character budget is respected`() {
        val long = "Marcus " + "x".repeat(300)
        val m = memory(long, "Marcus is here.")
        val picked = m.select("Marcus again", 5, 100)
        assertEquals(listOf("Marcus is here."), picked)
    }

    @Test fun `empty memory and zero budgets give nothing`() {
        assertTrue(memory().select("Hello there", 3, 400).isEmpty())
        assertTrue(memory("Hello").select("Hello there", 0, 400).isEmpty())
        assertTrue(memory("Hello").select("Hello there", 3, 0).isEmpty())
    }

    @Test fun `cleared memory gives nothing`() {
        val m = memory("Marcus took the truck.")
        m.clear()
        assertTrue(m.select("Marcus is back", 3, 400).isEmpty())
    }
}
