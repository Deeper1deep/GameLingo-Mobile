package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.settings.ArabicTranslationMode
import com.gamelingo.mobile.data.settings.DataStoreSettingsRepository
import com.gamelingo.mobile.data.settings.UserGlossaryTerm
import com.gamelingo.mobile.translate.local.EgyptianDictionary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** User-pinned glossary terms: storage, parsing, and dictionary layering. */
class UserGlossaryTest {

    @Test
    fun `user terms survive a repository round trip`() = runTest {
        val repo = FakeSettingsRepository()
        repo.setUserGlossaryTerms(listOf(UserGlossaryTerm("Joel", "جويل")))
        assertEquals(listOf(UserGlossaryTerm("Joel", "جويل")), repo.settings.first().userGlossaryTerms)
    }

    @Test
    fun `a corrupt stored glossary degrades to empty, never a crash`() {
        assertEquals(emptyList<UserGlossaryTerm>(), com.gamelingo.mobile.data.settings.parseUserGlossary("{broken"))
        assertEquals(emptyList<UserGlossaryTerm>(), com.gamelingo.mobile.data.settings.parseUserGlossary(null))
        assertEquals(
            emptyList<UserGlossaryTerm>(),
            com.gamelingo.mobile.data.settings.parseUserGlossary("""[{"en":"  ","ar":"x"}]"""),
        )
    }

    @Test
    fun `stored glossary parses back to the same terms`() {
        val terms = listOf(UserGlossaryTerm("Fireflies", "اليراعات"), UserGlossaryTerm("Joel", "جويل"))
        val json = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(UserGlossaryTerm.serializer()), terms,
        )
        assertEquals(terms, com.gamelingo.mobile.data.settings.parseUserGlossary(json))
    }

    private fun dictionary(): EgyptianDictionary = EgyptianDictionary.load(
        """
        {
          "version": 3,
          "factions": [ {"en": "Fireflies", "ar": "اليراعات"} ],
          "weapons": [ {"en": "shotgun", "ar": "بندقية خرطوش"} ],
          "military_terms": [ {"en": "checkpoint", "ar": "نقطة تفتيش"} ],
          "missions": [ {"en": "side quest", "ar": "مهمة جانبية"} ]
        }
        """.trimIndent(),
    )

    @Test
    fun `gaming world layers serve both modes with a consistent rendering`() {
        val source = "The Fireflies left a shotgun at the checkpoint after the side quest."
        for (mode in ArabicTranslationMode.entries) {
            val found = dictionary().glossaryTerms(source, mode)
            assertEquals("اليراعات", found["Fireflies"])
            assertEquals("بندقية خرطوش", found["shotgun"])
            assertEquals("نقطة تفتيش", found["checkpoint"])
            assertEquals("مهمة جانبية", found["side quest"])
        }
    }

    @Test
    fun `a faction name is not treated as a common word`() {
        // "Fireflies" the insects would be a literal translation; the
        // faction layer pins the recognized name instead.
        val found = dictionary().glossaryTerms("What do the Fireflies need?", ArabicTranslationMode.MODERN_STANDARD_ARABIC)
        assertFalse(found.containsValue("يراعات تطير"))
    }
}
