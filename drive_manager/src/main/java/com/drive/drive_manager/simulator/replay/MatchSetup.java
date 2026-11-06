package com.drive.drive_manager.simulator.replay;

import com.drive.drive_manager.simulator.actions.PreRoomActions;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;
import java.util.ArrayList;
import java.util.List;

/**
 * How a game started, taken the moment it starts (before the starters are set aside and the decks shuffled): the
 * seed of its chance, the room's kind and each seat with its deck in order. With it and the actions played after
 * (see {@link MatchRecorder}) the game can be played again exactly (see {@link MatchReplay}). A record: the bot's
 * room copies share it instead of copying it.
 *
 * @param seed            the game's chance starts from it (every shuffle and random pick of the game)
 * @param firstChoice     who goes first as chosen in the pre-room ("random" or a player id)
 * @param idSeq           the room's counter of ids then (new cards and effects number on from it)
 * @param eventSeq        the room's counter of events then
 */
public record MatchSetup(long seed, boolean editor, boolean bot, boolean ranked, boolean confirmMandatory, String hostId,
                         String firstChoice, long idSeq, long eventSeq, List<Seat> seats, int botEdge) {

    /** A player's seat: who sits there and the deck they start with, in its order. */
    public record Seat(String userId, String username, String deckId, String deckName, String starterCardId,
                       boolean skipOptional, List<CardSlot> deck) {}

    /** The room as it is now, just before its game starts with {@code seed}. */
    public static MatchSetup of(GameRoom room, long seed) {
        List<Seat> seats = new ArrayList<>();
        for (PlayerState p : room.getPlayers().values()) {
            seats.add(new Seat(p.getUserId(), p.getUsername(), p.getDeckId(), p.getDeckName(), p.getStarterCardId(),
                    p.isSkipOptional(), List.copyOf(PreRoomActions.deepCopySlots(p.getDeck()))));
        }
        return new MatchSetup(seed, room.isEditor(), room.isBot(), room.isRanked(), room.isConfirmMandatory(), room.getHostId(),
                room.getFirstChoice(), room.getIdSeq(), room.getEventSeq(), List.copyOf(seats), room.getBotEdge());
    }

    /** A new room, with this id, as the game's room was just before it started (its players seated and connected). */
    GameRoom toRoom(String roomId) {
        GameRoom room = new GameRoom();
        room.setRoomId(roomId);
        room.setEditor(editor);
        room.setBot(bot);
        room.setRanked(ranked);
        room.setConfirmMandatory(confirmMandatory);
        room.setHostId(hostId);
        room.setFirstChoice(firstChoice);
        room.setIdSeq(idSeq);
        room.setEventSeq(eventSeq);
        room.setBotEdge(botEdge);
        for (Seat s : seats) {
            PlayerState p = new PlayerState(s.userId(), s.username());
            p.setConnected(true);
            p.setDeckId(s.deckId());
            p.setDeckName(s.deckName());
            p.setStarterCardId(s.starterCardId());
            p.setSkipOptional(s.skipOptional());
            p.setDeck(PreRoomActions.deepCopySlots(s.deck()));
            p.setOriginalDeck(PreRoomActions.deepCopySlots(s.deck()));
            room.getPlayers().put(s.userId(), p);
        }
        return room;
    }

    /** The player's username, or the id when they are not in it. */
    String username(String userId) {
        return seats.stream().filter(s -> s.userId().equals(userId)).map(Seat::username).findFirst().orElse(userId);
    }
}
