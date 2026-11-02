package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.FloatingEffect;
import com.drive.drive_manager.simulator.GameLogEntry;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectScript.Limit;
import com.drive.drive_manager.simulator.engine.EffectStep.*;
import com.drive.drive_manager.simulator.engine.effects.model.SpecialRule;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.drive.drive_manager.simulator.engine.Summons.*;
import static com.drive.drive_manager.simulator.engine.SpecialRequirements.*;
import static com.drive.drive_manager.simulator.engine.SpecialSummons.*;
import static com.drive.drive_manager.simulator.engine.EffectEngine.*;
import static com.drive.drive_manager.simulator.engine.Removals.*;
import static com.drive.drive_manager.simulator.engine.TurnRules.*;
import static com.drive.drive_manager.simulator.engine.CombatRules.*;
import static com.drive.drive_manager.simulator.engine.Chain.*;
import static com.drive.drive_manager.simulator.engine.Resolution.*;
import static com.drive.drive_manager.simulator.engine.StepHelpers.*;
import static com.drive.drive_manager.simulator.engine.Answers.*;

/** Rituals, from the hand or by an effect. */
public final class Rituals {

    private Rituals() {}

    // ------------------------------------------------------------------ Ritual from the hand (rule 9.3)

    /**
     * Ritual summon from the hand (rule 9.3): pays the special cost and uses creatures that meet the
     * Requerimiento and share a type or color with it; the summoner is sacrificed and the creature takes its
     * place. {@code summonerId} is the creature it was dropped onto, if any; whatever is still missing is asked
     * with a prompt, and nothing is paid until it is done.
     */
    public static String ritualByPlayer(GameRoom room, String userId, String instanceId, String summonerId) {
        PlayerState who = room.getPlayer(userId);
        return summoningAs(who == null ? null : find(who.getHand(), instanceId), () -> ritualByPlayerAs(room, userId, instanceId, summonerId));
    }

    static String ritualByPlayerAs(GameRoom room, String userId, String instanceId, String summonerId) {
        String error = checkMainPhase(room, userId, "invocar");
        if (error != null) return error;
        PlayerState player = room.getPlayer(userId);
        CardSlot card = player == null ? null : find(player.getHand(), instanceId);
        if (card == null) return "La carta no está en tu mano";
        Requirement req = requirement(card);
        if (!"ritual".equalsIgnoreCase(card.getSpecialSummonKind()) || req == null || card.getSpecialCost() == null) {
            return name(card) + " no se invoca por Ritual";
        }
        if (askTap(room, player, card, "ritual", summonerId)) return null;
        int cost = ritualCost(room, userId, card);
        if (!canPay(room, userId, cost)) return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y el coste especial es " + cost;
        List<CardSlot> materials = ritualMaterials(player, card);
        String missing = "Tus criaturas no cumplen el Requerimiento de " + name(card) + ": " + card.getRequirement().trim();

        if (summonerId != null) {
            CardSlot summoner = find(player.getField(), summonerId);
            if (summoner == null) return "El invocador debe ser una criatura de tu campo";
            if (!materials.contains(summoner)) return name(summoner) + " no comparte tipo ni color con " + name(card);
            if (!req.sum()) {
                if (!req.fits(summoner)) return missing;
                handRitual(room, player, card, summoner, List.of());
                return null;
            }
            if (hasLevel(summoner, req.level())) {
                handRitual(room, player, card, summoner, List.of());
                return null;
            }
            List<CardSlot> others = materials.stream().filter(c -> c != summoner).toList();
            int remaining = req.level() - summonerLevel(summoner, others, req.level());
            if (remaining < 0 || !sumsExactly(others, remaining)) return missing;
            // Only those that fit, and no more than the levels left: once they add up, the rest are locked
            List<CardSlot> fitting = fittingSacrifices(others, remaining);
            ritualPrompt(room, player, card, summoner, fitting, "Elige las demás criaturas que se sacrifican junto a " + name(summoner)
                    + ": con el invocador, sus niveles deben sumar exactamente " + req.level() + ".", fitting.size());
            room.getPrompt().setMaxLevelSum(remaining);
            return null;
        }

        // Rule 9.2.0.13: first the summoner (it must meet part of the Requerimiento), then the rest
        List<CardSlot> valid = req.sum() ? summonerCandidates(materials, req.level())
                : materials.stream().filter(req::fits).toList();
        if (valid.isEmpty()) return missing;
        ritualPrompt(room, player, card, null, valid, "Elige el invocador" + (req.sum() ? "" : ": 1 criatura nivel " + req.level() + (req.atLeast() ? " o mayor" : ""))
                + ". Se sacrifica y " + name(card) + " ocupa su lugar y hereda su estado.", 1);
        room.getPrompt().setChosenLabel("Invocador");
        return null;
    }

