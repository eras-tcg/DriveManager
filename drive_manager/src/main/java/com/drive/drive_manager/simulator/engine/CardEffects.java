package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.CostModifiers.CostModifier;
import com.drive.drive_manager.simulator.engine.CostModifiers.Payment;
import com.drive.drive_manager.simulator.engine.EffectScript.Limit;
import com.drive.drive_manager.simulator.engine.EffectScript.Moment;
import com.drive.drive_manager.simulator.engine.EffectStep.AttachLowerFromTributes;
import com.drive.drive_manager.simulator.engine.EffectStep.DeclareAttack;
import com.drive.drive_manager.simulator.engine.EffectStep.IfTributed;
import com.drive.drive_manager.simulator.engine.EffectStep.KeepOnField;
import com.drive.drive_manager.simulator.engine.EffectStep.SummonLikeSubject;
import com.drive.drive_manager.simulator.engine.EffectStep.AttachSubjectToSummoned;
import com.drive.drive_manager.simulator.engine.EffectStep.DestroyFaceUpLife;
import com.drive.drive_manager.simulator.engine.EffectStep.ActivateTributedSummonEffect;
import com.drive.drive_manager.simulator.engine.EffectStep.TributeAllyMaterialWhere;
import com.drive.drive_manager.simulator.engine.EffectStep.FilterBy;
import com.drive.drive_manager.simulator.engine.EffectStep.PickAllyUpToSacrificedLevel;
import com.drive.drive_manager.simulator.engine.EffectStep.PickAllyWithHigherAlly;
import com.drive.drive_manager.simulator.engine.EffectStep.AttachSubjectToHigherAlly;
import com.drive.drive_manager.simulator.engine.EffectStep.PickAllyWithMaterials;
import com.drive.drive_manager.simulator.engine.EffectStep.ExtraHit;
import com.drive.drive_manager.simulator.engine.EffectStep.MayDo;
import com.drive.drive_manager.simulator.engine.EffectStep.ActivateSubjectSummonEffect;
import com.drive.drive_manager.simulator.engine.EffectStep.AlliesGain;
import com.drive.drive_manager.simulator.engine.EffectStep.SkipBlockStep;
import com.drive.drive_manager.simulator.engine.EffectStep.IfChosenGone;
import static com.drive.drive_manager.simulator.engine.Compose.*;
import com.drive.drive_manager.simulator.engine.EffectStep.SpecialFromZone;
import com.drive.drive_manager.simulator.engine.EffectStep.PickAlly;
import com.drive.drive_manager.simulator.engine.EffectStep.PickAllyWhere;
import com.drive.drive_manager.simulator.engine.EffectStep.EndYourTurn;
import com.drive.drive_manager.simulator.engine.EffectStep.IfCondition;
import com.drive.drive_manager.simulator.engine.EffectStep.LookedToTopOrBottom;
import com.drive.drive_manager.simulator.engine.EffectStep.SubjectIsThisCard;
import com.drive.drive_manager.simulator.engine.EffectStep.PlaceFromHandUntilTurnEnd;
import com.drive.drive_manager.simulator.engine.EffectStep.TributeAlliesLevelSum;
import com.drive.drive_manager.simulator.engine.EffectStep.TributesToDeckBottomForKeyword;
import com.drive.drive_manager.simulator.engine.EffectStep.ReduceRepeatedly;
import com.drive.drive_manager.simulator.engine.EffectStep.HandsToBottomDrawOpponentLives;
import com.drive.drive_manager.simulator.engine.EffectStep.OpponentMovesOneToFront;
import com.drive.drive_manager.simulator.engine.EffectStep.ExtraCombatHitThenEndTurn;
import com.drive.drive_manager.simulator.engine.EffectStep.TakeFromLookedLikeSubject;
import com.drive.drive_manager.simulator.engine.EffectStep.SummonFreeLevelSum;
import com.drive.drive_manager.simulator.engine.EffectStep.IfDone;
import com.drive.drive_manager.simulator.engine.EffectStep.IfPossible;
import com.drive.drive_manager.simulator.engine.EffectStep.RitualFromZone;
import com.drive.drive_manager.simulator.engine.EffectStep.BounceChosen;
import com.drive.drive_manager.simulator.engine.EffectStep.ChooseWeakestEnemy;
import com.drive.drive_manager.simulator.engine.EffectStep.IfNotDone;
import com.drive.drive_manager.simulator.engine.EffectStep.NoUntapChosen;
import com.drive.drive_manager.simulator.engine.EffectStep.SubjectFromTrigger;
import com.drive.drive_manager.simulator.engine.EffectStep.TributeAllyMaterial;

import com.drive.drive_manager.simulator.engine.EffectStep.*;

import static com.drive.drive_manager.simulator.engine.Condition.*;

import java.util.*;

/**
 * Effects implemented by the engine, by card name (reprints keep their effects), plus the keyword abilities
 * it knows. Cards not listed here are still played by hand on the board.
 */
public final class CardEffects {

    private static final String EN_INVOCACION = "En Invocación";
    private static final String EN_DESTRUCCION = "En Destrucción";
    private static final String AL_ATACAR = "Al Atacar";
    private static final String AL_FINAL = "Al Final Del Ataque";
    private static final String EN_JUEGO = "En Juego";
    private static final String TODOS = "Todos Los Turnos";

    private static final Map<String, List<EffectScript>> BY_NAME = new HashMap<>();
    /** Inherited effects ("heredables"): what a card gives the creature it is a Recurso of (rule 11.4.0.3). */
    private static final Map<String, List<EffectScript>> INHERITED = new HashMap<>();
    private static final Map<String, List<Continuous>> CONTINUOUS = new HashMap<>();
    private static final Map<String, List<Aura>> AURAS = new HashMap<>();

    /**
     * Keyword abilities with an effect the engine runs ("Celeridad"). Others (Provocar, Letalidad…) are combat rules; Asalto modifies the
     * summon itself (Summons.offerAssault).
     */
    private static final Map<String, EffectScript> KEYWORDS = Map.of(
            "celeridad", new EffectScript("kw:celeridad", Moment.ON_SUMMON, false, "Celeridad",
                    "Puede subir a tu línea frontal.", List.of(moveSelfToFront()))
                    .when(not(new InFrontLine())),
            "agrupar 1", new EffectScript("kw:agrupar", Moment.ON_ATTACK, false, "Agrupar 1",
                    "Si esta carta declara ataque: puedes agotar 1 aliado para que esta carta, durante el ataque, gane su fuerza e inflija daño 1 vez "
                            + "extra en caso de hacerlo.", List.of(new GroupUp(1))));

