package com.drive.drive_manager.simulator.bot

import kotlin.math.pow
import com.drive.drive_manager.simulator.engine.valueOr
import com.drive.drive_manager.simulator.engine.mergeWith
import com.drive.drive_manager.simulator.engine.comparingDouble
import com.drive.drive_manager.simulator.engine.Playability
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/**
 * What each card of a hand is worth to keep, from 0 to 1 (hRank, the user's rule for what to tribute from the hand,
 * 2026-11-04): the product of three factors, strongest first.
 * - Copies in the hand: the first copy of a card counts 1, the second `rCopy`, the third `rCopy`², the
 *   fourth `rCopy`³ (with 0.4: a second copy is already the one to give up, a fourth is worth nothing).
 * - Cards of the same type (creature, utility, structure): within each type, the best card counts 1, the next
 *   `rType`, the next `rType`², so piling up one type while giving up the others costs.
 * - Playability ([Playability], by the rules): 1 if it can be played now, `rPlay` if at its next turn,
 *   `rPlay`² if later.
 *
 * A hand of different cards of different types, all playable now, is worth what it was before (1 a card). The table's
 * value counts the hand by these ([TableScore.hand]), so the search sees what a tribute costs; the heuristic
 * and the tributes tried give up the lowest first.
 */
internal object HandRank {

    @JvmStatic
    fun on(w: BotSettings): Boolean {
        return w.get("hRank") >= 0.5
    }

    /** Each card's worth to keep, by its index in the hand. */
    @JvmStatic
    fun quality(room: GameRoom?, p: PlayerState, w: BotSettings): DoubleArray {
        val hand = p.hand
        val n = hand.size
        val rCopy = w.get("rCopy")
        val rType = w.get("rType")
        val rPlay = w.get("rPlay")
        val q = DoubleArray(n)
        val copies: MutableMap<String, Int> = HashMap()
        val levels: MutableMap<String, Int> = HashMap()
        for (i in 0 until n) {
            val c = hand[i]
            val key = if (c.cardName == null) "#$i" else BotSettings.cardKey(c.cardName)
            val j = copies.mergeWith(key, 1) { a, b -> a + b }!! - 1
            var play = 1.0
            if (room != null && rPlay < 1) {
                val level = levels.getOrPut(key) { Playability.level(room, p.userId, c) }
                play = rPlay.pow(level.toDouble())
            }
            q[i] = rCopy.pow(j.toDouble()) * play
        }
        // Within each type the best go first: the k-th of a type counts rType^k
        val byType: MutableMap<String, MutableList<Int>> = HashMap()
        for (i in 0 until n) {
            val type = hand[i].cardType ?: ""
            byType.getOrPut(type) { ArrayList() }.add(i)
        }
        for (same in byType.values) {
            same.sortWith(comparingDouble { i: Int -> -q[i] })
            for (k in 0 until same.size) q[same[k]] *= rType.pow(k.toDouble())
        }
        return q
    }

    /** The cards chosen among (ids in the hand), the least worth keeping first; ties by the card's size. */
    @JvmStatic
    fun weakestFirst(room: GameRoom?, p: PlayerState, choices: List<String?>, w: BotSettings): List<String?> {
        val q = quality(room, p, w)
        val byId: MutableMap<String?, Double> = HashMap()
        val size: MutableMap<String?, Double> = HashMap()
        for (i in 0 until p.hand.size) {
            val c = p.hand[i]
            byId[c.instanceId] = q[i]
            size[c.instanceId] = BotPlayer.value(c)
        }
        val order: MutableList<String?> = ArrayList(choices)
        order.sortWith(comparingDouble { id: String? -> byId.valueOr(id, 1.0) }.then(comparingDouble { id -> size.valueOr(id, 0.0) }))
        return order
    }
}
