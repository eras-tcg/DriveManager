package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.platform.Platform
import kotlin.jvm.JvmSuppressWildcards
import kotlin.jvm.JvmRecord
import kotlin.math.max
import kotlin.math.min
import com.drive.drive_manager.simulator.engine.effects.model.Action
import com.drive.drive_manager.simulator.engine.effects.model.Memory
import com.drive.drive_manager.simulator.engine.effects.model.Selector
import com.drive.drive_manager.simulator.engine.effects.model.SpecialRule
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState
import com.drive.drive_manager.simulator.room.Zone

/**
 * The game as actions see it. While old steps and new actions live together (until phase 7 of the refactor), the memory's
 * well-known names are the fields the old steps read, so one effect can mix both.
 */
internal class EngineContext(private val room: GameRoom, private val effect: PendingEffect, private val repeat: BooleanArray?) : EffectContext {
    override fun random(): GameRandom? = room.random

    constructor(room: GameRoom, effect: PendingEffect) : this(room, effect, BooleanArray(1))

    companion object {
        /** The materials chosen for a special summon. */
        const val SPECIAL_MATERIALS = "specialMaterials"
        /** The creature and the summoner of a special summon: fields the rules read while it is chosen. */
        const val SPECIAL_CARD = "specialCard"
        const val SPECIAL_SUMMONER = "specialSummoner"

        private fun destination(to: String?): String? {
            if (Action.Move.LIFE_TOP == to) return "lifeStack"
            return if (Action.Move.DECK_TOP == to || Action.Move.DECK_BOTTOM == to) "deck" else to
        }

        private fun isAttachment(to: String?): Boolean {
            return to!!.startsWith("materials:") || to.startsWith("resources:")
        }

        /** Rule of the designer (2026-11-01): the event comes from the destination, in this one place, whatever the verb printed. */
        private fun eventOf(zone: String?, from: String?): String {
            return when (zone!!) {
                "tributeZone" -> GameEvent.TRIBUTE
                "hand" -> if (Zone.FIELD.`is`(from)) GameEvent.SEND else GameEvent.ADD_TO_HAND
                else -> GameEvent.SEND
            }
        }

        private fun verb(to: String?): String {
            return when (to!!) {
                Action.Move.TRIBUTES -> "tributa"
                Action.Move.DISCARD -> "descarta"
                Action.Move.DECK_TOP -> "coloca al tope de su mazo"
                Action.Move.DECK_BOTTOM -> "envía al fondo de su mazo"
                Action.Move.HAND -> "añade a su mano"
                Action.Move.FRONT -> "coloca en su línea frontal"
                Action.Move.BACK -> "coloca en su retaguardia"
                Action.Move.LIFE_TOP -> "coloca al tope de sus vidas"
                else -> "mueve"
            }
        }

        private fun untilText(until: Action.Until?): String {
            return when (until!!) {
                Action.Until.TURN, Action.Until.AS_EVENT -> " hasta el final del turno"
                Action.Until.NEXT_TURN -> " hasta el final del turno oponente"
                Action.Until.ATTACK -> " hasta el final del ataque"
                Action.Until.NEXT_ATTACK -> " hasta el final del turno o de su próximo ataque"
            }
        }

        private fun floatingUntil(until: Action.Until?): String {
            return when (until!!) {
                Action.Until.TURN, Action.Until.AS_EVENT -> "turn"
                Action.Until.NEXT_TURN -> "nextTurn"
                Action.Until.ATTACK, Action.Until.NEXT_ATTACK -> "attack"
            }
        }
    }

    override fun sequenceIndex(): Int {
        return effect.sequenceIndex
    }

    override fun sequenceIndex(index: Int) {
        effect.sequenceIndex = index
    }

    override fun limitLevelSum(levels: Int) {
        room.prompt!!.maxLevelSum = levels
    }

    override fun pickable(zone: String?): List<CardSlot> {
        return when (zone!!) {
            "attackers" -> StepHelpers.attackers(player(), CardFilter.any())
            "pickableAttackers" -> StepHelpers.pickable(room, effect, player(), true)
            else -> StepHelpers.pickable(room, effect, player(), false)
        }
    }

    override fun endEffect() {
        effect.stepIndex = EffectEngine.script(effect).steps!!.size - 1
    }

    override fun asksTopOrBottom() {
        room.prompt!!.reorderRest = true
        room.prompt!!.topOrBottom = true
    }

