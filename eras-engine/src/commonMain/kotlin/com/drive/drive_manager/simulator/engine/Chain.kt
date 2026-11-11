package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmRecord
import com.drive.drive_manager.simulator.engine.CombatRules.combatStep
import com.drive.drive_manager.simulator.engine.EffectEngine.DEFINITIVE_USED
import com.drive.drive_manager.simulator.engine.EffectEngine.find
import com.drive.drive_manager.simulator.engine.EffectEngine.findAnywhere
import com.drive.drive_manager.simulator.engine.EffectEngine.findIn
import com.drive.drive_manager.simulator.engine.EffectEngine.log
import com.drive.drive_manager.simulator.engine.EffectEngine.name
import com.drive.drive_manager.simulator.engine.EffectEngine.names
import com.drive.drive_manager.simulator.engine.EffectEngine.ownerOf
import com.drive.drive_manager.simulator.engine.EffectEngine.record
import com.drive.drive_manager.simulator.engine.EffectEngine.refreshContinuous
import com.drive.drive_manager.simulator.engine.EffectEngine.rulesPrompt
import com.drive.drive_manager.simulator.engine.EffectEngine.script
import com.drive.drive_manager.simulator.engine.EffectEngine.zone
import com.drive.drive_manager.simulator.engine.EffectScript.Limit
import com.drive.drive_manager.simulator.engine.EffectStep.*
import com.drive.drive_manager.simulator.engine.Removals.leaveField
import com.drive.drive_manager.simulator.engine.Removals.promptRemoval
import com.drive.drive_manager.simulator.engine.Resolution.allPossibleAfterIfPossible
import com.drive.drive_manager.simulator.engine.Resolution.possible
import com.drive.drive_manager.simulator.engine.Resolution.stepTop
import com.drive.drive_manager.simulator.engine.Resolution.utilityToDiscard
import com.drive.drive_manager.simulator.engine.StepHelpers.pick
import com.drive.drive_manager.simulator.engine.StepHelpers.prompt
import com.drive.drive_manager.simulator.engine.Summons.completeAscension
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState
import com.drive.drive_manager.simulator.room.Zone

/** Triggers and the chain (rule 11): what an event triggers, the order effects are chained in, the priority to respond, and the slow effects a player activates in the open state. */
object Chain {

    // ------------------------------------------------------------------ Triggers and chain

    /**
     * Moves the game on until it needs a player: turns pending events into triggered effects (only between
     * resolutions), activates them in rules order, resolves the chain and goes on with the combat.
     */
    @JvmStatic
    fun advance(room: GameRoom) {
        refreshContinuous(room)
        while (room.prompt == null && room.status == GameRoom.RoomStatus.IN_PROGRESS) {
            // A card about to leave the field with a replacement: its owner chooses before anything else
            if (!room.pendingRemovals.isEmpty()) {
                promptRemoval(room)
                continue
            }
            val chain = room.chain
            // Rules 7.2.0.4 and 11.6.0.4: once a chain is closed and resolving nothing can be added to it; what
            // triggers meanwhile waits until the whole chain has resolved and then forms a new chain
            val resolving = chain.any { e -> e.priorityDone || e.resolutionId != null }
            val top = if (chain.isEmpty()) null else chain[chain.size - 1]
            if (top != null && paying(top)) {
                // The effect just activated pays its cost before anything else, choices included
                if (!Resolution.run(room, top, script(top).cost!![top.costIndex])) top.costIndex = top.costIndex + 1
            } else if (chain.isEmpty() && !room.pendingEvents.isEmpty()) {
                queueTriggers(room)
            } else if (!resolving && !room.triggerQueue.isEmpty() && !waitsForPhase(room, room.triggerQueue[0])) {
                activateNext(room)
            } else if (!chain.isEmpty()) {
                if (offerPriority(room)) return
                stepTop(room)
            } else if (room.pendingAscension != null) {
                completeAscension(room)
            } else if (room.combat != null) {
                combatStep(room)
            } else {
                return
            }
        }
    }

