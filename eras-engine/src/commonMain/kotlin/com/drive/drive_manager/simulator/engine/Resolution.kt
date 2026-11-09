package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmStatic
import kotlin.reflect.KClass
import com.drive.drive_manager.simulator.engine.EffectEngine.find
import com.drive.drive_manager.simulator.engine.EffectEngine.findAnywhere
import com.drive.drive_manager.simulator.engine.EffectEngine.lookingAs
import com.drive.drive_manager.simulator.engine.EffectEngine.name
import com.drive.drive_manager.simulator.engine.EffectEngine.record
import com.drive.drive_manager.simulator.engine.EffectEngine.script
import com.drive.drive_manager.simulator.engine.EffectEngine.strength
import com.drive.drive_manager.simulator.engine.EffectEngine.summoningAs
import com.drive.drive_manager.simulator.engine.EffectEngine.triggerEvent
import com.drive.drive_manager.simulator.engine.EffectStep.*
import com.drive.drive_manager.simulator.engine.Removals.leaveField
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler
import com.drive.drive_manager.simulator.engine.effects.runtime.Actions
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/**
 * Resolution (rule 11.6): runs the steps of an effect. A step is a basic action ([Do], run by its handler) or a step of a family
 * ([FlowSteps], [LookSteps], [CombatSteps], [AllySteps], [SummonSteps], [TableSteps]), with what it
 * needs in one place. A new step is a record in [EffectStep], its cases in a family and its line in [FAMILY].
 */
object Resolution {

    private enum class Family { FLOW, LOOK, COMBAT, ALLY, SUMMON, TABLE }

    private val FAMILY: MutableMap<KClass<*>, Family> = HashMap()

    private fun family(family: Family, vararg steps: KClass<*>) {
        for (step in steps) FAMILY[step] = family
    }

    @JvmStatic
    fun hasFamily(step: KClass<*>?): Boolean {
        return FAMILY.containsKey(step)
    }

    init {
        family(Family.FLOW, EndYourTurn::class, IfChosenGone::class, IfSaved::class, IfCondition::class, IfDone::class, IfNotDone::class, IfPossible::class, IfTributed::class, KeepOnField::class, MayDo::class, SubjectFromTrigger::class, SubjectIsThisCard::class)
        family(Family.LOOK, RevealFromHand::class, RevealOpponentHandSendOneToBottom::class, TakeFromLookedDistinctLevels::class, TakeFromLookedEachRestToBottom::class, TakeFromLookedLikeSubject::class)
        family(Family.COMBAT, AlliesGain::class, BounceChosen::class, ChooseEnemiesLevelSum::class, ChooseWeakestEnemy::class, DeclareBlockSelf::class, ExtraCombatHitThenEndTurn::class, ExtraHit::class, GroupUp::class, NoUntapChosen::class, PickAllyUpToSacrificedLevel::class, ReduceRepeatedly::class, ReturnSubjectAtAttackEnd::class, SkipBlockStep::class, TributesToDeckBottomForKeyword::class)
        family(Family.ALLY, ActivateSubjectSummonEffect::class, ActivateTributedSummonEffect::class, AddResourceToHigherAlly::class, AttachLowerFromTributes::class, AttachSelfAsResourceToAlly::class, AttachSubjectToHigherAlly::class, AttachSubjectToSummoned::class, MaterialOfExhaustedAllyToBack::class, PickAllyWithHigherAlly::class, PickAllyWithMaterials::class, PlaceFromHandUntilTurnEnd::class, ReturnAllyOfSummonedLevel::class, SacrificeAlly::class, TributeAlliesLevelSum::class, TributeAllyMaterial::class, TributeAllyMaterialWhere::class, TributeAllyResources::class)
        family(Family.SUMMON, ChooseOpponentTribute::class, MaterializeSelfOnTriggerAlly::class, PlaceChosenTributeAndSelf::class, SpecialSelfDeclaredSummoner::class, SummonFreeLevelSum::class, SummonLikeSubject::class)
        family(Family.TABLE, DestroyFaceUpLife::class, DestroyTopLife::class, EachPlayerDiscards::class, EachPlayerKeepsOneInFrontLine::class, EachPlayerLowersMarker::class, GainGemsPerOwnSacrificed::class, HandsToBottomDrawOpponentLives::class, LivesFaceDown::class, OpponentGainsGemsAsCounter::class, OpponentMovesOneToFront::class, ReduceOpponentLivesBy::class)
    }

