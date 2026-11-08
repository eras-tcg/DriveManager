package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.rank.RankRules
import java.util.regex.Pattern

/**
 * The bots the server can seat: its levels (easy, normal, hard) and the models to try, read from the resources (the
 * trained value model, the tuned settings and the named models), with how each is named at the table and which
 * level matches a player's strength in ranked games.
 */
object BotCatalog {

    /** The bot's trained model, promoted from a training run (null: none yet, the hand-set weights score). */
    internal const val MODEL_RESOURCE = "/bot/value-model.json"
    private val MODEL: ValueModel? = ValueModelFiles.resource(MODEL_RESOURCE)
    /** How it thinks at the hard level, found by playing variants against each other (BotThinkRun); none: the default. */
    internal const val SETTINGS_RESOURCE = "/bot/settings.txt"
    private val SETTINGS: BotSettings = tunedSettings()

    /**
     * The levels the owner chooses from, each the best bot of a stage of its tuning (2026-11-06, one level up from
     * before; the site's first bot, one turn ahead, retired): easy, the first tuned one (two turns ahead); normal, the
     * champion of the tests; hard, the champion with the user's corrections from their games (guiado). Their settings are the
     * lines "easy", "normal" and "hard" of the models resource (without them: the defaults, the settings above, the
     * settings above).
     */
    const val EASY = "easy"
    const val NORMAL = "normal"
    const val HARD = "hard"
    private val LEVEL_IDS: List<String> = java.util.List.of(EASY, NORMAL, HARD)

    /**
     * The levels' bots and the bots to try out, by name: each line of the resource is "id|label|settings" (settings as
     * in BotSettings#with, over the defaults); # starts a comment. Lines other than the levels' are models,
     * offered apart from the levels.
     */
    internal const val MODELS_RESOURCE = "/bot/models.txt"
    private val MODELS: Map<String?, Map.Entry<String, BotSettings>> = models()
    /** Every choice the owner has, in order: the levels, then the named models (id, label, and kind "level" or "model"). */
    @JvmField
    val CHOICES: MutableList<MutableMap<String, String>> = choices()
    @JvmField
    internal val LEVELS: Set<String> = CHOICES.stream().map { c -> c["id"]!! }.collect(java.util.stream.Collectors.toUnmodifiableSet())

    private fun models(): Map<String?, Map.Entry<String, BotSettings>> {
        val m = java.util.LinkedHashMap<String?, Map.Entry<String, BotSettings>>()
        try {
            BotCatalog::class.java.getResourceAsStream(MODELS_RESOURCE).use { input ->
                if (input == null) return m
                for (line in Pattern.compile("\\R").split(String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))) {
                    if (line.all { Character.isWhitespace(it) } || line.trim { it <= ' ' }.startsWith("#")) continue
                    val f = Pattern.compile("\\|").split(line, 3)
                    m[f[0].trim { it <= ' ' }] = java.util.Map.entry(f[1].trim { it <= ' ' }, BotSettings.DEFAULT.with(f[2].trim { it <= ' ' }))
                }
            }
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Cannot read the bot's models $MODELS_RESOURCE", e)
        }
        return m
    }

    private fun choices(): MutableList<MutableMap<String, String>> {
        val c: MutableList<MutableMap<String, String>> = ArrayList(java.util.List.of(java.util.Map.of("id", EASY, "label", "Fácil", "kind", "level"),
                java.util.Map.of("id", NORMAL, "label", "Normal", "kind", "level"), java.util.Map.of("id", HARD, "label", "Difícil", "kind", "level")))
        MODELS.forEach { id, model -> if (!LEVEL_IDS.contains(id)) c.add(java.util.Map.of("id", id, "label", model.key, "kind", "model")) }
        return java.util.List.copyOf(c)
    }

    private fun tunedSettings(): BotSettings {
        try {
            BotCatalog::class.java.getResourceAsStream(SETTINGS_RESOURCE).use { input ->
                return if (input == null) BotSettings.DEFAULT
                else BotSettings.DEFAULT.with(String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim { it <= ' ' })
            }
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Cannot read the bot's settings $SETTINGS_RESOURCE", e)
        }
    }

    /** The bot's name at the table: its level (or the model it tries), so it reads as a rival of its own. */
    @JvmStatic
    fun nameOf(level: String?): String {
        if (level != null && !LEVEL_IDS.contains(level) && MODELS.containsKey(level)) return "Bot (" + MODELS[level]!!.key + ")"
        return when (level ?: NORMAL) {
            EASY -> "Bot Fácil"
            HARD -> "Bot Difícil"
            else -> "Bot Normal"
        }
    }

    /** Ranked: the level that matches a player's strength (MMR), by the strength of each level (RankRules.BOT_MMR). */
    @JvmStatic
    fun levelFor(mmr: Int): String {
        // Halfway between the strengths of two levels
        val easyNormal = (RankRules.BOT_MMR[EASY]!! + RankRules.BOT_MMR[NORMAL]!!) / 2
        val normalHard = (RankRules.BOT_MMR[NORMAL]!! + RankRules.BOT_MMR[HARD]!!) / 2
        if (mmr < easyNormal) return EASY
        return if (mmr < normalHard) NORMAL else HARD
    }

    /** The bot of a level. With a trained model in the resources, the look-ahead ones score the tables with it. */
    @JvmStatic
    fun botAt(level: String?): BotPlayer {
        if (MODELS.containsKey(level)) return LookaheadBot(MODELS[level]!!.value, MODEL)
        return when (level!!) {
            HARD, NORMAL -> LookaheadBot(SETTINGS, MODEL)
            else -> LookaheadBot(BotSettings.DEFAULT, MODEL)
        }
    }
}
