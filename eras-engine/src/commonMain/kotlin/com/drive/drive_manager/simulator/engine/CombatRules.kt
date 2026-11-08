package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.platform.Platform
import kotlin.jvm.JvmStatic
import kotlin.math.max
import com.drive.drive_manager.simulator.engine.Chain.advance
import com.drive.drive_manager.simulator.engine.EffectEngine.CANNOT_TAP
import com.drive.drive_manager.simulator.engine.EffectEngine.CAUSE_BATTLE
import com.drive.drive_manager.simulator.engine.EffectEngine.END_TURN_REQUESTED
import com.drive.drive_manager.simulator.engine.EffectEngine.EXTRA_HIT
import com.drive.drive_manager.simulator.engine.EffectEngine.NO_ATTACK
import com.drive.drive_manager.simulator.engine.EffectEngine.NO_BLOCK
import com.drive.drive_manager.simulator.engine.EffectEngine.PENDING_EFFECT_DAMAGE
import com.drive.drive_manager.simulator.engine.EffectEngine.STRENGTH_ENTRY
import com.drive.drive_manager.simulator.engine.EffectEngine.canPay
import com.drive.drive_manager.simulator.engine.EffectEngine.find
import com.drive.drive_manager.simulator.engine.EffectEngine.findAnywhere
import com.drive.drive_manager.simulator.engine.EffectEngine.gemsOf
import com.drive.drive_manager.simulator.engine.EffectEngine.gemsText
import com.drive.drive_manager.simulator.engine.EffectEngine.ids
import com.drive.drive_manager.simulator.engine.EffectEngine.immuneTo
import com.drive.drive_manager.simulator.engine.EffectEngine.inFrontLine
import com.drive.drive_manager.simulator.engine.EffectEngine.keywordLevel
import com.drive.drive_manager.simulator.engine.EffectEngine.keywords
import com.drive.drive_manager.simulator.engine.EffectEngine.levels
import com.drive.drive_manager.simulator.engine.EffectEngine.log
import com.drive.drive_manager.simulator.engine.EffectEngine.loseByLives
import com.drive.drive_manager.simulator.engine.EffectEngine.name
import com.drive.drive_manager.simulator.engine.EffectEngine.record
import com.drive.drive_manager.simulator.engine.EffectEngine.rulesPrompt
import com.drive.drive_manager.simulator.engine.EffectEngine.strength
import com.drive.drive_manager.simulator.engine.Removals.destroy
import com.drive.drive_manager.simulator.engine.StepHelpers.prompt
import com.drive.drive_manager.simulator.engine.StepHelpers.subject
import com.drive.drive_manager.simulator.engine.Summons.checkMainPhase
import com.drive.drive_manager.simulator.engine.Summons.summonByEffect
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/** Combat (rule 10): declaring an attack, then its steps one by one (attack, block, clash, direct damage, end). */
object CombatRules {

    // ------------------------------------------------------------------ Combat (rule 10)

    /**
     * Declares an attack from the board (rule 10.1.1): an active creature of your Línea frontal attacks an
     * exhausted enemy creature (`targetId`) or the opponent (null). Exhausting it and declaring the attack
     * are one action; it is only possible with 0 or more gems.
     */
    @JvmStatic
    fun attackByPlayer(room: GameRoom, userId: String?, attackerId: String?, targetId: String?): String? {
        var error = checkMainPhase(room, userId, "atacar")
        if (error != null) return error
        val player = room.getPlayer(userId)
        val attacker = if (player == null) null else find(player.field, attackerId)
        error = whyCannotAttack(room, userId, attacker)
        if (error != null) return error
        if (targetId != null) {
            val opponent = room.getPlayer(room.getOpponentId(userId))
            val target = if (opponent == null) null else find(opponent.field, targetId)
            if (target == null || "creature" != target.cardType) return "Solo puedes atacar a criaturas enemigas"
            if (!inFrontLine(target)) return "No puedes atacar a criaturas de la retaguardia"
            if (!target.tapped && !challenges(attacker, target)) return "Solo puedes atacar a criaturas agotadas"
        }
        attacker!!.tapped = true
        startCombat(room, userId, attacker, targetId, false, null)
        advance(room)
        return null
    }

