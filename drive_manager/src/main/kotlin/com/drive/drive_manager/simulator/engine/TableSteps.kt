package com.drive.drive_manager.simulator.engine

import kotlin.math.max
import kotlin.math.min
import com.drive.drive_manager.simulator.engine.EffectEngine.CAUSE_EFFECT
import com.drive.drive_manager.simulator.engine.EffectEngine.LIFE_REDUCED
import com.drive.drive_manager.simulator.engine.EffectEngine.clamp
import com.drive.drive_manager.simulator.engine.EffectEngine.direction
import com.drive.drive_manager.simulator.engine.EffectEngine.find
import com.drive.drive_manager.simulator.engine.EffectEngine.frontLine
import com.drive.drive_manager.simulator.engine.EffectEngine.gemsOf
import com.drive.drive_manager.simulator.engine.EffectEngine.gemsText
import com.drive.drive_manager.simulator.engine.EffectEngine.ids
import com.drive.drive_manager.simulator.engine.EffectEngine.inFrontLine
import com.drive.drive_manager.simulator.engine.EffectEngine.log
import com.drive.drive_manager.simulator.engine.EffectEngine.loseByLives
import com.drive.drive_manager.simulator.engine.EffectEngine.name
import com.drive.drive_manager.simulator.engine.EffectEngine.plural
import com.drive.drive_manager.simulator.engine.EffectEngine.record
import com.drive.drive_manager.simulator.engine.EffectStep.*
import com.drive.drive_manager.simulator.engine.Removals.destroy
import com.drive.drive_manager.simulator.engine.StepHelpers.pick
import com.drive.drive_manager.simulator.engine.Summons.drawOne
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/** Both players, lives and gems. Each step: whether it can be done, carrying it out, and applying the player's choice. */
object TableSteps {

    @JvmStatic
    fun possibleStep(room: GameRoom, effect: PendingEffect, step: EffectStep): Boolean {
        val player = room.getPlayer(effect.controllerId)!!
        val onField = find(player.field, effect.sourceInstanceId)
        return when (step) {
            is DestroyFaceUpLife -> {
                val opponent = room.getPlayer(room.getOpponentId(effect.controllerId))
                opponent != null && opponent.lifeStack.any { l -> !l.faceDown }
            }
            else -> true
        }
    }

