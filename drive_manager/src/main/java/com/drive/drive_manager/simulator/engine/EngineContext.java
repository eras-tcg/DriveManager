package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.effects.model.Action;
import com.drive.drive_manager.simulator.engine.effects.model.Memory;
import com.drive.drive_manager.simulator.engine.effects.model.Selector;
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The game as actions see it. While old steps and new actions live together (until phase 7 of the refactor), the memory's
 * well-known names are the fields the old steps read, so one effect can mix both.
 */
final class EngineContext implements EffectContext {

    private final GameRoom room;
    private final PendingEffect effect;

    /** The materials chosen for a special summon. */
    static final String SPECIAL_MATERIALS = "specialMaterials";
    /** The creature and the summoner of a special summon: fields the rules read while it is chosen. */
    static final String SPECIAL_CARD = "specialCard";
    static final String SPECIAL_SUMMONER = "specialSummoner";

    private final boolean[] repeat;

    EngineContext(GameRoom room, PendingEffect effect) {
        this(room, effect, new boolean[1]);
    }

    EngineContext(GameRoom room, PendingEffect effect, boolean[] repeat) {
        this.room = room;
        this.effect = effect;
        this.repeat = repeat;
    }

    @Override
    public int sequenceIndex() {
        return effect.getSequenceIndex();
    }

    @Override
    public void sequenceIndex(int index) {
        effect.setSequenceIndex(index);
    }

    @Override
    public void limitLevelSum(int levels) {
        room.getPrompt().setMaxLevelSum(levels);
    }

    @Override
    public List<CardSlot> pickable(String zone) {
        return switch (zone) {
            case "attackers" -> StepHelpers.attackers(player(), CardFilter.any());
            case "pickableAttackers" -> StepHelpers.pickable(room, effect, player(), true);
            default -> StepHelpers.pickable(room, effect, player(), false);
        };
    }

    @Override
    public void endEffect() {
        effect.setStepIndex(EffectEngine.script(effect).steps().size() - 1);
    }

    @Override
    public void asksTopOrBottom() {
        room.getPrompt().setReorderRest(true);
        room.getPrompt().setTopOrBottom(true);
    }

    @Override
    public boolean attack(boolean opponentOnly) {
        return CombatRules.attackByEffect(room, effect, opponentOnly);
    }

    @Override
    public void attackChosen(List<String> picks) {
        CardSlot subject = StepHelpers.subject(room, effect);
        if (subject != null && room.getCombat() == null) {
            CombatRules.startCombat(room, player().getUserId(), subject, picks.isEmpty() ? null : picks.get(0), true, effect);
        }
    }

    @Override
    public void atEndOfTurn(String script) {
        String subject = effect.getSummonedId() != null ? effect.getSummonedId() : effect.getSubjectId() != null ? effect.getSubjectId() : "";
        room.getUsedThisTurn().add(StepHelpers.DELAYED + player().getUserId() + "|" + effect.getSourceInstanceId() + "|" + script + "|" + subject);
        EffectEngine.log(room, effect.getSourceName() + ": al final del turno se resolverá el resto de su efecto", null);
    }

    @Override
    public void goOn() {
        repeat[0] = true;
    }

    @Override
    public List<CardSlot> specialOptions(Selector.Special special) {
        return SpecialProcedure.options(room, effect, special);
    }

    @Override
    public List<CardSlot> specialShown(Selector.Special special) {
        return SpecialProcedure.shown(room, effect, special);
    }

    @Override
    public int[] specialBounds(Selector.Special special, int available) {
        return SpecialProcedure.bounds(room, effect, special, available);
    }

    @Override
    public String specialInstruction(Selector.Special special) {
        return SpecialProcedure.instruction(room, effect, special);
    }

    @Override
    public String specialCheck(Selector.Special special, List<String> picks) {
        return SpecialProcedure.checkMaterials(room, effect, special, picks);
    }

