package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** E2 (Azul, Verde y Violeta): one test (or more) per card programmed on 2026-10-31, from the printed text. */
class E2CardsTest extends CardBatchSupport {

    private void destroyByOpponent(CardSlot card) {
        Removals.destroy(room, card, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
    }

    /** An effect of one of the host's cards (for "por tus efectos"). */
    private PendingEffect hostEffect() {
        CardSlot src = onField(host, card("src", "Fuente", 1, 1, "R"), BACK);
        return Chain.pending(room, CardEffects.of("Dragón Goloso").get(0), "host", src, room.getEventSeq());
    }

    private static CardSlot special(CardSlot card, String kind, int cost, String requirement) {
        card.setSpecialSummonKind(kind);
        card.setSpecialCost(cost);
        card.setRequirement(requirement);
        return card;
    }

    // ------------------------------------------------------------------ Azul

    @Test
    void sicarioJoinsASpecialSummonedNeomaquinaAsMaterialAndItAttacks() {
        CardSlot sicario = onField(host, withClasses(card("si", "Sicario R3-D", 1, 1, "B"), "neomáquina"), BACK);
        CardSlot neo = onField(host, withClasses(card("nm", "Neo", 2, 2, "B"), "neomáquina"));
        EffectEngine.record(room, GameEvent.SPECIAL_SUMMON, "host", neo, "hand", "field", null, Map.of("summonType", "materialization"), "x");
        Chain.advance(room);
        accept(sicario);
        drain();
        assertThat(neo.getMaterials()).contains(sicario);
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.ATTACK_DECLARED.equals(e.getType()) && "nm".equals(e.getCardInstanceId()));
    }

