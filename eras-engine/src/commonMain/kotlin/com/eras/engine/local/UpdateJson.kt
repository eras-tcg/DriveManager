package com.eras.engine.local

import com.drive.drive_manager.simulator.engine.Chain
import com.drive.drive_manager.simulator.engine.Combat
import com.drive.drive_manager.simulator.engine.GameEvent
import com.drive.drive_manager.simulator.engine.PendingEffect
import com.drive.drive_manager.simulator.engine.Prompt
import com.drive.drive_manager.simulator.platform.Platform
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.FloatingEffect
import com.drive.drive_manager.simulator.room.GameLogEntry
import com.drive.drive_manager.simulator.room.GameStateUpdate
import com.drive.drive_manager.simulator.room.PlayerState
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/**
 * The room's updates as the server sends them over the WebSocket, without Jackson: the JSON tree Spring's Jackson 3
 * mapper writes for a [GameStateUpdate] with drive_manager's mix-ins (`json/SimulatorJson`), property by property and in
 * the same order, and its text as Jackson writes it ([JacksonText]).
 *
 * The order is Jackson 3's: properties sorted by name, except that the parameters of a class's one constructor that
 * names every property (`CardSlot`, `GameEvent`, the records) come first, in that constructor's order (and `PlayerState`'s
 * `userId`, `username`), then the getters that are not fields (`printedLevel`). What the mix-ins leave out is left out:
 * `PlayerState.disconnectedAt`, `CardSlot.altArtUrls`, `PendingEffect`'s working state (`repeatsDone`, `looked`,
 * `ordered`, `confirmed`, `lastDone`, `lastCount`, `lastTributed`, `keepOnField`, `passed`, `priorityDone`). Nulls are
 * written (Jackson's default), maps in their own iteration order, doubles as Java writes them ([Platform.doubleText]).
 *
 * drive_manager's `LocalMatchParityTest` checks every property list against Jackson's and every message of whole games
 * against what the server sends; a property added to one of these classes fails it until it is added here.
 */
@OptIn(ExperimentalSerializationApi::class)
object UpdateJson {

    /** The update's JSON text, as the player's queue receives it. */
    fun text(update: GameStateUpdate): String = JacksonText.write(tree(update))

    fun tree(u: GameStateUpdate): JsonObject = obj(
        "activatable" to map(u.activatable),
        "actorId" to str(u.actorId),
        "attackTargets" to map(u.attackTargets),
        "bot" to bool(u.bot),
        "botChoices" to list(u.botChoices),
        "botDelayMs" to num(u.botDelayMs),
        "botEdge" to num(u.botEdge),
        "botLevel" to str(u.botLevel),
        "chain" to list(u.chain),
        "combat" to value(u.combat),
        "currentPhase" to str(u.currentPhase),
        "currentTurnUserId" to str(u.currentTurnUserId),
        "editor" to bool(u.editor),
        "editorBot" to bool(u.editorBot),
        "editorSelfBot" to bool(u.editorSelfBot),
        "endReason" to str(u.endReason),
        "error" to str(u.error),
        "event" to str(u.event),
        "eventLog" to list(u.eventLog),
        "firstChoice" to str(u.firstChoice),
        "firstPlayerId" to str(u.firstPlayerId),
        "hostId" to str(u.hostId),
        "legalActions" to map(u.legalActions),
        "logEntry" to str(u.logEntry),
        "mulliganReady" to list(u.mulliganReady),
        "paused" to bool(u.paused),
        "players" to map(u.players),
        "postGameChoices" to map(u.postGameChoices),
        "prompt" to value(u.prompt),
        "queued" to bool(u.queued),
        "ranked" to bool(u.ranked),
        "replay" to bool(u.replay),
        "revealedCardImageUrl" to str(u.revealedCardImageUrl),
        "revealedCardName" to str(u.revealedCardName),
        "roomId" to str(u.roomId),
        "selfBotLevel" to str(u.selfBotLevel),
        "sharedCounter" to num(u.sharedCounter),
        "specialSummonable" to map(u.specialSummonable),
        "starterAdvantageUserId" to str(u.starterAdvantageUserId),
        "status" to str(u.status),
        "structurePlayed" to list(u.structurePlayed),
        "subjectUserId" to str(u.subjectUserId),
        "targetedInstanceId" to str(u.targetedInstanceId),
        "targetingSourceInstanceId" to str(u.targetingSourceInstanceId),
        "timeline" to list(u.timeline),
        "timelineAt" to num(u.timelineAt),
        "timelineSize" to num(u.timelineSize),
        "unplayable" to map(u.unplayable),
        "winnerId" to str(u.winnerId),
    )

