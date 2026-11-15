package com.drive.drive_manager.simulator.room

import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.bot.BotSeat
import com.drive.drive_manager.simulator.engine.frozenSetOf
import com.drive.drive_manager.simulator.engine.GameEvent
import com.drive.drive_manager.simulator.engine.PromptPurpose
import com.drive.drive_manager.simulator.engine.Prompt

/**
 * What one player may see of a room. The room's state is sent to each player on their own queue, so the other
 * side's hidden cards never leave the server:
 * - the rival's hand, both decks (contents and order) and the face-down lives are card backs; in the mulligan,
 *   until they confirm it, a player sees the five cards on top of their own deck (the ones they choose from);
 * - the rival's face-down cards on the field, the Tributos or the Descarte are card backs (their owner knows them);
 * - the rival's deck list, the choice the rival is making and what the rival can activate stay out;
 * - the timeline and the history do not name the cards drawn, looked at or sent to a hidden place.
 *
 * A test room is played by its owner on both sides: it is sent whole. A player who may fix the board by hand (a site
 * editor) also sees their own deck and lives, which the manual actions work on.
 *
 * Only the rule of what each one sees and who is sent it ([recipients]): sending it is [RoomMessages]'s (the server's),
 * and a game in the browser uses the same rules.
 */
object StateMask {

    /** `manual`: the viewer may use the manual actions in this room. */
    @JvmStatic
    fun forViewer(update: GameStateUpdate, viewerId: String?, manual: Boolean): GameStateUpdate {
        // A test room, or a replay of a game over: everything in view
        val byId = update.players
        if (byId == null || update.editor || update.replay) return update
        val view = ShallowCopy.of(update)

        val hidden: MutableSet<String?> = HashSet()
        // The mulligan is chosen from the top of the player's own deck: those cards are theirs to see until they confirm
        val choosing = GameRoom.RoomStatus.MULLIGAN.name == update.status
                && (update.mulliganReady == null || !update.mulliganReady!!.contains(viewerId))
        val ownShown = if (choosing) MULLIGAN_CARDS else 0
        // A life lost by direct damage that its owner may Reforzar with or play (it waits in their hand): it was revealed,
        // so the rival sees it too while its owner decides
        val revealed = update.prompt?.takeIf { p -> PromptPurpose.REINFORCE.`is`(p.purpose) || PromptPurpose.LIFE_PLAY.`is`(p.purpose) }?.subjectId
        val players: MutableMap<String, PlayerState> = LinkedHashMap()
        byId.forEach { (id, player) ->
            val mine = id == viewerId
            players[id] = mask(player, mine, manual, if (mine) ownShown else 0, hidden, revealed)
        }
        view.players = players

        val prompt = update.prompt
        if (prompt != null && viewerId!! != prompt.playerId) view.prompt = waitingOn(prompt)
        view.activatable = onlyMine(update.activatable, viewerId)
        view.specialSummonable = onlyMine(update.specialSummonable, viewerId)
        view.unplayable = onlyMine(update.unplayable, viewerId)
        val timeline = update.timeline
        if (timeline != null) view.timeline = timeline.mapTo(ArrayList(timeline.size)) { e -> redact(e, viewerId, hidden) }
        val eventLog = update.eventLog
        if (eventLog != null) view.eventLog = eventLog.mapTo(ArrayList(eventLog.size)) { e -> redact(e, hidden) }
        return view
    }

    /**
     * Who is sent an update (each their own [forViewer] of it): an error only who caused it (as it is); any other
     * update the room's players, who caused it and who it is about (a player who just left or was taken out still
     * gets the update that says so), never the test room's rival nor the bot. A replay is only its viewer's. `room` is
     * null once it is closed: its last update still names who was in it.
     */
    @JvmStatic
    fun recipients(room: GameRoom?, update: GameStateUpdate): Set<String> {
        if (update.error != null) return if (update.actorId == null) emptySet() else setOf(update.actorId!!)
        val viewers = LinkedHashSet<String>()
        if (room != null && room.replay) room.hostId?.let { viewers.add(it) } else update.players?.let { viewers.addAll(it.keys) }
        update.actorId?.let { viewers.add(it) }
        update.subjectUserId?.let { viewers.add(it) }
        viewers.remove(RoomEngine.EDITOR_RIVAL)
        viewers.remove(BotSeat.BOT)
        return viewers
    }

    /** The cards a player chooses from in the mulligan (PreRoomActions.handleConfirmMulligan). */
    const val MULLIGAN_CARDS = 5

    /** `shown`: how many cards on top of the deck keep their face (the owner's, in the mulligan). */
    private fun mask(player: PlayerState, mine: Boolean, manual: Boolean, shown: Int, hidden: MutableSet<String?>, revealed: String?): PlayerState {
        val masked = ShallowCopy.of(player)
        val ownHidden = mine && manual
        masked.hand = if (mine) player.hand
        else player.hand.mapTo(ArrayList(player.hand.size)) { c ->
            if (revealed != null && c.instanceId == revealed && !c.faceDown) withMaskedAttachments(c, hidden) else back(c, hidden)
        }
        masked.deck = if (ownHidden) player.deck else deckBacks(player, shown, hidden)
        masked.originalDeck = if (mine) player.originalDeck else starterOnly(player)
        masked.lifeStack = if (ownHidden) player.lifeStack else faceDownBacks(player.lifeStack, hidden)
        masked.tributeZone = if (mine) player.tributeZone else faceDownBacks(player.tributeZone, hidden)
        masked.discardPile = if (mine) player.discardPile else faceDownBacks(player.discardPile, hidden)
        masked.field = if (mine) player.field else faceDownBacks(player.field, hidden)
        return masked
    }

