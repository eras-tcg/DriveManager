package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.platform.Platform
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/**
 * A Ritual's Requerimiento that names each creature used, the summoner among them:
 * "criatura nivel 2 + 1 criatura [ángel] nivel 2 o menor", "2 criaturas nivel 2 o menor",
 * "2 criaturas nivel 4 en juego, una [ángel] y otra [ángel caído]". Each creature used fills one part; the summoner is one of them.
 *
 * "Cuando fueras a invocar esta carta [en turno oponente]: puedes utilizar un enemigo considerándolo [tipo] para el requerimiento" (Lilith,
 * [Reaction.EnemyAsTypeForRequirement]): an enemy of the opponent's Línea frontal can fill a part of that type, if it meets the rest of the part
 * itself; it is sacrificed for the Ritual and goes to its owner's Tributos (designer, Ángeles 4 and 2026-11-13). It is never the summoner.
 */
object RitualParts {

    /** One creature of the Requerimiento: its level ("o menor": at most), and its type if it says one. */
    @JvmRecord
    data class Part(val level: Int, val orLess: Boolean, val type: String?) {
        fun fits(card: CardSlot, asType: String?): Boolean {
            if (EffectEngine.levels(card).none { l -> if (orLess) l <= level else l == level }) return false
            if (type == null) return true
            return ClassChanges.classes(card).any { it.equals(type, ignoreCase = true) } || type.equals(asType, ignoreCase = true)
        }

        fun describe(): String = "criatura" + (if (type == null) "" else " [$type]") + " nivel " + level + (if (orLess) " o menor" else "")
    }

    private val PLUS = Platform.regex("^criatura\\s+nivel\\s*(\\d+)\\s*\\+\\s*(\\d+)\\s*criaturas?\\s*(?:\\[([^\\]]+)\\]\\s*)?nivel\\s*(\\d+)(\\s*o\\s*menor)?")
    private val MANY = Platform.regex(
        "^(\\d+)\\s*criaturas\\s*nivel\\s*(\\d+)(\\s*o\\s*menor)?(?:\\s*en\\s*juego)?(?:\\s*,\\s*una\\s*\\[([^\\]]+)\\]\\s*y\\s*otra\\s*\\[([^\\]]+)\\])?")

    /** The parts of such a Requerimiento (lower case), or null when it is not one. */
    @JvmStatic
    fun parse(text: String): List<Part>? {
        val plus = PLUS.find(text)
        if (plus != null) {
            val parts = ArrayList(frozenListOf(Part(plus.groupValues[1].toInt(), false, null)))
            val other = Part(plus.groupValues[4].toInt(), plus.groups[5] != null, plus.groups[3]?.value)
            repeat(plus.groupValues[2].toInt()) { parts.add(other) }
            return parts
        }
        val many = MANY.find(text) ?: return null
        val count = many.groupValues[1].toInt()
        if (count < 2) return null
        val types: List<String?> = arrayListOf(many.groups[4]?.value, many.groups[5]?.value)
        return (0 until count).map { i -> Part(many.groupValues[2].toInt(), many.groups[3] != null, if (types[0] == null || i > 1) null else types[i]) }
    }

    /** The enemies that can fill a part for this Ritual now (Lilith), and the type they count as; empty without such a reaction. */
    @JvmStatic
    fun enemies(room: GameRoom, player: PlayerState, target: CardSlot): List<CardSlot> {
        val r = CardEffects.enemyAsTypeForRequirement(target) ?: return frozenListOf()
        if (r.opponentTurn && room.currentTurnUserId == player.userId) return frozenListOf()
        val opponent = room.getPlayer(room.getOpponentId(player.userId)) ?: return frozenListOf()
        return opponent.field.filter { c -> "creature" == c.cardType && c.getLevel() != null && EffectEngine.inFrontLine(c) }
    }

    private fun enemyType(target: CardSlot): String? = CardEffects.enemyAsTypeForRequirement(target)?.asType

    /** Whether these creatures fill every part, one each (an enemy only a part of the type it counts as). */
    @JvmStatic
    fun fills(parts: List<Part>, cards: List<CardSlot>, enemies: List<CardSlot>, asType: String?): Boolean {
        if (cards.size != parts.size) return false
        return assign(parts, cards, 0, BooleanArray(parts.size), enemies, asType)
    }

