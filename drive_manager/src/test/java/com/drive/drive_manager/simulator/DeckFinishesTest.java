package com.drive.drive_manager.simulator;

import com.drive.drive_manager.service.CollectionService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A deck's copies shine as their owner has them in the collection, the finest first. */
class DeckFinishesTest {

    @Test
    void eachCopyTakesTheOwnersFinestFinishesFirst() {
        CollectionService collections = mock(CollectionService.class);
        when(collections.of("p1")).thenReturn(new CollectionService.View(
                Map.of("a", Map.of("plain", 1, "prism", 1), "b", Map.of("foil", 1)), Map.of()));
        MissionHooks hooks = new MissionHooks(null, null, collections, null);

        List<CardSlot> slots = new ArrayList<>();
        for (String id : List.of("a", "b", "a", "a", "c")) {
            CardSlot s = new CardSlot();
            s.setCardId(id);
            slots.add(s);
        }
        hooks.withFinishes("p1", slots);

        // a: prism, then plain, then one not owned; b: foil; c: not owned
        assertThat(slots).extracting(CardSlot::getFinish).containsExactly("prism", "foil", null, null, null);
    }

    @Test
    void aCopyWithTheAlternativeArtShowsItWithItsFinish() {
        CollectionService collections = mock(CollectionService.class);
        when(collections.of("p1")).thenReturn(new CollectionService.View(Map.of("titan", Map.of("plain", 1, "alt-x1-foil", 1, "alt-gone-prism", 1)), Map.of()));
        MissionHooks hooks = new MissionHooks(null, null, collections, null);

        List<CardSlot> slots = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            CardSlot s = new CardSlot();
            s.setCardId("titan");
            s.setImageUrl("https://img/titan");
            s.setAltArtUrls(Map.of("x1", "https://img/titan-alt"));
            slots.add(s);
        }
        hooks.withFinishes("p1", slots);

        // The copy of an art removed since it was pulled shows the usual art (still prismatic)
        assertThat(slots.get(0).isAlt()).isFalse();
        assertThat(slots.get(0).getImageUrl()).isEqualTo("https://img/titan");
        assertThat(slots.get(0).getFinish()).isEqualTo("prism");
        slots.remove(0);
        assertThat(slots.get(0).isAlt()).isTrue();
        assertThat(slots.get(0).getImageUrl()).isEqualTo("https://img/titan-alt");
        assertThat(slots.get(0).getFinish()).isEqualTo("foil");
        assertThat(slots.get(1).isAlt()).isFalse();
        assertThat(slots.get(1).getImageUrl()).isEqualTo("https://img/titan");
        // Still the same card: its rules and identity do not change
        assertThat(slots).extracting(CardSlot::getCardId).containsOnly("titan");
    }
}
