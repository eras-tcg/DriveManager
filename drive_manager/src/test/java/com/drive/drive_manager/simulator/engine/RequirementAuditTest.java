package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Audit of the special summons' Requerimientos (a tool, not a check): reads every distinct requirement exported from
 * the database ({"kind", "requirement", "cards"} per row), runs it through the engine's own readers
 * (SpecialRequirements for Evolución, Ascenso and Materialización; Rituals for Ritual) and writes a Markdown table of
 * what the engine understood and what words of the text it does not model. Runs only when asked:
 * {@code -Drequirements.in=<export.json> -Drequirements.out=<table.md>}.
 */
class RequirementAuditTest {

    /** Words the readers do not model, by what they mean on the card. */
    private static final List<String[]> UNMODELED = List.of(
            new String[]{"\\[[^\\]]+\\]", "clase"},
            new String[]{"sin recursos", "sin recursos"},
            new String[]{"copia", "copias"},
            new String[]{"alg[uú]n", "\"algún\""},
            new String[]{"utilidad", "material utilidad"},
            new String[]{"o menor", "\"o menor\""},
            new String[]{"entre \\d+ y \\d+", "\"entre N y M\""},
            new String[]{"en juego", "\"en juego\""},
            new String[]{"incluido", "\"incluido el invocador\""},
            new String[]{"(?:controlas|tienes)\\s+(?:\\d+\\s+o\\s+m[aá]s|al\\s+menos\\s+\\d+)\\s+(?:aliados|criaturas|\\[)", "cantidad de aliados"},
            new String[]{"criaturan|\\bniel\\b|invocar\\b", "error de tipeo"});

    @Test
    void writesTheAudit() throws Exception {
        String in = System.getProperty("requirements.in");
        String out = System.getProperty("requirements.out");
        assumeTrue(in != null && out != null, "audit only when asked");
        JsonNode rows = new ObjectMapper().readTree(Files.readString(Path.of(in), StandardCharsets.UTF_8));
        StringBuilder md = new StringBuilder("| Método | Requisito | Cartas | Lo que entiende el motor | No modela |\n|---|---|---|---|---|\n");
        int notRead = 0;
        int partial = 0;
        for (JsonNode row : rows) {
            String kind = row.get("kind").asText();
            String text = row.get("requirement").asText();
            List<String> cards = new ArrayList<>();
            row.get("cards").forEach(c -> cards.add(c.asText()));
            CardSlot card = new CardSlot();
            card.setSpecialSummonKind(kind);
            card.setRequirement(text);
            String read;
            if ("ritual".equals(kind)) {
                Rituals.Requirement r = Rituals.requirement(card);
                read = r == null ? null : describe(r);
            } else {
                SpecialRequirements.SpecialRequirement r = SpecialRequirements.specialRequirement(card);
                read = r == null ? null : describe(r);
            }
            List<String> missing = new ArrayList<>();
            String lower = text.toLowerCase();
            for (String[] u : UNMODELED) {
                if (Pattern.compile(u[0]).matcher(lower).find()) {
                    // A Ritual's parts read a class on the extra creature ("+ 1 criatura [ángel] nivel 2 o menor")
                    if ("ritual".equals(kind) && read != null && read.contains("partes") && (u[1].equals("clase") || u[1].contains("menor"))) continue;
                    // Ascenso: "no controles otras criaturas", formation and allies' sum are modeled
                    missing.add(u[1]);
                }
            }
            if (read == null) notRead++;
            else if (!missing.isEmpty()) partial++;
            md.append("| ").append(label(kind)).append(" | ").append(text.replace("|", "\\|")).append(" | ")
                    .append(String.join(", ", cards)).append(" | ").append(read == null ? "**no lo entiende**" : read)
                    .append(" | ").append(String.join(", ", missing)).append(" |\n");
        }
        md.insert(0, rows.size() + " requisitos distintos · " + notRead + " que no entiende · " + partial
                + " con partes que no modela\n\n");
        Files.writeString(Path.of(out), md.toString(), StandardCharsets.UTF_8);
    }

    private static String label(String kind) {
        return switch (kind) {
            case "evolution" -> "Evolución";
            case "promotion" -> "Ascenso";
            case "materialization" -> "Materialización";
            case "ritual" -> "Ritual";
            default -> kind;
        };
    }

    private static String level(int level, int max, boolean atLeast) {
        if (level == 0) return "cualquier nivel";
        if (atLeast) return "nivel " + level + " o mayor";
        return max > level ? "nivel " + level + " a " + max : "nivel " + level;
    }

    private static String describe(SpecialRequirements.SpecialRequirement r) {
        List<String> parts = new ArrayList<>();
        if (r.noSummoner()) parts.add("sin invocador en campo");
        else parts.add("invocador " + level(r.level(), r.maxLevel(), r.atLeast()));
        if (r.summonerZones() != null) parts.add("invocador desde " + r.summonerZones());
        if (r.materialCount() > 0)
            parts.add(r.materialCount() + (r.moreMaterials() ? " o más" : "") + " material(es) " + (r.materialLevel() == 0 ? "de cualquier nivel" : "nivel " + r.materialLevel()));
        if (r.levelSum() > 0) parts.add("materiales que suman " + r.levelSum());
        if (r.materialZones() != null && (r.materialCount() > 0 || r.levelSum() > 0)) parts.add("desde " + r.materialZones());
        if (r.alliesSum() > 0) parts.add("aliados suman " + r.alliesSum() + (r.alliesSumByCost() ? " en coste" : " en nivel"));
        if (r.alone()) parts.add("sin otras criaturas");
        if (r.aloneAtLevel() > 0) parts.add("sin otros aliados nivel " + r.aloneAtLevel());
        if (r.strongest()) parts.add("el más fuerte");
        if (r.formationSize() > 0) parts.add("formación de " + (r.formationAtLeast() ? "al menos " : "") + r.formationSize());
        String s = String.join("; ", parts);
        return r.alternative() == null ? s : s + " **o** " + describe(r.alternative());
    }

    private static String describe(Rituals.Requirement r) {
        if (!r.parts().isEmpty()) return "partes: " + r.parts();
        List<String> parts = new ArrayList<>();
        parts.add(r.sum() ? "sacrificios que suman " + r.level() : "sacrificio " + level(r.level(), r.maxLevel(), r.atLeast()));
        if (r.maxStrength() != null) parts.add("fuerza " + r.maxStrength() + " o menos");
        if (r.extra() > 0) parts.add("+ " + r.extra() + " o más aliados");
        return String.join("; ", parts);
    }
}