    /** Why this card cannot declare a regular attack now; null: it can (the main phase of the turn is asked apart). */
    @JvmStatic
    fun whyCannotAttack(room: GameRoom, userId: String?, attacker: CardSlot?): String? {
        if (attacker == null || "creature" != attacker.cardType) return "Solo tus criaturas del campo pueden atacar"
        if (!inFrontLine(attacker)) return "Solo las criaturas de la línea frontal pueden atacar"
        if (attacker.tapped) return "Esa criatura está agotada"
        if (keywords(attacker).contains(NO_ATTACK)) return name(attacker) + " no puede declarar ataque"
        if (keywords(attacker).contains(CANNOT_TAP)) return name(attacker) + " no puede agotarse para declarar ataque"
        if (!attackGemsOk(room, userId, attacker)) {
            val furia = furia(attacker)
            return if (furia == null) "Para declarar un ataque necesitas 0 o más gemas"
            else "Con Furia " + furia + " puedes declarar ataque mientras tu rival tenga " + furia + " o menos gemas"
        }
        return null
    }

    /**
     * Rule 10.1.1.1: a regular attack needs 0 or more gems. Furia N lets the creature declare it while the
     * opponent has N or fewer gems (−N or more for you); Furia without a number, with any gems. It does not lift
     * exhausting the creature to declare it.
     */
    @JvmStatic
    fun attackGemsOk(room: GameRoom, userId: String?, attacker: CardSlot?): Boolean {
        val gems = gemsOf(room, userId)
        if (gems >= 0) return true
        val furia = furia(attacker)
        return furia != null && (furia < 0 || gems >= -furia)
    }

    /** Furia N → N; Furia without a number → −1 (no limit); null without Furia. */
    @JvmStatic
    fun furia(card: CardSlot?): Int? {
        var best: Int? = null
        for (k in keywords(card)) {
            if (!k.lowercase().startsWith("furia")) continue
            val m = Platform.regex("(\\d+)").find(k)
            if (m == null) return -1
            best = if (best == null) m.groupValues[1].toInt() else max(best, m.groupValues[1].toInt())
        }
        return best
    }

    /** The declaration is the attack step itself: its "Al Atacar" triggers resolve before the block (rule 10.1.1.3). */
    @JvmStatic
    fun startCombat(room: GameRoom, userId: String?, attacker: CardSlot, targetId: String?, byEffect: Boolean, effect: PendingEffect?) {
        val player = room.getPlayer(userId)
        val opponent = room.getPlayer(room.getOpponentId(userId))
        val combat = Combat()
        combat.attackerId = attacker.instanceId
        combat.attackerOwnerId = userId
        combat.defenderId = opponent?.userId
        combat.targetId = targetId
        combat.byEffect = byEffect
        combat.step = Combat.Step.ATTACK
        combat.phase = Combat.Phase.EXIT
        room.combat = combat
        // "hasta el final del turno o de su próximo ataque": this is that attack
        attacker.untilNextAttack!!.forEach { entry -> combat.untilEndOfAttack.add(attacker.instanceId + "|" + entry) }
        attacker.untilNextAttack!!.clear()
        val target = if (targetId == null || opponent == null) null else find(opponent.field, targetId)
        val data: MutableMap<String, Any?> = Platform.hashMap()
        data["byEffect"] = byEffect
        data["exhausted"] = !byEffect
        if (targetId != null) data["targetId"] = targetId
        val targetName = if (target != null) name(target) else if (opponent != null) opponent.username else "el rival"
        record(room, GameEvent.ATTACK_DECLARED, userId, attacker, null, null, effect, data, if (byEffect)
            player!!.username + ": " + name(attacker) + " ataca a " + targetName
        else player!!.username + " agota a " + name(attacker) + " y ataca a " + targetName)
    }

