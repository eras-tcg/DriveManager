package com.drive.drive_manager.simulator.actions

import com.drive.drive_manager.simulator.bot.BotEdge
import com.drive.drive_manager.simulator.engine.EffectEngine
import com.drive.drive_manager.simulator.engine.TurnRules
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.FloatingEffect
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameLogEntry
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.GameStateUpdate
import com.drive.drive_manager.simulator.room.PlayerState
import com.drive.drive_manager.simulator.room.RoomGuard
import com.drive.drive_manager.simulator.room.SimMessages

/** Turns, gems and the marker, by hand. */
object TurnActions {

    @JvmStatic
    fun handleEnterEndPhase(room: GameRoom, userId: String?): GameStateUpdate {
        // Turns pass only in a game that started (not in the pre-room, the starters or the mulligan)
        if (room.status != GameRoom.RoomStatus.IN_PROGRESS)
            return GameStateUpdate.error(room.roomId, SimMessages.GAME_NOT_IN_PROGRESS)
        if (userId!! != room.currentTurnUserId)
            return GameStateUpdate.error(room.roomId, SimMessages.NOT_YOUR_TURN)
        if ("MAIN" != room.currentPhase || room.isEngineBusy())
            return GameStateUpdate.error(room.roomId, "La fase final empieza al terminar la fase principal")
        val dir = getPlayerDirection(room, userId)
        if (dir * room.sharedCounter > 0) {
            room.sharedCounter = 0
        }
        room.currentPhase = "END_PHASE"
        TurnRules.turnEnding(room, userId)
        // Rule 8.3: the end phase is closed: once its move up and its triggers are done, the turn ends by itself
        if (room.isEngineBusy()) {
            room.endTurnPending = true
            return GameStateUpdate.from(room, ActionType.ENTER_END_PHASE, userId)
        }
        return passTurn(room, userId)
    }

    @JvmStatic
    fun handleEndTurn(room: GameRoom, userId: String?): GameStateUpdate {
        if (room.status != GameRoom.RoomStatus.IN_PROGRESS)
            return GameStateUpdate.error(room.roomId, SimMessages.GAME_NOT_IN_PROGRESS)
        if (userId!! != room.currentTurnUserId)
            return GameStateUpdate.error(room.roomId, SimMessages.NOT_YOUR_TURN)
        // Ending from the main phase goes through the end phase first (rule 8.3)
        if ("MAIN" == room.currentPhase) return handleEnterEndPhase(room, userId)
        if (room.isEngineBusy()) return GameStateUpdate.error(room.roomId, "Termina primero lo pendiente de la fase final")
        return passTurn(room, userId)
    }

    @JvmStatic
    fun passTurn(room: GameRoom, userId: String?): GameStateUpdate {
        room.endTurnPending = false
        val opponentId = room.getOpponentId(userId)
        if (opponentId != null) startTurn(room, opponentId)
        else room.currentPhase = "MAIN"
        return GameStateUpdate.from(room, ActionType.END_TURN, userId)
    }

    /**
     * Rule 8.1, start of the turn, in order: the Marcador goes up by 1 (up to 5), the player gains that many gems
     * and draws 1 card, their cards are reactivated, they may move creatures up to the Línea frontal, and the
     * start-of-turn effects trigger. The main phase begins once all of that is done.
     */
    @JvmStatic
    fun startTurn(room: GameRoom, playerId: String?) {
        val player = room.getPlayer(playerId)
        room.currentTurnUserId = playerId
        room.firstTurn = false
        room.currentPhase = "START"
        clearTurnKeywords(room)
        player!!.turnsStarted = player.turnsStarted + 1
        val newCount = BotEdge.marcador(room, player, player.resourceCount + 1)
        player.resourceCount = newCount
        val dir = getPlayerDirection(room, playerId)
        room.sharedCounter = Math.max(-10, Math.min(10, room.sharedCounter + newCount * dir))
        // Not being able to draw at the start of the turn loses the game
        if (player.deck.isEmpty()) {
            EffectEngine.loseByDeckOut(room, playerId)
            return
        }
        autoDraw(player)
        // "no se reactiva al inicio del turno": that card stays exhausted this time
        player.field.forEach { c -> if (c.noUntap != 1 && c.noStartUntap != 1) c.tapped = false }
        TurnRules.turnStarted(room, playerId)
        if (room.isEngineBusy()) {
            room.startPhasePending = true
        } else {
            room.currentPhase = "MAIN"
        }
    }

