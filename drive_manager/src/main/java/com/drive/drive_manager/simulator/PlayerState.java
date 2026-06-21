package com.drive.drive_manager.simulator;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PlayerState {
    private String userId;
    private String username;
    private boolean connected;

    private String deckId;
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
