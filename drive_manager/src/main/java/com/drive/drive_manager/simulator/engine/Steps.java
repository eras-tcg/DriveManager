package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.FloatingEffect;
import com.drive.drive_manager.simulator.GameLogEntry;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectScript.Limit;
import com.drive.drive_manager.simulator.engine.EffectStep.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.drive.drive_manager.simulator.engine.EffectEngine.*;
import static com.drive.drive_manager.simulator.engine.Summons.*;
import static com.drive.drive_manager.simulator.engine.Removals.*;
import static com.drive.drive_manager.simulator.engine.TurnRules.*;
import static com.drive.drive_manager.simulator.engine.CombatRules.*;
import static com.drive.drive_manager.simulator.engine.Chain.*;
import static com.drive.drive_manager.simulator.engine.Answers.*;

/**
 * Resolution (rule 11.6): everything about each step of an effect in one place: whether it can be done ({@code possible}),
 * carrying it out ({@code run}) and applying the player's choice when it asks for one ({@code apply}). A new step is a
 * record in {@link EffectStep} plus its cases here.
 */
public final class Steps {

    private Steps() {}

    // ------------------------------------------------------------------ Resolution

    /** Runs the next step of the last effect in the chain, or finishes it. */
    static void stepTop(GameRoom room) {
        PendingEffect effect = room.getChain().get(room.getChain().size() - 1);
        if (effect.getResolutionId() == null) effect.setResolutionId(room.nextId("res"));
        EffectScript script = script(effect);
        List<EffectStep> steps = script.steps();
        if (effect.getStepIndex() >= steps.size()) {
            record(room, GameEvent.EFFECT_RESOLVED, effect.getControllerId(), null, null, null, effect, null,
                    "Se resuelve " + (effect.getLink() > 0 ? "la Cadena " + effect.getLink() + ": " : "") + effect.getLabel() + " de " + effect.getSourceName());
            room.getChain().remove(room.getChain().size() - 1);
            if (script.moment() == EffectScript.Moment.ON_PLAY) utilityToDiscard(room, effect);
            return;
        }
        boolean waiting = run(room, effect, steps.get(effect.getStepIndex()));
        if (!waiting) effect.setStepIndex(effect.getStepIndex() + 1);
    }

