package com.eras.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The outline of a card file (resources/cards/<edition>/<card>.json): its name and the ids and moments of its
 * effects. Read with kotlinx.serialization, which works the same on the JVM and in JavaScript (Jackson does not
 * run in the browser).
 */
data class CardOutline(val name: String, val effects: List<EffectOutline>)

/** One effect of a card: its id ("a-la-carga:en-juego") and when it applies ("ON_PLAY"). */
data class EffectOutline(val id: String, val moment: String?)

private val json = Json { ignoreUnknownKeys = true }

/** Reads the outline of a card file's text. */
fun readCardOutline(text: String): CardOutline {
    val root: JsonObject = json.parseToJsonElement(text).jsonObject
    val effects = root["effects"]?.jsonArray.orEmpty().map { element ->
        val effect = element.jsonObject
        EffectOutline(
            id = effect.getValue("id").jsonPrimitive.content,
            moment = effect["moment"]?.jsonPrimitive?.content,
        )
    }
    return CardOutline(root.getValue("name").jsonPrimitive.content, effects)
}
