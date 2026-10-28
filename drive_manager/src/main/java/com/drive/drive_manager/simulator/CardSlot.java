package com.drive.drive_manager.simulator;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CardSlot {
    private String instanceId;
    private String cardId;
    private String imageUrl;
    private boolean faceDown;
    private boolean tapped;
    private Double x;
    private Double y;
    private String cardName;
    private String cardType;
    private Integer strength;
    private String specialSummonKind;
    private boolean starter;
    private int strengthModifier = 0;
    /** Printed properties used by the rules engine (null when the card has no metadata). */
    private Integer cost;
    private Integer level;
    /** Color identity plus extra colors (B, G, P, R, W). */
    private List<String> colors = new ArrayList<>();
    private List<String> classes = new ArrayList<>();
    private List<CardSlot> materials = new ArrayList<>();
    private List<CardSlot> resources = new ArrayList<>();
}
