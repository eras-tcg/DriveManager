package com.eras.engine

import com.drive.drive_manager.simulator.engine.CardSource
import com.drive.drive_manager.simulator.engine.CardSources
import com.drive.drive_manager.simulator.engine.GameRandom
import com.drive.drive_manager.simulator.engine.cards.CardBundle
import kotlin.js.JsExport

/**
 * What eras-web calls (the JavaScript artifact). Plain values in and out (numbers as strings where they need 64
 * bits), so the boundary stays simple. For now a shuffle (the core's [GameRandom], the game's own), a card file's outline and
 * the programmed cards (the card bundle, installed before the engine is first used).
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
object EngineApi {
    /** The engine's version (the Gradle project version). */
    const val VERSION: String = "0.1.0-SNAPSHOT"

    /** The order of [count] cards (0..count-1) after shuffling with [seed], given as a decimal string. */
    fun shuffledOrder(seed: String, count: Int): Array<Int> {
        val cards = MutableList(count) { it }
        GameRandom.shuffle(cards, GameRandom(seed.toLong()))
        return cards.toTypedArray()
    }

    /**
     * Installs the programmed cards of a card bundle's text (eras-engine's `card-bundle.json`, fetched by the site) as the
     * engine's cards; to be called before the engine is first used. Reads every card now, so a broken bundle fails here
     * (an Error with the file and the reason). Returns how many cards it holds.
     */
    fun installCards(bundle: String): Int {
        val cards = CardBundle.read(bundle)
        CardSources.install(CardSource { cards })
        return cards.size
    }

    /** The name and effect ids of a card file's text, as "name|id1,id2". */
    fun cardSummary(text: String): String {
        val card = readCardOutline(text)
        return card.name + "|" + card.effects.joinToString(",") { it.id }
    }
}
