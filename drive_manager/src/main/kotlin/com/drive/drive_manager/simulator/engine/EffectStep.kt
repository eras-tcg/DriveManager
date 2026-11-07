package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.engine.effects.model.Action
import com.drive.drive_manager.simulator.room.GameRoom

/**
 * One instruction of an effect's resolution, run in order. Steps that need a player's choice pause the
 * engine with a prompt. "Luego:" is plain order (rule 11.1.3.2): the next step runs whether or not this one
 * did anything.
 */
sealed interface EffectStep {


    /** A basic action of the new effect language, run by its handler. Only until every old step is migrated (vault: Bitácora del refactor). */
    @JvmRecord
    data class Do(val action: Action?) : EffectStep

    /**
     * A step that acts on something that must be there: a target, a card to summon, play or take, an ally to choose.
     * An optional effect is only offered when such a step has something to act on (rule 11.2.0.2: an effect with nothing
     * to choose cannot be activated); the others (draw, gain gems…) never stop it.
     */
    sealed interface NeedsTarget : EffectStep

    /**
     * A filter whose level depends on the game ("de nivel igual o menor a la cantidad de copias de esta carta en tus
     * tributos"): `base` with its level, its maximum level, or both, read when it is used.
     */
    @JvmRecord
    data class FilterBy(val base: CardFilter?, val level: Amount?, val maxLevel: Amount?) {
        fun of(room: GameRoom, controller: String?): CardFilter? {
            var filter = base
            if (level != null) filter = filter!!.withLevel(level.of(room, controller))
            if (maxLevel != null) filter = filter!!.withMaxLevel(maxLevel.of(room, controller))
            return filter
        }
    }

    /** "súbelo a la mano": the chosen targets go back to their owner's hand; "Si lo haces" when any did. */
    data object BounceChosen : EffectStep

    /**
     * "este no se reactiva al inicio del turno oponente" (only that untap, Driada) / "no se puede reactivar durante el próximo turno
     * oponente" (`wholeTurn`: no effect can reactivate it either, Uzume).
     */
    @JvmRecord
    data class NoUntapChosen(val wholeTurn: Boolean) : EffectStep

    /** "elige el enemigo [activo / agotado] de menor fuerza" (a tie: the controller picks among them). */
    @JvmRecord
    data class ChooseWeakestEnemy(val filter: CardFilter?) : NeedsTarget

    // "sin pagar su coste".

    /** "Si tributaste 1 carta [filtro]:": the next `covers` steps only then. */
    @JvmRecord
    data class IfTributed(val filter: CardFilter?, val covers: Int) : EffectStep

    /**
     * "puedes hacer A para hacer B": both are done on resolution, and only when both can be (rule of "do A to do
     * B"): the effect is offered only then, and on resolution it does nothing if any of the following steps
     * can no longer be done.
     */
    data object IfPossible : EffectStep

    /**
     * "invoca 1 criatura [filtro] del mismo nivel y distinto nombre de tus [zona] en tu línea frontal sin pagar su
     * coste": like the ally chosen with PickAlly. The creature summoned is the effect's summonedId.
     */
    @JvmRecord
    data class SummonLikeSubject(val zone: String?, val filter: CardFilter?) : EffectStep

    /** "añádele el aliado elegido con todos sus materiales como materiales del aliado invocado". */
    data object AttachSubjectToSummoned : EffectStep

    /** "añadele 1 criatura [filtro] de menor nivel de tus tributos como material" (to the ally of the triggering event). */
    @JvmRecord
    data class AttachLowerFromTributes(val filter: CardFilter?) : EffectStep

    /** "coloca esta carta agotada en tu retaguardia": the utility stays on the field. */
    @JvmRecord
    data class KeepOnField(val exhausted: Boolean) : EffectStep

    /**
     * "elige 1 aliado; mira las primeras N cartas: de ellas toma hasta `max` carta que comparta tipo con el
     * aliado elegido. Envía el resto al fondo de tu mazo en orden aleatorio" (after [LookTop]).
     */
    @JvmRecord
    data class TakeFromLookedLikeSubject(val max: Int) : EffectStep