    @JvmStatic
    fun runStep(room: GameRoom, effect: PendingEffect, step: EffectStep): Boolean {
        val player = room.getPlayer(effect.controllerId)!!
        val userId = player.userId

        when (step) {
            is ReduceOpponentLivesBy -> {
                val opponent = room.getPlayer(room.getOpponentId(userId))
                val amount = step.amount!!.of(room, userId)
                if (opponent == null || amount <= 0) return false
                for (life in opponent.lifeStack) {
                    if (life.faceDown) continue
                    room.usedThisTurn.add(LIFE_REDUCED + life.instanceId + "|" + amount)
                    log(room, "La vida " + name(life) + " de " + opponent.username + " pierde " + amount + " de fuerza hasta el final del turno ("
                            + effect.sourceName + ")", null)
                }
                return false
            }
            is EachPlayerKeepsOneInFrontLine -> {
                if (effect.repeatsDone >= 2) {
                    effect.repeatsDone = 0
                    for (p in room.players.values) {
                        for (c in ArrayList(frontLine(p.field))) {
                            if ("creature" == c.cardType && !effect.targetIds.contains(c.instanceId))
                                destroy(room, c, p, true, CAUSE_EFFECT, userId, effect.sourceInstanceId, effect)
                        }
                    }
                    return false
                }
                val who = if (effect.repeatsDone == 0) player else room.getPlayer(room.getOpponentId(userId))
                val mine = if (who == null) frozenListOf() else frontLine(who.field).filter { c -> "creature" == c.cardType }
                if (mine.isEmpty()) {
                    effect.repeatsDone = effect.repeatsDone + 1
                    return Resolution.run(room, effect, step)
                }
                pick(room, effect, "Elige 1 de tus aliados de tu línea frontal: las otras criaturas de tu línea frontal se sacrifican (" + effect.sourceName + ").",
                        "Conservar", who!!.field, ids(mine), 1)
                room.prompt!!.playerId = who.userId
                return true
            }
            is EachPlayerLowersMarker -> {
                for (p in room.players.values) {
                    p.resourceCount = max(0, p.resourceCount - step.count)
                    log(room, p.username + " reduce en " + step.count + " su marcador: queda en " + p.resourceCount + " (" + effect.sourceName + ")", null)
                }
                return false
            }
            is LivesFaceDown -> {
                val faceUp = player.lifeStack.filter { l -> !l.faceDown }
                if (step.all) {
                    faceUp.forEach { l -> l.faceDown = true }
                    if (step.shuffle) GameRandom.shuffle(player.lifeStack, room.random)
                    log(room, player.username + " coloca bocabajo sus " + faceUp.size + " vidas bocarriba" + (if (step.shuffle) " y baraja sus vidas" else "")
                            + " (" + effect.sourceName + ")", null)
                    return false
                }
                if (faceUp.isEmpty()) return false
                pick(room, effect, "Coloca bocabajo 1 de tus vidas bocarriba.", "Bocabajo", player.lifeStack, ids(faceUp), 1)
                return true
            }
            is EachPlayerDiscards -> {
                // First the controller, then the opponent (repeatsDone counts who already did)
                if (effect.repeatsDone >= 2) {
                    effect.repeatsDone = 0
                    return false
                }
                val who = if (effect.repeatsDone == 0) player else room.getPlayer(room.getOpponentId(userId))
                if (who == null || who.hand.isEmpty()) {
                    effect.repeatsDone = effect.repeatsDone + 1
                    return Resolution.run(room, effect, step)
                }
                val n = min(step.count, who.hand.size)
                pick(room, effect, "Descarta " + n + " " + plural(n, "carta", "cartas") + " de tu mano, bocabajo (" + effect.sourceName + ").",
                        "Descartar", who.hand, ids(who.hand), n)
                room.prompt!!.playerId = who.userId
                return true
            }
            is OpponentGainsGemsAsCounter -> {
                val opponentId = room.getOpponentId(userId)
                val opponent = room.getPlayer(opponentId)
                val gems = if (opponent == null) 0 else max(0, gemsOf(room, opponentId))
                if (gems > 0) {
                    room.sharedCounter = clamp(room.sharedCounter + gems * direction(room, opponentId))
                    record(room, GameEvent.GAIN_GEMS, opponentId, null, null, null, effect, frozenMapOf<String, Any?>("count", gems),
                            opponent!!.username + " gana " + gemsText(gems) + " (" + effect.sourceName + ")")
                }
                return false
            }
            is GainGemsPerOwnSacrificed -> {
                val n = effect.targetIds.count { id -> find(player.tributeZone, id) != null }
                if (n > 0) Resolution.run(room, effect, Compose.gainGems(n))
                return false
            }
            is DestroyTopLife -> {
                val opponent = room.getPlayer(room.getOpponentId(userId))
                if (opponent == null || opponent.lifeStack.isEmpty()) return false
                val life = opponent.lifeStack.removeAt(0)
                life.faceDown = false
                opponent.discardPile.add(life)
                record(room, GameEvent.DESTROY, userId, life, "lifeStack", "discardPile", effect,
                        frozenMapOf<String, Any?>("cause", CAUSE_EFFECT, "ownerId", opponent.userId, "byCard", effect.sourceInstanceId, "defenderId", opponent.userId),
                        player.username + " destruye la vida " + name(life) + " de " + opponent.username + " (" + effect.sourceName + ")")
                if (opponent.lifeStack.isEmpty()) loseByLives(room, opponent)
                return false
            }
            is OpponentMovesOneToFront -> {
                val opponent = room.getPlayer(room.getOpponentId(userId))
                val back = if (opponent == null) frozenListOf() else opponent.field
                        .filter { c -> "creature" == c.cardType && !inFrontLine(c) }
                if (back.isEmpty()) {
                    log(room, "El oponente no tiene criaturas en su retaguardia (" + effect.sourceName + ")", null)
                    return false
                }
                pick(room, effect, "Elige 1 criatura de tu retaguardia: se mueve a tu línea frontal (" + effect.sourceName + ").", "A la línea frontal",
                        opponent!!.field, ids(back), 1)
                room.prompt!!.playerId = opponent.userId
                return true
            }
            is HandsToBottomDrawOpponentLives -> {
                val both: List<PlayerState> = ArrayList(room.players.values)
                for (p in both) {
                    val hand: MutableList<CardSlot> = ArrayList(p.hand)
                    GameRandom.shuffle(hand, room.random)
                    p.hand.clear()
                    hand.forEach { c -> c.faceDown = true }
                    p.deck.addAll(hand)
                    log(room, p.username + " envía las " + hand.size + " cartas de su mano al fondo de su mazo", null)
                }
                for (p in both) {
                    val other = room.getPlayer(room.getOpponentId(p.userId))
                    val n = min(if (other == null) 0 else other.lifeStack.size, p.deck.size)
                    for (i in 0 until n) drawOne(room, p, effect, false)
                    log(room, p.username + " roba " + n + " " + plural(n, "carta", "cartas") + " (" + effect.sourceName + ")", null)
                }
                return false
            }
            is DestroyFaceUpLife -> {
                val opponent = room.getPlayer(room.getOpponentId(userId))
                val valid = if (opponent == null) frozenListOf() else opponent.lifeStack.filter { l -> !l.faceDown }
                if (valid.isEmpty()) return false
                pick(room, effect, "Puedes destruir 1 carta bocarriba de las vidas de tu rival, o ninguna.", "Destruir",
                        opponent!!.lifeStack, ids(valid), 1)
                room.prompt!!.min = 0
                return true
            }
            else -> throw IllegalStateException("Paso de otra familia: $step")
        }
    }

