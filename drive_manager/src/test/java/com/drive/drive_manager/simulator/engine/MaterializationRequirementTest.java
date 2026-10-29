package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Materialización as printed: the zones of the material, the summoner's levels, alternatives, and no summoner. */
class MaterializationRequirementTest {

    private GameRoom room;
    private PlayerState host;

    @BeforeEach
    void setUp() {
        TestTable table = TestTable.inProgress(8, 8);
        room = table.room();
        host = table.host();
    }

    @Test
    void withoutASummonerTwoCreaturesOfTheHandOrTributesBecomeItsMaterials() {
        // SAR-2: "2 criaturas nivel 1 en tu mano o tributos"
        CardSlot sar = blue(materialization(card("sar", "SAR 2", 2, 2, "B"), 2, "2 criaturas nivel 1 en tu mano o tributos."));
        CardSlot fromHand = blue(card("h1", "En mano", 1, 1, "B"));
        CardSlot fromTributes = blue(card("t1", "En tributos", 1, 1, "B"));
        host.getHand().addAll(List.of(sar, fromHand));
        host.getTributeZone().add(fromTributes);
        assertThat(Summons.canSpecial(room, "host", sar)).isTrue();
        assertThat(Summons.specialByPlayer(room, "host", "sar", null)).isNull();
        Prompt materials = room.getPrompt();
        assertThat(materials.getValidIds()).containsExactlyInAnyOrder("h1", "t1");
        answer(materials, "h1", "t1");
        answerRest();
        assertThat(host.getField()).contains(sar);
        assertThat(sar.getMaterials()).containsExactlyInAnyOrder(fromHand, fromTributes);
        assertThat(sar.isTapped()).isFalse();
    }

    @Test
    void theMaterialMayComeFromTheHandWhenTheCardSaysSo() {
        // VA-7: "criatura nivel 1 + material nivel 1 en campo o mano"
        CardSlot va = blue(materialization(card("va", "VA 7", 1, 2, "B"), 1, "criatura nivel 1 + material nivel 1 en campo o mano."));
        onField(host, blue(card("s", "Invocador", 1, 1, "B")));
        CardSlot inHand = blue(card("m", "Material", 1, 1, "B"));
        host.getTributeZone().add(blue(card("t", "No vale", 1, 1, "B")));
        host.getHand().addAll(List.of(va, inHand));
        assertThat(Summons.specialByPlayer(room, "host", "va", "s")).isNull();
        Prompt materials = room.getPrompt();
        assertThat(materials.getValidIds()).containsExactly("m");
        answer(materials, "m");
        answerRest();
        assertThat(host.getField()).contains(va);
        assertThat(va.getMaterials()).contains(inHand);
    }

    @Test
    void onlyTheFieldWhenItSaysEnElCampo() {
        // Iron: "criatura nivel 3 + material nivel 3 en el campo"
        CardSlot iron = blue(materialization(card("ir", "Iron", 3, 3, "B"), 3, "criatura nivel 3 + material nivel 3 en el campo."));
        onField(host, blue(card("s", "Invocador", 3, 3, "B")));
        host.getTributeZone().add(blue(card("t", "En tributos", 3, 3, "B")));
        host.getHand().add(iron);
        assertThat(Summons.canSpecial(room, "host", iron)).isFalse();
        onField(host, blue(card("f", "En el campo", 3, 3, "B")), BACK);
        assertThat(Summons.canSpecial(room, "host", iron)).isTrue();
    }

    @Test
    void aSummonerOfEitherLevelAndAnAlternativeWithoutMaterial() {
        // Unidad H3-R0: "criatura nivel 3 o 4 + material nivel 3 en campo o mano"
        CardSlot unidad = blue(materialization(card("un", "Unidad H3 R0", 3, 3, "B"), 3, "criatura nivel 3 o 4 + material nivel 3 en campo o mano."));
        CardSlot level4 = onField(host, blue(card("s4", "Nivel 4", 4, 4, "B")));
        host.getHand().addAll(List.of(unidad, blue(card("m3", "Material 3", 3, 3, "B"))));
        assertThat(Summons.specialSummoners(host, unidad, Summons.specialRequirement(unidad))).contains(level4);
        // Proto Vigilante H2: "(criatura nivel 3) o (criatura nivel 2 + material nivel 2 en tu mano o campo)"
        CardSlot proto = blue(materialization(card("pv", "Proto Vigilante H2", 2, 2, "B"), 1,
                "(criatura nivel 3) o (criatura nivel 2 + material nivel 2 en tu mano o campo)."));
        CardSlot level3 = onField(host, blue(card("s3", "Nivel 3", 3, 3, "B")));
        host.getHand().add(proto);
        assertThat(Summons.specialByPlayer(room, "host", "pv", "s3")).isNull();
        answerRest();
        assertThat(host.getField()).contains(proto).doesNotContain(level3);
        assertThat(proto.getMaterials()).containsExactly(level3);
    }

    // ------------------------------------------------------------------ Helpers

    private static CardSlot materialization(CardSlot card, int cost, String requirement) {
        card.setSpecialSummonKind("materialization");
        card.setSpecialCost(cost);
        card.setRequirement(requirement);
        return card;
    }

    private static CardSlot blue(CardSlot card) {
        card.setClasses(new java.util.ArrayList<>(List.of("ente")));
        return card;
    }

    /** Declines what is left (the move up to the Línea frontal, optional effects). */
    private void answerRest() {
        for (int i = 0; i < 6 && room.getPrompt() != null; i++) {
            Prompt p = room.getPrompt();
            Answers.answer(room, p.getPlayerId(), p.getId(), List.of(), List.of());
        }
    }

    private void answer(Prompt prompt, String... ids) {
        assertThat(prompt).isNotNull();
        assertThat(Answers.answer(room, prompt.getPlayerId(), prompt.getId(), List.of(ids), List.of())).isNull();
    }
}
