package com.drive.drive_manager.simulator.rank;

import com.drive.drive_manager.simulator.bot.BotCatalog;
import java.util.List;

/**
 * The ranked ladder's rules, apart from where ranks are kept (see RankService). Each player has a hidden strength
 * (MMR, Elo) and a visible rank: Hierro, Bronce, Plata, Oro, Platino, Diamante (filled with gems) and Retador (by
 * points).
 * <ul>
 *   <li>Hierro and Bronce: a win adds a gem, a loss takes nothing; full, you go up. You never go down.</li>
 *   <li>Plata and Oro: a loss takes a gem; at none, it uses up a shield (two on reaching the rank); without shield you
 *   go down.</li>
 *   <li>Platino and Diamante: a loss takes a gem; at none, you go down. Full, a promotion series: win two of three to
 *   go up (losing it leaves you with {@link #AFTER_SERIES} gems).</li>
 *   <li>Retador: points, won or lost by how strong the rival is (MMR); under zero, back to Diamante.</li>
 * </ul>
 */
public final class RankRules {

    private RankRules() {}

    public enum Tier {
        IRON("Hierro", 3), BRONZE("Bronce", 3), SILVER("Plata", 4), GOLD("Oro", 4), PLATINUM("Platino", 5),
        DIAMOND("Diamante", 5), CHALLENGER("Retador", 0);

        public final String label;
        /** Gems to fill to go up (none in Retador: points). */
        public final int gems;

        Tier(String label, int gems) {
            this.label = label;
            this.gems = gems;
        }

        Tier up() {
            return values()[Math.min(ordinal() + 1, values().length - 1)];
        }

        Tier down() {
            return values()[Math.max(ordinal() - 1, 0)];
        }

        /** Low ranks: losing costs nothing. */
        boolean safe() {
            return this == IRON || this == BRONZE;
        }

        /** Middle ranks: a shield before going down. */
        boolean shielded() {
            return this == SILVER || this == GOLD;
        }

        /** High ranks: a promotion series to go up. */
        boolean series() {
            return this == PLATINUM || this == DIAMOND;
        }
    }

    public static final int START_MMR = 1000;
    /** Shields on reaching a middle rank. */
    public static final int SHIELDS = 2;
    /** Gems left after a lost series, and on coming down to a rank. */
    public static final int AFTER_SERIES = 3;
    /** Wins (or losses) that end a series. */
    public static final int SERIES_TO = 2;
    /** Games while the MMR moves faster (the first ones place the player). */
    public static final int PLACEMENT_GAMES = 10;
    static final int K_PLACEMENT = 40, K = 24;
    /** Retador's points for a game: between these, by how expected the result was. */
    static final int MIN_POINTS = 10, MAX_POINTS = 30;
    /** The server's bot strength by level, for the MMR of a ranked game against it. */
    public static final java.util.Map<String, Integer> BOT_MMR = java.util.Map.of(BotCatalog.EASY, 900, BotCatalog.NORMAL, 1100, BotCatalog.HARD, 1300);

    /**
     * A player's standing.
     *
     * @param seriesWins   wins of the promotion series in course (with seriesLosses; both 0 when none)
     * @param points       Retador's points
     */
    public record Standing(int mmr, int games, int wins, int losses, Tier tier, int gems, int shields, boolean inSeries,
                           int seriesWins, int seriesLosses, int points, Tier peak) {

        public static Standing start() {
            return new Standing(START_MMR, 0, 0, 0, Tier.IRON, 0, 0, false, 0, 0, 0, Tier.IRON);
        }
    }

    /** What a game changed: the standing before and after, and what to tell the player (in Spanish). */
    public record Change(Standing before, Standing after, int mmrDelta, String summary) {}

    /** Elo's scale: a rival this many points stronger is expected to win 10 times out of 11. */
    static final double ELO_SCALE = 400.0;

    /** The chance of winning against a rival (Elo). */
    static double expected(int mmr, int rivalMmr) {
        return 1.0 / (1.0 + Math.pow(10, (rivalMmr - mmr) / ELO_SCALE));
    }

