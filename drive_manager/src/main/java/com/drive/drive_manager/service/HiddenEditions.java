package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.dto.EditionMetadata;
import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.repository.EditionMetadataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Editions an admin hid (edition_metadata {@code hidden}: still in development). Only admins ({@value #ADMIN}) and
 * testers ({@link TestCards#PERMISSION}) see them; for everyone else they are not in the editions, the shop, the cards,
 * the images nor the public decks, and a deck with their cards cannot be saved.
 * <p>
 * An edition id may name a sub-edition ("E1.3"): hiding "E1" hides its sub-editions too, hiding "E1.3" only that one.
 */
@Component
public class HiddenEditions {

    /** The permission of who manages the editions: an admin, who sees them all. */
    public static final String ADMIN = "manage_editions";

    /** How long the hidden ids are kept before they are read again. */
    static final long TTL_MS = 30_000;

    private final EditionMetadataRepository editions;
    private final DriveCardRepository driveCards;
    private volatile Snapshot snapshot;

    @Autowired
    public HiddenEditions(EditionMetadataRepository editions, DriveCardRepository driveCards) {
        this.editions = editions;
        this.driveCards = driveCards;
    }

    /** A stand-in with no hidden edition (tests and classes built by hand). */
    public static HiddenEditions none() {
        return new HiddenEditions(null, null);
    }

    /** Whether the player sees the hidden editions: an admin or a tester. */
    public static boolean canSee(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            String name = a.getAuthority();
            if (ADMIN.equals(name) || TestCards.PERMISSION.equals(name)) return true;
        }
        return false;
    }

    /** The ids of the hidden editions, upper case ("E2", "E1.3"). */
    public Set<String> ids() {
        return current().editions();
    }

    /** Whether the edition ("E2", "E1.3") is hidden, by itself or by its base edition. */
    public boolean isHidden(String edition) {
        if (edition == null || edition.isBlank()) return false;
        Set<String> hidden = ids();
        if (hidden.isEmpty()) return false;
        String id = edition.trim().toUpperCase(Locale.ROOT);
        if (hidden.contains(id)) return true;
        int dot = id.indexOf('.');
        return dot > 0 && hidden.contains(id.substring(0, dot));
    }

    /** Whether a card of this edition and sub-edition is hidden ("E1" + "3" is "E1.3"). */
    public boolean isHidden(String edition, String subEdition) {
        if (isHidden(edition)) return true;
        return edition != null && subEdition != null && !subEdition.isBlank() && isHidden(edition.trim() + "." + subEdition.trim());
    }

    /** Whether a card of this edition is hidden from the player. */
    public boolean hidden(String edition, String subEdition, Authentication auth) {
        return isHidden(edition, subEdition) && !canSee(auth);
    }

    /** Whether the edition (an edition_metadata id) is hidden from the player. */
    public boolean hidden(String edition, Authentication auth) {
        return isHidden(edition) && !canSee(auth);
    }

    /** The items the player may see, by the edition and sub-edition each one has. */
    public <T> List<T> visible(List<T> items, Function<T, String> edition, Function<T, String> subEdition, Authentication auth) {
        if (canSee(auth) || ids().isEmpty()) return items;
        return items.stream().filter(i -> !isHidden(edition.apply(i), subEdition.apply(i))).toList();
    }

    /** Whether a deck with these images (drive_cards ids) has cards of a hidden edition the player may not see. */
    public boolean hiddenDeck(Collection<String> imageIds, Authentication auth) {
        if (canSee(auth) || imageIds == null || imageIds.isEmpty()) return false;
        Set<String> hiddenImages = current().images();
        return !hiddenImages.isEmpty() && imageIds.stream().anyMatch(hiddenImages::contains);
    }

    /** Reads the hidden editions again on the next question (an edition was saved or deleted). */
    public void refresh() {
        snapshot = null;
    }

    private Snapshot current() {
        Snapshot now = snapshot;
        if (now != null && System.currentTimeMillis() - now.at() < TTL_MS) return now;
        Snapshot read = read();
        snapshot = read;
        return read;
    }

    private Snapshot read() {
        if (editions == null) return new Snapshot(Set.of(), Set.of(), System.currentTimeMillis());
        Set<String> ids = editions.findAll().stream()
                .filter(EditionMetadata::isHiddenEdition)
                .map(e -> e.getEditionId().trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        Set<String> images = Set.of();
        if (!ids.isEmpty() && driveCards != null) {
            Set<String> bases = ids.stream().map(id -> id.contains(".") ? id.substring(0, id.indexOf('.')) : id).collect(Collectors.toSet());
            Snapshot partial = new Snapshot(ids, Set.of(), 0);
            images = bases.stream()
                    .flatMap(base -> driveCards.findByEdition(base).stream())
                    .filter(d -> partial.covers(d.getEdition(), d.getSubEdition()))
                    .map(DriveCard::getId)
                    .collect(Collectors.toUnmodifiableSet());
        }
        return new Snapshot(ids, images, System.currentTimeMillis());
    }

    private record Snapshot(Set<String> editions, Set<String> images, long at) {
        boolean covers(String edition, String subEdition) {
            if (edition == null) return false;
            String base = edition.trim().toUpperCase(Locale.ROOT);
            if (editions.contains(base)) return true;
            return subEdition != null && !subEdition.isBlank() && editions.contains(base + "." + subEdition.trim().toUpperCase(Locale.ROOT));
        }
    }
}
