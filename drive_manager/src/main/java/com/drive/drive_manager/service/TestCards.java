package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.DriveCardRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Cards in testing: cards that have no edition yet get a temporary one, "T1", "T2"… (files "T1-W01-Name.jpg"). Only
 * players with the {@value #PERMISSION} permission see them (catalog, deck builder, decks, editions); nobody can get
 * them from the shop or craft them. The rule is the edition's code, so a card loaded before anything else is set up
 * is already hidden.
 */
@Component
public class TestCards {

    /** The permission that shows the cards in testing. */
    public static final String PERMISSION = "test_cards";

    /** A testing edition: "T" and a number ("T1"), with or without a sub-edition ("T1.2"). */
    private static final Pattern TEST_EDITION = Pattern.compile("^T\\d+(?:\\.\\d+)?$", Pattern.CASE_INSENSITIVE);

    /** How long the ids of the testing images are kept before they are read again. */
    static final long IDS_TTL_MS = 30_000;

    private final DriveCardRepository driveCards;
    private volatile Set<String> ids;
    private volatile long idsAt;

    public TestCards(DriveCardRepository driveCards) {
        this.driveCards = driveCards;
    }

    /** Whether the edition ("T1", "T1.2") is a testing one. */
    public static boolean isTestEdition(String edition) {
        return edition != null && TEST_EDITION.matcher(edition.trim()).matches();
    }

    /** Whether the player may see the cards in testing. */
    public static boolean canSee(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) return false;
        return auth.getAuthorities().stream().anyMatch(a -> PERMISSION.equals(a.getAuthority()));
    }

    /** Whether a card of this edition is hidden from the player. */
    public static boolean hidden(String edition, Authentication auth) {
        return isTestEdition(edition) && !canSee(auth);
    }

    /** The ids of the images (drive_cards) of the testing editions, read again after {@link #IDS_TTL_MS}. */
    public Set<String> imageIds() {
        Set<String> now = ids;
        if (now != null && System.currentTimeMillis() - idsAt < IDS_TTL_MS) return now;
        Set<String> read = driveCards.findByEditionRegex("^[Tt]\\d+$").stream().map(DriveCard::getId).collect(Collectors.toUnmodifiableSet());
        ids = read;
        idsAt = System.currentTimeMillis();
        return read;
    }

    /** Whether any of these image ids (a deck's cards) is a card in testing. */
    public boolean anyIn(Collection<String> imageIds) {
        if (imageIds == null || imageIds.isEmpty()) return false;
        Set<String> test = imageIds();
        return !test.isEmpty() && imageIds.stream().anyMatch(test::contains);
    }

    /** Whether a deck with these cards is hidden from the player (it has cards in testing they may not see). */
    public boolean hiddenDeck(Collection<String> imageIds, Authentication auth) {
        return !canSee(auth) && anyIn(imageIds);
    }
}
