package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;

import java.util.ArrayList;
import java.util.List;

/**
 * Which cards an effect may choose ("1 carta Violeta o Blanca", "1 criatura [bestia] nivel 1").
 * Colors and classes are alternatives (any of them), unless {@code colorAndClass} asks for both; every other limit must
 * hold too. Empty = any card. Plain data, so a card's effects can be stored and read back.
 *
 * @param activeOnly    "activo": not exhausted
 * @param exhaustedOnly "agotado"
 * @param minStrength   "de fuerza N o más" (its current strength)
 * @param maxCost       "de coste N o menos"
 * @param name          "llamada X" (by its name, or a name it can be treated as)
 * @param notName       "que no sea X"
 * @param starterOnly   "Iniciador"
 * @param colorAndClass "Violeta [bestia]": one of the colors and one of the classes (not either)
 */
public record CardFilter(List<String> colors, List<String> classes, String cardType, Integer level, Integer maxLevel, Integer maxStrength,
                         boolean activeOnly, boolean exhaustedOnly, Integer minLevel, Integer minStrength, Integer maxCost, String name,
                         String notName, boolean starterOnly, boolean colorAndClass) {

    public CardFilter {
        colors = colors == null ? List.of() : List.copyOf(colors);
        classes = classes == null ? List.of() : List.copyOf(classes);
    }

    public CardFilter(List<String> colors, List<String> classes, String cardType, Integer level, Integer maxLevel) {
        this(colors, classes, cardType, level, maxLevel, null);
    }

    public CardFilter(List<String> colors, List<String> classes, String cardType, Integer level, Integer maxLevel, Integer maxStrength) {
        this(colors, classes, cardType, level, maxLevel, maxStrength, false, false, null, null, null, null, null, false, false);
    }

    public static CardFilter any() {
        return new CardFilter(List.of(), List.of(), null, null, null);
    }

    public static CardFilter colors(String... colors) {
        return new CardFilter(List.of(colors), List.of(), null, null, null);
    }

    public static CardFilter creatures() {
        return new CardFilter(List.of(), List.of(), "creature", null, null);
    }

    public CardFilter withColors(String... colors) {
        return new CardFilter(List.of(colors), classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass);
    }

    public CardFilter withClasses(String... classes) {
        return new CardFilter(colors, List.of(classes), cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass);
    }

    public CardFilter withLevel(int level) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass);
    }

    public CardFilter withMaxLevel(int maxLevel) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass);
    }

    /** "nivel N o mayor". */
    public CardFilter withMinLevel(int minLevel) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass);
    }

    /** "de fuerza N o menos" (its current strength). */
    public CardFilter withMaxStrength(int maxStrength) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass);
    }

    /** "de fuerza N o más" (its current strength). */
    public CardFilter withMinStrength(int minStrength) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass);
    }

    /** "de coste N o menos". */
    public CardFilter withMaxCost(int maxCost) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass);
    }

    /** "activo": not exhausted. */
    public CardFilter active() {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, true, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass);
    }

    /** "agotado". */
    public CardFilter exhausted() {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, true, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass);
    }

    /** "llamada X" (by its name). */
    public CardFilter named(String name) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass);
    }

    /** "que no sea X". */
    public CardFilter notNamed(String notName) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass);
    }

    /** "Iniciador". */
    public CardFilter starter() {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, true, colorAndClass);
    }

    /** "Violeta [bestia]": the color and one of the classes (not either). */
    public CardFilter colorAndClass(String color, String... classes) {
        return new CardFilter(List.of(color), List.of(classes), cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel,
                minStrength, maxCost, name, notName, starterOnly, true);
    }

    public boolean matches(CardSlot card) {
        if (cardType != null && !cardType.equals(card.getCardType())) return false;
        List<String> cardColors = card.getColors() == null ? List.of() : card.getColors();
        List<String> cardClasses = card.getClasses() == null ? List.of() : card.getClasses();
        boolean colorMatch = colors.stream().anyMatch(cardColors::contains);
        boolean classMatch = classes.stream().anyMatch(c -> cardClasses.stream().anyMatch(cc -> cc.equalsIgnoreCase(c)));
        if (colorAndClass) {
            if (!colorMatch || !classMatch) return false;
        } else if (!(colors.isEmpty() && classes.isEmpty()) && !colorMatch && !classMatch) {
            return false;
        }
        if (level != null && !level.equals(card.getLevel())) return false;
        if (maxLevel != null && (card.getLevel() == null || card.getLevel() > maxLevel)) return false;
        if (minLevel != null && (card.getLevel() == null || card.getLevel() < minLevel)) return false;
        if (maxStrength != null && EffectEngine.strength(card) > maxStrength) return false;
        if (minStrength != null && EffectEngine.strength(card) < minStrength) return false;
        if (maxCost != null && (card.getCost() == null || card.getCost() > maxCost)) return false;
        if (activeOnly && card.isTapped()) return false;
        if (exhaustedOnly && !card.isTapped()) return false;
        if (name != null && !CardEffects.isNamed(card, name)) return false;
        if (notName != null && notName.equalsIgnoreCase(card.getCardName())) return false;
        return !starterOnly || card.isStarter();
    }

    /** Spanish description for prompts ("Violeta o Blanca", "[bestia] nivel 1"). */
    public String describe() {
        List<String> parts = new ArrayList<>();
        if (!colorAndClass) {
            for (String c : colors) parts.add(colorName(c));
            for (String c : classes) parts.add("[" + c + "]");
        }
        String text = String.join(" o ", parts);
        if (level != null) text += " nivel " + level;
        if (maxLevel != null) text += " nivel " + maxLevel + " o menor";
        if (maxStrength != null) text += " de fuerza " + maxStrength + " o menos";
        if (activeOnly) text += " activo";
        if (colorAndClass) text += " " + String.join(" o ", colors.stream().map(CardFilter::colorName).toList()) + " "
                + String.join(" o ", classes.stream().map(c -> "[" + c + "]").toList());
        if (exhaustedOnly) text += " agotado";
        if (minStrength != null) text += " de fuerza " + minStrength + " o más";
        if (name != null) text += " \"" + name + "\"";
        if (notName != null) text += " que no sea \"" + notName + "\"";
        if (minLevel != null) text += " nivel " + minLevel + " o mayor";
        if (maxCost != null) text += " de coste " + maxCost + " o menos";
        if (starterOnly) text += " Iniciador";
        return text.trim();
    }

    static String colorName(String code) {
        return switch (code) {
            case "B" -> "Azul";
            case "G" -> "Verde";
            case "P" -> "Violeta";
            case "R" -> "Roja";
            case "W" -> "Blanca";
            default -> code;
        };
    }
}
