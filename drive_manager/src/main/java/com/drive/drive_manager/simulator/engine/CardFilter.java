package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 * @param notColors     "no-blanca": none of these colors
 * @param nameContains  "con "Primera Línea" en su nombre"
 * @param minCost       "de coste N o mayor"
 * @param cardTypes     "utilidad o estructura": one of these types (with {@code cardType}, both must hold)
 * @param mentions      "con [tipo] en su texto": of that type, with it in its name, or written in its effects or inherited effects
 * @param backOnly      "en tu retaguardia" (not in the Línea frontal)
 * @param anyOf         alternatives that cannot be written as one filter ("1 utilidad Violeta o [brujería]"): it matches when this
 *                      filter and one of them do
 */
public record CardFilter(List<String> colors, List<String> classes, String cardType, Integer level, Integer maxLevel, Integer maxStrength,
                         boolean activeOnly, boolean exhaustedOnly, Integer minLevel, Integer minStrength, Integer maxCost, String name,
                         String notName, boolean starterOnly, boolean colorAndClass, List<String> notColors, String nameContains,
                         Integer minCost, List<String> cardTypes, List<CardFilter> anyOf, String mentions, boolean backOnly) {

    public CardFilter {
        colors = colors == null ? List.of() : List.copyOf(colors);
        classes = classes == null ? List.of() : List.copyOf(classes);
        notColors = notColors == null ? List.of() : List.copyOf(notColors);
        cardTypes = cardTypes == null ? List.of() : List.copyOf(cardTypes);
        anyOf = anyOf == null ? List.of() : List.copyOf(anyOf);
    }

    public CardFilter(List<String> colors, List<String> classes, String cardType, Integer level, Integer maxLevel, Integer maxStrength,
                      boolean activeOnly, boolean exhaustedOnly, Integer minLevel, Integer minStrength, Integer maxCost, String name,
                      String notName, boolean starterOnly, boolean colorAndClass) {
        this(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength, maxCost, name, notName,
                starterOnly, colorAndClass, List.of(), null, null, List.of(), List.of(), null, false);
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
                maxCost, name, notName, starterOnly, colorAndClass, notColors, nameContains, minCost, cardTypes, anyOf, mentions, backOnly);
    }

    public CardFilter withClasses(String... classes) {
        return new CardFilter(colors, List.of(classes), cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, notColors, nameContains, minCost, cardTypes, anyOf, mentions, backOnly);
    }

    public CardFilter withLevel(int level) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, notColors, nameContains, minCost, cardTypes, anyOf, mentions, backOnly);
    }

    public CardFilter withMaxLevel(int maxLevel) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, notColors, nameContains, minCost, cardTypes, anyOf, mentions, backOnly);
    }

    /** "nivel N o mayor". */
    public CardFilter withMinLevel(int minLevel) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, notColors, nameContains, minCost, cardTypes, anyOf, mentions, backOnly);
    }

    /** "de fuerza N o menos" (its current strength). */
    public CardFilter withMaxStrength(int maxStrength) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, notColors, nameContains, minCost, cardTypes, anyOf, mentions, backOnly);
    }

    /** "de fuerza N o más" (its current strength). */
    public CardFilter withMinStrength(int minStrength) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, notColors, nameContains, minCost, cardTypes, anyOf, mentions, backOnly);
    }

    /** "de coste N o menos". */
    public CardFilter withMaxCost(int maxCost) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, notColors, nameContains, minCost, cardTypes, anyOf, mentions, backOnly);
    }

    /** "activo": not exhausted. */
    /** Only creatures, on top of what it asks. */
    public CardFilter asCreature() {
        return new CardFilter(colors, classes, "creature", level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, notColors, nameContains, minCost, cardTypes, anyOf, mentions, backOnly);
    }

    /** Only structures, on top of what it asks. */
    public CardFilter asStructure() {
        return new CardFilter(colors, classes, "structure", level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, notColors, nameContains, minCost, cardTypes, anyOf, mentions, backOnly);
    }

    public CardFilter active() {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, true, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, notColors, nameContains, minCost, cardTypes, anyOf, mentions, backOnly);
    }

    /** "agotado". */
    public CardFilter exhausted() {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, true, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, notColors, nameContains, minCost, cardTypes, anyOf, mentions, backOnly);
    }

    /** "llamada X" (by its name). */
    public CardFilter named(String name) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, notColors, nameContains, minCost, cardTypes, anyOf, mentions, backOnly);
    }

    /** "que no sea X". */
    public CardFilter notNamed(String notName) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, notColors, nameContains, minCost, cardTypes, anyOf, mentions, backOnly);
    }

    /** "Iniciador". */
    public CardFilter starter() {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, true, colorAndClass, notColors, nameContains, minCost, cardTypes, anyOf, mentions, backOnly);
    }

    /** "Violeta [bestia]": the color and one of the classes (not either). */
    public CardFilter colorAndClass(String color, String... classes) {
        return new CardFilter(List.of(color), List.of(classes), cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel,
                minStrength, maxCost, name, notName, starterOnly, true, notColors, nameContains, minCost, cardTypes, anyOf, mentions, backOnly);
    }

    /** "no-blanca": none of these colors. */
    public CardFilter notColors(String... notColors) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, List.of(notColors), nameContains, minCost, cardTypes, anyOf, mentions, backOnly);
    }

    /** "con "X" en su nombre". */
    public CardFilter nameContains(String text) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, notColors, text, minCost, cardTypes, anyOf, mentions, backOnly);
    }

    /** "de coste N o mayor". */
    public CardFilter withMinCost(int minCost) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, notColors, nameContains, minCost, cardTypes, anyOf, mentions, backOnly);
    }

    /** "utilidad", "estructura", "utilidad o estructura" ("creature", "utility", "structure"). */
    public CardFilter ofTypes(String... types) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, notColors, nameContains, minCost, List.of(types), anyOf, mentions, backOnly);
    }

    /** One of these, besides everything this filter asks ("1 utilidad Violeta o [brujería]"). */
    public CardFilter orAnyOf(CardFilter... alternatives) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, notColors, nameContains, minCost, cardTypes, List.of(alternatives), mentions, backOnly);
    }

    /** "1 estructura": any card of those types. */
    /** "con [tipo] en su texto". */
    public CardFilter mentioning(String type) {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, notColors, nameContains, minCost, cardTypes, anyOf, type, backOnly);
    }

    /** "en tu retaguardia". */
    public CardFilter inBack() {
        return new CardFilter(colors, classes, cardType, level, maxLevel, maxStrength, activeOnly, exhaustedOnly, minLevel, minStrength,
                maxCost, name, notName, starterOnly, colorAndClass, notColors, nameContains, minCost, cardTypes, anyOf, mentions, true);
    }

    public static CardFilter type(String... types) {
        return any().ofTypes(types);
    }

    public boolean matches(CardSlot card) {
        // "puede ser considerada como una criatura de fuerza N por efectos aliados" (Fósil)
        Integer asCreature = EffectEngine.creatureForEffects(card);
        if (cardType != null && !cardType.equals(card.getCardType()) && !("creature".equals(cardType) && asCreature != null)) return false;
        List<String> cardColors = card.getColors() == null ? List.of() : card.getColors();
        List<String> cardClasses = card.getClasses() == null ? List.of() : card.getClasses();
        boolean colorMatch = colors.stream().anyMatch(cardColors::contains);
        boolean classMatch = classes.stream().anyMatch(c -> cardClasses.stream().anyMatch(cc -> cc.equalsIgnoreCase(c)));
        if (colorAndClass) {
            if (!colorMatch || !classMatch) return false;
        } else if (!(colors.isEmpty() && classes.isEmpty()) && !colorMatch && !classMatch) {
            return false;
        }
        // Any level it can count as for the card looking (Abraza Almas)
        if ((level != null || maxLevel != null || minLevel != null) && EffectEngine.levels(card).stream()
                .noneMatch(l -> (level == null || level.equals(l)) && (maxLevel == null || l <= maxLevel) && (minLevel == null || l >= minLevel))) return false;
        int strength = asCreature != null && card.getStrength() == null ? asCreature : EffectEngine.strength(card);
        if (maxStrength != null && strength > maxStrength) return false;
        if (minStrength != null && strength < minStrength) return false;
        if (maxCost != null && (card.getCost() == null || card.getCost() > maxCost)) return false;
        if (activeOnly && card.isTapped()) return false;
        if (exhaustedOnly && !card.isTapped()) return false;
        if (name != null && !CardEffects.isNamed(card, name)) return false;
        if (notName != null && notName.equalsIgnoreCase(card.getCardName())) return false;
        if (!notColors.isEmpty() && notColors.stream().anyMatch(cardColors::contains)) return false;
        if (nameContains != null && (card.getCardName() == null
                || !card.getCardName().toLowerCase(Locale.ROOT).contains(nameContains.toLowerCase(Locale.ROOT)))) return false;
        if (minCost != null && (card.getCost() == null || card.getCost() < minCost)) return false;
        if (!cardTypes.isEmpty() && !cardTypes.contains(card.getCardType()) && !(cardTypes.contains("creature") && asCreature != null)) return false;
        if (!anyOf.isEmpty() && anyOf.stream().noneMatch(f -> f.matches(card))) return false;
        if (mentions != null) {
            String t = mentions.toLowerCase(Locale.ROOT);
            boolean ofType = card.getClasses().stream().anyMatch(c -> c.equalsIgnoreCase(t));
            boolean inName = card.getCardName() != null && card.getCardName().toLowerCase(Locale.ROOT).contains(t);
            if (!ofType && !inName && (card.getMentions() == null || !card.getMentions().contains(t))) return false;
        }
        if (backOnly && EffectEngine.inFrontLine(card)) return false;
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
        if (!cardTypes.isEmpty()) text = String.join(" o ", cardTypes.stream().map(CardFilter::typeName).toList()) + " " + text;
        if (!notColors.isEmpty()) text += " " + String.join(" ", notColors.stream().map(c -> "no-" + colorName(c).toLowerCase(Locale.ROOT)).toList());
        if (nameContains != null) text += " con \"" + nameContains + "\" en su nombre";
        if (minCost != null) text += " de coste " + minCost + " o mayor";
        if (!anyOf.isEmpty()) text += " " + String.join(" o ", anyOf.stream().map(CardFilter::describe).toList());
        if (mentions != null) text += " con [" + mentions + "] en su texto";
        if (backOnly) text += " en tu retaguardia";
        return text.trim();
    }

    static String typeName(String type) {
        return switch (type) {
            case "creature" -> "criatura";
            case "utility" -> "utilidad";
            case "structure" -> "estructura";
            default -> type;
        };
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
