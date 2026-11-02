package com.drive.drive_manager.simulator.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Rules on the code itself (vault: Plan de refactorización del motor, section 4). */
class EngineShapeTest {

    private static final Path SIMULATOR = Path.of("src/main/java/com/drive/drive_manager/simulator");

    private static final int MAX_LINES = 500;

    /** Still over the limit, at most this long: each one can only shrink. */
    private static final Map<String, Integer> TOO_LONG = Map.ofEntries(
            Map.entry("Compose.java", 890),
            Map.entry("RoomService.java", 840),
            Map.entry("EngineContext.java", 730),
            Map.entry("Condition.java", 665),
            Map.entry("EffectEngine.java", 625),
            Map.entry("Chain.java", 590),
            Map.entry("CombatRules.java", 560),
            Map.entry("Removals.java", 550),
            Map.entry("Answers.java", 515),
            Map.entry("ZoneActions.java", 510),
            Map.entry("StepHelpers.java", 505));

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(SIMULATOR)) {
            return files.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void noClassIsTooLong() throws IOException {
        List<String> tooLong = new ArrayList<>();
        for (Path file : sources()) {
            String name = file.getFileName().toString();
            long lines;
            try (Stream<String> all = Files.lines(file)) {
                lines = all.count();
            }
            if (lines > TOO_LONG.getOrDefault(name, MAX_LINES)) tooLong.add(name + ": " + lines);
        }
        assertThat(tooLong).isEmpty();
    }

    /** A card is a file in resources/cards: its name is not in the code. */
    @Test
    void noCardNameInTheCode() throws IOException {
        java.util.Set<String> names = new java.util.HashSet<>(CardFiles.load().stream().map(CardDefinition::name).toList());
        Pattern literal = Pattern.compile("\"([^\"\\\\]*)\"");
        List<String> found = new ArrayList<>();
        for (Path file : sources()) {
            for (String line : Files.readAllLines(file)) {
                String code = line.strip();
                if (code.startsWith("*") || code.startsWith("/*") || code.startsWith("//")) continue;
                Matcher m = literal.matcher(code);
                while (m.find()) {
                    String text = m.group(1).trim().toLowerCase();
                    if (names.contains(text)) found.add(file.getFileName() + ": " + m.group(1));
                }
            }
        }
        assertThat(found).isEmpty();
    }
}