    /** Of the rival's deck list, only the starter they chose: the pre-room shows it on their seat. */
    private fun starterOnly(player: PlayerState): MutableList<CardSlot> {
        val starter = player.starterCardId ?: return ArrayList()
        val shown = ArrayList<CardSlot>(1)
        val first = player.originalDeck.firstOrNull { c -> starter == c.cardId }
        if (first != null) shown.add(first)
        return shown
    }

    /**
     * The deck as backs with ids of their own: a card known before (one returned to the deck) cannot be followed. The
     * first `shown` cards keep their face and their id (the mulligan sends them back by id).
     */
    private fun deckBacks(player: PlayerState, shown: Int, hidden: MutableSet<String?>): MutableList<CardSlot> {
        val backs: MutableList<CardSlot> = ArrayList()
        for (i in 0 until player.deck.size) {
            if (i < shown) {
                backs.add(player.deck[i])
                continue
            }
            hidden.add(player.deck[i].instanceId)
            val back = CardSlot()
            back.instanceId = "deck-" + player.userId + "-" + i
            back.faceDown = true
            backs.add(back)
        }
        return backs
    }

    private fun backs(cards: List<CardSlot>?, hidden: MutableSet<String?>): MutableList<CardSlot> {
        return cards!!.mapTo(ArrayList(cards.size)) { c -> back(c, hidden) }
    }

    private fun faceDownBacks(cards: List<CardSlot>?, hidden: MutableSet<String?>): MutableList<CardSlot> {
        return cards!!.mapTo(ArrayList(cards.size)) { c -> if (c.faceDown) back(c, hidden) else withMaskedAttachments(c, hidden) }
    }

    /** A face-up card shows its face, but what is attached face down under it stays hidden. */
    private fun withMaskedAttachments(card: CardSlot, hidden: MutableSet<String?>): CardSlot {
        val anyHidden = card.materials!!.any { it.faceDown } || card.resources!!.any { it.faceDown }
        if (!anyHidden) return card
        val copy = ShallowCopy.of(card)
        copy.materials = faceDownBacks(card.materials, hidden)
        copy.resources = faceDownBacks(card.resources, hidden)
        return copy
    }

    /** A card back: where it is and whether it is exhausted, nothing of what it is. */
    private fun back(card: CardSlot, hidden: MutableSet<String?>): CardSlot {
        hidden.add(card.instanceId)
        val back = CardSlot()
        back.instanceId = card.instanceId
        back.faceDown = true
        back.tapped = card.tapped
        back.x = card.x
        back.y = card.y
        back.materials = backs(card.materials, hidden)
        back.resources = backs(card.resources, hidden)
        return back
    }

    /** The rival is choosing: who, and for which card's effect, but not the cards or the options. */
    private fun waitingOn(prompt: Prompt): Prompt {
        val shown = Prompt()
        shown.id = prompt.id
        shown.kind = prompt.kind
        shown.playerId = prompt.playerId
        shown.effectId = prompt.effectId
        shown.purpose = prompt.purpose
        shown.sourceName = prompt.sourceName
        shown.sourceImageUrl = prompt.sourceImageUrl
        shown.title = prompt.title
        // The revealed life its owner decides on (Reforzar, play it): the rival's board shows it waiting too
        if (PromptPurpose.REINFORCE.`is`(prompt.purpose) || PromptPurpose.LIFE_PLAY.`is`(prompt.purpose)) shown.subjectId = prompt.subjectId
        return shown
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> onlyMine(byPlayer: Map<String, T>?, viewerId: String?): MutableMap<String, T>? {
        if (byPlayer == null) return null
        val mine: MutableMap<String?, T?> = LinkedHashMap()
        val keys = byPlayer as Map<String?, T>
        if (keys.containsKey(viewerId)) mine[viewerId] = keys[viewerId]
        return mine as MutableMap<String, T>
    }

    /** Events that always name a card only their player sees. */
    private val PRIVATE_EVENTS: Set<String> = frozenSetOf(GameEvent.DRAW, GameEvent.LOOK_TOP)
    /** Events that name a card the viewer cannot see when it went to a hidden place. */
    private val MOVES: Set<String> = frozenSetOf(GameEvent.SEND, GameEvent.MOVE)

    private fun redact(event: GameEvent, viewerId: String?, hidden: Set<String?>): GameEvent {
        val secret = viewerId!! != event.playerId && (PRIVATE_EVENTS.contains(event.type!!)
                || MOVES.contains(event.type!!) && hidden.contains(event.cardInstanceId))
        if (!secret || event.cardName == null) return event
        val copy = ShallowCopy.of(event)
        copy.cardName = null
        return copy
    }

    private fun redact(entry: GameLogEntry, hidden: Set<String?>): GameLogEntry {
        if (entry.sourceId == null || !hidden.contains(entry.sourceId)) return entry
        val copy = ShallowCopy.of(entry)
        copy.sourceName = null
        copy.imageUrl = null
        return copy
    }
}
