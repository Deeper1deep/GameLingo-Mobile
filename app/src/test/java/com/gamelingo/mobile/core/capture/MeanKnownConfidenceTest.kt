package com.gamelingo.mobile.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeanKnownConfidenceTest {
    @Test fun `all zero means unavailable, not low`() = assertNull(meanKnownConfidence(listOf(0f, 0f)))
    @Test fun `empty gives none`() = assertNull(meanKnownConfidence(emptyList()))
    @Test fun `zeros are ignored in the mean`() = assertEquals(0.8f, meanKnownConfidence(listOf(0f, 0.8f, 0.8f))!!, 1e-6f)
}