    /** The standing after a ranked game against a rival of {@code rivalMmr}. */
    public static Change after(Standing s, boolean won, int rivalMmr) {
        double e = expected(s.mmr(), rivalMmr);
        int k = s.games() < PLACEMENT_GAMES ? K_PLACEMENT : K;
        int mmrDelta = (int) Math.round(k * ((won ? 1 : 0) - e));
        Standing base = new Standing(s.mmr() + mmrDelta, s.games() + 1, s.wins() + (won ? 1 : 0), s.losses() + (won ? 0 : 1),
                s.tier(), s.gems(), s.shields(), s.inSeries(), s.seriesWins(), s.seriesLosses(), s.points(), s.peak());
        Step step = s.tier() == Tier.CHALLENGER ? challenger(base, won, e) : won ? win(base) : loss(base);
        Standing after = step.standing();
        if (after.tier().ordinal() > after.peak().ordinal()) after = withPeak(after, after.tier());
        return new Change(s, after, mmrDelta, step.summary());
    }

    private record Step(Standing standing, String summary) {}

    private static Step win(Standing s) {
        Tier t = s.tier();
        if (s.inSeries()) {
            int w = s.seriesWins() + 1;
            if (w >= SERIES_TO) return promoted(s, t.up());
            return new Step(series(s, true, w, s.seriesLosses()), "Serie de ascenso: " + w + " de " + SERIES_TO + " victorias");
        }
        int gems = s.gems() + 1;
        if (gems < t.gems) return new Step(gems(s, gems, s.shields()), "+1 gema");
        if (t.series()) return new Step(series(s, true, 0, 0), "Gemas completas: empieza tu serie de ascenso (gana " + SERIES_TO + " de 3)");
        return promoted(s, t.up());
    }

    private static Step loss(Standing s) {
        Tier t = s.tier();
        if (t.safe()) return new Step(s, "Sin cambios: en " + t.label + " no se pierden gemas");
        if (s.inSeries()) {
            int l = s.seriesLosses() + 1;
            if (l >= SERIES_TO) return new Step(gems(series(s, false, 0, 0), AFTER_SERIES, s.shields()), "Serie perdida: quedas con " + AFTER_SERIES + " gemas");
            return new Step(series(s, true, s.seriesWins(), l), "Serie de ascenso: " + l + " derrota" + (l == 1 ? "" : "s"));
        }
        if (s.gems() > 0) return new Step(gems(s, s.gems() - 1, s.shields()), "−1 gema");
        if (t.shielded() && s.shields() > 0) {
            int left = s.shields() - 1;
            return new Step(gems(s, 0, left), "Tu escudo te protege (" + (left == 1 ? "queda 1" : left == 0 ? "no quedan más" : "quedan " + left) + ")");
        }
        Tier down = t.down();
        return new Step(entered(s, down, Math.min(AFTER_SERIES, down.gems - 1)), "Bajas a " + down.label);
    }

    private static Step challenger(Standing s, boolean won, double expected) {
        int delta = (int) Math.round(MIN_POINTS + (MAX_POINTS - MIN_POINTS) * (won ? 1 - expected : expected));
        int points = s.points() + (won ? delta : -delta);
        if (points < 0) return new Step(entered(s, Tier.DIAMOND, AFTER_SERIES), "Bajas a Diamante");
        Standing after = new Standing(s.mmr(), s.games(), s.wins(), s.losses(), s.tier(), 0, 0, false, 0, 0, points, s.peak());
        return new Step(after, (won ? "+" : "−") + delta + " puntos");
    }

    private static Step promoted(Standing s, Tier up) {
        return new Step(entered(s, up, 0), "¡Asciendes a " + up.label + "!");
    }

    /** The player reaches {@code tier} (up or down) with {@code gems}; a middle rank comes with its shields. */
    private static Standing entered(Standing s, Tier tier, int gems) {
        return new Standing(s.mmr(), s.games(), s.wins(), s.losses(), tier, tier == Tier.CHALLENGER ? 0 : gems,
                tier.shielded() ? SHIELDS : 0, false, 0, 0, 0, s.peak());
    }

    private static Standing gems(Standing s, int gems, int shields) {
        return new Standing(s.mmr(), s.games(), s.wins(), s.losses(), s.tier(), gems, shields, s.inSeries(), s.seriesWins(),
                s.seriesLosses(), s.points(), s.peak());
    }

    private static Standing series(Standing s, boolean in, int wins, int losses) {
        return new Standing(s.mmr(), s.games(), s.wins(), s.losses(), s.tier(), in ? s.tier().gems : s.gems(), s.shields(), in,
                wins, losses, s.points(), s.peak());
    }

    private static Standing withPeak(Standing s, Tier peak) {
        return new Standing(s.mmr(), s.games(), s.wins(), s.losses(), s.tier(), s.gems(), s.shields(), s.inSeries(), s.seriesWins(),
                s.seriesLosses(), s.points(), peak);
    }

    /** The tiers in order, as the web shows them. */
    public static List<Tier> tiers() {
        return List.of(Tier.values());
    }
}
