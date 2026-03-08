package com.drive.drive_manager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Effect DTO representing a card effect with instance, restrictions, triggers, and resolution.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Effect {

    private String instance;
    private String ussageLimit;
    private List<effectBlock> effectBlocks;
    private String kind;
    private List<String> tags;

    public String getPlainEffect(){
        StringBuilder plainEffect = new StringBuilder();
        if (instance != null) plainEffect.append("<").append(instance).append("> ");
        if ("once per turn".equals(ussageLimit)) plainEffect.append("[").append(ussageLimit).append("] ");
        if ("once per turn between copies".equals(ussageLimit)) plainEffect.append("(1)").append(" ");
        if (effectBlocks != null) {
            for (effectBlock block : effectBlocks) {
                plainEffect.append(block.getPlainEffect()).append(" ");
            }
        }
        return plainEffect.toString().trim();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class effectBlock {
        private String activationCondition;
        private String cost;
        private String resolution;

        private static String resolveTokens(String text) {
            if (text == null) return null;
            return java.util.regex.Pattern.compile("\\[\\[([^\\]:]+)(?::([^\\]]*))?\\]\\]")
                .matcher(text)
                .replaceAll(mr -> {
                    String kw  = mr.group(1);
                    String val = mr.group(2);
                    return java.util.regex.Matcher.quoteReplacement(
                        (val != null && !val.isEmpty())
                            ? kw.replaceFirst("\\{[^}]+\\}", val)
                            : kw
                    );
                });
        }

        public String getPlainEffect(){
            StringBuilder plainEffect = new StringBuilder();
            if (activationCondition != null) plainEffect.append(resolveTokens(activationCondition)).append(": ");
            if (cost != null)               plainEffect.append(resolveTokens(cost)).append("; ");
            if (resolution != null)         plainEffect.append(resolveTokens(resolution)).append(" ");
            return plainEffect.toString().trim();
        }
    }
}
