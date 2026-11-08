package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.engine.Chain.activate
import com.drive.drive_manager.simulator.engine.Chain.advance
import com.drive.drive_manager.simulator.engine.Chain.paying
import com.drive.drive_manager.simulator.engine.Chain.picked
import com.drive.drive_manager.simulator.engine.Chain.rank
import com.drive.drive_manager.simulator.engine.Chain.responses
import com.drive.drive_manager.simulator.engine.CombatRules.startCombat
import com.drive.drive_manager.simulator.engine.EffectEngine.SUMMON_SACRIFICE
import com.drive.drive_manager.simulator.engine.EffectEngine.canPay
import com.drive.drive_manager.simulator.engine.EffectEngine.find
import com.drive.drive_manager.simulator.engine.EffectEngine.findAnywhere
import com.drive.drive_manager.simulator.engine.EffectEngine.frontLine
import com.drive.drive_manager.simulator.engine.EffectEngine.ids
import com.drive.drive_manager.simulator.engine.EffectEngine.inFrontLine
import com.drive.drive_manager.simulator.engine.EffectEngine.locate
import com.drive.drive_manager.simulator.engine.EffectEngine.log
import com.drive.drive_manager.simulator.engine.EffectEngine.lookingAs
import com.drive.drive_manager.simulator.engine.EffectEngine.loseByLives
import com.drive.drive_manager.simulator.engine.EffectEngine.name
import com.drive.drive_manager.simulator.engine.EffectEngine.names
import com.drive.drive_manager.simulator.engine.EffectEngine.pay
import com.drive.drive_manager.simulator.engine.EffectEngine.plural
import com.drive.drive_manager.simulator.engine.EffectEngine.record
import com.drive.drive_manager.simulator.engine.EffectEngine.script
import com.drive.drive_manager.simulator.engine.Removals.answerRemoval
import com.drive.drive_manager.simulator.engine.Resolution.looker
import com.drive.drive_manager.simulator.engine.Rituals.addsUpTo
import com.drive.drive_manager.simulator.engine.Rituals.handRitual
import com.drive.drive_manager.simulator.engine.Rituals.requirement
import com.drive.drive_manager.simulator.engine.Rituals.ritualByPlayer
import com.drive.drive_manager.simulator.engine.Rituals.ritualCost
import com.drive.drive_manager.simulator.engine.SpecialRequirements.NO_SUMMONER
import com.drive.drive_manager.simulator.engine.SpecialRequirements.checkMaterials
import com.drive.drive_manager.simulator.engine.SpecialRequirements.fieldPiecesDiscount
import com.drive.drive_manager.simulator.engine.SpecialRequirements.findDeclared
import com.drive.drive_manager.simulator.engine.SpecialRequirements.specialRequirement
import com.drive.drive_manager.simulator.engine.SpecialRequirements.specialSource
import com.drive.drive_manager.simulator.engine.SpecialSummons.DISCOUNT_MATERIALS
import com.drive.drive_manager.simulator.engine.SpecialSummons.answerSummonInsteadOfRemoval
import com.drive.drive_manager.simulator.engine.SpecialSummons.handSpecial
import com.drive.drive_manager.simulator.engine.SpecialSummons.specialByPlayer
import com.drive.drive_manager.simulator.engine.SpecialSummons.specialSummon
import com.drive.drive_manager.simulator.engine.Summons.NORMAL_SUMMON
import com.drive.drive_manager.simulator.engine.Summons.completeAscension
import com.drive.drive_manager.simulator.engine.Summons.normalSummon
import com.drive.drive_manager.simulator.engine.Summons.play
import com.drive.drive_manager.simulator.engine.Summons.regulatoryDraw
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/**
 * The players' answers to the engine's choices: the rules' own (Ritual, move up, Reforzar, chaining, responding) here; an
 * effect step's choice is applied by [Steps].
 */
object Answers {

    // ------------------------------------------------------------------ Answers

