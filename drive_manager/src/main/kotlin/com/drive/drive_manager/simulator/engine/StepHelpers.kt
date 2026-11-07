package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.engine.EffectEngine.CAUSE_EFFECT
import com.drive.drive_manager.simulator.engine.EffectEngine.NO_ATTACK
import com.drive.drive_manager.simulator.engine.EffectEngine.PENDING_EFFECT_DAMAGE
import com.drive.drive_manager.simulator.engine.EffectEngine.allies
import com.drive.drive_manager.simulator.engine.EffectEngine.find
import com.drive.drive_manager.simulator.engine.EffectEngine.findAnywhere
import com.drive.drive_manager.simulator.engine.EffectEngine.findInRoom
import com.drive.drive_manager.simulator.engine.EffectEngine.floating
import com.drive.drive_manager.simulator.engine.EffectEngine.frontLine
import com.drive.drive_manager.simulator.engine.EffectEngine.frontLineCard
import com.drive.drive_manager.simulator.engine.EffectEngine.hasLevel
import com.drive.drive_manager.simulator.engine.EffectEngine.ids
import com.drive.drive_manager.simulator.engine.EffectEngine.immuneTo
import com.drive.drive_manager.simulator.engine.EffectEngine.inFrontLine
import com.drive.drive_manager.simulator.engine.EffectEngine.keywords
import com.drive.drive_manager.simulator.engine.EffectEngine.locate
import com.drive.drive_manager.simulator.engine.EffectEngine.log
import com.drive.drive_manager.simulator.engine.EffectEngine.loseByLives
import com.drive.drive_manager.simulator.engine.EffectEngine.name
import com.drive.drive_manager.simulator.engine.EffectEngine.reactivable
import com.drive.drive_manager.simulator.engine.EffectEngine.record
import com.drive.drive_manager.simulator.engine.EffectEngine.script
import com.drive.drive_manager.simulator.engine.EffectEngine.strength
import com.drive.drive_manager.simulator.engine.EffectEngine.zone
import com.drive.drive_manager.simulator.engine.EffectEngine.zoneName
import com.drive.drive_manager.simulator.engine.EffectStep.*
import com.drive.drive_manager.simulator.engine.Removals.destroy
import com.drive.drive_manager.simulator.engine.Removals.leaveField
import com.drive.drive_manager.simulator.engine.Rituals.shares
import com.drive.drive_manager.simulator.engine.Rituals.sumsExactly
import com.drive.drive_manager.simulator.engine.SpecialRequirements.SpecialRequirement
import com.drive.drive_manager.simulator.engine.SpecialRequirements.specialTargets
import com.drive.drive_manager.simulator.engine.effects.model.Action
import com.drive.drive_manager.simulator.engine.effects.model.SpecialRule
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState
import com.drive.drive_manager.simulator.room.Zone
import java.util.regex.Pattern

/** What the steps of an effect share. */
object StepHelpers {

    private val COMMA = Pattern.compile(",")

    /** Allies "sacrifica 1 [otro] aliado" may take. */
    @JvmStatic
    fun sacrificable(effect: PendingEffect, player: PlayerState, sacrifice: SacrificeAlly): List<CardSlot> {
        val all = if (sacrifice.toUntapAnother) sacrificeToUntap(player) else allies(player)
        return if (sacrifice.other) all.filter { c -> c.instanceId!! != effect.sourceInstanceId } else all
    }

    @JvmStatic
    fun pick(
        room: GameRoom, effect: PendingEffect, instruction: String?, chosenLabel: String?,
        cards: List<CardSlot>?, validIds: List<String?>, count: Int,
    ) {
        val prompt = prompt(room, effect, Prompt.Kind.PICK, instruction)
        prompt.cards = ArrayList(cards!!)
        prompt.validIds = ArrayList(validIds)
        prompt.min = count
        prompt.max = count
        prompt.chosenLabel = chosenLabel
        room.prompt = prompt
    }

    @JvmStatic
    fun prompt(room: GameRoom, effect: PendingEffect, kind: Prompt.Kind?, instruction: String?): Prompt {
        val p = Prompt()
        p.id = room.nextId("prompt")
        p.kind = kind
        p.playerId = effect.controllerId
        p.effectId = effect.id
        p.sourceName = effect.sourceName
        val source = findAnywhere(room, effect.controllerId, effect.sourceInstanceId)
        p.sourceImageUrl = source?.imageUrl
        p.title = (if (effect.link > 0) "Cadena " + effect.link + " · " else "") + effect.sourceName + " · " + effect.label
        p.effectText = effect.text
        p.instruction = instruction
        return p
    }

