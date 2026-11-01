package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.effects.model.Selector;
import com.drive.drive_manager.simulator.engine.effects.model.SpecialRule;
import com.drive.drive_manager.simulator.engine.Summons.SpecialRequirement;

import java.util.ArrayList;
import java.util.List;

/** What each choice of a special summon by effect offers, under its rule. The choices themselves are plain Select actions. */
final class SpecialProcedure {

    private SpecialProcedure() {}

    /** The rule as it is now: what depends on the game, worked out. */
    static SpecialRule resolve(GameRoom room, PendingEffect effect, SpecialRule s) {
        EngineContext ctx = new EngineContext(room, effect);
        int reduce = s.reduceBy() == null ? s.reduce() : ctx.amount(s.reduceBy());
        String kind = s.kind();
        CardFilter filter = s.filter();
        if (SpecialRule.OF_THIS_CARD.equals(kind)) {
            CardSlot self = EffectEngine.findAnywhere(room, effect.getControllerId(), effect.getSourceInstanceId());
            kind = self == null || self.getSpecialSummonKind() == null ? "any" : self.getSpecialSummonKind();
            filter = CardFilter.any().named(effect.getSourceName());
        }
        if (s.relative() != null) {
            CardSlot other = ctx.relativeTo(s.relative().to());
            filter = other == null || other.getLevel() == null ? filter.withLevel(-1) : filter.withLevel(other.getLevel()).notNamed(other.getCardName());
        }
        return new SpecialRule(kind, s.zone(), reduce, s.summoner(), filter);
    }

