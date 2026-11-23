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
 * codes of its cards ("ST4-R05", "E1.3-R01") and how many copies; at start they are made, or made again, in
 * {@code deck_lists} with this site's card ids, since each environment has its own. The file is the deck: a change to
 * it reaches every environment with the next deploy. A deck with a code the site does not have is left as it was
 * (logged), never saved half made. Whether the bot plays it is set on creation from the file and then kept, so a
 * simulator administrator's "Quitar del bot" lasts.
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
        int saved = 0;
        List<Listed> listed = read();
        for (Listed deck : listed) {
            try {
                if (save(deck)) saved++;
            } catch (RuntimeException e) {
                LOG.warn("Mazo del sistema {} no se pudo guardar: {}", deck.id(), e.getMessage());
            }
        }
        LOG.info("Mazos del sistema al día: {} de {}", saved, listed.size());
    }

    /**
     * One deck of the file: its id, name, cover card and cards (one code per copy, in the file's order), and where the
     * bot takes cards to vary it ({@code vary}: null when it is played as it is, the ST decks).
     */
    public record Listed(String id, String name, String cover, boolean botDeck, List<String> codes, Pool vary, String group, int order) {
        Listed(String id, String name, String cover, boolean botDeck, List<String> codes) {
            this(id, name, cover, botDeck, codes, null, null, 0);
        }
    }

    /** The cards a varied deck may take: those of some editions (every sub-edition) in a color. */
    public record Pool(List<String> editions, String color) {}

    private static volatile Map<String, Pool> pools;

    /** Where the bot takes cards to vary this system deck, or null when the deck is played as it is. */
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
                        vary.isObject() ? new Pool(editions(vary), vary.path("color").asString()) : null,
                        d.path("group").isTextual() ? d.path("group").asString() : null, out.size()));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The editions of a deck's «vary» ({"editions": [...]}, or one "edition"). */
    private static List<String> editions(JsonNode vary) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : vary.path("editions")) out.add(e.asString());
        if (out.isEmpty() && vary.path("edition").isTextual()) out.add(vary.path("edition").asString());
        return List.copyOf(out);
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
        deck.setSystemGroup(listed.group());
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
