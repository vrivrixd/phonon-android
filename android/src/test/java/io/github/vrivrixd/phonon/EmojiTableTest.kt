package io.github.vrivrixd.phonon

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class EmojiTableTest {
    private val table = EmojiTable(
        sequenceOf(
            "😀\trosto risonho",
            "👍\tpolegar para cima",
            "👍🏽\tpolegar para cima: pele morena",
            "❤\tcoração vermelho",
        ),
    )

    @Test fun plainTextIsUntouched() {
        assertEquals("Oi, tudo bem? 100%", table.spell("Oi, tudo bem? 100%"))
    }

    @Test fun anEmojiAddsNoPause() {
        assertEquals("Oi rosto risonho tudo bem", table.spell("Oi 😀 tudo bem"))
        assertEquals("Oi rosto risonho tudo", table.spell("Oi😀tudo"))
    }

    @Test fun anEmojiOnItsOwnIsJustItsName() {
        assertEquals("rosto risonho", table.spell("😀"))
    }

    @Test fun punctuationStaysAttached() {
        assertEquals("Oi! rosto risonho.", table.spell("Oi! 😀."))
    }

    @Test fun theLongestSequenceWins() {
        assertEquals("polegar para cima: pele morena", table.spell("👍🏽"))
    }

    @Test fun neighboursAreSeparated() {
        assertEquals("rosto risonho polegar para cima", table.spell("😀👍"))
    }

    @Test fun namesKeepTheirOwnPunctuation() {
        val names = EmojiTable(sequenceOf("😀\tfamília: homem, mulher e menina"))
        assertEquals("família: homem, mulher e menina", names.spell("😀"))
    }

    @Test fun variationSelectorsDoNotMatter() {
        assertEquals("coração vermelho", table.spell("❤️"))
    }

    @Test fun everyBundledTableIsComplete() {
        for (lang in Voices.LANGUAGES) {
            val lines = File("src/main/assets/emoji/$lang.tsv").readLines()
            assertEquals(lang, lines.size, lines.count { it.substringAfter('\t', "").isNotBlank() })
            val real = EmojiTable(lines.asSequence())
            assertEquals(lang, false, real.spell("😀").contains('\uD83D'))
        }
    }
}
