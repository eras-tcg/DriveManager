package com.drive.drive_manager.simulator.engine.effects.model;

import com.drive.drive_manager.simulator.engine.Amount;
import com.drive.drive_manager.simulator.engine.CardFilter;

/**
 * What an effect allows for a special summon: "[1 aliado] puede invocar por [método] 1 criatura [filtro] de tu [zona] reduciendo el
 * coste especial en N". The composition picks the creature, its summoner and its materials under this rule, then summons.
 *
 * @param kind     promotion, evolution, materialization, ritual, any, or {@link #OF_THIS_CARD}
 * @param zone     comma separated zones the creature comes from
 * @param reduce   how much the special cost goes down
 * @param summoner any, other, self, subject or level:N
 * @param filter   which creatures
 * @param reduceBy the reduction when it depends on the game; null: {@code reduce}
 * @param relative it depends on another card; null: nothing
 */
public record SpecialRule(String kind, String zone, int reduce, String summoner, CardFilter filter, Amount reduceBy, Selector.Relative relative) {

    /** The method this card itself is summoned by. */
    public static final String OF_THIS_CARD = "ofThisCard";
    public static final String RITUAL = "ritual";

    /** "esta carta invoca por Ritual…". */
    public boolean selfSummoner() {
        return "self".equals(summoner);
    }

    public SpecialRule(String kind, String zone, int reduce, String summoner, CardFilter filter) {
        this(kind, zone, reduce, summoner, filter, null, null);
    }
}