    private fun player(p: PlayerState): JsonObject = obj(
        "userId" to str(p.userId),
        "username" to str(p.username),
        "avatar" to str(p.avatar),
        "connected" to bool(p.connected),
        "deck" to list(p.deck),
        "deckId" to str(p.deckId),
        "deckName" to str(p.deckName),
        "discardPile" to list(p.discardPile),
        "field" to list(p.field),
        "hand" to list(p.hand),
        "lifeStack" to list(p.lifeStack),
        "originalDeck" to list(p.originalDeck),
        "playmat" to str(p.playmat),
        "ready" to bool(p.ready),
        "resourceCount" to num(p.resourceCount),
        "skipOptional" to bool(p.skipOptional),
        "starterCardId" to str(p.starterCardId),
        "tributeZone" to list(p.tributeZone),
        "turnsStarted" to num(p.turnsStarted),
    )

    private fun card(c: CardSlot): JsonObject = obj(
        "instanceId" to str(c.instanceId),
        "cardId" to str(c.cardId),
        "imageUrl" to str(c.imageUrl),
        "faceDown" to bool(c.faceDown),
        "tapped" to bool(c.tapped),
        "x" to num(c.x),
        "y" to num(c.y),
        "cardName" to str(c.cardName),
        "cardType" to str(c.cardType),
        "strength" to num(c.strength),
        "specialSummonKind" to str(c.specialSummonKind),
        "starter" to bool(c.starter),
        "strengthModifier" to num(c.strengthModifier),
        "cost" to num(c.cost),
        "level" to num(c.getLevel()),
        "colors" to list(c.colors),
        "classes" to list(c.classes),
        "specialCost" to num(c.specialCost),
        "requirement" to str(c.requirement),
        "keywords" to list(c.keywords),
        "inheritKeywords" to list(c.inheritKeywords),
        "turnKeywords" to list(c.turnKeywords),
        "laterKeywords" to list(c.laterKeywords),
        "untilNextAttack" to list(c.untilNextAttack),
        "fieldSeq" to num(c.fieldSeq),
        "turnStrength" to num(c.turnStrength),
        "laterStrength" to num(c.laterStrength),
        "continuousStrength" to num(c.continuousStrength),
        "continuousLevel" to num(c.continuousLevel),
        "returnAtTurnEnd" to bool(c.returnAtTurnEnd),
        "continuousKeywords" to list(c.continuousKeywords),
        "floating" to list(c.floating),
        "noUntap" to num(c.noUntap),
        "noStartUntap" to num(c.noStartUntap),
        "materials" to list(c.materials),
        "resources" to list(c.resources),
        "unprogrammed" to str(c.unprogrammed),
        "mentions" to list(c.mentions),
        "finish" to str(c.finish),
        "alt" to bool(c.alt),
        "printedLevel" to num(c.getPrintedLevel()),
    )

    private fun floating(f: FloatingEffect): JsonObject = obj(
        "text" to str(f.text),
        "source" to str(f.source),
        "until" to str(f.until),
        "strength" to num(f.strength),
        "keyword" to str(f.keyword),
    )

    private fun logEntry(e: GameLogEntry): JsonObject = obj(
        "detail" to bool(e.detail),
        "eventSeq" to num(e.eventSeq),
        "imageUrl" to str(e.imageUrl),
        "inheritedFromImage" to str(e.inheritedFromImage),
        "inheritedFromName" to str(e.inheritedFromName),
        "kind" to str(e.kind),
        "sourceId" to str(e.sourceId),
        "sourceName" to str(e.sourceName),
        "text" to str(e.text),
        "ts" to num(e.ts),
    )

    private fun event(e: GameEvent): JsonObject = obj(
        "seq" to num(e.seq),
        "ts" to num(e.ts),
        "type" to str(e.type),
        "playerId" to str(e.playerId),
        "cardInstanceId" to str(e.cardInstanceId),
        "cardName" to str(e.cardName),
        "fromZone" to str(e.fromZone),
        "toZone" to str(e.toZone),
        "effectId" to str(e.effectId),
        "resolutionId" to str(e.resolutionId),
        "data" to map(e.data),
    )

