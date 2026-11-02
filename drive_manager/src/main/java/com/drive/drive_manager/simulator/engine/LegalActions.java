package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a player can do now by the rules, card by card, for the board: asked of the same checks that would refuse the action, so the board
 * repeats no rule.
 */
public final class LegalActions {

    private LegalActions() {}

    /** By the card's instance id: "attack", "summon", "ritual", "play" and "activate". Only the cards that can do something. */
    public static Map<String, List<String>> of(GameRoom room, String userId) {
        Map<String, List<String>> legal = new LinkedHashMap<>();
        PlayerState player = room.getPlayer(userId);
        if (player == null || room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS || !"MAIN".equals(room.getCurrentPhase()) || room.isEngineBusy()) return legal;
        // Slow effects can be activated on either turn
        for (Chain.Activatable effect : Chain.activatable(room, userId)) add(legal, effect.instanceId(), "activate");
        if (Summons.checkMainPhase(room, userId, "") != null) return legal;
        // Copies: checking a utility's "En Juego" moves it out of the hand and onto the field, then back
        for (CardSlot card : new ArrayList<>(player.getField())) {
            if (CombatRules.whyCannotAttack(room, userId, card) == null) add(legal, card.getInstanceId(), "attack");
        }
        for (CardSlot card : new ArrayList<>(player.getHand())) {
            if (Summons.whyCannotSummon(room, userId, player, card) == null) add(legal, card.getInstanceId(), "summon");
            if (Summons.whyCannotPlay(room, userId, card) == null) add(legal, card.getInstanceId(), "play");
        }
        for (String id : SpecialRequirements.specialSummonable(room, userId)) add(legal, id, "ritual");
        return legal;
    }

    /**
     * By the card's instance id: why a card of the hand cannot be summoned or played now, so the board can say so (a Definitive
     * effect on the first turn, no gems…). Only on the player's main phase, and only for the cards {@code legal} leaves without actions.
     */
    public static Map<String, String> blocked(GameRoom room, String userId, Map<String, List<String>> legal) {
        Map<String, String> reasons = new LinkedHashMap<>();
        PlayerState player = room.getPlayer(userId);
        if (player == null || room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS || !"MAIN".equals(room.getCurrentPhase()) || room.isEngineBusy()) return reasons;
        if (Summons.checkMainPhase(room, userId, "") != null) return reasons;
        // Copies, as in of(): checking a utility's "En Juego" moves it out of the hand and back
        for (CardSlot card : new ArrayList<>(player.getHand())) {
            if (legal.containsKey(card.getInstanceId())) continue;
            String why = "creature".equals(card.getCardType()) ? Summons.whyCannotSummon(room, userId, player, card) : Summons.whyCannotPlay(room, userId, card);
            if (why != null) reasons.put(card.getInstanceId(), why);
        }
        return reasons;
    }

    /** By attacker: the enemies it can attack. */
    public static Map<String, List<String>> attackTargets(GameRoom room, String userId) {
        Map<String, List<String>> targets = new LinkedHashMap<>();
        PlayerState player = room.getPlayer(userId);
        if (player == null) return targets;
        for (CardSlot card : player.getField()) {
            if (!"creature".equals(card.getCardType())) continue;
            List<String> enemies = EffectEngine.ids(CombatRules.attackTargets(room, userId, card));
            if (!enemies.isEmpty()) targets.put(card.getInstanceId(), new ArrayList<>(enemies));
        }
        return targets;
    }

    private static void add(Map<String, List<String>> legal, String id, String action) {
        List<String> actions = legal.computeIfAbsent(id, k -> new ArrayList<>());
        if (!actions.contains(action)) actions.add(action);
    }
}
