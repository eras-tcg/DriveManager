package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.FloatingEffect;
import com.drive.drive_manager.simulator.GameLogEntry;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectScript.Limit;
import com.drive.drive_manager.simulator.engine.EffectStep.*;
import com.drive.drive_manager.simulator.engine.effects.model.Action;
import com.drive.drive_manager.simulator.engine.effects.model.SpecialRule;
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler;
import com.drive.drive_manager.simulator.engine.effects.runtime.Actions;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.drive.drive_manager.simulator.engine.EffectEngine.*;
import static com.drive.drive_manager.simulator.engine.Resolution.*;
import static com.drive.drive_manager.simulator.engine.StepHelpers.*;
import static com.drive.drive_manager.simulator.engine.Summons.*;
import static com.drive.drive_manager.simulator.engine.SpecialRequirements.*;
import static com.drive.drive_manager.simulator.engine.SpecialSummons.*;
import static com.drive.drive_manager.simulator.engine.Rituals.*;
import static com.drive.drive_manager.simulator.engine.Removals.*;
import static com.drive.drive_manager.simulator.engine.TurnRules.*;
import static com.drive.drive_manager.simulator.engine.CombatRules.*;
import static com.drive.drive_manager.simulator.engine.Chain.*;
import static com.drive.drive_manager.simulator.engine.Answers.*;

/** Attacks, blocks, and what acts on chosen enemies. Each step: whether it can be done, carrying it out, and applying the player's choice. */
final class CombatSteps {

    private CombatSteps() {}