    @Override
    public void summonSpecial(com.drive.drive_manager.simulator.engine.effects.model.SpecialRule rule) {
        SpecialProcedure.summon(room, effect, rule);
    }

    @Override
    public PlayerState player() {
        return room.getPlayer(effect.getControllerId());
    }

    @Override
    public PlayerState opponent() {
        return room.getPlayer(room.getOpponentId(effect.getControllerId()));
    }

    @Override
    public boolean isSelf(CardSlot card) {
        return card.getInstanceId().equals(effect.getSourceInstanceId());
    }

    @Override
    public boolean isOpponents(CardSlot card) {
        PlayerState opponent = opponent();
        return opponent != null && EffectEngine.find(opponent.getField(), card.getInstanceId()) != null;
    }

    @Override
    public boolean isCreature(CardSlot card) {
        return "creature".equals(card.getCardType());
    }

    @Override
    public List<CardSlot> attached(String zone) {
        CardSlot host = zone.endsWith(":subject") ? StepHelpers.subject(room, effect) : EffectEngine.find(player().getField(), effect.getSourceInstanceId());
        if (host == null) return List.of();
        return zone.startsWith("materials") ? host.getMaterials() : host.getResources();
    }

    @Override
    public String describeZones(String zones) {
        List<String> names = new ArrayList<>();
        for (String raw : zones.split(",")) {
            String zone = raw.trim();
            if (zone.startsWith("materials")) names.add("los materiales");
            else if (zone.startsWith("resources")) names.add("los recursos");
            else names.add("hand".equals(zone) || "deck".equals(zone) ? "tu " + EffectEngine.zoneName(zone) : "tus " + EffectEngine.zoneName(zone));
        }
        return String.join(" o ", names);
    }

    @Override
    public boolean alreadyIn(CardSlot card, String to) {
        Origin origin = origin(card);
        if (origin == null) return false;
        if ("field".equals(origin.zone())) {
            if (Action.Move.FRONT.equals(to)) return EffectEngine.inFrontLine(card);
            if (Action.Move.BACK.equals(to)) return !EffectEngine.inFrontLine(card);
        }
        return !origin.looked() && origin.zone().equals(destination(to));
    }

    /** Where a card is now, and the card it is attached to, if any. */
    private record Origin(PlayerState owner, String zone, CardSlot host, boolean looked) {}

    private Origin origin(CardSlot card) {
        if (EffectEngine.find(effect.getLooked(), card.getInstanceId()) != null) return new Origin(player(), "deck", null, true);
        for (String id : List.of(effect.getControllerId(), room.getOpponentId(effect.getControllerId()) == null ? "" : room.getOpponentId(effect.getControllerId()))) {
            PlayerState p = room.getPlayer(id);
            if (p == null) continue;
            EffectEngine.Located at = EffectEngine.locate(p, card.getInstanceId());
            if (at != null) return new Origin(p, at.zone(), null, false);
            for (CardSlot host : p.getField()) {
                if (EffectEngine.find(host.getMaterials(), card.getInstanceId()) != null) return new Origin(p, "materials", host, false);
                if (EffectEngine.find(host.getResources(), card.getInstanceId()) != null) return new Origin(p, "resources", host, false);
            }
        }
        return null;
    }

    private static String destination(String to) {
        if (Action.Move.LIFE_TOP.equals(to)) return "lifeStack";
        return Action.Move.DECK_TOP.equals(to) || Action.Move.DECK_BOTTOM.equals(to) ? "deck" : to;
    }

