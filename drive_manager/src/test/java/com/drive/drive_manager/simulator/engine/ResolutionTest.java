package com.drive.drive_manager.simulator.engine;

import kotlin.jvm.JvmClassMappingKt;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResolutionTest {

    /** A step without a family would only fail when a card runs it. */
    @Test
    void everyStepHasItsFamily() {
        check(EffectStep.class);
    }

    /** Every step (a record, or a Kotlin data object where a record had no fields) under the sealed families. */
    private void check(Class<?> type) {
        if (type.isRecord() || kotlinObject(type)) {
            if (type != EffectStep.Do.class) assertThat(Resolution.hasFamily(JvmClassMappingKt.getKotlinClass(type))).as(type.getSimpleName()).isTrue();
            return;
        }
        Class<?>[] permitted = type.getPermittedSubclasses();
        assertThat(permitted).as(type.getSimpleName() + " is neither a step nor a sealed family").isNotNull();
        for (Class<?> sub : permitted) check(sub);
    }

    /** A Kotlin {@code object}: a final class whose one instance is its static {@code INSTANCE}. */
    private static boolean kotlinObject(Class<?> type) {
        if (type.isInterface() || !java.lang.reflect.Modifier.isFinal(type.getModifiers())) return false;
        try {
            return type.getField("INSTANCE").getType() == type;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }
}