    /**
     * Enemies the effect may choose: the opponent's Línea frontal (rule 4.0.6.2), matching the filter. A creature
     * in the Retaguardia cannot affect enemies with its effects.
     */
    @JvmStatic
    fun enemyTargets(room: GameRoom, effect: PendingEffect, filter: CardFilter?): List<CardSlot> {
        if (inRetaguardia(room, effect)) return java.util.List.of()
        val opponent = room.getPlayer(room.getOpponentId(effect.controllerId)) ?: return java.util.List.of()
        val level = sourceLevel(room, effect)
        val front = frontLine(opponent.field)
        // Java's method reference (filter::matches) read the filter at once
        val f = filter!!
        return front.filter { c -> "creature" == c.cardType && f.matches(c) && !immuneTo(c, level) }
    }

    /**
     * Reduces an enemy's strength by an effect, until the end of this turn or of the next one; at 0 it is
     * destroyed by that effect (rule 12.0.0.2). Records it: "si reduces la fuerza …" effects trigger from it.
     */
    @JvmStatic
    fun reduceStrength(room: GameRoom, effect: PendingEffect, owner: PlayerState?, target: CardSlot, amount: Int, untilNextTurn: Boolean) {
        if (untilNextTurn) target.laterStrength = target.laterStrength - amount
        else target.turnStrength = target.turnStrength - amount
        floating(target, effect, "Pierde " + amount + " de fuerza hasta el final del " + (if (untilNextTurn) "próximo turno" else "turno"),
                if (untilNextTurn) "nextTurn" else "turn", -amount, null)
        val now = Math.max(0, strength(target))
        record(room, GameEvent.STRENGTH_REDUCED, effect.controllerId, target, null, null, effect,
                java.util.Map.of<String, Any?>("amount", amount, "to", now, "ownerId", owner!!.userId, "untilNextTurn", untilNextTurn),
                name(target) + " pierde " + amount + " de fuerza (" + effect.sourceName + "): queda en " + now)
        if (now <= 0) destroy(room, target, owner, false, CAUSE_EFFECT, effect.controllerId, null, effect)
    }

    /** Direct damage by an effect to the opponent's top life: a creature with that strength or less is tributed, the rest go to the hand. */
    @JvmStatic
    fun damageByEffect(room: GameRoom, effect: PendingEffect, player: PlayerState?, damage: Int) {
        damageByEffect(room, effect, player, damage, true)
    }

    /** `offer`: whether "en su lugar" of the damage (Estoque) is offered to the defender first; the damage then waits for the answer. */
    @JvmStatic
    fun damageByEffect(room: GameRoom, effect: PendingEffect, player: PlayerState?, damage: Int, offer: Boolean) {
        val defender = room.getPlayer(room.getOpponentId(player!!.userId))
        if (defender == null || defender.lifeStack.isEmpty()) return
        if (offer && !CombatRules.damageTributeCards(defender).isEmpty()) {
            room.usedThisTurn.add(PENDING_EFFECT_DAMAGE + effect.id + "|" + damage + "|" + player.userId)
            CombatRules.offerDamageTribute(room, defender, damage, "effect")
            return
        }
        val life = defender.lifeStack.removeAt(0)
        life.faceDown = false
        val tribute = "creature" == life.cardType && (life.strength == null || CardEffects.lifeStrength(room, defender.userId, life) <= damage)
        (if (tribute) defender.tributeZone else defender.hand).add(life)
        record(room, GameEvent.LIFE_DAMAGE, player.userId, life, "lifeStack", if (tribute) "tributeZone" else "hand", effect,
                java.util.Map.of<String, Any?>("damage", damage, "attackerId", effect.sourceInstanceId, "defenderId", defender.userId),
                effect.sourceName + " inflige " + damage + " de daño directo: " + defender.username + " pierde la vida "
                        + name(life) + (if (tribute) ", que va a sus tributos" else ", que va a su mano"))
        if (defender.lifeStack.isEmpty()) loseByLives(room, defender)
    }

