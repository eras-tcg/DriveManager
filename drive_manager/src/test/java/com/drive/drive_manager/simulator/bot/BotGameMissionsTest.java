package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.service.MissionRules;
import com.drive.drive_manager.service.MissionService;
import com.drive.drive_manager.service.ProgressionRules;
import com.drive.drive_manager.service.ProgressionService;
import com.drive.drive_manager.simulator.mission.MissionHooks;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.RoomOptions;
import com.drive.drive_manager.simulator.room.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A game against the server's bot (Modo solo) counts for its player's missions and progression as a game against a
 * player does (through RoomService's MissionHooks.count, after each action, the bot's moves included), never for the
 * bot; the Sala de pruebas, even with its bots playing, counts nothing.
 */
class BotGameMissionsTest {

    private final RoomService service = new RoomService();
    private MissionService missions;
    private ProgressionService progression;

    @BeforeEach
    void setUp() {
        missions = mock(MissionService.class);
        progression = mock(ProgressionService.class);
        service.hooks = new MissionHooks(missions, progression, null, null);
    }

    @Test
    void aFinishedGameAgainstTheBotCountsForItsPlayer() {
        GameRoom room = service.createRoom("owner", RoomOptions.BOT);
        String id = room.getRoomId();
        service.applyAction(id, "owner", BotPlayer.action("JOIN_ROOM", Map.of("username", "Ana")), false);
        int[] ids = {0};
        BotMatch.seat(room.getPlayer("owner"), "st1", ids);
        BotMatch.seat(room.getPlayer(BotRooms.BOT), "st3", ids);
        service.playBotNow(id);
        service.applyAction(id, "owner", BotPlayer.action("SET_READY", Map.of("ready", true)), false);

        // The owner plays as the passive bot would; the room's bot moves after each of the owner's actions
        BotPlayer owner = new BotPlayer();
        for (int i = 0; i < BotMatch.MAX_ACTIONS && room.getStatus() != GameRoom.RoomStatus.POST_GAME; i++) {
            service.playBotNow(id);
            if (room.getStatus() == GameRoom.RoomStatus.POST_GAME) break;
            GameAction action = owner.next(room, "owner");
            if (action == null) continue;
            GameStateUpdate update = service.applyAction(id, "owner", action, false);
            if (update != null && update.getError() != null) owner.refused(action, update.getError());
            else owner.played(room, action);
        }
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.POST_GAME);

        verify(missions, times(1)).record("owner", MissionRules.GAME_PLAYED, 1);
        verify(progression, times(1)).record("owner", MissionRules.GAME_PLAYED, 1);
        verify(progression, times(1)).record("owner", ProgressionRules.BOT_GAME_PLAYED, 1);
        verify(missions, never()).record(anyString(), eq(ProgressionRules.BOT_GAME_PLAYED), anyInt());
        int won = "owner".equals(room.getWinnerId()) ? 1 : 0;
        verify(missions, times(won)).record("owner", MissionRules.GAME_WON, 1);
        verify(progression, times(won)).record("owner", MissionRules.GAME_WON, 1);
        // The bot (who summoned and dealt damage) has no account
        verify(missions, never()).record(eq(BotRooms.BOT), anyString(), anyInt());
        verify(progression, never()).record(eq(BotRooms.BOT), anyString(), anyInt());
        assertThat(room.getTimeline()).anyMatch(e -> BotRooms.BOT.equals(e.getPlayerId()) && "NORMAL_SUMMON".equals(e.getType()));
    }

    @Test
    void theOwnersSummonsCountAsTheyHappen() {
        GameRoom room = service.createRoom("owner", RoomOptions.BOT);
        String id = room.getRoomId();
        service.applyAction(id, "owner", BotPlayer.action("JOIN_ROOM", Map.of("username", "Ana")), false);
        int[] ids = {0};
        BotMatch.seat(room.getPlayer("owner"), "st1", ids);
        BotMatch.seat(room.getPlayer(BotRooms.BOT), "st3", ids);
        service.playBotNow(id);
        service.applyAction(id, "owner", BotPlayer.action("SET_READY", Map.of("ready", true)), false);
        BotPlayer owner = new HeuristicBot();
        for (int i = 0; i < BotMatch.MAX_ACTIONS && room.getStatus() != GameRoom.RoomStatus.POST_GAME; i++) {
            service.playBotNow(id);
            if (room.getStatus() == GameRoom.RoomStatus.POST_GAME) break;
            GameAction action = owner.next(room, "owner");
            if (action == null) continue;
            GameStateUpdate update = service.applyAction(id, "owner", action, false);
            if (update != null && update.getError() != null) owner.refused(action, update.getError());
            else owner.played(room, action);
        }
        long summons = room.getTimeline().stream().filter(e -> "owner".equals(e.getPlayerId()) && "NORMAL_SUMMON".equals(e.getType())).count();
        assertThat(summons).isPositive();
        verify(missions, atLeastOnce()).record("owner", MissionRules.SUMMON, 1);
        verify(progression, atLeastOnce()).record("owner", MissionRules.SUMMON, 1);
        verify(missions, never()).record(eq(BotRooms.BOT), anyString(), anyInt());
    }

    @Test
    void theSalaDePruebasCountsNothingEvenWithItsBotsPlayingTheWholeGame() {
        GameRoom room = service.createRoom("owner", RoomOptions.TEST);
        String id = room.getRoomId();
        service.applyAction(id, "owner", BotPlayer.action("JOIN_ROOM", Map.of("username", "Ana")), false);
        int[] ids = {0};
        BotMatch.seat(room.getPlayer("owner"), "st1", ids);
        BotMatch.seat(room.getPlayer(RoomService.EDITOR_RIVAL), "st3", ids);
        service.applyAction(id, "owner", BotPlayer.action("SET_EDITOR_BOT", Map.of("on", true)), false);
        service.applyAction(id, "owner", BotPlayer.action("SET_BOT_LEVEL", Map.of("level", BotCatalog.EASY)), false);
        service.applyAction(id, "owner", BotPlayer.action("SET_BOT_LEVEL", Map.of("level", BotCatalog.EASY, "side", "self")), false);
        room.getAdminPlayers().add("owner");
        service.applyAction(id, "owner", BotPlayer.action("SET_EDITOR_SELF_BOT", Map.of("on", true)), false);
        service.applyAction(id, "owner", BotPlayer.action("SET_READY", Map.of("ready", true)), false);
        for (int i = 0; i < 50 && room.getStatus() != GameRoom.RoomStatus.POST_GAME; i++) service.playBotNow(id);
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.POST_GAME);

        verify(missions, never()).record(anyString(), anyString(), anyInt());
        verify(progression, never()).record(anyString(), anyString(), anyInt());
    }
}
