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
}
