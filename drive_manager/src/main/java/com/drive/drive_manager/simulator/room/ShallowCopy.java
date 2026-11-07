package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.simulator.engine.GameEvent;

/**
 * Shallow copies (the same values; lists and maps shared) of what {@link StateMask} changes for one viewer, written
 * out field by field: what Spring's BeanUtils.copyProperties did, without reflection, so the engine can run in the
 * browser. Exactly as BeanUtils did, a card's copy takes {@code getLevel()} (the printed level plus the continuous
 * one) as its level, and keeps the continuous level too. {@code ShallowCopyTest} lists every field.
 */
public final class ShallowCopy {

    private ShallowCopy() {}

    /** A GameStateUpdate with the same values (its lists and maps shared). */
    public static GameStateUpdate of(GameStateUpdate o) {
        GameStateUpdate c = new GameStateUpdate();
        c.setRoomId(o.getRoomId());
        c.setStatus(o.getStatus());
        c.setPlayers(o.getPlayers());
        c.setHostId(o.getHostId());
        c.setEditor(o.isEditor());
        c.setBot(o.isBot());
        c.setRanked(o.isRanked());
        c.setReplay(o.isReplay());
        c.setQueued(o.isQueued());
        c.setEditorBot(o.isEditorBot());
        c.setEditorSelfBot(o.isEditorSelfBot());
        c.setSelfBotLevel(o.getSelfBotLevel());
        c.setBotDelayMs(o.getBotDelayMs());
        c.setBotEdge(o.getBotEdge());
        c.setPaused(o.isPaused());
        c.setTimelineAt(o.getTimelineAt());
        c.setTimelineSize(o.getTimelineSize());
        c.setBotLevel(o.getBotLevel());
        c.setBotChoices(o.getBotChoices());
        c.setMulliganReady(o.getMulliganReady());
        c.setFirstChoice(o.getFirstChoice());
        c.setCurrentTurnUserId(o.getCurrentTurnUserId());
        c.setCurrentPhase(o.getCurrentPhase());
        c.setEvent(o.getEvent());
        c.setActorId(o.getActorId());
        c.setError(o.getError());
        c.setSharedCounter(o.getSharedCounter());
        c.setPostGameChoices(o.getPostGameChoices());
        c.setSubjectUserId(o.getSubjectUserId());
        c.setTargetedInstanceId(o.getTargetedInstanceId());
        c.setTargetingSourceInstanceId(o.getTargetingSourceInstanceId());
        c.setRevealedCardImageUrl(o.getRevealedCardImageUrl());
        c.setRevealedCardName(o.getRevealedCardName());
        c.setLogEntry(o.getLogEntry());
        c.setEventLog(o.getEventLog());
        c.setTimeline(o.getTimeline());
        c.setChain(o.getChain());
        c.setPrompt(o.getPrompt());
        c.setCombat(o.getCombat());
        c.setFirstPlayerId(o.getFirstPlayerId());
        c.setStarterAdvantageUserId(o.getStarterAdvantageUserId());
        c.setActivatable(o.getActivatable());
        c.setStructurePlayed(o.getStructurePlayed());
        c.setSpecialSummonable(o.getSpecialSummonable());
        c.setUnplayable(o.getUnplayable());
        c.setLegalActions(o.getLegalActions());
        c.setAttackTargets(o.getAttackTargets());
        c.setWinnerId(o.getWinnerId());
        c.setEndReason(o.getEndReason());
        return c;
    }

    /** A PlayerState with the same values (its lists and maps shared). */
    public static PlayerState of(PlayerState o) {
        PlayerState c = new PlayerState(o.getUserId(), o.getUsername());
        c.setUserId(o.getUserId());
        c.setUsername(o.getUsername());
        c.setAvatar(o.getAvatar());
        c.setPlaymat(o.getPlaymat());
        c.setConnected(o.isConnected());
        c.setDisconnectedAt(o.getDisconnectedAt());
        c.setDeckId(o.getDeckId());
        c.setDeckName(o.getDeckName());
        c.setStarterCardId(o.getStarterCardId());
        c.setReady(o.isReady());
        c.setSkipOptional(o.isSkipOptional());
        c.setResourceCount(o.getResourceCount());
        c.setTurnsStarted(o.getTurnsStarted());
        c.setHand(o.getHand());
        c.setDeck(o.getDeck());
        c.setOriginalDeck(o.getOriginalDeck());
        c.setLifeStack(o.getLifeStack());
        c.setTributeZone(o.getTributeZone());
        c.setDiscardPile(o.getDiscardPile());
        c.setField(o.getField());
        return c;
    }

