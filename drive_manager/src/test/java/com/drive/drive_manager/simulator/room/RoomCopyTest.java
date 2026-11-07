package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.simulator.bot.BotMatch;
import com.drive.drive_manager.simulator.bot.BotPlayer;
import com.drive.drive_manager.simulator.bot.HeuristicBot;
import com.drive.drive_manager.simulator.engine.Combat;
import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.engine.PendingEffect;
import com.drive.drive_manager.simulator.engine.Prompt;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * RoomCopy copies field by field, without reflection (so the engine can run in the browser). This checks it against
 * the reflective copy it replaced, after every action of whole bot games, and that no field of the copied classes is
 * left out.
 */
class RoomCopyTest {

    /** The fields RoomCopy copies, by class. A field added to one of these classes goes into RoomCopy and here. */
    private static final Map<Class<?>, String> FIELDS = Map.of(
            GameRoom.class, "roomId editor bot ranked replay reserved editorBot editorSelfBot selfBotLevel version paused timelineAt "
                    + "timelineSize botDelayMs botEdge botLevel confirmMandatory status players hostId currentTurnUserId currentPhase "
                    + "sharedCounter createdAt firstChoice emptySince starterSelections mulliganReady manualPlayers adminPlayers "
                    + "postGameChoices eventLog timeline eventSeq chain prompt triggerQueue pendingEvents idSeq pendingRemovals "
                    + "pendingAscension removalDecided combat fieldGrants usedThisTurn firstPlayerId firstTurn starterAdvantageUserId "
                    + "endTurnPending startPhasePending winnerId endReason random headless searchHope startSetup",
            PlayerState.class, "userId username avatar playmat connected disconnectedAt deckId deckName starterCardId ready skipOptional "
                    + "resourceCount turnsStarted hand deck originalDeck lifeStack tributeZone discardPile field",
            CardSlot.class, "instanceId cardId imageUrl faceDown tapped x y cardName cardType strength specialSummonKind starter "
                    + "strengthModifier cost level colors classes specialCost requirement keywords inheritKeywords turnKeywords "
                    + "laterKeywords untilNextAttack fieldSeq turnStrength laterStrength continuousStrength continuousLevel "
                    + "returnAtTurnEnd continuousKeywords floating noUntap noStartUntap materials resources unprogrammed mentions "
                    + "finish alt altArtUrls",
            PendingEffect.class, "id scriptId controllerId sourceInstanceId sourceFieldSeq sourceName label text triggerSeq link "
                    + "inheritedFromName inheritedFromImage costIndex targetIds repeatsDone resolutionId stepIndex sequenceIndex saved "
                    + "subjectId ritualTargetId ritualSummonerId summonedId looked ordered confirmed lastDone lastCount lastTributed "
                    + "keepOnField passed priorityDone",
            Prompt.class, "id kind playerId effectId purpose sourceName sourceImageUrl title instruction chosenLabel cards validIds "
                    + "min max reorderRest restToTop subjectId summonerId cardLabels effects effectText maxLevelSum exactOrNone "
                    + "requiredIds topOrBottom",
            Combat.class, "attackerId attackerOwnerId defenderId targetId blockerId step phase byEffect provokeAsked blockAsked "
                    + "hitsLeft untilEndOfAttack enemyDestroyed skipBlock damageReplaceAsked damageTributeAskedAt");

    @Test
    void copiesEveryFieldOfTheCopiedClasses() {
        FIELDS.forEach((type, listed) -> {
            Set<String> declared = new TreeSet<>();
            for (Field f : type.getDeclaredFields()) if (!Modifier.isStatic(f.getModifiers())) declared.add(f.getName());
            assertThat(declared).as("fields of %s (copy a new one in RoomCopy, then list it here)", type.getSimpleName())
                    .isEqualTo(new TreeSet<>(Arrays.asList(listed.split(" "))));
        });
    }

    @Test
    void copiesAsTheReflectiveCopyAfterEveryAction() {
        int compared = 0;
        String[][] games = {{"st1", "st2"}, {"st3", "st4"}, {"st2", "st3"}, {"st4", "st1"}};
        for (int g = 0; g < games.length; g++) compared += playAndCompare(games[g][0], games[g][1], 1000L + g);
        assertThat(compared).isGreaterThan(200);
    }

    @Test
    void restoreKeepsTheRoomsSettings() {
        RoomService service = new RoomService();
        GameRoom room = startedGame(service, "st1", "st2", 7L);
        GameRoom before = RoomCopy.of(room, new Random(1));
        room.setBotLevel("easy");
        room.setPaused(true);
        BotPlayer bot = new HeuristicBot();
        for (int i = 0; i < 30 && room.getStatus() == GameRoom.RoomStatus.IN_PROGRESS; i++) step(service, room, bot);
        RoomCopy.restore(room, before, new Random(2));
        assertThat(room.getBotLevel()).isEqualTo("easy");
        assertThat(room.isPaused()).isTrue();
        assertThat(room.getEventSeq()).isEqualTo(before.getEventSeq());
        assertThat(room.getPlayers()).isNotSameAs(before.getPlayers());
        same(room.getPlayers(), before.getPlayers(), new IdentityHashMap<>(), "players");
    }

