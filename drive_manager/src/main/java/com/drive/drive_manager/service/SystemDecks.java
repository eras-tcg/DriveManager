package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.DeckListRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The decks the system proposes (public, author ERAS): {@code decks/system-decks.json} lists each one by the printed
 * codes of its cards ("ST4-R05", "E1.3-R01") and how many copies; at start, a deck the database does not have yet is
 * made in {@code deck_lists} with this site's card ids (each environment has its own). The file only seeds: once made,
 * a deck is the simulator administrators' to edit on the site (cards, name, cover; DeckListController), and the file
 * never overwrites nor deletes it. A deck with a code the site does not have is not made (logged), never half made.
 */
@Component
public class SystemDecks implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SystemDecks.class);

    /** Where the decks are listed. */
    static final String FILE = "/decks/system-decks.json";
    /** The owner of every system deck: no player is, so none may edit or delete them. */
    public static final String OWNER = "system";
    /** The author the site shows. */
    public static final String AUTHOR = "ERAS";
    /** A printed code: edition, sub-edition, color and number ("E1.3-R01", "ST4-R05"). */
    private static final Pattern CODE = Pattern.compile("^([A-Z]+\\d+)(?:\\.(\\d+))?-([A-Z])(\\d+)$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DeckListRepository decks;
    private final DriveCardRepository driveCards;
    private final String r2PublicUrl;

    public SystemDecks(DeckListRepository decks, DriveCardRepository driveCards, @Value("${r2.public-url:}") String r2PublicUrl) {
        this.decks = decks;
        this.driveCards = driveCards;
        this.r2PublicUrl = r2PublicUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        int created = 0;
        List<Listed> listed = read();
        for (Listed deck : listed) {
            if (decks.existsById(deck.id())) continue;
            try {
                if (save(deck)) created++;
            } catch (RuntimeException e) {
                LOG.warn("Mazo del sistema {} no se pudo crear: {}", deck.id(), e.getMessage());
            }
        }
        LOG.info("Mazos del sistema: {} nuevos ({} en el archivo)", created, listed.size());
    }

    /**
     * One deck of the file: its id, name, cover card and cards (one code per copy, in the file's order), and where the
     * bot takes cards to vary it ({@code vary}: null when it is played as it is, the ST decks).
     */
    public record Listed(String id, String name, String cover, boolean botDeck, List<String> codes, Pool vary, String edition, int order) {
        Listed(String id, String name, String cover, boolean botDeck, List<String> codes) {
            this(id, name, cover, botDeck, codes, null, null, 0);
        }
    }

    /**
     * The cards a varied deck may take: those of the deck's block in its color up to its edition ({@code upTo} "E1.2":
     * edition E1, sub-editions up to 2; "E1": E1 alone).
     */
    public record Pool(String edition, int maxSub, String color) {
        static Pool upTo(String upTo, String color) {
            int dot = upTo.indexOf('.');
            return dot < 0 ? new Pool(upTo, 0, color) : new Pool(upTo.substring(0, dot), Integer.parseInt(upTo.substring(dot + 1)), color);
        }
    }

    private static volatile Map<String, Pool> pools;

    /**
     * Where the bot takes cards to vary a system deck as it is now: its color (most of its cards') in its block up to
     * its edition folder ("E1.2": E1, E1.1, E1.2); null for the Structures' decks (played as they are) or no folder.
     */
    public static Pool poolOf(String systemEdition, java.util.Collection<String> cardColors) {
        if (systemEdition == null || systemEdition.isBlank() || systemEdition.startsWith("ST") || cardColors.isEmpty()) return null;
        java.util.Map<String, Long> count = new java.util.HashMap<>();
        cardColors.forEach(c -> { if (c != null) count.merge(c, 1L, Long::sum); });
        String color = count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        return color == null ? null : Pool.upTo(systemEdition, color);
    }

    /** Where the bot takes cards to vary this system deck as the file lists it, or null when it is played as it is. */
    public static Pool variedFrom(String deckId) {
        Map<String, Pool> p = pools;
        if (p == null) {
            Map<String, Pool> made = new java.util.HashMap<>();
            for (Listed d : read()) if (d.vary() != null) made.put(d.id(), d.vary());
            pools = p = made;
        }
        return deckId == null ? null : p.get(deckId);
    }

    public static List<Listed> read() {
        try (InputStream in = SystemDecks.class.getResourceAsStream(FILE)) {
            if (in == null) return List.of();
            List<Listed> out = new ArrayList<>();
            for (JsonNode d : JSON.readTree(in).path("decks")) {
                List<String> codes = new ArrayList<>();
                for (JsonNode c : d.path("cards"))
                    for (int i = 0; i < c.path("copies").asInt(); i++) codes.add(c.path("code").asString());
                JsonNode vary = d.path("vary");
                out.add(new Listed(d.path("id").asString(), d.path("name").asString(), d.path("cover").asString(),
                        d.path("botDeck").asBoolean(false), codes,
                        vary.isObject() ? Pool.upTo(vary.path("upTo").asString(), vary.path("color").asString()) : null,
                        d.path("edition").isTextual() ? d.path("edition").asString() : null, out.size()));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Makes or updates one deck; false when one of its codes is not on the site (the deck stays as it was). */
    boolean save(Listed listed) {
        List<String> ids = new ArrayList<>();
        DriveCard cover = null;
        for (String code : listed.codes()) {
            DriveCard card = card(code);
            if (card == null) {
                LOG.warn("Mazo del sistema {}: el sitio no tiene la carta {}; queda como estaba", listed.id(), code);
                return false;
            }
            ids.add(card.getId());
            if (code.equals(listed.cover())) cover = card;
        }
        DeckList existing = decks.findById(listed.id()).orElse(null);
        DeckList deck = existing != null ? existing : new DeckList();
        if (existing == null) {
            deck.setId(listed.id());
            deck.setCreatedAt(Instant.now());
            deck.setBotDeck(listed.botDeck());
        }
        deck.setDeckName(listed.name());
        deck.setCards(ids);
        deck.setDeckImage(cover == null ? null : cover.imageUrl(r2PublicUrl));
        deck.setUserId(OWNER);
        deck.setUsername(AUTHOR);
        deck.setPrivateDeck(false);
        deck.setSystemDeck(true);
        deck.setSystemEdition(listed.edition());
        deck.setSystemOrder(listed.order());
        decks.save(deck);
        return true;
    }

    /** The site's card with that printed code, or null when it has none (or more than one). */
    DriveCard card(String code) {
        Matcher m = CODE.matcher(code);
        if (!m.matches()) return null;
        String sub = m.group(2);
        String color = m.group(3);
        List<DriveCard> found = driveCards.findByEditionAndNumber(m.group(1), Integer.parseInt(m.group(4))).stream()
                .filter(c -> color.equals(c.getColorIdentity()))
                .filter(c -> Objects.equals(sub, c.getSubEdition() == null || c.getSubEdition().isBlank() ? null : c.getSubEdition()))
                .toList();
        return found.size() == 1 ? found.get(0) : null;
    }
}
