package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.FloatingEffect;
import com.drive.drive_manager.simulator.GameLogEntry;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectScript.Limit;
import com.drive.drive_manager.simulator.engine.EffectStep.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.drive.drive_manager.simulator.engine.EffectEngine.*;
import static com.drive.drive_manager.simulator.engine.Summons.*;
import static com.drive.drive_manager.simulator.engine.TurnRules.*;
import static com.drive.drive_manager.simulator.engine.CombatRules.*;
import static com.drive.drive_manager.simulator.engine.Chain.*;
import static com.drive.drive_manager.simulator.engine.Steps.*;
import static com.drive.drive_manager.simulator.engine.Answers.*;

/** Cards leaving the field: destruction and sacrifice (rule 12, "Sacrificio y destrucción") and the replacements that can stop them ("en su lugar", Protección). */
public final class Removals {

    private Removals() {}

    // ------------------------------------------------------------------ Destruction and sacrifice

    /**
     * Destroys a card on the field from the board (cases the engine does not play yet): it goes to its
     * owner's Descarte and its "En Destrucción" effects trigger.
     */
    public static String destroyByPlayer(GameRoom room, String userId, String ownerId, String instanceId) {
        String error = checkOpen(room);
        if (error != null) return error;
        PlayerState owner = room.getPlayer(ownerId != null ? ownerId : userId);
        CardSlot card = owner == null ? null : find(owner.getField(), instanceId);
        if (card == null) return "La carta no está en el campo";
        destroy(room, card, owner, false, CAUSE_MANUAL, userId, null, null);
        advance(room);
        return null;
    }

    /** Sacrifices one of your cards on the field from the board: destroyed, to your Tributos (rule 4.0.4.2). */
    public static String sacrificeByPlayer(GameRoom room, String userId, String instanceId) {
        String error = checkOpen(room);
        if (error != null) return error;
        PlayerState owner = room.getPlayer(userId);
        CardSlot card = owner == null ? null : find(owner.getField(), instanceId);
        if (card == null) return "Solo puedes sacrificar tus cartas del campo";
        destroy(room, card, owner, true, CAUSE_MANUAL, userId, null, null);
        advance(room);
        return null;
    }

    /**
     * A creature whose strength was reduced to 0 is destroyed right away (rule 12.0.0.2), by effect and
     * attributed to whoever reduced it.
     */
    public static void destroyIfNoStrength(GameRoom room, String userId, PlayerState owner, CardSlot card) {
        if (room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS || room.isEngineBusy()) return;
        if (!"creature".equals(card.getCardType()) || card.getStrength() == null) return;
        if (strength(card) > 0 || find(owner.getField(), card.getInstanceId()) == null) return;
        destroy(room, card, owner, false, CAUSE_EFFECT, userId, null, null);
        advance(room);
    }

    static String checkOpen(GameRoom room) {
        if (room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS) return "La partida no está en curso";
        if (room.isEngineBusy()) return room.getCombat() != null ? "Hay un combate en curso" : "Hay un efecto resolviéndose";
        return null;
    }

