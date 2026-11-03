package com.drive.drive_manager.simulator;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.repository.DeckListRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.simulator.engine.CardEffects;
import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.engine.TurnRules;
import com.drive.drive_manager.simulator.engine.Summons;
import com.drive.drive_manager.simulator.engine.SpecialSummons;
import com.drive.drive_manager.simulator.engine.Removals;
import com.drive.drive_manager.simulator.engine.CombatRules;
import com.drive.drive_manager.simulator.engine.Chain;
import com.drive.drive_manager.simulator.engine.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.drive.drive_manager.simulator.RoomService.*;
import static com.drive.drive_manager.simulator.TurnActions.*;
import static com.drive.drive_manager.simulator.PreRoomActions.*;
import static com.drive.drive_manager.simulator.ZoneActions.*;
import static com.drive.drive_manager.simulator.CardActions.*;

/** What a player asks the rules engine to do. */
final class RulesActions {

    private RulesActions() {}

    /** Taking a creature from the hand to the board without an effect is a normal summon (rules engine). */
    static GameStateUpdate handleNormalSummon(GameRoom room, String userId, GameAction action) {
        String error = Summons.normalSummon(room, userId, action.getString("instanceId"), action.getDouble("x"), action.getDouble("y"));
        if (error != null) return GameStateUpdate.error(room.getRoomId(), error);
        return GameStateUpdate.from(room, "NORMAL_SUMMON", userId);
    }

    /** The player's choice for the effect step the engine is waiting on. */
    static GameStateUpdate handleResolvePrompt(GameRoom room, String userId, GameAction action) {
        String error = Answers.answer(room, userId, action.getString("promptId"), action.getStringList("chosen"), action.getStringList("order"));
        if (error != null) return GameStateUpdate.error(room.getRoomId(), error);
        // The end phase's move up was answered: the turn that was being ended ends now
        if (room.isEndTurnPending() && !room.isEngineBusy()) return passTurn(room, room.getCurrentTurnUserId());
        // The start of the turn is done (its move up answered, its effects resolved): the main phase begins
        if (room.isStartPhasePending() && !room.isEngineBusy()) {
            room.setStartPhasePending(false);
            room.setCurrentPhase("MAIN");
        }
        return GameStateUpdate.from(room, "RESOLVE_PROMPT", userId);
    }

    /** Destroys a card on the field (to its owner's Descarte); its "En Destrucción" effects trigger. */
    static GameStateUpdate handleDestroyCard(GameRoom room, String userId, GameAction action) {
        String error = Removals.destroyByPlayer(room, userId, action.getString("targetPlayerId"), action.getString("instanceId"));
        if (error != null) return GameStateUpdate.error(room.getRoomId(), error);
        return GameStateUpdate.from(room, "DESTROY_CARD", userId);
    }

    /** Sacrifices one of your cards on the field (destroyed, to your Tributos). */
    static GameStateUpdate handleSacrificeCard(GameRoom room, String userId, GameAction action) {
        String error = Removals.sacrificeByPlayer(room, userId, action.getString("instanceId"));
        if (error != null) return GameStateUpdate.error(room.getRoomId(), error);
        return GameStateUpdate.from(room, "SACRIFICE_CARD", userId);
    }

    /** Declares an attack (rule 10): the engine runs the combat. targetInstanceId null = the opponent. */
    static GameStateUpdate handleAttack(GameRoom room, String userId, GameAction action) {
        String error = CombatRules.attackByPlayer(room, userId, action.getString("instanceId"), action.getString("targetInstanceId"));
        if (error != null) return GameStateUpdate.error(room.getRoomId(), error);
        return GameStateUpdate.from(room, "ATTACK", userId);
    }

    /** Plays a utility or structure from the hand: pays its cost and fires its "En Juego" effects. */
    static GameStateUpdate handlePlayCard(GameRoom room, String userId, GameAction action) {
        String error = Summons.playByPlayer(room, userId, action.getString("instanceId"), action.getDouble("x"), action.getDouble("y"));
        if (error != null) return GameStateUpdate.error(room.getRoomId(), error);
        return GameStateUpdate.from(room, "PLAY_CARD", userId);
    }

    static GameStateUpdate handleRitualSummon(GameRoom room, String userId, GameAction action) {
        String error = SpecialSummons.specialByPlayer(room, userId, action.getString("instanceId"), action.getString("summonerId"));
        if (error != null) return GameStateUpdate.error(room.getRoomId(), error);
        return GameStateUpdate.from(room, "RITUAL_SUMMON", userId);
    }

    static GameStateUpdate handleActivateEffect(GameRoom room, String userId, GameAction action) {
        String error = Chain.activateByPlayer(room, userId, action.getString("instanceId"), action.getString("scriptId"));
        if (error != null) return GameStateUpdate.error(room.getRoomId(), error);
        return GameStateUpdate.from(room, "ACTIVATE_EFFECT", userId);
    }

