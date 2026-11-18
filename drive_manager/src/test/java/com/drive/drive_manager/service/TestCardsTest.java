package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.DriveCardRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestCardsTest {

    @Test
    void aTestingEditionIsTAndANumber() {
        assertThat(TestCards.isTestEdition("T1")).isTrue();
        assertThat(TestCards.isTestEdition("t12")).isTrue();
        assertThat(TestCards.isTestEdition("T1.2")).isTrue();
        assertThat(TestCards.isTestEdition("E1")).isFalse();
        assertThat(TestCards.isTestEdition("ST1")).isFalse();
        assertThat(TestCards.isTestEdition("T")).isFalse();
        assertThat(TestCards.isTestEdition(null)).isFalse();
    }

    @Test
    void onlyThePermissionShowsThem() {
        var player = new UsernamePasswordAuthenticationToken("bob", null, AuthorityUtils.createAuthorityList("manage_decks"));
        var tester = new UsernamePasswordAuthenticationToken("ana", null, AuthorityUtils.createAuthorityList("manage_decks", TestCards.PERMISSION));
        var anonymous = new AnonymousAuthenticationToken("key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        assertThat(TestCards.hidden("T1", player)).isTrue();
        assertThat(TestCards.hidden("T1", anonymous)).isTrue();
        assertThat(TestCards.hidden("T1", null)).isTrue();
        assertThat(TestCards.hidden("T1", tester)).isFalse();
        assertThat(TestCards.hidden("E2", player)).isFalse();
    }

    @Test
    void aDeckIsHiddenWhenItHasACardInTesting() {
        DriveCardRepository repo = mock(DriveCardRepository.class);
        DriveCard image = new DriveCard();
        image.setId("img-t1");
        image.setEdition("T1");
        when(repo.findByEditionRegex(anyString())).thenReturn(List.of(image));
        TestCards cards = new TestCards(repo);
        var player = new UsernamePasswordAuthenticationToken("bob", null, AuthorityUtils.NO_AUTHORITIES);
        assertThat(cards.hiddenDeck(List.of("img-e1", "img-t1"), player)).isTrue();
        assertThat(cards.hiddenDeck(List.of("img-e1"), player)).isFalse();
    }
}
