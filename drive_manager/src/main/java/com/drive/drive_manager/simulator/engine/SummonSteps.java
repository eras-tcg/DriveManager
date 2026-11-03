package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.FloatingEffect;
import com.drive.drive_manager.simulator.GameLogEntry;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectScript.Limit;
import com.drive.drive_manager.simulator.engine.EffectStep.*;
import com.drive.drive_manager.simulator.engine.effects.model.Action;
import com.drive.drive_manager.simulator.engine.effects.model.SpecialRule;
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler;
import com.drive.drive_manager.simulator.engine.effects.runtime.Actions;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.drive.drive_manager.simulator.engine.EffectEngine.*;
import static com.drive.drive_manager.simulator.engine.Resolution.*;
import static com.drive.drive_manager.simulator.engine.StepHelpers.*;
import static com.drive.drive_manager.simulator.engine.Summons.*;
import static com.drive.drive_manager.simulator.engine.SpecialRequirements.*;
import static com.drive.drive_manager.simulator.engine.SpecialSummons.*;
import static com.drive.drive_manager.simulator.engine.Rituals.*;
import static com.drive.drive_manager.simulator.engine.Removals.*;
import static com.drive.drive_manager.simulator.engine.TurnRules.*;
import static com.drive.drive_manager.simulator.engine.CombatRules.*;
import static com.drive.drive_manager.simulator.engine.Chain.*;
import static com.drive.drive_manager.simulator.engine.Answers.*;

/** Summons a basic action does not cover. Each step: whether it can be done, carrying it out, and applying the player's choice. */
final class SummonSteps {

    private SummonSteps() {}

