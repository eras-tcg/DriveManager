package com.eras.engine.local

import com.drive.drive_manager.simulator.bot.BotResourceSource
import com.drive.drive_manager.simulator.bot.ValueModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int

/**
 * The bot's resources (the server's class-path `/bot/models.txt`, `/bot/settings.txt` and, when there is one, the trained
 * `/bot/value-model.json`) from one JSON document, for the browser: eras-engine's build makes it from drive_manager's
 * resources (`bot-resources.json` in the npm package):
 * `{"format": "eras-bot-resources/1", "files": {"/bot/models.txt": "<text>", "/bot/settings.txt": "<text>"}}`.
 * Installed before the bot is first used (`EngineApi.installBotResources`), the bot's levels, settings and models are
 * the server's, and so are the choices the pre-room offers ("botChoices").
 */
class BotResources(json: String) : BotResourceSource {

    private val files: Map<String, String>

    init {
        val root = Json.parseToJsonElement(json) as? JsonObject ?: throw IllegalArgumentException("The bot's resources are not a JSON object")
        val format = (root["format"] as? JsonPrimitive)?.content
        if (format != FORMAT) throw IllegalArgumentException("The bot's resources are not $FORMAT: $format")
        val all = root["files"] as? JsonObject ?: throw IllegalArgumentException("The bot's resources have no files")
        files = all.mapValues { (path, text) -> (text as? JsonPrimitive)?.content ?: throw IllegalArgumentException("$path: not a text") }
    }

    /** How many files it holds. */
    val size: Int get() = files.size

    override fun text(path: String, failure: String): String? = files[path]

    override fun valueModel(path: String): ValueModel? {
        val text = files[path] ?: return null
        try {
            val o = Json.parseToJsonElement(text) as JsonObject
            return ValueModel.of(ValueModel.Weights(
                strings(o["features"]), doubles(o["mean"]), doubles(o["std"]), (o["hidden"] as JsonPrimitive).int,
                (o["w1"] as? JsonArray)?.map { doubles(it)!! }?.toTypedArray(), doubles(o["b1"]), doubles(o["w2"]),
                (o["b2"] as JsonPrimitive).double, null,
            ))
        } catch (e: Exception) {
            throw IllegalStateException("Cannot read the bot's model $path", e)
        }
    }

    private fun strings(e: JsonElement?): List<String>? = if (e == null || e is JsonNull) null else (e as JsonArray).map { (it as JsonPrimitive).content }

    private fun doubles(e: JsonElement?): DoubleArray? =
        if (e == null || e is JsonNull) null else (e as JsonArray).map { (it as JsonPrimitive).double }.toDoubleArray()

    companion object {
        const val FORMAT = "eras-bot-resources/1"
    }
}
