package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.FloatingEffect;
import com.drive.drive_manager.simulator.GameLogEntry;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectScript.Limit;
import com.drive.drive_manager.simulator.engine.EffectStep.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.drive.drive_manager.simulator.engine.EffectEngine.*;
import static com.drive.drive_manager.simulator.engine.Summons.*;
import static com.drive.drive_manager.simulator.engine.Removals.*;
import static com.drive.drive_manager.simulator.engine.TurnRules.*;
import static com.drive.drive_manager.simulator.engine.Chain.*;
import static com.drive.drive_manager.simulator.engine.Steps.*;
import static com.drive.drive_manager.simulator.engine.Answers.*;

/** Combat (rule 10): declaring an attack, then its steps one by one (attack, block, clash, direct damage, end). */
public final class CombatRules {

    private CombatRules() {}

    // ------------------------------------------------------------------ Combat (rule 10)

    /**
     * Declares an attack from the board (rule 10.1.1): an active creature of your Línea frontal attacks an
     * exhausted enemy creature ({@code targetId}) or the opponent (null). Exhausting it and declaring the attack
     * are one action; it is only possible with 0 or more gems.
     */
    public static String attackByPlayer(GameRoom room, String userId, String attackerId, String targetId) {
        String error = checkMainPhase(room, userId, "atacar");
        if (error != null) return error;
        PlayerState player = room.getPlayer(userId);
        CardSlot attacker = player == null ? null : find(player.getField(), attackerId);
        if (attacker == null || !"creature".equals(attacker.getCardType())) return "Solo tus criaturas del campo pueden atacar";
        if (!inFrontLine(attacker)) return "Solo las criaturas de la línea frontal pueden atacar";
        if (attacker.isTapped()) return "Esa criatura está agotada";
        if (keywords(attacker).contains(NO_ATTACK)) return name(attacker) + " no puede declarar ataque";
        if (keywords(attacker).contains(CANNOT_TAP)) return name(attacker) + " no puede agotarse para declarar ataque";
        if (!attackGemsOk(room, userId, attacker)) {
            Integer furia = furia(attacker);
            return furia == null ? "Para declarar un ataque necesitas 0 o más gemas"
                    : "Con Furia " + furia + " puedes declarar ataque mientras tu rival tenga " + furia + " o menos gemas";
        }
        if (targetId != null) {
            PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
            CardSlot target = opponent == null ? null : find(opponent.getField(), targetId);
            if (target == null || !"creature".equals(target.getCardType())) return "Solo puedes atacar a criaturas enemigas";
            if (!inFrontLine(target)) return "No puedes atacar a criaturas de la retaguardia";
            if (!target.isTapped() && !challenges(attacker, target)) return "Solo puedes atacar a criaturas agotadas";
        }
        attacker.setTapped(true);
        startCombat(room, userId, attacker, targetId, false, null);
        advance(room);
        return null;
    }

    /**
     * Rule 10.1.1.1: a regular attack needs 0 or more gems. Furia N lets the creature declare it while the
     * opponent has N or fewer gems (−N or more for you); Furia without a number, with any gems. It does not lift
     * exhausting the creature to declare it.
     */
    public static boolean attackGemsOk(GameRoom room, String userId, CardSlot attacker) {
        int gems = gemsOf(room, userId);
        if (gems >= 0) return true;
        Integer furia = furia(attacker);
        return furia != null && (furia < 0 || gems >= -furia);
    }

    /** Furia N → N; Furia without a number → −1 (no limit); null without Furia. */
    static Integer furia(CardSlot card) {
        Integer best = null;
        for (String k : keywords(card)) {
            if (!k.toLowerCase(Locale.ROOT).startsWith("furia")) continue;
            Matcher m = Pattern.compile("(\\d+)").matcher(k);
            if (!m.find()) return -1;
            best = best == null ? Integer.parseInt(m.group(1)) : Math.max(best, Integer.parseInt(m.group(1)));
        }
        return best;
    }

