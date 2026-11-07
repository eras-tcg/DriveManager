package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.engine.Chain.pending
import com.drive.drive_manager.simulator.engine.EffectEngine.*
import com.drive.drive_manager.simulator.engine.EffectStep.*
import com.drive.drive_manager.simulator.engine.Removals.*
import com.drive.drive_manager.simulator.engine.StepHelpers.*
import com.drive.drive_manager.simulator.engine.SpecialRequirements.*
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/** Allies, their materials and Recursos. Each step: whether it can be done, carrying it out, and applying the player's choice. */
object AllySteps {

    @JvmStatic
    fun possibleStep(room: GameRoom, effect: PendingEffect, step: EffectStep): Boolean {
        val player = room.getPlayer(effect.controllerId)!!
        val onField = find(player.field, effect.sourceInstanceId)
        return when (step) {
            is AttachLowerFromTributes -> {
                val trigger = triggerEvent(room, effect)
                val ally = if (trigger == null) null else find(player.field, trigger.cardInstanceId)
                ally != null && ally.getLevel() != null && player.tributeZone
                    .any { c -> step.filter().matches(c) && c.getLevel() != null && c.getLevel()!! < ally.getLevel()!! }
            }
            is TributeAlliesLevelSum -> {
                val valid = allies(player).filter { step.filter().matches(it) }
                valid.size >= step.minCount() && valid.sumOf { c -> if (c.getLevel() == null) 0 else c.getLevel()!! } >= step.minSum()
            }
            is PlaceFromHandUntilTurnEnd -> player.hand.any { step.filter().matches(it) }
            is TributeAllyMaterialWhere -> !allyMaterialsWhere(player, step).isEmpty()
            is SacrificeAlly -> !sacrificable(effect, player, step).isEmpty()
            is TributeAllyMaterial -> !allyMaterials(player).isEmpty()
            is PickAllyWithHigherAlly -> !withHigherAlly(player).isEmpty()
            is PickAllyWithMaterials -> allies(player).any { c -> c.materials!!.size >= step.count() }
            is TributeAllyResources -> allies(player).any { a -> a.resources!!.any { step.filter().matches(it) } }
            is AddResourceToHigherAlly -> !higherAllyResources(player, step).isEmpty()
            is AttachSelfAsResourceToAlly -> {
                val at = locate(player, effect.sourceInstanceId)
                at != null && allies(player).any { c -> c !== at.card() }
            }
            else -> true
        }
    }

