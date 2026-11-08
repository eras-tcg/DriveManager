package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.platform.Platform

/**
 * What the bot reads from the resources shipped with it ([BotCatalog]: `/bot/models.txt`, `/bot/settings.txt` and the
 * trained `/bot/value-model.json`). The core does not read files: the server answers from its class path, the model
 * with Jackson (`ValueModelFiles`), as a `BotResourceSource` service (META-INF/services, found by [Platform.discover]
 * on the JVM); another platform, or a test, installs its own with [BotResourceSources.install] before the bot is first
 * used.
 */
interface BotResourceSource {

    /**
     * The text (UTF-8) of the resource at `path`, or null when there is none. A resource that cannot be read throws
     * IllegalStateException with `failure` as its message.
     */
    fun text(path: String, failure: String): String?

    /** The value model saved at `path`, or null when there is none; IllegalStateException when it cannot be read. */
    fun valueModel(path: String): ValueModel?
}

/** The [BotResourceSource] in use: the one installed, else the one the platform offers, else [NONE]. */
object BotResourceSources {

    /** No resources: the bot plays with its defaults (no trained model, the default settings, no named models). */
    @JvmField
    val NONE: BotResourceSource = object : BotResourceSource {
        override fun text(path: String, failure: String): String? = null
        override fun valueModel(path: String): ValueModel? = null
    }

    private var installed: BotResourceSource? = null

    /** Uses `source` from now on; it only counts when installed before the bot reads its resources (first use). */
    @JvmStatic
    fun install(source: BotResourceSource) {
        installed = source
    }

    /** The source in use. */
    @JvmStatic
    fun current(): BotResourceSource = installed ?: Platform.discover(BotResourceSource::class).firstOrNull() ?: NONE
}
