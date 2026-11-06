package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.Playability;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What each card of a hand is worth to keep, from 0 to 1 (hRank, the user's rule for what to tribute from the hand,
 * 2026-11-04): the product of three factors, strongest first.
 * <ul>
 *   <li>Copies in the hand: the first copy of a card counts 1, the second {@code rCopy}, the third {@code rCopy}², the
 *   fourth {@code rCopy}³ (with 0.4: a second copy is already the one to give up, a fourth is worth nothing).</li>
 *   <li>Cards of the same type (creature, utility, structure): within each type, the best card counts 1, the next
 *   {@code rType}, the next {@code rType}², so piling up one type while giving up the others costs.</li>
 *   <li>Playability ({@link Playability}, by the rules): 1 if it can be played now, {@code rPlay} if at its next turn,
 *   {@code rPlay}² if later.</li>
 * </ul>
 * A hand of different cards of different types, all playable now, is worth what it was before (1 a card). The table's
 * value counts the hand by these ({@link TableScore#hand}), so the search sees what a tribute costs; the heuristic
 * and the tributes tried give up the lowest first.
 */
final class HandRank {

    private HandRank() {}

    static boolean on(BotSettings w) {
        return w.get("hRank") >= 0.5;
    }

    /** Each card's worth to keep, by its index in the hand. */
    static double[] quality(GameRoom room, PlayerState p, BotSettings w) {
        List<CardSlot> hand = p.getHand();
        int n = hand.size();
        double rCopy = w.get("rCopy"), rType = w.get("rType"), rPlay = w.get("rPlay");
        double[] q = new double[n];
        Map<String, Integer> copies = new HashMap<>();
        Map<String, Integer> levels = new HashMap<>();
        for (int i = 0; i < n; i++) {
            CardSlot c = hand.get(i);
            String key = c.getCardName() == null ? "#" + i : BotSettings.cardKey(c.getCardName());
            int j = copies.merge(key, 1, Integer::sum) - 1;
            double play = 1;
            if (room != null && rPlay < 1) {
                int level = levels.computeIfAbsent(key, k -> Playability.level(room, p.getUserId(), c));
                play = Math.pow(rPlay, level);
            }
            q[i] = Math.pow(rCopy, j) * play;
        }
        // Within each type the best go first: the k-th of a type counts rType^k
        Map<String, List<Integer>> byType = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String type = hand.get(i).getCardType() == null ? "" : hand.get(i).getCardType();
            byType.computeIfAbsent(type, t -> new ArrayList<>()).add(i);
        }
        for (List<Integer> same : byType.values()) {
            same.sort(Comparator.comparingDouble((Integer i) -> -q[i]));
            for (int k = 0; k < same.size(); k++) q[same.get(k)] *= Math.pow(rType, k);
        }
        return q;
    }

    /** The cards chosen among (ids in the hand), the least worth keeping first; ties by the card's size. */
    static List<String> weakestFirst(GameRoom room, PlayerState p, List<String> choices, BotSettings w) {
        double[] q = quality(room, p, w);
        Map<String, Double> byId = new HashMap<>();
        Map<String, Double> size = new HashMap<>();
        for (int i = 0; i < p.getHand().size(); i++) {
            CardSlot c = p.getHand().get(i);
            byId.put(c.getInstanceId(), q[i]);
            size.put(c.getInstanceId(), BotPlayer.value(c));
        }
        List<String> order = new ArrayList<>(choices);
        order.sort(Comparator.comparingDouble((String id) -> byId.getOrDefault(id, 1.0)).thenComparingDouble(id -> size.getOrDefault(id, 0.0)));
        return order;
    }
}
