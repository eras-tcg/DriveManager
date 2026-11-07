package com.drive.drive_manager.simulator.room;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PlayerState {
    private String userId;
    private String username;
    /** The player's icon (an image URL from their profile); null for none. */
    private String avatar;
    /** The player's playmat (an image URL from their profile), under their half of the table; null for none. */
    private String playmat;
    private boolean connected;
    /** When they lost their connection (0 while connected): the owner passes on after a while (a reload is not leaving). */
    private long disconnectedAt;

    private String deckId;
    /** Pre-room: the deck's name, the starter chosen (a card id of the deck, its first Iniciador by default) and ready. */
    private String deckName;
    private String starterCardId;
    private boolean ready;
    /**
     * "Efectos opcionales apagados": the engine does not offer this player's optional triggered effects (they lose
     * their moment); "En Juego" effects of the cards they play are still offered. The page remembers it on the device
     * and sets it on every game.
     */
    private boolean skipOptional;
    private int    resourceCount = 0;
    /** How many turns this player has started (their first turn is 1): the bot's edge counts by them. */
    private int    turnsStarted = 0;

    private List<CardSlot> hand         = new ArrayList<>();
    private List<CardSlot> deck         = new ArrayList<>();
    private List<CardSlot> originalDeck = new ArrayList<>();
    private List<CardSlot> lifeStack    = new ArrayList<>();
    private List<CardSlot> tributeZone  = new ArrayList<>();
    private List<CardSlot> discardPile  = new ArrayList<>();
    private List<CardSlot> field        = new ArrayList<>();

    public PlayerState(String userId, String username) {
        this.userId   = userId;
        this.username = username;
    }
}
