package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.engine.cards.CardBundle;
import com.drive.drive_manager.simulator.engine.cards.CardReader;
import com.drive.drive_manager.simulator.json.SimulatorJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The engine's common card reader ({@link CardReader}, kotlinx.serialization, what the server and the site run since step C)
 * builds from every card file the same definition the server's Jackson reader ({@link EffectJson}, kept here as the oracle)
 * built: the same JSON written by both of the server's mappers, byte for byte, the same record equality, hash and text, and
 * the same object graph down to the classes of its lists and sets. It refuses what Jackson refused, and the card bundle the
 * engine's jar carries holds exactly the card files of the class path.
 */
class CardReaderParityTest {

    private static final JsonMapper ROOM_JSON = SimulatorJson.configure(JsonMapper.builder()).build();

    @Test
    void theBundleHoldsTheCardFilesOfTheClassPath() {
        List<CardBundle.File> bundle = CardBundle.files(CardFiles.bundle());
        List<Map.Entry<String, String>> files = JacksonCardFiles.files();
        assertThat(bundle).hasSize(356);
        assertThat(files).hasSize(356);
        for (int i = 0; i < files.size(); i++) {
            assertThat(files.get(i).getKey()).endsWith("/cards/" + bundle.get(i).getPath());
            assertThat(bundle.get(i).getText()).as(bundle.get(i).getPath()).isEqualTo(files.get(i).getValue());
        }
    }

    @Test
    void everyCardReadsAsJacksonReadIt() {
        int compared = 0;
        for (CardBundle.File file : CardBundle.files(CardFiles.bundle())) {
            CardDefinition jackson = EffectJson.read(file.getText());
            CardDefinition common = CardReader.read(file.getText());
            String where = file.getPath();
            assertThat(EffectJson.write(common)).as(where).isEqualTo(EffectJson.write(jackson));
            assertThat(EffectJson.pretty(common)).as(where).isEqualTo(EffectJson.pretty(jackson));
            assertThat(ROOM_JSON.writeValueAsString(common)).as(where).isEqualTo(ROOM_JSON.writeValueAsString(jackson));
            assertThat(common).as(where).isEqualTo(jackson);
            assertThat(jackson).as(where).isEqualTo(common);
            assertThat(common.hashCode()).as(where).isEqualTo(jackson.hashCode());
            assertThat(common.toString()).as(where).isEqualTo(jackson.toString());
            sameGraph(jackson, common, where);
            compared++;
        }
        assertThat(compared).isEqualTo(356);
    }

    @Test
    void theServerLoadsWhatJacksonLoaded() {
        List<CardDefinition> common = CardFiles.load();
        List<CardDefinition> jackson = JacksonCardFiles.load();
        assertThat(common).hasSize(356).isEqualTo(jackson);
        assertThat(new ServerCardSource().cards()).isEqualTo(jackson);
    }

    /** No file relies on Jackson's leniency (coercions, explicit defaults): each is the JSON Jackson writes for it, as a tree. */
    @Test
    void everyCardFileIsCanonical() {
        JsonMapper plain = JsonMapper.builder().build();
        for (CardBundle.File file : CardBundle.files(CardFiles.bundle())) {
            assertThat(plain.readTree(file.getText())).as(file.getPath())
                    .isEqualTo(plain.readTree(EffectJson.write(EffectJson.read(file.getText()))));
        }
    }

    private static final String EFFECT = "{\"name\":\"x\",\"effects\":[{\"id\":\"x:a\",\"moment\":\"ON_SUMMON\",\"steps\":[%s]}]}";

