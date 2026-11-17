package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.EditionMetadata;
import com.drive.drive_manager.repository.EditionMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** An edition's cover is chosen on the site and replaces the one it had. */
class EditionImageServiceTest {

    private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    private CardArtStore store;
    private EditionImageService service;
    private EditionMetadata edition;

    @BeforeEach
    void setUp() {
        EditionMetadataRepository repo = mock(EditionMetadataRepository.class);
        store = mock(CardArtStore.class);
        service = new EditionImageService(repo, store);
        edition = new EditionMetadata();
        edition.setEditionId("ST2");
        edition.setEditionImage("https://pub.r2.dev/editions/ST2.jpg");
        when(repo.findById("ST2")).thenReturn(Optional.of(edition));
        when(repo.save(any(EditionMetadata.class))).thenAnswer(call -> call.getArgument(0));
        when(store.publicUrl(anyString(), anyString())).thenAnswer(call -> "https://pub.r2.dev/" + call.getArgument(0));
    }

    @Test
    void aNewCoverGetsItsOwnNameAndReplacesOnlyCoversStoredHere() throws InterruptedException {
        service.set("ST2", JPG);
        String first = edition.getEditionImage();
        assertThat(first).matches("https://pub[.]r2[.]dev/editions/ST2-[0-9]+[.]jpeg");
        // The cover from Drive (editions/ST2.jpg) is left where it is
        verify(store, never()).delete(anyString());

        Thread.sleep(2);
        service.set("ST2", JPG);
        verify(store).delete("editions/" + first.substring(first.lastIndexOf('/') + 1));
        verify(store, times(2)).put(anyString(), eq(JPG), eq("image/jpeg"));
        assertThat(service.set("NOPE", JPG)).isEmpty();
    }

    @Test
    void onlyImagesAndOwnFilesAreHandled() {
        assertThatThrownBy(() -> service.set("ST2", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.get("../cards/x.jpeg")).isEmpty();
        assertThat(EditionImageService.ownFile("https://x/editions/ST2-12.png", "ST2")).contains("ST2-12.png");
        assertThat(EditionImageService.ownFile("https://x/editions/ST2.jpg", "ST2")).isEmpty();
        assertThat(EditionImageService.ownFile("https://x/editions/ST21-12.png", "ST2")).isEmpty();
    }
}
