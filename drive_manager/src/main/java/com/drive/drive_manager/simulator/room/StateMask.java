package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.simulator.bot.BotRooms;
import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.engine.Prompt;
import org.springframework.beans.BeanUtils;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one player may see of a room. The room's state is sent to each player on their own queue, so the other
 * side's hidden cards never leave the server:
 * <ul>
 *   <li>the rival's hand, both decks (contents and order) and the face-down lives are card backs; in the mulligan,
 *   until they confirm it, a player sees the five cards on top of their own deck (the ones they choose from);</li>
 *   <li>the rival's face-down cards on the field, the Tributos or the Descarte are card backs (their owner knows them);</li>
 *   <li>the rival's deck list, the choice the rival is making and what the rival can activate stay out;</li>
 *   <li>the timeline and the history do not name the cards drawn, looked at or sent to a hidden place.</li>
 * </ul>
 * A test room is played by its owner on both sides: it is sent whole. A player who may fix the board by hand (a site
 * editor) also sees their own deck and lives, which the manual actions work on.
 */
public final class StateMask {

    private StateMask() {
    }

    /**
     * Sends each player their own view of the room on their queue (/user/queue/room/{id}). An error goes only to
     * who caused it; a player who just left or was taken out still gets the update that says so. {@code room} is
     * null once the room is closed: its last update still names who was in it.
     */
    static void send(SimpMessagingTemplate template, String roomId, GameRoom room, GameStateUpdate update) {
        send(template, null, roomId, room, update);
    }

    /** {@code users}: who is connected, and from how many tabs (null: leave it to Spring). */
    static void send(SimpMessagingTemplate template, SimpUserRegistry users, String roomId, GameRoom room,
                     GameStateUpdate update) {
        if (update == null || template == null) return;
        String destination = "/queue/room/" + roomId;
        if (update.getError() != null) {
            if (update.getActorId() != null) toUser(template, users, update.getActorId(), destination, update);
            return;
        }
        // A replay is only its viewer's (the game's players are not watching it)
        Set<String> viewers = room != null && room.isReplay() ? new LinkedHashSet<>(Set.of(room.getHostId()))
                : new LinkedHashSet<>(update.getPlayers() == null ? Set.of() : update.getPlayers().keySet());
        if (update.getActorId() != null) viewers.add(update.getActorId());
        if (update.getSubjectUserId() != null) viewers.add(update.getSubjectUserId());
        viewers.remove(RoomService.EDITOR_RIVAL);
        viewers.remove(BotRooms.BOT);
        for (String viewer : viewers) {
            boolean manual = room != null && room.getManualPlayers().contains(viewer);
            toUser(template, users, viewer, destination, forViewer(update, viewer, manual));
        }
    }

    /**
     * Sends to each of the user's sessions (tabs) on its own. With setPreserveReceiveOrder(true), Spring 7.0.5 sends
     * one user message to all their sessions as the same message, which the first send leaves immutable: every other
     * session got "Expected mutable SimpMessageHeaderAccessor" and no update. A message with a session id goes only to
     * that session, so each tab gets its own.
     */
    static void toUser(SimpMessagingTemplate template, SimpUserRegistry users, String userId, String destination,
                       Object payload) {
        SimpUser user = users == null ? null : users.getUser(userId);
        Set<SimpSession> sessions = user == null ? Set.of() : user.getSessions();
        if (sessions.size() < 2) {
            template.convertAndSendToUser(userId, destination, payload);
            return;
        }
        for (SimpSession session : sessions) {
            SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            headers.setSessionId(session.getId());
            headers.setLeaveMutable(true);
            template.convertAndSendToUser(userId, destination, payload, headers.getMessageHeaders());
        }
    }

