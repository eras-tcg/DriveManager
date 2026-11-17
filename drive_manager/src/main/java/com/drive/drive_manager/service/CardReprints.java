package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.repository.CardRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * A reprint: the same card in another edition's slot, as a card of its own. It copies the card's text and design and
 * gets its own copy of the art (never the original's file: replacing one would delete the other's). Its finished image
 * is drawn by the site afterwards, with the new code. Its printing's own things (deck copies, alternative arts, the
 * finished image) stay with the original.
 */
@Service
public class CardReprints {

    /** A reprint that cannot be made; the message says why. */
    public static class InvalidReprint extends RuntimeException {
        public InvalidReprint(String message) {
            super(message);
        }
    }

    private final CardRepository cards;
    private final CardArtStore store;
    private final CardService cardService;

    public CardReprints(CardRepository cards, CardArtStore store, CardService cardService) {
        this.cards = cards;
        this.store = store;
        this.cardService = cardService;
    }

    /** Creates the reprint of {@code id} at the slot (its color stays) and returns it. */
    public Card reprint(String id, String edition, String subEdition, Integer cardNumber) {
        if (edition == null || edition.isBlank() || cardNumber == null || cardNumber < 1)
            throw new InvalidReprint("Falta la edición o el número");
        Card original = cards.findById(id).orElseThrow(() -> new InvalidReprint("No existe la carta " + id));
        Card copy = new Card();
        BeanUtils.copyProperties(original, copy, "id", "artKey", "artType", "renderKey", "deckCopies", "altArts", "altImage");
        copy.setEdition(edition.trim());
        copy.setSubEdition(subEdition == null || subEdition.isBlank() ? null : subEdition.trim());
        copy.setCardNumber(cardNumber);
        try {
            cardService.checkCode(copy, null);
        } catch (CardService.CodeTaken e) {
            throw new InvalidReprint(e.getMessage());
        }
        Card saved = cards.save(copy);
        if (original.getArtKey() != null) {
            String ext = original.getArtKey().substring(original.getArtKey().lastIndexOf('.') + 1);
            String key = "card-art/" + saved.getId() + "/" + Instant.now().toEpochMilli() + "." + ext;
            store.put(key, store.get(original.getArtKey()), original.getArtType());
            saved.setArtKey(key);
            saved.setArtType(original.getArtType());
            saved = cards.save(saved);
        }
        return saved;
    }
}
