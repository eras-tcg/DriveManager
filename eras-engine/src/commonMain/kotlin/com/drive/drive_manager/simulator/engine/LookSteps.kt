package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmStatic
import kotlin.math.min
import com.drive.drive_manager.simulator.engine.EffectEngine.find
import com.drive.drive_manager.simulator.engine.EffectEngine.ids
import com.drive.drive_manager.simulator.engine.EffectEngine.log
import com.drive.drive_manager.simulator.engine.EffectEngine.name
import com.drive.drive_manager.simulator.engine.EffectEngine.plural
import com.drive.drive_manager.simulator.engine.EffectEngine.record
import com.drive.drive_manager.simulator.engine.EffectStep.*
import com.drive.drive_manager.simulator.engine.StepHelpers.assignable
import com.drive.drive_manager.simulator.engine.StepHelpers.assignableEach
import com.drive.drive_manager.simulator.engine.StepHelpers.inOrder
import com.drive.drive_manager.simulator.engine.StepHelpers.lookedToHand
import com.drive.drive_manager.simulator.engine.StepHelpers.pick
import com.drive.drive_manager.simulator.engine.StepHelpers.prompt
import com.drive.drive_manager.simulator.engine.StepHelpers.subject
import com.drive.drive_manager.simulator.engine.StepHelpers.takeFromLooked
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/** Taking from the cards looked at, and revealing. Each step: whether it can be done, carrying it out, and applying the player's choice. */
object LookSteps {

    @JvmStatic
    fun possibleStep(room: GameRoom, effect: PendingEffect, step: EffectStep): Boolean {
        val player = room.getPlayer(effect.controllerId)!!
        @Suppress("UNUSED_VARIABLE")
        val onField = find(player.field, effect.sourceInstanceId)
        return when (step) {
            is RevealFromHand -> assignable(player.hand.filter { c -> c.instanceId!! != effect.sourceInstanceId },
                step.filters)
            else -> true
        }
    }

    @JvmStatic
    fun runStep(room: GameRoom, effect: PendingEffect, step: EffectStep): Boolean {
        val player = room.getPlayer(effect.controllerId)!!
        val userId = player.userId

        when (step) {
            is TakeFromLookedEachRestToBottom -> {
                if (effect.looked.isEmpty()) return false
                SearchHope.apply(room, effect, player, { c -> step.filters!!.any { f -> f.matches(c) } }, step.filters!!.size)
                val valid = effect.looked.filter { c -> step.filters!!.any { f -> f.matches(c) } }.map { it.instanceId }
                val prompt = prompt(room, effect, Prompt.Kind.TAKE_REVEALED_REST_BOTTOM, "Toma " +
                        step.filters!!.joinToString(" y ") { f -> "hasta 1 " + f.describe() } + " para tu mano. Ordena el resto: irá al fondo de tu mazo.")
                prompt.cards = ArrayList(effect.looked)
                prompt.validIds = ArrayList(valid)
                prompt.min = 0
                prompt.max = min(step.filters!!.size, valid.size)
                prompt.reorderRest = true
                prompt.chosenLabel = "A tu mano"
                room.prompt = prompt
                return true
            }
            is TakeFromLookedDistinctLevels -> {
                if (effect.looked.isEmpty()) return false
                val filter = step.filter!!
                SearchHope.apply(room, effect, player, { c -> filter.matches(c) }, step.max)
                val valid = effect.looked.filter { c -> filter.matches(c) }.map { it.instanceId }
                val prompt = prompt(room, effect, Prompt.Kind.TAKE_REVEALED_REST_BOTTOM,
                        "Toma hasta " + step.max + " cartas " + step.filter!!.describe() + " con distinto nivel para tu mano. Ordena el resto: irá al fondo de tu mazo.")
                prompt.cards = ArrayList(effect.looked)
                prompt.validIds = ArrayList(valid)
                prompt.min = 0
                prompt.max = min(step.max, valid.size)
                prompt.reorderRest = true
                prompt.chosenLabel = "A tu mano"
                room.prompt = prompt
                return true
            }
            is RevealFromHand -> {
                val valid = player.hand.filter { c -> c.instanceId!! != effect.sourceInstanceId
                        && step.filters!!.any { f -> f.matches(c) } }
                if (!assignable(valid, step.filters)) return false
                pick(room, effect, "Revela de tu mano: " + step.filters!!.joinToString(" y ") { f -> "1 " + f.describe() } + ".",
                        "Revelar", player.hand, ids(valid), step.filters!!.size)
                return true
            }
            is RevealOpponentHandSendOneToBottom -> {
                val opponent = room.getPlayer(room.getOpponentId(userId))
                if (opponent == null || opponent.hand.isEmpty()) return false
                val hand: MutableList<CardSlot> = ArrayList(opponent.hand)
                GameRandom.shuffle(hand, room.random)
                val revealed = hand.subList(0, min(step.count, hand.size))
                log(room, opponent.username + " revela " + revealed.joinToString(", ") { name(it) } + " (" + effect.sourceName + ")", null)
                pick(room, effect, "Envía 1 de las cartas reveladas al fondo del mazo de tu rival.", "Al fondo", revealed, ids(revealed), 1)
                return true
            }
            is TakeFromLookedLikeSubject -> {
                if (effect.looked.isEmpty()) return false
                val subject = subject(room, effect)
                val classes: List<String> = if (subject == null || subject.classes == null) frozenListOf() else subject.classes!!
                SearchHope.apply(room, effect, player, { c -> c.classes != null && c.classes!!.any { t -> classes.any { t.equals(it, ignoreCase = true) } } }, step.max)
                val valid = effect.looked
                        .filter { c -> c.classes != null && c.classes!!.any { t -> classes.any { t.equals(it, ignoreCase = true) } } }
                        .map { it.instanceId }
                takeFromLooked(room, effect, Prompt.Kind.TAKE_REVEALED_REST_BOTTOM, "Toma hasta " + step.max + " " + plural(step.max, "carta", "cartas")
                        + " que comparta tipo con " + name(subject) + " para tu mano. El resto va al fondo de tu mazo en orden aleatorio.", valid, step.max)
                return true
            }
            else -> throw IllegalStateException("Paso de otra familia: $step")
        }
    }