    /** The declaration is the attack step itself: its "Al Atacar" triggers resolve before the block (rule 10.1.1.3). */
    static void startCombat(GameRoom room, String userId, CardSlot attacker, String targetId, boolean byEffect, PendingEffect effect) {
        PlayerState player = room.getPlayer(userId);
        PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
        Combat combat = new Combat();
        combat.setAttackerId(attacker.getInstanceId());
        combat.setAttackerOwnerId(userId);
        combat.setDefenderId(opponent == null ? null : opponent.getUserId());
        combat.setTargetId(targetId);
        combat.setByEffect(byEffect);
        combat.setStep(Combat.Step.ATTACK);
        combat.setPhase(Combat.Phase.EXIT);
        room.setCombat(combat);
        // "hasta el final del turno o de su próximo ataque": this is that attack
        attacker.getUntilNextAttack().forEach(entry -> combat.getUntilEndOfAttack().add(attacker.getInstanceId() + "|" + entry));
        attacker.getUntilNextAttack().clear();
        CardSlot target = targetId == null || opponent == null ? null : find(opponent.getField(), targetId);
        Map<String, Object> data = new HashMap<>();
        data.put("byEffect", byEffect);
        data.put("exhausted", !byEffect);
        if (targetId != null) data.put("targetId", targetId);
        String targetName = target != null ? name(target) : opponent != null ? opponent.getUsername() : "el rival";
        record(room, GameEvent.ATTACK_DECLARED, userId, attacker, null, null, effect, data, byEffect
                ? player.getUsername() + ": " + name(attacker) + " ataca a " + targetName
                : player.getUsername() + " agota a " + name(attacker) + " y ataca a " + targetName);
    }

    /**
     * Moves the combat on (rule 10.1). Each step is entered, carried out and left; entering and leaving record
     * an event, so the triggers of that moment resolve before the combat goes on.
     */
    static void combatStep(GameRoom room) {
        Combat c = room.getCombat();
        PlayerState owner = room.getPlayer(c.getAttackerOwnerId());
        PlayerState defender = room.getPlayer(c.getDefenderId());
        CardSlot attacker = owner == null ? null : find(owner.getField(), c.getAttackerId());
        // Rule 10.0.0.2: if the attacker leaves the field the combat ends at once; there is no end of combat
        if (attacker == null || defender == null) {
            room.setCombat(null);
            clearUntilEndOfAttack(room, c);
            log(room, "El combate termina: el atacante dejó el campo", null);
            return;
        }
        switch (c.getPhase()) {
            case ENTER -> {
                if (c.getStep() == Combat.Step.END) {
                    endCombat(room);
                    return;
                }
                record(room, GameEvent.COMBAT_STEP, owner.getUserId(), attacker, null, null, null,
                        Map.of("step", c.getStep().name(), "moment", "ENTER"), "Paso de " + stepName(c.getStep()));
                c.setPhase(Combat.Phase.ACT);
            }
            case ACT -> {
                if (actStep(room, c, owner, defender, attacker)) c.setPhase(Combat.Phase.EXIT);
            }
            case EXIT -> {
                record(room, GameEvent.COMBAT_STEP, owner.getUserId(), attacker, null, null, null,
                        Map.of("step", c.getStep().name(), "moment", "EXIT"), null);
                c.setStep(nextStep(c, defender, attacker));
                c.setPhase(Combat.Phase.ENTER);
            }
        }
    }

    static Combat.Step nextStep(Combat c, PlayerState defender, CardSlot attacker) {
        return switch (c.getStep()) {
            case ATTACK -> c.isSkipBlock() ? Combat.Step.DAMAGE : Combat.Step.BLOCK;
            // Rule 10.1.3.1: the clash only happens if there is a blocker when the step starts
            case BLOCK -> c.getBlockerId() != null && find(defender.getField(), c.getBlockerId()) != null
                    ? Combat.Step.CLASH : Combat.Step.DAMAGE;
            // Perforante: "al final del enfrentamiento, si el bloqueador fue destruido: entra al paso de daño directo"
            case CLASH -> keywordLevel(attacker, "Perforante") != null && find(defender.getField(), c.getBlockerId()) == null
                    ? Combat.Step.DAMAGE : Combat.Step.END;
            case DAMAGE, END -> Combat.Step.END;
        };
    }

