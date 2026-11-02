package com.drive.drive_manager.simulator;

import com.drive.drive_manager.service.CollectionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
        RoomService service = new RoomService();
        CollectionService collections = mock(CollectionService.class);
        when(collections.of("p1")).thenReturn(new CollectionService.View(
                Map.of("a", Map.of("plain", 1, "prism", 1), "b", Map.of("foil", 1)), Map.of()));
        ReflectionTestUtils.setField(service, "collections", collections);

        List<CardSlot> slots = new ArrayList<>();
        for (String id : List.of("a", "b", "a", "a", "c")) {
            CardSlot s = new CardSlot();
            s.setCardId(id);
            slots.add(s);
        }
        service.withFinishes("p1", slots);

        // a: prism, then plain, then one not owned; b: foil; c: not owned
        assertThat(slots).extracting(CardSlot::getFinish).containsExactly("prism", "foil", null, null, null);
    }
}
