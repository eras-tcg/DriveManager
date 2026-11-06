package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.engine.Prompt;
import com.drive.drive_manager.simulator.engine.PromptPurpose;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * advanceMore (the user's call, 2026-11-05): which creatures move up to the Línea frontal at the end of the turn. The
 * search tried all of them, none, or one; with the removal's risk in the table (frontRisk) the answer is often "all but
 * the one that matters most" or "only those that stop the rival's best attacker". Those join the options to try.
 */
final class AdvanceOptions {

    private AdvanceOptions() {}

    static void add(Set<List<String>> options, GameRoom room, PlayerState self, Prompt prompt, List<String> choices, BotSettings s) {
        if (s.get("advanceMore") < 0.5 || self == null || !PromptPurpose.ADVANCE.is(prompt.getPurpose()) || choices.size() < 2) return;
        List<CardSlot> cards = choices.stream().map(id -> self.getField().stream().filter(c -> id.equals(c.getInstanceId())).findFirst().orElse(null))
                .filter(c -> c != null).toList();
        if (cards.size() < 2) return;
        // All but the most valuable (kept safe in the Retaguardia)
        CardSlot best = cards.stream().max(Comparator.comparingDouble(BotPlayer::value)).get();
        add(options, cards.stream().filter(c -> c != best).toList(), prompt);
        // Only those that stop the rival's strongest creature (a block that wins or ties); none if none can
        PlayerState rival = room.getPlayer(room.getOpponentId(self.getUserId()));
        int threat = rival == null ? -1 : rival.getField().stream().filter(c -> "creature".equals(c.getCardType()))
                .mapToInt(EffectEngine::strength).max().orElse(-1);
        if (threat >= 0) add(options, cards.stream().filter(c -> EffectEngine.strength(c) >= threat).toList(), prompt);
    }

    private static void add(Set<List<String>> options, List<CardSlot> up, Prompt prompt) {
        if (up.size() < prompt.getMin() || up.size() > prompt.getMax()) return;
        List<String> ids = new ArrayList<>();
        for (CardSlot c : up) ids.add(c.getInstanceId());
        options.add(ids);
    }
}