    /** "Hasta el final del turno" ends and the per-turn limits reset when the turn changes (rule 11.8.0.7). */
    @JvmStatic
    fun clearTurnKeywords(room: GameRoom) {
        // "Al final del turno súbelo a tu mano" (placed by Janne's inherited effect)
        room.players.values.forEach { p ->
            for (c in ArrayList(p.field)) {
                if (!c.returnAtTurnEnd) continue
                p.field.remove(c)
                com.drive.drive_manager.simulator.engine.Removals.leaveField(c)
                p.hand.add(c)
                room.eventLog.add(GameLogEntry(System.currentTimeMillis(), c.cardName + " vuelve a la mano de " + p.username))
            }
        }
        room.players.values.forEach { p ->
            p.field.forEach { c ->
                c.turnKeywords!!.clear()
                c.turnKeywords!!.addAll(c.laterKeywords!!)
                c.laterKeywords!!.clear()
                c.untilNextAttack!!.clear()
                if (c.noUntap > 0) c.noUntap = c.noUntap - 1
                if (c.noStartUntap > 0) c.noStartUntap = c.noStartUntap - 1
                // What lasts until the end of the next turn now lasts until the end of this one
                c.turnStrength = c.laterStrength
                c.laterStrength = 0
                c.floating!!.removeIf { f -> "nextTurn" != f.until }
                c.floating!!.replaceAll { f -> FloatingEffect(f.text, f.source, "turn", f.strength, f.keyword) }
            }
        }
        // What was given to the table "hasta el final del turno" ends; "del turno oponente" now lasts until the end of this one
        room.fieldGrants.removeIf { g -> "turn" == g.until }
        room.fieldGrants.replaceAll { g -> com.drive.drive_manager.simulator.engine.FieldGrant(g.controllerId, g.filter, g.keyword, "turn", g.source, g.exceptId) }
        // "el turno anterior": who used a Definitive effect this turn is remembered for the next one
        val definitive = room.usedThisTurn.filter { k -> k.endsWith("|" + EffectEngine.DEFINITIVE_USED) }
                .map { k -> EffectEngine.DEFINITIVE_LAST_TURN + k.substring(0, k.indexOf('|')) }
        val destroyed = room.usedThisTurn.filter { k -> k.startsWith(EffectEngine.ALLY_DESTROYED_BY_OPPONENT) }
                .map { k -> EffectEngine.ALLY_DESTROYED_LAST_TURN + k.substring(EffectEngine.ALLY_DESTROYED_BY_OPPONENT.length) }
        room.usedThisTurn.clear()
        room.usedThisTurn.addAll(definitive)
        room.usedThisTurn.addAll(destroyed)
    }

    @JvmStatic
    fun getPlayerDirection(room: GameRoom, userId: String?): Int {
        val firstId = room.players.keys.iterator().next()
        return if (firstId == userId) -1 else 1
    }

    @JvmStatic
    fun handleGrantResource(room: GameRoom, userId: String?): GameStateUpdate {
        val opponentId = room.getOpponentId(userId) ?: return GameStateUpdate.error(room.roomId, SimMessages.NO_OPPONENT)
        val opponent = room.getPlayer(opponentId)
        val newCount = Math.min(5, opponent!!.resourceCount + 1)
        opponent.resourceCount = newCount
        val dir = getPlayerDirection(room, opponentId)
        val next = room.sharedCounter + newCount * dir
        room.sharedCounter = Math.max(-10, Math.min(10, next))
        return GameStateUpdate.from(room, ActionType.GRANT_RESOURCE, userId)
    }

    @JvmStatic
    fun handleSetCounter(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val d = action.getDouble("value") ?: return GameStateUpdate.error(room.roomId, SimMessages.NO_VALUE)
        val prev = room.sharedCounter
        room.sharedCounter = Math.max(-10, Math.min(10, d.toInt()))
        val player = room.getPlayer(userId)
        val update = GameStateUpdate.from(room, ActionType.SET_COUNTER, userId)
        val who = if (player != null) player.username else userId
        update.logEntry = who + " ajustó el contador compartido de " + prev + " a " + room.sharedCounter
        return update
    }

    @JvmStatic
    fun handleUseResources(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val d = action.getDouble("amount") ?: return GameStateUpdate.error(room.roomId, SimMessages.NO_AMOUNT)
        val next = room.sharedCounter + d.toInt()
        room.sharedCounter = Math.max(-10, Math.min(10, next))
        val player = room.getPlayer(userId)
        if (player != null) player.resourceCount = Math.abs(d.toInt())
        return GameStateUpdate.from(room, ActionType.USE_RESOURCES, userId)
    }

    @JvmStatic
    fun handleSetMarker(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val d = action.getDouble("value") ?: return GameStateUpdate.error(room.roomId, SimMessages.NO_VALUE)
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        val prev = player.resourceCount
        player.resourceCount = Math.min(5, Math.max(0, d.toInt()))
        val update = GameStateUpdate.from(room, ActionType.SET_MARKER, userId)
        update.logEntry = player.username + " ajustó su marcador de recursos de " + prev + " a " + player.resourceCount
        return update
    }

    /** Turns the offer of the player's optional triggered effects off or on (in a test room, of both sides). */
    @JvmStatic
    fun handleSetSkipOptional(room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        val skip = action.getBoolean("value", false)
        if (room.editor) room.players.values.forEach { p -> p.skipOptional = skip }
        else player.skipOptional = skip
        // Not in the history: neither player is told; the effects let go look like ones the player chose not to activate
        return GameStateUpdate.from(room, ActionType.SET_SKIP_OPTIONAL, userId)
    }

    @JvmStatic
    fun handleDraw(room: GameRoom, userId: String?): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        if (player.deck.isEmpty()) return GameStateUpdate.error(room.roomId, SimMessages.DECK_EMPTY)

        val drawn = player.deck.removeAt(0)
        drawn.faceDown = false
        player.hand.add(drawn)

        val update = GameStateUpdate.from(room, ActionType.DRAW_CARD, userId)
        update.logEntry = player.username + " robó una carta"
        return update
    }

    @JvmStatic
    fun autoDraw(player: PlayerState) {
        if (player.deck.isEmpty()) return
        val drawn = player.deck.removeAt(0)
        drawn.faceDown = false
        player.hand.add(drawn)
    }

    /** Conceding: only during a game, and the other player wins. */
    @JvmStatic
    fun handleSurrender(room: GameRoom, userId: String?): GameStateUpdate {
        if (!RoomGuard.inGame(room)) return GameStateUpdate.error(room.roomId, "No hay una partida en curso")
        room.winnerId = room.getOpponentId(userId)
        room.endReason = "surrender"
        room.postGameChoices.clear()
        room.status = GameRoom.RoomStatus.POST_GAME
        return GameStateUpdate.from(room, ActionType.SURRENDER, userId)
    }
}
