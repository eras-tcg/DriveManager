package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Cards in testing T1, archetype Ángeles (2026-11-13): one test (or more) per programmed effect, from the printed text. */
class T1AngelesCardsTest extends CardBatchSupport {

    private void destroyByOpponent(CardSlot card) {
        Removals.destroy(room, card, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
    }

    private static CardSlot angel(String id, String name, int cost, int level) {
        return withClasses(card(id, name, cost, level, "W"), "ángel");
    }

    private static CardSlot fallen(String id, String name, int cost, int level) {
        return withClasses(card(id, name, cost, level, "W"), "ángel caído");
    }

    private static CardSlot special(CardSlot card, String kind, int cost, String requirement) {
        card.setSpecialSummonKind(kind);
        card.setSpecialCost(cost);
        card.setRequirement(requirement);
        Req.program(card);
        return card;
    }

    private boolean offered(CardSlot source) {
        Prompt p = room.getPrompt();
        return p != null && p.getValidIds().contains(source.getInstanceId());
    }

    private void lifeRemoved() {
        CardSlot life = host.getLifeStack().remove(0);
        host.getHand().add(life);
        EffectEngine.record(room, GameEvent.LIFE_DAMAGE, "guest", life, "lifeStack", "hand", null, Map.of("defenderId", "host"), "daño");
        Chain.advance(room);
    }

    private void endOfHostTurn() {
        EffectEngine.record(room, GameEvent.TURN_END, "host", null, null, null, null, null, "fin del turno");
        Chain.advance(room);
    }

    // ------------------------------------------------------------------ W01 Bazael

    @Test
    void bazaelTakesUpToTwoWhiteOrAngelsAndTributesOneFromTheHand() {
        host.getDeck().addAll(0, List.of(angel("a1", "Ángel Uno", 1, 1), card("r1", "Roja", 1, 1, "R"),
                withClasses(card("r2", "Roja Caída", 1, 1, "R"), "ángel caído"), card("r3", "Roja Tres", 1, 1, "R"), card("r4", "Roja Cuatro", 1, 1, "R")));
        summon(angel("bz", "Bazael Pintor del Firmamento", 1, 1));
        assertThat(room.getPrompt().getValidIds()).containsExactlyInAnyOrder("a1", "r2");
        answer("a1", "r2");
        answer("r2");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("a1").doesNotContain("r2");
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).containsExactly("r2");
        assertThat(host.getDeck().subList(host.getDeck().size() - 3, host.getDeck().size())).extracting(CardSlot::getInstanceId)
                .containsExactlyInAnyOrder("r1", "r3", "r4");
    }

    @Test
    void bazaelBecomesAMaterialOfAnAllyWhenDestroyed() {
        CardSlot ally = onField(host, angel("al", "Aliado", 2, 2));
        CardSlot bazael = onField(host, angel("bz", "Bazael Pintor del Firmamento", 1, 1));
        destroyByOpponent(bazael);
        accept(bazael);
        answer("al");
        assertThat(ally.getMaterials()).containsExactly(bazael);
        assertThat(host.getDiscardPile()).doesNotContain(bazael);
    }

    // ------------------------------------------------------------------ W02 Kristia

    @Test
    void kristiaChangesAnAngelIntoAFallenAngelUntilTheEndOfTheTurn() {
        CardSlot ally = onField(host, withClasses(card("al", "Aliado", 2, 2, "W"), "ángel", "bestia"));
        summon(withClasses(card("kr", "Kristia la Falsa Oradora", 1, 1, "W"), "ángel", "ángel caído"));
        accept(host.getField().get(1));
        answer("al");
        assertThat(ClassChanges.classes(ally)).containsExactlyInAnyOrder("bestia", "ángel caído");
        assertThat(CardFilter.creatures().withClasses("ángel").matches(ally)).isFalse();
        assertThat(CardFilter.creatures().withClasses("ángel caído").matches(ally)).isTrue();
        // Its printed types do not change, and the change goes when the turn ends
        assertThat(ally.getClasses()).containsExactly("ángel", "bestia");
        assertThat(ally.getFloating()).anyMatch(f -> "turn".equals(f.until()) && ClassChanges.isChange(f));
    }