    /**
     * Rule 8 (start and end of the turn are phases, not one moment): their triggered effects are activated one at a time,
     * each resolved in full (its chain, the combat it starts: "esa fase se detiene hasta que el combate termine") before
     * the next one is offered (Ballesta Montada, Apostol De La Ruina).
     */
    @JvmStatic
    fun waitsForPhase(room: GameRoom, next: PendingEffect): Boolean {
        if (room.chain.isEmpty() && room.combat == null && room.pendingAscension == null) return false
        val event = room.timeline.lastOrNull { it.seq == next.triggerSeq } ?: return false
        return GameEvent.TURN_END == event.type || GameEvent.TURN_START == event.type
    }

    @JvmStatic
    fun queueTriggers(room: GameRoom) {
        val events: List<GameEvent> = ArrayList(room.pendingEvents)
        room.pendingEvents.clear()
        val triggered: MutableList<PendingEffect> = ArrayList()
        val seen: MutableMap<String, Int> = HashMap()
        for (event in events) {
            val now: MutableMap<String, Int> = HashMap()
            for (e in triggersOf(room, event)) {
                // "si …" triggers once per window however many events match (rule 11.7.0.1); the same effect given twice
                // to one card (2 Recursos of the same name) is 2 effects, and both trigger
                val key = e.sourceInstanceId + "|" + e.scriptId
                val n = now.mergeWith(key, 1) { a, b -> a + b }!!
                if (n > seen.valueOr(key, 0)) {
                    seen[key] = n
                    triggered.add(e)
                }
            }
        }
        // Rule 11.7.0.5: mandatory of the active player, mandatory of the opponent, optional of the active
        // player, optional of the opponent (within a group, in the order they happened)
        val active = room.currentTurnUserId
        triggered.sortWith(comparingInt { e: PendingEffect -> rank(e, active) })
        room.triggerQueue.addAll(triggered)
        // A utility played whose "En Juego" does not activate (its condition, a Definitive one already used) has
        // nothing to resolve: it goes to the Descarte (rule 6.2.3.1)
        for (event in events) {
            if (GameEvent.PLAY != event.type) continue
            val resolves = triggered.any { e -> e.sourceInstanceId == event.cardInstanceId && script(e).moment == EffectScript.Moment.ON_PLAY }
            val owner = room.getPlayer(event.playerId)
            val card = if (owner == null) null else find(owner.field, event.cardInstanceId)
            if (!resolves && card != null && "utility" == card.cardType &&
                CardEffects.of(card).any { sc -> sc.moment == EffectScript.Moment.ON_PLAY }) {
                owner!!.field.remove(card)
                leaveField(card)
                owner.discardPile.add(card)
                record(room, GameEvent.SEND, owner.userId, card, "field", "discardPile", null, null,
                    name(card) + " va al descarte: su efecto no se activa")
            }
        }
    }

