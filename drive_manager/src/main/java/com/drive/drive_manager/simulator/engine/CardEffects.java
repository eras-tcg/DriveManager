package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.CostModifiers.CostModifier;
import com.drive.drive_manager.simulator.engine.CostModifiers.Payment;
import com.drive.drive_manager.simulator.engine.EffectScript.Limit;
import com.drive.drive_manager.simulator.engine.EffectScript.Moment;
import com.drive.drive_manager.simulator.engine.EffectStep.ChooseEnemy;
import com.drive.drive_manager.simulator.engine.EffectStep.AttachLowerFromTributes;
import com.drive.drive_manager.simulator.engine.EffectStep.AttachSelfToSubject;
import com.drive.drive_manager.simulator.engine.EffectStep.DamageByEffect;
import com.drive.drive_manager.simulator.engine.EffectStep.DeckTopToLife;
import com.drive.drive_manager.simulator.engine.EffectStep.DeclareAttack;
import com.drive.drive_manager.simulator.engine.EffectStep.DrawLastCount;
import com.drive.drive_manager.simulator.engine.EffectStep.IfTributed;
import com.drive.drive_manager.simulator.engine.EffectStep.KeepOnField;
import com.drive.drive_manager.simulator.engine.EffectStep.MaterialToFront;
import com.drive.drive_manager.simulator.engine.EffectStep.MaterialToLife;
import com.drive.drive_manager.simulator.engine.EffectStep.ReduceChosen;
import com.drive.drive_manager.simulator.engine.EffectStep.ReduceTriggerToZero;
import com.drive.drive_manager.simulator.engine.EffectStep.ReduceTriggerFurther;
import com.drive.drive_manager.simulator.engine.EffectStep.SummonLikeSubject;
import com.drive.drive_manager.simulator.engine.EffectStep.AttachSubjectToSummoned;
import com.drive.drive_manager.simulator.engine.EffectStep.SummonSelfFromZone;
import com.drive.drive_manager.simulator.engine.EffectStep.TakePerCount;
import com.drive.drive_manager.simulator.engine.EffectStep.TargetAllEnemies;
import com.drive.drive_manager.simulator.engine.EffectStep.TributeFromHandUpTo;
import com.drive.drive_manager.simulator.engine.EffectStep.TributeMaterials;
import com.drive.drive_manager.simulator.engine.EffectStep.DestroyFaceUpLife;
import com.drive.drive_manager.simulator.engine.EffectStep.GainUntilEndOfAttack;
import com.drive.drive_manager.simulator.engine.EffectStep.PlaySelfFromZone;
import com.drive.drive_manager.simulator.engine.EffectStep.ToDeckTop;
import com.drive.drive_manager.simulator.engine.EffectStep.TributeResource;
import com.drive.drive_manager.simulator.engine.EffectStep.TributeTop;
import com.drive.drive_manager.simulator.engine.EffectStep.ActivateTributedSummonEffect;
import com.drive.drive_manager.simulator.engine.EffectStep.TributeAllyMaterialWhere;
import com.drive.drive_manager.simulator.engine.EffectStep.SummonFromZoneBy;
import com.drive.drive_manager.simulator.engine.EffectStep.FilterBy;
import com.drive.drive_manager.simulator.engine.EffectStep.PlaceFromHandUntilTurnEnd;
import com.drive.drive_manager.simulator.engine.EffectStep.TributeAlliesLevelSum;
import com.drive.drive_manager.simulator.engine.EffectStep.DiscardTributesForKeyword;
import com.drive.drive_manager.simulator.engine.EffectStep.ReduceRepeatedly;
import com.drive.drive_manager.simulator.engine.EffectStep.HandsToBottomDrawOpponentLives;
import com.drive.drive_manager.simulator.engine.EffectStep.OpponentMovesOneToFront;
import com.drive.drive_manager.simulator.engine.EffectStep.ExtraCombatHitThenEndTurn;
import com.drive.drive_manager.simulator.engine.EffectStep.DiscardLookedRestTop;
import com.drive.drive_manager.simulator.engine.EffectStep.TakeFromLookedLikeSubject;
import com.drive.drive_manager.simulator.engine.EffectStep.SendSelfFromFieldTo;
import com.drive.drive_manager.simulator.engine.EffectStep.SummonFreeLevelSum;
import com.drive.drive_manager.simulator.engine.EffectStep.GainStrength;
import com.drive.drive_manager.simulator.engine.EffectStep.IfDone;
import com.drive.drive_manager.simulator.engine.EffectStep.IfPossible;
import com.drive.drive_manager.simulator.engine.EffectStep.SpecialFromZone;
import com.drive.drive_manager.simulator.engine.EffectStep.SummonSelfFree;
import com.drive.drive_manager.simulator.engine.EffectStep.TakeFromZone;
import com.drive.drive_manager.simulator.engine.EffectStep.DestroyChosen;
import com.drive.drive_manager.simulator.engine.EffectStep.DestroyEnemy;
import com.drive.drive_manager.simulator.engine.EffectStep.Draw;
import com.drive.drive_manager.simulator.engine.EffectStep.GainGems;
import com.drive.drive_manager.simulator.engine.EffectStep.GainUntilEndOfTurn;
import com.drive.drive_manager.simulator.engine.EffectStep.LookTop;
import com.drive.drive_manager.simulator.engine.EffectStep.MayDo;
import com.drive.drive_manager.simulator.engine.EffectStep.MoveSelfToFront;
import com.drive.drive_manager.simulator.engine.EffectStep.PickAlly;
import com.drive.drive_manager.simulator.engine.EffectStep.PlaySelfFree;
import com.drive.drive_manager.simulator.engine.EffectStep.RitualFromZone;
import com.drive.drive_manager.simulator.engine.EffectStep.SacrificeAlly;
import com.drive.drive_manager.simulator.engine.EffectStep.SacrificeSelf;
import com.drive.drive_manager.simulator.engine.EffectStep.SendFromZoneToDeckBottom;
import com.drive.drive_manager.simulator.engine.EffectStep.SendSelfTo;
import com.drive.drive_manager.simulator.engine.EffectStep.SummonFromZone;
import com.drive.drive_manager.simulator.engine.EffectStep.TakeFromLookedRestToBottom;
import com.drive.drive_manager.simulator.engine.EffectStep.TapSelf;
import com.drive.drive_manager.simulator.engine.EffectStep.TributeFromHand;
import com.drive.drive_manager.simulator.engine.EffectStep.UntapAlly;
import com.drive.drive_manager.simulator.engine.EffectStep.UntapSelf;
import com.drive.drive_manager.simulator.engine.EffectStep.AddResourceFrom;
import com.drive.drive_manager.simulator.engine.EffectStep.BounceChosen;
import com.drive.drive_manager.simulator.engine.EffectStep.ChooseEnemyBy;
import com.drive.drive_manager.simulator.engine.EffectStep.ChooseWeakestEnemy;
import com.drive.drive_manager.simulator.engine.EffectStep.DamageBySelfStrength;
import com.drive.drive_manager.simulator.engine.EffectStep.GainStrengthUntil;
import com.drive.drive_manager.simulator.engine.EffectStep.IfNotDone;
import com.drive.drive_manager.simulator.engine.EffectStep.MaterialToBack;
import com.drive.drive_manager.simulator.engine.EffectStep.MaterialsToHand;
import com.drive.drive_manager.simulator.engine.EffectStep.NoUntapChosen;
import com.drive.drive_manager.simulator.engine.EffectStep.PickAllyWhere;
import com.drive.drive_manager.simulator.engine.EffectStep.PlaceSelfOnField;
import com.drive.drive_manager.simulator.engine.EffectStep.PlayFromZone;
import com.drive.drive_manager.simulator.engine.EffectStep.ReduceChosenToZero;
import com.drive.drive_manager.simulator.engine.EffectStep.ReturnSelfToHand;
import com.drive.drive_manager.simulator.engine.EffectStep.SacrificeAllyWhere;
import com.drive.drive_manager.simulator.engine.EffectStep.SelfToLife;
import com.drive.drive_manager.simulator.engine.EffectStep.SubjectFromTrigger;
import com.drive.drive_manager.simulator.engine.EffectStep.SummonSelf;
import com.drive.drive_manager.simulator.engine.EffectStep.SummonedCannotAttack;
import com.drive.drive_manager.simulator.engine.EffectStep.TapChosen;
import com.drive.drive_manager.simulator.engine.EffectStep.TributeAlly;
import com.drive.drive_manager.simulator.engine.EffectStep.TributeAllyMaterial;
import com.drive.drive_manager.simulator.engine.EffectStep.TributeFromLookedRestToBottom;
import com.drive.drive_manager.simulator.engine.EffectStep.UntapStructure;
import com.drive.drive_manager.simulator.engine.EffectStep.UntapSubject;

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

    /** Keyword abilities with an effect the engine runs ("Celeridad"). Others (Provocar, Letalidad…) are combat rules. */
    private static final Map<String, EffectScript> KEYWORDS = Map.of(
            "celeridad", new EffectScript("kw:celeridad", Moment.ON_SUMMON, false, "Celeridad",
                    "Puede subir a tu línea frontal.", List.of(new MoveSelfToFront()))
                    .when(not(new InFrontLine())),
            // Asalto replaces entering the Retaguardia; here it moves up right after, exhausted (the same position)
            "asalto", new EffectScript("kw:asalto", Moment.ON_SUMMON, false, "Asalto",
                    "En lugar de quedar en tu retaguardia, puedes colocarla agotada en tu línea frontal.",
                    List.of(new MoveSelfToFront(), new TapSelf()))
                    .when(not(new InFrontLine())));

    static {
        // ST3-1 Carnhaus (Iniciador)
        add("carnhaus",
                new EffectScript("carnhaus:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Mira las primeras 4 cartas de tu mazo: de ellas toma hasta 1 carta Violeta o Blanca. "
                                + "Envía el resto al fondo de tu mazo. Luego: tributa 1 carta de tu mano.",
                        List.of(new LookTop(4), new TakeFromLookedRestToBottom(1, CardFilter.colors("P", "W")), new TributeFromHand(1))),
                new EffectScript("carnhaus:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                        "Roba 1 carta. Luego: tributa 1 carta de tu mano.",
                        List.of(new Draw(1), new TributeFromHand(1))));
        // ST3-2 Damon
        add("damon", new EffectScript("damon:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                "Roba 3 cartas. Luego: tributa 2 cartas de tu mano.",
                List.of(new Draw(3), new TributeFromHand(2))));
        // ST3-3 Bajmut Pútrido (Imbloqueable: combat rule)
        add("bajmut pútrido", new EffectScript("bajmut-putrido:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Tributa 1 carta de tu mano.",
                List.of(new TributeFromHand(1))));
        // ST3-4 Buratán: only Provocar (combat rule)
        // ST3-5 Slicker: the cost can only be paid from the Tributos (sacrificed), not from the Descarte
        add("slicker", new EffectScript("slicker:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                "Puedes enviar esta carta a tu descarte para invocar 1 criatura Violeta de tus tributos reduciendo su coste en 1.",
                List.of(new IfPossible(), new SendSelfTo("discardPile"), new SummonFromZone("tributeZone", CardFilter.creatures().withColors("P"), 1))));
        // ST3-6 Virdris
        add("virdris", new EffectScript("virdris:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                "Roba 3 cartas. Luego: tributa 2 cartas de tu mano.",
                List.of(new Draw(3), new TributeFromHand(2))));
        // ST3-7 Krokler
        add("krokler", new EffectScript("krokler:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes elegir 1 enemigo nivel 1; destrúyelo.",
                List.of(new ChooseEnemy(1, CardFilter.any().withLevel(1))),
                List.of(new DestroyChosen())));
        // ST3-8 Zohx
        add("zohx", new EffectScript("zohx:al-final-del-ataque", Moment.ON_ATTACK_END, false, AL_FINAL,
                "(1) Si tienes 10 o más tributos [bestia]: esta carta puede invocar por Ritual 1 criatura de tus tributos reduciendo el coste especial en 1.",
                List.of(new RitualFromZone("tributeZone", true, 1)))
                .when(new CountInZone("tributeZone", CardFilter.any().withClasses("bestia"), 10))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // ST3-9 Zhaglizer
        add("zhaglizer", new EffectScript("zhaglizer:al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Elige 1 enemigo nivel 2 o menor; destrúyelo.",
                List.of(new ChooseEnemy(1, CardFilter.any().withMaxLevel(2))),
                List.of(new DestroyChosen())));
        // ST3-10 Hong (Letalidad 1: combat rule)
        add("hong", new EffectScript("hong:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Una vez por turno: si destruyes algún enemigo, puedes elegir 1 enemigo nivel 1; destrúyelo.",
                List.of(new ChooseEnemy(1, CardFilter.any().withLevel(1))),
                List.of(new DestroyChosen()))
                .when(new YouDestroyedEnemy(false))
                .limited(Limit.ONCE_PER_TURN));
        // ST3-11 Cazador De Bestias (Celeridad: keyword)
        add("cazador de bestias", new EffectScript("cazador-de-bestias:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Roba 3 cartas. Luego: tributa 2 cartas de tu mano.",
                List.of(new Draw(3), new TributeFromHand(2))));
        // ST3-12 Rhogler
        add("rhogler", new EffectScript("rhogler:al-final-del-ataque", Moment.ON_ATTACK_END, false, AL_FINAL,
                "(1) Puedes sacrificar esta carta; invoca 1 criatura [bestia] nivel 3 de tus tributos reduciendo su coste en 3.",
                List.of(new SacrificeSelf()),
                List.of(new SummonFromZone("tributeZone", CardFilter.creatures().withClasses("bestia").withLevel(3), 3)))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // ST3-13 Zhauz
        add("zhauz",
                new EffectScript("zhauz:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                        "Una vez por turno: puedes sacrificar 1 aliado para elegir 1 enemigo nivel 3 o menor; destrúyelo.",
                        List.of(new SacrificeAlly(), new ChooseEnemy(1, CardFilter.any().withMaxLevel(3))),
                        List.of(new DestroyChosen()))
                        .limited(Limit.ONCE_PER_TURN),
                new EffectScript("zhauz:turno-aliado", Moment.ON_EVENT, true, "Turno Aliado",
                        "Una vez por turno: si algún aliado es destruido, reactiva esta carta.",
                        List.of(new UntapSelf()))
                        .when(new AllyDestroyed(CardFilter.any(), false, false))
                        .onlyAllyTurn()
                        .limited(Limit.ONCE_PER_TURN));
        // ST3-14 Mawser (Provocar: combat rule)
        add("mawser", new EffectScript("mawser:todos-los-turnos", Moment.ON_EVENT, true, TODOS,
                "Una vez por turno: si esta carta destruye algún enemigo, elige 1 enemigo nivel 3 o menor; destrúyelo.",
                List.of(new ChooseEnemy(1, CardFilter.any().withMaxLevel(3))),
                List.of(new DestroyChosen()))
                .when(new ThisDestroyedEnemy(false))
                .limited(Limit.ONCE_PER_TURN));
        // ST3-15 Berkelus El Cazarrecompensas Del Bajomundo
        add("berkelus el cazarrecompensas del bajomundo", new EffectScript("berkelus:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                // "A para B": both on resolution, offered only with 10 [bestia] in the Tributos
                "Reactiva esta carta. Luego: puedes enviar 10 criaturas [bestia] de tus tributos al fondo de tu mazo para que "
                        + "esta carta declare ataque y gane Furia 1 hasta el final del turno.",
                List.of(
                        new UntapSelf(),
                        new MayDo("¿Envías 10 criaturas [bestia] de tus tributos al fondo de tu mazo para que Berkelus declare ataque y gane Furia 1?", 3),
                        new SendFromZoneToDeckBottom("tributeZone", 10, CardFilter.creatures().withClasses("bestia")),
                        new GainUntilEndOfTurn("Furia 1"),
                        new DeclareAttack())));
        // ST3-16 Cueva De Bestias (structure)
        add("cueva de bestias", new EffectScript("cueva-de-bestias:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Si algún aliado Violeta es destruido: puedes agotar esta carta; gana 1 gema y roba 1 carta. Si lo haces: tributa 1 carta de tu mano.",
                List.of(new TapSelf()),
                List.of(new GainGems(1), new Draw(1), new IfDone(1), new TributeFromHand(1)))
                .when(new AllyDestroyed(CardFilter.colors("P"), false, false)));
        // ST3-17 Alimento De Las Sombras (utility)
        add("alimento de las sombras",
                new EffectScript("alimento-de-las-sombras:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Un aliado invoca por Ritual 1 criatura de tus tributos reduciendo su coste especial en 1.",
                        List.of(new RitualFromZone("tributeZone", false, 1))),
                new EffectScript("alimento-de-las-sombras:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "(1) Si algún aliado es destruido por el oponente: puedes jugar esta carta sin pagar su coste.",
                        List.of(new PlaySelfFree()))
                        .when(new AllyDestroyed(CardFilter.any(), true, false))
                        .fromZone("hand")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // ST3-18 Caza Nocturna (utility)
        // "cuando fueras a jugar esta carta, si tienes menos de 0 gemas: reduce su coste en 1"
        costModifier("caza nocturna", new CostModifier("Reactivo", CostModifiers.Event.PLAY, Set.of(), CostModifiers.Holder.SELF, false,
                not(new GemsAtLeast(0)), Payment.none(), 1));
        add("caza nocturna", new EffectScript("caza-nocturna:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "(1) Elige 1 aliado para que gane Provocar 3 hasta el final del turno y declare ataque.",
                List.of(new PickAlly(true), new GainUntilEndOfTurn("Provocar 3"), new DeclareAttack()))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));

        // E1-P17 Arañador
        add("arañador",
                new EffectScript("aranador:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes invocar 1 criatura Violeta nivel 1 de tus tributos reduciendo su coste en 1.",
                        List.of(new SummonFromZone("tributeZone", CardFilter.creatures().withColors("P").withLevel(1), 1))),
                // A slow effect (no trigger condition): activated from the board in the open state
                new EffectScript("aranador:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                        "Una vez por turno: puede sacrificar 1 aliado para reactivar otro.",
                        List.of(new IfPossible(), new SacrificeAlly(true), new UntapAlly()))
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
                        List.of(new LookTop(4), new TakeFromLookedRestToBottom(2, CardFilter.colors("R", "W")), new TributeFromHand(1))),
                new EffectScript("caballero-umbrio:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                        "Puedes invocar 1 criatura Roja, [caballero] o [mercenario] de tu mano en tu línea frontal pagando su coste.",
                        List.of(new SummonFromZone("hand", CardFilter.creatures().withColors("R").withClasses("caballero", "mercenario"), 0, true))));
        // ST4-2 Peón De Línea Frontal: Enfrentar (combat rule)
        // ST4-3 Espada De Los Leones De Fuego
        add("espada de los leones de fuego", new EffectScript("espada-leones:en-mano", Moment.ON_EVENT, false, "En Mano",
                "(1) Si algún aliado es invocado por efecto y controlas algún [caballero] nivel 4 o mayor: puedes invocar esta carta sin pagar su coste.",
                List.of(new SummonSelfFree()))
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
                List.of(new Draw(2), new TributeFromHand(1))));
        // ST4-5 Caballero Férreo (the moment is missing in its metadata: taken as En Invocación)
        add("caballero férreo", new EffectScript("caballero-ferreo:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Reactiva 1 aliado nivel 2 o menor.",
                List.of(new UntapAlly(CardFilter.any().withMaxLevel(2)))));
        // ST4-6 Espada De Plata: its "En Destrucción" has no text in the metadata
        // ST4-7 Filo Vengativo: Celeridad (keyword)
        // ST4-8 Armadura De Plata
        add("armadura de plata", new EffectScript("armadura-de-plata:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "(1) Otro aliado puede invocar por Ascenso 1 criatura de tu mano reduciendo el coste especial en 1.",
                List.of(new SpecialFromZone("promotion", "hand", 1, true)))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // ST4-9 Caballería Cruzada
        add("caballería cruzada", new EffectScript("caballeria-cruzada:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Reactiva 1 aliado.",
                List.of(new UntapAlly())));
        // ST4-10 Luke El Filo Rojo (Celeridad: keyword)
        add("luke el filo rojo", new EffectScript("luke:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION + " por efecto",
                "Esta carta gana Enfrentar 3 hasta el final del turno.",
                List.of(new GainUntilEndOfTurn("Enfrentar 3")))
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
                List.of(new SummonFromZone("hand", CardFilter.creatures().withColors("R").withMaxLevel(2), 1)))
                .limited(Limit.ONCE_PER_TURN));
        // ST4-14 Caballero Real
        add("caballero real", new EffectScript("caballero-real:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Una vez por turno: puedes invocar 1 criatura Roja nivel 3 de tu mano reduciendo su coste en 2. "
                        + "Si lo haces: esta carta gana fuerza 3 y Perforante hasta el final del turno.",
                List.of(new SummonFromZone("hand", CardFilter.creatures().withColors("R").withLevel(3), 2),
                        new IfDone(2), new GainStrength(3), new GainUntilEndOfTurn("Perforante")))
                .limited(Limit.ONCE_PER_TURN));
        // ST4-15 Rey Franco
        add("rey franco",
                new EffectScript("rey-franco:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                        "Una vez por turno: si esta carta entra en combate, puedes reactivar esta carta.",
                        List.of(new UntapSelf()))
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
                List.of(new IfPossible(), new TapSelf(), new GainGems(1), new TakeFromZone("tributeZone", CardFilter.colors("R"), 1, false),
                        new IfDone(1), new TributeFromHand(1)))
                .when(new AllySummonedByEffect(CardFilter.colors("R"))));
        // ST4-17 Caballeros De La Primera Línea (utility)
        add("caballeros de la primera línea",
                new EffectScript("caballeros-primera-linea:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Puedes invocar 1 criatura Roja de tu mano reduciendo su coste en 1.",
                        List.of(new MayDo("¿Invocas 1 criatura Roja de tu mano reduciendo su coste en 1?", 1),
                                new SummonFromZone("hand", CardFilter.creatures().withColors("R"), 1))),
                new EffectScript("caballeros-primera-linea:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "(1) Si algún aliado es destruido: puedes jugar esta carta sin pagar su coste.",
                        List.of(new PlaySelfFree()))
                        .when(new AllyDestroyed(CardFilter.any(), false, false))
                        .fromZone("hand")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // ST4-18 Investidura (utility)
        add("investidura",
                new EffectScript("investidura:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "(1) Puedes tributar 1 carta de tu mano para tomar 1 carta de tus tributos. Luego: 1 aliado puede invocar por Ascenso "
                                + "1 criatura en tu mano reduciendo su coste especial en 1.",
                        List.of(new MayDo("¿Tributas 1 carta de tu mano para tomar 1 carta de tus tributos?", 2),
                                new TributeFromHand(1), new TakeFromZone("tributeZone", CardFilter.any(), 1, false),
                                new SpecialFromZone("promotion", "hand", 1, false)))
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("investidura:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "Si algún aliado es invocado por efecto: puedes jugar esta carta sin pagar su coste.",
                        List.of(new PlaySelfFree()))
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
                        List.of(new Draw(1), new TributeFromHand(1)))
                        .when(new TributedFromDeck(CardFilter.colors("G")))
                        .fromZone("tributeZone"),
                new EffectScript("dirumy:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Mira las primeras 4 cartas de tu mazo: de ellas toma hasta 1 carta Verde o Blanca. Envía el resto al fondo de tu mazo.",
                        List.of(new LookTop(4), new TakeFromLookedRestToBottom(1, CardFilter.colors("G", "W")))));
        inherit("dírumy", new EffectScript("dirumy:hereda-al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Heredado de Dírumy: puedes tributar \"Dírumy\" de los recursos de esta carta para robar 1 carta.",
                List.of(new IfPossible(), new TributeResource("Dírumy"), new Draw(1))));
        // ST2-2 Wyphound
        add("wyphound", new EffectScript("wyphound:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Coloca hasta 1 carta de tu mano al tope de tu mazo. Luego: tributa 2 cartas del tope de tu mazo.",
                List.of(new ToDeckTop("hand", 1, true), new TributeTop(2))));
        inherit("wyphound", new EffectScript("wyphound:hereda-al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Heredado de Wyphound: puedes colocar 1 carta de tus tributos al tope de tu mazo.",
                List.of(new ToDeckTop("tributeZone", 1, false))));
        // ST2-3 Flamagón
        add("flamagón", new EffectScript("flamagon:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Esta carta puede invocar por Evolución 1 [dragón] de tu mano reduciendo el coste especial en 1.",
                List.of(new SpecialFromZone("evolution", "hand", 1, "self", dragon))));
        inherit("flamagón", new EffectScript("flamagon:hereda-todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Heredado de Flamagón: si algún enemigo es destruido por tus efectos, puedes tomar 1 carta Verde de tus tributos.",
                List.of(new TakeFromZone("tributeZone", CardFilter.colors("G"), 1, false)))
                .when(new YouDestroyedEnemy(true)));
        // ST2-4 Duregón (Iniciador)
        // "Turno Aliado: cuando esta carta fuera a invocar por Evolución, si tienes 5 o más tributos [dragón]: reduce en 1 el coste especial"
        costModifier("duregón", new CostModifier("Turno Aliado", CostModifiers.Event.SPECIAL_SUMMON, Set.of("evolution"), CostModifiers.Holder.SUMMONER, true,
                new CountInZone("tributeZone", fiveDragons, 5), Payment.none(), 1));
        add("duregón", new EffectScript("duregon:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Tributa 3 cartas del tope de tu mazo. Luego: toma 1 carta Verde o [dragón] de tus tributos.",
                List.of(new TributeTop(3), new TakeFromZone("tributeZone", CardFilter.colors("G").withClasses("dragón"), 1, false))));
        inherit("duregón", new EffectScript("duregon:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Duregón: roba 1 carta. Luego: tributa 1 carta de tu mano.",
                List.of(new Draw(1), new TributeFromHand(1))));
        // ST2-5 Galveta
        add("galveta", new EffectScript("galveta:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Coloca hasta 1 carta de tu mano al tope de tu mazo. Luego: tributa 2 cartas del tope de tu mazo.",
                List.of(new ToDeckTop("hand", 1, true), new TributeTop(2))));
        inherit("galveta", new EffectScript("galveta:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Galveta: coloca hasta 1 carta de tu mano al tope de tu mazo. Luego: tributa 2 cartas del tope de tu mazo.",
                List.of(new ToDeckTop("hand", 1, true), new TributeTop(2))));
        // ST2-6 Wyfreik
        add("wyfreik", new EffectScript("wyfreik:al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Tributa 3 cartas del tope de tu mazo.",
                List.of(new TributeTop(3))));
        inherit("wyfreik", new EffectScript("wyfreik:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Wyfreik: coloca hasta 1 carta de tu mano al tope de tu mazo. Luego: tributa 2 cartas del tope de tu mazo.",
                List.of(new ToDeckTop("hand", 1, true), new TributeTop(2))));
        // ST2-7 Dragón Delta
        inherit("dragón delta", new EffectScript("dragon-delta:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Dragón Delta: elige 1 enemigo de fuerza 5 o menos; destrúyelo.",
                List.of(new ChooseEnemy(1, CardFilter.creatures().withMaxStrength(5))), List.of(new DestroyChosen())));
        // ST2-8 Flambreik
        // "Turno Aliado: cuando esta carta fuera a invocar por Evolución, puedes enviar 5 de tus tributos [dragón] al fondo de tu mazo
        // para reducir el coste especial en 1"
        costModifier("flambreik", new CostModifier("Turno Aliado", CostModifiers.Event.SPECIAL_SUMMON, Set.of("evolution"), CostModifiers.Holder.SUMMONER, true,
                null, Payment.tributesToDeckBottom(5, fiveDragons), 1));
        inherit("flambreik", new EffectScript("flambreik:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Flambreik: coloca hasta 1 carta de tu mano al tope de tu mazo. Luego: tributa 2 cartas del tope de tu mazo.",
                List.of(new ToDeckTop("hand", 1, true), new TributeTop(2))));
        // ST2-9 Fadrake
        add("fadrake", new EffectScript("fadrake:al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Una vez por turno: elige 1 enemigo de fuerza 5 o menos; destrúyelo.",
                List.of(new ChooseEnemy(1, CardFilter.creatures().withMaxStrength(5))), List.of(new DestroyChosen()))
                .limited(Limit.ONCE_PER_TURN));
        inherit("fadrake", new EffectScript("fadrake:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Fadrake: elige 1 enemigo de fuerza 5 o menos; destrúyelo.",
                List.of(new ChooseEnemy(1, CardFilter.creatures().withMaxStrength(5))), List.of(new DestroyChosen())));
        // ST2-10 Ddraig
        // The enemy is chosen on activation (up to 1, so it always activates); on resolution the 3 cards are always
        // tributed, then the chosen enemy (if any, and still valid) is destroyed
        add("ddraig", new EffectScript("ddraig:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Elige hasta 1 enemigo de fuerza 5 o menos; tributa 3 cartas del tope de tu mazo. Luego: destrúyelo.",
                List.of(new ChooseEnemy(1, CardFilter.creatures().withMaxStrength(5), true)),
                List.of(new TributeTop(3), new DestroyChosen())));
        inherit("ddraig", new EffectScript("ddraig:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Ddraig: elige 1 enemigo de fuerza 5 o menos; destrúyelo.",
                List.of(new ChooseEnemy(1, CardFilter.creatures().withMaxStrength(5))), List.of(new DestroyChosen())));
        // ST2-11 Wyverno De Pantano
        add("wyverno de pantano", new EffectScript("wyverno-pantano:tributada", Moment.ON_EVENT, false, "En Tributos",
                "(1) Si esta carta es tributada desde tu mazo: puedes tributar 1 carta de tu mano para tomar 1 carta Verde de tus tributos.",
                List.of(new IfPossible(), new TributeFromHand(1), new TakeFromZone("tributeZone", CardFilter.colors("G"), 1, false)))
                .when(new TributedFromDeck(null))
                .fromZone("tributeZone")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        inherit("wyverno de pantano", new EffectScript("wyverno-pantano:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Wyverno De Pantano: elige hasta 2 enemigos de fuerza 3 o menos; destrúyelos.",
                List.of(new ChooseEnemy(2, CardFilter.creatures().withMaxStrength(3), true)), List.of(new DestroyChosen())));
        // ST2-12 Dracoventus
        add("dracoventus", new EffectScript("dracoventus:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Puedes enviar 5 de tus tributos [dragón] al fondo de tu mazo para elegir 1 enemigo de fuerza 9 o menos; destrúyelo.",
                List.of(new SendFromZoneToDeckBottom("tributeZone", 5, fiveDragons), new ChooseEnemy(1, CardFilter.creatures().withMaxStrength(9))),
                List.of(new DestroyChosen())));
        // ST2-13 Saildreik
        add("saildreik", new EffectScript("saildreik:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Esta carta puede invocar por Evolución 1 [dragón] de tus tributos reduciendo su coste especial en 1.",
                List.of(new SpecialFromZone("evolution", "tributeZone", 1, "self", dragon))));
        inherit("saildreik", new EffectScript("saildreik:hereda-al-final-del-ataque", Moment.ON_ATTACK_END, true, AL_FINAL,
                "Heredado de Saildreik: elige 1 enemigo de fuerza 8 o menos; destrúyelo.",
                List.of(new ChooseEnemy(1, CardFilter.creatures().withMaxStrength(8))), List.of(new DestroyChosen())));
        // ST2-14 Dragón Carmesí
        EffectScript carmesiAttack = new EffectScript("dragon-carmesi:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Una vez por turno: puedes enviar 5 de tus tributos [dragón] al fondo de tu mazo; esta carta gana Letalidad 1 hasta el final del ataque.",
                List.of(new SendFromZoneToDeckBottom("tributeZone", 5, fiveDragons)), List.of(new GainUntilEndOfAttack("Letalidad 1")))
                .limited(Limit.ONCE_PER_TURN);
        add("dragón carmesí", carmesiAttack,
                new EffectScript("dragon-carmesi:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                        "Una vez por turno: si destruyes algún enemigo, puedes reactivar esta carta.",
                        List.of(new UntapSelf()))
                        .when(new YouDestroyedEnemy(false))
                        .limited(Limit.ONCE_PER_TURN));
        inherit("dragón carmesí", new EffectScript("dragon-carmesi:hereda-al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Heredado de Dragón Carmesí: puedes enviar 5 de tus tributos [dragón] al fondo de tu mazo; esta carta gana Letalidad 1 hasta el final del ataque.",
                List.of(new SendFromZoneToDeckBottom("tributeZone", 5, fiveDragons)), List.of(new GainUntilEndOfAttack("Letalidad 1"))));
        // ST2-15 Dragón Volcánico
        add("dragón volcánico",
                new EffectScript("dragon-volcanico:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                        "Una vez por turno: puedes destruir 1 carta bocarriba en las vidas oponentes.",
                        List.of(new DestroyFaceUpLife()))
                        .limited(Limit.ONCE_PER_TURN),
                new EffectScript("dragon-volcanico:al-final-del-ataque", Moment.ON_ATTACK_END, false, AL_FINAL,
                        "Una vez por turno: si algún enemigo fue destruido durante el ataque, puedes enviar 5 de tus tributos [dragón] al fondo de tu mazo "
                                + "para reactivar esta carta.",
                        List.of(new IfPossible(), new SendFromZoneToDeckBottom("tributeZone", 5, fiveDragons), new UntapSelf()))
                        .when(new EnemyDestroyedInAttack())
                        .limited(Limit.ONCE_PER_TURN));
        // ST2-16 Volcán De Dragones (structure)
        add("volcán de dragones", new EffectScript("volcan-de-dragones:turno-aliado", Moment.ON_EVENT, false, "Turno Aliado",
                "Si invocas especialmente 1 [dragón]: puedes agotar esta carta para ganar 1 gema y robar 1 carta. Si lo haces: tributa 1 carta de tu mano.",
                List.of(new IfPossible(), new TapSelf(), new GainGems(1), new Draw(1), new IfDone(1), new TributeFromHand(1)))
                .when(new YouSpecialSummoned(dragon))
                .onlyAllyTurn());
        // ST2-17 Huevo De Dragón (utility): it stays on the field as a permanent, with a slow effect from there
        add("huevo de dragón",
                new EffectScript("huevo-de-dragon:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Tributa 4 cartas del tope de tu mazo. Luego: toma 1 carta Verde o [dragón] de tus tributos. Esta carta permanece agotada en el campo.",
                        List.of(new TributeTop(4), new TakeFromZone("tributeZone", CardFilter.colors("G").withClasses("dragón"), 1, false),
                                new KeepOnField(true))),
                new EffectScript("huevo-de-dragon:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                        "Puedes enviar esta carta activa a tu descarte para invocar 1 [dragón] nivel 1 de tus tributos reduciendo su coste en 1.",
                        List.of(new IfPossible(), new SendSelfFromFieldTo("discardPile"),
                                new SummonFromZone("tributeZone", CardFilter.creatures().withClasses("dragón").withMaxLevel(1), 1, false)))
                        .when(new Active())
                        .onlyAllyTurn());
        // ST2-18 Llamarada (utility)
        add("llamarada",
                new EffectScript("llamarada:tributada", Moment.ON_EVENT, false, "En Tributos",
                        "(1) Si esta carta es tributada desde tu mazo por un aliado [dragón]: puedes jugar esta carta reduciendo su coste en el nivel de dicho aliado.",
                        List.of(new PlaySelfFromZone("tributeZone", true)))
                        .when(new TributedFromDeck(CardFilter.creatures().withClasses("dragón")))
                        .fromZone("tributeZone")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("llamarada:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Elige 1 enemigo de fuerza 11 o menos; destrúyelo.",
                        List.of(new ChooseEnemy(1, CardFilter.creatures().withMaxStrength(11))), List.of(new DestroyChosen())));
    }

    // ------------------------------------------------------------------ ST1 Entes
    static {
        CardFilter enemy = CardFilter.creatures();
        // ST1-1 Eisten
        add("eisten", new EffectScript("eisten:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Tributa 4 cartas del tope de tu mazo. Luego: toma hasta 1 carta Azul de tus tributos.",
                List.of(new TributeTop(4), new TakeFromZone("tributeZone", CardFilter.colors("B"), 1, true))));
        // ST1-2 Jhav
        add("jhav", new EffectScript("jhav:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Toma hasta 1 carta Azul de tus tributos. Luego: tributa 1 carta de tu mano. Si tributaste 1 carta [ente]: roba 1 carta.",
                List.of(new TakeFromZone("tributeZone", CardFilter.colors("B"), 1, true), new TributeFromHand(1),
                        new IfTributed(CardFilter.any().withClasses("ente"), 1), new Draw(1))));
        // ST1-3 Mihel: "su fuerza" is the reduced enemy's (it adds to the ally effect's reduction)
        add("mihel",
                new EffectScript("mihel:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Roba 2 cartas. Luego: tributa 2 cartas de tu mano.",
                        List.of(new Draw(2), new TributeFromHand(2))),
                new EffectScript("mihel:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                        "Si reduces la fuerza de algún enemigo: puedes añadir esta carta como material a un aliado para reducir su fuerza en 2 más.",
                        List.of(new IfPossible(), new PickAlly(), new AttachSelfToSubject(), new ReduceTriggerFurther(2)))
                        .when(new YouReducedStrength(null, true)));
        // ST1-4 Flureteus (Iniciador)
        add("flureteus",
                new EffectScript("flureteus:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Mira las primeras 5 cartas de tu mazo: de ellas toma hasta 2 cartas Azules o Blancas. Envía el resto al fondo de tu mazo. "
                                + "Luego: tributa 2 cartas de tu mano.",
                        List.of(new LookTop(5), new TakeFromLookedRestToBottom(2, CardFilter.colors("B", "W")), new TributeFromHand(2))),
                new EffectScript("flureteus:turno-aliado", Moment.ON_EVENT, false, "Turno Aliado",
                        "Si invocas 1 criatura por efecto: puedes invocar 1 criatura [ente] nivel 2 de tus tributos en tu línea frontal sin pagar su coste. "
                                + "Si lo haces: añade esta carta como material a un aliado.",
                        List.of(new SummonFromZone("tributeZone", CardFilter.creatures().withClasses("ente").withLevel(2), SummonFromZone.FREE, true),
                                new IfDone(2), new PickAlly(), new AttachSelfToSubject()))
                        .when(new AllySummonedByEffect(CardFilter.creatures()))
                        .onlyAllyTurn());
        // ST1-5 Flarix
        add("flarix",
                new EffectScript("flarix:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes tributar hasta 2 materiales de esta carta; toma 1 carta Azul de tus tributos por cada material tributado.",
                        List.of(new TributeMaterials(2, true)), List.of(new TakePerCount("tributeZone", CardFilter.colors("B")))),
                new EffectScript("flarix:en-tributos", Moment.ON_EVENT, false, "En Tributos",
                        "(1) Si reduces a 0 la fuerza de algún enemigo: puedes enviar esta carta al fondo de tu mazo para infringir 9 de daño directo.",
                        List.of(new IfPossible(), new SendSelfTo("deck"), new DamageByEffect(9)))
                        .when(new YouReducedStrength(0, false))
                        .fromZone("tributeZone")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // ST1-6 Zula
        add("zula", new EffectScript("zula:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Si tienes 4 o menos vidas: puedes tributar 1 material de esta carta; coloca hasta 1 material de esta carta al tope de tus vidas bocarriba.",
                List.of(new TributeMaterials(1, false)), List.of(new MaterialToLife()))
                .when(new LivesAtMost(false, 4)));
        // ST1-7 Fhalid: no effect
        // ST1-8 Arc xioma
        add("arc xioma",
                new EffectScript("arc-xioma:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes tributar 1 material de esta carta y elegir 1 enemigo; reduce su fuerza en 3 hasta el final del turno.",
                        List.of(new TributeMaterials(1, false), new ChooseEnemy(1, enemy)), List.of(new ReduceChosen(3, false))),
                new EffectScript("arc-xioma:en-tributos", Moment.ON_EVENT, false, "En Tributos",
                        "Si reduces a 2 o menos la fuerza de algún enemigo: puedes enviar esta carta al fondo de tu mazo para reducirla a 0.",
                        List.of(new IfPossible(), new SendSelfTo("deck"), new ReduceTriggerToZero()))
                        .when(new YouReducedStrength(2, false))
                        .fromZone("tributeZone"));
        // ST1-9 Bertalum
        add("bertalum", new EffectScript("bertalum:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes tributar 2 materiales de esta carta para elegir 1 enemigo; reduce en 4 su fuerza hasta el final del turno.",
                List.of(new TributeMaterials(2, false), new ChooseEnemy(1, enemy)), List.of(new ReduceChosen(4, false))));
        // ST1-10 Sahiel (Reactivar: keyword)
        add("sahiel", new EffectScript("sahiel:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Si tienes 5 o menos vidas: puedes tributar 2 materiales de esta carta; coloca la carta del tope de tu mazo al tope de tus vidas bocabajo.",
                List.of(new TributeMaterials(2, false)), List.of(new DeckTopToLife()))
                .when(new LivesAtMost(false, 5)));
        // ST1-11 Falis
        add("falis", new EffectScript("falis:en-tributos", Moment.ON_EVENT, false, "En Tributos",
                "(1) Si reduces a 0 la fuerza de algún enemigo: puedes invocar esta carta reduciendo su coste en 2.",
                List.of(new SummonSelfFromZone("tributeZone", 2)))
                .when(new YouReducedStrength(0, false))
                .fromZone("tributeZone")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // ST1-12 Mathaus (Letalidad 1: combat rule)
        add("mathaus", new EffectScript("mathaus:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                "Una vez por turno: puedes tributar 2 materiales de esta carta; elige 1 enemigo para reducir su fuerza en 3 hasta el final del turno oponente.",
                // "; elige 1 enemigo": after the ";", so the enemy is chosen on resolution
                List.of(new TributeMaterials(2, false)), List.of(new ChooseEnemy(1, enemy), new ReduceChosen(3, true)))
                .onlyAllyTurn()
                .limited(Limit.ONCE_PER_TURN));
        // ST1-13 Falanx: "En Tributos" (continuous) is SPECIAL_FROM_TRIBUTES. "si esta carta es agotada": exhausted to
        // declare an attack or by an effect; it triggers with the declaration (both happen at once)
        add("falanx", new EffectScript("falanx:agotada", Moment.ON_EVENT, false, TODOS,
                "Si esta carta es agotada: puedes tributar 2 materiales de esta carta y elegir 1 enemigo activo; reduce en 4 su fuerza "
                        + "hasta el final del próximo turno.",
                List.of(new TributeMaterials(2, false), new ChooseEnemy(1, CardFilter.creatures().active())),
                List.of(new ReduceChosen(4, true)))
                .when(new ThisExhausted()));
        // ST1-14 Galafendrax El Transmutador
        add("galafendrax el transmutador",
                new EffectScript("galafendrax:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Coloca hasta 1 [ente] nivel 4 de los materiales de esta carta en tu línea frontal. Luego: añádele todos los materiales "
                                + "de esta carta como materiales.",
                        List.of(new MaterialToFront(CardFilter.creatures().withClasses("ente"), 4))),
                new EffectScript("galafendrax:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                        "Una vez por turno: si reduces a 0 la fuerza de algún enemigo, puedes reactivar esta carta.",
                        List.of(new UntapSelf()))
                        .when(new YouReducedStrength(0, false))
                        .limited(Limit.ONCE_PER_TURN));
        // ST1-15 Hez (Reactivar: keyword)
        add("hez", new EffectScript("hez:inicio-del-turno", Moment.ON_EVENT, false, TODOS,
                "Al inicio del turno: puedes tributar 2 materiales de esta carta para elegir todos los enemigos en la línea frontal; "
                        + "reduce en 4 su fuerza hasta el final del turno.",
                // Before ";": the enemies are fixed on activation, with the cost
                List.of(new TributeMaterials(2, false), new TargetAllEnemies()), List.of(new ReduceChosen(4, false)))
                .when(new TurnStarts(false)));
        // ST1-16 Portal Al Mundo Espiritual (structure)
        add("portal al mundo espiritual", new EffectScript("portal-mundo-espiritual:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Si algún aliado [ente] es invocado por efecto: puedes agotar esta carta; añádele 1 criatura [ente] de menor nivel de tus tributos "
                        + "como material para ganar 1 gema.",
                List.of(new TapSelf()),
                // Before ";" its cost; then "añádele … para ganar 1 gema": the gem only if the material can be added
                List.of(new IfPossible(), new AttachLowerFromTributes(CardFilter.creatures().withClasses("ente")), new GainGems(1)))
                .when(new AllySummonedByEffect(CardFilter.any().withClasses("ente"))));
        // ST1-17 Composición Adimensional (utility; its "Turno Aliado" Materialización is not applied yet)
        add("composición adimensional",
                new EffectScript("composicion-adimensional:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Tributa hasta 3 cartas de tu mano. Luego: roba la misma cantidad y coloca esta carta agotada en tu retaguardia.",
                        List.of(new TributeFromHandUpTo(3), new DrawLastCount(), new KeepOnField(true))),
                // A slow effect from the field: the ally chosen gets this card as a material and is the summoner
                new EffectScript("composicion-adimensional:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                        "Puedes añadir esta carta activa como material a un aliado para que invoque por Materialización 1 criatura en tu mano o "
                                + "tributos reduciendo el coste especial en 1.",
                        List.of(new IfPossible(), new PickAlly(), new AttachSelfToSubject(),
                                new SpecialFromZone("materialization", "hand,tributeZone", 1, "subject", CardFilter.any())))
                        // "esta carta activa": not while it is exhausted (it stays exhausted after being played)
                        .when(new Active())
                        .onlyAllyTurn());
        // ST1-18 Paso Interdimensional (utility)
        add("paso interdimensional", new EffectScript("paso-interdimensional:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "(1) Elige 1 aliado: invoca 1 criatura [ente] del mismo nivel y distinto nombre de tus tributos en tu línea frontal sin pagar su coste. "
                        + "Si lo haces: añádele el aliado elegido con todos sus materiales como materiales del aliado invocado.",
                // "elige 1 aliado" is its cost (chosen on activation); the summon and the materials, its resolution
                List.of(new PickAlly()),
                List.of(new SummonLikeSubject("tributeZone", CardFilter.creatures().withClasses("ente")), new IfDone(1), new AttachSubjectToSummoned()))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
    }

    // ------------------------------------------------------------------ E1
    static {
        CardFilter enemy = CardFilter.creatures();
        // E1-P19 Zaranthrax
        add("zaranthrax", new EffectScript("zaranthrax:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "(1) Si tu oponente tiene 1 vida: esta carta puede declarar ataque.",
                List.of(new DeclareAttack()))
                .when(new LivesAtMost(true, 1))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        continuous("zaranthrax", new Continuous(TODOS, "Esta carta gana fuerza 5 durante el paso de daño directo.", false,
                new AttackingInDamageStep(),
                5, List.of()));
        // E1-R6 Sargento Legionario
        add("sargento legionario", new EffectScript("sargento-legionario:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Toma 1 carta Roja de tus tributos.",
                List.of(new TakeFromZone("tributeZone", CardFilter.colors("R"), 1, false))));
        continuous("sargento legionario", new Continuous(TODOS, "Esta carta gana fuerza 1 por cada aliado [legionario].", false,
                new Always(), 0, List.of(),
                new Amount.OnField(CardFilter.creatures().withClasses("legionario"))));
        // E1-G4 Wynsdey
        add("wynsdey", new EffectScript("wynsdey:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Tributa 3 cartas del tope de tu mazo. Luego: toma 1 carta Verde o Blanca de tus tributos.",
                List.of(new TributeTop(3), new TakeFromZone("tributeZone", CardFilter.colors("G", "W"), 1, false))));
        inherit("wynsdey", new EffectScript("wynsdey:hereda-todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Heredado de Wynsdey: si esta carta es agotada, puedes tributar el recurso de este efecto; roba 1 carta.",
                List.of(new TributeResource("Wynsdey")), List.of(new Draw(1)))
                .when(new ThisExhausted()));
        // E1-B15 Filirem (Asalto: keyword)
        add("filirem", new EffectScript("filirem:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Elige 1 enemigo con fuerza 6 o menos; reduce a 0 su fuerza.",
                List.of(new ChooseEnemy(1, enemy.withMaxStrength(6))), List.of(new ReduceChosenToZero())));
        // E1-B18 Iron (Enfrentar 4: combat rule)
        add("iron", new EffectScript("iron:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Puedes tributar 2 materiales de esta carta y elegir 1 enemigo; este pierde fuerza 3 hasta el final del turno.",
                List.of(new TributeMaterials(2, false), new ChooseEnemy(1, enemy)), List.of(new ReduceChosen(3, false))));
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
                List.of(new TributeFromHand(1), new ChooseWeakestEnemy(enemy.exhausted())), List.of(new NoUntapChosen())));
        inherit("driada", new EffectScript("driada:hereda-todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Heredado de Driada: si esta carta es agotada, puedes tributar el recurso con este efecto; invoca 1 [hada] de tu mano reduciendo su coste en 1.",
                List.of(new TributeResource("Driada")), List.of(new SummonFromZone("hand", CardFilter.creatures().withClasses("hada"), 1)))
                .when(new ThisExhausted()));
        // E1-G16 Dragón Rúnico
        add("dragón rúnico", new EffectScript("dragon-runico:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Mira las primeras 4 cartas de tu mazo: de ellas toma hasta 1 carta Verde. Envía el resto al fondo de tu mazo.",
                List.of(new LookTop(4), new TakeFromLookedRestToBottom(1, CardFilter.colors("G")))));
        inherit("dragón rúnico", new EffectScript("dragon-runico:hereda-en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                "Heredado de Dragón Rúnico: un aliado puede invocar por Evolución un \"Dragón Rúnico\" en tus tributos reduciendo el coste especial en 1.",
                List.of(new SpecialFromZone("evolution", "tributeZone", 1, "any", CardFilter.creatures().named("Dragón Rúnico")))));
        // E1-R25 Fervor De Guerra
        add("fervor de guerra", playAtBlockStep("fervor-de-guerra"), new EffectScript("fervor-de-guerra:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "(1) Elige 1 aliado Rojo nivel 3 o menor; reactívalo.",
                List.of(new PickAllyWhere(CardFilter.creatures().withColors("R").withMaxLevel(3))), List.of(new UntapSubject()))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-B7 Petrus
        add("petrus", new EffectScript("petrus:tributada-como-material", Moment.ON_EVENT, true, "En Tributos",
                "(1) Si esta carta es tributada como material por efecto de un aliado nivel 4 o mayor: elige 1 enemigo; reduce en 2 su fuerza "
                        + "hasta el final del turno.",
                List.of(new ChooseEnemy(1, enemy)), List.of(new ReduceChosen(2, false)))
                .when(new TributedAsMaterial(4))
                .fromZone("tributeZone")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-P3 Rata Ponsoñoza
        add("rata ponsoñoza", new EffectScript("rata-ponsonoza:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                "Elige 1 enemigo nivel 1; destrúyelo.",
                List.of(new ChooseEnemy(1, CardFilter.any().withLevel(1))), List.of(new DestroyChosen())));
        // E1-P14 Saco De Los Mil Rostros (Asalto: keyword)
        add("saco de los mil rostros", new EffectScript("saco-mil-rostros:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Elige hasta 1 enemigo nivel 2 y 1 enemigo nivel 1; destrúyelos.",
                List.of(new ChooseEnemy(1, CardFilter.any().withLevel(2), true), new ChooseEnemy(1, CardFilter.any().withLevel(1), true)),
                List.of(new DestroyChosen())));
        // E1-P16 Necroquinox
        add("necroquinox", new EffectScript("necroquinox:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                "Elige 1 enemigo de nivel menor o igual a la cantidad de copias de esta carta en tus tributos; destrúyelo.",
                List.of(new ChooseEnemyBy(1, new FilterBy(CardFilter.any(), null, new Amount.InZone("tributeZone", CardFilter.any().named("Necroquinox"))), false)),
                List.of(new DestroyChosen())));
        // E1-B3 Ephos
        add("ephos", new EffectScript("ephos:tributada-como-material", Moment.ON_EVENT, true, "En Tributos",
                "Si esta carta es tributada como material: elige 1 enemigo; reduce en 1 su fuerza hasta el final del turno.",
                List.of(new ChooseEnemy(1, enemy)), List.of(new ReduceChosen(1, false)))
                .when(new TributedAsMaterial(0))
                .fromZone("tributeZone"));
        // E1-B8 Delesel
        add("delesel", new EffectScript("delesel:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                "Puedes invocar 1 [ente] nivel 2 de tus tributos reduciendo su coste en 2.",
                List.of(new SummonFromZone("tributeZone", CardFilter.creatures().withClasses("ente").withLevel(2), 2))));
        // E1-B5 Androide A7
        add("androide a7", new EffectScript("androide-a7:tributada-como-material", Moment.ON_EVENT, false, "En Tributos",
                "(1) Si esta carta es tributada como material y no controlas copias de esta carta: puedes tributar 1 material de 1 aliado "
                        + "para invocar esta carta en tu línea frontal, reduciendo su coste en 1.",
                List.of(new IfPossible(), new TributeAllyMaterial(), new SummonSelf("tributeZone", Amount.fixed(1), true)))
                .when(all(new TributedAsMaterial(0), new NoCopies()))
                .fromZone("tributeZone")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-G10 Deriduragon (its inherited replacement: INHERITED_REPLACEMENTS)
        add("deriduragon", new EffectScript("deriduragon:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Tributa 3 cartas del tope de tu mazo. Luego: puedes añadir 1 criatura de fuerza 5 o menor de tu mano o tributos como recurso de esta carta.",
                List.of(new TributeTop(3), new MayDo("¿Añades 1 criatura de fuerza 5 o menor de tu mano o tributos como recurso de Deriduragon?", 1),
                        new AddResourceFrom("hand,tributeZone", CardFilter.creatures().withMaxStrength(5)))));
        // E1-G11 Lyna
        add("lyna", new EffectScript("lyna:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "(1) Puedes añadir 1 aliado nivel 1 como recurso de esta carta para ganar 1 gema. Luego: puedes agotar esta carta para reactivar "
                        + "1 de tus estructuras Verdes de coste 1 o menos.",
                List.of(new MayDo("¿Añades 1 aliado nivel 1 como recurso de Lyna para ganar 1 gema?", 2),
                        new AddResourceFrom("field", CardFilter.creatures().withLevel(1)), new GainGems(1),
                        new MayDo("¿Agotas a Lyna para reactivar 1 de tus estructuras Verdes de coste 1 o menos?", 2),
                        new TapSelf(), new UntapStructure(CardFilter.colors("G").withMaxCost(1))))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        inherit("lyna", new EffectScript("lyna:hereda-todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Heredado de Lyna: si esta carta es agotada, puedes tributar el recurso con este efecto; invoca 1 [hada] de tu mano reduciendo su coste en 1.",
                List.of(new TributeResource("Lyna")), List.of(new SummonFromZone("hand", CardFilter.creatures().withClasses("hada"), 1)))
                .when(new ThisExhausted()));
        // E1-G13 Hana
        add("hana", new EffectScript("hana:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes tributar 1 carta de tu mano; elige 1 enemigo de fuerza 8 o menos para agotarlo. Si ningún enemigo fue agotado por este efecto: "
                        + "reactiva esta carta.",
                List.of(new TributeFromHand(1)),
                List.of(new ChooseEnemy(1, enemy.withMaxStrength(8)), new TapChosen(), new IfNotDone(1), new UntapSelf())));
        for (String hada : List.of("hana", "celeste", "bianca")) {
            String title = Character.toUpperCase(hada.charAt(0)) + hada.substring(1);
            inherit(hada, new EffectScript(hada + ":hereda-todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                    "Heredado de " + title + ": si algún aliado de fuerza 4 o más es invocado por efecto, puedes tributar el recurso de este efecto; gana 1 gema.",
                    List.of(new TributeResource(title)), List.of(new GainGems(1)))
                    .when(new AllySummonedByEffect(CardFilter.creatures().withMinStrength(4))));
        }
        // E1-R7 Caballero Leal (its replacement when it would be destroyed: REPLACEMENTS)
        add("caballero leal", new EffectScript("caballero-leal:en-mano", Moment.ON_EVENT, false, "Turno Aliado / En Mano",
                "Si algún aliado es invocado por efecto y no controlas copias de esta carta: puedes invocar esta carta reduciendo su coste en 1.",
                List.of(new SummonSelf("hand", Amount.fixed(1), false)))
                .when(all(new AllySummonedByEffect(CardFilter.any()), new NoCopies()))
                .fromZone("hand")
                .onlyAllyTurn());
        // E1-P13 Convocador De Sombras
        add("convocador de sombras",
                new EffectScript("convocador-de-sombras:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                        "Una vez por turno: puedes sacrificar 1 aliado nivel 2 o mayor para ganar 1 gema.",
                        List.of(new IfPossible(), new SacrificeAllyWhere(CardFilter.creatures().withMinLevel(2)), new GainGems(1)))
                        .limited(Limit.ONCE_PER_TURN),
                new EffectScript("convocador-de-sombras:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                        "Puedes tributar 1 carta de tu mano para invocar 1 criatura nivel 2 de tus tributos reduciendo su coste en 1.",
                        List.of(new IfPossible(), new TributeFromHand(1), new SummonFromZone("tributeZone", CardFilter.creatures().withLevel(2), 1))));
        // E1-P18 Desollador
        add("desollador", new EffectScript("desollador:todos-los-turnos", Moment.ON_EVENT, true, TODOS,
                "Una vez por turno: si esta carta es agotada y tienes 20 o más tributos Violeta o [espectro], reactiva esta carta.",
                List.of(new UntapSelf()))
                .when(all(new ThisExhausted(), new CountInZone("tributeZone", CardFilter.colors("P").withClasses("espectro"), 20)))
                .limited(Limit.ONCE_PER_TURN));
        // E1-B11 VA 7 (Alcance: combat rule)
        add("va 7", new EffectScript("va-7:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes tributar 1 material de esta carta; juega 1 estructura Azul de tus tributos reduciendo su coste en 1.",
                List.of(new TributeMaterials(1, false)),
                List.of(new PlayFromZone("tributeZone", new CardFilter(List.of("B"), List.of(), "structure", null, null), 1))));
        // E1-B19 Sacerdotisa Del Portal Del Eclipse (Reactivar: keyword)
        add("sacerdotisa del portal del eclipse", new EffectScript("sacerdotisa-eclipse:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                "Puedes tributar 3 materiales de esta carta; invoca en tu línea frontal 1 criatura [chamán], [ente] o [ángel] de tus tributos "
                        + "reduciendo su coste en 3, pero no puede declarar ataque este turno.",
                List.of(new TributeMaterials(3, false)),
                List.of(new SummonFromZone("tributeZone", CardFilter.creatures().withClasses("chamán", "ente", "ángel"), 3, true), new SummonedCannotAttack()))
                .onlyAllyTurn());
        // E1-B22 Unidad De Emergencia L1
        add("unidad de emergencia l1", new EffectScript("unidad-emergencia-l1:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes subir los materiales de esta carta a tu mano. Si subiste alguna carta nivel 2 o menor a tu mano por este efecto: reactiva esta carta.",
                List.of(new MaterialsToHand(), new IfTributed(CardFilter.any().withMaxLevel(2), 1), new UntapSelf())));
        // E1-G5 Salamandrake
        add("salamandrake", new EffectScript("salamandrake:tributada", Moment.ON_EVENT, false, "En Tributos",
                "(1) Si esta carta es tributada desde el tope de tu mazo y no controlas copias de esta carta: puedes colocar esta carta en tu retaguardia.",
                List.of(new PlaceSelfOnField("tributeZone")))
                .when(all(new TributedFromDeck(null), new NoCopies()))
                .fromZone("tributeZone")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        for (String[] destroyer : List.of(new String[]{"salamandrake", "Salamandrake", "3"}, new String[]{"salamandra dracónica", "Salamandra Dracónica", "3"},
                new String[]{"dragaour", "Dragaour", "5"})) {
            int max = Integer.parseInt(destroyer[2]);
            inherit(destroyer[0], new EffectScript(destroyer[0] + ":hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                    "Heredado de " + destroyer[1] + ": elige 1 enemigo de fuerza " + max + " o menos; destrúyelo.",
                    List.of(new ChooseEnemy(1, enemy.withMaxStrength(max))), List.of(new DestroyChosen())));
        }
        // E1-P22 Fereteus (Celeridad: keyword)
        add("fereteus", new EffectScript("fereteus:en-mano", Moment.ACTIVATED, false, "Turno Aliado / En Mano",
                "(1) Si no controlas copias de esta carta: puedes invocar esta carta reduciendo su coste en X, donde X es la suma del nivel de todos "
                        + "tus aliados Violeta, [espectro] o [fauna].",
                List.of(new SummonSelf("hand", new Amount.LevelsOnField(CardFilter.colors("P").withClasses("espectro", "fauna")), false)))
                .when(new NoCopies())
                .fromZone("hand")
                .onlyAllyTurn()
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-R10 Martillo Frontal
        add("martillo frontal", new EffectScript("martillo-frontal:al-final-del-ataque", Moment.ON_ATTACK_END, false, AL_FINAL,
                "Una vez por turno: puedes invocar 1 [guerrero] o [retador] nivel 1 de tu mano reduciendo su coste en 2.",
                List.of(new SummonFromZone("hand", CardFilter.creatures().withClasses("guerrero", "retador").withLevel(1), 2)))
                .limited(Limit.ONCE_PER_TURN));
        // E1-B4 Cazador K3
        add("cazador k3", new EffectScript("cazador-k3:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Mira las primeras 5 cartas de tu mazo: de ellas toma hasta 3 cartas Azules o [autómata]. Envía el resto al fondo de tu mazo. "
                        + "Luego: tributa 2 cartas de tu mano.",
                List.of(new LookTop(5), new TakeFromLookedRestToBottom(3, CardFilter.colors("B").withClasses("autómata")), new TributeFromHand(2))));
        // E1-P11 Sombra Errante
        add("sombra errante", new EffectScript("sombra-errante:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                "(1) Puedes tributar 1 carta de tu mano para invocar 1 [espectro] nivel 1 de tus tributos sin pagar su coste.",
                List.of(new IfPossible(), new TributeFromHand(1),
                        new SummonFromZone("tributeZone", CardFilter.creatures().withClasses("espectro").withLevel(1), SummonFromZone.FREE)))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-B10 Phonia
        add("phonia",
                new EffectScript("phonia:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes tributar 1 material de esta carta; coloca 1 criatura Azul nivel 1 de los materiales de esta carta en tu retaguardia.",
                        List.of(new TributeMaterials(1, false)), List.of(new MaterialToBack(CardFilter.colors("B").withLevel(1)))),
                new EffectScript("phonia:en-tributos", Moment.ON_EVENT, false, "En Tributos",
                        "(1) Si reduces a 0 la fuerza de algún enemigo: puedes añadir esta carta como material a 1 aliado.",
                        List.of(new PickAlly(), new AttachSelfToSubject()))
                        .when(new YouReducedStrength(0, false))
                        .fromZone("tributeZone")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-B13 SP 7H
        add("sp 7h", new EffectScript("sp-7h:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Si tienes 0 o más gemas: puedes tributar 1 material de esta carta; esta carta declara ataque y gana Alcance hasta el final del turno.",
                List.of(new TributeMaterials(1, false)), List.of(new GainUntilEndOfTurn("Alcance"), new DeclareAttack()))
                .when(new GemsAtLeast(0)));
        // E1-B16 Vigilante De La Noche Cósmica
        continuous("vigilante de la noche cósmica", new Continuous(TODOS, "Esta carta gana fuerza 6 mientras esté en tus vidas.", false,
                new Always(), 6, List.of(), null, "lifeStack"));
        add("vigilante de la noche cósmica", new EffectScript("vigilante-noche-cosmica:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes tributar 3 materiales de esta carta; coloca esta carta al tope de tus vidas bocabajo.",
                List.of(new TributeMaterials(3, false)), List.of(new SelfToLife())));
        // E1-G8 Bestia Del Inframagma
        add("bestia del inframagma", new EffectScript("bestia-inframagma:al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Tributa 3 cartas del tope de tu mazo.",
                List.of(new TributeTop(3))));
        inherit("bestia del inframagma", new EffectScript("bestia-inframagma:hereda-en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                "Heredado de Bestia Del Inframagma: elige 1 enemigo de fuerza 5 o menor; destrúyelo.",
                List.of(new ChooseEnemy(1, enemy.withMaxStrength(5))), List.of(new DestroyChosen())));
        // E1-G25 Tributo Del Bosque: "si esta carta es removida de tus vidas" (a utility revealed as a life goes to the hand)
        add("tributo del bosque", new EffectScript("tributo-del-bosque:removida-de-vidas", Moment.ON_EVENT, true, "Vidas",
                        "Si esta carta es removida de tus vidas: elige 1 enemigo de fuerza 9 o menos; agótalo.",
                        List.of(new ChooseEnemy(1, enemy.withMaxStrength(9))), List.of(new TapChosen()))
                        .when(new ThisLeftLives())
                        .fromZone("hand"),
                new EffectScript("tributo-del-bosque:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Elige 1 enemigo agotado de fuerza 6 o menos; súbelo a la mano. Si algún enemigo fue removido por este efecto: puedes invocar "
                        + "1 criatura Verde de tu mano reduciendo su coste en 1.",
                List.of(new ChooseEnemy(1, enemy.withMaxStrength(6).exhausted())),
                List.of(new BounceChosen(), new IfDone(2), new MayDo("¿Invocas 1 criatura Verde de tu mano reduciendo su coste en 1?", 1),
                        new SummonFromZone("hand", CardFilter.creatures().withColors("G"), 1))));
        // E1-R14 Arquero Mítico Errante
        add("arquero mítico errante", new EffectScript("arquero-mitico:en-mano", Moment.ON_EVENT, false, "En Mano",
                "(1) Si un aliado declara ataque o bloqueo, y no controlas otras criaturas: puedes tributar esta carta; dicho aliado gana fuerza 3 "
                        + "hasta el final del combate.",
                List.of(new SendSelfTo("tributeZone")), List.of(new SubjectFromTrigger(), new GainStrengthUntil(3, "attack")))
                .when(new LoneAllyAttacksOrBlocks())
                .fromZone("hand")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-R24 Tácticas De Guerra (utility): it stays on the field, with a slow effect from there
        add("tácticas de guerra",
                new EffectScript("tacticas-de-guerra:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Tributa 2 cartas del tope de tu mazo. Luego: toma 1 carta Roja de tus tributos y coloca esta carta agotada en tu retaguardia.",
                        List.of(new TributeTop(2), new TakeFromZone("tributeZone", CardFilter.colors("R"), 1, false), new KeepOnField(true))),
                new EffectScript("tacticas-de-guerra:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                        "Puedes enviar esta carta activa a tu descarte para invocar 1 criatura Roja de tu mano reduciendo su coste en 1.",
                        List.of(new IfPossible(), new SendSelfFromFieldTo("discardPile"), new SummonFromZone("hand", CardFilter.creatures().withColors("R"), 1)))
                        .when(new Active())
                        .onlyAllyTurn());
        // E1-G14 Celeste (Asalto: keyword)
        add("celeste", new EffectScript("celeste:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Tributa 1 carta de tu mano y elige 1 enemigo agotado de fuerza 7 o menos; súbelo a la mano.",
                List.of(new TributeFromHand(1), new ChooseEnemy(1, enemy.withMaxStrength(7).exhausted())), List.of(new BounceChosen())));
        // E1-P15 Devorador De Bestias (Celeridad: keyword)
        add("devorador de bestias", new EffectScript("devorador-de-bestias:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes sacrificar 1 aliado Violeta nivel 3 que no sea una copia de esta carta para ganar 2 gemas.",
                List.of(new IfPossible(), new SacrificeAllyWhere(CardFilter.creatures().withColors("P").withLevel(3)
                        .notNamed("Devorador De Bestias")), new GainGems(2))));
        // E1-P25 Ritual De Sacrificio (utility)
        add("ritual de sacrificio",
                new EffectScript("ritual-de-sacrificio:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Sacrifica 1 aliado y elige 1 enemigo nivel 2 o menor; destrúyelo.",
                        List.of(new SacrificeAlly(), new ChooseEnemy(1, CardFilter.any().withMaxLevel(2))), List.of(new DestroyChosen())),
                new EffectScript("ritual-de-sacrificio:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "(1) Si algún aliado es destruido por el oponente, excepto como atacante: puedes enviar esta carta a tu descarte y elegir "
                                + "1 enemigo nivel 2 o menor; destrúyelo.",
                        List.of(new SendSelfTo("discardPile"), new ChooseEnemy(1, CardFilter.any().withMaxLevel(2))), List.of(new DestroyChosen()))
                        .when(new AllyDestroyed(CardFilter.any(), true, true))
                        .fromZone("hand")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-B6 Groll Vandálico
        add("groll vandálico", new EffectScript("groll-vandalico:en-mano", Moment.ON_EVENT, false, "En Mano",
                "(1) Si alguna carta es removida de tus vidas: puedes invocar esta carta en tu línea frontal. Si tienes 3 o menos vidas: reduce en 1 su coste.",
                List.of(new SummonSelf("hand", new Amount.IfThen(new LivesAtMost(false, 3), Amount.fixed(1), Amount.fixed(0)), true)))
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
                        List.of(new UntapSelf()))
                        .when(all(not(new YourTurn()), new ThisExhausted(), new FewerLivesThanOpponent()))
                        .limited(Limit.ONCE_PER_TURN),
                new EffectScript("guardian-imperial:turno-aliado", Moment.ON_EVENT, true, "Turno Aliado",
                        "Al inicio del turno: gana 1 gema.",
                        List.of(new GainGems(1)))
                        .when(new TurnStarts(true))
                        .onlyAllyTurn());
        // E1-G3 Salamandra Dracónica (Asalto: keyword)
        add("salamandra dracónica", new EffectScript("salamandra-draconica:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Elige 1 enemigo de fuerza 3 o menos; destrúyelo.",
                List.of(new ChooseEnemy(1, enemy.withMaxStrength(3))), List.of(new DestroyChosen())));
        // E1-G15 Dafleirk (Asalto: keyword)
        add("dafleirk", new EffectScript("dafleirk:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Elige 1 enemigo de fuerza 7 o menos; destrúyelo.",
                List.of(new ChooseEnemy(1, enemy.withMaxStrength(7))), List.of(new DestroyChosen())));
        // E1-P9 Ciervo Tejedor
        add("ciervo tejedor", new EffectScript("ciervo-tejedor:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Si controlas al menos 3 estructuras: puedes invocar 1 criatura Violeta [bestia] o [fauna] nivel 1 de tus tributos reduciendo en 1 su coste.",
                List.of(new SummonFromZone("tributeZone", CardFilter.creatures().withLevel(1).colorAndClass("P", "bestia", "fauna"), 1)))
                .when(new CountOnField(new CardFilter(List.of(), List.of(), "structure", null, null), 3, null)));
        // E1-P10 Zafrax (Provocar 2: combat rule)
        add("zafrax", new EffectScript("zafrax:todos-los-turnos", Moment.ON_EVENT, true, TODOS,
                "Una vez por turno: si esta carta destruye un enemigo por enfrentamiento, esta carta infringe daño directo.",
                List.of(new DamageBySelfStrength()))
                .when(new ThisDestroyedEnemy(true))
                .limited(Limit.ONCE_PER_TURN));
        // E1-G17 Ninfa Cantante De Dragones
        add("ninfa cantante de dragones", new EffectScript("ninfa-cantante:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Tributa las primeras 3 cartas de tu mazo. Luego: puedes invocar una criatura Verde de fuerza 7 o menos de tus tributos reduciendo en 2 su coste.",
                List.of(new TributeTop(3), new MayDo("¿Invocas 1 criatura Verde de fuerza 7 o menos de tus tributos reduciendo su coste en 2?", 1),
                        new SummonFromZone("tributeZone", CardFilter.creatures().withColors("G").withMaxStrength(7), 2))));
        inherit("ninfa cantante de dragones", new EffectScript("ninfa-cantante:hereda-todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Heredado de Ninfa Cantante De Dragones: si algún aliado de fuerza 4 o mayor es invocado por efecto, puedes tributar el recurso de este "
                        + "efecto; reactiva esta carta.",
                List.of(new TributeResource("Ninfa Cantante De Dragones")), List.of(new UntapSelf()))
                .when(new AllySummonedByEffect(CardFilter.creatures().withMinStrength(4))));
        // E1-R4 Legionario De Espadas Dobles
        aura("legionario de espadas dobles", new Aura("Turno Aliado", "Todos los aliados [armada] ganan fuerza 1.", true, null,
                CardFilter.creatures().withClasses("armada"), 1));
        // E1-R11 Alabarda De Primera Línea (Celeridad: keyword)
        add("alabarda de primera línea", new EffectScript("alabarda-primera-linea:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION + " por efecto",
                "Esta carta gana fuerza 2 y Enfrentar 3 hasta el final del turno.",
                List.of(new GainStrength(2), new GainUntilEndOfTurn("Enfrentar 3")))
                .when(new SummonedByEffect()));
        // E1-P5 Breo
        add("breo", new EffectScript("breo:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                "Elige 1 enemigo nivel 2 o menor; destrúyelo. Si tienes 20 o más tributos Violeta o [espectro]: en su lugar, puedes elegir 1 enemigo nivel 3.",
                List.of(new ChooseEnemyBy(1, new FilterBy(CardFilter.any(), null,
                        new Amount.IfThen(new CountInZone("tributeZone", CardFilter.colors("P").withClasses("espectro"), 20), Amount.fixed(3), Amount.fixed(2))), false)),
                List.of(new DestroyChosen())));
        // E1-P6 Caballero Derrotado: "puede ser tratada como Calavera Errante" (ALIASES)
        add("caballero derrotado",
                new EffectScript("caballero-derrotado:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "(1) Si algún aliado nivel 3 o mayor es destruido: puedes tributar 2 cartas de tu mano para invocar esta carta en tu línea frontal "
                                + "sin pagar su coste.",
                        List.of(new IfPossible(), new TributeFromHand(2), new SummonSelf("hand", Amount.fixed(SummonFromZone.FREE), true)))
                        .when(new AllyDestroyed(CardFilter.any().withMinLevel(3), false, false))
                        .fromZone("hand")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("caballero-derrotado:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                        "Roba 1 carta.",
                        List.of(new Draw(1))));
        // E1-B21 Unidad H3 R0
        add("unidad h3 r0",
                new EffectScript("unidad-h3-r0:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Si esta carta es el único aliado en juego: esta carta gana fuerza 3 hasta el final del turno oponente.",
                        List.of(new GainStrengthUntil(3, "nextTurn")))
                        .when(new CountOnField(CardFilter.creatures(), 1, 1)),
                new EffectScript("unidad-h3-r0:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                        "Una vez por turno: puedes tributar 2 materiales de esta carta; esta carta gana Letalidad 1 hasta el final del turno.",
                        List.of(new TributeMaterials(2, false)), List.of(new GainUntilEndOfTurn("Letalidad 1")))
                        .limited(Limit.ONCE_PER_TURN));
        // E1-R8 Explorador Temerario
        add("explorador temerario", new EffectScript("explorador-temerario:en-mano", Moment.ACTIVATED, false, "Turno Aliado / En Mano",
                "Puedes tributar 1 aliado [caballero] nivel 1; invoca esta carta en tu línea frontal reduciendo su coste en 1.",
                List.of(new TributeAlly(CardFilter.creatures().withClasses("caballero").withLevel(1))),
                List.of(new SummonSelf("hand", Amount.fixed(1), true)))
                .fromZone("hand")
                .onlyAllyTurn());
        // E1-R16 Caballeria Frontal
        add("caballeria frontal", new EffectScript("caballeria-frontal:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "1 aliado nivel 1 puede invocar por Ascenso 1 criatura en tu mano reduciendo el coste especial en 1.",
                List.of(new SpecialFromZone("promotion", "hand", 1, "level:1", CardFilter.any()))));
        // E1-P4 Albigio (Provocar 4: combat rule)
        add("albigio", new EffectScript("albigio:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION + " por efecto",
                "Celeridad: puede subir a tu línea frontal.",
                List.of(new MoveSelfToFront()))
                .when(all(new SummonedByEffect(), not(new InFrontLine()))));
        // E1-P8 Necroporx: the level goes up by 1 for each 2 copies of it in the Tributos
        add("necroporx", new EffectScript("necroporx:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION + " por efecto",
                "Puedes invocar 1 criatura Violeta nivel 1 de tus tributos sin pagar su coste. Aumenta en 1 el nivel por cada 2 copias de esta "
                        + "carta en tus tributos.",
                List.of(new SummonFromZoneBy("tributeZone", new FilterBy(CardFilter.creatures().withColors("P"),
                        new Amount.Scaled(new Amount.InZone("tributeZone", CardFilter.any().named("Necroporx")), 2, 1), null), SummonFromZone.FREE)))
                .when(new DestroyedByEffect()));
        // E1-G6 Janne
        inherit("janne", new EffectScript("janne:hereda-al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Heredado de Janne: puedes colocar 1 criatura Verde Iniciador de tu mano en tu retaguardia. Al final del turno súbela a tu mano.",
                List.of(new PlaceFromHandUntilTurnEnd(CardFilter.creatures().withColors("G").starter()))));
        add("janne", new EffectScript("janne:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Mira las primeras 4 cartas de tu mazo: de ellas toma 1 carta Verde o Blanca. Envía el resto al fondo de tu mazo. Luego: tributa 1 carta "
                        + "de tu mano.",
                List.of(new LookTop(4), new TakeFromLookedRestToBottom(1, CardFilter.colors("G", "W")), new TributeFromHand(1))));
        // E1-R3 Peón De Los Leones Dorados
        add("peón de los leones dorados", new EffectScript("peon-leones-dorados:al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Una vez por turno: si tienes menos vidas que tu oponente, reactiva esta carta.",
                List.of(new UntapSelf()))
                .when(new FewerLivesThanOpponent())
                .limited(Limit.ONCE_PER_TURN));
        // E1-G7 Dracoverdiris
        inherit("dracoverdiris", new EffectScript("dracoverdiris:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Dracoverdiris: una vez por turno, puedes colocar 1 carta de tu mano al tope de tu mazo. Luego: tributa 3 cartas del tope de tu mazo.",
                List.of(new ToDeckTop("hand", 1, true), new TributeTop(3)))
                .limited(Limit.ONCE_PER_TURN));
        // E1-G22 Uzume
        add("uzume",
                new EffectScript("uzume:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                        "Una vez por turno: si esta carta es agotada, puedes agotar el enemigo activo de menor fuerza.",
                        List.of(new ChooseWeakestEnemy(enemy.active()), new TapChosen()))
                        .when(new ThisExhausted())
                        .limited(Limit.ONCE_PER_TURN),
                new EffectScript("uzume:final-del-turno", Moment.ON_EVENT, false, "Turno Aliado",
                        "Al final del turno: puedes enviar 5 de tus tributos Verdes o [shogún] al fondo de tu mazo y elegir 1 enemigo; este no se puede "
                                + "reactivar durante el próximo turno oponente.",
                        List.of(new SendFromZoneToDeckBottom("tributeZone", 5, CardFilter.colors("G").withClasses("shogún")), new ChooseEnemy(1, enemy)),
                        List.of(new NoUntapChosen()))
                        .when(new TurnEnds())
                        .onlyAllyTurn());
        // E1-R9 Antorcha Legionaria
        add("antorcha legionaria", new EffectScript("antorcha-legionaria:final-del-turno", Moment.ON_EVENT, false, "Turno Aliado",
                "Al final del turno, si esta carta está en tu línea frontal y controlas 3 o más [legionarios]: puedes invocar 1 [legionario] de tu mano "
                        + "reduciendo su coste en 1.",
                List.of(new SummonFromZone("hand", CardFilter.creatures().withClasses("legionario"), 1)))
                .when(all(new TurnEnds(), new InFrontLine(), new CountOnField(CardFilter.creatures().withClasses("legionario"), 3, null)))
                .onlyAllyTurn());
        // E1-B14 Proto Vigilante H2, E1-R7 Caballero Leal, E1-G10 Deriduragon and E1-G19 Hydra Colosal (inherited): REPLACEMENTS
        // E1-B17 Roto G0
        add("roto g0",
                new EffectScript("roto-g0:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes tributar hasta 3 materiales de esta carta y elegir hasta 3 veces un enemigo; reduce en 2 la fuerza del enemigo elegido.",
                        List.of(new TributeMaterials(3, true)), List.of(new ReduceRepeatedly(-1, 2, "turn"))),
                new EffectScript("roto-g0:al-final-del-ataque", Moment.ON_ATTACK_END, false, AL_FINAL,
                        "Puedes subir esta carta a tu mano para ganar 2 gemas.",
                        List.of(new IfPossible(), new ReturnSelfToHand(), new GainGems(2))));
        // E1-R12 Espadón Legionario (Enfrentar 3: combat rule)
        add("espadón legionario", new EffectScript("espadon-legionario:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Si controlas 4 o más criaturas: gana 1 gema.",
                List.of(new GainGems(1)))
                .when(new CountOnField(CardFilter.creatures(), 4, null)));
        // E1-P7 Aullador
        add("aullador", new EffectScript("aullador:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                "Elige 1 aliado para que gane Provocar 3 hasta el final del turno.",
                List.of(new PickAlly(), new GainUntilEndOfTurn("Provocar 3"))));
        // E1-B09 SAR 2
        add("sar 2", new EffectScript("sar-2:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Puedes tributar 1 material de esta carta. Si lo haces, hasta 2 veces: puedes elegir 1 enemigo; reduce en 1 su fuerza hasta el final del ataque.",
                List.of(new TributeMaterials(1, false)), List.of(new ReduceRepeatedly(2, 1, "attack"))));
        // E1-B25 Sobrecarga (utility)
        add("sobrecarga", playAtBlockStep("sobrecarga"),
                new EffectScript("sobrecarga:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "(1) Tributa 1 material nivel 3 o menor de un [autómata] o [mecha]; añádele esta carta a dicho aliado como material para que active "
                                + "1 efecto En Invocación del material tributado.",
                        List.of(new TributeAllyMaterialWhere(CardFilter.creatures().withClasses("autómata", "automata", "mecha"),
                                CardFilter.any().withMaxLevel(3))),
                        List.of(new AttachSelfToSubject(), new ActivateTributedSummonEffect()))
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-G19 Hydra Colosal (its inherited replacement is in REPLACEMENTS)
        add("hydra colosal",
                new EffectScript("hydra-colosal:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Tributa las primeras 3 cartas de tu mazo. Luego: elige 1 enemigo de fuerza 7 o menos; destrúyelo.",
                        List.of(new TributeTop(3), new ChooseEnemy(1, enemy.withMaxStrength(7)), new DestroyChosen())),
                new EffectScript("hydra-colosal:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                        "Esta carta puede invocar por Evolución una criatura en tus tributos reduciendo su coste especial en 1.",
                        List.of(new SpecialFromZone("evolution", "tributeZone", 1, "self", CardFilter.creatures()))));
        // E1-R19 Dante Di Castirion (Letalidad 1: combat rule)
        add("dante di castirion", new EffectScript("dante-castirion:en-mano", Moment.ACTIVATED, false, "Turno Aliado / En Mano",
                "(1) Puedes tributar 3 o más aliados Rojos que sumen 5 o más en nivel para invocar esta carta en tu línea frontal reduciendo su coste en 3.",
                List.of(new TributeAlliesLevelSum(3, 5, CardFilter.creatures().withColors("R"))),
                List.of(new SummonSelf("hand", Amount.fixed(3), true)))
                .fromZone("hand")
                .onlyAllyTurn()
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // E1-R21 Imperial De La Llama Eterna (Perforante: combat rule)
        add("imperial de la llama eterna", new EffectScript("imperial-llama-eterna:al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Tantas veces como quieras: descarta 10 tributos Rojos o [retador]; esta carta gana Letalidad 1 hasta el final del ataque.",
                List.of(new DiscardTributesForKeyword(10, CardFilter.colors("R").withClasses("retador"), "Letalidad"))));
        // E1-W01…W05: White utilities with a Definitive effect (only 1 per turn, whichever card)
        add("nexo del inframundo material", new EffectScript("nexo-inframundo:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Efecto Definitivo. Elige 1 aliado; mira las primeras 4 cartas de tu mazo: de ellas toma hasta 1 carta que comparta tipo con el "
                        + "aliado elegido. Envía el resto al fondo de tu mazo en orden aleatorio.",
                List.of(new PickAlly()), List.of(new LookTop(4), new TakeFromLookedLikeSubject(1)))
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
                List.of(new LookTop(7), new DiscardLookedRestTop(5), new Draw(1)))
                .limited(Limit.DEFINITIVE));
        add("prisma de la grieta dimensional", new EffectScript("prisma-grieta:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Efecto Definitivo. Ambos jugadores envían las cartas de su mano al fondo de su mazo en orden aleatorio. Luego: ambos roban "
                        + "cartas igual a la cantidad de vidas que tiene su oponente.",
                List.of(new HandsToBottomDrawOpponentLives()))
                .limited(Limit.DEFINITIVE));
        // E1 Fuego Perforante (utility; its "En Tributos" is not applied yet)
        add("fuego perforante", new EffectScript("fuego-perforante:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Tributa esta carta; roba 1 carta.",
                List.of(new SendSelfFromFieldTo("tributeZone")), List.of(new Draw(1)))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
    }

    private CardEffects() {}

    /** Adds effects for a card name (tests use it for mechanics no printed card has yet). */
    static void register(String name, EffectScript... scripts) {
        add(name, scripts);
    }

    private static void add(String name, EffectScript... scripts) {
        BY_NAME.put(name, List.of(scripts));
    }

    public static List<EffectScript> of(String cardName) {
        if (cardName == null) return List.of();
        return BY_NAME.getOrDefault(cardName.trim().toLowerCase(Locale.ROOT), List.of());
    }

    private static void inherit(String name, EffectScript... scripts) {
        INHERITED.put(name, List.of(scripts));
    }

    /**
     * A continuous effect: while its condition holds, the card has that strength and those abilities. It is
     * checked again after every event, so it stops applying as soon as the condition does.
     */
    /** {@code zone}: where the card has to be for it to apply ("field", or "lifeStack": "mientras esté en tus vidas"). */
    public record Continuous(String label, String text, boolean allyTurn, Condition condition, int strength, List<String> keywords,
                             Amount bonus, String zone) {
        public Continuous(String label, String text, boolean allyTurn, Condition condition, int strength, List<String> keywords,
                          Amount bonus) {
            this(label, text, allyTurn, condition, strength, keywords, bonus, "field");
        }

        public Continuous(String label, String text, boolean allyTurn, Condition condition, int strength, List<String> keywords) {
            this(label, text, allyTurn, condition, strength, keywords, null, "field");
        }
    }

    private static void continuous(String name, Continuous... effects) {
        CONTINUOUS.put(name, List.of(effects));
    }

    /** A continuous effect on other cards: while its condition holds, the allies that match get that strength. */
    public record Aura(String label, String text, boolean allyTurn, Condition condition, CardFilter targets, int strength) {}

    private static void aura(String name, Aura... auras) {
        AURAS.put(name, List.of(auras));
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
        return strength;
    }

    /** "En Tributos: esta carta puede considerarse como si estuviera en tu mano para invocaciones especiales." */
    private static final Set<String> SPECIAL_FROM_TRIBUTES = Set.of("falanx");

    public static boolean specialFromTributes(CardSlot card) {
        return card.getCardName() != null && SPECIAL_FROM_TRIBUTES.contains(card.getCardName().trim().toLowerCase(Locale.ROOT));
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
            if (script != null) all.add(script);
        }
        return all;
    }

    /** Looks an effect up by its id (card effects and keywords). */
    public static Optional<EffectScript> byId(String id) {
        return BY_NAME.values().stream().flatMap(List::stream).filter(s -> s.id().equals(id)).findFirst()
                .or(() -> INHERITED.values().stream().flatMap(List::stream).filter(s -> s.id().equals(id)).findFirst())
                .or(() -> KEYWORDS.values().stream().filter(s -> s.id().equals(id)).findFirst());
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
        TRIBUTE_RESOURCES
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
    }

    private static final Map<String, List<Replacement>> REPLACEMENTS = Map.of(
            // E1-B14 Proto Vigilante H2 (Reactivar: keyword)
            "proto vigilante h2", List.of(new Replacement("proto-vigilante-h2:todos-los-turnos", TODOS,
                    "Cuando esta carta fuera a ser removida: en su lugar, puedes tributar 2 materiales de esta carta.", false, false, Limit.NONE,
                    ReplacementCost.TRIBUTE_TWO_MATERIALS)),
            // E1-R7 Caballero Leal
            "caballero leal", List.of(new Replacement("caballero-leal:todos-los-turnos", TODOS,
                    "(1) Cuando esta carta fuera a ser destruida: en su lugar puedes tributar 1 carta de tu mano.", true, false,
                    Limit.ONCE_PER_TURN_BETWEEN_COPIES, ReplacementCost.TRIBUTE_ONE_FROM_HAND)));

    /** Replacements a card gives as a Recurso (inherited). */
    private static final Map<String, List<Replacement>> INHERITED_REPLACEMENTS = Map.of(
            // E1-G19 Hydra Colosal
            "hydra colosal", List.of(new Replacement("hydra-colosal:hereda-todos-los-turnos", TODOS,
                    "Heredado de Hydra Colosal: si esta carta fuera a ser destruida, en su lugar, puedes tributar todos los recursos [Hydra] de esta carta.",
                    true, false, Limit.NONE, ReplacementCost.TRIBUTE_RESOURCES, CardFilter.any().withClasses("hydra"))),
            // E1-G10 Deriduragon
            "deriduragon", List.of(new Replacement("deriduragon:hereda-todos-los-turnos", TODOS,
                    "Heredado de Deriduragon: cuando esta carta fuera a ser removida por el oponente, puede tributar los recursos de esta carta.",
                    false, true, Limit.NONE, ReplacementCost.TRIBUTE_RESOURCES)));

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

    /** "En Mano: al entrar al paso de bloqueo: puedes jugar esta carta pagando su coste." */
    private static EffectScript playAtBlockStep(String id) {
        return new EffectScript(id + ":en-mano", Moment.ON_EVENT, false, "En Mano",
                "Al entrar al paso de bloqueo: puedes jugar esta carta pagando su coste.",
                List.of(new PlaySelfFromZone("hand", false)))
                .when(new CombatStep(Combat.Step.BLOCK, Combat.Phase.ENTER))
                .fromZone("hand");
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