    /**
     * "la próxima vez que un aliado inflinja daño directo por combate este turno, inflinge daño 1 vez más. Si lo hace:
     * termina tu turno."
     */
    data object ExtraCombatHitThenEndTurn : EffectStep

    /** "el oponente elige 1 criatura en su retaguardia para moverla a su línea frontal". */
    data object OpponentMovesOneToFront : EffectStep

    /**
     * "ambos jugadores envían las cartas de su mano al fondo de su mazo en orden aleatorio. Luego: ambos roban cartas
     * igual a la cantidad de vidas que tiene su oponente."
     */
    data object HandsToBottomDrawOpponentLives : EffectStep

    /**
     * "hasta N veces: puedes elegir 1 enemigo; reduce en X su fuerza [hasta el final del ataque]" (the same enemy can be
     * chosen again). `times` below 0: as many times as the last cost paid (`lastCount`). `until`:
     * "attack" or "turn".
     */
    @JvmRecord
    data class ReduceRepeatedly(val times: Int, val amount: Int, val until: String?) : EffectStep

    /** "tantas veces como quieras: descarta N tributos [filtro]; esta carta gana [habilidad] 1 hasta el final del ataque" (it adds up). */
    @JvmRecord
    data class TributesToDeckBottomForKeyword(val count: Int, val filter: CardFilter?, val keyword: String?) : EffectStep

    /** "tributa N o más aliados [filtro] que sumen M o más en nivel" (a cost). */
    @JvmRecord
    data class TributeAlliesLevelSum(val minCount: Int, val minSum: Int, val filter: CardFilter?) : EffectStep

    /** "coloca 1 criatura [filtro] de tu mano en tu retaguardia. Al final del turno súbelo a tu mano" (placed, not summoned). */
    @JvmRecord
    data class PlaceFromHandUntilTurnEnd(val filter: CardFilter?) : NeedsTarget

    /** "tributa 1 material [filtro] de un aliado [filtro]": that ally is the subject, the material the last tributed. */
    @JvmRecord
    data class TributeAllyMaterialWhere(val ally: CardFilter?, val material: CardFilter?) : NeedsTarget

    /** "para que [dicho aliado] active 1 efecto En Invocación del material tributado": a new link with that effect, for the ally. */
    data object ActivateTributedSummonEffect : EffectStep

    /** "invoca criaturas … que en nivel sumen hasta N de tu mano sin pagar sus costes" (up to that level sum, all at once). */
    @JvmRecord
    data class SummonFreeLevelSum(val zone: String?, val filter: CardFilter?, val maxLevelSum: Int) : NeedsTarget

    /** "destruye 1 carta bocarriba en las vidas oponentes". */
    data object DestroyFaceUpLife : NeedsTarget

    /** "Si lo haces:": the next `covers` steps only if the last optional step was done. */
    @JvmRecord
    data class IfDone(val covers: Int) : EffectStep

    /** "Si ningún … fue …:": the next `covers` steps only if the last step did nothing. */
    @JvmRecord
    data class IfNotDone(val covers: Int) : EffectStep

    /** "tributa 1 material de 1 aliado". */
    data object TributeAllyMaterial : NeedsTarget

    /**
     * "sacrifica 1 aliado": the controller picks one of their creatures on the field. With `toUntapAnother`
     * ("sacrifica 1 aliado para reactivar otro") both must be possible: the one sacrificed cannot be the only
     * ally left to reactivate. With `other` ("sacrifica 1 otro aliado") it cannot be this card.
     */
    @JvmRecord
    data class SacrificeAlly(val toUntapAnother: Boolean, val other: Boolean) : NeedsTarget {
        constructor() : this(false, false)
        constructor(toUntapAnother: Boolean) : this(toUntapAnother, false)
    }

    /** "dicho aliado": the card of the event that triggered the effect is the one the next steps act on. */
    data object SubjectFromTrigger : EffectStep

    /**
     * "puedes A; B": asks the controller. If they decline, or A cannot be done, the next `covers`
     * steps (A and B) are skipped.
     */
    @JvmRecord
    data class MayDo(val question: String?, val covers: Int) : EffectStep

