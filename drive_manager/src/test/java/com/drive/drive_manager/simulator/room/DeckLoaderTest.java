package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.repository.DeckListRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A deck's cards take their metadata by the printed code, as the web joins them, not by the image's name. */
class DeckLoaderTest {

    @Test
    void anImageWhoseNameIsNotTheMetadatasStillGetsItByItsCode() {
        DriveCard image = new DriveCard();
        image.setId("img-1");
        image.setName("Caballero León Dorado");
        image.setEdition("E1");
        image.setSubEdition("3");
        image.setNumber(6);
        image.setColorIdentity("R");
        Card meta = new Card();
        meta.setCardName("Caballero de los Leones Dorados");
        meta.setEdition("E1");
        meta.setSubEdition("3");
        meta.setCardNumber(6);
        meta.setColorIdentity("R");
        meta.setCardType("creature");
        meta.setLevel(2);
        meta.setCardClasses(List.of("caballero", "retador"));
        meta.setColors(List.of("R"));

        DriveCardRepository images = mock(DriveCardRepository.class);
        CardRepository cards = mock(CardRepository.class);
        when(images.findAllById(any())).thenReturn(List.of(image));
        when(cards.findByEditionIn(any())).thenReturn(List.of(meta));
        DeckLoader loader = new DeckLoader(mock(DeckListRepository.class), cards, images, "https://r2.example");

        DeckList deck = new DeckList();
        deck.setCards(List.of("img-1"));
        CardSlot slot = loader.slots(deck).get(0);
        assertThat(slot.getCardName()).isEqualTo("Caballero de los Leones Dorados");
        assertThat(slot.getClasses()).contains("retador");
        assertThat(slot.getLevel()).isEqualTo(2);
    }
}
