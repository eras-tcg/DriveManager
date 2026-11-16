package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Special summon Requerimientos programmed by hand (the card file's `requirement` section, RequirementSpec): the engine uses them and not the
 * printed text, in the four ways. One test (or more) per card programmed on 2026-11-16, plus the section itself.
 */
class ProgrammedRequirementTest extends CardBatchSupport {

    private static CardSlot special(CardSlot card, String kind, int cost, String requirement) {
        card.setSpecialSummonKind(kind);
        card.setSpecialCost(cost);
        card.setRequirement(requirement);
        return card;
    }

    private CardSlot ascenso(String id, String name, int level, String requirement) {
        CardSlot c = special(card(id, name, 3, level + 1, "R"), "promotion", 1, requirement);
        host.getHand().add(c);
        return c;
    }

    // ------------------------------------------------------------------ The section

    @Test
    void theProgrammedRequirementIsUsedInsteadOfTheText() {
        // The text would allow any level-1 summoner; the file asks for 2 creatures as well
        CardSlot apoyo = ascenso("ap", "Caballero De Apoyo", 1, "criatura nivel 1.");
        onField(host, card("s1", "Nivel 1", 1, 1, "R"), FRONT);
        assertThat(CardEffects.requirementOf(apoyo)).isNotNull();
        assertThat(SpecialRequirements.canSpecial(room, "host", apoyo)).isFalse();
    }

    @Test
    void aCardWithoutTheSectionIsNeverSummonedFromItsText() {
        // The text alone is never read: without its section the card cannot be special summoned, and counts as not programmed
        CardSlot plain = ascenso("pl", "Ascendido Sin Archivo", 1, "criatura nivel 1.");
        onField(host, card("s1", "Nivel 1", 1, 1, "R"), FRONT);
        assertThat(CardEffects.requirementOf(plain)).isNull();
        assertThat(SpecialRequirements.canSpecial(room, "host", plain)).isFalse();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "pl", "s1")).contains("Requerimiento").contains("programado");
        assertThat(CardEffects.pending("Ascendido Sin Archivo", 0, 0, 0, List.of(), "promotion")).contains("Requerimiento de invocación especial");
        assertThat(CardEffects.pending("Caballero De Apoyo", 0, 0, 0, List.of(), "promotion")).isNull();
    }

    @Test
    void criaDeDragonRunicoEvolvesOnlyFromACreatureWithoutResources() {
        CardSlot cria = withClasses(special(card("cr", "Cría De Dragón Rúnico", 2, 2, "G"), "evolution", 1, "criatura nivel 1 sin recursos."), "dragón");
        host.getHand().add(cria);
        CardSlot l1 = onField(host, withClasses(card("l1", "Nivel 1", 1, 1, "G"), "dragón"), FRONT);
        l1.getResources().add(card("rs", "Recurso", 1, 1, "G"));
        assertThat(SpecialRequirements.canSpecial(room, "host", cria)).isFalse();
        l1.getResources().clear();
        assertThat(SpecialRequirements.canSpecial(room, "host", cria)).isTrue();
    }

    @Test
    void falanxStillTakesAlliesAddingUpToSixWithTheSummoner() {
        CardSlot falanx = withClasses(special(card("fx", "Falanx", 5, 5, "B"), "materialization", 2,
                "aliados que sumen 6 en nivel, incluido el invocar nivel 3 o mayor."), "ente");
        host.getHand().add(falanx);
        onField(host, withClasses(card("s3", "Tres", 3, 3, "B"), "ente"), FRONT);
        onField(host, withClasses(card("a2", "Dos", 2, 2, "B"), "ente"), BACK);
        assertThat(SpecialRequirements.canSpecial(room, "host", falanx)).isFalse();
        onField(host, withClasses(card("a1", "Uno", 1, 1, "B"), "ente"), BACK);
        assertThat(SpecialRequirements.canSpecial(room, "host", falanx)).isTrue();
    }

    // ------------------------------------------------------------------ Open doubts (designer): no section until answered

    @Test
    void theCardsWaitingForTheDesignerHaveNoRequirementAndCannotBeSpecialSummoned() {
        for (String name : List.of("Fausto", "Samael el Fuego de la Destrucción", "Uriel el Fuego de la Esperanza", "Apostol De La Infamia")) {
            CardSlot c = card("x", name, 3, 3, "B");
            assertThat(CardEffects.requirementOf(c)).as(name).isNull();
        }
        CardSlot fausto = withClasses(special(card("fa", "Fausto", 4, 3, "B"), "materialization", 2, "2 [cinerianos] nivel 2 o menor."),
                "cineriano");
        host.getHand().add(fausto);
        onField(host, withClasses(card("c1", "Cineriano 1", 1, 1, "B"), "cineriano"), FRONT);
        host.getTributeZone().add(withClasses(card("c2", "Cineriano 2", 2, 2, "B"), "cineriano"));
        assertThat(SpecialRequirements.canSpecial(room, "host", fausto)).isFalse();
        assertThat(CardEffects.pending("Fausto", 0, 0, 0, List.of(), "materialization")).contains("Requerimiento de invocación especial");
    }

    // ------------------------------------------------------------------ Ascenso: "mientras controlas N o más …"

    @Test
    void caballeroDeApoyoAscendsOnlyWhileYouControlTwoCreatures() {
        CardSlot apoyo = ascenso("ap", "Caballero De Apoyo", 1, "criatura nivel 1 mientras controlas 2 o más criaturas.");
        CardSlot summoner = onField(host, card("s1", "Nivel 1", 1, 1, "R"), FRONT);
        assertThat(SpecialRequirements.canSpecial(room, "host", apoyo)).isFalse();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "ap", "s1")).contains("controlar 2 o más criaturas");
        onField(host, card("o1", "Otra", 1, 3, "R"), BACK);
        assertThat(SpecialRequirements.canSpecial(room, "host", apoyo)).isTrue();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "ap", "s1")).isNull();
        drain();
        assertThat(host.getField()).contains(apoyo).doesNotContain(summoner);
    }

    @Test
    void sargentoLegionarioNeedsThreeCreatures() {
        CardSlot sargento = ascenso("sg", "Sargento Legionario", 1, "criatura nivel 1 mientras controlas 3 o más ciraturas.");
        onField(host, card("s1", "Nivel 1", 1, 1, "R"), FRONT);
        onField(host, card("o1", "Otra", 1, 3, "R"), BACK);
        assertThat(SpecialRequirements.canSpecial(room, "host", sargento)).isFalse();
        onField(host, card("o2", "Otra 2", 1, 3, "R"), BACK);
        assertThat(SpecialRequirements.canSpecial(room, "host", sargento)).isTrue();
    }

    @Test
    void johanVexCountsOnlyTheCreaturesOfYourFrontLine() {
        CardSlot johan = ascenso("jv", "Johan Vex", 2, "criatura nivel 2, mientras controlas al menos 2 criaturas en tu línea frontal.");
        onField(host, card("s2", "Nivel 2", 2, 2, "R"), FRONT);
        CardSlot back = onField(host, card("o1", "Otra", 1, 1, "R"), BACK);
        assertThat(SpecialRequirements.canSpecial(room, "host", johan)).isFalse();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "jv", "s2")).contains("en tu línea frontal");
        back.setY(FRONT);
        assertThat(SpecialRequirements.canSpecial(room, "host", johan)).isTrue();
    }

    @Test
    void elViejoJackCountsPiratas() {
        CardSlot jack = ascenso("vj", "El Viejo Jack", 1, "criatura nivel 1 mientras controlas 3 o más [piratas].");
        onField(host, withClasses(card("s1", "Pirata 1", 1, 1, "R"), "pirata"), FRONT);
        onField(host, withClasses(card("p2", "Pirata 2", 1, 1, "R"), "pirata"), BACK);
        onField(host, card("x", "No pirata", 1, 1, "R"), BACK);
        assertThat(SpecialRequirements.canSpecial(room, "host", jack)).isFalse();
        onField(host, withClasses(card("p3", "Pirata 3", 1, 1, "R"), "pirata"), BACK);
        assertThat(SpecialRequirements.canSpecial(room, "host", jack)).isTrue();
    }

    @Test
    void elViejoJackIsNotOfferedByAnEffectWhileTheConditionFails() {
        CardSlot jack = ascenso("vj", "El Viejo Jack", 1, "criatura nivel 1 mientras controlas 3 o más [piratas].");
        onField(host, withClasses(card("s1", "Pirata 1", 1, 1, "R"), "pirata"), FRONT);
        assertThat(SpecialRequirements.specialSummonable(room, "host")).doesNotContain("vj");
        onField(host, withClasses(card("p2", "Pirata 2", 1, 1, "R"), "pirata"), BACK);
        onField(host, withClasses(card("p3", "Pirata 3", 1, 1, "R"), "pirata"), BACK);
        assertThat(SpecialRequirements.specialSummonable(room, "host")).contains("vj");
        assertThat(jack.getSpecialSummonKind()).isEqualTo("promotion");
    }

    // ------------------------------------------------------------------ Ascenso: "criatura en tu mano mientras controlas …"
    // Designer 2026-11-16: the summoner is a creature of your hand, of any level, tributed; the condition is met by your field; the card
    // enters the Retaguardia.

    private CardSlot shogun(String id, String name, int level, String requirement, String... classes) {
        CardSlot c = withClasses(special(card(id, name, 3, level, "R"), "promotion", 1, requirement), classes);
        host.getHand().add(c);
        return c;
    }

    @Test
    void mitsukageAscendsFromACreatureOfTheHandWhileYouControlTwoNinjas() {
        CardSlot mitsukage = shogun("mk", "Mitsukage", 3, "criatura en tu mano mientras controlas 2 o más [ninjas].", "shogún", "ninja");
        CardSlot fromHand = withClasses(card("hd", "De la mano", 4, 4, "R"), "samurái");
        host.getHand().add(fromHand);
        CardSlot n1 = onField(host, withClasses(card("n1", "Ninja 1", 1, 1, "R"), "ninja"), FRONT);
        assertThat(SpecialRequirements.canSpecial(room, "host", mitsukage)).isFalse();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "mk", null)).contains("controlar 2 o más [ninjas]");
        CardSlot n2 = onField(host, withClasses(card("n2", "Ninja 2", 2, 2, "R"), "ninja"), BACK);
        assertThat(SpecialRequirements.canSpecial(room, "host", mitsukage)).isTrue();
        int before = gems();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "mk", null)).isNull();
        // Only the creature of the hand (any level) can be the summoner, not the ninjas of the field
        assertThat(room.getPrompt().getValidIds()).containsExactly("hd");
        answer("hd");
        drain();
        assertThat(host.getField()).contains(mitsukage, n1, n2);
        assertThat(EffectEngine.inFrontLine(mitsukage)).isFalse();
        assertThat(host.getTributeZone()).contains(fromHand);
        assertThat(host.getHand()).doesNotContain(fromHand, mitsukage);
        assertThat(gems()).isEqualTo(before - 1);
    }

    @Test
    void korokageNeedsThreeNinjas() {
        CardSlot korokage = shogun("ko", "Korokage", 4, "criatura en tu mano, mientras controlas 3 o más [ninjas].", "shogún", "ninja", "oni");
        host.getHand().add(card("hd", "De la mano", 1, 1, "R"));
        onField(host, withClasses(card("n1", "Ninja 1", 1, 1, "R"), "ninja"), FRONT);
        onField(host, withClasses(card("n2", "Ninja 2", 1, 1, "R"), "ninja"), FRONT);
        assertThat(SpecialRequirements.canSpecial(room, "host", korokage)).isFalse();
        onField(host, withClasses(card("n3", "Ninja 3", 1, 1, "R"), "ninja"), BACK);
        assertThat(SpecialRequirements.canSpecial(room, "host", korokage)).isTrue();
    }

    @Test
    void takedaNeedsSomeLevelOneCreatureOnTheFieldAndAnotherCreatureInTheHand() {
        CardSlot takeda = shogun("tk", "Takeda", 3, "criatura en tu mano mientras controlas alguna criatura nivel 1.", "shogún", "samurái");
        onField(host, card("l2", "Nivel 2", 2, 2, "R"), FRONT);
        host.getHand().add(card("hd", "De la mano", 3, 3, "R"));
        assertThat(SpecialRequirements.canSpecial(room, "host", takeda)).isFalse();
        onField(host, card("l1", "Nivel 1", 1, 1, "R"), BACK);
        assertThat(SpecialRequirements.canSpecial(room, "host", takeda)).isTrue();
        // Without another creature in the hand there is no summoner
        host.getHand().removeIf(c -> "hd".equals(c.getInstanceId()));
        assertThat(SpecialRequirements.canSpecial(room, "host", takeda)).isFalse();
    }

    @Test
    void shinkenNoTenshiTakesALevelThreeOrFourOnTheField() {
        CardSlot shinken = shogun("sh", "Shinken No Tenshi", 5, "criatura en tu mano mientras controlas alguna criatura nivel 3 o 4.", "shogún");
        host.getHand().add(card("hd", "De la mano", 1, 1, "R"));
        onField(host, card("l5", "Nivel 5", 5, 5, "R"), FRONT);
        assertThat(SpecialRequirements.canSpecial(room, "host", shinken)).isFalse();
        onField(host, card("l4", "Nivel 4", 4, 4, "R"), FRONT);
        assertThat(SpecialRequirements.canSpecial(room, "host", shinken)).isTrue();
    }

    @Test
    void petaloNeedsALevelOneShogun() {
        CardSlot petalo = shogun("pe", "Pétalo de la Luna de Plata", 2, "criatura en tu mano, mientras controlas algún [shogún] nivel 1.",
                "shogún", "ninja");
        host.getHand().add(card("hd", "De la mano", 1, 1, "R"));
        onField(host, card("l1", "Nivel 1", 1, 1, "R"), FRONT);
        assertThat(SpecialRequirements.canSpecial(room, "host", petalo)).isFalse();
        onField(host, withClasses(card("sg", "Shogún 1", 1, 1, "R"), "shogún"), FRONT);
        assertThat(SpecialRequirements.canSpecial(room, "host", petalo)).isTrue();
    }

    // ------------------------------------------------------------------ Evolución: classes of the summoner

    @Test
    void escuadraDeLanzasAereasEvolvesOnlyFromADragonOrFaunaOfLevelTwo() {
        CardSlot escuadra = withClasses(special(card("es", "Escuadra de Lanzas Aereas", 4, 3, "G"), "evolution", 1, "[dragón] o [fauna] nivel 2."),
                "paladín");
        host.getHand().add(escuadra);
        onField(host, withClasses(card("pl", "Planta", 2, 2, "G"), "planta"), FRONT);
        assertThat(SpecialRequirements.canSpecial(room, "host", escuadra)).isFalse();
        CardSlot fauna = onField(host, withClasses(card("fa", "Fauna", 2, 2, "G"), "fauna"), FRONT);
        assertThat(SpecialRequirements.canSpecial(room, "host", escuadra)).isTrue();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "es", "pl")).isNotNull();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "es", "fa")).isNull();
        drain();
        assertThat(escuadra.getResources()).contains(fauna);
    }

    // ------------------------------------------------------------------ Materialización

    private CardSlot materialization(String id, String name, int level, String requirement, String... classes) {
        CardSlot c = withClasses(special(card(id, name, 3, level, "B"), "materialization", 1, requirement), classes);
        host.getHand().add(c);
        return c;
    }

    @Test
    void arthurCollierTakesAnArtefactoOfTheFieldAsMaterial() {
        CardSlot arthur = materialization("ac", "Arthur Collier", 3, "criatura nivel 1 + criatura [artefacto] en el campo.", "cineriano");
        onField(host, withClasses(card("s1", "Invocador", 1, 1, "B"), "cineriano"), FRONT);
        onField(host, withClasses(card("no", "Sin clase", 2, 3, "B"), "cineriano"), BACK);
        host.getTributeZone().add(withClasses(card("tr", "Artefacto en tributos", 1, 1, "B"), "artefacto"));
        assertThat(SpecialRequirements.canSpecial(room, "host", arthur)).isFalse();
        CardSlot art = onField(host, withClasses(card("ar", "Artefacto", 1, 3, "B"), "artefacto"), BACK);
        assertThat(SpecialRequirements.canSpecial(room, "host", arthur)).isTrue();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "ac", "s1")).isNull();
        assertThat(room.getPrompt().getValidIds()).containsExactly("ar");
        answer("ar");
        drain();
        assertThat(arthur.getMaterials()).contains(art);
    }

    @Test
    void helenaTakesOneOrMoreArtefactos() {
        CardSlot helena = materialization("he", "Helena la Pirata de Cineria", 4, "criatura nivel 2 + 1 o más criaturas [artefacto] en el campo.",
                "cineriano");
        onField(host, withClasses(card("s2", "Invocador", 2, 2, "B"), "cineriano"), FRONT);
        CardSlot a1 = onField(host, withClasses(card("a1", "Artefacto 1", 1, 1, "B"), "artefacto"), BACK);
        CardSlot a2 = onField(host, withClasses(card("a2", "Artefacto 2", 1, 2, "B"), "artefacto"), BACK);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "he", "s2")).isNull();
        assertThat(room.getPrompt().getMax()).isEqualTo(2);
        answer("a1", "a2");
        drain();
        assertThat(helena.getMaterials()).contains(a1, a2);
    }

    @Test
    void falcoTakesAnArtefactoUtilityFromTheHandOrTributos() {
        CardSlot falco = materialization("fa", "Falco el Buscador de Tesoros", 2, "criatura nivel 1 + material utilidad [artefacto] en mano o tributos.",
                "cineriano");
        onField(host, withClasses(card("s1", "Invocador", 1, 1, "B"), "cineriano"), FRONT);
        host.getHand().add(withClasses(card("cr", "Criatura artefacto", 1, 1, "B"), "artefacto"));
        assertThat(SpecialRequirements.canSpecial(room, "host", falco)).isFalse();
        CardSlot tool = withClasses(utility("ut", "Herramienta", 1, "B"), "artefacto");
        host.getTributeZone().add(tool);
        assertThat(SpecialRequirements.canSpecial(room, "host", falco)).isTrue();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "fa", "s1")).isNull();
        assertThat(room.getPrompt().getValidIds()).containsExactly("ut");
    }

    @Test
    void doctorNuliberTakesAMaterialOfLevelTwoOrLess() {
        CardSlot doctor = materialization("dn", "Doctor Nuliber", 3, "criatura nivel 1 + material nivel 2 o menor en mano o descarte.", "necrómata");
        onField(host, withClasses(card("s1", "Invocador", 1, 1, "B"), "necrómata"), FRONT);
        host.getHand().add(card("l3", "Nivel 3", 3, 3, "B"));
        assertThat(SpecialRequirements.canSpecial(room, "host", doctor)).isFalse();
        host.getDiscardPile().add(card("l1", "Nivel 1", 1, 1, "B"));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "dn", "s1")).isNull();
        assertThat(room.getPrompt().getValidIds()).containsExactly("l1");
    }

    @Test
    void unidadHeroicaDeclaresASummonerOfLevelTwoOrLessFromTheHand() {
        CardSlot unidad = materialization("uh", "Unidad Heróica V10-L3T", 3, "criatura nivel 2 o menor en mano + material en mano.", "neomáquina");
        CardSlot big = card("l3", "Nivel 3", 3, 3, "B");
        host.getHand().add(big);
        CardSlot l1 = card("l1", "Nivel 1", 1, 1, "B");
        host.getHand().add(l1);
        assertThat(SpecialRequirements.canSpecial(room, "host", unidad)).isTrue();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "uh", null)).isNull();
        // Only the level-1 one can be the summoner; the level-3 one may be its material
        assertThat(room.getPrompt().getValidIds()).containsExactly("l1");
        answer("l1");
        assertThat(room.getPrompt().getValidIds()).containsExactly("l3");
        answer("l3");
        drain();
        assertThat(host.getField()).contains(unidad);
        assertThat(unidad.getMaterials()).contains(l1, big);
    }

    @Test
    void corredorA1001CannotUseACopyOfItselfAsSummoner() {
        CardSlot corredor = materialization("ca", "Corredor A1001", 1, "criatura nivel 1 que no sea 1 copia de esta carta.", "autómata");
        onField(host, withClasses(card("c2", "Corredor A1001", 1, 1, "B"), "autómata"), FRONT);
        assertThat(SpecialRequirements.canSpecial(room, "host", corredor)).isFalse();
        onField(host, withClasses(card("ot", "Otro autómata", 1, 1, "B"), "autómata"), FRONT);
        assertThat(SpecialRequirements.canSpecial(room, "host", corredor)).isTrue();
    }

    // ------------------------------------------------------------------ Ritual

    private CardSlot ritual(String id, String name, String requirement) {
        CardSlot c = withClasses(special(card(id, name, 5, 4, "P"), "ritual", 1, requirement), "espectro");
        host.getHand().add(c);
        return c;
    }

    @Test
    void amalgamaCadavericaSacrificesCreaturesAddingUpToThreeOrFour() {
        CardSlot amalgama = ritual("am", "Amalgama Cadavérica", "criaturas que sumen entre 3 y 4 en nivel.");
        CardSlot a = onField(host, card("a", "Dos", 2, 2, "P"), FRONT);
        CardSlot b = onField(host, card("b", "Uno", 1, 1, "P"), BACK);
        assertThat(SpecialRequirements.canSpecial(room, "host", amalgama)).isTrue();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "am", "a")).isNull();
        answer("b");
        drain();
        assertThat(host.getField()).contains(amalgama).doesNotContain(a, b);
    }

    @Test
    void amalgamaCadavericaAlsoTakesFourAndNotFive() {
        CardSlot amalgama = ritual("am", "Amalgama Cadavérica", "criaturas que sumen entre 3 y 4 en nivel.");
        onField(host, card("a", "Tres", 3, 3, "P"), FRONT);
        onField(host, card("b", "Dos", 2, 2, "P"), BACK);
        // 3 alone, or nothing adds up to 4 (3 + 2 = 5): the summoner alone is enough
        assertThat(SpecialRequirements.canSpecial(room, "host", amalgama)).isTrue();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "am", "b")).isNotNull();
        onField(host, card("c", "Dos bis", 2, 2, "P"), BACK);
        // 2 + 2 = 4
        assertThat(SpecialSummons.specialByPlayer(room, "host", "am", "b")).isNull();
        assertThat(room.getPrompt().getValidIds()).contains("c").doesNotContain("a");
    }

    @Test
    void cadaverCorruptoAcceptsFourAsWell() {
        CardSlot cadaver = ritual("cc", "Cadaver Corrupto", "criaturas que sumen 3 o 4 en nivel.");
        onField(host, card("a", "Dos", 2, 2, "P"), FRONT);
        onField(host, card("b", "Dos bis", 2, 2, "P"), BACK);
        assertThat(SpecialRequirements.canSpecial(room, "host", cadaver)).isTrue();
    }

    @Test
    void vigilanteCadavericoUsesCreaturesOfLevelThreeOrLessAddingUpToFour() {
        CardSlot vigilante = ritual("vc", "Vigilante Cadavérico", "criaturas nivel 3 o menor que sumen 4 en nivel.");
        onField(host, card("l4", "Cuatro", 4, 4, "P"), FRONT);
        assertThat(SpecialRequirements.canSpecial(room, "host", vigilante)).isFalse();
        onField(host, card("l3", "Tres", 3, 3, "P"), FRONT);
        onField(host, card("l1", "Uno", 1, 1, "P"), BACK);
        assertThat(SpecialRequirements.canSpecial(room, "host", vigilante)).isTrue();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "vc", "l4")).isNotNull();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "vc", "l3")).isNull();
        assertThat(room.getPrompt().getValidIds()).containsExactly("l1");
    }

    @Test
    void theDefinitionKeepsTheRequirement() {
        CardDefinition def = CardEffects.definitions().get("el viejo jack");
        assertThat(def).isNotNull();
        assertThat(def.requirement()).isNotNull();
        assertThat(List.of(def.requirement().summoner().level())).containsExactly(1);
    }
}
