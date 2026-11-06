package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;
import com.drive.drive_manager.simulator.room.RoomService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BotEdgeTest {

    private static GameRoom soloRoom(int edge) {
        GameRoom room = new GameRoom();
        room.setRoomId("r");
        room.setBot(true);
        room.getPlayers().put("me", new PlayerState("me", "Yo"));
        room.getPlayers().put(BotRooms.BOT, new PlayerState(BotRooms.BOT, "Bot Difícil"));
        room.setBotEdge(edge);
        return room;
    }

    @Test
    void aLevelEveryHundredOfMmrOverTheHardestBotUpToEight() {
        assertThat(BotEdge.levelFor(1299)).isZero();
        assertThat(BotEdge.levelFor(1399)).isZero();
        assertThat(BotEdge.levelFor(1400)).isEqualTo(1);
        assertThat(BotEdge.levelFor(1650)).isEqualTo(3);
        assertThat(BotEdge.levelFor(5000)).isEqualTo(8);
        assertThat(BotEdge.strength(0)).isEqualTo(1300);
        assertThat(BotEdge.strength(3)).isEqualTo(1600);
    }

    @Test
    void theBotsMarcadorGoesUpEarlierWithEachLevelThenTheRivalsIsHeldBack() {
        assertThat(BotEdge.botTurn(0)).isZero();
        assertThat(BotEdge.botTurn(1)).isEqualTo(4);
        assertThat(BotEdge.botTurn(2)).isEqualTo(3);
        assertThat(BotEdge.botTurn(3)).isEqualTo(2);
        assertThat(BotEdge.botTurn(4)).isEqualTo(1);
        assertThat(BotEdge.botTurn(8)).isEqualTo(1);
        assertThat(BotEdge.rivalTurn(4)).isZero();
        assertThat(BotEdge.rivalTurn(5)).isEqualTo(5);
        assertThat(BotEdge.rivalTurn(6)).isEqualTo(4);
        assertThat(BotEdge.rivalTurn(7)).isEqualTo(3);
        assertThat(BotEdge.rivalTurn(8)).isEqualTo(2);
    }

    @Test
    void theEdgeMovesOnlyThatTurnsMarcadorAndSaysItInTheHistory() {
        GameRoom room = soloRoom(6);
        PlayerState bot = room.getPlayer(BotRooms.BOT);
        PlayerState me = room.getPlayer("me");
        bot.setTurnsStarted(1);
        assertThat(BotEdge.marcador(room, bot, 1)).isEqualTo(2);
        bot.setTurnsStarted(2);
        assertThat(BotEdge.marcador(room, bot, 3)).isEqualTo(3);
        me.setTurnsStarted(4);
        assertThat(BotEdge.marcador(room, me, 4)).isEqualTo(3);
        me.setTurnsStarted(5);
        assertThat(BotEdge.marcador(room, me, 4)).isEqualTo(4);
        assertThat(room.getEventLog()).hasSize(2);
        assertThat(room.getEventLog().get(0).getText()).contains("Bot Difícil", "1 más", "turno 1");
        assertThat(room.getEventLog().get(1).getText()).contains("Yo", "1 menos", "turno 4");
    }

    @Test
    void theMarcadorStaysBetweenZeroAndFive() {
        GameRoom room = soloRoom(4);
        PlayerState bot = room.getPlayer(BotRooms.BOT);
        bot.setTurnsStarted(1);
        assertThat(BotEdge.marcador(room, bot, 5)).isEqualTo(5);
        // Capped: nothing changed, nothing said
        assertThat(room.getEventLog()).isEmpty();
    }

    @Test
    void aTestRoomGivesTheEdgeToItsRivalsSide() {
        GameRoom room = new GameRoom();
        room.setEditor(true);
        room.getPlayers().put("owner", new PlayerState("owner", "Dueño"));
        room.getPlayers().put(RoomService.EDITOR_RIVAL, new PlayerState(RoomService.EDITOR_RIVAL, "Rival (prueba)"));
        room.setBotEdge(1);
        assertThat(BotEdge.delta(room, RoomService.EDITOR_RIVAL, 4)).isEqualTo(1);
        assertThat(BotEdge.delta(room, "owner", 4)).isZero();
        room.setBotEdge(0);
        assertThat(BotEdge.delta(room, RoomService.EDITOR_RIVAL, 4)).isZero();
    }
}
