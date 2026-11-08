package com.drive.drive_manager.simulator.engine.effects.model

import kotlin.jvm.JvmSuppressWildcards
import kotlin.jvm.JvmRecord
import com.drive.drive_manager.simulator.engine.Amount

/**
 * A basic action effects are composed of. Plain data: what each one does is in its handler (effects/actions), one class
 * per action. See the vault: 11 Simulador/Plan de refactorización del motor.
 */
sealed interface Action {

    /** Whether an optional effect can only be offered when this action has something to act on (rule 11.2.0.2). */
    fun needsCards(): Boolean {
        return false
    }

    /** Several actions that are one instruction of a card. */
    @JvmRecord
    data class Sequence(val actions: List<@JvmSuppressWildcards Action>?) : Action {
        override fun needsCards(): Boolean {
            return true
        }
    }

    /** The special summon itself: the creature, its summoner and its materials were chosen by the actions before it. */
    @JvmRecord
    data class SummonSpecial(val rule: SpecialRule?) : Action

    /** "invoca": `reduce` lowers its cost, [FREE] is "sin pagar su coste". */
    @JvmRecord
    data class Summon(val cards: Selector?, val reduce: Amount?, val toFront: Boolean, val required: Boolean) : Action {
        override fun needsCards(): Boolean {
            return required
        }

        companion object {
            const val FREE = 1000
        }
    }

    /** "juega": a utility or structure. */
    @JvmRecord
    data class Play(val cards: Selector?, val reduce: Amount?, val required: Boolean) : Action {
        override fun needsCards(): Boolean {
            return required
        }

        companion object {
            const val FREE = 1000
        }
    }

    /** "declara ataque": this card, or the ally chosen before, attacks without being exhausted (rule 11.9.3.1); `opponentOnly`: "a tu rival". */
    @JvmRecord
    data class Attack(val opponentOnly: Boolean) : Action

    /** "al final del turno: …": what follows is another effect of the card, `effect` (moment DELAYED), for the same subject. */
    @JvmRecord
    data class AtEndOfTurn(val effect: String?) : Action

    /** "roba N cartas"; `bothPlayers`: "ambos jugadores roban". */
    @JvmRecord
    data class Draw(val count: Amount?, val bothPlayers: Boolean) : Action

    /** "gana N gemas". */
    @JvmRecord
    data class Gems(val count: Amount?) : Action

    /** "infringe N de daño directo" to the opponent, by effect. */
    @JvmRecord
    data class Damage(val amount: Amount?) : Action

    /** "mira las primeras N cartas de tu mazo". */
    @JvmRecord
    data class Look(val count: Amount?) : Action

    /** "agota" (`tapped`) or "reactiva". */
    @JvmRecord
    data class SetTapped(val cards: Selector?, val tapped: Boolean, val required: Boolean) : Action {
        override fun needsCards(): Boolean {
            return required
        }
    }

    /** How long a modification lasts. */
    enum class Until {
        TURN, NEXT_TURN, ATTACK, NEXT_ATTACK,
        /** "en N más": as long as the reduction that triggered the effect. */
        AS_EVENT
    }

    /**
     * For a while, cards gain or lose strength, or gain an ability or a restriction.
     *
     * @param strength negative: it is reduced; null: no change
     * @param toZero   "reduce a 0 su fuerza"
     * @param keyword  an ability or restriction; null: none
     */
    @JvmRecord
    data class Modify(val cards: Selector?, val strength: Amount?, val toZero: Boolean, val keyword: String?, val until: Until?) : Action

    /** "destruye", or "sacrifica" with `sacrifice`. */
    @JvmRecord
    data class Destroy(val cards: Selector?, val sacrifice: Boolean, val required: Boolean) : Action {
        override fun needsCards(): Boolean {
            return required
        }
    }

    /**
     * Cards go to a zone; the event comes from where they arrive: reaching the Tributos is being tributed, the Descarte being
     * discarded, whatever the verb (designer ruling 2026-11-01).
     *
     * @param cards    which ones; when the player picks them they are not targets (only "elige", [Select], makes targets)
     * @param to        [TRIBUTES], [DISCARD], [DECK_TOP], [DECK_BOTTOM], [DECK_TOP_OR_BOTTOM] (the player says), [HAND], [FRONT], [BACK],
     *                  [LIFE_TOP], or "materials:x" / "resources:x" of x: self, subject or last
     * @param random   "en orden aleatorio"; otherwise the order the player gives
     * @param label    what is shown on a chosen card; null: said by the destination
     * @param required an optional effect is only offered when there is something to move
     * @param exhausted "agotada": entering the field that way
     * @param faceDown  "bocabajo"; cards going to the deck always are
     * @param rest      looked cards: where those not picked go, in the order the player gives, in the same choice; null: they stay
     */
    @JvmRecord
    data class Move(
        val cards: Selector?, val to: String?, val random: Boolean, val label: String?, val required: Boolean, val exhausted: Boolean,
        val faceDown: Boolean, val rest: String?,
    ) : Action {
        constructor(cards: Selector?, to: String?, random: Boolean, label: String?, required: Boolean) :
                this(cards, to, random, label, required, false, false, null)

        override fun needsCards(): Boolean {
            return required
        }

        companion object {
            const val FRONT = "frontLine"
            const val BACK = "backLine"
            const val LIFE_TOP = "lifeTop"
            const val TRIBUTES = "tributeZone"
            const val DISCARD = "discardPile"
            const val DECK_TOP = "deckTop"
            const val DECK_BOTTOM = "deckBottom"
            const val DECK_TOP_OR_BOTTOM = "deckTopOrBottom"
            const val HAND = "hand"
        }
    }

    /**
     * The player picks cards ("elige 1 enemigo", "tributa 1 carta de tu mano": the part that picks) and they are saved for the
     * next actions.
     *
     * @param selector which cards, and how many
     * @param target   the text says "elige" (rule 11.10): it makes them targets, so "no puede ser objetivo" applies. Without that
     *                 word the player still points at a card, but it is not a target (designer ruling 2026-11-01)
     * @param saveAs   the name the next actions read them by; null: [Memory.TARGETS] for targets, [Memory.LAST] otherwise
     * @param label    what is shown on a chosen card ("Al fondo"); null: "Objetivo" or "Elegida"
     * @param endsIfNone with nothing to pick, the rest of the effect does nothing ("elige 1 aliado; …")
     */
    @JvmRecord
    data class Select(val selector: Selector?, val target: Boolean, val saveAs: String?, val label: String?, val endsIfNone: Boolean) : Action {
        constructor(selector: Selector?, target: Boolean, saveAs: String?, label: String?) : this(selector, target, saveAs, label, false)

        override fun needsCards(): Boolean {
            return true
        }
    }
}
