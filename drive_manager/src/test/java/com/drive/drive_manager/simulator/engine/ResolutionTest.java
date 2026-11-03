package com.drive.drive_manager.simulator.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResolutionTest {

    /** A step without a family would only fail when a card runs it. */
    @Test
    void everyStepHasItsFamily() {
        check(EffectStep.class);
    }

    private void check(Class<?> type) {
        if (type.isRecord()) {
            if (type != EffectStep.Do.class) assertThat(Resolution.hasFamily(type)).as(type.getSimpleName()).isTrue();
            return;
        }
        for (Class<?> sub : type.getPermittedSubclasses()) check(sub);
    }
}
