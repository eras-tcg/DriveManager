package com.drive.drive_manager.simulator.engine.effects.model;

/** The names an effect's actions save cards under and read them by. Any other name is the effect's own. */
public final class Memory {

    /** The targets chosen by the effect. */
    public static final String TARGETS = "targets";
    /** The one card the next actions act on ("dicho aliado", "el aliado elegido"). */
    public static final String SUBJECT = "subject";
    /** The creature the effect just summoned or placed ("el aliado invocado"). */
    public static final String SUMMONED = "summoned";
    /** The cards the last action picked or moved ("si tributaste…", "de nivel menor que la carta devuelta"). */
    public static final String LAST = "last";

    private Memory() {}
}