    @Override
    public List<CardSlot> move(List<CardSlot> cards, String to, boolean exhausted, boolean faceDown) {
        String zone = destination(to);
        List<CardSlot> done = new ArrayList<>();
        String from = null;
        for (CardSlot card : cards) {
            Origin origin = origin(card);
            if (origin == null) continue;
            CardSlot host = hostIn(to);
            if (isAttachment(to) && (host == null || host == card)) continue;
            from = origin.zone();
            boolean enemy = !origin.owner().getUserId().equals(effect.getControllerId());
            if (enemy && "field".equals(from) && EffectEngine.keywords(card).contains(EffectEngine.UNREMOVABLE)) {
                EffectEngine.log(room, EffectEngine.name(card) + " no puede ser removida por efectos oponentes", null);
                continue;
            }
            // Moving within the field is not leaving it
            if ("field".equals(from) && (Action.Move.FRONT.equals(to) || Action.Move.BACK.equals(to))) {
                card.setY(Action.Move.FRONT.equals(to) ? 25.0 : 75.0);
                EffectEngine.record(room, GameEvent.MOVE, player().getUserId(), card, null, to, effect, Map.of("reason", "effect"), null);
                done.add(card);
                continue;
            }
            if (origin.looked()) effect.getLooked().remove(card);
            else if (origin.host() != null) ("materials".equals(from) ? origin.host().getMaterials() : origin.host().getResources()).remove(card);
            else if ("field".equals(from)) Removals.removeFromField(origin.owner(), card);
            else EffectEngine.zone(origin.owner(), from).remove(card);
            card.setFaceDown("deck".equals(zone) || faceDown);
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("byCard", origin.host() != null ? origin.host().getInstanceId() : effect.getSourceInstanceId());
            data.put("ownerId", origin.owner().getUserId());
            if ("deck".equals(zone)) data.put("position", Action.Move.DECK_TOP.equals(to) ? "top" : "bottom");
            String arrives = zone;
            if (isAttachment(to)) {
                (to.startsWith("materials:") ? host.getMaterials() : host.getResources()).add(card);
                data.put("to", host.getInstanceId());
                arrives = to.startsWith("materials:") ? "materials" : "resources";
            } else if (Action.Move.FRONT.equals(to) || Action.Move.BACK.equals(to)) {
                card.setTapped(exhausted);
                card.setX(50.0);
                card.setY(Action.Move.FRONT.equals(to) ? 25.0 : 75.0);
                origin.owner().getField().add(card);
                effect.setSummonedId(card.getInstanceId());
                arrives = "field";
            } else {
                // A card always goes to its owner's zones
                List<CardSlot> into = EffectEngine.zone(origin.owner(), zone);
                if (Action.Move.DECK_TOP.equals(to) || Action.Move.LIFE_TOP.equals(to)) into.add(0, card);
                else into.add(card);
            }
            if ("lifeStack".equals(from)) data.put("defenderId", origin.owner().getUserId());
            EffectEngine.record(room, eventOf(arrives, from), player().getUserId(), card, from, arrives, effect, data, null);
            done.add(card);
            if ("lifeStack".equals(from) && origin.owner().getLifeStack().isEmpty()) EffectEngine.loseByLives(room, origin.owner());
        }
        if (done.isEmpty()) return done;
        // A card of the hand or the deck stays hidden when it goes to the deck
        boolean hidden = faceDown || ("deck".equals(zone) && ("hand".equals(from) || "deck".equals(from)));
        String origin = "deck".equals(from) && !effect.getLooked().isEmpty() ? "" : "deck".equals(from) ? " del tope de su mazo" : "";
        EffectEngine.log(room, player().getUsername() + " " + verb(to) + " " + done.size() + " " + (done.size() == 1 ? "carta" : "cartas") + origin
                + " (" + effect.getSourceName() + ")" + (hidden ? "" : EffectEngine.names(done)), room.getEventSeq());
        return done;
    }

    private static boolean isAttachment(String to) {
        return to.startsWith("materials:") || to.startsWith("resources:");
    }

