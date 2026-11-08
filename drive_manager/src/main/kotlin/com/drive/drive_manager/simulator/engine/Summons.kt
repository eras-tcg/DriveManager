package com.drive.drive_manager.simulator.engine

import kotlin.math.max
import com.drive.drive_manager.simulator.engine.Chain.advance
import com.drive.drive_manager.simulator.engine.EffectEngine.CAUSE_EFFECT
import com.drive.drive_manager.simulator.engine.EffectEngine.CAUSE_RITUAL
import com.drive.drive_manager.simulator.engine.EffectEngine.STRUCTURE_PLAYED
import com.drive.drive_manager.simulator.engine.EffectEngine.SUMMON_SACRIFICE
import com.drive.drive_manager.simulator.engine.EffectEngine.allies
import com.drive.drive_manager.simulator.engine.EffectEngine.canPay
import com.drive.drive_manager.simulator.engine.EffectEngine.find
import com.drive.drive_manager.simulator.engine.EffectEngine.gemsOf
import com.drive.drive_manager.simulator.engine.EffectEngine.gemsText
import com.drive.drive_manager.simulator.engine.EffectEngine.inFrontLine
import com.drive.drive_manager.simulator.engine.EffectEngine.keywordLevel
import com.drive.drive_manager.simulator.engine.EffectEngine.log
import com.drive.drive_manager.simulator.engine.EffectEngine.name
import com.drive.drive_manager.simulator.engine.EffectEngine.pay
import com.drive.drive_manager.simulator.engine.EffectEngine.record
import com.drive.drive_manager.simulator.engine.EffectEngine.rulesPrompt
import com.drive.drive_manager.simulator.engine.EffectEngine.zone
import com.drive.drive_manager.simulator.engine.EffectEngine.zoneName
import com.drive.drive_manager.simulator.engine.EffectStep.*
import com.drive.drive_manager.simulator.engine.Removals.destroy
import com.drive.drive_manager.simulator.engine.SpecialRequirements.specialRequirement
import com.drive.drive_manager.simulator.engine.SpecialSummons.specialSummon
import com.drive.drive_manager.simulator.engine.effects.model.SpecialRule
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState
import com.drive.drive_manager.simulator.room.Zone

/** Summons and playing cards by the rules: normal summon, utilities and structures, and what follows a summon. */
object Summons {

    // ------------------------------------------------------------------ Normal summon and playing cards

    /**
     * Normal summon (rules 6.2.1.1, 9.1.0.1): taking a creature from the hand to the board without an effect.
     * Pays its cost in gems, enters the Retaguardia and fires the summon triggers.
     *
     * @return an error message for the player, or null when done
     */
    /** The mark of a "puedes agotar…" asked before a normal summon: the summon goes on with the answer (and where it was dropped). */
    const val NORMAL_SUMMON = "normal-summon|"

    /** Why this card of the hand cannot be summoned normally now; null: it can (the main phase of the turn is asked apart). */
    @JvmStatic
    fun whyCannotSummon(room: GameRoom, userId: String?, player: PlayerState?, card: CardSlot?): String? {
        if (card == null) return "La carta no está en tu mano"
        if ("creature" != card.cardType) return "Solo las criaturas se invocan"
        if (card.cost == null) return "Esta carta no tiene coste normal: no puede invocarse normalmente"
        val cost = card.cost!! - (if (starterAdvantage(room, userId, card, card.cost)) 1 else 0)
        // Its Reactivo–Modificadores ("cuando fueras a invocar esta carta: reduce su coste…"); "puedes agotar esta carta para reducir…" is asked
        // first, even when the cost is already 0 (designer ruling 2026-10-31: it stays 0)
        // Carnirex: the most a sacrifice could lower it
        val canSacrifice = CardEffects.sacrificeToSummon(card)
        val bySacrifice = if (canSacrifice == null) 0 else allies(player).filter { c -> canSacrifice.matches(c) && !name(c).equals(name(card), ignoreCase = true) }
                .maxOfOrNull { c -> if (c.cost == null) 0 else c.cost!! } ?: 0
        if (!canPay(room, userId, max(0, cost - bySacrifice - CostModifiers.available(room, player, CostModifiers.Event.SUMMON, null, card, null))))
            return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y cuesta " + cost
        return null
    }

