package com.gamelingo.mobile.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchFormatTest {
    @Test fun `a valid array parses in order`() {
        assertEquals(listOf("أ", "ب"), BatchFormat.parse("[\"أ\",\"ب\"]", 2))
    }

    @Test fun `a fenced array parses`() {
        assertEquals(listOf("أ", "ب"), BatchFormat.parse("```json\n[\"أ\",\"ب\"]\n```", 2))
    }

    @Test fun `wrong count blank and non string are rejected`() {
        assertNull(BatchFormat.parse("[\"أ\"]", 2))
        assertNull(BatchFormat.parse("[\"أ\",\"ب\",\"ج\"]", 2))
        assertNull(BatchFormat.parse("[\"أ\",\"  \"]", 2))
        assertNull(BatchFormat.parse("[\"أ\",5]", 2))
        assertNull(BatchFormat.parse("not json", 2))
    }
}
