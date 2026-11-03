package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.EditionMetadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.regex.Pattern;

/**
 * What comes out of a pack or a box of an edition (the same rules the web used to roll, now drawn here so nobody can
 * make up their cards). A pool is the edition's card ids by rarity. Pure: the random source is passed in.
 */
public final class BoosterRules {

    public static final List<String> RARITIES = List.of("C", "UC", "R", "SR", "SEC");
    private static final Pattern GOD = Pattern.compile("\\bgod\\b", Pattern.CASE_INSENSITIVE);

    private BoosterRules() {
    }

    /** A pack as it comes out: its type, its card ids in pull order, and the type it was if it went up a rarity. */
    public record OpenedPack(String packType, List<String> cards, String upgradedFrom) {
        public OpenedPack(String packType, List<String> cards) {
            this(packType, cards, null);
        }
    }

    /** Each slot is an independent pick (with replacement). */
    public static List<String> openPack(EditionMetadata.PackTypeConfig pack, Map<String, List<String>> pool, DoubleSupplier random) {
        List<String> picked = new ArrayList<>();
        for (String rarity : RARITIES) {
            List<String> candidates = pool.getOrDefault(rarity, List.of());
            int n = pack.getCardsPerRarity() == null ? 0 : pack.getCardsPerRarity().getOrDefault(rarity, 0);
            if (candidates.isEmpty()) continue;
            for (int i = 0; i < n; i++) picked.add(candidates.get(index(random, candidates.size())));
        }
        return picked;
    }

    /** How many packs a PROB entry gives, rolling its probability table. */
    public static int rollQuantity(List<EditionMetadata.ProbOption> options, DoubleSupplier random) {
        double roll = random.getAsDouble();
        double cumulative = 0;
        for (EditionMetadata.ProbOption option : options) {
            cumulative += option.getProb();
            if (roll <= cumulative) return option.getQuantity();
        }
        return options.isEmpty() ? 0 : options.get(options.size() - 1).getQuantity();
    }

    /** The pack types of a whole box, in order: FIXED entries, then PROB rolls, then FLEX fills the remaining packs. */
    public static List<String> boxPackTypes(EditionMetadata edition, DoubleSupplier random) {
        EditionMetadata.BoxConfig box = edition.getBoxConfig();
        List<String> types = new ArrayList<>();
        if (box == null || box.getEntries() == null) return types;
        for (EditionMetadata.BoxEntry entry : box.getEntries()) {
            if ("FIXED".equals(entry.getMode())) repeat(types, entry.getPackTypeName(), entry.getQuantity() == null ? 0 : entry.getQuantity());
        }
        for (EditionMetadata.BoxEntry entry : box.getEntries()) {
            if ("PROB".equals(entry.getMode()) && entry.getProbOptions() != null && !entry.getProbOptions().isEmpty()) {
                repeat(types, entry.getPackTypeName(), rollQuantity(entry.getProbOptions(), random));
            }
        }
        box.getEntries().stream().filter(e -> "FLEX".equals(e.getMode())).findFirst()
                .ifPresent(flex -> repeat(types, flex.getPackTypeName(), Math.max(0, box.getTotalPacks() - types.size())));
        return types;
    }

    /** One pack type at random, with the odds of a real box (one box simulated, one of its packs). */
    public static String randomPackType(EditionMetadata edition, DoubleSupplier random) {
        List<String> types = boxPackTypes(edition, random);
        if (!types.isEmpty()) return types.get(index(random, types.size()));
        List<EditionMetadata.PackTypeConfig> packs = edition.getPackTypes();
        return packs == null || packs.isEmpty() ? null : packs.get(0).getName();
    }

    /** A whole box, pack by pack (types the edition does not have are skipped). */
    public static List<OpenedPack> openBoxPacks(EditionMetadata edition, Map<String, List<String>> pool, DoubleSupplier random) {
        List<OpenedPack> opened = new ArrayList<>();
        for (String name : boxPackTypes(edition, random)) {
            EditionMetadata.PackTypeConfig pack = packNamed(edition, name);
            if (pack != null) opened.add(new OpenedPack(pack.getName(), openPack(pack, pool, random)));
        }
        return opened;
    }

    /** How special a pack type looks: 0 none, 1 SR, 2 SEC, 3 GOD (named so, two secrets or more, or five SR+SEC). */
    public static int tier(EditionMetadata.PackTypeConfig pack) {
        if (pack == null) return 0;
        Map<String, Integer> per = pack.getCardsPerRarity() == null ? Map.of() : pack.getCardsPerRarity();
        int sr = per.getOrDefault("SR", 0);
        int sec = per.getOrDefault("SEC", 0);
        if ((pack.getName() != null && GOD.matcher(pack.getName()).find()) || sec >= 2 || sr + sec >= 5) return 3;
        if (sec > 0) return 2;
        return sr > 0 ? 1 : 0;
    }

    /**
     * A box's bonus: with chance `chance` (1 for an edition, ½ for a sub-edition), one random pack that can go up a
     * rarity becomes the next pack type up and gets that type's cards.
     */
    public static List<OpenedPack> upgradeOnePack(List<OpenedPack> packs, EditionMetadata edition, Map<String, List<String>> pool,
                                                  double chance, DoubleSupplier random) {
        if (random.getAsDouble() >= chance) return packs;
        List<EditionMetadata.PackTypeConfig> types = edition.getPackTypes() == null ? List.of() : edition.getPackTypes();
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < packs.size(); i++) if (nextUp(types, edition, packs.get(i).packType()) != null) candidates.add(i);
        if (candidates.isEmpty()) return packs;
        int chosen = candidates.get(index(random, candidates.size()));
        EditionMetadata.PackTypeConfig target = nextUp(types, edition, packs.get(chosen).packType());
        List<OpenedPack> out = new ArrayList<>(packs);
        out.set(chosen, new OpenedPack(target.getName(), openPack(target, pool, random), packs.get(chosen).packType()));
        return out;
    }

    private static EditionMetadata.PackTypeConfig nextUp(List<EditionMetadata.PackTypeConfig> types, EditionMetadata edition, String name) {
        int rank = tier(packNamed(edition, name));
        return types.stream().filter(t -> tier(t) > rank).min(Comparator.comparingInt(BoosterRules::tier)).orElse(null);
    }

    private static EditionMetadata.PackTypeConfig packNamed(EditionMetadata edition, String name) {
        if (edition.getPackTypes() == null || name == null) return null;
        return edition.getPackTypes().stream().filter(p -> name.equals(p.getName())).findFirst().orElse(null);
    }

    private static void repeat(List<String> into, String value, int times) {
        for (int i = 0; i < times; i++) into.add(value);
    }

    private static int index(DoubleSupplier random, int size) {
        return Math.min(size - 1, (int) Math.floor(random.getAsDouble() * size));
    }
}