    // ------------------------------------------------------------------ Resolution

    /** Runs the next step of the last effect in the chain, or finishes it. */
    @JvmStatic
    fun stepTop(room: GameRoom) {
        val effect = room.chain[room.chain.size - 1]
        if (effect.resolutionId == null) effect.resolutionId = room.nextId("res")
        val script = script(effect)
        val steps = script.steps!!
        if (effect.stepIndex >= steps.size) {
            record(room, GameEvent.EFFECT_RESOLVED, effect.controllerId, null, null, null, effect, null,
                "Se resuelve " + (if (effect.link > 0) "la Cadena " + effect.link + ": " else "") + effect.label + " de " + effect.sourceName)
            room.chain.removeAt(room.chain.size - 1)
            if (script.moment == EffectScript.Moment.ON_PLAY) utilityToDiscard(room, effect)
            // The room as this link left it, for whoever shows each link's effects as it resolves
            LinkWatch.resolved(room)
            return
        }
        val seen = room.eventSeq
        val waiting = run(room, effect, steps[effect.stepIndex])
        if (waiting) return
        effect.stepIndex = effect.stepIndex + 1
        // What this step did, shown before the next one ("…; si lo haces, …" in its order)
        if (room.eventSeq != seen) LinkWatch.stepped(room)
    }

    /** Rule 6.2.3.1: a utility goes to the Descarte once its effect has resolved. */
    @JvmStatic
    fun utilityToDiscard(room: GameRoom, effect: PendingEffect) {
        val player = room.getPlayer(effect.controllerId)
        val card = if (player == null) null else find(player.field, effect.sourceInstanceId)
        if (card == null || "utility" != card.cardType || effect.keepOnField) return
        player!!.field.remove(card)
        leaveField(card)
        player.discardPile.add(card)
        record(room, GameEvent.SEND, player.userId, card, "field", "discardPile", null, null,
            name(card) + " va al descarte tras resolverse")
    }

    /**
     * "puedes hacer A para hacer B": A, the step right after IfPossible (from `from`), can be done now. A is paid for B and
     * can be paid even when B would do nothing (designer ruling 2026-10-30, Sombra Errante).
     */
    @JvmStatic
    fun allPossibleAfterIfPossible(room: GameRoom, effect: PendingEffect, steps: List<EffectStep>?, from: Int): Boolean {
        for (i in from until steps!!.size) {
            if (steps[i] !is IfPossible) continue
            return i + 1 >= steps.size || possible(room, effect, steps[i + 1])
        }
        return true
    }

    /** The enemy of the strength reduction that triggered the effect, still on the field with some strength. */
    @JvmStatic
    fun triggerEnemy(room: GameRoom, effect: PendingEffect): CardSlot? {
        val trigger = triggerEvent(room, effect)
        val opponent = room.getPlayer(room.getOpponentId(effect.controllerId))
        val target = if (trigger == null || opponent == null) null else find(opponent.field, trigger.cardInstanceId)
        return if (target != null && strength(target) > 0) target else null
    }

    /** Whether a step can do anything now (used to offer optional effects and "puedes" choices). */
    @JvmStatic
    fun possible(room: GameRoom, effect: PendingEffect, step: EffectStep): Boolean {
        return if (effect.ritualTargetId != null) summoningAs(looker(room, effect)) { possibleAs(room, effect, step) }
        else lookingAs(looker(room, effect)) { possibleAs(room, effect, step) }
    }

