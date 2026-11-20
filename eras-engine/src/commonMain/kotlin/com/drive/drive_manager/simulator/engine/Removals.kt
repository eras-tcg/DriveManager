package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.platform.Platform
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmRecord
import kotlin.math.min
import com.drive.drive_manager.simulator.engine.Chain.advance
import com.drive.drive_manager.simulator.engine.EffectEngine.ALLY_DESTROYED_BY_OPPONENT
import com.drive.drive_manager.simulator.engine.EffectEngine.CAUSE_BATTLE
import com.drive.drive_manager.simulator.engine.EffectEngine.CAUSE_EFFECT
import com.drive.drive_manager.simulator.engine.EffectEngine.CAUSE_MANUAL
import com.drive.drive_manager.simulator.engine.EffectEngine.CAUSE_RITUAL
import com.drive.drive_manager.simulator.engine.EffectEngine.CAUSE_RULES
import com.drive.drive_manager.simulator.engine.EffectEngine.UNREMOVABLE
import com.drive.drive_manager.simulator.engine.EffectEngine.concat
import com.drive.drive_manager.simulator.engine.EffectEngine.find
import com.drive.drive_manager.simulator.engine.EffectEngine.keywords
import com.drive.drive_manager.simulator.engine.EffectEngine.log
import com.drive.drive_manager.simulator.engine.EffectEngine.name
import com.drive.drive_manager.simulator.engine.EffectEngine.names
import com.drive.drive_manager.simulator.engine.EffectEngine.plural
import com.drive.drive_manager.simulator.engine.EffectEngine.record
import com.drive.drive_manager.simulator.engine.EffectEngine.rulesPrompt
import com.drive.drive_manager.simulator.engine.EffectEngine.strength
import com.drive.drive_manager.simulator.engine.EffectEngine.unaffected
import com.drive.drive_manager.simulator.engine.EffectEngine.zone
import com.drive.drive_manager.simulator.engine.EffectScript.Limit
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/** Cards leaving the field: destruction and sacrifice (rule 12, "Sacrificio y destrucción") and the replacements that can stop them ("en su lugar", Protección). */
object Removals {

    // ------------------------------------------------------------------ Destruction and sacrifice

    /**
     * Destroys a card on the field from the board (cases the engine does not play yet): it goes to its
     * owner's Descarte and its "En Destrucción" effects trigger.
     */
    @JvmStatic
    fun destroyByPlayer(room: GameRoom, userId: String?, ownerId: String?, instanceId: String?): String? {
        val error = checkOpen(room)
        if (error != null) return error
        val owner = room.getPlayer(ownerId ?: userId)
        val card = if (owner == null) null else find(owner.field, instanceId)
        if (card == null) return "La carta no está en el campo"
        destroy(room, card, owner, false, CAUSE_MANUAL, userId, null, null)
        advance(room)
        return null
    }

    /** Sacrifices one of your cards on the field from the board: destroyed, to your Tributos (rule 4.0.4.2). */
    @JvmStatic
    fun sacrificeByPlayer(room: GameRoom, userId: String?, instanceId: String?): String? {
        val error = checkOpen(room)
        if (error != null) return error
        val owner = room.getPlayer(userId)
        val card = if (owner == null) null else find(owner.field, instanceId)
        if (card == null) return "Solo puedes sacrificar tus cartas del campo"
        destroy(room, card, owner, true, CAUSE_MANUAL, userId, null, null)
        advance(room)
        return null
    }

    /**
     * A creature whose strength was reduced to 0 is destroyed right away (rule 12.0.0.2), by effect and
     * attributed to whoever reduced it.
     */
    @JvmStatic
    fun destroyIfNoStrength(room: GameRoom, userId: String?, owner: PlayerState?, card: CardSlot?) {
        if (room.status != GameRoom.RoomStatus.IN_PROGRESS || room.isEngineBusy()) return
        if ("creature" != card!!.cardType || card.strength == null) return
        if (strength(card) > 0 || find(owner!!.field, card.instanceId) == null) return
        destroy(room, card, owner, false, CAUSE_EFFECT, userId, null, null)
        advance(room)
    }

    /**
     * "sacrifica 1 [kuwal] en tu campo o mano", "sacrificar … una carta de tu mano": a card of the hand goes to the Tributos as a sacrifice. It was
     * no ally on the field, so nothing is destroyed: the event is a tribute that says it was a sacrifice ([SACRIFICED]), which "si esta carta es
     * sacrificada" reads.
     */
    @JvmStatic
    fun sacrificeFromHand(room: GameRoom, player: PlayerState, card: CardSlot, effect: PendingEffect?) {
        if (!player.hand.remove(card)) return
        card.faceDown = false
        player.tributeZone.add(card)
        val data: MutableMap<String, Any?> = Platform.hashMap()
        data["ownerId"] = player.userId
        data[SACRIFICED] = true
        if (effect != null) data["byCard"] = effect.sourceInstanceId
        record(room, GameEvent.TRIBUTE, player.userId, card, "hand", "tributeZone", effect, data,
            player.username + " sacrifica " + name(card) + " de su mano" + (if (effect != null) " (" + effect.sourceName + ")" else ""))
    }

    /** In the event of a card of the hand sacrificed ([sacrificeFromHand]): true. */
    const val SACRIFICED = "sacrificed"

    /** Remembers the strength the card has now, as it is about to leave the field ("la fuerza que tenía dicho aliado antes de ser removido"). */
    @JvmStatic
    fun rememberStrength(room: GameRoom, card: CardSlot) {
        val id = card.instanceId
        if (card.strength == null || id == null) return
        room.strengthLeft[id] = strength(card)
    }

    /** The strength the card had when it last left the field ([rememberStrength]); null: not known. */
    @JvmStatic
    fun strengthWhenLeft(room: GameRoom, instanceId: String?): Int? {
        return if (instanceId == null) null else room.strengthLeft[instanceId]
    }

