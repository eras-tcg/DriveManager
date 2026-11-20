package com.drive.drive_manager.simulator.engine.effects.model

import kotlin.jvm.JvmSuppressWildcards
import kotlin.jvm.JvmRecord
import com.drive.drive_manager.simulator.engine.Amount
import com.drive.drive_manager.simulator.engine.CardFilter

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

    /**
     * "invoca": `reduce` lowers its cost, [FREE] is "sin pagar su coste".
     *
     * @param orPlay "juega o invoca 1 [treant]": a utility or structure picked is played (paying its cost, with the same reduction) and a
     *               creature is summoned
     */
    @JvmRecord
    data class Summon(val cards: Selector?, val reduce: Amount?, val toFront: Boolean, val required: Boolean, val orPlay: Boolean = false) : Action {
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

    /**
     * "declara ataque": this card, or the ally chosen before, attacks without being exhausted (rule 11.9.3.1); `opponentOnly`: "a tu rival";
     * `creaturesOnly`: "puede atacar un enemigo" (an exhausted enemy creature, never the rival; none: no attack); `activeToo`: "a un
     * enemigo activo o agotado" (any enemy creature of the Línea frontal); `at`: "ataca al enemigo elegido", the enemy saved under that
     * name is the one attacked, with no choice (none still there: no attack).
     */
    @JvmRecord
    data class Attack(val opponentOnly: Boolean, val creaturesOnly: Boolean = false, val activeToo: Boolean = false, val at: String? = null) : Action

    /** "al final del turno: …": what follows is another effect of the card, `effect` (moment DELAYED), for the same subject. */
    @JvmRecord
    data class AtEndOfTurn(val effect: String?) : Action

    /**
     * "el enemigo elegido": this card follows the cards `cards` picks (usually `saved:targets`), and `effect` (moment WATCHED) is the rest
     * of its text, triggered when one of them leaves the field, for that card ("si es destruido este turno por tus cartas puedes invocar…",
     * "si el enemigo elegido es removido: envía esta carta a tu descarte"). `untilTurnEnd`: it is followed until the end of the turn
     * ("este turno"); otherwise while this card stays on the field.
     */
    @JvmRecord
    data class Watch(val cards: Selector?, val effect: String?, val untilTurnEnd: Boolean) : Action

    /** "roba N cartas"; `bothPlayers`: "ambos jugadores roban". */
    @JvmRecord
    data class Draw(val count: Amount?, val bothPlayers: Boolean) : Action

    /** "gana N gemas". */
    @JvmRecord
    data class Gems(val count: Amount?) : Action

    /** "infringe N de daño directo" to the opponent, by effect. */
    @JvmRecord
    data class Damage(val amount: Amount?) : Action

    /**
     * "mira las primeras N cartas de tu mazo", or "excava cartas de tu mazo hasta que reveles N cartas o 1 [filtro]" (rule in development,
     * vault `01 Reglas/03 Mecánicas/Excavar`): they stay with the effect until an action moves them.
     *
     * @param until    "hasta que reveles … o 1 [filtro]": it stops at the first card that matches (it is the last one taken); null: N cards
     * @param excavate "excava": the cards are revealed to both players and the player excavated ("si excavas cartas" reads it)
     * @param opponent "… del mazo de tu oponente": the top cards of the opponent's deck (they go back to that deck); not with `excavate`
     */
    @JvmRecord
    data class Look(val count: Amount?, val until: CardFilter? = null, val excavate: Boolean = false, val opponent: Boolean = false) : Action

    /**
     * "agota" (`tapped`) or "reactiva".
     *
     * @param maxCostSum "que en coste sumen hasta N": the cards picked add up to N or less in cost; null: no limit
     */
    @JvmRecord
    data class SetTapped(val cards: Selector?, val tapped: Boolean, val required: Boolean, val maxCostSum: Int? = null) : Action {
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
     * "revela 1 de tus vidas" (`faceUp` true), "coloca bocabajo …" (false), "voltea una de tus vidas" (`toggle`: each one turns over). A life revealed
     * is an event ([com.drive.drive_manager.simulator.engine.GameEvent.LIFE_REVEALED]: "si alguna vida es revelada").
     */
    @JvmRecord
    data class Flip(val cards: Selector?, val faceUp: Boolean = true, val toggle: Boolean = false) : Action

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
     * @param sameName  "N copias de una criatura": the cards picked all have the same name (only cards with enough copies can be picked)
     * @param distinctLevels "N cartas de distinto nivel": no two cards picked have the same level
     * @param withResources "añade hasta 3 [treant] bajo tu control con todos sus recursos como recursos de esta carta": a card of the field
     *                      that becomes a material or a Recurso takes its own Recursos along to the same card (they are not discarded)
     */
    @JvmRecord
    data class Move(
        val cards: Selector?, val to: String?, val random: Boolean, val label: String?, val required: Boolean, val exhausted: Boolean,
        val faceDown: Boolean, val rest: String?, val sameName: Boolean = false, val distinctLevels: Boolean = false,
        val withResources: Boolean = false,
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
            /** "al fondo de tus vidas", "al fondo de las vidas oponentes" (of the card's owner). */
            const val LIFE_BOTTOM = "lifeBottom"
            const val TRIBUTES = "tributeZone"
            const val DISCARD = "discardPile"
            const val DECK_TOP = "deckTop"
            const val DECK_BOTTOM = "deckBottom"
            /** Looked cards not picked: back on top of the deck in the order they had, not asked ("se mantienen en el mazo"). */
            const val DECK_TOP_KEPT = "deckTopKept"
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
     * @param maxLevelSum "elige criaturas … que sumen hasta N en nivel": the levels of those picked add up to N or less; null: no limit
     */
    @JvmRecord
    data class Select(
        val selector: Selector?, val target: Boolean, val saveAs: String?, val label: String?, val endsIfNone: Boolean,
        val maxLevelSum: Int? = null,
    ) : Action {
        constructor(selector: Selector?, target: Boolean, saveAs: String?, label: String?) : this(selector, target, saveAs, label, false)

        override fun needsCards(): Boolean {
            return true
        }
    }
}