    private static CardSlot target(GameRoom room, PendingEffect effect, SpecialRule rule) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        return effect.getRitualTargetId() == null ? null : EffectEngine.find(Summons.specialZones(player, rule), effect.getRitualTargetId());
    }

    /** "el aliado elegido", "esta carta", or the one chosen in the summoner's choice. */
    static CardSlot summoner(GameRoom room, PendingEffect effect, SpecialRule rule) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        return switch (rule.summoner()) {
            case "subject" -> Steps.subject(room, effect);
            case "self" -> EffectEngine.find(player.getField(), effect.getSourceInstanceId());
            default -> EffectEngine.find(player.getField(), effect.getRitualSummonerId());
        };
    }

    private static boolean ritual(Selector.Special special) {
        return SpecialRule.RITUAL.equals(special.rule().kind());
    }

    static List<CardSlot> options(GameRoom room, PendingEffect effect, Selector.Special special) {
        SpecialRule rule = resolve(room, effect, special.rule());
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (ritual(special)) return ritualOptions(room, effect, rule, special.stage(), player);
        if (special.stage() == Selector.Stage.CARD) {
            return effect.getRitualTargetId() != null ? List.of() : Summons.specialTargets(room, effect, rule);
        }
        CardSlot target = target(room, effect, rule);
        if (target == null) return List.of();
        SpecialRequirement req = Summons.specialRequirement(target);
        CardSlot summoner = summoner(room, effect, rule);
        if (special.stage() == Selector.Stage.SUMMONER) {
            if (summoner != null) return List.of();
            return Summons.specialSummoners(player, target, req).stream()
                    .filter(s -> Summons.summonerAllowed(rule, s, effect) && Summons.materialsPossible(player, target, req, s)).toList();
        }
        if (summoner == null || !"materialization".equals(req.kind())) return List.of();
        return Summons.specialMaterials(player, target, req, summoner);
    }

    private static List<CardSlot> ritualOptions(GameRoom room, PendingEffect effect, SpecialRule rule, Selector.Stage stage, PlayerState player) {
        if (stage == Selector.Stage.CARD) return effect.getRitualTargetId() != null ? List.of() : Summons.ritualTargets(room, effect, rule);
        CardSlot target = target(room, effect, rule);
        if (target == null) return List.of();
        Summons.Requirement req = Summons.requirement(target);
        List<CardSlot> materials = Summons.ritualMaterials(player, target);
        CardSlot summoner = summoner(room, effect, rule);
        if (stage == Selector.Stage.SUMMONER) {
            // Rule 9.2.0.13: the summoner is declared first; it must meet part of the Requerimiento
            if (summoner != null) return List.of();
            return req.sum() ? Summons.summonerCandidates(materials, req.level()) : materials.stream().filter(req::fits).toList();
        }
        if (summoner == null || !req.sum() || EffectEngine.hasLevel(summoner, req.level())) return List.of();
        return Summons.fittingSacrifices(materials.stream().filter(c -> c != summoner).toList(), remaining(player, target, req, summoner));
    }

    /** What the other creatures of a Ritual must add up to, with that summoner. */
    private static int remaining(PlayerState player, CardSlot target, Summons.Requirement req, CardSlot summoner) {
        return req.level() - Summons.summonerLevel(summoner, Summons.ritualMaterials(player, target).stream().filter(c -> c != summoner).toList(), req.level());
    }

    static List<CardSlot> shown(GameRoom room, PendingEffect effect, Selector.Special special) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (ritual(special) && special.stage() != Selector.Stage.CARD) return player.getField();
        return switch (special.stage()) {
            case CARD -> Summons.specialZones(player, special.rule());
            case SUMMONER -> player.getField();
            case MATERIAL -> {
                List<CardSlot> all = new ArrayList<>(player.getTributeZone());
                all.addAll(player.getField());
                yield all;
            }
        };
    }

    /** {min, max, most levels they may add up to (0: no limit)} of the choice. */
    static int[] bounds(GameRoom room, PendingEffect effect, Selector.Special special, int available) {
        if (ritual(special)) {
            if (special.stage() != Selector.Stage.MATERIAL) return new int[] {1, 1, 0};
            SpecialRule rule = resolve(room, effect, special.rule());
            CardSlot target = target(room, effect, rule);
            PlayerState player = room.getPlayer(effect.getControllerId());
            return new int[] {1, available, remaining(player, target, Summons.requirement(target), summoner(room, effect, rule))};
        }
        return switch (special.stage()) {
            case CARD -> new int[] {0, 1, 0};
            case SUMMONER -> new int[] {1, 1, 0};
            case MATERIAL -> {
                SpecialRule rule = resolve(room, effect, special.rule());
                yield Summons.specialRequirement(target(room, effect, rule)).levelSum() > 0 ? new int[] {1, available, 0} : new int[] {1, 1, 0};
            }
        };
    }

    static String instruction(GameRoom room, PendingEffect effect, Selector.Special special) {
        SpecialRule rule = resolve(room, effect, special.rule());
        if (ritual(special)) return ritualInstruction(room, effect, rule, special.stage());
        if (special.stage() == Selector.Stage.CARD) {
            return "Puedes elegir 1 criatura de tu " + Summons.zonesName(rule) + " para invocarla por " + Summons.kindName(rule.kind())
                    + (rule.reduce() > 0 ? " reduciendo su coste especial en " + rule.reduce() : "") + ", o ninguna.";
        }
        CardSlot target = target(room, effect, rule);
        SpecialRequirement req = Summons.specialRequirement(target);
        if (special.stage() == Selector.Stage.SUMMONER) {
            return "Elige el invocador: 1 criatura nivel " + req.level() + (req.atLeast() ? " o mayor" : "") + ". " + EffectEngine.name(target) + " ocupa su lugar y hereda su estado.";
        }
        return Summons.materialInstruction(req, target, summoner(room, effect, rule));
    }

    private static String ritualInstruction(GameRoom room, PendingEffect effect, SpecialRule rule, Selector.Stage stage) {
        if (stage == Selector.Stage.CARD) {
            return "Elige 1 criatura de tus " + Summons.zonesName(rule) + " para invocarla por Ritual pagando su coste especial reducido en " + rule.reduce() + ".";
        }
        CardSlot target = target(room, effect, rule);
        Summons.Requirement req = Summons.requirement(target);
        if (stage == Selector.Stage.SUMMONER) {
            return "Elige el invocador" + (req.sum() ? "" : ": 1 criatura nivel " + req.level() + (req.atLeast() ? " o mayor" : ""))
                    + ". Se sacrifica y " + EffectEngine.name(target) + " ocupa su lugar y hereda su estado.";
        }
        CardSlot summoner = summoner(room, effect, rule);
        return "Elige las demás criaturas que se sacrifican junto a " + EffectEngine.name(summoner) + " (el invocador): sus niveles deben sumar exactamente "
                + remaining(room.getPlayer(effect.getControllerId()), target, req, summoner) + ".";
    }

    /** Whether the materials picked meet the Requerimiento: an error, or null. */
    static String checkMaterials(GameRoom room, PendingEffect effect, Selector.Special special, List<String> picks) {
        if (special.stage() != Selector.Stage.MATERIAL) return null;
        SpecialRule rule = resolve(room, effect, special.rule());
        CardSlot target = target(room, effect, rule);
        CardSlot summoner = summoner(room, effect, rule);
        if (ritual(special)) {
            int total = summoner.getLevel() + picks.stream().map(id -> new EngineContext(room, effect).find(id)).mapToInt(CardSlot::getLevel).sum();
            Summons.Requirement req = Summons.requirement(target);
            return req.sum() && total != req.level() ? "Los niveles suman " + total + ": deben sumar exactamente " + req.level() : null;
        }
        List<CardSlot> materials = picks.stream().map(id -> new EngineContext(room, effect).find(id)).toList();
        return Summons.checkMaterials(Summons.specialRequirement(target), summoner, materials);
    }

    /** The summon, with what was chosen. Nothing when no creature was chosen. */
    static void summon(GameRoom room, PendingEffect effect, SpecialRule raw) {
        SpecialRule rule = resolve(room, effect, raw);
        PlayerState player = room.getPlayer(effect.getControllerId());
        CardSlot target = target(room, effect, rule);
        if (target == null) {
            effect.setLastDone(false);
            return;
        }
        CardSlot summoner = summoner(room, effect, rule);
        List<CardSlot> materials = new ArrayList<>();
        for (String id : effect.getSaved().getOrDefault(EngineContext.SPECIAL_MATERIALS, List.of())) {
            CardSlot material = new EngineContext(room, effect).find(id);
            if (material != null) materials.add(material);
        }
        effect.getSaved().remove(EngineContext.SPECIAL_MATERIALS);
        if (SpecialRule.RITUAL.equals(rule.kind())) {
            Summons.performRitual(room, effect, player, target, rule, summoner, materials);
            effect.setRitualSummonerId(null);
            return;
        }
        SpecialRequirement req = Summons.specialRequirement(target);
        String from = EffectEngine.find(player.getHand(), target.getInstanceId()) != null ? "hand"
                : EffectEngine.find(player.getTributeZone(), target.getInstanceId()) != null ? "tributeZone" : rule.zone().split(",")[0].trim();
        effect.setRitualTargetId(null);
        effect.setRitualSummonerId(null);
        Summons.specialSummon(room, player, target, req, summoner, materials, from, rule.reduce(), effect);
        effect.setSummonedId(target.getInstanceId());
        effect.setLastDone(true);
    }
}
