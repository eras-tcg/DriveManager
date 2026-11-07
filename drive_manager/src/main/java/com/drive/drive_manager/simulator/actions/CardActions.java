package com.drive.drive_manager.simulator.actions;

import com.drive.drive_manager.simulator.engine.Removals;
import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.CardLocator;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.PlayerState;
import com.drive.drive_manager.simulator.room.RoomGuard;
import com.drive.drive_manager.simulator.room.SimMessages;
import com.drive.drive_manager.simulator.room.Zone;

import java.util.*;
import java.util.stream.Collectors;

import static com.drive.drive_manager.simulator.actions.RulesActions.*;
import static com.drive.drive_manager.simulator.actions.TurnActions.*;
import static com.drive.drive_manager.simulator.actions.PreRoomActions.*;
import static com.drive.drive_manager.simulator.actions.ZoneActions.*;

/** Changing a card on the field by hand (editor mode): state, materials and Recursos. */
final class CardActions {

    private CardActions() {}

    /** "material" or "materials" (the page sends the plural, as the attachment lists are named); else resources. */
    static boolean isMaterial(String attachmentType) {
        return attachmentType != null && attachmentType.startsWith("material");
    }

    static GameStateUpdate handleTapCard(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.notInRoom(room);

        String instanceId = action.getString("instanceId");
        String zoneName   = action.getString("zone");
        List<CardSlot> zone = getZone(player, zoneName);
        if (zone == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.INVALID_ZONE);

        zone.stream()
                .filter(c -> instanceId.equals(c.getInstanceId()))
                .findFirst()
                .ifPresent(c -> c.setTapped(!c.isTapped()));

        return GameStateUpdate.from(room, ActionType.TAP_CARD, userId);
    }

    static GameStateUpdate handleModifyStrength(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        String instanceId = action.getString("instanceId");
        int delta = Math.max(-RoomGuard.MAX_STRENGTH_CHANGE, Math.min(RoomGuard.MAX_STRENGTH_CHANGE, action.getInt("delta", 0)));
        CardSlot card = player.getField().stream().filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (card == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.CARD_NOT_FOUND);
        card.setStrengthModifier(card.getStrengthModifier() + delta);
        // Strength 0: destroyed right away (rule 12.0.0.2)
        Removals.destroyIfNoStrength(room, userId, player, card);
        return GameStateUpdate.from(room, ActionType.MODIFY_STRENGTH, userId);
    }

