package com.drive.drive_manager.simulator.actions;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.simulator.bot.BotRooms;
import com.drive.drive_manager.simulator.mission.MissionHooks;
import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.DeckLoader;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.PlayerState;
import com.drive.drive_manager.simulator.room.RoomGuard;
import com.drive.drive_manager.simulator.room.RoomService;
import com.drive.drive_manager.simulator.room.SimMessages;

import java.util.ArrayList;
import java.util.List;

import static com.drive.drive_manager.simulator.actions.CardActions.*;
import static com.drive.drive_manager.simulator.actions.PreRoomActions.*;
import static com.drive.drive_manager.simulator.actions.RulesActions.*;
import static com.drive.drive_manager.simulator.actions.TurnActions.*;
import static com.drive.drive_manager.simulator.actions.ZoneActions.*;

/**
 * Sends an action, already let through by RoomService's guards, to what handles it. Holds the few handlers that need
 * the service's collaborators (the decks, the missions); the rest are the static *Actions classes.
 */
public final class ActionDispatcher {

    private final RoomService service;

    public ActionDispatcher(RoomService service) {
        this.service = service;
    }

    public GameStateUpdate dispatch(ActionType type, GameRoom room, String userId, GameAction action) {
        return switch (type) {
            case null                    -> GameStateUpdate.error(room.getRoomId(), SimMessages.UNKNOWN_ACTION + action.getType());
            case NORMAL_SUMMON           -> handleNormalSummon(room, userId, action);
            case RESOLVE_PROMPT          -> handleResolvePrompt(room, userId, action);
            case DESTROY_CARD            -> handleDestroyCard(room, userId, action);
            case SACRIFICE_CARD          -> handleSacrificeCard(room, userId, action);
            case ATTACK                  -> handleAttack(room, userId, action);
            case PLAY_CARD               -> handlePlayCard(room, userId, action);
            case RITUAL_SUMMON           -> handleRitualSummon(room, userId, action);
            case ACTIVATE_EFFECT         -> handleActivateEffect(room, userId, action);
            case JOIN_ROOM               -> service.members.join(room, userId, action);
            case DRAW_CARD               -> handleDraw(room, userId);
            case MOVE_CARD               -> handleMove(room, userId, action);
            case FLIP_CARD               -> handleFlip(room, userId, action);
            case SHUFFLE_DECK            -> handleShuffle(room, userId);
            case ENTER_END_PHASE         -> handleEnterEndPhase(room, userId);
            // One way to end a turn (rule 8.3: through the end phase); "PASS_TURN" is what older boards send
            case END_TURN, PASS_TURN     -> handleEndTurn(room, userId);
            // "FORFEIT" is what older boards sent to concede
            case SURRENDER, FORFEIT      -> handleSurrender(room, userId);
            case POST_GAME_CHOICE        -> PostGameActions.choose(service, room, userId, action);
            case START_GAME              -> handleStartGame(room, userId, action);
            case SET_DECK                -> setDeck(room, userId, action);
            case SET_STARTER             -> handleSetStarter(room, userId, action);
            case SET_BOT_LEVEL           -> service.botRooms.setLevel(room, userId, action);
            case SET_EDITOR_BOT          -> service.botRooms.setEditorBot(room, userId, action);
            case SET_EDITOR_SELF_BOT     -> service.botRooms.setEditorSelfBot(room, userId, action);
            case SET_BOT_DELAY           -> service.botRooms.setDelay(room, userId, action);
            case SET_BOT_EDGE            -> service.botRooms.setEdge(room, userId, action);
            case SET_FIRST_PLAYER        -> handleSetFirstPlayer(room, userId, action);
            case SET_PAUSED, TIME_STEP   -> service.timeline.handle(room, userId, action);
            case SET_RANKED              -> service.botRooms.setRanked(room, userId, action);
            case SET_READY               -> handleSetReady(room, userId, action);
            case LEAVE_ROOM              -> service.members.leave(room, userId);
            case KICK_PLAYER             -> service.members.kick(room, userId, action);
            case SELECT_STARTER          -> handleSelectStarter(room, userId, action);
            case CONFIRM_MULLIGAN        -> handleConfirmMulligan(room, userId, action);
            case SET_MARKER              -> handleSetMarker(room, userId, action);
            case SET_SKIP_OPTIONAL       -> handleSetSkipOptional(room, userId, action);
            case LIFE_HEAL               -> handleLifeHeal(room, userId);
            case LIFE_SHUFFLE            -> handleLifeShuffle(room, userId);
            case LIFE_ALL_DOWN           -> handleLifeAllDown(room, userId);
            case LIFE_ALL_UP             -> handleLifeAllUp(room, userId);
            case LIFE_RESTART            -> handleLifeRestart(room, userId);
            case DECK_REORDER_TOP        -> handleDeckReorderTop(room, userId, action);
            case ZONE_REORDER            -> handleZoneReorder(room, userId, action);
            case DECK_INCLUDE_DISCARD    -> handleDeckIncludeDiscard(room, userId);
            case DECK_INCLUDE_TRIBUTE    -> handleDeckIncludeTribute(room, userId);
            case TAP_CARD                -> handleTapCard(room, userId, action);
            case SET_COUNTER             -> handleSetCounter(room, userId, action);
            case USE_RESOURCES           -> handleUseResources(room, userId, action);
            case GRANT_RESOURCE          -> handleGrantResource(room, userId);
            case MODIFY_STRENGTH         -> handleModifyStrength(room, userId, action);
            case ADD_MATERIAL            -> handleAddMaterial(room, userId, action);
            case ADD_RESOURCE            -> handleAddResource(room, userId, action);
            case MOVE_FROM_ATTACHMENT    -> handleMoveFromAttachment(room, userId, action);
            case SPECIAL_SUMMON          -> handleSpecialSummon(room, userId, action);
            case SWAP_WITH_MAIN          -> handleSwapWithMain(room, userId, action);
            case TARGET_CARD             -> handleTargetCard(room, userId, action);
            case REORDER_ATTACHMENT      -> handleReorderAttachment(room, userId, action);
            case RECLASSIFY_ATTACHMENT   -> handleReclassifyAttachment(room, userId, action);
            case REVEAL_TO_HAND          -> handleRevealToHand(room, userId, action);
            case REFRESH_CARD_METADATA   -> refreshCardMetadata(room, userId, action);
            case REMOVE_CARD             -> handleRemoveCard(room, userId, action);
            case RESTORE_CARD            -> handleRestoreCard(room, userId, action);
            case CLOSE_ROOM              -> service.members.close(room, userId);
            case EFFECT_SUMMON_RESOLVE   -> handleEffectSummonResolve(room, userId, action);
            case VIEW_DECK               -> handleViewDeck(room, userId, action);
            case VIEW_LIFE_STACK         -> handleViewLifeStack(room, userId);
            case VIEW_LIFE_CARD          -> handleViewLifeCard(room, userId);
            case BULK_TO_DECK_BOTTOM     -> handleBulkToDeckBottom(room, userId, action);
            case BULK_MOVE               -> handleBulkMove(room, userId, action);
            case CREATE_DUMMY            -> handleCreateDummy(room, userId, action);
        };
    }