    static String stepName(Combat.Step step) {
        return switch (step) {
            case ATTACK -> "ataque";
            case BLOCK -> "bloqueo";
            case CLASH -> "enfrentamiento";
            case DAMAGE -> "daño directo";
            case END -> "final del combate";
        };
    }

    /** Carries out the step. Returns false while it waits for a choice. */
    static boolean actStep(GameRoom room, Combat c, PlayerState owner, PlayerState defender, CardSlot attacker) {
        switch (c.getStep()) {
            case BLOCK -> {
                // Provocar: "al entrar al paso de bloqueo puedes elegir 1 enemigo; deberá bloquear"
                if (!c.isProvokeAsked()) {
                    c.setProvokeAsked(true);
                    Integer provoke = keywordLevel(attacker, "Provocar");
                    if (provoke != null && c.getBlockerId() == null) {
                        List<CardSlot> options = blockers(defender).stream()
                                .filter(b -> !b.getInstanceId().equals(c.getTargetId()))
                                .filter(b -> provoke == 0 || (b.getLevel() != null && b.getLevel() <= provoke)).toList();
                        if (!options.isEmpty()) {
                            rulesPrompt(room, owner.getUserId(), PURPOSE_PROVOKE, name(attacker) + " · Provocar",
                                    "Puedes elegir 1 enemigo" + (provoke > 0 ? " nivel " + provoke + " o menor" : "") + ": deberá bloquear este ataque.",
                                    "Provocar", options, defender.getField());
                            return false;
                        }
                    }
                }
                // Rule 10.1.2.3: the defender may declare a blocker, unless one was already declared
                if (c.getBlockerId() == null && !c.isBlockAsked()) {
                    c.setBlockAsked(true);
                    List<CardSlot> options = keywordLevel(attacker, "Imbloqueable") != null ? List.of()
                            : blockers(defender).stream().filter(b -> !b.getInstanceId().equals(c.getTargetId())).toList();
                    if (!options.isEmpty()) {
                        rulesPrompt(room, defender.getUserId(), PURPOSE_BLOCK, name(attacker) + " ataca",
                                "Puedes elegir 1 criatura activa de tu línea frontal para bloquear (se agota), o ninguna.",
                                "Bloquear", options, defender.getField());
                        return false;
                    }
                }
                // Rule 10.1.2.7: attacking a creature that is not blocked makes it the blocker
                if (c.getBlockerId() == null && c.getTargetId() != null && find(defender.getField(), c.getTargetId()) != null) {
                    c.setBlockerId(c.getTargetId());
                }
                return true;
            }
            case CLASH -> {
                CardSlot blocker = c.getBlockerId() == null ? null : find(defender.getField(), c.getBlockerId());
                // Rule 10.1.3.5: a blocker removed during the clash leaves the attacker as the winner
                if (blocker == null) return true;
                int a = strength(attacker);
                int b = strength(blocker);
                record(room, GameEvent.CLASH, owner.getUserId(), attacker, null, null, null,
                        Map.of("blockerId", blocker.getInstanceId(), "attackerStrength", a, "blockerStrength", b),
                        "Enfrentamiento: " + name(attacker) + " (" + a + ") contra " + name(blocker) + " (" + b + ")");
                // Rule 10.1.3.2: the weaker one is destroyed; both when equal. Attributed to the opponent and the other creature.
                if (b <= a) destroy(room, blocker, defender, false, CAUSE_BATTLE, owner.getUserId(), attacker.getInstanceId(), null);
                // Alcance: "esta carta no puede ser destruida por enfrentamiento como atacante"
                if (a <= b && keywordLevel(attacker, "Alcance") == null) {
                    destroy(room, attacker, owner, false, CAUSE_BATTLE, defender.getUserId(), blocker.getInstanceId(), null);
                }
                return true;
            }
            case DAMAGE -> {
                return directDamage(room, c, owner, defender, attacker);
            }
            default -> {
                return true;
            }
        }
    }

