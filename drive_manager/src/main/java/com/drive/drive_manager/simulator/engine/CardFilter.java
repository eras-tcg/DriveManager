package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;

import java.util.List;

/**
 * Which cards an effect may choose ("1 carta Violeta o Blanca", "1 [dragón] nivel 1").
 * Colors and classes are alternatives (any of them); level limits must hold too. Empty = any card.
 */
public record CardFilter(List<String> colors, List<String> classes, Integer level, Integer maxLevel) {

    public static CardFilter any() {
        return new CardFilter(List.of(), List.of(), null, null);
    }

    public static CardFilter colors(String... colors) {
        return new CardFilter(List.of(colors), List.of(), null, null);
    }

    public boolean matches(CardSlot card) {
        boolean anyOf = colors.isEmpty() && classes.isEmpty();
        if (!anyOf) {
            List<String> cardColors = card.getColors() == null ? List.of() : card.getColors();
            List<String> cardClasses = card.getClasses() == null ? List.of() : card.getClasses();
            anyOf = colors.stream().anyMatch(cardColors::contains)
                    || classes.stream().anyMatch(c -> cardClasses.stream().anyMatch(cc -> cc.equalsIgnoreCase(c)));
        }
        if (!anyOf) return false;
        if (level != null && !level.equals(card.getLevel())) return false;
        return maxLevel == null || (card.getLevel() != null && card.getLevel() <= maxLevel);
    }

    /** Spanish description for prompts ("Violeta o Blanca"). */
    public String describe() {
        List<String> parts = new java.util.ArrayList<>();
        for (String c : colors) parts.add(colorName(c));
        for (String c : classes) parts.add("[" + c + "]");
        String text = String.join(" o ", parts);
        if (level != null) text += " nivel " + level;
        if (maxLevel != null) text += " nivel " + maxLevel + " o menor";
        return text.trim();
    }

    private static String colorName(String code) {
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