    @JvmStatic
    fun applyStep(
        room: GameRoom, effect: PendingEffect, step: EffectStep, player: PlayerState, picks: List<String>,
        order: List<String>?, repeat: BooleanArray?,
    ): String? {
        val userId = player.userId
        when (step) {
            is TakeFromLookedEachRestToBottom -> {
                val chosen = picks.map { id -> find(effect.looked, id) }
                if (chosen.contains(null)) return "Esa carta ya no está"
                if (!assignableEach(step.filters, chosen)) return "Elige " + step.filters!!.joinToString(" y ") { f -> "hasta 1 " + f.describe() }
                lookedToHand(room, effect, player, picks)
                val rest = inOrder(effect.looked, order)
                rest.forEach { c -> c.faceDown = true }
                player.deck.addAll(rest)
                effect.looked.clear()
            }
            is TakeFromLookedDistinctLevels -> {
                val levels = picks.map { id -> find(effect.looked, id) }.map { c -> c?.getLevel() }
                if (HashSet(levels).size < levels.size) return "Las cartas deben tener distinto nivel"
                lookedToHand(room, effect, player, picks)
                val rest = inOrder(effect.looked, order)
                rest.forEach { c -> c.faceDown = true }
                player.deck.addAll(rest)
                if (!rest.isEmpty()) log(room, player.username + " envía " + rest.size + " " + plural(rest.size, "carta", "cartas") + " al fondo de su mazo", null)
                effect.looked.clear()
            }
            is RevealFromHand -> {
                val cards = picks.map { id -> find(player.hand, id) }
                if (cards.contains(null)) return "Esa carta ya no está en tu mano"
                if (!assignable(cards, step.filters)) return "Revela " + step.filters!!.joinToString(" y ") { f -> "1 " + f.describe() }
                effect.lastDone = true
                effect.lastTributed = ArrayList(picks)
                log(room, player.username + " revela " + cards.joinToString(", ") { name(it) } + " (" + effect.sourceName + ")", null)
            }
            is RevealOpponentHandSendOneToBottom -> {
                val opponent = room.getPlayer(room.getOpponentId(userId))
                val card = if (opponent == null) null else find(opponent.hand, picks[0])
                if (card == null) return "Esa carta ya no está"
                opponent!!.hand.remove(card)
                card.faceDown = true
                opponent.deck.add(card)
                record(room, GameEvent.SEND, userId, card, "hand", "deck", effect, frozenMapOf<String, Any?>("ownerId", opponent.userId),
                        player.username + " envía " + name(card) + " al fondo del mazo de " + opponent.username + " (" + effect.sourceName + ")")
            }
            is TakeFromLookedLikeSubject -> {
                for (id in picks) {
                    val card = find(effect.looked, id) ?: return "Esa carta ya no está"
                    effect.looked.remove(card)
                    card.faceDown = false
                    player.hand.add(card)
                    record(room, GameEvent.ADD_TO_HAND, userId, card, "deck", "hand", effect, null,
                            player.username + " añade " + name(card) + " a su mano (" + effect.sourceName + ")")
                }
                val rest: MutableList<CardSlot> = ArrayList(effect.looked)
                GameRandom.shuffle(rest, room.random)
                rest.forEach { c -> c.faceDown = true }
                player.deck.addAll(rest)
                if (!rest.isEmpty()) log(room, player.username + " envía " + rest.size + " " + plural(rest.size, "carta", "cartas")
                        + " al fondo de su mazo en orden aleatorio", null)
                effect.looked.clear()
            }
            else -> {
                return "Este paso no espera ninguna elección"
            }
        }
        return null
    }
}