    /** What EffectJson refused, the common reader refuses (IllegalArgumentException, "Efectos inválidos: …"). */
    @Test
    void refusesWhatJacksonRefused() {
        List<String> broken = new ArrayList<>(List.of(
                "{\"name\":\"x\",\"bogus\":1}",
                "{\"name\":\"x\",\"name\":\"y\"}",
                "{\"name\":\"x\",\"traits\":{\"alias\":\"a\",\"al\\u0069as\":\"b\"}}",
                "{\"name\":{}}",
                "{\"name\":[]}",
                "{\"name\":\"x\",\"effects\":{}}",
                "{\"name\":\"x\",\"effects\":\"\"}",
                "{\"name\":\"x\",\"effects\":[{\"id\":\"a\",\"moment\":\"on_summon\"}]}",
                "{\"name\":\"x\",\"effects\":[{\"id\":\"a\",\"moment\":\"\"}]}",
                "{\"name\":\"x\",\"effects\":[{\"id\":\"a\",\"limit\":\"TWICE\"}]}",
                "{\"name\":\"x\",\"effects\":[{\"id\":\"a\",\"steps\":{\"type\":\"IfPossible\"}}]}",
                "{\"name\":\"x\"} {}",
                "{\"name\":\"x\",}",
                "{\"name\":\"x\"",
                "{name:\"x\"}",
                "// x\n{\"name\":\"x\"}",
                "[]",
                "\"x\"",
                ""));
        for (String steps : List.of(
                "{\"type\":\"RunCode\",\"code\":\"System.exit(0)\"}",
                "{\"type\":\"IfPossible\",\"x\":1}",
                "{\"type\":\"IfChosenGone\",\"covers\":1,\"extra\":1}",
                "{}",
                "{\"type\":null}",
                "{\"type\":3}",
                "{\"covers\":1}",
                "\"IfPossible\"",
                "[]",
                "{\"type\":\"Fixed\",\"value\":1}",
                "{\"type\":\"IfChosenGone\",\"covers\":99999999999}",
                "{\"type\":\"IfChosenGone\",\"covers\":{}}",
                "{\"type\":\"Do\",\"action\":{\"type\":\"Gems\",\"amount\":{\"type\":\"Gems\",\"bogus\":1}}}",
                "{\"type\":\"Do\",\"action\":{\"type\":\"Fixed\",\"value\":1}}")) {
            broken.add(EFFECT.formatted(steps));
        }
        for (String json : broken) {
            assertThatThrownBy(() -> EffectJson.read(json)).as("Jackson: " + json).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> CardReader.read(json)).as("common: " + json)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("Efectos inválidos: ");
        }
    }

    /**
     * Where the two differ: Jackson's coercions (a text for an int or a boolean, a number for a name, an enum's index, a
     * fraction cut to an int) and a null inside a list (Jackson kept it and the definition broke later). No card file uses
     * them ({@link #everyCardFileIsCanonical}); the common reader refuses them.
     */
    @Test
    void refusesJacksonsCoercions() {
        List<String> lenient = List.of(
                "{\"name\":5}",
                "{\"name\":true}",
                "{\"name\":\"x\",\"effects\":[{\"id\":\"a\",\"mandatory\":\"true\"}]}",
                "{\"name\":\"x\",\"effects\":[{\"id\":\"a\",\"mandatory\":1}]}",
                "{\"name\":\"x\",\"effects\":[{\"id\":\"a\",\"moment\":1}]}",
                EFFECT.formatted("{\"type\":\"IfChosenGone\",\"covers\":2.7}"),
                EFFECT.formatted("{\"type\":\"IfChosenGone\",\"covers\":\"3\"}"),
                "{\"name\":\"x\",\"effects\":[null]}");
        for (String json : lenient) {
            CardDefinition read;
            try {
                read = EffectJson.read(json);
            } catch (RuntimeException e) {
                throw new AssertionError("Jackson read " + json + " before", e);
            }
            assertThat(read).as(json).isNotNull();
            assertThatThrownBy(() -> CardReader.read(json)).as(json).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** The loader's own checks (a broken file, a card or an effect id twice): the same failures, the same messages. */
    @Test
    void theLoaderRefusesWhatItRefused() {
        String a = "{\"name\":\"a\",\"effects\":[{\"id\":\"a:1\"}]}";
        List<List<String[]>> cases = List.of(
                List.of(new String[] {"E1/a.json", a}, new String[] {"E2/a.json", a}),
                List.of(new String[] {"E1/a.json", a}, new String[] {"E1/b.json", "{\"name\":\"b\",\"effects\":[{\"id\":\"a:1\"}]}"}),
                List.<String[]>of(new String[] {"E1/b.json", "{\"name\":\"b\",\"effects\":[{\"id\":\"b:1\"}],\"inherited\":[{\"id\":\"b:1\"}]}"}),
                List.of(new String[] {"E1/a.json", a}, new String[] {"ST1/broken.json", "{\"name\":\"c\",\"bogus\":1}"}));
        for (List<String[]> files : cases) {
            List<Map.Entry<String, String>> jacksonFiles = files.stream().map(f -> Map.entry("cards/" + f[0], f[1])).toList();
            List<CardBundle.File> commonFiles = files.stream().map(f -> new CardBundle.File(f[0], f[1])).toList();
            Throwable jackson = catchFailure(() -> JacksonCardFiles.load(jacksonFiles));
            Throwable common = catchFailure(() -> CardBundle.read(commonFiles));
            assertThat(jackson).isInstanceOf(IllegalStateException.class);
            assertThat(common).isInstanceOf(IllegalStateException.class);
            String message = jackson.getMessage();
            if (message.contains("Efectos inválidos")) {
                // The file's name, then the reader's own explanation
                String prefix = message.substring(0, message.indexOf("Efectos inválidos: ") + "Efectos inválidos: ".length());
                assertThat(common.getMessage()).startsWith(prefix);
            } else {
                assertThat(common.getMessage()).isEqualTo(message);
            }
        }
    }

    private static Throwable catchFailure(Runnable run) {
        try {
            run.run();
        } catch (Throwable t) {
            return t;
        }
        throw new AssertionError("no failure");
    }

    /**
     * The same object graph: records component by component, lists and sets of the same class with the same elements in the
     * same order, the same enum constants, members without fields (Kotlin objects) of the same class.
     */
    private static void sameGraph(Object a, Object b, String path) {
        if (a == null || b == null) {
            assertThat(b).as(path).isEqualTo(a);
            return;
        }
        assertThat(b.getClass()).as(path).isEqualTo(a.getClass());
        Class<?> type = a.getClass();
        if (type.isRecord()) {
            for (RecordComponent c : type.getRecordComponents()) {
                try {
                    var field = type.getDeclaredField(c.getName());
                    field.setAccessible(true);
                    sameGraph(field.get(a), field.get(b), path + "." + c.getName());
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(path + "." + c.getName(), e);
                }
            }
        } else if (a instanceof Collection<?> ca) {
            Collection<?> cb = (Collection<?>) b;
            assertThat(cb).as(path).hasSize(ca.size());
            Iterator<?> ib = cb.iterator();
            int i = 0;
            for (Object x : ca) sameGraph(x, ib.next(), path + "[" + i++ + "]");
        } else if (type.isEnum() || a instanceof String || a instanceof Number || a instanceof Boolean) {
            assertThat(b).as(path).isEqualTo(a);
        } else {
            // A Kotlin data object: equal to any instance of its class
            assertThat(type.getDeclaredFields()).as(path + " (" + type.getName() + ")")
                    .allMatch(f -> java.lang.reflect.Modifier.isStatic(f.getModifiers()));
            assertThat(b).as(path).isEqualTo(a);
        }
    }
}
