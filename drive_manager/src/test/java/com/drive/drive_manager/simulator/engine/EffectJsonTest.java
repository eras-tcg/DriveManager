package com.drive.drive_manager.simulator.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Every programmed card can be written as JSON and read back unchanged, so it can live in the database. */
class EffectJsonTest {

    @Test
    void everyCardSurvivesTheRoundTrip() {
        Map<String, CardDefinition> all = CardEffects.definitions();
        assertTrue(all.size() >= 150, "cartas programadas: " + all.size());
        for (CardDefinition definition : all.values()) {
            String json = EffectJson.write(definition);
            assertEquals(definition, EffectJson.read(json), definition.name() + ": " + json);
        }
    }

    @Test
    void stepsAreWrittenByName() {
        String json = EffectJson.write(CardEffects.definitions().get("eisten"));
        assertTrue(json.contains("\"type\":\"TributeTop\""), json);
        assertTrue(json.contains("\"count\":4"), json);
    }

    @Test
    void unknownStepsAreRejected() {
        String json = """
                {"name":"x","effects":[{"id":"x:a","moment":"ON_SUMMON","mandatory":true,"label":"En Invocación","text":"",
                "cost":[],"steps":[{"type":"RunCode","code":"System.exit(0)"}],"sourceZone":"field","allyTurn":false,"limit":"NONE"}]}""";
        assertThrows(IllegalArgumentException.class, () -> EffectJson.read(json));
    }

    @Test
    void unknownFieldsAreRejected() {
        String json = """
                {"name":"x","effects":[{"id":"x:a","moment":"ON_SUMMON","mandatory":true,"label":"En Invocación","text":"",
                "cost":[],"steps":[{"type":"TributeTop","count":4,"extra":1}],"sourceZone":"field","allyTurn":false,"limit":"NONE"}]}""";
        assertThrows(IllegalArgumentException.class, () -> EffectJson.read(json));
    }
}
