package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmStatic
import kotlin.math.min
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/**
 * The bot's optimistic search, in its simulations only (the room's searchHope names whose searches it is): when a
 * look at the top of its deck finds valid cards, it trusts the search found the best ones. The chance of finding
 * any valid card is the deck's own (the copy's deck is shuffled as the bot cannot know its order), so the search pays
 * off as often as it would; what it finds, when it does, are the best valid cards of the deck: the most playable soon
 * ([Playability]), then the strongest. A search from a known zone (the Tributos) needs none of this.
 */
object SearchHope {

    /** Puts the best valid cards of the deck among the looked ones, as many as the valid ones it found (up to `max`). */
    @JvmStatic
    fun apply(room: GameRoom, effect: PendingEffect, player: PlayerState, valid: (CardSlot) -> Boolean, max: Int) {
        if (room.searchHope == null || room.searchHope != player.userId) return
        val looked = effect.looked
        val found = looked.count { valid(it) }
        val k = min(found, max)
        if (k == 0) return
        val pool: MutableList<CardSlot> = ArrayList()
        for (c in looked) if (valid(c)) pool.add(c)
        for (c in player.deck) if (valid(c)) pool.add(c)
        val me = player.userId
        pool.sortWith(comparingInt { c: CardSlot -> Playability.level(room, me, c) }
                .then(comparingInt { c: CardSlot -> if (c.strength == null) 0 else c.strength!! }.reversed())
                .then(comparingInt { c: CardSlot -> if (c.cost == null) 0 else c.cost!! }.reversed()))
        val best = pool.subList(0, min(k, pool.size))
        // Each best card from the deck swaps places with a valid looked card that is not among the best
        for (b in ArrayList(best)) {
            if (looked.contains(b)) continue
            val out = looked.firstOrNull { c -> valid(c) && !best.contains(c) } ?: break
            val li = looked.indexOf(out)
            val di = player.deck.indexOf(b)
            if (di < 0) continue
            b.faceDown = out.faceDown
            looked[li] = b
            out.faceDown = true
            player.deck[di] = out
        }
    }
}
