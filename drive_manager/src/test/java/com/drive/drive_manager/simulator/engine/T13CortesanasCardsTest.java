package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cards in testing T13, archetype Cortesanas (2026-11-19): one test (or more) per programmed effect, from the printed text. Legendaria stays
 * manual, as in the whole simulator.
 */
@Tag("cards")
@Tag("t13")
class T13CortesanasCardsTest extends CardBatchSupport {

    private static CardSlot white(String id, String name, Integer cost, int level, int strength, String... classes) {
        CardSlot c = withClasses(card(id, name, cost, level, "W"), classes);
        c.setStrength(strength);
        return c;
    }

    private static CardSlot cortesana(String id, String name, int cost, int level, int strength) {
        return white(id, name, cost, level, strength, "cortesana");
    }

    private static CardSlot yukihana(String id) { return cortesana(id, "Yukihana", 1, 1, 1); }
    private static CardSlot setsuna(String id) { return cortesana(id, "Setsuna", 1, 1, 1); }
    private static CardSlot yurei(String id) { return cortesana(id, "Yūrei", 1, 1, 1); }

    private static CardSlot fukuya(String id) {
        CardSlot c = cortesana(id, "Fukuya La Cortesana Carmesí", 3, 2, 2);
        c.setSpecialSummonKind("promotion");
        c.setSpecialCost(2);
        return c;
    }

    private static CardSlot tsukiyo(String id) {
        return white(id, "Tsukiyo La Domadora De Bestias", 4, 4, 11, "cortesana", "bestia");
    }

    private static CardSlot enemy(String id, int strength, int level) {
        CardSlot c = card(id, "Enemigo " + id, 1, level, "R");
        c.setStrength(strength);
        return c;
    }

    private void activate(CardSlot card, String idStart) {
        String script = Chain.activatable(room, "host").stream()
                .filter(a -> card.getInstanceId().equals(a.instanceId()) && a.scriptId().startsWith(idStart))
                .map(Chain.Activatable::scriptId).findFirst().orElse(null);
        assertThat(script).as("se puede activar " + idStart).isNotNull();
        assertThat(Chain.activateByPlayer(room, "host", card.getInstanceId(), script)).isNull();
    }

    private void settle() {
        for (int i = 0; i < 40; i++) {
            Prompt p = room.getPrompt();
            if (p == null) {
                if (room.getCombat() == null) break;
                Chain.advance(room);
                continue;
            }
            if (PromptPurpose.ORDER.is(p.getPurpose())) answer(p, p.getValidIds().get(0));
            else Answers.answer(room, p.getPlayerId(), p.getId(), List.of(), List.of());
        }
    }

    private void stayBack() {
        Prompt p = room.getPrompt();
        if (p != null && PromptPurpose.SPECIAL_FRONT.is(p.getPurpose())) answer(p);
    }

    // ------------------------------------------------------------------ W01 Yukihana

    @Test
    void yukihanaIsSummonedForFreeWhenYouSummonACortesana() {
        CardSlot yuki = yukihana("yk");
        host.getHand().add(yuki);
        summon(setsuna("st"));
        untilOffered(yuki);
        accept(yuki);
        settle();
        assertThat(host.getField()).contains(yuki);
        assertThat(gems()).isEqualTo(8 - 1);
    }

    @Test
    void yukihanaTributesItselfForAGemWhenAnAllyIsSummonedOnTheOpponentsTurn() {
        CardSlot yuki = onField(host, yukihana("yk"), BACK);
        room.setCurrentTurnUserId("guest");
        CardSlot ally = card("al", "Aliado", 1, 1, "R");
        host.getField().add(ally);
        ally.setFaceDown(false);
        EffectEngine.record(room, GameEvent.EFFECT_SUMMON, "host", ally, "hand", "field", null, null, "invoca");
        Chain.advance(room);
        untilOffered(yuki);
        accept(yuki);
        settle();
        assertThat(host.getTributeZone()).contains(yuki);
        assertThat(gems()).isEqualTo(8 + 1);
    }

    // ------------------------------------------------------------------ W02 Fukuya