    /** The card that receives the move: "materials:subject". */
    private CardSlot hostIn(String to) {
        if (!isAttachment(to)) return null;
        String who = to.substring(to.indexOf(':') + 1);
        return switch (who) {
            case "self" -> EffectEngine.find(player().getField(), effect.getSourceInstanceId());
            case "subject" -> effect.getSubjectId() == null ? null : EffectEngine.find(player().getField(), effect.getSubjectId());
            case "last" -> effect.getLastTributed().isEmpty() ? null : EffectEngine.find(player().getField(), effect.getLastTributed().get(0));
            case "event" -> eventCardOnField();
            default -> null;
        };
    }

    @Override
    public List<CardSlot> looked() {
        return effect.getLooked();
    }

    @Override
    public void lookedRestTo(String to, List<String> order) {
        List<CardSlot> rest = StepHelpers.inOrder(effect.getLooked(), order);
        rest.forEach(c -> c.setFaceDown(true));
        if (Action.Move.DECK_TOP.equals(to)) player().getDeck().addAll(0, rest);
        else player().getDeck().addAll(rest);
        effect.getLooked().clear();
        if (!rest.isEmpty()) {
            EffectEngine.log(room, player().getUsername() + " " + verb(to) + " " + rest.size() + " " + (rest.size() == 1 ? "carta" : "cartas"), null);
        }
    }

    @Override
    public CardSlot eventCard() {
        GameEvent trigger = EffectEngine.triggerEvent(room, effect);
        EffectEngine.Located at = trigger == null ? null : EffectEngine.locate(player(), trigger.getCardInstanceId());
        return at == null || "field".equals(at.zone()) ? null : at.card();
    }

    @Override
    public boolean unaffected(CardSlot card) {
        return EffectEngine.unaffected(card, effect);
    }

    @Override
    public void asksOrder(boolean toTop) {
        Prompt prompt = room.getPrompt();
        prompt.setKind(Prompt.Kind.TAKE_REVEALED_REST_BOTTOM);
        prompt.setReorderRest(true);
        prompt.setRestToTop(toTop);
    }

    /** Rule of the designer (2026-11-01): the event comes from the destination, in this one place, whatever the verb printed. */
    private static String eventOf(String zone, String from) {
        return switch (zone) {
            case "tributeZone" -> GameEvent.TRIBUTE;
            case "hand" -> "field".equals(from) ? GameEvent.SEND : GameEvent.ADD_TO_HAND;
            default -> GameEvent.SEND;
        };
    }

    private static String verb(String to) {
        return switch (to) {
            case Action.Move.TRIBUTES -> "tributa";
            case Action.Move.DISCARD -> "descarta";
            case Action.Move.DECK_TOP -> "coloca al tope de su mazo";
            case Action.Move.DECK_BOTTOM -> "envía al fondo de su mazo";
            case Action.Move.HAND -> "añade a su mano";
            case Action.Move.FRONT -> "coloca en su línea frontal";
            case Action.Move.BACK -> "coloca en su retaguardia";
            case Action.Move.LIFE_TOP -> "coloca al tope de sus vidas";
            default -> "mueve";
        };
    }

    @Override
    public void moved(List<CardSlot> cards) {
        effect.setLastCount(cards.size());
        effect.setLastTributed(new ArrayList<>(EffectEngine.ids(cards)));
        done(!cards.isEmpty());
    }

    @Override
    public void draw(int count, boolean bothPlayers) {
        for (PlayerState p : bothPlayers ? List.copyOf(room.getPlayers().values()) : List.of(player())) {
            int n = Math.min(count, p.getDeck().size());
            for (int i = 0; i < n; i++) Summons.drawOne(room, p, effect, false);
            if (!bothPlayers) done(n > 0);
            if (n > 0 || count > 0) EffectEngine.log(room, p.getUsername() + " roba " + n + " " + (n == 1 ? "carta" : "cartas") + " (" + effect.getSourceName() + ")"
                    + (n < count ? ": su mazo no tenía más" : ""), room.getEventSeq());
        }
    }

