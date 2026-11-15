package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.DeckBox;
import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.repository.DeckBoxRepository;
import com.drive.drive_manager.repository.DeckListRepository;
import com.drive.drive_manager.service.ActivityLogService;
import com.drive.drive_manager.service.ProgressionRules;
import com.drive.drive_manager.service.ProgressionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeckListControllerTest {

    private final DeckList alicePublic = deck("d1", "alice", false);
    private final DeckList alicePrivate = deck("d2", "alice", true);
    private final DeckList bobPublic = deck("d3", "bob", false);

    private DeckListRepository repo;
    private ProgressionService progression;
    private DeckBoxRepository deckBoxes;
    private DeckListController controller;

    @BeforeEach
    void setUp() {
        repo = mock(DeckListRepository.class);
        progression = mock(ProgressionService.class);
        deckBoxes = mock(DeckBoxRepository.class);
        when(repo.save(any(DeckList.class))).thenAnswer(call -> call.getArgument(0));
        when(repo.findAll()).thenReturn(List.of(alicePublic, alicePrivate, bobPublic));
        when(repo.findPublicDecks()).thenReturn(List.of(alicePublic, bobPublic));
        when(repo.findByUserId("alice")).thenReturn(List.of(alicePublic, alicePrivate));
        when(repo.findById("d2")).thenReturn(Optional.of(alicePrivate));
        when(repo.findById("d1")).thenReturn(Optional.of(alicePublic));
    }

    @Test
    void listWithoutFiltersReturnsOnlyPublicDecks() {
        assertThat(controller().listAll(null, false, anonymous()).getBody())
                .containsExactly(alicePublic, bobPublic);
    }

    @Test
    void ownerSeesTheirPrivateDecks() {
        assertThat(controller().listAll("alice", false, user("alice")).getBody())
                .containsExactly(alicePublic, alicePrivate);
    }

    @Test
    void othersSeeOnlyPublicDecksOfAUser() {
        assertThat(controller().listAll("alice", false, user("bob")).getBody())
                .containsExactly(alicePublic);
        assertThat(controller().listAll("alice", false, anonymous()).getBody())
                .containsExactly(alicePublic);
        assertThat(controller().listAll("alice", false, null).getBody())
                .containsExactly(alicePublic);
    }

    @Test
    void privateDeckIsNotFoundForOthers() {
        assertThat(controller().getOne("d2", user("bob")).getStatusCode().value()).isEqualTo(404);
        assertThat(controller().getOne("d2", anonymous()).getStatusCode().value()).isEqualTo(404);
        assertThat(controller().getOne("d2", user("alice")).getBody()).isEqualTo(alicePrivate);
    }

    @Test
    void publicDeckIsVisibleToEveryone() {
        assertThat(controller().getOne("d1", anonymous()).getBody()).isEqualTo(alicePublic);
    }

    @Test
    void aNewDeckCountsForTheProgressionAndAnEditDoesNot() {
        controller().create(deck(null, null, false), user("alice"));
        verify(progression).record("alice", ProgressionRules.DECK_SAVED, 1);

        progression = mock(ProgressionService.class);
        controller = null;
        controller().update("d1", deck("d1", "alice", false), user("alice"));
        verify(progression, never()).record(anyString(), anyString(), anyInt());
    }

    @Test
    void aNewDeckNameHasAtMost30Characters() {
        DeckList body = deck(null, null, false);
        body.setDeckName("x".repeat(30));
        assertThat(controller().create(body, user("alice")).getStatusCode().value()).isEqualTo(200);
        body.setDeckName("x".repeat(31));
        assertThat(controller().create(body, user("alice")).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void anOlderLongNameCanBeKeptButNotChangedToAnotherLongOne() {
        alicePublic.setDeckName("y".repeat(60));
        DeckList body = deck("d1", "alice", false);
        body.setDeckName("y".repeat(60));
        assertThat(controller().update("d1", body, user("alice")).getStatusCode().value()).isEqualTo(200);
        body.setDeckName("z".repeat(60));
        assertThat(controller().update("d1", body, user("alice")).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void deletingADeckTakesItOutOfItsDeckboxes() {
        DeckBox box = new DeckBox();
        box.setId("b1");
        box.setUserId("alice");
        box.setDeckIds(new java.util.ArrayList<>(List.of("d1", "d9")));
        when(deckBoxes.findByDeckIds("d1")).thenReturn(List.of(box));

        controller().delete("d1", user("alice"));

        assertThat(box.getDeckIds()).containsExactly("d9");
        verify(deckBoxes).saveAll(List.of(box));
    }

    private DeckListController controller() {
        if (controller == null) controller = new DeckListController(repo, progression, mock(ActivityLogService.class), deckBoxes);
        return controller;
    }

    private static Authentication user(String id) {
        return new UsernamePasswordAuthenticationToken(id, null, AuthorityUtils.NO_AUTHORITIES);
    }

    private static Authentication anonymous() {
        return new AnonymousAuthenticationToken("key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
    }

    private static DeckList deck(String id, String userId, boolean privateDeck) {
        DeckList d = new DeckList();
        d.setId(id);
        d.setUserId(userId);
        d.setPrivateDeck(privateDeck);
        d.setCards(List.of("E2-B01"));
        return d;
    }
}
