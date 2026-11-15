package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmRecord
import kotlin.math.max
import kotlin.math.min
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/**
 * Reactivo–Modificadores of a cost ("Reacciones, modificadores y Reemplazos"): applied right before the event they change
 * (a special summon, playing a card), they lower its cost and keep the event as it is. Some ask for a payment of their own
 * ("puedes agotar esta carta…", "puedes enviar 5 de tus tributos [dragón]…"). Cards declare them in [CardEffects].
 */
object CostModifiers {

    /** The event whose cost it changes. */
    enum class Event { SPECIAL_SUMMON, PLAY, SUMMON }

    /**
     * Whose it is: the special summon's summoner ("cuando esta carta fuera a invocar…"), any of the player's cards on the
     * field ("cuando fueras a invocar…", a structure) or the card being played itself ("cuando fueras a jugar esta carta").
     */
    enum class Holder { SUMMONER, FIELD, SELF }

    enum class PaymentKind { NONE, TAP_SELF, TRIBUTES_TO_DECK_BOTTOM, TRIBUTE_AS_RESOURCE, TAP_CARD, SELF_DAMAGE }

    /** What the modifier asks for, if anything. */
    @JvmRecord
    data class Payment(val kind: PaymentKind?, val count: Int, val filter: CardFilter?) {

        /** The damage of a [PaymentKind.SELF_DAMAGE] payment. */
        fun damage(): Int {
            return if (filter == null || filter.maxStrength == null) 0 else filter.maxStrength
        }

        /** The player has to choose the cards (the others are applied on their own). */
        fun needsChoice(): Boolean {
            return kind == PaymentKind.TRIBUTES_TO_DECK_BOTTOM || kind == PaymentKind.TRIBUTE_AS_RESOURCE || kind == PaymentKind.TAP_CARD
                    || kind == PaymentKind.SELF_DAMAGE
        }

        companion object {
            @JvmStatic
            fun none(): Payment {
                return Payment(PaymentKind.NONE, 0, null)
            }

            /** "puedes agotar esta carta": the holder, active. */
            @JvmStatic
            fun tapSelf(): Payment {
                return Payment(PaymentKind.TAP_SELF, 0, null)
            }

            /** "puedes enviar N de tus tributos [filtro] al fondo de tu mazo": the player picks which. */
            @JvmStatic
            fun tributesToDeckBottom(count: Int, filter: CardFilter?): Payment {
                return Payment(PaymentKind.TRIBUTES_TO_DECK_BOTTOM, count, filter)
            }

            /** "puedes añadir 1 de tus tributos [filtro] como recurso de esta carta": the summoner, before the summon. */
            @JvmStatic
            fun tributeAsResource(filter: CardFilter?): Payment {
                return Payment(PaymentKind.TRIBUTE_AS_RESOURCE, 1, filter)
            }

            /** "puedes agotar 1 carta [filtro] bajo tu control": the player picks which (active, on the field). */
            @JvmStatic
            fun tapCard(filter: CardFilter?): Payment {
                return Payment(PaymentKind.TAP_CARD, 1, filter)
            }

            /**
             * "hasta N veces: puedes infligirte X de daño para reducir en 1 el coste especial" (Biok): direct damage by effect to oneself, as many
             * times as the player picks (up to `count`); `filter` unused, the damage is in [Payment.damage].
             */
            @JvmStatic
            fun selfDamage(times: Int, damage: Int): Payment {
                return Payment(PaymentKind.SELF_DAMAGE, times, CardFilter.any().withMaxStrength(damage))
            }
        }
    }

