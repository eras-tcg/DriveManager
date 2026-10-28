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
    /** Its link in the chain: 1 for the first one activated (resolves last). */
    private int link;

    /** Cost steps already paid: the cost is paid right after it is activated, choices included (rule 11.2.0.2). */
    private int costIndex;
    /** Targets chosen when it was activated ("elige 1 enemigo …;"), acted on when it resolves (rule 11.10.1). */
    private List<String> targetIds = new ArrayList<>();

    // --- Resolution progress (set while it resolves) ---
    private String resolutionId;
    private int stepIndex;
    /** The ally chosen by "elige 1 aliado", which the next steps act on. */
    private String subjectId;
    /** Ritual by effect: the creature chosen to be summoned, before choosing who meets its Requerimiento. */
    private String ritualTargetId;
    /** Ritual by effect with a level sum: the summoner, chosen before the other creatures used. */
    private String ritualSummonerId;
    /** Cards taken out of the deck by "mira las primeras N", waiting for the next step. Owner-only info. */
    @JsonIgnore
    private List<CardSlot> looked = new ArrayList<>();
    /** Its controller already chose it to activate next among simultaneous triggers (rule 11.7.0.5). */
    @JsonIgnore
    private boolean ordered;
    /** An optional effect its controller already chose to activate (in the combined order choice). */
    @JsonIgnore
    private boolean confirmed;
    /** Players who passed priority while it was the last effect in the chain (rule 11.3.0.3). */
    @JsonIgnore
    private java.util.Set<String> passed = new java.util.HashSet<>();
    /** The chain it belongs to was closed: both players passed (rule 11.3.0.5). */
    @JsonIgnore
    private boolean priorityDone;
}
