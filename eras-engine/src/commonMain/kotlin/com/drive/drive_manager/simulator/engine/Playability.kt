package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmStatic
import kotlin.math.min
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom

/**
 * How soon a card can be played, by the rules and the known state of the game, as the board's legal actions are
 * found: a creature by its normal summon (its cost, with the reductions it has) or its special summon (a summoner,
 * the materials, the levels, the special cost); a utility if its "En Juego" can be activated (targets, limits) and
 * paid; a structure if it can be paid (and is the first of the turn). [NOW] with the gems and the table of
 * now; [NEXT_TURN] with the gems its Marcador will give at its next turn and the same table; else [LATER].
 * Where the card is does not matter (the hand, a look at the deck, the Tributos): it is asked as if it were in the hand.
 */
object Playability {

    const val NOW = 0
    const val NEXT_TURN = 1
    const val LATER = 2

    @JvmStatic
    fun level(room: GameRoom?, userId: String?, card: CardSlot?): Int {
        if (room == null || card == null || room.getPlayer(userId) == null) return LATER
        if (playableNow(room, userId, card)) return NOW
        // Its next turn: the gems its Marcador gives (one more, up to 5), its own main phase; the table as it is
        val player = room.getPlayer(userId)!!
        val counter = room.sharedCounter
        val turn = room.currentTurnUserId
        val phase = room.currentPhase
        try {
            room.sharedCounter = min(5, player.resourceCount + 1) * EffectEngine.direction(room, userId)
            room.currentTurnUserId = userId
            room.currentPhase = "MAIN"
            return if (playableNow(room, userId, card)) NEXT_TURN else LATER
        } finally {
            room.sharedCounter = counter
            room.currentTurnUserId = turn
            room.currentPhase = phase
        }
    }

    private fun playableNow(room: GameRoom, userId: String?, card: CardSlot): Boolean {
        try {
            val player = room.getPlayer(userId)
            if ("creature" == card.cardType) {
                return Summons.whyCannotSummon(room, userId, player, card) == null || SpecialRequirements.canSpecial(room, userId, card)
            }
            return Summons.whyCannotPlay(room, userId, card) == null
        } catch (e: RuntimeException) {
            // A card the checks cannot place (out of every zone they look at): not counted as playable
            return false
        }
    }
}
