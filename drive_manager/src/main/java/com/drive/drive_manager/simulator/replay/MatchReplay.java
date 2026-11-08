package com.drive.drive_manager.simulator.replay;

import com.drive.drive_manager.simulator.actions.PreRoomActions;
import com.drive.drive_manager.simulator.bot.BotPlayer;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.RoomService;
import com.drive.drive_manager.simulator.json.SimulatorJson;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Plays a recorded game again ({@link MatchSetup} and its actions) on a room service of its own (no database, no
 * messages, no missions): the same seed and the same actions give the same game, which its fingerprint confirms.
 */
public final class MatchReplay {

    private static final JsonMapper JSON = SimulatorJson.configure(JsonMapper.builder()).build();
    /** What changes with time or the connection, not with the game: left out of the fingerprint. */
    private static final List<String> NOT_GAME = List.of("avatar", "connected", "disconnectedAt", "ready");

    private MatchReplay() {}

    /** The room after playing the game again from its start up to its end. */
    public static GameRoom play(MatchSetup setup, List<MatchRecorder.Played> actions) {
        return play(setup, actions, actions.size(), null);
    }

    /**
     * The room after playing the game's first {@code upTo} actions; {@code onStep} sees the room after its start and
     * after each action (a viewer of the game, step by step).
     */
    static GameRoom play(MatchSetup setup, List<MatchRecorder.Played> actions, int upTo, Consumer<GameRoom> onStep) {
        RoomService engine = new RoomService();
        GameRoom room = setup.toRoom("replay");
        room.setHeadless(true);
        engine.adopt(room);
        PreRoomActions.startFromPreRoom(room, setup.seed());
        if (onStep != null) onStep.accept(room);
        for (MatchRecorder.Played p : actions.subList(0, Math.min(upTo, actions.size()))) {
            if (MatchRecorder.ABANDON.equals(p.type())) engine.members.remove(room, p.userId());
            else engine.applyAction(room.getRoomId(), p.userId(), BotPlayer.action(p.type(), p.payload()), p.manual());
            if (onStep != null) onStep.accept(room);
        }
        return room;
    }

    /**
     * A short sum of the game's state (each player's cards, zones and counters, the turn, the phase, the result and
     * the room's counters): the same game played again gives the same fingerprint.
     */
    public static String fingerprint(GameRoom room) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", room.getStatus().name());
        state.put("turn", room.getCurrentTurnUserId());
        state.put("phase", room.getCurrentPhase());
        state.put("counter", room.getSharedCounter());
        state.put("winner", room.getWinnerId());
        state.put("reason", room.getEndReason());
        state.put("idSeq", room.getIdSeq());
        state.put("eventSeq", room.getEventSeq());
        Map<String, Object> players = new LinkedHashMap<>();
        room.getPlayers().forEach((id, p) -> {
            ObjectNode node = JSON.valueToTree(p);
            NOT_GAME.forEach(node::remove);
            players.put(id, node);
        });
        state.put("players", players);
        try {
            byte[] bytes = JSON.writeValueAsString(state).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot sum up the game's state", e);
        }
    }
}
