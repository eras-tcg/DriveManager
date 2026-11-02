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
import static com.drive.drive_manager.simulator.engine.Removals.*;
import static com.drive.drive_manager.simulator.engine.TurnRules.*;
import static com.drive.drive_manager.simulator.engine.CombatRules.*;
import static com.drive.drive_manager.simulator.engine.Chain.*;
import static com.drive.drive_manager.simulator.engine.Answers.*;

/** "Si …", "puedes …" and who the next steps act on: what steers the rest of the effect. Each step: whether it can be done, carrying it out, and applying the player's choice. */
final class FlowSteps {

    private FlowSteps() {}

    static boolean possibleStep(GameRoom room, PendingEffect effect, EffectStep step) {
        return true;
    }

    static boolean runStep(GameRoom room, PendingEffect effect, EffectStep step) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        String userId = player.getUserId();

        switch (step) {
            case IfSaved ifSaved -> {
                if (new EngineContext(room, effect).saved(ifSaved.name()).isEmpty()) effect.setStepIndex(effect.getStepIndex() + ifSaved.covers());
                return false;
            }
            case IfCondition ifc -> {
                CardSlot source = findAnywhere(room, userId, effect.getSourceInstanceId());
                boolean holds = source != null && ifc.condition().test(room, null, source, userId);
                if (!holds) effect.setStepIndex(effect.getStepIndex() + ifc.covers());
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
            case SubjectIsThisCard ignored -> {
                effect.setSubjectId(null);
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
            default -> throw new IllegalStateException("Paso de otra familia: " + step);
        }
    }

    static String applyStep(GameRoom room, PendingEffect effect, EffectStep step, PlayerState player, List<String> picks,
                            List<String> order, boolean[] repeat) {
        String userId = player.getUserId();
        switch (step) {
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
}