    /**
     * Destroys (to the Descarte) or sacrifices (to the Tributos) a card on its owner's field. Its Recursos and
     * Materiales go to the Descarte (rules 6.3.0.5, 6.4.0.5) and it loses its state on the field.
     *
     * @param byCard the card that destroyed it (in a clash), if any; for effects, the effect's card
     */
    static GameEvent destroy(GameRoom room, CardSlot card, PlayerState owner, boolean sacrifice, String cause, String byId,
                             String byCard, PendingEffect effect) {
        // "no puede ser removido por efectos oponentes"
        if (effect != null && byId != null && !byId.equals(owner.getUserId()) && keywords(card).contains(UNREMOVABLE)) {
            log(room, name(card) + " no puede ser removida por efectos oponentes", null);
            // At strength 0 the rules destroy it anyway: not the effect, so nothing that reacts to a destruction by effect
            if ("creature".equals(card.getCardType()) && card.getStrength() != null && strength(card) <= 0)
                return destroy(room, card, owner, false, CAUSE_RULES, null, null, null);
            return null;
        }
        // A replacement ("en su lugar", Protección): its owner chooses first; the destruction waits
        if (!sacrifice && deferRemoval(room, card, owner, true, cause, byId, byCard, effect)) return null;
        owner.getField().remove(card);
        Combat combat = room.getCombat();
        if (combat != null && owner.getUserId().equals(combat.getDefenderId())) combat.setEnemyDestroyed(true);
        // Its Recursos leave with it, but what they gave it "En Destrucción" still triggers (rule 11.4.0.3)
        List<String> inherited = card.getResources().stream().map(CardSlot::getCardName).filter(Objects::nonNull).toList();
        removeFromField(owner, card);
        String toZone = sacrifice ? "tributeZone" : "discardPile";
        (sacrifice ? owner.getTributeZone() : owner.getDiscardPile()).add(card);

        PlayerState by = room.getPlayer(byId);
        String who = by != null ? by.getUsername() : "Las reglas";
        String whose = by != null && by != owner ? " de " + owner.getUsername() : "";
        String why = effect != null ? " (" + effect.getSourceName() + ")"
                : CAUSE_BATTLE.equals(cause) ? " en el enfrentamiento"
                : CAUSE_RITUAL.equals(cause) ? " para el Ritual"
                : CAUSE_EFFECT.equals(cause) ? " (fuerza 0)"
                : CAUSE_RULES.equals(cause) ? " por tener fuerza 0" : "";
        String text = sacrifice ? who + " sacrifica " + name(card) + why : who + " destruye " + name(card) + whose + why;
        Map<String, Object> data = new HashMap<>();
        data.put("cause", cause);
        data.put("ownerId", owner.getUserId());
        if (byCard == null && effect != null) byCard = effect.getSourceInstanceId();
        if (byCard != null) data.put("byCard", byCard);
        if (!inherited.isEmpty()) data.put("inherited", inherited);
        if (combat != null && card.getInstanceId().equals(combat.getAttackerId())) data.put("asAttacker", true);
        return record(room, sacrifice ? GameEvent.SACRIFICE : GameEvent.DESTROY, byId, card, "field", toZone, effect, data, text);
    }

    /**
     * A card that leaves the field is a new object (rule 6.3.0.5): it loses everything that applied to it there — the
     * floating effects, gained keywords and strength, exhausted state. Field effects (Príncipe Charles) apply again
     * when it comes back, since they look at the field.
     */
    public static void leaveField(CardSlot card) {
        card.setFaceDown(false);
        card.setTapped(false);
        card.setStrengthModifier(0);
        card.setTurnStrength(0);
        card.setLaterStrength(0);
        card.getTurnKeywords().clear();
        card.getLaterKeywords().clear();
        card.getUntilNextAttack().clear();
        card.setNoUntap(0);
        card.setNoStartUntap(0);
        card.setContinuousStrength(0);
        card.setContinuousLevel(0);
        card.getContinuousKeywords().clear();
        card.getFloating().clear();
        card.setReturnAtTurnEnd(false);
        card.setX(null);
        card.setY(null);
    }

    // ------------------------------------------------------------------ Replacements ("en su lugar", Protección)

    /** A way to replace a removal: the card's own or inherited replacement, or a protector destroyed instead. */
    record ReplacementOption(String key, CardSlot source, CardEffects.Replacement replacement, String label, String text) {}

    /**
     * Rule of replacements: "cuando X fuera a ser destruida / removida: en su lugar …". When the card has one its owner
     * can use, the removal waits for their choice. Manual removals (editor mode) are never replaced.
     *
     * @return true when the removal waits (it is done or replaced once the owner answers)
     */
    static boolean deferRemoval(GameRoom room, CardSlot card, PlayerState owner, boolean destroy, String cause, String byId,
                                        String byCard, PendingEffect effect) {
        if (CAUSE_MANUAL.equals(cause) || card == null) return false;
        if (room.getRemovalDecided().remove(card.getInstanceId())) return false;
        if (replacementOptions(room, card, owner, destroy, byId).isEmpty()) return false;
        if (room.getPendingRemovals().stream().anyMatch(r -> r.cardId().equals(card.getInstanceId()))) return true;
        room.getPendingRemovals().add(new PendingRemoval(card.getInstanceId(), owner.getUserId(), destroy, cause, byId, byCard,
                effect == null ? null : effect.getId()));
        return true;
    }

