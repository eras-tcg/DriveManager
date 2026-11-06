package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.simulator.actions.PreRoomActions;
import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.room.CardMeta;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.PlayerState;
import com.drive.drive_manager.simulator.room.RoomService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.*;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Positions the bot got wrong in the user's games (2026-11-04), each set up with the starter decks' real cards: the
 * bot ({@code bot.puzzle.settings}) plays its turn there, the rival answers by the heuristic, and the puzzle says
 * whether it did the right thing. With each, how its first decision went: every candidate, its mean score, its tries
 * and the round it was dropped in. Run with {@code -Dbot.puzzle=true -Dtest=BotPuzzleRun} ({@code bot.puzzle.only}:
 * one puzzle by name; {@code bot.puzzle.repeat}: times each is played, with other seeds; {@code bot.puzzle.seed}: the first seed, 1000).
 */
class BotPuzzleRun {

    private static final double FRONT = 20.0, BACK = 70.0;
    private static final Map<String, Card> META = load();
    private int ids;

    private record Puzzle(String name, String expected, java.util.function.Consumer<Table> setUp, Predicate<Table> passed) {}

    private record Table(GameRoom room, PlayerState host, PlayerState guest, List<GameEvent> turn) {}

    @Test
    void run() {
        assumeTrue(Boolean.getBoolean("bot.puzzle"), "runs only with -Dbot.puzzle=true");
        BotSettings s = BotSettings.DEFAULT.with(System.getProperty("bot.puzzle.settings", ""));
        String only = System.getProperty("bot.puzzle.only", "");
        int repeat = Integer.getInteger("bot.puzzle.repeat", 3);
        int passedAll = 0, playedAll = 0;
        for (Puzzle p : cases()) {
            if (!only.isEmpty() && !p.name().equals(only)) continue;
            int passed = 0;
            List<String> firstAudit = null;
            for (int r = 0; r < repeat; r++) {
                Table t = table(3);
                p.setUp().accept(t);
                List<String> audit = play(t, s, Long.getLong("bot.puzzle.seed", 1000L) + r);
                if (firstAudit == null) firstAudit = audit;
                // bot.puzzle.log: the history of the first play (what the engine says happened)
            if (Boolean.getBoolean("bot.puzzle.log") && r == 0) t.room().getEventLog().forEach(l -> System.out.println("[puzzle]     log: " + l.getText()));
            if (p.passed().test(t)) passed++;
                else if (Boolean.getBoolean("bot.puzzle.trace") && r == 0) {
                    StringBuilder tr = new StringBuilder();
                    for (GameEvent e : t.turn()) tr.append(e.getType()).append(e.getCardName() == null ? "" : " " + e.getCardName()).append(e.getPlayerId() == null ? "" : "(" + e.getPlayerId() + ")").append("; ");
                    System.out.println("[puzzle]     turno: " + tr);
                }
            }
            passedAll += passed;
            playedAll += repeat;
            System.out.println("[puzzle] " + (passed == repeat ? "PASA " : passed == 0 ? "FALLA" : "A VECES") + " " + p.name() + ": " + passed + "/" + repeat + " — " + p.expected());
            if (firstAudit != null) firstAudit.stream().limit(Boolean.getBoolean("bot.puzzle.trace") ? 80 : 14).forEach(l -> System.out.println("[puzzle]     " + l));
        }
        System.out.println("[puzzle] total " + passedAll + "/" + playedAll);
    }

