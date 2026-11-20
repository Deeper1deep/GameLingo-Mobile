package com.gamelingo.mobile.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun `equal versions compare as zero`() {
        assertEquals(0, VersionComparator.compare("1.2.3", "1.2.3"))
    }

    @Test
    fun `missing segment counts as zero`() {
        assertEquals(0, VersionComparator.compare("1.2", "1.2.0"))
    }

    @Test
    fun `numeric segments compare numerically not lexically`() {
        assertTrue(VersionComparator.compare("1.10.0", "1.9.9") > 0)
        assertTrue(VersionComparator.compare("1.9.9", "1.10.0") < 0)
    }

    @Test
    fun `leading v is ignored`() {
        assertEquals(0, VersionComparator.compare("v2.0.0", "2.0.0"))
    }

    @Test
    fun `non numeric suffix on a segment is ignored`() {
        assertEquals(0, VersionComparator.compare("1.4.0-beta", "1.4.0"))
    }

    @Test
    fun `isNewer only true for strictly newer`() {
        assertTrue(VersionComparator.isNewer("1.0.1", "1.0.0"))
        assertFalse(VersionComparator.isNewer("1.0.0", "1.0.0"))
        assertFalse(VersionComparator.isNewer("0.9.9", "1.0.0"))
    }
}