    override fun attack(opponentOnly: Boolean): Boolean {
        return CombatRules.attackByEffect(room, effect, opponentOnly)
    }

    override fun attackChosen(picks: List<String?>?) {
        val subject = StepHelpers.subject(room, effect)
        if (subject != null && room.combat == null) {
            CombatRules.startCombat(room, player()!!.userId, subject, if (picks!!.isEmpty()) null else picks[0], true, effect)
        }
    }

    override fun atEndOfTurn(script: String?) {
        val subject = effect.summonedId ?: (effect.subjectId ?: "")
        room.usedThisTurn.add(StepHelpers.DELAYED + player()!!.userId + "|" + effect.sourceInstanceId + "|" + script + "|" + subject)
        EffectEngine.log(room, effect.sourceName + ": al final del turno se resolverá el resto de su efecto", null)
    }

    override fun goOn() {
        repeat!![0] = true
    }

    override fun specialOptions(special: Selector.Special?): List<CardSlot> {
        return SpecialProcedure.options(room, effect, special)
    }

    override fun specialShown(special: Selector.Special?): List<CardSlot> {
        return SpecialProcedure.shown(room, effect, special)
    }

    override fun specialBounds(special: Selector.Special?, available: Int): IntArray {
        return SpecialProcedure.bounds(room, effect, special, available)
    }

    override fun specialInstruction(special: Selector.Special?): String? {
        return SpecialProcedure.instruction(room, effect, special)
    }

    override fun specialCheck(special: Selector.Special?, picks: List<String?>?): String? {
        return SpecialProcedure.checkMaterials(room, effect, special, picks)
    }

    override fun summonSpecial(rule: SpecialRule?) {
        SpecialProcedure.summon(room, effect, rule)
    }

    override fun player(): PlayerState? {
        return room.getPlayer(effect.controllerId)
    }

    override fun opponent(): PlayerState? {
        return room.getPlayer(room.getOpponentId(effect.controllerId))
    }

    override fun isSelf(card: CardSlot?): Boolean {
        return card!!.instanceId == effect.sourceInstanceId
    }

    override fun isOpponents(card: CardSlot?): Boolean {
        val opponent = opponent()
        return opponent != null && EffectEngine.find(opponent.field, card!!.instanceId) != null
    }

    override fun isCreature(card: CardSlot?): Boolean {
        return "creature" == card!!.cardType
    }

    override fun attached(zone: String?): List<CardSlot> {
        val host = if (zone!!.endsWith(":subject")) StepHelpers.subject(room, effect) else EffectEngine.find(player()!!.field, effect.sourceInstanceId)
        if (host == null) return frozenListOf()
        return if (zone.startsWith("materials")) host.materials!! else host.resources!!
    }

    override fun describeZones(zones: String?): String? {
        val names: MutableList<String> = ArrayList()
        for (raw in JavaText.split(Platform.regex(","), zones!!)) {
            val zone = raw.trim { it <= ' ' }
            if (zone.startsWith("materials")) names.add("los materiales")
            else if (zone.startsWith("resources")) names.add("los recursos")
            else names.add(if (Zone.HAND.`is`(zone) || Zone.DECK.`is`(zone)) "tu " + EffectEngine.zoneName(zone) else "tus " + EffectEngine.zoneName(zone))
        }
        return names.joinToString(" o ")
    }

    override fun alreadyIn(card: CardSlot?, to: String?): Boolean {
        val origin = origin(card!!) ?: return false
        if (Zone.FIELD.`is`(origin.zone)) {
            if (Action.Move.FRONT == to) return EffectEngine.inFrontLine(card)
            if (Action.Move.BACK == to) return !EffectEngine.inFrontLine(card)
        }
        return !origin.looked && origin.zone == destination(to)
    }

    /** Where a card is now, and the card it is attached to, if any. */
    @JvmRecord
    private data class Origin(val owner: PlayerState?, val zone: String?, val host: CardSlot?, val looked: Boolean)

    private fun origin(card: CardSlot): Origin? {
        if (EffectEngine.find(effect.looked, card.instanceId) != null) return Origin(player(), "deck", null, true)
        for (id in frozenListOf(effect.controllerId, if (room.getOpponentId(effect.controllerId) == null) "" else room.getOpponentId(effect.controllerId))) {
            val p = room.getPlayer(id) ?: continue
            val at = EffectEngine.locate(p, card.instanceId)
            if (at != null) return Origin(p, at.zone, null, false)
            for (host in p.field) {
                if (EffectEngine.find(host.materials, card.instanceId) != null) return Origin(p, "materials", host, false)
                if (EffectEngine.find(host.resources, card.instanceId) != null) return Origin(p, "resources", host, false)
            }
        }
        return null
    }