    /**
     * Moves the combat on (rule 10.1). Each step is entered, carried out and left; entering and leaving record
     * an event, so the triggers of that moment resolve before the combat goes on.
     */
    @JvmStatic
    fun combatStep(room: GameRoom) {
        val c = room.combat!!
        val owner = room.getPlayer(c.attackerOwnerId)
        val defender = room.getPlayer(c.defenderId)
        val attacker = if (owner == null) null else find(owner.field, c.attackerId)
        // Rule 10.0.0.2: if the attacker leaves the field the combat ends at once; there is no end of combat
        if (attacker == null || defender == null) {
            room.combat = null
            clearUntilEndOfAttack(room, c)
            log(room, "El combate termina: el atacante dejó el campo", null)
            return
        }
        when (c.phase!!) {
            Combat.Phase.ENTER -> {
                if (c.step == Combat.Step.END) {
                    endCombat(room)
                    return
                }
                record(room, GameEvent.COMBAT_STEP, owner!!.userId, attacker, null, null, null,
                    frozenMapOf<String, Any?>("step", c.step!!.name, "moment", "ENTER"), "Paso de " + stepName(c.step))
                c.phase = Combat.Phase.ACT
            }
            Combat.Phase.ACT -> {
                if (actStep(room, c, owner!!, defender, attacker)) c.phase = Combat.Phase.EXIT
            }
            Combat.Phase.EXIT -> {
                record(room, GameEvent.COMBAT_STEP, owner!!.userId, attacker, null, null, null,
                    frozenMapOf<String, Any?>("step", c.step!!.name, "moment", "EXIT"), null)
                c.step = nextStep(c, defender, attacker)
                c.phase = Combat.Phase.ENTER
            }
        }
    }

    @JvmStatic
    fun nextStep(c: Combat, defender: PlayerState, attacker: CardSlot?): Combat.Step {
        return when (c.step!!) {
            Combat.Step.ATTACK -> if (c.skipBlock) Combat.Step.DAMAGE else Combat.Step.BLOCK
            // Rule 10.1.3.1: the clash only happens if there is a blocker when the step starts
            Combat.Step.BLOCK -> if (c.blockerId != null && find(defender.field, c.blockerId) != null)
                Combat.Step.CLASH else Combat.Step.DAMAGE
            // Perforante: "al final del enfrentamiento, si el bloqueador fue destruido: entra al paso de daño directo"
            Combat.Step.CLASH -> if (keywordLevel(attacker, "Perforante") != null && find(defender.field, c.blockerId) == null)
                Combat.Step.DAMAGE else Combat.Step.END
            Combat.Step.DAMAGE, Combat.Step.END -> Combat.Step.END
        }
    }

    @JvmStatic
    fun stepName(step: Combat.Step?): String {
        return when (step!!) {
            Combat.Step.ATTACK -> "ataque"
            Combat.Step.BLOCK -> "bloqueo"
            Combat.Step.CLASH -> "enfrentamiento"
            Combat.Step.DAMAGE -> "daño directo"
            Combat.Step.END -> "final del combate"
        }
    }

