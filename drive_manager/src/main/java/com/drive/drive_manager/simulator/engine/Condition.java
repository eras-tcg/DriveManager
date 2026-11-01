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

    /**
     * "si algún aliado [filtro] es destruido" (sacrifices included: rule 4.0.4.2); {@code byOpponent}: "por el oponente";
     * {@code exceptAttacking}: not while it attacked.
     */
    record AllyDestroyed(CardFilter filter, boolean byOpponent, boolean exceptAttacking) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (!isDestruction(event) || !controller.equals(ownerOf(event))) return false;
            CardSlot destroyed = EffectEngine.eventCard(room, event);
            if (destroyed == null || !"creature".equals(destroyed.getCardType()) || !filter.matches(destroyed)) return false;
            if (byOpponent && (event.getPlayerId() == null || controller.equals(event.getPlayerId()))) return false;
            return !exceptAttacking || !(event.getData() != null && Boolean.TRUE.equals(event.getData().get("asAttacker")));
        }
    }

    /** "si destruyes algún enemigo"; {@code byEffect}: "por tus efectos". */
    record YouDestroyedEnemy(boolean byEffect) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return isDestruction(event) && controller.equals(event.getPlayerId()) && !controller.equals(ownerOf(event))
                    && (!byEffect || destroyedByEffect(event));
        }
    }

    /** "si esta carta destruye algún enemigo": in a clash, or by one of its effects; {@code inClash}: "por enfrentamiento". */
    record ThisDestroyedEnemy(boolean inClash) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            boolean destroyed = isDestruction(event) && event.getData() != null
                    && source.getInstanceId().equals(event.getData().get("byCard")) && !controller.equals(ownerOf(event));
            return destroyed && (!inClash || EffectEngine.CAUSE_BATTLE.equals(event.getData().get("cause")));
        }
    }

    /** "si destruyes alguna criatura [por efecto]": yours or an enemy's. */
    record YouDestroyedCreature(boolean byEffect) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (!isDestruction(event) || !controller.equals(event.getPlayerId()) || (byEffect && !destroyedByEffect(event))) return false;
            CardSlot destroyed = EffectEngine.eventCard(room, event);
            return destroyed != null && "creature".equals(destroyed.getCardType());
        }
    }

    /** "si algún aliado [filtro] inflige daño directo" (by combat). */
    record AllyDealtDirectDamage(CardFilter filter) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (!GameEvent.LIFE_DAMAGE.equals(event.getType()) || !controller.equals(event.getPlayerId()) || event.getData() == null) return false;
            CardSlot attacker = EffectEngine.findInRoom(room, (String) event.getData().get("attackerId"));
            return attacker != null && filter.matches(attacker);
        }
    }

    /** "si algún enemigo declara ataque". */
    record EnemyDeclaresAttack() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return GameEvent.ATTACK_DECLARED.equals(event.getType()) && event.getPlayerId() != null && !controller.equals(event.getPlayerId());
        }
    }

    /** "si activaste algún Efecto Definitivo el turno anterior". */
    record UsedDefinitiveLastTurn() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return room.getUsedThisTurn().contains(EffectEngine.DEFINITIVE_LAST_TURN + controller);
        }
    }

    /** "si algún enemigo fue destruido durante el ataque" (at the end of the attack). */
    record EnemyDestroyedInAttack() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return event.getData() != null && Boolean.TRUE.equals(event.getData().get("enemyDestroyed"));
        }
    }

    /** "En Destrucción por efecto": this card was destroyed by an effect. */
    record DestroyedByEffect() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return destroyedByEffect(event) || (event.getData() != null && EffectEngine.CAUSE_EFFECT.equals(event.getData().get("cause"))
                    && event.getData().get("byCard") != null);
        }
    }

    /** "si esta carta es tributada desde tu mazo [por efecto de una carta filtro]"; {@code by} null: by anything. */
    record TributedFromDeck(CardFilter by) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
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

    /** "si algún aliado [filtro] es invocado" (by any method: normal, special or by effect), other than this card. */
    record AllySummoned(CardFilter filter) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            String type = event.getType();
            boolean summon = GameEvent.NORMAL_SUMMON.equals(type) || GameEvent.SPECIAL_SUMMON.equals(type) || GameEvent.EFFECT_SUMMON.equals(type);
            if (!summon || !controller.equals(event.getPlayerId()) || source.getInstanceId().equals(event.getCardInstanceId())) return false;
            CardSlot summoned = EffectEngine.eventCard(room, event);
            return summoned != null && filter.matches(summoned);
        }
    }

    /** "si algún aliado [filtro] es invocado por [Ascenso / Evolución / Materialización / Ritual]" ({@code kind}: "promotion"…). */
    record YouSpecialSummonedAs(String kind, CardFilter filter) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (!GameEvent.SPECIAL_SUMMON.equals(event.getType()) || !controller.equals(event.getPlayerId())) return false;
            if (event.getData() == null || !kind.equals(event.getData().get("summonType"))) return false;
            CardSlot summoned = EffectEngine.eventCard(room, event);
            return summoned != null && filter.matches(summoned);
        }
    }

    /** "si esta carta tiene algún recurso". */
    record HasResources() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return source != null && !source.getResources().isEmpty();
        }
    }

    /** "si esta carta es tributada como material [por efecto de un aliado nivel N o mayor]"; {@code byLevel} 0: by anything. */
    record TributedAsMaterial(int byLevel) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (!GameEvent.TRIBUTE.equals(event.getType()) || !"materials".equals(event.getFromZone())) return false;
            if (!source.getInstanceId().equals(event.getCardInstanceId())) return false;
            if (byLevel <= 0) return true;
            if (event.getEffectId() == null || event.getData() == null) return false;
            CardSlot by = EffectEngine.findIn(room, controller, "field", (String) event.getData().get("byCard"));
            return by != null && by.getLevel() != null && by.getLevel() >= byLevel;
        }
    }

    /** "si invocas especialmente 1 [filtro]". */
    record YouSpecialSummoned(CardFilter filter) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (!GameEvent.SPECIAL_SUMMON.equals(event.getType()) || !controller.equals(event.getPlayerId())) return false;
            CardSlot summoned = EffectEngine.eventCard(room, event);
            return summoned != null && filter.matches(summoned);
        }
    }

    /**
     * A summon by effect: one an effect orders (rule 9.1.0.2). A special summon an effect only enables (a
     * Materialización by Composición, an Ascenso by Armadura) is not one ("Invocaciones especiales").
     */
    record SummonedByEffect() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return GameEvent.EFFECT_SUMMON.equals(event.getType());
        }
    }

    /** "si algún aliado [filtro] es invocado por efecto". */
    record AllySummonedByEffect(CardFilter filter) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (!GameEvent.EFFECT_SUMMON.equals(event.getType()) || !controller.equals(event.getPlayerId())) return false;
            CardSlot summoned = EffectEngine.eventCard(room, event);
            return summoned != null && filter.matches(summoned);
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

    /** "al inicio del turno"; {@code yours}: of its controller. */
    record TurnStarts(boolean yours) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return GameEvent.TURN_START.equals(event.getType()) && (!yours || controller.equals(event.getPlayerId()));
        }
    }

    /** "al final del turno" (of its controller). */
    record TurnEnds() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return GameEvent.TURN_END.equals(event.getType()) && controller.equals(event.getPlayerId());
        }
    }

    /** "si esta carta entra en combate": it attacks, blocks or is attacked. */
    record EntersCombat() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            String id = source.getInstanceId();
            if (GameEvent.ATTACK_DECLARED.equals(event.getType())) {
                return id.equals(event.getCardInstanceId()) || (event.getData() != null && id.equals(event.getData().get("targetId")));
            }
            return GameEvent.BLOCK.equals(event.getType()) && id.equals(event.getCardInstanceId());
        }
    }

    /** "si esta carta es agotada": exhausted by an effect or to declare an attack. */
    record ThisExhausted() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (!source.getInstanceId().equals(event.getCardInstanceId())) return false;
            return GameEvent.TAP.equals(event.getType()) || GameEvent.BLOCK.equals(event.getType())
                    || (GameEvent.ATTACK_DECLARED.equals(event.getType()) && event.getData() != null && Boolean.TRUE.equals(event.getData().get("exhausted")));
        }
    }

    /** "si un aliado declara ataque o bloqueo, y no controlas otras criaturas". */
    record LoneAllyAttacksOrBlocks() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            boolean attack = GameEvent.ATTACK_DECLARED.equals(event.getType()) || GameEvent.BLOCK.equals(event.getType());
            if (!attack || !controller.equals(event.getPlayerId())) return false;
            return EffectEngine.countOnField(room, controller, CardFilter.creatures()) == 1;
        }
    }

    /** "si alguna carta es removida de tus vidas". */
    record LifeRemoved() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (!"lifeStack".equals(event.getFromZone())) return false;
            Object defender = event.getData() == null ? null : event.getData().get("defenderId");
            return controller.equals(defender) || controller.equals(ownerOf(event));
        }
    }

    /** "si esta carta es removida de tus vidas". */
    record ThisLeftLives() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return "lifeStack".equals(event.getFromZone()) && source.getInstanceId().equals(event.getCardInstanceId());
        }
    }

    /** A step of the combat begins or ends ("al entrar al paso de bloqueo": BLOCK, ENTER). */
    record CombatStep(Combat.Step step, Combat.Phase phase) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return GameEvent.COMBAT_STEP.equals(event.getType()) && event.getData() != null
                    && step.name().equals(event.getData().get("step")) && phase.name().equals(event.getData().get("moment"));
        }
    }

    // ------------------------------------------------------------------ The table

    /** This card is in its controller's Línea frontal. */
    record InFrontLine() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return EffectEngine.inFrontLine(source);
        }
    }

    /** This card is active (not exhausted). */
    record Active() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return !source.isTapped();
        }
    }

    /** It is its controller's turn. */
    record YourTurn() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return controller.equals(room.getCurrentTurnUserId());
        }
    }

    /** No attack is going on. */
    record NoCombat() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return room.getCombat() == null;
        }
    }

    /** "durante el paso de daño directo" of an attack of this card. */
    record AttackingInDamageStep() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return room.getCombat() != null && room.getCombat().getStep() == Combat.Step.DAMAGE
                    && source.getInstanceId().equals(room.getCombat().getAttackerId());
        }
    }

    /** "controlas N o más [filtro]" (the field: Línea frontal and Retaguardia); either bound may be null. */
    record CountOnField(CardFilter filter, Integer atLeast, Integer atMost) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            long count = EffectEngine.countOnField(room, controller, filter);
            return (atLeast == null || count >= atLeast) && (atMost == null || count <= atMost);
        }
    }

    /** "tienes N o más [filtro] en tus tributos" (or another zone of yours: "tributeZone", "hand"). */
    record CountInZone(String zone, CardFilter filter, int atLeast) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return EffectEngine.countInZone(room, controller, zone, filter) >= atLeast;
        }
    }

    /** "si tienes N o menos vidas"; {@code opponent}: "si tu oponente tiene N o menos vidas". */
    record LivesAtMost(boolean opponent, int lives) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            String player = opponent ? room.getOpponentId(controller) : controller;
            return player != null && room.getPlayer(player) != null && EffectEngine.livesOf(room, player) <= lives;
        }
    }

    /** "si tienes menos vidas que tu oponente". */
    record FewerLivesThanOpponent() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            String opponent = room.getOpponentId(controller);
            return opponent != null && EffectEngine.livesOf(room, controller) < EffectEngine.livesOf(room, opponent);
        }
    }

    /** "si tienes N o menos vidas bocabajo". */
    record FaceDownLivesAtMost(int lives) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            PlayerState player = room.getPlayer(controller);
            long faceDown = player == null ? 0 : player.getLifeStack().stream().filter(CardSlot::isFaceDown).count();
            return faceDown <= lives;
        }
    }

    /** "si tienes N o más gemas" (gems can be negative: "menos de 0 gemas" is its opposite with 0). */
    record GemsAtLeast(int gems) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return EffectEngine.gemsOf(room, controller) >= gems;
        }
    }

    /** "no controlas copias de esta carta" (on the field). */
    record NoCopies() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            PlayerState player = room.getPlayer(controller);
            return player != null && player.getField().stream()
                    .noneMatch(c -> c != source && (CardEffects.isNamed(c, source.getCardName()) || CardEffects.isNamed(source, c.getCardName())));
        }
    }

    /** "si no hay enemigos en la línea frontal". */
    record NoEnemiesInFrontLine() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            PlayerState opponent = room.getPlayer(room.getOpponentId(controller));
            return opponent != null && opponent.getField().stream()
                    .noneMatch(c -> "creature".equals(c.getCardType()) && EffectEngine.inFrontLine(c));
        }
    }

    // ------------------------------------------------------------------ Shared

    // ------------------------------------------------------------------ E1.2, E1.3 and E2 (2026-10-31)

    /** "si algún aliado [filtro] es invocado y no tiene materiales [filtro]". */
    record SummonedWithoutMaterial(CardFilter material) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            CardSlot summoned = event == null ? null : EffectEngine.eventCard(room, event);
            return summoned != null && summoned.getMaterials().stream().noneMatch(material::matches);
        }
    }

    /** "En Invocación especial": this card was summoned by a special summon. */
    record SpecialSummoned() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return event != null && GameEvent.SPECIAL_SUMMON.equals(event.getType());
        }
    }

    /** "si esta carta activa [habilidad]" (Agrupar): it used that ability now. */
    record ThisUsedKeyword(String keyword) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return event != null && GameEvent.GAIN.equals(event.getType()) && source.getInstanceId().equals(event.getCardInstanceId())
                    && event.getData() != null && keyword.equals(event.getData().get("keyword"));
        }
    }

    /** "si las criaturas oponentes suman al menos N más en nivel que tus aliados". */
    record OpponentLevelsExceed(int by) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return EffectEngine.levelsOnField(room, room.getOpponentId(controller), CardFilter.creatures())
                    >= EffectEngine.levelsOnField(room, controller, CardFilter.creatures()) + by;
        }
    }

    /** "si un aliado [filtro] declara ataque". */
    record AllyDeclaresAttack(CardFilter filter) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (event == null || !GameEvent.ATTACK_DECLARED.equals(event.getType()) || !controller.equals(event.getPlayerId())) return false;
            CardSlot attacker = EffectEngine.eventCard(room, event);
            return attacker != null && filter.matches(attacker);
        }
    }

    /** "si, en nivel, tus aliados suman menos que los del oponente". */
    record LevelsBelowOpponent() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return EffectEngine.levelsOnField(room, controller, CardFilter.creatures())
                    < EffectEngine.levelsOnField(room, room.getOpponentId(controller), CardFilter.creatures());
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

    /** "al final del turno" of any turn (not only its controller's: "Todos Los Turnos", "Turno Oponente"). */
    record AnyTurnEnds() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return event != null && GameEvent.TURN_END.equals(event.getType());
        }
    }

    /** "si hay al menos N [filtro] en el campo" (both players'). */
    record CountOnBothFields(CardFilter filter, int atLeast) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            long n = room.getPlayers().values().stream().flatMap(p -> p.getField().stream()).filter(filter::matches).count();
            return n >= atLeast;
        }
    }

    /** "En Destrucción por enfrentamiento": this card was destroyed in a clash. */
    record DestroyedInClash() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return event != null && event.getData() != null && EffectEngine.CAUSE_BATTLE.equals(event.getData().get("cause"));
        }
    }

    /** "En Destrucción por efecto aliado": destroyed by an effect of its owner's. */
    record DestroyedByAllyEffect() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return event != null && controller.equals(event.getPlayerId()) && new DestroyedByEffect().test(room, event, source, controller);
        }
    }

    /** "si esta carta fue enviada a tu descarte" (destroyed: it went to the Descarte, not the Tributos). */
    record ThisWentToDiscard() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return event != null && "discardPile".equals(event.getToZone());
        }
    }

    /** "si esta carta tenía al menos 1 material" (when it was destroyed). */
    record HadMaterials() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return event != null && event.getData() != null && Boolean.TRUE.equals(event.getData().get("hadMaterials"));
        }
    }

    /** "si algún aliado es enviado al descarte" (from the field). */
    record AllySentToDiscard() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (event == null || !"field".equals(event.getFromZone()) || !"discardPile".equals(event.getToZone())) return false;
            if (!controller.equals(ownerOf(event))) return false;
            CardSlot card = EffectEngine.eventCard(room, event);
            return card != null && "creature".equals(card.getCardType());
        }
    }

    /** "si esta carta es enviada a tu descarte como material". */
    record ThisSentToDiscardAsMaterial() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return event != null && source.getInstanceId().equals(event.getCardInstanceId()) && "materials".equals(event.getFromZone())
                    && "discardPile".equals(event.getToZone());
        }
    }

    /** "si juegas alguna [filtro]" (an utility or structure, from the hand or by an effect). */
    record YouPlayed(CardFilter filter) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (event == null || !GameEvent.PLAY.equals(event.getType()) || !controller.equals(event.getPlayerId())) return false;
            CardSlot card = EffectEngine.eventCard(room, event);
            return card != null && filter.matches(card);
        }
    }

    /** "si recibes daño directo [por el oponente]" (by an attack or an effect). */
    record YouReceivedDirectDamage() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (event == null || !GameEvent.LIFE_DAMAGE.equals(event.getType()) || event.getData() == null) return false;
            return controller.equals(event.getData().get("defenderId"));
        }
    }

    /** "si esta carta sube a la línea frontal". */
    record ThisMovedToFront() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return event != null && GameEvent.MOVE.equals(event.getType()) && "frontLine".equals(event.getToZone())
                    && source.getInstanceId().equals(event.getCardInstanceId());
        }
    }

    /** "si alguna criatura es destruida o invocada especialmente" (either player's). */
    record CreatureDestroyedOrSpecialSummoned() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (event == null) return false;
            if (GameEvent.SPECIAL_SUMMON.equals(event.getType())) return true;
            if (!isDestruction(event)) return false;
            CardSlot card = EffectEngine.eventCard(room, event);
            return card != null && "creature".equals(card.getCardType()) && !"lifeStack".equals(event.getFromZone());
        }
    }

    /** "durante un combate": an attack is going on. */
    record DuringCombat() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return room.getCombat() != null;
        }
    }

    /** "si esta carta es ascendida": it was the summoner of an Ascenso (it went to the Tributos for it). */
    record ThisAscended() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return event != null && GameEvent.TRIBUTE.equals(event.getType()) && source.getInstanceId().equals(event.getCardInstanceId())
                    && event.getData() != null && "promotion".equals(event.getData().get("cause"));
        }
    }

    /**
     * "si esta carta enfrenta un enemigo [filtro] [como atacante]": the clash step of an attack where it attacks or blocks begins (before the
     * clash itself, so what it does happens before the strengths are compared).
     */
    record ThisClashes(CardFilter enemy, boolean asAttacker) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (event == null || !new CombatStep(Combat.Step.CLASH, Combat.Phase.ENTER).test(room, event, source, controller)) return false;
            Combat c = room.getCombat();
            if (c == null || c.getBlockerId() == null) return false;
            String id = source.getInstanceId();
            String other = id.equals(c.getAttackerId()) ? c.getBlockerId() : !asAttacker && id.equals(c.getBlockerId()) ? c.getAttackerId() : null;
            if (other == null) return false;
            CardSlot card = EffectEngine.findInRoom(room, other);
            return card != null && enemy.matches(card);
        }
    }

    /** "si algún aliado es destruido por tus efectos". */
    record AllyDestroyedByYourEffect() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return isDestruction(event) && controller.equals(ownerOf(event)) && controller.equals(event.getPlayerId()) && destroyedByEffect(event);
        }
    }

    /** "si algún aliado fue destruido por el oponente el turno anterior". */
    record AllyDestroyedByOpponentLastTurn() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return room.getUsedThisTurn().contains(EffectEngine.ALLY_DESTROYED_LAST_TURN + controller);
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

    /** "si esta carta es objetivo de un efecto". */
    record ThisTargeted() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return event != null && GameEvent.TARGET.equals(event.getType()) && event.getData() != null
                    && event.getData().get("targetIds") instanceof List<?> ids && ids.contains(source.getInstanceId());
        }
    }

    /** "si esta carta es declarada bloqueador". */
    record ThisDeclaredBlocker() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return event != null && GameEvent.BLOCK.equals(event.getType()) && source.getInstanceId().equals(event.getCardInstanceId());
        }
    }

    /** "un aliado sea objetivo de un efecto". */
    record AllyTargeted() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (event == null || !GameEvent.TARGET.equals(event.getType()) || event.getData() == null) return false;
            if (!(event.getData().get("targetIds") instanceof List<?> ids)) return false;
            PlayerState player = room.getPlayer(controller);
            return player != null && ids.stream().anyMatch(id -> EffectEngine.find(player.getField(), (String) id) != null);
        }
    }

    /** "si algún aliado destruye un enemigo por enfrentamiento". */
    record AllyDestroyedEnemyInClash() implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            return isDestruction(event) && controller.equals(event.getPlayerId()) && !controller.equals(ownerOf(event))
                    && event.getData() != null && EffectEngine.CAUSE_BATTLE.equals(event.getData().get("cause"));
        }
    }

    /** "si fueron removidos [filtro] de los materiales de algún aliado por tus efectos". */
    record MaterialRemovedByYou(CardFilter filter) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (event == null || !"materials".equals(event.getFromZone()) || event.getEffectId() == null || !controller.equals(event.getPlayerId())) return false;
            CardSlot card = EffectEngine.eventCard(room, event);
            return card != null && filter.matches(card);
        }
    }

    /** "si algún enemigo es destruido por tus [utilidades / filtro]": by an effect of one of your cards that matches. */
    record EnemyDestroyedByYourCard(CardFilter by) implements Condition {
        public boolean test(GameRoom room, GameEvent event, CardSlot source, String controller) {
            if (!isDestruction(event) || !controller.equals(event.getPlayerId()) || controller.equals(ownerOf(event))) return false;
            Object byId = event.getData() == null ? null : event.getData().get("byCard");
            CardSlot card = byId == null ? null : EffectEngine.findInRoom(room, (String) byId);
            return card != null && by.matches(card);
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
