package com.gamelingo.mobile.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateGuardsTest {

    @Test
    fun `newer apk matching the manifest is accepted`() =
        assertTrue(UpdateGuards.versionAcceptable(18, 18, 17))

    @Test
    fun `same or older apk is refused`() {
        assertFalse(UpdateGuards.versionAcceptable(17, 17, 17))
        assertFalse(UpdateGuards.versionAcceptable(16, 16, 17))
    }

    @Test
    fun `apk version that disagrees with the manifest is refused`() =
        assertFalse(UpdateGuards.versionAcceptable(19, 18, 17))

    @Test
    fun `unreadable apk version is refused`() =
        assertFalse(UpdateGuards.versionAcceptable(null, 18, 17))

    @Test
    fun `storage gate needs the apk plus headroom`() {
        val mb = 1024L * 1024
        assertTrue(UpdateGuards.hasEnoughStorage(200 * mb, 100 * mb))
        assertFalse(UpdateGuards.hasEnoughStorage(120 * mb, 100 * mb))
        assertFalse(UpdateGuards.hasEnoughStorage(10 * mb, null))
    }
}