    static boolean possibleStep(GameRoom room, PendingEffect effect, EffectStep step) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        CardSlot onField = find(player.getField(), effect.getSourceInstanceId());
        return switch (step) {
            case SummonFreeLevelSum sum -> !freeLevelSum(room, effect, sum).isEmpty();
            case SpecialSelfDeclaredSummoner s -> {
                CardSlot self = find(player.getHand(), effect.getSourceInstanceId());
                SpecialRequirements.SpecialRequirement req = self == null ? null : specialRequirement(self);
                yield req != null && self.getSpecialCost() != null && !declaredFrom(player, self, req, s.zones()).isEmpty()
                        && canPay(room, player.getUserId(), self.getSpecialCost() - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind(), self, null));
            }
            case ChooseOpponentTribute c -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(player.getUserId()));
                yield opponent != null && opponent.getTributeZone().stream().anyMatch(c.filter()::matches);
            }
            default -> true;
        };
    }

    static boolean runStep(GameRoom room, PendingEffect effect, EffectStep step) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        String userId = player.getUserId();

        switch (step) {
            case SpecialSelfDeclaredSummoner s -> {
                CardSlot self = find(player.getHand(), effect.getSourceInstanceId());
                SpecialRequirements.SpecialRequirement req = self == null ? null : specialRequirement(self);
                if (req == null) return false;
                if (effect.getRitualSummonerId() == null) {
                    List<CardSlot> valid = declaredFrom(player, self, req, s.zones());
                    if (valid.isEmpty()) return false;
                    pick(room, effect, "Elige la criatura de tu " + zonesText(s.zones()) + " que invoca especialmente a " + name(self) + " (nivel " + req.level()
                            + "). Se convierte en su material.", "Invocador", zones(player, s.zones()), ids(valid), 1);
                    room.getPrompt().setMin(0);
                    return true;
                }
                CardSlot summoner = findAnywhere(room, userId, effect.getRitualSummonerId());
                List<CardSlot> materials = declaredMaterials(player, self, req, summoner);
                pick(room, effect, "Elige los materiales de " + name(self) + (req.levelSum() > 0 ? ": deben sumar exactamente " + req.levelSum() + " en nivel." : "."),
                        "Material", zones(player, String.join(",", req.materialZones())), ids(materials), materials.size());
                room.getPrompt().setMin(1);
                if (req.levelSum() > 0) room.getPrompt().setMaxLevelSum(req.levelSum());
                return true;
            }
            case MaterializeSelfOnTriggerAlly ignored -> {
                GameEvent trigger = triggerEvent(room, effect);
                Object by = trigger == null || trigger.getData() == null ? null : trigger.getData().get("byCard");
                CardSlot ally = by == null ? null : find(player.getField(), (String) by);
                CardSlot self = find(player.getHand(), effect.getSourceInstanceId());
                if (ally == null || self == null) return false;
                SpecialRequirement req = new SpecialRequirement("materialization", ally.getLevel() == null ? 0 : ally.getLevel(), 0, 0, 0, true, 0, 0, List.of(), false, null, false, false);
                int free = self.getSpecialCost() == null ? 0 : self.getSpecialCost();
                specialSummon(room, player, self, req, ally, List.of(), "hand", free, effect);
                effect.setLastDone(true);
                return false;
            }
            case ChooseOpponentTribute c -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                List<CardSlot> valid = opponent == null ? List.of() : opponent.getTributeZone().stream().filter(c.filter()::matches).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Elige 1 criatura " + c.filter().describe() + " de los tributos de tu rival.", "Elegir", opponent.getTributeZone(), ids(valid), 1);
                return true;
            }
            case PlaceChosenTributeAndSelf ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                CardSlot chosen = opponent == null || effect.getRitualTargetId() == null ? null : find(opponent.getTributeZone(), effect.getRitualTargetId());
                effect.setRitualTargetId(null);
                if (chosen != null) placeOnField(room, effect, opponent, chosen, "tributeZone", true, false);
                Located self = locate(player, effect.getSourceInstanceId());
                if (self != null && !"field".equals(self.zone())) placeOnField(room, effect, player, self.card(), self.zone(), true, false);
                return false;
            }
            case SummonLikeSubject like -> {
                CardSlot ally = subject(room, effect);
                List<CardSlot> valid = ally == null ? List.of() : likeSubject(player, like, ally);
                if (valid.isEmpty()) {
                    log(room, "No hay criaturas que " + effect.getSourceName() + " pueda invocar", null);
                    return false;
                }
                pick(room, effect, "Invoca 1 criatura " + like.filter().describe() + " nivel " + ally.getLevel() + " distinta de " + name(ally)
                                + " de tus " + zoneName(like.zone()) + " sin pagar su coste. Entra en tu línea frontal y robas 1 carta.", "Invocar",
                        zone(player, like.zone()), ids(valid), 1);
                return true;
            }
            case SummonFreeLevelSum sum -> {
                List<CardSlot> valid = freeLevelSum(room, effect, sum);
                if (valid.isEmpty()) return false;
                pick(room, effect, "Puedes invocar criaturas " + sum.filter().describe() + " de tu " + zoneName(sum.zone())
                                + " que en nivel sumen hasta " + sum.maxLevelSum() + ", sin pagar sus costes. Entran en Retaguardia y robas 1 carta por cada una.",
                        "Invocar", zone(player, sum.zone()), ids(valid), valid.size());
                room.getPrompt().setMin(0);
                room.getPrompt().setMaxLevelSum(sum.maxLevelSum());
                return true;
            }
            default -> throw new IllegalStateException("Paso de otra familia: " + step);
        }
    }

    static String applyStep(GameRoom room, PendingEffect effect, EffectStep step, PlayerState player, List<String> picks,
                            List<String> order, boolean[] repeat) {
        String userId = player.getUserId();
        switch (step) {
            case SpecialSelfDeclaredSummoner s -> {
                if (picks.isEmpty()) {
                    effect.setRitualSummonerId(null);
                    break;
                }
                CardSlot self = find(player.getHand(), effect.getSourceInstanceId());
                SpecialRequirements.SpecialRequirement req = self == null ? null : specialRequirement(self);
                if (req == null) return "Esa carta ya no está en tu mano";
                if (effect.getRitualSummonerId() == null) {
                    if (find(declaredFrom(player, self, req, s.zones()), picks.get(0)) == null) return "Esa criatura no puede ser el invocador";
                    effect.setRitualSummonerId(picks.get(0));
                    repeat[0] = true;
                    return null;
                }
                CardSlot summoner = findAnywhere(room, userId, effect.getRitualSummonerId());
                List<CardSlot> valid = declaredMaterials(player, self, req, summoner);
                List<CardSlot> materials = picks.stream().map(id -> find(valid, id)).toList();
                if (summoner == null || materials.contains(null)) return "Esa criatura ya no está disponible";
                if (req.levelSum() > 0 ? !addsUpTo(materials, req.levelSum()) : materials.size() < Math.max(1, req.materialCount()))
                    return req.levelSum() > 0 ? "Los materiales deben sumar exactamente " + req.levelSum() + " en nivel" : "Elige " + req.materialCount() + " materiales";
                if (!canPay(room, userId, self.getSpecialCost() - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind(), self, null)))
                    return "No tienes gemas suficientes";
                List<CardSlot> all = new ArrayList<>(List.of(summoner));
                all.addAll(materials);
                effect.setRitualSummonerId(null);
                specialSummon(room, player, self, req, null, all, "hand", 0, effect);
                effect.setSummonedId(self.getInstanceId());
                effect.setLastDone(true);
            }
            case ChooseOpponentTribute ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                if (opponent == null || find(opponent.getTributeZone(), picks.get(0)) == null) return "Esa carta ya no está en los tributos de tu rival";
                effect.setRitualTargetId(picks.get(0));
            }
            case SummonFreeLevelSum sum -> {
                List<CardSlot> valid = freeLevelSum(room, effect, sum);
                List<CardSlot> chosen = new ArrayList<>();
                for (String id : picks) {
                    CardSlot card = find(valid, id);
                    if (card == null) return "Ya no puedes invocar esa criatura";
                    chosen.add(card);
                }
                if (chosen.stream().mapToInt(c -> c.getLevel()).sum() > sum.maxLevelSum())
                    return "Sus niveles deben sumar " + sum.maxLevelSum() + " o menos";
                for (CardSlot card : chosen) summonByEffect(room, effect, player, card, sum.zone(), card.getCost(), false);
                effect.setLastDone(!chosen.isEmpty());
            }
            case SummonLikeSubject like -> {
                CardSlot ally = subject(room, effect);
                CardSlot card = ally == null ? null : find(likeSubject(player, like, ally), picks.get(0));
                if (card == null) return "Ya no puedes invocar esa criatura";
                summonByEffect(room, effect, player, card, like.zone(), card.getCost(), true);
                effect.setSummonedId(card.getInstanceId());
                effect.setLastDone(true);
            }
            default -> {
                return "Este paso no espera ninguna elección";
            }
        }
        return null;
    }
}
