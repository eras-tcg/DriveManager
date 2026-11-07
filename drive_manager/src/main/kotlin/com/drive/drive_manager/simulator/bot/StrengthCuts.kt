package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.engine.CardEffects
import com.drive.drive_manager.simulator.engine.EffectEngine
import com.drive.drive_manager.simulator.engine.Prompt
import com.drive.drive_manager.simulator.room.CardLocator
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState
import com.drive.drive_manager.simulator.room.Zone
import java.util.Locale
import java.util.regex.Pattern

/**
 * hReduce (the user's call, 2026-11-05, the ST1 deck): a creature reduced to 0 strength is destroyed, so a strength
 * reduction is a removal where it reaches 0, and only there; reducing a creature it cannot bring to 0 pays only when a
 * later play finishes it or it opens or wins a clash. The heuristic picked "the most valuable enemy" for it (Hez of 10
 * for 3 points, Carnhaus of 3 left alone), and as it plays both sides of the tries, activating Mathaus or Arc-xioma
 * looked worthless and the bot never did. Its pick for "reduce su fuerza en N", in order:
 * 1. the most valuable enemy it brings to 0: strength at most N plus what its own cards add on the way ("reducir su
 *    fuerza en 2 más", Mihel) or finish ("si reduces a K o menos … reducirla a 0", Arc-xioma);
 * 2. else the active enemy of the Línea frontal that blocks and beats one of its attackers ready to attack now, and
 *    would no longer after the reduction (the strongest such);
 * 3. else nothing when it may choose nothing, the most valuable enemy when it must choose.
 */
internal object StrengthCuts {

    private val AMOUNT: Pattern = Pattern.compile("(?:reduce|reducir|reduzca)[^.;]*?fuerza en (\\d+)|(?:reduce|reducir) en (\\d+) su fuerza")
    private val MORE: Pattern = Pattern.compile("reducir su fuerza en (\\d+) más")
    private val TO_ZERO: Pattern = Pattern.compile("si reduces a (\\d+) o menos la fuerza[^.]*reducirla a 0")

    /** The amount a prompt reduces, from its texts; -1 when it is not a strength reduction. */
    @JvmStatic
    fun amount(prompt: Prompt): Int {
        for (t in arrayOf(prompt.effectText, prompt.instruction)) {
            if (t == null) continue
            val m = AMOUNT.matcher(t.lowercase(Locale.ROOT))
            if (m.find()) return Integer.parseInt(if (m.group(1) != null) m.group(1) else m.group(2))
        }
        return -1
    }

    /** The pick (ids), or null when the prompt is not a strength reduction on enemies. */
    @JvmStatic
    fun pick(room: GameRoom, self: PlayerState?, prompt: Prompt, choices: List<String?>): List<String?>? {
        val n = amount(prompt)
        if (n < 0 || self == null) return null
        // What its own cards add: "en 2 más" (Mihel, in the hand or on the field) and "si reduces a K o menos … a 0"
        var more = 0
        var zeroAt = 0
        val own: MutableList<CardSlot> = ArrayList(self.field)
        own.addAll(self.hand)
        for (c in own) {
            for (e in CardEffects.of(c)) {
                val t = if (e.text == null) "" else e.text!!.lowercase(Locale.ROOT)
                val m = MORE.matcher(t)
                if (m.find()) more = Math.max(more, Integer.parseInt(m.group(1)))
                val z = TO_ZERO.matcher(t)
                if (z.find() && self.field.contains(c)) zeroAt = Math.max(zeroAt, Integer.parseInt(z.group(1)))
            }
        }
        val enemies = choices.mapNotNull { id -> find(room, self, id)?.takeIf { c -> "creature" == c.cardType } }
        if (enemies.isEmpty()) return null
        val reach = n + more
        val zero = zeroAt
        val kill = enemies.filter { c -> EffectEngine.strength(c) - reach <= Math.max(0, zero) }
                .maxWithOrNull(Comparator.comparingDouble { c: CardSlot -> BotPlayer.value(c) })
        if (kill != null) return java.util.List.of(kill.instanceId!!)
        // A blocker that beats one of its ready attackers and would not after the reduction
        val attackers = self.field.filter { c -> "creature" == c.cardType && !c.tapped && EffectEngine.inFrontLine(c) }
        val opened = enemies.filter { c -> !c.tapped && EffectEngine.inFrontLine(c)
                    && attackers.any { a -> EffectEngine.strength(c) >= EffectEngine.strength(a) && EffectEngine.strength(c) - n < EffectEngine.strength(a) } }
                .maxWithOrNull(Comparator.comparingInt { c: CardSlot -> EffectEngine.strength(c) })
        if (opened != null) return java.util.List.of(opened.instanceId!!)
        if (prompt.min == 0) return java.util.List.of()
        return java.util.List.of(enemies.maxWith(Comparator.comparingDouble { c: CardSlot -> BotPlayer.value(c) }).instanceId!!)
    }

    private fun find(room: GameRoom, self: PlayerState, id: String?): CardSlot? {
        val rival = room.getPlayer(room.getOpponentId(self.userId))
        return CardLocator.find(java.util.Arrays.asList(rival, self), id, java.util.List.of(Zone.FIELD), false)
    }
}
