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

    public String getDisplayText() {
        return effect != null ? effect.getPlainEffect() : null;
    }
}