    @JvmStatic
    fun triggersOf(room: GameRoom, event: GameEvent): List<PendingEffect> {
        val result: MutableList<PendingEffect> = ArrayList()
        // The card's own moment
        val moment = when (event.type!!) {
            GameEvent.NORMAL_SUMMON, GameEvent.EFFECT_SUMMON, GameEvent.SPECIAL_SUMMON -> EffectScript.Moment.ON_SUMMON
            GameEvent.DESTROY, GameEvent.SACRIFICE -> EffectScript.Moment.ON_DESTROY
            GameEvent.ATTACK_DECLARED -> EffectScript.Moment.ON_ATTACK
            GameEvent.ATTACK_ENDED -> EffectScript.Moment.ON_ATTACK_END
            GameEvent.PLAY -> EffectScript.Moment.ON_PLAY
            else -> null
        }
        if (moment != null && event.cardInstanceId != null) {
            val controllerId = if (moment == EffectScript.Moment.ON_DESTROY) ownerOf(event) else event.playerId
            // "En Destrucción" activates from the zone it was sent to (rule 11.4.1.2d); the others from the field
            val zone = if (moment == EffectScript.Moment.ON_DESTROY) event.toZone else "field"
            val source = findIn(room, controllerId, zone, event.cardInstanceId)
            if (source != null) {
                val scripts: MutableList<EffectScript> = ArrayList(CardEffects.of(source))
                val names = event.data?.get("inherited")
                if (moment == EffectScript.Moment.ON_DESTROY && event.data != null && names is List<*>) {
                    for (n in names) scripts.addAll(CardEffects.inheritedOf(n as String?))
                }
                for (script in scripts) {
                    if (script.moment == moment) addIfTriggers(room, result, script, event, source, controllerId)
                }
            }
        }
        // "al final del turno: …" set by an effect this turn (AtEndOfTurn): the rest of it, now
        if (GameEvent.TURN_END == event.type) {
            for (mark in ArrayList(room.usedThisTurn)) {
                if (!mark.startsWith(StepHelpers.DELAYED)) continue
                room.usedThisTurn.remove(mark)
                val p = mark.substring(StepHelpers.DELAYED.length).split("|")
                val script = CardEffects.byId(p[2])
                val source = findAnywhere(room, p[0], p[1])
                if (script == null || source == null) continue
                val e = pending(room, script, p[0], source, event.seq)
                if (!p[3].isEmpty()) {
                    val ids = p[3].split(",")
                    e.subjectId = ids[0]
                    e.summonedId = ids[0]
                    e.saved[EngineContext.SUMMONED_ALL] = ArrayList<String?>(ids)
                }
                result.add(e)
            }
        }
        // Passive effects with a trigger condition, from the cards in play (or in the hand for "En Mano")
        for (p in room.players.values) {
            for (zone in frozenListOf("field", "hand", "tributeZone", "discardPile")) {
                for (card in ArrayList(EffectEngine.zone(p, zone))) {
                    for (script in CardEffects.of(card)) {
                        if (script.moment != EffectScript.Moment.ON_EVENT || script.sourceZone != zone) continue
                        if (script.allyTurn && p.userId != room.currentTurnUserId) continue
                        // A card on the field cannot react to an event that happened before it got there
                        if (Zone.FIELD.`is`(zone) && event.seq < card.fieldSeq) continue
                        addIfTriggers(room, result, script, event, card, p.userId)
                    }
                }
            }
        }
        return result
    }

    @JvmStatic
    fun addIfTriggers(room: GameRoom, result: MutableList<PendingEffect>, script: EffectScript, event: GameEvent,
                      source: CardSlot, controllerId: String?) {
        if (script.condition != null && !script.condition.test(room, event, source, controllerId)) return
        val e = pending(room, script, controllerId, source, event.seq)
        if (limitReached(room, e, script)) {
            if (script.limit == Limit.DEFINITIVE) log(room, name(source) + ": " + limitText(room, e, script), null)
            return
        }
        result.add(e)
    }

    /**
     * The effect's activation limit stops it now (rule 11.8): already used this turn, or a Definitive effect — of any
     * card and moment — on the first turn of the game (designer ruling 2026-10-30).
     */
    @JvmStatic
    fun limitReached(room: GameRoom, e: PendingEffect, script: EffectScript): Boolean {
        if (script.limit == Limit.DEFINITIVE && room.firstTurn) return true
        return script.limit != Limit.NONE && room.usedThisTurn.contains(usageKey(e, script))
    }

    /** Why [limitReached] stops it, for the player. */
    @JvmStatic
    fun limitText(room: GameRoom, e: PendingEffect, script: EffectScript): String {
        if (script.limit != Limit.DEFINITIVE) return "su efecto ya se usó este turno"
        return if (room.firstTurn) "no se pueden activar Efectos Definitivos en el primer turno de la partida"
        else "ya activaste un Efecto Definitivo este turno (solo 1 por turno)"
    }

