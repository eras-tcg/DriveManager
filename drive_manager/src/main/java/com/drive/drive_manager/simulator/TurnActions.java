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
import static com.drive.drive_manager.simulator.PreRoomActions.*;
import static com.drive.drive_manager.simulator.ZoneActions.*;
import static com.drive.drive_manager.simulator.CardActions.*;

/** Turns, gems and the marker, by hand. */
final class TurnActions {

    private TurnActions() {}

    static GameStateUpdate handleEnterEndPhase(GameRoom room, String userId) {
        if (!userId.equals(room.getCurrentTurnUserId()))
            return GameStateUpdate.error(room.getRoomId(), "Not your turn");
        if (!"MAIN".equals(room.getCurrentPhase()) || room.isEngineBusy())
            return GameStateUpdate.error(room.getRoomId(), "La fase final empieza al terminar la fase principal");
        int dir = getPlayerDirection(room, userId);
        if (dir * room.getSharedCounter() > 0) {
            room.setSharedCounter(0);
        }
        room.setCurrentPhase("END_PHASE");
        TurnRules.turnEnding(room, userId);
        // Rule 8.3: the end phase is closed: once its move up and its triggers are done, the turn ends by itself
        if (room.isEngineBusy()) {
            room.setEndTurnPending(true);
            return GameStateUpdate.from(room, "ENTER_END_PHASE", userId);
        }
        return passTurn(room, userId);
    }

    static GameStateUpdate handleEndTurn(GameRoom room, String userId) {
        if (!userId.equals(room.getCurrentTurnUserId()))
            return GameStateUpdate.error(room.getRoomId(), "Not your turn");
        // Ending from the main phase goes through the end phase first (rule 8.3)
        if ("MAIN".equals(room.getCurrentPhase())) return handleEnterEndPhase(room, userId);
        if (room.isEngineBusy()) return GameStateUpdate.error(room.getRoomId(), "Termina primero lo pendiente de la fase final");
        return passTurn(room, userId);
    }

    static GameStateUpdate passTurn(GameRoom room, String userId) {
        room.setEndTurnPending(false);
        String opponentId = room.getOpponentId(userId);
        if (opponentId != null) startTurn(room, opponentId);
        else room.setCurrentPhase("MAIN");
        return GameStateUpdate.from(room, "END_TURN", userId);
    }

    /**
     * Rule 8.1, start of the turn, in order: the Marcador goes up by 1 (up to 5), the player gains that many gems
     * and draws 1 card, their cards are reactivated, they may move creatures up to the Línea frontal, and the
     * start-of-turn effects trigger. The main phase begins once all of that is done.
     */
    static void startTurn(GameRoom room, String playerId) {
        PlayerState player = room.getPlayer(playerId);
        room.setCurrentTurnUserId(playerId);
        room.setFirstTurn(false);
        room.setCurrentPhase("START");
        clearTurnKeywords(room);
        int newCount = Math.min(5, player.getResourceCount() + 1);
        player.setResourceCount(newCount);
        int dir = getPlayerDirection(room, playerId);
        room.setSharedCounter(Math.max(-10, Math.min(10, room.getSharedCounter() + newCount * dir)));
        // Not being able to draw at the start of the turn loses the game
        if (player.getDeck().isEmpty()) {
            EffectEngine.loseByDeckOut(room, playerId);
            return;
        }
        autoDraw(player);
        // "no se reactiva al inicio del turno": that card stays exhausted this time
        player.getField().forEach(c -> { if (c.getNoUntap() != 1 && c.getNoStartUntap() != 1) c.setTapped(false); });
        TurnRules.turnStarted(room, playerId);
        if (room.isEngineBusy()) {
            room.setStartPhasePending(true);
        } else {
            room.setCurrentPhase("MAIN");
        }
    }

