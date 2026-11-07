package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.engine.EffectEngine.CANNOT_TAP
import com.drive.drive_manager.simulator.engine.EffectEngine.CAUSE_EFFECT
import com.drive.drive_manager.simulator.engine.EffectEngine.EXTRA_HIT
import com.drive.drive_manager.simulator.engine.EffectEngine.STRENGTH_ENTRY
import com.drive.drive_manager.simulator.engine.EffectEngine.UNREMOVABLE
import com.drive.drive_manager.simulator.engine.EffectEngine.allies
import com.drive.drive_manager.simulator.engine.EffectEngine.find
import com.drive.drive_manager.simulator.engine.EffectEngine.findAnywhere
import com.drive.drive_manager.simulator.engine.EffectEngine.floating
import com.drive.drive_manager.simulator.engine.EffectEngine.frontLine
import com.drive.drive_manager.simulator.engine.EffectEngine.ids
import com.drive.drive_manager.simulator.engine.EffectEngine.inFrontLine
import com.drive.drive_manager.simulator.engine.EffectEngine.keywordLevel
import com.drive.drive_manager.simulator.engine.EffectEngine.keywords
import com.drive.drive_manager.simulator.engine.EffectEngine.log
import com.drive.drive_manager.simulator.engine.EffectEngine.name
import com.drive.drive_manager.simulator.engine.EffectEngine.names
import com.drive.drive_manager.simulator.engine.EffectEngine.plural
import com.drive.drive_manager.simulator.engine.EffectEngine.record
import com.drive.drive_manager.simulator.engine.EffectEngine.refreshContinuous
import com.drive.drive_manager.simulator.engine.EffectEngine.strength
import com.drive.drive_manager.simulator.engine.EffectStep.*
import com.drive.drive_manager.simulator.engine.Removals.deferRemoval
import com.drive.drive_manager.simulator.engine.Removals.removeFromField
import com.drive.drive_manager.simulator.engine.StepHelpers.RETURN_AT_ATTACK_END
import com.drive.drive_manager.simulator.engine.StepHelpers.allIn
import com.drive.drive_manager.simulator.engine.StepHelpers.enemyTargets
import com.drive.drive_manager.simulator.engine.StepHelpers.levelSumPossible
import com.drive.drive_manager.simulator.engine.StepHelpers.levelWhenRemoved
import com.drive.drive_manager.simulator.engine.StepHelpers.pick
import com.drive.drive_manager.simulator.engine.StepHelpers.reduceStrength
import com.drive.drive_manager.simulator.engine.StepHelpers.subject
import com.drive.drive_manager.simulator.engine.StepHelpers.validTargets
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/** Attacks, blocks, and what acts on chosen enemies. Each step: whether it can be done, carrying it out, and applying the player's choice. */
object CombatSteps {

    @JvmStatic
    fun possibleStep(room: GameRoom, effect: PendingEffect, step: EffectStep): Boolean {
        val player = room.getPlayer(effect.controllerId)!!
        @Suppress("UNUSED_VARIABLE")
        val onField = find(player.field, effect.sourceInstanceId)
        return when (step) {
            is TributesToDeckBottomForKeyword -> {
                val f = step.filter!!
                player.tributeZone.count { f.matches(it) } >= step.count
            }
            is ChooseWeakestEnemy -> !enemyTargets(room, effect, step.filter).isEmpty()
            is PickAllyUpToSacrificedLevel -> allies(player).any { inFrontLine(it) }
            is ExtraHit -> room.combat != null && room.combat!!.step == Combat.Step.DAMAGE
            is ChooseEnemiesLevelSum -> levelSumPossible(enemyTargets(room, effect, CardFilter.creatures()), step.sum)
            else -> true
        }
    }