    /** Allies "elige 1 aliado" may choose; for an attack, only those in the Línea frontal (rule 10.1.1.1). */
    /** The rule of a special summon whose summoner is the ally chosen before; null for any other step. */
    @JvmStatic
    fun subjectSummon(step: EffectStep?): SpecialRule? {
        if (step !is Do || step.action !is Action.Sequence) return null
        for (a in step.action.actions!!) {
            if (a is Action.SummonSpecial && "subject" == a.rule!!.summoner) return a.rule
        }
        return null
    }

    @JvmStatic
    fun pickable(room: GameRoom, effect: PendingEffect, player: PlayerState?, attacker: Boolean): List<CardSlot> {
        val steps: MutableList<EffectStep> = ArrayList(script(effect).cost!!)
        steps.addAll(script(effect).steps!!)
        val valid = allies(player).filter { c ->
            (!attacker || inFrontLine(c))
                    // "añade esta carta como material a un aliado": another one
                    && (steps.none { s -> s is Do && s.action is Action.Move && "materials:subject" == s.action.to } || c.instanceId!! != effect.sourceInstanceId)
                    // "elige 1 aliado: invoca 1 criatura del mismo nivel": one with something to summon
                    && steps.none { s -> s is SummonLikeSubject && likeSubject(player, s, c).isEmpty() }
        }
        // "a un aliado para que invoque por …": only an ally that meets the Requerimiento of something it could summon
        val resolution = script(effect).steps!!
        // Its rule, as it reads now, in the sequence that summons
        val summonStep = resolution.firstOrNull { s -> subjectSummon(s) != null }
        val special = if (summonStep == null) null else SpecialProcedure.resolve(room, effect, subjectSummon(summonStep))
        // Not when that summon is conditional ("Si lo haces: dicho aliado puede invocar …")
        if (special == null || conditional(resolution, resolution.indexOf(summonStep))) return valid
        val chosen = effect.subjectId
        val result = valid.filter { ally ->
            effect.subjectId = ally.instanceId
            !specialTargets(room, effect, special).isEmpty()
        }
        effect.subjectId = chosen
        return result
    }

    /** The effect's card is a creature in its owner's Retaguardia. */
    @JvmStatic
    fun inRetaguardia(room: GameRoom, effect: PendingEffect): Boolean {
        val player = room.getPlayer(effect.controllerId)
        val source = if (player == null) null else find(player.field, effect.sourceInstanceId)
        return source != null && "creature" == source.cardType && !inFrontLine(source)
    }

    /** A creature on the field leaves it (not destroyed): its Recursos and Materiales go to the Descarte and it loses its state. */

    /** The materials of your creatures on the field. */
    @JvmStatic
    fun allyMaterialsWhere(player: PlayerState, t: TributeAllyMaterialWhere): List<CardSlot> {
        // Java's method references (t.ally()::matches, t.material()::matches) read both filters at once
        val ally = t.ally!!
        val material = t.material!!
        return allies(player).filter { ally.matches(it) }.flatMap { a -> a.materials!! }.filter { material.matches(it) }
    }

    @JvmStatic
    fun allyMaterials(player: PlayerState): List<CardSlot> {
        return allies(player).flatMap { a -> a.materials!! }
    }

    /** Cards "añade 1 … como recurso de esta carta" may take: from those zones, matching, not this card. */
    /** Allies that may be sacrificed "para reactivar otro": another ally that can be reactivated must remain. */
    @JvmStatic
    fun sacrificeToUntap(player: PlayerState): List<CardSlot> {
        val allies = allies(player)
        return allies.filter { s -> allies.any { r -> r !== s && reactivable(r) } }
    }

    /** The card the steps act on: the ally chosen by "elige 1 aliado", or the effect's own card. */
    @JvmStatic
    fun subject(room: GameRoom, effect: PendingEffect): CardSlot? {
        val player = room.getPlayer(effect.controllerId) ?: return null
        return find(player.field, if (effect.subjectId != null) effect.subjectId else effect.sourceInstanceId)
    }

    /**
     * The targets chosen on activation that are still valid: still in the opponent's Línea frontal (rule 11.10.2). Those
     * that are not are logged and skipped.
     */
    @JvmStatic
    fun validTargets(room: GameRoom, effect: PendingEffect, opponent: PlayerState?): List<CardSlot> {
        val valid: MutableList<CardSlot> = ArrayList()
        for (id in effect.targetIds) {
            val target = if (opponent == null) null else frontLineCard(opponent, id)
            if (target != null) valid.add(target)
            else log(room, "El objetivo de " + effect.sourceName + " ya no es válido", null)
        }
        return valid
    }

