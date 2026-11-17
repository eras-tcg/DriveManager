package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moves cards to other slots (edition, sub-edition and number; the color stays) all at once: a chain where each card
 * takes the slot of the next one is checked as a whole, so no two cards ever share a code. Each card's image records
 * (Drive's and the editor's "web-" one) move with it, and a Drive file name takes the new code, since the site drops
 * an image whose file name carries another one.
 */
@Service
public class CardSlots {

    /** One card and the slot it goes to. */
    public record Move(String id, String edition, String subEdition, Integer cardNumber) {}

    /** A move that cannot be made; the message says why, for the person moving cards. */
    public static class InvalidMove extends RuntimeException {
        public InvalidMove(String message) {
            super(message);
        }
    }

    private static final Pattern FILE_CODE = Pattern.compile("^([A-Za-z]+\\d+)(?:_(\\d+))?-([A-Za-z]+)(\\d+)(-.*)$");

    private final CardRepository cards;
    private final DriveCardRepository images;

    public CardSlots(CardRepository cards, DriveCardRepository images) {
        this.cards = cards;
        this.images = images;
    }

    /** Applies the moves and returns the cards as they are now. */
    public List<Card> move(List<Move> moves) {
        if (moves == null || moves.isEmpty()) return List.of();
        Map<String, Card> moving = new HashMap<>();
        Map<String, Card> before = new HashMap<>();
        for (Move m : moves) {
            if (m.id() == null || m.edition() == null || m.edition().isBlank() || m.cardNumber() == null || m.cardNumber() < 1)
                throw new InvalidMove("Falta la carta, la edición o el número");
            if (moving.containsKey(m.id())) throw new InvalidMove("Una carta se mueve dos veces");
            Card c = cards.findById(m.id()).orElseThrow(() -> new InvalidMove("No existe la carta " + m.id()));
            before.put(m.id(), copyKey(c));
            c.setEdition(m.edition().trim());
            c.setSubEdition(blank(m.subEdition()) ? null : m.subEdition().trim());
            c.setCardNumber(m.cardNumber());
            moving.put(m.id(), c);
        }
        // The end state: the moved cards' new codes against each other and against every card that stays
        Set<String> codes = new HashSet<>();
        for (Card c : moving.values()) {
            if (!codes.add(key(c))) throw new InvalidMove("Dos cartas terminan en " + CardService.code(c));
        }
        Set<String> editions = new HashSet<>();
        moving.values().forEach(c -> editions.add(c.getEdition()));
        for (String e : editions) {
            for (Card other : cards.findByEdition(e)) {
                if (moving.containsKey(other.getId())) continue;
                if (codes.contains(key(other)))
                    throw new InvalidMove("El lugar " + CardService.code(other) + " ya tiene a " + other.getCardName());
            }
        }
        // Image records found by the old codes before anything is saved
        List<DriveCard> changed = new ArrayList<>();
        for (Card c : moving.values()) {
            Card old = before.get(c.getId());
            for (DriveCard d : imagesOf(old, c.getId())) {
                d.setEdition(c.getEdition());
                d.setSubEdition(c.getSubEdition());
                d.setNumber(c.getCardNumber());
                d.setFileName(renamed(d.getFileName(), c));
                changed.add(d);
            }
        }
        List<Card> saved = cards.saveAll(moving.values());
        images.saveAll(changed);
        return saved;
    }

    /** The card's images: the editor's record and the Drive ones at its old code. */
    private List<DriveCard> imagesOf(Card old, String id) {
        List<DriveCard> out = new ArrayList<>();
        images.findById(DriveCard.WEB_PREFIX + id).ifPresent(out::add);
        if (old.getEdition() != null && old.getCardNumber() != null) {
            for (DriveCard d : images.findByEditionAndNumber(old.getEdition(), old.getCardNumber())) {
                if (d.getId().startsWith(DriveCard.WEB_PREFIX)) continue;
                if (Objects.equals(blankToNull(d.getSubEdition()), blankToNull(old.getSubEdition()))
                        && d.getColorIdentity() != null && d.getColorIdentity().equalsIgnoreCase(old.getColorIdentity()))
                    out.add(d);
            }
        }
        return out;
    }

    /** "E1-B03-Ephos.jpg" moved to E1.1, number 5 → "E1_1-B05-Ephos.jpg"; a name without a code is kept. */
    static String renamed(String fileName, Card c) {
        if (fileName == null) return null;
        Matcher m = FILE_CODE.matcher(fileName);
        if (!m.matches()) return fileName;
        String sub = blank(c.getSubEdition()) ? "" : "_" + c.getSubEdition();
        return c.getEdition() + sub + "-" + c.getColorIdentity().toUpperCase() + String.format("%02d", c.getCardNumber()) + m.group(5);
    }

    private static String key(Card c) {
        return c.getEdition() + "|" + Objects.toString(blankToNull(c.getSubEdition()), "") + "|"
                + Objects.toString(c.getColorIdentity(), "").toUpperCase() + "|" + c.getCardNumber();
    }

    private static Card copyKey(Card c) {
        Card k = new Card();
        k.setEdition(c.getEdition());
        k.setSubEdition(c.getSubEdition());
        k.setColorIdentity(c.getColorIdentity());
        k.setCardNumber(c.getCardNumber());
        return k;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return blank(s) ? null : s;
    }
}
