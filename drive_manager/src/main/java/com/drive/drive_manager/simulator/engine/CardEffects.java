package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectScript.Condition;
import com.drive.drive_manager.simulator.engine.EffectScript.Limit;
import com.drive.drive_manager.simulator.engine.EffectScript.Moment;
import com.drive.drive_manager.simulator.engine.EffectStep.DeclareAttack;
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
                List.of(new DestroyEnemy(1, CardFilter.any().withLevel(1)))));
        // ST3-8 Zohx
        add("zohx", new EffectScript("zohx:al-final-del-ataque", Moment.ON_ATTACK_END, false, AL_FINAL,
                "(1) Si tienes 10 o más tributos [bestia]: esta carta puede invocar por Ritual 1 criatura de tus tributos reduciendo el coste especial en 1.",
                List.of(new RitualFromZone("tributeZone", true, 1)))
                .when((room, event, source, controller) -> countInZone(room, controller, "tributeZone", CardFilter.any().withClasses("bestia")) >= 10)
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
        // ST3-9 Zhaglizer
        add("zhaglizer", new EffectScript("zhaglizer:al-atacar", Moment.ON_ATTACK, true, AL_ATACAR,
                "Elige 1 enemigo nivel 2 o menor; destrúyelo.",
                List.of(new DestroyEnemy(1, CardFilter.any().withMaxLevel(2)))));
        // ST3-10 Hong (Letalidad 1: combat rule)
        add("hong", new EffectScript("hong:todos-los-turnos", Moment.ON_EVENT, false, TODOS,
                "Una vez por turno: si destruyes algún enemigo, puedes elegir 1 enemigo nivel 1; destrúyelo.",
                List.of(new DestroyEnemy(1, CardFilter.any().withLevel(1))))
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
                        List.of(new SacrificeAlly(), new DestroyEnemy(1, CardFilter.any().withMaxLevel(3))))
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
                List.of(new DestroyEnemy(1, CardFilter.any().withMaxLevel(3))))
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
                List.of(new PickAlly(), new GainUntilEndOfTurn("Provocar 3"), new DeclareAttack()))
                .limited(Limit.ONCE_PER_TURN_BETWEEN_COPIES));
    }

    private CardEffects() {}

    private static void add(String name, EffectScript... scripts) {
        BY_NAME.put(name, List.of(scripts));
    }

    public static List<EffectScript> of(String cardName) {
        if (cardName == null) return List.of();
        return BY_NAME.getOrDefault(cardName.trim().toLowerCase(Locale.ROOT), List.of());
    }

    /** The card's own effects plus those of the keywords the engine runs. */
    public static List<EffectScript> of(CardSlot card) {
        List<EffectScript> all = new ArrayList<>(of(card.getCardName()));
        for (String keyword : card.getKeywords()) {
            EffectScript script = KEYWORDS.get(keyword.toLowerCase(Locale.ROOT));
            if (script != null) all.add(script);
        }
        return all;
    }

    /** Looks an effect up by its id (card effects and keywords). */
    public static Optional<EffectScript> byId(String id) {
        return BY_NAME.values().stream().flatMap(List::stream).filter(s -> s.id().equals(id)).findFirst()
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

    private static long countInZone(GameRoom room, String playerId, String zone, CardFilter filter) {
        PlayerState player = room.getPlayer(playerId);
        if (player == null) return 0;
        List<CardSlot> cards = "tributeZone".equals(zone) ? player.getTributeZone() : List.of();
        return cards.stream().filter(filter::matches).count();
    }
}