    /** Carries out the step. Returns false while it waits for a choice. */
    @JvmStatic
    fun actStep(room: GameRoom, c: Combat, owner: PlayerState, defender: PlayerState, attacker: CardSlot): Boolean {
        when (c.step!!) {
            Combat.Step.BLOCK -> {
                // Provocar: "al entrar al paso de bloqueo puedes elegir 1 enemigo; deberá bloquear"
                if (!c.provokeAsked) {
                    c.provokeAsked = true
                    val provoke = keywordLevel(attacker, "Provocar")
                    if (provoke != null && c.blockerId == null) {
                        val options = blockers(defender).filter { b ->
                            b.instanceId != c.targetId
                                    && (provoke == 0 || (b.getLevel() != null && b.getLevel()!! <= provoke))
                                    // Provocar is an effect of the attacker that targets: "no puede ser objetivo de efectos…" (HE-110) is left out
                                    && !immuneTo(b, if (attacker.getLevel() == null) 0 else attacker.getLevel()!!)
                        }
                        if (!options.isEmpty()) {
                            rulesPrompt(room, owner.userId, PromptPurpose.PROVOKE.id(), name(attacker) + " · Provocar",
                                "Puedes elegir 1 enemigo" + (if (provoke > 0) " nivel " + provoke + " o menor" else "") + ": deberá bloquear este ataque.",
                                "Provocar", options, defender.field)
                            return false
                        }
                    }
                }
                // Rule 10.1.2.3: the defender may declare a blocker, unless one was already declared
                if (c.blockerId == null && !c.blockAsked) {
                    c.blockAsked = true
                    val options = if (keywordLevel(attacker, "Imbloqueable") != null) frozenListOf<CardSlot>()
                    else blockers(defender).filter { b -> b.instanceId != c.targetId }
                    if (!options.isEmpty()) {
                        rulesPrompt(room, defender.userId, PromptPurpose.BLOCK.id(), name(attacker) + " ataca",
                            "Puedes elegir 1 criatura activa de tu línea frontal para bloquear (se agota), o ninguna.",
                            "Bloquear", options, defender.field)
                        return false
                    }
                }
                // Rule 10.1.2.7: attacking a creature that is not blocked makes it the blocker
                if (c.blockerId == null && c.targetId != null && find(defender.field, c.targetId) != null) {
                    c.blockerId = c.targetId
                }
                return true
            }
            Combat.Step.CLASH -> {
                val blocker = if (c.blockerId == null) null else find(defender.field, c.blockerId)
                // Rule 10.1.3.5: a blocker removed during the clash leaves the attacker as the winner
                if (blocker == null) return true
                val a = strength(attacker)
                val b = strength(blocker)
                record(room, GameEvent.CLASH, owner.userId, attacker, null, null, null,
                    frozenMapOf<String, Any?>("blockerId", blocker.instanceId, "attackerStrength", a, "blockerStrength", b),
                    "Enfrentamiento: " + name(attacker) + " (" + a + ") contra " + name(blocker) + " (" + b + ")")
                // Rule 10.1.3.2: the weaker one is destroyed; both when equal. Attributed to the opponent and the other creature.
                if (b <= a) destroy(room, blocker, defender, false, CAUSE_BATTLE, owner.userId, attacker.instanceId, null)
                // Alcance: "esta carta no puede ser destruida por enfrentamiento como atacante"
                if (a <= b && keywordLevel(attacker, "Alcance") == null) {
                    destroy(room, attacker, owner, false, CAUSE_BATTLE, defender.userId, blocker.instanceId, null)
                }
                return true
            }
            Combat.Step.DAMAGE -> {
                return directDamage(room, c, owner, defender, attacker)
            }
            else -> {
                return true
            }
        }
    }

    /** Creatures that can block: active, in the Línea frontal (rules 4.0.6.1, 6.2.1.3). */
    @JvmStatic
    fun blockers(defender: PlayerState): List<CardSlot> {
        return defender.field
            .filter { c -> "creature" == c.cardType && inFrontLine(c) && !c.tapped && !keywords(c).contains(NO_BLOCK) }
    }

