package com.drive.drive_manager.simulator.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Writes what is programmed for each card, for the vault's status (skill eras-card-effects, sync_vault_status.py).
 * Only when asked: {@code ./mvnw -o test -Dtest=ProgrammedCardsExportTest -Dexport.programmed=<file.json>}.
 */
class ProgrammedCardsExportTest {

    @Test
    void export() throws Exception {
        String target = System.getProperty("export.programmed");
        assumeTrue(target != null && !target.isBlank(), "solo con -Dexport.programmed=<archivo>");
        Map<String, List<String>> rules = CardEffects.ruleEffects();
        StringBuilder json = new StringBuilder("{\n");
        String sep = "";
        for (CardDefinition d : CardEffects.definitions().values()) {
            json.append(sep).append(entry(d.name(), d.effects().size() + d.continuous().size() + d.auras().size()
                    + d.costModifiers().size() + d.replacements().size() + rules.getOrDefault(d.name(), List.of()).size(),
                    d.inherited().size() + d.inheritedReplacements().size(),
                    d.effects().stream().map(EffectScript::id).toList()));
            sep = ",\n";
        }
        for (Map.Entry<String, List<String>> r : rules.entrySet()) {
            if (CardEffects.definitions().containsKey(r.getKey())) continue;
            json.append(sep).append(entry(r.getKey(), r.getValue().size(), 0, List.of()));
            sep = ",\n";
        }
        Files.writeString(Path.of(target), json.append("\n}\n").toString());
    }

    private static String entry(String name, int own, int inherited, List<String> ids) {
        return "\"" + name + "\": {\"own\": " + own + ", \"inherited\": " + inherited + ", \"scripts\": ["
                + ids.stream().map(i -> "\"" + i + "\"").collect(Collectors.joining(", ")) + "]}";
    }
}
