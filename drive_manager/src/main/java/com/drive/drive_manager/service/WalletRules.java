package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.EditionMetadata;

import java.util.Collection;
import java.util.List;

/**
 * The simulated gems of the Editions shop (there is no money on the platform): what a new account starts with, the
 * daily reward and the prices. Pure, so the web and the tests read the same rules.
 */
public final class WalletRules {

    public static final int STARTING_GEMS = 2400;
    public static final int DAILY_GEMS = 300;
    /** Any pack, of any edition (its type comes at random). */
    public static final int PACK_PRICE = 100;
    /** A booster box of an edition (E1, E2…) and of a sub-edition (E1.1, E1.2…). */
    public static final int BOX_PRICE = 2400;
    public static final int SUB_EDITION_BOX_PRICE = 1200;
    /** A structure deck (ST1, ST2…), whole. */
    public static final int STRUCTURE_PRICE = 1000;
    /**
     * The first four structure decks: the first of them an account takes is free (the "Bienvenida" starts by taking
     * it), each of the others costs STARTER_PRICE.
     */
    public static final List<String> STARTERS = List.of("ST1", "ST2", "ST3", "ST4");
    public static final int STARTER_PRICE = 250;

    private WalletRules() {
    }

    /** A wallet as of a day: its gems and the day of its last reward (YYYY-MM-DD). */
    public record State(int gems, String lastReward) {
    }

    /** The wallet opened on `today`, and the reward just given (0 when none). */
    public record Opened(State state, int reward) {
    }

    /**
     * A new wallet starts with the starting gems (that day's reward included); an existing one gets the daily reward
     * once per day.
     */
    public static Opened open(State saved, String today) {
        if (saved == null) return new Opened(new State(STARTING_GEMS, today), 0);
        if (today.equals(saved.lastReward())) return new Opened(saved, 0);
        return new Opened(new State(Math.max(0, saved.gems()) + DAILY_GEMS, today), DAILY_GEMS);
    }

    /** A structure deck has an ST code (ST1, ST2…). */
    public static boolean isStructure(String editionId) {
        return editionId != null && editionId.toUpperCase().startsWith("ST");
    }

    /** One of the first four structure decks (ST1–ST4). */
    public static boolean isStarter(String editionId) {
        return editionId != null && STARTERS.contains(editionId.toUpperCase());
    }

    /** The account already has one of the first four structure decks. */
    public static boolean ownsStarter(Collection<String> owned) {
        return owned != null && owned.stream().anyMatch(WalletRules::isStarter);
    }

    /** A sub-edition has a dotted code (E1.1, E1.2…); editions and structures do not (E1, ST1…). */
    public static boolean isSubEdition(String editionId) {
        return editionId != null && editionId.contains(".");
    }

    /**
     * What `product` of an edition costs to an account that has the structure decks `owned`, or null when the edition
     * does not sell it: a structure deck sells itself whole ("structure"; one of ST1–ST4 is free while the account has
     * none of them, STARTER_PRICE after); an edition sells a "pack" when it has pack types, and a "box" when it also
     * has a box.
     */
    public static Integer priceOf(EditionMetadata edition, String product, Collection<String> owned) {
        if (edition == null) return null;
        if (isStructure(edition.getEditionId())) {
            if (!"structure".equals(product)) return null;
            if (!isStarter(edition.getEditionId())) return STRUCTURE_PRICE;
            return ownsStarter(owned) ? STARTER_PRICE : 0;
        }
        if (edition.getPackTypes() == null || edition.getPackTypes().isEmpty()) return null;
        if ("pack".equals(product)) return PACK_PRICE;
        if (!"box".equals(product)) return null;
        EditionMetadata.BoxConfig box = edition.getBoxConfig();
        boolean hasBox = box != null && box.getTotalPacks() > 0 && box.getEntries() != null && !box.getEntries().isEmpty();
        if (!hasBox) return null;
        return isSubEdition(edition.getEditionId()) ? SUB_EDITION_BOX_PRICE : BOX_PRICE;
    }
}
