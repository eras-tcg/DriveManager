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
import static com.drive.drive_manager.simulator.engine.CombatRules.*;
import static com.drive.drive_manager.simulator.engine.Chain.*;
import static com.drive.drive_manager.simulator.engine.Steps.*;
import static com.drive.drive_manager.simulator.engine.Answers.*;

/** The start and the end of a turn (rule 8) as the engine sees them: moving up to the Línea frontal and the turn's own triggers. */
public final class TurnRules {

    private TurnRules() {}

    // ------------------------------------------------------------------ Línea frontal

    /**
     * Rules 8.1.0.4 and 8.3.0.3: at the start of the turn and in the end phase the active player may move any of
     * their creatures from the Retaguardia to the Línea frontal, all in one action. Asks only if there are some.
     */
    public static void offerAdvance(GameRoom room, String userId, boolean startOfTurn) {
        PlayerState player = room.getPlayer(userId);
        if (player == null || room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS || room.getPrompt() != null) return;
        List<CardSlot> back = player.getField().stream().filter(c -> "creature".equals(c.getCardType()) && !inFrontLine(c)).toList();
        if (back.isEmpty()) return;
        String title = startOfTurn ? "Inicio de turno" : "Final de turno";
        rulesPrompt(room, userId, PURPOSE_ADVANCE, title + " · Línea frontal",
                "Elige las criaturas de tu retaguardia que suben a la línea frontal (todas a la vez), o ninguna.",
                "A la línea frontal", back, back);
        room.getPrompt().setMax(back.size());
    }

    /**
     * Rule 8.1: the start of the turn, after the gems, the draw and the reactivation: records it (start-of-turn
     * triggers), offers the move up to the Línea frontal and lets pending effects resolve.
     */
    public static void turnStarted(GameRoom room, String playerId) {
        PlayerState player = room.getPlayer(playerId);
        if (player == null) return;
        record(room, GameEvent.TURN_START, playerId, null, null, null, null, null, "Inicio del turno de " + player.getUsername());
        // Reactivar: "al inicio del turno: reactiva esta carta" (any turn)
        for (PlayerState p : room.getPlayers().values()) {
            for (CardSlot c : p.getField()) {
                if (c.isTapped() && keywordLevel(c, "Reactivar") != null) {
                    c.setTapped(false);
                    record(room, GameEvent.UNTAP, p.getUserId(), c, null, null, null, Map.of("keyword", "Reactivar"),
                            name(c) + " se reactiva (Reactivar)");
                }
            }
        }
        offerAdvance(room, playerId, true);
        advance(room);
    }

    /**
     * Rule 8.3: the end phase, after the Contador: offers the move up to the Línea frontal and records it
     * (end-of-turn triggers resolve once the move up is answered).
     */
    public static void turnEnding(GameRoom room, String playerId) {
        PlayerState player = room.getPlayer(playerId);
        if (player == null) return;
        record(room, GameEvent.TURN_END, playerId, null, null, null, null, null, "Final del turno de " + player.getUsername());
        offerAdvance(room, playerId, false);
        advance(room);
    }
}
