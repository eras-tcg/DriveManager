package com.drive.drive_manager.simulator.mission;

import com.drive.drive_manager.service.MissionRules;
import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What an action in an online game counts for the players' missions: the summons and the damage to lives recorded
 * on the game's timeline since the action began (not what was done by hand from the board), and, when a game that was
 * really played just ended, one game played for each player and one won for the winner. A test room (solo mode)
 * counts nothing. Pure.
 */
public final class MissionEvents {

    /**
     * Turns that must have been passed for an ended game to count (each pass records a TURN_START): both players had
     * a turn and the first one started another. Ending a game right away (conceding in the pre-room or on the first
     * turn) is not a game played, so it cannot be farmed for gems.
     */
    static final int MIN_TURNS_PASSED = 2;
    /**
     * Events the engine keeps on the timeline (EffectEngine.TIMELINE_LIMIT): a timeline this long has lost its oldest
     * events, TURN_START included, and was plainly a real game.
     */
    static final int FULL_TIMELINE = 2000;

    private MissionEvents() {
    }

    /** One step of progress: a player, what it counts and how much. */
    public record Progress(String userId, String counts, int amount) {
    }

    /** The progress made by an action that started with the room in `statusBefore` and its timeline at `seqBefore`. */
    public static List<Progress> of(GameRoom room, GameRoom.RoomStatus statusBefore, long seqBefore) {
        List<Progress> out = new ArrayList<>();
        // Games against the bot count nothing, as test rooms: missions are won against players
        if (room.isEditor() || room.isBot()) return out;
        for (GameEvent e : room.getTimeline()) {
            if (e.getSeq() <= seqBefore || e.getPlayerId() == null || manual(e)) continue;
            switch (e.getType()) {
                case GameEvent.NORMAL_SUMMON -> out.add(new Progress(e.getPlayerId(), MissionRules.SUMMON, 1));
                case GameEvent.EFFECT_SUMMON -> {
                    out.add(new Progress(e.getPlayerId(), MissionRules.SUMMON, 1));
                    out.add(new Progress(e.getPlayerId(), MissionRules.EFFECT_SUMMON, 1));
                }
                case GameEvent.SPECIAL_SUMMON -> {
                    out.add(new Progress(e.getPlayerId(), MissionRules.SUMMON, 1));
                    out.add(new Progress(e.getPlayerId(), MissionRules.SPECIAL_SUMMON, 1));
                }
                case GameEvent.LIFE_DAMAGE -> damage(room, e, out);
                default -> {
                }
            }
        }
        // Only a game that was in progress when the action began, and lasted, counts as played
        boolean ended = statusBefore == GameRoom.RoomStatus.IN_PROGRESS && room.getStatus() == GameRoom.RoomStatus.POST_GAME;
        if (ended && played(room)) {
            room.getPlayers().keySet().forEach(id -> out.add(new Progress(id, MissionRules.GAME_PLAYED, 1)));
            if (room.getWinnerId() != null) out.add(new Progress(room.getWinnerId(), MissionRules.GAME_WON, 1));
        }
        return out;
    }

    /** The game lasted long enough to count: the turn was passed MIN_TURNS_PASSED times. */
    static boolean played(GameRoom room) {
        if (room.getTimeline().size() >= FULL_TIMELINE) return true;
        long passes = room.getTimeline().stream().filter(e -> GameEvent.TURN_START.equals(e.getType())).count();
        return passes >= MIN_TURNS_PASSED;
    }

    /** An event done by hand from the board (outside the engine's checks): it counts for nothing. */
    private static boolean manual(GameEvent e) {
        return e.getData() != null && Boolean.TRUE.equals(e.getData().get(GameEvent.MANUAL));
    }

    /** Damage to a player's lives counts for the rival (damage a player deals themself counts for nobody). */
    private static void damage(GameRoom room, GameEvent e, List<Progress> out) {
        Map<String, Object> data = e.getData();
        if (data == null || !(data.get("damage") instanceof Number amount) || amount.intValue() <= 0) return;
        String rival = room.getOpponentId(e.getPlayerId());
        if (rival == null) return;
        PlayerState damaged = room.getPlayer(e.getPlayerId());
        Object attacker = data.get("attackerId");
        if (damaged != null && attacker instanceof String id && owns(damaged, id)) return;
        out.add(new Progress(rival, MissionRules.DAMAGE, amount.intValue()));
    }

    private static boolean owns(PlayerState player, String instanceId) {
        for (List<CardSlot> zone : List.of(player.getHand(), player.getDeck(), player.getField(), player.getLifeStack(),
                player.getTributeZone(), player.getDiscardPile())) {
            for (CardSlot c : zone) if (instanceId.equals(c.getInstanceId())) return true;
        }
        return false;
    }
}
