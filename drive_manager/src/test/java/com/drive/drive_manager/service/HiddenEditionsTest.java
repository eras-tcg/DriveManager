package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.dto.EditionMetadata;
import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.repository.EditionMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** An edition an admin hid is seen only by admins and testers. */
class HiddenEditionsTest {

    private EditionMetadataRepository editions;
    private DriveCardRepository driveCards;
    private HiddenEditions hidden;

    private final Authentication player = new UsernamePasswordAuthenticationToken("bob", null, AuthorityUtils.createAuthorityList("manage_decks"));
    private final Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
    private final Authentication tester = new UsernamePasswordAuthenticationToken("tess", null, AuthorityUtils.createAuthorityList("manage_decks", TestCards.PERMISSION));
    private final Authentication admin = new UsernamePasswordAuthenticationToken("ada", null, AuthorityUtils.createAuthorityList(HiddenEditions.ADMIN));

    @BeforeEach
    void setUp() {
        editions = mock(EditionMetadataRepository.class);
        driveCards = mock(DriveCardRepository.class);
        hidden = new HiddenEditions(editions, driveCards);
        when(editions.findAll()).thenReturn(List.of(edition("E1", null), edition("E2", true), edition("E1.3", true), edition("ST1", false)));
        when(driveCards.findByEdition("E2")).thenReturn(List.of(image("img-e2", "E2", null)));
        when(driveCards.findByEdition("E1")).thenReturn(List.of(image("img-e1", "E1", null), image("img-e1-3", "E1", "3")));
    }

    @Test
    void onlyTheMarkedEditionsAreHidden() {
        assertThat(hidden.ids()).containsExactlyInAnyOrder("E2", "E1.3");
        assertThat(hidden.isHidden("E2")).isTrue();
        assertThat(hidden.isHidden("e2")).isTrue();
        assertThat(hidden.isHidden("E2.1")).as("a sub-edition of a hidden edition").isTrue();
        assertThat(hidden.isHidden("E1")).isFalse();
        assertThat(hidden.isHidden("E1", "3")).as("only that sub-edition").isTrue();
        assertThat(hidden.isHidden("E1", "2")).isFalse();
        assertThat(hidden.isHidden("ST1")).isFalse();
        assertThat(hidden.isHidden(null)).isFalse();
    }

    @Test
    void adminsAndTestersSeeThemTheRestDoNot() {
        assertThat(hidden.hidden("E2", player)).isTrue();
        assertThat(hidden.hidden("E2", anonymous)).isTrue();
        assertThat(hidden.hidden("E2", null)).isTrue();
        assertThat(hidden.hidden("E2", tester)).isFalse();
        assertThat(hidden.hidden("E2", admin)).isFalse();
        assertThat(hidden.hidden("E1", null, player)).isFalse();
    }

    record C(String edition, String sub) {
    }

    @Test
    void theCardsOfAHiddenEditionAreLeftOut() {
        List<C> cards = List.of(new C("E1", null), new C("E1", "3"), new C("E2", null), new C("ST1", null));
        assertThat(hidden.visible(cards, C::edition, C::sub, player)).containsExactly(new C("E1", null), new C("ST1", null));
        assertThat(hidden.visible(cards, C::edition, C::sub, tester)).isEqualTo(cards);
    }

    @Test
    void aDeckWithCardsOfAHiddenEditionIsHiddenFromPlayers() {
        assertThat(hidden.hiddenDeck(List.of("img-e1", "img-e2"), player)).isTrue();
        assertThat(hidden.hiddenDeck(List.of("img-e1", "img-e1-3"), player)).isTrue();
        assertThat(hidden.hiddenDeck(List.of("img-e1"), player)).isFalse();
        assertThat(hidden.hiddenDeck(List.of("img-e1", "img-e2"), admin)).isFalse();
    }

    @Test
    void aSavedEditionIsReadAgain() {
        assertThat(hidden.isHidden("ST1")).isFalse();
        when(editions.findAll()).thenReturn(List.of(edition("ST1", true)));
        assertThat(hidden.isHidden("ST1")).as("kept until it expires or is refreshed").isFalse();
        hidden.refresh();
        assertThat(hidden.isHidden("ST1")).isTrue();
        assertThat(hidden.isHidden("E2")).isFalse();
    }

    @Test
    void noneHidesNothing() {
        assertThat(HiddenEditions.none().hidden("E2", player)).isFalse();
    }

    static EditionMetadata edition(String id, Boolean isHidden) {
        EditionMetadata e = new EditionMetadata();
        e.setEditionId(id);
        e.setHidden(isHidden);
        return e;
    }

    private static DriveCard image(String id, String edition, String sub) {
        DriveCard d = new DriveCard();
        d.setId(id);
        d.setEdition(edition);
        d.setSubEdition(sub);
        return d;
    }
}
