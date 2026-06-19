package com.drive.drive_manager.simulator;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.repository.DeckListRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class RoomService {

    private final ConcurrentHashMap<String, GameRoom> rooms = new ConcurrentHashMap<>();

    @Autowired
    private DeckListRepository deckListRepo;

    @Autowired
    private CardRepository cardRepo;

    @Autowired
    private DriveCardRepository driveCardRepo;

    @Value("${r2.public-url}")
    private String r2PublicUrl;

    public GameRoom createRoom() {
        GameRoom room = new GameRoom();
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        room.setRoomId(id);
        rooms.put(id, room);
        return room;
    }

    public GameRoom getRoom(String roomId) {
        return rooms.get(roomId);
    }

    public GameStateUpdate applyAction(String roomId, String userId, GameAction action) {
        GameRoom room = rooms.get(roomId);
        if (room == null) return GameStateUpdate.error(roomId, "Room not found");

        return switch (action.getType()) {
            case "JOIN_ROOM"            -> handleJoin(room, userId, action);
            case "DRAW_CARD"            -> handleDraw(room, userId);
            case "MOVE_CARD"            -> handleMove(room, userId, action);
            case "FLIP_CARD"            -> handleFlip(room, userId, action);
            case "SHUFFLE_DECK"         -> handleShuffle(room, userId);
            case "PASS_TURN"            -> handlePassTurn(room, userId);
            case "ENTER_END_PHASE"      -> handleEnterEndPhase(room, userId);
            case "END_TURN"             -> handleEndTurn(room, userId);
            case "FORFEIT"              -> handleForfeit(room, userId);
            case "SURRENDER"            -> handleSurrender(room, userId);
            case "POST_GAME_CHOICE"     -> handlePostGameChoice(room, userId, action);
            case "START_GAME"           -> handleStartGame(room, userId, action);
            case "SELECT_STARTER"       -> handleSelectStarter(room, userId, action);
            case "CONFIRM_MULLIGAN"     -> handleConfirmMulligan(room, userId, action);
            case "SET_MARKER"           -> handleSetMarker(room, userId, action);
            case "LIFE_HEAL"            -> handleLifeHeal(room, userId);
            case "LIFE_SHUFFLE"         -> handleLifeShuffle(room, userId);
            case "LIFE_ALL_DOWN"        -> handleLifeAllDown(room, userId);
            case "LIFE_ALL_UP"          -> handleLifeAllUp(room, userId);
            case "LIFE_RESTART"         -> handleLifeRestart(room, userId);
            case "DECK_REORDER_TOP"     -> handleDeckReorderTop(room, userId, action);
            case "ZONE_REORDER"         -> handleZoneReorder(room, userId, action);
            case "DECK_INCLUDE_DISCARD" -> handleDeckIncludeDiscard(room, userId);
            case "DECK_INCLUDE_TRIBUTE" -> handleDeckIncludeTribute(room, userId);
            case "TAP_CARD"             -> handleTapCard(room, userId, action);
            case "SET_COUNTER"          -> handleSetCounter(room, userId, action);
            case "USE_RESOURCES"        -> handleUseResources(room, userId, action);
            case "GRANT_RESOURCE"           -> handleGrantResource(room, userId);
            case "MODIFY_STRENGTH"          -> handleModifyStrength(room, userId, action);
            case "ADD_MATERIAL"             -> handleAddMaterial(room, userId, action);
            case "ADD_RESOURCE"             -> handleAddResource(room, userId, action);
            case "MOVE_FROM_ATTACHMENT"     -> handleMoveFromAttachment(room, userId, action);
            case "SPECIAL_SUMMON"           -> handleSpecialSummon(room, userId, action);
            case "SWAP_WITH_MAIN"           -> handleSwapWithMain(room, userId, action);
            case "TARGET_CARD"              -> handleTargetCard(room, userId, action);
            case "REORDER_ATTACHMENT"           -> handleReorderAttachment(room, userId, action);
            case "RECLASSIFY_ATTACHMENT"        -> handleReclassifyAttachment(room, userId, action);
            default                             -> GameStateUpdate.error(roomId, "Unknown action: " + action.getType());
        };
    }

    private GameStateUpdate handleJoin(GameRoom room, String userId, GameAction action) {
        if (room.isFull() && !room.getPlayers().containsKey(userId)) {
            return GameStateUpdate.error(room.getRoomId(), "Room is full");
        }
        String username = action.getString("username");
        String deckId   = action.getString("deckId");

        if (room.getPlayers().containsKey(userId)) {
            PlayerState existing = room.getPlayer(userId);
            existing.setConnected(true);
            if (room.getStatus() == GameRoom.RoomStatus.WAITING && deckId != null) {
                existing.setDeckId(deckId);
                List<CardSlot> slots = resolveDeck(deckId);
                existing.setDeck(slots);
            }
            return GameStateUpdate.from(room, "RECONNECT", userId);
        }
        if (username == null) username = userId;

        PlayerState player = new PlayerState(userId, username);
        player.setConnected(true);

        if (deckId != null) {
            player.setDeckId(deckId);
            List<CardSlot> slots = resolveDeck(deckId);
            player.setDeck(slots);
        }

        room.getPlayers().put(userId, player);

        if (room.getPlayers().size() == 1) {
            room.setHostId(userId);
        }

        return GameStateUpdate.from(room, "JOIN_ROOM", userId);
    }

    private GameStateUpdate handleDraw(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null)          return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        if (player.getDeck().isEmpty()) return GameStateUpdate.error(room.getRoomId(), "Deck is empty");

        CardSlot drawn = player.getDeck().remove(0);
        drawn.setFaceDown(false);
        player.getHand().add(drawn);

        return GameStateUpdate.from(room, "DRAW_CARD", userId);
    }

    private GameStateUpdate handleMove(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");

        String instanceId = action.getString("instanceId");
        String fromZone   = action.getString("fromZone");
        String toZone     = action.getString("toZone");
        Double x          = action.getDouble("x");
        Double y          = action.getDouble("y");

        List<CardSlot> from = getZone(player, fromZone);
        List<CardSlot> to   = getZone(player, toZone);
        if (from == null || to == null) return GameStateUpdate.error(room.getRoomId(), "Invalid zone");

        Optional<CardSlot> cardOpt = from.stream()
                .filter(c -> instanceId.equals(c.getInstanceId()))
                .findFirst();
        if (cardOpt.isEmpty()) return GameStateUpdate.error(room.getRoomId(), "Card not found");

        CardSlot card = cardOpt.get();
        from.remove(card);

        if ("field".equals(toZone)) {
            card.setX(x);
            card.setY(y);
        } else {
            card.setX(null);
            card.setY(null);
        }

        // When leaving field, send attached cards to discard
        if ("field".equals(fromZone) && !"field".equals(toZone)) {
            card.getMaterials().forEach(m -> { m.setFaceDown(false); m.setTapped(false); player.getDiscardPile().add(m); });
            card.getResources().forEach(r -> { r.setFaceDown(false); r.setTapped(false); player.getDiscardPile().add(r); });
            card.setMaterials(new ArrayList<>());
            card.setResources(new ArrayList<>());
            card.setStrengthModifier(0);
        }

        if ("discardPile".equals(toZone)) {
            card.setFaceDown(false);
        }
        if ("lifeStack".equals(fromZone) && !"lifeStack".equals(toZone)) {
            card.setFaceDown(false);
        }
        if ("deck".equals(fromZone) && !"deck".equals(toZone) && !"lifeStack".equals(toZone)) {
            card.setFaceDown(false);
        }
        if ("deck".equals(toZone)) {
            card.setFaceDown(true);
        }
        if (!"field".equals(toZone)) {
            card.setTapped(false);
        }

        String toPosition = action.getString("toPosition");
        if ("deck".equals(toZone)) {
            if ("top".equals(toPosition)) {
                to.add(0, card);
            } else {
                to.add(card);
            }
        } else if ("lifeStack".equals(toZone) && "top".equals(toPosition)) {
            to.add(0, card);
        } else {
            to.add(card);
        }
        return GameStateUpdate.from(room, "MOVE_CARD", userId);
    }

    private GameStateUpdate handleFlip(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");

        String instanceId = action.getString("instanceId");
        String zoneName   = action.getString("zone");
        List<CardSlot> zone = getZone(player, zoneName);
        if (zone == null) return GameStateUpdate.error(room.getRoomId(), "Invalid zone");

        zone.stream()
                .filter(c -> instanceId.equals(c.getInstanceId()))
                .findFirst()
                .ifPresent(c -> c.setFaceDown(!c.isFaceDown()));

        return GameStateUpdate.from(room, "FLIP_CARD", userId);
    }

    private GameStateUpdate handleShuffle(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        Collections.shuffle(player.getDeck());
        return GameStateUpdate.from(room, "SHUFFLE_DECK", userId);
    }

    private GameStateUpdate handlePassTurn(GameRoom room, String userId) {
        String opponentId = room.getOpponentId(userId);
        if (opponentId != null) {
            PlayerState current = room.getPlayer(userId);
            current.setResourceCount(0);

            PlayerState opponent = room.getPlayer(opponentId);
            int newCount = Math.min(5, opponent.getResourceCount() + 1);
            opponent.setResourceCount(newCount);

            int dir = getPlayerDirection(room, opponentId);
            int next = room.getSharedCounter() + newCount * dir;
            room.setSharedCounter(Math.max(-10, Math.min(10, next)));

            room.setCurrentTurnUserId(opponentId);
        }
        return GameStateUpdate.from(room, "PASS_TURN", userId);
    }

    private GameStateUpdate handleForfeit(GameRoom room, String userId) {
        room.setStatus(GameRoom.RoomStatus.FINISHED);
        return GameStateUpdate.from(room, "FORFEIT", userId);
    }

    private GameStateUpdate handleStartGame(GameRoom room, String userId, GameAction action) {
        if (!userId.equals(room.getHostId()))
            return GameStateUpdate.error(room.getRoomId(), "Solo el host puede iniciar");
        if (room.getStatus() != GameRoom.RoomStatus.WAITING || room.getPlayers().size() < 2)
            return GameStateUpdate.error(room.getRoomId(), "No se puede iniciar aún");
        String firstPlayerId = action.getString("firstPlayerId");
        if (firstPlayerId == null || "random".equals(firstPlayerId)) {
            List<String> ids = new ArrayList<>(room.getPlayers().keySet());
            firstPlayerId = ids.get(new java.util.Random().nextInt(ids.size()));
        }
        if (!room.getPlayers().containsKey(firstPlayerId))
            return GameStateUpdate.error(room.getRoomId(), "Jugador no encontrado");
        room.setCurrentTurnUserId(firstPlayerId);
        room.setCurrentPhase("MAIN");
        room.getStarterSelections().clear();
        room.getMulliganReady().clear();
        room.setStatus(GameRoom.RoomStatus.SELECTING_STARTER);
        return GameStateUpdate.from(room, "START_GAME", userId);
    }

    private GameStateUpdate handleSelectStarter(GameRoom room, String userId, GameAction action) {
        if (room.getStatus() != GameRoom.RoomStatus.SELECTING_STARTER)
            return GameStateUpdate.error(room.getRoomId(), "No estás en selección de starter");
        if (room.getStarterSelections().containsKey(userId))
            return GameStateUpdate.error(room.getRoomId(), "Ya seleccionaste tu starter");
        String instanceId = action.getString("instanceId");
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Jugador no encontrado");
        Optional<CardSlot> cardOpt = player.getDeck().stream()
                .filter(c -> instanceId.equals(c.getInstanceId())).findFirst();
        if (cardOpt.isEmpty()) return GameStateUpdate.error(room.getRoomId(), "Carta no encontrada");
        CardSlot starter = cardOpt.get();
        player.getDeck().remove(starter);
        starter.setFaceDown(false);
        starter.setX(50.0);
        starter.setY(50.0);
        player.getField().add(starter);
        room.getStarterSelections().put(userId, instanceId);
        if (room.getStarterSelections().size() >= room.getPlayers().size()) {
            room.getPlayers().values().forEach(p -> Collections.shuffle(p.getDeck()));
            room.getMulliganReady().clear();
            room.setStatus(GameRoom.RoomStatus.MULLIGAN);
        }
        return GameStateUpdate.from(room, "SELECT_STARTER", userId);
    }

    private GameStateUpdate handleConfirmMulligan(GameRoom room, String userId, GameAction action) {
        if (room.getStatus() != GameRoom.RoomStatus.MULLIGAN)
            return GameStateUpdate.error(room.getRoomId(), "No estás en mulligán");
        if (room.getMulliganReady().contains(userId))
            return GameStateUpdate.error(room.getRoomId(), "Ya confirmaste el mulligán");
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Jugador no encontrado");
        @SuppressWarnings("unchecked")
        List<String> toBottom = action.getPayload() != null && action.getPayload().containsKey("toBottom")
                ? (List<String>) action.getPayload().get("toBottom")
                : Collections.emptyList();
        int topN = Math.min(5, player.getDeck().size());
        List<CardSlot> top5 = new ArrayList<>(player.getDeck().subList(0, topN));
        player.getDeck().subList(0, topN).clear();
        List<CardSlot> toBottomCards = top5.stream()
                .filter(c -> toBottom.contains(c.getInstanceId())).collect(Collectors.toList());
        List<CardSlot> keepCards = top5.stream()
                .filter(c -> !toBottom.contains(c.getInstanceId())).collect(Collectors.toList());
        player.getDeck().addAll(toBottomCards);
        keepCards.forEach(c -> c.setFaceDown(false));
        player.getHand().addAll(keepCards);
        while (player.getHand().size() < 5 && !player.getDeck().isEmpty()) {
            CardSlot drawn = player.getDeck().remove(0);
            drawn.setFaceDown(false);
            player.getHand().add(drawn);
        }
        Collections.shuffle(player.getDeck());
        int lifeCount = Math.min(6, player.getDeck().size());
        for (int i = 0; i < lifeCount; i++) {
            CardSlot c = player.getDeck().remove(0);
            c.setFaceDown(true);
            player.getLifeStack().add(c);
        }
        room.getMulliganReady().add(userId);
        if (room.getMulliganReady().size() >= room.getPlayers().size()) {
            room.setStatus(GameRoom.RoomStatus.IN_PROGRESS);
            String firstId = room.getCurrentTurnUserId();
            PlayerState first = room.getPlayer(firstId);
            if (first != null) {
                first.setResourceCount(1);
                int dir = getPlayerDirection(room, firstId);
                room.setSharedCounter(Math.max(-10, Math.min(10, room.getSharedCounter() + dir)));
            }
        }
        return GameStateUpdate.from(room, "CONFIRM_MULLIGAN", userId);
    }

    private GameStateUpdate handleSurrender(GameRoom room, String userId) {
        room.getPostGameChoices().clear();
        room.setStatus(GameRoom.RoomStatus.POST_GAME);
        return GameStateUpdate.from(room, "SURRENDER", userId);
    }

    private GameStateUpdate handlePostGameChoice(GameRoom room, String userId, GameAction action) {
        if (room.getStatus() != GameRoom.RoomStatus.POST_GAME)
            return GameStateUpdate.error(room.getRoomId(), "No estás en post-partida");
        String choice = action.getString("choice");
        if (choice == null) return GameStateUpdate.error(room.getRoomId(), "Falta choice");
        room.getPostGameChoices().put(userId, choice);
        if (room.getPostGameChoices().size() >= room.getPlayers().size()) {
            boolean anyExit       = room.getPostGameChoices().values().stream().anyMatch("exit"::equals);
            boolean anyChangeDeck = room.getPostGameChoices().values().stream().anyMatch("change_deck"::equals);
            if (anyExit) {
                List<String> exiting = room.getPostGameChoices().entrySet().stream()
                        .filter(e -> "exit".equals(e.getValue())).map(Map.Entry::getKey).collect(Collectors.toList());
                exiting.forEach(id -> room.getPlayers().remove(id));
                room.getPostGameChoices().clear();
                room.setStatus(GameRoom.RoomStatus.FINISHED);
                if (room.getPlayers().isEmpty()) rooms.remove(room.getRoomId());
            } else if (anyChangeDeck) {
                resetGameState(room, false);
                room.setStatus(GameRoom.RoomStatus.WAITING);
            } else {
                resetGameState(room, true);
                room.setStatus(GameRoom.RoomStatus.SELECTING_STARTER);
            }
        }
        return GameStateUpdate.from(room, "POST_GAME_CHOICE", userId);
    }

    private GameStateUpdate handleSetMarker(GameRoom room, String userId, GameAction action) {
        Double d = action.getDouble("value");
        if (d == null) return GameStateUpdate.error(room.getRoomId(), "No value");
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Jugador no encontrado");
        player.setResourceCount(Math.min(5, Math.max(0, d.intValue())));
        return GameStateUpdate.from(room, "SET_MARKER", userId);
    }

    private void resetGameState(GameRoom room, boolean keepDecks) {
        room.getPlayers().values().forEach(player -> {
            player.getHand().clear();
            player.getField().clear();
            player.getLifeStack().clear();
            player.getTributeZone().clear();
            player.getDiscardPile().clear();
            player.setResourceCount(0);
            if (keepDecks && player.getDeckId() != null) {
                player.getDeck().clear();
                player.setDeck(new ArrayList<>(resolveDeck(player.getDeckId())));
                Collections.shuffle(player.getDeck());
            } else if (!keepDecks) {
                player.getDeck().clear();
            }
        });
        room.setSharedCounter(0);
        room.setCurrentTurnUserId(null);
        room.setCurrentPhase("MAIN");
        room.getStarterSelections().clear();
        room.getMulliganReady().clear();
        room.getPostGameChoices().clear();
    }

    private GameStateUpdate handleLifeHeal(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        if (player.getDeck().isEmpty()) return GameStateUpdate.error(room.getRoomId(), "Deck is empty");
        CardSlot card = player.getDeck().remove(0);
        card.setFaceDown(true);
        player.getLifeStack().add(0, card);
        return GameStateUpdate.from(room, "LIFE_HEAL", userId);
    }

    private GameStateUpdate handleLifeShuffle(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        Collections.shuffle(player.getLifeStack());
        return GameStateUpdate.from(room, "LIFE_SHUFFLE", userId);
    }

    private GameStateUpdate handleLifeAllDown(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        player.getLifeStack().forEach(c -> c.setFaceDown(true));
        return GameStateUpdate.from(room, "LIFE_ALL_DOWN", userId);
    }

    private GameStateUpdate handleLifeAllUp(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        player.getLifeStack().forEach(c -> c.setFaceDown(false));
        return GameStateUpdate.from(room, "LIFE_ALL_UP", userId);
    }

    private GameStateUpdate handleLifeRestart(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        if (!player.getLifeStack().isEmpty()) return GameStateUpdate.error(room.getRoomId(), "Life stack not empty");
        int count = Math.min(6, player.getDeck().size());
        for (int i = 0; i < count; i++) {
            CardSlot card = player.getDeck().remove(0);
            card.setFaceDown(true);
            player.getLifeStack().add(0, card);
        }
        return GameStateUpdate.from(room, "LIFE_RESTART", userId);
    }

    private GameStateUpdate handleDeckReorderTop(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");

        @SuppressWarnings("unchecked")
        List<String> instanceIds = (List<String>) action.getPayload().get("instanceIds");
        String position = action.getString("position");
        boolean shuffle = Boolean.TRUE.equals(action.getPayload().get("shuffle"));

        if (instanceIds == null || instanceIds.isEmpty())
            return GameStateUpdate.error(room.getRoomId(), "No cards specified");

        Map<String, CardSlot> byId = new LinkedHashMap<>();
        for (String id : instanceIds) {
            player.getDeck().stream()
                    .filter(c -> id.equals(c.getInstanceId()))
                    .findFirst()
                    .ifPresent(c -> byId.put(id, c));
        }
        player.getDeck().removeAll(byId.values());

        List<CardSlot> ordered = new ArrayList<>(byId.values());
        if (shuffle) Collections.shuffle(ordered);

        if ("bottom".equals(position)) {
            player.getDeck().addAll(ordered);
        } else {
            for (int i = ordered.size() - 1; i >= 0; i--) {
                player.getDeck().add(0, ordered.get(i));
            }
        }
        return GameStateUpdate.from(room, "DECK_REORDER_TOP", userId);
    }

    private GameStateUpdate handleZoneReorder(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");

        String zoneName = action.getString("zone");
        @SuppressWarnings("unchecked")
        List<String> instanceIds = (List<String>) action.getPayload().get("instanceIds");

        List<CardSlot> zone = getZone(player, zoneName);
        if (zone == null) return GameStateUpdate.error(room.getRoomId(), "Invalid zone");
        if (instanceIds == null) return GameStateUpdate.error(room.getRoomId(), "No order specified");

        Map<String, CardSlot> cardMap = new LinkedHashMap<>();
        for (CardSlot c : zone) cardMap.put(c.getInstanceId(), c);

        List<CardSlot> reordered = instanceIds.stream()
                .map(cardMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        zone.clear();
        zone.addAll(reordered);

        return GameStateUpdate.from(room, "ZONE_REORDER", userId);
    }

    private GameStateUpdate handleEnterEndPhase(GameRoom room, String userId) {
        if (!userId.equals(room.getCurrentTurnUserId()))
            return GameStateUpdate.error(room.getRoomId(), "Not your turn");
        int dir = getPlayerDirection(room, userId);
        if (dir * room.getSharedCounter() > 0) {
            room.setSharedCounter(0);
        }
        room.setCurrentPhase("END_PHASE");
        return GameStateUpdate.from(room, "ENTER_END_PHASE", userId);
    }

    private GameStateUpdate handleEndTurn(GameRoom room, String userId) {
        if (!userId.equals(room.getCurrentTurnUserId()))
            return GameStateUpdate.error(room.getRoomId(), "Not your turn");
        String opponentId = room.getOpponentId(userId);
        if (opponentId != null) {
            PlayerState opponent = room.getPlayer(opponentId);
            int newCount = Math.min(5, opponent.getResourceCount() + 1);
            opponent.setResourceCount(newCount);
            int dir = getPlayerDirection(room, opponentId);
            int next = room.getSharedCounter() + newCount * dir;
            room.setSharedCounter(Math.max(-10, Math.min(10, next)));
            opponent.getField().forEach(c -> c.setTapped(false));
            room.setCurrentTurnUserId(opponentId);
        }
        room.setCurrentPhase("MAIN");
        return GameStateUpdate.from(room, "END_TURN", userId);
    }

    private int getPlayerDirection(GameRoom room, String userId) {
        String firstId = room.getPlayers().keySet().iterator().next();
        return firstId.equals(userId) ? -1 : 1;
    }

    private GameStateUpdate handleGrantResource(GameRoom room, String userId) {
        String opponentId = room.getOpponentId(userId);
        if (opponentId == null) return GameStateUpdate.error(room.getRoomId(), "No opponent");
        PlayerState opponent = room.getPlayer(opponentId);
        int newCount = Math.min(5, opponent.getResourceCount() + 1);
        opponent.setResourceCount(newCount);
        int dir = getPlayerDirection(room, opponentId);
        int next = room.getSharedCounter() + newCount * dir;
        room.setSharedCounter(Math.max(-10, Math.min(10, next)));
        return GameStateUpdate.from(room, "GRANT_RESOURCE", userId);
    }

    private GameStateUpdate handleSetCounter(GameRoom room, String userId, GameAction action) {
        Double d = action.getDouble("value");
        if (d == null) return GameStateUpdate.error(room.getRoomId(), "No value");
        room.setSharedCounter(Math.max(-10, Math.min(10, d.intValue())));
        return GameStateUpdate.from(room, "SET_COUNTER", userId);
    }

    private GameStateUpdate handleUseResources(GameRoom room, String userId, GameAction action) {
        Double d = action.getDouble("amount");
        if (d == null) return GameStateUpdate.error(room.getRoomId(), "No amount");
        int next = room.getSharedCounter() + d.intValue();
        room.setSharedCounter(Math.max(-10, Math.min(10, next)));
        PlayerState player = room.getPlayer(userId);
        if (player != null) player.setResourceCount(Math.abs(d.intValue()));
        return GameStateUpdate.from(room, "USE_RESOURCES", userId);
    }

    private GameStateUpdate handleTapCard(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");

        String instanceId = action.getString("instanceId");
        String zoneName   = action.getString("zone");
        List<CardSlot> zone = getZone(player, zoneName);
        if (zone == null) return GameStateUpdate.error(room.getRoomId(), "Invalid zone");

        zone.stream()
                .filter(c -> instanceId.equals(c.getInstanceId()))
                .findFirst()
                .ifPresent(c -> c.setTapped(!c.isTapped()));

        return GameStateUpdate.from(room, "TAP_CARD", userId);
    }

    private GameStateUpdate handleDeckIncludeDiscard(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        List<CardSlot> discard = new ArrayList<>(player.getDiscardPile());
        Collections.shuffle(discard);
        discard.forEach(c -> c.setFaceDown(true));
        player.getDiscardPile().clear();
        player.getDeck().addAll(discard);
        return GameStateUpdate.from(room, "DECK_INCLUDE_DISCARD", userId);
    }

    private GameStateUpdate handleDeckIncludeTribute(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        List<CardSlot> tribute = new ArrayList<>(player.getTributeZone());
        Collections.shuffle(tribute);
        tribute.forEach(c -> c.setFaceDown(true));
        player.getTributeZone().clear();
        player.getDeck().addAll(tribute);
        return GameStateUpdate.from(room, "DECK_INCLUDE_TRIBUTE", userId);
    }

    // ── Field card attachment actions ────────────────────────────────────────────

    private GameStateUpdate handleModifyStrength(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        String instanceId = action.getString("instanceId");
        int delta = action.getInt("delta", 0);
        CardSlot card = player.getField().stream().filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (card == null) return GameStateUpdate.error(room.getRoomId(), "Card not found");
        card.setStrengthModifier(card.getStrengthModifier() + delta);
        return GameStateUpdate.from(room, "MODIFY_STRENGTH", userId);
    }

    private GameStateUpdate handleAddMaterial(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        String instanceId      = action.getString("instanceId");
        String fromZone        = action.getString("fromZone");
        String targetId        = action.getString("targetInstanceId");
        String attachChoice    = action.getString("attachmentChoice"); // all|resources_only|materials_only|discard_all

        List<CardSlot> from = getZone(player, fromZone);
        if (from == null) return GameStateUpdate.error(room.getRoomId(), "Invalid zone");
        CardSlot card = from.stream().filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (card == null) return GameStateUpdate.error(room.getRoomId(), "Card not found");
        CardSlot target = player.getField().stream().filter(c -> targetId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (target == null) return GameStateUpdate.error(room.getRoomId(), "Target not found");

        from.remove(card);

        if (attachChoice != null && (!card.getMaterials().isEmpty() || !card.getResources().isEmpty())) {
            switch (attachChoice) {
                case "resources_only" -> {
                    target.getResources().addAll(card.getResources());
                    card.getMaterials().forEach(m -> { m.setFaceDown(false); player.getDiscardPile().add(m); });
                }
                case "materials_only" -> {
                    target.getMaterials().addAll(card.getMaterials());
                    card.getResources().forEach(r -> { r.setFaceDown(false); player.getDiscardPile().add(r); });
                }
                case "all" -> {
                    target.getMaterials().addAll(card.getMaterials());
                    target.getResources().addAll(card.getResources());
                }
                default -> {
                    card.getMaterials().forEach(m -> { m.setFaceDown(false); player.getDiscardPile().add(m); });
                    card.getResources().forEach(r -> { r.setFaceDown(false); player.getDiscardPile().add(r); });
                }
            }
        }
        card.setMaterials(new ArrayList<>());
        card.setResources(new ArrayList<>());
        card.setFaceDown(false);
        card.setTapped(false);
        card.setX(null);
        card.setY(null);
        target.getMaterials().add(card);
        return GameStateUpdate.from(room, "ADD_MATERIAL", userId);
    }

    private GameStateUpdate handleAddResource(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        String instanceId = action.getString("instanceId");
        String fromZone   = action.getString("fromZone");
        String targetId   = action.getString("targetInstanceId");

        List<CardSlot> from = getZone(player, fromZone);
        if (from == null) return GameStateUpdate.error(room.getRoomId(), "Invalid zone");
        CardSlot card = from.stream().filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (card == null) return GameStateUpdate.error(room.getRoomId(), "Card not found");
        CardSlot target = player.getField().stream().filter(c -> targetId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (target == null) return GameStateUpdate.error(room.getRoomId(), "Target not found");

        from.remove(card);
        // If the card was on field, flatten its own attachments to discard
        if ("field".equals(fromZone)) {
            card.getMaterials().forEach(m -> { m.setFaceDown(false); player.getDiscardPile().add(m); });
            card.getResources().forEach(r -> { r.setFaceDown(false); player.getDiscardPile().add(r); });
            card.setMaterials(new ArrayList<>());
            card.setResources(new ArrayList<>());
            card.setStrengthModifier(0);
        }
        card.setFaceDown(false);
        card.setTapped(false);
        card.setX(null);
        card.setY(null);
        target.getResources().add(card);
        return GameStateUpdate.from(room, "ADD_RESOURCE", userId);
    }

    private GameStateUpdate handleMoveFromAttachment(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        String instanceId      = action.getString("instanceId");
        String attachmentOf    = action.getString("attachmentOf");
        String attachmentType  = action.getString("attachmentType"); // material | resource
        String toZone          = action.getString("toZone");

        CardSlot parent = player.getField().stream().filter(c -> attachmentOf.equals(c.getInstanceId())).findFirst().orElse(null);
        if (parent == null) return GameStateUpdate.error(room.getRoomId(), "Parent not found");

        List<CardSlot> list = "material".equals(attachmentType) ? parent.getMaterials() : parent.getResources();
        CardSlot card = list.stream().filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (card == null) return GameStateUpdate.error(room.getRoomId(), "Card not found");

        list.remove(card);
        card.setFaceDown(action.getBoolean("faceDown", false));
        card.setTapped(false);
        List<CardSlot> dest = getZone(player, toZone);
        if (dest == null) return GameStateUpdate.error(room.getRoomId(), "Invalid zone");
        if ("field".equals(toZone)) {
            card.setX(parent.getX() != null ? parent.getX() + 5 : 50.0);
            card.setY(parent.getY() != null ? parent.getY() + 5 : 50.0);
        }
        if ("top".equals(action.getString("position"))) {
            dest.add(0, card);
        } else {
            dest.add(card);
        }
        return GameStateUpdate.from(room, "MOVE_FROM_ATTACHMENT", userId);
    }

    private GameStateUpdate handleSpecialSummon(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        String summonedId  = action.getString("summonedId");
        String fromZone    = action.getString("fromZone");
        String summonerId  = action.getString("summonerId");
        String summonType  = action.getString("summonType");

        List<CardSlot> source = getZone(player, fromZone);
        if (source == null) return GameStateUpdate.error(room.getRoomId(), "Invalid zone");
        CardSlot summoned = source.stream().filter(c -> summonedId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (summoned == null) return GameStateUpdate.error(room.getRoomId(), "Summoned card not found");
        CardSlot summoner = player.getField().stream().filter(c -> summonerId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (summoner == null) return GameStateUpdate.error(room.getRoomId(), "Summoner not found");

        source.remove(summoned);
        player.getField().remove(summoner);

        summoned.setFaceDown(false);
        summoned.setX(summoner.getX());
        summoned.setY(summoner.getY());

        switch (summonType) {
            case "materialization" -> {
                summoned.setTapped(summoner.isTapped());
                summoned.setStrengthModifier(summoner.getStrengthModifier());
                summoned.getMaterials().addAll(0, summoner.getMaterials());
                summoned.getResources().addAll(0, summoner.getResources());
                summoner.setMaterials(new ArrayList<>());
                summoner.setResources(new ArrayList<>());
                summoner.setTapped(false); summoner.setX(null); summoner.setY(null);
                summoned.getMaterials().add(0, summoner);
            }
            case "evolution" -> {
                summoned.setTapped(summoner.isTapped());
                summoned.setStrengthModifier(summoner.getStrengthModifier());
                summoned.getMaterials().addAll(0, summoner.getMaterials());
                summoned.getResources().addAll(0, summoner.getResources());
                summoner.setMaterials(new ArrayList<>());
                summoner.setResources(new ArrayList<>());
                summoner.setTapped(false); summoner.setX(null); summoner.setY(null);
                summoned.getResources().add(0, summoner);
            }
            case "ritual" -> {
                summoner.getMaterials().forEach(m -> { m.setFaceDown(false); player.getDiscardPile().add(m); });
                summoner.getResources().forEach(r -> { r.setFaceDown(false); player.getDiscardPile().add(r); });
                summoner.setMaterials(new ArrayList<>());
                summoner.setResources(new ArrayList<>());
                summoner.setFaceDown(false); summoner.setTapped(false); summoner.setX(null); summoner.setY(null);
                player.getTributeZone().add(summoner);
            }
            case "ascenso" -> {
                summoned.setTapped(summoner.isTapped());
                summoned.setStrengthModifier(summoner.getStrengthModifier());
                summoned.getMaterials().addAll(0, summoner.getMaterials());
                summoned.getResources().addAll(0, summoner.getResources());
                summoner.setMaterials(new ArrayList<>());
                summoner.setResources(new ArrayList<>());
                summoner.setFaceDown(false); summoner.setTapped(false); summoner.setX(null); summoner.setY(null);
                player.getTributeZone().add(summoner);
            }
        }

        summoned.setFaceDown(false);
        player.getField().add(summoned);
        return GameStateUpdate.from(room, "SPECIAL_SUMMON", userId);
    }

    private GameStateUpdate handleSwapWithMain(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not found");
        String instanceId     = action.getString("instanceId");
        String attachmentType = action.getString("attachmentType");
        String parentId       = action.getString("parentId");

        CardSlot parent = player.getField().stream()
                .filter(c -> parentId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (parent == null) return GameStateUpdate.error(room.getRoomId(), "Main card not found");

        List<CardSlot> list = "material".equals(attachmentType) ? parent.getMaterials() : parent.getResources();
        CardSlot attachment = list.stream()
                .filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (attachment == null) return GameStateUpdate.error(room.getRoomId(), "Attachment not found");

        // Remove attachment from its list
        list.remove(attachment);

        // Attachment inherits parent's field state
        attachment.setX(parent.getX());
        attachment.setY(parent.getY());
        attachment.setTapped(parent.isTapped());
        attachment.setFaceDown(parent.isFaceDown());
        attachment.setStrengthModifier(parent.getStrengthModifier());

        // Collect parent's current attachments (parent will go back as attachment)
        List<CardSlot> inheritedMaterials = new ArrayList<>(parent.getMaterials());
        List<CardSlot> inheritedResources = new ArrayList<>(parent.getResources());
        // Clear parent before it becomes an attachment
        parent.setMaterials(new ArrayList<>());
        parent.setResources(new ArrayList<>());
        parent.setX(null); parent.setY(null);
        parent.setTapped(false); parent.setFaceDown(false); parent.setStrengthModifier(0);

        // Parent goes into the same attachment type it came from
        if ("material".equals(attachmentType)) {
            inheritedMaterials.add(parent);
            attachment.setMaterials(inheritedMaterials);
            attachment.setResources(inheritedResources);
        } else {
            inheritedResources.add(parent);
            attachment.setMaterials(inheritedMaterials);
            attachment.setResources(inheritedResources);
        }

        // Replace parent with attachment on field
        int idx = player.getField().indexOf(parent);
        player.getField().remove(parent);
        if (idx >= 0) player.getField().add(idx, attachment);
        else player.getField().add(attachment);

        return GameStateUpdate.from(room, "SWAP_WITH_MAIN", userId);
    }

    private GameStateUpdate handleTargetCard(GameRoom room, String userId, GameAction action) {
        String targetInstanceId = action.getString("targetInstanceId");
        String sourceInstanceId = action.getString("sourceInstanceId");
        GameStateUpdate update = GameStateUpdate.from(room, "TARGET_CARD", userId);
        update.setTargetedInstanceId(targetInstanceId);
        update.setTargetingSourceInstanceId(sourceInstanceId);
        return update;
    }

    private GameStateUpdate handleReorderAttachment(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");

        String parentId       = action.getString("parentId");
        String attachmentType = action.getString("attachmentType");
        @SuppressWarnings("unchecked")
        List<String> instanceIds = (List<String>) action.getPayload().get("instanceIds");
        if (instanceIds == null) return GameStateUpdate.error(room.getRoomId(), "No order specified");

        CardSlot parent = player.getField().stream()
                .filter(c -> parentId.equals(c.getInstanceId()))
                .findFirst().orElse(null);
        if (parent == null) return GameStateUpdate.error(room.getRoomId(), "Card not found");

        List<CardSlot> attachments = "material".equals(attachmentType)
                ? parent.getMaterials() : parent.getResources();

        Map<String, CardSlot> map = new LinkedHashMap<>();
        for (CardSlot c : attachments) map.put(c.getInstanceId(), c);

        List<CardSlot> reordered = instanceIds.stream()
                .map(map::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        attachments.clear();
        attachments.addAll(reordered);

        return GameStateUpdate.from(room, "REORDER_ATTACHMENT", userId);
    }

    private GameStateUpdate handleReclassifyAttachment(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");

        String parentId   = action.getString("parentId");
        String instanceId = action.getString("instanceId");
        String fromType   = action.getString("fromType"); // "material" | "resource"

        CardSlot parent = player.getField().stream()
                .filter(c -> parentId.equals(c.getInstanceId()))
                .findFirst().orElse(null);
        if (parent == null) return GameStateUpdate.error(room.getRoomId(), "Parent card not found");

        List<CardSlot> fromList = "material".equals(fromType) ? parent.getMaterials() : parent.getResources();
        List<CardSlot> toList   = "material".equals(fromType) ? parent.getResources() : parent.getMaterials();

        CardSlot card = fromList.stream()
                .filter(c -> instanceId.equals(c.getInstanceId()))
                .findFirst().orElse(null);
        if (card == null) return GameStateUpdate.error(room.getRoomId(), "Card not found in attachment");

        fromList.remove(card);
        toList.add(card);

        return GameStateUpdate.from(room, "RECLASSIFY_ATTACHMENT", userId);
    }

    private List<CardSlot> getZone(PlayerState player, String zoneName) {
        if (zoneName == null) return null;
        return switch (zoneName) {
            case "hand"        -> player.getHand();
            case "deck"        -> player.getDeck();
            case "lifeStack"   -> player.getLifeStack();
            case "tributeZone" -> player.getTributeZone();
            case "discardPile" -> player.getDiscardPile();
            case "field"       -> player.getField();
            default            -> null;
        };
    }

    private List<CardSlot> resolveDeck(String deckId) {
        Optional<DeckList> deckOpt = deckListRepo.findById(deckId);
        if (deckOpt.isEmpty()) return new ArrayList<>();

        String baseUrl = r2PublicUrl.stripTrailing();
        return new ArrayList<>(deckOpt.get().getCards().stream()
                .map(driveFileId -> {
                    CardSlot slot = new CardSlot();
                    slot.setInstanceId(UUID.randomUUID().toString());
                    slot.setCardId(driveFileId);
                    slot.setImageUrl(baseUrl + "/cards/" + driveFileId + ".jpg");
                    slot.setFaceDown(true);
                    driveCardRepo.findById(driveFileId).ifPresent(dc -> {
                        List<Card> matches = cardRepo.findByCardNameIgnoreCaseAndEdition(dc.getName(), dc.getEdition());
                        if (!matches.isEmpty()) {
                            Card card = matches.get(0);
                            slot.setCardName(card.getCardName());
                            slot.setCardType(card.getCardType());
                            slot.setStrength(card.getStrength());
                            slot.setSpecialSummonKind(card.getSpecialSummonKind());
                        }
                    });
                    return slot;
                })
                .toList());
    }
}
