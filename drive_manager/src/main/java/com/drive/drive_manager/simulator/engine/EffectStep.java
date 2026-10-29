package com.drive.drive_manager.simulator.engine;

/**
 * One instruction of an effect's resolution, run in order. Steps that need a player's choice pause the
 * engine with a prompt. "Luego:" is plain order (rule 11.1.3.2): the next step runs whether or not this one
 * did anything.
 */
public sealed interface EffectStep {

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

    /** "mira las primeras N cartas de tu mazo" (fewer if the deck is shorter). */
    record LookTop(int count) implements EffectStep {}

    /**
     * "de ellas toma hasta N cartas [filtro]. Envía el resto al fondo de tu mazo": the controller picks up
     * to {@code max} looked cards that match the filter to add to the hand, and orders the rest, which go to
     * the bottom of the deck in that order.
     */
    record TakeFromLookedRestToBottom(int max, CardFilter filter) implements EffectStep {}

    /** "tributa N cartas de tu mano": the controller picks exactly N (all of them if the hand has fewer). */
    record TributeFromHand(int count) implements EffectStep {}

    /** "roba N cartas" (fewer if the deck is shorter). */
    record Draw(int count) implements EffectStep {}

    /**
     * "elige N enemigo [filtro]; destrúyelo": the controller picks exactly N enemies of the Línea frontal
     * (rule 4.0.6.2: the Retaguardia cannot be chosen by the opponent's effects). Destruction by effect.
     */
    record DestroyEnemy(int count, CardFilter filter) implements NeedsTarget {}

    /**
     * "elige N enemigo [filtro];" as a cost: the targets are chosen when the effect is activated (rule 11.10.1);
     * the effect can only be activated if there are enough of them. Only the opponent's Línea frontal.
     */
    record ChooseEnemy(int count, CardFilter filter, boolean upTo) implements NeedsTarget {
        public ChooseEnemy(int count, CardFilter filter) { this(count, filter, false); }
    }

    /** {@link ChooseEnemy} whose filter is worked out when it is used. */
    record ChooseEnemyBy(int count, FilterBy filter, boolean upTo) implements NeedsTarget {}

    /** "reduce a 0 su fuerza": the targets chosen on activation that are still valid. */
    record ReduceChosenToZero() implements EffectStep {}

    /** "agótalo": exhausts the chosen targets that are still valid. */
    record TapChosen() implements EffectStep {}

    /** "súbelo a la mano": the chosen targets go back to their owner's hand; "Si lo haces" when any did. */
    record BounceChosen() implements EffectStep {}

    /** "este no se reactiva al inicio del turno oponente / no se puede reactivar durante el próximo turno oponente". */
    record NoUntapChosen() implements EffectStep {}

    /** "elige el enemigo [activo / agotado] de menor fuerza" (a tie: the controller picks among them). */
    record ChooseWeakestEnemy(CardFilter filter) implements NeedsTarget {}

    /** "destrúyelo": destroys the targets chosen on activation that are still valid (rule 11.10.2). */
    record DestroyChosen() implements EffectStep {}

    /**
     * "invoca 1 criatura [filtro] de tus [zona] reduciendo su coste en N": summon by effect (rule 9.1.0.2),
     * paying the normal cost minus {@code reduce}; it enters the Retaguardia with the regulatory draw.
     */
    record SummonFromZone(String zone, CardFilter filter, int reduce, boolean toFront) implements NeedsTarget {
        /** "sin pagar su coste". */
        public static final int FREE = 1000;
        public SummonFromZone(String zone, CardFilter filter, int reduce) { this(zone, filter, reduce, false); }
    }

    /**
     * "invoca esta carta [en tu línea frontal] reduciendo su coste en N" from a zone: a summon by effect, with the
     * reduction worked out when it happens ({@link SummonFromZone#FREE}: without paying its cost).
     */
    record SummonSelf(String zone, Amount reduce, boolean toFront) implements NeedsTarget {}

    /** "invoca esta carta sin pagar su coste" from the hand: a summon by effect. */
    record SummonSelfFree() implements NeedsTarget {}

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

    /** "tributa [hasta] N materiales de esta carta" (a cost); lastCount is how many. */
    record TributeMaterials(int count, boolean upTo) implements EffectStep {}

    /** "toma 1 carta [filtro] de tus [zona] por cada material tributado" (lastCount). */
    record TakePerCount(String zone, CardFilter filter) implements EffectStep {}

    /** "tributa hasta N cartas de tu mano"; lastCount is how many. */
    record TributeFromHandUpTo(int count) implements EffectStep {}

    /** "roba la misma cantidad" (lastCount). */
    record DrawLastCount() implements EffectStep {}

    /** "Si tributaste 1 carta [filtro]:": the next {@code covers} steps only then. */
    record IfTributed(CardFilter filter, int covers) implements EffectStep {}

