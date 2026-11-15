package com.drive.drive_manager.simulator.engine.cards

import com.drive.drive_manager.simulator.TestCards
import com.drive.drive_manager.simulator.TestFiles
import com.drive.drive_manager.simulator.engine.CardEffects
import com.eras.engine.EngineApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The build's bundle: every card file, in the order of their paths, all read. */
class CardBundleTest {

    private val bundle = TestFiles.text("ERAS_CARD_BUNDLE")

    @Test
    fun holdsEveryCardFileInOrder() {
        val files = CardBundle.files(bundle)
        assertEquals(356, files.size)
        assertEquals(files.map { it.path }.sorted(), files.map { it.path })
        assertTrue(files.all { it.path.endsWith(".json") && it.path.count { c -> c == '/' } == 1 }, "cards/<edition>/<card>.json")
    }

    @Test
    fun readsEveryCard() {
        val cards = CardBundle.read(bundle)
        assertEquals(356, cards.size)
        assertEquals(356, cards.map { it.name }.toSet().size)
        assertTrue(cards.any { it.name == "falis" && it.effects().single().id == "falis:en-tributos" })
    }

    @Test
    fun refusesTheSameCardWrittenOtherwise() {
        val card = { name: String -> "{\n  \"name\" : \"$name\",\n  \"reactions\" : [ {\n    \"type\" : \"DamageReplacement\",\n    \"maxDamage\" : 8,\n    \"reduce\" : 1\n  } ]\n}" }
        val twice = assertFailsWith<IllegalStateException> {
            CardBundle.read(listOf(CardBundle.File("E1/roto-g0.json", card("roto g0")), CardBundle.File("E2/roto-g0-2.json", card("roto-g0"))))
        }
        assertTrue(twice.message!!.contains("Carta en dos archivos"), twice.message)
    }

    @Test
    fun findsACardByItsNameWithOrWithoutAccentsAndHyphens() {
        TestCards.install()
        assertEquals("roto-g0", CardEffects.nameOf("Roto G0"))
        assertEquals("deriduragón", CardEffects.nameOf("Deriduragon"))
        assertEquals("sar-2", CardEffects.nameOf(" SAR 2 "))
        assertEquals(CardEffects.of("sar-2"), CardEffects.of("SAR 2"))
        assertTrue(CardEffects.of("SAR 2").isNotEmpty())
        // A name no card file has stays as it is
        assertEquals("carta sin archivo", CardEffects.nameOf("Carta Sin Archivo"))
    }

    @Test
    fun isWhatTheEngineRuns() {
        TestCards.install()
        assertEquals(CardBundle.read(bundle).first { it.name == "falis" }.effects(), CardEffects.of("falis"))
        // What the site calls (installing again once the engine runs changes nothing)
        assertEquals(356, EngineApi.installCards(bundle))
    }
}