    private List<Puzzle> cases() {
        return List.of(
                new Puzzle("golpe-ganador", "el rival tiene 1 vida y ningún bloqueador: atacar y ganar", t -> {
                    gems(t, 2);
                    front(t.host(), "Peón De Línea Frontal");
                    t.host().getHand().addAll(List.of(card("Caballero Férreo"), card("Caballero Férreo")));
                    lives(t.guest(), 1, false);
                }, t -> "host".equals(t.room().getWinnerId())),
                // Duelo st1-st2 s13395 (2026-11-06): jugó Huevo, vació el mazo, evolucionó a Carmesí y no atacó; perdió por mazo
                new Puzzle("remate-mazo-corto", "el rival (st1) tiene 1 vida y la mesa vacía; Dragón Volcánico, Dracoventus y Galveta listos, Huevo De Dragón y Dracoventus en la mano, 4 cartas en el mazo: atacar y ganar", t -> {
                    gems(t, 5);
                    t.host().getDeck().clear();
                    deck(t.host(), "Huevo De Dragón", "Huevo De Dragón", "Flamagón", "Flamagón");
                    for (String n : List.of("Dragón Carmesí", "Fadrake", "Flambreik", "Wyfreik", "Dragón Delta", "Llamarada", "Huevo De Dragón")) t.host().getTributeZone().add(card(n));
                    CardSlot volc = front(t.host(), "Dragón Volcánico");
                    for (String n : List.of("Saildreik", "Ddraig", "Galveta")) volc.getResources().add(card(n));
                    front(t.host(), "Dracoventus").getResources().add(card("Wyverno De Pantano"));
                    front(t.host(), "Galveta").getResources().add(card("Flamagón"));
                    for (int i = 0; i < 3; i++) back(t.host(), "Volcán De Dragones");
                    t.host().getHand().addAll(List.of(card("Dracoventus"), card("Huevo De Dragón")));
                    lives(t.host(), 3, true);
                    for (String n : List.of("Flarix", "Falanx", "Mathaus", "Falis", "Flarix", "Zula", "Paso Interdimensional", "Hez", "Mihel")) { CardSlot c = card(n); c.setFaceDown(true); t.guest().getHand().add(c); }
                    lives(t.guest(), 1, true);
                }, t -> "host".equals(t.room().getWinnerId())),
                new Puzzle("perforante", "atacar con Franchute a la criatura agotada del rival y destruirla", t -> {
                    gems(t, 0);
                    front(t.host(), "Franchute");
                    CardSlot tired = front(t.guest(), "Peón De Línea Frontal");
                    tired.setTapped(true);
                    lives(t.guest(), 4, true);
                }, t -> t.guest().getField().isEmpty()),
                new Puzzle("alimento-sin-aliados", "no jugar Alimento De Las Sombras sin criaturas propias en el campo", t -> {
                    gems(t, 3);
                    t.host().getHand().add(card("Alimento De Las Sombras"));
                    t.host().getTributeZone().add(card("Cazador De Bestias"));
                }, t -> t.host().getHand().stream().anyMatch(c -> "Alimento De Las Sombras".equals(c.getCardName()))),
                new Puzzle("caza-retaguardia", "no jugar Caza Nocturna si su única criatura está en la Retaguardia (no puede atacar)", t -> {
                    gems(t, 2);
                    back(t.host(), "Slicker");
                    t.host().getHand().add(card("Caza Nocturna"));
                    lives(t.guest(), 4, true);
                }, t -> t.host().getHand().stream().anyMatch(c -> "Caza Nocturna".equals(c.getCardName()))),
                // Caza Nocturna (2026-11-04): an attack by an effect needs no active creature, only one in the Línea frontal
                new Puzzle("caza-agotada", "jugar Caza Nocturna sobre Slicker ya agotado en la Línea frontal: el ataque por efecto no exige que esté activo (segundo ataque)", t -> {
                    gems(t, 2);
                    front(t.host(), "Slicker").setTapped(true);
                    t.host().getHand().addAll(List.of(card("Caza Nocturna"), card("Filo Vengativo")));
                    lives(t.guest(), 4, true);
                }, t -> t.host().getHand().stream().noneMatch(c -> "Caza Nocturna".equals(c.getCardName()))),
                // The user's game (2026-11-04): at -1 gems Caza costs 0, but its only creature is in the Retaguardia
                new Puzzle("caza-deuda-retaguardia", "con -1 gemas (Caza cuesta 0), una criatura nivel 2 en la Retaguardia y una estructura agotada: no jugar Caza Nocturna (no puede atacar desde ahí)", t -> {
                    gems(t, -1);
                    back(t.host(), "Caballero Férreo");
                    back(t.host(), "Cueva De Bestias").setTapped(true);
                    t.host().getHand().add(card("Caza Nocturna"));
                    lives(t.guest(), 4, true);
                }, t -> t.host().getHand().stream().anyMatch(c -> "Caza Nocturna".equals(c.getCardName()))),
                // The user's game (2026-11-04): Krokler destroys only from the Línea frontal. Summoned normally it enters the
                // Retaguardia and destroys nothing; by Ritual over Buratán (the material shares its color, Violeta) it moves
                // up to the Línea frontal after the special summon and destroys the enemy level 1, for 1 gem instead of 2
                new Puzzle("krokler-ritual", "con 2 gemas, Buratán (nivel 1, violeta) y Krokler (nivel 2, violeta, En Invocación destruye 1 enemigo nivel 1 desde la Línea frontal) en la mano y un Peón rival: Buratán y Krokler por Ritual sobre él, que sube al frente y destruye el Peón", t -> {
                    gems(t, 2);
                    t.host().getHand().addAll(List.of(card("Buratán"), card("Krokler")));
                    front(t.guest(), "Peón De Línea Frontal");
                    lives(t.guest(), 4, true);
                }, t -> t.guest().getField().isEmpty() && t.turn().stream().anyMatch(e -> GameEvent.SPECIAL_SUMMON.equals(e.getType()) && "Krokler".equals(e.getCardName()))),
                // The same with a material of another color (Peón, Roja): no Ritual is possible, so Krokler would destroy
                // nothing this turn either way
                new Puzzle("krokler-sin-color", "con Peón (nivel 1, rojo) y Krokler (violeta): no hay Ritual (el material no comparte color) ni destrucción; no pasarse de gemas invocando los dos (2 gemas, cuestan 3)", t -> {
                    gems(t, 2);
                    t.host().getHand().addAll(List.of(card("Peón De Línea Frontal"), card("Krokler")));
                    front(t.guest(), "Peón De Línea Frontal");
                    lives(t.guest(), 4, true);
                }, t -> t.turn().stream().filter(e -> GameEvent.NORMAL_SUMMON.equals(e.getType()) && "host".equals(e.getPlayerId())).count() < 2),
                // The user's game (2026-11-04): with 1 gem it summoned Zhaglizer (3) to -2, then Bajmut Pútrido (0, tributes 1
                // card of the hand) and Caza Nocturna for free on creatures that had just entered the Retaguardia: its hand ended empty.
                // Zhaglizer at -2 is fine by the user; emptying the hand for a 3 and a keyword is not
                new Puzzle("mano-vacia", "con 1 gema y Zhaglizer (3), Bajmut Pútrido (0, tributa 1 de la mano), Caza Nocturna y Buratán en la mano: Zhaglizer a -2 vale, pero no vaciar la mano con Bajmut (2 cartas por un 3) ni con Caza sin quien pueda atacar", t -> {
                    gems(t, 1);
                    // Later in the game: Marcadores of 4 (going to -2 is allowed while the rival's Marcador is 2 or more)
                    t.host().setResourceCount(4);
                    t.guest().setResourceCount(4);
                    t.host().getHand().addAll(List.of(card("Zhaglizer"), card("Bajmut Pútrido"), card("Caza Nocturna"), card("Buratán")));
                    front(t.guest(), "Peón De Línea Frontal");
                    // The rival holds 2 cards (hidden to the bot): the gems below 0 are theirs to use
                    for (String n : List.of("Caballería Cruzada", "Caballero Férreo")) {
                        CardSlot c = card(n); c.setFaceDown(true); t.guest().getHand().add(c);
                    }
                    lives(t.guest(), 4, true);
                }, t -> t.host().getHand().stream().anyMatch(c -> "Caza Nocturna".equals(c.getCardName()))
                        && t.turn().stream().noneMatch(e -> GameEvent.NORMAL_SUMMON.equals(e.getType()) && "Bajmut Pútrido".equals(e.getCardName()))),
                // The user's games (2026-11-04): it plays its cards while it could attack, before any attack. Nothing here
                // makes a later attack better (summons enter the Retaguardia), so the attacks go first
                new Puzzle("atacar-primero", "dos criaturas propias activas en la Línea frontal, el rival sin bloqueadores y 2 criaturas en la mano: atacar antes de invocar", t -> {
                    gems(t, 3);
                    front(t.host(), "Filo Vengativo");
                    front(t.host(), "Caballero Férreo");
                    t.host().getHand().addAll(List.of(card("Ballesta Montada"), card("Buratán")));
                    back(t.guest(), "Luke El Filo Rojo");
                    lives(t.guest(), 4, true);
                }, t -> t.turn().stream().filter(e -> "host".equals(e.getPlayerId()) && (GameEvent.ATTACK_DECLARED.equals(e.getType())
                        || GameEvent.NORMAL_SUMMON.equals(e.getType()) || GameEvent.SPECIAL_SUMMON.equals(e.getType()) || GameEvent.PLAY.equals(e.getType())))
                        .findFirst().map(e -> GameEvent.ATTACK_DECLARED.equals(e.getType())).orElse(false)),
                // ... unless the card makes the attack better: Krokler by Ritual removes the blocker first
                new Puzzle("remover-antes-de-atacar", "Peón propio (4) contra un Peón rival (4) que lo bloquearía, Buratán en el campo y Krokler en la mano: Ritual de Krokler sobre Buratán (destruye el Peón rival) antes de atacar", t -> {
                    gems(t, 2);
                    front(t.host(), "Peón De Línea Frontal");
                    front(t.host(), "Buratán");
                    t.host().getHand().add(card("Krokler"));
                    front(t.guest(), "Peón De Línea Frontal");
                    lives(t.guest(), 4, true);
                }, t -> {
                    int destroyed = -1, attacked = -1;
                    for (int i = 0; i < t.turn().size(); i++) {
                        GameEvent e = t.turn().get(i);
                        if (destroyed < 0 && GameEvent.DESTROY.equals(e.getType()) && "Peón De Línea Frontal".equals(e.getCardName())) destroyed = i;
                        if (attacked < 0 && GameEvent.ATTACK_DECLARED.equals(e.getType()) && "host".equals(e.getPlayerId())) attacked = i;
                    }
                    return destroyed >= 0 && (attacked < 0 || destroyed < attacked);
                }),
                // The user's games (2026-11-04): the Violet utilities played with no creature of its own on the field, when a hit
                // reveals them from the lives or when they come free (Alimento: an ally destroyed by the opponent)
                new Puzzle("vida-alimento-sin-aliados", "en el turno rival, un golpe revela Alimento De Las Sombras de las vidas del bot, que no tiene criaturas: no jugarla (queda en la mano)", t -> {
                    gems(t, 0);
                    t.room().setCurrentTurnUserId("guest");
                    front(t.guest(), "Filo Vengativo");
                    lives(t.host(), 4, true);
                    CardSlot life = card("Alimento De Las Sombras"); life.setFaceDown(true); t.host().getLifeStack().add(0, life);
                }, t -> t.host().getDiscardPile().stream().noneMatch(c -> "Alimento De Las Sombras".equals(c.getCardName()))),
                new Puzzle("vida-caza-sin-aliados", "en el turno rival, un golpe revela Caza Nocturna de las vidas del bot, que no tiene criaturas: no jugarla (queda en la mano)", t -> {
                    gems(t, 0);
                    t.room().setCurrentTurnUserId("guest");
                    front(t.guest(), "Filo Vengativo");
                    lives(t.host(), 4, true);
                    CardSlot life = card("Caza Nocturna"); life.setFaceDown(true); t.host().getLifeStack().add(0, life);
                }, t -> t.host().getDiscardPile().stream().noneMatch(c -> "Caza Nocturna".equals(c.getCardName()))),
                new Puzzle("alimento-gratis-sin-aliados", "el rival destruye a Buratán (agotado), la única criatura del bot, y Alimento De Las Sombras se ofrece gratis: no jugarla sin aliados", t -> {
                    gems(t, 0);
                    t.room().setCurrentTurnUserId("guest");
                    front(t.host(), "Buratán").setTapped(true);
                    t.host().getHand().add(card("Alimento De Las Sombras"));
                    front(t.guest(), "Filo Vengativo");
                    lives(t.host(), 4, true);
                }, t -> t.host().getDiscardPile().stream().noneMatch(c -> "Alimento De Las Sombras".equals(c.getCardName()))),
                new Puzzle("caza-gratis-sin-aliados", "con -1 gemas Caza Nocturna cuesta 0, pero el bot no tiene criaturas: no jugarla", t -> {
                    gems(t, -1);
                    t.host().getHand().addAll(List.of(card("Caza Nocturna"), card("Alimento De Las Sombras")));
                    front(t.guest(), "Filo Vengativo");
                    lives(t.guest(), 4, true);
                }, t -> t.host().getHand().stream().anyMatch(c -> "Caza Nocturna".equals(c.getCardName()))
                        && t.host().getHand().stream().anyMatch(c -> "Alimento De Las Sombras".equals(c.getCardName()))),
                // The user's games (2026-11-04): attacks the rival surely blocks with a bigger creature, for nothing after
                new Puzzle("ataque-suicida", "Filo Vengativo (6) y Peón (4) contra Luke (9) activo que bloquea y 4 vidas rivales: no atacar con Filo contra Luke si después no sigue la cascada que lo aprovecha", t -> {
                    gems(t, 0);
                    front(t.host(), "Filo Vengativo");
                    front(t.host(), "Peón De Línea Frontal");
                    front(t.guest(), "Luke El Filo Rojo");
                    lives(t.guest(), 4, true);
                }, t -> {
                    boolean filoDied = t.turn().stream().anyMatch(e -> GameEvent.DESTROY.equals(e.getType()) && "Filo Vengativo".equals(e.getCardName()));
                    boolean hit = t.turn().stream().anyMatch(e -> GameEvent.LIFE_DAMAGE.equals(e.getType()) && "host".equals(e.getPlayerId()));
                    return !filoDied || hit;
                }),
                // Supervised duel (2026-11-04, st1-st2): Wyfreik, Ddraig and Fadrake attacked into bigger blockers one after the
                // other and died; Dragón Volcánico (14), the only one that beats every blocker, never attacked
                new Puzzle("cebos-sin-remate", "Wyfreik 5, Ddraig 8, Fadrake 8 y Dragón Volcánico 14 contra Mathaus 10, Falanx 12 y Galafendrax 13 activos: no perder 2 o más criaturas atacando contra bloqueadores mayores sin que Dragón Volcánico ataque", t -> {
                    gems(t, 6);
                    for (String n : List.of("Wyfreik", "Ddraig", "Fadrake", "Dragón Volcánico")) front(t.host(), n);
                    for (String n : List.of("Mathaus", "Falanx", "Galafendrax El Transmutador")) front(t.guest(), n);
                    t.host().getHand().addAll(List.of(card("Flambreik"), card("Huevo De Dragón"), card("Volcán De Dragones")));
                    lives(t.guest(), 4, true);
                }, t -> {
                    long lost = t.turn().stream().filter(e -> GameEvent.DESTROY.equals(e.getType()) && List.of("Wyfreik", "Ddraig", "Fadrake").contains(e.getCardName())).count();
                    boolean bigAttacked = t.turn().stream().anyMatch(e -> GameEvent.ATTACK_DECLARED.equals(e.getType()) && "Dragón Volcánico".equals(e.getCardName()));
                    return lost < 2 || bigAttacked;
                }),
                // Supervised duel (2026-11-04, st3-st4, overExtendPow=3): Hong (8) attacked into Caballero Real (13) and died,
                // for the gem and the card Cueva De Bestias gives when a Violet ally is destroyed; nothing attacked after it
                new Puzzle("hong-contra-muro", "Hong (8) con el rival lleno de criaturas de 13 activas: no atacar con Hong solo por el robo de Cueva De Bestias", t -> {
                    gems(t, 5);
                    for (String n : List.of("Rhogler", "Damon", "Berkelus El Cazarrecompensas Del Bajomundo", "Hong")) front(t.host(), n);
                    back(t.host(), "Cueva De Bestias");
                    t.host().getHand().addAll(List.of(card("Krokler"), card("Zhauz"), card("Zohx"), card("Mawser"), card("Slicker")));
                    lives(t.host(), 3, true);
                    for (String n : List.of("Filo Vengativo", "Caballero Real", "Caballería Cruzada", "Rey Franco", "Rey Franco", "Espada De Los Leones De Fuego")) front(t.guest(), n);
                    for (String n : List.of("Luke El Filo Rojo", "Franchute", "Filo Vengativo", "Peón De Línea Frontal", "Ballesta Montada")) {
                        CardSlot c = card(n); c.setFaceDown(true); t.guest().getHand().add(c);
                    }
                    lives(t.guest(), 6, true);
                }, t -> t.turn().stream().noneMatch(e -> GameEvent.DESTROY.equals(e.getType()) && "Hong".equals(e.getCardName()))),
                // The user's game (2026-11-05): Berkelus sent 10 bestias to the deck's bottom to declare its attack and chose the
                // exhausted Mawser (14), a clash it loses, instead of the rival's lives
                new Puzzle("berkelus-a-las-vidas", "Berkelus (12) entra por Ritual sobre Rhogler (sube al frente), envía 10 bestias de los Tributos y declara ataque: atacar a las vidas, no al Mawser (14) agotado, que gana el choque", t -> {
                    gems(t, 6);
                    t.host().setResourceCount(5);
                    t.guest().setResourceCount(5);
                    front(t.host(), "Rhogler");
                    t.host().getHand().add(card("Berkelus El Cazarrecompensas Del Bajomundo"));
                    for (String n : List.of("Slicker", "Hong", "Damon", "Virdris", "Carnhaus", "Buratán", "Zhaglizer", "Bajmut Pútrido", "Krokler", "Zohx", "Damon"))
                        t.host().getTributeZone().add(card(n));
                    front(t.guest(), "Mawser").setTapped(true);
                    lives(t.guest(), 4, true);
                }, t -> t.turn().stream().noneMatch(e -> GameEvent.DESTROY.equals(e.getType()) && e.getCardName() != null && e.getCardName().startsWith("Berkelus"))),
                // Supervised duel (2026-11-05, st1-st2): 5 dragons against 4 blockers and 3 lives attacked one after the other;
                // Sahiel blocked Dragón Carmesí to save 2 lives and Delta, Ddraig and Fadrake died against bigger blockers
                new Puzzle("cascada-imposible", "Carmesí 14 (se reactiva al destruir), Ddraig 8, Fadrake 8, Delta 6 y Wyphound 3 contra Falanx 12, Sahiel 9, Hez 10, Hez 10 y 3 vidas: no perder más dragones que los enemigos que Carmesí destruye (los cebos que cobra valen)", t -> {
                    gems(t, 7);
                    t.host().setResourceCount(5);
                    t.guest().setResourceCount(5);
                    for (String n : List.of("Ddraig", "Dragón Delta", "Fadrake", "Dragón Carmesí", "Wyphound")) front(t.host(), n);
                    t.host().getHand().addAll(List.of(card("Saildreik"), card("Huevo De Dragón"), card("Flamagón"), card("Dragón Carmesí"), card("Volcán De Dragones")));
                    for (String n : List.of("Falanx", "Sahiel", "Hez", "Hez")) front(t.guest(), n);
                    for (String n : List.of("Galafendrax El Transmutador", "Paso Interdimensional")) { CardSlot c = card(n); c.setFaceDown(true); t.guest().getHand().add(c); }
                    lives(t.guest(), 3, true);
                }, t -> {
                    long lost = t.turn().stream().filter(e -> GameEvent.DESTROY.equals(e.getType()) && List.of("Ddraig", "Dragón Delta", "Fadrake", "Wyphound").contains(e.getCardName())).count();
                    long killed = t.turn().stream().filter(e -> GameEvent.DESTROY.equals(e.getType()) && List.of("Falanx", "Sahiel", "Hez").contains(e.getCardName())).count();
                    return "host".equals(t.room().getWinnerId()) || lost <= killed;
                }),
                // Same duel: Sahiel blocked Dragón Carmesí to save the lives, Carmesí destroyed it and the bot did not reactivate it
                new Puzzle("carmesi-reactivar", "Dragón Carmesí ataca, el rival (2 vidas) bloquea con Sahiel y Carmesí lo destruye: reactivar a Carmesí (su efecto) para seguir cobrando", t -> {
                    gems(t, 2);
                    for (String n : List.of("Dragón Carmesí", "Ddraig", "Dragón Delta")) front(t.host(), n);
                    for (String n : List.of("Sahiel", "Hez")) front(t.guest(), n);
                    lives(t.guest(), 2, true);
                }, t -> {
                    boolean killed = t.turn().stream().anyMatch(e -> GameEvent.DESTROY.equals(e.getType()) && List.of("Sahiel", "Hez").contains(e.getCardName()));
                    return !killed || t.turn().stream().anyMatch(e -> GameEvent.UNTAP.equals(e.getType()) && "Dragón Carmesí".equals(e.getCardName()));
                }),
                // The user's call (2026-11-05): structures are engines; the bot left them unused
                new Puzzle("volcan-activar", "con Flamagón (dragón nivel 1) en la mesa, Volcán De Dragones y Dragón Delta (Evolución) en la mano y 2 gemas: jugar Volcán antes de evolucionar y activarlo (gana 1 gema y roba 1)", t -> {
                    gems(t, 2);
                    front(t.host(), "Flamagón");
                    t.host().getHand().addAll(List.of(card("Volcán De Dragones"), card("Dragón Delta")));
                    lives(t.guest(), 4, true);
                }, t -> activated(t, "Volcán De Dragones")),
                new Puzzle("cueva-activar", "en el turno rival destruyen a Buratán (aliado violeta) con Cueva De Bestias en la mesa: activarla (gana 1 gema y roba 1, tributa 1)", t -> {
                    gems(t, 0);
                    t.room().setCurrentTurnUserId("guest");
                    front(t.host(), "Buratán").setTapped(true);
                    back(t.host(), "Cueva De Bestias");
                    t.host().getHand().addAll(List.of(card("Damon"), card("Bajmut Pútrido")));
                    front(t.guest(), "Filo Vengativo");
                    lives(t.host(), 4, true);
                }, t -> activated(t, "Cueva De Bestias")),
                // ... and they move every creature up, where all the starter decks' removal reaches
                new Puzzle("no-exponer", "Zhaglizer (8) en la Retaguardia, el rival con Dragón Volcánico (14) activo y la mano llena de remoción por fuerza (Llamarada, Dracoventus, Ddraig): no subirlo a la Línea frontal", t -> {
                    gems(t, 0);
                    t.host().setResourceCount(4);
                    t.guest().setResourceCount(5);
                    back(t.host(), "Zhaglizer");
                    front(t.guest(), "Dragón Volcánico");
                    for (String n : List.of("Llamarada", "Llamarada", "Dracoventus", "Ddraig", "Fadrake")) { CardSlot c = card(n); c.setFaceDown(true); t.guest().getHand().add(c); }
                    lives(t.guest(), 4, true);
                }, t -> t.host().getField().stream().noneMatch(c -> "Zhaglizer".equals(c.getCardName()) && com.drive.drive_manager.simulator.engine.EffectEngine.inFrontLine(c))),
                // The user's call (2026-11-05): clear the rival's table before hitting their lives, unless the hit is near the win
                new Puzzle("limpiar-antes-que-danar", "Caballería Cruzada (8) puede atacar a Fhalid (3) agotado o a las vidas (el rival tiene 6): destruir a Fhalid aunque valga menos que una vida (lejos del letal)", t -> {
                    gems(t, 0);
                    front(t.host(), "Caballería Cruzada");
                    front(t.guest(), "Fhalid").setTapped(true);
                    for (String n : List.of("Falis", "Mihel", "Jhav")) { CardSlot c = card(n); c.setFaceDown(true); t.guest().getHand().add(c); }
                    lives(t.guest(), 6, true);
                }, t -> t.turn().stream().anyMatch(e -> GameEvent.DESTROY.equals(e.getType()) && "Fhalid".equals(e.getCardName()))),
                // ... and take the removal's risk when its own lives are few
                new Puzzle("arriesgar-con-pocas-vidas", "con 2 vidas propias, Zhaglizer (8) en la Retaguardia y el rival con Filo Vengativo (6) listo para atacar y la mano llena de remoción: subir a Zhaglizer para bloquear", t -> {
                    gems(t, 0);
                    t.host().setResourceCount(4);
                    t.guest().setResourceCount(5);
                    lives(t.host(), 2, true);
                    back(t.host(), "Zhaglizer");
                    front(t.guest(), "Filo Vengativo");
                    for (String n : List.of("Llamarada", "Llamarada", "Dracoventus", "Ddraig", "Fadrake")) { CardSlot c = card(n); c.setFaceDown(true); t.guest().getHand().add(c); }
                    lives(t.guest(), 4, true);
                }, t -> t.host().getField().stream().anyMatch(c -> "Zhaglizer".equals(c.getCardName()) && com.drive.drive_manager.simulator.engine.EffectEngine.inFrontLine(c))),
                // The tower (the user's st2 game, 2026-11-05): Ddraig by Evolución on a level 2 dragon costs a gem less and its
                // removal fires the same; the two bodies left wide are two targets for the mirror's removals
                new Puzzle("torre-ddraig", "Dragón Delta (nivel 2) en mesa, Ddraig en mano, 3 gemas; el rival (st2) tiene Galveta (5) y remociones en mano: invocar Ddraig por Evolución sobre Delta (destruye a Galveta) en vez de dejar dos cuerpos", t -> {
                    gems(t, 3);
                    front(t.host(), "Dragón Delta");
                    t.host().getHand().add(card("Ddraig"));
                    front(t.guest(), "Galveta");
                    for (String n : List.of("Ddraig", "Fadrake", "Llamarada", "Dragón Delta")) { CardSlot c = card(n); c.setFaceDown(true); t.guest().getHand().add(c); }
                    lives(t.guest(), 6, true);
                }, t -> t.host().getField().stream().anyMatch(c -> "Ddraig".equals(c.getCardName()) && !c.getMaterials().isEmpty() || "Ddraig".equals(c.getCardName()) && !c.getResources().isEmpty())
                        && t.host().getField().stream().noneMatch(c -> "Dragón Delta".equals(c.getCardName()))),
                // ST1's strength reduction (the user's call, 2026-11-05): reducing a creature to 0 destroys it; reducing one
                // it cannot destroy only pays if a later play finishes it, or to open or win a clash
                new Puzzle("reduccion-remueve", "Mathaus (2 materiales) puede reducir en 3 la fuerza de un enemigo; el rival tiene Carnhaus (3) y Hez (10): reducir a Carnhaus (llega a 0 y se destruye), no a Hez", t -> {
                    gems(t, 0);
                    CardSlot mathaus = front(t.host(), "Mathaus");
                    for (String n : List.of("Fhalid", "Jhav")) mathaus.getMaterials().add(card(n));
                    front(t.guest(), "Carnhaus");
                    front(t.guest(), "Hez");
                    lives(t.guest(), 6, true);
                }, t -> t.guest().getField().stream().noneMatch(c -> "Carnhaus".equals(c.getCardName()))),
                // riskCuts: the rival's Mathaus (2 materials) cuts 3 next turn for sure: Fhalid (3) and Jhav (2) moved up die
                new Puzzle("no-subir-ante-reduccion", "Fhalid (3) y Jhav (2) en la Retaguardia, Falanx (12) al frente; el rival tiene Mathaus con 2 materiales (reduce 3 en su turno): no subir a Fhalid ni a Jhav", t -> {
                    gems(t, 0);
                    front(t.host(), "Falanx");
                    back(t.host(), "Fhalid");
                    back(t.host(), "Jhav");
                    CardSlot m = front(t.guest(), "Mathaus");
                    for (String n : List.of("Eisten", "Flureteus")) m.getMaterials().add(card(n));
                    for (String n : List.of("Arc-xioma", "Bertalum", "Sahiel")) { CardSlot c = card(n); c.setFaceDown(true); t.guest().getHand().add(c); }
                    lives(t.guest(), 6, true);
                }, t -> t.host().getField().stream().noneMatch(c -> ("Fhalid".equals(c.getCardName()) || "Jhav".equals(c.getCardName())) && com.drive.drive_manager.simulator.engine.EffectEngine.inFrontLine(c))),
                // The user's st2 game (2026-11-05): it played Dragón Volcánico from the hand (8 gems with 7) instead of the sweep
                new Puzzle("carmesi-barre", "Dracoventus (Recursos: Wyverno De Pantano, Flambreik, Duregón), 4 Volcán De Dragones, 7 gemas, Dragón Carmesí y Dragón Volcánico en mano; el rival con Dracoventus y Dragón Delta agotados: Carmesí por Evolución y destruir a Dracoventus (nivel 4) antes que ir a las vidas (después, Delta o 2 vidas con Letalidad: ambos valen)", t -> {
                    gems(t, 7);
                    CardSlot draco = front(t.host(), "Dracoventus");
                    for (String n : List.of("Wyverno De Pantano", "Flambreik", "Duregón")) draco.getResources().add(card(n));
                    for (int i = 0; i < 4; i++) back(t.host(), "Volcán De Dragones");
                    t.host().getHand().addAll(List.of(card("Dragón Carmesí"), card("Dragón Volcánico")));
                    for (String n : List.of("Ddraig", "Fadrake", "Galveta", "Wyfreik", "Saildreik", "Dragón Delta")) t.host().getTributeZone().add(card(n));
                    front(t.guest(), "Dracoventus").setTapped(true);
                    front(t.guest(), "Dragón Delta").setTapped(true);
                    for (String n : List.of("Llamarada", "Ddraig", "Galveta")) { CardSlot c = card(n); c.setFaceDown(true); t.guest().getHand().add(c); }
                    lives(t.guest(), 5, true);
                }, t -> t.guest().getField().stream().noneMatch(c -> "Dracoventus".equals(c.getCardName()))),
                new Puzzle("carmesi-ataca", "Dragón Carmesí (14, Recursos: Dracoventus, Wyverno, Flambreik, Duregón) listo; el rival con Dracoventus (11) y Dragón Delta (6) agotados y 5 vidas: destruir a Dracoventus (nivel 4) antes que ir a las vidas (después, Delta o 2 vidas con Letalidad: ambos valen)", t -> {
                    gems(t, 3);
                    CardSlot carmesi = front(t.host(), "Dragón Carmesí");
                    for (String n : List.of("Dracoventus", "Wyverno De Pantano", "Flambreik", "Duregón")) carmesi.getResources().add(card(n));
                    for (String n : List.of("Ddraig", "Fadrake", "Galveta", "Wyfreik", "Saildreik", "Dragón Delta")) t.host().getTributeZone().add(card(n));
                    front(t.guest(), "Dracoventus").setTapped(true);
                    front(t.guest(), "Dragón Delta").setTapped(true);
                    for (String n : List.of("Llamarada", "Ddraig", "Galveta")) { CardSlot c = card(n); c.setFaceDown(true); t.guest().getHand().add(c); }
                    lives(t.guest(), 5, true);
                }, t -> t.guest().getField().stream().noneMatch(c -> "Dracoventus".equals(c.getCardName()))),
                // From the duels (2026-11-05, st4-st1 s13146): Fhalid (3) blocked Espada De Plata (5) with all six lives
                new Puzzle("no-bloquear-con-vidas", "en el turno rival Espada De Plata (5) ataca directo; el bot tiene 6 vidas y a Fhalid (3): no bloquear (un golpe de 5 cuesta menos que la criatura)", t -> {
                    gems(t, 0);
                    t.room().setCurrentTurnUserId("guest");
                    front(t.guest(), "Espada De Plata");
                    front(t.host(), "Fhalid");
                    lives(t.host(), 6, true);
                }, t -> t.host().getField().stream().anyMatch(c -> "Fhalid".equals(c.getCardName()))),
                // From the st2 mirror (2026-11-05, s13079 turn 11): it passed with the win at hand
                new Puzzle("letal-por-evolucion", "rival con 2 vidas y solo Dragón Delta agotado; Flambreik (nv 2) y Wyphound (nv 1) en la Retaguardia, 5 gemas: Fadrake por Evolución sobre Flambreik y Galveta sobre Wyphound entran a la Línea frontal y atacan: ganar", t -> {
                    gems(t, 5);
                    back(t.host(), "Flambreik");
                    back(t.host(), "Wyphound");
                    for (String n : List.of("Huevo De Dragón", "Llamarada", "Fadrake", "Huevo De Dragón", "Dragón Carmesí", "Wyverno De Pantano", "Galveta")) t.host().getHand().add(card(n));
                    lives(t.host(), 4, true);
                    front(t.guest(), "Dragón Delta").setTapped(true);
                    for (String n : List.of("Dragón Volcánico", "Dracoventus", "Wyphound", "Dírumy", "Ddraig")) { CardSlot c = card(n); c.setFaceDown(true); t.guest().getHand().add(c); }
                    t.guest().getLifeStack().clear();
                    for (int i = 0; i < 2; i++) { CardSlot c = card("Peón De Línea Frontal"); c.setFaceDown(true); t.guest().getLifeStack().add(c); }
                }, t -> "host".equals(t.room().getWinnerId())),
                new Puzzle("letal-por-evolucion-vidas-grandes", "lo mismo con vidas del rival de dragones grandes (Dragón Carmesí 14, Dragón Delta 6): los golpes de 5 y 7 pueden Reforzarse; atacar igual (no pasar)", t -> {
                    gems(t, 5);
                    back(t.host(), "Flambreik");
                    back(t.host(), "Wyphound");
                    for (String n : List.of("Huevo De Dragón", "Llamarada", "Fadrake", "Huevo De Dragón", "Dragón Carmesí", "Wyverno De Pantano", "Galveta")) t.host().getHand().add(card(n));
                    lives(t.host(), 4, true);
                    front(t.guest(), "Dragón Delta").setTapped(true);
                    for (String n : List.of("Dragón Volcánico", "Dracoventus", "Wyphound", "Dírumy", "Ddraig")) { CardSlot c = card(n); c.setFaceDown(true); t.guest().getHand().add(c); }
                    t.guest().getLifeStack().clear();
                    for (String n : List.of("Dragón Carmesí", "Dragón Delta")) { CardSlot c = card(n); c.setFaceDown(true); t.guest().getLifeStack().add(c); }
                }, t -> t.turn().stream().anyMatch(e -> com.drive.drive_manager.simulator.engine.GameEvent.ATTACK_DECLARED.equals(e.getType()))),
                // The mirror's removals reach every body of 9 or less (Dracoventus on attack, Ddraig, Llamarada): one tower
                new Puzzle("torre-contra-remocion", "Dragón Delta (6) y Galveta (5) en mesa, Ddraig y Fadrake en mano, 5 gemas; el rival (st2) tiene Dracoventus (11, destruye fuerza 9 o menos al atacar) y remociones en mano: no quedar con tres o más cuerpos medianos (a lo más 2 criaturas)", t -> {
                    gems(t, 5);
                    front(t.host(), "Dragón Delta");
                    front(t.host(), "Galveta");
                    t.host().getHand().addAll(List.of(card("Ddraig"), card("Fadrake")));
                    for (int i = 0; i < 6; i++) t.host().getTributeZone().add(card("Dragón Delta"));
                    front(t.guest(), "Dracoventus");
                    front(t.guest(), "Wyphound");
                    for (int i = 0; i < 6; i++) t.guest().getTributeZone().add(card("Dragón Delta"));
                    for (String n : List.of("Ddraig", "Llamarada", "Fadrake")) { CardSlot c = card(n); c.setFaceDown(true); t.guest().getHand().add(c); }
                    lives(t.guest(), 6, true);
                }, t -> t.host().getField().stream().filter(c -> "creature".equals(c.getCardType())).count() <= 2),
                // deckClock (the user's call, 2026-11-05): only an empty deck at the start of a turn loses; one card holds
                new Puzzle("dracoventus-recarga", "mazo vacío (pierde al inicio de su próximo turno): atacar con Dracoventus y enviar 5 tributos [dragón] al mazo aunque Falanx (12) lo bloquee", t -> {
                    gems(t, 0);
                    t.host().getDeck().clear();
                    for (int i = 0; i < 6; i++) t.host().getTributeZone().add(card("Dragón Delta"));
                    front(t.host(), "Dracoventus");
                    front(t.guest(), "Falanx");
                    front(t.guest(), "Fhalid");
                    lives(t.guest(), 6, true);
                }, t -> !t.host().getDeck().isEmpty()),
                new Puzzle("dracoventus-con-mazo", "con 1 carta en el mazo (aguanta el próximo robo): no perder a Dracoventus contra Falanx (12) solo por recargar el mazo", t -> {
                    gems(t, 0);
                    t.host().getDeck().clear();
                    t.host().getDeck().add(card("Dragón Delta"));
                    for (int i = 0; i < 6; i++) t.host().getTributeZone().add(card("Dragón Delta"));
                    front(t.host(), "Dracoventus");
                    front(t.guest(), "Falanx");
                    front(t.guest(), "Fhalid");
                    lives(t.guest(), 6, true);
                }, t -> t.host().getField().stream().anyMatch(c -> "Dracoventus".equals(c.getCardName()))),
                new Puzzle("aguantar-mazo-rival", "al rival le queda 1 carta (pierde al inicio de su turno siguiente al próximo) y tiene 3 atacantes contra 2 vidas: no atacar con todo y quedar sin bloqueadores", t -> {
                    gems(t, 0);
                    for (String n : List.of("Franchute", "Franchute", "Franchute", "Luke El Filo Rojo")) front(t.host(), n);
                    lives(t.host(), 2, true);
                    for (String n : List.of("Falanx", "Galafendrax El Transmutador", "Mathaus")) front(t.guest(), n);
                    t.guest().getDeck().clear();
                    t.guest().getDeck().add(card("Fhalid"));
                    lives(t.guest(), 3, true);
                }, t -> t.host().getField().stream().filter(c -> "creature".equals(c.getCardType()) && !c.isTapped()).count() >= 2),
                // Mawser (the user's case, 2026-11-05): every clash it wins takes one more creature of level 3 or less
                new Puzzle("mawser-expone", "Mawser (14) y Bajmut Pútrido (3) agotados; Luke (9), Caballería Cruzada (8) y Caballero Umbrío (3) listos: atacar a Bajmut deja al atacante agotado y Mawser se lleva dos en su turno; no exponer a Luke ni a Caballería (cambiar a Umbrío por Bajmut vale)", t -> {
                    gems(t, 0);
                    for (String n : List.of("Luke El Filo Rojo", "Caballería Cruzada", "Caballero Umbrío")) front(t.host(), n);
                    front(t.guest(), "Mawser").setTapped(true);
                    front(t.guest(), "Bajmut Pútrido").setTapped(true);
                    lives(t.guest(), 6, true);
                }, t -> t.host().getField().stream().noneMatch(c -> c.isTapped() && !"Caballero Umbrío".equals(c.getCardName()))),
                // The user's two combats (2026-11-05): the exchanges decide, not who has more creatures
                new Puzzle("intercambio-malo", "6 creaturas de fuerza 6 a 1 contra 4 de fuerza 7 a 4 (rival con 6 vidas): atacar pierde las 4 grandes por 2 golpes; no perder a la de 6", t -> {
                    gems(t, 0);
                    for (int s = 6; s >= 1; s--) front(t.host(), "Peón De Línea Frontal").setStrength(s);
                    for (int s = 7; s >= 4; s--) front(t.guest(), "Peón De Línea Frontal").setStrength(s);
                    lives(t.guest(), 6, true);
                }, t -> t.host().getField().stream().anyMatch(c -> c.getStrength() != null && c.getStrength() == 6)),
                new Puzzle("bloquear-o-perder", "3 criaturas (8, 7, 6) contra 2 (4, 3) y el rival con 2 vidas: atacar con todas; si no bloquea pierde, si bloquea pierde su mesa", t -> {
                    gems(t, 0);
                    for (int s : new int[] {8, 7, 6}) front(t.host(), "Peón De Línea Frontal").setStrength(s);
                    for (int s : new int[] {4, 3}) front(t.guest(), "Peón De Línea Frontal").setStrength(s);
                    // Lives a hit of 6 or more always takes (no Reforzar), as in the user's example
                    t.guest().getLifeStack().clear();
                    for (int i = 0; i < 2; i++) { CardSlot c = card("Peón De Línea Frontal"); c.setFaceDown(true); t.guest().getLifeStack().add(c); }
                }, t -> "host".equals(t.room().getWinnerId()) || t.guest().getField().isEmpty()),
                // From the duels (2026-11-05, st2-st3 s13080, in every batch): Ddraig attacked Bajmut agotado and Rhogler blocked it
                new Puzzle("presa-con-guardia", "Ddraig (8) puede atacar a Bajmut Pútrido (3) agotado, pero Rhogler (11) está activo y lo bloquea: no atacar con Ddraig", t -> {
                    gems(t, 5);
                    front(t.host(), "Ddraig");
                    for (String n : List.of("Dragón Carmesí", "Wyverno De Pantano", "Dracoventus", "Llamarada", "Dragón Delta", "Flamagón", "Saildreik")) t.host().getHand().add(card(n));
                    front(t.guest(), "Bajmut Pútrido").setTapped(true);
                    front(t.guest(), "Carnhaus");
                    front(t.guest(), "Rhogler");
                    for (String n : List.of("Berkelus El Cazarrecompensas Del Bajomundo", "Mawser", "Berkelus El Cazarrecompensas Del Bajomundo")) { CardSlot c = card(n); c.setFaceDown(true); t.guest().getHand().add(c); }
                    lives(t.guest(), 6, true);
                }, t -> t.host().getField().stream().anyMatch(c -> "Ddraig".equals(c.getCardName()))),
                // From a duel (2026-11-05, st4-st1 s13046): nine creatures against five blockers and 2 lives, three turns passed
                new Puzzle("ataque-total", "9 criaturas (13, 13, 10, 10, 10, 5, 3, 2, 1) contra 5 bloqueadores (8, 8, 9, 4, 11) y el rival con 2 vidas: atacar con todas (pasan 4) y ganar", t -> {
                    gems(t, 5);
                    for (String n : List.of("Galafendrax El Transmutador", "Galafendrax El Transmutador", "Mathaus", "Mathaus", "Mathaus", "Flarix", "Fhalid", "Jhav", "Flureteus")) front(t.host(), n);
                    for (String n : List.of("Composición Adimensional", "Portal Al Mundo Espiritual", "Mihel", "Arc-xioma", "Falanx", "Sahiel", "Falis", "Paso Interdimensional")) t.host().getHand().add(card(n));
                    for (String n : List.of("Caballería Cruzada", "Caballería Cruzada", "Luke El Filo Rojo", "Caballero Férreo", "Franchute")) front(t.guest(), n);
                    CardSlot c = card("Caballeros De La Primera Línea"); c.setFaceDown(true); t.guest().getHand().add(c);
                    lives(t.guest(), 2, true);
                }, t -> "host".equals(t.room().getWinnerId())),
                // From a duel (2026-11-05, st3-st4 s13065): Armadura De Plata attacked Krokler agotado (stronger) and died
                new Puzzle("armadura-suicida", "Armadura De Plata (4) contra Krokler (5) agotado y Zohx en la Retaguardia (nadie bloquea): atacar a las vidas o no atacar, nunca a Krokler", t -> {
                    gems(t, 4);
                    lives(t.host(), 3, true);
                    front(t.host(), "Armadura De Plata");
                    for (String n : List.of("Comandante De Las Espadas Dragón", "Rey Franco", "Franchute", "Investidura", "Reino Medieval", "Espada De Los Leones De Fuego")) t.host().getHand().add(card(n));
                    front(t.guest(), "Krokler").setTapped(true);
                    back(t.guest(), "Zohx");
                    for (String n : List.of("Krokler", "Hong", "Zhaglizer", "Cazador De Bestias")) { CardSlot c = card(n); c.setFaceDown(true); t.guest().getHand().add(c); }
                    lives(t.guest(), 6, true);
                }, t -> t.host().getField().stream().anyMatch(c -> "Armadura De Plata".equals(c.getCardName()))),
                new Puzzle("slicker-bloqueado", "no atacar con Slicker (4) si el rival bloquea con Cazador De Bestias (9)", t -> {
                    gems(t, 0);
                    front(t.host(), "Slicker");
                    front(t.guest(), "Cazador De Bestias");
                    lives(t.guest(), 5, true);
                }, t -> t.host().getField().stream().anyMatch(c -> "Slicker".equals(c.getCardName()))),
                new Puzzle("llamarada", "jugar Llamarada para destruir a Cazador De Bestias (9), con gemas de sobra", t -> {
                    gems(t, 6);
                    front(t.host(), "Peón De Línea Frontal");
                    t.host().getHand().add(card("Llamarada"));
                    front(t.guest(), "Cazador De Bestias");
                    lives(t.guest(), 4, true);
                }, t -> t.guest().getField().stream().noneMatch(c -> "Cazador De Bestias".equals(c.getCardName()))),
                new Puzzle("busqueda", "al buscar con Caballero Umbrío, tomar una carta jugable pronto, no Rey Franco (7) ni Caballero Real (6)", t -> {
                    gems(t, 2);
                    t.host().setResourceCount(1);
                    t.host().getHand().add(card("Caballero Umbrío"));
                    t.host().getDeck().clear();
                    for (String n : List.of("Rey Franco", "Caballero Real", "Peón De Línea Frontal", "Espada De Primera Línea", "Peón De Línea Frontal", "Rey Franco",
                            "Caballería Cruzada", "Armadura De Plata", "Ballesta Montada", "Peón De Línea Frontal", "Espada De Primera Línea", "Caballero Férreo", "Franchute", "Luke El Filo Rojo")) {
                        CardSlot c = card(n);
                        c.setFaceDown(true);
                        t.host().getDeck().add(c);
                    }
                }, t -> t.turn().stream().filter(e -> GameEvent.ADD_TO_HAND.equals(e.getType()) && "host".equals(e.getPlayerId()))
                        .anyMatch(e -> !List.of("Rey Franco", "Caballero Real").contains(e.getCardName()))),
                new Puzzle("ascenso", "invocar Peón y ascenderlo a Caballero Férreo, no invocar Férreo directo", t -> {
                    gems(t, 2);
                    front(t.host(), "Peón De Línea Frontal");
                    t.host().getHand().addAll(List.of(card("Peón De Línea Frontal"), card("Caballero Férreo")));
                }, t -> t.turn().stream().anyMatch(e -> GameEvent.SPECIAL_SUMMON.equals(e.getType()) && "Caballero Férreo".equals(e.getCardName()))),
                // The user's rule for tributes from the hand (2026-11-04): copies first, then the most crowded type, then playability
                new Puzzle("tributo-copias", "al invocar Espada De Primera Línea (roba 2, tributa 1), tributar una de las 2 Ballesta Montada, no el Caballero Férreo (más chico, único)", t -> {
                    gems(t, 1);
                    t.host().getHand().addAll(List.of(card("Espada De Primera Línea"), card("Ballesta Montada"), card("Ballesta Montada"), card("Caballero Férreo")));
                    deck(t.host(), "Caballería Cruzada", "Comandante De Las Espadas Dragón", "Filo Vengativo", "Luke El Filo Rojo");
                }, t -> tributed(t) && t.host().getTributeZone().stream().allMatch(c -> "Ballesta Montada".equals(c.getCardName()))),
                new Puzzle("tributo-tipo", "al invocar Espada De Primera Línea con la mano llena de criaturas, tributar una criatura, no Investidura (la única utilidad)", t -> {
                    gems(t, 1);
                    t.host().getHand().addAll(List.of(card("Espada De Primera Línea"), card("Caballero Férreo"), card("Filo Vengativo"), card("Luke El Filo Rojo"), card("Investidura")));
                    deck(t.host(), "Caballería Cruzada", "Comandante De Las Espadas Dragón", "Ballesta Montada", "Franchute");
                }, t -> tributed(t) && t.host().getTributeZone().stream().noneMatch(c -> "Investidura".equals(c.getCardName()))),
                // The user's greedy win (2026-11-04): 5 attackers against 3 blockers and 2 lives win by attacking with all
                new Puzzle("otk-cascada", "5 criaturas (6, 6, 4, 4, 3) contra 3 bloqueadores más fuertes (9, 8, 8) y 2 vidas: atacar con todo y ganar, aunque cada bloqueo gane su choque (no llenar la mesa y pasar)", t -> {
                    gems(t, 2);
                    for (String n : List.of("Filo Vengativo", "Filo Vengativo", "Caballero Férreo", "Peón De Línea Frontal", "Espada De Primera Línea")) front(t.host(), n);
                    for (String n : List.of("Luke El Filo Rojo", "Ballesta Montada", "Caballería Cruzada")) front(t.guest(), n);
                    t.host().getHand().addAll(List.of(card("Armadura De Plata"), card("Caballero Férreo")));
                    t.guest().getLifeStack().clear();
                    for (int i = 0; i < 2; i++) { CardSlot l = card("Espada De Primera Línea"); l.setFaceDown(true); t.guest().getLifeStack().add(l); }
                }, t -> "host".equals(t.room().getWinnerId())),
                // With many lives of its own a counterattack costs little: no need to keep a blocker home
                new Puzzle("vidas-de-sobra", "con 8 vidas propias y el rival sin bloqueadores en la Línea frontal (Espada De Plata 5 en su Retaguardia, que no puede matar a Filo agotado): atacar directo con Filo Vengativo (6) aunque no quede bloqueador en casa", t -> {
                    gems(t, 0);
                    lives(t.host(), 8, true);
                    front(t.host(), "Filo Vengativo");
                    back(t.guest(), "Espada De Plata");
                    t.guest().getLifeStack().clear();
                    for (int i = 0; i < 4; i++) { CardSlot l = card("Peón De Línea Frontal"); l.setFaceDown(true); t.guest().getLifeStack().add(l); }
                }, t -> t.turn().stream().anyMatch(e -> GameEvent.ATTACK_DECLARED.equals(e.getType()) && "host".equals(e.getPlayerId()))));
    }

