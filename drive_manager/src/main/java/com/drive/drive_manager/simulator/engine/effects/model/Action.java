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
     * Cards go to a zone; the event comes from where they arrive: reaching the Tributos is being tributed, the Descarte being
     * discarded, whatever the verb (designer ruling 2026-11-01).
     *
     * @param cards    which ones; when the player picks them they are not targets (only "elige", {@link Select}, makes targets)
     * @param to        {@link #TRIBUTES}, {@link #DISCARD}, {@link #DECK_TOP}, {@link #DECK_BOTTOM}, {@link #HAND}, {@link #FRONT}, {@link #BACK},
     *                  {@link #LIFE_TOP}, or "materials:x" / "resources:x" of x: self, subject or last
     * @param random   "en orden aleatorio"; otherwise the order the player gives
     * @param label    what is shown on a chosen card; null: said by the destination
     * @param required an optional effect is only offered when there is something to move
     * @param exhausted "agotada": entering the field that way
     * @param faceDown  "bocabajo"; cards going to the deck always are
     * @param rest      looked cards: where those not picked go, in the order the player gives, in the same choice; null: they stay
     */
    record Move(Selector cards, String to, boolean random, String label, boolean required, boolean exhausted, boolean faceDown, String rest) implements Action {
        public Move(Selector cards, String to, boolean random, String label, boolean required) {
            this(cards, to, random, label, required, false, false, null);
        }

        public static final String FRONT = "frontLine";
        public static final String BACK = "backLine";
        public static final String LIFE_TOP = "lifeTop";
        public static final String TRIBUTES = "tributeZone";
        public static final String DISCARD = "discardPile";
        public static final String DECK_TOP = "deckTop";
        public static final String DECK_BOTTOM = "deckBottom";
        public static final String HAND = "hand";

        @Override
        public boolean needsCards() {
            return required;
        }
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