    @JvmStatic
    fun applyStep(
        room: GameRoom, effect: PendingEffect, step: EffectStep, player: PlayerState, picks: List<String>,
        order: List<String>?, repeat: BooleanArray,
    ): String? {
        val userId = player.userId
        when (step) {
            is EachPlayerKeepsOneInFrontLine -> {
                if (find(player.field, picks[0]) == null) return "Ese aliado ya no está en el campo"
                effect.targetIds.add(picks[0])
                effect.repeatsDone = effect.repeatsDone + 1
                repeat[0] = true
            }
            is LivesFaceDown -> {
                val life = find(player.lifeStack, picks[0]) ?: return "Esa vida ya no está"
                life.faceDown = true
                effect.lastDone = true
                log(room, player.username + " coloca bocabajo la vida " + name(life) + " (" + effect.sourceName + ")", null)
            }
            is EachPlayerDiscards -> {
                for (id in picks) {
                    val card = find(player.hand, id) ?: return "Esa carta ya no está en tu mano"
                    player.hand.remove(card)
                    card.faceDown = true
                    player.discardPile.add(card)
                }
                log(room, player.username + " descarta " + picks.size + " " + plural(picks.size, "carta", "cartas") + " bocabajo (" + effect.sourceName + ")", null)
                effect.repeatsDone = effect.repeatsDone + 1
                repeat[0] = true
            }
            is OpponentMovesOneToFront -> {
                val card = find(player.field, picks[0])
                if (card == null || inFrontLine(card)) return "Esa criatura ya no está en tu retaguardia"
                card.y = 25.0
                record(room, GameEvent.MOVE, userId, card, null, "frontLine", effect, frozenMapOf<String, Any?>("reason", "effect"),
                        player.username + " mueve " + name(card) + " a su línea frontal (" + effect.sourceName + ")")
            }
            is DestroyFaceUpLife -> {
                val opponent = room.getPlayer(room.getOpponentId(userId))
                for (id in picks) {
                    val life = (if (opponent == null) null else find(opponent.lifeStack, id)) ?: return "Esa vida ya no está"
                    opponent!!.lifeStack.remove(life)
                    opponent.discardPile.add(life)
                    record(room, GameEvent.DESTROY, userId, life, "lifeStack", "discardPile", effect,
                            frozenMapOf<String, Any?>("cause", CAUSE_EFFECT, "ownerId", opponent.userId, "byCard", effect.sourceInstanceId),
                            player.username + " destruye la vida " + name(life) + " de " + opponent.username + " (" + effect.sourceName + ")")
                }
            }
            else -> {
                return "Este paso no espera ninguna elección"
            }
        }
        return null
    }
}