    @Test
    void kristiaChangeIsOncePerTurnBetweenCopies() {
        onField(host, angel("al", "Aliado", 2, 2));
        CardSlot first = withClasses(card("k1", "Kristia la Falsa Oradora", 1, 1, "W"), "ángel", "ángel caído");
        summon(first);
        accept(first);
        answer("al");
        CardSlot second = withClasses(card("k2", "Kristia la Falsa Oradora", 1, 1, "W"), "ángel", "ángel caído");
        summon(second);
        assertThat(offered(second)).isFalse();
    }

    @Test
    void aChangedTypeIsNotHandedToTheCreatureSummonedFromIt() {
        CardSlot summoner = onField(host, angel("su", "Invocador", 1, 1));
        ClassChanges.change(room, Chain.pending(room, CardEffects.of("kristia la falsa oradora").get(0), "host", summoner, 0), summoner, "ángel", "ángel caído");
        CardSlot evolved = withClasses(card("ev", "Evolucionado", 2, 2, "W"), "bestia");
        Summons.inheritFromSummoner(room, evolved, summoner);
        assertThat(ClassChanges.classes(evolved)).containsExactly("bestia");
    }

    @Test
    void kristiaComesBackExhaustedToTheFrontLineForFreeWhenDestroyed() {
        CardSlot kristia = onField(host, withClasses(card("kr", "Kristia la Falsa Oradora", 1, 1, "W"), "ángel", "ángel caído"), BACK);
        inHand("x", "Pago", 1, 1, "R");
        int before = gems();
        destroyByOpponent(kristia);
        accept(kristia);
        answer("x");
        assertThat(host.getField()).contains(kristia);
        assertThat(kristia.isTapped()).isTrue();
        assertThat(EffectEngine.inFrontLine(kristia)).isTrue();
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).contains("x");
        assertThat(gems()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ W03 Hermes

    @Test
    void hermesIsSummonedFromTheHandReducedByOneWhenAnAllyIsDestroyed() {
        CardSlot hermes = inHand("he", "Hermes el Notario Mensajero", 2, 2, "W", "ángel");
        CardSlot ally = onField(host, angel("al", "Aliado", 1, 1));
        int before = gems();
        destroyByOpponent(ally);
        accept(hermes);
        assertThat(host.getField()).contains(hermes);
        assertThat(gems()).isEqualTo(before - 1);
    }

    @Test
    void hermesInTheHandIsOncePerTurnBetweenCopies() {
        CardSlot h1 = inHand("h1", "Hermes el Notario Mensajero", 2, 2, "W", "ángel");
        CardSlot h2 = inHand("h2", "Hermes el Notario Mensajero", 2, 2, "W", "ángel");
        CardSlot a1 = onField(host, angel("a1", "Aliado Uno", 1, 1));
        CardSlot a2 = onField(host, angel("a2", "Aliado Dos", 1, 1));
        destroyByOpponent(a1);
        accept(h1);
        drain();
        destroyByOpponent(a2);
        assertThat(offered(h2)).isFalse();
    }

    @Test
    void hermesTributesAMaterialToSpeciallySummonFromTheTributesAndGainsAGem() {
        CardSlot hermes = onField(host, angel("he", "Hermes el Notario Mensajero", 2, 2));
        hermes.getMaterials().add(card("m", "Material", 1, 1, "W"));
        CardSlot evo = special(angel("ev", "Evolución Blanca", 3, 2), "evolution", 1, "criatura nivel 1.");
        host.getTributeZone().add(evo);
        CardSlot peon = angel("pe", "Peón", 1, 1);
        int before = gems();
        summon(peon);
        accept(hermes);
        answer("m");
        answer("ev");
        answer("pe");
        drain();
        assertThat(host.getField()).contains(evo).doesNotContain(peon);
        assertThat(hermes.getMaterials()).isEmpty();
        assertThat(gems()).isEqualTo(before - 1 - 1 + 1);
    }

    // ------------------------------------------------------------------ W04 Nekias

    @Test
    void nekiasSacrificesAnEnemyLevelOneAndGainsAGem() {
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        CardSlot nekias = withClasses(card("nk", "Nekias la Inocente", 2, 2, "W"), "ángel", "ángel caído");
        int before = gems();
        summonByEffectToFront(nekias);
        accept(nekias);
        answer("e");
        drain();
        assertThat(guest.getTributeZone()).contains(enemy);
        assertThat(gems()).isEqualTo(before + 1);
    }

    @Test
    void nekiasCannotChooseAnEnemyOfTheRetaguardiaNorALevelTwo() {
        onField(guest, card("eb", "Enemigo Atrás", 1, 1, "R"), BACK);
        onField(guest, card("e2", "Enemigo Dos", 2, 2, "R"));
        CardSlot mine = onField(host, card("m1", "Mío", 1, 1, "W"));
        CardSlot nekias = withClasses(card("nk", "Nekias la Inocente", 2, 2, "W"), "ángel", "ángel caído");
        summonByEffectToFront(nekias);
        accept(nekias);
        assertThat(room.getPrompt().getValidIds()).containsExactlyInAnyOrder(mine.getInstanceId(), "iv");
    }

    @Test
    void nekiasPlaysAWhiteStructureFromTheHandReducedByOneWhenDestroyed() {
        CardSlot nekias = onField(host, withClasses(card("nk", "Nekias la Inocente", 2, 2, "W"), "ángel", "ángel caído"));
        CardSlot st = structure("st", "Estructura Blanca", 2, "W");
        host.getHand().add(st);
        host.getHand().add(structure("sr", "Estructura Roja", 1, "R"));
        int before = gems();
        destroyByOpponent(nekias);
        accept(nekias);
        assertThat(room.getPrompt().getValidIds()).containsExactly("st");
        answer("st");
        assertThat(host.getField()).contains(st);
        assertThat(gems()).isEqualTo(before - 1);
    }

    // ------------------------------------------------------------------ W05 Shilpher

    @Test
    void shilpherEntersTheFrontLineAtTheBlockStepAndBlocksThatAttack() {
        room.setCurrentTurnUserId("guest");
        room.setSharedCounter(0);
        CardSlot attacker = onField(guest, card("at", "Atacante", 1, 1, "R"));
        CardSlot shilpher = inHand("sh", "Shilpher el Protector", 2, 2, "W", "ángel", "bestia");
        assertThat(CombatRules.attackByPlayer(room, "guest", attacker.getInstanceId(), null)).isNull();
        untilOffered(shilpher);
        accept(shilpher);
        assertThat(host.getField()).contains(shilpher);
        assertThat(EffectEngine.inFrontLine(shilpher)).isTrue();
        untilOffered(shilpher);
        answer("sh");
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.BLOCK.equals(e.getType()) && "sh".equals(e.getCardInstanceId()));
    }

