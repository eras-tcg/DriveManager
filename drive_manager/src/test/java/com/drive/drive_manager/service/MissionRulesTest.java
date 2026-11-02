package com.drive.drive_manager.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Which missions a player gets, and the periods they last. */
class MissionRulesTest {

    @Test
    void aPlayerGetsDifferentMissionsTheSameAllThePeriod() {
        List<MissionRules.Mission> daily = MissionRules.drawn("alice", "2026-11-02", 3);

        assertThat(daily).hasSize(3).doesNotHaveDuplicates();
        assertThat(MissionRules.drawn("alice", "2026-11-02", 3)).isEqualTo(daily);
    }

    @Test
    void aWeekAsksFiveTimesADay() {
        MissionRules.Mission play = MissionRules.byId("play");

        assertThat(play.target(false)).isEqualTo(3);
        assertThat(play.target(true)).isEqualTo(15);
        assertThat(MissionRules.byId("damage").target(true)).isEqualTo(2500);
    }

    @Test
    void weeksStartOnMonday() {
        LocalDate sunday = LocalDate.of(2026, 11, 8);

        assertThat(MissionRules.weekKey(LocalDate.of(2026, 11, 2))).isEqualTo(MissionRules.weekKey(sunday));
        assertThat(MissionRules.nextWeek(sunday)).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(MissionRules.weekKey(LocalDate.of(2026, 11, 9))).isNotEqualTo(MissionRules.weekKey(sunday));
    }
}
