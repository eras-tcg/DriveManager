package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;

/**
 * How soon a card can be played, by the rules and the known state of the game, as the board's legal actions are
 * found: a creature by its normal summon (its cost, with the reductions it has) or its special summon (a summoner,
 * the materials, the levels, the special cost); a utility if its "En Juego" can be activated (targets, limits) and
 * paid; a structure if it can be paid (and is the first of the turn). {@link #NOW} with the gems and the table of
 * now; {@link #NEXT_TURN} with the gems its Marcador will give at its next turn and the same table; else {@link #LATER}.
 * Where the card is does not matter (the hand, a look at the deck, the Tributos): it is asked as if it were in the hand.
 */
public final class Playability {

    public static final int NOW = 0, NEXT_TURN = 1, LATER = 2;

    private Playability() {}

    public static int level(GameRoom room, String userId, CardSlot card) {
        if (room == null || card == null || room.getPlayer(userId) == null) return LATER;
        if (playableNow(room, userId, card)) return NOW;
        // Its next turn: the gems its Marcador gives (one more, up to 5), its own main phase; the table as it is
        PlayerState player = room.getPlayer(userId);
        int counter = room.getSharedCounter();
        String turn = room.getCurrentTurnUserId(), phase = room.getCurrentPhase();
        try {
            room.setSharedCounter(Math.min(5, player.getResourceCount() + 1) * EffectEngine.direction(room, userId));
            room.setCurrentTurnUserId(userId);
            room.setCurrentPhase("MAIN");
            return playableNow(room, userId, card) ? NEXT_TURN : LATER;
        } finally {
            room.setSharedCounter(counter);
            room.setCurrentTurnUserId(turn);
            room.setCurrentPhase(phase);
        }
    }

    private static boolean playableNow(GameRoom room, String userId, CardSlot card) {
        try {
            PlayerState player = room.getPlayer(userId);
            if ("creature".equals(card.getCardType())) {
                return Summons.whyCannotSummon(room, userId, player, card) == null || SpecialRequirements.canSpecial(room, userId, card);
            }
            return Summons.whyCannotPlay(room, userId, card) == null;
        } catch (RuntimeException e) {
            // A card the checks cannot place (out of every zone they look at): not counted as playable
            return false;
        }
    }
}
