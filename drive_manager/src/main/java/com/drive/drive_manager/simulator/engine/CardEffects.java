package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.engine.CostModifiers.CostModifier;
import com.drive.drive_manager.simulator.engine.EffectScript.Limit;
import com.drive.drive_manager.simulator.engine.EffectScript.Moment;
import static com.drive.drive_manager.simulator.engine.Compose.*;

import com.drive.drive_manager.simulator.engine.EffectStep.*;

import static com.drive.drive_manager.simulator.engine.Condition.*;

import java.util.*;

/**
 * Effects implemented by the engine, by card name (reprints keep their effects), plus the keyword abilities
 * it knows. Cards not listed here are still played by hand on the board.
 */
public final class CardEffects {

    private static final String TODOS = "Todos Los Turnos";

    private static final Map<String, List<EffectScript>> BY_NAME = new HashMap<>();
    /** Inherited effects ("heredables"): what a card gives the creature it is a Recurso of (rule 11.4.0.3). */
    private static final Map<String, List<EffectScript>> INHERITED = new HashMap<>();
    private static final Map<String, List<Continuous>> CONTINUOUS = new HashMap<>();

    /**
     * Keyword abilities with an effect the engine runs ("Celeridad"). Others (Provocar, Letalidad…) are combat rules; Asalto modifies the
     * summon itself (Summons.offerAssault).
     */
    private static final Map<String, EffectScript> KEYWORDS = Map.of(
            "celeridad", new EffectScript("kw:celeridad", Moment.ON_SUMMON, false, "Celeridad",
                    "Puede subir a tu línea frontal.", List.of(moveSelfToFront()))
                    .when(not(Condition.inFrontLine())),
            "agrupar 1", new EffectScript("kw:agrupar", Moment.ON_ATTACK, false, "Agrupar 1",
                    "Si esta carta declara ataque: puedes agotar 1 aliado para que esta carta, durante el ataque, gane su fuerza e inflija daño 1 vez "
                            + "extra en caso de hacerlo.", List.of(new GroupUp(1))));

    static void register(String name, EffectScript... scripts) {
        BY_NAME.put(name, List.of(scripts));
    }

    public static List<EffectScript> of(String cardName) {
        if (cardName == null) return List.of();
        return BY_NAME.getOrDefault(cardName.trim().toLowerCase(Locale.ROOT), List.of());
    }

    /**
     * A continuous effect: while its condition holds, the cards it affects have that strength and those abilities. It is checked again
     * after every event, so it stops applying as soon as the condition does.
     *
     * @param condition  when it applies; null: always
     * @param zone       where the card has to be for it to apply ("field", or "lifeStack": "mientras esté en tus vidas")
     * @param levelBonus levels it gains while it applies ("gana 1 nivel por cada copia"), or null
     * @param targets    the cards it affects; null: this card
     * @param enemies    {@code targets} are the opponent's ("los enemigos de fuerza 3 o menos no pueden declarar ataque")
     */
    public record Continuous(String label, String text, boolean allyTurn, Condition condition, int strength, List<String> keywords,
                             Amount bonus, String zone, Amount levelBonus, CardFilter targets, boolean enemies) {
        public Continuous(String label, String text, boolean allyTurn, Condition condition, int strength, List<String> keywords,
                          Amount bonus, String zone, Amount levelBonus) {
            this(label, text, allyTurn, condition, strength, keywords, bonus, zone, levelBonus, null, false);
        }

        public Continuous(String label, String text, boolean allyTurn, Condition condition, int strength, List<String> keywords,
                          Amount bonus, String zone) {
            this(label, text, allyTurn, condition, strength, keywords, bonus, zone, null);
        }

        public Continuous(String label, String text, boolean allyTurn, Condition condition, int strength, List<String> keywords,
                          Amount bonus) {
            this(label, text, allyTurn, condition, strength, keywords, bonus, "field");
        }

        public Continuous(String label, String text, boolean allyTurn, Condition condition, int strength, List<String> keywords) {
            this(label, text, allyTurn, condition, strength, keywords, null, "field");
        }

        /** It applies to its holder now: the zone, the turn and the condition. */
        boolean holds(GameRoom room, CardSlot holder, String controller) {
            if (allyTurn && !controller.equals(room.getCurrentTurnUserId())) return false;
            return condition == null || condition.test(room, null, holder, controller);
        }
    }