    @JvmStatic
    fun normalSummon(room: GameRoom, userId: String?, instanceId: String?, x: Double?, y: Double?): String? {
        var error = checkMainPhase(room, userId, "invocar")
        if (error != null) return error

        val player = room.getPlayer(userId)
        val found = if (player == null) null else find(player.hand, instanceId)
        error = whyCannotSummon(room, userId, player, found)
        if (error != null) return error
        val card = found!!
        player!!

        val advantage = starterAdvantage(room, userId, card, card.cost)
        var cost = card.cost!! - (if (advantage) 1 else 0)
        // Carnirex: "puedes sacrificar 1 aliado [dinosaurio] activo … para invocar esta carta en tu línea frontal y reducir su coste en su coste"
        val sacrifice = CardEffects.sacrificeToSummon(card)
        var sacrificed: CardSlot? = null
        if (sacrifice != null) {
            val options = allies(player).filter { c -> sacrifice.matches(c) && !name(c).equals(name(card), ignoreCase = true) }
            val answer = room.usedThisTurn.firstOrNull { k -> k.startsWith(SUMMON_SACRIFICE + card.instanceId + "|") }
            if (answer == null && !options.isEmpty()) {
                rulesPrompt(room, userId, PromptPurpose.SUMMON_SACRIFICE.id(), name(card) + " · al invocarla",
                        "Puedes sacrificar 1 aliado [dinosaurio] activo para invocar " + name(card) + " en tu línea frontal reduciendo su coste en el coste "
                                + "del aliado. O ninguno.", "Sacrificar", options, player.field)
                room.prompt!!.min = 0
                room.prompt!!.subjectId = card.instanceId
                room.prompt!!.summonerId = if (y == null) "" else y.toString()
                return null
            }
            if (answer != null) {
                room.usedThisTurn.remove(answer)
                val id = answer.substring((SUMMON_SACRIFICE + card.instanceId + "|").length)
                sacrificed = find(options, id)
            }
            if (sacrificed != null) {
                val lower = if (sacrificed.cost == null) 0 else sacrificed.cost!!
                if (!canPay(room, userId, max(0, cost - lower))) return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y cuesta " + (cost - lower)
                destroy(room, sacrificed, player, true, CAUSE_EFFECT, userId, card.instanceId, null)
                cost = max(0, cost - lower)
            }
        }
        val tap = CostModifiers.unansweredTap(room, player, CostModifiers.Event.SUMMON, null, card)
        if (tap != null) {
            rulesPrompt(room, userId, PromptPurpose.COST_TAP.id(), name(tap.holder) + " · " + tap.modifier.label,
                    "¿Agotas " + name(tap.holder) + " para reducir en " + tap.modifier.reduceNow(room, userId) + " el coste de " + name(card) + "?",
                    "Agotar", frozenListOf(tap.holder), frozenListOf(tap.holder))
            room.prompt!!.kind = Prompt.Kind.CONFIRM
            room.prompt!!.subjectId = card.instanceId
            room.prompt!!.summonerId = NORMAL_SUMMON + (if (y == null) "" else y)
            return null
        }
        cost = max(0, cost - CostModifiers.applyAutomatic(room, player, CostModifiers.Event.SUMMON, null, card, null, cost))
        if (!canPay(room, userId, cost)) return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y cuesta " + cost
        pay(room, userId, cost)
        if (advantage) room.starterAdvantageUserId = null

        player.hand.remove(card)
        card.faceDown = false
        card.tapped = false
        card.x = x ?: 50.0
        // Normal summons enter the Retaguardia (the lower half of the owner's field); Asalto lets it enter the Línea frontal, exhausted
        val assault = y != null && y < 55 && keywordLevel(card, "Asalto") != null
        // Carnirex by a sacrifice: "invocar esta carta en tu línea frontal" (active, it is not Asalto)
        card.y = if (sacrificed != null) 25.0 else if (assault) y!! else if (y != null && y >= 55) y else 75.0
        if (assault) card.tapped = true
        player.field.add(card)

        record(room, GameEvent.NORMAL_SUMMON, userId, card, "hand", "field", null, frozenMapOf<String, Any?>("cost", cost),
                player.username + " invocó " + name(card) + " pagando " + gemsText(cost) + (if (advantage) " (ventaja del iniciador)" else ""))
        offerAssault(room, player, card)
        advance(room)
        return null
    }