    /** {@code manual}: the viewer may use the manual actions in this room. */
    public static GameStateUpdate forViewer(GameStateUpdate update, String viewerId, boolean manual) {
        // A test room, or a replay of a game over: everything in view
        if (update.getPlayers() == null || update.isEditor() || update.isReplay()) return update;
        GameStateUpdate view = new GameStateUpdate();
        BeanUtils.copyProperties(update, view);

        Set<String> hidden = new HashSet<>();
        // The mulligan is chosen from the top of the player's own deck: those cards are theirs to see until they confirm
        boolean choosing = GameRoom.RoomStatus.MULLIGAN.name().equals(update.getStatus())
                && (update.getMulliganReady() == null || !update.getMulliganReady().contains(viewerId));
        int ownShown = choosing ? MULLIGAN_CARDS : 0;
        Map<String, PlayerState> players = new LinkedHashMap<>();
        update.getPlayers().forEach((id, player) -> {
            boolean mine = id.equals(viewerId);
            players.put(id, mask(player, mine, manual, mine ? ownShown : 0, hidden));
        });
        view.setPlayers(players);

        Prompt prompt = update.getPrompt();
        if (prompt != null && !viewerId.equals(prompt.getPlayerId())) view.setPrompt(waitingOn(prompt));
        view.setActivatable(onlyMine(update.getActivatable(), viewerId));
        view.setSpecialSummonable(onlyMine(update.getSpecialSummonable(), viewerId));
        view.setUnplayable(onlyMine(update.getUnplayable(), viewerId));
        if (update.getTimeline() != null) view.setTimeline(update.getTimeline().stream().map(e -> redact(e, viewerId, hidden)).toList());
        if (update.getEventLog() != null) view.setEventLog(update.getEventLog().stream().map(e -> redact(e, hidden)).toList());
        return view;
    }

    /** The cards a player chooses from in the mulligan (PreRoomActions.handleConfirmMulligan). */
    static final int MULLIGAN_CARDS = 5;

    /** {@code shown}: how many cards on top of the deck keep their face (the owner's, in the mulligan). */
    private static PlayerState mask(PlayerState player, boolean mine, boolean manual, int shown, Set<String> hidden) {
        PlayerState masked = new PlayerState(player.getUserId(), player.getUsername());
        BeanUtils.copyProperties(player, masked);
        boolean ownHidden = mine && manual;
        masked.setHand(mine ? player.getHand() : backs(player.getHand(), hidden));
        masked.setDeck(ownHidden ? player.getDeck() : deckBacks(player, shown, hidden));
        masked.setOriginalDeck(mine ? player.getOriginalDeck() : starterOnly(player));
        masked.setLifeStack(ownHidden ? player.getLifeStack() : faceDownBacks(player.getLifeStack(), hidden));
        masked.setTributeZone(mine ? player.getTributeZone() : faceDownBacks(player.getTributeZone(), hidden));
        masked.setDiscardPile(mine ? player.getDiscardPile() : faceDownBacks(player.getDiscardPile(), hidden));
        masked.setField(mine ? player.getField() : faceDownBacks(player.getField(), hidden));
        return masked;
    }

    /** Of the rival's deck list, only the starter they chose: the pre-room shows it on their seat. */
    private static List<CardSlot> starterOnly(PlayerState player) {
        if (player.getStarterCardId() == null) return new ArrayList<>();
        return player.getOriginalDeck().stream().filter(c -> player.getStarterCardId().equals(c.getCardId())).limit(1).toList();
    }

    /**
     * The deck as backs with ids of their own: a card known before (one returned to the deck) cannot be followed. The
     * first {@code shown} cards keep their face and their id (the mulligan sends them back by id).
     */
    private static List<CardSlot> deckBacks(PlayerState player, int shown, Set<String> hidden) {
        List<CardSlot> backs = new ArrayList<>();
        for (int i = 0; i < player.getDeck().size(); i++) {
            if (i < shown) {
                backs.add(player.getDeck().get(i));
                continue;
            }
            hidden.add(player.getDeck().get(i).getInstanceId());
            CardSlot back = new CardSlot();
            back.setInstanceId("deck-" + player.getUserId() + "-" + i);
            back.setFaceDown(true);
            backs.add(back);
        }
        return backs;
    }

