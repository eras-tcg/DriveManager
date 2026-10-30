package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.StagedChange;
import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.repository.StagedChangeRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Applying staged changes in the background, in batches, with a progress the page can ask for. */
class StagedChangeServiceTest {

    private final StagedChangeRepository staged = mock(StagedChangeRepository.class);
    private final DriveCardRepository cards = mock(DriveCardRepository.class);
    private final R2Service r2 = mock(R2Service.class);
    private final ActivityTracker activity = mock(ActivityTracker.class);
    private final StagedChangeService service = new StagedChangeService(staged, cards, mock(DriveClientFactory.class), r2, activity, mock(CardNames.class));

    @Test
    void appliesEveryChangeInBatchesAndKeepsTheServerAwake() {
        List<StagedChange> all = IntStream.range(0, 12).mapToObj(i -> delete("f" + i)).toList();
        when(staged.findAll()).thenReturn(all);
        when(staged.existsById(anyString())).thenReturn(true);

        StagedChangeService.ApplyProgress started = service.startApplyAll();
        assertThat(started.running()).isTrue();
        assertThat(started.total()).isEqualTo(12);
        assertThat(started.batches()).isEqualTo(3);

        StagedChangeService.ApplyProgress done = waitUntilFinished();
        assertThat(done.done()).isEqualTo(12);
        assertThat(done.failed()).isZero();
        assertThat(done.batch()).isEqualTo(3);
        verify(r2, times(12)).delete(anyString());
        verify(activity, times(3)).touch();
    }

    @Test
    void appliesOnlyTheChosenOnesAndReportsTheOnesThatFail() {
        when(staged.findAllById(any())).thenReturn(List.of(delete("a"), delete("b")));
        when(staged.existsById(anyString())).thenReturn(true);
        doThrow(new RuntimeException("R2 caído")).when(r2).delete("b");

        service.startApply(List.of("a", "b"));
        StagedChangeService.ApplyProgress done = waitUntilFinished();

        assertThat(done.total()).isEqualTo(2);
        assertThat(done.done()).isEqualTo(1);
        assertThat(done.failed()).isEqualTo(1);
        assertThat(done.errors()).containsExactly("b.jpg: R2 caído");
        verify(staged, never()).findAll();
    }

    @Test
    void nothingToApplyEndsAtOnce() {
        when(staged.findAll()).thenReturn(List.of());
        StagedChangeService.ApplyProgress p = service.startApplyAll();
        assertThat(p.running()).isFalse();
        assertThat(p.finishedAt()).isNotNull();
    }

    private StagedChangeService.ApplyProgress waitUntilFinished() {
        await().atMost(Duration.ofSeconds(5)).until(() -> !service.progress().running());
        return service.progress();
    }

    private static StagedChange delete(String id) {
        return new StagedChange(id, id + ".jpg", null, 1, "B", "E1", null, "delete", Instant.now());
    }
}
