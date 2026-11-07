package com.drive.drive_manager.simulator.actions

import com.drive.drive_manager.simulator.actions.ZoneActions.getZone
import com.drive.drive_manager.simulator.actions.ZoneActions.zoneLabel
import com.drive.drive_manager.simulator.engine.Removals
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.CardLocator
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.GameStateUpdate
import com.drive.drive_manager.simulator.room.PlayerState
import com.drive.drive_manager.simulator.room.RoomGuard
import com.drive.drive_manager.simulator.room.SimMessages
import com.drive.drive_manager.simulator.room.Zone
import java.util.UUID

/** Changing a card on the field by hand (editor mode): state, materials and Recursos. */
object CardActions {

    /** "material" or "materials" (the page sends the plural, as the attachment lists are named); else resources. */
    @JvmStatic
    fun isMaterial(attachmentType: String?): Boolean {
        return attachmentType != null && attachmentType.startsWith("material")
    }

    @JvmStatic
    fun handleTapCard(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val tp = action.getString("targetPlayerId")
        val player = room.getPlayer(tp ?: userId) ?: return GameStateUpdate.notInRoom(room)

        val instanceId = action.getString("instanceId")
        val zoneName   = action.getString("zone")
        val zone = getZone(player, zoneName) ?: return GameStateUpdate.error(room.roomId, SimMessages.INVALID_ZONE)

        zone.firstOrNull { c -> instanceId!! == c.instanceId }
                ?.let { c -> c.tapped = !c.tapped }

        return GameStateUpdate.from(room, ActionType.TAP_CARD, userId)
    }

    @JvmStatic
    fun handleModifyStrength(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val tp = action.getString("targetPlayerId")
        val player = room.getPlayer(tp ?: userId) ?: return GameStateUpdate.notInRoom(room)
        val instanceId = action.getString("instanceId")
        val delta = Math.max(-RoomGuard.MAX_STRENGTH_CHANGE, Math.min(RoomGuard.MAX_STRENGTH_CHANGE, action.getInt("delta", 0)))
        val card = player.field.firstOrNull { c -> instanceId!! == c.instanceId }
                ?: return GameStateUpdate.error(room.roomId, SimMessages.CARD_NOT_FOUND)
        card.strengthModifier = card.strengthModifier + delta
        // Strength 0: destroyed right away (rule 12.0.0.2)
        Removals.destroyIfNoStrength(room, userId, player, card)
        return GameStateUpdate.from(room, ActionType.MODIFY_STRENGTH, userId)
    }

    @JvmStatic
    fun handleAddMaterial(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        val instanceId      = action.getString("instanceId")
        val fromZone        = action.getString("fromZone")
        val targetId        = action.getString("targetInstanceId")
        val attachChoice    = action.getString("attachmentChoice") // all|resources_only|materials_only|discard_all

        val from = getZone(player, fromZone) ?: return GameStateUpdate.error(room.roomId, SimMessages.INVALID_ZONE)
        val card = from.firstOrNull { c -> instanceId!! == c.instanceId }
                ?: return GameStateUpdate.error(room.roomId, SimMessages.CARD_NOT_FOUND)
        val target = player.field.firstOrNull { c -> targetId!! == c.instanceId }
                ?: return GameStateUpdate.error(room.roomId, SimMessages.TARGET_NOT_FOUND)

        from.remove(card)

        if (attachChoice != null && (!card.materials!!.isEmpty() || !card.resources!!.isEmpty())) {
            when (attachChoice) {
                "resources_only" -> {
                    target.resources!!.addAll(card.resources!!)
                    card.materials!!.forEach { m -> m.faceDown = false; player.discardPile.add(m) }
                }
                "materials_only" -> {
                    target.materials!!.addAll(card.materials!!)
                    card.resources!!.forEach { r -> r.faceDown = false; player.discardPile.add(r) }
                }
                "all" -> {
                    target.materials!!.addAll(card.materials!!)
                    target.resources!!.addAll(card.resources!!)
                }
                else -> {
                    card.materials!!.forEach { m -> m.faceDown = false; player.discardPile.add(m) }
                    card.resources!!.forEach { r -> r.faceDown = false; player.discardPile.add(r) }
                }
            }
        }
        card.materials = ArrayList()
        card.resources = ArrayList()
        card.faceDown = false
        card.tapped = false
        card.x = null
        card.y = null
        target.materials!!.add(card)
        return GameStateUpdate.from(room, ActionType.ADD_MATERIAL, userId)
    }

