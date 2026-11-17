package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/**
 * A family of [EffectStep]s that are not basic actions ([EffectStep.Do]): its three parts for each of its steps, which
 * [Resolution] calls with the table read as the effect's card sees it.
 */
interface StepFamily {

    /** Whether the step can do anything now (an optional effect is offered, "puedes A para B" is possible). */
    fun possibleStep(room: GameRoom, effect: PendingEffect, step: EffectStep): Boolean

    /** Carries the step out. Returns true when it left a prompt and waits for the player's choice. */
    fun runStep(room: GameRoom, effect: PendingEffect, step: EffectStep): Boolean

    /** Applies the player's choice: null when accepted, else the error for the player. Sets `repeat[0]` when the step asks again. */
    fun applyStep(
        room: GameRoom, effect: PendingEffect, step: EffectStep, player: PlayerState, picks: List<String>,
        order: List<String>?, repeat: BooleanArray,
    ): String?
}