    /** Plays a utility or structure from the hand (rules 6.2.2, 6.2.3): pays its cost, enters the Retaguardia. */
    /** Why this card of the hand cannot be played now; null: it can (the main phase of the turn is asked apart). */
    @JvmStatic
    fun whyCannotPlay(room: GameRoom, userId: String?, card: CardSlot?): String? {
        if (card == null) return "La carta no está en tu mano"
        if ("utility" != card.cardType && "structure" != card.cardType) return "Solo las utilidades y estructuras se juegan"
        // Only one structure can be played normally per turn (by effect or from a life it does not count)
        if ("structure" == card.cardType && structurePlayed(room, userId)) return "Solo puedes jugar una estructura por turno"
        val blocked = Chain.cannotActivateOnPlay(room, userId, card)
        if (blocked != null) return blocked
        val cost = CardEffects.playCost(room, userId, card)
        if (!canPay(room, userId, cost)) return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y cuesta " + cost
        return null
    }

    @JvmStatic
    fun playByPlayer(room: GameRoom, userId: String?, instanceId: String?, x: Double?, y: Double?): String? {
        var error = checkMainPhase(room, userId, "jugar cartas")
        if (error != null) return error
        val player = room.getPlayer(userId)
        val found = if (player == null) null else find(player.hand, instanceId)
        error = whyCannotPlay(room, userId, found)
        if (error != null) return error
        val card = found!!
        val structure = "structure" == card.cardType
        val cost = CardEffects.playCost(room, userId, card)
        pay(room, userId, cost)
        if (structure) room.usedThisTurn.add(STRUCTURE_PLAYED + userId)
        play(room, player, card, cost, x, y, null)
        advance(room)
        return null
    }

    /** Utilities in the player's hand whose "En Juego" cannot be activated now: the board does not offer to play them. */
    @JvmStatic
    fun unplayable(room: GameRoom, userId: String?): MutableList<String> {
        val player = room.getPlayer(userId)
        if (player == null || room.status != GameRoom.RoomStatus.IN_PROGRESS) return frozenListOf()
        @Suppress("UNCHECKED_CAST")
        return ArrayList(player.hand).filter { c -> Chain.cannotActivateOnPlay(room, userId, c) != null }
                .map { it.instanceId } as MutableList<String>
    }

    /** The player already played a structure normally this turn. */
    @JvmStatic
    fun structurePlayed(room: GameRoom, userId: String?): Boolean {
        return room.usedThisTurn.contains(STRUCTURE_PLAYED + userId)
    }

    @JvmStatic
    fun play(room: GameRoom, player: PlayerState?, card: CardSlot, cost: Int, x: Double?, y: Double?, effect: PendingEffect?) {
        player!!.hand.remove(card)
        card.faceDown = false
        card.tapped = false
        card.x = x ?: 50.0
        card.y = if (y != null && y >= 55) y else 75.0
        player.field.add(card)
        record(room, GameEvent.PLAY, player.userId, card, "hand", "field", effect, frozenMapOf<String, Any?>("cost", cost),
                player.username + " juega " + name(card) + (if (cost == 0) " sin pagar su coste" else " pagando " + gemsText(cost))
                        + (if (effect != null) " (" + effect.sourceName + ")" else ""))
    }

