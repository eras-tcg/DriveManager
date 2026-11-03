package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.repository.CardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A card's alternative art is set and removed from the site, in R2 apart from the cards' images. */
class AltArtServiceTest {

    private R2Service r2;
    private CardRepository cards;
    private AltArtService service;
    private Card titan;

    @BeforeEach
    void setUp() {
        r2 = mock(R2Service.class);
        cards = mock(CardRepository.class);
        service = new AltArtService(r2, cards);
        ReflectionTestUtils.setField(service, "publicUrl", "https://cdn.example/");
        titan = new Card();
        titan.setId("m1");
        titan.setCardName("Titanobrachios");
        when(cards.findById("m1")).thenReturn(Optional.of(titan));
        when(cards.save(any(Card.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void anUploadedImageBecomesTheCardsAlternativeArt() throws Exception {
        Card saved = service.put("m1", png(744, 1039)).orElseThrow();

        verify(r2).uploadRaw(eq("cards-alt/m1.jpg"), any(InputStream.class), anyLong());
        assertThat(saved.getAltImage()).startsWith("https://cdn.example/cards-alt/m1.jpg?v=");
        // The rest of the card is untouched
        assertThat(saved.getCardName()).isEqualTo("Titanobrachios");
    }

    @Test
    void itKeepsTheImagesSizeAsAJpeg() throws Exception {
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(AltArtService.jpeg(png(744, 1039))));
        assertThat(out.getWidth()).isEqualTo(744);
        assertThat(out.getHeight()).isEqualTo(1039);
    }

    @Test
    void aFileThatIsNoImageIsRefusedAndNothingIsStored() {
        assertThatThrownBy(() -> service.put("m1", new byte[]{1, 2, 3})).isInstanceOf(IllegalArgumentException.class);
        verify(r2, never()).uploadRaw(anyString(), any(InputStream.class), anyLong());
        verify(cards, never()).save(any(Card.class));
    }

    @Test
    void removingItLeavesTheCardWithoutOne() {
        titan.setAltImage("https://cdn.example/cards-alt/m1.jpg?v=1");
        Card saved = service.remove("m1").orElseThrow();

        verify(r2).deleteRaw("cards-alt/m1.jpg");
        assertThat(saved.getAltImage()).isNull();
    }

    @Test
    void anUnknownCardGetsNothing() throws Exception {
        when(cards.findById("nope")).thenReturn(Optional.empty());
        assertThat(service.put("nope", png(10, 10))).isEmpty();
        assertThat(service.remove("nope")).isEmpty();
        verify(r2, never()).deleteRaw(anyString());
    }

    private static byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", bytes);
        return bytes.toByteArray();
    }
}