    static {
        // ST3-1 Carnhaus (Iniciador)
        add("carnhaus",
                new EffectScript("carnhaus:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Mira las primeras 4 cartas de tu mazo: de ellas toma hasta 1 carta Violeta o Blanca. "
                                + "Envía el resto al fondo de tu mazo. Luego: tributa 1 carta de tu mano.",
                        List.of(lookTop(4), takeFromLookedRestToBottom(1, CardFilter.colors("P", "W")), tributeFromHand(1))),
                new EffectScript("carnhaus:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                        "Roba 1 carta. Luego: tributa 1 carta de tu mano.",
                        List.of(draw(1), tributeFromHand(1))));
        // ST3-2 Damon
        add("damon", new EffectScript("damon:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                "Roba 3 cartas. Luego: tributa 2 cartas de tu mano.",
                List.of(draw(3), tributeFromHand(2))));
        // ST3-3 Bajmut Pútrido (Imbloqueable: combat rule)
        add("bajmut pútrido", new EffectScript("bajmut-putrido:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Tributa 1 carta de tu mano.",
                List.of(tributeFromHand(1))));
        // ST3-4 Buratán: only Provocar (combat rule)
        // ST3-5 Slicker: the cost can only be paid from the Tributos (sacrificed), not from the Descarte
        add("slicker", new EffectScript("slicker:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                "Puedes enviar esta carta a tu descarte para invocar 1 criatura Violeta de tus tributos reduciendo su coste en 1.",
                List.of(new IfPossible(), sendSelfTo("discardPile"), summonFromZone("tributeZone", CardFilter.creatures().withColors("P"), 1))));
        // ST3-6 Virdris
        add("virdris", new EffectScript("virdris:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                "Roba 3 cartas. Luego: tributa 2 cartas de tu mano.",
                List.of(draw(3), tributeFromHand(2))));
        // ST3-7 Krokler
        add("krokler", new EffectScript("krokler:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes elegir 1 enemigo nivel 1; destrúyelo.",
                List.of(chooseEnemy(1, CardFilter.any().withLevel(1))),
                List.of(destroyChosen())));
        // ST3-8 Zohx
        add("zohx", new EffectScript("zohx:al-final-del-ataque", Moment.ON_ATTACK_END, false, AL_FINAL,
                "Si tienes 10 o más criaturas [bestia] en tus tributos: esta carta puede invocar por Ritual 1 criatura de tus tributos reduciendo el coste especial en 1.",
                List.of(new RitualFromZone("tributeZone", true, 1)))
                .when(new CountInZone("tributeZone", CardFilter.creatures().withClasses("bestia"), 10)));
        // ST3-9 Zhaglizer
        add("zhaglizer", new EffectScript("zhaglizer:al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Elige 1 enemigo nivel 2 o menor; destrúyelo.",
                List.of(chooseEnemy(1, CardFilter.any().withMaxLevel(2))),
                List.of(destroyChosen())));
        // ST3-10 Hong (Letalidad 1: combat rule)
        add("hong", new EffectScript("hong:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Una vez por turno: si destruyes algún enemigo, puedes elegir 1 enemigo nivel 1; destrúyelo.",
                List.of(chooseEnemy(1, CardFilter.any().withLevel(1))),
                List.of(destroyChosen()))
                .when(new YouDestroyedEnemy(false))
                .limited(Limit.ONCE_PER_TURN));
        // ST3-11 Cazador De Bestias (Celeridad: keyword)
        add("cazador de bestias", new EffectScript("cazador-de-bestias:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Roba 3 cartas. Luego: tributa 2 cartas de tu mano.",
                List.of(draw(3), tributeFromHand(2))));
        // ST3-12 Rhogler
        add("rhogler", new EffectScript("rhogler:al-final-del-ataque", Moment.ON_ATTACK_END, false, AL_FINAL,
                "(1) Puedes sacrificar esta carta; invoca 1 criatura [bestia] nivel 3 de tus tributos reduciendo su coste en 3.",
                List.of(sacrificeSelf()),
                List.of(summonFromZone("tributeZone", CardFilter.creatures().withClasses("bestia").withLevel(3), 3)))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // ST3-13 Zhauz
        add("zhauz",
                new EffectScript("zhauz:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                        "Una vez por turno: puedes sacrificar 1 aliado para elegir 1 enemigo nivel 3 o menor; destrúyelo.",
                        List.of(sacrificeAlly(), chooseEnemy(1, CardFilter.any().withMaxLevel(3))),
                        List.of(destroyChosen()))
                        .limited(Limit.ONCE_PER_TURN),
                new EffectScript("zhauz:turno-aliado", Moment.ON_EVENT, true, "Turno Aliado",
                        "Una vez por turno: si algún aliado es destruido, reactiva esta carta.",
                        List.of(untapSelf()))
                        .when(new AllyDestroyed(CardFilter.any(), false, false))
                        .onlyAllyTurn()
                        .limited(Limit.ONCE_PER_TURN));
        // ST3-14 Mawser (Provocar: combat rule)
        add("mawser", new EffectScript("mawser:todos-los-turnos", Moment.ON_EVENT, true, TODOS,
                "Una vez por turno: si esta carta destruye algún enemigo, elige 1 enemigo nivel 3 o menor; destrúyelo.",
                List.of(chooseEnemy(1, CardFilter.any().withMaxLevel(3))),
                List.of(destroyChosen()))
                .when(new ThisDestroyedEnemy(false))
                .limited(Limit.ONCE_PER_TURN));
        // ST3-15 Berkelus El Cazarrecompensas Del Bajomundo
        add("berkelus el cazarrecompensas del bajomundo", new EffectScript("berkelus:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                // "A para B": both on resolution, offered only with 10 [bestia] in the Tributos
                "Reactiva esta carta. Luego: puedes enviar 10 criaturas [bestia] de tus tributos al fondo de tu mazo para que "
                        + "esta carta declare ataque y gane Furia 1 hasta el final del turno.",
                List.of(
                        untapSelf(),
                        new MayDo("¿Envías 10 criaturas [bestia] de tus tributos al fondo de tu mazo para que Berkelus declare ataque y gane Furia 1?", 3),
                        sendFromZoneToDeckBottom("tributeZone", 10, CardFilter.creatures().withClasses("bestia")),
                        gainUntilEndOfTurn("Furia 1"),
                        new DeclareAttack())));
        // ST3-16 Cueva De Bestias (structure)
        add("cueva de bestias", new EffectScript("cueva-de-bestias:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Si algún aliado Violeta es destruido: puedes agotar esta carta; gana 1 gema y roba 1 carta. Si lo haces: tributa 1 carta de tu mano.",
                List.of(tapSelf()),
                List.of(gainGems(1), draw(1), new IfDone(1), tributeFromHand(1)))
                .when(new AllyDestroyed(CardFilter.colors("P"), false, false)));
        // ST3-17 Alimento De Las Sombras (utility)
        add("alimento de las sombras",
                new EffectScript("alimento-de-las-sombras:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Un aliado invoca por Ritual 1 criatura de tus tributos reduciendo su coste especial en 1.",
                        List.of(new RitualFromZone("tributeZone", false, 1))),
                new EffectScript("alimento-de-las-sombras:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "(1) Si algún aliado es destruido por el oponente: puedes jugar esta carta sin pagar su coste.",
                        List.of(playSelfFree()))
                        .when(new AllyDestroyed(CardFilter.any(), true, false))
                        .fromZone("hand")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // ST3-18 Caza Nocturna (utility)
        // "cuando fueras a jugar esta carta, si tienes menos de 0 gemas: reduce su coste en 1"
        costModifier("caza nocturna", new CostModifier("Reactivo", CostModifiers.Event.PLAY, Set.of(), CostModifiers.Holder.SELF, false,
                not(new GemsAtLeast(0)), Payment.none(), 1));
        add("caza nocturna", new EffectScript("caza-nocturna:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "(1) Elige 1 aliado para que gane Provocar 3 hasta el final del turno y declare ataque.",
                List.of(new PickAlly(true, true), gainUntilEndOfTurn("Provocar 3"), new DeclareAttack()))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));

        // E1-P17 Arañador
        add("arañador",
                new EffectScript("aranador:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes invocar 1 criatura Violeta nivel 1 de tus tributos reduciendo su coste en 1.",
                        List.of(summonFromZone("tributeZone", CardFilter.creatures().withColors("P").withLevel(1), 1))),
                // A slow effect (no trigger condition): activated from the board in the open state
                new EffectScript("aranador:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                        "Una vez por turno: puede sacrificar 1 aliado para reactivar otro.",
                        List.of(new IfPossible(), sacrificeAlly(true), untapAlly()))
                        .onlyAllyTurn()
                        .limited(Limit.ONCE_PER_TURN));
    }

    // ------------------------------------------------------------------ ST4 Caballeros
    static {
        // ST4-1 Caballero Umbrío (Iniciador)
        add("caballero umbrío",
                new EffectScript("caballero-umbrio:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Mira las primeras 4 cartas de tu mazo: de ellas toma hasta 2 cartas Rojas o Blancas. Envía el resto al fondo de tu mazo. "
                                + "Luego: tributa 1 carta de tu mano.",
                        List.of(lookTop(4), takeFromLookedRestToBottom(2, CardFilter.colors("R", "W")), tributeFromHand(1))),
                new EffectScript("caballero-umbrio:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                        "Puedes invocar 1 criatura Roja, [caballero] o [mercenario] de tu mano en tu línea frontal pagando su coste.",
                        List.of(summonFromZone("hand", CardFilter.creatures().withColors("R").withClasses("caballero", "mercenario"), 0, true))));
        // ST4-2 Peón De Línea Frontal: Enfrentar (combat rule)
        // ST4-3 Espada De Los Leones De Fuego
        add("espada de los leones de fuego", new EffectScript("espada-leones:en-mano", Moment.ON_EVENT, false, "En Mano",
                "(1) Si algún aliado es invocado por efecto y controlas algún [caballero] nivel 4 o mayor: puedes invocar esta carta sin pagar su coste.",
                List.of(summonSelfFree()))
                .when(all(new AllySummonedByEffect(CardFilter.any()), new CountOnField(CardFilter.creatures().withClasses("caballero").withMinLevel(4), 1, null)))
                .fromZone("hand")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // ST4-4 Espada De Primera Línea
        continuous("espada de primera línea", new Continuous("Turno Aliado",
                "Mientras controles 3 o más aliados Rojos: esta carta gana fuerza 2 y tiene Celeridad.", true,
                new CountOnField(CardFilter.creatures().withColors("R"), 3, null),
                2, List.of("Celeridad")));
        add("espada de primera línea", new EffectScript("espada-primera-linea:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Roba 2 cartas. Luego: tributa 1 carta de tu mano.",
                List.of(draw(2), tributeFromHand(1))));
        // ST4-5 Caballero Férreo
        add("caballero férreo", new EffectScript("caballero-ferreo:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Reactiva 1 aliado nivel 2 o menor.",
                List.of(untapAlly(CardFilter.any().withMaxLevel(2)))));
        // ST4-6 Espada De Plata: once destroyed it is not on the field, so the summoner is any ally that meets the requirement
        add("espada de plata", new EffectScript("espada-de-plata:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                "Puedes invocar por Ascenso 1 criatura Roja de tu mano reduciendo el coste especial en 1.",
                List.of(new SpecialFromZone("promotion", "hand", 1, "any", CardFilter.creatures().withColors("R")))));
        // ST4-7 Filo Vengativo: Celeridad (keyword)
        // ST4-8 Armadura De Plata
        add("armadura de plata", new EffectScript("armadura-de-plata:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "(1) Otro aliado puede invocar por Ascenso 1 criatura de tu mano reduciendo el coste especial en 1.",
                List.of(new SpecialFromZone("promotion", "hand", 1, true)))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // ST4-9 Caballería Cruzada
        add("caballería cruzada", new EffectScript("caballeria-cruzada:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Reactiva 1 aliado.",
                List.of(untapAlly())));
        // ST4-10 Luke El Filo Rojo (Celeridad: keyword)
        add("luke el filo rojo", new EffectScript("luke:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION + " por efecto",
                "Esta carta gana Enfrentar 3 hasta el final del turno.",
                List.of(gainUntilEndOfTurn("Enfrentar 3")))
                .when(new SummonedByEffect()));
        // ST4-11 Ballesta Montada
        add("ballesta montada", new EffectScript("ballesta-montada:final-del-turno", Moment.ON_EVENT, false, "Turno Aliado",
                "Al final del turno: esta carta activa puede atacar al oponente sin agotarse.",
                List.of(new DeclareAttack(true)))
                .when(all(new TurnEnds(), new Active(), new InFrontLine(), new NoCombat()))
                .onlyAllyTurn());
        // ST4-12 Franchute (Perforante: combat rule)
        add("franchute", new EffectScript("franchute:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes invocar criaturas Rojas que en nivel sumen hasta 2 de tu mano sin pagar sus costes.",
                List.of(new SummonFreeLevelSum("hand", CardFilter.creatures().withColors("R"), 2))));
        // ST4-13 Comandante De Las Espadas Dragón (Enfrentar 3: combat rule)
        add("comandante de las espadas dragón", new EffectScript("comandante-espadas-dragon:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Una vez por turno: puedes invocar 1 criatura Roja nivel 2 o menor de tu mano reduciendo su coste en 1.",
                List.of(summonFromZone("hand", CardFilter.creatures().withColors("R").withMaxLevel(2), 1)))
                .limited(Limit.ONCE_PER_TURN));
        // ST4-14 Caballero Real
        add("caballero real", new EffectScript("caballero-real:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Una vez por turno: puedes invocar 1 criatura Roja nivel 3 de tu mano reduciendo su coste en 2. "
                        + "Si lo haces: esta carta gana fuerza 3 y Perforante hasta el final del turno.",
                List.of(summonFromZone("hand", CardFilter.creatures().withColors("R").withLevel(3), 2),
                        new IfDone(2), gainStrength(3), gainUntilEndOfTurn("Perforante")))
                .limited(Limit.ONCE_PER_TURN));
        // ST4-15 Rey Franco
        add("rey franco",
                new EffectScript("rey-franco:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                        "Una vez por turno: si esta carta entra en combate, puedes reactivar esta carta.",
                        List.of(untapSelf()))
                        .when(new EntersCombat())
                        .limited(Limit.ONCE_PER_TURN),
                new EffectScript("rey-franco:final-del-turno", Moment.ON_EVENT, false, "Turno Aliado",
                        "Al final del turno: 1 aliado puede invocar por Ascenso una criatura de tu mano reduciendo el coste especial en 2.",
                        List.of(new SpecialFromZone("promotion", "hand", 2, false)))
                        .when(new TurnEnds())
                        .onlyAllyTurn());
        // ST4-16 Reino Medieval (structure)
        add("reino medieval", new EffectScript("reino-medieval:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Si algún aliado Rojo es invocado por efecto: puedes agotar esta carta para ganar 1 gema y tomar 1 carta Roja de tus tributos. "
                        + "Si lo haces: tributa 1 carta de tu mano.",
                List.of(new IfPossible(), tapSelf(), gainGems(1), takeFromZone("tributeZone", CardFilter.colors("R"), 1, false),
                        new IfDone(1), tributeFromHand(1)))
                .when(new AllySummonedByEffect(CardFilter.colors("R"))));
        // ST4-17 Caballeros De La Primera Línea (utility)
        add("caballeros de la primera línea",
                new EffectScript("caballeros-primera-linea:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Puedes invocar 1 criatura Roja de tu mano reduciendo su coste en 1.",
                        List.of(new MayDo("¿Invocas 1 criatura Roja de tu mano reduciendo su coste en 1?", 1),
                                summonFromZone("hand", CardFilter.creatures().withColors("R"), 1))),
                new EffectScript("caballeros-primera-linea:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "(1) Si algún aliado es destruido: puedes jugar esta carta sin pagar su coste.",
                        List.of(playSelfFree()))
                        .when(new AllyDestroyed(CardFilter.any(), false, false))
                        .fromZone("hand")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // ST4-18 Investidura (utility)
        add("investidura",
                new EffectScript("investidura:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "(1) Puedes tributar 1 carta de tu mano para tomar 1 carta de tus tributos. Luego: 1 aliado puede invocar por Ascenso "
                                + "1 criatura en tu mano reduciendo su coste especial en 1.",
                        List.of(new MayDo("¿Tributas 1 carta de tu mano para tomar 1 carta de tus tributos?", 2),
                                tributeFromHand(1), takeFromZone("tributeZone", CardFilter.any(), 1, false),
                                new SpecialFromZone("promotion", "hand", 1, false)))
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("investidura:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "Si algún aliado es invocado por efecto: puedes jugar esta carta sin pagar su coste.",
                        List.of(playSelfFree()))
                        .when(new AllySummonedByEffect(CardFilter.any()))
                        .fromZone("hand"));
    }

    // ------------------------------------------------------------------ ST2 Dragones
    static {
        CardFilter dragon = CardFilter.creatures().withClasses("dragón");
        CardFilter fiveDragons = CardFilter.any().withClasses("dragón");
        // ST2-1 Dírumy (Iniciador)
        add("dírumy",
                new EffectScript("dirumy:tributada", Moment.ON_EVENT, true, "En Tributos",
                        "Si esta carta es tributada desde tu mazo por efecto de 1 carta Verde: roba 1 carta. Luego: tributa 1 carta de tu mano.",
                        List.of(draw(1), tributeFromHand(1)))
                        .when(new TributedFromDeck(CardFilter.colors("G")))
                        .fromZone("tributeZone"),
                new EffectScript("dirumy:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Mira las primeras 4 cartas de tu mazo: de ellas toma hasta 1 carta Verde o Blanca. Envía el resto al fondo de tu mazo.",
                        List.of(lookTop(4), takeFromLookedRestToBottom(1, CardFilter.colors("G", "W")))));
        inherit("dírumy", new EffectScript("dirumy:hereda-al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Heredado de Dírumy: puedes tributar \"Dírumy\" de los recursos de esta carta para robar 1 carta.",
                List.of(new IfPossible(), tributeResource("Dírumy"), draw(1))));
        // ST2-2 Wyphound
        add("wyphound", new EffectScript("wyphound:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Coloca hasta 1 carta de tu mano al tope de tu mazo. Luego: tributa 2 cartas del tope de tu mazo.",
                List.of(toDeckTop("hand", 1, true), tributeTop(2))));
        inherit("wyphound", new EffectScript("wyphound:hereda-al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Heredado de Wyphound: puedes colocar 1 carta de tus tributos al tope de tu mazo.",
                List.of(toDeckTop("tributeZone", 1, false))));
        // ST2-3 Flamagón
        add("flamagón", new EffectScript("flamagon:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Esta carta puede invocar por Evolución 1 [dragón] de tu mano reduciendo el coste especial en 1.",
                List.of(new SpecialFromZone("evolution", "hand", 1, "self", dragon))));
        inherit("flamagón", new EffectScript("flamagon:hereda-todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Heredado de Flamagón: si algún enemigo es destruido por tus efectos, puedes tomar 1 carta Verde de tus tributos.",
                List.of(takeFromZone("tributeZone", CardFilter.colors("G"), 1, false)))
                .when(new YouDestroyedEnemy(true)));
        // ST2-4 Duregón (Iniciador)
        // "Turno Aliado: cuando esta carta fuera a invocar por Evolución, si tienes 5 o más tributos [dragón]: reduce en 1 el coste especial"
        costModifier("duregón", new CostModifier("Turno Aliado", CostModifiers.Event.SPECIAL_SUMMON, Set.of("evolution"), CostModifiers.Holder.SUMMONER, true,
                new CountInZone("tributeZone", fiveDragons, 5), Payment.none(), 1));
        add("duregón", new EffectScript("duregon:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Tributa 3 cartas del tope de tu mazo. Luego: toma 1 carta Verde o [dragón] de tus tributos.",
                List.of(tributeTop(3), takeFromZone("tributeZone", CardFilter.colors("G").withClasses("dragón"), 1, false))));
        inherit("duregón", new EffectScript("duregon:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Duregón: roba 1 carta. Luego: tributa 1 carta de tu mano.",
                List.of(draw(1), tributeFromHand(1))));
        // ST2-5 Galveta
        add("galveta", new EffectScript("galveta:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Coloca hasta 1 carta de tu mano al tope de tu mazo. Luego: tributa 2 cartas del tope de tu mazo.",
                List.of(toDeckTop("hand", 1, true), tributeTop(2))));
        inherit("galveta", new EffectScript("galveta:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Galveta: coloca hasta 1 carta de tu mano al tope de tu mazo. Luego: tributa 2 cartas del tope de tu mazo.",
                List.of(toDeckTop("hand", 1, true), tributeTop(2))));
        // ST2-6 Wyfreik
        add("wyfreik", new EffectScript("wyfreik:al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Tributa 3 cartas del tope de tu mazo.",
                List.of(tributeTop(3))));
        inherit("wyfreik", new EffectScript("wyfreik:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Wyfreik: coloca hasta 1 carta de tu mano al tope de tu mazo. Luego: tributa 2 cartas del tope de tu mazo.",
                List.of(toDeckTop("hand", 1, true), tributeTop(2))));
        // ST2-7 Dragón Delta
        inherit("dragón delta", new EffectScript("dragon-delta:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Dragón Delta: elige 1 enemigo de fuerza 5 o menos; destrúyelo.",
                List.of(chooseEnemy(1, CardFilter.creatures().withMaxStrength(5))), List.of(destroyChosen())));
        // ST2-8 Flambreik
        // "Turno Aliado: cuando esta carta fuera a invocar por Evolución, puedes enviar 5 de tus tributos [dragón] al fondo de tu mazo
        // para reducir el coste especial en 1"
        costModifier("flambreik", new CostModifier("Turno Aliado", CostModifiers.Event.SPECIAL_SUMMON, Set.of("evolution"), CostModifiers.Holder.SUMMONER, true,
                null, Payment.tributesToDeckBottom(5, fiveDragons), 1));
        inherit("flambreik", new EffectScript("flambreik:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Flambreik: coloca hasta 1 carta de tu mano al tope de tu mazo. Luego: tributa 2 cartas del tope de tu mazo.",
                List.of(toDeckTop("hand", 1, true), tributeTop(2))));
        // ST2-9 Fadrake
        add("fadrake", new EffectScript("fadrake:al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Una vez por turno: elige 1 enemigo de fuerza 5 o menos; destrúyelo.",
                List.of(chooseEnemy(1, CardFilter.creatures().withMaxStrength(5))), List.of(destroyChosen()))
                .limited(Limit.ONCE_PER_TURN));
        inherit("fadrake", new EffectScript("fadrake:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Fadrake: elige 1 enemigo de fuerza 5 o menos; destrúyelo.",
                List.of(chooseEnemy(1, CardFilter.creatures().withMaxStrength(5))), List.of(destroyChosen())));
        // ST2-10 Ddraig
        // The enemy is chosen on activation (up to 1, so it always activates); on resolution the 3 cards are always
        // tributed, then the chosen enemy (if any, and still valid) is destroyed
        add("ddraig", new EffectScript("ddraig:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Elige hasta 1 enemigo de fuerza 5 o menos; tributa 3 cartas del tope de tu mazo. Luego: destrúyelo.",
                List.of(chooseEnemy(1, CardFilter.creatures().withMaxStrength(5), true)),
                List.of(tributeTop(3), destroyChosen())));
        inherit("ddraig", new EffectScript("ddraig:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Ddraig: elige 1 enemigo de fuerza 5 o menos; destrúyelo.",
                List.of(chooseEnemy(1, CardFilter.creatures().withMaxStrength(5))), List.of(destroyChosen())));
        // ST2-11 Wyverno De Pantano
        add("wyverno de pantano", new EffectScript("wyverno-pantano:tributada", Moment.ON_EVENT, false, "En Tributos",
                "(1) Si esta carta es tributada desde tu mazo: puedes tributar 1 carta de tu mano para tomar 1 carta Verde de tus tributos.",
                List.of(new IfPossible(), tributeFromHand(1), takeFromZone("tributeZone", CardFilter.colors("G"), 1, false)))
                .when(new TributedFromDeck(null))
                .fromZone("tributeZone")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        inherit("wyverno de pantano", new EffectScript("wyverno-pantano:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Wyverno De Pantano: elige hasta 2 enemigos de fuerza 3 o menos; destrúyelos.",
                List.of(chooseEnemy(2, CardFilter.creatures().withMaxStrength(3), true)), List.of(destroyChosen())));
        // ST2-12 Dracoventus
        add("dracoventus", new EffectScript("dracoventus:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Puedes enviar 5 de tus tributos [dragón] al fondo de tu mazo para elegir 1 enemigo de fuerza 9 o menos; destrúyelo.",
                List.of(sendFromZoneToDeckBottom("tributeZone", 5, fiveDragons), chooseEnemy(1, CardFilter.creatures().withMaxStrength(9))),
                List.of(destroyChosen())));
        // ST2-13 Saildreik
        add("saildreik", new EffectScript("saildreik:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Esta carta puede invocar por Evolución 1 [dragón] de tus tributos reduciendo su coste especial en 1.",
                List.of(new SpecialFromZone("evolution", "tributeZone", 1, "self", dragon))));
        inherit("saildreik", new EffectScript("saildreik:hereda-al-final-del-ataque", Moment.ON_ATTACK_END, true, AL_FINAL,
                "Heredado de Saildreik: elige 1 enemigo de fuerza 8 o menos; destrúyelo.",
                List.of(chooseEnemy(1, CardFilter.creatures().withMaxStrength(8))), List.of(destroyChosen())));
        // ST2-14 Dragón Carmesí
        EffectScript carmesiAttack = new EffectScript("dragon-carmesi:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Una vez por turno: puedes enviar 5 de tus tributos [dragón] al fondo de tu mazo; esta carta gana Letalidad 1 hasta el final del ataque.",
                List.of(sendFromZoneToDeckBottom("tributeZone", 5, fiveDragons)), List.of(gainUntilEndOfAttack("Letalidad 1")))
                .limited(Limit.ONCE_PER_TURN);
        add("dragón carmesí", carmesiAttack,
                new EffectScript("dragon-carmesi:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                        "Una vez por turno: si destruyes algún enemigo, puedes reactivar esta carta.",
                        List.of(untapSelf()))
                        .when(new YouDestroyedEnemy(false))
                        .limited(Limit.ONCE_PER_TURN));
        inherit("dragón carmesí", new EffectScript("dragon-carmesi:hereda-al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Heredado de Dragón Carmesí: puedes enviar 5 de tus tributos [dragón] al fondo de tu mazo; esta carta gana Letalidad 1 hasta el final del ataque.",
                List.of(sendFromZoneToDeckBottom("tributeZone", 5, fiveDragons)), List.of(gainUntilEndOfAttack("Letalidad 1"))));
        // ST2-15 Dragón Volcánico
        add("dragón volcánico",
                new EffectScript("dragon-volcanico:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                        "Una vez por turno: puedes destruir 1 carta bocarriba en las vidas oponentes.",
                        List.of(new DestroyFaceUpLife()))
                        .limited(Limit.ONCE_PER_TURN),
                new EffectScript("dragon-volcanico:al-final-del-ataque", Moment.ON_ATTACK_END, false, AL_FINAL,
                        "Una vez por turno: si algún enemigo fue destruido durante el ataque, puedes enviar 5 de tus tributos [dragón] al fondo de tu mazo "
                                + "para reactivar esta carta.",
                        List.of(new IfPossible(), sendFromZoneToDeckBottom("tributeZone", 5, fiveDragons), untapSelf()))
                        .when(new EnemyDestroyedInAttack())
                        .limited(Limit.ONCE_PER_TURN));
        // ST2-16 Volcán De Dragones (structure)
        add("volcán de dragones", new EffectScript("volcan-de-dragones:turno-aliado", Moment.ON_EVENT, false, "Turno Aliado",
                "Si invocas especialmente 1 [dragón]: puedes agotar esta carta para ganar 1 gema y robar 1 carta. Si lo haces: tributa 1 carta de tu mano.",
                List.of(new IfPossible(), tapSelf(), gainGems(1), draw(1), new IfDone(1), tributeFromHand(1)))
                .when(new YouSpecialSummoned(dragon))
                .onlyAllyTurn());
        // ST2-17 Huevo De Dragón (utility): it stays on the field as a permanent, with a slow effect from there
        add("huevo de dragón",
                new EffectScript("huevo-de-dragon:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Tributa 4 cartas del tope de tu mazo. Luego: toma 1 carta Verde o [dragón] de tus tributos. Esta carta permanece agotada en el campo.",
                        List.of(tributeTop(4), takeFromZone("tributeZone", CardFilter.colors("G").withClasses("dragón"), 1, false),
                                new KeepOnField(true))),
                new EffectScript("huevo-de-dragon:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                        "Puedes enviar esta carta activa a tu descarte para invocar 1 [dragón] nivel 1 de tus tributos reduciendo su coste en 1.",
                        List.of(new IfPossible(), sendSelfFromFieldTo("discardPile"),
                                summonFromZone("tributeZone", CardFilter.creatures().withClasses("dragón").withMaxLevel(1), 1, false)))
                        .when(new Active())
                        .onlyAllyTurn());
        // ST2-18 Llamarada (utility)
        add("llamarada",
                new EffectScript("llamarada:tributada", Moment.ON_EVENT, false, "En Tributos",
                        "(1) Si esta carta es tributada desde tu mazo por un aliado [dragón]: puedes jugar esta carta reduciendo su coste en el nivel de dicho aliado.",
                        List.of(playSelfFromZone("tributeZone", true)))
                        .when(new TributedFromDeck(CardFilter.creatures().withClasses("dragón")))
                        .fromZone("tributeZone")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("llamarada:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Elige 1 enemigo de fuerza 11 o menos; destrúyelo.",
                        List.of(chooseEnemy(1, CardFilter.creatures().withMaxStrength(11))), List.of(destroyChosen())));
    }

    // ------------------------------------------------------------------ ST1 Entes
    static {
        CardFilter enemy = CardFilter.creatures();
        // ST1-1 Eisten
        add("eisten", new EffectScript("eisten:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Tributa 4 cartas del tope de tu mazo. Luego: toma hasta 1 carta Azul de tus tributos.",
                List.of(tributeTop(4), takeFromZone("tributeZone", CardFilter.colors("B"), 1, true))));
        // ST1-2 Jhav
        add("jhav", new EffectScript("jhav:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Toma hasta 1 carta Azul de tus tributos. Luego: tributa 1 carta de tu mano. Si tributaste 1 carta [ente]: roba 1 carta.",
                List.of(takeFromZone("tributeZone", CardFilter.colors("B"), 1, true), tributeFromHand(1),
                        new IfTributed(CardFilter.any().withClasses("ente"), 1), draw(1))));
        // ST1-3 Mihel: "su fuerza" is the reduced enemy's (it adds to the ally effect's reduction)
        add("mihel",
                new EffectScript("mihel:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Roba 2 cartas. Luego: tributa 2 cartas de tu mano.",
                        List.of(draw(2), tributeFromHand(2))),
                new EffectScript("mihel:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                        "Si reduces la fuerza de algún enemigo: puedes añadir esta carta como material a un aliado para reducir su fuerza en 2 más.",
                        List.of(new IfPossible(), new PickAlly(), attachSelfToSubject(), reduceTriggerFurther(2)))
                        .when(new YouReducedStrength(null, true)));
        // ST1-4 Flureteus (Iniciador)
        add("flureteus",
                new EffectScript("flureteus:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Mira las primeras 5 cartas de tu mazo: de ellas toma hasta 2 cartas Azules o Blancas. Envía el resto al fondo de tu mazo. "
                                + "Luego: tributa 2 cartas de tu mano.",
                        List.of(lookTop(5), takeFromLookedRestToBottom(2, CardFilter.colors("B", "W")), tributeFromHand(2))),
                new EffectScript("flureteus:turno-aliado", Moment.ON_EVENT, false, "Turno Aliado",
                        "Si invocas 1 criatura por efecto: puedes invocar 1 criatura [ente] nivel 2 de tus tributos en tu línea frontal sin pagar su coste. "
                                + "Si lo haces: añade esta carta como material a un aliado.",
                        List.of(summonFromZone("tributeZone", CardFilter.creatures().withClasses("ente").withLevel(2), FREE, true),
                                new IfDone(2), new PickAlly(), attachSelfToSubject()))
                        .when(new AllySummonedByEffect(CardFilter.creatures()))
                        .onlyAllyTurn());
        // ST1-5 Flarix
        add("flarix",
                new EffectScript("flarix:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes tributar hasta 2 materiales de esta carta; toma 1 carta Azul de tus tributos por cada material tributado.",
                        List.of(tributeMaterials(2, true)), List.of(takePerCount("tributeZone", CardFilter.colors("B")))),
                new EffectScript("flarix:en-tributos", Moment.ON_EVENT, false, "En Tributos",
                        "(1) Si reduces a 0 la fuerza de algún enemigo: puedes enviar esta carta al fondo de tu mazo para infringir 9 de daño directo.",
                        List.of(new IfPossible(), sendSelfTo("deck"), damageByEffect(9)))
                        .when(new YouReducedStrength(0, false))
                        .fromZone("tributeZone")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // ST1-6 Zula
        add("zula", new EffectScript("zula:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Si tienes 4 o menos vidas: puedes tributar 1 material de esta carta; coloca hasta 1 material de esta carta al tope de tus vidas bocarriba.",
                List.of(tributeMaterials(1, false)), List.of(materialToLife()))
                .when(new LivesAtMost(false, 4)));
        // ST1-7 Fhalid: no effect
        // ST1-8 Arc xioma
        add("arc-xioma",
                new EffectScript("arc-xioma:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes tributar 1 material de esta carta y elegir 1 enemigo; reduce su fuerza en 3 hasta el final del turno.",
                        List.of(tributeMaterials(1, false), chooseEnemy(1, enemy)), List.of(reduceChosen(3, false))),
                new EffectScript("arc-xioma:en-tributos", Moment.ON_EVENT, false, "En Tributos",
                        "Si reduces a 2 o menos la fuerza de algún enemigo: puedes enviar esta carta al fondo de tu mazo para reducirla a 0.",
                        List.of(new IfPossible(), sendSelfTo("deck"), reduceTriggerToZero()))
                        .when(new YouReducedStrength(2, false))
                        .fromZone("tributeZone"));
        // ST1-9 Bertalum
        add("bertalum", new EffectScript("bertalum:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes tributar 2 materiales de esta carta para elegir 1 enemigo; reduce en 4 su fuerza hasta el final del turno.",
                List.of(tributeMaterials(2, false), chooseEnemy(1, enemy)), List.of(reduceChosen(4, false))));
        // ST1-10 Sahiel (Reactivar: keyword)
        add("sahiel", new EffectScript("sahiel:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Si tienes 5 o menos vidas: puedes tributar 2 materiales de esta carta; coloca la carta del tope de tu mazo al tope de tus vidas bocabajo.",
                List.of(tributeMaterials(2, false)), List.of(deckTopToLife()))
                .when(new LivesAtMost(false, 5)));
        // ST1-11 Falis
        add("falis", new EffectScript("falis:en-tributos", Moment.ON_EVENT, false, "En Tributos",
                "(1) Si reduces a 0 la fuerza de algún enemigo: puedes invocar esta carta reduciendo su coste en 2.",
                List.of(summonSelfFromZone("tributeZone", 2)))
                .when(new YouReducedStrength(0, false))
                .fromZone("tributeZone")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // ST1-12 Mathaus (Letalidad 1: combat rule)
        add("mathaus", new EffectScript("mathaus:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                "Una vez por turno: puedes tributar 2 materiales de esta carta; elige 1 enemigo para reducir su fuerza en 3 hasta el final del turno oponente.",
                // "; elige 1 enemigo": after the ";", so the enemy is chosen on resolution
                List.of(tributeMaterials(2, false)), List.of(chooseEnemy(1, enemy), reduceChosen(3, true)))
                .onlyAllyTurn()
                .limited(Limit.ONCE_PER_TURN));
        // ST1-13 Falanx: "En Tributos" (continuous) is SPECIAL_FROM_TRIBUTES. "si esta carta es agotada": exhausted to
        // declare an attack or by an effect; it triggers with the declaration (both happen at once)
        add("falanx", new EffectScript("falanx:agotada", Moment.ON_EVENT, false, TODOS,
                "Si esta carta es agotada: puedes tributar 2 materiales de esta carta y elegir 1 enemigo activo; reduce en 4 su fuerza "
                        + "hasta el final del próximo turno.",
                List.of(tributeMaterials(2, false), chooseEnemy(1, CardFilter.creatures().active())),
                List.of(reduceChosen(4, true)))
                .when(new ThisExhausted()));
        // ST1-14 Galafendrax El Transmutador
        add("galafendrax el transmutador",
                new EffectScript("galafendrax:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Coloca hasta 1 [ente] nivel 4 de los materiales de esta carta en tu línea frontal. Luego: añádele todos los materiales "
                                + "de esta carta como materiales.",
                        List.of(materialUpToToFront(CardFilter.creatures().withClasses("ente").withLevel(4)), materialsToLast())),
                new EffectScript("galafendrax:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                        "Una vez por turno: si reduces a 0 la fuerza de algún enemigo, puedes reactivar esta carta.",
                        List.of(untapSelf()))
                        .when(new YouReducedStrength(0, false))
                        .limited(Limit.ONCE_PER_TURN));
        // ST1-15 Hez (Reactivar: keyword)
        add("hez", new EffectScript("hez:inicio-del-turno", Moment.ON_EVENT, false, TODOS,
                "Al inicio del turno: puedes tributar 2 materiales de esta carta para elegir todos los enemigos en la línea frontal; "
                        + "reduce en 4 su fuerza hasta el final del turno.",
                // Before ";": the enemies are fixed on activation, with the cost
                List.of(tributeMaterials(2, false), targetAllEnemies()), List.of(reduceChosen(4, false)))
                .when(new TurnStarts(false)));
        // ST1-16 Portal Al Mundo Espiritual (structure)
        add("portal al mundo espiritual", new EffectScript("portal-mundo-espiritual:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Si algún aliado [ente] es invocado por efecto: puedes agotar esta carta; añádele 1 criatura [ente] de menor nivel de tus tributos "
                        + "como material para ganar 1 gema.",
                List.of(tapSelf()),
                // Before ";" its cost; then "añádele … para ganar 1 gema": the gem only if the material can be added
                List.of(new IfPossible(), new AttachLowerFromTributes(CardFilter.creatures().withClasses("ente")), gainGems(1)))
                .when(new AllySummonedByEffect(CardFilter.any().withClasses("ente"))));
        // ST1-17 Composición Adimensional (utility; its "Turno Aliado" Materialización is not applied yet)
        add("composición adimensional",
                new EffectScript("composicion-adimensional:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Tributa hasta 3 cartas de tu mano. Luego: roba la misma cantidad y coloca esta carta agotada en tu retaguardia.",
                        List.of(tributeFromHandUpTo(3), drawLastCount(), new KeepOnField(true))),
                // A slow effect from the field: the ally chosen gets this card as a material and is the summoner
                new EffectScript("composicion-adimensional:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                        "Puedes añadir esta carta activa como material a un aliado para que invoque por Materialización 1 criatura en tu mano o "
                                + "tributos reduciendo el coste especial en 1.",
                        List.of(new IfPossible(), new PickAlly(), attachSelfToSubject(),
                                new SpecialFromZone("materialization", "hand,tributeZone", 1, "subject", CardFilter.any())))
                        // "esta carta activa": not while it is exhausted (it stays exhausted after being played)
                        .when(new Active())
                        .onlyAllyTurn());
        // ST1-18 Paso Interdimensional (utility)
        add("paso interdimensional", new EffectScript("paso-interdimensional:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "(1) Elige 1 aliado: invoca 1 criatura [ente] del mismo nivel y distinto nombre de tus tributos en tu línea frontal sin pagar su coste. "
                        + "Si lo haces: añádele el aliado elegido con todos sus materiales como materiales del aliado invocado.",
                // "elige 1 aliado" is its cost (chosen on activation); the summon and the materials, its resolution
                List.of(new PickAlly(false, true)),
                List.of(new SummonLikeSubject("tributeZone", CardFilter.creatures().withClasses("ente")), new IfDone(1), new AttachSubjectToSummoned()))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
    }

    // ------------------------------------------------------------------ E1
    static {
        CardFilter enemy = CardFilter.creatures();
        // E1-P19 Zaranthrax
        add("zaranthrax", new EffectScript("zaranthrax:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "(1) Esta carta gana Letalidad 1 hasta el final de su próximo ataque este turno.",
                List.of(gainUntilNextAttack(0, "Letalidad 1")))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-R6 Sargento Legionario
        add("sargento legionario", new EffectScript("sargento-legionario:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Toma 1 carta Roja de tus tributos.",
                List.of(takeFromZone("tributeZone", CardFilter.colors("R"), 1, false))));
        continuous("sargento legionario", new Continuous(TODOS, "Esta carta gana fuerza 1 por cada aliado [legionario].", false,
                new Always(), 0, List.of(),
                new Amount.OnField(CardFilter.creatures().withClasses("legionario"))));
        // E1-G4 Wynsdey
        add("wynsdey", new EffectScript("wynsdey:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Tributa 3 cartas del tope de tu mazo. Luego: toma 1 carta Verde o Blanca de tus tributos.",
                List.of(tributeTop(3), takeFromZone("tributeZone", CardFilter.colors("G", "W"), 1, false))));
        inherit("wynsdey", new EffectScript("wynsdey:hereda-todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Heredado de Wynsdey: si esta carta es agotada, puedes tributar el recurso de este efecto; roba 1 carta.",
                List.of(tributeResource("Wynsdey")), List.of(draw(1)))
                .when(new ThisExhausted()));
        // E1-B15 Filirem (Asalto: keyword)
        add("filirem", new EffectScript("filirem:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Elige 1 enemigo con fuerza 6 o menos; reduce a 0 su fuerza.",
                List.of(chooseEnemy(1, enemy.withMaxStrength(6))), List.of(reduceChosenToZero())));
        // E1-B18 Iron (Enfrentar 4: combat rule)
        add("iron", new EffectScript("iron:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Puedes tributar 2 materiales de esta carta y elegir 1 enemigo; este pierde fuerza 3 hasta el final del turno.",
                List.of(tributeMaterials(2, false), chooseEnemy(1, enemy)), List.of(reduceChosen(3, false))));
        // E1-B23 Neocité, E1-G23 Bosque Frondoso, E1-R22 Tierra Feudal, E1-P23 Ruinas De Sangre: "Todos Los Turnos: cuando
        // [un aliado] fuera a invocar especialmente por X, puedes agotar esta carta para reducir el coste especial en 1"
        for (String[] s : List.of(new String[]{"neocité", "materialization"}, new String[]{"bosque frondoso", "evolution"},
                new String[]{"tierra feudal", "promotion"}, new String[]{"ruinas de sangre", "ritual"})) {
            costModifier(s[0], new CostModifier(TODOS, CostModifiers.Event.SPECIAL_SUMMON, Set.of(s[1]), CostModifiers.Holder.FIELD, false,
                    null, Payment.tapSelf(), 1));
        }
        // E1-G9 Driada: "para elegir" is part of its cost (before the ";")
        add("driada", new EffectScript("driada:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes tributar 1 carta de tu mano para elegir el enemigo agotado de menor fuerza; este no se reactiva al inicio del turno oponente.",
                List.of(tributeFromHand(1), new ChooseWeakestEnemy(enemy.exhausted())), List.of(new NoUntapChosen(false))));
        inherit("driada", new EffectScript("driada:hereda-todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Heredado de Driada: si esta carta es agotada, puedes tributar el recurso con este efecto; invoca 1 [hada] de tu mano reduciendo su coste en 1.",
                List.of(tributeResource("Driada")), List.of(summonFromZone("hand", CardFilter.creatures().withClasses("hada"), 1)))
                .when(new ThisExhausted()));
        // E1-G16 Dragón Rúnico
        add("dragón rúnico", new EffectScript("dragon-runico:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Mira las primeras 4 cartas de tu mazo: de ellas toma hasta 1 carta Verde. Envía el resto al fondo de tu mazo.",
                List.of(lookTop(4), takeFromLookedRestToBottom(1, CardFilter.colors("G")))));
        inherit("dragón rúnico", new EffectScript("dragon-runico:hereda-en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                "Heredado de Dragón Rúnico: un aliado puede invocar por Evolución un \"Dragón Rúnico\" en tus tributos reduciendo el coste especial en 1.",
                List.of(new SpecialFromZone("evolution", "tributeZone", 1, "any", CardFilter.creatures().named("Dragón Rúnico")))));
        // E1-R25 Fervor De Guerra
        add("fervor de guerra", playAtBlockStep("fervor-de-guerra"), new EffectScript("fervor-de-guerra:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "(1) Elige 1 aliado Rojo nivel 3 o menor; reactívalo.",
                List.of(new PickAllyWhere(CardFilter.creatures().withColors("R").withMaxLevel(3), true)), List.of(untapSubject()))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-B7 Petrus
        add("petrus", new EffectScript("petrus:tributada-como-material", Moment.ON_EVENT, true, "En Tributos",
                "(1) Si esta carta es tributada como material por efecto de un aliado nivel 4 o mayor: elige 1 enemigo; reduce en 2 su fuerza "
                        + "hasta el final del turno.",
                List.of(chooseEnemy(1, enemy)), List.of(reduceChosen(2, false)))
                .when(new TributedAsMaterial(4))
                .fromZone("tributeZone")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-P3 Rata Ponsoñoza
        add("rata ponsoñoza", new EffectScript("rata-ponsonoza:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                "Elige 1 enemigo nivel 1; destrúyelo.",
                List.of(chooseEnemy(1, CardFilter.any().withLevel(1))), List.of(destroyChosen())));
        // E1-P14 Saco De Los Mil Rostros (Asalto: keyword)
        add("saco de los mil rostros", new EffectScript("saco-mil-rostros:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Elige hasta 1 enemigo nivel 2 y 1 enemigo nivel 1; destrúyelos.",
                List.of(chooseEnemy(1, CardFilter.any().withLevel(2), true), chooseEnemy(1, CardFilter.any().withLevel(1), true)),
                List.of(destroyChosen())));
        // E1-P16 Necroquinox
        add("necroquinox", new EffectScript("necroquinox:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                "Elige 1 enemigo de nivel menor o igual a la cantidad de copias de esta carta en tus tributos; destrúyelo.",
                List.of(chooseEnemyBy(1, new FilterBy(CardFilter.any(), null, new Amount.InZone("tributeZone", CardFilter.any().named("Necroquinox"))), false)),
                List.of(destroyChosen())));
        // E1-B3 Ephos
        add("ephos", new EffectScript("ephos:tributada-como-material", Moment.ON_EVENT, true, "En Tributos",
                "Si esta carta es tributada como material: elige 1 enemigo; reduce en 1 su fuerza hasta el final del turno.",
                List.of(chooseEnemy(1, enemy)), List.of(reduceChosen(1, false)))
                .when(new TributedAsMaterial(0))
                .fromZone("tributeZone"));
        // E1-B8 Delesel
        add("delesel", new EffectScript("delesel:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                "Puedes invocar 1 [ente] nivel 2 de tus tributos reduciendo su coste en 2.",
                List.of(summonFromZone("tributeZone", CardFilter.creatures().withClasses("ente").withLevel(2), 2))));
        // E1-B5 Androide A7
        add("androide a7", new EffectScript("androide-a7:tributada-como-material", Moment.ON_EVENT, false, "En Tributos",
                "(1) Si esta carta es tributada como material y no controlas copias de esta carta: puedes tributar 1 material de 1 aliado "
                        + "para invocar esta carta en tu línea frontal, reduciendo su coste en 1.",
                List.of(new IfPossible(), new TributeAllyMaterial(), summonSelf("tributeZone", Amount.fixed(1), true)))
                .when(all(new TributedAsMaterial(0), new NoCopies()))
                .fromZone("tributeZone")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-G10 Deriduragon (its inherited replacement: INHERITED_REPLACEMENTS)
        add("deriduragon", new EffectScript("deriduragon:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Tributa 3 cartas del tope de tu mazo. Luego: puedes añadir 1 criatura de fuerza 5 o menor de tu mano o tributos como recurso de esta carta.",
                List.of(tributeTop(3), new MayDo("¿Añades 1 criatura de fuerza 5 o menor de tu mano o tributos como recurso de Deriduragon?", 1),
                        addResourceFrom("hand,tributeZone", CardFilter.creatures().withMaxStrength(5)))));
        // E1-G11 Lyna
        add("lyna", new EffectScript("lyna:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "(1) Puedes añadir 1 aliado nivel 1 como recurso de esta carta para ganar 1 gema. Luego: puedes agotar esta carta para reactivar "
                        + "1 de tus estructuras Verdes de coste 1 o menos.",
                List.of(new MayDo("¿Añades 1 aliado nivel 1 como recurso de Lyna para ganar 1 gema?", 2),
                        addResourceFrom("field", CardFilter.creatures().withLevel(1)), gainGems(1),
                        new MayDo("¿Agotas a Lyna para reactivar 1 de tus estructuras Verdes de coste 1 o menos?", 2),
                        tapSelf(), untapStructure(CardFilter.colors("G").withMaxCost(1))))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        inherit("lyna", new EffectScript("lyna:hereda-todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Heredado de Lyna: si esta carta es agotada, puedes tributar el recurso con este efecto; invoca 1 [hada] de tu mano reduciendo su coste en 1.",
                List.of(tributeResource("Lyna")), List.of(summonFromZone("hand", CardFilter.creatures().withClasses("hada"), 1)))
                .when(new ThisExhausted()));
        // E1-G13 Hana
        add("hana", new EffectScript("hana:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes tributar 1 carta de tu mano; elige 1 enemigo de fuerza 8 o menos para agotarlo. Si ningún enemigo fue agotado por este efecto: "
                        + "reactiva esta carta.",
                List.of(tributeFromHand(1)),
                List.of(chooseEnemy(1, enemy.withMaxStrength(8)), tapChosen(), new IfNotDone(1), untapSelf())));
        for (String hada : List.of("hana", "celeste", "bianca")) {
            String title = Character.toUpperCase(hada.charAt(0)) + hada.substring(1);
            inherit(hada, new EffectScript(hada + ":hereda-todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                    "Heredado de " + title + ": si algún aliado de fuerza 4 o más es invocado por efecto, puedes tributar el recurso de este efecto; gana 1 gema.",
                    List.of(tributeResource(title)), List.of(gainGems(1)))
                    .when(new AllySummonedByEffect(CardFilter.creatures().withMinStrength(4))));
        }
        // E1-R7 Caballero Leal (its replacement when it would be destroyed: REPLACEMENTS)
        add("caballero leal", new EffectScript("caballero-leal:en-mano", Moment.ON_EVENT, false, "Turno Aliado / En Mano",
                "Si algún aliado es invocado por efecto y no controlas copias de esta carta: puedes invocar esta carta reduciendo su coste en 1.",
                List.of(summonSelf("hand", Amount.fixed(1), false)))
                .when(all(new AllySummonedByEffect(CardFilter.any()), new NoCopies()))
                .fromZone("hand")
                .onlyAllyTurn());
        // E1-P13 Convocador De Sombras
        add("convocador de sombras",
                new EffectScript("convocador-de-sombras:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                        "Una vez por turno: puedes sacrificar 1 aliado nivel 2 o mayor para ganar 1 gema.",
                        List.of(new IfPossible(), sacrificeAllyWhere(CardFilter.creatures().withMinLevel(2)), gainGems(1)))
                        .limited(Limit.ONCE_PER_TURN),
                new EffectScript("convocador-de-sombras:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                        "Puedes tributar 1 carta de tu mano para invocar 1 criatura nivel 2 de tus tributos reduciendo su coste en 1.",
                        List.of(new IfPossible(), tributeFromHand(1), summonFromZone("tributeZone", CardFilter.creatures().withLevel(2), 1))));
        // E1-P18 Desollador
        add("desollador", new EffectScript("desollador:todos-los-turnos", Moment.ON_EVENT, true, TODOS,
                "Una vez por turno: si esta carta es agotada y tienes 20 o más tributos Violeta o [espectro], reactiva esta carta.",
                List.of(untapSelf()))
                .when(all(new ThisExhausted(), new CountInZone("tributeZone", CardFilter.colors("P").withClasses("espectro"), 20)))
                .limited(Limit.ONCE_PER_TURN));
        // E1-B11 VA 7 (Alcance: combat rule)
        add("va 7", new EffectScript("va-7:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes tributar 1 material de esta carta; juega 1 estructura Azul de tus tributos reduciendo su coste en 1.",
                List.of(tributeMaterials(1, false)),
                List.of(playFromZone("tributeZone", new CardFilter(List.of("B"), List.of(), "structure", null, null), 1))));
        // E1-B19 Sacerdotisa Del Portal Del Eclipse (Reactivar: keyword)
        add("sacerdotisa del portal del eclipse", new EffectScript("sacerdotisa-eclipse:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                "Puedes tributar 3 materiales de esta carta; invoca en tu línea frontal 1 criatura [chamán], [ente] o [ángel] de tus tributos "
                        + "reduciendo su coste en 3, pero no puede declarar ataque este turno.",
                List.of(tributeMaterials(3, false)),
                List.of(summonFromZone("tributeZone", CardFilter.creatures().withClasses("chamán", "ente", "ángel"), 3, true), summonedCannotAttack()))
                .onlyAllyTurn());
        // E1-B22 Unidad De Emergencia L1
        add("unidad de emergencia l1", new EffectScript("unidad-emergencia-l1:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Puedes subir hasta tantos materiales de esta carta a tu mano como tenga. Luego: reactiva esta carta.",
                List.of(materialsToHand(true), untapSelf())));
        // E1-G5 Salamandrake
        add("salamandrake", new EffectScript("salamandrake:tributada", Moment.ON_EVENT, false, "En Tributos",
                "(1) Si esta carta es tributada desde el tope de tu mazo y no controlas copias de esta carta: puedes colocar esta carta en tu retaguardia.",
                List.of(placeSelfOnField("tributeZone")))
                .when(all(new TributedFromDeck(null), new NoCopies()))
                .fromZone("tributeZone")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        for (String[] destroyer : List.of(new String[]{"salamandrake", "Salamandrake", "3"}, new String[]{"salamandra dracónica", "Salamandra Dracónica", "3"},
                new String[]{"dragaour", "Dragaour", "5"})) {
            int max = Integer.parseInt(destroyer[2]);
            inherit(destroyer[0], new EffectScript(destroyer[0] + ":hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                    "Heredado de " + destroyer[1] + ": elige 1 enemigo de fuerza " + max + " o menos; destrúyelo.",
                    List.of(chooseEnemy(1, enemy.withMaxStrength(max))), List.of(destroyChosen())));
        }
        // E1-P22 Fereteus (Celeridad: keyword)
        add("fereteus", new EffectScript("fereteus:en-mano", Moment.ACTIVATED, false, "Turno Aliado / En Mano",
                "(1) Si no controlas copias de esta carta: puedes invocar esta carta reduciendo su coste en X, donde X es la suma del nivel de todos "
                        + "tus aliados Violeta, [espectro] o [fauna].",
                List.of(summonSelf("hand", new Amount.LevelsOnField(CardFilter.colors("P").withClasses("espectro", "fauna")), false)))
                .when(new NoCopies())
                .fromZone("hand")
                .onlyAllyTurn()
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-R10 Martillo Frontal
        add("martillo frontal", new EffectScript("martillo-frontal:al-final-del-ataque", Moment.ON_ATTACK_END, false, AL_FINAL,
                "Una vez por turno: puedes invocar 1 [guerrero] o [retador] nivel 1 de tu mano reduciendo su coste en 2.",
                List.of(summonFromZone("hand", CardFilter.creatures().withClasses("guerrero", "retador").withLevel(1), 2)))
                .limited(Limit.ONCE_PER_TURN));
        // E1-B4 Cazador K3
        add("cazador k3", new EffectScript("cazador-k3:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Mira las primeras 5 cartas de tu mazo: de ellas toma hasta 3 cartas Azules o [autómata]. Envía el resto al fondo de tu mazo. "
                        + "Luego: tributa 2 cartas de tu mano.",
                List.of(lookTop(5), takeFromLookedRestToBottom(3, CardFilter.colors("B").withClasses("autómata")), tributeFromHand(2))));
        // E1-P11 Sombra Errante
        add("sombra errante", new EffectScript("sombra-errante:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                "(1) Puedes tributar 1 carta de tu mano para invocar 1 [espectro] nivel 1 de tus tributos sin pagar su coste.",
                List.of(new IfPossible(), tributeFromHand(1),
                        summonFromZone("tributeZone", CardFilter.creatures().withClasses("espectro").withLevel(1), FREE)))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-B10 Phonia
        add("phonia",
                new EffectScript("phonia:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes tributar 1 material de esta carta; coloca 1 criatura Azul nivel 1 de los materiales de esta carta en tu retaguardia.",
                        List.of(tributeMaterials(1, false)), List.of(materialToBack(CardFilter.colors("B").withLevel(1)))),
                new EffectScript("phonia:en-tributos", Moment.ON_EVENT, false, "En Tributos",
                        "(1) Si reduces a 0 la fuerza de algún enemigo: puedes añadir esta carta como material a 1 aliado.",
                        List.of(new PickAlly(), attachSelfToSubject()))
                        .when(new YouReducedStrength(0, false))
                        .fromZone("tributeZone")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-B13 SP 7H
        add("sp 7h", new EffectScript("sp-7h:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Si tienes 0 o más gemas: puedes tributar 1 material de esta carta; esta carta declara ataque y gana Alcance hasta el final del turno.",
                List.of(tributeMaterials(1, false)), List.of(gainUntilEndOfTurn("Alcance"), new DeclareAttack()))
                .when(new GemsAtLeast(0)));
        // E1-B16 Vigilante De La Noche Cósmica
        continuous("vigilante de la noche cósmica", new Continuous(TODOS, "Esta carta gana fuerza 6 mientras esté en tus vidas.", false,
                new Always(), 6, List.of(), null, "lifeStack"));
        add("vigilante de la noche cósmica", new EffectScript("vigilante-noche-cosmica:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes tributar 3 materiales de esta carta; coloca esta carta al tope de tus vidas bocabajo.",
                List.of(tributeMaterials(3, false)), List.of(selfToLife())));
        // E1-G8 Bestia Del Inframagma
        add("bestia del inframagma", new EffectScript("bestia-inframagma:al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Tributa 3 cartas del tope de tu mazo.",
                List.of(tributeTop(3))));
        inherit("bestia del inframagma", new EffectScript("bestia-inframagma:hereda-en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                "Heredado de Bestia Del Inframagma: elige 1 enemigo de fuerza 5 o menor; destrúyelo.",
                List.of(chooseEnemy(1, enemy.withMaxStrength(5))), List.of(destroyChosen())));
        // E1-G25 Tributo Del Bosque: "si esta carta es removida de tus vidas" (a utility revealed as a life goes to the hand)
        add("tributo del bosque", new EffectScript("tributo-del-bosque:removida-de-vidas", Moment.ON_EVENT, true, "Vidas",
                        "Si esta carta es removida de tus vidas: elige 1 enemigo de fuerza 9 o menos; agótalo.",
                        List.of(chooseEnemy(1, enemy.withMaxStrength(9))), List.of(tapChosen()))
                        .when(new ThisLeftLives())
                        .fromZone("hand"),
                new EffectScript("tributo-del-bosque:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Elige 1 enemigo agotado de fuerza 6 o menos; súbelo a la mano. Si algún enemigo fue removido por este efecto: puedes invocar "
                        + "1 criatura Verde de tu mano reduciendo su coste en 1.",
                List.of(chooseEnemy(1, enemy.withMaxStrength(6).exhausted())),
                List.of(new BounceChosen(), new IfDone(2), new MayDo("¿Invocas 1 criatura Verde de tu mano reduciendo su coste en 1?", 1),
                        summonFromZone("hand", CardFilter.creatures().withColors("G"), 1))));
        // E1-R14 Arquero Mítico Errante
        add("arquero mítico errante", new EffectScript("arquero-mitico:en-mano", Moment.ON_EVENT, false, "En Mano",
                "(1) Si un aliado declara ataque o bloqueo, y no controlas otras criaturas: puedes tributar esta carta; dicho aliado gana fuerza 3 "
                        + "hasta el final del combate.",
                List.of(sendSelfTo("tributeZone")), List.of(new SubjectFromTrigger(), gainStrengthUntil(3, "attack")))
                .when(new LoneAllyAttacksOrBlocks())
                .fromZone("hand")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-R24 Tácticas De Guerra (utility): it stays on the field, with a slow effect from there
        add("tácticas de guerra",
                new EffectScript("tacticas-de-guerra:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Tributa 2 cartas del tope de tu mazo. Luego: toma 1 carta Roja de tus tributos y coloca esta carta agotada en tu retaguardia.",
                        List.of(tributeTop(2), takeFromZone("tributeZone", CardFilter.colors("R"), 1, false), new KeepOnField(true))),
                new EffectScript("tacticas-de-guerra:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                        "Puedes enviar esta carta activa a tu descarte para invocar 1 criatura Roja de tu mano reduciendo su coste en 1.",
                        List.of(new IfPossible(), sendSelfFromFieldTo("discardPile"), summonFromZone("hand", CardFilter.creatures().withColors("R"), 1)))
                        .when(new Active())
                        .onlyAllyTurn());
        // E1-G14 Celeste (Asalto: keyword)
        add("celeste", new EffectScript("celeste:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION + " por efecto",
                "Tributa 1 carta de tu mano y elige 1 enemigo agotado de fuerza 7 o menos; súbelo a la mano.",
                List.of(tributeFromHand(1), chooseEnemy(1, enemy.withMaxStrength(7).exhausted())), List.of(new BounceChosen()))
                .when(new SummonedByEffect()));
        // E1-P15 Devorador De Bestias (Celeridad: keyword)
        add("devorador de bestias", new EffectScript("devorador-de-bestias:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes sacrificar 1 aliado Violeta nivel 3 que no sea una copia de esta carta para ganar 2 gemas.",
                List.of(new IfPossible(), sacrificeAllyWhere(CardFilter.creatures().withColors("P").withLevel(3)
                        .notNamed("Devorador De Bestias")), gainGems(2))));
        // E1-P25 Ritual De Sacrificio (utility)
        add("ritual de sacrificio",
                new EffectScript("ritual-de-sacrificio:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Sacrifica 1 aliado y elige 1 enemigo nivel 2 o menor; destrúyelo.",
                        List.of(sacrificeAlly(), chooseEnemy(1, CardFilter.any().withMaxLevel(2))), List.of(destroyChosen())),
                new EffectScript("ritual-de-sacrificio:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "(1) Si algún aliado es destruido por el oponente, excepto como atacante: puedes enviar esta carta a tu descarte y elegir "
                                + "1 enemigo nivel 2 o menor; destrúyelo.",
                        List.of(sendSelfTo("discardPile"), chooseEnemy(1, CardFilter.any().withMaxLevel(2))), List.of(destroyChosen()))
                        .when(new AllyDestroyed(CardFilter.any(), true, true))
                        .fromZone("hand")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-B6 Groll Vandálico
        add("groll vandálico", new EffectScript("groll-vandalico:en-mano", Moment.ON_EVENT, false, "En Mano",
                "(1) Si alguna carta es removida de tus vidas: puedes invocar esta carta en tu línea frontal. Si tienes 3 o menos vidas: reduce en 1 su coste.",
                List.of(summonSelf("hand", new Amount.IfThen(new LivesAtMost(false, 3), Amount.fixed(1), Amount.fixed(0)), true)))
                .when(new LifeRemoved())
                .fromZone("hand")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-R5 Escudo De Primera Línea (Protección [caballero]: a replacement the engine runs, see Removals.replacementOptions)
        continuous("escudo de primera línea", new Continuous("Turno Oponente",
                "Mientras controles 3 o más aliados Rojos: esta carta en tu línea frontal gana fuerza 4 y tiene Protección [caballero].", false,
                all(not(new YourTurn()), new InFrontLine(), new CountOnField(CardFilter.creatures().withColors("R"), 3, null)),
                4, List.of("Protección [caballero]")));
        // E1-R18 Guardian Imperial
        add("guardian imperial",
                new EffectScript("guardian-imperial:turno-oponente", Moment.ON_EVENT, true, "Turno Oponente",
                        "Una vez por turno: si esta carta es agotada y tienes menos vidas que tu oponente, reactiva esta carta.",
                        List.of(untapSelf()))
                        .when(all(not(new YourTurn()), new ThisExhausted(), new FewerLivesThanOpponent()))
                        .limited(Limit.ONCE_PER_TURN),
                new EffectScript("guardian-imperial:turno-aliado", Moment.ON_EVENT, true, "Turno Aliado",
                        "Al inicio del turno: gana 1 gema.",
                        List.of(gainGems(1)))
                        .when(new TurnStarts(true))
                        .onlyAllyTurn());
        // E1-G3 Salamandra Dracónica (Asalto: keyword)
        add("salamandra dracónica", new EffectScript("salamandra-draconica:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Elige 1 enemigo de fuerza 3 o menos; destrúyelo.",
                List.of(chooseEnemy(1, enemy.withMaxStrength(3))), List.of(destroyChosen())));
        // E1-G15 Dafleirk (Asalto: keyword)
        add("dafleirk", new EffectScript("dafleirk:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Elige 1 enemigo de fuerza 7 o menos; destrúyelo.",
                List.of(chooseEnemy(1, enemy.withMaxStrength(7))), List.of(destroyChosen())));
        // E1-P9 Ciervo Tejedor
        add("ciervo tejedor", new EffectScript("ciervo-tejedor:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Si controlas al menos 3 estructuras: puedes invocar 1 criatura Violeta [bestia] o [fauna] nivel 1 de tus tributos reduciendo en 1 su coste.",
                List.of(summonFromZone("tributeZone", CardFilter.creatures().withLevel(1).colorAndClass("P", "bestia", "fauna"), 1)))
                .when(new CountOnField(new CardFilter(List.of(), List.of(), "structure", null, null), 3, null)));
        // E1-P10 Zafrax (Provocar 2: combat rule)
        add("zafrax", new EffectScript("zafrax:todos-los-turnos", Moment.ON_EVENT, true, TODOS,
                "Una vez por turno: si esta carta destruye un enemigo por enfrentamiento, esta carta infringe daño directo.",
                List.of(damageBySelfStrength()))
                .when(new ThisDestroyedEnemy(true))
                .limited(Limit.ONCE_PER_TURN));
        // E1-G17 Ninfa Cantante De Dragones
        add("ninfa cantante de dragones", new EffectScript("ninfa-cantante:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Tributa las primeras 3 cartas de tu mazo. Luego: puedes invocar una criatura Verde de fuerza 7 o menos de tus tributos reduciendo en 2 su coste.",
                List.of(tributeTop(3), new MayDo("¿Invocas 1 criatura Verde de fuerza 7 o menos de tus tributos reduciendo su coste en 2?", 1),
                        summonFromZone("tributeZone", CardFilter.creatures().withColors("G").withMaxStrength(7), 2))));
        inherit("ninfa cantante de dragones", new EffectScript("ninfa-cantante:hereda-todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Heredado de Ninfa Cantante De Dragones: si algún aliado de fuerza 4 o mayor es invocado por efecto, puedes tributar el recurso de este "
                        + "efecto; reactiva esta carta.",
                List.of(tributeResource("Ninfa Cantante De Dragones")), List.of(untapSelf()))
                .when(new AllySummonedByEffect(CardFilter.creatures().withMinStrength(4))));
        // E1-R4 Legionario De Espadas Dobles
        aura("legionario de espadas dobles", new Aura("Turno Aliado", "Todos los aliados [armada] ganan fuerza 1.", true, null,
                CardFilter.creatures().withClasses("armada"), 1));
        // E1-R11 Alabarda De Primera Línea (Celeridad: keyword)
        add("alabarda de primera línea", new EffectScript("alabarda-primera-linea:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION + " por efecto",
                "Esta carta gana fuerza 2 y Enfrentar 3 hasta el final del turno.",
                List.of(gainStrength(2), gainUntilEndOfTurn("Enfrentar 3")))
                .when(new SummonedByEffect()));
        // E1-P5 Breo
        add("breo", new EffectScript("breo:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                "Elige 1 enemigo nivel 2 o menor; destrúyelo. Si tienes 20 o más tributos Violeta o [espectro]: en su lugar, puedes elegir 1 enemigo nivel 3.",
                List.of(chooseEnemyBy(1, new FilterBy(CardFilter.any(), null,
                        new Amount.IfThen(new CountInZone("tributeZone", CardFilter.colors("P").withClasses("espectro"), 20), Amount.fixed(3), Amount.fixed(2))), false)),
                List.of(destroyChosen())));
        // E1-P6 Caballero Derrotado: "puede ser tratada como Calavera Errante" (ALIASES)
        add("caballero derrotado",
                new EffectScript("caballero-derrotado:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "(1) Si algún aliado nivel 3 o mayor es destruido: puedes tributar 2 cartas de tu mano para invocar esta carta en tu línea frontal "
                                + "sin pagar su coste.",
                        List.of(new IfPossible(), tributeFromHand(2), summonSelf("hand", Amount.fixed(FREE), true)))
                        .when(new AllyDestroyed(CardFilter.any().withMinLevel(3), false, false))
                        .fromZone("hand")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("caballero-derrotado:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                        "Roba 1 carta.",
                        List.of(draw(1))));
        // E1-B21 Unidad H3 R0
        add("unidad h3 r0",
                new EffectScript("unidad-h3-r0:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Si esta carta es el único aliado en juego: esta carta gana fuerza 3 hasta el final del turno oponente.",
                        List.of(gainStrengthUntil(3, "nextTurn")))
                        .when(new CountOnField(CardFilter.creatures(), 1, 1)),
                new EffectScript("unidad-h3-r0:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                        "Una vez por turno: puedes tributar 2 materiales de esta carta; esta carta gana Letalidad 1 hasta el final del turno.",
                        List.of(tributeMaterials(2, false)), List.of(gainUntilEndOfTurn("Letalidad 1")))
                        .limited(Limit.ONCE_PER_TURN));
        // E1-R8 Explorador Temerario
        // "Turno Aliado: una vez por turno entre copias, cuando esta carta fuera a invocar por Ascenso, si controlas 3 o más aliados:
        // reduce el coste especial en 1" (a Reactivo–Modificador of the Ascenso it makes as the summoner)
        costModifier("explorador temerario", new CostModifier("Turno Aliado", CostModifiers.Event.SPECIAL_SUMMON, Set.of("promotion"),
                CostModifiers.Holder.SUMMONER, true, new CountOnField(CardFilter.creatures(), 3, null), Payment.none(), 1,
                Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-R16 Caballeria Frontal
        add("caballeria frontal", new EffectScript("caballeria-frontal:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "1 aliado nivel 1 puede invocar por Ascenso 1 criatura en tu mano reduciendo el coste especial en 1.",
                List.of(new SpecialFromZone("promotion", "hand", 1, "level:1", CardFilter.any()))));
        // E1-P4 Albigio (Provocar 4: combat rule)
        add("albigio", new EffectScript("albigio:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION + " por efecto",
                "Celeridad: puede subir a tu línea frontal.",
                List.of(moveSelfToFront()))
                .when(all(new SummonedByEffect(), not(new InFrontLine()))));
        // E1-P8 Necroporx: the level goes up by 1 for each 2 copies of it in the Tributos
        add("necroporx", new EffectScript("necroporx:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION + " por efecto",
                "Puedes invocar 1 criatura Violeta nivel 1 de tus tributos sin pagar su coste. Aumenta en 1 el nivel por cada 2 copias de esta "
                        + "carta en tus tributos.",
                List.of(summonFromZoneBy("tributeZone", new FilterBy(CardFilter.creatures().withColors("P"),
                        new Amount.Scaled(new Amount.InZone("tributeZone", CardFilter.any().named("Necroporx")), 2, 1), null), FREE)))
                .when(new DestroyedByEffect()));
        // E1-G6 Janne
        inherit("janne", new EffectScript("janne:hereda-al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Heredado de Janne: puedes colocar 1 criatura Verde Iniciador de tu mano en tu retaguardia. Al final del turno súbela a tu mano.",
                List.of(new PlaceFromHandUntilTurnEnd(CardFilter.creatures().withColors("G").starter()))));
        add("janne", new EffectScript("janne:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Mira las primeras 4 cartas de tu mazo: de ellas toma 1 carta Verde o Blanca. Envía el resto al fondo de tu mazo. Luego: tributa 1 carta "
                        + "de tu mano.",
                List.of(lookTop(4), takeFromLookedRestToBottom(1, CardFilter.colors("G", "W")), tributeFromHand(1))));
        // E1-R3 Peón De Los Leones Dorados
        add("peón de los leones dorados", new EffectScript("peon-leones-dorados:al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Una vez por turno: si tienes menos vidas que tu oponente, reactiva esta carta.",
                List.of(untapSelf()))
                .when(new FewerLivesThanOpponent())
                .limited(Limit.ONCE_PER_TURN));
        // E1-G7 Dracoverdiris
        inherit("dracoverdiris", new EffectScript("dracoverdiris:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Dracoverdiris: una vez por turno, puedes colocar 1 carta de tu mano al tope de tu mazo. Luego: tributa 3 cartas del tope de tu mazo.",
                List.of(toDeckTop("hand", 1, true), tributeTop(3)))
                .limited(Limit.ONCE_PER_TURN));
        // E1-G22 Uzume
        add("uzume",
                new EffectScript("uzume:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                        "Una vez por turno: si esta carta es agotada, puedes agotar el enemigo activo de menor fuerza.",
                        List.of(new ChooseWeakestEnemy(enemy.active()), tapChosen()))
                        .when(new ThisExhausted())
                        .limited(Limit.ONCE_PER_TURN),
                new EffectScript("uzume:final-del-turno", Moment.ON_EVENT, false, "Turno Aliado",
                        "Al final del turno: puedes enviar 5 de tus tributos Verdes o [shogún] al fondo de tu mazo y elegir 1 enemigo; este no se puede "
                                + "reactivar durante el próximo turno oponente.",
                        List.of(sendFromZoneToDeckBottom("tributeZone", 5, CardFilter.colors("G").withClasses("shogún")), chooseEnemy(1, enemy)),
                        List.of(new NoUntapChosen(true)))
                        .when(new TurnEnds())
                        .onlyAllyTurn());
        // E1-R9 Antorcha Legionaria
        add("antorcha legionaria", new EffectScript("antorcha-legionaria:final-del-turno", Moment.ON_EVENT, false, "Turno Aliado",
                "Al final del turno, si esta carta está en tu línea frontal y controlas 3 o más [legionarios]: puedes invocar 1 [legionario] de tu mano "
                        + "reduciendo su coste en 1.",
                List.of(summonFromZone("hand", CardFilter.creatures().withClasses("legionario"), 1)))
                .when(all(new TurnEnds(), new InFrontLine(), new CountOnField(CardFilter.creatures().withClasses("legionario"), 3, null)))
                .onlyAllyTurn());
        // E1-B14 Proto Vigilante H2, E1-R7 Caballero Leal, E1-G10 Deriduragon and E1-G19 Hydra Colosal (inherited): REPLACEMENTS
        // E1-B17 Roto G0 (corrected 2026-10-30): the tribute is part of the resolution, then as many enemies as materials tributed
        add("roto g0",
                new EffectScript("roto-g0:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes tributar hasta 3 materiales de esta carta. Luego: la misma cantidad de veces elige un enemigo; reduce en 2 la fuerza "
                                + "del enemigo elegido.",
                        List.of(tributeMaterials(3, true), new ReduceRepeatedly(-1, 2, "turn"))),
                new EffectScript("roto-g0:al-final-del-ataque", Moment.ON_ATTACK_END, false, AL_FINAL,
                        "Puedes subir esta carta a tu mano para ganar 2 gemas.",
                        List.of(new IfPossible(), returnSelfToHand(), gainGems(2))));
        // E1-R12 Espadón Legionario (Enfrentar 3: combat rule)
        add("espadón legionario", new EffectScript("espadon-legionario:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Si controlas 4 o más criaturas: gana 1 gema.",
                List.of(gainGems(1)))
                .when(new CountOnField(CardFilter.creatures(), 4, null)));
        // E1-P7 Aullador
        add("aullador", new EffectScript("aullador:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                "Elige 1 aliado para que gane Provocar 3 hasta el final del turno.",
                List.of(new PickAlly(false, true), gainUntilEndOfTurn("Provocar 3"))));
        // E1-B09 SAR 2
        add("sar 2", new EffectScript("sar-2:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Puedes tributar 1 material de esta carta. Si lo haces, hasta 2 veces: puedes elegir 1 enemigo; reduce en 1 su fuerza hasta el final del ataque.",
                List.of(tributeMaterials(1, false)), List.of(new ReduceRepeatedly(2, 1, "attack"))));
        // E1-B25 Sobrecarga (utility)
        add("sobrecarga", playAtBlockStep("sobrecarga"),
                new EffectScript("sobrecarga:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "(1) Tributa 1 material nivel 3 o menor de un [autómata] o [mecha]; añádele esta carta a dicho aliado como material para que active "
                                + "1 efecto En Invocación del material tributado.",
                        List.of(new TributeAllyMaterialWhere(CardFilter.creatures().withClasses("autómata", "automata", "mecha"),
                                CardFilter.any().withMaxLevel(3))),
                        List.of(attachSelfToSubject(), new ActivateTributedSummonEffect()))
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-G19 Hydra Colosal (its inherited replacement is in REPLACEMENTS)
        add("hydra colosal",
                new EffectScript("hydra-colosal:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Tributa las primeras 3 cartas de tu mazo. Luego: elige 1 enemigo de fuerza 7 o menos; destrúyelo.",
                        List.of(tributeTop(3), chooseEnemy(1, enemy.withMaxStrength(7)), destroyChosen())),
                new EffectScript("hydra-colosal:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                        "Esta carta puede invocar por Evolución una criatura en tus tributos reduciendo su coste especial en 1.",
                        List.of(new SpecialFromZone("evolution", "tributeZone", 1, "self", CardFilter.creatures()))));
        // E1-R19 Dante Di Castirion (Letalidad 1: combat rule)
        add("dante di castirion", new EffectScript("dante-castirion:en-mano", Moment.ACTIVATED, false, "Turno Aliado / En Mano",
                "(1) Puedes tributar 3 o más aliados Rojos que sumen 5 o más en nivel para invocar esta carta en tu línea frontal reduciendo su coste en 3.",
                List.of(new TributeAlliesLevelSum(3, 5, CardFilter.creatures().withColors("R"))),
                List.of(summonSelf("hand", Amount.fixed(3), true)))
                .fromZone("hand")
                .onlyAllyTurn()
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-R21 Imperial De La Llama Eterna (Perforante: combat rule)
        add("imperial de la llama eterna", new EffectScript("imperial-llama-eterna:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Tantas veces como quieras: envía 10 tributos Rojos o [retador] al fondo de tu mazo; esta carta gana Letalidad 1 hasta el final del ataque.",
                List.of(new TributesToDeckBottomForKeyword(10, CardFilter.colors("R").withClasses("retador"), "Letalidad"))));
        // E1-W01…W05: White utilities with a Definitive effect (only 1 per turn, whichever card)
        add("nexo del inframundo material", new EffectScript("nexo-inframundo:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Efecto Definitivo. Elige 1 aliado; mira las primeras 4 cartas de tu mazo: de ellas toma hasta 1 carta que comparta tipo con el "
                        + "aliado elegido. Envía el resto al fondo de tu mazo en orden aleatorio.",
                List.of(new PickAlly(false, true)), List.of(lookTop(4), new TakeFromLookedLikeSubject(1)))
                .limited(Limit.DEFINITIVE));
        add("última línea de defensa", new EffectScript("ultima-linea:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Efecto Definitivo. Si tienes 2 o menos vidas bocabajo: la próxima vez que un aliado inflija daño directo por combate este turno, "
                        + "inflige daño 1 vez más. Si lo hace: termina tu turno.",
                List.of(new ExtraCombatHitThenEndTurn()))
                .when(new FaceDownLivesAtMost(2))
                .limited(Limit.DEFINITIVE));
        add("mandato imperial", new EffectScript("mandato-imperial:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Efecto Definitivo. Si no hay enemigos en la línea frontal: el oponente elige 1 criatura en su retaguardia para moverla a su línea frontal.",
                List.of(new OpponentMovesOneToFront()))
                .when(new NoEnemiesInFrontLine())
                .limited(Limit.DEFINITIVE));
        add("llegada del ciclo del caos", new EffectScript("llegada-ciclo-caos:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Efecto Definitivo. Mira las primeras 7 cartas de tu mazo: de ellas descarta al menos 5 bocabajo. Coloca el resto al tope de tu "
                        + "mazo en cualquier orden. Luego: roba 1 carta.",
                List.of(lookTop(7), discardLookedRestTop(5), draw(1)))
                .limited(Limit.DEFINITIVE));
        add("prisma de la grieta dimensional", new EffectScript("prisma-grieta:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Efecto Definitivo. Ambos jugadores envían las cartas de su mano al fondo de su mazo en orden aleatorio. Luego: ambos roban "
                        + "cartas igual a la cantidad de vidas que tiene su oponente.",
                List.of(new HandsToBottomDrawOpponentLives()))
                .limited(Limit.DEFINITIVE));
        // E1.1-G14 Fuego Perforante (utility): "Solo puedes activar 1 efecto de «Fuego Perforante» por turno"
        CardFilter dragonTributes = CardFilter.any().withClasses("dragón");
        add("fuego perforante",
                new EffectScript("fuego-perforante:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Tributa esta carta; roba 1 carta.",
                        List.of(sendSelfFromFieldTo("tributeZone")), List.of(draw(1)))
                        .limited(Limit.ONE_EFFECT_OF_NAME_PER_TURN),
                new EffectScript("fuego-perforante:en-tributos", Moment.ON_EVENT, false, "En Tributos",
                        "Si algún aliado [dragón] inflige daño directo: puedes enviar al fondo de tu mazo 10 de tus tributos [dragón], incluida esta "
                                + "carta; dicho aliado inflige daño 1 vez más.",
                        List.of(sendIncludingThisToDeckBottom("tributeZone", 10, dragonTributes)), List.of(new ExtraHit()))
                        .when(new AllyDealtDirectDamage(CardFilter.creatures().withClasses("dragón")))
                        .fromZone("tributeZone")
                        .limited(Limit.ONE_EFFECT_OF_NAME_PER_TURN));
    }

    static {
        // E1.1 (first subedition). Reprints keep the script registered under their name above.
        CardFilter enemy = CardFilter.creatures();
        CardFilter dragons = CardFilter.any().withClasses("dragón");
        // E1.1-B2 Pysche
        add("pysche", new EffectScript("pysche:turno-aliado", Moment.ON_EVENT, false, "Turno Aliado",
                "Si algún aliado es invocado por efecto mientras esta carta está en tu línea frontal: puedes mirar las primeras 3 cartas "
                        + "de tu mazo: de ellas tributa 1 y envía el resto al fondo de tu mazo en cualquier orden.",
                List.of(lookTop(3), tributeFromLookedRestToBottom(1)))
                .when(all(new AllySummonedByEffect(CardFilter.any()), new InFrontLine()))
                .onlyAllyTurn()
                .limited(Limit.ONCE_PER_TURN));
        // E1.1-B4 Forol
        add("forol", new EffectScript("forol:tributada-como-material", Moment.ON_EVENT, false, "En Tributos",
                "Si esta carta es tributada como material de un aliado nivel 3 o mayor: puedes tributar 1 carta de tu mano para tomar "
                        + "1 carta Azul de tus tributos.",
                List.of(new IfPossible(), tributeFromHand(1), takeFromZone("tributeZone", CardFilter.colors("B"), 1, false)))
                .when(new TributedAsMaterial(3))
                .fromZone("tributeZone"));
        // E1.1-B7 Spentalos
        add("spentalos", new EffectScript("spentalos:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes tributar 2 materiales de esta carta para ganar 1 gema.",
                List.of(new IfPossible(), tributeMaterials(2, false), gainGems(1))));
        // E1.1-B14 Composición Refractaria
        add("composición refractaria",
                new EffectScript("composicion-refractaria:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "(1) Si tienes 5 o menos vidas: coloca la carta del tope de tu mazo al tope de tus vidas bocabajo.",
                        List.of(deckTopToLife()))
                        .when(new LivesAtMost(false, 5))
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("composicion-refractaria:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "Si alguna carta es removida de tus vidas: puedes tributar 2 materiales de algún aliado para jugar esta carta sin pagar su coste.",
                        List.of(new IfPossible(), new PickAllyWithMaterials(2), tributeSubjectMaterials(2), playSelfFree()))
                        .when(new LifeRemoved())
                        .fromZone("hand"));
        // E1.1-G2 Dragón Goloso
        add("dragón goloso", new EffectScript("dragon-goloso:al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Elige 1 enemigo de fuerza 4 o menos; destrúyelo.",
                List.of(chooseEnemy(1, enemy.withMaxStrength(4))), List.of(destroyChosen())));
        // E1.1-G5 Snadreik
        add("snadreik", new EffectScript("snadreik:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Una vez por turno: puedes enviar 5 de tus tributos [dragón] al fondo de tu mazo y elegir 1 enemigo de fuerza 5 o menos; destrúyelo.",
                List.of(sendFromZoneToDeckBottom("tributeZone", 5, dragons), chooseEnemy(1, enemy.withMaxStrength(5))),
                List.of(destroyChosen()))
                .limited(Limit.ONCE_PER_TURN));
        inherit("snadreik", new EffectScript("snadreik:hereda-en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                "Heredado de Snadreik: elige 1 enemigo de fuerza 5 o menos; destrúyelo.",
                List.of(chooseEnemy(1, enemy.withMaxStrength(5))), List.of(destroyChosen())));
        // E1.1-G3 Cría De Dragón Rúnico (its own "En Invocación" is not applied yet)
        inherit("cría de dragón rúnico", new EffectScript("cria-dragon-runico:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Cría De Dragón Rúnico: tributa 1 carta del tope de tu mazo.",
                List.of(tributeTop(1))));
        // E1.1-G4 Rosedrake (its own effect when tributed is not applied yet)
        inherit("rosedrake", new EffectScript("rosedrake:hereda-al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Heredado de Rosedrake: puedes tributar 1 carta de tu mano para tomar 1 carta Verde de tus tributos.",
                List.of(new IfPossible(), tributeFromHand(1), takeFromZone("tributeZone", CardFilter.colors("G"), 1, false))));
        // E1.1-G7 Wyverno Rapaz (its own effects are not applied yet)
        inherit("wyverno rapaz", new EffectScript("wyverno-rapaz:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Wyverno Rapaz: coloca hasta 1 carta de tu mano al tope de tu mazo. Luego: tributa 2 cartas del tope de tu mazo.",
                List.of(toDeckTop("hand", 1, true), tributeTop(2))));
        // E1.1-G9 Wyverno De Las Nubes (its own "En Invocación" is not applied yet)
        inherit("wyverno de las nubes", new EffectScript("wyverno-de-las-nubes:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Wyverno De Las Nubes: tributa 3 cartas del tope de tu mazo.",
                List.of(tributeTop(3))));
        // E1.1-P3 Krull
        add("krull", new EffectScript("krull:al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Tributa 1 carta de tu mano.",
                List.of(tributeFromHand(1))));
        // E1.1-P9 Aracnopanto
        add("aracnopanto",
                new EffectScript("aracnopanto:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes sacrificar 1 aliado para reactivar esta carta.",
                        List.of(new IfPossible(), sacrificeAlly(false), untapSelf())),
                new EffectScript("aracnopanto:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                        "Puedes invocar 1 [bestia] nivel 1 de tu descarte sin pagar su coste.",
                        List.of(summonFromZone("discardPile", CardFilter.creatures().withClasses("bestia").withLevel(1), FREE, false))));
        // E1.1-R1 Peón Experimentado
        add("peón experimentado",
                new EffectScript("peon-experimentado:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Tributa 2 cartas del tope de tu mazo. Luego: toma 1 carta de tus tributos.",
                        List.of(tributeTop(2), takeFromZone("tributeZone", CardFilter.any(), 1, false))),
                new EffectScript("peon-experimentado:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                        "Puedes tributar esta carta en tu línea frontal para invocar 1 criatura Roja nivel 2 de tu mano reduciendo su coste en 1.",
                        List.of(new IfPossible(), sendSelfFromFieldTo("tributeZone"),
                                summonFromZone("hand", CardFilter.creatures().withColors("R").withLevel(2), 1, false)))
                        .when(new InFrontLine())
                        .onlyAllyTurn());
        // E1.1-R2 Espada Ferrea
        add("espada ferrea", new EffectScript("espada-ferrea:en-mano", Moment.ON_EVENT, false, "Turno Aliado / En Mano",
                "Inmediatamente después de que un aliado sea invocado por efecto: puedes invocar esta carta en tu línea frontal pagando su coste.",
                List.of(summonSelf("hand", Amount.fixed(0), true)))
                .when(new Immediately(new AllySummonedByEffect(CardFilter.any())))
                .fromZone("hand")
                .onlyAllyTurn());
        // E1.1-R3 Peón De Los Leones De Fuego
        add("peón de los leones de fuego", new EffectScript("peon-leones-de-fuego:en-mano", Moment.ACTIVATED, false, "Turno Aliado / En Mano",
                "Si controlas algún [caballero] nivel 4 o mayor: puedes invocar esta carta en tu línea frontal pagando su coste.",
                List.of(summonSelf("hand", Amount.fixed(0), true)))
                .when(new CountOnField(CardFilter.creatures().withClasses("caballero").withMinLevel(4), 1, null))
                .fromZone("hand")
                .onlyAllyTurn());
        // E1.1-R7 Sereno Fronterizo
        add("sereno fronterizo", new EffectScript("sereno-fronterizo:final-del-turno", Moment.ON_EVENT, true, "Turno Aliado",
                "Al final del turno, si esta carta está en tu línea frontal: reactiva hasta 2 aliados.",
                List.of(untapAllies(2)))
                .when(all(new TurnEnds(), new InFrontLine()))
                .onlyAllyTurn());
        // E1.1-R8 Alabarda De Ala Blanca
        add("alabarda de ala blanca",
                new EffectScript("alabarda-ala-blanca:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Por efecto: puedes invocar 1 criatura Roja nivel 3 o menor de tu mano reduciendo su coste en 3. Si lo haces: mueve esta carta a tu línea frontal.",
                        List.of(summonFromZone("hand", CardFilter.creatures().withColors("R").withMaxLevel(3), 3, false), new IfDone(1),
                                moveSelfToFront()))
                        .when(new SummonedByEffect()),
                new EffectScript("alabarda-ala-blanca:al-final-del-ataque", Moment.ON_ATTACK_END, false, AL_FINAL,
                        "Puedes tributar esta carta para ganar 2 gemas.",
                        List.of(new IfPossible(), sendSelfFromFieldTo("tributeZone"), gainGems(2))));
        // E1.1-R10 Comandante Veterano (its "Turno Oponente" blocker swap: BLOCKER_SWAP, CombatRules.offerBlockerSwap)
        add("comandante veterano", new EffectScript("comandante-veterano:en-mano", Moment.ACTIVATED, false, "Turno Aliado / En Mano",
                "Si no controlas copias de esta carta y controlas algún [caballero] o [armada] nivel 4 o mayor: puedes invocar esta carta reduciendo su coste en 3.",
                List.of(summonSelf("hand", Amount.fixed(3), false)))
                .when(all(new NoCopies(), new CountOnField(CardFilter.creatures().withClasses("caballero", "armada").withMinLevel(4), 1, null)))
                .fromZone("hand")
                .onlyAllyTurn());
        // E1.1-R11 Establo (structure)
        CardFilter caballero = CardFilter.creatures().withClasses("caballero");
        add("establo",
                new EffectScript("establo:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Toma 1 carta Roja de tus tributos.",
                        List.of(takeFromZone("tributeZone", CardFilter.colors("R"), 1, false))),
                new EffectScript("establo:turno-aliado", Moment.ON_EVENT, false, "Turno Aliado",
                        "Si algún aliado [caballero] es invocado por efecto o por Ascenso: puedes agotar esta carta para ganar 1 gema.",
                        List.of(new IfPossible(), tapSelf(), gainGems(1)))
                        .when(any(new AllySummonedByEffect(caballero), new YouSpecialSummonedAs("promotion", caballero)))
                        .onlyAllyTurn());
        // E1.1-W5 Escudos Leales
        add("escudos leales",
                new EffectScript("escudos-leales:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Efecto Definitivo. Reactiva hasta 2 aliados.",
                        List.of(untapAllies(2)))
                        .limited(Limit.DEFINITIVE),
                new EffectScript("escudos-leales:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "Si no activaste algún Efecto Definitivo el turno anterior y algún enemigo declara ataque mientras tienes 2 o menos vidas: "
                                + "puedes jugar esta carta sin pagar su coste.",
                        List.of(playSelfFree()))
                        .when(all(new EnemyDeclaresAttack(), new LivesAtMost(false, 2), not(new UsedDefinitiveLastTurn())))
                        .fromZone("hand"));

        // ---- E1.1, second batch: new steps and conditions
        // E1.1-B1 Zamaranzas
        add("zamaranzas",
                new EffectScript("zamaranzas:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes añadir esta carta como material a 1 aliado [ente] nivel 3 o menor; activa 1 de sus efectos En Invocación.",
                        List.of(new PickAllyWhere(CardFilter.creatures().withClasses("ente").withMaxLevel(3)), attachSelfToSubject()),
                        List.of(new ActivateSubjectSummonEffect())),
                new EffectScript("zamaranzas:en-tributos", Moment.ACTIVATED, false, "Turno Aliado / En Tributos",
                        "(1) Puedes añadir 1 aliado como material a otro de nivel mayor para invocar esta carta sin pagar su coste.",
                        List.of(new IfPossible(), new PickAllyWithHigherAlly(), new AttachSubjectToHigherAlly(), summonSelfFree()))
                        .fromZone("tributeZone")
                        .onlyAllyTurn()
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1.1-B5 Soléo
        add("soléo", new EffectScript("soleo:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Puedes tributar hasta 2 materiales de esta carta: por cada material tributado elige 1 enemigo distinto; reduce en 2 su fuerza.",
                List.of(tributeMaterials(2, true), chooseEnemiesPerCount(enemy)), List.of(reduceChosen(2, false))));
        // E1.1-B9 Cragzum
        add("cragzum", new EffectScript("cragzum:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes tributar hasta tantos materiales de esta carta como tenga y elegir 1 enemigo; reduce en 1 su fuerza por cada material removido.",
                List.of(tributeMaterials(99, true), chooseEnemy(1, enemy)), List.of(reduceChosenPerCount(1))));
        // E1.1-B11 HE 110 (its "no puede ser objetivo" is a continuous effect, below)
        add("he 110", new EffectScript("he-110:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Una vez por turno: si alguna de tus vidas es removida: puedes tributar 2 materiales de esta carta; coloca la carta del tope de tu mazo al tope de tus vidas.",
                List.of(tributeMaterials(2, false)), List.of(deckTopToLife()))
                .when(new LifeRemoved())
                .limited(Limit.ONCE_PER_TURN));
        // E1.1-G1 Hatchkin
        add("hatchkin",
                new EffectScript("hatchkin:tributada", Moment.ON_EVENT, false, "En Tributos",
                        "(1) Si esta carta es tributada, excepto desde tu mano: puedes enviar 5 de tus tributos [dragón] al fondo de tu mazo para "
                                + "colocar esta carta en tu línea frontal agotada.",
                        List.of(new IfPossible(), sendFromZoneToDeckBottom("tributeZone", 5, dragons), placeSelfInFrontLine("tributeZone", true)))
                        .when(new ThisTributed(null, "hand", null))
                        .fromZone("tributeZone")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("hatchkin:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                        "(1) Si algún aliado es invocado: puedes añadirle esta carta como recurso para ganar 1 gema.",
                        List.of(new IfPossible(), new SubjectFromTrigger(), attachSelfAsResourceToSubject(), gainGems(1)))
                        .when(new AllySummoned(CardFilter.creatures()))
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1.1-G3 Cría De Dragón Rúnico: "Todos Los Turnos: cuando esta carta fuera a invocar por Evolución: puedes enviar otras 5 de tus
        // tributos [dragón] al fondo de tu mazo para reducir el coste especial en 1" (a Reactivo–Modificador, like Flambreik's)
        costModifier("cría de dragón rúnico", new CostModifier(TODOS, CostModifiers.Event.SPECIAL_SUMMON, Set.of("evolution"),
                CostModifiers.Holder.SUMMONER, false, null, Payment.tributesToDeckBottom(5, CardFilter.any().withClasses("dragón")), 1));
        // E1.1-G4 Rosedrake
        add("rosedrake", new EffectScript("rosedrake:tributada", Moment.ON_EVENT, false, "En Tributos",
                "(1) Si esta carta es tributada, excepto desde tu mano: puedes tributar 1 carta de tu mano para tomar 1 carta Verde de tus tributos.",
                List.of(new IfPossible(), tributeFromHand(1), takeFromZone("tributeZone", CardFilter.colors("G"), 1, false)))
                .when(new ThisTributed(null, "hand", null))
                .fromZone("tributeZone")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1.1-G6 Grod
        continuous("grod", new Continuous(TODOS, "Esta carta no puede declarar ataque.", false, new Always(), 0, List.of(EffectEngine.NO_ATTACK)));
        // E1.1-G7 Wyverno Rapaz (its reaction when it would evolve is not applied yet)
        add("wyverno rapaz", new EffectScript("wyverno-rapaz:tributada", Moment.ON_EVENT, true, "En Tributos",
                "(1) Si esta carta es tributada por efecto de 1 carta Verde, excepto desde tu mano: tributa 2 cartas del tope de tu mazo.",
                List.of(tributeTop(2)))
                .when(new ThisTributed(null, "hand", CardFilter.colors("G")))
                .fromZone("tributeZone")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1.1-G9 Wyverno De Las Nubes
        add("wyverno de las nubes", new EffectScript("wyverno-de-las-nubes:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Mira las primeras 3 cartas de tu mazo: envíalas al tope o al fondo de tu mazo. Luego: tributa 3 cartas del tope de tu mazo.",
                List.of(lookTop(3), new LookedToTopOrBottom(), tributeTop(3))));
        // E1.1-G12 Hebi
        add("hebi",
                new EffectScript("hebi:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes añadir hasta 1 criatura [hydra] o [dragón] de tus tributos como recurso de esta carta.",
                        List.of(addResourceFrom("tributeZone", CardFilter.creatures().withClasses("hydra", "dragón")))),
                new EffectScript("hebi:al-final-del-ataque", Moment.ON_ATTACK_END, false, AL_FINAL,
                        "Una vez por turno: puedes tributar 2 recursos [hydra] o [dragón] de esta carta para reactivarla.",
                        List.of(new IfPossible(), tributeResources(2, CardFilter.any().withClasses("hydra", "dragón")), untapSelf()))
                        .limited(Limit.ONCE_PER_TURN));
        // E1.1-P1 Niobats
        add("niobats", new EffectScript("niobats:tributada", Moment.ON_EVENT, false, "En Tributos",
                "(1) Si esta carta es tributada por efecto de 1 [bestia]: puedes tributar 1 carta de tu mano para tomar 1 carta Violeta de tus tributos.",
                List.of(new IfPossible(), tributeFromHand(1), takeFromZone("tributeZone", CardFilter.colors("P"), 1, false)))
                .when(new ThisTributed(null, null, CardFilter.any().withClasses("bestia")))
                .fromZone("tributeZone")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1.1-P2 Jábalix (Iniciador)
        add("jábalix", new EffectScript("jabalix:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Mira las primeras 3 cartas de tu mazo: de ellas toma hasta 1 carta Violeta, Blanca o [mutante]. Envía el resto al fondo de tu mazo. "
                        + "Luego: tributa 1 carta de tu mano. Por cada copia de esta carta en tus tributos: mira 1 carta más con este efecto.",
                List.of(lookTopBy(new Amount.Scaled(new Amount.InZone("tributeZone", CardFilter.any().named("Jábalix")), 1, 3)),
                        takeFromLookedRestToBottom(1, CardFilter.colors("P", "W").withClasses("mutante")), tributeFromHand(1))));
        // E1.1-P4 Cofre Mímico
        add("cofre mímico",
                new EffectScript("cofre-mimico:tributada", Moment.ON_EVENT, false, "En Tributos",
                        "Si esta carta es tributada desde tus vidas: puedes invocarla en tu línea frontal.",
                        List.of(summonSelf("tributeZone", Amount.fixed(0), true)))
                        .when(new ThisTributed("lifeStack", null, null))
                        .fromZone("tributeZone"),
                new EffectScript("cofre-mimico:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes elegir 1 enemigo nivel 1; destrúyelo.",
                        List.of(chooseEnemy(1, enemy.withLevel(1), true)), List.of(destroyChosen())));
        // E1.1-P5 Lomo Plateado
        add("lomo plateado", new EffectScript("lomo-plateado:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                "Una vez por turno: puedes sacrificar 1 otro aliado para que esta carta gane fuerza 3 hasta el final del turno. Si lo haces: puedes "
                        + "mover esta carta a tu línea frontal.",
                List.of(new IfPossible(), sacrificeAlly(false, true), new SubjectIsThisCard(), gainStrength(3),
                        new MayDo("¿Mueves esta carta a tu línea frontal?", 1), moveSelfToFront()))
                .onlyAllyTurn()
                .limited(Limit.ONCE_PER_TURN));
        // E1.1-P6 Xiromorfo (Celeridad: keyword)
        Amount xiromorfoCopies = new Amount.InZone("tributeZone", CardFilter.any().named("Xiromorfo"));
        continuous("xiromorfo", new Continuous(TODOS, "Esta carta gana 1 nivel y fuerza 3 por cada copia de esta carta en tus tributos.", false,
                new Always(), 0, List.of(), new Amount.Times(xiromorfoCopies, 3), "field", xiromorfoCopies));
        // E1.1-R15 A La Carga!
        add("a la carga!", new EffectScript("a-la-carga:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Si tienes al menos 7 aliados Rojos: todos los aliados ganan Furia y Perforante hasta el final del turno.",
                List.of(new AlliesGain(CardFilter.creatures(), "Furia", "turn"), new AlliesGain(CardFilter.creatures(), "Perforante", "turn")))
                .when(new CountOnField(CardFilter.creatures().withColors("R"), 7, null)));
        // E1.1-P14 Festin
        add("festin", new EffectScript("festin:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "(1) Si tienes 0 o más gemas: puedes sacrificar 1 aliado para que otro a lo más 1 nivel mayor declare ataque.",
                List.of(new MayDo("¿Sacrificas 1 aliado para que otro de, a lo más, 1 nivel mayor declare ataque?", 3), sacrificeAlly(false),
                        new PickAllyUpToSacrificedLevel(1), new DeclareAttack(false)))
                .when(new GemsAtLeast(0))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1.1-G7 Wyverno Rapaz: "cuando esta carta fuera a invocar por Evolución: puedes añadir 1 de tus tributos de fuerza 6 o menos
        // como recurso de esta carta" (a Reacción before the summon: its Recursos pass on to the creature it summons)
        costModifier("wyverno rapaz", new CostModifier(TODOS, CostModifiers.Event.SPECIAL_SUMMON, Set.of("evolution"), CostModifiers.Holder.SUMMONER,
                false, null, Payment.tributeAsResource(CardFilter.creatures().withMaxStrength(6)), 0));
        // E1.1-W1 Hechizo Premonitorio
        add("hechizo premonitorio", new EffectScript("hechizo-premonitorio:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Efecto Definitivo. Mira las primeras 2 cartas de tu mazo: devuélvelas al tope o al fondo de tu mazo. Luego: roba 1 carta.",
                List.of(lookTop(2), new LookedToTopOrBottom(), draw(1)))
                .limited(Limit.DEFINITIVE));
        // ---- E1.1, third batch: rulings of 2026-10-29 and new mechanics
        // E1.1-B3 Remnis: "al inicio del turno" is any turn's
        add("remnis", new EffectScript("remnis:en-tributos", Moment.ON_EVENT, false, "En Tributos",
                "(1) Al inicio del turno, si el oponente no controla criaturas en su línea frontal: puedes añadir esta carta como material a 1 aliado "
                        + "Azul. Si lo haces: dicho aliado puede invocar por Materialización 1 criatura en tu mano o tributos reduciendo el coste especial en 1.",
                List.of(new PickAllyWhere(CardFilter.creatures().withColors("B")), attachSelfToSubject(), new IfDone(1),
                        new SpecialFromZone("materialization", "hand,tributeZone", 1, "subject", CardFilter.any())))
                .when(all(new TurnStarts(false), new NoEnemiesInFrontLine()))
                .fromZone("tributeZone")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1.1-B11 HE 110: "no puede ser objetivo de efectos de cartas oponentes nivel 4 o menor"
        continuous("he 110", new Continuous(TODOS, "Esta carta no puede ser objetivo de efectos de cartas oponentes nivel 4 o menor.", false,
                new Always(), 0, List.of(EffectEngine.IMMUNE + " 4")));
        // E1.1-B12 Lesvik (Letalidad 1: keyword)
        add("lesvik", new EffectScript("lesvik:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Puedes tributar 2 materiales de esta carta para elegir 1 enemigo; reduce en 8 su fuerza. Si reduces su fuerza a 0: salta el paso de bloqueo.",
                List.of(tributeMaterials(2, false), chooseEnemy(1, enemy)),
                List.of(reduceChosen(8, false), new IfChosenGone(1), new SkipBlockStep())));
        // E1.1-G11 Lectranatus
        add("lectranatus", new EffectScript("lectranatus:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Elige hasta 1 enemigo de fuerza 9 o menos; destrúyelo.",
                List.of(chooseEnemy(1, enemy.withMaxStrength(9), true)), List.of(destroyChosen())));
        // "Todos Los Turnos: los enemigos de fuerza 3 o menos no pueden declarar ataque" (continuous, on the opponent's creatures)
        aura("lectranatus", new Aura(TODOS, "Los enemigos de fuerza 3 o menos no pueden declarar ataque.", false, null,
                CardFilter.creatures().withMaxStrength(3), 0, true, List.of(EffectEngine.NO_ATTACK)));
        inherit("lectranatus", new EffectScript("lectranatus:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Lectranatus: elige 1 enemigo de fuerza 9 o menos; este no puede agotarse hasta el final del turno.",
                List.of(chooseEnemy(1, enemy.withMaxStrength(9))), List.of(chosenCannotBeExhausted("turn"))));
        // E1.1-P8 Kagravú: yours or an enemy's (both in the Línea frontal); a sacrificed enemy goes to its owner's Tributos
        add("kagravú",
                new EffectScript("kagravu:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes elegir 1 criatura nivel 1 o menor en el campo. Si lo haces: sacrifícala.",
                        List.of(chooseCreatureOnField(CardFilter.creatures().withMaxLevel(1), true), new IfDone(1), sacrificeChosen())),
                new EffectScript("kagravu:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                        "Una vez por turno: si destruyes alguna criatura por efecto: puedes invocar 1 criatura Violeta nivel 1 de tus tributos o descarte "
                                + "reduciendo su coste en 1.",
                        List.of(summonFromZone("tributeZone,discardPile", CardFilter.creatures().withColors("P").withLevel(1), 1, false)))
                        .when(new YouDestroyedCreature(true))
                        .limited(Limit.ONCE_PER_TURN));
        // E1.1-P11 Hominox (Provocar 4: keyword): a summon by effect from the Tributos, so to the Retaguardia
        add("hominox", new EffectScript("hominox:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Si esta carta destruye un enemigo: puedes invocar 1 criatura [mutante] de tus tributos de nivel igual o menor a la cantidad de "
                        + "copias de esta carta en tus tributos.",
                List.of(summonFromZoneBy("tributeZone", new FilterBy(CardFilter.creatures().withClasses("mutante"), null,
                        new Amount.InZone("tributeZone", CardFilter.any().named("Hominox"))), 0)))
                .when(new ThisDestroyedEnemy(false)));
        // E1.1-R4 Escudo Jurado
        continuous("escudo jurado", new Continuous(TODOS,
                "Mientras controles algún aliado nivel 4 o mayor: esta carta no puede ser objetivo de efectos oponentes.", false,
                new CountOnField(CardFilter.creatures().withMinLevel(4), 1, null), 0, List.of(EffectEngine.IMMUNE)));
        // E1.1-R9 Principe Charles Di Castirion (its attack before being ascended: ATTACK_BEFORE_ASCENDED)
        add("principe charles di castirion", new EffectScript("principe-charles:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Todos tus otros aliados [caballero] o [armada] ganan Reactivar hasta el final del turno oponente.",
                List.of(new AlliesGain(CardFilter.creatures().withClasses("caballero", "armada"), "Reactivar", "nextTurn", true))));
        // E1.1-R13 Valor Del Heroe: no duration written means "por el turno"
        add("valor del heroe",
                new EffectScript("valor-del-heroe:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Elige 1 aliado para que gane fuerza 3 y Furia 1 por el turno.",
                        List.of(new PickAlly(false, true), gainStrength(3), gainUntilEndOfTurn("Furia 1"))),
                new EffectScript("valor-del-heroe:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "(1) Si invocas algún [caballero] por efecto: puedes jugar esta carta sin pagar su coste.",
                        List.of(playSelfFree()))
                        .when(new AllySummonedByEffect(CardFilter.creatures().withClasses("caballero")))
                        .fromZone("hand")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1.1-W2 Barrera Refractora
        add("barrera refractora", new EffectScript("barrera-refractora:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Efecto Definitivo. Elige 1 aliado: tributa tantas cartas de tu mano como nivel tenga; dicho aliado no puede ser removido por efectos "
                        + "oponentes hasta el final del turno oponente.",
                List.of(new PickAlly(false, true), tributeFromHandAsSubjectLevel()), List.of(gainUntilNextTurn(EffectEngine.UNREMOVABLE)))
                .limited(Limit.DEFINITIVE));
        // E1.1-W4 Emboscada Al Carromato: until the end of the turn or of its next attack, whichever comes first
        add("emboscada al carromato", new EffectScript("emboscada-al-carromato:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Efecto Definitivo. Elige 1 aliado; este gana fuerza 3 y Provocar hasta el final del turno o de su próximo ataque.",
                List.of(new PickAlly(false, true)), List.of(gainUntilNextAttack(3, "Provocar")))
                .limited(Limit.DEFINITIVE));
        // E1.1-W3 Ascenso Espectral
        add("ascenso espectral", new EffectScript("ascenso-espectral:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Efecto Definitivo. Tributa tu mano. Luego: roba 5 cartas y termina tu turno.",
                List.of(tributeHand(), draw(5), new EndYourTurn()))
                .limited(Limit.DEFINITIVE));
    }

    // ------------------------------------------------------------------ E1.2 (2026-10-31; texts from the printed cards)
    static {
        CardFilter enemy = CardFilter.creatures();
        CardFilter hada = CardFilter.creatures().withClasses("hada");
        CardFilter structure = CardFilter.type("structure");
        CardFilter espectroP = CardFilter.any().withColors("P").withClasses("espectro");
        // E1.2-B01 Corredor A1001
        add("corredor a1001", new EffectScript("corredor-a1001:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Mira las primeras 3 cartas de tu mazo: de ellas añade a tu mano hasta 1 carta Azul. Envía el resto al fondo de tu mazo. "
                        + "Luego: tributa 1 carta de tu mano.",
                List.of(lookTop(3), takeFromLookedRestToBottom(1, CardFilter.colors("B")), tributeFromHand(1))));
        // E1.2-B02 B2 Guardián
        add("b2 guardián", new EffectScript("b2-guardian:al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Roba 1 carta. Luego: tributa 1 carta de tu mano. Si tributaste 1 estructura: roba 1 carta.",
                List.of(draw(1), tributeFromHand(1), new IfTributed(structure, 1), draw(1))));
        // E1.2-B04 Homorfo Errante: the two alternatives of one "En Destrucción", by the lives it has
        add("homorfo errante", new EffectScript("homorfo-errante:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                "Si tienes 4 o más vidas: puedes invocar 1 criatura Azul nivel 1 de tu mano reduciendo en 1 su coste. Si tienes 3 o menos vidas: "
                        + "en su lugar, puedes jugar 1 estructura Azul de tus tributos reduciendo en 1 su coste.",
                List.of(new IfCondition(not(new LivesAtMost(false, 3)), 1),
                        summonFromZone("hand", CardFilter.creatures().withColors("B").withLevel(1), 1),
                        new IfCondition(new LivesAtMost(false, 3), 1),
                        playFromZone("tributeZone", structure.withColors("B"), 1))));
        // E1.2-B05 VS-1 ("En Mano / En Tributos": one script per zone)
        EffectScript vs1Attach = new EffectScript("vs-1:en-mano", Moment.ON_EVENT, false, "En Mano / En Tributos",
                "Si algún aliado nivel 3 o mayor es invocado y no tiene materiales nivel 2: puedes añadirle esta carta como material.",
                List.of(new SubjectFromTrigger(), attachSelfToSubject()))
                .when(all(new AllySummoned(CardFilter.creatures().withMinLevel(3)), new SummonedWithoutMaterial(CardFilter.any().withLevel(2))));
        add("vs 1",
                new EffectScript("vs-1:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes tributar 1 material de esta carta; juega 1 estructura Azul de tus tributos reduciendo su coste en 1.",
                        List.of(tributeMaterials(1, false)), List.of(playFromZone("tributeZone", structure.withColors("B"), 1))),
                vs1Attach.fromZone("hand"),
                new EffectScript("vs-1:en-tributos", vs1Attach.moment(), false, vs1Attach.label(), vs1Attach.text(), vs1Attach.steps())
                        .when(vs1Attach.condition()).fromZone("tributeZone"));
        // E1.2-B06 VV-3
        add("vv 3", new EffectScript("vv-3:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Mira las primeras 5 cartas de tu mazo: de ellas toma hasta 3 cartas Azules o [autómata]. Envía el resto al fondo de tu mazo. "
                        + "Luego: tributa 2 cartas de entre tu mano y los materiales de esta carta.",
                List.of(lookTop(5), takeFromLookedRestToBottom(3, CardFilter.colors("B").withClasses("autómata")),
                        tributeFromHandOrMaterials(2))));
        // E1.2-B08 EXO-91 (its "En Tributos" replacement for an ally is not applied yet)
        add("exo 91", new EffectScript("exo-91:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes tributar 2 materiales de esta carta; juega 1 estructura de tus tributos reduciendo su coste en 2.",
                List.of(tributeMaterials(2, false)), List.of(playFromZone("tributeZone", structure, 2))));
        // E1.2-B11 SARS-2 (its "En Mano" replacement for an ally is not applied yet)
        add("sars 2", new EffectScript("sars-2:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes tributar hasta 2 materiales de esta carta para elegir la misma cantidad de enemigos; reduce en 2 su fuerza.",
                List.of(tributeMaterials(2, true), chooseEnemiesPerCount(enemy)), List.of(reduceChosen(2, false))));
        // E1.2-B12 Fibrax 1 (Asalto: summon rule)
        add("fibrax 1", new EffectScript("fibrax-1:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Si tienes 3 o más estructuras: elige 1 enemigo de fuerza 10 o menos; reduce a 0 su fuerza.",
                List.of(chooseEnemy(1, enemy.withMaxStrength(10))), List.of(reduceChosenToZero()))
                .when(new CountOnField(structure, 3, null)));
        // E1.2-B14 Geo Vigilante (structure; Reactivar: keyword): its cost and its special cost
        CardFilter blue3 = CardFilter.creatures().withColors("B").withMinLevel(3);
        costModifier("geo vigilante",
                new CostModifier(TODOS, CostModifiers.Event.SPECIAL_SUMMON, Set.of(), CostModifiers.Holder.FIELD, false, null, Payment.tapSelf(), 1,
                        Limit.NONE, blue3, null),
                new CostModifier(TODOS, CostModifiers.Event.SUMMON, Set.of(), CostModifiers.Holder.FIELD, false, null, Payment.tapSelf(), 1,
                        Limit.NONE, blue3, null));
        // E1.2-B15 Cañón Gauss (structure)
        add("cañón gauss",
                new EffectScript("canon-gauss:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                        "Si un aliado Azul declara ataque: puedes agotar esta carta para elegir 1 enemigo; reduce en 2 su fuerza hasta el final del ataque.",
                        List.of(tapSelf(), chooseEnemy(1, enemy)), List.of(reduceChosenUntilAttack(2)))
                        .when(new AllyDeclaresAttack(CardFilter.creatures().withColors("B"))),
                new EffectScript("canon-gauss:turno-aliado", Moment.ON_EVENT, true, "Turno Aliado",
                        "Al inicio del turno si, en nivel, tus aliados suman menos que los del oponente: gana 1 gema.",
                        List.of(gainGems(1)))
                        .when(all(new TurnStarts(true), new LevelsBelowOpponent()))
                        .onlyAllyTurn());
        // E1.2-G01 Anna
        add("anna", new EffectScript("anna:tributada-como-recurso", Moment.ON_EVENT, false, "En Tributos",
                "(1) Si esta carta es tributada como recurso: puedes tributar 1 carta de tu mano; invoca 1 [hada] de tu mano reduciendo su coste en 1.",
                List.of(tributeFromHand(1)), List.of(summonFromZone("hand", hada, 1)))
                .when(new ThisTributed("resources", null, null))
                .fromZone("tributeZone")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        inherit("anna", new EffectScript("anna:hereda-todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Heredado de Anna: si esta carta es agotada, puedes tributar el recurso de este efecto; roba 1 carta.",
                List.of(tributeResource("Anna")), List.of(draw(1)))
                .when(new ThisExhausted()));
        // E1.2-G02 Clavis (Asalto: summon rule)
        add("clavis", new EffectScript("clavis:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Elige 1 enemigo de fuerza 5 o menos; agótalo.",
                List.of(chooseEnemy(1, enemy.withMaxStrength(5))), List.of(tapChosen())));
        // E1.2-G04 Hada Aprendiz
        add("hada aprendiz", new EffectScript("hada-aprendiz:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "(1) Reactiva 1 carta Verde en tu campo de coste 1 o menos. Si no reactivaste nada por este efecto: roba 1 carta.",
                List.of(untapOwnCard(CardFilter.colors("G").withMaxCost(1)), new IfNotDone(1), draw(1)))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1.2-G06 Celius
        add("celius", new EffectScript("celius:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION + " por efecto",
                "Puedes añadir 1 aliado nivel 1 como recurso de esta carta para mover esta carta a tu línea frontal. Si lo haces: puedes tributar "
                        + "1 carta de tu mano y elegir 1 enemigo agotado de fuerza 6 o menos; tribútalo.",
                List.of(new IfPossible(), addAllyAsResource(CardFilter.creatures().withLevel(1)), moveSelfToFront(), new IfDone(4),
                        new MayDo("¿Tributas 1 carta de tu mano para tributar 1 enemigo agotado de fuerza 6 o menos?", 3),
                        tributeFromHand(1), chooseEnemy(1, enemy.exhausted().withMaxStrength(6)), tributeChosen()))
                .when(new SummonedByEffect()));
        // E1.2-G07 Cala
        add("cala", new EffectScript("cala:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes añadir 1 aliado nivel 1 como recurso de esta carta para reactivar 1 aliado de fuerza 7 o menos.",
                List.of(new IfPossible(), addAllyAsResource(CardFilter.creatures().withLevel(1)), untapAlly(CardFilter.any().withMaxStrength(7)))));
        inherit("cala", hadaResource("cala", "Cala"));
        // E1.2-G08 Raflesia (Asalto: summon rule)
        add("raflesia", new EffectScript("raflesia:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION + " por efecto",
                "Puedes elegir 1 criatura en el campo de fuerza 8 o menos; agótala. Si lo haces: puedes reactivar 1 aliado de fuerza 7 o menos.",
                List.of(chooseCreatureOnField(CardFilter.creatures().withMaxStrength(8), false)),
                List.of(tapChosenOnField(), new IfDone(2), new MayDo("¿Reactivas 1 aliado de fuerza 7 o menos?", 1),
                        untapAlly(CardFilter.any().withMaxStrength(7))))
                .when(new SummonedByEffect()));
        inherit("raflesia", hadaResource("raflesia", "Raflesia"));
        // E1.2-G09 Violet
        add("violet", new EffectScript("violet:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes mover un aliado en tu retaguardia a tu línea frontal.",
                List.of(moveAllyToFront())));
        inherit("violet", new EffectScript("violet:hereda-todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Heredado de Violet: si algún aliado de fuerza 4 o mayor es invocado por efecto, puedes tributar el recurso de este efecto; "
                        + "invoca 1 [hada] nivel 1 de tu mano reduciendo su coste en 1.",
                List.of(tributeResource("Violet")), List.of(summonFromZone("hand", hada.withLevel(1), 1)))
                .when(new AllySummonedByEffect(CardFilter.creatures().withMinStrength(4))));
        // E1.2-G11 Leims (printed: a trigger "si esta carta es agotada"; inherited: "gana 1 gema")
        add("leims", new EffectScript("leims:turno-aliado", Moment.ON_EVENT, false, "Turno Aliado",
                "Una vez por turno: si esta carta es agotada, puedes tributar 1 carta de tu mano; invoca 1 [hada] de tu mano reduciendo su coste en 1.",
                List.of(tributeFromHand(1)), List.of(summonFromZone("hand", hada, 1)))
                .when(new ThisExhausted())
                .onlyAllyTurn()
                .limited(Limit.ONCE_PER_TURN));
        inherit("leims", new EffectScript("leims:hereda-todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Heredado de Leims: si algún aliado de fuerza 4 o mayor es invocado por efecto, puedes tributar el recurso de este efecto; gana 1 gema.",
                List.of(tributeResource("Leims")), List.of(gainGems(1)))
                .when(new AllySummonedByEffect(CardFilter.creatures().withMinStrength(4))));
        // E1.2-G12 Juleus El Principe Olvidado (Celeridad: keyword)
        add("juleus el principe olvidado", new EffectScript("juleus:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes tributar 1 carta de tu mano; elige 1 enemigo de fuerza 12 o menos para agotarlo. Si ningún enemigo fue agotado por este efecto: "
                        + "reactiva esta carta.",
                List.of(tributeFromHand(1)),
                List.of(chooseEnemy(1, enemy.withMaxStrength(12)), tapChosen(), new IfNotDone(1), untapSelf())));
        inherit("juleus el principe olvidado", new EffectScript("juleus:hereda-todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Heredado de Juleus El Principe Olvidado: si esta carta es agotada, puedes tributar el recurso de este efecto y elegir 1 enemigo con "
                        + "fuerza 9 o menos; agótalo.",
                List.of(tributeResource("Juleus El Principe Olvidado"), chooseEnemy(1, enemy.withMaxStrength(9))), List.of(tapChosen()))
                .when(new ThisExhausted()));
        // E1.2-G14 Trampa Vegetal (utility that stays in the Retaguardia)
        add("trampa vegetal",
                new EffectScript("trampa-vegetal:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Coloca esta carta en tu retaguardia.",
                        List.of(new KeepOnField(false))),
                new EffectScript("trampa-vegetal:turno-oponente", Moment.ON_EVENT, true, "Turno Oponente",
                        "Si algún enemigo con fuerza 6 o menos entra a la línea frontal: envía esta carta al descarte; agota dicho enemigo.",
                        List.of(sendSelfFromFieldTo("discardPile")), List.of(tapTriggerCard()))
                        .when(all(not(new YourTurn()), new EntersFrontLine(true, CardFilter.creatures().withMaxStrength(6)))),
                new EffectScript("trampa-vegetal:final-del-turno", Moment.ON_EVENT, true, "Turno Oponente",
                        "Al final del turno: sube esta carta a tu mano.",
                        List.of(returnSelfToHand()))
                        .when(all(not(new YourTurn()), new AnyTurnEnds())));
        // E1.2-G15 Poder Antiguo (utility)
        add("poder antiguo", new EffectScript("poder-antiguo:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "(1) Elige 1 aliado activo; puede invocar por Evolución 1 carta Verde en tu mano reduciendo el coste especial en X donde X es el "
                        + "nivel del aliado elegido. Si lo hace: agótalo.",
                List.of(new PickAllyWhere(CardFilter.creatures().active(), true)),
                List.of(specialBySubjectLevel("evolution", "hand", CardFilter.colors("G")), new IfDone(1), tapSubject()))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1.2-P01 Abraza Almas: "Si no controlas cartas: esta carta cuesta 1 gema" (its "nivel 2 para tus [espectro]": ALTERNATIVE_LEVELS)
        costModifier("abraza almas", new CostModifier("Absoluto", CostModifiers.Event.SUMMON, Set.of(), CostModifiers.Holder.SELF, false,
                new CountOnField(CardFilter.any(), null, 0), Payment.none(), 1));
        add("abraza almas", new EffectScript("abraza-almas:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                "Roba 1 carta. Luego: tributa 1 carta de tu mano.",
                List.of(draw(1), tributeFromHand(1))));
        // E1.2-P02 Zokolok
        add("zokolok", new EffectScript("zokolok:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                "(1) Puedes jugar 1 utilidad Violeta de coste 1 o menos de tus tributos sin pagar su coste.",
                List.of(playFromZone("tributeZone", CardFilter.type("utility").withColors("P").withMaxCost(1), FREE)))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1.2-P04 Phog
        add("phog", new EffectScript("phog:en-mano", Moment.ACTIVATED, false, "Turno Aliado / En Mano",
                "Puedes sacrificar 1 criatura nivel 2 o mayor en el campo de la que seas dueño para invocar esta carta sin pagar su coste.",
                List.of(new IfPossible(), sacrificeAllyWhere(CardFilter.creatures().withMinLevel(2)), summonSelfFree()))
                .fromZone("hand")
                .onlyAllyTurn());
        // E1.2-P05 Calox
        continuous("calox", new Continuous(TODOS, "Si hay al menos 5 criaturas [interdimensional] en el campo: esta carta gana fuerza 3 y Provocar.", false,
                new CountOnBothFields(CardFilter.creatures().withClasses("interdimensional"), 5), 3, List.of("Provocar")));
        // E1.2-P10 Augurio Floreciente (Reactivar: keyword)
        add("augurio floreciente", new EffectScript("augurio-floreciente:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION + " por efecto o enfrentamiento",
                "Puedes tributar 1 carta de tu mano; invoca 1 criatura nivel 3 o menor de tus tributos, que no sea una copia de esta carta, reduciendo su coste en 2.",
                List.of(tributeFromHand(1)),
                List.of(summonFromZone("tributeZone", CardFilter.creatures().withMaxLevel(3).notNamed("Augurio Floreciente"), 2)))
                .when(any(new DestroyedByEffect(), new DestroyedInClash())));
        // E1.2-P12 Cadaver Corrupto
        add("cadaver corrupto", new EffectScript("cadaver-corrupto:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes sacrificar un aliado nivel 3 o mayor; coloca hasta 2 [espectros] nivel 2 o menor de tus tributos en tu retaguardia.",
                List.of(sacrificeAllyWhere(CardFilter.creatures().withMinLevel(3))),
                List.of(placeFromZone("tributeZone", CardFilter.creatures().withClasses("espectro").withMaxLevel(2), 2, false, false))));
        // E1.2-P13 Apostol De La Ruina
        add("apostol de la ruina",
                new EffectScript("apostol-de-la-ruina:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION + " especial",
                        "Elige 1 enemigo de nivel igual o menor a la cantidad de aliados utilizados para invocar esta carta; destrúyelo.",
                        List.of(chooseEnemyUpToSummonUsed()), List.of(destroyChosen()))
                        .when(new SpecialSummoned()),
                new EffectScript("apostol-de-la-ruina:final-del-turno", Moment.ON_EVENT, false, TODOS,
                        "Al final del turno, si tienes 20 o más tributos Violeta o [espectro]: puedes enviar 10 al fondo de tu mazo; esta carta inflige daño directo.",
                        List.of(sendFromZoneToDeckBottom("tributeZone", 10, espectroP)), List.of(damageBySelfStrength()))
                        .when(all(new AnyTurnEnds(), new CountInZone("tributeZone", espectroP, 20))));
        // E1.2-P14 Asedio Del Inframundo (utility)
        add("asedio del inframundo",
                new EffectScript("asedio-del-inframundo:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Puedes invocar 1 criatura nivel 1 de tus tributos reduciendo en 2 su coste. Luego: tributa esta carta.",
                        List.of(new MayDo("¿Invocas 1 criatura nivel 1 de tus tributos reduciendo en 2 su coste?", 1),
                                summonFromZone("tributeZone", CardFilter.creatures().withLevel(1), 2), sendSelfFromFieldTo("tributeZone"))),
                new EffectScript("asedio-del-inframundo:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "(1) Si algún aliado es enviado al descarte: tribútalo; juega esta carta sin pagar su coste.",
                        List.of(tributeTriggerCard()), List.of(playSelfFree()))
                        .when(new AllySentToDiscard())
                        .fromZone("hand")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1.2-P15 Reclamo Del Inframundo (utility)
        add("reclamo del inframundo", new EffectScript("reclamo-del-inframundo:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "(1) Tributa esta carta; 1 aliado puede invocar por Ritual 1 criatura Violeta o [espectro] de tus tributos reduciendo el coste especial "
                        + "en 1 por cada 10 tributos Violeta o [espectro] que tengas.",
                List.of(sendSelfFromFieldTo("tributeZone")),
                List.of(new MayDo("¿Un aliado invoca por Ritual 1 criatura Violeta o [espectro] de tus tributos?", 1),
                        ritualFromZoneBy("tributeZone", false, new Amount.Scaled(new Amount.InZone("tributeZone", espectroP), 10, 0), espectroP)))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1.2-R01 Estoque De Los Guerreros Jurados (its "En Mano" replacement of damage: DAMAGE_TRIBUTES)
        add("estoque de los guerreros jurados", new EffectScript("estoque:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Mira las primeras 5 cartas de tu mazo: de ellas toma hasta 2 cartas Rojas, [retador] o Blancas con distinto nivel. Envía el resto al "
                        + "fondo de tu mazo.",
                List.of(lookTop(5), new TakeFromLookedDistinctLevels(2, CardFilter.colors("R", "W").withClasses("retador")))));
        // E1.2-R03 Peleador Del Castillo
        continuous("peleador del castillo", new Continuous("Turno Oponente",
                "Esta carta gana fuerza 3 y no puede ser objetivo de efectos de cartas nivel 1 enemigas.", false,
                not(new YourTurn()), 3, List.of(EffectEngine.IMMUNE + " 1")));
        // E1.2-R04 Mago Armado Del Ocaso
        add("mago armado del ocaso",
                new EffectScript("mago-armado-del-ocaso:en-mano", Moment.ACTIVATED, false, "Turno Aliado / En Mano",
                        "(1) Si solo controlas 1 aliado: puedes revelar un [retador] nivel 2 y uno nivel 3 de tu mano; invoca esta carta sin pagar su coste.",
                        List.of(new RevealFromHand(List.of(CardFilter.any().withClasses("retador").withLevel(2),
                                CardFilter.any().withClasses("retador").withLevel(3)))), List.of(summonSelfFree()))
                        .when(new CountOnField(CardFilter.creatures(), 1, 1))
                        .fromZone("hand")
                        .onlyAllyTurn()
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("mago-armado-del-ocaso:todos-los-turnos", Moment.ON_EVENT, true, TODOS,
                        "Si un aliado es invocado especialmente: tributa esta carta y la carta del tope de tus vidas para ganar 1 gema.",
                        List.of(new IfPossible(), sendSelfFromFieldTo("tributeZone"), tributeTopLife(), gainGems(1)))
                        .when(new YouSpecialSummoned(CardFilter.creatures())));
        // E1.2-R06 Guerrero Forjado
        add("guerrero forjado", new EffectScript("guerrero-forjado:tributada", Moment.ON_EVENT, false, "En Tributos",
                "Si esta carta es tributada desde el campo y tienes menos de 0 gemas: puedes elegir 1 aliado activo; este declara ataque.",
                List.of(new PickAttacker(CardFilter.creatures().active(), true)), List.of(new DeclareAttack()))
                .when(all(new ThisTributed("field", null, null), not(new GemsAtLeast(0))))
                .fromZone("tributeZone"));
        // E1.2-R07 Guardia Del Muro
        add("guardia del muro", new EffectScript("guardia-del-muro:final-del-turno", Moment.ON_EVENT, false, "Turno Aliado",
                "Al final del turno: puedes tributar 1 aliado para reactivar esta carta.",
                List.of(new IfPossible(), tributeOwnCard(CardFilter.creatures()), untapSelf()))
                .when(new TurnEnds())
                .onlyAllyTurn());
        // E1.2-R08 Arco León Dorado
        add("arco león dorado",
                new EffectScript("arco-leon-dorado:tributada", Moment.ON_EVENT, false, "En Tributos",
                        "Si esta carta es tributada desde el campo: puedes tributar 1 aliado activo de coste 2 o mayor; reactiva 1 aliado.",
                        List.of(tributeOwnCard(CardFilter.creatures().active().withMinCost(2))), List.of(untapAlly()))
                        .when(new ThisTributed("field", null, null))
                        .fromZone("tributeZone"),
                new EffectScript("arco-leon-dorado:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes elegir 1 aliado y 1 enemigo de nivel igual o menor; dicho enemigo no puede bloquear hasta el final del turno.",
                        List.of(new PickAlly(false, true), chooseEnemyUpToSubjectLevel(false)), List.of(chosenCannotBlock())));
        // E1.2-R10 Espada De Los Leones Dorados (its "cuando fuera a ser ascendida": GAIN_BEFORE_ASCENDED)
        add("espada de los leones dorados", new EffectScript("espada-leones-dorados:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Si tienes menos vidas que tu oponente: gana 1 gema.",
                List.of(gainGems(1)))
                .when(new FewerLivesThanOpponent()));
        // E1.2-R11 Caballero Jurado
        continuous("caballero jurado", new Continuous(TODOS, "Mientras tengas menos vidas que tu oponente: esta carta gana Enfrentar 3 y fuerza 2.", false,
                new FewerLivesThanOpponent(), 2, List.of("Enfrentar 3")));
        add("caballero jurado", new EffectScript("caballero-jurado:al-final-del-ataque", Moment.ON_ATTACK_END, true, AL_FINAL,
                "Esta carta gana Reactivar hasta el final del turno oponente.",
                List.of(gainUntilNextTurn("Reactivar"))));
        // E1.2-R12 Comandante De Los Leones Dorados
        String retadorBack = "comandante-leones-dorados:final-del-turno";
        add("comandante de los leones dorados",
                new EffectScript("comandante-leones-dorados:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Tributa todos tus otros aliados. Por cada aliado tributado por este efecto: esta carta gana fuerza 2 hasta el final del turno.",
                        List.of(tributeOtherAllies(), gainStrengthPerCount(2))),
                new EffectScript("comandante-leones-dorados:invoca-retador", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes invocar 1 [retador] de tu mano reduciendo su coste en 2. Súbelo a tu mano al final de tu turno.",
                        List.of(summonFromZone("hand", CardFilter.creatures().withClasses("retador"), 2), new IfDone(1), new AtEndOfTurn(retadorBack))),
                new EffectScript("comandante-leones-dorados:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                        "Puedes invocar 1 [retador] de tu mano reduciendo su coste en 2. Súbelo a tu mano al final de tu turno.",
                        List.of(summonFromZone("hand", CardFilter.creatures().withClasses("retador"), 2), new IfDone(1), new AtEndOfTurn(retadorBack))),
                new EffectScript(retadorBack, Moment.DELAYED, true, "Al final del turno",
                        "Sube a tu mano el [retador] invocado.",
                        List.of(returnSubjectToHand())));
        // E1.2-R14 Mensaje De Un Compañero (utility): "Solo puedes activar 1 efecto de «Mensaje De Un Compañero» por turno"
        List<EffectStep> mensajeTake = List.of(new IfPossible(), sendSelfToDeckBottom(), takeFromZone("tributeZone", CardFilter.any().withClasses("retador"), 1, false));
        String mensajeText = "Si algún aliado es invocado por efecto o si es tu turno: puedes enviar esta carta al fondo de tu mazo para tomar 1 [retador] de tus tributos.";
        add("mensaje de un compañero",
                new EffectScript("mensaje-companero:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Tributa esta carta y 1 [retador] de tu mano; roba 2 cartas.",
                        List.of(sendSelfFromFieldTo("tributeZone"), tributeFromHandWhere(1, CardFilter.any().withClasses("retador"))), List.of(draw(2)))
                        .limited(Limit.ONE_EFFECT_OF_NAME_PER_TURN),
                new EffectScript("mensaje-companero:en-tributos", Moment.ON_EVENT, false, "En Tributos", mensajeText, mensajeTake)
                        .when(new AllySummonedByEffect(CardFilter.any()))
                        .fromZone("tributeZone")
                        .limited(Limit.ONE_EFFECT_OF_NAME_PER_TURN),
                new EffectScript("mensaje-companero:en-tributos-tu-turno", Moment.ACTIVATED, false, "En Tributos", mensajeText, mensajeTake)
                        .fromZone("tributeZone")
                        .onlyAllyTurn()
                        .limited(Limit.ONE_EFFECT_OF_NAME_PER_TURN));
        // E1.2-R15 Coliseo (structure)
        add("coliseo",
                new EffectScript("coliseo:turno-aliado", Moment.ON_EVENT, true, "Turno Aliado",
                        "Al inicio del turno, si controlas 1 o menos aliados: gana 1 gema.",
                        List.of(gainGems(1)))
                        .when(all(new TurnStarts(true), new CountOnField(CardFilter.creatures(), null, 1)))
                        .onlyAllyTurn(),
                new EffectScript("coliseo:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                        "Si algún aliado es invocado por efecto: puedes agotar esta carta para colocar bocabajo una de tus vidas bocarriba.",
                        List.of(new IfPossible(), tapSelf(), new LivesFaceDown(false, false)))
                        .when(new AllySummonedByEffect(CardFilter.any())));
        // E1.2-W01 Regreso De La Penumbra
        add("regreso de la penumbra", new EffectScript("regreso-penumbra:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Efecto Definitivo. Toma 1 criatura no-blanca de tu descarte.",
                List.of(takeFromZone("discardPile", CardFilter.creatures().notColors("W"), 1, false)))
                .limited(Limit.DEFINITIVE));
        // E1.2-W02 Circulo Devorador De Muerte: "solo puede ser afectada por copias": it can be chosen, but no other card's effect does
        // anything to it, yours included (designer ruling 2026-10-31; EffectEngine.unaffected)
        add("circulo devorador de muerte",
                new EffectScript("circulo-devorador:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Efecto Definitivo. Coloca esta carta agotada en tu retaguardia; esta carta solo puede ser afectada por copias de "
                                + "\"Circulo Devorador De Muerte\".",
                        List.of(new KeepOnField(true)))
                        .limited(Limit.DEFINITIVE),
                new EffectScript("circulo-devorador:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                        "Puedes enviar 4 copias de esta carta en tu retaguardia a tu descarte para ganar 4 gemas.",
                        List.of(new IfPossible(), sendCopiesFromField(4, "discardPile"), gainGems(4)))
                        .onlyAllyTurn());
        continuous("circulo devorador de muerte", new Continuous(TODOS, "Esta carta solo puede ser afectada por copias de sí misma.", false,
                new Always(), 0, List.of(EffectEngine.ONLY_COPIES)));
        // E1.2-W03 Exploración Territorial
        add("exploración territorial",
                new EffectScript("exploracion-territorial:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Efecto Definitivo. Tributa esta carta y 1 carta de tu mano; roba 2 cartas.",
                        List.of(sendSelfFromFieldTo("tributeZone"), tributeFromHand(1)), List.of(draw(2)))
                        .limited(Limit.DEFINITIVE),
                new EffectScript("exploracion-territorial:en-tributos", Moment.ON_EVENT, false, "En Tributos",
                        "(1) Si juegas alguna estructura: puedes descartar esta carta para tomar 1 carta de tus tributos.",
                        List.of(new IfPossible(), sendSelfTo("discardPile"), takeFromZone("tributeZone", CardFilter.any(), 1, false)))
                        .when(new YouPlayed(structure))
                        .fromZone("tributeZone")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1.2-W04 Trato Sucio
        add("trato sucio", new EffectScript("trato-sucio:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Efecto Definitivo. Ambos jugadores descartan 3 cartas de su mano bocabajo. Luego: ambos jugadores roban 3 cartas.",
                List.of(new EachPlayerDiscards(3), eachPlayerDraws(3)))
                .limited(Limit.DEFINITIVE));
        // E1.2-W05 Ejecución Despiadada
        add("ejecución despiadada", new EffectScript("ejecucion-despiadada:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Efecto Definitivo. Tu oponente gana tantas gemas como su contador de gemas diga; destruye 1 enemigo en la línea frontal (este efecto "
                        + "no hace objetivo).",
                List.of(new OpponentGainsGemsAsCounter(), destroyEnemyNoTarget()))
                .limited(Limit.DEFINITIVE));
    }

    /** "Heredado de X: si esta carta es agotada, puedes tributar el recurso con este efecto; invoca 1 [hada] de tu mano reduciendo su coste en 1." */
    private static EffectScript hadaResource(String id, String name) {
        return new EffectScript(id + ":hereda-todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Heredado de " + name + ": si esta carta es agotada, puedes tributar el recurso con este efecto; invoca 1 [hada] de tu mano reduciendo su coste en 1.",
                List.of(tributeResource(name)), List.of(summonFromZone("hand", CardFilter.creatures().withClasses("hada"), 1)))
                .when(new ThisExhausted());
    }

    // ------------------------------------------------------------------ E1.3 (2026-10-31; texts from the printed cards)
    static {
        CardFilter enemy = CardFilter.creatures();
        CardFilter necro = CardFilter.creatures().withClasses("necrómata");
        CardFilter blueNecro = CardFilter.creatures().withColors("B").withClasses("necrómata");
        CardFilter structure = CardFilter.type("structure");
        CardFilter primeraLinea = CardFilter.creatures().nameContains("Primera Línea");
        CardFilter brujeria = CardFilter.any().withClasses("brujería");
        // E1.3-B01 Vigilante Antiorgánico (Iniciador)
        add("vigilante antiorgánico",
                new EffectScript("vigilante-antiorganico:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Mira las primeras 3 cartas de tu mazo: de ellas envía hasta 2 a tu descarte. Envía el resto al fondo de tu mazo. Luego: toma "
                                + "hasta 1 carta [necrómata] de tu descarte.",
                        List.of(lookTop(3), discardFromLookedRestToBottom(2), takeFromZone("discardPile", CardFilter.any().withClasses("necrómata"), 1, true))),
                selfToLifeFaceUp("vigilante-antiorganico"));
        // E1.3-B03 Residuo Biomecánico
        add("residuo biomecánico", new EffectScript("residuo-biomecanico:en-descarte", Moment.ON_EVENT, false, "En Descarte",
                "(1) Si esta carta es enviada a tu descarte como material y tienes 3 o menos vidas: puedes colocar esta carta en tu retaguardia.",
                List.of(placeSelfOnField("discardPile")))
                .when(all(new ThisSentToDiscardAsMaterial(), new LivesAtMost(false, 3)))
                .fromZone("discardPile")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1.3-B04 Necrosimbionte
        add("necrosimbionte", new EffectScript("necrosimbionte:en-descarte", Moment.ON_EVENT, false, "En Descarte",
                "(1) Si algún enemigo es destruido por tus cartas: puedes colocar esta carta en tu retaguardia.",
                List.of(placeSelfOnField("discardPile")))
                .when(new YouDestroyedEnemy(false))
                .fromZone("discardPile")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1.3-B05 H-0110-W
        add("h 0110 w",
                new EffectScript("h-0110-w:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Si tienes 3 o menos vidas: esta carta puede declarar ataque sin agotarse. Si lo hace: gana fuerza 3 hasta el final del ataque.",
                        List.of(new DeclareAttack(), new IfCondition(new DuringCombat(), 1), gainStrengthUntil(3, "attack")))
                        .when(new LivesAtMost(false, 3)),
                selfToLifeFaceUp("h-0110-w"));
        // E1.3-B06 Recolector De Biomateria
        add("recolector de biomateria",
                new EffectScript("recolector-biomateria:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes tomar hasta 1 criatura Azul o [necrómata] de tu descarte.",
                        List.of(takeFromZone("discardPile", blueNecro, 1, true))),
                new EffectScript("recolector-biomateria:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                        "Si esta carta tenía al menos 1 material: puedes descartar 1 carta de tu mano; invoca 1 [necrómata] nivel 2 de tu descarte que no "
                                + "sea una copia de esta carta, sin pagar su coste.",
                        List.of(discardFromHand(1)),
                        List.of(summonFromZone("discardPile", necro.withLevel(2).notNamed("Recolector De Biomateria"), FREE)))
                        .when(new HadMaterials()));
        // E1.3-B07 Doctor Nuliber
        add("doctor nuliber", new EffectScript("doctor-nuliber:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Roba 2 cartas. Luego: descarta 2 cartas de tu mano. Si descartaste algún [necrómata] nivel 1: puedes colocar alguno de ellos agotado "
                        + "en tu línea frontal.",
                List.of(draw(2), discardFromHand(2), new IfTributed(necro.withLevel(1), 1), placeLastTributed(necro.withLevel(1), true, true))));
        // E1.3-B08 SRL-EX8 (Reactivar: keyword)
        add("srl ex8", new EffectScript("srl-ex8:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                "Puedes colocar 1 criatura nivel 2 o menor de tu descarte al tope de tus vidas bocarriba.",
                List.of(zoneToLife("discardPile", CardFilter.creatures().withMaxLevel(2), true))));
        // E1.3-B09 Freud
        add("freud", new EffectScript("freud:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Puedes enviar 5 cartas [necrómata] de tus tributos o descarte al fondo de tu mazo para ganar 1 gema. Luego: puedes destruir 1 aliado; "
                        + "esta carta invoca de forma especial 1 criatura Azul o [necrómata] de tu mano o descarte reduciendo el coste especial en 1.",
                List.of(new MayDo("¿Envías 5 cartas [necrómata] de tus tributos o descarte al fondo de tu mazo para ganar 1 gema?", 2),
                        sendFromZoneToDeckBottom("tributeZone,discardPile", 5, CardFilter.any().withClasses("necrómata")), gainGems(1),
                        new MayDo("¿Destruyes 1 aliado para que Freud invoque de forma especial 1 criatura Azul o [necrómata] de tu mano o descarte?", 2),
                        destroyAlly(CardFilter.creatures()), new SpecialFromZone("any", "hand,discardPile", 1, "self", blueNecro))));
        // E1.3-B10 Velkerop-1
        add("velkerop 1",
                new EffectScript("velkerop-1:en-descarte", Moment.ACTIVATED, false, "Turno Aliado / En Descarte",
                        "Puedes destruir un aliado; un aliado invoca esta carta de forma especial reduciendo su coste especial en 1.",
                        List.of(destroyAlly(CardFilter.creatures())), List.of(specialSelf("discardPile", 1)))
                        .fromZone("discardPile")
                        .onlyAllyTurn(),
                new EffectScript("velkerop-1:turno-oponente", Moment.ON_EVENT, false, "Turno Oponente",
                        "Una vez por turno: si alguna carta es removida de tus vidas, puedes descartar 2 materiales de esta carta; coloca en tu línea "
                                + "frontal 1 criatura Azul o [necrómata] nivel 1 de tu descarte.",
                        List.of(discardMaterials(2)), List.of(placeFromZone("discardPile", blueNecro.withLevel(1), 1, true, false)))
                        .when(all(not(new YourTurn()), new LifeRemoved()))
                        .limited(Limit.ONCE_PER_TURN));
        // E1.3-B11 Biok: "cuando esta carta fuera a invocar especialmente, hasta 3 veces: puedes infligirte 10 de daño para reducir en 1 el coste
        // especial" (designer ruling 2026-10-31: direct damage by effect to its own player)
        costModifier("biok", new CostModifier(TODOS, CostModifiers.Event.SPECIAL_SUMMON, Set.of(), CostModifiers.Holder.SUMMONER, false, null,
                Payment.selfDamage(3, 10), 1));
        add("biok", new EffectScript("biok:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Esta carta gana Reactivar hasta el final del turno oponente.",
                List.of(gainUntilNextTurn("Reactivar"))));
        // E1.3-B14 Bio-prototipo A0
        add("bio prototipo a0",
                new EffectScript("bio-prototipo-a0:vida-removida", Moment.ON_EVENT, false, TODOS,
                        "Si alguna de tus vidas es removida: puedes añadirla como material a esta carta para elegir 1 enemigo; reduce en 3 su fuerza "
                                + "hasta el final del turno.",
                        List.of(attachTriggerCardAsMaterial(), chooseEnemy(1, enemy)), List.of(reduceChosen(3, false)))
                        .when(new LifeRemoved()),
                new EffectScript("bio-prototipo-a0:dano-directo", Moment.ON_EVENT, false, TODOS,
                        "Una vez por turno: si recibes daño directo, puedes descartar 2 materiales de esta carta; reactívala.",
                        List.of(discardMaterials(2)), List.of(untapSelf()))
                        .when(new YouReceivedDirectDamage())
                        .limited(Limit.ONCE_PER_TURN));
        // E1.3-B15 Ingeniería Neurobiónica (utility)
        add("ingeniería neurobiónica",
                new EffectScript("ingenieria-neurobionica:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "(1) Destruye 1 aliado Azul o [necrómata]; invoca en tu línea frontal 1 criatura [necrómata] de nivel menor de tu mano o "
                                + "descarte sin pagar su coste.",
                        List.of(destroyAlly(blueNecro)),
                        List.of(summonRelativeToLast("hand,discardPile", necro, FREE, true, "lower")))
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("ingenieria-neurobionica:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "Si recibes daño directo: puedes jugar esta carta sin pagar su coste.",
                        List.of(playSelfFree()))
                        .when(new YouReceivedDirectDamage())
                        .fromZone("hand"));
        // E1.3-G01 Cervalyra (Iniciador): "con [fauna] en su texto" = of that type, in its name or in its effects (designer ruling 2026-10-31)
        add("cervalyra", new EffectScript("cervalyra:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Mira las primeras 3 cartas de tu mazo: de ellas toma hasta 1 estructura Verde o con [fauna] en su texto. Envía el resto al tope o "
                        + "al fondo de tu mazo. Luego: roba 1 carta.",
                List.of(lookTop(3), takeFromLooked(1, structure.orAnyOf(CardFilter.colors("G"), CardFilter.any().mentioning("fauna"))), new LookedToTopOrBottom(), draw(1))));
        inherit("cervalyra", new EffectScript("cervalyra:hereda-en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Heredado de Cervalyra: una vez por turno, roba 1 carta.",
                List.of(draw(1)))
                .limited(Limit.ONCE_PER_TURN));
        // E1.3-G03 Mono De Pantano
        inherit("mono de pantano", new EffectScript("mono-de-pantano:hereda-al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Heredado de Mono De Pantano: esta carta puede invocar por Evolución una carta de tu mano.",
                List.of(new SpecialFromZone("evolution", "hand", 0, "self", CardFilter.any()))));
        // E1.3-G04 Veleaf
        add("veleaf", new EffectScript("veleaf:turno-aliado", Moment.ON_EVENT, false, "Turno Aliado",
                "Si esta carta sube a la línea frontal: esta carta puede invocar por Evolución una criatura en tu mano reduciendo el coste especial "
                        + "en 1. Si lo haces: roba 1 carta.",
                List.of(new SpecialFromZone("evolution", "hand", 1, "self", CardFilter.creatures()), new IfDone(1), draw(1)))
                .when(new ThisMovedToFront())
                .onlyAllyTurn());
        // E1.3-G05 Fiorvo
        add("fiorvo", new EffectScript("fiorvo:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "(1) Puedes jugar 1 estructura Verde, de tu mano o tributos, reduciendo su coste en 1.",
                List.of(playFromZone("hand,tributeZone", structure.withColors("G"), 1)))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        inherit("fiorvo", new EffectScript("fiorvo:hereda-turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                "Heredado de Fiorvo: (1) puedes tributar el recurso con este efecto; añade 1 criatura nivel 2 de tu mano o tributos como recurso de "
                        + "esta carta. Si es [fauna] puede ser nivel 3.",
                List.of(tributeResource("Fiorvo")),
                List.of(addResourceFromZone("hand,tributeZone", CardFilter.creatures().orAnyOf(CardFilter.any().withLevel(2),
                        CardFilter.any().withClasses("fauna").withLevel(3)), false)))
                .onlyAllyTurn()
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1.3-G06 Cylvaus (metadata "Cylvarus"): a Reactivo–Modificador with a payment
        costModifier("cylvarus", new CostModifier("Turno Aliado", CostModifiers.Event.SPECIAL_SUMMON, Set.of("evolution"), CostModifiers.Holder.SUMMONER,
                true, null, Payment.tapCard(CardFilter.colors("G")), 1));
        // E1.3-G07 Leshy
        add("leshy", new EffectScript("leshy:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes añadir 1 criatura Verde nivel 3 o menor de tu mano o tributos como recurso de esta carta. Si lo haces: roba 1 carta.",
                List.of(addResourceFromZone("hand,tributeZone", CardFilter.creatures().withColors("G").withMaxLevel(3), false), new IfDone(1), draw(1))));
        inherit("leshy", new EffectScript("leshy:hereda-todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Heredado de Leshy: si esta carta es agotada, puedes tributar el recurso con este efecto; invoca 1 criatura Verde nivel 1 de tu mano "
                        + "reduciendo en 1 su coste.",
                List.of(tributeResource("Leshy")), List.of(summonFromZone("hand", CardFilter.creatures().withColors("G").withLevel(1), 1)))
                .when(new ThisExhausted()));
        // E1.3-G08 Filyho
        inherit("filyho", new EffectScript("filyho:hereda-en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Heredado de Filyho: esta carta gana Furia 2 hasta el final del turno.",
                List.of(gainUntilEndOfTurn("Furia 2"))));
        // E1.3-G10 Tharnok: "cuando fueras a invocar especialmente esta carta, si no controlas criaturas nivel 4 o mayor: reduce en 1 su coste
        // especial por cada vida bocabajo más que tenga tu oponente que tú"
        costModifier("tharnok", new CostModifier("Absoluto", CostModifiers.Event.SPECIAL_SUMMON, Set.of(), CostModifiers.Holder.SELF, false,
                new CountOnField(CardFilter.creatures().withMinLevel(4), null, 0), Payment.none(), 0, Limit.NONE, null, new Amount.LivesBehind(true)));
        inherit("tharnok", new EffectScript("tharnok:hereda-todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Heredado de Tharnok: una vez por turno, si esta carta es agotada, puedes agotar una de tus estructuras para reactivar esta carta.",
                List.of(new IfPossible(), tapOwnCard(structure), untapSelf()))
                .when(new ThisExhausted())
                .limited(Limit.ONCE_PER_TURN));
        // E1.3-G13 Druida Antiguo
        add("druida antiguo",
                new EffectScript("druida-antiguo:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                        "(1) Puedes reactivar hasta 2 de tus estructuras Verdes de coste 2 o menos.",
                        List.of(untapStructures(2, CardFilter.colors("G").withMaxCost(2))))
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("druida-antiguo:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                        "Si esta carta es agotada: puedes agotar 1 de tus estructuras para invocar por Evolución una criatura en tu mano reduciendo el "
                                + "coste especial en 2.",
                        List.of(new IfPossible(), tapOwnCard(structure), new SpecialFromZone("evolution", "hand", 2, "any", CardFilter.creatures())))
                        .when(new ThisExhausted()));
        // E1.3-G14 Mensaje De Los Espiritus Protectores (utility)
        add("mensaje de los espiritus protectores",
                new EffectScript("mensaje-espiritus:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "(1) Tributa esta carta y hasta 3 recursos [fauna] de un aliado; ese u otro aliado invoca especialmente una criatura de tu mano "
                                + "reduciendo el coste especial en 1 por cada recurso tributado.",
                        List.of(sendSelfFromFieldTo("tributeZone"), new TributeAllyResources(3, CardFilter.any().withClasses("fauna"))),
                        List.of(new SubjectIsThisCard(), specialByCount("any", "hand", "any", CardFilter.creatures())))
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("mensaje-espiritus:en-tributos", Moment.ACTIVATED, false, "En Tributos",
                        "Puedes enviar esta carta al fondo de tu mazo; añade 1 criatura Verde de tus tributos como recurso de un aliado de nivel mayor.",
                        List.of(sendSelfToDeckBottom()), List.of(new AddResourceToHigherAlly("tributeZone", CardFilter.creatures().withColors("G"))))
                        .fromZone("tributeZone"));
        // E1.3-G15 Pradera Salvaje (structure)
        costModifier("pradera salvaje", new CostModifier("Turno Aliado", CostModifiers.Event.SPECIAL_SUMMON, Set.of(), CostModifiers.Holder.FIELD, true,
                null, Payment.tapSelf(), 1));
        add("pradera salvaje", new EffectScript("pradera-salvaje:turno-oponente", Moment.ON_EVENT, true, "Turno Oponente",
                "Al inicio del turno: reactiva esta carta y hasta 1 aliado [fauna].",
                List.of(untapSelf(), untapAllies(1, CardFilter.creatures().withClasses("fauna"))))
                .when(all(new TurnStarts(false), not(new YourTurn()))));
        // E1.3-P01 Hul
        add("hul",
                new EffectScript("hul:todos-los-turnos", Moment.ON_EVENT, true, TODOS,
                        "Si alguna criatura es destruida o invocada especialmente: sacrifica esta carta en tu línea frontal.",
                        List.of(sacrificeSelf()))
                        .when(all(new CreatureDestroyedOrSpecialSummoned(), new InFrontLine())),
                new EffectScript("hul:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION + " por efecto",
                        "El dueño de esta carta puede jugar 1 utilidad de sus tributos reduciendo su coste en 1.",
                        List.of(playFromZone("tributeZone", CardFilter.type("utility"), 1)))
                        .when(new DestroyedByEffect()));
        // E1.3-P02 Bruja Mensajera (Iniciador)
        add("bruja mensajera",
                new EffectScript("bruja-mensajera:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Mira las primeras 5 cartas de tu mazo: de ellas tributa hasta 3 cartas [brujo] o [brujería]. Envía el resto al tope o al fondo "
                                + "de tu mazo. Luego: toma 1 carta Violeta o Blanca de tus tributos.",
                        List.of(lookTop(5), tributeFromLooked(3, CardFilter.any().withClasses("brujo", "brujería")), new LookedToTopOrBottom(),
                                takeFromZone("tributeZone", CardFilter.colors("P", "W"), 1, false))),
                new EffectScript("bruja-mensajera:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                        "Puedes tomar 1 [brujería] de tus tributos.",
                        List.of(takeFromZone("tributeZone", brujeria, 1, false))));
        // E1.3-P04 Piromago
        add("piromago",
                new EffectScript("piromago:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Roba 1 carta. Luego: tributa 1 carta de tu mano. Si tributaste 1 utilidad Violeta o [brujería]: roba 1 carta.",
                        List.of(draw(1), tributeFromHand(1), new IfTributed(CardFilter.type("utility").withColors("P").withClasses("brujería"), 1),
                                draw(1))),
                new EffectScript("piromago:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                        "Si esta carta fue enviada a tu descarte: esta carta inflinge daño directo. Luego: puedes tomar 1 [brujo] o [pyro] de tus tributos "
                                + "o descarte.",
                        List.of(damageBySelfStrength(), takeFromZone("tributeZone,discardPile", CardFilter.any().withClasses("brujo", "pyro"), 1, true)))
                        .when(new ThisWentToDiscard()));
        // E1.3-P05 Espantapajaros Carroñero, E1.3-P06 Segador De Almas
        add("espantapajaros carroñero", reaperFromTributes("espantapajaros-carronero"), tributeAtTurnEnd("espantapajaros-carronero"));
        add("segador de almas", reaperFromTributes("segador-de-almas"), tributeAtTurnEnd("segador-de-almas"));
        // E1.3-P08 Necrobrujo
        add("necrobrujo",
                new EffectScript("necrobrujo:en-tributos", Moment.ON_EVENT, false, "Turno Aliado / En Tributos",
                        "Al inicio del turno: puedes invocar esta carta de forma especial reduciendo su coste especial en 1.",
                        List.of(specialSelf("tributeZone", 1)))
                        .when(new TurnStarts(true))
                        .fromZone("tributeZone")
                        .onlyAllyTurn(),
                new EffectScript("necrobrujo:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Roba 2 cartas. Luego: tributa 2 cartas de tu mano. Si tributaste alguna utilidad por este efecto: puedes jugar 1 utilidad de "
                                + "tus tributos reduciendo su coste en 1.",
                        List.of(draw(2), tributeFromHand(2), new IfTributed(CardFilter.type("utility"), 2),
                                new MayDo("¿Juegas 1 utilidad de tus tributos reduciendo su coste en 1?", 1),
                                playFromZone("tributeZone", CardFilter.type("utility"), 1))));
        // E1.3-P11 Apostol De La Infamia (Enfrentar: combat rule)
        add("apostol de la infamia",
                new EffectScript("apostol-de-la-infamia:todos-los-turnos", Moment.ON_EVENT, true, TODOS,
                        "Si esta carta enfrenta un enemigo nivel 3 o mayor: sacrifica ambas criaturas.",
                        List.of(sacrificeClashing()))
                        .when(new ThisClashes(CardFilter.creatures().withMinLevel(3), false)),
                new EffectScript("apostol-de-la-infamia:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                        "Puedes invocar 1 criatura Violeta nivel 4 o menor de tus tributos reduciendo en 6 su coste. Si es [espectro]: muévela a tu línea frontal.",
                        List.of(summonFromZone("tributeZone", CardFilter.creatures().withColors("P").withMaxLevel(4), 6),
                                moveSummonedToFrontIf(CardFilter.any().withClasses("espectro")))));
        // E1.3-P12 Khaan (Reactivar: keyword)
        add("khaan", new EffectScript("khaan:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Una vez por turno: si esta carta es agotada, puedes invocar 1 criatura Violeta nivel 2 o menor de tus tributos reduciendo su coste en 2.",
                List.of(summonFromZone("tributeZone", CardFilter.creatures().withColors("P").withMaxLevel(2), 2)))
                .when(new ThisExhausted())
                .limited(Limit.ONCE_PER_TURN));
        // E1.3-P13 Furia Álmica (utility)
        add("furia álmica", new EffectScript("furia-almica:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Elige 1 enemigo nivel 1 o menor; destrúyelo. Por cada 5 de tus tributos [brujería]: aumenta en 1 el nivel máximo elegible por este efecto.",
                List.of(chooseEnemyBy(1, new FilterBy(enemy, null, new Amount.Scaled(new Amount.InZone("tributeZone", brujeria), 5, 1)), false)),
                List.of(destroyChosen())));
        // E1.3-P14 Materialización Interdimensional (utility): "más de 20" is 21 or more
        CardFilter interdimensional = CardFilter.any().withClasses("espectro", "interdimensional");
        add("materialización interdimensional", new EffectScript("materializacion-interdimensional:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "(1) Tributa esta carta; activa 1 de los siguientes efectos según cuántos tributos [espectro] o [interdimensional] tengas: menos de 20: "
                        + "roba 3 cartas. Luego: tributa 2 cartas de tu mano. Más de 20: gana 1 gema.",
                List.of(sendSelfFromFieldTo("tributeZone")),
                List.of(new IfCondition(not(new CountInZone("tributeZone", interdimensional, 20)), 2), draw(3), tributeFromHand(2),
                        new IfCondition(new CountInZone("tributeZone", interdimensional, 21), 1), gainGems(1)))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1.3-P15 Conjuro De Oscuridad Infinita (utility; from the lives: LIFE_PLAY_COST)
        costModifier("conjuro de oscuridad infinita", new CostModifier("Absoluto", CostModifiers.Event.PLAY, Set.of(), CostModifiers.Holder.SELF, false,
                new CountOnField(CardFilter.creatures().withClasses("brujo").withMinLevel(5), 1, null), Payment.none(), 1));
        add("conjuro de oscuridad infinita", new EffectScript("conjuro-oscuridad-infinita:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Elige 1 enemigo; destrúyelo.",
                List.of(chooseEnemy(1, enemy)), List.of(destroyChosen())));
        // E1.3-R01 Peón Del Castillo (Iniciador)
        add("peón del castillo",
                new EffectScript("peon-del-castillo:ascendida", Moment.ON_EVENT, false, "En Tributos",
                        "(1) Si esta carta es ascendida: puedes tributar 1 carta de tu mano para invocar 1 criatura con \"Primera Línea\" en su nombre, "
                                + "de tu mano reduciendo su coste en 1.",
                        List.of(new IfPossible(), tributeFromHand(1), summonFromZone("hand", primeraLinea, 1)))
                        .when(new ThisAscended())
                        .fromZone("tributeZone")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("peon-del-castillo:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Roba 3 cartas. Luego: tributa 1 carta de tu mano.",
                        List.of(draw(3), tributeFromHand(1))));
        // E1.3-R03 Guerrero Del Alma Imbatible
        add("guerrero del alma imbatible",
                new EffectScript("guerrero-alma-imbatible:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes tributar la carta del tope de tus vidas para ganar 1 gema.",
                        List.of(new IfPossible(), tributeTopLife(), gainGems(1))),
                new EffectScript("guerrero-alma-imbatible:todos-los-turnos", Moment.ON_EVENT, true, TODOS,
                        "Si un aliado es invocado especialmente: sube esta carta a tu mano. Si lo haces: puedes revelar 1 [retador] nivel 3 o menor de tu "
                                + "mano y elegir 1 aliado del mismo nivel; reactívalo.",
                        List.of(returnSelfToHand(), new IfDone(3),
                                new MayDo("¿Revelas 1 [retador] nivel 3 o menor de tu mano para reactivar 1 aliado del mismo nivel?", 2),
                                new RevealFromHand(List.of(CardFilter.any().withClasses("retador").withMaxLevel(3))), untapAllySameLevelAsLast()))
                        .when(new YouSpecialSummoned(CardFilter.creatures())));
        // E1.3-R04 Caballero Versátil
        add("caballero versátil", new EffectScript("caballero-versatil:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Si esta carta es agotada durante un combate: puedes tomar 1 carta Roja de tus tributos.",
                List.of(takeFromZone("tributeZone", CardFilter.colors("R"), 1, false)))
                .when(all(new ThisExhausted(), new DuringCombat())));
        // E1.3-R05 Infantería De Primera Línea (Agrupar 1: keyword)
        add("infantería de primera línea", new EffectScript("infanteria-primera-linea:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Si esta carta es agotada durante un combate: puedes tributar esta carta para invocar 1 criatura nivel 1 con \"Primera Línea\" en su "
                        + "nombre de tu mano sin pagar su coste.",
                List.of(new IfPossible(), sendSelfFromFieldTo("tributeZone"), summonFromZone("hand", primeraLinea.withLevel(1), FREE)))
                .when(all(new ThisExhausted(), new DuringCombat())));
        // E1.3-R06 Caballero León Dorado
        add("caballero león dorado",
                new EffectScript("caballero-leon-dorado:tributada", Moment.ON_EVENT, false, "En Tributos",
                        "Si esta carta es tributada desde el campo y tienes menos vidas que tu oponente: un aliado puede invocar especialmente 1 criatura "
                                + "en tu mano reduciendo el coste especial en 1.",
                        List.of(new SpecialFromZone("any", "hand", 1, "any", CardFilter.creatures())))
                        .when(all(new ThisTributed("field", null, null), new FewerLivesThanOpponent()))
                        .fromZone("tributeZone"),
                new EffectScript("caballero-leon-dorado:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes tributar 1 aliado coste 2 o mayor distinto de este para ganar 1 gema.",
                        List.of(new IfPossible(), tributeOwnCard(CardFilter.creatures().withMinCost(2)), gainGems(1))));
        // E1.3-R07 Caballero De Apoyo: "hasta el final del combate"
        add("caballero de apoyo", new EffectScript("caballero-de-apoyo:todos-los-turnos", Moment.ON_EVENT, true, TODOS,
                "Si esta carta es agotada durante un combate: elige un aliado; este gana fuerza 2 hasta el final del combate.",
                List.of(new PickAlly(false, true)), List.of(gainStrengthUntil(2, "attack")))
                .when(all(new ThisExhausted(), new DuringCombat())));
        // E1.3-R08 Armadura Del Alma Impasible
        add("armadura del alma impasible",
                new EffectScript("armadura-alma-impasible:en-mano", Moment.ACTIVATED, false, "Turno Aliado / En Mano",
                        "(1) Si solo controlas 1 aliado: puedes revelar un [retador] nivel 4 y uno nivel 5 de tu mano; invoca esta carta sin pagar su coste.",
                        List.of(new RevealFromHand(List.of(CardFilter.any().withClasses("retador").withLevel(4),
                                CardFilter.any().withClasses("retador").withLevel(5)))), List.of(summonSelfFree()))
                        .when(new CountOnField(CardFilter.creatures(), 1, 1))
                        .fromZone("hand")
                        .onlyAllyTurn()
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("armadura-alma-impasible:todos-los-turnos", Moment.ON_EVENT, true, TODOS,
                        "Si un aliado es invocado especialmente: coloca esta carta al tope de tus vidas bocabajo.",
                        List.of(selfToLifeFrom(false)))
                        .when(new YouSpecialSummoned(CardFilter.creatures())));
        // E1.3-R09 Filo De Sangre
        add("filo de sangre", new EffectScript("filo-de-sangre:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Esta carta gana fuerza 1 por cada vida más que tenga tu oponente que tú, hasta el final del turno.",
                List.of(gainStrengthBy(new Amount.LivesBehind(false)))));
        // E1.3-R10 Caballería De Primera Línea (Agrupar 1: keyword)
        add("caballería de primera línea", new EffectScript("caballeria-primera-linea:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Una vez por turno: si esta carta activa Agrupar, puedes tributar 1 carta de tu mano para reactivar esta carta.",
                List.of(new IfPossible(), tributeFromHand(1), untapSelf()))
                .when(new ThisUsedKeyword("Agrupar"))
                .limited(Limit.ONCE_PER_TURN));
        // E1.3-R11 Heroe De Primera Línea
        add("heroe de primera línea", new EffectScript("heroe-primera-linea:final-del-turno", Moment.ON_EVENT, true, "Turno Aliado",
                "Al final del turno: reactiva hasta 3 aliados con \"Primera Línea\" en su nombre.",
                List.of(untapAllies(3, primeraLinea)))
                .when(new TurnEnds())
                .onlyAllyTurn());
        // E1.3-R14 Fervor De La Primera Línea (utility)
        add("fervor de la primera línea",
                new EffectScript("fervor-primera-linea:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "(1) Puedes invocar 1 criatura con \"Primera Línea\" en su nombre de tu mano, en tu línea frontal reduciendo su coste en 1.",
                        List.of(new MayDo("¿Invocas 1 criatura con \"Primera Línea\" en su nombre de tu mano en tu línea frontal?", 1),
                                summonFromZone("hand", primeraLinea, 1, true)))
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("fervor-primera-linea:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "Si algún aliado es destruido por el oponente: puedes jugar esta carta sin pagar su coste.",
                        List.of(playSelfFree()))
                        .when(new AllyDestroyed(CardFilter.any(), true, false))
                        .fromZone("hand"));
        // E1.3-R15 Investidura De Los Leones Dorados (utility)
        add("investidura de los leones dorados",
                new EffectScript("investidura-leones-dorados:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "(1) Elige 1 aliado; súbelo a tu mano para invocar en tu línea frontal 1 criatura con \"León Dorado\" en su nombre de nivel igual "
                                + "o menor, pero distinto nombre de tu mano sin pagar su coste (si tiene).",
                        List.of(new PickAlly(false, true)),
                        List.of(new IfPossible(), returnSubjectToHand(),
                                summonRelativeToLast("hand", CardFilter.creatures().nameContains("León Dorado"), FREE, true, "upToOtherName")))
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("investidura-leones-dorados:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "Inmediatamente después de que un aliado sea objetivo de un efecto: puedes tributar otras 2 cartas de tu mano para jugar esta carta.",
                        List.of(new IfPossible(), tributeFromHand(2), playSelfFromZone("hand", false)))
                        .when(new Immediately(new AllyTargeted()))
                        .fromZone("hand"));
        // E1.3-W01 Viaje Extenso
        add("viaje extenso",
                new EffectScript("viaje-extenso:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Efecto Definitivo. Si aún no has jugado ni invocado otra carta este turno: roba 3 cartas. Al final de tu turno: envía cartas de "
                                + "tu mano al fondo de tu mazo hasta tener 4 cartas en tu mano.",
                        List.of(draw(3), new AtEndOfTurn("viaje-extenso:final-del-turno")))
                        .when(new NothingElsePlayedThisTurn())
                        .limited(Limit.DEFINITIVE),
                new EffectScript("viaje-extenso:final-del-turno", Moment.DELAYED, true, "Al final de tu turno",
                        "Envía cartas de tu mano al fondo de tu mazo hasta tener 4 cartas en tu mano.",
                        List.of(handDownTo(4))));
        // E1.3-W02 Sueño Premonitorio
        add("sueño premonitorio", new EffectScript("sueno-premonitorio:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Efecto Definitivo. Elige al azar hasta 3 cartas de la mano del oponente para que las revele; envía 1 de ellas al fondo del mazo.",
                List.of(new RevealOpponentHandSendOneToBottom(3)))
                .limited(Limit.DEFINITIVE));
        // E1.3-W03 Promesa Rota
        add("promesa rota", new EffectScript("promesa-rota:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Efecto Definitivo. Si algún aliado fue destruido por el oponente el turno anterior: puedes invocar 1 criatura de tu mano reduciendo su "
                        + "coste en 1.",
                List.of(new MayDo("¿Invocas 1 criatura de tu mano reduciendo su coste en 1?", 1), summonFromZone("hand", CardFilter.creatures(), 1)))
                .when(new AllyDestroyedByOpponentLastTurn())
                .limited(Limit.DEFINITIVE));
        // E1.3-W04 Condensador De Materia
        add("condensador de materia", new EffectScript("condensador-de-materia:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Efecto Definitivo. Coloca bocabajo todas tus vidas bocarriba. Luego: baraja tus vidas.",
                List.of(new LivesFaceDown(true, true)))
                .limited(Limit.DEFINITIVE));
        // E1.3-W05 Magia Incontrolable
        add("magia incontrolable", new EffectScript("magia-incontrolable:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Efecto Definitivo. Toma 1 utilidad no-blanca de tu descarte.",
                List.of(takeFromZone("discardPile", CardFilter.type("utility").notColors("W"), 1, false)))
                .limited(Limit.DEFINITIVE));
    }

    /** "En Destrucción: puedes colocar esta carta al tope de tus vidas bocarriba." */
    private static EffectScript selfToLifeFaceUp(String id) {
        return new EffectScript(id + ":en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                "Puedes colocar esta carta al tope de tus vidas bocarriba.",
                List.of(selfToLifeFrom(true)));
    }

    /**
     * "En Tributos: una vez por turno entre copias, si algún enemigo es destruido por tus efectos: puedes invocar esta carta en tu línea
     * frontal reduciendo su coste en 1 por cada 10 tributos Violeta que tengas. Si lo haces: tribútala al final del turno."
     */
    private static EffectScript reaperFromTributes(String id) {
        return new EffectScript(id + ":en-tributos", Moment.ON_EVENT, false, "En Tributos",
                "(1) Si algún enemigo es destruido por tus efectos: puedes invocar esta carta en tu línea frontal reduciendo su coste en 1 por cada 10 "
                        + "tributos Violeta que tengas. Si lo haces: tribútala al final del turno.",
                List.of(summonSelf("tributeZone", new Amount.Scaled(new Amount.InZone("tributeZone", CardFilter.colors("P")), 10, 0), true),
                        new IfDone(1), new AtEndOfTurn(id + ":final-del-turno")))
                .when(new YouDestroyedEnemy(true))
                .fromZone("tributeZone")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES);
    }

    private static EffectScript tributeAtTurnEnd(String id) {
        return new EffectScript(id + ":final-del-turno", Moment.DELAYED, true, "Al final del turno",
                "Tributa esta carta.",
                List.of(sendSelfFromFieldTo("tributeZone")));
    }

    // ------------------------------------------------------------------ E2, Azul, Verde y Violeta (2026-10-31; texts from the printed cards)
    static {
        CardFilter enemy = CardFilter.creatures();
        CardFilter neomaquina = CardFilter.creatures().withClasses("neomáquina");
        CardFilter artefacto = CardFilter.any().withClasses("artefacto");
        CardFilter dinosaurio = CardFilter.any().withClasses("dinosaurio");
        CardFilter muertoViviente = CardFilter.creatures().withClasses("muerto viviente");
        CardFilter brujeria = CardFilter.any().withClasses("brujería");
        CardFilter espectroP = CardFilter.any().withColors("P").withClasses("espectro");
        // E2-B01 Sicario R3-D (Iniciador)
        add("sicario r3-d",
                new EffectScript("sicario-r3-d:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Mira las primeras 4 cartas de tu mazo: de ellas toma hasta 2 cartas Azules o Blancas. Envía el resto al fondo de tu mazo.",
                        List.of(lookTop(4), takeFromLookedRestToBottom(2, CardFilter.colors("B", "W")))),
                new EffectScript("sicario-r3-d:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                        "Si algún aliado [neomáquina] es invocado especialmente: puedes añadirle esta carta como material para que declare ataque.",
                        List.of(new IfPossible(), new SubjectFromTrigger(), attachSelfToSubject(), new DeclareAttack()))
                        .when(new YouSpecialSummoned(neomaquina)));
        // E2-B02 Flerik el Ingeniero Mercader (Iniciador)
        add("flerik el ingeniero mercader",
                new EffectScript("flerik:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Mira las primeras 4 cartas de tu mazo, de ellas toma hasta 1 [cineriano] o [artefacto] y hasta 1 utilidad o estructura Azul. "
                                + "Envía el resto al fondo de tu mazo.",
                        List.of(lookTop(4), new TakeFromLookedEachRestToBottom(List.of(CardFilter.any().withClasses("cineriano", "artefacto"),
                                CardFilter.type("utility", "structure").withColors("B"))))),
                new EffectScript("flerik:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                        "Puedes agotar esta carta en tu línea frontal para colocar 1 [artefacto] de tu mano en tu retaguardia.",
                        List.of(new IfPossible(), tapSelf(), placeFromZone("hand", artefacto, 1, false, false)))
                        .when(new InFrontLine())
                        .onlyAllyTurn());
        // E2-B03 Falco el Buscador de Tesoros
        add("falco el buscador de tesoros", new EffectScript("falco:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "(1) Puedes tributar 1 material de esta carta para jugar 1 estructura Azul de tu mano. Luego, si no controlas otros aliados: puedes "
                        + "tributar 1 [artefacto] de los materiales de esta carta para colocar 1 [cineriano] nivel 1 de los materiales de esta carta en tu "
                        + "línea frontal.",
                List.of(new MayDo("¿Tributas 1 material de Falco para jugar 1 estructura Azul de tu mano?", 2), tributeMaterials(1, false),
                        playFromZone("hand", CardFilter.type("structure").withColors("B"), 0),
                        new IfCondition(new CountOnField(CardFilter.creatures(), null, 1), 3),
                        new MayDo("¿Tributas 1 [artefacto] de sus materiales para colocar 1 [cineriano] nivel 1 de sus materiales en tu línea frontal?", 2),
                        tributeMaterialWhere(artefacto), materialToFrontLine(CardFilter.creatures().withClasses("cineriano").withLevel(1))))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E2-B04 Clarice la Ejecutora de Artefactos
        add("clarice la ejecutora de artefactos",
                new EffectScript("clarice:en-mano", Moment.ACTIVATED, false, "Turno Aliado / En Mano",
                        "(1) Si no controlas copias de esta carta: puedes subir a tu mano 1 [artefacto] bajo tu control para invocar esta carta en tu "
                                + "línea frontal.",
                        List.of(new IfPossible(), returnOwnCardToHand(artefacto), summonSelf("hand", Amount.fixed(0), true)))
                        .when(new NoCopies())
                        .fromZone("hand")
                        .onlyAllyTurn()
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("clarice:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                        "Si un [artefacto] entra tu línea frontal: esta carta puede invocar especialmente 1 criatura de tu mano reduciendo en 2 el coste "
                                + "especial.",
                        List.of(new SpecialFromZone("any", "hand", 2, "self", CardFilter.creatures())))
                        .when(new EntersFrontLine(false, artefacto)));
        // E2-B06 Unidad Heróica V10-L3T
        add("unidad heróica v10-l3t",
                new EffectScript("v10-l3t:tributada-como-material", Moment.ON_EVENT, true, "En Tributos",
                        "Si esta carta es tributada como material durante un combate: roba 1 carta.",
                        List.of(draw(1)))
                        .when(all(new TributedAsMaterial(0), new DuringCombat()))
                        .fromZone("tributeZone"),
                new EffectScript("v10-l3t:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                        "Puedes tributar 2 materiales de esta carta; esta carta gana Enfrentar 3 hasta el final del ataque.",
                        List.of(tributeMaterials(2, false)), List.of(gainUntilEndOfAttack("Enfrentar 3"))));
        // E2-B07 Arthur Collier (Perforante: combat rule)
        add("arthur collier", new EffectScript("arthur-collier:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Si esta carta enfrenta un enemigo como atacante: puedes tributar 2 materiales de esta carta para reducir su fuerza en 2 por el valor "
                        + "de tu marcador de gemas.",
                List.of(new IfPossible(), tributeMaterials(2, false), reduceClashOpponent(new Amount.Times(new Amount.Gems(), 2))))
                .when(new ThisClashes(enemy, true)));
        // E2-B08 Albert Collier el Tecnomago: its replacement for allies [cineriano] is ALLY_GUARDS (Legendaria: manual)
        // E2-B09 Fausto: the rest of the 6 cards goes to the bottom of the deck (the printed text does not say where)
        add("fausto",
                new EffectScript("fausto:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Mira las primeras 6 cartas de tu mazo: de ellas toma hasta 2 cartas Azules, Blancas, [artefacto] y/o [cineriano].",
                        List.of(lookTop(6), takeFromLookedRestToBottom(2, CardFilter.colors("B", "W").withClasses("artefacto", "cineriano")))),
                new EffectScript("fausto:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                        "Puedes tributar 2 materiales de esta carta para jugar 1 utilidad Azul o [artefacto] de tu mano o tributos reduciendo en 1 su coste.",
                        List.of(new IfPossible(), tributeMaterials(2, false),
                                playFromZone("hand,tributeZone", CardFilter.type("utility").withColors("B").withClasses("artefacto"), 1)))
                        .onlyAllyTurn());
        // E2-B10 Unidad de Combate Frontal F1-R3
        add("unidad de combate frontal f1-r3",
                new EffectScript("f1-r3:tributada-como-material", Moment.ON_EVENT, false, "En Tributos",
                        "Si esta carta es tributada como material durante un combate: puedes elegir 1 enemigo; reduce en 2 su fuerza hasta el final del combate.",
                        List.of(chooseEnemy(1, enemy)), List.of(reduceChosenUntilAttack(2)))
                        .when(all(new TributedAsMaterial(0), new DuringCombat()))
                        .fromZone("tributeZone"),
                new EffectScript("f1-r3:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                        "Puedes tributar 2 materiales de esta carta; juega 1 utilidad o estructura Azul o Blanca de tu mano reduciendo su coste en 1.",
                        List.of(tributeMaterials(2, false)),
                        List.of(playFromZone("hand", CardFilter.type("utility", "structure").withColors("B", "W"), 1))));
        // E2-B11 Unidad Policial B1-U3
        add("unidad policial b1-u3",
                new EffectScript("b1-u3:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "(1) Si algún aliado destruye un enemigo por enfrentamiento: dicho aliado puede invocar esta carta por Materialización ignorando "
                                + "el requerimiento y sin pagar el coste especial.",
                        List.of(new MaterializeSelfOnTriggerAlly()))
                        .when(new AllyDestroyedEnemyInClash())
                        .fromZone("hand")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("b1-u3:al-final-del-ataque", Moment.ON_ATTACK_END, true, AL_FINAL,
                        "Si algún enemigo fue destruido durante el combate: esta carta inflige daño directo.",
                        List.of(damageBySelfStrength()))
                        .when(new EnemyDestroyedInAttack()));
        // E2-B12 Unidad de Seguridad A-5H
        add("unidad de seguridad a-5h",
                new EffectScript("a-5h:tributada-como-material", Moment.ON_EVENT, false, "En Tributos",
                        "(1) Si esta carta es tributada como material: puedes tomar 1 carta Azul de tus tributos.",
                        List.of(takeFromZone("tributeZone", CardFilter.colors("B"), 1, false)))
                        .when(new TributedAsMaterial(0))
                        .fromZone("tributeZone")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("a-5h:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "(1) Si un aliado inflige daño directo: puedes invocar esta carta de forma especial reduciendo su coste especial en 2.",
                        List.of(specialSelf("hand", 2)))
                        .when(new AllyDealtDirectDamage(CardFilter.any()))
                        .fromZone("hand")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E2-B13 Helena la Pirata de Cineria (the opponent's face-up lives: designer ruling 2026-10-31)
        add("helena la pirata de cineria",
                new EffectScript("helena:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "Si fueron removidos [artefactos] de los materiales de algún aliado por tus efectos: puedes invocar esta carta especialmente.",
                        List.of(specialSelf("hand", 0)))
                        .when(new MaterialRemovedByYou(artefacto))
                        .fromZone("hand"),
                new EffectScript("helena:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Tributa hasta 3 materiales de esta carta y elige hasta 3 enemigos; reduce la fuerza de las criaturas elegidas y las de las vidas "
                                + "oponentes en 1 por el valor de tu contador de gemas, hasta el final del turno.",
                        List.of(tributeMaterials(3, true), chooseEnemy(3, enemy, true)),
                        List.of(reduceChosenBy(new Amount.Gems()), new ReduceOpponentLivesBy(new Amount.Gems()))));
        // E2-B14 Unidad BL-Killer (Furia 2: keyword)
        add("unidad bl-killer", new EffectScript("bl-killer:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Puedes tributar 2 materiales de esta carta; esta carta gana Letalidad 1 hasta el final del ataque.",
                List.of(tributeMaterials(2, false)), List.of(gainUntilEndOfAttack("Letalidad 1"))));
        // E2-B16 Prototipo XTRM-1N3 (its "si utilizas al menos 2 criaturas en tu campo: reduce el coste especial en 3": FIELD_PIECES_DISCOUNTS)
        add("prototipo xtrm-1n3", new EffectScript("xtrm-1n3:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Puedes tributar 2 materiales de esta carta y elegir hasta 2 enemigos de fuerza 5 o menos; reduce sus fuerzas a 0.",
                List.of(tributeMaterials(2, false), chooseEnemy(2, enemy.withMaxStrength(5), true)), List.of(reduceChosenToZero())));
        // E2-B17 Roto-G0: the reprint of E1 Roto G0 (another metadata name)
        add("roto-g0",
                new EffectScript("roto-g0-e2:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes tributar hasta 3 materiales de esta carta y elegir hasta 3 veces un enemigo; reduce en 2 la fuerza del enemigo elegido.",
                        List.of(tributeMaterials(3, true), new ReduceRepeatedly(-1, 2, "turn"))),
                new EffectScript("roto-g0-e2:al-final-del-ataque", Moment.ON_ATTACK_END, false, AL_FINAL,
                        "Puedes subir esta carta a tu mano para ganar 2 gemas.",
                        List.of(new IfPossible(), returnSelfToHand(), gainGems(2))));
        // E2-B18 Unidad Exoplanetaria de Rescate ("5 más en nivel": the card was corrected, 2026-10-31)
        add("unidad exoplanetaria de rescate",
                new EffectScript("exoplanetaria:en-mano", Moment.ACTIVATED, false, "Turno Aliado / En Mano",
                        "Si las criaturas oponentes suman al menos 5 más en nivel que tus aliados: una criatura en tu mano o tributos puede invocar "
                                + "especialmente esta carta.",
                        List.of(new SpecialSelfDeclaredSummoner("hand,tributeZone")))
                        .when(new OpponentLevelsExceed(5))
                        .fromZone("hand")
                        .onlyAllyTurn(),
                new EffectScript("exoplanetaria:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes tributar materiales de esta carta: por cada 2 materiales tributados elige 1 enemigo; reduce en 2 su fuerza.",
                        List.of(tributeMaterials(99, true), chooseEnemiesPerCountEvery(enemy, 2)), List.of(reduceChosen(2, false))));
        // E2-B21 Puerto de Cineria (structure): an [artefacto] utility can become a creature in the Línea frontal
        add("puerto de cineria",
                new EffectScript("puerto-cineria:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                        "Puedes agotar esta carta para reactivar 1 [artefacto] en tu retaguardia.",
                        List.of(new IfPossible(), tapSelf(), untapOwnCard(artefacto.inBack())))
                        .onlyAllyTurn(),
                new EffectScript("puerto-cineria:final-del-turno", Moment.ON_EVENT, false, "Turno Aliado",
                        "Al final del turno, si tienes menos de 0 gemas: puedes tributar 1 [artefacto] bajo tu control para ganar 1 gema.",
                        List.of(new IfPossible(), tributeOwnCard(artefacto), gainGems(1)))
                        .when(all(new TurnEnds(), not(new GemsAtLeast(0))))
                        .onlyAllyTurn());
        // E2-B22 Preparando Expedición (utility)
        costModifier("preparando expedición", new CostModifier("Absoluto", CostModifiers.Event.PLAY, Set.of(), CostModifiers.Holder.SELF, false,
                new CountOnField(artefacto.inBack(), null, 0), Payment.none(), 1));
        add("preparando expedición", new EffectScript("preparando-expedicion:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "(1) Puedes jugar 1 [artefacto] de tu mano. Si lo haces: puedes colocar 1 [artefacto] de los materiales de un aliado agotado en tu "
                        + "retaguardia.",
                List.of(new MayDo("¿Juegas 1 [artefacto] de tu mano?", 1), playFromZone("hand", artefacto, 0), new IfDone(1),
                        new MaterialOfExhaustedAllyToBack(artefacto)))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E2-B23 Potencia Nuclear (utility)
        add("potencia nuclear", new EffectScript("potencia-nuclear:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Puedes tributar 2 materiales de 1 aliado [autómata], [neomáquina] o [mecha] para que declare ataque sin agotarse. Si lo hace: al final "
                        + "del ataque súbelo a tu mano con todos sus materiales.",
                List.of(new MayDo("¿Tributas 2 materiales de 1 aliado [autómata], [neomáquina] o [mecha] para que declare ataque?", 5),
                        new PickAttacker(CardFilter.creatures().withClasses("autómata", "neomáquina", "mecha")), tributeSubjectMaterials(2),
                        new DeclareAttack(), new IfCondition(new DuringCombat(), 1), new ReturnSubjectAtAttackEnd())));
        // E2-B24 Alter Neo (utility)
        add("alter neo", new EffectScript("alter-neo:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Elige 1 aliado [neomáquina]; súbelo a tu mano con todos sus materiales. Luego: invoca de forma especial 1 [neomáquina] de tu mano del "
                        + "mismo nivel, pero distinto nombre y sin pagar su coste especial.",
                List.of(new PickAllyWhere(neomaquina, true)),
                List.of(returnSubjectMaterialsToHand(), returnSubjectToHand(), specialRelativeToLast("hand", neomaquina, FREE))));
        // E2-B25 Cianotipo (utility)
        add("cianotipo",
                new EffectScript("cianotipo:tributada-como-material", Moment.ON_EVENT, true, "En Tributos",
                        "Si esta carta es tributada como material: roba 1 carta.",
                        List.of(draw(1)))
                        .when(new TributedAsMaterial(0))
                        .fromZone("tributeZone"),
                new EffectScript("cianotipo:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "(1) Tributa hasta 3 materiales de un aliado [mecha] o [neomáquina]; roba la misma cantidad de cartas. Luego: añade esta carta "
                                + "como material a un aliado.",
                        List.of(new PickAllyWhere(CardFilter.creatures().withClasses("mecha", "neomáquina")), tributeSubjectMaterialsUpTo(3)),
                        List.of(drawLastCount(), new PickAlly(false), attachSelfToSubject()))
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));

        // E2-G02 Deinonicus (Iniciador)
        add("deinonicus", new EffectScript("deinonicus:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Tributa las primeras 3 cartas de tu mazo. Luego: puedes tomar 1 carta Verde, Blanca o [dinosaurio] de tus tributos.",
                List.of(tributeTop(3), takeFromZone("tributeZone", CardFilter.colors("G", "W").withClasses("dinosaurio"), 1, true))));
        inherit("deinonicus", dinoGem("deinonicus", "Deinonicus"));
        // E2-G04 Arqueopterix
        add("arqueopterix",
                new EffectScript("arqueopterix:tributada", Moment.ON_EVENT, false, "En Tributos",
                        "(1) Si esta carta es tributada: puedes tributar 1 carta de tu mano; toma 1 [dinosaurio] de tus tributos.",
                        List.of(tributeFromHand(1)), List.of(takeFromZone("tributeZone", dinosaurio, 1, false)))
                        .when(new ThisTributed(null, null, null))
                        .fromZone("tributeZone")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("arqueopterix:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "(1) Si juegas alguna estructura Verde: puedes colocar esta carta en tu retaguardia.",
                        List.of(placeSelfOnField("hand")))
                        .when(new YouPlayed(CardFilter.type("structure").withColors("G")))
                        .fromZone("hand")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        inherit("arqueopterix", dinoGem("arqueopterix", "Arqueopterix"));
        // E2-G05 Lyldra (Iniciador)
        add("lyldra",
                new EffectScript("lyldra:tributada-como-recurso", Moment.ON_EVENT, false, "En Tributos",
                        "(1) Si esta carta es tributada como recurso: puedes colocarla agotada en tu retaguardia.",
                        List.of(placeSelf("tributeZone", false, true)))
                        .when(new ThisTributed("resources", null, null))
                        .fromZone("tributeZone")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("lyldra:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Tributa 3 cartas del tope de tu mazo. Luego: añade hasta 1 [hydra] de tus tributos como recurso de esta carta.",
                        List.of(tributeTop(3), addResourceFromZone("tributeZone", CardFilter.any().withClasses("hydra"), true))));
        inherit("lyldra", new EffectScript("lyldra:hereda-al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Heredado de Lyldra: puedes enviar 1 carta de tu mano al fondo de tu mazo para tomar 1 carta Verde, Blanca o [hydra] de tus tributos.",
                List.of(new IfPossible(), handToDeckBottom(1), takeFromZone("tributeZone", CardFilter.colors("G", "W").withClasses("hydra"), 1, false))));
        // E2-G06 Dodo (Celeridad: keyword)
        add("dodo", new EffectScript("dodo:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                "Puedes añadir esta carta en tu descarte como recurso de un aliado.",
                List.of(new AttachSelfAsResourceToAlly()))
                .when(new ThisWentToDiscard()));
        inherit("dodo", dinoGem("dodo", "Dodo"));
        // E2-G07 Dromeorax (Enfrentar: combat rule)
        inherit("dromeorax", dinoGem("dromeorax", "Dromeorax"));
        // E2-G08 Sarkhydrax (its inherited reaction: INHERITED_REPLACEMENTS)
        add("sarkhydrax", new EffectScript("sarkhydrax:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Tributa 3 cartas del tope de tu mazo. Luego: añade hasta 1 [hydra] o [dragón] de fuerza 6 o menos de tu mano o tributos como recurso "
                        + "de esta carta.",
                List.of(tributeTop(3), addResourceFromZone("hand,tributeZone", CardFilter.creatures().withClasses("hydra", "dragón").withMaxStrength(6), true))));
        // E2-G09 Petrodalix: "con [dinosaurio] en su texto" = of that type, in its name or in its effects (designer ruling 2026-10-31)
        add("petrodalix", new EffectScript("petrodalix:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "(1) Tributa 3 cartas del tope de tu mazo. Luego: puedes jugar 1 estructura con [dinosaurio] en su texto de tu mano o tributos "
                        + "reduciendo su coste en 1.",
                List.of(tributeTop(3), new MayDo("¿Juegas 1 estructura [dinosaurio] de tu mano o tributos reduciendo su coste en 1?", 1),
                        playFromZone("hand,tributeZone", CardFilter.type("structure").mentioning("dinosaurio"), 1)))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        inherit("petrodalix", dinoGem("petrodalix", "Petrodalix"));
        // E2-G10 Caudapterix, E2-G13 Spinosaurus (Celeridad: keyword)
        add("caudapterix", sacrificeWeaker("caudapterix"));
        inherit("caudapterix", dinoGem("caudapterix", "Caudapterix"));
        add("spinosaurus", sacrificeWeaker("spinosaurus"));
        inherit("spinosaurus", dinoGem("spinosaurus", "Spinosaurus"));
        // E2-G11 Deriduragón: the reprint of E1 Deriduragon (another metadata name)
        add("deriduragón", new EffectScript("deriduragon-e2:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Tributa 3 cartas del tope de tu mazo. Luego: puedes añadir 1 criatura de fuerza 5 o menor de tu mano o tributos como recurso de esta carta.",
                List.of(tributeTop(3), addResourceFromZone("hand,tributeZone", CardFilter.creatures().withMaxStrength(5), true))));
        // E2-G14 Yako Kitsune
        add("yako kitsune", new EffectScript("yako-kitsune:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Elige hasta 2 enemigos; puedes invocar 1 recurso de esta carta reduciendo su coste en 2. Si lo haces: reduce la fuerza de los "
                        + "enemigos elegidos en 2 hasta el final del turno.",
                List.of(chooseEnemy(2, enemy, true)), List.of(summonResource(2), new IfDone(1), reduceChosen(2, false))));
        inherit("yako kitsune", new EffectScript("yako-kitsune:hereda-turno-oponente", Moment.ON_EVENT, false, "Turno Oponente",
                "Heredado de Yako Kitsune: al entrar al paso de bloqueo, puedes tributar el recurso de este efecto; esta carta agotada declara bloqueo.",
                List.of(tributeResource("Yako Kitsune")), List.of(new DeclareBlockSelf()))
                .when(all(not(new YourTurn()), new CombatStep(Combat.Step.BLOCK, Combat.Phase.ENTER))));
        // E2-G15 Triceratops
        add("triceratops", new EffectScript("triceratops:turno-oponente", Moment.ON_EVENT, false, "Turno Oponente",
                "Una vez por turno: si esta carta es objetivo de un efecto o es declarada bloqueador, puedes añadir 1 [dinosaurio] de tu mano como "
                        + "recurso de esta carta. Si lo haces: reactívala.",
                List.of(addResourceFromZone("hand", dinosaurio, false), new IfDone(1), untapSelf()))
                .when(all(not(new YourTurn()), any(new ThisTargeted(), new ThisDeclaredBlocker())))
                .limited(Limit.ONCE_PER_TURN));
        inherit("triceratops", dinoGem("triceratops", "Triceratops"));
        // E2-G16 Nereidra (inherited Reactivar: keyword)
        add("nereidra", new EffectScript("nereidra:tributada-como-recurso", Moment.ON_EVENT, false, "En Tributos",
                "Si esta carta es tributada como recurso: un aliado puede invocarla especialmente sin pagar su coste especial.",
                List.of(specialSelf("tributeZone", FREE)))
                .when(new ThisTributed("resources", null, null))
                .fromZone("tributeZone"));
        // E2-G17 Braquiosaurus
        add("braquiosaurus",
                new EffectScript("braquiosaurus:todos-los-turnos", Moment.ON_EVENT, true, TODOS,
                        "Si esta carta es agotada: sacrifica todas las criaturas en la línea frontal de fuerza 5 o menos.",
                        List.of(sacrificeAllInFrontLine(CardFilter.creatures().withMaxStrength(5))))
                        .when(new ThisExhausted()),
                new EffectScript("braquiosaurus:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                        "Gana 2 gemas.",
                        List.of(gainGems(2))));
        // E2-G18 Quetzalcoatlus
        add("quetzalcoatlus", new EffectScript("quetzalcoatlus:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Puedes sacrificar 1 aliado con fuerza menor a esta carta; añade 1 [dinosaurio] de tus tributos como recurso de esta carta. Si lo "
                        + "haces: salta el paso de bloqueo.",
                List.of(sacrificeAllyWeakerThanSelf()),
                List.of(addResourceFromZone("tributeZone", dinosaurio, false), new IfDone(1), new SkipBlockStep())));
        // E2-G20 Titanorax (its inherited replacement: INHERITED_REPLACEMENTS)
        add("titanorax", new EffectScript("titanorax:turno-oponente", Moment.ON_EVENT, true, "Turno Oponente",
                "Si esta carta es agotada: elige hasta 3 enemigos de fuerza 5 o menos; destrúyelos.",
                List.of(chooseEnemy(3, enemy.withMaxStrength(5), true)), List.of(destroyChosen()))
                .when(all(not(new YourTurn()), new ThisExhausted())));
        // E2-G21 Carnirex (its "cuando fueras a invocar esta carta: puedes sacrificar 1 aliado [dinosaurio] activo…": SACRIFICE_TO_SUMMON)
        add("carnirex", new EffectScript("carnirex:al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Elige 1 criatura de menor fuerza que esta carta; sacrifícala. Si era un aliado activo: destruye el tope de las vidas oponentes.",
                List.of(new ChooseWeakerThanSelf(false)), List.of(sacrificeChosen(), new IfChosenWasActiveAlly(1), new DestroyTopLife())));
        // E2-G22 Titanobrachios (Reactivar: keyword)
        add("titanobrachios", new EffectScript("titanobrachios:en-mano", Moment.ACTIVATED, false, "Turno Aliado / En Mano",
                "Puedes enviar hasta 20 cartas Verdes o [dinosaurio] de tus tributos al fondo de tu mazo en orden aleatorio; invoca esta carta "
                        + "reduciendo su coste en 1 por cada 2 cartas enviadas.",
                List.of(sendFromZoneToDeckBottomUpTo("tributeZone", 20, CardFilter.colors("G").withClasses("dinosaurio"))), List.of(summonSelfPerCount("hand", 2)))
                .fromZone("hand")
                .onlyAllyTurn());
        // E2-G23 Tierra Jurásica (structure)
        add("tierra jurásica", new EffectScript("tierra-jurasica:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "(1) Si algún aliado es destruido por tus efectos: puedes agotar hasta tantas copias de esta carta como controles; invoca de tu mano o "
                        + "tributos, sin pagar su coste, 1 [dinosaurio] de nivel igual a la cantidad de copias de esta carta agotadas por este efecto.",
                List.of(tapCopiesUpTo()), List.of(summonLevelLastCount("hand,tributeZone", CardFilter.creatures().withClasses("dinosaurio"))))
                .when(new AllyDestroyedByYourEffect())
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E2-G24 Primer Aliento (utility that stays)
        add("primer aliento",
                new EffectScript("primer-aliento:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Mira las primeras 4 cartas de tu mazo: de ellas toma hasta 2 cartas Verdes. Envía el resto al fondo de tu mazo. Luego: tributa 1 "
                                + "carta de tu mano y coloca esta carta agotada en tu retaguardia.",
                        List.of(lookTop(4), takeFromLookedRestToBottom(2, CardFilter.colors("G")), tributeFromHand(1), new KeepOnField(true))),
                new EffectScript("primer-aliento:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                        "Puedes enviar esta carta activa a tu descarte para invocar 1 criatura Verde de tu mano reduciendo su coste en 1.",
                        List.of(new IfPossible(), sendSelfFromFieldTo("discardPile"), summonFromZone("hand", CardFilter.creatures().withColors("G"), 1)))
                        .when(new Active())
                        .onlyAllyTurn());
        // E2-G25 Fósil (utility; "considerada criatura de fuerza 1": CREATURE_FOR_EFFECTS; its inherited reaction: INHERITED_REPLACEMENTS)
        add("fósil", new EffectScript("fosil:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Añade esta carta como recurso a un aliado.",
                List.of(new PickAlly(false), attachSelfAsResourceToSubject(), new KeepOnField(false))));

        // E2-P01 Calavera Errante (Iniciador)
        add("calavera errante",
                new EffectScript("calavera-errante:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Roba 1 carta. Luego: tributa 1 carta de tu mano.",
                        List.of(draw(1), tributeFromHand(1))),
                new EffectScript("calavera-errante:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION + " por efecto o combate",
                        "(1) Puedes tributar 1 carta de tu mano; al final del turno: invoca 1 \"Calavera Errante\" agotado en tu línea frontal, de tu "
                                + "descarte o tributos, sin pagar su coste.",
                        List.of(tributeFromHand(1)), List.of(new AtEndOfTurn("calavera-errante:final-del-turno")))
                        .when(any(new DestroyedByEffect(), new DestroyedInClash()))
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("calavera-errante:final-del-turno", Moment.DELAYED, true, "Al final del turno",
                        "Invoca 1 \"Calavera Errante\" agotado en tu línea frontal, de tu descarte o tributos, sin pagar su coste.",
                        List.of(summonFromZone("discardPile,tributeZone", CardFilter.creatures().named("Calavera Errante"), FREE, true),
                                tapSummoned())));
        // E2-P03 Asechador De Las Almas Perdidas
        add("asechador de las almas perdidas", new EffectScript("asechador:tributada", Moment.ON_EVENT, false, "En Tributos",
                "(1) Si esta carta es tributada: puedes enviar esta carta bocabajo a tu descarte y enviar 5 cartas [muerto viviente] de entre tus "
                        + "tributos y descarte al fondo de tu mazo para ganar 1 gema.",
                List.of(new IfPossible(), sendFromZoneToDeckBottom("tributeZone,discardPile", 5,
                        CardFilter.any().withClasses("muerto viviente").notNamed("Asechador De Las Almas Perdidas")),
                        sendSelfTo("discardPile"), gainGems(1)))
                .when(new ThisTributed(null, null, null))
                .fromZone("tributeZone")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E2-P05 Bruja Lunar
        add("bruja lunar", new EffectScript("bruja-lunar:en-tributos", Moment.ACTIVATED, false, "Turno Aliado / En Tributos",
                "(1) Puedes elegir 1 criatura nivel 2 o menor de los tributos oponentes; coloca la criatura elegida y esta carta en la línea frontal "
                        + "de sus dueños.",
                List.of(new ChooseOpponentTribute(CardFilter.creatures().withMaxLevel(2))), List.of(new PlaceChosenTributeAndSelf()))
                .fromZone("tributeZone")
                .onlyAllyTurn()
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E2-P07 Alarido Espectral: "por cada 10 tributos Violeta o [espectro]: reduce en 2 el coste de esta carta"
        costModifier("alarido espectral", new CostModifier("Absoluto", CostModifiers.Event.SUMMON, Set.of(), CostModifiers.Holder.SELF, false, null,
                Payment.none(), 0, Limit.NONE, null, new Amount.Times(new Amount.Scaled(new Amount.InZone("tributeZone", espectroP), 10, 0), 2)));
        add("alarido espectral", new EffectScript("alarido-espectral:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "(1) Otro aliado puede invocar por Ritual 1 criatura en tus tributos reduciendo el coste especial en 2.",
                List.of(new RitualFromZone("tributeZone", false, 2)))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E2-P09 Lureruri
        CardFilter violetSpell = CardFilter.type("utility").withColors("P").withClasses("brujería");
        add("lureruri", new EffectScript("lureruri:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Roba 1 carta. Luego: tributa 1 carta de tu mano. Si tributaste alguna utilidad Violeta o [brujería]: puedes jugar 1 utilidad Violeta o "
                        + "[brujería] de tus tributos reduciendo su coste en 1.",
                List.of(draw(1), tributeFromHand(1), new IfTributed(violetSpell, 2),
                        new MayDo("¿Juegas 1 utilidad Violeta o [brujería] de tus tributos reduciendo su coste en 1?", 1),
                        playFromZone("tributeZone", violetSpell, 1))));
        // E2-P10 Centauros De La Noche Iluminada
        add("centauros de la noche iluminada",
                new EffectScript("centauros:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "(1) Si algún aliado nivel 2 o mayor es destruido por el oponente: puedes invocar esta carta en tu línea frontal reduciendo su coste en 2.",
                        List.of(summonSelf("hand", Amount.fixed(2), true)))
                        .when(new AllyDestroyed(CardFilter.creatures().withMinLevel(2), true, false))
                        .fromZone("hand")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("centauros:final-del-turno", Moment.ON_EVENT, true, TODOS,
                        "Al final del turno: destruye esta carta. Luego: destruye 1 enemigo nivel 3 o menor.",
                        List.of(destroySelf(), destroyEnemy(1, enemy.withMaxLevel(3))))
                        .when(new AnyTurnEnds()));
        // E2-P11 Esqueleto De Las Mazmorras
        add("esqueleto de las mazmorras",
                new EffectScript("esqueleto-mazmorras:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION + " por efecto o combate",
                        "(1) Puedes tributar 1 carta de tu mano; al final del turno: puedes invocar 1 [muerto viviente] de tributos o descarte agotado en "
                                + "tu línea frontal reduciendo su coste en 2.",
                        List.of(tributeFromHand(1)), List.of(new AtEndOfTurn("esqueleto-mazmorras:final-del-turno")))
                        .when(any(new DestroyedByEffect(), new DestroyedInClash()))
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("esqueleto-mazmorras:final-del-turno", Moment.DELAYED, true, "Al final del turno",
                        "Puedes invocar 1 [muerto viviente] de tributos o descarte agotado en tu línea frontal reduciendo su coste en 2.",
                        List.of(new MayDo("¿Invocas 1 [muerto viviente] de tus tributos o descarte en tu línea frontal reduciendo su coste en 2?", 2),
                                summonFromZone("tributeZone,discardPile", muertoViviente, 2, true), tapSummoned())));
        // E2-P12 Augurio Catastrófico ("Si esta carta fue tributada desde tu descarte: en su lugar, envíala al fondo de tu mazo")
        CardFilter byBrujo = CardFilter.any().withClasses("brujo", "brujería");
        add("augurio catastrófico",
                new EffectScript("augurio-catastrofico:tributada", Moment.ON_EVENT, false, "En Tributos",
                        "Una vez por turno: si esta carta es tributada por efecto de un [brujo] o [brujería], puedes enviar esta carta a tu descarte y "
                                + "tributar 1 [brujería] de tu mano; gana 1 gema.",
                        List.of(sendSelfTo("discardPile"), tributeFromHandWhere(1, brujeria)), List.of(gainGems(1)))
                        .when(new ThisTributed(null, "discardPile", byBrujo))
                        .fromZone("tributeZone")
                        .limited(Limit.ONCE_PER_TURN),
                new EffectScript("augurio-catastrofico:tributada-del-descarte", Moment.ON_EVENT, false, "En Tributos",
                        "Una vez por turno: si esta carta fue tributada desde tu descarte por efecto de un [brujo] o [brujería], puedes enviarla al fondo "
                                + "de tu mazo y tributar 1 [brujería] de tu mano; gana 1 gema.",
                        List.of(sendSelfToDeckBottom(), tributeFromHandWhere(1, brujeria)), List.of(gainGems(1)))
                        .when(new ThisTributed("discardPile", null, byBrujo))
                        .fromZone("tributeZone")
                        .limited(Limit.ONCE_PER_TURN),
                new EffectScript("augurio-catastrofico:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                        "Tributa esta carta.",
                        List.of(sendSelfTo("tributeZone"))));
        // E2-P14 Amalgama Cadavérica
        add("amalgama cadavérica",
                new EffectScript("amalgama:al-final-del-ataque", Moment.ON_ATTACK_END, false, AL_FINAL,
                        "Puedes sacrificar esta carta para elegir 1 enemigo nivel 1 o menor; destrúyelo. Por cada 10 tributos Violeta o [espectro]: "
                                + "aumenta en 1 el nivel máximo elegible por este efecto.",
                        List.of(new IfPossible(), sacrificeSelf(),
                                chooseEnemyBy(1, new FilterBy(enemy, null, new Amount.Scaled(new Amount.InZone("tributeZone", espectroP), 10, 1)), false),
                                destroyChosen())),
                new EffectScript("amalgama:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION + " por efecto o enfrentamiento",
                        "Puedes tributar 1 carta de tu mano; invoca 1 criatura Violeta nivel 3 de tus tributos reduciendo su coste en 3.",
                        List.of(tributeFromHand(1)), List.of(summonFromZone("tributeZone", CardFilter.creatures().withColors("P").withLevel(3), 3)))
                        .when(any(new DestroyedByEffect(), new DestroyedInClash())));
        // E2-P15 Vigilante Cadavérico (Reactivar: keyword)
        add("vigilante cadavérico",
                new EffectScript("vigilante-cadaverico:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                        "Puedes tributar 1 carta de tu mano; al final del turno: puedes invocar 1 [muerto viviente] nivel 3 de tu descarte o tributos "
                                + "reduciendo su coste en 3.",
                        List.of(tributeFromHand(1)), List.of(new AtEndOfTurn("vigilante-cadaverico:final-del-turno"))),
                new EffectScript("vigilante-cadaverico:final-del-turno", Moment.DELAYED, true, "Al final del turno",
                        "Puedes invocar 1 [muerto viviente] nivel 3 de tu descarte o tributos reduciendo su coste en 3.",
                        List.of(new MayDo("¿Invocas 1 [muerto viviente] nivel 3 de tu descarte o tributos reduciendo su coste en 3?", 1),
                                summonFromZone("discardPile,tributeZone", muertoViviente.withLevel(3), 3))));
        // E2-P16 Conjurado Olvidado
        continuous("conjurado olvidado", new Continuous("Turno Aliado", "Esta carta gana fuerza 1 por cada [brujería] en tus tributos.", true,
                new Always(), 0, List.of(), new Amount.InZone("tributeZone", brujeria)));
        add("conjurado olvidado", new EffectScript("conjurado-olvidado:al-final-del-ataque", Moment.ON_ATTACK_END, true, AL_FINAL,
                "Sacrifica esta carta. Si lo haces: puedes jugar 1 [brujería] de tus tributos reduciendo su coste en 2.",
                List.of(sacrificeSelf(), new MayDo("¿Juegas 1 [brujería] de tus tributos reduciendo su coste en 2?", 1),
                        playFromZone("tributeZone", brujeria, 2))));
        // E2-P17 Zombi Berserker
        continuous("zombi berserker", new Continuous(TODOS, "Esta carta no puede declarar bloqueo.", false, new Always(), 0, List.of(EffectEngine.NO_BLOCK)));
        add("zombi berserker",
                new EffectScript("zombi-berserker:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                        "Una vez por turno: si algún aliado es invocado por efecto, esta carta puede declarar ataque.",
                        List.of(new DeclareAttack()))
                        .when(new AllySummonedByEffect(CardFilter.any()))
                        .limited(Limit.ONCE_PER_TURN),
                new EffectScript("zombi-berserker:al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                        "Una vez por turno: elige enemigos que sumen 4 en nivel; destrúyelos.",
                        List.of(new ChooseEnemiesLevelSum(4)), List.of(destroyChosen()))
                        .limited(Limit.ONCE_PER_TURN));
        // E2-P18 Liche
        costModifier("liche", new CostModifier("Absoluto", CostModifiers.Event.SUMMON, Set.of(), CostModifiers.Holder.SELF, false, null,
                Payment.none(), 0, Limit.ONCE_PER_TURN_BETWEEN_COPIES, null, new Amount.InZones("discardPile,tributeZone", brujeria)));
        add("liche", new EffectScript("liche:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Tributa hasta 4 [brujería] de tu descarte; juega 1 [brujería] de tus tributos reduciendo su coste en 1 por cada carta tributada.",
                List.of(tributeFromZoneUpTo("discardPile", 4, brujeria)), List.of(playFromZoneByCount("tributeZone", brujeria, 1))));
        // E2-P19 Imperio Desolado (structure)
        add("imperio desolado",
                new EffectScript("imperio-desolado:en-tributos", Moment.ON_EVENT, false, "En Tributos",
                        "(1) Si algún enemigo es destruido por tus utilidades: puedes jugar esta carta sin pagar su coste.",
                        List.of(playSelfReduced("tributeZone", FREE)))
                        .when(new EnemyDestroyedByYourCard(CardFilter.type("utility")))
                        .fromZone("tributeZone")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("imperio-desolado:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                        "Si juegas una utilidad [brujería] o de coste 3 o mayor: puedes agotar esta carta para ganar 1 gema.",
                        List.of(new IfPossible(), tapSelf(), gainGems(1)))
                        .when(new YouPlayed(CardFilter.type("utility").orAnyOf(CardFilter.any().withClasses("brujería"), CardFilter.any().withMinCost(3)))));
        // E2-P20 Llamada De Los Muertos (utility)
        costModifier("llamada de los muertos", new CostModifier("Absoluto", CostModifiers.Event.PLAY, Set.of(), CostModifiers.Holder.SELF, false,
                new CountOnField(CardFilter.creatures(), null, 1), Payment.none(), 1));
        add("llamada de los muertos", new EffectScript("llamada-muertos:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Tributa 2 cartas de tu mano; invoca hasta 3 criaturas Violeta o [muerto viviente] nivel 1 de tu descarte reduciendo en 1 sus costes.",
                List.of(tributeFromHand(2)),
                List.of(summonUpTo("discardPile", CardFilter.creatures().withColors("P").withClasses("muerto viviente").withLevel(1), 3, 1))));
        // E2-P21 Convocación De Muertos (utility)
        add("convocación de muertos",
                new EffectScript("convocacion-muertos:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "(1) Si invocas algún [brujo] nivel 3 o mayor: puedes jugar esta carta reduciendo su coste en 1.",
                        List.of(playSelfReduced("hand", 1)))
                        .when(new AllySummoned(CardFilter.creatures().withClasses("brujo").withMinLevel(3)))
                        .fromZone("hand")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("convocacion-muertos:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Invoca hasta 2 criaturas Violeta de tus tributos reduciendo sus costes en 1.",
                        List.of(summonUpTo("tributeZone", CardFilter.creatures().withColors("P"), 2, 1))));
        // E2-P22 Cosecha De Almas (utility)
        add("cosecha de almas",
                new EffectScript("cosecha-almas:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Elige 3 criaturas nivel 2 o menor en la línea frontal; sacrifícalas. Por cada aliado sacrificado de esta forma: gana 1 gema.",
                        List.of(chooseFrontLineCreatures(3, CardFilter.creatures().withMaxLevel(2))),
                        List.of(sacrificeChosen(), new GainGemsPerOwnSacrificed())),
                new EffectScript("cosecha-almas:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "(1) Si recibes daño directo por el oponente: puedes jugar esta carta reduciendo su coste en 1.",
                        List.of(playSelfReduced("hand", 1)))
                        .when(all(new YouReceivedDirectDamage(), not(new YourTurn())))
                        .fromZone("hand")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E2-P23 Maleficio Devastador (utility; from the lives: LIFE_PLAY_COST)
        add("maleficio devastador", new EffectScript("maleficio-devastador:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Elige 1 aliado [brujo] en tu línea frontal y 1 enemigo de nivel menor; destruye el enemigo elegido. Luego: el aliado elegido inflige "
                        + "daño directo.",
                List.of(new PickAttacker(CardFilter.creatures().withClasses("brujo"), true), chooseEnemyUpToSubjectLevel(true)),
                List.of(destroyChosen(), damageBySelfStrength())));
        // E2-P24 Quebrantamiento (utility; from the lives: LIFE_PLAY_COST). "reducen en 1 su contador de gemas" is each player's Marcador (0 to 5,
        // not below 0): designer ruling 2026-10-31, the card will be corrected
        add("quebrantamiento", new EffectScript("quebrantamiento:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Ambos jugadores eligen 1 de sus aliados de su línea frontal. Si lo hacen: sacrifican todas las otras criaturas en su línea frontal. "
                        + "Luego: ambos jugadores reducen en 1 su contador de gemas.",
                List.of(new EachPlayerKeepsOneInFrontLine(), new EachPlayerLowersMarker(1))));
        // E2-P25 Ritual Convocatorio (utility; from the lives: LIFE_PLAY_COST)
        add("ritual convocatorio", new EffectScript("ritual-convocatorio:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Coloca 1 [brujo] de tus tributos en tu línea frontal. Luego: sube a tu mano 1 aliado del mismo nivel. Si el aliado añadido a tu mano "
                        + "es nivel 3 o menor: gana 2 gemas.",
                List.of(placeFromZone("tributeZone", CardFilter.creatures().withClasses("brujo"), 1, true, false), new ReturnAllyOfSummonedLevel(),
                        new IfTributed(CardFilter.creatures().withMaxLevel(3), 1), gainGems(2))));
    }

    /** "Heredado de X: En Destrucción por efecto aliado: puedes tributar esta carta; gana 1 gema" (the dinosaurs of E2). */
    private static EffectScript dinoGem(String id, String name) {
        return new EffectScript(id + ":hereda-en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION + " por efecto aliado",
                "Heredado de " + name + ": puedes tributar esta carta; gana 1 gema.",
                List.of(new TributeNamedFromDiscard(name)), List.of(gainGems(1)))
                .when(new DestroyedByAllyEffect());
    }

    /** "En Invocación: puedes elegir 1 criatura en el campo con fuerza menor a esta carta; sacrifícala. Si era un aliado activo: gana 1 gema." */
    private static EffectScript sacrificeWeaker(String id) {
        return new EffectScript(id + ":en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes elegir 1 criatura en el campo con fuerza menor a esta carta; sacrifícala. Si era un aliado activo: gana 1 gema.",
                List.of(new ChooseWeakerThanSelf(false)), List.of(sacrificeChosen(), new IfChosenWasActiveAlly(1), gainGems(1)));
    }

    private CardEffects() {}

    /** Adds effects for a card name (tests use it for mechanics no printed card has yet). */
    static void register(String name, EffectScript... scripts) {
        BY_NAME.put(name, List.of(scripts));
    }

    private static void add(String name, EffectScript... scripts) {
        if (BY_NAME.putIfAbsent(name, List.of(scripts)) != null) throw new IllegalStateException("Carta registrada dos veces: " + name);
    }

    public static List<EffectScript> of(String cardName) {
        if (cardName == null) return List.of();
        return BY_NAME.getOrDefault(cardName.trim().toLowerCase(Locale.ROOT), List.of());
    }

    private static void inherit(String name, EffectScript... scripts) {
        if (INHERITED.putIfAbsent(name, List.of(scripts)) != null) throw new IllegalStateException("Carta registrada dos veces: " + name);
    }

    /**
     * A continuous effect: while its condition holds, the card has that strength and those abilities. It is
     * checked again after every event, so it stops applying as soon as the condition does.
     */
    /** {@code zone}: where the card has to be for it to apply ("field", or "lifeStack": "mientras esté en tus vidas"). */
    /** {@code levelBonus}: levels it gains while it applies ("gana 1 nivel por cada copia"), or null. */
    public record Continuous(String label, String text, boolean allyTurn, Condition condition, int strength, List<String> keywords,
                             Amount bonus, String zone, Amount levelBonus) {
        public Continuous(String label, String text, boolean allyTurn, Condition condition, int strength, List<String> keywords,
                          Amount bonus, String zone) {
            this(label, text, allyTurn, condition, strength, keywords, bonus, zone, null);
        }

        public Continuous(String label, String text, boolean allyTurn, Condition condition, int strength, List<String> keywords,
                          Amount bonus) {
            this(label, text, allyTurn, condition, strength, keywords, bonus, "field");
        }

        public Continuous(String label, String text, boolean allyTurn, Condition condition, int strength, List<String> keywords) {
            this(label, text, allyTurn, condition, strength, keywords, null, "field");
        }
    }

    private static void continuous(String name, Continuous... effects) {
        if (CONTINUOUS.putIfAbsent(name, List.of(effects)) != null) throw new IllegalStateException("Carta registrada dos veces: " + name);
    }

    /**
     * A continuous effect on other cards: while its condition holds, the cards that match get that strength and those
     * keywords — its allies, or with {@code enemies} the opponent's ("los enemigos de fuerza 3 o menos no pueden declarar ataque").
     */
    public record Aura(String label, String text, boolean allyTurn, Condition condition, CardFilter targets, int strength,
                       boolean enemies, List<String> keywords) {
        public Aura(String label, String text, boolean allyTurn, Condition condition, CardFilter targets, int strength) {
            this(label, text, allyTurn, condition, targets, strength, false, List.of());
        }

        public Aura {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }

    private static void aura(String name, Aura... auras) {
        if (AURAS.putIfAbsent(name, List.of(auras)) != null) throw new IllegalStateException("Carta registrada dos veces: " + name);
    }

    public static List<Aura> aurasOf(CardSlot card) {
        if (card.getCardName() == null) return List.of();
        return AURAS.getOrDefault(card.getCardName().trim().toLowerCase(Locale.ROOT), List.of());
    }

    /** The name of the card that gives an inherited effect (its id), or null for a card's own effect. */
    public static String inheritedFrom(String scriptId) {
        for (Map.Entry<String, List<EffectScript>> e : INHERITED.entrySet()) {
            if (e.getValue().stream().anyMatch(s -> s.id().equals(scriptId))) return e.getKey();
        }
        return null;
    }

    /** Loads the cards (their static registrations), for registries filled from here such as {@link CostModifiers}. */
    static void load() {
    }

    /** Everything programmed, card by card (by name in lower case), as data: see {@link EffectJson}. */
    public static Map<String, CardDefinition> definitions() {
        Set<String> names = new TreeSet<>();
        for (Map<String, ?> registry : List.of(BY_NAME, INHERITED, CONTINUOUS, AURAS, REPLACEMENTS, INHERITED_REPLACEMENTS)) {
            names.addAll(registry.keySet());
        }
        names.addAll(CostModifiers.names());
        Map<String, CardDefinition> all = new LinkedHashMap<>();
        for (String name : names) {
            all.put(name, new CardDefinition(name, BY_NAME.get(name), INHERITED.get(name), CONTINUOUS.get(name), AURAS.get(name),
                    CostModifiers.of(name), REPLACEMENTS.get(name), INHERITED_REPLACEMENTS.get(name)));
        }
        return all;
    }

    /**
     * Card effects the engine runs as a rule of its own rather than a script, by card name in lower case: "puede
     * considerarse como si estuviera en tu mano" ({@link #SPECIAL_FROM_TRIBUTES}) and "puede ser tratada como"
     * ({@link #ALIASES}). With {@link #definitions()}, everything programmed for a card.
     */
    public static Map<String, List<String>> ruleEffects() {
        Map<String, List<String>> all = new TreeMap<>();
        SPECIAL_FROM_TRIBUTES.forEach(n -> all.computeIfAbsent(n, k -> new ArrayList<>()).add("En Tributos: cuenta como en la mano para invocaciones especiales"));
        ALIASES.forEach((n, alias) -> all.computeIfAbsent(n, k -> new ArrayList<>()).add("puede ser tratada como \"" + alias + "\""));
        BLOCKER_SWAP.forEach(n -> all.computeIfAbsent(n, k -> new ArrayList<>()).add("cambia al bloqueador declarado por otro aliado"));
        ATTACK_BEFORE_ASCENDED.forEach(n -> all.computeIfAbsent(n, k -> new ArrayList<>()).add("declara ataque antes de ser ascendida"));
        GAIN_BEFORE_ASCENDED.forEach((n, k) -> all.computeIfAbsent(n, x -> new ArrayList<>()).add("gana " + k + " antes de ser ascendida"));
        LIFE_PLAY_COST.forEach((n, c) -> all.computeIfAbsent(n, x -> new ArrayList<>()).add("desde las vidas se juega pagando " + c + " gemas"));
        ALLY_REMOVAL_SUMMONS.forEach((n, s) -> all.computeIfAbsent(n, x -> new ArrayList<>()).add("se invoca especialmente en lugar de que un aliado sea removido"));
        ALLY_GUARDS.forEach((n, g) -> all.computeIfAbsent(n, x -> new ArrayList<>()).add("evita que sus aliados sean removidos por efecto oponente"));
        ALTERNATIVE_LEVELS.forEach((n, a) -> all.computeIfAbsent(n, x -> new ArrayList<>()).add("puede ser considerada nivel " + a.level()));
        CREATURE_FOR_EFFECTS.forEach((n, s) -> all.computeIfAbsent(n, x -> new ArrayList<>()).add("cuenta como criatura de fuerza " + s + " para efectos aliados"));
        SACRIFICE_TO_SUMMON.forEach((n, f) -> all.computeIfAbsent(n, x -> new ArrayList<>()).add("se invoca sacrificando un aliado"));
        FIELD_PIECES_DISCOUNTS.forEach((n, d) -> all.computeIfAbsent(n, x -> new ArrayList<>()).add("cuesta " + d.reduce() + " menos usando " + d.pieces()
                + " criaturas de tu campo"));
        DAMAGE_TRIBUTES.forEach(n -> all.computeIfAbsent(n, x -> new ArrayList<>()).add("se tributa con 2 [retador] en lugar de recibir daño"));
        DAMAGE_REPLACEMENTS.forEach((n, r) -> all.computeIfAbsent(n, k -> new ArrayList<>()).add("se invoca en lugar de recibir " + r.maxDamage() + " o menos de daño"));
        // The usage limit printed as its own line ("Solo puedes activar 1 efecto de «X» por turno"): Limit.ONE_EFFECT_OF_NAME_PER_TURN
        all.computeIfAbsent("fuego perforante", k -> new ArrayList<>()).add("solo 1 de sus efectos por turno");
        all.computeIfAbsent("mensaje de un compañero", k -> new ArrayList<>()).add("solo 1 de sus efectos por turno");
        return all;
    }

    /** Keyword abilities the engine applies (combat rules, {@link #KEYWORDS}, Asalto); the rest are played by hand. */
    public static final Set<String> ENGINE_KEYWORDS = Set.of("Provocar", "Enfrentar", "Celeridad", "Asalto", "Furia", "Reactivar",
            "Letalidad", "Imbloqueable", "Perforante", "Alcance", "Protección", "Agrupar");

    /**
     * What of a card's metadata the engine does not run yet (as the vault's status counts it: sync_vault_status.py),
     * or null when everything is programmed. {@code own}/{@code inherited}: its effects in the metadata.
     */
    public static String pending(String cardName, int own, int inherited, List<String> keywords) {
        String name = cardName == null ? "" : cardName.trim().toLowerCase(Locale.ROOT);
        int ownDone = BY_NAME.getOrDefault(name, List.of()).size() + CONTINUOUS.getOrDefault(name, List.of()).size()
                + AURAS.getOrDefault(name, List.of()).size() + CostModifiers.of(name).size()
                + REPLACEMENTS.getOrDefault(name, List.of()).size() + ruleEffects().getOrDefault(name, List.of()).size();
        int inheritedDone = INHERITED.getOrDefault(name, List.of()).size() + INHERITED_REPLACEMENTS.getOrDefault(name, List.of()).size();
        List<String> pending = new ArrayList<>();
        if (own > ownDone) pending.add((own - ownDone) + " de " + own + " efectos propios");
        if (inherited > inheritedDone) pending.add((inherited - inheritedDone) + " de " + inherited + " heredados");
        List<String> manual = keywords.stream().map(k -> k.replaceAll("\\s*\\d+$", "").trim())
                .filter(k -> !k.isEmpty() && !ENGINE_KEYWORDS.contains(k)).distinct().toList();
        if (!manual.isEmpty()) pending.add(String.join(", ", manual));
        return pending.isEmpty() ? null : String.join("; ", pending);
    }

    /** What a card gives the creature it is a Recurso of. */
    public static List<EffectScript> inheritedOf(String name) {
        return name == null ? List.of() : INHERITED.getOrDefault(name.trim().toLowerCase(Locale.ROOT), List.of());
    }

    /** A creature's strength while it is a life: its own and what its continuous effects of the lives give it. */
    static int lifeStrength(GameRoom room, String owner, CardSlot life) {
        int strength = life.getStrength() == null ? 0 : life.getStrength();
        for (Continuous c : continuousOf(life)) {
            if ("lifeStack".equals(c.zone()) && c.condition().test(room, null, life, owner)) strength += c.strength();
        }
        // Helena: "reduce … las de las vidas oponentes en N, hasta el final del turno"
        String reduced = EffectEngine.LIFE_REDUCED + life.getInstanceId() + "|";
        for (String k : room.getUsedThisTurn()) if (k.startsWith(reduced)) strength -= Integer.parseInt(k.substring(reduced.length()));
        return strength;
    }

    /** "En Tributos: esta carta puede considerarse como si estuviera en tu mano para invocaciones especiales." */
    private static final Set<String> SPECIAL_FROM_TRIBUTES = Set.of("falanx");

    public static boolean specialFromTributes(CardSlot card) {
        return card.getCardName() != null && SPECIAL_FROM_TRIBUTES.contains(card.getCardName().trim().toLowerCase(Locale.ROOT));
    }

    private static final java.util.regex.Pattern AGRUPAR = java.util.regex.Pattern.compile("agrupar\\s*(\\d+)");

    /** "Agrupar N": up to N allies, Letalidad 1 for each (designer ruling 2026-10-31). Agrupar 1 is the one in KEYWORDS. */
    static EffectScript agrupar(int allies) {
        if (allies == 1) return KEYWORDS.get("agrupar 1");
        return new EffectScript("kw:agrupar-" + allies, Moment.ON_ATTACK, false, "Agrupar " + allies,
                "Si esta carta declara ataque: puedes agotar hasta " + allies + " aliados para que esta carta, durante el ataque, gane sus fuerzas e "
                        + "inflija daño 1 vez extra por cada uno.", List.of(new GroupUp(allies)));
    }

    /** The card's own continuous effects. */
    public static List<Continuous> continuousOf(CardSlot card) {
        if (card.getCardName() == null) return List.of();
        return CONTINUOUS.getOrDefault(card.getCardName().trim().toLowerCase(Locale.ROOT), List.of());
    }

    /** The card's own effects, those its Recursos give it and those of the keywords the engine runs. */
    public static List<EffectScript> of(CardSlot card) {
        List<EffectScript> all = new ArrayList<>(of(card.getCardName()));
        for (CardSlot resource : card.getResources()) {
            if (resource.getCardName() != null) all.addAll(INHERITED.getOrDefault(resource.getCardName().trim().toLowerCase(Locale.ROOT), List.of()));
        }
        for (String keyword : EffectEngine.keywords(card)) {
            EffectScript script = KEYWORDS.get(keyword.toLowerCase(Locale.ROOT));
            // Agrupar N: up to N allies
            java.util.regex.Matcher groupUp = AGRUPAR.matcher(keyword.toLowerCase(Locale.ROOT));
            if (script == null && groupUp.matches()) script = agrupar(Integer.parseInt(groupUp.group(1)));
            if (script != null) all.add(script);
        }
        return all;
    }

    /** Looks an effect up by its id (card effects and keywords). */
    public static Optional<EffectScript> byId(String id) {
        return BY_NAME.values().stream().flatMap(List::stream).filter(s -> s.id().equals(id)).findFirst()
                .or(() -> INHERITED.values().stream().flatMap(List::stream).filter(s -> s.id().equals(id)).findFirst())
                .or(() -> KEYWORDS.values().stream().filter(s -> s.id().equals(id)).findFirst())
                .or(() -> id.startsWith("kw:agrupar-") ? Optional.of(agrupar(Integer.parseInt(id.substring("kw:agrupar-".length())))) : Optional.empty());
    }

    /** Cost to play a utility or structure, after its cost modifiers (they ask for nothing to be paid). */
    public static int playCost(GameRoom room, String userId, CardSlot card) {
        int cost = card.getCost() == null ? 0 : card.getCost();
        return Math.max(0, cost - CostModifiers.available(room, room.getPlayer(userId), CostModifiers.Event.PLAY, null, card, null));
    }

    private static void costModifier(String name, CostModifier... modifiers) {
        CostModifiers.register(name, modifiers);
    }

    // ------------------------------------------------------------------ Replacements ("en su lugar")

    /** What a replacement costs instead of the removal. */
    public enum ReplacementCost {
        /** "puedes tributar 2 materiales de esta carta". */
        TRIBUTE_TWO_MATERIALS,
        /** "puedes tributar 1 carta de tu mano" (the owner picks it). */
        TRIBUTE_ONE_FROM_HAND,
        /** "puedes tributar todos los recursos [filtro] de esta carta" (resourceFilter; any: all of them). */
        TRIBUTE_RESOURCES,
        /** "puedes colocar hasta 2 criaturas [filtro] de los materiales de esta carta en tu retaguardia" (resourceFilter: which ones). */
        MATERIALS_TO_BACK,
        /** "puede tributar los recursos de esta carta" before it is removed (a reaction: it is still removed; they do not go to the Descarte). */
        RESOURCES_TO_TRIBUTES_BEFORE,
        /** "en su lugar, puede invocar especialmente esta carta": a regular special summon of another card with the ally as summoner. */
        ALLY_SPECIAL_SUMMON,
        /** "en su lugar, puedes tributar 1 material de esta carta y sacrificar 1 [filtro] bajo tu control" (resourceFilter: what is sacrificed). */
        GUARD_MATERIAL_AND_SACRIFICE,
        /** "puedes tributar 1 de sus recursos para añadir otro de ellos a tu mano" before it is removed (a reaction: it is still removed). */
        RESOURCE_TRIBUTED_ANOTHER_TO_HAND,
        /**
         * "coloca el recurso de este efecto en tu retaguardia con todos los recursos [filtro] de esta carta como sus recursos" (mandatory: done by
         * the rules before the removal, Removals.reactionsBeforeRemoval; never offered as a choice).
         */
        RESOURCE_TO_BACK_WITH_OTHERS
    }

    /**
     * "cuando esta carta fuera a ser destruida / removida [por el oponente]: en su lugar, puedes …".
     *
     * @param destroyOnly only when it would be destroyed ("removida" also covers being sent to the hand)
     */
    public record Replacement(String id, String label, String text, boolean destroyOnly, boolean byOpponentOnly, Limit limit, ReplacementCost cost,
                              CardFilter resourceFilter) {
        public Replacement(String id, String label, String text, boolean destroyOnly, boolean byOpponentOnly, Limit limit, ReplacementCost cost) {
            this(id, label, text, destroyOnly, byOpponentOnly, limit, cost, CardFilter.any());
        }

        /** "en su lugar": the removal does not happen. A reaction (MATERIALS_TO_BACK, RESOURCES_TO_TRIBUTES_BEFORE) happens before it, and the removal goes on. */
        public boolean instead() {
            return cost != ReplacementCost.MATERIALS_TO_BACK && cost != ReplacementCost.RESOURCES_TO_TRIBUTES_BEFORE
                    && cost != ReplacementCost.RESOURCE_TRIBUTED_ANOTHER_TO_HAND && cost != ReplacementCost.RESOURCE_TO_BACK_WITH_OTHERS;
        }
    }

    private static final Map<String, List<Replacement>> REPLACEMENTS = Map.of(
            // E1-B14 Proto Vigilante H2 (Reactivar: keyword)
            "proto vigilante h2", List.of(new Replacement("proto-vigilante-h2:todos-los-turnos", TODOS,
                    "Cuando esta carta fuera a ser removida: en su lugar, puedes tributar 2 materiales de esta carta.", false, false, Limit.NONE,
                    ReplacementCost.TRIBUTE_TWO_MATERIALS)),
            // E1-R7 Caballero Leal
            "caballero leal", List.of(new Replacement("caballero-leal:todos-los-turnos", TODOS,
                    "(1) Cuando esta carta fuera a ser destruida: en su lugar puedes tributar 1 carta de tu mano.", true, false,
                    Limit.ONCE_PER_TURN_BETWEEN_COPIES, ReplacementCost.TRIBUTE_ONE_FROM_HAND)),
            // E1.1-B6 Gémini: a reaction, the destruction still happens
            "gémini", List.of(new Replacement("gemini:todos-los-turnos", TODOS,
                    "Cuando esta carta fuera a ser destruida: puedes colocar hasta 2 criaturas nivel 1 de los materiales de esta carta en tu retaguardia.",
                    true, false, Limit.NONE, ReplacementCost.MATERIALS_TO_BACK, CardFilter.creatures().withLevel(1))));

    /** Replacements a card gives as a Recurso (inherited). */
    private static final Map<String, List<Replacement>> INHERITED_REPLACEMENTS = Map.of(
            // E1-G19 Hydra Colosal
            "hydra colosal", List.of(new Replacement("hydra-colosal:hereda-todos-los-turnos", TODOS,
                    "Heredado de Hydra Colosal: si esta carta fuera a ser destruida, en su lugar, puedes tributar todos los recursos [Hydra] de esta carta.",
                    true, false, Limit.NONE, ReplacementCost.TRIBUTE_RESOURCES, CardFilter.any().withClasses("hydra"))),
            // E1-G10 Deriduragon: a reaction, the card is still removed (its Recursos go to the Tributos instead of the Descarte)
            "deriduragon", List.of(new Replacement("deriduragon:hereda-todos-los-turnos", TODOS,
                    "Heredado de Deriduragon: cuando esta carta fuera a ser removida por el oponente, puede tributar los recursos de esta carta.",
                    false, true, Limit.NONE, ReplacementCost.RESOURCES_TO_TRIBUTES_BEFORE)),
            // E2-G11 Deriduragón (the reprint, another metadata name)
            "deriduragón", List.of(new Replacement("deriduragon-e2:hereda-todos-los-turnos", TODOS,
                    "Heredado de Deriduragón: cuando esta carta fuera a ser removida por el oponente, puedes tributar los recursos de esta carta.",
                    false, true, Limit.NONE, ReplacementCost.RESOURCES_TO_TRIBUTES_BEFORE)),
            // E2-G08 Sarkhydrax: a reaction, the card is still removed
            "sarkhydrax", List.of(new Replacement("sarkhydrax:hereda-todos-los-turnos", TODOS,
                    "Heredado de Sarkhydrax: cuando esta carta fuera a ser removida, puedes tributar 1 de sus recursos para añadir otro de ellos a tu mano.",
                    false, false, Limit.NONE, ReplacementCost.RESOURCE_TRIBUTED_ANOTHER_TO_HAND)),
            // E2-G25 Fósil: mandatory, before the removal by the opponent (Removals.reactionsBeforeRemoval)
            "fósil", List.of(new Replacement("fosil:hereda-todos-los-turnos", TODOS,
                    "Heredado de Fósil: cuando esta carta fuera a ser removida por el oponente, coloca el recurso de este efecto en tu retaguardia con "
                            + "todos los recursos [dinosaurio] de esta carta como sus recursos.",
                    false, true, Limit.NONE, ReplacementCost.RESOURCE_TO_BACK_WITH_OTHERS, CardFilter.any().withClasses("dinosaurio"))),
            // E2-G20 Titanorax
            "titanorax", List.of(new Replacement("titanorax:hereda-todos-los-turnos", TODOS,
                    "Heredado de Titanorax: cuando esta carta fuera a ser removida, en su lugar, puedes tributar todos los recursos [hydra] de esta carta.",
                    false, false, Limit.NONE, ReplacementCost.TRIBUTE_RESOURCES, CardFilter.any().withClasses("hydra"))));

    /**
     * "cuando algún aliado [filtro] fuera a ser removido: en su lugar, puede invocar especialmente esta carta [reduciendo en N su coste
     * especial]", from the card's zone (the hand, the Tributos). Designer ruling 2026-10-31: it is a regular special summon, paying its cost and
     * with everything its Requerimiento asks; the ally that would be removed is its summoner (Removals.replacementOptions).
     */
    public record AllyRemovalSummon(String zone, CardFilter ally, int reduce, Replacement replacement) {}

    private static final Map<String, AllyRemovalSummon> ALLY_REMOVAL_SUMMONS = Map.of(
            // E1.2-B08 EXO-91
            "exo 91", new AllyRemovalSummon("tributeZone", CardFilter.creatures(), 1, new Replacement("exo-91:en-tributos", "En Tributos",
                    "(1) Cuando algún aliado fuera a ser removido: en su lugar, puede invocar especialmente esta carta reduciendo en 1 su coste especial.",
                    false, false, Limit.ONCE_PER_TURN_BETWEEN_COPIES, ReplacementCost.ALLY_SPECIAL_SUMMON)),
            // E1.2-B11 SARS-2
            "sars 2", new AllyRemovalSummon("hand", CardFilter.creatures().withClasses("autómata").withLevel(3), 0, new Replacement("sars-2:en-mano", "En Mano",
                    "(1) Cuando algún aliado [autómata] nivel 3 fuera a ser removido: en su lugar, puede invocar especialmente esta carta.",
                    false, false, Limit.ONCE_PER_TURN_BETWEEN_COPIES, ReplacementCost.ALLY_SPECIAL_SUMMON)));

    /**
     * "cuando uno o más aliados [filtro] fueran a ser removidos por efecto oponente: en su lugar, puedes …" held by a card on the field (itself
     * included). Designer ruling 2026-10-31: paid once, it saves every one of them that this effect was removing.
     */
    public record AllyGuard(CardFilter allies, Replacement replacement) {}

    private static final Map<String, AllyGuard> ALLY_GUARDS = Map.of(
            // E2-B08 Albert Collier el Tecnomago (Legendaria: manual)
            "albert collier el tecnomago", new AllyGuard(CardFilter.creatures().withClasses("cineriano"), new Replacement("albert-collier:todos-los-turnos",
                    TODOS, "Cuando uno o más aliados [cinerianos] fueran a ser removidos por efecto oponente: en su lugar, puedes tributar 1 material de "
                    + "esta carta y sacrificar 1 [artefacto] bajo tu control.", false, true, Limit.NONE, ReplacementCost.GUARD_MATERIAL_AND_SACRIFICE,
                    CardFilter.any().withClasses("artefacto"))));

    static AllyGuard allyGuard(CardSlot card) {
        return card.getCardName() == null ? null : ALLY_GUARDS.get(card.getCardName().trim().toLowerCase(Locale.ROOT));
    }

    /**
     * "En Mano: cuando fueras a recibir daño: en su lugar, puedes tributar esta carta más 2 cartas [retador] de tu mano, todas de distinto nivel"
     * (designer ruling 2026-10-31: damage of an attack or of an effect; one hit at a time, and Letalidad's next hits do not come after a
     * prevented one). CombatRules.offerDamageTribute.
     */
    private static final Set<String> DAMAGE_TRIBUTES = Set.of("estoque de los guerreros jurados");

    static boolean tributesInsteadOfDamage(CardSlot card) {
        return card.getCardName() != null && DAMAGE_TRIBUTES.contains(card.getCardName().trim().toLowerCase(Locale.ROOT));
    }

    /** "esta carta puede ser considerada nivel N por tus cartas [filtro]" (designer ruling 2026-10-31: for everything, special summons and effects). */
    public record AlternativeLevel(int level, CardFilter by) {}

    private static final Map<String, AlternativeLevel> ALTERNATIVE_LEVELS = Map.of(
            // E1.2-P01 Abraza Almas
            "abraza almas", new AlternativeLevel(2, CardFilter.any().withClasses("espectro")));

    /** The other level {@code card} can count as when {@code looker} reads it, or null. */
    static Integer alternativeLevel(CardSlot card, CardSlot looker) {
        if (card.getCardName() == null || looker == null) return null;
        AlternativeLevel alt = ALTERNATIVE_LEVELS.get(card.getCardName().trim().toLowerCase(Locale.ROOT));
        return alt != null && alt.by().matches(looker) ? alt.level() : null;
    }

    /** "si utilizas al menos N criaturas en tu campo: reduce el coste especial de esta carta en M" (Summons.fieldPiecesDiscount). */
    public record FieldPiecesDiscount(int pieces, int reduce) {}

    private static final Map<String, FieldPiecesDiscount> FIELD_PIECES_DISCOUNTS = Map.of("prototipo xtrm-1n3", new FieldPiecesDiscount(2, 3));

    static FieldPiecesDiscount fieldPiecesDiscount(CardSlot card) {
        return card.getCardName() == null ? null : FIELD_PIECES_DISCOUNTS.get(card.getCardName().trim().toLowerCase(Locale.ROOT));
    }

    /**
     * "esta carta puede ser considerada como una criatura de fuerza N por efectos aliados, pero no puede ser utilizada para invocaciones
     * especiales" (Fósil): the strength it has for the effects that look at it (CardFilter), never for a special summon.
     */
    private static final Map<String, Integer> CREATURE_FOR_EFFECTS = Map.of("fósil", 1);

    static Integer creatureStrengthForEffects(CardSlot card) {
        return card.getCardName() == null ? null : CREATURE_FOR_EFFECTS.get(card.getCardName().trim().toLowerCase(Locale.ROOT));
    }

    /**
     * "Cuando fueras a invocar esta carta: puedes sacrificar 1 aliado [filtro] activo que no sea una copia de esta carta para invocar esta
     * carta en tu línea frontal y reducir su coste en el coste del aliado sacrificado" (Carnirex; Summons.normalSummon).
     */
    private static final Map<String, CardFilter> SACRIFICE_TO_SUMMON = Map.of("carnirex", CardFilter.creatures().withClasses("dinosaurio").active());

    static CardFilter sacrificeToSummon(CardSlot card) {
        return card.getCardName() == null ? null : SACRIFICE_TO_SUMMON.get(card.getCardName().trim().toLowerCase(Locale.ROOT));
    }

    static AllyRemovalSummon allyRemovalSummon(CardSlot card) {
        return card.getCardName() == null ? null : ALLY_REMOVAL_SUMMONS.get(card.getCardName().trim().toLowerCase(Locale.ROOT));
    }

    /** The card's own replacements and those its Recursos give it. */
    public static List<Replacement> replacementsOf(CardSlot card) {
        List<Replacement> all = new ArrayList<>();
        if (card.getCardName() != null) all.addAll(REPLACEMENTS.getOrDefault(card.getCardName().trim().toLowerCase(Locale.ROOT), List.of()));
        for (CardSlot resource : card.getResources()) {
            if (resource.getCardName() != null)
                all.addAll(INHERITED_REPLACEMENTS.getOrDefault(resource.getCardName().trim().toLowerCase(Locale.ROOT), List.of()));
        }
        return all;
    }

    /** The Recurso of the card that gives it that replacement (inherited), or null when it is the card's own. */
    public static CardSlot replacementGiver(CardSlot card, Replacement replacement) {
        for (CardSlot resource : card.getResources()) {
            if (resource.getCardName() != null
                    && INHERITED_REPLACEMENTS.getOrDefault(resource.getCardName().trim().toLowerCase(Locale.ROOT), List.of()).contains(replacement)) return resource;
        }
        return null;
    }

    /** "En Mano: al entrar al paso de bloqueo: puedes jugar esta carta pagando su coste." */
    private static EffectScript playAtBlockStep(String id) {
        return new EffectScript(id + ":en-mano", Moment.ON_EVENT, false, "En Mano",
                "Al entrar al paso de bloqueo: puedes jugar esta carta pagando su coste.",
                List.of(playSelfFromZone("hand", false)))
                .when(new CombatStep(Combat.Step.BLOCK, Combat.Phase.ENTER))
                .fromZone("hand");
    }

    /** "cuando esta carta fuera a ser ascendida: puede declarar ataque" (Summons.handSpecial, then completeAscension). */
    private static final Set<String> ATTACK_BEFORE_ASCENDED = Set.of("principe charles di castirion");

    static boolean attacksBeforeAscended(CardSlot card) {
        return card.getCardName() != null && ATTACK_BEFORE_ASCENDED.contains(card.getCardName().trim().toLowerCase(Locale.ROOT));
    }

    /** "Para jugar esta carta desde tus vidas debes pagar N gemas" (an absolute effect: CombatRules.directDamage offers it only then). */
    private static final Map<String, Integer> LIFE_PLAY_COST = Map.of("conjuro de oscuridad infinita", 2, "maleficio devastador", 2,
            "quebrantamiento", 2, "ritual convocatorio", 2);

    static int lifePlayCost(CardSlot card) {
        return card.getCardName() == null ? 0 : LIFE_PLAY_COST.getOrDefault(card.getCardName().trim().toLowerCase(Locale.ROOT), 0);
    }

    /** "cuando esta carta fuera a ser ascendida: esta carta gana [habilidad] hasta el final del turno" (Turno Aliado; Summons.specialSummon). */
    private static final Map<String, String> GAIN_BEFORE_ASCENDED = Map.of("espada de los leones dorados", "Furia 2");

    static String gainBeforeAscended(CardSlot card) {
        return card.getCardName() == null ? null : GAIN_BEFORE_ASCENDED.get(card.getCardName().trim().toLowerCase(Locale.ROOT));
    }

    /** "cuando un aliado sea declarado bloqueador: en su lugar, puedes reactivarlo y elegir otro aliado …" (CombatRules.offerBlockerSwap). */
    private static final Set<String> BLOCKER_SWAP = Set.of("comandante veterano");

    static boolean swapsBlocker(CardSlot card) {
        return card.getCardName() != null && BLOCKER_SWAP.contains(card.getCardName().trim().toLowerCase(Locale.ROOT));
    }

    /** "cuando fueras a recibir N o menos de daño directo por ataque: en su lugar, puedes invocar esta carta … reduciendo su coste en M". */
    public record DamageReplacement(int maxDamage, int reduce) {}

    private static final Map<String, DamageReplacement> DAMAGE_REPLACEMENTS = Map.of("mercenario valeroso", new DamageReplacement(8, 1));

    static DamageReplacement damageReplacement(CardSlot card) {
        return card.getCardName() == null ? null : DAMAGE_REPLACEMENTS.get(card.getCardName().trim().toLowerCase(Locale.ROOT));
    }

    /** "Esta carta puede ser tratada como «X»": the name it also answers to. */
    private static final Map<String, String> ALIASES = Map.of("caballero derrotado", "calavera errante");

    /** The card is called {@code name}, or can be treated as such. */
    static boolean isNamed(CardSlot card, String name) {
        if (card.getCardName() == null || name == null) return false;
        String own = card.getCardName().trim().toLowerCase(Locale.ROOT);
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        return own.equals(wanted) || wanted.equals(ALIASES.get(own));
    }
}