    @Test
    void fukuyaAscendsWithTwoCortesanasAndReturnsOneToBounceAStrongerEnemy() {
        CardSlot summoner = onField(host, cortesana("c1", "Cortesana Uno", 1, 1, 1));
        CardSlot other = onField(host, cortesana("c2", "Cortesana Dos", 1, 1, 3), BACK);
        CardSlot strong = onField(guest, enemy("e1", 8, 2));
        CardSlot tooStrong = onField(guest, enemy("e2", 9, 2));
        CardSlot fukuya = fukuya("fk");
        host.getHand().add(fukuya);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "fk", "c1")).isNull();
        stayBack();
        answer("c2");
        assertThat(room.getPrompt().getValidIds()).containsExactly("e1");
        answer("e1");
        settle();
        assertThat(host.getHand()).contains(other);
        assertThat(guest.getHand()).contains(strong);
        assertThat(guest.getField()).contains(tooStrong);
    }

    @Test
    void fukuyaNeedsTwoCortesanas() {
        onField(host, cortesana("c1", "Cortesana Uno", 1, 1, 1));
        host.getHand().add(fukuya("fk"));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "fk", "c1")).isNotNull();
    }

    @Test
    void fukuyaTributesTheAttackingCortesanaAndItselfToTributeALife() {
        CardSlot fukuya = onField(host, fukuya("fk"), BACK);
        CardSlot attacker = onField(host, cortesana("c2", "Cortesana Dos", 1, 1, 3));
        assertThat(CombatRules.attackByPlayer(room, "host", "c2", null)).isNull();
        untilOffered(fukuya);
        accept(fukuya);
        settle();
        assertThat(host.getTributeZone()).contains(fukuya, attacker);
        assertThat(guest.getTributeZone()).extracting(CardSlot::getInstanceId).contains("gl0");
        assertThat(guest.getLifeStack()).hasSize(2);
    }

    // ------------------------------------------------------------------ W03 Setsuna

    @Test
    void setsunaTributesUpToTwoWhiteCardsAndTakesOne() {
        host.getDeck().addAll(0, List.of(card("w1", "Blanca", 1, 1, "W"), card("r1", "Roja", 1, 1, "R"), card("w2", "Blanca Dos", 1, 1, "W")));
        summon(setsuna("st"));
        assertThat(room.getPrompt().getValidIds()).containsExactlyInAnyOrder("w1", "w2");
        answer("w1", "w2");
        answer("w2");
        settle();
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("w2");
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).containsExactly("w1");
    }

    // ------------------------------------------------------------------ W04 Yūrei

    @Test
    void yureiComesToTheFrontLineWhenAnAllyIsDestroyedAndDestroysALowerEnemy() {
        CardSlot yurei = yurei("yu");
        host.getHand().add(yurei);
        onField(host, cortesana("c1", "Cortesana Uno", 1, 1, 1), BACK);
        onField(host, cortesana("c2", "Cortesana Dos", 1, 1, 1), BACK);
        CardSlot victim = onField(host, card("vi", "Víctima", 1, 1, "R"), BACK);
        CardSlot low = onField(guest, enemy("e1", 5, 2));
        CardSlot high = onField(guest, enemy("e2", 5, 3));
        Removals.destroy(room, victim, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        untilOffered(yurei);
        accept(yurei);
        // Three [cortesanas] (Yūrei too): level below 3
        assertThat(room.getPrompt().getValidIds()).containsExactly("e1");
        answer("e1");
        settle();
        assertThat(guest.getDiscardPile()).contains(low);
        assertThat(guest.getField()).contains(high);
        assertThat(EffectEngine.inFrontLine(yurei)).isTrue();
    }

    @Test
    void yureiNormallySummonedDoesNothing() {
        onField(guest, enemy("e1", 5, 1));
        summon(yurei("yu"));
        assertThat(room.getPrompt()).isNull();
    }

    // ------------------------------------------------------------------ W05 Danza Lunar

    @Test
    void danzaLunarSummonsACortesanaFromTheTributosAndStaysExhausted() {
        CardSlot c = cortesana("c3", "Cortesana Tres", 2, 2, 4);
        host.getTributeZone().add(c);
        CardSlot danza = utility("dl", "Danza Lunar", 1, "W");
        danza.setLevel(4);
        play(danza);
        answer("c3");
        settle();
        assertThat(host.getField()).contains(c, danza);
        assertThat(danza.isTapped()).isTrue();
        assertThat(gems()).isEqualTo(8 - 1 - 2);
    }

    @Test
    void danzaLunarDiscardsItselfAndTributesCortesanasToSummonAShogunForLess() {
        CardSlot danza = onField(host, utility("dl", "Danza Lunar", 1, "W"), BACK);
        CardSlot c1 = onField(host, cortesana("c1", "Cortesana Uno", 1, 1, 1), BACK);
        CardSlot c2 = onField(host, cortesana("c2", "Cortesana Dos", 1, 1, 1), BACK);
        CardSlot summoner = onField(host, white("s2", "Dos", 2, 2, 4), BACK);
        CardSlot shogun = white("sh", "Shogún de Prueba", 4, 3, 9, "shogún");
        shogun.setSpecialSummonKind("evolution");
        shogun.setSpecialCost(3);
        host.getTributeZone().add(shogun);
        CardEffects.registerRequirement("shogún de prueba", Req.summoner(2));
        try {
            activate(danza, "danza-lunar:turno-aliado");
            answer("c1", "c2");
            answer("sh");
            answer("s2");
            stayBack();
            settle();
            assertThat(host.getDiscardPile()).contains(danza);
            assertThat(host.getTributeZone()).contains(c1, c2);
            assertThat(host.getField()).contains(shogun);
            assertThat(gems()).isEqualTo(8 - 1);
        } finally {
            CardEffects.registerRequirement("shogún de prueba", null);
        }
    }

    // ------------------------------------------------------------------ W06 Bonsai Centenario

    @Test
    void bonsaiExhaustsAndTributesToSwapAnAllyForACortesanaOfAnotherName() {
        CardSlot bonsai = onField(host, withClasses(structure("bc", "Bonsai Centenario", 1, "W"), "shogún"), BACK);
        CardSlot ally = onField(host, cortesana("c1", "Cortesana Uno", 1, 1, 1), BACK);
        CardSlot copy = cortesana("c1b", "Cortesana Uno", 1, 1, 1);
        CardSlot other = cortesana("c2", "Cortesana Dos", 2, 2, 3);
        host.getTributeZone().addAll(List.of(copy, other));
        host.getHand().add(card("h1", "Mano", 1, 1, "R"));
        activate(bonsai, "bonsai-centenario:turno-aliado");
        answer("h1");
        answer("c1");
        assertThat(room.getPrompt().getValidIds()).containsExactly("c2");
        answer("c2");
        settle();
        assertThat(bonsai.isTapped()).isTrue();
        assertThat(host.getTributeZone()).contains(ally);
        assertThat(host.getField()).contains(other);
        assertThat(gems()).isEqualTo(8 - 1);
    }

    @Test
    void bonsaiOnPlayLetsAShogunBeSummonedSpecially() {
        CardSlot summoner = onField(host, white("s2", "Dos", 2, 2, 4), BACK);
        CardSlot shogun = white("sh", "Shogún de Prueba", 4, 3, 9, "shogún");
        shogun.setSpecialSummonKind("evolution");
        shogun.setSpecialCost(3);
        host.getHand().add(shogun);
        CardEffects.registerRequirement("shogún de prueba", Req.summoner(2));
        try {
            CardSlot bonsai = withClasses(structure("bc", "Bonsai Centenario", 1, "W"), "shogún");
            play(bonsai);
            accept(bonsai);
            answer("sh");
            answer("s2");
            stayBack();
            settle();
            assertThat(host.getField()).contains(shogun);
            assertThat(gems()).isEqualTo(8 - 1 - 2);
        } finally {
            CardEffects.registerRequirement("shogún de prueba", null);
        }
    }

    // ------------------------------------------------------------------ W07 Tsukiyo

    @Test
    void tsukiyoTributesALevelThreeEnemy() {
        CardSlot target = onField(guest, enemy("e1", 6, 3));
        summonByEffectToFront(tsukiyo("ts"));
        CardSlot tsukiyo = host.getField().stream().filter(c -> c.getInstanceId().equals("ts")).findFirst().orElseThrow();
        untilOffered(tsukiyo);
        accept(tsukiyo);
        answer("e1");
        settle();
        assertThat(guest.getTributeZone()).contains(target);
    }

    @Test
    void tsukiyoTakesAChosenBeastAllyAsAResourceForTwoGems() {
        CardSlot beast = onField(host, white("bs", "Bestia", 3, 3, 6, "bestia"));
        summonByEffectToFront(tsukiyo("ts"));
        CardSlot tsukiyo = host.getField().stream().filter(c -> c.getInstanceId().equals("ts")).findFirst().orElseThrow();
        untilOffered(tsukiyo);
        accept(tsukiyo);
        answer("bs");
        settle();
        assertThat(tsukiyo.getResources()).containsExactly(beast);
        assertThat(host.getTributeZone()).doesNotContain(beast);
        assertThat(gems()).isEqualTo(8 + 2);
    }
}
