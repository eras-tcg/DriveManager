package com.drive.drive_manager.simulator.actions

import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.actions.TurnActions.passTurn
import com.drive.drive_manager.simulator.actions.ZoneActions.cardLabel
import com.drive.drive_manager.simulator.actions.ZoneActions.getZone
import com.drive.drive_manager.simulator.actions.ZoneActions.zoneLabel
import com.drive.drive_manager.simulator.engine.Answers
import com.drive.drive_manager.simulator.engine.Chain
import com.drive.drive_manager.simulator.engine.CombatRules
import com.drive.drive_manager.simulator.engine.Removals
import com.drive.drive_manager.simulator.engine.SpecialSummons
import com.drive.drive_manager.simulator.engine.Summons
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.GameStateUpdate
import com.drive.drive_manager.simulator.room.SimMessages

/** What a player asks the rules engine to do. */
object RulesActions {

    /** A rules action: the engine's refusal as an error, else the room after it. */
    private inline fun ruled(room: GameRoom, userId: String?, type: ActionType, rules: () -> String?): GameStateUpdate {
        val error = rules()
        if (error != null) return GameStateUpdate.error(room.roomId, error)
        return GameStateUpdate.from(room, type, userId)
    }

    /** Taking a creature from the hand to the board without an effect is a normal summon (rules engine). */
    @JvmStatic
    fun handleNormalSummon(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        return ruled(room, userId, ActionType.NORMAL_SUMMON) {
            Summons.normalSummon(room, userId, action.getString("instanceId"), action.getDouble("x"), action.getDouble("y"))
        }
    }

    /** The player's choice for the effect step the engine is waiting on. */
    @JvmStatic
    fun handleResolvePrompt(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val error = Answers.answer(room, userId, action.getString("promptId"), action.getStringList("chosen"), action.getStringList("order"))
        if (error != null) return GameStateUpdate.error(room.roomId, error)
        // The end phase's move up was answered: the turn that was being ended ends now
        if (room.endTurnPending && !room.isEngineBusy()) return passTurn(room, room.currentTurnUserId)
        // The start of the turn is done (its move up answered, its effects resolved): the main phase begins
        if (room.startPhasePending && !room.isEngineBusy()) {
            room.startPhasePending = false
            room.currentPhase = "MAIN"
        }
        return GameStateUpdate.from(room, ActionType.RESOLVE_PROMPT, userId)
    }

    /** Destroys a card on the field (to its owner's Descarte); its "En Destrucción" effects trigger. */
    @JvmStatic
    fun handleDestroyCard(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        return ruled(room, userId, ActionType.DESTROY_CARD) {
            Removals.destroyByPlayer(room, userId, action.getString("targetPlayerId"), action.getString("instanceId"))
        }
    }

    /** Sacrifices one of your cards on the field (destroyed, to your Tributos). */
    @JvmStatic
    fun handleSacrificeCard(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        return ruled(room, userId, ActionType.SACRIFICE_CARD) {
            Removals.sacrificeByPlayer(room, userId, action.getString("instanceId"))
        }
    }

    /** Declares an attack (rule 10): the engine runs the combat. targetInstanceId null = the opponent. */
    @JvmStatic
    fun handleAttack(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        return ruled(room, userId, ActionType.ATTACK) {
            CombatRules.attackByPlayer(room, userId, action.getString("instanceId"), action.getString("targetInstanceId"))
        }
    }

    /** Plays a utility or structure from the hand: pays its cost and fires its "En Juego" effects. */
    @JvmStatic
    fun handlePlayCard(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        return ruled(room, userId, ActionType.PLAY_CARD) {
            Summons.playByPlayer(room, userId, action.getString("instanceId"), action.getDouble("x"), action.getDouble("y"))
        }
    }

    @JvmStatic
    fun handleRitualSummon(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        return ruled(room, userId, ActionType.RITUAL_SUMMON) {
            SpecialSummons.specialByPlayer(room, userId, action.getString("instanceId"), action.getString("summonerId"))
        }
    }

    @JvmStatic
    fun handleActivateEffect(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        return ruled(room, userId, ActionType.ACTIVATE_EFFECT) {
            Chain.activateByPlayer(room, userId, action.getString("instanceId"), action.getString("scriptId"))
        }
    }

    /** Enlace: a creature of the Línea frontal links with the one next to it (its formation, or a new one with it). */
    @JvmStatic
    fun handleFormationJoin(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        return ruled(room, userId, ActionType.FORMATION_JOIN) {
            com.drive.drive_manager.simulator.engine.Formations.joinByPlayer(room, userId, action.getString("instanceId"),
                action.getString("targetId"))
        }
    }