    @JvmStatic
    fun checkOpen(room: GameRoom): String? {
        if (room.status != GameRoom.RoomStatus.IN_PROGRESS) return "La partida no está en curso"
        if (room.isEngineBusy()) return if (room.combat != null) "Hay un combate en curso" else "Hay un efecto resolviéndose"
        return null
    }

    /**
     * Destroys (to the Descarte) or sacrifices (to the Tributos) a card on its owner's field. Its Recursos and
     * Materiales go to the Descarte (rules 6.3.0.5, 6.4.0.5) and it loses its state on the field.
     *
     * @param byCard the card that destroyed it (in a clash), if any; for effects, the effect's card
     */
    @JvmStatic
    fun destroy(
        room: GameRoom, card: CardSlot?, owner: PlayerState?, sacrifice: Boolean, cause: String?, byId: String?,
        byCard: String?, effect: PendingEffect?,
    ): GameEvent? {
        var byCard = byCard
        // "solo puede ser afectada por copias de sí misma"
        if (unaffected(card, effect)) {
            log(room, name(card) + " solo puede ser afectada por sus copias: " + effect!!.sourceName + " no le hace nada", null)
            return null
        }
        // "no puede ser removido por efectos oponentes"
        if (effect != null && byId != null && byId != owner!!.userId && keywords(card).contains(UNREMOVABLE)) {
            log(room, name(card) + " no puede ser removida por efectos oponentes", null)
            // At strength 0 the rules destroy it anyway: not the effect, so nothing that reacts to a destruction by effect
            if ("creature" == card!!.cardType && card.strength != null && strength(card) <= 0)
                return destroy(room, card, owner, false, CAUSE_RULES, null, null, null)
            return null
        }
        // A replacement ("en su lugar", Protección): its owner chooses first; the destruction waits
        if (!sacrifice && deferRemoval(room, card, owner, true, cause, byId, byCard, effect)) return null
        // Its level as it was on the field ("1 nivel mayor que el aliado sacrificado"): leaving it, it loses its continuous levels
        val levelThere = card!!.getLevel()
        rememberStrength(room, card)
        owner!!.field.remove(card)
        val combat = room.combat
        if (combat != null && owner.userId == combat.defenderId) combat.enemyDestroyed = true
        // Its Recursos leave with it, but what they gave it "En Destrucción" still triggers (rule 11.4.0.3)
        reactionsBeforeRemoval(room, owner, card, byId)
        val inherited = card.resources!!.mapNotNull { it.cardName }
        // So do the abilities it gained between «» (GrantEffect), lost as it leaves the field
        val granted = GrantedEffects.markersOf(card)
        val materials: List<CardSlot> = ArrayList(card.materials!!)
        val hadMaterials = !materials.isEmpty()
        // "si algún aliado fue destruido por el oponente el turno anterior"
        if (byId != null && byId != owner.userId && "creature" == card.cardType)
            room.usedThisTurn.add(ALLY_DESTROYED_BY_OPPONENT + owner.userId)
        removeFromField(owner, card)
        val toZone = if (sacrifice) "tributeZone" else "discardPile"
        (if (sacrifice) owner.tributeZone else owner.discardPile).add(card)

        val by = room.getPlayer(byId)
        val who = if (by != null) by.username else "Las reglas"
        val whose = if (by != null && by !== owner) " de " + owner.username else ""
        val why = if (effect != null) " (" + effect.sourceName + ")"
        else if (CAUSE_BATTLE == cause) " en el enfrentamiento"
        else if (CAUSE_RITUAL == cause) " para el Ritual"
        else if (CAUSE_EFFECT == cause) " (fuerza 0)"
        else if (CAUSE_RULES == cause) " por tener fuerza 0" else ""
        val text = if (sacrifice) who + " sacrifica " + name(card) + why else who + " destruye " + name(card) + whose + why
        val data: MutableMap<String, Any?> = Platform.hashMap()
        data["cause"] = cause
        data["ownerId"] = owner.userId
        if (levelThere != null) data["level"] = levelThere
        if (byCard == null && effect != null) byCard = effect.sourceInstanceId
        if (byCard != null) data["byCard"] = byCard
        if (!inherited.isEmpty()) data["inherited"] = inherited
        if (!granted.isEmpty()) data["granted"] = granted
        if (hadMaterials) data["hadMaterials"] = true
        if (combat != null && card.instanceId == combat.attackerId) data["asAttacker"] = true
        val removed = record(room, if (sacrifice) GameEvent.SACRIFICE else GameEvent.DESTROY, byId, card, "field", toZone, effect, data, text)
        // Its materials went to the Descarte with it ("si esta carta es enviada a tu descarte como material")
        for (material in materials) {
            record(room, GameEvent.SEND, owner.userId, material, "materials", "discardPile", null, frozenMapOf<String, Any?>("from", card.instanceId), null)
        }
        return removed
    }

    /**
     * Mandatory reactions of its Recursos before the card is removed (Fósil: "coloca el recurso de este efecto en tu retaguardia con todos los
     * recursos [dinosaurio] de esta carta como sus recursos", only when the opponent removes it).
     */
    @JvmStatic
    fun reactionsBeforeRemoval(room: GameRoom, owner: PlayerState, card: CardSlot, byId: String?) {
        val byOpponent = byId != null && byId != owner.userId
        for (resource in ArrayList(card.resources!!)) {
            if (!card.resources!!.contains(resource)) continue
            for (r in CardEffects.replacementsOf(card)) {
                if (r.cost != CardEffects.ReplacementCost.RESOURCE_TO_BACK_WITH_OTHERS || (r.byOpponentOnly && !byOpponent)) continue
                if (CardEffects.replacementGiver(card, r) !== resource) continue
                card.resources!!.remove(resource)
                val filter = r.resourceFilter!!
                val others = card.resources!!.filter { filter.matches(it) }
                card.resources!!.removeAll(others)
                resource.resources!!.addAll(others)
                resource.faceDown = false
                resource.tapped = false
                resource.x = 50.0
                resource.y = 75.0
                owner.field.add(resource)
                record(room, GameEvent.SEND, owner.userId, resource, "resources", "field", null, frozenMapOf<String, Any?>("from", card.instanceId),
                    name(resource) + " se coloca en la retaguardia de " + owner.username + (if (others.isEmpty()) "" else " con " + others.size
                            + " " + plural(others.size, "recurso", "recursos")) + " antes de que " + name(card) + " sea removida")
            }
        }
    }

