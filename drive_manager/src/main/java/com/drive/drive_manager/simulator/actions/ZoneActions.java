package com.drive.drive_manager.simulator.actions;

import com.drive.drive_manager.simulator.engine.GameRandom;
import com.drive.drive_manager.simulator.engine.Removals;
import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.PlayerState;
import com.drive.drive_manager.simulator.room.SimMessages;
import com.drive.drive_manager.simulator.room.Zone;

import java.util.*;
import java.util.stream.Collectors;

import static com.drive.drive_manager.simulator.actions.RulesActions.*;
import static com.drive.drive_manager.simulator.actions.TurnActions.*;
import static com.drive.drive_manager.simulator.actions.PreRoomActions.*;
import static com.drive.drive_manager.simulator.actions.CardActions.*;

/** Moving cards between zones by hand (editor mode), and looking at them. */
final class ZoneActions {

    private ZoneActions() {}

    static GameStateUpdate handleMove(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.notInRoom(room);

        String instanceId = action.getString("instanceId");
        String fromZone   = action.getString("fromZone");
        String toZone     = action.getString("toZone");
        Double x          = action.getDouble("x");
        Double y          = action.getDouble("y");

        List<CardSlot> from = getZone(player, fromZone);
        List<CardSlot> to   = getZone(player, toZone);
        if (from == null || to == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.INVALID_ZONE);

        Optional<CardSlot> cardOpt = from.stream()
                .filter(c -> instanceId.equals(c.getInstanceId()))
                .findFirst();
        if (cardOpt.isEmpty()) return GameStateUpdate.error(room.getRoomId(), SimMessages.CARD_NOT_FOUND);

        CardSlot card = cardOpt.get();
        from.remove(card);
        boolean wasPublic = !card.isFaceDown();

        if (Zone.FIELD.is(toZone)) {
            card.setX(x);
            card.setY(y);
            // Placed by hand: it reacts to events from now on
            if (!Zone.FIELD.is(fromZone)) card.setFieldSeq(room.getEventSeq() + 1);
        } else {
            card.setX(null);
            card.setY(null);
        }

        // When leaving field, send attached cards to discard
        if (Zone.FIELD.is(fromZone) && !Zone.FIELD.is(toZone)) {
            card.getMaterials().forEach(m -> { m.setFaceDown(false); m.setTapped(false); player.getDiscardPile().add(m); });
            card.getResources().forEach(r -> { r.setFaceDown(false); r.setTapped(false); player.getDiscardPile().add(r); });
            card.setMaterials(new ArrayList<>());
            card.setResources(new ArrayList<>());
            // A creature removed from the field loses its floating effects (what is decided below keeps its face)
            boolean faceDown = card.isFaceDown();
            com.drive.drive_manager.simulator.engine.Removals.leaveField(card);
            card.setFaceDown(faceDown);
        }

        if (Zone.DISCARD_PILE.is(toZone) || Zone.TRIBUTE_ZONE.is(toZone)) {
            card.setFaceDown(false);
        }
        if (Zone.LIFE_STACK.is(fromZone) && !Zone.LIFE_STACK.is(toZone)) {
            card.setFaceDown(false);
        }
        if (Zone.DECK.is(fromZone) && !Zone.DECK.is(toZone) && !Zone.LIFE_STACK.is(toZone)) {
            card.setFaceDown(false);
        }
        if (Zone.DECK.is(toZone)) {
            card.setFaceDown(true);
        }
        if (Zone.HAND.is(fromZone) && Zone.LIFE_STACK.is(toZone)) {
            card.setFaceDown(true);
        }
        Object forceFaceDown = action.getPayload().get("faceDown");
        if (Boolean.TRUE.equals(forceFaceDown)) {
            card.setFaceDown(true);
        }
        // Hand cards are always visible to their owner — hiding from the
        // OPPONENT is already handled unconditionally by zone type (hand is
        // never shown for isOpponent), so faceDown must never stick here even
        // if a "hide from opponent" send explicitly requested it above.
        if (Zone.HAND.is(toZone)) {
            card.setFaceDown(false);
        }
        if (!Zone.FIELD.is(toZone)) {
            card.setTapped(false);
        }

        String toPosition = action.getString("toPosition");
        if (Zone.DECK.is(toZone)) {
            if ("top".equals(toPosition)) {
                to.add(0, card);
            } else {
                to.add(card);
            }
        } else if (Zone.LIFE_STACK.is(toZone) && "top".equals(toPosition)) {
            to.add(0, card);
        } else {
            to.add(card);
        }

        GameStateUpdate update = GameStateUpdate.from(room, ActionType.MOVE_CARD, userId);
        update.setLogEntry(buildMoveLogEntry(room, player, card, fromZone, toZone, toPosition, wasPublic));
        return update;
    }