    /** The name of the card that gives an inherited effect (its id), or null for a card's own effect. */
    public static String inheritedFrom(String scriptId) {
        for (Map.Entry<String, List<EffectScript>> e : INHERITED.entrySet()) {
            if (e.getValue().stream().anyMatch(s -> s.id().equals(scriptId))) return e.getKey();
        }
        return null;
    }

    /** Loads the cards (their static registrations), for registries filled from here such as {@link CostModifiers}. */
    static void load() {
    }

    /** Everything programmed, card by card (by name in lower case), as data: see {@link EffectJson}. */
    public static Map<String, CardDefinition> definitions() {
        Set<String> names = new TreeSet<>();
        for (Map<String, ?> registry : List.of(BY_NAME, INHERITED, CONTINUOUS, REPLACEMENTS, INHERITED_REPLACEMENTS)) {
            names.addAll(registry.keySet());
        }
        names.addAll(CostModifiers.names());
        names.addAll(TRAITS.keySet());
        names.addAll(REACTIONS.keySet());
        Map<String, CardDefinition> all = new LinkedHashMap<>();
        for (String name : names) {
            all.put(name, new CardDefinition(name, BY_NAME.get(name), INHERITED.get(name), CONTINUOUS.get(name),
                    reactionsOf(name), INHERITED_REPLACEMENTS.containsKey(name) ? List.copyOf(INHERITED_REPLACEMENTS.get(name)) : null, TRAITS.get(name)));
        }
        return all;
    }

    /**
     * Card effects the engine runs as a rule of its own rather than a script, by card name in lower case: "puede
     * considerarse como si estuviera en tu mano" ({@link #SPECIAL_FROM_TRIBUTES}) and "puede ser tratada como"
     * ({@link #ALIASES}). With {@link #definitions()}, everything programmed for a card.
     */
    public static Map<String, List<String>> ruleEffects() {
        Map<String, List<String>> all = new TreeMap<>();
        TRAITS.forEach((n, t) -> {
            List<String> rules = all.computeIfAbsent(n, k -> new ArrayList<>());
            if (t.specialFromTributes()) rules.add("En Tributos: cuenta como en la mano para invocaciones especiales");
            if (t.oneEffectPerTurn()) rules.add("solo 1 de sus efectos por turno");
            if (t.alias() != null) rules.add("puede ser tratada como \"" + t.alias() + "\"");
            if (t.lifePlayCost() != null) rules.add("desde las vidas se juega pagando " + t.lifePlayCost() + " gemas");
            if (t.alternativeLevel() != null) rules.add("puede ser considerada nivel " + t.alternativeLevel().level());
            if (t.creatureForEffects() != null) rules.add("cuenta como criatura de fuerza " + t.creatureForEffects() + " para efectos aliados");
            if (t.sacrificeToSummon() != null) rules.add("se invoca sacrificando un aliado");
            if (t.fieldPiecesDiscount() != null) rules.add("cuesta " + t.fieldPiecesDiscount().reduce() + " menos usando " + t.fieldPiecesDiscount().pieces() + " criaturas de tu campo");
        });
        REACTIONS.forEach((n, reactions) -> {
            List<String> rules = all.computeIfAbsent(n, k -> new ArrayList<>());
            for (Reaction r : reactions) {
                if (r instanceof Reaction.SwapsBlocker) rules.add("cambia al bloqueador declarado por otro aliado");
                if (r instanceof Reaction.AttacksBeforeAscended) rules.add("declara ataque antes de ser ascendida");
                if (r instanceof Reaction.GainBeforeAscended g) rules.add("gana " + g.keyword() + " antes de ser ascendida");
                if (r instanceof AllyRemovalSummon) rules.add("se invoca especialmente en lugar de que un aliado sea removido");
                if (r instanceof AllyGuard) rules.add("evita que sus aliados sean removidos por efecto oponente");
                if (r instanceof Reaction.TributesInsteadOfDamage) rules.add("se tributa con 2 [retador] en lugar de recibir daño");
                if (r instanceof DamageReplacement d) rules.add("se invoca en lugar de recibir " + d.maxDamage() + " o menos de daño");
            }
        });
        return all;
    }

    /** Keyword abilities the engine applies (combat rules, {@link #KEYWORDS}, Asalto); the rest are played by hand. */
    public static final Set<String> ENGINE_KEYWORDS = Set.of("Provocar", "Enfrentar", "Celeridad", "Asalto", "Furia", "Reactivar",
            "Letalidad", "Imbloqueable", "Perforante", "Alcance", "Protección", "Agrupar");