    /**
     * @param label     how it is printed ("Turno Aliado", "Todos Los Turnos"), for the choice
     * @param kinds     the special summon methods it applies to ("evolution"…); empty: all of them
     * @param allyTurn  only on its controller's turn
     * @param condition the rest of its condition ("si tienes 5 o más tributos [dragón]"), on its holder
     * @param reduce    how much it lowers the cost
     * @param card      the card whose cost it lowers ("1 criatura Azul nivel 3 o mayor"); null: any
     * @param amount    a reduction read from the game, added to `reduce` ("en 1 por cada [brujería] en tu descarte"); null: none
     */
    @JvmRecord
    data class CostModifier(
        val label: String?, val event: Event?, val kinds: Set<String>?, val holder: Holder?, val allyTurn: Boolean,
        val condition: Condition?, val payment: Payment?, val reduce: Int, val limit: EffectScript.Limit?, val card: CardFilter?, val amount: Amount?,
    ) : Reaction {
        constructor(
            label: String?, event: Event?, kinds: Set<String>?, holder: Holder?, allyTurn: Boolean, condition: Condition?, payment: Payment?,
            reduce: Int,
        ) : this(label, event, kinds, holder, allyTurn, condition, payment, reduce, EffectScript.Limit.NONE)

        constructor(
            label: String?, event: Event?, kinds: Set<String>?, holder: Holder?, allyTurn: Boolean, condition: Condition?, payment: Payment?,
            reduce: Int, limit: EffectScript.Limit?,
        ) : this(label, event, kinds, holder, allyTurn, condition, payment, reduce, limit, null, null)

        /** How much it lowers the cost now. */
        fun reduceNow(room: GameRoom, controller: String?): Int {
            return reduce + (amount?.of(room, controller) ?: 0)
        }
    }

    /** The mark of a limited modifier used this turn (between copies: by the card's name). */
    @JvmStatic
    fun usageKey(player: PlayerState, use: Use): String {
        // A Definitive one counts as the player's Definitive effect of the turn (only 1 per turn, whichever card)
        if (use.modifier.limit == EffectScript.Limit.DEFINITIVE) return player.userId + "|" + EffectEngine.DEFINITIVE_USED
        return player.userId + "|cost-modifier|" + use.holder.cardName!!.trim { it <= ' ' }.lowercase()
    }

    /** A limited modifier was used: it is not offered again this turn. */
    @JvmStatic
    fun markUsed(room: GameRoom, player: PlayerState, use: Use) {
        if (use.modifier.limit != EffectScript.Limit.NONE) room.usedThisTurn.add(usageKey(player, use))
    }

    /** A modifier that applies now, with the card that has it. */
    @JvmRecord
    data class Use(val modifier: CostModifier, val holder: CardSlot)

    private val BY_NAME: MutableMap<String, MutableList<CostModifier>> = HashMap()

    init {
        // The cards register their modifiers when CardEffects loads: load it before the first lookup
        CardEffects.load()
    }

    @JvmStatic
    fun register(cardName: String, vararg modifiers: CostModifier) {
        BY_NAME.getOrPut(cardName.trim { it <= ' ' }.lowercase()) { ArrayList() }.addAll(frozenListOf(*modifiers))
    }

    /** The card's modifiers, by its name in lower case. */
    @JvmStatic
    fun of(name: String?): List<CostModifier> {
        return BY_NAME.valueOr(name, frozenListOf())
    }

    /** The names of the cards with modifiers. */
    @JvmStatic
    fun names(): Set<String> {
        return BY_NAME.keys
    }

    private fun of(card: CardSlot?): List<CostModifier> {
        if (card == null || card.cardName == null) return frozenListOf()
        return BY_NAME.valueOr(CardEffects.nameOf(card.cardName), frozenListOf())
    }

