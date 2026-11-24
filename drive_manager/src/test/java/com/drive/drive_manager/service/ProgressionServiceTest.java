package com.drive.drive_manager.service;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Claims give their reward once; the structure step asks for one of ST1–ST4 taken in the shop. */
class ProgressionServiceTest {

    private MongoTemplate mongo;
    private WalletService wallets;
    private ProgressionService progression;

    @BeforeEach
    void setUp() {
        mongo = mock(MongoTemplate.class);
        wallets = mock(WalletService.class);
        CollectionService collections = mock(CollectionService.class);
        when(collections.of(any())).thenReturn(new CollectionService.View(Map.of(), Map.of()));
        progression = new ProgressionService(mongo, wallets, collections, () -> LocalDate.of(2026, 11, 2));
    }

    private void saved(Document doc) {
        when(mongo.findById("alice", Document.class, ProgressionService.COLLECTION)).thenReturn(doc);
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class), eq(ProgressionService.COLLECTION)))
                .thenReturn(doc);
    }

    @Test
    void theStructureStepWaitsForOneOfST1ToST4AndThenPaysItsGems() {
        saved(new Document());
        when(wallets.structures("alice")).thenReturn(List.of("ST5"));
        assertThatThrownBy(() -> progression.claimWelcome("alice", "structure")).hasMessage("Esa misión todavía no está cumplida");
        verify(wallets, never()).grant(any(), anyInt());

        when(wallets.structures("alice")).thenReturn(List.of("ST5", "ST3"));
        progression.claimWelcome("alice", "structure");
        verify(wallets).grant("alice", 100);
    }

    @Test
    void aMilestoneTierGivesItsGemsAndAClaimAlreadyTakenIsRefused() {
        saved(new Document("counts", new Document(MissionRules.GAME_PLAYED, 12)).append("milestones", new Document("games", 1)));

        progression.claimMilestone("alice", "games");
        verify(wallets).grant("alice", 250);

        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class), eq(ProgressionService.COLLECTION)))
                .thenReturn(null);
        assertThatThrownBy(() -> progression.claimMilestone("alice", "games")).hasMessage("Ya reclamaste ese nivel");
    }

    @Test
    void onlyThePagesThatCountAreNoted() {
        progression.visit("alice", "cartas");
        progression.visit("alice", "admin");

        verify(mongo).upsert(any(Query.class), argThat(u -> u.getUpdateObject().toString().contains("cartas")), eq(ProgressionService.COLLECTION));
        verify(mongo, never()).upsert(any(Query.class), argThat(u -> u.getUpdateObject().toString().contains("admin")), eq(ProgressionService.COLLECTION));
    }

    @Test
    void aVisitStepPaysOnceEvenWhenClaimedTwiceAtTheSameTime() {
        Document doc = new Document("welcome", List.of("structure")).append("visited", List.of(ProgressionRules.PAGE_HOW_TO_PLAY));
        saved(doc);
        progression.claimWelcome("alice", "how-to-play");
        verify(wallets).grant("alice", 100);

        // A second claim read the same document, but the atomic update finds the step already marked
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class), eq(ProgressionService.COLLECTION)))
                .thenReturn(null);
        assertThatThrownBy(() -> progression.claimWelcome("alice", "how-to-play")).hasMessage("Ya reclamaste esa misión");
        verify(wallets).grant("alice", 100);
    }
}
