package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.engine.EffectEngine.END_TURN_REQUESTED
import com.drive.drive_manager.simulator.engine.EffectEngine.find
import com.drive.drive_manager.simulator.engine.EffectEngine.findAnywhere
import com.drive.drive_manager.simulator.engine.EffectEngine.findInRoom
import com.drive.drive_manager.simulator.engine.EffectEngine.log
import com.drive.drive_manager.simulator.engine.EffectEngine.name
import com.drive.drive_manager.simulator.engine.EffectEngine.script
import com.drive.drive_manager.simulator.engine.EffectEngine.triggerEvent
import com.drive.drive_manager.simulator.engine.EffectStep.*
import com.drive.drive_manager.simulator.engine.Resolution.allPossibleAfterIfPossible
import com.drive.drive_manager.simulator.engine.Resolution.possible
import com.drive.drive_manager.simulator.engine.StepHelpers.prompt
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/** "Si …", "puedes …" and who the next steps act on: what steers the rest of the effect. Each step: whether it can be done, carrying it out, and applying the player's choice. */
object FlowSteps {

    @JvmStatic
    fun possibleStep(room: GameRoom, effect: PendingEffect, step: EffectStep): Boolean {
        return true
    }

    @JvmStatic
    fun runStep(room: GameRoom, effect: PendingEffect, step: EffectStep): Boolean {
        val player = room.getPlayer(effect.controllerId)!!
        val userId = player.userId

        when (step) {
            is IfSaved -> {
                if (EngineContext(room, effect).saved(step.name).isEmpty()) effect.stepIndex = effect.stepIndex + step.covers
                return false
            }
            is IfCondition -> {
                val source = findAnywhere(room, userId, effect.sourceInstanceId)
                val holds = source != null && step.condition!!.test(room, null, source, userId)
                if (!holds) effect.stepIndex = effect.stepIndex + step.covers
                return false
            }
            is IfChosenGone -> {
                val gone = effect.targetIds.none { id -> findInRoom(room, id) != null
                        && room.players.values.any { p -> find(p.field, id) != null } }
                if (!gone) effect.stepIndex = effect.stepIndex + step.covers
                return false
            }
            is EndYourTurn -> {
                room.usedThisTurn.add(END_TURN_REQUESTED + userId)
                log(room, player.username + " termina su turno (" + effect.sourceName + ")", null)
                return false
            }
            is SubjectIsThisCard -> {
                effect.subjectId = null
                return false
            }
            is IfTributed -> {
                val match = effect.lastTributed.any { id ->
                    val c = findAnywhere(room, userId, id)
                    c != null && step.filter!!.matches(c)
                }
                if (!match) effect.stepIndex = effect.stepIndex + step.covers
                return false
            }
            is IfPossible -> {
                if (!allPossibleAfterIfPossible(room, effect, script(effect).steps!!, effect.stepIndex)) {
                    log(room, effect.sourceName + ": " + effect.label + " no se resuelve (ya no puede hacerse todo lo que pide)", null)
                    effect.stepIndex = script(effect).steps!!.size - 1
                }
                return false
            }
            is KeepOnField -> {
                val self = find(player.field, effect.sourceInstanceId)
                if (self != null) {
                    // It stays as a permanent: its effects on the field work like any other card's there
                    if (step.exhausted) self.tapped = true
                    effect.keepOnField = true
                    log(room, name(self) + (if (step.exhausted) " queda agotada en la retaguardia" else " permanece en el campo"), null)
                }
                return false
            }
            is IfDone -> {
                if (!effect.lastDone) effect.stepIndex = effect.stepIndex + step.covers
                effect.lastDone = false
                return false
            }
            is IfNotDone -> {
                if (effect.lastDone) effect.stepIndex = effect.stepIndex + step.covers
                effect.lastDone = false
                return false
            }
            is SubjectFromTrigger -> {
                val trigger = triggerEvent(room, effect)
                val card = if (trigger == null) null else find(player.field, trigger.cardInstanceId)
                if (card != null) effect.subjectId = card.instanceId
                return false
            }
            is MayDo -> {
                val steps = script(effect).steps!!
                val next = effect.stepIndex + 1
                // "puedes A para B": offered only when everything it covers can be done
                var can = next < steps.size
                var i = next
                while (can && i < Math.min(steps.size, next + step.covers)) {
                    if (steps[i] !is IfDone && !possible(room, effect, steps[i])) can = false
                    i++
                }
                if (!can) {
                    effect.stepIndex = effect.stepIndex + step.covers
                    return false
                }
                val prompt = prompt(room, effect, Prompt.Kind.CONFIRM, step.question)
                val source = findAnywhere(room, userId, effect.sourceInstanceId)
                if (source != null) {
                    prompt.cards = ArrayList(java.util.List.of(source))
                    prompt.validIds = ArrayList(java.util.List.of(source.instanceId))
                }
                prompt.min = 0
                prompt.max = 1
                prompt.chosenLabel = "Sí"
                room.prompt = prompt
                return true
            }
            else -> throw IllegalStateException("Paso de otra familia: $step")
        }
    }

    @JvmStatic
    fun applyStep(
        room: GameRoom, effect: PendingEffect, step: EffectStep, player: PlayerState, picks: List<String>,
        order: List<String>?, repeat: BooleanArray?,
    ): String? {
        val userId = player.userId
        when (step) {
            is MayDo -> {
                // Declined: skip what it covers
                if (picks.isEmpty()) effect.stepIndex = effect.stepIndex + step.covers
            }
            else -> {
                return "Este paso no espera ninguna elección"
            }
        }
        return null
    }
}
