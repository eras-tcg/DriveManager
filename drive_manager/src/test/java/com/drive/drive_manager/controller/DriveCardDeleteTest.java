package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.service.R2Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Deleting a card's image record from the site: an editor card does not touch R2, and R2 failing does not stop it. */
class DriveCardDeleteTest {

    private DriveController controller;
    private DriveCardRepository repo;
    private R2Service r2;

    @BeforeEach
    void setUp() {
        controller = new DriveController();
        repo = mock(DriveCardRepository.class);
        r2 = mock(R2Service.class);
        ReflectionTestUtils.setField(controller, "driveCardRepository", repo);
        ReflectionTestUtils.setField(controller, "r2Service", r2);
    }

    @Test
    void anEditorCardIsDeletedWithoutTouchingR2() {
        DriveCard web = new DriveCard("web-m1", null, "Ciudad Muerta", 14, "B", "E1", "1", null, DriveCard.WEB);
        when(repo.findById("web-m1")).thenReturn(Optional.of(web));
        assertThat(controller.deleteCard("web-m1").getStatusCode().value()).isEqualTo(204);
        verify(r2, never()).delete(anyString());
        verify(repo).deleteById("web-m1");
    }

    @Test
    void aDriveCardIsDeletedEvenWhenR2Fails() {
        DriveCard drive = new DriveCard("drive-1", "E1-B14.jpg", "Ciudad Muerta", 14, "B", "E1", null, null);
        when(repo.findById("drive-1")).thenReturn(Optional.of(drive));
        doThrow(new RuntimeException("handshake_failure")).when(r2).delete("drive-1");
        assertThat(controller.deleteCard("drive-1").getStatusCode().value()).isEqualTo(204);
        verify(repo).deleteById("drive-1");
    }

    @Test
    void aMissingRecordIsNotFound() {
        when(repo.findById("x")).thenReturn(Optional.empty());
        assertThat(controller.deleteCard("x").getStatusCode().value()).isEqualTo(404);
    }
}