    static List<ReplacementOption> replacementOptions(GameRoom room, CardSlot card, PlayerState owner, boolean destroy, String byId) {
        List<ReplacementOption> options = new ArrayList<>();
        boolean byOpponent = byId != null && !byId.equals(owner.getUserId());
        for (CardEffects.Replacement r : CardEffects.replacementsOf(card)) {
            if (r.destroyOnly() && !destroy) continue;
            if (r.byOpponentOnly() && !byOpponent) continue;
            if (r.limit() == Limit.DEFINITIVE && room.isFirstTurn()) continue;
            if (r.limit() != Limit.NONE && room.getUsedThisTurn().contains(replacementKey(owner, card, r))) continue;
            boolean payable = switch (r.cost()) {
                case TRIBUTE_TWO_MATERIALS -> card.getMaterials().size() >= 2;
                case TRIBUTE_ONE_FROM_HAND -> !owner.getHand().isEmpty();
                case TRIBUTE_RESOURCES, RESOURCES_TO_TRIBUTES_BEFORE -> card.getResources().stream().anyMatch(r.resourceFilter()::matches);
                case MATERIALS_TO_BACK -> card.getMaterials().stream().anyMatch(m -> "creature".equals(m.getCardType()) && r.resourceFilter().matches(m));
            };
            if (payable) options.add(new ReplacementOption(r.id() + "@" + card.getInstanceId(), card, r, r.label(), r.text()));
        }
        // Protección [tipo]: "cuando algún aliado [tipo] fuera a ser removido: en su lugar, puedes destruir esta carta"
        for (CardSlot protector : owner.getField()) {
            if (protector == card) continue;
            for (String k : keywords(protector)) {
                if (!k.toLowerCase(Locale.ROOT).startsWith("protección")) continue;
                String kind = k.replaceAll("(?i)protección", "").replaceAll("[\\[\\]]", "").trim();
                boolean matches = kind.isEmpty() || (card.getClasses() != null && card.getClasses().stream().anyMatch(kind::equalsIgnoreCase));
                if (matches) options.add(new ReplacementOption("proteccion@" + protector.getInstanceId(), protector, null, k,
                        k + ": en su lugar, puedes destruir " + name(protector) + "."));
            }
        }
        return options;
    }

    static String replacementKey(PlayerState owner, CardSlot card, CardEffects.Replacement r) {
        // A Definitive one counts as the player's Definitive effect of the turn (only 1 per turn, whichever card)
        if (r.limit() == Limit.DEFINITIVE) return owner.getUserId() + "|" + EffectEngine.DEFINITIVE_USED;
        return r.limit() == Limit.ONCE_PER_TURN_BETWEEN_COPIES ? owner.getUserId() + "|" + r.id() : owner.getUserId() + "|" + card.getInstanceId() + "@" + card.getFieldSeq() + "|" + r.id();
    }

    /** Asks the owner of the first pending removal whether to replace it; a removal with nothing left to replace it happens now. */
    static void promptRemoval(GameRoom room) {
        PendingRemoval pending = room.getPendingRemovals().get(0);
        PlayerState owner = room.getPlayer(pending.ownerId());
        CardSlot card = owner == null ? null : find(owner.getField(), pending.cardId());
        List<ReplacementOption> options = card == null ? List.of() : replacementOptions(room, card, owner, pending.destroy(), pending.byId());
        if (options.isEmpty()) {
            room.getPendingRemovals().remove(0);
            if (card != null) executeRemoval(room, pending, owner, card);
            return;
        }
        List<CardSlot> sources = options.stream().map(ReplacementOption::source).distinct().toList();
        rulesPrompt(room, owner.getUserId(), PURPOSE_REPLACE, name(card) + (pending.destroy() ? " va a ser destruida" : " va a ser removida"),
                "Puedes usar 1 de estos efectos, o dejar que " + (pending.destroy() ? "sea destruida." : "sea removida."),
                "Usar", sources, sources);
        room.getPrompt().setSubjectId(card.getInstanceId());
        room.getPrompt().setEffects(options.stream()
                .map(o -> {
                    // An inherited replacement (Deriduragon): the Recurso that gives it, small on the card
                    CardSlot giver = o.replacement() == null ? null : CardEffects.replacementGiver(o.source(), o.replacement());
                    return new Prompt.OfferedEffect(o.key(), o.source().getInstanceId(), name(o.source()), o.label(), o.text(),
                            giver == null ? null : giver.getCardName(), giver == null ? null : giver.getImageUrl());
                }).toList());
    }

