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

    private class effectBlock {
        private String activationCondition;
        private String cost;
        private String resolution;

        public String getPlainEffect(){
            StringBuilder plainEffect = new StringBuilder();
            if (activationCondition != null) plainEffect.append(activationCondition).append(": ");
            if (cost != null) plainEffect.append(cost).append("; ");
            if (resolution != null) plainEffect.append(resolution).append(" ");
            return plainEffect.toString().trim();
        }
    }
}
