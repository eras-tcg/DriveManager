package com.drive.drive_manager.simulator

import com.drive.drive_manager.simulator.actions.PreRoomActions
import com.drive.drive_manager.simulator.bot.HeuristicBot
import com.drive.drive_manager.simulator.engine.CardEffects
import com.drive.drive_manager.simulator.engine.CardSources
import com.drive.drive_manager.simulator.engine.cards.BundleCardSource
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState
import com.drive.drive_manager.simulator.room.RoomEngine
import com.drive.drive_manager.simulator.room.RoomHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The cards of the build's bundle, installed once as the core's card source (before the engine first reads them). */
object TestCards {
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        CardSources.install(BundleCardSource(TestFiles.text("ERAS_CARD_BUNDLE")))
        CardEffects.load()
    }
}

/**
 * A whole game between two bots from a seed, through the core alone (RoomEngine with a room that is only played, as a game
 * in the browser will be): the seats of drive_manager's `BotMatch.seat` from the same starter decks' metadata
 * (`CardMeta.apply`, ported), the start of `MatchReplay.play` (`PreRoomActions.startFromPreRoom` with the seed), then the
 * loop of `SimulatorJsonTest`: one heuristic bot, seeded, moving for the first seat that has a move.
 */
object SeededBotGame {

    const val MAX_ACTIONS = 3000

    class Result(val room: GameRoom, val log: List<String>, val fingerprint: String)

    fun play(hostDeck: String, guestDeck: String, seed: Long): Result {
        TestCards.install()
        val room = GameRoom()
        room.roomId = "seeded"
        room.hostId = "host"
        room.headless = true
        val ids = intArrayOf(0)
        for ((seat, name, deck) in listOf(Triple("host", "Ana", hostDeck), Triple("guest", "Beto", guestDeck))) {
            val p = PlayerState(seat, name)
            p.connected = true
            seatDeck(p, deck, ids)
            room.players[seat] = p
        }
        PreRoomActions.startFromPreRoom(room, seed)
        val engine = RoomEngine(RoomHost.gameOnly())
        val bot = HeuristicBot()
        bot.seed(seed)
        val log = ArrayList<String>()
        var actions = 0
        while (actions < MAX_ACTIONS && room.status != GameRoom.RoomStatus.POST_GAME) {
            var moved = false
            for (seat in listOf("host", "guest")) {
                val action = bot.next(room, seat) ?: continue
                val update = engine.applyAction(room, seat, action, false)
                actions++
                if (update?.error != null) bot.refused(action, update.error) else bot.played(room, action)
                log.add(seat + " " + GameFingerprint.action(action) + (if (update?.error != null) " ! " + update.error else ""))
                moved = true
                break
            }
            if (!moved) break
        }
        return Result(room, log, GameFingerprint.of(room, log))
    }

    /** `BotMatch.seat`: the Iniciador once, the rest three times, each copy filled by [apply] (`CardMeta.apply`). */
    private fun seatDeck(player: PlayerState, deck: String, ids: IntArray) {
        val slots = ArrayList<CardSlot>()
        for (meta in Json.parseToJsonElement(TestFiles.text("ERAS_DECKS", "$deck-cards.json")).jsonArray) {
            val card = meta.jsonObject
            val copies = if (bool(card, "starter")) 1 else 3
            repeat(copies) {
                val s = CardSlot()
                s.instanceId = "c" + (++ids[0])
                s.cardId = string(card, "id")
                s.faceDown = true
                apply(s, card)
                slots.add(s)
            }
        }
        player.deckId = "deck-$deck"
        player.deckName = deck.uppercase()
        player.deck = slots
        player.originalDeck = PreRoomActions.deepCopySlots(slots)
        player.starterCardId = slots.firstOrNull { it.starter }?.cardId
    }

    /** drive_manager's `CardMeta.apply`, from the metadata's JSON. */
    private fun apply(slot: CardSlot, meta: JsonObject) {
        slot.cardName = string(meta, "cardName")
        slot.altArtUrls = HashMap()
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
        slot.unprogrammed = CardEffects.pending(string(meta, "cardName"), list(meta, "effects").size, list(meta, "inheritEffects").size, allKeywords)
        slot.mentions = mentionedTypes(meta)
        val colors = ArrayList<String>()
        string(meta, "colorIdentity")?.let { colors.add(it) }
        strings(meta, "colors").filter { it !in colors }.forEach { colors.add(it) }
        slot.colors = colors
    }

    private val TYPE = Regex("(?<!\\[)\\[([^\\[\\]]+)\\](?!\\])")

    private fun mentionedTypes(meta: JsonObject): MutableList<String> {
        val found = ArrayList<String>()
        for (effect in list(meta, "effects") + list(meta, "inheritEffects")) {
            for (block in list(effect.jsonObject, "effectBlocks")) {
                for (key in listOf("activationCondition", "cost", "resolution")) {
                    val text = string(block.jsonObject, key) ?: continue
                    for (m in TYPE.findAll(text)) {
                        val t = m.groupValues[1].trim().lowercase()
                        if (t !in found) found.add(t)
                    }
                }
            }
        }
        return found
    }