    /** Asks which of the looked cards go to the hand (up to `max` of `valid`). */
    @JvmStatic
    fun takeFromLooked(room: GameRoom, effect: PendingEffect, kind: Prompt.Kind?, instruction: String?, valid: List<String?>, max: Int) {
        val prompt = prompt(room, effect, kind, instruction)
        prompt.cards = ArrayList(effect.looked)
        prompt.validIds = ArrayList(valid)
        prompt.min = 0
        prompt.max = Math.min(max, valid.size)
        prompt.chosenLabel = "A tu mano"
        room.prompt = prompt
    }

    /** The chosen looked cards go to the hand. */
    @JvmStatic
    fun lookedToHand(room: GameRoom, effect: PendingEffect, player: PlayerState, picks: List<String>) {
        for (id in picks) {
            val card = find(effect.looked, id)
            // Java removed the null (nothing) and threw on it right after
            effect.looked.remove(card!!)
            card.faceDown = false
            player.hand.add(card)
            record(room, GameEvent.ADD_TO_HAND, player.userId, card, "deck", "hand", effect, null,
                    player.username + " añade " + name(card) + " a su mano (" + effect.sourceName + ")")
        }
    }

    /** The chosen hand cards go to the Tributos; they are the last tributed, and how many is the last count. */
    @JvmStatic
    fun tributeFromHand(room: GameRoom, effect: PendingEffect, player: PlayerState, picks: List<String>) {
        for (id in picks) {
            val card = find(player.hand, id)
            // Java removed the null (nothing) and threw on it right after
            player.hand.remove(card!!)
            card.faceDown = false
            player.tributeZone.add(card)
            record(room, GameEvent.TRIBUTE, player.userId, card, "hand", "tributeZone", effect, null,
                    player.username + " tributa " + name(card) + " de su mano (" + effect.sourceName + ")")
        }
        effect.lastTributed = ArrayList(picks)
        effect.lastCount = picks.size
    }

    /** This card leaves its zone to become a material (`asMaterial`) or a Recurso of the subject. */
    @JvmStatic
    fun attachSelfToSubject(room: GameRoom, effect: PendingEffect, player: PlayerState, asMaterial: Boolean) {
        val at = locate(player, effect.sourceInstanceId)
        val self = at?.card
        val ally = subject(room, effect)
        if (self == null || ally == null || self === ally) return
        at!!
        if (Zone.FIELD.`is`(at.zone)) {
            player.field.remove(self)
            leaveField(self)
        } else {
            zone(player, at.zone).remove(self)
            self.faceDown = false
        }
        (if (asMaterial) ally.materials else ally.resources)!!.add(self)
        effect.lastDone = true
        record(room, GameEvent.SEND, player.userId, self, at.zone, if (asMaterial) "materials" else "resources", effect,
                java.util.Map.of<String, Any?>("to", ally.instanceId),
                player.username + " añade " + name(self) + " como " + (if (asMaterial) "material" else "recurso") + " de " + name(ally))
    }

    /** Allies with another ally of higher level ("1 aliado como material de otro de nivel mayor"). */
    @JvmStatic
    fun withHigherAlly(player: PlayerState): List<CardSlot> {
        return allies(player).filter { c -> !higherThan(player, c).isEmpty() }
    }

    /** The player's other creatures of a higher level than `card`. */
    @JvmStatic
    fun higherThan(player: PlayerState, card: CardSlot?): List<CardSlot> {
        val level = if (card!!.getLevel() == null) 0 else card.getLevel()!!
        return allies(player).filter { c -> c !== card && c.getLevel() != null && c.getLevel()!! > level }
    }

    /** Whether the step at `index` is covered by an earlier connector ("Si lo haces:", "puedes …", "Si …:"). */
    @JvmStatic
    fun conditional(steps: List<EffectStep>, index: Int): Boolean {
        for (i in 0 until index) {
            val covers = Chain.covers(steps[i])
            if (covers > 0 && index <= i + covers) return true
        }
        return false
    }