    private static List<CardSlot> backs(List<CardSlot> cards, Set<String> hidden) {
        return cards.stream().map(c -> back(c, hidden)).toList();
    }

    private static List<CardSlot> faceDownBacks(List<CardSlot> cards, Set<String> hidden) {
        return cards.stream().map(c -> c.isFaceDown() ? back(c, hidden) : withMaskedAttachments(c, hidden)).toList();
    }

    /** A face-up card shows its face, but what is attached face down under it stays hidden. */
    private static CardSlot withMaskedAttachments(CardSlot card, Set<String> hidden) {
        boolean anyHidden = card.getMaterials().stream().anyMatch(CardSlot::isFaceDown)
                || card.getResources().stream().anyMatch(CardSlot::isFaceDown);
        if (!anyHidden) return card;
        CardSlot copy = new CardSlot();
        BeanUtils.copyProperties(card, copy);
        copy.setMaterials(faceDownBacks(card.getMaterials(), hidden));
        copy.setResources(faceDownBacks(card.getResources(), hidden));
        return copy;
    }

    /** A card back: where it is and whether it is exhausted, nothing of what it is. */
    private static CardSlot back(CardSlot card, Set<String> hidden) {
        hidden.add(card.getInstanceId());
        CardSlot back = new CardSlot();
        back.setInstanceId(card.getInstanceId());
        back.setFaceDown(true);
        back.setTapped(card.isTapped());
        back.setX(card.getX());
        back.setY(card.getY());
        back.setMaterials(backs(card.getMaterials(), hidden));
        back.setResources(backs(card.getResources(), hidden));
        return back;
    }

    /** The rival is choosing: who, and for which card's effect, but not the cards or the options. */
    private static Prompt waitingOn(Prompt prompt) {
        Prompt shown = new Prompt();
        shown.setId(prompt.getId());
        shown.setKind(prompt.getKind());
        shown.setPlayerId(prompt.getPlayerId());
        shown.setEffectId(prompt.getEffectId());
        shown.setPurpose(prompt.getPurpose());
        shown.setSourceName(prompt.getSourceName());
        shown.setSourceImageUrl(prompt.getSourceImageUrl());
        shown.setTitle(prompt.getTitle());
        return shown;
    }

    private static <T> Map<String, T> onlyMine(Map<String, T> byPlayer, String viewerId) {
        if (byPlayer == null) return null;
        Map<String, T> mine = new LinkedHashMap<>();
        if (byPlayer.containsKey(viewerId)) mine.put(viewerId, byPlayer.get(viewerId));
        return mine;
    }

    /** Events that always name a card only their player sees. */
    private static final Set<String> PRIVATE_EVENTS = Set.of(GameEvent.DRAW, GameEvent.LOOK_TOP);
    /** Events that name a card the viewer cannot see when it went to a hidden place. */
    private static final Set<String> MOVES = Set.of(GameEvent.SEND, GameEvent.MOVE);

    private static GameEvent redact(GameEvent event, String viewerId, Set<String> hidden) {
        boolean secret = !viewerId.equals(event.getPlayerId()) && (PRIVATE_EVENTS.contains(event.getType())
                || MOVES.contains(event.getType()) && hidden.contains(event.getCardInstanceId()));
        if (!secret || event.getCardName() == null) return event;
        GameEvent copy = new GameEvent();
        BeanUtils.copyProperties(event, copy);
        copy.setCardName(null);
        return copy;
    }

    private static GameLogEntry redact(GameLogEntry entry, Set<String> hidden) {
        if (entry.getSourceId() == null || !hidden.contains(entry.getSourceId())) return entry;
        GameLogEntry copy = new GameLogEntry();
        BeanUtils.copyProperties(entry, copy);
        copy.setSourceName(null);
        copy.setImageUrl(null);
        return copy;
    }
}
