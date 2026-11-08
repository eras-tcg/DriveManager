package com.drive.drive_manager.simulator;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's core (the rules, the effects, the bot, the actions and the room's state) imports neither Spring, nor
 * Jackson, nor reflection, nor the server's classes (Mongo's documents, the rooms' service, missions, ranks, the kept
 * games): it is to become Kotlin Multiplatform and run in the browser too (plan of the Kotlin engine, phase 1).
 * Whatever the server adds around it lives outside these files.
 */
class CoreBoundaryTest {

    private static final Path ROOT = Path.of("src/main/java/com/drive/drive_manager/simulator");
    /** Where the files already converted to Kotlin are (phase 2), under the same packages. */
    private static final Path KOTLIN = Path.of("src/main/kotlin/com/drive/drive_manager/simulator");

    /** The core's packages, every file in them... */
    private static final List<String> PACKAGES = List.of("engine", "bot", "actions");
    /** ...but for the server's own (the bot's scheduler) and the JVM's readers, which Kotlin replaces in phase 2. */
    private static final Set<String> OUTSIDE = Set.of(
            "bot/BotRooms.java",          // the server running the bots (scheduler, locks, messages)
            "bot/ValueModelFiles.java",   // the bot's model read and saved with Jackson
            "bot/ServerBotResourceSource.java", // the bot's resources from the class path, offered to the core
            "engine/CardFiles.java",      // the card files found with Spring's classpath scanning
            "engine/CardFilesCheck.java", // the server's start-up check of the card files
            "engine/ServerCardSource.java", // the card files offered to the core (CardSource)
            "engine/EffectJson.java");    // the card files read with Jackson (kotlinx.serialization in Kotlin)
    /** The core's files in other packages. */
    private static final List<String> FILES = List.of(
            "room/ActionType.java", "room/CardLocator.java", "room/CardSlot.java", "room/FloatingEffect.java",
            "room/GameAction.java", "room/GameLogEntry.java", "room/GameRoom.java", "room/GameStateUpdate.java",
            "room/PlayerState.java", "room/RoomCopy.java", "room/RoomEngine.java", "room/RoomGuard.java",
            "room/RoomHost.java", "room/RoomOptions.java", "room/RoomSetting.java", "room/RoomSetup.java",
            "room/ShallowCopy.java", "room/SimMessages.java", "room/SimulatorLimits.java", "room/StateMask.java",
            "room/Zone.java", "rank/RankRules.java", "replay/MatchSetup.java");

    private static final Pattern FORBIDDEN = Pattern.compile("^import (static )?("
            + "org\\.springframework|com\\.fasterxml\\.jackson|tools\\.jackson|java\\.lang\\.reflect"
            + "|com\\.drive\\.drive_manager\\.(dto|service|repository|model|config|controller)\\."
            + "|com\\.drive\\.drive_manager\\.simulator\\.(mission|replay\\.Match(History|Controller|Recorder|Replay)|room\\.("
            + "RoomService|RoomMessages|RoomDecks|DeckLoader|CardMeta|RoomRegistry|RoomMembership|GameController|RoomRestController"
            + "|GameDisconnectListener|TestTimeline)|bot\\.(BotRooms|ValueModelFiles)|engine\\.(CardFiles|EffectJson))\\b)");
    /** Reflection without an import. */
    private static final Pattern REFLECTION = Pattern.compile("\\b(getDeclaredFields?|getDeclaredMethods?|Class\\.forName|getPermittedSubclasses|setAccessible)\\(");

    @Test
    void theCoreImportsNoneOfTheServer() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : coreFiles()) {
            String name = (file.startsWith(KOTLIN) ? KOTLIN : ROOT).relativize(file).toString().replace('\\', '/');
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim().replaceAll(";$", "");
                if (line.startsWith("import ") && !line.endsWith(";")) line = line + ";";
                if (FORBIDDEN.matcher(line).find()) found.add(name + ":" + (i + 1) + " " + line);
                Matcher m = REFLECTION.matcher(line);
                if (!line.startsWith("*") && !line.startsWith("//") && m.find()) found.add(name + ":" + (i + 1) + " " + line);
            }
        }
        assertThat(found).as("the engine's core must not use these (see the class)").isEmpty();
    }

    @Test
    void everyListedFileExists() {
        for (String f : FILES) assertThat(source(f)).as(f).exists();
        for (String f : OUTSIDE) assertThat(source(f)).as(f).exists();
    }

    /** A listed file, in Java or already in Kotlin. */
    private static Path source(String javaName) {
        Path java = ROOT.resolve(javaName);
        return Files.exists(java) ? java : KOTLIN.resolve(javaName.replaceAll("\\.java$", ".kt"));
    }

    private static List<Path> coreFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path root : List.of(ROOT, KOTLIN)) for (String pkg : PACKAGES) {
            if (!Files.isDirectory(root.resolve(pkg))) continue;
            try (Stream<Path> all = Files.walk(root.resolve(pkg))) {
                all.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt"))
                        .filter(p -> !OUTSIDE.contains(root.relativize(p).toString().replace('\\', '/')))
                        .forEach(files::add);
            }
        }
        FILES.forEach(f -> files.add(source(f)));
        return files;
    }
}
