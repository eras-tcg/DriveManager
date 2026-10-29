package com.drive.drive_manager.simulator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The pre-room: both players confirm their deck, starter and who goes first, then the game starts at the mulligan. */
class PreRoomTest {

    private final RoomService service = new RoomService();
    private GameRoom room;
    private int ids;

    @BeforeEach
    void setUp() {
        room = service.createRoom();
        for (String id : List.of("host", "guest")) {
            service.applyAction(room.getRoomId(), id, action("JOIN_ROOM", Map.of("username", id)));
            PlayerState p = room.getPlayer(id);
            p.setDeckId("deck-" + id);
            for (int i = 0; i < 10; i++) p.getDeck().add(card("plain", false));
            p.getDeck().add(card("starter-a", true));
            p.getDeck().add(card("starter-b", true));
            p.setStarterCardId("starter-a");
        }
    }

    @Test
    void bothReadyStartsAtTheMulliganWithTheChosenStartersAndFirstPlayer() {
        assertThat(service.applyAction(room.getRoomId(), "host", action("SET_STARTER", Map.of("cardId", "starter-b"))).getError()).isNull();
        assertThat(service.applyAction(room.getRoomId(), "guest", action("SET_FIRST_PLAYER", Map.of("choice", "guest"))).getError())
                .contains("dueño");
        service.applyAction(room.getRoomId(), "host", action("SET_FIRST_PLAYER", Map.of("choice", "guest")));
        service.applyAction(room.getRoomId(), "host", action("SET_READY", Map.of("ready", true)));
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.WAITING);
        service.applyAction(room.getRoomId(), "guest", action("SET_READY", Map.of("ready", true)));

        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.MULLIGAN);
        assertThat(room.getCurrentTurnUserId()).isEqualTo("guest");
        assertThat(room.getPlayer("host").getField()).extracting(CardSlot::getCardId).containsExactly("starter-b");
        assertThat(room.getPlayer("guest").getField()).extracting(CardSlot::getCardId).containsExactly("starter-a");
    }

    @Test
    void changingTheFirstPlayerAsksBothToConfirmAgain() {
        service.applyAction(room.getRoomId(), "guest", action("SET_READY", Map.of("ready", true)));
        service.applyAction(room.getRoomId(), "host", action("SET_FIRST_PLAYER", Map.of("choice", "host")));
        assertThat(room.getPlayer("guest").isReady()).isFalse();
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.WAITING);
    }

    @Test
    void anEmptyRoomIsKeptSoAReloadFindsIt() {
        service.handleSessionDisconnect("none");
        service.registerSession("s1", room.getRoomId(), "host");
        service.registerSession("s2", room.getRoomId(), "guest");
        room.getPlayers().values().forEach(p -> p.setConnected(true));
        service.handleSessionDisconnect("s1");
        service.handleSessionDisconnect("s2");
        assertThat(service.getRoom(room.getRoomId())).isNotNull();
        assertThat(service.openRooms()).extracting(GameRoom::getRoomId).contains(room.getRoomId());
    }

    @Test
    void theCreatorOwnsTheRoomEvenIfSomeoneElseEntersFirst() {
        GameRoom other = service.createRoom("creator");
        service.applyAction(other.getRoomId(), "guest", action("JOIN_ROOM", Map.of("username", "guest")));
        service.applyAction(other.getRoomId(), "creator", action("JOIN_ROOM", Map.of("username", "creator")));
        assertThat(other.getHostId()).isEqualTo("creator");
    }

    @Test
    void aCreatorWhoNeverEntersLosesTheRoomToThePlayerWaitingInIt() {
        GameRoom other = service.createRoom("creator");
        service.applyAction(other.getRoomId(), "guest", action("JOIN_ROOM", Map.of("username", "guest")));
        long created = other.getCreatedAt().toEpochMilli();
        assertThat(service.checkOwner(other, created + 1_000)).isNull();
        assertThat(service.checkOwner(other, created + 20_000).getEvent()).isEqualTo("OWNER_CHANGED");
        assertThat(other.getHostId()).isEqualTo("guest");
    }

    @Test
    void whenTheOwnerLeavesTheOtherPlayerOwnsTheRoomAndWhenNobodyIsLeftItCloses() {
        assertThat(room.getHostId()).isEqualTo("host");
        assertThat(service.applyAction(room.getRoomId(), "host", action("LEAVE_ROOM", Map.of())).getError()).isNull();
        assertThat(room.getPlayers()).containsOnlyKeys("guest");
        assertThat(room.getHostId()).isEqualTo("guest");
        assertThat(service.getRoom(room.getRoomId())).isNotNull();

        assertThat(service.applyAction(room.getRoomId(), "guest", action("LEAVE_ROOM", Map.of())).getEvent()).isEqualTo("ROOM_CLOSED");
        assertThat(service.getRoom(room.getRoomId())).isNull();
    }

    @Test
    void anOwnerWhoLosesTheConnectionKeepsTheRoomForAWhileThenItPassesOn() {
        service.registerSession("s1", room.getRoomId(), "host");
        room.getPlayers().values().forEach(p -> p.setConnected(true));
        service.handleSessionDisconnect("s1");
        long now = System.currentTimeMillis();
        // A reload comes back in time: still the owner
        assertThat(service.checkOwner(room, now)).isNull();
        assertThat(room.getHostId()).isEqualTo("host");
        // Gone for longer: the connected player owns it
        assertThat(service.checkOwner(room, now + 20_000).getEvent()).isEqualTo("OWNER_CHANGED");
        assertThat(room.getHostId()).isEqualTo("guest");
    }

    @Test
    void theOwnerCanTakeOutAPlayerAndInAGameOnlyOneWhoIsDisconnected() {
        assertThat(service.applyAction(room.getRoomId(), "guest", action("KICK_PLAYER", Map.of("userId", "host"))).getError()).contains("dueño");

        room.setStatus(GameRoom.RoomStatus.IN_PROGRESS);
        room.getPlayers().values().forEach(p -> p.setConnected(true));
        assertThat(service.applyAction(room.getRoomId(), "host", action("KICK_PLAYER", Map.of("userId", "guest"))).getError()).contains("desconectado");

        room.getPlayer("guest").setConnected(false);
        GameStateUpdate kicked = service.applyAction(room.getRoomId(), "host", action("KICK_PLAYER", Map.of("userId", "guest")));
        assertThat(kicked.getError()).isNull();
        assertThat(kicked.getSubjectUserId()).isEqualTo("guest");
        assertThat(room.getPlayers()).containsOnlyKeys("host");
        // The game is over: the owner waits for a new rival in the pre-room
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.WAITING);
    }

    @Test
    void inThePreRoomTheOwnerCanTakeOutAConnectedPlayer() {
        room.getPlayers().values().forEach(p -> p.setConnected(true));
        assertThat(service.applyAction(room.getRoomId(), "host", action("KICK_PLAYER", Map.of("userId", "guest"))).getError()).isNull();
        assertThat(room.getPlayers()).containsOnlyKeys("host");
    }

    @Test
    void aTestRoomIsPlayedAloneOnBothSidesAndNobodyElseEnters() {
        GameRoom test = service.createRoom("owner", true);
        String id = test.getRoomId();
        assertThat(service.openRooms()).extracting(GameRoom::getRoomId).doesNotContain(id);
        assertThat(service.applyAction(id, "owner", action("JOIN_ROOM", Map.of("username", "owner"))).getError()).isNull();
        assertThat(service.applyAction(id, "intruder", action("JOIN_ROOM", Map.of("username", "intruder"))).getError()).contains("prueba");
        assertThat(test.getPlayers()).containsOnlyKeys("owner", RoomService.EDITOR_RIVAL);

        // Both decks are set by the owner (the rival's with actAs); one confirmation starts it
        for (String side : List.of("owner", RoomService.EDITOR_RIVAL)) {
            PlayerState p = test.getPlayer(side);
            p.setDeckId("deck");
            for (int i = 0; i < 10; i++) p.getDeck().add(card("plain", false));
            p.getDeck().add(card("starter-a", true));
            p.setStarterCardId("starter-a");
        }
        assertThat(service.applyAction(id, "owner", action("SET_READY", Map.of("ready", true))).getEvent()).isEqualTo("START_GAME");
        assertThat(test.getStatus()).isEqualTo(GameRoom.RoomStatus.MULLIGAN);

        // A test card in the rival's field, made as the rival
        GameStateUpdate dummy = service.applyAction(id, "owner", action("CREATE_DUMMY", Map.of(
                "actAs", RoomService.EDITOR_RIVAL, "zone", "field", "cardName", "Mihel", "cardType", "creature", "strength", 2, "level", 1,
                "classes", List.of("ente"))));
        assertThat(dummy.getError()).isNull();
        CardSlot made = test.getPlayer(RoomService.EDITOR_RIVAL).getField().get(test.getPlayer(RoomService.EDITOR_RIVAL).getField().size() - 1);
        assertThat(made.getCardName()).isEqualTo("Mihel");
        assertThat(made.getStrength()).isEqualTo(2);
        assertThat(made.getClasses()).containsExactly("ente");
        assertThat(made.isFaceDown()).isFalse();

        // After the game, "Cambiar mazo" goes back to the pre-room at once: the owner decides for both sides
        test.setStatus(GameRoom.RoomStatus.POST_GAME);
        // (no deck store in these tests: the decks are not reloaded)
        test.getPlayers().values().forEach(p -> p.setDeckId(null));
        assertThat(service.applyAction(id, "owner", action("POST_GAME_CHOICE", Map.of("choice", "change_deck"))).getError()).isNull();
        assertThat(test.getStatus()).isEqualTo(GameRoom.RoomStatus.WAITING);

        // The owner leaving closes it
        assertThat(service.applyAction(id, "owner", action("LEAVE_ROOM", Map.of("actAs", RoomService.EDITOR_RIVAL))).getEvent()).isEqualTo("ROOM_CLOSED");
        assertThat(service.getRoom(id)).isNull();
    }

    @Test
    void aTestRoomMovesAWholeDeckAtOnceAndSurvivesAReconnectedSocket() {
        GameRoom test = service.createRoom("owner", true);
        String id = test.getRoomId();
        service.applyAction(id, "owner", action("JOIN_ROOM", Map.of("username", "owner")));
        for (String side : List.of("owner", RoomService.EDITOR_RIVAL)) {
            PlayerState p = test.getPlayer(side);
            p.setDeckId("deck");
            for (int i = 0; i < 30; i++) p.getDeck().add(card("plain", false));
            p.getDeck().add(card("starter-a", true));
            p.setStarterCardId("starter-a");
        }
        service.applyAction(id, "owner", action("SET_READY", Map.of("ready", true)));
        PlayerState owner = test.getPlayer("owner");
        List<String> deck = owner.getDeck().stream().map(CardSlot::getInstanceId).toList();
        GameStateUpdate moved = service.applyAction(id, "owner", action("BULK_MOVE", Map.of("instanceIds", deck, "fromZone", "deck", "toZone", "tributeZone")));
        assertThat(moved.getError()).isNull();
        assertThat(owner.getDeck()).isEmpty();
        assertThat(owner.getTributeZone()).extracting(CardSlot::getInstanceId).containsExactlyElementsOf(deck);
        assertThat(owner.getTributeZone()).noneMatch(CardSlot::isFaceDown);

        // The socket dropped and reconnected by itself (no JOIN_ROOM): acting again keeps the room open
        service.registerSession("s1", id, "owner");
        service.handleSessionDisconnect("s1");
        assertThat(owner.isConnected()).isFalse();
        assertThat(test.getEmptySince()).isNotNull();
        service.registerSession("s2", id, "owner");
        assertThat(owner.isConnected()).isTrue();
        assertThat(test.getEmptySince()).isNull();
    }

    @Test
    void anOwnerWaitingAloneCanPlayBothSides() {
        GameRoom waiting = service.createRoom("owner");
        service.applyAction(waiting.getRoomId(), "owner", action("JOIN_ROOM", Map.of("username", "owner")));
        assertThat(service.makeTestRoom(waiting.getRoomId(), "someone")).contains("dueño");
        assertThat(service.makeTestRoom(waiting.getRoomId(), "owner")).isNull();
        assertThat(waiting.isEditor()).isTrue();
        assertThat(waiting.getPlayers()).containsOnlyKeys("owner", RoomService.EDITOR_RIVAL);
        assertThat(service.openRooms()).extracting(GameRoom::getRoomId).doesNotContain(waiting.getRoomId());
        // With a rival in it, it is not possible
        assertThat(service.makeTestRoom(room.getRoomId(), "host")).contains("esperas rival");
    }

    @Test
    void testCardsOnlyExistInATestRoom() {
        assertThat(service.applyAction(room.getRoomId(), "host", action("CREATE_DUMMY", Map.of("zone", "hand", "cardName", "X"))).getError())
                .contains("sala de prueba");
    }

    private CardSlot card(String cardId, boolean starter) {
        CardSlot c = new CardSlot();
        c.setInstanceId("c" + (++ids));
        c.setCardId(cardId);
        c.setCardType("creature");
        c.setStarter(starter);
        c.setFaceDown(true);
        return c;
    }

    private static GameAction action(String type, Map<String, Object> payload) {
        GameAction a = new GameAction();
        a.setType(type);
        a.setPayload(new HashMap<>(payload));
        return a;
    }
}
