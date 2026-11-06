package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectStep.*;

import java.util.*;

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

/** Taking from the cards looked at, and revealing. Each step: whether it can be done, carrying it out, and applying the player's choice. */
final class LookSteps {

    private LookSteps() {}

    static boolean possibleStep(GameRoom room, PendingEffect effect, EffectStep step) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        CardSlot onField = find(player.getField(), effect.getSourceInstanceId());
        return switch (step) {
            case RevealFromHand r -> assignable(player.getHand().stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId())).toList(),
                    r.filters());
            default -> true;
        };
    }

    static boolean runStep(GameRoom room, PendingEffect effect, EffectStep step) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        String userId = player.getUserId();

        switch (step) {
            case TakeFromLookedEachRestToBottom take -> {
                if (effect.getLooked().isEmpty()) return false;
                SearchHope.apply(room, effect, player, c -> take.filters().stream().anyMatch(f -> f.matches(c)), take.filters().size());
                List<String> valid = effect.getLooked().stream().filter(c -> take.filters().stream().anyMatch(f -> f.matches(c))).map(CardSlot::getInstanceId).toList();
                Prompt prompt = prompt(room, effect, Prompt.Kind.TAKE_REVEALED_REST_BOTTOM, "Toma " + String.join(" y ",
                        take.filters().stream().map(f -> "hasta 1 " + f.describe()).toList()) + " para tu mano. Ordena el resto: irá al fondo de tu mazo.");
                prompt.setCards(new ArrayList<>(effect.getLooked()));
                prompt.setValidIds(new ArrayList<>(valid));
                prompt.setMin(0);
                prompt.setMax(Math.min(take.filters().size(), valid.size()));
                prompt.setReorderRest(true);
                prompt.setChosenLabel("A tu mano");
                room.setPrompt(prompt);
                return true;
            }
            case TakeFromLookedDistinctLevels take -> {
                if (effect.getLooked().isEmpty()) return false;
                SearchHope.apply(room, effect, player, take.filter()::matches, take.max());
                List<String> valid = effect.getLooked().stream().filter(take.filter()::matches).map(CardSlot::getInstanceId).toList();
                Prompt prompt = prompt(room, effect, Prompt.Kind.TAKE_REVEALED_REST_BOTTOM,
                        "Toma hasta " + take.max() + " cartas " + take.filter().describe() + " con distinto nivel para tu mano. Ordena el resto: irá al fondo de tu mazo.");
                prompt.setCards(new ArrayList<>(effect.getLooked()));
                prompt.setValidIds(new ArrayList<>(valid));
                prompt.setMin(0);
                prompt.setMax(Math.min(take.max(), valid.size()));
                prompt.setReorderRest(true);
                prompt.setChosenLabel("A tu mano");
                room.setPrompt(prompt);
                return true;
            }
            case RevealFromHand r -> {
                List<CardSlot> valid = player.getHand().stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId())
                        && r.filters().stream().anyMatch(f -> f.matches(c))).toList();
                if (!assignable(valid, r.filters())) return false;
                pick(room, effect, "Revela de tu mano: " + String.join(" y ", r.filters().stream().map(f -> "1 " + f.describe()).toList()) + ".",
                        "Revelar", player.getHand(), ids(valid), r.filters().size());
                return true;
            }
            case RevealOpponentHandSendOneToBottom reveal -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                if (opponent == null || opponent.getHand().isEmpty()) return false;
                List<CardSlot> hand = new ArrayList<>(opponent.getHand());
                Collections.shuffle(hand, room.getRandom());
                List<CardSlot> revealed = hand.subList(0, Math.min(reveal.count(), hand.size()));
                log(room, opponent.getUsername() + " revela " + String.join(", ", revealed.stream().map(EffectEngine::name).toList()) + " (" + effect.getSourceName() + ")", null);
                pick(room, effect, "Envía 1 de las cartas reveladas al fondo del mazo de tu rival.", "Al fondo", revealed, ids(revealed), 1);
                return true;
            }
            case TakeFromLookedLikeSubject take -> {
                if (effect.getLooked().isEmpty()) return false;
                CardSlot subject = subject(room, effect);
                List<String> classes = subject == null || subject.getClasses() == null ? List.of() : subject.getClasses();
                SearchHope.apply(room, effect, player, c -> c.getClasses() != null && c.getClasses().stream().anyMatch(t -> classes.stream().anyMatch(t::equalsIgnoreCase)), take.max());
                List<String> valid = effect.getLooked().stream()
                        .filter(c -> c.getClasses() != null && c.getClasses().stream().anyMatch(t -> classes.stream().anyMatch(t::equalsIgnoreCase)))
                        .map(CardSlot::getInstanceId).toList();
                takeFromLooked(room, effect, Prompt.Kind.TAKE_REVEALED_REST_BOTTOM, "Toma hasta " + take.max() + " " + plural(take.max(), "carta", "cartas")
                        + " que comparta tipo con " + name(subject) + " para tu mano. El resto va al fondo de tu mazo en orden aleatorio.", valid, take.max());
                return true;
            }
            default -> throw new IllegalStateException("Paso de otra familia: " + step);
        }
    }

    static String applyStep(GameRoom room, PendingEffect effect, EffectStep step, PlayerState player, List<String> picks,
                            List<String> order, boolean[] repeat) {
        String userId = player.getUserId();
        switch (step) {
            case TakeFromLookedEachRestToBottom take -> {
                List<CardSlot> chosen = picks.stream().map(id -> find(effect.getLooked(), id)).toList();
                if (chosen.contains(null)) return "Esa carta ya no está";
                if (!assignableEach(take.filters(), chosen)) return "Elige " + String.join(" y ", take.filters().stream().map(f -> "hasta 1 " + f.describe()).toList());
                lookedToHand(room, effect, player, picks);
                List<CardSlot> rest = inOrder(effect.getLooked(), order);
                rest.forEach(c -> c.setFaceDown(true));
                player.getDeck().addAll(rest);
                effect.getLooked().clear();
            }
            case TakeFromLookedDistinctLevels ignored -> {
                List<Integer> levels = picks.stream().map(id -> find(effect.getLooked(), id)).map(c -> c == null ? null : c.getLevel()).toList();
                if (new HashSet<>(levels).size() < levels.size()) return "Las cartas deben tener distinto nivel";
                lookedToHand(room, effect, player, picks);
                List<CardSlot> rest = inOrder(effect.getLooked(), order);
                rest.forEach(c -> c.setFaceDown(true));
                player.getDeck().addAll(rest);
                if (!rest.isEmpty()) log(room, player.getUsername() + " envía " + rest.size() + " " + plural(rest.size(), "carta", "cartas") + " al fondo de su mazo", null);
                effect.getLooked().clear();
            }
            case RevealFromHand r -> {
                List<CardSlot> cards = picks.stream().map(id -> find(player.getHand(), id)).toList();
                if (cards.contains(null)) return "Esa carta ya no está en tu mano";
                if (!assignable(cards, r.filters())) return "Revela " + String.join(" y ", r.filters().stream().map(f -> "1 " + f.describe()).toList());
                effect.setLastDone(true);
                effect.setLastTributed(new ArrayList<>(picks));
                log(room, player.getUsername() + " revela " + String.join(", ", cards.stream().map(EffectEngine::name).toList()) + " (" + effect.getSourceName() + ")", null);
            }
            case RevealOpponentHandSendOneToBottom ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                CardSlot card = opponent == null ? null : find(opponent.getHand(), picks.get(0));
                if (card == null) return "Esa carta ya no está";
                opponent.getHand().remove(card);
                card.setFaceDown(true);
                opponent.getDeck().add(card);
                record(room, GameEvent.SEND, userId, card, "hand", "deck", effect, Map.of("ownerId", opponent.getUserId()),
                        player.getUsername() + " envía " + name(card) + " al fondo del mazo de " + opponent.getUsername() + " (" + effect.getSourceName() + ")");
            }
            case TakeFromLookedLikeSubject ignored -> {
                for (String id : picks) {
                    CardSlot card = find(effect.getLooked(), id);
                    if (card == null) return "Esa carta ya no está";
                    effect.getLooked().remove(card);
                    card.setFaceDown(false);
                    player.getHand().add(card);
                    record(room, GameEvent.ADD_TO_HAND, userId, card, "deck", "hand", effect, null,
                            player.getUsername() + " añade " + name(card) + " a su mano (" + effect.getSourceName() + ")");
                }
                List<CardSlot> rest = new ArrayList<>(effect.getLooked());
                Collections.shuffle(rest, room.getRandom());
                rest.forEach(c -> c.setFaceDown(true));
                player.getDeck().addAll(rest);
                if (!rest.isEmpty()) log(room, player.getUsername() + " envía " + rest.size() + " " + plural(rest.size(), "carta", "cartas")
                        + " al fondo de su mazo en orden aleatorio", null);
                effect.getLooked().clear();
            }
            default -> {
                return "Este paso no espera ninguna elección";
            }
        }
        return null;
    }
}
