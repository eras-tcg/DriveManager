package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.simulator.engine.CardEffects;
import com.drive.drive_manager.simulator.engine.CardKeys;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Card names that do not match between the metadata and the programmed cards (eras-engine/cards). The engine finds a card's
 * effects by its name, accents and hyphens aside (CardKeys): a metadata name written otherwise still plays, but it is a name to fix
 * (Drive's web export turns every space into a hyphen, and names typed from the file lose the real ones). A card file whose name no
 * metadata card has is a card the engine never plays.
 */
@Service
public class CardNameCheck {

    private final CardRepository cards;

    public CardNameCheck(CardRepository cards) {
        this.cards = cards;
    }

    /** A metadata name that finds its card file only by its key: it should be written as the file (the printed name). */
    public record NameToFix(String edition, String code, String metadataName, String programmedName) {}

    /**
     * @param namesToFix         metadata names that differ from their card file in accents, hyphens or spaces
     * @param scriptsWithoutCard card files no metadata card names (lower case, as the file has it)
     */
    public record Report(List<NameToFix> namesToFix, List<String> scriptsWithoutCard) {}

    public Report check() {
        return check(cards.findAll(), CardEffects.programmedNames());
    }

    static Report check(List<Card> metadata, Collection<String> programmed) {
        Map<String, String> byKey = new HashMap<>();
        for (String name : programmed) byKey.put(CardKeys.key(name), name);
        List<NameToFix> toFix = new ArrayList<>();
        Set<String> named = new HashSet<>();
        for (Card card : metadata) {
            if (card.getCardName() == null || card.getCardName().isBlank()) continue;
            String plain = card.getCardName().trim().toLowerCase(Locale.ROOT);
            String file = byKey.get(CardKeys.key(plain));
            if (file == null) continue;
            named.add(file);
            if (!file.equals(plain)) toFix.add(new NameToFix(card.getEdition(), codeOf(card), card.getCardName(), file));
        }
        toFix.sort(Comparator.comparing(NameToFix::code));
        List<String> orphans = programmed.stream().filter(n -> !named.contains(n)).sorted().toList();
        return new Report(toFix, orphans);
    }

    /** The printed code: E1.2-B05. */
    static String codeOf(Card c) {
        String edition = c.getEdition() + (c.getSubEdition() == null || c.getSubEdition().isBlank() ? "" : "." + c.getSubEdition());
        return edition + "-" + c.getColorIdentity() + (c.getCardNumber() == null ? "?" : String.format("%02d", c.getCardNumber()));
    }
}
