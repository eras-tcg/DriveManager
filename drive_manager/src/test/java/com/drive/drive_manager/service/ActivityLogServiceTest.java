package com.drive.drive_manager.service;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The activity log: a visit a day, failures that never reach the player, and the administrators' filters. */
class ActivityLogServiceTest {

    private MongoTemplate mongo;
    private final AtomicReference<LocalDate> today = new AtomicReference<>(LocalDate.of(2026, 11, 3));
    private ActivityLogService log;

    @BeforeEach
    void setUp() {
        mongo = mock(MongoTemplate.class);
        when(mongo.indexOps(ActivityLogService.COLLECTION)).thenReturn(mock(IndexOperations.class));
        log = new ActivityLogService(mongo, today::get);
    }

    @Test
    void aPlayerVisitsOnceADay() {
        log.visit("alice");
        log.visit("alice");
        today.set(LocalDate.of(2026, 11, 4));
        log.visit("alice");

        verify(mongo).insert(argThat((Document d) -> "visit-alice-2026-11-03".equals(d.get("_id"))), eq(ActivityLogService.COLLECTION));
        verify(mongo).insert(argThat((Document d) -> "visit-alice-2026-11-04".equals(d.get("_id"))), eq(ActivityLogService.COLLECTION));
    }

    @Test
    void aVisitAlreadyWrittenBeforeARestartIsNotAnError() {
        when(mongo.insert(any(Document.class), eq(ActivityLogService.COLLECTION))).thenThrow(new DuplicateKeyException("dup"));

        assertThatCode(() -> log.visit("alice")).doesNotThrowAnyException();
        log.visit("alice");
        verify(mongo, times(1)).insert(any(Document.class), eq(ActivityLogService.COLLECTION));
    }

    @Test
    void aFailedRecordNeverReachesThePlayer() {
        when(mongo.insert(any(Document.class), eq(ActivityLogService.COLLECTION))).thenThrow(new RuntimeException("down"));

        assertThatCode(() -> log.record("alice", ActivityLogService.DECK_CREATE, Map.of("deckName", "Rojo"))).doesNotThrowAnyException();
    }

    @Test
    void eventsWithoutAPlayerAreNotRecorded() {
        log.record(null, ActivityLogService.LOGIN, null);

        verify(mongo, never()).insert(any(Document.class), anyString());
    }

    @Test
    void aNameMatchingNoAccountFindsNothing() {
        when(mongo.find(any(Query.class), eq(Document.class), eq("users"))).thenReturn(List.of());

        assertThat(log.search(null, "nadie", null, null, 0, 50).total()).isZero();
        verify(mongo, never()).find(any(Query.class), eq(Document.class), eq(ActivityLogService.COLLECTION));
    }

    @Test
    void aGamesRivalIsShownByTheirCurrentName() {
        when(mongo.find(any(Query.class), eq(Document.class), eq(ActivityLogService.COLLECTION))).thenReturn(List.of(
                new Document("_id", "e1").append("userId", "u1").append("action", ActivityLogService.ONLINE_GAME_END)
                        .append("detail", new Document("rivalId", "u2").append("result", "won"))));
        when(mongo.find(any(Query.class), eq(Document.class), eq("users"))).thenReturn(List.of(
                new Document("_id", "u1").append("username", "ana"), new Document("_id", "u2").append("username", "beto")));

        assertThat(log.search(null, null, null, null, 0, 50).items().get(0).detail()).containsEntry("rival", "beto");
    }

    @Test
    void searchFiltersByActionAndDatesAndShowsTheCurrentName() {
        Instant at = Instant.parse("2026-11-03T12:00:00Z");
        when(mongo.count(any(Query.class), eq(ActivityLogService.COLLECTION))).thenReturn(1L);
        when(mongo.find(any(Query.class), eq(Document.class), eq(ActivityLogService.COLLECTION))).thenReturn(List.of(
                new Document("_id", "e1").append("userId", "u1").append("action", ActivityLogService.SHOP_PURCHASE)
                        .append("detail", new Document("product", "box")).append("at", Date.from(at))));
        when(mongo.find(any(Query.class), eq(Document.class), eq("users"))).thenReturn(List.of(new Document("_id", "u1").append("username", "ana")));

        ActivityLogService.Page page = log.search(List.of(ActivityLogService.SHOP_PURCHASE), null,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-04T00:00:00Z"), 0, 50);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).containsExactly(
                new ActivityLogService.Entry("e1", "u1", "ana", ActivityLogService.SHOP_PURCHASE, Map.of("product", "box"), at));
        verify(mongo).find(argThat((Query q) -> q.getQueryObject().toString().contains(ActivityLogService.SHOP_PURCHASE)
                && q.getQueryObject().toString().contains("$gte") && q.getLimit() == 50), eq(Document.class), eq(ActivityLogService.COLLECTION));
    }
}
