package com.drive.drive_manager.simulator.engine.effects.selection;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.effects.model.Selector;
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a {@link Selector}, with the rules on what an effect may reach in one place: the Retaguardia cannot be reached by
 * the opponent's effects and a creature in the Retaguardia cannot affect enemies (rule 4.0.6.2); a card that cannot be a target
 * can still be picked by an effect that does not say "elige" (rule 11.10).
 */
public final class Selection {

    private Selection() {}

    /** The cards shown for the choice: what is in the selector's zones within reach, whether or not it can be picked. */
    public static List<CardSlot> pool(EffectContext ctx, Selector selector) {
        List<CardSlot> pool = new ArrayList<>();
        if (selector.owner() != Selector.Owner.OPPONENT) addZones(pool, ctx, ctx.player(), selector.zones(), false);
        if (selector.owner() != Selector.Owner.YOU && ctx.opponent() != null) addZones(pool, ctx, ctx.opponent(), selector.zones(), true);
        return pool;
    }

    /** The cards that can be picked now. */
    public static List<CardSlot> candidates(EffectContext ctx, Selector selector, boolean target) {
        List<CardSlot> found = new ArrayList<>();
        for (CardSlot card : pool(ctx, selector)) {
            boolean self = ctx.isSelf(card);
            if (selector.mode() == Selector.Mode.SELF && !self) continue;
            if (!selector.filter().matches(card) && !(self && selector.self() == Selector.Self.REQUIRE)) continue;
            if (selector.self() == Selector.Self.EXCLUDE && self) continue;
            if (selector.relative() != null && !relative(ctx, selector.relative(), card)) continue;
            if (ctx.isOpponents(card)) {
                // An enemy is a creature
                if (!ctx.isCreature(card)) continue;
                if (target && ctx.immune(card)) continue;
            }
            found.add(card);
        }
        return found;
    }

    private static boolean relative(EffectContext ctx, Selector.Relative r, CardSlot card) {
        if (r.level() != null && !Integer.valueOf(ctx.amount(r.level())).equals(card.getLevel())) return false;
        if (r.maxLevel() != null && (card.getLevel() == null || card.getLevel() > ctx.amount(r.maxLevel()))) return false;
        if (r.to() == null) return true;
        CardSlot other = ctx.relativeTo(r.to());
        if (other == null) return false;
        if (r.compareLevel() != null) {
            if (card.getLevel() == null) return false;
            int level = ctx.levelOf(other) + r.plus();
            boolean holds = switch (r.compareLevel()) {
                case EQUAL -> card.getLevel() == level;
                case AT_MOST -> card.getLevel() <= level;
                case LESS -> card.getLevel() < level;
                case GREATER -> card.getLevel() > level;
            };
            if (!holds) return false;
        }
        if (r.compareStrength() == Selector.Compare.LESS && ctx.strength(card) >= ctx.strength(other)) return false;
        String name = card.getCardName();
        if (r.differentName() && name != null && name.equalsIgnoreCase(other.getCardName())) return false;
        if (r.sameName() && (name == null || !name.equalsIgnoreCase(other.getCardName()))) return false;
        if (r.sharesClass()) {
            List<String> theirs = other.getClasses() == null ? List.of() : other.getClasses();
            if (card.getClasses() == null || card.getClasses().stream().noneMatch(t -> theirs.stream().anyMatch(t::equalsIgnoreCase))) return false;
        }
        return true;
    }

    private static void addZones(List<CardSlot> into, EffectContext ctx, PlayerState whose, String zones, boolean enemy) {
        for (String raw : zones.split(",")) {
            String zone = raw.trim();
            if (zone.startsWith("saved:")) {
                if (!enemy) for (String id : ctx.saved(zone.substring("saved:".length()))) {
                    CardSlot card = ctx.find(id);
                    // Rule 11.10.2: a target that left the enemy's Línea frontal is no longer valid
                    if (card != null && !(ctx.isOpponents(card) && !ctx.inFrontLine(card))) into.add(card);
                }
                continue;
            }
            if (zone.equals("clash")) {
                CardSlot card = ctx.clashing();
                // Taken once: it belongs to the opponent, whichever side is being read
                if (card != null && !into.contains(card)) into.add(card);
                continue;
            }
            if (zone.equals("thisCard")) {
                CardSlot card = enemy ? null : ctx.find(ctx.selfId());
                if (card != null && ctx.onField(card)) into.add(card);
                continue;
            }
            if (zone.equals("subjectOrSummoned")) {
                CardSlot card = enemy ? null : ctx.subjectOrSummoned();
                if (card != null) into.add(card);
                continue;
            }
            if (zone.equals("subjectOrSelf")) {
                CardSlot card = enemy ? null : ctx.subjectOrSelf();
                if (card != null) into.add(card);
                continue;
            }
            if (zone.equals("eventOnField")) {
                CardSlot card = enemy ? null : ctx.eventCardOnField();
                if (card != null && !(ctx.isOpponents(card) && !ctx.inFrontLine(card))) into.add(card);
                continue;
            }
            if (zone.equals("event")) {
                CardSlot card = enemy ? null : ctx.eventCard();
                if (card != null) into.add(card);
                continue;
            }
            if (zone.equals("looked")) {
                if (!enemy) into.addAll(ctx.looked());
                continue;
            }
            if (zone.startsWith("materials") || zone.startsWith("resources")) {
                if (!enemy) into.addAll(ctx.attached(zone));
                continue;
            }
            boolean allies = zone.equals("allies");
            boolean onField = allies || zone.equals("field") || zone.equals("frontLine") || zone.equals("backLine");
            if (!onField) {
                into.addAll(ctx.zone(whose, zone));
                continue;
            }
            // Rule 4.0.6.2: effects reach only the enemies of the Línea frontal, and only from yours
            if (enemy && ctx.inRetaguardia()) continue;
            for (CardSlot card : whose.getField()) {
                boolean front = ctx.inFrontLine(card);
                if (allies && !ctx.isCreature(card)) continue;
                if (zone.equals("frontLine") && !front) continue;
                if (zone.equals("backLine") && front) continue;
                if (enemy && !front) continue;
                into.add(card);
            }
        }
    }
}