    /**
     * Designer ruling (2026-10-30): a utility can be played only if its "En Juego" can be activated — its activation
     * limit allows it, its activation condition holds and its cost can be paid (what the resolution does is not checked). It is checked as if the utility were
     * already on the field.
     *
     * @return why it cannot be played now, or null
     */
    @JvmStatic
    fun cannotActivateOnPlay(room: GameRoom, userId: String?, card: CardSlot): String? {
        if ("utility" != card.cardType) return null
        val onPlay = CardEffects.of(card).filter { s -> s.moment == EffectScript.Moment.ON_PLAY }
        val player = room.getPlayer(userId)
        if (onPlay.isEmpty() || player == null) return null
        val from = listOf(player.hand, player.tributeZone, player.discardPile, player.deck, player.lifeStack).firstOrNull { z -> z.contains(card) }
        val at = from?.indexOf(card) ?: -1
        if (from != null) from.removeAt(at)
        player.field.add(card)
        try {
            val played = GameEvent()
            played.type = GameEvent.PLAY
            played.playerId = userId
            played.cardInstanceId = card.instanceId
            played.cardName = card.cardName
            played.fromZone = "hand"
            played.toZone = "field"
            played.seq = Long.MAX_VALUE
            var reason: String? = null
            for (script in onPlay) {
                val why = whyNot(room, userId, card, script, played) ?: return null
                if (reason == null) reason = why
            }
            return name(card) + " no se puede jugar: " + reason
        } finally {
            player.field.remove(card)
            if (from != null) from.add(at, card)
        }
    }

    private fun whyNot(room: GameRoom, userId: String?, card: CardSlot, script: EffectScript, played: GameEvent): String? {
        val e = candidate(script, userId, card, played.seq)
        if (limitReached(room, e, script)) return limitText(room, e, script)
        if (script.condition != null && !script.condition.test(room, played, card, userId)) return "no se cumple la condición de su efecto"
        for (step in script.cost!!) if (!possible(room, e, step)) return "no puedes pagar el coste de su efecto"
        return null
    }

    /**
     * The key an effect's use is counted under. One printed effect written as several scripts (one per way it can start:
     * "si algún aliado es invocado por efecto o si es tu turno") names them "<id>" and "<id>#<variant>": they share one limit.
     */
    @JvmStatic
    fun usageKey(e: PendingEffect, script: EffectScript): String {
        if (script.limit == Limit.DEFINITIVE) return e.controllerId + "|" + DEFINITIVE_USED
        if (script.limit == Limit.ONE_EFFECT_OF_NAME_PER_TURN) return e.controllerId + "|name:" + e.sourceName!!.lowercase()
        val id = script.id?.substringBefore('#')
        return if (script.limit == Limit.ONCE_PER_TURN_BETWEEN_COPIES)
            e.controllerId + "|" + id
        // "Una vez por turno" belongs to this card while it stays on the field (rule 11.8: back on the field it is a new card)
        else e.controllerId + "|" + e.sourceInstanceId + "@" + e.sourceFieldSeq + "|" + id
    }

    @JvmStatic
    fun rank(e: PendingEffect, active: String?): Int {
        val mandatory = script(e).mandatory
        val mine = e.controllerId == active
        return (if (mandatory) 0 else 2) + (if (mine) 0 else 1)
    }