    override fun move(cards: List<CardSlot>?, to: String?, exhausted: Boolean, faceDown: Boolean): List<CardSlot> {
        val zone = destination(to)
        val done: MutableList<CardSlot> = ArrayList()
        var from: String? = null
        for (card in cards!!) {
            val origin = origin(card) ?: continue
            val host = hostIn(to)
            if (isAttachment(to) && (host == null || host === card)) continue
            from = origin.zone
            val enemy = origin.owner!!.userId != effect.controllerId
            if (enemy && Zone.FIELD.`is`(from) && EffectEngine.keywords(card).contains(EffectEngine.UNREMOVABLE)) {
                EffectEngine.log(room, EffectEngine.name(card) + " no puede ser removida por efectos oponentes", null)
                continue
            }
            // Moving within the field is not leaving it
            if (Zone.FIELD.`is`(from) && (Action.Move.FRONT == to || Action.Move.BACK == to)) {
                card.y = if (Action.Move.FRONT == to) 25.0 else 75.0
                EffectEngine.record(room, GameEvent.MOVE, player()!!.userId, card, null, to, effect, frozenMapOf<String, Any?>("reason", "effect"), null)
                done.add(card)
                continue
            }
            if (origin.looked) effect.looked.remove(card)
            else if (origin.host != null) (if ("materials" == from) origin.host.materials!! else origin.host.resources!!).remove(card)
            else if (Zone.FIELD.`is`(from)) Removals.removeFromField(origin.owner, card)
            else EffectEngine.zone(origin.owner, from).remove(card)
            card.faceDown = Zone.DECK.`is`(zone) || faceDown
            val data: MutableMap<String, Any?> = Platform.hashMap()
            data["byCard"] = if (origin.host != null) origin.host.instanceId else effect.sourceInstanceId
            data["ownerId"] = origin.owner.userId
            if (Zone.DECK.`is`(zone)) data["position"] = if (Action.Move.DECK_TOP == to) "top" else "bottom"
            var arrives = zone
            if (isAttachment(to)) {
                (if (to!!.startsWith("materials:")) host!!.materials!! else host!!.resources!!).add(card)
                data["to"] = host.instanceId
                arrives = if (to.startsWith("materials:")) "materials" else "resources"
            } else if (Action.Move.FRONT == to || Action.Move.BACK == to) {
                card.tapped = exhausted
                card.x = 50.0
                card.y = if (Action.Move.FRONT == to) 25.0 else 75.0
                origin.owner.field.add(card)
                effect.summonedId = card.instanceId
                arrives = "field"
            } else {
                // A card always goes to its owner's zones
                val into = EffectEngine.zone(origin.owner, zone)
                if (Action.Move.DECK_TOP == to || Action.Move.LIFE_TOP == to) into.add(0, card)
                else into.add(card)
            }
            if (Zone.LIFE_STACK.`is`(from)) data["defenderId"] = origin.owner.userId
            EffectEngine.record(room, eventOf(arrives, from), player()!!.userId, card, from, arrives, effect, data, null)
            done.add(card)
            if (Zone.LIFE_STACK.`is`(from) && origin.owner.lifeStack.isEmpty()) EffectEngine.loseByLives(room, origin.owner)
        }
        if (done.isEmpty()) return done
        // A card of the hand or the deck stays hidden when it goes to the deck
        val hidden = faceDown || (Zone.DECK.`is`(zone) && (Zone.HAND.`is`(from) || Zone.DECK.`is`(from)))
        val origin = if (Zone.DECK.`is`(from) && !effect.looked.isEmpty()) "" else if (Zone.DECK.`is`(from)) " del tope de su mazo" else ""
        EffectEngine.log(room, player()!!.username + " " + verb(to) + " " + done.size + " " + (if (done.size == 1) "carta" else "cartas") + origin
                + " (" + effect.sourceName + ")" + (if (hidden) "" else EffectEngine.names(done)), room.eventSeq)
        return done
    }

