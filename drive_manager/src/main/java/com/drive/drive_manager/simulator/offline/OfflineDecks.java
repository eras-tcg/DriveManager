package com.drive.drive_manager.simulator.offline;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.repository.DeckListRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.service.CollectionService;
import com.drive.drive_manager.simulator.room.CardMeta;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.DeckRules;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The decks an offline game may have used, as the server has them, made into LocalMatch's decks to play the game again:
 * the record gives each deck as its card ids (one per copy, in the deck's order) and the deck is made again from those
 * ids with the server's data only (its drive cards and card metadata, never the site's). A deck is accepted when it is
 * <ul>
 *   <li>legal ({@link DeckRules}: the deck builder's construction rules and what a room demands of a deck: at most 100
 *       cards and 4 copies of a name, every card known to the server and run by the engine, an Iniciador); and</li>
 *   <li>for the player's own seat, owned: every copy in their collection (what a game against the bot on the server
 *       demands: {@code DeckChoice}'s refusal through {@code RoomDecks}). The bot plays any legal deck: it owns none.</li>
 * </ul>
 * The deck does not have to be one of the player's saved decks, nor match one as it is saved now: a deck edited or
 * deleted since the game is still the deck the game was played with, and it is checked as such.
 * A starter deck (the engine's {@code eras/starter-decks.json}, what the site offers offline: "deck-st1"…) must be the
 * file's cards in its order, each the starter's card itself or a drive card of the same name and edition (the site
 * gives a starter's card the catalog's id when it has the catalog), and is owned as any other deck: the server has no
 * starter decks of its own, and online every deck a player plays against the bot needs its copies in the collection, so
 * here too (a copy of a starter's card counts whichever printing of that name and edition the player owns).
 * Each card is filled from the server's data as {@code DeckLoader.slots} fills a room's: the drive card's image and the
 * metadata of its name (case aside) and edition.
 */
@Component
public class OfflineDecks {

    /** Where the engine's jar keeps the starter decks (LocalMatch's form: {"decks": {"deck-st1": {"deckName", "metadata"}}}). */
    static final String STARTERS = "/eras/starter-decks.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** The name of a deck the player no longer has saved (only the messages and the history show it). */
    static final String UNSAVED_DECK = "Mazo";

    private final DeckListRepository decks;
    private final DriveCardRepository driveCards;
    private final CardRepository cards;
    /** The players' collections (none in tests: any legal deck is played). */
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
        if (cards.size() > DeckRules.MAX_CARDS) return Made.refused("El mazo tiene más de " + DeckRules.MAX_CARDS + " cartas");
        if (cards.stream().anyMatch(id -> id == null || id.isBlank())) return Made.refused("El mazo tiene una carta sin id");
        JsonNode starter = starters.get(deckId);
        return starter != null ? starter(userId, starter, cards, forBot) : listed(userId, deckId, cards, forBot);
    }

    /** A starter deck: the file's cards in order (×3, the Iniciador ×1), each as the site named it. */
    private Made starter(String userId, JsonNode starter, List<String> ids, boolean forBot) {
        String name = starter.path("deckName").asString();
        Made notIt = Made.refused("El mazo " + name + " no es el de inicio");
        ArrayNode out = JSON.createArrayNode();
        List<CardSlot> slots = new ArrayList<>();
        int at = 0;
        Map<String, DriveCard> drives = null;
        for (JsonNode meta : starter.get("metadata")) {
            int copies = meta.path("starter").asBoolean(false) ? 1 : 3;
            if (at + copies > ids.size()) return notIt;
            String id = ids.get(at);
            for (int i = 1; i < copies; i++) if (!id.equals(ids.get(at + i))) return notIt;
            at += copies;
            if (!id.equals(meta.path("id").asString(null))) {
                // The catalog's drive card of the same name and edition
                if (drives == null) drives = driveCards.findAllById(new ArrayList<>(new LinkedHashSet<>(ids))).stream()
                        .collect(Collectors.toMap(DriveCard::getId, d -> d, (a, b) -> a));
                DriveCard drive = drives.get(id);
                if (drive == null || !sameCard(drive.getName(), drive.getEdition(), meta)) return notIt;
            }
            Card card = JSON.treeToValue(meta, Card.class);
            for (int i = 0; i < copies; i++) slots.add(slot(id, card));
            ObjectNode entry = out.addObject();
            entry.set("card", meta);
            entry.put("copies", copies);
            entry.put("cardId", id);
        }
        if (at != ids.size()) return notIt;
        String illegal = DeckRules.refusal(name, slots);
        if (illegal != null) return Made.refused(illegal);
        if (!forBot && missingStarterCopies(userId, starter) > 0) return notOwned(name);
        ObjectNode spec = JSON.createObjectNode();
        spec.put("deckName", name);
        spec.set("cards", out);
        return new Made(spec, null);
    }

    /**
     * Copies of the starter deck the player does not own: each card's copies against every printing of its name and
     * edition in their collection (0 without a collection to check).
     */
    private int missingStarterCopies(String userId, JsonNode starter) {
        if (collections == null) return 0;
        Map<String, Map<String, Integer>> owned = collections.of(userId).cards();
        Map<String, List<DriveCard>> byEdition = new HashMap<>();
        int missing = 0;
        for (JsonNode meta : starter.get("metadata")) {
            int copies = meta.path("starter").asBoolean(false) ? 1 : 3;
            String edition = meta.path("edition").asString(null);
            List<DriveCard> printings = edition == null ? List.of() : byEdition.computeIfAbsent(edition, driveCards::findByEdition);
            int have = 0;
            for (DriveCard d : printings) {
                if (!sameCard(d.getName(), d.getEdition(), meta)) continue;
                have += owned.getOrDefault(d.getId(), Map.of()).values().stream().mapToInt(Integer::intValue).sum();
            }
            missing += Math.max(0, copies - have);
        }
        return missing;
    }

    private static boolean sameCard(String name, String edition, JsonNode meta) {
        return name != null && name.toLowerCase(Locale.ROOT).equals(meta.path("cardName").asString("").toLowerCase(Locale.ROOT))
                && Objects.equals(edition, meta.path("edition").asString(null));
    }

    /**
     * Any other deck, from its card ids alone: each a drive card of the server with its metadata, the deck legal and, for
     * the player's seat, in their collection. Its name is the saved deck's when the player still has it.
     */
    private Made listed(String userId, String deckId, List<String> fileIds, boolean forBot) {
        DeckList saved = decks.findById(deckId).orElse(null);
        String name = saved != null && (Objects.equals(userId, saved.getUserId()) || !saved.isPrivateDeck()) && saved.getDeckName() != null
                ? saved.getDeckName() : UNSAVED_DECK;
        Map<String, DriveCard> drives = driveCards.findAllById(new ArrayList<>(new LinkedHashSet<>(fileIds))).stream()
                .collect(Collectors.toMap(DriveCard::getId, d -> d, (a, b) -> a));
        List<String> editions = drives.values().stream().map(DriveCard::getEdition).filter(Objects::nonNull).distinct().toList();
        Map<String, Card> metas = editions.isEmpty() ? Map.of() : cards.findByEditionIn(new ArrayList<>(editions)).stream()
                .filter(c -> c.getCardName() != null)
                .collect(Collectors.toMap(c -> c.getCardName().toLowerCase(Locale.ROOT) + ":" + c.getEdition(), c -> c, (a, b) -> a));
        List<CardSlot> slots = new ArrayList<>();
        ArrayNode out = JSON.createArrayNode();
        ObjectNode last = null;
        for (String fileId : fileIds) {
            DriveCard drive = drives.get(fileId);
            Card meta = drive == null || drive.getName() == null || drive.getEdition() == null ? null
                    : metas.get(drive.getName().toLowerCase(Locale.ROOT) + ":" + drive.getEdition());
            if (meta == null) return Made.refused("El mazo " + name + " tiene cartas que el servidor no conoce");
            slots.add(slot(fileId, meta));
            // Runs of the same card are one entry, as DeckLoader.slots plays them
            if (last != null && fileId.equals(last.path("cardId").asString(null))) {
                last.put("copies", last.path("copies").asInt() + 1);
                continue;
            }
            last = out.addObject();
            last.set("card", JSON.valueToTree(meta));
            last.put("copies", 1);
            last.put("cardId", fileId);
            last.put("imageUrl", drive.imageUrl(r2PublicUrl));
        }
        String illegal = DeckRules.refusal(name, slots);
        if (illegal != null) return Made.refused(illegal);
        if (!forBot && collections != null && collections.missing(userId, fileIds) > 0) return notOwned(name);
        ObjectNode spec = JSON.createObjectNode();
        spec.put("deckName", name);
        spec.set("cards", out);
        return new Made(spec, null);
    }

    private static Made notOwned(String name) {
        return Made.refused("No puedes jugar el mazo " + name + ": te faltan cartas de tu colección");
    }

    /** One copy filled from the server's metadata, as a room's (what the rules check of the deck reads). */
    private static CardSlot slot(String cardId, Card meta) {
        CardSlot slot = new CardSlot();
        slot.setCardId(cardId);
        CardMeta.apply(slot, cardId, meta);
        return slot;
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
