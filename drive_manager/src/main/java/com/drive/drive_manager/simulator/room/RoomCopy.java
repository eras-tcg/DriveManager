package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.simulator.engine.Combat;
import com.drive.drive_manager.simulator.engine.PendingEffect;
import com.drive.drive_manager.simulator.engine.Prompt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A deep copy of a room, to try moves on it (the bot's look ahead) without touching the real one. Every mutable object
 * of the simulator (the room, its players, cards, effects, prompt and combat) is copied field by field, keeping which
 * ones are the same object (a card in two lists stays one card). Shared, as they never change once made: strings,
 * numbers, enums, dates, records (card definitions, floating effects, offered effects…), and the timeline's events and
 * history lines.
 * <p>
 * Written out field by field, without reflection, so the engine can run where there is none (Kotlin Multiplatform,
 * in the browser). A field added to one of these classes must be copied here too: {@code RoomCopyTest} lists every
 * field and fails until it is.
 */
public final class RoomCopy {

    private final Map<Object, Object> copies = new IdentityHashMap<>();
    /** Seeds the copy's own chance (never the room's: trying moves must not change what the real game draws next). */
    private final java.util.Random chance;

    private RoomCopy(java.util.Random chance) {
        this.chance = chance;
    }

    /** A copy with a chance of its own, seeded from {@code chance}. */
    public static GameRoom of(GameRoom room, java.util.Random chance) {
        return new RoomCopy(chance).room(room);
    }

    /**
     * Puts the state of {@code from} into {@code target} (a copy of it: {@code from} stays as it is), but for the
     * room's own settings (the fields marked {@link RoomSetting}).
     */
    public static void restore(GameRoom target, GameRoom from, java.util.Random chance) {
        GameRoom c = of(from, chance);
        target.setStatus(c.getStatus());
        target.setPlayers(c.getPlayers());
        target.setCurrentTurnUserId(c.getCurrentTurnUserId());
        target.setCurrentPhase(c.getCurrentPhase());
        target.setSharedCounter(c.getSharedCounter());
        target.setFirstChoice(c.getFirstChoice());
        target.setStarterSelections(c.getStarterSelections());
        target.setMulliganReady(c.getMulliganReady());
        target.setPostGameChoices(c.getPostGameChoices());
        target.setEventLog(c.getEventLog());
        target.setTimeline(c.getTimeline());
        target.setEventSeq(c.getEventSeq());
        target.setChain(c.getChain());
        target.setPrompt(c.getPrompt());
        target.setTriggerQueue(c.getTriggerQueue());
        target.setPendingEvents(c.getPendingEvents());
        target.setIdSeq(c.getIdSeq());
        target.setPendingRemovals(c.getPendingRemovals());
        target.setPendingAscension(c.getPendingAscension());
        target.setRemovalDecided(c.getRemovalDecided());
        target.setCombat(c.getCombat());
        target.setFieldGrants(c.getFieldGrants());
        target.setUsedThisTurn(c.getUsedThisTurn());
        target.setFirstPlayerId(c.getFirstPlayerId());
        target.setFirstTurn(c.isFirstTurn());
        target.setStarterAdvantageUserId(c.getStarterAdvantageUserId());
        target.setEndTurnPending(c.isEndTurnPending());
        target.setStartPhasePending(c.isStartPhasePending());
        target.setWinnerId(c.getWinnerId());
        target.setEndReason(c.getEndReason());
        target.setSearchHope(c.getSearchHope());
        target.setStartSetup(c.getStartSetup());
    }

    private GameRoom room(GameRoom o) {
        if (o == null) return null;
        Object done = copies.get(o);
        if (done != null) return (GameRoom) done;
        GameRoom c = new GameRoom();
        copies.put(o, c);
        c.setRoomId(o.getRoomId());
        c.setEditor(o.isEditor());
        c.setBot(o.isBot());
        c.setRanked(o.isRanked());
        c.setReplay(o.isReplay());
        c.setReserved(set(o.getReserved()));
        c.setEditorBot(o.isEditorBot());
        c.setEditorSelfBot(o.isEditorSelfBot());
        c.setSelfBotLevel(o.getSelfBotLevel());
        c.setVersion(o.getVersion());
        c.setPaused(o.isPaused());
        c.setTimelineAt(o.getTimelineAt());
        c.setTimelineSize(o.getTimelineSize());
        c.setBotDelayMs(o.getBotDelayMs());
        c.setBotEdge(o.getBotEdge());
        c.setBotLevel(o.getBotLevel());
        c.setConfirmMandatory(o.isConfirmMandatory());
        c.setStatus(o.getStatus());
        Map<String, PlayerState> players = mapLike(o.getPlayers());
        if (players != null) o.getPlayers().forEach((k, v) -> players.put(k, player(v)));
        c.setPlayers(players);
        c.setHostId(o.getHostId());
        c.setCurrentTurnUserId(o.getCurrentTurnUserId());
        c.setCurrentPhase(o.getCurrentPhase());
        c.setSharedCounter(o.getSharedCounter());
        c.setCreatedAt(o.getCreatedAt());
        c.setFirstChoice(o.getFirstChoice());
        c.setEmptySince(o.getEmptySince());
        c.setStarterSelections(map(o.getStarterSelections()));
        c.setMulliganReady(set(o.getMulliganReady()));
        c.setManualPlayers(set(o.getManualPlayers()));
        c.setAdminPlayers(set(o.getAdminPlayers()));
        c.setPostGameChoices(map(o.getPostGameChoices()));
        c.setEventLog(list(o.getEventLog()));
        c.setTimeline(list(o.getTimeline()));
        c.setEventSeq(o.getEventSeq());
        c.setChain(effects(o.getChain()));
        c.setPrompt(prompt(o.getPrompt()));
        c.setTriggerQueue(effects(o.getTriggerQueue()));
        c.setPendingEvents(list(o.getPendingEvents()));
        c.setIdSeq(o.getIdSeq());
        c.setPendingRemovals(list(o.getPendingRemovals()));
        c.setPendingAscension(o.getPendingAscension());
        c.setRemovalDecided(set(o.getRemovalDecided()));
        c.setCombat(combat(o.getCombat()));
        c.setFieldGrants(list(o.getFieldGrants()));
        c.setUsedThisTurn(set(o.getUsedThisTurn()));
        c.setFirstPlayerId(o.getFirstPlayerId());
        c.setFirstTurn(o.isFirstTurn());
        c.setStarterAdvantageUserId(o.getStarterAdvantageUserId());
        c.setEndTurnPending(o.isEndTurnPending());
        c.setStartPhasePending(o.isStartPhasePending());
        c.setWinnerId(o.getWinnerId());
        c.setEndReason(o.getEndReason());
        c.setRandom(o.getRandom() == null ? null : new java.util.Random(chance.nextLong()));
        c.setHeadless(o.isHeadless());
        c.setSearchHope(o.getSearchHope());
        c.setStartSetup(o.getStartSetup());
        return c;
    }

    private PlayerState player(PlayerState o) {
        if (o == null) return null;
        Object done = copies.get(o);
        if (done != null) return (PlayerState) done;
        PlayerState c = new PlayerState(o.getUserId(), o.getUsername());
        copies.put(o, c);
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
        c.setHand(cards(o.getHand()));
        c.setDeck(cards(o.getDeck()));
        c.setOriginalDeck(cards(o.getOriginalDeck()));
        c.setLifeStack(cards(o.getLifeStack()));
        c.setTributeZone(cards(o.getTributeZone()));
        c.setDiscardPile(cards(o.getDiscardPile()));
        c.setField(cards(o.getField()));
        return c;
    }

    private CardSlot card(CardSlot o) {
        if (o == null) return null;
        Object done = copies.get(o);
        if (done != null) return (CardSlot) done;
        CardSlot c = new CardSlot();
        copies.put(o, c);
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
        c.setLevel(o.getPrintedLevel()); // getLevel() adds the continuous level
        c.setColors(list(o.getColors()));
        c.setClasses(list(o.getClasses()));
        c.setSpecialCost(o.getSpecialCost());
        c.setRequirement(o.getRequirement());
        c.setKeywords(list(o.getKeywords()));
        c.setInheritKeywords(list(o.getInheritKeywords()));
        c.setTurnKeywords(list(o.getTurnKeywords()));
        c.setLaterKeywords(list(o.getLaterKeywords()));
        c.setUntilNextAttack(list(o.getUntilNextAttack()));
        c.setFieldSeq(o.getFieldSeq());
        c.setTurnStrength(o.getTurnStrength());
        c.setLaterStrength(o.getLaterStrength());
        c.setContinuousStrength(o.getContinuousStrength());
        c.setContinuousLevel(o.getContinuousLevel());
        c.setReturnAtTurnEnd(o.isReturnAtTurnEnd());
        c.setContinuousKeywords(list(o.getContinuousKeywords()));
        c.setFloating(list(o.getFloating()));
        c.setNoUntap(o.getNoUntap());
        c.setNoStartUntap(o.getNoStartUntap());
        c.setMaterials(cards(o.getMaterials()));
        c.setResources(cards(o.getResources()));
        c.setUnprogrammed(o.getUnprogrammed());
        c.setMentions(list(o.getMentions()));
        c.setFinish(o.getFinish());
        c.setAlt(o.isAlt());
        c.setAltArtUrls(map(o.getAltArtUrls()));
        return c;
    }

    private PendingEffect effect(PendingEffect o) {
        if (o == null) return null;
        Object done = copies.get(o);
        if (done != null) return (PendingEffect) done;
        PendingEffect c = new PendingEffect();
        copies.put(o, c);
        c.setId(o.getId());
        c.setScriptId(o.getScriptId());
        c.setControllerId(o.getControllerId());
        c.setSourceInstanceId(o.getSourceInstanceId());
        c.setSourceFieldSeq(o.getSourceFieldSeq());
        c.setSourceName(o.getSourceName());
        c.setLabel(o.getLabel());
        c.setText(o.getText());
        c.setTriggerSeq(o.getTriggerSeq());
        c.setLink(o.getLink());
        c.setInheritedFromName(o.getInheritedFromName());
        c.setInheritedFromImage(o.getInheritedFromImage());
        c.setCostIndex(o.getCostIndex());
        c.setTargetIds(list(o.getTargetIds()));
        c.setRepeatsDone(o.getRepeatsDone());
        c.setResolutionId(o.getResolutionId());
        c.setStepIndex(o.getStepIndex());
        c.setSequenceIndex(o.getSequenceIndex());
        Map<String, List<String>> saved = mapLike(o.getSaved());
        if (saved != null) o.getSaved().forEach((k, v) -> saved.put(k, list(v)));
        c.setSaved(saved);
        c.setSubjectId(o.getSubjectId());
        c.setRitualTargetId(o.getRitualTargetId());
        c.setRitualSummonerId(o.getRitualSummonerId());
        c.setSummonedId(o.getSummonedId());
        c.setLooked(cards(o.getLooked()));
        c.setOrdered(o.isOrdered());
        c.setConfirmed(o.isConfirmed());
        c.setLastDone(o.isLastDone());
        c.setLastCount(o.getLastCount());
        c.setLastTributed(list(o.getLastTributed()));
        c.setKeepOnField(o.isKeepOnField());
        c.setPassed(set(o.getPassed()));
        c.setPriorityDone(o.isPriorityDone());
        return c;
    }

    private Prompt prompt(Prompt o) {
        if (o == null) return null;
        Object done = copies.get(o);
        if (done != null) return (Prompt) done;
        Prompt c = new Prompt();
        copies.put(o, c);
        c.setId(o.getId());
        c.setKind(o.getKind());
        c.setPlayerId(o.getPlayerId());
        c.setEffectId(o.getEffectId());
        c.setPurpose(o.getPurpose());
        c.setSourceName(o.getSourceName());
        c.setSourceImageUrl(o.getSourceImageUrl());
        c.setTitle(o.getTitle());
        c.setInstruction(o.getInstruction());
        c.setChosenLabel(o.getChosenLabel());
        c.setCards(cards(o.getCards()));
        c.setValidIds(list(o.getValidIds()));
        c.setMin(o.getMin());
        c.setMax(o.getMax());
        c.setReorderRest(o.isReorderRest());
        c.setRestToTop(o.isRestToTop());
        c.setSubjectId(o.getSubjectId());
        c.setSummonerId(o.getSummonerId());
        c.setCardLabels(map(o.getCardLabels()));
        c.setEffects(list(o.getEffects()));
        c.setEffectText(o.getEffectText());
        c.setMaxLevelSum(o.getMaxLevelSum());
        c.setExactOrNone(o.isExactOrNone());
        c.setRequiredIds(list(o.getRequiredIds()));
        c.setTopOrBottom(o.isTopOrBottom());
        return c;
    }

    private Combat combat(Combat o) {
        if (o == null) return null;
        Object done = copies.get(o);
        if (done != null) return (Combat) done;
        Combat c = new Combat();
        copies.put(o, c);
        c.setAttackerId(o.getAttackerId());
        c.setAttackerOwnerId(o.getAttackerOwnerId());
        c.setDefenderId(o.getDefenderId());
        c.setTargetId(o.getTargetId());
        c.setBlockerId(o.getBlockerId());
        c.setStep(o.getStep());
        c.setPhase(o.getPhase());
        c.setByEffect(o.isByEffect());
        c.setProvokeAsked(o.isProvokeAsked());
        c.setBlockAsked(o.isBlockAsked());
        c.setHitsLeft(o.getHitsLeft());
        c.setUntilEndOfAttack(list(o.getUntilEndOfAttack()));
        c.setEnemyDestroyed(o.isEnemyDestroyed());
        c.setSkipBlock(o.isSkipBlock());
        c.setDamageReplaceAsked(o.isDamageReplaceAsked());
        c.setDamageTributeAskedAt(o.getDamageTributeAskedAt());
        return c;
    }

    // --- Collections: a new one of the same kind, as the reflective copy made them ---

    private List<CardSlot> cards(List<CardSlot> o) {
        if (o == null) return null;
        Object done = copies.get(o);
        if (done != null) return cast(done);
        List<CardSlot> c = new ArrayList<>(o.size());
        copies.put(o, c);
        for (CardSlot s : o) c.add(card(s));
        return c;
    }

    private List<PendingEffect> effects(List<PendingEffect> o) {
        if (o == null) return null;
        Object done = copies.get(o);
        if (done != null) return cast(done);
        List<PendingEffect> c = new ArrayList<>(o.size());
        copies.put(o, c);
        for (PendingEffect e : o) c.add(effect(e));
        return c;
    }

    /** A list of shared values (strings, records, events). */
    private <T> List<T> list(List<T> o) {
        if (o == null) return null;
        Object done = copies.get(o);
        if (done != null) return cast(done);
        List<T> c = new ArrayList<>(o);
        copies.put(o, c);
        return c;
    }

    /** A set of shared values. */
    private <T> Set<T> set(Set<T> o) {
        if (o == null) return null;
        Object done = copies.get(o);
        if (done != null) return cast(done);
        Set<T> c = setLike(o);
        c.addAll(o);
        copies.put(o, c);
        return c;
    }

    /** A map of shared values. */
    private <K, V> Map<K, V> map(Map<K, V> o) {
        Map<K, V> c = mapLike(o);
        if (c != null) c.putAll(o);
        return c;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <K, V> Map<K, V> mapLike(Map<K, V> o) {
        if (o == null) return null;
        Object done = copies.get(o);
        if (done != null) return cast(done);
        Map<K, V> c = o instanceof SortedMap<?, ?> sorted ? new TreeMap(sorted.comparator())
                : o instanceof LinkedHashMap<?, ?> ? new LinkedHashMap<>() : new HashMap<>();
        copies.put(o, c);
        return c;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Set<T> setLike(Collection<T> o) {
        return o instanceof SortedSet<?> sorted ? new TreeSet(sorted.comparator())
                : o instanceof LinkedHashSet<?> ? new LinkedHashSet<>() : new HashSet<>();
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) {
        return (T) o;
    }
}