    /**
     * The player's answer to the pending prompt.
     *
     * @param chosen chosen card instance ids
     * @param order  for prompts with `reorderRest`: the cards that were not chosen, in the order they go to the deck bottom
     * @return an error message, or null when accepted
     */
    @JvmStatic
    fun answer(room: GameRoom, userId: String?, promptId: String?, chosen: List<String>?, order: List<String>?): String? {
        val prompt = room.prompt
        if (prompt == null || prompt.id != promptId) return "No hay ninguna elección pendiente"
        if (prompt.playerId != userId) return "Esta elección le corresponde al otro jugador"
        val picks: List<String> = chosen ?: java.util.List.of()
        if (HashSet(picks).size != picks.size) return "Elegiste la misma carta dos veces"
        if (picks.size < prompt.min || picks.size > prompt.max) {
            return if (prompt.min == prompt.max)
                "Debes elegir " + prompt.min + " " + plural(prompt.min, "carta", "cartas")
            else "Puedes elegir hasta " + prompt.max + " " + plural(prompt.max, "carta", "cartas")
        }
        val allowed: MutableSet<String?> = HashSet(prompt.validIds)
        if (prompt.effects != null) prompt.effects!!.forEach { e -> allowed.add(e.effectId) }
        if (!allowed.containsAll(picks)) return "Esa carta no cumple lo que pide el efecto"
        val player = room.getPlayer(userId) ?: return "No estás en la partida"

        // Creatures that move up to the Línea frontal, all at once
        if (PromptPurpose.ADVANCE.`is`(prompt.purpose)) {
            room.prompt = null
            val moved: MutableList<String> = ArrayList()
            for (id in picks) {
                val card = find(player.field, id)
                if (card == null || inFrontLine(card)) continue
                card.y = 25.0
                moved.add(name(card))
                record(room, GameEvent.MOVE, userId, card, null, "frontLine", null, java.util.Map.of<String, Any?>("reason", "advance"), null)
            }
            if (!moved.isEmpty()) log(room, player.username + " sube a la línea frontal: " + moved.joinToString(", "), room.eventSeq)
            advance(room)
            return null
        }

        val other = answerRules(room, prompt, player, picks)
        if (other != null) return if (other.isEmpty()) null else other

        // Combat choices: the blocker, or the enemy forced to block by Provocar
        if (PromptPurpose.BLOCK_SWAP.`is`(prompt.purpose) || PromptPurpose.DAMAGE_REPLACE.`is`(prompt.purpose)) {
            room.prompt = null
            if (PromptPurpose.BLOCK_SWAP.`is`(prompt.purpose)) CombatRules.swapBlocker(room, player, prompt, picks)
            else CombatRules.replaceDamage(room, player, picks)
            advance(room)
            return null
        }
        if (prompt.purpose != null) {
            val c = room.combat
            room.prompt = null
            if (c != null && !picks.isEmpty()) {
                val defender = room.getPlayer(c.defenderId)!!
                val blocker = find(defender.field, picks[0])
                if (blocker != null) {
                    blocker.tapped = true
                    c.blockerId = blocker.instanceId
                    val forced = PromptPurpose.PROVOKE.`is`(prompt.purpose)
                    record(room, GameEvent.BLOCK, defender.userId, blocker, null, null, null, java.util.Map.of<String, Any?>("forced", forced),
                        name(blocker) + (if (forced) " debe bloquear (Provocar)" else " bloquea el ataque"))
                    // "cuando un aliado sea declarado bloqueador: en su lugar, puedes …" (Comandante Veterano)
                    if (CombatRules.offerBlockerSwap(room, defender, blocker)) return null
                }
            }
            advance(room)
            return null
        }

        // An optional triggered effect: activate it or let it go
        val queue = room.triggerQueue
        if (!queue.isEmpty() && queue[0].id == prompt.effectId) {
            val effect = queue.removeAt(0)
            room.prompt = null
            if (!picks.isEmpty()) {
                activate(room, effect)
            } else {
                record(room, GameEvent.EFFECT_DECLINED, userId, null, null, null, effect, null,
                    player.username + " no activa el efecto " + effect.label + " de " + effect.sourceName)
            }
            advance(room)
            return null
        }

        val effect = room.chain.firstOrNull { e -> e.id == prompt.effectId } ?: return "El efecto ya no está pendiente"
        val paying = paying(effect)
        val step = if (paying) script(effect).cost!![effect.costIndex] else script(effect).steps!![effect.stepIndex]
        val repeat = booleanArrayOf(false)
        // Cleared first: the step may ask something else right away (a special summon's move up)
        room.prompt = null
        val error = Resolution.apply(room, effect, step, player, picks, order, repeat)
        if (error != null) {
            room.prompt = prompt
            return error
        }
        if (!repeat[0]) {
            if (paying) effect.costIndex = effect.costIndex + 1
            else effect.stepIndex = effect.stepIndex + 1
        }
        advance(room)
        return null
    }