    /** Mandatory effects go straight into the chain; optional ones ask their controller first. */
    @JvmStatic
    fun activateNext(room: GameRoom) {
        val effect = room.triggerQueue[0]
        val script = script(effect)
        if (limitReached(room, effect, script)) {
            // Another copy used it meanwhile
            room.triggerQueue.removeAt(0)
            return
        }
        // Rule 11.7.0.5: the active player's mandatory effects, the opponent's mandatory effects, then the
        // active player's optional ones and the opponent's, all into the same chain
        val active = room.currentTurnUserId
        val head = rank(effect, active)
        val group: MutableList<PendingEffect> = ArrayList()
        for (e in room.triggerQueue) {
            if (rank(e, active) != head) break
            group.add(e)
        }
        if (script.mandatory && !effect.ordered) {
            // Several mandatory effects (also several of one card, like its inherited ones): their controller
            // chains them one at a time (all of them are chained)
            // A lone one too (when the room asks for it): its controller sees what triggered and chains it
            if (group.size > 1 || room.confirmMandatory) {
                val sources = sources(room, group)
                val instruction = if (group.size > 1)
                    "Estos efectos son obligatorios: se encadenan todos. Elige cuál entra ahora como Cadena " +
                            (room.chain.size + 1) + "; después eliges el siguiente. La cadena se resuelve al revés: " +
                            "el último eslabón se resuelve primero."
                else "Este efecto es obligatorio: confírmalo para encadenarlo como Cadena " + (room.chain.size + 1) + "."
                rulesPrompt(room, effect.controllerId, PromptPurpose.ORDER.id(),
                    if (group.size > 1) "Encadenar efectos obligatorios" else "Efecto obligatorio", instruction, "Encadenar", sources, sources)
                offerEffects(room, room.prompt!!, group)
                room.prompt!!.min = 1
                room.prompt!!.max = 1
                return
            }
        }
        if (!script.mandatory && !effect.confirmed) {
            // Optional effects that cannot be activated now (cost, nothing to do, limit) lose their moment, and so do
            // those of a player who turned the optional effects off (except "En Juego": the card was played for it)
            val controller = room.getPlayer(effect.controllerId)
            val skip = controller != null && controller.skipOptional
            var dropped = false
            for (e in group) {
                if (skip && script(e).moment != EffectScript.Moment.ON_PLAY) {
                    room.triggerQueue.remove(e)
                    // As if the player chose not to activate it: the switch is not revealed to anyone
                    record(room, GameEvent.EFFECT_DECLINED, controller!!.userId, null, null, null, e, null,
                        controller.username + " no activa el efecto " + e.label + " de " + e.sourceName)
                    dropped = true
                }
            }
            if (dropped) return
            for (e in group) {
                val s = script(e)
                val used = limitReached(room, e, s)
                if (used || !canActivate(room, e, s)) {
                    room.triggerQueue.remove(e)
                    // Said in the history: otherwise the player only sees that nothing happened
                    log(room, e.sourceName + ": " + e.label + (if (used) ": " + limitText(room, e, s) else " no puede activarse (no se puede pagar su coste o no hay objetivos válidos)"), null)
                    dropped = true
                }
            }
            if (dropped) return
            // Offered one at a time, with the same choice even when only one is left: the chosen one is chained
            // and pays its cost, then the rest are offered again until the player passes; then the opponent
            val sources = sources(room, group)
            if (!sources.isEmpty()) {
                rulesPrompt(room, effect.controllerId, PromptPurpose.OPTIONAL.id(), "Encadenar efectos",
                    "Elige 1 efecto para encadenarlo como Cadena " + (room.chain.size + 1) +
                            (if (group.size > 1) " (después podrás elegir otro)" else "") + ", o no encadenes más. " +
                            "La cadena se resuelve al revés: el último eslabón se resuelve primero.",
                    "Encadenar", sources, sources)
                offerEffects(room, room.prompt!!, group)
                return
            }
        }
        if (script.mandatory || effect.confirmed) {
            room.triggerQueue.removeAt(0)
            for (step in script.cost!!) {
                if (!possible(room, effect, step)) {
                    // A cost that cannot be paid (no valid target): it cannot be activated (rule 11.2.0.2)
                    log(room, effect.sourceName + ": " + effect.label + " no puede activarse (no hay objetivos válidos)", null)
                    if (script.moment == EffectScript.Moment.ON_PLAY) utilityToDiscard(room, effect)
                    return
                }
            }
            activate(room, effect)
            return
        }
        if (!canActivate(room, effect, script)) {
            // Its cost cannot be paid or it would do nothing: it loses its moment
            room.triggerQueue.removeAt(0)
            return
        }
        val link = room.chain.size + 1
        val prompt = StepHelpers.prompt(room, effect, Prompt.Kind.CONFIRM, (if (link == 1) "¿Activas este efecto como Cadena 1? "
        else "¿Lo encadenas como Cadena $link? Se resolverá antes que los eslabones que ya están en la cadena. ") + effect.text)
        val source = findAnywhere(room, effect.controllerId, effect.sourceInstanceId)
        if (source != null) {
            prompt.cards = ArrayList(frozenListOf(source))
            prompt.validIds = ArrayList(frozenListOf(source.instanceId))
        }
        prompt.min = 0
        prompt.max = 1
        prompt.chosenLabel = "Activar"
        room.prompt = prompt
    }

