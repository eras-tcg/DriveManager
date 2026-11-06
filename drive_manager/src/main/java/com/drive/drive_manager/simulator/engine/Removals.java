package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectScript.Limit;
import com.drive.drive_manager.simulator.engine.EffectStep.*;

import java.util.*;

import static com.drive.drive_manager.simulator.engine.EffectEngine.*;
import static com.drive.drive_manager.simulator.engine.Summons.*;
import static com.drive.drive_manager.simulator.engine.SpecialRequirements.*;
import static com.drive.drive_manager.simulator.engine.SpecialSummons.*;
import static com.drive.drive_manager.simulator.engine.Rituals.*;
import static com.drive.drive_manager.simulator.engine.TurnRules.*;
import static com.drive.drive_manager.simulator.engine.CombatRules.*;
import static com.drive.drive_manager.simulator.engine.Chain.*;
import static com.drive.drive_manager.simulator.engine.Resolution.*;
import static com.drive.drive_manager.simulator.engine.StepHelpers.*;
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
        // "solo puede ser afectada por copias de sí misma"
        if (unaffected(card, effect)) {
            log(room, name(card) + " solo puede ser afectada por sus copias: " + effect.getSourceName() + " no le hace nada", null);
            return null;
        }
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
        // Its level as it was on the field ("1 nivel mayor que el aliado sacrificado"): leaving it, it loses its continuous levels
        Integer levelThere = card.getLevel();
        owner.getField().remove(card);
        Combat combat = room.getCombat();
        if (combat != null && owner.getUserId().equals(combat.getDefenderId())) combat.setEnemyDestroyed(true);
        // Its Recursos leave with it, but what they gave it "En Destrucción" still triggers (rule 11.4.0.3)
        reactionsBeforeRemoval(room, owner, card, byId);
        List<String> inherited = card.getResources().stream().map(CardSlot::getCardName).filter(Objects::nonNull).toList();
        List<CardSlot> materials = new ArrayList<>(card.getMaterials());
        boolean hadMaterials = !materials.isEmpty();
        // "si algún aliado fue destruido por el oponente el turno anterior"
        if (byId != null && !byId.equals(owner.getUserId()) && "creature".equals(card.getCardType()))
            room.getUsedThisTurn().add(ALLY_DESTROYED_BY_OPPONENT + owner.getUserId());
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
        if (levelThere != null) data.put("level", levelThere);
        if (byCard == null && effect != null) byCard = effect.getSourceInstanceId();
        if (byCard != null) data.put("byCard", byCard);
        if (!inherited.isEmpty()) data.put("inherited", inherited);
        if (hadMaterials) data.put("hadMaterials", true);
        if (combat != null && card.getInstanceId().equals(combat.getAttackerId())) data.put("asAttacker", true);
        GameEvent removed = record(room, sacrifice ? GameEvent.SACRIFICE : GameEvent.DESTROY, byId, card, "field", toZone, effect, data, text);
        // Its materials went to the Descarte with it ("si esta carta es enviada a tu descarte como material")
        for (CardSlot material : materials) {
            record(room, GameEvent.SEND, owner.getUserId(), material, "materials", "discardPile", null, Map.of("from", card.getInstanceId()), null);
        }
        return removed;
    }

    /**
     * Mandatory reactions of its Recursos before the card is removed (Fósil: "coloca el recurso de este efecto en tu retaguardia con todos los
     * recursos [dinosaurio] de esta carta como sus recursos", only when the opponent removes it).
     */
    static void reactionsBeforeRemoval(GameRoom room, PlayerState owner, CardSlot card, String byId) {
        boolean byOpponent = byId != null && !byId.equals(owner.getUserId());
        for (CardSlot resource : new ArrayList<>(card.getResources())) {
            if (!card.getResources().contains(resource)) continue;
            for (CardEffects.Replacement r : CardEffects.replacementsOf(card)) {
                if (r.cost() != CardEffects.ReplacementCost.RESOURCE_TO_BACK_WITH_OTHERS || (r.byOpponentOnly() && !byOpponent)) continue;
                if (CardEffects.replacementGiver(card, r) != resource) continue;
                card.getResources().remove(resource);
                List<CardSlot> others = card.getResources().stream().filter(r.resourceFilter()::matches).toList();
                card.getResources().removeAll(others);
                resource.getResources().addAll(others);
                resource.setFaceDown(false);
                resource.setTapped(false);
                resource.setX(50.0);
                resource.setY(75.0);
                owner.getField().add(resource);
                record(room, GameEvent.SEND, owner.getUserId(), resource, "resources", "field", null, Map.of("from", card.getInstanceId()),
                        name(resource) + " se coloca en la retaguardia de " + owner.getUsername() + (others.isEmpty() ? "" : " con " + others.size()
                                + " " + plural(others.size(), "recurso", "recursos")) + " antes de que " + name(card) + " sea removida");
            }
        }
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
        if (replacementOptions(room, card, owner, destroy, byId, effect != null).isEmpty()) return false;
        if (room.getPendingRemovals().stream().anyMatch(r -> r.cardId().equals(card.getInstanceId()))) return true;
        room.getPendingRemovals().add(new PendingRemoval(card.getInstanceId(), owner.getUserId(), destroy, cause, byId, byCard,
                effect == null ? null : effect.getId()));
        return true;
    }

    static List<ReplacementOption> replacementOptions(GameRoom room, CardSlot card, PlayerState owner, boolean destroy, String byId, boolean byEffect) {
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
                case ALLY_SPECIAL_SUMMON, GUARD_MATERIAL_AND_SACRIFICE, RESOURCE_TO_BACK_WITH_OTHERS -> false;
                case RESOURCE_TRIBUTED_ANOTHER_TO_HAND -> card.getResources().size() >= 2;
            };
            if (payable) options.add(new ReplacementOption(r.id() + "@" + card.getInstanceId(), card, r, r.label(), r.text()));
        }
        // "cuando algún aliado fuera a ser removido: en su lugar, puede invocar especialmente esta carta" (EXO-91 from the Tributos, SARS-2 from the hand)
        if ("creature".equals(card.getCardType())) {
            for (String z : List.of("hand", "tributeZone")) {
                for (CardSlot c : zone(owner, z)) {
                    CardEffects.AllyRemovalSummon s = CardEffects.allyRemovalSummon(c);
                    if (s == null || !s.zone().equals(z) || !s.ally().matches(card)) continue;
                    CardEffects.Replacement r = s.replacement();
                    if (r.limit() != Limit.NONE && room.getUsedThisTurn().contains(replacementKey(owner, c, r))) continue;
                    if (!SpecialSummons.canSummonInsteadOfRemoval(room, owner, c, card, s.reduce())) continue;
                    options.add(new ReplacementOption(r.id() + "@" + c.getInstanceId(), c, r, r.label(),
                            r.text() + " " + name(card) + " es su invocador."));
                }
            }
        }
        // "cuando uno o más aliados [filtro] fueran a ser removidos por efecto oponente: en su lugar, puedes …" (Albert Collier, itself included)
        if (byOpponent && byEffect) {
            for (CardSlot guard : owner.getField()) {
                CardEffects.AllyGuard g = CardEffects.allyGuard(guard);
                if (g == null || guard.isFaceDown() || !g.allies().matches(card) || guard.getMaterials().isEmpty()) continue;
                CardEffects.Replacement r = g.replacement();
                if (owner.getField().stream().noneMatch(c -> c != card && r.resourceFilter().matches(c))) continue;
                options.add(new ReplacementOption(r.id() + "@" + guard.getInstanceId(), guard, r, r.label(), r.text()));
            }
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
        List<ReplacementOption> options = card == null ? List.of() : replacementOptions(room, card, owner, pending.destroy(), pending.byId(),
                pending.effectId() != null);
        if (options.isEmpty()) {
            room.getPendingRemovals().remove(0);
            if (card != null) executeRemoval(room, pending, owner, card);
            return;
        }
        List<CardSlot> sources = options.stream().map(ReplacementOption::source).distinct().toList();
        rulesPrompt(room, owner.getUserId(), PromptPurpose.REPLACE.id(), name(card) + (pending.destroy() ? " va a ser destruida" : " va a ser removida"),
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
        if (PromptPurpose.REPLACE_MATERIALS.is(prompt.getPurpose())) {
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
        if (PromptPurpose.REPLACE_RESOURCES.is(prompt.getPurpose())) {
            // Sarkhydrax: the first one picked is tributed, the second goes to the hand; then the removal goes on
            if (card == null || picks.size() != 2) return "Elige el recurso que tributas y el que añades a tu mano";
            CardSlot tributed = find(card.getResources(), picks.get(0));
            CardSlot taken = find(card.getResources(), picks.get(1));
            if (tributed == null || taken == null || tributed == taken) return "Elige 2 recursos distintos de " + name(card);
            room.setPrompt(null);
            room.getPendingRemovals().remove(0);
            card.getResources().removeAll(List.of(tributed, taken));
            tributed.setFaceDown(false);
            player.getTributeZone().add(tributed);
            record(room, GameEvent.TRIBUTE, player.getUserId(), tributed, "resources", "tributeZone", null, Map.of("byCard", card.getInstanceId()), null);
            taken.setFaceDown(false);
            player.getHand().add(taken);
            record(room, GameEvent.ADD_TO_HAND, player.getUserId(), taken, "resources", "hand", null, null,
                    player.getUsername() + " tributa " + name(tributed) + " y añade " + name(taken) + " a su mano antes de que " + name(card) + " sea removida");
            executeRemoval(room, pending, player, card);
            return null;
        }
        if (PromptPurpose.REPLACE_GUARD.is(prompt.getPurpose())) {
            // Albert Collier: 1 material of it and 1 [artefacto]; paid once, every ally it guards that this effect was removing stays
            CardSlot guard = find(player.getField(), prompt.getSubjectId());
            CardEffects.AllyGuard g = guard == null ? null : CardEffects.allyGuard(guard);
            if (g == null) {
                room.setPrompt(null);
                room.getPendingRemovals().remove(0);
                if (card != null) executeRemoval(room, pending, player, card);
                return null;
            }
            CardSlot material = picks.stream().map(id -> find(guard.getMaterials(), id)).filter(Objects::nonNull).findFirst().orElse(null);
            CardSlot sacrificed = picks.stream().map(id -> find(player.getField(), id)).filter(c -> c != null && g.replacement().resourceFilter().matches(c))
                    .findFirst().orElse(null);
            if (picks.size() != 2 || material == null || sacrificed == null)
                return "Elige 1 material de " + name(guard) + " y 1 " + g.replacement().resourceFilter().describe() + " bajo tu control";
            room.setPrompt(null);
            guard.getMaterials().remove(material);
            material.setFaceDown(false);
            player.getTributeZone().add(material);
            record(room, GameEvent.TRIBUTE, player.getUserId(), material, "materials", "tributeZone", null, Map.of("byCard", guard.getInstanceId()),
                    player.getUsername() + " tributa " + name(material) + " de los materiales de " + name(guard));
            List<PendingRemoval> saved = room.getPendingRemovals().stream().filter(p -> p.ownerId().equals(player.getUserId())
                    && Objects.equals(p.effectId(), pending.effectId()) && find(player.getField(), p.cardId()) != null
                    && g.allies().matches(find(player.getField(), p.cardId()))).toList();
            room.getPendingRemovals().removeAll(saved);
            log(room, name(guard) + ": " + String.join(", ", saved.stream().map(p -> name(find(player.getField(), p.cardId()))).toList())
                    + " no " + (saved.size() == 1 ? "es removida" : "son removidas"), null);
            destroy(room, sacrificed, player, true, CAUSE_EFFECT, player.getUserId(), guard.getInstanceId(), null);
            return null;
        }
        if (PromptPurpose.REPLACE_HAND.is(prompt.getPurpose())) {
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
        List<ReplacementOption> options = replacementOptions(room, card, player, pending.destroy(), pending.byId(), pending.effectId() != null);
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
            case RESOURCE_TRIBUTED_ANOTHER_TO_HAND -> {
                rulesPrompt(room, player.getUserId(), PromptPurpose.REPLACE_RESOURCES.id(), name(card) + " · " + r.label(),
                        "Elige primero el recurso que tributas y después el que añades a tu mano; luego " + name(card) + " es removida.",
                        "Elegir", card.getResources(), card.getResources());
                room.getPrompt().setMin(2);
                room.getPrompt().setMax(2);
                return null;
            }
            case GUARD_MATERIAL_AND_SACRIFICE -> {
                CardSlot guard = chosen.source();
                List<CardSlot> valid = new ArrayList<>(guard.getMaterials());
                valid.addAll(player.getField().stream().filter(c -> c != card && r.resourceFilter().matches(c)).toList());
                rulesPrompt(room, player.getUserId(), PromptPurpose.REPLACE_GUARD.id(), name(guard) + " · " + r.label(),
                        "Tributa 1 material de " + name(guard) + " y sacrifica 1 " + r.resourceFilter().describe() + " bajo tu control: "
                                + "no se remueve ningún aliado [" + String.join(", ", CardEffects.allyGuard(guard).allies().classes()) + "] de este efecto.",
                        "Pagar", valid, valid);
                room.getPrompt().setMin(2);
                room.getPrompt().setMax(2);
                room.getPrompt().setSubjectId(guard.getInstanceId());
                return null;
            }
            case ALLY_SPECIAL_SUMMON -> {
                // A regular special summon of that card, the ally as its summoner: the ally is not removed
                room.getPendingRemovals().remove(0);
                log(room, name(card) + " no es removida: en su lugar, invoca especialmente a " + name(chosen.source()), null);
                SpecialSummons.summonInsteadOfRemoval(room, player, chosen.source(), card);
                return null;
            }
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
                rulesPrompt(room, player.getUserId(), PromptPurpose.REPLACE_MATERIALS.id(), name(card) + " · " + r.label(),
                        "Coloca hasta 2 de estas criaturas en tu retaguardia; luego " + name(card) + " es destruida.", "A la retaguardia", valid, card.getMaterials());
                room.getPrompt().setMin(1);
                room.getPrompt().setMax(Math.min(2, valid.size()));
                return null;
            }
            case TRIBUTE_ONE_FROM_HAND -> {
                // The hand card is chosen next; the removal stays pending until then
                rulesPrompt(room, player.getUserId(), PromptPurpose.REPLACE_HAND.id(), name(card) + " · " + r.label(),
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