    private fun keywordNames(effects: JsonElement?): MutableList<String> {
        val keywords = ArrayList<String>()
        if (effects == null || effects is JsonNull) return keywords
        for (k in effects.jsonArray) {
            val keyword = string(k.jsonObject, "keyword") ?: continue
            val name = keyword.replace(Regex("\\{[^}]*\\}"), string(k.jsonObject, "number") ?: "").trim()
            keywords.add(name.replace(Regex("\\s+"), " "))
        }
        return keywords
    }

    private fun list(o: JsonObject, key: String): List<JsonElement> = (o[key] as? JsonArray) ?: emptyList()
    private fun strings(o: JsonObject, key: String): MutableList<String> = list(o, key).map { it.jsonPrimitive.content }.toMutableList()
    private fun string(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun int(o: JsonObject, key: String): Int? = string(o, key)?.toInt()
    private fun bool(o: JsonObject, key: String): Boolean = string(o, key) == "true"
}

/**
 * A deterministic digest of a game, in common code (no Jackson): the room's state (its counters, each player's counters and
 * every card of every zone with its fields), its history's lines without their times, and the actions played. The same text
 * on every platform for the same game, summed up with 64-bit FNV-1a over its UTF-8 bytes.
 */
object GameFingerprint {

    fun of(room: GameRoom, actions: List<String>): String = digest(text(room, actions))

    fun text(room: GameRoom, actions: List<String>): String {
        val out = StringBuilder()
        out.append("status=").append(room.status).append(" turn=").append(room.currentTurnUserId).append(" phase=").append(room.currentPhase)
            .append(" counter=").append(room.sharedCounter).append(" winner=").append(room.winnerId).append(" reason=").append(room.endReason)
            .append(" idSeq=").append(room.idSeq).append(" eventSeq=").append(room.eventSeq).append(" version=").append(room.version)
            .append(" first=").append(room.firstPlayerId).append('\n')
        for ((id, p) in room.players) {
            out.append("player ").append(id).append(" resources=").append(p.resourceCount).append(" turns=").append(p.turnsStarted).append('\n')
            for ((zone, cards) in listOf("hand" to p.hand, "deck" to p.deck, "lives" to p.lifeStack, "tributes" to p.tributeZone,
                    "discard" to p.discardPile, "field" to p.field)) {
                out.append(' ').append(zone).append(':')
                for (c in cards) out.append(' ').append(card(c))
                out.append('\n')
            }
        }
        for (entry in room.eventLog) out.append("log ").append(entry.kind).append(' ').append(entry.text).append('\n')
        for (a in actions) out.append("action ").append(a).append('\n')
        return out.toString()
    }

    private fun card(c: CardSlot): String {
        val fields = listOf(
            c.instanceId, c.cardId, c.cardName, c.faceDown, c.tapped, number(c.x), number(c.y), c.strength, c.strengthModifier,
            c.cost, c.getPrintedLevel(), c.getLevel(), c.turnStrength, c.laterStrength, c.continuousStrength, c.continuousLevel,
            c.fieldSeq, c.returnAtTurnEnd, c.noUntap, c.noStartUntap, c.keywords, c.inheritKeywords, c.turnKeywords,
            c.laterKeywords, c.untilNextAttack, c.continuousKeywords, c.floating?.size, c.unprogrammed, c.mentions, c.colors, c.classes,
        )
        val nested = (c.materials ?: emptyList<CardSlot>()).joinToString(",", "m[", "]") { card(it) } +
            (c.resources ?: emptyList<CardSlot>()).joinToString(",", "r[", "]") { card(it) }
        return "{" + fields.joinToString("|") + nested + "}"
    }

    /** A double by its bits (Double.toString differs between the JVM and JavaScript: "1.0" against "1"). */
    private fun number(d: Double?): String = d?.toRawBits()?.toString(16) ?: "null"

    fun action(a: GameAction): String {
        val payload = a.payload ?: return a.type ?: "null"
        return a.type + " " + payload.keys.sorted().joinToString(",", "{", "}") { k -> k + "=" + value(payload[k]) }
    }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Double -> "d:" + number(v)
        is Float -> "f:" + number(v.toDouble())
        is Map<*, *> -> v.keys.map { it.toString() }.sorted().joinToString(",", "{", "}") { k -> k + "=" + value(v[k]) }
        is Collection<*> -> v.joinToString(",", "[", "]") { value(it) }
        else -> v.toString()
    }

    fun digest(text: String): String {
        var h = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis (0xcbf29ce484222325)
        for (b in text.encodeToByteArray()) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        return h.toULong().toString(16).padStart(16, '0')
    }
}
