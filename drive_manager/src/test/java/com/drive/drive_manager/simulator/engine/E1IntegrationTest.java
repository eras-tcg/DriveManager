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

/** E1 effects and the mechanics they brought (choices that add up, bounce, auras, inherited "En Destrucción"…). */
class E1IntegrationTest {

    private GameRoom room;
    private PlayerState host;
    private PlayerState guest;

    @BeforeEach
    void setUp() {
        TestTable table = TestTable.inProgress(5, 8);
        room = table.room();
        host = table.host();
        guest = table.guest();
    }

    @Test
    void sacoDestroysUpToOneLevel2AndOneLevel1Enemy() {
        CardSlot level2 = onField(guest, card("e2", "Dos", 2, 2, "R"), FRONT);
        CardSlot level1 = onField(guest, card("e1", "Uno", 1, 1, "R"), FRONT);
        CardSlot saco = card("saco", "Saco De Los Mil Rostros", 1, 3, "P");
        host.getHand().add(saco);
        summonInFront("saco");

        Prompt first = room.getPrompt();
        assertThat(first.getValidIds()).containsExactly("e2");
        assertThat(first.getMin()).isZero();
        Answers.answer(room, "host", first.getId(), List.of("e2"), List.of());
        Prompt second = room.getPrompt();
        assertThat(second.getValidIds()).containsExactly("e1");
        Answers.answer(room, "host", second.getId(), List.of("e1"), List.of());

        assertThat(guest.getDiscardPile()).containsExactlyInAnyOrder(level2, level1);
    }

    @Test
    void filiremReducesAnEnemyToZeroAndDestroysIt() {
        CardSlot enemy = onField(guest, card("e", "Enemigo", 2, 2, "R"), FRONT);
        enemy.setStrength(6);
        host.getHand().add(card("fil", "Filirem", 1, 3, "B"));
        summonInFront("fil");
        Answers.answer(room, "host", room.getPrompt().getId(), List.of("e"), List.of());
        assertThat(guest.getDiscardPile()).containsExactly(enemy);
    }

    @Test
    void celesteTributesAndBouncesAnExhaustedEnemyWithItsMaterials() {
        CardSlot enemy = onField(guest, card("e", "Enemigo", 2, 2, "R"), FRONT);
        enemy.setTapped(true);
        enemy.getMaterials().add(card("m", "Material", 1, 1, "R"));
        host.getHand().add(card("cel", "Celeste", 1, 3, "G"));
        host.getHand().add(card("h1", "Mano", 1, 1, "G"));
        summonInFront("cel");
        // Cost: tribute 1 from the hand, then choose the target
        Answers.answer(room, "host", room.getPrompt().getId(), List.of("h1"), List.of());
        Answers.answer(room, "host", room.getPrompt().getId(), List.of("e"), List.of());

        assertThat(guest.getHand()).containsExactly(enemy);
        assertThat(guest.getDiscardPile()).extracting(CardSlot::getInstanceId).containsExactly("m");
        assertThat(enemy.isTapped()).isFalse();
    }

    @Test
    void hanaReactivatesWhenNoEnemyWasExhausted() {
        CardSlot hana = card("hana", "Hana", 1, 3, "G");
        host.getHand().add(hana);
        host.getHand().add(card("h1", "Mano", 1, 1, "G"));
        onField(guest, card("big", "Grande", 5, 4, "R"), FRONT).setStrength(12);
        summonInFront("hana");
        hana.setTapped(true);
        // Optional: activate it, then pay the cost
        Answers.answer(room, "host", room.getPrompt().getId(), List.of("hana"), List.of());
        Answers.answer(room, "host", room.getPrompt().getId(), List.of("h1"), List.of());
        assertThat(room.getPrompt()).isNull();
        assertThat(hana.isTapped()).isFalse();
    }