    /** It tributed from the hand this turn, or did not summon Espada De Primera Línea (then the puzzle cannot judge: it passes). */
    private static boolean tributed(Table t) {
        return !t.host().getTributeZone().isEmpty() || t.turn().stream().noneMatch(e -> GameEvent.NORMAL_SUMMON.equals(e.getType()) && "Espada De Primera Línea".equals(e.getCardName()));
    }

    /** An effect of that card of the host's was activated this turn. */
    private static boolean activated(Table t, String source) {
        return t.turn().stream().anyMatch(e -> GameEvent.EFFECT_ACTIVATED.equals(e.getType()) && "host".equals(e.getPlayerId())
                && e.getData() != null && source.equals(e.getData().get("sourceName")));
    }

    /** The player's deck, face down, these cards first (the draws), then the usual fillers. */
    private void deck(PlayerState p, String... top) {
        List<CardSlot> rest = new ArrayList<>(p.getDeck());
        p.getDeck().clear();
        for (String n : top) { CardSlot c = card(n); c.setFaceDown(true); p.getDeck().add(c); }
        p.getDeck().addAll(rest);
    }

    /** Plays the host's turn (the bot) to its end, the guest answering by the heuristic; the first decision's audit. */
    private List<String> play(Table t, BotSettings s, long seed) {
        RoomService service = new RoomService();
        service.adopt(t.room());
        t.room().setRandom(new Random(seed));
        LookaheadBot bot = new LookaheadBot(s);
        bot.seed(seed);
        // bot.puzzle.rival: the rival is a look-ahead bot with these settings (e.g. the Difícil line) instead of the heuristic
        String rivalSettings = System.getProperty("bot.puzzle.rival", "");
        BotPlayer rival = rivalSettings.isEmpty() ? new HeuristicBot() : new LookaheadBot(BotSettings.DEFAULT.with(rivalSettings));
        int start = t.room().getTimeline().size();
        List<String> audit = new ArrayList<>();
        boolean first = true;
        // A puzzle may start in the rival's turn (the bot answers as the defender): the rival plays it, then the bot its own
        boolean hostTurn = "host".equals(t.room().getCurrentTurnUserId());
        for (int step = 0; step < 300 && t.room().getStatus() == GameRoom.RoomStatus.IN_PROGRESS; step++) {
            String seat = t.room().getPrompt() != null ? t.room().getPrompt().getPlayerId() : t.room().getCurrentTurnUserId();
            if ("host".equals(t.room().getCurrentTurnUserId())) hostTurn = true;
            if (hostTurn && !"host".equals(t.room().getCurrentTurnUserId()) && t.room().getPrompt() == null) break;
            BotPlayer side = "host".equals(seat) ? bot : rival;
            boolean traceAll = Boolean.getBoolean("bot.puzzle.trace");
            if ((first || traceAll) && side == bot) {
                if (!first) audit.add("— decisión: " + (t.room().getPrompt() == null ? "jugada" : "elección «" + t.room().getPrompt().getTitle() + "» (" + t.room().getPrompt().getKind() + ")"));
                MoveTrials.AUDIT.set(audit);
            }
            GameAction a = side.next(t.room(), seat);
            if ((first || traceAll) && side == bot) { MoveTrials.AUDIT.remove(); first = false; }
            if (traceAll && side == bot && a != null) audit.add("  → " + a.getType() + " " + a.getPayload());
            if (a == null) break;
            GameStateUpdate u = service.applyAction(t.room().getRoomId(), seat, a, false);
            if (u != null && u.getError() != null) side.refused(a, u.getError());
            else side.played(t.room(), a);
        }
        t.turn().addAll(t.room().getTimeline().subList(Math.min(start, t.room().getTimeline().size()), t.room().getTimeline().size()));
        return audit;
    }