    @JvmStatic
    fun runStep(room: GameRoom, effect: PendingEffect, step: EffectStep): Boolean {
        val player = room.getPlayer(effect.controllerId)!!
        val userId = player.userId

        when (step) {
            is MaterialOfExhaustedAllyToBack -> {
                val valid = exhaustedAllyMaterials(player, step.filter())
                if (valid.isEmpty()) return false
                pick(room, effect, "Puedes colocar 1 " + step.filter().describe() + " de los materiales de un aliado agotado en tu retaguardia.", "Colocar",
                    valid, ids(valid), 1)
                room.prompt!!.min = 0
                return true
            }
            is TributeAllyResources -> {
                val ally = if (effect.subjectId == null) null else find(player.field, effect.subjectId)
                if (ally == null) {
                    val valid = allies(player).filter { a -> a.resources!!.any { step.filter().matches(it) } }
                    if (valid.isEmpty()) return false
                    pick(room, effect, "Elige 1 aliado con recursos " + step.filter().describe() + ".", "Elegido", player.field, ids(valid), 1)
                    return true
                }
                val valid = ally.resources!!.filter { step.filter().matches(it) }
                pick(room, effect, "Tributa hasta " + step.max() + " recursos " + step.filter().describe() + " de " + name(ally) + ".", "Tributar",
                    ally.resources, ids(valid), Math.min(step.max(), valid.size))
                room.prompt!!.min = 0
                return true
            }
            is AddResourceToHigherAlly -> {
                if (effect.ritualTargetId == null) {
                    val valid = higherAllyResources(player, step)
                    if (valid.isEmpty()) return false
                    pick(room, effect, "Elige 1 carta " + step.filter().describe() + " de tus " + zonesText(step.zone()) + ": será recurso de un aliado de nivel mayor.",
                        "Recurso", zones(player, step.zone()), ids(valid), 1)
                    return true
                }
                val card = findAnywhere(room, userId, effect.ritualTargetId)
                val valid: List<CardSlot> = if (card == null) java.util.List.of() else higherThan(player, card).filter { c -> c !== card }
                if (valid.isEmpty()) {
                    effect.ritualTargetId = null
                    return false
                }
                pick(room, effect, "Elige el aliado de nivel mayor que recibe a " + name(card) + " como recurso.", "Recibe", player.field, ids(valid), 1)
                return true
            }
            is ReturnAllyOfSummonedLevel -> {
                val placed = findAnywhere(room, userId, effect.summonedId)
                if (placed == null || placed.getLevel() == null) return false
                val valid = allies(player).filter { c -> c !== placed && placed.getLevel() == c.getLevel() }
                if (valid.isEmpty()) return false
                pick(room, effect, "Sube a tu mano 1 aliado nivel " + placed.getLevel() + ".", "A tu mano", player.field, ids(valid), 1)
                return true
            }
            is AttachSelfAsResourceToAlly -> {
                val at = locate(player, effect.sourceInstanceId)
                val valid: List<CardSlot> = if (at == null) java.util.List.of() else allies(player).filter { c -> c !== at.card() }
                if (valid.isEmpty()) return false
                pick(room, effect, "Elige el aliado que recibe a " + effect.sourceName + " como recurso.", "Recibe", player.field, ids(valid), 1)
                return true
            }
            is PickAllyWithHigherAlly -> {
                val valid = withHigherAlly(player)
                if (valid.isEmpty()) return false
                pick(room, effect, "Elige el aliado que será material de otro de nivel mayor.", "Material", player.field, ids(valid), 1)
                return true
            }
            is AttachSubjectToHigherAlly -> {
                val subject = subject(room, effect) ?: return false
                val valid = higherThan(player, subject)
                if (valid.isEmpty()) return false
                pick(room, effect, "Elige el aliado de nivel mayor que recibe a " + name(subject) + " como material.", "Recibe", player.field, ids(valid), 1)
                return true
            }
            is PickAllyWithMaterials -> {
                val valid = allies(player).filter { c -> c.materials!!.size >= step.count() }
                if (valid.isEmpty()) return false
                pick(room, effect, "Elige 1 aliado con " + step.count() + " o más materiales.", "Elegido", player.field, ids(valid), 1)
                return true
            }
            is ActivateSubjectSummonEffect -> {
                val ally = subject(room, effect)
                val summon = if (ally == null) null else CardEffects.of(ally.cardName)
                    .firstOrNull { sc -> sc.moment == EffectScript.Moment.ON_SUMMON }
                if (summon == null) {
                    log(room, "No hay un efecto En Invocación que activar (" + effect.sourceName + ")", null)
                    return false
                }
                // Activated as the ally's own: a new link of the chain, after this one resolves
                val link = pending(room, summon, userId, ally, room.eventSeq)
                link.isConfirmed = true
                link.isOrdered = true
                room.triggerQueue.add(0, link)
                log(room, name(ally) + " activa su efecto En Invocación (" + effect.sourceName + ")", null)
                return false
            }
            is TributeAlliesLevelSum -> {
                val valid = allies(player).filter { step.filter().matches(it) }
                pick(room, effect, "Tributa " + step.minCount() + " o más aliados " + step.filter().describe() + " que sumen " + step.minSum() + " o más en nivel.",
                    "Tributar", player.field, ids(valid), valid.size)
                room.prompt!!.min = step.minCount()
                return true
            }
            is PlaceFromHandUntilTurnEnd -> {
                val valid = player.hand.filter { step.filter().matches(it) }
                if (valid.isEmpty()) return false
                pick(room, effect, "Puedes colocar 1 criatura " + step.filter().describe() + " de tu mano en tu retaguardia; al final del turno vuelve a tu mano.",
                    "A la retaguardia", player.hand, ids(valid), 1)
                room.prompt!!.min = 0
                return true
            }
            is TributeAllyMaterialWhere -> {
                val valid = allyMaterialsWhere(player, step)
                if (valid.isEmpty()) return false
                pick(room, effect, "Tributa 1 material " + step.material().describe() + " de un aliado " + step.ally().describe() + ".", "Tributar", valid, ids(valid), 1)
                return true
            }
            is ActivateTributedSummonEffect -> {
                val ally = subject(room, effect)
                val materialId = if (effect.lastTributed.isEmpty()) null else effect.lastTributed[0]
                val material = if (materialId == null) null else find(player.tributeZone, materialId)
                val summon = if (material == null) null else CardEffects.of(material.cardName)
                    .firstOrNull { sc -> sc.moment == EffectScript.Moment.ON_SUMMON }
                if (ally == null || summon == null) {
                    log(room, "No hay un efecto En Invocación que activar (" + effect.sourceName + ")", null)
                    return false
                }
                // The ally activates it as its own: a new link of the chain, after this one resolves
                val link = pending(room, summon, userId, ally, room.eventSeq)
                link.isConfirmed = true
                link.isOrdered = true
                room.triggerQueue.add(0, link)
                log(room, name(ally) + " activa el efecto En Invocación de " + name(material) + " (" + effect.sourceName + ")", null)
                return false
            }
            is AttachSubjectToSummoned -> {
                val ally = find(player.field, effect.subjectId)
                val summoned = find(player.field, effect.summonedId)
                if (ally == null || summoned == null || ally === summoned) return false
                player.field.remove(ally)
                leaveField(ally)
                val materials: List<CardSlot> = ArrayList(ally.materials!!)
                ally.materials!!.clear()
                summoned.materials!!.add(ally)
                summoned.materials!!.addAll(materials)
                record(room, GameEvent.SEND, userId, ally, "field", "materials", effect, java.util.Map.of<String, Any?>("to", summoned.instanceId),
                    player.username + " añade " + name(ally) + (if (materials.isEmpty()) "" else " y sus " + materials.size + " " +
                            plural(materials.size, "material", "materiales")) + " como materiales de " + name(summoned))
                return false
            }
            is AttachLowerFromTributes -> {
                val trigger = triggerEvent(room, effect)
                val ally = if (trigger == null) null else find(player.field, trigger.cardInstanceId)
                if (ally == null || ally.getLevel() == null) return false
                val valid = player.tributeZone
                    .filter { c -> step.filter().matches(c) && c.getLevel() != null && c.getLevel()!! < ally.getLevel()!! }
                    .map { it.instanceId }
                if (valid.isEmpty()) return false
                effect.subjectId = ally.instanceId
                pick(room, effect, "Añade a " + name(ally) + " 1 criatura " + step.filter().describe() + " de menor nivel de tus tributos como material.",
                    "Material", player.tributeZone, valid, 1)
                return true
            }
            is SacrificeAlly -> {
                val valid = sacrificable(effect, player, step)
                if (valid.isEmpty()) return false
                pick(room, effect, if (step.toUntapAnother())
                    "Sacrifica 1 aliado. No puede ser el único aliado agotado que queda por reactivar."
                else "Sacrifica 1 aliado.", "Sacrificar", player.field, ids(valid), 1)
                return true
            }
            is TributeAllyMaterial -> {
                val valid = allyMaterials(player)
                if (valid.isEmpty()) return false
                pick(room, effect, "Tributa 1 material de 1 de tus aliados.", "Tributar", valid, ids(valid), 1)
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
            is MaterialOfExhaustedAllyToBack -> {
                if (!picks.isEmpty()) {
                    val card = find(exhaustedAllyMaterials(player, step.filter()), picks[0]) ?: return "Ese material ya no está"
                    for (ally in player.field) ally.materials!!.remove(card)
                    player.hand.add(card)
                    placeOnField(room, effect, player, card, "hand", false, false)
                    effect.isLastDone = true
                }
            }
            is TributeAllyResources -> {
                if (effect.subjectId == null || find(player.field, effect.subjectId) == null) {
                    if (find(player.field, picks[0]) == null) return "Ese aliado ya no está en el campo"
                    effect.subjectId = picks[0]
                    repeat[0] = true
                    return null
                }
                val ally = find(player.field, effect.subjectId)
                for (id in picks) {
                    val resource = find(ally.resources, id) ?: return "Ese recurso ya no está"
                    ally.resources!!.remove(resource)
                    resource.faceDown = false
                    player.tributeZone.add(resource)
                    record(room, GameEvent.TRIBUTE, userId, resource, "resources", "tributeZone", effect, java.util.Map.of<String, Any?>("byCard", effect.sourceInstanceId),
                        player.username + " tributa " + name(resource) + " de los recursos de " + name(ally))
                }
                effect.lastCount = picks.size
            }
            is AddResourceToHigherAlly -> {
                if (effect.ritualTargetId == null) {
                    effect.ritualTargetId = picks[0]
                    repeat[0] = true
                    return null
                }
                val card = findAnywhere(room, userId, effect.ritualTargetId)
                val ally = find(player.field, picks[0])
                effect.ritualTargetId = null
                if (card == null || ally == null) return "Esa carta ya no está disponible"
                val from = zoneOf(player, step.zone(), card)
                zone(player, from).remove(card)
                card.faceDown = false
                ally.resources!!.add(card)
                effect.isLastDone = true
                record(room, GameEvent.SEND, userId, card, from, "resources", effect, java.util.Map.of<String, Any?>("to", ally.instanceId),
                    player.username + " añade " + name(card) + " como recurso de " + name(ally))
            }
            is ReturnAllyOfSummonedLevel -> {
                val ally = find(player.field, picks[0]) ?: return "Ese aliado ya no está en el campo"
                removeFromField(player, ally)
                player.hand.add(ally)
                effect.lastTributed = ArrayList(java.util.List.of(ally.instanceId))
                record(room, GameEvent.SEND, userId, ally, "field", "hand", effect, null,
                    player.username + " sube " + name(ally) + " a su mano (" + effect.sourceName + ")")
            }
            is AttachSelfAsResourceToAlly -> {
                if (find(player.field, picks[0]) == null) return "Ese aliado ya no está en el campo"
                effect.subjectId = picks[0]
                attachSelfToSubject(room, effect, player, false)
            }
            is PickAllyWithHigherAlly -> {
                if (find(player.field, picks[0]) == null) return "Ese aliado ya no está en el campo"
                effect.subjectId = picks[0]
            }
            is AttachSubjectToHigherAlly -> {
                val subject = subject(room, effect)
                val holder = find(player.field, picks[0])
                if (subject == null || holder == null || !higherThan(player, subject).contains(holder)) return "Debe ser un aliado de nivel mayor"
                removeFromField(player, subject)
                subject.faceDown = false
                holder.materials!!.add(subject)
                effect.isLastDone = true
                record(room, GameEvent.SEND, userId, subject, "field", "materials", effect, java.util.Map.of<String, Any?>("to", holder.instanceId),
                    player.username + " añade " + name(subject) + " como material de " + name(holder) + " (" + effect.sourceName + ")")
            }
            is PickAllyWithMaterials -> {
                if (find(player.field, picks[0]) == null) return "Ese aliado ya no está en el campo"
                effect.subjectId = picks[0]
            }
            is TributeAlliesLevelSum -> {
                val chosen: MutableList<CardSlot> = ArrayList()
                for (id in picks) {
                    val c = find(player.field, id)
                    if (c == null || !step.filter().matches(c)) return "Ese aliado ya no está en el campo"
                    chosen.add(c)
                }
                val sum = chosen.sumOf { c -> if (c.getLevel() == null) 0 else c.getLevel()!! }
                if (chosen.size < step.minCount() || sum < step.minSum())
                    return "Elige " + step.minCount() + " o más aliados que sumen " + step.minSum() + " o más en nivel (suman " + sum + ")"
                for (c in chosen) {
                    player.field.remove(c)
                    for (a in concat(c.materials, c.resources)) player.discardPile.add(a)
                    c.materials = ArrayList()
                    c.resources = ArrayList()
                    leaveField(c)
                    player.tributeZone.add(c)
                    record(room, GameEvent.TRIBUTE, userId, c, "field", "tributeZone", effect, java.util.Map.of<String, Any?>("byCard", effect.sourceInstanceId),
                        player.username + " tributa a " + name(c) + " (" + effect.sourceName + ")")
                }
            }
            is PlaceFromHandUntilTurnEnd -> {
                if (picks.isEmpty()) return null
                val card = find(player.hand, picks[0]) ?: return "Esa carta ya no está en tu mano"
                player.hand.remove(card)
                card.faceDown = false
                card.tapped = false
                card.x = 50.0
                card.y = 75.0
                card.returnAtTurnEnd = true
                player.field.add(card)
                // Its details show the condition it is placed under (Janne's inherited effect)
                val from = CardEffects.inheritedFrom(effect.scriptId)
                val holder = if (from == null) null else find(player.field, effect.sourceInstanceId)
                val giver = if (holder == null) from else holder.resources!!.map { it.cardName }
                    .firstOrNull { from!!.equals(it, ignoreCase = true) } ?: from
                floating(card, effect, "Colocada hasta el final del turno: entonces vuelve a tu mano" + (if (giver == null) "" else " (heredado de $giver)"),
                    "turn", 0, null)
                record(room, GameEvent.SEND, userId, card, "hand", "field", effect, java.util.Map.of<String, Any?>("placed", true),
                    player.username + " coloca " + name(card) + " en su retaguardia hasta el final del turno (" + effect.sourceName + ")")
            }
            is TributeAllyMaterialWhere -> {
                val holder = allies(player).firstOrNull { a -> step.ally().matches(a) && find(a.materials, picks[0]) != null }
                    ?: return "Ese material ya no está"
                val material = find(holder.materials, picks[0])
                holder.materials!!.remove(material)
                material.faceDown = false
                player.tributeZone.add(material)
                effect.subjectId = holder.instanceId
                effect.lastTributed = ArrayList(java.util.List.of(material.instanceId))
                record(room, GameEvent.TRIBUTE, userId, material, "materials", "tributeZone", effect, java.util.Map.of<String, Any?>("byCard", effect.sourceInstanceId),
                    player.username + " tributa " + name(material) + " de los materiales de " + name(holder) + " (" + effect.sourceName + ")")
            }
            is SacrificeAlly -> {
                val ally = find(player.field, picks[0]) ?: return "Ese aliado ya no está en el campo"
                effect.lastTributed = ArrayList(java.util.List.of(ally.instanceId))
                destroy(room, ally, player, true, CAUSE_EFFECT, userId, null, effect)
            }
            is AttachLowerFromTributes -> {
                val ally = find(player.field, effect.subjectId)
                val card = find(player.tributeZone, picks[0])
                if (ally == null || card == null) return "Esa carta ya no está disponible"
                player.tributeZone.remove(card)
                card.faceDown = false
                ally.materials!!.add(card)
                record(room, GameEvent.SEND, userId, card, "tributeZone", "materials", effect, java.util.Map.of<String, Any?>("to", ally.instanceId),
                    player.username + " añade " + name(card) + " como material de " + name(ally) + " (" + effect.sourceName + ")")
            }
            is TributeAllyMaterial -> {
                val holder = allies(player).firstOrNull { a -> find(a.materials, picks[0]) != null } ?: return "Ese material ya no está"
                val material = find(holder.materials, picks[0])
                holder.materials!!.remove(material)
                material.faceDown = false
                player.tributeZone.add(material)
                record(room, GameEvent.TRIBUTE, userId, material, "materials", "tributeZone", effect, java.util.Map.of<String, Any?>("byCard", effect.sourceInstanceId),
                    player.username + " tributa " + name(material) + " de los materiales de " + name(holder) + " (" + effect.sourceName + ")")
            }
            else -> {
                return "Este paso no espera ninguna elección"
            }
        }
        return null
    }
}