    /** Creatures that can block: active, in the Línea frontal (rules 4.0.6.1, 6.2.1.3). */
    static List<CardSlot> blockers(PlayerState defender) {
        return defender.getField().stream()
                .filter(c -> "creature".equals(c.getCardType()) && inFrontLine(c) && !c.isTapped()).toList();
    }

    /**
     * Rule 10.1.4: the attacker deals damage equal to its strength to the top life card, which is revealed. A
     * creature with that strength or less is tributed; a stronger one goes to the hand, and if it was just
     * revealed it may Reforzar instead (face up at the bottom of the life stack). A utility or structure may be
     * played without paying its cost, otherwise it goes to the hand. Letalidad N repeats the damage.
     *
     * @return false while it waits for the defender's choice
     */
    static boolean directDamage(GameRoom room, Combat c, PlayerState owner, PlayerState defender, CardSlot attacker) {
        if (c.getHitsLeft() == null) {
            Integer lethality = keywordLevel(attacker, "Letalidad");
            c.setHitsLeft(1 + (lethality == null ? 0 : lethality));
            // "la próxima vez que un aliado inflinja daño directo por combate este turno, inflinge daño 1 vez más"
            if (room.getUsedThisTurn().remove(EXTRA_HIT + owner.getUserId())) {
                c.setHitsLeft(c.getHitsLeft() + 1);
                room.getUsedThisTurn().add(END_TURN_REQUESTED + owner.getUserId());
                log(room, name(attacker) + " inflige daño 1 vez más: después termina el turno de " + owner.getUsername(), null);
            }
        }
        int damage = strength(attacker);
        if (offerDamageReplacement(room, c, defender, damage)) return false;
        while (c.getHitsLeft() > 0 && !defender.getLifeStack().isEmpty()) {
            c.setHitsLeft(c.getHitsLeft() - 1);
            CardSlot life = defender.getLifeStack().remove(0);
            boolean revealedNow = life.isFaceDown();
            life.setFaceDown(false);
            boolean creature = "creature".equals(life.getCardType());
            boolean tribute = creature && (life.getStrength() == null || CardEffects.lifeStrength(room, defender.getUserId(), life) <= damage);
            (tribute ? defender.getTributeZone() : defender.getHand()).add(life);
            record(room, GameEvent.LIFE_DAMAGE, owner.getUserId(), life, "lifeStack", tribute ? "tributeZone" : "hand", null,
                    Map.of("damage", damage, "attackerId", attacker.getInstanceId(), "defenderId", defender.getUserId()),
                    name(attacker) + " inflige " + damage + " de daño directo: " + defender.getUsername() + " pierde la vida "
                            + name(life) + (tribute ? ", que va a sus tributos" : ", que va a su mano"));
            if (creature && !tribute && revealedNow) {
                lifePrompt(room, defender, life, PURPOSE_REINFORCE, "Reforzar", name(life)
                        + " tiene más fuerza que el daño y va a tu mano. ¿Refuerzas con ella? Se coloca bocarriba al fondo de tu pila de vidas.", "Reforzar");
                return false;
            }
            if ("utility".equals(life.getCardType()) || "structure".equals(life.getCardType())) {
                lifePrompt(room, defender, life, PURPOSE_LIFE_PLAY, "Vida revelada",
                        "¿Juegas " + name(life) + " sin pagar su coste? Si no, queda en tu mano.", "Jugar");
                return false;
            }
            // The revealed life is fully resolved (its triggers too) before the next hit (Letalidad)
            if (c.getHitsLeft() > 0 && !defender.getLifeStack().isEmpty()) return false;
        }
        if (defender.getLifeStack().isEmpty()) loseByLives(room, defender);
        return true;
    }