    static String buildMoveLogEntry(GameRoom room, PlayerState player, CardSlot card, String fromZone, String toZone, String toPosition, boolean wasPublic) {
        String who   = player.getUsername();
        String label = cardLabel(card);
        String from  = zoneLabel(fromZone);

        switch (Zone.parse(toZone).orElse(null)) {
            case FIELD: {
                // A creature entering the field from a non-field zone while the game is
                // in progress always triggers the effect-summon draw/no-draw prompt
                // (see FieldZone.vue's effectSummonPending) — so its placement is only
                // logged once that decision resolves, via handleEffectSummonResolve.
                boolean pendingDecision = "creature".equalsIgnoreCase(card.getCardType())
                        && !Zone.FIELD.is(fromZone)
                        && room.getStatus() == GameRoom.RoomStatus.IN_PROGRESS;
                if (pendingDecision) return null;
                String suffix = "creature".equalsIgnoreCase(card.getCardType()) && card.isFaceDown() ? " (set)" : "";
                return who + " colocó/invocó " + label + " desde " + from + suffix;
            }
            case LIFE_STACK: {
                String pos = "top".equals(toPosition) ? "tope" : "fondo";
                return who + " añadió " + (card.isFaceDown() ? "una carta oculta" : label) + " al " + pos + " de la vida desde " + from;
            }
            case HAND: {
                boolean fromPublicZone = Zone.FIELD.is(fromZone) || Zone.TRIBUTE_ZONE.is(fromZone) || Zone.DISCARD_PILE.is(fromZone);
                String what = (fromPublicZone && !card.isFaceDown()) ? label : "una carta";
                return who + " añadió " + what + " a la mano desde " + from;
            }
            case TRIBUTE_ZONE:
                return who + " envió " + (card.isFaceDown() ? "una carta" : label) + " a Tributo desde " + from;
            case DISCARD_PILE:
                return who + " envió " + (card.isFaceDown() ? "una carta" : label) + " a Descarte desde " + from;
            case DECK: {
                String pos = "top".equals(toPosition) ? "tope" : "fondo";
                String what = wasPublic ? label : "una carta oculta";
                return who + " envió " + what + " al " + pos + " del mazo desde " + from;
            }
            case null, default:
                return null;
        }
    }

    static String cardLabel(CardSlot card) {
        return card.getCardName() != null ? card.getCardName() : "Carta";
    }

    static String zoneLabel(String zone) {
        return Zone.logLabel(zone);
    }

    static GameStateUpdate handleFlip(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);

        String instanceId = action.getString("instanceId");
        String zoneName   = action.getString("zone");
        List<CardSlot> zone = getZone(player, zoneName);
        if (zone == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.INVALID_ZONE);

        GameStateUpdate update = GameStateUpdate.from(room, ActionType.FLIP_CARD, userId);
        zone.stream()
                .filter(c -> instanceId.equals(c.getInstanceId()))
                .findFirst()
                .ifPresent(c -> {
                    boolean nowFaceDown = !c.isFaceDown();
                    c.setFaceDown(nowFaceDown);
                    if (Zone.FIELD.is(zoneName) && nowFaceDown) {
                        update.setLogEntry(player.getUsername() + " puso boca abajo a " + cardLabel(c) + " en el campo");
                    } else if (Zone.LIFE_STACK.is(zoneName) && !nowFaceDown) {
                        update.setLogEntry(player.getUsername() + " reveló " + cardLabel(c) + " en su vida");
                    } else if (Zone.LIFE_STACK.is(zoneName)) {
                        update.setLogEntry(player.getUsername() + " ocultó a " + cardLabel(c) + " en su vida");
                    } else if (Zone.TRIBUTE_ZONE.is(zoneName) || Zone.DISCARD_PILE.is(zoneName)) {
                        String zoneWord = Zone.TRIBUTE_ZONE.is(zoneName) ? "Tributo" : "Descarte";
                        update.setLogEntry(player.getUsername() + (nowFaceDown ? " ocultó a " : " reveló a ")
                                + cardLabel(c) + " en " + zoneWord);
                    }
                });

