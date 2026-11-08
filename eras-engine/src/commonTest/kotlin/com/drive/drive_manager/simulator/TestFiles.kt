package com.drive.drive_manager.simulator

/**
 * Files the build hands the tests through environment variables (build.gradle.kts): `ERAS_CARD_BUNDLE`, the card bundle
 * the build made; `ERAS_DECKS`, the folder of the starter decks' metadata (drive_manager's test resources `simulator/`).
 */
expect object TestFiles {
    /** The text of the file named by the environment variable [variable], or of [child] inside that folder. */
    fun text(variable: String, child: String? = null): String
}