    @JvmStatic
    fun runStep(room: GameRoom, effect: PendingEffect, step: EffectStep): Boolean {
        val player = room.getPlayer(effect.controllerId)!!
        val userId = player.userId

        when (step) {
            is ReturnSubjectAtAttackEnd -> {
                val ally = subject(room, effect)
                if (ally == null || room.combat == null) return false
                room.usedThisTurn.add(RETURN_AT_ATTACK_END + userId + "|" + ally.instanceId)
                return false
            }
            is DeclareBlockSelf -> {
                val self = subject(room, effect)
                val c = room.combat
                if (self == null || c == null || c.blockerId != null || player.userId != c.defenderId) return false
                c.blockerId = self.instanceId
                record(room, GameEvent.BLOCK, userId, self, null, null, effect, null, name(self) + " declara bloqueo (" + effect.sourceName + ")")
                return false
            }
            is GroupUp -> {
                val self = find(player.field, effect.sourceInstanceId)
                val valid = if (self == null) java.util.List.of<CardSlot>() else allies(player).filter { c -> c !== self && !c.tapped
                        && !keywords(c).contains(CANNOT_TAP) }
                if (valid.isEmpty() || room.combat == null) return false
                pick(room, effect, "Agrupar: puedes agotar hasta " + step.allies + " " + plural(step.allies, "aliado", "aliados") + "; " + name(self)
                        + " gana su fuerza durante el ataque e inflige daño 1 vez extra por cada uno.", "Agotar", player.field, ids(valid), Math.min(step.allies, valid.size))
                room.prompt!!.min = 0
                return true
            }
            is ChooseEnemiesLevelSum -> {
                val valid = enemyTargets(room, effect, CardFilter.creatures())
                if (!levelSumPossible(valid, step.sum)) return false
                val opponent = room.getPlayer(room.getOpponentId(userId))
                pick(room, effect, "Elige enemigos que sumen " + step.sum + " en nivel.", "Objetivo", frontLine(opponent!!.field), ids(valid), valid.size)
                room.prompt!!.min = 1
                return true
            }
            is AlliesGain -> {
                // On the table: the allies that enter later get it too (refreshContinuous gives it)
                val until = if ("nextTurn" == step.until) "hasta el final del turno oponente" else "hasta el final del turno"
                val except = if (step.others) effect.sourceInstanceId else null
                room.fieldGrants.add(FieldGrant(userId, step.filter, step.keyword, step.until, effect.sourceName, except))
                refreshContinuous(room)
                val n = allies(player).count { c -> step.filter!!.matches(c) && c.instanceId != except }
                record(room, GameEvent.GAIN, userId, null, null, null, effect, java.util.Map.of<String, Any?>("keyword", step.keyword, "count", n),
                    player.username + ": sus " + (if (step.others) "otros " else "") + "aliados ganan " + step.keyword + " " + until + ", también los que entren después ("
                            + effect.sourceName + ")")
                return false
            }
            is PickAllyUpToSacrificedLevel -> {
                val sacrificed = if (effect.lastTributed.isEmpty()) null else findAnywhere(room, userId, effect.lastTributed[0])
                val max = levelWhenRemoved(room, sacrificed) + step.plus
                val valid = allies(player).filter { c -> inFrontLine(c) && c.getLevel() != null && c.getLevel()!! <= max }
                if (valid.isEmpty()) return false
                pick(room, effect, "Elige 1 aliado de tu línea frontal nivel " + max + " o menor: declarará ataque.", "Elegido", player.field, ids(valid), 1)
                return true
            }
            is ExtraHit -> {
                val c = room.combat
                if (c == null || c.step != Combat.Step.DAMAGE) return false
                c.hitsLeft = (if (c.hitsLeft == null) 0 else c.hitsLeft!!) + 1
                c.phase = Combat.Phase.ACT
                log(room, "El atacante inflige daño 1 vez más (" + effect.sourceName + ")", null)
                return false
            }
            is SkipBlockStep -> {
                if (room.combat != null) {
                    room.combat!!.skipBlock = true
                    log(room, "El ataque salta el paso de bloqueo (" + effect.sourceName + ")", null)
                }
                return false
            }
            is ReduceRepeatedly -> {
                val total = if (step.times < 0) effect.lastCount else step.times
                val valid = enemyTargets(room, effect, CardFilter.creatures())
                if (effect.repeatsDone >= total || valid.isEmpty()) {
                    effect.repeatsDone = 0
                    return false
                }
                val opponent = room.getPlayer(room.getOpponentId(userId))
                pick(room, effect, "Elige 1 enemigo: reduce en " + step.amount + " su fuerza" + (if ("attack" == step.until) " hasta el final del ataque" else "")
                        + " (" + (effect.repeatsDone + 1) + " de " + total + "). Puedes no elegir más.", "Reducir",
                    frontLine(opponent!!.field), ids(valid), 1)
                room.prompt!!.min = 0
                return true
            }
            is TributesToDeckBottomForKeyword -> {
                val f = step.filter!!
                val valid = player.tributeZone.filter { f.matches(it) }
                if (valid.size < step.count) return false
                pick(room, effect, "Puedes descartar " + step.count + " tributos " + step.filter.describe() + " para que " + effect.sourceName + " gane "
                        + step.keyword + " 1 hasta el final del ataque (tantas veces como quieras). Elige ninguno para terminar.", "Descartar",
                    player.tributeZone, ids(valid), step.count)
                room.prompt!!.min = 0
                return true
            }
            is ExtraCombatHitThenEndTurn -> {
                room.usedThisTurn.add(EXTRA_HIT + userId)
                log(room, "La próxima vez que un aliado de " + player.username + " inflija daño directo por combate este turno, inflige daño 1 vez más",
                    null)
                return false
            }
            is BounceChosen -> {
                val opponent = room.getPlayer(room.getOpponentId(userId))
                var any = false
                for (target in validTargets(room, effect, opponent)) {
                    any = true
                    if (keywords(target).contains(UNREMOVABLE)) {
                        log(room, name(target) + " no puede ser removida por efectos oponentes", null)
                        continue
                    }
                    if (deferRemoval(room, target, opponent, false, CAUSE_EFFECT, userId, effect.sourceInstanceId, effect)) continue
                    removeFromField(opponent, target)
                    opponent!!.hand.add(target)
                    record(room, GameEvent.SEND, userId, target, "field", "hand", effect, java.util.Map.of<String, Any?>("ownerId", opponent.userId),
                        player.username + " sube a " + name(target) + " a la mano de " + opponent.username + " (" + effect.sourceName + ")")
                }
                effect.lastDone = any
                return false
            }
            is NoUntapChosen -> {
                val opponent = room.getPlayer(room.getOpponentId(userId))
                for (id in effect.targetIds) {
                    val target = (if (opponent == null) null else find(opponent.field, id)) ?: continue
                    // Counted down at each change of turn: it is 1 during its owner's next turn
                    val turns = if (opponent!!.userId == room.currentTurnUserId) 3 else 2
                    if (step.wholeTurn) {
                        target.noUntap = turns
                        floating(target, effect, "No se puede reactivar durante el próximo turno de " + opponent.username, "nextTurn", 0, null)
                        log(room, name(target) + " no se podrá reactivar durante el próximo turno de " + opponent.username + " (" + effect.sourceName + ")", null)
                    } else {
                        target.noStartUntap = turns
                        floating(target, effect, "No se reactiva al inicio del próximo turno de " + opponent.username, "nextTurn", 0, null)
                        log(room, name(target) + " no se reactivará al inicio del próximo turno de " + opponent.username + " (" + effect.sourceName + ")", null)
                    }
                }
                return false
            }
            is ChooseWeakestEnemy -> {
                val valid = enemyTargets(room, effect, step.filter)
                if (valid.isEmpty()) return false
                val min = valid.minOf { strength(it) }
                val ties = valid.filter { c -> strength(c) == min }
                if (ties.size == 1) {
                    effect.targetIds.add(ties[0].instanceId)
                    record(room, GameEvent.TARGET, userId, ties[0], null, null, effect, java.util.Map.of<String, Any?>("targetIds", java.util.List.of(ties[0].instanceId)),
                        player.username + " elige como objetivo a " + name(ties[0]) + " (" + effect.sourceName + ")")
                    return false
                }
                val opponent = room.getPlayer(room.getOpponentId(userId))
                pick(room, effect, "Varios enemigos " + step.filter!!.describe() + " tienen la menor fuerza (" + min + "): elige 1.",
                    "Objetivo", frontLine(opponent!!.field), ids(ties), 1)
                return true
            }
            else -> throw IllegalStateException("Paso de otra familia: " + step)
        }
    }

