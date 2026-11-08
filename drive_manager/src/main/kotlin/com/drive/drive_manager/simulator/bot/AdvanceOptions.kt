package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.engine.comparingDouble
import com.drive.drive_manager.simulator.engine.EffectEngine
import com.drive.drive_manager.simulator.engine.Prompt
import com.drive.drive_manager.simulator.engine.PromptPurpose
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/**
 * advanceMore (the user's call, 2026-11-05): which creatures move up to the Línea frontal at the end of the turn. The
 * search tried all of them, none, or one; with the removal's risk in the table (frontRisk) the answer is often "all but
 * the one that matters most" or "only those that stop the rival's best attacker". Those join the options to try.
 */
internal object AdvanceOptions {

    @JvmStatic
    fun add(options: MutableSet<List<String?>>, room: GameRoom, self: PlayerState?, prompt: Prompt, choices: List<String?>, s: BotSettings) {
        if (s.get("advanceMore") < 0.5 || self == null || !PromptPurpose.ADVANCE.`is`(prompt.purpose) || choices.size < 2) return
        val cards = choices.mapNotNull { id -> self.field.firstOrNull { c -> id!! == c.instanceId } }
        if (cards.size < 2) return
        // All but the most valuable (kept safe in the Retaguardia)
        val best = cards.maxWith(comparingDouble { c: CardSlot -> BotPlayer.value(c) })
        add(options, cards.filter { c -> c !== best }, prompt)
        // Only those that stop the rival's strongest creature (a block that wins or ties); none if none can
        val rival = room.getPlayer(room.getOpponentId(self.userId))
        val threat = rival?.field?.filter { c -> "creature" == c.cardType }?.maxOfOrNull { c -> EffectEngine.strength(c) } ?: -1
        if (threat >= 0) add(options, cards.filter { c -> EffectEngine.strength(c) >= threat }, prompt)
    }

    private fun add(options: MutableSet<List<String?>>, up: List<CardSlot>, prompt: Prompt) {
        if (up.size < prompt.min || up.size > prompt.max) return
        val ids: MutableList<String?> = ArrayList()
        for (c in up) ids.add(c.instanceId)
        options.add(ids)
    }
}
