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

    public GameLogEntry(long ts, String text) {
        this.ts = ts;
        this.text = text;
    }
}
