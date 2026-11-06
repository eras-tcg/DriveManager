package com.drive.drive_manager.simulator.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Improves the bot one accepted change at a time (a ratchet): its strength against where it started can only grow.
 * Off in the normal build; run it with {@code local-dev/bot-ratchet.sh}.
 * <p>
 * Each iteration nudges one or two knobs of the champion at random (a candidate) and plays it against the champion
 * in <b>paired games</b>: each deal (a seed, a pair of decks) is played twice, with the bots' seats swapped, so the
 * luck of the deal cancels out. A sequential test (SPRT, H0: wins 50 %, H1: wins {@code p1}) stops as soon as it is
 * sure enough either way, after every batch: bad candidates are dropped after a few games, a candidate is accepted
 * only when the test says it wins about {@code p1} of its games. Rejected also when the games run out undecided.
 * The size of the nudges grows after an accepted one and shrinks after a rejected one.
 * <p>
 * Every iteration the champion also plays the starting bot a little (paired games), adding up while it stays the
 * champion: its edge over the start, measured more precisely each time. At the end a long paired match against the
 * start decides whether the champion is better with 95 % confidence.
 * <p>
 * While tuning the bots think every candidate move with no time limit, so a game depends on its seed only. Kept in
 * {@code <out>/<date>_<label>/}: {@code run.json}, {@code iterations.jsonl}, {@code games.jsonl}, {@code champion.txt}
 * (and {@code champion-vN.txt} for each accepted change), {@code final.json}.
 */
class BotRatchetRun {

    static final ObjectMapper JSON =new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    private static final String NO_TIME_LIMIT = "budgetMs=1000000";

    /** A knob: its step (unit), its range, and whether it is an on/off flag or a whole number. */
    record Knob(String name, double unit, double min, double max, char kind) {}

    /** The heuristic's rules (they make the big decisions) and the table's main weights. */
    static final List<Knob> KNOBS = List.of(
            new Knob("hMullCost", 1, 1, 9, 'i'),
            new Knob("hMullKeep", 1, 0, 5, 'i'),
            new Knob("hAttackFirst", 1, 0, 1, 'f'),
            new Knob("hPreyMargin", 1, -3, 3, 'r'),
            new Knob("hDirectMargin", 1, -4, 4, 'r'),
            new Knob("hEvenTrade", 0.25, 0, 3, 'r'),
            new Knob("hHold", 1, 0, 4, 'i'),
            new Knob("hActivate", 1, 0, 1, 'f'),
            new Knob("hBlockEven", 0.25, 0, 3, 'r'),
            new Knob("hChumpLives", 1, -1, 4, 'i'),
            new Knob("hAdvanceMinStr", 1, 0, 6, 'i'),
            new Knob("hAssault", 1, 0, 1, 'f'),
            new Knob("hRespond", 1, 0, 1, 'f'),
            new Knob("hOptional", 1, 0, 1, 'f'),
            new Knob("life", 2, 1, 40, 'r'),
            new Knob("hand", 0.5, 0, 6, 'r'),
            new Knob("guard", 0.15, 0, 2, 'r'),
            new Knob("exposed", 0.15, 0, 2, 'r'),
            new Knob("support", 0.2, 0, 3, 'r'),
            new Knob("structure", 0.2, 0, 3, 'r'),
            new Knob("lowDeck", 3, 0, 40, 'r'),
            new Knob("zHand", 0.1, -0.5, 1.5, 'r'),
            new Knob("gems", 0.2, -2, 3, 'r'),
            new Knob("aggression", 0.1, 0.3, 2.5, 'r'),
            new Knob("vStr", 0.15, 0, 3, 'r'),
            new Knob("vKeyword", 0.15, -1, 3, 'r'),
            new Knob("vLevel", 0.1, -1, 2, 'r'));