    @JvmStatic
    fun checkMainPhase(room: GameRoom, userId: String?, what: String): String? {
        if (room.status != GameRoom.RoomStatus.IN_PROGRESS) return "La partida no está en curso"
        if (userId!! != room.currentTurnUserId) return "Solo puedes $what durante tu turno"
        if ("MAIN" != room.currentPhase) return "Solo puedes $what en la fase principal"
        if (room.isEngineBusy()) return if (room.combat != null) "Hay un combate en curso" else "Hay un efecto resolviéndose"
        return null
    }

    /**
     * A special summon done on the board (Ritual, Materialización, Evolución, Ascenso): records it and fires
     * the summon triggers. A Ritual sacrifices the summoner (rule 9.3.0.3): it counts as destroyed, so its
     * "En Destrucción" effects trigger too, once the whole Ritual is done (rule 9.3.0.7).
     */
    @JvmStatic
    fun specialSummoned(room: GameRoom, userId: String?, summoned: CardSlot?, summoner: CardSlot?, summonType: String?, logText: String?) {
        val player = room.getPlayer(userId) ?: return
        if ("ritual" == summonType) {
            record(room, GameEvent.SACRIFICE, userId, summoner, "field", "tributeZone", null,
                    frozenMapOf<String, Any?>("cause", CAUSE_RITUAL, "ownerId", userId), null)
        } else if ("promotion" == summonType) {
            // Ascenso only tributes the summoner: not a sacrifice nor a destruction (rule 9.6.0.2)
            record(room, GameEvent.TRIBUTE, userId, summoner, "field", "tributeZone", null, null, null)
        }
        // "manual": done by hand, nothing checked its cost or requirement (unlike specialByPlayer), so it does not count
        // for the missions (MissionEvents)
        record(room, GameEvent.SPECIAL_SUMMON, userId, summoned, null, "field", null,
                frozenMapOf<String, Any?>("summonType", summonType ?: "special", GameEvent.MANUAL, true), logText)
        afterSpecialSummon(room, player, summoned!!, null)
        advance(room)
    }

    /**
     * Rule 6.2.1.4, "ventaja del iniciador": once per game the second player summons an Iniciador paying 1 less
     * (a reduction by the rules, not by an effect). It is used whenever it lowers a cost.
     */
    @JvmStatic
    fun starterAdvantage(room: GameRoom, userId: String?, card: CardSlot?, cost: Int?): Boolean {
        return card!!.starter && cost != null && cost > 0 && userId!! == room.starterAdvantageUserId
    }

    /**
     * Rule 4.0.7.3: right after a special summon, before anything else, the creature may move up to the Línea
     * frontal. Then the regulatory draw (rule 9.1.0.4); only after both do its triggers go on.
     */
    @JvmStatic
    fun afterSpecialSummon(room: GameRoom, player: PlayerState, card: CardSlot, effect: PendingEffect?) {
        if ("creature" == card.cardType && !inFrontLine(card) && find(player.field, card.instanceId) != null) {
            rulesPrompt(room, player.userId, PromptPurpose.SPECIAL_FRONT.id(), name(card) + " · Línea frontal",
                    "¿Mueves a " + name(card) + " a tu línea frontal? Después robas 1 carta por la invocación.",
                    "A la línea frontal", frozenListOf(card), frozenListOf(card))
            room.prompt!!.kind = Prompt.Kind.CONFIRM
            room.prompt!!.subjectId = card.instanceId
            if (effect != null) room.prompt!!.effectId = effect.id
            return
        }
        regulatoryDraw(room, player, effect)
    }

    /**
     * "Todos Los Turnos: cuando fueras a invocar especialmente por X, puedes agotar esta carta para reducir el coste especial en 1"
     * (Bosque Frondoso and the like): optional, so it is asked before the summon goes on. The answer is kept for this summon.
     */
    @JvmStatic
    fun askTap(room: GameRoom, player: PlayerState, card: CardSlot, kind: String?, summonerId: String?): Boolean {
        val use = CostModifiers.unansweredTap(room, player, CostModifiers.Event.SPECIAL_SUMMON, kind, card) ?: return false
        rulesPrompt(room, player.userId, PromptPurpose.COST_TAP.id(), name(use.holder) + " · " + use.modifier.label,
                "¿Agotas " + name(use.holder) + " para reducir en " + use.modifier.reduceNow(room, player.userId) + " el coste especial de " + name(card) + "?",
                "Agotar", frozenListOf(use.holder), frozenListOf(use.holder))
        room.prompt!!.kind = Prompt.Kind.CONFIRM
        room.prompt!!.subjectId = card.instanceId
        room.prompt!!.summonerId = summonerId
        return true
    }

