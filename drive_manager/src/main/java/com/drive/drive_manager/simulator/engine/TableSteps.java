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

/** Both players, lives and gems. Each step: whether it can be done, carrying it out, and applying the player's choice. */
final class TableSteps {

    private TableSteps() {}

    static boolean possibleStep(GameRoom room, PendingEffect effect, EffectStep step) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        CardSlot onField = find(player.getField(), effect.getSourceInstanceId());
        return switch (step) {
            case DestroyFaceUpLife ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(effect.getControllerId()));
                yield opponent != null && opponent.getLifeStack().stream().anyMatch(l -> !l.isFaceDown());
            }
            default -> true;
        };
    }

    static boolean runStep(GameRoom room, PendingEffect effect, EffectStep step) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        String userId = player.getUserId();

        switch (step) {
            case ReduceOpponentLivesBy r -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                int amount = r.amount().of(room, userId);
                if (opponent == null || amount <= 0) return false;
                for (CardSlot life : opponent.getLifeStack()) {
                    if (life.isFaceDown()) continue;
                    room.getUsedThisTurn().add(LIFE_REDUCED + life.getInstanceId() + "|" + amount);
                    log(room, "La vida " + name(life) + " de " + opponent.getUsername() + " pierde " + amount + " de fuerza hasta el final del turno ("
                            + effect.getSourceName() + ")", null);
                }
                return false;
            }
            case EachPlayerKeepsOneInFrontLine k -> {
                if (effect.getRepeatsDone() >= 2) {
                    effect.setRepeatsDone(0);
                    for (PlayerState p : room.getPlayers().values()) {
                        for (CardSlot c : new ArrayList<>(frontLine(p.getField()))) {
                            if ("creature".equals(c.getCardType()) && !effect.getTargetIds().contains(c.getInstanceId()))
                                destroy(room, c, p, true, CAUSE_EFFECT, userId, effect.getSourceInstanceId(), effect);
                        }
                    }
                    return false;
                }
                PlayerState who = effect.getRepeatsDone() == 0 ? player : room.getPlayer(room.getOpponentId(userId));
                List<CardSlot> mine = who == null ? List.of() : frontLine(who.getField()).stream().filter(c -> "creature".equals(c.getCardType())).toList();
                if (mine.isEmpty()) {
                    effect.setRepeatsDone(effect.getRepeatsDone() + 1);
                    return run(room, effect, step);
                }
                pick(room, effect, "Elige 1 de tus aliados de tu línea frontal: las otras criaturas de tu línea frontal se sacrifican (" + effect.getSourceName() + ").",
                        "Conservar", who.getField(), ids(mine), 1);
                room.getPrompt().setPlayerId(who.getUserId());
                return true;
            }
            case EachPlayerLowersMarker l -> {
                for (PlayerState p : room.getPlayers().values()) {
                    p.setResourceCount(Math.max(0, p.getResourceCount() - l.count()));
                    log(room, p.getUsername() + " reduce en " + l.count() + " su marcador: queda en " + p.getResourceCount() + " (" + effect.getSourceName() + ")", null);
                }
                return false;
            }
            case LivesFaceDown lives -> {
                List<CardSlot> faceUp = player.getLifeStack().stream().filter(l -> !l.isFaceDown()).toList();
                if (lives.all()) {
                    faceUp.forEach(l -> l.setFaceDown(true));
                    if (lives.shuffle()) Collections.shuffle(player.getLifeStack());
                    log(room, player.getUsername() + " coloca bocabajo sus " + faceUp.size() + " vidas bocarriba" + (lives.shuffle() ? " y baraja sus vidas" : "")
                            + " (" + effect.getSourceName() + ")", null);
                    return false;
                }
                if (faceUp.isEmpty()) return false;
                pick(room, effect, "Coloca bocabajo 1 de tus vidas bocarriba.", "Bocabajo", player.getLifeStack(), ids(faceUp), 1);
                return true;
            }
            case EachPlayerDiscards d -> {
                // First the controller, then the opponent (repeatsDone counts who already did)
                if (effect.getRepeatsDone() >= 2) {
                    effect.setRepeatsDone(0);
                    return false;
                }
                PlayerState who = effect.getRepeatsDone() == 0 ? player : room.getPlayer(room.getOpponentId(userId));
                if (who == null || who.getHand().isEmpty()) {
                    effect.setRepeatsDone(effect.getRepeatsDone() + 1);
                    return run(room, effect, step);
                }
                int n = Math.min(d.count(), who.getHand().size());
                pick(room, effect, "Descarta " + n + " " + plural(n, "carta", "cartas") + " de tu mano, bocabajo (" + effect.getSourceName() + ").",
                        "Descartar", who.getHand(), ids(who.getHand()), n);
                room.getPrompt().setPlayerId(who.getUserId());
                return true;
            }
            case OpponentGainsGemsAsCounter ignored -> {
                String opponentId = room.getOpponentId(userId);
                PlayerState opponent = room.getPlayer(opponentId);
                int gems = opponent == null ? 0 : Math.max(0, gemsOf(room, opponentId));
                if (gems > 0) {
                    room.setSharedCounter(clamp(room.getSharedCounter() + gems * direction(room, opponentId)));
                    record(room, GameEvent.GAIN_GEMS, opponentId, null, null, null, effect, Map.of("count", gems),
                            opponent.getUsername() + " gana " + gemsText(gems) + " (" + effect.getSourceName() + ")");
                }
                return false;
            }
            case GainGemsPerOwnSacrificed ignored -> {
                int n = (int) effect.getTargetIds().stream().filter(id -> find(player.getTributeZone(), id) != null).count();
                if (n > 0) run(room, effect, Compose.gainGems(n));
                return false;
            }
            case DestroyTopLife ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                if (opponent == null || opponent.getLifeStack().isEmpty()) return false;
                CardSlot life = opponent.getLifeStack().remove(0);
                life.setFaceDown(false);
                opponent.getDiscardPile().add(life);
                record(room, GameEvent.DESTROY, userId, life, "lifeStack", "discardPile", effect,
                        Map.of("cause", CAUSE_EFFECT, "ownerId", opponent.getUserId(), "byCard", effect.getSourceInstanceId(), "defenderId", opponent.getUserId()),
                        player.getUsername() + " destruye la vida " + name(life) + " de " + opponent.getUsername() + " (" + effect.getSourceName() + ")");
                if (opponent.getLifeStack().isEmpty()) loseByLives(room, opponent);
                return false;
            }
            case OpponentMovesOneToFront ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                List<CardSlot> back = opponent == null ? List.of() : opponent.getField().stream()
                        .filter(c -> "creature".equals(c.getCardType()) && !inFrontLine(c)).toList();
                if (back.isEmpty()) {
                    log(room, "El oponente no tiene criaturas en su retaguardia (" + effect.getSourceName() + ")", null);
                    return false;
                }
                pick(room, effect, "Elige 1 criatura de tu retaguardia: se mueve a tu línea frontal (" + effect.getSourceName() + ").", "A la línea frontal",
                        opponent.getField(), ids(back), 1);
                room.getPrompt().setPlayerId(opponent.getUserId());
                return true;
            }
            case HandsToBottomDrawOpponentLives ignored -> {
                List<PlayerState> both = new ArrayList<>(room.getPlayers().values());
                for (PlayerState p : both) {
                    List<CardSlot> hand = new ArrayList<>(p.getHand());
                    Collections.shuffle(hand);
                    p.getHand().clear();
                    hand.forEach(c -> c.setFaceDown(true));
                    p.getDeck().addAll(hand);
                    log(room, p.getUsername() + " envía las " + hand.size() + " cartas de su mano al fondo de su mazo", null);
                }
                for (PlayerState p : both) {
                    PlayerState other = room.getPlayer(room.getOpponentId(p.getUserId()));
                    int n = Math.min(other == null ? 0 : other.getLifeStack().size(), p.getDeck().size());
                    for (int i = 0; i < n; i++) drawOne(room, p, effect, false);
                    log(room, p.getUsername() + " roba " + n + " " + plural(n, "carta", "cartas") + " (" + effect.getSourceName() + ")", null);
                }
                return false;
            }
            case DestroyFaceUpLife ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                List<CardSlot> valid = opponent == null ? List.of() : opponent.getLifeStack().stream().filter(l -> !l.isFaceDown()).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Puedes destruir 1 carta bocarriba de las vidas de tu rival, o ninguna.", "Destruir",
                        opponent.getLifeStack(), ids(valid), 1);
                room.getPrompt().setMin(0);
                return true;
            }
            default -> throw new IllegalStateException("Paso de otra familia: " + step);
        }
    }

    static String applyStep(GameRoom room, PendingEffect effect, EffectStep step, PlayerState player, List<String> picks,
                            List<String> order, boolean[] repeat) {
        String userId = player.getUserId();
        switch (step) {
            case EachPlayerKeepsOneInFrontLine ignored -> {
                if (find(player.getField(), picks.get(0)) == null) return "Ese aliado ya no está en el campo";
                effect.getTargetIds().add(picks.get(0));
                effect.setRepeatsDone(effect.getRepeatsDone() + 1);
                repeat[0] = true;
            }
            case LivesFaceDown ignored -> {
                CardSlot life = find(player.getLifeStack(), picks.get(0));
                if (life == null) return "Esa vida ya no está";
                life.setFaceDown(true);
                effect.setLastDone(true);
                log(room, player.getUsername() + " coloca bocabajo la vida " + name(life) + " (" + effect.getSourceName() + ")", null);
            }
            case EachPlayerDiscards ignored -> {
                for (String id : picks) {
                    CardSlot card = find(player.getHand(), id);
                    if (card == null) return "Esa carta ya no está en tu mano";
                    player.getHand().remove(card);
                    card.setFaceDown(true);
                    player.getDiscardPile().add(card);
                }
                log(room, player.getUsername() + " descarta " + picks.size() + " " + plural(picks.size(), "carta", "cartas") + " bocabajo (" + effect.getSourceName() + ")", null);
                effect.setRepeatsDone(effect.getRepeatsDone() + 1);
                repeat[0] = true;
            }
            case OpponentMovesOneToFront ignored -> {
                CardSlot card = find(player.getField(), picks.get(0));
                if (card == null || inFrontLine(card)) return "Esa criatura ya no está en tu retaguardia";
                card.setY(25.0);
                record(room, GameEvent.MOVE, userId, card, null, "frontLine", effect, Map.of("reason", "effect"),
                        player.getUsername() + " mueve " + name(card) + " a su línea frontal (" + effect.getSourceName() + ")");
            }
            case DestroyFaceUpLife ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                for (String id : picks) {
                    CardSlot life = opponent == null ? null : find(opponent.getLifeStack(), id);
                    if (life == null) return "Esa vida ya no está";
                    opponent.getLifeStack().remove(life);
                    opponent.getDiscardPile().add(life);
                    record(room, GameEvent.DESTROY, userId, life, "lifeStack", "discardPile", effect,
                            Map.of("cause", CAUSE_EFFECT, "ownerId", opponent.getUserId(), "byCard", effect.getSourceInstanceId()),
                            player.getUsername() + " destruye la vida " + name(life) + " de " + opponent.getUsername() + " (" + effect.getSourceName() + ")");
                }
            }
            default -> {
                return "Este paso no espera ninguna elección";
            }
        }
        return null;
    }
}
