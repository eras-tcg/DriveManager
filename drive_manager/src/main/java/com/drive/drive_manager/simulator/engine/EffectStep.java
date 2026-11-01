package com.drive.drive_manager.simulator.engine;

/**
 * One instruction of an effect's resolution, run in order. Steps that need a player's choice pause the
 * engine with a prompt. "Luego:" is plain order (rule 11.1.3.2): the next step runs whether or not this one
 * did anything.
 */
public sealed interface EffectStep {

    /** A basic action of the new effect language, run by its handler. Only until every old step is migrated (vault: Bitácora del refactor). */
    record Do(com.drive.drive_manager.simulator.engine.effects.model.Action action) implements EffectStep {}

    /**
     * A step that acts on something that must be there: a target, a card to summon, play or take, an ally to choose.
     * An optional effect is only offered when such a step has something to act on (rule 11.2.0.2: an effect with nothing
     * to choose cannot be activated); the others (draw, gain gems…) never stop it.
     */
    sealed interface NeedsTarget extends EffectStep {}

    /**
     * A filter whose level depends on the game ("de nivel igual o menor a la cantidad de copias de esta carta en tus
     * tributos"): {@code base} with its level, its maximum level, or both, read when it is used.
     */
    record FilterBy(CardFilter base, Amount level, Amount maxLevel) {
        CardFilter of(com.drive.drive_manager.simulator.GameRoom room, String controller) {
            CardFilter filter = base;
            if (level != null) filter = filter.withLevel(level.of(room, controller));
            if (maxLevel != null) filter = filter.withMaxLevel(maxLevel.of(room, controller));
            return filter;
        }
    }

    /** "súbelo a la mano": the chosen targets go back to their owner's hand; "Si lo haces" when any did. */
    record BounceChosen() implements EffectStep {}

    /**
     * "este no se reactiva al inicio del turno oponente" (only that untap, Driada) / "no se puede reactivar durante el próximo turno
     * oponente" ({@code wholeTurn}: no effect can reactivate it either, Uzume).
     */
    record NoUntapChosen(boolean wholeTurn) implements EffectStep {}

    /** "elige el enemigo [activo / agotado] de menor fuerza" (a tie: the controller picks among them). */
    record ChooseWeakestEnemy(CardFilter filter) implements NeedsTarget {}

        /** "sin pagar su coste". */

    /**
     * "[otro aliado / 1 aliado] puede invocar por [Ascenso / Evolución] 1 criatura de tu [zona] reduciendo el coste
     * especial en N": the controller may pick the creature (or none), then the summoner.
     */
    record SpecialFromZone(String kind, String zone, int reduce, String summoner, CardFilter filter) implements NeedsTarget {
        /** "otro aliado" (not this card) or "1 aliado" (any). */
        public SpecialFromZone(String kind, String zone, int reduce, boolean otherAlly) {
            this(kind, zone, reduce, otherAlly ? "other" : "any", CardFilter.any());
        }
    }

    /** "Si tributaste 1 carta [filtro]:": the next {@code covers} steps only then. */
    record IfTributed(CardFilter filter, int covers) implements EffectStep {}

    /**
     * "puedes hacer A para hacer B": both are done on resolution, and only when both can be (rule of "do A to do
     * B"): the effect is offered only then, and on resolution it does nothing if any of the following steps
     * can no longer be done.
     */
    record IfPossible() implements EffectStep {}

    /**
     * "invoca 1 criatura [filtro] del mismo nivel y distinto nombre de tus [zona] en tu línea frontal sin pagar su
     * coste": like the ally chosen with PickAlly. The creature summoned is the effect's summonedId.
     */
    record SummonLikeSubject(String zone, CardFilter filter) implements EffectStep {}

    /** "añádele el aliado elegido con todos sus materiales como materiales del aliado invocado". */
    record AttachSubjectToSummoned() implements EffectStep {}

    /** "añadele 1 criatura [filtro] de menor nivel de tus tributos como material" (to the ally of the triggering event). */
    record AttachLowerFromTributes(CardFilter filter) implements EffectStep {}