    /** "Si era un aliado activo:": the next `covers` steps only if something is saved under that name. */
    @JvmRecord
    data class IfSaved(val name: String?, val covers: Int) : EffectStep

    /** "Si [condición sobre la mesa]:": the next `covers` steps only if it holds now. */
    @JvmRecord
    data class IfCondition(val condition: Condition?, val covers: Int) : EffectStep

    /**
     * "todos [tus otros] aliados [filtro] ganan [habilidad] hasta el final del turno [oponente]" (`until`: "turn" or
     * "nextTurn"; `others`: not this card).
     */
    @JvmRecord
    data class AlliesGain(val filter: CardFilter?, val keyword: String?, val until: String?, val others: Boolean) : EffectStep {
        constructor(filter: CardFilter?, keyword: String?, until: String?) : this(filter, keyword, until, false)
    }

    /** "termina tu turno": once nothing is pending, the turn goes on to its end phase. */
    data object EndYourTurn : EffectStep

    /** "activa 1 de sus efectos En Invocación": the subject activates its own "En Invocación" as a new link. */
    data object ActivateSubjectSummonEffect : EffectStep

    /** "esta carta …" again after a step that chose another card (a sacrifice, an ally): the next steps act on this card. */
    data object SubjectIsThisCard : EffectStep

    /** "salta el paso de bloqueo" (of the attack going on). */
    data object SkipBlockStep : EffectStep

    /** "Si reduces su fuerza a 0 / si fue destruido:": the next `covers` steps only if no chosen target is still on the field. */
    @JvmRecord
    data class IfChosenGone(val covers: Int) : EffectStep

    /** "para que otro [aliado] a lo más N nivel mayor declare ataque": an ally of the Línea frontal of level up to the sacrificed one's + N. */
    @JvmRecord
    data class PickAllyUpToSacrificedLevel(val plus: Int) : NeedsTarget

    /** "1 aliado como material de otro de nivel mayor": the ally to add, one with an ally of higher level. */
    data object PickAllyWithHigherAlly : NeedsTarget

    /** "… como material de otro de nivel mayor": the subject becomes a material of an ally of higher level. */
    data object AttachSubjectToHigherAlly : EffectStep

    /** "algún aliado [con N materiales]": an ally with at least `count` materials becomes the subject. */
    @JvmRecord
    data class PickAllyWithMaterials(val count: Int) : NeedsTarget

    /** "dicho aliado inflige daño 1 vez más": one more hit of the direct damage going on. */
    data object ExtraHit : EffectStep

    // ------------------------------------------------------------------ E1.2, E1.3 and E2 (2026-10-31)

    /** "de ellas toma hasta N cartas [filtro] con distinto nivel. Envía el resto al fondo de tu mazo" (after [LookTop]). */
    @JvmRecord
    data class TakeFromLookedDistinctLevels(val max: Int, val filter: CardFilter?) : EffectStep

    /** "revela 1 [filtro] y 1 [filtro] de tu mano": one different card for each filter (a cost); the cards stay in the hand. */
    @JvmRecord
    data class RevealFromHand(val filters: List<CardFilter>?) : NeedsTarget

    /** "coloca bocabajo una de tus vidas bocarriba" (`all`: every one of them, "Luego: baraja tus vidas" with `shuffle`). */
    @JvmRecord
    data class LivesFaceDown(val all: Boolean, val shuffle: Boolean) : EffectStep

    /** "ambos jugadores descartan N cartas de su mano [bocabajo]": each player chooses theirs. */
    @JvmRecord
    data class EachPlayerDiscards(val count: Int) : EffectStep

    /** "tu oponente gana tantas gemas como su contador de gemas diga". */
    data object OpponentGainsGemsAsCounter : EffectStep

    /** "tributa hasta N recursos [filtro] de un aliado": the ally is the subject, `lastCount` how many. */
    @JvmRecord
    data class TributeAllyResources(val max: Int, val filter: CardFilter?) : NeedsTarget

