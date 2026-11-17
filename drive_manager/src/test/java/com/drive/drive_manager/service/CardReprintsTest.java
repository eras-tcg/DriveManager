package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.repository.CardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** A reprint is a card of its own in another slot, with its own copy of the art. */
class CardReprintsTest {

    private CardRepository cards;
    private CardArtStore store;
    private CardReprints reprints;
    private Card original;

    @BeforeEach
    void setUp() {
        cards = mock(CardRepository.class);
        store = mock(CardArtStore.class);
        CardService service = new CardService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "cardRepository", cards);
        reprints = new CardReprints(cards, store, service);
        original = new Card();
        original.setId("st1");
        original.setCardName("Eliséo");
        original.setEdition("ST1");
        original.setColorIdentity("B");
        original.setCardNumber(1);
        original.setDesign(Map.of("frameStyle", "full"));
        original.setArtKey("card-art/st1/1.jpeg");
        original.setArtType("image/jpeg");
        original.setRenderKey("card-render/st1/2.jpeg");
        original.setDeckCopies(4);
        when(cards.findById("st1")).thenReturn(Optional.of(original));
        when(cards.save(any(Card.class))).thenAnswer(call -> {
            Card c = call.getArgument(0);
            if (c.getId() == null) c.setId("new");
            return c;
        });
        when(store.get("card-art/st1/1.jpeg")).thenReturn(new byte[] {1, 2, 3});
    }

    @Test
    void theReprintCopiesTheCardAndGetsItsOwnArt() {
        when(cards.findByEdition("E1")).thenReturn(List.of());

        Card copy = reprints.reprint("st1", "E1", "1", 4);

        assertThat(copy.getId()).isEqualTo("new");
        assertThat(copy.getCardName()).isEqualTo("Eliséo");
        assertThat(copy.getEdition()).isEqualTo("E1");
        assertThat(copy.getSubEdition()).isEqualTo("1");
        assertThat(copy.getCardNumber()).isEqualTo(4);
        assertThat(copy.getDesign()).containsEntry("frameStyle", "full");
        // Its own art file, never the original's; the printing's own things stay behind
        assertThat(copy.getArtKey()).startsWith("card-art/new/").isNotEqualTo(original.getArtKey());
        verify(store).put(eq(copy.getArtKey()), eq(new byte[] {1, 2, 3}), eq("image/jpeg"));
        assertThat(copy.getRenderKey()).isNull();
        assertThat(copy.getDeckCopies()).isNull();
        assertThat(original.getEdition()).isEqualTo("ST1");
    }

    @Test
    void aTakenSlotIsRefused() {
        Card there = new Card();
        there.setId("x");
        there.setEdition("E1");
        there.setColorIdentity("B");
        there.setCardNumber(4);
        when(cards.findByEdition("E1")).thenReturn(List.of(there));

        assertThatThrownBy(() -> reprints.reprint("st1", "E1", null, 4)).isInstanceOf(CardReprints.InvalidReprint.class);
        verify(store, never()).put(anyString(), any(), anyString());
    }
}
