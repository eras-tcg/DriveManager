package com.drive.drive_manager.simulator.bot

import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmField
import kotlin.math.max
import com.drive.drive_manager.simulator.engine.frozenListOf
import com.drive.drive_manager.simulator.engine.EffectEngine
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/**
 * The table as numbers, seen by one player: what the bot's value model reads (see [ValueModel]). Only what
 * that player may see: of the rival, how many cards each hidden zone holds, never which. The order of
 * [NAMES] is the order of the vector; a saved model keeps the names it was trained with.
 */
internal object BoardFeatures {

    @JvmField
    val NAMES: List<String> = frozenListOf(
            "myLives", "oppLives", "myHand", "oppHand", "myDeck", "oppDeck",
            "myCreatures", "oppCreatures",
            "myFrontActiveStr", "oppFrontActiveStr", "myFrontTappedStr", "oppFrontTappedStr", "myBackStr", "oppBackStr",
            "myFieldValue", "oppFieldValue", "myMaxStr", "oppMaxStr",
            "mySupport", "oppSupport", "myKeywords", "oppKeywords", "myAttached", "oppAttached",
            "myGems", "myMarker", "oppMarker", "myTurn", "lifeDiff", "fieldValueDiff")

    /** The vector for `me` (in the order of [NAMES]). */
    @JvmStatic
    fun of(room: GameRoom, me: String?): DoubleArray {
        val self = room.getPlayer(me)
        val rival = room.getPlayer(room.getOpponentId(me))
        val a = side(self)
        val b = side(rival)
        return doubleArrayOf(
                a.lives, b.lives, a.hand, b.hand, a.deck, b.deck,
                a.creatures, b.creatures,
                a.frontActive, b.frontActive, a.frontTapped, b.frontTapped, a.back, b.back,
                a.value, b.value, a.maxStr, b.maxStr,
                a.support, b.support, a.keywords, b.keywords, a.attached, b.attached,
                EffectEngine.gemsOf(room, me).toDouble(), (self?.resourceCount ?: 0).toDouble(), (rival?.resourceCount ?: 0).toDouble(),
                if (me!! == room.currentTurnUserId) 1.0 else 0.0, a.lives - b.lives, a.value - b.value)
    }

    private class Side(val lives: Double, val hand: Double, val deck: Double, val creatures: Double, val frontActive: Double,
                       val frontTapped: Double, val back: Double, val value: Double, val maxStr: Double, val support: Double,
                       val keywords: Double, val attached: Double)

    private fun side(p: PlayerState?): Side {
        if (p == null) return Side(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        var creatures = 0.0
        var frontActive = 0.0
        var frontTapped = 0.0
        var back = 0.0
        var value = 0.0
        var maxStr = 0.0
        var support = 0.0
        var keywords = 0.0
        var attached = 0.0
        for (c in p.field) {
            attached += (c.materials!!.size + c.resources!!.size).toDouble()
            if ("creature" != c.cardType) {
                support++
                continue
            }
            val str = EffectEngine.strength(c)
            creatures++
            value += BotPlayer.value(c)
            maxStr = max(maxStr, str.toDouble())
            keywords += EffectEngine.keywords(c).size.toDouble()
            if (!EffectEngine.inFrontLine(c)) back += str.toDouble()
            else if (c.tapped) frontTapped += str.toDouble()
            else frontActive += str.toDouble()
        }
        return Side(p.lifeStack.size.toDouble(), p.hand.size.toDouble(), p.deck.size.toDouble(), creatures, frontActive, frontTapped,
                back, value, maxStr, support, keywords, attached)
    }
}
