package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;

import java.util.List;

/**
 * When an effect triggers or applies ("si algún aliado es destruido", "mientras controles 3 o más aliados Rojos"), as data:
 * a closed vocabulary of records, so a card's effects can be stored and read back without code.
 * <p>
 * Trigger conditions read the {@code event}; state conditions only read the table, and are also checked without one
 * (continuous effects, auras, cost modifiers, amounts), when {@code event} is null.
 */
public sealed interface Condition {

    /**
     * @param event      the event that would trigger it; null when only the table is checked
     * @param source     the card with the effect
     * @param controller who controls it
     */
    boolean test(GameRoom room, GameEvent event, CardSlot source, String controller);

    // ------------------------------------------------------------------ Combinations

    /** Every one of them. */
    record All(List<Condition> all) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return all.stream().allMatch(c -> c.test(room, event, source, controller));
        }
    }

    /** The opposite. */
    record Not(Condition condition) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return !condition.test(room, event, source, controller);
        }
    }

    /** No condition: it always applies. */
    record Always() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return true;
        }
    }

    /** Any one of them. */
    record Any(List<Condition> any) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return any.stream().anyMatch(c -> c.test(room, event, source, controller));
        }
    }

    /**
     * "Inmediatamente después de que X": X must be the last thing that happened (rule of the vault "Efectos, cadenas y
     * resolución"); if anything else happened after it in the same resolution, the effect misses its moment.
     */
    record Immediately(Condition condition) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return event != null && condition.test(room, event, source, controller) && EffectEngine.lastThingHappened(room, event);
        }
    }

    static Condition any(Condition... conditions) {
        return new Any(List.of(conditions));
    }

    static Condition all(Condition... conditions) {
        return new All(List.of(conditions));
    }

    static Condition not(Condition condition) {
        return new Not(condition);
    }

    // ------------------------------------------------------------------ Triggers

    /** Whose: yours, your enemy's or either. */
    enum Side { YOU, ENEMY, ANY }

    private static boolean sideIs(Side side, String player, String controller) {
        return switch (side) {
            case ANY -> true;
            case YOU -> controller.equals(player);
            case ENEMY -> player != null && !controller.equals(player);
        };
    }

    /**
     * "si … es destruido" (sacrifices included: rule 4.0.4.2).
     *
     * @param owner          whose card was destroyed
     * @param by             who destroyed it
     * @param filter         which card; null: not asked
     * @param byCard         "por tus [filtro]": the card whose effect did it; null: not asked
     * @param byThis         "esta carta destruye"
     * @param cause          "effect", "battle" or null
     * @param exceptAttacker "excepto como atacante"
     */
    record Destroyed(Side owner, Side by, CardFilter filter, CardFilter byCard, boolean byThis, String cause, boolean exceptAttacker) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (event == null || !isDestruction(event)) return false;
            if (!sideIs(owner, ownerOf(event), controller) || !sideIs(by, event.getPlayerId(), controller)) return false;
            if ("effect".equals(cause) && !destroyedByEffect(event)) return false;
            if ("battle".equals(cause) && !(event.getData() != null && EffectEngine.CAUSE_BATTLE.equals(event.getData().get("cause")))) return false;
            if (byThis && !(event.getData() != null && source.getInstanceId().equals(event.getData().get("byCard")))) return false;
            if (exceptAttacker && event.getData() != null && Boolean.TRUE.equals(event.getData().get("asAttacker"))) return false;
            if (byCard != null) {
                Object id = event.getData() == null ? null : event.getData().get("byCard");
                CardSlot card = id == null ? null : EffectEngine.findInRoom(room, (String) id);
                if (card == null || !byCard.matches(card)) return false;
            }
            if (filter == null) return true;
            CardSlot destroyed = EffectEngine.eventCard(room, event);
            return destroyed != null && filter.matches(destroyed);
        }
    }

    /**
     * "si … es invocado".
     *
     * @param side   whose summon
     * @param other  other than this card
     * @param filter which creature; null: not asked
     * @param how    "effect", "special" or null: any way
     * @param kind   the special summon's method; null: any
     */
    record Summoned(Side side, boolean other, CardFilter filter, String how, String kind) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (event == null) return false;
            String type = event.getType();
            boolean matches = switch (how == null ? "" : how) {
                case "effect" -> GameEvent.EFFECT_SUMMON.equals(type);
                case "special" -> GameEvent.SPECIAL_SUMMON.equals(type);
                default -> GameEvent.NORMAL_SUMMON.equals(type) || GameEvent.SPECIAL_SUMMON.equals(type) || GameEvent.EFFECT_SUMMON.equals(type);
            };
            if (!matches || !sideIs(side, event.getPlayerId(), controller)) return false;
            if (other && source.getInstanceId().equals(event.getCardInstanceId())) return false;
            if (kind != null && (event.getData() == null || !kind.equals(event.getData().get("summonType")))) return false;
            if (filter == null) return true;
            CardSlot summoned = EffectEngine.eventCard(room, event);
            return summoned != null && filter.matches(summoned);
        }
    }

    /**
     * Something happened: the general form of a trigger.
     *
     * @param eventType the event's type; null: any
     * @param actor    who did it
     * @param owner    whose card it was
     * @param thisCard it is about this card
     * @param filter   which card; null: not asked
     * @param from     the zone it left; null: not asked
     * @param to       the zone it reached; null: not asked
     * @param byEffect an effect did it
     * @param data     what the event says, as "key=value"; the value "$this" is this card and "$you" is its controller
     */
    record Happened(String eventType, Side actor, Side owner, boolean thisCard, CardFilter filter, String from, String to, boolean byEffect, List<String> data) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (event == null || (eventType != null && !eventType.equals(event.getType()))) return false;
            if (!sideIs(actor, event.getPlayerId(), controller) || !sideIs(owner, ownerOf(event), controller)) return false;
            if (thisCard && !source.getInstanceId().equals(event.getCardInstanceId())) return false;
            if ((from != null && !from.equals(event.getFromZone())) || (to != null && !to.equals(event.getToZone()))) return false;
            if (byEffect && event.getEffectId() == null) return false;
            for (String pair : data) {
                int at = pair.indexOf('=');
                Object value = event.getData() == null ? null : event.getData().get(pair.substring(0, at));
                String wanted = pair.substring(at + 1);
                if (wanted.equals("$this")) wanted = source.getInstanceId();
                if (wanted.equals("$you")) wanted = controller;
                if (value == null || !wanted.equals(String.valueOf(value))) return false;
            }
            if (filter == null) return true;
            CardSlot card = EffectEngine.eventCard(room, event);
            return card != null && filter.matches(card);
        }
    }

    /** How a {@link Compare} compares. */
    enum Op { AT_LEAST, AT_MOST, LESS, EQUAL }

    /** The table: "controlas 3 o más aliados Rojos", "si tienes 5 o menos vidas", "si tienes menos vidas que tu oponente". */
    record Compare(Amount left, Op op, Amount right) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            int a = left.of(room, controller);
            int b = right.of(room, controller);
            return switch (op) {
                case AT_LEAST -> a >= b;
                case AT_MOST -> a <= b;
                case LESS -> a < b;
                case EQUAL -> a == b;
            };
        }
    }

    /** What can be asked of this card as it is now. */
    enum CardState { IN_FRONT_LINE, ACTIVE, NO_COPIES }

    /** "mientras esté en la línea frontal", "si está activa", "si no controlas copias de esta carta". */
    record ThisIs(CardState state) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return switch (state) {
                case IN_FRONT_LINE -> EffectEngine.inFrontLine(source);
                case ACTIVE -> !source.isTapped();
                case NO_COPIES -> {
                    PlayerState player = room.getPlayer(controller);
                    yield player != null && player.getField().stream()
                            .noneMatch(c -> c != source && (CardEffects.isNamed(c, source.getCardName()) || CardEffects.isNamed(source, c.getCardName())));
                }
            };
        }
    }

    /** What can be asked of the game as it is now. */
    enum GameState { YOUR_TURN, DURING_COMBAT, DEFINITIVE_LAST_TURN, ALLY_DESTROYED_LAST_TURN }

    /** "durante tu turno", "durante un combate", "si activaste algún Efecto Definitivo el turno anterior", "si algún aliado fue destruido por el oponente el turno anterior". */
    record GameIs(GameState state) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return switch (state) {
                case YOUR_TURN -> controller.equals(room.getCurrentTurnUserId());
                case DURING_COMBAT -> room.getCombat() != null;
                case DEFINITIVE_LAST_TURN -> room.getUsedThisTurn().contains(EffectEngine.DEFINITIVE_LAST_TURN + controller);
                case ALLY_DESTROYED_LAST_TURN -> room.getUsedThisTurn().contains(EffectEngine.ALLY_DESTROYED_LAST_TURN + controller);
            };
        }
    }

    /** "En Destrucción por efecto [aliado]": this card was destroyed by an effect (of its owner's). */
    record ByEffect(Side by) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (event == null || !sideIs(by, event.getPlayerId(), controller)) return false;
            return destroyedByEffect(event) || (event.getData() != null && EffectEngine.CAUSE_EFFECT.equals(event.getData().get("cause"))
                    && event.getData().get("byCard") != null);
        }
    }

    /** "si esta carta es objetivo" ({@code thisCard}) or "si un aliado es objetivo". */
    record Targeted(boolean thisCard) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (event == null || !GameEvent.TARGET.equals(event.getType()) || event.getData() == null) return false;
            if (!(event.getData().get("targetIds") instanceof List<?> ids)) return false;
            if (thisCard) return ids.contains(source.getInstanceId());
            PlayerState player = room.getPlayer(controller);
            return player != null && ids.stream().anyMatch(id -> EffectEngine.find(player.getField(), (String) id) != null);
        }
    }

    private static Condition compare(Amount left, Op op, int right) {
        return new Compare(left, op, new Amount.Fixed(right));
    }

    private static Condition happened(String type, boolean thisCard, String... data) {
        return new Happened(type, Side.ANY, Side.ANY, thisCard, null, null, null, false, List.of(data));
    }

    private static Condition moved(String from, String to) {
        return new Happened(null, Side.ANY, Side.ANY, true, null, from, to, false, List.of());
    }

    // The usual printed phrases

    static Condition countOnField(CardFilter filter, Integer atLeast, Integer atMost) {
        Amount count = new Amount.OnField(filter);
        if (atLeast != null && atMost != null) return all(compare(count, Op.AT_LEAST, atLeast), compare(count, Op.AT_MOST, atMost));
        return atLeast != null ? compare(count, Op.AT_LEAST, atLeast) : compare(count, Op.AT_MOST, atMost);
    }

    static Condition countInZone(String zone, CardFilter filter, int atLeast) {
        return compare(new Amount.InZone(zone, filter), Op.AT_LEAST, atLeast);
    }

    static Condition countOnBothFields(CardFilter filter, int atLeast) {
        return compare(new Amount.OnBothFields(filter), Op.AT_LEAST, atLeast);
    }

    static Condition livesAtMost(boolean opponent, int lives) {
        return compare(new Amount.Lives(opponent, false), Op.AT_MOST, lives);
    }

    static Condition fewerLivesThanOpponent() {
        return new Compare(new Amount.Lives(false, false), Op.LESS, new Amount.Lives(true, false));
    }

    static Condition faceDownLivesAtMost(int lives) {
        return compare(new Amount.Lives(false, true), Op.AT_MOST, lives);
    }

    static Condition opponentLevelsExceed(int by) {
        return new Compare(new Amount.OpponentLevels(), Op.AT_LEAST, new Amount.Scaled(new Amount.LevelsOnField(CardFilter.creatures()), 1, by));
    }

    static Condition levelsBelowOpponent() {
        return new Compare(new Amount.LevelsOnField(CardFilter.creatures()), Op.LESS, new Amount.OpponentLevels());
    }

    static Condition gemsAtLeast(int gems) {
        return compare(new Amount.SignedGems(), Op.AT_LEAST, gems);
    }

    static Condition noEnemiesInFrontLine() {
        return compare(new Amount.EnemiesInFrontLine(), Op.EQUAL, 0);
    }

    static Condition inFrontLine() {
        return new ThisIs(CardState.IN_FRONT_LINE);
    }

    static Condition active() {
        return new ThisIs(CardState.ACTIVE);
    }

    static Condition noCopies() {
        return new ThisIs(CardState.NO_COPIES);
    }

    static Condition yourTurn() {
        return new GameIs(GameState.YOUR_TURN);
    }

    static Condition duringCombat() {
        return new GameIs(GameState.DURING_COMBAT);
    }

    static Condition noCombat() {
        return new Not(duringCombat());
    }

    static Condition usedDefinitiveLastTurn() {
        return new GameIs(GameState.DEFINITIVE_LAST_TURN);
    }

    static Condition allyDestroyedByOpponentLastTurn() {
        return new GameIs(GameState.ALLY_DESTROYED_LAST_TURN);
    }

    static Condition destroyedByEffect() {
        return new ByEffect(Side.ANY);
    }

    static Condition destroyedByAllyEffect() {
        return new ByEffect(Side.YOU);
    }

    /** "si esta carta entra en combate": it attacks, blocks or is attacked. */
    static Condition entersCombat() {
        return any(happened(GameEvent.ATTACK_DECLARED, true), happened(GameEvent.ATTACK_DECLARED, false, "targetId=$this"), happened(GameEvent.BLOCK, true));
    }

    /** "si esta carta es agotada": by an effect or to declare an attack or a block. */
    static Condition thisExhausted() {
        return any(happened(GameEvent.TAP, true), happened(GameEvent.BLOCK, true), happened(GameEvent.ATTACK_DECLARED, true, "exhausted=true"));
    }

    /** "si un aliado declara ataque o bloqueo, y no controlas otras criaturas". */
    static Condition loneAllyAttacksOrBlocks() {
        Condition declared = any(new Happened(GameEvent.ATTACK_DECLARED, Side.YOU, Side.ANY, false, null, null, null, false, List.of()),
                new Happened(GameEvent.BLOCK, Side.YOU, Side.ANY, false, null, null, null, false, List.of()));
        return all(declared, compare(new Amount.OnField(CardFilter.creatures()), Op.EQUAL, 1));
    }

    /** "si alguna carta es removida de tus vidas". */
    static Condition lifeRemoved() {
        return any(new Happened(null, Side.ANY, Side.YOU, false, null, "lifeStack", null, false, List.of()),
                new Happened(null, Side.ANY, Side.ANY, false, null, "lifeStack", null, false, List.of("defenderId=$you")));
    }

    /** "si recibes daño directo [por el oponente]" (by an attack or an effect). */
    static Condition youReceivedDirectDamage() {
        return happened(GameEvent.LIFE_DAMAGE, false, "defenderId=$you");
    }

    /** "si alguna criatura es destruida o invocada especialmente" (either player's). */
    static Condition creatureDestroyedOrSpecialSummoned() {
        Condition fromLives = new Happened(null, Side.ANY, Side.ANY, false, null, "lifeStack", null, false, List.of());
        return any(specialSummoned(), all(new Destroyed(Side.ANY, Side.ANY, CardFilter.creatures(), null, false, null, false), new Not(fromLives)));
    }

    static Condition thisTargeted() {
        return new Targeted(true);
    }

    static Condition allyTargeted() {
        return new Targeted(false);
    }

    static Condition turnStarts(boolean yours) {
        return new Happened(GameEvent.TURN_START, yours ? Side.YOU : Side.ANY, Side.ANY, false, null, null, null, false, List.of());
    }

    static Condition turnEnds() {
        return turnEnds(true);
    }

    static Condition turnEnds(boolean yours) {
        return new Happened(GameEvent.TURN_END, yours ? Side.YOU : Side.ANY, Side.ANY, false, null, null, null, false, List.of());
    }

    static Condition combatStep(Combat.Step step, Combat.Phase phase) {
        return happened(GameEvent.COMBAT_STEP, false, "step=" + step.name(), "moment=" + phase.name());
    }

    static Condition thisMoved(String from, String to) {
        return moved(from, to);
    }

    static Condition thisDeclaredBlocker() {
        return happened(GameEvent.BLOCK, true);
    }

    static Condition thisUsedKeyword(String keyword) {
        return happened(GameEvent.GAIN, true, "keyword=" + keyword);
    }

    static Condition thisAscended() {
        return happened(GameEvent.TRIBUTE, true, "cause=promotion");
    }

    static Condition youPlayed(CardFilter filter) {
        return new Happened(GameEvent.PLAY, Side.YOU, Side.ANY, false, filter, null, null, false, List.of());
    }

    static Condition destroyedInClash() {
        return happened(null, false, "cause=" + EffectEngine.CAUSE_BATTLE);
    }

    static Condition hadMaterials() {
        return happened(null, false, "hadMaterials=true");
    }

    static Condition enemyDestroyedInAttack() {
        return happened(null, false, "enemyDestroyed=true");
    }

    static Condition thisWentToDiscard() {
        return new Happened(null, Side.ANY, Side.ANY, false, null, null, "discardPile", false, List.of());
    }

    static Condition materialRemovedByYou(CardFilter filter) {
        return new Happened(null, Side.YOU, Side.ANY, false, filter, "materials", null, true, List.of());
    }

    static Condition allySentToDiscard() {
        return new Happened(null, Side.ANY, Side.YOU, false, CardFilter.creatures(), "field", "discardPile", false, List.of());
    }

    static Condition allyDestroyed(CardFilter filter, boolean byOpponent, boolean exceptAttacking) {
        return new Destroyed(Side.YOU, byOpponent ? Side.ENEMY : Side.ANY, filter.asCreature(), null, false, null, exceptAttacking);
    }

    static Condition youDestroyedEnemy(boolean byEffect) {
        return new Destroyed(Side.ENEMY, Side.YOU, null, null, false, byEffect ? "effect" : null, false);
    }

    static Condition thisDestroyedEnemy(boolean inClash) {
        return new Destroyed(Side.ENEMY, Side.ANY, null, null, true, inClash ? "battle" : null, false);
    }

    static Condition youDestroyedCreature(boolean byEffect) {
        return new Destroyed(Side.ANY, Side.YOU, CardFilter.creatures(), null, false, byEffect ? "effect" : null, false);
    }

    static Condition allyDestroyedByYourEffect() {
        return new Destroyed(Side.YOU, Side.YOU, null, null, false, "effect", false);
    }

    static Condition allyDestroyedEnemyInClash() {
        return new Destroyed(Side.ENEMY, Side.YOU, null, null, false, "battle", false);
    }

    static Condition enemyDestroyedByYourCard(CardFilter by) {
        return new Destroyed(Side.ENEMY, Side.YOU, null, by, false, null, false);
    }

    static Condition allySummoned(CardFilter filter) {
        return new Summoned(Side.YOU, true, filter, null, null);
    }

    static Condition allySummonedByEffect(CardFilter filter) {
        return new Summoned(Side.YOU, false, filter, "effect", null);
    }

    static Condition youSpecialSummoned(CardFilter filter) {
        return new Summoned(Side.YOU, false, filter, "special", null);
    }

    static Condition youSpecialSummonedAs(String kind, CardFilter filter) {
        return new Summoned(Side.YOU, false, filter, "special", kind);
    }

    static Condition summonedByEffect() {
        return new Summoned(Side.ANY, false, null, "effect", null);
    }

    static Condition specialSummoned() {
        return new Summoned(Side.ANY, false, null, "special", null);
    }

    static Condition tributedFromDeck(CardFilter by) {
        return new ThisTributed("deck", null, by);
    }

    static Condition tributedAsMaterial(int byLevel) {
        return new ThisTributed("materials", null, byLevel > 0 ? CardFilter.any().withMinLevel(byLevel) : null);
    }

    static Condition thisLeftLives() {
        return moved("lifeStack", null);
    }

    static Condition thisSentToDiscardAsMaterial() {
        return moved("materials", "discardPile");
    }

    static Condition thisMovedToFront() {
        return moved(null, "frontLine");
    }

    static Condition anyTurnEnds() {
        return turnEnds(false);
    }

    static Condition allyDeclaresAttack(CardFilter filter) {
        return new Happened(GameEvent.ATTACK_DECLARED, Side.YOU, Side.ANY, false, filter, null, null, false, List.of());
    }

    static Condition enemyDeclaresAttack() {
        return new Happened(GameEvent.ATTACK_DECLARED, Side.ENEMY, Side.ANY, false, null, null, null, false, List.of());
    }

    /** "si algún aliado [filtro] inflige daño directo" (by combat). */
    record AllyDealtDirectDamage(CardFilter filter) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (!GameEvent.LIFE_DAMAGE.equals(event.getType()) || !controller.equals(event.getPlayerId()) || event.getData() == null) return false;
            CardSlot attacker = EffectEngine.findInRoom(room, (String) event.getData().get("attackerId"));
            return attacker != null && filter.matches(attacker);
        }
    }

    /**
     * "si esta carta es tributada [desde zona] [, excepto desde zona] [por efecto de 1 carta filtro]": {@code fromZone} and
     * {@code exceptZone} may be null ("tributada" from anywhere); {@code by} null: by anything, otherwise only by an effect of
     * a card that matches.
     */
    record ThisTributed(String fromZone, String exceptZone, CardFilter by) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            // Being sacrificed is being tributed too (designer ruling 2026-10-30, Niobats), and a life lost to direct damage that
            // goes to the Tributos is tributed from the lives (rule 10.1.4, Cofre Mímico)
            String type = event.getType();
            boolean tributed = GameEvent.TRIBUTE.equals(type) || GameEvent.SACRIFICE.equals(type)
                    || (GameEvent.LIFE_DAMAGE.equals(type) && "tributeZone".equals(event.getToZone()));
            if (!tributed || !source.getInstanceId().equals(event.getCardInstanceId())) return false;
            if (fromZone != null && !fromZone.equals(event.getFromZone())) return false;
            if (exceptZone != null && exceptZone.equals(event.getFromZone())) return false;
            if (by == null) return true;
            Object byId = event.getData() == null ? null : event.getData().get("byCard");
            if (event.getEffectId() == null || byId == null) return false;
            CardSlot byCard = EffectEngine.findInRoom(room, (String) byId);
            return byCard != null && by.matches(byCard);
        }
    }

    /** "si reduces [a N o menos] la fuerza de algún enemigo"; {@code enemyStillThere}: it is still on the field with some strength. */
    record YouReducedStrength(Integer toAtMost, boolean enemyStillThere) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (!GameEvent.STRENGTH_REDUCED.equals(event.getType()) || !controller.equals(event.getPlayerId())) return false;
            Object to = event.getData() == null ? null : event.getData().get("to");
            if (toAtMost != null && !(to instanceof Number n && n.intValue() <= toAtMost)) return false;
            if (!enemyStillThere) return true;
            CardSlot target = EffectEngine.findIn(room, ownerOf(event), "field", event.getCardInstanceId());
            return target != null && EffectEngine.strength(target) > 0;
        }
    }

    // ------------------------------------------------------------------ The table

    // ------------------------------------------------------------------ Shared

    // ------------------------------------------------------------------ E1.2, E1.3 and E2 (2026-10-31)

    /** "si algún aliado [filtro] es invocado y no tiene materiales [filtro]". */
    record SummonedWithoutMaterial(CardFilter material) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            CardSlot summoned = event == null ? null : EffectEngine.eventCard(room, event);
            return summoned != null && summoned.getMaterials().stream().noneMatch(material::matches);
        }
    }

    /**
     * "si algún [enemigo / aliado] [filtro] entra a la línea frontal": summoned or placed there, or moved up from the Retaguardia
     * ({@code enemy}: the opponent's).
     */
    record EntersFrontLine(boolean enemy, CardFilter filter) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (event == null || event.getCardInstanceId() == null) return false;
            String type = event.getType();
            boolean entered = GameEvent.MOVE.equals(type) && "frontLine".equals(event.getToZone());
            if (!entered && "field".equals(event.getToZone()) && (GameEvent.NORMAL_SUMMON.equals(type) || GameEvent.EFFECT_SUMMON.equals(type)
                    || GameEvent.SPECIAL_SUMMON.equals(type) || GameEvent.SEND.equals(type))) {
                CardSlot card = EffectEngine.findInRoom(room, event.getCardInstanceId());
                entered = card != null && EffectEngine.inFrontLine(card);
            }
            if (!entered) return false;
            String owner = event.getData() != null && event.getData().get("ownerId") instanceof String o ? o : event.getPlayerId();
            if (enemy == controller.equals(owner)) return false;
            CardSlot card = EffectEngine.findInRoom(room, event.getCardInstanceId());
            return card != null && filter.matches(card);
        }
    }

    /**
     * "si esta carta enfrenta un enemigo [filtro] [como atacante]": the clash step of an attack where it attacks or blocks begins (before the
     * clash itself, so what it does happens before the strengths are compared).
     */
    record ThisClashes(CardFilter enemy, boolean asAttacker) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (event == null || !combatStep(Combat.Step.CLASH, Combat.Phase.ENTER).test(room, event, source, controller)) return false;
            Combat c = room.getCombat();
            if (c == null || c.getBlockerId() == null) return false;
            String id = source.getInstanceId();
            String other = id.equals(c.getAttackerId()) ? c.getBlockerId() : !asAttacker && id.equals(c.getBlockerId()) ? c.getAttackerId() : null;
            if (other == null) return false;
            CardSlot card = EffectEngine.findInRoom(room, other);
            return card != null && enemy.matches(card);
        }
    }

    /** "si aún no has jugado ni invocado otra carta este turno". */
    record NothingElsePlayedThisTurn() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            List<GameEvent> timeline = room.getTimeline();
            for (int i = timeline.size() - 1; i >= 0; i--) {
                GameEvent e = timeline.get(i);
                if (GameEvent.TURN_START.equals(e.getType())) return true;
                if (!controller.equals(e.getPlayerId()) || source.getInstanceId().equals(e.getCardInstanceId())) continue;
                String type = e.getType();
                if (GameEvent.PLAY.equals(type) || GameEvent.NORMAL_SUMMON.equals(type) || GameEvent.SPECIAL_SUMMON.equals(type)
                        || GameEvent.EFFECT_SUMMON.equals(type)) return false;
            }
            return true;
        }
    }

    /**
     * Destroyed (or sacrificed) "por efecto": during an effect, and not as the cost of a summon that effect made (the creatures used
     * for a Ritual are sacrificed by the Ritual's rules, even when an effect summons it).
     */
    private static boolean destroyedByEffect(GameEvent event) {
        return event.getEffectId() != null && !(event.getData() != null && EffectEngine.CAUSE_RITUAL.equals(event.getData().get("cause")));
    }

    private static boolean isDestruction(GameEvent event) {
        return GameEvent.DESTROY.equals(event.getType()) || GameEvent.SACRIFICE.equals(event.getType());
    }

    private static String ownerOf(GameEvent event) {
        return event.getData() == null ? null : (String) event.getData().get("ownerId");
    }
}