    /**
     * A card that leaves the field is a new object (rule 6.3.0.5): it loses everything that applied to it there — the
     * floating effects, gained keywords and strength, exhausted state. Field effects (Príncipe Charles) apply again
     * when it comes back, since they look at the field.
     */
    @JvmStatic
    fun leaveField(card: CardSlot) {
        // A card the field had made a creature ("coloca esta carta … como criatura de fuerza N") is its printed card again
        if (CardEffects.creatureOnFieldOnly(card)) {
            card.cardType = "utility"
            card.strength = null
        }
        card.faceDown = false
        card.tapped = false
        card.strengthModifier = 0
        card.turnStrength = 0
        card.laterStrength = 0
        card.turnKeywords!!.clear()
        card.laterKeywords!!.clear()
        card.untilNextAttack!!.clear()
        card.noUntap = 0
        card.noStartUntap = 0
        card.continuousStrength = 0
        card.continuousLevel = 0
        card.continuousKeywords!!.clear()
        card.floating!!.clear()
        card.returnAtTurnEnd = false
        card.x = null
        card.y = null
    }

    // ------------------------------------------------------------------ Replacements ("en su lugar", Protección)

    /** A way to replace a removal: the card's own or inherited replacement, or a protector destroyed instead. */
    @JvmRecord
    data class ReplacementOption(val key: String, val source: CardSlot, val replacement: CardEffects.Replacement?, val label: String?, val text: String?)

    /**
     * Rule of replacements: "cuando X fuera a ser destruida / removida: en su lugar …". When the card has one its owner
     * can use, the removal waits for their choice. Manual removals (editor mode) are never replaced.
     *
     * @return true when the removal waits (it is done or replaced once the owner answers)
     */
    @JvmStatic
    fun deferRemoval(
        room: GameRoom, card: CardSlot?, owner: PlayerState?, destroy: Boolean, cause: String?, byId: String?,
        byCard: String?, effect: PendingEffect?,
    ): Boolean {
        if (CAUSE_MANUAL == cause || card == null) return false
        if (decided(room).remove(card.instanceId)) return false
        if (replacementOptions(room, card, owner!!, destroy, byId, effect != null).isEmpty()) return false
        if (room.pendingRemovals.any { r -> r.cardId == card.instanceId }) return true
        room.pendingRemovals.add(PendingRemoval(card.instanceId, owner.userId, destroy, cause, byId, byCard,
            effect?.id))
        return true
    }

    /** The room's removals already decided, as Java's set took them: an id may be null. */
    @Suppress("UNCHECKED_CAST")
    private fun decided(room: GameRoom): MutableSet<String?> = room.removalDecided as MutableSet<String?>

    /** The other creatures of the player that can be sacrificed instead ("sacrificar otro aliado"). */
    private fun sacrificeOthers(owner: PlayerState, card: CardSlot): List<CardSlot> =
        owner.field.filter { c -> c !== card && "creature" == c.cardType }

    /** The other members of the card's formation (Enlace): "un aliado enlazado a esta carta". */
    private fun linkedAllies(owner: PlayerState, card: CardSlot): List<CardSlot> {
        val members = Formations.formationOf(owner, card.instanceId) ?: return frozenListOf()
        return owner.field.filter { c -> c !== card && members.contains(c.instanceId) }
    }

    /** The active structures of the player that can be exhausted. */
    private fun tappableStructures(owner: PlayerState): List<CardSlot> =
        owner.field.filter { c -> "structure" == c.cardType && !c.tapped && !EffectEngine.keywords(c).contains(EffectEngine.CANNOT_TAP) }

