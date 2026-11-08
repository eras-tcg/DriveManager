package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.simulator.engine.GameRandom;
import com.drive.drive_manager.simulator.actions.PostGameActions;
import com.drive.drive_manager.simulator.bot.BotCatalog;
import com.drive.drive_manager.simulator.bot.BotMatch;
import com.drive.drive_manager.simulator.bot.BotPlayer;
import com.drive.drive_manager.simulator.bot.BotRooms;
import com.drive.drive_manager.simulator.bot.HeuristicBot;
import com.drive.drive_manager.simulator.bot.Sandbox;
import com.drive.drive_manager.simulator.rank.RankRules;
import com.drive.drive_manager.simulator.rank.RankService;
import com.drive.drive_manager.simulator.replay.MatchRecorder;
import com.drive.drive_manager.simulator.replay.MatchReplay;
import com.drive.drive_manager.simulator.replay.MatchReplayTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** What the 2026-11-05 audit fixed, kept fixed. */
class AuditFixesTest {

    private final RoomService service = new RoomService();
    private final MatchReplayTest.Kept kept = new MatchReplayTest.Kept();

    AuditFixesTest() {
        ReflectionTestUtils.setField(service, "history", kept);
    }

    private GameStateUpdate act(String roomId, String userId, String type, Map<String, Object> payload) {
        return service.applyAction(roomId, userId, BotPlayer.action(type, payload), false);
    }

    /** A test room started with two starter decks. */
    private GameRoom startedTestRoom() {
        GameRoom room = service.createRoom("owner", RoomOptions.TEST);
        room.setRandom(new GameRandom(9));
        act(room.getRoomId(), "owner", "JOIN_ROOM", Map.of("username", "Ana"));
        int[] ids = {0};
        BotMatch.seat(room.getPlayer("owner"), "st2", ids);
        BotMatch.seat(room.getPlayer(RoomService.EDITOR_RIVAL), "st4", ids);
        act(room.getRoomId(), "owner", "SET_READY", Map.of("ready", true));
        return room;
    }

    @Test
    void inATestRoomTheBotsCopiesCanPlayTheRivalsSide() {
        GameRoom room = startedTestRoom();
        HeuristicBot rival = new HeuristicBot();
        Sandbox sandbox = new Sandbox();
        // Up to the rival's first decision, each side played by its own heuristic bot
        Map<String, BotPlayer> bots = Map.of("owner", new HeuristicBot(), RoomService.EDITOR_RIVAL, rival);
        GameAction move = null;
        for (int i = 0; i < 200 && move == null; i++) {
            move = rival.next(room, RoomService.EDITOR_RIVAL);
            if (move != null) break;
            GameAction own = bots.get("owner").next(room, "owner");
            assertThat(own).isNotNull();
            BotRooms.applyAs(service, room, "owner", own);
        }
        assertThat(move).as("the rival had a decision").isNotNull();
        GameRoom copy = sandbox.fork(room, RoomService.EDITOR_RIVAL);
        assertThat(copy.isHeadless()).isTrue();
        assertThat(sandbox.act(copy, RoomService.EDITOR_RIVAL, move).getError()).isNull();
        sandbox.drop(copy);
    }

    /** A ranked game against the bot, under way. */
    private GameRoom rankedGameAgainstTheBot() {
        GameRoom room = service.createRoom("owner", RoomOptions.RANKED_BOT);
        act(room.getRoomId(), "owner", "JOIN_ROOM", Map.of("username", "Ana"));
        int[] ids = {0};
        BotMatch.seat(room.getPlayer("owner"), "st1", ids);
        BotMatch.seat(room.getPlayer(BotRooms.BOT), "st1", ids);
        room.getPlayer(BotRooms.BOT).setReady(true);
        act(room.getRoomId(), "owner", "SET_READY", Map.of("ready", true));
        assertThat(RoomGuard.playing(room)).isTrue();
        return room;
    }

    @Test
    void leavingARankedGameAgainstTheBotLosesItAndIsKept() {
        GameRoom room = rankedGameAgainstTheBot();
        assertThat(act(room.getRoomId(), "owner", "LEAVE_ROOM", Map.of()).getError()).isNull();
        assertThat(kept.games).hasSize(1);
        MatchRecorder.Match game = kept.games.get(0);
        assertThat(game.winnerId()).isEqualTo(BotRooms.BOT);
        assertThat(game.endReason()).isEqualTo("abandon");
        assertThat(game.setup().ranked()).isTrue();
        assertThat(MatchReplay.fingerprint(MatchReplay.play(game.setup(), game.actions()))).isEqualTo(game.fingerprint());
    }