    /** "Hasta el final del turno" ends and the per-turn limits reset when the turn changes (rule 11.8.0.7). */
    static void clearTurnKeywords(GameRoom room) {
        // "Al final del turno súbelo a tu mano" (placed by Janne's inherited effect)
        room.getPlayers().values().forEach(p -> new ArrayList<>(p.getField()).stream().filter(CardSlot::isReturnAtTurnEnd).forEach(c -> {
            p.getField().remove(c);
            com.drive.drive_manager.simulator.engine.Removals.leaveField(c);
            p.getHand().add(c);
            room.getEventLog().add(new GameLogEntry(System.currentTimeMillis(), c.getCardName() + " vuelve a la mano de " + p.getUsername()));
        }));
        room.getPlayers().values().forEach(p -> p.getField().forEach(c -> {
            c.getTurnKeywords().clear();
            c.getTurnKeywords().addAll(c.getLaterKeywords());
            c.getLaterKeywords().clear();
            c.getUntilNextAttack().clear();
            if (c.getNoUntap() > 0) c.setNoUntap(c.getNoUntap() - 1);
            if (c.getNoStartUntap() > 0) c.setNoStartUntap(c.getNoStartUntap() - 1);
            // What lasts until the end of the next turn now lasts until the end of this one
            c.setTurnStrength(c.getLaterStrength());
            c.setLaterStrength(0);
            c.getFloating().removeIf(f -> !"nextTurn".equals(f.until()));
            c.getFloating().replaceAll(f -> new FloatingEffect(f.text(), f.source(), "turn", f.strength(), f.keyword()));
        }));
        // What was given to the table "hasta el final del turno" ends; "del turno oponente" now lasts until the end of this one
        room.getFieldGrants().removeIf(g -> "turn".equals(g.until()));
        room.getFieldGrants().replaceAll(g -> new com.drive.drive_manager.simulator.engine.FieldGrant(g.controllerId(), g.filter(), g.keyword(), "turn", g.source(), g.exceptId()));
        // "el turno anterior": who used a Definitive effect this turn is remembered for the next one
        List<String> definitive = room.getUsedThisTurn().stream().filter(k -> k.endsWith("|" + EffectEngine.DEFINITIVE_USED))
                .map(k -> EffectEngine.DEFINITIVE_LAST_TURN + k.substring(0, k.indexOf('|'))).toList();
        List<String> destroyed = room.getUsedThisTurn().stream().filter(k -> k.startsWith(EffectEngine.ALLY_DESTROYED_BY_OPPONENT))
                .map(k -> EffectEngine.ALLY_DESTROYED_LAST_TURN + k.substring(EffectEngine.ALLY_DESTROYED_BY_OPPONENT.length())).toList();
        room.getUsedThisTurn().clear();
        room.getUsedThisTurn().addAll(definitive);
        room.getUsedThisTurn().addAll(destroyed);
    }

    static int getPlayerDirection(GameRoom room, String userId) {
        String firstId = room.getPlayers().keySet().iterator().next();
        return firstId.equals(userId) ? -1 : 1;
    }

    static GameStateUpdate handleGrantResource(GameRoom room, String userId) {
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

    static GameStateUpdate handleSetCounter(GameRoom room, String userId, GameAction action) {
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

    static GameStateUpdate handleUseResources(GameRoom room, String userId, GameAction action) {
        Double d = action.getDouble("amount");
        if (d == null) return GameStateUpdate.error(room.getRoomId(), "No amount");
        int next = room.getSharedCounter() + d.intValue();
        room.setSharedCounter(Math.max(-10, Math.min(10, next)));
        PlayerState player = room.getPlayer(userId);
        if (player != null) player.setResourceCount(Math.abs(d.intValue()));
        return GameStateUpdate.from(room, "USE_RESOURCES", userId);
    }

    static GameStateUpdate handleSetMarker(GameRoom room, String userId, GameAction action) {
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

    /** Turns the offer of the player's optional triggered effects off or on (in a test room, of both sides). */
    static GameStateUpdate handleSetSkipOptional(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Jugador no encontrado");
        boolean skip = action.getBoolean("value", false);
        if (room.isEditor()) room.getPlayers().values().forEach(p -> p.setSkipOptional(skip));
        else player.setSkipOptional(skip);
        // Not in the history: neither player is told; the effects let go look like ones the player chose not to activate
        return GameStateUpdate.from(room, "SET_SKIP_OPTIONAL", userId);
    }

    static GameStateUpdate handleDraw(GameRoom room, String userId) {
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

    static void autoDraw(PlayerState player) {
        if (player.getDeck().isEmpty()) return;
        CardSlot drawn = player.getDeck().remove(0);
        drawn.setFaceDown(false);
        player.getHand().add(drawn);
    }

    static GameStateUpdate handleForfeit(GameRoom room, String userId) {
        room.setStatus(GameRoom.RoomStatus.FINISHED);
        return GameStateUpdate.from(room, "FORFEIT", userId);
    }

    static GameStateUpdate handleSurrender(GameRoom room, String userId) {
        room.setWinnerId(room.getOpponentId(userId));
        room.setEndReason("surrender");
        room.getPostGameChoices().clear();
        room.setStatus(GameRoom.RoomStatus.POST_GAME);
        return GameStateUpdate.from(room, "SURRENDER", userId);
    }
}
