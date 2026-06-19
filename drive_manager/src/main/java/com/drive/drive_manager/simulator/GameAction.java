package com.drive.drive_manager.simulator;

import lombok.Data;

import java.util.Map;

@Data
public class GameAction {
    private String              type;
    private Map<String, Object> payload;

    public String getString(String key) {
        Object val = payload != null ? payload.get(key) : null;
        return val instanceof String s ? s : null;
    }

    public Double getDouble(String key) {
        Object val = payload != null ? payload.get(key) : null;
        return val instanceof Number n ? n.doubleValue() : null;
    }

    public int getInt(String key, int defaultValue) {
        Object val = payload != null ? payload.get(key) : null;
        return val instanceof Number n ? n.intValue() : defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object val = payload != null ? payload.get(key) : null;
        return val instanceof Boolean b ? b : defaultValue;
    }
}