    /**
     * Rule 10.1.4: the attacker deals damage equal to its strength to the top life card, which is revealed. A
     * creature with that strength or less is tributed; a stronger one goes to the hand, and if it was just
     * revealed it may Reforzar instead (face up at the bottom of the life stack). A utility or structure may be
     * played without paying its cost, otherwise it goes to the hand. Letalidad N repeats the damage.
     *
     * @return false while it waits for the defender's choice
     */
    @JvmStatic
    fun directDamage(room: GameRoom, c: Combat, owner: PlayerState, defender: PlayerState, attacker: CardSlot): Boolean {
        if (c.hitsLeft == null) {
            val lethality = keywordLevel(attacker, "Letalidad")
            c.hitsLeft = 1 + (lethality ?: 0)
            // "la próxima vez que un aliado inflinja daño directo por combate este turno, inflinge daño 1 vez más"
            if (room.usedThisTurn.remove(EXTRA_HIT + owner.userId)) {
                c.hitsLeft = c.hitsLeft!! + 1
                room.usedThisTurn.add(END_TURN_REQUESTED + owner.userId)
                log(room, name(attacker) + " inflige daño 1 vez más: después termina el turno de " + owner.username, null)
            }
        }
        val damage = strength(attacker)
        if (offerDamageReplacement(room, c, defender, damage)) return false
        while (c.hitsLeft!! > 0 && !defender.lifeStack.isEmpty()) {
            // Estoque: offered before each hit
            if (c.hitsLeft != c.damageTributeAskedAt && !damageTributeCards(defender).isEmpty()) {
                c.damageTributeAskedAt = c.hitsLeft
                offerDamageTribute(room, defender, damage, "combat")
                return false
            }
            c.hitsLeft = c.hitsLeft!! - 1
            val life = defender.lifeStack.removeAt(0)
            val revealedNow = life.faceDown
            life.faceDown = false
            val creature = "creature" == life.cardType
            val tribute = creature && (life.strength == null || CardEffects.lifeStrength(room, defender.userId, life) <= damage)
            (if (tribute) defender.tributeZone else defender.hand).add(life)
            record(room, GameEvent.LIFE_DAMAGE, owner.userId, life, "lifeStack", if (tribute) "tributeZone" else "hand", null,
                frozenMapOf<String, Any?>("damage", damage, "attackerId", attacker.instanceId, "defenderId", defender.userId),
                name(attacker) + " inflige " + damage + " de daño directo: " + defender.username + " pierde la vida " +
                        name(life) + (if (tribute) ", que va a sus tributos" else ", que va a su mano"))
            if (creature && !tribute && revealedNow) {
                lifePrompt(room, defender, life, PromptPurpose.REINFORCE.id(), "Reforzar", name(life) +
                        " tiene más fuerza que el daño y va a tu mano. ¿Refuerzas con ella? Se coloca bocarriba al fondo de tu pila de vidas.", "Reforzar")
                return false
            }
            val lifeCost = CardEffects.lifePlayCost(life)
            if (("utility" == life.cardType || "structure" == life.cardType)
                && Chain.cannotActivateOnPlay(room, defender.userId, life) == null && canPay(room, defender.userId, lifeCost)) {
                lifePrompt(room, defender, life, PromptPurpose.LIFE_PLAY.id(), "Vida revelada",
                    "¿Juegas " + name(life) + (if (lifeCost > 0) " pagando " + gemsText(lifeCost) else " sin pagar su coste") + "? Si no, queda en tu mano.", "Jugar")
                return false
            }
            // The revealed life is fully resolved (its triggers too) before the next hit (Letalidad)
            if (c.hitsLeft!! > 0 && !defender.lifeStack.isEmpty()) return false
        }
        if (defender.lifeStack.isEmpty()) loseByLives(room, defender)
        return true
    }

    /**
     * Comandante Veterano: "Turno Oponente: una vez por turno, cuando un aliado sea declarado bloqueador: en su lugar, puedes
     * reactivarlo y elegir otro aliado para que sea declarado bloqueador". Offered to the defender after a block.
     *
     * @return true when it waits for the defender's answer
     */
    @JvmStatic
    fun offerBlockerSwap(room: GameRoom, defender: PlayerState, blocker: CardSlot): Boolean {
        val holder = defender.field.firstOrNull { c -> !c.faceDown && CardEffects.swapsBlocker(c) }
        if (holder == null || room.usedThisTurn.contains(defender.userId + "|blocker-swap")) return false
        val combat = room.combat
        // Declared by the effect, not by the rules: an exhausted creature of the Línea frontal can be the new blocker too
        // (designer ruling 2026-10-30)
        val others = defender.field
            .filter { b -> "creature" == b.cardType && inFrontLine(b) && b !== blocker && (combat == null || b.instanceId != combat.targetId) }
        if (others.isEmpty()) return false
        rulesPrompt(room, defender.userId, PromptPurpose.BLOCK_SWAP.id(), name(holder) + " · Turno Oponente",
            "En lugar de " + name(blocker) + ", puedes reactivarlo y elegir otro aliado para que bloquee. O ninguno.", "Bloquea", others,
            defender.field)
        room.prompt!!.subjectId = blocker.instanceId
        room.prompt!!.min = 0
        return true
    }

