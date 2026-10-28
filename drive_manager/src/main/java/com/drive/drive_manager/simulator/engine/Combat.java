package com.drive.drive_manager.simulator.engine;

import lombok.Data;

/** A combat in progress (rule 10): attack, block, clash or direct damage, end. */
@Data
public class Combat {

    public enum Step { ATTACK, BLOCK, CLASH, DAMAGE, END }

    /** Within a step: entering it, its own action, leaving it. Each one opens a window for triggers (rule 10.1). */
    public enum Phase { ENTER, ACT, EXIT }

    private String attackerId;
    private String attackerOwnerId;
    private String defenderId;
    /** Exhausted enemy creature being attacked; null when attacking the opponent. */
    private String targetId;
    private String blockerId;
    private Step step = Step.ATTACK;
    private Phase phase = Phase.ACT;
    /** Declared by an effect (the attacker was not exhausted). */
    private boolean byEffect;
    private boolean provokeAsked;
    private boolean blockAsked;
    /** Direct damage still to deal (1 + Letalidad), counted down hit by hit; null until the damage step. */
    private Integer hitsLeft;
    /** Abilities gained "hasta el final del ataque" ("instanceId|keyword"), removed when the combat ends. */
    private java.util.List<String> untilEndOfAttack = new java.util.ArrayList<>();
    /** An enemy of the attacker was destroyed during this attack. */
    private boolean enemyDestroyed;
}
