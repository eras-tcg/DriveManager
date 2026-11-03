package com.drive.drive_manager.simulator;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.repository.DeckListRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.simulator.engine.CardEffects;
import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.engine.TurnRules;
import com.drive.drive_manager.simulator.engine.Summons;
import com.drive.drive_manager.simulator.engine.Removals;
import com.drive.drive_manager.simulator.engine.CombatRules;
import com.drive.drive_manager.simulator.engine.Chain;
import com.drive.drive_manager.simulator.engine.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.drive.drive_manager.simulator.RoomService.*;
import static com.drive.drive_manager.simulator.RulesActions.*;
import static com.drive.drive_manager.simulator.TurnActions.*;
import static com.drive.drive_manager.simulator.PreRoomActions.*;
import static com.drive.drive_manager.simulator.ZoneActions.*;

/** Changing a card on the field by hand (editor mode): state, materials and Recursos. */
final class CardActions {

    private CardActions() {}

    static GameStateUpdate handleTapCard(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");

        String instanceId = action.getString("instanceId");
        String zoneName   = action.getString("zone");
        List<CardSlot> zone = getZone(player, zoneName);
        if (zone == null) return GameStateUpdate.error(room.getRoomId(), "Invalid zone");

        zone.stream()
                .filter(c -> instanceId.equals(c.getInstanceId()))
                .findFirst()
                .ifPresent(c -> c.setTapped(!c.isTapped()));

        return GameStateUpdate.from(room, "TAP_CARD", userId);
    }

    static GameStateUpdate handleModifyStrength(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        String instanceId = action.getString("instanceId");
        int delta = Math.max(-RoomGuard.MAX_STRENGTH_CHANGE, Math.min(RoomGuard.MAX_STRENGTH_CHANGE, action.getInt("delta", 0)));
        CardSlot card = player.getField().stream().filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (card == null) return GameStateUpdate.error(room.getRoomId(), "Card not found");
        card.setStrengthModifier(card.getStrengthModifier() + delta);
        // Strength 0: destroyed right away (rule 12.0.0.2)
        Removals.destroyIfNoStrength(room, userId, player, card);
        return GameStateUpdate.from(room, "MODIFY_STRENGTH", userId);
    }