    @Override
    public void gainGems(int count) {
        String userId = player().getUserId();
        room.setSharedCounter(EffectEngine.clamp(room.getSharedCounter() + count * EffectEngine.direction(room, userId)));
        EffectEngine.record(room, GameEvent.GAIN_GEMS, userId, null, null, null, effect, Map.of("count", count),
                player().getUsername() + " gana " + EffectEngine.gemsText(count) + " (" + effect.getSourceName() + ")");
    }

    @Override
    public void damage(int amount) {
        StepHelpers.damageByEffect(room, effect, player(), amount);
    }

    @Override
    public void look(int count) {
        int n = Math.min(count, player().getDeck().size());
        for (int i = 0; i < n; i++) effect.getLooked().add(player().getDeck().remove(0));
        EffectEngine.record(room, GameEvent.LOOK_TOP, player().getUserId(), null, "deck", null, effect, Map.of("count", n),
                player().getUsername() + " mira las primeras " + n + " cartas de su mazo (" + effect.getSourceName() + ")");
    }

    private PlayerState ownerOnField(CardSlot card) {
        for (PlayerState p : room.getPlayers().values()) if (EffectEngine.find(p.getField(), card.getInstanceId()) != null) return p;
        return null;
    }

    @Override
    public boolean onField(CardSlot card) {
        return ownerOnField(card) != null;
    }

    @Override
    public boolean canTap(CardSlot card) {
        return !card.isTapped() && !EffectEngine.keywords(card).contains(EffectEngine.CANNOT_TAP);
    }

    @Override
    public boolean canUntap(CardSlot card) {
        return card.isTapped() && card.getNoUntap() != 1;
    }

    @Override
    public void setTapped(CardSlot card, boolean tapped) {
        PlayerState owner = ownerOnField(card);
        card.setTapped(tapped);
        EffectEngine.record(room, tapped ? GameEvent.TAP : GameEvent.UNTAP, player().getUserId(), card, null, null, effect, Map.of("ownerId", owner.getUserId()),
                player().getUsername() + (tapped ? " agota a " : " reactiva a ") + EffectEngine.name(card) + " (" + effect.getSourceName() + ")");
    }

    @Override
    public int strength(CardSlot card) {
        return Math.max(0, EffectEngine.strength(card));
    }

    @Override
    public void changeStrength(CardSlot card, int amount, Action.Until until) {
        if (amount == 0) return;
        PlayerState owner = ownerOnField(card);
        if (amount < 0) {
            reduce(card, owner, -amount, until);
            return;
        }
        String text = untilText(until);
        switch (until) {
            case NEXT_TURN -> card.setLaterStrength(card.getLaterStrength() + amount);
            case ATTACK -> {
                card.setTurnStrength(card.getTurnStrength() + amount);
                if (room.getCombat() != null) room.getCombat().getUntilEndOfAttack().add(card.getInstanceId() + "|" + EffectEngine.STRENGTH_ENTRY + amount);
            }
            case NEXT_ATTACK -> {
                card.setTurnStrength(card.getTurnStrength() + amount);
                card.getUntilNextAttack().add(EffectEngine.STRENGTH_ENTRY + amount);
            }
            case TURN, AS_EVENT -> card.setTurnStrength(card.getTurnStrength() + amount);
        }
        EffectEngine.floating(card, effect, "Gana fuerza " + amount + text, floatingUntil(until), amount, null);
        EffectEngine.record(room, GameEvent.GAIN, effect.getControllerId(), card, null, null, effect, Map.of("strength", amount),
                EffectEngine.name(card) + " gana fuerza " + amount + text);
    }

