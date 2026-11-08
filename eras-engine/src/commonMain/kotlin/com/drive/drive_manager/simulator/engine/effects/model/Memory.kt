package com.drive.drive_manager.simulator.engine.effects.model

/** The names an effect's actions save cards under and read them by. Any other name is the effect's own. */
object Memory {

    /** The targets chosen by the effect. */
    const val TARGETS = "targets"
    /** The one card the next actions act on ("dicho aliado", "el aliado elegido"). */
    const val SUBJECT = "subject"
    /** The creature the effect just summoned or placed ("el aliado invocado"). */
    const val SUMMONED = "summoned"
    /** The cards the last action picked or moved ("si tributaste…", "de nivel menor que la carta devuelta"). */
    const val LAST = "last"
    /** Of what was last chosen, the active allies at that moment: "si era un aliado activo". */
    const val ACTIVE_ALLIES = "activeAllies"
}
