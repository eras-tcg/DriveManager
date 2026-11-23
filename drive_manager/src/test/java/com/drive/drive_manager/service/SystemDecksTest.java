package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.DeckListRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.simulator.engine.CardEffects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemDecksTest {

    @Test
    void everyListedDeckHasFiftyCardsAndItsCover() {
        List<SystemDecks.Listed> decks = SystemDecks.read();
        assertThat(decks).hasSize(8);
        assertThat(decks).extracting(SystemDecks.Listed::id).doesNotHaveDuplicates();
        for (SystemDecks.Listed d : decks) {
            assertThat(d.codes()).as(d.id()).hasSize(50);
            assertThat(d.codes()).as(d.id()).contains(d.cover());
            Map<String, Integer> copies = new HashMap<>();
            d.codes().forEach(c -> copies.merge(c, 1, Integer::sum));
            assertThat(copies.values()).as(d.id()).allMatch(n -> n <= 4);
        }
    }

    @Test
    void aDeckIsMadeWithTheSiteCardIdsAndOwnedByTheSystem() {
        DeckListRepository decks = mock(DeckListRepository.class);
        DriveCardRepository driveCards = mock(DriveCardRepository.class);
        when(decks.findById(anyString())).thenReturn(Optional.empty());
        when(driveCards.findByEditionAndNumber(anyString(), anyInt())).thenAnswer(inv -> {
            String edition = inv.getArgument(0);
            int number = inv.getArgument(1);
            // Two printings with the same number: only the one with the code's sub-edition and color is taken
            return List.of(new DriveCard(edition + "-" + number + "-a", null, "x", number, "R", edition, "3", null),
                    new DriveCard(edition + "-" + number + "-b", null, "x", number, "B", edition, null, null));
        });
        SystemDecks system = new SystemDecks(decks, driveCards, "https://r2");

        assertThat(system.save(new SystemDecks.Listed("system-x", "X", "E1.3-R01", true, List.of("E1.3-R01", "E1.3-R01", "E1-B02")))).isTrue();

        ArgumentCaptor<DeckList> saved = ArgumentCaptor.forClass(DeckList.class);
        verify(decks).save(saved.capture());
        DeckList deck = saved.getValue();
        assertThat(deck.getCards()).containsExactly("E1-1-a", "E1-1-a", "E1-2-b");
        assertThat(deck.getUserId()).isEqualTo(SystemDecks.OWNER);
        assertThat(deck.getUsername()).isEqualTo("ERAS");
        assertThat(deck.isSystemDeck()).isTrue();
        assertThat(deck.isBotDeck()).isTrue();
        assertThat(deck.isPrivateDeck()).isFalse();
        assertThat(deck.getDeckImage()).isEqualTo("https://r2/cards/E1-1-a.jpg");
    }

    @Test
    void aDeckWithACardTheSiteLacksIsLeftAsItWas() {
        DeckListRepository decks = mock(DeckListRepository.class);
        DriveCardRepository driveCards = mock(DriveCardRepository.class);
        when(driveCards.findByEditionAndNumber(anyString(), anyInt())).thenReturn(List.of());
        SystemDecks system = new SystemDecks(decks, driveCards, "");

        assertThat(system.save(new SystemDecks.Listed("system-x", "X", "ST4-R01", true, List.of("ST4-R01")))).isFalse();
        verify(decks, never()).save(any());
    }

    @Test
    void aBotChoiceMadeByAnAdministratorIsKept() {
        DeckListRepository decks = mock(DeckListRepository.class);
        DriveCardRepository driveCards = mock(DriveCardRepository.class);
        DeckList existing = new DeckList();
        existing.setId("system-x");
        existing.setBotDeck(false);
        when(decks.findById("system-x")).thenReturn(Optional.of(existing));
        when(driveCards.findByEditionAndNumber("ST4", 1)).thenReturn(List.of(new DriveCard("st4-1", null, "x", 1, "R", "ST4", null, null)));

        new SystemDecks(decks, driveCards, "").save(new SystemDecks.Listed("system-x", "X", "ST4-R01", true, List.of("ST4-R01")));

        assertThat(existing.isBotDeck()).isFalse();
        assertThat(existing.getCards()).containsExactly("st4-1");
    }

    /**
     * Every card of every deck is run by the engine and every deck has an Iniciador and at most 4 copies of a name,
     * checked against a dump of the {@code cards} collection: {@code -Dsystem.decks.meta=<file.json>} with {"cards": [...]}.
     */
    @Test
    @EnabledIfSystemProperty(named = "system.decks.meta", matches = ".+")
    void everyCardIsProgrammedAndEveryDeckIsLegal() throws Exception {
        JsonNode root = JsonMapper.builder().build().readTree(new File(System.getProperty("system.decks.meta")));
        Map<String, JsonNode> byCode = new HashMap<>();
        for (JsonNode c : root.path("cards")) {
            String sub = c.path("subEdition").isTextual() && !c.path("subEdition").asString().isBlank() ? "." + c.path("subEdition").asString() : "";
            byCode.put(String.format("%s%s-%s%02d", c.path("edition").asString(), sub, c.path("colorIdentity").asString(), c.path("cardNumber").asInt()), c);
        }
        List<String> problems = new ArrayList<>();
        for (SystemDecks.Listed d : SystemDecks.read()) {
            Map<String, Integer> names = new HashMap<>();
            boolean starter = false;
            for (String code : d.codes()) {
                JsonNode c = byCode.get(code);
                if (c == null) { problems.add(d.id() + ": falta " + code); continue; }
                String name = c.path("cardName").asString();
                names.merge(name.toLowerCase(), 1, Integer::sum);
                starter |= c.path("starter").asBoolean(false);
                List<String> keywords = new ArrayList<>();
                for (String k : List.of("keywordEffects", "inheritKeywordEffects"))
                    // As CardMeta.keywordNames: "Letalidad {cantidad}" + 1 → "Letalidad 1"
                    for (JsonNode kw : c.path(k)) keywords.add(kw.path("keyword").asString("")
                            .replaceAll("\\{[^}]*}", kw.path("number").isTextual() ? kw.path("number").asString() : "").trim().replaceAll("\\s+", " "));
                String pending = CardEffects.pending(name, c.path("effects").size(), c.path("inheritEffects").size(),
                        c.path("formationEffects").size() + c.path("formationKeywordEffects").size(), keywords,
                        c.path("specialSummonKind").isTextual() ? c.path("specialSummonKind").asString() : null);
                if (pending != null) problems.add(d.id() + ": " + code + " " + name + " sin programar (" + pending + ")");
            }
            if (!starter) problems.add(d.id() + ": sin Iniciador");
            names.forEach((n, k) -> { if (k > 4) problems.add(d.id() + ": " + k + " copias de " + n); });
        }
        assertThat(problems.stream().distinct().toList()).isEmpty();
    }
}