    private fun assign(parts: List<Part>, cards: List<CardSlot>, i: Int, used: BooleanArray, enemies: List<CardSlot>, asType: String?): Boolean {
        if (i == cards.size) return true
        val card = cards[i]
        val enemy = enemies.contains(card)
        for (p in parts.indices) {
            if (used[p]) continue
            val part = parts[p]
            val ok = if (enemy) part.type != null && part.type.equals(asType, ignoreCase = true) && part.fits(card, asType) else part.fits(card, null)
            if (!ok) continue
            used[p] = true
            if (assign(parts, cards, i + 1, used, enemies, asType)) return true
            used[p] = false
        }
        return false
    }

    /** Some creatures of the pool complete the parts together with `fixed`. */
    private fun completes(parts: List<Part>, fixed: List<CardSlot>, pool: List<CardSlot>, enemies: List<CardSlot>, asType: String?): Boolean {
        val missing = parts.size - fixed.size
        if (missing < 0) return false
        if (missing == 0) return fills(parts, fixed, enemies, asType)
        val rest = pool.filter { c -> fixed.none { it === c } }
        return choose(rest, 0, missing, ArrayList(fixed)) { fills(parts, it, enemies, asType) }
    }

    private fun choose(pool: List<CardSlot>, from: Int, left: Int, picked: MutableList<CardSlot>, test: (List<CardSlot>) -> Boolean): Boolean {
        if (left == 0) return test(picked)
        for (i in from until pool.size) {
            picked.add(pool[i])
            if (choose(pool, i + 1, left - 1, picked, test)) return true
            picked.removeAt(picked.size - 1)
        }
        return false
    }

    /** Your creatures that can be the summoner (rule 9.2.0.13: it meets a part, and the others can complete the rest). */
    @JvmStatic
    fun summoners(room: GameRoom, player: PlayerState, target: CardSlot, parts: List<Part>): List<CardSlot> {
        val own = Rituals.ritualMaterials(player, target)
        val enemies = enemies(room, player, target)
        val asType = enemyType(target)
        return own.filter { s -> completes(parts, frozenListOf(s), own + enemies, enemies, asType) }
    }

    /** The creatures that can be used with that summoner (yours, and the enemies that can fill a part). */
    @JvmStatic
    fun others(room: GameRoom, player: PlayerState, target: CardSlot, parts: List<Part>, summoner: CardSlot): List<CardSlot> {
        val pool = Rituals.ritualMaterials(player, target) + enemies(room, player, target)
        val enemies = enemies(room, player, target)
        val asType = enemyType(target)
        return pool.filter { c -> c !== summoner && completes(parts, frozenListOf(summoner, c), pool, enemies, asType) }
    }

    /** What is wrong with these creatures for the Ritual, or null when they meet it. */
    @JvmStatic
    fun check(room: GameRoom, player: PlayerState, target: CardSlot, parts: List<Part>, summoner: CardSlot, others: List<CardSlot>): String? {
        val pool = Rituals.ritualMaterials(player, target) + enemies(room, player, target)
        if (others.any { o -> pool.none { it === o } } || pool.none { it === summoner }) return "Esa criatura no puede usarse para este Ritual"
        val all = ArrayList(frozenListOf(summoner))
        all.addAll(others)
        return if (fills(parts, all, enemies(room, player, target), enemyType(target))) null
        else "Deben ser " + parts.size + " criaturas: " + parts.joinToString(", ") { it.describe() }
    }

    /** "Elige las otras N criaturas …". */
    @JvmStatic
    fun instruction(parts: List<Part>, summoner: CardSlot): String {
        return "Elige " + (if (parts.size == 2) "la otra criatura" else "las otras " + (parts.size - 1) + " criaturas") + " que se sacrifican junto a " +
                EffectEngine.name(summoner) + ". Entre todas: " + parts.joinToString(", ") { it.describe() } + "."
    }

    /** "Elige el invocador: 1 criatura nivel 2 …". */
    @JvmStatic
    fun summonerInstruction(parts: List<Part>, target: CardSlot): String {
        return "Elige el invocador: 1 de " + parts.joinToString(", ") { it.describe() } + ". Se sacrifica y " + EffectEngine.name(target) +
                " ocupa su lugar y hereda su estado."
    }
}
