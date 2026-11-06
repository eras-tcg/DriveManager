package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.simulator.engine.CardEffects;

import java.util.ArrayList;
import java.util.List;

/** A card's metadata as the simulator keeps it on each copy (CardSlot): printed properties, abilities, what is not programmed. */
public final class CardMeta {

    private CardMeta() {}

    /** Fills the card slot from its metadata (only a slot of that card): what the board shows and the rules engine reads. */
    public static void apply(CardSlot slot, String cardId, Card meta) {
        if (!cardId.equals(slot.getCardId())) return;
        slot.setCardName(meta.getCardName());
        java.util.Map<String, String> arts = new java.util.HashMap<>();
        meta.getAltArts().forEach(a -> arts.put(a.getId(), a.getUrl()));
        slot.setAltArtUrls(arts);
        slot.setCardType(meta.getCardType());
        slot.setStrength(meta.getStrength());
        slot.setSpecialSummonKind(meta.getSpecialSummonKind());
        slot.setStarter(Boolean.TRUE.equals(meta.getStarter()));
        // Printed properties the rules engine reads (cost to pay, filters by color/class/level)
        slot.setCost(meta.getCost());
        slot.setLevel(meta.getLevel());
        slot.setClasses(meta.getCardClasses() != null ? new ArrayList<>(meta.getCardClasses()) : new ArrayList<>());
        slot.setSpecialCost(meta.getSpecialCost());
        slot.setRequirement(meta.getRequirement());
        slot.setKeywords(keywordNames(meta.getKeywordEffects()));
        slot.setInheritKeywords(keywordNames(meta.getInheritKeywordEffects()));
        List<String> allKeywords = new ArrayList<>(slot.getKeywords());
        allKeywords.addAll(slot.getInheritKeywords());
        slot.setUnprogrammed(CardEffects.pending(meta.getCardName(),
                meta.getEffects() == null ? 0 : meta.getEffects().size(),
                meta.getInheritEffects() == null ? 0 : meta.getInheritEffects().size(), allKeywords));
        slot.setMentions(mentionedTypes(meta));
        List<String> colors = new ArrayList<>();
        if (meta.getColorIdentity() != null) colors.add(meta.getColorIdentity());
        if (meta.getColors() != null) meta.getColors().stream().filter(c -> !colors.contains(c)).forEach(colors::add);
        slot.setColors(colors);
    }

    /** The [tipos] written in its effects and inherited effects ("[[Reactivar]]" is a keyword, not a type). */
    public static List<String> mentionedTypes(Card meta) {
        List<String> found = new ArrayList<>();
        java.util.regex.Pattern type = java.util.regex.Pattern.compile("(?<!\\[)\\[([^\\[\\]]+)\\](?!\\])");
        List<com.drive.drive_manager.dto.Effect> all = new ArrayList<>();
        if (meta.getEffects() != null) all.addAll(meta.getEffects());
        if (meta.getInheritEffects() != null) all.addAll(meta.getInheritEffects());
        for (com.drive.drive_manager.dto.Effect e : all) {
            if (e.getEffectBlocks() == null) continue;
            for (com.drive.drive_manager.dto.Effect.effectBlock b : e.getEffectBlocks()) {
                for (String text : java.util.Arrays.asList(b.getActivationCondition(), b.getCost(), b.getResolution())) {
                    if (text == null) continue;
                    java.util.regex.Matcher m = type.matcher(text);
                    while (m.find()) {
                        String t = m.group(1).trim().toLowerCase(java.util.Locale.ROOT);
                        if (!found.contains(t)) found.add(t);
                    }
                }
            }
        }
        return found;
    }

    /** "Letalidad {cantidad}" + 1 → "Letalidad 1". */
    static List<String> keywordNames(List<com.drive.drive_manager.dto.CardKeywordEffect> effects) {
        List<String> keywords = new ArrayList<>();
        if (effects == null) return keywords;
        effects.forEach(k -> {
            if (k.getKeyword() == null) return;
            String name = k.getKeyword().replaceAll("\\{[^}]*}", k.getNumber() != null ? k.getNumber() : "").trim();
            keywords.add(name.replaceAll("\\s+", " "));
        });
        return keywords;
    }
}
