package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmStatic

/**
 * What a rules prompt asks for, sent to the clients as the prompt's `purpose` string ([id], the
 * constant's name). A prompt of a card's own effect carries none.
 */
enum class PromptPurpose {
    BLOCK,
    PROVOKE,
    ADVANCE,
    RITUAL,
    /** Evolución, Ascenso and Materialización from the hand: the summoner, then (Materialización) the material. */
    SPECIAL,
    /** "puedes agotar esta carta para reducir el coste especial" (Bosque Frondoso): asked before a special summon from the hand. */
    COST_TAP,
    SPECIAL_FRONT,
    /** Flambreik: "cuando esta carta fuera a invocar por Evolución: puedes enviar 5 de tus tributos [dragón]…". */
    SUMMONER_DISCOUNT,
    REINFORCE,
    LIFE_PLAY,
    ORDER,
    /** A card about to leave the field: its owner may replace it ("en su lugar"), or a card that pays it (Caballero Leal's hand card). */
    REPLACE,
    REPLACE_HAND,
    OPTIONAL,
    PRIORITY,
    REPLACE_MATERIALS,
    BLOCK_SWAP,
    ASCEND_ATTACK,
    ASSAULT,
    DAMAGE_REPLACE,
    /** The materials of a special summon made instead of an ally's removal (EXO-91, SARS-2). */
    REPLACE_SUMMON,
    /** What pays a guard of allies (Albert Collier): 1 material of it and 1 card to sacrifice. */
    REPLACE_GUARD,
    /** Sarkhydrax: the Recurso tributed and the one taken before the removal. */
    REPLACE_RESOURCES,
    /** Carnirex: the ally sacrificed to summon it ("summon-sacrifice|card|ally or -"). */
    SUMMON_SACRIFICE,
    /** The cards tributed instead of receiving damage (Estoque De Los Guerreros Jurados). */
    DAMAGE_TRIBUTE;

    /** The purpose string the prompt carries. */
    fun id(): String = name

    /** Whether `purpose` (a prompt's purpose string, maybe null) is this one. */
    fun `is`(purpose: String?): Boolean = name == purpose

    companion object {
        /** The purpose with that string, if there is one. */
        @JvmStatic
        fun parse(purpose: String?): PromptPurpose? {
            if (purpose != null) for (p in entries) if (p.name == purpose) return p
            return null
        }
    }
}
