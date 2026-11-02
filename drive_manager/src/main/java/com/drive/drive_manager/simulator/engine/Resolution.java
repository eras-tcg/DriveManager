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
import static com.drive.drive_manager.simulator.engine.StepHelpers.*;
import static com.drive.drive_manager.simulator.engine.Summons.*;
import static com.drive.drive_manager.simulator.engine.Removals.*;
import static com.drive.drive_manager.simulator.engine.TurnRules.*;
import static com.drive.drive_manager.simulator.engine.CombatRules.*;
import static com.drive.drive_manager.simulator.engine.Chain.*;
import static com.drive.drive_manager.simulator.engine.Answers.*;

/**
 * Resolution (rule 11.6): runs the steps of an effect. A step is a basic action ({@link Do}, run by its handler) or a step of a family
 * ({@link FlowSteps}, {@link LookSteps}, {@link CombatSteps}, {@link AllySteps}, {@link SummonSteps}, {@link TableSteps}), with what it
 * needs in one place. A new step is a record in {@link EffectStep}, its cases in a family and its line in {@link #FAMILY}.
 */
public final class Resolution {

    private Resolution() {}

    private enum Family { FLOW, LOOK, COMBAT, ALLY, SUMMON, TABLE }

    private static final Map<Class<?>, Family> FAMILY = new HashMap<>();

    private static void family(Family family, Class<?>... steps) {
        for (Class<?> step : steps) FAMILY.put(step, family);
    }

    static boolean hasFamily(Class<?> step) {
        return FAMILY.containsKey(step);
    }

    static {
        family(Family.FLOW, AtEndOfTurn.class, EndYourTurn.class, IfChosenGone.class, IfChosenWasActiveAlly.class, IfCondition.class, IfDone.class, IfNotDone.class, IfPossible.class, IfTributed.class, KeepOnField.class, MayDo.class, SubjectFromTrigger.class, SubjectIsThisCard.class);
        family(Family.LOOK, LookedToTopOrBottom.class, RevealFromHand.class, RevealOpponentHandSendOneToBottom.class, TakeFromLookedDistinctLevels.class, TakeFromLookedEachRestToBottom.class, TakeFromLookedLikeSubject.class);
        family(Family.COMBAT, AlliesGain.class, BounceChosen.class, ChooseEnemiesLevelSum.class, ChooseWeakerThanSelf.class, ChooseWeakestEnemy.class, DeclareAttack.class, DeclareBlockSelf.class, ExtraCombatHitThenEndTurn.class, ExtraHit.class, GroupUp.class, NoUntapChosen.class, PickAllyUpToSacrificedLevel.class, ReduceRepeatedly.class, ReturnSubjectAtAttackEnd.class, SkipBlockStep.class, TributesToDeckBottomForKeyword.class);
        family(Family.ALLY, ActivateSubjectSummonEffect.class, ActivateTributedSummonEffect.class, AddResourceToHigherAlly.class, AttachLowerFromTributes.class, AttachSelfAsResourceToAlly.class, AttachSubjectToHigherAlly.class, AttachSubjectToSummoned.class, MaterialOfExhaustedAllyToBack.class, PickAllyWithHigherAlly.class, PickAllyWithMaterials.class, PlaceFromHandUntilTurnEnd.class, ReturnAllyOfSummonedLevel.class, SacrificeAlly.class, TributeAlliesLevelSum.class, TributeAllyMaterial.class, TributeAllyMaterialWhere.class, TributeAllyResources.class, TributeNamedFromDiscard.class);
        family(Family.SUMMON, ChooseOpponentTribute.class, MaterializeSelfOnTriggerAlly.class, PlaceChosenTributeAndSelf.class, SpecialSelfDeclaredSummoner.class, SummonFreeLevelSum.class, SummonLikeSubject.class);
        family(Family.TABLE, DestroyFaceUpLife.class, DestroyTopLife.class, EachPlayerDiscards.class, EachPlayerKeepsOneInFrontLine.class, EachPlayerLowersMarker.class, GainGemsPerOwnSacrificed.class, HandsToBottomDrawOpponentLives.class, LivesFaceDown.class, OpponentGainsGemsAsCounter.class, OpponentMovesOneToFront.class, ReduceOpponentLivesBy.class);
    }

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
        if (room.getPlayer(effect.getControllerId()) == null) return false;
        if (step instanceof Do d) return Actions.possible(new EngineContext(room, effect), d.action());
        return switch (FAMILY.get(step.getClass())) {
            case FLOW -> FlowSteps.possibleStep(room, effect, step);
            case LOOK -> LookSteps.possibleStep(room, effect, step);
            case COMBAT -> CombatSteps.possibleStep(room, effect, step);
            case ALLY -> AllySteps.possibleStep(room, effect, step);
            case SUMMON -> SummonSteps.possibleStep(room, effect, step);
            case TABLE -> TableSteps.possibleStep(room, effect, step);
        };
    }

    /** Runs one step. Returns true when it is now waiting for the player's choice. */
    static boolean run(GameRoom room, PendingEffect effect, EffectStep step) {
        return effect.getRitualTargetId() != null ? summoningAs(looker(room, effect), () -> runAs(room, effect, step))
                : lookingAs(looker(room, effect), () -> runAs(room, effect, step));
    }

    static boolean runAs(GameRoom room, PendingEffect effect, EffectStep step) {
        if (room.getPlayer(effect.getControllerId()) == null) return false;
        if (step instanceof Do d) return Actions.run(new EngineContext(room, effect), d.action());
        return switch (FAMILY.get(step.getClass())) {
            case FLOW -> FlowSteps.runStep(room, effect, step);
            case LOOK -> LookSteps.runStep(room, effect, step);
            case COMBAT -> CombatSteps.runStep(room, effect, step);
            case ALLY -> AllySteps.runStep(room, effect, step);
            case SUMMON -> SummonSteps.runStep(room, effect, step);
            case TABLE -> TableSteps.runStep(room, effect, step);
        };
    }

    /** Carries out the step with the player's choice. Sets {@code repeat[0]} when the step asks again. */
    static String apply(GameRoom room, PendingEffect effect, EffectStep step, PlayerState player, List<String> picks,
                                List<String> order, boolean[] repeat) {
        return effect.getRitualTargetId() != null ? summoningAs(looker(room, effect), () -> applyAs(room, effect, step, player, picks, order, repeat))
                : lookingAs(looker(room, effect), () -> applyAs(room, effect, step, player, picks, order, repeat));
    }

    static String applyAs(GameRoom room, PendingEffect effect, EffectStep step, PlayerState player, List<String> picks,
                                List<String> order, boolean[] repeat) {
        if (step instanceof Do d) return Actions.answer(new EngineContext(room, effect, repeat), d.action(), new ActionHandler.Answer(picks, order));
        return switch (FAMILY.get(step.getClass())) {
            case FLOW -> FlowSteps.applyStep(room, effect, step, player, picks, order, repeat);
            case LOOK -> LookSteps.applyStep(room, effect, step, player, picks, order, repeat);
            case COMBAT -> CombatSteps.applyStep(room, effect, step, player, picks, order, repeat);
            case ALLY -> AllySteps.applyStep(room, effect, step, player, picks, order, repeat);
            case SUMMON -> SummonSteps.applyStep(room, effect, step, player, picks, order, repeat);
            case TABLE -> TableSteps.applyStep(room, effect, step, player, picks, order, repeat);
        };
    }
}