    @Test
    void shilpherGivesAnotherAllyAnEnDestruccionThatSummonsAnAngel() {
        CardSlot ally = onField(host, card("al", "Aliado", 1, 1, "R"));
        CardSlot angel = inHand("an", "Ángel De Mano", 2, 2, "W", "ángel");
        summon(withClasses(card("sh", "Shilpher el Protector", 2, 2, "W"), "ángel", "bestia"));
        answer("al");
        assertThat(EffectEngine.keywords(ally)).anyMatch(k -> k.startsWith("«En Destrucción"));
        int before = gems();
        destroyByOpponent(ally);
        accept(ally);
        answer("an");
        assertThat(host.getField()).contains(angel);
        assertThat(gems()).isEqualTo(before - 1);
    }

    @Test
    void theAbilityShilpherGivesIsLostWhenTheAllyLeavesTheField() {
        CardSlot ally = onField(host, card("al", "Aliado", 1, 1, "R"));
        inHand("an", "Ángel De Mano", 2, 2, "W", "ángel");
        summon(withClasses(card("sh", "Shilpher el Protector", 2, 2, "W"), "ángel", "bestia"));
        answer("al");
        Removals.leaveField(ally);
        assertThat(CardEffects.of(ally)).noneMatch(s -> s.moment() == EffectScript.Moment.ON_DESTROY);
    }

    // ------------------------------------------------------------------ W06 Lilith

    @Test
    void lilithPlacesALevelTwoAngelExhaustedInTheFrontLine() {
        CardSlot two = angel("a2", "Ángel Dos", 2, 2);
        host.getDiscardPile().add(two);
        host.getTributeZone().add(angel("a3", "Ángel Tres", 3, 3));
        CardSlot lilith = withClasses(card("li", "Lilith las Alas del Susurro", 3, 3, "W"), "ángel caído");
        summon(lilith);
        accept(lilith);
        assertThat(room.getPrompt().getValidIds()).containsExactly("a2");
        answer("a2");
        assertThat(host.getField()).contains(two);
        assertThat(two.isTapped()).isTrue();
        assertThat(EffectEngine.inFrontLine(two)).isTrue();
    }