    /** The card that receives the move: "materials:subject". */
    private fun hostIn(to: String?): CardSlot? {
        if (!isAttachment(to)) return null
        val who = to!!.substring(to.indexOf(':') + 1)
        return when (who) {
            "self" -> EffectEngine.find(player()!!.field, effect.sourceInstanceId)
            "subject" -> if (effect.subjectId == null) null else EffectEngine.find(player()!!.field, effect.subjectId)
            "last" -> if (effect.lastTributed.isEmpty()) null else EffectEngine.find(player()!!.field, effect.lastTributed[0])
            "event" -> eventCardOnField()
            else -> null
        }
    }

    override fun looked(): List<CardSlot> {
        return effect.looked
    }

    override fun lookedRestTo(to: String?, order: List<String?>?) {
        val rest = StepHelpers.inOrder(effect.looked, order)
        rest.forEach { c -> c.faceDown = true }
        if (Action.Move.DECK_TOP == to) player()!!.deck.addAll(0, rest)
        else player()!!.deck.addAll(rest)
        effect.looked.clear()
        if (!rest.isEmpty()) {
            EffectEngine.log(room, player()!!.username + " " + verb(to) + " " + rest.size + " " + (if (rest.size == 1) "carta" else "cartas"), null)
        }
    }

    override fun eventCard(): CardSlot? {
        val trigger = EffectEngine.triggerEvent(room, effect)
        val at = if (trigger == null) null else EffectEngine.locate(player(), trigger.cardInstanceId)
        return if (at == null || Zone.FIELD.`is`(at.zone)) null else at.card
    }

    override fun unaffected(card: CardSlot?): Boolean {
        return EffectEngine.unaffected(card, effect)
    }

    override fun asksOrder(toTop: Boolean) {
        val prompt = room.prompt
        prompt!!.kind = Prompt.Kind.TAKE_REVEALED_REST_BOTTOM
        prompt.reorderRest = true
        prompt.restToTop = toTop
    }

    override fun moved(cards: List<CardSlot>?) {
        effect.lastCount = cards!!.size
        effect.lastTributed = ArrayList(EffectEngine.ids(cards))
        done(!cards.isEmpty())
    }

    override fun draw(count: Int, bothPlayers: Boolean) {
        for (p in if (bothPlayers) frozenListCopy(room.players.values) else frozenListOf(player())) {
            val n = min(count, p!!.deck.size)
            for (i in 0 until n) Summons.drawOne(room, p, effect, false)
            if (!bothPlayers) done(n > 0)
            if (n > 0 || count > 0) EffectEngine.log(room, p.username + " roba " + n + " " + (if (n == 1) "carta" else "cartas") + " (" + effect.sourceName + ")"
                    + (if (n < count) ": su mazo no tenía más" else ""), room.eventSeq)
        }
    }

    override fun gainGems(count: Int) {
        val userId = player()!!.userId
        room.sharedCounter = EffectEngine.clamp(room.sharedCounter + count * EffectEngine.direction(room, userId))
        EffectEngine.record(room, GameEvent.GAIN_GEMS, userId, null, null, null, effect, frozenMapOf<String, Any?>("count", count),
            player()!!.username + " gana " + EffectEngine.gemsText(count) + " (" + effect.sourceName + ")")
    }

    override fun damage(amount: Int) {
        StepHelpers.damageByEffect(room, effect, player(), amount)
    }

    override fun look(count: Int) {
        val n = min(count, player()!!.deck.size)
        for (i in 0 until n) effect.looked.add(player()!!.deck.removeAt(0))
        EffectEngine.record(room, GameEvent.LOOK_TOP, player()!!.userId, null, "deck", null, effect, frozenMapOf<String, Any?>("count", n),
            player()!!.username + " mira las primeras " + n + " cartas de su mazo (" + effect.sourceName + ")")
    }

    private fun ownerOnField(card: CardSlot): PlayerState? {
        for (p in room.players.values) if (EffectEngine.find(p.field, card.instanceId) != null) return p
        return null
    }

    override fun onField(card: CardSlot?): Boolean {
        return ownerOnField(card!!) != null
    }

    override fun canTap(card: CardSlot?): Boolean {
        return !card!!.tapped && !EffectEngine.keywords(card).contains(EffectEngine.CANNOT_TAP)
    }

    override fun canUntap(card: CardSlot?): Boolean {
        return card!!.tapped && card.noUntap != 1
    }

