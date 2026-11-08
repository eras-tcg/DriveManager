package com.drive.drive_manager.simulator.mission;

import com.drive.drive_manager.service.MissionRules;
import com.drive.drive_manager.service.ProgressionRules;
import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What an online game's actions count for the players' missions (a game against the bot too; never the Sala de pruebas). */
class MissionEventsTest {

    private GameRoom room;

    @BeforeEach
    void setUp() {
        room = new GameRoom();
        room.setStatus(GameRoom.RoomStatus.IN_PROGRESS);
        room.getPlayers().put("alice", player("alice"));
        room.getPlayers().put("bob", player("bob"));
    }

    private static PlayerState player(String id) {
        return new PlayerState(id, id);
    }

    private void event(long seq, String type, String playerId, Map<String, Object> data) {
        GameEvent e = new GameEvent();
        e.setSeq(seq);
        e.setType(type);
        e.setPlayerId(playerId);
        e.setData(data);
        room.getTimeline().add(e);
    }

    @Test
    void summonsCountForWhoeverSummons() {
        event(1, GameEvent.NORMAL_SUMMON, "alice", null);
        event(2, GameEvent.EFFECT_SUMMON, "alice", null);
        event(3, GameEvent.SPECIAL_SUMMON, "bob", null);

        assertThat(MissionEvents.of(room, GameRoom.RoomStatus.IN_PROGRESS, 0)).containsExactly(
                new MissionEvents.Progress("alice", MissionRules.SUMMON, 1),
                new MissionEvents.Progress("alice", MissionRules.SUMMON, 1),
                new MissionEvents.Progress("alice", MissionRules.EFFECT_SUMMON, 1),
                new MissionEvents.Progress("bob", MissionRules.SUMMON, 1),
                new MissionEvents.Progress("bob", MissionRules.SPECIAL_SUMMON, 1));
    }

    @Test
    void onlyWhatTheActionAddedCounts() {
        event(1, GameEvent.NORMAL_SUMMON, "alice", null);

        assertThat(MissionEvents.of(room, GameRoom.RoomStatus.IN_PROGRESS, 1)).isEmpty();
    }

    @Test
    void damageToALifeCountsForTheRivalButNotDamageToOneself() {
        CardSlot own = new CardSlot();
        own.setInstanceId("biok");
        room.getPlayer("bob").getField().add(own);
        event(1, GameEvent.LIFE_DAMAGE, "bob", Map.of("damage", 300, "attackerId", "x"));
        event(2, GameEvent.LIFE_DAMAGE, "bob", Map.of("damage", 100, "attackerId", "biok"));

        assertThat(MissionEvents.of(room, GameRoom.RoomStatus.IN_PROGRESS, 0)).containsExactly(new MissionEvents.Progress("alice", MissionRules.DAMAGE, 300));
    }

    @Test
    void anEndedGameCountsForBothAndItsWinner() {
        event(1, GameEvent.TURN_START, "bob", null);
        event(2, GameEvent.TURN_START, "alice", null);
        room.setStatus(GameRoom.RoomStatus.POST_GAME);
        room.setWinnerId("bob");

        assertThat(MissionEvents.of(room, GameRoom.RoomStatus.IN_PROGRESS, 2)).containsExactly(
                new MissionEvents.Progress("alice", MissionRules.GAME_PLAYED, 1),
                new MissionEvents.Progress("bob", MissionRules.GAME_PLAYED, 1),
                new MissionEvents.Progress("bob", MissionRules.GAME_WON, 1));
        // Already in the post-game: nothing new
        assertThat(MissionEvents.of(room, GameRoom.RoomStatus.POST_GAME, 0)).isEmpty();
    }

    @Test
    void aGameEndedBeforeTheTurnPassedTwiceCountsForNobody() {
        // Conceded on the second turn
        event(1, GameEvent.TURN_START, "bob", null);
        room.setStatus(GameRoom.RoomStatus.POST_GAME);
        room.setWinnerId("bob");

        assertThat(MissionEvents.of(room, GameRoom.RoomStatus.IN_PROGRESS, 1)).isEmpty();
    }

