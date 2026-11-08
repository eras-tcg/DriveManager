package com.drive.drive_manager.simulator.json;

import com.drive.drive_manager.simulator.engine.GameRandom;
import com.drive.drive_manager.simulator.bot.BotMatch;
import com.drive.drive_manager.simulator.bot.BotPlayer;
import com.drive.drive_manager.simulator.bot.HeuristicBot;
import com.drive.drive_manager.simulator.replay.MatchReplay;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.RoomService;
import com.drive.drive_manager.simulator.room.StateMask;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the simulator sends and sums up as JSON stays the same now that the Jackson annotations left the engine's
 * classes for the mix-ins ({@link SimulatorJson}): the rooms and updates the players receive, and the fingerprints
 * that confirm a kept game replays the same. The sums were taken with the annotations still on the classes.
 */
class SimulatorJsonTest {

    /** Per game: the JSON of the room and of each update after every action, and the fingerprints, summed up. */
    /** Per game: what each player is sent of every update (StateMask.forViewer, as a player and as an editor), summed up. */
    private static final Map<String, String> VIEWS = Map.of(
            "st1-st2-11", "572b595e244378956e82ebdb814def70",
            "st3-st4-12", "6c5d4cd60d9695fa033b9739891ed180");

    private static final Map<String, String> GOLDEN = Map.of(
            "st1-st2-11", "d9a32513b98f4053a565ac61e0e8fde7",
            "st3-st4-12", "ec2f8ff3b507081889c772357d32e141");

    @Test
    void sendsAndSumsUpAsBefore() throws Exception {
        JsonMapper json = SimulatorJson.configure(JsonMapper.builder()).build();
        Map<String, String> sums = new java.util.LinkedHashMap<>();
        for (String game : List.of("st1-st2-11", "st3-st4-12")) {
            String[] g = game.split("-");
            sums.put(game, play(json, g[0], g[1], Long.parseLong(g[2])));
        }
        assertThat(sums).isEqualTo(GOLDEN);
    }

    @Test
    void eachPlayerSeesAsBefore() throws Exception {
        JsonMapper json = SimulatorJson.configure(JsonMapper.builder()).build();
        Map<String, String> sums = new java.util.LinkedHashMap<>();
        for (String game : List.of("st1-st2-11", "st3-st4-12")) {
            String[] g = game.split("-");
            sums.put(game, play(json, g[0], g[1], Long.parseLong(g[2]), true));
        }
        assertThat(sums).isEqualTo(VIEWS);
    }

    private static String play(JsonMapper json, String hostDeck, String guestDeck, long seed) throws Exception {
        return play(json, hostDeck, guestDeck, seed, false);
    }

    private static String play(JsonMapper json, String hostDeck, String guestDeck, long seed, boolean views) throws Exception {
        RoomService service = new RoomService();
        GameRoom room = service.createRoom("host");
        room.setRandom(new GameRandom(seed));
        room.setCreatedAt(java.time.Instant.EPOCH);
        String id = room.getRoomId();
        service.applyAction(id, "host", BotPlayer.action("JOIN_ROOM", Map.of("username", "Ana")), false);
        service.applyAction(id, "guest", BotPlayer.action("JOIN_ROOM", Map.of("username", "Beto")), false);
        int[] ids = {0};
        BotMatch.seat(room.getPlayer("host"), hostDeck, ids);
        BotMatch.seat(room.getPlayer("guest"), guestDeck, ids);
        BotPlayer bot = new HeuristicBot();
        bot.seed(seed);
        MessageDigest sum = MessageDigest.getInstance("SHA-256");
        for (int actions = 0; actions < 400 && room.getStatus() != GameRoom.RoomStatus.POST_GAME; actions++) {
            boolean moved = false;
            for (String seat : List.of("host", "guest")) {
                var action = bot.next(room, seat);
                if (action == null) continue;
                GameStateUpdate update = service.applyAction(id, seat, action, false);
                bot.played(room, action);
                // The history's lines carry the time they were written ("ts", left out of the sums); the room's JSON
                // leaves them out altogether, the players' views keep them (the history is masked too)
                if (!views) {
                    room.getEventLog().clear();
                    if (update != null) update.setEventLog(null);
                }
                if (views) {
                    if (update != null && update.getError() == null) {
                        for (String viewer : List.of("host", "guest")) for (boolean manual : new boolean[] {false, true}) {
                            sum.update(stable(json, StateMask.forViewer(update, viewer, manual)));
                        }
                    }
                } else {
                    sum.update(stable(json, room));
                    sum.update(stable(json, update));
                    sum.update(MatchReplay.fingerprint(room).getBytes(StandardCharsets.UTF_8));
                }
                moved = true;
                break;
            }
            if (!moved) break;
        }
        return HexFormat.of().formatHex(sum.digest()).substring(0, 32);
    }

    /**
     * The JSON without what changes from run to run whatever the game: the clock, the room's random id, and the order
     * of the events' data (Map.of has a different order in each run of the JVM): its keys are sorted.
     */
    private static byte[] stable(JsonMapper json, Object value) {
        return json.writeValueAsString(stable(json.valueToTree(value))).getBytes(StandardCharsets.UTF_8);
    }

    private static tools.jackson.databind.JsonNode stable(tools.jackson.databind.JsonNode node) {
        if (node instanceof tools.jackson.databind.node.ObjectNode object) {
            tools.jackson.databind.node.ObjectNode sorted = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            new java.util.TreeSet<>(object.propertyNames()).forEach(name -> {
                if (!List.of("ts", "roomId", "createdAt").contains(name)) sorted.set(name, stable(object.get(name)));
            });
            return sorted;
        }
        if (node != null && node.isArray()) {
            tools.jackson.databind.node.ArrayNode array = tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> array.add(stable(item)));
            return array;
        }
        return node;
    }
}
