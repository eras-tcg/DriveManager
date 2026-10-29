package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Which cards an effect may choose ("1 carta Violeta o Blanca", "1 criatura [bestia] nivel 1").
 * Colors and classes are alternatives (any of them); card type and level limits must hold too. Empty = any card.
 */
public record CardFilter(List<String> colors, List<String> classes, String cardType, Integer level, Integer maxLevel, Integer maxStrength,
                         boolean activeOnly, Predicate<CardSlot> extra, String extraText) {

    public CardFilter(List<String> colors, List<String> classes, String cardType, Integer level, Integer maxLevel) {
        this(colors, classes, cardType, level, maxLevel, null, false, null, null);
    }

    public CardFilter(List<String> colors, List<String> classes, String cardType, Integer level, Integer maxLevel, Integer maxStrength) {
        this(colors, classes, cardType, level, maxLevel, maxStrength, false, null, null);
    }

    /** "activo": not exhausted. */
    public CardFilter active() {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, true, extra, extraText);
    }

    /** Another condition every card must meet too ("agotado", "de fuerza 4 o más", "llamada X"), described as {@code text}. */
    public CardFilter where(String text, Predicate<CardSlot> condition) {
        Predicate<CardSlot> both = extra == null ? condition : extra.and(condition);
        String described = extraText == null ? text : extraText + " " + text;
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, both, described);
    }

    /** "agotado". */
    public CardFilter exhausted() {
        return where("agotado", CardSlot::isTapped);
    }

    /** "de fuerza N o más" (its current strength). */
    public CardFilter withMinStrength(int minStrength) {
        return where("de fuerza " + minStrength + " o más", c -> EffectEngine.strength(c) >= minStrength);
    }

    /** "llamada X" (by its name). */
    public CardFilter named(String name) {
        return where("\"" + name + "\"", c -> CardEffects.isNamed(c, name));
    }

    /** "Violeta [bestia]": the color and one of the classes (not either). */
    public CardFilter colorAndClass(String color, String... classes) {
        List<String> wanted = List.of(classes);
        return new CardFilter(List.of(), List.of(), cardType, level, maxLevel, maxStrength, activeOnly, extra, extraText)
                .where(colorName(color) + " " + String.join(" o ", wanted.stream().map(c -> "[" + c + "]").toList()),
                        c -> c.getColors() != null && c.getColors().contains(color) && c.getClasses() != null
                                && c.getClasses().stream().anyMatch(cc -> wanted.stream().anyMatch(cc::equalsIgnoreCase)));
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
        return new CardFilter(List.of(colors), classes, cardType, level, maxLevel, maxStrength, activeOnly, extra, extraText);
    }

    public CardFilter withClasses(String... classes) {
        return new CardFilter(colors, List.of(classes), cardType, level, maxLevel, maxStrength, activeOnly, extra, extraText);
    }

    public CardFilter withLevel(int level) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, extra, extraText);
    }

    public CardFilter withMaxLevel(int maxLevel) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, extra, extraText);
    }

    /** "de fuerza N o menos" (its current strength). */
    public CardFilter withMaxStrength(int maxStrength) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, extra, extraText);
    }

    public boolean matches(CardSlot card) {
        if (cardType != null && !cardType.equals(card.getCardType())) return false;
        boolean anyOf = colors.isEmpty() && classes.isEmpty();
        if (!anyOf) {
            List<String> cardColors = card.getColors() == null ? List.of() : card.getColors();
            List<String> cardClasses = card.getClasses() == null ? List.of() : card.getClasses();
            anyOf = colors.stream().anyMatch(cardColors::contains)
                    || classes.stream().anyMatch(c -> cardClasses.stream().anyMatch(cc -> cc.equalsIgnoreCase(c)));
        }
        if (!anyOf) return false;
        if (level != null && !level.equals(card.getLevel())) return false;
        if (maxStrength != null && EffectEngine.strength(card) > maxStrength) return false;
        if (activeOnly && card.isTapped()) return false;
        if (extra != null && !extra.test(card)) return false;
        return maxLevel == null || (card.getLevel() != null && card.getLevel() <= maxLevel);
    }

    /** Spanish description for prompts ("Violeta o Blanca", "[bestia] nivel 1"). */
    public String describe() {
        List<String> parts = new ArrayList<>();
        for (String c : colors) parts.add(colorName(c));
        for (String c : classes) parts.add("[" + c + "]");
        String text = String.join(" o ", parts);
        if (level != null) text += " nivel " + level;
        if (maxLevel != null) text += " nivel " + maxLevel + " o menor";
        if (maxStrength != null) text += " de fuerza " + maxStrength + " o menos";
        if (activeOnly) text += " activo";
        if (extraText != null) text += " " + extraText;
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
