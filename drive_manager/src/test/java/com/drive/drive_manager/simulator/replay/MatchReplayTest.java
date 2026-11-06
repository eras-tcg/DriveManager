package com.drive.drive_manager.simulator.replay;

import com.drive.drive_manager.simulator.bot.BotMatch;
import com.drive.drive_manager.simulator.bot.BotPlayer;
import com.drive.drive_manager.simulator.bot.HeuristicBot;
import com.drive.drive_manager.simulator.bot.LookaheadBot;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.RoomOptions;
import com.drive.drive_manager.simulator.room.RoomService;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Every game is recorded as it is played (its start and its actions) and, played again from the record, it ends the
 * same: the same cards everywhere, the same turn and the same winner (its fingerprint), rematches included.
 */
public class MatchReplayTest {

    /** Keeps the games instead of writing them to the database. */
    public static final class Kept extends MatchHistory {
        public final List<MatchRecorder.Match> games = new ArrayList<>();

        public Kept() {
            super(mock(MongoTemplate.class), null);
        }

        @Override
        void save(MatchRecorder.Match match) {
            games.add(match);
        }
    }

    private final RoomService service = new RoomService();
    private final Kept kept = new Kept();

    MatchReplayTest() {
        ReflectionTestUtils.setField(service, "history", kept);
    }

    /** Both bots play until the game is over; the room's id. */
    private String playOut(GameRoom room, BotPlayer host, BotPlayer guest) {
        String id = room.getRoomId();
        Map<String, BotPlayer> bots = Map.of("host", host, "guest", guest);
        for (int actions = 0; actions < BotMatch.MAX_ACTIONS && room.getStatus() != GameRoom.RoomStatus.POST_GAME; actions++) {
            boolean moved = false;
            for (String seat : List.of("host", "guest")) {
                GameAction action = bots.get(seat).next(room, seat);
                if (action == null) continue;
                GameStateUpdate update = service.applyAction(id, seat, action, false);
                if (update != null && update.getError() != null) bots.get(seat).refused(action, update.getError());
                else bots.get(seat).played(room, action);
                moved = true;
                break;
            }
            if (!moved) break;
        }
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.POST_GAME);
        return id;
    }

    private GameRoom newGame(String hostDeck, String guestDeck, long seed) {
        GameRoom room = service.createRoom("host");
        room.setRandom(new java.util.Random(seed));
        service.applyAction(room.getRoomId(), "host", BotPlayer.action("JOIN_ROOM", Map.of("username", "Ana")), false);
        service.applyAction(room.getRoomId(), "guest", BotPlayer.action("JOIN_ROOM", Map.of("username", "Beto")), false);
        int[] ids = {0};
        BotMatch.seat(room.getPlayer("host"), hostDeck, ids);
        BotMatch.seat(room.getPlayer("guest"), guestDeck, ids);
        return room;
    }

    private static void assertReplaysTheSame(MatchRecorder.Match game) {
        GameRoom again = MatchReplay.play(game.setup(), game.actions());
        assertThat(again.getStatus()).isEqualTo(GameRoom.RoomStatus.POST_GAME);
        assertThat(again.getWinnerId()).isEqualTo(game.winnerId());
        assertThat(again.getEndReason()).isEqualTo(game.endReason());
        assertThat(MatchReplay.fingerprint(again)).isEqualTo(game.fingerprint());
    }

    @Test
    void everyGameBetweenTheStarterDecksPlaysAgainTheSame() {
        long seed = 11;
        for (String a : BotMatch.DECKS) {
            for (String b : BotMatch.DECKS) {
                GameRoom room = newGame(a, b, seed++);
                playOut(room, new HeuristicBot(), new HeuristicBot());
            }
        }
        assertThat(kept.games).hasSize(BotMatch.DECKS.size() * BotMatch.DECKS.size());
        for (MatchRecorder.Match game : kept.games) {
            assertThat(game.actions()).isNotEmpty();
            assertThat(game.winnerId()).isNotNull();
            assertReplaysTheSame(game);
        }
    }

    @Test
    void aGameOfTheLookAheadBotPlaysAgainTheSame() {
        GameRoom room = newGame("st2", "st4", 5);
        LookaheadBot host = new LookaheadBot();
        host.seed(7);
        playOut(room, host, new HeuristicBot());
        assertThat(kept.games).hasSize(1);
        assertReplaysTheSame(kept.games.get(0));
    }

    @Test
    void aSecondGameInTheSameRoomIsAGameOfItsOwnAndPlaysAgainTheSame() {
        GameRoom room = newGame("st1", "st3", 3);
        String id = playOut(room, new HeuristicBot(), new HeuristicBot());
        // Back to the pre-room and the same decks again (the tests have no deck lists to rebuild them from)
        room.getPlayers().values().forEach(p -> p.setDeckId(null));
        service.applyAction(id, "host", BotPlayer.action("POST_GAME_CHOICE", Map.of("choice", "change_deck")), false);
        service.applyAction(id, "guest", BotPlayer.action("POST_GAME_CHOICE", Map.of("choice", "change_deck")), false);
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.WAITING);
        int[] ids = {room.getPlayers().values().stream().mapToInt(p -> p.getOriginalDeck().size()).sum() + 1000};
        BotMatch.seat(room.getPlayer("host"), "st2", ids);
        BotMatch.seat(room.getPlayer("guest"), "st4", ids);
        playOut(room, new HeuristicBot(), new HeuristicBot());
        assertThat(kept.games).hasSize(2);
        assertThat(kept.games.get(1).setup()).isNotSameAs(kept.games.get(0).setup());
        assertReplaysTheSame(kept.games.get(1));
    }

    @Test
    void aGameReadBackFromItsLogPlaysAgainTheSame() {
        GameRoom room = newGame("st3", "st2", 21);
        playOut(room, new HeuristicBot(), new HeuristicBot());
        MatchRecorder.Match game = kept.games.get(0);
        // As the history keeps it (JSON, gzipped there), read back
        MatchHistory.Body read = MatchHistory.read(MatchHistory.write(game.setup(), game.actions()));
        GameRoom again = MatchReplay.play(read.setup(), read.actions());
        assertThat(MatchReplay.fingerprint(again)).isEqualTo(game.fingerprint());
    }

    @Test
    void halfwayTheReplayShowsTheGameAsItWasThen() {
        GameRoom room = newGame("st4", "st1", 9);
        playOut(room, new HeuristicBot(), new HeuristicBot());
        MatchRecorder.Match game = kept.games.get(0);
        List<String> steps = new ArrayList<>();
        MatchReplay.play(game.setup(), game.actions(), game.actions().size(), r -> steps.add(MatchReplay.fingerprint(r)));
        assertThat(steps).hasSize(game.actions().size() + 1);
        assertThat(steps.get(steps.size() - 1)).isEqualTo(game.fingerprint());
        GameRoom half = MatchReplay.play(game.setup(), game.actions(), game.actions().size() / 2, null);
        assertThat(MatchReplay.fingerprint(half)).isEqualTo(steps.get(game.actions().size() / 2));
    }

    @Test
    void testRoomsAreNotKept() {
        GameRoom room = service.createRoom("host", RoomOptions.TEST);
        assertThat(room.isEditor()).isTrue();
        kept.save(new MatchRecorder.Match(room.getRoomId(), MatchSetup.of(room, 1), List.of(), java.time.Instant.now(),
                java.time.Instant.now(), null, null, null, 0, "", null));
        // The real history leaves them out (MatchHistory.save); the recorder never starts one for a test room either
        assertThat(service.recorder.actionsOf(room.getRoomId())).isNull();
    }
}