    /**
     * A creature dropped onto the field from a non-hand zone only becomes an
     * "effect summon" once the player answers the draw/no-draw prompt (see
     * FieldZone.vue's effectSummonPending flow) — not at drop time.
     */
    @JvmStatic
    fun handleEffectSummonResolve(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        val instanceId = action.getString("instanceId")
        val fromZone   = action.getString("fromZone")
        val draw = action.getBoolean("draw", false)

        val card = player.field
                .firstOrNull { c -> instanceId != null && instanceId == c.instanceId }
        val label = if (card != null) cardLabel(card) else "una carta"

        val logText: String
        if (draw) {
            var actuallyDrew = false
            if (!player.deck.isEmpty()) {
                val drawn = player.deck.removeAt(0)
                drawn.faceDown = false
                player.hand.add(drawn)
                actuallyDrew = true
            }
            logText = player.username + " invocó por efecto a " + label + (if (actuallyDrew) " y robó una carta" else "")
        } else {
            // Declined the effect-summon draw — this is just a plain summon/set,
            // using the same phrasing the immediate MOVE_CARD log would have used
            // had it not been deferred (see buildMoveLogEntry's "field" case).
            val from   = zoneLabel(fromZone)
            val suffix = if (card != null && "creature".equals(card.cardType, ignoreCase = true) && card.faceDown) " (set)" else ""
            logText = player.username + " colocó/invocó " + label + " desde " + from + suffix
        }

        val update = GameStateUpdate.from(room, ActionType.EFFECT_SUMMON_RESOLVE, userId)
        update.logEntry = logText
        return update
    }

    @JvmStatic
    fun handleSpecialSummon(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        val summonedId  = action.getString("summonedId")
        val fromZone    = action.getString("fromZone")
        val summonerId  = action.getString("summonerId")
        val summonType  = action.getString("summonType")

        val source = getZone(player, fromZone) ?: return GameStateUpdate.error(room.roomId, SimMessages.INVALID_ZONE)
        val summoned = source.firstOrNull { c -> summonedId!! == c.instanceId }
                ?: return GameStateUpdate.error(room.roomId, SimMessages.SUMMONED_NOT_FOUND)
        val summoner = player.field.firstOrNull { c -> summonerId!! == c.instanceId }
                ?: return GameStateUpdate.error(room.roomId, SimMessages.SUMMONER_NOT_FOUND)

        source.remove(summoned)
        player.field.remove(summoner)

        summoned.faceDown = false
        summoned.x = summoner.x
        summoned.y = summoner.y
        // Rule 9.2.0.3: its whole state, also its effects until the end of the turn and its role in a combat
        Summons.inheritFromSummoner(room, summoned, summoner)

        if (summonType!! in PASSING_METHODS) {
            // The summoner's state and attachments pass to the summoned creature (rule 9.3.0.5 for a Ritual)
            summoned.tapped = summoner.tapped
            summoned.strengthModifier = summoner.strengthModifier
            summoned.materials!!.addAll(0, summoner.materials!!)
            summoned.resources!!.addAll(0, summoner.resources!!)
            summoner.materials = ArrayList()
            summoner.resources = ArrayList()
            summoner.tapped = false; summoner.x = null; summoner.y = null
            when (summonType) {
                "materialization" -> summoned.materials!!.add(0, summoner)
                "evolution" -> summoned.resources!!.add(0, summoner)
                // Ritual (the summoner is sacrificed) and Ascenso: the summoner goes to the Tributos
                else -> {
                    summoner.faceDown = false
                    player.tributeZone.add(summoner)
                }
            }
        }

        summoned.faceDown = false
        player.field.add(summoned)
        // Recorded by the engine: it offers the move up to the Línea frontal, does the regulatory draw and then
        // fires the summon and sacrifice triggers ("En Invocación", "En Destrucción")
        Summons.specialSummoned(room, userId, summoned, summoner, summonType,
                player.username + " invocó especialmente a " + cardLabel(summoned)
                        + " (" + summonTypeLabel(summonType) + ") usando " + cardLabel(summoner))
        return GameStateUpdate.from(room, ActionType.SPECIAL_SUMMON, userId)
    }

    /** The special summon methods whose summoner hands its state to the creature it summons. */
    private val PASSING_METHODS = setOf("materialization", "evolution", "ritual", "promotion")

    @JvmStatic
    fun summonTypeLabel(summonType: String?): String {
        if (summonType == null) return "invocación especial"
        return when (summonType) {
            "materialization" -> "Materialización"
            "evolution"       -> "Evolución"
            "ritual"          -> "Ritual"
            "promotion"       -> "Ascenso"
            else              -> summonType
        }
    }
}
