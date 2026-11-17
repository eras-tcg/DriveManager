package com.drive.drive_manager.simulator.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A special summon or a Ritual can always be given up with nothing chosen ("No invocar"), even on a step that needs materials. */
class GiveUpSummonTest extends CardBatchSupport {

    private Prompt asking(String purpose, int min) {
        Prompt p = new Prompt();
        p.setId(room.nextId("prompt"));
        p.setPurpose(purpose);
        p.setPlayerId("host");
        p.setMin(min);
        p.setMax(min);
        p.setValidIds(new java.util.ArrayList<>(List.of("x")));
        p.setSubjectId("nobody");
        p.setSummonerId("someone");
        room.setPrompt(p);
        return p;
    }

    @Test
    void aSpecialSummonsMaterialStepIsGivenUpWithNothingChosen() {
        Prompt p = asking(PromptPurpose.SPECIAL.id(), 1);
        assertThat(Answers.answer(room, "host", p.getId(), List.of(), List.of())).isNotEqualTo("Debes elegir 1 carta");
        assertThat(room.getPrompt()).isNull();
    }

    @Test
    void anyOtherChoiceStillNeedsItsMinimum() {
        Prompt p = asking(PromptPurpose.BLOCK.id(), 1);
        assertThat(Answers.answer(room, "host", p.getId(), List.of(), List.of())).isEqualTo("Debes elegir 1 carta");
    }
}
