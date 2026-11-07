package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.simulator.actions.ActionDispatcher;
import com.drive.drive_manager.simulator.bot.BotRooms;
import com.drive.drive_manager.simulator.engine.EffectEngine;

import java.util.ArrayList;
import java.util.Map;

import static com.drive.drive_manager.simulator.actions.TurnActions.handleEnterEndPhase;

/**
 * The rules of a room, without the server around them: whose action it is (the test room's owner acting for its
 * rival, the bot's deck), the guards, the closed game state, the dispatch, and the history line. The server's
 * {@link RoomService} adds its rooms, locks, missions, recording and messages around it; the bot plays its copies
 * with it alone ({@code Sandbox}), and so will a game in the browser.
 */
public final class RoomEngine {

    /** The test room's rival: its owner plays that side too. */
    public static final String EDITOR_RIVAL = "editor-rival";

    /** An action applied: what it changed, and who it counted as (the rival or the bot instead of the owner). */
    public record Applied(GameStateUpdate update, String actor) {}

    private final ActionDispatcher dispatcher;

    public RoomEngine(RoomHost host) {
        this.dispatcher = new ActionDispatcher(host);
    }

    /** Who {@code userId}'s action counts as, or an error update when they may not act in this room. */
    public Applied actor(GameRoom room, String userId, GameAction action) {
        ActionType type = ActionType.of(action);
        if (room.isEditor()) {
            if (!userId.equals(room.getHostId())) return refused(room, "Es una sala de pruebas: solo su dueño puede jugar en ella", userId);
            // The owner plays the rival's side too
            if (EDITOR_RIVAL.equals(action.getString("actAs")) && (type == null || !type.ownerOnly())) userId = EDITOR_RIVAL;
        }
        if (BotRooms.forBot(room, action)) {
            if (!userId.equals(room.getHostId())) return refused(room, "Solo el dueño de la sala elige el mazo del bot", userId);
            if (room.isRanked()) return refused(room, "En una partida ranked el bot juega con tu mismo mazo", userId);
            userId = BotRooms.BOT;
        }
        return new Applied(null, userId);
    }

    /** Applies the action of {@code actor} (as {@link #actor} gave it): the guards, the dispatch, the history line. */
    public GameStateUpdate apply(GameRoom room, String actor, GameAction action, boolean manual) {
        ActionType type = ActionType.of(action);
        String refusal = RoomGuard.refusal(room, actor, action, manual);
        if (refusal != null) return GameStateUpdate.error(room.getRoomId(), refusal);

        // Closed game state (rule 7.2): while a chain or a choice is pending, only answering it (and leaving
        // or conceding) is allowed. The board's manual actions wait until the effect is done.
        if (room.isEngineBusy() && (type == null || !type.allowedWhileResolving())) {
            return GameStateUpdate.error(room.getRoomId(), "Hay un efecto resolviéndose: espera a que termine");
        }

        GameStateUpdate update = dispatcher.dispatch(type, room, actor, action);

        // "termina tu turno" (Última Línea De Defensa): once its combat and effects are over, the turn ends
        String current = room.getCurrentTurnUserId();
        if (update != null && update.getError() == null && current != null && !room.isEngineBusy()
                && room.getStatus() == GameRoom.RoomStatus.IN_PROGRESS && "MAIN".equals(room.getCurrentPhase())
                && room.getUsedThisTurn().remove(EffectEngine.END_TURN_REQUESTED + current)) {
            update = handleEnterEndPhase(room, current);
        }
        if (update != null && update.getError() == null) {
            room.setVersion(room.getVersion() + 1);
            recordLog(room, update, action);
            if (!room.isHeadless()) update.setEventLog(new ArrayList<>(room.getEventLog()));
        }
        return update;
    }

    /** {@link #actor} and {@link #apply} together. */
    public GameStateUpdate applyAction(GameRoom room, String userId, GameAction action, boolean manual) {
        Applied who = actor(room, userId, action);
        if (who.update() != null) return who.update();
        return apply(room, who.actor(), action, manual);
    }

    /**
     * A move of {@code seat}: its own in a room against the bot or online; in a test room the owner plays both sides,
     * so the rival's moves are the owner's acting for it.
     */
    public GameStateUpdate applyAs(GameRoom room, String seat, GameAction action) {
        if (!room.isEditor() || seat.equals(room.getHostId())) return applyAction(room, seat, action, false);
        return applyAction(room, room.getHostId(), asRival(action), false);
    }

    /** The same action, as the test room's owner acting for its rival. */
    public static GameAction asRival(GameAction action) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(action.getPayload() == null ? Map.of() : action.getPayload());
        payload.put("actAs", EDITOR_RIVAL);
        GameAction rival = new GameAction();
        rival.setType(action.getType());
        rival.setPayload(new java.util.HashMap<>(payload));
        return rival;
    }

    /** A line of the history (an action by hand or of the room), with the card it was done to when there is one. */
    public static void recordLog(GameRoom room, GameStateUpdate update, GameAction action) {
        if (update.getLogEntry() == null) return;
        GameLogEntry entry = new GameLogEntry(System.currentTimeMillis(), update.getLogEntry());
        if (action != null) {
            entry.setKind("MANUAL");
            String id = action.getString("instanceId");
            CardSlot card = id == null ? null : EffectEngine.findInRoom(room, id);
            if (card != null) {
                entry.setSourceId(id);
                entry.setSourceName(card.getCardName());
                entry.setImageUrl(card.getImageUrl());
            }
        }
        room.getEventLog().add(entry);
        if (room.getEventLog().size() > SimulatorLimits.EVENT_LOG_MAX) room.getEventLog().remove(0);
    }

    private static Applied refused(GameRoom room, String message, String userId) {
        return new Applied(GameStateUpdate.error(room.getRoomId(), message), userId);
    }
}
