package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * The real name of a card image. Drive's .jpg files are exported with every space turned into a hyphen
 * ("E2-B05-Roto-G0.jpg"), so the file name cannot tell "Roto-G0" from "Roto G0": the card's metadata, typed from
 * the printed card, is the name when it is the same but for hyphens and spaces. A name that really differs is left
 * alone: metadata left from another card of the slot must not rename the image. The image and its metadata are
 * joined by edition, number, sub-edition and color, never by name, so changing it does not break that link.
 */
@Service
public class CardNames {

    private final CardRepository cardRepository;
    private final DriveCardRepository driveCardRepository;

    public CardNames(CardRepository cardRepository, DriveCardRepository driveCardRepository) {
        this.cardRepository = cardRepository;
        this.driveCardRepository = driveCardRepository;
    }

    /** The metadata's name for this image when it only adds the hyphens; otherwise the name read from its file. */
    public String nameFor(String edition, Integer number, String subEdition, String color, String fileName) {
        if (edition == null || number == null) return fileName;
        return cardRepository.findByEdition(edition).stream()
                .filter(c -> sameImage(c, edition, number, subEdition, color))
                .map(Card::getCardName)
                .filter(n -> n != null && !n.isBlank() && (fileName == null || sameButHyphens(n, fileName)))
                .findFirst()
                .orElse(fileName);
    }

    /** After metadata is saved: its images take its name. Returns how many changed. */
    public int syncImages(Card meta) {
        if (meta == null || meta.getCardName() == null || meta.getCardName().isBlank()
                || meta.getEdition() == null || meta.getCardNumber() == null) return 0;
        List<DriveCard> changed = driveCardRepository.findByEditionAndNumber(meta.getEdition(), meta.getCardNumber()).stream()
                .filter(d -> sameImage(meta, d.getEdition(), d.getNumber(), d.getSubEdition(), d.getColorIdentity()))
                .filter(d -> !meta.getCardName().equals(d.getName()))
                .filter(d -> d.getName() == null || sameButHyphens(meta.getCardName(), d.getName()))
                .toList();
        changed.forEach(d -> d.setName(meta.getCardName()));
        driveCardRepository.saveAll(changed);
        return changed.size();
    }

    static boolean sameImage(Card meta, String edition, Integer number, String subEdition, String color) {
        return Objects.equals(meta.getEdition(), edition)
                && Objects.equals(meta.getCardNumber(), number)
                && Objects.equals(blankToNull(meta.getSubEdition()), blankToNull(subEdition))
                && (meta.getColorIdentity() == null || color == null || meta.getColorIdentity().equalsIgnoreCase(color));
    }

    /** "Roto-G0" and "Roto G0": the same name once hyphens and spaces are alike. */
    static boolean sameButHyphens(String a, String b) {
        return plain(a).equals(plain(b));
    }

    private static String plain(String s) {
        return s.replaceAll("[-\\s]+", " ").trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