    /**
     * The modifiers that apply now to that event: their holder is where it has to be (active, to exhaust it), it is its
     * controller's turn when they say so, their condition holds and their payment can be made. With no summoner chosen
     * yet, any card of the field could be it (to tell whether the summon is affordable at all).
     */
    @JvmStatic
    fun applicable(room: GameRoom, player: PlayerState?, event: Event?, kind: String?, card: CardSlot?, summoner: CardSlot?): List<Use> {
        if (player == null) return frozenListOf()
        val uses: MutableList<Use> = ArrayList()
        val holders: MutableList<CardSlot> = ArrayList()
        // The card itself ("cuando fueras a jugar / invocar esta carta"), while it is not on the field yet
        if (card != null && !player.field.contains(card)) holders.add(card)
        holders.addAll(player.field)
        for (holder in holders) {
            // A card face down on the field has no effects (the one being played is not on the field yet)
            if (holder !== card && holder.faceDown) continue
            for (m in of(holder)) {
                if (m.event != event || (!m.kinds!!.isEmpty() && !m.kinds.contains(kind))) continue
                if (m.card != null && (card == null || !m.card.matches(card))) continue
                val placed = when (m.holder!!) {
                    Holder.SELF -> holder === card
                    Holder.SUMMONER -> if (summoner == null) holder !== card else holder === summoner
                    Holder.FIELD -> holder !== card
                }
                if (!placed) continue
                if (m.allyTurn && player.userId != room.currentTurnUserId) continue
                if (m.condition != null && !m.condition.test(room, null, holder, player.userId)) continue
                if (!payable(player, m.payment!!, holder, card)) continue
                // "puedes agotar…" the player said no to, for this summon
                if (m.payment.kind == PaymentKind.TAP_SELF && card != null && room.usedThisTurn.contains(tapKey(holder, card, false))) continue
                val use = Use(m, holder)
                if (m.limit == EffectScript.Limit.DEFINITIVE && room.firstTurn) continue
                if (m.limit != EffectScript.Limit.NONE && room.usedThisTurn.contains(usageKey(player, use))) continue
                uses.add(use)
            }
        }
        return uses
    }

    /** `card`: the card whose cost it lowers, never part of the payment ("envía otras 5…": it may be in the Tributos). */
    private fun payable(player: PlayerState, payment: Payment, holder: CardSlot, card: CardSlot?): Boolean {
        return when (payment.kind!!) {
            PaymentKind.NONE -> true
            PaymentKind.TAP_SELF -> !holder.tapped
            PaymentKind.TRIBUTES_TO_DECK_BOTTOM, PaymentKind.TRIBUTE_AS_RESOURCE -> tributesFor(player, payment, card).size >= payment.count
            PaymentKind.TAP_CARD -> player.field.count { c -> !c.tapped && payment.filter!!.matches(c) } >= payment.count
            PaymentKind.SELF_DAMAGE -> !player.lifeStack.isEmpty()
        }
    }

    /** How much the cost could go down now, all of them used (to tell whether it is affordable). */
    @JvmStatic
    fun available(room: GameRoom, player: PlayerState?, event: Event?, kind: String?, card: CardSlot?, summoner: CardSlot?): Int {
        return applicable(room, player, event, kind, card, summoner).sumOf { u -> u.modifier.reduceNow(room, player!!.userId) }
    }

    /** The player's answer to "puedes agotar esta carta para reducir…" for this summon (Bosque Frondoso), until it is used. */
    @JvmStatic
    fun tapKey(holder: CardSlot, card: CardSlot, yes: Boolean): String {
        return (if (yes) "cost-tap|" else "cost-tap-no|") + holder.instanceId + "|" + card.instanceId
    }

    /**
     * "puedes agotar esta carta para reducir el coste especial en 1" is optional: the first one not answered yet for this card, to
     * ask before the summon (null when there is none, or the cost is already 0).
     */
    @JvmStatic
    fun unansweredTap(room: GameRoom, player: PlayerState?, event: Event?, kind: String?, card: CardSlot?): Use? {
        // Designer ruling 2026-10-31: a cost can be reduced when it is already 0 (it stays 0), so it is asked anyway
        return applicable(room, player, event, kind, card, null).firstOrNull { u ->
            u.modifier.payment!!.kind == PaymentKind.TAP_SELF
                    && !room.usedThisTurn.contains(tapKey(u.holder, card!!, true))
                    && !room.usedThisTurn.contains(tapKey(u.holder, card, false))
        }
    }

    /**
     * Applies those that need no choice while the cost is above 0: a free one (Duregón's) and an exhaustion the player accepted
     * ("puedes agotar"; one not asked, as in a summon by effect, is used when it lowers the cost). Returns the reduction.
     */
    @JvmStatic
    fun applyAutomatic(room: GameRoom, player: PlayerState?, event: Event?, kind: String?, card: CardSlot?, summoner: CardSlot?, cost: Int): Int {
        return applyAutomatic(room, player, event, kind, card, summoner, cost, true)
    }