    @Test
    void driadaKeepsTheWeakestExhaustedEnemyExhaustedNextTurn() {
        CardSlot weak = onField(guest, card("w", "Débil", 1, 1, "R"), FRONT);
        weak.setTapped(true);
        weak.setStrength(2);
        CardSlot strong = onField(guest, card("s", "Fuerte", 1, 1, "R"), FRONT);
        strong.setTapped(true);
        strong.setStrength(5);
        host.getHand().add(card("dri", "Driada", 1, 2, "G"));
        host.getHand().add(card("h1", "Mano", 1, 1, "G"));
        summonInFront("dri");
        Answers.answer(room, "host", room.getPrompt().getId(), List.of("dri"), List.of());
        Answers.answer(room, "host", room.getPrompt().getId(), List.of("h1"), List.of());

        assertThat(weak.getNoUntap()).isEqualTo(2);
        assertThat(strong.getNoUntap()).isZero();
        // During its owner's next turn it cannot be reactivated
        weak.setNoUntap(1);
        assertThat(EffectEngine.reactivable(weak)).isFalse();
    }

    @Test
    void inheritedEnDestruccionTriggersWhenTheHolderIsDestroyed() {
        CardSlot holder = onField(host, card("holder", "Dragón", 3, 3, "G"), FRONT);
        holder.getResources().add(card("bestia", "Bestia Del Inframagma", 2, 2, "G"));
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"), FRONT);
        enemy.setStrength(5);

        Removals.destroyByPlayer(room, "guest", "host", "holder");

        Prompt choose = room.getPrompt();
        assertThat(choose.getSourceName()).isEqualTo("Dragón");
        Answers.answer(room, "host", choose.getId(), List.of("e"), List.of());
        assertThat(guest.getDiscardPile()).containsExactly(enemy);
    }

    @Test
    void bosqueFrondosoPaysPartOfAnEvolution() {
        room.setSharedCounter(-1);
        CardSlot bosque = onField(host, card("bosque", "Bosque Frondoso", 1, 2, "G"), BACK);
        bosque.setCardType("structure");
        onField(host, card("base", "Base", 1, 1, "G"), FRONT);
        CardSlot evolved = card("evo", "Evolución", 2, 2, "G");
        evolved.setSpecialSummonKind("evolution");
        evolved.setSpecialCost(2);
        evolved.setRequirement("criatura nivel 1.");
        host.getHand().add(evolved);

        // 1 gem and a cost of 2: the structure pays the other one
        assertThat(Summons.canSpecial(room, "host", evolved)).isTrue();
        assertThat(Summons.specialByPlayer(room, "host", "evo", "base")).isNull();
        assertThat(host.getField()).contains(evolved);
        assertThat(bosque.isTapped()).isTrue();
        assertThat(EffectEngine.gemsOf(room, "host")).isZero();
    }