    /**
     * What of a card's metadata the engine does not run yet (as the vault's status counts it: sync_vault_status.py),
     * or null when everything is programmed. {@code own}/{@code inherited}: its effects in the metadata.
     */
    public static String pending(String cardName, int own, int inherited, List<String> keywords) {
        String name = cardName == null ? "" : cardName.trim().toLowerCase(Locale.ROOT);
        int ownDone = BY_NAME.getOrDefault(name, List.of()).size() + CONTINUOUS.getOrDefault(name, List.of()).size() + CostModifiers.of(name).size()
                + REPLACEMENTS.getOrDefault(name, List.of()).size() + ruleEffects().getOrDefault(name, List.of()).size();
        int inheritedDone = INHERITED.getOrDefault(name, List.of()).size() + INHERITED_REPLACEMENTS.getOrDefault(name, List.of()).size();
        List<String> pending = new ArrayList<>();
        if (own > ownDone) pending.add((own - ownDone) + " de " + own + " efectos propios");
        if (inherited > inheritedDone) pending.add((inherited - inheritedDone) + " de " + inherited + " heredados");
        List<String> manual = keywords.stream().map(k -> k.replaceAll("\\s*\\d+$", "").trim())
                .filter(k -> !k.isEmpty() && !ENGINE_KEYWORDS.contains(k)).distinct().toList();
        if (!manual.isEmpty()) pending.add(String.join(", ", manual));
        return pending.isEmpty() ? null : String.join("; ", pending);
    }

    /** What a card gives the creature it is a Recurso of. */
    public static List<EffectScript> inheritedOf(String name) {
        return name == null ? List.of() : INHERITED.getOrDefault(name.trim().toLowerCase(Locale.ROOT), List.of());
    }

    /** A creature's strength while it is a life: its own and what its continuous effects of the lives give it. */
    static int lifeStrength(GameRoom room, String owner, CardSlot life) {
        int strength = life.getStrength() == null ? 0 : life.getStrength();
        for (Continuous c : continuousOf(life)) {
            if ("lifeStack".equals(c.zone()) && c.condition().test(room, null, life, owner)) strength += c.strength();
        }
        // Helena: "reduce … las de las vidas oponentes en N, hasta el final del turno"
        String reduced = EffectEngine.LIFE_REDUCED + life.getInstanceId() + "|";
        for (String k : room.getUsedThisTurn()) if (k.startsWith(reduced)) strength -= Integer.parseInt(k.substring(reduced.length()));
        return strength;
    }

    /** "En Tributos: esta carta puede considerarse como si estuviera en tu mano para invocaciones especiales." */
    public static boolean specialFromTributes(CardSlot card) {
        return traits(card).specialFromTributes();
    }

    private static final java.util.regex.Pattern AGRUPAR = java.util.regex.Pattern.compile("agrupar\\s*(\\d+)");

    /** "Agrupar N": up to N allies, Letalidad 1 for each (designer ruling 2026-10-31). Agrupar 1 is the one in KEYWORDS. */
    static EffectScript agrupar(int allies) {
        if (allies == 1) return KEYWORDS.get("agrupar 1");
        return new EffectScript("kw:agrupar-" + allies, Moment.ON_ATTACK, false, "Agrupar " + allies,
                "Si esta carta declara ataque: puedes agotar hasta " + allies + " aliados para que esta carta, durante el ataque, gane sus fuerzas e "
                        + "inflija daño 1 vez extra por cada uno.", List.of(new GroupUp(allies)));
    }

    /** The card's own continuous effects. */
    public static List<Continuous> continuousOf(CardSlot card) {
        if (card.getCardName() == null) return List.of();
        return CONTINUOUS.getOrDefault(card.getCardName().trim().toLowerCase(Locale.ROOT), List.of());
    }

    /** The card's own effects, those its Recursos give it and those of the keywords the engine runs. */
    public static List<EffectScript> of(CardSlot card) {
        List<EffectScript> all = new ArrayList<>(of(card.getCardName()));
        for (CardSlot resource : card.getResources()) {
            if (resource.getCardName() != null) all.addAll(INHERITED.getOrDefault(resource.getCardName().trim().toLowerCase(Locale.ROOT), List.of()));
        }
        for (String keyword : EffectEngine.keywords(card)) {
            EffectScript script = KEYWORDS.get(keyword.toLowerCase(Locale.ROOT));
            // Agrupar N: up to N allies
            java.util.regex.Matcher groupUp = AGRUPAR.matcher(keyword.toLowerCase(Locale.ROOT));
            if (script == null && groupUp.matches()) script = agrupar(Integer.parseInt(groupUp.group(1)));
            if (script != null) all.add(script);
        }
        return all;
    }

