package com.drive.drive_manager.simulator.bot

import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.platform.AtomicFlag
import kotlin.math.max
import kotlin.math.min
import com.drive.drive_manager.simulator.engine.frozenListOf
import com.drive.drive_manager.simulator.engine.frozenMapOf
import com.drive.drive_manager.simulator.engine.EffectEngine
import com.drive.drive_manager.simulator.engine.Prompt
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/**
 * A side of the table played by the server (a room against the bot). It sends the same actions as a player and
 * decides only from what its own player sees: its hand, its deck top in the mulligan, the table and the open
 * choice, never the rival's hidden cards.
 *
 * This base plays the least it can (phase 0): it keeps its hand, answers every choice with the fewest cards it
 * allows and ends its turn. [HeuristicBot] plays to win, overriding [mulligan], [answer] and
 * [mainMove]. [next] never changes the room: the room service sends the move and tells the bot how
 * it went ([played], [refused]).
 */
open class BotPlayer {

    private val waking = AtomicFlag()
    /** Moves the engine refused (by [key]): not tried again while the memory is about the same moment. */
    private val refused: MutableSet<String> = HashSet()
    /**
     * Answers the engine refused to the choice that waits now ([refusedPrompt]), kept past a new moment: the same answer
     * to the same choice is refused again, so it is never sent twice. (A driver that asks one bot for both seats starts
     * a new moment at every call, which forgot [refused] and sent the refused answer forever: st3-st4 seeds 2, 3, 5.)
     */
    private val refusedAnswers: MutableSet<String> = HashSet()
    private var refusedPrompt: String? = null
    /** The moment the memory is about: the room's status, whose turn it is and the bot's deck. */
    private var moment: String? = null
    private var movesThisTurn = 0

    // ------------------------------------------------------------------ The move

    /** The bot's next move in the room, or null when it has nothing to do now (it waits for the other player). */
    open fun next(room: GameRoom, me: String?): GameAction? {
        val self = room.getPlayer(me) ?: return null
        remember(room, self)
        @Suppress("UNCHECKED_CAST")
        return when (room.status) {
            GameRoom.RoomStatus.WAITING -> if (self.deckId != null && self.starterCardId != null && !self.ready)
                untried(action(ActionType.SET_READY, frozenMapOf<String, Any?>("ready", true))) else null
            GameRoom.RoomStatus.SELECTING_STARTER -> if ((room.starterSelections as Map<String?, String>).containsKey(me)) null
                else self.deck.firstOrNull { it.starter }
                    ?.let { c -> untried(action(ActionType.SELECT_STARTER, frozenMapOf<String, Any?>("instanceId", c.instanceId))) }
            GameRoom.RoomStatus.MULLIGAN -> if ((room.mulliganReady as Set<String?>).contains(me)) null else untried(action("CONFIRM_MULLIGAN",
                frozenMapOf<String, Any?>("toBottom", mulligan(room, self, ArrayList(self.deck.subList(0, min(5, self.deck.size)))))))
            GameRoom.RoomStatus.IN_PROGRESS -> inGame(room, me)
            GameRoom.RoomStatus.POST_GAME -> if ((room.postGameChoices as Map<String?, String>).containsKey(me)) null
                else untried(action(ActionType.POST_GAME_CHOICE, frozenMapOf<String, Any?>("choice", "rematch")))
            else -> null
        }
    }

    private fun inGame(room: GameRoom, me: String?): GameAction? {
        val prompt = room.prompt
        if (prompt != null) return if (me!! == prompt.playerId) answerAction(room, me, prompt) else null
        if (room.isEngineBusy() || me!! != room.currentTurnUserId) return null
        if ("MAIN" == room.currentPhase && movesThisTurn < MAX_MOVES_PER_TURN) {
            val move = mainMove(room, me)
            if (move != null && !isRefused(move)) return move
        }
        // The end phase with nothing pending passes the turn too
        return untried(action(ActionType.END_TURN, frozenMapOf()))
    }

    /** The answer to the bot's choice: its own, else the plainest ones the prompt allows; conceding when none is left. */
    private fun answerAction(room: GameRoom, me: String?, prompt: Prompt): GameAction {
        val options: MutableList<List<String?>> = ArrayList()
        val own = answer(room, me, prompt)
        if (own != null) options.add(own)
        options.addAll(fallbacks(prompt))
        for (chosen in options) {
            val answer = action(ActionType.RESOLVE_PROMPT, frozenMapOf<String, Any?>("promptId", prompt.id, "chosen", chosen, "order", order(prompt, chosen)))
            if (!isRefused(answer)) return answer
        }
        return action(ActionType.SURRENDER, frozenMapOf())
    }

