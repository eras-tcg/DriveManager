package com.drive.drive_manager.simulator.engine;

import lombok.Data;

/** A combat in progress (rule 10): attack, block, clash or direct damage, end. */
@Data
public class Combat {

    public enum Step { ATTACK, BLOCK, CLASH, DAMAGE, END }

    private String attackerId;
    private String attackerOwnerId;
    private String defenderId;
    /** Exhausted enemy creature being attacked; null when attacking the opponent. */
    private String targetId;
    private String blockerId;
    private Step step = Step.ATTACK;
    /** Declared by an effect (the attacker was not exhausted). */
    private boolean byEffect;
    private boolean provokeAsked;
    private boolean blockAsked;
}
