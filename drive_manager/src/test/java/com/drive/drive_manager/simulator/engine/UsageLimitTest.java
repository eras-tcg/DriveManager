package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.engine.EffectScript.Limit;
import com.drive.drive_manager.simulator.engine.EffectScript.Moment;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Rule 11.8: "una vez por turno" stops an effect after one use, "dos veces por turno" after two (per copy or between copies). */
@Tag("basic")
@Tag("engine")
class UsageLimitTest {

    private GameRoom room;
    private PlayerState host;

    @BeforeEach
    void setUp() {
        TestTable table = TestTable.inProgress(5, 6);
        room = table.room();
        host = table.host();
    }

    private EffectScript script(Limit limit) {
        return new EffectScript("prueba:limite", Moment.ACTIVATED, false, "Turno Aliado", "Roba 1 carta.",
                List.of(Compose.draw(1))).limited(limit);
    }

    private PendingEffect use(EffectScript script, CardSlot source) {
        PendingEffect e = Chain.candidate(script, "host", source, 0);
        Limit.take(script.limit(), room.getUsedThisTurn(), Chain.usageKey(e, script));
        return e;
    }

    @Test
    void twicePerTurnStopsTheCopyAfterItsSecondUse() {
        EffectScript twice = script(Limit.TWICE_PER_TURN);
        CardSlot first = onField(host, card("a", "Criatura", 1, 1, "R"), FRONT);
        CardSlot second = onField(host, card("b", "Criatura", 1, 1, "R"), BACK);

        PendingEffect e = use(twice, first);
        assertThat(Chain.limitReached(room, e, twice)).isFalse();
        use(twice, first);
        assertThat(Chain.limitReached(room, e, twice)).isTrue();
        assertThat(Chain.limitText(room, e, twice)).isEqualTo("su efecto ya se usó 2 veces este turno");
        // Another copy counts its own
        assertThat(Chain.limitReached(room, Chain.candidate(twice, "host", second, 0), twice)).isFalse();
    }

    @Test
    void twicePerTurnBetweenCopiesSharesItsTwoUses() {
        EffectScript twice = script(Limit.TWICE_PER_TURN_BETWEEN_COPIES);
        CardSlot first = onField(host, card("a", "Criatura", 1, 1, "R"), FRONT);
        CardSlot second = onField(host, card("b", "Criatura", 1, 1, "R"), BACK);

        use(twice, first);
        PendingEffect other = use(twice, second);
        assertThat(Chain.limitReached(room, other, twice)).isTrue();
    }

    @Test
    void oncePerTurnStillStopsAfterOneUse() {
        EffectScript once = script(Limit.ONCE_PER_TURN);
        PendingEffect e = use(once, onField(host, card("a", "Criatura", 1, 1, "R"), FRONT));
        assertThat(Chain.limitReached(room, e, once)).isTrue();
        assertThat(room.getUsedThisTurn()).noneMatch(k -> k.endsWith(Limit.SECOND_USE));
    }
}