    /** Whether the card can be summoned by Ritual from the hand now, for the board's legal actions. */
    public static boolean canRitual(GameRoom room, String userId, CardSlot card) {
        return summoningAs(card, () -> canRitualAs(room, userId, card));
    }

    static boolean canRitualAs(GameRoom room, String userId, CardSlot card) {
        PlayerState player = room.getPlayer(userId);
        Requirement req = requirement(card);
        if (player == null || req == null || card.getSpecialCost() == null || !"ritual".equalsIgnoreCase(card.getSpecialSummonKind())) return false;
        if (!canPay(room, userId, ritualCost(room, userId, card))) return false;
        List<CardSlot> materials = ritualMaterials(player, card);
        return req.sum() ? sumsExactly(materials, req.level()) : materials.stream().anyMatch(req::fits);
    }

    static int ritualCost(GameRoom room, String userId, CardSlot card) {
        int cost = card.getSpecialCost() - (starterAdvantage(room, userId, card, card.getSpecialCost()) ? 1 : 0);
        PlayerState player = room.getPlayer(userId);
        return player == null ? cost : Math.max(0, cost - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, "ritual", card, null));
    }

    static void ritualPrompt(GameRoom room, PlayerState player, CardSlot card, CardSlot summoner, List<CardSlot> valid,
                                     String instruction, int max) {
        rulesPrompt(room, player.getUserId(), PURPOSE_RITUAL, name(card) + " · Ritual",
                instruction + " Si no eliges ninguna, no se invoca.", "Sacrificar", valid, player.getField());
        room.getPrompt().setMax(max);
        room.getPrompt().setSubjectId(card.getInstanceId());
        if (summoner != null) room.getPrompt().setSummonerId(summoner.getInstanceId());
    }

    static void handRitual(GameRoom room, PlayerState player, CardSlot card, CardSlot summoner, List<CardSlot> others) {
        String userId = player.getUserId();
        boolean advantage = starterAdvantage(room, userId, card, card.getSpecialCost());
        int cost = Math.max(0, card.getSpecialCost() - (advantage ? 1 : 0));
        cost -= CostModifiers.applyAutomatic(room, player, CostModifiers.Event.SPECIAL_SUMMON, "ritual", card, summoner, cost);
        pay(room, userId, cost);
        if (advantage) room.setStarterAdvantageUserId(null);
        player.getHand().remove(card);
        List<String> used = new ArrayList<>(List.of(name(summoner)));
        others.forEach(o -> used.add(name(o)));
        ritualSummon(room, player, card, "hand", cost, summoner, others, null,
                player.getUsername() + " invoca por Ritual a " + name(card) + " usando " + String.join(", ", used) + " y pagando "
                        + gemsText(cost) + (advantage ? " (ventaja del iniciador)" : ""));
        advance(room);
    }

    // ------------------------------------------------------------------ Ritual by effect (rule 9.3)

    /** Requerimiento of a Ritual: one creature of level X, or several whose levels add up to X. */
    /**
     * A Ritual's Requerimiento: "criatura nivel N [o mayor] [de fuerza M o menos]" or "criaturas que [en nivel] sumen N".
     */
    record Requirement(boolean sum, int level, boolean atLeast, Integer maxStrength) {
        /** The creature alone meets a single-creature Requerimiento. */
        boolean fits(CardSlot c) {
            boolean level = levels(c).stream().anyMatch(l -> atLeast ? l >= this.level : l == this.level);
            return level && (maxStrength == null || strength(c) <= maxStrength);
        }
    }

    static Requirement requirement(CardSlot card) {
        if (card.getRequirement() == null) return null;
        String text = card.getRequirement().toLowerCase(Locale.ROOT);
        Matcher m = Pattern.compile("nivel\\s*(\\d+)|sumen\\s*(\\d+)").matcher(text);
        if (!m.find()) return null;
        int level = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
        boolean atLeast = Pattern.compile("nivel\\s*\\d+\\s*o\\s*mayor").matcher(text).find();
        Matcher strength = Pattern.compile("fuerza\\s*(\\d+)\\s*o\\s*menos").matcher(text);
        return new Requirement(text.contains("sum"), level, atLeast, strength.find() ? Integer.valueOf(strength.group(1)) : null);
    }

    /** Creatures that can be the summoner of a level-sum Ritual: the rest of the field can complete the sum exactly. */
    static List<CardSlot> summonerCandidates(List<CardSlot> materials, int level) {
        return materials.stream()
                .filter(s -> levels(s).stream().anyMatch(l -> l <= level && sumsExactly(materials.stream().filter(c -> c != s).toList(), level - l)))
                .toList();
    }

    /** Whether some of the creatures' levels add up exactly to {@code target} ("criaturas que en nivel sumen N"). */
    /**
     * The creatures that can be sacrificed with the summoner to reach exactly `remaining` levels: each one must fit
     * (its level no more than what is left) and leave a sum the others can complete.
     */
    static List<CardSlot> fittingSacrifices(List<CardSlot> others, int remaining) {
        return others.stream()
                .filter(c -> levels(c).stream().anyMatch(l -> l > 0 && l <= remaining
                        && (l == remaining || sumsExactly(others.stream().filter(o -> o != c).toList(), remaining - l))))
                .toList();
    }

    /** Some of the creatures add up exactly to {@code target}, each counting as any level it can (Abraza Almas). */
    static boolean sumsExactly(List<CardSlot> cards, int target) {
        if (target < 0) return false;
        boolean[] reachable = new boolean[target + 1];
        reachable[0] = true;
        for (CardSlot c : cards) {
            boolean[] next = reachable.clone();
            for (int level : levels(c)) {
                if (level <= 0) continue;
                for (int t = target; t >= level; t--) if (reachable[t - level]) next[t] = true;
            }
            reachable = next;
        }
        return reachable[target];
    }

    /** All these creatures together add up exactly to {@code target}, each counting as any level it can. */
    static boolean addsUpTo(List<CardSlot> cards, int target) {
        java.util.Set<Integer> sums = new java.util.HashSet<>(List.of(0));
        for (CardSlot c : cards) {
            java.util.Set<Integer> next = new java.util.HashSet<>();
            List<Integer> options = levels(c).isEmpty() ? List.of(0) : levels(c);
            for (int s : sums) for (int l : options) next.add(s + l);
            sums = next;
        }
        return sums.contains(target);
    }

    /** The level the summoner of a level-sum Ritual counts as: one the others can complete, else its own. */
    static int summonerLevel(CardSlot summoner, List<CardSlot> others, int total) {
        for (int l : levels(summoner)) if (l <= total && sumsExactly(others, total - l)) return l;
        return summoner.getLevel() == null ? 0 : summoner.getLevel();
    }

    /** Rule 9.2.0.10: the cards used must share a type or a color with the summoned creature. */
    static boolean shares(CardSlot used, CardSlot summoned) {
        boolean color = used.getColors().stream().anyMatch(summoned.getColors()::contains);
        boolean type = used.getClasses().stream().anyMatch(t -> summoned.getClasses().stream().anyMatch(t::equalsIgnoreCase));
        return color || type;
    }

    /** Creatures that may be used for the Ritual of {@code target}. */
    static List<CardSlot> ritualMaterials(PlayerState player, CardSlot target) {
        return player.getField().stream().filter(c -> "creature".equals(c.getCardType()) && c.getLevel() != null && shares(c, target)).toList();
    }

    static boolean ritualFeasible(GameRoom room, PendingEffect effect, SpecialRule ritual, PlayerState player, CardSlot target) {
        Requirement req = requirement(target);
        if (req == null || target.getSpecialCost() == null) return false;
        if (!canPay(room, player.getUserId(), Math.max(0, target.getSpecialCost() - ritual.reduce()
                - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, "ritual", target, null)))) return false;
        List<CardSlot> materials = ritualMaterials(player, target);
        if (ritual.selfSummoner()) {
            CardSlot self = find(materials, effect.getSourceInstanceId());
            if (self == null) return false;
            if (!req.sum()) return req.fits(self);
            // The summoner is one of them: the others make up the rest
            return levels(self).stream().anyMatch(l -> l <= req.level() && sumsExactly(materials.stream().filter(c -> c != self).toList(), req.level() - l));
        }
        if (!req.sum()) return materials.stream().anyMatch(req::fits);
        return sumsExactly(materials, req.level());
    }

    static List<CardSlot> ritualTargets(GameRoom room, PendingEffect effect, SpecialRule ritual) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return List.of();
        return zone(player, ritual.zone()).stream()
                .filter(c -> "creature".equals(c.getCardType()) && "ritual".equalsIgnoreCase(c.getSpecialSummonKind()))
                .filter(c -> ritual.filter() == null || ritual.filter().matches(c))
                .filter(c -> summoningAs(c, () -> ritualFeasible(room, effect, ritual, player, c)))
                .toList();
    }
}
