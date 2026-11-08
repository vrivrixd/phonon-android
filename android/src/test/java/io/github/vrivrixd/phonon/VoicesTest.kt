package io.github.vrivrixd.phonon

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicesTest {
    @Test fun thePhonesLanguageComesFirstThenEnglish() {
        assertEquals(listOf("pt", "en", "es", "fr", "de"), Voices.languagesFor("pt"))
        assertEquals(listOf("de", "en", "pt", "es", "fr"), Voices.languagesFor("de"))
        assertEquals(listOf("en", "pt", "es", "fr", "de"), Voices.languagesFor("en"))
    }

    @Test fun anUnsupportedLanguageFallsBackToEnglish() {
        assertEquals(listOf("en", "pt", "es", "fr", "de"), Voices.languagesFor("it"))
    }
}
