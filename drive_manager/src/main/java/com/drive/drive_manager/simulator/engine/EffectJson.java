package com.drive.drive_manager.simulator.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.NamedType;

import java.util.ArrayList;
import java.util.List;

/**
 * A card's programmed effects as JSON ({@link CardDefinition}), to keep them in the database.
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
        for (Class<?> family : List.of(EffectStep.class, Condition.class, Amount.class)) collect(family, types);
        JsonMapper.Builder builder = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE)
                .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
                .registerSubtypes(types.toArray(NamedType[]::new));
        for (Class<?> family : List.of(EffectStep.class, EffectStep.NeedsTarget.class, Condition.class, Amount.class)) {
            builder.addMixIn(family, Typed.class);
        }
        return builder.build();
    }

    /** Every record of a sealed family, under its simple name. */
    private static void collect(Class<?> type, List<NamedType> into) {
        if (type.isRecord()) {
            into.add(new NamedType(type, type.getSimpleName()));
            return;
        }
        Class<?>[] permitted = type.getPermittedSubclasses();
        if (permitted != null) for (Class<?> sub : permitted) collect(sub, into);
    }
}