    // ------------------------------------------------------------------ W07 Raziel

    @Test
    void razielTributesAHandCardToSummonOneOfItsMaterialsReducedByOne() {
        CardSlot raziel = angel("rz", "Raziel el Ojo de la Luz y la Oscuridad", 3, 3);
        CardSlot material = card("m", "Material", 2, 2, "W");
        raziel.getMaterials().add(material);
        inHand("x", "Pago", 1, 1, "R");
        int before = gems();
        summon(raziel);
        accept(raziel);
        answer("x");
        answer("m");
        assertThat(host.getField()).contains(material);
        assertThat(raziel.getMaterials()).isEmpty();
        assertThat(gems()).isEqualTo(before - 3 - 1);
    }

    @Test
    void razielSacrificesAnAllyToPlaceALevelTwoAngelExhaustedOncePerTurn() {
        CardSlot raziel = onField(host, angel("rz", "Raziel el Ojo de la Luz y la Oscuridad", 3, 3));
        CardSlot victim = onField(host, card("vi", "Víctima", 1, 1, "R"));
        CardSlot two = inHand("a2", "Ángel Dos", 2, 2, "W", "ángel");
        inHand("a4", "Ángel Dos Bis", 2, 2, "W", "ángel");
        assertThat(Chain.activateByPlayer(room, "host", "rz", "raziel:turno-aliado")).isNull();
        drain2("vi", "a2");
        assertThat(host.getTributeZone()).contains(victim);
        assertThat(host.getField()).contains(two);
        assertThat(two.isTapped()).isTrue();
        assertThat(EffectEngine.inFrontLine(two)).isFalse();
        assertThat(Chain.activateByPlayer(room, "host", "rz", "raziel:turno-aliado")).isNotNull();
        assertThat(host.getField()).contains(raziel);
    }

    /** Answers the next two choices with these cards, accepting the effect first if it is asked. */
    private void drain2(String first, String second) {
        Prompt p = room.getPrompt();
        if (p != null && !p.getValidIds().contains(first) && p.getValidIds().size() == 1) answer(p.getValidIds().get(0));
        answer(first);
        answer(second);
    }

    // ------------------------------------------------------------------ W08 Samael, W09 Uriel