    /** The cards of a group of triggered effects, once each. */
    @JvmStatic
    fun sources(room: GameRoom, group: List<PendingEffect>): List<CardSlot> {
        val sources: MutableList<CardSlot> = ArrayList()
        for (e in group) {
            val source = findAnywhere(room, e.controllerId, e.sourceInstanceId)
            if (source != null && !sources.contains(source)) sources.add(source)
        }
        return sources
    }

    /**
     * The effects of a group with their whole text, for a chaining choice. Each one is chosen by its own id: a
     * card can trigger several at once (its own and the inherited ones); its card's id picks its first one.
     */
    @JvmStatic
    fun offerEffects(room: GameRoom, prompt: Prompt, group: List<PendingEffect>) {
        prompt.cardLabels = labels(group)
        // An inherited effect: the Recurso that gives it is shown on the card
        prompt.effects = group.map { e ->
            Prompt.OfferedEffect(e.id, e.sourceInstanceId, e.sourceName, e.label, e.text, e.inheritedFromName, e.inheritedFromImage)
        }
    }

    /** The effect a chaining choice picked: by its own id, or the first one of the card picked. */
    @JvmStatic
    fun picked(e: PendingEffect, pick: String?): Boolean {
        return e.id == pick || e.sourceInstanceId == pick
    }

    /** What each card of a group triggered ("Celeridad", "En Destrucción"), for the choice. */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun labels(group: List<PendingEffect>): MutableMap<String, String> {
        val labels: MutableMap<String?, String> = LinkedHashMap()
        for (e in group) labels.mergeWith(e.sourceInstanceId, e.label!!) { a, b -> "$a + $b" }
        return labels as MutableMap<String, String>
    }

    /** Puts the effect in the chain; its cost, if any, is paid now (rule 11.2.0.2). */
    @JvmStatic
    fun activate(room: GameRoom, effect: PendingEffect) {
        val script = script(effect)
        if (script.limit != Limit.NONE) room.usedThisTurn.add(usageKey(effect, script))
        effect.link = room.chain.size + 1
        record(room, GameEvent.EFFECT_ACTIVATED, effect.controllerId, null, null, null, effect,
            frozenMapOf<String, Any?>("label", effect.label, "link", effect.link, "sourceId", effect.sourceInstanceId,
                "sourceName", effect.sourceName, "scriptId", effect.scriptId),
            "Cadena " + effect.link + ": se activa el efecto " + effect.label + " de " + effect.sourceName)
        // Its cost is paid next (advance), before anything else happens
        room.chain.add(effect)
    }

    /** The effect is still paying its cost. */
    @JvmStatic
    fun paying(effect: PendingEffect): Boolean {
        return effect.costIndex < script(effect).cost!!.size
    }

