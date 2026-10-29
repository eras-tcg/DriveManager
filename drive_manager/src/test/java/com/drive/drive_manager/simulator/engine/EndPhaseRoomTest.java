package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameAction;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.GameStateUpdate;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** End-of-turn effects through the room, as played: ENTER_END_PHASE, the move up, then the triggers (Antorcha Legionaria). */
class EndPhaseRoomTest {

    private final RoomService service = new RoomService();
    private GameRoom room;
    private PlayerState host;

    @BeforeEach
    void setUp() {
        room = service.createRoom();
        host = new PlayerState("host", "Ana");
        PlayerState guest = new PlayerState("guest", "Beto");
        room.getPlayers().put("host", host);
        room.getPlayers().put("guest", guest);
        room.setStatus(GameRoom.RoomStatus.IN_PROGRESS);
        room.setCurrentTurnUserId("host");
        room.setCurrentPhase("MAIN");
        room.setSharedCounter(-3);
        host.setResourceCount(3);
        guest.setResourceCount(3);
        host.setDeck(deck("hd", 8));
        guest.setDeck(deck("gd", 8));
    }

    @Test
    void antorchaLegionariaSummonsALegionarioAtTheEndOfTheTurn() {
        onField(host, legionario(card("ant", "Antorcha Legionaria", 2, 2, "R")));
        onField(host, legionario(card("peon", "Peón De Los Leones Dorados", 1, 1, "R")), BACK);
        onField(host, legionario(card("esp", "Espadón Legionario", 3, 3, "R")), BACK);
        CardSlot inHand = legionario(card("lh", "Legionario En Mano", 2, 2, "R"));
        host.getHand().add(inHand);

        assertThat(act("ENTER_END_PHASE").getError()).isNull();
        boolean offered = false;
        for (int i = 0; i < 10 && room.getPrompt() != null; i++) {
            Prompt p = room.getPrompt();
            List<String> picks = List.of();
            if (p.getValidIds().contains("ant")) {
                offered = true;
                picks = List.of("ant");
            } else if (p.getValidIds().contains("lh")) {
                picks = List.of("lh");
            }
            assertThat(Answers.answer(room, p.getPlayerId(), p.getId(), picks, List.of())).as(p.getTitle()).isNull();
        }
        assertThat(offered).isTrue();
        assertThat(host.getField()).contains(inHand);
    }

    private GameStateUpdate act(String type) {
        GameAction action = new GameAction();
        action.setType(type);
        action.setPayload(new HashMap<>(Map.of()));
        return service.applyAction(room.getRoomId(), "host", action);
    }

    private static CardSlot legionario(CardSlot c) {
        c.setClasses(new ArrayList<>(List.of("legionario")));
        return c;
    }
}