    @Test
    void flerikTakesOneOfEachKind() {
        host.getDeck().add(0, withClasses(card("ci", "Cineriano", 1, 1, "B"), "cineriano"));
        host.getDeck().add(0, structure("es", "Estructura Azul", 1, "B"));
        host.getDeck().add(0, withClasses(card("c2", "Cineriano 2", 1, 1, "B"), "cineriano"));
        summon(withClasses(card("fl", "Flerik el Ingeniero Mercader", 1, 1, "B"), "cineriano"));
        Prompt take = room.getPrompt();
        assertThat(Answers.answer(room, "host", take.getId(), List.of("ci", "c2"), List.of())).isNotNull();
        answer(take, "ci", "es");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).containsExactlyInAnyOrder("ci", "es");
    }

    @Test
    void falcoTributesAMaterialToPlayABlueStructureFromTheHand() {
        CardSlot estr = structure("es", "Estructura Azul", 1, "B");
        host.getHand().add(estr);
        CardSlot falco = withClasses(card("fa", "Falco el Buscador de Tesoros", 1, 1, "B"), "cineriano");
        falco.getMaterials().add(card("m", "Material", 1, 1, "B"));
        onField(host, card("ot", "Otro", 1, 1, "B"));
        summon(falco);
        answer("fa");
        answer("m");
        answer("es");
        assertThat(host.getField()).contains(estr);
    }

    @Test
    void v10l3tGainsEnfrentarForTheAttack() {
        CardSlot v10 = onField(host, withClasses(card("v1", "Unidad Heróica V10-L3T", 2, 2, "B"), "neomáquina"));
        v10.getMaterials().addAll(List.of(card("m1", "M1", 1, 1, "B"), card("m2", "M2", 1, 1, "B")));
        assertThat(CombatRules.attackByPlayer(room, "host", "v1", null)).isNull();
        untilOffered(v10);
        answer("v1");
        answer("m1", "m2");
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.GAIN.equals(e.getType()) && "Enfrentar 3".equals(e.getData().get("keyword")));
    }

    @Test
    void arthurCollierReducesTheBlockerBeforeTheClash() {
        room.setSharedCounter(-3);
        CardSlot arthur = onField(host, withClasses(card("ac", "Arthur Collier", 2, 2, "B"), "cineriano"));
        arthur.setStrength(5);
        arthur.getMaterials().addAll(List.of(card("m1", "M1", 1, 1, "B"), card("m2", "M2", 1, 1, "B")));
        CardSlot blocker = onField(guest, card("bl", "Bloqueador", 3, 3, "R"));
        blocker.setStrength(9);
        assertThat(CombatRules.attackByPlayer(room, "host", "ac", null)).isNull();
        for (int i = 0; i < 10 && room.getPrompt() != null && !room.getPrompt().getValidIds().contains("ac"); i++) {
            Prompt p = room.getPrompt();
            answer(p, p.getValidIds().contains("bl") ? new String[]{"bl"} : new String[0]);
        }
        answer("ac");
        answer("m1", "m2");
        drain();
        // 9 - 2 × 3 gems = 3: Arthur (5) wins the clash
        assertThat(guest.getField()).doesNotContain(blocker);
        assertThat(host.getField()).contains(arthur);
    }

    @Test
    void faustoTributesTwoMaterialsToPlayABlueUtilityFromTheTributes() {
        CardSlot fausto = onField(host, withClasses(card("fs", "Fausto", 2, 2, "B"), "cineriano"));
        fausto.getMaterials().addAll(List.of(card("m1", "M1", 1, 1, "B"), card("m2", "M2", 1, 1, "B")));
        CardSlot util = utility("ut", "Utilidad Azul", 1, "B");
        host.getTributeZone().add(util);
        assertThat(Chain.activateByPlayer(room, "host", "fs", "fausto:turno-aliado")).isNull();
        answer("m1", "m2");
        answer("ut");
        assertThat(host.getTributeZone()).doesNotContain(util);
    }

    @Test
    void f1r3TributedAsMaterialInCombatReducesAnEnemyUntilTheEndOfTheCombat() {
        CardSlot f1 = withClasses(card("f1", "Unidad de Combate Frontal F1-R3", 2, 2, "B"), "neomáquina");
        CardSlot holder = onField(host, card("ho", "Portador", 2, 2, "B"));
        holder.getMaterials().add(f1);
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        assertThat(CombatRules.attackByPlayer(room, "host", "ho", null)).isNull();
        // While the defender decides the block, the material is tributed
        assertThat(room.getCombat()).isNotNull();
        tributeMaterial(holder, f1);
        Prompt block = room.getPrompt();
        answer(block);
        untilOffered(f1);
        answer("f1");
        answer("e");
        assertThat(room.getTimeline()).anyMatch(ev -> GameEvent.STRENGTH_REDUCED.equals(ev.getType()) && "e".equals(ev.getCardInstanceId()));
    }

    private void tributeMaterial(CardSlot holder, CardSlot material) {
        holder.getMaterials().remove(material);
        host.getTributeZone().add(material);
        EffectEngine.record(room, GameEvent.TRIBUTE, "host", material, "materials", "tributeZone", null, Map.of("byCard", holder.getInstanceId()), "tributa");
    }

    @Test
    void b1u3IsMaterializedForFreeByTheAllyThatDestroyedInAClash() {
        CardSlot b1 = inHand("b1", "Unidad Policial B1-U3", 3, 3, "B", "neomáquina");
        special(b1, "materialization", 3, "criatura nivel 3 en mano + material en mano.");
        CardSlot attacker = onField(host, card("at", "Atacante", 2, 2, "B"));
        attacker.setStrength(8);
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        enemy.setTapped(true);
        int before = gems();
        assertThat(CombatRules.attackByPlayer(room, "host", "at", "e")).isNull();
        untilOffered(b1);
        answer("b1");
        drain();
        assertThat(host.getField()).contains(b1);
        assertThat(b1.getMaterials()).contains(attacker);
        assertThat(gems()).isEqualTo(before);
    }

    @Test
    void a5hTributedAsMaterialTakesABlueCard() {
        CardSlot a5h = withClasses(card("a5", "Unidad de Seguridad A-5H", 3, 3, "B"), "neomáquina");
        CardSlot holder = onField(host, withClasses(card("ho", "Portador", 2, 2, "B"), "neomáquina"));
        holder.getMaterials().add(a5h);
        host.getTributeZone().add(card("bl", "Azul", 1, 1, "B"));
        tributeMaterial(holder, a5h);
        Chain.advance(room);
        accept(a5h);
        answer("bl");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("bl");
    }

    @Test
    void helenaReducesTheChosenByTheGems() {
        room.setSharedCounter(-3);
        CardSlot e1 = onField(guest, card("e1", "Enemigo", 1, 1, "R"));
        e1.setStrength(5);
        CardSlot helena = withClasses(card("hl", "Helena la Pirata de Cineria", 3, 3, "B"), "cineriano");
        helena.getMaterials().add(card("m", "M", 1, 1, "B"));
        summonByEffectToFront(helena);
        answer("m");
        answer("e1");
        assertThat(EffectEngine.strength(e1)).isEqualTo(5 - gems());
    }

    @Test
    void blKillerGainsLetalidadForTheAttack() {
        CardSlot killer = onField(host, withClasses(card("bk", "Unidad BL-Killer", 3, 3, "B"), "neomáquina"));
        killer.getMaterials().addAll(List.of(card("m1", "M1", 1, 1, "B"), card("m2", "M2", 1, 1, "B")));
        assertThat(CombatRules.attackByPlayer(room, "host", "bk", null)).isNull();
        untilOffered(killer);
        answer("bk");
        answer("m1", "m2");
        drain();
        assertThat(guest.getLifeStack()).hasSize(1);
    }

    @Test
    void xtrmReducesTwoWeakEnemiesToZero() {
        CardSlot xtrm = onField(host, withClasses(card("xt", "Prototipo XTRM-1N3", 4, 4, "B"), "neomáquina"));
        xtrm.getMaterials().addAll(List.of(card("m1", "M1", 1, 1, "B"), card("m2", "M2", 1, 1, "B")));
        CardSlot e1 = onField(guest, card("e1", "E1", 1, 1, "R"));
        CardSlot e2 = onField(guest, card("e2", "E2", 1, 1, "R"));
        assertThat(CombatRules.attackByPlayer(room, "host", "xt", null)).isNull();
        untilOffered(xtrm);
        answer("xt");
        answer("m1", "m2");
        answer("e1", "e2");
        assertThat(guest.getField()).doesNotContain(e1, e2);
    }

    @Test
    void rotoG0E2WorksLikeTheE1Reprint() {
        CardSlot roto = withClasses(card("rg", "Roto-G0", 4, 4, "B"), "neomáquina");
        roto.getMaterials().add(card("m1", "M1", 1, 1, "B"));
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        summonByEffectToFront(roto);
        accept(roto);
        answer("m1");
        answer("e");
        assertThat(EffectEngine.strength(enemy)).isEqualTo(1);
    }

    @Test
    void exoplanetariaChoosesOneEnemyPerTwoMaterials() {
        CardSlot exo = withClasses(card("ex", "Unidad Exoplanetaria de Rescate", 4, 4, "B"), "mecha");
        exo.getMaterials().addAll(List.of(card("m1", "M1", 1, 1, "B"), card("m2", "M2", 1, 1, "B")));
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        summonByEffectToFront(exo);
        accept(exo);
        answer("m1", "m2");
        answer("e");
        assertThat(EffectEngine.strength(enemy)).isEqualTo(1);
    }

    @Test
    void puertoDeCineriaTributesAnArtefactoForAGemWithNegativeGems() {
        room.setSharedCounter(1);
        CardSlot puerto = onField(host, structure("pc", "Puerto de Cineria", 1, "B"), BACK);
        onField(host, withClasses(utility("ar", "Artefacto", 0, "B"), "artefacto"), BACK);
        int before = gems();
        TurnRules.turnEnding(room, "host");
        untilOffered(puerto);
        answer("pc");
        answer("ar");
        assertThat(gems()).isEqualTo(before + 1);
    }

    @Test
    void preparandoExpedicionCostsOneLessWithoutArtefactos() {
        assertThat(CardEffects.playCost(room, "host", utility("pe", "Preparando Expedición", 1, "B"))).isEqualTo(0);
    }

    @Test
    void potenciaNuclearAttacksWithoutExhaustingAndTheAllyGoesBackToTheHand() {
        CardSlot neo = onField(host, withClasses(card("nm", "Neo", 2, 2, "B"), "neomáquina"));
        neo.getMaterials().addAll(List.of(card("m1", "M1", 1, 1, "B"), card("m2", "M2", 1, 1, "B"), card("m3", "M3", 1, 1, "B")));
        play(utility("pn", "Potencia Nuclear", 0, "B"));
        answer("pn");
        answer("nm");
        answer("m1", "m2");
        drain();
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("nm", "m3");
    }

    @Test
    void alterNeoReturnsANeomaquinaAndSummonsAnotherOfItsLevel() {
        CardSlot old = onField(host, withClasses(card("ol", "Neo Viejo", 2, 2, "B"), "neomáquina"));
        CardSlot summoner = onField(host, card("su", "Invocador", 2, 2, "B"));
        CardSlot other = inHand("nw", "Neo Nuevo", 2, 2, "B", "neomáquina");
        special(other, "evolution", 2, "criatura nivel 2.");
        play(utility("an", "Alter Neo", 1, "B"));
        answer("ol");
        answer("nw");
        answer("su");
        assertThat(host.getField()).contains(other);
        assertThat(host.getHand()).contains(old);
        assertThat(summoner).isNotNull();
    }

    @Test
    void cianotipoDrawsAsManyAsMaterialsTributedThenBecomesAMaterial() {
        CardSlot neo = onField(host, withClasses(card("nm", "Neo", 2, 2, "B"), "neomáquina"));
        neo.getMaterials().addAll(List.of(card("m1", "M1", 1, 1, "B"), card("m2", "M2", 1, 1, "B")));
        CardSlot ciano = utility("ci", "Cianotipo", 0, "B");
        play(ciano);
        answer("nm");
        answer("m1", "m2");
        answer("nm");
        assertThat(host.getHand()).hasSize(2);
        assertThat(neo.getMaterials()).contains(ciano);
    }


    private PendingEffect guestEffect() {
        CardSlot src = onField(guest, card("gsrc", "Fuente Rival", 1, 1, "R"));
        PendingEffect e = Chain.pending(room, CardEffects.of("Dragón Goloso").get(0), "guest", src, room.getEventSeq());
        room.getChain().add(e);
        return e;
    }

    @Test
    void albertCollierPaysOnceAndSavesEveryCinerianoTheEffectWasRemoving() {
        CardSlot albert = onField(host, withClasses(card("ab", "Albert Collier el Tecnomago", 2, 2, "B"), "cineriano"));
        albert.getMaterials().add(card("m", "Material", 1, 1, "B"));
        CardSlot art = onField(host, withClasses(utility("ar", "Artefacto", 0, "B"), "artefacto"), BACK);
        CardSlot c1 = onField(host, withClasses(card("c1", "Cineriano 1", 1, 1, "B"), "cineriano"));
        CardSlot c2 = onField(host, withClasses(card("c2", "Cineriano 2", 1, 1, "B"), "cineriano"));
        PendingEffect theirs = guestEffect();
        Removals.destroy(room, c1, host, false, EffectEngine.CAUSE_EFFECT, "guest", "gsrc", theirs);
        Removals.destroy(room, c2, host, false, EffectEngine.CAUSE_EFFECT, "guest", "gsrc", theirs);
        room.getChain().remove(theirs);
        Chain.advance(room);
        answer(room.getPrompt(), "ab");
        answer("m", "ar");
        drain();
        assertThat(host.getField()).contains(c1, c2, albert).doesNotContain(art);
        assertThat(albert.getMaterials()).isEmpty();
    }

    @Test
    void albertCollierCanSaveItself() {
        CardSlot albert = onField(host, withClasses(card("ab", "Albert Collier el Tecnomago", 2, 2, "B"), "cineriano"));
        albert.getMaterials().add(card("m", "Material", 1, 1, "B"));
        onField(host, withClasses(utility("ar", "Artefacto", 0, "B"), "artefacto"), BACK);
        PendingEffect theirs = guestEffect();
        Removals.destroy(room, albert, host, false, EffectEngine.CAUSE_EFFECT, "guest", "gsrc", theirs);
        room.getChain().remove(theirs);
        Chain.advance(room);
        answer(room.getPrompt(), "ab");
        answer("m", "ar");
        assertThat(host.getField()).contains(albert);
    }

    @Test
    void albertCollierDoesNotGuardAgainstYourOwnEffects() {
        CardSlot albert = onField(host, withClasses(card("ab", "Albert Collier el Tecnomago", 2, 2, "B"), "cineriano"));
        albert.getMaterials().add(card("m", "Material", 1, 1, "B"));
        onField(host, withClasses(utility("ar", "Artefacto", 0, "B"), "artefacto"), BACK);
        CardSlot c1 = onField(host, withClasses(card("c1", "Cineriano 1", 1, 1, "B"), "cineriano"));
        Removals.destroy(room, c1, host, false, EffectEngine.CAUSE_EFFECT, "host", "src", hostEffect());
        assertThat(host.getField()).doesNotContain(c1);
    }

    // ------------------------------------------------------------------ Verde

    @Test
    void deinonicusTributesThreeAndTakesADinosaur() {
        host.getDeck().add(0, withClasses(card("dn", "Dino", 1, 1, "G"), "dinosaurio"));
        summon(withClasses(card("de", "Deinonicus", 1, 1, "G"), "dinosaurio"));
        answer("dn");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("dn");
    }

    @Test
    void aDinosaurResourceGivesAGemWhenTheHolderIsDestroyedByAnAllyEffect() {
        CardSlot holder = onField(host, card("ho", "Portador", 2, 2, "G"));
        CardSlot dino = withClasses(card("de", "Deinonicus", 1, 1, "G"), "dinosaurio");
        holder.getResources().add(dino);
        int before = gems();
        Removals.destroy(room, holder, host, false, EffectEngine.CAUSE_EFFECT, "host", "src", hostEffect());
        Chain.advance(room);
        accept(holder);
        assertThat(host.getTributeZone()).contains(dino);
        assertThat(gems()).isEqualTo(before + 1);
    }

    @Test
    void arqueopterixTributedTakesADinosaur() {
        CardSlot arq = withClasses(card("aq", "Arqueopterix", 1, 1, "G"), "dinosaurio");
        host.getTributeZone().addAll(List.of(arq, withClasses(card("dn", "Dino", 1, 1, "G"), "dinosaurio")));
        inHand("x", "Pago", 1, 1, "R");
        EffectEngine.record(room, GameEvent.TRIBUTE, "host", arq, "deck", "tributeZone", null, null, "tributa");
        Chain.advance(room);
        accept(arq);
        answer("x");
        answer("dn");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("dn");
    }

    @Test
    void lyldraAddsAHydraOfTheTributesAsResource() {
        host.getTributeZone().add(withClasses(card("hy", "Hydra", 1, 1, "G"), "hydra"));
        CardSlot lyldra = withClasses(card("ly", "Lyldra", 1, 1, "G"), "hydra", "dragón");
        summon(lyldra);
        answer("hy");
        assertThat(lyldra.getResources()).extracting(CardSlot::getInstanceId).contains("hy");
    }

    @Test
    void dodoDestroyedBecomesAResourceOfAnAlly() {
        CardSlot dodo = onField(host, withClasses(card("do", "Dodo", 1, 1, "G"), "dinosaurio"));
        CardSlot ally = onField(host, card("al", "Aliado", 2, 2, "G"));
        destroyByOpponent(dodo);
        accept(dodo);
        answer("al");
        assertThat(ally.getResources()).contains(dodo);
    }

    @Test
    void sarkhydraxAddsAStrongEnoughHydraFromTheHand() {
        CardSlot hy = inHand("hy", "Hydra", 2, 2, "G", "hydra");
        CardSlot sark = withClasses(card("sk", "Sarkhydrax", 2, 2, "G"), "hydra", "dragón");
        summon(sark);
        answer("hy");
        assertThat(sark.getResources()).contains(hy);
    }

    @Test
    void petrodalixPlaysADinosaurStructureFromTheTributes() {
        CardSlot estr = withClasses(structure("es", "Estructura Dino", 2, "G"), "dinosaurio");
        host.getTributeZone().add(estr);
        CardSlot petro = withClasses(card("pd", "Petrodalix", 2, 2, "G"), "dinosaurio");
        summon(petro);
        answer("pd");
        answer("es");
        assertThat(host.getField()).contains(estr);
    }

    @Test
    void caudapterixSacrificesAWeakerActiveAllyForAGem() {
        CardSlot weak = onField(host, card("wk", "Débil", 1, 1, "G"));
        CardSlot cauda = withClasses(card("ca", "Caudapterix", 3, 2, "G"), "dinosaurio");
        cauda.setStrength(6);
        int before = gems();
        summon(cauda);
        accept(cauda);
        answer("wk");
        assertThat(host.getTributeZone()).contains(weak);
        assertThat(gems()).isEqualTo(before - 3 + 1);
    }

    @Test
    void deriduragonE2AddsAResource() {
        CardSlot weak = inHand("wk", "Débil", 1, 1, "G");
        CardSlot der = withClasses(card("dr", "Deriduragón", 2, 2, "G"), "dragón");
        summon(der);
        answer("wk");
        assertThat(der.getResources()).contains(weak);
    }

    @Test
    void yakoKitsuneSummonsItsResourceToReduceTheChosen() {
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        enemy.setStrength(5);
        CardSlot yako = withClasses(card("yk", "Yako Kitsune", 3, 3, "G"), "shogún", "fauna");
        CardSlot res = card("rs", "Recurso", 2, 2, "G");
        yako.getResources().add(res);
        summonByEffectToFront(yako);
        answer("e");
        answer("rs");
        assertThat(host.getField()).contains(res);
        assertThat(EffectEngine.strength(enemy)).isEqualTo(3);
    }

    @Test
    void triceratopsTargetedAddsADinosaurAndReactivates() {
        CardSlot tri = onField(host, withClasses(card("tr", "Triceratops", 3, 3, "G"), "dinosaurio"));
        tri.setTapped(true);
        CardSlot dino = inHand("dn", "Dino", 1, 1, "G", "dinosaurio");
        room.setCurrentTurnUserId("guest");
        EffectEngine.record(room, GameEvent.TARGET, "guest", tri, null, null, null, Map.of("targetIds", List.of("tr")), "objetivo");
        Chain.advance(room);
        accept(tri);
        answer("dn");
        assertThat(tri.getResources()).contains(dino);
        assertThat(tri.isTapped()).isFalse();
    }

    @Test
    void braquiosaurusExhaustedSacrificesTheWeakFrontLine() {
        CardSlot braq = onField(host, withClasses(card("bq", "Braquiosaurus", 6, 4, "G"), "dinosaurio"));
        braq.setStrength(12);
        CardSlot e = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        CardSlot mine = onField(host, card("mi", "Mío", 1, 1, "G"));
        assertThat(CombatRules.attackByPlayer(room, "host", "bq", null)).isNull();
        drain();
        assertThat(guest.getField()).doesNotContain(e);
        assertThat(host.getField()).doesNotContain(mine);
    }

    @Test
    void quetzalcoatlusSacrificesAWeakerAllyAndSkipsTheBlock() {
        CardSlot q = onField(host, withClasses(card("qz", "Quetzalcoatlus", 6, 4, "G"), "dinosaurio"));
        q.setStrength(11);
        onField(host, card("wk", "Débil", 1, 1, "G"));
        host.getTributeZone().add(withClasses(card("dn", "Dino", 1, 1, "G"), "dinosaurio"));
        onField(guest, card("bl", "Bloqueador", 1, 1, "R"));
        assertThat(CombatRules.attackByPlayer(room, "host", "qz", null)).isNull();
        untilOffered(q);
        answer("qz");
        answer("wk");
        answer("dn");
        drain();
        assertThat(q.getResources()).extracting(CardSlot::getInstanceId).contains("dn");
        assertThat(guest.getLifeStack()).hasSize(2);
    }

    @Test
    void titanoraxGivesAReplacementThatTributesItsHydraResources() {
        CardSlot holder = onField(host, card("ho", "Portador", 4, 4, "G"));
        holder.getResources().addAll(List.of(withClasses(card("tx", "Titanorax", 6, 4, "G"), "hydra"), withClasses(card("hy", "Hydra", 1, 1, "G"), "hydra")));
        assertThat(CardEffects.replacementsOf(holder)).hasSize(1);
    }

    @Test
    void carnirexSacrificesAnActiveAllyAndDestroysTheTopLife() {
        CardSlot carni = onField(host, withClasses(card("cx", "Carnirex", 10, 5, "G"), "dinosaurio"));
        carni.setStrength(14);
        onField(host, card("al", "Aliado", 1, 1, "G"), BACK);
        assertThat(CombatRules.attackByPlayer(room, "host", "cx", null)).isNull();
        answer("al");
        drain();
        assertThat(guest.getDiscardPile()).hasSize(1);
    }

    @Test
    void titanobrachiosIsCheaperForEveryTwoCardsSentToTheDeck() {
        CardSlot titan = inHand("tb", "Titanobrachios", 10, 5, "G", "dinosaurio");
        for (int i = 0; i < 20; i++) host.getTributeZone().add(card("t" + i, "T" + i, 1, 1, "G"));
        int before = gems();
        assertThat(Chain.activateByPlayer(room, "host", "tb", null)).isNull();
        answer(host.getTributeZone().stream().limit(16).map(CardSlot::getInstanceId).toArray(String[]::new));
        assertThat(host.getField()).contains(titan);
        assertThat(gems()).isEqualTo(before - 2);
    }

    @Test
    void tierraJurasicaSummonsADinosaurOfTheLevelOfTheCopiesExhausted() {
        CardSlot t1 = onField(host, structure("t1", "Tierra Jurásica", 1, "G"), BACK);
        CardSlot t2 = onField(host, structure("t2", "Tierra Jurásica", 1, "G"), BACK);
        CardSlot dino = inHand("dn", "Dino Dos", 3, 2, "G", "dinosaurio");
        CardSlot ally = onField(host, card("al", "Aliado", 1, 1, "G"));
        Removals.destroy(room, ally, host, false, EffectEngine.CAUSE_EFFECT, "host", "src", hostEffect());
        Chain.advance(room);
        untilOffered(t1);
        answer(room.getPrompt(), room.getPrompt().getValidIds().contains("t1") && room.getPrompt().getMax() > 1 ? new String[]{"t1", "t2"} : new String[]{"t1"});
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("t2")) answer("t1", "t2");
        answer("dn");
        assertThat(host.getField()).contains(dino);
        assertThat(t1.isTapped() && t2.isTapped()).isTrue();
    }

    @Test
    void primerAlientoStaysAndLaterGoesToTheDiscardToSummonAGreen() {
        room.setFirstTurn(false);
        host.getDeck().add(0, card("g1", "Verde", 1, 1, "G"));
        inHand("x", "Pago", 1, 1, "R");
        CardSlot aliento = utility("pa", "Primer Aliento", 1, "G");
        play(aliento);
        answer("g1");
        answer("x");
        assertThat(host.getField()).contains(aliento);
        aliento.setTapped(false);
        CardSlot green = host.getHand().stream().filter(c -> "g1".equals(c.getInstanceId())).findFirst().orElseThrow();
        assertThat(Chain.activateByPlayer(room, "host", "pa", "primer-aliento:turno-aliado")).isNull();
        answer("g1");
        assertThat(host.getField()).contains(green);
        assertThat(host.getDiscardPile()).contains(aliento);
    }

    @Test
    void fosilBecomesAResourceOfAnAlly() {
        CardSlot ally = onField(host, card("al", "Aliado", 2, 2, "G"));
        CardSlot fosil = withClasses(utility("fo", "Fósil", 1, "G"), "dinosaurio");
        play(fosil);
        answer("al");
        assertThat(ally.getResources()).contains(fosil);
    }

    // ------------------------------------------------------------------ Violeta

    @Test
    void calaveraErranteComesBackExhaustedAtTheEndOfTheTurn() {
        CardSlot calavera = onField(host, withClasses(card("ce", "Calavera Errante", 1, 1, "P"), "bestia"));
        inHand("x", "Pago", 1, 1, "R");
        destroyByOpponent(calavera);
        accept(calavera);
        answer("x");
        drain();
        TurnRules.turnEnding(room, "host");
        for (int i = 0; i < 6 && room.getPrompt() != null && !room.getPrompt().getValidIds().contains("ce"); i++) answer(room.getPrompt());
        answer("ce");
        assertThat(host.getField()).contains(calavera);
        assertThat(calavera.isTapped()).isTrue();
        assertThat(EffectEngine.inFrontLine(calavera)).isTrue();
    }

    @Test
    void asechadorSendsFiveMuertosVivientesForAGem() {
        CardSlot asechador = withClasses(card("as", "Asechador De Las Almas Perdidas", 1, 1, "P"), "muerto viviente");
        host.getTributeZone().add(asechador);
        for (int i = 0; i < 5; i++) host.getTributeZone().add(withClasses(card("m" + i, "Muerto " + i, 1, 1, "P"), "muerto viviente"));
        int before = gems();
        EffectEngine.record(room, GameEvent.TRIBUTE, "host", asechador, "hand", "tributeZone", null, null, "tributa");
        Chain.advance(room);
        accept(asechador);
        answer("m0", "m1", "m2", "m3", "m4");
        assertThat(host.getDiscardPile()).contains(asechador);
        assertThat(gems()).isEqualTo(before + 1);
    }

    @Test
    void brujaLunarPlacesItselfAndAnOpponentsTributeInTheFrontLines() {
        CardSlot bruja = withClasses(card("bl", "Bruja Lunar", 1, 1, "P"), "brujo");
        host.getTributeZone().add(bruja);
        CardSlot theirs = card("th", "Suya", 1, 1, "R");
        guest.getTributeZone().add(theirs);
        assertThat(Chain.activateByPlayer(room, "host", "bl", null)).isNull();
        answer("th");
        assertThat(host.getField()).contains(bruja);
        assertThat(guest.getField()).contains(theirs);
    }

    @Test
    void alaridoEspectralCostsTwoLessPerTenVioletTributes() {
        for (int i = 0; i < 10; i++) host.getTributeZone().add(card("t" + i, "T" + i, 1, 1, "P"));
        int before = gems();
        summon(withClasses(card("ae", "Alarido Espectral", 5, 2, "P"), "espectro"));
        assertThat(gems()).isEqualTo(before - 3);
    }

    @Test
    void lureruriPlaysAVioletUtilityFromTheTributesIfItTributedOne() {
        CardSlot util = utility("ut", "Utilidad Violeta", 1, "P");
        CardEffects.register("utilidad violeta", new EffectScript("utilidad-violeta:en-juego", EffectScript.Moment.ON_PLAY, true, "En Juego",
                "Gana 1 gema.", List.of(Compose.gainGems(1))));
        host.getHand().add(util);
        summon(withClasses(card("lu", "Lureruri", 2, 2, "P"), "brujo"));
        answer("ut");
        answer("lu");
        answer("ut");
        drain();
        assertThat(host.getTributeZone()).doesNotContain(util);
    }

    @Test
    void centaurosDestroysItselfAndAnEnemyAtTheEndOfTheTurn() {
        CardSlot cent = onField(host, withClasses(card("cn", "Centauros De La Noche Iluminada", 3, 3, "P"), "muerto viviente"));
        CardSlot enemy = onField(guest, card("e", "Enemigo", 3, 3, "R"));
        TurnRules.turnEnding(room, "host");
        for (int i = 0; i < 10 && room.getPrompt() != null && !room.getPrompt().getValidIds().contains("e"); i++) answer(room.getPrompt());
        answer("e");
        assertThat(host.getField()).doesNotContain(cent);
        assertThat(guest.getField()).doesNotContain(enemy);
    }

    @Test
    void augurioCatastroficoTributedByABrujoGivesAGem() {
        CardSlot augurio = withClasses(card("ac", "Augurio Catastrófico", 3, 3, "P"), "brujo", "brujería");
        host.getTributeZone().add(augurio);
        CardSlot brujo = onField(host, withClasses(card("bj", "Brujo", 1, 1, "P"), "brujo"));
        inHand("bz", "Brujería", 1, 1, "P", "brujería");
        int before = gems();
        PendingEffect byBrujo = Chain.pending(room, CardEffects.of("Dragón Goloso").get(0), "host", brujo, room.getEventSeq());
        EffectEngine.record(room, GameEvent.TRIBUTE, "host", augurio, "deck", "tributeZone", byBrujo, Map.of("byCard", "bj"), "tributa");
        Chain.advance(room);
        accept(augurio);
        answer("bz");
        assertThat(host.getDiscardPile()).contains(augurio);
        assertThat(gems()).isEqualTo(before + 1);
    }

    @Test
    void amalgamaSacrificesItselfToDestroyAnEnemyOfLevelOne() {
        CardSlot amalgama = onField(host, withClasses(card("am", "Amalgama Cadavérica", 6, 4, "P"), "espectro"));
        amalgama.setStrength(11);
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        assertThat(CombatRules.attackByPlayer(room, "host", "am", null)).isNull();
        untilOffered(amalgama);
        answer("am");
        answer("e");
        drain();
        assertThat(guest.getField()).doesNotContain(enemy);
        assertThat(host.getTributeZone()).contains(amalgama);
    }

    @Test
    void vigilanteCadavericoSummonsAMuertoVivienteAtTheEndOfTheTurn() {
        CardSlot vig = onField(host, withClasses(card("vc", "Vigilante Cadavérico", 4, 4, "P"), "bestia"));
        CardSlot muerto = withClasses(card("mv", "Muerto Tres", 3, 3, "P"), "muerto viviente");
        host.getTributeZone().add(muerto);
        inHand("x", "Pago", 1, 1, "R");
        destroyByOpponent(vig);
        accept(vig);
        answer("x");
        drain();
        TurnRules.turnEnding(room, "host");
        for (int i = 0; i < 6 && room.getPrompt() != null && !room.getPrompt().getValidIds().contains("mv"); i++) {
            Prompt p = room.getPrompt();
            answer(p, p.getValidIds().contains("vc") ? new String[]{"vc"} : new String[0]);
        }
        answer("mv");
        assertThat(host.getField()).contains(muerto);
    }

    @Test
    void conjuradoOlvidadoGainsOnePerBrujeriaOnItsTurn() {
        CardSlot conj = onField(host, withClasses(card("co", "Conjurado Olvidado", 5, 4, "P"), "brujo"));
        conj.setStrength(1);
        for (int i = 0; i < 3; i++) host.getTributeZone().add(withClasses(utility("b" + i, "B" + i, 1, "P"), "brujería"));
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.strength(conj)).isEqualTo(4);
    }

    @Test
    void zombiBerserkerCannotBlockAndDestroysEnemiesAddingFour() {
        CardSlot zombi = onField(host, withClasses(card("zb", "Zombi Berserker", 9, 5, "P"), "muerto viviente"));
        EffectEngine.refreshContinuous(room);
        assertThat(CombatRules.blockers(host)).doesNotContain(zombi);
        CardSlot e1 = onField(guest, card("e1", "E1", 1, 1, "R"));
        CardSlot e3 = onField(guest, card("e3", "E3", 3, 3, "R"));
        assertThat(CombatRules.attackByPlayer(room, "host", "zb", null)).isNull();
        Prompt choose = room.getPrompt();
        assertThat(Answers.answer(room, "host", choose.getId(), List.of("e3"), List.of())).contains("4");
        answer(choose, "e1", "e3");
        assertThat(guest.getField()).doesNotContain(e1, e3);
    }

    @Test
    void licheCostsOneLessPerBrujeriaInTheDiscardAndTributes() {
        for (int i = 0; i < 5; i++) host.getDiscardPile().add(withClasses(utility("d" + i, "D" + i, 1, "P"), "brujería"));
        for (int i = 0; i < 5; i++) host.getTributeZone().add(withClasses(utility("t" + i, "T" + i, 1, "P"), "brujería"));
        int before = gems();
        summon(withClasses(card("li", "Liche", 12, 5, "P"), "brujo"));
        assertThat(gems()).isEqualTo(before - 2);
    }

    @Test
    void imperioDesoladoGainsAGemWhenAHighCostUtilityIsPlayed() {
        CardSlot imperio = onField(host, withClasses(structure("id", "Imperio Desolado", 1, "P"), "brujo"), BACK);
        int before = gems();
        play(utility("bg", "Utilidad Cara", 3, "P"));
        untilOffered(imperio);
        answer("id");
        drain();
        assertThat(gems()).isEqualTo(before - 3 + 1);
    }

    @Test
    void llamadaDeLosMuertosSummonsUpToThreeLevelOnes() {
        for (int i = 0; i < 3; i++) host.getDiscardPile().add(withClasses(card("m" + i, "Muerto " + i, 1, 1, "P"), "muerto viviente"));
        inHand("x1", "Pago 1", 1, 1, "R");
        inHand("x2", "Pago 2", 1, 1, "R");
        play(utility("ll", "Llamada De Los Muertos", 2, "P"));
        answer("x1", "x2");
        answer("m0", "m1", "m2");
        assertThat(host.getField()).extracting(CardSlot::getInstanceId).contains("m0", "m1", "m2");
    }

    @Test
    void convocacionDeMuertosSummonsTwoVioletsFromTheTributes() {
        host.getTributeZone().addAll(List.of(card("v1", "V1", 1, 1, "P"), card("v2", "V2", 1, 1, "P")));
        play(withClasses(utility("cm", "Convocación De Muertos", 2, "P"), "brujería"));
        answer("v1", "v2");
        assertThat(host.getField()).extracting(CardSlot::getInstanceId).contains("v1", "v2");
    }

    @Test
    void cosechaDeAlmasSacrificesThreeAndGainsAGemPerAlly() {
        CardSlot a1 = onField(host, card("a1", "A1", 1, 1, "P"));
        CardSlot a2 = onField(host, card("a2", "A2", 1, 1, "P"));
        CardSlot e1 = onField(guest, card("e1", "E1", 1, 1, "R"));
        int before = gems();
        play(withClasses(utility("cs", "Cosecha De Almas", 3, "P"), "brujería"));
        answer("a1", "a2", "e1");
        assertThat(host.getTributeZone()).contains(a1, a2);
        assertThat(guest.getTributeZone()).contains(e1);
        assertThat(gems()).isEqualTo(before - 3 + 2);
    }

    @Test
    void maleficioDestroysALowerEnemyAndTheBrujoDealsDirectDamage() {
        onField(host, withClasses(card("bj", "Brujo", 3, 3, "P"), "brujo"));
        CardSlot enemy = onField(guest, card("e", "Enemigo", 2, 2, "R"));
        play(withClasses(utility("md", "Maleficio Devastador", 4, "P"), "brujería"));
        answer("bj");
        answer("e");
        assertThat(guest.getField()).doesNotContain(enemy);
        assertThat(guest.getLifeStack()).hasSize(2);
    }

    @Test
    void quebrantamientoLeavesOneCreaturePerFrontLine() {
        CardSlot keepH = onField(host, card("kh", "Queda H", 1, 1, "P"));
        CardSlot goneH = onField(host, card("gh", "Se va H", 1, 1, "P"));
        CardSlot keepG = onField(guest, card("kg", "Queda G", 1, 1, "R"));
        CardSlot goneG = onField(guest, card("gg", "Se va G", 1, 1, "R"));
        play(withClasses(utility("qb", "Quebrantamiento", 4, "P"), "brujería"));
        answer("kh");
        answer("kg");
        assertThat(host.getField()).contains(keepH).doesNotContain(goneH);
        assertThat(guest.getField()).contains(keepG).doesNotContain(goneG);
        // Each Marcador goes down by 1 (8 → 7); one at 0 stays at 0
        assertThat(host.getResourceCount()).isEqualTo(7);
        assertThat(guest.getResourceCount()).isEqualTo(0);
    }

    @Test
    void ritualConvocatorioPlacesABrujoAndReturnsAnAllyOfItsLevelForTwoGems() {
        CardSlot brujo = withClasses(card("bj", "Brujo", 2, 2, "P"), "brujo");
        host.getTributeZone().add(brujo);
        CardSlot two = onField(host, card("tw", "Dos", 2, 2, "P"));
        int before = gems();
        play(withClasses(utility("rc", "Ritual Convocatorio", 4, "P"), "brujería"));
        answer("bj");
        answer("tw");
        assertThat(host.getField()).contains(brujo);
        assertThat(host.getHand()).contains(two);
        assertThat(gems()).isEqualTo(before - 4 + 2);
    }

    @Test
    void theBrujeriasOfE2CostTwoGemsToPlayFromTheLives() {
        for (String n : List.of("Maleficio Devastador", "Quebrantamiento", "Ritual Convocatorio"))
            assertThat(CardEffects.lifePlayCost(utility("x", n, 4, "P"))).isEqualTo(2);
    }
}