    static GameStateUpdate handleAddMaterial(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        String instanceId      = action.getString("instanceId");
        String fromZone        = action.getString("fromZone");
        String targetId        = action.getString("targetInstanceId");
        String attachChoice    = action.getString("attachmentChoice"); // all|resources_only|materials_only|discard_all

        List<CardSlot> from = getZone(player, fromZone);
        if (from == null) return GameStateUpdate.error(room.getRoomId(), "Invalid zone");
        CardSlot card = from.stream().filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (card == null) return GameStateUpdate.error(room.getRoomId(), "Card not found");
        CardSlot target = player.getField().stream().filter(c -> targetId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (target == null) return GameStateUpdate.error(room.getRoomId(), "Target not found");

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
        return GameStateUpdate.from(room, "ADD_MATERIAL", userId);
    }

    static GameStateUpdate handleAddResource(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        String instanceId = action.getString("instanceId");
        String fromZone   = action.getString("fromZone");
        String targetId   = action.getString("targetInstanceId");

        List<CardSlot> from = getZone(player, fromZone);
        if (from == null) return GameStateUpdate.error(room.getRoomId(), "Invalid zone");
        CardSlot card = from.stream().filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (card == null) return GameStateUpdate.error(room.getRoomId(), "Card not found");
        CardSlot target = player.getField().stream().filter(c -> targetId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (target == null) return GameStateUpdate.error(room.getRoomId(), "Target not found");

        from.remove(card);
        // If the card was on field, flatten its own attachments to discard
        if ("field".equals(fromZone)) {
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
        return GameStateUpdate.from(room, "ADD_RESOURCE", userId);
    }

    static GameStateUpdate handleMoveFromAttachment(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        String instanceId      = action.getString("instanceId");
        String attachmentOf    = action.getString("attachmentOf");
        String attachmentType  = action.getString("attachmentType"); // material(s) | resource(s)
        String toZone          = action.getString("toZone");

        CardSlot parent = player.getField().stream().filter(c -> attachmentOf.equals(c.getInstanceId())).findFirst().orElse(null);
        if (parent == null) return GameStateUpdate.error(room.getRoomId(), "Parent not found");

        List<CardSlot> list = isMaterial(attachmentType) ? parent.getMaterials() : parent.getResources();
        CardSlot card = list.stream().filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (card == null) return GameStateUpdate.error(room.getRoomId(), "Card not found");

        list.remove(card);
        card.setFaceDown(action.getBoolean("faceDown", false));
        card.setTapped(false);
        List<CardSlot> dest = getZone(player, toZone);
        if (dest == null) return GameStateUpdate.error(room.getRoomId(), "Invalid zone");
        if ("field".equals(toZone)) {
            card.setX(parent.getX() != null ? parent.getX() + 5 : 50.0);
            card.setY(parent.getY() != null ? parent.getY() + 5 : 50.0);
        }
        if ("top".equals(action.getString("position"))) {
            dest.add(0, card);
        } else {
            dest.add(card);
        }
        return GameStateUpdate.from(room, "MOVE_FROM_ATTACHMENT", userId);
    }

    static GameStateUpdate handleSwapWithMain(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not found");
        String instanceId     = action.getString("instanceId");
        String attachmentType = action.getString("attachmentType");
        String parentId       = action.getString("parentId");

        CardSlot parent = player.getField().stream()
                .filter(c -> parentId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (parent == null) return GameStateUpdate.error(room.getRoomId(), "Main card not found");

        List<CardSlot> list = isMaterial(attachmentType) ? parent.getMaterials() : parent.getResources();
        CardSlot attachment = list.stream()
                .filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (attachment == null) return GameStateUpdate.error(room.getRoomId(), "Attachment not found");

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

        return GameStateUpdate.from(room, "SWAP_WITH_MAIN", userId);
    }

    static GameStateUpdate handleTargetCard(GameRoom room, String userId, GameAction action) {
        String targetInstanceId = action.getString("targetInstanceId");
        String sourceInstanceId = action.getString("sourceInstanceId");
        GameStateUpdate update = GameStateUpdate.from(room, "TARGET_CARD", userId);
        update.setTargetedInstanceId(targetInstanceId);
        update.setTargetingSourceInstanceId(sourceInstanceId);
        return update;
    }

    static CardSlot findInstanceAnywhere(PlayerState player, String instanceId) {
        List<List<CardSlot>> zones = List.of(
                player.getHand(), player.getDeck(), player.getField(),
                player.getLifeStack(), player.getTributeZone(), player.getDiscardPile());
        for (List<CardSlot> zone : zones) {
            for (CardSlot c : zone) {
                if (instanceId.equals(c.getInstanceId())) return c;
                for (CardSlot m : c.getMaterials()) if (instanceId.equals(m.getInstanceId())) return m;
                for (CardSlot r : c.getResources()) if (instanceId.equals(r.getInstanceId())) return r;
            }
        }
        return null;
    }

    static GameStateUpdate handleRemoveCard(GameRoom room, String userId, GameAction action) {
        String instanceId = action.getString("instanceId");
        if (instanceId == null) return GameStateUpdate.error(room.getRoomId(), "No instanceId");
        String targetPlayerId = action.getString("targetPlayerId");
        PlayerState player = targetPlayerId != null ? room.getPlayer(targetPlayerId) : room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");

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
        if (!removed) return GameStateUpdate.error(room.getRoomId(), "Card not found");
        return GameStateUpdate.from(room, "REMOVE_CARD", userId);
    }

    static GameStateUpdate handleRestoreCard(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        String instanceId = action.getString("instanceId");
        if (instanceId == null) return GameStateUpdate.error(room.getRoomId(), "No instanceId");
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
        return GameStateUpdate.from(room, "RESTORE_CARD", userId);
    }

    static GameStateUpdate handleReorderAttachment(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");

        String parentId       = action.getString("parentId");
        String attachmentType = action.getString("attachmentType");
        @SuppressWarnings("unchecked")
        List<String> instanceIds = (List<String>) action.getPayload().get("instanceIds");
        if (instanceIds == null) return GameStateUpdate.error(room.getRoomId(), "No order specified");

        CardSlot parent = player.getField().stream()
                .filter(c -> parentId.equals(c.getInstanceId()))
                .findFirst().orElse(null);
        if (parent == null) return GameStateUpdate.error(room.getRoomId(), "Card not found");

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

        return GameStateUpdate.from(room, "REORDER_ATTACHMENT", userId);
    }

    static GameStateUpdate handleReclassifyAttachment(GameRoom room, String userId, GameAction action) {
        String tp = action.getString("targetPlayerId");
        PlayerState player = room.getPlayer(tp != null ? tp : userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");

        String parentId   = action.getString("parentId");
        String instanceId = action.getString("instanceId");
        String fromType   = action.getString("fromType"); // "material(s)" | "resource(s)"

        CardSlot parent = player.getField().stream()
                .filter(c -> parentId.equals(c.getInstanceId()))
                .findFirst().orElse(null);
        if (parent == null) return GameStateUpdate.error(room.getRoomId(), "Parent card not found");

        List<CardSlot> fromList = isMaterial(fromType) ? parent.getMaterials() : parent.getResources();
        List<CardSlot> toList   = isMaterial(fromType) ? parent.getResources() : parent.getMaterials();

        CardSlot card = fromList.stream()
                .filter(c -> instanceId.equals(c.getInstanceId()))
                .findFirst().orElse(null);
        if (card == null) return GameStateUpdate.error(room.getRoomId(), "Card not found in attachment");

        fromList.remove(card);
        toList.add(card);

        return GameStateUpdate.from(room, "RECLASSIFY_ATTACHMENT", userId);
    }

    /** A test card (test rooms only): made from what the owner typed, in the zone they chose. */
    /**
     * Test room: a card made up with the properties given (or copied from a real card: its name gives it that
     * card's effects), placed in a zone of the side being played. It is placed, not summoned or played.
     */
    static GameStateUpdate handleCreateDummy(GameRoom room, String userId, GameAction action) {
        if (!room.isEditor()) return GameStateUpdate.error(room.getRoomId(), "Las cartas de prueba solo existen en una sala de prueba");
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Jugador no encontrado");
        String zoneName = action.getString("zone");
        List<CardSlot> zone = getZone(player, zoneName);
        if (zone == null) return GameStateUpdate.error(room.getRoomId(), "Zona no válida");
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
        boolean hidden = "deck".equals(zoneName) || "lifeStack".equals(zoneName);
        card.setFaceDown(action.getBoolean("faceDown", hidden));
        if ("field".equals(zoneName)) {
            Double x = action.getDouble("x");
            Double y = action.getDouble("y");
            card.setX(x != null ? x : 50.0);
            // The Retaguardia unless placed in the Línea frontal
            card.setY(y != null ? y : 75.0);
            card.setFieldSeq(room.getEventSeq() + 1);
        }
        if ("top".equals(action.getString("position"))) zone.add(0, card);
        else zone.add(card);

        GameStateUpdate update = GameStateUpdate.from(room, "CREATE_DUMMY", userId);
        update.setLogEntry(player.getUsername() + " crea la carta de prueba " + card.getCardName() + " en " + zoneLabel(zoneName));
        return update;
    }

    static Integer integer(GameAction action, String key) {
        Double value = action.getDouble(key);
        return value == null ? null : value.intValue();
    }
}
