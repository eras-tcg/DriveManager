package com.drive.drive_manager.simulator.room

import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.platform.Platform
import com.drive.drive_manager.simulator.engine.GameRandom
import com.drive.drive_manager.simulator.engine.Combat
import com.drive.drive_manager.simulator.engine.PendingEffect
import com.drive.drive_manager.simulator.engine.Prompt

/**
 * A deep copy of a room, to try moves on it (the bot's look ahead) without touching the real one. Every mutable object
 * of the simulator (the room, its players, cards, effects, prompt and combat) is copied field by field, keeping which
 * ones are the same object (a card in two lists stays one card). Shared, as they never change once made: strings,
 * numbers, enums, dates, records (card definitions, floating effects, offered effects…), and the timeline's events and
 * history lines.
 *
 * Written out field by field, without reflection, so the engine can run where there is none (Kotlin Multiplatform,
 * in the browser). A field added to one of these classes must be copied here too: `RoomCopyTest` lists every
 * field and fails until it is.
 */
class RoomCopy private constructor(
    /** Seeds the copy's own chance (never the room's: trying moves must not change what the real game draws next). */
    private val chance: GameRandom?,
) {

    private val copies: MutableMap<Any, Any> = Platform.identityMap()

    companion object {
        /** A copy with a chance of its own, seeded from `chance`. */
        @JvmStatic
        fun of(room: GameRoom?, chance: GameRandom?): GameRoom? {
            return RoomCopy(chance).room(room)
        }

        /**
         * Puts the state of `from` into `target` (a copy of it: `from` stays as it is), but for the
         * room's own settings (the fields marked [RoomSetting]).
         */
        @JvmStatic
        fun restore(target: GameRoom, from: GameRoom?, chance: GameRandom?) {
            val c = of(from, chance)!!
            target.status = c.status
            target.players = c.players
            target.currentTurnUserId = c.currentTurnUserId
            target.currentPhase = c.currentPhase
            target.sharedCounter = c.sharedCounter
            target.firstChoice = c.firstChoice
            target.starterSelections = c.starterSelections
            target.mulliganReady = c.mulliganReady
            target.postGameChoices = c.postGameChoices
            target.eventLog = c.eventLog
            target.timeline = c.timeline
            target.eventSeq = c.eventSeq
            target.chain = c.chain
            target.prompt = c.prompt
            target.triggerQueue = c.triggerQueue
            target.pendingEvents = c.pendingEvents
            target.idSeq = c.idSeq
            target.pendingRemovals = c.pendingRemovals
            target.pendingAscension = c.pendingAscension
            target.removalDecided = c.removalDecided
            target.combat = c.combat
            target.fieldGrants = c.fieldGrants
            target.usedThisTurn = c.usedThisTurn
            target.firstPlayerId = c.firstPlayerId
            target.firstTurn = c.firstTurn
            target.starterAdvantageUserId = c.starterAdvantageUserId
            target.endTurnPending = c.endTurnPending
            target.startPhasePending = c.startPhasePending
            target.winnerId = c.winnerId
            target.endReason = c.endReason
            target.searchHope = c.searchHope
            target.startSetup = c.startSetup
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> setLike(o: Collection<T>): MutableSet<T> {
            return Platform.emptySortedSetLike(o)
                ?: if (o is LinkedHashSet<*>) LinkedHashSet() else HashSet()
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> cast(o: Any): T {
            return o as T
        }
    }

    private fun room(o: GameRoom?): GameRoom? {
        if (o == null) return null
        val done = copies[o]
        if (done != null) return done as GameRoom
        val c = GameRoom()
        copies[o] = c
        c.roomId = o.roomId
        c.editor = o.editor
        c.bot = o.bot
        c.ranked = o.ranked
        c.replay = o.replay
        c.reserved = set(o.reserved)!!
        c.editorBot = o.editorBot
        c.editorSelfBot = o.editorSelfBot
        c.selfBotLevel = o.selfBotLevel
        c.version = o.version
        c.paused = o.paused
        c.timelineAt = o.timelineAt
        c.timelineSize = o.timelineSize
        c.botDelayMs = o.botDelayMs
        c.botEdge = o.botEdge
        c.botLevel = o.botLevel
        c.confirmMandatory = o.confirmMandatory
        c.status = o.status
        val players = mapLike<String, PlayerState?>(o.players)
        if (players != null) o.players.forEach { (k, v) -> players[k] = player(v) }
        @Suppress("UNCHECKED_CAST")
        c.players = players as MutableMap<String, PlayerState>
        c.hostId = o.hostId
        c.currentTurnUserId = o.currentTurnUserId
        c.currentPhase = o.currentPhase
        c.sharedCounter = o.sharedCounter
        c.createdAt = o.createdAt
        c.firstChoice = o.firstChoice
        c.emptySince = o.emptySince
        c.starterSelections = map(o.starterSelections)!!
        c.mulliganReady = set(o.mulliganReady)!!
        c.manualPlayers = set(o.manualPlayers)!!
        c.adminPlayers = set(o.adminPlayers)!!
        c.postGameChoices = map(o.postGameChoices)!!
        c.eventLog = list(o.eventLog)!!
        c.timeline = list(o.timeline)!!
        c.eventSeq = o.eventSeq
        c.chain = effects(o.chain)!!
        c.prompt = prompt(o.prompt)
        c.triggerQueue = effects(o.triggerQueue)!!
        c.pendingEvents = list(o.pendingEvents)!!
        c.idSeq = o.idSeq
        c.pendingRemovals = list(o.pendingRemovals)!!
        c.pendingAscension = o.pendingAscension
        c.removalDecided = set(o.removalDecided)!!
        c.combat = combat(o.combat)
        c.fieldGrants = list(o.fieldGrants)!!
        c.usedThisTurn = set(o.usedThisTurn)!!
        c.firstPlayerId = o.firstPlayerId
        c.firstTurn = o.firstTurn
        c.starterAdvantageUserId = o.starterAdvantageUserId
        c.endTurnPending = o.endTurnPending
        c.startPhasePending = o.startPhasePending
        c.winnerId = o.winnerId
        c.endReason = o.endReason
        c.random = if (o.random == null) null else GameRandom(chance!!.nextLong())
        c.headless = o.headless
        c.searchHope = o.searchHope
        c.startSetup = o.startSetup
        return c
    }

    private fun player(o: PlayerState?): PlayerState? {
        if (o == null) return null
        val done = copies[o]
        if (done != null) return done as PlayerState
        val c = PlayerState(o.userId, o.username)
        copies[o] = c
        c.avatar = o.avatar
        c.playmat = o.playmat
        c.connected = o.connected
        c.disconnectedAt = o.disconnectedAt
        c.deckId = o.deckId
        c.deckName = o.deckName
        c.starterCardId = o.starterCardId
        c.ready = o.ready
        c.skipOptional = o.skipOptional
        c.resourceCount = o.resourceCount
        c.turnsStarted = o.turnsStarted
        c.hand = cards(o.hand)!!
        c.deck = cards(o.deck)!!
        c.originalDeck = cards(o.originalDeck)!!
        c.lifeStack = cards(o.lifeStack)!!
        c.tributeZone = cards(o.tributeZone)!!
        c.discardPile = cards(o.discardPile)!!
        c.field = cards(o.field)!!
        return c
    }

    private fun card(o: CardSlot?): CardSlot? {
        if (o == null) return null
        val done = copies[o]
        if (done != null) return done as CardSlot
        val c = CardSlot()
        copies[o] = c
        c.instanceId = o.instanceId
        c.cardId = o.cardId
        c.imageUrl = o.imageUrl
        c.faceDown = o.faceDown
        c.tapped = o.tapped
        c.x = o.x
        c.y = o.y
        c.cardName = o.cardName
        c.cardType = o.cardType
        c.strength = o.strength
        c.specialSummonKind = o.specialSummonKind
        c.starter = o.starter
        c.strengthModifier = o.strengthModifier
        c.cost = o.cost
        c.setLevel(o.getPrintedLevel()) // getLevel() adds the continuous level
        c.colors = list(o.colors)
        c.classes = list(o.classes)
        c.specialCost = o.specialCost
        c.requirement = o.requirement
        c.keywords = list(o.keywords)
        c.inheritKeywords = list(o.inheritKeywords)
        c.turnKeywords = list(o.turnKeywords)
        c.laterKeywords = list(o.laterKeywords)
        c.untilNextAttack = list(o.untilNextAttack)
        c.fieldSeq = o.fieldSeq
        c.turnStrength = o.turnStrength
        c.laterStrength = o.laterStrength
        c.continuousStrength = o.continuousStrength
        c.continuousLevel = o.continuousLevel
        c.returnAtTurnEnd = o.returnAtTurnEnd
        c.continuousKeywords = list(o.continuousKeywords)
        c.floating = list(o.floating)
        c.noUntap = o.noUntap
        c.noStartUntap = o.noStartUntap
        c.materials = cards(o.materials)
        c.resources = cards(o.resources)
        c.unprogrammed = o.unprogrammed
        c.mentions = list(o.mentions)
        c.finish = o.finish
        c.alt = o.alt
        c.altArtUrls = map(o.altArtUrls)
        return c
    }

    private fun effect(o: PendingEffect?): PendingEffect? {
        if (o == null) return null
        val done = copies[o]
        if (done != null) return done as PendingEffect
        val c = PendingEffect()
        copies[o] = c
        c.id = o.id
        c.scriptId = o.scriptId
        c.controllerId = o.controllerId
        c.sourceInstanceId = o.sourceInstanceId
        c.sourceFieldSeq = o.sourceFieldSeq
        c.sourceName = o.sourceName
        c.label = o.label
        c.text = o.text
        c.triggerSeq = o.triggerSeq
        c.link = o.link
        c.inheritedFromName = o.inheritedFromName
        c.inheritedFromImage = o.inheritedFromImage
        c.costIndex = o.costIndex
        c.targetIds = list(o.targetIds)!!
        c.repeatsDone = o.repeatsDone
        c.resolutionId = o.resolutionId
        c.stepIndex = o.stepIndex
        c.sequenceIndex = o.sequenceIndex
        val saved = mapLike<String, MutableList<String?>?>(o.saved)
        if (saved != null) o.saved.forEach { (k, v) -> saved[k] = list(v) }
        @Suppress("UNCHECKED_CAST")
        c.saved = saved as MutableMap<String, MutableList<String?>>
        c.subjectId = o.subjectId
        c.ritualTargetId = o.ritualTargetId
        c.ritualSummonerId = o.ritualSummonerId
        c.summonedId = o.summonedId
        c.looked = cards(o.looked)!!
        c.ordered = o.ordered
        c.confirmed = o.confirmed
        c.lastDone = o.lastDone
        c.lastCount = o.lastCount
        c.lastTributed = list(o.lastTributed)!!
        c.keepOnField = o.keepOnField
        c.passed = set(o.passed)!!
        c.priorityDone = o.priorityDone
        return c
    }

    private fun prompt(o: Prompt?): Prompt? {
        if (o == null) return null
        val done = copies[o]
        if (done != null) return done as Prompt
        val c = Prompt()
        copies[o] = c
        c.id = o.id
        c.kind = o.kind
        c.playerId = o.playerId
        c.effectId = o.effectId
        c.purpose = o.purpose
        c.sourceName = o.sourceName
        c.sourceImageUrl = o.sourceImageUrl
        c.title = o.title
        c.instruction = o.instruction
        c.chosenLabel = o.chosenLabel
        c.cards = cards(o.cards)!!
        c.validIds = list(o.validIds)!!
        c.min = o.min
        c.max = o.max
        c.reorderRest = o.reorderRest
        c.restToTop = o.restToTop
        c.subjectId = o.subjectId
        c.summonerId = o.summonerId
        c.cardLabels = map(o.cardLabels)
        c.effects = list(o.effects)
        c.effectText = o.effectText
        c.maxLevelSum = o.maxLevelSum
        c.exactOrNone = o.exactOrNone
        c.distinctLevels = o.distinctLevels
        c.requiredIds = list(o.requiredIds)
        c.topOrBottom = o.topOrBottom
        return c
    }

    private fun combat(o: Combat?): Combat? {
        if (o == null) return null
        val done = copies[o]
        if (done != null) return done as Combat
        val c = Combat()
        copies[o] = c
        c.attackerId = o.attackerId
        c.attackerOwnerId = o.attackerOwnerId
        c.defenderId = o.defenderId
        c.targetId = o.targetId
        c.blockerId = o.blockerId
        c.step = o.step
        c.phase = o.phase
        c.byEffect = o.byEffect
        c.provokeAsked = o.provokeAsked
        c.blockAsked = o.blockAsked
        c.hitsLeft = o.hitsLeft
        c.untilEndOfAttack = list(o.untilEndOfAttack)!!
        c.enemyDestroyed = o.enemyDestroyed
        c.skipBlock = o.skipBlock
        c.damageReplaceAsked = o.damageReplaceAsked
        c.damageTributeAskedAt = o.damageTributeAskedAt
        return c
    }

    // --- Collections: a new one of the same kind, as the reflective copy made them ---

    private fun cards(o: List<CardSlot?>?): MutableList<CardSlot>? {
        if (o == null) return null
        val done = copies[o]
        if (done != null) return cast(done)
        val c = ArrayList<CardSlot?>(o.size)
        copies[o] = c
        for (s in o) c.add(card(s))
        @Suppress("UNCHECKED_CAST")
        return c as MutableList<CardSlot>
    }

    private fun effects(o: List<PendingEffect?>?): MutableList<PendingEffect>? {
        if (o == null) return null
        val done = copies[o]
        if (done != null) return cast(done)
        val c = ArrayList<PendingEffect?>(o.size)
        copies[o] = c
        for (e in o) c.add(effect(e))
        @Suppress("UNCHECKED_CAST")
        return c as MutableList<PendingEffect>
    }

    /** A list of shared values (strings, records, events). */
    private fun <T> list(o: List<T>?): MutableList<T>? {
        if (o == null) return null
        val done = copies[o]
        if (done != null) return cast(done)
        val c: MutableList<T> = ArrayList(o)
        copies[o] = c
        return c
    }

    /** A set of shared values. */
    private fun <T> set(o: Set<T>?): MutableSet<T>? {
        if (o == null) return null
        val done = copies[o]
        if (done != null) return cast(done)
        val c = setLike(o)
        c.addAll(o)
        copies[o] = c
        return c
    }

    /** A map of shared values. */
    private fun <K, V> map(o: Map<K, V>?): MutableMap<K, V>? {
        val c = mapLike<K, V>(o)
        if (c != null) c.putAll(o!!)
        return c
    }

    @Suppress("UNCHECKED_CAST")
    private fun <K, V> mapLike(o: Map<*, *>?): MutableMap<K, V>? {
        if (o == null) return null
        val done = copies[o]
        if (done != null) return cast(done)
        val c: MutableMap<K, V> = Platform.emptySortedMapLike(o)
                ?: if (o is LinkedHashMap<*, *>) LinkedHashMap() else HashMap()
        copies[o] = c
        return c
    }
}
