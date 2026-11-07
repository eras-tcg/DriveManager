package com.drive.drive_manager.simulator.rank

import com.drive.drive_manager.simulator.bot.BotCatalog

/**
 * The ranked ladder's rules, apart from where ranks are kept (see RankService). Each player has a hidden strength
 * (MMR, Elo) and a visible rank: Hierro, Bronce, Plata, Oro, Platino, Diamante (filled with gems) and Retador (by
 * points).
 * - Hierro and Bronce: a win adds a gem, a loss takes nothing; full, you go up. You never go down.
 * - Plata and Oro: a loss takes a gem; at none, it uses up a shield (two on reaching the rank); without shield you
 *   go down.
 * - Platino and Diamante: a loss takes a gem; at none, you go down. Full, a promotion series: win two of three to
 *   go up (losing it leaves you with [AFTER_SERIES] gems).
 * - Retador: points, won or lost by how strong the rival is (MMR); under zero, back to Diamante.
 */
object RankRules {

    enum class Tier(
        @JvmField val label: String,
        /** Gems to fill to go up (none in Retador: points). */
        @JvmField val gems: Int,
    ) {
        IRON("Hierro", 3), BRONZE("Bronce", 3), SILVER("Plata", 4), GOLD("Oro", 4), PLATINUM("Platino", 5),
        DIAMOND("Diamante", 5), CHALLENGER("Retador", 0);

        fun up(): Tier {
            return values()[Math.min(ordinal + 1, values().size - 1)]
        }

        fun down(): Tier {
            return values()[Math.max(ordinal - 1, 0)]
        }

        /** Low ranks: losing costs nothing. */
        fun safe(): Boolean {
            return this == IRON || this == BRONZE
        }

        /** Middle ranks: a shield before going down. */
        fun shielded(): Boolean {
            return this == SILVER || this == GOLD
        }

        /** High ranks: a promotion series to go up. */
        fun series(): Boolean {
            return this == PLATINUM || this == DIAMOND
        }
    }

    const val START_MMR = 1000
    /** Shields on reaching a middle rank. */
    const val SHIELDS = 2
    /** Gems left after a lost series, and on coming down to a rank. */
    const val AFTER_SERIES = 3
    /** Wins (or losses) that end a series. */
    const val SERIES_TO = 2
    /** Games while the MMR moves faster (the first ones place the player). */
    const val PLACEMENT_GAMES = 10
    internal const val K_PLACEMENT = 40
    internal const val K = 24
    /** Retador's points for a game: between these, by how expected the result was. */
    internal const val MIN_POINTS = 10
    internal const val MAX_POINTS = 30
    /** The server's bot strength by level, for the MMR of a ranked game against it. */
    @JvmField val BOT_MMR: Map<String, Int> = java.util.Map.of(BotCatalog.EASY, 900, BotCatalog.NORMAL, 1100, BotCatalog.HARD, 1300)

    /**
     * A player's standing.
     *
     * @param seriesWins   wins of the promotion series in course (with seriesLosses; both 0 when none)
     * @param points       Retador's points
     */
    @JvmRecord
    data class Standing(
        val mmr: Int, val games: Int, val wins: Int, val losses: Int, val tier: Tier?, val gems: Int, val shields: Int,
        val inSeries: Boolean, val seriesWins: Int, val seriesLosses: Int, val points: Int, val peak: Tier?,
    ) {
        companion object {
            @JvmStatic
            fun start(): Standing {
                return Standing(START_MMR, 0, 0, 0, Tier.IRON, 0, 0, false, 0, 0, 0, Tier.IRON)
            }
        }
    }

    /** What a game changed: the standing before and after, and what to tell the player (in Spanish). */
    @JvmRecord
    data class Change(val before: Standing?, val after: Standing?, val mmrDelta: Int, val summary: String?)

    /** Elo's scale: a rival this many points stronger is expected to win 10 times out of 11. */
    internal const val ELO_SCALE = 400.0

    /** The chance of winning against a rival (Elo). */
    @JvmStatic
    fun expected(mmr: Int, rivalMmr: Int): Double {
        return 1.0 / (1.0 + Math.pow(10.0, (rivalMmr - mmr) / ELO_SCALE))
    }