    static String answerRemoval(GameRoom room, Prompt prompt, PlayerState player, List<String> picks) {
        if (room.getPendingRemovals().isEmpty()) {
            room.setPrompt(null);
            return null;
        }
        PendingRemoval pending = room.getPendingRemovals().get(0);
        CardSlot card = find(player.getField(), pending.cardId());
        if (PURPOSE_REPLACE_MATERIALS.equals(prompt.getPurpose())) {
            // Gémini: the chosen materials go to the Retaguardia, then the destruction goes on (a reaction, not "en su lugar")
            room.setPrompt(null);
            room.getPendingRemovals().remove(0);
            if (card == null) return null;
            for (String id : picks) {
                CardSlot m = find(card.getMaterials(), id);
                if (m == null) continue;
                card.getMaterials().remove(m);
                m.setFaceDown(false);
                m.setTapped(false);
                m.setX(50.0);
                m.setY(75.0);
                player.getField().add(m);
                record(room, GameEvent.SEND, player.getUserId(), m, "materials", "field", null, Map.of("from", card.getInstanceId()),
                        player.getUsername() + " coloca " + name(m) + " (material de " + name(card) + ") en su retaguardia");
            }
            executeRemoval(room, pending, player, card);
            return null;
        }
        if (PURPOSE_REPLACE_HAND.equals(prompt.getPurpose())) {
            // Caballero Leal: the hand card tributed instead
            CardSlot paid = picks.isEmpty() ? null : find(player.getHand(), picks.get(0));
            if (paid == null) return "Elige 1 carta de tu mano";
            room.setPrompt(null);
            room.getPendingRemovals().remove(0);
            player.getHand().remove(paid);
            player.getTributeZone().add(paid);
            record(room, GameEvent.TRIBUTE, player.getUserId(), paid, "hand", "tributeZone", null, null,
                    player.getUsername() + " tributa " + name(paid) + " de su mano: " + (card == null ? "la carta" : name(card)) + " no es destruida");
            if (card != null) destroyIfStillNoStrength(room, pending, player, card);
            return null;
        }
        room.setPrompt(null);
        if (card == null) {
            room.getPendingRemovals().remove(0);
            return null;
        }
        List<ReplacementOption> options = replacementOptions(room, card, player, pending.destroy(), pending.byId());
        ReplacementOption chosen = picks.isEmpty() ? null : options.stream()
                .filter(o -> o.key().equals(picks.get(0)) || o.source().getInstanceId().equals(picks.get(0))).findFirst().orElse(null);
        if (chosen == null) {
            // Not replaced: the removal happens now
            room.getPendingRemovals().remove(0);
            executeRemoval(room, pending, player, card);
            return null;
        }
        if (chosen.replacement() == null) {
            // Protección: the protector is destroyed instead (it may have a replacement of its own)
            room.getPendingRemovals().remove(0);
            log(room, name(chosen.source()) + " protege a " + name(card) + ": es destruida en su lugar", null);
            destroy(room, chosen.source(), player, false, CAUSE_EFFECT, player.getUserId(), card.getInstanceId(), null);
            destroyIfStillNoStrength(room, pending, player, card);
            return null;
        }
        CardEffects.Replacement r = chosen.replacement();
        if (r.limit() != Limit.NONE) room.getUsedThisTurn().add(replacementKey(player, card, r));
        switch (r.cost()) {
            case TRIBUTE_TWO_MATERIALS -> {
                for (int i = 0; i < 2 && !card.getMaterials().isEmpty(); i++) {
                    CardSlot m = card.getMaterials().remove(0);
                    m.setFaceDown(false);
                    player.getTributeZone().add(m);
                    record(room, GameEvent.TRIBUTE, player.getUserId(), m, "materials", "tributeZone", null, Map.of("byCard", card.getInstanceId()), null);
                }
                log(room, player.getUsername() + " tributa 2 materiales de " + name(card) + ": no es removida", null);
            }
            case TRIBUTE_RESOURCES -> {
                List<CardSlot> paid = card.getResources().stream().filter(r.resourceFilter()::matches).toList();
                card.getResources().removeAll(paid);
                for (CardSlot c : paid) {
                    c.setFaceDown(false);
                    player.getTributeZone().add(c);
                }
                log(room, player.getUsername() + " tributa " + paid.size() + " " + plural(paid.size(), "recurso", "recursos") + " de " + name(card)
                        + ": no es " + (pending.destroy() ? "destruida" : "removida") + names(paid), null);
            }
            case RESOURCES_TO_TRIBUTES_BEFORE -> {
                // A reaction: the Recursos go to the Tributos first, then the removal goes on
                List<CardSlot> paid = card.getResources().stream().filter(r.resourceFilter()::matches).toList();
                card.getResources().removeAll(paid);
                for (CardSlot c : paid) {
                    c.setFaceDown(false);
                    player.getTributeZone().add(c);
                    record(room, GameEvent.TRIBUTE, player.getUserId(), c, "resources", "tributeZone", null, Map.of("byCard", card.getInstanceId()), null);
                }
                log(room, player.getUsername() + " tributa " + paid.size() + " " + plural(paid.size(), "recurso", "recursos") + " de " + name(card)
                        + " antes de que sea " + (pending.destroy() ? "destruida" : "removida") + names(paid), null);
                room.getPendingRemovals().remove(0);
                executeRemoval(room, pending, player, card);
                return null;
            }
            case MATERIALS_TO_BACK -> {
                List<CardSlot> valid = card.getMaterials().stream().filter(m -> "creature".equals(m.getCardType()) && r.resourceFilter().matches(m)).toList();
                rulesPrompt(room, player.getUserId(), PURPOSE_REPLACE_MATERIALS, name(card) + " · " + r.label(),
                        "Coloca hasta 2 de estas criaturas en tu retaguardia; luego " + name(card) + " es destruida.", "A la retaguardia", valid, card.getMaterials());
                room.getPrompt().setMin(1);
                room.getPrompt().setMax(Math.min(2, valid.size()));
                return null;
            }
            case TRIBUTE_ONE_FROM_HAND -> {
                // The hand card is chosen next; the removal stays pending until then
                rulesPrompt(room, player.getUserId(), PURPOSE_REPLACE_HAND, name(card) + " · " + r.label(),
                        "Tributa 1 carta de tu mano: " + name(card) + " no es destruida.", "Tributar", player.getHand(), player.getHand());
                room.getPrompt().setMin(1);
                return null;
            }
        }
        room.getPendingRemovals().remove(0);
        destroyIfStillNoStrength(room, pending, player, card);
        return null;
    }

