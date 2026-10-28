package com.drive.drive_manager.simulator.engine;

import java.util.List;

/**
 * An effect the engine runs on its own: when it triggers, whether it is mandatory, and its steps.
 *
 * @param id        stable id, e.g. "carnhaus:en-invocacion"
 * @param moment    when it triggers (see {@link Moment})
 * @param mandatory triggered effects without "puedes" are mandatory (rule 11.7.0.3)
 * @param label     the moment as printed ("En Invocación")
 * @param text      the effect text, shown while it resolves
 */
public record EffectScript(String id, Moment moment, boolean mandatory, String label, String text, List<EffectStep> steps) {

    public enum Moment {
        /** "En Invocación": when this creature is summoned, by any method (rule 11.4.1.2b). */
        ON_SUMMON
    }
}