    @Test
    void legionarioDeEspadasDoblesGivesStrengthToArmadaAlliesOnItsTurn() {
        CardSlot legionario = onField(host, card("leg", "Legionario De Espadas Dobles", 1, 1, "R"), FRONT);
        legionario.setClasses(new ArrayList<>(List.of("armada")));
        CardSlot sargento = onField(host, card("sar", "Sargento Legionario", 1, 1, "R"), FRONT);
        sargento.setClasses(new ArrayList<>(List.of("armada")));
        CardSlot other = onField(host, card("o", "Otro", 1, 1, "R"), FRONT);
        other.setClasses(new ArrayList<>(List.of("legionario")));

        EffectEngine.refreshContinuous(room);
        // +1 from the aura; the Sargento also +1 per [legionario] ally
        assertThat(EffectEngine.strength(legionario)).isEqualTo(4);
        assertThat(EffectEngine.strength(sargento)).isEqualTo(5);
        assertThat(EffectEngine.strength(other)).isEqualTo(3);

        room.setCurrentTurnUserId("guest");
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.strength(legionario)).isEqualTo(3);
    }

    @Test
    void grollIsSummonedToTheFrontWhenALifeIsRemoved() {
        host.getLifeStack().addAll(List.of(card("l1", "Vida", 1, 1, "R"), card("l2", "Vida", 1, 1, "R")));
        CardSlot groll = card("groll", "Groll Vandálico", 2, 1, "B");
        host.getHand().add(groll);
        room.setCurrentTurnUserId("guest");
        room.setSharedCounter(5);
        CardSlot attacker = onField(guest, card("a", "Atacante", 1, 1, "R"), FRONT);
        attacker.setStrength(5);

        CombatRules.attackByPlayer(room, "guest", "a", null);
        // No blockers: direct damage, then Groll (2 lives or fewer: cost 1) is offered from the hand
        Prompt offer = room.getPrompt();
        assertThat(offer.getPlayerId()).isEqualTo("host");
        Answers.answer(room, "host", offer.getId(), List.of("groll"), List.of());

        assertThat(host.getField()).contains(groll);
        assertThat(EffectEngine.inFrontLine(groll)).isTrue();
    }

    @Test
    void asaltoPlacesTheCreatureExhaustedInTheFrontLine() {
        CardSlot dafleirk = card("daf", "Dafleirk", 1, 3, "G");
        dafleirk.setKeywords(new ArrayList<>(List.of("Asalto")));
        host.getHand().add(dafleirk);
        Summons.normalSummon(room, "host", "daf", 50.0, BACK);
        // Its "En Invocación" has no target; Asalto is offered
        Prompt offer = room.getPrompt();
        Answers.answer(room, "host", offer.getId(), offer.getValidIds().subList(0, 1), List.of());
        assertThat(EffectEngine.inFrontLine(dafleirk)).isTrue();
        assertThat(dafleirk.isTapped()).isTrue();
    }

    @Test
    void ephosReducesAnEnemyWhenItIsTributedAsAMaterial() {
        CardSlot iron = onField(host, card("iron", "Iron", 5, 4, "B"), FRONT);
        iron.getMaterials().add(card("ephos", "Ephos", 1, 1, "B"));
        iron.getMaterials().add(card("m2", "Otro", 1, 1, "B"));
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"), FRONT);
        enemy.setStrength(5);

        CombatRules.attackByPlayer(room, "host", "iron", null);
        // Iron's "Al Atacar": activate, tribute 2 materials, choose the enemy
        Answers.answer(room, "host", room.getPrompt().getId(), List.of("iron"), List.of());
        Answers.answer(room, "host", room.getPrompt().getId(), List.of("ephos", "m2"), List.of());
        Answers.answer(room, "host", room.getPrompt().getId(), List.of("e"), List.of());
        // Ephos triggers from the Tributos: choose the enemy, then Iron resolves
        Answers.answer(room, "host", room.getPrompt().getId(), List.of("e"), List.of());
        assertThat(EffectEngine.strength(enemy)).isEqualTo(1);
    }

    @Test
    void zafraxDealsDirectDamageWhenItDestroysAnEnemyInAClash() {
        guest.getLifeStack().addAll(List.of(card("l1", "Vida", 1, 1, "R"), card("l2", "Vida", 1, 1, "R")));
        CardSlot zafrax = onField(host, card("z", "Zafrax", 2, 2, "P"), FRONT);
        zafrax.setStrength(5);
        CardSlot target = onField(guest, card("t", "Objetivo", 1, 1, "R"), FRONT);
        target.setTapped(true);

        CombatRules.attackByPlayer(room, "host", "z", "t");
        while (room.getPrompt() != null) {
            Prompt p = room.getPrompt();
            Answers.answer(room, p.getPlayerId(), p.getId(), List.of(), List.of());
        }
        assertThat(guest.getDiscardPile()).contains(target);
        assertThat(guest.getLifeStack()).hasSize(1);
    }

    // ------------------------------------------------------------------ White: Definitive effects

    @Test
    void onlyOneDefinitiveEffectPerTurnTheSecondUtilityGoesToTheDescarte() {
        // Mandato Imperial resolves (nobody in the front line, nothing in the opponent's Retaguardia to move)
        CardSlot mandato = utility("mi", "Mandato Imperial");
        CardSlot caos = utility("ca", "Llegada Del Ciclo Del Caos");
        host.getHand().addAll(List.of(mandato, caos));
        assertThat(Summons.playByPlayer(room, "host", "mi", 50.0, BACK)).isNull();
        assertThat(host.getDiscardPile()).contains(mandato);
        assertThat(Summons.playByPlayer(room, "host", "ca", 50.0, BACK)).isNull();
        // Its Definitive effect cannot be activated this turn: nothing to choose, it goes to the Descarte
        assertThat(room.getPrompt()).isNull();
        assertThat(host.getDiscardPile()).contains(caos);
    }

    @Test
    void prismaSendsBothHandsToTheBottomAndEachDrawsTheOpponentsLives() {
        host.getHand().addAll(List.of(card("h1", "Uno", 1, 1, "R"), card("h2", "Dos", 1, 1, "R")));
        guest.getHand().add(card("g1", "Tres", 1, 1, "R"));
        for (int i = 0; i < 3; i++) guest.getLifeStack().add(card("gl" + i, "Vida", 1, 1, "R"));
        host.getLifeStack().add(card("hl", "Vida", 1, 1, "R"));
        host.getHand().add(utility("pr", "Prisma De La Grieta Dimensional"));
        Summons.playByPlayer(room, "host", "pr", 50.0, BACK);
        assertThat(host.getHand()).hasSize(3).extracting(CardSlot::getInstanceId).doesNotContain("h1", "h2");
        assertThat(guest.getHand()).hasSize(1).extracting(CardSlot::getInstanceId).doesNotContain("g1");
        assertThat(host.getDeck()).extracting(CardSlot::getInstanceId).contains("h1", "h2");
    }

    @Test
    void llegadaDelCicloDelCaosDiscardsAtLeast5AndPutsTheRestOnTop() {
        host.setDeck(deck("hd", 9));
        host.getHand().add(utility("ca", "Llegada Del Ciclo Del Caos"));
        Summons.playByPlayer(room, "host", "ca", 50.0, BACK);
        Prompt pick = room.getPrompt();
        assertThat(pick.getMin()).isEqualTo(5);
        assertThat(Answers.answer(room, "host", pick.getId(), List.of("hd1", "hd2", "hd3", "hd4"), List.of())).isNotNull();
        Answers.answer(room, "host", pick.getId(), List.of("hd1", "hd2", "hd3", "hd4", "hd5"), List.of("hd7", "hd6"));
        assertThat(host.getDiscardPile()).extracting(CardSlot::getInstanceId).contains("hd1", "hd5");
        assertThat(host.getDiscardPile().stream().filter(c -> c.getInstanceId().startsWith("hd")).allMatch(CardSlot::isFaceDown)).isTrue();
        // The rest on top in the order given, then the draw takes the first one
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("hd7");
        assertThat(host.getDeck().get(0).getInstanceId()).isEqualTo("hd6");
    }

    @Test
    void mandatoImperialMakesTheOpponentMoveOneCreatureToTheFront() {
        CardSlot back = onField(guest, card("gb", "Atrás", 1, 1, "R"), BACK);
        host.getHand().add(utility("mi", "Mandato Imperial"));
        Summons.playByPlayer(room, "host", "mi", 50.0, BACK);
        Prompt pick = room.getPrompt();
        assertThat(pick.getPlayerId()).isEqualTo("guest");
        Answers.answer(room, "guest", pick.getId(), List.of("gb"), List.of());
        assertThat(EffectEngine.inFrontLine(back)).isTrue();
    }

    @Test
    void nexoTakesACardSharingTypeWithTheChosenAlly() {
        CardSlot ally = onField(host, card("al", "Aliado", 1, 1, "R"), FRONT);
        ally.setClasses(new ArrayList<>(List.of("caballero")));
        host.setDeck(deck("hd", 6));
        host.getDeck().get(2).setClasses(new ArrayList<>(List.of("Caballero")));
        host.getHand().add(utility("nx", "Nexo Del Inframundo Material"));
        Summons.playByPlayer(room, "host", "nx", 50.0, BACK);
        Answers.answer(room, "host", room.getPrompt().getId(), List.of("al"), List.of());
        Prompt take = room.getPrompt();
        assertThat(take.getValidIds()).containsExactly("hd3");
        Answers.answer(room, "host", take.getId(), List.of("hd3"), List.of());
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("hd3");
    }

    @Test
    void ultimaLineaGivesTheNextCombatDamageOneMoreHit() {
        for (int i = 0; i < 2; i++) host.getLifeStack().add(card("hl" + i, "Vida", 1, 1, "R"));
        for (int i = 0; i < 4; i++) {
            CardSlot life = card("gl" + i, "Vida", 1, 1, "R");
            life.setStrength(1);
            guest.getLifeStack().add(life);
        }
        CardSlot attacker = onField(host, card("at", "Atacante", 1, 1, "R"), FRONT);
        host.getHand().add(utility("ul", "Última Línea De Defensa"));
        Summons.playByPlayer(room, "host", "ul", 50.0, BACK);
        CombatRules.attackByPlayer(room, "host", attacker.getInstanceId(), null);
        while (room.getPrompt() != null) Answers.answer(room, room.getPrompt().getPlayerId(), room.getPrompt().getId(), List.of(), List.of());
        assertThat(guest.getLifeStack()).hasSize(2);
        assertThat(room.getUsedThisTurn()).contains(EffectEngine.END_TURN_REQUESTED + "host");
    }

    // ------------------------------------------------------------------ Repeated choices, costs from the hand, aliases

    /** Answers every pending choice of {@code player} with the first valid ids it wants, until there is none. */
    private void answerAll(String player, java.util.function.Function<Prompt, List<String>> picks) {
        for (int i = 0; i < 20 && room.getPrompt() != null && room.getPrompt().getPlayerId().equals(player); i++) {
            Prompt p = room.getPrompt();
            assertThat(Answers.answer(room, player, p.getId(), picks.apply(p), List.of())).isNull();
        }
    }

    @Test
    void sar2ReducesUpToTwiceTheSameEnemyUntilTheEndOfTheAttack() {
        CardSlot sar = onField(host, card("sar", "SAR 2", 1, 2, "B"), FRONT);
        sar.getMaterials().add(card("m1", "Material", 1, 1, "B"));
        CardSlot enemy = onField(guest, card("en", "Enemigo", 1, 1, "R"), FRONT);
        enemy.setStrength(5);
        CombatRules.attackByPlayer(room, "host", "sar", null);
        Answers.answer(room, "host", room.getPrompt().getId(), List.of("sar"), List.of());
        // Its cost: the material (the only one is chosen), then twice the same enemy
        answerAll("host", p -> p.getValidIds().contains("en") ? List.of("en") : p.getValidIds().subList(0, 1));
        assertThat(EffectEngine.strength(enemy)).isEqualTo(3);
        assertThat(room.getCombat().getUntilEndOfAttack()).hasSize(2);
    }

    @Test
    void rotoG0ReducesAsManyTimesAsMaterialsItTributed() {
        for (int i = 0; i < 3; i++) host.getHand().add(card("x" + i, "X", 1, 1, "B"));
        CardSlot roto = card("roto", "Roto G0", 1, 3, "B");
        roto.getMaterials().addAll(List.of(card("m1", "M", 1, 1, "B"), card("m2", "M", 1, 1, "B")));
        host.getHand().add(roto);
        CardSlot enemy = onField(guest, card("en", "Enemigo", 1, 1, "R"), FRONT);
        enemy.setStrength(9);
        summonInFront("roto");
        Answers.answer(room, "host", room.getPrompt().getId(), List.of("roto"), List.of());
        Answers.answer(room, "host", room.getPrompt().getId(), List.of("m1", "m2"), List.of());
        answerAll("host", p -> List.of("en"));
        assertThat(EffectEngine.strength(enemy)).isEqualTo(5);
    }

    @Test
    void imperialDiscardsTenTributesForEachLetalidad() {
        CardSlot imperial = onField(host, card("imp", "Imperial De La Llama Eterna", 1, 5, "R"), FRONT);
        for (int i = 0; i < 20; i++) host.getTributeZone().add(card("t" + i, "Tributo", 1, 1, "R"));
        onField(guest, card("en", "Enemigo", 1, 1, "R"), FRONT);
        CombatRules.attackByPlayer(room, "host", "imp", null);
        Answers.answer(room, "host", room.getPrompt().getId(), List.of("imp"), List.of());
        Prompt first = room.getPrompt();
        assertThat(Answers.answer(room, "host", first.getId(), first.getValidIds().subList(0, 3), List.of())).contains("exactamente 10");
        Answers.answer(room, "host", first.getId(), first.getValidIds().subList(0, 10), List.of());
        Prompt second = room.getPrompt();
        Answers.answer(room, "host", second.getId(), second.getValidIds().subList(0, 10), List.of());
        assertThat(EffectEngine.keywordLevel(imperial, "Letalidad")).isEqualTo(2);
        assertThat(host.getTributeZone()).isEmpty();
        assertThat(host.getDiscardPile()).hasSize(20);
    }

    @Test
    void danteIsSummonedFromTheHandTributingRedAlliesThatAddUpTo5() {
        onField(host, card("a1", "A", 1, 1, "R"), FRONT);
        onField(host, card("a2", "B", 1, 2, "R"), FRONT);
        onField(host, card("a3", "C", 1, 2, "R"), BACK);
        CardSlot dante = card("dan", "Dante Di Castirion", 6, 5, "R");
        host.getHand().add(dante);
        room.setSharedCounter(-10);
        assertThat(Chain.activatable(room, "host")).extracting(Chain.Activatable::scriptId).contains("dante-castirion:en-mano");
        assertThat(Chain.activateByPlayer(room, "host", "dan", null)).isNull();
        assertThat(Answers.answer(room, "host", room.getPrompt().getId(), List.of("a1", "a2"), List.of())).isNotNull();
        Answers.answer(room, "host", room.getPrompt().getId(), List.of("a1", "a2", "a3"), List.of());
        answerAll("host", p -> List.of());
        assertThat(host.getField()).containsExactly(dante);
        assertThat(EffectEngine.inFrontLine(dante)).isTrue();
        assertThat(host.getTributeZone()).hasSize(3);
    }

    @Test
    void janneInheritedPlacesAGreenStarterUntilTheEndOfTheTurn() {
        CardSlot dragon = onField(host, card("dr", "Dragón", 1, 2, "G"), FRONT);
        dragon.getResources().add(card("jan", "Janne", 1, 1, "G"));
        CardSlot starter = card("st", "Iniciador", 1, 1, "G");
        starter.setStarter(true);
        host.getHand().add(starter);
        host.getHand().add(card("other", "Otra", 1, 1, "G"));
        onField(guest, card("en", "Enemigo", 1, 1, "R"), FRONT);
        CombatRules.attackByPlayer(room, "host", "dr", null);
        Answers.answer(room, "host", room.getPrompt().getId(), List.of("dr"), List.of());
        Prompt place = room.getPrompt();
        assertThat(place.getValidIds()).containsExactly("st");
        Answers.answer(room, "host", place.getId(), List.of("st"), List.of());
        assertThat(host.getField()).contains(starter);
        assertThat(starter.isReturnAtTurnEnd()).isTrue();
        assertThat(EffectEngine.inFrontLine(starter)).isFalse();
    }

    @Test
    void necroporxSummonsAHigherLevelWithTwoCopiesInTheTributos() {
        host.getTributeZone().add(card("np2", "Necroporx", 1, 1, "P"));
        host.getTributeZone().add(card("np3", "Necroporx", 1, 1, "P"));
        CardSlot level2 = card("l2", "Violeta Dos", 1, 2, "P");
        host.getTributeZone().add(level2);
        CardSlot necro = onField(host, card("np", "Necroporx", 1, 1, "P"), FRONT);
        CardSlot killer = onField(guest, card("k", "Rata Ponsoñoza", 1, 1, "P"), FRONT);
        // Destroyed by an effect (it goes to the Descarte): two copies in the Tributos → level 2
        Removals.destroy(room, necro, host, false, EffectEngine.CAUSE_EFFECT, "guest", "k", null);
        Chain.advance(room);
        Answers.answer(room, "host", room.getPrompt().getId(), List.of("np"), List.of());
        Prompt summon = room.getPrompt();
        assertThat(summon.getValidIds()).containsExactly("l2");
        assertThat(killer).isNotNull();
    }

    @Test
    void caballeroDerrotadoCountsAsCalaveraErrante() {
        CardSlot c = card("cd", "Caballero Derrotado", 1, 1, "P");
        assertThat(CardFilter.any().named("Calavera Errante").matches(c)).isTrue();
        assertThat(CardFilter.any().named("Caballero Derrotado").matches(c)).isTrue();
        assertThat(CardFilter.any().named("Otra").matches(c)).isFalse();
    }

    @Test
    void tributoDelBosqueExhaustsAnEnemyWhenItIsRemovedFromTheLives() {
        CardSlot tributo = card("tb", "Tributo Del Bosque", 1, 1, "G");
        tributo.setCardType("utility");
        guest.getLifeStack().add(tributo);
        guest.getLifeStack().add(card("gl", "Vida", 1, 1, "R"));
        CardSlot attacker = onField(host, card("at", "Atacante", 1, 1, "R"), FRONT);
        attacker.setStrength(8);
        CombatRules.attackByPlayer(room, "host", "at", null);
        // The guest: the revealed utility is not played; then its trigger exhausts the attacker (strength 8)
        for (int i = 0; i < 6 && room.getPrompt() != null; i++) {
            Prompt p = room.getPrompt();
            List<String> pick = p.getValidIds().contains("at") ? List.of("at") : List.of();
            Answers.answer(room, p.getPlayerId(), p.getId(), pick, List.of());
        }
        assertThat(guest.getHand()).contains(tributo);
        assertThat(attacker.isTapped()).isTrue();
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.EFFECT_ACTIVATED.equals(e.getType()) && "Vidas".equals(e.getData().get("label")));
    }

    @Test
    void fervorDeGuerraCanBePlayedFromTheHandWhenTheBlockStepStarts() {
        CardSlot fervor = card("fv", "Fervor De Guerra", 1, 1, "R");
        fervor.setCardType("utility");
        guest.getHand().add(fervor);
        guest.setResourceCount(5);
        onField(guest, card("bl", "Bloqueador", 1, 1, "R"), FRONT);
        onField(host, card("at", "Atacante", 1, 1, "R"), FRONT);
        CombatRules.attackByPlayer(room, "host", "at", null);
        for (int i = 0; i < 6 && room.getPrompt() != null; i++) {
            Prompt p = room.getPrompt();
            if (p.getEffects() != null && p.getEffects().stream().anyMatch(e -> e.sourceId().equals("fv"))) {
                assertThat(p.getPlayerId()).isEqualTo("guest");
                return;
            }
            Answers.answer(room, p.getPlayerId(), p.getId(), List.of(), List.of());
        }
        throw new AssertionError("Fervor De Guerra was not offered at the block step");
    }

    @Test
    void sobrecargaLetsAMechaActivateTheSummonEffectOfTheTributedMaterial() {
        CardSlot mecha = onField(host, card("me", "Mecha", 1, 4, "B"), FRONT);
        mecha.setClasses(new ArrayList<>(List.of("mecha")));
        CardSlot filirem = card("fil", "Filirem", 1, 2, "B");
        mecha.getMaterials().add(filirem);
        CardSlot enemy = onField(guest, card("en", "Enemigo", 1, 1, "R"), FRONT);
        enemy.setStrength(5);
        CardSlot sobrecarga = utility("so", "Sobrecarga");
        host.getHand().add(sobrecarga);
        Summons.playByPlayer(room, "host", "so", 50.0, BACK);
        answerAll("host", p -> p.getValidIds().contains("fil") ? List.of("fil") : p.getValidIds().contains("en") ? List.of("en") : List.of());
        assertThat(mecha.getMaterials()).contains(sobrecarga);
        assertThat(host.getTributeZone()).contains(filirem);
        assertThat(guest.getField()).doesNotContain(enemy);
    }

    // ------------------------------------------------------------------ Replacements ("en su lugar", Protección)

    /** A guest creature destroys {@code target} by effect (as "elige 1 enemigo; destrúyelo" would). */
    private void destroyByGuest(CardSlot target) {
        Removals.destroy(room, target, host, false, EffectEngine.CAUSE_EFFECT, "guest", "x", null);
        Chain.advance(room);
    }

    @Test
    void protoVigilanteTributesTwoMaterialsInsteadOfBeingDestroyed() {
        CardSlot proto = onField(host, card("pv", "Proto Vigilante H2", 1, 3, "B"), FRONT);
        proto.getMaterials().addAll(List.of(card("m1", "M", 1, 1, "B"), card("m2", "M", 1, 1, "B"), card("m3", "M", 1, 1, "B")));
        destroyByGuest(proto);
        Prompt ask = room.getPrompt();
        assertThat(ask.getPurpose()).isEqualTo("REPLACE");
        assertThat(ask.getPlayerId()).isEqualTo("host");
        Answers.answer(room, "host", ask.getId(), List.of(ask.getEffects().get(0).effectId()), List.of());
        assertThat(host.getField()).contains(proto);
        assertThat(proto.getMaterials()).hasSize(1);
        assertThat(host.getTributeZone()).hasSize(2);
    }

    @Test
    void declinedTheReplacementTheCardIsDestroyed() {
        CardSlot proto = onField(host, card("pv", "Proto Vigilante H2", 1, 3, "B"), FRONT);
        proto.getMaterials().addAll(List.of(card("m1", "M", 1, 1, "B"), card("m2", "M", 1, 1, "B")));
        destroyByGuest(proto);
        Answers.answer(room, "host", room.getPrompt().getId(), List.of(), List.of());
        assertThat(host.getField()).doesNotContain(proto);
        assertThat(host.getDiscardPile()).contains(proto);
        assertThat(room.getPendingRemovals()).isEmpty();
    }

    @Test
    void caballeroLealTributesAHandCardOncePerTurn() {
        CardSlot leal = onField(host, card("cl", "Caballero Leal", 1, 2, "R"), FRONT);
        host.getHand().addAll(List.of(card("h1", "Uno", 1, 1, "R"), card("h2", "Dos", 1, 1, "R")));
        destroyByGuest(leal);
        Answers.answer(room, "host", room.getPrompt().getId(), List.of(room.getPrompt().getEffects().get(0).effectId()), List.of());
        Prompt hand = room.getPrompt();
        assertThat(hand.getPurpose()).isEqualTo("REPLACE_HAND");
        Answers.answer(room, "host", hand.getId(), List.of("h2"), List.of());
        assertThat(host.getField()).contains(leal);
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).containsExactly("h2");
        // Once per turn: the second time it is destroyed
        destroyByGuest(leal);
        assertThat(room.getPrompt()).isNull();
        assertThat(host.getDiscardPile()).contains(leal);
    }

    @Test
    void deriduragonInheritedOnlyAgainstTheOpponent() {
        CardSlot dragon = onField(host, card("dr", "Dragón", 1, 3, "G"), FRONT);
        dragon.getResources().add(card("der", "Deriduragon", 1, 2, "G"));
        // By its own player: nothing to replace
        Removals.destroy(room, dragon, host, false, EffectEngine.CAUSE_EFFECT, "host", "x", null);
        assertThat(room.getPrompt()).isNull();
        assertThat(host.getDiscardPile()).contains(dragon);
    }

    @Test
    void hydraInheritedTributesTheHydraResources() {
        CardSlot dragon = onField(host, card("dr", "Dragón", 1, 3, "G"), FRONT);
        CardSlot hydra = card("hy", "Hydra Colosal", 1, 4, "G");
        hydra.setClasses(new ArrayList<>(List.of("dragón", "hydra")));
        CardSlot other = card("ot", "Otro", 1, 1, "G");
        dragon.getResources().addAll(List.of(hydra, other));
        destroyByGuest(dragon);
        Answers.answer(room, "host", room.getPrompt().getId(), List.of(room.getPrompt().getEffects().get(0).effectId()), List.of());
        assertThat(host.getField()).contains(dragon);
        assertThat(dragon.getResources()).containsExactly(other);
        assertThat(host.getTributeZone()).contains(hydra);
    }

    @Test
    void escudoDePrimeraLineaProtectsACaballeroDestroyingItselfInstead() {
        CardSlot escudo = onField(host, card("es", "Escudo De Primera Línea", 1, 1, "R"), FRONT);
        escudo.setClasses(new ArrayList<>(List.of("caballero")));
        CardSlot knight = onField(host, card("kn", "Caballero", 1, 2, "R"), FRONT);
        knight.setClasses(new ArrayList<>(List.of("caballero")));
        onField(host, card("r3", "Rojo", 1, 1, "R"), FRONT);
        // Turno Oponente, with 3 Red allies: it has Protección [caballero]
        room.setCurrentTurnUserId("guest");
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.keywords(escudo)).contains("Protección [caballero]");
        destroyByGuest(knight);
        Prompt ask = room.getPrompt();
        Prompt.OfferedEffect protect = ask.getEffects().stream().filter(e -> e.sourceId().equals("es")).findFirst().orElseThrow();
        Answers.answer(room, "host", ask.getId(), List.of(protect.effectId()), List.of());
        assertThat(host.getField()).contains(knight).doesNotContain(escudo);
        assertThat(host.getDiscardPile()).contains(escudo);
    }

    // ------------------------------------------------------------------ Helpers

    /**
     * A creature of the hand summoned straight into the Línea frontal (as by a special summon): a normal summon
     * enters the Retaguardia, from where its effects cannot affect enemies.
     */
    private void summonInFront(String id) {
        CardSlot card = host.getHand().stream().filter(c -> c.getInstanceId().equals(id)).findFirst().orElseThrow();
        host.getHand().remove(card);
        onField(host, card, FRONT);
        Summons.specialSummoned(room, "host", card, null, "special", null);
    }

}