    /** "coloca esta carta agotada en tu retaguardia": the utility stays on the field. */
    record KeepOnField(boolean exhausted) implements EffectStep {}

    /**
     * "elige 1 aliado; mira las primeras N cartas: de ellas toma hasta {@code max} carta que comparta tipo con el
     * aliado elegido. Envía el resto al fondo de tu mazo en orden aleatorio" (after {@link LookTop}).
     */
    record TakeFromLookedLikeSubject(int max) implements EffectStep {}

    /**
     * "la próxima vez que un aliado inflinja daño directo por combate este turno, inflinge daño 1 vez más. Si lo hace:
     * termina tu turno."
     */
    record ExtraCombatHitThenEndTurn() implements EffectStep {}

    /** "el oponente elige 1 criatura en su retaguardia para moverla a su línea frontal". */
    record OpponentMovesOneToFront() implements EffectStep {}

    /**
     * "ambos jugadores envían las cartas de su mano al fondo de su mazo en orden aleatorio. Luego: ambos roban cartas
     * igual a la cantidad de vidas que tiene su oponente."
     */
    record HandsToBottomDrawOpponentLives() implements EffectStep {}

    /**
     * "hasta N veces: puedes elegir 1 enemigo; reduce en X su fuerza [hasta el final del ataque]" (the same enemy can be
     * chosen again). {@code times} below 0: as many times as the last cost paid ({@code lastCount}). {@code until}:
     * "attack" or "turn".
     */
    record ReduceRepeatedly(int times, int amount, String until) implements EffectStep {}

    /** "tantas veces como quieras: descarta N tributos [filtro]; esta carta gana [habilidad] 1 hasta el final del ataque" (it adds up). */
    record TributesToDeckBottomForKeyword(int count, CardFilter filter, String keyword) implements EffectStep {}

    /** "tributa N o más aliados [filtro] que sumen M o más en nivel" (a cost). */
    record TributeAlliesLevelSum(int minCount, int minSum, CardFilter filter) implements EffectStep {}

    /** "coloca 1 criatura [filtro] de tu mano en tu retaguardia. Al final del turno súbelo a tu mano" (placed, not summoned). */
    record PlaceFromHandUntilTurnEnd(CardFilter filter) implements NeedsTarget {}

    /** "tributa 1 material [filtro] de un aliado [filtro]": that ally is the subject, the material the last tributed. */
    record TributeAllyMaterialWhere(CardFilter ally, CardFilter material) implements NeedsTarget {}

    /** "para que [dicho aliado] active 1 efecto En Invocación del material tributado": a new link with that effect, for the ally. */
    record ActivateTributedSummonEffect() implements EffectStep {}

    /** "invoca criaturas … que en nivel sumen hasta N de tu mano sin pagar sus costes" (up to that level sum, all at once). */
    record SummonFreeLevelSum(String zone, CardFilter filter, int maxLevelSum) implements NeedsTarget {}

    /** "destruye 1 carta bocarriba en las vidas oponentes". */
    record DestroyFaceUpLife() implements NeedsTarget {}

    /** "Si lo haces:": the next {@code covers} steps only if the last optional step was done. */
    record IfDone(int covers) implements EffectStep {}

    /** "Si ningún … fue …:": the next {@code covers} steps only if the last step did nothing. */
    record IfNotDone(int covers) implements EffectStep {}

    /**
     * "declara ataque": this card, or the ally chosen by {@link PickAlly}, attacks without being exhausted
     * (rule 11.9.3.1). Its controller picks an exhausted enemy creature, or none to attack the opponent.
     */
    record DeclareAttack(boolean opponentOnly) implements EffectStep {
        public DeclareAttack() { this(false); }
    }

    /**
     * "elige 1 aliado": the ally the next steps act on. With {@code attacker} it is going to declare an attack, so
     * only allies of the Línea frontal can be chosen.
     */
    record PickAlly(boolean attacker) implements NeedsTarget {
        public PickAlly() { this(false); }
    }