    /** Pre-room: the deck a player will use; its first Iniciador is the starter until another is chosen. */
    private GameStateUpdate setDeck(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        if (room.getStatus() != GameRoom.RoomStatus.WAITING) return GameStateUpdate.error(room.getRoomId(), SimMessages.GAME_STARTED);
        String deckId = action.getString("deckId");
        if (deckId == null) return GameStateUpdate.error(room.getRoomId(), "Falta el mazo");
        DeckLoader decks = service.decks;
        DeckList deck = decks == null ? null : decks.deck(deckId).orElse(null);
        if (deck == null || !RoomGuard.usableDeck(deck, room, userId)) return GameStateUpdate.error(room.getRoomId(), SimMessages.DECK_NOT_FOUND);
        List<CardSlot> slots = decks.slots(deck);
        if (slots.isEmpty()) return GameStateUpdate.error(room.getRoomId(), SimMessages.DECK_NOT_FOUND);
        // The bot plays the same deck as its own cards (new instances, without the owner's finishes)
        List<CardSlot> forBot = room.isBot() ? DeckLoader.freshCopy(slots) : null;
        // Cards the engine does not run cannot be played; a test room plays them by hand (it is where they are tried)
        if (!room.isEditor() && DeckLoader.unprogrammedIn(slots) != null) return GameStateUpdate.error(room.getRoomId(),
                "Este mazo tiene cartas que todavía no están programadas en el simulador");
        // Only with every copy in the player's collection (a test room plays anything: it is where cards are tried; the bot owns none)
        boolean bot = BotRooms.BOT.equals(userId);
        MissionHooks hooks = service.hooks;
        if (!room.isEditor() && !bot && hooks != null && hooks.missing(userId, slots) > 0) {
            return GameStateUpdate.error(room.getRoomId(), "No puedes jugar este mazo: te faltan cartas de tu colección");
        }
        if (hooks != null && !bot) hooks.withFinishes(userId, slots);
        player.setDeckId(deckId);
        player.setDeckName(deck.getDeckName());
        player.setDeck(new ArrayList<>(slots));
        player.setOriginalDeck(deepCopySlots(slots));
        player.setStarterCardId(slots.stream().filter(CardSlot::isStarter).map(CardSlot::getCardId).findFirst().orElse(null));
        player.setReady(false);
        if (room.isBot() && !bot) service.botRooms.mirrorDeck(room, player, forBot);
        return GameStateUpdate.from(room, ActionType.SET_DECK, userId);
    }

    private GameStateUpdate refreshCardMetadata(GameRoom room, String userId, GameAction action) {
        String cardId = action.getString("cardId");
        if (cardId == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.NO_CARD);
        Card meta = service.decks == null ? null : service.decks.metadataOf(cardId);
        if (meta == null) return GameStateUpdate.error(room.getRoomId(), SimMessages.METADATA_NOT_FOUND);
        DeckLoader.refresh(room, cardId, meta);
        return GameStateUpdate.from(room, ActionType.REFRESH_CARD_METADATA, userId);
    }
}
