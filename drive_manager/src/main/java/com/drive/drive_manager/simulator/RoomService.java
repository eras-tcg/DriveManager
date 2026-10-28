package com.drive.drive_manager.simulator;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.repository.DeckListRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.simulator.engine.EffectEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class RoomService {

    private final ConcurrentHashMap<String, GameRoom> rooms = new ConcurrentHashMap<>();

    // STOMP sessionId -> [roomId, userId], so a SessionDisconnectEvent (browser
    // closed / network drop) can be resolved back to a player in a room.
    private final ConcurrentHashMap<String, String[]> sessionRegistry = new ConcurrentHashMap<>();

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
        GameStateUpdate update = apply(roomId, userId, action);
        // Errors go to the whole room topic: tag who caused them so only that player is shown the message
        if (update != null && update.getError() != null && update.getActorId() == null) update.setActorId(userId);
        return update;
    }

    private GameStateUpdate apply(String roomId, String userId, GameAction action) {
        GameRoom room = rooms.get(roomId);
        if (room == null) return GameStateUpdate.error(roomId, "Room not found");

        // Closed game state (rule 7.2): while a chain or a choice is pending, only answering it (and leaving
        // or conceding) is allowed. The board's manual actions wait until the effect is done.
        if (room.isEngineBusy() && !ALLOWED_WHILE_RESOLVING.contains(action.getType())) {
            return GameStateUpdate.error(roomId, "Hay un efecto resolviéndose: espera a que termine");
        }

        GameStateUpdate update = switch (action.getType()) {
            case "NORMAL_SUMMON"        -> handleNormalSummon(room, userId, action);
            case "RESOLVE_PROMPT"       -> handleResolvePrompt(room, userId, action);
            case "DESTROY_CARD"         -> handleDestroyCard(room, userId, action);
            case "SACRIFICE_CARD"       -> handleSacrificeCard(room, userId, action);
            case "ATTACK"               -> handleAttack(room, userId, action);
            case "PLAY_CARD"            -> handlePlayCard(room, userId, action);
            case "RITUAL_SUMMON"        -> handleRitualSummon(room, userId, action);
            case "ACTIVATE_EFFECT"      -> handleActivateEffect(room, userId, action);
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
            case "REVEAL_TO_HAND"               -> handleRevealToHand(room, userId, action);
            case "REFRESH_CARD_METADATA"        -> handleRefreshCardMetadata(room, userId, action);
            case "REMOVE_CARD"                  -> handleRemoveCard(room, userId, action);
            case "RESTORE_CARD"                 -> handleRestoreCard(room, userId, action);
            case "CLOSE_ROOM"                   -> handleCloseRoom(room, userId);
            case "EFFECT_SUMMON_RESOLVE"        -> handleEffectSummonResolve(room, userId, action);
            case "VIEW_DECK"                    -> handleViewDeck(room, userId, action);
            case "VIEW_LIFE_STACK"              -> handleViewLifeStack(room, userId);
            case "VIEW_LIFE_CARD"               -> handleViewLifeCard(room, userId);
            case "BULK_TO_DECK_BOTTOM"           -> handleBulkToDeckBottom(room, userId, action);
            default                             -> GameStateUpdate.error(roomId, "Unknown action: " + action.getType());
        };

        if (update != null && update.getError() == null) {
            recordLog(room, update);
            update.setEventLog(new ArrayList<>(room.getEventLog()));
        }
        return update;
    }

    private static final Set<String> ALLOWED_WHILE_RESOLVING = Set.of(
            "RESOLVE_PROMPT", "JOIN_ROOM", "SURRENDER", "FORFEIT", "CLOSE_ROOM", "POST_GAME_CHOICE", "REFRESH_CARD_METADATA");

    /** Taking a creature from the hand to the board without an effect is a normal summon (rules engine). */
    private GameStateUpdate handleNormalSummon(GameRoom room, String userId, GameAction action) {
        String error = EffectEngine.normalSummon(room, userId, action.getString("instanceId"), action.getDouble("x"), action.getDouble("y"));
        if (error != null) return GameStateUpdate.error(room.getRoomId(), error);
        return GameStateUpdate.from(room, "NORMAL_SUMMON", userId);
    }

    /** The player's choice for the effect step the engine is waiting on. */
    private GameStateUpdate handleResolvePrompt(GameRoom room, String userId, GameAction action) {
        String error = EffectEngine.answer(room, userId, action.getString("promptId"), action.getStringList("chosen"), action.getStringList("order"));
        if (error != null) return GameStateUpdate.error(room.getRoomId(), error);
        // The end phase's move up was answered: the turn that was being ended ends now
        if (room.isEndTurnPending() && !room.isEngineBusy()) {
            room.setEndTurnPending(false);
            return handleEndTurn(room, room.getCurrentTurnUserId());
        }
        return GameStateUpdate.from(room, "RESOLVE_PROMPT", userId);
    }

    /** Destroys a card on the field (to its owner's Descarte); its "En Destrucción" effects trigger. */
    private GameStateUpdate handleDestroyCard(GameRoom room, String userId, GameAction action) {
        String error = EffectEngine.destroyByPlayer(room, userId, action.getString("targetPlayerId"), action.getString("instanceId"));
        if (error != null) return GameStateUpdate.error(room.getRoomId(), error);
        return GameStateUpdate.from(room, "DESTROY_CARD", userId);
    }

    /** Sacrifices one of your cards on the field (destroyed, to your Tributos). */
    private GameStateUpdate handleSacrificeCard(GameRoom room, String userId, GameAction action) {
        String error = EffectEngine.sacrificeByPlayer(room, userId, action.getString("instanceId"));
        if (error != null) return GameStateUpdate.error(room.getRoomId(), error);
        return GameStateUpdate.from(room, "SACRIFICE_CARD", userId);
    }

    /** Declares an attack (rule 10): the engine runs the combat. targetInstanceId null = the opponent. */
    private GameStateUpdate handleAttack(GameRoom room, String userId, GameAction action) {
        String error = EffectEngine.attackByPlayer(room, userId, action.getString("instanceId"), action.getString("targetInstanceId"));
        if (error != null) return GameStateUpdate.error(room.getRoomId(), error);
        return GameStateUpdate.from(room, "ATTACK", userId);
    }

    /** Plays a utility or structure from the hand: pays its cost and fires its "En Juego" effects. */
    private GameStateUpdate handlePlayCard(GameRoom room, String userId, GameAction action) {
        String error = EffectEngine.playByPlayer(room, userId, action.getString("instanceId"), action.getDouble("x"), action.getDouble("y"));
        if (error != null) return GameStateUpdate.error(room.getRoomId(), error);
        return GameStateUpdate.from(room, "PLAY_CARD", userId);
    }

    private GameStateUpdate handleRitualSummon(GameRoom room, String userId, GameAction action) {
        String error = EffectEngine.ritualByPlayer(room, userId, action.getString("instanceId"), action.getString("summonerId"));
        if (error != null) return GameStateUpdate.error(room.getRoomId(), error);
        return GameStateUpdate.from(room, "RITUAL_SUMMON", userId);
    }

    private GameStateUpdate handleActivateEffect(GameRoom room, String userId, GameAction action) {
        String error = EffectEngine.activateByPlayer(room, userId, action.getString("instanceId"), action.getString("scriptId"));
        if (error != null) return GameStateUpdate.error(room.getRoomId(), error);
        return GameStateUpdate.from(room, "ACTIVATE_EFFECT", userId);
    }

    /** Rule 3.0.1: who starts; the other player gets the "ventaja del iniciador" (rule 6.2.1.4). */
    private void setFirstPlayer(GameRoom room, String firstPlayerId) {
        room.setCurrentTurnUserId(firstPlayerId);
        room.setFirstPlayerId(firstPlayerId);
        room.setStarterAdvantageUserId(room.getOpponentId(firstPlayerId));
    }

    private void recordLog(GameRoom room, GameStateUpdate update) {
        if (update.getLogEntry() == null) return;
        room.getEventLog().add(new GameLogEntry(System.currentTimeMillis(), update.getLogEntry()));
        if (room.getEventLog().size() > 300) room.getEventLog().remove(0);
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
                existing.setDeck(new ArrayList<>(slots));
                existing.setOriginalDeck(deepCopySlots(slots));
            }
            return GameStateUpdate.from(room, "RECONNECT", userId);
        }
        if (username == null) username = userId;

        PlayerState player = new PlayerState(userId, username);
        player.setConnected(true);

        if (deckId != null) {
            player.setDeckId(deckId);
            List<CardSlot> slots = resolveDeck(deckId);
            player.setDeck(new ArrayList<>(slots));
            player.setOriginalDeck(deepCopySlots(slots));
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

        GameStateUpdate update = GameStateUpdate.from(room, "DRAW_CARD", userId);
        update.setLogEntry(player.getUsername() + " robó una carta");
        return update;
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
        boolean wasPublic = !card.isFaceDown();

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

        if ("discardPile".equals(toZone) || "tributeZone".equals(toZone)) {
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
        if ("hand".equals(fromZone) && "lifeStack".equals(toZone)) {
            card.setFaceDown(true);
        }
        Object forceFaceDown = action.getPayload().get("faceDown");
        if (Boolean.TRUE.equals(forceFaceDown)) {
            card.setFaceDown(true);
        }
        // Hand cards are always visible to their owner — hiding from the
        // OPPONENT is already handled unconditionally by zone type (hand is
        // never shown for isOpponent), so faceDown must never stick here even
        // if a "hide from opponent" send explicitly requested it above.
        if ("hand".equals(toZone)) {
            card.setFaceDown(false);
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

        GameStateUpdate update = GameStateUpdate.from(room, "MOVE_CARD", userId);
        update.setLogEntry(buildMoveLogEntry(room, player, card, fromZone, toZone, toPosition, wasPublic));
        return update;
    }

    private String buildMoveLogEntry(GameRoom room, PlayerState player, CardSlot card, String fromZone, String toZone, String toPosition, boolean wasPublic) {
        String who   = player.getUsername();
        String label = cardLabel(card);
        String from  = zoneLabel(fromZone);

        switch (toZone) {
            case "field": {
                // A creature entering the field from a non-field zone while the game is
                // in progress always triggers the effect-summon draw/no-draw prompt
                // (see FieldZone.vue's effectSummonPending) — so its placement is only
                // logged once that decision resolves, via handleEffectSummonResolve.
                boolean pendingDecision = "creature".equalsIgnoreCase(card.getCardType())
                        && !"field".equals(fromZone)
                        && room.getStatus() == GameRoom.RoomStatus.IN_PROGRESS;
                if (pendingDecision) return null;
                String suffix = "creature".equalsIgnoreCase(card.getCardType()) && card.isFaceDown() ? " (set)" : "";
                return who + " colocó/invocó " + label + " desde " + from + suffix;
            }
            case "lifeStack": {
                String pos = "top".equals(toPosition) ? "tope" : "fondo";
                return who + " añadió " + (card.isFaceDown() ? "una carta oculta" : label) + " al " + pos + " de la vida desde " + from;
            }
            case "hand": {
                boolean fromPublicZone = "field".equals(fromZone) || "tributeZone".equals(fromZone) || "discardPile".equals(fromZone);
                String what = (fromPublicZone && !card.isFaceDown()) ? label : "una carta";
                return who + " añadió " + what + " a la mano desde " + from;
            }
            case "tributeZone":
                return who + " envió " + (card.isFaceDown() ? "una carta" : label) + " a Tributo desde " + from;
            case "discardPile":
                return who + " envió " + (card.isFaceDown() ? "una carta" : label) + " a Descarte desde " + from;
            case "deck": {
                String pos = "top".equals(toPosition) ? "tope" : "fondo";
                String what = wasPublic ? label : "una carta oculta";
                return who + " envió " + what + " al " + pos + " del mazo desde " + from;
            }
            default:
                return null;
        }
    }

    private String cardLabel(CardSlot card) {
        return card.getCardName() != null ? card.getCardName() : "Carta";
    }

    private String zoneLabel(String zone) {
        if (zone == null) return "zona desconocida";
        return switch (zone) {
            case "hand"        -> "la mano";
            case "deck"        -> "el mazo";
            case "field"       -> "el campo";
            case "lifeStack"   -> "la vida";
            case "tributeZone" -> "tributo";
            case "discardPile" -> "descarte";
            default            -> zone;
        };
    }

    private GameStateUpdate handleFlip(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");

        String instanceId = action.getString("instanceId");
        String zoneName   = action.getString("zone");
        List<CardSlot> zone = getZone(player, zoneName);
        if (zone == null) return GameStateUpdate.error(room.getRoomId(), "Invalid zone");

        GameStateUpdate update = GameStateUpdate.from(room, "FLIP_CARD", userId);
        zone.stream()
                .filter(c -> instanceId.equals(c.getInstanceId()))
                .findFirst()
                .ifPresent(c -> {
                    boolean nowFaceDown = !c.isFaceDown();
                    c.setFaceDown(nowFaceDown);
                    if ("field".equals(zoneName) && nowFaceDown) {
                        update.setLogEntry(player.getUsername() + " puso boca abajo a " + cardLabel(c) + " en el campo");
                    } else if ("lifeStack".equals(zoneName) && !nowFaceDown) {
                        update.setLogEntry(player.getUsername() + " reveló " + cardLabel(c) + " en su vida");
                    } else if ("lifeStack".equals(zoneName)) {
                        update.setLogEntry(player.getUsername() + " ocultó a " + cardLabel(c) + " en su vida");
                    } else if ("tributeZone".equals(zoneName) || "discardPile".equals(zoneName)) {
                        String zoneWord = "tributeZone".equals(zoneName) ? "Tributo" : "Descarte";
                        update.setLogEntry(player.getUsername() + (nowFaceDown ? " ocultó a " : " reveló a ")
                                + cardLabel(c) + " en " + zoneWord);
                    }
                });

        return update;
    }

    private GameStateUpdate handleShuffle(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        Collections.shuffle(player.getDeck());
        GameStateUpdate update = GameStateUpdate.from(room, "SHUFFLE_DECK", userId);
        update.setLogEntry(player.getUsername() + " barajó su mazo");
        return update;
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
            clearTurnKeywords(room);
            EffectEngine.offerAdvance(room, opponentId, true);
        }
        return GameStateUpdate.from(room, "PASS_TURN", userId);
    }

    private GameStateUpdate handleForfeit(GameRoom room, String userId) {
        room.setStatus(GameRoom.RoomStatus.FINISHED);
        return GameStateUpdate.from(room, "FORFEIT", userId);
    }

    public void registerSession(String sessionId, String roomId, String userId) {
        if (sessionId == null) return;
        sessionRegistry.put(sessionId, new String[]{roomId, userId});
    }

    /** Called from GameDisconnectListener when a STOMP session drops (browser closed, network lost). */
    public GameStateUpdate handleSessionDisconnect(String sessionId) {
        String[] info = sessionRegistry.remove(sessionId);
        if (info == null) return null;
        String roomId = info[0];
        String userId = info[1];

        GameRoom room = rooms.get(roomId);
        if (room == null) return null;
        PlayerState player = room.getPlayer(userId);
        if (player == null) return null;

        player.setConnected(false);
        boolean allDisconnected = room.getPlayers().values().stream().noneMatch(PlayerState::isConnected);
        if (allDisconnected) {
            rooms.remove(roomId);
            return null;
        }
        GameStateUpdate update = GameStateUpdate.from(room, "PLAYER_DISCONNECTED", userId);
        update.setEventLog(new ArrayList<>(room.getEventLog()));
        return update;
    }

    private GameStateUpdate handleCloseRoom(GameRoom room, String userId) {
        room.setStatus(GameRoom.RoomStatus.FINISHED);
        GameStateUpdate update = GameStateUpdate.from(room, "ROOM_CLOSED", userId);
        rooms.remove(room.getRoomId());
        return update;
    }

    /**
     * A creature dropped onto the field from a non-hand zone only becomes an
     * "effect summon" once the player answers the draw/no-draw prompt (see
     * FieldZone.vue's effectSummonPending flow) — not at drop time.
     */
    private GameStateUpdate handleEffectSummonResolve(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        String instanceId = action.getString("instanceId");
        String fromZone   = action.getString("fromZone");
        boolean draw = action.getBoolean("draw", false);

        CardSlot card = player.getField().stream()
                .filter(c -> instanceId != null && instanceId.equals(c.getInstanceId()))
                .findFirst().orElse(null);
        String label = card != null ? cardLabel(card) : "una carta";

        String logText;
        if (draw) {
            boolean actuallyDrew = false;
            if (!player.getDeck().isEmpty()) {
                CardSlot drawn = player.getDeck().remove(0);
                drawn.setFaceDown(false);
                player.getHand().add(drawn);
                actuallyDrew = true;
            }
            logText = player.getUsername() + " invocó por efecto a " + label + (actuallyDrew ? " y robó una carta" : "");
        } else {
            // Declined the effect-summon draw — this is just a plain summon/set,
            // using the same phrasing the immediate MOVE_CARD log would have used
            // had it not been deferred (see buildMoveLogEntry's "field" case).
            String from   = zoneLabel(fromZone);
            String suffix = (card != null && "creature".equalsIgnoreCase(card.getCardType()) && card.isFaceDown()) ? " (set)" : "";
            logText = player.getUsername() + " colocó/invocó " + label + " desde " + from + suffix;
        }

        GameStateUpdate update = GameStateUpdate.from(room, "EFFECT_SUMMON_RESOLVE", userId);
        update.setLogEntry(logText);
        return update;
    }

    /** Broadcast-only: the action of peeking is public, the cards seen are not. */
    private GameStateUpdate handleViewDeck(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        String position = action.getString("position");
        int n = action.getInt("n", 1);
        GameStateUpdate update = GameStateUpdate.from(room, "VIEW_DECK", userId);
        String pos = "bottom".equals(position) ? "fondo" : "tope";
        update.setLogEntry(player.getUsername() + " vio el " + pos + " " + n + " del mazo");
        return update;
    }

    /** Broadcast-only: the action of viewing the life pile is public, hidden cards stay hidden. */
    private GameStateUpdate handleViewLifeStack(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        GameStateUpdate update = GameStateUpdate.from(room, "VIEW_LIFE_STACK", userId);
        update.setLogEntry(player.getUsername() + " vio su pila de vida");
        return update;
    }

    /** Broadcast-only: the peek action is public, the specific card peeked is not. */
    private GameStateUpdate handleViewLifeCard(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        GameStateUpdate update = GameStateUpdate.from(room, "VIEW_LIFE_CARD", userId);
        update.setLogEntry(player.getUsername() + " vio una carta oculta de su vida");
        return update;
    }

    /**
     * Bulk-sends a selection of cards from any zone to the bottom of the deck in
     * ONE atomic server-side operation — replaces the previous approach of firing
     * one MOVE_CARD per selected card back-to-back from the client (which raced
     * client-side optimistic mutation against async per-card server responses).
     * `instanceIds` must already be in the desired base order (the zone-list
     * order); this handler only decides whether to additionally shuffle it.
     */
    private GameStateUpdate handleBulkToDeckBottom(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        String zoneName = action.getString("zone");
        List<CardSlot> zone = getZone(player, zoneName);
        if (zone == null) return GameStateUpdate.error(room.getRoomId(), "Invalid zone");

        @SuppressWarnings("unchecked")
        List<String> instanceIds = (List<String>) action.getPayload().get("instanceIds");
        boolean random = action.getBoolean("random", false);
        if (instanceIds == null || instanceIds.isEmpty())
            return GameStateUpdate.error(room.getRoomId(), "No cards specified");

        Map<String, CardSlot> byId = new LinkedHashMap<>();
        for (String id : instanceIds) {
            zone.stream().filter(c -> id.equals(c.getInstanceId())).findFirst().ifPresent(c -> byId.put(id, c));
        }
        if (byId.isEmpty()) return GameStateUpdate.error(room.getRoomId(), "Cards not found");
        zone.removeAll(byId.values());

        List<CardSlot> ordered = new ArrayList<>(byId.values());
        if (random) Collections.shuffle(ordered);
        ordered.forEach(c -> { c.setFaceDown(true); c.setTapped(false); c.setX(null); c.setY(null); });
        player.getDeck().addAll(ordered);

        GameStateUpdate update = GameStateUpdate.from(room, "BULK_TO_DECK_BOTTOM", userId);
        update.setLogEntry(player.getUsername() + " envió " + ordered.size() + " carta(s) desde " + zoneLabel(zoneName)
                + " al fondo del mazo en orden " + (random ? "aleatorio" : "de la lista"));
        return update;
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
        setFirstPlayer(room, firstPlayerId);
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
        if (!starter.isStarter()) return GameStateUpdate.error(room.getRoomId(), "Esa carta no es un starter válido");
        player.getDeck().remove(starter);
        // Rule 3.0.2: set aside and public until the mulligan is done (kept on the field meanwhile)
        starter.setFaceDown(false);
        starter.setX(50.0);
        starter.setY(75.0);
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
        // Rule 3.0.6: after the mulligan the starter joins the hand (6 cards)
        String starterId = room.getStarterSelections().get(userId);
        player.getField().stream().filter(c -> c.getInstanceId().equals(starterId)).findFirst().ifPresent(starter -> {
            player.getField().remove(starter);
            starter.setX(null);
            starter.setY(null);
            player.getHand().add(starter);
        });
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
                // A rematch starts right away: who goes first is drawn again (rule 3.0.1)
                List<String> ids = new ArrayList<>(room.getPlayers().keySet());
                setFirstPlayer(room, ids.get(new java.util.Random().nextInt(ids.size())));
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
        int prev = player.getResourceCount();
        player.setResourceCount(Math.min(5, Math.max(0, d.intValue())));
        GameStateUpdate update = GameStateUpdate.from(room, "SET_MARKER", userId);
        update.setLogEntry(player.getUsername() + " ajustó su marcador de recursos de " + prev + " a " + player.getResourceCount());
        return update;
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
        room.setFirstPlayerId(null);
        room.setStarterAdvantageUserId(null);
        room.setEndTurnPending(false);
        room.setCurrentPhase("MAIN");
        room.getStarterSelections().clear();
        room.getMulliganReady().clear();
        room.getPostGameChoices().clear();
        // A new game starts with an empty chain, no pending choice and a fresh timeline
        room.getChain().clear();
        room.setPrompt(null);
        room.getTriggerQueue().clear();
        room.getPendingEvents().clear();
        room.setCombat(null);
        room.getUsedThisTurn().clear();
        room.getTimeline().clear();
    }

    private GameStateUpdate handleLifeHeal(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        if (player.getDeck().isEmpty()) return GameStateUpdate.error(room.getRoomId(), "Deck is empty");
        CardSlot card = player.getDeck().remove(0);
        card.setFaceDown(true);
        player.getLifeStack().add(0, card);
        GameStateUpdate update = GameStateUpdate.from(room, "LIFE_HEAL", userId);
        update.setLogEntry(player.getUsername() + " curó vida (tope del mazo)");
        return update;
    }

    private GameStateUpdate handleLifeShuffle(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        Collections.shuffle(player.getLifeStack());
        GameStateUpdate update = GameStateUpdate.from(room, "LIFE_SHUFFLE", userId);
        update.setLogEntry(player.getUsername() + " barajó su vida");
        return update;
    }

    private GameStateUpdate handleLifeAllDown(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        player.getLifeStack().forEach(c -> c.setFaceDown(true));
        GameStateUpdate update = GameStateUpdate.from(room, "LIFE_ALL_DOWN", userId);
        update.setLogEntry(player.getUsername() + " ocultó toda su vida");
        return update;
    }

    private GameStateUpdate handleLifeAllUp(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        player.getLifeStack().forEach(c -> c.setFaceDown(false));
        GameStateUpdate update = GameStateUpdate.from(room, "LIFE_ALL_UP", userId);
        update.setLogEntry(player.getUsername() + " reveló toda su vida");
        return update;
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
        GameStateUpdate update = GameStateUpdate.from(room, "LIFE_RESTART", userId);
        update.setLogEntry(player.getUsername() + " reinició su vida (+" + count + " del mazo)");
        return update;
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
        GameStateUpdate update = GameStateUpdate.from(room, "DECK_REORDER_TOP", userId);
        String pos = "bottom".equals(position) ? "fondo" : "tope";
        update.setLogEntry(player.getUsername() + " devolvió " + ordered.size() + " carta(s) al " + pos + " del mazo");
        return update;
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
        EffectEngine.offerAdvance(room, userId, false);
        return GameStateUpdate.from(room, "ENTER_END_PHASE", userId);
    }

    private GameStateUpdate handleEndTurn(GameRoom room, String userId) {
        if (!userId.equals(room.getCurrentTurnUserId()))
            return GameStateUpdate.error(room.getRoomId(), "Not your turn");
        // Ending from the main phase goes through the end phase first (rule 8.3): its move up to the Línea frontal
        if ("MAIN".equals(room.getCurrentPhase())) {
            handleEnterEndPhase(room, userId);
            if (room.getPrompt() != null) {
                room.setEndTurnPending(true);
                return GameStateUpdate.from(room, "ENTER_END_PHASE", userId);
            }
        }
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
            clearTurnKeywords(room);
            autoDraw(opponent);
        }
        room.setCurrentPhase("MAIN");
        if (opponentId != null) EffectEngine.offerAdvance(room, opponentId, true);
        return GameStateUpdate.from(room, "END_TURN", userId);
    }

    /** "Hasta el final del turno" ends and the per-turn limits reset when the turn changes (rule 11.8.0.7). */
    private void clearTurnKeywords(GameRoom room) {
        room.getPlayers().values().forEach(p -> p.getField().forEach(c -> c.getTurnKeywords().clear()));
        room.getUsedThisTurn().clear();
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
        int prev = room.getSharedCounter();
        room.setSharedCounter(Math.max(-10, Math.min(10, d.intValue())));
        PlayerState player = room.getPlayer(userId);
        GameStateUpdate update = GameStateUpdate.from(room, "SET_COUNTER", userId);
        String who = player != null ? player.getUsername() : userId;
        update.setLogEntry(who + " ajustó el contador compartido de " + prev + " a " + room.getSharedCounter());
        return update;
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
        String names = discard.stream()
                .map(c -> c.isFaceDown() ? "una carta oculta" : cardLabel(c))
                .collect(Collectors.joining(", "));
        Collections.shuffle(discard);
        discard.forEach(c -> c.setFaceDown(true));
        player.getDiscardPile().clear();
        player.getDeck().addAll(discard);
        GameStateUpdate update = GameStateUpdate.from(room, "DECK_INCLUDE_DISCARD", userId);
        update.setLogEntry(player.getUsername() + " mezcló el descarte con el mazo"
                + (names.isEmpty() ? "" : ": " + names));
        return update;
    }

    private GameStateUpdate handleDeckIncludeTribute(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        List<CardSlot> tribute = new ArrayList<>(player.getTributeZone());
        String names = tribute.stream()
                .map(c -> c.isFaceDown() ? "una carta oculta" : cardLabel(c))
                .collect(Collectors.joining(", "));
        Collections.shuffle(tribute);
        tribute.forEach(c -> c.setFaceDown(true));
        player.getTributeZone().clear();
        player.getDeck().addAll(tribute);
        GameStateUpdate update = GameStateUpdate.from(room, "DECK_INCLUDE_TRIBUTE", userId);
        update.setLogEntry(player.getUsername() + " mezcló el tributo con el mazo"
                + (names.isEmpty() ? "" : ": " + names));
        return update;
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
        // Strength 0: destroyed right away (rule 12.0.0.2)
        EffectEngine.destroyIfNoStrength(room, userId, player, card);
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
        // Rule 9.2.0.3: its whole state, also its effects until the end of the turn and its role in a combat
        EffectEngine.inheritFromSummoner(room, summoned, summoner);

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
                // The summoner is sacrificed; its Recursos and Materiales pass to the summoned creature (rule 9.3.0.5)
                summoned.setTapped(summoner.isTapped());
                summoned.setStrengthModifier(summoner.getStrengthModifier());
                summoned.getMaterials().addAll(0, summoner.getMaterials());
                summoned.getResources().addAll(0, summoner.getResources());
                summoner.setMaterials(new ArrayList<>());
                summoner.setResources(new ArrayList<>());
                summoner.setFaceDown(false); summoner.setTapped(false); summoner.setX(null); summoner.setY(null);
                player.getTributeZone().add(summoner);
            }
            case "promotion" -> {
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
        // Recorded by the engine: it offers the move up to the Línea frontal, does the regulatory draw and then
        // fires the summon and sacrifice triggers ("En Invocación", "En Destrucción")
        EffectEngine.specialSummoned(room, userId, summoned, summoner, summonType,
                player.getUsername() + " invocó especialmente a " + cardLabel(summoned)
                        + " (" + summonTypeLabel(summonType) + ") usando " + cardLabel(summoner));
        return GameStateUpdate.from(room, "SPECIAL_SUMMON", userId);
    }

    private String summonTypeLabel(String summonType) {
        if (summonType == null) return "invocación especial";
        return switch (summonType) {
            case "materialization" -> "Materialización";
            case "evolution"       -> "Evolución";
            case "ritual"          -> "Ritual";
            case "promotion"       -> "Ascenso";
            default                -> summonType;
        };
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

    private CardSlot findInstanceAnywhere(PlayerState player, String instanceId) {
        List<List<CardSlot>> zones = List.of(
                player.getHand(), player.getDeck(), player.getField(),
                player.getLifeStack(), player.getTributeZone(), player.getDiscardPile());
        for (List<CardSlot> zone : zones) {
            for (CardSlot c : zone) {
                if (instanceId.equals(c.getInstanceId())) return c;
                for (CardSlot m : c.getMaterials()) if (instanceId.equals(m.getInstanceId())) return m;
                for (CardSlot r : c.getResources()) if (instanceId.equals(r.getInstanceId())) return r;
            }
        }
        return null;
    }

    private GameStateUpdate handleRemoveCard(GameRoom room, String userId, GameAction action) {
        String instanceId = action.getString("instanceId");
        if (instanceId == null) return GameStateUpdate.error(room.getRoomId(), "No instanceId");
        String targetPlayerId = action.getString("targetPlayerId");
        PlayerState player = targetPlayerId != null ? room.getPlayer(targetPlayerId) : room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");

        List<List<CardSlot>> zones = List.of(
                player.getHand(), player.getDeck(), player.getField(),
                player.getLifeStack(), player.getTributeZone(), player.getDiscardPile());
        boolean removed = false;
        for (List<CardSlot> zone : zones) {
            if (zone.removeIf(c -> instanceId.equals(c.getInstanceId()))) { removed = true; break; }
        }
        if (!removed) {
            outer:
            for (List<CardSlot> zone : zones) {
                for (CardSlot c : zone) {
                    if (c.getMaterials().removeIf(m -> instanceId.equals(m.getInstanceId()))) { removed = true; break outer; }
                    if (c.getResources().removeIf(r -> instanceId.equals(r.getInstanceId()))) { removed = true; break outer; }
                }
            }
        }
        if (!removed) return GameStateUpdate.error(room.getRoomId(), "Card not found");
        return GameStateUpdate.from(room, "REMOVE_CARD", userId);
    }

    private GameStateUpdate handleRestoreCard(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        String instanceId = action.getString("instanceId");
        if (instanceId == null) return GameStateUpdate.error(room.getRoomId(), "No instanceId");
        if (findInstanceAnywhere(player, instanceId) != null) {
            return GameStateUpdate.error(room.getRoomId(), "La carta ya está en el juego");
        }
        CardSlot card = new CardSlot();
        card.setInstanceId(instanceId);
        card.setCardId(action.getString("cardId"));
        card.setImageUrl(action.getString("imageUrl"));
        card.setCardName(action.getString("cardName"));
        card.setCardType(action.getString("cardType"));
        Object rawStrength = action.getPayload().get("strength");
        if (rawStrength instanceof Number n) card.setStrength(n.intValue());
        card.setSpecialSummonKind(action.getString("specialSummonKind"));
        card.setFaceDown(false);
        player.getHand().add(card);
        return GameStateUpdate.from(room, "RESTORE_CARD", userId);
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

    private GameStateUpdate handleRevealToHand(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        String instanceId = action.getString("instanceId");
        CardSlot card = player.getDeck().stream()
                .filter(c -> instanceId.equals(c.getInstanceId()))
                .findFirst().orElse(null);
        if (card == null) return GameStateUpdate.error(room.getRoomId(), "Card not found in deck");
        player.getDeck().remove(card);
        card.setFaceDown(false);
        player.getHand().add(card);
        GameStateUpdate update = GameStateUpdate.from(room, "REVEAL_TO_HAND", userId);
        update.setRevealedCardImageUrl(card.getImageUrl());
        update.setRevealedCardName(card.getCardName());
        update.setLogEntry(player.getUsername() + " reveló " + cardLabel(card) + " y la añadió a la mano");
        return update;
    }

    private GameStateUpdate handleRefreshCardMetadata(GameRoom room, String userId, GameAction action) {
        String cardId = action.getString("cardId");
        if (cardId == null) return GameStateUpdate.error(room.getRoomId(), "No cardId");

        DriveCard dc = driveCardRepo.findById(cardId).orElse(null);
        if (dc == null || dc.getName() == null)
            return GameStateUpdate.error(room.getRoomId(), "Card not found in drive_cards");

        List<Card> matches = cardRepo.findByCardNameIgnoreCaseAndEdition(dc.getName(), dc.getEdition());
        if (matches.isEmpty())
            return GameStateUpdate.error(room.getRoomId(), "Card metadata not found");
        Card meta = matches.get(0);

        for (PlayerState player : room.getPlayers().values()) {
            refreshSlotsInList(player.getHand(),         cardId, meta);
            refreshSlotsInList(player.getDeck(),         cardId, meta);
            refreshSlotsInList(player.getOriginalDeck(), cardId, meta);
            refreshSlotsInList(player.getLifeStack(),    cardId, meta);
            refreshSlotsInList(player.getTributeZone(),  cardId, meta);
            refreshSlotsInList(player.getDiscardPile(),  cardId, meta);
            for (CardSlot fs : player.getField()) {
                applyMeta(fs, cardId, meta);
                refreshSlotsInList(fs.getMaterials(), cardId, meta);
                refreshSlotsInList(fs.getResources(), cardId, meta);
            }
        }
        return GameStateUpdate.from(room, "REFRESH_CARD_METADATA", userId);
    }

    private void autoDraw(PlayerState player) {
        if (player.getDeck().isEmpty()) return;
        CardSlot drawn = player.getDeck().remove(0);
        drawn.setFaceDown(false);
        player.getHand().add(drawn);
    }

    private List<CardSlot> deepCopySlots(List<CardSlot> slots) {
        return slots.stream().map(s -> {
            CardSlot c = new CardSlot();
            c.setInstanceId(s.getInstanceId());
            c.setCardId(s.getCardId());
            c.setImageUrl(s.getImageUrl());
            c.setCardName(s.getCardName());
            c.setCardType(s.getCardType());
            c.setStrength(s.getStrength());
            c.setSpecialSummonKind(s.getSpecialSummonKind());
            c.setStarter(s.isStarter());
            c.setCost(s.getCost());
            c.setLevel(s.getLevel());
            c.setColors(new ArrayList<>(s.getColors()));
            c.setClasses(new ArrayList<>(s.getClasses()));
            c.setSpecialCost(s.getSpecialCost());
            c.setRequirement(s.getRequirement());
            c.setKeywords(new ArrayList<>(s.getKeywords()));
            c.setFaceDown(true);
            return c;
        }).collect(Collectors.toList());
    }

    private void refreshSlotsInList(List<CardSlot> slots, String cardId, Card meta) {
        slots.stream().filter(s -> cardId.equals(s.getCardId())).forEach(s -> applyMeta(s, cardId, meta));
    }

    private void applyMeta(CardSlot slot, String cardId, Card meta) {
        if (!cardId.equals(slot.getCardId())) return;
        slot.setCardName(meta.getCardName());
        slot.setCardType(meta.getCardType());
        slot.setStrength(meta.getStrength());
        slot.setSpecialSummonKind(meta.getSpecialSummonKind());
        slot.setStarter(Boolean.TRUE.equals(meta.getStarter()));
        // Printed properties the rules engine reads (cost to pay, filters by color/class/level)
        slot.setCost(meta.getCost());
        slot.setLevel(meta.getLevel());
        slot.setClasses(meta.getCardClasses() != null ? new ArrayList<>(meta.getCardClasses()) : new ArrayList<>());
        slot.setSpecialCost(meta.getSpecialCost());
        slot.setRequirement(meta.getRequirement());
        // "Letalidad {cantidad}" + 1 → "Letalidad 1"
        List<String> keywords = new ArrayList<>();
        if (meta.getKeywordEffects() != null) {
            meta.getKeywordEffects().forEach(k -> {
                if (k.getKeyword() == null) return;
                String name = k.getKeyword().replaceAll("\\{[^}]*}", k.getNumber() != null ? k.getNumber() : "").trim();
                keywords.add(name.replaceAll("\\s+", " "));
            });
        }
        slot.setKeywords(keywords);
        List<String> colors = new ArrayList<>();
        if (meta.getColorIdentity() != null) colors.add(meta.getColorIdentity());
        if (meta.getColors() != null) meta.getColors().stream().filter(c -> !colors.contains(c)).forEach(colors::add);
        slot.setColors(colors);
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

        List<String> fileIds = deckOpt.get().getCards();
        String baseUrl = r2PublicUrl.stripTrailing();

        // Batch 1: all DriveCards at once
        Map<String, DriveCard> driveCardMap = driveCardRepo.findAllById(fileIds)
                .stream().collect(Collectors.toMap(DriveCard::getId, dc -> dc));

        // Batch 2: all Card metadata by editions present in deck
        Set<String> editions = driveCardMap.values().stream()
                .map(DriveCard::getEdition)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, Card> cardMeta = cardRepo.findByEditionIn(new ArrayList<>(editions))
                .stream().collect(Collectors.toMap(
                        c -> c.getCardName().toLowerCase() + ":" + c.getEdition(),
                        c -> c,
                        (a, b) -> a
                ));

        return new ArrayList<>(fileIds.stream().map(driveFileId -> {
            CardSlot slot = new CardSlot();
            slot.setInstanceId(UUID.randomUUID().toString());
            slot.setCardId(driveFileId);
            slot.setFaceDown(true);
            DriveCard dc = driveCardMap.get(driveFileId);
            if (dc != null) {
                slot.setImageUrl(baseUrl + "/cards/" + driveFileId + ".jpg");
                if (dc.getName() != null) {
                    slot.setCardName(dc.getName());
                    if (dc.getEdition() != null) {
                        Card card = cardMeta.get(dc.getName().toLowerCase() + ":" + dc.getEdition());
                        if (card != null) {
                            applyMeta(slot, driveFileId, card);
                        }
                    }
                }
            }
            return slot;
        }).toList());
    }
}
