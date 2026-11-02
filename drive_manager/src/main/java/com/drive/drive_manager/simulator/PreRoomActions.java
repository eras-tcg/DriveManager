package com.drive.drive_manager.simulator;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.repository.DeckListRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.simulator.engine.CardEffects;
import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.engine.TurnRules;
import com.drive.drive_manager.simulator.engine.Summons;
import com.drive.drive_manager.simulator.engine.Removals;
import com.drive.drive_manager.simulator.engine.CombatRules;
import com.drive.drive_manager.simulator.engine.Chain;
import com.drive.drive_manager.simulator.engine.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.drive.drive_manager.simulator.RoomService.*;
import static com.drive.drive_manager.simulator.RulesActions.*;
import static com.drive.drive_manager.simulator.TurnActions.*;
import static com.drive.drive_manager.simulator.ZoneActions.*;
import static com.drive.drive_manager.simulator.CardActions.*;

/** The room before and after a game: decks, starter, mulligan. */
final class PreRoomActions {

    private PreRoomActions() {}

    /** Rule 3.0.1: who starts; the other player gets the "ventaja del iniciador" (rule 6.2.1.4). */
    static void setFirstPlayer(GameRoom room, String firstPlayerId) {
        room.setCurrentTurnUserId(firstPlayerId);
        room.setFirstPlayerId(firstPlayerId);
        room.setFirstTurn(true);
        room.setStarterAdvantageUserId(room.getOpponentId(firstPlayerId));
    }

    static GameStateUpdate handleSetStarter(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "No estás en la sala");
        if (room.getStatus() != GameRoom.RoomStatus.WAITING) return GameStateUpdate.error(room.getRoomId(), "La partida ya empezó");
        String cardId = action.getString("cardId");
        if (player.getDeck().stream().noneMatch(c -> c.isStarter() && c.getCardId().equals(cardId)))
            return GameStateUpdate.error(room.getRoomId(), "Esa carta no es un Iniciador de tu mazo");
        player.setStarterCardId(cardId);
        player.setReady(false);
        return GameStateUpdate.from(room, "SET_STARTER", userId);
    }

    /** The host chooses who takes the first turn: a player or "random". Both confirm again. */
    static GameStateUpdate handleSetFirstPlayer(GameRoom room, String userId, GameAction action) {
        if (!userId.equals(room.getHostId())) return GameStateUpdate.error(room.getRoomId(), "Solo el dueño de la sala elige quién empieza");
        if (room.getStatus() != GameRoom.RoomStatus.WAITING) return GameStateUpdate.error(room.getRoomId(), "La partida ya empezó");
        String choice = action.getString("choice");
        if (choice == null || (!"random".equals(choice) && !room.getPlayers().containsKey(choice)))
            return GameStateUpdate.error(room.getRoomId(), "Jugador no encontrado");
        room.setFirstChoice(choice);
        room.getPlayers().values().forEach(p -> p.setReady(false));
        return GameStateUpdate.from(room, "SET_FIRST_PLAYER", userId);
    }

    /** A player confirms (or not) that they start; with both ready the game starts at the mulligan. */
    static GameStateUpdate handleSetReady(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "No estás en la sala");
        if (room.getStatus() != GameRoom.RoomStatus.WAITING) return GameStateUpdate.error(room.getRoomId(), "La partida ya empezó");
        boolean ready = action.getBoolean("ready", true);
        if (ready && (player.getDeckId() == null || player.getStarterCardId() == null))
            return GameStateUpdate.error(room.getRoomId(), "Elige un mazo con un Iniciador");
        if (room.isEditor() && ready) {
            // The owner confirms for both sides
            PlayerState other = room.getPlayer(room.getOpponentId(userId));
            if (other == null || other.getDeckId() == null || other.getStarterCardId() == null)
                return GameStateUpdate.error(room.getRoomId(), "Elige también el mazo del otro lado");
            other.setReady(true);
        }
        player.setReady(ready);
        boolean all = room.getPlayers().size() == 2 && room.getPlayers().values().stream().allMatch(PlayerState::isReady);
        if (all) startFromPreRoom(room);
        return GameStateUpdate.from(room, all ? "START_GAME" : "SET_READY", userId);
    }

    /**
     * Starts the game from the pre-room choices: who goes first (rule 3.0.1), each starter set aside and public
     * (rule 3.0.2), decks shuffled; then the mulligan.
     */
    static void startFromPreRoom(GameRoom room) {
        String first = room.getFirstChoice();
        if (first == null || "random".equals(first) || !room.getPlayers().containsKey(first)) {
            List<String> ids = new ArrayList<>(room.getPlayers().keySet());
            first = ids.get(new java.util.Random().nextInt(ids.size()));
        }
        setFirstPlayer(room, first);
        room.setCurrentPhase("MAIN");
        room.getStarterSelections().clear();
        room.getMulliganReady().clear();
        for (PlayerState player : room.getPlayers().values()) {
            player.setReady(false);
            CardSlot starter = player.getDeck().stream()
                    .filter(c -> c.isStarter() && c.getCardId().equals(player.getStarterCardId())).findFirst()
                    .or(() -> player.getDeck().stream().filter(CardSlot::isStarter).findFirst()).orElse(null);
            if (starter == null) continue;
            player.getDeck().remove(starter);
            starter.setFaceDown(false);
            starter.setX(50.0);
            starter.setY(75.0);
            player.getField().add(starter);
            room.getStarterSelections().put(player.getUserId(), starter.getInstanceId());
        }
        room.getPlayers().values().forEach(p -> Collections.shuffle(p.getDeck()));
        room.setStatus(GameRoom.RoomStatus.MULLIGAN);
    }

    static GameStateUpdate handleSelectStarter(GameRoom room, String userId, GameAction action) {
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

    static GameStateUpdate handleConfirmMulligan(GameRoom room, String userId, GameAction action) {
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

    static GameStateUpdate handleStartGame(GameRoom room, String userId, GameAction action) {
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

    static List<CardSlot> deepCopySlots(List<CardSlot> slots) {
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
            c.setLevel(s.getPrintedLevel());
            c.setContinuousLevel(s.getContinuousLevel());
            c.setColors(new ArrayList<>(s.getColors()));
            c.setClasses(new ArrayList<>(s.getClasses()));
            c.setSpecialCost(s.getSpecialCost());
            c.setRequirement(s.getRequirement());
            c.setKeywords(new ArrayList<>(s.getKeywords()));
            c.setInheritKeywords(new ArrayList<>(s.getInheritKeywords()));
            c.setUnprogrammed(s.getUnprogrammed());
            c.setMentions(new ArrayList<>(s.getMentions()));
            c.setFaceDown(true);
            return c;
        }).collect(Collectors.toList());
    }
}