    /**
     * Rule 12.0.0.2 is checked again after a destruction is replaced: a creature that is still at strength 0 is destroyed anyway
     * (designer ruling 2026-10-30), by the rules and not by the effect (no "destruida por efecto" triggers). It may be replaced
     * again if its owner can pay once more.
     */
    static void destroyIfStillNoStrength(GameRoom room, PendingRemoval pending, PlayerState owner, CardSlot card) {
        if (!"creature".equals(card.getCardType()) || card.getStrength() == null || strength(card) > 0) return;
        if (find(owner.getField(), card.getInstanceId()) == null) return;
        log(room, name(card) + " sigue con fuerza 0: es destruida", null);
        destroy(room, card, owner, false, CAUSE_RULES, null, null, null);
    }

    /** The removal that was waiting, as it was going to happen. */
    static void executeRemoval(GameRoom room, PendingRemoval pending, PlayerState owner, CardSlot card) {
        PendingEffect effect = pending.effectId() == null ? null
                : room.getChain().stream().filter(e -> pending.effectId().equals(e.getId())).findFirst().orElse(null);
        room.getRemovalDecided().add(card.getInstanceId());
        if (pending.destroy()) {
            destroy(room, card, owner, false, pending.cause(), pending.byId(), pending.byCard(), effect);
            return;
        }
        room.getRemovalDecided().remove(card.getInstanceId());
        removeFromField(owner, card);
        owner.getHand().add(card);
        PlayerState by = room.getPlayer(pending.byId());
        record(room, GameEvent.SEND, pending.byId(), card, "field", "hand", effect, Map.of("ownerId", owner.getUserId()),
                (by == null ? "" : by.getUsername() + " ") + "sube a " + name(card) + " a la mano de " + owner.getUsername());
    }

    /**
     * A card leaves the field (destroyed, tributed, bounced, used as a material…): it loses its state and its Recursos
     * and Materiales go to the Descarte (rules 6.3.0.5, 6.4.0.5). Only a special summon's summoner passes them on.
     */
    static void removeFromField(PlayerState owner, CardSlot card) {
        owner.getField().remove(card);
        for (CardSlot attached : concat(card.getMaterials(), card.getResources())) {
            attached.setFaceDown(false);
            owner.getDiscardPile().add(attached);
        }
        card.setMaterials(new ArrayList<>());
        card.setResources(new ArrayList<>());
        leaveField(card);
    }

}