    @Test
    void aGameThatNeverStartedCountsForNobody() {
        // From the pre-room (or the starter choice, the mulligan) straight to the post-game
        event(1, GameEvent.TURN_START, "bob", null);
        event(2, GameEvent.TURN_START, "alice", null);
        room.setStatus(GameRoom.RoomStatus.POST_GAME);
        room.setWinnerId("bob");

        assertThat(MissionEvents.of(room, GameRoom.RoomStatus.WAITING, 2)).isEmpty();
        assertThat(MissionEvents.of(room, GameRoom.RoomStatus.MULLIGAN, 2)).isEmpty();
    }

    @Test
    void aSpecialSummonDoneByHandCountsForNothing() {
        event(1, GameEvent.SPECIAL_SUMMON, "alice", Map.of("summonType", "ritual", GameEvent.MANUAL, true));
        event(2, GameEvent.SPECIAL_SUMMON, "alice", Map.of("summonType", "ritual", "cost", 2));

        assertThat(MissionEvents.of(room, GameRoom.RoomStatus.IN_PROGRESS, 0)).containsExactly(
                new MissionEvents.Progress("alice", MissionRules.SUMMON, 1),
                new MissionEvents.Progress("alice", MissionRules.SPECIAL_SUMMON, 1));
    }

    @Test
    void aGameAgainstTheBotCountsForItsPlayerAsAGameAgainstAPlayerButNeverForTheBot() {
        room.setBot(true);
        room.getPlayers().remove("bob");
        room.getPlayers().put("bot", player("bot"));
        CardSlot own = new CardSlot();
        own.setInstanceId("bots-own");
        room.getPlayer("bot").getField().add(own);
        event(1, GameEvent.NORMAL_SUMMON, "alice", null);
        event(2, GameEvent.NORMAL_SUMMON, "bot", null);
        event(3, GameEvent.LIFE_DAMAGE, "bot", Map.of("damage", 300, "attackerId", "x"));
        event(4, GameEvent.LIFE_DAMAGE, "alice", Map.of("damage", 200, "attackerId", "bots-own"));
        event(5, GameEvent.TURN_START, "bot", null);
        event(6, GameEvent.TURN_START, "alice", null);
        room.setStatus(GameRoom.RoomStatus.POST_GAME);
        room.setWinnerId("alice");

        assertThat(MissionEvents.of(room, GameRoom.RoomStatus.IN_PROGRESS, 0)).containsExactly(
                new MissionEvents.Progress("alice", MissionRules.SUMMON, 1),
                new MissionEvents.Progress("alice", MissionRules.DAMAGE, 300),
                new MissionEvents.Progress("alice", MissionRules.GAME_PLAYED, 1),
                new MissionEvents.Progress("alice", ProgressionRules.BOT_GAME_PLAYED, 1),
                new MissionEvents.Progress("alice", MissionRules.GAME_WON, 1));
        // The bot wins: the player's game counts, nothing for the bot
        room.setWinnerId("bot");
        assertThat(MissionEvents.of(room, GameRoom.RoomStatus.IN_PROGRESS, 6)).containsExactly(
                new MissionEvents.Progress("alice", MissionRules.GAME_PLAYED, 1),
                new MissionEvents.Progress("alice", ProgressionRules.BOT_GAME_PLAYED, 1));
    }

    @Test
    void aTestRoomWithItsBotsCountsNothing() {
        room.setEditor(true);
        room.setEditorBot(true);
        event(1, GameEvent.NORMAL_SUMMON, "alice", null);
        event(2, GameEvent.TURN_START, "bob", null);
        event(3, GameEvent.TURN_START, "alice", null);
        room.setStatus(GameRoom.RoomStatus.POST_GAME);
        room.setWinnerId("alice");

        assertThat(MissionEvents.of(room, GameRoom.RoomStatus.IN_PROGRESS, 0)).isEmpty();
    }

    @Test
    void soloModeCountsNothing() {
        room.setEditor(true);
        event(1, GameEvent.NORMAL_SUMMON, "alice", null);
        room.setStatus(GameRoom.RoomStatus.POST_GAME);

        assertThat(MissionEvents.of(room, GameRoom.RoomStatus.IN_PROGRESS, 0)).isEmpty();
    }
}