    /** The level of the effect's card (0 when it has none), for "efectos de cartas nivel N o menor". */
    @JvmStatic
    fun sourceLevel(room: GameRoom, effect: PendingEffect): Int {
        val source = findAnywhere(room, effect.controllerId, effect.sourceInstanceId)
        return if (source == null || source.getLevel() == null) 0 else source.getLevel()!!
    }

    /** The cards of one or more of the player's zones ("tributeZone,discardPile"). */
    @JvmStatic
    fun zones(player: PlayerState?, csv: String?): List<CardSlot> {
        val all: MutableList<CardSlot> = ArrayList()
        for (z in COMMA.split(csv!!)) all.addAll(zone(player, z.trim { it <= ' ' }))
        return all
    }

    /** Which of those zones holds the card. */
    @JvmStatic
    fun zoneOf(player: PlayerState?, csv: String?, card: CardSlot?): String? {
        for (z in COMMA.split(csv!!)) if (zone(player, z.trim { it <= ' ' }).contains(card)) return z.trim { it <= ' ' }
        return csv
    }

    /** "1 criatura en el campo": yours, or an enemy's in its Línea frontal when this card is in yours (and it is not immune). */
    @JvmStatic
    fun creaturesOnField(room: GameRoom, effect: PendingEffect, filter: CardFilter?): List<CardSlot> {
        val player = room.getPlayer(effect.controllerId) ?: return java.util.List.of()
        val all: MutableList<CardSlot> = ArrayList(player.field
                .filter { c -> "creature" == c.cardType && c.instanceId!! != effect.sourceInstanceId && filter!!.matches(c) })
        all.addAll(enemyTargets(room, effect, filter))
        return all
    }

    /** Creatures of the zone the effect may summon: with a normal cost the controller can pay after the reduction. */
    /** Creatures of the zone of the ally's level and another name (summoned free). */
    @JvmStatic
    fun likeSubject(player: PlayerState?, like: SummonLikeSubject, ally: CardSlot): List<CardSlot> {
        if (ally.getLevel() == null) return java.util.List.of()
        return zone(player, like.zone)
                .filter { c ->
                    "creature" == c.cardType && c.cost != null && like.filter!!.matches(c)
                            && ally.getLevel() == c.getLevel() && !name(c).equals(name(ally), ignoreCase = true)
                }
    }

    /** Creatures that fit within the level sum on their own (summoned free). */
    @JvmStatic
    fun freeLevelSum(room: GameRoom, effect: PendingEffect, sum: SummonFreeLevelSum): List<CardSlot> {
        val player = room.getPlayer(effect.controllerId) ?: return java.util.List.of()
        val cards = zone(player, sum.zone)
        // Java's method reference (sum.filter()::matches) read the filter at once
        val filter = sum.filter!!
        return cards
                .filter { c ->
                    c.instanceId!! != effect.sourceInstanceId
                            && "creature" == c.cardType && c.cost != null && c.getLevel() != null
                            && filter.matches(c)
                            && c.getLevel()!! <= sum.maxLevelSum
                }
    }

    @JvmStatic
    fun allIn(cards: List<CardSlot>, ids: List<String>): Boolean {
        return ids.all { id -> find(cards, id) != null }
    }

    /** `cards` in the order given by `order`; ids missing from it keep their place at the end. */
    /** A card's level as it was on the field when it was last destroyed or sacrificed (its continuous levels included). */
    @JvmStatic
    fun levelWhenRemoved(room: GameRoom, card: CardSlot?): Int {
        if (card == null) return 0
        val timeline = room.timeline
        for (i in timeline.size - 1 downTo 0) {
            val e = timeline[i]
            if (card.instanceId!! != e.cardInstanceId) continue
            if ((GameEvent.DESTROY == e.type || GameEvent.SACRIFICE == e.type) && e.data != null) {
                val level = e.data!!["level"]
                if (level is Int) return level
            }
        }
        return if (card.getLevel() == null) 0 else card.getLevel()!!
    }

    @JvmStatic
    fun inOrder(cards: List<CardSlot>, order: List<String?>?): List<CardSlot> {
        val result: MutableList<CardSlot> = ArrayList()
        if (order != null) {
            for (id in order) {
                val card = find(cards, id)
                if (card != null && !result.contains(card)) result.add(card)
            }
        }
        for (card in cards) if (!result.contains(card)) result.add(card)
        return result
    }