    /** "añade 1 criatura [filtro] de tus [zona] como recurso de un aliado de nivel mayor". */
    @JvmRecord
    data class AddResourceToHigherAlly(val zone: String?, val filter: CardFilter?) : NeedsTarget

    /** "elige al azar N cartas de la mano del oponente para que las revele; envía 1 de ellas al fondo de su mazo". */
    @JvmRecord
    data class RevealOpponentHandSendOneToBottom(val count: Int) : EffectStep

    /** "elige enemigos que sumen N en nivel" (a cost). */
    @JvmRecord
    data class ChooseEnemiesLevelSum(val sum: Int) : NeedsTarget

    /** "Por cada aliado sacrificado de esta forma: gana 1 gema". */
    data object GainGemsPerOwnSacrificed : EffectStep

    /** "sube a tu mano 1 aliado del mismo nivel [que la carta colocada / invocada]": `lastTributed` is that ally. */
    data object ReturnAllyOfSummonedLevel : EffectStep

    /** "destruye el tope de las vidas oponentes". */
    data object DestroyTopLife : EffectStep

    /** "añade esta carta (de tu descarte / tributos) como recurso de un aliado". */
    data object AttachSelfAsResourceToAlly : NeedsTarget

    /** "elige 1 criatura nivel N o menor de los tributos oponentes" (a cost; the card chosen is the subject). */
    @JvmRecord
    data class ChooseOpponentTribute(val filter: CardFilter?) : NeedsTarget

    /** "coloca la criatura elegida y esta carta en la línea frontal de sus dueños". */
    data object PlaceChosenTributeAndSelf : EffectStep

    /**
     * Agrupar N: "si esta carta declara ataque: puedes agotar N aliados para que esta carta, durante el ataque, gane su fuerza e inflija daño
     * 1 vez extra en caso de hacerlo" (Letalidad 1 more until the end of the attack).
     */
    @JvmRecord
    data class GroupUp(val allies: Int) : EffectStep

    /** "de ellas toma hasta 1 [filtro] y hasta 1 [filtro]. Envía el resto al fondo de tu mazo" (each card for a different filter). */
    @JvmRecord
    data class TakeFromLookedEachRestToBottom(val filters: List<CardFilter>?) : EffectStep

    /** "puedes colocar 1 [filtro] de los materiales de un aliado agotado en tu retaguardia" (as the card type it is). */
    @JvmRecord
    data class MaterialOfExhaustedAllyToBack(val filter: CardFilter?) : NeedsTarget

    /**
     * "reduce … las de las vidas oponentes en N, hasta el final del turno" (Helena): the opponent's face-up lives count that much less
     * strength when they are checked this turn (CardEffects.lifeStrength).
     */
    @JvmRecord
    data class ReduceOpponentLivesBy(val amount: Amount?) : EffectStep

    /**
     * "dicho aliado puede invocar esta carta por Materialización ignorando el requerimiento y sin pagar el coste especial": the ally of
     * the triggering event (the one that destroyed) is the summoner.
     */
    data object MaterializeSelfOnTriggerAlly : EffectStep

    /** "al final del ataque súbelo a tu mano con todos sus materiales": the subject, when the attack going on ends. */
    data object ReturnSubjectAtAttackEnd : EffectStep

    /** "esta carta [agotada] declara bloqueo" (it blocks the attack going on, even exhausted). */
    data object DeclareBlockSelf : EffectStep

    /** "ambos jugadores eligen 1 de sus aliados de su línea frontal. Si lo hacen: sacrifican todas las otras criaturas en su línea frontal". */
    data object EachPlayerKeepsOneInFrontLine : EffectStep

    /** "ambos jugadores reducen en N su Marcador" (0 to 5; it does not go below 0). */
    @JvmRecord
    data class EachPlayerLowersMarker(val count: Int) : EffectStep

    /**
     * "una criatura en tu mano o tributos puede invocar especialmente esta carta": the summoner is declared from those zones (it becomes the
     * first material), then the materials its Requerimiento asks; a regular special summon (cost and Requerimiento).
     */
    @JvmRecord
    data class SpecialSelfDeclaredSummoner(val zones: String?) : NeedsTarget
}
