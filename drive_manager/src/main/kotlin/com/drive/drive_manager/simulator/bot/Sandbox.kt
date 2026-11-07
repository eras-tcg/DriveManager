package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.engine.GameRandom
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.GameStateUpdate
import com.drive.drive_manager.simulator.room.RoomCopy
import com.drive.drive_manager.simulator.room.RoomEngine
import com.drive.drive_manager.simulator.room.RoomHost
import java.util.concurrent.atomic.AtomicLong

/**
 * Where the bot tries its moves: copies of a room, played by the room's rules alone ([RoomEngine]: no
 * database, no messages, no missions), so the real game is never touched.
 *
 * A copy holds only what the bot may know. The rival's hidden cards (hand, deck and face-down lives) are shuffled
 * together and dealt back, as many to each zone as there were: the bot knows how many cards each zone holds, not
 * which. Its own deck is shuffled too: it does not know its order.
 */
class Sandbox {

    private val engine = RoomEngine(RoomHost.gameOnly())
    /** The chance of the copies (their shuffles, the hidden cards dealt again): the bot's own, seedable. */
    private var chance = java.util.Random()

    /** The same seed, the same copies: two bots seeded alike try their moves on the same deals. */
    fun seed(seed: Long) {
        chance = java.util.Random(seed)
    }

    /** A copy of the room as the bot may know it, ready to play on. */
    fun fork(room: GameRoom, me: String?): GameRoom {
        return fork(room, me, null)
    }

    /** The same, dealt by `deal` (the same seed, the same copy) or, when null, by the sandbox's own chance. */
    fun fork(room: GameRoom, me: String?, deal: java.util.Random?): GameRoom {
        val chance = deal ?: this.chance
        val copy = RoomCopy.of(room, chance)!!
        copy.roomId = "sim-" + IDS.incrementAndGet()
        copy.emptySince = null
        copy.headless = true
        hideFrom(copy, me, chance)
        return copy
    }

    /** A seed for a deal of its own, from the sandbox's chance. */
    fun deal(): Long {
        return chance.nextLong()
    }

    /** A move of `seat` on the copy (in a test room, the rival's side is played by its owner acting for it). */
    fun act(copy: GameRoom, seat: String?, action: GameAction): GameStateUpdate? {
        return engine.applyAs(copy, seat, action)
    }

    /** Done with a copy (kept for the callers: the copies are no longer registered anywhere). */
    fun drop(copy: GameRoom?) {
    }

    companion object {
        private val IDS = AtomicLong()

        /** What the bot cannot see is dealt again at random (see the class). */
        @JvmStatic
        fun hideFrom(copy: GameRoom, me: String?, chance: java.util.Random?) {
            val self = copy.getPlayer(me)
            if (self != null) GameRandom.shuffle(self.deck, chance)
            val rival = copy.getPlayer(copy.getOpponentId(me)) ?: return
            val lives = rival.lifeStack
            val hiddenLives: MutableList<Int> = ArrayList()
            for (i in 0 until lives.size) if (lives[i].faceDown) hiddenLives.add(i)
            val pool: MutableList<CardSlot> = ArrayList(rival.hand)
            pool.addAll(rival.deck)
            hiddenLives.forEach { i -> pool.add(lives[i]) }
            GameRandom.shuffle(pool, chance)
            var next = 0
            for (i in 0 until rival.hand.size) rival.hand[i] = faced(pool[next++], false)
            for (i in 0 until rival.deck.size) rival.deck[i] = faced(pool[next++], true)
            for (i in hiddenLives) lives[i] = faced(pool[next++], true)
        }

        private fun faced(card: CardSlot, down: Boolean): CardSlot {
            card.faceDown = down
            return card
        }
    }
}
