package com.eras.engine.local

import com.drive.drive_manager.simulator.room.GameAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The body the board publishes to `/app/room/{id}/action` (`{"type": …, "payload": {…}}`) read as the server reads it:
 * Spring's Jackson into a [GameAction], the payload as Jackson's untyped values (a whole number as an `Int` when it fits,
 * else a `Long`; a number with a fraction or an exponent as a `Double`; arrays as `ArrayList`, objects as `LinkedHashMap`).
 * Other members are ignored, as Jackson 3 ignores unknown properties; a body Jackson could not read (not JSON, a type
 * or a payload of the wrong kind) is refused with [IllegalArgumentException]: the server drops such a message (nothing
 * is sent back), and so does [LocalMatch].
 */
object ActionJson {

    private val JSON = Json { isLenient = false }

    fun read(body: String): GameAction {
        val root = try {
            JSON.parseToJsonElement(body)
        } catch (e: Exception) {
            throw IllegalArgumentException("Not JSON: " + e.message)
        }
        if (root !is JsonObject) throw IllegalArgumentException("Not an object")
        val action = GameAction()
        when (val type = root["type"]) {
            null, is JsonNull -> {}
            // Jackson takes a number or a boolean for a String property as its text
            is JsonPrimitive -> action.type = type.content
            else -> throw IllegalArgumentException("The type is not a text")
        }
        when (val payload = root["payload"]) {
            null, is JsonNull -> {}
            is JsonObject -> action.payload = obj(payload)
            else -> throw IllegalArgumentException("The payload is not an object")
        }
        return action
    }

    private fun obj(o: JsonObject): MutableMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in o) out[k] = value(v)
        return out
    }

    /** Jackson's untyped value (UntypedObjectDeserializer with its defaults). */
    fun value(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> obj(e)
        is JsonArray -> e.mapTo(ArrayList()) { value(it) }
        is JsonPrimitive -> if (e.isString) e.content else primitive(e.content)
    }

    private fun primitive(text: String): Any? {
        if (text == "true") return true
        if (text == "false") return false
        if (text.any { it == '.' || it == 'e' || it == 'E' }) return text.toDouble()
        text.toIntOrNull()?.let { return it }
        text.toLongOrNull()?.let { return it }
        // Jackson would make a BigInteger: no such thing here, the nearest double
        return text.toDouble()
    }
}
