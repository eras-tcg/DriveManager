package com.eras.engine.local

import com.drive.drive_manager.simulator.actions.PreRoomActions
import com.drive.drive_manager.simulator.engine.CardEffects
import com.drive.drive_manager.simulator.platform.Platform
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The decks of a game in the browser, in place of the server's Mongo (`DeckLoader`): what a deck id holds and the card
 * slots a room plays, each card filled from its metadata as the server's `CardMeta.apply` fills it.
 *
 * A deck, by its id (what the board's `SET_DECK` sends):
 * ```
 * {"deckName": "ST1",
 *  "cards": [{"card": {<the card's metadata, as the site's card API gives it>}, "copies": 3,
 *             "cardId": "<the card's file id; default: the metadata's id>", "imageUrl": "<default: none>"}, …]}
 * ```
 * or, for a starter deck, `{"deckName": "ST1", "metadata": [<each card's metadata>]}`: every card three times and the
 * Iniciador once (how drive_manager's tests seat the starter decks, `BotMatch.seat`). The slots are made in that order,
 * face down, each with the next id of the game ([LocalMatch]: "c1", "c2", …, as the server gives each a new UUID).
 */
class LocalDecks(decksJson: JsonObject?) {

    /** One kind of card of a deck. */
    class Entry(val cardId: String, val imageUrl: String?, val meta: JsonObject, val copies: Int)

    class Deck(val id: String, val name: String?, val entries: List<Entry>)

    private val decks: Map<String, Deck> = read(decksJson)

    fun deck(deckId: String): Deck? = decks[deckId]

    /** A card's metadata by its file id (any deck that has it), as the server reads it again: null when there is none. */
    fun metadataOf(cardId: String): JsonObject? = decks.values.asSequence().flatMap { it.entries.asSequence() }
        .firstOrNull { it.cardId == cardId }?.meta

    companion object {

        /** The decks of a JSON object `{"<deckId>": <deck>, …}`. */
        fun read(decksJson: JsonObject?): Map<String, Deck> {
            val out = LinkedHashMap<String, Deck>()
            if (decksJson == null) return out
            for ((id, value) in decksJson) {
                val spec = value as? JsonObject ?: throw IllegalArgumentException("Deck $id: not an object")
                val entries = ArrayList<Entry>()
                (spec["cards"] as? JsonArray)?.forEach { e ->
                    val o = e as? JsonObject ?: throw IllegalArgumentException("Deck $id: a card is not an object")
                    val meta = o["card"] as? JsonObject ?: throw IllegalArgumentException("Deck $id: a card without its metadata (\"card\")")
                    val copies = int(o, "copies") ?: 1
                    entries.add(Entry(string(o, "cardId") ?: string(meta, "id") ?: "", string(o, "imageUrl"), meta, copies))
                }
                (spec["metadata"] as? JsonArray)?.forEach { e ->
                    val meta = e as? JsonObject ?: throw IllegalArgumentException("Deck $id: a card's metadata is not an object")
                    entries.add(Entry(string(meta, "id") ?: "", null, meta, if (bool(meta, "starter")) 1 else 3))
                }
                out[id] = Deck(id, string(spec, "deckName"), entries)
            }
            return out
        }

        /** The decks of a JSON text (see the class). */
        fun parse(text: String): Map<String, Deck> = read(Json.parseToJsonElement(text) as JsonObject)

        /** The deck's cards as a room plays them (`DeckLoader.slots`): face down, each a new instance (`nextId`). */
        fun slots(deck: Deck, nextId: () -> String): MutableList<CardSlot> {
            val slots = ArrayList<CardSlot>()
            for (e in deck.entries) repeat(e.copies) {
                val slot = CardSlot()
                slot.instanceId = nextId()
                slot.cardId = e.cardId
                slot.faceDown = true
                slot.imageUrl = e.imageUrl
                slot.cardName = string(e.meta, "cardName")
                apply(slot, e.cardId, e.meta)
                slots.add(slot)
            }
            return slots
        }

        /** The same cards as new instances (`DeckLoader.freshCopy`: the bot's copy of its owner's deck). */
        fun freshCopy(slots: List<CardSlot>, nextId: () -> String): MutableList<CardSlot> {
            val copy = PreRoomActions.deepCopySlots(slots)
            copy.forEach { it.instanceId = nextId() }
            return copy
        }

        /** Every copy of the card in the room takes its metadata again (`DeckLoader.refresh`). */
        fun refresh(room: GameRoom, cardId: String, meta: JsonObject) {
            for (player in room.players.values) {
                for (zone in listOf(player.hand, player.deck, player.originalDeck, player.lifeStack, player.tributeZone, player.discardPile)) {
                    zone.filter { cardId == it.cardId }.forEach { apply(it, cardId, meta) }
                }
                for (fs in player.field) {
                    if (cardId == fs.cardId) apply(fs, cardId, meta)
                    fs.materials!!.filter { cardId == it.cardId }.forEach { apply(it, cardId, meta) }
                    fs.resources!!.filter { cardId == it.cardId }.forEach { apply(it, cardId, meta) }
                }
            }
        }

        /** The names of a deck's cards the engine does not run in full ("×2 Roto-G0, Sicario R3-D"), or null (`DeckLoader.unprogrammedIn`). */
        fun unprogrammedIn(slots: List<CardSlot>): String? {
            val names = LinkedHashMap<String, Int>()
            for (s in slots) if (s.unprogrammed != null) {
                val name = s.cardName ?: "?"
                names[name] = (names[name] ?: 0) + 1
            }
            if (names.isEmpty()) return null
            return names.entries.joinToString(", ") { (name, n) -> if (n > 1) "×$n $name" else name }
        }

        /** drive_manager's `CardMeta.apply`: the slot of that card filled from its metadata's JSON. */
        fun apply(slot: CardSlot, cardId: String, meta: JsonObject) {
            if (cardId != slot.cardId) return
            slot.cardName = string(meta, "cardName")
            val arts = HashMap<String, String>()
            list(meta, "altArts").forEach { a -> (a as? JsonObject)?.let { arts[string(it, "id")!!] = string(it, "url")!! } }
            slot.altArtUrls = arts
            slot.cardType = string(meta, "cardType")
            slot.strength = int(meta, "strength")
            slot.specialSummonKind = string(meta, "specialSummonKind")
            slot.starter = bool(meta, "starter")
            slot.cost = int(meta, "cost")
            slot.setLevel(int(meta, "level"))
            slot.classes = strings(meta, "cardClasses")
            slot.specialCost = int(meta, "specialCost")
            slot.requirement = string(meta, "requirement")
            slot.keywords = keywordNames(meta["keywordEffects"])
            slot.inheritKeywords = keywordNames(meta["inheritKeywordEffects"])
            val allKeywords = ArrayList(slot.keywords!!)
            allKeywords.addAll(slot.inheritKeywords!!)
            slot.unprogrammed = CardEffects.pending(string(meta, "cardName"), list(meta, "effects").size,
                list(meta, "inheritEffects").size, allKeywords)
            slot.mentions = mentionedTypes(meta)
            val colors = ArrayList<String>()
            string(meta, "colorIdentity")?.let { colors.add(it) }
            strings(meta, "colors").filter { it !in colors }.forEach { colors.add(it) }
            slot.colors = colors
        }

        private val TYPE = Platform.regex("(?<!\\[)\\[([^\\[\\]]+)\\](?!\\])")
        private val PLACEHOLDER = Platform.regex("\\{[^}]*\\}")
        private val SPACES = Platform.regex("\\s+")

        /** `CardMeta.mentionedTypes`: the [tipos] written in its effects and inherited effects. */
        private fun mentionedTypes(meta: JsonObject): MutableList<String> {
            val found = ArrayList<String>()
            for (effect in list(meta, "effects") + list(meta, "inheritEffects")) {
                for (block in list(effect as JsonObject, "effectBlocks")) {
                    for (key in listOf("activationCondition", "cost", "resolution")) {
                        val text = string(block as JsonObject, key) ?: continue
                        for (m in TYPE.findAll(text)) {
                            val t = m.groupValues[1].trim { it <= ' ' }.lowercase()
                            if (t !in found) found.add(t)
                        }
                    }
                }
            }
            return found
        }

        /** `CardMeta.keywordNames`: "Letalidad {cantidad}" + 1 → "Letalidad 1". */
        private fun keywordNames(effects: JsonElement?): MutableList<String> {
            val keywords = ArrayList<String>()
            if (effects == null || effects is JsonNull) return keywords
            for (k in (effects as JsonArray)) {
                val keyword = string(k as JsonObject, "keyword") ?: continue
                val number = string(k, "number") ?: ""
                val name = PLACEHOLDER.replace(keyword) { number }.trim { it <= ' ' }
                keywords.add(SPACES.replace(name) { " " })
            }
            return keywords
        }

        private fun list(o: JsonObject, key: String): List<JsonElement> = (o[key] as? JsonArray) ?: emptyList()
        private fun strings(o: JsonObject, key: String): MutableList<String> = list(o, key).mapTo(ArrayList()) { (it as JsonPrimitive).content }
        private fun string(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        private fun int(o: JsonObject, key: String): Int? = string(o, key)?.toInt()
        private fun bool(o: JsonObject, key: String): Boolean = string(o, key) == "true"
    }
}