    /** `taps`: whether "puedes agotar esta carta…" ones may be used (a normal summon uses them only when it could not be paid otherwise). */
    @JvmStatic
    fun applyAutomatic(room: GameRoom, player: PlayerState?, event: Event?, kind: String?, card: CardSlot?, summoner: CardSlot?, cost: Int,
                       taps: Boolean): Int {
        var reduced = 0
        for (use in applicable(room, player, event, kind, card, summoner)) {
            player!!
            if (use.modifier.payment!!.needsChoice()) continue
            if (!taps && use.modifier.payment.kind == PaymentKind.TAP_SELF) continue
            // An exhaustion the player accepted is done even when the cost is already 0; the others only while they lower it
            val accepted = use.modifier.payment.kind == PaymentKind.TAP_SELF && card != null
                    && room.usedThisTurn.contains(tapKey(use.holder, card, true))
            if (cost - reduced <= 0 && !accepted) continue
            if (use.modifier.payment.kind == PaymentKind.TAP_SELF) {
                room.usedThisTurn.remove(tapKey(use.holder, card!!, true))
                use.holder.tapped = true
                EffectEngine.record(room, GameEvent.TAP, player.userId, use.holder, null, null, null, null,
                    player.username + " agota " + use.holder.cardName + " para reducir en " + use.modifier.reduceNow(room, player.userId) + " el coste")
            }
            reduced += use.modifier.reduceNow(room, player.userId)
            markUsed(room, player, use)
        }
        // The answers given for this summon are used up
        if (card != null) room.usedThisTurn.removeAll { k -> k.startsWith("cost-tap") && k.endsWith("|" + card.instanceId) }
        // A cost does not go below 0
        return min(reduced, max(0, cost))
    }

    /** The first one that asks the player for its payment (Flambreik's tributes), or null. */
    @JvmStatic
    fun choice(room: GameRoom, player: PlayerState?, event: Event?, kind: String?, card: CardSlot?, summoner: CardSlot?): Use? {
        return applicable(room, player, event, kind, card, summoner).firstOrNull { u -> u.modifier.payment!!.needsChoice() }
    }

    private fun tributesFor(player: PlayerState, payment: Payment, card: CardSlot?): List<CardSlot> {
        return player.tributeZone.filter { c -> c !== card && payment.filter!!.matches(c) }
    }

    /** The cards that can pay it (`card`: the one whose cost it lowers, left out). */
    @JvmStatic
    fun paymentOptions(player: PlayerState, use: Use, card: CardSlot?): List<CardSlot> {
        val payment = use.modifier.payment!!
        if (payment.kind == PaymentKind.TAP_CARD) return player.field.filter { c -> !c.tapped && payment.filter!!.matches(c) }
        // Each life of the top it may take, one per time
        if (payment.kind == PaymentKind.SELF_DAMAGE) return player.lifeStack.subList(0, min(payment.count, player.lifeStack.size))
        return if (payment.needsChoice()) tributesFor(player, payment, card) else frozenListOf()
    }

    /** What is asked, for the choice. */
    @JvmStatic
    fun describe(use: Use): String {
        val payment = use.modifier.payment!!
        if (payment.kind == PaymentKind.TRIBUTE_AS_RESOURCE) {
            return "Puedes añadir 1 de tus tributos " + payment.filter!!.describe() + " como recurso de " + use.holder.cardName +
                    " antes de la invocación. Elige 1, o ninguna."
        }
        if (payment.kind == PaymentKind.SELF_DAMAGE) {
            return "Hasta " + payment.count + " veces: puedes infligirte " + payment.damage() + " de daño directo para reducir en " + use.modifier.reduce +
                    " el coste especial. Elige tantas vidas del tope como veces, o ninguna."
        }
        if (payment.kind == PaymentKind.TAP_CARD) {
            return "Puedes agotar 1 carta " + payment.filter!!.describe() + " bajo tu control para reducir en " + use.modifier.reduce +
                    " el coste especial. Elige 1, o ninguna."
        }
        val what = if (payment.kind == PaymentKind.TRIBUTES_TO_DECK_BOTTOM)
            "enviar " + payment.count + " de tus tributos " + payment.filter!!.describe() + " al fondo de tu mazo" else "pagarlo"
        return "Puedes " + what + " para reducir en " + use.modifier.reduce + " el coste. Elige " + payment.count +
                ", o ninguna para pagar el coste completo."
    }
}
