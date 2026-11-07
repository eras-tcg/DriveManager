package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.room.GameRoom

/**
 * What a player can do now by the rules, card by card, for the board: asked of the same checks that would refuse the action, so the board
 * repeats no rule.
 */
object LegalActions {

    /** By the card's instance id: "attack", "summon", "ritual", "play" and "activate". Only the cards that can do something. */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun of(room: GameRoom, userId: String?): MutableMap<String, MutableList<String>> {
        val legal = LinkedHashMap<String?, MutableList<String>>()
        val player = room.getPlayer(userId)
        if (player == null || room.status != GameRoom.RoomStatus.IN_PROGRESS || "MAIN" != room.currentPhase || room.isEngineBusy()) return legal as MutableMap<String, MutableList<String>>
        // Slow effects can be activated on either turn
        for (effect in Chain.activatable(room, userId)) add(legal, effect.instanceId, "activate")
        if (Summons.checkMainPhase(room, userId, "") != null) return legal as MutableMap<String, MutableList<String>>
        // Copies: checking a utility's "En Juego" moves it out of the hand and onto the field, then back
        for (card in ArrayList(player.field)) {
            if (CombatRules.whyCannotAttack(room, userId, card) == null) add(legal, card.instanceId, "attack")
        }
        for (card in ArrayList(player.hand)) {
            if (Summons.whyCannotSummon(room, userId, player, card) == null) add(legal, card.instanceId, "summon")
            if (Summons.whyCannotPlay(room, userId, card) == null) add(legal, card.instanceId, "play")
        }
        for (id in SpecialRequirements.specialSummonable(room, userId)) add(legal, id, "ritual")
        return legal as MutableMap<String, MutableList<String>>
    }

    /** By attacker: the enemies it can attack. */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun attackTargets(room: GameRoom, userId: String?): MutableMap<String, MutableList<String>> {
        val targets = LinkedHashMap<String?, MutableList<String>>()
        val player = room.getPlayer(userId) ?: return targets as MutableMap<String, MutableList<String>>
        for (card in player.field) {
            if ("creature" != card.cardType) continue
            val enemies = EffectEngine.ids(CombatRules.attackTargets(room, userId, card))
            if (!enemies.isEmpty()) targets[card.instanceId] = ArrayList(enemies)
        }
        return targets as MutableMap<String, MutableList<String>>
    }

    private fun add(legal: MutableMap<String?, MutableList<String>>, id: String?, action: String) {
        val actions = legal.computeIfAbsent(id) { ArrayList() }
        if (!actions.contains(action)) actions.add(action)
    }
}
