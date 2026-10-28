package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** An effect in the chain: activated, waiting to resolve, or resolving step by step. */
@Data
public class PendingEffect {
    private String id;
    private String scriptId;
    private String controllerId;
    private String sourceInstanceId;
    private String sourceName;
    private String label;
    private String text;
    /** Timeline seq of the event that triggered it. */
    private long triggerSeq;

    // --- Resolution progress (set while it resolves) ---
    private String resolutionId;
    private int stepIndex;
    /** Cards taken out of the deck by "mira las primeras N", waiting for the next step. Owner-only info. */
    @JsonIgnore
    private List<CardSlot> looked = new ArrayList<>();
}