    // ------------------------------------------------------------------ The table

    private Table table(int deckSize) {
        GameRoom room = new GameRoom();
        room.setRoomId("puzzle-" + (++ids));
        PlayerState host = new PlayerState("host", "Bot"), guest = new PlayerState("guest", "Rival");
        room.getPlayers().put("host", host);
        room.getPlayers().put("guest", guest);
        room.setStatus(GameRoom.RoomStatus.IN_PROGRESS);
        room.setCurrentTurnUserId("host");
        room.setCurrentPhase("MAIN");
        room.setFirstPlayerId("host");
        for (PlayerState p : List.of(host, guest)) {
            for (int i = 0; i < 12; i++) { CardSlot c = card(i % 2 == 0 ? "Peón De Línea Frontal" : "Espada De Primera Línea"); c.setFaceDown(true); p.getDeck().add(c); }
            p.setOriginalDeck(PreRoomActions.deepCopySlots(p.getDeck()));
        }
        lives(host, 4, true);
        lives(guest, 4, true);
        return new Table(room, host, guest, new ArrayList<>());
    }

    private static void gems(Table t, int gems) {
        // The first player counts the shared gems negatively
        t.room().setSharedCounter(-gems);
        t.host().setResourceCount(Math.max(gems, 1));
        t.guest().setResourceCount(Math.max(gems, 1));
    }

