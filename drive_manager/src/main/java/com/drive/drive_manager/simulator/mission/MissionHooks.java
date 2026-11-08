package com.drive.drive_manager.simulator.mission;

import com.drive.drive_manager.service.ActivityLogService;
import com.drive.drive_manager.service.CollectionRules;
import com.drive.drive_manager.service.CollectionService;
import com.drive.drive_manager.service.MissionService;
import com.drive.drive_manager.service.ProgressionRules;
import com.drive.drive_manager.service.ProgressionService;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a room asks of the player's account: its games count for the missions and the progression and go to the
 * administrators' activity log, and a deck is played with the copies the player owns (and shines as they own them).
 * Each service is optional, so a room without them (tests) plays anything and counts nothing.
 */
@Component
public class MissionHooks {

    private final MissionService missions;
    private final ProgressionService progression;
    private final CollectionService collections;
    private final ActivityLogService activityLog;

    @Autowired
    public MissionHooks(MissionService missions, ProgressionService progression, CollectionService collections,
                        ActivityLogService activityLog) {
        this.missions = missions;
        this.progression = progression;
        this.collections = collections;
        this.activityLog = activityLog;
    }

    /** The room as an action found it: its stage, its timeline's last event and its players. */
    public record Before(GameRoom.RoomStatus status, long seq, List<String> players) {
        public static Before of(GameRoom room) {
            return new Before(room.getStatus(), room.getEventSeq(), new ArrayList<>(room.getPlayers().keySet()));
        }
    }

    /**
     * The summons, damage and finished game an action brought, for the players' missions, and the games it started or
     * ended, for the activity log (never stops the game).
     */
    public void count(GameRoom room, Before before) {
        count(room, before, Map.of());
    }

    /**
     * The same, for a game the server did not host: an offline game against the bot, played again on the server
     * (OfflineGames), counted exactly as the server counts its own games against the bot (missions and progression
     * included); `extra` goes into its activity log's detail.
     */
    public void count(GameRoom room, Before before, Map<String, Object> extra) {
        GameActivity.log(activityLog, room, before.status(), before.players(), extra);
        if (missions == null && progression == null) return;
        try {
            for (MissionEvents.Progress p : MissionEvents.of(room, before.status(), before.seq())) {
                // A game against the bot is a mission's game like any other: its own counter is the progression's
                if (missions != null && !ProgressionRules.BOT_GAME_PLAYED.equals(p.counts())) missions.record(p.userId(), p.counts(), p.amount());
                if (progression != null) progression.record(p.userId(), p.counts(), p.amount());
            }
        } catch (RuntimeException e) {
            // Missions are a bonus: a failure there must not break the room
        }
    }

    /** Copies of the deck the player does not own (0 without a collection to check). */
    public int missing(String userId, List<CardSlot> slots) {
        return collections == null ? 0 : collections.missing(userId, slots.stream().map(CardSlot::getCardId).toList());
    }

    /**
     * Each copy shines as its owner has it: the copies of a printing take the player's finest owned copies first, the
     * alternative art before all (both players see them). A copy they lack, or a plain one, has none.
     */
    public List<CardSlot> withFinishes(String userId, List<CardSlot> slots) {
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