    /** "reduce su fuerza en N" on the chosen targets; {@code untilNextTurn}: until the end of the next turn. */
    record ReduceChosen(int amount, boolean untilNextTurn) implements EffectStep {}

    /** "elige todos los enemigos en la línea frontal" (no choice: they are the targets). */
    record TargetAllEnemies() implements EffectStep {}

    /** "reducirla a 0": the enemy whose strength was reduced in the triggering event. */
    record ReduceTriggerToZero() implements EffectStep {}

    /**
     * "puedes hacer A para hacer B": both are done on resolution, and only when both can be (rule of "do A to do
     * B"): the effect is offered only then, and on resolution it does nothing if any of the following steps
     * can no longer be done.
     */
    record IfPossible() implements EffectStep {}

    /** "reduce su fuerza en N más": the enemy of the triggering reduction, for as long as that reduction lasts. */
    record ReduceTriggerFurther(int amount) implements EffectStep {}

    /**
     * "invoca 1 criatura [filtro] del mismo nivel y distinto nombre de tus [zona] en tu línea frontal sin pagar su
     * coste": like the ally chosen with PickAlly. The creature summoned is the effect's summonedId.
     */
    record SummonLikeSubject(String zone, CardFilter filter) implements EffectStep {}

    /** "añádele el aliado elegido con todos sus materiales como materiales del aliado invocado". */
    record AttachSubjectToSummoned() implements EffectStep {}

    /** "coloca hasta 1 material de esta carta al tope de tus vidas bocarriba". */
    record MaterialToLife() implements NeedsTarget {}

    /**
     * "coloca hasta 1 [ente] nivel 4 de los materiales de esta carta en tu línea frontal. Luego: añádele todos los
     * materiales de esta carta como materiales." It is placed, not summoned.
     */
    record MaterialToFront(CardFilter filter, int level) implements EffectStep {}

    /** "coloca la carta del tope de tu mazo al tope de tus vidas bocabajo". */
    record DeckTopToLife() implements EffectStep {}

    /** "infringe N de daño directo" by effect (to the top life). */
    record DamageByEffect(int amount) implements EffectStep {}

    /** "puedes invocar esta carta [desde zona] reduciendo su coste en N". */
    record SummonSelfFromZone(String zone, int reduce) implements NeedsTarget {}

    /** "añadele 1 criatura [filtro] de menor nivel de tus tributos como material" (to the ally of the triggering event). */
    record AttachLowerFromTributes(CardFilter filter) implements EffectStep {}

    /** "añade esta carta como material a un aliado" (the ally chosen with PickAlly). */
    record AttachSelfToSubject() implements EffectStep {}

    /** "coloca esta carta agotada en tu retaguardia": the utility stays on the field. */
    record KeepOnField(boolean exhausted) implements EffectStep {}

    /**
     * "elige 1 aliado; mira las primeras N cartas: de ellas toma hasta {@code max} carta que comparta tipo con el
     * aliado elegido. Envía el resto al fondo de tu mazo en orden aleatorio" (after {@link LookTop}).
     */
    record TakeFromLookedLikeSubject(int max) implements EffectStep {}

    /** "de ellas descarta al menos N bocabajo. Coloca el resto al tope de tu mazo en cualquier orden" (after {@link LookTop}). */
    record DiscardLookedRestTop(int atLeast) implements EffectStep {}

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
    record DiscardTributesForKeyword(int count, CardFilter filter, String keyword) implements EffectStep {}

    /** "tributa N o más aliados [filtro] que sumen M o más en nivel" (a cost). */
    record TributeAlliesLevelSum(int minCount, int minSum, CardFilter filter) implements EffectStep {}

    /** "coloca 1 criatura [filtro] de tu mano en tu retaguardia. Al final del turno súbelo a tu mano" (placed, not summoned). */
    record PlaceFromHandUntilTurnEnd(CardFilter filter) implements NeedsTarget {}

    /** {@link SummonFromZone} whose filter is worked out when it is used. */
    record SummonFromZoneBy(String zone, FilterBy filter, int reduce) implements EffectStep {}

    /** "tributa 1 material [filtro] de un aliado [filtro]": that ally is the subject, the material the last tributed. */
    record TributeAllyMaterialWhere(CardFilter ally, CardFilter material) implements NeedsTarget {}

    /** "para que [dicho aliado] active 1 efecto En Invocación del material tributado": a new link with that effect, for the ally. */
    record ActivateTributedSummonEffect() implements EffectStep {}

    /** "tributa N cartas del tope de tu mazo". */
    record TributeTop(int count) implements EffectStep {}

    /** "envía esta carta (del campo) a tu descarte": a permanent paying with itself. */
    record SendSelfFromFieldTo(String zone) implements EffectStep {}