    /**
     * Rules 9.2.0.2–9.2.0.7: the summoned creature takes the summoner's zone and its whole game state (active or
     * exhausted, attacker or blocker, strength changes and every floating effect) but keeps its own printed
     * properties; the summoner's Recursos and Materiales pass to it. Continuous effects are checked again afterwards.
     */
    @JvmStatic
    fun inheritFromSummoner(room: GameRoom, target: CardSlot, summoner: CardSlot?) {
        target.faceDown = false
        target.x = summoner!!.x
        target.y = summoner.y
        target.tapped = summoner.tapped
        target.strengthModifier = summoner.strengthModifier
        // Every effect that lasts a while passes on (designer: the summoner always hands its floating effects to the
        // creature summoned): strength and abilities until the end of the turn, of the next turn or of its next attack,
        // and "no se reactiva"; its printed strength stays its own
        target.turnKeywords!!.clear()
        target.turnKeywords!!.addAll(summoner.turnKeywords!!)
        target.laterKeywords!!.clear()
        target.laterKeywords!!.addAll(summoner.laterKeywords!!)
        target.untilNextAttack!!.clear()
        target.untilNextAttack!!.addAll(summoner.untilNextAttack!!)
        target.turnStrength = summoner.turnStrength
        target.laterStrength = summoner.laterStrength
        target.noUntap = summoner.noUntap
        target.noStartUntap = summoner.noStartUntap
        target.floating!!.clear()
        target.floating!!.addAll(summoner.floating!!)
        target.materials!!.addAll(0, summoner.materials!!)
        target.resources!!.addAll(0, summoner.resources!!)
        summoner.materials = ArrayList()
        summoner.resources = ArrayList()
        val c = room.combat
        if (c != null) {
            val from = summoner.instanceId
            val to = target.instanceId
            if (from!! == c.attackerId) c.attackerId = to
            if (from == c.blockerId) c.blockerId = to
            if (from == c.targetId) c.targetId = to
        }
    }

    /** The Ascenso that waited for its summoner's attack: the creature arrives now and takes its place in the combat. */
    @JvmStatic
    fun completeAscension(room: GameRoom) {
        val pending = room.pendingAscension
        room.pendingAscension = null
        val player = room.getPlayer(pending!!.playerId) ?: return
        val card = find(zone(player, pending.fromZone), pending.cardId)
        val summoner = find(player.field, pending.summonerId)
        if (card == null || summoner == null) {
            log(room, "El Ascenso no se completa: " + (if (summoner == null) "el invocador dejó el campo" else "la carta ya no está"), null)
            return
        }
        specialSummon(room, player, card, specialRequirement(card)!!, summoner, frozenListOf(), pending.fromZone, 0, null)
    }

    /**
     * Asalto: "en lugar de quedar en tu retaguardia, puedes colocarla agotada en tu línea frontal". It modifies the summon, so it is
     * asked as the creature enters the Retaguardia, before anything reacts to it (not a triggered effect).
     */
    @JvmStatic
    fun offerAssault(room: GameRoom, player: PlayerState, card: CardSlot) {
        if (room.prompt != null || keywordLevel(card, "Asalto") == null || inFrontLine(card)) return
        rulesPrompt(room, player.userId, PromptPurpose.ASSAULT.id(), name(card) + " · Asalto",
                "En lugar de quedar en tu retaguardia, puedes colocarla agotada en tu línea frontal.", "Línea frontal", frozenListOf(card), frozenListOf(card))
        room.prompt!!.kind = Prompt.Kind.CONFIRM
        room.prompt!!.subjectId = card.instanceId
    }

