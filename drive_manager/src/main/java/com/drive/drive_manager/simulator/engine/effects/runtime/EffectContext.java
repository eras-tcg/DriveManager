package com.drive.drive_manager.simulator.engine.effects.runtime;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.Amount;
import com.drive.drive_manager.simulator.engine.effects.model.Selector;

import java.util.List;
import java.util.Map;

/**
 * What an action can see and do: the effect it belongs to and the game, through named operations. Actions never touch the
 * engine directly. The engine implements it ({@code EngineContext}).
 */
public interface EffectContext {

    PlayerState player();

    PlayerState opponent();

    boolean isSelf(CardSlot card);

    boolean isOpponents(CardSlot card);

    boolean isCreature(CardSlot card);

    /** The materials or resources a selector zone names ("materials", "resources:subject"). */
    List<CardSlot> attached(String zone);

    /** How a selector's zones read in a sentence. */
    String describeZones(String zones);

    /** The card is already where a move would leave it. */
    boolean alreadyIn(CardSlot card, String to);

    /** Moves the cards, one by one in that order, recording each one's event by where it arrives. Returns those that moved. */
    List<CardSlot> move(List<CardSlot> cards, String to, boolean exhausted, boolean faceDown);

    /** The cards an effect is looking at. */
    List<CardSlot> looked();

    /** The looked cards not picked go there, in the order the player gave. */
    void lookedRestTo(String to, List<String> order);

    /** The card of the event that triggered the effect, unless it is on the field. */
    CardSlot eventCard();

    /** The card only reacts to its copies ("solo puede ser afectada por copias de sí misma"). */
    boolean unaffected(CardSlot card);

    /** The choice just asked also orders the cards not picked; {@code toTop}: they end up on top of the deck. */
    void asksOrder(boolean toTop);

    /** What the last action moved, for "si lo haces", "por cada…" and "si tributaste…". */
    void moved(List<CardSlot> cards);

    void draw(int count, boolean bothPlayers);

    void gainGems(int count);

    void damage(int amount);

    void look(int count);

    boolean onField(CardSlot card);

    boolean canTap(CardSlot card);

    boolean canUntap(CardSlot card);

    void setTapped(CardSlot card, boolean tapped);

    int strength(CardSlot card);

    /** Its strength goes up or down for a while; a reduction to 0 destroys it (rule 12.0.0.2). */
    void changeStrength(CardSlot card, int amount, com.drive.drive_manager.simulator.engine.effects.model.Action.Until until);

    void grant(CardSlot card, String keyword, com.drive.drive_manager.simulator.engine.effects.model.Action.Until until);

    void destroy(CardSlot card, boolean sacrifice);

    /** The card the next actions act on: the one chosen before, or else this card. */
    CardSlot subjectOrSelf();

    /** The card of the triggering event, on the field. */
    CardSlot eventCardOnField();

    /** The card a relative selector compares with: self, subject, last or event. */
    CardSlot relativeTo(String to);

    int levelOf(CardSlot card);

    /** The creature this card clashes with. */
    CardSlot clashing();

    String selfId();

    /** The ally chosen before or, if a special summon replaced it, the creature that took its place. */
    CardSlot subjectOrSummoned();

    /** How many cards the last action acted on. */
    void counted(int count);

    /** Whether the action did something, for "si lo haces". */
    void done(boolean done);

    /** The card must be among those picked in the choice just asked. */
    void requireInChoice(CardSlot card);

    /** The card cannot be a target of this effect. */
    boolean immune(CardSlot card);

    /** The effect's card is in the Retaguardia: it cannot reach enemies. */
    boolean inRetaguardia();

    boolean inFrontLine(CardSlot card);

    List<CardSlot> zone(PlayerState player, String zone);

    int amount(Amount amount);

    String describe(Selector selector);

    String name(CardSlot card);

    /** Finds a card of either player by id, wherever it is. */
    CardSlot find(String instanceId);

    /** Pauses the effect until the player picks between {@code min} and {@code max} of {@code valid}. */
    void ask(String instruction, String chosenLabel, List<CardSlot> shown, List<CardSlot> valid, int min, int max);

    /** The cards saved under that name. */
    List<String> saved(String name);

    /** Adds them under that name; {@code Memory.SUBJECT} keeps the first one and {@code Memory.LAST} replaces what it had. */
    void save(String name, List<String> instanceIds);

    /** Records an event of the game's timeline, its message included. */
    void event(String type, CardSlot card, Map<String, Object> data, String message);
}