    @JvmStatic
    fun handleAddResource(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        val instanceId = action.getString("instanceId")
        val fromZone   = action.getString("fromZone")
        val targetId   = action.getString("targetInstanceId")

        val from = getZone(player, fromZone) ?: return GameStateUpdate.error(room.roomId, SimMessages.INVALID_ZONE)
        val card = from.firstOrNull { c -> instanceId!! == c.instanceId }
                ?: return GameStateUpdate.error(room.roomId, SimMessages.CARD_NOT_FOUND)
        val target = player.field.firstOrNull { c -> targetId!! == c.instanceId }
                ?: return GameStateUpdate.error(room.roomId, SimMessages.TARGET_NOT_FOUND)

        from.remove(card)
        // If the card was on field, flatten its own attachments to discard
        if (Zone.FIELD.`is`(fromZone)) {
            card.materials!!.forEach { m -> m.faceDown = false; player.discardPile.add(m) }
            card.resources!!.forEach { r -> r.faceDown = false; player.discardPile.add(r) }
            card.materials = ArrayList()
            card.resources = ArrayList()
            val faceDown = card.faceDown
            com.drive.drive_manager.simulator.engine.Removals.leaveField(card)
            card.faceDown = faceDown
        }
        card.faceDown = false
        card.tapped = false
        card.x = null
        card.y = null
        target.resources!!.add(card)
        return GameStateUpdate.from(room, ActionType.ADD_RESOURCE, userId)
    }

    @JvmStatic
    fun handleMoveFromAttachment(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val tp = action.getString("targetPlayerId")
        val player = room.getPlayer(tp ?: userId) ?: return GameStateUpdate.notInRoom(room)
        val instanceId      = action.getString("instanceId")
        val attachmentOf    = action.getString("attachmentOf")
        val attachmentType  = action.getString("attachmentType") // material(s) | resource(s)
        val toZone          = action.getString("toZone")

        val parent = player.field.firstOrNull { c -> attachmentOf!! == c.instanceId }
                ?: return GameStateUpdate.error(room.roomId, SimMessages.HOLDER_NOT_FOUND)

        val list = (if (isMaterial(attachmentType)) parent.materials else parent.resources)!!
        val card = list.firstOrNull { c -> instanceId!! == c.instanceId }
                ?: return GameStateUpdate.error(room.roomId, SimMessages.CARD_NOT_FOUND)

        list.remove(card)
        card.faceDown = action.getBoolean("faceDown", false)
        card.tapped = false
        val dest = getZone(player, toZone) ?: return GameStateUpdate.error(room.roomId, SimMessages.INVALID_ZONE)
        if (Zone.FIELD.`is`(toZone)) {
            card.x = if (parent.x != null) parent.x!! + 5 else 50.0
            card.y = if (parent.y != null) parent.y!! + 5 else 50.0
        }
        if ("top" == action.getString("position")) {
            dest.add(0, card)
        } else {
            dest.add(card)
        }
        return GameStateUpdate.from(room, ActionType.MOVE_FROM_ATTACHMENT, userId)
    }

    @JvmStatic
    fun handleSwapWithMain(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val tp = action.getString("targetPlayerId")
        val player = room.getPlayer(tp ?: userId) ?: return GameStateUpdate.error(room.roomId, SimMessages.PLAYER_NOT_FOUND)
        val instanceId     = action.getString("instanceId")
        val attachmentType = action.getString("attachmentType")
        val parentId       = action.getString("parentId")

        val parent = player.field
                .firstOrNull { c -> parentId!! == c.instanceId }
                ?: return GameStateUpdate.error(room.roomId, SimMessages.HOLDER_NOT_FOUND)

        val list = (if (isMaterial(attachmentType)) parent.materials else parent.resources)!!
        val attachment = list
                .firstOrNull { c -> instanceId!! == c.instanceId }
                ?: return GameStateUpdate.error(room.roomId, SimMessages.ATTACHMENT_NOT_FOUND)

        // Remove attachment from its list
        list.remove(attachment)

        // Attachment inherits parent's field state
        attachment.x = parent.x
        attachment.y = parent.y
        attachment.tapped = parent.tapped
        attachment.faceDown = parent.faceDown
        attachment.strengthModifier = parent.strengthModifier

        // Collect parent's current attachments (parent will go back as attachment)
        val inheritedMaterials: MutableList<CardSlot> = ArrayList(parent.materials!!)
        val inheritedResources: MutableList<CardSlot> = ArrayList(parent.resources!!)
        // Clear parent before it becomes an attachment
        parent.materials = ArrayList()
        parent.resources = ArrayList()
        parent.x = null; parent.y = null
        parent.tapped = false; parent.faceDown = false; parent.strengthModifier = 0

        // Parent goes into the same attachment type it came from
        if (isMaterial(attachmentType)) {
            inheritedMaterials.add(parent)
            attachment.materials = inheritedMaterials
            attachment.resources = inheritedResources
        } else {
            inheritedResources.add(parent)
            attachment.materials = inheritedMaterials
            attachment.resources = inheritedResources
        }

        // Replace parent with attachment on field
        val idx = player.field.indexOf(parent)
        player.field.remove(parent)
        if (idx >= 0) player.field.add(idx, attachment)
        else player.field.add(attachment)

        return GameStateUpdate.from(room, ActionType.SWAP_WITH_MAIN, userId)
    }

