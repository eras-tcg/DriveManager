package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A card's illustration is kept apart, referenced from the card's own document. */
class CardArtServiceTest {

    private CardArtStore store;
    private CardRepository cards;
    private DriveCardRepository images;
    private CardArtService service;
    private Card card;

    @BeforeEach
    void setUp() {
        cards = mock(CardRepository.class);
        store = mock(CardArtStore.class);
        images = mock(DriveCardRepository.class);
        service = new CardArtService(cards, store, images);
        card = new Card();
        card.setId("m1");
        when(cards.findById("m1")).thenReturn(Optional.of(card));
        when(cards.save(any(Card.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void theArtIsStoredApartAndReplaced() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
        service.set("m1", png);
        String first = card.getArtKey();
        assertThat(first).matches("card-art/m1/[0-9]+[.]png");
        assertThat(card.getArtType()).isEqualTo("image/png");
        verify(store).put(eq(first), eq(png), eq("image/png"));
        verify(store, never()).delete(anyString());

        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        service.set("m1", jpg);
        assertThat(card.getArtKey()).endsWith(".jpeg");
        verify(store).delete(first);

        String second = card.getArtKey();
        service.remove("m1");
        assertThat(card.getArtKey()).isNull();
        verify(store).delete(second);
        assertThat(service.set("nope", jpg)).isEmpty();
    }

    @Test
    void onlyImagesAreAccepted() {
        assertThatThrownBy(() -> service.set("m1", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        assertThat(CardArtService.mediaType(webp)).isEqualTo("image/webp");
        assertThat(CardArtService.mediaType(new byte[3])).isNull();
    }

    @Test
    void theRenderedCardIsKeptApartFromTheArt() {
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        service.setRender("m1", jpg);
        String first = card.getRenderKey();
        assertThat(first).matches("card-render/m1/[0-9]+[.]jpeg");
        assertThat(card.getArtKey()).isNull();
        service.setRender("m1", jpg);
        verify(store).delete(first);
        when(store.get(card.getRenderKey())).thenReturn(jpg);
        assertThat(service.getRender("m1")).get().extracting(CardArtService.Art::type).isEqualTo("image/jpeg");
    }

    @Test
    void aRenderedCardJoinsTheCatalogAsAnImageRecordOfItsOwn() {
        card.setCardName("Bea");
        card.setEdition("ST2");
        card.setColorIdentity("G");
        card.setCardNumber(1);
        service.setRender("m1", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        ArgumentCaptor<DriveCard> saved = ArgumentCaptor.forClass(DriveCard.class);
        verify(images).save(saved.capture());
        DriveCard image = saved.getValue();
        assertThat(image.getId()).isEqualTo("web-m1");
        assertThat(image.fromWeb()).isTrue();
        assertThat(List.of(image.getName(), image.getEdition(), image.getColorIdentity(), image.getNumber())).containsExactly("Bea", "ST2", "G", 1);
        // Its image is the editor's finished card, served by the cards' API
        assertThat(image.imageUrl("https://cdn.example")).startsWith("/api/cards/m1/render?v=");
    }

    @Test
    void aFileAnotherCardStillUsesIsNotDeleted() {
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        card.setRenderKey("card-render/st1/1.jpeg");
        when(cards.existsByRenderKeyAndIdNot("card-render/st1/1.jpeg", "m1")).thenReturn(true);
        service.setRender("m1", jpg);
        verify(store, never()).delete("card-render/st1/1.jpeg");
        String mine = card.getRenderKey();
        service.setRender("m1", jpg);
        verify(store).delete(mine);
    }

    @Test
    void aMissingFileIsNoImage() {
        card.setRenderKey("card-render/m1/1.jpeg");
        when(store.get("card-render/m1/1.jpeg")).thenThrow(new RuntimeException("no such file"));
        assertThat(service.getRender("m1")).isEmpty();
    }

    @Test
    void aCardWithItsDriveImageGetsNoSecondRecordAndItsDriveRecordShowsTheRender() {
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        card.setEdition("E2");
        card.setCardNumber(11);
        card.setColorIdentity("B");
        DriveCard drive = new DriveCard("drive-id", "E2-B11-VA-7.jpg", "VA 7", 11, "B", "E2", null, null);
        when(images.findByEditionAndNumber("E2", 11)).thenReturn(List.of(drive));
        service.setRender("m1", jpg);
        verify(images, never()).save(any(DriveCard.class));
        verify(images).deleteById("web-m1");
        verify(images).saveAll(List.of(drive));
        assertThat(drive.getRenderOf()).isEqualTo("m1");
        assertThat(drive.imageUrl("https://r2")).startsWith("/api/cards/m1/render?v=");
    }

    @Test
    void aDriveImageWithoutRenderIsServedFromR2() {
        DriveCard drive = new DriveCard("drive-id", "E2-B11-VA-7.jpg", "VA 7", 11, "B", "E2", null, null);
        assertThat(drive.imageUrl("https://r2")).isEqualTo("https://r2/cards/drive-id.jpg");
    }
}