    /** The card whose rules read the table while this effect works: the creature it is special summoning, or its own card. */
    @JvmStatic
    fun looker(room: GameRoom, effect: PendingEffect): CardSlot? {
        val target = if (effect.ritualTargetId == null) null else findAnywhere(room, effect.controllerId, effect.ritualTargetId)
        return target ?: findAnywhere(room, effect.controllerId, effect.sourceInstanceId)
    }

    @JvmStatic
    fun possibleAs(room: GameRoom, effect: PendingEffect, step: EffectStep): Boolean {
        if (room.getPlayer(effect.controllerId) == null) return false
        if (step is Do) return Actions.possible(EngineContext(room, effect), step.action!!)
        return when (FAMILY[step::class]!!) {
            Family.FLOW -> FlowSteps.possibleStep(room, effect, step)
            Family.LOOK -> LookSteps.possibleStep(room, effect, step)
            Family.COMBAT -> CombatSteps.possibleStep(room, effect, step)
            Family.ALLY -> AllySteps.possibleStep(room, effect, step)
            Family.SUMMON -> SummonSteps.possibleStep(room, effect, step)
            Family.TABLE -> TableSteps.possibleStep(room, effect, step)
        }
    }

    /** Runs one step. Returns true when it is now waiting for the player's choice. */
    @JvmStatic
    fun run(room: GameRoom, effect: PendingEffect, step: EffectStep): Boolean {
        return if (effect.ritualTargetId != null) summoningAs(looker(room, effect)) { runAs(room, effect, step) }
        else lookingAs(looker(room, effect)) { runAs(room, effect, step) }
    }

    @JvmStatic
    fun runAs(room: GameRoom, effect: PendingEffect, step: EffectStep): Boolean {
        if (room.getPlayer(effect.controllerId) == null) return false
        if (step is Do) return Actions.run(EngineContext(room, effect), step.action!!)
        return when (FAMILY[step::class]!!) {
            Family.FLOW -> FlowSteps.runStep(room, effect, step)
            Family.LOOK -> LookSteps.runStep(room, effect, step)
            Family.COMBAT -> CombatSteps.runStep(room, effect, step)
            Family.ALLY -> AllySteps.runStep(room, effect, step)
            Family.SUMMON -> SummonSteps.runStep(room, effect, step)
            Family.TABLE -> TableSteps.runStep(room, effect, step)
        }
    }

    /** Carries out the step with the player's choice. Sets `repeat[0]` when the step asks again. */
    @JvmStatic
    fun apply(
        room: GameRoom, effect: PendingEffect, step: EffectStep, player: PlayerState, picks: List<String>,
        order: List<String>?, repeat: BooleanArray,
    ): String? {
        return if (effect.ritualTargetId != null) summoningAs(looker(room, effect)) { applyAs(room, effect, step, player, picks, order, repeat) }
        else lookingAs(looker(room, effect)) { applyAs(room, effect, step, player, picks, order, repeat) }
    }

    @JvmStatic
    fun applyAs(
        room: GameRoom, effect: PendingEffect, step: EffectStep, player: PlayerState, picks: List<String>,
        order: List<String>?, repeat: BooleanArray,
    ): String? {
        if (step is Do) return Actions.answer(EngineContext(room, effect, repeat), step.action!!, ActionHandler.Answer(picks, order))
        return when (FAMILY[step::class]!!) {
            Family.FLOW -> FlowSteps.applyStep(room, effect, step, player, picks, order, repeat)
            Family.LOOK -> LookSteps.applyStep(room, effect, step, player, picks, order, repeat)
            Family.COMBAT -> CombatSteps.applyStep(room, effect, step, player, picks, order, repeat)
            Family.ALLY -> AllySteps.applyStep(room, effect, step, player, picks, order, repeat)
            Family.SUMMON -> SummonSteps.applyStep(room, effect, step, player, picks, order, repeat)
            Family.TABLE -> TableSteps.applyStep(room, effect, step, player, picks, order, repeat)
        }
    }
}