    @JvmStatic
    fun handleTargetCard(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val targetInstanceId = action.getString("targetInstanceId")
        val sourceInstanceId = action.getString("sourceInstanceId")
        val update = GameStateUpdate.from(room, ActionType.TARGET_CARD, userId)
        update.targetedInstanceId = targetInstanceId
        update.targetingSourceInstanceId = sourceInstanceId
        return update
    }

    @JvmStatic
    fun findInstanceAnywhere(player: PlayerState?, instanceId: String?): CardSlot? {
        return CardLocator.find(player, instanceId, CardLocator.MANUAL_ORDER, true)
    }

    @JvmStatic
    fun handleRemoveCard(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val instanceId = action.getString("instanceId") ?: return GameStateUpdate.error(room.roomId, SimMessages.NO_CARD)
        val targetPlayerId = action.getString("targetPlayerId")
        val player = (if (targetPlayerId != null) room.getPlayer(targetPlayerId) else room.getPlayer(userId))
                ?: return GameStateUpdate.notInRoom(room)

        val zones: List<MutableList<CardSlot>> = java.util.List.of(
                player.hand, player.deck, player.field,
                player.lifeStack, player.tributeZone, player.discardPile)
        var removed = false
        for (zone in zones) {
            if (zone.removeIf { c -> instanceId == c.instanceId }) { removed = true; break }
        }
        if (!removed) {
            outer@ for (zone in zones) {
                for (c in zone) {
                    if (c.materials!!.removeIf { m -> instanceId == m.instanceId }) { removed = true; break@outer }
                    if (c.resources!!.removeIf { r -> instanceId == r.instanceId }) { removed = true; break@outer }
                }
            }
        }
        if (!removed) return GameStateUpdate.error(room.roomId, SimMessages.CARD_NOT_FOUND)
        return GameStateUpdate.from(room, ActionType.REMOVE_CARD, userId)
    }

    @JvmStatic
    fun handleRestoreCard(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        val instanceId = action.getString("instanceId") ?: return GameStateUpdate.error(room.roomId, SimMessages.NO_CARD)
        if (findInstanceAnywhere(player, instanceId) != null) {
            return GameStateUpdate.error(room.roomId, "La carta ya está en el juego")
        }
        val card = CardSlot()
        card.instanceId = instanceId
        card.cardId = action.getString("cardId")
        card.imageUrl = action.getString("imageUrl")
        card.cardName = action.getString("cardName")
        card.cardType = action.getString("cardType")
        val rawStrength = action.payload!!["strength"]
        if (rawStrength is Number) card.strength = rawStrength.toInt()
        card.specialSummonKind = action.getString("specialSummonKind")
        card.faceDown = false
        player.hand.add(card)
        return GameStateUpdate.from(room, ActionType.RESTORE_CARD, userId)
    }

