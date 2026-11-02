package com.drive.drive_manager.simulator;

import com.drive.drive_manager.simulator.engine.Prompt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one player may see of a room update: the opponent's hand, both decks, face-down cards and the opponent's decklist
 * are hidden, keeping only where each card is (its instance id, place and state), so the board still counts and draws them.
 * The cards of the player's own choice stay visible, even from the deck. A test room is sent whole: its owner plays both sides.
 */
public final class StateMask {

    private static final JsonMapper JSON = JsonMapper.shared();

    /** Per-player maps of what can be done now: they name the cards of that player's hand. */
    private static final List<String> BY_PLAYER = List.of("activatable", "specialSummonable", "unplayable", "legalActions", "blockedActions", "attackTargets");

    /** What a hidden card keeps: enough to draw its back where it is; the rest is emptied. */
    private static final Set<String> KEPT = Set.of("instanceId", "x", "y", "tapped", "fieldSeq");

    private StateMask() {}

    /**
     * Who gets an update: the room's players (not the test room's rival, nobody plays it), the player it is about (one just
     * taken out of the room) and the one who sent the action, even when the room is gone (its error).
     */
    public static Set<String> recipients(GameRoom room, GameStateUpdate update, String senderId, String notAPlayer) {
        Set<String> to = new LinkedHashSet<>(room == null ? List.of() : room.getPlayers().keySet());
        for (String id : new String[] {update.getSubjectUserId(), update.getActorId(), senderId}) if (id != null) to.add(id);
        to.remove(notAPlayer);
        return to;
    }

    /** The update as that player may see it. */
    public static Object forViewer(GameRoom room, GameStateUpdate update, String viewerId) {
        if (update.isEditor() || (room != null && room.isEditor())) return update;
        Set<String> hidden = hiddenFor(update.getPlayers() != null ? update.getPlayers() : room == null ? Map.of() : room.getPlayers(),
                update.getPrompt(), viewerId);
        ObjectNode tree = JSON.valueToTree(update);
        for (String key : BY_PLAYER) {
            if (tree.get(key) instanceof ObjectNode byPlayer) byPlayer.retain(viewerId);
        }
        if (tree.get("players") instanceof ObjectNode players) {
            for (Map.Entry<String, JsonNode> e : players.properties()) {
                if (!e.getKey().equals(viewerId) && e.getValue() instanceof ObjectNode player) player.putArray("originalDeck");
            }
        }
        mask(tree, hidden);
        return tree;
    }

    /** The instance ids that player may not see now. */
    static Set<String> hiddenFor(Map<String, PlayerState> players, Prompt prompt, String viewerId) {
        Set<String> hidden = new HashSet<>();
        for (PlayerState p : players.values()) {
            // Nobody knows the order of a deck, nor a face-down life
            p.getDeck().forEach(c -> hidden.add(c.getInstanceId()));
            p.getLifeStack().stream().filter(CardSlot::isFaceDown).forEach(c -> hidden.add(c.getInstanceId()));
            if (viewerId.equals(p.getUserId())) continue;
            p.getHand().forEach(c -> hidden.add(c.getInstanceId()));
            for (List<CardSlot> zone : List.of(p.getField(), p.getTributeZone(), p.getDiscardPile())) {
                for (CardSlot c : zone) {
                    if (c.isFaceDown()) hidden.add(c.getInstanceId());
                    for (List<CardSlot> attached : List.of(c.getMaterials(), c.getResources())) {
                        attached.stream().filter(CardSlot::isFaceDown).forEach(a -> hidden.add(a.getInstanceId()));
                    }
                }
            }
        }
        if (prompt != null) {
            // What the player is choosing among, they see; of another player's choice, only what is already public on the table
            // (looked cards have left the deck: they are only in the choice)
            if (viewerId.equals(prompt.getPlayerId())) prompt.getCards().forEach(c -> hidden.remove(c.getInstanceId()));
            else {
                Set<String> shown = publicIds(players);
                prompt.getCards().stream().filter(c -> !shown.contains(c.getInstanceId())).forEach(c -> hidden.add(c.getInstanceId()));
            }
        }
        return hidden;
    }

    /** The cards everyone sees: face up on the field (and attached), in the Tributos, the Descarte and the lives. */
    private static Set<String> publicIds(Map<String, PlayerState> players) {
        Set<String> shown = new HashSet<>();
        for (PlayerState p : players.values()) {
            for (List<CardSlot> zone : List.of(p.getField(), p.getTributeZone(), p.getDiscardPile(), p.getLifeStack())) {
                for (CardSlot c : zone) {
                    if (!c.isFaceDown()) shown.add(c.getInstanceId());
                    for (List<CardSlot> attached : List.of(c.getMaterials(), c.getResources())) {
                        attached.stream().filter(a -> !a.isFaceDown()).forEach(a -> shown.add(a.getInstanceId()));
                    }
                }
            }
        }
        return shown;
    }

    /** Hides, wherever they appear (zones, choices, chain, history), the cards of {@code hidden}. */
    private static void mask(JsonNode node, Set<String> hidden) {
        if (node instanceof ObjectNode object) {
            if (hidden.contains(object.path("instanceId").asString(null))) {
                // Same shape the board expects (empty lists, no values), nothing about the card
                for (Map.Entry<String, JsonNode> field : List.copyOf(object.properties())) {
                    if (KEPT.contains(field.getKey())) continue;
                    if (field.getValue().isArray()) object.putArray(field.getKey());
                    else if (field.getValue().isBoolean()) object.put(field.getKey(), false);
                    else if (field.getValue().isNumber()) object.put(field.getKey(), 0);
                    else object.putNull(field.getKey());
                }
                object.put("faceDown", true);
                return;
            }
            // A timeline event about a hidden card (a draw): not its name
            if (hidden.contains(object.path("cardInstanceId").asString(null))) object.putNull("cardName");
            // A history line about a hidden card: not its picture
            if (hidden.contains(object.path("sourceId").asString(null))) object.putNull("imageUrl");
            for (JsonNode child : object.values()) mask(child, hidden);
        } else if (node != null && node.isArray()) {
            for (JsonNode child : node.values()) mask(child, hidden);
        }
    }
}
