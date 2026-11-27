package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.settings.UserGlossaryTerm
import com.gamelingo.mobile.data.settings.parseUserGlossary
import org.junit.Assert.assertEquals
import org.junit.Test

class GlossaryFieldsCompatTest {
    @Test fun `terms saved before the extra fields existed still load`() {
        val old = """[{"en":"Joel","ar":"جويل"}]"""
        assertEquals(listOf(UserGlossaryTerm("Joel", "جويل")), parseUserGlossary(old))
    }

    @Test fun `extra fields round trip`() {
        val json = kotlinx.serialization.json.Json
        val term = UserGlossaryTerm("Joel", "جويل", game = "TLOU", pronunciation = "جويل", note = "main character")
        val back = parseUserGlossary(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(UserGlossaryTerm.serializer()), listOf(term)))
        assertEquals(listOf(term), back)
    }
}
