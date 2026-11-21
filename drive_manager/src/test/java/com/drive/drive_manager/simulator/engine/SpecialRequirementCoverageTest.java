package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every card with a special summon method has its Requerimiento programmed (the card file's `requirement` section): the engine never reads
 * the printed text, so a card without it cannot be special summoned. Only the name and the method are looked at, never the text.
 */
@Tag("basic")
class SpecialRequirementCoverageTest {

    /** Waiting for the designer (vault, "Requerimientos de invocación especial"): no section until answered. */
    private static final Set<String> WAITING = Set.of("fausto", "samael el fuego de la destrucción", "uriel el fuego de la esperanza",
            "apostol de la infamia");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void everySpecialCardOfTheStarterDecksHasItsRequirement() throws Exception {
        TreeSet<String> missing = new TreeSet<>();
        for (String deck : List.of("st1", "st2", "st3", "st4")) {
            try (InputStream in = getClass().getResourceAsStream("/simulator/" + deck + "-cards.json")) {
                missing.addAll(missing(JSON.readTree(in)));
            }
        }
        assertThat(missing).as("cartas de los mazos de estructura sin sección requirement").isEmpty();
    }

    /**
     * The whole database, from an export of its cards ({@code [{cardName, specialSummonKind}]}, local base); runs only when asked:
     * {@code -Drequirements.in=<export.json>}.
     */
    @Test
    void everySpecialCardOfTheDatabaseHasItsRequirement() throws Exception {
        String in = System.getProperty("requirements.in");
        assumeTrue(in != null && !in.isBlank(), "solo con -Drequirements.in=<archivo>");
        TreeSet<String> missing = missing(JSON.readTree(Files.readString(Path.of(in), StandardCharsets.UTF_8)));
        missing.removeIf(WAITING::contains);
        assertThat(missing).as("cartas con invocación especial sin sección requirement").isEmpty();
    }

    private static TreeSet<String> missing(JsonNode cards) {
        TreeSet<String> missing = new TreeSet<>();
        for (JsonNode c : cards) {
            String kind = c.path("specialSummonKind").asText("");
            if (kind.isBlank() || "null".equals(kind)) continue;
            CardSlot card = new CardSlot();
            card.setCardName(c.get("cardName").asText().trim());
            if (CardEffects.requirementOf(card) == null) missing.add(card.getCardName().toLowerCase());
        }
        return missing;
    }
}
