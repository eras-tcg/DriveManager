package com.drive.drive_manager.simulator.engine.cards

import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.engine.CardDefinition
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A card file's text (`cards/<edition>/<card>.json`) as its [CardDefinition], on every platform: the same object
 * graph the server's Jackson reader (`EffectJson`) built from the same text.
 *
 * Steps, conditions, amounts, actions and reactions are sealed families: each value names its record in `"type"`
 * (`{"type": "TributeTop", "count": 4}`), a Kotlin `data object` is its type alone. Only those names and their fields
 * are accepted, so the files are a closed vocabulary the engine already knows: an unknown type or field, a repeated
 * key, a value of the wrong kind or text that is not JSON is an error ([IllegalArgumentException], "Efectos inválidos: …"),
 * never code. The model's readers are generated from the Kotlin classes ([CardModelReader]).
 */
object CardReader {

    /** @throws IllegalArgumentException when [json] is not a valid definition */
    @JvmStatic
    fun read(json: String): CardDefinition {
        try {
            return CardModelReader.cardDefinition(parse(json), "")
        } catch (e: CardJsonException) {
            throw IllegalArgumentException("Efectos inválidos: " + e.message, e)
        }
    }

    /** [json] as a tree: JSON only (kotlinx.serialization, not lenient), no key twice in an object. */
    internal fun parse(json: String): JsonElement {
        val tree = try {
            Json.parseToJsonElement(json)
        } catch (e: SerializationException) {
            throw CardJsonException("JSON mal formado: " + e.message)
        }
        DuplicateKeys.check(json)
        return tree
    }
}

/**
 * Jackson's strict duplicate detection (and its refusal of raw control characters in strings), which kotlinx's tree
 * does not have: a scan of text already known to be JSON.
 */
internal object DuplicateKeys {

    fun check(json: String) {
        // One entry per open container: the keys seen in an object, null for an array
        val open = ArrayList<MutableSet<String>?>()
        var i = 0
        while (i < json.length) {
            when (json[i]) {
                '{' -> open.add(HashSet())
                '[' -> open.add(null)
                '}', ']' -> open.removeAt(open.size - 1)
                '"' -> {
                    val text = StringBuilder()
                    i = string(json, i + 1, text)
                    var j = i + 1
                    while (j < json.length && json[j].isJsonSpace()) j++
                    val keys = open.lastOrNull()
                    if (j < json.length && json[j] == ':' && keys != null && !keys.add(text.toString())) {
                        throw CardJsonException("clave repetida \"$text\"")
                    }
                }
            }
            i++
        }
    }

    /** Reads the string starting at [from] (after its quote) into [into]; returns the index of its closing quote. */
    private fun string(json: String, from: Int, into: StringBuilder): Int {
        var i = from
        while (true) {
            val c = json[i]
            when {
                c == '"' -> return i
                c == '\\' -> {
                    val e = json[i + 1]
                    when (e) {
                        'u' -> { into.append(json.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                        'n' -> into.append('\n')
                        't' -> into.append('\t')
                        'r' -> into.append('\r')
                        'b' -> into.append('\b')
                        'f' -> into.append('\u000C')
                        else -> into.append(e)
                    }
                    i += 2
                }
                c < ' ' -> throw CardJsonException("carácter de control sin escapar (código ${c.code}) en un texto")
                else -> { into.append(c); i++ }
            }
        }
    }

    private fun Char.isJsonSpace() = this == ' ' || this == '\n' || this == '\r' || this == '\t'
}
