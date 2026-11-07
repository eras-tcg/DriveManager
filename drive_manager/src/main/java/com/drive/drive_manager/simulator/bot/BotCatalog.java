package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.rank.RankRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The bots the server can seat: its levels (easy, normal, hard) and the models to try, read from the resources (the
 * trained value model, the tuned settings and the named models), with how each is named at the table and which
 * level matches a player's strength in ranked games.
 */
public final class BotCatalog {

    private BotCatalog() {}

    /** The bot's trained model, promoted from a training run (null: none yet, the hand-set weights score). */
    static final String MODEL_RESOURCE = "/bot/value-model.json";
    private static final ValueModel MODEL = ValueModelFiles.resource(MODEL_RESOURCE);
    /** How it thinks at the hard level, found by playing variants against each other (BotThinkRun); none: the default. */
    static final String SETTINGS_RESOURCE = "/bot/settings.txt";
    private static final BotSettings SETTINGS = tunedSettings();

    /**
     * The levels the owner chooses from, each the best bot of a stage of its tuning (2026-11-06, one level up from
     * before; the site's first bot, one turn ahead, retired): easy, the first tuned one (two turns ahead); normal, the
     * champion of the tests; hard, the champion with the user's corrections from their games (guiado). Their settings are the
     * lines "easy", "normal" and "hard" of the models resource (without them: the defaults, the settings above, the
     * settings above).
     */
    public static final String EASY = "easy", NORMAL = "normal", HARD = "hard";
    private static final List<String> LEVEL_IDS = List.of(EASY, NORMAL, HARD);

    /**
     * The levels' bots and the bots to try out, by name: each line of the resource is "id|label|settings" (settings as
     * in BotSettings#with, over the defaults); # starts a comment. Lines other than the levels' are models,
     * offered apart from the levels.
     */
    static final String MODELS_RESOURCE = "/bot/models.txt";
    private static final Map<String, Map.Entry<String, BotSettings>> MODELS = models();
    /** Every choice the owner has, in order: the levels, then the named models (id, label, and kind "level" or "model"). */
    public static final List<Map<String, String>> CHOICES = choices();
    static final Set<String> LEVELS = CHOICES.stream().map(c -> c.get("id")).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private static Map<String, Map.Entry<String, BotSettings>> models() {
        Map<String, Map.Entry<String, BotSettings>> m = new java.util.LinkedHashMap<>();
        try (java.io.InputStream in = BotCatalog.class.getResourceAsStream(MODELS_RESOURCE)) {
            if (in == null) return m;
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.trim().startsWith("#")) continue;
                String[] f = line.split("\\|", 3);
                m.put(f[0].trim(), Map.entry(f[1].trim(), BotSettings.DEFAULT.with(f[2].trim())));
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read the bot's models " + MODELS_RESOURCE, e);
        }
        return m;
    }

    private static List<Map<String, String>> choices() {
        List<Map<String, String>> c = new ArrayList<>(List.of(Map.of("id", EASY, "label", "Fácil", "kind", "level"),
                Map.of("id", NORMAL, "label", "Normal", "kind", "level"), Map.of("id", HARD, "label", "Difícil", "kind", "level")));
        MODELS.forEach((id, model) -> { if (!LEVEL_IDS.contains(id)) c.add(Map.of("id", id, "label", model.getKey(), "kind", "model")); });
        return List.copyOf(c);
    }

    private static BotSettings tunedSettings() {
        try (java.io.InputStream in = BotCatalog.class.getResourceAsStream(SETTINGS_RESOURCE)) {
            return in == null ? BotSettings.DEFAULT
                    : BotSettings.DEFAULT.with(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read the bot's settings " + SETTINGS_RESOURCE, e);
        }
    }

    /** The bot's name at the table: its level (or the model it tries), so it reads as a rival of its own. */
    public static String nameOf(String level) {
        if (level != null && !LEVEL_IDS.contains(level) && MODELS.containsKey(level)) return "Bot (" + MODELS.get(level).getKey() + ")";
        return switch (level == null ? NORMAL : level) {
            case EASY -> "Bot Fácil";
            case HARD -> "Bot Difícil";
            default -> "Bot Normal";
        };
    }

    /** Ranked: the level that matches a player's strength (MMR), by the strength of each level (RankRules.BOT_MMR). */
    public static String levelFor(int mmr) {
        // Halfway between the strengths of two levels
        int easyNormal = (RankRules.BOT_MMR.get(EASY) + RankRules.BOT_MMR.get(NORMAL)) / 2;
        int normalHard = (RankRules.BOT_MMR.get(NORMAL) + RankRules.BOT_MMR.get(HARD)) / 2;
        if (mmr < easyNormal) return EASY;
        return mmr < normalHard ? NORMAL : HARD;
    }

    /** The bot of a level. With a trained model in the resources, the look-ahead ones score the tables with it. */
    static BotPlayer botAt(String level) {
        if (MODELS.containsKey(level)) return new LookaheadBot(MODELS.get(level).getValue(), MODEL);
        return switch (level) {
            case HARD, NORMAL -> new LookaheadBot(SETTINGS, MODEL);
            default -> new LookaheadBot(BotSettings.DEFAULT, MODEL);
        };
    }
}