    private fun pending(p: PendingEffect): JsonObject = obj(
        "controllerId" to str(p.controllerId),
        "costIndex" to num(p.costIndex),
        "id" to str(p.id),
        "inheritedFromImage" to str(p.inheritedFromImage),
        "inheritedFromName" to str(p.inheritedFromName),
        "label" to str(p.label),
        "link" to num(p.link),
        "resolutionId" to str(p.resolutionId),
        "ritualSummonerId" to str(p.ritualSummonerId),
        "ritualTargetId" to str(p.ritualTargetId),
        "saved" to map(p.saved),
        "scriptId" to str(p.scriptId),
        "sequenceIndex" to num(p.sequenceIndex),
        "sourceFieldSeq" to num(p.sourceFieldSeq),
        "sourceInstanceId" to str(p.sourceInstanceId),
        "sourceName" to str(p.sourceName),
        "stepIndex" to num(p.stepIndex),
        "subjectId" to str(p.subjectId),
        "summonedId" to str(p.summonedId),
        "targetIds" to list(p.targetIds),
        "text" to str(p.text),
        "triggerSeq" to num(p.triggerSeq),
    )

    private fun prompt(p: Prompt): JsonObject = obj(
        "cardLabels" to map(p.cardLabels),
        "cards" to list(p.cards),
        "chosenLabel" to str(p.chosenLabel),
        "effectId" to str(p.effectId),
        "effectText" to str(p.effectText),
        "effects" to list(p.effects),
        "exactOrNone" to bool(p.exactOrNone),
        "id" to str(p.id),
        "instruction" to str(p.instruction),
        "kind" to value(p.kind),
        "max" to num(p.max),
        "maxLevelSum" to num(p.maxLevelSum),
        "min" to num(p.min),
        "playerId" to str(p.playerId),
        "purpose" to str(p.purpose),
        "reorderRest" to bool(p.reorderRest),
        "requiredIds" to list(p.requiredIds),
        "restToTop" to bool(p.restToTop),
        "sourceImageUrl" to str(p.sourceImageUrl),
        "sourceName" to str(p.sourceName),
        "subjectId" to str(p.subjectId),
        "summonerId" to str(p.summonerId),
        "title" to str(p.title),
        "topOrBottom" to bool(p.topOrBottom),
        "validIds" to list(p.validIds),
    )

    private fun offered(o: Prompt.OfferedEffect): JsonObject = obj(
        "effectId" to str(o.effectId),
        "sourceId" to str(o.sourceId),
        "sourceName" to str(o.sourceName),
        "label" to str(o.label),
        "text" to str(o.text),
        "inheritedFromName" to str(o.inheritedFromName),
        "inheritedFromImage" to str(o.inheritedFromImage),
    )

    private fun combat(c: Combat): JsonObject = obj(
        "attackerId" to str(c.attackerId),
        "attackerOwnerId" to str(c.attackerOwnerId),
        "blockAsked" to bool(c.blockAsked),
        "blockerId" to str(c.blockerId),
        "byEffect" to bool(c.byEffect),
        "damageReplaceAsked" to bool(c.damageReplaceAsked),
        "damageTributeAskedAt" to num(c.damageTributeAskedAt),
        "defenderId" to str(c.defenderId),
        "enemyDestroyed" to bool(c.enemyDestroyed),
        "hitsLeft" to num(c.hitsLeft),
        "phase" to value(c.phase),
        "provokeAsked" to bool(c.provokeAsked),
        "skipBlock" to bool(c.skipBlock),
        "step" to value(c.step),
        "targetId" to str(c.targetId),
        "untilEndOfAttack" to list(c.untilEndOfAttack),
    )

    private fun activatable(a: Chain.Activatable): JsonObject = obj(
        "instanceId" to str(a.instanceId),
        "scriptId" to str(a.scriptId),
        "label" to str(a.label),
        "text" to str(a.text),
    )