    @JvmStatic
    fun applyStep(
        room: GameRoom, effect: PendingEffect, step: EffectStep, player: PlayerState, picks: List<String>,
        order: List<String>?, repeat: BooleanArray,
    ): String? {
        val userId = player.userId
        when (step) {
            is GroupUp -> {
                val self = find(player.field, effect.sourceInstanceId)
                if (!(picks.isEmpty() || self == null)) {
                    var gained = 0
                    for (id in picks) {
                        val ally = find(player.field, id)
                        if (ally == null || ally.tapped) return "Ese aliado ya no está activo"
                        ally.tapped = true
                        gained += strength(ally)
                        record(room, GameEvent.TAP, userId, ally, null, null, effect, null, player.username + " agota a " + name(ally) + " (Agrupar)")
                    }
                    self.turnStrength = self.turnStrength + gained
                    // "Letalidad 1 por cada aliado agotado" (designer ruling 2026-10-31)
                    val lethality = "Letalidad " + picks.size
                    self.turnKeywords!!.add(lethality)
                    if (room.combat != null) {
                        room.combat!!.untilEndOfAttack.add(self.instanceId + "|" + STRENGTH_ENTRY + gained)
                        room.combat!!.untilEndOfAttack.add(self.instanceId + "|" + lethality)
                    }
                    val extra = if (picks.size == 1) "1 vez extra" else picks.size.toString() + " veces extra"
                    floating(self, effect, "Gana fuerza " + gained + " hasta el final del ataque (Agrupar)", "attack", gained, null)
                    floating(self, effect, "Inflige daño " + extra + " (Agrupar)", "attack", 0, lethality)
                    record(room, GameEvent.GAIN, userId, self, null, null, effect, java.util.Map.of<String, Any?>("keyword", "Agrupar", "strength", gained),
                        name(self) + " agrupa: gana fuerza " + gained + " e inflige daño " + extra + " durante el ataque")
                }
            }
            is ChooseEnemiesLevelSum -> {
                val opponent = room.getPlayer(room.getOpponentId(userId))
                val chosen = picks.map { id -> if (opponent == null) null else find(frontLine(opponent.field), id) }
                if (chosen.contains(null)) return "Ese enemigo ya no está en la línea frontal"
                if (chosen.sumOf { c -> if (c!!.getLevel() == null) 0 else c.getLevel()!! } != step.sum) return "Sus niveles deben sumar " + step.sum
                effect.targetIds.addAll(picks)
                record(room, GameEvent.TARGET, userId, chosen[0], null, null, effect, java.util.Map.of<String, Any?>("targetIds", picks),
                    player.username + " elige como objetivo a " + chosen.joinToString(", ") { name(it) } + " (" + effect.sourceName + ")")
            }
            is PickAllyUpToSacrificedLevel -> {
                if (find(player.field, picks[0]) == null) return "Ese aliado ya no está en el campo"
                effect.subjectId = picks[0]
            }
            is ReduceRepeatedly -> {
                if (picks.isEmpty()) {
                    effect.repeatsDone = 0
                    return null
                }
                val opponent = room.getPlayer(room.getOpponentId(userId))
                val target = (if (opponent == null) null else find(frontLine(opponent.field), picks[0]))
                    ?: return "Ese enemigo ya no está en la línea frontal"
                reduceStrength(room, effect, opponent, target, step.amount, false)
                if ("attack" == step.until && room.combat != null && find(opponent!!.field, target.instanceId) != null)
                    room.combat!!.untilEndOfAttack.add(target.instanceId + "|" + STRENGTH_ENTRY + (-step.amount))
                effect.repeatsDone = effect.repeatsDone + 1
                repeat[0] = true
            }
            is TributesToDeckBottomForKeyword -> {
                if (picks.isEmpty()) return null
                if (picks.size != step.count) return "Elige exactamente " + step.count + " tributos, o ninguno"
                if (!allIn(player.tributeZone, picks)) return "Esas cartas ya no están en tus tributos"
                val discarded: MutableList<CardSlot> = ArrayList()
                for (id in picks) {
                    val card = find(player.tributeZone, id)
                    player.tributeZone.remove(card)
                    card!!.faceDown = true
                    player.deck.add(card)
                    discarded.add(card)
                }
                val self = find(player.field, effect.sourceInstanceId)
                if (self != null) {
                    val had = keywordLevel(self, step.keyword)
                    val gained = step.keyword + " " + ((had ?: 0) + 1)
                    self.turnKeywords!!.add(gained)
                    if (room.combat != null) room.combat!!.untilEndOfAttack.add(self.instanceId + "|" + gained)
                    log(room, player.username + " descarta " + step.count + " tributos (" + names(discarded).substring(2) + "): " + name(self)
                            + " tiene " + gained + " hasta el final del ataque", room.eventSeq)
                }
                repeat[0] = true
            }
            is ChooseWeakestEnemy -> {
                val opponent = room.getPlayer(room.getOpponentId(userId))
                val target = (if (opponent == null) null else find(frontLine(opponent.field), picks[0]))
                    ?: return "Ese enemigo ya no está en la línea frontal"
                effect.targetIds.add(target.instanceId)
                record(room, GameEvent.TARGET, userId, target, null, null, effect, java.util.Map.of<String, Any?>("targetIds", picks),
                    player.username + " elige como objetivo a " + name(target) + " (" + effect.sourceName + ")")
            }
            else -> {
                return "Este paso no espera ninguna elección"
            }
        }
        return null
    }
}