    @JvmStatic
    fun replacementOptions(room: GameRoom, card: CardSlot, owner: PlayerState, destroy: Boolean, byId: String?, byEffect: Boolean): List<ReplacementOption> {
        val options: MutableList<ReplacementOption> = ArrayList()
        val byOpponent = byId != null && byId != owner.userId
        for (r in CardEffects.replacementsOf(card)) {
            if (r.destroyOnly && !destroy) continue
            if (r.byOpponentOnly && !byOpponent) continue
            if (r.withMaterials && card.materials!!.isEmpty()) continue
            if (r.holder != null && !r.holder.matches(card)) continue
            if (r.allyTurn && room.currentTurnUserId != owner.userId) continue
            if (r.limit == Limit.DEFINITIVE && room.firstTurn) continue
            if (Limit.spent(r.limit, room.usedThisTurn, replacementKey(owner, card, r))) continue
            val payable = when (r.cost!!) {
                CardEffects.ReplacementCost.TRIBUTE_TWO_MATERIALS -> card.materials!!.size >= 2
                CardEffects.ReplacementCost.TRIBUTE_ONE_FROM_HAND -> !owner.hand.isEmpty()
                CardEffects.ReplacementCost.TRIBUTE_RESOURCES, CardEffects.ReplacementCost.RESOURCES_TO_TRIBUTES_BEFORE,
                CardEffects.ReplacementCost.TRIBUTE_RESOURCE_RETURN_TO_HAND -> {
                    val filter = r.resourceFilter!!
                    card.resources!!.any { filter.matches(it) }
                }
                CardEffects.ReplacementCost.MATERIALS_TO_BACK -> card.materials!!.any { m -> "creature" == m.cardType && r.resourceFilter!!.matches(m) }
                // "cuando esta carta fuera a ser destruida: en su lugar puedes tributar 1 de sus materiales y un aliado [ente]" (its own)
                CardEffects.ReplacementCost.GUARD_MATERIAL_AND_SACRIFICE ->
                    card.materials!!.isNotEmpty() && owner.field.any { c -> c !== card && r.resourceFilter!!.matches(c) }
                CardEffects.ReplacementCost.ALLY_SPECIAL_SUMMON,
                CardEffects.ReplacementCost.RESOURCE_TO_BACK_WITH_OTHERS, CardEffects.ReplacementCost.SACRIFICE_OTHER_ALLY_OR_HAND -> false
                CardEffects.ReplacementCost.RESOURCE_TRIBUTED_ANOTHER_TO_HAND -> card.resources!!.size >= 2
                CardEffects.ReplacementCost.ALLY_TO_LIFE_FACE_DOWN -> owner.field.any { c -> c !== card && "creature" == c.cardType }
                CardEffects.ReplacementCost.TAP_STRUCTURE -> tappableStructures(owner).isNotEmpty()
                CardEffects.ReplacementCost.DESTROY_LINKED_ALLY -> linkedAllies(owner, card).isNotEmpty()
            }
            if (payable) options.add(ReplacementOption(r.id + "@" + card.instanceId, card, r, r.label, r.text))
        }
        // "cuando algún aliado fuera a ser removido: en su lugar, puede invocar especialmente esta carta" (EXO-91 from the Tributos, SARS-2 from the hand)
        if ("creature" == card.cardType) {
            for (z in frozenListOf("hand", "tributeZone")) {
                for (c in zone(owner, z)) {
                    val s = CardEffects.allyRemovalSummon(c)
                    if (s == null || s.zone != z || !s.ally!!.matches(card)) continue
                    val r = s.replacement!!
                    // "cuando algún aliado fuera a ser destruido"
                    if (r.destroyOnly && !destroy) continue
                    if (Limit.spent(r.limit, room.usedThisTurn, replacementKey(owner, c, r))) continue
                    if (!SpecialSummons.canSummonInsteadOfRemoval(room, owner, c, card, s.reduce)) continue
                    options.add(ReplacementOption(r.id + "@" + c.instanceId, c, r, r.label,
                        r.text + " " + name(card) + " es su invocador."))
                }
            }
        }
        // "cuando uno o más aliados [filtro] fueran a ser removidos por efecto oponente: en su lugar, puedes …" (Albert Collier, itself included);
        // "cuando algún aliado [kuwal] fuera a ser destruido: en su lugar, puedes sacrificar otro aliado o una carta de tu mano" (itself included)
        for (guard in owner.field) {
            val g = CardEffects.allyGuard(guard)
            if (g == null || guard.faceDown || !g.allies!!.matches(card)) continue
            val r = g.replacement!!
            if (r.destroyOnly && !destroy) continue
            if (r.byOpponentOnly && !byOpponent) continue
            if (Limit.spent(r.limit, room.usedThisTurn, replacementKey(owner, guard, r))) continue
            val payable = when (r.cost) {
                CardEffects.ReplacementCost.GUARD_MATERIAL_AND_SACRIFICE -> byOpponent && byEffect && guard.materials!!.isNotEmpty()
                        && owner.field.any { c -> c !== card && r.resourceFilter!!.matches(c) }
                CardEffects.ReplacementCost.SACRIFICE_OTHER_ALLY_OR_HAND -> owner.hand.isNotEmpty() || sacrificeOthers(owner, card).isNotEmpty()
                else -> false
            }
            if (payable) options.add(ReplacementOption(r.id + "@" + guard.instanceId, guard, r, r.label, r.text))
        }
        // Protección [tipo]: "cuando algún aliado [tipo] fuera a ser removido: en su lugar, puedes destruir esta carta"
        for (protector in owner.field) {
            if (protector === card) continue
            for (k in keywords(protector)) {
                if (!k.lowercase().startsWith("protección")) continue
                val kind = k.replace(PROTECCION, "").replace(BRACKETS, "").trim { it <= ' ' }
                val matches = kind.isEmpty() || (card.classes != null && card.classes!!.any { kind.equals(it, ignoreCase = true) })
                if (matches) options.add(ReplacementOption("proteccion@" + protector.instanceId, protector, null, k,
                    k + ": en su lugar, puedes destruir " + name(protector) + "."))
            }
        }
        return options
    }

    // Java's "(?i)protección", which JavaScript's regular expressions do not take (no inline flags): case-insensitive for
    // the ASCII letters only, as Java's (?i) without UNICODE_CASE is ("ó" matches "ó" alone). Kotlin's IGNORE_CASE would
    // add UNICODE_CASE on the JVM.
    private val PROTECCION = Platform.regex("[pP][rR][oO][tT][eE][cC][cC][iI]ó[nN]")
    private val BRACKETS = Platform.regex("[\\[\\]]")

    @JvmStatic
    fun replacementKey(owner: PlayerState, card: CardSlot, r: CardEffects.Replacement): String {
        // A Definitive one counts as the player's Definitive effect of the turn (only 1 per turn, whichever card)
        if (r.limit == Limit.DEFINITIVE) return owner.userId + "|" + EffectEngine.DEFINITIVE_USED
        return if (r.limit?.betweenCopies == true) owner.userId + "|" + r.id
        else owner.userId + "|" + card.instanceId + "@" + card.fieldSeq + "|" + r.id
    }

