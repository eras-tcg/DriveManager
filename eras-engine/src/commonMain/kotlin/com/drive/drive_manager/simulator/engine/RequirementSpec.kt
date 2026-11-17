package com.drive.drive_manager.simulator.engine

import kotlin.jvm.JvmRecord

/**
 * A special summon's Requerimiento, programmed by hand in the card file (its `requirement` section), as the effects are. It is the only
 * source, in the four ways (Evolución, Ascenso, Materialización: [SpecialRequirements.specialRequirement]; Ritual: [Rituals.requirement]):
 * the printed text is only shown, never read. A card with a special summon method and no section cannot be special summoned and counts as
 * not programmed ([CardEffects.pending]). Vault: `11 Simulador/Requerimientos de invocación especial`.
 *
 * Evolución, Ascenso and Materialización use [summoner], [materials] and the rest; a Ritual uses [ritual].
 *
 * @param summoner        who summons ("criatura [dragón] nivel 2"); null: any creature of the field
 * @param materials       Materialización: the other materials ("+ material nivel 2 o menor en mano o tributos"); null: none
 * @param alliesSum       Ascenso: "mientras tus aliados suman N o más en nivel" (or in cost with [alliesSumByCost]); 0 without it
 * @param alliesSumByCost the sum counts the allies' costs
 * @param alone           "mientras no controlas otras criaturas"
 * @param aloneAtLevel    "mientras no controlas otros aliados nivel N": N (0 without it)
 * @param strongest       "con fuerza igual o mayor que la de cualquier otro aliado"
 * @param formationSize   Enlace: "en una formación de [al menos] N criaturas": N (0 without it)
 * @param formationAtLeast "al menos" N
 * @param condition       any other check, as a condition of the card language tested on the board as it is ("mientras controlas 2 o más
 *                        [ninjas]", "alguna criatura nivel 1", "en tu línea frontal"): `Compare` of `OnField` amounts and the like. The
 *                        summoner, still on the field, counts. Null: none. (The vault's proposal called it `while`.)
 * @param conditionText   what [condition] asks, for the message when it is not met ("controlar 2 o más [ninjas]"); null: the printed text
 * @param alternative     "(A) o (B)": the other way, or null
 * @param ritual          Ritual: what is sacrificed; null for the other ways
 */
@JvmRecord
data class RequirementSpec(
    val summoner: SummonerSpec?, val materials: MaterialSpec?, val alliesSum: Int, val alliesSumByCost: Boolean, val alone: Boolean,
    val aloneAtLevel: Int, val strongest: Boolean, val formationSize: Int, val formationAtLeast: Boolean, val condition: Condition?,
    val conditionText: String?, val alternative: RequirementSpec?, val ritual: RitualSpec?,
)

/**
 * The summoner of an Evolución, Ascenso or Materialización.
 *
 * @param level    its level ("nivel 2"); 0: any level
 * @param maxLevel the highest level ("nivel 2 o 3"); null: [level] only
 * @param atLeast  "nivel N o mayor"
 * @param orLess   "nivel N o menor"
 * @param filter   anything else it must be ("[dragón] o [fauna]", "criatura [artefacto]"); null: any creature
 * @param zones    declared from these zones, not the field ("criatura nivel 3 en mano + …"): it becomes the first material; null: the field
 * @param none     no summoner: every creature used is a material and the card enters the Retaguardia ("2 criaturas nivel 1 en campo")
 */
@JvmRecord
data class SummonerSpec(
    val level: Int, val maxLevel: Int?, val atLeast: Boolean, val orLess: Boolean, val filter: CardFilter?, val zones: List<String>?,
    val none: Boolean,
)

/**
 * The other materials of a Materialización.
 *
 * @param count    how many ("+ material": 1; "+ 2 materiales": 2)
 * @param more     "N o más"
 * @param level    their level; 0: any level
 * @param orLess   "nivel N o menor"
 * @param levelSum "aliados que sumen N en nivel" (with the summoner); 0 without it
 * @param zones    where they come from ("field", "hand", "tributeZone", "discardPile"); null: the Tributos or the field
 * @param filter   anything else they must be ("[artefacto]", a utility with `cardType`); null: creatures
 */
@JvmRecord
data class MaterialSpec(
    val count: Int, val more: Boolean, val level: Int, val orLess: Boolean, val levelSum: Int, val zones: List<String>?, val filter: CardFilter?,
)

/**
 * What a Ritual sacrifices.
 *
 * @param sum         the levels of the sacrificed creatures add up to [level] ("criaturas que sumen 4 en nivel"); false: one creature of [level]
 * @param level       the level (or the sum)
 * @param maxLevel    the highest level, or the highest sum ("nivel 2 o 3", "sumen entre 3 y 4"); null: [level] only
 * @param atLeast     "nivel N o mayor"
 * @param maxStrength "de fuerza N o menos"; null: any
 * @param extra       "+ N o más aliados": other allies sacrificed with it, at least
 * @param eachMaxLevel "criaturas nivel N o menor que sumen …": the highest level of each creature used; null: any
 * @param parts       each creature used, the summoner among them ("criatura nivel 2 + 1 criatura [ángel] nivel 2 o menor"): one
 *                    [RitualParts.Part] each (level, "o menor", class); null or empty: the fields above
 */
@JvmRecord
data class RitualSpec(
    val sum: Boolean, val level: Int, val maxLevel: Int?, val atLeast: Boolean, val maxStrength: Int?, val extra: Int, val eachMaxLevel: Int? = null,
    val parts: List<RitualParts.Part>? = null,
)
