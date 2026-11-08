package com.drive.drive_manager.simulator.engine.cards

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A card file that is not a valid definition; [CardReader] reports it as "Efectos inválidos: …". */
internal class CardJsonException(message: String) : IllegalArgumentException(message)

/**
 * How [CardModelReader] reads one value of a card file, as Jackson read it with `EffectJson`'s settings: an absent or null
 * `boolean`/`int` is false/0, an absent or null list or set is empty (`ArrayList`, `HashSet`), any other absent or null
 * value is null, and a field the record does not have is an error. Stricter than Jackson in one way: values are of their own
 * JSON kind (no `"3"` or `2.0` for an int, no number for a name or an enum, no null inside a list); the card files never use
 * Jackson's coercions (checked by the server's `CardReaderParityTest.everyCardFileIsCanonical`).
 */
internal object JsonFields {

    fun fail(at: String, message: String): Nothing = throw CardJsonException(if (at.isEmpty()) message else "$at: $message")

    private fun child(at: String, key: String) = if (at.isEmpty()) key else "$at.$key"

    /** [e] as an object (a record of [what]). */
    fun objectOf(e: JsonElement, at: String, what: String): JsonObject =
        e as? JsonObject ?: fail(at, "se esperaba un objeto ($what), no ${kind(e)}")

    /** No field but [known] (and "type" for a typed record). */
    fun fields(o: JsonObject, at: String, what: String, known: Set<String>, typed: Boolean) {
        for (key in o.keys) {
            if (key in known || (typed && key == "type")) continue
            fail(at, "campo desconocido \"$key\" en $what (conocidos: ${known.sorted().joinToString(", ")})")
        }
    }

    /** The record's name in "type" (the only type ids are the record names of the families). */
    fun typeId(o: JsonObject, at: String, family: String): String {
        val type = o["type"]
        if (type == null || type is JsonNull) fail(at, "falta \"type\" (el nombre de un $family)")
        if (type !is JsonPrimitive || !type.isString) fail(at, "\"type\" debe ser un nombre, no ${kind(type)}")
        return type.content
    }

    fun unknownType(at: String, id: String, family: String, known: Collection<String>): Nothing =
        fail(at, "tipo desconocido \"$id\" para $family (conocidos: ${known.sorted().joinToString(", ")})")

    fun int(o: JsonObject, key: String, at: String): Int = intOrNull(o, key, at) ?: 0

    fun intOrNull(o: JsonObject, key: String, at: String): Int? {
        val e = o[key] ?: return null
        return if (e is JsonNull) null else intElement(e, child(at, key))
    }

    fun intElement(e: JsonElement, at: String): Int {
        if (e !is JsonPrimitive || e.isString || e is JsonNull) fail(at, "se esperaba un número entero, no ${kind(e)}")
        val text = e.content
        if (!INTEGER.matches(text)) fail(at, "se esperaba un número entero, no $text")
        return text.toIntOrNull() ?: fail(at, "número fuera del rango de int: $text")
    }

    fun bool(o: JsonObject, key: String, at: String): Boolean {
        val e = o[key] ?: return false
        if (e is JsonNull) return false
        if (e !is JsonPrimitive || e.isString || (e.content != "true" && e.content != "false")) {
            fail(child(at, key), "se esperaba true o false, no ${kind(e)}")
        }
        return e.content == "true"
    }

    fun str(o: JsonObject, key: String, at: String): String? {
        val e = o[key] ?: return null
        return if (e is JsonNull) null else stringElement(e, child(at, key))
    }

    fun stringElement(e: JsonElement, at: String): String {
        if (e !is JsonPrimitive || !e.isString) fail(at, "se esperaba un texto, no ${kind(e)}")
        return e.content
    }

    /** An enum constant by its name (exactly). */
    fun <E : Enum<E>> enum(o: JsonObject, key: String, at: String, entries: List<E>, what: String): E? {
        val name = str(o, key, at) ?: return null
        return entries.firstOrNull { it.name == name }
            ?: fail(child(at, key), "\"$name\" no es un $what (valores: ${entries.joinToString(", ") { it.name }})")
    }

    /** A record or a typed value; null when absent or null. */
    fun <T> value(o: JsonObject, key: String, at: String, read: (JsonElement, String) -> T): T? {
        val e = o[key] ?: return null
        return if (e is JsonNull) null else read(e, child(at, key))
    }

    fun <T> list(o: JsonObject, key: String, at: String, read: (JsonElement, String) -> T): MutableList<T> {
        val out = ArrayList<T>()
        elements(o, key, at) { e, path -> out.add(read(e, path)) }
        return out
    }

    fun <T> set(o: JsonObject, key: String, at: String, read: (JsonElement, String) -> T): MutableSet<T> {
        val out = HashSet<T>()
        elements(o, key, at) { e, path -> out.add(read(e, path)) }
        return out
    }

    /** A record whose value must not be null (a non-null Kotlin parameter: Jackson failed on its null). */
    fun <T : Any> required(value: T?, at: String, key: String): T = value ?: fail(child(at, key), "no puede faltar")

    private inline fun elements(o: JsonObject, key: String, at: String, each: (JsonElement, String) -> Unit) {
        val e = o[key] ?: return
        if (e is JsonNull) return
        val path = child(at, key)
        if (e !is JsonArray) fail(path, "se esperaba una lista, no ${kind(e)}")
        e.forEachIndexed { i, element ->
            if (element is JsonNull) fail("$path[$i]", "elemento nulo")
            each(element, "$path[$i]")
        }
    }

    private val INTEGER = Regex("-?(0|[1-9][0-9]*)")

    fun kind(e: JsonElement): String = when {
        e is JsonNull -> "null"
        e is JsonObject -> "un objeto"
        e is JsonArray -> "una lista"
        e is JsonPrimitive && e.isString -> "el texto \"${e.content}\""
        else -> (e as JsonPrimitive).content
    }
}
