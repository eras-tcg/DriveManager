package com.drive.drive_manager.simulator.engine.effects.model;

/**
 * A basic action effects are composed of. Plain data: what each one does is in its handler (effects/actions), one class
 * per action. See the vault: 11 Simulador/Plan de refactorización del motor.
 */
public sealed interface Action {

    /** Whether an optional effect can only be offered when this action has something to act on (rule 11.2.0.2). */
    default boolean needsCards() {
        return false;
    }

    /**
     * The player picks cards ("elige 1 enemigo", "tributa 1 carta de tu mano": the part that picks) and they are saved for the
     * next actions.
     *
     * @param selector which cards, and how many
     * @param target   the text says "elige" (rule 11.10): it makes them targets, so "no puede ser objetivo" applies. Without that
     *                 word the player still points at a card, but it is not a target (designer ruling 2026-11-01)
     * @param saveAs   the name the next actions read them by; null: {@link Memory#TARGETS} for targets, {@link Memory#LAST} otherwise
     * @param label    what is shown on a chosen card ("Al fondo"); null: "Objetivo" or "Elegida"
     */
    record Select(Selector selector, boolean target, String saveAs, String label) implements Action {
        @Override
        public boolean needsCards() {
            return true;
        }
    }
}
