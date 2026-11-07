package com.eras.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/** A card file is read the same on every platform (kotlinx.serialization, not Jackson). */
class CardFileTest {
    // The start of resources/cards/E1/a-la-carga.json, with a second effect added
    private val text = """
        {
          "name" : "a la carga!",
          "effects" : [ {
            "id" : "a-la-carga:en-juego",
            "moment" : "ON_PLAY",
            "mandatory" : true,
            "steps" : [ { "type" : "AlliesGain", "filter" : { "cardType" : "creature" } } ]
          }, {
            "id" : "a-la-carga:otro"
          } ]
        }
    """.trimIndent()

    @Test
    fun readsNameAndEffects() {
        val card = readCardOutline(text)
        assertEquals("a la carga!", card.name)
        assertEquals(listOf(EffectOutline("a-la-carga:en-juego", "ON_PLAY"), EffectOutline("a-la-carga:otro", null)), card.effects)
    }

    @Test
    fun summarizesForTheSite() {
        assertEquals("a la carga!|a-la-carga:en-juego,a-la-carga:otro", EngineApi.cardSummary(text))
    }
}