    /** "tributa 1 material de 1 aliado". */
    record TributeAllyMaterial() implements NeedsTarget {}

    /**
     * "sacrifica 1 aliado": the controller picks one of their creatures on the field. With {@code toUntapAnother}
     * ("sacrifica 1 aliado para reactivar otro") both must be possible: the one sacrificed cannot be the only
     * ally left to reactivate. With {@code other} ("sacrifica 1 otro aliado") it cannot be this card.
     */
    record SacrificeAlly(boolean toUntapAnother, boolean other) implements NeedsTarget {
        public SacrificeAlly() { this(false, false); }
        public SacrificeAlly(boolean toUntapAnother) { this(toUntapAnother, false); }
    }

    /** "elige 1 aliado [filtro]": like {@link PickAlly}, among those that match. */
    record PickAllyWhere(CardFilter filter) implements NeedsTarget {}

    /** "dicho aliado": the card of the event that triggered the effect is the one the next steps act on. */
    record SubjectFromTrigger() implements EffectStep {}

    /**
     * "[esta carta / un aliado] invoca por Ritual 1 criatura de tus [zona] reduciendo su coste especial en N"
     * (rule 9.3): the controller picks the creature, then the summoner and the other creatures that meet its
     * Requerimiento; they are sacrificed and it is summoned in the summoner's place.
     */
    record RitualFromZone(String zone, boolean selfSummoner, int reduce, CardFilter filter) implements NeedsTarget {
        public RitualFromZone(String zone, boolean selfSummoner, int reduce) { this(zone, selfSummoner, reduce, CardFilter.any()); }
    }

    /**
     * "puedes A; B": asks the controller. If they decline, or A cannot be done, the next {@code covers}
     * steps (A and B) are skipped.
     */
    record MayDo(String question, int covers) implements EffectStep {}

    /** "envíalas al tope o al fondo de tu mazo" / "devuélvelas al tope o envíalas al fondo": the looked cards, all together. */
    record LookedToTopOrBottom() implements EffectStep {}

    /** "Si [condición sobre la mesa]:": the next {@code covers} steps only if it holds now. */
    record IfCondition(Condition condition, int covers) implements EffectStep {}

    /**
     * "todos [tus otros] aliados [filtro] ganan [habilidad] hasta el final del turno [oponente]" ({@code until}: "turn" or
     * "nextTurn"; {@code others}: not this card).
     */
    record AlliesGain(CardFilter filter, String keyword, String until, boolean others) implements EffectStep {
        public AlliesGain(CardFilter filter, String keyword, String until) { this(filter, keyword, until, false); }
    }

    /** "termina tu turno": once nothing is pending, the turn goes on to its end phase. */
    record EndYourTurn() implements EffectStep {}

    /** "activa 1 de sus efectos En Invocación": the subject activates its own "En Invocación" as a new link. */
    record ActivateSubjectSummonEffect() implements EffectStep {}

    /** "esta carta …" again after a step that chose another card (a sacrifice, an ally): the next steps act on this card. */
    record SubjectIsThisCard() implements EffectStep {}

    /** "salta el paso de bloqueo" (of the attack going on). */
    record SkipBlockStep() implements EffectStep {}

    /** "Si reduces su fuerza a 0 / si fue destruido:": the next {@code covers} steps only if no chosen target is still on the field. */
    record IfChosenGone(int covers) implements EffectStep {}

    /** "para que otro [aliado] a lo más N nivel mayor declare ataque": an ally of the Línea frontal of level up to the sacrificed one's + N. */
    record PickAllyUpToSacrificedLevel(int plus) implements NeedsTarget {}

    /** "1 aliado como material de otro de nivel mayor": the ally to add, one with an ally of higher level. */
    record PickAllyWithHigherAlly() implements NeedsTarget {}

    /** "… como material de otro de nivel mayor": the subject becomes a material of an ally of higher level. */
    record AttachSubjectToHigherAlly() implements EffectStep {}

    /** "algún aliado [con N materiales]": an ally with at least {@code count} materials becomes the subject. */
    record PickAllyWithMaterials(int count) implements NeedsTarget {}

