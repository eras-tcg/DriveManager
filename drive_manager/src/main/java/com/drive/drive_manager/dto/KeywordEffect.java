package com.drive.drive_manager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class KeywordEffect {
    private String keyword;
    private Effect effect;
    
    // Legacy fields for backward compatibility
    private String parameter;
    private String description;
    
    // Constructor for legacy data
    public KeywordEffect(String keyword, String parameter, String description) {
        this.keyword = keyword;
        this.parameter = parameter;
        this.description = description;
        this.effect = null;
    }
    
    // Constructor for new full effect
    public KeywordEffect(String keyword, Effect effect) {
        this.keyword = keyword;
        this.effect = effect;
        this.parameter = null;
        this.description = null;
    }
    
    public String getDisplayText() {
        if (effect != null) {
            return effect.getPlainEffect();
        } else {
            // Legacy format
            if (parameter != null && !parameter.trim().isEmpty()) {
                return keyword + " (" + parameter + "): " + (description != null ? description : "");
            } else {
                return keyword + ": " + (description != null ? description : "");
            }
        }
    }
}