    private void reduce(CardSlot card, PlayerState owner, int amount, Action.Until until) {
        if (until == Action.Until.AS_EVENT) {
            GameEvent trigger = EffectEngine.triggerEvent(room, effect);
            boolean later = trigger != null && trigger.getData() != null && Boolean.TRUE.equals(trigger.getData().get("untilNextTurn"));
            StepHelpers.reduceStrength(room, effect, owner, card, amount, later);
            return;
        }
        if (until != Action.Until.ATTACK) {
            StepHelpers.reduceStrength(room, effect, owner, card, amount, until == Action.Until.NEXT_TURN);
            return;
        }
        card.setTurnStrength(card.getTurnStrength() - amount);
        EffectEngine.floating(card, effect, "Pierde " + amount + " de fuerza hasta el final del ataque", "attack", -amount, null);
        if (room.getCombat() != null) room.getCombat().getUntilEndOfAttack().add(card.getInstanceId() + "|" + EffectEngine.STRENGTH_ENTRY + (-amount));
        int now = strength(card);
        EffectEngine.record(room, GameEvent.STRENGTH_REDUCED, effect.getControllerId(), card, null, null, effect,
                Map.of("amount", amount, "to", now, "ownerId", owner.getUserId(), "untilNextTurn", false),
                EffectEngine.name(card) + " pierde " + amount + " de fuerza hasta el final del ataque (" + effect.getSourceName() + "): queda en " + now);
        if (now <= 0) Removals.destroy(room, card, owner, false, EffectEngine.CAUSE_EFFECT, effect.getControllerId(), null, effect);
    }

    @Override
    public void grant(CardSlot card, String keyword, Action.Until until) {
        switch (until) {
            case NEXT_TURN -> card.getLaterKeywords().add(keyword);
            case ATTACK -> {
                card.getTurnKeywords().add(keyword);
                if (room.getCombat() != null) room.getCombat().getUntilEndOfAttack().add(card.getInstanceId() + "|" + keyword);
            }
            case NEXT_ATTACK -> {
                card.getTurnKeywords().add(keyword);
                card.getUntilNextAttack().add(keyword);
            }
            case TURN, AS_EVENT -> card.getTurnKeywords().add(keyword);
        }
        // A restriction already says what happens ("No puede agotarse"); an ability is gained
        String what = (keyword.startsWith("No puede") ? keyword : "Gana " + keyword) + untilText(until);
        EffectEngine.floating(card, effect, what, floatingUntil(until), 0, keyword);
        EffectEngine.record(room, GameEvent.GAIN, effect.getControllerId(), card, null, null, effect, Map.of("keyword", keyword), EffectEngine.name(card) + ": " + what);
    }

    private static String untilText(Action.Until until) {
        return switch (until) {
            case TURN, AS_EVENT -> " hasta el final del turno";
            case NEXT_TURN -> " hasta el final del turno oponente";
            case ATTACK -> " hasta el final del ataque";
            case NEXT_ATTACK -> " hasta el final del turno o de su próximo ataque";
        };
    }

    private static String floatingUntil(Action.Until until) {
        return switch (until) {
            case TURN, AS_EVENT -> "turn";
            case NEXT_TURN -> "nextTurn";
            case ATTACK, NEXT_ATTACK -> "attack";
        };
    }

    @Override
    public void destroy(CardSlot card, boolean sacrifice) {
        PlayerState owner = ownerOnField(card);
        if (owner == null) return;
        Removals.destroy(room, card, owner, sacrifice, EffectEngine.CAUSE_EFFECT, effect.getControllerId(), sacrifice ? effect.getSourceInstanceId() : null, effect);
    }

    @Override
    public CardSlot subjectOrSelf() {
        return StepHelpers.subject(room, effect);
    }

    @Override
    public CardSlot eventCardOnField() {
        GameEvent trigger = EffectEngine.triggerEvent(room, effect);
        return trigger == null ? null : find(trigger.getCardInstanceId());
    }

    @Override
    public CardSlot relativeTo(String to) {
        return switch (to) {
            case "self" -> find(effect.getSourceInstanceId());
            case "subject" -> effect.getSubjectId() == null ? null : find(effect.getSubjectId());
            case "last" -> effect.getLastTributed().isEmpty() ? null : find(effect.getLastTributed().get(0));
            case "event" -> eventCardOnField();
            default -> null;
        };
    }

