package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.engine.effects.model.Action;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.NamedType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A card's programmed effects as JSON ({@link CardDefinition}): the files in {@code resources/cards}. What a field has by default
 * (false, 0, empty, null) is not written.
 * <p>
 * Steps, conditions and amounts are sealed families of records: each one is written with its record name as
 * {@code "type"} ({@code {"type": "TributeTop", "count": 4}}). Reading only accepts those names and their fields, so the
 * stored JSON is a closed vocabulary the engine already knows: an unknown type or field is an error, never code.
 */
public final class EffectJson {

    private static final JsonMapper MAPPER = mapper();

    private EffectJson() {}

    public static String write(CardDefinition definition) {
        return MAPPER.writeValueAsString(definition);
    }

    /** As it is kept in its file. */
    public static String pretty(CardDefinition definition) {
        return MAPPER.writer().with(SerializationFeature.INDENT_OUTPUT).writeValueAsString(definition);
    }

    /**
     * @throws IllegalArgumentException when the JSON is not a valid definition (unknown step, condition or field)
     */
    public static CardDefinition read(String json) {
        try {
            return MAPPER.readValue(json, CardDefinition.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Efectos inválidos: " + e.getMessage(), e);
        }
    }

    /** The "type" property names the record. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    private interface Typed {}

    private static JsonMapper mapper() {
        List<NamedType> types = new ArrayList<>();
        for (Class<?> family : List.of(EffectStep.class, Action.class, Condition.class, Amount.class, Reaction.class)) collect(family, types);
        JsonMapper.Builder builder = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE)
                // A member without fields (a Kotlin data object) is written as its type alone
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_EMPTY))
                // A primitive false or 0 is what it is when absent; an Integer's 0 is not (null is "not asked")
                .withConfigOverride(boolean.class, o -> o.setInclude(JsonInclude.Value.construct(JsonInclude.Include.NON_DEFAULT, JsonInclude.Include.NON_DEFAULT)))
                .withConfigOverride(int.class, o -> o.setInclude(JsonInclude.Value.construct(JsonInclude.Include.NON_DEFAULT, JsonInclude.Include.NON_DEFAULT)))
                .withConfigOverride(List.class, o -> o.setNullHandling(JsonSetter.Value.forValueNulls(Nulls.AS_EMPTY)))
                .withConfigOverride(Set.class, o -> o.setNullHandling(JsonSetter.Value.forValueNulls(Nulls.AS_EMPTY)))
                .registerSubtypes(types.toArray(NamedType[]::new));
        for (Class<?> family : List.of(EffectStep.class, EffectStep.NeedsTarget.class, Action.class, Condition.class, Amount.class, Reaction.class)) {
            builder.addMixIn(family, Typed.class);
        }
        return builder.build();
    }

    /**
     * Every member of a sealed family, under its simple name: its records, and the Kotlin {@code data object}s that
     * take the place of records without fields (Kotlin has no record without a component). Both are written as
     * {@code {"type": "<name>", …}}; an object has nothing but its type.
     */
    private static void collect(Class<?> type, List<NamedType> into) {
        if (type.isRecord() || kotlinObject(type)) {
            into.add(new NamedType(type, type.getSimpleName()));
            return;
        }
        Class<?>[] permitted = type.getPermittedSubclasses();
        if (permitted != null) for (Class<?> sub : permitted) collect(sub, into);
    }

    /** A Kotlin {@code object}: a final class whose one instance is its static {@code INSTANCE}. */
    private static boolean kotlinObject(Class<?> type) {
        if (type.isInterface() || !java.lang.reflect.Modifier.isFinal(type.getModifiers())) return false;
        try {
            return type.getField("INSTANCE").getType() == type;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }
}