    /** Asks the owner of the first pending removal whether to replace it; a removal with nothing left to replace it happens now. */
    @JvmStatic
    fun promptRemoval(room: GameRoom) {
        val pending = room.pendingRemovals[0]
        val owner = room.getPlayer(pending.ownerId)
        val card = if (owner == null) null else find(owner.field, pending.cardId)
        val options: List<ReplacementOption> = if (card == null) frozenListOf() else replacementOptions(room, card, owner!!, pending.destroy, pending.byId,
            pending.effectId != null)
        if (options.isEmpty()) {
            room.pendingRemovals.removeAt(0)
            if (card != null) executeRemoval(room, pending, owner!!, card)
            return
        }
        val sources = options.map { it.source }.distinct()
        rulesPrompt(room, owner!!.userId, PromptPurpose.REPLACE.id(), name(card) + (if (pending.destroy) " va a ser destruida" else " va a ser removida"),
            "Puedes usar 1 de estos efectos, o dejar que " + (if (pending.destroy) "sea destruida." else "sea removida."),
            "Usar", sources, sources)
        room.prompt!!.subjectId = card!!.instanceId
        room.prompt!!.effects = options
            .map { o ->
                // An inherited replacement (Deriduragon): the Recurso that gives it, small on the card
                val giver = if (o.replacement == null) null else CardEffects.replacementGiver(o.source, o.replacement)
                Prompt.OfferedEffect(o.key, o.source.instanceId, name(o.source), o.label, o.text,
                    giver?.cardName, giver?.imageUrl)
            }
    }

