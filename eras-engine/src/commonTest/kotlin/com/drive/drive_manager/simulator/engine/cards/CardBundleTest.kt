package com.drive.drive_manager.simulator.engine.cards

import com.drive.drive_manager.simulator.TestCards
import com.drive.drive_manager.simulator.TestFiles
import com.drive.drive_manager.simulator.engine.CardEffects
import com.eras.engine.EngineApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The build's bundle: every card file, in the order of their paths, all read. */
class CardBundleTest {

    private val bundle = TestFiles.text("ERAS_CARD_BUNDLE")

    @Test
    fun holdsEveryCardFileInOrder() {
        val files = CardBundle.files(bundle)
        assertEquals(358, files.size)
        assertEquals(files.map { it.path }.sorted(), files.map { it.path })
        assertTrue(files.all { it.path.endsWith(".json") && it.path.count { c -> c == '/' } == 1 }, "cards/<edition>/<card>.json")
    }

    @Test
    fun readsEveryCard() {
        val cards = CardBundle.read(bundle)
        assertEquals(358, cards.size)
        assertEquals(358, cards.map { it.name }.toSet().size)
        assertTrue(cards.any { it.name == "falis" && it.effects().single().id == "falis:en-tributos" })
    }

    @Test
    fun isWhatTheEngineRuns() {
        TestCards.install()
        assertEquals(CardBundle.read(bundle).first { it.name == "falis" }.effects(), CardEffects.of("falis"))
        // What the site calls (installing again once the engine runs changes nothing)
        assertEquals(358, EngineApi.installCards(bundle))
    }
}