    // ------------------------------------------------------------------ What a bot decides (phase 0: the least it can)

    /** Mulligan: the instance ids of the deck's top cards sent to the bottom. Phase 0 keeps them all. */
    protected open fun mulligan(room: GameRoom, self: PlayerState, top: List<CardSlot>): List<String> {
        return frozenListOf()
    }

    /** The cards (or offered effects) chosen in the bot's choice; null leaves it to the fallbacks. Phase 0: the fewest. */
    protected open fun answer(room: GameRoom, me: String?, prompt: Prompt): List<String>? {
        val choices = choices(prompt)
        @Suppress("UNCHECKED_CAST")
        return if (choices.size < prompt.min) null else ArrayList(choices.subList(0, prompt.min)) as List<String>
    }

    /** What the bot does in its main phase, or null to end its turn. Phase 0 ends it. */
    protected open fun mainMove(room: GameRoom, me: String?): GameAction? {
        return null
    }

    // ------------------------------------------------------------------ Told by the room service

    /** The bot's own chance starts again from this seed (a bot that decides by chance, as one that looks ahead on copies). */
    open fun seed(seed: Long) {
    }

    /** The move went through. */
    open fun played(room: GameRoom, action: GameAction) {
        movesThisTurn++
        // Its own turn ended: the next one is a new moment even when the bot was not asked during the rival's turn (a
        // match driver that asks it only when it has something to do). Else the moves of its turns added up and, past
        // MAX_MOVES_PER_TURN, it passed with the game won at hand (duel st1-st2 s13395, 2026-11-06)
        if (ActionType.END_TURN.`is`(action)) moment = null
    }

    /** The engine refused the move: it is not tried again at this moment. */
    open fun refused(action: GameAction, error: String?) {
        refused.add(key(action))
        if (ActionType.RESOLVE_PROMPT.`is`(action)) {
            val prompt = action.getString("promptId")
            if (prompt != refusedPrompt) {
                refusedPrompt = prompt
                refusedAnswers.clear()
            }
            refusedAnswers.add(key(action))
        }
    }

    /** Only one pending wake at a time: true when this call takes it. */
    open fun claimWake(): Boolean {
        return waking.compareAndSet(false, true)
    }

    open fun releaseWake() {
        waking.set(false)
    }

    /** How long the bot waits before its next move. */
    open fun pause(room: GameRoom, me: String?): Long {
        if (room.status != GameRoom.RoomStatus.IN_PROGRESS) return PAUSE_ROOM_MS
        return if (me!! == room.currentTurnUserId && room.prompt == null) PAUSE_TURN_MS else PAUSE_ANSWER_MS
    }

    // ------------------------------------------------------------------ Helpers

    /** How many moves the engine refused at this moment (a decision made before a refusal is made again). */
    protected open fun refusals(): Int {
        return refused.size
    }

    protected open fun isRefused(action: GameAction): Boolean {
        val k = key(action)
        return refused.contains(k) || refusedPrompt != null && refusedAnswers.contains(k) && refusedPrompt == action.getString("promptId")
    }

    /** The action, or null when the engine already refused it. */
    protected open fun untried(action: GameAction): GameAction? {
        return if (isRefused(action)) null else action
    }

    /** A new moment (another turn, the next game, another deck): what was refused may work now. */
    private fun remember(room: GameRoom, self: PlayerState) {
        val now = room.status.toString() + "|" + room.currentTurnUserId + "|" + self.deckId + "|" + self.starterCardId
        if (now == moment) return
        moment = now
        refused.clear()
        movesThisTurn = 0
        newMoment()
    }

    /** A new moment began (another turn, the next game): what a bot remembers of the last one may go. */
    protected open fun newMoment() {
    }

