package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A card's illustration alone (not the finished card), set from the card editor: kept as it came (JPG, PNG or WebP)
 * in CardArtStore under {@code card-art/<card id>/<ms>.<ext>}, and referenced from the card's own document (artKey,
 * artType). The finished card is drawn from the document and this art.
 */
@Service
public class CardArtService {

    /** The largest art file accepted. */
    public static final int MAX_BYTES = 25 * 1024 * 1024;

    /** An art file and its media type. */
    public record Art(byte[] bytes, String type) {
    }

    private final CardRepository cards;
    private final CardArtStore store;
    private final DriveCardRepository images;

    public CardArtService(CardRepository cards, CardArtStore store, DriveCardRepository images) {
        this.cards = cards;
        this.store = store;
        this.images = images;
    }

    /**
     * The card's image record (drive_cards), as a card synced from Drive has: what makes it a card of the catalog, the
     * deck builder and the simulator. Its image is the editor's finished card; it carries the card's code and name.
     */
    void register(Card card) {
        String id = DriveCard.WEB_PREFIX + card.getId();
        // A card that has its image from Drive keeps it as its only record (a second one would list it twice), now showing
        // the editor's render: decks and collections point to that record
        List<DriveCard> drive = driveImages(card);
        if (!drive.isEmpty()) {
            images.deleteById(id);
            Instant now = Instant.now();
            for (DriveCard d : drive) {
                d.setRenderOf(card.getId());
                d.setTimeStamp(now);
            }
            images.saveAll(drive);
            return;
        }
        DriveCard image = new DriveCard(id, null, card.getCardName(), card.getCardNumber(),
                card.getColorIdentity(), card.getEdition(), card.getSubEdition(), Instant.now(), DriveCard.WEB);
        images.save(image);
    }

    /** The Drive image records at the card's code (edition, number, sub-edition and color). */
    private List<DriveCard> driveImages(Card card) {
        if (card.getEdition() == null || card.getCardNumber() == null) return List.of();
        return images.findByEditionAndNumber(card.getEdition(), card.getCardNumber()).stream()
                .filter(d -> d.getId() != null && !d.getId().startsWith(DriveCard.WEB_PREFIX))
                .filter(d -> java.util.Objects.equals(blankToNull(d.getSubEdition()), blankToNull(card.getSubEdition()))
                        && d.getColorIdentity() != null && d.getColorIdentity().equalsIgnoreCase(card.getColorIdentity()))
                .toList();
    }

    /** Drops a stored file unless another card still points to it (a reprint made as a copy shares its files). */
    private void dropUnlessShared(String key, String cardId) {
        if (key == null) return;
        if (cards.existsByArtKeyAndIdNot(key, cardId) || cards.existsByRenderKeyAndIdNot(key, cardId)) return;
        store.delete(key);
    }

