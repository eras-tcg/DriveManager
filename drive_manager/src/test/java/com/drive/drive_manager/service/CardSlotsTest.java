package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Moving cards between slots: a chain is checked as a whole and each card's images go with it. */
class CardSlotsTest {

    private final CardRepository cards = mock(CardRepository.class);
    private final DriveCardRepository images = mock(DriveCardRepository.class);
    private final CardSlots slots = new CardSlots(cards, images);

    @Test
    void aChainSwapsTwoCardsAndTheirImages() {
        Card ephos = card("a", "Ephos", "E1", null, 3);
        Card jhav = card("b", "Jhav", "E1", "1", 5);
        DriveCard ephosImg = image("d1", "E1-B03-Ephos.jpg", "E1", null, 3);
        DriveCard jhavImg = image("d2", "E1_1-B05-Jhav.jpg", "E1", "1", 5);
        DriveCard web = new DriveCard("web-b", null, "Jhav", 5, "B", "E1", "1", Instant.EPOCH, DriveCard.WEB);
        when(cards.findById("a")).thenReturn(Optional.of(ephos));
        when(cards.findById("b")).thenReturn(Optional.of(jhav));
        when(cards.findByEdition("E1")).thenReturn(List.of(ephos, jhav));
        when(images.findById(anyString())).thenReturn(Optional.empty());
        when(images.findById("web-b")).thenReturn(Optional.of(web));
        when(images.findByEditionAndNumber("E1", 3)).thenReturn(List.of(ephosImg));
        when(images.findByEditionAndNumber("E1", 5)).thenReturn(List.of(jhavImg));
        when(cards.saveAll(any())).thenAnswer(i -> List.copyOf((java.util.Collection<Card>) i.getArgument(0)));

        // Ephos takes Jhav's place, Jhav takes Ephos'
        slots.move(List.of(new CardSlots.Move("a", "E1", "1", 5), new CardSlots.Move("b", "E1", "", 3)));

        assertThat(ephos.getSubEdition()).isEqualTo("1");
        assertThat(ephos.getCardNumber()).isEqualTo(5);
        assertThat(jhav.getSubEdition()).isNull();
        assertThat(jhav.getCardNumber()).isEqualTo(3);
        assertThat(ephosImg.getFileName()).isEqualTo("E1_1-B05-Ephos.jpg");
        assertThat(ephosImg.getSubEdition()).isEqualTo("1");
        assertThat(jhavImg.getFileName()).isEqualTo("E1-B03-Jhav.jpg");
        assertThat(web.getNumber()).isEqualTo(3);
        assertThat(web.getSubEdition()).isNull();
    }

    @Test
    void aSlotTakenByACardThatStaysIsRefused() {
        Card ephos = card("a", "Ephos", "E1", null, 3);
        Card other = card("c", "Petrus", "E2", null, 7);
        when(cards.findById("a")).thenReturn(Optional.of(ephos));
        when(cards.findByEdition("E2")).thenReturn(List.of(other));

        assertThatThrownBy(() -> slots.move(List.of(new CardSlots.Move("a", "E2", null, 7))))
                .isInstanceOf(CardSlots.InvalidMove.class)
                .hasMessageContaining("E2-B07");
        verify(cards, never()).saveAll(any());
    }

    @Test
    void twoCardsCannotEndInTheSameSlot() {
        when(cards.findById("a")).thenReturn(Optional.of(card("a", "Ephos", "E1", null, 3)));
        when(cards.findById("b")).thenReturn(Optional.of(card("b", "Jhav", "E1", null, 4)));

        assertThatThrownBy(() -> slots.move(List.of(new CardSlots.Move("a", "E2", null, 1), new CardSlots.Move("b", "E2", "", 1))))
                .isInstanceOf(CardSlots.InvalidMove.class);
    }

    @Test
    void aFileNameWithoutCodeIsKept() {
        Card c = card("a", "X", "E3", null, 12);
        assertThat(CardSlots.renamed("portada.jpg", c)).isEqualTo("portada.jpg");
        assertThat(CardSlots.renamed("E2-B05-Roto-G0.jpg", c)).isEqualTo("E3-B12-Roto-G0.jpg");
    }

    private static Card card(String id, String name, String edition, String sub, int number) {
        Card c = new Card();
        c.setId(id);
        c.setCardName(name);
        c.setEdition(edition);
        c.setSubEdition(sub);
        c.setColorIdentity("B");
        c.setCardNumber(number);
        return c;
    }

    private static DriveCard image(String id, String file, String edition, String sub, int number) {
        return new DriveCard(id, file, null, number, "B", edition, sub, Instant.EPOCH);
    }
}
