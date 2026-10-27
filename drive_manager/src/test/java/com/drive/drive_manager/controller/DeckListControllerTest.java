package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.repository.DeckListRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeckListControllerTest {

    private final DeckList alicePublic = deck("d1", "alice", false);
    private final DeckList alicePrivate = deck("d2", "alice", true);
    private final DeckList bobPublic = deck("d3", "bob", false);

    private DeckListRepository repo;
    private DeckListController controller;

    @BeforeEach
    void setUp() {
        repo = mock(DeckListRepository.class);
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

    private DeckListController controller() {
        if (controller == null) controller = new DeckListController(repo);
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
