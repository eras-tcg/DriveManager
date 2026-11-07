package com.drive.drive_manager.simulator.engine

/**
 * An effect the engine runs on its own: when it triggers, whether it is mandatory, and its steps.
 *
 * @param id         stable id, e.g. "carnhaus:en-invocacion"
 * @param moment     when it triggers (see [Moment])
 * @param mandatory  triggered effects without "puedes" are mandatory (rule 11.7.0.3); optional ones ask the
 *                   controller before they are activated
 * @param label      the moment as printed ("En Invocación")
 * @param text       the effect text, shown while it resolves
 * @param cost       what is paid when it is activated ("puedes agotar esta carta"); an optional effect is
 *                   only offered when its cost can be paid
 * @param steps      the resolution
 * @param condition  extra condition checked when it would trigger ("si tienes 10 o más tributos [bestia]");
 *                   for [Moment.ON_EVENT] it is the trigger itself ("si algún aliado es destruido")
 * @param sourceZone where the card must be for an [Moment.ON_EVENT] effect (the field, or "hand" for "En Mano")
 * @param allyTurn   "Turno Aliado": only during its controller's turn
 * @param limit      activation limit per turn (rule 11.8)
 */
@JvmRecord
data class EffectScript(
    val id: String?, val moment: Moment?, val mandatory: Boolean, val label: String?, val text: String?,
    val cost: List<@JvmSuppressWildcards EffectStep>?, val steps: List<@JvmSuppressWildcards EffectStep>?,
    val condition: Condition?, val sourceZone: String?, val allyTurn: Boolean, val limit: Limit?,
) {

    constructor(id: String?, moment: Moment?, mandatory: Boolean, label: String?, text: String?, steps: List<@JvmSuppressWildcards EffectStep>?) :
        this(id, moment, mandatory, label, text, java.util.List.of(), steps, null, "field", false, Limit.NONE)

    constructor(
        id: String?, moment: Moment?, mandatory: Boolean, label: String?, text: String?, cost: List<@JvmSuppressWildcards EffectStep>?,
        steps: List<@JvmSuppressWildcards EffectStep>?,
    ) : this(id, moment, mandatory, label, text, cost, steps, null, "field", false, Limit.NONE)

    fun `when`(condition: Condition?): EffectScript {
        return EffectScript(id, moment, mandatory, label, text, cost, steps, condition, sourceZone, allyTurn, limit)
    }

    fun fromZone(zone: String?): EffectScript {
        return EffectScript(id, moment, mandatory, label, text, cost, steps, condition, zone, allyTurn, limit)
    }

    fun onlyAllyTurn(): EffectScript {
        return EffectScript(id, moment, mandatory, label, text, cost, steps, condition, sourceZone, true, limit)
    }

    fun limited(limit: Limit?): EffectScript {
        return EffectScript(id, moment, mandatory, label, text, cost, steps, condition, sourceZone, allyTurn, limit)
    }

    enum class Moment {
        /** "En Invocación": when this creature is summoned, by any method (rule 11.4.1.2b). */
        ON_SUMMON,
        /** "En Destrucción": when this card is destroyed or sacrificed; it activates from the zone it was sent to. */
        ON_DESTROY,
        /** "Al Atacar": when this creature declares an attack. */
        ON_ATTACK,
        /** "Al Final Del Ataque": when a combat where this creature attacked reaches its end. */
        ON_ATTACK_END,
        /** "En Juego": when this card is played. */
        ON_PLAY,
        /** Passive moments with a trigger condition ("Todos Los Turnos: si algún aliado es destruido"). */
        ON_EVENT,
        /**
         * Passive moments without a trigger condition: slow effects, activated by the player in the open state
         * (rule 11.4.1.1), from the field unless a zone is given.
         */
        ACTIVATED,
        /** Effects that can be added to a chain being built, in response to its last effect (rule 11.3.0.3). */
        RESPONSE,
        /** "al final del turno: …": the rest of another effect of the card, triggered at the end of the turn it was set (`AtEndOfTurn`). */
        DELAYED,
    }

    /** Rule 11.8: "una vez por turno" (that copy) and "una vez por turno entre copias" (same original name). */
    enum class Limit {
        NONE, ONCE_PER_TURN, ONCE_PER_TURN_BETWEEN_COPIES,
        /** "Efecto Definitivo": a player can activate only 1 of them per turn, whatever card it is. */
        DEFINITIVE,
        /** "Solo puedes activar 1 efecto de «X» por turno": one of the card's effects, whichever, between copies. */
        ONE_EFFECT_OF_NAME_PER_TURN,
    }
}
