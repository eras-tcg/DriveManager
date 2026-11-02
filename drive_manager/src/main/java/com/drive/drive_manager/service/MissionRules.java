package com.drive.drive_manager.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Daily and weekly missions: the catalog (what each one counts, its target and its gems, a week asking five times a
 * day's), and which ones a player gets. Each day a player gets DAILY_COUNT missions drawn at random from the catalog,
 * and each week WEEKLY_COUNT, the same all that day or week (drawn from the player's id and the period). Pure.
 */
public final class MissionRules {

    public static final int DAILY_COUNT = 3;
    public static final int WEEKLY_COUNT = 3;

    /** What a mission counts (what online games and logins report). */
    public static final String LOGIN = "LOGIN";
    public static final String GAME_PLAYED = "GAME_PLAYED";
    public static final String GAME_WON = "GAME_WON";
    public static final String SUMMON = "SUMMON";
    public static final String EFFECT_SUMMON = "EFFECT_SUMMON";
    public static final String SPECIAL_SUMMON = "SPECIAL_SUMMON";
    public static final String DAMAGE = "DAMAGE";

    /** A mission of the catalog: its id, what it counts, and a day's and a week's target and gems. */
    public record Mission(String id, String counts, int dailyTarget, int dailyGems, int weeklyTarget, int weeklyGems) {
        public int target(boolean weekly) {
            return weekly ? weeklyTarget : dailyTarget;
        }

        public int gems(boolean weekly) {
            return weekly ? weeklyGems : dailyGems;
        }
    }

    public static final List<Mission> CATALOG = List.of(
            new Mission("login", LOGIN, 1, 50, 5, 250),
            new Mission("play", GAME_PLAYED, 3, 100, 15, 400),
            new Mission("win", GAME_WON, 1, 100, 5, 400),
            new Mission("summon", SUMMON, 10, 75, 50, 300),
            new Mission("effect-summon", EFFECT_SUMMON, 5, 100, 25, 400),
            new Mission("special-summon", SPECIAL_SUMMON, 5, 100, 25, 400),
            new Mission("damage", DAMAGE, 500, 100, 2500, 400));

    private MissionRules() {
    }

    public static Mission byId(String id) {
        return CATALOG.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
    }

    /** The day's key (YYYY-MM-DD). */
    public static String dayKey(LocalDate day) {
        return day.toString();
    }

    /** The ISO week's key (YYYY-Www). */
    public static String weekKey(LocalDate day) {
        return String.format("%d-W%02d", day.get(IsoFields.WEEK_BASED_YEAR), day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /** The first day of the next week (Monday), when the weekly missions change. */
    public static LocalDate nextWeek(LocalDate day) {
        return day.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    }

    /** A player's missions of a period (its key): `count` different ones, always the same for that player and key. */
    public static List<Mission> drawn(String userId, String periodKey, int count) {
        List<Mission> pool = new ArrayList<>(CATALOG);
        Random random = new Random((userId + "|" + periodKey).hashCode());
        List<Mission> out = new ArrayList<>();
        while (out.size() < count && !pool.isEmpty()) out.add(pool.remove(random.nextInt(pool.size())));
        return out;
    }
}
