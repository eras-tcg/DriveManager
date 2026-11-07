package com.eras.engine

import kotlin.js.JsExport

/**
 * What eras-web calls (the JavaScript artifact). Plain values in and out (numbers as strings where they need 64
 * bits), so the boundary stays simple. Phase 0: a shuffle and a card file's outline, to prove the round trip.
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
object EngineApi {
    /** The engine's version (the Gradle project version). */
    const val VERSION: String = "0.1.0-SNAPSHOT"

    /** The order of [count] cards (0..count-1) after shuffling with [seed], given as a decimal string. */
    fun shuffledOrder(seed: String, count: Int): Array<Int> {
        val cards = MutableList(count) { it }
        cards.shuffleAs(GameRandom(seed.toLong()))
        return cards.toTypedArray()
    }

    /** The name and effect ids of a card file's text, as "name|id1,id2". */
    fun cardSummary(text: String): String {
        val card = readCardOutline(text)
        return card.name + "|" + card.effects.joinToString(",") { it.id }
    }
}