    /** Looks an effect up by its id (card effects and keywords). */
    public static Optional<EffectScript> byId(String id) {
        return BY_NAME.values().stream().flatMap(List::stream).filter(s -> s.id().equals(id)).findFirst()
                .or(() -> INHERITED.values().stream().flatMap(List::stream).filter(s -> s.id().equals(id)).findFirst())
                .or(() -> KEYWORDS.values().stream().filter(s -> s.id().equals(id)).findFirst())
                .or(() -> id.startsWith("kw:agrupar-") ? Optional.of(agrupar(Integer.parseInt(id.substring("kw:agrupar-".length())))) : Optional.empty());
    }

    /** Cost to play a utility or structure, after its cost modifiers (they ask for nothing to be paid). */
    public static int playCost(GameRoom room, String userId, CardSlot card) {
        int cost = card.getCost() == null ? 0 : card.getCost();
        return Math.max(0, cost - CostModifiers.available(room, room.getPlayer(userId), CostModifiers.Event.PLAY, null, card, null));
    }

    // ------------------------------------------------------------------ Replacements ("en su lugar")

    /** What a replacement costs instead of the removal. */
    public enum ReplacementCost {
        /** "puedes tributar 2 materiales de esta carta". */
        TRIBUTE_TWO_MATERIALS,
        /** "puedes tributar 1 carta de tu mano" (the owner picks it). */
        TRIBUTE_ONE_FROM_HAND,
        /** "puedes tributar todos los recursos [filtro] de esta carta" (resourceFilter; any: all of them). */
        TRIBUTE_RESOURCES,
        /** "puedes colocar hasta 2 criaturas [filtro] de los materiales de esta carta en tu retaguardia" (resourceFilter: which ones). */
        MATERIALS_TO_BACK,
        /** "puede tributar los recursos de esta carta" before it is removed (a reaction: it is still removed; they do not go to the Descarte). */
        RESOURCES_TO_TRIBUTES_BEFORE,
        /** "en su lugar, puede invocar especialmente esta carta": a regular special summon of another card with the ally as summoner. */
        ALLY_SPECIAL_SUMMON,
        /** "en su lugar, puedes tributar 1 material de esta carta y sacrificar 1 [filtro] bajo tu control" (resourceFilter: what is sacrificed). */
        GUARD_MATERIAL_AND_SACRIFICE,
        /** "puedes tributar 1 de sus recursos para añadir otro de ellos a tu mano" before it is removed (a reaction: it is still removed). */
        RESOURCE_TRIBUTED_ANOTHER_TO_HAND,
        /**
         * "coloca el recurso de este efecto en tu retaguardia con todos los recursos [filtro] de esta carta como sus recursos" (mandatory: done by
         * the rules before the removal, Removals.reactionsBeforeRemoval; never offered as a choice).
         */
        RESOURCE_TO_BACK_WITH_OTHERS
    }

    /**
     * "cuando esta carta fuera a ser destruida / removida [por el oponente]: en su lugar, puedes …".
     *
     * @param destroyOnly only when it would be destroyed ("removida" also covers being sent to the hand)
     */
    public record Replacement(String id, String label, String text, boolean destroyOnly, boolean byOpponentOnly, Limit limit, ReplacementCost cost,
                              CardFilter resourceFilter) implements Reaction {
        public Replacement(String id, String label, String text, boolean destroyOnly, boolean byOpponentOnly, Limit limit, ReplacementCost cost) {
            this(id, label, text, destroyOnly, byOpponentOnly, limit, cost, CardFilter.any());
        }

        /** "en su lugar": the removal does not happen. A reaction (MATERIALS_TO_BACK, RESOURCES_TO_TRIBUTES_BEFORE) happens before it, and the removal goes on. */
        public boolean instead() {
            return cost != ReplacementCost.MATERIALS_TO_BACK && cost != ReplacementCost.RESOURCES_TO_TRIBUTES_BEFORE
                    && cost != ReplacementCost.RESOURCE_TRIBUTED_ANOTHER_TO_HAND && cost != ReplacementCost.RESOURCE_TO_BACK_WITH_OTHERS;
        }
    }

    private static final Map<String, List<Replacement>> REPLACEMENTS = new HashMap<>();

    /** Replacements a card gives as a Recurso (inherited). */
    private static final Map<String, List<Replacement>> INHERITED_REPLACEMENTS = new HashMap<>();

