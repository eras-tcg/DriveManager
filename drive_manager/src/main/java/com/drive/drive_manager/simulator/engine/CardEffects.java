package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectScript.Condition;
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
import com.drive.drive_manager.simulator.engine.EffectStep.MaterialToLife;
import com.drive.drive_manager.simulator.engine.EffectStep.ReduceChosen;
import com.drive.drive_manager.simulator.engine.EffectStep.ReduceTriggerToZero;
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
import com.drive.drive_manager.simulator.engine.EffectStep.GainStrength;
import com.drive.drive_manager.simulator.engine.EffectStep.IfDone;
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

    /** Keyword abilities with an effect the engine runs ("Celeridad"). Others (Provocar, Letalidad…) are combat rules. */
    private static final Map<String, EffectScript> KEYWORDS = Map.of(
            "celeridad", new EffectScript("kw:celeridad", Moment.ON_SUMMON, false, "Celeridad",
                    "Puede subir a tu línea frontal.", List.of(new MoveSelfToFront()))
                    .when((room, event, source, controller) -> !EffectEngine.inFrontLine(source)));

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
                List.of(new SendSelfTo("discardPile")),
                List.of(new SummonFromZone("tributeZone", CardFilter.creatures().withColors("P"), 1))));
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
                .when((room, event, source, controller) -> countInZone(room, controller, "tributeZone", CardFilter.any().withClasses("bestia")) >= 10)
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
                .when(CardEffects::youDestroyedAnEnemy)
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
                        .when(allyDestroyed(CardFilter.any()))
                        .onlyAllyTurn()
                        .limited(Limit.ONCE_PER_TURN));
        // ST3-14 Mawser (Provocar: combat rule)
        add("mawser", new EffectScript("mawser:todos-los-turnos", Moment.ON_EVENT, true, TODOS,
                "Una vez por turno: si esta carta destruye algún enemigo, elige 1 enemigo nivel 3 o menor; destrúyelo.",
                List.of(new ChooseEnemy(1, CardFilter.any().withMaxLevel(3))),
                List.of(new DestroyChosen()))
                .when(CardEffects::thisDestroyedAnEnemy)
                .limited(Limit.ONCE_PER_TURN));
        // ST3-15 Berkelus El Cazarrecompensas Del Bajomundo
        add("berkelus el cazarrecompensas del bajomundo", new EffectScript("berkelus:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Reactiva esta carta. Luego: puedes enviar 10 criaturas [bestia] de tus tributos al fondo de tu mazo; "
                        + "esta carta declara ataque y gana Furia 1 hasta el final del turno.",
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
                List.of(new GainGems(1), new Draw(1), new TributeFromHand(1)))
                .when(allyDestroyed(CardFilter.colors("P"))));
        // ST3-17 Alimento De Las Sombras (utility)
        add("alimento de las sombras",
                new EffectScript("alimento-de-las-sombras:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Un aliado invoca por Ritual 1 criatura de tus tributos reduciendo su coste especial en 1.",
                        List.of(new RitualFromZone("tributeZone", false, 1))),
                new EffectScript("alimento-de-las-sombras:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "(1) Si algún aliado es destruido por el oponente: puedes jugar esta carta sin pagar su coste.",
                        List.of(new PlaySelfFree()))
                        .when(CardEffects::allyDestroyedByOpponent)
                        .fromZone("hand")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // ST3-18 Caza Nocturna (utility; its cost reduction is in playCost)
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
                        List.of(new SacrificeAlly(true), new UntapAlly()))
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
                .when((room, event, source, controller) -> allySummonedByEffect(CardFilter.any()).test(room, event, source, controller)
                        && controls(room, controller, CardFilter.creatures().withClasses("caballero"), 4))
                .fromZone("hand")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // ST4-4 Espada De Primera Línea (its continuous "+2 fuerza y Celeridad con 3 aliados Rojos" is not applied yet)
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
                .when((room, event, source, controller) -> GameEvent.EFFECT_SUMMON.equals(event.getType())));
        // ST4-11 Ballesta Montada
        add("ballesta montada", new EffectScript("ballesta-montada:final-del-turno", Moment.ON_EVENT, false, "Turno Aliado",
                "Al final del turno: esta carta activa puede atacar al oponente sin agotarse.",
                List.of(new DeclareAttack(true)))
                .when((room, event, source, controller) -> turnEnds(event, controller) && !source.isTapped()
                        && EffectEngine.inFrontLine(source) && room.getCombat() == null)
                .onlyAllyTurn());
        // ST4-12 Franchute: Perforante (combat rule); its inherited effect waits for the inherited effects
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
                        .when(CardEffects::entersCombat)
                        .limited(Limit.ONCE_PER_TURN),
                new EffectScript("rey-franco:final-del-turno", Moment.ON_EVENT, false, "Turno Aliado",
                        "Al final del turno: 1 aliado puede invocar por Ascenso una criatura de tu mano reduciendo el coste especial en 2.",
                        List.of(new SpecialFromZone("promotion", "hand", 2, false)))
                        .when((room, event, source, controller) -> turnEnds(event, controller))
                        .onlyAllyTurn());
        // ST4-16 Reino Medieval (structure)
        add("reino medieval", new EffectScript("reino-medieval:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Si algún aliado Rojo es invocado por efecto: puedes agotar esta carta para ganar 1 gema y tomar 1 carta Roja de tus tributos. "
                        + "Si lo haces: tributa 1 carta de tu mano.",
                List.of(new TapSelf()),
                List.of(new GainGems(1), new TakeFromZone("tributeZone", CardFilter.colors("R"), 1, false), new TributeFromHand(1)))
                .when(allySummonedByEffect(CardFilter.colors("R"))));
        // ST4-17 Caballeros De La Primera Línea (utility)
        add("caballeros de la primera línea",
                new EffectScript("caballeros-primera-linea:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Puedes invocar 1 criatura Roja de tu mano reduciendo su coste en 1.",
                        List.of(new MayDo("¿Invocas 1 criatura Roja de tu mano reduciendo su coste en 1?", 1),
                                new SummonFromZone("hand", CardFilter.creatures().withColors("R"), 1))),
                new EffectScript("caballeros-primera-linea:en-mano", Moment.ON_EVENT, false, "En Mano",
                        "(1) Si algún aliado es destruido: puedes jugar esta carta sin pagar su coste.",
                        List.of(new PlaySelfFree()))
                        .when(allyDestroyed(CardFilter.any()))
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
                        .when(allySummonedByEffect(CardFilter.any()))
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
                        .when(tributedFromDeckBy(CardFilter.colors("G")))
                        .fromZone("tributeZone"),
                new EffectScript("dirumy:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                        "Mira las primeras 4 cartas de tu mazo: de ellas toma hasta 1 carta Verde o Blanca. Envía el resto al fondo de tu mazo.",
                        List.of(new LookTop(4), new TakeFromLookedRestToBottom(1, CardFilter.colors("G", "W")))));
        inherit("dírumy", new EffectScript("dirumy:hereda-al-atacar", Moment.ON_ATTACK, false, AL_ATACAR,
                "Heredado de Dírumy: puedes tributar \"Dírumy\" de los recursos de esta carta para robar 1 carta.",
                List.of(new TributeResource("Dírumy")), List.of(new Draw(1))));
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
                .when(CardEffects::enemyDestroyedByYourEffect));
        // ST2-4 Duregón (Iniciador; its Evolución cost reduction is in summonerReduction)
        add("duregón", new EffectScript("duregon:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Tributa 2 cartas del tope de tu mazo. Luego: toma 1 carta Verde o [dragón] de tus tributos.",
                List.of(new TributeTop(2), new TakeFromZone("tributeZone", CardFilter.colors("G").withClasses("dragón"), 1, false))));
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
        // ST2-8 Flambreik (its optional Evolución cost reduction is not applied yet)
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
        add("ddraig", new EffectScript("ddraig:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Tributa 3 cartas del tope de tu mazo. Luego: elige 1 enemigo de fuerza 5 o menos; destrúyelo.",
                List.of(new TributeTop(3), new DestroyEnemy(1, CardFilter.creatures().withMaxStrength(5)))));
        inherit("ddraig", new EffectScript("ddraig:hereda-al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Heredado de Ddraig: elige 1 enemigo de fuerza 5 o menos; destrúyelo.",
                List.of(new ChooseEnemy(1, CardFilter.creatures().withMaxStrength(5))), List.of(new DestroyChosen())));
        // ST2-11 Wyverno De Pantano
        add("wyverno de pantano", new EffectScript("wyverno-pantano:tributada", Moment.ON_EVENT, false, "En Tributos",
                "(1) Si esta carta es tributada desde tu mazo: puedes tributar 1 carta de tu mano para tomar 1 carta Verde de tus tributos.",
                List.of(new TributeFromHand(1)), List.of(new TakeFromZone("tributeZone", CardFilter.colors("G"), 1, false)))
                .when(tributedFromDeckBy(null))
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
                        .when(CardEffects::youDestroyedAnEnemy)
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
                        List.of(new SendFromZoneToDeckBottom("tributeZone", 5, fiveDragons)), List.of(new UntapSelf()))
                        .when((room, event, source, controller) -> event.getData() != null && Boolean.TRUE.equals(event.getData().get("enemyDestroyed")))
                        .limited(Limit.ONCE_PER_TURN));
        // ST2-16 Volcán De Dragones (structure)
        add("volcán de dragones", new EffectScript("volcan-de-dragones:turno-aliado", Moment.ON_EVENT, false, "Turno Aliado",
                "Si invocas especialmente 1 [dragón]: puedes agotar esta carta para ganar 1 gema y robar 1 carta. Si lo haces: tributa 1 carta de tu mano.",
                List.of(new TapSelf()),
                List.of(new GainGems(1), new Draw(1), new TributeFromHand(1)))
                .when(youSpecialSummoned(dragon))
                .onlyAllyTurn());
        // ST2-17 Huevo De Dragón (utility; its slow effect from the field is not applied yet)
        add("huevo de dragón", new EffectScript("huevo-de-dragon:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                "Tributa 4 cartas del tope de tu mazo. Luego: toma 1 carta Verde o [dragón] de tus tributos.",
                List.of(new TributeTop(4), new TakeFromZone("tributeZone", CardFilter.colors("G").withClasses("dragón"), 1, false))));
        // ST2-18 Llamarada (utility)
        add("llamarada",
                new EffectScript("llamarada:tributada", Moment.ON_EVENT, false, "En Tributos",
                        "(1) Si esta carta es tributada desde tu mazo por un aliado [dragón]: puedes jugar esta carta reduciendo su coste en el nivel de dicho aliado.",
                        List.of(new PlaySelfFromZone("tributeZone", true)))
                        .when(tributedFromDeckBy(CardFilter.creatures().withClasses("dragón")))
                        .fromZone("tributeZone")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES),
                new EffectScript("llamarada:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Elige 1 enemigo de fuerza 11 o menos; destrúyelo.",
                        List.of(new ChooseEnemy(1, CardFilter.creatures().withMaxStrength(11))), List.of(new DestroyChosen())));
    }

    /** Evolución by Duregón: "si tienes 5 o más tributos [dragón]: reduce en 1 el coste especial". */
    static int summonerReduction(GameRoom room, PlayerState player, String kind, CardSlot summoner) {
        if (!"evolution".equals(kind) || summoner == null || !"duregón".equalsIgnoreCase(summoner.getCardName())) return 0;
        return countInZone(room, player.getUserId(), "tributeZone", CardFilter.any().withClasses("dragón")) >= 5 ? 1 : 0;
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
        // ST1-3 Mihel (its "Todos Los Turnos" effect, as a material that reduces 2 more, is not applied yet)
        add("mihel", new EffectScript("mihel:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Roba 2 cartas. Luego: tributa 2 cartas de tu mano.",
                List.of(new Draw(2), new TributeFromHand(2))));
        // ST1-4 Flureteus (Iniciador; its "Turno Aliado" summon from the Tributos is not applied yet)
        add("flureteus", new EffectScript("flureteus:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                "Mira las primeras 5 cartas de tu mazo: de ellas toma hasta 2 cartas Azules o Blancas. Envía el resto al fondo de tu mazo. "
                        + "Luego: tributa 2 cartas de tu mano.",
                List.of(new LookTop(5), new TakeFromLookedRestToBottom(2, CardFilter.colors("B", "W")), new TributeFromHand(2))));
        // ST1-5 Flarix
        add("flarix",
                new EffectScript("flarix:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes tributar hasta 2 materiales de esta carta; toma 1 carta Azul de tus tributos por cada material tributado.",
                        List.of(new TributeMaterials(2, true)), List.of(new TakePerCount("tributeZone", CardFilter.colors("B")))),
                new EffectScript("flarix:en-tributos", Moment.ON_EVENT, false, "En Tributos",
                        "(1) Si reduces a 0 la fuerza de algún enemigo: puedes enviar esta carta al fondo de tu mazo para infringir 9 de daño directo.",
                        List.of(new SendSelfTo("deck")), List.of(new DamageByEffect(9)))
                        .when(youReducedStrengthTo(0))
                        .fromZone("tributeZone")
                        .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // ST1-6 Zula
        add("zula", new EffectScript("zula:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Si tienes 4 o menos vidas: puedes tributar 1 material de esta carta; coloca hasta 1 material de esta carta al tope de tus vidas bocarriba.",
                List.of(new TributeMaterials(1, false)), List.of(new MaterialToLife()))
                .when(livesAtMost(4)));
        // ST1-7 Fhalid: no effect
        // ST1-8 Arc xioma
        add("arc xioma",
                new EffectScript("arc-xioma:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                        "Puedes tributar 1 material de esta carta y elegir 1 enemigo; reduce su fuerza en 3 hasta el final del turno.",
                        List.of(new TributeMaterials(1, false), new ChooseEnemy(1, enemy)), List.of(new ReduceChosen(3, false))),
                new EffectScript("arc-xioma:en-tributos", Moment.ON_EVENT, false, "En Tributos",
                        "Si reduces a 2 o menos la fuerza de algún enemigo: puedes enviar esta carta al fondo de tu mazo para reducirla a 0.",
                        List.of(new SendSelfTo("deck")), List.of(new ReduceTriggerToZero()))
                        .when(youReducedStrengthTo(2))
                        .fromZone("tributeZone"));
        // ST1-9 Bertalum
        add("bertalum", new EffectScript("bertalum:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Puedes tributar 2 materiales de esta carta para elegir 1 enemigo; reduce en 4 su fuerza hasta el final del turno.",
                List.of(new TributeMaterials(2, false), new ChooseEnemy(1, enemy)), List.of(new ReduceChosen(4, false))));
        // ST1-10 Sahiel (Reactivar: keyword)
        add("sahiel", new EffectScript("sahiel:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                "Si tienes 5 o menos vidas: puedes tributar 2 materiales de esta carta; coloca la carta del tope de tu mazo al tope de tus vidas bocabajo.",
                List.of(new TributeMaterials(2, false)), List.of(new DeckTopToLife()))
                .when(livesAtMost(5)));
        // ST1-11 Falis
        add("falis", new EffectScript("falis:en-tributos", Moment.ON_EVENT, false, "En Tributos",
                "(1) Si reduces a 0 la fuerza de algún enemigo: puedes invocar esta carta reduciendo su coste en 2.",
                List.of(new SummonSelfFromZone("tributeZone", 2)))
                .when(youReducedStrengthTo(0))
                .fromZone("tributeZone")
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // ST1-12 Mathaus (Letalidad 1: combat rule)
        add("mathaus", new EffectScript("mathaus:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                "Una vez por turno: puedes tributar 2 materiales de esta carta; elige 1 enemigo para reducir su fuerza en 3 hasta el final del turno oponente.",
                List.of(new TributeMaterials(2, false), new ChooseEnemy(1, enemy)), List.of(new ReduceChosen(3, true)))
                .onlyAllyTurn()
                .limited(Limit.ONCE_PER_TURN));
        // ST1-13 Falanx: its Requerimiento and "En Tributos" special summon are not applied yet
        // ST1-14 Galafendrax El Transmutador (its "En Invocación" is not applied yet)
        add("galafendrax el transmutador", new EffectScript("galafendrax:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Una vez por turno: si reduces a 0 la fuerza de algún enemigo, puedes reactivar esta carta.",
                List.of(new UntapSelf()))
                .when(youReducedStrengthTo(0))
                .limited(Limit.ONCE_PER_TURN));
        // ST1-15 Hez (Reactivar: keyword)
        add("hez", new EffectScript("hez:inicio-del-turno", Moment.ON_EVENT, false, TODOS,
                "Al inicio del turno: puedes tributar 2 materiales de esta carta para elegir todos los enemigos en la línea frontal; "
                        + "reduce en 4 su fuerza hasta el final del turno.",
                List.of(new TributeMaterials(2, false)), List.of(new TargetAllEnemies(), new ReduceChosen(4, false)))
                .when((room, event, source, controller) -> GameEvent.TURN_START.equals(event.getType())));
        // ST1-16 Portal Al Mundo Espiritual (structure)
        add("portal al mundo espiritual", new EffectScript("portal-mundo-espiritual:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Si algún aliado [ente] es invocado por efecto: puedes agotar esta carta; añádele 1 criatura [ente] de menor nivel de tus tributos "
                        + "como material para ganar 1 gema.",
                List.of(new TapSelf()),
                List.of(new AttachLowerFromTributes(CardFilter.creatures().withClasses("ente")), new GainGems(1)))
                .when(allySummonedByEffect(CardFilter.any().withClasses("ente"))));
        // ST1-17 Composición Adimensional (utility; its "Turno Aliado" Materialización is not applied yet)
        add("composición adimensional",
                new EffectScript("composicion-adimensional:en-juego", Moment.ON_PLAY, true, EN_JUEGO,
                        "Tributa hasta 3 cartas de tu mano. Luego: roba la misma cantidad y coloca esta carta agotada en tu retaguardia.",
                        List.of(new TributeFromHandUpTo(3), new DrawLastCount(), new KeepOnField())),
                // A slow effect from the field: the ally chosen gets this card as a material and is the summoner
                new EffectScript("composicion-adimensional:turno-aliado", Moment.ACTIVATED, false, "Turno Aliado",
                        "Puedes añadir esta carta como material a un aliado para que invoque por Materialización 1 criatura en tu mano o tributos "
                                + "reduciendo el coste especial en 1.",
                        List.of(new PickAlly(), new AttachSelfToSubject(),
                                new SpecialFromZone("materialization", "hand,tributeZone", 1, "subject", CardFilter.any())))
                        .onlyAllyTurn());
        // ST1-18 Paso Interdimensional: not applied yet
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

    /** The card's own effects, those its Recursos give it and those of the keywords the engine runs. */
    public static List<EffectScript> of(CardSlot card) {
        List<EffectScript> all = new ArrayList<>(of(card.getCardName()));
        for (CardSlot resource : card.getResources()) {
            if (resource.getCardName() != null) all.addAll(INHERITED.getOrDefault(resource.getCardName().trim().toLowerCase(Locale.ROOT), List.of()));
        }
        for (String keyword : card.getKeywords()) {
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

    /** Cost to play a utility or structure, after the reductions printed on it. */
    public static int playCost(GameRoom room, String userId, CardSlot card) {
        int cost = card.getCost() == null ? 0 : card.getCost();
        // Caza Nocturna: "cuando fueras a jugar esta carta, si tienes menos de 0 gemas: reduce su coste en 1"
        if ("caza nocturna".equalsIgnoreCase(card.getCardName()) && EffectEngine.gemsOf(room, userId) < 0) cost -= 1;
        return Math.max(0, cost);
    }

    // ------------------------------------------------------------------ Trigger conditions

    private static boolean isDestruction(GameEvent event) {
        return GameEvent.DESTROY.equals(event.getType()) || GameEvent.SACRIFICE.equals(event.getType());
    }

    private static String ownerOf(GameEvent event) {
        return event.getData() == null ? null : (String) event.getData().get("ownerId");
    }

    /** "si algún aliado [filtro] es destruido" (sacrifices included: rule 4.0.4.2). */
    private static Condition allyDestroyed(CardFilter filter) {
        return (room, event, source, controller) -> {
            if (!isDestruction(event) || !controller.equals(ownerOf(event))) return false;
            CardSlot destroyed = EffectEngine.eventCard(room, event);
            return destroyed != null && "creature".equals(destroyed.getCardType()) && filter.matches(destroyed);
        };
    }

    /** "si algún aliado es destruido por el oponente". */
    private static boolean allyDestroyedByOpponent(GameRoom room, GameEvent event, CardSlot source, String controller) {
        return allyDestroyed(CardFilter.any()).test(room, event, source, controller)
                && event.getPlayerId() != null && !controller.equals(event.getPlayerId());
    }

    /** "si destruyes algún enemigo". */
    private static boolean youDestroyedAnEnemy(GameRoom room, GameEvent event, CardSlot source, String controller) {
        return isDestruction(event) && controller.equals(event.getPlayerId()) && !controller.equals(ownerOf(event));
    }

    /** "si esta carta destruye algún enemigo": in a clash, or by one of its effects. */
    private static boolean thisDestroyedAnEnemy(GameRoom room, GameEvent event, CardSlot source, String controller) {
        return isDestruction(event) && event.getData() != null
                && source.getInstanceId().equals(event.getData().get("byCard")) && !controller.equals(ownerOf(event));
    }

    /** "si esta carta es tributada desde tu mazo [por efecto de una carta filtro]". */
    private static Condition tributedFromDeckBy(CardFilter by) {
        return (room, event, source, controller) -> {
            if (!GameEvent.TRIBUTE.equals(event.getType()) || !"deck".equals(event.getFromZone())) return false;
            if (!source.getInstanceId().equals(event.getCardInstanceId())) return false;
            if (by == null) return true;
            Object byCard = event.getData() == null ? null : event.getData().get("byCard");
            for (PlayerState p : room.getPlayers().values()) {
                for (List<CardSlot> zone : List.of(p.getField(), p.getHand(), p.getTributeZone(), p.getDiscardPile())) {
                    for (CardSlot c : zone) if (c.getInstanceId().equals(byCard)) return by.matches(c);
                }
            }
            return false;
        };
    }

    /** "si algún enemigo es destruido por tus efectos". */
    private static boolean enemyDestroyedByYourEffect(GameRoom room, GameEvent event, CardSlot source, String controller) {
        return youDestroyedAnEnemy(room, event, source, controller) && event.getEffectId() != null;
    }

    /** "si invocas especialmente 1 [filtro]". */
    private static Condition youSpecialSummoned(CardFilter filter) {
        return (room, event, source, controller) -> {
            if (!GameEvent.SPECIAL_SUMMON.equals(event.getType()) || !controller.equals(event.getPlayerId())) return false;
            CardSlot summoned = EffectEngine.eventCard(room, event);
            return summoned != null && filter.matches(summoned);
        };
    }

    /** "si reduces [a N o menos] la fuerza de algún enemigo". */
    private static Condition youReducedStrengthTo(Integer atMost) {
        return (room, event, source, controller) -> {
            if (!GameEvent.STRENGTH_REDUCED.equals(event.getType()) || !controller.equals(event.getPlayerId())) return false;
            Object to = event.getData() == null ? null : event.getData().get("to");
            return atMost == null || (to instanceof Number n && n.intValue() <= atMost);
        };
    }

    /** "si tienes N o menos vidas". */
    private static Condition livesAtMost(int lives) {
        return (room, event, source, controller) -> {
            PlayerState player = room.getPlayer(controller);
            return player != null && player.getLifeStack().size() <= lives;
        };
    }

    /** "si algún aliado [filtro] es invocado por efecto". */
    private static Condition allySummonedByEffect(CardFilter filter) {
        return (room, event, source, controller) -> {
            if (!GameEvent.EFFECT_SUMMON.equals(event.getType()) || !controller.equals(event.getPlayerId())) return false;
            CardSlot summoned = EffectEngine.eventCard(room, event);
            return summoned != null && filter.matches(summoned);
        };
    }

    /** "controlas algún [filtro] nivel N o mayor". */
    private static boolean controls(GameRoom room, String controller, CardFilter filter, int minLevel) {
        PlayerState player = room.getPlayer(controller);
        return player != null && player.getField().stream()
                .anyMatch(c -> filter.matches(c) && c.getLevel() != null && c.getLevel() >= minLevel);
    }

    /** "al final del turno" (of its controller). */
    private static boolean turnEnds(GameEvent event, String controller) {
        return GameEvent.TURN_END.equals(event.getType()) && controller.equals(event.getPlayerId());
    }

    /** "si esta carta entra en combate": it attacks, blocks or is attacked. */
    private static boolean entersCombat(GameRoom room, GameEvent event, CardSlot source, String controller) {
        String id = source.getInstanceId();
        if (GameEvent.ATTACK_DECLARED.equals(event.getType())) {
            return id.equals(event.getCardInstanceId()) || (event.getData() != null && id.equals(event.getData().get("targetId")));
        }
        return GameEvent.BLOCK.equals(event.getType()) && id.equals(event.getCardInstanceId());
    }

    private static long countInZone(GameRoom room, String playerId, String zone, CardFilter filter) {
        PlayerState player = room.getPlayer(playerId);
        if (player == null) return 0;
        List<CardSlot> cards = "tributeZone".equals(zone) ? player.getTributeZone() : "hand".equals(zone) ? player.getHand() : List.of();
        return cards.stream().filter(filter::matches).count();
    }
}
