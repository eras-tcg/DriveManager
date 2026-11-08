package com.drive.drive_manager.simulator.offline;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.repository.DeckListRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.service.CollectionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The decks an offline game may have used, as the server has them, made into LocalMatch's decks to play the game again:
 * the metadata of every card is the server's, never the site's. A deck is legal when it is
 * <ul>
 *   <li>a starter deck (the engine's {@code eras/starter-decks.json}, what the site offers offline: "deck-st1"…), its
 *       cards in the file's order, each the starter's card itself or a drive card of the same name and edition (the site
 *       gives a starter's card the catalog's id when it has the catalog); or</li>
 *   <li>one of the player's own saved decks, exactly as it is saved now (the same cards in the same order), and, for the
 *       player's own seat, with every copy in their collection (what a game against the bot on the server demands:
 *       {@code DeckChoice}'s refusal). The bot plays any of those without a collection.</li>
 * </ul>
 * Each card is filled from the server's data as {@code DeckLoader.slots} fills a room's: the drive card's image and the
 * metadata of its name (case aside) and edition.
 */
@Component
public class OfflineDecks {

    /** Where the engine's jar keeps the starter decks (LocalMatch's form: {"decks": {"deck-st1": {"deckName", "metadata"}}}). */
    static final String STARTERS = "/eras/starter-decks.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DeckListRepository decks;
    private final DriveCardRepository driveCards;
    private final CardRepository cards;
    /** The players' collections (none in tests: any deck of the player is played). */
    private final CollectionService collections;
    private final String r2PublicUrl;
    private final Map<String, JsonNode> starters;

    @Autowired
    public OfflineDecks(DeckListRepository decks, DriveCardRepository driveCards, CardRepository cards,
                        ObjectProvider<CollectionService> collections, @Value("${r2.public-url:}") String r2PublicUrl) {
        this(decks, driveCards, cards, collections.getIfAvailable(), r2PublicUrl, readStarters());
    }

    OfflineDecks(DeckListRepository decks, DriveCardRepository driveCards, CardRepository cards, CollectionService collections,
                 String r2PublicUrl, Map<String, JsonNode> starters) {
        this.decks = decks;
        this.driveCards = driveCards;
        this.cards = cards;
        this.collections = collections;
        this.r2PublicUrl = r2PublicUrl == null ? "" : r2PublicUrl;
        this.starters = starters;
    }

    /** A deck made for LocalMatch ({@code spec}), or why it may not be played ({@code refusal}). */
    public record Made(ObjectNode spec, String refusal) {
        static Made refused(String why) {
            return new Made(null, why);
        }
    }

    /**
     * The deck {@code deckId} as the player used it ({@code cards}: its card ids, one per copy, as the site had them),
     * for the player's seat or the bot's ({@code forBot}).
     */
    public Made make(String userId, String deckId, List<String> cards, boolean forBot) {
        if (cards == null || cards.isEmpty()) return Made.refused("La partida no dice con qué cartas se jugó el mazo " + deckId);
        JsonNode starter = starters.get(deckId);
        if (starter != null) return starter(starter, cards);
        DeckList deck = decks.findById(deckId).orElse(null);
        if (deck == null || !Objects.equals(userId, deck.getUserId())) return Made.refused("El mazo " + deckId + " no es uno de tus mazos");
        if (!cards.equals(deck.getCards())) return Made.refused("El mazo " + deck.getDeckName() + " cambió desde que jugaste esa partida");
        if (!forBot && collections != null && collections.missing(userId, deck.getCards()) > 0)
            return Made.refused("No puedes jugar el mazo " + deck.getDeckName() + ": te faltan cartas de tu colección");
        return new Made(userDeck(deck), null);
    }

    /** A starter deck: the file's cards in order (×3, the Iniciador ×1), each as the site named it. */
    private Made starter(JsonNode starter, List<String> ids) {
        ArrayNode out = JSON.createArrayNode();
        int at = 0;
        Map<String, DriveCard> drives = null;
        for (JsonNode meta : starter.get("metadata")) {
            int copies = meta.path("starter").asBoolean(false) ? 1 : 3;
            if (at + copies > ids.size()) return Made.refused("El mazo " + starter.path("deckName").asString() + " no es el de inicio");
            String id = ids.get(at);
            for (int i = 1; i < copies; i++) if (!id.equals(ids.get(at + i))) return Made.refused("El mazo " + starter.path("deckName").asString() + " no es el de inicio");
            at += copies;
            if (!id.equals(meta.path("id").asString(null))) {
                // The catalog's drive card of the same name and edition
                if (drives == null) drives = driveCards.findAllById(new ArrayList<>(new java.util.LinkedHashSet<>(ids))).stream()
                        .collect(Collectors.toMap(DriveCard::getId, d -> d, (a, b) -> a));
                DriveCard drive = drives.get(id);
                if (drive == null || drive.getName() == null || !drive.getName().toLowerCase(Locale.ROOT).equals(meta.path("cardName").asString("").toLowerCase(Locale.ROOT))
                        || !Objects.equals(drive.getEdition(), meta.path("edition").asString(null)))
                    return Made.refused("El mazo " + starter.path("deckName").asString() + " no es el de inicio");
            }
            ObjectNode entry = out.addObject();
            entry.set("card", meta);
            entry.put("copies", copies);
            entry.put("cardId", id);
        }
        if (at != ids.size()) return Made.refused("El mazo " + starter.path("deckName").asString() + " no es el de inicio");
        ObjectNode spec = JSON.createObjectNode();
        spec.put("deckName", starter.path("deckName").asString());
        spec.set("cards", out);
        return new Made(spec, null);
    }

    /** A saved deck as DeckLoader.slots plays it: runs of the same card are one entry, each with the server's metadata. */
    private ObjectNode userDeck(DeckList deck) {
        List<String> fileIds = deck.getCards();
        Map<String, DriveCard> drives = driveCards.findAllById(new ArrayList<>(new java.util.LinkedHashSet<>(fileIds))).stream()
                .collect(Collectors.toMap(DriveCard::getId, d -> d, (a, b) -> a));
        List<String> editions = drives.values().stream().map(DriveCard::getEdition).filter(Objects::nonNull).distinct().toList();
        Map<String, Card> metas = editions.isEmpty() ? Map.of() : cards.findByEditionIn(new ArrayList<>(editions)).stream()
                .filter(c -> c.getCardName() != null)
                .collect(Collectors.toMap(c -> c.getCardName().toLowerCase(Locale.ROOT) + ":" + c.getEdition(), c -> c, (a, b) -> a));
        ArrayNode out = JSON.createArrayNode();
        ObjectNode last = null;
        for (String fileId : fileIds) {
            if (last != null && fileId.equals(last.path("cardId").asString(null))) {
                last.put("copies", last.path("copies").asInt() + 1);
                continue;
            }
            DriveCard drive = drives.get(fileId);
            Card meta = drive == null || drive.getName() == null || drive.getEdition() == null ? null
                    : metas.get(drive.getName().toLowerCase(Locale.ROOT) + ":" + drive.getEdition());
            ObjectNode card;
            if (meta != null) {
                card = JSON.valueToTree(meta);
            } else {
                card = JSON.createObjectNode();
                card.put("id", fileId);
                card.put("cardName", drive == null ? null : drive.getName());
            }
            last = out.addObject();
            last.set("card", card);
            last.put("copies", 1);
            last.put("cardId", fileId);
            last.put("imageUrl", drive == null ? null : drive.imageUrl(r2PublicUrl));
        }
        ObjectNode spec = JSON.createObjectNode();
        spec.put("deckName", deck.getDeckName());
        spec.set("cards", out);
        return spec;
    }

    /** The engine's starter decks, by deck id. */
    static Map<String, JsonNode> readStarters() {
        try (InputStream in = OfflineDecks.class.getResourceAsStream(STARTERS)) {
            if (in == null) return Map.of();
            JsonNode file = JSON.readTree(in);
            Map<String, JsonNode> out = new LinkedHashMap<>();
            file.path("decks").properties().forEach(e -> out.put(e.getKey(), e.getValue()));
            return Collections.unmodifiableMap(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