    /** Rule 6.2.3.1: a utility goes to the Descarte once its effect has resolved. */
    static void utilityToDiscard(GameRoom room, PendingEffect effect) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        CardSlot card = player == null ? null : find(player.getField(), effect.getSourceInstanceId());
        if (card == null || !"utility".equals(card.getCardType()) || effect.isKeepOnField()) return;
        player.getField().remove(card);
        leaveField(card);
        player.getDiscardPile().add(card);
        record(room, GameEvent.SEND, player.getUserId(), card, "field", "discardPile", null, null,
                name(card) + " va al descarte tras resolverse");
    }

    /**
     * "puedes hacer A para hacer B": A, the step right after IfPossible (from {@code from}), can be done now. A is paid for B and
     * can be paid even when B would do nothing (designer ruling 2026-10-30, Sombra Errante).
     */
    static boolean allPossibleAfterIfPossible(GameRoom room, PendingEffect effect, List<EffectStep> steps, int from) {
        for (int i = from; i < steps.size(); i++) {
            if (!(steps.get(i) instanceof IfPossible)) continue;
            return i + 1 >= steps.size() || possible(room, effect, steps.get(i + 1));
        }
        return true;
    }

    /** The enemy of the strength reduction that triggered the effect, still on the field with some strength. */
    static CardSlot triggerEnemy(GameRoom room, PendingEffect effect) {
        GameEvent trigger = triggerEvent(room, effect);
        PlayerState opponent = room.getPlayer(room.getOpponentId(effect.getControllerId()));
        CardSlot target = trigger == null || opponent == null ? null : find(opponent.getField(), trigger.getCardInstanceId());
        return target != null && strength(target) > 0 ? target : null;
    }

    /** A step whose details depend on the game, as it is now ({@link ChooseEnemyBy} → {@link ChooseEnemy}). */
    static EffectStep resolved(GameRoom room, PendingEffect effect, EffectStep step) {
        if (step instanceof ChooseEnemyBy by) return new ChooseEnemy(by.count(), by.filter().of(room, effect.getControllerId()), by.upTo());
        if (step instanceof SummonFromZoneBy by) return new SummonFromZone(by.zone(), by.filter().of(room, effect.getControllerId()), by.reduce());
        if (step instanceof LookTopBy by) return new LookTop(by.count().of(room, effect.getControllerId()));
        if (step instanceof ChooseEnemiesPerCount per) return new ChooseEnemy(effect.getLastCount(), per.filter(), false);
        if (step instanceof ReduceChosenPerCount per) return new ReduceChosen(per.each() * effect.getLastCount(), false);
        if (step instanceof SpecialBySubjectLevel s) {
            CardSlot ally = subject(room, effect);
            return new SpecialFromZone(s.kind(), s.zone(), ally == null || ally.getLevel() == null ? 0 : ally.getLevel(), "subject", s.filter());
        }
        if (step instanceof SpecialByCount s) return new SpecialFromZone(s.kind(), s.zone(), effect.getLastCount(), s.summoner(), s.filter());
        if (step instanceof SpecialSelf s) {
            CardSlot self = findAnywhere(room, effect.getControllerId(), effect.getSourceInstanceId());
            String kind = self == null || self.getSpecialSummonKind() == null ? "any" : self.getSpecialSummonKind();
            return new SpecialFromZone(kind, s.zone(), s.reduce(), "any", CardFilter.any().named(effect.getSourceName()));
        }
        if (step instanceof RitualFromZoneBy r) return new RitualFromZone(r.zone(), r.selfSummoner(), r.reduce().of(room, effect.getControllerId()), r.filter());
        if (step instanceof ChooseEnemyUpToSubjectLevel c) {
            // Before the ally is chosen (is the effect possible at all?): any enemy
            if (effect.getSubjectId() == null) return new ChooseEnemy(1, CardFilter.creatures());
            CardSlot ally = subject(room, effect);
            int level = ally == null || ally.getLevel() == null ? 0 : ally.getLevel();
            return new ChooseEnemy(1, CardFilter.creatures().withMaxLevel(c.lower() ? level - 1 : level));
        }
        if (step instanceof SummonRelativeToLast s) {
            CardSlot last = effect.getLastTributed().isEmpty() ? null : findInRoom(room, effect.getLastTributed().get(0));
            int level = last == null || last.getLevel() == null ? 0 : last.getLevel();
            CardFilter filter = "lower".equals(s.relation()) ? s.filter().withMaxLevel(level - 1)
                    : s.filter().withMaxLevel(level).notNamed(last == null ? null : last.getCardName());
            return new SummonFromZone(s.zone(), filter, s.reduce(), s.toFront());
        }
        if (step instanceof PlayFromZoneByCount p) return new PlayFromZone(p.zone(), p.filter(), p.each() * effect.getLastCount());
        if (step instanceof ChooseEnemiesPerCountEvery c) return new ChooseEnemy(effect.getLastCount() / c.per(), c.filter(), false);
        if (step instanceof SpecialRelativeToLast s) {
            CardSlot last = effect.getLastTributed().isEmpty() ? null : findInRoom(room, effect.getLastTributed().get(0));
            CardFilter filter = last == null || last.getLevel() == null ? s.filter().withLevel(-1) : s.filter().withLevel(last.getLevel()).notNamed(last.getCardName());
            return new SpecialFromZone("any", s.zone(), s.reduce(), "any", filter);
        }
        if (step instanceof ChooseEnemyUpToSummonUsed) {
            GameEvent trigger = triggerEvent(room, effect);
            Object used = trigger == null || trigger.getData() == null ? null : trigger.getData().get("used");
            return new ChooseEnemy(1, CardFilter.creatures().withMaxLevel(used instanceof Number n ? n.intValue() : 0));
        }
        if (step instanceof SummonLevelLastCount s) return new SummonFromZone(s.zone(), s.filter().withLevel(effect.getLastCount()), SummonFromZone.FREE);
        return step;
    }

    /** Whether a step can do anything now (used to offer optional effects and "puedes" choices). */
    static boolean possible(GameRoom room, PendingEffect effect, EffectStep step) {
        return effect.getRitualTargetId() != null ? summoningAs(looker(room, effect), () -> possibleAs(room, effect, step))
                : lookingAs(looker(room, effect), () -> possibleAs(room, effect, step));
    }

    /** The card whose rules read the table while this effect works: the creature it is special summoning, or its own card. */
    static CardSlot looker(GameRoom room, PendingEffect effect) {
        CardSlot target = effect.getRitualTargetId() == null ? null : findAnywhere(room, effect.getControllerId(), effect.getRitualTargetId());
        return target != null ? target : findAnywhere(room, effect.getControllerId(), effect.getSourceInstanceId());
    }

    static boolean possibleAs(GameRoom room, PendingEffect effect, EffectStep step) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return false;
        step = resolved(room, effect, step);
        CardSlot onField = find(player.getField(), effect.getSourceInstanceId());
        return switch (step) {
            case DestroyEnemy destroy -> !enemyTargets(room, effect, destroy.filter()).isEmpty();
            // "elige hasta N": choosing none is allowed, so it never stops the effect
            case ChooseEnemy choose -> choose.upTo() || enemyTargets(room, effect, choose.filter()).size() >= choose.count();
            case TributeTop ignored -> !player.getDeck().isEmpty();
            case TributeMaterials tm -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                yield self != null && self.getMaterials().size() >= (tm.upTo() ? 1 : tm.count());
            }
            case MaterialToLife ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                yield self != null && !self.getMaterials().isEmpty();
            }
            case MaterialToFront mf -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                yield self != null && self.getMaterials().stream().anyMatch(m -> materialToFront(mf, m));
            }
            case SummonSelfFromZone ss -> {
                CardSlot self = find(zone(player, ss.zone()), effect.getSourceInstanceId());
                yield self != null && self.getCost() != null && canPay(room, player.getUserId(), Math.max(0, self.getCost() - ss.reduce()));
            }
            case TributeFromHand tribute -> player.getHand().stream()
                    .filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId())).count() >= tribute.count();
            case TargetAllEnemies ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(player.getUserId()));
                yield opponent != null && frontLine(opponent.getField()).stream().anyMatch(c -> "creature".equals(c.getCardType()));
            }
            case AttachSelfToSubject ignored -> {
                Located at = locate(player, effect.getSourceInstanceId());
                yield at != null && ("field".equals(at.zone()) || "tributeZone".equals(at.zone()));
            }
            case AttachLowerFromTributes attach -> {
                GameEvent trigger = triggerEvent(room, effect);
                CardSlot ally = trigger == null ? null : find(player.getField(), trigger.getCardInstanceId());
                yield ally != null && ally.getLevel() != null && player.getTributeZone().stream()
                        .anyMatch(c -> attach.filter().matches(c) && c.getLevel() != null && c.getLevel() < ally.getLevel());
            }
            case ReduceTriggerFurther ignored -> triggerEnemy(room, effect) != null;
            case ReduceTriggerToZero ignored -> triggerEnemy(room, effect) != null;
            case ToDeckTop top -> !zone(player, top.zone()).isEmpty();
            case TributeResource tr -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                yield self != null && self.getResources().stream().anyMatch(r -> tr.name().equalsIgnoreCase(r.getCardName()));
            }
            case DestroyFaceUpLife ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(effect.getControllerId()));
                yield opponent != null && opponent.getLifeStack().stream().anyMatch(l -> !l.isFaceDown());
            }
            case PlaySelfFromZone play -> find(zone(player, play.zone()), effect.getSourceInstanceId()) != null;
            case SummonFromZone summon -> !summonable(room, effect, summon).isEmpty();
            case SummonSelfFree ignored -> {
                Located at = locate(player, effect.getSourceInstanceId());
                yield at != null && !"field".equals(at.zone()) && "creature".equals(at.card().getCardType()) && at.card().getCost() != null;
            }
            case SpecialFromZone special -> !specialTargets(room, effect, special).isEmpty();
            case TakeFromZone take -> zones(player, take.zone()).stream().anyMatch(take.filter()::matches);
            case RitualFromZone ritual -> !ritualTargets(room, effect, ritual).isEmpty();
            case SendSelfTo send -> {
                Located at = locate(player, effect.getSourceInstanceId());
                yield at != null && !at.zone().equals(send.zone()) && !"field".equals(at.zone());
            }
            case SendSelfFromFieldTo ignored -> onField != null;
            case TributesToDeckBottomForKeyword d -> player.getTributeZone().stream().filter(d.filter()::matches).count() >= d.count();
            case TributeAlliesLevelSum t -> {
                List<CardSlot> valid = allies(player).stream().filter(t.filter()::matches).toList();
                yield valid.size() >= t.minCount() && valid.stream().mapToInt(c -> c.getLevel() == null ? 0 : c.getLevel()).sum() >= t.minSum();
            }
            case PlaceFromHandUntilTurnEnd p -> player.getHand().stream().anyMatch(p.filter()::matches);
            case TributeAllyMaterialWhere t -> !allyMaterialsWhere(player, t).isEmpty();
            case SummonFreeLevelSum sum -> !freeLevelSum(room, effect, sum).isEmpty();
            case SendFromZoneToDeckBottom send -> zones(player, send.zone()).stream().filter(send.filter()::matches).count() >= send.count();
            case TapSelf ignored -> onField != null && !onField.isTapped();
            case SacrificeSelf ignored -> onField != null;
            case SacrificeAlly sacrifice -> !sacrificable(effect, player, sacrifice).isEmpty();
            case UntapAlly untap -> allies(player).stream().anyMatch(c -> reactivable(c) && untap.filter().matches(c));
            case PickAlly pick -> !pickable(room, effect, player, pick).isEmpty();
            case MoveSelfToFront ignored -> onField != null && !inFrontLine(onField);
            case PlaySelfFree ignored -> {
                CardSlot self = find(player.getHand(), effect.getSourceInstanceId());
                yield self != null && Chain.cannotActivateOnPlay(room, player.getUserId(), self) == null;
            }
            case ChooseWeakestEnemy weakest -> !enemyTargets(room, effect, weakest.filter()).isEmpty();
            case SummonSelf ss -> {
                CardSlot self = find(zone(player, ss.zone()), effect.getSourceInstanceId());
                yield self != null && "creature".equals(self.getCardType()) && self.getCost() != null
                        && canPay(room, player.getUserId(), Math.max(0, self.getCost() - ss.reduce().of(room, player.getUserId())));
            }
            case SacrificeAllyWhere sacrifice -> allies(player).stream().anyMatch(sacrifice.filter()::matches);
            case TributeAlly tribute -> allies(player).stream().anyMatch(tribute.filter()::matches);
            case TributeAllyMaterial ignored -> !allyMaterials(player).isEmpty();
            case PickAllyWhere pick -> !pickableWhere(room, effect, player, pick).isEmpty();
            case MaterialsToHand ignored -> onField != null && !onField.getMaterials().isEmpty();
            case SelfToLife ignored -> onField != null;
            case MaterialToBack back -> onField != null && onField.getMaterials().stream()
                    .anyMatch(m -> "creature".equals(m.getCardType()) && back.filter().matches(m));
            case PlaceSelfOnField place -> find(zone(player, place.zone()), effect.getSourceInstanceId()) != null;
            case PlayFromZone play -> !playable(room, effect, play).isEmpty();
            case AddResourceFrom add -> !resourceCandidates(room, effect, add).isEmpty();
            case ReturnSelfToHand ignored -> onField != null;
            case UntapStructure untap -> player.getField().stream().anyMatch(c -> "structure".equals(c.getCardType()) && c.isTapped()
                    && untap.filter().matches(c));
            case TributeResources tr -> onField != null && onField.getResources().stream().filter(tr.filter()::matches).count() >= tr.count();
            case ChooseCreatureOnField choose -> choose.upTo() || !creaturesOnField(room, effect, choose.filter()).isEmpty();
            case PickAllyUpToSacrificedLevel pick -> allies(player).stream().anyMatch(EffectEngine::inFrontLine);
            case PickAllyWithHigherAlly ignored -> !withHigherAlly(player).isEmpty();
            case PickAllyWithMaterials pick -> allies(player).stream().anyMatch(c -> c.getMaterials().size() >= pick.count());
            case ExtraHit ignored -> room.getCombat() != null && room.getCombat().getStep() == Combat.Step.DAMAGE;
            case PlaceSelfInFrontLine place -> find(zone(player, place.zone()), effect.getSourceInstanceId()) != null;

            case SpecialSelfDeclaredSummoner s -> {
                CardSlot self = find(player.getHand(), effect.getSourceInstanceId());
                Summons.SpecialRequirement req = self == null ? null : specialRequirement(self);
                yield req != null && self.getSpecialCost() != null && !declaredFrom(player, self, req, s.zones()).isEmpty()
                        && canPay(room, player.getUserId(), self.getSpecialCost() - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind(), self, null));
            }
            case MaterialToFrontLine m -> onField != null && onField.getMaterials().stream().anyMatch(c -> "creature".equals(c.getCardType()) && m.filter().matches(c));
            case TributeNamedFromDiscard t -> lastNamed(player.getDiscardPile(), t.name()) != null;
            case PlaceSelf p -> find(zone(player, p.zone()), effect.getSourceInstanceId()) != null;
            case SacrificeAllyWeakerThanSelf ignored -> onField != null && allies(player).stream().anyMatch(c -> c != onField && strength(c) < strength(onField));
            case TapOwnCard t -> player.getField().stream().anyMatch(c -> !c.isTapped() && !c.getInstanceId().equals(effect.getSourceInstanceId())
                    && t.filter().matches(c) && !unaffected(c, effect));
            case AddAllyAsResource a -> onField != null && allies(player).stream()
                    .anyMatch(c -> !c.getInstanceId().equals(effect.getSourceInstanceId()) && a.filter().matches(c));
            case PlaceFromZone p -> zones(player, p.zone()).stream()
                    .anyMatch(c -> !c.getInstanceId().equals(effect.getSourceInstanceId()) && p.filter().matches(c));
            case RevealFromHand r -> assignable(player.getHand().stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId())).toList(),
                    r.filters());
            case PickAttacker p -> !attackers(player, p.filter()).isEmpty();
            case TributeFromHandWhere t -> player.getHand().stream()
                    .filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId()) && t.filter().matches(c)).count() >= t.count();
            case DestroyAlly d -> allies(player).stream().anyMatch(d.filter()::matches);
            case TributeAllyResources t -> allies(player).stream().anyMatch(a -> a.getResources().stream().anyMatch(t.filter()::matches));
            case AddResourceToHigherAlly a -> !higherAllyResources(player, a).isEmpty();
            case ChooseEnemiesLevelSum s -> levelSumPossible(enemyTargets(room, effect, CardFilter.creatures()), s.sum());
            case SummonUpTo s -> !summonable(room, effect, new SummonFromZone(s.zone(), s.filter(), s.reduce())).isEmpty();
            case ChooseFrontLineCreatures c -> frontLineCreatures(room, effect, c.filter()).size() >= c.count();
            case ChooseWeakerThanSelf w -> w.upTo() || !weakerThanSelf(room, effect).isEmpty();
            case ReturnOwnCardToHand r -> player.getField().stream().anyMatch(c -> !c.getInstanceId().equals(effect.getSourceInstanceId()) && r.filter().matches(c)
                    && !unaffected(c, effect));
            case TributeOwnCard t -> player.getField().stream().anyMatch(c -> !c.getInstanceId().equals(effect.getSourceInstanceId()) && t.filter().matches(c)
                    && !unaffected(c, effect));
            case SummonResource s -> onField != null && onField.getResources().stream().anyMatch(r -> "creature".equals(r.getCardType())
                    && r.getCost() != null && canPay(room, player.getUserId(), Math.max(0, r.getCost() - s.reduce())));
            case HandToDeckBottom h -> player.getHand().stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId())).count() >= h.count();
            case AttachSelfAsResourceToAlly ignored -> {
                Located at = locate(player, effect.getSourceInstanceId());
                yield at != null && allies(player).stream().anyMatch(c -> c != at.card());
            }
            case ChooseOpponentTribute c -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(player.getUserId()));
                yield opponent != null && opponent.getTributeZone().stream().anyMatch(c.filter()::matches);
            }
            case SummonSelfPerCount s -> find(zone(player, s.zone()), effect.getSourceInstanceId()) != null;
            case PlaySelfReduced p -> {
                CardSlot self = find(zone(player, p.zone()), effect.getSourceInstanceId());
                yield self != null && Chain.cannotActivateOnPlay(room, player.getUserId(), self) == null
                        && canPay(room, player.getUserId(), Math.max(0, (self.getCost() == null ? 0 : self.getCost()) - p.reduce()));
            }
            case SendCopiesFromField s -> copiesInBack(player, effect).size() >= s.count();
            case TributeTopLife ignored -> !player.getLifeStack().isEmpty();
            case TributeFromHandOrMaterials t -> !player.getHand().isEmpty() || (onField != null && !onField.getMaterials().isEmpty());
            case DiscardMaterials d -> onField != null && onField.getMaterials().size() >= d.count();
            case TributeMaterialWhere t -> onField != null && onField.getMaterials().stream().anyMatch(t.filter()::matches);
            case SelfToLifeFrom ignored -> locate(player, effect.getSourceInstanceId()) != null;
            case SendSelfToDeckBottom ignored -> {
                Located at = locate(player, effect.getSourceInstanceId());
                yield at != null && !"deck".equals(at.zone());
            }
            case DiscardFromHand d -> player.getHand().stream().anyMatch(c -> !c.getInstanceId().equals(effect.getSourceInstanceId()));
            case TapCopiesUpTo ignored -> onField != null && !onField.isTapped();
            case SendFromZoneToDeckBottomUpTo s -> zones(player, s.zone()).stream().anyMatch(s.filter()::matches);
            case TributeFromZoneUpTo t -> true;
            case ZoneToLife z -> zones(player, z.zone()).stream().anyMatch(c -> !c.getInstanceId().equals(effect.getSourceInstanceId()) && z.filter().matches(c));
            default -> true;
        };
    }

    /** Runs one step. Returns true when it is now waiting for the player's choice. */
    static boolean run(GameRoom room, PendingEffect effect, EffectStep step) {
        return effect.getRitualTargetId() != null ? summoningAs(looker(room, effect), () -> runAs(room, effect, step))
                : lookingAs(looker(room, effect), () -> runAs(room, effect, step));
    }

    static boolean runAs(GameRoom room, PendingEffect effect, EffectStep step) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return false;
        String userId = player.getUserId();
        step = resolved(room, effect, step);

        switch (step) {

            case ChooseEnemyUpToSummonUsed ignored -> {
                return false; // worked out as ChooseEnemy
            }
            case ChooseEnemiesPerCountEvery ignored -> {
                return false; // worked out as ChooseEnemy
            }
            case SpecialSelfDeclaredSummoner s -> {
                CardSlot self = find(player.getHand(), effect.getSourceInstanceId());
                Summons.SpecialRequirement req = self == null ? null : specialRequirement(self);
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
            case SpecialRelativeToLast ignored -> {
                return false; // worked out as SpecialFromZone
            }
            case TakeFromLookedEachRestToBottom take -> {
                if (effect.getLooked().isEmpty()) return false;
                List<String> valid = effect.getLooked().stream().filter(c -> take.filters().stream().anyMatch(f -> f.matches(c))).map(CardSlot::getInstanceId).toList();
                Prompt prompt = prompt(room, effect, Prompt.Kind.TAKE_REVEALED_REST_BOTTOM, "Toma " + String.join(" y ",
                        take.filters().stream().map(f -> "hasta 1 " + f.describe()).toList()) + " para tu mano. Ordena el resto: irá al fondo de tu mazo.");
                prompt.setCards(new ArrayList<>(effect.getLooked()));
                prompt.setValidIds(new ArrayList<>(valid));
                prompt.setMin(0);
                prompt.setMax(Math.min(take.filters().size(), valid.size()));
                prompt.setReorderRest(true);
                prompt.setChosenLabel("A tu mano");
                room.setPrompt(prompt);
                return true;
            }
            case MaterialToFrontLine m -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                List<CardSlot> valid = self == null ? List.of() : self.getMaterials().stream().filter(c -> "creature".equals(c.getCardType()) && m.filter().matches(c)).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Coloca 1 " + m.filter().describe() + " de los materiales de " + name(self) + " en tu línea frontal.", "A la línea frontal",
                        self.getMaterials(), ids(valid), 1);
                return true;
            }
            case MaterialOfExhaustedAllyToBack m -> {
                List<CardSlot> valid = exhaustedAllyMaterials(player, m.filter());
                if (valid.isEmpty()) return false;
                pick(room, effect, "Puedes colocar 1 " + m.filter().describe() + " de los materiales de un aliado agotado en tu retaguardia.", "Colocar",
                        valid, ids(valid), 1);
                room.getPrompt().setMin(0);
                return true;
            }
            case ReduceOpponentLivesBy r -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                int amount = r.amount().of(room, userId);
                if (opponent == null || amount <= 0) return false;
                for (CardSlot life : opponent.getLifeStack()) {
                    if (life.isFaceDown()) continue;
                    room.getUsedThisTurn().add(LIFE_REDUCED + life.getInstanceId() + "|" + amount);
                    log(room, "La vida " + name(life) + " de " + opponent.getUsername() + " pierde " + amount + " de fuerza hasta el final del turno ("
                            + effect.getSourceName() + ")", null);
                }
                return false;
            }
            case ReduceClashOpponent r -> {
                CardSlot other = clashOpponent(room, effect);
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                int amount = r.amount().of(room, userId);
                if (other != null && amount > 0) reduceStrength(room, effect, opponent, other, amount, false);
                return false;
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
            case ReduceChosenBy r -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                int amount = r.amount().of(room, userId);
                if (amount <= 0) return false;
                for (CardSlot target : validTargets(room, effect, opponent)) reduceStrength(room, effect, opponent, target, amount, false);
                return false;
            }
            case ReturnSubjectAtAttackEnd ignored -> {
                CardSlot ally = subject(room, effect);
                if (ally == null || room.getCombat() == null) return false;
                room.getUsedThisTurn().add(RETURN_AT_ATTACK_END + userId + "|" + ally.getInstanceId());
                return false;
            }
            case ReturnSubjectWithMaterials ignored -> {
                CardSlot ally = subject(room, effect);
                if (ally == null) return false;
                List<CardSlot> materials = new ArrayList<>(ally.getMaterials());
                ally.getMaterials().clear();
                removeFromField(player, ally);
                player.getHand().add(ally);
                for (CardSlot m : materials) {
                    m.setFaceDown(false);
                    player.getHand().add(m);
                }
                effect.setLastTributed(new ArrayList<>(List.of(ally.getInstanceId())));
                effect.setLastDone(true);
                record(room, GameEvent.SEND, userId, ally, "field", "hand", effect, null,
                        player.getUsername() + " sube " + name(ally) + (materials.isEmpty() ? "" : " y sus " + materials.size() + " materiales") + " a su mano");
                return false;
            }
            case TributeSubjectMaterialsUpTo t -> {
                CardSlot ally = subject(room, effect);
                effect.setLastCount(0);
                if (ally == null || ally.getMaterials().isEmpty()) return false;
                pick(room, effect, "Tributa hasta " + t.max() + " materiales de " + name(ally) + ".", "Tributar", ally.getMaterials(), ids(ally.getMaterials()),
                        Math.min(t.max(), ally.getMaterials().size()));
                room.getPrompt().setMin(1);
                return true;
            }
            case TributeNamedFromDiscard t -> {
                CardSlot card = lastNamed(player.getDiscardPile(), t.name());
                if (card == null) return false;
                player.getDiscardPile().remove(card);
                card.setFaceDown(false);
                player.getTributeZone().add(card);
                effect.setLastDone(true);
                record(room, GameEvent.TRIBUTE, userId, card, "discardPile", "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                        player.getUsername() + " tributa " + name(card) + " (" + effect.getSourceName() + ")");
                return false;
            }
            case PlaceSelf place -> {
                CardSlot self = find(zone(player, place.zone()), effect.getSourceInstanceId());
                if (self == null) return false;
                placeOnField(room, effect, player, self, place.zone(), place.toFront(), place.exhausted());
                effect.setLastDone(true);
                return false;
            }
            case DeclareBlockSelf ignored -> {
                CardSlot self = subject(room, effect);
                Combat c = room.getCombat();
                if (self == null || c == null || c.getBlockerId() != null || !player.getUserId().equals(c.getDefenderId())) return false;
                c.setBlockerId(self.getInstanceId());
                record(room, GameEvent.BLOCK, userId, self, null, null, effect, null, name(self) + " declara bloqueo (" + effect.getSourceName() + ")");
                return false;
            }
            case SacrificeAllyWeakerThanSelf ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                List<CardSlot> valid = self == null ? List.of() : allies(player).stream().filter(c -> c != self && strength(c) < strength(self)).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Sacrifica 1 aliado con fuerza menor a " + name(self) + ".", "Sacrificar", player.getField(), ids(valid), 1);
                return true;
            }
            case TapSummoned ignored -> {
                CardSlot summoned = find(player.getField(), effect.getSummonedId());
                if (summoned == null || summoned.isTapped()) return false;
                summoned.setTapped(true);
                record(room, GameEvent.TAP, userId, summoned, null, null, effect, null, name(summoned) + " queda agotada (" + effect.getSourceName() + ")");
                return false;
            }
            case EachPlayerKeepsOneInFrontLine k -> {
                if (effect.getRepeatsDone() >= 2) {
                    effect.setRepeatsDone(0);
                    for (PlayerState p : room.getPlayers().values()) {
                        for (CardSlot c : new ArrayList<>(frontLine(p.getField()))) {
                            if ("creature".equals(c.getCardType()) && !effect.getTargetIds().contains(c.getInstanceId()))
                                destroy(room, c, p, true, CAUSE_EFFECT, userId, effect.getSourceInstanceId(), effect);
                        }
                    }
                    return false;
                }
                PlayerState who = effect.getRepeatsDone() == 0 ? player : room.getPlayer(room.getOpponentId(userId));
                List<CardSlot> mine = who == null ? List.of() : frontLine(who.getField()).stream().filter(c -> "creature".equals(c.getCardType())).toList();
                if (mine.isEmpty()) {
                    effect.setRepeatsDone(effect.getRepeatsDone() + 1);
                    return run(room, effect, step);
                }
                pick(room, effect, "Elige 1 de tus aliados de tu línea frontal: las otras criaturas de tu línea frontal se sacrifican (" + effect.getSourceName() + ").",
                        "Conservar", who.getField(), ids(mine), 1);
                room.getPrompt().setPlayerId(who.getUserId());
                return true;
            }
            case EachPlayerLowersMarker l -> {
                for (PlayerState p : room.getPlayers().values()) {
                    p.setResourceCount(Math.max(0, p.getResourceCount() - l.count()));
                    log(room, p.getUsername() + " reduce en " + l.count() + " su marcador: queda en " + p.getResourceCount() + " (" + effect.getSourceName() + ")", null);
                }
                return false;
            }
            case GroupUp g -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                List<CardSlot> valid = self == null ? List.of() : allies(player).stream().filter(c -> c != self && !c.isTapped()
                        && !keywords(c).contains(CANNOT_TAP)).toList();
                if (valid.isEmpty() || room.getCombat() == null) return false;
                pick(room, effect, "Agrupar: puedes agotar hasta " + g.allies() + " " + plural(g.allies(), "aliado", "aliados") + "; " + name(self)
                        + " gana su fuerza durante el ataque e inflige daño 1 vez extra por cada uno.", "Agotar", player.getField(), ids(valid), Math.min(g.allies(), valid.size()));
                room.getPrompt().setMin(0);
                return true;
            }
            case UntapAllySameLevelAsLast ignored -> {
                CardSlot last = effect.getLastTributed().isEmpty() ? null : findAnywhere(room, userId, effect.getLastTributed().get(0));
                List<CardSlot> valid = last == null || last.getLevel() == null ? List.of()
                        : allies(player).stream().filter(c -> reactivable(c) && last.getLevel().equals(c.getLevel())).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Elige 1 aliado nivel " + last.getLevel() + "; se reactiva.", "Reactivar", player.getField(), ids(valid), 1);
                return true;
            }
            case ReduceChosenUntilAttack r -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                for (CardSlot target : validTargets(room, effect, opponent)) {
                    target.setTurnStrength(target.getTurnStrength() - r.amount());
                    floating(target, effect, "Pierde " + r.amount() + " de fuerza hasta el final del ataque", "attack", -r.amount(), null);
                    if (room.getCombat() != null) room.getCombat().getUntilEndOfAttack().add(target.getInstanceId() + "|" + STRENGTH_ENTRY + (-r.amount()));
                    int now = Math.max(0, strength(target));
                    record(room, GameEvent.STRENGTH_REDUCED, userId, target, null, null, effect,
                            Map.of("amount", r.amount(), "to", now, "ownerId", opponent.getUserId(), "untilNextTurn", false),
                            name(target) + " pierde " + r.amount() + " de fuerza hasta el final del ataque (" + effect.getSourceName() + "): queda en " + now);
                    if (now <= 0) destroy(room, target, opponent, false, CAUSE_EFFECT, userId, null, effect);
                }
                return false;
            }
            case TapTriggerCard ignored -> {
                GameEvent trigger = triggerEvent(room, effect);
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                CardSlot target = trigger == null || opponent == null ? null : frontLineCard(opponent, trigger.getCardInstanceId());
                if (target == null || target.isTapped() || keywords(target).contains(CANNOT_TAP)) return false;
                target.setTapped(true);
                effect.setLastDone(true);
                record(room, GameEvent.TAP, userId, target, null, null, effect, Map.of("ownerId", opponent.getUserId()),
                        player.getUsername() + " agota a " + name(target) + " (" + effect.getSourceName() + ")");
                return false;
            }
            case TributeFromHandOrMaterials t -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                List<CardSlot> cards = new ArrayList<>(player.getHand().stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId())).toList());
                if (self != null) cards.addAll(self.getMaterials());
                if (cards.isEmpty()) return false;
                int n = Math.min(t.count(), cards.size());
                pick(room, effect, "Tributa " + n + " " + plural(n, "carta", "cartas") + " de entre tu mano y los materiales de " + effect.getSourceName() + ".",
                        "Tributar", cards, ids(cards), n);
                return true;
            }
            case UntapOwnCard untap -> {
                List<CardSlot> valid = player.getField().stream().filter(c -> c.isTapped() && reactivable(c) && untap.filter().matches(c)
                        && !unaffected(c, effect)).toList();
                effect.setLastDone(false);
                if (valid.isEmpty()) {
                    log(room, "No hay cartas " + untap.filter().describe() + " agotadas que reactivar (" + effect.getSourceName() + ")", null);
                    return false;
                }
                pick(room, effect, "Reactiva 1 carta " + untap.filter().describe() + " de tu campo.", "Reactivar", player.getField(), ids(valid), 1);
                return true;
            }
            case TapOwnCard t -> {
                List<CardSlot> valid = player.getField().stream().filter(c -> !c.isTapped() && !c.getInstanceId().equals(effect.getSourceInstanceId())
                        && t.filter().matches(c) && !unaffected(c, effect)).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Agota 1 carta " + t.filter().describe() + " de tu campo.", "Agotar", player.getField(), ids(valid), 1);
                return true;
            }
            case AddAllyAsResource a -> {
                List<CardSlot> valid = allies(player).stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId()) && a.filter().matches(c)).toList();
                if (valid.isEmpty() || find(player.getField(), effect.getSourceInstanceId()) == null) return false;
                pick(room, effect, "Añade 1 aliado " + a.filter().describe() + " como recurso de " + effect.getSourceName() + ".", "Recurso",
                        player.getField(), ids(valid), 1);
                return true;
            }
            case TributeChosen ignored -> {
                for (String id : new ArrayList<>(effect.getTargetIds())) {
                    for (PlayerState owner : room.getPlayers().values()) {
                        CardSlot target = find(owner.getField(), id);
                        if (target == null) continue;
                        boolean enemy = !owner.getUserId().equals(userId);
                        if (enemy && !inFrontLine(target)) break;
                        if (unaffected(target, effect)) break;
                        if (enemy && keywords(target).contains(UNREMOVABLE)) {
                            log(room, name(target) + " no puede ser removida por efectos oponentes", null);
                            break;
                        }
                        removeFromField(owner, target);
                        owner.getTributeZone().add(target);
                        effect.setLastDone(true);
                        record(room, GameEvent.TRIBUTE, userId, target, "field", "tributeZone", effect,
                                Map.of("byCard", effect.getSourceInstanceId(), "ownerId", owner.getUserId()),
                                player.getUsername() + " tributa a " + name(target) + " (" + effect.getSourceName() + ")");
                    }
                }
                return false;
            }
            case TapChosenOnField ignored -> {
                for (String id : effect.getTargetIds()) {
                    for (PlayerState owner : room.getPlayers().values()) {
                        CardSlot target = find(owner.getField(), id);
                        if (target == null) continue;
                        if (!owner.getUserId().equals(userId) && !inFrontLine(target)) break;
                        if (target.isTapped() || keywords(target).contains(CANNOT_TAP) || unaffected(target, effect)) break;
                        target.setTapped(true);
                        effect.setLastDone(true);
                        record(room, GameEvent.TAP, userId, target, null, null, effect, Map.of("ownerId", owner.getUserId()),
                                player.getUsername() + " agota a " + name(target) + " (" + effect.getSourceName() + ")");
                    }
                }
                return false;
            }
            case MoveAllyToFront ignored -> {
                List<CardSlot> valid = allies(player).stream().filter(c -> !inFrontLine(c)).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Puedes mover 1 aliado de tu retaguardia a tu línea frontal.", "A la línea frontal", player.getField(), ids(valid), 1);
                room.getPrompt().setMin(0);
                return true;
            }
            case TapSubject ignored -> {
                CardSlot ally = subject(room, effect);
                // "Si lo hace: agótalo": after a special summon the creature that took its place
                if ((ally == null || effect.getSubjectId() != null && find(player.getField(), effect.getSubjectId()) == null) && effect.getSummonedId() != null)
                    ally = find(player.getField(), effect.getSummonedId());
                if (ally == null || ally.isTapped() || keywords(ally).contains(CANNOT_TAP)) return false;
                ally.setTapped(true);
                record(room, GameEvent.TAP, userId, ally, null, null, effect, null, player.getUsername() + " agota a " + name(ally) + " (" + effect.getSourceName() + ")");
                return false;
            }
            case ReturnSubjectToHand ignored -> {
                CardSlot ally = subject(room, effect);
                if (ally == null || ally.getInstanceId().equals(effect.getSourceInstanceId()) && effect.getSubjectId() == null) return false;
                removeFromField(player, ally);
                player.getHand().add(ally);
                effect.setLastTributed(new ArrayList<>(List.of(ally.getInstanceId())));
                effect.setLastDone(true);
                record(room, GameEvent.SEND, userId, ally, "field", "hand", effect, null,
                        player.getUsername() + " sube " + name(ally) + " a su mano (" + effect.getSourceName() + ")");
                return false;
            }
            case SpecialBySubjectLevel ignored -> {
                return false; // worked out as SpecialFromZone
            }
            case SpecialByCount ignored -> {
                return false; // worked out as SpecialFromZone
            }
            case SpecialSelf ignored -> {
                return false; // worked out as SpecialFromZone
            }
            case RitualFromZoneBy ignored -> {
                return false; // worked out as RitualFromZone
            }
            case ChooseEnemyUpToSubjectLevel ignored -> {
                return false; // worked out as ChooseEnemy
            }
            case SummonRelativeToLast ignored -> {
                return false; // worked out as SummonFromZone
            }
            case PlayFromZoneByCount ignored -> {
                return false; // worked out as PlayFromZone
            }
            case SummonLevelLastCount ignored -> {
                return false; // worked out as SummonFromZone
            }
            case PlaceFromZone p -> {
                List<CardSlot> valid = zones(player, p.zone()).stream()
                        .filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId()) && p.filter().matches(c)).toList();
                if (valid.isEmpty()) return false;
                int n = Math.min(p.max(), valid.size());
                pick(room, effect, "Coloca hasta " + n + " " + plural(n, "carta", "cartas") + " " + p.filter().describe() + " de tus " + zonesText(p.zone())
                                + " en tu " + (p.toFront() ? "línea frontal" : "retaguardia") + (p.exhausted() ? ", agotada" : "") + ".", "Colocar",
                        zones(player, p.zone()), ids(valid), n);
                room.getPrompt().setMin(0);
                return true;
            }
            case PlaceLastTributed p -> {
                List<CardSlot> valid = effect.getLastTributed().stream().map(id -> findAnywhere(room, userId, id))
                        .filter(c -> c != null && p.filter().matches(c) && find(player.getField(), c.getInstanceId()) == null).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Puedes colocar 1 de ellas en tu " + (p.toFront() ? "línea frontal" : "retaguardia") + (p.exhausted() ? ", agotada" : "")
                        + ".", "Colocar", valid, ids(valid), 1);
                room.getPrompt().setMin(0);
                return true;
            }
            case TributeTriggerCard ignored -> {
                GameEvent trigger = triggerEvent(room, effect);
                Located at = trigger == null ? null : locate(player, trigger.getCardInstanceId());
                if (at == null || "tributeZone".equals(at.zone()) || "field".equals(at.zone())) return false;
                zone(player, at.zone()).remove(at.card());
                at.card().setFaceDown(false);
                player.getTributeZone().add(at.card());
                effect.setLastDone(true);
                record(room, GameEvent.TRIBUTE, userId, at.card(), at.zone(), "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                        player.getUsername() + " tributa " + name(at.card()) + " (" + effect.getSourceName() + ")");
                return false;
            }
            case TakeFromLookedDistinctLevels take -> {
                if (effect.getLooked().isEmpty()) return false;
                List<String> valid = effect.getLooked().stream().filter(take.filter()::matches).map(CardSlot::getInstanceId).toList();
                Prompt prompt = prompt(room, effect, Prompt.Kind.TAKE_REVEALED_REST_BOTTOM,
                        "Toma hasta " + take.max() + " cartas " + take.filter().describe() + " con distinto nivel para tu mano. Ordena el resto: irá al fondo de tu mazo.");
                prompt.setCards(new ArrayList<>(effect.getLooked()));
                prompt.setValidIds(new ArrayList<>(valid));
                prompt.setMin(0);
                prompt.setMax(Math.min(take.max(), valid.size()));
                prompt.setReorderRest(true);
                prompt.setChosenLabel("A tu mano");
                room.setPrompt(prompt);
                return true;
            }
            case TakeFromLooked take -> {
                if (effect.getLooked().isEmpty()) return false;
                List<String> valid = effect.getLooked().stream().filter(take.filter()::matches).map(CardSlot::getInstanceId).toList();
                takeFromLooked(room, effect, Prompt.Kind.PICK, "Toma hasta " + take.max() + " " + plural(take.max(), "carta", "cartas") + " "
                        + take.filter().describe() + " para tu mano.", valid, take.max());
                return true;
            }
            case TributeFromLooked tribute -> {
                if (effect.getLooked().isEmpty()) return false;
                List<String> valid = effect.getLooked().stream().filter(tribute.filter()::matches).map(CardSlot::getInstanceId).toList();
                takeFromLooked(room, effect, Prompt.Kind.PICK, "Tributa hasta " + tribute.max() + " " + plural(tribute.max(), "carta", "cartas") + " "
                        + tribute.filter().describe() + ".", valid, tribute.max());
                room.getPrompt().setChosenLabel("Tributar");
                return true;
            }
            case DiscardFromLookedRestToBottom discard -> {
                if (effect.getLooked().isEmpty()) return false;
                pick(room, effect, "Envía hasta " + discard.max() + " " + plural(discard.max(), "carta", "cartas") + " a tu descarte. Ordena el resto: irá al "
                        + "fondo de tu mazo.", "Al descarte", effect.getLooked(), ids(effect.getLooked()), Math.min(discard.max(), effect.getLooked().size()));
                room.getPrompt().setMin(0);
                room.getPrompt().setReorderRest(true);
                return true;
            }
            case RevealFromHand r -> {
                List<CardSlot> valid = player.getHand().stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId())
                        && r.filters().stream().anyMatch(f -> f.matches(c))).toList();
                if (!assignable(valid, r.filters())) return false;
                pick(room, effect, "Revela de tu mano: " + String.join(" y ", r.filters().stream().map(f -> "1 " + f.describe()).toList()) + ".",
                        "Revelar", player.getHand(), ids(valid), r.filters().size());
                return true;
            }
            case TributeTopLife ignored -> {
                if (player.getLifeStack().isEmpty()) return false;
                CardSlot life = player.getLifeStack().remove(0);
                life.setFaceDown(false);
                player.getTributeZone().add(life);
                effect.setLastDone(true);
                record(room, GameEvent.TRIBUTE, userId, life, "lifeStack", "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId(),
                        "defenderId", userId), player.getUsername() + " tributa " + name(life) + " del tope de sus vidas (" + effect.getSourceName() + ")");
                if (player.getLifeStack().isEmpty()) loseByLives(room, player);
                return false;
            }
            case PickAttacker p -> {
                List<CardSlot> valid = attackers(player, p.filter());
                if (valid.isEmpty()) {
                    log(room, "No hay aliados que puedan declarar ataque (" + effect.getSourceName() + ")", null);
                    effect.setStepIndex(script(effect).steps().size() - 1);
                    return false;
                }
                pick(room, effect, "Elige 1 aliado " + p.filter().describe() + " de tu línea frontal: declarará ataque.", "Elegido", player.getField(), ids(valid), 1);
                return true;
            }
            case ChosenCannotBlock ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                for (CardSlot target : validTargets(room, effect, opponent)) {
                    target.getTurnKeywords().add(NO_BLOCK);
                    floating(target, effect, "No puede bloquear hasta el final del turno", "turn", 0, NO_BLOCK);
                    record(room, GameEvent.GAIN, userId, target, null, null, effect, Map.of("keyword", NO_BLOCK),
                            name(target) + " no puede bloquear hasta el final del turno (" + effect.getSourceName() + ")");
                }
                return false;
            }
            case TributeOtherAllies ignored -> {
                List<CardSlot> others = allies(player).stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId())).toList();
                for (CardSlot ally : others) {
                    removeFromField(player, ally);
                    player.getTributeZone().add(ally);
                    record(room, GameEvent.TRIBUTE, userId, ally, "field", "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                            player.getUsername() + " tributa a " + name(ally) + " (" + effect.getSourceName() + ")");
                }
                effect.setLastCount(others.size());
                effect.setLastTributed(new ArrayList<>(ids(others)));
                return false;
            }
            case GainStrengthPerCount per -> {
                gainStrength(room, effect, per.each() * effect.getLastCount());
                return false;
            }
            case GainStrengthBy by -> {
                gainStrength(room, effect, by.amount().of(room, userId));
                return false;
            }
            case AtEndOfTurn later -> {
                String subject = effect.getSummonedId() != null ? effect.getSummonedId() : effect.getSubjectId() != null ? effect.getSubjectId() : "";
                room.getUsedThisTurn().add(DELAYED + userId + "|" + effect.getSourceInstanceId() + "|" + later.scriptId() + "|" + subject);
                log(room, effect.getSourceName() + ": al final del turno se resolverá el resto de su efecto", null);
                return false;
            }
            case SendSelfToDeckBottom ignored -> {
                Located at = locate(player, effect.getSourceInstanceId());
                if (at == null || "deck".equals(at.zone())) return false;
                if ("field".equals(at.zone())) removeFromField(player, at.card());
                else zone(player, at.zone()).remove(at.card());
                at.card().setFaceDown(true);
                player.getDeck().add(at.card());
                effect.setLastDone(true);
                record(room, GameEvent.SEND, userId, at.card(), at.zone(), "deck", effect, null,
                        player.getUsername() + " envía " + name(at.card()) + " al fondo de su mazo");
                return false;
            }
            case LivesFaceDown lives -> {
                List<CardSlot> faceUp = player.getLifeStack().stream().filter(l -> !l.isFaceDown()).toList();
                if (lives.all()) {
                    faceUp.forEach(l -> l.setFaceDown(true));
                    if (lives.shuffle()) Collections.shuffle(player.getLifeStack());
                    log(room, player.getUsername() + " coloca bocabajo sus " + faceUp.size() + " vidas bocarriba" + (lives.shuffle() ? " y baraja sus vidas" : "")
                            + " (" + effect.getSourceName() + ")", null);
                    return false;
                }
                if (faceUp.isEmpty()) return false;
                pick(room, effect, "Coloca bocabajo 1 de tus vidas bocarriba.", "Bocabajo", player.getLifeStack(), ids(faceUp), 1);
                return true;
            }
            case TributeFromHandWhere t -> {
                List<CardSlot> valid = player.getHand().stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId()) && t.filter().matches(c)).toList();
                if (valid.isEmpty()) return false;
                int n = Math.min(t.count(), valid.size());
                pick(room, effect, "Tributa " + n + " " + plural(n, "carta", "cartas") + " " + t.filter().describe() + " de tu mano.", "Tributar",
                        player.getHand(), ids(valid), n);
                return true;
            }
            case SendCopiesFromField s -> {
                List<CardSlot> copies = copiesInBack(player, effect);
                if (copies.size() < s.count()) return false;
                pick(room, effect, "Envía " + s.count() + " copias de " + effect.getSourceName() + " de tu retaguardia a tu " + zoneName(s.zone()) + ".",
                        "Enviar", player.getField(), ids(copies), s.count());
                return true;
            }
            case EachPlayerDiscards d -> {
                // First the controller, then the opponent (repeatsDone counts who already did)
                if (effect.getRepeatsDone() >= 2) {
                    effect.setRepeatsDone(0);
                    return false;
                }
                PlayerState who = effect.getRepeatsDone() == 0 ? player : room.getPlayer(room.getOpponentId(userId));
                if (who == null || who.getHand().isEmpty()) {
                    effect.setRepeatsDone(effect.getRepeatsDone() + 1);
                    return run(room, effect, step);
                }
                int n = Math.min(d.count(), who.getHand().size());
                pick(room, effect, "Descarta " + n + " " + plural(n, "carta", "cartas") + " de tu mano, bocabajo (" + effect.getSourceName() + ").",
                        "Descartar", who.getHand(), ids(who.getHand()), n);
                room.getPrompt().setPlayerId(who.getUserId());
                return true;
            }
            case EachPlayerDraws d -> {
                for (PlayerState p : room.getPlayers().values()) {
                    int n = Math.min(d.count(), p.getDeck().size());
                    for (int i = 0; i < n; i++) drawOne(room, p, effect, false);
                    log(room, p.getUsername() + " roba " + n + " " + plural(n, "carta", "cartas") + " (" + effect.getSourceName() + ")", room.getEventSeq());
                }
                return false;
            }
            case OpponentGainsGemsAsCounter ignored -> {
                String opponentId = room.getOpponentId(userId);
                PlayerState opponent = room.getPlayer(opponentId);
                int gems = opponent == null ? 0 : Math.max(0, gemsOf(room, opponentId));
                if (gems > 0) {
                    room.setSharedCounter(clamp(room.getSharedCounter() + gems * direction(room, opponentId)));
                    record(room, GameEvent.GAIN_GEMS, opponentId, null, null, null, effect, Map.of("count", gems),
                            opponent.getUsername() + " gana " + gemsText(gems) + " (" + effect.getSourceName() + ")");
                }
                return false;
            }
            case DestroyEnemyNoTarget ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                List<CardSlot> valid = opponent == null ? List.of() : frontLine(opponent.getField()).stream().filter(c -> "creature".equals(c.getCardType())).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Destruye 1 enemigo de la línea frontal (no hace objetivo).", "Destruir", frontLine(opponent.getField()), ids(valid), 1);
                return true;
            }
            case SelfToLifeFrom life -> {
                Located at = locate(player, effect.getSourceInstanceId());
                if (at == null || "lifeStack".equals(at.zone())) return false;
                if ("field".equals(at.zone())) removeFromField(player, at.card());
                else zone(player, at.zone()).remove(at.card());
                at.card().setFaceDown(!life.faceUp());
                player.getLifeStack().add(0, at.card());
                effect.setLastDone(true);
                record(room, GameEvent.SEND, userId, at.card(), at.zone(), "lifeStack", effect, null,
                        player.getUsername() + " coloca " + name(at.card()) + " al tope de sus vidas, " + (life.faceUp() ? "bocarriba" : "bocabajo"));
                return false;
            }
            case DiscardFromHand d -> {
                List<CardSlot> valid = player.getHand().stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId())).toList();
                if (valid.isEmpty()) return false;
                int n = Math.min(d.count(), valid.size());
                pick(room, effect, "Descarta " + n + " " + plural(n, "carta", "cartas") + " de tu mano.", "Descartar", player.getHand(), ids(valid), n);
                return true;
            }
            case ZoneToLife z -> {
                List<CardSlot> valid = zones(player, z.zone()).stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId()) && z.filter().matches(c)).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Puedes colocar 1 carta " + z.filter().describe() + " de tus " + zonesText(z.zone()) + " al tope de tus vidas"
                        + (z.faceUp() ? ", bocarriba." : "."), "A tus vidas", zones(player, z.zone()), ids(valid), 1);
                room.getPrompt().setMin(0);
                return true;
            }
            case DestroyAlly d -> {
                List<CardSlot> valid = allies(player).stream().filter(d.filter()::matches).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Destruye 1 aliado " + d.filter().describe() + ".", "Destruir", player.getField(), ids(valid), 1);
                return true;
            }
            case DiscardMaterials d -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null || self.getMaterials().size() < d.count()) return false;
                pick(room, effect, "Descarta " + d.count() + " materiales de " + name(self) + ".", "Descartar", self.getMaterials(), ids(self.getMaterials()), d.count());
                return true;
            }
            case AttachTriggerCardAsMaterial ignored -> {
                GameEvent trigger = triggerEvent(room, effect);
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                Located at = trigger == null ? null : locate(player, trigger.getCardInstanceId());
                if (self == null || at == null || "field".equals(at.zone()) || "lifeStack".equals(at.zone())) return false;
                zone(player, at.zone()).remove(at.card());
                at.card().setFaceDown(false);
                self.getMaterials().add(at.card());
                effect.setLastDone(true);
                record(room, GameEvent.SEND, userId, at.card(), at.zone(), "materials", effect, Map.of("to", self.getInstanceId()),
                        player.getUsername() + " añade " + name(at.card()) + " como material de " + name(self));
                return false;
            }
            case UntapStructures untap -> {
                List<CardSlot> valid = player.getField().stream().filter(c -> "structure".equals(c.getCardType()) && c.isTapped() && untap.filter().matches(c)
                        && !unaffected(c, effect)).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Reactiva hasta " + untap.max() + " de tus estructuras " + untap.filter().describe() + ".", "Reactivar", player.getField(), ids(valid),
                        Math.min(untap.max(), valid.size()));
                room.getPrompt().setMin(0);
                return true;
            }
            case TributeAllyResources t -> {
                CardSlot ally = effect.getSubjectId() == null ? null : find(player.getField(), effect.getSubjectId());
                if (ally == null) {
                    List<CardSlot> valid = allies(player).stream().filter(a -> a.getResources().stream().anyMatch(t.filter()::matches)).toList();
                    if (valid.isEmpty()) return false;
                    pick(room, effect, "Elige 1 aliado con recursos " + t.filter().describe() + ".", "Elegido", player.getField(), ids(valid), 1);
                    return true;
                }
                List<CardSlot> valid = ally.getResources().stream().filter(t.filter()::matches).toList();
                pick(room, effect, "Tributa hasta " + t.max() + " recursos " + t.filter().describe() + " de " + name(ally) + ".", "Tributar", ally.getResources(),
                        ids(valid), Math.min(t.max(), valid.size()));
                room.getPrompt().setMin(0);
                return true;
            }
            case AddResourceToHigherAlly a -> {
                if (effect.getRitualTargetId() == null) {
                    List<CardSlot> valid = higherAllyResources(player, a);
                    if (valid.isEmpty()) return false;
                    pick(room, effect, "Elige 1 carta " + a.filter().describe() + " de tus " + zonesText(a.zone()) + ": será recurso de un aliado de nivel mayor.",
                            "Recurso", zones(player, a.zone()), ids(valid), 1);
                    return true;
                }
                CardSlot card = findAnywhere(room, userId, effect.getRitualTargetId());
                List<CardSlot> valid = card == null ? List.of() : higherThan(player, card).stream().filter(c -> c != card).toList();
                if (valid.isEmpty()) {
                    effect.setRitualTargetId(null);
                    return false;
                }
                pick(room, effect, "Elige el aliado de nivel mayor que recibe a " + name(card) + " como recurso.", "Recibe", player.getField(), ids(valid), 1);
                return true;
            }
            case SacrificeClashing ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                CardSlot clashing = clashOpponent(room, effect);
                CardSlot other = opponent == null || clashing == null ? null : find(opponent.getField(), clashing.getInstanceId());
                if (other != null) destroy(room, other, opponent, true, CAUSE_EFFECT, userId, effect.getSourceInstanceId(), effect);
                if (self != null) destroy(room, self, player, true, CAUSE_EFFECT, userId, null, effect);
                return false;
            }
            case MoveSummonedToFrontIf move -> {
                CardSlot summoned = find(player.getField(), effect.getSummonedId());
                if (summoned == null || inFrontLine(summoned) || !move.filter().matches(summoned)) return false;
                summoned.setY(25.0);
                record(room, GameEvent.MOVE, userId, summoned, null, "frontLine", effect, Map.of("reason", "effect"),
                        name(summoned) + " se mueve a la línea frontal (" + effect.getSourceName() + ")");
                return false;
            }
            case RevealOpponentHandSendOneToBottom reveal -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                if (opponent == null || opponent.getHand().isEmpty()) return false;
                List<CardSlot> hand = new ArrayList<>(opponent.getHand());
                Collections.shuffle(hand);
                List<CardSlot> revealed = hand.subList(0, Math.min(reveal.count(), hand.size()));
                log(room, opponent.getUsername() + " revela " + String.join(", ", revealed.stream().map(EffectEngine::name).toList()) + " (" + effect.getSourceName() + ")", null);
                pick(room, effect, "Envía 1 de las cartas reveladas al fondo del mazo de tu rival.", "Al fondo", revealed, ids(revealed), 1);
                return true;
            }
            case HandDownTo h -> {
                int extra = player.getHand().size() - h.keep();
                if (extra <= 0) return false;
                pick(room, effect, "Envía " + extra + " " + plural(extra, "carta", "cartas") + " de tu mano al fondo de tu mazo (te quedas con " + h.keep() + ").",
                        "Al fondo", player.getHand(), ids(player.getHand()), extra);
                return true;
            }
            case ChooseEnemiesLevelSum s -> {
                List<CardSlot> valid = enemyTargets(room, effect, CardFilter.creatures());
                if (!levelSumPossible(valid, s.sum())) return false;
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                pick(room, effect, "Elige enemigos que sumen " + s.sum() + " en nivel.", "Objetivo", frontLine(opponent.getField()), ids(valid), valid.size());
                room.getPrompt().setMin(1);
                return true;
            }
            case SummonUpTo s -> {
                List<CardSlot> valid = summonable(room, effect, new SummonFromZone(s.zone(), s.filter(), s.reduce()));
                if (valid.isEmpty()) return false;
                pick(room, effect, "Invoca hasta " + s.max() + " criaturas " + s.filter().describe() + " de tus " + zonesText(s.zone())
                        + (s.reduce() > 0 ? " reduciendo sus costes en " + s.reduce() : "") + ".", "Invocar", zones(player, s.zone()), ids(valid),
                        Math.min(s.max(), valid.size()));
                room.getPrompt().setMin(0);
                return true;
            }
            case ChooseFrontLineCreatures c -> {
                List<CardSlot> valid = frontLineCreatures(room, effect, c.filter());
                if (valid.size() < c.count()) return false;
                List<CardSlot> shown = new ArrayList<>(frontLine(player.getField()));
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                if (opponent != null) shown.addAll(frontLine(opponent.getField()));
                pick(room, effect, "Elige " + c.count() + " criaturas " + c.filter().describe() + " en la línea frontal.", "Objetivo", shown, ids(valid), c.count());
                return true;
            }
            case GainGemsPerOwnSacrificed ignored -> {
                int n = (int) effect.getTargetIds().stream().filter(id -> find(player.getTributeZone(), id) != null).count();
                if (n > 0) run(room, effect, new GainGems(n));
                return false;
            }
            case ReturnAllyOfSummonedLevel ignored -> {
                CardSlot placed = findAnywhere(room, userId, effect.getSummonedId());
                if (placed == null || placed.getLevel() == null) return false;
                List<CardSlot> valid = allies(player).stream().filter(c -> c != placed && placed.getLevel().equals(c.getLevel())).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Sube a tu mano 1 aliado nivel " + placed.getLevel() + ".", "A tu mano", player.getField(), ids(valid), 1);
                return true;
            }
            case TributeFromZoneUpTo t -> {
                List<CardSlot> valid = zones(player, t.zone()).stream().filter(t.filter()::matches).toList();
                effect.setLastCount(0);
                if (valid.isEmpty()) return false;
                pick(room, effect, "Tributa hasta " + t.max() + " cartas " + t.filter().describe() + " de tus " + zonesText(t.zone()) + ".", "Tributar",
                        zones(player, t.zone()), ids(valid), Math.min(t.max(), valid.size()));
                room.getPrompt().setMin(0);
                return true;
            }
            case ChooseWeakerThanSelf w -> {
                List<CardSlot> valid = weakerThanSelf(room, effect);
                if (valid.isEmpty()) return false;
                List<CardSlot> shown = new ArrayList<>(player.getField());
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                if (opponent != null) shown.addAll(frontLine(opponent.getField()));
                pick(room, effect, "Elige 1 criatura en el campo con fuerza menor a " + effect.getSourceName() + ".", "Elegir", shown, ids(valid), 1);
                if (w.upTo()) room.getPrompt().setMin(0);
                return true;
            }
            case IfChosenWasActiveAlly ifActive -> {
                if (effect.getRepeatsDone() != ACTIVE_ALLY_CHOSEN) effect.setStepIndex(effect.getStepIndex() + ifActive.covers());
                effect.setRepeatsDone(0);
                return false;
            }
            case DestroyTopLife ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                if (opponent == null || opponent.getLifeStack().isEmpty()) return false;
                CardSlot life = opponent.getLifeStack().remove(0);
                life.setFaceDown(false);
                opponent.getDiscardPile().add(life);
                record(room, GameEvent.DESTROY, userId, life, "lifeStack", "discardPile", effect,
                        Map.of("cause", CAUSE_EFFECT, "ownerId", opponent.getUserId(), "byCard", effect.getSourceInstanceId(), "defenderId", opponent.getUserId()),
                        player.getUsername() + " destruye la vida " + name(life) + " de " + opponent.getUsername() + " (" + effect.getSourceName() + ")");
                if (opponent.getLifeStack().isEmpty()) loseByLives(room, opponent);
                return false;
            }
            case DestroySelf ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self != null) destroy(room, self, player, false, CAUSE_EFFECT, userId, null, effect);
                return false;
            }
            case SacrificeAllInFrontLine s -> {
                for (PlayerState owner : room.getPlayers().values()) {
                    for (CardSlot c : new ArrayList<>(frontLine(owner.getField()))) {
                        if ("creature".equals(c.getCardType()) && s.filter().matches(c) && find(owner.getField(), c.getInstanceId()) != null) {
                            destroy(room, c, owner, true, CAUSE_EFFECT, userId, effect.getSourceInstanceId(), effect);
                        }
                    }
                }
                return false;
            }
            case TributeMaterialWhere t -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                List<CardSlot> valid = self == null ? List.of() : self.getMaterials().stream().filter(t.filter()::matches).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Tributa 1 material " + t.filter().describe() + " de " + name(self) + ".", "Tributar", self.getMaterials(), ids(valid), 1);
                return true;
            }
            case ReturnOwnCardToHand r -> {
                List<CardSlot> valid = player.getField().stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId()) && r.filter().matches(c)
                        && !unaffected(c, effect)).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Sube a tu mano 1 carta " + r.filter().describe() + " bajo tu control.", "A tu mano", player.getField(), ids(valid), 1);
                return true;
            }
            case TributeOwnCard t -> {
                List<CardSlot> valid = player.getField().stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId()) && t.filter().matches(c)
                        && !unaffected(c, effect)).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Tributa 1 carta " + t.filter().describe() + " bajo tu control.", "Tributar", player.getField(), ids(valid), 1);
                return true;
            }
            case SummonResource s -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                List<CardSlot> valid = self == null ? List.of() : self.getResources().stream().filter(r -> "creature".equals(r.getCardType())
                        && r.getCost() != null && canPay(room, userId, Math.max(0, r.getCost() - s.reduce()))).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Puedes invocar 1 recurso de " + name(self) + " reduciendo su coste en " + s.reduce() + ".", "Invocar", self.getResources(), ids(valid), 1);
                room.getPrompt().setMin(0);
                return true;
            }
            case AddResourceFromZone add -> {
                List<CardSlot> valid = resourceCandidates(room, effect, new AddResourceFrom(add.zones(), add.filter()));
                if (valid.isEmpty()) return false;
                pick(room, effect, "Añade " + (add.upTo() ? "hasta " : "") + "1 carta " + add.filter().describe() + " de tus " + zonesText(add.zones())
                        + " como recurso de " + effect.getSourceName() + ".", "Recurso", zones(player, add.zones()), ids(valid), 1);
                if (add.upTo()) room.getPrompt().setMin(0);
                return true;
            }
            case HandToDeckBottom h -> {
                List<CardSlot> valid = player.getHand().stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId())).toList();
                if (valid.size() < h.count()) return false;
                pick(room, effect, "Envía " + h.count() + " " + plural(h.count(), "carta", "cartas") + " de tu mano al fondo de tu mazo.", "Al fondo",
                        player.getHand(), ids(valid), h.count());
                return true;
            }
            case AttachSelfAsResourceToAlly ignored -> {
                Located at = locate(player, effect.getSourceInstanceId());
                List<CardSlot> valid = at == null ? List.of() : allies(player).stream().filter(c -> c != at.card()).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Elige el aliado que recibe a " + effect.getSourceName() + " como recurso.", "Recibe", player.getField(), ids(valid), 1);
                return true;
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
            case SendFromZoneToDeckBottomUpTo s -> {
                List<CardSlot> valid = zones(player, s.zone()).stream().filter(s.filter()::matches).toList();
                effect.setLastCount(0);
                if (valid.isEmpty()) return false;
                pick(room, effect, "Envía hasta " + s.max() + " cartas " + s.filter().describe() + " de tus " + zonesText(s.zone()) + " al fondo de tu mazo "
                        + "en orden aleatorio.", "Al fondo", zones(player, s.zone()), ids(valid), Math.min(s.max(), valid.size()));
                room.getPrompt().setMin(0);
                return true;
            }
            case SummonSelfPerCount s -> {
                CardSlot self = find(zone(player, s.zone()), effect.getSourceInstanceId());
                if (self == null || self.getCost() == null) return false;
                int reduce = effect.getLastCount() / s.per();
                if (!canPay(room, userId, Math.max(0, self.getCost() - reduce))) {
                    log(room, "No tienes gemas para invocar " + name(self), null);
                    return false;
                }
                summonByEffect(room, effect, player, self, s.zone(), reduce, false);
                effect.setLastDone(true);
                return false;
            }
            case TapCopiesUpTo ignored -> {
                List<CardSlot> copies = player.getField().stream().filter(c -> !c.isTapped() && c.getCardName() != null
                        && c.getCardName().equalsIgnoreCase(effect.getSourceName())).toList();
                effect.setLastCount(0);
                if (copies.isEmpty()) return false;
                pick(room, effect, "Agota hasta " + copies.size() + " copias de " + effect.getSourceName() + ".", "Agotar", player.getField(), ids(copies), copies.size());
                room.getPrompt().setMin(1);
                return true;
            }
            case PlaySelfReduced p -> {
                CardSlot self = find(zone(player, p.zone()), effect.getSourceInstanceId());
                if (self == null) return false;
                int cost = Math.max(0, (self.getCost() == null ? 0 : self.getCost()) - p.reduce());
                if (!canPay(room, userId, cost)) {
                    log(room, "No tienes gemas para jugar " + name(self) + " (" + cost + ")", null);
                    return false;
                }
                pay(room, userId, cost);
                zone(player, p.zone()).remove(self);
                if (!"hand".equals(p.zone())) player.getHand().add(self);
                play(room, player, self, cost, null, null, effect);
                effect.setLastDone(true);
                return false;
            }
            case LookTop look -> {
                int n = Math.min(look.count(), player.getDeck().size());
                for (int i = 0; i < n; i++) effect.getLooked().add(player.getDeck().remove(0));
                record(room, GameEvent.LOOK_TOP, userId, null, "deck", null, effect, Map.of("count", n),
                        player.getUsername() + " mira las primeras " + n + " cartas de su mazo (" + effect.getSourceName() + ")");
                return false;
            }
            case LookTopBy ignored -> {
                return false; // worked out as LookTop
            }
            case ChooseEnemiesPerCount ignored -> {
                return false; // worked out as ChooseEnemy
            }
            case ReduceChosenPerCount ignored -> {
                return false; // worked out as ReduceChosen
            }
            case LookedToTopOrBottom ignored -> {
                if (effect.getLooked().isEmpty()) return false;
                // One choice: their order and where they go (the first one of the order ends up highest)
                Prompt prompt = prompt(room, effect, Prompt.Kind.PICK, effect.getLooked().size() > 1
                        ? "Ordena las cartas (la de la izquierda queda más arriba) y elige si van al tope o al fondo de tu mazo."
                        : "Elige si la carta va al tope o al fondo de tu mazo.");
                prompt.setCards(new ArrayList<>(effect.getLooked()));
                prompt.setValidIds(new ArrayList<>(ids(effect.getLooked())));
                prompt.setMin(0);
                prompt.setMax(1);
                prompt.setReorderRest(true);
                prompt.setTopOrBottom(true);
                room.setPrompt(prompt);
                return true;
            }
            case TakeFromLookedRestToTributes take -> {
                if (effect.getLooked().isEmpty()) return false;
                List<String> valid = effect.getLooked().stream().filter(take.filter()::matches).map(CardSlot::getInstanceId).toList();
                takeFromLooked(room, effect, Prompt.Kind.PICK, "Toma hasta " + take.max() + " " + plural(take.max(), "carta", "cartas") + " "
                        + take.filter().describe() + " para tu mano. El resto se tributa.", valid, take.max());
                return true;
            }
            case IfCondition ifc -> {
                CardSlot source = findAnywhere(room, userId, effect.getSourceInstanceId());
                boolean holds = source != null && ifc.condition().test(room, null, source, userId);
                if (!holds) effect.setStepIndex(effect.getStepIndex() + ifc.covers());
                return false;
            }
            case AlliesGain gain -> {
                // On the table: the allies that enter later get it too (refreshContinuous gives it)
                String until = "nextTurn".equals(gain.until()) ? "hasta el final del turno oponente" : "hasta el final del turno";
                String except = gain.others() ? effect.getSourceInstanceId() : null;
                room.getFieldGrants().add(new FieldGrant(userId, gain.filter(), gain.keyword(), gain.until(), effect.getSourceName(), except));
                refreshContinuous(room);
                int n = (int) allies(player).stream().filter(c -> gain.filter().matches(c) && !c.getInstanceId().equals(except)).count();
                record(room, GameEvent.GAIN, userId, null, null, null, effect, Map.of("keyword", gain.keyword(), "count", n),
                        player.getUsername() + ": sus " + (gain.others() ? "otros " : "") + "aliados ganan " + gain.keyword() + " " + until + ", también los que entren después ("
                                + effect.getSourceName() + ")");
                return false;
            }
            case GainUntilNextTurn gain -> {
                CardSlot subject = subject(room, effect);
                if (subject == null) return false;
                subject.getLaterKeywords().add(gain.keyword());
                floating(subject, effect, "Gana " + gain.keyword() + " hasta el final del turno oponente", "nextTurn", 0, gain.keyword());
                record(room, GameEvent.GAIN, userId, subject, null, null, effect, Map.of("keyword", gain.keyword()),
                        name(subject) + " gana " + gain.keyword() + " hasta el final del turno oponente");
                return false;
            }
            case GainUntilNextAttack gain -> {
                CardSlot subject = subject(room, effect);
                if (subject == null) return false;
                String until = " hasta el final del turno o de su próximo ataque";
                if (gain.strength() != 0) {
                    subject.setTurnStrength(subject.getTurnStrength() + gain.strength());
                    subject.getUntilNextAttack().add(STRENGTH_ENTRY + gain.strength());
                    floating(subject, effect, "Gana fuerza " + gain.strength() + until, "attack", gain.strength(), null);
                }
                if (gain.keyword() != null) {
                    subject.getTurnKeywords().add(gain.keyword());
                    subject.getUntilNextAttack().add(gain.keyword());
                    floating(subject, effect, "Gana " + gain.keyword() + until, "attack", 0, gain.keyword());
                }
                record(room, GameEvent.GAIN, userId, subject, null, null, effect, Map.of("strength", gain.strength()),
                        name(subject) + " gana fuerza " + gain.strength() + (gain.keyword() == null ? "" : " y " + gain.keyword()) + until);
                return false;
            }
            case ChooseCreatureOnField choose -> {
                List<CardSlot> valid = creaturesOnField(room, effect, choose.filter());
                if (valid.isEmpty()) return false;
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                List<CardSlot> shown = new ArrayList<>(player.getField());
                if (opponent != null) shown.addAll(frontLine(opponent.getField()));
                pick(room, effect, "Elige 1 criatura " + choose.filter().describe() + " en el campo.", "Elegir", shown, ids(valid), 1);
                if (choose.upTo()) room.getPrompt().setMin(0);
                return true;
            }
            case SacrificeChosen ignored -> {
                for (String id : effect.getTargetIds()) {
                    for (PlayerState owner : room.getPlayers().values()) {
                        CardSlot target = find(owner.getField(), id);
                        if (target == null) continue;
                        // An enemy only while it is still in its Línea frontal
                        if (!owner.getUserId().equals(userId) && !inFrontLine(target)) break;
                        destroy(room, target, owner, true, CAUSE_EFFECT, userId, effect.getSourceInstanceId(), effect);
                        effect.setLastDone(true);
                    }
                }
                return false;
            }
            case TributeFromHandAsSubjectLevel ignored -> {
                CardSlot subject = subject(room, effect);
                int n = subject == null || subject.getLevel() == null ? 0 : subject.getLevel();
                List<CardSlot> valid = player.getHand().stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId())).toList();
                if (n == 0) return false;
                pick(room, effect, "Tributa " + n + " " + plural(n, "carta", "cartas") + " de tu mano (el nivel de " + name(subject) + ").", "Tributar",
                        player.getHand(), ids(valid), Math.min(n, valid.size()));
                return true;
            }
            case PickAllyUpToSacrificedLevel pick -> {
                CardSlot sacrificed = effect.getLastTributed().isEmpty() ? null : findAnywhere(room, userId, effect.getLastTributed().get(0));
                int max = levelWhenRemoved(room, sacrificed) + pick.plus();
                List<CardSlot> valid = allies(player).stream().filter(c -> inFrontLine(c) && c.getLevel() != null && c.getLevel() <= max).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Elige 1 aliado de tu línea frontal nivel " + max + " o menor: declarará ataque.", "Elegido", player.getField(), ids(valid), 1);
                return true;
            }
            case PickAllyWithHigherAlly ignored -> {
                List<CardSlot> valid = withHigherAlly(player);
                if (valid.isEmpty()) return false;
                pick(room, effect, "Elige el aliado que será material de otro de nivel mayor.", "Material", player.getField(), ids(valid), 1);
                return true;
            }
            case AttachSubjectToHigherAlly ignored -> {
                CardSlot subject = subject(room, effect);
                if (subject == null) return false;
                List<CardSlot> valid = higherThan(player, subject);
                if (valid.isEmpty()) return false;
                pick(room, effect, "Elige el aliado de nivel mayor que recibe a " + name(subject) + " como material.", "Recibe", player.getField(), ids(valid), 1);
                return true;
            }
            case PickAllyWithMaterials pick -> {
                List<CardSlot> valid = allies(player).stream().filter(c -> c.getMaterials().size() >= pick.count()).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Elige 1 aliado con " + pick.count() + " o más materiales.", "Elegido", player.getField(), ids(valid), 1);
                return true;
            }
            case TributeSubjectMaterials tm -> {
                CardSlot subject = subject(room, effect);
                if (subject == null || subject.getMaterials().size() < tm.count()) return false;
                pick(room, effect, "Tributa " + tm.count() + " materiales de " + name(subject) + ".", "Tributar", subject.getMaterials(),
                        ids(subject.getMaterials()), tm.count());
                return true;
            }
            case ExtraHit ignored -> {
                Combat c = room.getCombat();
                if (c == null || c.getStep() != Combat.Step.DAMAGE) return false;
                c.setHitsLeft((c.getHitsLeft() == null ? 0 : c.getHitsLeft()) + 1);
                c.setPhase(Combat.Phase.ACT);
                log(room, "El atacante inflige daño 1 vez más (" + effect.getSourceName() + ")", null);
                return false;
            }
            case SkipBlockStep ignored -> {
                if (room.getCombat() != null) {
                    room.getCombat().setSkipBlock(true);
                    log(room, "El ataque salta el paso de bloqueo (" + effect.getSourceName() + ")", null);
                }
                return false;
            }
            case IfChosenGone ifGone -> {
                boolean gone = effect.getTargetIds().stream().noneMatch(id -> findInRoom(room, id) != null
                        && room.getPlayers().values().stream().anyMatch(p -> find(p.getField(), id) != null));
                if (!gone) effect.setStepIndex(effect.getStepIndex() + ifGone.covers());
                return false;
            }
            case ChosenCannotBeExhausted cannot -> {
                boolean later = "nextTurn".equals(cannot.until());
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                for (CardSlot target : validTargets(room, effect, opponent)) {
                    (later ? target.getLaterKeywords() : target.getTurnKeywords()).add(CANNOT_TAP);
                    floating(target, effect, "No puede agotarse hasta el final del turno" + (later ? " oponente" : ""), cannot.until(), 0, CANNOT_TAP);
                    record(room, GameEvent.GAIN, userId, target, null, null, effect, Map.of("keyword", CANNOT_TAP),
                            name(target) + " no puede agotarse hasta el final del turno" + (later ? " oponente" : ""));
                }
                return false;
            }
            case TributeHand ignored -> {
                List<CardSlot> hand = new ArrayList<>(player.getHand());
                for (CardSlot card : hand) {
                    player.getHand().remove(card);
                    card.setFaceDown(false);
                    player.getTributeZone().add(card);
                    record(room, GameEvent.TRIBUTE, userId, card, "hand", "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                            player.getUsername() + " tributa " + name(card) + " (" + effect.getSourceName() + ")");
                }
                effect.setLastTributed(new ArrayList<>(ids(hand)));
                effect.setLastCount(hand.size());
                return false;
            }
            case EndYourTurn ignored -> {
                room.getUsedThisTurn().add(END_TURN_REQUESTED + userId);
                log(room, player.getUsername() + " termina su turno (" + effect.getSourceName() + ")", null);
                return false;
            }
            case PlaceSelfInFrontLine place -> {
                CardSlot self = find(zone(player, place.zone()), effect.getSourceInstanceId());
                if (self == null) return false;
                zone(player, place.zone()).remove(self);
                self.setFaceDown(false);
                self.setTapped(place.exhausted());
                self.setX(50.0);
                self.setY(20.0);
                player.getField().add(self);
                effect.setLastDone(true);
                record(room, GameEvent.SEND, userId, self, place.zone(), "field", effect, null,
                        player.getUsername() + " coloca " + name(self) + " en su línea frontal" + (place.exhausted() ? " agotada" : "")
                                + " (" + effect.getSourceName() + ")");
                return false;
            }
            case AttachSelfAsResourceToSubject ignored -> {
                attachSelfToSubject(room, effect, player, false);
                return false;
            }
            case TributeResources tr -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null) return false;
                List<CardSlot> valid = self.getResources().stream().filter(tr.filter()::matches).toList();
                if (valid.size() < tr.count()) return false;
                pick(room, effect, "Tributa " + tr.count() + " " + plural(tr.count(), "recurso", "recursos") + " " + tr.filter().describe()
                        + " de " + name(self) + ".", "Tributar", self.getResources(), ids(valid), tr.count());
                return true;
            }
            case ActivateSubjectSummonEffect ignored -> {
                CardSlot ally = subject(room, effect);
                EffectScript summon = ally == null ? null : CardEffects.of(ally.getCardName()).stream()
                        .filter(sc -> sc.moment() == EffectScript.Moment.ON_SUMMON).findFirst().orElse(null);
                if (summon == null) {
                    log(room, "No hay un efecto En Invocación que activar (" + effect.getSourceName() + ")", null);
                    return false;
                }
                // Activated as the ally's own: a new link of the chain, after this one resolves
                PendingEffect link = pending(room, summon, userId, ally, room.getEventSeq());
                link.setConfirmed(true);
                link.setOrdered(true);
                room.getTriggerQueue().add(0, link);
                log(room, name(ally) + " activa su efecto En Invocación (" + effect.getSourceName() + ")", null);
                return false;
            }
            case SubjectIsThisCard ignored -> {
                effect.setSubjectId(null);
                return false;
            }
            case TakeFromLookedLikeSubject take -> {
                if (effect.getLooked().isEmpty()) return false;
                CardSlot subject = subject(room, effect);
                List<String> classes = subject == null || subject.getClasses() == null ? List.of() : subject.getClasses();
                List<String> valid = effect.getLooked().stream()
                        .filter(c -> c.getClasses() != null && c.getClasses().stream().anyMatch(t -> classes.stream().anyMatch(t::equalsIgnoreCase)))
                        .map(CardSlot::getInstanceId).toList();
                takeFromLooked(room, effect, Prompt.Kind.TAKE_REVEALED_REST_BOTTOM, "Toma hasta " + take.max() + " " + plural(take.max(), "carta", "cartas")
                        + " que comparta tipo con " + name(subject) + " para tu mano. El resto va al fondo de tu mazo en orden aleatorio.", valid, take.max());
                return true;
            }
            case DiscardLookedRestTop discard -> {
                if (effect.getLooked().isEmpty()) return false;
                int n = effect.getLooked().size();
                int min = Math.min(discard.atLeast(), n);
                pick(room, effect, "Descarta al menos " + min + " " + plural(min, "carta", "cartas") + " bocabajo. Ordena el resto: vuelve al tope "
                        + "de tu mazo en ese orden (la de la izquierda queda arriba).", "Descartar", effect.getLooked(), ids(effect.getLooked()), n);
                room.getPrompt().setMin(min);
                room.getPrompt().setReorderRest(true);
                room.getPrompt().setRestToTop(true);
                return true;
            }
            case ReduceRepeatedly r -> {
                int total = r.times() < 0 ? effect.getLastCount() : r.times();
                List<CardSlot> valid = enemyTargets(room, effect, CardFilter.creatures());
                if (effect.getRepeatsDone() >= total || valid.isEmpty()) {
                    effect.setRepeatsDone(0);
                    return false;
                }
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                pick(room, effect, "Elige 1 enemigo: reduce en " + r.amount() + " su fuerza" + ("attack".equals(r.until()) ? " hasta el final del ataque" : "")
                                + " (" + (effect.getRepeatsDone() + 1) + " de " + total + "). Puedes no elegir más.", "Reducir",
                        frontLine(opponent.getField()), ids(valid), 1);
                room.getPrompt().setMin(0);
                return true;
            }
            case TributesToDeckBottomForKeyword d -> {
                List<CardSlot> valid = player.getTributeZone().stream().filter(d.filter()::matches).toList();
                if (valid.size() < d.count()) return false;
                pick(room, effect, "Puedes descartar " + d.count() + " tributos " + d.filter().describe() + " para que " + effect.getSourceName() + " gane "
                        + d.keyword() + " 1 hasta el final del ataque (tantas veces como quieras). Elige ninguno para terminar.", "Descartar",
                        player.getTributeZone(), ids(valid), d.count());
                room.getPrompt().setMin(0);
                return true;
            }
            case TributeAlliesLevelSum t -> {
                List<CardSlot> valid = allies(player).stream().filter(t.filter()::matches).toList();
                pick(room, effect, "Tributa " + t.minCount() + " o más aliados " + t.filter().describe() + " que sumen " + t.minSum() + " o más en nivel.",
                        "Tributar", player.getField(), ids(valid), valid.size());
                room.getPrompt().setMin(t.minCount());
                return true;
            }
            case PlaceFromHandUntilTurnEnd p -> {
                List<CardSlot> valid = player.getHand().stream().filter(p.filter()::matches).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Puedes colocar 1 criatura " + p.filter().describe() + " de tu mano en tu retaguardia; al final del turno vuelve a tu mano.",
                        "A la retaguardia", player.getHand(), ids(valid), 1);
                room.getPrompt().setMin(0);
                return true;
            }
            case TributeAllyMaterialWhere t -> {
                List<CardSlot> valid = allyMaterialsWhere(player, t);
                if (valid.isEmpty()) return false;
                pick(room, effect, "Tributa 1 material " + t.material().describe() + " de un aliado " + t.ally().describe() + ".", "Tributar", valid, ids(valid), 1);
                return true;
            }
            case ActivateTributedSummonEffect ignored -> {
                CardSlot ally = subject(room, effect);
                String materialId = effect.getLastTributed().isEmpty() ? null : effect.getLastTributed().get(0);
                CardSlot material = materialId == null ? null : find(player.getTributeZone(), materialId);
                EffectScript summon = material == null ? null : CardEffects.of(material.getCardName()).stream()
                        .filter(sc -> sc.moment() == EffectScript.Moment.ON_SUMMON).findFirst().orElse(null);
                if (ally == null || summon == null) {
                    log(room, "No hay un efecto En Invocación que activar (" + effect.getSourceName() + ")", null);
                    return false;
                }
                // The ally activates it as its own: a new link of the chain, after this one resolves
                PendingEffect link = pending(room, summon, userId, ally, room.getEventSeq());
                link.setConfirmed(true);
                link.setOrdered(true);
                room.getTriggerQueue().add(0, link);
                log(room, name(ally) + " activa el efecto En Invocación de " + name(material) + " (" + effect.getSourceName() + ")", null);
                return false;
            }
            case ExtraCombatHitThenEndTurn ignored -> {
                room.getUsedThisTurn().add(EXTRA_HIT + userId);
                log(room, "La próxima vez que un aliado de " + player.getUsername() + " inflija daño directo por combate este turno, inflige daño 1 vez más",
                        null);
                return false;
            }
            case OpponentMovesOneToFront ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                List<CardSlot> back = opponent == null ? List.of() : opponent.getField().stream()
                        .filter(c -> "creature".equals(c.getCardType()) && !inFrontLine(c)).toList();
                if (back.isEmpty()) {
                    log(room, "El oponente no tiene criaturas en su retaguardia (" + effect.getSourceName() + ")", null);
                    return false;
                }
                pick(room, effect, "Elige 1 criatura de tu retaguardia: se mueve a tu línea frontal (" + effect.getSourceName() + ").", "A la línea frontal",
                        opponent.getField(), ids(back), 1);
                room.getPrompt().setPlayerId(opponent.getUserId());
                return true;
            }
            case HandsToBottomDrawOpponentLives ignored -> {
                List<PlayerState> both = new ArrayList<>(room.getPlayers().values());
                for (PlayerState p : both) {
                    List<CardSlot> hand = new ArrayList<>(p.getHand());
                    Collections.shuffle(hand);
                    p.getHand().clear();
                    hand.forEach(c -> c.setFaceDown(true));
                    p.getDeck().addAll(hand);
                    log(room, p.getUsername() + " envía las " + hand.size() + " cartas de su mano al fondo de su mazo", null);
                }
                for (PlayerState p : both) {
                    PlayerState other = room.getPlayer(room.getOpponentId(p.getUserId()));
                    int n = Math.min(other == null ? 0 : other.getLifeStack().size(), p.getDeck().size());
                    for (int i = 0; i < n; i++) drawOne(room, p, effect, false);
                    log(room, p.getUsername() + " roba " + n + " " + plural(n, "carta", "cartas") + " (" + effect.getSourceName() + ")", null);
                }
                return false;
            }
            case TakeFromLookedRestToBottom take -> {
                if (effect.getLooked().isEmpty()) return false;
                List<String> valid = effect.getLooked().stream().filter(take.filter()::matches).map(CardSlot::getInstanceId).toList();
                Prompt prompt = prompt(room, effect, Prompt.Kind.TAKE_REVEALED_REST_BOTTOM,
                        "Toma hasta " + take.max() + " " + plural(take.max(), "carta", "cartas") + " " + take.filter().describe()
                                + " para tu mano. Ordena el resto: irá al fondo de tu mazo en ese orden.");
                prompt.setCards(new ArrayList<>(effect.getLooked()));
                prompt.setValidIds(new ArrayList<>(valid));
                prompt.setMin(0);
                prompt.setMax(Math.min(take.max(), valid.size()));
                prompt.setReorderRest(true);
                prompt.setChosenLabel("A tu mano");
                room.setPrompt(prompt);
                return true;
            }
            case TributeFromHand tribute -> {
                // Not the effect's own card when it is in the hand ("tributa 2 cartas de tu mano para invocar esta carta")
                List<CardSlot> valid = player.getHand().stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId())).toList();
                if (valid.isEmpty()) return false;
                int n = Math.min(tribute.count(), valid.size());
                pick(room, effect, "Tributa " + n + " " + plural(n, "carta", "cartas") + " de tu mano.", "Tributar",
                        player.getHand(), ids(valid), n);
                return true;
            }
            case Draw draw -> {
                int n = Math.min(draw.count(), player.getDeck().size());
                for (int i = 0; i < n; i++) drawOne(room, player, effect, false);
                effect.setLastDone(n > 0);
                log(room, player.getUsername() + " roba " + n + " " + plural(n, "carta", "cartas") + " (" + effect.getSourceName() + ")"
                        + (n < draw.count() ? ": su mazo no tenía más" : ""), room.getEventSeq());
                return false;
            }
            case DestroyEnemy destroy -> {
                List<CardSlot> valid = enemyTargets(room, effect, destroy.filter());
                if (valid.isEmpty()) {
                    log(room, inRetaguardia(room, effect)
                            ? effect.getSourceName() + " está en la retaguardia: sus efectos no pueden afectar a enemigos"
                            : "No hay enemigos " + destroy.filter().describe() + " en la línea frontal (" + effect.getSourceName() + ")", null);
                    return false;
                }
                int n = Math.min(destroy.count(), valid.size());
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                pick(room, effect, "Elige " + n + " " + plural(n, "enemigo", "enemigos") + " " + destroy.filter().describe() + " de la línea frontal: "
                        + plural(n, "será destruido", "serán destruidos") + ".", "Destruir",
                        frontLine(opponent.getField()), ids(valid), n);
                return true;
            }
            case ChooseEnemy choose -> {
                List<CardSlot> valid = enemyTargets(room, effect, choose.filter());
                if (valid.isEmpty()) return false;
                int n = Math.min(choose.count(), valid.size());
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                pick(room, effect, "Elige " + (choose.upTo() ? "hasta " : "") + n + " " + plural(n, "enemigo", "enemigos") + " "
                        + choose.filter().describe() + " de la línea frontal: " + (n == 1 ? "es el objetivo" : "son los objetivos") + " del efecto.",
                        "Objetivo", frontLine(opponent.getField()), ids(valid), n);
                if (choose.upTo()) room.getPrompt().setMin(0);
                return true;
            }
            case DestroyChosen ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                for (CardSlot target : validTargets(room, effect, opponent)) {
                    destroy(room, target, opponent, false, CAUSE_EFFECT, userId, null, effect);
                }
                return false;
            }
            case SummonFromZone summon -> {
                List<CardSlot> valid = summonable(room, effect, summon);
                if (valid.isEmpty()) {
                    log(room, "No hay criaturas que " + effect.getSourceName() + " pueda invocar", null);
                    return false;
                }
                pick(room, effect, "Invoca 1 criatura " + summon.filter().describe() + " de tu " + zoneName(summon.zone())
                                + (summon.reduce() >= SummonFromZone.FREE ? " sin pagar su coste"
                                        : summon.reduce() > 0 ? " pagando su coste reducido en " + summon.reduce() : " pagando su coste")
                                + ". Entra en " + (summon.toFront() ? "tu línea frontal" : "Retaguardia") + " y robas 1 carta.", "Invocar",
                        zones(player, summon.zone()), ids(valid), 1);
                return true;
            }
            case SummonSelfFree ignored -> {
                Located at = locate(player, effect.getSourceInstanceId());
                if (at == null || "field".equals(at.zone()) || at.card().getCost() == null) return false;
                summonByEffect(room, effect, player, at.card(), at.zone(), at.card().getCost(), false);
                effect.setLastDone(true);
                return false;
            }
            case SpecialFromZone special -> {
                return runSpecialFromZone(room, effect, player, special);
            }
            case TakeFromZone take -> {
                List<CardSlot> from = zones(player, take.zone());
                List<String> valid = from.stream().filter(take.filter()::matches).map(CardSlot::getInstanceId).toList();
                if (valid.isEmpty()) {
                    log(room, "No hay cartas " + take.filter().describe() + " en tus " + zoneName(take.zone()) + " (" + effect.getSourceName() + ")", null);
                    effect.setLastDone(false);
                    return false;
                }
                int n = Math.min(take.count(), valid.size());
                pick(room, effect, "Toma " + (take.upTo() ? "hasta " : "") + n + " " + plural(n, "carta", "cartas") + " "
                        + take.filter().describe() + " de tus " + zonesText(take.zone()) + " para tu mano.", "A tu mano", from, valid, n);
                if (take.upTo()) room.getPrompt().setMin(0);
                return true;
            }
            case TributeMaterials tm -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null || self.getMaterials().isEmpty()) return false;
                int n = Math.min(tm.count(), self.getMaterials().size());
                pick(room, effect, "Tributa " + (tm.upTo() ? "hasta " : "") + n + " " + plural(n, "material", "materiales") + " de "
                        + name(self) + ".", "Tributar", self.getMaterials(), ids(self.getMaterials()), n);
                if (tm.upTo()) room.getPrompt().setMin(1);
                return true;
            }
            case TakePerCount take -> {
                int n = effect.getLastCount();
                List<CardSlot> from = zone(player, take.zone());
                List<String> valid = from.stream().filter(take.filter()::matches).map(CardSlot::getInstanceId).toList();
                if (n <= 0 || valid.isEmpty()) return false;
                n = Math.min(n, valid.size());
                pick(room, effect, "Toma " + n + " " + plural(n, "carta", "cartas") + " " + take.filter().describe() + " de tus "
                        + zoneName(take.zone()) + " para tu mano.", "A tu mano", from, valid, n);
                return true;
            }
            case TributeFromHandUpTo up -> {
                if (player.getHand().isEmpty()) {
                    effect.setLastCount(0);
                    return false;
                }
                int n = Math.min(up.count(), player.getHand().size());
                pick(room, effect, "Tributa hasta " + n + " " + plural(n, "carta", "cartas") + " de tu mano.", "Tributar",
                        player.getHand(), ids(player.getHand()), n);
                room.getPrompt().setMin(0);
                return true;
            }
            case DrawLastCount ignored -> {
                int n = Math.min(effect.getLastCount(), player.getDeck().size());
                for (int i = 0; i < n; i++) drawOne(room, player, effect, false);
                if (n > 0) log(room, player.getUsername() + " roba " + n + " " + plural(n, "carta", "cartas") + " (" + effect.getSourceName() + ")", room.getEventSeq());
                return false;
            }
            case IfTributed ift -> {
                boolean match = effect.getLastTributed().stream()
                        .map(id -> findAnywhere(room, userId, id)).anyMatch(c -> c != null && ift.filter().matches(c));
                if (!match) effect.setStepIndex(effect.getStepIndex() + ift.covers());
                return false;
            }
            case TargetAllEnemies all -> {
                effect.setTargetIds(new ArrayList<>(ids(enemyTargets(room, effect, all.filter()))));
                return false;
            }
            case ReduceChosen reduce -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                for (CardSlot target : validTargets(room, effect, opponent)) {
                    reduceStrength(room, effect, opponent, target, reduce.amount(), reduce.untilNextTurn());
                }
                return false;
            }
            case ReduceTriggerToZero ignored -> {
                GameEvent trigger = triggerEvent(room, effect);
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                CardSlot target = trigger == null || opponent == null ? null : find(opponent.getField(), trigger.getCardInstanceId());
                if (target != null && strength(target) > 0) reduceStrength(room, effect, opponent, target, strength(target), false);
                return false;
            }
            case IfPossible ignored -> {
                if (!allPossibleAfterIfPossible(room, effect, script(effect).steps(), effect.getStepIndex())) {
                    log(room, effect.getSourceName() + ": " + effect.getLabel() + " no se resuelve (ya no puede hacerse todo lo que pide)", null);
                    effect.setStepIndex(script(effect).steps().size() - 1);
                }
                return false;
            }
            case ReduceTriggerFurther further -> {
                GameEvent trigger = triggerEvent(room, effect);
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                CardSlot target = trigger == null || opponent == null ? null : find(opponent.getField(), trigger.getCardInstanceId());
                if (target == null || strength(target) <= 0) return false;
                boolean later = trigger.getData() != null && Boolean.TRUE.equals(trigger.getData().get("untilNextTurn"));
                reduceStrength(room, effect, opponent, target, further.amount(), later);
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
            case AttachSubjectToSummoned ignored -> {
                CardSlot ally = find(player.getField(), effect.getSubjectId());
                CardSlot summoned = find(player.getField(), effect.getSummonedId());
                if (ally == null || summoned == null || ally == summoned) return false;
                player.getField().remove(ally);
                leaveField(ally);
                List<CardSlot> materials = new ArrayList<>(ally.getMaterials());
                ally.getMaterials().clear();
                summoned.getMaterials().add(ally);
                summoned.getMaterials().addAll(materials);
                record(room, GameEvent.SEND, userId, ally, "field", "materials", effect, Map.of("to", summoned.getInstanceId()),
                        player.getUsername() + " añade " + name(ally) + (materials.isEmpty() ? "" : " y sus " + materials.size() + " "
                                + plural(materials.size(), "material", "materiales")) + " como materiales de " + name(summoned));
                return false;
            }
            case MaterialToLife ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null || self.getMaterials().isEmpty()) return false;
                pick(room, effect, "Coloca hasta 1 material de " + name(self) + " al tope de tus vidas, bocarriba.", "A tus vidas",
                        self.getMaterials(), ids(self.getMaterials()), 1);
                room.getPrompt().setMin(0);
                return true;
            }
            case MaterialToFront mf -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                List<String> valid = self == null ? List.of() : ids(self.getMaterials().stream().filter(m -> materialToFront(mf, m)).toList());
                if (valid.isEmpty()) {
                    log(room, "No hay materiales que colocar en la línea frontal (" + effect.getSourceName() + ")", null);
                    return false;
                }
                pick(room, effect, "Coloca hasta 1 " + mf.filter().describe() + " nivel " + mf.level() + " de los materiales de " + name(self)
                        + " en tu línea frontal; recibirá todos los demás materiales.", "A la línea frontal", self.getMaterials(), valid, 1);
                room.getPrompt().setMin(0);
                return true;
            }
            case DeckTopToLife ignored -> {
                if (player.getDeck().isEmpty()) return false;
                CardSlot card = player.getDeck().remove(0);
                card.setFaceDown(true);
                player.getLifeStack().add(0, card);
                record(room, GameEvent.SEND, userId, card, "deck", "lifeStack", effect, null,
                        player.getUsername() + " coloca la carta del tope de su mazo como vida (" + effect.getSourceName() + ")");
                return false;
            }
            case DamageByEffect damage -> {
                damageByEffect(room, effect, player, damage.amount());
                return false;
            }
            case SummonSelfFromZone ss -> {
                CardSlot self = find(zone(player, ss.zone()), effect.getSourceInstanceId());
                if (self == null || self.getCost() == null) return false;
                summonByEffect(room, effect, player, self, ss.zone(), ss.reduce(), false);
                return false;
            }
            case AttachLowerFromTributes attach -> {
                GameEvent trigger = triggerEvent(room, effect);
                CardSlot ally = trigger == null ? null : find(player.getField(), trigger.getCardInstanceId());
                if (ally == null || ally.getLevel() == null) return false;
                List<String> valid = player.getTributeZone().stream()
                        .filter(c -> attach.filter().matches(c) && c.getLevel() != null && c.getLevel() < ally.getLevel())
                        .map(CardSlot::getInstanceId).toList();
                if (valid.isEmpty()) return false;
                effect.setSubjectId(ally.getInstanceId());
                pick(room, effect, "Añade a " + name(ally) + " 1 criatura " + attach.filter().describe() + " de menor nivel de tus tributos como material.",
                        "Material", player.getTributeZone(), valid, 1);
                return true;
            }
            case AttachSelfToSubject ignored -> {
                attachSelfToSubject(room, effect, player, true);
                return false;
            }
            case KeepOnField keep -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self != null) {
                    // It stays as a permanent: its effects on the field work like any other card's there
                    if (keep.exhausted()) self.setTapped(true);
                    effect.setKeepOnField(true);
                    log(room, name(self) + (keep.exhausted() ? " queda agotada en la retaguardia" : " permanece en el campo"), null);
                }
                return false;
            }
            case SendSelfFromFieldTo send -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null) return false;
                player.getField().remove(self);
                leaveField(self);
                zone(player, send.zone()).add(self);
                record(room, "tributeZone".equals(send.zone()) ? GameEvent.TRIBUTE : GameEvent.SEND, userId, self, "field", send.zone(), effect, null,
                        player.getUsername() + " envía " + name(self) + " a su " + zoneName(send.zone()));
                return false;
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
            case TributeTop top -> {
                int n = Math.min(top.count(), player.getDeck().size());
                List<CardSlot> tributed = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    CardSlot card = player.getDeck().remove(0);
                    card.setFaceDown(false);
                    player.getTributeZone().add(card);
                    tributed.add(card);
                    record(room, GameEvent.TRIBUTE, userId, card, "deck", "tributeZone", effect,
                            Map.of("byCard", effect.getSourceInstanceId()), null);
                }
                log(room, player.getUsername() + " tributa " + n + " " + plural(n, "carta", "cartas") + " del tope de su mazo ("
                        + effect.getSourceName() + ")" + names(tributed), room.getEventSeq());
                return false;
            }
            case ToDeckTop top -> {
                List<CardSlot> from = zone(player, top.zone());
                if (from.isEmpty()) return false;
                int n = Math.min(top.count(), from.size());
                pick(room, effect, "Coloca " + (top.upTo() ? "hasta " : "") + n + " " + plural(n, "carta", "cartas") + " de tu "
                        + zoneName(top.zone()) + " al tope de tu mazo.", "Al tope", from, ids(from), n);
                if (top.upTo()) room.getPrompt().setMin(0);
                return true;
            }
            case TributeResource tr -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                CardSlot resource = self == null ? null
                        : self.getResources().stream().filter(r -> tr.name().equalsIgnoreCase(r.getCardName())).findFirst().orElse(null);
                if (resource == null) return false;
                self.getResources().remove(resource);
                resource.setFaceDown(false);
                player.getTributeZone().add(resource);
                record(room, GameEvent.TRIBUTE, userId, resource, "resources", "tributeZone", effect, null,
                        player.getUsername() + " tributa " + name(resource) + " de los recursos de " + name(self));
                return false;
            }
            case DestroyFaceUpLife ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                List<CardSlot> valid = opponent == null ? List.of() : opponent.getLifeStack().stream().filter(l -> !l.isFaceDown()).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Puedes destruir 1 carta bocarriba de las vidas de tu rival, o ninguna.", "Destruir",
                        opponent.getLifeStack(), ids(valid), 1);
                room.getPrompt().setMin(0);
                return true;
            }
            case GainUntilEndOfAttack gain -> {
                CardSlot subject = subject(room, effect);
                if (subject == null) return false;
                subject.getTurnKeywords().add(gain.keyword());
                if (room.getCombat() != null) room.getCombat().getUntilEndOfAttack().add(subject.getInstanceId() + "|" + gain.keyword());
                floating(subject, effect, "Gana " + gain.keyword() + " hasta el final del ataque", "attack", 0, gain.keyword());
                record(room, GameEvent.GAIN, userId, subject, null, null, effect, Map.of("keyword", gain.keyword()),
                        name(subject) + " gana " + gain.keyword() + " hasta el final del ataque");
                return false;
            }
            case PlaySelfFromZone play -> {
                CardSlot self = find(zone(player, play.zone()), effect.getSourceInstanceId());
                if (self == null) return false;
                int reduce = play.reduceBySourceLevel() ? triggerSourceLevel(room, effect) : 0;
                int cost = Math.max(0, (self.getCost() == null ? 0 : self.getCost()) - reduce);
                if (!canPay(room, userId, cost)) {
                    log(room, "No tienes gemas para jugar " + name(self) + " (" + cost + ")", null);
                    return false;
                }
                pay(room, userId, cost);
                zone(player, play.zone()).remove(self);
                player.getHand().add(self);
                play(room, player, self, cost, null, null, effect);
                return false;
            }
            case IfDone ifDone -> {
                if (!effect.isLastDone()) effect.setStepIndex(effect.getStepIndex() + ifDone.covers());
                effect.setLastDone(false);
                return false;
            }
            case IfNotDone ifNot -> {
                if (effect.isLastDone()) effect.setStepIndex(effect.getStepIndex() + ifNot.covers());
                effect.setLastDone(false);
                return false;
            }
            case GainStrength gain -> {
                CardSlot subject = subject(room, effect);
                if (subject == null) return false;
                subject.setTurnStrength(subject.getTurnStrength() + gain.amount());
                floating(subject, effect, "Gana fuerza " + gain.amount() + " hasta el final del turno", "turn", gain.amount(), null);
                record(room, GameEvent.GAIN, userId, subject, null, null, effect, Map.of("strength", gain.amount()),
                        name(subject) + " gana fuerza " + gain.amount() + " hasta el final del turno");
                return false;
            }
            case RitualFromZone ritual -> {
                return runRitual(room, effect, player, ritual);
            }
            case SendSelfTo send -> {
                Located at = locate(player, effect.getSourceInstanceId());
                if (at == null || at.zone().equals(send.zone())) return false;
                zone(player, at.zone()).remove(at.card());
                at.card().setFaceDown(false);
                zone(player, send.zone()).add(at.card());
                record(room, "tributeZone".equals(send.zone()) ? GameEvent.TRIBUTE : GameEvent.SEND, userId, at.card(), at.zone(), send.zone(), effect, null,
                        player.getUsername() + " envía " + name(at.card()) + " a su " + zoneName(send.zone()));
                return false;
            }
            case SendFromZoneToDeckBottom send -> {
                List<CardSlot> from = zones(player, send.zone());
                List<String> valid = from.stream().filter(send.filter()::matches).map(CardSlot::getInstanceId).toList();
                int n = Math.min(send.count(), valid.size());
                if (n == 0) return false;
                pick(room, effect, "Elige " + n + " " + plural(n, "carta", "cartas") + " " + send.filter().describe() + " de tus "
                        + zoneName(send.zone()) + ": irán al fondo de tu mazo en el orden en que las elijas.", "Al fondo", from, valid, n);
                return true;
            }
            case UntapSelf ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null || !self.isTapped()) return false;
                self.setTapped(false);
                record(room, GameEvent.UNTAP, userId, self, null, null, effect, null,
                        player.getUsername() + " reactiva " + name(self) + " (" + effect.getSourceName() + ")");
                return false;
            }
            case TapSelf ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null || self.isTapped()) return false;
                self.setTapped(true);
                record(room, GameEvent.TAP, userId, self, null, null, effect, null, player.getUsername() + " agota " + name(self));
                return false;
            }
            case GainUntilEndOfTurn gain -> {
                CardSlot subject = subject(room, effect);
                if (subject == null) return false;
                subject.getTurnKeywords().add(gain.keyword());
                floating(subject, effect, "Gana " + gain.keyword() + " hasta el final del turno", "turn", 0, gain.keyword());
                record(room, GameEvent.GAIN, userId, subject, null, null, effect, Map.of("keyword", gain.keyword()),
                        name(subject) + " gana " + gain.keyword() + " hasta el final del turno");
                return false;
            }
            case GainGems gems -> {
                room.setSharedCounter(clamp(room.getSharedCounter() + gems.count() * direction(room, userId)));
                record(room, GameEvent.GAIN_GEMS, userId, null, null, null, effect, Map.of("count", gems.count()),
                        player.getUsername() + " gana " + gemsText(gems.count()) + " (" + effect.getSourceName() + ")");
                return false;
            }
            case DeclareAttack declare -> {
                CardSlot subject = subject(room, effect);
                if (subject == null) return false;
                if (room.getCombat() != null) {
                    log(room, name(subject) + " no puede atacar: ya hay un combate en curso", null);
                    return false;
                }
                if (keywords(subject).contains(NO_ATTACK)) {
                    log(room, name(subject) + " no puede declarar ataque este turno", null);
                    return false;
                }
                if (!inFrontLine(subject)) {
                    log(room, name(subject) + " no puede atacar: no está en la línea frontal", null);
                    return false;
                }
                List<CardSlot> targets = declare.opponentOnly() ? List.of() : attackTargets(room, userId, subject);
                if (targets.isEmpty()) {
                    startCombat(room, userId, subject, null, true, effect);
                    return false;
                }
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                Prompt prompt = prompt(room, effect, Prompt.Kind.PICK,
                        name(subject) + " declara ataque: elige una criatura agotada a la que atacar, o ninguna para atacar a tu rival.");
                prompt.setCards(new ArrayList<>(opponent.getField()));
                prompt.setValidIds(new ArrayList<>(ids(targets)));
                prompt.setMin(0);
                prompt.setMax(1);
                prompt.setChosenLabel("Atacar");
                room.setPrompt(prompt);
                return true;
            }
            case PickAlly pick -> {
                List<CardSlot> valid = pickable(room, effect, player, pick);
                if (valid.isEmpty()) {
                    // Nothing to act on: the rest of the effect does nothing
                    log(room, "No hay aliados que puedan elegirse (" + effect.getSourceName() + ")", null);
                    effect.setStepIndex(script(effect).steps().size() - 1);
                    return false;
                }
                pick(room, effect, pick.attacker() ? "Elige 1 aliado de tu línea frontal: declarará ataque." : "Elige 1 aliado.",
                        "Elegido", player.getField(), ids(valid), 1);
                return true;
            }
            case SacrificeSelf ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self != null) destroy(room, self, player, true, CAUSE_EFFECT, userId, null, effect);
                return false;
            }
            case SacrificeAlly sacrifice -> {
                List<CardSlot> valid = sacrificable(effect, player, sacrifice);
                if (valid.isEmpty()) return false;
                pick(room, effect, sacrifice.toUntapAnother()
                        ? "Sacrifica 1 aliado. No puede ser el único aliado agotado que queda por reactivar."
                        : "Sacrifica 1 aliado.", "Sacrificar", player.getField(), ids(valid), 1);
                return true;
            }
            case UntapAlly untap -> {
                List<CardSlot> valid = allies(player).stream().filter(c -> reactivable(c) && untap.filter().matches(c)).toList();
                if (valid.isEmpty()) {
                    log(room, "No hay aliados agotados que reactivar (" + effect.getSourceName() + ")", null);
                    return false;
                }
                String which = untap.filter().describe();
                pick(room, effect, "Reactiva 1 aliado agotado" + (which.isEmpty() ? "" : " " + which) + ".", "Reactivar", player.getField(), ids(valid), 1);
                return true;
            }
            case UntapAllies untap -> {
                List<CardSlot> valid = allies(player).stream().filter(c -> reactivable(c) && untap.filter().matches(c)).toList();
                if (valid.isEmpty()) {
                    log(room, "No hay aliados agotados que reactivar (" + effect.getSourceName() + ")", null);
                    return false;
                }
                int max = Math.min(untap.max(), valid.size());
                pick(room, effect, "Reactiva hasta " + untap.max() + " " + plural(untap.max(), "aliado agotado", "aliados agotados") + ".", "Reactivar",
                        player.getField(), ids(valid), max);
                room.getPrompt().setMin(0);
                return true;
            }
            case MoveSelfToFront ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null || inFrontLine(self)) return false;
                self.setY(25.0);
                record(room, GameEvent.MOVE, userId, self, null, "frontLine", effect, null,
                        name(self) + " sube a la línea frontal (" + effect.getLabel() + ")");
                return false;
            }
            case PlaySelfFree ignored -> {
                CardSlot self = find(player.getHand(), effect.getSourceInstanceId());
                if (self != null) play(room, player, self, 0, null, null, effect);
                return false;
            }
            case ChooseEnemyBy ignored -> {
                return false;
            }
            case SummonFromZoneBy ignored -> {
                return false;
            }
            case ReduceChosenToZero ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                for (CardSlot target : validTargets(room, effect, opponent)) {
                    if (strength(target) > 0) reduceStrength(room, effect, opponent, target, strength(target), false);
                }
                return false;
            }
            case TapChosen ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                for (CardSlot target : validTargets(room, effect, opponent)) {
                    if (target.isTapped() || keywords(target).contains(CANNOT_TAP)) continue;
                    target.setTapped(true);
                    effect.setLastDone(true);
                    record(room, GameEvent.TAP, userId, target, null, null, effect, Map.of("ownerId", opponent.getUserId()),
                            player.getUsername() + " agota a " + name(target) + " (" + effect.getSourceName() + ")");
                }
                return false;
            }
            case BounceChosen ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                boolean any = false;
                for (CardSlot target : validTargets(room, effect, opponent)) {
                    any = true;
                    if (keywords(target).contains(UNREMOVABLE)) {
                        log(room, name(target) + " no puede ser removida por efectos oponentes", null);
                        continue;
                    }
                    if (deferRemoval(room, target, opponent, false, CAUSE_EFFECT, userId, effect.getSourceInstanceId(), effect)) continue;
                    removeFromField(opponent, target);
                    opponent.getHand().add(target);
                    record(room, GameEvent.SEND, userId, target, "field", "hand", effect, Map.of("ownerId", opponent.getUserId()),
                            player.getUsername() + " sube a " + name(target) + " a la mano de " + opponent.getUsername() + " (" + effect.getSourceName() + ")");
                }
                effect.setLastDone(any);
                return false;
            }
            case NoUntapChosen noUntap -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                for (String id : effect.getTargetIds()) {
                    CardSlot target = opponent == null ? null : find(opponent.getField(), id);
                    if (target == null) continue;
                    // Counted down at each change of turn: it is 1 during its owner's next turn
                    int turns = opponent.getUserId().equals(room.getCurrentTurnUserId()) ? 3 : 2;
                    if (noUntap.wholeTurn()) {
                        target.setNoUntap(turns);
                        floating(target, effect, "No se puede reactivar durante el próximo turno de " + opponent.getUsername(), "nextTurn", 0, null);
                        log(room, name(target) + " no se podrá reactivar durante el próximo turno de " + opponent.getUsername() + " (" + effect.getSourceName() + ")", null);
                    } else {
                        target.setNoStartUntap(turns);
                        floating(target, effect, "No se reactiva al inicio del próximo turno de " + opponent.getUsername(), "nextTurn", 0, null);
                        log(room, name(target) + " no se reactivará al inicio del próximo turno de " + opponent.getUsername() + " (" + effect.getSourceName() + ")", null);
                    }
                }
                return false;
            }
            case ChooseWeakestEnemy weakest -> {
                List<CardSlot> valid = enemyTargets(room, effect, weakest.filter());
                if (valid.isEmpty()) return false;
                int min = valid.stream().mapToInt(EffectEngine::strength).min().orElse(0);
                List<CardSlot> ties = valid.stream().filter(c -> strength(c) == min).toList();
                if (ties.size() == 1) {
                    effect.getTargetIds().add(ties.get(0).getInstanceId());
                    record(room, GameEvent.TARGET, userId, ties.get(0), null, null, effect, Map.of("targetIds", List.of(ties.get(0).getInstanceId())),
                            player.getUsername() + " elige como objetivo a " + name(ties.get(0)) + " (" + effect.getSourceName() + ")");
                    return false;
                }
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                pick(room, effect, "Varios enemigos " + weakest.filter().describe() + " tienen la menor fuerza (" + min + "): elige 1.",
                        "Objetivo", frontLine(opponent.getField()), ids(ties), 1);
                return true;
            }
            case SummonSelf ss -> {
                CardSlot self = find(zone(player, ss.zone()), effect.getSourceInstanceId());
                if (self == null || self.getCost() == null) return false;
                int reduce = ss.reduce().of(room, userId);
                if (!canPay(room, userId, Math.max(0, self.getCost() - reduce))) {
                    log(room, "No tienes gemas para invocar " + name(self), null);
                    return false;
                }
                summonByEffect(room, effect, player, self, ss.zone(), reduce, ss.toFront());
                effect.setLastDone(true);
                return false;
            }
            case SacrificeAllyWhere sacrifice -> {
                List<CardSlot> valid = allies(player).stream().filter(sacrifice.filter()::matches).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Sacrifica 1 aliado " + sacrifice.filter().describe() + ".", "Sacrificar", player.getField(), ids(valid), 1);
                return true;
            }
            case TributeAlly tribute -> {
                List<CardSlot> valid = allies(player).stream().filter(tribute.filter()::matches).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Tributa 1 aliado " + tribute.filter().describe() + ": va a tus tributos.", "Tributar", player.getField(), ids(valid), 1);
                return true;
            }
            case TributeAllyMaterial ignored -> {
                List<CardSlot> valid = allyMaterials(player);
                if (valid.isEmpty()) return false;
                pick(room, effect, "Tributa 1 material de 1 de tus aliados.", "Tributar", valid, ids(valid), 1);
                return true;
            }
            case PickAllyWhere pick -> {
                List<CardSlot> valid = pickableWhere(room, effect, player, pick);
                if (valid.isEmpty()) {
                    log(room, "No hay aliados que puedan elegirse (" + effect.getSourceName() + ")", null);
                    effect.setStepIndex(script(effect).steps().size() - 1);
                    return false;
                }
                pick(room, effect, "Elige 1 aliado " + pick.filter().describe() + ".", "Elegido", player.getField(), ids(valid), 1);
                return true;
            }
            case SubjectFromTrigger ignored -> {
                GameEvent trigger = triggerEvent(room, effect);
                CardSlot card = trigger == null ? null : find(player.getField(), trigger.getCardInstanceId());
                if (card != null) effect.setSubjectId(card.getInstanceId());
                return false;
            }
            case UntapSubject ignored -> {
                CardSlot ally = subject(room, effect);
                if (ally == null || !reactivable(ally)) return false;
                ally.setTapped(false);
                record(room, GameEvent.UNTAP, userId, ally, null, null, effect, null,
                        player.getUsername() + " reactiva " + name(ally) + " (" + effect.getSourceName() + ")");
                return false;
            }
            case GainStrengthUntil gain -> {
                CardSlot subject = subject(room, effect);
                if (subject == null) return false;
                if ("nextTurn".equals(gain.until())) {
                    subject.setLaterStrength(subject.getLaterStrength() + gain.amount());
                } else {
                    subject.setTurnStrength(subject.getTurnStrength() + gain.amount());
                    if (room.getCombat() != null) room.getCombat().getUntilEndOfAttack().add(subject.getInstanceId() + "|" + STRENGTH_ENTRY + gain.amount());
                }
                String until = "nextTurn".equals(gain.until()) ? "el final del turno oponente" : "el final del ataque";
                floating(subject, effect, "Gana fuerza " + gain.amount() + " hasta " + until, gain.until(), gain.amount(), null);
                record(room, GameEvent.GAIN, userId, subject, null, null, effect, Map.of("strength", gain.amount()),
                        name(subject) + " gana fuerza " + gain.amount() + " hasta " + until);
                return false;
            }
            case DamageBySelfStrength ignored -> {
                CardSlot self = subject(room, effect);
                if (self == null && effect.getSubjectId() == null) self = findAnywhere(room, userId, effect.getSourceInstanceId());
                if (self != null) damageByEffect(room, effect, player, strength(self));
                return false;
            }
            case SummonedCannotAttack ignored -> {
                CardSlot summoned = find(player.getField(), effect.getSummonedId());
                if (summoned == null) return false;
                summoned.getTurnKeywords().add(NO_ATTACK);
                floating(summoned, effect, "No puede declarar ataque este turno", "turn", 0, NO_ATTACK);
                return false;
            }
            case MaterialsToHand toHand -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null || self.getMaterials().isEmpty()) return false;
                if (toHand.upTo()) {
                    // "hasta tantos … como tenga": the controller picks which (none is fine)
                    pick(room, effect, "Sube a tu mano los materiales de " + name(self) + " que quieras (o ninguno).", "A tu mano",
                            self.getMaterials(), ids(self.getMaterials()), self.getMaterials().size());
                    room.getPrompt().setMin(0);
                    return true;
                }
                List<CardSlot> materials = new ArrayList<>(self.getMaterials());
                self.getMaterials().clear();
                for (CardSlot m : materials) {
                    m.setFaceDown(false);
                    player.getHand().add(m);
                    record(room, GameEvent.ADD_TO_HAND, userId, m, "materials", "hand", effect, null, null);
                }
                effect.setLastTributed(new ArrayList<>(ids(materials)));
                effect.setLastDone(true);
                log(room, player.getUsername() + " sube a su mano los materiales de " + name(self) + ": "
                        + String.join(", ", materials.stream().map(EffectEngine::name).toList()), room.getEventSeq());
                return false;
            }
            case SelfToLife ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null) return false;
                removeFromField(player, self);
                self.setFaceDown(true);
                player.getLifeStack().add(0, self);
                record(room, GameEvent.SEND, userId, self, "field", "lifeStack", effect, null,
                        player.getUsername() + " coloca " + name(self) + " al tope de sus vidas, bocabajo");
                return false;
            }
            case MaterialToBack back -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                List<String> valid = self == null ? List.of() : ids(self.getMaterials().stream()
                        .filter(m -> "creature".equals(m.getCardType()) && back.filter().matches(m)).toList());
                if (valid.isEmpty()) return false;
                pick(room, effect, "Coloca 1 criatura " + back.filter().describe() + " de los materiales de " + name(self) + " en tu retaguardia.",
                        "A la retaguardia", self.getMaterials(), valid, 1);
                return true;
            }
            case PlaceSelfOnField place -> {
                CardSlot self = find(zone(player, place.zone()), effect.getSourceInstanceId());
                if (self == null) return false;
                zone(player, place.zone()).remove(self);
                self.setFaceDown(false);
                self.setTapped(false);
                self.setX(50.0);
                self.setY(75.0);
                player.getField().add(self);
                record(room, GameEvent.SEND, userId, self, place.zone(), "field", effect, null,
                        player.getUsername() + " coloca " + name(self) + " en su retaguardia (" + effect.getSourceName() + ")");
                return false;
            }
            case PlayFromZone play -> {
                List<CardSlot> valid = playable(room, effect, play);
                if (valid.isEmpty()) {
                    log(room, "No hay cartas " + play.filter().describe() + " que jugar de tus " + zoneName(play.zone()), null);
                    return false;
                }
                pick(room, effect, "Juega 1 carta " + play.filter().describe() + " de tus " + zoneName(play.zone())
                        + (play.reduce() > 0 ? " reduciendo su coste en " + play.reduce() : "") + ".", "Jugar", zones(player, play.zone()), ids(valid), 1);
                return true;
            }
            case TributeFromLookedRestToBottom tribute -> {
                if (effect.getLooked().isEmpty()) return false;
                int n = Math.min(tribute.count(), effect.getLooked().size());
                Prompt prompt = prompt(room, effect, Prompt.Kind.TAKE_REVEALED_REST_BOTTOM,
                        "Tributa " + n + " " + plural(n, "carta", "cartas") + ". Ordena el resto: irá al fondo de tu mazo en ese orden.");
                prompt.setCards(new ArrayList<>(effect.getLooked()));
                prompt.setValidIds(new ArrayList<>(ids(effect.getLooked())));
                prompt.setMin(n);
                prompt.setMax(n);
                prompt.setReorderRest(true);
                prompt.setChosenLabel("Tributar");
                room.setPrompt(prompt);
                return true;
            }
            case AddResourceFrom add -> {
                List<CardSlot> valid = resourceCandidates(room, effect, add);
                if (valid.isEmpty()) return false;
                List<CardSlot> shown = zones(player, add.zones());
                pick(room, effect, "Añade 1 carta " + add.filter().describe() + " como recurso de " + effect.getSourceName() + ".",
                        "Recurso", shown, ids(valid), 1);
                return true;
            }
            case ReturnSelfToHand ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null) return false;
                removeFromField(player, self);
                player.getHand().add(self);
                effect.setLastDone(true);
                record(room, GameEvent.SEND, userId, self, "field", "hand", effect, null,
                        player.getUsername() + " sube " + name(self) + " a su mano");
                return false;
            }
            case UntapStructure untap -> {
                List<CardSlot> valid = player.getField().stream()
                        .filter(c -> "structure".equals(c.getCardType()) && c.isTapped() && untap.filter().matches(c)).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Reactiva 1 de tus estructuras " + untap.filter().describe() + ".", "Reactivar", player.getField(), ids(valid), 1);
                return true;
            }
            case MayDo may -> {
                List<EffectStep> steps = script(effect).steps();
                int next = effect.getStepIndex() + 1;
                // "puedes A para B": offered only when everything it covers can be done
                boolean can = next < steps.size();
                for (int i = next; can && i < Math.min(steps.size(), next + may.covers()); i++) {
                    if (!(steps.get(i) instanceof IfDone) && !possible(room, effect, steps.get(i))) can = false;
                }
                if (!can) {
                    effect.setStepIndex(effect.getStepIndex() + may.covers());
                    return false;
                }
                Prompt prompt = prompt(room, effect, Prompt.Kind.CONFIRM, may.question());
                CardSlot source = findAnywhere(room, userId, effect.getSourceInstanceId());
                if (source != null) {
                    prompt.setCards(new ArrayList<>(List.of(source)));
                    prompt.setValidIds(new ArrayList<>(List.of(source.getInstanceId())));
                }
                prompt.setMin(0);
                prompt.setMax(1);
                prompt.setChosenLabel("Sí");
                room.setPrompt(prompt);
                return true;
            }
        }
    }

    /** Allies "sacrifica 1 [otro] aliado" may take. */
    static List<CardSlot> sacrificable(PendingEffect effect, PlayerState player, SacrificeAlly sacrifice) {
        List<CardSlot> all = sacrifice.toUntapAnother() ? sacrificeToUntap(player) : allies(player);
        return sacrifice.other() ? all.stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId())).toList() : all;
    }

    static void pick(GameRoom room, PendingEffect effect, String instruction, String chosenLabel,
                             List<CardSlot> cards, List<String> validIds, int count) {
        Prompt prompt = prompt(room, effect, Prompt.Kind.PICK, instruction);
        prompt.setCards(new ArrayList<>(cards));
        prompt.setValidIds(new ArrayList<>(validIds));
        prompt.setMin(count);
        prompt.setMax(count);
        prompt.setChosenLabel(chosenLabel);
        room.setPrompt(prompt);
    }

    static Prompt prompt(GameRoom room, PendingEffect effect, Prompt.Kind kind, String instruction) {
        Prompt p = new Prompt();
        p.setId(room.nextId("prompt"));
        p.setKind(kind);
        p.setPlayerId(effect.getControllerId());
        p.setEffectId(effect.getId());
        p.setSourceName(effect.getSourceName());
        CardSlot source = findAnywhere(room, effect.getControllerId(), effect.getSourceInstanceId());
        p.setSourceImageUrl(source == null ? null : source.getImageUrl());
        p.setTitle((effect.getLink() > 0 ? "Cadena " + effect.getLink() + " · " : "") + effect.getSourceName() + " · " + effect.getLabel());
        p.setEffectText(effect.getText());
        p.setInstruction(instruction);
        return p;
    }

    /**
     * Enemies the effect may choose: the opponent's Línea frontal (rule 4.0.6.2), matching the filter. A creature
     * in the Retaguardia cannot affect enemies with its effects.
     */
    static List<CardSlot> enemyTargets(GameRoom room, PendingEffect effect, CardFilter filter) {
        if (inRetaguardia(room, effect)) return List.of();
        PlayerState opponent = room.getPlayer(room.getOpponentId(effect.getControllerId()));
        if (opponent == null) return List.of();
        int level = sourceLevel(room, effect);
        return frontLine(opponent.getField()).stream().filter(c -> "creature".equals(c.getCardType())).filter(filter::matches)
                .filter(c -> !immuneTo(c, level)).toList();
    }

    /**
     * Reduces an enemy's strength by an effect, until the end of this turn or of the next one; at 0 it is
     * destroyed by that effect (rule 12.0.0.2). Records it: "si reduces la fuerza …" effects trigger from it.
     */
    static void reduceStrength(GameRoom room, PendingEffect effect, PlayerState owner, CardSlot target, int amount, boolean untilNextTurn) {
        if (untilNextTurn) target.setLaterStrength(target.getLaterStrength() - amount);
        else target.setTurnStrength(target.getTurnStrength() - amount);
        floating(target, effect, "Pierde " + amount + " de fuerza hasta el final del " + (untilNextTurn ? "próximo turno" : "turno"),
                untilNextTurn ? "nextTurn" : "turn", -amount, null);
        int now = Math.max(0, strength(target));
        record(room, GameEvent.STRENGTH_REDUCED, effect.getControllerId(), target, null, null, effect,
                Map.of("amount", amount, "to", now, "ownerId", owner.getUserId(), "untilNextTurn", untilNextTurn),
                name(target) + " pierde " + amount + " de fuerza (" + effect.getSourceName() + "): queda en " + now);
        if (now <= 0) destroy(room, target, owner, false, CAUSE_EFFECT, effect.getControllerId(), null, effect);
    }

    /** Direct damage by an effect to the opponent's top life: a creature with that strength or less is tributed, the rest go to the hand. */
    static void damageByEffect(GameRoom room, PendingEffect effect, PlayerState player, int damage) {
        damageByEffect(room, effect, player, damage, true);
    }

    /** {@code offer}: whether "en su lugar" of the damage (Estoque) is offered to the defender first; the damage then waits for the answer. */
    static void damageByEffect(GameRoom room, PendingEffect effect, PlayerState player, int damage, boolean offer) {
        PlayerState defender = room.getPlayer(room.getOpponentId(player.getUserId()));
        if (defender == null || defender.getLifeStack().isEmpty()) return;
        if (offer && !CombatRules.damageTributeCards(defender).isEmpty()) {
            room.getUsedThisTurn().add(PENDING_EFFECT_DAMAGE + effect.getId() + "|" + damage + "|" + player.getUserId());
            CombatRules.offerDamageTribute(room, defender, damage, "effect");
            return;
        }
        CardSlot life = defender.getLifeStack().remove(0);
        life.setFaceDown(false);
        boolean tribute = "creature".equals(life.getCardType()) && (life.getStrength() == null || CardEffects.lifeStrength(room, defender.getUserId(), life) <= damage);
        (tribute ? defender.getTributeZone() : defender.getHand()).add(life);
        record(room, GameEvent.LIFE_DAMAGE, player.getUserId(), life, "lifeStack", tribute ? "tributeZone" : "hand", effect,
                Map.of("damage", damage, "attackerId", effect.getSourceInstanceId(), "defenderId", defender.getUserId()),
                effect.getSourceName() + " inflige " + damage + " de daño directo: " + defender.getUsername() + " pierde la vida "
                        + name(life) + (tribute ? ", que va a sus tributos" : ", que va a su mano"));
        if (defender.getLifeStack().isEmpty()) loseByLives(room, defender);
    }

    /** Allies "elige 1 aliado" may choose; for an attack, only those in the Línea frontal (rule 10.1.1.1). */
    static List<CardSlot> pickable(GameRoom room, PendingEffect effect, PlayerState player, PickAlly pick) {
        List<EffectStep> steps = new ArrayList<>(script(effect).cost());
        steps.addAll(script(effect).steps());
        List<CardSlot> valid = allies(player).stream().filter(c -> !pick.attacker() || inFrontLine(c))
                // "añade esta carta como material a un aliado": another one
                .filter(c -> steps.stream().noneMatch(s -> s instanceof AttachSelfToSubject) || !c.getInstanceId().equals(effect.getSourceInstanceId()))
                // "elige 1 aliado: invoca 1 criatura del mismo nivel": one with something to summon
                .filter(c -> steps.stream().noneMatch(s -> s instanceof SummonLikeSubject like && likeSubject(player, like, c).isEmpty()))
                .toList();
        // "a un aliado para que invoque por …": only an ally that meets the Requerimiento of something it could summon
        List<EffectStep> resolution = script(effect).steps();
        SpecialFromZone special = resolution.stream().filter(s -> s instanceof SpecialFromZone sp && "subject".equals(sp.summoner()))
                .map(s -> (SpecialFromZone) s).findFirst().orElse(null);
        // Not when that summon is conditional ("Si lo haces: dicho aliado puede invocar …")
        if (special == null || conditional(resolution, resolution.indexOf(special))) return valid;
        String chosen = effect.getSubjectId();
        List<CardSlot> result = valid.stream().filter(ally -> {
            effect.setSubjectId(ally.getInstanceId());
            return !specialTargets(room, effect, special).isEmpty();
        }).toList();
        effect.setSubjectId(chosen);
        return result;
    }

    /** The effect's card is a creature in its owner's Retaguardia. */
    static boolean inRetaguardia(GameRoom room, PendingEffect effect) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        CardSlot source = player == null ? null : find(player.getField(), effect.getSourceInstanceId());
        return source != null && "creature".equals(source.getCardType()) && !inFrontLine(source);
    }

    /** A creature on the field leaves it (not destroyed): its Recursos and Materiales go to the Descarte and it loses its state. */

    /** The materials of your creatures on the field. */
    static List<CardSlot> allyMaterialsWhere(PlayerState player, TributeAllyMaterialWhere t) {
        return allies(player).stream().filter(t.ally()::matches).flatMap(a -> a.getMaterials().stream()).filter(t.material()::matches).toList();
    }

    static List<CardSlot> allyMaterials(PlayerState player) {
        return allies(player).stream().flatMap(a -> a.getMaterials().stream()).toList();
    }

    /** Allies "elige 1 aliado [filtro]" may choose. */
    static List<CardSlot> pickableWhere(GameRoom room, PendingEffect effect, PlayerState player, PickAllyWhere pick) {
        return pickable(room, effect, player, new PickAlly()).stream().filter(pick.filter()::matches).toList();
    }

    /** Cards an effect can play from a zone: utilities and structures whose reduced cost the controller can pay. */
    static List<CardSlot> playable(GameRoom room, PendingEffect effect, PlayFromZone play) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return List.of();
        return zones(player, play.zone()).stream()
                .filter(c -> ("utility".equals(c.getCardType()) || "structure".equals(c.getCardType())) && play.filter().matches(c))
                .filter(c -> canPay(room, player.getUserId(), Math.max(0, (c.getCost() == null ? 0 : c.getCost()) - play.reduce())))
                .filter(c -> Chain.cannotActivateOnPlay(room, player.getUserId(), c) == null)
                .toList();
    }

    /** Cards "añade 1 … como recurso de esta carta" may take: from those zones, matching, not this card. */
    static List<CardSlot> resourceCandidates(GameRoom room, PendingEffect effect, AddResourceFrom add) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null || find(player.getField(), effect.getSourceInstanceId()) == null) return List.of();
        List<CardSlot> all = new ArrayList<>();
        all.addAll(zones(player, add.zones()));
        return all.stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId())).filter(add.filter()::matches).toList();
    }

    /** Allies that may be sacrificed "para reactivar otro": another ally that can be reactivated must remain. */
    static List<CardSlot> sacrificeToUntap(PlayerState player) {
        List<CardSlot> allies = allies(player);
        return allies.stream().filter(s -> allies.stream().anyMatch(r -> r != s && reactivable(r))).toList();
    }

    /** The card the steps act on: the ally chosen by "elige 1 aliado", or the effect's own card. */
    static CardSlot subject(GameRoom room, PendingEffect effect) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return null;
        return find(player.getField(), effect.getSubjectId() != null ? effect.getSubjectId() : effect.getSourceInstanceId());
    }

    /**
     * The targets chosen on activation that are still valid: still in the opponent's Línea frontal (rule 11.10.2). Those
     * that are not are logged and skipped.
     */
    static List<CardSlot> validTargets(GameRoom room, PendingEffect effect, PlayerState opponent) {
        List<CardSlot> valid = new ArrayList<>();
        for (String id : effect.getTargetIds()) {
            CardSlot target = opponent == null ? null : frontLineCard(opponent, id);
            if (target != null) valid.add(target);
            else log(room, "El objetivo de " + effect.getSourceName() + " ya no es válido", null);
        }
        return valid;
    }

    /** Asks which of the looked cards go to the hand (up to {@code max} of {@code valid}). */
    static void takeFromLooked(GameRoom room, PendingEffect effect, Prompt.Kind kind, String instruction, List<String> valid, int max) {
        Prompt prompt = prompt(room, effect, kind, instruction);
        prompt.setCards(new ArrayList<>(effect.getLooked()));
        prompt.setValidIds(new ArrayList<>(valid));
        prompt.setMin(0);
        prompt.setMax(Math.min(max, valid.size()));
        prompt.setChosenLabel("A tu mano");
        room.setPrompt(prompt);
    }

    /** The chosen looked cards go to the hand. */
    static void lookedToHand(GameRoom room, PendingEffect effect, PlayerState player, List<String> picks) {
        for (String id : picks) {
            CardSlot card = find(effect.getLooked(), id);
            effect.getLooked().remove(card);
            card.setFaceDown(false);
            player.getHand().add(card);
            record(room, GameEvent.ADD_TO_HAND, player.getUserId(), card, "deck", "hand", effect, null,
                    player.getUsername() + " añade " + name(card) + " a su mano (" + effect.getSourceName() + ")");
        }
    }

    /** The chosen hand cards go to the Tributos; they are the last tributed, and how many is the last count. */
    static void tributeFromHand(GameRoom room, PendingEffect effect, PlayerState player, List<String> picks) {
        for (String id : picks) {
            CardSlot card = find(player.getHand(), id);
            player.getHand().remove(card);
            card.setFaceDown(false);
            player.getTributeZone().add(card);
            record(room, GameEvent.TRIBUTE, player.getUserId(), card, "hand", "tributeZone", effect, null,
                    player.getUsername() + " tributa " + name(card) + " de su mano (" + effect.getSourceName() + ")");
        }
        effect.setLastTributed(new ArrayList<>(picks));
        effect.setLastCount(picks.size());
    }

    /** This card leaves its zone to become a material ({@code asMaterial}) or a Recurso of the subject. */
    static void attachSelfToSubject(GameRoom room, PendingEffect effect, PlayerState player, boolean asMaterial) {
        Located at = locate(player, effect.getSourceInstanceId());
        CardSlot self = at == null ? null : at.card();
        CardSlot ally = subject(room, effect);
        if (self == null || ally == null || self == ally) return;
        if ("field".equals(at.zone())) {
            player.getField().remove(self);
            leaveField(self);
        } else {
            zone(player, at.zone()).remove(self);
            self.setFaceDown(false);
        }
        (asMaterial ? ally.getMaterials() : ally.getResources()).add(self);
        effect.setLastDone(true);
        record(room, GameEvent.SEND, player.getUserId(), self, at.zone(), asMaterial ? "materials" : "resources", effect,
                Map.of("to", ally.getInstanceId()),
                player.getUsername() + " añade " + name(self) + " como " + (asMaterial ? "material" : "recurso") + " de " + name(ally));
    }

    /** Allies with another ally of higher level ("1 aliado como material de otro de nivel mayor"). */
    static List<CardSlot> withHigherAlly(PlayerState player) {
        return allies(player).stream().filter(c -> !higherThan(player, c).isEmpty()).toList();
    }

    /** The player's other creatures of a higher level than {@code card}. */
    static List<CardSlot> higherThan(PlayerState player, CardSlot card) {
        int level = card.getLevel() == null ? 0 : card.getLevel();
        return allies(player).stream().filter(c -> c != card && c.getLevel() != null && c.getLevel() > level).toList();
    }

    /** Whether the step at {@code index} is covered by an earlier connector ("Si lo haces:", "puedes …", "Si …:"). */
    static boolean conditional(List<EffectStep> steps, int index) {
        for (int i = 0; i < index; i++) {
            int covers = Chain.covers(steps.get(i));
            if (covers > 0 && index <= i + covers) return true;
        }
        return false;
    }

    /** The level of the effect's card (0 when it has none), for "efectos de cartas nivel N o menor". */
    static int sourceLevel(GameRoom room, PendingEffect effect) {
        CardSlot source = findAnywhere(room, effect.getControllerId(), effect.getSourceInstanceId());
        return source == null || source.getLevel() == null ? 0 : source.getLevel();
    }

    /** The cards of one or more of the player's zones ("tributeZone,discardPile"). */
    static List<CardSlot> zones(PlayerState player, String csv) {
        List<CardSlot> all = new ArrayList<>();
        for (String z : csv.split(",")) all.addAll(zone(player, z.trim()));
        return all;
    }

    /** Which of those zones holds the card. */
    static String zoneOf(PlayerState player, String csv, CardSlot card) {
        for (String z : csv.split(",")) if (zone(player, z.trim()).contains(card)) return z.trim();
        return csv;
    }

    /** "1 criatura en el campo": yours, or an enemy's in its Línea frontal when this card is in yours (and it is not immune). */
    static List<CardSlot> creaturesOnField(GameRoom room, PendingEffect effect, CardFilter filter) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return List.of();
        List<CardSlot> all = new ArrayList<>(player.getField().stream()
                .filter(c -> "creature".equals(c.getCardType()) && !c.getInstanceId().equals(effect.getSourceInstanceId()) && filter.matches(c)).toList());
        all.addAll(enemyTargets(room, effect, filter));
        return all;
    }

    /** Creatures of the zone the effect may summon: with a normal cost the controller can pay after the reduction. */
    static List<CardSlot> summonable(GameRoom room, PendingEffect effect, SummonFromZone summon) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return List.of();
        boolean delayed = script(effect).moment() == EffectScript.Moment.DELAYED;
        return zones(player, summon.zone()).stream()
                .filter(c -> delayed || !c.getInstanceId().equals(effect.getSourceInstanceId()))
                .filter(c -> "creature".equals(c.getCardType()) && c.getCost() != null)
                .filter(summon.filter()::matches)
                .filter(c -> canPay(room, player.getUserId(), Math.max(0, c.getCost() - summon.reduce())))
                .toList();
    }

    /** Creatures of the zone of the ally's level and another name (summoned free). */
    static List<CardSlot> likeSubject(PlayerState player, SummonLikeSubject like, CardSlot ally) {
        if (ally.getLevel() == null) return List.of();
        return zone(player, like.zone()).stream()
                .filter(c -> "creature".equals(c.getCardType()) && c.getCost() != null && like.filter().matches(c))
                .filter(c -> ally.getLevel().equals(c.getLevel()) && !name(c).equalsIgnoreCase(name(ally)))
                .toList();
    }

    /** Creatures that fit within the level sum on their own (summoned free). */
    static List<CardSlot> freeLevelSum(GameRoom room, PendingEffect effect, SummonFreeLevelSum sum) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return List.of();
        return zone(player, sum.zone()).stream()
                .filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId()))
                .filter(c -> "creature".equals(c.getCardType()) && c.getCost() != null && c.getLevel() != null)
                .filter(sum.filter()::matches)
                .filter(c -> c.getLevel() <= sum.maxLevelSum())
                .toList();
    }

    static boolean materialToFront(MaterialToFront mf, CardSlot material) {
        return "creature".equals(material.getCardType()) && mf.filter().matches(material)
                && material.getLevel() != null && material.getLevel() == mf.level();
    }

    /** Carries out the step with the player's choice. Sets {@code repeat[0]} when the step asks again. */
    static String apply(GameRoom room, PendingEffect effect, EffectStep step, PlayerState player, List<String> picks,
                                List<String> order, boolean[] repeat) {
        return effect.getRitualTargetId() != null ? summoningAs(looker(room, effect), () -> applyAs(room, effect, step, player, picks, order, repeat))
                : lookingAs(looker(room, effect), () -> applyAs(room, effect, step, player, picks, order, repeat));
    }

    static String applyAs(GameRoom room, PendingEffect effect, EffectStep step, PlayerState player, List<String> picks,
                                List<String> order, boolean[] repeat) {
        String userId = player.getUserId();
        step = resolved(room, effect, step);
        switch (step) {

            case SpecialSelfDeclaredSummoner s -> {
                if (picks.isEmpty()) {
                    effect.setRitualSummonerId(null);
                    break;
                }
                CardSlot self = find(player.getHand(), effect.getSourceInstanceId());
                Summons.SpecialRequirement req = self == null ? null : specialRequirement(self);
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
            case TakeFromLookedEachRestToBottom take -> {
                List<CardSlot> chosen = picks.stream().map(id -> find(effect.getLooked(), id)).toList();
                if (chosen.contains(null)) return "Esa carta ya no está";
                if (!assignableEach(take.filters(), chosen)) return "Elige " + String.join(" y ", take.filters().stream().map(f -> "hasta 1 " + f.describe()).toList());
                lookedToHand(room, effect, player, picks);
                List<CardSlot> rest = inOrder(effect.getLooked(), order);
                rest.forEach(c -> c.setFaceDown(true));
                player.getDeck().addAll(rest);
                effect.getLooked().clear();
            }
            case MaterialToFrontLine ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                CardSlot card = self == null ? null : find(self.getMaterials(), picks.get(0));
                if (card == null) return "Ese material ya no está";
                self.getMaterials().remove(card);
                player.getHand().add(card);
                placeOnField(room, effect, player, card, "hand", true, false);
                effect.setLastDone(true);
            }
            case MaterialOfExhaustedAllyToBack m -> {
                if (picks.isEmpty()) break;
                CardSlot card = find(exhaustedAllyMaterials(player, m.filter()), picks.get(0));
                if (card == null) return "Ese material ya no está";
                for (CardSlot ally : player.getField()) ally.getMaterials().remove(card);
                player.getHand().add(card);
                placeOnField(room, effect, player, card, "hand", false, false);
                effect.setLastDone(true);
            }
            case TributeSubjectMaterialsUpTo ignored -> {
                CardSlot ally = subject(room, effect);
                if (ally == null || !allIn(ally.getMaterials(), picks)) return "Ese material ya no está";
                for (String id : picks) {
                    CardSlot material = find(ally.getMaterials(), id);
                    ally.getMaterials().remove(material);
                    material.setFaceDown(false);
                    player.getTributeZone().add(material);
                    record(room, GameEvent.TRIBUTE, userId, material, "materials", "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                            player.getUsername() + " tributa " + name(material) + " de los materiales de " + name(ally));
                }
                effect.setLastCount(picks.size());
            }
            case SacrificeAllyWeakerThanSelf ignored -> {
                CardSlot ally = find(player.getField(), picks.get(0));
                if (ally == null) return "Ese aliado ya no está en el campo";
                effect.setLastTributed(new ArrayList<>(List.of(ally.getInstanceId())));
                destroy(room, ally, player, true, CAUSE_EFFECT, userId, null, effect);
            }
            case EachPlayerKeepsOneInFrontLine ignored -> {
                if (find(player.getField(), picks.get(0)) == null) return "Ese aliado ya no está en el campo";
                effect.getTargetIds().add(picks.get(0));
                effect.setRepeatsDone(effect.getRepeatsDone() + 1);
                repeat[0] = true;
            }
            case GroupUp ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (picks.isEmpty() || self == null) break;
                int gained = 0;
                for (String id : picks) {
                    CardSlot ally = find(player.getField(), id);
                    if (ally == null || ally.isTapped()) return "Ese aliado ya no está activo";
                    ally.setTapped(true);
                    gained += strength(ally);
                    record(room, GameEvent.TAP, userId, ally, null, null, effect, null, player.getUsername() + " agota a " + name(ally) + " (Agrupar)");
                }
                self.setTurnStrength(self.getTurnStrength() + gained);
                // "Letalidad 1 por cada aliado agotado" (designer ruling 2026-10-31)
                String lethality = "Letalidad " + picks.size();
                self.getTurnKeywords().add(lethality);
                if (room.getCombat() != null) {
                    room.getCombat().getUntilEndOfAttack().add(self.getInstanceId() + "|" + STRENGTH_ENTRY + gained);
                    room.getCombat().getUntilEndOfAttack().add(self.getInstanceId() + "|" + lethality);
                }
                String extra = picks.size() == 1 ? "1 vez extra" : picks.size() + " veces extra";
                floating(self, effect, "Gana fuerza " + gained + " hasta el final del ataque (Agrupar)", "attack", gained, null);
                floating(self, effect, "Inflige daño " + extra + " (Agrupar)", "attack", 0, lethality);
                record(room, GameEvent.GAIN, userId, self, null, null, effect, Map.of("keyword", "Agrupar", "strength", gained),
                        name(self) + " agrupa: gana fuerza " + gained + " e inflige daño " + extra + " durante el ataque");
            }
            case UntapAllySameLevelAsLast ignored -> {
                CardSlot ally = find(player.getField(), picks.get(0));
                if (ally == null || !reactivable(ally)) return "Ese aliado ya no puede reactivarse";
                ally.setTapped(false);
                record(room, GameEvent.UNTAP, userId, ally, null, null, effect, null, player.getUsername() + " reactiva " + name(ally) + " (" + effect.getSourceName() + ")");
            }
            case TributeFromHandOrMaterials ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                List<String> fromHand = new ArrayList<>();
                for (String id : picks) {
                    if (find(player.getHand(), id) != null) {
                        fromHand.add(id);
                        continue;
                    }
                    CardSlot material = self == null ? null : find(self.getMaterials(), id);
                    if (material == null) return "Esa carta ya no está";
                    self.getMaterials().remove(material);
                    material.setFaceDown(false);
                    player.getTributeZone().add(material);
                    record(room, GameEvent.TRIBUTE, userId, material, "materials", "tributeZone", effect, Map.of("byCard", self.getInstanceId()),
                            player.getUsername() + " tributa " + name(material) + " de los materiales de " + name(self));
                }
                tributeFromHand(room, effect, player, fromHand);
                effect.setLastTributed(new ArrayList<>(picks));
                effect.setLastCount(picks.size());
            }
            case UntapOwnCard ignored -> {
                CardSlot card = find(player.getField(), picks.get(0));
                if (card == null || !card.isTapped()) return "Esa carta ya no está agotada";
                card.setTapped(false);
                effect.setLastDone(true);
                record(room, GameEvent.UNTAP, userId, card, null, null, effect, null, player.getUsername() + " reactiva " + name(card) + " (" + effect.getSourceName() + ")");
            }
            case TapOwnCard ignored -> {
                CardSlot card = find(player.getField(), picks.get(0));
                if (card == null || card.isTapped()) return "Esa carta ya no está activa";
                card.setTapped(true);
                effect.setLastDone(true);
                record(room, GameEvent.TAP, userId, card, null, null, effect, null, player.getUsername() + " agota " + name(card) + " (" + effect.getSourceName() + ")");
            }
            case AddAllyAsResource ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                CardSlot ally = find(player.getField(), picks.get(0));
                if (self == null || ally == null || ally == self) return "Ese aliado ya no está en el campo";
                removeFromField(player, ally);
                self.getResources().add(ally);
                effect.setLastDone(true);
                record(room, GameEvent.SEND, userId, ally, "field", "resources", effect, Map.of("to", self.getInstanceId()),
                        player.getUsername() + " añade a " + name(ally) + " como recurso de " + name(self));
            }
            case MoveAllyToFront ignored -> {
                if (picks.isEmpty()) break;
                CardSlot ally = find(player.getField(), picks.get(0));
                if (ally == null || inFrontLine(ally)) return "Ese aliado ya no está en tu retaguardia";
                ally.setY(25.0);
                effect.setLastDone(true);
                record(room, GameEvent.MOVE, userId, ally, null, "frontLine", effect, Map.of("reason", "effect"),
                        player.getUsername() + " mueve " + name(ally) + " a su línea frontal (" + effect.getSourceName() + ")");
            }
            case PlaceFromZone p -> {
                for (String id : picks) {
                    CardSlot card = find(zones(player, p.zone()), id);
                    if (card == null) return "Esa carta ya no está disponible";
                    placeOnField(room, effect, player, card, zoneOf(player, p.zone(), card), p.toFront(), p.exhausted());
                    effect.setSummonedId(card.getInstanceId());
                }
                effect.setLastDone(!picks.isEmpty());
            }
            case PlaceLastTributed p -> {
                if (picks.isEmpty()) break;
                Located at = locate(player, picks.get(0));
                if (at == null || "field".equals(at.zone())) return "Esa carta ya no está disponible";
                placeOnField(room, effect, player, at.card(), at.zone(), p.toFront(), p.exhausted());
                effect.setLastDone(true);
            }
            case TakeFromLookedDistinctLevels ignored -> {
                List<Integer> levels = picks.stream().map(id -> find(effect.getLooked(), id)).map(c -> c == null ? null : c.getLevel()).toList();
                if (new HashSet<>(levels).size() < levels.size()) return "Las cartas deben tener distinto nivel";
                lookedToHand(room, effect, player, picks);
                List<CardSlot> rest = inOrder(effect.getLooked(), order);
                rest.forEach(c -> c.setFaceDown(true));
                player.getDeck().addAll(rest);
                if (!rest.isEmpty()) log(room, player.getUsername() + " envía " + rest.size() + " " + plural(rest.size(), "carta", "cartas") + " al fondo de su mazo", null);
                effect.getLooked().clear();
            }
            case TakeFromLooked ignored -> lookedToHand(room, effect, player, picks);
            case TributeFromLooked ignored -> {
                for (String id : picks) {
                    CardSlot card = find(effect.getLooked(), id);
                    if (card == null) return "Esa carta ya no está";
                    effect.getLooked().remove(card);
                    card.setFaceDown(false);
                    player.getTributeZone().add(card);
                    record(room, GameEvent.TRIBUTE, userId, card, "deck", "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                            player.getUsername() + " tributa " + name(card) + " (" + effect.getSourceName() + ")");
                }
                effect.setLastTributed(new ArrayList<>(picks));
            }
            case DiscardFromLookedRestToBottom ignored -> {
                for (String id : picks) {
                    CardSlot card = find(effect.getLooked(), id);
                    if (card == null) return "Esa carta ya no está";
                    effect.getLooked().remove(card);
                    card.setFaceDown(false);
                    player.getDiscardPile().add(card);
                    record(room, GameEvent.SEND, userId, card, "deck", "discardPile", effect, null,
                            player.getUsername() + " envía " + name(card) + " a su descarte (" + effect.getSourceName() + ")");
                }
                List<CardSlot> rest = inOrder(effect.getLooked(), order);
                rest.forEach(c -> c.setFaceDown(true));
                player.getDeck().addAll(rest);
                effect.getLooked().clear();
            }
            case RevealFromHand r -> {
                List<CardSlot> cards = picks.stream().map(id -> find(player.getHand(), id)).toList();
                if (cards.contains(null)) return "Esa carta ya no está en tu mano";
                if (!assignable(cards, r.filters())) return "Revela " + String.join(" y ", r.filters().stream().map(f -> "1 " + f.describe()).toList());
                effect.setLastDone(true);
                effect.setLastTributed(new ArrayList<>(picks));
                log(room, player.getUsername() + " revela " + String.join(", ", cards.stream().map(EffectEngine::name).toList()) + " (" + effect.getSourceName() + ")", null);
            }
            case PickAttacker ignored -> {
                if (find(player.getField(), picks.get(0)) == null) return "Ese aliado ya no está en el campo";
                effect.setSubjectId(picks.get(0));
            }
            case LivesFaceDown ignored -> {
                CardSlot life = find(player.getLifeStack(), picks.get(0));
                if (life == null) return "Esa vida ya no está";
                life.setFaceDown(true);
                effect.setLastDone(true);
                log(room, player.getUsername() + " coloca bocabajo la vida " + name(life) + " (" + effect.getSourceName() + ")", null);
            }
            case TributeFromHandWhere ignored -> {
                if (!allIn(player.getHand(), picks)) return "Esa carta ya no está en tu mano";
                tributeFromHand(room, effect, player, picks);
            }
            case SendCopiesFromField s -> {
                for (String id : picks) {
                    CardSlot copy = find(player.getField(), id);
                    if (copy == null) return "Esa copia ya no está";
                    removeFromField(player, copy);
                    zone(player, s.zone()).add(copy);
                    record(room, GameEvent.SEND, userId, copy, "field", s.zone(), effect, null, player.getUsername() + " envía " + name(copy) + " a su " + zoneName(s.zone()));
                }
                effect.setLastDone(true);
            }
            case EachPlayerDiscards ignored -> {
                for (String id : picks) {
                    CardSlot card = find(player.getHand(), id);
                    if (card == null) return "Esa carta ya no está en tu mano";
                    player.getHand().remove(card);
                    card.setFaceDown(true);
                    player.getDiscardPile().add(card);
                }
                log(room, player.getUsername() + " descarta " + picks.size() + " " + plural(picks.size(), "carta", "cartas") + " bocabajo (" + effect.getSourceName() + ")", null);
                effect.setRepeatsDone(effect.getRepeatsDone() + 1);
                repeat[0] = true;
            }
            case DestroyEnemyNoTarget ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                CardSlot enemy = opponent == null ? null : find(frontLine(opponent.getField()), picks.get(0));
                if (enemy == null) return "Ese enemigo ya no está en la línea frontal";
                destroy(room, enemy, opponent, false, CAUSE_EFFECT, userId, null, effect);
            }
            case DiscardFromHand ignored -> {
                if (!allIn(player.getHand(), picks)) return "Esa carta ya no está en tu mano";
                for (String id : picks) {
                    CardSlot card = find(player.getHand(), id);
                    player.getHand().remove(card);
                    card.setFaceDown(false);
                    player.getDiscardPile().add(card);
                    record(room, GameEvent.SEND, userId, card, "hand", "discardPile", effect, null,
                            player.getUsername() + " descarta " + name(card) + " (" + effect.getSourceName() + ")");
                }
                effect.setLastTributed(new ArrayList<>(picks));
                effect.setLastCount(picks.size());
            }
            case ZoneToLife z -> {
                if (picks.isEmpty()) break;
                CardSlot card = find(zones(player, z.zone()), picks.get(0));
                if (card == null) return "Esa carta ya no está disponible";
                zone(player, zoneOf(player, z.zone(), card)).remove(card);
                card.setFaceDown(!z.faceUp());
                player.getLifeStack().add(0, card);
                effect.setLastDone(true);
                record(room, GameEvent.SEND, userId, card, zoneOf(player, z.zone(), card), "lifeStack", effect, null,
                        player.getUsername() + " coloca " + name(card) + " al tope de sus vidas" + (z.faceUp() ? ", bocarriba" : ""));
            }
            case DestroyAlly ignored -> {
                CardSlot ally = find(player.getField(), picks.get(0));
                if (ally == null) return "Ese aliado ya no está en el campo";
                effect.setLastTributed(new ArrayList<>(List.of(ally.getInstanceId())));
                effect.setLastDone(true);
                destroy(room, ally, player, false, CAUSE_EFFECT, userId, null, effect);
            }
            case DiscardMaterials ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null || !allIn(self.getMaterials(), picks)) return "Ese material ya no está";
                for (String id : picks) {
                    CardSlot material = find(self.getMaterials(), id);
                    self.getMaterials().remove(material);
                    material.setFaceDown(false);
                    player.getDiscardPile().add(material);
                    record(room, GameEvent.SEND, userId, material, "materials", "discardPile", effect, Map.of("byCard", self.getInstanceId()),
                            player.getUsername() + " descarta " + name(material) + " de los materiales de " + name(self));
                }
                effect.setLastCount(picks.size());
            }
            case UntapStructures ignored -> {
                for (String id : picks) {
                    CardSlot card = find(player.getField(), id);
                    if (card == null) return "Esa estructura ya no está";
                    card.setTapped(false);
                    record(room, GameEvent.UNTAP, userId, card, null, null, effect, null, player.getUsername() + " reactiva " + name(card) + " (" + effect.getSourceName() + ")");
                }
            }
            case TributeAllyResources ignored -> {
                if (effect.getSubjectId() == null || find(player.getField(), effect.getSubjectId()) == null) {
                    if (find(player.getField(), picks.get(0)) == null) return "Ese aliado ya no está en el campo";
                    effect.setSubjectId(picks.get(0));
                    repeat[0] = true;
                    return null;
                }
                CardSlot ally = find(player.getField(), effect.getSubjectId());
                for (String id : picks) {
                    CardSlot resource = find(ally.getResources(), id);
                    if (resource == null) return "Ese recurso ya no está";
                    ally.getResources().remove(resource);
                    resource.setFaceDown(false);
                    player.getTributeZone().add(resource);
                    record(room, GameEvent.TRIBUTE, userId, resource, "resources", "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                            player.getUsername() + " tributa " + name(resource) + " de los recursos de " + name(ally));
                }
                effect.setLastCount(picks.size());
            }
            case AddResourceToHigherAlly a -> {
                if (effect.getRitualTargetId() == null) {
                    effect.setRitualTargetId(picks.get(0));
                    repeat[0] = true;
                    return null;
                }
                CardSlot card = findAnywhere(room, userId, effect.getRitualTargetId());
                CardSlot ally = find(player.getField(), picks.get(0));
                effect.setRitualTargetId(null);
                if (card == null || ally == null) return "Esa carta ya no está disponible";
                String from = zoneOf(player, a.zone(), card);
                zone(player, from).remove(card);
                card.setFaceDown(false);
                ally.getResources().add(card);
                effect.setLastDone(true);
                record(room, GameEvent.SEND, userId, card, from, "resources", effect, Map.of("to", ally.getInstanceId()),
                        player.getUsername() + " añade " + name(card) + " como recurso de " + name(ally));
            }
            case RevealOpponentHandSendOneToBottom ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                CardSlot card = opponent == null ? null : find(opponent.getHand(), picks.get(0));
                if (card == null) return "Esa carta ya no está";
                opponent.getHand().remove(card);
                card.setFaceDown(true);
                opponent.getDeck().add(card);
                record(room, GameEvent.SEND, userId, card, "hand", "deck", effect, Map.of("ownerId", opponent.getUserId()),
                        player.getUsername() + " envía " + name(card) + " al fondo del mazo de " + opponent.getUsername() + " (" + effect.getSourceName() + ")");
            }
            case HandDownTo ignored -> {
                if (!allIn(player.getHand(), picks)) return "Esa carta ya no está en tu mano";
                for (String id : inOrder(picks.stream().map(i -> find(player.getHand(), i)).toList(), picks).stream().map(CardSlot::getInstanceId).toList()) {
                    CardSlot card = find(player.getHand(), id);
                    player.getHand().remove(card);
                    card.setFaceDown(true);
                    player.getDeck().add(card);
                }
                log(room, player.getUsername() + " envía " + picks.size() + " " + plural(picks.size(), "carta", "cartas") + " de su mano al fondo de su mazo", null);
            }
            case ChooseEnemiesLevelSum s -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                List<CardSlot> chosen = picks.stream().map(id -> opponent == null ? null : find(frontLine(opponent.getField()), id)).toList();
                if (chosen.contains(null)) return "Ese enemigo ya no está en la línea frontal";
                if (chosen.stream().mapToInt(c -> c.getLevel() == null ? 0 : c.getLevel()).sum() != s.sum()) return "Sus niveles deben sumar " + s.sum();
                effect.getTargetIds().addAll(picks);
                record(room, GameEvent.TARGET, userId, chosen.get(0), null, null, effect, Map.of("targetIds", picks),
                        player.getUsername() + " elige como objetivo a " + String.join(", ", chosen.stream().map(EffectEngine::name).toList()) + " (" + effect.getSourceName() + ")");
            }
            case SummonUpTo s -> {
                List<CardSlot> valid = summonable(room, effect, new SummonFromZone(s.zone(), s.filter(), s.reduce()));
                List<CardSlot> chosen = picks.stream().map(id -> find(valid, id)).toList();
                if (chosen.contains(null)) return "Ya no puedes invocar esa criatura";
                int total = chosen.stream().mapToInt(c -> Math.max(0, c.getCost() - s.reduce())).sum();
                if (!canPay(room, userId, total)) return "No tienes gemas para invocarlas todas (" + total + ")";
                for (CardSlot card : chosen) summonByEffect(room, effect, player, card, zoneOf(player, s.zone(), card), s.reduce(), false);
                effect.setLastDone(!chosen.isEmpty());
            }
            case ChooseFrontLineCreatures ignored -> {
                List<CardSlot> valid = frontLineCreatures(room, effect, CardFilter.any());
                if (!allIn(valid, picks)) return "Esa criatura ya no está en la línea frontal";
                effect.getTargetIds().addAll(picks);
                record(room, GameEvent.TARGET, userId, find(valid, picks.get(0)), null, null, effect, Map.of("targetIds", picks),
                        player.getUsername() + " elige " + picks.size() + " criaturas (" + effect.getSourceName() + ")");
            }
            case ReturnAllyOfSummonedLevel ignored -> {
                CardSlot ally = find(player.getField(), picks.get(0));
                if (ally == null) return "Ese aliado ya no está en el campo";
                removeFromField(player, ally);
                player.getHand().add(ally);
                effect.setLastTributed(new ArrayList<>(List.of(ally.getInstanceId())));
                record(room, GameEvent.SEND, userId, ally, "field", "hand", effect, null, player.getUsername() + " sube " + name(ally) + " a su mano (" + effect.getSourceName() + ")");
            }
            case TributeFromZoneUpTo t -> {
                for (String id : picks) {
                    CardSlot card = find(zones(player, t.zone()), id);
                    if (card == null) return "Esa carta ya no está";
                    String from = zoneOf(player, t.zone(), card);
                    zone(player, from).remove(card);
                    card.setFaceDown(false);
                    player.getTributeZone().add(card);
                    record(room, GameEvent.TRIBUTE, userId, card, from, "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                            player.getUsername() + " tributa " + name(card) + " (" + effect.getSourceName() + ")");
                }
                effect.setLastCount(picks.size());
                effect.setLastTributed(new ArrayList<>(picks));
            }
            case ChooseWeakerThanSelf ignored -> {
                if (picks.isEmpty()) break;
                CardSlot chosen = find(weakerThanSelf(room, effect), picks.get(0));
                if (chosen == null) return "Esa criatura ya no puede elegirse";
                effect.getTargetIds().add(chosen.getInstanceId());
                effect.setRepeatsDone(find(player.getField(), chosen.getInstanceId()) != null && !chosen.isTapped() ? ACTIVE_ALLY_CHOSEN : 0);
                effect.setLastDone(true);
                record(room, GameEvent.TARGET, userId, chosen, null, null, effect, Map.of("targetIds", picks),
                        player.getUsername() + " elige a " + name(chosen) + " (" + effect.getSourceName() + ")");
            }
            case TributeMaterialWhere ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                CardSlot material = self == null ? null : find(self.getMaterials(), picks.get(0));
                if (material == null) return "Ese material ya no está";
                self.getMaterials().remove(material);
                material.setFaceDown(false);
                player.getTributeZone().add(material);
                effect.setLastDone(true);
                record(room, GameEvent.TRIBUTE, userId, material, "materials", "tributeZone", effect, Map.of("byCard", self.getInstanceId()),
                        player.getUsername() + " tributa " + name(material) + " de los materiales de " + name(self));
            }
            case ReturnOwnCardToHand ignored -> {
                CardSlot card = find(player.getField(), picks.get(0));
                if (card == null) return "Esa carta ya no está en el campo";
                removeFromField(player, card);
                player.getHand().add(card);
                effect.setLastDone(true);
                record(room, GameEvent.SEND, userId, card, "field", "hand", effect, null, player.getUsername() + " sube " + name(card) + " a su mano (" + effect.getSourceName() + ")");
            }
            case TributeOwnCard ignored -> {
                CardSlot card = find(player.getField(), picks.get(0));
                if (card == null) return "Esa carta ya no está en el campo";
                removeFromField(player, card);
                player.getTributeZone().add(card);
                effect.setLastDone(true);
                record(room, GameEvent.TRIBUTE, userId, card, "field", "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                        player.getUsername() + " tributa " + name(card) + " (" + effect.getSourceName() + ")");
            }
            case SummonResource s -> {
                if (picks.isEmpty()) break;
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                CardSlot card = self == null ? null : find(self.getResources(), picks.get(0));
                if (card == null) return "Ese recurso ya no está";
                self.getResources().remove(card);
                player.getHand().add(card);
                summonByEffect(room, effect, player, card, "hand", s.reduce(), false);
                effect.setSummonedId(card.getInstanceId());
                effect.setLastDone(true);
            }
            case AddResourceFromZone add -> {
                if (picks.isEmpty()) break;
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                CardSlot card = find(resourceCandidates(room, effect, new AddResourceFrom(add.zones(), add.filter())), picks.get(0));
                if (self == null || card == null) return "Esa carta ya no está disponible";
                Located at = locate(player, card.getInstanceId());
                if ("field".equals(at.zone())) removeFromField(player, card);
                else zone(player, at.zone()).remove(card);
                card.setFaceDown(false);
                self.getResources().add(card);
                effect.setLastDone(true);
                record(room, GameEvent.SEND, userId, card, at.zone(), "resources", effect, Map.of("to", self.getInstanceId()),
                        player.getUsername() + " añade " + name(card) + " como recurso de " + name(self));
            }
            case HandToDeckBottom ignored -> {
                if (!allIn(player.getHand(), picks)) return "Esa carta ya no está en tu mano";
                for (String id : picks) {
                    CardSlot card = find(player.getHand(), id);
                    player.getHand().remove(card);
                    card.setFaceDown(true);
                    player.getDeck().add(card);
                }
                effect.setLastDone(true);
                log(room, player.getUsername() + " envía " + picks.size() + " " + plural(picks.size(), "carta", "cartas") + " de su mano al fondo de su mazo", null);
            }
            case AttachSelfAsResourceToAlly ignored -> {
                if (find(player.getField(), picks.get(0)) == null) return "Ese aliado ya no está en el campo";
                effect.setSubjectId(picks.get(0));
                attachSelfToSubject(room, effect, player, false);
            }
            case ChooseOpponentTribute ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                if (opponent == null || find(opponent.getTributeZone(), picks.get(0)) == null) return "Esa carta ya no está en los tributos de tu rival";
                effect.setRitualTargetId(picks.get(0));
            }
            case SendFromZoneToDeckBottomUpTo s -> {
                List<CardSlot> chosen = new ArrayList<>();
                for (String id : picks) {
                    CardSlot card = find(zones(player, s.zone()), id);
                    if (card == null) return "Esa carta ya no está";
                    chosen.add(card);
                }
                Collections.shuffle(chosen);
                for (CardSlot card : chosen) {
                    zone(player, zoneOf(player, s.zone(), card)).remove(card);
                    card.setFaceDown(true);
                    player.getDeck().add(card);
                }
                effect.setLastCount(chosen.size());
                log(room, player.getUsername() + " envía " + chosen.size() + " cartas al fondo de su mazo en orden aleatorio (" + effect.getSourceName() + ")", null);
            }
            case TapCopiesUpTo ignored -> {
                for (String id : picks) {
                    CardSlot copy = find(player.getField(), id);
                    if (copy == null || copy.isTapped()) return "Esa copia ya no está activa";
                }
                for (String id : picks) {
                    CardSlot copy = find(player.getField(), id);
                    copy.setTapped(true);
                    record(room, GameEvent.TAP, userId, copy, null, null, effect, null, player.getUsername() + " agota " + name(copy));
                }
                effect.setLastCount(picks.size());
            }
            case LookedToTopOrBottom ignored -> {
                // "devuélvelas al tope o envíalas al fondo": in the order the player chose
                boolean top = !picks.isEmpty();
                List<CardSlot> looked = inOrder(effect.getLooked(), order);
                looked.forEach(c -> c.setFaceDown(true));
                if (top) player.getDeck().addAll(0, looked);
                else player.getDeck().addAll(looked);
                effect.getLooked().clear();
                log(room, player.getUsername() + (top ? " devuelve " : " envía ") + looked.size() + " " + plural(looked.size(), "carta", "cartas")
                        + (top ? " al tope" : " al fondo") + " de su mazo (" + effect.getSourceName() + ")", null);
            }
            case TakeFromLookedRestToTributes ignored -> {
                if (!allIn(effect.getLooked(), picks)) return "Esa carta no está entre las miradas";
                lookedToHand(room, effect, player, picks);
                List<CardSlot> rest = new ArrayList<>(effect.getLooked());
                for (CardSlot card : rest) {
                    card.setFaceDown(false);
                    player.getTributeZone().add(card);
                    record(room, GameEvent.TRIBUTE, userId, card, "deck", "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                            player.getUsername() + " tributa " + name(card) + " (" + effect.getSourceName() + ")");
                }
                effect.setLastTributed(new ArrayList<>(ids(rest)));
                effect.getLooked().clear();
            }
            case ChooseCreatureOnField choose -> {
                List<CardSlot> valid = creaturesOnField(room, effect, choose.filter());
                if (!allIn(valid, picks)) return "Esa criatura ya no se puede elegir";
                effect.getTargetIds().addAll(picks);
                effect.setLastDone(!picks.isEmpty());
                if (!picks.isEmpty()) {
                    CardSlot target = findInRoom(room, picks.get(0));
                    record(room, GameEvent.TARGET, userId, target, null, null, effect, Map.of("targetIds", picks),
                            player.getUsername() + " elige a " + name(target) + " (" + effect.getSourceName() + ")");
                }
            }
            case PickAllyUpToSacrificedLevel ignored -> {
                if (find(player.getField(), picks.get(0)) == null) return "Ese aliado ya no está en el campo";
                effect.setSubjectId(picks.get(0));
            }
            case PickAllyWithHigherAlly ignored -> {
                if (find(player.getField(), picks.get(0)) == null) return "Ese aliado ya no está en el campo";
                effect.setSubjectId(picks.get(0));
            }
            case AttachSubjectToHigherAlly ignored -> {
                CardSlot subject = subject(room, effect);
                CardSlot holder = find(player.getField(), picks.get(0));
                if (subject == null || holder == null || !higherThan(player, subject).contains(holder)) return "Debe ser un aliado de nivel mayor";
                removeFromField(player, subject);
                subject.setFaceDown(false);
                holder.getMaterials().add(subject);
                effect.setLastDone(true);
                record(room, GameEvent.SEND, userId, subject, "field", "materials", effect, Map.of("to", holder.getInstanceId()),
                        player.getUsername() + " añade " + name(subject) + " como material de " + name(holder) + " (" + effect.getSourceName() + ")");
            }
            case PickAllyWithMaterials ignored -> {
                if (find(player.getField(), picks.get(0)) == null) return "Ese aliado ya no está en el campo";
                effect.setSubjectId(picks.get(0));
            }
            case TributeSubjectMaterials ignored -> {
                CardSlot subject = subject(room, effect);
                if (subject == null || !allIn(subject.getMaterials(), picks)) return "Ese material ya no está";
                for (String id : picks) {
                    CardSlot material = find(subject.getMaterials(), id);
                    subject.getMaterials().remove(material);
                    material.setFaceDown(false);
                    player.getTributeZone().add(material);
                    record(room, GameEvent.TRIBUTE, userId, material, "materials", "tributeZone", effect, Map.of("byCard", subject.getInstanceId()),
                            player.getUsername() + " tributa " + name(material) + " de los materiales de " + name(subject));
                }
                effect.setLastTributed(new ArrayList<>(picks));
                effect.setLastDone(true);
            }
            case MaterialsToHand ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null || !allIn(self.getMaterials(), picks)) return "Ese material ya no está";
                for (String id : picks) {
                    CardSlot m = find(self.getMaterials(), id);
                    self.getMaterials().remove(m);
                    m.setFaceDown(false);
                    player.getHand().add(m);
                    record(room, GameEvent.ADD_TO_HAND, userId, m, "materials", "hand", effect, null,
                            player.getUsername() + " sube " + name(m) + " (material de " + name(self) + ") a su mano");
                }
                effect.setLastTributed(new ArrayList<>(picks));
                effect.setLastDone(!picks.isEmpty());
            }
            case TributeFromHandAsSubjectLevel ignored -> {
                if (!allIn(player.getHand(), picks)) return "Esa carta ya no está en tu mano";
                tributeFromHand(room, effect, player, picks);
            }
            case TributeResources ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null || !allIn(self.getResources(), picks)) return "Esos recursos ya no están";
                for (String id : picks) {
                    CardSlot resource = find(self.getResources(), id);
                    self.getResources().remove(resource);
                    resource.setFaceDown(false);
                    player.getTributeZone().add(resource);
                    record(room, GameEvent.TRIBUTE, userId, resource, "resources", "tributeZone", effect, Map.of("byCard", self.getInstanceId()),
                            player.getUsername() + " tributa " + name(resource) + ", recurso de " + name(self));
                }
                effect.setLastTributed(new ArrayList<>(picks));
                effect.setLastDone(true);
            }
            case TakeFromLookedLikeSubject ignored -> {
                for (String id : picks) {
                    CardSlot card = find(effect.getLooked(), id);
                    if (card == null) return "Esa carta ya no está";
                    effect.getLooked().remove(card);
                    card.setFaceDown(false);
                    player.getHand().add(card);
                    record(room, GameEvent.ADD_TO_HAND, userId, card, "deck", "hand", effect, null,
                            player.getUsername() + " añade " + name(card) + " a su mano (" + effect.getSourceName() + ")");
                }
                List<CardSlot> rest = new ArrayList<>(effect.getLooked());
                Collections.shuffle(rest);
                rest.forEach(c -> c.setFaceDown(true));
                player.getDeck().addAll(rest);
                if (!rest.isEmpty()) log(room, player.getUsername() + " envía " + rest.size() + " " + plural(rest.size(), "carta", "cartas")
                        + " al fondo de su mazo en orden aleatorio", null);
                effect.getLooked().clear();
            }
            case DiscardLookedRestTop ignored -> {
                for (String id : picks) {
                    CardSlot card = find(effect.getLooked(), id);
                    if (card == null) return "Esa carta ya no está";
                    effect.getLooked().remove(card);
                    card.setFaceDown(true);
                    player.getDiscardPile().add(card);
                }
                log(room, player.getUsername() + " descarta " + picks.size() + " " + plural(picks.size(), "carta", "cartas") + " bocabajo ("
                        + effect.getSourceName() + ")", null);
                List<CardSlot> rest = inOrder(effect.getLooked(), order);
                rest.forEach(c -> c.setFaceDown(true));
                player.getDeck().addAll(0, rest);
                if (!rest.isEmpty()) log(room, player.getUsername() + " coloca " + rest.size() + " " + plural(rest.size(), "carta", "cartas")
                        + " al tope de su mazo", null);
                effect.getLooked().clear();
            }
            case ReduceRepeatedly r -> {
                if (picks.isEmpty()) {
                    effect.setRepeatsDone(0);
                    return null;
                }
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                CardSlot target = opponent == null ? null : find(frontLine(opponent.getField()), picks.get(0));
                if (target == null) return "Ese enemigo ya no está en la línea frontal";
                reduceStrength(room, effect, opponent, target, r.amount(), false);
                if ("attack".equals(r.until()) && room.getCombat() != null && find(opponent.getField(), target.getInstanceId()) != null)
                    room.getCombat().getUntilEndOfAttack().add(target.getInstanceId() + "|" + STRENGTH_ENTRY + (-r.amount()));
                effect.setRepeatsDone(effect.getRepeatsDone() + 1);
                repeat[0] = true;
            }
            case TributesToDeckBottomForKeyword d -> {
                if (picks.isEmpty()) return null;
                if (picks.size() != d.count()) return "Elige exactamente " + d.count() + " tributos, o ninguno";
                if (!allIn(player.getTributeZone(), picks)) return "Esas cartas ya no están en tus tributos";
                List<CardSlot> discarded = new ArrayList<>();
                for (String id : picks) {
                    CardSlot card = find(player.getTributeZone(), id);
                    player.getTributeZone().remove(card);
                    card.setFaceDown(true);
                    player.getDeck().add(card);
                    discarded.add(card);
                }
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self != null) {
                    Integer had = keywordLevel(self, d.keyword());
                    String gained = d.keyword() + " " + ((had == null ? 0 : had) + 1);
                    self.getTurnKeywords().add(gained);
                    if (room.getCombat() != null) room.getCombat().getUntilEndOfAttack().add(self.getInstanceId() + "|" + gained);
                    log(room, player.getUsername() + " descarta " + d.count() + " tributos (" + names(discarded).substring(2) + "): " + name(self)
                            + " tiene " + gained + " hasta el final del ataque", room.getEventSeq());
                }
                repeat[0] = true;
            }
            case TributeAlliesLevelSum t -> {
                List<CardSlot> chosen = new ArrayList<>();
                for (String id : picks) {
                    CardSlot c = find(player.getField(), id);
                    if (c == null || !t.filter().matches(c)) return "Ese aliado ya no está en el campo";
                    chosen.add(c);
                }
                int sum = chosen.stream().mapToInt(c -> c.getLevel() == null ? 0 : c.getLevel()).sum();
                if (chosen.size() < t.minCount() || sum < t.minSum())
                    return "Elige " + t.minCount() + " o más aliados que sumen " + t.minSum() + " o más en nivel (suman " + sum + ")";
                for (CardSlot c : chosen) {
                    player.getField().remove(c);
                    for (CardSlot a : concat(c.getMaterials(), c.getResources())) player.getDiscardPile().add(a);
                    c.setMaterials(new ArrayList<>());
                    c.setResources(new ArrayList<>());
                    leaveField(c);
                    player.getTributeZone().add(c);
                    record(room, GameEvent.TRIBUTE, userId, c, "field", "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                            player.getUsername() + " tributa a " + name(c) + " (" + effect.getSourceName() + ")");
                }
            }
            case PlaceFromHandUntilTurnEnd ignored -> {
                if (picks.isEmpty()) return null;
                CardSlot card = find(player.getHand(), picks.get(0));
                if (card == null) return "Esa carta ya no está en tu mano";
                player.getHand().remove(card);
                card.setFaceDown(false);
                card.setTapped(false);
                card.setX(50.0);
                card.setY(75.0);
                card.setReturnAtTurnEnd(true);
                player.getField().add(card);
                // Its details show the condition it is placed under (Janne's inherited effect)
                String from = CardEffects.inheritedFrom(effect.getScriptId());
                CardSlot holder = from == null ? null : find(player.getField(), effect.getSourceInstanceId());
                String giver = holder == null ? from : holder.getResources().stream().map(CardSlot::getCardName)
                        .filter(from::equalsIgnoreCase).findFirst().orElse(from);
                floating(card, effect, "Colocada hasta el final del turno: entonces vuelve a tu mano" + (giver == null ? "" : " (heredado de " + giver + ")"),
                        "turn", 0, null);
                record(room, GameEvent.SEND, userId, card, "hand", "field", effect, Map.of("placed", true),
                        player.getUsername() + " coloca " + name(card) + " en su retaguardia hasta el final del turno (" + effect.getSourceName() + ")");
            }
            case TributeAllyMaterialWhere t -> {
                CardSlot holder = allies(player).stream().filter(a -> t.ally().matches(a) && find(a.getMaterials(), picks.get(0)) != null)
                        .findFirst().orElse(null);
                if (holder == null) return "Ese material ya no está";
                CardSlot material = find(holder.getMaterials(), picks.get(0));
                holder.getMaterials().remove(material);
                material.setFaceDown(false);
                player.getTributeZone().add(material);
                effect.setSubjectId(holder.getInstanceId());
                effect.setLastTributed(new ArrayList<>(List.of(material.getInstanceId())));
                record(room, GameEvent.TRIBUTE, userId, material, "materials", "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                        player.getUsername() + " tributa " + name(material) + " de los materiales de " + name(holder) + " (" + effect.getSourceName() + ")");
            }
            case OpponentMovesOneToFront ignored -> {
                CardSlot card = find(player.getField(), picks.get(0));
                if (card == null || inFrontLine(card)) return "Esa criatura ya no está en tu retaguardia";
                card.setY(25.0);
                record(room, GameEvent.MOVE, userId, card, null, "frontLine", effect, Map.of("reason", "effect"),
                        player.getUsername() + " mueve " + name(card) + " a su línea frontal (" + effect.getSourceName() + ")");
            }
            case TakeFromLookedRestToBottom ignored -> {
                lookedToHand(room, effect, player, picks);
                // The rest go to the deck bottom in the order the player left them (not part of the timeline)
                List<CardSlot> rest = inOrder(effect.getLooked(), order);
                for (CardSlot card : rest) {
                    card.setFaceDown(true);
                    player.getDeck().add(card);
                }
                if (!rest.isEmpty()) {
                    log(room, player.getUsername() + " envía " + rest.size() + " " + plural(rest.size(), "carta", "cartas") + " al fondo de su mazo", null);
                }
                effect.getLooked().clear();
            }
            case TributeFromHand ignored -> {
                if (!allIn(player.getHand(), picks)) return "Esa carta ya no está en tu mano";
                tributeFromHand(room, effect, player, picks);
            }
            case DestroyEnemy ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                if (opponent == null || !allIn(frontLine(opponent.getField()), picks)) return "Ese enemigo ya no está en la línea frontal";
                for (String id : picks) destroy(room, find(opponent.getField(), id), opponent, false, CAUSE_EFFECT, userId, null, effect);
            }
            case ChooseEnemy ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                if (opponent == null || !allIn(frontLine(opponent.getField()), picks)) return "Ese enemigo ya no está en la línea frontal";
                // Several choices of one effect add up ("elige hasta 1 enemigo nivel 2 y 1 enemigo nivel 1")
                effect.getTargetIds().addAll(picks);
                // "elige hasta N": none chosen
                if (picks.isEmpty()) break;
                List<String> names = picks.stream().map(id -> name(find(opponent.getField(), id))).toList();
                record(room, GameEvent.TARGET, userId, find(opponent.getField(), picks.get(0)), null, null, effect, Map.of("targetIds", picks),
                        player.getUsername() + " elige como objetivo a " + String.join(", ", names) + " (" + effect.getSourceName() + ")");
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
            case SummonFromZone summon -> {
                CardSlot card = find(summonable(room, effect, summon), picks.get(0));
                if (card == null) return "Ya no puedes invocar esa criatura";
                summonByEffect(room, effect, player, card, zoneOf(player, summon.zone(), card), summon.reduce(), summon.toFront());
                effect.setSummonedId(card.getInstanceId());
                effect.setLastDone(true);
            }
            case SummonLikeSubject like -> {
                CardSlot ally = subject(room, effect);
                CardSlot card = ally == null ? null : find(likeSubject(player, like, ally), picks.get(0));
                if (card == null) return "Ya no puedes invocar esa criatura";
                summonByEffect(room, effect, player, card, like.zone(), card.getCost(), true);
                effect.setSummonedId(card.getInstanceId());
                effect.setLastDone(true);
            }
            case RitualFromZone ritual -> {
                if (effect.getRitualTargetId() == null) {
                    effect.setRitualTargetId(picks.get(0));
                    repeat[0] = true;
                    return null;
                }
                CardSlot target = find(zone(player, ritual.zone()), effect.getRitualTargetId());
                if (target == null) return "Esa criatura ya no está en tus " + zoneName(ritual.zone());
                Requirement req = requirement(target);
                CardSlot summoner = ritual.selfSummoner() ? find(player.getField(), effect.getSourceInstanceId())
                        : find(player.getField(), effect.getRitualSummonerId());
                if (summoner == null) {
                    // This answer is the summoner
                    if (find(player.getField(), picks.get(0)) == null) return "Esa criatura ya no está en el campo";
                    effect.setRitualSummonerId(picks.get(0));
                    repeat[0] = true;
                    return null;
                }
                List<CardSlot> others = new ArrayList<>();
                for (String id : picks) {
                    CardSlot c = find(player.getField(), id);
                    if (c == null) return "Esa criatura ya no está en el campo";
                    if (c != summoner) others.add(c);
                }
                int total = summoner.getLevel() + others.stream().mapToInt(CardSlot::getLevel).sum();
                if (req.sum() && total != req.level()) return "Los niveles suman " + total + ": deben sumar exactamente " + req.level();
                performRitual(room, effect, player, target, ritual, summoner, others);
                effect.setRitualSummonerId(null);
            }
            case SendFromZoneToDeckBottom send -> {
                List<CardSlot> from = zones(player, send.zone());
                if (!allIn(from, picks)) return "Esa carta ya no está en tus " + zonesText(send.zone());
                List<CardSlot> sent = new ArrayList<>();
                for (String id : picks) {
                    CardSlot card = find(from, id);
                    String fromZone = zoneOf(player, send.zone(), card);
                    zone(player, fromZone).remove(card);
                    card.setFaceDown(true);
                    player.getDeck().add(card);
                    sent.add(card);
                    record(room, GameEvent.SEND, userId, card, fromZone, "deck", effect, Map.of("position", "bottom"), null);
                }
                log(room, player.getUsername() + " envía " + picks.size() + " " + plural(picks.size(), "carta", "cartas") + " de sus "
                        + zoneName(send.zone()) + " al fondo de su mazo (" + effect.getSourceName() + ")" + (publicZone(send.zone()) ? names(sent) : ""),
                        room.getEventSeq());
            }
            case PickAlly ignored -> {
                if (find(player.getField(), picks.get(0)) == null) return "Ese aliado ya no está en el campo";
                effect.setSubjectId(picks.get(0));
            }
            case SacrificeAlly ignored -> {
                CardSlot ally = find(player.getField(), picks.get(0));
                if (ally == null) return "Ese aliado ya no está en el campo";
                effect.setLastTributed(new ArrayList<>(List.of(ally.getInstanceId())));
                destroy(room, ally, player, true, CAUSE_EFFECT, userId, null, effect);
            }
            case TributeMaterials ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null || !allIn(self.getMaterials(), picks)) return "Ese material ya no está";
                for (String id : picks) {
                    CardSlot material = find(self.getMaterials(), id);
                    self.getMaterials().remove(material);
                    material.setFaceDown(false);
                    player.getTributeZone().add(material);
                    record(room, GameEvent.TRIBUTE, userId, material, "materials", "tributeZone", effect, Map.of("byCard", self.getInstanceId()),
                            player.getUsername() + " tributa " + name(material) + " de los materiales de " + name(self));
                }
                effect.setLastCount(picks.size());
            }
            case TakePerCount take -> {
                List<CardSlot> from = zone(player, take.zone());
                if (!allIn(from, picks)) return "Esa carta ya no está en tus " + zoneName(take.zone());
                for (String id : picks) {
                    CardSlot card = find(from, id);
                    from.remove(card);
                    card.setFaceDown(false);
                    player.getHand().add(card);
                    record(room, GameEvent.ADD_TO_HAND, userId, card, take.zone(), "hand", effect, null,
                            player.getUsername() + " toma " + name(card) + " de sus " + zoneName(take.zone()) + " (" + effect.getSourceName() + ")");
                }
            }
            case TributeFromHandUpTo ignored -> {
                if (!allIn(player.getHand(), picks)) return "Esa carta ya no está en tu mano";
                tributeFromHand(room, effect, player, picks);
            }
            case MaterialToLife ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                for (String id : picks) {
                    CardSlot material = self == null ? null : find(self.getMaterials(), id);
                    if (material == null) return "Ese material ya no está";
                    self.getMaterials().remove(material);
                    material.setFaceDown(false);
                    player.getLifeStack().add(0, material);
                    record(room, GameEvent.SEND, userId, material, "materials", "lifeStack", effect, null,
                            player.getUsername() + " coloca " + name(material) + " como vida bocarriba (" + effect.getSourceName() + ")");
                }
            }
            case MaterialToFront ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (picks.isEmpty() || self == null) return null;
                CardSlot card = find(self.getMaterials(), picks.get(0));
                if (card == null) return "Ese material ya no está";
                self.getMaterials().remove(card);
                card.setFaceDown(false);
                card.setTapped(false);
                card.setX(50.0);
                card.setY(25.0);
                player.getField().add(card);
                record(room, GameEvent.SEND, userId, card, "materials", "field", effect, null,
                        player.getUsername() + " coloca " + name(card) + " en su línea frontal (" + effect.getSourceName() + ")");
                List<CardSlot> rest = new ArrayList<>(self.getMaterials());
                self.getMaterials().clear();
                card.getMaterials().addAll(rest);
                if (!rest.isEmpty()) log(room, player.getUsername() + " añade " + rest.size() + " " + plural(rest.size(), "material", "materiales")
                        + " de " + name(self) + " a " + name(card), room.getEventSeq());
            }
            case AttachLowerFromTributes ignored -> {
                CardSlot ally = find(player.getField(), effect.getSubjectId());
                CardSlot card = find(player.getTributeZone(), picks.get(0));
                if (ally == null || card == null) return "Esa carta ya no está disponible";
                player.getTributeZone().remove(card);
                card.setFaceDown(false);
                ally.getMaterials().add(card);
                record(room, GameEvent.SEND, userId, card, "tributeZone", "materials", effect, Map.of("to", ally.getInstanceId()),
                        player.getUsername() + " añade " + name(card) + " como material de " + name(ally) + " (" + effect.getSourceName() + ")");
            }
            case ToDeckTop top -> {
                List<CardSlot> from = zone(player, top.zone());
                if (!allIn(from, picks)) return "Esa carta ya no está en tu " + zoneName(top.zone());
                List<CardSlot> placed = new ArrayList<>();
                for (String id : picks) {
                    CardSlot card = find(from, id);
                    from.remove(card);
                    card.setFaceDown(true);
                    player.getDeck().add(0, card);
                    placed.add(card);
                    record(room, GameEvent.SEND, userId, card, top.zone(), "deck", effect, Map.of("position", "top"), null);
                }
                if (!picks.isEmpty()) log(room, player.getUsername() + " coloca " + picks.size() + " " + plural(picks.size(), "carta", "cartas")
                        + " al tope de su mazo (" + effect.getSourceName() + ")" + (publicZone(top.zone()) ? names(placed) : ""), room.getEventSeq());
                effect.setLastDone(!picks.isEmpty());
            }
            case DestroyFaceUpLife ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                for (String id : picks) {
                    CardSlot life = opponent == null ? null : find(opponent.getLifeStack(), id);
                    if (life == null) return "Esa vida ya no está";
                    opponent.getLifeStack().remove(life);
                    opponent.getDiscardPile().add(life);
                    record(room, GameEvent.DESTROY, userId, life, "lifeStack", "discardPile", effect,
                            Map.of("cause", CAUSE_EFFECT, "ownerId", opponent.getUserId(), "byCard", effect.getSourceInstanceId()),
                            player.getUsername() + " destruye la vida " + name(life) + " de " + opponent.getUsername() + " (" + effect.getSourceName() + ")");
                }
            }
            case TakeFromZone take -> {
                List<CardSlot> from = zones(player, take.zone());
                if (!allIn(from, picks)) return "Esa carta ya no está en tus " + zonesText(take.zone());
                for (String id : picks) {
                    CardSlot card = find(from, id);
                    zone(player, zoneOf(player, take.zone(), card)).remove(card);
                    card.setFaceDown(false);
                    player.getHand().add(card);
                    record(room, GameEvent.ADD_TO_HAND, userId, card, take.zone(), "hand", effect, null,
                            player.getUsername() + " toma " + name(card) + " de sus " + zoneName(take.zone()) + " (" + effect.getSourceName() + ")");
                }
                effect.setLastDone(!picks.isEmpty());
            }
            case SpecialFromZone special -> {
                if (effect.getRitualTargetId() == null) {
                    if (picks.isEmpty()) return null;
                    effect.setRitualTargetId(picks.get(0));
                    repeat[0] = true;
                    return null;
                }
                CardSlot target = find(specialZones(player, special), effect.getRitualTargetId());
                if (target == null) return "Esa criatura ya no está disponible";
                SpecialRequirement req = specialRequirement(target);
                boolean bySubject = "subject".equals(special.summoner()) || "self".equals(special.summoner());
                CardSlot summoner = "subject".equals(special.summoner()) ? subject(room, effect)
                        : "self".equals(special.summoner()) ? find(player.getField(), effect.getSourceInstanceId())
                        : find(player.getField(), effect.getRitualSummonerId());
                if (summoner == null) {
                    if (find(player.getField(), picks.get(0)) == null) return "Esa criatura ya no está en el campo";
                    effect.setRitualSummonerId(picks.get(0));
                    if ("materialization".equals(req.kind())) {
                        repeat[0] = true;
                        return null;
                    }
                    summoner = find(player.getField(), picks.get(0));
                } else if (!"materialization".equals(req.kind())) {
                    return "Este paso ya tiene invocador";
                }
                List<CardSlot> materials = new ArrayList<>();
                if ("materialization".equals(req.kind()) && !picks.isEmpty() && !picks.get(0).equals(summoner.getInstanceId())) {
                    for (String id : picks) {
                        CardSlot material = find(player.getTributeZone(), id);
                        if (material == null) material = find(player.getField(), id);
                        if (material == null) return "Ese material ya no está disponible";
                        materials.add(material);
                    }
                    String wrong = checkMaterials(req, summoner, materials);
                    if (wrong != null) return wrong;
                }
                String from = find(player.getHand(), target.getInstanceId()) != null ? "hand"
                        : find(player.getTributeZone(), target.getInstanceId()) != null ? "tributeZone" : special.zone().split(",")[0].trim();
                effect.setRitualTargetId(null);
                effect.setRitualSummonerId(null);
                specialSummon(room, player, target, req, summoner, materials, from, special.reduce(), effect);
                effect.setSummonedId(target.getInstanceId());
                effect.setLastDone(true);
            }
            case UntapAlly ignored -> {
                CardSlot ally = find(player.getField(), picks.get(0));
                if (ally == null || !reactivable(ally)) return "Ese aliado ya no puede reactivarse";
                ally.setTapped(false);
                record(room, GameEvent.UNTAP, userId, ally, null, null, effect, null,
                        player.getUsername() + " reactiva " + name(ally) + " (" + effect.getSourceName() + ")");
            }
            case UntapAllies untap -> {
                if (picks.size() > untap.max()) return "Elige hasta " + untap.max();
                for (String id : picks) {
                    CardSlot ally = find(player.getField(), id);
                    if (ally == null || !reactivable(ally)) return "Ese aliado ya no puede reactivarse";
                }
                for (String id : picks) {
                    CardSlot ally = find(player.getField(), id);
                    ally.setTapped(false);
                    record(room, GameEvent.UNTAP, userId, ally, null, null, effect, null,
                            player.getUsername() + " reactiva " + name(ally) + " (" + effect.getSourceName() + ")");
                }
            }
            case DeclareAttack ignored -> {
                CardSlot subject = subject(room, effect);
                if (subject != null && room.getCombat() == null) {
                    startCombat(room, userId, subject, picks.isEmpty() ? null : picks.get(0), true, effect);
                }
            }
            case ChooseWeakestEnemy ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                CardSlot target = opponent == null ? null : find(frontLine(opponent.getField()), picks.get(0));
                if (target == null) return "Ese enemigo ya no está en la línea frontal";
                effect.getTargetIds().add(target.getInstanceId());
                record(room, GameEvent.TARGET, userId, target, null, null, effect, Map.of("targetIds", picks),
                        player.getUsername() + " elige como objetivo a " + name(target) + " (" + effect.getSourceName() + ")");
            }
            case SacrificeAllyWhere ignored -> {
                CardSlot ally = find(player.getField(), picks.get(0));
                if (ally == null) return "Ese aliado ya no está en el campo";
                effect.setLastTributed(new ArrayList<>(List.of(ally.getInstanceId())));
                destroy(room, ally, player, true, CAUSE_EFFECT, userId, null, effect);
            }
            case TributeAlly ignored -> {
                CardSlot ally = find(player.getField(), picks.get(0));
                if (ally == null) return "Ese aliado ya no está en el campo";
                removeFromField(player, ally);
                player.getTributeZone().add(ally);
                record(room, GameEvent.TRIBUTE, userId, ally, "field", "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                        player.getUsername() + " tributa a " + name(ally) + " (" + effect.getSourceName() + ")");
            }
            case TributeAllyMaterial ignored -> {
                CardSlot holder = allies(player).stream().filter(a -> find(a.getMaterials(), picks.get(0)) != null).findFirst().orElse(null);
                if (holder == null) return "Ese material ya no está";
                CardSlot material = find(holder.getMaterials(), picks.get(0));
                holder.getMaterials().remove(material);
                material.setFaceDown(false);
                player.getTributeZone().add(material);
                record(room, GameEvent.TRIBUTE, userId, material, "materials", "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                        player.getUsername() + " tributa " + name(material) + " de los materiales de " + name(holder) + " (" + effect.getSourceName() + ")");
            }
            case PickAllyWhere ignored -> {
                if (find(player.getField(), picks.get(0)) == null) return "Ese aliado ya no está en el campo";
                effect.setSubjectId(picks.get(0));
            }
            case MaterialToBack ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                CardSlot card = self == null ? null : find(self.getMaterials(), picks.get(0));
                if (card == null) return "Ese material ya no está";
                self.getMaterials().remove(card);
                card.setFaceDown(false);
                card.setTapped(false);
                card.setX(50.0);
                card.setY(75.0);
                player.getField().add(card);
                record(room, GameEvent.SEND, userId, card, "materials", "field", effect, null,
                        player.getUsername() + " coloca " + name(card) + " en su retaguardia (" + effect.getSourceName() + ")");
            }
            case PlayFromZone play -> {
                CardSlot card = find(playable(room, effect, play), picks.get(0));
                if (card == null) return "Ya no puedes jugar esa carta";
                int cost = Math.max(0, (card.getCost() == null ? 0 : card.getCost()) - play.reduce());
                pay(room, userId, cost);
                zone(player, zoneOf(player, play.zone(), card)).remove(card);
                player.getHand().add(card);
                play(room, player, card, cost, null, null, effect);
                effect.setLastDone(true);
            }
            case TributeFromLookedRestToBottom ignored -> {
                for (String id : picks) {
                    CardSlot card = find(effect.getLooked(), id);
                    effect.getLooked().remove(card);
                    card.setFaceDown(false);
                    player.getTributeZone().add(card);
                    record(room, GameEvent.TRIBUTE, userId, card, "deck", "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                            player.getUsername() + " tributa " + name(card) + " (" + effect.getSourceName() + ")");
                }
                List<CardSlot> rest = inOrder(effect.getLooked(), order);
                for (CardSlot card : rest) {
                    card.setFaceDown(true);
                    player.getDeck().add(card);
                }
                if (!rest.isEmpty()) log(room, player.getUsername() + " envía " + rest.size() + " " + plural(rest.size(), "carta", "cartas") + " al fondo de su mazo", null);
                effect.getLooked().clear();
            }
            case AddResourceFrom add -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                CardSlot card = find(resourceCandidates(room, effect, add), picks.get(0));
                if (self == null || card == null) return "Esa carta ya no está disponible";
                Located at = locate(player, card.getInstanceId());
                if ("field".equals(at.zone())) removeFromField(player, card);
                else zone(player, at.zone()).remove(card);
                card.setFaceDown(false);
                self.getResources().add(card);
                effect.setLastDone(true);
                record(room, GameEvent.SEND, userId, card, at.zone(), "resources", effect, Map.of("to", self.getInstanceId()),
                        player.getUsername() + " añade " + name(card) + " como recurso de " + name(self));
            }
            case UntapStructure ignored -> {
                CardSlot card = find(player.getField(), picks.get(0));
                if (card == null) return "Esa estructura ya no está";
                card.setTapped(false);
                record(room, GameEvent.UNTAP, userId, card, null, null, effect, null,
                        player.getUsername() + " reactiva " + name(card) + " (" + effect.getSourceName() + ")");
            }
            case MayDo may -> {
                // Declined: skip what it covers
                if (picks.isEmpty()) effect.setStepIndex(effect.getStepIndex() + may.covers());
            }
            default -> {
                return "Este paso no espera ninguna elección";
            }
        }
        return null;
    }

    static boolean allIn(List<CardSlot> cards, List<String> ids) {
        return ids.stream().allMatch(id -> find(cards, id) != null);
    }

    /** {@code cards} in the order given by {@code order}; ids missing from it keep their place at the end. */
    /** A card's level as it was on the field when it was last destroyed or sacrificed (its continuous levels included). */
    static int levelWhenRemoved(GameRoom room, CardSlot card) {
        if (card == null) return 0;
        List<GameEvent> timeline = room.getTimeline();
        for (int i = timeline.size() - 1; i >= 0; i--) {
            GameEvent e = timeline.get(i);
            if (!card.getInstanceId().equals(e.getCardInstanceId())) continue;
            if ((GameEvent.DESTROY.equals(e.getType()) || GameEvent.SACRIFICE.equals(e.getType())) && e.getData() != null
                    && e.getData().get("level") instanceof Integer level) return level;
        }
        return card.getLevel() == null ? 0 : card.getLevel();
    }

    static List<CardSlot> inOrder(List<CardSlot> cards, List<String> order) {
        List<CardSlot> result = new ArrayList<>();
        if (order != null) {
            for (String id : order) {
                CardSlot card = find(cards, id);
                if (card != null && !result.contains(card)) result.add(card);
            }
        }
        for (CardSlot card : cards) if (!result.contains(card)) result.add(card);
        return result;
    }

    /** "puede considerarse": the marker of an ally chosen while active ({@link IfChosenWasActiveAlly}), kept in {@code repeatsDone}. */
    static final int ACTIVE_ALLY_CHOSEN = 7001;

    /** The mark of an effect that goes on at the end of the turn: controller, card, script and subject ({@link AtEndOfTurn}). */
    static final String DELAYED = "delayed|";

    /** A card placed on the field by an effect (not summoned): face up, in the Línea frontal or the Retaguardia. */
    /** Materials of the exhausted allies that match. */
    static List<CardSlot> exhaustedAllyMaterials(PlayerState player, CardFilter filter) {
        return allies(player).stream().filter(CardSlot::isTapped).flatMap(a -> a.getMaterials().stream()).filter(filter::matches).toList();
    }

    static void placeOnField(GameRoom room, PendingEffect effect, PlayerState owner, CardSlot card, String fromZone, boolean toFront, boolean exhausted) {
        zone(owner, fromZone).remove(card);
        card.setFaceDown(false);
        card.setTapped(exhausted);
        card.setX(50.0);
        card.setY(toFront ? 25.0 : 75.0);
        owner.getField().add(card);
        record(room, GameEvent.SEND, effect.getControllerId(), card, fromZone, "field", effect, Map.of("ownerId", owner.getUserId(), "frontLine", toFront),
                owner.getUsername() + " coloca " + name(card) + " en su " + (toFront ? "línea frontal" : "retaguardia") + (exhausted ? ", agotada" : "")
                        + " (" + effect.getSourceName() + ")");
    }

    /** "gana fuerza N hasta el final del turno": the subject. */
    static void gainStrength(GameRoom room, PendingEffect effect, int amount) {
        CardSlot subject = subject(room, effect);
        if (subject == null || amount == 0) return;
        subject.setTurnStrength(subject.getTurnStrength() + amount);
        floating(subject, effect, "Gana fuerza " + amount + " hasta el final del turno", "turn", amount, null);
        record(room, GameEvent.GAIN, effect.getControllerId(), subject, null, null, effect, Map.of("strength", amount),
                name(subject) + " gana fuerza " + amount + " hasta el final del turno");
    }

    /** Allies of the Línea frontal that can declare an attack and match. */
    static List<CardSlot> attackers(PlayerState player, CardFilter filter) {
        return allies(player).stream().filter(c -> inFrontLine(c) && filter.matches(c) && !keywords(c).contains(NO_ATTACK)).toList();
    }

    /** One different card for each filter (a small matching: every filter is tried with every card). */
    static boolean assignable(List<CardSlot> cards, List<CardFilter> filters) {
        if (filters.isEmpty()) return true;
        CardFilter first = filters.get(0);
        for (CardSlot c : cards) {
            if (!first.matches(c)) continue;
            List<CardSlot> rest = new ArrayList<>(cards);
            rest.remove(c);
            if (assignable(rest, filters.subList(1, filters.size()))) return true;
        }
        return false;
    }

    /** Copies of the effect's card in its owner's Retaguardia (the card included). */
    static List<CardSlot> copiesInBack(PlayerState player, PendingEffect effect) {
        return player.getField().stream().filter(c -> !inFrontLine(c) && c.getCardName() != null
                && c.getCardName().equalsIgnoreCase(effect.getSourceName())).toList();
    }

    /** Cards of the zone that can be a Recurso of an ally of a higher level. */
    static List<CardSlot> higherAllyResources(PlayerState player, AddResourceToHigherAlly a) {
        return zones(player, a.zone()).stream().filter(a.filter()::matches).filter(c -> !higherThan(player, c).isEmpty()).toList();
    }

    /** Some of these enemies add up to exactly {@code sum} in level. */
    static boolean levelSumPossible(List<CardSlot> cards, int sum) {
        boolean[] reach = new boolean[sum + 1];
        reach[0] = true;
        for (CardSlot c : cards) {
            int level = c.getLevel() == null ? 0 : c.getLevel();
            if (level <= 0) continue;
            for (int s = sum; s >= level; s--) if (reach[s - level]) reach[s] = true;
        }
        return reach[sum];
    }

    /** "N criaturas en la línea frontal": yours, and the enemies this effect can affect. */
    static List<CardSlot> frontLineCreatures(GameRoom room, PendingEffect effect, CardFilter filter) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return List.of();
        List<CardSlot> all = new ArrayList<>(frontLine(player.getField()).stream().filter(c -> "creature".equals(c.getCardType()) && filter.matches(c)).toList());
        all.addAll(enemyTargets(room, effect, filter));
        return all;
    }

    /** "1 criatura en el campo con fuerza menor a esta carta". */
    static List<CardSlot> weakerThanSelf(GameRoom room, PendingEffect effect) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        CardSlot self = player == null ? null : find(player.getField(), effect.getSourceInstanceId());
        if (self == null) return List.of();
        return creaturesOnField(room, effect, CardFilter.creatures().withMaxStrength(strength(self) - 1));
    }

    /** The mark of a creature that goes back to its owner's hand (with its materials) when the attack ends ({@link ReturnSubjectAtAttackEnd}). */
    static final String RETURN_AT_ATTACK_END = "return-at-attack-end|";

    /** The creature the effect's card clashes with in the attack going on (the blocker, or the attacker when it blocks). */
    static CardSlot clashOpponent(GameRoom room, PendingEffect effect) {
        Combat c = room.getCombat();
        if (c == null) return null;
        String self = effect.getSourceInstanceId();
        String other = self.equals(c.getAttackerId()) ? c.getBlockerId() : self.equals(c.getBlockerId()) ? c.getAttackerId() : null;
        return other == null ? null : findInRoom(room, other);
    }

    /** The last card with that name in the zone (the one that just went there). */
    static CardSlot lastNamed(List<CardSlot> zone, String name) {
        for (int i = zone.size() - 1; i >= 0; i--) if (name.equalsIgnoreCase(zone.get(i).getCardName())) return zone.get(i);
        return null;
    }

    /** The chosen cards can each go to a different filter ("hasta 1 X y hasta 1 Y"). */
    static boolean assignableEach(List<CardFilter> filters, List<CardSlot> cards) {
        if (cards.isEmpty()) return true;
        CardSlot first = cards.get(0);
        for (int i = 0; i < filters.size(); i++) {
            if (!filters.get(i).matches(first)) continue;
            List<CardFilter> rest = new ArrayList<>(filters);
            rest.remove(i);
            if (assignableEach(rest, cards.subList(1, cards.size()))) return true;
        }
        return false;
    }

    /** Creatures of those zones that can be the declared summoner of {@code card}: its level, sharing a type or color, with its materials there. */
    static List<CardSlot> declaredFrom(PlayerState player, CardSlot card, Summons.SpecialRequirement req, String zones) {
        return zones(player, zones).stream()
                .filter(c -> c != card && "creature".equals(c.getCardType()) && Summons.optionFor(req, c) != null && shares(c, card))
                .filter(c -> {
                    List<CardSlot> materials = declaredMaterials(player, card, req, c);
                    return req.levelSum() > 0 ? sumsExactly(materials, req.levelSum()) : materials.size() >= Math.max(1, req.materialCount());
                })
                .toList();
    }

    /** The materials of {@code card} besides that summoner, in the zones of its Requerimiento ("materiales que sumen 3 en tus tributos"). */
    static List<CardSlot> declaredMaterials(PlayerState player, CardSlot card, Summons.SpecialRequirement req, CardSlot summoner) {
        List<CardSlot> all = new ArrayList<>();
        for (String z : req.materialZones()) all.addAll(zone(player, z));
        return all.stream().filter(c -> c != card && c != summoner && "creature".equals(c.getCardType()) && c.getLevel() != null && shares(c, card)
                && (req.levelSum() > 0 || hasLevel(c, req.materialLevel()))).toList();
    }

    /** "tus tributos o descarte". */
    static String zonesText(String csv) {
        return String.join(" o ", Arrays.stream(csv.split(",")).map(z -> zoneName(z.trim())).toList());
    }
}
