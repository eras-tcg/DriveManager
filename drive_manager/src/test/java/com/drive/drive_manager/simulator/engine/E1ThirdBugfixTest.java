package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixes reported while playing E1 (2026-10-30, third batch): a summoner declared from the hand, paying a cost whose effect
 * would do nothing, strength 0 checked again after a replacement, reactions before a removal and several cards.
 */
class E1ThirdBugfixTest {

    private GameRoom room;
    private PlayerState host;
    private PlayerState guest;

    @BeforeEach
    void setUp() {
        TestTable table = TestTable.inProgress(8, 8);
        room = table.room();
        host = table.host();
        guest = table.guest();
    }

    // ------------------------------------------------------------------ Materialización with a declared summoner

    @Test
    void rotoG0DeclaresItsSummonerFromTheHandAndTakesTwoMoreFromTheHandOrTributes() {
        CardSlot roto = materialization(card("roto", "Roto-G0", 4, 4, "B"), 4,
                "criatura nivel 3 en mano + 2 criaturas nivel 3 en tu mano o tributos.");
        CardSlot summoner = card("s", "Invocador", 3, 3, "B");
        CardSlot fromHand = card("h", "En mano", 3, 3, "B");
        CardSlot fromTributes = card("t", "En tributos", 3, 3, "B");
        host.getHand().addAll(List.of(roto, summoner, fromHand));
        host.getTributeZone().add(fromTributes);
        assertThat(Summons.canSpecial(room, "host", roto)).isTrue();

        assertThat(Summons.specialByPlayer(room, "host", "roto", null)).isNull();
        Prompt declare = room.getPrompt();
        assertThat(declare.getChosenLabel()).isEqualTo("Invocador");
        // "invocador … en mano": the one in the Tributos cannot be it
        assertThat(declare.getValidIds()).containsExactlyInAnyOrder("s", "h");
        answer(declare, "s");
        Prompt materials = room.getPrompt();
        assertThat(materials.getValidIds()).containsExactlyInAnyOrder("h", "t");
        answer(materials, "h", "t");
        answerAll();

        assertThat(host.getField()).contains(roto);
        assertThat(roto.getMaterials()).containsExactly(summoner, fromHand, fromTributes);
    }

    @Test
    void rotoG0NeedsItsSummonerInTheHand() {
        CardSlot roto = materialization(card("roto", "Roto-G0", 4, 4, "B"), 4,
                "criatura nivel 3 en mano + 2 criaturas nivel 3 en tu mano o tributos.");
        host.getHand().add(roto);
        for (int i = 0; i < 3; i++) host.getTributeZone().add(card("t" + i, "En tributos", 3, 3, "B"));
        assertThat(Summons.canSpecial(room, "host", roto)).isFalse();
        host.getHand().add(card("s", "Invocador", 3, 3, "B"));
        assertThat(Summons.canSpecial(room, "host", roto)).isTrue();
    }

    @Test
    void protoVigilanteH2AsPrintedTakesALevel2Or3SummonerAndALevel2Material() {
        CardSlot proto = materialization(card("pv", "Proto Vigilante H2", 3, 3, "B"), 1,
                "criatura nivel 2 o 3 + material nivel 2 en mano o campo.");
        host.getHand().add(proto);
        onField(host, card("s3", "Nivel 3", 3, 3, "B"));
        assertThat(Summons.canSpecial(room, "host", proto)).isFalse();
        host.getHand().add(card("m2", "Material 2", 2, 2, "B"));
        assertThat(Summons.canSpecial(room, "host", proto)).isTrue();
        assertThat(Summons.specialByPlayer(room, "host", "pv", "s3")).isNull();
        answer(room.getPrompt(), "m2");
        answerAll();
        assertThat(host.getField()).contains(proto);
        assertThat(proto.getMaterials()).extracting(CardSlot::getInstanceId).containsExactly("s3", "m2");
    }

    // ------------------------------------------------------------------ Paying a cost whose effect would do nothing

