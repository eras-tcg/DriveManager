package com.drive.drive_manager.simulator.rank;

import com.drive.drive_manager.simulator.bot.BotCatalog;
import com.drive.drive_manager.simulator.bot.BotMatch;
import com.drive.drive_manager.simulator.bot.BotPlayer;
import com.drive.drive_manager.simulator.bot.BotRooms;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.RoomOptions;
import com.drive.drive_manager.simulator.room.RoomService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Friendly and ranked rooms: what the owner may still choose in a ranked one, online and against the bot. */
class RankedRoomTest {

    private final RoomService service = new RoomService();

    private GameStateUpdate act(String roomId, String userId, String type, Map<String, Object> payload) {
        return service.applyAction(roomId, userId, BotPlayer.action(type, payload), false);
    }

    @Test
    void aRankedOnlineRoomIsListedAsRankedAndDrawsWhoGoesFirst() {
        GameRoom room = service.createRoom("owner", RoomOptions.RANKED);
        String id = room.getRoomId();
        act(id, "owner", "JOIN_ROOM", Map.of("username", "Ana"));
        assertThat(service.openRooms()).contains(room);
        assertThat(room.isRanked()).isTrue();
        assertThat(act(id, "owner", "SET_FIRST_PLAYER", Map.of("choice", "owner")).getError()).isNotNull();

        // Back to friendly: the owner chooses again
        GameStateUpdate friendly = act(id, "owner", "SET_RANKED", Map.of("ranked", false));
        assertThat(friendly.getError()).isNull();
        assertThat(friendly.isRanked()).isFalse();
        assertThat(act(id, "owner", "SET_FIRST_PLAYER", Map.of("choice", "owner")).getError()).isNull();
        // Ranked again: the choice goes back to the draw
        act(id, "owner", "SET_RANKED", Map.of("ranked", true));
        assertThat(room.getFirstChoice()).isEqualTo("random");
    }

    @Test
    void onlyTheOwnerMakesItRankedAndNeverATestRoom() {
        GameRoom room = service.createRoom("owner", RoomOptions.ONLINE);
        act(room.getRoomId(), "owner", "JOIN_ROOM", Map.of("username", "Ana"));
        act(room.getRoomId(), "guest", "JOIN_ROOM", Map.of("username", "Beto"));
        assertThat(act(room.getRoomId(), "guest", "SET_RANKED", Map.of("ranked", true)).getError()).isNotNull();
        assertThat(room.isRanked()).isFalse();

        GameRoom test = service.createRoom("owner", new RoomOptions(true, false, true));
        assertThat(test.isRanked()).isFalse();
        act(test.getRoomId(), "owner", "JOIN_ROOM", Map.of("username", "Ana"));
        assertThat(act(test.getRoomId(), "owner", "SET_RANKED", Map.of("ranked", true)).getError()).isNotNull();
    }

    @Test
    void rankedAgainstTheBotItsLevelAndDeckAreNotChosen() {
        GameRoom room = service.createRoom("owner", RoomOptions.BOT);
        String id = room.getRoomId();
        act(id, "owner", "JOIN_ROOM", Map.of("username", "Ana"));
        assertThat(act(id, "owner", "SET_BOT_LEVEL", Map.of("level", BotCatalog.HARD)).getError()).isNull();

        GameStateUpdate ranked = act(id, "owner", "SET_RANKED", Map.of("ranked", true));
        assertThat(ranked.getError()).isNull();
        assertThat(room.getBotLevel()).isEqualTo(BotCatalog.NORMAL);
        assertThat(act(id, "owner", "SET_BOT_LEVEL", Map.of("level", BotCatalog.EASY)).getError()).isNotNull();
        assertThat(service.applyAction(id, "owner", BotPlayer.action("SET_DECK", Map.of("deckId", "x", "actAs", BotRooms.BOT)), false).getError())
                .isNotNull();
    }

    /** A ladder where the owner has this strength. */
    private void ownerStrength(int mmr) {
        RankService ladder = new RankService(null) {
            @Override
            public RankRules.Standing of(String userId) {
                RankRules.Standing s = RankRules.Standing.start();
                return new RankRules.Standing(mmr, 20, 10, 10, s.tier(), 0, 0, false, 0, 0, 0, s.peak());
            }
        };
        org.springframework.test.util.ReflectionTestUtils.setField(service, "ranks", ladder);
    }

    @Test
    void rankedAgainstTheBotItsLevelFollowsTheOwnersStrength() {
        for (Map.Entry<Integer, String> c : Map.of(850, BotCatalog.EASY, 1000, BotCatalog.NORMAL, 1199, BotCatalog.NORMAL, 1250, BotCatalog.HARD).entrySet()) {
            ownerStrength(c.getKey());
            GameRoom room = service.createRoom("owner", RoomOptions.BOT);
            act(room.getRoomId(), "owner", "JOIN_ROOM", Map.of("username", "Ana"));
            act(room.getRoomId(), "owner", "SET_RANKED", Map.of("ranked", true));
            assertThat(room.getBotLevel()).as("MMR " + c.getKey()).isEqualTo(c.getValue());
            assertThat(room.getPlayer(BotRooms.BOT).getUsername()).isEqualTo(BotCatalog.nameOf(c.getValue()));
        }
    }

    @Test
    void aRoomMadeRankedAgainstTheBotTakesTheLevelAtOnce() {
        ownerStrength(1300);
        GameRoom room = service.createRoom("owner", RoomOptions.RANKED_BOT);
        assertThat(room.isRanked()).isTrue();
        assertThat(room.getBotLevel()).isEqualTo(BotCatalog.HARD);
    }

    @Test
    void inAFriendlyGameTheBotIsNamedByTheLevelChosen() {
        GameRoom room = service.createRoom("owner", RoomOptions.BOT);
        act(room.getRoomId(), "owner", "JOIN_ROOM", Map.of("username", "Ana"));
        assertThat(room.getPlayer(BotRooms.BOT).getUsername()).isEqualTo("Bot Normal");
        act(room.getRoomId(), "owner", "SET_BOT_LEVEL", Map.of("level", BotCatalog.HARD));
        assertThat(room.getPlayer(BotRooms.BOT).getUsername()).isEqualTo("Bot Difícil");
    }
    @Test
    void madeRankedAfterChoosingTheDeckTheBotPlaysItsOwnCopiesOfIt() {
        GameRoom room = service.createRoom("owner", RoomOptions.BOT);
        String id = room.getRoomId();
        act(id, "owner", "JOIN_ROOM", Map.of("username", "Ana"));
        BotMatch.seat(room.getPlayer("owner"), "st1", new int[] {0});
        room.getPlayer("owner").getOriginalDeck().forEach(c -> c.setFinish("foil"));

        assertThat(act(id, "owner", "SET_RANKED", Map.of("ranked", true)).getError()).isNull();

        List<CardSlot> mine = room.getPlayer("owner").getOriginalDeck();
        List<CardSlot> bots = room.getPlayer(BotRooms.BOT).getDeck();
        Set<String> myIds = mine.stream().map(CardSlot::getInstanceId).collect(Collectors.toSet());
        assertThat(bots).hasSameSizeAs(mine);
        // Sharing ids, the board and the engine took the bot's cards for the owner's (its lives flew from the owner's deck)
        assertThat(bots).noneMatch(c -> myIds.contains(c.getInstanceId()));
        assertThat(room.getPlayer(BotRooms.BOT).getOriginalDeck()).noneMatch(c -> myIds.contains(c.getInstanceId()));
        assertThat(bots).allMatch(c -> c.getFinish() == null);
    }
}