    @JvmStatic
    fun answerRemoval(room: GameRoom, prompt: Prompt, player: PlayerState, picks: List<String>): String? {
        if (room.pendingRemovals.isEmpty()) {
            room.prompt = null
            return null
        }
        val pending = room.pendingRemovals[0]
        val card = find(player.field, pending.cardId)
        if (PromptPurpose.REPLACE_MATERIALS.`is`(prompt.purpose)) {
            // Gémini: the chosen materials go to the Retaguardia, then the destruction goes on (a reaction, not "en su lugar")
            room.prompt = null
            room.pendingRemovals.removeAt(0)
            if (card == null) return null
            for (id in picks) {
                val m = find(card.materials, id) ?: continue
                card.materials!!.remove(m)
                m.faceDown = false
                m.tapped = false
                m.x = 50.0
                m.y = 75.0
                player.field.add(m)
                record(room, GameEvent.SEND, player.userId, m, "materials", "field", null, frozenMapOf<String, Any?>("from", card.instanceId),
                    player.username + " coloca " + name(m) + " (material de " + name(card) + ") en su retaguardia")
            }
            executeRemoval(room, pending, player, card)
            return null
        }
        if (PromptPurpose.REPLACE_RESOURCES.`is`(prompt.purpose)) {
            // Sarkhydrax: the first one picked is tributed, the second goes to the hand; then the removal goes on
            if (card == null || picks.size != 2) return "Elige el recurso que tributas y el que añades a tu mano"
            val tributed = find(card.resources, picks[0])
            val taken = find(card.resources, picks[1])
            if (tributed == null || taken == null || tributed === taken) return "Elige 2 recursos distintos de " + name(card)
            room.prompt = null
            room.pendingRemovals.removeAt(0)
            card.resources!!.removeAll(frozenListOf(tributed, taken))
            tributed.faceDown = false
            player.tributeZone.add(tributed)
            record(room, GameEvent.TRIBUTE, player.userId, tributed, "resources", "tributeZone", null, frozenMapOf<String, Any?>("byCard", card.instanceId), null)
            taken.faceDown = false
            player.hand.add(taken)
            record(room, GameEvent.ADD_TO_HAND, player.userId, taken, "resources", "hand", null, null,
                player.username + " tributa " + name(tributed) + " y añade " + name(taken) + " a su mano antes de que " + name(card) + " sea removida")
            executeRemoval(room, pending, player, card)
            return null
        }
        if (PromptPurpose.REPLACE_GUARD.`is`(prompt.purpose)) {
            // Albert Collier: 1 material of it and 1 [artefacto]; paid once, every ally it guards that this effect was removing stays
            val guard = find(player.field, prompt.subjectId)
            val g = if (guard == null) null else CardEffects.allyGuard(guard)
            // Its own replacement (Novikhé): 1 of its materials and 1 ally that matches; only this card stays
            val own = if (g != null || guard == null || guard !== card) null
                else CardEffects.replacementsOf(guard).firstOrNull { it.cost == CardEffects.ReplacementCost.GUARD_MATERIAL_AND_SACRIFICE }
            if (own != null) {
                val material = picks.map { id -> find(guard!!.materials, id) }.firstOrNull { it != null }
                val sacrificed = picks.map { id -> find(player.field, id) }.firstOrNull { c -> c != null && c !== guard && own.resourceFilter!!.matches(c) }
                if (picks.size != 2 || material == null || sacrificed == null)
                    return "Elige 1 material de " + name(guard) + " y 1 " + own.resourceFilter!!.describe() + " bajo tu control"
                room.prompt = null
                room.pendingRemovals.removeAt(0)
                guard!!.materials!!.remove(material)
                material.faceDown = false
                player.tributeZone.add(material)
                record(room, GameEvent.TRIBUTE, player.userId, material, "materials", "tributeZone", null, frozenMapOf<String, Any?>("byCard", guard.instanceId),
                    player.username + " tributa " + name(material) + " de los materiales de " + name(guard) + ": no es " + (if (pending.destroy) "destruida" else "removida"))
                destroy(room, sacrificed, player, true, CAUSE_EFFECT, player.userId, guard.instanceId, null)
                destroyIfStillNoStrength(room, pending, player, guard)
                return null
            }
            if (g == null) {
                room.prompt = null
                room.pendingRemovals.removeAt(0)
                if (card != null) executeRemoval(room, pending, player, card)
                return null
            }
            val material = picks.map { id -> find(guard!!.materials, id) }.firstOrNull { it != null }
            val sacrificed = picks.map { id -> find(player.field, id) }.firstOrNull { c -> c != null && g.replacement!!.resourceFilter!!.matches(c) }
            if (picks.size != 2 || material == null || sacrificed == null)
                return "Elige 1 material de " + name(guard) + " y 1 " + g.replacement!!.resourceFilter!!.describe() + " bajo tu control"
            room.prompt = null
            guard!!.materials!!.remove(material)
            material.faceDown = false
            player.tributeZone.add(material)
            record(room, GameEvent.TRIBUTE, player.userId, material, "materials", "tributeZone", null, frozenMapOf<String, Any?>("byCard", guard.instanceId),
                player.username + " tributa " + name(material) + " de los materiales de " + name(guard))
            val saved = room.pendingRemovals.filter { p -> p.ownerId == player.userId
                    && p.effectId == pending.effectId && find(player.field, p.cardId) != null
                    && g.allies!!.matches(find(player.field, p.cardId)!!) }
            room.pendingRemovals.removeAll(saved)
            log(room, name(guard) + ": " + saved.joinToString(", ") { p -> name(find(player.field, p.cardId)) }
                    + " no " + (if (saved.size == 1) "es removida" else "son removidas"), null)
            destroy(room, sacrificed, player, true, CAUSE_EFFECT, player.userId, guard.instanceId, null)
            return null
        }
        if (PromptPurpose.REPLACE_LIFE.`is`(prompt.purpose)) {
            // "en su lugar, puedes colocar 1 aliado al tope de tus vidas bocabajo": that ally goes there, the card stays
            val ally = (if (picks.isEmpty()) null else find(player.field, picks[0]))
            if (ally == null || ally === card || "creature" != ally.cardType) return "Elige 1 aliado"
            room.prompt = null
            room.pendingRemovals.removeAt(0)
            removeFromField(room, player, ally)
            ally.faceDown = true
            player.lifeStack.add(0, ally)
            record(room, GameEvent.SEND, player.userId, ally, "field", "lifeStack", null, null,
                player.username + " coloca " + name(ally) + " al tope de sus vidas bocabajo: " + (if (card == null) "la carta" else name(card))
                        + " no es " + (if (pending.destroy) "destruida" else "removida"))
            if (card != null) destroyIfStillNoStrength(room, pending, player, card)
            return null
        }
        if (PromptPurpose.REPLACE_TAP.`is`(prompt.purpose)) {
            // "en su lugar, puedes agotar 1 de tus estructuras": that structure is exhausted, the card stays
            val structure = (if (picks.isEmpty()) null else find(tappableStructures(player), picks[0])) ?: return "Elige 1 de tus estructuras activas"
            room.prompt = null
            room.pendingRemovals.removeAt(0)
            structure.tapped = true
            record(room, GameEvent.TAP, player.userId, structure, null, null, null, frozenMapOf<String, Any?>("ownerId", player.userId),
                player.username + " agota " + name(structure) + ": " + (if (card == null) "la carta" else name(card))
                        + " no es " + (if (pending.destroy) "destruida" else "removida"))
            if (card != null) destroyIfStillNoStrength(room, pending, player, card)
            return null
        }
        if (PromptPurpose.REPLACE_SACRIFICE.`is`(prompt.purpose)) {
            // "en su lugar, puedes sacrificar otro aliado o una carta de tu mano": that card is sacrificed, the ally stays
            val pick = if (picks.isEmpty()) null else picks[0]
            val ally = if (card == null) null else sacrificeOthers(player, card).firstOrNull { it.instanceId == pick }
            val inHand = if (pick == null) null else find(player.hand, pick)
            if (ally == null && inHand == null) return "Elige otro aliado o 1 carta de tu mano"
            room.prompt = null
            room.pendingRemovals.removeAt(0)
            val guard = find(player.field, prompt.summonerId)
            log(room, player.username + " sacrifica " + name(ally ?: inHand) + ": " + (if (card == null) "la carta" else name(card)) + " no es destruida"
                    + (if (guard == null) "" else " (" + name(guard) + ")"), null)
            if (ally != null) destroy(room, ally, player, true, CAUSE_EFFECT, player.userId, guard?.instanceId, null)
            else sacrificeFromHand(room, player, inHand!!, null)
            if (card != null) destroyIfStillNoStrength(room, pending, player, card)
            return null
        }
        if (PromptPurpose.REPLACE_DESTROY.`is`(prompt.purpose)) {
            // "en su lugar, puedes destruir un aliado enlazado a esta carta": that ally is destroyed, the card stays
            val pick = if (picks.isEmpty()) null else picks[0]
            val ally = if (card == null) null else linkedAllies(player, card).firstOrNull { it.instanceId == pick }
                ?: return "Elige 1 aliado enlazado"
            room.prompt = null
            room.pendingRemovals.removeAt(0)
            log(room, player.username + " destruye " + name(ally) + ": " + name(card) + " no es destruida", null)
            destroy(room, ally, player, false, CAUSE_EFFECT, player.userId, card!!.instanceId, null)
            destroyIfStillNoStrength(room, pending, player, card)
            return null
        }
        if (PromptPurpose.REPLACE_HAND.`is`(prompt.purpose)) {
            // Caballero Leal: the hand card tributed instead
            val paid = (if (picks.isEmpty()) null else find(player.hand, picks[0])) ?: return "Elige 1 carta de tu mano"
            room.prompt = null
            room.pendingRemovals.removeAt(0)
            player.hand.remove(paid)
            player.tributeZone.add(paid)
            record(room, GameEvent.TRIBUTE, player.userId, paid, "hand", "tributeZone", null, null,
                player.username + " tributa " + name(paid) + " de su mano: " + (if (card == null) "la carta" else name(card)) + " no es destruida")
            if (card != null) destroyIfStillNoStrength(room, pending, player, card)
            return null
        }
        room.prompt = null
        if (card == null) {
            room.pendingRemovals.removeAt(0)
            return null
        }
        val options = replacementOptions(room, card, player, pending.destroy, pending.byId, pending.effectId != null)
        val chosen = if (picks.isEmpty()) null else options
            .firstOrNull { o -> o.key == picks[0] || o.source.instanceId == picks[0] }
        if (chosen == null) {
            // Not replaced: the removal happens now
            room.pendingRemovals.removeAt(0)
            executeRemoval(room, pending, player, card)
            return null
        }
        if (chosen.replacement == null) {
            // Protección: the protector is destroyed instead (it may have a replacement of its own)
            room.pendingRemovals.removeAt(0)
            log(room, name(chosen.source) + " protege a " + name(card) + ": es destruida en su lugar", null)
            destroy(room, chosen.source, player, false, CAUSE_EFFECT, player.userId, card.instanceId, null)
            destroyIfStillNoStrength(room, pending, player, card)
            return null
        }
        val r = chosen.replacement
        // A guard's limit is that of the card that guards ("una vez por turno" of it, or between its copies)
        val guarding = CardEffects.allyGuard(chosen.source)?.replacement == r
        Limit.take(r.limit, room.usedThisTurn, replacementKey(player, if (guarding) chosen.source else card, r))
        when (r.cost!!) {
            CardEffects.ReplacementCost.RESOURCE_TRIBUTED_ANOTHER_TO_HAND -> {
                rulesPrompt(room, player.userId, PromptPurpose.REPLACE_RESOURCES.id(), name(card) + " · " + r.label,
                    "Elige primero el recurso que tributas y después el que añades a tu mano; luego " + name(card) + " es removida.",
                    "Elegir", card.resources, card.resources)
                room.prompt!!.min = 2
                room.prompt!!.max = 2
                return null
            }
            CardEffects.ReplacementCost.GUARD_MATERIAL_AND_SACRIFICE -> {
                val guard = chosen.source
                val valid: MutableList<CardSlot> = ArrayList(guard.materials!!)
                valid.addAll(player.field.filter { c -> c !== card && r.resourceFilter!!.matches(c) })
                rulesPrompt(room, player.userId, PromptPurpose.REPLACE_GUARD.id(), name(guard) + " · " + r.label,
                    "Tributa 1 material de " + name(guard) + " y sacrifica 1 " + r.resourceFilter!!.describe() + " bajo tu control: "
                            + (CardEffects.allyGuard(guard)?.let { g -> "no se remueve ningún aliado [" + g.allies!!.classes().joinToString(", ") + "] de este efecto." }
                                ?: (name(guard) + " no es " + (if (pending.destroy) "destruida." else "removida."))),
                    "Pagar", valid, valid)
                room.prompt!!.min = 2
                room.prompt!!.max = 2
                room.prompt!!.subjectId = guard.instanceId
                return null
            }
            CardEffects.ReplacementCost.ALLY_SPECIAL_SUMMON -> {
                // A regular special summon of that card, the ally as its summoner: the ally is not removed
                room.pendingRemovals.removeAt(0)
                log(room, name(card) + " no es removida: en su lugar, invoca especialmente a " + name(chosen.source), null)
                SpecialSummons.summonInsteadOfRemoval(room, player, chosen.source, card)
                return null
            }
            CardEffects.ReplacementCost.TRIBUTE_TWO_MATERIALS -> {
                var i = 0
                while (i < 2 && !card.materials!!.isEmpty()) {
                    val m = card.materials!!.removeAt(0)
                    m.faceDown = false
                    player.tributeZone.add(m)
                    record(room, GameEvent.TRIBUTE, player.userId, m, "materials", "tributeZone", null, frozenMapOf<String, Any?>("byCard", card.instanceId), null)
                    i++
                }
                log(room, player.username + " tributa 2 materiales de " + name(card) + ": no es removida", null)
            }
            CardEffects.ReplacementCost.TRIBUTE_RESOURCES -> {
                val filter = r.resourceFilter!!
                val paid = card.resources!!.filter { filter.matches(it) }
                card.resources!!.removeAll(paid)
                for (c in paid) {
                    c.faceDown = false
                    player.tributeZone.add(c)
                }
                log(room, player.username + " tributa " + paid.size + " " + plural(paid.size, "recurso", "recursos") + " de " + name(card)
                        + ": no es " + (if (pending.destroy) "destruida" else "removida") + names(paid), null)
            }
            CardEffects.ReplacementCost.TRIBUTE_RESOURCE_RETURN_TO_HAND -> {
                // "en su lugar, tributa "X" de sus recursos y súbela a tu mano": that Recurso to the Tributos, the card to its owner's hand
                val paid = card.resources!!.firstOrNull { r.resourceFilter!!.matches(it) }
                if (paid != null) {
                    card.resources!!.remove(paid)
                    paid.faceDown = false
                    player.tributeZone.add(paid)
                    record(room, GameEvent.TRIBUTE, player.userId, paid, "resources", "tributeZone", null, frozenMapOf<String, Any?>("byCard", card.instanceId), null)
                }
                room.pendingRemovals.removeAt(0)
                removeFromField(room, player, card)
                card.faceDown = false
                player.hand.add(card)
                record(room, GameEvent.SEND, player.userId, card, "field", "hand", null, frozenMapOf<String, Any?>("ownerId", player.userId),
                    player.username + " tributa " + name(paid) + " y sube a " + name(card) + " a su mano: no es " + (if (pending.destroy) "destruida" else "removida"))
                return null
            }
            CardEffects.ReplacementCost.RESOURCES_TO_TRIBUTES_BEFORE -> {
                // A reaction: the Recursos go to the Tributos first, then the removal goes on
                val filter = r.resourceFilter!!
                val paid = card.resources!!.filter { filter.matches(it) }
                card.resources!!.removeAll(paid)
                for (c in paid) {
                    c.faceDown = false
                    player.tributeZone.add(c)
                    record(room, GameEvent.TRIBUTE, player.userId, c, "resources", "tributeZone", null, frozenMapOf<String, Any?>("byCard", card.instanceId), null)
                }
                log(room, player.username + " tributa " + paid.size + " " + plural(paid.size, "recurso", "recursos") + " de " + name(card)
                        + " antes de que sea " + (if (pending.destroy) "destruida" else "removida") + names(paid), null)
                room.pendingRemovals.removeAt(0)
                executeRemoval(room, pending, player, card)
                return null
            }
            CardEffects.ReplacementCost.MATERIALS_TO_BACK -> {
                val valid = card.materials!!.filter { m -> "creature" == m.cardType && r.resourceFilter!!.matches(m) }
                rulesPrompt(room, player.userId, PromptPurpose.REPLACE_MATERIALS.id(), name(card) + " · " + r.label,
                    "Coloca hasta 2 de estas criaturas en tu retaguardia; luego " + name(card) + " es destruida.", "A la retaguardia", valid, card.materials)
                room.prompt!!.min = 1
                room.prompt!!.max = min(2, valid.size)
                return null
            }
            CardEffects.ReplacementCost.ALLY_TO_LIFE_FACE_DOWN -> {
                // The ally is chosen next; the removal stays pending until then
                val allies = player.field.filter { c -> c !== card && "creature" == c.cardType }
                rulesPrompt(room, player.userId, PromptPurpose.REPLACE_LIFE.id(), name(card) + " · " + r.label,
                    "Coloca 1 aliado al tope de tus vidas bocabajo: " + name(card) + " no es " + (if (pending.destroy) "destruida." else "removida."),
                    "A tus vidas", allies, allies)
                room.prompt!!.min = 1
                room.prompt!!.subjectId = card.instanceId
                return null
            }
            CardEffects.ReplacementCost.TAP_STRUCTURE -> {
                // The structure is chosen next; the removal stays pending until then
                val structures = tappableStructures(player)
                rulesPrompt(room, player.userId, PromptPurpose.REPLACE_TAP.id(), name(card) + " · " + r.label,
                    "Agota 1 de tus estructuras: " + name(card) + " no es " + (if (pending.destroy) "destruida." else "removida."),
                    "Agotar", structures, structures)
                room.prompt!!.min = 1
                return null
            }
            CardEffects.ReplacementCost.SACRIFICE_OTHER_ALLY_OR_HAND -> {
                // The ally or the hand card is chosen next; the removal stays pending until then
                val valid = sacrificeOthers(player, card) + player.hand
                rulesPrompt(room, player.userId, PromptPurpose.REPLACE_SACRIFICE.id(), name(card) + " · " + r.label,
                    "Sacrifica otro aliado o 1 carta de tu mano: " + name(card) + " no es destruida.", "Sacrificar", valid, valid)
                room.prompt!!.min = 1
                room.prompt!!.subjectId = card.instanceId
                room.prompt!!.summonerId = chosen.source.instanceId
                return null
            }
            CardEffects.ReplacementCost.DESTROY_LINKED_ALLY -> {
                // The linked ally is chosen next; the removal stays pending until then
                val linked = linkedAllies(player, card)
                rulesPrompt(room, player.userId, PromptPurpose.REPLACE_DESTROY.id(), name(card) + " · " + r.label,
                    "Destruye 1 aliado enlazado a " + name(card) + ": " + name(card) + " no es destruida.", "Destruir", linked, linked)
                room.prompt!!.min = 1
                room.prompt!!.subjectId = card.instanceId
                return null
            }
            CardEffects.ReplacementCost.TRIBUTE_ONE_FROM_HAND -> {
                // The hand card is chosen next; the removal stays pending until then
                rulesPrompt(room, player.userId, PromptPurpose.REPLACE_HAND.id(), name(card) + " · " + r.label,
                    "Tributa 1 carta de tu mano: " + name(card) + " no es destruida.", "Tributar", player.hand, player.hand)
                room.prompt!!.min = 1
                return null
            }
            else -> {}
        }
        room.pendingRemovals.removeAt(0)
        destroyIfStillNoStrength(room, pending, player, card)
        return null
    }