    // --- Games ---

    private static GameRoom startedGame(RoomService service, String hostDeck, String guestDeck, long seed) {
        GameRoom room = service.createRoom("host");
        room.setRandom(new Random(seed));
        String id = room.getRoomId();
        service.applyAction(id, "host", BotPlayer.action("JOIN_ROOM", Map.of("username", "Ana")), false);
        service.applyAction(id, "guest", BotPlayer.action("JOIN_ROOM", Map.of("username", "Beto")), false);
        int[] ids = {0};
        BotMatch.seat(room.getPlayer("host"), hostDeck, ids);
        BotMatch.seat(room.getPlayer("guest"), guestDeck, ids);
        return room;
    }

    /** One move of whichever seat has one; false when neither has. */
    private static boolean step(RoomService service, GameRoom room, BotPlayer bot) {
        for (String seat : List.of("host", "guest")) {
            var action = bot.next(room, seat);
            if (action == null) continue;
            service.applyAction(room.getRoomId(), seat, action, false);
            bot.played(room, action);
            return true;
        }
        return false;
    }

    private static int playAndCompare(String hostDeck, String guestDeck, long seed) {
        RoomService service = new RoomService();
        GameRoom room = startedGame(service, hostDeck, guestDeck, seed);
        BotPlayer bot = new HeuristicBot();
        bot.seed(seed);
        int compared = 0;
        for (int actions = 0; actions < 600 && room.getStatus() != GameRoom.RoomStatus.POST_GAME; actions++) {
            GameRoom explicit = RoomCopy.of(room, new Random(seed + actions));
            GameRoom reflective = ReflectiveRoomCopy.of(room, new Random(seed + actions));
            same(explicit, reflective, new IdentityHashMap<>(), "room");
            apart(room, explicit, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
            compared++;
            if (!step(service, room, bot)) break;
        }
        return compared;
    }

    // --- Deep comparison ---

    private static boolean shared(Object o) {
        return o instanceof String || o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof Enum<?>
                || o instanceof Temporal || o instanceof Record || o instanceof Class<?> || o instanceof GameEvent
                || o instanceof GameLogEntry;
    }

    /** {@code a} and {@code b} hold the same values, with the same objects shared the same way. */
    private static void same(Object a, Object b, Map<Object, Object> paired, String path) {
        if (a == null || b == null) {
            if (a != b) fail(path + ": " + a + " vs " + b);
            return;
        }
        if (shared(a)) {
            assertThat(b).as(path).isEqualTo(a);
            return;
        }
        Object before = paired.get(a);
        if (before != null) {
            if (before != b) fail(path + ": the same object in one copy is two in the other");
            return;
        }
        paired.put(a, b);
        assertThat(b.getClass()).as(path).isEqualTo(a.getClass());
        if (a instanceof Random ra) {
            assertThat(((Random) b).nextLong()).as(path).isEqualTo(ra.nextLong());
        } else if (a instanceof Map<?, ?> ma) {
            Map<?, ?> mb = (Map<?, ?>) b;
            assertThat(mb.keySet()).as(path).isEqualTo(ma.keySet());
            ma.forEach((k, v) -> same(v, mb.get(k), paired, path + "[" + k + "]"));
        } else if (a instanceof Set<?> sa) {
            assertThat((Set<?>) b).as(path).isEqualTo(sa);
        } else if (a instanceof Collection<?> ca) {
            Collection<?> cb = (Collection<?>) b;
            assertThat(cb).as(path).hasSameSizeAs(ca);
            Iterator<?> ib = cb.iterator();
            int i = 0;
            for (Object x : ca) same(x, ib.next(), paired, path + "[" + i++ + "]");
        } else {
            for (Field f : fields(a.getClass())) {
                try {
                    same(f.get(a), f.get(b), paired, path + "." + f.getName());
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    /** No mutable object of the copy is one of the room's. */
    private static void apart(Object original, Object copy, Set<Object> seen) {
        Set<Object> originals = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        collect(original, originals);
        Set<Object> copies = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        collect(copy, copies);
        copies.retainAll(originals);
        assertThat(copies).as("objects the copy shares with the room").isEmpty();
    }

    private static void collect(Object o, Set<Object> into) {
        if (o == null || shared(o) || !into.add(o)) return;
        if (o instanceof Map<?, ?> m) m.values().forEach(v -> collect(v, into));
        else if (o instanceof Collection<?> c) c.forEach(v -> collect(v, into));
        else if (o.getClass().getName().startsWith("com.drive.drive_manager.")) {
            for (Field f : fields(o.getClass())) {
                try {
                    collect(f.get(o), into);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> all = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                all.add(f);
            }
        }
        return all;
    }
}
