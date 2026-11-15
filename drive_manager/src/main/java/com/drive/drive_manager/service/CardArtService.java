package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.repository.CardRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
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

    public CardArtService(CardRepository cards, CardArtStore store) {
        this.cards = cards;
        this.store = store;
    }

    /** Stores a new art for the card and drops the one it had: the card after it, empty if none. */
    public Optional<Card> set(String cardId, byte[] bytes) {
        String type = mediaType(bytes);
        if (type == null) throw new IllegalArgumentException("El archivo no es una imagen JPG, PNG o WebP");
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("La imagen pesa más de 25 MB");
        return cards.findById(cardId).map(card -> {
            String old = card.getArtKey();
            String key = "card-art/" + cardId + "/" + Instant.now().toEpochMilli() + "." + type.substring(6);
            store.put(key, bytes, type);
            card.setArtKey(key);
            card.setArtType(type);
            Card saved = cards.save(card);
            if (old != null) store.delete(old);
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
            if (old != null) store.delete(old);
            return saved;
        });
    }

    public Optional<Art> getRender(String cardId) {
        return cards.findById(cardId)
                .filter(c -> c.getRenderKey() != null)
                .map(c -> new Art(store.get(c.getRenderKey()), "image/" + c.getRenderKey().substring(c.getRenderKey().lastIndexOf('.') + 1)));
    }

    public Optional<Card> remove(String cardId) {
        return cards.findById(cardId).map(card -> {
            if (card.getArtKey() != null) store.delete(card.getArtKey());
            card.setArtKey(null);
            card.setArtType(null);
            return cards.save(card);
        });
    }

    public Optional<Art> get(String cardId) {
        return cards.findById(cardId)
                .filter(c -> c.getArtKey() != null)
                .map(c -> new Art(store.get(c.getArtKey()), c.getArtType()));
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