    /** Ritual by effect (rule 9.3): pays the special cost minus the reduction and summons it. */
    @JvmStatic
    fun performRitual(
        room: GameRoom, effect: PendingEffect, player: PlayerState, target: CardSlot, ritual: SpecialRule,
        summoner: CardSlot?, others: List<CardSlot>,
    ) {
        var cost = max(0, target.specialCost!! - ritual.reduce)
        cost -= CostModifiers.applyAutomatic(room, player, CostModifiers.Event.SPECIAL_SUMMON, "ritual", target, summoner, cost)
        pay(room, player.userId, cost)
        zone(player, ritual.zone).remove(target)
        effect.ritualTargetId = null
        ritualSummon(room, player, target, ritual.zone, cost, summoner, others, effect,
                player.username + " invoca por Ritual " + name(target) + " de sus " + zoneName(ritual.zone) + " usando "
                        + name(summoner) + " y pagando " + gemsText(cost) + " (" + effect.sourceName + ")")
    }

    /**
     * Rule 9.3: the summoner's attachments pass to the summoned creature, which takes its place and state; the
     * summoner and the other creatures used are sacrificed. Then the move up and the regulatory draw.
     */
    @JvmStatic
    fun ritualSummon(
        room: GameRoom, player: PlayerState, target: CardSlot, fromZone: String?, cost: Int,
        summoner: CardSlot?, others: List<CardSlot>, effect: PendingEffect?, logText: String?,
    ) {
        inheritFromSummoner(room, target, summoner)
        destroy(room, summoner, player, true, CAUSE_RITUAL, player.userId, null, effect)
        for (other in others) destroy(room, other, player, true, CAUSE_RITUAL, player.userId, null, effect)
        player.field.add(target)
        record(room, GameEvent.SPECIAL_SUMMON, player.userId, target, fromZone, "field", effect,
                frozenMapOf<String, Any?>("summonType", "ritual", "cost", cost, "used", 1 + others.size), logText)
        afterSpecialSummon(room, player, target, effect)
    }

    /** Summon by effect (rule 9.1.0.2): pays the cost, enters the Retaguardia, then the regulatory draw (9.1.0.4). */
    @JvmStatic
    fun summonByEffect(
        room: GameRoom, effect: PendingEffect, player: PlayerState?, card: CardSlot, fromZone: String?, reduce: Int,
        toFront: Boolean,
    ) {
        val cost = max(0, card.cost!! - reduce)
        pay(room, player!!.userId, cost)
        zone(player, fromZone).remove(card)
        card.faceDown = false
        card.tapped = false
        card.x = 50.0
        // By effect it enters the Retaguardia, unless the effect says "en tu línea frontal"
        card.y = if (toFront) 25.0 else 75.0
        player.field.add(card)
        record(room, GameEvent.EFFECT_SUMMON, player.userId, card, fromZone, "field", effect, frozenMapOf<String, Any?>("cost", cost),
                player.username + " invoca " + name(card) + (if (Zone.HAND.`is`(fromZone)) " de su mano" else " de sus " + zoneName(fromZone))
                        + " pagando " + gemsText(cost)
                        + " (" + effect.sourceName + ")")
        regulatoryDraw(room, player, effect)
        offerAssault(room, player, card)
    }

    @JvmStatic
    fun regulatoryDraw(room: GameRoom, player: PlayerState, effect: PendingEffect?) {
        if (player.deck.isEmpty()) return
        drawOne(room, player, effect, true)
        log(room, player.username + " roba 1 carta por la invocación", room.eventSeq)
    }

    @JvmStatic
    fun drawOne(room: GameRoom, player: PlayerState, effect: PendingEffect?, regulatory: Boolean) {
        val card = player.deck.removeAt(0)
        card.faceDown = false
        player.hand.add(card)
        record(room, GameEvent.DRAW, player.userId, card, "deck", "hand", effect, if (regulatory) frozenMapOf<String, Any?>("regulatory", true) else null, null)
    }
}