    /**
     * Each class's properties in the order this writer writes them (Jackson's), by the class's simple name: what
     * drive_manager's parity test compares with Jackson's own introspection.
     */
    val PROPERTIES: Map<String, List<String>> by lazy {
        linkedMapOf(
            "GameStateUpdate" to tree(GameStateUpdate()).keys.toList(),
            "PlayerState" to player(PlayerState(null, null)).keys.toList(),
            "CardSlot" to card(CardSlot()).keys.toList(),
            "FloatingEffect" to floating(FloatingEffect(null, null, null, 0, null)).keys.toList(),
            "GameLogEntry" to logEntry(GameLogEntry()).keys.toList(),
            "GameEvent" to event(GameEvent()).keys.toList(),
            "PendingEffect" to pending(PendingEffect()).keys.toList(),
            "Prompt" to prompt(Prompt()).keys.toList(),
            "OfferedEffect" to offered(Prompt.OfferedEffect(null, null, null, null, null, null, null)).keys.toList(),
            "Combat" to combat(Combat()).keys.toList(),
            "Activatable" to activatable(Chain.Activatable(null, null, null, null)).keys.toList(),
        )
    }

    // ------------------------------------------------------------------ Values

    /** Any value the updates carry (an event's data holds plain values, lists and maps). */
    fun value(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        // An untyped value (an event's data): on JavaScript a whole Double reads as an Int (none of the engine's data holds one)
        is Long -> JsonPrimitive(v)
        is Int, is Short, is Byte -> JsonPrimitive(v as Number)
        is Double -> double(v)
        is Float -> double(v.toDouble())
        is Enum<*> -> JsonPrimitive(v.name)
        is CardSlot -> card(v)
        is PlayerState -> player(v)
        is GameLogEntry -> logEntry(v)
        is GameEvent -> event(v)
        is PendingEffect -> pending(v)
        is Prompt -> prompt(v)
        is Prompt.OfferedEffect -> offered(v)
        is Combat -> combat(v)
        is Chain.Activatable -> activatable(v)
        is FloatingEffect -> floating(v)
        is GameStateUpdate -> tree(v)
        is Map<*, *> -> map(v)
        is Collection<*> -> list(v)
        is Array<*> -> JsonArray(v.map { value(it) })
        else -> throw IllegalArgumentException("No JSON for a " + v::class.simpleName)
    }

    private fun obj(vararg properties: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*properties))

    private fun str(s: String?): JsonElement = if (s == null) JsonNull else JsonPrimitive(s)

    private fun bool(b: Boolean): JsonElement = JsonPrimitive(b)

    // By the property's declared type: on JavaScript an Int and a Double are both a number (`is Double` holds for 0)
    private fun num(n: Int?): JsonElement = if (n == null) JsonNull else JsonPrimitive(n)

    private fun num(n: Long?): JsonElement = if (n == null) JsonNull else JsonPrimitive(n)

    private fun num(n: Double?): JsonElement = if (n == null) JsonNull else double(n)

    private fun double(d: Double): JsonElement = JsonUnquotedLiteral(Platform.doubleText(d))

    private fun list(items: Collection<*>?): JsonElement = if (items == null) JsonNull else JsonArray(items.map { value(it) })

    private fun map(items: Map<*, *>?): JsonElement {
        if (items == null) return JsonNull
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in items) out[k.toString()] = value(v)
        return JsonObject(out)
    }
}

/**
 * JSON text as Jackson writes it by default: compact, the members in the tree's order, strings with `"`, `\` and the
 * control characters escaped (`\b \t \n \f \r`, the rest as `\u00XX` with upper-case digits) and nothing else, numbers as
 * their literal.
 */
object JacksonText {

    fun write(element: JsonElement): String = StringBuilder().also { write(element, it) }.toString()

    fun write(element: JsonElement, out: StringBuilder) {
        when (element) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                var first = true
                for ((k, v) in element) {
                    if (!first) out.append(',')
                    first = false
                    string(k, out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                for (i in element.indices) {
                    if (i > 0) out.append(',')
                    write(element[i], out)
                }
                out.append(']')
            }
            is JsonPrimitive -> if (element.isString) string(element.content, out) else out.append(element.content)
        }
    }

    private const val HEX = "0123456789ABCDEF"

    private fun string(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c < ' ' -> when (c) {
                    '\b' -> out.append("\\b")
                    '\t' -> out.append("\\t")
                    '\n' -> out.append("\\n")
                    '\u000C' -> out.append("\\f")
                    '\r' -> out.append("\\r")
                    else -> out.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 0xF])
                }
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
