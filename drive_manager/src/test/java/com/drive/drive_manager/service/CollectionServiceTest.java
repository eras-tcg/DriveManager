package com.drive.drive_manager.service;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Crafting and breaking never ask for more than one request can hold, and fragment sums never overflow. */
class CollectionServiceTest {

    private MongoTemplate mongo;
    private CollectionService collections;

    @BeforeEach
    void setUp() {
        mongo = mock(MongoTemplate.class);
        CardPoolService pool = mock(CardPoolService.class);
        when(pool.rarityOf("c1")).thenReturn("C");
        when(pool.raritiesOf(anyCollection())).thenReturn(Map.of("c1", "C", "c2", "C"));
        collections = new CollectionService(mongo, pool, () -> 0.9);
    }

    @Test
    void craftingMoreThanOneRequestHoldsIsRefusedBeforeTouchingTheCollection() {
        // 71582789 * 30 used to wrap to a negative cost, which passed the guard and added fragments
        assertThatThrownBy(() -> collections.craft("alice", "c1", 71_582_789))
                .hasMessage("Puedes fabricar hasta " + CollectionRules.MAX_CRAFTS + " copias de una vez");
        assertThatThrownBy(() -> collections.craft("alice", "c1", CollectionRules.MAX_CRAFTS + 1))
                .isInstanceOf(CollectionService.CollectionException.class);
        verifyNoInteractions(mongo);
    }

    @Test
    void craftingChargesItsCostAndGuardsOnIt() {
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class), eq(CollectionService.COLLECTION)))
                .thenReturn(new Document());
        collections.craft("alice", "c1", CollectionRules.MAX_CRAFTS);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).findAndModify(query.capture(), update.capture(), any(FindAndModifyOptions.class), eq(Document.class), eq(CollectionService.COLLECTION));
        int cost = CollectionRules.MAX_CRAFTS * CollectionRules.CRAFT_COST;
        assertThat(update.getValue().getUpdateObject().get("$inc", Document.class).get("fragments.C")).isEqualTo(-cost);
        assertThat(query.getValue().getQueryObject().get("fragments.C", Document.class).get("$gte")).isEqualTo(cost);
    }

    @Test
    void aDecksMissingCardsAreCappedInAllEvenWhenTheirSumOverflows() {
        Map<String, Integer> many = new LinkedHashMap<>();
        many.put("c1", CollectionRules.MAX_CRAFTS);
        many.put("c2", 1);
        assertThatThrownBy(() -> collections.craftMany("alice", many)).isInstanceOf(CollectionService.CollectionException.class);

        Map<String, Integer> overflowing = new LinkedHashMap<>();
        overflowing.put("c1", Integer.MAX_VALUE);
        overflowing.put("c2", Integer.MAX_VALUE);
        assertThatThrownBy(() -> collections.craftMany("alice", overflowing)).isInstanceOf(CollectionService.CollectionException.class);
        verifyNoInteractions(mongo);
    }

    @Test
    void breakingSoManyCopiesThatTheirFragmentsOverflowIsRefused() {
        assertThatThrownBy(() -> collections.breakCopies("alice", "c1", "prism", Integer.MAX_VALUE))
                .isInstanceOf(CollectionService.CollectionException.class);
        verifyNoInteractions(mongo);
    }

    @Test
    void craftingUntilAFinishSpendsAtMostOneRequestsCrafts() {
        when(mongo.findById("alice", Document.class, CollectionService.COLLECTION))
                .thenReturn(new Document("fragments", new Document("C", Integer.MAX_VALUE)));
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class), eq(CollectionService.COLLECTION)))
                .thenReturn(new Document());

        CollectionService.Crafted crafted = collections.craftUntil("alice", "c1", "prism", null);

        assertThat(crafted.finishes()).hasSize(CollectionRules.MAX_CRAFTS);
        assertThat(Set.copyOf(crafted.finishes())).containsExactly("plain");
    }
}