    override fun setTapped(card: CardSlot?, tapped: Boolean) {
        val owner = ownerOnField(card!!)
        card.tapped = tapped
        EffectEngine.record(room, if (tapped) GameEvent.TAP else GameEvent.UNTAP, player()!!.userId, card, null, null, effect, frozenMapOf<String, Any?>("ownerId", owner!!.userId),
            player()!!.username + (if (tapped) " agota a " else " reactiva a ") + EffectEngine.name(card) + " (" + effect.sourceName + ")")
    }

    override fun strength(card: CardSlot?): Int {
        return max(0, EffectEngine.strength(card))
    }

    override fun changeStrength(card: CardSlot?, amount: Int, until: Action.Until?) {
        if (amount == 0) return
        val owner = ownerOnField(card!!)
        if (amount < 0) {
            reduce(card, owner, -amount, until)
            return
        }
        val text = untilText(until)
        when (until!!) {
            Action.Until.NEXT_TURN -> card.laterStrength = card.laterStrength + amount
            Action.Until.ATTACK -> {
                card.turnStrength = card.turnStrength + amount
                if (room.combat != null) room.combat!!.untilEndOfAttack.add(card.instanceId + "|" + EffectEngine.STRENGTH_ENTRY + amount)
            }
            Action.Until.NEXT_ATTACK -> {
                card.turnStrength = card.turnStrength + amount
                card.untilNextAttack!!.add(EffectEngine.STRENGTH_ENTRY + amount)
            }
            Action.Until.TURN, Action.Until.AS_EVENT -> card.turnStrength = card.turnStrength + amount
        }
        EffectEngine.floating(card, effect, "Gana fuerza " + amount + text, floatingUntil(until), amount, null)
        EffectEngine.record(room, GameEvent.GAIN, effect.controllerId, card, null, null, effect, frozenMapOf<String, Any?>("strength", amount),
            EffectEngine.name(card) + " gana fuerza " + amount + text)
    }

    private fun reduce(card: CardSlot, owner: PlayerState?, amount: Int, until: Action.Until?) {
        if (until == Action.Until.AS_EVENT) {
            val trigger = EffectEngine.triggerEvent(room, effect)
            val later = trigger != null && trigger.data != null && trigger.data!!["untilNextTurn"] == true
            StepHelpers.reduceStrength(room, effect, owner, card, amount, later)
            return
        }
        if (until != Action.Until.ATTACK) {
            StepHelpers.reduceStrength(room, effect, owner, card, amount, until == Action.Until.NEXT_TURN)
            return
        }
        card.turnStrength = card.turnStrength - amount
        EffectEngine.floating(card, effect, "Pierde " + amount + " de fuerza hasta el final del ataque", "attack", -amount, null)
        if (room.combat != null) room.combat!!.untilEndOfAttack.add(card.instanceId + "|" + EffectEngine.STRENGTH_ENTRY + (-amount))
        val now = strength(card)
        EffectEngine.record(room, GameEvent.STRENGTH_REDUCED, effect.controllerId, card, null, null, effect,
            frozenMapOf<String, Any?>("amount", amount, "to", now, "ownerId", owner!!.userId, "untilNextTurn", false),
            EffectEngine.name(card) + " pierde " + amount + " de fuerza hasta el final del ataque (" + effect.sourceName + "): queda en " + now)
        if (now <= 0) Removals.destroy(room, card, owner, false, EffectEngine.CAUSE_EFFECT, effect.controllerId, null, effect)
    }

    override fun grant(card: CardSlot?, keyword: String?, until: Action.Until?) {
        when (until!!) {
            Action.Until.NEXT_TURN -> card!!.laterKeywords!!.add(keyword!!)
            Action.Until.ATTACK -> {
                card!!.turnKeywords!!.add(keyword!!)
                if (room.combat != null) room.combat!!.untilEndOfAttack.add(card.instanceId + "|" + keyword)
            }
            Action.Until.NEXT_ATTACK -> {
                card!!.turnKeywords!!.add(keyword!!)
                card.untilNextAttack!!.add(keyword)
            }
            Action.Until.TURN, Action.Until.AS_EVENT -> card!!.turnKeywords!!.add(keyword!!)
        }
        // A restriction already says what happens ("No puede agotarse"); an ability is gained
        val what = (if (keyword.startsWith("No puede")) keyword else "Gana " + keyword) + untilText(until)
        EffectEngine.floating(card, effect, what, floatingUntil(until), 0, keyword)
        EffectEngine.record(room, GameEvent.GAIN, effect.controllerId, card, null, null, effect, frozenMapOf<String, Any?>("keyword", keyword), EffectEngine.name(card) + ": " + what)
    }