    @JvmStatic
    fun swapBlocker(room: GameRoom, defender: PlayerState, prompt: Prompt, picks: List<String>) {
        val c = room.combat
        val first = find(defender.field, prompt.subjectId)
        val other = if (picks.isEmpty()) null else find(defender.field, picks[0])
        if (c == null || first == null || other == null) return
        room.usedThisTurn.add(defender.userId + "|blocker-swap")
        first.tapped = false
        // The effect declares it: it is not exhausted for blocking (an active one stays active)
        c.blockerId = other.instanceId
        record(room, GameEvent.UNTAP, defender.userId, first, null, null, null, null, defender.username + " reactiva a " + name(first))
        record(room, GameEvent.BLOCK, defender.userId, other, null, null, null, frozenMapOf<String, Any?>("forced", false),
            name(other) + " bloquea el ataque en lugar de " + name(first))
    }

    /**
     * Mercenario Valeroso: "En Mano: una vez por turno entre copias, cuando fueras a recibir 8 o menos de daño directo por ataque:
     * en su lugar, puedes invocar esta carta agotada en tu línea frontal reduciendo su coste en 1". Offered before the first hit.
     *
     * @return true when it waits for the defender's answer
     */
    @JvmStatic
    fun offerDamageReplacement(room: GameRoom, c: Combat, defender: PlayerState, damage: Int): Boolean {
        if (c.damageReplaceAsked) return false
        c.damageReplaceAsked = true
        val options = defender.hand.filter { card ->
            val r = CardEffects.damageReplacement(card)
            r != null && damage <= r.maxDamage && card.cost != null
                    && !room.usedThisTurn.contains(defender.userId + "|damage-replace|" + card.cardName)
                    && canPay(room, defender.userId, max(0, card.cost!! - r.reduce))
        }
        if (options.isEmpty()) return false
        rulesPrompt(room, defender.userId, PromptPurpose.DAMAGE_REPLACE.id(), "Daño directo: " + damage,
            "En lugar de recibir el daño, puedes invocar 1 de estas cartas agotada en tu línea frontal reduciendo su coste. O ninguna.",
            "Invocar", options, options)
        room.prompt!!.min = 0
        return true
    }

    @JvmStatic
    fun replaceDamage(room: GameRoom, defender: PlayerState, picks: List<String>) {
        val c = room.combat
        val card = if (picks.isEmpty()) null else find(defender.hand, picks[0])
        val r = if (card == null) null else CardEffects.damageReplacement(card)
        if (c == null || r == null) return
        room.usedThisTurn.add(defender.userId + "|damage-replace|" + card!!.cardName)
        val effect = PendingEffect()
        effect.id = room.nextId("eff")
        effect.controllerId = defender.userId
        effect.sourceInstanceId = card.instanceId
        effect.sourceName = name(card)
        summonByEffect(room, effect, defender, card, "hand", r.reduce, true)
        card.tapped = true
        c.hitsLeft = 0
        log(room, name(card) + " entra en lugar del daño directo: " + defender.username + " no recibe daño", null)
    }

    /** The Estoque cards of the hand that can be paid: 2 other [retador] of the hand, the 3 of distinct levels. */
    @JvmStatic
    fun damageTributeCards(defender: PlayerState): List<CardSlot> {
        return defender.hand.filter { e ->
            CardEffects.tributesInsteadOfDamage(e) && run {
                val others = defender.hand.filter { c -> c !== e && retador(c) && c.getLevel() != null && c.getLevel() != e.getLevel() }
                others.any { a -> others.any { b -> a !== b && a.getLevel() != b.getLevel() } }
            }
        }
    }

    @JvmStatic
    fun retador(card: CardSlot): Boolean {
        return card.classes != null && card.classes!!.any { "retador".equals(it, ignoreCase = true) }
    }

