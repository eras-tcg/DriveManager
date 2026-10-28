package com.drive.drive_manager.simulator.engine;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * One entry of the game timeline (rules 11.6 and 11.7): every rules-relevant change is recorded here, in
 * order, so triggers can ask "did X happen during the last resolution" and "was X the last thing that happened".
 * The text log is generated from these events.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GameEvent {

    /** Event types recorded so far. New mechanics add their own. */
    public static final String NORMAL_SUMMON    = "NORMAL_SUMMON";
    public static final String EFFECT_TRIGGERED = "EFFECT_TRIGGERED";
    public static final String EFFECT_ACTIVATED = "EFFECT_ACTIVATED";
    public static final String EFFECT_RESOLVED  = "EFFECT_RESOLVED";
    public static final String LOOK_TOP         = "LOOK_TOP";
    public static final String ADD_TO_HAND      = "ADD_TO_HAND";
    public static final String TRIBUTE          = "TRIBUTE";
    public static final String EFFECT_DECLINED  = "EFFECT_DECLINED";
    public static final String DRAW             = "DRAW";
    /** Destroyed: to the Descarte. data: cause (EFFECT, BATTLE, RULES, MANUAL), ownerId. */
    public static final String DESTROY          = "DESTROY";
    /** Sacrificed: destroyed, but to the Tributos (rule 4.0.4.2). Same data as DESTROY. */
    public static final String SACRIFICE        = "SACRIFICE";
    public static final String EFFECT_SUMMON    = "EFFECT_SUMMON";
    public static final String SPECIAL_SUMMON   = "SPECIAL_SUMMON";
    public static final String SEND             = "SEND";
    public static final String UNTAP            = "UNTAP";
    public static final String GAIN             = "GAIN";
    public static final String DECLARE_ATTACK   = "DECLARE_ATTACK";

    /** Order in the room's timeline (1, 2, 3…). */
    private long seq;
    private long ts;
    private String type;
    /** Player who performed the action (the effect's controller for effect actions). */
    private String playerId;
    private String cardInstanceId;
    private String cardName;
    private String fromZone;
    private String toZone;
    /** Effect instance this event belongs to (null for actions that are not part of an effect). */
    private String effectId;
    /** Resolution in which it happened (rule 11.6: "lo último que ocurrió" is measured per resolution). */
    private String resolutionId;
    private Map<String, Object> data;
}
