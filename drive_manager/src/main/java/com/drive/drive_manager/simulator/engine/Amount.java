package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.GameRoom;

/**
 * A number an effect reads from the game ("reduciendo su coste en X, donde X es …", "gana fuerza 1 por cada aliado
 * [legionario]"), as data: a closed vocabulary of records, like {@link Condition}.
 */
public sealed interface Amount {

    int of(GameRoom room, String controller);

    /** How many cards the last action moved ("por cada material tributado"): the effect's own, read by EngineContext. */
    record LastCount() implements Amount {
        public int of(GameRoom room, String controller) {
            return 0;
        }
    }

    /** "esta carta infringe daño directo": the strength of the card acting, read by EngineContext. */
    record SourceStrength() implements Amount {
        public int of(GameRoom room, String controller) {
            return 0;
        }
    }

    /** The level of the ally chosen before, read by EngineContext. */
    record SubjectLevel() implements Amount {
        public int of(GameRoom room, String controller) {
            return 0;
        }
    }

    /** "la cantidad de aliados utilizados para invocar esta carta", read by EngineContext. */
    record UsedToSummon() implements Amount {
        public int of(GameRoom room, String controller) {
            return 0;
        }
    }

    /** How many cards of your hand are over that number. */
    record HandOver(int keep) implements Amount {
        public int of(GameRoom room, String controller) {
            return Math.max(0, room.getPlayer(controller).getHand().size() - keep);
        }
    }

    /** "en el nivel de dicho aliado": the card of the triggering event, read by EngineContext. */
    record TriggerSourceLevel() implements Amount {
        public int of(GameRoom room, String controller) {
            return 0;
        }
    }

    /** How many cards on both fields match. */
    record OnBothFields(CardFilter filter) implements Amount {
        public int of(GameRoom room, String controller) {
            return (int) room.getPlayers().values().stream().flatMap(p -> p.getField().stream()).filter(filter::matches).count();
        }
    }

    /** How many lives a player has; {@code faceDownOnly}: "bocabajo". Without an opponent yet, more than any card asks. */
    record Lives(boolean opponent, boolean faceDownOnly) implements Amount {
        public int of(GameRoom room, String controller) {
            String id = opponent ? room.getOpponentId(controller) : controller;
            com.drive.drive_manager.simulator.PlayerState p = id == null ? null : room.getPlayer(id);
            if (p == null) return Integer.MAX_VALUE;
            return faceDownOnly ? (int) p.getLifeStack().stream().filter(com.drive.drive_manager.simulator.CardSlot::isFaceDown).count() : p.getLifeStack().size();
        }
    }

    /** The sum of the levels of the opponent's creatures. */
    record OpponentLevels() implements Amount {
        public int of(GameRoom room, String controller) {
            return (int) EffectEngine.levelsOnField(room, room.getOpponentId(controller), CardFilter.creatures());
        }
    }

    /** The opponent's creatures in the Línea frontal. Without an opponent yet, one (so "no hay enemigos" does not hold). */
    record EnemiesInFrontLine() implements Amount {
        public int of(GameRoom room, String controller) {
            com.drive.drive_manager.simulator.PlayerState p = room.getPlayer(room.getOpponentId(controller));
            if (p == null) return 1;
            return (int) p.getField().stream().filter(c -> "creature".equals(c.getCardType()) && EffectEngine.inFrontLine(c)).count();
        }
    }

    /** Your gems as they are: below 0 when you owe them ("si tienes menos de 0 gemas"). */
    record SignedGems() implements Amount {
        public int of(GameRoom room, String controller) {
            return EffectEngine.gemsOf(room, controller);
        }
    }

    /** A fixed number. */
    record Fixed(int value) implements Amount {
        public int of(GameRoom room, String controller) {
            return value;
        }
    }

    /** How many of your cards on the field match. */
    record OnField(CardFilter filter) implements Amount {
        public int of(GameRoom room, String controller) {
            return (int) EffectEngine.countOnField(room, controller, filter);
        }
    }

    /** How many cards of one of your zones ("tributeZone", "hand") match. */
    record InZone(String zone, CardFilter filter) implements Amount {
        public int of(GameRoom room, String controller) {
            return (int) EffectEngine.countInZone(room, controller, zone, filter);
        }
    }

    /** The sum of the levels of your creatures on the field that match. */
    record LevelsOnField(CardFilter filter) implements Amount {
        public int of(GameRoom room, String controller) {
            return EffectEngine.levelsOnField(room, controller, filter);
        }
    }

    /** {@code base / divisor + plus} ("aumenta en 1 el nivel por cada 2 copias": 1 + copias / 2). */
    record Scaled(Amount base, int divisor, int plus) implements Amount {
        public int of(GameRoom room, String controller) {
            return base.of(room, controller) / divisor + plus;
        }
    }

    /** {@code base × factor} ("gana fuerza 3 por cada copia": copias × 3). */
    record Times(Amount base, int factor) implements Amount {
        public int of(GameRoom room, String controller) {
            return base.of(room, controller) * factor;
        }
    }

    /** One amount while a condition on the table holds, another otherwise ("si tienes 3 o menos vidas: …"). */
    record IfThen(Condition condition, Amount then, Amount otherwise) implements Amount {
        public int of(GameRoom room, String controller) {
            return condition.test(room, null, null, controller) ? then.of(room, controller) : otherwise.of(room, controller);
        }
    }

    /** How many more lives the opponent has than you (0 when you have as many or more): "por cada vida más que tenga tu oponente que tú". */
    record LivesBehind(boolean faceDownOnly) implements Amount {
        public int of(GameRoom room, String controller) {
            var you = room.getPlayer(controller);
            var opponent = room.getPlayer(room.getOpponentId(controller));
            if (you == null || opponent == null) return 0;
            long mine = you.getLifeStack().stream().filter(l -> !faceDownOnly || l.isFaceDown()).count();
            long theirs = opponent.getLifeStack().stream().filter(l -> !faceDownOnly || l.isFaceDown()).count();
            return (int) Math.max(0, theirs - mine);
        }
    }

    /** How many cards of several of your zones ("de tu descarte y tributos") match. */
    record InZones(String zones, CardFilter filter) implements Amount {
        public int of(GameRoom room, String controller) {
            int n = 0;
            for (String z : zones.split(",")) n += (int) EffectEngine.countInZone(room, controller, z.trim(), filter);
            return n;
        }
    }

    /** Your gems now ("el valor de tu marcador de gemas"), 0 when below 0. */
    record Gems() implements Amount {
        public int of(GameRoom room, String controller) {
            return Math.max(0, EffectEngine.gemsOf(room, controller));
        }
    }

    static Amount fixed(int value) {
        return new Fixed(value);
    }
}
