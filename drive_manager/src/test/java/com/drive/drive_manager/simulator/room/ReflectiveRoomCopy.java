package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.simulator.engine.GameRandom;
import com.drive.drive_manager.simulator.engine.GameEvent;
import org.springframework.objenesis.Objenesis;
import org.springframework.objenesis.ObjenesisStd;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.temporal.Temporal;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * The copy RoomCopy made before phase 1 (by reflection), kept for RoomCopyTest to check the explicit one against.
 * A deep copy of a room, to try moves on it (the bot's look ahead) without touching the real one. Every object of
 * the simulator is copied field by field, keeping which ones are the same object (a card in two lists stays one
 * card). Shared, as they never change once made: strings, numbers, enums, dates, records (card definitions,
 * floating effects, offered effects…), and the timeline's events and history lines.
 */
final class ReflectiveRoomCopy {

    private static final Objenesis NEW = new ObjenesisStd(true);
    /** The fields to copy of each class, its superclasses included. */
    private static final Map<Class<?>, List<Field>> FIELDS = new ConcurrentHashMap<>();

    private final Map<Object, Object> copies = new IdentityHashMap<>();
    /** Seeds the copy's own chance (never the room's: trying moves must not change what the real game draws next). */
    private final GameRandom chance;

    private ReflectiveRoomCopy(GameRandom chance) {
        this.chance = chance;
    }

    /** A copy with a chance of its own, seeded from {@code chance}. */
    public static GameRoom of(GameRoom room, GameRandom chance) {
        return (GameRoom) new ReflectiveRoomCopy(chance).copy(room);
    }

    /**
     * Puts the state of {@code from} into {@code target} (a copy of it: {@code from} stays as it is), but for the
     * room's own settings (the fields marked {@link RoomSetting}).
     */
    public static void restore(GameRoom target, GameRoom from, GameRandom chance) {
        GameRoom copy = of(from, chance);
        try {
            for (Field f : fields(GameRoom.class)) if (!f.isAnnotationPresent(RoomSetting.class)) f.set(target, f.get(copy));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object copy(Object o) {
        if (o == null || shared(o)) return o;
        Object done = copies.get(o);
        if (done != null) return done;
        Class<?> type = o.getClass();
        if (type.isArray()) {
            int n = Array.getLength(o);
            Object out = Array.newInstance(type.getComponentType(), n);
            copies.put(o, out);
            for (int i = 0; i < n; i++) Array.set(out, i, copy(Array.get(o, i)));
            return out;
        }
        if (o instanceof GameRandom) {
            Object out = new GameRandom(chance.nextLong());
            copies.put(o, out);
            return out;
        }
        if (o instanceof Map<?, ?> map) {
            Map out = map instanceof SortedMap<?, ?> sorted ? new TreeMap(sorted.comparator())
                    : map instanceof LinkedHashMap<?, ?> ? new LinkedHashMap<>() : new HashMap<>();
            copies.put(o, out);
            map.forEach((k, v) -> out.put(copy(k), copy(v)));
            return out;
        }
        if (o instanceof Collection<?> collection) {
            Collection out = collection instanceof SortedSet<?> sorted ? new TreeSet(sorted.comparator())
                    : collection instanceof LinkedHashSet<?> ? new LinkedHashSet<>()
                    : collection instanceof Set<?> ? new HashSet<>() : new ArrayList<>(collection.size());
            copies.put(o, out);
            for (Object item : collection) out.add(copy(item));
            return out;
        }
        if (!type.getName().startsWith("com.drive.drive_manager.")) {
            throw new IllegalStateException("A room holds a " + type.getName() + " that RoomCopy cannot copy");
        }
        Object out = NEW.newInstance(type);
        copies.put(o, out);
        try {
            for (Field f : fields(type)) f.set(out, copy(f.get(o)));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static boolean shared(Object o) {
        return o instanceof String || o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof Enum<?>
                || o instanceof Temporal || o instanceof Record || o instanceof Class<?>
                || o instanceof GameEvent || o instanceof GameLogEntry;
    }

    private static List<Field> fields(Class<?> type) {
        return FIELDS.computeIfAbsent(type, t -> {
            List<Field> all = new ArrayList<>();
            for (Class<?> c = t; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    all.add(f);
                }
            }
            return all;
        });
    }
}