    /** "cuando fueras a recibir daño: en su lugar, puedes tributar esta carta más 2 [retador] de tu mano, todas de distinto nivel". */
    @JvmStatic
    fun offerDamageTribute(room: GameRoom, defender: PlayerState, damage: Int, context: String?) {
        val valid = defender.hand.filter { c -> CardEffects.tributesInsteadOfDamage(c) || retador(c) }
        rulesPrompt(room, defender.userId, PromptPurpose.DAMAGE_TRIBUTE.id(), "Vas a recibir " + damage + " de daño",
            "En su lugar, puedes tributar Estoque De Los Guerreros Jurados y 2 [retador] más de tu mano, los 3 de distinto nivel. O ninguna.",
            "Tributar", valid, defender.hand)
        room.prompt!!.min = 0
        room.prompt!!.max = 3
        room.prompt!!.subjectId = context
    }

    /** Its answer: the 3 cards are tributed and that damage is not received (in an attack, nor the next hits); none: the damage goes on. */
    @JvmStatic
    fun answerDamageTribute(room: GameRoom, prompt: Prompt?, player: PlayerState, picks: List<String>): String? {
        val effectMark = room.usedThisTurn.firstOrNull { k -> k.startsWith(PENDING_EFFECT_DAMAGE) }
        if (!picks.isEmpty()) {
            val cards = picks.map { id -> find(player.hand, id) }
            if (cards.size != 3 || cards.contains(null)) return "Elige Estoque y 2 [retador] de tu mano, o ninguna"
            if (cards.none { CardEffects.tributesInsteadOfDamage(it!!) } || !cards.all { retador(it!!) })
                return "Elige Estoque De Los Guerreros Jurados y 2 [retador] más"
            if (cards.map { it!!.getLevel() }.distinct().size < 3) return "Las 3 cartas deben ser de distinto nivel"
        }
        room.prompt = null
        if (effectMark != null) room.usedThisTurn.remove(effectMark)
        if (picks.isEmpty()) {
            // Not replaced: an effect's damage happens now (an attack's goes on by itself)
            if (effectMark != null) {
                val p = JavaText.split(Platform.regex("\\|"), effectMark.substring(PENDING_EFFECT_DAMAGE.length))
                val effect = room.chain.firstOrNull { e -> e.id == p[0] }
                val controller = room.getPlayer(p[2])
                if (effect != null && controller != null) StepHelpers.damageByEffect(room, effect, controller, p[1].toInt(), false)
            }
            advance(room)
            return null
        }
        for (id in picks) {
            val c = find(player.hand, id)
            player.hand.remove(c)
            player.tributeZone.add(c!!)
            record(room, GameEvent.TRIBUTE, player.userId, c, "hand", "tributeZone", null, null, null)
        }
        log(room, player.username + " tributa " + picks.joinToString(", ") { id -> name(find(player.tributeZone, id)) }
                + " y no recibe el daño", room.eventSeq)
        // A prevented hit is no damage received: the next ones of Letalidad do not come
        val c = room.combat
        if (effectMark == null && c != null) c.hitsLeft = 0
        advance(room)
        return null
    }

    @JvmStatic
    fun lifePrompt(room: GameRoom, defender: PlayerState, life: CardSlot, purpose: String?, title: String?, instruction: String?, label: String?) {
        rulesPrompt(room, defender.userId, purpose, title, instruction, label, frozenListOf(life), frozenListOf(life))
        room.prompt!!.kind = Prompt.Kind.CONFIRM
        room.prompt!!.subjectId = life.instanceId
    }

    /** Rule 10.1.5: the end of the combat fires "Al Final Del Ataque", resolved outside the combat. */
    @JvmStatic
    fun endCombat(room: GameRoom) {
        val c = room.combat!!
        room.combat = null
        clearUntilEndOfAttack(room, c)
        val owner = room.getPlayer(c.attackerOwnerId)
        val attacker = if (owner == null) null else findAnywhere(room, owner.userId, c.attackerId)
        if (owner != null) record(room, GameEvent.ATTACK_ENDED, owner.userId, attacker, null, null, null,
            frozenMapOf<String, Any?>("enemyDestroyed", c.enemyDestroyed), "Termina el combate")
    }

