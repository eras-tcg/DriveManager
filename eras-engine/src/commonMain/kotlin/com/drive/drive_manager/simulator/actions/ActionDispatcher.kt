package com.drive.drive_manager.simulator.actions

import com.drive.drive_manager.simulator.actions.CardActions.handleAddMaterial
import com.drive.drive_manager.simulator.actions.CardActions.handleAddResource
import com.drive.drive_manager.simulator.actions.CardActions.handleCreateDummy
import com.drive.drive_manager.simulator.actions.CardActions.handleModifyStrength
import com.drive.drive_manager.simulator.actions.CardActions.handleMoveFromAttachment
import com.drive.drive_manager.simulator.actions.CardActions.handleReclassifyAttachment
import com.drive.drive_manager.simulator.actions.CardActions.handleRemoveCard
import com.drive.drive_manager.simulator.actions.CardActions.handleReorderAttachment
import com.drive.drive_manager.simulator.actions.CardActions.handleRestoreCard
import com.drive.drive_manager.simulator.actions.CardActions.handleSwapWithMain
import com.drive.drive_manager.simulator.actions.CardActions.handleTapCard
import com.drive.drive_manager.simulator.actions.CardActions.handleTargetCard
import com.drive.drive_manager.simulator.actions.PreRoomActions.handleConfirmMulligan
import com.drive.drive_manager.simulator.actions.PreRoomActions.handleSelectStarter
import com.drive.drive_manager.simulator.actions.PreRoomActions.handleSetFirstPlayer
import com.drive.drive_manager.simulator.actions.PreRoomActions.handleSetReady
import com.drive.drive_manager.simulator.actions.PreRoomActions.handleSetStarter
import com.drive.drive_manager.simulator.actions.PreRoomActions.handleStartGame
import com.drive.drive_manager.simulator.actions.RulesActions.handleActivateEffect
import com.drive.drive_manager.simulator.actions.RulesActions.handleAttack
import com.drive.drive_manager.simulator.actions.RulesActions.handleDestroyCard
import com.drive.drive_manager.simulator.actions.RulesActions.handleEffectSummonResolve
import com.drive.drive_manager.simulator.actions.RulesActions.handleNormalSummon
import com.drive.drive_manager.simulator.actions.RulesActions.handlePlayCard
import com.drive.drive_manager.simulator.actions.RulesActions.handleResolvePrompt
import com.drive.drive_manager.simulator.actions.RulesActions.handleRitualSummon
import com.drive.drive_manager.simulator.actions.RulesActions.handleSacrificeCard
import com.drive.drive_manager.simulator.actions.RulesActions.handleSpecialSummon
import com.drive.drive_manager.simulator.actions.TurnActions.handleDraw
import com.drive.drive_manager.simulator.actions.TurnActions.handleEndTurn
import com.drive.drive_manager.simulator.actions.TurnActions.handleEnterEndPhase
import com.drive.drive_manager.simulator.actions.TurnActions.handleGrantResource
import com.drive.drive_manager.simulator.actions.TurnActions.handleSetCounter
import com.drive.drive_manager.simulator.actions.TurnActions.handleSetMarker
import com.drive.drive_manager.simulator.actions.TurnActions.handleSetSkipOptional
import com.drive.drive_manager.simulator.actions.TurnActions.handleSurrender
import com.drive.drive_manager.simulator.actions.TurnActions.handleUseResources
import com.drive.drive_manager.simulator.actions.ZoneActions.handleBulkMove
import com.drive.drive_manager.simulator.actions.ZoneActions.handleBulkToDeckBottom
import com.drive.drive_manager.simulator.actions.ZoneActions.handleDeckIncludeDiscard
import com.drive.drive_manager.simulator.actions.ZoneActions.handleDeckIncludeTribute
import com.drive.drive_manager.simulator.actions.ZoneActions.handleDeckReorderTop
import com.drive.drive_manager.simulator.actions.ZoneActions.handleFlip
import com.drive.drive_manager.simulator.actions.ZoneActions.handleLifeAllDown
import com.drive.drive_manager.simulator.actions.ZoneActions.handleLifeAllUp
import com.drive.drive_manager.simulator.actions.ZoneActions.handleLifeHeal
import com.drive.drive_manager.simulator.actions.ZoneActions.handleLifeRestart
import com.drive.drive_manager.simulator.actions.ZoneActions.handleLifeShuffle
import com.drive.drive_manager.simulator.actions.ZoneActions.handleMove
import com.drive.drive_manager.simulator.actions.ZoneActions.handleRevealToHand
import com.drive.drive_manager.simulator.actions.ZoneActions.handleShuffle
import com.drive.drive_manager.simulator.actions.ZoneActions.handleViewDeck
import com.drive.drive_manager.simulator.actions.ZoneActions.handleViewLifeCard
import com.drive.drive_manager.simulator.actions.ZoneActions.handleViewLifeStack
import com.drive.drive_manager.simulator.actions.ZoneActions.handleZoneReorder
import com.drive.drive_manager.simulator.room.ActionType
import com.drive.drive_manager.simulator.room.GameAction
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.GameStateUpdate
import com.drive.drive_manager.simulator.room.RoomHost
import com.drive.drive_manager.simulator.room.SimMessages

