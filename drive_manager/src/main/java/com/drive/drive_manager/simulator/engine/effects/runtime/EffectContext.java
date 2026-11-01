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