    override fun destroy(card: CardSlot?, sacrifice: Boolean) {
        val owner = ownerOnField(card!!) ?: return
        Removals.destroy(room, card, owner, sacrifice, EffectEngine.CAUSE_EFFECT, effect.controllerId, if (sacrifice) effect.sourceInstanceId else null, effect)
    }

    override fun subjectOrSelf(): CardSlot? {
        return StepHelpers.subject(room, effect)
    }

    override fun eventCardOnField(): CardSlot? {
        val trigger = EffectEngine.triggerEvent(room, effect)
        return if (trigger == null) null else find(trigger.cardInstanceId)
    }

    override fun relativeTo(to: String?): CardSlot? {
        return when (to!!) {
            "self" -> find(effect.sourceInstanceId)
            "subject" -> if (effect.subjectId == null) null else find(effect.subjectId)
            "last" -> if (effect.lastTributed.isEmpty()) null else find(effect.lastTributed[0])
            "event" -> eventCardOnField()
            else -> null
        }
    }

    override fun levelOf(card: CardSlot?): Int {
        return if (card!!.getLevel() == null) 0 else card.getLevel()!!
    }

    override fun clashing(): CardSlot? {
        return StepHelpers.clashOpponent(room, effect)
    }

    override fun selfId(): String? {
        return effect.sourceInstanceId
    }

    override fun subjectOrSummoned(): CardSlot? {
        val ally = StepHelpers.subject(room, effect)
        val replaced = ally == null || (effect.subjectId != null && EffectEngine.find(player()!!.field, effect.subjectId) == null)
        return if (replaced && effect.summonedId != null) EffectEngine.find(player()!!.field, effect.summonedId) else ally
    }

    override fun canPay(cost: Int): Boolean {
        return EffectEngine.canPay(room, effect.controllerId, cost)
    }

    override fun canBePlayed(card: CardSlot?): Boolean {
        return Chain.cannotActivateOnPlay(room, effect.controllerId, card!!) == null
    }

    override fun delayed(): Boolean {
        return effect.scriptId != null && EffectEngine.script(effect).moment == EffectScript.Moment.DELAYED
    }

    override fun note(message: String?) {
        EffectEngine.log(room, message + " (" + effect.sourceName + ")", null)
    }

    /** Out of the card it is attached to, or of its zone: its zone name for the event. */
    private fun detach(card: CardSlot): String? {
        val origin = origin(card) ?: return null
        if (origin.host == null && !origin.looked) return origin.zone
        (if (origin.host != null) (if ("materials" == origin.zone) origin.host.materials!! else origin.host.resources!!) else effect.looked).remove(card)
        player()!!.hand.add(card)
        return "hand"
    }

    override fun summon(card: CardSlot?, reduce: Int, toFront: Boolean) {
        val from = detach(card!!) ?: return
        Summons.summonByEffect(room, effect, player(), card, from, reduce, toFront)
        effect.summonedId = card.instanceId
    }

    override fun play(card: CardSlot?, reduce: Int) {
        val from = detach(card!!) ?: return
        val cost = max(0, (if (card.cost == null) 0 else card.cost!!) - reduce)
        EffectEngine.pay(room, effect.controllerId, cost)
        if (!Zone.HAND.`is`(from)) {
            EffectEngine.zone(player(), from).remove(card)
            player()!!.hand.add(card)
        }
        Summons.play(room, player(), card, cost, null, null, effect)
    }

    override fun counted(count: Int) {
        effect.lastCount = count
    }

    override fun done(done: Boolean) {
        // "Si lo haces" reads what the resolution did, not the cost paid before it
        if (effect.scriptId == null || !Chain.paying(effect)) effect.lastDone = done
    }

    override fun requireInChoice(card: CardSlot?) {
        room.prompt!!.requiredIds = frozenListOf(card!!.instanceId!!)
    }

    override fun immune(card: CardSlot?): Boolean {
        return EffectEngine.immuneTo(card, StepHelpers.sourceLevel(room, effect))
    }

    override fun inRetaguardia(): Boolean {
        return StepHelpers.inRetaguardia(room, effect)
    }

