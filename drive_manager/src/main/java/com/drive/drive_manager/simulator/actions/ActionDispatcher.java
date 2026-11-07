package com.drive.drive_manager.simulator.actions;

import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.RoomHost;
import com.drive.drive_manager.simulator.room.SimMessages;

import static com.drive.drive_manager.simulator.actions.CardActions.*;
import static com.drive.drive_manager.simulator.actions.PreRoomActions.*;
import static com.drive.drive_manager.simulator.actions.RulesActions.*;
import static com.drive.drive_manager.simulator.actions.TurnActions.*;
import static com.drive.drive_manager.simulator.actions.ZoneActions.*;

/**
 * Sends an action, already let through by the room's guards ({@code RoomEngine}), to what handles it: the static
 * *Actions classes, or the room's host ({@link RoomHost}) for what only whoever runs the room knows (who joins, the
 * decks, the bot's and the timeline's settings).
 */
public final class ActionDispatcher {

    private final RoomHost host;

    public ActionDispatcher(RoomHost host) {
        this.host = host;
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
            case JOIN_ROOM               -> host.join(room, userId, action);
            case DRAW_CARD               -> handleDraw(room, userId);
            case MOVE_CARD               -> handleMove(room, userId, action);
            case FLIP_CARD               -> handleFlip(room, userId, action);
            case SHUFFLE_DECK            -> handleShuffle(room, userId);
            case ENTER_END_PHASE         -> handleEnterEndPhase(room, userId);
            // One way to end a turn (rule 8.3: through the end phase); "PASS_TURN" is what older boards send
            case END_TURN, PASS_TURN     -> handleEndTurn(room, userId);
            // "FORFEIT" is what older boards sent to concede
            case SURRENDER, FORFEIT      -> handleSurrender(room, userId);
            case POST_GAME_CHOICE        -> PostGameActions.choose(host, room, userId, action);
            case START_GAME              -> handleStartGame(room, userId, action);
            case SET_DECK                -> host.setDeck(room, userId, action);
            case SET_STARTER             -> handleSetStarter(room, userId, action);
            case SET_BOT_LEVEL           -> host.botSetting(type, room, userId, action);
            case SET_EDITOR_BOT          -> host.botSetting(type, room, userId, action);
            case SET_EDITOR_SELF_BOT     -> host.botSetting(type, room, userId, action);
            case SET_BOT_DELAY           -> host.botSetting(type, room, userId, action);
            case SET_BOT_EDGE            -> host.botSetting(type, room, userId, action);
            case SET_FIRST_PLAYER        -> handleSetFirstPlayer(room, userId, action);
            case SET_PAUSED, TIME_STEP   -> host.timeline(room, userId, action);
            case SET_RANKED              -> host.botSetting(type, room, userId, action);
            case SET_READY               -> handleSetReady(room, userId, action);
            case LEAVE_ROOM              -> host.leave(room, userId);
            case KICK_PLAYER             -> host.kick(room, userId, action);
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
            case REFRESH_CARD_METADATA   -> host.refreshCardMetadata(room, userId, action);
            case REMOVE_CARD             -> handleRemoveCard(room, userId, action);
            case RESTORE_CARD            -> handleRestoreCard(room, userId, action);
            case CLOSE_ROOM              -> host.close(room, userId);
            case EFFECT_SUMMON_RESOLVE   -> handleEffectSummonResolve(room, userId, action);
            case VIEW_DECK               -> handleViewDeck(room, userId, action);
            case VIEW_LIFE_STACK         -> handleViewLifeStack(room, userId);
            case VIEW_LIFE_CARD          -> handleViewLifeCard(room, userId);
            case BULK_TO_DECK_BOTTOM     -> handleBulkToDeckBottom(room, userId, action);
            case BULK_MOVE               -> handleBulkMove(room, userId, action);
            case CREATE_DUMMY            -> handleCreateDummy(room, userId, action);
        };
    }

}
