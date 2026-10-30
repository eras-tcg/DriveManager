package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** The real name of a card image: its metadata's, since the file name turns every space into a hyphen. */
class CardNamesTest {

    private final CardRepository cards = mock(CardRepository.class);
    private final DriveCardRepository images = mock(DriveCardRepository.class);
    private final CardNames names = new CardNames(cards, images);

    @Test
    void theMetadataKeepsTheHyphensTheFileNameLost() {
        when(cards.findByEdition("E2")).thenReturn(List.of(meta("Roto-G0", "E2", 5, null, "B"), meta("Otra", "E2", 5, null, "R")));

        assertThat(names.nameFor("E2", 5, null, "B", "Roto G0")).isEqualTo("Roto-G0");
        // Another color, another sub-edition or no metadata: the file's name
        assertThat(names.nameFor("E2", 5, "1", "B", "Roto G0")).isEqualTo("Roto G0");
        assertThat(names.nameFor("E2", 6, null, "B", "Nueva")).isEqualTo("Nueva");
    }

    @Test
    void savingMetadataRenamesOnlyItsOwnImages() {
        DriveCard mine = image("a", "Sicario R3 D", "E2", 9, null, "B");
        DriveCard otherColor = image("b", "Sicario R3 D", "E2", 9, null, "R");
        DriveCard subEdition = image("c", "Sicario R3 D", "E2", 9, "1", "B");
        when(images.findByEditionAndNumber("E2", 9)).thenReturn(List.of(mine, otherColor, subEdition));

        assertThat(names.syncImages(meta("Sicario R3-D", "E2", 9, null, "B"))).isEqualTo(1);

        assertThat(mine.getName()).isEqualTo("Sicario R3-D");
        assertThat(otherColor.getName()).isEqualTo("Sicario R3 D");
        assertThat(subEdition.getName()).isEqualTo("Sicario R3 D");
        verify(images).saveAll(List.of(mine));
    }

    @Test
    void metadataOfAnotherCardDoesNotRenameTheImage() {
        when(cards.findByEdition("E2")).thenReturn(List.of(meta("Damon", "E2", 2, null, "P")));
        assertThat(names.nameFor("E2", 2, null, "P", "Bruja Mensajera")).isEqualTo("Bruja Mensajera");

        DriveCard bruja = image("a", "Bruja Mensajera", "E2", 2, null, "P");
        when(images.findByEditionAndNumber("E2", 2)).thenReturn(List.of(bruja));
        assertThat(names.syncImages(meta("Damon", "E2", 2, null, "P"))).isZero();
        assertThat(bruja.getName()).isEqualTo("Bruja Mensajera");
    }

    @Test
    void nothingToRenameWithoutANameOrANumber() {
        assertThat(names.syncImages(meta(null, "E2", 9, null, "B"))).isZero();
        assertThat(names.syncImages(meta("X", "E2", null, null, "B"))).isZero();
        verify(images, never()).saveAll(anyList());
    }

    private static Card meta(String name, String edition, Integer number, String sub, String color) {
        Card c = new Card();
        c.setCardName(name);
        c.setEdition(edition);
        c.setCardNumber(number);
        c.setSubEdition(sub);
        c.setColorIdentity(color);
        return c;
    }

    private static DriveCard image(String id, String name, String edition, int number, String sub, String color) {
        return new DriveCard(id, id + ".jpg", name, number, color, edition, sub, Instant.now());
    }
}
