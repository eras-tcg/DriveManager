package com.drive.drive_manager.simulator;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PlayerState {
    private String userId;
    private String username;
    private boolean connected;
    /** When they lost their connection (0 while connected): the owner passes on after a while (a reload is not leaving). */
    @com.fasterxml.jackson.annotation.JsonIgnore
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