    /** The standing after a ranked game against a rival of `rivalMmr`. */
    @JvmStatic
    fun after(s: Standing, won: Boolean, rivalMmr: Int): Change {
        val e = expected(s.mmr, rivalMmr)
        val k = if (s.games < PLACEMENT_GAMES) K_PLACEMENT else K
        val mmrDelta = Math.round(k * ((if (won) 1 else 0) - e)).toInt()
        val base = Standing(s.mmr + mmrDelta, s.games + 1, s.wins + (if (won) 1 else 0), s.losses + (if (won) 0 else 1),
                s.tier, s.gems, s.shields, s.inSeries, s.seriesWins, s.seriesLosses, s.points, s.peak)
        val step = if (s.tier == Tier.CHALLENGER) challenger(base, won, e) else if (won) win(base) else loss(base)
        var after = step.standing
        if (after.tier!!.ordinal > after.peak!!.ordinal) after = withPeak(after, after.tier)
        return Change(s, after, mmrDelta, step.summary)
    }

    private data class Step(val standing: Standing, val summary: String)

    private fun win(s: Standing): Step {
        val t = s.tier!!
        if (s.inSeries) {
            val w = s.seriesWins + 1
            if (w >= SERIES_TO) return promoted(s, t.up())
            return Step(series(s, true, w, s.seriesLosses), "Serie de ascenso: " + w + " de " + SERIES_TO + " victorias")
        }
        val newGems = s.gems + 1
        if (newGems < t.gems) return Step(gems(s, newGems, s.shields), "+1 gema")
        if (t.series()) return Step(series(s, true, 0, 0), "Gemas completas: empieza tu serie de ascenso (gana " + SERIES_TO + " de 3)")
        return promoted(s, t.up())
    }

    private fun loss(s: Standing): Step {
        val t = s.tier!!
        if (t.safe()) return Step(s, "Sin cambios: en " + t.label + " no se pierden gemas")
        if (s.inSeries) {
            val l = s.seriesLosses + 1
            if (l >= SERIES_TO) return Step(gems(series(s, false, 0, 0), AFTER_SERIES, s.shields), "Serie perdida: quedas con " + AFTER_SERIES + " gemas")
            return Step(series(s, true, s.seriesWins, l), "Serie de ascenso: " + l + " derrota" + (if (l == 1) "" else "s"))
        }
        if (s.gems > 0) return Step(gems(s, s.gems - 1, s.shields), "−1 gema")
        if (t.shielded() && s.shields > 0) {
            val left = s.shields - 1
            return Step(gems(s, 0, left), "Tu escudo te protege (" + (if (left == 1) "queda 1" else if (left == 0) "no quedan más" else "quedan " + left) + ")")
        }
        val down = t.down()
        return Step(entered(s, down, Math.min(AFTER_SERIES, down.gems - 1)), "Bajas a " + down.label)
    }

    private fun challenger(s: Standing, won: Boolean, expected: Double): Step {
        val delta = Math.round(MIN_POINTS + (MAX_POINTS - MIN_POINTS) * (if (won) 1 - expected else expected)).toInt()
        val points = s.points + (if (won) delta else -delta)
        if (points < 0) return Step(entered(s, Tier.DIAMOND, AFTER_SERIES), "Bajas a Diamante")
        val after = Standing(s.mmr, s.games, s.wins, s.losses, s.tier, 0, 0, false, 0, 0, points, s.peak)
        return Step(after, (if (won) "+" else "−") + delta + " puntos")
    }

    private fun promoted(s: Standing, up: Tier): Step {
        return Step(entered(s, up, 0), "¡Asciendes a " + up.label + "!")
    }

    /** The player reaches `tier` (up or down) with `gems`; a middle rank comes with its shields. */
    private fun entered(s: Standing, tier: Tier, gems: Int): Standing {
        return Standing(s.mmr, s.games, s.wins, s.losses, tier, if (tier == Tier.CHALLENGER) 0 else gems,
                if (tier.shielded()) SHIELDS else 0, false, 0, 0, 0, s.peak)
    }

    private fun gems(s: Standing, gems: Int, shields: Int): Standing {
        return Standing(s.mmr, s.games, s.wins, s.losses, s.tier, gems, shields, s.inSeries, s.seriesWins,
                s.seriesLosses, s.points, s.peak)
    }

    private fun series(s: Standing, `in`: Boolean, wins: Int, losses: Int): Standing {
        return Standing(s.mmr, s.games, s.wins, s.losses, s.tier, if (`in`) s.tier!!.gems else s.gems, s.shields, `in`,
                wins, losses, s.points, s.peak)
    }

    private fun withPeak(s: Standing, peak: Tier?): Standing {
        return Standing(s.mmr, s.games, s.wins, s.losses, s.tier, s.gems, s.shields, s.inSeries, s.seriesWins,
                s.seriesLosses, s.points, peak)
    }

    /** The tiers in order, as the web shows them. */
    @JvmStatic
    fun tiers(): List<Tier> {
        return java.util.List.of(*Tier.values())
    }
}