    /** "invoca criaturas … que en nivel sumen hasta N de tu mano sin pagar sus costes" (up to that level sum, all at once). */
    record SummonFreeLevelSum(String zone, CardFilter filter, int maxLevelSum) implements NeedsTarget {}

    /** "coloca [hasta] N cartas de tu [zona] al tope de tu mazo". */
    record ToDeckTop(String zone, int count, boolean upTo) implements EffectStep {}

    /** "puedes tributar "X" de los recursos de esta carta" (a cost). */
    record TributeResource(String name) implements EffectStep {}

    /** "destruye 1 carta bocarriba en las vidas oponentes". */
    record DestroyFaceUpLife() implements NeedsTarget {}

    /** "gana [habilidad] hasta el final del ataque". */
    record GainUntilEndOfAttack(String keyword) implements EffectStep {}

    /** "puedes jugar esta carta [desde zona] reduciendo su coste en el nivel de dicho aliado". */
    record PlaySelfFromZone(String zone, boolean reduceBySourceLevel) implements NeedsTarget {}

    /** "toma N carta [filtro] de tus [zona]": to the hand (up to N when {@code upTo}). */
    record TakeFromZone(String zone, CardFilter filter, int count, boolean upTo) implements NeedsTarget {}

    /** "Si lo haces:": the next {@code covers} steps only if the last optional step was done. */
    record IfDone(int covers) implements EffectStep {}

    /** "Si ningún … fue …:": the next {@code covers} steps only if the last step did nothing. */
    record IfNotDone(int covers) implements EffectStep {}

    /** "gana fuerza N hasta el final del turno": this card or the chosen ally. */
    record GainStrength(int amount) implements EffectStep {}

    /** "envía esta carta a tu [zona]" (Slicker pays its effect this way). Only from another zone. */
    record SendSelfTo(String zone) implements EffectStep {}

    /** "envía N cartas [filtro] de tus [zona] al fondo de tu mazo": exactly N, in the order chosen. */
    record SendFromZoneToDeckBottom(String zone, int count, CardFilter filter) implements EffectStep {}

    /** "reactiva esta carta". */
    record UntapSelf() implements EffectStep {}

    /** "gana [habilidad] hasta el final del turno": this card, or the ally chosen by {@link PickAlly}. */
    record GainUntilEndOfTurn(String keyword) implements EffectStep {}

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

    /** "gana N gemas": moves the Contador towards the controller. */
    record GainGems(int count) implements EffectStep {}

    /** "agota esta carta" (a cost: it must be active on the field). */
    record TapSelf() implements EffectStep {}

    /** "sacrifica esta carta" (a cost: it must be on the field). */
    record SacrificeSelf() implements EffectStep {}

    /** "sacrifica 1 aliado [filtro]". */
    record SacrificeAllyWhere(CardFilter filter) implements NeedsTarget {}

    /** "tributa 1 aliado [filtro]": one of your creatures on the field goes to your Tributos (not destroyed). */
    record TributeAlly(CardFilter filter) implements NeedsTarget {}

    /** "tributa 1 material de 1 aliado". */
    record TributeAllyMaterial() implements NeedsTarget {}

    /**
     * "sacrifica 1 aliado": the controller picks one of their creatures on the field. With {@code toUntapAnother}
     * ("sacrifica 1 aliado para reactivar otro") both must be possible: the one sacrificed cannot be the only
     * ally left to reactivate.
     */
    record SacrificeAlly(boolean toUntapAnother) implements NeedsTarget {
        public SacrificeAlly() { this(false); }
    }

    /** "reactiva 1 aliado" / "reactivar otro": the controller picks an exhausted ally that can be reactivated. */
    record UntapAlly(CardFilter filter) implements NeedsTarget {
        public UntapAlly() { this(CardFilter.any()); }
    }

    /** "elige 1 aliado [filtro]": like {@link PickAlly}, among those that match. */
    record PickAllyWhere(CardFilter filter) implements NeedsTarget {}

    /** "dicho aliado": the card of the event that triggered the effect is the one the next steps act on. */
    record SubjectFromTrigger() implements EffectStep {}

    /** "reactívalo": the ally chosen with {@link PickAlly}. */
    record UntapSubject() implements EffectStep {}

    /** "gana fuerza N hasta el final del ataque / del turno oponente" ({@code until}: "attack" or "nextTurn"). */
    record GainStrengthUntil(int amount, String until) implements EffectStep {}

    /** "esta carta infringe daño directo": damage equal to its strength, by effect. */
    record DamageBySelfStrength() implements EffectStep {}

    /** "pero no puede declarar ataque este turno": the creature this effect summoned. */
    record SummonedCannotAttack() implements EffectStep {}