    companion object {

        /**
         * Moves tried in a row for one decision before the bot concedes (the engine refused them all). A refused move
         * changes nothing, so trying is cheap: enough to go through every group of a short list of cards.
         */
        internal const val MAX_ATTEMPTS = 1100
        /** Moves of one turn before the bot ends it, whatever it could still do (an effect it could repeat forever). */
        internal const val MAX_MOVES_PER_TURN = 60

        /** Pauses before a move, so the player follows it on the board. */
        internal const val PAUSE_ROOM_MS: Long = 400
        internal const val PAUSE_ANSWER_MS: Long = 700
        internal const val PAUSE_TURN_MS: Long = 1000

        /** Lists up to this long are searched group by group when nothing plainer is accepted (2^10 groups at most). */
        private const val MAX_COMBINED = 10

        private fun key(action: GameAction): String {
            // As the toString of a TreeMap of the payload: "{a=1, b=x}" by key
            val payload: Map<String, Any?> = action.payload ?: frozenMapOf()
            return action.type + payload.keys.sorted().joinToString(", ", "{", "}") { k -> k + "=" + payload[k] }
        }

        @JvmStatic
        fun action(type: ActionType, payload: Map<String, Any?>): GameAction {
            return action(type.name, payload)
        }

        @JvmStatic
        fun action(type: String?, payload: Map<String, Any?>): GameAction {
            val action = GameAction()
            action.type = type
            action.payload = HashMap(payload)
            return action
        }

        /** What may be chosen in the prompt: its cards that are valid, and the effects it offers. */
        @JvmStatic
        protected fun choices(prompt: Prompt): MutableList<String?> {
            val ids: MutableList<String?> = ArrayList(prompt.validIds)
            if (prompt.effects != null) prompt.effects!!.forEach { e -> if (!ids.contains(e.effectId)) ids.add(e.effectId) }
            return ids
        }

        /**
         * Plain answers the prompt allows, tried when the bot's own is refused: none, the first ones, each one alone, as
         * many as it allows; then, for a short list, every other group of cards, smallest first (a choice with a rule of
         * its own, as materials whose levels must add up to an exact number).
         */
        private fun fallbacks(prompt: Prompt): List<List<String?>> {
            val choices = choices(prompt)
            val min = prompt.min
            val max = min(prompt.max, choices.size)
            val out: MutableSet<List<String?>> = LinkedHashSet()
            if (min == 0) out.add(frozenListOf())
            if (min > 0 && choices.size >= min) out.add(ArrayList(choices.subList(0, min)))
            if (min <= 1 && max >= 1) choices.forEach { id -> out.add(frozenListOf(id)) }
            if (max > min) out.add(ArrayList(choices.subList(0, max)))
            if (choices.size <= MAX_COMBINED) {
                for (size in max(1, min)..max) groups(choices, size, 0, ArrayList(), out)
            }
            return ArrayList(out)
        }

        private fun groups(choices: List<String?>, size: Int, from: Int, group: MutableList<String?>, out: MutableSet<List<String?>>) {
            if (group.size == size) {
                out.add(ArrayList(group))
                return
            }
            for (i in from until choices.size) {
                group.add(choices[i])
                groups(choices, size, i + 1, group, out)
                group.removeAt(group.size - 1)
            }
        }

        /** The cards not chosen, in the order they are shown, when the prompt lets them be reordered. */
        private fun order(prompt: Prompt, chosen: List<String?>): List<String?> {
            if (!prompt.reorderRest) return frozenListOf()
            return prompt.cards.map { it.instanceId }.filter { id -> !chosen.contains(id) }
        }

        /** A rough worth of a card to its owner: its cost, its strength, its abilities and what it carries. */
        @JvmStatic
        fun value(card: CardSlot?): Double {
            if (card == null) return 0.0
            var v: Double = if (card.cost == null) 2.0 else card.cost!!.toDouble()
            if ("creature" == card.cardType) {
                v += EffectEngine.strength(card) * 0.6
                v += EffectEngine.keywords(card).size * 0.5
                if (card.getLevel() != null) v += card.getLevel()!! * 0.3
            }
            v += (card.materials!!.size + card.resources!!.size) * 0.5
            return v
        }

        /** One of the player's cards, in any zone (attachments included), or null. */
        @JvmStatic
        protected fun own(player: PlayerState?, instanceId: String?): CardSlot? {
            for (zone in frozenListOf(player!!.hand, player.field, player.deck, player.lifeStack,
                    player.tributeZone, player.discardPile)) {
                for (c in zone) {
                    if (instanceId!! == c.instanceId) return c
                    for (a in c.materials!!) if (instanceId == a.instanceId) return a
                    for (a in c.resources!!) if (instanceId == a.instanceId) return a
                }
            }
            return null
        }

        /** A card on the player's field, or null. */
        @JvmStatic
        protected fun onField(player: PlayerState?, instanceId: String?): CardSlot? {
            if (player == null || instanceId == null) return null
            return player.field.firstOrNull { c -> instanceId == c.instanceId }
        }
    }
}