    @Override
    public int levelOf(CardSlot card) {
        return card.getLevel() == null ? 0 : card.getLevel();
    }

    @Override
    public CardSlot clashing() {
        return StepHelpers.clashOpponent(room, effect);
    }

    @Override
    public String selfId() {
        return effect.getSourceInstanceId();
    }

    @Override
    public CardSlot subjectOrSummoned() {
        CardSlot ally = StepHelpers.subject(room, effect);
        boolean replaced = ally == null || (effect.getSubjectId() != null && EffectEngine.find(player().getField(), effect.getSubjectId()) == null);
        return replaced && effect.getSummonedId() != null ? EffectEngine.find(player().getField(), effect.getSummonedId()) : ally;
    }

    @Override
    public boolean canPay(int cost) {
        return EffectEngine.canPay(room, effect.getControllerId(), cost);
    }

    @Override
    public boolean canBePlayed(CardSlot card) {
        return Chain.cannotActivateOnPlay(room, effect.getControllerId(), card) == null;
    }

    @Override
    public boolean delayed() {
        return effect.getScriptId() != null && EffectEngine.script(effect).moment() == EffectScript.Moment.DELAYED;
    }

    @Override
    public void note(String message) {
        EffectEngine.log(room, message + " (" + effect.getSourceName() + ")", null);
    }

    /** Out of the card it is attached to, or of its zone: its zone name for the event. */
    private String detach(CardSlot card) {
        Origin origin = origin(card);
        if (origin == null) return null;
        if (origin.host() == null && !origin.looked()) return origin.zone();
        (origin.host() != null ? ("materials".equals(origin.zone()) ? origin.host().getMaterials() : origin.host().getResources()) : effect.getLooked()).remove(card);
        player().getHand().add(card);
        return "hand";
    }

    @Override
    public void summon(CardSlot card, int reduce, boolean toFront) {
        String from = detach(card);
        if (from == null) return;
        Summons.summonByEffect(room, effect, player(), card, from, reduce, toFront);
        effect.setSummonedId(card.getInstanceId());
    }

    @Override
    public void play(CardSlot card, int reduce) {
        String from = detach(card);
        if (from == null) return;
        int cost = Math.max(0, (card.getCost() == null ? 0 : card.getCost()) - reduce);
        EffectEngine.pay(room, effect.getControllerId(), cost);
        if (!"hand".equals(from)) {
            EffectEngine.zone(player(), from).remove(card);
            player().getHand().add(card);
        }
        Summons.play(room, player(), card, cost, null, null, effect);
    }

    @Override
    public void counted(int count) {
        effect.setLastCount(count);
    }

    @Override
    public void done(boolean done) {
        // "Si lo haces" reads what the resolution did, not the cost paid before it
        if (effect.getScriptId() == null || !Chain.paying(effect)) effect.setLastDone(done);
    }

    @Override
    public void requireInChoice(CardSlot card) {
        room.getPrompt().setRequiredIds(List.of(card.getInstanceId()));
    }

    @Override
    public boolean immune(CardSlot card) {
        return EffectEngine.immuneTo(card, StepHelpers.sourceLevel(room, effect));
    }

    @Override
    public boolean inRetaguardia() {
        return StepHelpers.inRetaguardia(room, effect);
    }

    @Override
    public boolean inFrontLine(CardSlot card) {
        return EffectEngine.inFrontLine(card);
    }

    @Override
    public List<CardSlot> zone(PlayerState player, String zone) {
        return EffectEngine.zone(player, zone);
    }