    @Test
    void ratchet() throws Exception {
        assumeTrue(Boolean.getBoolean("bot.ratchet"), "the ratchet runs only with -Dbot.ratchet=true");
        // Who plays the games: the heuristic bot (fast: its rules are the "h" knobs) or the look-ahead one (slow, the server's)
        String agent = System.getProperty("bot.ratchet.bot", "heuristic");
        boolean fast = "heuristic".equals(agent);
        int iterations = Integer.getInteger("bot.ratchet.iterations", fast ? 300 : 60);
        int threads = Integer.getInteger("bot.ratchet.threads", Runtime.getRuntime().availableProcessors());
        int maxGames = Integer.getInteger("bot.ratchet.maxGames", fast ? 4096 : 2048);
        int seedsPerBatch = Integer.getInteger("bot.ratchet.seedsPerBatch", fast ? 4 : 2);
        int originSeeds = Integer.getInteger("bot.ratchet.originSeeds", fast ? 4 : 1);
        int finalSeeds = Integer.getInteger("bot.ratchet.finalSeeds", fast ? 64 : 16);
        // The champion against the start once more with the look-ahead bot (the server's), when the agent was the heuristic
        int validateSeeds = Integer.getInteger("bot.ratchet.validateSeeds", fast ? 8 : 0);
        int changes = Integer.getInteger("bot.ratchet.changes", 1);
        // One knob at a time moves the score by a point or two: with cheap games the test looks for small, real gains
        double p1 = Double.parseDouble(System.getProperty("bot.ratchet.p1", "0.52"));
        double alpha = Double.parseDouble(System.getProperty("bot.ratchet.alpha", "0.05"));
        // No candidate is accepted before this many games, and the test never trusts a variance below this floor:
        // with few deals that tell the bots apart the variance seen is low by chance, and the test would be too sure
        int minAccept = Integer.getInteger("bot.ratchet.minAcceptGames", 1024);
        double varianceFloor = Double.parseDouble(System.getProperty("bot.ratchet.varianceFloor", "0.02"));
        // A new champion that, after this many games against the start, does clearly worse than the one it replaced goes back
        int revertGames = Integer.getInteger("bot.ratchet.revertGames", 512);
        double beta = Double.parseDouble(System.getProperty("bot.ratchet.beta", "0.1"));
        long seed = Long.getLong("bot.ratchet.seed", System.currentTimeMillis());
        List<Knob> knobs = picked(System.getProperty("bot.ratchet.knobs", ""));
        // The heuristic bot plays by its rules only: the table's weights would change nothing
        if (fast && System.getProperty("bot.ratchet.knobs", "").isBlank()) knobs = knobs.stream().filter(k -> k.name().matches("h[A-Z].*")).toList();
        // Blocks: the table's weights one at a time, and every card bias of one deck at once (the rules stay as they are)
        boolean blocks = Boolean.getBoolean("bot.ratchet.blocks");
        Map<String, List<Knob>> groups = new LinkedHashMap<>();
        if (blocks) {
            List<Knob> weights = KNOBS.stream().filter(k -> !k.name().matches("h[A-Z].*")).toList();
            for (Knob k : weights) groups.put(k.name(), List.of(k));
            Map<String, Double> seen = exposure(Path.of(System.getProperty("bot.ratchet.out", "target/bot-runs")).resolve("exposure.csv"));
            double minExposure = Double.parseDouble(System.getProperty("bot.ratchet.minExposure", "0.01"));
            Map<String, String> deckOf = BotMatch.deckOfCards();
            for (String deck : BotMatch.DECKS) {
                List<Knob> block = new ArrayList<>();
                for (String card : BotMatch.cardNames()) {
                    if (!deck.equals(deckOf.get(card))) continue;
                    for (String prefix : List.of(BotSettings.BIAS, BotSettings.BIAS_TRIBUTE, BotSettings.BIAS_DISCARD)) {
                        if (seen.getOrDefault(prefix + card, 0.0) >= minExposure) block.add(new Knob(prefix + card, 1, -6, 10, 'r'));
                    }
                }
                if (!block.isEmpty()) groups.put("biases " + deck, block);
            }
            List<Knob> all = new ArrayList<>(weights);
            groups.values().stream().filter(g -> g.size() > 1).forEach(all::addAll);
            knobs = all;
        }
        // Guided: every continuous knob moves in each candidate, along the direction learned from all the tests so far
        // (accepted or not) plus a random exploration; see the candidate below
        boolean guided = Boolean.getBoolean("bot.ratchet.guided");
        if (guided) {
            List<Knob> all = new ArrayList<>(KNOBS.stream().filter(k -> k.kind() != 'f').toList());
            Map<String, Double> seen = exposure(Path.of(System.getProperty("bot.ratchet.out", "target/bot-runs")).resolve("exposure.csv"));
            double minExposure = Double.parseDouble(System.getProperty("bot.ratchet.minExposure", "0.01"));
            for (String card : BotMatch.cardNames()) {
                for (String prefix : List.of(BotSettings.BIAS, BotSettings.BIAS_TRIBUTE, BotSettings.BIAS_DISCARD)) {
                    if (seen.getOrDefault(prefix + card, 0.0) >= minExposure) all.add(new Knob(prefix + card, 1, -6, 10, 'r'));
                }
            }
            knobs = all;
        }
        // The learned direction, per knob in steps of that knob; how far a candidate goes along it, and how much it explores
        double[] direction = new double[knobs.size()];
        double step = Double.parseDouble(System.getProperty("bot.ratchet.step", "0.5"));
        double explore = Double.parseDouble(System.getProperty("bot.ratchet.explore", "0.5"));
        double momentum = Double.parseDouble(System.getProperty("bot.ratchet.momentum", "0.8"));
        // The look-ahead bot thinks less while tuning (both sides alike), so its games are affordable
        String tuning = System.getProperty("bot.ratchet.lookaheadTuning", "samples=1,maxCandidates=8");
        String play = "lookahead".equals(agent) ? NO_TIME_LIMIT + "," + tuning : NO_TIME_LIMIT;
        String startFile = System.getProperty("bot.ratchet.startFile", "");
        BotSettings origin = BotSettings.DEFAULT;
        BotSettings champion = startFile.isBlank() ? origin
                : origin.with(Files.readString(Path.of(startFile), StandardCharsets.UTF_8).trim());
        List<String> decks = BotMatch.DECKS;

        String label = System.getProperty("bot.ratchet.label", "ratchet").replaceAll("[^A-Za-z0-9._-]", "-");
        Path dir = Path.of(System.getProperty("bot.ratchet.out", "target/bot-runs"))
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "_" + label);
        Files.createDirectories(dir);
        double upper = Math.log((1 - beta) / alpha), lower = Math.log(beta / (1 - alpha));

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("label", label);
        run.put("method", "ratchet: candidate (knobs nudged) vs champion in paired games, pair-based SPRT H0 score 0.5 vs H1 p1");
        run.put("agent", agent);
        run.put("startedAt", Instant.now().toString());
        run.put("iterations", iterations);
        run.put("threads", threads);
        run.put("sprt", Map.of("p1", p1, "alpha", alpha, "beta", beta, "upper", upper, "lower", lower, "maxGames", maxGames,
                "gamesPerBatch", seedsPerBatch * decks.size() * decks.size() * 2));
        run.put("originGamesPerIteration", originSeeds * decks.size() * decks.size() * 2);
        run.put("changesPerCandidate", changes);
        if (blocks) run.put("blocks", groups.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size(), (a, b) -> a, LinkedHashMap::new)));
        if ("lookahead".equals(agent)) run.put("lookaheadTuning", tuning);
        if (guided) run.put("guided", Map.of("step", step, "explore", explore, "momentum", momentum,
                "rule", "candidate = champion + step * direction / rms(direction) + explore * random signs (in each knob's steps); "
                        + "after each test, direction = momentum * direction + (1 - momentum) * (pair score - 0.5) * sign / explore"));
        run.put("guards", Map.of("minAcceptGames", minAccept, "varianceFloor", varianceFloor, "revertGames", revertGames));
        run.put("seed", seed);
        run.put("knobs", knobs);
        run.put("start", values(champion, knobs));
        if (!startFile.isBlank()) run.put("startFile", startFile);
        write(dir.resolve("run.json"), run);
        Files.writeString(dir.resolve("champion.txt"), text(champion, knobs) + System.lineSeparator());
        log("Ratchet -> " + dir.toAbsolutePath() + " (" + knobs.size() + " knobs, " + threads + " threads)");

        Random random = new Random(seed);
        double sigma = 1.5;
        int version = 0;
        // The champion's paired games against the start, added up while it stays the champion
        long vsWins = 0, vsLosses = 0, vsGames = 0;
        // The champions it replaced, with what they did against the start, to go back to one that does better
        record Former(BotSettings settings, int version, long wins, long losses, long games) {}
        java.util.Deque<Former> formers = new java.util.ArrayDeque<>();
        // A champion read from a file is already not the start: it is measured from the first iteration
        boolean measured = !startFile.isBlank();
        int nextVersion = 0;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long t0 = System.nanoTime();
        try {
            for (int k = 1; k <= iterations; k++) {
                long iterStart = System.nanoTime();
                // A candidate: one or two knobs nudged
                Map<String, double[]> nudged = new LinkedHashMap<>();
                BotSettings candidate = champion;
                List<Knob> pool2 = new ArrayList<>(knobs);
                java.util.Collections.shuffle(pool2, random);
                // Blocks: one group at random; a weight is nudged alone, a deck's biases all at once (each up or down)
                String group = null;
                if (blocks) {
                    List<String> names = new ArrayList<>(groups.keySet());
                    List<String> decksGroups = names.stream().filter(n -> groups.get(n).size() > 1).toList();
                    List<String> singles = names.stream().filter(n -> groups.get(n).size() == 1).toList();
                    group = !decksGroups.isEmpty() && (singles.isEmpty() || random.nextBoolean())
                            ? decksGroups.get(random.nextInt(decksGroups.size())) : singles.get(random.nextInt(singles.size()));
                    List<Knob> chosen = groups.get(group);
                    for (Knob kn : chosen) {
                        double from = champion.get(kn.name());
                        double to = chosen.size() == 1 ? nudge(kn, from, sigma, random)
                                : Math.max(kn.min(), Math.min(kn.max(), Math.round((from + (random.nextBoolean() ? 1 : -1) * sigma * 0.5 * kn.unit()) * 10000) / 10000.0));
                        if (to == from) continue;
                        nudged.put(kn.name(), new double[]{from, to});
                        candidate = candidate.with(kn.name() + "=" + to);
                    }
                    pool2.clear();
                }
                // Guided: every knob moves a step along the learned direction and explores up or down
                int[] signs = null;
                if (guided) {
                    group = "guided";
                    signs = new int[knobs.size()];
                    double rms = 0;
                    for (double d : direction) rms += d * d;
                    rms = Math.sqrt(rms / Math.max(1, direction.length));
                    for (int i = 0; i < knobs.size(); i++) {
                        Knob kn = knobs.get(i);
                        signs[i] = random.nextBoolean() ? 1 : -1;
                        double move = (rms > 0 ? step * direction[i] / rms : 0) + explore * signs[i];
                        double from = champion.get(kn.name());
                        double to = Math.max(kn.min(), Math.min(kn.max(), from + move * kn.unit()));
                        to = kn.kind() == 'i' ? Math.round(to) : Math.round(to * 10000) / 10000.0;
                        if (to == from) continue;
                        nudged.put(kn.name(), new double[]{from, to});
                        candidate = candidate.with(kn.name() + "=" + to);
                    }
                    pool2.clear();
                }
                // Knobs in random order until enough have moved (a knob at the end of its range may not move)
                for (Knob kn : pool2) {
                    if (nudged.size() >= changes) break;
                    double from = champion.get(kn.name());
                    double to = nudge(kn, from, sigma, random);
                    if (to == from) to = nudge(kn, from, Math.max(1, sigma), random);
                    if (to == from) continue;
                    nudged.put(kn.name(), new double[]{from, to});
                    candidate = candidate.with(kn.name() + "=" + to);
                }
                BotSettings cand = candidate.with(play), champ = champion.with(play);

                // Sequential test, batch by batch, on pairs: the two games of a deal (seats swapped) are one score
                long wins = 0, losses = 0, played = 0;
                Pairs pairs = new Pairs();
                double llr = 0;
                String decision = "undecided";
                while (played < maxGames) {
                    List<Map<String, Object>> batch = paired(cand, champ, agent, decks, seedsPerBatch, random, pool, "candidate", k, dir);
                    for (Map<String, Object> g : batch) {
                        played++;
                        if ("a".equals(g.get("winner"))) wins++;
                        else if ("b".equals(g.get("winner"))) losses++;
                    }
                    pairs.add(batch);
                    llr = pairs.llr(p1, varianceFloor);
                    if (pairs.n() < 16) continue;
                    if (llr >= upper && played >= minAccept) { decision = "accepted"; break; }
                    if (llr <= lower) { decision = "rejected"; break; }
                }
                boolean accepted = "accepted".equals(decision);
                // Every test teaches, accepted or not: the pair score's distance from 0.5 along each knob's random sign
                if (guided && pairs.n() > 0) {
                    double s = pairs.mean() - 0.5;
                    for (int i = 0; i < direction.length; i++) direction[i] = momentum * direction[i] + (1 - momentum) * s * signs[i] / explore;
                    step = accepted ? Math.min(2, step * 1.25) : Math.max(0.2, step * 0.9);
                }
                if (accepted) {
                    formers.push(new Former(champion, version, vsWins, vsLosses, vsGames));
                    champion = candidate;
                    version = ++nextVersion;
                    measured = true;
                    vsWins = vsLosses = vsGames = 0;
                    sigma = Math.min(3, sigma * 1.25);
                    Files.writeString(dir.resolve("champion.txt"), text(champion, knobs) + System.lineSeparator());
                    Files.writeString(dir.resolve("champion-v" + version + ".txt"), text(champion, knobs) + System.lineSeparator());
                } else {
                    sigma = Math.max(0.5, sigma * 0.93);
                }

                // The champion against the start, a little more each iteration
                String reverted = null;
                if (measured) {
                    for (Map<String, Object> g : paired(champion.with(play), origin.with(play), agent, decks, originSeeds, random, pool, "origin", k, dir)) {
                        vsGames++;
                        if ("a".equals(g.get("winner"))) vsWins++;
                        else if ("b".equals(g.get("winner"))) vsLosses++;
                    }
                    // Clearly worse against the start than the best well-measured champion before it (two-proportion
                    // test): back to that one. The best, not just the last, so a chain of quick changes cannot slip past
                    Former prev = null;
                    for (Former f : formers) {
                        if (f.wins() + f.losses() < revertGames) continue;
                        if (prev == null || (double) f.wins() / (f.wins() + f.losses()) > (double) prev.wins() / (prev.wins() + prev.losses())) prev = f;
                    }
                    if (prev != null && vsGames >= revertGames) {
                        double pNew = (double) vsWins / Math.max(1, vsWins + vsLosses), pOld = (double) prev.wins() / (prev.wins() + prev.losses());
                        double pool2p = (double) (vsWins + prev.wins()) / (vsWins + vsLosses + prev.wins() + prev.losses());
                        double se = Math.sqrt(pool2p * (1 - pool2p) * (1.0 / Math.max(1, vsWins + vsLosses) + 1.0 / (prev.wins() + prev.losses())));
                        if (se > 0 && (pNew - pOld) / se < -2) {
                            reverted = String.format(Locale.ROOT, "v%d (%.3f) back to v%d (%.3f)", version, pNew, prev.version(), pOld);
                            while (!formers.isEmpty() && formers.peek() != prev) formers.pop();
                            formers.pop();
                            champion = prev.settings();
                            version = prev.version();
                            vsWins = prev.wins();
                            vsLosses = prev.losses();
                            vsGames = prev.games();
                            Files.writeString(dir.resolve("champion.txt"), text(champion, knobs) + System.lineSeparator());
                        }
                    }
                }
                double[] ci = BotDuel.wilson(vsWins, vsWins + vsLosses);
                Map<String, Object> vs = new LinkedHashMap<>();
                vs.put("version", version);
                vs.put("games", vsGames);
                vs.put("wins", vsWins);
                vs.put("losses", vsLosses);
                vs.put("winRate", vsWins + vsLosses == 0 ? 0.5 : BotDuel.round((double) vsWins / (vsWins + vsLosses)));
                vs.put("winLow95", !measured ? 0.5 : BotDuel.round(ci[0]));
                vs.put("winHigh95", !measured ? 0.5 : BotDuel.round(ci[1]));

                Map<String, Object> line = new LinkedHashMap<>();
                line.put("iteration", k);
                if (group != null) line.put("group", group);
                if (guided) {
                    line.put("step", BotDuel.round(step));
                    // The knobs the learned direction pushes most, and which way (in steps of each)
                    Map<String, Double> top = new LinkedHashMap<>();
                    Integer[] order = new Integer[direction.length];
                    for (int i = 0; i < order.length; i++) order[i] = i;
                    java.util.Arrays.sort(order, (x, y) -> Double.compare(Math.abs(direction[y]), Math.abs(direction[x])));
                    for (int i = 0; i < Math.min(15, order.length); i++) top.put(knobs.get(order[i]).name(), BotDuel.round(direction[order[i]]));
                    line.put("direction", top);
                }
                line.put("nudged", nudged.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                        e -> Map.of("from", BotDuel.round(e.getValue()[0]), "to", BotDuel.round(e.getValue()[1])), (a, b) -> a, LinkedHashMap::new)));
                line.put("sigma", BotDuel.round(sigma));
                line.put("sprt", Map.of("games", played, "wins", wins, "losses", losses, "llr", BotDuel.round(llr), "decision", decision,
                        "pairs", pairs.n(), "pairScore", BotDuel.round(pairs.mean())));
                line.put("accepted", accepted);
                if (reverted != null) line.put("reverted", reverted);
                line.put("championVersion", version);
                line.put("champion", values(champion, knobs));
                line.put("vsOrigin", vs);
                line.put("seconds", BotDuel.round((System.nanoTime() - iterStart) / 1e9));
                append(dir.resolve("iterations.jsonl"), line);
                log(String.format(Locale.ROOT, "it %d: %s %s in %d games (%d-%d, LLR %.2f) | champion v%d vs start %s (%d games) | %.0f s",
                        k, nudged.keySet(), decision.toUpperCase(Locale.ROOT), played, wins, losses, llr, version, vs.get("winRate"), vsGames,
                        (System.nanoTime() - iterStart) / 1e9));
            }

            // The champion against the start, long and paired: better with 95 % confidence?
            long w = 0, l = 0, n = 0;
            for (Map<String, Object> g : paired(champion.with(NO_TIME_LIMIT), origin.with(NO_TIME_LIMIT), agent, decks, finalSeeds, random, pool, "final", iterations, dir)) {
                n++;
                if ("a".equals(g.get("winner"))) w++;
                else if ("b".equals(g.get("winner"))) l++;
            }
            double[] ci = BotDuel.wilson(w, w + l);
            Map<String, Object> fin = new LinkedHashMap<>();
            fin.put("agent", agent);
            if (validateSeeds > 0 && measured) {
                // The same champion, now played by the look-ahead bot (the server's), against the start played by it too
                long vw = 0, vl = 0, vn = 0;
                for (Map<String, Object> g : paired(champion.with(NO_TIME_LIMIT), origin.with(NO_TIME_LIMIT), "lookahead", decks, validateSeeds, random, pool, "validate", iterations, dir)) {
                    vn++;
                    if ("a".equals(g.get("winner"))) vw++;
                    else if ("b".equals(g.get("winner"))) vl++;
                }
                double[] vci = BotDuel.wilson(vw, vw + vl);
                fin.put("lookaheadValidation", Map.of("games", vn, "wins", vw, "losses", vl, "winRate", BotDuel.round(vw + vl == 0 ? 0 : (double) vw / (vw + vl)),
                        "winLow95", BotDuel.round(vci[0]), "winHigh95", BotDuel.round(vci[1])));
                log(String.format(Locale.ROOT, "look-ahead validation: %d-%d (%.3f, 95%% %.3f-%.3f)", vw, vl, vw + vl == 0 ? 0 : (double) vw / (vw + vl), vci[0], vci[1]));
            }
            fin.put("championVersion", version);
            fin.put("champion", values(champion, knobs));
            fin.put("settings", text(champion, knobs));
            fin.put("againstStart", Map.of("games", n, "wins", w, "losses", l, "winRate", BotDuel.round(w + l == 0 ? 0 : (double) w / (w + l)),
                    "winLow95", BotDuel.round(ci[0]), "winHigh95", BotDuel.round(ci[1])));
            fin.put("accepted", measured && ci[0] > 0.5);
            fin.put("minutes", BotDuel.round((System.nanoTime() - t0) / 6e10));
            write(dir.resolve("final.json"), fin);
            run.put("finishedAt", Instant.now().toString());
            run.put("final", fin);
            write(dir.resolve("run.json"), run);
            log(String.format(Locale.ROOT, "final: champion v%d vs start %d-%d (%.3f, 95%% from %.3f) -> %s", version, w, l,
                    w + l == 0 ? 0 : (double) w / (w + l), ci[0], measured && ci[0] > 0.5 ? "BETTER" : "not proven better"));
        } finally {
            pool.shutdown();
        }
    }

    // ------------------------------------------------------------------ Paired games

    /**
     * A against B on {@code seeds} new deals: each seed with every pair of decks, played twice with the seats (and so
     * the decks) swapped between the bots. Every game is kept in games.jsonl. Winner "a" or "b".
     */
    static List<Map<String, Object>> paired(BotSettings a, BotSettings b, String agent, List<String> decks, int seeds,
                                                    Random random, ExecutorService pool, String phase, int iteration, Path dir) throws Exception {
        return paired(a, b, agent, decks, seeds, 1, 0, random, pool, phase, iteration, dir);
    }

    /**
     * The same, playing only part of the pairs of decks: the {@code part}-th of {@code parts} (pair index modulo parts),
     * so a round can be smaller (half the pairs: 16 games instead of 32) and every {@code parts} rounds still cover them all.
     */
    static List<Map<String, Object>> paired(BotSettings a, BotSettings b, String agent, List<String> decks, int seeds,
                                            int parts, int part, Random random, ExecutorService pool, String phase, int iteration, Path dir) throws Exception {
        record Deal(long seed, String hostDeck, String guestDeck, boolean aHost) {}
        List<Deal> deals = new ArrayList<>();
        for (int s = 0; s < seeds; s++) {
            long dealSeed = random.nextLong();
            int pair = 0;
            for (String x : decks) for (String y : decks) {
                if (pair++ % parts != part) continue;
                deals.add(new Deal(dealSeed, x, y, true));
                deals.add(new Deal(dealSeed, x, y, false));
            }
        }
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        for (Deal d : deals) {
            futures.add(pool.submit(() -> {
                BotPlayer botA = "lookahead".equals(agent) ? new LookaheadBot(a) : new HeuristicBot(a);
                BotPlayer botB = "lookahead".equals(agent) ? new LookaheadBot(b) : new HeuristicBot(b);
                BotMatch.Result r = d.aHost() ? BotMatch.playSeeded(d.hostDeck(), d.guestDeck(), botA, botB, d.seed())
                        : BotMatch.playSeeded(d.hostDeck(), d.guestDeck(), botB, botA, d.seed());
                String aSeat = d.aHost() ? "host" : "guest";
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("phase", phase);
                m.put("iteration", iteration);
                m.put("seed", d.seed());
                m.put("hostDeck", d.hostDeck());
                m.put("guestDeck", d.guestDeck());
                m.put("aSeat", aSeat);
                m.put("aDeck", d.aHost() ? d.hostDeck() : d.guestDeck());
                m.put("bDeck", d.aHost() ? d.guestDeck() : d.hostDeck());
                m.put("winner", r.winner() == null ? null : r.winner().equals(aSeat) ? "a" : "b");
                m.put("reason", r.reason());
                m.put("turns", r.turns());
                m.put("conceded", r.conceded() == null ? null : r.conceded().equals(aSeat) ? "a" : "b");
                m.put("ms", r.millis());
                // Utilities and structures each bot played from its hand
                m.put("aPlays", r.plays().getOrDefault(aSeat, 0));
                m.put("bPlays", r.plays().getOrDefault(d.aHost() ? "guest" : "host", 0));
                // Own effects each bot activated: how much of its deck's engine it set going
                m.put("aEffects", r.effects().getOrDefault(aSeat, 0));
                m.put("bEffects", r.effects().getOrDefault(d.aHost() ? "guest" : "host", 0));
                if (botA instanceof LookaheadBot la && botB instanceof LookaheadBot lb) {
                    m.put("aThink", thinking(la));
                    m.put("bThink", thinking(lb));
                }
                return m;
            }));
        }
        List<Map<String, Object>> games = new ArrayList<>();
        for (Future<Map<String, Object>> f : futures) games.add(f.get());
        synchronized (BotRatchetRun.class) {
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> g : games) sb.append(JSON.writeValueAsString(g)).append(System.lineSeparator());
            Files.writeString(dir.resolve("games.jsonl"), sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return games;
    }

    /**
     * What a look-ahead bot's decisions cost in one game (how many, their total and longest time, how many ran out of
     * time) and how it used its chances: special summons it could make and made, cards it sent from the hand to the
     * Tributos when it could choose creatures or not.
     */
    static Map<String, Object> thinking(LookaheadBot bot) {
        LookaheadBot.Stats s = bot.stats();
        LookaheadBot.Choices c = bot.choices();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decisions", s.decisions());
        m.put("ms", BotDuel.round(s.totalNanos() / 1e6));
        m.put("maxMs", BotDuel.round(s.maxNanos() / 1e6));
        m.put("outOfTime", s.outOfTime());
        m.put("specialChances", c.specialChances());
        m.put("specials", c.specials());
        m.put("tributeChances", c.tributeChances());
        m.put("tributedCreatures", c.tributedCreatures());
        m.put("tributedOthers", c.tributedOthers());
        return m;
    }

    /**
     * The games of a test grouped by deal (seed and decks, played twice with the seats swapped): each deal scores A's
     * share of its two games (1, 0.5 or 0). A deal each bot won once says nothing about which is better (the deal or the
     * seat won), and it only lowers the variance; the test reads the mean of those scores.
     */
    static final class Pairs {
        private final Map<String, double[]> deals = new LinkedHashMap<>();

        void add(List<Map<String, Object>> games) {
            for (Map<String, Object> g : games) {
                String key = g.get("seed") + "|" + g.get("hostDeck") + "|" + g.get("guestDeck");
                double[] d = deals.computeIfAbsent(key, x -> new double[2]);
                d[0] += "a".equals(g.get("winner")) ? 1 : g.get("winner") == null ? 0.5 : 0;
                d[1]++;
            }
        }

        private List<Double> scores() {
            List<Double> out = new ArrayList<>();
            for (double[] d : deals.values()) if (d[1] == 2) out.add(d[0] / 2);
            return out;
        }

        int n() {
            return scores().size();
        }

        double mean() {
            List<Double> s = scores();
            return s.isEmpty() ? 0.5 : s.stream().mapToDouble(Double::doubleValue).average().orElse(0.5);
        }

        /**
         * The log-likelihood ratio of H1 (mean score p1) against H0 (0.5), with the scores taken as normal with the
         * variance seen (the usual approximation for paired games): n (p1 - 0.5) (mean - (0.5 + p1) / 2) / variance.
         */
        double llr(double p1, double floor) {
            List<Double> s = scores();
            int n = s.size();
            if (n < 2) return 0;
            double m = mean(), v = 0;
            for (double x : s) v += (x - m) * (x - m);
            v = Math.max(v / (n - 1), floor);
            return n * (p1 - 0.5) * (m - (0.5 + p1) / 2) / v;
        }
    }

    // ------------------------------------------------------------------ Knobs

    /** A knob moved by about sigma of its steps, kept in its range (a flag is flipped; a whole number rounded). */
    static double nudge(Knob k, double from, double sigma, Random random) {
        double to;
        if (k.kind() == 'f') to = from >= 0.5 ? 0 : 1;
        else {
            to = from + random.nextGaussian() * sigma * k.unit();
            if (k.kind() == 'i') to = Math.round(to);
        }
        to = Math.max(k.min(), Math.min(k.max(), to));
        return k.kind() == 'r' ? Math.round(to * 10000) / 10000.0 : to;
    }

    /** The share of the tables where each card is in each zone (exposure.csv), by bias key; empty without the file. */
    private static Map<String, Double> exposure(Path file) throws IOException {
        Map<String, Double> m = new LinkedHashMap<>();
        if (!Files.exists(file)) return m;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> head = List.of(lines.get(0).split(","));
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",");
            m.put(BotSettings.BIAS + f[0], Double.parseDouble(f[head.indexOf("field")]));
            m.put(BotSettings.BIAS_TRIBUTE + f[0], Double.parseDouble(f[head.indexOf("tribute")]));
            m.put(BotSettings.BIAS_DISCARD + f[0], Double.parseDouble(f[head.indexOf("discard")]));
        }
        return m;
    }

    private static List<Knob> picked(String names) {
        if (names.isBlank()) return KNOBS;
        List<Knob> out = new ArrayList<>();
        for (String n : names.split(",")) {
            String name = n.trim();
            if (name.isEmpty()) continue;
            out.add(KNOBS.stream().filter(k -> k.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown knob: " + name)));
        }
        return out;
    }

    private static Map<String, Double> values(BotSettings s, List<Knob> knobs) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (Knob k : knobs) m.put(k.name(), BotDuel.round(s.get(k.name())));
        return m;
    }

    /** "hMullCost=4.0,life=10.0,…": the knobs as BotSettings#with reads them (and the server's settings.txt). */
    private static String text(BotSettings s, List<Knob> knobs) {
        return knobs.stream().map(k -> k.name() + "=" + s.get(k.name())).collect(Collectors.joining(","));
    }

    // ------------------------------------------------------------------ Files

    private static void write(Path file, Object value) throws IOException {
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8);
    }

    private static void append(Path file, Object value) throws IOException {
        Files.writeString(file, JSON.writeValueAsString(value) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void log(String line) {
        System.out.println("[ratchet " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + line);
    }
}