    /**
     * A creature dropped onto the field from a non-hand zone only becomes an
     * "effect summon" once the player answers the draw/no-draw prompt (see
     * FieldZone.vue's effectSummonPending flow) — not at drop time.
     */
    static GameStateUpdate handleEffectSummonResolve(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        String instanceId = action.getString("instanceId");
        String fromZone   = action.getString("fromZone");
        boolean draw = action.getBoolean("draw", false);

        CardSlot card = player.getField().stream()
                .filter(c -> instanceId != null && instanceId.equals(c.getInstanceId()))
                .findFirst().orElse(null);
        String label = card != null ? cardLabel(card) : "una carta";

        String logText;
        if (draw) {
            boolean actuallyDrew = false;
            if (!player.getDeck().isEmpty()) {
                CardSlot drawn = player.getDeck().remove(0);
                drawn.setFaceDown(false);
                player.getHand().add(drawn);
                actuallyDrew = true;
            }
            logText = player.getUsername() + " invocó por efecto a " + label + (actuallyDrew ? " y robó una carta" : "");
        } else {
            // Declined the effect-summon draw — this is just a plain summon/set,
            // using the same phrasing the immediate MOVE_CARD log would have used
            // had it not been deferred (see buildMoveLogEntry's "field" case).
            String from   = zoneLabel(fromZone);
            String suffix = (card != null && "creature".equalsIgnoreCase(card.getCardType()) && card.isFaceDown()) ? " (set)" : "";
            logText = player.getUsername() + " colocó/invocó " + label + " desde " + from + suffix;
        }

        GameStateUpdate update = GameStateUpdate.from(room, "EFFECT_SUMMON_RESOLVE", userId);
        update.setLogEntry(logText);
        return update;
    }

    static GameStateUpdate handleSpecialSummon(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "Player not in room");
        String summonedId  = action.getString("summonedId");
        String fromZone    = action.getString("fromZone");
        String summonerId  = action.getString("summonerId");
        String summonType  = action.getString("summonType");

        List<CardSlot> source = getZone(player, fromZone);
        if (source == null) return GameStateUpdate.error(room.getRoomId(), "Invalid zone");
        CardSlot summoned = source.stream().filter(c -> summonedId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (summoned == null) return GameStateUpdate.error(room.getRoomId(), "Summoned card not found");
        CardSlot summoner = player.getField().stream().filter(c -> summonerId.equals(c.getInstanceId())).findFirst().orElse(null);
        if (summoner == null) return GameStateUpdate.error(room.getRoomId(), "Summoner not found");

        source.remove(summoned);
        player.getField().remove(summoner);

        summoned.setFaceDown(false);
        summoned.setX(summoner.getX());
        summoned.setY(summoner.getY());
        // Rule 9.2.0.3: its whole state, also its effects until the end of the turn and its role in a combat
        Summons.inheritFromSummoner(room, summoned, summoner);

        switch (summonType) {
            case "materialization" -> {
                summoned.setTapped(summoner.isTapped());
                summoned.setStrengthModifier(summoner.getStrengthModifier());
                summoned.getMaterials().addAll(0, summoner.getMaterials());
                summoned.getResources().addAll(0, summoner.getResources());
                summoner.setMaterials(new ArrayList<>());
                summoner.setResources(new ArrayList<>());
                summoner.setTapped(false); summoner.setX(null); summoner.setY(null);
                summoned.getMaterials().add(0, summoner);
            }
            case "evolution" -> {
                summoned.setTapped(summoner.isTapped());
                summoned.setStrengthModifier(summoner.getStrengthModifier());
                summoned.getMaterials().addAll(0, summoner.getMaterials());
                summoned.getResources().addAll(0, summoner.getResources());
                summoner.setMaterials(new ArrayList<>());
                summoner.setResources(new ArrayList<>());
                summoner.setTapped(false); summoner.setX(null); summoner.setY(null);
                summoned.getResources().add(0, summoner);
            }
            case "ritual" -> {
                // The summoner is sacrificed; its Recursos and Materiales pass to the summoned creature (rule 9.3.0.5)
                summoned.setTapped(summoner.isTapped());
                summoned.setStrengthModifier(summoner.getStrengthModifier());
                summoned.getMaterials().addAll(0, summoner.getMaterials());
                summoned.getResources().addAll(0, summoner.getResources());
                summoner.setMaterials(new ArrayList<>());
                summoner.setResources(new ArrayList<>());
                summoner.setFaceDown(false); summoner.setTapped(false); summoner.setX(null); summoner.setY(null);
                player.getTributeZone().add(summoner);
            }
            case "promotion" -> {
                summoned.setTapped(summoner.isTapped());
                summoned.setStrengthModifier(summoner.getStrengthModifier());
                summoned.getMaterials().addAll(0, summoner.getMaterials());
                summoned.getResources().addAll(0, summoner.getResources());
                summoner.setMaterials(new ArrayList<>());
                summoner.setResources(new ArrayList<>());
                summoner.setFaceDown(false); summoner.setTapped(false); summoner.setX(null); summoner.setY(null);
                player.getTributeZone().add(summoner);
            }
        }

        summoned.setFaceDown(false);
        player.getField().add(summoned);
        // Recorded by the engine: it offers the move up to the Línea frontal, does the regulatory draw and then
        // fires the summon and sacrifice triggers ("En Invocación", "En Destrucción")
        Summons.specialSummoned(room, userId, summoned, summoner, summonType,
                player.getUsername() + " invocó especialmente a " + cardLabel(summoned)
                        + " (" + summonTypeLabel(summonType) + ") usando " + cardLabel(summoner));
        return GameStateUpdate.from(room, "SPECIAL_SUMMON", userId);
    }

    static String summonTypeLabel(String summonType) {
        if (summonType == null) return "invocación especial";
        return switch (summonType) {
            case "materialization" -> "Materialización";
            case "evolution"       -> "Evolución";
            case "ritual"          -> "Ritual";
            case "promotion"       -> "Ascenso";
            default                -> summonType;
        };
    }
}
