package com.drive.drive_manager.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Progression missions, done once per account: the "Bienvenida" chain (steps claimed in order, the last one for
 * finishing them all) and the milestones (lifetime counts in tiers, each tier claimed once, then the next one). What
 * they count comes from Facts: lifetime counters (games, wins, summons, damage, login days, packs, decks saved, icons
 * set), the pages visited and what the account already has (a structure deck, cards). Pure.
 */
public final class ProgressionRules {

    /** Lifetime counters beyond the missions' ones (MissionRules: LOGIN, GAME_PLAYED, GAME_WON, SUMMON, DAMAGE…). */
    public static final String PACK_OPENED = "PACK_OPENED";
    /** A deck created in the builder, and an icon uploaded: their steps ask for the act, not for what the account has. */
    public static final String DECK_SAVED = "DECK_SAVED";
    public static final String AVATAR_SET = "AVATAR_SET";
    /**
     * A game against the bot (Modo solo on the server, or offline and accepted) that counted as GAME_PLAYED: the games
     * and wins count for the missions and the milestones whoever the rival was, but the "Tu primera partida" step asks
     * for a game against another player ("Termina una partida online contra otro jugador"), so it counts GAME_PLAYED
     * less these. Games against the bot counted nothing before this counter existed, so the difference is exact.
     */
    public static final String BOT_GAME_PLAYED = "BOT_GAME_PLAYED";

    /** Pages whose visit counts (the web reports them). */
    public static final String PAGE_HOW_TO_PLAY = "como-jugar";
    public static final String PAGE_CARDS = "cartas";
    public static final Set<String> PAGES = Set.of(PAGE_HOW_TO_PLAY, PAGE_CARDS);

    /** The welcome step that gives a structure deck, and the gems it gives instead to whoever already has one. */
    public static final String STRUCTURE_STEP = "structure";
    public static final int STRUCTURE_STEP_GEMS = WalletRules.STRUCTURE_PRICE;
    /** The last welcome step (every other one claimed) and the badge it gives. */
    public static final String GRADUATE_STEP = "graduate";
    public static final String GRADUATE_BADGE = "recluta";

    /** What an account has done and has, as progression reads it. */
    public record Facts(Map<String, Integer> counts, Set<String> visited, boolean hasStructure, int distinctCards) {
        public int count(String counts) {
            Integer n = this.counts.get(counts);
            return n == null ? 0 : n;
        }
    }

    /** A welcome step: its id, its target, its gems and how far an account is. */
    public record Step(String id, int target, int gems, ToIntFunction<Facts> progress) {
    }

    /** A milestone: its id, its tiers' targets and gems (same length) and how far an account is. */
    public record Milestone(String id, int[] targets, int[] gems, ToIntFunction<Facts> progress) {
    }

    public static final List<Step> WELCOME = List.of(
            new Step("welcome", 1, 100, f -> 1),
            new Step(STRUCTURE_STEP, 1, 0, f -> f.hasStructure() ? 1 : 0),
            new Step("how-to-play", 1, 100, f -> f.visited().contains(PAGE_HOW_TO_PLAY) ? 1 : 0),
            new Step("cards", 1, 50, f -> f.visited().contains(PAGE_CARDS) ? 1 : 0),
            new Step("deck", 1, 150, f -> f.count(DECK_SAVED)),
            new Step("pack", 1, 100, f -> f.count(PACK_OPENED)),
            new Step("avatar", 1, 100, f -> f.count(AVATAR_SET)),
            new Step("first-game", 1, 200, ProgressionRules::gamesAgainstPlayers),
            new Step("first-win", 1, 300, f -> f.count(MissionRules.GAME_WON)),
            new Step(GRADUATE_STEP, 9, 500, f -> 0));