    /**
     * Answers to choices made by the rules rather than by an effect step: Ritual from the hand, moving up after a
     * special summon, Reforzar, playing a life, the order of simultaneous triggers and responding to a chain.
     *
     * @return null if the prompt is not one of these, "" when done, or an error
     */
    /** Direct damage of `damage` to oneself by an effect (Biok's payment): the top life, as direct damage does. */
    @JvmStatic
    fun selfDamage(room: GameRoom, player: PlayerState, by: CardSlot, damage: Int) {
        val life = player.lifeStack.removeAt(0)
        life.faceDown = false
        val tribute = "creature" == life.cardType && (life.strength == null || CardEffects.lifeStrength(room, player.userId, life) <= damage)
        (if (tribute) player.tributeZone else player.hand).add(life)
        record(room, GameEvent.LIFE_DAMAGE, player.userId, life, "lifeStack", if (tribute) "tributeZone" else "hand", null,
            java.util.Map.of<String, Any?>("damage", damage, "attackerId", by.instanceId, "defenderId", player.userId),
            player.username + " se inflige " + damage + " de daño (" + name(by) + "): pierde la vida " + name(life) +
                    (if (tribute) ", que va a sus tributos" else ", que va a su mano"))
        if (player.lifeStack.isEmpty()) loseByLives(room, player)
    }

    @JvmStatic
    fun answerRules(room: GameRoom, prompt: Prompt, player: PlayerState, picks: List<String>): String? {
        val looker = if (prompt.subjectId == null) null else findAnywhere(room, player.userId, prompt.subjectId)
        return lookingAs(looker) { answerRulesAs(room, prompt, player, picks) }
    }