    @Test
    void sacerdotisaCanPayItsMaterialsWithNoValidCreatureInTheTributes() {
        CardSlot sacerdotisa = onField(host, card("sac", "Sacerdotisa Del Portal Del Eclipse", 5, 4, "B"));
        for (int i = 0; i < 3; i++) sacerdotisa.getMaterials().add(card("m" + i, "Material", 1, 1, "B"));
        assertThat(Chain.activatable(room, "host")).extracting(Chain.Activatable::instanceId).contains("sac");
        assertThat(Chain.activateByPlayer(room, "host", "sac", null)).isNull();
        drive();
        assertThat(sacerdotisa.getMaterials()).isEmpty();
        assertThat(host.getTributeZone()).hasSize(3);
    }

    @Test
    void sombraErranteCanTributeFromTheHandWithNoEspectroInTheTributes() {
        CardSlot sombra = onField(host, card("so", "Sombra Errante", 2, 2, "P"));
        host.getHand().add(card("h", "Mano", 1, 1, "P"));
        Removals.destroyByPlayer(room, "guest", "host", "so");
        // Offered though there is no [espectro] nivel 1 to summon
        assertThat(room.getPrompt()).isNotNull();
        drive();
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).containsExactly("h");
        assertThat(host.getDiscardPile()).contains(sombra);
        assertThat(host.getField()).doesNotContain(sombra);
    }

    @Test
    void theSummonedCreatureOfSacerdotisaCannotDeclareAnAttack() {
        CardSlot sacerdotisa = onField(host, card("sac", "Sacerdotisa Del Portal Del Eclipse", 5, 4, "B"));
        for (int i = 0; i < 3; i++) sacerdotisa.getMaterials().add(card("m" + i, "Material", 1, 1, "B"));
        CardSlot ente = card("en", "Ente", 3, 3, "B");
        ente.setClasses(new ArrayList<>(List.of("ente")));
        host.getTributeZone().add(ente);
        assertThat(Chain.activateByPlayer(room, "host", "sac", null)).isNull();
        drive("en");
        assertThat(host.getField()).contains(ente);
        assertThat(EffectEngine.keywords(ente)).contains(EffectEngine.NO_ATTACK);
    }

    // ------------------------------------------------------------------ Strength 0 after a replacement

    @Test
    void aCreatureStillAtStrength0IsDestroyedAfterItsReplacementIsUsed() {
        CardSlot proto = onField(host, card("pv", "Proto Vigilante H2", 3, 3, "B"));
        proto.setStrength(9);
        for (int i = 0; i < 2; i++) proto.getMaterials().add(card("m" + i, "Material", 1, 1, "B"));
        proto.setTurnStrength(-9);
        Removals.destroyIfNoStrength(room, "guest", host, proto);
        // "en su lugar, puedes tributar 2 materiales": used; it is still at 0, so it is destroyed anyway
        Prompt replace = room.getPrompt();
        assertThat(replace.getPurpose()).isEqualTo(EffectEngine.PURPOSE_REPLACE);
        answer(replace, replace.getEffects().get(0).effectId());
        answerAll();
        assertThat(host.getField()).doesNotContain(proto);
        assertThat(host.getDiscardPile()).contains(proto);
    }

    // ------------------------------------------------------------------ Reactions before a removal

    @Test
    void deriduragonTributesTheResourcesButTheCardIsStillDestroyed() {
        CardSlot holder = onField(host, card("d", "Dragón", 3, 3, "G"));
        CardSlot deriduragon = card("der", "Deriduragon", 2, 2, "G");
        CardSlot other = card("r2", "Otro recurso", 1, 1, "G");
        holder.getResources().addAll(List.of(deriduragon, other));
        Removals.destroyByPlayer(room, "guest", "host", "d");
        // A card that is not in this test's flow (manual destruction): the board destroys it with no replacement
        assertThat(host.getDiscardPile()).contains(holder);

        CardSlot second = onField(host, card("d2", "Dragón 2", 3, 3, "G"));
        second.getResources().addAll(List.of(card("der2", "Deriduragon", 2, 2, "G"), card("r3", "Otro", 1, 1, "G")));
        CardSlot enemy = onField(guest, card("x", "Destructor", 1, 1, "R"));
        GameEvent ignored = Removals.destroy(room, second, host, false, EffectEngine.CAUSE_EFFECT, "guest", enemy.getInstanceId(), null);
        Prompt react = room.getPrompt() != null ? room.getPrompt() : promptRemoval();
        assertThat(react.getEffects()).hasSize(1);
        // The Recurso that gives it, small on the card
        assertThat(react.getEffects().get(0).inheritedFromName()).isEqualTo("Deriduragon");
        answer(react, react.getEffects().get(0).effectId());
        assertThat(host.getField()).doesNotContain(second);
        assertThat(host.getDiscardPile()).contains(second);
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).contains("der2", "r3");
    }

    // ------------------------------------------------------------------ Cards

    @Test
    void driadaOnlyStopsTheUntapAtTheStartOfTheTurnAndUzumeTheWholeTurn() {
        CardSlot weak = onField(guest, card("w", "Débil", 1, 1, "R"));
        weak.setTapped(true);
        weak.setNoStartUntap(1);
        assertThat(EffectEngine.reactivable(weak)).isTrue();
        weak.setNoStartUntap(0);
        weak.setNoUntap(1);
        assertThat(EffectEngine.reactivable(weak)).isFalse();
    }

    @Test
    void zaranthraxGainsLetalidadUntilItsNextAttack() {
        CardSlot zar = card("zar", "Zaranthrax", 4, 4, "P");
        zar.setStrength(12);
        host.getHand().add(zar);
        host.getHand().remove(zar);
        onField(host, zar);
        Summons.specialSummoned(room, "host", zar, null, "ritual-test", null);
        answerAll();
        assertThat(EffectEngine.keywordLevel(zar, "Letalidad")).isEqualTo(1);
        assertThat(zar.getUntilNextAttack()).contains("Letalidad 1");
    }

    @Test
    void janneShowsWhyThePlacedCreatureIsThere() {
        CardSlot attacker = onField(host, card("a", "Atacante", 3, 3, "G"));
        attacker.getResources().add(card("janne", "Janne", 1, 1, "G"));
        CardSlot starter = card("st", "Iniciador", 1, 1, "G");
        starter.setStarter(true);
        host.getHand().add(starter);
        onField(guest, card("e", "Enemigo", 1, 1, "R"));
        CombatRules.attackByPlayer(room, "host", "a", null);
        drive("st");
        assertThat(host.getField()).contains(starter);
        assertThat(starter.getFloating()).anyMatch(f -> f.text().contains("vuelve a tu mano") && f.text().contains("Janne"));
    }

    // ------------------------------------------------------------------ Helpers

    private Prompt promptRemoval() {
        Removals.promptRemoval(room);
        return room.getPrompt();
    }

    private static CardSlot materialization(CardSlot card, int cost, String requirement) {
        card.setSpecialSummonKind("materialization");
        card.setSpecialCost(cost);
        card.setRequirement(requirement);
        return card;
    }

    /** Answers every choice: the ids given when they can be chosen, else the first valid ones it needs (a CONFIRM: yes). */
    private void drive(String... prefer) {
        for (int i = 0; i < 12 && room.getPrompt() != null; i++) {
            Prompt p = room.getPrompt();
            List<String> picks = new ArrayList<>(List.of(prefer).stream().filter(p.getValidIds()::contains).toList());
            if (picks.isEmpty() && p.getEffects() != null && !p.getEffects().isEmpty()) picks.add(p.getEffects().get(0).effectId());
            if (picks.isEmpty()) picks.addAll(p.getValidIds().subList(0, Math.min(p.getValidIds().size(), Math.max(1, p.getMin()))));
            String error = Answers.answer(room, p.getPlayerId(), p.getId(), picks, List.of());
            if (error != null) throw new AssertionError(p.getTitle() + ": " + error);
        }
    }

    private void answerAll() {
        for (int i = 0; i < 8 && room.getPrompt() != null; i++) {
            Prompt p = room.getPrompt();
            Answers.answer(room, p.getPlayerId(), p.getId(), List.of(), List.of());
        }
    }

    private void answer(Prompt prompt, String... ids) {
        assertThat(prompt).isNotNull();
        List<String> picks = ids.length == 1 && ids[0] == null ? List.of() : List.of(ids);
        assertThat(Answers.answer(room, prompt.getPlayerId(), prompt.getId(), picks, List.of())).isNull();
    }
}