    static boolean possibleStep(GameRoom room, PendingEffect effect, EffectStep step) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        CardSlot onField = find(player.getField(), effect.getSourceInstanceId());
        return switch (step) {
            case TributesToDeckBottomForKeyword d -> player.getTributeZone().stream().filter(d.filter()::matches).count() >= d.count();
            case ChooseWeakestEnemy weakest -> !enemyTargets(room, effect, weakest.filter()).isEmpty();
            case PickAllyUpToSacrificedLevel pick -> allies(player).stream().anyMatch(EffectEngine::inFrontLine);
            case ExtraHit ignored -> room.getCombat() != null && room.getCombat().getStep() == Combat.Step.DAMAGE;
            case ChooseEnemiesLevelSum s -> levelSumPossible(enemyTargets(room, effect, CardFilter.creatures()), s.sum());
            default -> true;
        };
    }

    static boolean runStep(GameRoom room, PendingEffect effect, EffectStep step) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        String userId = player.getUserId();

        switch (step) {
            case ReturnSubjectAtAttackEnd ignored -> {
                CardSlot ally = subject(room, effect);
                if (ally == null || room.getCombat() == null) return false;
                room.getUsedThisTurn().add(RETURN_AT_ATTACK_END + userId + "|" + ally.getInstanceId());
                return false;
            }
            case DeclareBlockSelf ignored -> {
                CardSlot self = subject(room, effect);
                Combat c = room.getCombat();
                if (self == null || c == null || c.getBlockerId() != null || !player.getUserId().equals(c.getDefenderId())) return false;
                c.setBlockerId(self.getInstanceId());
                record(room, GameEvent.BLOCK, userId, self, null, null, effect, null, name(self) + " declara bloqueo (" + effect.getSourceName() + ")");
                return false;
            }
            case GroupUp g -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                List<CardSlot> valid = self == null ? List.of() : allies(player).stream().filter(c -> c != self && !c.isTapped()
                        && !keywords(c).contains(CANNOT_TAP)).toList();
                if (valid.isEmpty() || room.getCombat() == null) return false;
                pick(room, effect, "Agrupar: puedes agotar hasta " + g.allies() + " " + plural(g.allies(), "aliado", "aliados") + "; " + name(self)
                        + " gana su fuerza durante el ataque e inflige daño 1 vez extra por cada uno.", "Agotar", player.getField(), ids(valid), Math.min(g.allies(), valid.size()));
                room.getPrompt().setMin(0);
                return true;
            }
            case ChooseEnemiesLevelSum s -> {
                List<CardSlot> valid = enemyTargets(room, effect, CardFilter.creatures());
                if (!levelSumPossible(valid, s.sum())) return false;
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                pick(room, effect, "Elige enemigos que sumen " + s.sum() + " en nivel.", "Objetivo", frontLine(opponent.getField()), ids(valid), valid.size());
                room.getPrompt().setMin(1);
                return true;
            }
            case AlliesGain gain -> {
                // On the table: the allies that enter later get it too (refreshContinuous gives it)
                String until = "nextTurn".equals(gain.until()) ? "hasta el final del turno oponente" : "hasta el final del turno";
                String except = gain.others() ? effect.getSourceInstanceId() : null;
                room.getFieldGrants().add(new FieldGrant(userId, gain.filter(), gain.keyword(), gain.until(), effect.getSourceName(), except));
                refreshContinuous(room);
                int n = (int) allies(player).stream().filter(c -> gain.filter().matches(c) && !c.getInstanceId().equals(except)).count();
                record(room, GameEvent.GAIN, userId, null, null, null, effect, Map.of("keyword", gain.keyword(), "count", n),
                        player.getUsername() + ": sus " + (gain.others() ? "otros " : "") + "aliados ganan " + gain.keyword() + " " + until + ", también los que entren después ("
                                + effect.getSourceName() + ")");
                return false;
            }
            case PickAllyUpToSacrificedLevel pick -> {
                CardSlot sacrificed = effect.getLastTributed().isEmpty() ? null : findAnywhere(room, userId, effect.getLastTributed().get(0));
                int max = levelWhenRemoved(room, sacrificed) + pick.plus();
                List<CardSlot> valid = allies(player).stream().filter(c -> inFrontLine(c) && c.getLevel() != null && c.getLevel() <= max).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Elige 1 aliado de tu línea frontal nivel " + max + " o menor: declarará ataque.", "Elegido", player.getField(), ids(valid), 1);
                return true;
            }
            case ExtraHit ignored -> {
                Combat c = room.getCombat();
                if (c == null || c.getStep() != Combat.Step.DAMAGE) return false;
                c.setHitsLeft((c.getHitsLeft() == null ? 0 : c.getHitsLeft()) + 1);
                c.setPhase(Combat.Phase.ACT);
                log(room, "El atacante inflige daño 1 vez más (" + effect.getSourceName() + ")", null);
                return false;
            }
            case SkipBlockStep ignored -> {
                if (room.getCombat() != null) {
                    room.getCombat().setSkipBlock(true);
                    log(room, "El ataque salta el paso de bloqueo (" + effect.getSourceName() + ")", null);
                }
                return false;
            }
            case ReduceRepeatedly r -> {
                int total = r.times() < 0 ? effect.getLastCount() : r.times();
                List<CardSlot> valid = enemyTargets(room, effect, CardFilter.creatures());
                if (effect.getRepeatsDone() >= total || valid.isEmpty()) {
                    effect.setRepeatsDone(0);
                    return false;
                }
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                pick(room, effect, "Elige 1 enemigo: reduce en " + r.amount() + " su fuerza" + ("attack".equals(r.until()) ? " hasta el final del ataque" : "")
                                + " (" + (effect.getRepeatsDone() + 1) + " de " + total + "). Puedes no elegir más.", "Reducir",
                        frontLine(opponent.getField()), ids(valid), 1);
                room.getPrompt().setMin(0);
                return true;
            }
            case TributesToDeckBottomForKeyword d -> {
                List<CardSlot> valid = player.getTributeZone().stream().filter(d.filter()::matches).toList();
                if (valid.size() < d.count()) return false;
                pick(room, effect, "Puedes descartar " + d.count() + " tributos " + d.filter().describe() + " para que " + effect.getSourceName() + " gane "
                        + d.keyword() + " 1 hasta el final del ataque (tantas veces como quieras). Elige ninguno para terminar.", "Descartar",
                        player.getTributeZone(), ids(valid), d.count());
                room.getPrompt().setMin(0);
                return true;
            }
            case ExtraCombatHitThenEndTurn ignored -> {
                room.getUsedThisTurn().add(EXTRA_HIT + userId);
                log(room, "La próxima vez que un aliado de " + player.getUsername() + " inflija daño directo por combate este turno, inflige daño 1 vez más",
                        null);
                return false;
            }
            case BounceChosen ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                boolean any = false;
                for (CardSlot target : validTargets(room, effect, opponent)) {
                    any = true;
                    if (keywords(target).contains(UNREMOVABLE)) {
                        log(room, name(target) + " no puede ser removida por efectos oponentes", null);
                        continue;
                    }
                    if (deferRemoval(room, target, opponent, false, CAUSE_EFFECT, userId, effect.getSourceInstanceId(), effect)) continue;
                    removeFromField(opponent, target);
                    opponent.getHand().add(target);
                    record(room, GameEvent.SEND, userId, target, "field", "hand", effect, Map.of("ownerId", opponent.getUserId()),
                            player.getUsername() + " sube a " + name(target) + " a la mano de " + opponent.getUsername() + " (" + effect.getSourceName() + ")");
                }
                effect.setLastDone(any);
                return false;
            }
            case NoUntapChosen noUntap -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                for (String id : effect.getTargetIds()) {
                    CardSlot target = opponent == null ? null : find(opponent.getField(), id);
                    if (target == null) continue;
                    // Counted down at each change of turn: it is 1 during its owner's next turn
                    int turns = opponent.getUserId().equals(room.getCurrentTurnUserId()) ? 3 : 2;
                    if (noUntap.wholeTurn()) {
                        target.setNoUntap(turns);
                        floating(target, effect, "No se puede reactivar durante el próximo turno de " + opponent.getUsername(), "nextTurn", 0, null);
                        log(room, name(target) + " no se podrá reactivar durante el próximo turno de " + opponent.getUsername() + " (" + effect.getSourceName() + ")", null);
                    } else {
                        target.setNoStartUntap(turns);
                        floating(target, effect, "No se reactiva al inicio del próximo turno de " + opponent.getUsername(), "nextTurn", 0, null);
                        log(room, name(target) + " no se reactivará al inicio del próximo turno de " + opponent.getUsername() + " (" + effect.getSourceName() + ")", null);
                    }
                }
                return false;
            }
            case ChooseWeakestEnemy weakest -> {
                List<CardSlot> valid = enemyTargets(room, effect, weakest.filter());
                if (valid.isEmpty()) return false;
                int min = valid.stream().mapToInt(EffectEngine::strength).min().orElse(0);
                List<CardSlot> ties = valid.stream().filter(c -> strength(c) == min).toList();
                if (ties.size() == 1) {
                    effect.getTargetIds().add(ties.get(0).getInstanceId());
                    record(room, GameEvent.TARGET, userId, ties.get(0), null, null, effect, Map.of("targetIds", List.of(ties.get(0).getInstanceId())),
                            player.getUsername() + " elige como objetivo a " + name(ties.get(0)) + " (" + effect.getSourceName() + ")");
                    return false;
                }
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                pick(room, effect, "Varios enemigos " + weakest.filter().describe() + " tienen la menor fuerza (" + min + "): elige 1.",
                        "Objetivo", frontLine(opponent.getField()), ids(ties), 1);
                return true;
            }
            default -> throw new IllegalStateException("Paso de otra familia: " + step);
        }
    }

    static String applyStep(GameRoom room, PendingEffect effect, EffectStep step, PlayerState player, List<String> picks,
                            List<String> order, boolean[] repeat) {
        String userId = player.getUserId();
        switch (step) {
            case GroupUp ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (picks.isEmpty() || self == null) break;
                int gained = 0;
                for (String id : picks) {
                    CardSlot ally = find(player.getField(), id);
                    if (ally == null || ally.isTapped()) return "Ese aliado ya no está activo";
                    ally.setTapped(true);
                    gained += strength(ally);
                    record(room, GameEvent.TAP, userId, ally, null, null, effect, null, player.getUsername() + " agota a " + name(ally) + " (Agrupar)");
                }
                self.setTurnStrength(self.getTurnStrength() + gained);
                // "Letalidad 1 por cada aliado agotado" (designer ruling 2026-10-31)
                String lethality = "Letalidad " + picks.size();
                self.getTurnKeywords().add(lethality);
                if (room.getCombat() != null) {
                    room.getCombat().getUntilEndOfAttack().add(self.getInstanceId() + "|" + STRENGTH_ENTRY + gained);
                    room.getCombat().getUntilEndOfAttack().add(self.getInstanceId() + "|" + lethality);
                }
                String extra = picks.size() == 1 ? "1 vez extra" : picks.size() + " veces extra";
                floating(self, effect, "Gana fuerza " + gained + " hasta el final del ataque (Agrupar)", "attack", gained, null);
                floating(self, effect, "Inflige daño " + extra + " (Agrupar)", "attack", 0, lethality);
                record(room, GameEvent.GAIN, userId, self, null, null, effect, Map.of("keyword", "Agrupar", "strength", gained),
                        name(self) + " agrupa: gana fuerza " + gained + " e inflige daño " + extra + " durante el ataque");
            }
            case ChooseEnemiesLevelSum s -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                List<CardSlot> chosen = picks.stream().map(id -> opponent == null ? null : find(frontLine(opponent.getField()), id)).toList();
                if (chosen.contains(null)) return "Ese enemigo ya no está en la línea frontal";
                if (chosen.stream().mapToInt(c -> c.getLevel() == null ? 0 : c.getLevel()).sum() != s.sum()) return "Sus niveles deben sumar " + s.sum();
                effect.getTargetIds().addAll(picks);
                record(room, GameEvent.TARGET, userId, chosen.get(0), null, null, effect, Map.of("targetIds", picks),
                        player.getUsername() + " elige como objetivo a " + String.join(", ", chosen.stream().map(EffectEngine::name).toList()) + " (" + effect.getSourceName() + ")");
            }
            case PickAllyUpToSacrificedLevel ignored -> {
                if (find(player.getField(), picks.get(0)) == null) return "Ese aliado ya no está en el campo";
                effect.setSubjectId(picks.get(0));
            }
            case ReduceRepeatedly r -> {
                if (picks.isEmpty()) {
                    effect.setRepeatsDone(0);
                    return null;
                }
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                CardSlot target = opponent == null ? null : find(frontLine(opponent.getField()), picks.get(0));
                if (target == null) return "Ese enemigo ya no está en la línea frontal";
                reduceStrength(room, effect, opponent, target, r.amount(), false);
                if ("attack".equals(r.until()) && room.getCombat() != null && find(opponent.getField(), target.getInstanceId()) != null)
                    room.getCombat().getUntilEndOfAttack().add(target.getInstanceId() + "|" + STRENGTH_ENTRY + (-r.amount()));
                effect.setRepeatsDone(effect.getRepeatsDone() + 1);
                repeat[0] = true;
            }
            case TributesToDeckBottomForKeyword d -> {
                if (picks.isEmpty()) return null;
                if (picks.size() != d.count()) return "Elige exactamente " + d.count() + " tributos, o ninguno";
                if (!allIn(player.getTributeZone(), picks)) return "Esas cartas ya no están en tus tributos";
                List<CardSlot> discarded = new ArrayList<>();
                for (String id : picks) {
                    CardSlot card = find(player.getTributeZone(), id);
                    player.getTributeZone().remove(card);
                    card.setFaceDown(true);
                    player.getDeck().add(card);
                    discarded.add(card);
                }
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self != null) {
                    Integer had = keywordLevel(self, d.keyword());
                    String gained = d.keyword() + " " + ((had == null ? 0 : had) + 1);
                    self.getTurnKeywords().add(gained);
                    if (room.getCombat() != null) room.getCombat().getUntilEndOfAttack().add(self.getInstanceId() + "|" + gained);
                    log(room, player.getUsername() + " descarta " + d.count() + " tributos (" + names(discarded).substring(2) + "): " + name(self)
                            + " tiene " + gained + " hasta el final del ataque", room.getEventSeq());
                }
                repeat[0] = true;
            }
            case ChooseWeakestEnemy ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                CardSlot target = opponent == null ? null : find(frontLine(opponent.getField()), picks.get(0));
                if (target == null) return "Ese enemigo ya no está en la línea frontal";
                effect.getTargetIds().add(target.getInstanceId());
                record(room, GameEvent.TARGET, userId, target, null, null, effect, Map.of("targetIds", picks),
                        player.getUsername() + " elige como objetivo a " + name(target) + " (" + effect.getSourceName() + ")");
            }
            default -> {
                return "Este paso no espera ninguna elección";
            }
        }
        return null;
    }
}
