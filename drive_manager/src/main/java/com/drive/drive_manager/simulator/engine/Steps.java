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
            case Do d -> Actions.possible(new EngineContext(room, effect), d.action());
            case AttachLowerFromTributes attach -> {
                GameEvent trigger = triggerEvent(room, effect);
                CardSlot ally = trigger == null ? null : find(player.getField(), trigger.getCardInstanceId());
                yield ally != null && ally.getLevel() != null && player.getTributeZone().stream()
                        .anyMatch(c -> attach.filter().matches(c) && c.getLevel() != null && c.getLevel() < ally.getLevel());
            }
            case DestroyFaceUpLife ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(effect.getControllerId()));
                yield opponent != null && opponent.getLifeStack().stream().anyMatch(l -> !l.isFaceDown());
            }
            case TributesToDeckBottomForKeyword d -> player.getTributeZone().stream().filter(d.filter()::matches).count() >= d.count();
            case TributeAlliesLevelSum t -> {
                List<CardSlot> valid = allies(player).stream().filter(t.filter()::matches).toList();
                yield valid.size() >= t.minCount() && valid.stream().mapToInt(c -> c.getLevel() == null ? 0 : c.getLevel()).sum() >= t.minSum();
            }
            case PlaceFromHandUntilTurnEnd p -> player.getHand().stream().anyMatch(p.filter()::matches);
            case TributeAllyMaterialWhere t -> !allyMaterialsWhere(player, t).isEmpty();
            case SummonFreeLevelSum sum -> !freeLevelSum(room, effect, sum).isEmpty();
            case SacrificeAlly sacrifice -> !sacrificable(effect, player, sacrifice).isEmpty();
            case PickAlly pick -> !pickable(room, effect, player, pick).isEmpty();
            case ChooseWeakestEnemy weakest -> !enemyTargets(room, effect, weakest.filter()).isEmpty();
            case TributeAllyMaterial ignored -> !allyMaterials(player).isEmpty();
            case PickAllyWhere pick -> !pickableWhere(room, effect, player, pick).isEmpty();
            case PickAllyUpToSacrificedLevel pick -> allies(player).stream().anyMatch(EffectEngine::inFrontLine);
            case PickAllyWithHigherAlly ignored -> !withHigherAlly(player).isEmpty();
            case PickAllyWithMaterials pick -> allies(player).stream().anyMatch(c -> c.getMaterials().size() >= pick.count());
            case ExtraHit ignored -> room.getCombat() != null && room.getCombat().getStep() == Combat.Step.DAMAGE;
            case SpecialSelfDeclaredSummoner s -> {
                CardSlot self = find(player.getHand(), effect.getSourceInstanceId());
                Summons.SpecialRequirement req = self == null ? null : specialRequirement(self);
                yield req != null && self.getSpecialCost() != null && !declaredFrom(player, self, req, s.zones()).isEmpty()
                        && canPay(room, player.getUserId(), self.getSpecialCost() - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind(), self, null));
            }
            case TributeNamedFromDiscard t -> lastNamed(player.getDiscardPile(), t.name()) != null;
            case RevealFromHand r -> assignable(player.getHand().stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId())).toList(),
                    r.filters());
            case PickAttacker p -> !attackers(player, p.filter()).isEmpty();
            case TributeAllyResources t -> allies(player).stream().anyMatch(a -> a.getResources().stream().anyMatch(t.filter()::matches));
            case AddResourceToHigherAlly a -> !higherAllyResources(player, a).isEmpty();
            case ChooseEnemiesLevelSum s -> levelSumPossible(enemyTargets(room, effect, CardFilter.creatures()), s.sum());
            case ChooseWeakerThanSelf w -> w.upTo() || !weakerThanSelf(room, effect).isEmpty();
            case AttachSelfAsResourceToAlly ignored -> {
                Located at = locate(player, effect.getSourceInstanceId());
                yield at != null && allies(player).stream().anyMatch(c -> c != at.card());
            }
            case ChooseOpponentTribute c -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(player.getUserId()));
                yield opponent != null && opponent.getTributeZone().stream().anyMatch(c.filter()::matches);
            }
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

            case Do d -> {
                return Actions.run(new EngineContext(room, effect), d.action());
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
            case ReturnSubjectAtAttackEnd ignored -> {
                CardSlot ally = subject(room, effect);
                if (ally == null || room.getCombat() == null) return false;
                room.getUsedThisTurn().add(RETURN_AT_ATTACK_END + userId + "|" + ally.getInstanceId());
                return false;
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
            case DeclareBlockSelf ignored -> {
                CardSlot self = subject(room, effect);
                Combat c = room.getCombat();
                if (self == null || c == null || c.getBlockerId() != null || !player.getUserId().equals(c.getDefenderId())) return false;
                c.setBlockerId(self.getInstanceId());
                record(room, GameEvent.BLOCK, userId, self, null, null, effect, null, name(self) + " declara bloqueo (" + effect.getSourceName() + ")");
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
            case RevealFromHand r -> {
                List<CardSlot> valid = player.getHand().stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId())
                        && r.filters().stream().anyMatch(f -> f.matches(c))).toList();
                if (!assignable(valid, r.filters())) return false;
                pick(room, effect, "Revela de tu mano: " + String.join(" y ", r.filters().stream().map(f -> "1 " + f.describe()).toList()) + ".",
                        "Revelar", player.getHand(), ids(valid), r.filters().size());
                return true;
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
            case AtEndOfTurn later -> {
                String subject = effect.getSummonedId() != null ? effect.getSummonedId() : effect.getSubjectId() != null ? effect.getSubjectId() : "";
                room.getUsedThisTurn().add(DELAYED + userId + "|" + effect.getSourceInstanceId() + "|" + later.scriptId() + "|" + subject);
                log(room, effect.getSourceName() + ": al final del turno se resolverá el resto de su efecto", null);
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
            case ChooseEnemiesLevelSum s -> {
                List<CardSlot> valid = enemyTargets(room, effect, CardFilter.creatures());
                if (!levelSumPossible(valid, s.sum())) return false;
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                pick(room, effect, "Elige enemigos que sumen " + s.sum() + " en nivel.", "Objetivo", frontLine(opponent.getField()), ids(valid), valid.size());
                room.getPrompt().setMin(1);
                return true;
            }
            case GainGemsPerOwnSacrificed ignored -> {
                int n = (int) effect.getTargetIds().stream().filter(id -> find(player.getTributeZone(), id) != null).count();
                if (n > 0) run(room, effect, Compose.gainGems(n));
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
            case EndYourTurn ignored -> {
                room.getUsedThisTurn().add(END_TURN_REQUESTED + userId);
                log(room, player.getUsername() + " termina su turno (" + effect.getSourceName() + ")", null);
                return false;
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
            case IfTributed ift -> {
                boolean match = effect.getLastTributed().stream()
                        .map(id -> findAnywhere(room, userId, id)).anyMatch(c -> c != null && ift.filter().matches(c));
                if (!match) effect.setStepIndex(effect.getStepIndex() + ift.covers());
                return false;
            }
            case IfPossible ignored -> {
                if (!allPossibleAfterIfPossible(room, effect, script(effect).steps(), effect.getStepIndex())) {
                    log(room, effect.getSourceName() + ": " + effect.getLabel() + " no se resuelve (ya no puede hacerse todo lo que pide)", null);
                    effect.setStepIndex(script(effect).steps().size() - 1);
                }
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
            case DestroyFaceUpLife ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                List<CardSlot> valid = opponent == null ? List.of() : opponent.getLifeStack().stream().filter(l -> !l.isFaceDown()).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Puedes destruir 1 carta bocarriba de las vidas de tu rival, o ninguna.", "Destruir",
                        opponent.getLifeStack(), ids(valid), 1);
                room.getPrompt().setMin(0);
                return true;
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
            case SacrificeAlly sacrifice -> {
                List<CardSlot> valid = sacrificable(effect, player, sacrifice);
                if (valid.isEmpty()) return false;
                pick(room, effect, sacrifice.toUntapAnother()
                        ? "Sacrifica 1 aliado. No puede ser el único aliado agotado que queda por reactivar."
                        : "Sacrifica 1 aliado.", "Sacrificar", player.getField(), ids(valid), 1);
                return true;
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
    /** "elige 1 aliado": the literal word makes it a target (designer ruling 2026-11-01), so "si un aliado es objetivo" reacts. */
    static void recordAllyTarget(GameRoom room, PendingEffect effect, PlayerState player, String id) {
        CardSlot ally = find(player.getField(), id);
        record(room, GameEvent.TARGET, player.getUserId(), ally, null, null, effect, Map.of("targetIds", List.of(id)),
                player.getUsername() + " elige como objetivo a " + name(ally) + " (" + effect.getSourceName() + ")");
    }

    /** The rule of a special summon whose summoner is the ally chosen before; null for any other step. */
    static SpecialRule subjectSummon(EffectStep step) {
        if (!(step instanceof Do d) || !(d.action() instanceof Action.Sequence seq)) return null;
        for (Action a : seq.actions()) {
            if (a instanceof Action.SummonSpecial summon && "subject".equals(summon.rule().summoner())) return summon.rule();
        }
        return null;
    }

    static List<CardSlot> pickable(GameRoom room, PendingEffect effect, PlayerState player, PickAlly pick) {
        List<EffectStep> steps = new ArrayList<>(script(effect).cost());
        steps.addAll(script(effect).steps());
        List<CardSlot> valid = allies(player).stream().filter(c -> !pick.attacker() || inFrontLine(c))
                // "añade esta carta como material a un aliado": another one
                .filter(c -> steps.stream().noneMatch(s -> s instanceof Do d && d.action() instanceof Action.Move m && "materials:subject".equals(m.to())) || !c.getInstanceId().equals(effect.getSourceInstanceId()))
                // "elige 1 aliado: invoca 1 criatura del mismo nivel": one with something to summon
                .filter(c -> steps.stream().noneMatch(s -> s instanceof SummonLikeSubject like && likeSubject(player, like, c).isEmpty()))
                .toList();
        // "a un aliado para que invoque por …": only an ally that meets the Requerimiento of something it could summon
        List<EffectStep> resolution = script(effect).steps();
        // Its rule, as it reads now, in the sequence that summons
        EffectStep summonStep = resolution.stream().filter(s -> subjectSummon(s) != null).findFirst().orElse(null);
        SpecialRule special = summonStep == null ? null : SpecialProcedure.resolve(room, effect, subjectSummon(summonStep));
        // Not when that summon is conditional ("Si lo haces: dicho aliado puede invocar …")
        if (special == null || conditional(resolution, resolution.indexOf(summonStep))) return valid;
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

    /** Cards "añade 1 … como recurso de esta carta" may take: from those zones, matching, not this card. */
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

            case Do d -> {
                return Actions.answer(new EngineContext(room, effect, repeat), d.action(), new ActionHandler.Answer(picks, order));
            }
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
            case MaterialOfExhaustedAllyToBack m -> {
                if (picks.isEmpty()) break;
                CardSlot card = find(exhaustedAllyMaterials(player, m.filter()), picks.get(0));
                if (card == null) return "Ese material ya no está";
                for (CardSlot ally : player.getField()) ally.getMaterials().remove(card);
                player.getHand().add(card);
                placeOnField(room, effect, player, card, "hand", false, false);
                effect.setLastDone(true);
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
            case RevealFromHand r -> {
                List<CardSlot> cards = picks.stream().map(id -> find(player.getHand(), id)).toList();
                if (cards.contains(null)) return "Esa carta ya no está en tu mano";
                if (!assignable(cards, r.filters())) return "Revela " + String.join(" y ", r.filters().stream().map(f -> "1 " + f.describe()).toList());
                effect.setLastDone(true);
                effect.setLastTributed(new ArrayList<>(picks));
                log(room, player.getUsername() + " revela " + String.join(", ", cards.stream().map(EffectEngine::name).toList()) + " (" + effect.getSourceName() + ")", null);
            }
            case PickAttacker p -> {
                if (find(player.getField(), picks.get(0)) == null) return "Ese aliado ya no está en el campo";
                effect.setSubjectId(picks.get(0));
                if (p.target()) recordAllyTarget(room, effect, player, picks.get(0));
            }
            case LivesFaceDown ignored -> {
                CardSlot life = find(player.getLifeStack(), picks.get(0));
                if (life == null) return "Esa vida ya no está";
                life.setFaceDown(true);
                effect.setLastDone(true);
                log(room, player.getUsername() + " coloca bocabajo la vida " + name(life) + " (" + effect.getSourceName() + ")", null);
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
            case ChooseEnemiesLevelSum s -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                List<CardSlot> chosen = picks.stream().map(id -> opponent == null ? null : find(frontLine(opponent.getField()), id)).toList();
                if (chosen.contains(null)) return "Ese enemigo ya no está en la línea frontal";
                if (chosen.stream().mapToInt(c -> c.getLevel() == null ? 0 : c.getLevel()).sum() != s.sum()) return "Sus niveles deben sumar " + s.sum();
                effect.getTargetIds().addAll(picks);
                record(room, GameEvent.TARGET, userId, chosen.get(0), null, null, effect, Map.of("targetIds", picks),
                        player.getUsername() + " elige como objetivo a " + String.join(", ", chosen.stream().map(EffectEngine::name).toList()) + " (" + effect.getSourceName() + ")");
            }
            case ReturnAllyOfSummonedLevel ignored -> {
                CardSlot ally = find(player.getField(), picks.get(0));
                if (ally == null) return "Ese aliado ya no está en el campo";
                removeFromField(player, ally);
                player.getHand().add(ally);
                effect.setLastTributed(new ArrayList<>(List.of(ally.getInstanceId())));
                record(room, GameEvent.SEND, userId, ally, "field", "hand", effect, null, player.getUsername() + " sube " + name(ally) + " a su mano (" + effect.getSourceName() + ")");
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
            case PickAlly p -> {
                if (find(player.getField(), picks.get(0)) == null) return "Ese aliado ya no está en el campo";
                effect.setSubjectId(picks.get(0));
                if (p.target()) recordAllyTarget(room, effect, player, picks.get(0));
            }
            case SacrificeAlly ignored -> {
                CardSlot ally = find(player.getField(), picks.get(0));
                if (ally == null) return "Ese aliado ya no está en el campo";
                effect.setLastTributed(new ArrayList<>(List.of(ally.getInstanceId())));
                destroy(room, ally, player, true, CAUSE_EFFECT, userId, null, effect);
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
            case PickAllyWhere p -> {
                if (find(player.getField(), picks.get(0)) == null) return "Ese aliado ya no está en el campo";
                effect.setSubjectId(picks.get(0));
                if (p.target()) recordAllyTarget(room, effect, player, picks.get(0));
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
