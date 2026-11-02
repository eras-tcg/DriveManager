package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.engine.effects.model.Action;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Writes the catalog of the effect language from the code, with the cards that use each word (vault: Catálogo del lenguaje). Only when asked:
 * {@code ./mvnw -o test -Dtest=LanguageCatalogExportTest -Dexport.catalog=<file.md>}.
 */
class LanguageCatalogExportTest {

    private static final Pattern TYPE = Pattern.compile("\"type\":\"(\\w+)\"");

    @Test
    void export() throws Exception {
        String target = System.getProperty("export.catalog");
        assumeTrue(target != null && !target.isBlank(), "solo con -Dexport.catalog=<archivo>");
        TreeMap<String, Set<String>> users = new TreeMap<>();
        for (CardDefinition card : CardFiles.load()) {
            Matcher m = TYPE.matcher(EffectJson.write(card));
            while (m.find()) users.computeIfAbsent(m.group(1), k -> new TreeSet<>()).add(card.name());
        }
        StringBuilder out = new StringBuilder();
        out.append(section("Acciones básicas", Action.class, users));
        out.append(section("Pasos: control de flujo y especiales", EffectStep.class, users));
        out.append(section("Condiciones", Condition.class, users));
        out.append(section("Cantidades", Amount.class, users));
        out.append(section("Reacciones", Reaction.class, users));
        Files.writeString(Path.of(target), out.toString());
    }

    private static String section(String title, Class<?> family, TreeMap<String, Set<String>> users) {
        List<Class<?>> records = new ArrayList<>();
        collect(family, records);
        records.sort((a, b) -> a.getSimpleName().compareTo(b.getSimpleName()));
        StringBuilder s = new StringBuilder("## " + title + " (" + records.size() + ")\n\n| Nombre | Campos | Cartas | Ejemplo |\n|---|---|---|---|\n");
        for (Class<?> record : records) {
            Set<String> cards = users.getOrDefault(record.getSimpleName(), Set.of());
            String fields = Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.joining(", "));
            s.append("| `").append(record.getSimpleName()).append("` | ").append(fields).append(" | ").append(cards.size()).append(" | ")
                    .append(cards.stream().findFirst().orElse("")).append(" |\n");
        }
        return s.append('\n').toString();
    }

    private static void collect(Class<?> type, List<Class<?>> into) {
        if (type.isRecord()) {
            into.add(type);
            return;
        }
        Class<?>[] permitted = type.getPermittedSubclasses();
        if (permitted != null) for (Class<?> sub : permitted) collect(sub, into);
    }
}
