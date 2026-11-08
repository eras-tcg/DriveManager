package com.drive.drive_manager.simulator.room

import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.engine.GameEvent

/**
 * Shallow copies (the same values; lists and maps shared) of what [StateMask] changes for one viewer, written
 * out field by field: what Spring's BeanUtils.copyProperties did, without reflection, so the engine can run in the
 * browser. Exactly as BeanUtils did, a card's copy takes `getLevel()` (the printed level plus the continuous
 * one) as its level, and keeps the continuous level too. `ShallowCopyTest` lists every field.
 */
object ShallowCopy {

    /** A GameStateUpdate with the same values (its lists and maps shared). */
    @JvmStatic
    fun of(o: GameStateUpdate): GameStateUpdate {
        val c = GameStateUpdate()
        c.roomId = o.roomId
        c.status = o.status
        c.players = o.players
        c.hostId = o.hostId
        c.editor = o.editor
        c.bot = o.bot
        c.ranked = o.ranked
        c.replay = o.replay
        c.queued = o.queued
        c.editorBot = o.editorBot
        c.editorSelfBot = o.editorSelfBot
        c.selfBotLevel = o.selfBotLevel
        c.botDelayMs = o.botDelayMs
        c.botEdge = o.botEdge
        c.paused = o.paused
        c.timelineAt = o.timelineAt
        c.timelineSize = o.timelineSize
        c.botLevel = o.botLevel
        c.botChoices = o.botChoices
        c.mulliganReady = o.mulliganReady
        c.firstChoice = o.firstChoice
        c.currentTurnUserId = o.currentTurnUserId
        c.currentPhase = o.currentPhase
        c.event = o.event
        c.actorId = o.actorId
        c.error = o.error
        c.sharedCounter = o.sharedCounter
        c.postGameChoices = o.postGameChoices
        c.subjectUserId = o.subjectUserId
        c.targetedInstanceId = o.targetedInstanceId
        c.targetingSourceInstanceId = o.targetingSourceInstanceId
        c.revealedCardImageUrl = o.revealedCardImageUrl
        c.revealedCardName = o.revealedCardName
        c.logEntry = o.logEntry
        c.eventLog = o.eventLog
        c.timeline = o.timeline
        c.chain = o.chain
        c.prompt = o.prompt
        c.combat = o.combat
        c.firstPlayerId = o.firstPlayerId
        c.starterAdvantageUserId = o.starterAdvantageUserId
        c.activatable = o.activatable
        c.structurePlayed = o.structurePlayed
        c.specialSummonable = o.specialSummonable
        c.unplayable = o.unplayable
        c.legalActions = o.legalActions
        c.attackTargets = o.attackTargets
        c.winnerId = o.winnerId
        c.endReason = o.endReason
        return c
    }

    /** A PlayerState with the same values (its lists and maps shared). */
    @JvmStatic
    fun of(o: PlayerState): PlayerState {
        val c = PlayerState(o.userId, o.username)
        c.userId = o.userId
        c.username = o.username
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
        c.hand = o.hand
        c.deck = o.deck
        c.originalDeck = o.originalDeck
        c.lifeStack = o.lifeStack
        c.tributeZone = o.tributeZone
        c.discardPile = o.discardPile
        c.field = o.field
        return c
    }

    /** A CardSlot with the same values (its lists and maps shared). */
    @JvmStatic
    fun of(o: CardSlot): CardSlot {
        val c = CardSlot()
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
        c.setLevel(o.getLevel()) // as BeanUtils did: the level with the continuous one added (see the class)
        c.colors = o.colors
        c.classes = o.classes
        c.specialCost = o.specialCost
        c.requirement = o.requirement
        c.keywords = o.keywords
        c.inheritKeywords = o.inheritKeywords
        c.turnKeywords = o.turnKeywords
        c.laterKeywords = o.laterKeywords
        c.untilNextAttack = o.untilNextAttack
        c.fieldSeq = o.fieldSeq
        c.turnStrength = o.turnStrength
        c.laterStrength = o.laterStrength
        c.continuousStrength = o.continuousStrength
        c.continuousLevel = o.continuousLevel
        c.returnAtTurnEnd = o.returnAtTurnEnd
        c.continuousKeywords = o.continuousKeywords
        c.floating = o.floating
        c.noUntap = o.noUntap
        c.noStartUntap = o.noStartUntap
        c.materials = o.materials
        c.resources = o.resources
        c.unprogrammed = o.unprogrammed
        c.mentions = o.mentions
        c.finish = o.finish
        c.alt = o.alt
        c.altArtUrls = o.altArtUrls
        return c
    }

    /** A GameEvent with the same values (its lists and maps shared). */
    @JvmStatic
    fun of(o: GameEvent): GameEvent {
        val c = GameEvent()
        c.seq = o.seq
        c.ts = o.ts
        c.type = o.type
        c.playerId = o.playerId
        c.cardInstanceId = o.cardInstanceId
        c.cardName = o.cardName
        c.fromZone = o.fromZone
        c.toZone = o.toZone
        c.effectId = o.effectId
        c.resolutionId = o.resolutionId
        c.data = o.data
        return c
    }

    /** A GameLogEntry with the same values (its lists and maps shared). */
    @JvmStatic
    fun of(o: GameLogEntry): GameLogEntry {
        val c = GameLogEntry()
        c.ts = o.ts
        c.text = o.text
        c.eventSeq = o.eventSeq
        c.kind = o.kind
        c.sourceId = o.sourceId
        c.sourceName = o.sourceName
        c.imageUrl = o.imageUrl
        c.inheritedFromName = o.inheritedFromName
        c.inheritedFromImage = o.inheritedFromImage
        c.detail = o.detail
        return c
    }
}