    @JvmStatic
    fun handleReorderAttachment(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val tp = action.getString("targetPlayerId")
        val player = room.getPlayer(tp ?: userId) ?: return GameStateUpdate.notInRoom(room)

        val parentId       = action.getString("parentId")
        val attachmentType = action.getString("attachmentType")
        @Suppress("UNCHECKED_CAST")
        val instanceIds = action.payload!!["instanceIds"] as List<String?>?
                ?: return GameStateUpdate.error(room.roomId, SimMessages.NO_ORDER)

        val parent = player.field
                .firstOrNull { c -> parentId!! == c.instanceId }
                ?: return GameStateUpdate.error(room.roomId, SimMessages.CARD_NOT_FOUND)

        val attachments = (if (isMaterial(attachmentType))
            parent.materials else parent.resources)!!

        val map = LinkedHashMap<String?, CardSlot>()
        for (c in attachments) map[c.instanceId] = c

        val reordered = instanceIds.mapNotNull { map[it] }
        attachments.clear()
        attachments.addAll(reordered)

        return GameStateUpdate.from(room, ActionType.REORDER_ATTACHMENT, userId)
    }

    @JvmStatic
    fun handleReclassifyAttachment(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val tp = action.getString("targetPlayerId")
        val player = room.getPlayer(tp ?: userId) ?: return GameStateUpdate.notInRoom(room)

        val parentId   = action.getString("parentId")
        val instanceId = action.getString("instanceId")
        val fromType   = action.getString("fromType") // "material(s)" | "resource(s)"

        val parent = player.field
                .firstOrNull { c -> parentId!! == c.instanceId }
                ?: return GameStateUpdate.error(room.roomId, SimMessages.HOLDER_NOT_FOUND)

        val fromList = (if (isMaterial(fromType)) parent.materials else parent.resources)!!
        val toList   = if (isMaterial(fromType)) parent.resources else parent.materials

        val card = fromList
                .firstOrNull { c -> instanceId!! == c.instanceId }
                ?: return GameStateUpdate.error(room.roomId, SimMessages.ATTACHMENT_NOT_FOUND)

        fromList.remove(card)
        toList!!.add(card)

        return GameStateUpdate.from(room, ActionType.RECLASSIFY_ATTACHMENT, userId)
    }

    /** A test card (test rooms only): made from what the owner typed, in the zone they chose. */
    /**
     * Test room: a card made up with the properties given (or copied from a real card: its name gives it that
     * card's effects), placed in a zone of the side being played. It is placed, not summoned or played.
     */
    @JvmStatic
    fun handleCreateDummy(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        if (!room.editor) return GameStateUpdate.error(room.roomId, "Las cartas de prueba solo existen en una sala de prueba")
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        val zoneName = action.getString("zone")
        val zone = getZone(player, zoneName) ?: return GameStateUpdate.error(room.roomId, SimMessages.INVALID_ZONE)
        val name = action.getString("cardName")
        if (name == null || name.all { Character.isWhitespace(it) }) return GameStateUpdate.error(room.roomId, "La carta necesita un nombre")

        val card = CardSlot()
        card.instanceId = "dummy-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10)
        card.cardId = if (action.getString("cardId") != null) action.getString("cardId") else "dummy"
        card.imageUrl = action.getString("imageUrl")
        card.cardName = name.trim { it <= ' ' }
        card.cardType = if (action.getString("cardType") != null) action.getString("cardType") else "creature"
        card.strength = integer(action, "strength")
        card.cost = integer(action, "cost")
        card.setLevel(integer(action, "level"))
        card.specialCost = integer(action, "specialCost")
        card.specialSummonKind = action.getString("specialSummonKind")
        card.requirement = action.getString("requirement")
        card.starter = action.getBoolean("starter", false)
        card.colors = ArrayList(action.getStringList("colors"))
        card.classes = ArrayList(action.getStringList("classes"))
        card.keywords = ArrayList(action.getStringList("keywords"))
        val hidden = Zone.DECK.`is`(zoneName) || Zone.LIFE_STACK.`is`(zoneName)
        card.faceDown = action.getBoolean("faceDown", hidden)
        if (Zone.FIELD.`is`(zoneName)) {
            val x = action.getDouble("x")
            val y = action.getDouble("y")
            card.x = x ?: 50.0
            // The Retaguardia unless placed in the Línea frontal
            card.y = y ?: 75.0
            card.fieldSeq = room.eventSeq + 1
        }
        if ("top" == action.getString("position")) zone.add(0, card)
        else zone.add(card)

        val update = GameStateUpdate.from(room, ActionType.CREATE_DUMMY, userId)
        update.logEntry = player.username + " crea la carta de prueba " + card.cardName + " en " + zoneLabel(zoneName)
        return update
    }

    @JvmStatic
    fun integer(action: GameAction, key: String): Int? {
        val value = action.getDouble(key)
        return value?.toInt()
    }
}
