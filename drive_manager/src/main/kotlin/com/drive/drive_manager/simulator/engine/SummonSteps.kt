package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.engine.EffectEngine.canPay
import com.drive.drive_manager.simulator.engine.EffectEngine.find
import com.drive.drive_manager.simulator.engine.EffectEngine.findAnywhere
import com.drive.drive_manager.simulator.engine.EffectEngine.ids
import com.drive.drive_manager.simulator.engine.EffectEngine.locate
import com.drive.drive_manager.simulator.engine.EffectEngine.log
import com.drive.drive_manager.simulator.engine.EffectEngine.name
import com.drive.drive_manager.simulator.engine.EffectEngine.triggerEvent
import com.drive.drive_manager.simulator.engine.EffectEngine.zone
import com.drive.drive_manager.simulator.engine.EffectEngine.zoneName
import com.drive.drive_manager.simulator.engine.EffectStep.*
import com.drive.drive_manager.simulator.engine.Rituals.addsUpTo
import com.drive.drive_manager.simulator.engine.SpecialRequirements.SpecialRequirement
import com.drive.drive_manager.simulator.engine.SpecialRequirements.specialRequirement
import com.drive.drive_manager.simulator.engine.SpecialSummons.specialSummon
import com.drive.drive_manager.simulator.engine.StepHelpers.declaredFrom
import com.drive.drive_manager.simulator.engine.StepHelpers.declaredMaterials
import com.drive.drive_manager.simulator.engine.StepHelpers.freeLevelSum
import com.drive.drive_manager.simulator.engine.StepHelpers.likeSubject
import com.drive.drive_manager.simulator.engine.StepHelpers.pick
import com.drive.drive_manager.simulator.engine.StepHelpers.placeOnField
import com.drive.drive_manager.simulator.engine.StepHelpers.subject
import com.drive.drive_manager.simulator.engine.StepHelpers.zones
import com.drive.drive_manager.simulator.engine.StepHelpers.zonesText
import com.drive.drive_manager.simulator.engine.Summons.summonByEffect
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState
import com.drive.drive_manager.simulator.room.Zone

/** Summons a basic action does not cover. Each step: whether it can be done, carrying it out, and applying the player's choice. */
object SummonSteps {