    /**
     * Comandante Veterano: "Turno Oponente: una vez por turno, cuando un aliado sea declarado bloqueador: en su lugar, puedes
     * reactivarlo y elegir otro aliado para que sea declarado bloqueador". Offered to the defender after a block.
     *
     * @return true when it waits for the defender's answer
     */
    static boolean offerBlockerSwap(GameRoom room, PlayerState defender, CardSlot blocker) {
        CardSlot holder = defender.getField().stream().filter(c -> !c.isFaceDown() && CardEffects.swapsBlocker(c)).findFirst().orElse(null);
        if (holder == null || room.getUsedThisTurn().contains(defender.getUserId() + "|blocker-swap")) return false;
        Combat combat = room.getCombat();
        List<CardSlot> others = blockers(defender).stream()
                .filter(b -> b != blocker && (combat == null || !b.getInstanceId().equals(combat.getTargetId()))).toList();
        if (others.isEmpty()) return false;
        rulesPrompt(room, defender.getUserId(), PURPOSE_BLOCK_SWAP, name(holder) + " · Turno Oponente",
                "En lugar de " + name(blocker) + ", puedes reactivarlo y elegir otro aliado para que bloquee. O ninguno.", "Bloquea", others,
                defender.getField());
        room.getPrompt().setSubjectId(blocker.getInstanceId());
        room.getPrompt().setMin(0);
        return true;
    }

    static void swapBlocker(GameRoom room, PlayerState defender, Prompt prompt, List<String> picks) {
        Combat c = room.getCombat();
        CardSlot first = find(defender.getField(), prompt.getSubjectId());
        CardSlot other = picks.isEmpty() ? null : find(defender.getField(), picks.get(0));
        if (c == null || first == null || other == null) return;
        room.getUsedThisTurn().add(defender.getUserId() + "|blocker-swap");
        first.setTapped(false);
        other.setTapped(true);
        c.setBlockerId(other.getInstanceId());
        record(room, GameEvent.UNTAP, defender.getUserId(), first, null, null, null, null, defender.getUsername() + " reactiva a " + name(first));
        record(room, GameEvent.BLOCK, defender.getUserId(), other, null, null, null, Map.of("forced", false),
                name(other) + " bloquea el ataque en lugar de " + name(first));
    }

    /**
     * Mercenario Valeroso: "En Mano: una vez por turno entre copias, cuando fueras a recibir 8 o menos de daño directo por ataque:
     * en su lugar, puedes invocar esta carta agotada en tu línea frontal reduciendo su coste en 1". Offered before the first hit.
     *
     * @return true when it waits for the defender's answer
     */
    static boolean offerDamageReplacement(GameRoom room, Combat c, PlayerState defender, int damage) {
        if (c.isDamageReplaceAsked()) return false;
        c.setDamageReplaceAsked(true);
        List<CardSlot> options = defender.getHand().stream().filter(card -> {
            CardEffects.DamageReplacement r = CardEffects.damageReplacement(card);
            return r != null && damage <= r.maxDamage() && card.getCost() != null
                    && !room.getUsedThisTurn().contains(defender.getUserId() + "|damage-replace|" + card.getCardName())
                    && canPay(room, defender.getUserId(), Math.max(0, card.getCost() - r.reduce()));
        }).toList();
        if (options.isEmpty()) return false;
        rulesPrompt(room, defender.getUserId(), PURPOSE_DAMAGE_REPLACE, "Daño directo: " + damage,
                "En lugar de recibir el daño, puedes invocar 1 de estas cartas agotada en tu línea frontal reduciendo su coste. O ninguna.",
                "Invocar", options, options);
        room.getPrompt().setMin(0);
        return true;
    }

