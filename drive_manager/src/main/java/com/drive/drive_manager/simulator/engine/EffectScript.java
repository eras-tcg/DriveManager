package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;

import java.util.List;

/**
 * An effect the engine runs on its own: when it triggers, whether it is mandatory, and its steps.
 *
 * @param id         stable id, e.g. "carnhaus:en-invocacion"
 * @param moment     when it triggers (see {@link Moment})
 * @param mandatory  triggered effects without "puedes" are mandatory (rule 11.7.0.3); optional ones ask the
 *                   controller before they are activated
 * @param label      the moment as printed ("En Invocación")
 * @param text       the effect text, shown while it resolves
 * @param cost       what is paid when it is activated ("puedes agotar esta carta"); an optional effect is
 *                   only offered when its cost can be paid
 * @param steps      the resolution
 * @param condition  extra condition checked when it would trigger ("si tienes 10 o más tributos [bestia]");
 *                   for {@link Moment#ON_EVENT} it is the trigger itself ("si algún aliado es destruido")
 * @param sourceZone where the card must be for an {@link Moment#ON_EVENT} effect (the field, or "hand" for "En Mano")
 * @param allyTurn   "Turno Aliado": only during its controller's turn
 * @param limit      activation limit per turn (rule 11.8)
 */
public record EffectScript(String id, Moment moment, boolean mandatory, String label, String text,
                           List<EffectStep> cost, List<EffectStep> steps,
                           Condition condition, String sourceZone, boolean allyTurn, Limit limit) {

    public EffectScript(String id, Moment moment, boolean mandatory, String label, String text, List<EffectStep> steps) {
        this(id, moment, mandatory, label, text, List.of(), steps, null, "field", false, Limit.NONE);
    }

    public EffectScript(String id, Moment moment, boolean mandatory, String label, String text, List<EffectStep> cost, List<EffectStep> steps) {
        this(id, moment, mandatory, label, text, cost, steps, null, "field", false, Limit.NONE);
    }

    public EffectScript when(Condition condition) {
        return new EffectScript(id, moment, mandatory, label, text, cost, steps, condition, sourceZone, allyTurn, limit);
    }

    public EffectScript fromZone(String zone) {
        return new EffectScript(id, moment, mandatory, label, text, cost, steps, condition, zone, allyTurn, limit);
    }

    public EffectScript onlyAllyTurn() {
        return new EffectScript(id, moment, mandatory, label, text, cost, steps, condition, sourceZone, true, limit);
    }

    public EffectScript limited(Limit limit) {
        return new EffectScript(id, moment, mandatory, label, text, cost, steps, condition, sourceZone, allyTurn, limit);
    }

    public enum Moment {
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
        ON_EVENT
    }

    /** Rule 11.8: "una vez por turno" (that copy) and "una vez por turno entre copias" (same original name). */
    public enum Limit { NONE, ONCE_PER_TURN, ONCE_PER_TURN_BETWEEN_COPIES }

    @FunctionalInterface
    public interface Condition {
        /**
         * @param event      the event that would trigger it
         * @param source     the card with the effect
         * @param controller who controls it
         */
        boolean test(GameRoom room, GameEvent event, CardSlot source, String controller);
    }
}