        return update;
    }

    static GameStateUpdate handleShuffle(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        GameRandom.shuffle(player.getDeck(), room.getRandom());
        GameStateUpdate update = GameStateUpdate.from(room, ActionType.SHUFFLE_DECK, userId);
        update.setLogEntry(player.getUsername() + " barajó su mazo");
        return update;
    }

    /** Broadcast-only: the action of peeking is public, the cards seen are not. */
    static GameStateUpdate handleViewDeck(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        String position = action.getString("position");
        int n = action.getInt("n", 1);
        GameStateUpdate update = GameStateUpdate.from(room, ActionType.VIEW_DECK, userId);
        String pos = "bottom".equals(position) ? "fondo" : "tope";
        update.setLogEntry(player.getUsername() + " vio el " + pos + " " + n + " del mazo");
        return update;
    }

    /** Broadcast-only: the action of viewing the life pile is public, hidden cards stay hidden. */
    static GameStateUpdate handleViewLifeStack(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        GameStateUpdate update = GameStateUpdate.from(room, ActionType.VIEW_LIFE_STACK, userId);
        update.setLogEntry(player.getUsername() + " vio su pila de vida");
        return update;
    }

    /** Broadcast-only: the peek action is public, the specific card peeked is not. */
    static GameStateUpdate handleViewLifeCard(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        GameStateUpdate update = GameStateUpdate.from(room, ActionType.VIEW_LIFE_CARD, userId);
        update.setLogEntry(player.getUsername() + " vio una carta oculta de su vida");
        return update;
    }

    /**
     * Bulk-sends a selection of cards from any zone to the bottom of the deck in
     * ONE atomic server-side operation — replaces the previous approach of firing
     * one MOVE_CARD per selected card back-to-back from the client (which raced
     * client-side optimistic mutation against async per-card server responses).
     * `instanceIds` must already be in the desired base order (the zone-list
     * order); this handler only decides whether to additionally shuffle it.
     */
    static GameStateUpdate handleBulkToDeckBottom(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        String zoneName = action.getString("zone");
        List<CardSlot> zone = getZone(player, zoneName);
        if (zone == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.INVALID_ZONE);

        @SuppressWarnings("unchecked")
        List<String> instanceIds = (List<String>) action.getPayload().get("instanceIds");
        boolean random = action.getBoolean("random", false);
        if (instanceIds == null || instanceIds.isEmpty())
            return GameStateUpdate.error(room.getRoomId(), SimMessages.NO_CARDS);

        Map<String, CardSlot> byId = new LinkedHashMap<>();
        for (String id : instanceIds) {
            zone.stream().filter(c -> id.equals(c.getInstanceId())).findFirst().ifPresent(c -> byId.put(id, c));
        }
        if (byId.isEmpty()) return GameStateUpdate.error(room.getRoomId(), SimMessages.CARDS_NOT_FOUND);
        zone.removeAll(byId.values());

        List<CardSlot> ordered = new ArrayList<>(byId.values());
        if (random) GameRandom.shuffle(ordered, room.getRandom());
        ordered.forEach(c -> { c.setFaceDown(true); c.setTapped(false); c.setX(null); c.setY(null); });
        player.getDeck().addAll(ordered);

        GameStateUpdate update = GameStateUpdate.from(room, ActionType.BULK_TO_DECK_BOTTOM, userId);
        update.setLogEntry(player.getUsername() + " envió " + ordered.size() + " carta(s) desde " + zoneLabel(zoneName)
                + " al fondo del mazo en orden " + (random ? "aleatorio" : "de la lista"));
        return update;
    }

    /**
     * Moves a selection of cards from one zone to another in one action (instead of one MOVE_CARD per card),
     * each as MOVE_CARD would; one line of history for all of them.
     */
    static GameStateUpdate handleBulkMove(GameRoom room, String userId, GameAction action) {
        List<String> instanceIds = action.getStringList("instanceIds");
        if (instanceIds == null || instanceIds.isEmpty()) return GameStateUpdate.error(room.getRoomId(), SimMessages.NO_CARDS);
        int moved = 0;
        for (String id : instanceIds) {
            Map<String, Object> payload = new HashMap<>(action.getPayload());
            payload.remove("instanceIds");
            payload.put("instanceId", id);
            GameAction one = new GameAction();
            one.setType("MOVE_CARD");
            one.setPayload(payload);
            if (handleMove(room, userId, one).getError() == null) moved++;
        }
        if (moved == 0) return GameStateUpdate.error(room.getRoomId(), SimMessages.CARDS_NOT_FOUND);
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        GameStateUpdate update = GameStateUpdate.from(room, ActionType.BULK_MOVE, userId);
        update.setLogEntry(player.getUsername() + " envió " + moved + " carta(s) desde " + zoneLabel(action.getString("fromZone"))
                + " a " + zoneLabel(action.getString("toZone")));
        return update;
    }

    static GameStateUpdate handleLifeHeal(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        if (player.getDeck().isEmpty()) return GameStateUpdate.error(room.getRoomId(), SimMessages.DECK_EMPTY);
        CardSlot card = player.getDeck().remove(0);
        card.setFaceDown(true);
        player.getLifeStack().add(0, card);
        GameStateUpdate update = GameStateUpdate.from(room, ActionType.LIFE_HEAL, userId);
        update.setLogEntry(player.getUsername() + " curó vida (tope del mazo)");
        return update;
    }

    static GameStateUpdate handleLifeShuffle(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        GameRandom.shuffle(player.getLifeStack(), room.getRandom());
        GameStateUpdate update = GameStateUpdate.from(room, ActionType.LIFE_SHUFFLE, userId);
        update.setLogEntry(player.getUsername() + " barajó su vida");
        return update;
    }

    static GameStateUpdate handleLifeAllDown(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        player.getLifeStack().forEach(c -> c.setFaceDown(true));
        GameStateUpdate update = GameStateUpdate.from(room, ActionType.LIFE_ALL_DOWN, userId);
        update.setLogEntry(player.getUsername() + " ocultó toda su vida");
        return update;
    }

    static GameStateUpdate handleLifeAllUp(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        player.getLifeStack().forEach(c -> c.setFaceDown(false));
        GameStateUpdate update = GameStateUpdate.from(room, ActionType.LIFE_ALL_UP, userId);
        update.setLogEntry(player.getUsername() + " reveló toda su vida");
        return update;
    }

    static GameStateUpdate handleLifeRestart(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        if (!player.getLifeStack().isEmpty()) return GameStateUpdate.error(room.getRoomId(), SimMessages.LIFE_STACK_NOT_EMPTY);
        int count = Math.min(6, player.getDeck().size());
        for (int i = 0; i < count; i++) {
            CardSlot card = player.getDeck().remove(0);
            card.setFaceDown(true);
            player.getLifeStack().add(0, card);
        }
        GameStateUpdate update = GameStateUpdate.from(room, ActionType.LIFE_RESTART, userId);
        update.setLogEntry(player.getUsername() + " reinició su vida (+" + count + " del mazo)");
        return update;
    }

    static GameStateUpdate handleDeckReorderTop(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);

        @SuppressWarnings("unchecked")
        List<String> instanceIds = (List<String>) action.getPayload().get("instanceIds");
        String position = action.getString("position");
        boolean shuffle = Boolean.TRUE.equals(action.getPayload().get("shuffle"));

        if (instanceIds == null || instanceIds.isEmpty())
            return GameStateUpdate.error(room.getRoomId(), SimMessages.NO_CARDS);

        Map<String, CardSlot> byId = new LinkedHashMap<>();
        for (String id : instanceIds) {
            player.getDeck().stream()
                    .filter(c -> id.equals(c.getInstanceId()))
                    .findFirst()
                    .ifPresent(c -> byId.put(id, c));
        }
        player.getDeck().removeAll(byId.values());

        List<CardSlot> ordered = new ArrayList<>(byId.values());
        if (shuffle) GameRandom.shuffle(ordered, room.getRandom());

        if ("bottom".equals(position)) {
            player.getDeck().addAll(ordered);
        } else {
            for (int i = ordered.size() - 1; i >= 0; i--) {
                player.getDeck().add(0, ordered.get(i));
            }
        }
        GameStateUpdate update = GameStateUpdate.from(room, ActionType.DECK_REORDER_TOP, userId);
        String pos = "bottom".equals(position) ? "fondo" : "tope";
        update.setLogEntry(player.getUsername() + " devolvió " + ordered.size() + " carta(s) al " + pos + " del mazo");
        return update;
    }

    static GameStateUpdate handleZoneReorder(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);

        String zoneName = action.getString("zone");
        @SuppressWarnings("unchecked")
        List<String> instanceIds = (List<String>) action.getPayload().get("instanceIds");

        List<CardSlot> zone = getZone(player, zoneName);
        if (zone == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.INVALID_ZONE);
        if (instanceIds == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.NO_ORDER);

        Map<String, CardSlot> cardMap = new LinkedHashMap<>();
        for (CardSlot c : zone) cardMap.put(c.getInstanceId(), c);

        List<CardSlot> reordered = instanceIds.stream()
                .map(cardMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        zone.clear();
        zone.addAll(reordered);

        return GameStateUpdate.from(room, ActionType.ZONE_REORDER, userId);
    }

    static GameStateUpdate handleDeckIncludeDiscard(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        List<CardSlot> discard = new ArrayList<>(player.getDiscardPile());
        String names = discard.stream()
                .map(c -> c.isFaceDown() ? "una carta oculta" : cardLabel(c))
                .collect(Collectors.joining(", "));
        GameRandom.shuffle(discard, room.getRandom());
        discard.forEach(c -> c.setFaceDown(true));
        player.getDiscardPile().clear();
        player.getDeck().addAll(discard);
        GameStateUpdate update = GameStateUpdate.from(room, ActionType.DECK_INCLUDE_DISCARD, userId);
        update.setLogEntry(player.getUsername() + " mezcló el descarte con el mazo"
                + (names.isEmpty() ? "" : ": " + names));
        return update;
    }

    static GameStateUpdate handleDeckIncludeTribute(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        List<CardSlot> tribute = new ArrayList<>(player.getTributeZone());
        String names = tribute.stream()
                .map(c -> c.isFaceDown() ? "una carta oculta" : cardLabel(c))
                .collect(Collectors.joining(", "));
        GameRandom.shuffle(tribute, room.getRandom());
        tribute.forEach(c -> c.setFaceDown(true));
        player.getTributeZone().clear();
        player.getDeck().addAll(tribute);
        GameStateUpdate update = GameStateUpdate.from(room, ActionType.DECK_INCLUDE_TRIBUTE, userId);
        update.setLogEntry(player.getUsername() + " mezcló el tributo con el mazo"
                + (names.isEmpty() ? "" : ": " + names));
        return update;
    }

    static GameStateUpdate handleRevealToHand(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        String instanceId = action.getString("instanceId");
        CardSlot card = player.getDeck().stream()
                .filter(c -> instanceId.equals(c.getInstanceId()))
                .findFirst().orElse(null);
        if (card == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.CARD_NOT_IN_DECK);
        player.getDeck().remove(card);
        card.setFaceDown(false);
        player.getHand().add(card);
        GameStateUpdate update = GameStateUpdate.from(room, ActionType.REVEAL_TO_HAND, userId);
        update.setRevealedCardImageUrl(card.getImageUrl());
        update.setRevealedCardName(card.getCardName());
        update.setLogEntry(player.getUsername() + " reveló " + cardLabel(card) + " y la añadió a la mano");
        return update;
    }

    static List<CardSlot> getZone(PlayerState player, String zoneName) {
        return Zone.parse(zoneName).map(z -> z.of(player)).orElse(null);
    }
}
