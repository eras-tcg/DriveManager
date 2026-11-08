package com.drive.drive_manager.simulator.engine.cards

import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.engine.CardDefinition
import com.drive.drive_manager.simulator.engine.CardSource
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The card bundle: every card file's text in one JSON document, made by eras-engine's build (task `cardBundle`, from
 * `eras-engine/cards`), in the order of their paths:
 * `{"format": "eras-card-bundle/1", "files": [{"path": "E1/a-la-carga.json", "text": "<the file's text>"}, …]}`.
 * The JVM artifact carries it as the resource [RESOURCE]; the site fetches it.
 */
object CardBundle {

    const val FORMAT: String = "eras-card-bundle/1"

    /** Where the JVM artifact keeps it on the class path. */
    const val RESOURCE: String = "eras/card-bundle.json"

    /** One card file of the bundle: its path under `cards/` and its text. */
    class File(val path: String, val text: String) {
        /** Its name without the folders ("a-la-carga.json"). */
        val name: String get() = path.substringAfterLast('/')
    }

    /** The files of [bundle], in order. @throws IllegalStateException when it is not a bundle */
    @JvmStatic
    fun files(bundle: String): List<File> {
        val root = try {
            Json.parseToJsonElement(bundle)
        } catch (e: SerializationException) {
            throw IllegalStateException("El paquete de cartas no es JSON: " + e.message, e)
        }
        check(root is JsonObject && (root["format"] as? JsonPrimitive)?.content == FORMAT) { "No es un paquete de cartas ($FORMAT)" }
        val files = root["files"] as? JsonArray ?: throw IllegalStateException("Paquete de cartas sin \"files\"")
        return files.map {
            val path = ((it as? JsonObject)?.get("path") as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
            val text = ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.takeIf { t -> t.isString }?.content
            check(path != null && text != null) { "Archivo del paquete de cartas sin \"path\" o \"text\": $it" }
            File(path, text)
        }
    }

    /**
     * Every card of [bundle], read with [CardReader], with the server's checks of the card files: the engine does not run on a
     * broken card.
     *
     * @throws IllegalStateException when a file is invalid ("<file name>: Efectos inválidos: …"), or a card or an effect id is
     *         there twice
     */
    @JvmStatic
    fun read(bundle: String): List<CardDefinition> = read(files(bundle))

    /** As [read], from files already listed (a platform that has them one by one). */
    @JvmStatic
    fun read(files: List<File>): List<CardDefinition> {
        val cards = ArrayList<CardDefinition>()
        val names = HashSet<String?>()
        val ids = HashSet<String?>()
        for (file in files) {
            val card = try {
                CardReader.read(file.text)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException(file.name + ": " + e.message, e)
            }
            if (!names.add(card.name)) throw IllegalStateException("Carta en dos archivos: " + card.name)
            for (script in card.effects()) if (!ids.add(script.id)) throw IllegalStateException("Efecto repetido: " + script.id)
            for (script in card.inherited()) if (!ids.add(script.id)) throw IllegalStateException("Efecto repetido: " + script.id)
            cards.add(card)
        }
        return cards
    }
}

/**
 * The cards of a bundle's text as the core's [CardSource]: `CardSources.install(BundleCardSource(text))` before the engine is
 * first used (JavaScript, a test). Read when the engine asks, once.
 */
class BundleCardSource(private val bundle: String) : CardSource {
    override fun cards(): List<CardDefinition> = CardBundle.read(bundle)
}
