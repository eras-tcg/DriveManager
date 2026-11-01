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

    static List<CardSlot> options(GameRoom room, PendingEffect effect, Selector.Special special) {
        SpecialRule rule = resolve(room, effect, special.rule());
        PlayerState player = room.getPlayer(effect.getControllerId());
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

    static List<CardSlot> shown(GameRoom room, PendingEffect effect, Selector.Special special) {
        PlayerState player = room.getPlayer(effect.getControllerId());
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

    /** {min, max} of the choice. */
    static int[] bounds(GameRoom room, PendingEffect effect, Selector.Special special, int available) {
        return switch (special.stage()) {
            case CARD -> new int[] {0, 1};
            case SUMMONER -> new int[] {1, 1};
            case MATERIAL -> {
                SpecialRule rule = resolve(room, effect, special.rule());
                yield Summons.specialRequirement(target(room, effect, rule)).levelSum() > 0 ? new int[] {1, available} : new int[] {1, 1};
            }
        };
    }

    static String instruction(GameRoom room, PendingEffect effect, Selector.Special special) {
        SpecialRule rule = resolve(room, effect, special.rule());
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

    /** Whether the materials picked meet the Requerimiento: an error, or null. */
    static String checkMaterials(GameRoom room, PendingEffect effect, Selector.Special special, List<String> picks) {
        if (special.stage() != Selector.Stage.MATERIAL) return null;
        SpecialRule rule = resolve(room, effect, special.rule());
        CardSlot target = target(room, effect, rule);
        CardSlot summoner = summoner(room, effect, rule);
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
        SpecialRequirement req = Summons.specialRequirement(target);
        CardSlot summoner = summoner(room, effect, rule);
        List<CardSlot> materials = new ArrayList<>();
        for (String id : effect.getSaved().getOrDefault(EngineContext.SPECIAL_MATERIALS, List.of())) {
            CardSlot material = new EngineContext(room, effect).find(id);
            if (material != null) materials.add(material);
        }
        effect.getSaved().remove(EngineContext.SPECIAL_MATERIALS);
        String from = EffectEngine.find(player.getHand(), target.getInstanceId()) != null ? "hand"
                : EffectEngine.find(player.getTributeZone(), target.getInstanceId()) != null ? "tributeZone" : rule.zone().split(",")[0].trim();
        effect.setRitualTargetId(null);
        effect.setRitualSummonerId(null);
        Summons.specialSummon(room, player, target, req, summoner, materials, from, rule.reduce(), effect);
        effect.setSummonedId(target.getInstanceId());
        effect.setLastDone(true);
    }
}
