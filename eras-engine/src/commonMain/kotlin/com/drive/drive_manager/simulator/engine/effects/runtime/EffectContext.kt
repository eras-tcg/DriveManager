package com.drive.drive_manager.simulator.engine.effects.runtime

import kotlin.jvm.JvmSuppressWildcards
import com.drive.drive_manager.simulator.engine.GameRandom
import com.drive.drive_manager.simulator.engine.Amount
import com.drive.drive_manager.simulator.engine.effects.model.Action
import com.drive.drive_manager.simulator.engine.effects.model.Selector
import com.drive.drive_manager.simulator.engine.effects.model.SpecialRule
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.PlayerState

/**
 * What an action can see and do: the effect it belongs to and the game, through named operations. Actions never touch the
 * engine directly. The engine implements it (`EngineContext`).
 */
interface EffectContext {

    fun player(): PlayerState?

    /** The room's chance (see GameRoom#getRandom). */
    fun random(): GameRandom?

    fun opponent(): PlayerState?

    fun isSelf(card: CardSlot?): Boolean

    fun isOpponents(card: CardSlot?): Boolean

    fun isCreature(card: CardSlot?): Boolean

    /** The materials or resources a selector zone names ("materials", "resources:subject"). */
    fun attached(zone: String?): List<CardSlot>

    /** How a selector's zones read in a sentence. */
    fun describeZones(zones: String?): String?

    /** The card is already where a move would leave it. */
    fun alreadyIn(card: CardSlot?, to: String?): Boolean

    /** Moves the cards, one by one in that order, recording each one's event by where it arrives. Returns those that moved. */
    fun move(cards: List<CardSlot>?, to: String?, exhausted: Boolean, faceDown: Boolean): List<CardSlot>

    /** The cards an effect is looking at. */
    fun looked(): List<CardSlot>

    /** The looked cards not picked go there, in the order the player gave. */
    fun lookedRestTo(to: String?, order: List<String?>?)

    /** The card of the event that triggered the effect, unless it is on the field. */
    fun eventCard(): CardSlot?

    /** The card only reacts to its copies ("solo puede ser afectada por copias de sí misma"). */
    fun unaffected(card: CardSlot?): Boolean

    /** The choice just asked also orders the cards not picked; `toTop`: they end up on top of the deck. */
    fun asksOrder(toTop: Boolean)

    /** What the last action moved, for "si lo haces", "por cada…" and "si tributaste…". */
    fun moved(cards: List<CardSlot>?)

    fun draw(count: Int, bothPlayers: Boolean)

    fun gainGems(count: Int)

    fun damage(amount: Int)

    fun look(count: Int)

    fun onField(card: CardSlot?): Boolean

    fun canTap(card: CardSlot?): Boolean

    fun canUntap(card: CardSlot?): Boolean

    fun setTapped(card: CardSlot?, tapped: Boolean)

    fun strength(card: CardSlot?): Int

    /** Its strength goes up or down for a while; a reduction to 0 destroys it (rule 12.0.0.2). */
    fun changeStrength(card: CardSlot?, amount: Int, until: Action.Until?)

    fun grant(card: CardSlot?, keyword: String?, until: Action.Until?)

    fun destroy(card: CardSlot?, sacrifice: Boolean)

    /** The card the next actions act on: the one chosen before, or else this card. */
    fun subjectOrSelf(): CardSlot?

    /** The card of the triggering event, on the field. */
    fun eventCardOnField(): CardSlot?

    /** The card a relative selector compares with: self, subject, last or event. */
    fun relativeTo(to: String?): CardSlot?

    fun levelOf(card: CardSlot?): Int

    /** The creature this card clashes with. */
    fun clashing(): CardSlot?

    fun selfId(): String?

    /** The ally chosen before or, if a special summon replaced it, the creature that took its place. */
    fun subjectOrSummoned(): CardSlot?

    fun canPay(cost: Int): Boolean

    /** A utility or structure whose "En Juego" can be activated now. */
    fun canBePlayed(card: CardSlot?): Boolean

    /** The effect comes back later ("al final del turno"), so it may act on its own card. */
    fun delayed(): Boolean

    fun note(message: String?)

    /** A summon by effect. */
    fun summon(card: CardSlot?, reduce: Int, toFront: Boolean)

    /** A play by effect. */
    fun play(card: CardSlot?, reduce: Int)

    /** Which action of a sequence is running. */
    fun sequenceIndex(): Int

    fun sequenceIndex(index: Int)

    /** The effect stays on this instruction after this answer: the sequence goes on. */
    fun goOn()

    fun specialOptions(special: Selector.Special?): List<CardSlot>

    fun specialShown(special: Selector.Special?): List<CardSlot>

    fun specialBounds(special: Selector.Special?, available: Int): IntArray

    fun specialInstruction(special: Selector.Special?): String?

    fun specialCheck(special: Selector.Special?, picks: List<String?>?): String?

    fun summonSpecial(rule: SpecialRule?)

    /** The choice just asked may add up to that many levels. */
    fun limitLevelSum(levels: Int)

    /** The allies an "elige 1 aliado" can choose: those that can do what the effect goes on to ask. */
    fun pickable(zone: String?): List<CardSlot>

    /** The rest of the effect does nothing. */
    fun endEffect()

    /** The looked cards' choice also says top or bottom of the deck: a card picked is the top. */
    fun asksTopOrBottom()

    /** "declara ataque". Returns true when it asks what to attack. */
    fun attack(opponentOnly: Boolean): Boolean

    /** What to attack; none: the opponent. */
    fun attackChosen(picks: List<String?>?)

    /** The rest of the effect runs at the end of this turn: `effect` of this card. */
    fun atEndOfTurn(effect: String?)

    /** How many cards the last action acted on. */
    fun counted(count: Int)

    /** Whether the action did something, for "si lo haces". */
    fun done(done: Boolean)

    /** The card must be among those picked in the choice just asked. */
    fun requireInChoice(card: CardSlot?)

    /** The card cannot be a target of this effect. */
    fun immune(card: CardSlot?): Boolean

    /** The effect's card is in the Retaguardia: it cannot reach enemies. */
    fun inRetaguardia(): Boolean

    fun inFrontLine(card: CardSlot?): Boolean

    fun zone(player: PlayerState?, zone: String?): List<CardSlot>

    fun amount(amount: Amount?): Int

    fun describe(selector: Selector?): String?

    fun name(card: CardSlot?): String?

    /** Finds a card of either player by id, wherever it is. */
    fun find(instanceId: String?): CardSlot?

    /** Pauses the effect until the player picks between `min` and `max` of `valid`. */
    fun ask(instruction: String?, chosenLabel: String?, shown: List<CardSlot>?, valid: List<CardSlot>?, min: Int, max: Int)

    /** The cards saved under that name. */
    fun saved(name: String?): List<String?>

    /** Adds them under that name; `Memory.SUBJECT` keeps the first one and `Memory.LAST` replaces what it had. */
    fun save(name: String?, instanceIds: List<String?>?)

    /** Records an event of the game's timeline, its message included. */
    fun event(type: String?, card: CardSlot?, data: Map<String, @JvmSuppressWildcards Any?>?, message: String?)
}