    @Test
    void aDisconnectedRivalIsTakenOutOfAGameOnlyAfterTheGrace() {
        GameRoom room = service.createRoom("owner", RoomOptions.RANKED);
        String id = room.getRoomId();
        act(id, "owner", "JOIN_ROOM", Map.of("username", "Ana"));
        act(id, "guest", "JOIN_ROOM", Map.of("username", "Beto"));
        int[] ids = {0};
        BotMatch.seat(room.getPlayer("owner"), "st1", ids);
        BotMatch.seat(room.getPlayer("guest"), "st3", ids);
        act(id, "guest", "SET_READY", Map.of("ready", true));
        act(id, "owner", "SET_READY", Map.of("ready", true));
        assertThat(RoomGuard.playing(room)).isTrue();
        PlayerState guest = room.getPlayer("guest");
        guest.setConnected(false);
        guest.setDisconnectedAt(System.currentTimeMillis());
        assertThat(act(id, "owner", "KICK_PLAYER", Map.of("userId", "guest")).getError()).isNotNull();
        guest.setDisconnectedAt(System.currentTimeMillis() - 60_000);
        assertThat(act(id, "owner", "KICK_PLAYER", Map.of("userId", "guest")).getError()).isNull();
        // Kept as an abandon, which plays again the same
        MatchRecorder.Match game = kept.games.get(0);
        assertThat(game.actions().get(game.actions().size() - 1).type()).isEqualTo(MatchRecorder.ABANDON);
        assertThat(MatchReplay.fingerprint(MatchReplay.play(game.setup(), game.actions()))).isEqualTo(game.fingerprint());
    }

    @Test
    void theHiddenStrengthIsNeverInWhatPlayersSee() {
        RankRules.Change c = RankRules.after(RankRules.Standing.start(), true, 1000);
        Document change = RankService.changeDocument(c, "m1", "r1");
        assertThat(change.get("before", Document.class)).doesNotContainKey("mmr");
        assertThat(change.get("after", Document.class)).doesNotContainKey("mmr");
    }

    @Test
    void aRematchKeepsTheDecksAsChosenWithTheirFinishes() {
        GameRoom room = rankedGameAgainstTheBot();
        room.getPlayer("owner").getOriginalDeck().forEach(c -> c.setFinish("foil"));
        act(room.getRoomId(), "owner", "SURRENDER", Map.of());
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.POST_GAME);
        PostGameActions.reset(room, true);
        List<CardSlot> all = new ArrayList<>(room.getPlayer("owner").getDeck());
        assertThat(all).isNotEmpty().allMatch(c -> "foil".equals(c.getFinish()));
        assertThat(room.getStartSetup()).isNull();
    }

    /**
     * What stays when the Sala de pruebas travels in time (or a replay moves): the fields marked RoomSetting. A field
     * added to or taken from this list is a choice (does it belong to the room or to the game?), so it is spelled out.
     */
    @Test
    void theRoomsOwnSettingsAreTheFieldsMarkedRoomSetting() {
        Set<String> settings = Arrays.stream(GameRoom.class.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(RoomSetting.class)).map(Field::getName).collect(Collectors.toSet());
        assertThat(settings).containsExactlyInAnyOrder("roomId", "editor", "bot", "ranked", "reserved", "editorBot",
                "editorSelfBot", "selfBotLevel", "botDelayMs", "botEdge", "botLevel", "confirmMandatory", "hostId", "createdAt", "emptySince",
                "manualPlayers", "adminPlayers", "paused", "timelineAt", "timelineSize", "random", "headless", "replay", "version");
    }

    @Test
    void theBotLevelsSplitHalfwayBetweenTheirStrengths() {
        assertThat(BotCatalog.levelFor(999)).isEqualTo(BotCatalog.EASY);
        assertThat(BotCatalog.levelFor(1000)).isEqualTo(BotCatalog.NORMAL);
        assertThat(BotCatalog.levelFor(1199)).isEqualTo(BotCatalog.NORMAL);
        assertThat(BotCatalog.levelFor(1200)).isEqualTo(BotCatalog.HARD);
        assertThat(new HashMap<>(RankRules.BOT_MMR)).containsKeys(BotCatalog.EASY, BotCatalog.NORMAL, BotCatalog.HARD);
    }
}
