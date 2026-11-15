package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.CardDraft;
import com.drive.drive_manager.repository.CardDraftRepository;
import com.drive.drive_manager.repository.CardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/** The card editor's drafts: unique codes, saved data and design, art kept apart. */
class CardDraftServiceTest {

    private CardDraftRepository drafts;
    private CardRepository cards;
    private DraftArtStore store;
    private CardDraftService service;
    private final List<CardDraft> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        drafts = mock(CardDraftRepository.class);
        cards = mock(CardRepository.class);
        store = mock(DraftArtStore.class);
        service = new CardDraftService(drafts, cards, store);
        Card published = card("E1", null, "R", 3);
        published.setId("m1");
        when(cards.findById("m1")).thenReturn(Optional.of(published));
        when(cards.findByEdition("E1")).thenReturn(List.of(published));
        when(drafts.findAll()).thenAnswer(call -> saved);
        when(drafts.findFirstByMetaId(anyString())).thenAnswer(call ->
                saved.stream().filter(d -> call.getArgument(0).equals(d.getMetaId())).findFirst());
        when(drafts.findById(anyString())).thenAnswer(call ->
                saved.stream().filter(d -> call.getArgument(0).equals(d.getId())).findFirst());
        when(drafts.save(any(CardDraft.class))).thenAnswer(call -> {
            CardDraft d = call.getArgument(0);
            if (d.getId() == null) {
                d.setId("d" + (saved.size() + 1));
                saved.add(d);
            }
            return d;
        });
    }

    private static Card card(String edition, String sub, String color, int number) {
        Card c = new Card();
        c.setEdition(edition);
        c.setSubEdition(sub);
        c.setColorIdentity(color);
        c.setCardNumber(number);
        c.setCardName("Carta");
        return c;
    }

    private static Card noNumber() {
        Card c = card("E1", null, "R", 0);
        c.setCardNumber(null);
        return c;
    }

    @Test
    void aNewCardNeedsAFreeCode() {
        CardDraft d = service.create(null, card("E1", null, "R", 4), Map.of("frameStyle", "full"), "ana");
        assertThat(d.getId()).isEqualTo("d1");
        assertThat(d.getDesign()).containsEntry("frameStyle", "full");
        assertThat(d.getUpdatedBy()).isEqualTo("ana");

        assertThatThrownBy(() -> service.create(null, card("E1", null, "R", 3), null, "ana"))
                .isInstanceOf(CardDraftService.CodeTaken.class).hasMessageContaining("E1-R03");
        assertThatThrownBy(() -> service.create(null, card("E1", "", "r", 4), null, "ana"))
                .isInstanceOf(CardDraftService.CodeTaken.class).hasMessageContaining("borrador");
        // Another sub-edition or color is another code
        assertThat(service.create(null, card("E1", "1", "R", 3), null, "ana").getId()).isEqualTo("d2");
        assertThat(service.create(null, card("E1", null, "B", 3), null, "ana").getId()).isEqualTo("d3");
        assertThatThrownBy(() -> service.create(null, noNumber(), null, "ana"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPublishedCardKeepsItsCodeInItsOnlyDraft() {
        CardDraft d = service.create("m1", card("E1", null, "R", 3), null, "ana");
        assertThat(d.getMetaId()).isEqualTo("m1");
        assertThat(service.update(d.getId(), card("E1", null, "R", 3), Map.of("goldBorder", true), "luis"))
                .get().extracting(CardDraft::getUpdatedBy).isEqualTo("luis");
        assertThatThrownBy(() -> service.create("m1", card("E1", null, "R", 3), null, "ana"))
                .isInstanceOf(CardDraftService.CodeTaken.class);
        assertThatThrownBy(() -> service.create("nope", card("E1", null, "R", 9), null, "ana"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theArtIsStoredApartAndReplaced() {
        CardDraft d = service.create(null, card("E1", null, "R", 4), null, "ana");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
        service.setArt(d.getId(), png, "ana");
        String first = d.getArtKey();
        assertThat(first).matches("card-art/d1/[0-9]+[.]png");
        assertThat(d.getArtType()).isEqualTo("image/png");
        verify(store).put(eq(first), eq(png), eq("image/png"));
        verify(store, never()).delete(anyString());

        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        service.setArt(d.getId(), jpg, "ana");
        assertThat(d.getArtKey()).endsWith(".jpeg");
        verify(store).delete(first);

        assertThatThrownBy(() -> service.setArt(d.getId(), new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}, "ana"))
                .isInstanceOf(IllegalArgumentException.class);

        String second = d.getArtKey();
        service.removeArt(d.getId(), "ana");
        assertThat(d.getArtKey()).isNull();
        verify(store).delete(second);
    }

    @Test
    void readsTheImageTypeFromItsFirstBytes() {
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        assertThat(CardDraftService.mediaType(webp)).isEqualTo("image/webp");
        assertThat(CardDraftService.mediaType(new byte[3])).isNull();
        assertThat(CardDraftService.code(card("E1", "1", "P", 1))).isEqualTo("E1.1-P01");
    }
}
