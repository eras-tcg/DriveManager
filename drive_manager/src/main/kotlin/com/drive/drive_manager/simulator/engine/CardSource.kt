package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.platform.Platform

/**
 * Where the core gets the programmed cards from (the registries of [CardEffects] are filled from it once, on first
 * use). The core does not read files: the server reads the card files with Jackson (`CardFiles`, `EffectJson`) and
 * offers them as a `CardSource` service (META-INF/services, found by [Platform.discover] on the JVM); another
 * platform, or a test, installs its own with [CardSources.install] before the engine is first used.
 */
fun interface CardSource {

    /**
     * Every programmed card. Throws IllegalStateException when one is broken (the engine does not run on a broken
     * card).
     */
    fun cards(): List<CardDefinition>
}

/** The [CardSource] in use: the one installed, else the one the platform offers, else none (no programmed cards). */
object CardSources {

    private var installed: CardSource? = null

    /** Uses `source` from now on; it only counts when installed before the engine reads the cards (first use). */
    @JvmStatic
    fun install(source: CardSource) {
        installed = source
    }

    /** The cards of the source in use (none without one). */
    @JvmStatic
    fun cards(): List<CardDefinition> =
        (installed ?: Platform.discover(CardSource::class).firstOrNull())?.cards() ?: frozenListOf()
}
