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
    private int strengthModifier = 0;
    private List<CardSlot> materials = new ArrayList<>();
    private List<CardSlot> resources = new ArrayList<>();
}