    /** "puedes subir los materiales de esta carta a tu mano": all of them; they are the last "tributed" for {@link IfTributed}. */
    record MaterialsToHand() implements EffectStep {}

    /** "coloca esta carta al tope de tus vidas bocabajo". */
    record SelfToLife() implements EffectStep {}

    /** "coloca 1 criatura [filtro] de los materiales de esta carta en tu retaguardia" (placed, not summoned). */
    record MaterialToBack(CardFilter filter) implements NeedsTarget {}

    /** "coloca esta carta en tu retaguardia" from a zone (placed, not summoned). */
    record PlaceSelfOnField(String zone) implements NeedsTarget {}

    /** "juega 1 [estructura / utilidad] [filtro] de tus [zona] reduciendo su coste en N". */
    record PlayFromZone(String zone, CardFilter filter, int reduce) implements NeedsTarget {}

    /** "mira las primeras N cartas: de ellas tributa M y envía el resto al fondo de tu mazo en cualquier orden". */
    record TributeFromLookedRestToBottom(int count) implements EffectStep {}

    /** "añade 1 [aliado / criatura] [filtro] de tus [zonas] como recurso de esta carta". */
    record AddResourceFrom(String zones, CardFilter filter) implements EffectStep {}

    /** "sube esta carta a tu mano" (from the field). */
    record ReturnSelfToHand() implements EffectStep {}

    /** "reactiva 1 de tus estructuras [filtro]". */
    record UntapStructure(CardFilter filter) implements NeedsTarget {}

    /** Celeridad: "puede subir a tu línea frontal". */
    record MoveSelfToFront() implements NeedsTarget {}

    /** "puedes jugar esta carta sin pagar su coste" (from the hand). */
    record PlaySelfFree() implements NeedsTarget {}

    /**
     * "[esta carta / un aliado] invoca por Ritual 1 criatura de tus [zona] reduciendo su coste especial en N"
     * (rule 9.3): the controller picks the creature, then the summoner and the other creatures that meet its
     * Requerimiento; they are sacrificed and it is summoned in the summoner's place.
     */
    record RitualFromZone(String zone, boolean selfSummoner, int reduce) implements NeedsTarget {}

    /**
     * "puedes A; B": asks the controller. If they decline, or A cannot be done, the next {@code covers}
     * steps (A and B) are skipped.
     */
    record MayDo(String question, int covers) implements EffectStep {}

    /** {@link LookTop} whose count depends on the game ("mira 1 carta más por cada copia de esta carta en tus tributos"). */
    record LookTopBy(Amount count) implements EffectStep {}

    /** "envíalas al tope o al fondo de tu mazo" / "devuélvelas al tope o envíalas al fondo": the looked cards, all together. */
    record LookedToTopOrBottom() implements EffectStep {}

    /** "de ellas toma hasta N [filtro]. Tributa el resto." (after {@link LookTop}). */
    record TakeFromLookedRestToTributes(int max, CardFilter filter) implements EffectStep {}

    /** "Si [condición sobre la mesa]:": the next {@code covers} steps only if it holds now. */
    record IfCondition(Condition condition, int covers) implements EffectStep {}

    /** "por cada material tributado elige 1 enemigo distinto": as many enemies as the last count (lastCount). */
    record ChooseEnemiesPerCount(CardFilter filter) implements NeedsTarget {}

    /** "reduce en N su fuerza por cada material removido": N times the last count, on the chosen targets. */
    record ReduceChosenPerCount(int each) implements EffectStep {}

    /** "todos los aliados [filtro] ganan [habilidad] hasta el final del turno". */
    record AlliesGainUntilEndOfTurn(CardFilter filter, String keyword) implements EffectStep {}

    /** "tributa tu mano": all of it. */
    record TributeHand() implements EffectStep {}

    /** "termina tu turno": once nothing is pending, the turn goes on to its end phase. */
    record EndYourTurn() implements EffectStep {}

    /** "coloca esta carta en tu línea frontal [agotada]" from a zone (placed, not summoned). */
    record PlaceSelfInFrontLine(String zone, boolean exhausted) implements EffectStep {}

    /** "añádele esta carta como recurso": this card becomes a Recurso of the subject (the ally of the trigger, or the chosen one). */
    record AttachSelfAsResourceToSubject() implements EffectStep {}

    /** "tributa N recursos [filtro] de esta carta". */
    record TributeResources(int count, CardFilter filter) implements EffectStep {}

    /** "activa 1 de sus efectos En Invocación": the subject activates its own "En Invocación" as a new link. */
    record ActivateSubjectSummonEffect() implements EffectStep {}

    /** "esta carta …" again after a step that chose another card (a sacrifice, an ally): the next steps act on this card. */
    record SubjectIsThisCard() implements EffectStep {}
}
