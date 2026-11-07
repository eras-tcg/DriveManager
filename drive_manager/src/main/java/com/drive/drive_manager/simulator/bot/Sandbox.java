package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.PlayerState;
import com.drive.drive_manager.simulator.room.RoomCopy;
import com.drive.drive_manager.simulator.room.RoomEngine;
import com.drive.drive_manager.simulator.room.RoomHost;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where the bot tries its moves: copies of a room, played by the room's rules alone ({@link RoomEngine}: no
 * database, no messages, no missions), so the real game is never touched.
 * <p>
 * A copy holds only what the bot may know. The rival's hidden cards (hand, deck and face-down lives) are shuffled
 * together and dealt back, as many to each zone as there were: the bot knows how many cards each zone holds, not
 * which. Its own deck is shuffled too: it does not know its order.
 */
public final class Sandbox {

    private static final AtomicLong IDS = new AtomicLong();

    private final RoomEngine engine = new RoomEngine(RoomHost.gameOnly());
    /** The chance of the copies (their shuffles, the hidden cards dealt again): the bot's own, seedable. */
    private java.util.Random chance = new java.util.Random();

    /** The same seed, the same copies: two bots seeded alike try their moves on the same deals. */
    void seed(long seed) {
        chance = new java.util.Random(seed);
    }

    /** A copy of the room as the bot may know it, ready to play on. */
    public GameRoom fork(GameRoom room, String me) {
        return fork(room, me, null);
    }

    /** The same, dealt by {@code deal} (the same seed, the same copy) or, when null, by the sandbox's own chance. */
    GameRoom fork(GameRoom room, String me, java.util.Random deal) {
        java.util.Random chance = deal != null ? deal : this.chance;
        GameRoom copy = RoomCopy.of(room, chance);
        copy.setRoomId("sim-" + IDS.incrementAndGet());
        copy.setEmptySince(null);
        copy.setHeadless(true);
        hideFrom(copy, me, chance);
        return copy;
    }

    /** A seed for a deal of its own, from the sandbox's chance. */
    long deal() {
        return chance.nextLong();
    }

    /** A move of {@code seat} on the copy (in a test room, the rival's side is played by its owner acting for it). */
    public GameStateUpdate act(GameRoom copy, String seat, GameAction action) {
        return engine.applyAs(copy, seat, action);
    }

    /** Done with a copy (kept for the callers: the copies are no longer registered anywhere). */
    public void drop(GameRoom copy) {
    }

    /** What the bot cannot see is dealt again at random (see the class). */
    static void hideFrom(GameRoom copy, String me, java.util.Random chance) {
        PlayerState self = copy.getPlayer(me);
        if (self != null) Collections.shuffle(self.getDeck(), chance);
        PlayerState rival = copy.getPlayer(copy.getOpponentId(me));
        if (rival == null) return;
        List<CardSlot> lives = rival.getLifeStack();
        List<Integer> hiddenLives = new ArrayList<>();
        for (int i = 0; i < lives.size(); i++) if (lives.get(i).isFaceDown()) hiddenLives.add(i);
        List<CardSlot> pool = new ArrayList<>(rival.getHand());
        pool.addAll(rival.getDeck());
        hiddenLives.forEach(i -> pool.add(lives.get(i)));
        Collections.shuffle(pool, chance);
        int next = 0;
        for (int i = 0; i < rival.getHand().size(); i++) rival.getHand().set(i, faced(pool.get(next++), false));
        for (int i = 0; i < rival.getDeck().size(); i++) rival.getDeck().set(i, faced(pool.get(next++), true));
        for (int i : hiddenLives) lives.set(i, faced(pool.get(next++), true));
    }

    private static CardSlot faced(CardSlot card, boolean down) {
        card.setFaceDown(down);
        return card;
    }
}
