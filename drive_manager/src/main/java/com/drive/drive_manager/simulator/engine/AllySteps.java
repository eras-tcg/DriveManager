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
import static com.drive.drive_manager.simulator.engine.Removals.*;
import static com.drive.drive_manager.simulator.engine.TurnRules.*;
import static com.drive.drive_manager.simulator.engine.CombatRules.*;
import static com.drive.drive_manager.simulator.engine.Chain.*;
import static com.drive.drive_manager.simulator.engine.Answers.*;

/** Allies, their materials and Recursos. Each step: whether it can be done, carrying it out, and applying the player's choice. */
final class AllySteps {

    private AllySteps() {}

    static boolean possibleStep(GameRoom room, PendingEffect effect, EffectStep step) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        CardSlot onField = find(player.getField(), effect.getSourceInstanceId());
        return switch (step) {
            case AttachLowerFromTributes attach -> {
                GameEvent trigger = triggerEvent(room, effect);
                CardSlot ally = trigger == null ? null : find(player.getField(), trigger.getCardInstanceId());
                yield ally != null && ally.getLevel() != null && player.getTributeZone().stream()
                        .anyMatch(c -> attach.filter().matches(c) && c.getLevel() != null && c.getLevel() < ally.getLevel());
            }
            case TributeAlliesLevelSum t -> {
                List<CardSlot> valid = allies(player).stream().filter(t.filter()::matches).toList();
                yield valid.size() >= t.minCount() && valid.stream().mapToInt(c -> c.getLevel() == null ? 0 : c.getLevel()).sum() >= t.minSum();
            }
            case PlaceFromHandUntilTurnEnd p -> player.getHand().stream().anyMatch(p.filter()::matches);
            case TributeAllyMaterialWhere t -> !allyMaterialsWhere(player, t).isEmpty();
            case SacrificeAlly sacrifice -> !sacrificable(effect, player, sacrifice).isEmpty();
            case TributeAllyMaterial ignored -> !allyMaterials(player).isEmpty();
            case PickAllyWithHigherAlly ignored -> !withHigherAlly(player).isEmpty();
            case PickAllyWithMaterials pick -> allies(player).stream().anyMatch(c -> c.getMaterials().size() >= pick.count());
            case TributeNamedFromDiscard t -> lastNamed(player.getDiscardPile(), t.name()) != null;
            case TributeAllyResources t -> allies(player).stream().anyMatch(a -> a.getResources().stream().anyMatch(t.filter()::matches));
            case AddResourceToHigherAlly a -> !higherAllyResources(player, a).isEmpty();
            case AttachSelfAsResourceToAlly ignored -> {
                Located at = locate(player, effect.getSourceInstanceId());
                yield at != null && allies(player).stream().anyMatch(c -> c != at.card());
            }
            default -> true;
        };
    }

    static boolean runStep(GameRoom room, PendingEffect effect, EffectStep step) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        String userId = player.getUserId();

        switch (step) {
            case MaterialOfExhaustedAllyToBack m -> {
                List<CardSlot> valid = exhaustedAllyMaterials(player, m.filter());
                if (valid.isEmpty()) return false;
                pick(room, effect, "Puedes colocar 1 " + m.filter().describe() + " de los materiales de un aliado agotado en tu retaguardia.", "Colocar",
                        valid, ids(valid), 1);
                room.getPrompt().setMin(0);
                return true;
            }
            case TributeNamedFromDiscard t -> {
                CardSlot card = lastNamed(player.getDiscardPile(), t.name());
                if (card == null) return false;
                player.getDiscardPile().remove(card);
                card.setFaceDown(false);
                player.getTributeZone().add(card);
                effect.setLastDone(true);
                record(room, GameEvent.TRIBUTE, userId, card, "discardPile", "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                        player.getUsername() + " tributa " + name(card) + " (" + effect.getSourceName() + ")");
                return false;
            }
            case TributeAllyResources t -> {
                CardSlot ally = effect.getSubjectId() == null ? null : find(player.getField(), effect.getSubjectId());
                if (ally == null) {
                    List<CardSlot> valid = allies(player).stream().filter(a -> a.getResources().stream().anyMatch(t.filter()::matches)).toList();
                    if (valid.isEmpty()) return false;
                    pick(room, effect, "Elige 1 aliado con recursos " + t.filter().describe() + ".", "Elegido", player.getField(), ids(valid), 1);
                    return true;
                }
                List<CardSlot> valid = ally.getResources().stream().filter(t.filter()::matches).toList();
                pick(room, effect, "Tributa hasta " + t.max() + " recursos " + t.filter().describe() + " de " + name(ally) + ".", "Tributar", ally.getResources(),
                        ids(valid), Math.min(t.max(), valid.size()));
                room.getPrompt().setMin(0);
                return true;
            }
            case AddResourceToHigherAlly a -> {
                if (effect.getRitualTargetId() == null) {
                    List<CardSlot> valid = higherAllyResources(player, a);
                    if (valid.isEmpty()) return false;
                    pick(room, effect, "Elige 1 carta " + a.filter().describe() + " de tus " + zonesText(a.zone()) + ": será recurso de un aliado de nivel mayor.",
                            "Recurso", zones(player, a.zone()), ids(valid), 1);
                    return true;
                }
                CardSlot card = findAnywhere(room, userId, effect.getRitualTargetId());
                List<CardSlot> valid = card == null ? List.of() : higherThan(player, card).stream().filter(c -> c != card).toList();
                if (valid.isEmpty()) {
                    effect.setRitualTargetId(null);
                    return false;
                }
                pick(room, effect, "Elige el aliado de nivel mayor que recibe a " + name(card) + " como recurso.", "Recibe", player.getField(), ids(valid), 1);
                return true;
            }
            case ReturnAllyOfSummonedLevel ignored -> {
                CardSlot placed = findAnywhere(room, userId, effect.getSummonedId());
                if (placed == null || placed.getLevel() == null) return false;
                List<CardSlot> valid = allies(player).stream().filter(c -> c != placed && placed.getLevel().equals(c.getLevel())).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Sube a tu mano 1 aliado nivel " + placed.getLevel() + ".", "A tu mano", player.getField(), ids(valid), 1);
                return true;
            }
            case AttachSelfAsResourceToAlly ignored -> {
                Located at = locate(player, effect.getSourceInstanceId());
                List<CardSlot> valid = at == null ? List.of() : allies(player).stream().filter(c -> c != at.card()).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Elige el aliado que recibe a " + effect.getSourceName() + " como recurso.", "Recibe", player.getField(), ids(valid), 1);
                return true;
            }
            case PickAllyWithHigherAlly ignored -> {
                List<CardSlot> valid = withHigherAlly(player);
                if (valid.isEmpty()) return false;
                pick(room, effect, "Elige el aliado que será material de otro de nivel mayor.", "Material", player.getField(), ids(valid), 1);
                return true;
            }
            case AttachSubjectToHigherAlly ignored -> {
                CardSlot subject = subject(room, effect);
                if (subject == null) return false;
                List<CardSlot> valid = higherThan(player, subject);
                if (valid.isEmpty()) return false;
                pick(room, effect, "Elige el aliado de nivel mayor que recibe a " + name(subject) + " como material.", "Recibe", player.getField(), ids(valid), 1);
                return true;
            }
            case PickAllyWithMaterials pick -> {
                List<CardSlot> valid = allies(player).stream().filter(c -> c.getMaterials().size() >= pick.count()).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Elige 1 aliado con " + pick.count() + " o más materiales.", "Elegido", player.getField(), ids(valid), 1);
                return true;
            }
            case ActivateSubjectSummonEffect ignored -> {
                CardSlot ally = subject(room, effect);
                EffectScript summon = ally == null ? null : CardEffects.of(ally.getCardName()).stream()
                        .filter(sc -> sc.moment() == EffectScript.Moment.ON_SUMMON).findFirst().orElse(null);
                if (summon == null) {
                    log(room, "No hay un efecto En Invocación que activar (" + effect.getSourceName() + ")", null);
                    return false;
                }
                // Activated as the ally's own: a new link of the chain, after this one resolves
                PendingEffect link = pending(room, summon, userId, ally, room.getEventSeq());
                link.setConfirmed(true);
                link.setOrdered(true);
                room.getTriggerQueue().add(0, link);
                log(room, name(ally) + " activa su efecto En Invocación (" + effect.getSourceName() + ")", null);
                return false;
            }
            case TributeAlliesLevelSum t -> {
                List<CardSlot> valid = allies(player).stream().filter(t.filter()::matches).toList();
                pick(room, effect, "Tributa " + t.minCount() + " o más aliados " + t.filter().describe() + " que sumen " + t.minSum() + " o más en nivel.",
                        "Tributar", player.getField(), ids(valid), valid.size());
                room.getPrompt().setMin(t.minCount());
                return true;
            }
            case PlaceFromHandUntilTurnEnd p -> {
                List<CardSlot> valid = player.getHand().stream().filter(p.filter()::matches).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Puedes colocar 1 criatura " + p.filter().describe() + " de tu mano en tu retaguardia; al final del turno vuelve a tu mano.",
                        "A la retaguardia", player.getHand(), ids(valid), 1);
                room.getPrompt().setMin(0);
                return true;
            }
            case TributeAllyMaterialWhere t -> {
                List<CardSlot> valid = allyMaterialsWhere(player, t);
                if (valid.isEmpty()) return false;
                pick(room, effect, "Tributa 1 material " + t.material().describe() + " de un aliado " + t.ally().describe() + ".", "Tributar", valid, ids(valid), 1);
                return true;
            }
            case ActivateTributedSummonEffect ignored -> {
                CardSlot ally = subject(room, effect);
                String materialId = effect.getLastTributed().isEmpty() ? null : effect.getLastTributed().get(0);
                CardSlot material = materialId == null ? null : find(player.getTributeZone(), materialId);
                EffectScript summon = material == null ? null : CardEffects.of(material.getCardName()).stream()
                        .filter(sc -> sc.moment() == EffectScript.Moment.ON_SUMMON).findFirst().orElse(null);
                if (ally == null || summon == null) {
                    log(room, "No hay un efecto En Invocación que activar (" + effect.getSourceName() + ")", null);
                    return false;
                }
                // The ally activates it as its own: a new link of the chain, after this one resolves
                PendingEffect link = pending(room, summon, userId, ally, room.getEventSeq());
                link.setConfirmed(true);
                link.setOrdered(true);
                room.getTriggerQueue().add(0, link);
                log(room, name(ally) + " activa el efecto En Invocación de " + name(material) + " (" + effect.getSourceName() + ")", null);
                return false;
            }
            case AttachSubjectToSummoned ignored -> {
                CardSlot ally = find(player.getField(), effect.getSubjectId());
                CardSlot summoned = find(player.getField(), effect.getSummonedId());
                if (ally == null || summoned == null || ally == summoned) return false;
                player.getField().remove(ally);
                leaveField(ally);
                List<CardSlot> materials = new ArrayList<>(ally.getMaterials());
                ally.getMaterials().clear();
                summoned.getMaterials().add(ally);
                summoned.getMaterials().addAll(materials);
                record(room, GameEvent.SEND, userId, ally, "field", "materials", effect, Map.of("to", summoned.getInstanceId()),
                        player.getUsername() + " añade " + name(ally) + (materials.isEmpty() ? "" : " y sus " + materials.size() + " "
                                + plural(materials.size(), "material", "materiales")) + " como materiales de " + name(summoned));
                return false;
            }
            case AttachLowerFromTributes attach -> {
                GameEvent trigger = triggerEvent(room, effect);
                CardSlot ally = trigger == null ? null : find(player.getField(), trigger.getCardInstanceId());
                if (ally == null || ally.getLevel() == null) return false;
                List<String> valid = player.getTributeZone().stream()
                        .filter(c -> attach.filter().matches(c) && c.getLevel() != null && c.getLevel() < ally.getLevel())
                        .map(CardSlot::getInstanceId).toList();
                if (valid.isEmpty()) return false;
                effect.setSubjectId(ally.getInstanceId());
                pick(room, effect, "Añade a " + name(ally) + " 1 criatura " + attach.filter().describe() + " de menor nivel de tus tributos como material.",
                        "Material", player.getTributeZone(), valid, 1);
                return true;
            }
            case SacrificeAlly sacrifice -> {
                List<CardSlot> valid = sacrificable(effect, player, sacrifice);
                if (valid.isEmpty()) return false;
                pick(room, effect, sacrifice.toUntapAnother()
                        ? "Sacrifica 1 aliado. No puede ser el único aliado agotado que queda por reactivar."
                        : "Sacrifica 1 aliado.", "Sacrificar", player.getField(), ids(valid), 1);
                return true;
            }
            case TributeAllyMaterial ignored -> {
                List<CardSlot> valid = allyMaterials(player);
                if (valid.isEmpty()) return false;
                pick(room, effect, "Tributa 1 material de 1 de tus aliados.", "Tributar", valid, ids(valid), 1);
                return true;
            }
            default -> throw new IllegalStateException("Paso de otra familia: " + step);
        }
    }

    static String applyStep(GameRoom room, PendingEffect effect, EffectStep step, PlayerState player, List<String> picks,
                            List<String> order, boolean[] repeat) {
        String userId = player.getUserId();
        switch (step) {
            case MaterialOfExhaustedAllyToBack m -> {
                if (picks.isEmpty()) break;
                CardSlot card = find(exhaustedAllyMaterials(player, m.filter()), picks.get(0));
                if (card == null) return "Ese material ya no está";
                for (CardSlot ally : player.getField()) ally.getMaterials().remove(card);
                player.getHand().add(card);
                placeOnField(room, effect, player, card, "hand", false, false);
                effect.setLastDone(true);
            }
            case TributeAllyResources ignored -> {
                if (effect.getSubjectId() == null || find(player.getField(), effect.getSubjectId()) == null) {
                    if (find(player.getField(), picks.get(0)) == null) return "Ese aliado ya no está en el campo";
                    effect.setSubjectId(picks.get(0));
                    repeat[0] = true;
                    return null;
                }
                CardSlot ally = find(player.getField(), effect.getSubjectId());
                for (String id : picks) {
                    CardSlot resource = find(ally.getResources(), id);
                    if (resource == null) return "Ese recurso ya no está";
                    ally.getResources().remove(resource);
                    resource.setFaceDown(false);
                    player.getTributeZone().add(resource);
                    record(room, GameEvent.TRIBUTE, userId, resource, "resources", "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                            player.getUsername() + " tributa " + name(resource) + " de los recursos de " + name(ally));
                }
                effect.setLastCount(picks.size());
            }
            case AddResourceToHigherAlly a -> {
                if (effect.getRitualTargetId() == null) {
                    effect.setRitualTargetId(picks.get(0));
                    repeat[0] = true;
                    return null;
                }
                CardSlot card = findAnywhere(room, userId, effect.getRitualTargetId());
                CardSlot ally = find(player.getField(), picks.get(0));
                effect.setRitualTargetId(null);
                if (card == null || ally == null) return "Esa carta ya no está disponible";
                String from = zoneOf(player, a.zone(), card);
                zone(player, from).remove(card);
                card.setFaceDown(false);
                ally.getResources().add(card);
                effect.setLastDone(true);
                record(room, GameEvent.SEND, userId, card, from, "resources", effect, Map.of("to", ally.getInstanceId()),
                        player.getUsername() + " añade " + name(card) + " como recurso de " + name(ally));
            }
            case ReturnAllyOfSummonedLevel ignored -> {
                CardSlot ally = find(player.getField(), picks.get(0));
                if (ally == null) return "Ese aliado ya no está en el campo";
                removeFromField(player, ally);
                player.getHand().add(ally);
                effect.setLastTributed(new ArrayList<>(List.of(ally.getInstanceId())));
                record(room, GameEvent.SEND, userId, ally, "field", "hand", effect, null, player.getUsername() + " sube " + name(ally) + " a su mano (" + effect.getSourceName() + ")");
            }
            case AttachSelfAsResourceToAlly ignored -> {
                if (find(player.getField(), picks.get(0)) == null) return "Ese aliado ya no está en el campo";
                effect.setSubjectId(picks.get(0));
                attachSelfToSubject(room, effect, player, false);
            }
            case PickAllyWithHigherAlly ignored -> {
                if (find(player.getField(), picks.get(0)) == null) return "Ese aliado ya no está en el campo";
                effect.setSubjectId(picks.get(0));
            }
            case AttachSubjectToHigherAlly ignored -> {
                CardSlot subject = subject(room, effect);
                CardSlot holder = find(player.getField(), picks.get(0));
                if (subject == null || holder == null || !higherThan(player, subject).contains(holder)) return "Debe ser un aliado de nivel mayor";
                removeFromField(player, subject);
                subject.setFaceDown(false);
                holder.getMaterials().add(subject);
                effect.setLastDone(true);
                record(room, GameEvent.SEND, userId, subject, "field", "materials", effect, Map.of("to", holder.getInstanceId()),
                        player.getUsername() + " añade " + name(subject) + " como material de " + name(holder) + " (" + effect.getSourceName() + ")");
            }
            case PickAllyWithMaterials ignored -> {
                if (find(player.getField(), picks.get(0)) == null) return "Ese aliado ya no está en el campo";
                effect.setSubjectId(picks.get(0));
            }
            case TributeAlliesLevelSum t -> {
                List<CardSlot> chosen = new ArrayList<>();
                for (String id : picks) {
                    CardSlot c = find(player.getField(), id);
                    if (c == null || !t.filter().matches(c)) return "Ese aliado ya no está en el campo";
                    chosen.add(c);
                }
                int sum = chosen.stream().mapToInt(c -> c.getLevel() == null ? 0 : c.getLevel()).sum();
                if (chosen.size() < t.minCount() || sum < t.minSum())
                    return "Elige " + t.minCount() + " o más aliados que sumen " + t.minSum() + " o más en nivel (suman " + sum + ")";
                for (CardSlot c : chosen) {
                    player.getField().remove(c);
                    for (CardSlot a : concat(c.getMaterials(), c.getResources())) player.getDiscardPile().add(a);
                    c.setMaterials(new ArrayList<>());
                    c.setResources(new ArrayList<>());
                    leaveField(c);
                    player.getTributeZone().add(c);
                    record(room, GameEvent.TRIBUTE, userId, c, "field", "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                            player.getUsername() + " tributa a " + name(c) + " (" + effect.getSourceName() + ")");
                }
            }
            case PlaceFromHandUntilTurnEnd ignored -> {
                if (picks.isEmpty()) return null;
                CardSlot card = find(player.getHand(), picks.get(0));
                if (card == null) return "Esa carta ya no está en tu mano";
                player.getHand().remove(card);
                card.setFaceDown(false);
                card.setTapped(false);
                card.setX(50.0);
                card.setY(75.0);
                card.setReturnAtTurnEnd(true);
                player.getField().add(card);
                // Its details show the condition it is placed under (Janne's inherited effect)
                String from = CardEffects.inheritedFrom(effect.getScriptId());
                CardSlot holder = from == null ? null : find(player.getField(), effect.getSourceInstanceId());
                String giver = holder == null ? from : holder.getResources().stream().map(CardSlot::getCardName)
                        .filter(from::equalsIgnoreCase).findFirst().orElse(from);
                floating(card, effect, "Colocada hasta el final del turno: entonces vuelve a tu mano" + (giver == null ? "" : " (heredado de " + giver + ")"),
                        "turn", 0, null);
                record(room, GameEvent.SEND, userId, card, "hand", "field", effect, Map.of("placed", true),
                        player.getUsername() + " coloca " + name(card) + " en su retaguardia hasta el final del turno (" + effect.getSourceName() + ")");
            }
            case TributeAllyMaterialWhere t -> {
                CardSlot holder = allies(player).stream().filter(a -> t.ally().matches(a) && find(a.getMaterials(), picks.get(0)) != null)
                        .findFirst().orElse(null);
                if (holder == null) return "Ese material ya no está";
                CardSlot material = find(holder.getMaterials(), picks.get(0));
                holder.getMaterials().remove(material);
                material.setFaceDown(false);
                player.getTributeZone().add(material);
                effect.setSubjectId(holder.getInstanceId());
                effect.setLastTributed(new ArrayList<>(List.of(material.getInstanceId())));
                record(room, GameEvent.TRIBUTE, userId, material, "materials", "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                        player.getUsername() + " tributa " + name(material) + " de los materiales de " + name(holder) + " (" + effect.getSourceName() + ")");
            }
            case SacrificeAlly ignored -> {
                CardSlot ally = find(player.getField(), picks.get(0));
                if (ally == null) return "Ese aliado ya no está en el campo";
                effect.setLastTributed(new ArrayList<>(List.of(ally.getInstanceId())));
                destroy(room, ally, player, true, CAUSE_EFFECT, userId, null, effect);
            }
            case AttachLowerFromTributes ignored -> {
                CardSlot ally = find(player.getField(), effect.getSubjectId());
                CardSlot card = find(player.getTributeZone(), picks.get(0));
                if (ally == null || card == null) return "Esa carta ya no está disponible";
                player.getTributeZone().remove(card);
                card.setFaceDown(false);
                ally.getMaterials().add(card);
                record(room, GameEvent.SEND, userId, card, "tributeZone", "materials", effect, Map.of("to", ally.getInstanceId()),
                        player.getUsername() + " añade " + name(card) + " como material de " + name(ally) + " (" + effect.getSourceName() + ")");
            }
            case TributeAllyMaterial ignored -> {
                CardSlot holder = allies(player).stream().filter(a -> find(a.getMaterials(), picks.get(0)) != null).findFirst().orElse(null);
                if (holder == null) return "Ese material ya no está";
                CardSlot material = find(holder.getMaterials(), picks.get(0));
                holder.getMaterials().remove(material);
                material.setFaceDown(false);
                player.getTributeZone().add(material);
                record(room, GameEvent.TRIBUTE, userId, material, "materials", "tributeZone", effect, Map.of("byCard", effect.getSourceInstanceId()),
                        player.getUsername() + " tributa " + name(material) + " de los materiales de " + name(holder) + " (" + effect.getSourceName() + ")");
            }
            default -> {
                return "Este paso no espera ninguna elección";
            }
        }
        return null;
    }
}
