package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.engine.Chain.advance
import com.drive.drive_manager.simulator.engine.EffectEngine.canPay
import com.drive.drive_manager.simulator.engine.EffectEngine.find
import com.drive.drive_manager.simulator.engine.EffectEngine.floating
import com.drive.drive_manager.simulator.engine.EffectEngine.gemsOf
import com.drive.drive_manager.simulator.engine.EffectEngine.gemsText
import com.drive.drive_manager.simulator.engine.EffectEngine.ids
import com.drive.drive_manager.simulator.engine.EffectEngine.inFrontLine
import com.drive.drive_manager.simulator.engine.EffectEngine.name
import com.drive.drive_manager.simulator.engine.EffectEngine.pay
import com.drive.drive_manager.simulator.engine.EffectEngine.plural
import com.drive.drive_manager.simulator.engine.EffectEngine.record
import com.drive.drive_manager.simulator.engine.EffectEngine.rulesPrompt
import com.drive.drive_manager.simulator.engine.EffectEngine.summoningAs
import com.drive.drive_manager.simulator.engine.EffectEngine.zone
import com.drive.drive_manager.simulator.engine.EffectStep.*
import com.drive.drive_manager.simulator.engine.Removals.leaveField
import com.drive.drive_manager.simulator.engine.Removals.removeFromField
import com.drive.drive_manager.simulator.engine.Rituals.ritualByPlayer
import com.drive.drive_manager.simulator.engine.Rituals.shares
import com.drive.drive_manager.simulator.engine.SpecialRequirements.NO_SUMMONER
import com.drive.drive_manager.simulator.engine.SpecialRequirements.SpecialRequirement
import com.drive.drive_manager.simulator.engine.SpecialRequirements.affordableWith
import com.drive.drive_manager.simulator.engine.SpecialRequirements.alliesAddUp
import com.drive.drive_manager.simulator.engine.SpecialRequirements.checkMaterials
import com.drive.drive_manager.simulator.engine.SpecialRequirements.creaturesNeeded
import com.drive.drive_manager.simulator.engine.SpecialRequirements.declaredSummoners
import com.drive.drive_manager.simulator.engine.SpecialRequirements.enoughCreatures
import com.drive.drive_manager.simulator.engine.SpecialRequirements.fieldPiecesDiscount
import com.drive.drive_manager.simulator.engine.SpecialRequirements.findDeclared
import com.drive.drive_manager.simulator.engine.SpecialRequirements.kindName
import com.drive.drive_manager.simulator.engine.SpecialRequirements.materialInstruction
import com.drive.drive_manager.simulator.engine.SpecialRequirements.materialsPossible
import com.drive.drive_manager.simulator.engine.SpecialRequirements.optionFor
import com.drive.drive_manager.simulator.engine.SpecialRequirements.possibleFieldPiecesDiscount
import com.drive.drive_manager.simulator.engine.SpecialRequirements.specialMaterials
import com.drive.drive_manager.simulator.engine.SpecialRequirements.specialRequirement
import com.drive.drive_manager.simulator.engine.SpecialRequirements.specialSource
import com.drive.drive_manager.simulator.engine.SpecialRequirements.specialSummoners
import com.drive.drive_manager.simulator.engine.SpecialRequirements.zonesText
import com.drive.drive_manager.simulator.engine.Summons.afterSpecialSummon
import com.drive.drive_manager.simulator.engine.Summons.askTap
import com.drive.drive_manager.simulator.engine.Summons.checkMainPhase
import com.drive.drive_manager.simulator.engine.Summons.inheritFromSummoner
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState
import com.drive.drive_manager.simulator.room.Zone

/** Evolución, Ascenso and Materialización carried out, by the player or by an effect. */
object SpecialSummons {

    /**
     * Special summon from the hand by the rules (rule 9): Ritual, Evolución, Ascenso or Materialización, as the
     * card says. `summonerId` is the creature it was dropped onto; whatever is missing is asked for.
     */
    @JvmStatic
    fun specialByPlayer(room: GameRoom, userId: String?, instanceId: String?, summonerId: String?): String? {
        val who = room.getPlayer(userId)
        return summoningAs(if (who == null) null else specialSource(who, instanceId)) { specialByPlayerAs(room, userId, instanceId, summonerId) }
    }

