package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.AltArt;
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

/** A card's alternative arts are added, changed and removed from the site, in R2 apart from the cards' images. */
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
    void anUploadedImageBecomesANewAlternativeArt() throws Exception {
        Card saved = service.add("m1", png(744, 1039), null).orElseThrow();

        assertThat(saved.getAltArts()).hasSize(1);
        AltArt art = saved.getAltArts().get(0);
        assertThat(art.getId()).matches("[a-z0-9]{6}");
        assertThat(art.getOdds()).isNull();
        verify(r2).uploadRaw(eq("cards-alt/m1-" + art.getId() + ".jpg"), any(InputStream.class), anyLong());
        assertThat(art.getUrl()).startsWith("https://cdn.example/cards-alt/m1-" + art.getId() + ".jpg?v=");
        // The rest of the card is untouched
        assertThat(saved.getCardName()).isEqualTo("Titanobrachios");
    }

    @Test
    void aCardCanHaveSeveralEachWithItsOwnOdds() throws Exception {
        service.add("m1", png(10, 10), null);
        Card saved = service.add("m1", png(10, 10), 0.002).orElseThrow();
        assertThat(saved.getAltArts()).hasSize(2);
        assertThat(saved.getAltArts().get(1).getOdds()).isEqualTo(0.002);
        assertThat(saved.getAltArts().get(0).getId()).isNotEqualTo(saved.getAltArts().get(1).getId());

        String first = saved.getAltArts().get(0).getId();
        assertThat(service.setOdds("m1", first, 0.5).orElseThrow().getAltArts().get(0).getOdds()).isEqualTo(0.5);
        assertThat(service.setOdds("m1", first, null).orElseThrow().getAltArts().get(0).getOdds()).isNull();
        assertThatThrownBy(() -> service.setOdds("m1", first, 1.5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSingleArtOfBeforeBecomesTheFirstOfTheList() throws Exception {
        titan.setAltImage("https://cdn.example/cards-alt/m1.jpg?v=1");
        assertThat(titan.getAltArts()).containsExactly(new AltArt("0", "https://cdn.example/cards-alt/m1.jpg?v=1", null));

        Card saved = service.add("m1", png(10, 10), null).orElseThrow();
        assertThat(saved.getAltArts()).hasSize(2);
        assertThat(saved.getAltArts().get(0).getId()).isEqualTo("0");
        assertThat(saved.getAltImage()).isNull();

        // Its image keeps the key it had
        service.remove("m1", "0");
        verify(r2).deleteRaw("cards-alt/m1.jpg");
        assertThat(titan.getAltArts()).hasSize(1);
    }

    @Test
    void replacingAnArtKeepsItsIdAndOdds() throws Exception {
        String id = service.add("m1", png(10, 10), 0.01).orElseThrow().getAltArts().get(0).getId();
        Card saved = service.replace("m1", id, png(20, 20)).orElseThrow();
        assertThat(saved.getAltArts()).hasSize(1);
        assertThat(saved.getAltArts().get(0).getId()).isEqualTo(id);
        assertThat(saved.getAltArts().get(0).getOdds()).isEqualTo(0.01);
        assertThat(service.replace("m1", "nope", png(20, 20))).isEmpty();
    }

    @Test
    void itKeepsTheImagesSizeAsAJpeg() throws Exception {
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(AltArtService.jpeg(png(744, 1039))));
        assertThat(out.getWidth()).isEqualTo(744);
        assertThat(out.getHeight()).isEqualTo(1039);
    }

    @Test
    void aFileThatIsNoImageOrBadOddsAreRefusedAndNothingIsStored() {
        assertThatThrownBy(() -> service.add("m1", new byte[]{1, 2, 3}, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.add("m1", new byte[]{1, 2, 3}, -0.1)).isInstanceOf(IllegalArgumentException.class);
        verify(r2, never()).uploadRaw(anyString(), any(InputStream.class), anyLong());
        verify(cards, never()).save(any(Card.class));
    }

    @Test
    void removingOneLeavesTheOthers() throws Exception {
        service.add("m1", png(10, 10), null);
        Card two = service.add("m1", png(10, 10), null).orElseThrow();
        String gone = two.getAltArts().get(0).getId();
        String kept = two.getAltArts().get(1).getId();

        Card saved = service.remove("m1", gone).orElseThrow();
        verify(r2).deleteRaw("cards-alt/m1-" + gone + ".jpg");
        assertThat(saved.getAltArts()).extracting(AltArt::getId).containsExactly(kept);
        assertThat(service.remove("m1", "nope")).isEmpty();
    }

    @Test
    void anUnknownCardGetsNothing() throws Exception {
        when(cards.findById("nope")).thenReturn(Optional.empty());
        assertThat(service.add("nope", png(10, 10), null)).isEmpty();
        assertThat(service.remove("nope", "a")).isEmpty();
        verify(r2, never()).deleteRaw(anyString());
    }

    private static byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", bytes);
        return bytes.toByteArray();
    }
}