    /** "dicho aliado inflige daño 1 vez más": one more hit of the direct damage going on. */
    record ExtraHit() implements EffectStep {}

    // ------------------------------------------------------------------ E1.2, E1.3 and E2 (2026-10-31)

    /**
     * "[el aliado elegido] puede invocar por [método] 1 carta [filtro] en tu [zona] reduciendo el coste especial en X donde X es el nivel
     * del aliado elegido".
     */
    record SpecialBySubjectLevel(String kind, String zone, CardFilter filter) implements NeedsTarget {}

    /**
     * "[un aliado / esta carta] invoca especialmente 1 criatura [filtro] de tu [zona] reduciendo el coste especial en 1 por cada …
     * tributado" ({@code lastCount}); {@code kind} "any" for "invoca especialmente".
     */
    record SpecialByCount(String kind, String zone, String summoner, CardFilter filter) implements EffectStep {}

    /** "un aliado invoca esta carta de forma especial [reduciendo su coste especial en N]" from the zone it is in. */
    record SpecialSelf(String zone, int reduce) implements NeedsTarget {}

    /**
     * "1 aliado puede invocar por Ritual 1 criatura [filtro] de tus [zona] reduciendo el coste especial en X", X read when it is used
     * ("1 por cada 10 tributos Violeta").
     */
    record RitualFromZoneBy(String zone, boolean selfSummoner, Amount reduce, CardFilter filter) implements EffectStep {}

    /** "de ellas toma hasta N cartas [filtro] con distinto nivel. Envía el resto al fondo de tu mazo" (after {@link LookTop}). */
    record TakeFromLookedDistinctLevels(int max, CardFilter filter) implements EffectStep {}

    /** "revela 1 [filtro] y 1 [filtro] de tu mano": one different card for each filter (a cost); the cards stay in the hand. */
    record RevealFromHand(java.util.List<CardFilter> filters) implements NeedsTarget {}

    /** "elige 1 aliado [filtro]; este declara ataque": an ally of the Línea frontal that matches (the subject). */
    record PickAttacker(CardFilter filter) implements NeedsTarget {}

    /**
     * "al final del turno: …": what follows is another effect of the card ({@code scriptId}, moment {@code DELAYED}) that triggers at the
     * end of this turn, for the same subject (the creature this effect summoned, or the chosen ally).
     */
    record AtEndOfTurn(String scriptId) implements EffectStep {}

    /** "coloca bocabajo una de tus vidas bocarriba" ({@code all}: every one of them, "Luego: baraja tus vidas" with {@code shuffle}). */
    record LivesFaceDown(boolean all, boolean shuffle) implements EffectStep {}

    /** "ambos jugadores descartan N cartas de su mano [bocabajo]": each player chooses theirs. */
    record EachPlayerDiscards(int count) implements EffectStep {}

    /** "tu oponente gana tantas gemas como su contador de gemas diga". */
    record OpponentGainsGemsAsCounter() implements EffectStep {}

    /** "tributa hasta N recursos [filtro] de un aliado": the ally is the subject, {@code lastCount} how many. */
    record TributeAllyResources(int max, CardFilter filter) implements NeedsTarget {}

    /** "añade 1 criatura [filtro] de tus [zona] como recurso de un aliado de nivel mayor". */
    record AddResourceToHigherAlly(String zone, CardFilter filter) implements NeedsTarget {}

    /** "elige al azar N cartas de la mano del oponente para que las revele; envía 1 de ellas al fondo de su mazo". */
    record RevealOpponentHandSendOneToBottom(int count) implements EffectStep {}

    /** "elige enemigos que sumen N en nivel" (a cost). */
    record ChooseEnemiesLevelSum(int sum) implements NeedsTarget {}

    /** "Por cada aliado sacrificado de esta forma: gana 1 gema". */
    record GainGemsPerOwnSacrificed() implements EffectStep {}

    /** "sube a tu mano 1 aliado del mismo nivel [que la carta colocada / invocada]": {@code lastTributed} is that ally. */
    record ReturnAllyOfSummonedLevel() implements EffectStep {}

