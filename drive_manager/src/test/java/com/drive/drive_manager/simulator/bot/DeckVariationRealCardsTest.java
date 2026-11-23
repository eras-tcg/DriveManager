package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.service.SystemDecks;
import com.drive.drive_manager.simulator.room.CardMeta;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.DeckRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bot's variation of the system decks with the real cards: a dump of the {@code cards} collection
 * ({@code -Dsystem.decks.meta=<file.json>}, {"cards": [...]}). Every variant is legal and changes something; the ST
 * decks are never varied. Prints a few variants to read.
 */
@EnabledIfSystemProperty(named = "system.decks.meta", matches = ".+")
class DeckVariationRealCardsTest {

    private static final Pattern CODE = Pattern.compile("^([A-Z]+\\d+)(?:\\.(\\d+))?-([A-Z])(\\d+)$");

    @Test
    void everyVariantOfTheE1DecksIsLegal() throws Exception {
        JsonMapper json = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        JsonNode root = json.readTree(new File(System.getProperty("system.decks.meta")));
        Map<String, Card> byCode = new HashMap<>();
        for (JsonNode n : root.path("cards")) {
            Card c = json.treeToValue(n, Card.class);
            String sub = c.getSubEdition() == null || c.getSubEdition().isBlank() ? "" : "." + c.getSubEdition();
            byCode.put(String.format("%s%s-%s%02d", c.getEdition(), sub, c.getColorIdentity(), c.getCardNumber()), c);
        }
        int[] id = {0};
        for (SystemDecks.Listed d : SystemDecks.read()) {
            SystemDecks.Pool pool = SystemDecks.variedFrom(d.id());
            if (d.id().startsWith("system-st")) {
                assertThat(pool).as(d.id()).isNull();
                continue;
            }
            assertThat(pool).as(d.id()).isNotNull();
            List<CardSlot> deck = new ArrayList<>();
            for (String code : d.codes()) deck.add(slot(code, byCode.get(code), id));
            List<CardSlot> catalog = new ArrayList<>();
            byCode.forEach((code, c) -> {
                Matcher m = CODE.matcher(code);
                if (m.matches() && m.group(1).equals(pool.edition()) && m.group(3).equals(pool.color())) catalog.add(slot(code, c, id));
            });
            for (int seed = 0; seed < 100; seed++) {
                DeckVariation.Result r = DeckVariation.vary(d.name(), deck, catalog, new Random(seed));
                assertThat(r.changes()).as("%s seed %d", d.id(), seed).isNotEmpty();
                assertThat(DeckRules.refusal(d.name(), r.cards())).as("%s seed %d", d.id(), seed).isNull();
                if (seed < 3) System.out.println(d.name() + " #" + seed + ": " + String.join("; ", r.changes()));
            }
        }
    }

    private static CardSlot slot(String code, Card meta, int[] id) {
        CardSlot s = new CardSlot();
        s.setInstanceId("i" + (id[0]++));
        s.setCardId(code);
        s.setCardName(meta.getCardName());
        CardMeta.apply(s, code, meta);
        return s;
    }
}