    /**
     * "cuando algún aliado [filtro] fuera a ser removido: en su lugar, puede invocar especialmente esta carta [reduciendo en N su coste
     * especial]", from the card's zone (the hand, the Tributos). Designer ruling 2026-10-31: it is a regular special summon, paying its cost and
     * with everything its Requerimiento asks; the ally that would be removed is its summoner (Removals.replacementOptions).
     */
    /** What cards change in the rules themselves, by name: {@link CardTraits}. */
    private static final Map<String, CardTraits> TRAITS = new HashMap<>();

    /** What cards do before an event, other than cost changes and replacements of a removal: {@link Reaction}. */
    private static final Map<String, List<Reaction>> REACTIONS = new HashMap<>();

    /** Everything the card reacts with. */
    private static List<Reaction> reactionsOf(String name) {
        List<Reaction> all = new ArrayList<>(CostModifiers.of(name));
        all.addAll(REPLACEMENTS.getOrDefault(name, List.of()));
        all.addAll(REACTIONS.getOrDefault(name, List.of()));
        return all;
    }

    /** The card's reaction of that kind, or null. */
    private static <T extends Reaction> T reaction(CardSlot card, Class<T> kind) {
        if (card.getCardName() == null) return null;
        return REACTIONS.getOrDefault(card.getCardName().trim().toLowerCase(Locale.ROOT), List.of()).stream()
                .filter(kind::isInstance).map(kind::cast).findFirst().orElse(null);
    }

    private static CardTraits traits(CardSlot card) {
        return card.getCardName() == null ? CardTraits.NONE : TRAITS.getOrDefault(card.getCardName().trim().toLowerCase(Locale.ROOT), CardTraits.NONE);
    }

    public record AllyRemovalSummon(String zone, CardFilter ally, int reduce, Replacement replacement) implements Reaction {}

    /**
     * "cuando uno o más aliados [filtro] fueran a ser removidos por efecto oponente: en su lugar, puedes …" held by a card on the field (itself
     * included). Designer ruling 2026-10-31: paid once, it saves every one of them that this effect was removing.
     */
    public record AllyGuard(CardFilter allies, Replacement replacement) implements Reaction {}

    static AllyGuard allyGuard(CardSlot card) {
        return reaction(card, AllyGuard.class);
    }

    /**
     * "En Mano: cuando fueras a recibir daño: en su lugar, puedes tributar esta carta más 2 cartas [retador] de tu mano, todas de distinto nivel"
     * (designer ruling 2026-10-31: damage of an attack or of an effect; one hit at a time, and Letalidad's next hits do not come after a
     * prevented one). CombatRules.offerDamageTribute.
     */
    static boolean tributesInsteadOfDamage(CardSlot card) {
        return reaction(card, Reaction.TributesInsteadOfDamage.class) != null;
    }

    /** "esta carta puede ser considerada nivel N por tus cartas [filtro]" (designer ruling 2026-10-31: for everything, special summons and effects). */
    public record AlternativeLevel(int level, CardFilter by) {}

    /** The other level {@code card} can count as when {@code looker} reads it, or null. */
    static Integer alternativeLevel(CardSlot card, CardSlot looker) {
        AlternativeLevel alt = looker == null ? null : traits(card).alternativeLevel();
        return alt != null && alt.by().matches(looker) ? alt.level() : null;
    }

    /** "si utilizas al menos N criaturas en tu campo: reduce el coste especial de esta carta en M" (SpecialRequirements.fieldPiecesDiscount). */
    public record FieldPiecesDiscount(int pieces, int reduce) {}

    static FieldPiecesDiscount fieldPiecesDiscount(CardSlot card) {
        return traits(card).fieldPiecesDiscount();
    }

    /**
     * "esta carta puede ser considerada como una criatura de fuerza N por efectos aliados, pero no puede ser utilizada para invocaciones
     * especiales" (Fósil): the strength it has for the effects that look at it (CardFilter), never for a special summon.
     */
    static Integer creatureStrengthForEffects(CardSlot card) {
        return traits(card).creatureForEffects();
    }

    /**
     * "Cuando fueras a invocar esta carta: puedes sacrificar 1 aliado [filtro] activo que no sea una copia de esta carta para invocar esta
     * carta en tu línea frontal y reducir su coste en el coste del aliado sacrificado" (Carnirex; Summons.normalSummon).
     */
    static CardFilter sacrificeToSummon(CardSlot card) {
        return traits(card).sacrificeToSummon();
    }

