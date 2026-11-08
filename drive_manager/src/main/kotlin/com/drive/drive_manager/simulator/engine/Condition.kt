package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.Zone

/**
 * When an effect triggers or applies ("si algún aliado es destruido", "mientras controles 3 o más aliados Rojos"), as data:
 * a closed vocabulary of records, so a card's effects can be stored and read back without code.
 *
 * Trigger conditions read the `event`; state conditions only read the table, and are also checked without one
 * (continuous effects, auras, cost modifiers, amounts), when `event` is null.
 */
sealed interface Condition {

    /**
     * @param event      the event that would trigger it; null when only the table is checked
     * @param source     the card with the effect
     * @param controller who controls it
     */
    fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean

    // ------------------------------------------------------------------ Combinations

    /** Every one of them. */
    @JvmRecord
    data class All(val all: List<@JvmSuppressWildcards Condition>?) : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            return all!!.all { c -> c.test(room, event, source, controller) }
        }
    }

    /** The opposite. */
    @JvmRecord
    data class Not(val condition: Condition?) : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            return !condition!!.test(room, event, source, controller)
        }
    }

    /** No condition: it always applies. */
    data object Always : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            return true
        }
    }

    /** Any one of them. */
    @JvmRecord
    data class Any(val any: List<@JvmSuppressWildcards Condition>?) : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            return any!!.any { c -> c.test(room, event, source, controller) }
        }
    }

    /**
     * "Inmediatamente después de que X": X must be the last thing that happened (rule of the vault "Efectos, cadenas y
     * resolución"); if anything else happened after it in the same resolution, the effect misses its moment.
     */
    @JvmRecord
    data class Immediately(val condition: Condition?) : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            return event != null && condition!!.test(room, event, source, controller) && EffectEngine.lastThingHappened(room, event)
        }
    }

    // ------------------------------------------------------------------ Triggers

    /** Whose: yours, your enemy's or either. */
    enum class Side { YOU, ENEMY, ANY }

    /**
     * "si … es destruido" (sacrifices included: rule 4.0.4.2).
     *
     * @param owner          whose card was destroyed
     * @param by             who destroyed it
     * @param filter         which card; null: not asked
     * @param byCard         "por tus [filtro]": the card whose effect did it; null: not asked
     * @param byThis         "esta carta destruye"
     * @param cause          "effect", "battle" or null
     * @param exceptAttacker "excepto como atacante"
     */
    @JvmRecord
    data class Destroyed(
        val owner: Side?, val by: Side?, val filter: CardFilter?, val byCard: CardFilter?, val byThis: Boolean, val cause: String?,
        val exceptAttacker: Boolean,
    ) : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            if (event == null || !isDestruction(event)) return false
            if (!sideIs(owner, ownerOf(event), controller) || !sideIs(by, event.playerId, controller)) return false
            if ("effect" == cause && !destroyedByEffect(event)) return false
            if ("battle" == cause && !(event.data != null && EffectEngine.CAUSE_BATTLE == event.data!!["cause"])) return false
            if (byThis && !(event.data != null && source!!.instanceId == event.data!!["byCard"])) return false
            if (exceptAttacker && event.data != null && event.data!!["asAttacker"] == true) return false
            if (byCard != null) {
                val id = if (event.data == null) null else event.data!!["byCard"]
                val card = if (id == null) null else EffectEngine.findInRoom(room, id as String)
                if (card == null || !byCard.matches(card)) return false
            }
            if (filter == null) return true
            val destroyed = EffectEngine.eventCard(room, event)
            return destroyed != null && filter.matches(destroyed)
        }
    }

    /**
     * "si … es invocado".
     *
     * @param side   whose summon
     * @param other  other than this card
     * @param filter which creature; null: not asked
     * @param how    "effect", "special" or null: any way
     * @param kind   the special summon's method; null: any
     */
    @JvmRecord
    data class Summoned(val side: Side?, val other: Boolean, val filter: CardFilter?, val how: String?, val kind: String?) : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            if (event == null) return false
            val type = event.type
            val matches = when (how ?: "") {
                "effect" -> GameEvent.EFFECT_SUMMON == type
                "special" -> GameEvent.SPECIAL_SUMMON == type
                else -> GameEvent.NORMAL_SUMMON == type || GameEvent.SPECIAL_SUMMON == type || GameEvent.EFFECT_SUMMON == type
            }
            if (!matches || !sideIs(side, event.playerId, controller)) return false
            if (other && source!!.instanceId == event.cardInstanceId) return false
            if (kind != null && (event.data == null || kind != event.data!!["summonType"])) return false
            if (filter == null) return true
            val summoned = EffectEngine.eventCard(room, event)
            return summoned != null && filter.matches(summoned)
        }
    }

    /**
     * Something happened: the general form of a trigger.
     *
     * @param eventType the event's type; null: any
     * @param actor    who did it
     * @param owner    whose card it was
     * @param thisCard it is about this card
     * @param filter   which card; null: not asked
     * @param from     the zone it left; null: not asked
     * @param to       the zone it reached; null: not asked
     * @param byEffect an effect did it
     * @param data     what the event says, as "key=value"; the value "$this" is this card and "$you" is its controller
     */
    @JvmRecord
    data class Happened(
        val eventType: String?, val actor: Side?, val owner: Side?, val thisCard: Boolean, val filter: CardFilter?, val from: String?,
        val to: String?, val byEffect: Boolean, val data: List<String>?,
    ) : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            if (event == null || (eventType != null && eventType != event.type)) return false
            if (!sideIs(actor, event.playerId, controller) || !sideIs(owner, ownerOf(event), controller)) return false
            if (thisCard && source!!.instanceId != event.cardInstanceId) return false
            if ((from != null && from != event.fromZone) || (to != null && to != event.toZone)) return false
            if (byEffect && event.effectId == null) return false
            for (pair in data!!) {
                val at = pair.indexOf('=')
                val value = if (event.data == null) null else event.data!![pair.substring(0, at)]
                var wanted: String? = pair.substring(at + 1)
                if (wanted == "\$this") wanted = source!!.instanceId
                if (wanted == "\$you") wanted = controller
                if (value == null || wanted!! != value.toString()) return false
            }
            if (filter == null) return true
            val card = EffectEngine.eventCard(room, event)
            return card != null && filter.matches(card)
        }
    }

    /** How a [Compare] compares. */
    enum class Op { AT_LEAST, AT_MOST, LESS, EQUAL }

    /** The table: "controlas 3 o más aliados Rojos", "si tienes 5 o menos vidas", "si tienes menos vidas que tu oponente". */
    @JvmRecord
    data class Compare(val left: Amount?, val op: Op?, val right: Amount?) : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            val a = left!!.of(room, controller)
            val b = right!!.of(room, controller)
            return when (op!!) {
                Op.AT_LEAST -> a >= b
                Op.AT_MOST -> a <= b
                Op.LESS -> a < b
                Op.EQUAL -> a == b
            }
        }
    }

    /** What can be asked of this card as it is now. */
    enum class CardState { IN_FRONT_LINE, ACTIVE, NO_COPIES }

    /** "mientras esté en la línea frontal", "si está activa", "si no controlas copias de esta carta". */
    @JvmRecord
    data class ThisIs(val state: CardState?) : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            return when (state!!) {
                CardState.IN_FRONT_LINE -> EffectEngine.inFrontLine(source)
                CardState.ACTIVE -> !source!!.tapped
                CardState.NO_COPIES -> {
                    val player = room.getPlayer(controller)
                    player != null && player.field
                        .none { c -> c !== source && (CardEffects.isNamed(c, source!!.cardName) || CardEffects.isNamed(source, c.cardName)) }
                }
            }
        }
    }

    /** What can be asked of the game as it is now. */
    enum class GameState { YOUR_TURN, DURING_COMBAT, DEFINITIVE_LAST_TURN, ALLY_DESTROYED_LAST_TURN }

    /** "durante tu turno", "durante un combate", "si activaste algún Efecto Definitivo el turno anterior", "si algún aliado fue destruido por el oponente el turno anterior". */
    @JvmRecord
    data class GameIs(val state: GameState?) : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            return when (state!!) {
                GameState.YOUR_TURN -> controller!! == room.currentTurnUserId
                GameState.DURING_COMBAT -> room.combat != null
                GameState.DEFINITIVE_LAST_TURN -> room.usedThisTurn.contains(EffectEngine.DEFINITIVE_LAST_TURN + controller)
                GameState.ALLY_DESTROYED_LAST_TURN -> room.usedThisTurn.contains(EffectEngine.ALLY_DESTROYED_LAST_TURN + controller)
            }
        }
    }

    /** "En Destrucción por efecto [aliado]": this card was destroyed by an effect (of its owner's). */
    @JvmRecord
    data class ByEffect(val by: Side?) : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            if (event == null || !sideIs(by, event.playerId, controller)) return false
            return destroyedByEffect(event) || (event.data != null && EffectEngine.CAUSE_EFFECT == event.data!!["cause"]
                    && event.data!!["byCard"] != null)
        }
    }

    /** "si esta carta es objetivo" (`thisCard`) or "si un aliado es objetivo". */
    @JvmRecord
    data class Targeted(val thisCard: Boolean) : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            if (event == null || GameEvent.TARGET != event.type || event.data == null) return false
            val ids = event.data!!["targetIds"] as? List<*> ?: return false
            if (thisCard) return ids.contains(source!!.instanceId)
            val player = room.getPlayer(controller)
            return player != null && ids.any { id -> EffectEngine.find(player.field, id as String?) != null }
        }
    }

    /** "si algún aliado [filtro] inflige daño directo" (by combat). */
    @JvmRecord
    data class AllyDealtDirectDamage(val filter: CardFilter?) : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            if (event == null || GameEvent.LIFE_DAMAGE != event.type || controller!! != event.playerId || event.data == null) return false
            val attacker = EffectEngine.findInRoom(room, event.data!!["attackerId"] as String?)
            return attacker != null && filter!!.matches(attacker)
        }
    }

    /**
     * "si esta carta es tributada [desde zona] [, excepto desde zona] [por efecto de 1 carta filtro]": `fromZone` and
     * `exceptZone` may be null ("tributada" from anywhere); `by` null: by anything, otherwise only by an effect of
     * a card that matches.
     */
    @JvmRecord
    data class ThisTributed(val fromZone: String?, val exceptZone: String?, val by: CardFilter?) : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            // Being sacrificed is being tributed too (designer ruling 2026-10-30, Niobats), and a life lost to direct damage that
            // goes to the Tributos is tributed from the lives (rule 10.1.4, Cofre Mímico)
            if (event == null) return false
            val type = event.type
            val tributed = GameEvent.TRIBUTE == type || GameEvent.SACRIFICE == type
                    || (GameEvent.LIFE_DAMAGE == type && Zone.TRIBUTE_ZONE.`is`(event.toZone))
            if (!tributed || source!!.instanceId != event.cardInstanceId) return false
            if (fromZone != null && fromZone != event.fromZone) return false
            if (exceptZone != null && exceptZone == event.fromZone) return false
            if (by == null) return true
            val byId = if (event.data == null) null else event.data!!["byCard"]
            if (event.effectId == null || byId == null) return false
            val byCard = EffectEngine.findInRoom(room, byId as String)
            return byCard != null && by.matches(byCard)
        }
    }

    /** "si reduces [a N o menos] la fuerza de algún enemigo"; `enemyStillThere`: it is still on the field with some strength. */
    @JvmRecord
    data class YouReducedStrength(val toAtMost: Int?, val enemyStillThere: Boolean) : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            if (event == null || GameEvent.STRENGTH_REDUCED != event.type || controller!! != event.playerId) return false
            val to = if (event.data == null) null else event.data!!["to"]
            if (toAtMost != null && !(to is Number && to.toInt() <= toAtMost)) return false
            if (!enemyStillThere) return true
            val target = EffectEngine.findIn(room, ownerOf(event), "field", event.cardInstanceId)
            return target != null && EffectEngine.strength(target) > 0
        }
    }

    // ------------------------------------------------------------------ The table

    // ------------------------------------------------------------------ Shared

    // ------------------------------------------------------------------ E1.2, E1.3 and E2 (2026-10-31)

    /** "si algún aliado [filtro] es invocado y no tiene materiales [filtro]". */
    @JvmRecord
    data class SummonedWithoutMaterial(val material: CardFilter?) : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            val summoned = if (event == null) null else EffectEngine.eventCard(room, event)
            if (summoned == null) return false
            // Java's material::matches: the filter is read before the materials
            val m = material!!
            return summoned.materials!!.none { m.matches(it) }
        }
    }

    /**
     * "si algún [enemigo / aliado] [filtro] entra a la línea frontal": summoned or placed there, or moved up from the Retaguardia
     * (`enemy`: the opponent's).
     */
    @JvmRecord
    data class EntersFrontLine(val enemy: Boolean, val filter: CardFilter?) : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            if (event == null || event.cardInstanceId == null) return false
            val type = event.type
            var entered = GameEvent.MOVE == type && "frontLine" == event.toZone
            if (!entered && Zone.FIELD.`is`(event.toZone) && (GameEvent.NORMAL_SUMMON == type || GameEvent.EFFECT_SUMMON == type
                        || GameEvent.SPECIAL_SUMMON == type || GameEvent.SEND == type)) {
                val card = EffectEngine.findInRoom(room, event.cardInstanceId)
                entered = card != null && EffectEngine.inFrontLine(card)
            }
            if (!entered) return false
            val o = if (event.data != null) event.data!!["ownerId"] else null
            val owner = if (o is String) o else event.playerId
            if (enemy == (controller!! == owner)) return false
            val card = EffectEngine.findInRoom(room, event.cardInstanceId)
            return card != null && filter!!.matches(card)
        }
    }

    /**
     * "si esta carta enfrenta un enemigo [filtro] [como atacante]": the clash step of an attack where it attacks or blocks begins (before the
     * clash itself, so what it does happens before the strengths are compared).
     */
    @JvmRecord
    data class ThisClashes(val enemy: CardFilter?, val asAttacker: Boolean) : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            if (event == null || !combatStep(Combat.Step.CLASH, Combat.Phase.ENTER).test(room, event, source, controller)) return false
            val c = room.combat
            if (c == null || c.blockerId == null) return false
            val id = source!!.instanceId
            val other = if (id == c.attackerId) c.blockerId else if (!asAttacker && id == c.blockerId) c.attackerId else null
            if (other == null) return false
            val card = EffectEngine.findInRoom(room, other)
            return card != null && enemy!!.matches(card)
        }
    }

    /** "si aún no has jugado ni invocado otra carta este turno". */
    data object NothingElsePlayedThisTurn : Condition {
        override fun test(room: GameRoom, event: GameEvent?, source: CardSlot?, controller: String?): Boolean {
            val timeline = room.timeline
            for (i in timeline.size - 1 downTo 0) {
                val e = timeline[i]
                if (GameEvent.TURN_START == e.type) return true
                if (controller!! != e.playerId || source!!.instanceId == e.cardInstanceId) continue
                val type = e.type
                if (GameEvent.PLAY == type || GameEvent.NORMAL_SUMMON == type || GameEvent.SPECIAL_SUMMON == type
                    || GameEvent.EFFECT_SUMMON == type) return false
            }
            return true
        }
    }

    companion object {

        @JvmStatic
        fun any(vararg conditions: Condition): Condition {
            return Any(java.util.List.of(*conditions))
        }

        @JvmStatic
        fun all(vararg conditions: Condition): Condition {
            return All(java.util.List.of(*conditions))
        }

        @JvmStatic
        fun not(condition: Condition?): Condition {
            return Not(condition)
        }

        private fun sideIs(side: Side?, player: String?, controller: String?): Boolean {
            return when (side!!) {
                Side.ANY -> true
                Side.YOU -> controller!! == player
                Side.ENEMY -> player != null && controller!! != player
            }
        }

        private fun compare(left: Amount?, op: Op?, right: Int): Condition {
            return Compare(left, op, Amount.Fixed(right))
        }

        private fun happened(type: String?, thisCard: Boolean, vararg data: String): Condition {
            return Happened(type, Side.ANY, Side.ANY, thisCard, null, null, null, false, java.util.List.of(*data))
        }

        private fun moved(from: String?, to: String?): Condition {
            return Happened(null, Side.ANY, Side.ANY, true, null, from, to, false, java.util.List.of())
        }

        // The usual printed phrases

        @JvmStatic
        fun countOnField(filter: CardFilter?, atLeast: Int?, atMost: Int?): Condition {
            val count: Amount = Amount.OnField(filter)
            if (atLeast != null && atMost != null) return all(compare(count, Op.AT_LEAST, atLeast), compare(count, Op.AT_MOST, atMost))
            return if (atLeast != null) compare(count, Op.AT_LEAST, atLeast) else compare(count, Op.AT_MOST, atMost!!)
        }

        @JvmStatic
        fun countInZone(zone: String?, filter: CardFilter?, atLeast: Int): Condition {
            return compare(Amount.InZone(zone, filter), Op.AT_LEAST, atLeast)
        }

        @JvmStatic
        fun countOnBothFields(filter: CardFilter?, atLeast: Int): Condition {
            return compare(Amount.OnBothFields(filter), Op.AT_LEAST, atLeast)
        }

        @JvmStatic
        fun livesAtMost(opponent: Boolean, lives: Int): Condition {
            return compare(Amount.Lives(opponent, false), Op.AT_MOST, lives)
        }

        @JvmStatic
        fun fewerLivesThanOpponent(): Condition {
            return Compare(Amount.Lives(false, false), Op.LESS, Amount.Lives(true, false))
        }

        @JvmStatic
        fun faceDownLivesAtMost(lives: Int): Condition {
            return compare(Amount.Lives(false, true), Op.AT_MOST, lives)
        }

        @JvmStatic
        fun opponentLevelsExceed(by: Int): Condition {
            return Compare(Amount.OpponentLevels, Op.AT_LEAST, Amount.Scaled(Amount.LevelsOnField(CardFilter.creatures()), 1, by))
        }

        @JvmStatic
        fun levelsBelowOpponent(): Condition {
            return Compare(Amount.LevelsOnField(CardFilter.creatures()), Op.LESS, Amount.OpponentLevels)
        }

        @JvmStatic
        fun gemsAtLeast(gems: Int): Condition {
            return compare(Amount.SignedGems, Op.AT_LEAST, gems)
        }

        @JvmStatic
        fun noEnemiesInFrontLine(): Condition {
            return compare(Amount.EnemiesInFrontLine, Op.EQUAL, 0)
        }

        @JvmStatic
        fun inFrontLine(): Condition {
            return ThisIs(CardState.IN_FRONT_LINE)
        }

        @JvmStatic
        fun active(): Condition {
            return ThisIs(CardState.ACTIVE)
        }

        @JvmStatic
        fun noCopies(): Condition {
            return ThisIs(CardState.NO_COPIES)
        }

        @JvmStatic
        fun yourTurn(): Condition {
            return GameIs(GameState.YOUR_TURN)
        }

        @JvmStatic
        fun duringCombat(): Condition {
            return GameIs(GameState.DURING_COMBAT)
        }

        @JvmStatic
        fun noCombat(): Condition {
            return Not(duringCombat())
        }

        @JvmStatic
        fun usedDefinitiveLastTurn(): Condition {
            return GameIs(GameState.DEFINITIVE_LAST_TURN)
        }

        @JvmStatic
        fun allyDestroyedByOpponentLastTurn(): Condition {
            return GameIs(GameState.ALLY_DESTROYED_LAST_TURN)
        }

        @JvmStatic
        fun destroyedByEffect(): Condition {
            return ByEffect(Side.ANY)
        }

        @JvmStatic
        fun destroyedByAllyEffect(): Condition {
            return ByEffect(Side.YOU)
        }

        /** "si esta carta entra en combate": it attacks, blocks or is attacked. */
        @JvmStatic
        fun entersCombat(): Condition {
            return any(happened(GameEvent.ATTACK_DECLARED, true), happened(GameEvent.ATTACK_DECLARED, false, "targetId=\$this"), happened(GameEvent.BLOCK, true))
        }

        /** "si esta carta es agotada": by an effect or to declare an attack or a block. */
        @JvmStatic
        fun thisExhausted(): Condition {
            return any(happened(GameEvent.TAP, true), happened(GameEvent.BLOCK, true), happened(GameEvent.ATTACK_DECLARED, true, "exhausted=true"))
        }

        /** "si un aliado declara ataque o bloqueo, y no controlas otras criaturas". */
        @JvmStatic
        fun loneAllyAttacksOrBlocks(): Condition {
            val declared = any(
                Happened(GameEvent.ATTACK_DECLARED, Side.YOU, Side.ANY, false, null, null, null, false, java.util.List.of()),
                Happened(GameEvent.BLOCK, Side.YOU, Side.ANY, false, null, null, null, false, java.util.List.of()),
            )
            return all(declared, compare(Amount.OnField(CardFilter.creatures()), Op.EQUAL, 1))
        }

        /** "si alguna carta es removida de tus vidas". */
        @JvmStatic
        fun lifeRemoved(): Condition {
            return any(
                Happened(null, Side.ANY, Side.YOU, false, null, "lifeStack", null, false, java.util.List.of()),
                Happened(null, Side.ANY, Side.ANY, false, null, "lifeStack", null, false, java.util.List.of("defenderId=\$you")),
            )
        }

        /** "si recibes daño directo [por el oponente]" (by an attack or an effect). */
        @JvmStatic
        fun youReceivedDirectDamage(): Condition {
            return happened(GameEvent.LIFE_DAMAGE, false, "defenderId=\$you")
        }

        /** "si alguna criatura es destruida o invocada especialmente" (either player's). */
        @JvmStatic
        fun creatureDestroyedOrSpecialSummoned(): Condition {
            val fromLives: Condition = Happened(null, Side.ANY, Side.ANY, false, null, "lifeStack", null, false, java.util.List.of())
            return any(specialSummoned(), all(Destroyed(Side.ANY, Side.ANY, CardFilter.creatures(), null, false, null, false), Not(fromLives)))
        }

        @JvmStatic
        fun thisTargeted(): Condition {
            return Targeted(true)
        }

        @JvmStatic
        fun allyTargeted(): Condition {
            return Targeted(false)
        }

        @JvmStatic
        fun turnStarts(yours: Boolean): Condition {
            return Happened(GameEvent.TURN_START, if (yours) Side.YOU else Side.ANY, Side.ANY, false, null, null, null, false, java.util.List.of())
        }

        @JvmStatic
        fun turnEnds(): Condition {
            return turnEnds(true)
        }

        @JvmStatic
        fun turnEnds(yours: Boolean): Condition {
            return Happened(GameEvent.TURN_END, if (yours) Side.YOU else Side.ANY, Side.ANY, false, null, null, null, false, java.util.List.of())
        }

        @JvmStatic
        fun combatStep(step: Combat.Step?, phase: Combat.Phase?): Condition {
            return happened(GameEvent.COMBAT_STEP, false, "step=" + step!!.name, "moment=" + phase!!.name)
        }

        @JvmStatic
        fun thisMoved(from: String?, to: String?): Condition {
            return moved(from, to)
        }

        @JvmStatic
        fun thisDeclaredBlocker(): Condition {
            return happened(GameEvent.BLOCK, true)
        }

        @JvmStatic
        fun thisUsedKeyword(keyword: String?): Condition {
            return happened(GameEvent.GAIN, true, "keyword=" + keyword)
        }

        @JvmStatic
        fun thisAscended(): Condition {
            return happened(GameEvent.TRIBUTE, true, "cause=promotion")
        }

        @JvmStatic
        fun youPlayed(filter: CardFilter?): Condition {
            return Happened(GameEvent.PLAY, Side.YOU, Side.ANY, false, filter, null, null, false, java.util.List.of())
        }

        @JvmStatic
        fun destroyedInClash(): Condition {
            return happened(null, false, "cause=" + EffectEngine.CAUSE_BATTLE)
        }

        @JvmStatic
        fun hadMaterials(): Condition {
            return happened(null, false, "hadMaterials=true")
        }

        @JvmStatic
        fun enemyDestroyedInAttack(): Condition {
            return happened(null, false, "enemyDestroyed=true")
        }

        @JvmStatic
        fun thisWentToDiscard(): Condition {
            return Happened(null, Side.ANY, Side.ANY, false, null, null, "discardPile", false, java.util.List.of())
        }

        @JvmStatic
        fun materialRemovedByYou(filter: CardFilter?): Condition {
            return Happened(null, Side.YOU, Side.ANY, false, filter, "materials", null, true, java.util.List.of())
        }

        @JvmStatic
        fun allySentToDiscard(): Condition {
            return Happened(null, Side.ANY, Side.YOU, false, CardFilter.creatures(), "field", "discardPile", false, java.util.List.of())
        }

        @JvmStatic
        fun allyDestroyed(filter: CardFilter?, byOpponent: Boolean, exceptAttacking: Boolean): Condition {
            return Destroyed(Side.YOU, if (byOpponent) Side.ENEMY else Side.ANY, filter!!.asCreature(), null, false, null, exceptAttacking)
        }

        @JvmStatic
        fun youDestroyedEnemy(byEffect: Boolean): Condition {
            return Destroyed(Side.ENEMY, Side.YOU, null, null, false, if (byEffect) "effect" else null, false)
        }

        @JvmStatic
        fun thisDestroyedEnemy(inClash: Boolean): Condition {
            return Destroyed(Side.ENEMY, Side.ANY, null, null, true, if (inClash) "battle" else null, false)
        }

        @JvmStatic
        fun youDestroyedCreature(byEffect: Boolean): Condition {
            return Destroyed(Side.ANY, Side.YOU, CardFilter.creatures(), null, false, if (byEffect) "effect" else null, false)
        }

        @JvmStatic
        fun allyDestroyedByYourEffect(): Condition {
            return Destroyed(Side.YOU, Side.YOU, null, null, false, "effect", false)
        }

        @JvmStatic
        fun allyDestroyedEnemyInClash(): Condition {
            return Destroyed(Side.ENEMY, Side.YOU, null, null, false, "battle", false)
        }

        @JvmStatic
        fun enemyDestroyedByYourCard(by: CardFilter?): Condition {
            return Destroyed(Side.ENEMY, Side.YOU, null, by, false, null, false)
        }

        @JvmStatic
        fun allySummoned(filter: CardFilter?): Condition {
            return Summoned(Side.YOU, true, filter, null, null)
        }

        @JvmStatic
        fun allySummonedByEffect(filter: CardFilter?): Condition {
            return Summoned(Side.YOU, false, filter, "effect", null)
        }

        @JvmStatic
        fun youSpecialSummoned(filter: CardFilter?): Condition {
            return Summoned(Side.YOU, false, filter, "special", null)
        }

        @JvmStatic
        fun youSpecialSummonedAs(kind: String?, filter: CardFilter?): Condition {
            return Summoned(Side.YOU, false, filter, "special", kind)
        }

        @JvmStatic
        fun summonedByEffect(): Condition {
            return Summoned(Side.ANY, false, null, "effect", null)
        }

        @JvmStatic
        fun specialSummoned(): Condition {
            return Summoned(Side.ANY, false, null, "special", null)
        }

        @JvmStatic
        fun tributedFromDeck(by: CardFilter?): Condition {
            return ThisTributed("deck", null, by)
        }

        @JvmStatic
        fun tributedAsMaterial(byLevel: Int): Condition {
            return ThisTributed("materials", null, if (byLevel > 0) CardFilter.any().withMinLevel(byLevel) else null)
        }

        @JvmStatic
        fun thisLeftLives(): Condition {
            return moved("lifeStack", null)
        }

        @JvmStatic
        fun thisSentToDiscardAsMaterial(): Condition {
            return moved("materials", "discardPile")
        }

        @JvmStatic
        fun thisMovedToFront(): Condition {
            return moved(null, "frontLine")
        }

        @JvmStatic
        fun anyTurnEnds(): Condition {
            return turnEnds(false)
        }

        @JvmStatic
        fun allyDeclaresAttack(filter: CardFilter?): Condition {
            return Happened(GameEvent.ATTACK_DECLARED, Side.YOU, Side.ANY, false, filter, null, null, false, java.util.List.of())
        }

        @JvmStatic
        fun enemyDeclaresAttack(): Condition {
            return Happened(GameEvent.ATTACK_DECLARED, Side.ENEMY, Side.ANY, false, null, null, null, false, java.util.List.of())
        }

        /**
         * Destroyed (or sacrificed) "por efecto": during an effect, and not as the cost of a summon that effect made (the creatures used
         * for a Ritual are sacrificed by the Ritual's rules, even when an effect summons it).
         */
        private fun destroyedByEffect(event: GameEvent): Boolean {
            return event.effectId != null && !(event.data != null && EffectEngine.CAUSE_RITUAL == event.data!!["cause"])
        }

        private fun isDestruction(event: GameEvent): Boolean {
            return GameEvent.DESTROY == event.type || GameEvent.SACRIFICE == event.type
        }

        private fun ownerOf(event: GameEvent): String? {
            return if (event.data == null) null else event.data!!["ownerId"] as String?
        }
    }
}
