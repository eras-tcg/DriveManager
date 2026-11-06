package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.CardEffects;
import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.engine.EffectScript;
import com.drive.drive_manager.simulator.room.CardSlot;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a card is worth to the look-ahead bot, by the weights of its {@link BotSettings}: its stats (cost,
 * strength, abilities, level, what it carries) and a bonus for its effects. The bonus does not read what an effect
 * does, only how many effects of each kind the card has (continuous, activated, each turn or on an event, on summon,
 * on play, on attack, on destruction, inherited as a Recurso): a card with a slow effect is worth more than a bare
 * one of the same stats, by as much as the tuning finds. Each card's own biases by zone are added where the card is
 * ({@link TableScore#side}).
 */
final class CardWorth {

    /** How many effects of each kind a card has, by its name (cards never change their programmed effects). */
    record Profile(int continuous, int activated, int event, int summon, int play, int attack, int destroy, int inherited) {}

    private static final Map<String, Profile> PROFILES = new ConcurrentHashMap<>();

    private CardWorth() {}

    static double of(CardSlot card, BotSettings w) {
        if (card == null) return 0;
        double v = card.getCost() == null ? 2 : card.getCost();
        if ("creature".equals(card.getCardType())) {
            int strength = EffectEngine.strength(card);
            v += strength * w.vStr + strength * strength * w.vStrSq;
            v += EffectEngine.keywords(card).size() * w.vKeyword;
            if (card.getLevel() != null) v += card.getLevel() * w.vLevel;
        }
        v += (card.getMaterials().size() + card.getResources().size()) * w.vAttached;
        Profile p = profile(card);
        return v + p.continuous() * w.eContinuous + p.activated() * w.eActivated + p.event() * w.eEvent + p.summon() * w.eSummon
                + p.play() * w.ePlay + p.attack() * w.eAttack + p.destroy() * w.eDestroy + p.inherited() * w.eInherited;
    }

    static Profile profile(CardSlot card) {
        String name = card.getCardName() == null ? "" : card.getCardName().trim().toLowerCase(Locale.ROOT);
        return PROFILES.computeIfAbsent(name, n -> {
            int activated = 0, event = 0, summon = 0, play = 0, attack = 0, destroy = 0;
            for (EffectScript e : CardEffects.of(n)) {
                switch (e.moment()) {
                    case ACTIVATED -> activated++;
                    case ON_SUMMON -> summon++;
                    case ON_PLAY -> play++;
                    case ON_ATTACK, ON_ATTACK_END -> attack++;
                    case ON_DESTROY -> destroy++;
                    default -> event++;
                }
            }
            return new Profile(CardEffects.continuousOf(card).size(), activated, event, summon, play, attack, destroy,
                    CardEffects.inheritedOf(n).size());
        });
    }
}