    /** A CardSlot with the same values (its lists and maps shared). */
    public static CardSlot of(CardSlot o) {
        CardSlot c = new CardSlot();
        c.setInstanceId(o.getInstanceId());
        c.setCardId(o.getCardId());
        c.setImageUrl(o.getImageUrl());
        c.setFaceDown(o.isFaceDown());
        c.setTapped(o.isTapped());
        c.setX(o.getX());
        c.setY(o.getY());
        c.setCardName(o.getCardName());
        c.setCardType(o.getCardType());
        c.setStrength(o.getStrength());
        c.setSpecialSummonKind(o.getSpecialSummonKind());
        c.setStarter(o.isStarter());
        c.setStrengthModifier(o.getStrengthModifier());
        c.setCost(o.getCost());
        c.setLevel(o.getLevel()); // as BeanUtils did: the level with the continuous one added (see the class)
        c.setColors(o.getColors());
        c.setClasses(o.getClasses());
        c.setSpecialCost(o.getSpecialCost());
        c.setRequirement(o.getRequirement());
        c.setKeywords(o.getKeywords());
        c.setInheritKeywords(o.getInheritKeywords());
        c.setTurnKeywords(o.getTurnKeywords());
        c.setLaterKeywords(o.getLaterKeywords());
        c.setUntilNextAttack(o.getUntilNextAttack());
        c.setFieldSeq(o.getFieldSeq());
        c.setTurnStrength(o.getTurnStrength());
        c.setLaterStrength(o.getLaterStrength());
        c.setContinuousStrength(o.getContinuousStrength());
        c.setContinuousLevel(o.getContinuousLevel());
        c.setReturnAtTurnEnd(o.isReturnAtTurnEnd());
        c.setContinuousKeywords(o.getContinuousKeywords());
        c.setFloating(o.getFloating());
        c.setNoUntap(o.getNoUntap());
        c.setNoStartUntap(o.getNoStartUntap());
        c.setMaterials(o.getMaterials());
        c.setResources(o.getResources());
        c.setUnprogrammed(o.getUnprogrammed());
        c.setMentions(o.getMentions());
        c.setFinish(o.getFinish());
        c.setAlt(o.isAlt());
        c.setAltArtUrls(o.getAltArtUrls());
        return c;
    }

    /** A GameEvent with the same values (its lists and maps shared). */
    public static GameEvent of(GameEvent o) {
        GameEvent c = new GameEvent();
        c.setSeq(o.getSeq());
        c.setTs(o.getTs());
        c.setType(o.getType());
        c.setPlayerId(o.getPlayerId());
        c.setCardInstanceId(o.getCardInstanceId());
        c.setCardName(o.getCardName());
        c.setFromZone(o.getFromZone());
        c.setToZone(o.getToZone());
        c.setEffectId(o.getEffectId());
        c.setResolutionId(o.getResolutionId());
        c.setData(o.getData());
        return c;
    }

    /** A GameLogEntry with the same values (its lists and maps shared). */
    public static GameLogEntry of(GameLogEntry o) {
        GameLogEntry c = new GameLogEntry();
        c.setTs(o.getTs());
        c.setText(o.getText());
        c.setEventSeq(o.getEventSeq());
        c.setKind(o.getKind());
        c.setSourceId(o.getSourceId());
        c.setSourceName(o.getSourceName());
        c.setImageUrl(o.getImageUrl());
        c.setInheritedFromName(o.getInheritedFromName());
        c.setInheritedFromImage(o.getInheritedFromImage());
        c.setDetail(o.isDetail());
        return c;
    }
}