    public static final List<Milestone> MILESTONES = List.of(
            new Milestone("games", new int[]{1, 10, 25, 50, 100}, new int[]{100, 250, 500, 1000, 2000}, f -> f.count(MissionRules.GAME_PLAYED)),
            new Milestone("wins", new int[]{1, 5, 25, 50, 100}, new int[]{100, 250, 500, 1000, 2000}, f -> f.count(MissionRules.GAME_WON)),
            new Milestone("summons", new int[]{25, 100, 500, 1000}, new int[]{100, 250, 500, 1000}, f -> f.count(MissionRules.SUMMON)),
            new Milestone("damage", new int[]{1000, 10000, 50000, 100000}, new int[]{100, 250, 500, 1000}, f -> f.count(MissionRules.DAMAGE)),
            new Milestone("login-days", new int[]{3, 7, 30, 100}, new int[]{100, 250, 500, 1000}, f -> f.count(MissionRules.LOGIN)),
            new Milestone("collector", new int[]{50, 150, 300}, new int[]{250, 500, 1000}, Facts::distinctCards),
            new Milestone("packs", new int[]{10, 50, 100}, new int[]{250, 500, 1000}, f -> f.count(PACK_OPENED)));

    private ProgressionRules() {
    }

    /** The games played against other players (every game played less those against the bot). */
    static int gamesAgainstPlayers(Facts f) {
        return Math.max(0, f.count(MissionRules.GAME_PLAYED) - f.count(BOT_GAME_PLAYED));
    }

    public static Step step(String id) {
        return WELCOME.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    public static Milestone milestone(String id) {
        return MILESTONES.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
    }

    /** How far an account is on a welcome step (the last one counts the steps claimed before it). */
    public static int progress(Step step, Facts facts, Set<String> claimed) {
        if (step.id().equals(GRADUATE_STEP)) return (int) WELCOME.stream().filter(s -> !s.id().equals(GRADUATE_STEP) && claimed.contains(s.id())).count();
        return step.progress().applyAsInt(facts);
    }

    /** A welcome step is open once every step before it was claimed. */
    public static boolean unlocked(Step step, Set<String> claimed) {
        for (Step s : WELCOME) {
            if (s == step) return true;
            if (!claimed.contains(s.id())) return false;
        }
        return false;
    }

    /**
     * Why a welcome step cannot be claimed now, or null when it can. The structure step can always be claimed once open:
     * whoever has no structure deck yet picks one as its reward.
     */
    public static String refusal(Step step, Facts facts, Set<String> claimed) {
        if (step == null) return "Esa misión no existe";
        if (claimed.contains(step.id())) return "Ya reclamaste esa misión";
        if (!unlocked(step, claimed)) return "Primero completa las misiones anteriores";
        if (!step.id().equals(STRUCTURE_STEP) && progress(step, facts, claimed) < step.target()) return "Esa misión todavía no está cumplida";
        return null;
    }

    /** Why a milestone's next tier (after `tiersClaimed`) cannot be claimed now, or null when it can. */
    public static String refusal(Milestone milestone, Facts facts, int tiersClaimed) {
        if (milestone == null) return "Esa misión no existe";
        if (tiersClaimed >= milestone.targets().length) return "Ya completaste todos los niveles de esa misión";
        if (milestone.progress().applyAsInt(facts) < milestone.targets()[tiersClaimed]) return "Ese nivel todavía no está cumplido";
        return null;
    }

    /** A welcome step as the web shows it. */
    public record StepView(String id, int progress, int target, int gems, boolean claimed, boolean locked) {
    }

    /** A milestone as the web shows it: tiers claimed of how many, and the next tier's target and gems (the last one's when all are claimed). */
    public record MilestoneView(String id, int tier, int tiers, int progress, int target, int gems, boolean maxed) {
    }

    public static List<StepView> welcome(Facts facts, Set<String> claimed) {
        List<StepView> out = new ArrayList<>();
        for (Step s : WELCOME) {
            int gems = s.id().equals(STRUCTURE_STEP) && facts.hasStructure() ? STRUCTURE_STEP_GEMS : s.gems();
            out.add(new StepView(s.id(), Math.min(progress(s, facts, claimed), s.target()), s.target(), gems,
                    claimed.contains(s.id()), !unlocked(s, claimed)));
        }
        return out;
    }

    public static List<MilestoneView> milestones(Facts facts, Map<String, Integer> tiersClaimed) {
        List<MilestoneView> out = new ArrayList<>();
        for (Milestone m : MILESTONES) {
            int tiers = m.targets().length;
            int tier = Math.min(tiersClaimed.getOrDefault(m.id(), 0), tiers);
            int next = Math.min(tier, tiers - 1);
            out.add(new MilestoneView(m.id(), tier, tiers, Math.min(m.progress().applyAsInt(facts), m.targets()[next]),
                    m.targets()[next], m.gems()[next], tier >= tiers));
        }
        return out;
    }
}