    @JvmStatic
    fun answerRulesAs(room: GameRoom, prompt: Prompt, player: PlayerState, picks: List<String>): String? {
        val userId = player.userId
        val purpose = prompt.purpose
        if (PromptPurpose.REPLACE_SUMMON.`is`(purpose)) {
            val error = answerSummonInsteadOfRemoval(room, prompt, player, picks)
            return error ?: ""
        }
        if (PromptPurpose.DAMAGE_TRIBUTE.`is`(purpose)) {
            val error = CombatRules.answerDamageTribute(room, prompt, player, picks)
            return error ?: ""
        }
        if (PromptPurpose.SUMMON_SACRIFICE.`is`(purpose)) {
            val card = find(player.hand, prompt.subjectId)
            room.prompt = null
            if (card == null) {
                advance(room)
                return ""
            }
            room.usedThisTurn.add(SUMMON_SACRIFICE + card.instanceId + "|" + (if (picks.isEmpty()) "-" else picks[0]))
            val y = if (prompt.summonerId == null) "" else prompt.summonerId!!
            val error = normalSummon(room, userId, card.instanceId, 50.0, if (y.isEmpty()) null else y.toDouble())
            if (error != null) {
                room.usedThisTurn.removeIf { k -> k.startsWith(SUMMON_SACRIFICE + card.instanceId + "|") }
                room.prompt = prompt
                return error
            }
            return ""
        }
        if (PromptPurpose.REPLACE.`is`(purpose) || PromptPurpose.REPLACE_HAND.`is`(purpose) || PromptPurpose.REPLACE_MATERIALS.`is`(purpose) ||
            PromptPurpose.REPLACE_GUARD.`is`(purpose) || PromptPurpose.REPLACE_RESOURCES.`is`(purpose)) {
            val error = answerRemoval(room, prompt, player, picks)
            if (error != null) return error
            advance(room)
            return ""
        }
        if (PromptPurpose.RITUAL.`is`(purpose)) {
            if (picks.isEmpty()) {
                room.prompt = null
                advance(room)
                return ""
            }
            val card = find(player.hand, prompt.subjectId)
            if (card == null) {
                room.prompt = null
                return "Esa carta ya no está en tu mano"
            }
            if (prompt.summonerId == null) {
                // This answer is the summoner: the Ritual goes on as if the card had been dropped onto it
                room.prompt = null
                val error = ritualByPlayer(room, userId, card.instanceId, picks[0])
                if (error != null) {
                    room.prompt = prompt
                    return error
                }
                return ""
            }
            val used: MutableList<CardSlot?> = ArrayList()
            for (id in picks) used.add(find(player.field, id))
            val summoner = if (prompt.summonerId != null) find(player.field, prompt.summonerId) else used[0]
            if (summoner == null || used.contains(null)) return "Esa criatura ya no está en el campo"
            val others: MutableList<CardSlot> = ArrayList()
            for (u in used) others.add(u!!)
            others.remove(summoner)
            val req = requirement(card)
            val all: MutableList<CardSlot> = ArrayList(others)
            all.add(summoner)
            if (req!!.sum && !addsUpTo(all, req.level)) {
                val total = all.sumOf { it.getLevel()!! }
                return "Los niveles suman " + total + ": deben sumar exactamente " + req.level
            }
            if (!canPay(room, userId, ritualCost(room, userId, card))) return "Ya no tienes gemas suficientes"
            room.prompt = null
            handRitual(room, player, card, summoner, others)
            return ""
        }
        if (PromptPurpose.COST_TAP.`is`(purpose)) {
            val card = specialSource(player, prompt.subjectId)
            val holder = if (prompt.validIds.isEmpty()) null else find(player.field, prompt.validIds[0])
            room.prompt = null
            if (card == null || holder == null) {
                advance(room)
                return ""
            }
            room.usedThisTurn.add(CostModifiers.tapKey(holder, card, !picks.isEmpty()))
            val normal = if (prompt.summonerId != null && prompt.summonerId!!.startsWith(NORMAL_SUMMON))
                prompt.summonerId!!.substring(NORMAL_SUMMON.length) else null
            val error = if (normal != null) normalSummon(room, userId, card.instanceId, 50.0, if (normal.isEmpty()) null else normal.toDouble())
            else specialByPlayer(room, userId, card.instanceId, prompt.summonerId)
            if (error != null) {
                // Without it the summon cannot be paid: ask again
                room.usedThisTurn.remove(CostModifiers.tapKey(holder, card, !picks.isEmpty()))
                room.prompt = prompt
                return if (picks.isEmpty()) "Sin esa reducción no puedes pagar el coste especial" else error
            }
            return ""
        }
        if (PromptPurpose.SPECIAL.`is`(purpose)) {
            val card = specialSource(player, prompt.subjectId)
            if (picks.isEmpty() || card == null) {
                room.prompt = null
                advance(room)
                return if (card == null) "Esa carta ya no está en tu mano" else ""
            }
            if (prompt.summonerId == null) {
                room.prompt = null
                val error = specialByPlayer(room, userId, card.instanceId, picks[0])
                if (error != null) {
                    room.prompt = prompt
                    return error
                }
                return ""
            }
            val req = specialRequirement(card)
            val materials: MutableList<CardSlot> = ArrayList()
            if (req!!.declaredSummoner()) {
                // The summoner declared from the hand or the Tributos is its first material; it enters the Retaguardia
                val declared = findDeclared(player, req, prompt.summonerId) ?: return "Esa criatura ya no está disponible"
                materials.add(declared)
            }
            val noSummoner = req.declaredSummoner() || NO_SUMMONER == prompt.summonerId
            val summoner = if (noSummoner) null else find(player.field, prompt.summonerId)
            for (id in picks) {
                var material = find(player.tributeZone, id)
                if (material == null) material = find(player.field, id)
                if (material == null) material = find(player.hand, id)
                if (material == null) return "Esa criatura ya no está disponible"
                materials.add(material)
            }
            if (summoner == null && !noSummoner) return "Esa criatura ya no está disponible"
            val expected = req.materialCount + (if (req.declaredSummoner()) 1 else 0)
            if (noSummoner && (if (req.moreMaterials) materials.size < expected else materials.size != expected))
                return "Elige " + req.materialCount + (if (req.moreMaterials) " o más" else "") + " criaturas"
            val wrong = if (noSummoner) null else checkMaterials(req, summoner, materials)
            if (wrong != null) return wrong
            if (!canPay(room, userId, card.specialCost!! - fieldPiecesDiscount(player, card, summoner, materials) -
                        CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, "materialization", card, summoner)))
                return "Ya no tienes gemas suficientes"
            room.prompt = null
            handSpecial(room, player, card, req, summoner, materials, if (find(player.hand, card.instanceId) != null) "hand" else "tributeZone")
            return ""
        }
        if (PromptPurpose.ASSAULT.`is`(purpose)) {
            room.prompt = null
            val card = find(player.field, prompt.subjectId)
            if (card != null && !picks.isEmpty() && !inFrontLine(card)) {
                card.y = 25.0
                card.tapped = true
                record(room, GameEvent.MOVE, userId, card, null, "frontLine", null, java.util.Map.of<String, Any?>("reason", "Asalto"),
                    name(card) + " entra agotada en la línea frontal (Asalto)")
            }
            advance(room)
            return ""
        }
        if (PromptPurpose.ASCEND_ATTACK.`is`(purpose)) {
            room.prompt = null
            val card = specialSource(player, prompt.subjectId)
            val summoner = find(player.field, prompt.summonerId)
            if (card == null || summoner == null) {
                advance(room)
                return ""
            }
            val fromZone = if (find(player.hand, card.instanceId) != null) "hand" else "tributeZone"
            if (picks.isEmpty()) {
                // No attack: the Ascenso goes on as asked (its cost modifiers are asked there)
                room.pendingAscension = PendingAscension(userId, card.instanceId, summoner.instanceId, fromZone)
                completeAscension(room)
                advance(room)
                return ""
            }
            val target = if (picks[0] == summoner.instanceId) null else picks[0]
            room.pendingAscension = PendingAscension(userId, card.instanceId, summoner.instanceId, fromZone)
            startCombat(room, userId, summoner, target, true, null)
            advance(room)
            return ""
        }
        if (PromptPurpose.SUMMONER_DISCOUNT.`is`(purpose)) {
            val card = specialSource(player, prompt.subjectId)
            val summoner = find(player.field, prompt.summonerId)
            if (card == null || summoner == null) {
                room.prompt = null
                advance(room)
                return ""
            }
            val req = specialRequirement(card)
            val use = CostModifiers.choice(room, player, CostModifiers.Event.SPECIAL_SUMMON, req!!.kind, card, summoner)
            val count = if (use == null) 0 else use.modifier.payment!!.count
            val selfDamage = use != null && use.modifier.payment!!.kind == CostModifiers.PaymentKind.SELF_DAMAGE
            if (!picks.isEmpty() && (if (selfDamage) picks.size > count else picks.size != count)) return "Elige $count cartas, o ninguna"
            val reduce = if (picks.isEmpty() || use == null) 0 else use.modifier.reduceNow(room, userId) * (if (selfDamage) picks.size else 1)
            // Without it, the others still apply on their own
            if (!canPay(room, userId, card.specialCost!! - reduce -
                        CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind, card, summoner) +
                        (if (use == null) 0 else use.modifier.reduceNow(room, userId))))
                return "Sin la reducción no tienes gemas suficientes: elige $count cartas"
            // The materials chosen before this payment (Biok)
            val kept = room.usedThisTurn.firstOrNull { k -> k.startsWith(DISCOUNT_MATERIALS + card.instanceId + "|") }
            val keptMaterials: MutableList<CardSlot> = ArrayList()
            if (kept != null) {
                room.usedThisTurn.remove(kept)
                val list = kept.substring((DISCOUNT_MATERIALS + card.instanceId + "|").length)
                // As Java's String.split: the empty strings at the end are left out
                for (id in if (list.isEmpty()) arrayOf<String>() else JavaText.split(Regex(","), list)) {
                    val at = locate(player, id)
                    if (at != null) keptMaterials.add(at.card!!)
                }
            }
            if (!picks.isEmpty() && use != null) CostModifiers.markUsed(room, player, use)
            room.prompt = null
            val paid: MutableList<CardSlot> = ArrayList()
            val asResource = use != null && use.modifier.payment!!.kind == CostModifiers.PaymentKind.TRIBUTE_AS_RESOURCE
            if (selfDamage) {
                var i = 0
                while (i < picks.size && !player.lifeStack.isEmpty()) {
                    Answers.selfDamage(room, player, use!!.holder, use.modifier.payment!!.damage())
                    i++
                }
            }
            for (id in if (selfDamage) java.util.List.of<String>() else picks) {
                // "puedes agotar 1 carta [filtro] bajo tu control para reducir…"
                if (use != null && use.modifier.payment!!.kind == CostModifiers.PaymentKind.TAP_CARD) {
                    val t = find(player.field, id)
                    if (t == null || t.tapped) continue
                    t.tapped = true
                    record(room, GameEvent.TAP, userId, t, null, null, null, null, player.username + " agota " + name(t) + " para reducir el coste especial")
                    continue
                }
                val c = find(player.tributeZone, id) ?: continue
                player.tributeZone.remove(c)
                paid.add(c)
                if (asResource) {
                    // It becomes a Recurso of the summoner, which passes its Recursos on to the creature it summons
                    c.faceDown = false
                    summoner.resources!!.add(c)
                    record(room, GameEvent.SEND, userId, c, "tributeZone", "resources", null, java.util.Map.of<String, Any?>("to", summoner.instanceId),
                        player.username + " añade " + name(c) + " como recurso de " + name(summoner))
                    continue
                }
                c.faceDown = true
                player.deck.add(c)
                record(room, GameEvent.SEND, userId, c, "tributeZone", "deck", null, java.util.Map.of<String, Any?>("position", "bottom"), null)
            }
            if (reduce > 0) log(room, player.username + " envía " + paid.size + " tributos al fondo de su mazo: " + name(use!!.holder) +
                    " reduce en " + reduce + " el coste especial" + names(paid), room.eventSeq)
            specialSummon(room, player, card, req, summoner, keptMaterials, if (find(player.hand, card.instanceId) != null) "hand" else "tributeZone",
                reduce, null)
            advance(room)
            return ""
        }
        if (PromptPurpose.SPECIAL_FRONT.`is`(purpose)) {
            room.prompt = null
            val card = find(player.field, prompt.subjectId)
            if (!picks.isEmpty() && card != null && !inFrontLine(card)) {
                card.y = 25.0
                record(room, GameEvent.MOVE, userId, card, null, "frontLine", null, java.util.Map.of<String, Any?>("reason", "special-summon"),
                    name(card) + " sube a la línea frontal tras su invocación especial")
            }
            val effect = if (prompt.effectId == null) null else room.chain.firstOrNull { e -> e.id == prompt.effectId }
            regulatoryDraw(room, player, effect)
            advance(room)
            return ""
        }
        if (PromptPurpose.REINFORCE.`is`(purpose) || PromptPurpose.LIFE_PLAY.`is`(purpose)) {
            room.prompt = null
            val card = find(player.hand, prompt.subjectId)
            if (!picks.isEmpty() && card != null) {
                if (PromptPurpose.REINFORCE.`is`(purpose)) {
                    player.hand.remove(card)
                    card.faceDown = false
                    player.lifeStack.add(card)
                    record(room, GameEvent.SEND, userId, card, "hand", "lifeStack", null, java.util.Map.of<String, Any?>("reinforce", true),
                        player.username + " refuerza con " + name(card) + ": va bocarriba al fondo de su pila de vidas")
                } else {
                    val lifeCost = CardEffects.lifePlayCost(card)
                    if (!canPay(room, userId, lifeCost)) {
                        advance(room)
                        return ""
                    }
                    pay(room, userId, lifeCost)
                    play(room, player, card, lifeCost, null, null, null)
                }
            }
            advance(room)
            return ""
        }
        if (PromptPurpose.ORDER.`is`(purpose)) {
            room.prompt = null
            // The group at the head of the queue, chained in the order the sources were picked
            val queue = room.triggerQueue
            val head = rank(queue[0], room.currentTurnUserId)
            val group: MutableList<PendingEffect> = ArrayList()
            while (!queue.isEmpty() && rank(queue[0], room.currentTurnUserId) == head) group.add(queue.removeAt(0))
            // The chosen one is chained now; the rest are asked for again
            group.sortWith(Comparator.comparingInt<PendingEffect> { e ->
                var position = picks.size
                for (at in picks.indices) if (picked(e, picks[at])) {
                    position = at
                    break
                }
                position
            })
            group[0].ordered = true
            queue.addAll(0, group)
            advance(room)
            return ""
        }
        if (PromptPurpose.OPTIONAL.`is`(purpose)) {
            room.prompt = null
            val queue = room.triggerQueue
            val head = rank(queue[0], room.currentTurnUserId)
            val group: MutableList<PendingEffect> = ArrayList()
            for (e in queue) {
                if (rank(e, room.currentTurnUserId) != head) break
                group.add(e)
            }
            if (picks.isEmpty()) {
                // No more: the rest of this player's optional effects are not activated
                for (e in group) {
                    queue.remove(e)
                    record(room, GameEvent.EFFECT_DECLINED, userId, null, null, null, e, null,
                        player.username + " no activa el efecto " + e.label + " de " + e.sourceName)
                }
            } else {
                // The chosen one goes first and is chained now (activateNext pays its cost next)
                val chosen = group.firstOrNull { e -> e.id == picks[0] } ?: group.firstOrNull { e -> picked(e, picks[0]) }
                if (chosen != null) {
                    queue.remove(chosen)
                    chosen.confirmed = true
                    queue.add(0, chosen)
                }
            }
            advance(room)
            return ""
        }
        if (PromptPurpose.PRIORITY.`is`(purpose)) {
            val top = if (room.chain.isEmpty()) null else room.chain[room.chain.size - 1]
            if (top == null) {
                room.prompt = null
                return ""
            }
            if (picks.isEmpty()) {
                top.passed.add(userId)
            } else {
                val response = responses(room, userId, top).firstOrNull { e -> e.sourceInstanceId == picks[0] } ?: return "Ese efecto ya no puede activarse"
                response.id = room.nextId("eff")
                activate(room, response)
            }
            room.prompt = null
            advance(room)
            return ""
        }
        return null
    }
}
