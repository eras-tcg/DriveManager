package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cards in testing T12, archetype Hydras (2026-11-19): one test (or more) per programmed effect, from the printed text.
 */
@Tag("cards")
@Tag("t12")
class T12HydrasCardsTest extends CardBatchSupport {

    private static CardSlot hydra(String id, String name, Integer cost, int level, int strength) {
        CardSlot c = withClasses(card(id, name, cost, level, "G"), "hydra", "dragón");
        c.setStrength(strength);
        return c;
    }

    private static CardSlot evolution(CardSlot c, int specialCost) {
        c.setSpecialSummonKind("evolution");
        c.setSpecialCost(specialCost);
        return c;
    }

    private static CardSlot ramphirior(String id) { return evolution(hydra(id, "Ramphirior", 2, 2, 5), 1); }
    private static CardSlot leviadra(String id) { return evolution(hydra(id, "Leviadra", 2, 2, 4), 1); }
    private static CardSlot talassidra(String id) { return evolution(hydra(id, "Talassidra", 3, 3, 7), 2); }
    private static CardSlot chalybris(String id) { return evolution(hydra(id, "Chalybris, las Escamas Fundidas", 7, 5, 13), 4); }

    private static CardSlot enemy(String id, int strength) {
        CardSlot c = card(id, "Enemigo " + id, 1, 1, "R");
        c.setStrength(strength);
        return c;
    }

    private void evolve(CardSlot card, CardSlot summoner) {
        if (!host.getHand().contains(card)) host.getHand().add(card);
        assertThat(SpecialSummons.specialByPlayer(room, "host", card.getInstanceId(), summoner.getInstanceId())).isNull();
        stayBack();
    }

