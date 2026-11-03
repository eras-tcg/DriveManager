package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.EditionMetadata;
import com.drive.drive_manager.repository.EditionMetadataRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A structure deck is bought once per account; rewards and grants add atomically, never overwriting the balance. */
class WalletServiceTest {

    private MongoTemplate mongo;
    private WalletService wallets;

    @BeforeEach
    void setUp() {
        mongo = mock(MongoTemplate.class);
        EditionMetadataRepository editions = mock(EditionMetadataRepository.class);
        EditionMetadata st1 = new EditionMetadata();
        st1.setEditionId("ST1");
        when(editions.findById("ST1")).thenReturn(Optional.of(st1));
        wallets = new WalletService(mongo, editions, "America/Santiago");
    }

    @Test
    void aStructureAlreadyBoughtIsRefusedSayingSo() {
        Document saved = new Document("gems", 5000).append("lastReward", java.time.LocalDate.now(java.time.ZoneId.of("America/Santiago")).toString())
                .append("structures", List.of("ST1"));
        when(mongo.findById("alice", Document.class, WalletService.COLLECTION)).thenReturn(saved);
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class), eq(WalletService.COLLECTION))).thenReturn(null);

        assertThatThrownBy(() -> wallets.buy("alice", "ST1", "structure")).hasMessage("Ya compraste este structure deck");
        assertThat(wallets.structures("alice")).containsExactly("ST1");
    }

    @Test
    void withoutTheGemsItSaysThat() {
        Document saved = new Document("gems", 10).append("lastReward", java.time.LocalDate.now(java.time.ZoneId.of("America/Santiago")).toString());
        when(mongo.findById("alice", Document.class, WalletService.COLLECTION)).thenReturn(saved);
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class), eq(WalletService.COLLECTION))).thenReturn(null);

        assertThatThrownBy(() -> wallets.buy("alice", "ST1", "structure")).hasMessage("No te alcanzan las gemas");
    }

    @Test
    void theDailyRewardIsAddedNotSetAndOnlyByTheUpdateThatMovesTheDay() {
        String today = java.time.LocalDate.now(java.time.ZoneId.of("America/Santiago")).toString();
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class), eq(WalletService.COLLECTION)))
                .thenReturn(new Document("gems", 1300).append("lastReward", today));

        WalletRules.Opened opened = wallets.open("alice");

        assertThat(opened.reward()).isEqualTo(WalletRules.DAILY_GEMS);
        assertThat(opened.state().gems()).isEqualTo(1300);
        verify(mongo).findAndModify(
                argThat(q -> q.getQueryObject().get("lastReward", Document.class).get("$ne").equals(today)),
                argThat(u -> u.getUpdateObject().get("$inc", Document.class).get("gems").equals(WalletRules.DAILY_GEMS)
                        && !u.getUpdateObject().get("$set", Document.class).containsKey("gems")),
                any(FindAndModifyOptions.class), eq(Document.class), eq(WalletService.COLLECTION));
        verify(mongo, never()).upsert(any(Query.class), any(Update.class), eq(WalletService.COLLECTION));
    }

    @Test
    void aNewWalletIsOnlyEverInsertedAndAlreadyRewardedGivesNothing() {
        String today = java.time.LocalDate.now(java.time.ZoneId.of("America/Santiago")).toString();
        when(mongo.findById("alice", Document.class, WalletService.COLLECTION)).thenReturn(new Document("gems", 700).append("lastReward", today));

        WalletRules.Opened opened = wallets.open("alice");

        assertThat(opened.reward()).isZero();
        assertThat(opened.state().gems()).isEqualTo(700);
        // $setOnInsert only: an existing wallet (and a purchase running meanwhile) is left as it is
        verify(mongo).upsert(any(Query.class), argThat(u -> u.getUpdateObject().keySet().equals(java.util.Set.of("$setOnInsert"))), eq(WalletService.COLLECTION));
    }

    @Test
    void aGrantIsOneAtomicSumThatNeverGoesBelowZero() {
        when(mongo.findAndModify(any(Query.class), any(AggregationUpdate.class), any(FindAndModifyOptions.class), eq(Document.class), eq(WalletService.COLLECTION)))
                .thenReturn(new Document("gems", 0));

        assertThat(wallets.grant("alice", -5000)).isZero();

        verify(mongo).findAndModify(any(Query.class), argThat(d -> {
            if (!(d instanceof AggregationUpdate u)) return false;
            String pipeline = u.toPipeline(org.springframework.data.mongodb.core.aggregation.Aggregation.DEFAULT_CONTEXT).toString();
            return pipeline.contains("$max") && pipeline.contains("$add") && pipeline.contains("-5000");
        }), any(FindAndModifyOptions.class), eq(Document.class), eq(WalletService.COLLECTION));
        verify(mongo, never()).upsert(any(Query.class), argThat(u -> u.getUpdateObject().containsKey("$set")), eq(WalletService.COLLECTION));
    }
}
