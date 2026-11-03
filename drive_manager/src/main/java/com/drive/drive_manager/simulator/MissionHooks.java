package com.drive.drive_manager.simulator;

import com.drive.drive_manager.service.CollectionRules;
import com.drive.drive_manager.service.CollectionService;
import com.drive.drive_manager.service.MissionService;
import com.drive.drive_manager.service.ProgressionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a room asks of the player's account: its games count for the missions and the progression, and a deck is
 * played with the copies the player owns (and shines as they own them). Each service is optional, so a room
 * without them (tests) plays anything and counts nothing.
 */
@Component
public class MissionHooks {

    private final MissionService missions;
    private final ProgressionService progression;
    private final CollectionService collections;

    @Autowired
    public MissionHooks(MissionService missions, ProgressionService progression, CollectionService collections) {
        this.missions = missions;
        this.progression = progression;
        this.collections = collections;
    }

    /** The summons, damage and finished game an action brought, for the players' missions (never stops the game). */
    void count(GameRoom room, GameRoom.RoomStatus statusBefore, long seqBefore) {
        if (missions == null && progression == null) return;
        try {
            for (MissionEvents.Progress p : MissionEvents.of(room, statusBefore, seqBefore)) {
                if (missions != null) missions.record(p.userId(), p.counts(), p.amount());
                if (progression != null) progression.record(p.userId(), p.counts(), p.amount());
            }
        } catch (RuntimeException e) {
            // Missions are a bonus: a failure there must not break the room
        }
    }

    /** Copies of the deck the player does not own (0 without a collection to check). */
    int missing(String userId, List<CardSlot> slots) {
        return collections == null ? 0 : collections.missing(userId, slots.stream().map(CardSlot::getCardId).toList());
    }

    /**
     * Each copy shines as its owner has it: the copies of a printing take the player's finest owned copies first, the
     * alternative art before all (both players see them). A copy they lack, or a plain one, has none.
     */
    List<CardSlot> withFinishes(String userId, List<CardSlot> slots) {
        if (collections == null || slots.isEmpty()) return slots;
        Map<String, Map<String, Integer>> owned = collections.of(userId).cards();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CardSlot s : slots) counts.merge(s.getCardId(), 1, Integer::sum);
        Map<String, Iterator<String>> next = new HashMap<>();
        counts.forEach((id, n) -> next.put(id, CollectionRules.copyFinishes(owned.get(id), n).iterator()));
        for (CardSlot s : slots) {
            String key = next.get(s.getCardId()).next();
            String finish = CollectionRules.finishOf(key);
            s.setFinish("foil".equals(finish) || "prism".equals(finish) ? finish : null);
            // An art removed since the copy was pulled: the copy shows the usual one
            String url = CollectionRules.isAlt(key) ? s.getAltArtUrls().get(CollectionRules.artOf(key)) : null;
            s.setAlt(url != null);
            if (url != null) s.setImageUrl(url);
        }
        return slots;
    }
}
