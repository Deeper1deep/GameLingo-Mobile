package com.gamelingo.mobile.core.quality

import com.gamelingo.mobile.core.quality.TranslationQualityGate.Issue
import com.gamelingo.mobile.data.settings.ArabicTranslationMode
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedRetryTest {

    private fun run(vararg answers: String): Triple<BoundedRetry.Outcome<String>, Int, Unit> {
        var calls = 0
        val outcome = runBlocking {
            BoundedRetry.run(
                first = { calls++; answers[0] },
                retry = { calls++; answers[1] },
                isCorrupt = { it == "bad" },
            )
        }
        return Triple(outcome, calls, Unit)
    }

    @Test fun `a good first answer is accepted with no retry`() {
        val (o, calls, _) = run("good", "good")
        assertTrue(o.accepted); assertFalse(o.retried); assertEquals(1, calls)
    }

    @Test fun `a bad first answer retries once and accepts a good second`() {
        val (o, calls, _) = run("bad", "good")
        assertTrue(o.accepted); assertTrue(o.retried); assertEquals(2, calls); assertEquals("good", o.value)
    }

    @Test fun `two bad answers are rejected after exactly two calls`() {
        val (o, calls, _) = run("bad", "bad")
        assertFalse(o.accepted); assertTrue(o.retried); assertEquals(2, calls)
    }

    private fun corrupt(source: String, output: String) = TranslationQualityGate.isCorrupt(
        TranslationQualityGate.inspect(source, output, ArabicTranslationMode.EGYPTIAN_ARABIC),
    )

    @Test fun `empty output is corrupt`() {
        assertTrue(corrupt("Get down!", "   "))
    }

    @Test fun `a looping output is corrupt`() {
        assertTrue(corrupt("Get in the car now", "اركب اركب اركب اركب اركب"))
    }

    @Test fun `a normal short translation is not corrupt`() {
        assertFalse(corrupt("That's just easy money.", "دي فلوس سهلة."))
    }

    @Test fun `a character name kept as is is not corrupt`() {
        assertFalse(corrupt("Marcus", "Marcus"))
    }

    @Test fun `corrupting set does not include the advisory issues`() {
        assertFalse(Issue.NEGATION_LOST in TranslationQualityGate.CORRUPTING)
        assertFalse(Issue.UNTRANSLATED in TranslationQualityGate.CORRUPTING)
    }

    @Test fun `cancellation during the first attempt propagates and never starts the retry`() {
        var retried = false
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            BoundedRetry.run(
                first = { kotlinx.coroutines.delay(10_000); "bad" },
                retry = { retried = true; "good" },
                isCorrupt = { it == "bad" },
            )
        }
        runBlocking { kotlinx.coroutines.delay(100); job.cancelAndJoin() }
        assertTrue(job.isCancelled); assertFalse(retried)
    }
}