    @JvmStatic
    fun specialByPlayerAs(room: GameRoom, userId: String?, instanceId: String?, summonerId: String?): String? {
        val player = room.getPlayer(userId)
        val card = if (player == null) null else specialSource(player, instanceId)
        if (card != null && "ritual".equals(card.specialSummonKind, ignoreCase = true)) return ritualByPlayer(room, userId, instanceId, summonerId)
        val error = checkMainPhase(room, userId, "invocar")
        if (error != null) return error
        if (card == null) return "La carta no está en tu mano"
        player!!
        val fromZone = if (find(player.hand, card.instanceId) != null) "hand" else "tributeZone"
        val req = specialRequirement(card)
        if (req == null || card.specialCost == null) return name(card) + " no tiene una invocación especial que el motor conozca"
        val kind = kindName(req.kind)
        if (!canPay(room, userId, card.specialCost!! - possibleFieldPiecesDiscount(player, card)
                        - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind, card, null)))
            return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y el coste especial es " + card.specialCost
        if (askTap(room, player, card, req.kind, summonerId)) return null
        if ("promotion" == req.kind && !alliesAddUp(player, req)) return "Para el Ascenso tus aliados deben sumar " + req.alliesSum + " o más en nivel"
        if (!enoughCreatures(player, card)) return "Para invocar a " + name(card) + " debes controlar al menos " + creaturesNeeded(card) + " criaturas"
        val missing = "Tus criaturas no cumplen el Requerimiento de " + name(card) + ": " + card.requirement!!.trim { it <= ' ' }
        if (req.declaredSummoner()) {
            if (summonerId == null) {
                // Rule 9.2.0.13: the summoner is declared first
                val summoners = declaredSummoners(player, card, req)
                if (summoners.isEmpty()) return missing
                val shown: MutableList<CardSlot> = ArrayList()
                for (z in req.summonerZones!!) shown.addAll(zone(player, z))
                shown.remove(card)
                rulesPrompt(room, userId, PromptPurpose.SPECIAL.id(), name(card) + " · " + kind,
                        "Declara el invocador: 1 criatura nivel " + req.level + " de " + zonesText(req.summonerZones) + ". Luego eliges "
                                + req.materialCount + (if (req.moreMaterials) " o más " else " ") + plural(req.materialCount, "material", "materiales")
                                + ". Si no eliges ninguna, no se invoca.",
                        "Invocador", summoners, shown)
                room.prompt!!.subjectId = card.instanceId
                return null
            }
            val summoner = findDeclared(player, req, summonerId)
            if (summoner == null || !declaredSummoners(player, card, req).contains(summoner)) return missing
            val shown: MutableList<CardSlot> = ArrayList()
            for (z in req.materialZones!!) shown.addAll(zone(player, z))
            shown.remove(card)
            shown.remove(summoner)
            val materialOptions = specialMaterials(player, card, req, summoner)
            rulesPrompt(room, userId, PromptPurpose.SPECIAL.id(), name(card) + " · " + kind, materialInstruction(req, card, summoner), "Material",
                    materialOptions, shown)
            room.prompt!!.min = req.materialCount
            room.prompt!!.max = if (req.moreMaterials) materialOptions.size else req.materialCount
            room.prompt!!.subjectId = card.instanceId
            room.prompt!!.summonerId = summoner.instanceId
            return null
        }
        if (req.noSummoner) {
            val materials = specialMaterials(player, card, req, null)
            if (materials.size < req.materialCount) return missing
            val shown: MutableList<CardSlot> = ArrayList()
            for (z in req.materialZones!!) shown.addAll(zone(player, z))
            rulesPrompt(room, userId, PromptPurpose.SPECIAL.id(), name(card) + " · " + kind, materialInstruction(req, card, null), "Material", materials, shown)
            room.prompt!!.min = req.materialCount
            room.prompt!!.max = req.materialCount
            room.prompt!!.subjectId = card.instanceId
            room.prompt!!.summonerId = NO_SUMMONER
            return null
        }
        val possible = specialSummoners(player, card, req).filter { s -> materialsPossible(player, card, req, s) }
        // Only those it can be paid with: one that could not would be refused once chosen
        val summoners = possible.filter { s -> affordableWith(room, player, card, req, s) }
        if (summonerId == null) {
            if (summoners.isEmpty()) return if (possible.isEmpty()) missing else "No tienes gemas suficientes para invocar a " + name(card) + " con ninguna de tus criaturas"
            rulesPrompt(room, userId, PromptPurpose.SPECIAL.id(), name(card) + " · " + kind,
                    "Elige el invocador: 1 criatura nivel " + req.level + (if (req.atLeast) " o mayor" else if (req.maxLevel > req.level) " o " + req.maxLevel else "")
                            + (if (req.alternative != null) " (o nivel " + req.alternative.level + ")" else "") + ". " + name(card)
                            + " ocupa su lugar y hereda su estado. Si no eliges ninguna, no se invoca.",
                    "Invocador", summoners, player.field)
            room.prompt!!.subjectId = card.instanceId
            return null
        }
        val summoner = find(player.field, summonerId) ?: return "El invocador debe ser una criatura de tu campo"
        if (!possible.contains(summoner)) return if (shares(summoner, card)) missing else name(summoner) + " no comparte tipo ni color con " + name(card)
        val option = optionFor(req, summoner)
        if ("materialization" == req.kind && (option!!.materialCount > 0 || option.levelSum > 0)) {
            val materials = specialMaterials(player, card, req, summoner)
            val shown: MutableList<CardSlot> = ArrayList()
            for (z in option.materialZones!!) shown.addAll(zone(player, z))
            rulesPrompt(room, userId, PromptPurpose.SPECIAL.id(), name(card) + " · " + kind, materialInstruction(req, card, summoner), "Material", materials, shown)
            room.prompt!!.min = Math.max(1, option.materialCount)
            room.prompt!!.max = if (option.levelSum > 0) materials.size else option.materialCount
            room.prompt!!.subjectId = card.instanceId
            room.prompt!!.summonerId = summoner.instanceId
            return null
        }
        if (!canPay(room, userId, card.specialCost!! - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind, card, summoner))) {
            return "No tienes gemas suficientes para invocarla con " + name(summoner)
        }
        handSpecial(room, player, card, req, summoner, java.util.List.of(), fromZone)
        return null
    }

    /**
     * Carries out the special summon (rules 9.4–9.6): pays the special cost, the creature takes the summoner's
     * place and state; the summoner is tributed (Ascenso), becomes a Recurso (Evolución) or, with the other
     * material, Materiales (Materialización). Then the move up and the regulatory draw.
     */
    @JvmStatic
    fun handSpecial(
        room: GameRoom, player: PlayerState, card: CardSlot, req: SpecialRequirement, summoner: CardSlot?,
        materials: List<CardSlot>, fromZone: String?,
    ) {
        // "cuando esta carta fuera a ser ascendida: puede declarar ataque" (Príncipe Charles): asked before the Ascenso
        if ("promotion" == req.kind && summoner != null && CardEffects.attacksBeforeAscended(summoner) && room.combat == null
                && room.pendingAscension == null && inFrontLine(summoner)) {
            val options: MutableList<CardSlot> = ArrayList(java.util.List.of(summoner))
            options.addAll(CombatRules.attackTargets(room, player.userId, summoner))
            val opponent = room.getPlayer(room.getOpponentId(player.userId))
            val shown: MutableList<CardSlot> = ArrayList(java.util.List.of(summoner))
            if (opponent != null) shown.addAll(opponent.field)
            rulesPrompt(room, player.userId, PromptPurpose.ASCEND_ATTACK.id(), name(summoner) + " va a ser ascendida",
                    "Puede declarar ataque antes: elige " + name(summoner) + " para atacar al oponente, o una criatura enemiga agotada. "
                            + "O ninguna para ascenderla ya.", "Atacar", options, shown)
            room.prompt!!.min = 0
            room.prompt!!.subjectId = card.instanceId
            room.prompt!!.summonerId = summoner.instanceId
            return
        }
        // A cost modifier that asks for its payment (Flambreik's tributes) is asked before the summon (a Reacción applies
        // before its event); only when no materials are being chosen
        var use = CostModifiers.choice(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind, card, summoner)
        if (use != null && !materials.isEmpty() && use.modifier.payment!!.kind != CostModifiers.PaymentKind.SELF_DAMAGE) use = null
        if (use != null) {
            room.usedThisTurn.removeIf { k -> k.startsWith(DISCOUNT_MATERIALS + card.instanceId + "|") }
            if (!materials.isEmpty()) room.usedThisTurn.add(DISCOUNT_MATERIALS + card.instanceId + "|" + ids(materials).joinToString(","))
            rulesPrompt(room, player.userId, PromptPurpose.SUMMONER_DISCOUNT.id(), name(use.holder) + " · " + use.modifier.label,
                    CostModifiers.describe(use), "Pagar", CostModifiers.paymentOptions(player, use, card), player.tributeZone)
            room.prompt!!.max = use.modifier.payment!!.count
            if (use.modifier.payment!!.kind == CostModifiers.PaymentKind.SELF_DAMAGE) room.prompt!!.min = 0
            // "puedes enviar 5…": exactly that many, or none to pay it all
            else room.prompt!!.exactOrNone = true
            // Without this reduction the summon cannot be paid: the payment is not optional any more
            val others = CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind, card, summoner) - use.modifier.reduceNow(room, player.userId)
            if (use.modifier.payment!!.kind != CostModifiers.PaymentKind.SELF_DAMAGE && !canPay(room, player.userId, card.specialCost!! - others)) {
                room.prompt!!.min = use.modifier.payment!!.count
            }
            room.prompt!!.subjectId = card.instanceId
            room.prompt!!.summonerId = summoner!!.instanceId
            return
        }
        specialSummon(room, player, card, req, summoner, materials, fromZone, 0, null)
        advance(room)
    }

    /** The materials chosen before a payment the player is asked for (Biok): card id | material ids. */
    const val DISCOUNT_MATERIALS = "discount-materials|"

    @JvmStatic
    fun specialSummon(
        room: GameRoom, player: PlayerState, card: CardSlot, req: SpecialRequirement, summoner: CardSlot?,
        materials: List<CardSlot>, fromZone: String?, reduce: Int, effect: PendingEffect?,
    ) {
        val userId = player.userId
        var cost = Math.max(0, card.specialCost!! - reduce - fieldPiecesDiscount(player, card, summoner, materials))
        // Its cost modifiers that ask for no choice ("reduce en 1 el coste especial", "puedes agotar esta carta para reducir…")
        cost -= CostModifiers.applyAutomatic(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind, card, summoner, cost)
        pay(room, userId, cost)
        zone(player, fromZone).remove(card)
        var used: String
        if (summoner == null) {
            // No summoner ("2 criaturas nivel 1 en tu mano o tributos"): it enters the Retaguardia, active
            card.faceDown = false
            card.tapped = false
            card.x = 50.0
            card.y = 75.0
            used = ""
        } else {
            // "cuando esta carta fuera a ser ascendida: esta carta gana Furia 2 hasta el final del turno" (it passes to the new creature)
            val gain = if ("promotion" == req.kind) CardEffects.gainBeforeAscended(summoner) else null
            if (gain != null && userId!! == room.currentTurnUserId) {
                summoner.turnKeywords!!.add(gain)
                floating(summoner, null, "Gana $gain hasta el final del turno", "turn", 0, gain)
            }
            inheritFromSummoner(room, card, summoner)
            player.field.remove(summoner)
            leaveField(summoner)
            used = name(summoner)
        }
        when (req.kind!!) {
            "promotion" -> {
                // Java added the summoner as it was (a null one too)
                @Suppress("UNCHECKED_CAST")
                (player.tributeZone as MutableList<CardSlot?>).add(summoner)
                record(room, GameEvent.TRIBUTE, userId, summoner, "field", "tributeZone", null, java.util.Map.of<String, Any?>("cause", "promotion"), null)
            }
            "evolution" -> {
                @Suppress("UNCHECKED_CAST")
                (card.resources as MutableList<CardSlot?>).add(0, summoner)
            }
            else -> {
                if (summoner != null) card.materials!!.add(0, summoner)
                for (material in materials) {
                    val from = if (player.tributeZone.remove(material)) "tributeZone" else if (player.hand.remove(material)) "hand"
                    else if (player.discardPile.remove(material)) "discardPile" else "field"
                    // A creature of the field used as a material loses its state; its attachments go to the Descarte
                    if (Zone.FIELD.`is`(from)) removeFromField(player, material)
                    material.faceDown = false
                    card.materials!!.add(material)
                    used += (if (used.isEmpty()) "" else " y ") + name(material)
                }
            }
        }
        player.field.add(card)
        record(room, GameEvent.SPECIAL_SUMMON, userId, card, fromZone, "field", effect,
                java.util.Map.of<String, Any?>("summonType", req.kind, "cost", cost, "used", (if (summoner == null) 0 else 1) + materials.size),
                player.username + " invoca por " + kindName(req.kind) + " a " + name(card) + " usando " + used + " y pagando "
                        + gemsText(cost) + (if (effect != null) " (" + effect.sourceName + ")" else ""))
        afterSpecialSummon(room, player, card, effect)
    }

    /**
     * "en su lugar, puede invocar especialmente esta carta" (EXO-91, SARS-2): a regular special summon of `card` with `ally` as its
     * summoner is possible now: the ally meets the Requerimiento, the materials are there and the cost can be paid.
     */
    @JvmStatic
    fun canSummonInsteadOfRemoval(room: GameRoom, player: PlayerState, card: CardSlot, ally: CardSlot, reduce: Int): Boolean {
        return summoningAs(card) { canSummonInsteadOfRemovalAs(room, player, card, ally, reduce) }
    }

    @JvmStatic
    fun canSummonInsteadOfRemovalAs(room: GameRoom, player: PlayerState, card: CardSlot, ally: CardSlot, reduce: Int): Boolean {
        val req = specialRequirement(card)
        if (req == null || card.specialCost == null || "ritual" == req.kind || req.declaredSummoner() || req.noSummoner) return false
        if (!specialSummoners(player, card, req).contains(ally) || !materialsPossible(player, card, req, ally)) return false
        if ("promotion" == req.kind && !alliesAddUp(player, req)) return false
        return canPay(room, player.userId, card.specialCost!! - reduce
                - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind, card, ally))
    }

    /** Carries out that special summon: asks for the materials when the Requerimiento has some, then summons (the ally takes no other part). */
    @JvmStatic
    fun summonInsteadOfRemoval(room: GameRoom, player: PlayerState, card: CardSlot, ally: CardSlot) {
        val req = specialRequirement(card)
        val s = CardEffects.allyRemovalSummon(card)
        val from = if (find(player.hand, card.instanceId) != null) "hand" else "tributeZone"
        val option = optionFor(req, ally)
        if ("materialization" == req!!.kind && (option!!.materialCount > 0 || option.levelSum > 0)) {
            val materials = specialMaterials(player, card, req, ally)
            val shown: MutableList<CardSlot> = ArrayList()
            for (z in option.materialZones!!) shown.addAll(zone(player, z))
            shown.remove(card)
            shown.remove(ally)
            rulesPrompt(room, player.userId, PromptPurpose.REPLACE_SUMMON.id(), name(card) + " · en lugar de que " + name(ally) + " sea removida",
                    materialInstruction(req, card, ally), "Material", materials, shown)
            room.prompt!!.min = Math.max(1, option.materialCount)
            room.prompt!!.max = if (option.levelSum > 0) materials.size else option.materialCount
            room.prompt!!.subjectId = card.instanceId
            room.prompt!!.summonerId = ally.instanceId
            return
        }
        specialSummon(room, player, card, req, ally, java.util.List.of(), from, if (s == null) 0 else s.reduce, null)
    }

    /** The materials chosen for [summonInsteadOfRemoval]. */
    @JvmStatic
    fun answerSummonInsteadOfRemoval(room: GameRoom, prompt: Prompt, player: PlayerState, picks: List<String>): String? {
        var card = find(player.hand, prompt.subjectId)
        var from = "hand"
        if (card == null) {
            card = find(player.tributeZone, prompt.subjectId)
            from = "tributeZone"
        }
        val ally = find(player.field, prompt.summonerId)
        if (card == null || ally == null) {
            room.prompt = null
            advance(room)
            return null
        }
        val materials: MutableList<CardSlot> = ArrayList()
        for (id in picks) {
            var m = find(player.tributeZone, id)
            if (m == null) m = find(player.field, id)
            if (m == null) m = find(player.hand, id)
            if (m == null) return "Esa criatura ya no está disponible"
            materials.add(m)
        }
        val req = specialRequirement(card)
        val wrong = checkMaterials(req!!, ally, materials)
        if (wrong != null) return wrong
        room.prompt = null
        val s = CardEffects.allyRemovalSummon(card)
        specialSummon(room, player, card, req, ally, materials, from, if (s == null) 0 else s.reduce, null)
        advance(room)
        return null
    }
}