    static AllyRemovalSummon allyRemovalSummon(CardSlot card) {
        return reaction(card, AllyRemovalSummon.class);
    }

    /** The card's own replacements and those its Recursos give it. */
    public static List<Replacement> replacementsOf(CardSlot card) {
        List<Replacement> all = new ArrayList<>();
        if (card.getCardName() != null) all.addAll(REPLACEMENTS.getOrDefault(card.getCardName().trim().toLowerCase(Locale.ROOT), List.of()));
        for (CardSlot resource : card.getResources()) {
            if (resource.getCardName() != null)
                all.addAll(INHERITED_REPLACEMENTS.getOrDefault(resource.getCardName().trim().toLowerCase(Locale.ROOT), List.of()));
        }
        return all;
    }

    /** The Recurso of the card that gives it that replacement (inherited), or null when it is the card's own. */
    public static CardSlot replacementGiver(CardSlot card, Replacement replacement) {
        for (CardSlot resource : card.getResources()) {
            if (resource.getCardName() != null
                    && INHERITED_REPLACEMENTS.getOrDefault(resource.getCardName().trim().toLowerCase(Locale.ROOT), List.of()).contains(replacement)) return resource;
        }
        return null;
    }

    /** "En Mano: al entrar al paso de bloqueo: puedes jugar esta carta pagando su coste." */
    /** "cuando esta carta fuera a ser ascendida: puede declarar ataque" (SpecialSummons.handSpecial, then completeAscension). */
    static boolean attacksBeforeAscended(CardSlot card) {
        return reaction(card, Reaction.AttacksBeforeAscended.class) != null;
    }

    /** "Para jugar esta carta desde tus vidas debes pagar N gemas" (an absolute effect: CombatRules.directDamage offers it only then). */
    static int lifePlayCost(CardSlot card) {
        Integer cost = traits(card).lifePlayCost();
        return cost == null ? 0 : cost;
    }

    /** "cuando esta carta fuera a ser ascendida: esta carta gana [habilidad] hasta el final del turno" (Turno Aliado; Summons.specialSummon). */
    static String gainBeforeAscended(CardSlot card) {
        Reaction.GainBeforeAscended gain = reaction(card, Reaction.GainBeforeAscended.class);
        return gain == null ? null : gain.keyword();
    }

    /** "cuando un aliado sea declarado bloqueador: en su lugar, puedes reactivarlo y elegir otro aliado …" (CombatRules.offerBlockerSwap). */
    static boolean swapsBlocker(CardSlot card) {
        return reaction(card, Reaction.SwapsBlocker.class) != null;
    }

    /** "cuando fueras a recibir N o menos de daño directo por ataque: en su lugar, puedes invocar esta carta … reduciendo su coste en M". */
    public record DamageReplacement(int maxDamage, int reduce) implements Reaction {}

    static DamageReplacement damageReplacement(CardSlot card) {
        return reaction(card, DamageReplacement.class);
    }

    /** "Esta carta puede ser tratada como «X»": the name it also answers to. */
    /** The card is called {@code name}, or can be treated as such. */
    static boolean isNamed(CardSlot card, String name) {
        if (card.getCardName() == null || name == null) return false;
        String own = card.getCardName().trim().toLowerCase(Locale.ROOT);
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        return own.equals(wanted) || wanted.equals(traits(card).alias());
    }

    /** What the files say of a card, to the registries. */
    private static void install(CardDefinition d) {
        if (!d.effects().isEmpty()) BY_NAME.put(d.name(), d.effects());
        if (!d.inherited().isEmpty()) INHERITED.put(d.name(), d.inherited());
        if (!d.continuous().isEmpty()) CONTINUOUS.put(d.name(), d.continuous());
        if (d.traits() != null) TRAITS.put(d.name(), d.traits());
        List<Reaction> others = new ArrayList<>();
        List<Replacement> replacements = new ArrayList<>();
        for (Reaction r : d.reactions()) {
            if (r instanceof CostModifier m) CostModifiers.register(d.name(), m);
            else if (r instanceof Replacement p) replacements.add(p);
            else others.add(r);
        }
        if (!replacements.isEmpty()) REPLACEMENTS.put(d.name(), replacements);
        if (!others.isEmpty()) REACTIONS.put(d.name(), others);
        List<Replacement> inherited = d.inheritedReactions().stream().map(Replacement.class::cast).toList();
        if (!inherited.isEmpty()) INHERITED_REPLACEMENTS.put(d.name(), inherited);
    }

    static {
        CardFiles.load().forEach(CardEffects::install);
    }
}