    /**
     * Rule 12.0.0.2 is checked again after a destruction is replaced: a creature that is still at strength 0 is destroyed anyway
     * (designer ruling 2026-10-30), by the rules and not by the effect (no "destruida por efecto" triggers). It may be replaced
     * again if its owner can pay once more.
     */
    @JvmStatic
    fun destroyIfStillNoStrength(room: GameRoom, pending: PendingRemoval?, owner: PlayerState, card: CardSlot) {
        if ("creature" != card.cardType || card.strength == null || strength(card) > 0) return
        if (find(owner.field, card.instanceId) == null) return
        log(room, name(card) + " sigue con fuerza 0: es destruida", null)
        destroy(room, card, owner, false, CAUSE_RULES, null, null, null)
    }

    /** The removal that was waiting, as it was going to happen. */
    @JvmStatic
    fun executeRemoval(room: GameRoom, pending: PendingRemoval, owner: PlayerState, card: CardSlot) {
        val effect = if (pending.effectId == null) null
        else room.chain.firstOrNull { e -> pending.effectId == e.id }
        decided(room).add(card.instanceId)
        if (pending.destroy) {
            destroy(room, card, owner, false, pending.cause, pending.byId, pending.byCard, effect)
            return
        }
        decided(room).remove(card.instanceId)
        removeFromField(room, owner, card)
        owner.hand.add(card)
        val by = room.getPlayer(pending.byId)
        record(room, GameEvent.SEND, pending.byId, card, "field", "hand", effect, frozenMapOf<String, Any?>("ownerId", owner.userId),
            (if (by == null) "" else by.username + " ") + "sube a " + name(card) + " a la mano de " + owner.username)
    }

    /**
     * A card leaves the field (destroyed, tributed, bounced, used as a material…): it loses its state and its Recursos
     * and Materiales go to the Descarte (rules 6.3.0.5, 6.4.0.5). Only a special summon's summoner passes them on.
     */
    @JvmStatic
    fun removeFromField(room: GameRoom, owner: PlayerState?, card: CardSlot) {
        rememberStrength(room, card)
        removeFromField(owner, card)
    }

    /** As [removeFromField] with the room, without remembering its strength. */
    @JvmStatic
    fun removeFromField(owner: PlayerState?, card: CardSlot) {
        owner!!.field.remove(card)
        for (attached in concat(card.materials, card.resources)) {
            attached.faceDown = false
            owner.discardPile.add(attached)
        }
        card.materials = ArrayList()
        card.resources = ArrayList()
        leaveField(card)
    }
}