    @JvmStatic
    fun possibleStep(room: GameRoom, effect: PendingEffect, step: EffectStep): Boolean {
        val player = room.getPlayer(effect.controllerId)!!
        val onField = find(player.field, effect.sourceInstanceId)
        return when (step) {
            is SummonFreeLevelSum -> !freeLevelSum(room, effect, step).isEmpty()
            is SpecialSelfDeclaredSummoner -> {
                val self = find(player.hand, effect.sourceInstanceId)
                val req = if (self == null) null else specialRequirement(self)
                req != null && self!!.specialCost != null && !declaredFrom(player, self, req, step.zones).isEmpty()
                        && canPay(room, player.userId, self.specialCost!! - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind, self, null))
            }
            is ChooseOpponentTribute -> {
                val opponent = room.getPlayer(room.getOpponentId(player.userId))
                // Java's method reference (c.filter()::matches) read the filter at once
                opponent != null && step.filter!!.let { f -> opponent.tributeZone.any { f.matches(it) } }
            }
            else -> true
        }
    }

    @JvmStatic
    fun runStep(room: GameRoom, effect: PendingEffect, step: EffectStep): Boolean {
        val player = room.getPlayer(effect.controllerId)!!
        val userId = player.userId

        when (step) {
            is SpecialSelfDeclaredSummoner -> {
                val self = find(player.hand, effect.sourceInstanceId)
                val req = (if (self == null) null else specialRequirement(self)) ?: return false
                self!!
                if (effect.ritualSummonerId == null) {
                    val valid = declaredFrom(player, self, req, step.zones)
                    if (valid.isEmpty()) return false
                    pick(room, effect, "Elige la criatura de tu " + zonesText(step.zones) + " que invoca especialmente a " + name(self) + " (nivel " + req.level
                            + "). Se convierte en su material.", "Invocador", zones(player, step.zones), ids(valid), 1)
                    room.prompt!!.min = 0
                    return true
                }
                val summoner = findAnywhere(room, userId, effect.ritualSummonerId)
                val materials = declaredMaterials(player, self, req, summoner)
                pick(room, effect, "Elige los materiales de " + name(self) + (if (req.levelSum > 0) ": deben sumar exactamente " + req.levelSum + " en nivel." else "."),
                        "Material", zones(player, java.lang.String.join(",", req.materialZones!!)), ids(materials), materials.size)
                room.prompt!!.min = 1
                if (req.levelSum > 0) room.prompt!!.maxLevelSum = req.levelSum
                return true
            }
            is MaterializeSelfOnTriggerAlly -> {
                val trigger = triggerEvent(room, effect)
                val by = if (trigger == null || trigger.data == null) null else trigger.data!!["byCard"]
                val ally = if (by == null) null else find(player.field, by as String)
                val self = find(player.hand, effect.sourceInstanceId)
                if (ally == null || self == null) return false
                val req = SpecialRequirement("materialization", if (ally.getLevel() == null) 0 else ally.getLevel()!!, 0, 0, 0, true, 0, 0, java.util.List.of(), false, null, false, false)
                val free = if (self.specialCost == null) 0 else self.specialCost!!
                specialSummon(room, player, self, req, ally, java.util.List.of(), "hand", free, effect)
                effect.lastDone = true
                return false
            }
            is ChooseOpponentTribute -> {
                val opponent = room.getPlayer(room.getOpponentId(userId))
                val valid = if (opponent == null) java.util.List.of() else step.filter!!.let { f -> opponent.tributeZone.filter { f.matches(it) } }
                if (valid.isEmpty()) return false
                pick(room, effect, "Elige 1 criatura " + step.filter!!.describe() + " de los tributos de tu rival.", "Elegir", opponent!!.tributeZone, ids(valid), 1)
                return true
            }
            is PlaceChosenTributeAndSelf -> {
                val opponent = room.getPlayer(room.getOpponentId(userId))
                val chosen = if (opponent == null || effect.ritualTargetId == null) null else find(opponent.tributeZone, effect.ritualTargetId)
                effect.ritualTargetId = null
                if (chosen != null) placeOnField(room, effect, opponent!!, chosen, "tributeZone", true, false)
                val self = locate(player, effect.sourceInstanceId)
                if (self != null && !Zone.FIELD.`is`(self.zone)) placeOnField(room, effect, player, self.card!!, self.zone, true, false)
                return false
            }
            is SummonLikeSubject -> {
                val ally = subject(room, effect)
                val valid = if (ally == null) java.util.List.of() else likeSubject(player, step, ally)
                if (valid.isEmpty()) {
                    log(room, "No hay criaturas que " + effect.sourceName + " pueda invocar", null)
                    return false
                }
                pick(room, effect, "Invoca 1 criatura " + step.filter!!.describe() + " nivel " + ally!!.getLevel() + " distinta de " + name(ally)
                        + " de tus " + zoneName(step.zone) + " sin pagar su coste. Entra en tu línea frontal y robas 1 carta.", "Invocar",
                        zone(player, step.zone), ids(valid), 1)
                return true
            }
            is SummonFreeLevelSum -> {
                val valid = freeLevelSum(room, effect, step)
                if (valid.isEmpty()) return false
                pick(room, effect, "Puedes invocar criaturas " + step.filter!!.describe() + " de tu " + zoneName(step.zone)
                        + " que en nivel sumen hasta " + step.maxLevelSum + ", sin pagar sus costes. Entran en Retaguardia y robas 1 carta por cada una.",
                        "Invocar", zone(player, step.zone), ids(valid), valid.size)
                room.prompt!!.min = 0
                room.prompt!!.maxLevelSum = step.maxLevelSum
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
            is SpecialSelfDeclaredSummoner -> {
                if (picks.isEmpty()) {
                    effect.ritualSummonerId = null
                    return null
                }
                val self = find(player.hand, effect.sourceInstanceId)
                val req = (if (self == null) null else specialRequirement(self)) ?: return "Esa carta ya no está en tu mano"
                self!!
                if (effect.ritualSummonerId == null) {
                    if (find(declaredFrom(player, self, req, step.zones), picks[0]) == null) return "Esa criatura no puede ser el invocador"
                    effect.ritualSummonerId = picks[0]
                    repeat[0] = true
                    return null
                }
                val summoner = findAnywhere(room, userId, effect.ritualSummonerId)
                val valid = declaredMaterials(player, self, req, summoner)
                val picked = picks.map { id -> find(valid, id) }
                if (summoner == null || picked.contains(null)) return "Esa criatura ya no está disponible"
                @Suppress("UNCHECKED_CAST")
                val materials = picked as List<CardSlot>
                if (if (req.levelSum > 0) !addsUpTo(materials, req.levelSum) else materials.size < Math.max(1, req.materialCount))
                    return if (req.levelSum > 0) "Los materiales deben sumar exactamente " + req.levelSum + " en nivel" else "Elige " + req.materialCount + " materiales"
                if (!canPay(room, userId, self.specialCost!! - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind, self, null)))
                    return "No tienes gemas suficientes"
                val all: MutableList<CardSlot> = ArrayList(java.util.List.of(summoner))
                all.addAll(materials)
                effect.ritualSummonerId = null
                specialSummon(room, player, self, req, null, all, "hand", 0, effect)
                effect.summonedId = self.instanceId
                effect.lastDone = true
            }
            is ChooseOpponentTribute -> {
                val opponent = room.getPlayer(room.getOpponentId(userId))
                if (opponent == null || find(opponent.tributeZone, picks[0]) == null) return "Esa carta ya no está en los tributos de tu rival"
                effect.ritualTargetId = picks[0]
            }
            is SummonFreeLevelSum -> {
                val valid = freeLevelSum(room, effect, step)
                val chosen: MutableList<CardSlot> = ArrayList()
                for (id in picks) {
                    val card = find(valid, id) ?: return "Ya no puedes invocar esa criatura"
                    chosen.add(card)
                }
                if (chosen.sumOf { c -> c.getLevel()!! } > step.maxLevelSum)
                    return "Sus niveles deben sumar " + step.maxLevelSum + " o menos"
                for (card in chosen) summonByEffect(room, effect, player, card, step.zone, card.cost!!, false)
                effect.lastDone = !chosen.isEmpty()
            }
            is SummonLikeSubject -> {
                val ally = subject(room, effect)
                val card = (if (ally == null) null else find(likeSubject(player, step, ally), picks[0])) ?: return "Ya no puedes invocar esa criatura"
                summonByEffect(room, effect, player, card, step.zone, card.cost!!, true)
                effect.summonedId = card.instanceId
                effect.lastDone = true
            }
            else -> {
                return "Este paso no espera ninguna elección"
            }
        }
        return null
    }
}
