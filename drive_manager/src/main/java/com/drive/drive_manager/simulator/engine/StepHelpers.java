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
import static com.drive.drive_manager.simulator.engine.Summons.*;
import static com.drive.drive_manager.simulator.engine.SpecialRequirements.*;
import static com.drive.drive_manager.simulator.engine.SpecialSummons.*;
import static com.drive.drive_manager.simulator.engine.Rituals.*;
import static com.drive.drive_manager.simulator.engine.Removals.*;
import static com.drive.drive_manager.simulator.engine.TurnRules.*;
import static com.drive.drive_manager.simulator.engine.CombatRules.*;
import static com.drive.drive_manager.simulator.engine.Chain.*;
import static com.drive.drive_manager.simulator.engine.Answers.*;

/** What the steps of an effect share. */
public final class StepHelpers {

    private StepHelpers() {}

    /** Allies "sacrifica 1 [otro] aliado" may take. */
    static List<CardSlot> sacrificable(PendingEffect effect, PlayerState player, SacrificeAlly sacrifice) {
        List<CardSlot> all = sacrifice.toUntapAnother() ? sacrificeToUntap(player) : allies(player);
        return sacrifice.other() ? all.stream().filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId())).toList() : all;
    }

    static void pick(GameRoom room, PendingEffect effect, String instruction, String chosenLabel,
                             List<CardSlot> cards, List<String> validIds, int count) {
        Prompt prompt = prompt(room, effect, Prompt.Kind.PICK, instruction);
        prompt.setCards(new ArrayList<>(cards));
        prompt.setValidIds(new ArrayList<>(validIds));
        prompt.setMin(count);
        prompt.setMax(count);
        prompt.setChosenLabel(chosenLabel);
        room.setPrompt(prompt);
    }

    static Prompt prompt(GameRoom room, PendingEffect effect, Prompt.Kind kind, String instruction) {
        Prompt p = new Prompt();
        p.setId(room.nextId("prompt"));
        p.setKind(kind);
        p.setPlayerId(effect.getControllerId());
        p.setEffectId(effect.getId());
        p.setSourceName(effect.getSourceName());
        CardSlot source = findAnywhere(room, effect.getControllerId(), effect.getSourceInstanceId());
        p.setSourceImageUrl(source == null ? null : source.getImageUrl());
        p.setTitle((effect.getLink() > 0 ? "Cadena " + effect.getLink() + " · " : "") + effect.getSourceName() + " · " + effect.getLabel());
        p.setEffectText(effect.getText());
        p.setInstruction(instruction);
        return p;
    }

    /**
     * Enemies the effect may choose: the opponent's Línea frontal (rule 4.0.6.2), matching the filter. A creature
     * in the Retaguardia cannot affect enemies with its effects.
     */
    static List<CardSlot> enemyTargets(GameRoom room, PendingEffect effect, CardFilter filter) {
        if (inRetaguardia(room, effect)) return List.of();
        PlayerState opponent = room.getPlayer(room.getOpponentId(effect.getControllerId()));
        if (opponent == null) return List.of();
        int level = sourceLevel(room, effect);
        return frontLine(opponent.getField()).stream().filter(c -> "creature".equals(c.getCardType())).filter(filter::matches)
                .filter(c -> !immuneTo(c, level)).toList();
    }

    /**
     * Reduces an enemy's strength by an effect, until the end of this turn or of the next one; at 0 it is
     * destroyed by that effect (rule 12.0.0.2). Records it: "si reduces la fuerza …" effects trigger from it.
     */
    static void reduceStrength(GameRoom room, PendingEffect effect, PlayerState owner, CardSlot target, int amount, boolean untilNextTurn) {
        if (untilNextTurn) target.setLaterStrength(target.getLaterStrength() - amount);
        else target.setTurnStrength(target.getTurnStrength() - amount);
        floating(target, effect, "Pierde " + amount + " de fuerza hasta el final del " + (untilNextTurn ? "próximo turno" : "turno"),
                untilNextTurn ? "nextTurn" : "turn", -amount, null);
        int now = Math.max(0, strength(target));
        record(room, GameEvent.STRENGTH_REDUCED, effect.getControllerId(), target, null, null, effect,
                Map.of("amount", amount, "to", now, "ownerId", owner.getUserId(), "untilNextTurn", untilNextTurn),
                name(target) + " pierde " + amount + " de fuerza (" + effect.getSourceName() + "): queda en " + now);
        if (now <= 0) destroy(room, target, owner, false, CAUSE_EFFECT, effect.getControllerId(), null, effect);
    }

    /** Direct damage by an effect to the opponent's top life: a creature with that strength or less is tributed, the rest go to the hand. */
    static void damageByEffect(GameRoom room, PendingEffect effect, PlayerState player, int damage) {
        damageByEffect(room, effect, player, damage, true);
    }

    /** {@code offer}: whether "en su lugar" of the damage (Estoque) is offered to the defender first; the damage then waits for the answer. */
    static void damageByEffect(GameRoom room, PendingEffect effect, PlayerState player, int damage, boolean offer) {
        PlayerState defender = room.getPlayer(room.getOpponentId(player.getUserId()));
        if (defender == null || defender.getLifeStack().isEmpty()) return;
        if (offer && !CombatRules.damageTributeCards(defender).isEmpty()) {
            room.getUsedThisTurn().add(PENDING_EFFECT_DAMAGE + effect.getId() + "|" + damage + "|" + player.getUserId());
            CombatRules.offerDamageTribute(room, defender, damage, "effect");
            return;
        }
        CardSlot life = defender.getLifeStack().remove(0);
        life.setFaceDown(false);
        boolean tribute = "creature".equals(life.getCardType()) && (life.getStrength() == null || CardEffects.lifeStrength(room, defender.getUserId(), life) <= damage);
        (tribute ? defender.getTributeZone() : defender.getHand()).add(life);
        record(room, GameEvent.LIFE_DAMAGE, player.getUserId(), life, "lifeStack", tribute ? "tributeZone" : "hand", effect,
                Map.of("damage", damage, "attackerId", effect.getSourceInstanceId(), "defenderId", defender.getUserId()),
                effect.getSourceName() + " inflige " + damage + " de daño directo: " + defender.getUsername() + " pierde la vida "
                        + name(life) + (tribute ? ", que va a sus tributos" : ", que va a su mano"));
        if (defender.getLifeStack().isEmpty()) loseByLives(room, defender);
    }

    /** Allies "elige 1 aliado" may choose; for an attack, only those in the Línea frontal (rule 10.1.1.1). */
    /** The rule of a special summon whose summoner is the ally chosen before; null for any other step. */
    static SpecialRule subjectSummon(EffectStep step) {
        if (!(step instanceof Do d) || !(d.action() instanceof Action.Sequence seq)) return null;
        for (Action a : seq.actions()) {
            if (a instanceof Action.SummonSpecial summon && "subject".equals(summon.rule().summoner())) return summon.rule();
        }
        return null;
    }

    static List<CardSlot> pickable(GameRoom room, PendingEffect effect, PlayerState player, boolean attacker) {
        List<EffectStep> steps = new ArrayList<>(script(effect).cost());
        steps.addAll(script(effect).steps());
        List<CardSlot> valid = allies(player).stream().filter(c -> !attacker || inFrontLine(c))
                // "añade esta carta como material a un aliado": another one
                .filter(c -> steps.stream().noneMatch(s -> s instanceof Do d && d.action() instanceof Action.Move m && "materials:subject".equals(m.to())) || !c.getInstanceId().equals(effect.getSourceInstanceId()))
                // "elige 1 aliado: invoca 1 criatura del mismo nivel": one with something to summon
                .filter(c -> steps.stream().noneMatch(s -> s instanceof SummonLikeSubject like && likeSubject(player, like, c).isEmpty()))
                .toList();
        // "a un aliado para que invoque por …": only an ally that meets the Requerimiento of something it could summon
        List<EffectStep> resolution = script(effect).steps();
        // Its rule, as it reads now, in the sequence that summons
        EffectStep summonStep = resolution.stream().filter(s -> subjectSummon(s) != null).findFirst().orElse(null);
        SpecialRule special = summonStep == null ? null : SpecialProcedure.resolve(room, effect, subjectSummon(summonStep));
        // Not when that summon is conditional ("Si lo haces: dicho aliado puede invocar …")
        if (special == null || conditional(resolution, resolution.indexOf(summonStep))) return valid;
        String chosen = effect.getSubjectId();
        List<CardSlot> result = valid.stream().filter(ally -> {
            effect.setSubjectId(ally.getInstanceId());
            return !specialTargets(room, effect, special).isEmpty();
        }).toList();
        effect.setSubjectId(chosen);
        return result;
    }

    /** The effect's card is a creature in its owner's Retaguardia. */
    static boolean inRetaguardia(GameRoom room, PendingEffect effect) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        CardSlot source = player == null ? null : find(player.getField(), effect.getSourceInstanceId());
        return source != null && "creature".equals(source.getCardType()) && !inFrontLine(source);
    }

    /** A creature on the field leaves it (not destroyed): its Recursos and Materiales go to the Descarte and it loses its state. */

    /** The materials of your creatures on the field. */
    static List<CardSlot> allyMaterialsWhere(PlayerState player, TributeAllyMaterialWhere t) {
        return allies(player).stream().filter(t.ally()::matches).flatMap(a -> a.getMaterials().stream()).filter(t.material()::matches).toList();
    }

    static List<CardSlot> allyMaterials(PlayerState player) {
        return allies(player).stream().flatMap(a -> a.getMaterials().stream()).toList();
    }

    /** Cards "añade 1 … como recurso de esta carta" may take: from those zones, matching, not this card. */
    /** Allies that may be sacrificed "para reactivar otro": another ally that can be reactivated must remain. */
    static List<CardSlot> sacrificeToUntap(PlayerState player) {
        List<CardSlot> allies = allies(player);
        return allies.stream().filter(s -> allies.stream().anyMatch(r -> r != s && reactivable(r))).toList();
    }

    /** The card the steps act on: the ally chosen by "elige 1 aliado", or the effect's own card. */
    static CardSlot subject(GameRoom room, PendingEffect effect) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return null;
        return find(player.getField(), effect.getSubjectId() != null ? effect.getSubjectId() : effect.getSourceInstanceId());
    }

    /**
     * The targets chosen on activation that are still valid: still in the opponent's Línea frontal (rule 11.10.2). Those
     * that are not are logged and skipped.
     */
    static List<CardSlot> validTargets(GameRoom room, PendingEffect effect, PlayerState opponent) {
        List<CardSlot> valid = new ArrayList<>();
        for (String id : effect.getTargetIds()) {
            CardSlot target = opponent == null ? null : frontLineCard(opponent, id);
            if (target != null) valid.add(target);
            else log(room, "El objetivo de " + effect.getSourceName() + " ya no es válido", null);
        }
        return valid;
    }

    /** Asks which of the looked cards go to the hand (up to {@code max} of {@code valid}). */
    static void takeFromLooked(GameRoom room, PendingEffect effect, Prompt.Kind kind, String instruction, List<String> valid, int max) {
        Prompt prompt = prompt(room, effect, kind, instruction);
        prompt.setCards(new ArrayList<>(effect.getLooked()));
        prompt.setValidIds(new ArrayList<>(valid));
        prompt.setMin(0);
        prompt.setMax(Math.min(max, valid.size()));
        prompt.setChosenLabel("A tu mano");
        room.setPrompt(prompt);
    }

    /** The chosen looked cards go to the hand. */
    static void lookedToHand(GameRoom room, PendingEffect effect, PlayerState player, List<String> picks) {
        for (String id : picks) {
            CardSlot card = find(effect.getLooked(), id);
            effect.getLooked().remove(card);
            card.setFaceDown(false);
            player.getHand().add(card);
            record(room, GameEvent.ADD_TO_HAND, player.getUserId(), card, "deck", "hand", effect, null,
                    player.getUsername() + " añade " + name(card) + " a su mano (" + effect.getSourceName() + ")");
        }
    }

    /** The chosen hand cards go to the Tributos; they are the last tributed, and how many is the last count. */
    static void tributeFromHand(GameRoom room, PendingEffect effect, PlayerState player, List<String> picks) {
        for (String id : picks) {
            CardSlot card = find(player.getHand(), id);
            player.getHand().remove(card);
            card.setFaceDown(false);
            player.getTributeZone().add(card);
            record(room, GameEvent.TRIBUTE, player.getUserId(), card, "hand", "tributeZone", effect, null,
                    player.getUsername() + " tributa " + name(card) + " de su mano (" + effect.getSourceName() + ")");
        }
        effect.setLastTributed(new ArrayList<>(picks));
        effect.setLastCount(picks.size());
    }

    /** This card leaves its zone to become a material ({@code asMaterial}) or a Recurso of the subject. */
    static void attachSelfToSubject(GameRoom room, PendingEffect effect, PlayerState player, boolean asMaterial) {
        Located at = locate(player, effect.getSourceInstanceId());
        CardSlot self = at == null ? null : at.card();
        CardSlot ally = subject(room, effect);
        if (self == null || ally == null || self == ally) return;
        if ("field".equals(at.zone())) {
            player.getField().remove(self);
            leaveField(self);
        } else {
            zone(player, at.zone()).remove(self);
            self.setFaceDown(false);
        }
        (asMaterial ? ally.getMaterials() : ally.getResources()).add(self);
        effect.setLastDone(true);
        record(room, GameEvent.SEND, player.getUserId(), self, at.zone(), asMaterial ? "materials" : "resources", effect,
                Map.of("to", ally.getInstanceId()),
                player.getUsername() + " añade " + name(self) + " como " + (asMaterial ? "material" : "recurso") + " de " + name(ally));
    }

    /** Allies with another ally of higher level ("1 aliado como material de otro de nivel mayor"). */
    static List<CardSlot> withHigherAlly(PlayerState player) {
        return allies(player).stream().filter(c -> !higherThan(player, c).isEmpty()).toList();
    }

    /** The player's other creatures of a higher level than {@code card}. */
    static List<CardSlot> higherThan(PlayerState player, CardSlot card) {
        int level = card.getLevel() == null ? 0 : card.getLevel();
        return allies(player).stream().filter(c -> c != card && c.getLevel() != null && c.getLevel() > level).toList();
    }

    /** Whether the step at {@code index} is covered by an earlier connector ("Si lo haces:", "puedes …", "Si …:"). */
    static boolean conditional(List<EffectStep> steps, int index) {
        for (int i = 0; i < index; i++) {
            int covers = Chain.covers(steps.get(i));
            if (covers > 0 && index <= i + covers) return true;
        }
        return false;
    }

    /** The level of the effect's card (0 when it has none), for "efectos de cartas nivel N o menor". */
    static int sourceLevel(GameRoom room, PendingEffect effect) {
        CardSlot source = findAnywhere(room, effect.getControllerId(), effect.getSourceInstanceId());
        return source == null || source.getLevel() == null ? 0 : source.getLevel();
    }

    /** The cards of one or more of the player's zones ("tributeZone,discardPile"). */
    static List<CardSlot> zones(PlayerState player, String csv) {
        List<CardSlot> all = new ArrayList<>();
        for (String z : csv.split(",")) all.addAll(zone(player, z.trim()));
        return all;
    }

    /** Which of those zones holds the card. */
    static String zoneOf(PlayerState player, String csv, CardSlot card) {
        for (String z : csv.split(",")) if (zone(player, z.trim()).contains(card)) return z.trim();
        return csv;
    }

    /** "1 criatura en el campo": yours, or an enemy's in its Línea frontal when this card is in yours (and it is not immune). */
    static List<CardSlot> creaturesOnField(GameRoom room, PendingEffect effect, CardFilter filter) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return List.of();
        List<CardSlot> all = new ArrayList<>(player.getField().stream()
                .filter(c -> "creature".equals(c.getCardType()) && !c.getInstanceId().equals(effect.getSourceInstanceId()) && filter.matches(c)).toList());
        all.addAll(enemyTargets(room, effect, filter));
        return all;
    }

    /** Creatures of the zone the effect may summon: with a normal cost the controller can pay after the reduction. */
    /** Creatures of the zone of the ally's level and another name (summoned free). */
    static List<CardSlot> likeSubject(PlayerState player, SummonLikeSubject like, CardSlot ally) {
        if (ally.getLevel() == null) return List.of();
        return zone(player, like.zone()).stream()
                .filter(c -> "creature".equals(c.getCardType()) && c.getCost() != null && like.filter().matches(c))
                .filter(c -> ally.getLevel().equals(c.getLevel()) && !name(c).equalsIgnoreCase(name(ally)))
                .toList();
    }

    /** Creatures that fit within the level sum on their own (summoned free). */
    static List<CardSlot> freeLevelSum(GameRoom room, PendingEffect effect, SummonFreeLevelSum sum) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return List.of();
        return zone(player, sum.zone()).stream()
                .filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId()))
                .filter(c -> "creature".equals(c.getCardType()) && c.getCost() != null && c.getLevel() != null)
                .filter(sum.filter()::matches)
                .filter(c -> c.getLevel() <= sum.maxLevelSum())
                .toList();
    }

    static boolean allIn(List<CardSlot> cards, List<String> ids) {
        return ids.stream().allMatch(id -> find(cards, id) != null);
    }

    /** {@code cards} in the order given by {@code order}; ids missing from it keep their place at the end. */
    /** A card's level as it was on the field when it was last destroyed or sacrificed (its continuous levels included). */
    static int levelWhenRemoved(GameRoom room, CardSlot card) {
        if (card == null) return 0;
        List<GameEvent> timeline = room.getTimeline();
        for (int i = timeline.size() - 1; i >= 0; i--) {
            GameEvent e = timeline.get(i);
            if (!card.getInstanceId().equals(e.getCardInstanceId())) continue;
            if ((GameEvent.DESTROY.equals(e.getType()) || GameEvent.SACRIFICE.equals(e.getType())) && e.getData() != null
                    && e.getData().get("level") instanceof Integer level) return level;
        }
        return card.getLevel() == null ? 0 : card.getLevel();
    }

    static List<CardSlot> inOrder(List<CardSlot> cards, List<String> order) {
        List<CardSlot> result = new ArrayList<>();
        if (order != null) {
            for (String id : order) {
                CardSlot card = find(cards, id);
                if (card != null && !result.contains(card)) result.add(card);
            }
        }
        for (CardSlot card : cards) if (!result.contains(card)) result.add(card);
        return result;
    }

    /** The mark of an effect that goes on at the end of the turn: controller, card, script and subject ({@link AtEndOfTurn}). */
    static final String DELAYED = "delayed|";

    /** A card placed on the field by an effect (not summoned): face up, in the Línea frontal or the Retaguardia. */
    /** Materials of the exhausted allies that match. */
    static List<CardSlot> exhaustedAllyMaterials(PlayerState player, CardFilter filter) {
        return allies(player).stream().filter(CardSlot::isTapped).flatMap(a -> a.getMaterials().stream()).filter(filter::matches).toList();
    }

    static void placeOnField(GameRoom room, PendingEffect effect, PlayerState owner, CardSlot card, String fromZone, boolean toFront, boolean exhausted) {
        zone(owner, fromZone).remove(card);
        card.setFaceDown(false);
        card.setTapped(exhausted);
        card.setX(50.0);
        card.setY(toFront ? 25.0 : 75.0);
        owner.getField().add(card);
        record(room, GameEvent.SEND, effect.getControllerId(), card, fromZone, "field", effect, Map.of("ownerId", owner.getUserId(), "frontLine", toFront),
                owner.getUsername() + " coloca " + name(card) + " en su " + (toFront ? "línea frontal" : "retaguardia") + (exhausted ? ", agotada" : "")
                        + " (" + effect.getSourceName() + ")");
    }

    /** "gana fuerza N hasta el final del turno": the subject. */
    static void gainStrength(GameRoom room, PendingEffect effect, int amount) {
        CardSlot subject = subject(room, effect);
        if (subject == null || amount == 0) return;
        subject.setTurnStrength(subject.getTurnStrength() + amount);
        floating(subject, effect, "Gana fuerza " + amount + " hasta el final del turno", "turn", amount, null);
        record(room, GameEvent.GAIN, effect.getControllerId(), subject, null, null, effect, Map.of("strength", amount),
                name(subject) + " gana fuerza " + amount + " hasta el final del turno");
    }

    /** Allies of the Línea frontal that can declare an attack and match. */
    static List<CardSlot> attackers(PlayerState player, CardFilter filter) {
        return allies(player).stream().filter(c -> inFrontLine(c) && filter.matches(c) && !keywords(c).contains(NO_ATTACK)).toList();
    }

    /** One different card for each filter (a small matching: every filter is tried with every card). */
    static boolean assignable(List<CardSlot> cards, List<CardFilter> filters) {
        if (filters.isEmpty()) return true;
        CardFilter first = filters.get(0);
        for (CardSlot c : cards) {
            if (!first.matches(c)) continue;
            List<CardSlot> rest = new ArrayList<>(cards);
            rest.remove(c);
            if (assignable(rest, filters.subList(1, filters.size()))) return true;
        }
        return false;
    }

    /** Copies of the effect's card in its owner's Retaguardia (the card included). */
    static List<CardSlot> copiesInBack(PlayerState player, PendingEffect effect) {
        return player.getField().stream().filter(c -> !inFrontLine(c) && c.getCardName() != null
                && c.getCardName().equalsIgnoreCase(effect.getSourceName())).toList();
    }

    /** Cards of the zone that can be a Recurso of an ally of a higher level. */
    static List<CardSlot> higherAllyResources(PlayerState player, AddResourceToHigherAlly a) {
        return zones(player, a.zone()).stream().filter(a.filter()::matches).filter(c -> !higherThan(player, c).isEmpty()).toList();
    }

    /** Some of these enemies add up to exactly {@code sum} in level. */
    static boolean levelSumPossible(List<CardSlot> cards, int sum) {
        boolean[] reach = new boolean[sum + 1];
        reach[0] = true;
        for (CardSlot c : cards) {
            int level = c.getLevel() == null ? 0 : c.getLevel();
            if (level <= 0) continue;
            for (int s = sum; s >= level; s--) if (reach[s - level]) reach[s] = true;
        }
        return reach[sum];
    }

    /** "N criaturas en la línea frontal": yours, and the enemies this effect can affect. */
    static List<CardSlot> frontLineCreatures(GameRoom room, PendingEffect effect, CardFilter filter) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return List.of();
        List<CardSlot> all = new ArrayList<>(frontLine(player.getField()).stream().filter(c -> "creature".equals(c.getCardType()) && filter.matches(c)).toList());
        all.addAll(enemyTargets(room, effect, filter));
        return all;
    }

    /** "1 criatura en el campo con fuerza menor a esta carta". */
    static List<CardSlot> weakerThanSelf(GameRoom room, PendingEffect effect) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        CardSlot self = player == null ? null : find(player.getField(), effect.getSourceInstanceId());
        if (self == null) return List.of();
        return creaturesOnField(room, effect, CardFilter.creatures().withMaxStrength(strength(self) - 1));
    }

    /** The mark of a creature that goes back to its owner's hand (with its materials) when the attack ends ({@link ReturnSubjectAtAttackEnd}). */
    static final String RETURN_AT_ATTACK_END = "return-at-attack-end|";

    /** The creature the effect's card clashes with in the attack going on (the blocker, or the attacker when it blocks). */
    static CardSlot clashOpponent(GameRoom room, PendingEffect effect) {
        Combat c = room.getCombat();
        if (c == null) return null;
        String self = effect.getSourceInstanceId();
        String other = self.equals(c.getAttackerId()) ? c.getBlockerId() : self.equals(c.getBlockerId()) ? c.getAttackerId() : null;
        return other == null ? null : findInRoom(room, other);
    }

    /** The last card with that name in the zone (the one that just went there). */
    static CardSlot lastNamed(List<CardSlot> zone, String name) {
        for (int i = zone.size() - 1; i >= 0; i--) if (name.equalsIgnoreCase(zone.get(i).getCardName())) return zone.get(i);
        return null;
    }

    /** The chosen cards can each go to a different filter ("hasta 1 X y hasta 1 Y"). */
    static boolean assignableEach(List<CardFilter> filters, List<CardSlot> cards) {
        if (cards.isEmpty()) return true;
        CardSlot first = cards.get(0);
        for (int i = 0; i < filters.size(); i++) {
            if (!filters.get(i).matches(first)) continue;
            List<CardFilter> rest = new ArrayList<>(filters);
            rest.remove(i);
            if (assignableEach(rest, cards.subList(1, cards.size()))) return true;
        }
        return false;
    }

    /** Creatures of those zones that can be the declared summoner of {@code card}: its level, sharing a type or color, with its materials there. */
    static List<CardSlot> declaredFrom(PlayerState player, CardSlot card, SpecialRequirements.SpecialRequirement req, String zones) {
        return zones(player, zones).stream()
                .filter(c -> c != card && "creature".equals(c.getCardType()) && SpecialRequirements.optionFor(req, c) != null && shares(c, card))
                .filter(c -> {
                    List<CardSlot> materials = declaredMaterials(player, card, req, c);
                    return req.levelSum() > 0 ? sumsExactly(materials, req.levelSum()) : materials.size() >= Math.max(1, req.materialCount());
                })
                .toList();
    }

    /** The materials of {@code card} besides that summoner, in the zones of its Requerimiento ("materiales que sumen 3 en tus tributos"). */
    static List<CardSlot> declaredMaterials(PlayerState player, CardSlot card, SpecialRequirements.SpecialRequirement req, CardSlot summoner) {
        List<CardSlot> all = new ArrayList<>();
        for (String z : req.materialZones()) all.addAll(zone(player, z));
        return all.stream().filter(c -> c != card && c != summoner && "creature".equals(c.getCardType()) && c.getLevel() != null && shares(c, card)
                && (req.levelSum() > 0 || hasLevel(c, req.materialLevel()))).toList();
    }

    /** "tus tributos o descarte". */
    static String zonesText(String csv) {
        return String.join(" o ", Arrays.stream(csv.split(",")).map(z -> zoneName(z.trim())).toList());
    }
}
