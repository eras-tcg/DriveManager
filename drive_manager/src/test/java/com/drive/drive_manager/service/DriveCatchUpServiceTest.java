package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.dto.StagedChange;
import com.drive.drive_manager.dto.SyncReport;
import com.drive.drive_manager.dto.SyncState;
import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.repository.StagedChangeRepository;
import com.drive.drive_manager.repository.SyncReportRepository;
import com.drive.drive_manager.repository.SyncStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The catch-up of a server that wakes up: Drive's change list from the saved token, or a full comparison without one. */
class DriveCatchUpServiceTest {

    private final DriveWatchService watch = mock(DriveWatchService.class);
    private final DriveParser parser = mock(DriveParser.class);
    private final SyncStateRepository states = mock(SyncStateRepository.class);
    private final SyncReportRepository reports = mock(SyncReportRepository.class);
    private final DriveCardRepository cards = mock(DriveCardRepository.class);
    private final StagedChangeRepository staged = mock(StagedChangeRepository.class);
    private DriveCatchUpService service;

    @BeforeEach
    void setUp() {
        service = new DriveCatchUpService(watch, parser, states, reports, cards, staged);
        ReflectionTestUtils.setField(service, "stateId", "drive-aws");
        when(reports.findById("drive-aws")).thenReturn(Optional.empty());
    }

    @Test
    void readsDrivesChangesFromTheSavedTokenAndReportsThem() throws Exception {
        when(states.findById("drive-aws")).thenReturn(Optional.of(new SyncState("drive-aws", "t1", null, null, null)));
        StagedChange change = upsert("f1", "E1-B01-Jhav.jpg", "E1", null, "B", 1);
        when(watch.fetchChanges("t1")).thenReturn(new DriveWatchService.ChangesResult(List.of(change), "t2"));

        SyncReport done = runAndWait();

        assertThat(done.getMode()).isEqualTo("changes");
        assertThat(done.getItems()).containsExactly(new SyncReport.Item("f1", "E1-B01-Jhav.jpg", "upsert"));
        ArgumentCaptor<SyncState> saved = ArgumentCaptor.forClass(SyncState.class);
        verify(states).save(saved.capture());
        assertThat(saved.getValue().getPageToken()).isEqualTo("t2");
        verify(parser, never()).scan(any(), any(), any());
    }

    @Test
    void withoutATokenItComparesDriveWithTheRegisteredCards() throws Exception {
        when(states.findById("drive-aws")).thenReturn(Optional.empty());
        when(watch.startPageToken()).thenReturn("fresh");
        when(parser.scan(any(), any(), any())).thenReturn(List.of(
                upsert("same", "E1-B01-Jhav.jpg", "E1", null, "B", 1),
                upsert("moved", "E1_1-P04-Cofre-Mímico.jpg", "E1", "1", "P", 4),
                upsert("new", "E1-B02-Eisten.jpg", "E1", null, "B", 2)));
        when(cards.findAll()).thenReturn(List.of(
                card("same", "E1-B01-Jhav.jpg", "E1", null, "B", 1, "Jhav corregido"),
                card("moved", "E1_1-P04-Cofre-Mímico.jpg", "E1", null, "P", 4, "Cofre Mímico"),
                card("gone", "E1-B03-Viejo.jpg", "E1", null, "B", 3, "Viejo")));

        SyncReport done = runAndWait();

        assertThat(done.getMode()).isEqualTo("full");
        // A name corrected in drive_cards is not a change; another section, a new file and a missing one are
        assertThat(done.getItems()).extracting(SyncReport.Item::fileId, SyncReport.Item::action)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("moved", "upsert"),
                        org.assertj.core.groups.Tuple.tuple("new", "upsert"), org.assertj.core.groups.Tuple.tuple("gone", "delete"));
        ArgumentCaptor<SyncState> saved = ArgumentCaptor.forClass(SyncState.class);
        verify(states).save(saved.capture());
        assertThat(saved.getValue().getPageToken()).isEqualTo("fresh");
    }

    @Test
    void aScanThatSeesAlmostNothingDeletesNothing() throws Exception {
        when(states.findById("drive-aws")).thenReturn(Optional.empty());
        when(watch.startPageToken()).thenReturn("fresh");
        when(parser.scan(any(), any(), any())).thenReturn(List.of());
        when(cards.findAll()).thenReturn(List.of(card("a", "E1-B01-Jhav.jpg", "E1", null, "B", 1, "Jhav")));

        SyncReport done = runAndWait();

        assertThat(done.getStatus()).isEqualTo("error");
        verify(staged, never()).saveAll(any());
        verify(states, never()).save(any());
    }

    private SyncReport runAndWait() {
        SyncReport running = service.start();
        assertThat(running.getStatus()).isEqualTo("running");
        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> !"running".equals(running.getStatus()));
        return running;
    }

    private static StagedChange upsert(String id, String file, String edition, String sub, String color, int number) {
        return new StagedChange(id, file, file, number, color, edition, sub, "upsert", Instant.now());
    }

    private static DriveCard card(String id, String file, String edition, String sub, String color, int number, String name) {
        return new DriveCard(id, file, name, number, color, edition, sub, Instant.now());
    }
}