    static void replaceDamage(GameRoom room, PlayerState defender, List<String> picks) {
        Combat c = room.getCombat();
        CardSlot card = picks.isEmpty() ? null : find(defender.getHand(), picks.get(0));
        CardEffects.DamageReplacement r = card == null ? null : CardEffects.damageReplacement(card);
        if (c == null || r == null) return;
        room.getUsedThisTurn().add(defender.getUserId() + "|damage-replace|" + card.getCardName());
        PendingEffect effect = new PendingEffect();
        effect.setId(room.nextId("eff"));
        effect.setControllerId(defender.getUserId());
        effect.setSourceInstanceId(card.getInstanceId());
        effect.setSourceName(name(card));
        summonByEffect(room, effect, defender, card, "hand", r.reduce(), true);
        card.setTapped(true);
        c.setHitsLeft(0);
        log(room, name(card) + " entra en lugar del daño directo: " + defender.getUsername() + " no recibe daño", null);
    }

    static void lifePrompt(GameRoom room, PlayerState defender, CardSlot life, String purpose, String title,
                                   String instruction, String label) {
        rulesPrompt(room, defender.getUserId(), purpose, title, instruction, label, List.of(life), List.of(life));
        room.getPrompt().setKind(Prompt.Kind.CONFIRM);
        room.getPrompt().setSubjectId(life.getInstanceId());
    }

    /** Rule 10.1.5: the end of the combat fires "Al Final Del Ataque", resolved outside the combat. */
    static void endCombat(GameRoom room) {
        Combat c = room.getCombat();
        room.setCombat(null);
        clearUntilEndOfAttack(room, c);
        PlayerState owner = room.getPlayer(c.getAttackerOwnerId());
        CardSlot attacker = owner == null ? null : findAnywhere(room, owner.getUserId(), c.getAttackerId());
        if (owner != null) record(room, GameEvent.ATTACK_ENDED, owner.getUserId(), attacker, null, null, null,
                Map.of("enemyDestroyed", c.isEnemyDestroyed()), "Termina el combate");
    }

    /** "hasta el final del ataque" ends with the combat. */
    static void clearUntilEndOfAttack(GameRoom room, Combat c) {
        for (String entry : c.getUntilEndOfAttack()) {
            String[] parts = entry.split("\\|", 2);
            for (PlayerState p : room.getPlayers().values()) {
                CardSlot card = find(p.getField(), parts[0]);
                if (card == null) continue;
                if (parts[1].startsWith(STRENGTH_ENTRY)) {
                    int amount = Integer.parseInt(parts[1].substring(STRENGTH_ENTRY.length()));
                    card.setTurnStrength(card.getTurnStrength() - amount);
                    card.getFloating().removeIf(f -> "attack".equals(f.until()) && f.keyword() == null && f.strength() == amount);
                    continue;
                }
                card.getTurnKeywords().remove(parts[1]);
                card.getFloating().removeIf(f -> "attack".equals(f.until()) && parts[1].equals(f.keyword()));
            }
        }
        c.getUntilEndOfAttack().clear();
    }

    /** Enfrentar: "puede atacar enemigos activos"; Enfrentar N, only those of level N or less. */
    public static boolean challenges(CardSlot attacker, CardSlot target) {
        Integer level = attacker == null ? null : keywordLevel(attacker, "Enfrentar");
        return level != null && (level == 0 || (target.getLevel() != null && target.getLevel() <= level));
    }

    /** Enemy creatures that can be attacked (rule 10.0.0.1): exhausted ones, or active ones the attacker can face (Enfrentar). */
    static List<CardSlot> attackTargets(GameRoom room, String userId, CardSlot attacker) {
        PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
        if (opponent == null) return List.of();
        // Rule 4.0.6.2: the Retaguardia cannot be attacked
        return opponent.getField().stream()
                .filter(c -> "creature".equals(c.getCardType()) && inFrontLine(c) && (c.isTapped() || challenges(attacker, c))).toList();
    }
}
