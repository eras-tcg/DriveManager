package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class DeckVariationTest {

    private static int ids;

    private static CardSlot card(String name, String type, Integer level, boolean starter, String... classes) {
        CardSlot c = new CardSlot();
        c.setCardId("id-" + name);
        c.setInstanceId("i" + (ids++));
        c.setCardName(name);
        c.setCardType(type);
        c.setLevel(level);
        c.setStarter(starter);
        c.setClasses(new ArrayList<>(List.of(classes)));
        c.setSpecialSummonKind("creature".equals(type) && level != null && level > 1 ? "promotion" : null);
        return c;
    }

    private static void add(List<CardSlot> deck, CardSlot c, int copies) {
        for (int i = 0; i < copies; i++) deck.add(c);
    }

    /** A 50-card deck in the ST template: 4-copy core, 2-copy flexible entries. */
    private static List<CardSlot> deck() {
        List<CardSlot> d = new ArrayList<>();
        add(d, card("Ini", "creature", 1, true, "caballero"), 4);
        add(d, card("Uno A", "creature", 1, false, "caballero"), 4);
        add(d, card("Uno B", "creature", 1, false, "retador"), 2);
        add(d, card("Dos A", "creature", 2, false, "caballero"), 4);
        add(d, card("Dos B", "creature", 2, false, "caballero"), 4);
        add(d, card("Dos C", "creature", 2, false, "retador"), 2);
        add(d, card("Tres A", "creature", 3, false, "caballero"), 4);
        add(d, card("Tres B", "creature", 3, false, "retador"), 2);
        add(d, card("Tres C", "creature", 3, false, "caballero"), 2);
        add(d, card("Cuatro A", "creature", 4, false, "caballero"), 4);
        add(d, card("Cuatro B", "creature", 4, false, "retador"), 2);
        add(d, card("Cinco A", "creature", 5, false, "caballero"), 2);
        add(d, card("Cinco B", "creature", 5, false, "retador"), 2);
        add(d, card("Estructura", "structure", 2, false), 4);
        add(d, card("Util A", "utility", 1, false), 4);
        add(d, card("Util B", "utility", 2, false), 2);
        add(d, card("Util C", "utility", 5, false), 2);
        return d;
    }

    private static List<CardSlot> catalog() {
        List<CardSlot> c = new ArrayList<>();
        for (int level = 1; level <= 5; level++) {
            c.add(card("Pool caballero " + level, "creature", level, false, "caballero"));
            c.add(card("Pool retador " + level, "creature", level, false, "retador"));
            c.add(card("Pool ajeno " + level, "creature", level, false, "dragón"));
        }
        c.add(card("Pool util", "utility", 3, false));
        CardSlot unprogrammed = card("Pool sin programar", "creature", 3, false, "caballero");
        unprogrammed.setUnprogrammed("1 de 1 efectos propios");
        c.add(unprogrammed);
        return c;
    }

    private static Map<String, Integer> curve(List<CardSlot> cards) {
        Map<String, Integer> m = new TreeMap<>();
        cards.forEach(c -> m.merge(DeckVariation.role(c), 1, Integer::sum));
        return m;
    }

    private static Map<String, Integer> copies(List<CardSlot> cards) {
        Map<String, Integer> m = new HashMap<>();
        cards.forEach(c -> m.merge(c.getCardName(), 1, Integer::sum));
        return m;
    }

    @Test
    void aVariantKeepsSizeCurveAndCoreAndTakesOnlyRelatedProgrammedCards() {
        for (int seed = 0; seed < 200; seed++) {
            List<CardSlot> base = deck();
            DeckVariation.Result r = DeckVariation.vary("Mazo", base, catalog(), new Random(seed));
            assertThat(r.changes()).as("seed %d", seed).isNotEmpty();
            assertThat(r.cards()).hasSize(50);
            assertThat(curve(r.cards())).isEqualTo(curve(base));
            Map<String, Integer> n = copies(r.cards());
            assertThat(n.values()).allMatch(k -> k <= 4);
            // The core (Iniciador, structure, 4-copy entries) stays whole
            for (String core : List.of("Ini", "Uno A", "Dos A", "Dos B", "Tres A", "Cuatro A", "Estructura", "Util A"))
                assertThat(n.get(core)).as("seed %d %s", seed, core).isEqualTo(4);
            assertThat(n.keySet()).noneMatch(name -> name.startsWith("Pool ajeno") || name.equals("Pool sin programar"));
            // New instances: no instance id of the base is reused
            assertThat(r.cards()).extracting(CardSlot::getInstanceId).doesNotHaveDuplicates()
                    .doesNotContainAnyElementsOf(base.stream().map(CardSlot::getInstanceId).toList());
        }
    }

    @Test
    void theSameChanceGivesTheSameDeck() {
        DeckVariation.Result a = DeckVariation.vary("Mazo", deck(), catalog(), new Random(7));
        DeckVariation.Result b = DeckVariation.vary("Mazo", deck(), catalog(), new Random(7));
        assertThat(a.changes()).isEqualTo(b.changes());
        assertThat(a.cards()).extracting(CardSlot::getCardName).isEqualTo(b.cards().stream().map(CardSlot::getCardName).toList());
    }

    @Test
    void withNothingRelatedTheDeckIsPlayedAsItIs() {
        List<CardSlot> base = deck();
        DeckVariation.Result r = DeckVariation.vary("Mazo", base, List.of(card("Ajeno", "creature", 2, false, "dragón")), new Random(1));
        // Copies may still move; without any change at all the very same deck comes back
        assertThat(curve(r.cards())).isEqualTo(curve(base));
        if (r.changes().isEmpty()) assertThat(r.cards()).isSameAs(base);
    }
}
