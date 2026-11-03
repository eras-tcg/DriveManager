package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.EditionMetadata;
import com.drive.drive_manager.repository.EditionMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A purchase is drawn on the server and lands in the buyer's collection. */
class ShopServiceTest {

    private WalletService wallets;
    private EditionMetadataRepository editions;
    private CardPoolService pool;
    private CollectionService collections;
    private ShopService shop;

    @BeforeEach
    void setUp() {
        wallets = mock(WalletService.class);
        editions = mock(EditionMetadataRepository.class);
        pool = mock(CardPoolService.class);
        collections = mock(CollectionService.class);
        // Every roll 0.9: plain finishes, the first candidate
        shop = new ShopService(wallets, editions, pool, collections, () -> 0.9);
    }

    @Test
    void aPackIsDrawnChargedAndAddedToTheCollection() {
        EditionMetadata e1 = new EditionMetadata();
        e1.setEditionId("E1");
        e1.setPackTypes(List.of(new EditionMetadata.PackTypeConfig("R PACK", Map.of("C", 2))));
        when(editions.findById("E1")).thenReturn(Optional.of(e1));
        when(pool.pool("E1")).thenReturn(Map.of("C", List.of("c1")));
        when(wallets.buy("alice", "E1", "pack")).thenReturn(new WalletService.Purchase(2300, 100));

        ShopService.Opening opening = shop.buy("alice", "E1", "pack");

        assertThat(opening.gems()).isEqualTo(2300);
        assertThat(opening.packs()).singleElement().satisfies(p -> {
            assertThat(p.packType()).isEqualTo("R PACK");
            assertThat(p.cards()).containsExactly(new ShopService.OpenedCard("c1", "plain"), new ShopService.OpenedCard("c1", "plain"));
        });
        verify(collections).add("alice", List.of(new CollectionService.Pull("c1", "plain"), new CollectionService.Pull("c1", "plain")));
    }

    @Test
    void aStructureDeckComesWholeAndPlain() {
        EditionMetadata st1 = new EditionMetadata();
        st1.setEditionId("ST1");
        when(editions.findById("ST1")).thenReturn(Optional.of(st1));
        when(pool.structureCards("ST1")).thenReturn(List.of("a", "b"));
        when(wallets.buy("alice", "ST1", "structure")).thenReturn(new WalletService.Purchase(1900, 500));

        assertThat(shop.buy("alice", "ST1", "structure").packs().get(0).cards())
                .containsExactly(new ShopService.OpenedCard("a", "plain"), new ShopService.OpenedCard("b", "plain"));
    }

    @Test
    void nothingIsChargedForAnEditionWithoutCards() {
        EditionMetadata e9 = new EditionMetadata();
        e9.setEditionId("E9");
        e9.setPackTypes(List.of(new EditionMetadata.PackTypeConfig("R PACK", Map.of("C", 2))));
        when(editions.findById("E9")).thenReturn(Optional.of(e9));
        when(pool.pool("E9")).thenReturn(Map.of());

        assertThatThrownBy(() -> shop.buy("alice", "E9", "pack")).isInstanceOf(WalletService.PurchaseException.class);
        verify(wallets, never()).buy(anyString(), anyString(), anyString());
    }
}