    /** "elige 1 criatura en el campo con fuerza menor a esta carta" (a cost); "Si era un aliado activo" reads it. */
    record ChooseWeakerThanSelf(boolean upTo) implements NeedsTarget {}

    /** "Si era un aliado activo:": the next {@code covers} steps only if the chosen creature was an active ally when chosen. */
    record IfChosenWasActiveAlly(int covers) implements EffectStep {}

    /** "destruye el tope de las vidas oponentes". */
    record DestroyTopLife() implements EffectStep {}

    /** "añade esta carta (de tu descarte / tributos) como recurso de un aliado". */
    record AttachSelfAsResourceToAlly() implements NeedsTarget {}

    /** "elige 1 criatura nivel N o menor de los tributos oponentes" (a cost; the card chosen is the subject). */
    record ChooseOpponentTribute(CardFilter filter) implements NeedsTarget {}

    /** "coloca la criatura elegida y esta carta en la línea frontal de sus dueños". */
    record PlaceChosenTributeAndSelf() implements EffectStep {}

    /**
     * Agrupar N: "si esta carta declara ataque: puedes agotar N aliados para que esta carta, durante el ataque, gane su fuerza e inflija daño
     * 1 vez extra en caso de hacerlo" (Letalidad 1 more until the end of the attack).
     */
    record GroupUp(int allies) implements EffectStep {}

    /** "de ellas toma hasta 1 [filtro] y hasta 1 [filtro]. Envía el resto al fondo de tu mazo" (each card for a different filter). */
    record TakeFromLookedEachRestToBottom(java.util.List<CardFilter> filters) implements EffectStep {}

    /** "puedes colocar 1 [filtro] de los materiales de un aliado agotado en tu retaguardia" (as the card type it is). */
    record MaterialOfExhaustedAllyToBack(CardFilter filter) implements NeedsTarget {}

    /**
     * "reduce … las de las vidas oponentes en N, hasta el final del turno" (Helena): the opponent's face-up lives count that much less
     * strength when they are checked this turn (CardEffects.lifeStrength).
     */
    record ReduceOpponentLivesBy(Amount amount) implements EffectStep {}

    /**
     * "dicho aliado puede invocar esta carta por Materialización ignorando el requerimiento y sin pagar el coste especial": the ally of
     * the triggering event (the one that destroyed) is the summoner.
     */
    record MaterializeSelfOnTriggerAlly() implements EffectStep {}

    /** "al final del ataque súbelo a tu mano con todos sus materiales": the subject, when the attack going on ends. */
    record ReturnSubjectAtAttackEnd() implements EffectStep {}

    /**
     * "invoca de forma especial 1 [filtro] de tu [zona] del mismo nivel, pero distinto nombre [que la carta devuelta] [sin pagar su coste
     * especial]".
     */
    record SpecialRelativeToLast(String zone, CardFilter filter, int reduce) implements EffectStep {}

    /** "puedes tributar esta carta" said by an inherited effect when its holder left the field: the Recurso, now in the Descarte. */
    record TributeNamedFromDiscard(String name) implements NeedsTarget {}

    /** "esta carta [agotada] declara bloqueo" (it blocks the attack going on, even exhausted). */
    record DeclareBlockSelf() implements EffectStep {}

    /** "ambos jugadores eligen 1 de sus aliados de su línea frontal. Si lo hacen: sacrifican todas las otras criaturas en su línea frontal". */
    record EachPlayerKeepsOneInFrontLine() implements EffectStep {}

    /** "ambos jugadores reducen en N su Marcador" (0 to 5; it does not go below 0). */
    record EachPlayerLowersMarker(int count) implements EffectStep {}

    /**
     * "una criatura en tu mano o tributos puede invocar especialmente esta carta": the summoner is declared from those zones (it becomes the
     * first material), then the materials its Requerimiento asks; a regular special summon (cost and Requerimiento).
     */
    record SpecialSelfDeclaredSummoner(String zones) implements NeedsTarget {}

}
