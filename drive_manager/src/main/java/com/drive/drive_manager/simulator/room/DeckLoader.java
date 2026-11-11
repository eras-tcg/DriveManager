package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.repository.DeckListRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.simulator.engine.CardEffects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Where the simulator's decks and cards come from: a deck list made into the card slots a room plays (each card with
 * its image and its metadata), a card's metadata read again, and which cards the engine does not run in full (kept a
 * moment: the catalog asks for it on every visit). The room service without it (tests, the bot's copies, replays)
 * never loads decks.
 */
@Component
public class DeckLoader {

    /** How long the list of cards not run in full is kept before it is read again. */
    static final long UNPROGRAMMED_TTL_MS = 60_000;

    /**
     * Where each new card instance gets its id: a random UUID. A test may make them predictable (the browser's game,
     * {@code com.eras.engine.local.LocalMatch}, numbers them "c1", "c2"…, and its parity test compares the two).
     */
    static java.util.function.Supplier<String> instanceIds = () -> UUID.randomUUID().toString();

    /** The id of a new card instance ({@link #instanceIds}). */
    public static String newInstanceId() {
        return instanceIds.get();
    }

    private final DeckListRepository decks;
    private final CardRepository cards;
    private final DriveCardRepository driveCards;
    private final String r2PublicUrl;
    private volatile Map<String, String> unprogrammed;
    private volatile long unprogrammedAt;

    public DeckLoader(DeckListRepository decks, CardRepository cards, DriveCardRepository driveCards,
                      @Value("${r2.public-url}") String r2PublicUrl) {
        this.decks = decks;
        this.cards = cards;
        this.driveCards = driveCards;
        this.r2PublicUrl = r2PublicUrl;
    }

    /** A deck list by id. */
    public Optional<DeckList> deck(String deckId) {
        return decks.findById(deckId);
    }

    /** The deck's cards as a room plays them, face down, each a new instance (two reads: its drive cards, their metadata). */
    public List<CardSlot> slots(DeckList deck) {
        List<String> fileIds = deck.getCards();
        String baseUrl = r2PublicUrl.stripTrailing();
        Map<String, DriveCard> driveCardMap = driveCards.findAllById(fileIds).stream()
                .collect(Collectors.toMap(DriveCard::getId, dc -> dc));
        Set<String> editions = driveCardMap.values().stream().map(DriveCard::getEdition).filter(Objects::nonNull).collect(Collectors.toSet());
        List<Card> editionCards = cards.findByEditionIn(new ArrayList<>(editions));
        // By the printed code, as the web joins them (edition, number, sub-edition, color): an image whose name is not the
        // metadata's (a card renamed, a file named otherwise) still gets it; by name only when the code finds nothing
        Map<String, Card> byKey = editionCards.stream().filter(c -> c.getCardNumber() != null)
                .collect(Collectors.toMap(DeckLoader::keyOf, c -> c, (a, b) -> a));
        Map<String, Card> cardMeta = editionCards.stream().filter(c -> c.getCardName() != null)
                .collect(Collectors.toMap(c -> c.getCardName().toLowerCase() + ":" + c.getEdition(), c -> c, (a, b) -> a));
        return new ArrayList<>(fileIds.stream().map(driveFileId -> {
            CardSlot slot = new CardSlot();
            slot.setInstanceId(instanceIds.get());
            slot.setCardId(driveFileId);
            slot.setFaceDown(true);
            DriveCard dc = driveCardMap.get(driveFileId);
            if (dc != null) {
                slot.setImageUrl(dc.imageUrl(baseUrl));
                if (dc.getName() != null) slot.setCardName(dc.getName());
                Card card = dc.getEdition() == null ? null : byKey.get(keyOf(dc));
                if (card == null && dc.getName() != null && dc.getEdition() != null) card = cardMeta.get(dc.getName().toLowerCase() + ":" + dc.getEdition());
                if (card != null) CardMeta.apply(slot, driveFileId, card);
            }
            return slot;
        }).toList());
    }

    /** The same cards as new instances (another seat playing the same deck: the bot's copy of its owner's). */
    public static List<CardSlot> freshCopy(List<CardSlot> slots) {
        return DeckChoice.freshCopy(slots, DeckLoader::newInstanceId);
    }

    /** A card's metadata as it is now, by its drive file id (by its printed code, else by name); null when it is not found. */
    public Card metadataOf(String cardId) {
        DriveCard dc = driveCards.findById(cardId).orElse(null);
        if (dc == null || dc.getEdition() == null) return null;
        Card byCode = cards.findByEdition(dc.getEdition()).stream().filter(c -> c.getCardNumber() != null && keyOf(c).equals(keyOf(dc)))
                .findFirst().orElse(null);
        if (byCode != null || dc.getName() == null) return byCode;
        List<Card> matches = cards.findByCardNameIgnoreCaseAndEdition(dc.getName(), dc.getEdition());
        return matches.isEmpty() ? null : matches.get(0);
    }

    /** The printed code that joins an image and its metadata: edition | number | sub-edition | color. */
    static String keyOf(Card c) {
        return c.getEdition() + "|" + c.getCardNumber() + "|" + (c.getSubEdition() == null ? "" : c.getSubEdition()) + "|" + c.getColorIdentity();
    }

    static String keyOf(DriveCard d) {
        return d.getEdition() + "|" + d.getNumber() + "|" + (d.getSubEdition() == null ? "" : d.getSubEdition()) + "|" + d.getColorIdentity();
    }

    /** Every copy of the card in the room takes its metadata again. */
    public static void refresh(GameRoom room, String cardId, Card meta) {
        DeckChoice.refresh(room, cardId, slot -> {
            CardMeta.apply(slot, cardId, meta);
            return kotlin.Unit.INSTANCE;
        });
    }

    /** The names of a deck's cards the engine does not run in full ("×2 Roto-G0, Sicario R3-D"), or null. */
    public static String unprogrammedIn(List<CardSlot> slots) {
        return DeckChoice.unprogrammedIn(slots);
    }

    /**
     * The cards whose metadata the engine does not run in full, by metadata id, with what is missing (as a card in
     * play shows it); read again after a minute.
     */
    Map<String, String> unprogrammedCards() {
        Map<String, String> now = unprogrammed;
        if (now != null && System.currentTimeMillis() - unprogrammedAt < UNPROGRAMMED_TTL_MS) return now;
        Map<String, String> pending = new LinkedHashMap<>();
        for (Card meta : cards.findAll()) {
            List<String> keywords = new ArrayList<>(CardMeta.keywordNames(meta.getKeywordEffects()));
            keywords.addAll(CardMeta.keywordNames(meta.getInheritKeywordEffects()));
            String missing = CardEffects.pending(meta.getCardName(),
                    meta.getEffects() == null ? 0 : meta.getEffects().size(),
                    meta.getInheritEffects() == null ? 0 : meta.getInheritEffects().size(), keywords);
            if (missing != null) pending.put(meta.getId(), missing);
        }
        unprogrammed = java.util.Collections.unmodifiableMap(pending);
        unprogrammedAt = System.currentTimeMillis();
        return unprogrammed;
    }
}
