package com.drive.drive_manager.simulator.offline;

import com.drive.drive_manager.simulator.bot.HeuristicBot;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.eras.engine.local.LocalMatch;
import com.eras.engine.local.LocalMatchRoom;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records an offline game as eras-web's engine worker does: a LocalMatch made from the site's configuration, every body
 * the board sends recorded in order, and after each one the bot's moves made one by one (step() until it has none), each
 * counted; the result taken (LocalMatch.result()) the moment the room reaches the post-game. The player's moves are
 * chosen by a seeded heuristic bot on the match's room (a test driver, not a player).
 */
final class OfflineRecorder {

    static final JsonMapper JSON = JsonMapper.builder().build();
    /** The bot's clock in these tests: 20 ms a reading (a 2-second budget is 100 readings), so a game takes seconds. */
    static final long TICK_NANOS = 20_000_000L;

    private OfflineRecorder() {
    }

    /** A recorded game and the match that played it. */
    record Recorded(OfflineGame game, LocalMatch match) {
    }

    /** A whole game of `userId` with `deckId` (its spec in `decks`, its cards as the site lists them in `cards`). */
    static Recorded play(String userId, long seed, String level, ObjectNode decks, String deckId, List<String> cards, long startedAt) {
        ObjectNode config = JSON.createObjectNode();
        config.put("seed", String.valueOf(seed));
        config.put("userId", userId);
        config.put("botLevel", level);
        config.put("botClock", "ticks");
        config.put("tickNanos", String.valueOf(TICK_NANOS));
        config.set("decks", decks);
        LocalMatch match = new LocalMatch(config.toString());
        GameRoom room = LocalMatchRoom.of(match);
        List<OfflineGame.Step> steps = new ArrayList<>();
        java.util.function.Function<String, List<String>> send = body -> {
            List<String> answer = List.of(match.send(body));
            int moves = 0;
            while (room.getStatus() != GameRoom.RoomStatus.POST_GAME && match.step() != null) moves++;
            steps.add(new OfflineGame.Step(body, moves));
            return answer;
        };
        send.apply(body("JOIN_ROOM", Map.of("username", "Ana")));
        send.apply(body("SET_DECK", Map.of("deckId", deckId)));
        HeuristicBot player = new HeuristicBot();
        player.seed(seed * 31 + 1);
        int actions = 0;
        while (actions++ < 3000 && room.getStatus() != GameRoom.RoomStatus.POST_GAME) {
            GameAction action = player.next(room, userId);
            if (action == null) break;
            List<String> answer = send.apply(body(action.getType(), action.getPayload() == null ? Map.of() : action.getPayload()));
            JsonNode error = answer.isEmpty() ? null : JSON.readTree(answer.get(0)).get("error");
            if (error != null && error.isString()) player.refused(action, error.asString());
            else player.played(room, action);
        }
        JsonNode result = JSON.readTree(match.result());
        OfflineGame.Claim claim = new OfflineGame.Claim(result.path("gamesEnded").asInt(), text(result, "winnerId"),
                text(result, "endReason"), result.path("turns").asInt(), text(result, "digest"));
        Map<String, List<String>> used = new LinkedHashMap<>();
        used.put(deckId, cards);
        OfflineGame game = new OfflineGame(java.util.UUID.nameUUIDFromBytes((userId + seed + deckId).getBytes()).toString(),
                "test-build", String.valueOf(seed), level, startedAt, startedAt + 600_000, used, List.copyOf(steps), claim);
        return new Recorded(game, match);
    }

    static String body(String type, Map<String, ?> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("payload", payload);
        return JSON.writeValueAsString(body);
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v == null || v.isNull() ? null : v.asString();
    }
}