    private void lives(PlayerState p, int n, boolean faceDown) {
        p.getLifeStack().clear();
        for (int i = 0; i < n; i++) {
            CardSlot c = card(i % 2 == 0 ? "Caballero Real" : "Peón De Línea Frontal");
            c.setFaceDown(faceDown);
            p.getLifeStack().add(c);
        }
    }

    private CardSlot front(PlayerState p, String name) {
        return place(p, name, FRONT);
    }

    private CardSlot back(PlayerState p, String name) {
        return place(p, name, BACK);
    }

    private CardSlot place(PlayerState p, String name, double y) {
        CardSlot c = card(name);
        c.setX(20.0 + 15 * p.getField().size());
        c.setY(y);
        p.getField().add(c);
        return c;
    }

    private CardSlot card(String name) {
        Card meta = META.get(BotSettings.cardKey(name));
        if (meta == null) throw new IllegalArgumentException("No está en los mazos de inicio: " + name);
        CardSlot s = new CardSlot();
        s.setInstanceId("p" + (++ids));
        s.setCardId(meta.getId());
        CardMeta.apply(s, meta.getId(), meta);
        s.setFaceDown(false);
        return s;
    }

    private static Map<String, Card> load() {
        ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        Map<String, Card> out = new HashMap<>();
        for (String deck : BotMatch.DECKS) {
            try (InputStream in = BotPuzzleRun.class.getResourceAsStream("/simulator/" + deck + "-cards.json")) {
                List<Card> cards = json.readValue(in, new TypeReference<>() {});
                cards.forEach(c -> out.putIfAbsent(BotSettings.cardKey(c.getCardName()), c));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return out;
    }
}
