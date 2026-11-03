package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.AltArt;
import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.repository.DeckListRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The cards that can come out of an edition: its images (drive_cards) that have metadata, joined as the web joins
 * them (edition + number + sub-edition + color; leftover records whose file name says another card are dropped), with
 * their rarity (none counts as C).
 */
@Service
public class CardPoolService {

    /** The code at the start of an image's file name: "E1_3-B10-…" is edition E1, sub-edition 3, blue, number 10. */
    private static final Pattern FILE_CODE = Pattern.compile("^((?:ST|E)\\d+)(?:_(\\d+))?-([BGPRW])(\\d+)-", Pattern.CASE_INSENSITIVE);

    private final DriveCardRepository driveCards;
    private final CardRepository metadata;
    private final DeckListRepository decks;

    public CardPoolService(DriveCardRepository driveCards, CardRepository metadata, DeckListRepository decks) {
        this.driveCards = driveCards;
        this.metadata = metadata;
        this.decks = decks;
    }

    /** The card ids of an edition ("E1" or "E1.1") by rarity. */
    public Map<String, List<String>> pool(String editionId) {
        Map<String, List<String>> pool = new LinkedHashMap<>();
        rarities(editionId).forEach((id, rarity) -> pool.computeIfAbsent(rarity, r -> new ArrayList<>()).add(id));
        return pool;
    }

    /** The rarity of a card, or null when it is not a card with metadata. */
    public String rarityOf(String cardId) {
        DriveCard card = driveCards.findById(cardId).orElse(null);
        if (card == null || card.getEdition() == null) return null;
        String editionId = card.getSubEdition() == null || card.getSubEdition().isEmpty() ? card.getEdition() : card.getEdition() + "." + card.getSubEdition();
        return rarities(editionId).get(cardId);
    }

    /** The rarity of each of these cards (those that are cards with metadata), each edition read once. */
    public Map<String, String> raritiesOf(java.util.Collection<String> cardIds) {
        Map<String, String> out = new HashMap<>();
        java.util.Set<String> editions = new java.util.HashSet<>();
        for (DriveCard card : driveCards.findAllById(cardIds)) {
            if (card.getEdition() == null) continue;
            editions.add(card.getSubEdition() == null || card.getSubEdition().isEmpty() ? card.getEdition() : card.getEdition() + "." + card.getSubEdition());
        }
        for (String edition : editions) {
            rarities(edition).forEach((id, rarity) -> {
                if (cardIds.contains(id)) out.put(id, rarity);
            });
        }
        return out;
    }

    /** A structure deck's cards: its public deck (named like the edition, "ST1"), or else one of each of its cards. */
    public List<String> structureCards(String editionId) {
        List<String> fromDeck = decks.findPublicDecks().stream()
                .filter(d -> d.getDeckName() != null && d.getDeckName().trim().equalsIgnoreCase(editionId))
                .map(DeckList::getCards).filter(Objects::nonNull).findFirst().orElse(List.of());
        Map<String, String> known = rarities(editionId);
        List<String> cards = fromDeck.stream().filter(known::containsKey).toList();
        return cards.isEmpty() ? new ArrayList<>(known.keySet()) : cards;
    }

    /** The cards of an edition ("E1" or "E1.1") that can come out with an alternative art: card id → its arts. */
    public Map<String, List<AltArt>> withAltArt(String editionId) {
        Map<String, List<AltArt>> out = new java.util.HashMap<>();
        joined(editionId).forEach((id, meta) -> {
            if (!meta.getAltArts().isEmpty()) out.put(id, meta.getAltArts());
        });
        return out;
    }

    /** Card id → rarity, for the cards of an edition with metadata. */
    private Map<String, String> rarities(String editionId) {
        Map<String, String> out = new LinkedHashMap<>();
        joined(editionId).forEach((id, m) -> out.put(id, m.getRarity() == null || m.getRarity().isBlank() ? "C" : m.getRarity()));
        return out;
    }

    /** Card id → metadata, for the images of an edition joined to theirs (as the web joins them). */
    private Map<String, Card> joined(String editionId) {
        String[] parts = editionId.split("\\.", 2);
        String base = parts[0];
        String sub = parts.length > 1 ? parts[1] : "";
        Map<String, Card> byKey = new HashMap<>();
        for (Card m : metadata.findByEdition(base)) {
            if (m.getCardNumber() != null) byKey.put(key(m.getEdition(), m.getCardNumber(), m.getSubEdition(), m.getColorIdentity()), m);
        }
        Map<String, Card> out = new LinkedHashMap<>();
        for (DriveCard d : driveCards.findByEdition(base)) {
            if (!sub.equals(d.getSubEdition() == null ? "" : d.getSubEdition()) || !matchesFileName(d)) continue;
            Card m = byKey.get(key(d.getEdition(), d.getNumber(), d.getSubEdition(), d.getColorIdentity()));
            if (m != null) out.put(d.getId(), m);
        }
        return out;
    }

    private static String key(String edition, Integer number, String sub, String color) {
        return (edition == null ? "" : edition) + "|" + (number == null ? "" : number) + "|" + (sub == null ? "" : sub) + "|" + (color == null ? "" : color);
    }

    /** False for a leftover record whose file name codes another card (a re-uploaded image left behind). */
    static boolean matchesFileName(DriveCard card) {
        Matcher m = card.getFileName() == null ? null : FILE_CODE.matcher(card.getFileName());
        if (m == null || !m.find()) return true;
        return m.group(1).equalsIgnoreCase(card.getEdition() == null ? "" : card.getEdition())
                && (m.group(2) == null ? "" : m.group(2)).equals(card.getSubEdition() == null ? "" : card.getSubEdition())
                && m.group(3).equalsIgnoreCase(card.getColorIdentity() == null ? "" : card.getColorIdentity())
                && Objects.equals(Integer.valueOf(m.group(4)), card.getNumber());
    }
}