    /** An optional effect is offered only when its cost can be paid and its choices have something to choose. */
    /** How many of the next steps a connector makes conditional (0 for any other step). */
    @JvmStatic
    fun covers(step: EffectStep): Int {
        return when (step) {
            is IfDone -> step.covers
            is IfNotDone -> step.covers
            is IfTributed -> step.covers
            is IfCondition -> step.covers
            is IfChosenGone -> step.covers
            is IfSaved -> step.covers
            is MayDo -> step.covers
            else -> 0
        }
    }

    @JvmStatic
    fun canActivate(room: GameRoom, effect: PendingEffect, script: EffectScript): Boolean {
        for (step in script.cost!!) if (!possible(room, effect, step)) return false
        // Designer ruling (2026-10-30): the cost can be paid to activate an effect even when its resolution would do nothing
        // (Sacerdotisa Del Portal Del Eclipse with no valid creature in the Tributos)
        if (!script.cost!!.isEmpty()) return true
        if (!allPossibleAfterIfPossible(room, effect, script.steps, 0)) return false
        // "Si ningún enemigo fue …: …" (IfNotDone): the effect does something even when there is nothing to choose
        if (script.steps!!.any { s -> s is IfNotDone }) return true
        val steps = script.steps!!
        var i = 0
        while (i < steps.size) {
            // "puedes A para B": A was checked; what B needs does not stop it
            if (steps[i] is IfPossible) return true
            val covers = covers(steps[i])
            if (covers > 0) {
                i += covers
                i++
                continue
            }
            val s = steps[i]
            val needsCards = s is NeedsTarget || (s is EffectStep.Do && s.action!!.needsCards())
            if (needsCards && !possible(room, effect, steps[i])) return false
            i++
        }
        return true
    }

    @JvmStatic
    fun pending(room: GameRoom, script: EffectScript, controllerId: String?, source: CardSlot, triggerSeq: Long): PendingEffect {
        val e = candidate(script, controllerId, source, triggerSeq)
        e.id = room.nextId("eff")
        return e
    }

    /** An effect that could be activated, still without an id. */
    @JvmStatic
    fun candidate(script: EffectScript, controllerId: String?, source: CardSlot, triggerSeq: Long): PendingEffect {
        val e = PendingEffect()
        e.scriptId = script.id
        e.controllerId = controllerId
        e.sourceInstanceId = source.instanceId
        e.sourceFieldSeq = source.fieldSeq
        e.sourceName = name(source)
        e.label = script.label
        e.text = script.text
        e.triggerSeq = triggerSeq
        val from = CardEffects.inheritedFrom(script.id)
        if (from != null) {
            val resource = source.resources!!.firstOrNull { r -> from.equals(r.cardName, ignoreCase = true) }
            e.inheritedFromName = if (resource != null) resource.cardName else from
            e.inheritedFromImage = resource?.imageUrl
        }
        return e
    }

    /**
     * Rules 11.3.0.3–11.3.0.5: before the chain resolves, the active player and then the opponent may add effects
     * that respond to its last effect; the chain is closed once both pass in a row. A player with nothing to
     * respond with passes on their own.
     *
     * @return true when it waits for a player
     */
    @JvmStatic
    fun offerPriority(room: GameRoom): Boolean {
        val chain = room.chain
        val top = chain[chain.size - 1]
        if (top.resolutionId != null || top.priorityDone) return false
        val active = room.currentTurnUserId
        for (playerId in listOf(active, if (active == null) null else room.getOpponentId(active))) {
            if (playerId == null || top.passed.contains(playerId)) continue
            val options = responses(room, playerId, top)
            if (options.isEmpty()) {
                top.passed.add(playerId)
                continue
            }
            val sources = options.mapNotNull { e -> findAnywhere(room, playerId, e.sourceInstanceId) }.distinct()
            rulesPrompt(room, playerId, PromptPurpose.PRIORITY.id(), "Responder a " + top.sourceName,
                "Puedes activar un efecto en respuesta a " + top.label + " de " + top.sourceName + ", o pasar.",
                "Activar", sources, sources)
            return true
        }
        chain.forEach { e -> e.priorityDone = true }
        return false
    }