    static GameStateUpdate handleAddMaterial(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        String instanceId      = action.getString("instanceId");
        String fromZone        = action.getString("fromZone");
        String targetId        = action.getString("targetInstanceId");
        String attachChoice    = action.getString("attachmentChoice"); // all|resources_only|materials_only|discard_all

        List<CardSlot> from = getZone(player, fromZone);
        if (from == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.INVALID_ZONE);
        CardSlot card = from.stream().filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (card == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.CARD_NOT_FOUND);
        CardSlot target = player.getField().stream().filter(c -> targetId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (target == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.TARGET_NOT_FOUND);

        from.remove(card);

        if (attachChoice != null && (!card.getMaterials().isEmpty() || !card.getResources().isEmpty())) {
            switch (attachChoice) {
                case "resources_only" -> {
                    target.getResources().addAll(card.getResources());
                    card.getMaterials().forEach(m -> { m.setFaceDown(false); player.getDiscardPile().add(m); });
                }
                case "materials_only" -> {
                    target.getMaterials().addAll(card.getMaterials());
                    card.getResources().forEach(r -> { r.setFaceDown(false); player.getDiscardPile().add(r); });
                }
                case "all" -> {
                    target.getMaterials().addAll(card.getMaterials());
                    target.getResources().addAll(card.getResources());
                }
                default -> {
                    card.getMaterials().forEach(m -> { m.setFaceDown(false); player.getDiscardPile().add(m); });
                    card.getResources().forEach(r -> { r.setFaceDown(false); player.getDiscardPile().add(r); });
                }
            }
        }
        card.setMaterials(new ArrayList<>());
        card.setResources(new ArrayList<>());
        card.setFaceDown(false);
        card.setTapped(false);
        card.setX(null);
        card.setY(null);
        target.getMaterials().add(card);
        return GameStateUpdate.from(room, ActionType.ADD_MATERIAL, userId);
    }

    static GameStateUpdate handleAddResource(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        String instanceId = action.getString("instanceId");
        String fromZone   = action.getString("fromZone");
        String targetId   = action.getString("targetInstanceId");

        List<CardSlot> from = getZone(player, fromZone);
        if (from == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.INVALID_ZONE);
        CardSlot card = from.stream().filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (card == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.CARD_NOT_FOUND);
        CardSlot target = player.getField().stream().filter(c -> targetId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (target == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.TARGET_NOT_FOUND);

        from.remove(card);
        // If the card was on field, flatten its own attachments to discard
        if (Zone.FIELD.is(fromZone)) {
            card.getMaterials().forEach(m -> { m.setFaceDown(false); player.getDiscardPile().add(m); });
            card.getResources().forEach(r -> { r.setFaceDown(false); player.getDiscardPile().add(r); });
            card.setMaterials(new ArrayList<>());
            card.setResources(new ArrayList<>());
            boolean faceDown = card.isFaceDown();
            com.drive.drive_manager.simulator.engine.Removals.leaveField(card);
            card.setFaceDown(faceDown);
        }
        card.setFaceDown(false);
        card.setTapped(false);
        card.setX(null);
        card.setY(null);
        target.getResources().add(card);
        return GameStateUpdate.from(room, ActionType.ADD_RESOURCE, userId);
    }

    static GameStateUpdate handleMoveFromAttachment(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        String instanceId      = action.getString("instanceId");
        String attachmentOf    = action.getString("attachmentOf");
        String attachmentType  = action.getString("attachmentType"); // material(s) | resource(s)
        String toZone          = action.getString("toZone");

        CardSlot parent = player.getField().stream().filter(c -> attachmentOf.equals(c.getInstanceId())).findFirst().orElse(null);
        if (parent == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.HOLDER_NOT_FOUND);

        List<CardSlot> list = isMaterial(attachmentType) ? parent.getMaterials() : parent.getResources();
        CardSlot card = list.stream().filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (card == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.CARD_NOT_FOUND);

        list.remove(card);
        card.setFaceDown(action.getBoolean("faceDown", false));
        card.setTapped(false);
        List<CardSlot> dest = getZone(player, toZone);
        if (dest == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.INVALID_ZONE);
        if (Zone.FIELD.is(toZone)) {
            card.setX(parent.getX() != null ? parent.getX() + 5 : 50.0);
            card.setY(parent.getY() != null ? parent.getY() + 5 : 50.0);
        }
        if ("top".equals(action.getString("position"))) {
            dest.add(0, card);
        } else {
            dest.add(card);
        }
        return GameStateUpdate.from(room, ActionType.MOVE_FROM_ATTACHMENT, userId);
    }

    static GameStateUpdate handleSwapWithMain(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.PLAYER_NOT_FOUND);
        String instanceId     = action.getString("instanceId");
        String attachmentType = action.getString("attachmentType");
        String parentId       = action.getString("parentId");

        CardSlot parent = player.getField().stream()
                .filter(c -> parentId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (parent == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.HOLDER_NOT_FOUND);

        List<CardSlot> list = isMaterial(attachmentType) ? parent.getMaterials() : parent.getResources();
        CardSlot attachment = list.stream()
                .filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (attachment == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.ATTACHMENT_NOT_FOUND);

        // Remove attachment from its list
        list.remove(attachment);

        // Attachment inherits parent's field state
        attachment.setX(parent.getX());
        attachment.setY(parent.getY());
        attachment.setTapped(parent.isTapped());
        attachment.setFaceDown(parent.isFaceDown());
        attachment.setStrengthModifier(parent.getStrengthModifier());

        // Collect parent's current attachments (parent will go back as attachment)
        List<CardSlot> inheritedMaterials = new ArrayList<>(parent.getMaterials());
        List<CardSlot> inheritedResources = new ArrayList<>(parent.getResources());
        // Clear parent before it becomes an attachment
        parent.setMaterials(new ArrayList<>());
        parent.setResources(new ArrayList<>());
        parent.setX(null); parent.setY(null);
        parent.setTapped(false); parent.setFaceDown(false); parent.setStrengthModifier(0);

        // Parent goes into the same attachment type it came from
        if (isMaterial(attachmentType)) {
            inheritedMaterials.add(parent);
            attachment.setMaterials(inheritedMaterials);
            attachment.setResources(inheritedResources);
        } else {
            inheritedResources.add(parent);
            attachment.setMaterials(inheritedMaterials);
            attachment.setResources(inheritedResources);
        }

        // Replace parent with attachment on field
        int idx = player.getField().indexOf(parent);
        player.getField().remove(parent);
        if (idx >= 0) player.getField().add(idx, attachment);
        else player.getField().add(attachment);

        return GameStateUpdate.from(room, ActionType.SWAP_WITH_MAIN, userId);
    }

    static GameStateUpdate handleTargetCard(GameRoom room, String userId, GameAction action) {
        String targetInstanceId = action.getString("targetInstanceId");
        String sourceInstanceId = action.getString("sourceInstanceId");
        GameStateUpdate update = GameStateUpdate.from(room, ActionType.TARGET_CARD, userId);
        update.setTargetedInstanceId(targetInstanceId);
        update.setTargetingSourceInstanceId(sourceInstanceId);
        return update;
    }

    static CardSlot findInstanceAnywhere(PlayerState player, String instanceId) {
        return CardLocator.find(player, instanceId, CardLocator.MANUAL_ORDER, true);
    }

    static GameStateUpdate handleRemoveCard(GameRoom room, String userId, GameAction action) {
        String instanceId = action.getString("instanceId");
        if (instanceId == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.NO_CARD);
        String targetPlayerId = action.getString("targetPlayerId");
        PlayerState player = targetPlayerId != null ? room.getPlayer(targetPlayerId) : room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);

        List<List<CardSlot>> zones = List.of(
                player.getHand(), player.getDeck(), player.getField(),
                player.getLifeStack(), player.getTributeZone(), player.getDiscardPile());
        boolean removed = false;
        for (List<CardSlot> zone : zones) {
            if (zone.removeIf(c -> instanceId.equals(c.getInstanceId()))) { removed = true; break; }
        }
        if (!removed) {
            outer:
            for (List<CardSlot> zone : zones) {
                for (CardSlot c : zone) {
                    if (c.getMaterials().removeIf(m -> instanceId.equals(m.getInstanceId()))) { removed = true; break outer; }
                    if (c.getResources().removeIf(r -> instanceId.equals(r.getInstanceId()))) { removed = true; break outer; }
                }
            }
        }
        if (!removed) return GameStateUpdate.error(room.getRoomId(), SimMessages.CARD_NOT_FOUND);
        return GameStateUpdate.from(room, ActionType.REMOVE_CARD, userId);
    }

    static GameStateUpdate handleRestoreCard(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        String instanceId = action.getString("instanceId");
        if (instanceId == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.NO_CARD);
        if (findInstanceAnywhere(player, instanceId) != null) {
            return GameStateUpdate.error(room.getRoomId(), "La carta ya está en el juego");
        }
        CardSlot card = new CardSlot();
        card.setInstanceId(instanceId);
        card.setCardId(action.getString("cardId"));
        card.setImageUrl(action.getString("imageUrl"));
        card.setCardName(action.getString("cardName"));
        card.setCardType(action.getString("cardType"));
        Object rawStrength = action.getPayload().get("strength");
        if (rawStrength instanceof Number n) card.setStrength(n.intValue());
        card.setSpecialSummonKind(action.getString("specialSummonKind"));
        card.setFaceDown(false);
        player.getHand().add(card);
        return GameStateUpdate.from(room, ActionType.RESTORE_CARD, userId);
    }

    static GameStateUpdate handleReorderAttachment(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.notInRoom(room);

        String parentId       = action.getString("parentId");
        String attachmentType = action.getString("attachmentType");
        @SuppressWarnings("unchecked")
        List<String> instanceIds = (List<String>) action.getPayload().get("instanceIds");
        if (instanceIds == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.NO_ORDER);

        CardSlot parent = player.getField().stream()
                .filter(c -> parentId.equals(c.getInstanceId()))
                .findFirst().orElse(null);
        if (parent == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.CARD_NOT_FOUND);

        List<CardSlot> attachments = isMaterial(attachmentType)
                ? parent.getMaterials() : parent.getResources();

        Map<String, CardSlot> map = new LinkedHashMap<>();
        for (CardSlot c : attachments) map.put(c.getInstanceId(), c);

        List<CardSlot> reordered = instanceIds.stream()
                .map(map::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        attachments.clear();
        attachments.addAll(reordered);

        return GameStateUpdate.from(room, ActionType.REORDER_ATTACHMENT, userId);
    }

    static GameStateUpdate handleReclassifyAttachment(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.notInRoom(room);

        String parentId   = action.getString("parentId");
        String instanceId = action.getString("instanceId");
        String fromType   = action.getString("fromType"); // "material(s)" | "resource(s)"

        CardSlot parent = player.getField().stream()
                .filter(c -> parentId.equals(c.getInstanceId()))
                .findFirst().orElse(null);
        if (parent == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.HOLDER_NOT_FOUND);

        List<CardSlot> fromList = isMaterial(fromType) ? parent.getMaterials() : parent.getResources();
        List<CardSlot> toList   = isMaterial(fromType) ? parent.getResources() : parent.getMaterials();

        CardSlot card = fromList.stream()
                .filter(c -> instanceId.equals(c.getInstanceId()))
                .findFirst().orElse(null);
        if (card == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.ATTACHMENT_NOT_FOUND);

        fromList.remove(card);
        toList.add(card);

        return GameStateUpdate.from(room, ActionType.RECLASSIFY_ATTACHMENT, userId);
    }

    /** A test card (test rooms only): made from what the owner typed, in the zone they chose. */
    /**
     * Test room: a card made up with the properties given (or copied from a real card: its name gives it that
     * card's effects), placed in a zone of the side being played. It is placed, not summoned or played.
     */
    static GameStateUpdate handleCreateDummy(GameRoom room, String userId, GameAction action) {
        if (!room.isEditor()) return GameStateUpdate.error(room.getRoomId(), "Las cartas de prueba solo existen en una sala de prueba");
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        String zoneName = action.getString("zone");
        List<CardSlot> zone = getZone(player, zoneName);
        if (zone == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.INVALID_ZONE);
        String name = action.getString("cardName");
        if (name == null || name.isBlank()) return GameStateUpdate.error(room.getRoomId(), "La carta necesita un nombre");

        CardSlot card = new CardSlot();
        card.setInstanceId("dummy-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        card.setCardId(action.getString("cardId") != null ? action.getString("cardId") : "dummy");
        card.setImageUrl(action.getString("imageUrl"));
        card.setCardName(name.trim());
        card.setCardType(action.getString("cardType") != null ? action.getString("cardType") : "creature");
        card.setStrength(integer(action, "strength"));
        card.setCost(integer(action, "cost"));
        card.setLevel(integer(action, "level"));
        card.setSpecialCost(integer(action, "specialCost"));
        card.setSpecialSummonKind(action.getString("specialSummonKind"));
        card.setRequirement(action.getString("requirement"));
        card.setStarter(action.getBoolean("starter", false));
        card.setColors(new ArrayList<>(action.getStringList("colors")));
        card.setClasses(new ArrayList<>(action.getStringList("classes")));
        card.setKeywords(new ArrayList<>(action.getStringList("keywords")));
        boolean hidden = Zone.DECK.is(zoneName) || Zone.LIFE_STACK.is(zoneName);
        card.setFaceDown(action.getBoolean("faceDown", hidden));
        if (Zone.FIELD.is(zoneName)) {
            Double x = action.getDouble("x");
            Double y = action.getDouble("y");
            card.setX(x != null ? x : 50.0);
            // The Retaguardia unless placed in the Línea frontal
            card.setY(y != null ? y : 75.0);
            card.setFieldSeq(room.getEventSeq() + 1);
        }
        if ("top".equals(action.getString("position"))) zone.add(0, card);
        else zone.add(card);

        GameStateUpdate update = GameStateUpdate.from(room, ActionType.CREATE_DUMMY, userId);
        update.setLogEntry(player.getUsername() + " crea la carta de prueba " + card.getCardName() + " en " + zoneLabel(zoneName));
        return update;
    }

    static Integer integer(GameAction action, String key) {
        Double value = action.getDouble(key);
        return value == null ? null : value.intValue();
    }
}