    /** The mark of an effect that goes on at the end of the turn: controller, card, script and subject ([AtEndOfTurn]). */
    const val DELAYED = "delayed|"

    /** A card placed on the field by an effect (not summoned): face up, in the Línea frontal or the Retaguardia. */
    /** Materials of the exhausted allies that match. */
    @JvmStatic
    fun exhaustedAllyMaterials(player: PlayerState, filter: CardFilter?): List<CardSlot> {
        val all = allies(player)
        // Java's method reference (filter::matches) read the filter at once
        val f = filter!!
        return all.filter { it.tapped }.flatMap { a -> a.materials!! }.filter { f.matches(it) }
    }

    @JvmStatic
    fun placeOnField(room: GameRoom, effect: PendingEffect, owner: PlayerState, card: CardSlot, fromZone: String?, toFront: Boolean, exhausted: Boolean) {
        zone(owner, fromZone).remove(card)
        card.faceDown = false
        card.tapped = exhausted
        card.x = 50.0
        card.y = if (toFront) 25.0 else 75.0
        owner.field.add(card)
        record(room, GameEvent.SEND, effect.controllerId, card, fromZone, "field", effect, java.util.Map.of<String, Any?>("ownerId", owner.userId, "frontLine", toFront),
                owner.username + " coloca " + name(card) + " en su " + (if (toFront) "línea frontal" else "retaguardia") + (if (exhausted) ", agotada" else "")
                        + " (" + effect.sourceName + ")")
    }

    /** "gana fuerza N hasta el final del turno": the subject. */
    @JvmStatic
    fun gainStrength(room: GameRoom, effect: PendingEffect, amount: Int) {
        val subject = subject(room, effect)
        if (subject == null || amount == 0) return
        subject.turnStrength = subject.turnStrength + amount
        floating(subject, effect, "Gana fuerza $amount hasta el final del turno", "turn", amount, null)
        record(room, GameEvent.GAIN, effect.controllerId, subject, null, null, effect, java.util.Map.of<String, Any?>("strength", amount),
                name(subject) + " gana fuerza " + amount + " hasta el final del turno")
    }

    /** Allies of the Línea frontal that can declare an attack and match. */
    @JvmStatic
    fun attackers(player: PlayerState?, filter: CardFilter?): List<CardSlot> {
        return allies(player).filter { c -> inFrontLine(c) && filter!!.matches(c) && !keywords(c).contains(NO_ATTACK) }
    }

    /** One different card for each filter (a small matching: every filter is tried with every card). */
    @JvmStatic
    fun assignable(cards: List<CardSlot?>, filters: List<CardFilter>?): Boolean {
        if (filters!!.isEmpty()) return true
        val first = filters[0]
        for (c in cards) {
            if (!first.matches(c!!)) continue
            val rest: MutableList<CardSlot?> = ArrayList(cards)
            rest.remove(c)
            if (assignable(rest, filters.subList(1, filters.size))) return true
        }
        return false
    }

    /** Copies of the effect's card in its owner's Retaguardia (the card included). */
    @JvmStatic
    fun copiesInBack(player: PlayerState, effect: PendingEffect): List<CardSlot> {
        return player.field.filter { c ->
            !inFrontLine(c) && c.cardName != null
                    && c.cardName!!.equals(effect.sourceName, ignoreCase = true)
        }
    }

    /** Cards of the zone that can be a Recurso of an ally of a higher level. */
    @JvmStatic
    fun higherAllyResources(player: PlayerState, a: AddResourceToHigherAlly): List<CardSlot> {
        val cards = zones(player, a.zone)
        // Java's method reference (a.filter()::matches) read the filter at once
        val filter = a.filter!!
        return cards.filter { c -> filter.matches(c) && !higherThan(player, c).isEmpty() }
    }

    /** Some of these enemies add up to exactly `sum` in level. */
    @JvmStatic
    fun levelSumPossible(cards: List<CardSlot>, sum: Int): Boolean {
        val reach = BooleanArray(sum + 1)
        reach[0] = true
        for (c in cards) {
            val level = if (c.getLevel() == null) 0 else c.getLevel()!!
            if (level <= 0) continue
            for (s in sum downTo level) if (reach[s - level]) reach[s] = true
        }
        return reach[sum]
    }