    /** Effects a player could add in response to `top`: "RESPONSE" effects whose condition accepts its activation. */
    @JvmStatic
    fun responses(room: GameRoom, playerId: String?, top: PendingEffect): List<PendingEffect> {
        val player = room.getPlayer(playerId) ?: return frozenListOf()
        var activation: GameEvent? = null
        for (e in room.timeline) {
            if (GameEvent.EFFECT_ACTIVATED == e.type && top.id == e.effectId) activation = e
        }
        val result: MutableList<PendingEffect> = ArrayList()
        for (zone in frozenListOf("field", "hand")) {
            for (card in EffectEngine.zone(player, zone)) {
                for (script in CardEffects.of(card)) {
                    if (script.moment != EffectScript.Moment.RESPONSE || script.sourceZone != zone) continue
                    if (script.allyTurn && playerId != room.currentTurnUserId) continue
                    if (script.condition != null && (activation == null || !script.condition.test(room, activation, card, playerId))) continue
                    val e = candidate(script, playerId, card, activation?.seq ?: room.eventSeq)
                    if (limitReached(room, e, script)) continue
                    if (canActivate(room, e, script)) result.add(e)
                }
            }
        }
        return result
    }

    // ------------------------------------------------------------------ Slow effects (rule 11.4.1.1)

    /** A slow effect a player can activate now, for the board. */
    @JvmRecord
    data class Activatable(val instanceId: String?, val scriptId: String?, val label: String?, val text: String?)

    @JvmStatic
    fun activatable(room: GameRoom, userId: String?): MutableList<Activatable> {
        return slowEffects(room, userId).mapTo(ArrayList()) { e -> Activatable(e.sourceInstanceId, e.scriptId, e.label, e.text) }
    }

    /**
     * Passive effects without a trigger condition are slow: only in the open state (no chain, choice or combat
     * pending, main phase), within their moment, zone and limit, and only if all they do is possible.
     */
    @JvmStatic
    fun slowEffects(room: GameRoom, userId: String?): List<PendingEffect> {
        if (room.status != GameRoom.RoomStatus.IN_PROGRESS || "MAIN" != room.currentPhase || room.isEngineBusy()) return frozenListOf()
        val player = room.getPlayer(userId) ?: return frozenListOf()
        val result: MutableList<PendingEffect> = ArrayList()
        // "En Tributos" / "En Descarte" effects are activated from those zones too (Zamaranzas)
        for (zone in frozenListOf("field", "hand", "tributeZone", "discardPile")) {
            for (card in EffectEngine.zone(player, zone)) {
                for (script in CardEffects.of(card)) {
                    if (script.moment != EffectScript.Moment.ACTIVATED || script.sourceZone != zone) continue
                    if (script.allyTurn && userId != room.currentTurnUserId) continue
                    if (script.condition != null && !script.condition.test(room, null, card, userId)) continue
                    val e = candidate(script, userId, card, room.eventSeq)
                    if (limitReached(room, e, script)) continue
                    if (canActivate(room, e, script)) result.add(e)
                }
            }
        }
        return result
    }

    /** Activates a slow effect from the board: it starts a chain (rule 11.5.1). */
    @JvmStatic
    fun activateByPlayer(room: GameRoom, userId: String?, instanceId: String?, scriptId: String?): String? {
        if (room.status != GameRoom.RoomStatus.IN_PROGRESS) return "La partida no está en curso"
        if (room.isEngineBusy()) return if (room.combat != null) "Hay un combate en curso" else "Hay un efecto resolviéndose"
        val effect = slowEffects(room, userId)
            .firstOrNull { e -> e.sourceInstanceId == instanceId && (scriptId == null || e.scriptId == scriptId) }
            ?: return "Ese efecto no se puede activar ahora"
        effect.id = room.nextId("eff")
        activate(room, effect)
        advance(room)
        return null
    }
}