    /** A stored file, or empty when it is gone (a missing file is "no image", not an error). */
    private Optional<byte[]> read(String key) {
        try {
            return Optional.of(store.get(key));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * Stores a new art for the card and drops the one it had: the card after it, empty if none. A different picture
     * leaves its creature's cut-out behind (it was cut from the old one); the same picture again keeps it.
     */
    public Optional<Card> set(String cardId, byte[] bytes) {
        String type = mediaType(bytes);
        if (type == null) throw new IllegalArgumentException("El archivo no es una imagen JPG, PNG o WebP");
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("La imagen pesa más de 25 MB");
        return cards.findById(cardId).map(card -> {
            String old = card.getArtKey();
            boolean same = old != null && read(old).map(b -> Arrays.equals(b, bytes)).orElse(false);
            String key = "card-art/" + cardId + "/" + Instant.now().toEpochMilli() + "." + type.substring(6);
            store.put(key, bytes, type);
            card.setArtKey(key);
            card.setArtType(type);
            String creature = same ? null : card.getCreatureKey();
            if (creature != null) card.setCreatureKey(null);
            Card saved = cards.save(card);
            dropUnlessShared(old, cardId);
            if (creature != null) store.delete(creature);
            return saved;
        });
    }

    /** Stores the finished card drawn by the editor (a cache of the document and its art) and drops the one before. */
    public Optional<Card> setRender(String cardId, byte[] bytes) {
        String type = mediaType(bytes);
        if (type == null) throw new IllegalArgumentException("El archivo no es una imagen JPG, PNG o WebP");
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("La imagen pesa más de 25 MB");
        return cards.findById(cardId).map(card -> {
            String old = card.getRenderKey();
            String key = "card-render/" + cardId + "/" + Instant.now().toEpochMilli() + "." + type.substring(6);
            store.put(key, bytes, type);
            card.setRenderKey(key);
            Card saved = cards.save(card);
            dropUnlessShared(old, cardId);
            register(saved);
            return saved;
        });
    }

    /** Where the card shines (see Card.shineKey): a PNG, replacing the one before. */
    public Optional<Card> setShine(String cardId, byte[] bytes) {
        if (!"image/png".equals(mediaType(bytes))) throw new IllegalArgumentException("El brillo de la carta debe ser un PNG");
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("La imagen pesa más de 25 MB");
        return cards.findById(cardId).map(card -> {
            String old = card.getShineKey();
            String key = "card-shine/" + cardId + "/" + Instant.now().toEpochMilli() + ".png";
            store.put(key, bytes, "image/png");
            card.setShineKey(key);
            Card saved = cards.save(card);
            if (old != null) store.delete(old);
            return saved;
        });
    }

    /** The card's creature cut out of its illustration (see Card.creatureKey): a PNG, replacing the one before. */
    public Optional<Card> setCreature(String cardId, byte[] bytes) {
        if (!"image/png".equals(mediaType(bytes))) throw new IllegalArgumentException("El recorte de la criatura debe ser un PNG");
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("La imagen pesa más de 25 MB");
        return cards.findById(cardId).map(card -> {
            String old = card.getCreatureKey();
            String key = "card-creature/" + cardId + "/" + Instant.now().toEpochMilli() + ".png";
            store.put(key, bytes, "image/png");
            card.setCreatureKey(key);
            Card saved = cards.save(card);
            if (old != null) store.delete(old);
            return saved;
        });
    }

    public Optional<Art> getCreature(String cardId) {
        return cards.findById(cardId)
                .filter(c -> c.getCreatureKey() != null)
                .flatMap(c -> read(c.getCreatureKey()).map(bytes -> new Art(bytes, "image/png")));
    }

    /** The card no longer has its creature cut out. */
    public Optional<Card> removeCreature(String cardId) {
        return cards.findById(cardId).map(card -> {
            String old = card.getCreatureKey();
            card.setCreatureKey(null);
            Card saved = cards.save(card);
            if (old != null) store.delete(old);
            return saved;
        });
    }

    public Optional<Art> getShine(String cardId) {
        return cards.findById(cardId)
                .filter(c -> c.getShineKey() != null)
                .flatMap(c -> read(c.getShineKey()).map(bytes -> new Art(bytes, "image/png")));
    }

    public Optional<Art> getRender(String cardId) {
        return cards.findById(cardId)
                .filter(c -> c.getRenderKey() != null)
                .flatMap(c -> read(c.getRenderKey())
                        .map(bytes -> new Art(bytes, "image/" + c.getRenderKey().substring(c.getRenderKey().lastIndexOf('.') + 1))));
    }

    /** The card loses its art, and its creature's cut-out with it (cut from that art). */
    public Optional<Card> remove(String cardId) {
        return cards.findById(cardId).map(card -> {
            dropUnlessShared(card.getArtKey(), cardId);
            String creature = card.getCreatureKey();
            card.setArtKey(null);
            card.setArtType(null);
            card.setCreatureKey(null);
            Card saved = cards.save(card);
            if (creature != null) store.delete(creature);
            return saved;
        });
    }

    public Optional<Art> get(String cardId) {
        return cards.findById(cardId)
                .filter(c -> c.getArtKey() != null)
                .flatMap(c -> read(c.getArtKey()).map(bytes -> new Art(bytes, c.getArtType())));
    }

    /** The image's media type from its first bytes: image/jpeg, image/png or image/webp; null for anything else. */
    static String mediaType(byte[] b) {
        if (b == null || b.length < 12) return null;
        if ((b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) return "image/jpeg";
        if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }
}