/**
 * Sends an action, already let through by the room's guards (`RoomEngine`), to what handles it: the static
 * *Actions classes, or the room's host ([RoomHost]) for what only whoever runs the room knows (who joins, the
 * decks, the bot's and the timeline's settings).
 */
class ActionDispatcher(private val host: RoomHost) {

    fun dispatch(type: ActionType?, room: GameRoom, userId: String?, action: GameAction): GameStateUpdate {
        return when (type) {
            null                                   -> GameStateUpdate.error(room.roomId, SimMessages.UNKNOWN_ACTION + action.type)
            ActionType.NORMAL_SUMMON               -> handleNormalSummon(room, userId, action)
            ActionType.RESOLVE_PROMPT              -> handleResolvePrompt(room, userId, action)
            ActionType.DESTROY_CARD                -> handleDestroyCard(room, userId, action)
            ActionType.SACRIFICE_CARD              -> handleSacrificeCard(room, userId, action)
            ActionType.ATTACK                      -> handleAttack(room, userId, action)
            ActionType.PLAY_CARD                   -> handlePlayCard(room, userId, action)
            ActionType.RITUAL_SUMMON               -> handleRitualSummon(room, userId, action)
            ActionType.ACTIVATE_EFFECT             -> handleActivateEffect(room, userId, action)
            ActionType.JOIN_ROOM                   -> host.join(room, userId, action)
            ActionType.DRAW_CARD                   -> handleDraw(room, userId)
            ActionType.MOVE_CARD                   -> handleMove(room, userId, action)
            ActionType.FLIP_CARD                   -> handleFlip(room, userId, action)
            ActionType.SHUFFLE_DECK                -> handleShuffle(room, userId)
            ActionType.ENTER_END_PHASE             -> handleEnterEndPhase(room, userId)
            // One way to end a turn (rule 8.3: through the end phase); "PASS_TURN" is what older boards send
            ActionType.END_TURN, ActionType.PASS_TURN -> handleEndTurn(room, userId)
            // "FORFEIT" is what older boards sent to concede
            ActionType.SURRENDER, ActionType.FORFEIT -> handleSurrender(room, userId)
            ActionType.POST_GAME_CHOICE            -> PostGameActions.choose(host, room, userId, action)
            ActionType.START_GAME                  -> handleStartGame(room, userId, action)
            ActionType.SET_DECK                    -> host.setDeck(room, userId, action)
            ActionType.SET_STARTER                 -> handleSetStarter(room, userId, action)
            ActionType.SET_BOT_LEVEL               -> host.botSetting(type, room, userId, action)
            ActionType.SET_EDITOR_BOT              -> host.botSetting(type, room, userId, action)
            ActionType.SET_EDITOR_SELF_BOT         -> host.botSetting(type, room, userId, action)
            ActionType.SET_BOT_DELAY               -> host.botSetting(type, room, userId, action)
            ActionType.SET_BOT_EDGE                -> host.botSetting(type, room, userId, action)
            ActionType.SET_FIRST_PLAYER            -> handleSetFirstPlayer(room, userId, action)
            ActionType.SET_PAUSED, ActionType.TIME_STEP -> host.timeline(room, userId, action)
            ActionType.SET_RANKED                  -> host.botSetting(type, room, userId, action)
            ActionType.SET_READY                   -> handleSetReady(room, userId, action)
            ActionType.LEAVE_ROOM                  -> host.leave(room, userId)
            ActionType.KICK_PLAYER                 -> host.kick(room, userId, action)
            ActionType.SELECT_STARTER              -> handleSelectStarter(room, userId, action)
            ActionType.CONFIRM_MULLIGAN            -> handleConfirmMulligan(room, userId, action)
            ActionType.SET_MARKER                  -> handleSetMarker(room, userId, action)
            ActionType.SET_SKIP_OPTIONAL           -> handleSetSkipOptional(room, userId, action)
            ActionType.LIFE_HEAL                   -> handleLifeHeal(room, userId)
            ActionType.LIFE_SHUFFLE                -> handleLifeShuffle(room, userId)
            ActionType.LIFE_ALL_DOWN               -> handleLifeAllDown(room, userId)
            ActionType.LIFE_ALL_UP                 -> handleLifeAllUp(room, userId)
            ActionType.LIFE_RESTART                -> handleLifeRestart(room, userId)
            ActionType.DECK_REORDER_TOP            -> handleDeckReorderTop(room, userId, action)
            ActionType.ZONE_REORDER                -> handleZoneReorder(room, userId, action)
            ActionType.DECK_INCLUDE_DISCARD        -> handleDeckIncludeDiscard(room, userId)
            ActionType.DECK_INCLUDE_TRIBUTE        -> handleDeckIncludeTribute(room, userId)
            ActionType.TAP_CARD                    -> handleTapCard(room, userId, action)
            ActionType.SET_COUNTER                 -> handleSetCounter(room, userId, action)
            ActionType.USE_RESOURCES               -> handleUseResources(room, userId, action)
            ActionType.GRANT_RESOURCE              -> handleGrantResource(room, userId)
            ActionType.MODIFY_STRENGTH             -> handleModifyStrength(room, userId, action)
            ActionType.ADD_MATERIAL                -> handleAddMaterial(room, userId, action)
            ActionType.ADD_RESOURCE                -> handleAddResource(room, userId, action)
            ActionType.MOVE_FROM_ATTACHMENT        -> handleMoveFromAttachment(room, userId, action)
            ActionType.SPECIAL_SUMMON              -> handleSpecialSummon(room, userId, action)
            ActionType.SWAP_WITH_MAIN              -> handleSwapWithMain(room, userId, action)
            ActionType.TARGET_CARD                 -> handleTargetCard(room, userId, action)
            ActionType.REORDER_ATTACHMENT          -> handleReorderAttachment(room, userId, action)
            ActionType.RECLASSIFY_ATTACHMENT       -> handleReclassifyAttachment(room, userId, action)
            ActionType.REVEAL_TO_HAND              -> handleRevealToHand(room, userId, action)
            ActionType.REFRESH_CARD_METADATA       -> host.refreshCardMetadata(room, userId, action)
            ActionType.REMOVE_CARD                 -> handleRemoveCard(room, userId, action)
            ActionType.RESTORE_CARD                -> handleRestoreCard(room, userId, action)
            ActionType.CLOSE_ROOM                  -> host.close(room, userId)
            ActionType.EFFECT_SUMMON_RESOLVE       -> handleEffectSummonResolve(room, userId, action)
            ActionType.VIEW_DECK                   -> handleViewDeck(room, userId, action)
            ActionType.VIEW_LIFE_STACK             -> handleViewLifeStack(room, userId)
            ActionType.VIEW_LIFE_CARD              -> handleViewLifeCard(room, userId)
            ActionType.BULK_TO_DECK_BOTTOM         -> handleBulkToDeckBottom(room, userId, action)
            ActionType.BULK_MOVE                   -> handleBulkMove(room, userId, action)
            ActionType.CREATE_DUMMY                -> handleCreateDummy(room, userId, action)
        }
    }
}
