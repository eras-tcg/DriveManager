package com.drive.drive_manager.service;

import com.drive.drive_manager.controller.DeckListController;
import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.dto.EditionMetadata;
import com.drive.drive_manager.repository.DeckListRepository;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A structure deck a player gets (bought in the shop, the welcome one at price 0) goes into their decks: a private
 * deck (only they see it) with the cards they got, named after the structure's public deck. It is the system's doing,
 * not a deck the player built, so it does not count for the "Arma tu mazo" welcome step (no DECK_SAVED).
 */
@Service
public class StructureDeckService {

    private final DeckListRepository decks;
    private final MongoTemplate mongo;

    public StructureDeckService(DeckListRepository decks, MongoTemplate mongo) {
        this.decks = decks;
        this.mongo = mongo;
    }

    /**
     * Saves the player's private deck of `edition`'s structure with `cards`, unless one of their decks is already
     * named after it; returns their deck of it.
     */
    public DeckList giveTo(String userId, EditionMetadata edition, List<String> cards) {
        String editionId = edition.getEditionId();
        List<DeckList> mine = decks.findByUserId(userId);
        for (DeckList d : mine) {
            if (CardPoolService.namedAfter(d.getDeckName(), editionId)) return d;
        }
        DeckList structure = decks.findPublicDecks().stream()
                .filter(d -> CardPoolService.namedAfter(d.getDeckName(), editionId))
                .findFirst().orElse(null);
        DeckList deck = new DeckList();
        deck.setDeckName(deckName(structure, edition));
        deck.setCards(new ArrayList<>(cards));
        deck.setDeckImage(structure == null ? null : structure.getDeckImage());
        deck.setUserId(userId);
        deck.setUsername(username(userId));
        deck.setPrivateDeck(true);
        deck.setCreatedAt(Instant.now());
        return decks.save(deck);
    }

    /**
     * The deck's name: the structure's public deck's ("ST2 Habitantes de la tierra mágica"), else its code and name,
     * cut at a word to the longest a deck may have (its code always first, so it is still found as that structure).
     */
    static String deckName(DeckList structure, EditionMetadata edition) {
        String name = structure != null && structure.getDeckName() != null && !structure.getDeckName().isBlank()
                ? structure.getDeckName().trim()
                : (edition.getEditionId() + (edition.getEditionName() == null ? "" : " " + edition.getEditionName())).trim();
        return wholeWords(name, DeckListController.MAX_NAME_LENGTH);
    }

    /** `text` cut to `max` characters without leaving half a word. */
    static String wholeWords(String text, int max) {
        if (text.length() <= max) return text;
        String cut = text.substring(0, max);
        int space = cut.lastIndexOf(' ');
        return (text.charAt(max) == ' ' || space <= 0 ? cut : cut.substring(0, space)).stripTrailing();
    }

    /** The account's username (UsersService's "users" collection), null when it is not found. */
    private String username(String userId) {
        Query query = new Query(Criteria.where("_id").is(ObjectId.isValid(userId) ? new ObjectId(userId) : userId));
        query.fields().include("username");
        Document user = mongo.findOne(query, Document.class, "users");
        return user == null ? null : user.getString("username");
    }
}