    private void stayBack() {
        Prompt p = room.getPrompt();
        if (p != null && PromptPurpose.SPECIAL_FRONT.is(p.getPurpose())) answer(p);
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

    private void yes() {
        answer(room.getPrompt(), room.getPrompt().getValidIds().get(0));
    }

    // ------------------------------------------------------------------ G01 Ramphirior

    @Test
    void ramphiriorTributedAsAResourceIsSummonedAndTakesALevelOneHydraForAGem() {
        CardSlot holder = onField(host, hydra("ho", "Portador", 3, 3, 7), BACK);
        CardSlot ramph = ramphirior("rp");
        holder.getResources().add(ramph);
        CardSlot small = onField(host, hydra("sm", "Hydra Uno", 1, 1, 2), BACK);
        // A Recurso tributed (an effect): it goes to the Tributos from the Recursos
        holder.getResources().remove(ramph);
        host.getTributeZone().add(ramph);
        EffectEngine.record(room, GameEvent.TRIBUTE, "host", ramph, "resources", "tributeZone", null,
                new HashMap<>(Map.of("ownerId", "host")), "tributa");
        Chain.advance(room);
        untilOffered(ramph);
        accept(ramph);
        yes();
        answer("sm");
        settle();
        assertThat(host.getField()).contains(ramph);
        assertThat(ramph.getResources()).containsExactly(small);
        // Ramphirior 2 - 1, + 1
        assertThat(gems()).isEqualTo(8 - 1 + 1);
    }

    @Test
    void ramphiriorAsAResourceDealsFourUnlessTheOpponentExhaustsAnAlly() {
        CardSlot holder = onField(host, hydra("ho", "Portador", 3, 3, 7));
        holder.getResources().add(ramphirior("rp"));
        CardSlot guard = onField(guest, enemy("en", 2));
        guard.setTapped(true);
        assertThat(CombatRules.attackByPlayer(room, "host", "ho", null)).isNull();
        settle();
        // No active ally to exhaust: 4 damage by the effect (one life of strength 3 and 1 more), then the attack's 7
        assertThat(guest.getLifeStack().size()).isLessThan(3);
    }

    @Test
    void ramphiriorAsAResourceIsStoppedWhenTheOpponentExhaustsAnAlly() {
        CardSlot holder = onField(host, hydra("ho", "Portador", 3, 3, 7));
        holder.getResources().add(ramphirior("rp"));
        CardSlot guard = onField(guest, enemy("en", 2));
        assertThat(CombatRules.attackByPlayer(room, "host", "ho", null)).isNull();
        Prompt p = room.getPrompt();
        assertThat(p.getPlayerId()).isEqualTo("guest");
        answer(p, "en");
        assertThat(guard.isTapped()).isTrue();
        assertThat(room.getTimeline().stream().noneMatch(e -> GameEvent.LIFE_DAMAGE.equals(e.getType()) && e.getData() != null
                && e.getData().get("attackerId") == null)).isTrue();
    }

    // ------------------------------------------------------------------ G02 Leviadra

    @Test
    void leviadraMillsThreeAndSendsFiveHydrasToSummonSpecially() {
        for (int i = 0; i < 5; i++) host.getDiscardPile().add(hydra("h" + i, "Hydra " + i, 1, 1, 1));
        CardSlot tala = talassidra("ta");
        host.getHand().add(tala);
        CardSlot summoner = onField(host, hydra("s1", "Uno", 1, 1, 2), BACK);
        CardSlot levi = leviadra("lv");
        evolve(levi, summoner);
        assertThat(host.getTributeZone()).hasSize(3);
        yes();
        answer("h0", "h1", "h2", "h3", "h4");
        answer("ta");
        stayBack();
        settle();
        assertThat(host.getField()).contains(tala);
        assertThat(tala.getResources()).contains(levi);
        // Leviadra 1, Talassidra 2 - 1
        assertThat(gems()).isEqualTo(8 - 1 - 1);
    }

    @Test
    void leviadraAsAResourceDestroysUpToTwoWeakEnemies() {
        CardSlot holder = onField(host, hydra("ho", "Portador", 3, 3, 7));
        holder.getResources().add(leviadra("lv"));
        CardSlot e1 = onField(guest, enemy("e1", 3));
        CardSlot e2 = onField(guest, enemy("e2", 2));
        assertThat(CombatRules.attackByPlayer(room, "host", "ho", null)).isNull();
        untilOffered(holder);
        accept(holder);
        answer("e1", "e2");
        settle();
        assertThat(guest.getDiscardPile()).contains(e1, e2);
    }

    // ------------------------------------------------------------------ G03 Talassidra

    @Test
    void talassidraDestroysTheExhaustedWeakEnemies() {
        CardSlot e1 = onField(guest, enemy("e1", 5));
        e1.setTapped(true);
        CardSlot e2 = onField(guest, enemy("e2", 2));
        CardSlot summoner = onField(host, hydra("s2", "Dos", 2, 2, 4));
        evolve(talassidra("ta"), summoner);
        settle();
        assertThat(guest.getDiscardPile()).containsExactly(e1);
        assertThat(guest.getField()).contains(e2);
        assertThat(host.getTributeZone()).isEmpty();
    }

    @Test
    void talassidraMillsAndTakesAHydraAsAResourceWhenNothingWasDestroyed() {
        host.getDeck().add(2, hydra("hx", "Hydra Mazo", 1, 1, 1));
        CardSlot summoner = onField(host, hydra("s2", "Dos", 2, 2, 4));
        CardSlot tala = talassidra("ta");
        evolve(tala, summoner);
        assertThat(host.getTributeZone()).hasSize(3);
        answer("hx");
        settle();
        assertThat(tala.getResources()).extracting(CardSlot::getInstanceId).contains("hx");
    }

    @Test
    void talassidraAsAResourceReactivatesAHydraExhaustedOnTheOpponentsTurn() {
        CardSlot holder = onField(host, hydra("ho", "Portador", 3, 3, 7));
        holder.getResources().addAll(List.of(talassidra("ta"), card("r1", "R1", 1, 1, "G"), card("r2", "R2", 1, 1, "G")));
        room.setCurrentTurnUserId("guest");
        holder.setTapped(true);
        EffectEngine.record(room, GameEvent.TAP, "guest", holder, null, null, null, null, "agota");
        Chain.advance(room);
        untilOffered(holder);
        accept(holder);
        answer("ta", "r1", "r2");
        settle();
        assertThat(holder.isTapped()).isFalse();
        assertThat(holder.getResources()).isEmpty();
    }

    // ------------------------------------------------------------------ G04 Chalybris

    @Test
    void chalybrisTakesAHydraOrDragonAsAResource() {
        CardSlot dragon = withClasses(card("dr", "Dragón", 1, 1, "G"), "dragón");
        host.getTributeZone().add(dragon);
        CardSlot summoner = onField(host, hydra("s4", "Cuatro", 4, 4, 10), BACK);
        CardSlot ch = chalybris("ch");
        evolve(ch, summoner);
        answer("dr");
        settle();
        assertThat(ch.getResources()).contains(dragon);
    }

    @Test
    void chalybrisEvolvesIntoAHydraOfTheTributosOnAttackAndDestroysTheWeakestActiveEnemy() {
        CardSlot ch = onField(host, chalybris("ch"));
        CardSlot next = evolution(hydra("nx", "Hydra Seis", 9, 6, 16), 5);
        host.getTributeZone().add(next);
        CardEffects.registerRequirement("hydra seis", Req.summoner(5));
        try {
            CardSlot weak = onField(guest, enemy("e1", 2));
            CardSlot strong = onField(guest, enemy("e2", 4));
            assertThat(CombatRules.attackByPlayer(room, "host", "ch", null)).isNull();
            untilOffered(ch);
            accept(ch);
            answer("nx");
            answer(room.getPrompt(), room.getPrompt().getValidIds().contains("ch") ? new String[]{"ch"} : new String[0]);
            settle();
            assertThat(host.getField()).contains(next);
            assertThat(guest.getDiscardPile()).contains(weak);
            assertThat(guest.getField()).contains(strong);
            assertThat(gems()).isEqualTo(8 - 2);
        } finally {
            CardEffects.registerRequirement("hydra seis", null);
        }
    }

    @Test
    void chalybrisAsAResourceTributesItselfAndAHydraToDestroyTheTopLife() {
        CardSlot holder = onField(host, hydra("ho", "Portador", 7, 5, 13));
        holder.getResources().addAll(List.of(chalybris("ch"), hydra("hr", "Hydra Recurso", 1, 1, 1)));
        assertThat(CombatRules.attackByPlayer(room, "host", "ho", null)).isNull();
        untilOffered(holder);
        accept(holder);
        answer("hr");
        settle();
        assertThat(holder.getResources()).isEmpty();
        // The attack's damage took the first life; at the end of the attack the next top one is destroyed
        assertThat(guest.getDiscardPile()).extracting(CardSlot::getInstanceId).contains("gl1");
    }

    // ------------------------------------------------------------------ G05 Tenacidad de Hydra

    @Test
    void tenacidadLetsAnAllySummonAHydraOfAnotherNameForOneLessAndOneMoreAtTheSameLevel() {
        CardSlot summoner = onField(host, hydra("s2", "Dos", 2, 2, 4), BACK);
        CardSlot tala = talassidra("ta");
        CardSlot levi = leviadra("lv");
        host.getTributeZone().addAll(List.of(tala, levi));
        CardSlot tenacidad = utility("tn", "Tenacidad de Hydra", 1, "G");
        tenacidad.setLevel(3);
        play(tenacidad);
        answer("ta");
        answer("s2");
        stayBack();
        settle();
        assertThat(host.getField()).contains(tala);
        // Tenacidad 1, Talassidra 2 - 1 (level 3 vs 2: no more)
        assertThat(gems()).isEqualTo(8 - 1 - 1);
    }

    @Test
    void tenacidadReducesOneMoreWhenTheSummonerHasTheSameLevel() {
        CardSlot summoner = onField(host, hydra("s2", "Dos", 2, 2, 4), BACK);
        CardSlot levi = leviadra("lv");
        levi.setSpecialCost(2);
        host.getTributeZone().add(levi);
        CardSlot tenacidad = utility("tn", "Tenacidad de Hydra", 1, "G");
        play(tenacidad);
        answer("lv");
        answer("s2");
        stayBack();
        settle();
        assertThat(host.getField()).contains(levi);
        // Tenacidad 1, Leviadra 2 - 1 - 1
        assertThat(gems()).isEqualTo(8 - 1);
    }
}