    /** "hasta el final del ataque" ends with the combat (and "al final del ataque súbelo a tu mano con todos sus materiales"). */
    @JvmStatic
    fun clearUntilEndOfAttack(room: GameRoom, c: Combat) {
        for (mark in ArrayList(room.usedThisTurn)) {
            if (!mark.startsWith(StepHelpers.RETURN_AT_ATTACK_END)) continue
            room.usedThisTurn.remove(mark)
            val p = JavaText.split(Platform.regex("\\|"), mark.substring(StepHelpers.RETURN_AT_ATTACK_END.length))
            val owner = room.getPlayer(p[0])
            val card = if (owner == null) null else find(owner.field, p[1])
            if (card == null) continue
            val materials = ArrayList(card.materials!!)
            card.materials!!.clear()
            Removals.removeFromField(owner, card)
            owner!!.hand.add(card)
            materials.forEach { m ->
                m.faceDown = false
                owner.hand.add(m)
            }
            record(room, GameEvent.SEND, owner.userId, card, "field", "hand", null, null,
                owner.username + " sube " + name(card) + " y sus materiales a su mano al final del ataque")
        }
        for (entry in c.untilEndOfAttack) {
            val parts = JavaText.split(Platform.regex("\\|"), entry, 2)
            for (p in room.players.values) {
                val card = find(p.field, parts[0]) ?: continue
                if (parts[1].startsWith(STRENGTH_ENTRY)) {
                    val amount = parts[1].substring(STRENGTH_ENTRY.length).toInt()
                    card.turnStrength = card.turnStrength - amount
                    card.floating!!.removeAll { f -> "attack" == f.until && f.keyword == null && f.strength == amount }
                    continue
                }
                card.turnKeywords!!.remove(parts[1])
                card.floating!!.removeAll { f -> "attack" == f.until && parts[1] == f.keyword }
            }
        }
        c.untilEndOfAttack.clear()
    }

    /** Enfrentar: "puede atacar enemigos activos"; Enfrentar N, only those of level N or less. */
    @JvmStatic
    fun challenges(attacker: CardSlot?, target: CardSlot): Boolean {
        val level = if (attacker == null) null else keywordLevel(attacker, "Enfrentar")
        return level != null && (level == 0 || (target.getLevel() != null && target.getLevel()!! <= level))
    }

    /** Enemy creatures that can be attacked (rule 10.0.0.1): exhausted ones, or active ones the attacker can face (Enfrentar). */
    @JvmStatic
    fun attackTargets(room: GameRoom, userId: String?, attacker: CardSlot?): List<CardSlot> {
        val opponent = room.getPlayer(room.getOpponentId(userId)) ?: return frozenListOf()
        // Rule 4.0.6.2: the Retaguardia cannot be attacked
        return opponent.field
            .filter { c -> "creature" == c.cardType && inFrontLine(c) && (c.tapped || challenges(attacker, c)) }
    }

    /** "declara ataque" by an effect: the subject attacks if it can. Returns true when the player is asked what to attack. */
    @JvmStatic
    fun attackByEffect(room: GameRoom, effect: PendingEffect, opponentOnly: Boolean): Boolean {
        val userId = effect.controllerId
        val subject = subject(room, effect) ?: return false
        if (room.combat != null) {
            log(room, name(subject) + " no puede atacar: ya hay un combate en curso", null)
            return false
        }
        if (keywords(subject).contains(NO_ATTACK)) {
            log(room, name(subject) + " no puede declarar ataque este turno", null)
            return false
        }
        if (!inFrontLine(subject)) {
            log(room, name(subject) + " no puede atacar: no está en la línea frontal", null)
            return false
        }
        val targets = if (opponentOnly) frozenListOf<CardSlot>() else attackTargets(room, userId, subject)
        if (targets.isEmpty()) {
            startCombat(room, userId, subject, null, true, effect)
            return false
        }
        val opponent = room.getPlayer(room.getOpponentId(userId))
        val prompt = prompt(room, effect, Prompt.Kind.PICK,
            name(subject) + " declara ataque: elige una criatura agotada a la que atacar, o ninguna para atacar a tu rival.")
        prompt.cards = ArrayList(opponent!!.field)
        prompt.validIds = ArrayList(ids(targets))
        prompt.min = 0
        prompt.max = 1
        prompt.chosenLabel = "Atacar"
        room.prompt = prompt
        return true
    }
}