    @Test
    void samaelReducesTheChosenEnemyBySevenAndCannotBlock() {
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        enemy.setStrength(9);
        CardSlot samael = fallen("sa", "Samael el Fuego de la Destrucción", 6, 4);
        summonByEffectToFront(samael);
        answer("e");
        drain();
        assertThat(EffectEngine.strength(enemy)).isEqualTo(2);
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.keywords(samael)).contains(EffectEngine.NO_BLOCK);
    }

    @Test
    void samaelTributesAllItsMaterialsAtTheEndOfTheTurnToPlaceAnAngel() {
        CardSlot samael = onField(host, fallen("sa", "Samael el Fuego de la Destrucción", 6, 4));
        CardSlot m1 = card("m1", "M1", 1, 1, "W");
        CardSlot m2 = card("m2", "M2", 1, 1, "W");
        samael.getMaterials().addAll(List.of(m1, m2));
        CardSlot three = angel("a3", "Ángel Tres", 3, 3);
        host.getTributeZone().addAll(List.of(three, angel("a4", "Ángel Cuatro", 4, 4), fallen("f3", "Caído Tres", 3, 3)));
        endOfHostTurn();
        accept(samael);
        assertThat(room.getPrompt().getValidIds()).containsExactly("a3");
        answer("a3");
        assertThat(samael.getMaterials()).isEmpty();
        assertThat(host.getTributeZone()).contains(m1, m2);
        assertThat(host.getField()).contains(three);
        assertThat(EffectEngine.inFrontLine(three)).isTrue();
        assertThat(three.isTapped()).isFalse();
    }

    @Test
    void samaelWithoutMaterialsStillPlacesAnAngel() {
        // "tributar todos los materiales" with none is an empty action: the cost counts as paid (designer, 2026-11-13)
        CardSlot samael = onField(host, fallen("sa", "Samael el Fuego de la Destrucción", 6, 4));
        CardSlot three = angel("a3", "Ángel Tres", 3, 3);
        host.getTributeZone().add(three);
        endOfHostTurn();
        accept(samael);
        answer("a3");
        assertThat(host.getField()).contains(three);
    }

    @Test
    void urielReducesEveryEnemyOfTheFrontLineByThree() {
        CardSlot front = onField(guest, card("ef", "Frente", 1, 1, "R"));
        front.setStrength(5);
        CardSlot back = onField(guest, card("eb", "Atrás", 1, 1, "R"), BACK);
        back.setStrength(5);
        summonByEffectToFront(angel("ur", "Uriel el Fuego de la Esperanza", 6, 4));
        drain();
        assertThat(EffectEngine.strength(front)).isEqualTo(2);
        assertThat(EffectEngine.strength(back)).isEqualTo(5);
    }

    @Test
    void urielPlacesAFallenAngelAtTheEndOfTheTurn() {
        CardSlot uriel = onField(host, angel("ur", "Uriel el Fuego de la Esperanza", 6, 4));
        uriel.getMaterials().add(card("m1", "M1", 1, 1, "W"));
        CardSlot two = fallen("f2", "Caído Dos", 2, 2);
        host.getTributeZone().addAll(List.of(two, angel("a2", "Ángel Dos", 2, 2)));
        endOfHostTurn();
        accept(uriel);
        assertThat(room.getPrompt().getValidIds()).containsExactly("f2");
        answer("f2");
        assertThat(host.getField()).contains(two);
    }

    // ------------------------------------------------------------------ W10 Serafín

    @Test
    void serafinSendsAnEnemyOfTheRetaguardiaToTheDiscardFaceDown() {
        CardSlot back = onField(guest, card("eb", "Atrás", 1, 1, "R"), BACK);
        onField(guest, card("ef", "Frente", 1, 1, "R"));
        CardSlot serafin = onField(host, withClasses(card("sf", "Serafín Ignoto el Fuego del Caos", null, 5, "W"), "ángel", "ángel caído"));
        EffectEngine.record(room, GameEvent.SPECIAL_SUMMON, "host", serafin, "hand", "field", null, Map.of("summonType", "ritual"), "ritual");
        Chain.advance(room);
        assertThat(room.getPrompt().getValidIds()).containsExactlyInAnyOrder("eb", "ef");
        answer("eb");
        assertThat(guest.getDiscardPile()).contains(back);
        assertThat(back.isFaceDown()).isTrue();
    }

    @Test
    void serafinCannotBeSummonedNormally() {
        CardSlot serafin = withClasses(card("sf", "Serafín Ignoto el Fuego del Caos", null, 5, "W"), "ángel", "ángel caído");
        host.getHand().add(serafin);
        assertThat(Summons.normalSummon(room, "host", "sf", 50.0, 75.0)).isNotNull();
    }

    // ------------------------------------------------------------------ W11 Catedral

    @Test
    void catedralExhaustsForAGemWhenAnAngelIsSummonedByEffect() {
        CardSlot catedral = onField(host, structure("ca", "Catedral de la Luz Divina", 1, "W"), BACK);
        summonByEffectToFront(angel("an", "Ángel", 0, 1));
        int before = gems();
        accept(catedral);
        assertThat(catedral.isTapped()).isTrue();
        assertThat(gems()).isEqualTo(before + 1);
    }

    @Test
    void catedralDoesNothingForANormalSummon() {
        CardSlot catedral = onField(host, structure("ca", "Catedral de la Luz Divina", 1, "W"), BACK);
        summon(angel("an", "Ángel", 1, 1));
        assertThat(offered(catedral)).isFalse();
    }

    @Test
    void catedralIsTributedToPutTheTopOfTheDeckOnTheLivesOncePerTurnBetweenCopies() {
        CardSlot c1 = onField(host, structure("c1", "Catedral de la Luz Divina", 1, "W"), BACK);
        CardSlot c2 = onField(host, structure("c2", "Catedral de la Luz Divina", 1, "W"), BACK);
        CardSlot top = host.getDeck().get(0);
        lifeRemoved();
        Prompt p = room.getPrompt();
        answer(p, p.getValidIds().get(0));
        drain();
        assertThat(host.getLifeStack().get(0)).isSameAs(top);
        assertThat(host.getTributeZone()).containsAnyOf(c1, c2);
        lifeRemoved();
        assertThat(offered(c1) || offered(c2)).isFalse();
    }

    // ------------------------------------------------------------------ W12 Decepción

    @Test
    void decepcionMaterializesAFallenAngelWithAnAngelAllyIgnoringTheRequirementAndCost() {
        CardSlot ally = onField(host, angel("al", "Ángel Cuatro", 4, 4));
        CardSlot oldMaterial = card("om", "Material Viejo", 1, 1, "W");
        ally.getMaterials().add(oldMaterial);
        CardSlot fallen = special(fallen("fa", "Caído Cuatro", 6, 4), "materialization", 2, "criatura nivel 3 + aliados que sumen 3 o 4 en nivel.");
        host.getTributeZone().addAll(List.of(fallen, special(fallen("f3", "Caído Tres", 3, 3), "materialization", 1, "criatura nivel 2.")));
        int before = gems();
        play(utility("de", "Decepción del Nombre Perdido", 1, "W"));
        drain2("al", "fa");
        assertThat(host.getField()).contains(fallen).doesNotContain(ally);
        assertThat(fallen.getMaterials()).contains(ally, oldMaterial);
        assertThat(gems()).isEqualTo(before - 1);
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.SPECIAL_SUMMON.equals(e.getType()) && "fa".equals(e.getCardInstanceId()));
    }

    @Test
    void decepcionSacrificesTheAllyForAFallenAngelOfRitual() {
        CardSlot ally = onField(host, angel("al", "Ángel Dos", 2, 2));
        CardSlot ritual = special(withClasses(card("ri", "Caído Ritual", 2, 2, "W"), "ángel", "ángel caído"), "ritual", 0, "2 criaturas nivel 2 o menor.");
        host.getTributeZone().add(ritual);
        play(utility("de", "Decepción del Nombre Perdido", 1, "W"));
        drain2("al", "ri");
        assertThat(host.getField()).contains(ritual);
        assertThat(host.getTributeZone()).contains(ally);
    }

    @Test
    void decepcionDoesNothingWithoutAFallenAngelOfTheAllysLevel() {
        onField(host, angel("al", "Ángel Dos", 2, 2));
        CardSlot three = special(fallen("f3", "Caído Tres", 3, 3), "materialization", 1, "criatura nivel 2.");
        host.getTributeZone().add(three);
        play(utility("de", "Decepción del Nombre Perdido", 1, "W"));
        drain();
        assertThat(host.getTributeZone()).contains(three);
    }

    @Test
    void decepcionFromTheDiscardSendsTenTributesAndItselfToSpeciallySummonAnAngelReducedByTwo() {
        CardSlot decepcion = utility("de", "Decepción del Nombre Perdido", 1, "W");
        host.getDiscardPile().add(decepcion);
        for (int i = 0; i < 10; i++) host.getTributeZone().add(card("t" + i, "Tributo " + i, 1, 1, "R"));
        CardSlot summoner = onField(host, angel("su", "Ángel Uno", 1, 1));
        CardSlot evo = special(angel("ev", "Ángel Evolución", 3, 2), "evolution", 2, "criatura nivel 1.");
        host.getHand().add(evo);
        int before = gems();
        assertThat(Chain.activateByPlayer(room, "host", "de", "decepcion-nombre-perdido:en-descarte")).isNull();
        Prompt p = room.getPrompt();
        answer(p, "t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9");
        answer("ev");
        answer("su");
        drain();
        assertThat(host.getField()).contains(evo).doesNotContain(summoner);
        assertThat(host.getTributeZone()).isEmpty();
        assertThat(host.getDeck()).contains(decepcion);
        assertThat(gems()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ W13 Voluntad

    @Test
    void voluntadTakesTwoAngelsFromTheTributes() {
        CardSlot a1 = angel("a1", "Ángel Uno", 1, 1);
        CardSlot a2 = withClasses(card("a2", "Ángel Mixto", 2, 2, "W"), "ángel", "ángel caído");
        host.getTributeZone().addAll(List.of(a1, a2, fallen("f1", "Caído", 1, 1), angel("a3", "Ángel Tres", 3, 3)));
        play(utility("vo", "Voluntad del Fuego Ignoto", 1, "W"));
        assertThat(room.getPrompt().getValidIds()).containsExactlyInAnyOrder("a1", "a2", "a3");
        answer("a1", "a2");
        assertThat(host.getHand()).contains(a1, a2);
    }

    @Test
    void voluntadFromTheDiscardSendsFiveCardsWithItselfToSpeciallySummonAFallenAngel() {
        CardSlot voluntad = utility("vo", "Voluntad del Fuego Ignoto", 1, "W");
        host.getDiscardPile().add(voluntad);
        for (int i = 0; i < 4; i++) host.getTributeZone().add(card("t" + i, "Tributo " + i, 1, 1, "R"));
        CardSlot fallenEvo = special(fallen("fe", "Caído Evolución", 3, 2), "evolution", 1, "criatura nivel 1.");
        host.getTributeZone().add(fallenEvo);
        CardSlot summoner = angel("su", "Ángel Uno", 1, 1);
        summon(summoner);
        accept(voluntad);
        answer("vo", "t0", "t1", "t2", "t3");
        answer("fe");
        answer("su");
        drain();
        assertThat(host.getField()).contains(fallenEvo);
        assertThat(host.getDeck()).contains(voluntad);
    }

    // ------------------------------------------------------------------ Designer answers 2026-11-13

    @Test
    void serafinReachesTheEnemyRetaguardiaFromItsOwnRetaguardia() {
        CardSlot back = onField(guest, card("eb", "Atrás", 1, 1, "R"), BACK);
        CardSlot serafin = onField(host, withClasses(card("sf", "Serafín Ignoto el Fuego del Caos", null, 5, "W"), "ángel", "ángel caído"), BACK);
        EffectEngine.record(room, GameEvent.SPECIAL_SUMMON, "host", serafin, "hand", "field", null, Map.of("summonType", "ritual"), "ritual");
        Chain.advance(room);
        answer("eb");
        assertThat(guest.getDiscardPile()).contains(back);
    }

    @Test
    void kristiaCanChooseItselfAndATypeHeldTwiceCountsOnce() {
        CardSlot kristia = withClasses(card("kr", "Kristia la Falsa Oradora", 1, 1, "W"), "ángel", "ángel caído");
        summon(kristia);
        accept(kristia);
        answer("kr");
        assertThat(ClassChanges.classes(kristia)).containsExactly("ángel caído");
        CardSlot twice = withClasses(card("tw", "Doble", 1, 1, "W"), "ángel", "Ángel");
        assertThat(ClassChanges.classes(twice)).hasSize(1);
    }

    @Test
    void anInvocaEspecialmenteIsNotASummonByEffectForCatedral() {
        // "invoca especialmente" only enables the special summon: it is not an invocation by effect (designer, 2026-11-13)
        CardSlot catedral = onField(host, structure("ca", "Catedral de la Luz Divina", 1, "W"), BACK);
        CardSlot hermes = onField(host, angel("he", "Hermes el Notario Mensajero", 2, 2));
        hermes.getMaterials().add(card("m", "Material", 1, 1, "W"));
        CardSlot evo = special(angel("ev", "Evolución Blanca", 3, 2), "evolution", 1, "criatura nivel 1.");
        host.getTributeZone().add(evo);
        summon(angel("pe", "Peón", 1, 1));
        accept(hermes);
        answer("m");
        answer("ev");
        answer("pe");
        assertThat(host.getField()).contains(evo);
        assertThat(offered(catedral)).isFalse();
    }

    @Test
    void decepcionsSummonIsNotASummonByEffectForCatedral() {
        CardSlot catedral = onField(host, structure("ca", "Catedral de la Luz Divina", 1, "W"), BACK);
        onField(host, angel("al", "Ángel Dos", 2, 2));
        CardSlot fallenTwo = special(withClasses(card("f2", "Caído Dos", 2, 2, "W"), "ángel", "ángel caído"), "materialization", 1, "criatura nivel 1.");
        host.getTributeZone().add(fallenTwo);
        play(utility("de", "Decepción del Nombre Perdido", 1, "W"));
        drain2("al", "f2");
        assertThat(host.getField()).contains(fallenTwo);
        assertThat(offered(catedral)).isFalse();
    }

    @Test
    void nekiasRitualSacrificesTwoCreaturesOfLevelTwoOrLess() {
        CardSlot two = onField(host, angel("a2", "Ángel Dos", 2, 2));
        CardSlot one = onField(host, angel("a1", "Ángel Uno", 1, 1));
        onField(host, angel("a3", "Ángel Tres", 3, 3));
        CardSlot nekias = special(withClasses(card("nk", "Nekias la Inocente", 2, 2, "W"), "ángel", "ángel caído"), "ritual", 0, "2 criaturas nivel 2 o menor.");
        host.getHand().add(nekias);
        assertThat(Rituals.ritualByPlayer(room, "host", "nk", null)).isNull();
        assertThat(room.getPrompt().getValidIds()).containsExactlyInAnyOrder("a2", "a1");
        answer("a2");
        assertThat(room.getPrompt().getValidIds()).containsExactly("a1");
        answer("a1");
        assertThat(host.getField()).contains(nekias);
        assertThat(host.getTributeZone()).contains(two, one);
    }

    @Test
    void serafinsRitualNeedsAnAngelAndAFallenAngelOfLevelFour() {
        CardSlot serafin = special(withClasses(card("sf", "Serafín Ignoto el Fuego del Caos", null, 5, "W"), "ángel", "ángel caído"), "ritual", 3,
                "2 criaturas nivel 4 en juego, una [ángel] y otra [ángel caído].");
        host.getHand().add(serafin);
        onField(host, angel("a4", "Ángel Cuatro", 6, 4));
        onField(host, angel("b4", "Otro Ángel Cuatro", 6, 4));
        assertThat(Rituals.ritualByPlayer(room, "host", "sf", null)).isNotNull();
        CardSlot fallenFour = onField(host, fallen("f4", "Caído Cuatro", 6, 4));
        assertThat(Rituals.ritualByPlayer(room, "host", "sf", null)).isNull();
        answer("a4");
        assertThat(room.getPrompt().getValidIds()).containsExactly("f4");
        answer("f4");
        assertThat(host.getField()).contains(serafin);
        assertThat(host.getTributeZone()).contains(fallenFour);
    }

    private CardSlot lilithInRitual() {
        return special(withClasses(card("li", "Lilith las Alas del Susurro", 3, 3, "W"), "ángel caído"), "ritual", 2,
                "criatura nivel 2 + 1 criatura [ángel] nivel 2 o menor.");
    }

    @Test
    void lilithOnYourTurnNeedsAnAngelOfYoursAndNoEnemyIsOffered() {
        CardSlot lilith = lilithInRitual();
        host.getHand().add(lilith);
        onField(host, card("su", "Invocador", 2, 2, "W"));
        onField(guest, card("e2", "Enemigo Dos", 2, 2, "R"));
        assertThat(Rituals.ritualByPlayer(room, "host", "li", null)).isNotNull();
        CardSlot angelOne = onField(host, angel("a1", "Ángel Uno", 1, 1));
        assertThat(Rituals.ritualByPlayer(room, "host", "li", null)).isNull();
        answer("su");
        assertThat(room.getPrompt().getValidIds()).containsExactly("a1");
        answer("a1");
        assertThat(host.getField()).contains(lilith);
        assertThat(host.getTributeZone()).contains(angelOne);
    }

    @Test
    void lilithOnTheOpponentsTurnUsesAnEnemyOfTheirFrontLineAsTheAngel() {
        room.setCurrentTurnUserId("guest");
        room.setSharedCounter(0);
        CardSlot lilith = lilithInRitual();
        CardSlot voluntad = utility("vo", "Voluntad del Fuego Ignoto", 1, "W");
        host.getDiscardPile().add(voluntad);
        for (int i = 0; i < 4; i++) host.getTributeZone().add(card("t" + i, "Tributo " + i, 1, 1, "R"));
        host.getTributeZone().add(lilith);
        CardSlot summoner = onField(host, card("su", "Invocador", 2, 2, "W"));
        CardSlot enemy = onField(guest, card("e2", "Enemigo Dos", 2, 2, "R"));
        onField(guest, card("e3", "Enemigo Tres", 3, 3, "R"));
        onField(guest, card("eb", "Enemigo Atrás", 1, 1, "R"), BACK);
        // An ally of the host is summoned by effect on the opponent's turn: Voluntad lets Lilith be summoned by Ritual
        CardSlot newcomer = onField(host, card("nw", "Recién Llegado", 1, 1, "W"), BACK);
        EffectEngine.record(room, GameEvent.EFFECT_SUMMON, "host", newcomer, "hand", "field", null, null, "invocado por efecto");
        Chain.advance(room);
        accept(voluntad);
        answer("vo", "t0", "t1", "t2", "t3");
        answer("li");
        answer("su");
        assertThat(room.getPrompt().getValidIds()).containsExactly("e2");
        answer("e2");
        drain();
        assertThat(host.getField()).contains(lilith).doesNotContain(summoner);
        assertThat(guest.getTributeZone()).contains(enemy);
        assertThat(host.getTributeZone()).contains(summoner);
    }
}