    /** "N criaturas en la línea frontal": yours, and the enemies this effect can affect. */
    @JvmStatic
    fun frontLineCreatures(room: GameRoom, effect: PendingEffect, filter: CardFilter?): List<CardSlot> {
        val player = room.getPlayer(effect.controllerId) ?: return java.util.List.of()
        val all: MutableList<CardSlot> = ArrayList(frontLine(player.field).filter { c -> "creature" == c.cardType && filter!!.matches(c) })
        all.addAll(enemyTargets(room, effect, filter))
        return all
    }

    /** "1 criatura en el campo con fuerza menor a esta carta". */
    @JvmStatic
    fun weakerThanSelf(room: GameRoom, effect: PendingEffect): List<CardSlot> {
        val player = room.getPlayer(effect.controllerId)
        val self = (if (player == null) null else find(player.field, effect.sourceInstanceId)) ?: return java.util.List.of()
        return creaturesOnField(room, effect, CardFilter.creatures().withMaxStrength(strength(self) - 1))
    }

    /** The mark of a creature that goes back to its owner's hand (with its materials) when the attack ends ([ReturnSubjectAtAttackEnd]). */
    const val RETURN_AT_ATTACK_END = "return-at-attack-end|"

    /** The creature the effect's card clashes with in the attack going on (the blocker, or the attacker when it blocks). */
    @JvmStatic
    fun clashOpponent(room: GameRoom, effect: PendingEffect): CardSlot? {
        val c = room.combat ?: return null
        val self = effect.sourceInstanceId
        val other = if (self!! == c.attackerId) c.blockerId else if (self == c.blockerId) c.attackerId else null
        return if (other == null) null else findInRoom(room, other)
    }

    /** The last card with that name in the zone (the one that just went there). */
    @JvmStatic
    fun lastNamed(zone: List<CardSlot>, name: String?): CardSlot? {
        for (i in zone.size - 1 downTo 0) if (name!!.equals(zone[i].cardName, ignoreCase = true)) return zone[i]
        return null
    }

    /** The chosen cards can each go to a different filter ("hasta 1 X y hasta 1 Y"). */
    @JvmStatic
    fun assignableEach(filters: List<CardFilter>?, cards: List<CardSlot?>): Boolean {
        if (cards.isEmpty()) return true
        val first = cards[0]
        for (i in filters!!.indices) {
            if (!filters[i].matches(first!!)) continue
            val rest: MutableList<CardFilter> = ArrayList(filters)
            rest.removeAt(i)
            if (assignableEach(rest, cards.subList(1, cards.size))) return true
        }
        return false
    }

    /** Creatures of those zones that can be the declared summoner of `card`: its level, sharing a type or color, with its materials there. */
    @JvmStatic
    fun declaredFrom(player: PlayerState, card: CardSlot, req: SpecialRequirement, zones: String?): List<CardSlot> {
        return StepHelpers.zones(player, zones)
                .filter { c ->
                    c !== card && "creature" == c.cardType && SpecialRequirements.optionFor(req, c) != null && shares(c, card)
                            && declaredMaterialsEnough(player, card, req, c)
                }
    }

    private fun declaredMaterialsEnough(player: PlayerState, card: CardSlot, req: SpecialRequirement, c: CardSlot): Boolean {
        val materials = declaredMaterials(player, card, req, c)
        return if (req.levelSum > 0) sumsExactly(materials, req.levelSum) else materials.size >= Math.max(1, req.materialCount)
    }

    /** The materials of `card` besides that summoner, in the zones of its Requerimiento ("materiales que sumen 3 en tus tributos"). */
    @JvmStatic
    fun declaredMaterials(player: PlayerState, card: CardSlot, req: SpecialRequirement, summoner: CardSlot?): List<CardSlot> {
        val all: MutableList<CardSlot> = ArrayList()
        for (z in req.materialZones!!) all.addAll(zone(player, z))
        return all.filter { c ->
            c !== card && c !== summoner && "creature" == c.cardType && c.getLevel() != null && shares(c, card)
                    && (req.levelSum > 0 || hasLevel(c, req.materialLevel))
        }
    }

    /** "tus tributos o descarte". */
    @JvmStatic
    fun zonesText(csv: String?): String {
        return java.lang.String.join(" o ", COMMA.split(csv!!).map { z -> zoneName(z.trim { it <= ' ' }) })
    }
}
