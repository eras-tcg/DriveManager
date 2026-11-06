package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * The bot's optimistic search, in its simulations only (the room's searchHope names whose searches it is): when a
 * look at the top of its deck finds valid cards, it trusts the search found the best ones. The chance of finding
 * any valid card is the deck's own (the copy's deck is shuffled as the bot cannot know its order), so the search pays
 * off as often as it would; what it finds, when it does, are the best valid cards of the deck: the most playable soon
 * ({@link Playability}), then the strongest. A search from a known zone (the Tributos) needs none of this.
 */
final class SearchHope {

    private SearchHope() {}

    /** Puts the best valid cards of the deck among the looked ones, as many as the valid ones it found (up to {@code max}). */
    static void apply(GameRoom room, PendingEffect effect, PlayerState player, Predicate<CardSlot> valid, int max) {
        if (room.getSearchHope() == null || !room.getSearchHope().equals(player.getUserId())) return;
        List<CardSlot> looked = effect.getLooked();
        int found = (int) looked.stream().filter(valid).count();
        int k = Math.min(found, max);
        if (k == 0) return;
        List<CardSlot> pool = new ArrayList<>();
        for (CardSlot c : looked) if (valid.test(c)) pool.add(c);
        for (CardSlot c : player.getDeck()) if (valid.test(c)) pool.add(c);
        String me = player.getUserId();
        pool.sort(Comparator.comparingInt((CardSlot c) -> Playability.level(room, me, c))
                .thenComparing(Comparator.comparingInt((CardSlot c) -> c.getStrength() == null ? 0 : c.getStrength()).reversed())
                .thenComparing(Comparator.comparingInt((CardSlot c) -> c.getCost() == null ? 0 : c.getCost()).reversed()));
        List<CardSlot> best = pool.subList(0, Math.min(k, pool.size()));
        // Each best card from the deck swaps places with a valid looked card that is not among the best
        for (CardSlot b : new ArrayList<>(best)) {
            if (looked.contains(b)) continue;
            CardSlot out = looked.stream().filter(c -> valid.test(c) && !best.contains(c)).findFirst().orElse(null);
            if (out == null) break;
            int li = looked.indexOf(out), di = player.getDeck().indexOf(b);
            if (di < 0) continue;
            b.setFaceDown(out.isFaceDown());
            looked.set(li, b);
            out.setFaceDown(true);
            player.getDeck().set(di, out);
        }
    }
}
