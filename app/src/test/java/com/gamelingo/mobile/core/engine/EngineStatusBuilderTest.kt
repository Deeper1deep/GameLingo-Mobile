package com.gamelingo.mobile.core.engine

import com.gamelingo.mobile.audio.AsrModelState
import com.gamelingo.mobile.translate.local.LocalAiModelState
import org.junit.Assert.assertEquals
import org.junit.Test

class EngineStatusBuilderTest {
    private fun build(
        gemmaOptIn: Boolean = false,
        gemma: LocalAiModelState = LocalAiModelState.NotInstalled,
        marian: LocalAiModelState = LocalAiModelState.NotInstalled,
        asr: AsrModelState = AsrModelState.NotInstalled,
        speech: Boolean = false,
        tts: Boolean = false,
    ) = EngineStatusBuilder.build("mlkit-ondevice", gemmaOptIn, gemma, marian, asr, speech, tts)

    private fun EngineStatus.row(k: EngineKey) = rows.first { it.key == k }

    @Test fun `fresh install shows nothing ready and audio off`() {
        val s = build()
        assertEquals(EngineHealth.NOT_INSTALLED, s.row(EngineKey.GEMMA).health)
        assertEquals(EngineHealth.NOT_INSTALLED, s.row(EngineKey.ASR).health)
        assertEquals(EngineHealth.OFF, s.row(EngineKey.TTS).health)
    }

    @Test fun `installed gemma without opt-in is OFF, with opt-in READY and carries size`() {
        val installed = LocalAiModelState.Installed(1, 2_000_000_000L)
        assertEquals(EngineHealth.OFF, build(gemmaOptIn = false, gemma = installed).row(EngineKey.GEMMA).health)
        val r = build(gemmaOptIn = true, gemma = installed).row(EngineKey.GEMMA)
        assertEquals(EngineHealth.READY, r.health); assertEquals(2_000_000_000L, r.sizeBytes)
    }

    @Test fun `download progress is a clamped percentage`() {
        val d = build(asr = AsrModelState.Downloading(50, 200)).row(EngineKey.ASR)
        assertEquals(EngineHealth.DOWNLOADING, d.health); assertEquals(25, d.percent)
        assertEquals(0, build(asr = AsrModelState.Downloading(5, 0)).row(EngineKey.ASR).percent)
        assertEquals(100, build(asr = AsrModelState.Downloading(900, 200)).row(EngineKey.ASR).percent)
    }

    @Test fun `installed speech model is OFF until the user enables speech`() {
        val inst = AsrModelState.Installed(70_000_000L)
        assertEquals(EngineHealth.OFF, build(asr = inst, speech = false).row(EngineKey.ASR).health)
        assertEquals(EngineHealth.READY, build(asr = inst, speech = true).row(EngineKey.ASR).health)
    }

    @Test fun `failure keeps its reason and verifying is its own state`() {
        assertEquals("wifi_required", build(marian = LocalAiModelState.Failed("wifi_required")).row(EngineKey.MARIAN).reason)
        assertEquals(EngineHealth.VERIFYING, build(marian = LocalAiModelState.Verifying).row(EngineKey.MARIAN).health)
    }

    @Test fun `planned engine id is passed through unchanged`() {
        assertEquals("mlkit-ondevice", build().plannedEngineId)
    }
}
