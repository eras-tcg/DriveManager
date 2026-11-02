package com.drive.drive_manager.simulator;

import com.drive.drive_manager.service.MissionRules;
import com.drive.drive_manager.simulator.engine.GameEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What an action in an online game counts for the players' missions: the summons and the damage to lives recorded
 * on the game's timeline since the action began, and, when the game just ended, one game played for each player and
 * one won for the winner. A test room (solo mode) counts nothing. Pure.
 */
public final class MissionEvents {

    private MissionEvents() {
    }

    /** One step of progress: a player, what it counts and how much. */
    public record Progress(String userId, String counts, int amount) {
    }

    /** The progress made by an action that started with the room in `statusBefore` and its timeline at `seqBefore`. */
    public static List<Progress> of(GameRoom room, GameRoom.RoomStatus statusBefore, long seqBefore) {
        List<Progress> out = new ArrayList<>();
        if (room.isEditor()) return out;
        for (GameEvent e : room.getTimeline()) {
            if (e.getSeq() <= seqBefore || e.getPlayerId() == null) continue;
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
        boolean ended = statusBefore != GameRoom.RoomStatus.POST_GAME && room.getStatus() == GameRoom.RoomStatus.POST_GAME;
        if (ended) {
            room.getPlayers().keySet().forEach(id -> out.add(new Progress(id, MissionRules.GAME_PLAYED, 1)));
            if (room.getWinnerId() != null) out.add(new Progress(room.getWinnerId(), MissionRules.GAME_WON, 1));
        }
        return out;
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
