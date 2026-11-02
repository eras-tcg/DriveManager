package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.EditionMetadata;
import com.drive.drive_manager.repository.EditionMetadataRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A structure deck is bought once per account. */
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
}