    @Override
    public int amount(Amount amount) {
        if (amount instanceof Amount.LastCount) return effect.getLastCount();
        // They may be built on what only the effect knows
        if (amount instanceof Amount.Times t) return amount(t.base()) * t.factor();
        if (amount instanceof Amount.Scaled s) return amount(s.base()) / s.divisor() + s.plus();
        if (amount instanceof Amount.TriggerSourceLevel) return Summons.triggerSourceLevel(room, effect);
        if (amount instanceof Amount.SubjectLevel) {
            CardSlot subject = StepHelpers.subject(room, effect);
            return subject == null || subject.getLevel() == null ? 0 : subject.getLevel();
        }
        if (amount instanceof Amount.UsedToSummon) {
            GameEvent trigger = EffectEngine.triggerEvent(room, effect);
            return trigger != null && trigger.getData() != null && trigger.getData().get("used") instanceof Number n ? n.intValue() : 0;
        }
        if (amount instanceof Amount.SourceStrength) {
            CardSlot self = StepHelpers.subject(room, effect);
            if (self == null && effect.getSubjectId() == null) self = EffectEngine.findAnywhere(room, effect.getControllerId(), effect.getSourceInstanceId());
            return self == null ? 0 : EffectEngine.strength(self);
        }
        return amount.of(room, effect.getControllerId());
    }

    @Override
    public String describe(Selector selector) {
        String who = selector.owner() == Selector.Owner.OPPONENT ? "enemigo" : selector.zones().toLowerCase().contains("allies") || selector.zones().contains("ttackers") ? "aliado" : "carta";
        String filter = selector.filter().describe();
        return filter.isBlank() ? who : who + " " + filter;
    }

    @Override
    public String name(CardSlot card) {
        return EffectEngine.name(card);
    }

    @Override
    public CardSlot find(String instanceId) {
        CardSlot mine = EffectEngine.findAnywhere(room, effect.getControllerId(), instanceId);
        return mine != null ? mine : EffectEngine.findAnywhere(room, room.getOpponentId(effect.getControllerId()), instanceId);
    }

    @Override
    public void ask(String instruction, String chosenLabel, List<CardSlot> shown, List<CardSlot> valid, int min, int max) {
        StepHelpers.pick(room, effect, instruction, chosenLabel, shown, EffectEngine.ids(valid), max);
        room.getPrompt().setMin(min);
    }

    @Override
    public List<String> saved(String name) {
        return switch (name) {
            case Memory.TARGETS -> effect.getTargetIds();
            case Memory.LAST -> effect.getLastTributed();
            case Memory.SUBJECT -> effect.getSubjectId() == null ? List.of() : List.of(effect.getSubjectId());
            case Memory.SUMMONED -> effect.getSummonedId() == null ? List.of() : List.of(effect.getSummonedId());
            default -> effect.getSaved().getOrDefault(name, List.of());
        };
    }

    @Override
    public void save(String name, List<String> instanceIds) {
        switch (name) {
            case SPECIAL_CARD -> {
                if (!instanceIds.isEmpty()) effect.setRitualTargetId(instanceIds.get(0));
            }
            case SPECIAL_SUMMONER -> {
                if (!instanceIds.isEmpty()) effect.setRitualSummonerId(instanceIds.get(0));
            }
            // Several choices of one effect add up ("elige hasta 1 enemigo nivel 2 y 1 enemigo nivel 1")
            case Memory.TARGETS -> effect.getTargetIds().addAll(instanceIds);
            case Memory.LAST -> effect.setLastTributed(new ArrayList<>(instanceIds));
            case Memory.SUBJECT -> {
                if (!instanceIds.isEmpty()) effect.setSubjectId(instanceIds.get(0));
            }
            default -> effect.getSaved().computeIfAbsent(name, k -> new ArrayList<>()).addAll(instanceIds);
        }
    }

    @Override
    public void event(String type, CardSlot card, Map<String, Object> data, String message) {
        EffectEngine.record(room, type, effect.getControllerId(), card, null, null, effect, data, message + " (" + effect.getSourceName() + ")");
    }
}