    override fun inFrontLine(card: CardSlot?): Boolean {
        return EffectEngine.inFrontLine(card)
    }

    override fun zone(player: PlayerState?, zone: String?): List<CardSlot> {
        return EffectEngine.zone(player, zone)
    }

    override fun amount(amount: Amount?): Int {
        if (amount is Amount.LastCount) return effect.lastCount
        // They may be built on what only the effect knows
        if (amount is Amount.Times) return this.amount(amount.base) * amount.factor
        if (amount is Amount.Scaled) return this.amount(amount.base) / amount.divisor + amount.plus
        if (amount is Amount.TriggerSourceLevel) return SpecialRequirements.triggerSourceLevel(room, effect)
        if (amount is Amount.SubjectLevel) {
            val subject = StepHelpers.subject(room, effect)
            return if (subject == null || subject.getLevel() == null) 0 else subject.getLevel()!!
        }
        if (amount is Amount.UsedToSummon) {
            val trigger = EffectEngine.triggerEvent(room, effect)
            val used = if (trigger != null && trigger.data != null) trigger.data!!["used"] else null
            return if (used is Number) used.toInt() else 0
        }
        if (amount is Amount.SourceStrength) {
            var self = StepHelpers.subject(room, effect)
            if (self == null && effect.subjectId == null) self = EffectEngine.findAnywhere(room, effect.controllerId, effect.sourceInstanceId)
            return if (self == null) 0 else EffectEngine.strength(self)
        }
        return amount!!.of(room, effect.controllerId)
    }

    override fun describe(selector: Selector?): String? {
        val who = if (selector!!.owner == Selector.Owner.OPPONENT) "enemigo"
        else if (selector.zones!!.lowercase().contains("allies") || selector.zones.contains("ttackers")) "aliado" else "carta"
        val filter = selector.filter().describe()
        return if (filter.all { JavaText.isWhitespace(it) }) who else "$who $filter"
    }

    override fun name(card: CardSlot?): String? {
        return EffectEngine.name(card)
    }

    override fun find(instanceId: String?): CardSlot? {
        val mine = EffectEngine.findAnywhere(room, effect.controllerId, instanceId)
        return mine ?: EffectEngine.findAnywhere(room, room.getOpponentId(effect.controllerId), instanceId)
    }

    override fun ask(instruction: String?, chosenLabel: String?, shown: List<CardSlot>?, valid: List<CardSlot>?, min: Int, max: Int) {
        StepHelpers.pick(room, effect, instruction, chosenLabel, shown, EffectEngine.ids(valid), max)
        room.prompt!!.min = min
    }

    override fun saved(name: String?): List<String?> {
        return when (name!!) {
            Memory.TARGETS -> effect.targetIds
            Memory.LAST -> effect.lastTributed
            Memory.SUBJECT -> if (effect.subjectId == null) frozenListOf() else frozenListOf(effect.subjectId)
            Memory.SUMMONED -> if (effect.summonedId == null) frozenListOf() else frozenListOf(effect.summonedId)
            else -> {
                val saved: Map<String, List<String?>> = effect.saved
                saved.valueOr(name, frozenListOf())
            }
        }
    }

    override fun save(name: String?, instanceIds: List<String?>?) {
        when (name!!) {
            SPECIAL_CARD -> {
                if (!instanceIds!!.isEmpty()) effect.ritualTargetId = instanceIds[0]
            }
            SPECIAL_SUMMONER -> {
                if (!instanceIds!!.isEmpty()) effect.ritualSummonerId = instanceIds[0]
            }
            // Several choices of one effect add up ("elige hasta 1 enemigo nivel 2 y 1 enemigo nivel 1")
            Memory.TARGETS -> effect.targetIds.addAll(instanceIds!!)
            Memory.LAST -> effect.lastTributed = ArrayList(instanceIds!!)
            Memory.SUBJECT -> {
                if (!instanceIds!!.isEmpty()) effect.subjectId = instanceIds[0]
            }
            else -> effect.saved.getOrPut(name) { ArrayList() }.addAll(instanceIds!!)
        }
    }

    override fun event(type: String?, card: CardSlot?, data: Map<String, @JvmSuppressWildcards Any?>?, message: String?) {
        EffectEngine.record(room, type, effect.controllerId, card, null, null, effect, data as MutableMap<String, Any?>?, message + " (" + effect.sourceName + ")")
    }
}
