package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.EditionMetadata;
import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.repository.EditionMetadataRepository;
import com.drive.drive_manager.service.ActivityLogService;
import com.drive.drive_manager.service.EditionImageService;
import com.drive.drive_manager.service.HiddenEditions;
import com.drive.drive_manager.service.ProgressionService;
import com.drive.drive_manager.service.ShopService;
import com.drive.drive_manager.service.TestCards;
import com.drive.drive_manager.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The server hides an edition an admin marked: from the editions and the shop, for everyone but admins and testers. */
class HiddenEditionsControllerTest {

    private List<EditionMetadata> stored;
    private HiddenEditions hidden;
    private EditionMetadataController editions;

    private final Authentication player = user("bob", "manage_decks");
    private final Authentication tester = user("tess", "manage_decks", TestCards.PERMISSION);
    private final Authentication admin = user("ada", HiddenEditions.ADMIN);

    @BeforeEach
    void setUp() {
        EditionMetadataRepository repo = mock(EditionMetadataRepository.class);
        stored = new ArrayList<>(List.of(edition("E1", null), edition("E2", true), edition("E2.1", null)));
        when(repo.findAll()).thenAnswer(i -> List.copyOf(stored));
        when(repo.findById(anyString())).thenAnswer(i -> stored.stream().filter(e -> e.getEditionId().equals(i.getArgument(0))).findFirst());
        when(repo.save(any(EditionMetadata.class))).thenAnswer(i -> i.getArgument(0));
        hidden = new HiddenEditions(repo, mock(DriveCardRepository.class));
        editions = new EditionMetadataController(repo, mock(EditionImageService.class), hidden);
    }

    @Test
    void aHiddenEditionIsListedOnlyToAdminsAndTesters() {
        assertThat(ids(editions.listAll(player).getBody())).as("its sub-editions go with it").containsExactly("E1");
        assertThat(ids(editions.listAll(null).getBody())).containsExactly("E1");
        assertThat(ids(editions.listAll(tester).getBody())).containsExactly("E1", "E2", "E2.1");
        assertThat(ids(editions.listAll(admin).getBody())).containsExactly("E1", "E2", "E2.1");
        assertThat(editions.getOne("E2", player).getStatusCode().value()).isEqualTo(404);
        assertThat(editions.getOne("E2.1", player).getStatusCode().value()).isEqualTo(404);
        assertThat(editions.getOne("E2", admin).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anAdminHidesAndShowsAnEditionAndTheCardsFollowAtOnce() {
        assertThat(editions.setHidden("E1", new EditionMetadataController.HiddenRequest(true)).getBody().getHidden()).isTrue();
        assertThat(hidden.hidden("E1", player)).isTrue();
        editions.setHidden("E2", new EditionMetadataController.HiddenRequest(false));
        assertThat(ids(editions.listAll(player).getBody())).containsExactly("E2", "E2.1");
        assertThat(editions.setHidden("X9", new EditionMetadataController.HiddenRequest(true)).getStatusCode().value()).isEqualTo(404);
        assertThat(editions.setHidden("E1", new EditionMetadataController.HiddenRequest(null)).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void aSaveWithoutTheMarkKeepsIt() {
        EditionMetadata body = edition("E2", null);
        editions.upsert(body);
        assertThat(body.getHidden()).isTrue();
    }

    @Test
    void aPlayerCannotBuyAHiddenEdition() {
        ShopService shop = mock(ShopService.class);
        WalletController wallet = new WalletController(mock(WalletService.class), shop, mock(ProgressionService.class), mock(ActivityLogService.class));
        ReflectionTestUtils.setField(wallet, "hiddenEditions", hidden);

        var refused = wallet.buy(player, new WalletController.PurchaseRequest("E2", "pack"));
        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        verify(shop, never()).buy(anyString(), anyString(), anyString());

        when(shop.buy("tess", "E2", "pack")).thenReturn(new ShopService.Opening(0, 100, List.of()));
        assertThat(wallet.buy(tester, new WalletController.PurchaseRequest("E2", "pack")).getStatusCode().value()).isEqualTo(200);
    }

    private static List<String> ids(List<EditionMetadata> list) {
        return list.stream().map(EditionMetadata::getEditionId).toList();
    }

    private static EditionMetadata edition(String id, Boolean isHidden) {
        EditionMetadata e = new EditionMetadata();
        e.setEditionId(id);
        e.setHidden(isHidden);
        return e;
    }

    private static Authentication user(String name, String... permissions) {
        return new UsernamePasswordAuthenticationToken(name, null, AuthorityUtils.createAuthorityList(permissions));
    }
}
