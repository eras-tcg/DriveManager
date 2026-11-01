package com.drive.drive_manager.simulator.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Writes every programmed card as JSON, plus the effects run as rules, for the refactor's inventory (vault: Plan de
 * refactorización del motor). Only when asked: {@code ./mvnw -o test -Dtest=DefinitionsExportTest -Dexport.definitions=<file.json>}.
 */
class DefinitionsExportTest {

    @Test
    void export() throws Exception {
        String target = System.getProperty("export.definitions");
        assumeTrue(target != null && !target.isBlank(), "solo con -Dexport.definitions=<archivo>");
        String cards = CardEffects.definitions().values().stream().map(EffectJson::write).collect(Collectors.joining(",\n"));
        String rules = CardEffects.ruleEffects().entrySet().stream().map(EffectJsonRule::of).collect(Collectors.joining(",\n"));
        Files.writeString(Path.of(target), "{\"cards\":[\n" + cards + "\n],\n\"rules\":{\n" + rules + "\n}}\n");
    }

    private static final class EffectJsonRule {
        static String of(Map.Entry<String, java.util.List<String>> e) {
            return quote(e.getKey()) + ":[" + e.getValue().stream().map(EffectJsonRule::quote).collect(Collectors.joining(",")) + "]";
        }

        static String quote(String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }
}
