package com.drive.drive_manager.simulator;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GameLogEntry {
    private long   ts;
    private String text;
    /** Timeline event this entry describes, when it comes from one. */
    private Long eventSeq;
    /** What it is: the timeline event's type, MANUAL (an action by hand) or null (a note of the rules). */
    private String kind;
    /** The card behind it: the effect's card for an effect's actions, else the card the action is about. */
    private String sourceId;
    private String sourceName;
    private String imageUrl;
    /** An inherited effect's Recurso (the card that gives it), as in the chaining choice. */
    private String inheritedFromName;
    private String inheritedFromImage;
    /** One of the steps of an effect (not its activation or resolution): hidden in the history's short view. */
    private boolean detail;

    public GameLogEntry(long ts, String text) {
        this.ts = ts;
        this.text = text;
    }

    public GameLogEntry(long ts, String text, Long eventSeq) {
        this.ts = ts;
        this.text = text;
        this.eventSeq = eventSeq;
    }
}
