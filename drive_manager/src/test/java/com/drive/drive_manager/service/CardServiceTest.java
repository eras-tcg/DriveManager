package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.repository.CardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** One document per card: unique codes, and the editor's fields kept on update. */
class CardServiceTest {

    private CardService service;
    private Card published;

    @BeforeEach
    void setUp() {
        CardRepository cards = mock(CardRepository.class);
        service = new CardService();
        ReflectionTestUtils.setField(service, "cardRepository", cards);
        ReflectionTestUtils.setField(service, "cardNames", mock(CardNames.class));
        published = card("E1", null, "R", 3);
        published.setId("m1");
        published.setArtKey("card-art/m1/1.png");
        when(cards.findById("m1")).thenReturn(Optional.of(published));
        when(cards.findByEdition("E1")).thenReturn(List.of(published));
        when(cards.save(any(Card.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static Card card(String edition, String sub, String color, Integer number) {
        Card c = new Card();
        c.setEdition(edition);
        c.setSubEdition(sub);
        c.setColorIdentity(color);
        c.setCardNumber(number);
        return c;
    }

    @Test
    void aCodeBelongsToOneCard() {
        assertThatThrownBy(() -> service.createCard(card("E1", "", "r", 3)))
                .isInstanceOf(CardService.CodeTaken.class).hasMessageContaining("E1-R03");
        assertThat(service.createCard(card("E1", "1", "R", 3))).isNotNull();
        assertThat(service.createCard(card("E1", null, "B", 3))).isNotNull();
        // A card keeps its own code
        assertThat(service.updateCard("m1", card("E1", null, "R", 3))).isPresent();
    }

    @Test
    void anUpdateKeepsTheEditorsFieldsAndTheArt() {
        Card change = new Card();
        change.setDesign(Map.of("frameStyle", "full"));
        change.setEnlace(true);
        change.setArrows(List.of("left"));
        change.setArtKey("other");
        Card saved = service.updateCard("m1", change).orElseThrow();
        assertThat(saved.getDesign()).containsEntry("frameStyle", "full");
        assertThat(saved.getEnlace()).isTrue();
        assertThat(saved.getArrows()).containsExactly("left");
        // The art changes only through CardArtService
        assertThat(saved.getArtKey()).isEqualTo("card-art/m1/1.png");
        // A save without design (the metadata page) leaves it
        assertThat(service.updateCard("m1", new Card()).orElseThrow().getDesign()).containsEntry("frameStyle", "full");
    }

    @Test
    void aStructureCardKeepsHowManyCopiesTheDeckBrings() {
        Card change = new Card();
        change.setDeckCopies(3);
        assertThat(service.updateCard("m1", change).orElseThrow().getDeckCopies()).isEqualTo(3);
        // Another save without it leaves it
        assertThat(service.updateCard("m1", new Card()).orElseThrow().getDeckCopies()).isEqualTo(3);
        Card wrong = new Card();
        wrong.setDeckCopies(-1);
        assertThatThrownBy(() -> service.updateCard("m1", wrong)).isInstanceOf(IllegalArgumentException.class);
    }
}
