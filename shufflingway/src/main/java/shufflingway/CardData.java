package shufflingway;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable value object representing a single card in game state.
 * Carries everything needed for display and rules checks.
 */
public record CardData(
        String imageUrl,
        String name,
        String element,
        int    cost,
        int    power,
        String type,
        boolean isLb,
        int     lbCost,
        boolean exBurst,
        boolean multicard,
        Set<Trait> traits,
        int    warpValue,
        List<String> warpCost,
        String primingTarget,
        List<String> primingCost,
        List<ActionAbility>  actionAbilities,
        List<AutoAbility>   autoAbilities,
        List<FieldAbility>   fieldAbilities,
        List<IfControlBoost> ifControlBoosts,
        List<FieldPowerGrant>       fieldPowerGrants,
        List<ScalingSelfPowerBoost> scalingSelfPowerBoosts,
        List<FieldCostReduction>    fieldCostReductions,
        List<SelfCostModifier>      selfCostModifiers,
        List<FieldPrimingAnyElement> fieldPrimingAnyElements,
        List<FieldPartyAnyElement>   fieldPartyAnyElements,
        boolean warpCostAnyElement,
        boolean canFormPartyAnyElement,
        int[]   fieldCannotBeBlockedByCost,       // null = no restriction; {costVal, 1} = "or more", {costVal, 0} = "or less"
        boolean cannotBeBlockedByHigherPower,     // cannot be blocked by a Forward with greater power
        boolean cannotBlockAtAll,                 // "cardName cannot block."
        boolean cannotBlockHigherPower,           // "cardName cannot block a Forward with a power greater than its."
        boolean cannotBlockParty,                 // "cardName cannot block Forwards forming a party."
        boolean cannotAttackOrBlock,              // "cardName cannot attack or block."
        int     maxAttacksPerTurn,                // "cardName can attack twice/N times in the same turn."; 1 = no permission
        String job,
        String category1,
        String category2,
        String textEn
) {

    public Set<Trait> getTraits() {
        return traits;
    }
    public enum Trait {
        HASTE,
        BRAVE,
        FIRST_STRIKE,
        BACK_ATTACK,
        WARP,
        PRIMING,
        CANNOT_BE_BROKEN,
        CANNOT_BE_BROKEN_BY_NON_DMG,
        CANNOT_BE_DULLED_BY_OPP,
        CANNOT_BE_RETURNED_TO_HAND_BY_OPP,
        CANNOT_LEAVE_FIELD_BY_OPP,
        POWER_CANNOT_BE_DECREASED_BY_OPP;

        /**
         * Human-readable name for the game log — {@code FIRST_STRIKE} becomes {@code "First Strike"}.
         * Title-cases every underscore-separated word, so multi-word traits do not leak the
         * enum's underscores into player-facing text.
         */
        public String displayName() {
            StringBuilder sb = new StringBuilder();
            for (String word : name().split("_")) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
            }
            return sb.toString();
        }
    }

    /** Defensive copy — collection fields are always immutable after construction. */
    public CardData {
        // EnumSet, not Set.copyOf: the latter randomises iteration order per JVM run
        // (ImmutableCollections.SALT), which leaks into rendered trait lists.
        EnumSet<Trait> traitSet = EnumSet.noneOf(Trait.class);
        traitSet.addAll(traits);
        traits          = Collections.unmodifiableSet(traitSet);
        warpCost        = List.copyOf(warpCost);
        primingCost     = List.copyOf(primingCost);
        actionAbilities  = List.copyOf(actionAbilities);
        autoAbilities    = List.copyOf(autoAbilities);
        fieldAbilities   = List.copyOf(fieldAbilities);
        ifControlBoosts  = List.copyOf(ifControlBoosts);
        fieldPowerGrants       = List.copyOf(fieldPowerGrants);
        scalingSelfPowerBoosts = List.copyOf(scalingSelfPowerBoosts);
        fieldCostReductions    = List.copyOf(fieldCostReductions);
        selfCostModifiers      = List.copyOf(selfCostModifiers);
        fieldPrimingAnyElements = List.copyOf(fieldPrimingAnyElements);
        fieldPartyAnyElements   = List.copyOf(fieldPartyAnyElements);
        job       = job       != null ? job       : "";
        category1 = category1 != null ? category1 : "";
        category2 = category2 != null ? category2 : "";
        textEn    = textEn    != null ? textEn    : "";
    }

    private static final Pattern SUMMON_EX_PREFIX =
            Pattern.compile("(?i)^\\s*(?:\\[\\[ex\\]\\]|EX\\s+BURST)\\s*");
    /** Matches the full [[ex]]…[[/]] EX Burst tag anywhere in card text. */
    private static final Pattern EX_BURST_TAG =
            Pattern.compile("(?i)\\[\\[ex\\]\\].*?\\[\\[/\\]\\]\\s*", Pattern.DOTALL);
    private static final Pattern SUMMON_MARKUP =
            Pattern.compile("(?i)\\[\\[[a-z/0-9]+\\]\\]");
    private static final Pattern SUMMON_BR =
            Pattern.compile("(?i)\\[\\[br\\]\\]");

    /**
     * Matches the alternate summon cost prefix (may appear after a traits [[br]]):
     * "Before paying the cost to cast X, you can pay 《costs》 to reduce the cost … by N."
     * Group {@code costs} captures all 《…》 tokens; group {@code reduction} captures N.
     */
    private static final Pattern ALT_COST_SUMMON = Pattern.compile(
        "(?i)Before\\s+paying\\s+the\\s+cost\\s+to\\s+cast\\s+[^,]+,\\s+" +
        "you\\s+can\\s+pay\\s+(?<costs>(?:《[^》]+》)+)\\s+" +
        "to\\s+reduce\\s+the\\s+cost\\s+required\\s+to\\s+cast\\s+\\S.*?\\s+by\\s+(?<reduction>\\d+)\\."
    );

    /**
     * Matches the alternate summon cost paid by removing a field card rather than Crystals:
     * "Before paying the cost to cast X, you can remove N [Element] Backup you control from the
     * game to reduce the cost required to cast X by M." (Ifrit 25-004H and its five element
     * counterparts.)
     *
     * <p>The card name is matched with a reluctant {@code .+?} rather than {@link
     * #ALT_COST_SUMMON}'s {@code [^,]+}, because names in this family contain commas
     * ("Mateus, the Corrupt", "Famfrit, the Darkening Cloud").
     *
     * <p>Requiring the "Before paying the cost to cast" prefix and a numeric count is what keeps
     * this off the other "you can remove … from the game" costs: the Break Zone family removes
     * "… in your Break Zone" rather than "… you control", Vayne 15-088H removes "any number of"
     * with no digit, and Sonon 18-123L's is an instead-of-paying cost with no prefix.
     */
    private static final Pattern ALT_COST_SUMMON_REMOVE_FIELD = Pattern.compile(
        "(?i)Before\\s+paying\\s+the\\s+cost\\s+to\\s+cast\\s+.+?,\\s+" +
        "you\\s+can\\s+remove\\s+(?<count>\\d+)\\s+" +
        "(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+" +
        "(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control\\s+" +
        "from\\s+the\\s+game\\s+to\\s+reduce\\s+the\\s+cost\\s+required\\s+to\\s+cast\\s+.+?\\s+" +
        "by\\s+(?<reduction>\\d+)\\."
    );

    /**
     * Matches the alternate non-summon cost prefix.  Optional groups:
     * <ul>
     *   <li>{@code condition}  — "If you control …, " prefix</li>
     *   <li>{@code costs}      — one or more 《…》 CP/Crystal tokens</li>
     *   <li>{@code bzremovals} — "and remove N Elem Type … in your Break Zone …" clause</li>
     *   <li>{@code backuponly} — "You can only pay this cost with CP produced by Backups" sentence</li>
     *   <li>{@code followup}   — "If/When you do so, …" trailing effect</li>
     * </ul>
     */
    private static final Pattern ALT_COST_NONSUMMON = Pattern.compile(
        "(?i)(?:If\\s+you\\s+control\\s+(?<condition>[^,]+),\\s+)?" +
        "you\\s+can\\s+pay\\s+(?<costs>(?:《[^》]+》)+)" +
        "(?:\\s+and\\s+remove\\s+(?<bzremovals>[^(]+?)\\s+(?:in|from)\\s+(?:your\\s+)?Break\\s+Zone(?:\\s+from\\s+the\\s+game)?)?" +
        "\\s+\\(instead\\s+of\\s+paying\\s+the\\s+CP\\s+cost\\)\\s+to\\s+cast\\s+\\S[^.]*\\.?" +
        "(?:\\s+(?<backuponly>You\\s+can\\s+only\\s+pay\\s+this\\s+cost\\s+with\\s+CP\\s+produced\\s+by\\s+Backups)\\.?)?" +
        "(?:\\s+(?:If|When)\\s+you\\s+do\\s+so[,.]?\\s+(?<followup>.+?))?" +
        "(?=\\s*(?:\\[\\[br\\]\\]|$))"
    );

    /**
     * Matches the alternate cast cost paid by dulling Forwards rather than CP:
     * "[During your turn,] you can dull 1 active Fire Job Class Zero Cadet Forward you control and
     * 1 active Lightning Job Class Zero Cadet Forward you control (instead of paying the CP cost)
     * to cast Nine."
     *
     * <p>Group {@code reqs} holds the whole requirement list, one clause per "and"; group
     * {@code yourturn} is present on the Summon printings (Phoenix 26-017R and its five element
     * counterparts, Moomba 27-031H), which may only take this route on their controller's turn.
     */
    private static final Pattern ALT_COST_DULL = Pattern.compile(
        "(?i)(?<yourturn>During\\s+your\\s+turn,\\s+)?" +
        "you\\s+can\\s+dull\\s+(?<reqs>\\d+\\s+active\\s+[^(]+?)\\s*" +
        "\\(instead\\s+of\\s+paying\\s+the\\s+CP\\s+cost\\)\\s+to\\s+(?:cast|play)\\s+\\S[^.]*\\.?"
    );

    /**
     * Matches the alternate cast cost paid by putting your own Characters into the Break Zone
     * rather than CP: "You can put a total of 3 Forwards or Monsters you control into the Break
     * Zone to play Kefka from your hand onto the field." — Kefka 4-080L.
     *
     * <p>Unlike its neighbours this one carries no "(instead of paying the CP cost)" marker. It
     * does not need one: "to play Kefka from your hand onto the field" is already a complete
     * description of what the payment buys, so the cost replaces the CP cost outright rather than
     * reducing it. That is why {@link #altPutToBzCost} reports no CP alongside it.
     *
     * <p>"a total of" is what makes {@code types} one pool rather than a count per type — any three
     * cards across the two rows will do. Group {@code count} is that total.
     */
    private static final Pattern ALT_COST_PUT_TO_BZ = Pattern.compile(
        "(?i)You\\s+can\\s+put\\s+a\\s+total\\s+of\\s+(?<count>\\d+)\\s+" +
        "(?<types>Forwards?(?:\\s+or\\s+Monsters?)?|Monsters?(?:\\s+or\\s+Forwards?)?|Characters?)\\s+" +
        "you\\s+control\\s+into\\s+the\\s+Break\\s+Zone\\s+to\\s+play\\s+\\S[^.]*?\\s+" +
        "from\\s+your\\s+hand\\s+onto\\s+the\\s+field[.!]?"
    );

    /**
     * An alternate cast cost paid by putting {@code count} of your own Characters into the Break
     * Zone. The three flags say which rows may supply them; "a total of" pools them, so the count
     * is across all the permitted rows rather than per row.
     */
    public record AltPutToBzCost(int count, boolean inclForwards, boolean inclMonsters,
            boolean inclBackups) {}

    /**
     * This card's put-into-Break-Zone alternate cast cost, or {@code null} when it prints none.
     *
     * <p>Reported on its own rather than through {@link #altCpElements()} because it leaves no CP
     * to pay: the sentence buys the play outright.
     */
    public AltPutToBzCost altPutToBzCost() {
        Matcher m = ALT_COST_PUT_TO_BZ.matcher(textEn);
        if (!m.find()) return null;
        String types = m.group("types").toLowerCase(Locale.ROOT);
        boolean character = types.contains("character");
        return new AltPutToBzCost(Integer.parseInt(m.group("count")),
                character || types.contains("forward"),
                character || types.contains("monster"),
                character);
    }

    /** One "N active [Element] [Category X] [Job Y] Forward(s) [you control]" clause of {@link #ALT_COST_DULL}. */
    private static final Pattern ALT_DULL_REQUIREMENT = Pattern.compile(
        "(?i)^(?<count>\\d+)\\s+active\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?:Job\\s+(?<job>[A-Za-z][A-Za-z\\s'\\-]*?)\\s+)?" +
        "Forwards?(?:\\s+you\\s+control)?$"
    );

    /** Parses one "N Element Type" requirement phrase from a BZ-removal list. */
    private static final Pattern BZ_REMOVAL_ENTRY = Pattern.compile(
        "(?i)(\\d+)\\s+(Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
        "(?:\\s+(Character|Forward|Backup|Monster))?"
    );

    /**
     * Matches the extra-cost clause "If you cast [Name], you may [cost] as an extra cost."
     * Originally written for summons but the phrasing is identical for Forward/Character
     * "enters the field" abilities (e.g. Samurai). Six variants:
     * <ul>
     *   <li>remove N [Element] cards in your Break Zone from the game as an extra cost</li>
     *   <li>remove N Forward(s) in your Break Zone from the game as an extra cost</li>
     *   <li>remove N Card Name [name] in your Break Zone from the game as an extra cost</li>
     *   <li>discard N card(s) as an extra cost</li>
     *   <li>pay 《X》 as an extra cost — variable amount, player's choice</li>
     *   <li>pay 《Element》《N》 as an extra cost — fixed CP amount, e.g. "Wind + 2 generic"</li>
     *   <li>pay an extra 《Element》《N》 — the same fixed-CP cost with the marker printed
     *       <em>before</em> the tokens instead of after (Prishe 14-128H, Ixion 17-090R,
     *       Fenrir 8-081R, Fina 8-060L)</li>
     * </ul>
     *
     * <p>Two lead-ins, because Fina 8-060L says "If you pay the cost to play Fina onto the field"
     * where the rest say "If you cast [Name]". Same declaration either way.
     *
     * Groups: {@code count}, {@code element}, {@code forward}, {@code cardname}, {@code discardcount},
     * {@code cptoks} (raw {@code 《...》《...》} token string for the fixed-CP variant) and
     * {@code cptoksinline} (the same, for the "an extra" word order).
     */
    static final Pattern EXTRA_COST_SUMMON = Pattern.compile(
        "(?i)If\\s+you\\s+(?:cast\\s+[^,]+|pay\\s+the\\s+cost\\s+to\\s+play\\s+.+?\\s+onto\\s+the\\s+field)" +
        ",\\s+you\\s+may\\s+" +
        "(?:" +
            // Marker-first spelling. Tried ahead of the branches below so the "as an extra cost"
            // tail they require is never hunted for on a sentence that has no such tail.
            "pay\\s+an\\s+extra\\s+(?<cptoksinline>(?:《[^》]+》)+)" +
        "|" +
            "(?:" +
                "remove\\s+(?<count>\\d+)\\s+" +
                "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+cards?|" +
                "(?<forward>Forwards?)|" +
                "Card\\s+Name\\s+(?<cardname>.+?)(?=\\s+in\\s+your\\s+Break))" +
                "\\s+in\\s+your\\s+Break\\s+Zone\\s+from\\s+the\\s+game" +
            "|" +
                "discard\\s+(?<discardcount>\\d+)\\s+cards?" +
            "|" +
                "pay\\s+《X》" +
            "|" +
                "pay\\s+(?<cptoks>(?:《[^》]+》)+)" +
            ")" +
            "\\s+as\\s+an\\s+extra\\s+cost" +
        ")"
    );

    /** Extracts card names from "a Card Name X" phrases in a condition string. */
    private static final Pattern CONDITION_CARD_NAME = Pattern.compile(
        "(?i)a\\s+Card\\s+Name\\s+(?<name>[^,]+?)(?=\\s+(?:or\\b|and\\b)|\\s*$)"
    );

    /** Matches a segment that contains only trait keywords (Haste, Brave, Back Attack, etc.). */
    static final Pattern TRAIT_ONLY_SEGMENT = Pattern.compile(
        "(?i)^(?:(?:Haste|Brave|First\\s+Strike|Back\\s+Attack|Warp\\s+\\d+|Priming)" +
        "(?:\\s*[,/]\\s*)?)+\\.?$"
    );

    // ── Alt-cost helpers ──────────────────────────────────────────────────────

    /**
     * Splits a cost token string (e.g. {@code "《C》《2》《Fire》"}) into CP element strings
     * (empty string = generic CP) and returns the crystal count in {@code crystalOut[0]}.
     */
    private static List<String> parseCostTokens(String costs, int[] crystalOut) {
        List<String> cpElems = new ArrayList<>();
        Matcher m = Pattern.compile("《([^》]+)》").matcher(costs);
        while (m.find()) {
            String token = m.group(1).trim();
            if (token.equalsIgnoreCase("C")) {
                crystalOut[0]++;
            } else {
                try {
                    int n = Integer.parseInt(token);
                    for (int i = 0; i < n; i++) cpElems.add(""); // generic CP
                } catch (NumberFormatException e) {
                    cpElems.add(token); // element name
                }
            }
        }
        return cpElems;
    }

    /** Returns the number of Crystals in the alternate cast cost, or 0 if none exists. */
    public int altCrystalCost() {
        int[] c = {0};
        Matcher m = ALT_COST_SUMMON.matcher(textEn);
        if (m.find()) { parseCostTokens(m.group("costs"), c); return c[0]; }
        m = ALT_COST_NONSUMMON.matcher(textEn);
        if (m.find()) { parseCostTokens(m.group("costs"), c); return c[0]; }
        return 0;
    }

    /**
     * Returns the CP elements for the alternate cast cost as a list of element strings
     * (empty string = generic CP).  For summons the list is derived from the card's own
     * element(s) and the cost reduction; for non-summons it is taken directly from the
     * cost token string.  Returns an empty list when no alternate cost exists.
     */
    public List<String> altCpElements() {
        int[] crystals = {0};
        Matcher m = ALT_COST_SUMMON.matcher(textEn);
        if (m.find()) {
            parseCostTokens(m.group("costs"), crystals);
            return reducedCastCpElements(Integer.parseInt(m.group("reduction")));
        }
        m = ALT_COST_SUMMON_REMOVE_FIELD.matcher(textEn);
        if (m.find()) return reducedCastCpElements(Integer.parseInt(m.group("reduction")));
        m = ALT_COST_NONSUMMON.matcher(textEn);
        if (m.find()) return List.copyOf(parseCostTokens(m.group("costs"), crystals));
        return List.of();
    }

    /**
     * The CP still owed after a cast cost reduction of {@code reduction}, expressed as one
     * element string per CP and drawn from the card's own elements (multi-element cards
     * alternate). Shared by the Crystal and field-removal alternate costs, which differ only in
     * what is handed over to earn the reduction.
     */
    private List<String> reducedCastCpElements(int reduction) {
        int altCp = Math.max(0, cost - reduction);
        List<String> elems = new ArrayList<>();
        String[] cardElems = elements();
        for (int i = 0; i < altCp; i++) elems.add(cardElems.length > 0 ? cardElems[i % cardElems.length] : "");
        return List.copyOf(elems);
    }

    /**
     * A field card the alternate cast cost hands over: "remove 1 Fire Backup you control from the
     * game". {@code type} is the printed card type as written ("Backup"), {@code element} the
     * required element.
     */
    public record AltFieldRemoval(int count, String element, String type) {}

    /**
     * The field cards this card's alternate cast cost removes from the game, or {@code null} when
     * its alternate cost is not of that kind. The reduction itself is reported through
     * {@link #altCpElements()}, the same way the Crystal alternate cost reports its own.
     */
    public AltFieldRemoval altFieldRemoval() {
        Matcher m = ALT_COST_SUMMON_REMOVE_FIELD.matcher(textEn);
        if (!m.find()) return null;
        return new AltFieldRemoval(Integer.parseInt(m.group("count")),
                m.group("element").trim(), m.group("type").trim());
    }

    /** Convenience: total CP to pay for the alternate cast ({@code altCpElements().size()}). */
    public int altCpCost() { return altCpElements().size(); }

    /**
     * The Forwards this card's alternate cast cost dulls, in printed order, or an empty list when
     * it has no such cost. {@link DullForwardCost} is reused rather than mirrored, so eligibility
     * and payment follow the rules already written for ability costs.
     *
     * <p>A clause that fails to parse voids the whole list: a partial reading would understate the
     * cost and let the card be cast for less than it prints.
     */
    public List<DullForwardCost> altDullCosts() {
        Matcher m = ALT_COST_DULL.matcher(textEn);
        if (!m.find()) return List.of();
        List<DullForwardCost> out = new ArrayList<>();
        for (String clause : m.group("reqs").split("(?i)\\s+and\\s+")) {
            Matcher r = ALT_DULL_REQUIREMENT.matcher(clause.trim());
            if (!r.find()) return List.of();
            String job = r.group("job");
            out.add(new DullForwardCost(Integer.parseInt(r.group("count")), "", r.group("element"),
                    null, job != null ? job.trim() : null, r.group("category"), "Forward"));
        }
        return List.copyOf(out);
    }

    /** Whether the dull alternate cost may only be taken during its controller's own turn. */
    public boolean altDullYourTurnOnly() {
        Matcher m = ALT_COST_DULL.matcher(textEn);
        return m.find() && m.group("yourturn") != null;
    }

    /**
     * Returns the condition text that must be satisfied before the alternate cost may be used
     * (e.g. {@code "a Card Name Cecil or a Card Name Rosa"}), or an empty string if there is
     * no condition.
     */
    public String altConditionText() {
        Matcher m = ALT_COST_NONSUMMON.matcher(textEn);
        if (m.find()) { String c = m.group("condition"); return c != null ? c.trim() : ""; }
        return "";
    }

    /**
     * Returns card names parsed from the alternate cost condition (e.g. {@code ["Cecil", "Rosa"]}
     * for "a Card Name Cecil or a Card Name Rosa").  Returns an empty list when there is no
     * condition.  The condition is satisfied if the player controls ANY of the listed cards.
     */
    public List<String> altConditionCardNames() {
        String cond = altConditionText();
        if (cond.isEmpty()) return List.of();
        List<String> names = new ArrayList<>();
        Matcher m = CONDITION_CARD_NAME.matcher(cond);
        while (m.find()) names.add(m.group("name").trim());
        return List.copyOf(names);
    }

    /**
     * Returns the "If you do so" followup effect text attached to the alternate cost, or an
     * empty string if there is none.
     */
    public String altFollowupText() {
        Matcher m = ALT_COST_NONSUMMON.matcher(textEn);
        if (m.find()) { String f = m.group("followup"); return f != null ? f.trim() : ""; }
        return "";
    }

    /**
     * Returns the optional extra cost for this card, or {@code null} if none is defined.
     * An extra cost lets the player pay something extra when casting — remove cards from
     * their Break Zone, discard from hand, or pay additional CP — in exchange for an
     * enhanced effect ("If you paid the extra cost, …").
     */
    public ExtraCost extraCost() {
        Matcher m = EXTRA_COST_SUMMON.matcher(textEn);
        if (!m.find()) {
            // Bahamut SIN 28-087H prices its modal choice instead of printing a "you may pay …
            // as an extra cost" sentence, so it never reaches the pattern above. The surcharge
            // is optional and paid at cast time like every other extra cost, which is exactly
            // what this accessor exists to describe -- see ActionResolver.selectActionsSurcharge.
            ActionResolver.SelectActionsSurcharge surcharge =
                    ActionResolver.selectActionsSurcharge(textEn, name);
            return surcharge != null ? ExtraCost.crystals(surcharge.crystals()) : null;
        }
        String discardcount = m.group("discardcount");
        if (discardcount != null) return ExtraCost.discardHand(Integer.parseInt(discardcount));
        String cptoks = m.group("cptoks") != null ? m.group("cptoks") : m.group("cptoksinline");
        if (cptoks != null) return ExtraCost.cpFixed(parseCostTokens(cptoks, new int[1]));
        String count = m.group("count");
        if (count == null) return ExtraCost.cpX();  // pay 《X》 branch
        String cardname = m.group("cardname");
        if (cardname != null) return ExtraCost.bzRemoveCardName(Integer.parseInt(count), cardname.trim());
        String element = m.group("element");
        if (element != null) return ExtraCost.bzRemoveElement(Integer.parseInt(count), element);
        return ExtraCost.bzRemoveForward(Integer.parseInt(count));
    }

    /**
     * Returns the list of Break Zone removal requirements for the alternate cost, one entry per
     * card that must be removed from the game.  Each entry is {@code "Element Type"} (e.g.
     * {@code "Fire Character"}).  Returns an empty list when no BZ-removal clause is present.
     */
    public List<String> altBzRemovals() {
        Matcher m = ALT_COST_NONSUMMON.matcher(textEn);
        if (!m.find()) return List.of();
        String bz = m.group("bzremovals");
        if (bz == null || bz.isBlank()) return List.of();

        // Find the card type once from the end of the whole phrase (e.g. "Character")
        Matcher typM = Pattern.compile("(?i)(Character|Forward|Backup|Monster)\\s*$").matcher(bz.trim());
        String globalType = typM.find() ? typM.group(1) : "Character";

        List<String> result = new ArrayList<>();
        Matcher em = BZ_REMOVAL_ENTRY.matcher(bz);
        while (em.find()) {
            int count = Integer.parseInt(em.group(1));
            String elem = em.group(2);
            String bzType = em.group(3) != null ? em.group(3) : globalType;
            for (int i = 0; i < count; i++) result.add(elem + " " + bzType);
        }
        return List.copyOf(result);
    }

    /**
     * Returns {@code true} when the alternate cost may only be paid with CP produced by Backups
     * (hand-card discards are not allowed).
     */
    public boolean altBackupOnlyCp() {
        Matcher m = ALT_COST_NONSUMMON.matcher(textEn);
        return m.find() && m.group("backuponly") != null;
    }

    /**
     * Returns {@code true} when the card's main casting cost may only be paid with CP produced
     * by Backups — hand-card discards are not eligible.
     * Matches text of the form "You can only pay with CP produced by Backups to cast [Name]."
     */
    private static final Pattern CAST_BACKUP_CP_ONLY = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+pay\\s+with\\s+CP\\s+produced\\s+by\\s+Backups\\s+to\\s+cast\\s+\\S[^.]*\\.?"
    );
    public boolean castBackupCpOnly() {
        return CAST_BACKUP_CP_ONLY.matcher(textEn).find();
    }

    /**
     * Returns the single element whose CP must be used when casting this card
     * ("You can only pay with Water CP to cast [Name]."), or {@code null} if unrestricted.
     */
    public String castElementOnly() {
        Matcher m = CAST_ELEMENT_ONLY.matcher(textEn);
        return m.find() ? m.group("element") : null;
    }

    // "While paying the cost to cast a Category X card, if Rikku is on the field, Rikku can produce CP of any Element."
    // Captures the category identifier only (e.g. "XI"), not the "Category" prefix.
    private static final Pattern BACKUP_CP_ANY_ELEM_CATEGORY = Pattern.compile(
        "(?i)While\\s+paying\\s+the\\s+cost\\s+to\\s+cast\\s+a\\s+Category\\s+(\\S+)\\s+card.*?can\\s+produce\\s+CP\\s+of\\s+any\\s+Element",
        Pattern.DOTALL
    );

    // "If Urianger is on the field, Urianger can produce Lightning CP."
    // "If Cindy is on the field, Cindy can produce Ice or Lightning CP."
    // Matches any "If [name] is on the field, [name] can produce [elements] CP." sentence.
    // Named group {@code elems} captures the element list (e.g. "Lightning" or "Ice or Lightning").
    private static final Pattern BACKUP_CP_EXTRA_ELEMENTS = Pattern.compile(
        "(?i)If\\s+.+?\\s+is\\s+on\\s+the\\s+field,.+?can\\s+produce\\s+" +
        "(?<elems>(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
        "(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*)\\s+CP[.!]?"
    );

    /**
     * "If [Name] is on the field, [it | [Name]] gains Elements of Fire, Ice, Wind, Earth, Lightning
     * and Water." — Shantotto, whose two printings word the subject differently: 1-107L says "it
     * gains" and the reprint Re-099L/1-107L spells the name out. Both mean the same thing, so both
     * route here rather than the reprint getting a parallel mechanism of its own.
     *
     * <p>{@code subject} is lazy and otherwise unrestricted, so it will happily absorb a name that
     * is not the carrier's; {@link #backupCpExtraElements} checks it, and any future reader must.
     * Groups: {@code cond}, {@code subject}, {@code elems} — comma-and-separated element list.
     */
    private static final Pattern BACKUP_GAINS_ELEMENTS = Pattern.compile(
        "(?i)If\\s+(?<cond>[^,]+?)\\s+is\\s+on\\s+the\\s+field,\\s+(?<subject>[^,]+?)\\s+gains\\s+Elements?\\s+of\\s+" +
        "(?<elems>(?:(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)(?:,\\s*|\\s+and\\s+))*" +
        "(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))[.!]?"
    );

    /**
     * Returns the list of extra CP elements this backup can produce when on the field.
     * Covers both "can produce X or Y CP" and "gains Elements of X, Y, Z and W" forms.
     * Returns an empty list if no such ability exists.
     */
    public List<String> backupCpExtraElements() {
        Matcher m = BACKUP_CP_EXTRA_ELEMENTS.matcher(textEn);
        if (m.find()) return List.of(m.group("elems").split("(?i)\\s+or\\s+"));
        return selfGainedElements();
    }

    /**
     * The Elements this card grants itself through "If [self] is on the field, [it | [self]] gains
     * Elements of A, B and C." (Shantotto), or an empty list when it prints no such ability.
     *
     * <p>Split from its "can produce X CP" sibling above because the two say different things and
     * only one of them is about Elements: producing CP of an Element does not make a card that
     * Element, while gaining an Element does — which is why {@code MainWindow.effectiveElements}
     * reads this and not {@link #backupCpExtraElements}. Both still resolve through the one
     * {@link #BACKUP_GAINS_ELEMENTS} pattern, so the CP dialog and the Element tests cannot
     * disagree about what the sentence grants.
     *
     * <p>Both name captures are checked against the carrier: the subject group is lazy and
     * unrestricted, so without this a sentence granting Elements to some other card would be read
     * as a self-grant. "it" is the pronoun form, and refers to whoever the condition names.
     */
    public List<String> selfGainedElements() {
        Matcher mg = BACKUP_GAINS_ELEMENTS.matcher(textEn);
        if (mg.find()
                && mg.group("cond").trim().equalsIgnoreCase(name)
                && (mg.group("subject").trim().equalsIgnoreCase("it")
                    || mg.group("subject").trim().equalsIgnoreCase(name)))
            return List.of(mg.group("elems").split("(?i),\\s*|\\s+and\\s+"));
        return List.of();
    }

    // "If Sherlotta is on the field, Sherlotta can produce CP of any Element of the Forwards you control."
    private static final Pattern BACKUP_CP_ANY_ELEM_OF_FORWARDS = Pattern.compile(
        "(?i)can\\s+produce\\s+CP\\s+of\\s+any\\s+Element\\s+of\\s+the\\s+Forwards\\s+you\\s+control"
    );

    // "If Chaos is on the field, Chaos can produce CP of any Element."
    private static final Pattern BACKUP_CP_ANY_ELEM_ALWAYS = Pattern.compile(
        "(?i)can\\s+produce\\s+CP\\s+of\\s+any\\s+Element"
    );

    /**
     * Returns the category (e.g. "Category X") for which this backup can produce CP of any
     * Element while paying casting costs, or an empty string if no such ability exists.
     */
    public String backupCpAnyElementCategory() {
        Matcher m = BACKUP_CP_ANY_ELEM_CATEGORY.matcher(textEn);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Returns true if this backup can produce CP of any Element of the Forwards the player
     * controls (Sherlotta-type ability).
     */
    public boolean backupCpAnyElementOfForwards() {
        return BACKUP_CP_ANY_ELEM_OF_FORWARDS.matcher(textEn).find();
    }

    /**
     * Returns true if this backup can unconditionally produce CP of any Element (Chaos/Cosmos-type).
     * Returns false if the ability is category-restricted or limited to controlled forwards' elements.
     */
    public boolean backupCpAnyElement() {
        if (!backupCpAnyElementCategory().isEmpty()) return false;
        if (backupCpAnyElementOfForwards()) return false;
        return BACKUP_CP_ANY_ELEM_ALWAYS.matcher(textEn).find();
    }

    /**
     * Matches field abilities that grant "can produce CP of any Element" to a set of Backups:
     * <ul>
     *   <li>"[The] Backups you control can produce CP of any Element."</li>
     *   <li>"The Job Moogle Backups you control can produce CP of any Element."</li>
     *   <li>"The Category VI Backups you control can produce CP of any Element."</li>
     *   <li>"The Earth Backups you control can produce CP of any Element."</li>
     * </ul>
     * Named groups {@code job}, {@code category}, {@code element} capture the optional filter.
     */
    private static final Pattern BACKUP_CP_GRANT = Pattern.compile(
        "(?i)(?:The\\s+)?(?:Job\\s+(?<job>\\S+)\\s+|Category\\s+(?<category>\\S+)\\s+" +
        "|(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "Backups\\s+you\\s+control\\s+can\\s+produce\\s+CP\\s+of\\s+any\\s+Element"
    );

    /** Matches "The Job Sky Pirate Backups you control can produce Wind or Water CP." */
    private static final Pattern BACKUP_CP_GRANT_SPECIFIC_ELEMS = Pattern.compile(
        "(?i)(?:The\\s+)?(?:Job\\s+(?<job>[^B]+?)\\s+|Category\\s+(?<category>\\S+)\\s+" +
        "|(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "Backups\\s+you\\s+control\\s+can\\s+produce\\s+" +
        "(?<elems>(?:(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)(?:\\s+or\\s+))+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))\\s+CP"
    );

    /**
     * Returns a {@link BackupCpGrant} describing the field-ability grant on this card, or
     * {@code null} if no such ability is present.  The {@code grantedElements} field is
     * {@code null} for any-element grants, or a specific list for element-restricted grants.
     */
    public BackupCpGrant backupCpGrant() {
        Matcher m = BACKUP_CP_GRANT.matcher(textEn);
        if (m.find())
            return new BackupCpGrant(m.group("job"), m.group("category"), m.group("element"), null);
        Matcher ms = BACKUP_CP_GRANT_SPECIFIC_ELEMS.matcher(textEn);
        if (ms.find()) {
            String[] parts = ms.group("elems").split("(?i)\\s+or\\s+");
            List<String> elems = new ArrayList<>();
            for (String p : parts) elems.add(p.trim());
            return new BackupCpGrant(ms.group("job"), ms.group("category"), ms.group("element"), elems);
        }
        return null;
    }

    /** Returns {@code true} if {@code text} describes any "produce CP" backup ability (self or grant). */
    static boolean isBackupCpAbility(String text) {
        return BACKUP_CP_ANY_ELEM_ALWAYS.matcher(text).find()
            || BACKUP_CP_EXTRA_ELEMENTS.matcher(text).find()
            || BACKUP_GAINS_ELEMENTS.matcher(text).find()
            || BACKUP_CP_GRANT.matcher(text).find()
            || BACKUP_CP_GRANT_SPECIFIC_ELEMS.matcher(text).find();
    }

    /**
     * "You cannot cast X." — an absolute prohibition covering every zone a cast can be
     * declared from (hand, Break Zone, removed-from-game).  Forza 12-015H is the only
     * printing: it can still reach the field via effects that <em>put</em> it there.
     *
     * <p>The lookahead rejects the unrelated "…you cannot cast any cards/copies…" and
     * "Players cannot cast Summons." wordings, which are duration-scoped or global rather
     * than a property of the card carrying the sentence.
     */
    private static final Pattern CAST_PROHIBITED = Pattern.compile(
        "(?i)\\bYou\\s+cannot\\s+cast\\s+(?!any\\b|cards?\\b|Summons\\b)\\S[^.]*[.!]"
    );

    /** "You can only cast X during your turn." */
    private static final Pattern CAST_YOUR_TURN_ONLY = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+cast\\s+\\S[^.]+?\\s+during\\s+your\\s+turn[.!]?"
    );

    /** "You can only cast X during your Main Phase." */
    private static final Pattern CAST_MAIN_PHASE_ONLY = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+cast\\s+\\S[^.]+?\\s+during\\s+your\\s+Main\\s+Phase[.!]?"
    );

    /** "You can only cast X during your opponent's turn." (Back Attack cards) */
    private static final Pattern CAST_OPPONENT_TURN_ONLY = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+cast\\s+\\S[^.]+?\\s+during\\s+your\\s+opponent(?:'s|s')\\s+turn[.!]?"
    );

    /** "You can only cast X if you don't control any Forwards." */
    private static final Pattern CAST_REQUIRES_NO_FORWARDS = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+cast\\s+\\S[^.]+?\\s+if\\s+you\\s+don(?:'t|t)\\s+control\\s+any\\s+Forwards[.!]?"
    );

    /** "You can only cast X if you have a Forward." (on the field; not in Break Zone) */
    private static final Pattern CAST_REQUIRES_A_FORWARD = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+cast\\s+\\S[^.]+?\\s+if\\s+you\\s+have\\s+a\\s+Forward" +
        "(?!\\s+in\\s+your\\s+Break\\s+Zone)(?!,)[.!]?"
    );

    /**
     * "You can only play [Name] if a Forward you controlled has been put from the field into the
     * Break Zone this turn." — Nox Suzaku 15-130H.
     *
     * <p>Says "play" rather than "cast" because Nox Suzaku is a Forward; both verbs are accepted so
     * a Summon printing the same condition would be read too. The board condition it names is
     * already tracked, per player and per turn, as {@code PlayerTurnState.forwardPutToBZThisTurn} —
     * {@link #FORWARD_PUT_TO_BZ_THIS_TURN_RESTRICTION} gates <em>abilities</em> on the same flag.
     * This is that restriction moved to the cast.
     */
    private static final Pattern CAST_REQUIRES_FORWARD_PUT_TO_BZ = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+(?:cast|play)\\s+\\S[^.]+?\\s+if\\s+a\\s+Forward\\s+you\\s+controlled\\s+" +
        "has\\s+been\\s+put\\s+from\\s+the\\s+field\\s+into\\s+the\\s+Break\\s+Zone\\s+this\\s+turn[.!]?"
    );

    /**
     * "You can only play [Name] if either player has received N points of damage or more."
     * — Sephiroth 11-130L. Group {@code count} — the damage threshold.
     *
     * <p>"Either player" is the whole point of the wording: the larger of the two damage zones is
     * what the threshold is measured against, so an opponent who has taken four opens the cast as
     * readily as taking four yourself. Every other damage gate in the corpus reads one side only
     * ("if you have received N points of damage or more"), which is why this one cannot join the
     * {@code Damage N --} family.
     *
     * <p>Says "play" rather than "cast" the way {@link #CAST_REQUIRES_FORWARD_PUT_TO_BZ} does, and
     * accepts both verbs for the same reason: a hand card's play is its cast, and which verb is
     * printed only reflects whether the card is a Character or a Summon.
     */
    private static final Pattern CAST_REQUIRES_EITHER_PLAYER_DAMAGE = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+(?:cast|play)\\s+\\S[^.]+?\\s+if\\s+either\\s+player\\s+has\\s+received\\s+" +
        "(?<count>\\d+)\\s+points?\\s+of\\s+damage\\s+or\\s+more[.!]?"
    );

    /**
     * "You can only cast X if your opponent has N cards or less in their hand."
     * Group {@code count} — the maximum allowed hand size.
     */
    private static final Pattern CAST_MAX_OPPONENT_HAND = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+cast\\s+\\S[^.]+?\\s+if\\s+your\\s+opponent\\s+has\\s+" +
        "(?<count>\\d+)\\s+cards?\\s+or\\s+less\\s+in\\s+their\\s+hand[.!]?"
    );

    /**
     * "You must control N or more Job X Forwards and/or Job Y Forwards to cast Z."
     * Group {@code count} — minimum number of qualifying Forwards.
     * Group {@code jobs}  — the full "Job X Forwards and/or Job Y Forwards" segment.
     */
    private static final Pattern CAST_MUST_CONTROL = Pattern.compile(
        "(?i)You\\s+must\\s+control\\s+(?<count>\\d+)\\s+or\\s+more\\s+" +
        "(?<jobs>Job\\s+.+?\\s+Forwards?(?:\\s+and/or\\s+Job\\s+.+?\\s+Forwards?)*)\\s+" +
        "to\\s+cast\\s+\\S[^.]*?[.!]?"
    );

    /**
     * "You must control N or more Forwards to cast [Name]." (Steiner 14-109C)
     * Group {@code count} — minimum number of Forwards, of any Job, Element or Category.
     *
     * <p>The unqualified sibling of {@link #CAST_MUST_CONTROL}, which requires a "Job X Forwards"
     * segment after "or more" and so cannot reach this wording. The two cannot both match one
     * sentence — either "Job" follows the count or "Forwards to cast" does — but the parse site
     * still tries the qualified form first, so a future "N or more Job X Forwards" printing keeps
     * its job filter rather than being flattened into a bare count.
     */
    private static final Pattern CAST_MUST_CONTROL_FORWARD_COUNT = Pattern.compile(
        "(?i)You\\s+must\\s+control\\s+(?<count>\\d+)\\s+or\\s+more\\s+Forwards?\\s+" +
        "to\\s+cast\\s+\\S[^.]*?[.!]?"
    );

    /**
     * "You must control Characters of cost 1, 2, 3, 4, 5 and 6 to cast [Name]." (Leo 16-126R)
     * Group {@code costs} — the raw "1, 2, 3, 4, 5 and 6" list. Each listed cost needs its own
     * Character, so this is a set of separate requirements rather than one count.
     */
    private static final Pattern CAST_MUST_CONTROL_COSTS = Pattern.compile(
        "(?i)You\\s+must\\s+control\\s+Characters\\s+of\\s+cost\\s+" +
        "(?<costs>\\d+(?:\\s*,\\s*\\d+)*(?:\\s*,?\\s*and\\s+\\d+)?)\\s+to\\s+cast\\s+\\S[^.]*?[.!]?"
    );

    /**
     * "You must control a Category X Forward to play [name] from your hand onto the field."
     * Group {@code cat} — the category token (e.g. "XIV").
     *
     * <p>Two tails, same requirement: Rhitahtyn 9-020R prints the play-from-hand spelling, both
     * Noctis printings (21-130S, 29-090R) print "to cast [name]". A hand card's play is its cast,
     * so nothing distinguishes them but the wording.
     */
    private static final Pattern CAST_MUST_CONTROL_CATEGORY_FWD = Pattern.compile(
        "(?i)You\\s+must\\s+control\\s+a\\s+Category\\s+(?<cat>\\S+)\\s+Forward\\s+to\\s+" +
        "(?:play\\s+\\S[^.]*?\\s+from\\s+your\\s+hand\\s+onto\\s+the\\s+field|cast\\s+\\S[^.]*?)[.!]?"
    );

    /**
     * "You can only play [name] if you control a Category X Forward."
     * Group {@code cat} — the category token (e.g. "VII").
     */
    private static final Pattern CAST_ONLY_PLAY_IF_CONTROL_CATEGORY_FWD = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+play\\s+\\S[^.]*?\\s+if\\s+you\\s+control\\s+a\\s+Category\\s+(?<cat>\\S+)\\s+Forward[.!]?"
    );

    /**
     * "You can only cast X if you have a Forward, Backup, Monster, and a Summon in your Break Zone …"
     * Group {@code types} captures the word list before "in your Break Zone".
     * The negative lookahead {@code (?!a\s+total)} prevents matching Eiko's count variant.
     */
    private static final Pattern CAST_REQUIRES_BZ_TYPES = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+cast\\s+\\S[^.]+?\\s+if\\s+you\\s+have\\s+(?!a\\s+total)(?<types>[^.]+?)\\s+in\\s+your\\s+Break\\s+Zone"
    );

    /**
     * "You can only cast X if you have a total of N or more Summons in your Break Zone
     *  and/or Summons you own removed from the game."
     * Group 1 = minimum count N.
     */
    private static final Pattern CAST_MIN_BZ_RFP_SUMMONS = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+cast\\s+\\S[^.]+?\\s+if\\s+you\\s+have\\s+a\\s+total\\s+of\\s+(\\d+)" +
        "\\s+or\\s+more\\s+Summons\\s+in\\s+your\\s+Break\\s+Zone\\s+and/or\\s+Summons\\s+you\\s+own\\s+removed\\s+from\\s+the\\s+game"
    );

    /**
     * Returns {@code true} for a card printing "You cannot cast [Name]." — it can never be cast,
     * from hand or from any borrowed-cast zone, and may only reach the field through effects that
     * put it there.
     */
    public boolean castProhibited() {
        return CAST_PROHIBITED.matcher(textEn).find();
    }

    /**
     * "You cannot play [Name] from your hand due to Summons or abilities." — blocks only plays
     * sourced from hand (Graham 12-060R, Nimbus 3-046H, Estinien 6-089R, Tidus 7-116L,
     * Bergan 9-018R, Edge 9-045H). Group {@code name} is checked against the card.
     */
    private static final Pattern PLAY_BY_EFFECT_PROHIBITED_FROM_HAND = Pattern.compile(
        "(?i)You\\s+cannot\\s+play\\s+(?<name>[^.!]+?)\\s+from\\s+your\\s+hand\\s+" +
        "due\\s+to\\s+Summons?\\s+or\\s+abilities[.!]?"
    );

    /**
     * "You cannot play [Name] due to Summons or abilities." — no zone named, so it blocks a play
     * out of any zone (Leo 16-126R). The negative lookahead keeps the from-hand wording out, which
     * is the narrower restriction and has its own pattern.
     */
    private static final Pattern PLAY_BY_EFFECT_PROHIBITED_ANY_ZONE = Pattern.compile(
        "(?i)You\\s+cannot\\s+play\\s+(?<name>[^.!]+?)\\s+(?!from\\s+your\\s+hand\\s+)" +
        "due\\s+to\\s+Summons?\\s+or\\s+abilities[.!]?"
    );

    /**
     * Returns {@code true} when a Summon or ability may not put this card onto the field from
     * {@code fromHand}'s zone. This is the counterpart to {@link #castProhibited()}: those cards
     * can only arrive via an effect, these can only arrive by being cast normally.
     *
     * <p>Both printings name the card explicitly, and the name is verified — the sentence is a
     * statement about this card, and the same text can appear quoted inside an ability it grants.
     *
     * @param fromHand {@code true} when the effect would play the card out of its owner's hand
     */
    public boolean playByEffectProhibited(boolean fromHand) {
        Matcher any = PLAY_BY_EFFECT_PROHIBITED_ANY_ZONE.matcher(textEn);
        while (any.find())
            if (any.group("name").trim().equalsIgnoreCase(name)) return true;
        if (!fromHand) return false;
        Matcher hand = PLAY_BY_EFFECT_PROHIBITED_FROM_HAND.matcher(textEn);
        while (hand.find())
            if (hand.group("name").trim().equalsIgnoreCase(name)) return true;
        return false;
    }

    /**
     * Returns a {@link CastRestriction} describing any "You cannot cast …" / "You can only cast …"
     * constraint on this card, or {@code null} if no such restriction is present.
     */
    public CastRestriction castRestriction() {
        // An outright prohibition subsumes every conditional restriction — no need to parse further.
        if (castProhibited()) {
            return new CastRestriction(true, false, false, false, false, false,
                    java.util.Set.of(), 0, -1, null);
        }

        boolean yourTurnOnly     = CAST_YOUR_TURN_ONLY.matcher(textEn).find();
        boolean mainPhaseOnly    = CAST_MAIN_PHASE_ONLY.matcher(textEn).find();
        boolean opponentTurnOnly = CAST_OPPONENT_TURN_ONLY.matcher(textEn).find();
        boolean requiresNoFwds   = CAST_REQUIRES_NO_FORWARDS.matcher(textEn).find();
        boolean requiresAFwd     = CAST_REQUIRES_A_FORWARD.matcher(textEn).find();
        boolean requiresFwdToBZ  = CAST_REQUIRES_FORWARD_PUT_TO_BZ.matcher(textEn).find();

        java.util.Set<String> requiredBZTypes = java.util.Set.of();
        Matcher bzM = CAST_REQUIRES_BZ_TYPES.matcher(textEn);
        if (bzM.find()) {
            String typesText = bzM.group("types");
            java.util.Set<String> found = new java.util.HashSet<>();
            for (String t : new String[]{"Forward", "Backup", "Monster", "Summon"}) {
                if (typesText.contains(t)) found.add(t);
            }
            requiredBZTypes = java.util.Set.copyOf(found);
        }

        int minBZAndRfpSummons = 0;
        Matcher sumM = CAST_MIN_BZ_RFP_SUMMONS.matcher(textEn);
        if (sumM.find()) minBZAndRfpSummons = Integer.parseInt(sumM.group(1));

        int maxOpponentHand = -1;
        Matcher oppHandM = CAST_MAX_OPPONENT_HAND.matcher(textEn);
        if (oppHandM.find()) maxOpponentHand = Integer.parseInt(oppHandM.group("count"));

        // Sephiroth 11-130L. 0 means no such restriction, as with minBZAndRfpSummons — a printed
        // threshold is always at least 1.
        int minEitherPlayerDamage = 0;
        Matcher eitherDmgM = CAST_REQUIRES_EITHER_PLAYER_DAMAGE.matcher(textEn);
        if (eitherDmgM.find()) minEitherPlayerDamage = Integer.parseInt(eitherDmgM.group("count"));

        ControlCondition mustControl = null;
        Matcher mustM = CAST_MUST_CONTROL.matcher(textEn);
        if (mustM.find()) {
            int count = Integer.parseInt(mustM.group("count"));
            String[] segments = mustM.group("jobs").split("(?i)\\s+and/or\\s+");
            java.util.List<String> jobs = new java.util.ArrayList<>();
            for (String seg : segments) {
                String job = seg.trim()
                        .replaceFirst("(?i)^Job\\s+", "")
                        .replaceFirst("(?i)\\s+Forwards?$", "")
                        .trim();
                if (!job.isEmpty()) jobs.add(job);
            }
            String jobFilter = String.join("|", jobs);
            mustControl = new ControlCondition(
                    java.util.List.of(), count, false, "Forward", null,
                    jobFilter.isEmpty() ? null : jobFilter,
                    null, 0, java.util.List.of());
        }
        if (mustControl == null) {
            // Checked after the Job-qualified form above, so a job filter is never lost to the
            // bare count. See CAST_MUST_CONTROL_FORWARD_COUNT.
            Matcher cntM = CAST_MUST_CONTROL_FORWARD_COUNT.matcher(textEn);
            if (cntM.find()) {
                mustControl = new ControlCondition(
                        java.util.List.of(), Integer.parseInt(cntM.group("count")), false,
                        "Forward", null, null, null, 0, java.util.List.of());
            }
        }
        if (mustControl == null) {
            Matcher catM = CAST_MUST_CONTROL_CATEGORY_FWD.matcher(textEn);
            if (catM.find()) {
                mustControl = new ControlCondition(
                        java.util.List.of(), 1, false, "Forward", null, null,
                        catM.group("cat").trim(), 0, java.util.List.of());
            }
        }
        if (mustControl == null) {
            Matcher catM = CAST_ONLY_PLAY_IF_CONTROL_CATEGORY_FWD.matcher(textEn);
            if (catM.find()) {
                mustControl = new ControlCondition(
                        java.util.List.of(), 1, false, "Forward", null, null,
                        catM.group("cat").trim(), 0, java.util.List.of());
            }
        }

        java.util.Set<Integer> mustControlCosts = new java.util.TreeSet<>();
        Matcher costsM = CAST_MUST_CONTROL_COSTS.matcher(textEn);
        if (costsM.find())
            for (String tok : costsM.group("costs").split("(?i)\\s*,\\s*|\\s*,?\\s*and\\s+"))
                if (!tok.isBlank()) mustControlCosts.add(Integer.parseInt(tok.trim()));

        if (!yourTurnOnly && !mainPhaseOnly && !opponentTurnOnly && !requiresNoFwds
                && !requiresAFwd && requiredBZTypes.isEmpty()
                && minBZAndRfpSummons == 0 && maxOpponentHand < 0 && mustControl == null
                && mustControlCosts.isEmpty() && !requiresFwdToBZ && minEitherPlayerDamage == 0) {
            return null;
        }
        return new CastRestriction(false, yourTurnOnly, mainPhaseOnly, opponentTurnOnly,
                requiresNoFwds, requiresAFwd, requiredBZTypes, minBZAndRfpSummons,
                maxOpponentHand, mustControl, mustControlCosts, requiresFwdToBZ,
                minEitherPlayerDamage);
    }

    /**
     * Returns cleaned effect text for a Summon: strips the {@code [[ex]]} exBurst prefix,
     * then (when an alternate cost exists) splits on {@code [[br]]} and skips segments that
     * are trait-only lines or alternate-cost blocks.  All remaining markup tags are removed
     * and whitespace is collapsed.
     */
    public String summonEffect() {
        String t = SUMMON_EX_PREFIX.matcher(textEn).replaceFirst("");
        if (altCrystalCost() > 0 || extraCost() != null || altFieldRemoval() != null
                || !altDullCosts().isEmpty()) {
            String[] parts = SUMMON_BR.split(t);
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                if (ALT_COST_SUMMON.matcher(trimmed).find())    continue;
                if (ALT_COST_SUMMON_REMOVE_FIELD.matcher(trimmed).find()) continue;
                if (ALT_COST_NONSUMMON.matcher(trimmed).find()) continue;
                if (ALT_COST_DULL.matcher(trimmed).find())      continue;
                if (EXTRA_COST_SUMMON.matcher(trimmed).find())  continue; // extra-cost clause
                if (TRAIT_ONLY_SEGMENT.matcher(trimmed).matches()) continue;
                sb.append(trimmed).append(" ");
            }
            t = sb.toString().trim();
        }
        t = SUMMON_MARKUP.matcher(t).replaceAll(" ");
        return t.replaceAll("\\s+", " ").trim();
    }

    /**
     * Returns the element whose Backup CP must be used to cast/play this card, or
     * {@code ""} if any Backup CP is accepted, or {@code null} if there is no such restriction.
     * Detected from "You can only pay with CP produced by [Element] Backups to cast/play [CardName]"
     * which, when present, always appears as the first {@code [[br]]}-delimited segment of the card text.
     */
    public String cpBackupElement() {
        Matcher m = CP_BACKUP_ONLY_CAST.matcher(textEn);
        if (!m.find()) return null;
        String elem = m.group("element");
        return elem != null ? elem : "";
    }

    /**
     * Returns the effect text to execute when this card triggers an EX Burst.
     * <ul>
     *   <li>Summons — everything after the {@code [[ex]]…[[/]]} tag, markup cleaned.</li>
     *   <li>Forwards / Backups / Monsters — the first {@code [[br]]}-delimited segment after
     *       the tag, with any leading "When [trigger]," clause stripped.</li>
     * </ul>
     * Returns an empty string if the card has no EX Burst tag or no parseable effect.
     */
    public String exBurstEffect() {
        Matcher m = EX_BURST_TAG.matcher(textEn);
        if (!m.find()) return "";
        String after = textEn.substring(m.end()).trim();
        if (after.isEmpty()) return "";

        if (!isSummon()) {
            int brIdx = after.toLowerCase(Locale.ROOT).indexOf("[[br]]");
            if (brIdx >= 0) after = after.substring(0, brIdx).trim();
            // Strip "When [CardName] [trigger], " so the bare effect text is left
            after = after.replaceFirst("(?i)^When\\s+[^,]+,\\s*", "").trim();
        }

        after = SUMMON_MARKUP.matcher(after).replaceAll(" ");
        return after.replaceAll("\\s+", " ").trim();
    }

    // Haste: start with [[br]] or (This descriptor, middle [[br]]…[[br]], or paired with other keywords
    private static final Pattern HASTE_PATTERN = Pattern.compile(
        "(?i)(?:^Haste\\s*(?:\\[\\[br\\]\\]|\\(This)|\\[\\[br\\]\\]Haste\\b|Haste\\s+First\\s+Strike)"
    );

    // Brave: start with [[br]] or (Attacking descriptor, after any [[br]], or paired with other keywords
    private static final Pattern BRAVE_PATTERN = Pattern.compile(
        "(?i)(?:^Brave\\s*(?:\\[\\[br\\]\\]|\\(Attacking)|\\[\\[br\\]\\]Brave\\b|Brave\\s*\\[\\[br\\]\\]|First\\s+Strike\\s+Brave|Haste\\s+Brave)"
    );

    // First Strike: start of card with (If, [[br]], after any [[br]], or paired with Haste/Brave
    private static final Pattern FIRST_STRIKE_PATTERN = Pattern.compile(
        "(?i)(?:^First\\s+Strike\\s*(?:\\(If|\\[\\[br\\]\\])|\\[\\[br\\]\\]First\\s+Strike\\b|Haste\\s+First\\s+Strike|First\\s+Strike\\s+Brave)"
    );

    // Back Attack: at the start of card text or after a [[br]] separator (card data uses [[br]], not <p>).
    private static final Pattern BACK_ATTACK_PATTERN = Pattern.compile(
        "(?i)(?:^Back\\s+Attack\\b|\\[\\[br\\]\\]Back\\s+Attack\\b)"
    );

    private static final Pattern WARP_PATTERN = Pattern.compile(
        "(?i)Warp\\s+(\\d+)\\s*--\\s*((?:《[^》]*》\\s*)*)"
    );

    /** The shared tail of every non-damage break shield: "cannot be broken by … that don't deal damage". */
    private static final String NON_DMG_BREAK_TAIL =
        "cannot\\s+be\\s+broken\\s+by\\s+(?:opposing\\s+)?(?:Summons?\\s+or\\s+)?abilit(?:y|ies)\\s+that\\s+don'?t\\s+deal\\s+damage";

    // Field-ability grants: "[CardName] gains '[CardName] cannot be broken by … abilities that don't deal damage.'"
    // The optional "… and " run before the quote carries the other things granted in the same
    // breath (Wol 14-059R: "gains +1000 power, Brave and \"Wol cannot be broken by …\"").
    private static final Pattern SELF_NON_DMG_BREAK_SHIELD_GRANT = Pattern.compile(
        "(?i)^(?<name>.+?)\\s+gains?\\s+(?:[^'\"]*\\s+and\\s+)?" +
        "['\"][^'\"]*?" + NON_DMG_BREAK_TAIL + "\\.?['\"][.!]?$"
    );

    // Direct self-protection: "[CardName] cannot be broken by opposing Summons or abilities that don't deal damage."
    private static final Pattern SELF_NON_DMG_BREAK_SHIELD_DIRECT = Pattern.compile(
        "(?i)^(?<name>[^.!]+?)\\s+" + NON_DMG_BREAK_TAIL + "[.!]?$"
    );

    /**
     * Returns {@code true} when {@code effectText} is a self-targeted field-ability protection grant
     * of the form "[cardName] gains '[cardName] cannot be broken by … abilities that don't deal damage.'".
     */
    static boolean parseSelfNonDmgBreakShield(String effectText, String cardName) {
        Matcher m = SELF_NON_DMG_BREAK_SHIELD_GRANT.matcher(effectText.trim());
        if (!m.matches()) return false;
        return m.group("name").trim().equalsIgnoreCase(cardName);
    }

    /**
     * Returns {@code true} when {@code effectText} is a direct self-protection field ability
     * of the form "[cardName] cannot be broken by opposing [Summons or] abilities that don't deal damage."
     */
    static boolean parseSelfNonDmgBreakShieldDirect(String effectText, String cardName) {
        Matcher m = SELF_NON_DMG_BREAK_SHIELD_DIRECT.matcher(effectText.trim());
        if (!m.matches()) return false;
        return m.group("name").trim().equalsIgnoreCase(cardName);
    }

    // Unconditional printed self-protection: "[CardName] cannot be broken." (Cid (WOFF) 4-034R,
    // Ardyn 8-068L). Anchored at both ends and checked against the card's own name, so the
    // qualified forms stay out: "... cannot be broken by ..." fails the tail anchor, and a
    // leading condition ("During each Attack Phase, Galuf ...") fails the name check.
    private static final Pattern SELF_CANNOT_BE_BROKEN_DIRECT = Pattern.compile(
        "(?i)^(?<name>[^.!]+?)\\s+cannot\\s+be\\s+broken[.!]?$"
    );

    /**
     * Returns {@code true} when {@code effectText} is the unconditional self-protection field
     * ability "[cardName] cannot be broken."
     *
     * <p>The engine applies this one through the printed {@link Trait#CANNOT_BE_BROKEN} set by
     * {@link #parseTraits}, which {@code breakTarget} checks for a card in any zone — so it
     * protects Backups such as Cid (WOFF), not only Forwards. This parser exists so callers that
     * work a field ability at a time can recognise the sentence that grants it.
     */
    static boolean parseSelfCannotBeBroken(String effectText, String cardName) {
        Matcher m = SELF_CANNOT_BE_BROKEN_DIRECT.matcher(effectText.trim());
        if (!m.matches()) return false;
        return m.group("name").trim().equalsIgnoreCase(cardName);
    }

    /**
     * "[CardName] cannot leave the field due to your opponent's Summons or abilities."
     * (Chaos B-001, Spiritus B-002, President Shinra B-029, Hojo B-030.)
     *
     * <p>Wider than {@link #SELF_CANNOT_BE_BROKEN_DIRECT}: it covers every way an opponent's
     * effect could move the card off the field — broken, removed from the game, or returned to
     * hand — while leaving the card's own controller and non-effect causes (combat damage, a cost
     * the controller pays) alone.
     */
    private static final Pattern SELF_CANNOT_LEAVE_FIELD_BY_OPP = Pattern.compile(
        "(?i)^(?<name>[^.!]+?)\\s+cannot\\s+leave\\s+the\\s+field\\s+due\\s+to\\s+" +
        "your\\s+opponent(?:'s|s')\\s+Summons?\\s+or\\s+abilities[.!]?$"
    );

    /**
     * "[CardName] cannot gain [Trait]." (Ravana, Savior of the Gnath 14-087L.)
     *
     * <p>A restriction on <em>gaining</em>, not on having: a trait printed on the card is not one
     * it gained, so this only bars the granted sources. Ravana prints no Brave of his own, and the
     * distinction is what keeps the sentence from reading as "loses Brave" — the wording next to
     * it on Magus Sisters 20-083R ("The Forwards opponent controls lose Haste") is the other kind.
     */
    private static final Pattern SELF_CANNOT_GAIN_TRAIT = Pattern.compile(
        "(?i)^(?<name>[^.!]+?)\\s+cannot\\s+gain\\s+" +
        "(?<trait>Haste|Brave|First\\s+Strike|Back\\s+Attack)[.!]?$"
    );

    /**
     * The trait {@code effectText} bars this card from gaining, or {@code null} when the text is
     * not that shape or names another card.
     */
    static Trait parseSelfCannotGainTrait(String effectText, String cardName) {
        Matcher m = SELF_CANNOT_GAIN_TRAIT.matcher(effectText.trim());
        if (!m.matches() || !m.group("name").trim().equalsIgnoreCase(cardName)) return null;
        EnumSet<Trait> named = traitsNamedIn(m.group("trait"));
        return named.isEmpty() ? null : named.iterator().next();
    }

    /**
     * Traits this card may never gain from an effect. Empty for every card that prints no such
     * restriction; the traits it prints itself are unaffected.
     */
    public Set<Trait> cannotGainTraits() {
        EnumSet<Trait> out = EnumSet.noneOf(Trait.class);
        for (FieldAbility fa : fieldAbilities()) {
            Trait t = parseSelfCannotGainTrait(fa.effectText(), name);
            if (t != null) out.add(t);
        }
        return out;
    }

    /**
     * Returns {@code true} when {@code effectText} is the self-protection field ability
     * "[cardName] cannot leave the field due to your opponent's Summons or abilities."
     *
     * <p>Applied through the printed {@link Trait#CANNOT_LEAVE_FIELD_BY_OPP} set by
     * {@link #parseTraits}, which every effect-driven field exit consults. The name check is what
     * keeps it a statement about its own carrier rather than one it merely quotes.
     */
    static boolean parseSelfCannotLeaveFieldByOpp(String effectText, String cardName) {
        Matcher m = SELF_CANNOT_LEAVE_FIELD_BY_OPP.matcher(effectText.trim());
        if (!m.matches()) return false;
        return m.group("name").trim().equalsIgnoreCase(cardName);
    }

    /**
     * "If you control [X], [CardName] [gains +N power and ]cannot be broken by … that don't deal
     * damage." — Gilgamesh (XI) 10-111H and Gilgamesh 22-061L. The power half of the latter is
     * already carried by its {@link IfControlBoost}; only the shield needs this.
     */
    private static final Pattern IF_CONTROL_NON_DMG_BREAK_SHIELD = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<cond>.+?),\\s+(?<name>[^.!]+?)\\s+" +
        "(?:gains?\\s+\\+\\d+\\s+power\\s+and\\s+)?" + NON_DMG_BREAK_TAIL + "[.!]?$"
    );

    /**
     * Returns the {@link ControlCondition} gating "If you control [X], [cardName] cannot be broken
     * by … that don't deal damage.", or {@code null} when the text is not that shape, names another
     * card, or states a condition that does not parse.
     */
    static ControlCondition parseIfControlNonDmgBreakShield(String effectText, String cardName) {
        Matcher m = IF_CONTROL_NON_DMG_BREAK_SHIELD.matcher(effectText.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(cardName)) return null;
        return parseControlCondition(m.group("cond").trim());
    }

    /**
     * "The [Element | Job X | Card Name Y | Category Z] [Type] you control [gain "…" and ]cannot be
     * broken by … that don't deal damage." — a grant to a filtered set rather than to the printing
     * card. Celestia 13-128L and Rasler 5-166S print it bare; Haveh 21-075R and Madam Edel 16-080H
     * wrap it as a quoted ability the members gain.
     */
    private static final Pattern FIELD_NON_DMG_BREAK_SHIELD_GRANT = Pattern.compile(
        "(?i)^The\\s+(?:Job\\s+(?<job>.+?)|Card\\s+Name\\s+(?<cardname>.+?)|Category\\s+(?<category>\\S+)" +
        "|(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))\\s+" +
        "(?<type>Forwards?|Backups?|Monsters?|Characters?)?\\s*you\\s+control\\s+" +
        "(?:gains?\\s+(?:\"[^\"]*\"\\s+and\\s+)*\"This\\s+(?:Character|Forward|Monster|Backup)\\s+)?" +
        NON_DMG_BREAK_TAIL + "\\.?\"?[.!]?$"
    );

    /**
     * The filter a {@link #FIELD_NON_DMG_BREAK_SHIELD_GRANT} protects. Exactly one of
     * {@code job}/{@code cardName}/{@code category}/{@code element} is non-null.
     */
    record NonDmgBreakShieldGrant(String job, String cardName, String category, String element,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters) {

        /**
         * True when {@code c} is inside the protected set, using the shared field-filter rules.
         *
         * @param jobsStripped whether {@code c} has lost its Jobs for the turn (Exdeath 3-100L)
         */
        boolean appliesToCard(CardData c, boolean jobsStripped) {
            if (c == null) return false;
            boolean typeOk = (inclForwards && c.isForward())
                          || (inclBackups  && c.isBackup())
                          || (inclMonsters && (c.isMonster() || c.alsoCountsAsMonster()));
            return typeOk
                && CardFilters.meetsElementFilter(c, element)
                && CardFilters.meetsJobFilter(c, job, jobsStripped)
                && CardFilters.meetsCategoryFilter(c, category)
                && CardFilters.meetsCardNameFilter(c, cardName);
        }
    }

    /**
     * "The [Type]s you control cannot be broken by your opponent's Summons or abilities." —
     * Auron 1-002R, the one printing in the corpus with a bare type and no "that don't deal damage"
     * qualifier. It is kept as its own pattern rather than by loosening
     * {@link #FIELD_NON_DMG_BREAK_SHIELD_GRANT}, whose required filter and required qualifier are
     * what stop it over-claiming the 26 other "cannot be broken" printings.
     *
     * <p>The missing qualifier costs nothing here: the only thing this protects in practice is a
     * Backup, and a Backup has no power to be broken by damage, so "by Summons or abilities" and
     * "by Summons or abilities that don't deal damage" pick out the same breaks.
     */
    private static final Pattern FIELD_TYPE_BREAK_SHIELD_GRANT = Pattern.compile(
        "(?i)^The\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control\\s+" +
        "cannot\\s+be\\s+broken\\s+by\\s+(?:your\\s+opponent(?:'s|s')|opposing)\\s+" +
        "Summons?\\s+or\\s+abilit(?:y|ies)[.!]?$"
    );

    /**
     * Parses a "The [filter] you control cannot be broken by … that don't deal damage." field
     * ability, or returns {@code null}. The printing card is protected only when it matches its
     * own filter — Celestia is a Water Character and Haveh has the Job Warrior, so both do;
     * Rasler is not named Ashe and Madam Edel is not a Morze's Soiree Member, so neither does.
     */
    static NonDmgBreakShieldGrant parseFieldNonDmgBreakShieldGrant(String effectText) {
        Matcher bare = FIELD_TYPE_BREAK_SHIELD_GRANT.matcher(effectText.trim());
        if (bare.matches()) {
            String bt = bare.group("type").toLowerCase(Locale.ROOT);
            boolean anyType = bt.startsWith("character");
            return new NonDmgBreakShieldGrant(null, null, null, null,
                    anyType || bt.startsWith("forward"),
                    anyType || bt.startsWith("backup"),
                    anyType || bt.startsWith("monster"));
        }
        Matcher m = FIELD_NON_DMG_BREAK_SHIELD_GRANT.matcher(effectText.trim());
        if (!m.matches()) return null;
        String type = m.group("type");
        String t    = type == null ? "character" : type.toLowerCase(Locale.ROOT);
        boolean any = t.startsWith("character");
        return new NonDmgBreakShieldGrant(
                m.group("job")      != null ? m.group("job").trim()      : null,
                m.group("cardname") != null ? m.group("cardname").trim() : null,
                m.group("category") != null ? m.group("category").trim() : null,
                m.group("element"),
                any || t.startsWith("forward"),
                any || t.startsWith("backup"),
                any || t.startsWith("monster"));
    }

    /** "[CardName] cannot be broken during your turn." (Galuf 7-067L) */
    private static final Pattern SELF_CANNOT_BE_BROKEN_YOUR_TURN = Pattern.compile(
        "(?i)^(?<name>[^.!]+?)\\s+cannot\\s+be\\s+broken\\s+during\\s+your\\s+turn[.!]?$"
    );

    /** "During each Attack Phase, [CardName] cannot be broken." (Galuf 12-056H) */
    private static final Pattern SELF_CANNOT_BE_BROKEN_ATTACK_PHASE = Pattern.compile(
        "(?i)^During\\s+each\\s+Attack\\s+Phase,\\s+(?<name>[^.!]+?)\\s+cannot\\s+be\\s+broken[.!]?$"
    );

    /** "If a [X] Counter is placed on [CardName], [CardName] cannot be broken." (Llednar 13-108L) */
    private static final Pattern SELF_CANNOT_BE_BROKEN_WITH_COUNTER = Pattern.compile(
        "(?i)^If\\s+a\\s+(?<counter>.+?)\\s+Counter\\s+is\\s+placed\\s+on\\s+(?<on>[^,]+?),\\s+" +
        "(?<name>[^.!]+?)\\s+cannot\\s+be\\s+broken[.!]?$"
    );

    /**
     * Returns {@code true} for "[cardName] cannot be broken during your turn." — protection that
     * holds only while it is the controller's own turn, so it is granted per-query by
     * {@code FieldGrantCalculator} rather than baked into the printed traits.
     */
    static boolean parseSelfCannotBeBrokenDuringYourTurn(String effectText, String cardName) {
        Matcher m = SELF_CANNOT_BE_BROKEN_YOUR_TURN.matcher(effectText.trim());
        if (!m.matches()) return false;
        return m.group("name").trim().equalsIgnoreCase(cardName);
    }

    /**
     * Returns {@code true} for "During each Attack Phase, [cardName] cannot be broken." — note
     * "each", so it holds in both players' Attack Phases, not only the controller's.
     */
    static boolean parseSelfCannotBeBrokenDuringAttackPhase(String effectText, String cardName) {
        Matcher m = SELF_CANNOT_BE_BROKEN_ATTACK_PHASE.matcher(effectText.trim());
        if (!m.matches()) return false;
        return m.group("name").trim().equalsIgnoreCase(cardName);
    }

    /**
     * Returns the counter name gating "If a [X] Counter is placed on [cardName], [cardName]
     * cannot be broken.", or {@code null} when the text is not that shape or names another card.
     * Both the card the counter sits on and the protected card must be the source itself.
     */
    static String parseSelfCannotBeBrokenWithCounter(String effectText, String cardName) {
        Matcher m = SELF_CANNOT_BE_BROKEN_WITH_COUNTER.matcher(effectText.trim());
        if (!m.matches()) return null;
        if (!m.group("on").trim().equalsIgnoreCase(cardName)) return null;
        if (!m.group("name").trim().equalsIgnoreCase(cardName)) return null;
        return m.group("counter").trim();
    }

    /** "If your opponent has N card(s) or less in their hand, [CardName] cannot be broken." */
    static final Pattern IF_OPP_HAND_SIZE_CANNOT_BE_BROKEN = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+has\\s+(?<n>\\d+)\\s+cards?\\s+or\\s+less\\s+in\\s+their\\s+hand,\\s+" +
        "(?<card>.+?)\\s+cannot\\s+be\\s+broken[.!]?$"
    );

    /**
     * Returns the opponent hand-size threshold for the "cannot be broken" condition,
     * or {@code -1} if the text does not match or the card name does not match.
     */
    static int parseIfOpponentHandSizeCannotBeBrokenThreshold(String effectText, String cardName) {
        Matcher m = IF_OPP_HAND_SIZE_CANNOT_BE_BROKEN.matcher(effectText.trim());
        if (!m.matches() || !m.group("card").trim().equalsIgnoreCase(cardName)) return -1;
        return Integer.parseInt(m.group("n"));
    }

    /**
     * "If either player has N cards or less in their hands, [CardName] gains …" and
     * "If both you and your opponent have no cards in hand, [CardName] gains …" — Squall 16-011L.
     *
     * <p>One pattern for both because they differ only in the quantifier: {@code either} is
     * satisfied by the smaller of the two hands, {@code both} by the larger. "no cards" is the
     * threshold-0 spelling of "0 cards or less", so it feeds the same number.
     */
    private static final Pattern HAND_SIZE_SELF_GRANT = Pattern.compile(
        "(?i)^If\\s+(?:(?<either>either\\s+player)|both\\s+you\\s+and\\s+your\\s+opponent)\\s+" +
        "(?:has|have)\\s+(?:no\\s+cards?|(?<n>\\d+)\\s+cards?\\s+or\\s+less)\\s+in\\s+(?:their\\s+)?hands?,\\s+" +
        "(?<card>[A-Za-z][A-Za-z0-9''\\-\\s()]*?)\\s+gains?\\s+(?<grant>.+?)[.!]?$"
    );

    /**
     * A hand-size-conditional self grant: while the condition holds, the printing card has
     * {@code traits} and may attack {@code maxAttacks} times.
     *
     * @param bothPlayers {@code true} for "both you and your opponent" (the larger hand must be
     *                    within {@code maxCards}); {@code false} for "either player" (the smaller)
     * @param maxCards    the hand-size ceiling the condition tests against
     * @param maxAttacks  1 when the grant carries no multi-attack permission
     */
    record HandSizeSelfGrant(boolean bothPlayers, int maxCards, Set<Trait> traits, int maxAttacks) {
        HandSizeSelfGrant {
            // EnumSet, not Set.copyOf: the latter randomises iteration order per JVM run, which
            // would leak into the rendered trait list.
            EnumSet<Trait> t = EnumSet.noneOf(Trait.class);
            t.addAll(traits);
            traits = Collections.unmodifiableSet(t);
        }

        /** Whether {@code yourHand}/{@code theirHand} satisfy this grant's condition. */
        boolean conditionMet(int yourHand, int theirHand) {
            return (bothPlayers ? Math.max(yourHand, theirHand) : Math.min(yourHand, theirHand)) <= maxCards;
        }
    }

    /**
     * Parses a {@link HandSizeSelfGrant} from {@code effectText}, or returns {@code null} when the
     * text is not one or names a card other than {@code cardName}. A grant that resolves to no
     * traits and no multi-attack permission is rejected rather than returned empty, so a caller
     * cannot mistake "parsed but grants nothing" for a live effect.
     */
    static HandSizeSelfGrant parseHandSizeSelfGrant(String effectText, String cardName) {
        Matcher m = HAND_SIZE_SELF_GRANT.matcher(effectText.trim());
        if (!m.matches() || !m.group("card").trim().equalsIgnoreCase(cardName)) return null;
        String grant = m.group("grant");
        EnumSet<Trait> traits = traitsNamedIn(grant);
        int maxAttacks = 1;
        // The multi-attack permission arrives as a quoted ability ("Squall can attack twice in the
        // same turn."), so it is read with the same pattern that parses the printed form.
        Matcher q = QUOTED_CLAUSE.matcher(grant);
        while (q.find()) {
            Matcher at = FIELD_CAN_ATTACK_TWICE.matcher(q.group(1).trim());
            if (!at.matches() || !at.group("cardname").trim().equalsIgnoreCase(cardName)) continue;
            String count = at.group("count");
            maxAttacks = Math.max(maxAttacks, count != null ? Integer.parseInt(count) : 2);
        }
        if (traits.isEmpty() && maxAttacks == 1) return null;
        int n = m.group("n") != null ? Integer.parseInt(m.group("n")) : 0;
        return new HandSizeSelfGrant(m.group("either") == null, n, traits, maxAttacks);
    }

    /** A double-quoted clause; group 1 is its contents. */
    private static final Pattern QUOTED_CLAUSE = Pattern.compile("\"([^\"]+)\"");

    /**
     * "[CardName] gains [traits] and "[quoted ability]"" — a self grant whose condition is carried
     * by the {@link FieldAbility} rather than spelled inside the text. Yumcax 18-067C and
     * Gilgamesh 18-074L both print it behind a "Damage 3 --" prefix, which
     * {@link #parseFieldAbilities} strips into {@link FieldAbility#damageThreshold()}; the callers
     * that read this grant apply that gate themselves.
     *
     * <p>A quoted clause is <em>required</em>. The traits-only spelling ("Desch gains First
     * Strike.") belongs to {@link #SELF_TRAIT_GRANT}, and letting this pattern claim it as well
     * would put one text under two parsers with no rule for which wins.
     *
     * <p>The name charset admits a comma so that a card whose own name carries one can be matched
     * — Lakshmi, Lady of Bliss 14-111R. That cannot widen what the parser claims, because the
     * captured name still has to equal the caller's card name: a sentence whose prefix now fits
     * the charset ("If you control a Card Name Zangan, Tifa gains …") captures the whole prefix
     * and fails that check, exactly as it failed the match before.
     */
    private static final Pattern SELF_GAINS_QUOTED_GRANT = Pattern.compile(
        "(?i)^(?<card>[A-Za-z][A-Za-z0-9'',\\-\\s()]*?)\\s+gains?\\s+(?<grant>[^\"]*\"[^\"]+\".*?)[.!]?$"
    );

    /**
     * "If you control N or less Forwards, …" — the board gate on Machina 15-017H's self grant,
     * whose remainder is an ordinary {@link SelfGainsQuotedGrant} ("Machina gains Brave and
     * \"When Machina attacks, deal 4000 damage to all the Forwards opponent controls.\"").
     *
     * <p>Stripped and evaluated the way the {@code Damage N --} prefix is for Yumcax 18-067C: the
     * gate is read off the board, the remainder is handed to the grant parser unchanged, and
     * nothing about the grant itself has to know a condition was there. The difference is only that
     * a damage threshold is recorded on the {@link FieldAbility} at parse time while this one
     * cannot be — the Forward count moves during the turn, so it is re-read on every lookup.
     * Group: {@code max}.
     */
    private static final Pattern IF_CONTROL_MAX_FORWARDS_PREFIX = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<max>\\d+)\\s+or\\s+less\\s+Forwards,\\s+(?<rest>\\S.*)$",
        Pattern.DOTALL
    );

    /** A {@link #IF_CONTROL_MAX_FORWARDS_PREFIX} gate and the grant sentence it guards. */
    record MaxForwardsGatedGrant(int maxForwards, String remainder) {}

    /**
     * Splits "If you control N or less Forwards, [grant]" into its gate and its grant, or returns
     * {@code null} when {@code text} is not that shape.
     */
    static MaxForwardsGatedGrant parseMaxForwardsGatedGrant(String text) {
        if (text == null) return null;
        Matcher m = IF_CONTROL_MAX_FORWARDS_PREFIX.matcher(text.trim());
        if (!m.matches()) return null;
        return new MaxForwardsGatedGrant(Integer.parseInt(m.group("max")), m.group("rest").trim());
    }

    /**
     * "If you have N or more cards in your hand, [grant]" — the hand-size twin of
     * {@link #IF_CONTROL_MAX_FORWARDS_PREFIX}, gating Lakshmi, Lady of Bliss 14-111R's quoted
     * damage modifier.
     *
     * <p>Hand size moves constantly, so like the Forward count this gate cannot be settled at parse
     * time and is re-read on every lookup. Group: {@code min}, {@code rest}.
     *
     * <p>{@link #IF_OWN_HAND_MIN_BOOST} reads the same opening for the power/trait grants
     * (Galuf 3-077H), and the two do not collide: that one is consumed by
     * {@link #parseIfControlBoosts} into an {@link IfControlBoost}, which declines a grant carrying
     * neither power nor traits — which is every grant this one exists to carry.
     */
    private static final Pattern IF_OWN_HAND_MIN_PREFIX = Pattern.compile(
        "(?i)^If\\s+you\\s+have\\s+(?<min>\\d+)\\s+or\\s+more\\s+cards?\\s+in\\s+your\\s+hand,\\s+(?<rest>\\S.*)$",
        Pattern.DOTALL
    );

    /** A {@link #IF_OWN_HAND_MIN_PREFIX} gate and the grant sentence it guards. */
    record MinHandSizeGatedGrant(int minCards, String remainder) {}

    /**
     * Splits "If you have N or more cards in your hand, [grant]" into its gate and its grant, or
     * returns {@code null} when {@code text} is not that shape.
     */
    static MinHandSizeGatedGrant parseMinHandSizeGatedGrant(String text) {
        if (text == null) return null;
        Matcher m = IF_OWN_HAND_MIN_PREFIX.matcher(text.trim());
        if (!m.matches()) return null;
        return new MinHandSizeGatedGrant(Integer.parseInt(m.group("min")), m.group("rest").trim());
    }

    /**
     * "If your opponent controls N or more dull Characters, [grant]" — Firion 21-099H, whose grant
     * is "Firion gains +5000 power, Brave and "Firion can attack twice in the same turn.""
     *
     * <p>"Characters" spans all three opposing rows, so a dull Backup counts as readily as a dull
     * Forward — the same reading {@link ScalingSelfPowerBoost}'s opponent-Character source uses.
     * Groups: {@code count}, {@code rest}.
     */
    private static final Pattern IF_OPP_DULL_CHARACTERS_PREFIX = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+controls\\s+(?<count>\\d+)\\s+or\\s+more\\s+dull\\s+Characters,\\s+" +
        "(?<rest>.+)$",
        Pattern.DOTALL
    );

    /** The gate and grant halves of an {@link #IF_OPP_DULL_CHARACTERS_PREFIX} sentence. */
    record OppDullCharsGatedGrant(int minDullCharacters, String remainder) {}

    /**
     * Splits "If your opponent controls N or more dull Characters, [grant]" into its gate and its
     * grant, or {@code null} when {@code text} is not that shape.
     *
     * <p>A splitter rather than a grant parser, exactly like {@link #parseMaxForwardsGatedGrant}:
     * the remainder is an ordinary self grant, so the power, trait and multi-attack halves are each
     * read by the parser that already owns them instead of this one learning to read all three.
     */
    static OppDullCharsGatedGrant parseOppDullCharsGatedGrant(String text) {
        if (text == null) return null;
        Matcher m = IF_OPP_DULL_CHARACTERS_PREFIX.matcher(text.trim());
        if (!m.matches()) return null;
        return new OppDullCharsGatedGrant(Integer.parseInt(m.group("count")), m.group("rest").trim());
    }

    /**
     * A conditional self grant carrying a quoted ability: while its condition holds, the printing
     * card has {@code traits}, may attack {@code maxAttacks} times, and has {@code abilityTexts}
     * as auto abilities of its own.
     *
     * @param maxAttacks   1 when the grant carries no multi-attack permission
     * @param abilityTexts quoted clauses {@link #parseAutoAbilities} recognises as triggered
     *                     abilities; the multi-attack permission is not among them, since it is a
     *                     rules permission rather than a trigger and is reported separately
     */
    record SelfGainsQuotedGrant(Set<Trait> traits, int maxAttacks, List<String> abilityTexts,
            List<String> passiveTexts) {
        SelfGainsQuotedGrant {
            // EnumSet, not Set.copyOf — the latter randomises iteration order per JVM run, which
            // would leak into the rendered trait list.
            EnumSet<Trait> t = EnumSet.noneOf(Trait.class);
            t.addAll(traits);
            traits       = Collections.unmodifiableSet(t);
            abilityTexts = List.copyOf(abilityTexts);
            passiveTexts = List.copyOf(passiveTexts);
        }

        /** Compatibility constructor for the trigger-only form; no passive clauses. */
        SelfGainsQuotedGrant(Set<Trait> traits, int maxAttacks, List<String> abilityTexts) {
            this(traits, maxAttacks, abilityTexts, List.of());
        }
    }

    /**
     * "[Self] cannot be blocked." — the standing restriction Ritz 11-063L hands itself inside a
     * quoted grant. Read per block-legality check by {@code MainWindow.attackerConditionallyUnblockable}.
     */
    private static final Pattern SELF_CANNOT_BE_BLOCKED = Pattern.compile(
        "(?i)^(?<name>.+?)\\s+cannot\\s+be\\s+blocked[.!]?$"
    );

    /** Whether {@code clause} is "[cardName] cannot be blocked." naming its own carrier. */
    static boolean isSelfCannotBeBlocked(String clause, String cardName) {
        if (clause == null || cardName == null) return false;
        Matcher m = SELF_CANNOT_BE_BLOCKED.matcher(clause.trim());
        return m.matches() && m.group("name").trim().equalsIgnoreCase(cardName);
    }

    /**
     * "If [Self] forms a party, that party cannot be blocked." — Black Chocobo 3-054C.
     *
     * <p>Its own pattern rather than an arm of {@link #SELF_CANNOT_BE_BLOCKED}, because what it
     * shields is not the printing card: the subject of "cannot be blocked" is the party. The
     * carrier's name only says whose presence in a party turns it on, which is why the reader has
     * to ask the board for a declared party before honouring it.
     */
    private static final Pattern SELF_PARTY_CANNOT_BE_BLOCKED = Pattern.compile(
        "(?i)^If\\s+(?<name>.+?)\\s+forms\\s+a\\s+party,\\s+that\\s+party\\s+cannot\\s+be\\s+blocked[.!]?$"
    );

    /** Whether {@code clause} is the party-wide unblockable naming {@code cardName} as its member. */
    static boolean isSelfPartyCannotBeBlocked(String clause, String cardName) {
        if (clause == null || cardName == null) return false;
        Matcher m = SELF_PARTY_CANNOT_BE_BLOCKED.matcher(clause.trim());
        return m.matches() && m.group("name").trim().equalsIgnoreCase(cardName);
    }

    /**
     * "The damage dealt to [Self] is reduced by N instead." — Charlotte 13-023R's quoted half. The
     * passive spelling of a damage modifier, with the subject in the object position rather than
     * the subject one.
     */
    private static final Pattern SELF_INCOMING_DAMAGE_REDUCED = Pattern.compile(
        "(?i)^The\\s+damage\\s+dealt\\s+to\\s+(?<name>.+?)\\s+is\\s+reduced\\s+by\\s+(?<amount>\\d+)\\s+instead[.!]?$"
    );

    /**
     * "If [Self] receives damage, the damage is reduced by N instead." — Lion 10-123R's quoted
     * half, and the passive-voice twin of {@link #SELF_INCOMING_DAMAGE_REDUCED}.
     *
     * <p>{@code FA_DAMAGE_MODIFIER} already reads "receives damage" as a synonym for "is dealt
     * damage", but only ahead of the <em>active</em> effect wording ("reduce the damage by N
     * instead"). This sentence states the same replacement passively, which is why it needs a
     * rewrite rather than simply reaching that pattern. Both openings are accepted here so the
     * two halves of the synonym stay together.
     */
    private static final Pattern SELF_RECEIVES_DAMAGE_REDUCED = Pattern.compile(
        "(?i)^If\\s+(?<name>.+?)\\s+(?:receives|is\\s+dealt)\\s+damage,\\s+the\\s+damage\\s+is\\s+" +
        "reduced\\s+by\\s+(?<amount>\\d+)\\s+instead[.!]?$"
    );

    /**
     * {@code clause} rewritten into the canonical damage-modifier wording when it is the passive
     * spelling naming {@code cardName}, or {@code null} when it is not one.
     *
     * <p>Rewritten rather than given its own pattern, on the same reasoning as
     * {@link #splitGrantWithDamageRider}: the effect is one {@code FA_DAMAGE_MODIFIER} already
     * knows how to apply, and a second pattern for it would be a second place to keep the source
     * clauses and the reduce/set/increase arithmetic in step.
     */
    static String canonicalSelfDamageModifier(String clause, String cardName) {
        if (clause == null || cardName == null) return null;
        String t = clause.trim();
        for (Pattern p : new Pattern[]{ SELF_INCOMING_DAMAGE_REDUCED, SELF_RECEIVES_DAMAGE_REDUCED }) {
            Matcher m = p.matcher(t);
            if (!m.matches() || !m.group("name").trim().equalsIgnoreCase(cardName)) continue;
            return "If " + cardName + " is dealt damage, reduce the damage by "
                    + m.group("amount") + " instead.";
        }
        return null;
    }

    /**
     * Parses a {@link SelfGainsQuotedGrant} from {@code effectText}, or {@code null} when the text
     * is not one or names a card other than {@code cardName}. A grant that resolves to nothing at
     * all is rejected rather than returned empty, so a caller cannot mistake "parsed but grants
     * nothing" for a live effect — the same guard {@link #parseHandSizeSelfGrant} applies.
     */
    static SelfGainsQuotedGrant parseSelfGainsQuotedGrant(String effectText, String cardName) {
        if (effectText == null || cardName == null) return null;
        Matcher m = SELF_GAINS_QUOTED_GRANT.matcher(effectText.trim());
        if (!m.matches() || !m.group("card").trim().equalsIgnoreCase(cardName)) return null;
        String grant = m.group("grant");

        // Traits come from outside the quotation only. A quoted clause is a different card's
        // ability or a grant to a whole field ("Aranea gains \"The Forwards you control gain
        // Haste.\"" — 11-086L), and scanning the quotation for trait words hands the printing card
        // a trait it was only handing out.
        String outside = QUOTED_CLAUSE.matcher(grant).replaceAll(" ");
        // A power boost in the same breath is read off the same sentence by parseSelfPowerGrant, so
        // it no longer forces the grant to decline — but it must not be mistaken for a trait, which
        // is why the traits are still taken from the de-quoted text alone.
        EnumSet<Trait> traits = traitsNamedIn(outside);

        int maxAttacks = 1;
        List<String> abilities = new ArrayList<>();
        List<String> passives  = new ArrayList<>();
        Matcher q = QUOTED_CLAUSE.matcher(grant);
        while (q.find()) {
            String clause = q.group(1).trim();
            // Standing restrictions and passives the card hands itself. They carry no trigger, so
            // parseAutoAbilities rejects them and the whole grant used to decline.
            //
            // Only clauses with a reader are accepted here. A passive nobody consults would let the
            // grant's other half (traits, power) apply while the quoted ability silently did
            // nothing — the half-an-ability failure the decline below exists to prevent.
            if (isSelfCannotBeBlocked(clause, cardName)) { passives.add(clause); continue; }
            // "If [Self] deals damage to a Forward or your opponent, double the damage instead."
            // — Kefka 23-004R. Every reader of the doubler goes through selfPassiveClauses, so a
            // granted copy is seen wherever a printed one is.
            Matcher ddM = AutoAbilityTriggers.FA_OUTGOING_DAMAGE_DOUBLER.matcher(clause);
            if (ddM.matches() && ddM.group("card").trim().equalsIgnoreCase(cardName)) {
                passives.add(clause);
                continue;
            }
            String canonical = canonicalSelfDamageModifier(clause, cardName);
            if (canonical != null) { passives.add(canonical); continue; }
            // Already in the canonical incoming-damage wording (The Fiend 20-114L), so it needs no
            // rewrite — the same DamageResolver scan reads it as printed.
            Matcher dmgM = AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(clause);
            if (dmgM.find() && dmgM.group("card").trim().equalsIgnoreCase(cardName)) {
                passives.add(clause);
                continue;
            }
            // The multi-attack permission arrives quoted, and is read with the same pattern that
            // parses the printed form — as parseHandSizeSelfGrant does for Squall 16-011L.
            Matcher at = FIELD_CAN_ATTACK_TWICE.matcher(clause);
            if (at.matches() && at.group("cardname").trim().equalsIgnoreCase(cardName)) {
                String count = at.group("count");
                maxAttacks = Math.max(maxAttacks, count != null ? Integer.parseInt(count) : 2);
                continue;
            }
            // Anything else has to be a trigger-bearing ability, which parseAutoAbilities is the
            // authority on. A clause that is neither declines the whole grant: granting half of it
            // and reporting the text as handled is worse than leaving it visibly unparsed, which
            // is the rule permanentGrantForClause follows for the same situation.
            if (parseAutoAbilities(clause).isEmpty()) return null;
            abilities.add(clause);
        }
        if (traits.isEmpty() && maxAttacks == 1 && abilities.isEmpty() && passives.isEmpty()
                && parseSelfPowerGrant(effectText, cardName) == 0)
            return null;
        return new SelfGainsQuotedGrant(traits, maxAttacks, abilities, passives);
    }

    /**
     * Every clause of {@code effectText} a passive reader should be offered for {@code cardName}:
     * the sentence as printed, followed by any passive the card hands itself inside quotes.
     *
     * <p>The printed text comes first and is always present, so a reader that walks this list
     * behaves exactly as it did when it read {@code fa.effectText()} directly — the quoted clauses
     * only add cases it used to miss. Kefka 23-004R is why it exists: his doubler is printed
     * inside a "Kefka gains +2000 power, Haste and \"…\"" grant, and no damage pattern matches the
     * outer sentence the clause is nested in.
     */
    static List<String> selfPassiveClauses(String effectText, String cardName) {
        if (effectText == null) return List.of();
        SelfGainsQuotedGrant grant = parseSelfGainsQuotedGrant(effectText, cardName);
        if (grant == null || grant.passiveTexts().isEmpty()) return List.of(effectText);
        List<String> out = new ArrayList<>();
        out.add(effectText);
        out.addAll(grant.passiveTexts());
        return out;
    }

    /**
     * "If a [Card Name X] Forward you control [other than Y] is dealt damage, the damage is dealt
     * to Y instead." — Daisy 18-060H (bare, with an exclusion) and Tidus 26-112H (Card Name
     * filtered, no exclusion).
     */
    private static final Pattern FRIENDLY_DAMAGE_REDIRECT = Pattern.compile(
        "(?i)^If\\s+a\\s+(?:Card\\s+Name\\s+(?<cardname>[A-Za-z][A-Za-z0-9''\\-\\s()]*?)\\s+)?" +
        "Forward\\s+you\\s+control(?:\\s+other\\s+than\\s+(?<except>[A-Za-z][A-Za-z0-9''\\-\\s()]*?))?\\s+" +
        "is\\s+dealt\\s+damage,\\s+the\\s+damage\\s+is\\s+dealt\\s+to\\s+(?<to>[A-Za-z][A-Za-z0-9''\\-\\s()]*?)\\s+instead[.!]?$"
    );

    /**
     * Which of its controller's Forwards a {@link #parseDamageRedirectGrant} printing stands in for.
     * The stand-in is always the printing card itself, so only the filter is carried.
     */
    record DamageRedirectGrant(String cardNameFilter, String exceptCardName) {
        /** Whether damage dealt to {@code c} is taken by the printing card instead. */
        boolean coversCard(CardData c) {
            if (c == null || !c.isForward()) return false;
            if (exceptCardName != null && CardFilters.meetsCardNameFilter(c, exceptCardName)) return false;
            return CardFilters.meetsCardNameFilter(c, cardNameFilter);
        }
    }

    /**
     * Parses a friendly-damage redirect, or returns {@code null} when the text is not one or names
     * a stand-in other than {@code cardName}. The name check is what keeps the redirect self-
     * targeted: the effect only ever moves damage onto the card that prints it.
     */
    static DamageRedirectGrant parseDamageRedirectGrant(String effectText, String cardName) {
        Matcher m = FRIENDLY_DAMAGE_REDIRECT.matcher(effectText.trim());
        if (!m.matches() || !m.group("to").trim().equalsIgnoreCase(cardName)) return null;
        return new DamageRedirectGrant(
                m.group("cardname") != null ? m.group("cardname").trim() : null,
                m.group("except")   != null ? m.group("except").trim()   : null);
    }

    private static final Pattern PRIMING_PATTERN = Pattern.compile(
        "(?i)Priming\\s+\"([^\"]+)\"\\s*--\\s*((?:《[^》]*》\\s*)*)"
    );

    // Matches individual 《symbol》 cost tokens
    private static final Pattern CP_TOKEN = Pattern.compile("《([^》]*)》");

    // Maps element abbreviations (and full names) to canonical element strings
    private static final Map<String, String> ELEM_SYM;
    static {
        ELEM_SYM = new HashMap<>();
        ELEM_SYM.put("F",          "Fire");
        ELEM_SYM.put("I",          "Ice");
        ELEM_SYM.put("W",          "Wind");
        ELEM_SYM.put("E",          "Earth");
        ELEM_SYM.put("L",          "Lightning");
        ELEM_SYM.put("U",          "Water");
        ELEM_SYM.put("D",          "Dark");
        ELEM_SYM.put("G",          "Light");
        ELEM_SYM.put("FIRE",       "Fire");
        ELEM_SYM.put("ICE",        "Ice");
        ELEM_SYM.put("WIND",       "Wind");
        ELEM_SYM.put("EARTH",      "Earth");
        ELEM_SYM.put("LIGHTNING",  "Lightning");
        ELEM_SYM.put("WATER",      "Water");
        ELEM_SYM.put("DARK",       "Dark");
        ELEM_SYM.put("LIGHT",      "Light");
    }

    // -------------------------------------------------------------------------
    // Warp parsing
    // -------------------------------------------------------------------------

    /** Parses the Warp value (X) from card text; returns 0 if absent. */
    public static int parseWarpValue(String textEn) {
        if (textEn == null) return 0;
        Matcher m = WARP_PATTERN.matcher(textEn);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Parses the Warp alternate cost; numeric tokens expand to N generic ("") entries. */
    public static List<String> parseWarpCost(String textEn) {
        if (textEn == null) return List.of();
        Matcher m = WARP_PATTERN.matcher(textEn);
        if (!m.find()) return List.of();
        return parseCpTokens(m.group(2));
    }

    // -------------------------------------------------------------------------
    // Priming parsing
    // -------------------------------------------------------------------------

    /** Parses the Priming target card name; returns empty string if absent. */
    public static String parsePrimingTarget(String textEn) {
        if (textEn == null) return "";
        Matcher m = PRIMING_PATTERN.matcher(textEn);
        return m.find() ? m.group(1).trim() : "";
    }

    /** Parses the Priming cost; numeric tokens expand to N generic ("") entries. */
    public static List<String> parsePrimingCost(String textEn) {
        if (textEn == null) return List.of();
        Matcher m = PRIMING_PATTERN.matcher(textEn);
        if (!m.find()) return List.of();
        return parseCpTokens(m.group(2));
    }

    /**
     * "If you control N or more Characters, [Name] can prime to pay [cost] instead of paying the
     * Priming cost." — Dion 29-106H, the only printing that discounts a Priming cost.
     *
     * <p>A replacement, not a reduction: what it names is the whole cost to pay in place of the
     * printed one, which is why the parsed tokens stand on their own rather than being subtracted
     * from anything. Groups: {@code count}, {@code card}, {@code cost}.
     */
    private static final Pattern PRIMING_COST_DISCOUNT_PATTERN = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<count>\\d+)\\s+or\\s+more\\s+Characters,\\s+" +
        "(?<card>.+?)\\s+can\\s+prime\\s+to\\s+pay\\s+(?<cost>(?:《[^》]*》\\s*)+)" +
        "instead\\s+of\\s+paying\\s+the\\s+Priming\\s+cost[.!]?\\s*$"
    );

    /**
     * A Priming cost a card may pay in place of its printed one, and the board it needs to do so.
     *
     * @param minCharacters how many Characters its controller must control
     * @param cost          the replacement cost, in the same token form as {@link #primingCost()}
     */
    public record PrimingCostDiscount(int minCharacters, List<String> cost) {
        public PrimingCostDiscount { cost = List.copyOf(cost); }
    }

    /**
     * The Priming cost discount {@code seg} declares for {@code cardName}, or {@code null} when it
     * declares none or names another card.
     *
     * <p>Name-checked against its carrier because the sentence names the card that primes, and a
     * card's own name in its own text means that card. The board condition is the caller's to
     * check — a {@link CardData} cannot count Characters.
     */
    public static PrimingCostDiscount parsePrimingCostDiscount(String seg, String cardName) {
        if (seg == null || cardName == null) return null;
        Matcher m = PRIMING_COST_DISCOUNT_PATTERN.matcher(seg.trim());
        if (!m.matches() || !m.group("card").trim().equalsIgnoreCase(cardName)) return null;
        List<String> cost = parseCpTokens(m.group("cost"));
        return cost.isEmpty() ? null
                : new PrimingCostDiscount(Integer.parseInt(m.group("count")), cost);
    }

    /** Shared CP-token parser used by both Warp and Priming cost parsing. */
    private static List<String> parseCpTokens(String costPart) {
        List<String> result = new ArrayList<>();
        Matcher cpM = CP_TOKEN.matcher(costPart);
        while (cpM.find()) {
            String sym = cpM.group(1).trim();
            if (sym.matches("\\d+")) {
                int n = Integer.parseInt(sym);
                for (int i = 0; i < n; i++) result.add("");
            } else {
                result.add(ELEM_SYM.getOrDefault(sym.toUpperCase(), sym));
            }
        }
        return List.copyOf(result);
    }

    // -------------------------------------------------------------------------
    // Action / Special Ability parsing
    // -------------------------------------------------------------------------

    /**
     * Matches action abilities in card text.  The groups are:
     * <ol>
     *   <li>Special ability name (optional) — content of {@code [[s]]…[[/]]}</li>
     *   <li>Zero or more {@code 《token》} CP-cost sequences</li>
     *   <li>Optional "put … into the Break Zone" cost phrase</li>
     *   <li>Effect text — everything after {@code :} up to the next markup tag or end</li>
     * </ol>
     * A lookahead after the optional {@code [[s]]} header ensures the cost section
     * begins with either a {@code 《} token or the word {@code put}, preventing
     * spurious matches on arbitrary colons in card text.
     */
    private static final Pattern ACTION_ABILITY_PATTERN = Pattern.compile(
        "(?i)(?:Damage\\s+(\\d+)\\s+--\\s+)?"                               +  // group 1: optional Damage N -- threshold
        "(?:(?i)\\[\\[s\\]\\]\\s*([^\\[]+?)\\s*\\[\\[/\\]\\]\\s*)?"        +  // group 2: optional [[s]]Name[[/]]
        "(?=(?:《|(?i:put)\\b|(?i:discard)\\b|(?i:remove)\\b|(?i:return)\\b|(?i:dull)\\b))" + // lookahead: must start with 《, put, discard, remove, return, or dull
        "((?:《[^》]*》\\s*)*)"                                            +  // group 3: zero or more 《cost》 tokens
        "(?:\\s*\\(This cost is reduced by 1 for each Job (?<inlinejob>[^)]+?) other than (?<inlineexclude>[^)]+?) you control\\.\\))?" + // groups 4-5 (named): optional inline cost modifier
        "((?i)(?:,\\s*)?put\\s+(?:(?!\\[\\[br\\]\\]).)+?\\s+into\\s+the\\s+Break\\s+Zone\\s*)?"  + // group 6: optional BZ cost phrase
        "((?i)(?:,\\s*)?discard(?:(?!,\\s*(?:remove|return)\\b)[^:\\[])+)?"  +  // group 7: optional discard cost phrase (never crosses [[…]] markup)
        "((?i)(?:,\\s*)?remove\\s+[^:]+?\\s+from\\s+(?:the\\s+)?game\\s*)?" + // group 8: optional remove-from-game cost phrase
        "((?i)(?:,\\s*)?return\\s+[^:]+?\\s+to\\s+(?:its|their)\\s+owner(?:'s|s')?\\s+hand\\s*)?" + // group 9: optional return-to-hand cost phrase
        // Neither half may cross [[…]] markup, for the reason the Break Zone and discard groups
        // above may not: a counter-removal *sentence* elsewhere on the card ("You can remove 3 Reel
        // Counters from Wakka to use …", 16-138S) otherwise runs its lazy tail through [[br]] and
        // the next ability's [[s]]…[[/]] header to reach that ability's colon, swallowing the
        // header and the 《》 costs with it. Wakka escapes only because a comma happens to sit in
        // between; a printing without one would lose its Special entirely.
        "((?i)(?:,\\s*)?remove\\s+(?:\\d+|X)\\s+[^:\\[]+?\\s+Counters?\\s+from\\s+[^:,\\[]+?\\s*)?" + // group 10: optional counter-removal cost phrase ("remove X …" defers the amount to activation)
        "(?<dullcost>(?i)(?:,\\s*)?Dull\\s+(?:a\\s+total\\s+of\\s+)?(?<dullcount>\\d+)?\\s*(?<dullcond>active|dull|damaged)?\\s*" + // group 11 (named): optional Dull N? [cond] Forward(s) cost — simple, Card Name, or bare-name form
        // The card-type suffix is optional: Monk 10-084C prints "Dull 1 active Card Name Monk:"
        // with no "Forward" after the name, and requiring it dropped the whole ability.
        "(?:Card\\s+Name\\s+.+?(?:\\s+(?:Forwards?|Backups?|Monsters?|Characters?))?" +      // named-card branch: "Dull N [cond] Card Name X [Forward] [and N [cond] Card Name Y Forward]"
        "(?:\\s+and\\s+\\d+\\s*(?:active|dull|damaged)?\\s*Card\\s+Name\\s+.+?(?:\\s+(?:Forwards?|Backups?|Monsters?|Characters?))?)*" +
        "|Category\\s+(?<dullcat>[A-Za-z0-9][A-Za-z0-9\\s''\\-]*?)(?:\\s+(?:Forwards?|Backups?|Monsters?|Characters?))?" + // category branch
        "|Job\\s+(?<dulljob>[A-Za-z][A-Za-z''\\s\\-]*?)(?:\\s+(?:Forwards?|Backups?|Monsters?|Characters?))?(?:\\s+(?:and/)?or\\s+Card\\s+Name\\s+[^:]+?)?" + // job branch: "Dull N [cond] Job X [and/or Card Name Y]"
        "|(?<dullelem>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)?\\s*" + // standard branch
        "(?:Forwards?(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)?\\s*Backups?)?" + // Forwards [or Backups]
        "|Backups?(?:\\s+of\\s+the\\s+same\\s+Element)?(?:\\s+or\\s+\\d+\\s*(?:active|dull|damaged)?\\s*Backups?\\s+of\\s+the\\s+same\\s+Element(?:\\s+and\\s+[A-Za-z][A-Za-z\\s''\\-]*?)?)?" + // Backups [of the same Element] [or N Backups ... and Name]
        "|Characters?)" + // Characters
        "|(?<dullbarename>(?-i:[A-Z])[A-Za-z''\\-]+)(?:\\s+(?:Forwards?|Backups?|Monsters?|Characters?))?(?:\\s+and\\s+[^:\\[]+)?)" + // bare-name branch: "Dull [cond] CardName [and N [cond] ...]"
        // "other than [Name]" applies to whichever branch matched — Steiner 4-129L to the standard
        // one, Penelo 17-057H to the Job one. Written once outside the alternation, and ahead of the
        // closing \s* so the cost still ends where the colon expects it. Without it the phrase ran
        // past the end of the cost group and the whole ability failed to match.
        "(?:\\s+other\\s+than\\s+(?<dullexcept>[^:,]+?))?\\s*)?" +
        // Appended after every numbered cost group on purpose: a group inserted higher up would
        // renumber groups 6-11, which the parse site reads positionally.
        "(?<bottomdeckcost>(?i)(?:,\\s*)?put\\s+(?<bottomdeckname>[^:,]+?)\\s+at\\s+the\\s+bottom\\s+of\\s+" +
        "(?:its|their)\\s+owner(?:'s|s')?\\s+deck\\s*)?" +                      // optional "put [self] at the bottom of its owner's deck" cost
        // Optional "reveal N [filter] in your hand" cost (Rinoa 18-097R, "《S》, reveal 1 Forward in
        // your hand:"). Named and appended for the same reason bottomdeckcost is: groups 6-11 are
        // read positionally, so a group inserted above would renumber them. Every group between the
        // 《》 tokens and here is optional, so the phrase still matches in its printed position.
        "(?<revealcost>(?i)(?:,\\s*)?reveal\\s+(?<revealcount>\\d+)\\s+(?<revealwhat>[^:,]+?)\\s+" +
        "in\\s+your\\s+hand\\s*)?" +
        ":\\s*"                                                              +  // colon separator
        "(?<effecttext>(?:[^\\[]|\\[(?!\\[))*)"                                // effect text (up to next [[markup]])
    );

    // Captures the content between "put " and " into the Break Zone"
    private static final Pattern BREAK_ZONE_COST_PATTERN = Pattern.compile(
        "(?i)put\\s+(.+?)\\s+into\\s+the\\s+Break\\s+Zone"
    );

    /**
     * Matches "Dull [cond] CardName [Forward] [and continuation]" — the bare-name cost form where
     * count is implicit (1) and the card is named directly without a "Card Name" prefix.
     * Groups: {@code cond}, {@code barename}, {@code continuation} (text after "and", may be null).
     */
    private static final Pattern DULL_BARE_NAME_COST_PATTERN = Pattern.compile(
        "(?i)Dull\\s+(?<cond>active|dull|damaged)?\\s*" +
        "(?<barename>[A-Z][A-Za-z''\\-]+)(?:\\s+(?:Forwards?|Backups?|Monsters?|Characters?))?" +
        "(?:\\s+and\\s+(?<continuation>.+))?"
    );

    /**
     * Where one dull cost item ends: the end of the cost text, the comma or "and" that introduces
     * the next item. Used as a lookahead so a lazy Job or Category name cannot stop short of it.
     */
    private static final String DULL_ITEM_END = "(?=\\s*(?:,|and\\b|$))";

    /**
     * Matches a single dull cost item: Card Name, Category, Job, or element-filtered
     * Forward/Backup/Character.
     *
     * <p>The Job and Category names are lazy and everything that can follow them — the card-type
     * suffix, the "or Card Name X" alternative — is optional, so both need {@link #DULL_ITEM_END}
     * behind them to force the name out to its full width. Without it the shortest possible name
     * wins and the whole item still matches: "Dull 4 active Job Warrior of Light" captured the job
     * as "W" (19-102L Refia), "Dull 2 active Category VII Characters" the category as "V", and
     * since {@code dullForwardCostMatches} compares those against real Jobs and Categories, the cost could
     * never be paid and the ability was unusable. Unlike the enclosing
     * {@code ACTION_ABILITY_PATTERN}, which ends on the cost's colon and so backtracks the name
     * out on its own, this pattern is run over the extracted cost text with nothing behind it.
     */
    private static final Pattern DULL_COST_ITEM_PATTERN = Pattern.compile(
        // "and" leads every item after the first: the cost text says "Dull" once and joins the rest
        // with it (Ceodore 11-117R, "Dull 1 active Card Name Cecil Forward and 1 active Card Name
        // Rosa Forward"). The scan is a find() loop, so without this arm it stopped after the item
        // the word "Dull" introduced and the second Forward was never required. A digit has to
        // follow, which is what keeps it off the "and" inside an item's own filter text.
        "(?i)(?:Dull|and)\\s+(?:a\\s+total\\s+of\\s+)?(?<count>\\d+)\\s*(?<cond>active|dull|damaged)?\\s*" +
        // Suffix optional for the same reason ACTION_ABILITY_PATTERN's named-card branch makes it
        // optional; the trailing DULL_ITEM_END still forces the lazy name out to its full width.
        // The "or Card Name Y" alternative is the Job branch's {@code joborcardname} in the shape
        // Cloud 29-005L prints it ("dull 1 active Card Name Tifa or Card Name Aerith"); without it
        // the lazy name grew straight through the alternative into a name no card has.
        "(?:Card\\s+Name\\s+(?<cardname>.+?)(?:\\s+(?:Forwards?|Backups?|Monsters?|Characters?))?" +
        "(?:\\s+or\\s+Card\\s+Name\\s+(?<cardorname>.+?)(?:\\s+(?:Forwards?|Backups?|Monsters?|Characters?))?)?" +
        "|Category\\s+(?<category>[A-Za-z0-9][A-Za-z0-9\\s''\\-]*?)(?:\\s+(?:Forwards?|Backups?|Monsters?|(?<catchar>Characters?)))?" + DULL_ITEM_END +
        // The Job branch takes its own exclusion group, ahead of the item-end lookahead: that
        // lookahead is what forces the greedy card-name capture to keep growing, and it grew right
        // through "other than Penelo" (17-057H) into a name no card has.
        "|Job\\s+(?<job>[A-Za-z][A-Za-z''\\s\\-]*?)(?:\\s+(?:Forwards?|Backups?|Monsters?|(?<jobchar>Characters?)))?(?:\\s+(?:and/)?or\\s+Card\\s+Name\\s+(?<joborcardname>.+?))?(?:\\s+other\\s+than\\s+(?<jobexcept>[^:,]+?))?" + DULL_ITEM_END +
        "|(?<elem>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)?\\s*" +
        "(?:Forwards?(?<orbackup>\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)?\\s*Backups?)?" + // Forwards [or Backups]
        "|(?<sameelembackup>Backups?(?:\\s+of\\s+the\\s+same\\s+Element)?)" + // Backups [of same element] — e.g. Yuri
        "|(?<stdchar>Characters?)))" +
        // The exclusion the cost group also carries, re-read here because this pattern parses the
        // raw cost text on its own. Lazy Job/Card Name captures give it up rather than swallowing
        // it, which is what left Penelo 17-057H with a card name of "Dancer other than Penelo".
        "(?:\\s+other\\s+than\\s+(?<except>[^:,]+?))?(?=\\s*(?:,|and\\b|$))"
    );

    private static final Pattern SELF_MILL_COST_PATTERN = Pattern.compile(
        "(?i)put\\s+the\\s+top\\s+(\\d+)\\s+cards?\\s+of\\s+your\\s+deck\\s+into\\s+the\\s+Break\\s+Zone"
    );

    /** Detects whether an ability effect targets a Forward blocking a specific named card or job. */
    private static final Pattern HAS_BLOCKING_TARGET_EFFECT_PATTERN = Pattern.compile(
        "(?i)Choose\\s+\\d+\\s+(?:Forward|Character)s?\\s+blocking\\s+"
    );

    private static final Pattern DISCARD_COST_PATTERN = Pattern.compile(
        "(?i)(?:,\\s*)?discard\\s+(?<count>\\d+)\\s+" +
        "(?:" +
            "Card\\s+Name\\s+(?<cardname>.+)"    +                        // "Card Name X"
        "|" +
            "Category\\s+(?<category>\\S+)\\s+(?<typecat>Characters?|Forwards?|Backups?|Monsters?|Summons?)" + // "Category VI Characters"
        "|" +
            "(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+cards?" + // "Water card"
        "|" +
            "(?<type>Summons?|Forwards?|Backups?|Monsters?|Characters?)" + // type only
        "|" +
            "cards?(?<different>,\\s*each\\s+of\\s+a\\s+different\\s+card\\s+type)?" + // "card(s)"
        ")"
    );

    /**
     * Parses all Action and Special Abilities from {@code textEn}.
     *
     * <p>Each ability follows the format {@code [[[s]]Name[[/]]] CostTokens: EffectText}.
     * {@code 《Dull》} tokens set {@link ActionAbility#requiresDull()};
     * {@code 《S》} tokens and the presence of {@code [[s]]…[[/]]} set
     * {@link ActionAbility#isSpecial()}.
     * All other tokens are mapped to element names via {@link #ELEM_SYM}, with numeric
     * tokens expanding to that many generic {@code ""} entries in {@link ActionAbility#cpCost()}.
     */
    public static List<ActionAbility> parseActionAbilities(String textEn) {
        if (textEn == null || textEn.isBlank()) return List.of();
        // Collapse "select N of M following actions" headers with their [[br]]-delimited quoted
        // sub-actions onto one line so the effect group captures them (the same join used by
        // parseAutoAbilities / parseFieldAbilities); otherwise the effect truncates at [[br]].
        textEn = joinSelectActions(textEn);
        List<ActionAbility> result = new ArrayList<>();

        // Extract "If you have Card Name X in your Break Zone, Y gains 'ab1' [and 'ab2']" patterns
        // before normal matching so the quoted abilities don't get parsed without their BZ condition.
        Matcher bzGainsM = IF_OWN_BZ_GAINS_PATTERN.matcher(textEn);
        StringBuffer sbBz = new StringBuffer();
        while (bzGainsM.find()) {
            String bzCard = bzGainsM.group("bzcard").trim();
            Matcher qM = IF_CTRL_EFFECT_QUOTED.matcher(bzGainsM.group("quotedAbilities"));
            while (qM.find()) {
                for (ActionAbility inner : parseActionAbilities(qM.group(1))) {
                    result.add(withOwnBzNameRequired(inner, bzCard));
                }
            }
            bzGainsM.appendReplacement(sbBz, "");
        }
        bzGainsM.appendTail(sbBz);
        textEn = sbBz.toString();

        // Strip quoted abilities that a card grants to a Forward (e.g. Medusa's "《5》: Remove all
        // Petrification Counters…", Machinist's "《Dull》: Choose 1 Forward. Deal it 4000 damage.").
        // Such a grant is an ability handed to some OTHER card, not one of THIS card's own action
        // abilities, so remove it before ACTION_ABILITY_PATTERN runs — otherwise its 《cost》: is
        // mis-read as the source card's own ability. Matched narrowly: a quote that OPENS with one or
        // more 《cost》 tokens then a colon, and does not span [[…]] markup — so it can't swallow a
        // card's real [[s]] special ability that merely sits inside a wider quoted span. (Self-grants
        // gated on the Break Zone were already extracted above and are gone by now.)
        textEn = textEn.replaceAll("\"(?:\\s*《[^》]*》)+\\s*:[^\"\\[]*\"", "");

        Matcher m = ACTION_ABILITY_PATTERN.matcher(textEn);
        while (m.find()) {
            String thresholdStr  = m.group(1);
            int    damageThreshold = thresholdStr != null ? Integer.parseInt(thresholdStr) : 0;
            String rawName       = m.group(2);
            String costPart      = m.group(3);
            String bzRaw         = m.group(6);
            String discardRaw    = m.group(7);
            String removeRaw     = m.group(8);
            String returnRaw     = m.group(9);
            String counterRaw    = m.group(10);
            String dullCostRaw   = m.group("dullcost");
            String bottomDeckRaw = m.group("bottomdeckcost");
            String revealRaw     = m.group("revealcost");
            String effectRaw     = DAMAGE_THRESHOLD_REMINDER_PAREN.matcher(m.group("effecttext").trim()).replaceAll("").trim();
            if (effectRaw.isEmpty()) continue;
            // Skip if there are no CP tokens or any non-CP cost phrase (spurious match)
            if ((costPart == null || costPart.isBlank()) && bzRaw == null && discardRaw == null
                    && removeRaw == null && returnRaw == null && counterRaw == null
                    && dullCostRaw == null && bottomDeckRaw == null && revealRaw == null) continue;

            String  abilityName  = rawName != null ? rawName.trim() : "";
            boolean isSpecial    = !abilityName.isEmpty();
            boolean requiresDull = false;
            boolean hasXCost     = false;
            int     crystalCost  = 0;
            List<String> cpCost  = new ArrayList<>();

            if (costPart != null) {
                Matcher cpM = CP_TOKEN.matcher(costPart);
                while (cpM.find()) {
                    String sym = cpM.group(1).trim();
                    if ("Dull".equalsIgnoreCase(sym)) {
                        requiresDull = true;
                    } else if ("S".equalsIgnoreCase(sym)) {
                        isSpecial = true;
                    } else if ("C".equalsIgnoreCase(sym)) {
                        crystalCost++;
                    } else if ("X".equalsIgnoreCase(sym)) {
                        hasXCost = true;
                    } else if (sym.matches("\\d+")) {
                        int n = Integer.parseInt(sym);
                        for (int i = 0; i < n; i++) cpCost.add("");
                    } else {
                        cpCost.add(ELEM_SYM.getOrDefault(sym.toUpperCase(), sym));
                    }
                }
            }

            int selfMillCost = 0;
            if (bzRaw != null) {
                Matcher smm = SELF_MILL_COST_PATTERN.matcher(bzRaw);
                if (smm.find()) {
                    selfMillCost = Integer.parseInt(smm.group(1));
                    bzRaw = null;
                }
            }
            List<BreakZoneCost>      breakZoneCosts      = parseBreakZoneCosts(bzRaw);
            List<DiscardCost>        discardCosts        = parseDiscardCosts(discardRaw);
            List<RemoveFromGameCost> removeFromGameCosts = parseRemoveFromGameCosts(removeRaw);
            List<ReturnToHandCost>   returnToHandCosts   = parseReturnToHandCosts(returnRaw);
            List<CounterCost>        counterCosts        = parseCounterCosts(counterRaw);
            List<DullForwardCost>    dullForwardCosts    = parseDullForwardCosts(dullCostRaw);
            boolean yourTurnOnly      = YOUR_TURN_ONLY_PATTERN.matcher(effectRaw).find();
            boolean opponentTurnOnly  = OPP_TURN_ONLY_PATTERN.matcher(effectRaw).find();
            boolean oncePerTurn       = ONCE_PER_TURN_PATTERN.matcher(effectRaw).find();
            boolean mainPhaseOnly     = MAIN_PHASE_ONLY_PATTERN.matcher(effectRaw).find();
            boolean whilePartyAtk     = WHILE_PARTY_ATTACKING_PATTERN.matcher(effectRaw).find();
            String  whileCardAtk      = null;
            if (!whilePartyAtk) {
                Matcher wAtkM = WHILE_CARD_ATTACKING_PATTERN.matcher(effectRaw);
                if (wAtkM.find()) whileCardAtk = wAtkM.group("card").trim();
            }
            Matcher wBlkM             = WHILE_CARD_BLOCKING_PATTERN.matcher(effectRaw);
            String  whileCardBlk      = wBlkM.find() ? wBlkM.group("card").trim() : null;
            boolean whileCardInHand   = WHILE_CARD_IN_HAND_PATTERN.matcher(effectRaw).find();
            boolean hasBlockingTarget = HAS_BLOCKING_TARGET_EFFECT_PATTERN.matcher(effectRaw).find();
            boolean sourceInBattle    = SOURCE_IN_BATTLE_PATTERN.matcher(effectRaw).find();
            boolean requiresOppDiscardedThisTurn = OPP_DISCARD_THIS_TURN_PATTERN.matcher(effectRaw).find();
            boolean requiresCastSummonThisTurn   = CAST_SUMMON_THIS_TURN_PATTERN.matcher(effectRaw).find();
            boolean requiresOpponentEmptyHand    = OPP_NO_CARDS_IN_HAND_RESTRICTION.matcher(effectRaw).find();
            boolean requiresSelfEmptyHand        = SELF_NO_CARDS_IN_HAND_RESTRICTION.matcher(effectRaw).find();
            boolean requiresSourceIsForward      = SOURCE_IS_FORWARD_RESTRICTION.matcher(effectRaw).find();
            Matcher dmgThreshM = OWN_DAMAGE_THRESHOLD_RESTRICTION.matcher(effectRaw);
            if (damageThreshold == 0 && dmgThreshM.find()) damageThreshold = Integer.parseInt(dmgThreshM.group("count"));
            Matcher namedCardDmgM = NAMED_CARD_TOOK_DAMAGE_THIS_TURN_RESTRICTION.matcher(effectRaw);
            String requiresNamedCardTookDamageThisTurn = namedCardDmgM.find() ? namedCardDmgM.group("card").trim() : null;
            boolean requiresSelfReceivedDamageThisTurn = SELF_RECEIVED_DAMAGE_THIS_TURN_RESTRICTION.matcher(effectRaw).find();
            boolean requiresForwardPutToBZThisTurn = FORWARD_PUT_TO_BZ_THIS_TURN_RESTRICTION.matcher(effectRaw).find();
            Matcher jobPutToBzM = JOB_PUT_TO_BZ_THIS_TURN_RESTRICTION.matcher(effectRaw);
            String  requiresJobPutToBZThisTurn = jobPutToBzM.find() ? jobPutToBzM.group("job").trim().toLowerCase() : null;
            Matcher blockerForAttackerM = BREAK_FORWARD_THAT_BLOCKS_NAMED_CARD.matcher(effectRaw);
            String  blockerForAttacker  = blockerForAttackerM.find() ? blockerForAttackerM.group("name").trim() : null;
            Matcher elemFwdM = ELEMENT_FORWARD_ENTERED_THIS_TURN_PATTERN.matcher(effectRaw);
            String  requiresElementForwardEnteredThisTurn = elemFwdM.find() ? elemFwdM.group("element").toLowerCase() : null;
            Matcher cardNameFwdM = CARD_NAME_ENTERED_THIS_TURN_PATTERN.matcher(effectRaw);
            String  requiresCardNameEnteredThisTurn = cardNameFwdM.find() ? cardNameFwdM.group("cardname").trim() : null;
            // "You can only use this ability [during your Main Phase and] if X is in the Break
            // Zone." always names the source card itself, on every printing in the corpus, and so
            // always means "this ability is used from the Break Zone" — breakZoneOnly, never
            // ownBreakZoneNameRequired. Reading it as the latter left 18 abilities unreachable:
            // the card is in the Break Zone, so the field menu never asks, and the Break Zone
            // dialog only lists abilities with breakZoneOnly set.
            //
            // ownBreakZoneNameRequired is still populated, but only by the grant that genuinely
            // means it — "If you have a Card Name X in your Break Zone, Y gains …" (Innocence
            // 13-137S), applied by withOwnBzNameRequired above. That one names another card and
            // is used from the field, which is what the field distinguishes.
            Matcher bzOnlyM = CARD_IN_BREAK_ZONE_PATTERN.matcher(effectRaw);
            String  breakZoneOnly = bzOnlyM.find() ? bzOnlyM.group("card").trim() : null;
            ControlCondition controlCondition = null;
            if (!whileCardInHand && breakZoneOnly == null) {
                Matcher compM = YOUR_TURN_AND_CONTROL_IF_PATTERN.matcher(effectRaw);
                if (compM.find()) {
                    controlCondition = parseControlCondition(compM.group("condition"));
                } else {
                    Matcher ctrlM = CONTROL_IF_PATTERN.matcher(effectRaw);
                    if (ctrlM.find()) {
                        controlCondition = parseControlCondition(ctrlM.group("condition"));
                    } else {
                        Matcher notM = CONTROL_IF_NOT_ANY_PATTERN.matcher(effectRaw);
                        Matcher neitherM = CONTROL_IF_NEITHER_PLAYER_PATTERN.matcher(effectRaw);
                        if (notM.find()) {
                            String rawType = notM.group("type");
                            String cardType = rawType.replaceAll("(?i)s$", "").trim();
                            cardType = Character.toUpperCase(cardType.charAt(0)) + cardType.substring(1).toLowerCase();
                            controlCondition = new ControlCondition(List.of(), 0, true, cardType, null, null, null, 0, List.of());
                        } else if (neitherM.find()) {
                            String rawType = neitherM.group("type");
                            String cardType = rawType.replaceAll("(?i)s$", "").trim();
                            cardType = Character.toUpperCase(cardType.charAt(0)) + cardType.substring(1).toLowerCase();
                            controlCondition = ControlCondition.forNeitherPlayerControls(cardType);
                        } else {
                            Matcher oppM = OPPONENT_CONTROLS_N_OR_MORE_PATTERN.matcher(effectRaw);
                            if (oppM.find()) {
                                int count = Integer.parseInt(oppM.group("count"));
                                String rawType = oppM.group("type");
                                String cardType = rawType.replaceAll("(?i)s$", "").trim();
                                cardType = Character.toUpperCase(cardType.charAt(0)) + cardType.substring(1).toLowerCase();
                                controlCondition = ControlCondition.forOpponentCount(count, cardType);
                            }
                        }
                    }
                }
            }
            Matcher cpBkpM = CP_BACKUP_ONLY_ABILITY.matcher(effectRaw);
            String cpBackupElement = cpBkpM.find()
                    ? (cpBkpM.group("element") != null ? cpBkpM.group("element") : "")
                    : null;
            Matcher cpElemsM = CP_ELEMENTS_ONLY_ABILITY.matcher(effectRaw);
            String cpAllowedElements = null;
            if (cpElemsM.find()) {
                List<String> elems = new ArrayList<>();
                elems.add(cpElemsM.group("elem1"));
                if (cpElemsM.group("elem2") != null) elems.add(cpElemsM.group("elem2"));
                if (cpElemsM.group("elem3") != null) elems.add(cpElemsM.group("elem3"));
                cpAllowedElements = String.join("|", elems);
            }
            Matcher csrM = COUNTER_SCALE_REF_PATTERN.matcher(effectRaw);
            Matcher cfeM = FOR_EACH_COUNTER_PLACED_ON_PATTERN.matcher(effectRaw);
            Matcher camM = COST_AT_MOST_COUNTER_PATTERN.matcher(effectRaw);
            String counterScaleName = csrM.find() ? csrM.group("counterName").trim()
                                    : cfeM.find() ? cfeM.group("counterName").trim()
                                    : camM.find() ? camM.group("counterName").trim() : null;
            Matcher cminM = COUNTER_MINIMUM_RESTRICTION.matcher(effectRaw);
            int    minCounterRequired = 0;
            String minCounterType     = null;
            if (cminM.find()) {
                minCounterRequired = cminM.group("count") != null ? Integer.parseInt(cminM.group("count")) : 1;
                minCounterType     = cminM.group("type").trim();
            }
            Matcher oppHandM = OPP_HAND_AT_MOST_RESTRICTION.matcher(effectRaw);
            int maxOpponentHandSize = oppHandM.find() ? Integer.parseInt(oppHandM.group("count")) : -1;
            Matcher cmaxM = COUNTER_ZERO_RESTRICTION.matcher(effectRaw);
            int    maxCounterAllowed = -1;
            String maxCounterType    = null;
            if (cmaxM.find()) {
                maxCounterAllowed = 0;
                maxCounterType    = cmaxM.group("type").trim();
            }
            String inlineJobRaw = m.group("inlinejob");
            String inlineCostReductionJob = inlineJobRaw != null ? inlineJobRaw.trim() : null;
            String inlineExcludeRaw = inlineJobRaw != null ? m.group("inlineexclude") : null;
            String inlineCostReductionExcludeName = inlineExcludeRaw != null ? inlineExcludeRaw.trim() : null;
            boolean requiresOwnWarpCard = REMOVE_WARP_COUNTER_FROM_RFG.matcher(effectRaw).find();
            boolean usableByEitherPlayer = EACH_PLAYER_CAN_USE_PATTERN.matcher(effectRaw).find();
            // "You can only use this ability if [CardName] has N power or more." The gate is
            // applied to the source card, and the captured name is not checked against it because
            // this method is given the text alone. Sound for the corpus: Hyoh 16-097H is the only
            // card with the wording and it names itself. A future card naming a *different* card
            // would need the owner's name threaded in here to tell the two apart.
            Matcher selfPowerM = SELF_POWER_AT_LEAST_RESTRICTION.matcher(effectRaw);
            int requiresSelfPowerAtLeast = selfPowerM.find()
                    ? Integer.parseInt(selfPowerM.group("power")) : 0;
            ActionAbility parsed = new ActionAbility(abilityName, requiresDull, isSpecial, crystalCost, selfMillCost, hasXCost, cpCost, breakZoneCosts, discardCosts, removeFromGameCosts, returnToHandCosts, counterCosts, dullForwardCosts, yourTurnOnly, opponentTurnOnly, oncePerTurn, mainPhaseOnly, whileCardAtk, whileCardBlk, whilePartyAtk, whileCardInHand, hasBlockingTarget, effectRaw, damageThreshold, controlCondition, cpBackupElement, cpAllowedElements, sourceInBattle, requiresOppDiscardedThisTurn, requiresCastSummonThisTurn, requiresElementForwardEnteredThisTurn, requiresCardNameEnteredThisTurn, breakZoneOnly, requiresOpponentEmptyHand, requiresSelfEmptyHand, requiresNamedCardTookDamageThisTurn, requiresSelfReceivedDamageThisTurn, requiresForwardPutToBZThisTurn, requiresJobPutToBZThisTurn, blockerForAttacker, null, counterScaleName, minCounterRequired, minCounterType, maxOpponentHandSize, requiresSourceIsForward, maxCounterAllowed, maxCounterType, inlineCostReductionJob, inlineCostReductionExcludeName, requiresOwnWarpCard, usableByEitherPlayer, requiresSelfPowerAtLeast);
            // The named card is carried rather than resolved here: this parser is not given the
            // card's own name, and the activation site is, so the self check belongs there.
            if (bottomDeckRaw != null && m.group("bottomdeckname") != null)
                parsed = parsed.withBottomOfDeckCost(m.group("bottomdeckname").trim());
            if (revealRaw != null && m.group("revealcount") != null)
                parsed = parsed.withRevealCost(new RevealCost(
                        Integer.parseInt(m.group("revealcount")),
                        revealCostCardType(m.group("revealwhat"))));
            result.add(parsed);
        }
        return List.copyOf(result);
    }

    /**
     * The card type a "reveal N X in your hand" cost names, or {@code null} when it names none
     * ("reveal 1 card in your hand" would be any card).  The phrase is matched loosely because it
     * is the tail of a cost group: "Forward"/"Forwards" both appear, and an unrecognised noun is
     * better read as "any card" than as a cost that can never be paid.
     */
    private static String revealCostCardType(String what) {
        if (what == null) return null;
        String w = what.trim().toLowerCase(Locale.ROOT);
        if (w.contains("forward"))   return "Forward";
        if (w.contains("backup"))    return "Backup";
        if (w.contains("monster"))   return "Monster";
        if (w.contains("summon"))    return "Summon";
        if (w.contains("character")) return "Character";
        return null;
    }

    private static ActionAbility withOwnBzNameRequired(ActionAbility a, String bzCard) {
        return new ActionAbility(a.abilityName(), a.requiresDull(), a.isSpecial(), a.crystalCost(),
                a.selfMillCost(), a.hasXCost(), a.cpCost(), a.breakZoneCosts(), a.discardCosts(),
                a.removeFromGameCosts(), a.returnToHandCosts(), a.counterCosts(), a.dullForwardCosts(),
                a.yourTurnOnly(), a.opponentTurnOnly(), a.oncePerTurn(), a.mainPhaseOnly(), a.whileCardAttacking(),
                a.whileCardBlocking(), a.whilePartyAttacking(), a.whileCardInHand(),
                a.hasBlockingTargetEffect(), a.effectText(), a.damageThreshold(), a.controlCondition(),
                a.cpBackupElement(), a.cpAllowedElements(), a.sourceInBattle(), a.requiresOppDiscardedThisTurn(),
                a.requiresCastSummonThisTurn(), a.requiresElementForwardEnteredThisTurn(),
                a.requiresCardNameEnteredThisTurn(), a.breakZoneOnly(), a.requiresOpponentEmptyHand(),
                a.requiresSelfEmptyHand(), a.requiresNamedCardTookDamageThisTurn(), a.requiresSelfReceivedDamageThisTurn(),
                a.requiresForwardPutToBZThisTurn(), a.requiresJobPutToBZThisTurn(), a.blockerForAttacker(), bzCard,
                a.counterScaleName(), a.minCounterRequired(), a.minCounterType(), a.maxOpponentHandSize(), a.requiresSourceIsForward(),
                a.maxCounterAllowed(), a.maxCounterType(), a.inlineCostReductionJob(), a.inlineCostReductionExcludeName(), a.requiresOwnWarpCard(),
                // Spelled out rather than routed through the compatibility constructor, which
                // would silently drop both from the copy.
                a.usableByEitherPlayer(), a.requiresSelfPowerAtLeast(),
                a.bottomOfDeckCostCardName(), a.revealCost());
    }

    /** Parses a "discard N [filter]" cost phrase into a {@link DiscardCost} list (0 or 1 item). */
    private static List<DiscardCost> parseDiscardCosts(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        Matcher m = DISCARD_COST_PATTERN.matcher(raw.trim());
        if (!m.find()) return List.of();

        int    count     = Integer.parseInt(m.group("count"));
        String cardName  = m.group("cardname");
        String category  = m.group("category");
        String typeCat   = m.group("typecat");
        String element   = m.group("element");
        String type      = m.group("type");
        String different = m.group("different");

        String finalType = typeCat != null ? normalizeTypeSuffix(typeCat)
                         : type    != null ? normalizeTypeSuffix(type) : null;

        if (cardName != null) cardName = cardName.trim();
        if (category != null) category = category.trim();

        return List.of(new DiscardCost(count, cardName, element, finalType, category, different != null));
    }

    /** Strips a trailing "s" from plural type names (e.g. "Summons" → "Summon"). */
    private static String normalizeTypeSuffix(String t) {
        String s = t.trim();
        return (s.length() > 2 && s.endsWith("s")) ? s.substring(0, s.length() - 1) : s;
    }

    /** Parses the "put … into the Break Zone" cost phrase into individual {@link BreakZoneCost} items. */
    private static List<BreakZoneCost> parseBreakZoneCosts(String bzRaw) {
        if (bzRaw == null || bzRaw.isBlank()) return List.of();
        Matcher m = BREAK_ZONE_COST_PATTERN.matcher(bzRaw.trim());
        if (!m.find()) return List.of();
        String content = m.group(1).trim();

        List<BreakZoneCost> result = new ArrayList<>();
        for (String part : content.split("(?i)\\s+and\\s+")) {
            String p = part.trim();
            Matcher numM = Pattern.compile("^(\\d+)\\s+(.+)$").matcher(p);
            if (numM.matches()) {
                result.add(new BreakZoneCost("", Integer.parseInt(numM.group(1)), numM.group(2).trim()));
            } else {
                result.add(new BreakZoneCost(p, 1, ""));
            }
        }
        return List.copyOf(result);
    }

    private static final Pattern REMOVE_FROM_GAME_COST_PATTERN = Pattern.compile(
        "(?i)remove\\s+(.+?)\\s+from\\s+(?:the\\s+)?game"
    );

    static final Pattern YOUR_TURN_ONLY_PATTERN = Pattern.compile(
        "(?i)(?:You\\s+can(?:\\s+only)?\\s+use\\s+this\\s+ability(?:\\s+only)?\\s+|,\\s*)during\\s+your\\s+turn[.!]?"
    );

    /** "You can only use this ability during your opponent's turn" restriction. */
    static final Pattern OPP_TURN_ONLY_PATTERN = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+during\\s+your\\s+opponent'?s?\\s+turn[.!]?"
    );

    /** "You can only use this ability if your opponent has no cards in their hand" — standalone or as part of a compound restriction. */
    static final Pattern OPP_NO_CARDS_IN_HAND_RESTRICTION = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+your\\s+opponent\\s+has\\s+no\\s+cards?\\s+in\\s+(?:his/her|his|her|their)\\s+hand[,.]?"
    );

    /** "You can only use this ability if your opponent has N card(s) or less in their hand." Group {@code count}. */
    static final Pattern OPP_HAND_AT_MOST_RESTRICTION = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+your\\s+opponent\\s+has\\s+(?<count>\\d+)\\s+cards?\\s+or\\s+less\\s+in\\s+(?:his/her|his|her|their)\\s+hand[,.]?"
    );

    /** "You can only use this ability if you have no cards in your hand." */
    static final Pattern SELF_NO_CARDS_IN_HAND_RESTRICTION = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+you\\s+have\\s+no\\s+cards?\\s+in\\s+your\\s+hand[.!]?"
    );

    /** "You can only use this ability if [CardName] is a Forward." */
    static final Pattern SOURCE_IS_FORWARD_RESTRICTION = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+.+?\\s+is\\s+a\\s+Forward[.!]?"
    );

    static final Pattern ONCE_PER_TURN_PATTERN = Pattern.compile(
        "(?i)(?:You\\s+can(?:\\s+only)?\\s+use\\s+this\\s+ability(?:\\s+only)?\\s+|(?:and\\s+)?only\\s+)once\\s+per\\s+turn[.!]?"
    );

    static final Pattern MAIN_PHASE_ONLY_PATTERN = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+during\\s+your\\s+Main\\s+Phase[.!]?"
    );

    /** "Each player can use this ability." — the non-controller may also activate it. */
    static final Pattern EACH_PLAYER_CAN_USE_PATTERN = Pattern.compile(
        "(?i)Each\\s+player\\s+can\\s+use\\s+this\\s+ability[.!]?"
    );

    /**
     * Matches the party-member filter in "N or more [Category X | Job Y] Forwards [you control]".
     * Used when parsing party-attack auto-ability triggers.
     */
    private static final Pattern PARTY_FILTER_PATTERN = Pattern.compile(
        "(?i)(?<count>\\d+)\\s+or\\s+more\\s+" +
        "(?:Category\\s+(?<category>\\S+)\\s+|Job\\s+(?<job>.+?)\\s+)?Forwards?(?:\\s+you\\s+control)?"
    );

    /**
     * Matches a secondary conditional party-size sentence embedded in an effect:
     * "If N or more [Category X | Job Y] Forwards form the party, also [effect]."
     * These are converted into a second party-attack trigger during preprocessing.
     */
    private static final Pattern PARTY_ATTACK_FOLLOWUP_PATTERN = Pattern.compile(
        "(?i)If\\s+(?<count>\\d+)\\s+or\\s+more\\s+" +
        "(?:Category\\s+(?<category>\\S+)\\s+|Job\\s+(?<job>.+?)\\s+)?Forwards?\\s+form\\s+the\\s+party,?\\s+" +
        "also\\s+(?<effect>[^.\\[!]+)[.!]?",
        Pattern.DOTALL
    );

    // Must be tested before WHILE_CARD_ATTACKING_PATTERN to avoid "a party you control" matching as a card name
    static final Pattern WHILE_PARTY_ATTACKING_PATTERN = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+while\\s+a\\s+party\\s+you\\s+control\\s+is\\s+attacking[.!]?"
    );
    static final Pattern WHILE_CARD_ATTACKING_PATTERN = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+while\\s+(?<card>.+?)\\s+is\\s+attacking[.!]?"
    );
    static final Pattern WHILE_CARD_BLOCKING_PATTERN = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+while\\s+(?<card>.+?)\\s+is\\s+blocking[.!]?"
    );
    static final Pattern WHILE_CARD_IN_HAND_PATTERN = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability" +
        "(?:\\s+during\\s+your\\s+turn\\s+and)?" +
        "\\s+if\\s+.+?\\s+is\\s+in\\s+your\\s+hand[.!]?"
    );

    static final Pattern SOURCE_IN_BATTLE_PATTERN = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+when\\s+.+?\\s+is\\s+in\\s+Battle[.!]?"
    );

    /** "You can only use this ability if your opponent has discarded a card from their hand due to your Summons or abilities this turn." */
    static final Pattern OPP_DISCARD_THIS_TURN_PATTERN = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+your\\s+opponent\\s+has\\s+discarded\\s+a\\s+card\\s+from\\s+their\\s+hand\\s+due\\s+to\\s+your\\s+Summons\\s+or\\s+abilities\\s+this\\s+turn[.!]?"
    );

    /** "You can only use this ability if you have cast a Summon this turn." */
    static final Pattern CAST_SUMMON_THIS_TURN_PATTERN = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+you\\s+have\\s+cast\\s+a\\s+Summon\\s+this\\s+turn[.!]?"
    );

    /**
     * "You can only use this ability if [CardName] has N power or more." (Hyoh 16-097H) — gates
     * activation on the source card's <em>current</em> power, so a card that has already used a
     * power-setting ability this turn qualifies where its printed power would not.
     * Group {@code power} — the required minimum.
     */
    static final Pattern SELF_POWER_AT_LEAST_RESTRICTION = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+(?<card>.+?)\\s+has\\s+" +
        "(?<power>\\d+)\\s+power\\s+or\\s+more[.!]?"
    );

    /** "You can only use this ability if you have received N points of damage or more." */
    static final Pattern OWN_DAMAGE_THRESHOLD_RESTRICTION = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+you\\s+have\\s+received\\s+(?<count>\\d+)\\s+points?\\s+of\\s+damage\\s+or\\s+more[.!]?"
    );

    /**
     * Redundant inline reminder "(If you have received N points of damage or more, this [Card type]
     * has this ability.)" — present when the ability already carries a "Damage N --" prefix.
     * Stripped from effect text before parsing so it doesn't appear as an unrecognised sub-effect.
     */
    private static final Pattern DAMAGE_THRESHOLD_REMINDER_PAREN = Pattern.compile(
        "(?i)\\s*\\(If\\s+you\\s+have\\s+received\\s+\\d+\\s+points?\\s+of\\s+damage\\s+or\\s+more,\\s+" +
        "this\\s+(?:Forward|Backup|Monster|Character|card)\\s+has\\s+this\\s+ability\\.?\\)"
    );

    /** "You can only use this ability if [CardName] has received damage this turn." */
    static final Pattern NAMED_CARD_TOOK_DAMAGE_THIS_TURN_RESTRICTION = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+(?<card>.+?)\\s+has\\s+received\\s+damage\\s+this\\s+turn[.!]?"
    );

    /** "You can only use this ability if you have received a point of damage this turn." */
    static final Pattern SELF_RECEIVED_DAMAGE_THIS_TURN_RESTRICTION = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+you\\s+have\\s+received\\s+a\\s+point\\s+of\\s+damage\\s+this\\s+turn[.!]?"
    );

    /** Matches "Break the Forward that blocks [Name]" — extracts group {@code name}. */
    static final Pattern BREAK_FORWARD_THAT_BLOCKS_NAMED_CARD = Pattern.compile(
        "(?i)Break\\s+the\\s+Forward\\s+that\\s+blocks?\\s+(?<name>[^.!]+)[.!]?"
    );

    /** "You can only use this ability if a Forward you controlled has been put from the field into the Break Zone this turn." */
    static final Pattern FORWARD_PUT_TO_BZ_THIS_TURN_RESTRICTION = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+a\\s+Forward\\s+you\\s+controlled\\s+has\\s+been\\s+put\\s+from\\s+the\\s+field\\s+into\\s+the\\s+Break\\s+Zone\\s+this\\s+turn[.!]?"
    );

    /**
     * "You can only use this ability if a Job [Job Name] you controlled has been put from the
     * field into the Break Zone this turn." — job-qualified sibling of
     * {@link #FORWARD_PUT_TO_BZ_THIS_TURN_RESTRICTION}. Captures the job name in group {@code job}.
     */
    static final Pattern JOB_PUT_TO_BZ_THIS_TURN_RESTRICTION = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+a\\s+Job\\s+(?<job>.+?)\\s+you\\s+controlled\\s+has\\s+been\\s+put\\s+from\\s+the\\s+field\\s+into\\s+the\\s+Break\\s+Zone\\s+this\\s+turn[.!]?"
    );

    /** "You can only use this ability if an/a [Element] Forward has entered your field this turn." */
    static final Pattern ELEMENT_FORWARD_ENTERED_THIS_TURN_PATTERN = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+an?\\s+(?<element>\\w+)\\s+Forward\\s+has\\s+entered\\s+your\\s+field\\s+this\\s+turn[.!]?"
    );

    /** Matches "if a Card Name X has entered your field this turn" — captures the card name. */
    static final Pattern CARD_NAME_ENTERED_THIS_TURN_PATTERN = Pattern.compile(
        "(?i)\\bif\\s+a\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+has\\s+entered\\s+your\\s+field\\s+this\\s+turn[.!]?"
    );

    /**
     * Matches "if [CardName] is in the Break Zone" (1–3 words for the card name).
     * The card name is captured in the {@code card} group.
     * Handles patterns like "and if Fran is in the Break Zone" — the lazy 1–3 word
     * limit prevents the match from swallowing preceding "if X has entered..." clauses.
     */
    static final Pattern CARD_IN_BREAK_ZONE_PATTERN = Pattern.compile(
        "(?i)\\bif\\s+(?<card>\\S+(?:\\s+\\S+){0,2})\\s+is\\s+in\\s+the\\s+Break\\s+Zone[.!]?"
    );

    /**
     * Matches restriction sentences of the form "You can only use this ability [during your Main Phase and] if
     * [CardName] is in the Break Zone."  The "during your Main Phase and" prefix is optional (combined restriction).
     *
     * <p>Anchored at the end of the sentence, which is the only thing this adds over
     * {@link #CARD_IN_BREAK_ZONE_PATTERN}: it exists so restriction stripping can take the whole
     * sentence out as a unit, the same job {@link #YOUR_TURN_AND_BZ_RESTRICTION} does for the
     * your-turn spelling. It sets no field of its own — every printing of this sentence names the
     * source card, so what it states is that the ability is used from the Break Zone, and
     * {@code CARD_IN_BREAK_ZONE_PATTERN} records that as {@code breakZoneOnly}.
     */
    static final Pattern OWN_BZ_NAME_REQUIRED_RESTRICTION = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+(?:during\\s+your\\s+Main\\s+Phase\\s+and\\s+)?if\\s+" +
        "(?<card>\\S+(?:\\s+\\S+){0,2})\\s+is\\s+in\\s+the\\s+Break\\s+Zone[.!]?\\s*$"
    );

    /**
     * Combined restriction: "You can only use this ability during your turn and if [CardName] is
     * in the Break Zone." — your-turn-only + BZ-activation in one sentence (e.g. Chaos).  Parsing
     * picks the two parts up separately ({@link #YOUR_TURN_ONLY_PATTERN} sets yourTurnOnly and
     * {@link #CARD_IN_BREAK_ZONE_PATTERN} sets breakZoneOnly); this pattern exists so restriction
     * stripping can remove the whole sentence as a unit rather than leaving
     * "and if X is in the Break Zone." as an unparsed fragment.
     */
    static final Pattern YOUR_TURN_AND_BZ_RESTRICTION = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+during\\s+your\\s+turn\\s+and\\s+if\\s+" +
        "\\S+(?:\\s+\\S+){0,2}\\s+is\\s+in\\s+the\\s+Break\\s+Zone[.!]?\\s*$"
    );

    /**
     * Outer structure: "If you have [a] Card Name X in your Break Zone, Y gains 'ab1' [and 'ab2']"
     * The {@code bzcard} group captures the card name; {@code quotedAbilities} captures all quoted ability strings.
     */
    private static final Pattern IF_OWN_BZ_GAINS_PATTERN = Pattern.compile(
        "(?i)If\\s+you\\s+have\\s+(?:a\\s+)?Card\\s+Name\\s+(?<bzcard>.+?)\\s+in\\s+your\\s+Break\\s+Zone,\\s+" +
        "[A-Za-z][A-Za-z\\s'\\-]*?\\s+gains?\\s+(?<quotedAbilities>\"[^\"]+\"(?:\\s+and\\s+\"[^\"]+\")*)\\.?"
    );

    /** Restriction: "You can only use this ability if N or more [Type] Counters are placed on [CardName]." */
    static final Pattern COUNTER_MINIMUM_RESTRICTION = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+" +
        "(?:(?<count>\\d+)\\s+or\\s+more\\s+|a\\s+)" +
        "(?<type>\\w+)\\s+Counters?\\s+(?:are|is)\\s+placed\\s+on\\s+.+?[.!]?\\s*$"
    );

    /** Effect marker: "Choose 1 card removed from the game. Remove 1 Warp Counter from it." */
    static final Pattern REMOVE_WARP_COUNTER_FROM_RFG = Pattern.compile(
        "(?i)Choose\\s+1\\s+card\\s+removed\\s+from\\s+the\\s+game\\.\\s*Remove\\s+1\\s+Warp\\s+Counter\\s+from\\s+it"
    );

    /** Restriction: "You can only use this ability if there are no [Type] Counters on [CardName]." */
    static final Pattern COUNTER_ZERO_RESTRICTION = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+" +
        "there\\s+are\\s+no\\s+" +
        "(?<type>\\w+)\\s+Counters?\\s+on\\s+.+?[.!]?\\s*$"
    );

    /** Captures the counter type name from "the same number of X as the [Name] Counters placed on [card]". */
    static final Pattern COUNTER_SCALE_REF_PATTERN = Pattern.compile(
        "(?i)the\\s+same\\s+number\\s+of.+?as\\s+the\\s+(?<counterName>.+?)\\s+Counters?\\s+placed\\s+on\\s+.+?(?:[,.]|\\s*$)"
    );

    /** Captures the counter type name from "for each [Name] Counter(s) placed on [card]". */
    static final Pattern FOR_EACH_COUNTER_PLACED_ON_PATTERN = Pattern.compile(
        "(?i)for\\s+each\\s+(?<counterName>.+?)\\s+Counters?\\s+placed\\s+on\\s+.+?(?:[,.]|\\s*$)"
    );

    /**
     * Captures the counter type name from "of cost equal to or less than the number of [Name]
     * Counter(s) placed on [card]" (15-083L Rydia), where the counter count is a cost ceiling
     * rather than a repetition count.  Kept distinct from
     * {@link #FOR_EACH_COUNTER_PLACED_ON_PATTERN} because the "number of X you control" wording
     * this shares its opening with is far more common and must not be read as a counter scale.
     */
    static final Pattern COST_AT_MOST_COUNTER_PATTERN = Pattern.compile(
        "(?i)cost\\s+equal\\s+to\\s+or\\s+less\\s+than\\s+the\\s+number\\s+of\\s+" +
        "(?<counterName>.+?)\\s+Counters?\\s+placed\\s+on\\s+.+?(?:[,.]|\\s*$)"
    );

    /** Captures the condition from "You can only use this ability during your turn and if you control [X]". */
    static final Pattern YOUR_TURN_AND_CONTROL_IF_PATTERN = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+during\\s+your\\s+turn\\s+and\\s+if\\s+you\\s+control\\s+(?<condition>.+?)\\s*[.!]?\\s*$"
    );

    /** Captures the raw condition text from "You can only use this ability if you control [X]". */
    static final Pattern CONTROL_IF_PATTERN = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+you\\s+control\\s+(?<condition>.+?)\\s*[.!]?\\s*$"
    );

    /** Captures the card type from "You can only use this ability if you don't control any [type]". */
    static final Pattern CONTROL_IF_NOT_ANY_PATTERN = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+you\\s+don't\\s+control\\s+any\\s+(?<type>Forwards?|Monsters?|Backups?|Characters?)\\s*[.!]?\\s*$"
    );

    /**
     * "You can only use this ability if neither player controls [type]" — 23-053R Meteion.
     *
     * <p>Unlike {@link #CONTROL_IF_NOT_ANY_PATTERN} the condition spans both fields, so it becomes
     * a {@link ControlCondition#forNeitherPlayerControls} rather than an exact-zero on the
     * controller's side alone.
     */
    static final Pattern CONTROL_IF_NEITHER_PLAYER_PATTERN = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+neither\\s+player\\s+controls\\s+" +
        "(?:any\\s+)?(?<type>Forwards?|Monsters?|Backups?|Characters?)\\s*[.!]?\\s*$"
    );

    /** Captures count and type from "You can only use this ability if your opponent controls N or more [type]". */
    static final Pattern OPPONENT_CONTROLS_N_OR_MORE_PATTERN = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+your\\s+opponent\\s+controls?\\s+" +
        "(?<count>\\d+)\\s+or\\s+more\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s*[.!]?\\s*$"
    );

    /**
     * Named-card mode: "Card Name X [<conj> [a] Card Name Y [<conj> [a] Card Name Z]]"
     * where {@code conj} is {@code and} (AND semantics) or {@code or} (OR semantics).
     * The optional "a" article is allowed before each name (including subsequent ones).
     * Mixing conjunctions ("Card Name X and Card Name Y or Card Name Z") is parsed but
     * treated as homogeneous — the {@code conj1} group's value wins.
     */
    private static final Pattern CONTROL_NAMED_CARDS_PATTERN = Pattern.compile(
        "(?i)(?:a\\s+)?Card\\s+Name\\s+(?<n1>.+?)" +
        "(?:\\s+(?<conj1>and|or)\\s+(?:a\\s+)?Card\\s+Name\\s+(?<n2>.+?))?" +
        "(?:\\s+(?:and|or)\\s+(?:a\\s+)?Card\\s+Name\\s+(?<n3>.+?))?" +
        "\\s*$"
    );

    /**
     * Count mode: "[N or more | only N | a(n)] [element] [Category X] [Job name] [type] [of power P or more] [or Card Name X]"
     * <ul>
     *   <li>{@code count}    — "N or more" numeric threshold; absent when "only" or "a/an" prefix</li>
     *   <li>{@code exactn}   — "only N" exact count; absent otherwise</li>
     *   <li>{@code element}  — element name, absent if none</li>
     *   <li>{@code category} — category name after "Category", absent if none</li>
     *   <li>{@code job}      — job name after "Job", lazily captured until type/or/end</li>
     *   <li>{@code type}     — card type: Forward(s)/Monster(s)/Backup(s)/Character(s)</li>
     *   <li>{@code power}    — power threshold from "of power P or more"</li>
     *   <li>{@code altname}  — card name after "or Card Name"</li>
     * </ul>
     */
    /**
     * "4 Forwards or more" — the same threshold {@link #CONTROL_COUNT_CONDITION_PATTERN} reads,
     * with the qualifier printed after the noun instead of before it. Normalised into the leading
     * form rather than widening that pattern: its count prefix is what anchors the whole match,
     * and letting "or more" float would leave every bare noun ambiguous.
     *
     * <p>Anchored and limited to the four type nouns, because Ephemeral Vision 2-123C is the only
     * card in the corpus that prints it this way round.
     */
    private static final Pattern TRAILING_OR_MORE_COUNT = Pattern.compile(
        "(?i)^(?<count>\\d+)\\s+(?<noun>Forwards?|Monsters?|Backups?|Characters?)\\s+or\\s+more$"
    );

    private static final Pattern CONTROL_COUNT_CONDITION_PATTERN = Pattern.compile(
        "(?i)" +
        "(?:(?<count>\\d+)\\s+or\\s+more|only\\s+(?<exactn>\\d+)|a(?:n)?\\s+)\\s*" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark|Multi-Element)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?:Job\\s+(?<job>.+?)(?=\\s+(?:Forwards?|Monsters?|Backups?|Characters?)(?:\\s|$)|\\s+or\\s+Card\\s+Name\\b|\\s*$))?" +
        "(?<type>Forwards?|Monsters?|Backups?|Characters?)?" +
        "(?:\\s+of\\s+an?\\s+Element\\s+other\\s+than\\s+(?<excludeelem>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))?" +
        "(?:\\s+of\\s+power\\s+(?<power>\\d+)\\s+or\\s+more)?" +
        // Garland 17-004C's "a Forward of cost 2 or less". Without it the pattern matched "a
        // Forward" and then failed on the anchor, so the whole condition — and the boost hanging
        // off it — was dropped. Both comparisons are read; no printing states a cost and a power.
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+(?<costcmp>less|more))?" +
        "(?:\\s+or\\s+Card\\s+Name\\s+(?<altname>.+?))?" +
        "\\s*$"
    );

    /**
     * Matches a single "When [card] [trigger], [optional you/opponent may] [effect]" block.
     * <ul>
     *   <li>{@code card}    — card name in the trigger (may contain spaces)</li>
     *   <li>{@code trigger} — "attack(s)", "block(s)", "attacks? or blocks?", or "enters? the field"</li>
     *   <li>{@code youmay}  — "you may " or "your opponent may " prefix (optional)</li>
     *   <li>{@code effect}  — remaining effect text</li>
     * </ul>
     * The effect capture ends at the next auto-ability header, an action-ability cost sequence
     * ({@code 《token》:}), or end of input.
     */
    private static final Pattern AUTO_ABILITY_PATTERN = Pattern.compile(
        "(?i)(?:Damage\\s+(?<threshold>\\d+)\\s+--\\s+)?" +
        // "Each time" is the same trigger word as "When" — Cloud 1-187S is the only printing to use
        // it, and it means exactly what the other two do: once per occurrence.
        "(?:When|Whenever|Each\\s+time)\\s+(?<card>[^,]+?)\\s+" +
        "(?<trigger>" +
            // "forms a party and attacks" must precede plain "attacks" to be preferred
            "forms?\\s+a\\s+party\\s+and\\s+attacks?" +
            "|attacks?(?:\\s+or\\s+blocks?)?" +
            "|blocks?(?:\\s+or\\s+is\\s+blocked)?" +
            "|is\\s+blocked" +
            // "enters the field or is put from the field into the Break Zone" must precede both
            // plain "enters the field" and plain "is put into the Break Zone"
            "|enters?\\s+the\\s+field\\s+or\\s+is\\s+put\\s+(?:from\\s+the\\s+field\\s+)?into\\s+the\\s+Break\\s+Zone" +
            // "enters the field or attacks" must precede plain "enters the field"
            "|enters?\\s+the\\s+field\\s+or\\s+attacks?" +
            "|enters?\\s+the\\s+field(?:\\s+due\\s+to\\s+(?:your\\s+cast|Warp))?" +
            // "enters your field other than from your hand" must precede plain "enters your field"
            "|enters?\\s+your\\s+field\\s+other\\s+than\\s+from\\s+your\\s+hand" +
            "|enters?\\s+your\\s+field" +
            // "enters your opponent's field other than from their hand" must precede the plain form.
            "|enters?\\s+your\\s+opponent's\\s+field\\s+other\\s+than\\s+from\\s+(?:his/her|his|her|their)\\s+hand" +
            // "enters your opponent's field" — location-based phrasing of the watcher trigger,
            // distinct from "of your opponent enters the field" (possessive-based; see below)
            "|enters?\\s+your\\s+opponent's\\s+field" +
            "|leaves?\\s+the\\s+field" +
            // The optional "on/during the same turn" tail belongs to the eight printings whose
            // subject is "[a|the] [type] damaged by [name]" — see DAMAGED_BY_BZ_SUBJECT. Widening
            // this arm rather than adding a second one keeps the alternation free of an ordering
            // hazard: a separate arm would have to be placed ahead of this one, since find()
            // would otherwise match the shorter phrase and then fail on the comma that is not
            // there yet.
            "|is\\s+put\\s+(?:from\\s+the\\s+field\\s+)?into\\s+the\\s+Break\\s+Zone" +
                "(?:\\s+(?:on|during)\\s+the\\s+same\\s+turn)?" +
            "|casts?\\s+a\\s+Summon" +
            "|is\\s+put\\s+into\\s+(?:your\\s+)?Damage\\s+Zone" +
            "|is\\s+removed\\s+from\\s+the\\s+game\\s+due\\s+to\\s+Warp" +
            "|deals?\\s+damage\\s+to\\s+your\\s+opponent" +
            "|deals?\\s+damage\\s+to\\s+a\\s+Forward" +
            // Distinct from "deals damage": the source is the card being damaged, not the dealer.
            "|is\\s+dealt\\s+damage" +
            "|receives?\\s+a\\s+point\\s+of\\s+damage" +
            "|(?:is|are)\\s+chosen\\s+by\\s+your\\s+opponent's\\s+Summons?(?:\\s+or\\s+abilit(?:y|ies))?" +
            "|uses?\\s+an\\s+EX\\s+Burst" +
            "|becomes?\\s+dull" +
            // "is priming" — the act of paying a Priming cost, watched by 24-109R Dion,
            // 24-113R Barnabas (XVI), 26-021C Anabella, 26-084H Vivian and 29-085R Cidolfus.
            // Distinct from "primes into", which names the fetched Eikon and is handled by
            // PRIMES_INTO_PATTERN in an earlier pass.
            "|(?:is|are)\\s+priming" +
            // "searches for 1 or more cards" (5-130R Tonberry, 13-034H Remedi) and the "for"-less
            // printing on 25-111H The Emperor. Searching is a public event other cards punish.
            "|searches?\\s+(?:for\\s+)?1\\s+or\\s+more\\s+cards?" +
            // "discards [1 or more Characters|a card from their hand|…] due to your Summons or
            // abilities" — an 11-card family whose printings vary in count, in whether "from their
            // hand" appears, and in the discarded type. The middle is left loose because the
            // "due to your …" tail is what identifies the trigger.
            "|discards?\\s+[^,]*?due\\s+to\\s+your\\s+Summons?\\s+or\\s+abilit(?:y|ies)" +
            // "are added to your opponent's hand from the Break Zone" — 25-111H The Emperor.
            "|(?:is|are)\\s+added\\s+to\\s+your\\s+opponent's\\s+hand\\s+from\\s+the\\s+Break\\s+Zone" +
            // "gain a 《C》" — 16-115H Sarah (MOBIUS). Player-scoped, so the subject is "you".
            "|gains?\\s+an?\\s+《C》" +
        ")\\s*,\\s+" +
        "(?<youmay>(?:you|your\\s+opponent)\\s+may\\s+)?" +
        "(?<effect>.+?)\\s*" +
        // Effect ends at: a [[br]], the next "When …" trigger, a card's own 《cost》: special-ability
        // marker, or end of text. The (?<!\") guard keeps a 《cost》: that sits INSIDE a quoted granted
        // ability (e.g. Machinist's "《Dull》: …", Medusa's "《5》: …") from prematurely ending the effect.
        "(?=\\s*\\[\\[br\\]\\]|\\s*When\\s+[^,]+?\\s+(?:forms?\\s+a\\s+party\\s+and\\s+attacks?|attacks?|blocks?|enters?|leaves?|is\\s+(?:put|removed|blocked|dealt)|(?:is|are)\\s+(?:added|priming)|deals?|uses?|becomes?|searches?|discards?|gains?)|\\s*(?<!\")(?:《[^》]+》)+\\s*:|\\s*$)",
        Pattern.DOTALL
    );

    /**
     * A damage gate written inline at the head of an auto-ability's effect: "if you have received
     * N points of damage or more, &lt;effect&gt;" — 4-129L Steiner.
     *
     * <p>Semantically identical to the "Damage N --" prefix {@link #AUTO_ABILITY_PATTERN} captures
     * as {@code threshold}, so {@link #parseAutoAbilities} lifts it into the same field rather than
     * asking the resolver to carry a conditional it has no other use for.
     *
     * <p>Anchored at the start: a gate appearing later in the text is qualifying some clause of a
     * larger effect ("… If you have received 5 points of damage or more, … instead"), which is a
     * different thing entirely and belongs to whatever parser owns that effect.
     * <ul>
     *   <li>Group {@code damage} — the damage-counter threshold</li>
     *   <li>Group {@code effect} — the gated effect, with the condition removed</li>
     * </ul>
     */
    private static final Pattern INLINE_DAMAGE_GATE = Pattern.compile(
        "(?i)^if\\s+you\\s+have\\s+received\\s+(?<damage>\\d+)\\s+points?\\s+of\\s+damage" +
        "(?:\\s+or\\s+more)?,\\s*(?<effect>.+)$",
        Pattern.DOTALL
    );

    /**
     * Rewrites "When X, a Y[, a Z]* or a W [trigger]" into "When X or a Y[ or a Z]* or a W [trigger]"
     * so {@link #AUTO_ABILITY_PATTERN} can capture the full disjunctive subject as a single
     * {@code (?<card>[^,]+?)} group. The matched compound subject is later split at the dispatcher
     * (see {@code AutoAbilityTriggers#matchesEntersFieldSubject}).
     * Group {@code head} is the first subject (typically the source card's own name);
     * group {@code rest} is the comma-separated list of additional "a/an X" subjects ending in
     * "or a/an Y". A lookahead requires a trigger verb to follow so unrelated comma-bearing
     * "When …" sentences are not rewritten.
     */
    private static final Pattern MULTI_SUBJECT_TRIGGER = Pattern.compile(
        "(?i)(?<prefix>When\\s+)(?<head>[^,]+?)" +
        "(?<rest>(?:,\\s+an?\\s+[^,]+?)+\\s+or\\s+an?\\s+[^,]+?)" +
        "\\s+(?=enters?|attacks?|blocks?|leaves?|is\\s+(?:put|blocked|removed|dealt)|deals?|forms?|casts?|receives?|primes?)"
    );

    /**
     * "When [event A], or when [event B], [effect]" — two whole triggers sharing one effect, as
     * distinct from {@link #MULTI_SUBJECT_TRIGGER}'s several subjects sharing one trigger verb.
     * Palom 2-016R is the only printing: "When Palom deals damage to your opponent, or when the
     * Forward damaged by Palom is put from the field into the Break Zone during the same turn, …".
     *
     * <p>Groups: {@code first} and {@code second} (each an unpunctuated subject-plus-trigger
     * clause) and {@code effect}, which runs to the end of the segment.
     *
     * <p>{@link #expandAlternativeTriggers} splits it into two sentences rather than trying to
     * teach {@link #AUTO_ABILITY_PATTERN} a second trigger in one match. Two independent abilities
     * is also the right reading: the card fires on each occurrence of either event, not once for
     * the pair.
     */
    private static final Pattern ALTERNATIVE_TRIGGERS = Pattern.compile(
        "(?i)When\\s+(?<first>[^,]+?),\\s*or\\s+when\\s+(?<second>[^,]+?),\\s+" +
        "(?<effect>.+?)(?=\\s*\\[\\[br\\]\\]|\\s*$)",
        Pattern.DOTALL
    );

    /**
     * Joins "select N of M following actions" headers with their [[br]]-delimited quoted
     * action strings so that {@link #AUTO_ABILITY_PATTERN} captures the full effect as one unit.
     * Input: {@code ...select 1 of the 2 following actions.[[br]] "A."[[br]] "B."...}
     * Output: {@code ...select 1 of the 2 following actions. "A." "B."...}
     *
     * <p>A restriction sentence may sit between the header and the quoted list — 15-115H Penelo
     * reads "Select 1 of the 3 following actions. You can only use this ability once per turn."
     * before its options. It is absorbed into the joined effect rather than terminating the join;
     * without that the options are lost and the ability parses to nothing. The run is bounded by
     * {@code [^"\[]} so it can never cross a quote or a {@code [[br]]} and swallow a neighbouring
     * ability.
     */
    private static final Pattern SELECT_ACTIONS_JOINER = Pattern.compile(
        "(?i)((?:[^.!?]*,\\s+)?select\\s+" +
        "(?:" +
          "(?:up\\s+to\\s+)?\\d+\\s+of\\s+the\\s+\\d+\\s+following\\s+actions?" +  // "select N of the M following actions"
          "|the\\s+following\\s+actions?[^.!?]*" +                                   // "select the following actions..."
        ")" +
        "[.!]?)([^\"\\[]*?(?:\\s*\\[\\[br\\]\\]\\s*\"[^\"]+\")+)",
        Pattern.DOTALL
    );

    /**
     * Matches "At the beginning of the Attack Phase during each of your turns, [effect]".
     * Named group {@code effect} captures the effect text.
     */
    private static final Pattern AT_BEGINNING_OF_ATTACK_PHASE_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+beginning\\s+of\\s+the\\s+Attack\\s+Phase\\s+during\\s+each\\s+of\\s+your\\s+turns,\\s+" +
        "(?<effect>.+?)\\s*" +
        "(?=\\s*\\[\\[br\\]\\]|\\s*At\\s+the\\s+beginning|\\s*When\\s+[^,]+?\\s+" +
        "(?:attacks?|blocks?|enters?|leaves?|is\\s+(?:put|removed))|\\s*$)",
        Pattern.DOTALL
    );

    /**
     * Matches "At the beginning of the Attack Phase during each player's turn, [effect]" — the
     * both-turns variant of {@link #AT_BEGINNING_OF_ATTACK_PHASE_PATTERN} (The Crystal Exarch
     * 13-133S, Sanctuary Keeper 19-094R, Sol 18-106H).  Named group {@code effect} captures the
     * effect text.
     */
    private static final Pattern AT_BEGINNING_OF_ATTACK_PHASE_EACH_TURN_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+beginning\\s+of\\s+the\\s+Attack\\s+Phase\\s+during\\s+each\\s+player'?s\\s+turns?,\\s+" +
        "(?<effect>.+?)\\s*" +
        "(?=\\s*\\[\\[br\\]\\]|\\s*At\\s+the\\s+beginning|\\s*When\\s+[^,]+?\\s+" +
        "(?:attacks?|blocks?|enters?|leaves?|is\\s+(?:put|removed))|\\s*$)",
        Pattern.DOTALL
    );

    /**
     * Matches "At the beginning of your opponent's Attack Phase, [effect]" — Ardyn 8-068L.  The
     * opponent-turn twin of {@link #AT_BEGINNING_OF_ATTACK_PHASE_PATTERN}, which names "each of your
     * turns" and so never claims this wording.  Named group {@code effect} captures the effect text;
     * {@code threshold} captures the segment's optional "Damage N -- " gate, which this pass has to
     * read itself because it scans the whole card text rather than the stripped segments.
     */
    private static final Pattern AT_BEGINNING_OF_OPP_ATTACK_PHASE_PATTERN = Pattern.compile(
        "(?i)(?:Damage\\s+(?<threshold>\\d+)\\s+--\\s+)?" +
        "At\\s+the\\s+beginning\\s+of\\s+your\\s+opponent'?s\\s+Attack\\s+Phase,\\s+" +
        "(?<effect>.+?)\\s*" +
        "(?=\\s*\\[\\[br\\]\\]|\\s*At\\s+the\\s+beginning|\\s*When\\s+[^,]+?\\s+" +
        "(?:attacks?|blocks?|enters?|leaves?|is\\s+(?:put|removed))|\\s*$)",
        Pattern.DOTALL
    );

    /**
     * Matches "During each turn, when [subject] is chosen by your opponent's [Summon | ability |
     * Summon or ability] for the first time in that turn, [effect]" — Colkhab 18-041C, Owe 17-092R,
     * Illua 5-099H, The Fiend 20-114L.
     *
     * <p>The same reactive trigger the "When [subject] is chosen by …" printings carry, with the
     * per-turn limit stated up front instead of as a trailing "This effect will trigger only once
     * per turn." sentence. Because the segment opens with "During" rather than "When", neither
     * {@link #AUTO_ABILITY_PATTERN} nor the {@link #FA_AUTO_PREFIX} exclusion saw it, and the whole
     * family fell through to the field-ability list unparsed.
     *
     * <p>Groups: {@code card} — the trigger subject, matched the same way every other chosen-by
     * subject is; {@code by} — the chooser clause, which picks the Summon-only trigger apart from
     * the Summon-or-ability one; {@code effect}; {@code threshold} — the segment's optional
     * "Damage N -- " gate, captured here because this pass scans the whole card text rather than the
     * already-stripped segments {@link #parseFieldAbilities} works from. The Fiend 20-114L prints
     * one, and dropping it would leave the ability live from turn one.
     */
    private static final Pattern DURING_EACH_TURN_CHOSEN_FIRST_TIME_PATTERN = Pattern.compile(
        "(?i)(?:Damage\\s+(?<threshold>\\d+)\\s+--\\s+)?" +
        // The subject must not run past a segment break. It is lazy, but a card printing two of
        // these (The Fiend 20-114L) gives it a second "is chosen by" to reach, and it took it —
        // swallowing the intervening segment, the "Damage 3 -- " gate included, into the name.
        "During\\s+each\\s+turn,\\s+when\\s+(?<card>(?:(?!\\[\\[br\\]\\]).)+?)\\s+is\\s+chosen\\s+by\\s+your\\s+opponent'?s\\s+" +
        "(?<by>Summons?\\s+or\\s+(?:an?\\s+)?abilit(?:y|ies)|Summons?|abilit(?:y|ies))\\s+" +
        "for\\s+the\\s+first\\s+time\\s+in\\s+that\\s+turn,\\s+" +
        "(?<effect>.+?)\\s*" +
        "(?=\\s*\\[\\[br\\]\\]|\\s*$)",
        Pattern.DOTALL
    );

    /**
     * Matches the ordinal cast trigger: "During each turn, when you cast the [ordinal] [card |
     * Summon] you've cast, [effect]" — Shikaree G 15-051C, Atomos 16-043H, Belgemine 24-052L —
     * and the equivalent "When you cast the [ordinal] card you've cast this turn, [effect]" that
     * Rosa 14-057H prints instead.
     *
     * <p>The two spellings are one trigger. "During each turn" and the trailing "this turn" both
     * say the count restarts each turn, which is what the per-turn cast counters already do, so
     * each is optional here rather than meaning anything different. Neither shape could reach
     * {@link #AUTO_ABILITY_PATTERN}: its trigger list is closed, and "cast the Nth card" is not on
     * it — so the whole family fell through to the field-ability list unparsed.
     *
     * <p>Groups: {@code ordinal} — the position word, resolved by {@link #ordinalValue};
     * {@code what} — "card" or "Summon", which decides which of the two per-turn counters the
     * trigger is measured against; {@code effect}; {@code threshold} — the optional "Damage N -- "
     * gate, captured for the same reason the chosen-first-time pass captures one.
     */
    private static final Pattern DURING_EACH_TURN_NTH_CAST_PATTERN = Pattern.compile(
        "(?i)(?:Damage\\s+(?<threshold>\\d+)\\s+--\\s+)?" +
        "(?:During\\s+each\\s+turn,\\s+)?when\\s+you\\s+cast\\s+the\\s+" +
        "(?<ordinal>first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)\\s+" +
        "(?<what>cards?|Summons?)\\s+you've\\s+cast(?:\\s+this\\s+turn)?,\\s+" +
        "(?<effect>.+?)\\s*" +
        "(?=\\s*\\[\\[br\\]\\]|\\s*$)",
        Pattern.DOTALL
    );

    /** The position words {@link #DURING_EACH_TURN_NTH_CAST_PATTERN} accepts, in order from 1. */
    private static final List<String> ORDINAL_WORDS = List.of(
            "first", "second", "third", "fourth", "fifth",
            "sixth", "seventh", "eighth", "ninth", "tenth");

    /** The 1-based position {@code word} names, or 0 when it is not one this parser knows. */
    private static int ordinalValue(String word) {
        int i = ORDINAL_WORDS.indexOf(word.trim().toLowerCase(Locale.ROOT));
        return i < 0 ? 0 : i + 1;
    }

    /**
     * The trigger key an ordinal cast ability carries, and the one
     * {@code AutoAbilityTriggers} fires as each cast is recorded. Built here rather than spelled
     * out at both ends so the parser and the dispatcher cannot disagree about it.
     *
     * @param summon whether the printing counts Summons rather than cards of any type
     * @param n      the 1-based position within the turn
     */
    static String nthCastTrigger(boolean summon, int n) {
        return "cast nth " + (summon ? "summon " : "card ") + n;
    }

    /**
     * Separate pattern for "When a Warp Counter is removed from [CardName], [effect]".
     * Uses {@code target} for the card whose counter is decremented.
     */
    private static final Pattern WARP_COUNTER_PATTERN = Pattern.compile(
        "(?i)When\\s+a\\s+Warp\\s+Counter\\s+is\\s+removed\\s+from\\s+(?<target>[^,]+?)\\s*,\\s+" +
        "(?<youmay>(?:you|your\\s+opponent)\\s+may\\s+)?" +
        "(?<effect>.+?)\\s*" +
        "(?=\\s*\\[\\[br\\]\\]|\\s*When\\s+[^,]+?\\s+(?:attacks?|blocks?|enters?|leaves?|is\\s+(?:put|removed|blocked))|\\s*(?:《[^》]+》)+\\s*:|\\s*$)",
        Pattern.DOTALL
    );

    /**
     * Matches "When [CardName] enters the field or at the beginning of [Main Phase 1 | the
     * Attack Phase] during each of your turns, [effect]" — a compound trigger combining an ETF
     * condition with a recurring phase trigger (e.g. Vayne, Alus, Orphan, Number 24, Yiazmat).
     * Group {@code phase} distinguishes Main Phase 1 from the Attack Phase.
     */
    private static final Pattern ETF_OR_PHASE_TRIGGER_PATTERN = Pattern.compile(
        "(?i)When\\s+(?<card>[A-Z][A-Za-z0-9''\\-\\s\\(\\)]+?)\\s+enters\\s+the\\s+field\\s+or\\s+at\\s+the\\s+beginning\\s+of\\s+" +
        "(?<phase>(?:your\\s+)?Main\\s+Phase\\s+1|the\\s+Attack\\s+Phase)\\s+during\\s+each\\s+of\\s+your\\s+turns\\s*,\\s+" +
        "(?<effect>.+?)\\s*" +
        "(?=\\s*\\[\\[br\\]\\]|\\s*When\\s+[^,]+?\\s+(?:attacks?|blocks?|enters?|leaves?|is\\s+(?:put|removed|blocked))|\\s*(?:《[^》]+》)+\\s*:|\\s*$)",
        Pattern.DOTALL
    );

    /**
     * Matches priming triggers in two forms:
     * <ol>
     *   <li>Pure: {@code "When [PrimerCard] primes into [TargetCard], [effect]"}</li>
     *   <li>Combined: {@code "When [TargetCard] [trigger] [, extra] or when [PrimerCard] primes into [TargetCard], [effect]"}</li>
     * </ol>
     * Named groups: {@code pretarget}, {@code pretrigger}, {@code preextra} (optional preceding
     * trigger clause), {@code primer} (card initiating the prime), {@code target} (card being
     * primed into), {@code youmay}, and {@code effect}.
     */
    private static final Pattern PRIMES_INTO_PATTERN = Pattern.compile(
        "(?i)" +
        // Optional preceding clause: "When [TargetCard] [trigger] [, extra] or"
        "(?:When\\s+(?<pretarget>[^,]+?)\\s+" +
        "(?<pretrigger>" +
            "attacks?(?:\\s+or\\s+blocks?)?" +
            "|blocks?(?:\\s+or\\s+is\\s+blocked)?" +
            "|is\\s+blocked" +
            "|enters?\\s+the\\s+field(?:\\s+due\\s+to\\s+(?:your\\s+cast|Warp))?" +
            "|leaves?\\s+the\\s+field" +
            "|deals?\\s+damage\\s+to\\s+your\\s+opponent" +
            "|deals?\\s+damage\\s+to\\s+a\\s+Forward" +
        ")(?:\\s*,\\s*(?<preextra>[^.!]*?))?\\s+or\\s+)?" +
        // Prime trigger: "[W]hen [PrimerCard] primes into [TargetCard],"
        "when\\s+(?<primer>[^,]+?)\\s+primes?\\s+into\\s+(?<target>[^,]+?)\\s*,\\s+" +
        "(?<youmay>(?:you|your\\s+opponent)\\s+may\\s+)?" +
        "(?<effect>.+?)\\s*" +
        "(?=\\s*\\[\\[br\\]\\]|\\s*when\\s+[^,]+?\\s+(?:attacks?|blocks?|enters?|leaves?|is\\s+(?:put|removed|blocked)|deals?|primes?)|\\s*(?:《[^》]+》)+\\s*:|\\s*$)",
        Pattern.DOTALL
    );

    /**
     * Matches "When [CardName] or your [Element] Summon deals damage to a Forward, [effect]".
     * Produces two {@link AutoAbility} entries: one for the named card's battle damage and one
     * for the element-typed Summon's ability damage (e.g. Ramuh + Lightning Summon).
     */
    private static final Pattern BREAKTOUCH_SUMMON_PATTERN = Pattern.compile(
        "(?i)When\\s+(?<card>[^,]+?)\\s+or\\s+your\\s+(?<element>\\w+)\\s+Summon\\s+deals?\\s+damage\\s+to\\s+a\\s+Forward\\s*,\\s+" +
        "(?<effect>.+?)\\s*" +
        "(?=\\s*\\[\\[br\\]\\]|\\s*When\\s+[^,]+?\\s+(?:attacks?|blocks?|enters?|leaves?|is\\s+(?:put|removed)|deals?)|\\s*(?:《[^》]+》)+\\s*:|\\s*$)",
        Pattern.DOTALL
    );

    /** "a Forward other than [name] you control" — subject of a watcher attack trigger. */
    static final Pattern OTHER_FORWARD_SUBJECT = Pattern.compile(
        "(?i)^a\\s+Forward\\s+other\\s+than\\s+.+?\\s+you\\s+control$"
    );

    /**
     * "a Forward you control" — the unrestricted watcher attack subject (Cloud 1-187S). The same
     * trigger as {@link #OTHER_FORWARD_SUBJECT} with nobody excluded, so it is classified alongside
     * it and told apart at dispatch by {@code AutoAbilityTriggers#matchesOtherForwardSubject}.
     *
     * <p>The carrier is <em>not</em> excluded: "a Forward you control" describes Cloud too, so
     * Cloud attacking gives Cloud the boost. That is what separates the two subjects, and it is why
     * this one cannot simply be folded into the "other than" pattern with an optional clause.
     */
    static final Pattern ANY_OWN_FORWARD_SUBJECT = Pattern.compile(
        "(?i)^a\\s+Forward\\s+you\\s+control$"
    );

    /**
     * "[a|an|the] [type][ you control| opponent controls] [damaged by | that took damage from]
     * [name]" — the break-zone trigger subject of the printings that ask <em>who dealt the
     * damage</em> rather than merely describing the departing card: Galuf 15-066C, Firion 16-120C,
     * Tifa 23-012C, Delita 16-014R, Machina 3-022H, Vermilion Bird l'Cie Zhuyu 5-011H, Bahamut
     * 24-015C, both Cid Highwinds (1-072R, 1-073C), and the copy Morrow 11-013R hands itself in
     * quotes.
     *
     * <p>Groups: {@code subject} (everything ahead of the damage clause, which is an ordinary
     * type/controller phrase) and {@code name} (the damager, always the printing card).
     *
     * <p>The two wordings say the same thing from opposite ends — the Cid Highwinds are the only
     * printings to put the departing card in the subject position — so they resolve to one trigger
     * rather than two. Without the second arm those two would still have matched the widened
     * break-zone arm of {@link #AUTO_ABILITY_PATTERN} and been classified as ordinary "put into
     * break zone" triggers, whose subject matcher has no way to answer "that took damage from Cid
     * Highwind": they would have counted as parsed and then never fired.
     *
     * <p>Kept here rather than beside the matcher in {@code AutoAbilityTriggers} for the reason
     * {@link #FILTER_FORWARD_SUBJECT} is: one definition, shared by the classifier that assigns the
     * trigger and the runtime check that answers it, so the two cannot drift apart.
     */
    static final Pattern DAMAGED_BY_BZ_SUBJECT = Pattern.compile(
        "(?i)^(?<subject>.+?)\\s+(?:damaged\\s+by|that\\s+took\\s+damage\\s+from)\\s+(?<name>.+?)\\s*$"
    );

    /**
     * "[a | N or more] Job X [or a Card Name Y] [Forward(s)] [other than Z] you control" — subject
     * of a filtered-forward attack trigger.
     *
     * <p>The plain "a Job X you control" form fires once per qualifying attacker. The count form
     * ("1 or more Job Member of the Turks Forwards other than Cissnei you control", Cissnei
     * 22-028H) describes the whole declaration, so it fires once no matter how many members of the
     * attacking party qualify — {@code count} is what tells the trigger code which it is.
     *
     * <p>Groups: {@code count}, {@code type1}/{@code val1}, {@code type2}/{@code val2},
     * {@code fwdnoun}, {@code exclude}. This is the single definition — {@code AutoAbilityTriggers}
     * matches against it too, so the classifier and the runtime check cannot drift apart.
     */
    static final Pattern FILTER_FORWARD_SUBJECT = Pattern.compile(
        "(?i)^(?:a|(?<count>\\d+)\\s+or\\s+more)\\s+" +
        "(?<type1>Job|Card\\s+Name)\\s+(?<val1>.+?)" +
        "(?:\\s+or\\s+a\\s+(?<type2>Job|Card\\s+Name)\\s+(?<val2>.+?))?" +
        "(?<fwdnoun>\\s+Forwards?)?" +
        "(?:\\s+other\\s+than\\s+(?<exclude>.+?))?" +
        "\\s+you\\s+control$"
    );

    /** Matches the restriction sentence appended to a auto-ability effect, capturing flags. */
    private static final Pattern FA_TRIGGER_RESTRICTION = Pattern.compile(
        "(?i)[.!,]?\\s*This\\s+effect\\s+will\\s+trigger\\s+only\\s+" +
        "(?:(?<yourTurn>during\\s+your\\s+turn)(?:\\s+and\\s+only\\s+)?)?(?<once>once\\s+per\\s+turn)?[.!]?\\s*$"
    );

    /** Matches "This effect will trigger only if [card] is removed from the game." */
    private static final Pattern FA_RFP_CONDITION = Pattern.compile(
        "(?i)[.!,]?\\s*This\\s+effect\\s+will\\s+trigger\\s+only\\s+if\\s+(?<rfpCard>[^.!]+?)\\s+is\\s+removed\\s+from\\s+the\\s+game[.!]?\\s*$"
    );

    /** Matches "This effect will trigger only if [card] is in the Break Zone." */
    private static final Pattern FA_BZ_CONDITION = Pattern.compile(
        "(?i)[.!,]?\\s*This\\s+effect\\s+will\\s+trigger\\s+only\\s+if\\s+(?<bzCard>[^.!]+?)\\s+is\\s+in\\s+the\\s+Break\\s+Zone[.!]?\\s*$"
    );

    /**
     * Matches a prefix condition requiring a named card (optionally with a specific Job) to be
     * in the owner's Break Zone:
     * "if you have a Card Name X [with Job Y] in your Break Zone, "
     */
    private static final Pattern FA_BZ_HAVE_CONDITION = Pattern.compile(
        "(?i)^if\\s+you\\s+have\\s+a\\s+Card\\s+Name\\s+(?<bzCard>.+?)" +
        "(?:\\s+with\\s+Job\\s+(?<bzJob>.+?))?\\s+in\\s+your\\s+Break\\s+Zone,\\s+"
    );

    /**
     * Matches a prefix condition requiring the card's cast cost to have been paid with CP from
     * N or more different element types:
     * "if the cost to cast X was paid with CP of N or more different Elements, "
     */
    private static final Pattern FA_CAST_PAYMENT_ELEMENTS = Pattern.compile(
        "(?i)^if\\s+the\\s+cost\\s+to\\s+cast\\s+[^,]+?\\s+was\\s+paid\\s+with\\s+CP\\s+of\\s+(?<n>\\d+)\\s+or\\s+more\\s+different\\s+Elements,?\\s+"
    );

    /**
     * Parses all Auto Abilities ("When X Y, Z") from {@code textEn}.
     * The returned list is immutable.
     */
    /**
     * Joins "select N of M following actions" headers with their [[br]]-delimited quoted
     * action strings into a single line so {@link #AUTO_ABILITY_PATTERN} captures them together.
     */
    /**
     * Rewrites "If N or more [filter] Forwards form the party, also [effect]." inline sentences
     * into a full "When N or more [filter] Forwards you control form a party and attack, [effect]."
     * trigger, preceded by [[br]] so AUTO_ABILITY_PATTERN treats them as a separate auto-ability.
     */
    private static String expandPartyAttackFollowups(String text) {
        Matcher m = PARTY_ATTACK_FOLLOWUP_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String count    = m.group("count");
            String category = m.group("category");
            String job      = m.group("job");
            String effect   = m.group("effect").trim();
            String filter   = category != null ? "Category " + category + " "
                            : job      != null ? "Job " + job + " "
                            : "";
            String replacement = "[[br]]When " + count + " or more " + filter
                    + "Forwards you control form a party and attack, " + effect + ".";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Rewrites "When X, a Y[, a Z]* or a W [trigger]" into "When X or a Y[ or a Z]* or a W [trigger]"
     * so {@link #AUTO_ABILITY_PATTERN}'s comma-delimited card group can capture the whole
     * disjunctive subject in one match. The compound subject is split downstream when the
     * trigger fires.
     */
    /**
     * Rewrites "When A, or when B, [effect]" into two sentences carrying the same effect, split by
     * {@code [[br]]} so {@link #AUTO_ABILITY_PATTERN} reads each as its own auto-ability.
     *
     * <p>Without this the pattern matched the first trigger and stopped at the "or when …" that
     * follows it, producing an ability whose whole effect was the word "or" — and losing the second
     * trigger entirely. See {@link #ALTERNATIVE_TRIGGERS}.
     */
    private static String expandAlternativeTriggers(String text) {
        Matcher m = ALTERNATIVE_TRIGGERS.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String effect = m.group("effect").trim();
            String replacement = "When " + m.group("first").trim() + ", " + effect
                    + "[[br]]When " + m.group("second").trim() + ", " + effect;
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String expandMultiSubjectTriggers(String text) {
        Matcher m = MULTI_SUBJECT_TRIGGER.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String prefix = m.group("prefix");
            String head   = m.group("head");
            // Strip the leading comma from `rest`, then replace any remaining inner commas with " or ".
            String rest   = m.group("rest").replaceFirst("^,\\s+", "").replaceAll(",\\s+", " or ");
            m.appendReplacement(sb, Matcher.quoteReplacement(prefix + head + " or " + rest + " "));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Classifies a "discards … due to your Summons or abilities" trigger by what was discarded.
     *
     * <p>27-036L Locke carries two of these at once — one for Characters and one for Summons — so
     * the discarded type has to survive into the trigger label or both would fire on any discard.
     * Only the text before "due to" is inspected: the tail names the <em>cause</em> and always
     * contains "Summons", which would otherwise read as a Summon being discarded.
     */
    private static String discardByEffectTrigger(String triggerRaw) {
        int dueTo = triggerRaw.indexOf("due to");
        String discarded = dueTo < 0 ? triggerRaw : triggerRaw.substring(0, dueTo);
        if (discarded.contains("character")) return "opponent discards character by effect";
        if (discarded.contains("summon"))    return "opponent discards summon by effect";
        return "opponent discards by effect";
    }

    /**
     * True if {@code index} falls inside a sentence opened by a "When …, " trigger.
     *
     * <p>Used to tell a standalone timing ability ("At the end of your opponent's turn, …" as its
     * own line) from the delayed half of a triggered one, which the whole-text passes would
     * otherwise lift out and strip of the context that gives it meaning. Scope is the current
     * {@code [[br]]} segment, since a segment break always ends an ability.
     */
    private static boolean isInsideTriggeredSentence(String text, int index) {
        int segStart = text.lastIndexOf("[[br]]", index);
        String segment = text.substring(segStart < 0 ? 0 : segStart, index);
        return TRIGGER_CLAUSE_OPENER.matcher(segment).find();
    }

    /** A "When &lt;subject&gt; &lt;trigger verb&gt;," clause opening a triggered ability. */
    private static final Pattern TRIGGER_CLAUSE_OPENER = Pattern.compile(
        "(?i)\\bWhen(?:ever)?\\s+[^,]+?\\s+(?:attacks?|blocks?|enters?|leaves?|casts?|uses?|becomes?" +
        "|deals?\\s+damage|is\\s+(?:put|removed|blocked|dealt|chosen))[^,]*,"
    );

    private static String joinSelectActions(String text) {
        Matcher m = SELECT_ACTIONS_JOINER.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String header  = m.group(1);
            String actions = m.group(2).replaceAll("\\[\\[br\\]\\]\\s*", " ").trim();
            m.appendReplacement(sb, Matcher.quoteReplacement(header + " " + actions));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** True when {@code pattern} matches somewhere in {@code text} outside every quoted span. */
    private static boolean matchesOutsideQuotes(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        while (m.find()) if (!isInsideQuotes(text, m.start())) return true;
        return false;
    }

    /** True when {@code index} falls inside a double-quoted span of {@code text}. */
    private static boolean isInsideQuotes(String text, int index) {
        int quotes = 0;
        for (int i = 0; i < index; i++) if (text.charAt(i) == '"') quotes++;
        return quotes % 2 == 1;
    }

    /**
     * "During your opponent's turn, [trigger sentence]" — Lunafreya 8-132L.
     *
     * <p>The remainder is a whole trigger sentence rather than an effect: what the gate restricts is
     * when the trigger may fire, so it is lifted off and the sentence behind it read as any other.
     * Group {@code rest} runs to the next {@code [[br]]} or to the end of the text.
     */
    private static final Pattern DURING_OPP_TURN_TRIGGER_PATTERN = Pattern.compile(
        "(?i)During\\s+your\\s+opponent'?s\\s+turn,\\s+(?<rest>When\\b.+?)(?=\\s*\\[\\[br\\]\\]|\\s*$)",
        Pattern.DOTALL
    );

    /**
     * A quoted granted ability that carries a trigger of its own.
     *
     * <p>"At the beginning" counts alongside "When": a phase trigger handed to a card by a grant is
     * as much a trigger as an event one, and four printings spell one that way without using the
     * word "When" at all (Sabin 15-018C, Lann 16-102R, Reynn 16-105R, Titan (XVI) 29-068L). Left
     * unmasked, Sabin's granted "At the beginning of Main Phase 1 during each of your turns, …"
     * was read as a standing ability of his own, and the effect it produced was the quotation's
     * tail — closing quote, duration clause and all.
     */
    private static final Pattern QUOTED_TRIGGER_SPAN =
            Pattern.compile("(?i)\"(?=[^\"]*(?:\\bWhen\\b|\\bAt\\s+the\\s+beginning\\b))[^\"]+\"");

    /**
     * Token standing in for a masked quoted ability. Deliberately bare letters and digits: it has
     * to survive every rewrite {@link #parseAutoAbilities} performs without looking like a trigger,
     * a subject, a sentence break, or a cost marker to any of the patterns those rewrites use.
     */
    private static final String QUOTE_MASK_PREFIX = "QUOTEDABILITY";

    /**
     * Replaces each quoted trigger-bearing span of {@code text} with an inert token, appending the
     * spans to {@code out} in the order their tokens are numbered.
     */
    private static String maskQuotedTriggerSpans(String text, List<String> out) {
        Matcher m = QUOTED_TRIGGER_SPAN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, QUOTE_MASK_PREFIX + out.size());
            out.add(m.group());
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * An action ability's cost marker: one or more {@code 《…》} tokens then a colon, not preceded
     * by a quotation mark.
     *
     * <p>The same shape {@link #AUTO_ABILITY_PATTERN} already uses to decide where an auto
     * ability's effect ends, quote guard included — a {@code 《5》:} inside a granted ability
     * (Medusa's petrification removal) is not this card's own cost.
     */
    private static final Pattern ACTION_ABILITY_COST_MARKER =
            Pattern.compile("(?<!\")(?:《[^》]+》)+\s*:");

    /**
     * {@code text} with each {@code [[br]]} segment truncated at its action-ability cost marker.
     *
     * <p>Everything after that colon is the action ability's effect, and a trigger sentence printed
     * inside one belongs to that ability rather than standing on its own. Ardyn 26-122H is what
     * this is for: "Damage 5 -- 《0》: Play Ardyn onto the field. When Ardyn enters the field, Ardyn
     * deals you 1 point of damage." Read as a standing ability, the second sentence hurt its
     * controller every time Ardyn entered the field by any route, including an ordinary cast — but
     * the printing only says it happens when the Break Zone ability puts him there, and the
     * resolver already runs it as the ability's second sentence.
     *
     * <p>No auto ability's effect text is shortened by this: {@code AUTO_ABILITY_PATTERN} already
     * ends an effect at the same marker, so a trigger printed <em>before</em> the cost keeps
     * exactly the text it had.
     */
    private static String truncateAtActionAbilityCost(String text) {
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (pos <= text.length()) {
            int br = text.indexOf("[[br]]", pos);
            int end = br < 0 ? text.length() : br;
            String segment = text.substring(pos, end);
            Matcher m = ACTION_ABILITY_COST_MARKER.matcher(segment);
            out.append(m.find() ? segment.substring(0, m.end()) : segment);
            if (br < 0) break;
            out.append("[[br]]");
            pos = br + "[[br]]".length();
        }
        return out.toString();
    }

    /** Puts the spans {@link #maskQuotedTriggerSpans} took out back into an extracted effect. */
    private static String unmaskQuotedTriggerSpans(String text, List<String> masked) {
        for (int i = 0; i < masked.size(); i++) {
            String token = QUOTE_MASK_PREFIX + i;
            if (text.contains(token)) text = text.replace(token, masked.get(i));
        }
        return text;
    }

    public static List<AutoAbility> parseAutoAbilities(String textEn) {
        if (textEn == null || textEn.isBlank()) return List.of();
        textEn = joinSelectActions(textEn);
        List<AutoAbility> result = new ArrayList<>();
        // Hide double-quoted substrings that contain a trigger word ("When") so that quoted
        // ability text inside a grant (e.g. "When X attacks, ...") is never incorrectly
        // registered as a permanent auto-ability of this card.
        // We intentionally leave bare quoted status text (e.g. "X cannot be blocked.")
        // in place so the surrounding ability effect is captured in full.
        //
        // Masked with an inert token rather than deleted: when the quoted ability is the *object*
        // of a grant ("Odin (XVI) gains "When a Character enters your opponent's field, dull it
        // and Freeze it."") deleting it left the grant reading "Odin (XVI) gains  (This effect…)",
        // losing the very ability being granted. The tokens are swapped back before returning.
        List<String> maskedQuotes = new ArrayList<>();
        String textForSearch = truncateAtActionAbilityCost(maskQuotedTriggerSpans(textEn, maskedQuotes));

        // First pass: "when [PrimerCard] primes into [TargetCard], [effect]"
        // Also handles "When [Target] [trigger] [, extra] or when [Primer] primes into [Target], [effect]"
        // Matched regions are stripped from textForSearch before the remaining passes run.
        Matcher pm = PRIMES_INTO_PATTERN.matcher(textForSearch);
        StringBuffer strippedBuf = new StringBuffer();
        while (pm.find()) {
            String primer    = pm.group("primer").trim();
            String youMayRaw = pm.group("youmay");
            boolean opponentMay = youMayRaw != null
                    && youMayRaw.trim().toLowerCase(Locale.ROOT).startsWith("your opponent");
            boolean youMay  = youMayRaw != null && !opponentMay;
            String effect = SUMMON_MARKUP.matcher(pm.group("effect").trim()).replaceAll("").trim();
            if (!effect.isEmpty()) {
                // Prime trigger: triggerCard = primer card name
                AutoAbility primeFA = parseAutoAbilityRestrictions(
                        primer, "primed into", youMay, opponentMay, false, false, effect, 0);
                if (primeFA != null) result.add(primeFA);

                // Optional preceding trigger clause
                String pretargetRaw  = pm.group("pretarget");
                String pretriggerRaw = pm.group("pretrigger");
                if (pretargetRaw != null && pretriggerRaw != null) {
                    String pretarget  = pretargetRaw.trim();
                    String pretrigRaw = pretriggerRaw.trim().toLowerCase(Locale.ROOT);
                    boolean castOnly  = pretrigRaw.contains("due to your cast");
                    boolean warpOnly  = pretrigRaw.contains("enter") && pretrigRaw.contains("warp");
                    boolean preIsParty = pretarget.toLowerCase(Locale.ROOT).contains("party");
                    String preTrig = normalizePretrigger(pretrigRaw, pretarget, preIsParty, warpOnly);
                    AutoAbility preFA = parseAutoAbilityRestrictions(
                            pretarget, preTrig, youMay, opponentMay, castOnly, warpOnly, effect, 0);
                    if (preFA != null) result.add(preFA);

                    // Extra trigger phrase between preceding comma and "or" (e.g. "deals damage to your opponent")
                    String preextra = pm.group("preextra");
                    if (preextra != null && !preextra.isBlank()) {
                        String extraTrig = normalizePretrigger(
                                preextra.trim().toLowerCase(Locale.ROOT), pretarget, false, false);
                        AutoAbility extraFA = parseAutoAbilityRestrictions(
                                pretarget, extraTrig, youMay, opponentMay, false, false, effect, 0);
                        if (extraFA != null) result.add(extraFA);
                    }
                }
            }
            pm.appendReplacement(strippedBuf, "");
        }
        pm.appendTail(strippedBuf);
        textForSearch = strippedBuf.toString();

        // Second pass: "When [CardName] enters the field or at the beginning of [phase] during
        // each of your turns, [effect]" — registers two AutoAbility entries sharing the same
        // effect text: one for entering the field, one for the recurring phase trigger. Matched
        // regions are stripped from textForSearch first so the later phase-trigger passes below
        // (which independently scan the same text) don't also pick up this same clause and
        // produce a duplicate entry.
        Matcher efm = ETF_OR_PHASE_TRIGGER_PATTERN.matcher(textForSearch);
        StringBuffer efBuf = new StringBuffer();
        while (efm.find()) {
            String card      = efm.group("card").trim();
            String phaseRaw  = efm.group("phase").trim().toLowerCase(Locale.ROOT);
            String phaseTrigger = phaseRaw.contains("attack") ? "beginning of attack phase" : "beginning of main phase 1";
            String effect = SUMMON_MARKUP.matcher(efm.group("effect").trim()).replaceAll("").trim();
            if (!effect.isEmpty()) {
                AutoAbility etfFa = parseAutoAbilityRestrictions(card, "enters the field", false, false, false, false, effect, 0);
                if (etfFa != null) result.add(etfFa);
                AutoAbility phaseFa = parseAutoAbilityRestrictions("", phaseTrigger, false, false, false, false, effect, 0);
                if (phaseFa != null) result.add(phaseFa);
            }
            efm.appendReplacement(efBuf, "");
        }
        efm.appendTail(efBuf);
        textForSearch = efBuf.toString();

        // Second-and-a-half pass: "During your opponent's turn, [trigger sentence]" — Lunafreya
        // 8-132L, the only printing that states a turn restriction ahead of its trigger rather than
        // as a trailing "This effect will trigger only during your turn." sentence.
        //
        // The remainder is an ordinary trigger sentence, so it is parsed by this very method rather
        // than by a pattern of its own — the same gate-and-remainder split parseActionAbilities uses
        // for the Break Zone grants — and every ability it yields is marked with the restriction.
        // Stripped afterwards so the passes below cannot claim the sentence a second time without
        // the gate.
        Matcher oppTurnM = DURING_OPP_TURN_TRIGGER_PATTERN.matcher(textForSearch);
        StringBuffer oppTurnBuf = new StringBuffer();
        while (oppTurnM.find()) {
            for (AutoAbility inner : parseAutoAbilities(oppTurnM.group("rest").trim()))
                result.add(inner.withOpponentTurnOnly());
            oppTurnM.appendReplacement(oppTurnBuf, "");
        }
        oppTurnM.appendTail(oppTurnBuf);
        textForSearch = oppTurnBuf.toString();

        // Convert "If N or more [filter] Forwards form the party, also [effect]." into a second trigger sentence.
        textForSearch = expandPartyAttackFollowups(textForSearch);

        // Rewrite Remedi's "your opponent plays a <Type> onto the field other than from his/her hand"
        // into the equivalent watcher phrasing "a <Type> enters your opponent's field other than from
        // their hand", so AUTO_ABILITY_PATTERN captures the type as the subject (card) group.
        textForSearch = textForSearch.replaceAll(
                "(?i)your\\s+opponent\\s+plays\\s+(an?)\\s+(Forward|Backup|Monster|Character)\\s+onto\\s+the\\s+field\\s+other\\s+than\\s+from\\s+(?:his/her|his|her|their)\\s+hand",
                "$1 $2 enters your opponent's field other than from their hand");

        // Split "When A, or when B, [effect]" into one sentence per trigger. Must precede
        // expandMultiSubjectTriggers: that one rewrites a comma-separated subject list into an
        // "or"-joined one, and running it first on "When A, or when B, …" would leave a shape this
        // pattern no longer recognises.
        textForSearch = expandAlternativeTriggers(textForSearch);

        // Rewrite "When X, a Y or a Z [trigger]" into "When X or a Y or a Z [trigger]" so that
        // AUTO_ABILITY_PATTERN's (?<card>[^,]+?) group captures the full disjunction as one subject.
        textForSearch = expandMultiSubjectTriggers(textForSearch);

        Matcher m = AUTO_ABILITY_PATTERN.matcher(textForSearch);
        while (m.find()) {
            String card      = m.group("card").trim();
            String triggerRaw = m.group("trigger").trim().toLowerCase(Locale.ROOT);
            // Normalise trigger to a canonical form
            String trigger;
            boolean cardIsParty = card.toLowerCase(Locale.ROOT).contains("party");
            // triggerRaw contains "party" when the trigger phrase itself is "forms a party and attacks"
            boolean triggerHasParty = triggerRaw.contains("party");
            boolean warpOnly    = triggerRaw.contains("enter") && triggerRaw.contains("warp");
            if      (triggerRaw.contains("attack") && triggerRaw.contains("block"))                        trigger = "attacks or blocks";
            else if (triggerRaw.contains("attack") && (cardIsParty || triggerHasParty))                    trigger = "party attacks";
            else if (triggerRaw.contains("enter") && triggerRaw.contains("break zone"))                   trigger = "enters the field or put into break zone";
            else if (triggerRaw.contains("enter") && triggerRaw.contains("attack"))                        trigger = "enters the field or attacks";
            else if (triggerRaw.contains("enter") && triggerRaw.contains("opponent") && triggerRaw.contains("other than from")) trigger = "enters opponent's field not from hand";
            else if (triggerRaw.contains("enter") && triggerRaw.contains("other than from your hand"))     trigger = "enters your field not from hand";
            else if (triggerRaw.contains("enter") && triggerRaw.contains("opponent") && triggerRaw.contains("field")) trigger = "enters opponent's field";
            else if (triggerRaw.contains("enter") && triggerRaw.contains("your field"))                            trigger = "enters your field";
            else if (triggerRaw.contains("attack")
                    && FILTER_FORWARD_SUBJECT.matcher(card).matches())                                       trigger = "filtered forward attacks";
            else if (triggerRaw.contains("attack")
                    && OTHER_FORWARD_SUBJECT.matcher(card).matches())                                        trigger = "other forward attacks";
            else if (triggerRaw.contains("attack")
                    && ANY_OWN_FORWARD_SUBJECT.matcher(card).matches())                                      trigger = "other forward attacks";
            else if (triggerRaw.contains("attack")
                    && card.toLowerCase(Locale.ROOT).matches("\\d+\\s+or\\s+more\\s+forwards?\\s+you\\s+control"))
                                                                                                             trigger = "attack";
            else if (triggerRaw.contains("attack"))                                                         trigger = "attacks";
            else if (triggerRaw.equals("is blocked"))                                                       trigger = "is blocked";
            else if (triggerRaw.contains("block") && triggerRaw.contains("is blocked"))                    trigger = "blocks or is blocked";
            else if (triggerRaw.contains("block"))                                                          trigger = "blocks";
            // Both of these must precede the "break zone" and "summon" branches below, which would
            // otherwise claim them: "added to your opponent's hand from the Break Zone" contains
            // "break zone", and "due to your Summons or abilities" contains "summon".
            else if (triggerRaw.contains("added to your opponent's hand"))                                  trigger = "opponent salvages from break zone";
            else if (triggerRaw.contains("discard") && triggerRaw.contains("due to your"))                  trigger = discardByEffectTrigger(triggerRaw);
            // "a Forward damaged by Galuf is put from the field into the Break Zone on the same
            // turn" — the same event as the plain break-zone trigger below, qualified by who dealt
            // the damage. Told apart by the subject rather than by the "same turn" tail: the tail
            // only restates the window the damage record already keeps, while "damaged by" is what
            // makes this a different question from "put into break zone" and needs that record.
            // Must precede the plain branch, which would otherwise claim it — both contain
            // "break zone".
            else if (triggerRaw.contains("break zone")
                    && DAMAGED_BY_BZ_SUBJECT.matcher(card).matches())                               trigger = "damaged card put into break zone";
            else if (triggerRaw.contains("break zone"))                                                     trigger = "put into break zone";
            else if (triggerRaw.contains("chosen") && triggerRaw.contains("abilit"))                        trigger = "chosen by opponent's summon or ability";
            else if (triggerRaw.contains("chosen"))                                                         trigger = "chosen by opponent's summon";
            else if (triggerRaw.contains("summon"))                                                         trigger = castSummonTrigger(card);
            else if (triggerRaw.contains("damage zone"))                                                    trigger = "damage zone";
            else if (triggerRaw.contains("leaves"))                                                         trigger = "leaves the field";
            else if (warpOnly)                                                                               trigger = "enters the field";
            else if (triggerRaw.contains("warp"))                                                           trigger = "warp placed";
            else if (triggerRaw.contains("deals damage") && triggerRaw.contains("opponent"))                trigger = "deals damage to opponent";
            else if (triggerRaw.contains("deals damage"))                                                   trigger = "deals damage to forward";
            // The subject here is the card receiving the damage, not the one dealing it.
            else if (triggerRaw.contains("dealt damage"))                                                   trigger = "is dealt damage";
            else if (triggerRaw.contains("receive") && triggerRaw.contains("a point of damage")) {
                if (card.equalsIgnoreCase("you"))   trigger = "you receive damage";
                else                                trigger = "either player receives damage";
            }
            // Matched on the Crystal token rather than on "gain", which is a common enough verb in
            // other trigger phrasings to be worth not keying on. Lower-case 《c》 because
            // triggerRaw was folded above, as every literal in this chain assumes.
            else if (triggerRaw.contains("《c》"))                                                            trigger = "gain crystal";
            else if (triggerRaw.contains("uses") && triggerRaw.contains("ex burst"))                        trigger = "opponent uses ex burst";
            else if (triggerRaw.contains("dull"))                                                            trigger = "becomes dull";
            // Every printing of this trigger watches the opponent searching — either "your opponent
            // searches" or "a Character opponent controls searches" — so the side is implied by the
            // trigger itself and the subject only narrows which of their cards counts.
            else if (triggerRaw.contains("search"))                                                          trigger = "opponent searches";
            // The act of priming, watched by the card doing it or by a filter over the controller's
            // Characters. The Eikon that arrives is what "primes into" describes; this fires on the
            // payment, before the fetched card is known.
            else if (triggerRaw.contains("priming"))                                                         trigger = "is priming";
            else                                                                                             trigger = "enters the field";

            // For "becomes dull", strip optional "active " state qualifier from the card name
            // e.g. "active Ra-la" → triggerCard = "Ra-la"; the state check is enforced at dispatch time
            if (trigger.equals("becomes dull")) {
                card = card.replaceAll("(?i)^active\\s+", "").trim();
            }
            // For "warp placed", strip the " in your hand" suffix from the card name
            if (trigger.equals("warp placed")) {
                card = card.replaceAll("(?i)\\s+in\\s+your\\s+hand$", "").trim();
            }
            // "a X of your opponent enters the field" → reclassify so dispatch can watch the opponent's side
            if (trigger.equals("enters the field")
                    && card.toLowerCase(Locale.ROOT).contains("of your opponent")) {
                trigger = "enters opponent's field";
                card = card.replaceAll("(?i)\\s+of\\s+your\\s+opponent\\s*$", "").trim();
            }

            String  youMayRaw   = m.group("youmay");
            boolean opponentMay = youMayRaw != null
                    && youMayRaw.trim().toLowerCase(Locale.ROOT).startsWith("your opponent");
            boolean youMay      = youMayRaw != null && !opponentMay;

            boolean castOnly = triggerRaw.contains("due to your cast");
            String effect = SUMMON_MARKUP.matcher(m.group("effect").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;

            String thresholdStr = m.group("threshold");
            int damageThreshold = thresholdStr != null ? Integer.parseInt(thresholdStr) : 0;

            // "if you have received N points of damage or more, <effect>" — the same gate the
            // "Damage N --" prefix expresses, written inline after the trigger instead (4-129L
            // Steiner). Folding it into damageThreshold hands it to the check already in
            // executeAutoAbilityImpl and leaves a bare effect the resolver can parse; left in
            // place the whole ability failed to parse, so Steiner drew nothing at any damage.
            Matcher inline = INLINE_DAMAGE_GATE.matcher(effect);
            if (inline.find()) {
                damageThreshold = Integer.parseInt(inline.group("damage"));
                effect = inline.group("effect").trim();
            }

            // Extract party-attack filter fields when applicable
            int    partyMinCount = 0;
            String partyCategory = null, partyJob = null, partyCardName = null;
            if (trigger.equals("party attacks") && triggerHasParty && !cardIsParty) {
                Matcher pf = PARTY_FILTER_PATTERN.matcher(card);
                if (pf.find()) {
                    partyMinCount = Integer.parseInt(pf.group("count"));
                    partyCategory = pf.group("category");
                    partyJob      = pf.group("job");
                } else {
                    // A named-card party subject. Strip an optional "a Card Name " prefix and a
                    // "you control" suffix so "a Card Name Chocobo you control" (9-050C) yields the
                    // bare name "Chocobo"; a plain "Morrow" ("When Morrow forms a party and attacks")
                    // passes through unchanged. "a Job X you control" resolves to a job filter.
                    String subject = card.replaceAll("(?i)\\s+you\\s+control$", "").trim();
                    Matcher jobM = Pattern.compile("(?i)^an?\\s+Job\\s+(?<job>.+)$").matcher(subject);
                    if (jobM.matches()) {
                        partyJob = jobM.group("job").trim();
                    } else {
                        partyCardName = subject.replaceAll("(?i)^an?\\s+Card\\s+Name\\s+", "").trim();
                    }
                }
            }

            AutoAbility fa = parseAutoAbilityRestrictions(card, trigger, youMay, opponentMay, castOnly, warpOnly,
                    effect, damageThreshold, partyMinCount, partyCategory, partyJob, partyCardName);
            if (fa != null) result.add(fa);
        }

        // Third pass: "When a Warp Counter is removed from [CardName], [effect]"
        Matcher wm = WARP_COUNTER_PATTERN.matcher(textForSearch);
        while (wm.find()) {
            String target     = wm.group("target").trim();
            String youMayRaw  = wm.group("youmay");
            boolean opponentMay = youMayRaw != null
                    && youMayRaw.trim().toLowerCase(Locale.ROOT).startsWith("your opponent");
            boolean youMay      = youMayRaw != null && !opponentMay;
            String effect = SUMMON_MARKUP.matcher(wm.group("effect").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            AutoAbility fa = parseAutoAbilityRestrictions(target, "warp counter removed", youMay, opponentMay, false, false, effect, 0);
            if (fa != null) result.add(fa);
        }

        // Fourth pass: "When [CardName] or your [Element] Summon deals damage to a Forward, [effect]"
        // Produces two AutoAbility entries: battle-damage trigger and element-summon trigger.
        Matcher sm = BREAKTOUCH_SUMMON_PATTERN.matcher(textForSearch);
        while (sm.find()) {
            String card    = sm.group("card").trim();
            String element = sm.group("element").trim();
            String elemCap = Character.toUpperCase(element.charAt(0)) + element.substring(1).toLowerCase(Locale.ROOT);
            String effect  = SUMMON_MARKUP.matcher(sm.group("effect").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            AutoAbility fa1 = parseAutoAbilityRestrictions(card, "deals damage to forward", false, false, false, false, effect, 0);
            if (fa1 != null) result.add(fa1);
            String summonTrigger = elemCap.toLowerCase(Locale.ROOT) + " summon deals damage to forward";
            AutoAbility fa2 = parseAutoAbilityRestrictions(card, summonTrigger, false, false, false, false, effect, 0);
            if (fa2 != null) result.add(fa2);
        }

        // Fifth pass: "At the beginning of the Attack Phase during each of your turns, [effect]"
        Matcher bam = AT_BEGINNING_OF_ATTACK_PHASE_PATTERN.matcher(textForSearch);
        while (bam.find()) {
            String effect = SUMMON_MARKUP.matcher(bam.group("effect").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            AutoAbility fa = parseAutoAbilityRestrictions("", "beginning of attack phase", false, false, false, false, effect, 0);
            if (fa != null) result.add(fa);
        }

        // Sixth pass: "At the end of [each of your turns | your turn], [effect]"
        Matcher eotm = ActionResolverPatterns.AT_END_OF_EACH_TURN_PATTERN.matcher(textForSearch);
        while (eotm.find()) {
            // Vayne 9-022L prints this trigger inside the ability it grants to the opponent's
            // Forwards; a match starting inside quotes is not the printing card's own ability.
            if (isInsideQuotes(textForSearch, eotm.start())) continue;
            String effect = SUMMON_MARKUP.matcher(eotm.group("inner").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            AutoAbility aa = parseAutoAbilityRestrictions("", "end of your turn", false, false, false, false, effect, 0);
            if (aa != null) result.add(aa);
        }

        // Seventh pass: "At the beginning of your Main Phase 1, [effect]"
        Matcher mp1m = ActionResolverPatterns.AT_BEGINNING_OF_MAIN_PHASE_1_PATTERN.matcher(textForSearch);
        while (mp1m.find()) {
            String effect = SUMMON_MARKUP.matcher(mp1m.group("inner").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            AutoAbility aa = parseAutoAbilityRestrictions("", "beginning of main phase 1", false, false, false, false, effect, 0);
            if (aa != null) result.add(aa);
        }

        // Eighth pass: "At the beginning of your Main Phase 2, [effect]"
        Matcher mp2m = ActionResolverPatterns.AT_BEGINNING_OF_MAIN_PHASE_2_PATTERN.matcher(textForSearch);
        while (mp2m.find()) {
            String effect = SUMMON_MARKUP.matcher(mp2m.group("inner").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            AutoAbility aa = parseAutoAbilityRestrictions("", "beginning of main phase 2", false, false, false, false, effect, 0);
            if (aa != null) result.add(aa);
        }

        // Eighth-and-a-half pass: "At the beginning of Main Phase [1|2] during each of your turns,
        // [effect]" — the other spelling of the two passes above, and the one most of the corpus
        // uses (Reks 27-054C, Shinryu 14-115L, Robel-Akbel 15-084L, Twintania 16-130H and six more).
        // It fires on exactly the same event, so it produces the same trigger key rather than one of
        // its own.
        Matcher mpEach = ActionResolverPatterns.AT_BEGINNING_OF_MAIN_PHASE_EACH_YOUR_TURN_PATTERN
                .matcher(textForSearch);
        while (mpEach.find()) {
            String effect = SUMMON_MARKUP.matcher(mpEach.group("inner").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            String trigger = "1".equals(mpEach.group("phase"))
                    ? "beginning of main phase 1" : "beginning of main phase 2";
            AutoAbility aa = parseAutoAbilityRestrictions("", trigger, false, false, false, false, effect, 0);
            if (aa != null) result.add(aa);
        }

        // Eighth-and-three-quarters pass: "At the beginning of Main Phase 1 during each player's
        // turn, [effect]" — Ardyn 28-002R. The same event as the pass below, spelled the other way
        // round, so it produces that pass's trigger key rather than one of its own.
        Matcher mpEachPlayer = ActionResolverPatterns.AT_BEGINNING_OF_MAIN_PHASE_1_EACH_PLAYERS_TURN_PATTERN
                .matcher(textForSearch);
        while (mpEachPlayer.find()) {
            String effect = SUMMON_MARKUP.matcher(mpEachPlayer.group("inner").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            AutoAbility aa = parseAutoAbilityRestrictions("", "beginning of main phase 1 each turn",
                    false, false, false, false, effect, 0);
            if (aa != null) result.add(aa);
        }

        // Ninth pass: "Each turn, at the beginning of Main Phase 1, [effect]" (fires both players)
        Matcher mp1et = ActionResolverPatterns.AT_BEGINNING_OF_MAIN_PHASE_1_EACH_TURN_PATTERN.matcher(textForSearch);
        while (mp1et.find()) {
            String effect = SUMMON_MARKUP.matcher(mp1et.group("inner").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            AutoAbility aa = parseAutoAbilityRestrictions("", "beginning of main phase 1 each turn", false, false, false, false, effect, 0);
            if (aa != null) result.add(aa);
        }

        // Tenth pass: "At the beginning of your opponent's Main Phase 1, [effect]"
        Matcher ompm = ActionResolverPatterns.AT_BEGINNING_OF_OPP_MAIN_PHASE_1_PATTERN.matcher(textForSearch);
        while (ompm.find()) {
            String effect = SUMMON_MARKUP.matcher(ompm.group("inner").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            AutoAbility aa = parseAutoAbilityRestrictions("", "beginning of opponent's main phase 1", false, false, false, false, effect, 0);
            if (aa != null) result.add(aa);
        }

        // Eleventh pass: "At the end of your opponent's turn(s), [effect]"
        Matcher ootm = ActionResolverPatterns.AT_END_OF_OPP_TURN_PATTERN.matcher(textForSearch);
        while (ootm.find()) {
            String effect = SUMMON_MARKUP.matcher(ootm.group("inner").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            // Skip a clause that belongs to an enclosing triggered ability — that trigger already
            // owns it, and lifting it out here would make it a second, recurring ability.
            //
            // 28-043R Gi Nattak ("When Gi Nattak is dealt damage, choose 1 Forward opponent
            // controls. At the end of your opponent's turn, break it.") shows the orphaning half of
            // the problem: "break it" has no target once detached. 20-057L The Goddess shows the
            // duplication half — "When The Goddess enters the field, at the end of your opponent's
            // turn, break all the Forwards … with a Doom Counter on them" names its own targets, so
            // it used to be lifted, and the card then broke Forwards at the end of *every* opponent
            // turn instead of once. Both are one delayed effect set up by one trigger; the trigger's
            // own ability queues it via tryParseEndOfOppTurnDelayedEffect.
            if (isInsideTriggeredSentence(textForSearch, ootm.start())) continue;
            AutoAbility aa = parseAutoAbilityRestrictions("", "end of opponent's turn", false, false, false, false, effect, 0);
            if (aa != null) result.add(aa);
        }

        // Twelfth pass: "At the end of each player's turn, [effect]" (fires both players)
        Matcher eptm = ActionResolverPatterns.AT_END_OF_EACH_PLAYERS_TURN_PATTERN.matcher(textForSearch);
        while (eptm.find()) {
            String effect = SUMMON_MARKUP.matcher(eptm.group("inner").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            AutoAbility aa = parseAutoAbilityRestrictions("", "end of each player's turn", false, false, false, false, effect, 0);
            if (aa != null) result.add(aa);
        }

        // Thirteenth pass: "At the beginning of the Attack Phase during each player's turn, [effect]"
        // (fires on both players' turns)
        Matcher baem = AT_BEGINNING_OF_ATTACK_PHASE_EACH_TURN_PATTERN.matcher(textForSearch);
        while (baem.find()) {
            // Lann 16-102R / Reynn 16-105R carry this wording inside an ability their enter-the-field
            // effect conditionally grants, so a match starting inside quotes is not the card's own.
            if (isInsideQuotes(textForSearch, baem.start())) continue;
            String effect = SUMMON_MARKUP.matcher(baem.group("effect").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            AutoAbility aa = parseAutoAbilityRestrictions("", "beginning of attack phase each turn",
                    false, false, false, false, effect, 0);
            if (aa != null) result.add(aa);
        }

        // Fourteenth pass: "At the beginning of your opponent's Attack Phase, [effect]"
        Matcher oapm = AT_BEGINNING_OF_OPP_ATTACK_PHASE_PATTERN.matcher(textForSearch);
        while (oapm.find()) {
            // Titan (XVI) 29-068L prints this wording inside the ability it grants to chosen
            // Forwards, so a match starting inside quotes is not the printing card's own.
            if (isInsideQuotes(textForSearch, oapm.start())) continue;
            String effect = SUMMON_MARKUP.matcher(oapm.group("effect").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            AutoAbility aa = parseAutoAbilityRestrictions("", "beginning of opponent's attack phase",
                    false, false, false, false, effect, damageThresholdOf(oapm));
            if (aa != null) result.add(aa);
        }

        // Fifteenth pass: "During each turn, when [subject] is chosen by your opponent's
        // [Summon | ability | Summon or ability] for the first time in that turn, [effect]"
        Matcher dcm = DURING_EACH_TURN_CHOSEN_FIRST_TIME_PATTERN.matcher(textForSearch);
        while (dcm.find()) {
            String effect = SUMMON_MARKUP.matcher(dcm.group("effect").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            // "Summon or ability" and the ability-only printing both dispatch through the broader
            // trigger — there is no ability-only dispatch, and the Summon-only one would miss the
            // ability half outright. Only a text naming Summons alone takes the narrow trigger.
            String by      = dcm.group("by").toLowerCase(Locale.ROOT);
            String trigger = by.contains("abilit")
                    ? "chosen by opponent's summon or ability"
                    : "chosen by opponent's summon";
            AutoAbility aa = parseAutoAbilityRestrictions(dcm.group("card").trim(), trigger,
                    false, false, false, false, effect, damageThresholdOf(dcm));
            // "for the first time in that turn" is the per-turn limit stated in the trigger clause;
            // the restriction stripper only reads the trailing sentence form, so set it here.
            if (aa != null) result.add(aa.withOncePerTurn());
        }

        // Sixteenth pass: the ordinal cast trigger — "During each turn, when you cast the [ordinal]
        // [card | Summon] you've cast, [effect]" and Rosa 14-057H's "…this turn" spelling of it.
        Matcher ncm = DURING_EACH_TURN_NTH_CAST_PATTERN.matcher(textForSearch);
        while (ncm.find()) {
            String effect = SUMMON_MARKUP.matcher(ncm.group("effect").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            int n = ordinalValue(ncm.group("ordinal"));
            if (n == 0) continue;
            boolean summonsOnly = ncm.group("what").toLowerCase(Locale.ROOT).startsWith("summon");
            // "you" is the subject every printing in the family names: the count is the caster's,
            // not any one card's, so the trigger is player-scoped like "When you cast a Summon".
            AutoAbility aa = parseAutoAbilityRestrictions("you", nthCastTrigger(summonsOnly, n),
                    false, false, false, false, effect, damageThresholdOf(ncm));
            if (aa != null) result.add(aa);
        }

        // Put back any quoted granted ability that was masked while scanning for triggers, so a
        // grant reports the ability it confers rather than a hole where the quote used to be.
        if (!maskedQuotes.isEmpty()) {
            result.replaceAll(aa -> {
                String restored = unmaskQuotedTriggerSpans(aa.effectText(), maskedQuotes);
                return restored.equals(aa.effectText()) ? aa : aa.withEffectText(restored);
            });
        }
        return List.copyOf(result);
    }

    /**
     * The "Damage N -- " gate a whole-text trigger pass captured on its own match, or 0 when the
     * segment carries none. The segment-based parsers strip the prefix before matching; the passes
     * that scan the full card text have to read it from their own {@code threshold} group instead.
     */
    private static int damageThresholdOf(Matcher m) {
        String s = m.group("threshold");
        return s == null ? 0 : Integer.parseInt(s);
    }

    /**
     * Strips trigger-restriction sentences from {@code effect}, records the resulting flags,
     * and returns a complete {@link AutoAbility}.  Returns {@code null} if the effect is empty
     * after stripping.  Party-attack filter fields default to 0 / null.
     */
    private static AutoAbility parseAutoAbilityRestrictions(
            String card, String trigger, boolean youMay, boolean opponentMay, boolean castOnly, boolean warpOnly,
            String effect, int damageThreshold) {
        return parseAutoAbilityRestrictions(card, trigger, youMay, opponentMay, castOnly, warpOnly,
                effect, damageThreshold, 0, null, null, null);
    }

    /**
     * Full form — also accepts party-attack filter fields.
     */
    private static AutoAbility parseAutoAbilityRestrictions(
            String card, String trigger, boolean youMay, boolean opponentMay, boolean castOnly, boolean warpOnly,
            String effect, int damageThreshold,
            int partyMinCount, String partyCategory, String partyJob, String partyCardName) {

        boolean oncePerTurn = false, yourTurnOnly = false;
        String  rfpConditionCard = "";
        String  bzConditionCard  = "";
        String  bzConditionJob   = "";
        int     castPaymentMinElements = 0;

        // Suffix restrictions (strip from end)
        Matcher restr = FA_TRIGGER_RESTRICTION.matcher(effect);
        if (restr.find() && (restr.group("yourTurn") != null || restr.group("once") != null)) {
            yourTurnOnly = restr.group("yourTurn") != null;
            oncePerTurn  = restr.group("once")     != null;
            effect = effect.substring(0, restr.start()).trim().replaceAll("[.!,]+$", "").trim();
        }

        Matcher rfp = FA_RFP_CONDITION.matcher(effect);
        if (rfp.find()) {
            rfpConditionCard = rfp.group("rfpCard").trim();
            effect = effect.substring(0, rfp.start()).trim().replaceAll("[.!,]+$", "").trim();
        }

        Matcher bzCond = FA_BZ_CONDITION.matcher(effect);
        if (bzCond.find()) {
            bzConditionCard = bzCond.group("bzCard").trim();
            effect = effect.substring(0, bzCond.start()).trim().replaceAll("[.!,]+$", "").trim();
        }

        // Prefix condition: "if you have a Card Name X [with Job Y] in your Break Zone, "
        Matcher bzHave = FA_BZ_HAVE_CONDITION.matcher(effect);
        if (bzHave.find()) {
            bzConditionCard = bzHave.group("bzCard").trim();
            if (bzHave.group("bzJob") != null) bzConditionJob = bzHave.group("bzJob").trim();
            effect = effect.substring(bzHave.end()).trim();
        }

        // Prefix condition: "if the cost to cast X was paid with CP of N or more different Elements, "
        Matcher pay = FA_CAST_PAYMENT_ELEMENTS.matcher(effect);
        if (pay.find()) {
            castPaymentMinElements = Integer.parseInt(pay.group("n"));
            effect = effect.substring(pay.end()).trim();
        }

        if (effect.isEmpty()) return null;
        // "You may pay 《X》. If you don't pay 《X》, …" — the "you may" belongs to the cost, not to the
        // ability: the gate itself asks whether to pay, and the consequence lands either way. Left as
        // an optional ability, declining the prompt would skip the consequence too.
        if (youMay && ActionResolver.isPayOrElseGate(effect)) youMay = false;
        return new AutoAbility(card, trigger, youMay, opponentMay, effect,
                oncePerTurn, yourTurnOnly, false, rfpConditionCard, bzConditionCard, bzConditionJob, castPaymentMinElements, castOnly, warpOnly, damageThreshold,
                partyMinCount, partyCategory, partyJob, partyCardName);
    }

    /**
     * Which of the three cast-a-Summon triggers {@code subject} names.
     *
     * <p>Unlike almost every other trigger subject, this one is a player rather than a card, and it
     * decides <em>whose</em> field the trigger fires on: "you" is the caster's own side, "your
     * opponent" the other one, "either player" both. Every printing spells it out, so a subject that
     * matches neither of the two special forms is the "you" case.
     *
     * <p>Folding all three into one key is what let the dispatch fire "When your opponent casts a
     * Summon" on the casting player's field — the side the text explicitly excludes.
     */
    private static String castSummonTrigger(String subject) {
        String s = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        if (s.contains("either player")) return "either player casts summon";
        if (s.contains("opponent"))      return "opponent casts summon";
        return "cast summon";
    }

    /** Normalises a raw trigger string (lower-cased) to a canonical trigger value. */
    private static String normalizePretrigger(String raw, String subject, boolean cardIsParty,
            boolean warpOnly) {
        if (raw == null || raw.isBlank()) return "enters the field";
        String r = raw.trim();
        if (r.contains("attack") && r.contains("block"))                   return "attacks or blocks";
        if (r.contains("attack") && (cardIsParty || r.contains("party"))) return "party attacks";
        if (r.contains("enter") && r.contains("attack"))           return "enters the field or attacks";
        if (r.contains("attack"))                                   return "attacks";
        if (r.contains("block") && r.contains("is blocked"))       return "blocks or is blocked";
        if (r.equals("is blocked"))                                 return "is blocked";
        if (r.contains("block"))                                    return "blocks";
        if (r.contains("break zone"))                               return "put into break zone";
        if (r.contains("summon"))                                   return castSummonTrigger(subject);
        if (r.contains("damage zone"))                              return "damage zone";
        if (r.contains("leaves"))                                   return "leaves the field";
        if (warpOnly)                                               return "enters the field";
        if (r.contains("warp"))                                     return "warp placed";
        if (r.contains("deals damage") && r.contains("opponent"))  return "deals damage to opponent";
        if (r.contains("deals damage"))                             return "deals damage to forward";
        return "enters the field";
    }

    // -------------------------------------------------------------------------
    // "If you control X, Y gains Z" conditional field-boost parsing
    // -------------------------------------------------------------------------

    /**
     * "If all the [Type] you control have [property], [target] gain/gains [effects]."
     * Groups: {@code type} (Forward/Backup/Character/Monster), {@code property} (element or job text),
     * {@code target} ("they" = same type, or a card name), {@code effects} (+N power).
     */
    private static final Pattern IF_ALL_HAVE_BOOST = Pattern.compile(
        "(?i)^If\\s+all\\s+the\\s+(?<type>Forwards?|Backups?|Characters?|Monsters?)\\s+you\\s+control\\s+have\\s+" +
        "(?<property>.+?),\\s+(?<target>.+?)\\s+gains?\\s+(?<effects>.+?)\\.?\\s*$"
    );

    /** Matches element-property form: "X Element" or just "X" (one of the 8 elements). */
    private static final Pattern ALL_HAVE_ELEMENT_PROPERTY = Pattern.compile(
        "(?i)^(Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)(?:\\s+Element)?$"
    );

    /** Extracts a single job name from a "[Job (X)]" or "Job X" token in an all-have property. */
    private static final Pattern ALL_HAVE_JOB_TOKEN = Pattern.compile(
        "(?i)(?:\\[Job\\s*\\(([^)]+)\\)\\]|(?:^|\\bor\\s+)Job\\s+([A-Za-z][A-Za-z\\s''\\-]+?)(?=\\s+or\\b|\\s*$))"
    );

    /** Outer structure: "If you control <raw>, <target> gains <effects>[.]" */
    private static final Pattern IF_CTRL_BOOST_OUTER = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<raw>[^,]+),\\s+(?<target>.+?)\\s+gains?\\s+(?<effects>.+?)\\.?\\s*$"
    );

    /**
     * "If there are N or more cards removed from the game, [target] gains [effects]."
     * Groups: {@code count}, {@code target}, {@code effects}.
     */
    private static final Pattern IF_RFP_COUNT_BOOST = Pattern.compile(
        "(?i)^If\\s+there\\s+are\\s+(?<count>\\d+)\\s+or\\s+more\\s+cards?\\s+removed\\s+from\\s+the\\s+game,\\s+" +
        "(?<target>.+?)\\s+gains?\\s+(?<effects>.+?)\\.?\\s*$"
    );

    /**
     * "If you have received N points of damage or more, [target] gains [effects]."
     * Groups: {@code count}, {@code target}, {@code effects}.
     */
    private static final Pattern IF_DAMAGE_RECEIVED_BOOST = Pattern.compile(
        "(?i)^If\\s+you\\s+have\\s+received\\s+(?<count>\\d+)\\s+points?\\s+of\\s+damage\\s+or\\s+more,\\s+" +
        "(?<target>.+?)\\s+gains?\\s+(?<effects>.+?)\\.?\\s*$"
    );

    /**
     * "If your opponent has N cards or less in his/her hand, [target] gains [effects]."
     * Groups: {@code count}, {@code target}, {@code effects}.
     */
    private static final Pattern IF_OPP_HAND_BOOST = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+has\\s+(?<count>\\d+)\\s+cards?\\s+or\\s+less\\s+in\\s+" +
        "(?:his/her|their)\\s+hand,\\s+(?<target>.+?)\\s+gains?\\s+(?<effects>.+?)\\.?\\s*$"
    );

    /**
     * "If you have N cards or less in your hand, [target] gains [effects]."
     * Groups: {@code count}, {@code target}, {@code effects}.
     */
    private static final Pattern IF_OWN_HAND_BOOST = Pattern.compile(
        "(?i)^If\\s+you\\s+have\\s+(?<count>\\d+)\\s+cards?\\s+or\\s+less\\s+in\\s+your\\s+hand,\\s+" +
        "(?<target>.+?)\\s+gains?\\s+(?<effects>.+?)\\.?\\s*$"
    );

    /**
     * "If you have N or more cards in your hand, [target] gains [effects]." (Galuf 3-077H, which
     * prints one line per threshold: +2000 power at 4 cards, Brave at 5.)
     * Groups: {@code count}, {@code target}, {@code effects}.
     *
     * <p>The floor twin of {@link #IF_OWN_HAND_BOOST}, whose "N cards or less" is a ceiling. The
     * two wordings cannot be folded together: the comparison runs the other way.
     */
    private static final Pattern IF_OWN_HAND_MIN_BOOST = Pattern.compile(
        "(?i)^If\\s+you\\s+have\\s+(?<count>\\d+)\\s+or\\s+more\\s+cards?\\s+in\\s+your\\s+hand,\\s+" +
        "(?<target>.+?)\\s+gains?\\s+(?<effects>.+?)\\.?\\s*$"
    );

    /**
     * "If you have N or more cards in your hand, [target] cannot be blocked." (Zidane 8-115L)
     * Groups: {@code count}, {@code target}.
     *
     * <p>The unblockable twin of {@link #IF_OWN_HAND_MIN_BOOST}, which cannot claim this text:
     * its tail is a "gains [power/traits]" clause, and this printing grants neither — it lifts a
     * blocking restriction instead. Same hand-size gate, different effect, so the two are separate
     * patterns rather than one with an optional tail.
     */
    private static final Pattern IF_OWN_HAND_MIN_CANNOT_BE_BLOCKED = Pattern.compile(
        "(?i)^If\\s+you\\s+have\\s+(?<count>\\d+)\\s+or\\s+more\\s+cards?\\s+in\\s+your\\s+hand,\\s+" +
        "(?<target>.+?)\\s+cannot\\s+be\\s+blocked[.!]?\\s*$"
    );

    /**
     * "If you control N or more different Element Backups, [target] gains [effects]."
     * (Kefka 3-079H: +3000 power at 3 Elements, Brave at 5.)
     * Groups: {@code count}, {@code target}, {@code effects}.
     *
     * <p>Counts <em>distinct</em> Elements among the Backups controlled, not the Backups
     * themselves, which is what separates it from {@link #IF_N_BACKUPS_ALL_DIFF_ELEMENTS_BOOST} —
     * that one counts Backups and requires no two to share an Element.
     */
    private static final Pattern IF_N_DIFF_ELEMENT_BACKUPS_BOOST = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<count>\\d+)\\s+or\\s+more\\s+different\\s+Element\\s+Backups?,\\s+" +
        "(?<target>.+?)\\s+gains?\\s+(?<effects>.+?)\\.?\\s*$"
    );

    /**
     * "If you control N or more Backups and if the Backups you control are all of different Elements,
     * [target] gains +[power] power."
     * Groups: {@code count}, {@code target}, {@code power}.
     */
    private static final Pattern IF_N_BACKUPS_ALL_DIFF_ELEMENTS_BOOST = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<count>\\d+)\\s+or\\s+more\\s+Backups?\\s+and\\s+if\\s+the\\s+Backups?\\s+you\\s+control\\s+are\\s+all\\s+of\\s+different\\s+Elements?,\\s+" +
        "(?<target>.+?)\\s+gains?\\s+\\+?(?<power>\\d+)\\s+power\\.?\\s*$"
    );

    /**
     * "If your opponent controls N or more Forwards, [target] gains [effects]."
     * Groups: {@code n}, {@code target}, {@code effects}.
     */
    private static final Pattern IF_OPP_CTRL_N_OR_MORE_FWD_BOOST = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+controls\\s+(?<n>\\d+)\\s+or\\s+more\\s+Forwards?," +
        "\\s+(?<target>.+?)\\s+gains?\\s+(?<effects>.+?)\\.?\\s*$"
    );

    /**
     * General "If your opponent controls [any] X, [target] gains [effects]." pattern.
     * Handles job/element/type filters via {@link #parseControlCondition} after normalising
     * "any" to "a".  Groups: {@code raw} (condition text), {@code target}, {@code effects}.
     */
    private static final Pattern IF_OPP_CTRL_BOOST_OUTER = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+controls?\\s+(?<raw>[^,]+),\\s+(?<target>.+?)\\s+gains?\\s+(?<effects>.+?)\\.?\\s*$"
    );

    /**
     * "If your opponent doesn't control [any|a] Forward[s] [of cost N or more], [target] gains [effects]."
     * Groups: {@code mincost} (optional cost filter), {@code target}, {@code effects}.
     */
    private static final Pattern IF_OPP_CTRL_NO_FWD_BOOST = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+(?:doesn'?t|does\\s+not)\\s+control\\s+(?:any\\s+|a\\s+)?Forwards?" +
        "(?:\\s+of\\s+cost\\s+(?<mincost>\\d+)\\s+or\\s+more)?,\\s+" +
        "(?<target>.+?)\\s+gains?\\s+(?<effects>.+?)\\.?\\s*$"
    );

    /**
     * "If you have a 《C》, [target] gains [effects]."
     * Groups: {@code target}, {@code effects}.
     */
    private static final Pattern IF_HAVE_CRYSTAL_BOOST = Pattern.compile(
        "(?i)^If\\s+you\\s+have\\s+a\\s*《C》,\\s+(?<target>.+?)\\s+gains?\\s+(?<effects>.+?)\\.?\\s*$"
    );

    /**
     * "If [CardName] is [dull|active|attacking], [target] gains [effects]." — Knight 17-100C's
     * +3000 power, and Queen 21-089R's pair of quoted abilities.
     * Groups: {@code condcard} (the card whose state is the gate), {@code state}, {@code target},
     * {@code effects}.
     */
    private static final Pattern IF_CARD_IS_STATE_BOOST = Pattern.compile(
        "(?i)^If\\s+(?<condcard>[A-Z][A-Za-z''\\-]+(?:\\s+[A-Za-z''\\-]+)*)\\s+is\\s+" +
        "(?<state>dull|active|attacking),\\s+" +
        "(?<target>.+?)\\s+gains?\\s+(?<effects>.+?)\\.?\\s*$"
    );

    /**
     * The same gate with an immunity for its whole consequence rather than a gain:
     * "If [CardName] is [dull|active|attacking], [target] cannot be chosen by your opponent's
     * [Summons or abilities | Summons | abilities]." — Knight 17-100C and Trey 3-064H, who prints
     * one of each state.
     *
     * <p>The state twin of {@link #IF_CTRL_CANNOT_BE_CHOSEN}, and anchored and scoped for the same
     * reasons: a trailing qualifier would narrow the immunity, and "your opponent's" is what makes
     * it opponent-scoped downstream.
     * Groups: {@code condcard}, {@code state}, {@code target}, {@code effects}.
     */
    private static final Pattern IF_CARD_IS_STATE_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)^If\\s+(?<condcard>[A-Z][A-Za-z''\\-]+(?:\\s+[A-Za-z''\\-]+)*)\\s+is\\s+" +
        "(?<state>dull|active|attacking),\\s+(?<target>.+?)\\s+" +
        "(?<effects>cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "(?:Summons?\\s+or\\s+abilities|abilities\\s+or\\s+Summons?|Summons?|abilities))" +
        "\\s*[.!]?\\s*$"
    );

    /**
     * "If you control [raw], [target] loses [power] power[ instead]."
     * Groups: {@code raw} (condition text), {@code target}, {@code power} (bare number, stored negative),
     * {@code instead} (present when the word "instead" follows "power" — effect replaces a base field grant).
     */
    private static final Pattern IF_CTRL_LOSE_OUTER = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<raw>[^,]+),\\s+(?<target>.+?)\\s+loses?\\s+(?<power>\\d+)\\s+power(?:\\s+(?<instead>instead))?\\.?\\s*$"
    );

    /** The four grantable keyword traits, as an alternation for embedding in a larger pattern. */
    private static final String TRAIT_KEYWORD = "(?:Haste|Brave|First\\s+Strike|Back\\s+Attack)";

    /**
     * The element names a grant filter may name, as a bare alternation (no group of its own, so
     * callers can name it). Includes the "Multi-Element" pseudo-element, which
     * {@link CardFilters#meetsElementFilter} resolves to "has more than one element".
     */
    private static final String ELEMENT_KEYWORD =
            "Multi-Element|Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark";

    /**
     * The target filter shared by {@link #FIELD_GRANT_CNB_BY_COST_DIRECT} and
     * {@link #FIELD_GRANT_CNB_BY_COST_QUOTED}: "The [Job X | Category Y | Element] Forwards
     * [other than Z] you control". Both patterns must carry the same groups — the handler reads
     * them off whichever matcher won, and a group missing from the other would throw.
     */
    private static final String CNB_BY_COST_TARGET =
        "(?i)^The\\s+(?:Job\\s+(?<job>.+?)|Category\\s+(?<category>.+?)|(?<element>" + ELEMENT_KEYWORD + "))" +
        "\\s+Forwards?\\s+(?:other\\s+than\\s+(?<except>.+?)\\s+)?you\\s+control\\s+";

    /** The "by a/Forwards of cost N or more/less." tail shared by the two patterns below. */
    private static final String CNB_BY_COST_TAIL =
        "by\\s+(?:a\\s+)?Forwards?\\s+of\\s+cost\\s+(?<costval>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?";

    /**
     * "The [Job X | Category Y | Element] Forwards [other than Z] you control cannot be blocked by
     * a/Forwards of cost N or more/less."
     * Groups: {@code job}, {@code category} or {@code element}, {@code except} (optional),
     * {@code costval}, {@code costcmp}.
     */
    private static final Pattern FIELD_GRANT_CNB_BY_COST_DIRECT = Pattern.compile(
        CNB_BY_COST_TARGET + "cannot\\s+be\\s+blocked\\s+" + CNB_BY_COST_TAIL + "\\s*\\.?\\s*$"
    );

    /**
     * "The [Job X | Category Y | Element] Forwards [other than Z] you control gain
     * "This Forward cannot be blocked by a/Forwards of cost N or more/less.""
     * (Vaan 15-044L for the Job form, Poppy 18-048R for the Element form.)
     * Groups: same as {@link #FIELD_GRANT_CNB_BY_COST_DIRECT}.
     */
    private static final Pattern FIELD_GRANT_CNB_BY_COST_QUOTED = Pattern.compile(
        CNB_BY_COST_TARGET + "gain\\s+[\"\\u201C]This\\s+Forward\\s+cannot\\s+be\\s+blocked\\s+" +
        CNB_BY_COST_TAIL + "[.][\"\\u201D]\\s*\\.?\\s*$"
    );

    /**
     * "[CardName] cannot be blocked." — unconditional permanent unblockability with no condition.
     * Group {@code name} captures the card name.
     */
    private static final Pattern UNCONDITIONAL_CNB_PATTERN = Pattern.compile(
        "(?i)^(?<name>[A-Z][A-Za-z''\\-\\s()]+?)\\s+cannot\\s+be\\s+blocked\\.?\\s*$"
    );

    /**
     * "The Card Name X you control cannot be chosen by your opponent's Summons [or abilities]."
     * — permanent targeting immunity granted to a named card, with no "If you control" condition
     * (10-097R Noel and 19-134S Mog (XIII-2) for Serah; 1-017R Dajh for Sazh; 5-157S / 21-057R
     * Fran for Balthier; 16-062C Lexa for Madam Edel).
     *
     * <p>Stored as an {@link IfControlBoost} with an empty conditions list, the same shape
     * {@link #UNCONDITIONAL_CNB_PATTERN} uses, so it reaches the engine through the existing
     * {@code icbGrantsImmunity} check rather than a second immunity mechanism.
     * Groups: {@code name}, {@code scope}.
     */
    private static final Pattern UNCONDITIONAL_NAMED_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)^The\\s+Card\\s+Name\\s+(?<name>.+?)\\s+you\\s+control\\s+" +
        "cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "(?<scope>Summons?\\s+or\\s+abilities|Summons?|abilities)\\s*[.!]?\\s*$"
    );

    /**
     * The conditional twin of {@link #UNCONDITIONAL_NAMED_CANNOT_BE_CHOSEN}:
     * "If you control Card Name X, it cannot be chosen by your opponent's
     * [Summons or abilities | Summons | abilities]." — Serah 1-045R (for Snow).
     *
     * <p>The pronoun "it" refers back to the card the condition names, so the condition and the
     * target are the same card: the shield covers X while X is on the field. That makes the
     * condition redundant with the target lookup, which already walks the field — but it is kept
     * so the parsed shape says what the text says, and so a future printing whose "it" resolves
     * elsewhere cannot be folded in here by accident.
     * Groups: {@code name}, {@code scope}.
     */
    private static final Pattern IF_CONTROL_NAMED_IT_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?:an?\\s+)?Card\\s+Name\\s+(?<name>.+?),\\s+" +
        "it\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "(?<scope>Summons?\\s+or\\s+abilities|Summons?|abilities)\\s*[.!]?\\s*$"
    );

    /**
     * The same shield handed to a filtered <em>set</em> rather than a named card:
     * "The [Job J | Category C] [type] [with Trait] [other than X] you control cannot be chosen
     * by your opponent's [Summons or abilities | Summons | abilities]." — Silver Dragon 23-044R
     * (Monsters, excluding itself), White Tiger l'Cie Nimbus 23-035H (Forwards with Brave,
     * excluding itself), Kimahri 7-108H (a Job and a Category, one printing each), Reddas
     * 22-054R (Job, excluding itself) and Paine 6-053R / Rikku 6-062R (Job plus a type).
     *
     * <p>Every part before "you control" is optional but at least one has to be there for the
     * sentence to name a set at all; the anchors are what enforce that, since a text with none of
     * them does not reach "you control" from "The".
     *
     * <p>Stored as an {@link IfControlBoost} with an empty conditions list and a target filter, the
     * same shape {@link #UNCONDITIONAL_NAMED_CANNOT_BE_CHOSEN} uses for the named form — the
     * immunity lookup already resolves a filter by walking the field, so nothing downstream needed
     * a new mechanism.
     * Groups: {@code targets}, {@code withtrait} (optional), {@code except} (optional), {@code scope}.
     */
    private static final Pattern UNCONDITIONAL_FILTERED_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)^The\\s+" +
        // A Job or Category narrowing the set (Kimahri 7-108H, Reddas 22-054R, Paine 6-053R,
        // Rikku 6-062R). The lookahead refuses Mayakov 15-121R's "Job Dancer and Card Name
        // Dancer", which is two alternative filters rather than one and has its own pattern —
        // without it the lazy Job group swallows the whole phrase and yields a filter no card
        // can meet.
        "(?:Job\\s+(?!.*\\s+and\\s+Card\\s+Name\\s+)(?<job>.+?)\\s+" +
        "|Category\\s+(?<category>\\S+)\\s+)?" +
        // Optional, because a Job or Category is subject enough on its own — "The Job Guardian
        // … you control" names no type and so reaches every row.
        "(?:(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+)?" +
        "(?:with\\s+(?<withtrait>" + TRAIT_KEYWORD + ")\\s+)?" +
        "(?:other\\s+than\\s+(?<except>[A-Z][A-Za-z''\\-]+(?:\\s+[A-Za-z''\\-]+)*)\\s+)?" +
        "you\\s+control\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        // The card type whose abilities the immunity covers, when the printing names one —
        // "your opponent's Backup abilities" (Aerith 3-050L). Its own group ahead of the scope,
        // because it narrows what may choose rather than what kind of effect chooses: a Summon
        // has no card type to match and is excluded by the scope alone.
        "(?<sourcetype>Forward|Backup|Monster|Character)?\\s*" +
        "(?<scope>Summons?\\s+or\\s+abilities|Summons?|abilities)\\s*[.!]?\\s*$"
    );

    /**
     * The same shield handed to a Job and a Card Name at once: "The Job Dancer and Card Name Dancer
     * you control cannot be chosen by your opponent's abilities." — Mayakov 15-121R.
     *
     * <p>The two halves are alternatives, not a conjunction — a Job Dancer qualifies whatever it is
     * called, and a card named Dancer qualifies whatever Job it has. {@link FieldPowerGrant}'s job
     * and card-name filters are conjunctive, so this is stored as <em>two</em> boosts, one per
     * branch, exactly as Faris 21-114L's "Job Pirate and Card Name Viking" base-power grant is.
     * A card satisfying both is covered twice, which changes nothing: the immunity is a boolean.
     *
     * <p>No target-type token in the sentence, so it reaches every row.
     * Groups: {@code job}, {@code name}, {@code scope}.
     */
    private static final Pattern UNCONDITIONAL_JOB_AND_NAME_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)^The\\s+Job\\s+(?<job>.+?)\\s+and\\s+Card\\s+Name\\s+(?<name>.+?)\\s+you\\s+control\\s+" +
        "cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "(?<scope>Summons?\\s+or\\s+abilities|Summons?|abilities)\\s*[.!]?\\s*$"
    );

    /**
     * "If you control <raw>, <target> cannot be blocked[ by a/Forwards of cost N or more/less][.]"
     * The cost clause is optional; when absent the target is fully unblockable while active.
     * Groups: {@code raw}, {@code target}, {@code costval} (optional), {@code costcmp} (optional).
     */
    private static final Pattern IF_CTRL_CANNOT_BE_BLOCKED = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<raw>[^,]+),\\s+(?<target>.+?)\\s+cannot\\s+be\\s+blocked" +
        "(?:\\s+by\\s+(?:a\\s+)?Forwards?\\s+of\\s+cost\\s+(?<costval>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "\\.?\\s*$"
    );

    /**
     * "If you control &lt;raw&gt;, &lt;target&gt; cannot be chosen by your opponent's
     * [Summons or abilities | Summons | abilities]." — the shield stated directly rather than
     * handed over inside a quoted grant: Fran 10-060L, Yuri 11-062R and 18-049R, Adelle 14-039R,
     * Jack Garland 27-046H, Mog (VI) 4-140H and Elena B-051.
     *
     * <p>The sibling of {@link #IF_CTRL_BOOST_OUTER} for a condition whose consequence is an
     * immunity rather than a gain, and of {@link #IF_CONTROL_NAMED_IT_CANNOT_BE_CHOSEN} for a
     * sentence that repeats the card's name where that one writes "it". Everything downstream is
     * shared: {@code effects} is handed to {@link #ICB_EFFECT_NO_CHOSEN} exactly as a quoted grant
     * is, so the two spellings produce the same {@link IfControlBoost}.
     *
     * <p>The pronoun is refused in {@code target} rather than left to fall through: Serah 1-045R's
     * "…, it cannot be chosen…" satisfies this shape too, and without the lookahead she parses
     * twice — once here into a boost whose target is the literal word "it", which names no card and
     * so shields nothing, and once into the real boost {@link #IF_CONTROL_NAMED_IT_CANNOT_BE_CHOSEN}
     * builds by resolving the pronoun back to the card the condition names.
     *
     * <p>"your opponent's" is required, not optional, because that qualifier is what makes the
     * immunity opponent-scoped downstream. A printing that omits it means the broader both-players
     * rule, and silently reading it as the narrow one would hand out a weaker shield than the card
     * prints; none exists in this shape today, and one would stay visibly unhandled here.
     *
     * <p>Anchored end to end for the reason {@link ActionResolverPatterns#FA_SELF_CANNOT_BE_CHOSEN_BY_OPP}
     * is: a trailing qualifier narrows the immunity ("…that share its Element"), and a scanning
     * matcher would stop at the keyword and grant a blanket one instead.
     * Groups: {@code raw}, {@code target}, {@code effects}.
     */
    private static final Pattern IF_CTRL_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<raw>[^,]+),\\s+(?<target>(?!it\\b).+?)\\s+" +
        "(?<effects>cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "(?:Summons?\\s+or\\s+abilities|abilities\\s+or\\s+Summons?|Summons?|abilities))" +
        "\\s*[.!]?\\s*$"
    );

    /** Splits a single condition part on " other than ": group(1) = condition, group(2) = excluded name. */
    private static final Pattern IF_CTRL_BOOST_EXCEPT = Pattern.compile(
        "(?i)^(.+?)\\s+other\\s+than\\s+(\\S.*)$"
    );

    /** Extracts the +N power value from an effects substring. */
    private static final Pattern IF_CTRL_EFFECT_POWER = Pattern.compile("(?i)\\+(\\d+)\\s+power");

    /** Extracts quoted special ability text from an effects substring. */
    private static final Pattern IF_CTRL_EFFECT_QUOTED = Pattern.compile("\"([^\"]+)\"");

    /**
     * Target-side filter matcher for {@link IfControlBoost}: parses a target phrase like
     * "the Category IV Forwards you control" or "the Forwards other than Ashe you control"
     * into a {@link FieldPowerGrant}-shaped filter (zero power; the power/traits come from
     * the outer effects clause).  Returns {@code null} when the phrase looks like a literal
     * card name rather than a filter.
     */
    private static final Pattern ICB_TARGET_FILTER_PATTERN = Pattern.compile(
        "(?i)^the\\s+" +
        "(?:Job\\s+(?<job>[A-Za-z][A-Za-z\\s''\\-]*?)(?=\\s+Forwards?|\\s+Backups?|\\s+Monsters?|\\s+Characters?|\\s+other\\s+than|\\s+you)|" +
        "Category\\s+(?<category>\\S+))?\\s*" +
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s*" +
        "(?:other\\s+than\\s+(?<except>[A-Z][A-Za-z''\\-]+(?:\\s+[A-Za-z''\\-]+)*)\\s+)?" +
        "you\\s+control\\s*$"
    );

    // Simple keyword matchers for effect substrings (not positional like the card-text trait patterns)
    private static final Pattern ICB_EFFECT_HASTE             = Pattern.compile("(?i)\\bHaste\\b");
    private static final Pattern ICB_EFFECT_BRAVE             = Pattern.compile("(?i)\\bBrave\\b");
    private static final Pattern ICB_EFFECT_FIRST_STRIKE      = Pattern.compile("(?i)\\bFirst\\s+Strike\\b");
    private static final Pattern ICB_EFFECT_BACK_ATTACK       = Pattern.compile("(?i)\\bBack\\s+Attack\\b");

    /**
     * Collects the trait keywords named anywhere in {@code text}. Returns an empty set for
     * {@code null} text, so an optional regex group can be passed straight through.
     */
    private static EnumSet<Trait> traitsNamedIn(String text) {
        EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
        if (text == null) return traits;
        if (ICB_EFFECT_HASTE.matcher(text).find())        traits.add(Trait.HASTE);
        if (ICB_EFFECT_BRAVE.matcher(text).find())        traits.add(Trait.BRAVE);
        if (ICB_EFFECT_FIRST_STRIKE.matcher(text).find()) traits.add(Trait.FIRST_STRIKE);
        if (ICB_EFFECT_BACK_ATTACK.matcher(text).find())  traits.add(Trait.BACK_ATTACK);
        return traits;
    }

    /**
     * "cannot be chosen by [your opponent's] &lt;scope&gt;" inside an IfControlBoost effects clause.
     *
     * <p>One pattern rather than a Summons matcher and an abilities matcher, because the two
     * halves share a single "cannot be chosen by" prefix: two independent matchers both anchored
     * on that prefix cannot each claim their half of "…by your opponent's Summons or abilities",
     * and the abilities one silently lost every time.
     *
     * <p>Group {@code opp} is present only when the card prints the "your opponent's" qualifier,
     * which is what decides whether the immunity blocks both players or only the target's
     * opponent — see {@link IfControlBoost#chosenImmunityOpponentOnly}.
     */
    private static final Pattern ICB_EFFECT_NO_CHOSEN = Pattern.compile(
        "(?i)cannot\\s+be\\s+chosen\\s+by\\s+(?<opp>your\\s+opponent's\\s+)?" +
        "(?<scope>Summons?\\s+or\\s+abilities|abilities\\s+or\\s+Summons?|Summons?|abilities)");

    /**
     * The choice immunity an {@link IfControlBoost} effects clause names: which halves it covers,
     * and whose Summons and abilities it stops.
     *
     * <p>{@code NONE} is the answer for a clause that names no immunity at all, and is what every
     * gate that does not print one gets.
     */
    private record ChosenImmunity(boolean summons, boolean abilities, boolean opponentOnly) {
        static final ChosenImmunity NONE = new ChosenImmunity(false, false, false);
        boolean any() { return summons || abilities; }
    }

    /**
     * Reads every "cannot be chosen" clause in {@code effectsStr}, so a text naming both halves
     * ("… by your opponent's Summons or abilities") sets both flags.
     *
     * <p>The immunity is scoped to the opponent only when <em>every</em> clause carries the
     * qualifier: on a mixed text the unqualified half is the broader rule, and the broader rule has
     * to win.
     *
     * <p>Shared by every gate that can carry one of these clauses, because which gate a card prints
     * — "If you control …", "If you have received N points of damage or more…", a quoted grant —
     * decides only when the shield is live, never what it covers. Reading the clause in one gate
     * and not another is what left Cecil 7-135S's shield on the card and off the field.
     */
    private static ChosenImmunity parseChosenImmunity(String effectsStr) {
        boolean summons = false, abilities = false, anyClause = false, anyUnqualified = false;
        Matcher m = ICB_EFFECT_NO_CHOSEN.matcher(effectsStr);
        while (m.find()) {
            String scope = m.group("scope").toLowerCase(Locale.ROOT);
            if (scope.contains("summon")) summons = true;
            if (scope.contains("abilit")) abilities = true;
            anyClause = true;
            if (m.group("opp") == null) anyUnqualified = true;
        }
        if (!anyClause) return ChosenImmunity.NONE;
        return new ChosenImmunity(summons, abilities, !anyUnqualified);
    }

    /**
     * Matches a self-targeted trait grant: "[CardName] gains [+N power,] [Trait(s)]." — no
     * "until end of turn".
     *
     * <p>The power clause is optional and discarded here: Kain 28-081L prints
     * "Damage 3 -- Kain gains +1000 power, Haste and First Strike.", where the number is
     * {@link #parseSelfPowerGrant}'s half of the same sentence. Without the optional clause the
     * trait list did not reach the front of the string, so the whole match failed and Kain's two
     * keywords were silently dropped while his power boost applied.
     */
    private static final Pattern SELF_TRAIT_GRANT = Pattern.compile(
        "(?i)^(?<name>.+?)\\s+gains?\\s+(?:\\+\\d+\\s+power\\s*(?:,|and)\\s*)?" +
        "(?<traits>(?:(?:Haste|First\\s+Strike|Brave|Back\\s+Attack)(?:\\s*(?:,|and)\\s*)?)+)[.!]?$"
    );

    /**
     * If {@code effectText} is a self-targeted trait grant for {@code cardName}
     * (e.g., "Desch gains First Strike."), returns the granted traits; otherwise empty.
     */
    static EnumSet<Trait> parseSelfTraitGrant(String effectText, String cardName) {
        Matcher m = SELF_TRAIT_GRANT.matcher(effectText.trim());
        if (!m.matches()) return EnumSet.noneOf(Trait.class);
        if (!m.group("name").trim().equalsIgnoreCase(cardName)) return EnumSet.noneOf(Trait.class);
        String traitsText = m.group("traits");
        EnumSet<Trait> result = EnumSet.noneOf(Trait.class);
        if (ICB_EFFECT_HASTE.matcher(traitsText).find())        result.add(Trait.HASTE);
        if (ICB_EFFECT_BRAVE.matcher(traitsText).find())        result.add(Trait.BRAVE);
        if (ICB_EFFECT_FIRST_STRIKE.matcher(traitsText).find()) result.add(Trait.FIRST_STRIKE);
        if (ICB_EFFECT_BACK_ATTACK.matcher(traitsText).find())  result.add(Trait.BACK_ATTACK);
        return result;
    }

    /**
     * "If a Forward in your Break Zone has Haste, First Strike, or Brave, [CardName] gains those
     * abilities." — Gogo 4-127H.
     *
     * <p>A self trait grant whose granted set is not printed but discovered: the sentence lists the
     * keywords to look for, and which of them Gogo actually gains depends on what is in the Break
     * Zone at the moment the question is asked. "those abilities" is therefore a filter over the
     * listed keywords rather than the grant itself — see
     * {@link #parseBreakZoneTraitGrantCandidates}.
     * Groups: {@code traits}, {@code name}.
     */
    private static final Pattern SELF_BZ_TRAIT_GRANT = Pattern.compile(
        "(?i)^If\\s+a\\s+Forward\\s+in\\s+your\\s+Break\\s+Zone\\s+has\\s+" +
        // Separators are spelled out as one repetition rather than an optional
        // "(?:,|and|or)" between keywords, because the printed list uses BOTH at its last
        // joint — "Haste, First Strike, or Brave". The looser form stops the list at
        // "First Strike", and the lazy name group then absorbs "or Brave, Gogo", so the
        // sentence still matches and the carrier's name check is what quietly fails.
        "(?<traits>(?:Haste|First\\s+Strike|Brave|Back\\s+Attack)" +
        "(?:\\s*,?\\s*(?:and|or)?\\s+(?:Haste|First\\s+Strike|Brave|Back\\s+Attack))*)," +
        // Comma-free, so a name can never reach back across the list either.
        "\\s+(?<name>[^,]+?)\\s+gains?\\s+those\\s+abilities[.!]?$"
    );

    /**
     * The keywords a {@link #SELF_BZ_TRAIT_GRANT} on {@code cardName} can confer, or an empty set
     * when {@code effectText} is not one.
     *
     * <p>These are candidates, not a grant: the carrier gains only those a Forward in its
     * controller's Break Zone actually has, which is board state and so is resolved by
     * {@code FieldGrantCalculator}. Returning the printed list here keeps the parsing side free of
     * the board, exactly as {@link #parseSelfTraitGrant} is.
     */
    static EnumSet<Trait> parseBreakZoneTraitGrantCandidates(String effectText, String cardName) {
        if (effectText == null || cardName == null) return EnumSet.noneOf(Trait.class);
        Matcher m = SELF_BZ_TRAIT_GRANT.matcher(effectText.trim());
        if (!m.matches()) return EnumSet.noneOf(Trait.class);
        if (!m.group("name").trim().equalsIgnoreCase(cardName)) return EnumSet.noneOf(Trait.class);
        String traitsText = m.group("traits");
        EnumSet<Trait> result = EnumSet.noneOf(Trait.class);
        if (ICB_EFFECT_HASTE.matcher(traitsText).find())        result.add(Trait.HASTE);
        if (ICB_EFFECT_BRAVE.matcher(traitsText).find())        result.add(Trait.BRAVE);
        if (ICB_EFFECT_FIRST_STRIKE.matcher(traitsText).find()) result.add(Trait.FIRST_STRIKE);
        if (ICB_EFFECT_BACK_ATTACK.matcher(traitsText).find())  result.add(Trait.BACK_ATTACK);
        return result;
    }

    /**
     * Matches a self-targeted power grant: "[CardName] gains +N power[ and …]." — the power sibling
     * of {@link #SELF_TRAIT_GRANT}, which matches only when the grant is traits alone.
     *
     * <p>The tail is deliberately unrestricted: what follows the power may be traits (which
     * {@link #parseSelfTraitGrant} reads from the same sentence) or a quoted ability (which
     * {@link #parseSelfGainsQuotedGrant} reads). Each parser takes its own half, so this one only
     * has to find the number.
     */
    private static final Pattern SELF_POWER_GRANT = Pattern.compile(
        "(?i)^(?<name>[^\"]+?)\\s+gains?\\s+\\+(?<power>\\d+)\\s+power(?<rest>.*)$",
        Pattern.DOTALL
    );

    /**
     * The power a self-targeted grant hands its own card ("Charlotte gains +2000 power and …",
     * "Elle gains +2000 power."), or 0 when {@code effectText} is not one or names another card.
     *
     * <p>A passive read, not an effect. {@code ActionResolverPower.tryParseFieldSelfPowerBoost}
     * parses the same sentence into a {@code Consumer<GameContext>} — what an <em>ability</em>
     * resolving the text would run — but a field ability is never resolved, so nothing was applying
     * it and the coverage report's "OK" on these printings was hollow. Every one of them in the
     * corpus sits behind a "Damage N --" gate whose state changes mid-game, which is the other
     * reason it has to be read per query rather than applied once.
     */
    static int parseSelfPowerGrant(String effectText, String cardName) {
        if (effectText == null || cardName == null) return 0;
        Matcher m = SELF_POWER_GRANT.matcher(effectText.trim());
        if (!m.matches() || !m.group("name").trim().equalsIgnoreCase(cardName)) return 0;
        return Integer.parseInt(m.group("power"));
    }

    /**
     * Parses all "If you control [X], [target] gains [Z]" conditional field boosts from
     * {@code textEn}.  Returns an empty list for Summons (field abilities don't apply to them)
     * and whenever no matching segments are found.
     *
     * <p>Each {@code [[br]]}-delimited segment is checked independently.  Segments that
     * have already been identified as action or auto abilities are still re-examined here
     * because the outer structure differs; the parse is additive and does not conflict.
     */
    public static List<IfControlBoost> parseIfControlBoosts(String textEn, String cardType) {
        if (textEn == null || textEn.isBlank()) return List.of();
        if ("Summon".equalsIgnoreCase(cardType)) return List.of();

        List<IfControlBoost> result = new ArrayList<>();
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;

            // "If you have a 《C》, [target] gains [effects]."
            Matcher crystalM = IF_HAVE_CRYSTAL_BOOST.matcher(seg);
            if (crystalM.find()) {
                String targetName = crystalM.group("target").trim();
                String effectsStr = crystalM.group("effects").trim();
                ControlCondition crystalCond = ControlCondition.forCrystal();
                Matcher pwrM = IF_CTRL_EFFECT_POWER.matcher(effectsStr);
                int powerBonus = pwrM.find() ? Integer.parseInt(pwrM.group(1)) : 0;
                EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
                if (ICB_EFFECT_HASTE.matcher(effectsStr).find())        traits.add(Trait.HASTE);
                if (ICB_EFFECT_BRAVE.matcher(effectsStr).find())        traits.add(Trait.BRAVE);
                if (ICB_EFFECT_FIRST_STRIKE.matcher(effectsStr).find()) traits.add(Trait.FIRST_STRIKE);
                if (ICB_EFFECT_BACK_ATTACK.matcher(effectsStr).find())  traits.add(Trait.BACK_ATTACK);
                if (powerBonus != 0 || !traits.isEmpty()) {
                    FieldPowerGrant targetFilter = parseIcbTargetFilter(targetName);
                    result.add(new IfControlBoost(List.of(crystalCond), "", targetName, targetFilter,
                            powerBonus, traits, "", false, false, false, null));
                }
                continue;
            }

            // "If [CardName] is [dull|active|attacking], [target] gains [effects]." and the
            // spelling whose whole consequence is an immunity, which reaches the same boost.
            Matcher stateM   = IF_CARD_IS_STATE_BOOST.matcher(seg);
            Matcher stateCncM = IF_CARD_IS_STATE_CANNOT_BE_CHOSEN.matcher(seg);
            Matcher stateHit = stateM.find() ? stateM : (stateCncM.find() ? stateCncM : null);
            if (stateHit != null) {
                String condCard   = stateHit.group("condcard").trim();
                String targetName = stateHit.group("target").trim();
                String effectsStr = stateHit.group("effects").trim();
                ControlCondition stateCond = ControlCondition.forNamedCardState(condCard,
                        ControlCondition.NamedCardState.valueOf(
                                stateHit.group("state").toUpperCase(Locale.ROOT)));
                Matcher pwrM = IF_CTRL_EFFECT_POWER.matcher(effectsStr);
                int powerBonus = pwrM.find() ? Integer.parseInt(pwrM.group(1)) : 0;
                EnumSet<Trait> traits = traitsNamedIn(effectsStr);
                ChosenImmunity stateImmunity = parseChosenImmunity(effectsStr);
                if (powerBonus != 0 || !traits.isEmpty() || stateImmunity.any()) {
                    FieldPowerGrant targetFilter = parseIcbTargetFilter(targetName);
                    IfControlBoost icb = new IfControlBoost(List.of(stateCond), "", targetName,
                            targetFilter, powerBonus, traits, "", stateImmunity.summons(),
                            stateImmunity.abilities(), false, null);
                    result.add(stateImmunity.opponentOnly() ? icb.asOpponentScopedChosenImmunity() : icb);
                }
                continue;
            }

            // "If you control [raw], [target] loses [N] power[ instead]."
            Matcher loseM = IF_CTRL_LOSE_OUTER.matcher(seg);
            if (loseM.find()) {
                String rawCond0  = loseM.group("raw").trim();
                String targetName = loseM.group("target").trim();
                int powerBonus   = -Integer.parseInt(loseM.group("power"));
                boolean isInstead = loseM.group("instead") != null;
                String[] condParts0 = rawCond0.split("(?i)\\s+and\\s+(?=a\\s+)");
                List<ControlCondition> conditions0 = new ArrayList<>();
                String exceptName0 = "";
                for (String part : condParts0) {
                    Matcher exceptM = IF_CTRL_BOOST_EXCEPT.matcher(part.trim());
                    String condText;
                    if (exceptM.matches()) { condText = exceptM.group(1).trim(); exceptName0 = exceptM.group(2).trim(); }
                    else                   { condText = part.trim(); }
                    ControlCondition cond = parseControlCondition(condText);
                    if (cond != null) conditions0.add(cond);
                }
                if (!conditions0.isEmpty()) {
                    FieldPowerGrant targetFilter = parseIcbTargetFilter(targetName);
                    result.add(new IfControlBoost(conditions0, exceptName0, targetName, targetFilter,
                            powerBonus, EnumSet.noneOf(Trait.class), "", false, false, false, null, 0, 0, isInstead));
                }
                continue;
            }

            // "If there are N or more cards removed from the game, [target] gains [effects]."
            Matcher rfpM = IF_RFP_COUNT_BOOST.matcher(seg);
            if (rfpM.find()) {
                int minRfp        = Integer.parseInt(rfpM.group("count"));
                String targetName = rfpM.group("target").trim();
                String effectsStr = rfpM.group("effects").trim();
                Matcher pwrM = IF_CTRL_EFFECT_POWER.matcher(effectsStr);
                int powerBonus = pwrM.find() ? Integer.parseInt(pwrM.group(1)) : 0;
                if (powerBonus != 0) {
                    FieldPowerGrant targetFilter = parseIcbTargetFilter(targetName);
                    result.add(new IfControlBoost(List.of(), "", targetName, targetFilter,
                            powerBonus, EnumSet.noneOf(Trait.class), "", false, false, false, null, minRfp, 0, false));
                }
                continue;
            }

            // "If you have received N points of damage or more, [target] gains [effects]."
            Matcher dmgM = IF_DAMAGE_RECEIVED_BOOST.matcher(seg);
            if (dmgM.find()) {
                int minDmg        = Integer.parseInt(dmgM.group("count"));
                String targetName = dmgM.group("target").trim();
                String effectsStr = dmgM.group("effects").trim();
                Matcher pwrM = IF_CTRL_EFFECT_POWER.matcher(effectsStr);
                int powerBonus = pwrM.find() ? Integer.parseInt(pwrM.group(1)) : 0;
                EnumSet<Trait> dmgTraits = EnumSet.noneOf(Trait.class);
                if (ICB_EFFECT_HASTE.matcher(effectsStr).find())        dmgTraits.add(Trait.HASTE);
                if (ICB_EFFECT_BRAVE.matcher(effectsStr).find())        dmgTraits.add(Trait.BRAVE);
                if (ICB_EFFECT_FIRST_STRIKE.matcher(effectsStr).find()) dmgTraits.add(Trait.FIRST_STRIKE);
                if (ICB_EFFECT_BACK_ATTACK.matcher(effectsStr).find())  dmgTraits.add(Trait.BACK_ATTACK);
                // Cecil 7-135S hands himself a quoted shield alongside the power, and the damage
                // count is the only thing gating it: "If you have received 5 points of damage or
                // more, Cecil gains +2000 power and \"Cecil cannot be chosen by your opponent's
                // abilities\"." The clause reads the same here as under any other gate.
                ChosenImmunity dmgImmunity = parseChosenImmunity(effectsStr);
                if (powerBonus != 0 || !dmgTraits.isEmpty() || dmgImmunity.any()) {
                    FieldPowerGrant targetFilter = parseIcbTargetFilter(targetName);
                    IfControlBoost icb = new IfControlBoost(List.of(), "", targetName, targetFilter,
                            powerBonus, dmgTraits, "", dmgImmunity.summons(), dmgImmunity.abilities(),
                            false, null, 0, minDmg, false);
                    result.add(dmgImmunity.opponentOnly() ? icb.asOpponentScopedChosenImmunity() : icb);
                }
                continue;
            }

            // "If your opponent has N cards or less in his/her hand, [target] gains [effects]."
            Matcher oppHandM = IF_OPP_HAND_BOOST.matcher(seg);
            if (oppHandM.find()) {
                int maxHand       = Integer.parseInt(oppHandM.group("count"));
                String targetName = oppHandM.group("target").trim();
                String effectsStr = oppHandM.group("effects").trim();
                Matcher pwrM = IF_CTRL_EFFECT_POWER.matcher(effectsStr);
                int powerBonus = pwrM.find() ? Integer.parseInt(pwrM.group(1)) : 0;
                EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
                if (ICB_EFFECT_HASTE.matcher(effectsStr).find())        traits.add(Trait.HASTE);
                if (ICB_EFFECT_BRAVE.matcher(effectsStr).find())        traits.add(Trait.BRAVE);
                if (ICB_EFFECT_FIRST_STRIKE.matcher(effectsStr).find()) traits.add(Trait.FIRST_STRIKE);
                if (ICB_EFFECT_BACK_ATTACK.matcher(effectsStr).find())  traits.add(Trait.BACK_ATTACK);
                if (powerBonus != 0 || !traits.isEmpty()) {
                    FieldPowerGrant targetFilter = parseIcbTargetFilter(targetName);
                    result.add(new IfControlBoost(List.of(), "", targetName, targetFilter,
                            powerBonus, traits, "", false, false, false, null, 0, 0, false, maxHand));
                }
                continue;
            }

            // "If you have N cards or less in your hand, [target] gains [effects]."
            Matcher ownHandM = IF_OWN_HAND_BOOST.matcher(seg);
            if (ownHandM.find()) {
                int maxHand       = Integer.parseInt(ownHandM.group("count"));
                String targetName = ownHandM.group("target").trim();
                String effectsStr = ownHandM.group("effects").trim();
                Matcher pwrM = IF_CTRL_EFFECT_POWER.matcher(effectsStr);
                int powerBonus = pwrM.find() ? Integer.parseInt(pwrM.group(1)) : 0;
                EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
                if (ICB_EFFECT_HASTE.matcher(effectsStr).find())        traits.add(Trait.HASTE);
                if (ICB_EFFECT_BRAVE.matcher(effectsStr).find())        traits.add(Trait.BRAVE);
                if (ICB_EFFECT_FIRST_STRIKE.matcher(effectsStr).find()) traits.add(Trait.FIRST_STRIKE);
                if (ICB_EFFECT_BACK_ATTACK.matcher(effectsStr).find())  traits.add(Trait.BACK_ATTACK);
                if (powerBonus != 0 || !traits.isEmpty()) {
                    FieldPowerGrant targetFilter = parseIcbTargetFilter(targetName);
                    result.add(new IfControlBoost(List.of(), "", targetName, targetFilter,
                            powerBonus, traits, "", false, false, false, null, 0, 0, false, 0, 0, maxHand));
                }
                continue;
            }

            // "If you have N or more cards in your hand, [target] gains [effects]."
            // Must follow IF_OWN_HAND_BOOST: the two share the "If you have N" prefix and differ
            // only in the comparison word, so neither can be allowed to run on the other's text.
            Matcher ownHandMinM = IF_OWN_HAND_MIN_BOOST.matcher(seg);
            if (ownHandMinM.find()) {
                IfControlBoost boost = icbSelfGrant(ownHandMinM.group("target").trim(),
                        ownHandMinM.group("effects").trim());
                if (boost != null)
                    result.add(boost.withMinOwnHandSize(Integer.parseInt(ownHandMinM.group("count"))));
                continue;
            }

            // "If you have N or more cards in your hand, [target] cannot be blocked." (Zidane 8-115L)
            // Must follow IF_OWN_HAND_MIN_BOOST for the same reason that one follows IF_OWN_HAND_BOOST:
            // all three open on "If you have N", and only the clause after the comma tells them apart.
            Matcher ownHandMinNbM = IF_OWN_HAND_MIN_CANNOT_BE_BLOCKED.matcher(seg);
            if (ownHandMinNbM.find()) {
                String nbTarget = ownHandMinNbM.group("target").trim();
                result.add(new IfControlBoost(List.of(), "", nbTarget, parseIcbTargetFilter(nbTarget),
                        0, EnumSet.noneOf(Trait.class), "", false, false, true, null, 0, 0, false, 0)
                        .withMinOwnHandSize(Integer.parseInt(ownHandMinNbM.group("count"))));
                continue;
            }

            // "If you control N or more different Element Backups, [target] gains [effects]."
            Matcher diffElemBkpM = IF_N_DIFF_ELEMENT_BACKUPS_BOOST.matcher(seg);
            if (diffElemBkpM.find()) {
                IfControlBoost boost = icbSelfGrant(diffElemBkpM.group("target").trim(),
                        diffElemBkpM.group("effects").trim());
                if (boost != null)
                    result.add(boost.withMinDifferentElementBackups(
                            Integer.parseInt(diffElemBkpM.group("count"))));
                continue;
            }

            // "If you control N or more Backups and if the Backups you control are all of different Elements, [target] gains +[power] power."
            Matcher diffElemM = IF_N_BACKUPS_ALL_DIFF_ELEMENTS_BOOST.matcher(seg);
            if (diffElemM.find()) {
                int minCount      = Integer.parseInt(diffElemM.group("count"));
                String targetName = diffElemM.group("target").trim();
                int powerBonus    = Integer.parseInt(diffElemM.group("power"));
                ControlCondition bkpCond = new ControlCondition(
                        List.of(), minCount, false, "Backup", null, null, null, 0, List.of());
                FieldPowerGrant targetFilter = parseIcbTargetFilter(targetName);
                result.add(new IfControlBoost(List.of(bkpCond), "", targetName, targetFilter,
                        powerBonus, EnumSet.noneOf(Trait.class), "", false, false, false, null, 0, 0, false, 0, 0, 0, true));
                continue;
            }

            // "If your opponent controls N or more Forwards, [target] gains [effects]."
            Matcher oppFwdM = IF_OPP_CTRL_N_OR_MORE_FWD_BOOST.matcher(seg);
            if (oppFwdM.find()) {
                int minFwds       = Integer.parseInt(oppFwdM.group("n"));
                String targetName = oppFwdM.group("target").trim();
                String effectsStr = oppFwdM.group("effects").trim();
                Matcher pwrM = IF_CTRL_EFFECT_POWER.matcher(effectsStr);
                int powerBonus = pwrM.find() ? Integer.parseInt(pwrM.group(1)) : 0;
                EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
                if (ICB_EFFECT_HASTE.matcher(effectsStr).find())        traits.add(Trait.HASTE);
                if (ICB_EFFECT_BRAVE.matcher(effectsStr).find())        traits.add(Trait.BRAVE);
                if (ICB_EFFECT_FIRST_STRIKE.matcher(effectsStr).find()) traits.add(Trait.FIRST_STRIKE);
                if (ICB_EFFECT_BACK_ATTACK.matcher(effectsStr).find())  traits.add(Trait.BACK_ATTACK);
                if (powerBonus != 0 || !traits.isEmpty()) {
                    FieldPowerGrant targetFilter = parseIcbTargetFilter(targetName);
                    result.add(new IfControlBoost(List.of(), "", targetName, targetFilter,
                            powerBonus, traits, "", false, false, false, null, 0, 0, false, 0, minFwds));
                }
                continue;
            }

            // "If your opponent doesn't control Forwards [of cost N or more], [target] gains [effects]."
            Matcher oppNoFwdM = IF_OPP_CTRL_NO_FWD_BOOST.matcher(seg);
            if (oppNoFwdM.find()) {
                String targetName = oppNoFwdM.group("target").trim();
                String effectsStr = oppNoFwdM.group("effects").trim();
                int minCost = oppNoFwdM.group("mincost") != null
                        ? Integer.parseInt(oppNoFwdM.group("mincost")) : 0;
                // "exactly 0 [cost ≥ N] Forwards on the opponent's field"
                ControlCondition noFwdCond = new ControlCondition(
                        List.of(), 0, true, "Forward", null, null, null, 0,
                        List.of(), false, null, null, false, false, true, minCost);
                Matcher pwrM = IF_CTRL_EFFECT_POWER.matcher(effectsStr);
                int powerBonus = pwrM.find() ? Integer.parseInt(pwrM.group(1)) : 0;
                EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
                if (ICB_EFFECT_HASTE.matcher(effectsStr).find())        traits.add(Trait.HASTE);
                if (ICB_EFFECT_BRAVE.matcher(effectsStr).find())        traits.add(Trait.BRAVE);
                if (ICB_EFFECT_FIRST_STRIKE.matcher(effectsStr).find()) traits.add(Trait.FIRST_STRIKE);
                if (ICB_EFFECT_BACK_ATTACK.matcher(effectsStr).find())  traits.add(Trait.BACK_ATTACK);
                if (powerBonus != 0 || !traits.isEmpty()) {
                    FieldPowerGrant targetFilter = parseIcbTargetFilter(targetName);
                    result.add(new IfControlBoost(List.of(noFwdCond), "", targetName, targetFilter,
                            powerBonus, traits, "", false, false, false, null));
                }
                continue;
            }

            // "If your opponent controls [any] X, [target] gains [effects]."
            Matcher oppCtrlM = IF_OPP_CTRL_BOOST_OUTER.matcher(seg);
            if (oppCtrlM.find()) {
                // Normalise "any" → "a" so parseControlCondition handles it as minCount=1
                String rawCond    = oppCtrlM.group("raw").trim()
                                        .replaceFirst("(?i)^any\\s+", "a ");
                String targetName = oppCtrlM.group("target").trim();
                String effectsStr = oppCtrlM.group("effects").trim();
                ControlCondition base = parseControlCondition(rawCond);
                if (base != null) {
                    ControlCondition oppCond = new ControlCondition(
                            base.requiredCardNames(), base.minCount(), base.exactCount(),
                            base.cardType(), base.element(), base.job(), base.category(),
                            base.minPower(), base.orCardNames(), base.anyOf(),
                            base.excludeElement(), base.stateCardName(),
                            base.requiresCrystal(), base.allHave(), true);
                    Matcher pwrM = IF_CTRL_EFFECT_POWER.matcher(effectsStr);
                    int powerBonus = pwrM.find() ? Integer.parseInt(pwrM.group(1)) : 0;
                    EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
                    if (ICB_EFFECT_HASTE.matcher(effectsStr).find())        traits.add(Trait.HASTE);
                    if (ICB_EFFECT_BRAVE.matcher(effectsStr).find())        traits.add(Trait.BRAVE);
                    if (ICB_EFFECT_FIRST_STRIKE.matcher(effectsStr).find()) traits.add(Trait.FIRST_STRIKE);
                    if (ICB_EFFECT_BACK_ATTACK.matcher(effectsStr).find())  traits.add(Trait.BACK_ATTACK);
                    if (powerBonus != 0 || !traits.isEmpty()) {
                        FieldPowerGrant targetFilter = parseIcbTargetFilter(targetName);
                        result.add(new IfControlBoost(List.of(oppCond), "", targetName, targetFilter,
                                powerBonus, traits, "", false, false, false, null));
                    }
                }
                continue;
            }

            // "If all the [Type] you control have [property], [target] gains [effects]."
            Matcher allHaveM = IF_ALL_HAVE_BOOST.matcher(seg);
            if (allHaveM.find()) {
                String typeStr    = allHaveM.group("type").trim();
                String property   = allHaveM.group("property").trim();
                String targetStr  = allHaveM.group("target").trim();
                String effectsStr = allHaveM.group("effects").trim();

                String rawType = typeStr.replaceAll("(?i)s$", "");
                String ahCardType = Character.toUpperCase(rawType.charAt(0)) + rawType.substring(1).toLowerCase();

                String element   = null;
                String jobFilter = null;
                Matcher elemPM = ALL_HAVE_ELEMENT_PROPERTY.matcher(property);
                if (elemPM.find()) {
                    element = elemPM.group(1);
                } else {
                    List<String> jobs = new ArrayList<>();
                    Matcher jobTokM = ALL_HAVE_JOB_TOKEN.matcher(property);
                    while (jobTokM.find()) {
                        String j = jobTokM.group(1) != null ? jobTokM.group(1) : jobTokM.group(2);
                        if (j != null) jobs.add(j.trim());
                    }
                    if (!jobs.isEmpty()) jobFilter = String.join("|", jobs);
                }

                if (element != null || jobFilter != null) {
                    ControlCondition cond = ControlCondition.forAllHave(ahCardType, element, jobFilter);
                    Matcher pwrM = IF_CTRL_EFFECT_POWER.matcher(effectsStr);
                    int powerBonus = pwrM.find() ? Integer.parseInt(pwrM.group(1)) : 0;
                    if (powerBonus != 0) {
                        FieldPowerGrant targetFilter;
                        String targetName;
                        if ("they".equalsIgnoreCase(targetStr)) {
                            boolean isFwd = ahCardType.equalsIgnoreCase("Forward") || ahCardType.equalsIgnoreCase("Character");
                            boolean isBkp = ahCardType.equalsIgnoreCase("Backup")  || ahCardType.equalsIgnoreCase("Character");
                            boolean isMon = ahCardType.equalsIgnoreCase("Monster") || ahCardType.equalsIgnoreCase("Character");
                            targetFilter = new FieldPowerGrant(null, null, isFwd, isBkp, isMon,
                                    null, powerBonus, EnumSet.noneOf(Trait.class));
                            targetName = "";
                        } else {
                            targetFilter = parseIcbTargetFilter(targetStr);
                            targetName = targetStr;
                        }
                        result.add(new IfControlBoost(List.of(cond), "", targetName, targetFilter,
                                powerBonus, EnumSet.noneOf(Trait.class), "", false, false, false, null));
                    }
                }
                continue;
            }

            Matcher m    = IF_CTRL_BOOST_OUTER.matcher(seg);
            Matcher cnbM = IF_CTRL_CANNOT_BE_BLOCKED.matcher(seg);
            Matcher cncM = IF_CTRL_CANNOT_BE_CHOSEN.matcher(seg);

            String rawCond, targetName, effectsStr;
            boolean isCannotBeBlocked;
            int[]   icbCostFilter = null;
            if (m.find()) {
                rawCond           = m.group("raw").trim();
                targetName        = m.group("target").trim();
                effectsStr        = m.group("effects").trim();
                isCannotBeBlocked = false;
            } else if (cnbM.find()) {
                rawCond           = cnbM.group("raw").trim();
                targetName        = cnbM.group("target").trim();
                effectsStr        = "";
                String costValStr = cnbM.group("costval");
                if (costValStr != null) {
                    int costVal  = Integer.parseInt(costValStr);
                    boolean orMore = !"less".equalsIgnoreCase(cnbM.group("costcmp"));
                    icbCostFilter = new int[]{costVal, orMore ? 1 : 0};
                }
                isCannotBeBlocked = icbCostFilter == null; // full unblockable when no cost clause
            } else if (cncM.find()) {
                // "If you control X, [Self] cannot be chosen by your opponent's …" — the whole
                // consequence is the immunity, so the matched clause is handed on as the effects
                // string and ICB_EFFECT_NO_CHOSEN below reads it exactly as it reads the quoted
                // spelling ("… gains \"[Self] cannot be chosen …\"").
                rawCond           = cncM.group("raw").trim();
                targetName        = cncM.group("target").trim();
                effectsStr        = cncM.group("effects").trim();
                isCannotBeBlocked = false;
            } else {
                continue;
            }

            // Split on " and a " to support AND conditions ("a Job Father and a Job Mother")
            String[] condParts = rawCond.split("(?i)\\s+and\\s+(?=a\\s+)");

            List<ControlCondition> conditions = new ArrayList<>();
            String exceptName = "";

            for (String part : condParts) {
                Matcher exceptM = IF_CTRL_BOOST_EXCEPT.matcher(part.trim());
                String condText;
                if (exceptM.matches()) {
                    condText   = exceptM.group(1).trim();
                    exceptName = exceptM.group(2).trim();
                } else {
                    condText = part.trim();
                }
                ControlCondition cond = parseControlCondition(condText);
                if (cond == null) cond = parseUnionControlCondition(condText);
                if (cond != null) conditions.add(cond);
            }
            if (conditions.isEmpty()) continue;

            Matcher pwrM = IF_CTRL_EFFECT_POWER.matcher(effectsStr);
            int powerBonus = pwrM.find() ? Integer.parseInt(pwrM.group(1)) : 0;

            Matcher quotedM = IF_CTRL_EFFECT_QUOTED.matcher(effectsStr);
            String specialText = quotedM.find() ? quotedM.group(1).trim() : "";

            EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
            if (ICB_EFFECT_HASTE.matcher(effectsStr).find())        traits.add(Trait.HASTE);
            if (ICB_EFFECT_BRAVE.matcher(effectsStr).find())        traits.add(Trait.BRAVE);
            if (ICB_EFFECT_FIRST_STRIKE.matcher(effectsStr).find()) traits.add(Trait.FIRST_STRIKE);
            if (ICB_EFFECT_BACK_ATTACK.matcher(effectsStr).find())  traits.add(Trait.BACK_ATTACK);

            ChosenImmunity immunity = parseChosenImmunity(effectsStr);
            boolean noChooseSummons = immunity.summons();
            boolean noChooseAbilits = immunity.abilities();
            boolean chosenImmunityOppOnly = immunity.opponentOnly();

            if (powerBonus == 0 && traits.isEmpty() && specialText.isEmpty()
                    && !noChooseSummons && !noChooseAbilits && !isCannotBeBlocked
                    && icbCostFilter == null) continue;

            FieldPowerGrant targetFilter = parseIcbTargetFilter(targetName);
            IfControlBoost icb = new IfControlBoost(conditions, exceptName, targetName, targetFilter,
                    powerBonus, traits, specialText, noChooseSummons, noChooseAbilits,
                    isCannotBeBlocked, icbCostFilter);
            result.add(chosenImmunityOppOnly ? icb.asOpponentScopedChosenImmunity() : icb);
        }

        // Parse "The [Job X / Category Y / Element] Forwards [other than Z] you control [gain "This
        // Forward"] cannot be blocked by a/Forwards of cost N or more/less." — always-active grants
        // with no "If you control..." condition, stored as ICBs with an empty conditions list.
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;
            Matcher dm = FIELD_GRANT_CNB_BY_COST_DIRECT.matcher(seg);
            Matcher qm = FIELD_GRANT_CNB_BY_COST_QUOTED.matcher(seg);
            Matcher fm = dm.find() ? dm : (qm.find() ? qm : null);
            if (fm == null) continue;
            String job      = fm.group("job");
            String category = fm.group("category");
            String element  = fm.group("element");
            String except   = fm.group("except");
            int costVal     = Integer.parseInt(fm.group("costval"));
            boolean orMore  = !"less".equalsIgnoreCase(fm.group("costcmp"));
            FieldPowerGrant grantFilter = new FieldPowerGrant(
                    job      != null ? job.trim()      : null,
                    category != null ? category.trim() : null,
                    true, false, false,
                    except   != null ? except.trim()   : null,
                    0, java.util.Set.of(), false, -1, null,
                    element  != null ? element.trim()  : null);
            result.add(new IfControlBoost(List.of(), "", "", grantFilter, 0,
                    EnumSet.noneOf(Trait.class), "", false, false, false,
                    new int[]{costVal, orMore ? 1 : 0}));
        }

        // Parse "[CardName] cannot be blocked." — unconditional, no "If you control" condition.
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;
            Matcher m = UNCONDITIONAL_CNB_PATTERN.matcher(seg);
            if (!m.matches()) continue;
            String name = m.group("name").trim();
            result.add(new IfControlBoost(List.of(), "", name, null, 0,
                    EnumSet.noneOf(Trait.class), "", false, false, true, null, 0, 0, false));
        }

        // Parse "The Card Name X you control cannot be chosen by your opponent's Summons [or
        // abilities]." — unconditional, no "If you control" condition. The named card is the
        // ICB's target, so the protection follows whichever copy of X its controller has on the
        // field rather than being pinned to the card that granted it.
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;
            Matcher m = UNCONDITIONAL_NAMED_CANNOT_BE_CHOSEN.matcher(seg);
            if (!m.matches()) continue;
            String scope = m.group("scope").toLowerCase(Locale.ROOT);
            result.add(new IfControlBoost(List.of(), "", m.group("name").trim(), null, 0,
                    EnumSet.noneOf(Trait.class), "",
                    scope.contains("summon"), scope.contains("abilit"), false, null, 0, 0, false)
                    // The pattern requires the "your opponent's" qualifier, so every match is
                    // opponent-scoped: the controller may still choose their own protected card.
                    .asOpponentScopedChosenImmunity());
        }

        // Parse the conditional named form — "If you control Card Name Snow, it cannot be chosen by
        // your opponent's abilities." (Serah 1-045R). "it" is the card the condition names, so the
        // condition and the target carry the same name.
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;
            Matcher m = IF_CONTROL_NAMED_IT_CANNOT_BE_CHOSEN.matcher(seg);
            if (!m.matches()) continue;
            String target = m.group("name").trim();
            ControlCondition cond = parseControlCondition("Card Name " + target);
            if (cond == null) continue;
            String scope = m.group("scope").toLowerCase(Locale.ROOT);
            result.add(new IfControlBoost(List.of(cond), "", target, null, 0,
                    EnumSet.noneOf(Trait.class), "",
                    scope.contains("summon"), scope.contains("abilit"), false, null, 0, 0, false)
                    // The pattern requires the "your opponent's" qualifier, so the controller may
                    // still choose their own protected card — as with the unconditional form.
                    .asOpponentScopedChosenImmunity());
        }

        // Parse the Job-and-Card-Name form — "The Job Dancer and Card Name Dancer you control
        // cannot be chosen by …" (Mayakov 15-121R). One boost per branch, since a single filter
        // would AND the two and cover only cards that are both.
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;
            Matcher m = UNCONDITIONAL_JOB_AND_NAME_CANNOT_BE_CHOSEN.matcher(seg);
            if (!m.matches()) continue;
            String scope = m.group("scope").toLowerCase(Locale.ROOT);
            boolean byS = scope.contains("summon");
            boolean byA = scope.contains("abilit");
            FieldPowerGrant jobFilter = new FieldPowerGrant(m.group("job").trim(), null,
                    true, true, true, null, 0, EnumSet.noneOf(Trait.class));
            result.add(new IfControlBoost(List.of(), "", "", jobFilter, 0,
                    EnumSet.noneOf(Trait.class), "", byS, byA, false, null, 0, 0, false)
                    .asOpponentScopedChosenImmunity());
            result.add(new IfControlBoost(List.of(), "", m.group("name").trim(), null, 0,
                    EnumSet.noneOf(Trait.class), "", byS, byA, false, null, 0, 0, false)
                    .asOpponentScopedChosenImmunity());
        }

        // Parse the same shield handed to a filtered set — "The Monsters other than Silver Dragon
        // you control cannot be chosen by …". Same storage as the named form above, with a target
        // filter standing in for the name.
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;
            Matcher m = UNCONDITIONAL_FILTERED_CANNOT_BE_CHOSEN.matcher(seg);
            if (!m.matches()) continue;
            String job      = m.group("job");
            String category = m.group("category");
            // No type token is not "no rows": a Job or Category names Characters wherever they
            // sit, so the filter has to reach all three. parseFieldGrantTargetFlags answers for
            // the printings that do name a type.
            int[] incl = m.group("targets") != null
                    ? parseFieldGrantTargetFlags(m.group("targets"))
                    : new int[]{1, 1, 1};
            String except = m.group("except");
            FieldPowerGrant filter = FieldPowerGrant.sameSideFiltered(
                    job      != null ? job.trim()      : null,
                    category != null ? category.trim() : null,
                    incl[0] != 0, incl[1] != 0, incl[2] != 0,
                    except != null ? except.trim() : null,
                    0, EnumSet.noneOf(Trait.class), null,
                    traitsNamedIn(m.group("withtrait")));
            String scope = m.group("scope").toLowerCase(Locale.ROOT);
            IfControlBoost shield = new IfControlBoost(List.of(), "", "", filter, 0,
                    EnumSet.noneOf(Trait.class), "",
                    scope.contains("summon"), scope.contains("abilit"), false, null, 0, 0, false)
                    .asOpponentScopedChosenImmunity();
            // "Backup abilities" narrows the immunity to abilities belonging to a card of that
            // type; every other printing names no type and covers any source.
            String sourceType = m.group("sourcetype");
            if (sourceType != null) shield = shield.withChosenImmunitySourceType(sourceType.trim());
            result.add(shield);
        }

        return List.copyOf(result);
    }

    /**
     * Builds the unconditional half of an {@link IfControlBoost} — the target and what it gains —
     * leaving the caller to attach the condition with the matching wither.
     *
     * <p>Returns {@code null} when the effects clause yields neither power nor a trait, so a
     * caller cannot mistake "parsed but grants nothing" for a live grant. That rejection is why
     * this is worth factoring out: every branch that reads a "[target] gains [effects]" tail owes
     * the same check, and the ones written inline have repeated it by hand.
     */
    private static IfControlBoost icbSelfGrant(String targetName, String effectsStr) {
        Matcher pwrM = IF_CTRL_EFFECT_POWER.matcher(effectsStr);
        int powerBonus = pwrM.find() ? Integer.parseInt(pwrM.group(1)) : 0;
        EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
        if (ICB_EFFECT_HASTE.matcher(effectsStr).find())        traits.add(Trait.HASTE);
        if (ICB_EFFECT_BRAVE.matcher(effectsStr).find())        traits.add(Trait.BRAVE);
        if (ICB_EFFECT_FIRST_STRIKE.matcher(effectsStr).find()) traits.add(Trait.FIRST_STRIKE);
        if (ICB_EFFECT_BACK_ATTACK.matcher(effectsStr).find())  traits.add(Trait.BACK_ATTACK);
        if (powerBonus == 0 && traits.isEmpty()) return null;
        return new IfControlBoost(List.of(), "", targetName, parseIcbTargetFilter(targetName),
                powerBonus, traits, "", false, false, false, null, 0, 0, false, 0);
    }

    /**
     * Parses an {@link IfControlBoost} target phrase like "the Category IV Forwards you control"
     * into a filter-shaped {@link FieldPowerGrant} (zero power; power/traits come from the outer
     * effects clause).  Returns {@code null} when the phrase is a literal card name.
     */
    private static FieldPowerGrant parseIcbTargetFilter(String targetPhrase) {
        if (targetPhrase == null) return null;
        Matcher m = ICB_TARGET_FILTER_PATTERN.matcher(targetPhrase.trim());
        if (!m.matches()) return null;
        String job      = m.group("job");
        String category = m.group("category");
        String except   = m.group("except");
        int[] incl = parseFieldGrantTargetFlags(m.group("targets"));
        return new FieldPowerGrant(
                job != null ? job.trim() : null,
                category,
                incl[0] != 0, incl[1] != 0, incl[2] != 0,
                except != null ? except.trim() : null,
                0,
                EnumSet.noneOf(Trait.class));
    }

    // -------------------------------------------------------------------------
    // FieldPowerGrant parsing
    // -------------------------------------------------------------------------

    /** The "All the …" spelling of a same-side grant, normalised to the "The …" every pattern reads. */
    private static final Pattern ALL_THE_GRANT_PREFIX = Pattern.compile("(?i)^All\\s+the\\s+");

    /**
     * A same-side power grant with a damage rider attached: "The Forwards other than Cecil you
     * control gain +1000 power, and if they are dealt damage by a Summon or an ability, the damage
     * becomes 0 instead." (Cecil 2-129L, the only printing of this shape.)
     *
     * <p>Group {@code grant} is the power half; {@code targets} and {@code except} are the target
     * filter the rider's "they" refers back to; {@code rider} is the damage clause with its own
     * leading "if" already consumed.
     */
    private static final Pattern GRANT_WITH_DAMAGE_RIDER = Pattern.compile(
        "(?i)^(?<grant>The\\s+(?<targets>Forwards?|Backups?|Monsters?|Characters?)\\s+" +
        "(?:other\\s+than\\s+(?<except>[A-Z][A-Za-z''\\-]+(?:\\s+[A-Za-z''\\-]+)*)\\s+)?" +
        "you\\s+control\\s+gains?\\s+\\+\\d+\\s+power)" +
        ",\\s+and\\s+if\\s+they\\s+are\\s+(?<rider>dealt\\s+damage.+?)\\s+instead[.!]?$"
    );

    /**
     * Splits a grant that carries a damage rider into the two sentences the engine already reads
     * separately, or {@code null} when {@code seg} is not one.
     *
     * <p>{@code [0]} is the power grant on its own; {@code [1]} is the rider rewritten into the
     * canonical field-damage wording, with "they" resolved back to the grant's own target filter —
     * "If a Forward other than Cecil you control is dealt damage by a Summon or an ability, the
     * damage becomes 0 instead." Both halves then land in the parser that already owns that shape,
     * rather than teaching either one about a sentence carrying the other's effect.
     */
    static String[] splitGrantWithDamageRider(String seg) {
        Matcher m = GRANT_WITH_DAMAGE_RIDER.matcher(seg.trim());
        if (!m.matches()) return null;
        String singular = m.group("targets").replaceAll("(?i)s$", "");
        String except   = m.group("except");
        String rewritten = "If a " + singular
                + (except != null ? " other than " + except.trim() : "")
                + " you control is " + m.group("rider").trim() + " instead.";
        return new String[]{ m.group("grant") + ".", rewritten };
    }

    /**
     * The text a field-damage scan should read for {@code seg}: the rider half when {@code seg} is
     * a power grant carrying one, and {@code seg} unchanged otherwise.
     */
    static String fieldDamageRiderText(String seg) {
        String[] split = splitGrantWithDamageRider(seg);
        return split != null ? split[1] : seg;
    }

    /**
     * Matches passive grants of the form:
     * "The [Element] (Job X | Category Y) [Forwards?|Backups?|Monsters?|Characters?]
     *  [other than Z] you control gain[s] [+N power] [and] [Trait...]"
     *
     * <p>The optional {@code element} prefix stacks with the Job/Category filter — both must hold
     * (3-040C DGS Trooper 1st Class, "The Ice Job Standard Unit Forwards you control"). It sits
     * ahead of the {@code Job}/{@code Category} keyword, so a job or category whose own name
     * begins with an element word cannot be clipped by it. The element-only form with no
     * Job/Category filter is {@link #FIELD_GRANT_BARE_PATTERN}.
     */
    private static final Pattern FIELD_GRANT_PATTERN = Pattern.compile(
        "(?i)^The\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Job\\s+(?<job>[A-Za-z][A-Za-z\\s''\\-]*?)(?=\\s+Forwards?|\\s+Backups?|\\s+Monsters?|\\s+Characters?|\\s+other\\s+than|\\s+you)|" +
        "Category\\s+(?<category>\\S+))\\s*" +
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)?\\s*" +
        "(?:other\\s+than\\s+(?<except>[A-Z][A-Za-z''\\-]+(?:\\s+[A-Za-z''\\-]+)*)\\s+)?you\\s+control\\s+gains?\\s+" +
        "(?:\\+(?<power>\\d+)\\s+power(?:\\s+and\\s+)?)?" +
        "(?<traitstext>.+?)?[.!]?$"
    );

    /**
     * Matches the two-tier damage-conditional grant:
     * "The [type] [other than X] you control gain +BASE power.
     *  If you have received THRESHOLD points of damage or more,
     *  the [type2] you control gain +BOOST power instead."
     * Groups: {@code targets}, {@code except} (optional), {@code basepower},
     * {@code threshold}, {@code targets2}, {@code boostpower}.
     */
    private static final Pattern FIELD_GRANT_DAMAGE_THRESHOLD_PATTERN = Pattern.compile(
        "(?i)^The\\s+" +
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+" +
        "(?:other\\s+than\\s+(?<except>[A-Z][A-Za-z''\\-]+(?:\\s+[A-Za-z''\\-]+)*)\\s+)?" +
        "you\\s+control\\s+gains?\\s+\\+(?<basepower>\\d+)\\s+power\\.\\s+" +
        "If\\s+you\\s+have\\s+received\\s+(?<threshold>\\d+)\\s+points?\\s+of\\s+damage\\s+or\\s+more,?\\s+" +
        "the\\s+(?<targets2>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+" +
        "you\\s+control\\s+gains?\\s+\\+(?<boostpower>\\d+)\\s+power\\s+instead[.!]?$"
    );

    /**
     * Matches the bare same-side grant "The [Element] Forwards?|Backups?|Monsters?|Characters?
     * [with Trait] [other than Z] you control gain +N power" (no Job/Category prefix).
     * The optional {@code element} group captures an element name prefix (e.g. "Ice"), including
     * the pseudo-element "Multi-Element" (Nichol 13-096R), which {@link CardFilters#meetsElementFilter}
     * resolves to "has more than one element". The optional {@code withtrait} group restricts the
     * grant to cards carrying that trait (Ash 21-062H). The optional trailing {@code traitstext}
     * hands the boosted cards a trait as well (Poppy 18-048R, "…gain +1000 power and First Strike.")
     * — the Job/Category form of that already lives in {@link #FIELD_GRANT_PATTERN}.
     * Companion to {@link #FIELD_GRANT_PATTERN}.
     *
     * <p>The optional {@code attacking} prefix restricts the grant to Forwards currently declared as
     * attackers (Lava Spider 8-022R). It is a board-state filter rather than a card filter, so
     * {@link FieldPowerGrant#appliesToCard} cannot resolve it — {@code MainWindow} gates on it while
     * summing contributions.
     */
    private static final Pattern FIELD_GRANT_BARE_PATTERN = Pattern.compile(
        // The window the grant is open in, when the printing states one — "During your opponent's
        // turn, all the Forwards you control gain +2000 power." (Rydia 28-072L). The optional
        // "all" comes with it: every printing that opens with the window also says "all the",
        // and the two are the same sentence otherwise.
        "(?i)^(?:During\\s+(?<turnwindow>your\\s+opponent's|your)\\s+turn,\\s+all\\s+)?The\\s+" +
        "(?<attacking>attacking\\s+)?" +
        "(?<element>" + ELEMENT_KEYWORD + ")?\\s*" +
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+" +
        "(?:with\\s+(?<withtrait>" + TRAIT_KEYWORD + ")\\s+)?" +
        "(?:other\\s+than\\s+(?<except>[A-Z][A-Za-z''\\-]+(?:\\s+[A-Za-z''\\-]+)*)\\s+)?" +
        "you\\s+control\\s+gains?\\s+\\+(?<power>\\d+)\\s+power" +
        "(?:\\s+and\\s+(?<traitstext>" + TRAIT_KEYWORD + "(?:\\s+and\\s+" + TRAIT_KEYWORD + ")*))?" +
        "[.!]?$"
    );

    /**
     * The trait-only sibling of {@link #FIELD_GRANT_BARE_PATTERN}: "The [Element] [type]
     * [with Trait] [other than Z] you control gain Trait[, and Trait]." — a grant with no power
     * component and no Job/Category filter, which the bare pattern's mandatory "+N power" clause
     * turns away and {@link #FIELD_GRANT_PATTERN} never sees because it requires a Job or Category.
     *
     * <p>The trait clause is spelled out from {@link #TRAIT_KEYWORD} rather than left open, and the
     * pattern is end-anchored, so the resolved-effect wording "…gain Haste until the end of the
     * turn." — which is an effect the {@code ActionResolver} runs, not a passive — cannot reach it.
     */
    private static final Pattern FIELD_GRANT_BARE_TRAIT_ONLY_PATTERN = Pattern.compile(
        "(?i)^The\\s+" +
        "(?<element>" + ELEMENT_KEYWORD + ")?\\s*" +
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+" +
        "(?:with\\s+(?<withtrait>" + TRAIT_KEYWORD + ")\\s+)?" +
        "(?:other\\s+than\\s+(?<except>[A-Z][A-Za-z''\\-]+(?:\\s+[A-Za-z''\\-]+)*)\\s+)?" +
        "you\\s+control\\s+gains?\\s+" +
        "(?<traitstext>" + TRAIT_KEYWORD + "(?:\\s*,?\\s*(?:and\\s+)?" + TRAIT_KEYWORD + ")*)" +
        "[.!]?$"
    );

    /**
     * Matches "[Damage N -- ] [Self] gains "&lt;field ability&gt;"" — a card handing itself a whole
     * passive ability, usually once its controller has taken enough damage. Aranea 11-086L,
     * Yang 13-064R, Yuna 16-134S and Tifa 11-071L are the printings.
     *
     * <p>The quoted half is an ordinary field ability, so the parsers re-enter it on its own and the
     * wrapper contributes only the "Damage N -- " gate. Group {@code subject} is checked against the
     * carrier's name when the caller knows it; {@code threshold} is the gate, captured here because
     * this parser reads raw {@code [[br]]} segments rather than the stripped ones
     * {@link #parseFieldAbilities} produces.
     *
     * <p>The subject may not contain a comma, which is what keeps the lazy group inside the name
     * rather than letting it swallow a leading condition: "If a Blessing Counter is placed on
     * Number 24, Number 24 gains "…"" (20-036H) is a counter grant read elsewhere, and without that
     * bound a caller passing no name would read the whole clause as the subject and claim it.
     */
    static final Pattern SELF_GAINS_QUOTED_FIELD_ABILITY = Pattern.compile(
        "(?i)^(?:Damage\\s+(?<threshold>\\d+)\\s+--\\s+)?" +
        "(?<subject>[^\",.]+?)\\s+gains?\\s+" +
        "\"(?<inner>.+)\"[.!]?$",
        Pattern.DOTALL
    );

    /**
     * The field ability {@code effectText} hands {@code cardName} in quotes, or {@code null} when it
     * is not a self-granted quoted ability. The "Damage N -- " gate the wrapper may carry is not
     * returned: callers reading a {@link FieldAbility} already have it in
     * {@link FieldAbility#damageThreshold()}, which {@link #parseFieldAbilities} stripped for them.
     *
     * <p>{@link #parseFieldPowerGrants} does its own matching rather than calling this, because it
     * works from raw {@code [[br]]} segments and so needs the gate as well.
     */
    static String selfGrantedFieldAbility(String effectText, String cardName) {
        if (effectText == null || cardName == null) return null;
        Matcher m = SELF_GAINS_QUOTED_FIELD_ABILITY.matcher(effectText.trim());
        if (!m.matches()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(cardName)) return null;
        return m.group("inner").trim();
    }

    /**
     * Matches "The Card Name X you control gains +N power [and Trait(s)]."
     * Groups: {@code cardname}, {@code power}, {@code traitstext} (optional).
     */
    private static final Pattern FIELD_GRANT_CARD_NAME_PATTERN = Pattern.compile(
        "(?i)^The\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+you\\s+control\\s+gains?\\s+" +
        "\\+(?<power>\\d+)\\s+power" +
        "(?:\\s+and\\s+(?<traitstext>Haste|Brave|First\\s+Strike|Back\\s+Attack" +
        "(?:\\s+and\\s+(?:Haste|Brave|First\\s+Strike|Back\\s+Attack))*))?[.!]?$"
    );

    /**
     * Matches "The Card Name X and Card Name Y you control gain +N power."
     * Groups: {@code cardname1}, {@code cardname2}, {@code power}.
     */
    private static final Pattern FIELD_GRANT_DUAL_CARD_NAME_PATTERN = Pattern.compile(
        "(?i)^The\\s+Card\\s+Name\\s+(?<cardname1>.+?)\\s+and\\s+Card\\s+Name\\s+(?<cardname2>.+?)\\s+you\\s+control\\s+gains?\\s+\\+(?<power>\\d+)\\s+power[.!]?$"
    );

    /**
     * Matches "The Card Name X you control gains Trait [and Trait] during your turn."
     * Groups: {@code cardname}, {@code traitstext}.
     */
    private static final Pattern FIELD_GRANT_CARD_NAME_TRAIT_YOUR_TURN = Pattern.compile(
        "(?i)^The\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+you\\s+control\\s+gains?\\s+" +
        "(?<traitstext>Haste|Brave|First\\s+Strike|Back\\s+Attack" +
        "(?:\\s+and\\s+(?:Haste|Brave|First\\s+Strike|Back\\s+Attack))*)" +
        "\\s+during\\s+your\\s+turn[.!]?$"
    );

    /**
     * Matches "The Card Name X you control gains Trait [and Trait]." (unconditional, always-on).
     * Groups: {@code cardname}, {@code traitstext}.
     */
    private static final Pattern FIELD_GRANT_CARD_NAME_TRAIT_ALWAYS = Pattern.compile(
        "(?i)^(?:The\\s+)?Card\\s+Name\\s+(?<cardname>.+?)\\s+you\\s+control\\s+gains?\\s+" +
        "(?<traitstext>Haste|Brave|First\\s+Strike|Back\\s+Attack" +
        "(?:\\s+(?:and\\s+)?(?:Haste|Brave|First\\s+Strike|Back\\s+Attack))*)" +
        "[.!]?$"
    );

    /**
     * Matches "The Card Name X [Forward] you control gains Trait[s] and "[quoted permission]"."
     * — Prompto 27-068R, the only corpus printing that hands a <em>named</em> Forward a keyword
     * and a quoted rules permission in the same sentence.
     *
     * <p>Its own pattern rather than a widening of {@link #FIELD_GRANT_CARD_NAME_TRAIT_ALWAYS},
     * which anchors its trait list to the end of the sentence: letting that one run on would
     * grant the keyword and silently drop the quotation, the half-an-ability failure
     * {@link #parseSelfGainsQuotedGrant} declines for the same reason. Both halves are read —
     * the traits by {@link #parseFieldPowerGrants}, the quotation by
     * {@link #parseNamedMaxAttacksGrant} — so the sentence is only claimed where it is honoured.
     *
     * <p>Groups: {@code cardname}, {@code traitstext}, {@code quoted}.
     */
    private static final Pattern FIELD_GRANT_CARD_NAME_TRAIT_AND_QUOTED = Pattern.compile(
        "(?i)^(?:The\\s+)?Card\\s+Name\\s+(?<cardname>.+?)" +
        "(?:\\s+(?:Forwards?|Backups?|Monsters?|Characters?))?\\s+you\\s+control\\s+gains?\\s+" +
        "(?<traitstext>(?:Haste|Brave|First\\s+Strike|Back\\s+Attack)" +
        "(?:\\s*(?:,|and)\\s*(?:Haste|Brave|First\\s+Strike|Back\\s+Attack))*)" +
        "\\s+and\\s+\"(?<quoted>[^\"]+)\"[.!]?$"
    );

    /**
     * The multi-attack permission a {@link #FIELD_GRANT_CARD_NAME_TRAIT_AND_QUOTED} sentence hands
     * the card it names, or {@code null} when {@code effectText} is not that sentence or its
     * quotation is not a permission this engine can honour.
     *
     * @param cardName    the name the grant targets ("Noctis")
     * @param maxAttacks  how many attacks per turn the quotation allows; always &gt; 1
     */
    record NamedMaxAttacksGrant(String cardName, int maxAttacks) {}

    /**
     * Parses the quoted half of a named-Forward grant into a {@link NamedMaxAttacksGrant}, or
     * {@code null} when the text is not one.
     *
     * <p>The quotation's subject is written from the grantee's point of view ("This Forward can
     * attack twice per turn."), so it is matched against the demonstrative wordings as well as
     * against the granted name — a printing that spelled the name out reads the same way here.
     */
    static NamedMaxAttacksGrant parseNamedMaxAttacksGrant(String effectText) {
        if (effectText == null) return null;
        Matcher m = FIELD_GRANT_CARD_NAME_TRAIT_AND_QUOTED.matcher(effectText.trim());
        if (!m.matches()) return null;
        String cardName = m.group("cardname").trim();
        Matcher at = FIELD_CAN_ATTACK_TWICE.matcher(m.group("quoted").trim());
        if (!at.matches()) return null;
        String subject = at.group("cardname").trim();
        if (!subject.equalsIgnoreCase(cardName)
                && !subject.matches("(?i)This\\s+(?:Forward|Character|Monster|Backup)")) return null;
        String count = at.group("count");
        return new NamedMaxAttacksGrant(cardName, count != null ? Integer.parseInt(count) : 2);
    }

    /**
     * Matches a cost-filtered same-side grant with optional power and/or traits:
     * "The [type] of cost N you control gain[s] [+P power [and]] [Trait...]"
     * Groups: {@code targets}, {@code cost}, {@code power} (optional), {@code traitstext} (optional).
     */
    private static final Pattern FIELD_GRANT_COST_FILTER_PATTERN = Pattern.compile(
        "(?i)^The\\s+(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+" +
        "of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?\\s+" +
        "(?:other\\s+than\\s+(?<except>[A-Z][A-Za-z''\\-]+(?:\\s+[A-Za-z''\\-]+)*)\\s+)?" +
        "you\\s+control\\s+gains?\\s+" +
        "(?:\\+(?<power>\\d+)\\s+power(?:\\s+and\\s+)?)?" +
        "(?<traitstext>.+?)?[.!]?$"
    );

    /**
     * Matches the opposing-side debuff "The Forwards?|Backups?|Monsters?|Characters? opponent
     * controls lose N power." Stored as a {@link FieldPowerGrant} with {@code affectsOpponent=true}
     * and negative {@code powerBonus}.
     */
    private static final Pattern FIELD_OPPONENT_DEBUFF_PATTERN = Pattern.compile(
        "(?i)^The\\s+(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+" +
        "(?:your\\s+)?opponent\\s+controls?\\s+loses?\\s+(?<power>\\d+)\\s+power[.!]?$"
    );

    /**
     * Matches the controller-less debuff "The [type] of an Element other than [X] lose N power." —
     * Tchakka 18-092C. Naming no controller is the whole point: it reaches both boards, so it is
     * stored as a pair of {@link FieldPowerGrant}s, one per side.
     */
    private static final Pattern FIELD_ELEMENT_EXCLUDED_DEBUFF_PATTERN = Pattern.compile(
        "(?i)^The\\s+(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+" +
        "of\\s+an\\s+Element\\s+other\\s+than\\s+(?<element>" + ELEMENT_KEYWORD + ")\\s+" +
        "loses?\\s+(?<power>\\d+)\\s+power[.!]?$"
    );

    /**
     * Matches "The [type] forming a party with [CardName] gain [+N power] [and] [Trait…]." —
     * Chocobo 2-060C. The grant is live only while a party containing the printing card is declared,
     * which {@code MainWindow} checks; the card name is verified against the carrier by the caller,
     * since a party clause naming someone else is a different card's business.
     */
    private static final Pattern FIELD_PARTY_WITH_GRANT_PATTERN = Pattern.compile(
        "(?i)^The\\s+(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+" +
        "forming\\s+a\\s+party\\s+with\\s+(?<cardname>.+?)\\s+gains?\\s+" +
        "(?:\\+(?<power>\\d+)\\s+power(?:\\s+and\\s+)?)?" +
        "(?<traitstext>.+?)?[.!]?$"
    );

    /**
     * Matches "The [type] forming a party you control gain [+N power] [and] [Trait…]." —
     * Gippal 12-058C. The unnamed sibling of {@link #FIELD_PARTY_WITH_GRANT_PATTERN}: that one
     * names the partymate the grant flows from, this one names only whose party it has to be.
     *
     * <p>The two cannot claim each other's text — one requires the literal "with", the other the
     * literal "you control" in the same slot — so their order in the chain is free.
     */
    private static final Pattern FIELD_PARTY_ANY_GRANT_PATTERN = Pattern.compile(
        "(?i)^The\\s+(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+" +
        "forming\\s+a\\s+party\\s+you\\s+control\\s+gains?\\s+" +
        "(?:\\+(?<power>\\d+)\\s+power(?:\\s+and\\s+)?)?" +
        "(?<traitstext>.+?)?[.!]?$"
    );

    /**
     * Matches "If there are N or more cards in your Break Zone, the Card Name X [type] and the
     * Job Y [type] you control gain[s] +P power [and traits]."
     * Groups: {@code bzcount}, {@code cardname}, {@code type1}, {@code job}, {@code type2},
     * {@code power} (optional), {@code traitstext} (optional).
     */
    /**
     * Matches "If you have N or more Job X Forwards in your Break Zone, [CardName] gains +P power."
     * Groups: {@code bzcount}, {@code job}, {@code type}, {@code selfname}, {@code power}.
     */
    private static final Pattern FIELD_GRANT_BZ_JOB_SELF_PATTERN = Pattern.compile(
        "(?i)^If\\s+you\\s+have\\s+(?<bzcount>\\d+)\\s+or\\s+more\\s+" +
        "Job\\s+(?<job>[A-Za-z][A-Za-z\\s''\\-]*)\\s+(?<type>Forwards?|Backups?|Monsters?)\\s+" +
        "in\\s+your\\s+Break\\s+Zone,?\\s+" +
        "(?<selfname>[A-Za-z][A-Za-z\\s''\\-]*)\\s+gains?\\s+\\+(?<power>\\d+)\\s+power[.!]?\\s*$"
    );

    /**
     * Matches "If you have a Card Name X in your Break Zone, X gains +N power [and Trait]."
     * Groups: {@code bzname}, {@code selfname}, {@code power}, {@code traitstext} (optional).
     */
    private static final Pattern FIELD_GRANT_BZ_CARD_NAME_SELF_PATTERN = Pattern.compile(
        "(?i)^If\\s+you\\s+have\\s+a\\s+Card\\s+Name\\s+(?<bzname>[A-Za-z][A-Za-z\\s''\\-]*)\\s+" +
        "in\\s+your\\s+Break\\s+Zone,?\\s+" +
        "(?<selfname>[A-Za-z][A-Za-z\\s''\\-]*)\\s+gains?\\s+\\+(?<power>\\d+)\\s+power" +
        "(?:\\s+and\\s+(?<traitstext>.+?))?[.!]?\\s*$"
    );

    /**
     * Matches "If there are N or more different Elements among Forwards/Characters you control,
     * the Forwards/Characters you control gain +P power."
     * Groups: {@code min}, {@code ctype} (condition type), {@code gttype} (grant target type), {@code power}.
     */
    private static final Pattern IF_DIFF_ELEMENTS_FIELD_GRANT = Pattern.compile(
        "(?i)^If\\s+there\\s+are\\s+(?<min>\\d+)\\s+or\\s+more\\s+different\\s+Elements?\\s+among\\s+" +
        "(?<ctype>Forwards?|Backups?|Characters?|Monsters?)\\s+you\\s+control[,.]?\\s+" +
        "the\\s+(?<gttype>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?|Monsters?)\\s+" +
        "you\\s+control\\s+gains?\\s+\\+(?<power>\\d+)\\s+power[.!]?$"
    );

    /**
     * "The power of Forwards you control cannot be decreased by your opponent's Summons or
     * abilities." — Shelke 16-029R.
     *
     * <p>Stored as a passive grant of {@link Trait#POWER_CANNOT_BE_DECREASED_BY_OPP} to the
     * controller's Forwards, because that trait is what every power-decrease path already consults,
     * and those paths already scope the block to a decrease coming from the Forward's opponent.
     * Asura 23-039R hands the same trait to a Forward through the quoted per-card wording, so the
     * two printings meet at one enforcement point.
     *
     * <p>The boost-suppressing twin printed beside this on Shelke
     * ({@link AutoAbilityTriggers#FA_OPP_FORWARD_SELF_BOOST_SUPPRESSED}) takes the opposite route —
     * a scan of the opposing field — because it restricts who may boost rather than marking the
     * Forwards, and so has no per-card trait to hang on.
     */
    private static final Pattern FIELD_POWER_CANNOT_BE_DECREASED = Pattern.compile(
        "(?i)^The\\s+power\\s+of\\s+Forwards?\\s+you\\s+control\\s+cannot\\s+be\\s+decreased\\s+by\\s+" +
        "your\\s+opponent(?:'s|s')\\s+Summons?\\s+or\\s+abilit(?:y|ies)[.!]?$"
    );

    /**
     * "The power of the Job Pirate Forwards and Card Name Viking Forwards other than Faris you
     * control becomes 8000." — Faris 21-114L.
     *
     * <p>A base-power replacement, not a bonus: the value lands in
     * {@link FieldPowerGrant#basePowerSet}, which {@code MainWindow} substitutes for the printed
     * power before boosts and reductions are applied, so those still stack on top of it.
     *
     * <p>Both filter branches are optional and either may carry its own "other than" exclusion, but
     * the printing that motivates this puts one exclusion after the pair; {@code except} is captured
     * once and applied to both grants for that reason. The two branches are ORed by emitting one
     * grant each — {@link FieldPowerGrant#appliesToCard} ANDs job and card-name filters.
     */
    private static final Pattern FIELD_BASE_POWER_BECOMES = Pattern.compile(
        "(?i)^The\\s+power\\s+of\\s+(?:the\\s+)?" +
        "(?:Job\\s+(?<job>[A-Za-z][A-Za-z\\s''\\-]*?)\\s+(?:Forwards?)\\s+and\\s+)?" +
        "Card\\s+Name\\s+(?<cardname>[A-Za-z][A-Za-z\\s''\\-]*?)\\s+Forwards?\\s+" +
        "(?:other\\s+than\\s+(?<except>[A-Z][A-Za-z''\\-]+(?:\\s+[A-Za-z''\\-]+)*)\\s+)?" +
        "you\\s+control\\s+becomes\\s+(?<power>\\d+)[.!]?$"
    );

    /**
     * "The Job Monk Forwards and Card Name Monk Forwards you control cannot become dull by your
     * opponent's Summons or abilities." — Maat 6-078R.
     *
     * <p>Stored as a passive grant of {@link Trait#CANNOT_BE_DULLED_BY_OPP}, which is the trait
     * every dulling path already consults ({@code GameContextImpl.dullP1Forward} and its twin), so
     * this printing meets the per-card quoted wording at one enforcement point rather than adding a
     * second notion of dull immunity.
     *
     * <p>The two filter branches are ORed by emitting one grant each, exactly as Faris 21-114L's
     * base-power grant is — {@link FieldPowerGrant#appliesToCard} ANDs job and card-name filters,
     * so a single grant carrying both would cover only the cards that satisfy the two at once.
     * Overlap between the branches is harmless here for the same reason it is there: the grants
     * carry no power, and a trait added twice is the same set.
     * Groups: {@code job} (optional), {@code cardname}.
     */
    private static final Pattern FIELD_CANNOT_BECOME_DULL_BY_OPP = Pattern.compile(
        "(?i)^The\\s+(?:Job\\s+(?<job>[A-Za-z][A-Za-z\\s''\\-]*?)\\s+Forwards?\\s+and\\s+)?" +
        "Card\\s+Name\\s+(?<cardname>[A-Za-z][A-Za-z\\s''\\-]*?)\\s+Forwards?\\s+you\\s+control\\s+" +
        "cannot\\s+become\\s+dull\\s+by\\s+your\\s+opponent's\\s+" +
        "Summons?(?:\\s+or\\s+abilit(?:y|ies))?[.!]?$"
    );

    /**
     * "The Job Chocobo Forwards and Card Name Chocobo Forwards you control gain +3000 power."
     * — Billy 29-048C.
     *
     * <p>The generic {@link #FIELD_GRANT_PATTERN} claimed this text and read the whole phrase
     * "Chocobo Forwards and Card Name Chocobo" as one job name, so the grant matched no card at
     * all. Needed as its own pattern for the same reason Faris 21-114L's base-power twin and Maat
     * 6-078R's dull-immunity twin are: the union of a job and a card name has no place in the
     * generic shape.
     *
     * <p>Unlike those two, this one emits a <em>single</em> grant carrying both filters rather than
     * one grant per branch. A power bonus is additive, so two grants would stack to +6000 on any
     * card that satisfied both — which "Bartz has all the jobs" (1-081R) and a runtime-granted Job
     * make reachable. {@link FieldPowerGrant#appliesToCard} reads two filled filters as
     * alternatives, so one grant covers the union exactly once.
     *
     * <p>Groups: {@code job}, {@code cardname}, {@code type} (the noun both branches share),
     * {@code bonus}.
     */
    private static final Pattern FIELD_GRANT_JOB_AND_CARD_NAME_PATTERN = Pattern.compile(
        "(?i)^The\\s+Job\\s+(?<job>[A-Za-z][A-Za-z\\s''\\-]*?)\\s+" +
        "(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+and\\s+" +
        "Card\\s+Name\\s+(?<cardname>[A-Za-z][A-Za-z\\s''\\-]*?)\\s+" +
        "(?:Forwards?|Backups?|Monsters?|Characters?)\\s+" +
        "(?:other\\s+than\\s+(?<except>[A-Z][A-Za-z''\\-]+(?:\\s+[A-Za-z''\\-]+)*)\\s+)?" +
        "you\\s+control\\s+gains?\\s+\\+(?<bonus>\\d+)\\s+power[.!]?$"
    );

    /**
     * "The Forwards you control gain +N power for every M cards with EX Burst in your Damage Zone."
     * Groups: {@code bonus}, {@code groupsize}.
     */
    private static final Pattern FIELD_EX_BURST_DMG_SCALING_GRANT = Pattern.compile(
        "(?i)^The\\s+Forwards?\\s+you\\s+control\\s+gain\\s+\\+(?<bonus>\\d+)\\s+power\\s+for\\s+every\\s+(?<groupsize>\\d+)\\s+cards?\\s+with\\s+EX\\s+Burst\\s+in\\s+your\\s+Damage\\s+Zone\\.?\\s*$"
    );

    private static final Pattern FIELD_GRANT_BZ_COND_CN_AND_JOB_PATTERN = Pattern.compile(
        "(?i)^If\\s+there\\s+are\\s+(?<bzcount>\\d+)\\s+or\\s+more\\s+cards?\\s+in\\s+your\\s+Break\\s+Zone,?\\s+" +
        "the\\s+Card\\s+Name\\s+(?<cardname>[A-Za-z][A-Za-z\\s''\\-]*)\\s+(?<type1>Forwards?|Characters?|Backups?|Monsters?)\\s+" +
        "and\\s+the\\s+Job\\s+(?<job>[A-Za-z][A-Za-z\\s''\\-]*)\\s+(?<type2>Forwards?|Characters?|Backups?|Monsters?)\\s+" +
        "you\\s+control\\s+gains?\\s+" +
        "(?:\\+(?<power>\\d+)\\s+power(?:\\s+and\\s+)?)?" +
        "(?<traitstext>.+?)?[.!]?$"
    );

    /**
     * Matches a counter-conditioned grant to Forwards you control:
     * "Each Forward you control with a [name] Counter on it gains [grant]."
     * Groups: {@code counter} — counter type name; {@code grant} — the granted power/ability text.
     */
    static final Pattern COUNTER_GRANT_PATTERN = Pattern.compile(
        "(?i)^Each\\s+Forward\\s+you\\s+control\\s+with\\s+an?\\s+(?<counter>.+?)\\s+Counter\\s+on\\s+it\\s+gains\\s+(?<grant>.+)$"
    );

    /**
     * The threshold twin of {@link #COUNTER_GRANT_PATTERN}:
     * "The Forwards with N or more [name] Counters on them you control gain [grant]."
     * — Palom 23-018R (a power grant) and Porom 23-110R (a quoted ability).
     * Groups: {@code count} — counters required; {@code counter}; {@code grant}.
     *
     * <p>Kept apart rather than folded in as an optional clause: the two word the subject
     * differently ("Each Forward … with a" against "The Forwards with … on them"), and an optional
     * count group on the other pattern would let a threshold printing match at one counter if the
     * clause ever failed to bind.
     */
    static final Pattern COUNTER_THRESHOLD_GRANT_PATTERN = Pattern.compile(
        "(?i)^The\\s+Forwards?\\s+with\\s+(?<count>\\d+)\\s+or\\s+more\\s+(?<counter>.+?)\\s+Counters?\\s+" +
        "on\\s+them\\s+you\\s+control\\s+gains?\\s+(?<grant>.+)$"
    );

    /** Captures the "+N power" bonus within a {@link #COUNTER_GRANT_PATTERN} grant clause. */
    private static final Pattern COUNTER_GRANT_POWER = Pattern.compile("(?i)\\+(?<power>\\d+)\\s+power");

    /**
     * "The Forwards opponent controls lose N power for each [X] Counter on them." — Gargas 17-045R.
     * Groups: {@code power}, {@code counter}.
     *
     * <p>Kept apart from {@link #COUNTER_GRANT_PATTERN} rather than widened into it: this one scales
     * with the counter count instead of triggering at one or more, and reaches across the field
     * instead of applying to its controller's own Forwards. Both differences are carried on the
     * resulting {@link CounterGrant}.
     */
    static final Pattern COUNTER_SCALED_OPP_DEBUFF = Pattern.compile(
        "(?i)^The\\s+Forwards?\\s+(?:your\\s+)?opponent\\s+controls?\\s+loses?\\s+(?<power>\\d+)\\s+power\\s+" +
        "for\\s+each\\s+(?<counter>.+?)\\s+Counter\\s+on\\s+them[.!]?$"
    );

    /**
     * The self-named counter grant: "If a [X] Counter is placed on [Name], [Name] gains \"[ability]\"."
     * — Number 24 20-036H. Groups: {@code counter}, {@code subject} (the gaining card), {@code grant}.
     *
     * <p>Worded as a trigger but read as a standing grant, because that is what it does: the counter
     * is what the ability is conditioned on, so removing the last one takes the ability away again —
     * which is exactly what Number 24's granted ability does to itself. Treating the placement as a
     * one-shot event would leave the ability behind after the counter it paid for was spent.
     *
     * <p>Both name captures are checked against the carrier by {@link #counterGrants()}; without that
     * the lazy groups would happily read a grant one card makes to another as a self-grant.
     */
    static final Pattern SELF_COUNTER_PLACED_GAINS_PATTERN = Pattern.compile(
        "(?i)^If\\s+an?\\s+(?<counter>.+?)\\s+Counter\\s+is\\s+placed\\s+on\\s+(?<placed>.+?),\\s+" +
        "(?<subject>.+?)\\s+gains\\s+(?<grant>\".+\")[.!]?$",
        Pattern.DOTALL
    );

    /**
     * Parses counter-conditioned passive power grants from this card's field abilities — the
     * same-side "Each Forward you control with a [X] Counter on it gains …" form (a power bonus or
     * a quoted ability) and the opposing-side per-counter debuff.
     * The returned list is immutable (empty when the card has no such grant).
     */
    public List<CounterGrant> counterGrants() {
        List<CounterGrant> out = null;
        for (FieldAbility fa : fieldAbilities()) {
            Matcher dm = COUNTER_SCALED_OPP_DEBUFF.matcher(fa.effectText());
            if (dm.matches()) {
                if (out == null) out = new ArrayList<>();
                out.add(new CounterGrant(dm.group("counter").trim(),
                        -Integer.parseInt(dm.group("power")), null, true, true, false));
                continue;
            }
            Matcher sm = SELF_COUNTER_PLACED_GAINS_PATTERN.matcher(fa.effectText());
            if (sm.matches()
                    && sm.group("placed").trim().equalsIgnoreCase(name())
                    && sm.group("subject").trim().equalsIgnoreCase(name())) {
                String ability = unquoteGrant(sm.group("grant").trim());
                if (ability != null) {
                    if (out == null) out = new ArrayList<>();
                    out.add(new CounterGrant(sm.group("counter").trim(), 0, ability, false, false, true));
                }
                continue;
            }
            CounterGrant threshold = parseCounterThresholdGrant(fa.effectText());
            if (threshold != null) {
                if (out == null) out = new ArrayList<>();
                out.add(threshold);
                continue;
            }
            if (COUNTER_THRESHOLD_GRANT_PATTERN.matcher(fa.effectText()).matches()) continue;
            Matcher m = COUNTER_GRANT_PATTERN.matcher(fa.effectText());
            if (!m.matches()) continue;
            CounterGrant cg = counterGrantFor(m.group("counter"), m.group("grant"), 1);
            if (cg == null) continue;
            if (out == null) out = new ArrayList<>();
            out.add(cg);
        }
        return out == null ? List.of() : List.copyOf(out);
    }

    /**
     * The {@link CounterGrant} {@code seg} describes when it is a threshold counter grant
     * ({@link #COUNTER_THRESHOLD_GRANT_PATTERN}), or {@code null} when it is not one or names a
     * grant clause this cannot build. {@link #counterGrants()} reads it through this, so a caller
     * asking whether the engine acts on a text gets the same answer the engine gives.
     */
    static CounterGrant parseCounterThresholdGrant(String seg) {
        if (seg == null || seg.isBlank()) return null;
        Matcher m = COUNTER_THRESHOLD_GRANT_PATTERN.matcher(seg);
        if (!m.matches()) return null;
        return counterGrantFor(m.group("counter"), m.group("grant"), Integer.parseInt(m.group("count")));
    }

    /**
     * Builds the same-side {@link CounterGrant} a "… gains [grant]" clause describes — a quoted
     * ability or a "+N power" bonus — or {@code null} when the clause is neither. Shared by the
     * at-least-one and threshold counter-grant patterns, which differ only in {@code minCount}.
     */
    private static CounterGrant counterGrantFor(String counterName, String grantClause, int minCount) {
        String counter = counterName.trim();
        String grant   = grantClause.trim();
        if (grant.startsWith("\"")) {
            String ability = unquoteGrant(grant);
            return ability == null ? null
                    : new CounterGrant(counter, 0, ability, false, false, false, minCount);
        }
        Matcher pm = COUNTER_GRANT_POWER.matcher(grant);
        if (!pm.find()) return null;
        return new CounterGrant(counter, Integer.parseInt(pm.group("power")), null,
                false, false, false, minCount);
    }

    /**
     * Strips the surrounding quotes from a counter grant's quoted ability clause, or returns
     * {@code null} when {@code grant} is not a non-empty quoted ability. The closing quote is taken
     * as the last one in the clause, not the next, so an ability that itself quotes something keeps
     * its inner text.
     */
    private static String unquoteGrant(String grant) {
        if (!grant.startsWith("\"")) return null;
        int end = grant.lastIndexOf('"');
        if (end <= 0) return null;
        String ability = grant.substring(1, end).trim();
        return ability.isEmpty() ? null : ability;
    }

    public static List<FieldPowerGrant> parseFieldPowerGrants(String textEn, String cardType) {
        return parseFieldPowerGrants(textEn, cardType, null);
    }

    /**
     * As {@link #parseFieldPowerGrants(String, String)}, but able to verify that a card handing
     * itself a quoted field ability ("Damage 6 -- Aranea gains "…"") really is naming itself.
     *
     * @param cardName the carrier's name, or {@code null} to accept any name-shaped subject —
     *     which is what the two-argument form passes, since most of its callers parse a text with
     *     no card attached
     */
    public static List<FieldPowerGrant> parseFieldPowerGrants(String textEn, String cardType,
            String cardName) {
        if (textEn == null || textEn.isBlank()) return List.of();
        if ("Summon".equalsIgnoreCase(cardType)) return List.of();

        List<FieldPowerGrant> result = new ArrayList<>();
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;
            // Normalize bracket filter notation to plain form so existing patterns match.
            seg = seg.replaceAll("(?i)\\[Job\\s*\\(([^)]+)\\)\\]", "Job $1");
            seg = seg.replaceAll("(?i)\\[Category\\s*\\(([^)]+)\\)\\]", "Category $1");
            seg = seg.replaceAll("(?i)\\[Card\\s+Name\\s*\\(([^)]+)\\)\\]", "Card Name $1");
            // "All the …" is the same grant as "The …", printed by two cards (Golbez 19-077L,
            // Cecil 2-129L). Normalised once here rather than in each of the dozen patterns below,
            // every one of which is anchored on "^The ".
            seg = ALL_THE_GRANT_PREFIX.matcher(seg).replaceFirst("The ");
            // A grant that carries a damage rider is two effects in one sentence; the power half
            // is this parser's, and the rider is read separately off the same text.
            String[] rider = splitGrantWithDamageRider(seg);
            if (rider != null) seg = rider[0];

            // "Damage N -- [Self] gains "<field ability>"" — the quoted half is an ordinary passive
            // grant, so it is re-entered on its own and the wrapper contributes only its gate.
            // Checked ahead of everything else: every pattern below is anchored on "^The ", so none
            // of them can see past a segment that opens with the carrier's name.
            Matcher selfQuotedM = SELF_GAINS_QUOTED_FIELD_ABILITY.matcher(seg);
            if (selfQuotedM.matches()
                    && (cardName == null || selfQuotedM.group("subject").trim().equalsIgnoreCase(cardName))) {
                String thresholdStr = selfQuotedM.group("threshold");
                int threshold = thresholdStr != null ? Integer.parseInt(thresholdStr) : 0;
                for (FieldPowerGrant inner : parseFieldPowerGrants(selfQuotedM.group("inner"), cardType, cardName))
                    result.add(inner.withMinDamageThreshold(threshold));
                continue;
            }

            if (FIELD_POWER_CANNOT_BE_DECREASED.matcher(seg).matches()) {
                result.add(FieldPowerGrant.sameSideFiltered(true, false, false, null, 0,
                        EnumSet.of(Trait.POWER_CANNOT_BE_DECREASED_BY_OPP), null,
                        EnumSet.noneOf(Trait.class)));
                continue;
            }

            Matcher basePowM = FIELD_BASE_POWER_BECOMES.matcher(seg);
            if (basePowM.matches()) {
                int    newBase = Integer.parseInt(basePowM.group("power"));
                String except  = basePowM.group("except");
                String job     = basePowM.group("job");
                String exceptName = except != null ? except.trim() : null;
                if (job != null)
                    result.add(FieldPowerGrant.sameSideBasePower(job.trim(), null, exceptName, newBase));
                result.add(FieldPowerGrant.sameSideBasePower(null, basePowM.group("cardname").trim(),
                        exceptName, newBase));
                continue;
            }

            Matcher dullImmM = FIELD_CANNOT_BECOME_DULL_BY_OPP.matcher(seg);
            if (dullImmM.matches()) {
                EnumSet<Trait> immune = EnumSet.of(Trait.CANNOT_BE_DULLED_BY_OPP);
                String job = dullImmM.group("job");
                if (job != null)
                    result.add(new FieldPowerGrant(job.trim(), null, true, false, false,
                            null, 0, immune, false));
                result.add(new FieldPowerGrant(null, null, true, false, false,
                        null, 0, immune, false, -1, null, null,
                        dullImmM.group("cardname").trim(), 0, 0, null, null, false, false, 0, 0, 1, 0, 0,
                        EnumSet.noneOf(Trait.class), false, 0));
                continue;
            }

            Matcher jobAndCnM = FIELD_GRANT_JOB_AND_CARD_NAME_PATTERN.matcher(seg);
            if (jobAndCnM.matches()) {
                String type   = jobAndCnM.group("type").toLowerCase(Locale.ROOT);
                boolean isChar = type.startsWith("character");
                String except = jobAndCnM.group("except");
                result.add(new FieldPowerGrant(
                        jobAndCnM.group("job").trim(), null,
                        isChar || type.startsWith("forward"),
                        isChar || type.startsWith("backup"),
                        isChar || type.startsWith("monster"),
                        except != null ? except.trim() : null,
                        Integer.parseInt(jobAndCnM.group("bonus")),
                        EnumSet.noneOf(Trait.class), false, -1, null, null,
                        jobAndCnM.group("cardname").trim(), 0, 0, null, null, false, false, 0, 0, 1, 0, 0,
                        EnumSet.noneOf(Trait.class), false, 0));
                continue;
            }

            Matcher exBurstDmgM = FIELD_EX_BURST_DMG_SCALING_GRANT.matcher(seg);
            if (exBurstDmgM.matches()) {
                int bonus     = Integer.parseInt(exBurstDmgM.group("bonus"));
                int groupSize = Integer.parseInt(exBurstDmgM.group("groupsize"));
                result.add(new FieldPowerGrant(null, null, true, false, false,
                        null, 0, EnumSet.noneOf(Trait.class), false, -1, null, null, null,
                        0, 0, null, false, false, 0, bonus, groupSize));
                continue;
            }

            Matcher diffElemM = IF_DIFF_ELEMENTS_FIELD_GRANT.matcher(seg);
            if (diffElemM.matches()) {
                int min = Integer.parseInt(diffElemM.group("min"));
                int[] incl = parseFieldGrantTargetFlags(diffElemM.group("gttype"));
                result.add(new FieldPowerGrant(null, null, incl[0] != 0, incl[1] != 0, incl[2] != 0,
                        null, Integer.parseInt(diffElemM.group("power")),
                        EnumSet.noneOf(Trait.class), false, -1, null, null, null,
                        0, 0, null, false, false, min));
                continue;
            }

            Matcher bzCnJobM = FIELD_GRANT_BZ_COND_CN_AND_JOB_PATTERN.matcher(seg);
            if (bzCnJobM.matches()) {
                int minBzSize = Integer.parseInt(bzCnJobM.group("bzcount"));
                String bzCardName = bzCnJobM.group("cardname").trim();
                String job      = bzCnJobM.group("job").trim();
                String type1    = bzCnJobM.group("type1");
                String type2    = bzCnJobM.group("type2");
                int[]  incl1   = parseFieldGrantTargetFlags(type1);
                int[]  incl2   = parseFieldGrantTargetFlags(type2);
                String powerStr = bzCnJobM.group("power");
                int power = powerStr != null ? Integer.parseInt(powerStr) : 0;
                String traitsText = bzCnJobM.group("traitstext");
                EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
                if (traitsText != null) {
                    if (ICB_EFFECT_HASTE.matcher(traitsText).find())        traits.add(Trait.HASTE);
                    if (ICB_EFFECT_BRAVE.matcher(traitsText).find())        traits.add(Trait.BRAVE);
                    if (ICB_EFFECT_FIRST_STRIKE.matcher(traitsText).find()) traits.add(Trait.FIRST_STRIKE);
                    if (ICB_EFFECT_BACK_ATTACK.matcher(traitsText).find())  traits.add(Trait.BACK_ATTACK);
                }
                result.add(new FieldPowerGrant(null, null, incl1[0] != 0, incl1[1] != 0, incl1[2] != 0,
                        null, power, traits, false, -1, null, null, bzCardName, minBzSize, 0, null, false, false));
                result.add(new FieldPowerGrant(job, null, incl2[0] != 0, incl2[1] != 0, incl2[2] != 0,
                        null, power, traits, false, -1, null, null, null, minBzSize, 0, null, false, false));
                continue;
            }

            Matcher bzJobSelfM = FIELD_GRANT_BZ_JOB_SELF_PATTERN.matcher(seg);
            if (bzJobSelfM.matches()) {
                int minBzFC   = Integer.parseInt(bzJobSelfM.group("bzcount"));
                String bzJob  = bzJobSelfM.group("job").trim();
                String type   = bzJobSelfM.group("type");
                boolean bzFwd = type != null && type.toLowerCase().startsWith("forward");
                String selfName = bzJobSelfM.group("selfname").trim();
                int power = Integer.parseInt(bzJobSelfM.group("power"));
                result.add(new FieldPowerGrant(null, null, true, false, false,
                        null, power, EnumSet.noneOf(Trait.class), false, -1, null, null,
                        selfName, 0, minBzFC, bzJob, bzFwd, false));
                continue;
            }

            Matcher bzCnSelfM = FIELD_GRANT_BZ_CARD_NAME_SELF_PATTERN.matcher(seg);
            if (bzCnSelfM.matches()) {
                String bzName     = bzCnSelfM.group("bzname").trim();
                String selfName   = bzCnSelfM.group("selfname").trim();
                int power         = Integer.parseInt(bzCnSelfM.group("power"));
                String traitsText = bzCnSelfM.group("traitstext");
                EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
                if (traitsText != null) {
                    if (ICB_EFFECT_HASTE.matcher(traitsText).find())        traits.add(Trait.HASTE);
                    if (ICB_EFFECT_BRAVE.matcher(traitsText).find())        traits.add(Trait.BRAVE);
                    if (ICB_EFFECT_FIRST_STRIKE.matcher(traitsText).find()) traits.add(Trait.FIRST_STRIKE);
                    if (ICB_EFFECT_BACK_ATTACK.matcher(traitsText).find())  traits.add(Trait.BACK_ATTACK);
                }
                result.add(new FieldPowerGrant(null, null, true, true, true,
                        null, power, traits, false, -1, null, null,
                        selfName, 0, 0, null, bzName, false, false, 0, 0, 1, 0, 0));
                continue;
            }

            Matcher dmgThreshM = FIELD_GRANT_DAMAGE_THRESHOLD_PATTERN.matcher(seg);
            if (dmgThreshM.matches()) {
                int threshold  = Integer.parseInt(dmgThreshM.group("threshold"));
                int basepower  = Integer.parseInt(dmgThreshM.group("basepower"));
                int boostpower = Integer.parseInt(dmgThreshM.group("boostpower"));
                String except  = dmgThreshM.group("except");
                int[] incl1    = parseFieldGrantTargetFlags(dmgThreshM.group("targets"));
                int[] incl2    = parseFieldGrantTargetFlags(dmgThreshM.group("targets2"));
                // Below-threshold grant: targets (possibly excluding a card), basepower, only when damage < threshold
                result.add(new FieldPowerGrant(null, null, incl1[0] != 0, incl1[1] != 0, incl1[2] != 0,
                        except != null ? except.trim() : null, basepower, EnumSet.noneOf(Trait.class),
                        false, -1, null, null, null, 0, 0, null, null, false, false, 0, 0, 1,
                        0, threshold));
                // At-or-above-threshold grant: targets2 (no exclusion), boostpower, only when damage >= threshold
                result.add(new FieldPowerGrant(null, null, incl2[0] != 0, incl2[1] != 0, incl2[2] != 0,
                        null, boostpower, EnumSet.noneOf(Trait.class),
                        false, -1, null, null, null, 0, 0, null, null, false, false, 0, 0, 1,
                        threshold, 0));
                continue;
            }

            Matcher bareM = FIELD_GRANT_BARE_PATTERN.matcher(seg);
            if (bareM.matches()) {
                int[] incl = parseFieldGrantTargetFlags(bareM.group("targets"));
                String bareExcept = bareM.group("except");
                String bareElem   = bareM.group("element");
                FieldPowerGrant bare = FieldPowerGrant.sameSideFiltered(incl[0] != 0, incl[1] != 0, incl[2] != 0,
                        bareExcept != null ? bareExcept.trim() : null,
                        Integer.parseInt(bareM.group("power")),
                        traitsNamedIn(bareM.group("traitstext")),
                        bareElem != null ? bareElem.trim() : null,
                        traitsNamedIn(bareM.group("withtrait")),
                        bareM.group("attacking") != null);
                String window = bareM.group("turnwindow");
                if (window != null)
                    bare = bare.withTurnWindow(window.toLowerCase(Locale.ROOT).contains("opponent"));
                result.add(bare);
                continue;
            }

            // Must follow FIELD_GRANT_BARE_PATTERN: the two describe the same sentence minus and
            // plus a power clause, and only the power-bearing one can tell them apart.
            Matcher bareTraitM = FIELD_GRANT_BARE_TRAIT_ONLY_PATTERN.matcher(seg);
            if (bareTraitM.matches()) {
                int[] incl = parseFieldGrantTargetFlags(bareTraitM.group("targets"));
                EnumSet<Trait> traits = traitsNamedIn(bareTraitM.group("traitstext"));
                if (!traits.isEmpty()) {
                    String bareExcept = bareTraitM.group("except");
                    String bareElem   = bareTraitM.group("element");
                    result.add(FieldPowerGrant.sameSideFiltered(incl[0] != 0, incl[1] != 0, incl[2] != 0,
                            bareExcept != null ? bareExcept.trim() : null, 0, traits,
                            bareElem != null ? bareElem.trim() : null,
                            traitsNamedIn(bareTraitM.group("withtrait"))));
                    continue;
                }
            }

            Matcher dualCnM = FIELD_GRANT_DUAL_CARD_NAME_PATTERN.matcher(seg);
            if (dualCnM.matches()) {
                int power = Integer.parseInt(dualCnM.group("power"));
                result.add(new FieldPowerGrant(null, null, true, true, true, null,
                        power, EnumSet.noneOf(Trait.class), false, -1, null, null,
                        dualCnM.group("cardname1").trim()));
                result.add(new FieldPowerGrant(null, null, true, true, true, null,
                        power, EnumSet.noneOf(Trait.class), false, -1, null, null,
                        dualCnM.group("cardname2").trim()));
                continue;
            }

            Matcher cnM = FIELD_GRANT_CARD_NAME_PATTERN.matcher(seg);
            if (cnM.matches()) {
                int power = Integer.parseInt(cnM.group("power"));
                String traitsText = cnM.group("traitstext");
                EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
                if (traitsText != null) {
                    if (ICB_EFFECT_HASTE.matcher(traitsText).find())        traits.add(Trait.HASTE);
                    if (ICB_EFFECT_BRAVE.matcher(traitsText).find())        traits.add(Trait.BRAVE);
                    if (ICB_EFFECT_FIRST_STRIKE.matcher(traitsText).find()) traits.add(Trait.FIRST_STRIKE);
                    if (ICB_EFFECT_BACK_ATTACK.matcher(traitsText).find())  traits.add(Trait.BACK_ATTACK);
                }
                result.add(new FieldPowerGrant(null, null, true, true, true, null,
                        power, traits, false, -1, null, null,
                        cnM.group("cardname").trim()));
                continue;
            }

            // Must precede the two trait-only Card Name readers: this sentence opens exactly as
            // they do and continues into a quotation, which they would drop.
            Matcher cnTraitQuotedM = FIELD_GRANT_CARD_NAME_TRAIT_AND_QUOTED.matcher(seg);
            if (cnTraitQuotedM.matches() && parseNamedMaxAttacksGrant(seg) != null) {
                String traitsText = cnTraitQuotedM.group("traitstext");
                EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
                if (ICB_EFFECT_HASTE.matcher(traitsText).find())        traits.add(Trait.HASTE);
                if (ICB_EFFECT_BRAVE.matcher(traitsText).find())        traits.add(Trait.BRAVE);
                if (ICB_EFFECT_FIRST_STRIKE.matcher(traitsText).find()) traits.add(Trait.FIRST_STRIKE);
                if (ICB_EFFECT_BACK_ATTACK.matcher(traitsText).find())  traits.add(Trait.BACK_ATTACK);
                result.add(new FieldPowerGrant(null, null, true, true, true, null, 0, traits,
                        false, -1, null, null, cnTraitQuotedM.group("cardname").trim(),
                        0, 0, null, false, false));
                continue;
            }

            Matcher cnTraitYourTurnM = FIELD_GRANT_CARD_NAME_TRAIT_YOUR_TURN.matcher(seg);
            if (cnTraitYourTurnM.matches()) {
                String traitsText = cnTraitYourTurnM.group("traitstext");
                EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
                if (ICB_EFFECT_HASTE.matcher(traitsText).find())        traits.add(Trait.HASTE);
                if (ICB_EFFECT_BRAVE.matcher(traitsText).find())        traits.add(Trait.BRAVE);
                if (ICB_EFFECT_FIRST_STRIKE.matcher(traitsText).find()) traits.add(Trait.FIRST_STRIKE);
                if (ICB_EFFECT_BACK_ATTACK.matcher(traitsText).find())  traits.add(Trait.BACK_ATTACK);
                result.add(new FieldPowerGrant(null, null, true, true, true, null, 0, traits,
                        false, -1, null, null, cnTraitYourTurnM.group("cardname").trim(),
                        0, 0, null, false, true));
                continue;
            }

            Matcher cnTraitAlwaysM = FIELD_GRANT_CARD_NAME_TRAIT_ALWAYS.matcher(seg);
            if (cnTraitAlwaysM.matches()) {
                String traitsText = cnTraitAlwaysM.group("traitstext");
                EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
                if (ICB_EFFECT_HASTE.matcher(traitsText).find())        traits.add(Trait.HASTE);
                if (ICB_EFFECT_BRAVE.matcher(traitsText).find())        traits.add(Trait.BRAVE);
                if (ICB_EFFECT_FIRST_STRIKE.matcher(traitsText).find()) traits.add(Trait.FIRST_STRIKE);
                if (ICB_EFFECT_BACK_ATTACK.matcher(traitsText).find())  traits.add(Trait.BACK_ATTACK);
                result.add(new FieldPowerGrant(null, null, true, true, true, null, 0, traits,
                        false, -1, null, null, cnTraitAlwaysM.group("cardname").trim(),
                        0, 0, null, false, false));
                continue;
            }

            // Controller-less element-excluded debuff — one grant per side, because the record
            // scopes to one side at a time and this sentence names neither.
            Matcher elemDebuffM = FIELD_ELEMENT_EXCLUDED_DEBUFF_PATTERN.matcher(seg);
            if (elemDebuffM.matches()) {
                int[] incl  = parseFieldGrantTargetFlags(elemDebuffM.group("targets"));
                int   power = -Integer.parseInt(elemDebuffM.group("power"));
                String elem = elemDebuffM.group("element");
                result.add(FieldPowerGrant.elementExcludedDebuff(
                        incl[0] != 0, incl[1] != 0, incl[2] != 0, power, elem, false));
                result.add(FieldPowerGrant.elementExcludedDebuff(
                        incl[0] != 0, incl[1] != 0, incl[2] != 0, power, elem, true));
                continue;
            }

            Matcher partyM = FIELD_PARTY_WITH_GRANT_PATTERN.matcher(seg);
            if (partyM.matches()) {
                int[]  incl      = parseFieldGrantTargetFlags(partyM.group("targets"));
                String powerStr  = partyM.group("power");
                int    power     = powerStr != null ? Integer.parseInt(powerStr) : 0;
                EnumSet<Trait> traits = traitsNamedIn(partyM.group("traitstext"));
                if (power != 0 || !traits.isEmpty())
                    result.add(FieldPowerGrant.partyWithGrant(incl[0] != 0, incl[1] != 0, incl[2] != 0,
                            power, traits, partyM.group("cardname").trim()));
                continue;
            }

            Matcher partyAnyM = FIELD_PARTY_ANY_GRANT_PATTERN.matcher(seg);
            if (partyAnyM.matches()) {
                int[]  incl     = parseFieldGrantTargetFlags(partyAnyM.group("targets"));
                String powerStr = partyAnyM.group("power");
                int    power    = powerStr != null ? Integer.parseInt(powerStr) : 0;
                EnumSet<Trait> traits = traitsNamedIn(partyAnyM.group("traitstext"));
                if (power != 0 || !traits.isEmpty())
                    result.add(FieldPowerGrant.partyAnyGrant(incl[0] != 0, incl[1] != 0, incl[2] != 0,
                            power, traits));
                continue;
            }

            Matcher debuffM = FIELD_OPPONENT_DEBUFF_PATTERN.matcher(seg);
            if (debuffM.matches()) {
                int[] incl = parseFieldGrantTargetFlags(debuffM.group("targets"));
                result.add(new FieldPowerGrant(null, null, incl[0] != 0, incl[1] != 0, incl[2] != 0,
                        null, -Integer.parseInt(debuffM.group("power")),
                        EnumSet.noneOf(Trait.class), true));
                continue;
            }

            Matcher costM = FIELD_GRANT_COST_FILTER_PATTERN.matcher(seg);
            if (costM.matches()) {
                int[] incl = parseFieldGrantTargetFlags(costM.group("targets"));
                int cost = Integer.parseInt(costM.group("cost"));
                String costCmp2 = costM.group("costcmp");
                String except2  = costM.group("except");
                String powerStr2 = costM.group("power");
                int power2 = powerStr2 != null ? Integer.parseInt(powerStr2) : 0;
                String traitsText2 = costM.group("traitstext");
                EnumSet<Trait> traits2 = EnumSet.noneOf(Trait.class);
                if (traitsText2 != null) {
                    if (ICB_EFFECT_HASTE.matcher(traitsText2).find())        traits2.add(Trait.HASTE);
                    if (ICB_EFFECT_BRAVE.matcher(traitsText2).find())        traits2.add(Trait.BRAVE);
                    if (ICB_EFFECT_FIRST_STRIKE.matcher(traitsText2).find()) traits2.add(Trait.FIRST_STRIKE);
                    if (ICB_EFFECT_BACK_ATTACK.matcher(traitsText2).find())  traits2.add(Trait.BACK_ATTACK);
                }
                if (power2 != 0 || !traits2.isEmpty())
                    result.add(new FieldPowerGrant(null, null, incl[0] != 0, incl[1] != 0, incl[2] != 0,
                            except2 != null ? except2.trim() : null, power2, traits2, false, cost, costCmp2));
                continue;
            }

            Matcher m = FIELD_GRANT_PATTERN.matcher(seg);
            if (!m.find()) continue;

            String job      = m.group("job");
            String category = m.group("category");
            String element  = m.group("element");
            if (job != null) job = job.trim();
            if (element != null) element = element.trim();

            String targets = m.group("targets");
            int[] inclFlags = parseFieldGrantTargetFlags(targets);
            boolean inclForwards = inclFlags[0] != 0;
            boolean inclBackups  = inclFlags[1] != 0;
            boolean inclMonsters = inclFlags[2] != 0;

            String except = m.group("except");
            if (except != null) except = except.trim();

            String powerStr = m.group("power");
            int power = powerStr != null ? Integer.parseInt(powerStr) : 0;

            String traitsText = m.group("traitstext");
            EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
            if (traitsText != null) {
                if (ICB_EFFECT_HASTE.matcher(traitsText).find())        traits.add(Trait.HASTE);
                if (ICB_EFFECT_BRAVE.matcher(traitsText).find())        traits.add(Trait.BRAVE);
                if (ICB_EFFECT_FIRST_STRIKE.matcher(traitsText).find()) traits.add(Trait.FIRST_STRIKE);
                if (ICB_EFFECT_BACK_ATTACK.matcher(traitsText).find())  traits.add(Trait.BACK_ATTACK);
            }

            if (power == 0 && traits.isEmpty()) continue;
            result.add(new FieldPowerGrant(job, category, inclForwards, inclBackups, inclMonsters,
                    except, power, traits, false, -1, null, element));
        }
        return List.copyOf(result);
    }

    /**
     * Resolves a {@code targets} capture from a Field-grant regex into
     * {@code {forwards, backups, monsters}} flags (0/1). {@code null} or {@code "Characters"}
     * means all three; {@code Forwards and Monsters} sets both flags accordingly.
     */
    private static int[] parseFieldGrantTargetFlags(String targets) {
        if (targets == null) return new int[] { 1, 1, 1 };
        String tl = targets.toLowerCase();
        return new int[] {
            (tl.contains("forward") || tl.contains("character")) ? 1 : 0,
            (tl.contains("backup")  || tl.contains("character")) ? 1 : 0,
            (tl.contains("monster") || tl.contains("character")) ? 1 : 0,
        };
    }

    // -------------------------------------------------------------------------
    // ScalingSelfPowerBoost parsing
    // -------------------------------------------------------------------------

    /**
     * Matches "For each Forward [your] opponent controls, &lt;target&gt; gains +N power."
     * The captured {@code target} must equal the card's own name for the boost to apply
     * (see {@link #parseScalingSelfPowerBoosts}).
     */
    private static final Pattern SCALING_SELF_OPP_FWD_PATTERN = Pattern.compile(
        "(?i)^For\\s+each\\s+Forward\\s+(?:your\\s+)?opponent\\s+controls,\\s+" +
        "(?<target>.+?)\\s+gains?\\s+\\+(?<power>\\d+)\\s+power[.!]?$"
    );

    /** "For each Backup [your] opponent controls, [target] gains +N power." */
    private static final Pattern SCALING_SELF_OPP_BACKUP_PATTERN = Pattern.compile(
        "(?i)^For\\s+each\\s+Backup\\s+(?:your\\s+)?opponent\\s+controls,\\s+" +
        "(?<target>.+?)\\s+gains?\\s+\\+(?<power>\\d+)\\s+power[.!]?$"
    );

    /**
     * "For each dull Character [your] opponent controls, [target] gains +N power." (Squall 2-038H)
     *
     * <p>Kept separate from {@link #SCALING_SELF_OPP_FWD_PATTERN} rather than widened into it:
     * the type word is Character, not Forward, so the count spans the opposing Backup and Monster
     * rows as well, and the dull filter has no counterpart on the other opponent-side patterns.
     */
    private static final Pattern SCALING_SELF_OPP_DULL_CHARACTER_PATTERN = Pattern.compile(
        "(?i)^For\\s+each\\s+dull\\s+Character\\s+(?:your\\s+)?opponent\\s+controls,\\s+" +
        "(?<target>.+?)\\s+gains?\\s+\\+(?<power>\\d+)\\s+power[.!]?$"
    );

    /** "For each point of damage you have received, [target] gains +N power." */
    private static final Pattern SCALING_SELF_DMG_PATTERN = Pattern.compile(
        "(?i)^For\\s+each\\s+point\\s+of\\s+damage\\s+you\\s+have\\s+received,\\s+" +
        "(?<target>.+?)\\s+gains?\\s+\\+(?<power>\\d+)\\s+power[.!]?$"
    );

    /** "For each card in your hand, [target] gains +N power." */
    private static final Pattern SCALING_SELF_HAND_PATTERN = Pattern.compile(
        "(?i)^For\\s+each\\s+card\\s+in\\s+your\\s+hand,\\s+" +
        "(?<target>.+?)\\s+gains?\\s+\\+(?<power>\\d+)\\s+power[.!]?$"
    );

    /**
     * "For each [Job J or ]Card Name X in your Break Zone, [target] gains +N power."
     *
     * <p>The Job half is Shinra Soldier 10-093C's, and is a genuine alternative rather than an
     * extra requirement: a Break Zone card counts if it carries the Job <em>or</em> the name, which
     * is how {@code matchesScalingFilter} already reads a filter pair. Optional because the other
     * two printings of this shape (Gilgamesh 7-088L, SOLDIER: 3rd Class 20-032C) name only a card.
     * Groups: {@code job} (optional), {@code name}, {@code target}, {@code power}.
     */
    private static final Pattern SCALING_SELF_BZ_CARD_NAME_PATTERN = Pattern.compile(
        "(?i)^For\\s+each\\s+(?:Job\\s+(?<job>.+?)\\s+or\\s+)?Card\\s+Name\\s+(?<name>.+?)" +
        "\\s+in\\s+your\\s+Break\\s+Zone,\\s+" +
        "(?<target>.+?)\\s+gains?\\s+\\+(?<power>\\d+)\\s+power[.!]?$"
    );

    /** "For each [X] Counter placed on [Name], [Name] gains +N power." (self counter scaling) */
    private static final Pattern SCALING_SELF_COUNTER_PATTERN = Pattern.compile(
        "(?i)^For\\s+each\\s+(?<counter>.+?)\\s+Counter\\s+placed\\s+on\\s+(?<target>.+?),\\s+" +
        "(?<subject>.+?)\\s+gains?\\s+\\+(?<power>\\d+)\\s+power[.!]?$"
    );

    /** "[Name] gains +N power for each card removed by [Name]'s ability." (Cloud of Darkness 10-140S) */
    private static final Pattern SCALING_SELF_REMOVED_BY_OWN_ABILITY_PATTERN = Pattern.compile(
        "(?i)^(?<target>.+?)\\s+gains?\\s+\\+(?<power>\\d+)\\s+power\\s+for\\s+each\\s+card\\s+removed\\s+" +
        "by\\s+(?<subject>.+?)'s?\\s+ability[.!]?$"
    );

    /** "For every N Summons in your Break Zone, [target] gains +P power." */
    private static final Pattern SCALING_SELF_BZ_SUMMON_EVERY_N_PATTERN = Pattern.compile(
        "(?i)^For\\s+every\\s+(?<n>\\d+)\\s+Summons?\\s+in\\s+your\\s+Break\\s+Zone,\\s+" +
        "(?<target>.+?)\\s+gains?\\s+\\+(?<power>\\d+)\\s+power[.!]?$"
    );

    /**
     * Unified "For each &lt;filter&gt; [other than X] you control[ other than X], &lt;target&gt; gains +N power."
     * pattern. {@code filter} captures everything between "For each" and the first "other than" or
     * "you control" — including type words (Forward/Character), element prefixes ("Earth"), an
     * "active" prefix, bracketed terms, and written Job/Card Name/Category terms.
     * {@code excA} (before "you control") or {@code excB} (after "you control") captures either the
     * source's own name OR an element name (e.g., "other than Fire"); the parser disambiguates downstream.
     * {@code samejob} captures the trailing "with the same Job as X" qualifier (Bartz 18-047H), whose
     * Job set is only knowable on the field and so is carried as a flag rather than a job string.
     */
    private static final Pattern SCALING_SELF_FOR_EACH_PATTERN = Pattern.compile(
        "(?i)^For\\s+each\\s+(?<filter>.+?)" +
        "(?:\\s+other\\s+than\\s+(?<excA>[^,]+?))?" +
        "\\s+you\\s+control" +
        "(?:\\s+with\\s+the\\s+same\\s+Job\\s+as\\s+(?<samejob>[^,]+?))?" +
        "(?:\\s*,?\\s*other\\s+than\\s+(?<excB>[^,]+?))?" +
        ",\\s+(?<target>.+?)\\s+gains?\\s+\\+(?<power>\\d+)\\s+power[.!]?$"
    );

    private static final Pattern SCALING_FILTER_CARD_NAME_BRACKET = Pattern.compile("(?i)\\[Card\\s+Name\\s+\\(([^)]+)\\)\\]");
    private static final Pattern SCALING_FILTER_JOB_BRACKET       = Pattern.compile("(?i)\\[Job\\s+\\(([^)]+)\\)\\]");
    private static final Pattern SCALING_FILTER_CATEGORY_BRACKET  = Pattern.compile("(?i)\\[Category\\s+\\(([^)]+)\\)\\]");
    private static final Pattern SCALING_FILTER_TYPE_WORD         = Pattern.compile("(?i)^(?<rest>.*?)\\s*(?<type>Forwards?|Characters?|Backups?|Monsters?)$");
    private static final Pattern SCALING_FILTER_ELEMENT_PREFIX    = Pattern.compile("(?i)^(?<elem>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\b\\s*(?<rest>.*)$");
    private static final Pattern SCALING_FILTER_ACTIVE_PREFIX     = Pattern.compile("(?i)^active\\s+(?<rest>.+)$");
    private static final Pattern SCALING_FILTER_WRITTEN_JOB_TERM  = Pattern.compile("(?i)^Job\\s+(?<val>.+)$");
    private static final Pattern SCALING_FILTER_WRITTEN_NAME_TERM = Pattern.compile("(?i)^Card\\s+Name\\s+(?<val>.+)$");
    private static final Pattern SCALING_FILTER_WRITTEN_CAT_TERM  = Pattern.compile("(?i)^Category\\s+(?<val>\\S+)$");
    private static final java.util.Set<String> SCALING_ELEMENT_NAMES = java.util.Set.of(
            "fire", "ice", "wind", "earth", "lightning", "water", "light", "dark");

    /**
     * Parsed filter components: which Source enum to use, the filter terms, and prefix modifiers.
     * Defaults: source = OTHER_FORWARDS_YOU_CONTROL, all filters null, requireActive = false.
     */
    private record ScalingFilterParse(
            ScalingSelfPowerBoost.Source source,
            String jobFilter,
            String categoryFilter,
            String cardNameFilter,
            String elementFilter,
            boolean requireActive) {}

    /**
     * Decomposes the raw filter capture from {@link #SCALING_SELF_FOR_EACH_PATTERN} into the
     * appropriate Source enum and individual filter fields. Handles the "active" prefix,
     * element prefix, type word ("Forward"/"Character" — picks the Source), bracket forms
     * ({@code [Job (X)]}, {@code [Card Name (X)]}, {@code [Category (X)]}), and written forms
     * split on "and" ({@code Job X}, {@code Card Name Y}, {@code Category Z}).
     */
    private static ScalingFilterParse parseScalingForEachFilter(String raw) {
        ScalingSelfPowerBoost.Source source = ScalingSelfPowerBoost.Source.OTHER_FORWARDS_YOU_CONTROL;
        if (raw == null) raw = "";
        String text = raw.trim();
        boolean active = false;

        Matcher am = SCALING_FILTER_ACTIVE_PREFIX.matcher(text);
        if (am.matches()) { active = true; text = am.group("rest").trim(); }

        Matcher tm = SCALING_FILTER_TYPE_WORD.matcher(text);
        if (tm.matches()) {
            String t = tm.group("type").toLowerCase(Locale.ROOT);
            if      (t.startsWith("character")) source = ScalingSelfPowerBoost.Source.OTHER_CHARACTERS_YOU_CONTROL;
            else if (t.startsWith("backup"))    source = ScalingSelfPowerBoost.Source.OTHER_BACKUPS_YOU_CONTROL;
            else if (t.startsWith("monster"))   source = ScalingSelfPowerBoost.Source.OTHER_MONSTERS_YOU_CONTROL;
            // forward(s) keeps the default OTHER_FORWARDS_YOU_CONTROL
            text = tm.group("rest").trim();
        }

        StringBuilder jobs = new StringBuilder();
        StringBuilder cats = new StringBuilder();
        StringBuilder names = new StringBuilder();
        StringBuilder elements = new StringBuilder();

        Matcher nm = SCALING_FILTER_CARD_NAME_BRACKET.matcher(text);
        while (nm.find()) appendScalingTerm(names, nm.group(1));
        text = nm.replaceAll("");

        Matcher jm = SCALING_FILTER_JOB_BRACKET.matcher(text);
        while (jm.find()) appendScalingTerm(jobs, jm.group(1));
        text = jm.replaceAll("");

        Matcher cm = SCALING_FILTER_CATEGORY_BRACKET.matcher(text);
        while (cm.find()) appendScalingTerm(cats, cm.group(1));
        text = cm.replaceAll("");

        text = text.trim().replaceAll("\\s+", " ");

        Matcher em = SCALING_FILTER_ELEMENT_PREFIX.matcher(text);
        if (em.matches()) { appendScalingTerm(elements, em.group("elem")); text = em.group("rest").trim(); }

        if (!text.isEmpty()) {
            for (String term : text.split("(?i)\\s+and\\s+")) {
                String t = term.trim();
                if (t.isEmpty()) continue;
                Matcher wj = SCALING_FILTER_WRITTEN_JOB_TERM.matcher(t);
                Matcher wn = SCALING_FILTER_WRITTEN_NAME_TERM.matcher(t);
                Matcher wc = SCALING_FILTER_WRITTEN_CAT_TERM.matcher(t);
                if      (wj.matches()) appendScalingTerm(jobs,  wj.group("val"));
                else if (wn.matches()) appendScalingTerm(names, wn.group("val"));
                else if (wc.matches()) appendScalingTerm(cats,  wc.group("val"));
            }
        }
        return new ScalingFilterParse(source,
                jobs.length()     > 0 ? jobs.toString()     : null,
                cats.length()     > 0 ? cats.toString()     : null,
                names.length()    > 0 ? names.toString()    : null,
                elements.length() > 0 ? elements.toString() : null,
                active);
    }

    /** Returns the captured exclude name (preferring excA over excB), or {@code null}. */
    private static String coalesceScalingExclude(Matcher m) {
        String a = m.group("excA");
        if (a != null && !a.isBlank()) return a.trim();
        String b = m.group("excB");
        return (b != null && !b.isBlank()) ? b.trim() : null;
    }

    private static void appendScalingTerm(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) return;
        if (sb.length() > 0) sb.append('|');
        sb.append(value.trim());
    }

    /**
     * Parses passive self-targeting scaling power boosts
     * "For each Forward opponent controls, &lt;cardName&gt; gains +1000 power.").
     * Returns an empty list for Summons and whenever the target name does not match
     * the card's own name.
     */
    public static List<ScalingSelfPowerBoost> parseScalingSelfPowerBoosts(
            String textEn, String cardType, String cardName) {
        if (textEn == null || textEn.isBlank()) return List.of();
        if ("Summon".equalsIgnoreCase(cardType)) return List.of();
        if (cardName == null || cardName.isBlank()) return List.of();

        List<ScalingSelfPowerBoost> result = new ArrayList<>();
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;
            Matcher m = SCALING_SELF_OPP_FWD_PATTERN.matcher(seg);
            if (m.find()) {
                if (!m.group("target").trim().equalsIgnoreCase(cardName)) continue;
                int perUnit = Integer.parseInt(m.group("power"));
                if (perUnit <= 0) continue;
                result.add(new ScalingSelfPowerBoost(
                        ScalingSelfPowerBoost.Source.OPPONENT_FORWARDS, perUnit));
                continue;
            }
            Matcher ob = SCALING_SELF_OPP_BACKUP_PATTERN.matcher(seg);
            if (ob.find()) {
                if (!ob.group("target").trim().equalsIgnoreCase(cardName)) continue;
                int perUnit = Integer.parseInt(ob.group("power"));
                if (perUnit <= 0) continue;
                result.add(new ScalingSelfPowerBoost(
                        ScalingSelfPowerBoost.Source.OPPONENT_BACKUPS, perUnit));
                continue;
            }
            Matcher od = SCALING_SELF_OPP_DULL_CHARACTER_PATTERN.matcher(seg);
            if (od.find()) {
                if (!od.group("target").trim().equalsIgnoreCase(cardName)) continue;
                int perUnit = Integer.parseInt(od.group("power"));
                if (perUnit <= 0) continue;
                result.add(new ScalingSelfPowerBoost(
                        ScalingSelfPowerBoost.Source.OPPONENT_DULL_CHARACTERS, perUnit));
                continue;
            }
            Matcher dm = SCALING_SELF_DMG_PATTERN.matcher(seg);
            if (dm.find()) {
                if (!dm.group("target").trim().equalsIgnoreCase(cardName)) continue;
                int perUnit = Integer.parseInt(dm.group("power"));
                if (perUnit <= 0) continue;
                result.add(new ScalingSelfPowerBoost(
                        ScalingSelfPowerBoost.Source.DAMAGE_RECEIVED, perUnit));
                continue;
            }
            Matcher bz = SCALING_SELF_BZ_CARD_NAME_PATTERN.matcher(seg);
            if (bz.find()) {
                if (!bz.group("target").trim().equalsIgnoreCase(cardName)) continue;
                int perUnit = Integer.parseInt(bz.group("power"));
                if (perUnit <= 0) continue;
                result.add(new ScalingSelfPowerBoost(
                        ScalingSelfPowerBoost.Source.CARD_NAME_IN_BREAK_ZONE, perUnit,
                        bz.group("job") != null ? bz.group("job").trim() : null,
                        null, bz.group("name").trim(), null, null, false));
                continue;
            }
            Matcher cc = SCALING_SELF_COUNTER_PATTERN.matcher(seg);
            if (cc.find()) {
                if (!cc.group("target").trim().equalsIgnoreCase(cardName)) continue;
                if (!cc.group("subject").trim().equalsIgnoreCase(cardName)) continue;
                int perUnit = Integer.parseInt(cc.group("power"));
                if (perUnit <= 0) continue;
                // cardNameFilter carries the counter name (see Source.COUNTERS_ON_SELF).
                result.add(new ScalingSelfPowerBoost(
                        ScalingSelfPowerBoost.Source.COUNTERS_ON_SELF, perUnit,
                        null, null, cc.group("counter").trim(), null, null, false));
                continue;
            }
            Matcher rmOwn = SCALING_SELF_REMOVED_BY_OWN_ABILITY_PATTERN.matcher(seg);
            if (rmOwn.find()) {
                if (!rmOwn.group("target").trim().equalsIgnoreCase(cardName)) continue;
                if (!rmOwn.group("subject").trim().equalsIgnoreCase(cardName)) continue;
                int perUnit = Integer.parseInt(rmOwn.group("power"));
                if (perUnit <= 0) continue;
                result.add(new ScalingSelfPowerBoost(
                        ScalingSelfPowerBoost.Source.CARDS_REMOVED_BY_OWN_ABILITY, perUnit));
                continue;
            }
            Matcher bzSummon = SCALING_SELF_BZ_SUMMON_EVERY_N_PATTERN.matcher(seg);
            if (bzSummon.find()) {
                if (!bzSummon.group("target").trim().equalsIgnoreCase(cardName)) continue;
                int perUnit   = Integer.parseInt(bzSummon.group("power"));
                int groupSize = Integer.parseInt(bzSummon.group("n"));
                if (perUnit <= 0 || groupSize <= 0) continue;
                result.add(new ScalingSelfPowerBoost(
                        ScalingSelfPowerBoost.Source.SUMMONS_IN_BREAK_ZONE, perUnit,
                        null, null, null, null, null, false, groupSize));
                continue;
            }
            Matcher hm = SCALING_SELF_HAND_PATTERN.matcher(seg);
            if (hm.find()) {
                if (!hm.group("target").trim().equalsIgnoreCase(cardName)) continue;
                int perUnit = Integer.parseInt(hm.group("power"));
                if (perUnit <= 0) continue;
                result.add(new ScalingSelfPowerBoost(ScalingSelfPowerBoost.Source.CARDS_IN_HAND, perUnit));
                continue;
            }
            Matcher fe = SCALING_SELF_FOR_EACH_PATTERN.matcher(seg);
            if (!fe.find()) continue;
            if (!fe.group("target").trim().equalsIgnoreCase(cardName)) continue;
            int perUnit = Integer.parseInt(fe.group("power"));
            if (perUnit <= 0) continue;

            String exclude = coalesceScalingExclude(fe);
            String excludeElement = null;
            if (exclude != null) {
                if (SCALING_ELEMENT_NAMES.contains(exclude.toLowerCase(Locale.ROOT))) {
                    excludeElement = exclude;
                } else if (!exclude.equalsIgnoreCase(cardName)) {
                    continue; // "other than X" with X not a self-name and not an element — not ours
                }
            }
            // "with the same Job as X" is only ours when X is the card itself — a qualifier naming
            // some other card would be a filter this boost cannot express.
            String sameJobAs = fe.group("samejob");
            if (sameJobAs != null && !sameJobAs.trim().equalsIgnoreCase(cardName)) continue;

            ScalingFilterParse sf = parseScalingForEachFilter(fe.group("filter"));
            result.add(new ScalingSelfPowerBoost(
                    sf.source(), perUnit,
                    sf.jobFilter(), sf.categoryFilter(), sf.cardNameFilter(),
                    sf.elementFilter(), excludeElement, sf.requireActive(), 1,
                    sameJobAs != null));
        }
        return List.copyOf(result);
    }

    // -------------------------------------------------------------------------
    // FieldCostReduction parsing
    // -------------------------------------------------------------------------

    /**
     * Matches passive cost reductions of the form:
     * "The cost required to cast [your] &lt;spec&gt; is reduced by N
     *  [for each Job X forward you control] [(it cannot become 0)]."
     *
     * <p>Spec variants:
     * <ul>
     *   <li>{@code [Card Name (Name)]} — specific card by name in bracket notation</li>
     *   <li>{@code [Element] [Card Name Name] Type} — element / inline name / card type</li>
     *   <li>{@code Job JobName} — job filter (all types)</li>
     * </ul>
     */
    private static final Pattern FIELD_COST_REDUCTION_PATTERN = Pattern.compile(
        "(?i)^The\\s+cost\\s+required\\s+to\\s+cast\\s+" +
        "(?<your>your\\s+)?" +
        "(?:" +
            // [Card Name (Name)] — bracket notation for a single specific card
            "\\[Card\\s+Name\\s+\\((?<bracketedname>[^)]+)\\)\\]" +
        "|" +
            // [Element] [Card Name InlineName] Type
            "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
            "(?:Card\\s+Name\\s+(?<cardname>\\S+)\\s+)?" +
            "(?<type>(?:Forwards?|Backups?|Monsters?|Summons?|Characters?)(?:\\s+or\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?))?)" +
        "|" +
            // Job JobName (lazy so it stops before " is reduced")
            "Job\\s+(?<job>[A-Za-z][A-Za-z\\s'\\-]*?)" +
        ")\\s+is\\s+reduced\\s+by\\s+(?<amount>\\d+)" +
        "(?:\\s+for\\s+each\\s+Job\\s+(?<scalingjob>[A-Za-z][A-Za-z\\s'\\-]*?)\\s+forward\\s+you\\s+control)?" +
        "(?:\\s+(?<flooratone>\\(it\\s+cannot\\s+become\\s+0\\)))?" +
        "\\s*\\.?$"
    );

    /**
     * Matches passive cost reductions of the "play … onto the field" form:
     * "The cost required to play [your] &lt;spec&gt; onto the field is reduced by N
     *  [(it cannot become 0)]."
     *
     * <p>Spec may be a single branch or several "Card Name X or Card Name Y" branches.
     * Each branch is parsed separately by {@link #parsePlayCostReductionBranch}.
     */
    private static final Pattern FIELD_PLAY_COST_REDUCTION_PATTERN = Pattern.compile(
        "(?i)^The\\s+cost\\s+required\\s+to\\s+play\\s+" +
        "(?<your>your\\s+)?" +
        "(?<rawspec>.+?)\\s+" +
        "onto\\s+the\\s+field\\s+" +
        "is\\s+reduced\\s+by\\s+(?<amount>\\d+)" +
        "(?:\\s+for\\s+each\\s+Job\\s+(?<scalingjob>[A-Za-z][A-Za-z\\s'''\\-]*?)\\s+forward\\s+you\\s+control)?" +
        "(?:\\s*(?<flooratone>\\(it\\s+cannot\\s+become\\s+0\\)))?" +
        "\\s*\\.?$"
    );

    /**
     * Matches "The cost required for [your opponent|all players] to cast &lt;spec&gt;
     * is increased by N."
     *
     * <p>Groups: {@code who} — the affected player(s); {@code type} — the card type(s) named
     * positively; {@code excluded} — the single type named by the "cards other than a Backup"
     * form, which is the complement of {@code type}; {@code direction} — the verb, since the
     * same sentence shape prints "reduced"; {@code amount} — the magnitude.
     *
     * <p>Terra 1-046H prints "increases by 1" rather than "is increased by 1", so the verb
     * alternation carries both. Without it Terra's Summon tax parses as nothing at all.
     */
    static final Pattern FIELD_CAST_COST_INCREASE_PATTERN = Pattern.compile(
        "(?i)^The\\s+cost\\s+required\\s+for\\s+(?<who>your\\s+opponent|all\\s+players)\\s+to\\s+cast\\s+" +
        "(?:" +
            "cards?\\s+other\\s+than\\s+(?:an?\\s+)?(?<excluded>Forwards?|Backups?|Monsters?|Summons?)" +
        "|" +
            "(?<type>(?:Forwards?|Backups?|Monsters?|Summons?|Characters?)(?:\\s+or\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?))?)" +
        ")" +
        "\\s+(?:is\\s+(?<direction>increased|reduced)|(?<direction2>increases|decreases))\\s+by\\s+(?<amount>\\d+)" +
        "\\s*\\.?$"
    );

    /** Matches "If you control N or more [Category X] Y, the cost required to cast/play Name is reduced by N." */
    private static final Pattern FIELD_CONDITIONAL_COST_REDUCTION_PATTERN = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+\\d+\\s+or\\s+more\\s+" +
        "(?:Category\\s+(?<cat>\\S+)\\s+)?" +
        "(?:Forwards?|Backups?|Monsters?|Summons?|Characters?),\\s+" +
        "the\\s+cost\\s+required\\s+to\\s+(?:cast|play)\\s+" +
        "(?:Card\\s+Name\\s+)?(?<cardname>[A-Za-z][A-Za-z\\s'''\\-]*?)\\s+" +
        "is\\s+reduced\\s+by\\s+(?<amount>\\d+)" +
        "\\s*\\.?$"
    );

    /** Matches {@code Card Name <name>} where name may contain spaces (used after ` or ` splitting). */
    private static final Pattern PLAY_SPEC_CARD_NAME = Pattern.compile(
        "(?i)^Card\\s+Name\\s+(?<name>.+)$"
    );

    /** Matches {@code [Job (Name)]} with an optional trailing card type. */
    private static final Pattern PLAY_SPEC_BRACKETED_JOB = Pattern.compile(
        "(?i)^\\[Job\\s+\\((?<job>[^)]+)\\)\\](?:\\s+(?<type>Forwards?|Backups?|Monsters?|Summons?|Characters?))?$"
    );

    /**
     * Parses one "play … onto the field" spec branch into a {@link FieldCostReduction}.
     * Returns {@code null} if no sub-pattern matches.
     */
    private static FieldCostReduction parsePlayCostReductionBranch(
            String branch, int amount, boolean floorAtOne, boolean ownerOnly, String scalingJob) {
        Matcher m;

        m = PLAY_SPEC_BRACKETED_JOB.matcher(branch);
        if (m.find()) {
            String job  = m.group("job").trim();
            String type = m.group("type");
            String tl   = type != null ? type.toLowerCase() : "";
            boolean iF  = type == null || tl.contains("forward")  || tl.contains("character");
            boolean iB  = type == null || tl.contains("backup")   || tl.contains("character");
            boolean iM  = type == null || tl.contains("monster")  || tl.contains("character");
            boolean iS  = type == null || tl.contains("summon");
            return new FieldCostReduction(amount, floorAtOne, ownerOnly, false, iF, iB, iM, iS,
                    null, job, null, null, scalingJob, false, null);
        }

        m = CAST_SPEC_JOB_TYPE.matcher(branch);
        if (m.find()) {
            String job  = m.group("job").trim();
            String type = m.group("type");
            String tl   = type != null ? type.toLowerCase() : "";
            boolean iF  = type == null || tl.contains("forward")  || tl.contains("character");
            boolean iB  = type == null || tl.contains("backup")   || tl.contains("character");
            boolean iM  = type == null || tl.contains("monster")  || tl.contains("character");
            boolean iS  = type == null || tl.contains("summon");
            return new FieldCostReduction(amount, floorAtOne, ownerOnly, false, iF, iB, iM, iS,
                    null, job, null, null, scalingJob, false, null);
        }

        m = CAST_SPEC_BRACKETED.matcher(branch);
        if (m.find())
            return new FieldCostReduction(amount, floorAtOne, ownerOnly, false, true, true, true, true,
                    null, null, m.group("name").trim(), null, scalingJob, false, null);

        m = PLAY_SPEC_CARD_NAME.matcher(branch);
        if (m.find())
            return new FieldCostReduction(amount, floorAtOne, ownerOnly, false, true, true, true, true,
                    null, null, m.group("name").trim(), null, scalingJob, false, null);

        m = CAST_SPEC_CATEGORY_TYPE.matcher(branch);
        if (m.find()) {
            String cat  = m.group("cat");
            String tl   = m.group("type").toLowerCase();
            boolean iF  = tl.contains("forward")  || tl.contains("character");
            boolean iB  = tl.contains("backup")   || tl.contains("character");
            boolean iM  = tl.contains("monster")  || tl.contains("character");
            boolean iS  = tl.contains("summon");
            return new FieldCostReduction(amount, floorAtOne, ownerOnly, false, iF, iB, iM, iS,
                    null, null, null, cat, scalingJob, false, null);
        }

        m = CAST_SPEC_TYPE.matcher(branch);
        if (m.find()) {
            String element = m.group("element");
            String tl      = m.group("type").toLowerCase();
            boolean iF     = tl.contains("forward")  || tl.contains("character");
            boolean iB     = tl.contains("backup")   || tl.contains("character");
            boolean iM     = tl.contains("monster")  || tl.contains("character");
            boolean iS     = tl.contains("summon");
            return new FieldCostReduction(amount, floorAtOne, ownerOnly, false, iF, iB, iM, iS,
                    element, null, null, null, scalingJob, false, null);
        }

        return null;
    }

    /** Parses all cast-cost modifiers: flat reductions and "can be paid with any Element" grants. */
    public static List<FieldCostReduction> parseFieldCostReductions(String textEn, String cardType) {
        if (textEn == null || textEn.isBlank()) return List.of();
        if ("Summon".equalsIgnoreCase(cardType)) return List.of();

        List<FieldCostReduction> result = new ArrayList<>();
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;

            // "Damage N -- …" gates the modifier on the printing player's own damage zone.
            // Stripping it here keeps every pattern below anchored at the sentence proper, which
            // is why they matched these segments before the gate was honoured at all.
            int damageThreshold = 0;
            Matcher dtM = DAMAGE_THRESHOLD_PREFIX.matcher(seg);
            if (dtM.find()) {
                damageThreshold = Integer.parseInt(dtM.group(1));
                seg = seg.substring(dtM.end()).trim();
                if (seg.isEmpty()) continue;
            }

            for (FieldCostReduction fcr : parseFieldCostReductionSegment(seg))
                result.add(damageThreshold > 0 ? fcr.withDamageThreshold(damageThreshold) : fcr);
        }
        return List.copyOf(result);
    }

    /**
     * Parses one {@code [[br]]}-delimited segment, already stripped of markup and of any
     * "Damage N --" prefix. Returns an empty list when the segment declares no cast-cost modifier.
     */
    private static List<FieldCostReduction> parseFieldCostReductionSegment(String seg) {
        List<FieldCostReduction> result = new ArrayList<>();

        // Flat / scaling reduction
        Matcher m = FIELD_COST_REDUCTION_PATTERN.matcher(seg);
        if (m.find()) {
            boolean ownerOnly  = m.group("your")      != null;
            boolean floorAtOne = m.group("flooratone") != null;
            int     amount     = Integer.parseInt(m.group("amount"));

            String elementFilter  = m.group("element");
            String jobFilter      = m.group("job");
            if (jobFilter != null) jobFilter = jobFilter.trim();
            String cardNameFilter = m.group("bracketedname") != null
                    ? m.group("bracketedname").trim()
                    : m.group("cardname");
            String scalingJob = m.group("scalingjob");
            if (scalingJob != null) scalingJob = scalingJob.trim();

            boolean inclForwards, inclBackups, inclMonsters, inclSummons;
            if (cardNameFilter != null && m.group("type") == null) {
                inclForwards = inclBackups = inclMonsters = inclSummons = true;
            } else if (jobFilter != null) {
                inclForwards = inclBackups = inclMonsters = inclSummons = true;
            } else {
                String tl = m.group("type") != null ? m.group("type").toLowerCase() : "";
                inclForwards = tl.contains("forward")   || tl.contains("character");
                inclBackups  = tl.contains("backup")    || tl.contains("character");
                inclMonsters = tl.contains("monster")   || tl.contains("character");
                inclSummons  = tl.contains("summon");
            }

            result.add(new FieldCostReduction(amount, floorAtOne, ownerOnly, false,
                    inclForwards, inclBackups, inclMonsters, inclSummons,
                    elementFilter, jobFilter, cardNameFilter, null, scalingJob, false, null));
            return result;
        }

        // "play … onto the field is reduced by N" — may have multiple "or Card Name X" branches
        Matcher pm = FIELD_PLAY_COST_REDUCTION_PATTERN.matcher(seg);
        if (pm.find()) {
            boolean ownerOnly  = pm.group("your")       != null;
            boolean floorAtOne = pm.group("flooratone") != null;
            int     amount     = Integer.parseInt(pm.group("amount"));
            String  scalingJob = pm.group("scalingjob");
            if (scalingJob != null) scalingJob = scalingJob.trim();
            for (String branch : pm.group("rawspec").split("(?i)\\s+or\\s+")) {
                FieldCostReduction fcr = parsePlayCostReductionBranch(
                        branch.trim(), amount, floorAtOne, ownerOnly, scalingJob);
                if (fcr != null) result.add(fcr);
            }
            return result;
        }

        // Conditional reduction ("If you control N or more X, the cost … is reduced by N")
        Matcher cm = FIELD_CONDITIONAL_COST_REDUCTION_PATTERN.matcher(seg);
        if (cm.find()) {
            int    amount   = Integer.parseInt(cm.group("amount"));
            String cardName = cm.group("cardname").trim();
            result.add(new FieldCostReduction(amount, false, false, false, true, true, true, true,
                    null, null, cardName, null, null, false, null));
            return result;
        }

        // Conditional BZ-job any-element grant
        Matcher bzJobM = FIELD_CONDITIONAL_BZ_JOB_ANY_ELEMENT_PATTERN.matcher(seg);
        if (bzJobM.find()) {
            String job      = bzJobM.group("job").trim();
            String cardName = bzJobM.group("cardname").trim();
            result.add(new FieldCostReduction(0, false, true, false, true, true, true, true,
                    null, null, cardName, null, null, true, job));
            return result;
        }

        // Cast cost increase ("The cost required for [your opponent|all players] to cast X
        // is increased by N") — the "all players" form taxes the printing player too, so it
        // is neither ownerOnly nor opponentOnly.
        Matcher oppM = FIELD_CAST_COST_INCREASE_PATTERN.matcher(seg);
        if (oppM.find()) {
            int     amount   = Integer.parseInt(oppM.group("amount"));
            boolean oppOnly  = oppM.group("who").toLowerCase().contains("opponent");
            String  dir      = oppM.group("direction") != null
                    ? oppM.group("direction") : oppM.group("direction2");
            boolean increase = dir.toLowerCase().startsWith("increase");
            boolean iF, iB, iM, iS;
            if (oppM.group("excluded") != null) {
                // "cards other than a Backup" — every type but the one named.
                String ex = oppM.group("excluded").toLowerCase();
                iF = !ex.contains("forward");
                iB = !ex.contains("backup");
                iM = !ex.contains("monster");
                iS = !ex.contains("summon");
            } else {
                String tl = oppM.group("type").toLowerCase();
                iF = tl.contains("forward")  || tl.contains("character");
                iB = tl.contains("backup")   || tl.contains("character");
                iM = tl.contains("monster")  || tl.contains("character");
                iS = tl.contains("summon");
            }
            // Store an increase as negative amountPerUnit so apply() adds it:
            // cost - (-amount) = cost + amount.
            result.add(new FieldCostReduction(increase ? -amount : amount, false, false, oppOnly,
                    iF, iB, iM, iS, null, null, null, null, null, false, null));
            return result;
        }

        // "can be paid with CP of any Element" (with optional flat reduction prefix)
        m = FIELD_CAST_ANY_ELEMENT_PATTERN.matcher(seg);
        if (m.find()) {
            boolean ownerOnly = m.group("your") != null;
            String  amtStr    = m.group("amount");
            int     amount    = amtStr != null ? Integer.parseInt(amtStr) : 0;
            for (String branch : m.group("rawspec").split("(?i)\\s+or\\s+")) {
                FieldCostReduction fcr = parseCastAnyElementBranch(branch.trim(), amount, ownerOnly);
                if (fcr != null) result.add(fcr);
            }
        }
        return result;
    }

    /**
     * Matches "If you have a Job &lt;job&gt; in your Break Zone, the cost required to cast &lt;cardname&gt;
     * can be paid with CP of any Element."
     * Groups: {@code job}, {@code cardname}.
     */
    private static final Pattern FIELD_CONDITIONAL_BZ_JOB_ANY_ELEMENT_PATTERN = Pattern.compile(
        "(?i)^If\\s+you\\s+have\\s+a\\s+Job\\s+(?<job>[^,]+?)\\s+in\\s+your\\s+Break\\s+Zone,\\s+" +
        "the\\s+cost\\s+required\\s+to\\s+cast\\s+(?<cardname>.+?)\\s+can\\s+be\\s+paid\\s+with\\s+CP\\s+of\\s+any\\s+Element\\s*\\.?$"
    );

    /**
     * Matches "can be paid with CP of any Element" cast-cost grants, with an optional
     * flat reduction prefix:
     * "The cost required to (cast|play) [your] &lt;rawspec&gt; [(onto the field)]
     *  [(is reduced by N [and])] can be paid with CP of any Element."
     */
    private static final Pattern FIELD_CAST_ANY_ELEMENT_PATTERN = Pattern.compile(
        "(?i)^The\\s+cost\\s+required\\s+to\\s+(?:cast|play)\\s+" +
        "(?<your>your\\s+)?" +
        "(?<rawspec>.+?)\\s+" +
        "(?:onto\\s+the\\s+field\\s+)?" +
        "(?:is\\s+reduced\\s+by\\s+(?<amount>\\d+)(?:\\s+and)?\\s+)?" +
        "can\\s+be\\s+paid\\s+with\\s+CP\\s+of\\s+any\\s+Element" +
        "(?:\\s+\\(it\\s+cannot\\s+become\\s+0\\))?" +
        "\\s*\\.?$"
    );

    // Sub-patterns for individual spec branches (split on " or ")
    private static final Pattern CAST_SPEC_JOB_TYPE = Pattern.compile(
        "(?i)^Job\\s+(?<job>[A-Za-z][A-Za-z\\s'''\\-]*?)(?:\\s+(?<type>Forwards?|Backups?|Monsters?|Summons?|Characters?))?$"
    );
    private static final Pattern CAST_SPEC_CARD_NAME = Pattern.compile(
        "(?i)^Card\\s+Name\\s+(?<name>\\S+)$"
    );
    private static final Pattern CAST_SPEC_BRACKETED = Pattern.compile(
        "(?i)^\\[Card\\s+Name\\s+\\((?<name>[^)]+)\\)\\]$"
    );
    private static final Pattern CAST_SPEC_CATEGORY_TYPE = Pattern.compile(
        "(?i)^Category\\s+(?<cat>\\S+)\\s+(?<type>Forwards?|Backups?|Monsters?|Summons?|Characters?)$"
    );
    private static final Pattern CAST_SPEC_TYPE = Pattern.compile(
        "(?i)^(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?(?<type>Forwards?|Backups?|Monsters?|Summons?|Characters?)$"
    );

    /** Parses one spec branch into a {@link FieldCostReduction} with {@code anyElement=true}. */
    private static FieldCostReduction parseCastAnyElementBranch(
            String branch, int amount, boolean ownerOnly) {
        Matcher m;

        m = CAST_SPEC_JOB_TYPE.matcher(branch);
        if (m.find()) {
            String job  = m.group("job").trim();
            String type = m.group("type");
            String tl   = type != null ? type.toLowerCase() : "";
            boolean iF  = type == null || tl.contains("forward")  || tl.contains("character");
            boolean iB  = type == null || tl.contains("backup")   || tl.contains("character");
            boolean iM  = type == null || tl.contains("monster")  || tl.contains("character");
            boolean iS  = type == null || tl.contains("summon");
            return new FieldCostReduction(amount, false, ownerOnly, false, iF, iB, iM, iS,
                    null, job, null, null, null, true, null);
        }

        m = CAST_SPEC_BRACKETED.matcher(branch);
        if (m.find())
            return new FieldCostReduction(amount, false, ownerOnly, false, true, true, true, true,
                    null, null, m.group("name").trim(), null, null, true, null);

        m = CAST_SPEC_CARD_NAME.matcher(branch);
        if (m.find())
            return new FieldCostReduction(amount, false, ownerOnly, false, true, true, true, true,
                    null, null, m.group("name"), null, null, true, null);

        m = CAST_SPEC_CATEGORY_TYPE.matcher(branch);
        if (m.find()) {
            String cat  = m.group("cat");
            String tl   = m.group("type").toLowerCase();
            boolean iF  = tl.contains("forward")  || tl.contains("character");
            boolean iB  = tl.contains("backup")   || tl.contains("character");
            boolean iM  = tl.contains("monster")  || tl.contains("character");
            boolean iS  = tl.contains("summon");
            return new FieldCostReduction(amount, false, ownerOnly, false, iF, iB, iM, iS,
                    null, null, null, cat, null, true, null);
        }

        m = CAST_SPEC_TYPE.matcher(branch);
        if (m.find()) {
            String element = m.group("element");
            String tl      = m.group("type").toLowerCase();
            boolean iF     = tl.contains("forward")  || tl.contains("character");
            boolean iB     = tl.contains("backup")   || tl.contains("character");
            boolean iM     = tl.contains("monster")  || tl.contains("character");
            boolean iS     = tl.contains("summon");
            return new FieldCostReduction(amount, false, ownerOnly, false, iF, iB, iM, iS,
                    element, null, null, null, null, true, null);
        }

        return null;
    }

    private static final Pattern FIELD_PRIMING_ANY_ELEMENT_PATTERN = Pattern.compile(
        "(?i)^The\\s+Priming\\s+cost\\s+of\\s+the\\s+" +
        "(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+" +
        "you\\s+control\\s+can\\s+be\\s+paid\\s+with\\s+CP\\s+of\\s+any\\s+Element" +
        "\\s*\\.?$"
    );

    private static final Pattern WARP_ANY_ELEMENT_PATTERN = Pattern.compile(
        "(?i)^Your\\s+Warp\\s+cost\\s+can\\s+be\\s+paid\\s+with\\s+CP\\s+of\\s+any\\s+Element\\s*\\.?$"
    );

    /** Parses all "The Priming cost of the X you control can be paid with CP of any Element" segments. */
    public static List<FieldPrimingAnyElement> parseFieldPrimingAnyElements(String textEn, String cardType) {
        if (textEn == null || textEn.isBlank()) return List.of();
        if ("Summon".equalsIgnoreCase(cardType)) return List.of();

        List<FieldPrimingAnyElement> result = new ArrayList<>();
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;
            Matcher m = FIELD_PRIMING_ANY_ELEMENT_PATTERN.matcher(seg);
            if (!m.find()) continue;
            String tl    = m.group("type").toLowerCase();
            boolean inclF = tl.contains("forward")  || tl.contains("character");
            boolean inclB = tl.contains("backup")   || tl.contains("character");
            boolean inclM = tl.contains("monster")  || tl.contains("character");
            result.add(new FieldPrimingAnyElement(inclF, inclB, inclM));
        }
        return List.copyOf(result);
    }

    /** Returns {@code true} if the card text contains "Your Warp cost can be paid with CP of any Element." */
    public static boolean parseWarpCostAnyElement(String textEn) {
        if (textEn == null || textEn.isBlank()) return false;
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (WARP_ANY_ELEMENT_PATTERN.matcher(seg).find()) return true;
        }
        return false;
    }

    /** Matches "[CardName] can form a party with Forwards of any Element." (self-grant on card text). */
    private static final Pattern PARTY_ANY_ELEMENT_PATTERN = Pattern.compile(
        "(?i)\\S.*?\\s+can\\s+form\\s+a\\s+party\\s+with\\s+Forwards?\\s+of\\s+any\\s+Element\\s*\\.?"
    );

    /**
     * Matches "The [Job X / Category X / all] Forwards you control can form a party with
     * [anything] Forwards of any Element." — a field-ability grant to other cards.
     * Groups: {@code job}, {@code category}, {@code cardname} (all optional).
     */
    private static final Pattern FIELD_PARTY_ANY_ELEMENT_PATTERN = Pattern.compile(
        "(?i)The\\s+" +
        "(?:Job\\s+(?<job>.+?)\\s+|Category\\s+(?<category>\\S+)\\s+|Card\\s+Name\\s+(?<cardname>\\S+)\\s+)?" +
        "Forwards?\\s+you\\s+control\\s+can\\s+form\\s+a\\s+party\\s+with\\s+" +
        "(?:.+?\\s+)?Forwards?\\s+of\\s+any\\s+Element\\s*\\.?"
    );

    /**
     * Matches "[CardName] cannot be blocked by a/Forwards of cost N or more/less."
     * Groups: {@code cardname}, {@code costval}, {@code costcmp} (optional: "less" or "more"; default "more").
     */
    private static final Pattern FIELD_CANNOT_BE_BLOCKED_BY_COST = Pattern.compile(
        "(?i)^(?<cardname>.+?)\\s+cannot\\s+be\\s+blocked\\s+by\\s+(?:a\\s+)?Forwards?\\s+of\\s+cost\\s+" +
        "(?<costval>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?\\s*\\.?\\s*$"
    );

    /**
     * Parses an intrinsic "cannot be blocked by a Forward of cost N or more/less" field ability.
     * Returns {@code null} if no such ability is present.
     * The returned array is {@code {costVal, 1}} for "or more" and {@code {costVal, 0}} for "or less".
     */
    public static int[] parseFieldCannotBeBlockedByCost(String textEn, String cardName) {
        if (textEn == null || textEn.isBlank()) return null;
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;
            Matcher m = FIELD_CANNOT_BE_BLOCKED_BY_COST.matcher(seg);
            if (!m.find()) continue;
            if (!m.group("cardname").trim().equalsIgnoreCase(cardName)) continue;
            int costVal  = Integer.parseInt(m.group("costval"));
            boolean orMore = !"less".equalsIgnoreCase(m.group("costcmp")); // default "or more"
            return new int[]{costVal, orMore ? 1 : 0};
        }
        return null;
    }

    /**
     * Matches "[CardName] cannot be blocked by a Forward of power N or more/less." (Ark Angel MR
     * 8-045R; Iris 12-117R grants the same wording until end of turn.)
     * Groups: {@code cardname}, {@code powerval}, {@code powercmp} (optional; default "more").
     *
     * <p>An absolute threshold, unlike {@link #FIELD_CANNOT_BE_BLOCKED_BY_HIGHER_POWER}, which
     * compares the blocker against the attacker's own power and so moves with it. The shape is
     * {@link #FIELD_CANNOT_BE_BLOCKED_BY_COST}'s, with power in place of cost.
     */
    private static final Pattern FIELD_CANNOT_BE_BLOCKED_BY_POWER = Pattern.compile(
        "(?i)^(?<cardname>.+?)\\s+cannot\\s+be\\s+blocked\\s+by\\s+(?:a\\s+)?Forwards?\\s+of\\s+power\\s+" +
        "(?<powerval>\\d+)(?:\\s+or\\s+(?<powercmp>less|more))?\\s*\\.?\\s*$"
    );

    /**
     * Parses an intrinsic "cannot be blocked by a Forward of power N or more/less" field ability
     * off {@code textEn}, or {@code null} when the card prints none naming itself.
     * The result is {@code {powerVal, 1}} for "or more" and {@code {powerVal, 0}} for "or less".
     *
     * <p>Read on demand rather than stored on the record, unlike its cost-filter twin: the
     * constructor already carries thirty-odd arguments and fifty-nine call sites, and the answer is
     * a regex over one sentence.
     */
    public static int[] parseFieldCannotBeBlockedByPower(String textEn, String cardName) {
        if (textEn == null || textEn.isBlank()) return null;
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;
            Matcher m = FIELD_CANNOT_BE_BLOCKED_BY_POWER.matcher(seg);
            if (!m.matches()) continue;
            if (!m.group("cardname").trim().equalsIgnoreCase(cardName)) continue;
            int powerVal = Integer.parseInt(m.group("powerval"));
            boolean orMore = !"less".equalsIgnoreCase(m.group("powercmp")); // default "or more"
            return new int[]{powerVal, orMore ? 1 : 0};
        }
        return null;
    }

    /**
     * Matches "[CardName] cannot be blocked by a Forward with a power greater than [his/hers/CardName's]."
     * Groups: {@code cardname}, {@code ref} (the possessive reference).
     */
    private static final Pattern FIELD_CANNOT_BE_BLOCKED_BY_HIGHER_POWER = Pattern.compile(
        "(?i)^(?<cardname>.+?)\\s+cannot\\s+be\\s+blocked\\s+by\\s+a\\s+Forward\\s+with\\s+a\\s+power" +
        "\\s+greater\\s+than\\s+(?<ref>his|hers|\\S.*?'s)\\.?\\s*$"
    );

    /**
     * Returns {@code true} if the card has an intrinsic "cannot be blocked by a Forward with a power
     * greater than [its own]" field ability.
     */
    public static boolean parseCannotBeBlockedByHigherPower(String textEn, String cardName) {
        if (textEn == null || textEn.isBlank()) return false;
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;
            Matcher m = FIELD_CANNOT_BE_BLOCKED_BY_HIGHER_POWER.matcher(seg);
            if (!m.find()) continue;
            if (!m.group("cardname").trim().equalsIgnoreCase(cardName)) continue;
            String ref = m.group("ref").trim();
            // Accept generic pronouns or an explicit self-reference ("CardName's")
            if (ref.equalsIgnoreCase("his") || ref.equalsIgnoreCase("hers")
                    || ref.equalsIgnoreCase(cardName + "'s")) return true;
        }
        return false;
    }

    /** "[CardName] cannot block a Forward with a power greater than his/her/its." */
    private static final Pattern FIELD_CANNOT_BLOCK_HIGHER_POWER = Pattern.compile(
        "(?i)^(?<cardname>.+?)\\s+cannot\\s+block\\s+a\\s+Forward\\s+with\\s+a\\s+power\\s+greater\\s+than\\s+(?<ref>his|hers?|its)[.!]?\\s*$"
    );

    /** "[CardName] cannot block Forwards forming a party." */
    private static final Pattern FIELD_CANNOT_BLOCK_PARTY = Pattern.compile(
        "(?i)^(?<cardname>.+?)\\s+cannot\\s+block\\s+Forwards?\\s+forming\\s+a\\s+party[.!]?\\s*$"
    );

    /** "[CardName] cannot block." (no qualifier — absolute restriction). */
    private static final Pattern FIELD_CANNOT_BLOCK = Pattern.compile(
        "(?i)^(?<cardname>.+?)\\s+cannot\\s+block[.!]?\\s*$"
    );

    /**
     * "[All the] Forwards of cost N or more/less cannot block." — Edea 2-100H.
     *
     * <p>Board-wide: it names no controller, so unlike its self-named neighbours above it reaches
     * every Forward on either side of the field, its own controller's included.
     * Groups: {@code cost}, {@code costcmp}.
     *
     * <p>{@link #FIELD_CANNOT_BLOCK}'s lazy {@code cardname} group also matches this sentence, with
     * the whole cost clause absorbed as the name. That is harmless — the name check against the
     * carrier fails — but it is why this must not be folded into that pattern.
     */
    static final Pattern FIELD_COST_FORWARDS_CANNOT_BLOCK = Pattern.compile(
        "(?i)^(?:All\\s+)?(?:the\\s+)?Forwards?\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+" +
        "(?<costcmp>more|less)\\s+cannot\\s+block[.!]?\\s*$"
    );

    /**
     * Reads a board-wide cost-gated block lock out of {@code seg}, or {@code null} when it is not
     * one. Returns {@code {cost, cmp}} where {@code cmp} is {@code 1} for "or more" and {@code -1}
     * for "or less".
     */
    public static int[] parseCostForwardsCannotBlock(String seg) {
        if (seg == null || seg.isBlank()) return null;
        Matcher m = FIELD_COST_FORWARDS_CANNOT_BLOCK.matcher(seg.trim());
        if (!m.matches()) return null;
        return new int[] { Integer.parseInt(m.group("cost")),
                           "more".equalsIgnoreCase(m.group("costcmp")) ? 1 : -1 };
    }

    /**
     * This card's board-wide cost-gated block lock as {@code {cost, cmp}}, or {@code null} when it
     * prints none. See {@link #parseCostForwardsCannotBlock}.
     */
    public int[] costForwardsCannotBlock() {
        for (String seg : rawFieldSegments()) {
            int[] lock = parseCostForwardsCannotBlock(seg);
            if (lock != null) return lock;
        }
        return null;
    }

    /** "[CardName] cannot attack or block." — absolute restriction on both attack and block. */
    static final Pattern FIELD_CANNOT_ATTACK_OR_BLOCK = Pattern.compile(
        "(?i)^(?<cardname>.+?)\\s+cannot\\s+attack\\s+or\\s+block[.!]?\\s*$"
    );

    /**
     * "[CardName] can attack twice/N times in the same turn." — also accepts "twice per turn"
     * (Prompto 27-068R) and the "3 times"/"4 times" forms (Gilgamesh (FFBE) 14-023L,
     * Ravana 14-087L). Group {@code count} is absent for the "twice" wording.
     *
     * <p>Tidus 29-105L's "as many times … as the points of damage you have received" is
     * deliberately not matched: its permitted count varies during the turn, which
     * {@link #parseMaxAttacksPerTurn}'s static int cannot carry.
     */
    static final Pattern FIELD_CAN_ATTACK_TWICE = Pattern.compile(
        "(?i)^(?<cardname>.+?)\\s+can\\s+attack\\s+(?:twice|(?<count>\\d+)\\s+times)\\s+" +
        "(?:in\\s+the\\s+same\\s+turn|per\\s+turn)[.!]?\\s*$"
    );

    /**
     * "[CardName] can attack as many times in the same turn as the points of damage you have
     * received." (Tidus 29-105L) — the permission {@link #FIELD_CAN_ATTACK_TWICE} deliberately
     * skips, because its allowance moves with the damage zone during the turn and so has to be
     * read at query time rather than frozen into {@link #maxAttacksPerTurn}.
     */
    static final Pattern FIELD_ATTACKS_PER_OWN_DAMAGE = Pattern.compile(
        "(?i)^(?<cardname>.+?)\\s+can\\s+attack\\s+as\\s+many\\s+times\\s+in\\s+the\\s+same\\s+turn\\s+" +
        "as\\s+the\\s+points\\s+of\\s+damage\\s+you\\s+have\\s+received[.!]?\\s*$"
    );

    /**
     * Returns {@code true} when {@code effectText} is the "can attack as many times … as the points
     * of damage you have received" field ability belonging to {@code cardName}.
     */
    public static boolean parseAttacksPerOwnDamage(String effectText, String cardName) {
        Matcher m = FIELD_ATTACKS_PER_OWN_DAMAGE.matcher(effectText.trim());
        if (!m.matches()) return false;
        return m.group("cardname").trim().equalsIgnoreCase(cardName);
    }

    /**
     * The number of times {@code cardName} may attack in a turn per its printed text, or 1 when it
     * carries no multi-attack permission. Every Forward may attack once; a permission replaces that
     * allowance rather than adding to it.
     */
    public static int parseMaxAttacksPerTurn(String textEn, String cardName) {
        if (textEn == null || textEn.isBlank()) return 1;
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;
            Matcher m = FIELD_CAN_ATTACK_TWICE.matcher(seg);
            if (!m.matches()) continue;
            if (!m.group("cardname").trim().equalsIgnoreCase(cardName)) continue;
            return m.group("count") != null ? Integer.parseInt(m.group("count")) : 2;
        }
        return 1;
    }

    public static boolean parseCannotAttackOrBlock(String textEn, String cardName) {
        if (textEn == null || textEn.isBlank()) return false;
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;
            Matcher m = FIELD_CANNOT_ATTACK_OR_BLOCK.matcher(seg);
            if (!m.matches()) continue;
            if (m.group("cardname").trim().equalsIgnoreCase(cardName)) return true;
        }
        return false;
    }

    public static boolean parseCannotBlockAtAll(String textEn, String cardName) {
        if (textEn == null || textEn.isBlank()) return false;
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;
            Matcher m = FIELD_CANNOT_BLOCK.matcher(seg);
            if (!m.matches()) continue;
            if (m.group("cardname").trim().equalsIgnoreCase(cardName)) return true;
        }
        return false;
    }

    public static boolean parseCannotBlockHigherPower(String textEn, String cardName) {
        if (textEn == null || textEn.isBlank()) return false;
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;
            Matcher m = FIELD_CANNOT_BLOCK_HIGHER_POWER.matcher(seg);
            if (!m.matches()) continue;
            if (m.group("cardname").trim().equalsIgnoreCase(cardName)) return true;
        }
        return false;
    }

    public static boolean parseCannotBlockParty(String textEn, String cardName) {
        if (textEn == null || textEn.isBlank()) return false;
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;
            Matcher m = FIELD_CANNOT_BLOCK_PARTY.matcher(seg);
            if (!m.matches()) continue;
            if (m.group("cardname").trim().equalsIgnoreCase(cardName)) return true;
        }
        return false;
    }

    /** Returns {@code true} if the card text contains a "can form a party with Forwards of any Element" clause. */
    public static boolean parseCanFormPartyAnyElement(String textEn) {
        if (textEn == null || textEn.isBlank()) return false;
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (PARTY_ANY_ELEMENT_PATTERN.matcher(seg).find()) return true;
        }
        return false;
    }

    /**
     * Parses all "The [filter] Forwards you control can form a party with … Forwards of any Element."
     * field-ability grants into a list of {@link FieldPartyAnyElement} records.
     */
    public static List<FieldPartyAnyElement> parseFieldPartyAnyElements(String textEn, String cardType) {
        if (textEn == null || textEn.isBlank()) return List.of();
        if ("Summon".equalsIgnoreCase(cardType)) return List.of();
        List<FieldPartyAnyElement> result = new ArrayList<>();
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            Matcher m = FIELD_PARTY_ANY_ELEMENT_PATTERN.matcher(seg);
            if (!m.find()) continue;
            String job      = m.group("job")      != null ? m.group("job").trim()      : null;
            String category = m.group("category") != null ? m.group("category").trim() : null;
            String cardname = m.group("cardname") != null ? m.group("cardname").trim() : null;
            result.add(new FieldPartyAnyElement(job, category, cardname));
        }
        return List.copyOf(result);
    }

    // -------------------------------------------------------------------------
    // Field Ability parsing
    // -------------------------------------------------------------------------

    /**
     * Matches a segment that consists solely of trait keyword(s) — possibly followed
     * by a parenthetical description — with no other content.
     * Covers: Haste, Brave, First Strike, Back Attack (alone or combined),
     * Warp N -- costs, Priming "name" -- costs.
     */
    private static final Pattern FA_TRAIT_KEYWORD = Pattern.compile(
        "(?i)^(?:" +
        "(?:(?:Haste|Brave|First\\s+Strike|Back\\s+Attack)(?:\\s+(?:Haste|Brave|First\\s+Strike|Back\\s+Attack))*)" +
        "|Warp\\s+\\d+\\s*--\\s*(?:《[^》]*》\\s*)*" +
        "|Priming\\s+\"[^\"]+\"\\s*--\\s*(?:《[^》]*》\\s*)*" +
        ")(?:\\s*\\([^)]*\\))*\\s*$"
    );

    /**
     * Matches the "When " prefix common to all Auto abilities and Warp Counter triggers.
     * The optional "EX BURST " prefix covers cards whose EX Burst text is stored inline
     * without [[ex]]…[[/]] tag delimiters (so EX_BURST_TAG does not strip it).
     * Used to exclude auto-ability segments from field-ability parsing.
     *
     * <p>"Whenever" is the same trigger word — {@link #AUTO_ABILITY_PATTERN} has always accepted
     * both — and it has to be excluded here for the same reason. Without it Rosa 2-143R's
     * "Whenever a Forward you control is chosen by your opponent's Summon, …" was emitted as a
     * field ability <em>as well as</em> the auto-ability that actually runs it, and showed up in
     * the field-ability report as unwired work that was in fact already done.
     */
    /**
     * The trigger words that open an auto-ability sentence, so {@link #parseFieldAbilities} leaves
     * it to {@link #parseAutoAbilities} instead of listing it as an unparsed field ability. Kept in
     * step with {@link #AUTO_ABILITY_PATTERN}'s opener — "Each time" is Cloud 1-187S's, and adding
     * it to one without the other would have the segment claimed twice.
     */
    private static final Pattern FA_AUTO_PREFIX =
            Pattern.compile("(?i)^(?:EX\\s+BURST\\s+)?(?:When(?:ever)?|Each\\s+time)\\s+");

    /**
     * Matches a "Damage N -- " threshold prefix at the start of a {@code [[br]]}-delimited
     * segment.  Group 1 captures the numeric threshold value.
     */
    private static final Pattern DAMAGE_THRESHOLD_PREFIX = Pattern.compile(
        "(?i)^Damage\\s+(\\d+)\\s+--\\s+"
    );

    /**
     * Matches standalone restriction sentences that trail action or auto abilities but
     * may appear as their own {@code [[br]]}-delimited segment in the card text.
     * Examples:
     * <ul>
     *   <li>"You can only use this ability once per turn."</li>
     *   <li>"You can only use this ability during your turn."</li>
     *   <li>"You can only pay this cost with CP produced by Backups."</li>
     *   <li>"This effect will trigger only once per turn."</li>
     * </ul>
     */
    private static final Pattern FA_RESTRICTION_SENTENCE = Pattern.compile(
        "(?i)^(?:" +
        "You\\s+can(?:\\s+only)?\\s+use\\s+this\\s+ability" +
        "|You\\s+can\\s+only\\s+pay\\s+this\\s+cost" +
        "|You\\s+can\\s+only\\s+pay\\s+with\\s+CP\\s+produced\\s+by\\s+(?:(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?Backups" +
        "|You\\s+can\\s+only\\s+pay\\s+with\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+CP\\s+to\\s+cast\\b" +
        "|You\\s+can\\s+only\\s+pay\\s+with\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+CP\\s+to\\s+use" +
        "|This\\s+effect\\s+will\\s+trigger" +
        ")"
    );
    // Note: OPP_DISCARD_THIS_TURN_PATTERN starts with "You can only use this ability" and is therefore
    // already matched by FA_RESTRICTION_SENTENCE above — no additional entry needed.

    /**
     * Matches "You can only pay with CP produced by [Element] Backups to cast/play [CardName]…"
     * capturing the optional {@code element} group.
     * Covers both "to cast [Name]" and "to play [Name] from your hand onto the field".
     */
    private static final Pattern CP_BACKUP_ONLY_CAST = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+pay\\s+with\\s+CP\\s+produced\\s+by\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "Backups\\s+to\\s+(?:cast|play)\\b"
    );

    /**
     * Matches "You can only pay with [Element] CP to cast [CardName]."
     * Unlike {@link #CP_BACKUP_ONLY_CAST}, this restricts the element of CP (not the source type).
     */
    private static final Pattern CAST_ELEMENT_ONLY = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+pay\\s+with\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+CP\\s+to\\s+cast\\b"
    );

    /**
     * Matches "You can only pay with CP produced by [Element] Backups to use this ability"
     * capturing the optional {@code element} group.
     */
    static final Pattern CP_BACKUP_ONLY_ABILITY = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+pay\\s+with\\s+CP\\s+produced\\s+by\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "Backups\\s+to\\s+use\\s+this\\s+ability[.!]?"
    );

    /**
     * Matches "You can only pay with [Elem] CP[, [Elem] CP][, or [Elem] CP] to use this ability."
     * Captures up to three elements in groups {@code elem1}, {@code elem2}, {@code elem3}.
     */
    static final Pattern CP_ELEMENTS_ONLY_ABILITY = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+pay\\s+with\\s+" +
        "(?<elem1>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+CP" +
        "(?:,\\s+(?<elem2>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+CP)?" +
        "(?:,?\\s+or\\s+(?<elem3>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+CP)?" +
        "\\s+to\\s+use\\s+this\\s+ability[.!]?"
    );

    /** Matches "[CardName] has all the jobs." as a field ability. */
    static final Pattern HAS_ALL_JOBS_PATTERN = Pattern.compile(
        "(?i)^.+?\\s+has\\s+all\\s+the\\s+jobs\\.?$"
    );

    /** Matches "[CardName] has the Jobs of the Forwards you control." as a field ability. */
    private static final Pattern HAS_JOBS_OF_FORWARDS_PATTERN = Pattern.compile(
        "(?i)^.+?\\s+has\\s+the\\s+Jobs\\s+of\\s+the\\s+Forwards\\s+you\\s+control\\.?$"
    );

    /** Matches "If [name] has N Jobs? or more, [name] gains [traits]." */
    static final Pattern IF_SELF_JOB_COUNT_TRAIT_GRANT = Pattern.compile(
        "(?i)^If\\s+(?<n1>.+?)\\s+has\\s+(?<n>\\d+)\\s+Jobs?\\s+or\\s+more,\\s+(?<n2>.+?)\\s+gains?\\s+" +
        "(?<traits>(?:(?:Haste|First\\s+Strike|Brave|Back\\s+Attack)(?:\\s*(?:,|and)\\s*)?)+)[.!]?\\s*$"
    );

    /**
     * Matches "If [name] has N power or more, [name] gains [traits]." — Ramza 7-104H, which prints
     * three of these at 4000/6000/8000 for Haste, Brave and First Strike.
     *
     * <p>The threshold is read against the card's <em>current</em> power, not its printed one; that
     * is the whole point of the wording, and on Ramza the printed 2000 clears none of the three.
     *
     * <p>The trait list is anchored to the end of the sentence, so this claims only the grants that
     * are traits and nothing else. Three corpus printings open identically and then continue past
     * the keyword — Ramza 5-118L ("Haste and \"When Ramza attacks …\""), Gilgamesh 7-088L ("Brave
     * and can attack twice in the same turn") and Oschon 26-047H (two quoted abilities and no
     * keyword at all). Matching those on the trait half alone would grant the keyword and silently
     * drop the rest, so the anchor refuses them; they stay unhandled, which is what they were.
     */
    static final Pattern IF_SELF_POWER_TRAIT_GRANT = Pattern.compile(
        "(?i)^If\\s+(?<n1>.+?)\\s+has\\s+(?<n>\\d+)\\s+power\\s+or\\s+more,\\s+(?<n2>.+?)\\s+gains?\\s+" +
        "(?<traits>(?:(?:Haste|First\\s+Strike|Brave|Back\\s+Attack)(?:\\s*(?:,|and)\\s*)?)+)[.!]?\\s*$"
    );

    /**
     * The same grant with subject and power swapped: "If the power of [name] is N or more, [name]
     * gains [traits]." — Yang 2-090R, the only printing in the corpus to word it this way.
     *
     * <p>A sibling pattern rather than an alternation inside {@link #IF_SELF_POWER_TRAIT_GRANT}:
     * Java forbids repeating a group name, so folding the two openings into one regex would mean a
     * second set of capture names and a reader that has to ask which arm fired. The names here are
     * deliberately identical to that pattern's, so both accessors read whichever matched without
     * caring which it was.
     *
     * <p>The trait list is anchored to the end of the sentence for the same reason it is there: a
     * printing that continues past the keyword carries an effect this grant would silently drop.
     */
    static final Pattern IF_SELF_POWER_IS_TRAIT_GRANT = Pattern.compile(
        "(?i)^If\\s+the\\s+power\\s+of\\s+(?<n1>.+?)\\s+is\\s+(?<n>\\d+)\\s+or\\s+more,\\s+(?<n2>.+?)\\s+gains?\\s+" +
        "(?<traits>(?:(?:Haste|First\\s+Strike|Brave|Back\\s+Attack)(?:\\s*(?:,|and)\\s*)?)+)[.!]?\\s*$"
    );

    /** Matches "If there are N or more face-up cards in your LB deck, [name] gains [traits]." */
    static final Pattern IF_SELF_LB_FACEUP_COUNT_TRAIT_GRANT = Pattern.compile(
        "(?i)^If\\s+there\\s+are\\s+(?<n>\\d+)\\s+or\\s+more\\s+face[- ]up\\s+cards?\\s+in\\s+your\\s+LB\\s+deck,\\s+(?<cardname>.+?)\\s+gains?\\s+" +
        "(?<traits>(?:(?:Haste|First\\s+Strike|Brave|Back\\s+Attack)(?:\\s*(?:,|and)\\s*)?)+)[.!]?\\s*$"
    );

    /**
     * "Limit Break -- N", the cost declaration that opens a Limit Break card's text.
     *
     * <p>Not an ability: the ETL reads the value into {@code lb_cost} and the flag into
     * {@code limit_break}, so the sentence grants the card nothing and is dropped from
     * {@link #parseFieldAbilities} the way the alias and enters-dull declarations already are.
     * It was the last such declaration still leaking through — the sibling "Warp N -- 《cost》"
     * line and the parenthetical "(Cards with 《LB》 cannot be included…)" reminder are already
     * excluded, the latter by the leading-parenthesis rule further down this method.
     */
    private static final Pattern LIMIT_BREAK_DECLARATION = Pattern.compile(
        "(?i)^Limit\\s+Break\\s*--\\s*\\d+\\s*[.!]?$"
    );

    /**
     * Matches "[CardName] is also Card Name X [and Card Name Y ...] in all situations."
     * Group {@code names} captures the raw "Card Name A [and Card Name B]" list.
     */
    private static final Pattern IS_ALSO_CARD_NAME_PATTERN = Pattern.compile(
        "(?i)^.+?\\s+is\\s+also\\s+(?<names>Card\\s+Name\\s+.+?)\\s+in\\s+all\\s+situations\\.?\\s*$",
        Pattern.DOTALL
    );

    /**
     * Matches "You cannot play {name1} or Card Name {name2} while already in control of either Character."
     * Group {@code name2} captures the Card Name that pairs with this card in the play restriction.
     */
    private static final Pattern ALIAS_PLAY_RESTRICTION_PATTERN = Pattern.compile(
        "(?i)^You\\s+cannot\\s+play\\s+(?<name1>.+?)\\s+or\\s+Card\\s+Name\\s+(?<name2>.+?)\\s+while\\s+already\\s+in\\s+control\\s+of\\s+either\\s+Character\\.?\\s*$"
    );

    /** Matches "[CardName] is also a Monster in all situations." */
    private static final Pattern IS_ALSO_MONSTER_PATTERN = Pattern.compile(
        "(?i)^.+?\\s+is\\s+also\\s+a\\s+Monster\\s+in\\s+all\\s+situations\\.?\\s*$"
    );

    /**
     * "[You may] put [Self] into the Break Zone to produce N CP of any Element [in order to pay a
     * CP cost]." — Sherlotta 8-053H and her reprint Re-066H, which word the same permission two
     * ways and mean the same thing.
     *
     * <p>Both spellings state that breaking her is <em>additional</em> to dulling her, one in the
     * lead-in ("If you pay a CP, …") and one in the parenthetical, so neither half is captured:
     * the reminder is text, and the rule it describes is that the break is a payment of its own.
     * The trailing parenthetical is matched and discarded for the same reason.
     * Groups: {@code cardname}, {@code count}.
     */
    private static final Pattern FIELD_BREAK_SELF_FOR_ANY_CP = Pattern.compile(
        "(?i)^(?:If\\s+you\\s+pay\\s+a\\s+CP,\\s+)?[Yy]ou\\s+may\\s+put\\s+(?<cardname>.+?)\\s+" +
        "into\\s+the\\s+Break\\s+Zone\\s+to\\s+produce\\s+(?<count>\\d+)\\s+CP\\s+of\\s+any\\s+Element" +
        "(?:\\s+in\\s+order\\s+to\\s+pay\\s+a\\s+CP\\s+cost)?\\s*[.!]?" +
        "(?:\\s*\\([^)]*\\))?\\s*$"
    );

    /**
     * How much CP breaking {@code cardName} produces under {@code effectText}, or 0 when the text
     * is not that permission or names another card.
     *
     * <p>Self-named like every other passive of this shape: the sentence is printed on the card it
     * spends, and a grant that named somebody else would be a different rule.
     */
    public static int parseBreakSelfForCpAmount(String effectText, String cardName) {
        if (effectText == null || cardName == null) return 0;
        Matcher m = FIELD_BREAK_SELF_FOR_ANY_CP.matcher(effectText.trim());
        if (!m.matches() || !m.group("cardname").trim().equalsIgnoreCase(cardName)) return 0;
        return Integer.parseInt(m.group("count"));
    }

    /**
     * "You can remove N [X] Counters from [Self] to use [Self]'s special ability without paying the
     * cost." — Wakka 16-138S, whose own auto ability stocks the Reel Counters this spends.
     *
     * <p>A third way to pay a Special, alongside the same-named discard and Glaciela Wezette
     * 17-113L's Crystal — and the widest of the three: "without paying the cost" waives the whole
     * cost rather than standing in for the 《S》 alone, which is why the reader hands the activation
     * a cost-stripped copy of the ability rather than a substitute payment.
     * Groups: {@code count}, {@code counter}, {@code cardname}, {@code owner}.
     */
    private static final Pattern FIELD_COUNTER_WAIVES_SPECIAL_COST = Pattern.compile(
        "(?i)^You\\s+can\\s+remove\\s+(?<count>\\d+)\\s+(?<counter>.+?)\\s+Counters?\\s+from\\s+" +
        "(?<cardname>.+?)\\s+to\\s+use\\s+(?<owner>.+?)'s\\s+special\\s+ability\\s+" +
        "without\\s+paying\\s+the\\s+cost[.!]?\\s*$"
    );

    /**
     * How many counters of what kind {@code cardName} may remove from itself to use its Special
     * ability for free, or {@code null} when {@code effectText} is not that permission.
     *
     * @param counterName the counter spent ("Reel")
     * @param count       how many the waiver costs
     */
    record SpecialCostCounterWaiver(String counterName, int count) {}

    /**
     * Parses a {@link SpecialCostCounterWaiver} from {@code effectText}, or {@code null} when the
     * text is not one or names another card.
     *
     * <p>Both names in the sentence are checked, not just the first: the counters come off the
     * carrier and the ability being paid for is the carrier's, and a printing that split the two
     * would be a different rule than the one this waiver implements.
     */
    static SpecialCostCounterWaiver parseSpecialCostCounterWaiver(String effectText, String cardName) {
        if (effectText == null || cardName == null) return null;
        Matcher m = FIELD_COUNTER_WAIVES_SPECIAL_COST.matcher(effectText.trim());
        if (!m.matches()) return null;
        if (!m.group("cardname").trim().equalsIgnoreCase(cardName)) return null;
        if (!m.group("owner").trim().equalsIgnoreCase(cardName)) return null;
        return new SpecialCostCounterWaiver(m.group("counter").trim(),
                Integer.parseInt(m.group("count")));
    }

    /** Matches "[CardName] enters the field dull." */
    private static final Pattern ENTERS_FIELD_DULL_PATTERN = Pattern.compile(
        "(?i)^.+?\\s+enters\\s+the\\s+field\\s+dull[.!]?\\s*$"
    );

    /**
     * "[Self] gains all the special abilities of the Job [Job] you own removed from the game."
     * — Clive 26-005H, whose Priming pile is Job Eikon.
     *
     * <p>A continuous grant, not a one-shot copy: the borrowed abilities are whatever is sitting in
     * the owner's removed-from-game zone at the moment the question is asked, so the reader is run
     * per query rather than at parse time. Group {@code cardname} is name-checked against the
     * carrier by {@link #parseRfgJobSpecialAbilityGrant}; {@code job} is the pile it opens.
     */
    private static final Pattern SELF_GAINS_RFG_JOB_SPECIAL_ABILITIES = Pattern.compile(
        "(?i)^(?<cardname>.+?)\\s+gains?\\s+all\\s+the\\s+special\\s+abilities\\s+of\\s+the\\s+" +
        "Job\\s+(?<job>[A-Za-z][A-Za-z''\\s\\-]*?)\\s+you\\s+own\\s+removed\\s+from\\s+the\\s+game[.!]?\\s*$"
    );

    /**
     * The Job whose removed-from-game Special abilities {@code effectText} hands {@code cardName},
     * or {@code null} when the text is not that grant or names another card.
     */
    static String parseRfgJobSpecialAbilityGrant(String effectText, String cardName) {
        if (effectText == null || cardName == null) return null;
        Matcher m = SELF_GAINS_RFG_JOB_SPECIAL_ABILITIES.matcher(effectText.trim());
        if (!m.matches() || !m.group("cardname").trim().equalsIgnoreCase(cardName)) return null;
        return m.group("job").trim();
    }

    /**
     * Matches "You may use [Target]'s special ability by discarding a[n] [Substitute] instead of
     * discarding a Card Name [Target] as part of the cost."
     * Groups: {@code target} — whose special ability; {@code subName} — substitute card name;
     * {@code subElem} — substitute element; {@code subType} — substitute card type.
     */
    private static final Pattern SPECIAL_ABILITY_PROXY_PATTERN = Pattern.compile(
        "(?i)^You\\s+may\\s+use\\s+(?<target>.+?)'s\\s+special\\s+ability" +
        "\\s+by\\s+discarding\\s+an?\\s+" +
        "(?:Card\\s+Name\\s+(?<subName>.+?)" +
        "|(?:(?<subElem>[A-Z]\\w+)\\s+)?(?<subType>Summon|Forward|Backup|Monster|Character))" +
        "\\s+instead\\s+of\\s+discarding\\s+a\\s+Card\\s+Name\\s+.+?\\s+as\\s+part\\s+of\\s+the\\s+cost\\.?\\s*$"
    );

    /**
     * Matches "If you control a Card Name [Require], you can discard [N] card instead of 《S》 when
     * paying for [Target]'s special ability." — Tifa 26-076H.
     *
     * <p>The board-conditioned relative of {@link #SPECIAL_ABILITY_PROXY_PATTERN}. That one widens
     * the S cost permanently and by card <em>kind</em> ("discarding an Earth Summon"); this one
     * widens it to any card at all, but only while its controller has the named card on the field.
     *
     * <p>The 《S》 markup reaches this intact — {@code SUMMON_MARKUP} strips only {@code [[…]]} tags
     * — so the guillemets are matched rather than assumed away, with the bare spelling accepted too
     * in case a reprint drops them.
     * Groups: {@code require} — the card name that must be on your field; {@code count} — how many
     * cards the alternative discards; {@code target} — whose special ability it pays for.
     */
    private static final Pattern SPECIAL_ABILITY_CONTROL_ANY_DISCARD_PATTERN = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+an?\\s+Card\\s+Name\\s+(?<require>.+?),\\s+" +
        "you\\s+can\\s+discard\\s+(?<count>\\d+)\\s+cards?\\s+instead\\s+of\\s+" +
        "(?:《S》|S)\\s+when\\s+paying\\s+for\\s+(?<target>.+?)'s\\s+special\\s+ability[.!]?\\s*$"
    );

    /**
     * Matches "You can pay with 《C》 instead of 《S》 when paying for the special abilities of
     * Category [X] Characters you control." — Glaciela Wezette 17-113L.
     *
     * <p>The third way the corpus widens an S cost, and the only one that leaves the hand out of it
     * altogether: the other two substitute a different card to discard
     * ({@link #SPECIAL_ABILITY_PROXY_PATTERN}, {@link #SPECIAL_ABILITY_CONTROL_ANY_DISCARD_PATTERN}),
     * while this one spends a Crystal instead. That is why it is not a
     * {@link SpecialAbilityProxy} — every field of that record describes a substitute discard.
     *
     * <p>It also names its beneficiaries by Category rather than by card name, covering every
     * Character of that Category its controller controls (the printing card included) instead of
     * one named target. {@code 《C》} reaches this intact for the reason the Tifa pattern documents,
     * and the bare spelling is accepted on the same grounds.
     * Group: {@code category}.
     */
    private static final Pattern CRYSTAL_PAYS_SPECIAL_COST_PATTERN = Pattern.compile(
        "(?i)^You\\s+can\\s+pay\\s+with\\s+(?:《C》|C)\\s+instead\\s+of\\s+(?:《S》|S)\\s+" +
        "when\\s+paying\\s+for\\s+the\\s+special\\s+abilit(?:y|ies)\\s+of\\s+" +
        "Category\\s+(?<category>.+?)\\s+Characters?\\s+you\\s+control[.!]?\\s*$"
    );

    /**
     * The Category whose Characters may pay an 《S》 cost with a Crystal under {@code seg}, or
     * {@code null} when {@code seg} is not that ability.
     *
     * <p>Static and segment-scoped like {@link #parseSpecialAbilityProxy}, because the reader is a
     * board sweep: the grant lives on one card and applies to others, so the caller holds field
     * abilities from every card its controller controls rather than from a single {@code CardData}.
     */
    public static String parseCrystalPaysSpecialCostCategory(String seg) {
        if (seg == null || seg.isBlank()) return null;
        Matcher m = CRYSTAL_PAYS_SPECIAL_COST_PATTERN.matcher(seg.trim());
        return m.matches() ? m.group("category").trim() : null;
    }

    /** Matches "The opponent's Forwards enter the field dull." */
    static final Pattern OPPONENT_FORWARDS_ENTER_DULL_PATTERN = Pattern.compile(
        "(?i)^(?:the\\s+)?(?:your\\s+)?opponent'?s?\\s+Forwards?\\s+enters?\\s+the\\s+field\\s+dull[.!]?\\s*$"
    );

    /** Returns {@code true} if {@code seg} is an "opponent's Forwards enter the field dull" ability. */
    public static boolean parseOpponentForwardsEnterDull(String seg) {
        if (seg == null || seg.isBlank()) return false;
        return OPPONENT_FORWARDS_ENTER_DULL_PATTERN.matcher(seg.trim()).matches();
    }

    /** Returns {@code true} if this card has an "opponent's Forwards enter the field dull" field ability. */
    public boolean opponentForwardsEnterFieldDull() {
        for (String seg : rawFieldSegments())
            if (parseOpponentForwardsEnterDull(seg)) return true;
        return false;
    }

    /**
     * Matches "All the Forwards other than [Name] enter the field dull." — Ultimecia 1-152L.
     * The both-sides twin of {@link #OPPONENT_FORWARDS_ENTER_DULL_PATTERN}: naming no controller,
     * it dulls every Forward that enters on either side except the ones the exception names.
     * Group: {@code except}.
     */
    static final Pattern ALL_FORWARDS_EXCEPT_ENTER_DULL_PATTERN = Pattern.compile(
        "(?i)^All\\s+the\\s+Forwards?\\s+other\\s+than\\s+(?<except>.+?)\\s+enters?\\s+the\\s+field\\s+dull[.!]?\\s*$"
    );

    /**
     * The card name spared by an "All the Forwards other than X enter the field dull." ability, or
     * {@code null} when {@code seg} is not one.
     */
    public static String parseAllForwardsExceptEnterDull(String seg) {
        if (seg == null || seg.isBlank()) return null;
        Matcher m = ALL_FORWARDS_EXCEPT_ENTER_DULL_PATTERN.matcher(seg.trim());
        return m.matches() ? m.group("except").trim() : null;
    }

    /**
     * The card name this card's "All the Forwards other than X enter the field dull." ability
     * spares, or {@code null} when it prints none.
     *
     * <p>The exception is carried as a name rather than reduced to "except me": card text names
     * cards by Card Name, so a second copy of Ultimecia entering is spared too, and a future
     * printing that named somebody else would not silently become a self-reference.
     */
    public String allForwardsEnterFieldDullExcept() {
        for (String seg : rawFieldSegments()) {
            String except = parseAllForwardsExceptEnterDull(seg);
            if (except != null) return except;
        }
        return null;
    }

    /**
     * Matches "During your turn, [CardName] also becomes a Forward with [X] power."
     * Group {@code power} captures the numeric power value.
     * Trailing text (e.g. embedded auto-abilities) is allowed after the power clause.
     */
    private static final Pattern BECOME_FORWARD_DURING_TURN_PATTERN = Pattern.compile(
        "(?i)^During\\s+your\\s+turn,\\s+.+?\\s+also\\s+becomes?\\s+a\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power"
    );

    /**
     * Matches "If you control N or more Monsters, [CardName] also becomes a Forward with P power."
     * Groups: {@code n} — minimum monster count; {@code power} — Forward power value.
     */
    private static final Pattern BECOME_FORWARD_IF_CONTROL_N_MONSTERS_PATTERN = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<n>\\d+)\\s+or\\s+more\\s+Monsters?,\\s+.+?\\s+also\\s+becomes?\\s+a\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power"
    );

    /**
     * Matches "[CardName] also becomes a Forward with [X] power." with no leading condition.
     * Used to detect the damage-threshold variant after "Damage N -- " has been stripped.
     * Group {@code power} captures the numeric power value.
     */
    private static final Pattern BECOME_FORWARD_UNCONDITIONAL_PATTERN = Pattern.compile(
        "(?i)^.+?\\s+also\\s+becomes?\\s+a\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power"
    );

    /**
     * Matches "[CardName] has all the Elements [except X[, Y, ...]]." as a field ability.
     * Group {@code exceptions} captures the comma- or "and"-separated exclusion list, if any.
     */
    static final Pattern HAS_ALL_ELEMENTS_PATTERN = Pattern.compile(
        "(?i)^.+?\\s+has\\s+all\\s+the\\s+Elements?(?:\\s+except\\s+(?<exceptions>[^.]+))?\\.?$"
    );

    /**
     * Matches "[CardName] gains Elements of all the Characters opponent controls except X[, Y]."
     * (Kimahri 1-103C.) Group {@code exceptions} captures the exclusion list, if any.
     *
     * <p>Unlike {@link #HAS_ALL_ELEMENTS_PATTERN}, which names a fixed set, this one is a
     * standing query over the opposing board: the Elements it grants change as the opponent's
     * Characters come and go, so it cannot be resolved on the card and is answered per lookup by
     * {@code MainWindow.effectiveElements}.
     */
    static final Pattern GAINS_OPP_CHARACTER_ELEMENTS_PATTERN = Pattern.compile(
        "(?i)^(?<name>[^.!]+?)\\s+gains\\s+Elements\\s+of\\s+all\\s+the\\s+Characters\\s+" +
        "opponent\\s+controls(?:\\s+except\\s+(?<exceptions>[^.]+))?\\.?$"
    );

    /**
     * The Elements excluded from a "gains Elements of all the Characters opponent controls except X"
     * field ability, an empty set when it names no exclusions, or {@code null} when this card has
     * no such ability. The name is checked against the card, so a quoted copy of the sentence does
     * not grant it to whoever prints the quote.
     */
    public java.util.Set<String> gainsOpponentCharacterElementsExcept() {
        for (FieldAbility fa : fieldAbilities()) {
            Matcher m = GAINS_OPP_CHARACTER_ELEMENTS_PATTERN.matcher(fa.effectText().trim());
            if (!m.matches() || !m.group("name").trim().equalsIgnoreCase(name)) continue;
            String raw = m.group("exceptions");
            if (raw == null || raw.isBlank()) return java.util.Set.of();
            return java.util.Arrays.stream(raw.split(",\\s*|\\s+and\\s+"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toSet());
        }
        return null;
    }

    /**
     * Matches "[CardName] can be played onto the field even if you control other [Light|Dark] Characters."
     * as a self-exception field ability. Groups: {@code name}, {@code element}.
     */
    static final Pattern SELF_LIGHT_DARK_PLAY_EXCEPTION_PATTERN = Pattern.compile(
        "(?i)^(?<name>.+?)\\s+can be played onto the field even if you control other (?<element>Light|Dark) Characters\\.?$"
    );

    /**
     * Matches "You can play 2 or more [Light|Dark] Characters onto the field."
     * as a field-wide multi-play grant ability. Group: {@code element}.
     */
    static final Pattern MULTI_LIGHT_DARK_PLAY_PATTERN = Pattern.compile(
        "(?i)^You can play 2 or more (?<element>Light|Dark) Characters onto the field\\.?$"
    );

    /**
     * Matches "You can play 2 or more Card Name X onto the field."
     * as a field-wide name-specific multi-play grant. Group: {@code cardname}.
     */
    static final Pattern MULTI_NAME_PLAY_PATTERN = Pattern.compile(
        "(?i)^You can play 2 or more Card Name (?<cardname>.+?) onto the field\\.?$"
    );

    /**
     * Matches "You can discard [Light and Dark|Light|Dark] Element cards from your hand to
     * produce CP." (with optional reminder text) as a field-wide payment grant ability.
     * Groups: {@code e1} and, for the "X and Y" form, {@code e2}.
     */
    static final Pattern LIGHT_DARK_DISCARD_CP_PATTERN = Pattern.compile(
        "(?i)^You can discard (?<e1>Light|Dark)(?: and (?<e2>Light|Dark))? Element cards " +
        "from your hand to produce CP\\.?(?:\\s*\\([^)]*\\))?$"
    );

    /**
     * Parses all Field Abilities from {@code textEn} by exclusion:
     * any {@code [[br]]}-delimited segment that is not a trait keyword, an Auto ability,
     * an Action ability, an alternate-cost declaration, or an ability restriction sentence
     * is a Field ability.
     *
     * <p>Summon cards have no field abilities — their card text is a one-time effect — so
     * passing {@code "Summon"} as {@code cardType} always returns an empty list.
     *
     * <p>The returned list is immutable.
     */
    public static List<FieldAbility> parseFieldAbilities(String textEn, String cardType) {
        if (textEn == null || textEn.isBlank()) return List.of();
        if ("Summon".equalsIgnoreCase(cardType))  return List.of();

        // Remove EX Burst block entirely — it is either an action ability or a summon effect
        String text = EX_BURST_TAG.matcher(textEn).replaceAll(" ");
        // Join "select N of M following actions" headers with their quoted sub-actions so the
        // whole ability collapses into a single [[br]]-delimited segment (same as auto-ability parsing).
        text = joinSelectActions(text);

        List<FieldAbility> result = new ArrayList<>();
        for (String raw : text.split("(?i)\\[\\[br\\]\\]")) {
            String rawTrimmed = raw.trim();
            if (rawTrimmed.isEmpty()) continue;

            // Action abilities: check raw text (preserves [[s]]…[[/]] markup the pattern needs)
            if (ACTION_ABILITY_PATTERN.matcher(rawTrimmed).find()) continue;

            // Strip remaining markup tags for the checks below
            String seg = SUMMON_MARKUP.matcher(rawTrimmed).replaceAll("").trim();
            if (seg.isEmpty()) continue;

            // Damage threshold prefix: "Damage N -- rest" — strip prefix and record threshold,
            // then re-apply the exclusion checks on the bare ability text.
            int damageThreshold = 0;
            Matcher dtM = DAMAGE_THRESHOLD_PREFIX.matcher(seg);
            if (dtM.find()) {
                damageThreshold = Integer.parseInt(dtM.group(1));
                seg = seg.substring(dtM.end()).trim();
                if (seg.isEmpty()) continue;
            }

            // Alternate-cost declarations
            if (ALT_COST_SUMMON.matcher(seg).find())    continue;
            if (ALT_COST_SUMMON_REMOVE_FIELD.matcher(seg).find()) continue;
            if (ALT_COST_NONSUMMON.matcher(seg).find()) continue;
            if (ALT_COST_DULL.matcher(seg).find())      continue;
            if (ALT_COST_PUT_TO_BZ.matcher(seg).find()) continue;
            // "If you cast [card], you may pay 《…》 as an extra cost." — a cast-time option read
            // by {@link #extraCost}, not a field ability. It has no continuous effect of its own;
            // what it does is set the flag a later "if you paid the extra cost" clause reads.
            if (EXTRA_COST_SUMMON.matcher(seg).find())  continue;

            // Auto abilities: "When [card/event] [trigger], [effect]" and phase-trigger patterns
            if (FA_AUTO_PREFIX.matcher(seg).find()) continue;
            if (AT_BEGINNING_OF_ATTACK_PHASE_PATTERN.matcher(seg).find()) continue;
            if (AT_BEGINNING_OF_ATTACK_PHASE_EACH_TURN_PATTERN.matcher(seg).find()) continue;
            // Opens with "During", so FA_AUTO_PREFIX above does not see the "when … is chosen by"
            // trigger it carries; without this line the auto-ability it produces would be printed a
            // second time here as a field ability.
            if (DURING_EACH_TURN_CHOSEN_FIRST_TIME_PATTERN.matcher(seg).find()) continue;
            // Same shape and same reason: the ordinal cast trigger opens on "During" (Shikaree G
            // 15-051C, Atomos 16-043H, Belgemine 24-052L) or states its window at the end
            // (Rosa 14-057H), so neither reaches FA_AUTO_PREFIX above.
            if (DURING_EACH_TURN_NTH_CAST_PATTERN.matcher(seg).find()) continue;
            // Same shape, same reason: Lunafreya 8-132L opens on "During your opponent's turn," and
            // the trigger behind it is parsed as an auto ability, so the segment is not also a
            // standing one.
            if (DURING_OPP_TURN_TRIGGER_PATTERN.matcher(seg).find()) continue;
            // Quote-aware for the same reason as the end-of-turn line below: Titan (XVI) 29-068L
            // prints this trigger inside a grant, and that segment stays a field ability.
            if (matchesOutsideQuotes(AT_BEGINNING_OF_OPP_ATTACK_PHASE_PATTERN, seg)) continue;
            // Quote-aware: Vayne 9-022L's grant prints this trigger inside quotes, and that segment
            // must stay a field ability — it is the grant, not an end-of-turn ability of Vayne's own.
            if (matchesOutsideQuotes(ActionResolverPatterns.AT_END_OF_EACH_TURN_PATTERN, seg)) continue;
            if (ActionResolverPatterns.AT_BEGINNING_OF_MAIN_PHASE_1_PATTERN.matcher(seg).find()) continue;
            if (ActionResolverPatterns.AT_BEGINNING_OF_MAIN_PHASE_2_PATTERN.matcher(seg).find()) continue;
            if (ActionResolverPatterns.AT_BEGINNING_OF_MAIN_PHASE_1_EACH_TURN_PATTERN.matcher(seg).find()) continue;
            // Quote-aware like the two above it: Sabin 15-018C hands this exact trigger to himself
            // inside a grant, and that segment is an "enters the field" ability rather than a
            // standing one of his own.
            if (matchesOutsideQuotes(
                    ActionResolverPatterns.AT_BEGINNING_OF_MAIN_PHASE_EACH_YOUR_TURN_PATTERN, seg)) continue;
            if (matchesOutsideQuotes(
                    ActionResolverPatterns.AT_BEGINNING_OF_MAIN_PHASE_1_EACH_PLAYERS_TURN_PATTERN, seg)) continue;
            if (ActionResolverPatterns.AT_BEGINNING_OF_OPP_MAIN_PHASE_1_PATTERN.matcher(seg).find()) continue;
            if (ActionResolverPatterns.AT_END_OF_OPP_TURN_PATTERN.matcher(seg).find()) continue;
            if (ActionResolverPatterns.AT_END_OF_EACH_PLAYERS_TURN_PATTERN.matcher(seg).find()) continue;

            // Quoted sub-action lines from "select the following actions" auto-abilities
            if (seg.startsWith("\"")) continue;

            // Trait keyword segments (Haste, Brave, Warp N, Priming "…", etc.)
            if (FA_TRAIT_KEYWORD.matcher(seg).find()) continue;

            // Parenthetical trait descriptions like "(This Forward can attack…)"
            if (seg.startsWith("(")) continue;

            // Standalone restriction sentences that trail action/auto abilities
            if (FA_RESTRICTION_SENTENCE.matcher(seg).find()) continue;

            // Extra-element CP production — handled as a static card property
            if (BACKUP_CP_EXTRA_ELEMENTS.matcher(seg).find())               continue;
            if (BACKUP_GAINS_ELEMENTS.matcher(seg).find())                  continue;

            // Scaling self power boost ("For each Forward opponent controls, X gains +N power")
            if (SCALING_SELF_OPP_FWD_PATTERN.matcher(seg).find())            continue;
            // Scaling self power boost ("For each Backup opponent controls, X gains +N power")
            if (SCALING_SELF_OPP_BACKUP_PATTERN.matcher(seg).find())         continue;
            // Scaling self power boost ("For each dull Character opponent controls, X gains +N power")
            if (SCALING_SELF_OPP_DULL_CHARACTER_PATTERN.matcher(seg).find()) continue;
            // Scaling self power boost ("For each [filter] you control, X gains +N power")
            if (SCALING_SELF_FOR_EACH_PATTERN.matcher(seg).find())            continue;
            // Scaling self power boost ("For each point of damage you have received, X gains +N power")
            if (SCALING_SELF_DMG_PATTERN.matcher(seg).find())                 continue;
            // Scaling self power boost ("For each Card Name X in your Break Zone, X gains +N power")
            if (SCALING_SELF_BZ_CARD_NAME_PATTERN.matcher(seg).find())        continue;
            // Scaling self power boost ("For each X Counter placed on Self, Self gains +N power")
            if (SCALING_SELF_COUNTER_PATTERN.matcher(seg).find())             continue;
            if (SCALING_SELF_REMOVED_BY_OWN_ABILITY_PATTERN.matcher(seg).find()) continue;
            // Scaling self power boost ("For every N Summons in your Break Zone, X gains +P power")
            if (SCALING_SELF_BZ_SUMMON_EVERY_N_PATTERN.matcher(seg).find())   continue;
            // Scaling self power boost ("For each card in your hand, X gains +N power")
            if (SCALING_SELF_HAND_PATTERN.matcher(seg).find())                 continue;
            // BZ-conditional / distinct-element-conditional / EX Burst scaling passive grant — handled by parseFieldPowerGrants
            if (FIELD_GRANT_BZ_COND_CN_AND_JOB_PATTERN.matcher(seg).find())       continue;
            if (FIELD_EX_BURST_DMG_SCALING_GRANT.matcher(seg).matches())           continue;
            if (FIELD_GRANT_BZ_JOB_SELF_PATTERN.matcher(seg).matches())            continue;
            if (FIELD_GRANT_BZ_CARD_NAME_SELF_PATTERN.matcher(seg).matches())      continue;
            if (IF_DIFF_ELEMENTS_FIELD_GRANT.matcher(seg).matches())               continue;
            if (FIELD_GRANT_DAMAGE_THRESHOLD_PATTERN.matcher(seg).matches())       continue;

            // Cast/play restrictions — handled as static properties via castRestriction()
            if (CAST_PROHIBITED.matcher(seg).find())                          continue;
            if (CAST_REQUIRES_NO_FORWARDS.matcher(seg).find())                continue;
            if (CAST_REQUIRES_FORWARD_PUT_TO_BZ.matcher(seg).find())          continue;
            if (CAST_REQUIRES_EITHER_PLAYER_DAMAGE.matcher(seg).find())      continue;
            // The three conditional forms that print as their own sentence (Gogo 27-099H,
            // Titania 13-132S, Eiko 23-124L). "during your turn" and "during your Main Phase"
            // are deliberately absent: those wordings occur as a *prefix* on a longer ability,
            // and a find() exclusion here would swallow the ability with them.
            if (CAST_OPPONENT_TURN_ONLY.matcher(seg).find())                  continue;
            if (CAST_REQUIRES_BZ_TYPES.matcher(seg).find())                   continue;
            if (CAST_MIN_BZ_RFP_SUMMONS.matcher(seg).find())                  continue;
            if (CAST_MUST_CONTROL.matcher(seg).find())                        continue;
            if (CAST_MUST_CONTROL_FORWARD_COUNT.matcher(seg).find())          continue;
            if (CAST_MUST_CONTROL_COSTS.matcher(seg).find())                  continue;
            if (CAST_MUST_CONTROL_CATEGORY_FWD.matcher(seg).find())          continue;
            if (CAST_ONLY_PLAY_IF_CONTROL_CATEGORY_FWD.matcher(seg).find())  continue;
            if (CAST_MAX_OPPONENT_HAND.matcher(seg).find())                   continue;
            // "You cannot play X [from your hand] due to Summons or abilities." — read at
            // effect-play time via playByEffectProhibited, not an ability of its own.
            if (PLAY_BY_EFFECT_PROHIBITED_FROM_HAND.matcher(seg).find())      continue;
            if (PLAY_BY_EFFECT_PROHIBITED_ANY_ZONE.matcher(seg).find())       continue;

            // Field cost reduction / any-element declarations — handled as static card properties
            if (FIELD_COST_REDUCTION_PATTERN.matcher(seg).find())            continue;
            if (FIELD_PLAY_COST_REDUCTION_PATTERN.matcher(seg).find())       continue;
            if (FIELD_CONDITIONAL_COST_REDUCTION_PATTERN.matcher(seg).find()) continue;
            // Self-cost modifier ("If <cond>, the cost required to cast/play <cardName>…") — handled via parseSelfCostModifiers
            if (isSelfCostModifierText(seg))                                  continue;
            if (FIELD_CAST_COST_INCREASE_PATTERN.matcher(seg).find())       continue;
            if (FIELD_CONDITIONAL_BZ_JOB_ANY_ELEMENT_PATTERN.matcher(seg).find()) continue;
            if (FIELD_CAST_ANY_ELEMENT_PATTERN.matcher(seg).find())          continue;
            if (FIELD_PRIMING_ANY_ELEMENT_PATTERN.matcher(seg).find())       continue;
            if (WARP_ANY_ELEMENT_PATTERN.matcher(seg).find())                continue;
            if (PARTY_ANY_ELEMENT_PATTERN.matcher(seg).find())               continue;
            if (FIELD_PARTY_ANY_ELEMENT_PATTERN.matcher(seg).find())        continue;

            // Limit Break cost declaration — handled as a static card property
            if (LIMIT_BREAK_DECLARATION.matcher(seg).matches())             continue;

            // Name/type alias declarations and enter-dull — handled as static card properties
            if (IS_ALSO_CARD_NAME_PATTERN.matcher(seg).find())              continue;
            if (IS_ALSO_MONSTER_PATTERN.matcher(seg).find())                continue;
            if (ENTERS_FIELD_DULL_PATTERN.matcher(seg).matches())           continue;
            if (ALIAS_PLAY_RESTRICTION_PATTERN.matcher(seg).matches())      continue;
            if (SPECIAL_ABILITY_PROXY_PATTERN.matcher(seg).matches())       continue;
            if (SPECIAL_ABILITY_CONTROL_ANY_DISCARD_PATTERN.matcher(seg).matches()) continue;
            if (BECOME_FORWARD_IF_CONTROL_N_MONSTERS_PATTERN.matcher(seg).find()) continue;
            if (BECOME_FORWARD_DURING_TURN_PATTERN.matcher(seg).find())       continue;
            if (BECOME_FORWARD_UNCONDITIONAL_PATTERN.matcher(seg).find())     continue;
            if (FIELD_CAN_ATTACK_TWICE.matcher(seg).matches())               continue;
            if (FIELD_GRANT_CARD_NAME_TRAIT_YOUR_TURN.matcher(seg).matches()) continue;
            if (FIELD_GRANT_CARD_NAME_TRAIT_ALWAYS.matcher(seg).matches())    continue;

            List<String> cannotClauses = splitCompoundCannotClauses(seg);
            if (cannotClauses != null) {
                for (String clause : cannotClauses) result.add(new FieldAbility(clause, damageThreshold));
            } else {
                result.add(new FieldAbility(seg, damageThreshold));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Matches a compound protection sentence
     * "X cannot A, cannot B[, and cannot C] [(reminder text)]." — a single subject with
     * multiple joined "cannot" clauses (e.g. Black Tortoise l'Cie Gilgamesh).
     */
    private static final Pattern FA_COMPOUND_CANNOT_CLAUSES = Pattern.compile(
        "(?i)^(?<subject>[A-Za-z''\\-\\s]+?)\\s+(?<first>cannot\\s+[^,]+?)" +
        "(?<rest>(?:,\\s*(?:and\\s+)?cannot\\s+[^,]+?)+)\\s*(?:\\([^)]*\\))?\\s*\\.?$"
    );

    /**
     * Splits "X cannot A, cannot B, and cannot C (reminder)." into individual sentences
     * ("X cannot A.", "X cannot B.", "X cannot C.") so each protection clause is parsed and
     * evaluated on its own. Returns {@code null} when {@code seg} is not a compound-cannot
     * sentence (fewer than two "cannot" clauses).
     */
    private static List<String> splitCompoundCannotClauses(String seg) {
        Matcher m = FA_COMPOUND_CANNOT_CLAUSES.matcher(seg.trim());
        if (!m.matches()) return null;
        String subject = m.group("subject").trim();
        List<String> out = new ArrayList<>();
        out.add(subject + " " + m.group("first").trim() + ".");
        for (String part : m.group("rest").split("(?i),\\s*(?:and\\s+)?(?=cannot\\s)")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            out.add(subject + " " + part + ".");
        }
        return out;
    }

    private static final Pattern COUNTER_COST_PATTERN = Pattern.compile(
        "(?i)remove\\s+(?<n>\\d+|X)\\s+(?<name>.+?)\\s+Counters?\\s+from\\s+(?<card>[^,:.]+?)\\s*$"
    );

    /**
     * Parses "remove [N|X] [Name] Counter(s) from [CardName]" into a {@link CounterCost} list.
     * The {@code X} form defers the amount to activation time — see {@link CounterCost#variable}.
     */
    private static List<CounterCost> parseCounterCosts(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        Matcher m = COUNTER_COST_PATTERN.matcher(raw.trim());
        if (!m.find()) return List.of();
        String  n           = m.group("n");
        boolean variable    = "X".equalsIgnoreCase(n);
        String  counterName = m.group("name").trim();
        String  cardName    = m.group("card").trim();
        return List.of(new CounterCost(cardName, counterName,
                variable ? 0 : Integer.parseInt(n), variable));
    }

    /** Parses one or more dull-forward cost items from the raw {@code dullcost} group string.
     *  Handles "Dull N [cond] [elem] Forward(s)", "Dull N [cond] Card Name X Forward [and ...]",
     *  and the bare-name form "Dull [cond] CardName [and N [cond] ...]". */
    private static List<DullForwardCost> parseDullForwardCosts(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<DullForwardCost> costs = new ArrayList<>();

        // "Dull [cond] BareCardName [and N [cond] continuation]" — implicit count=1, no "Card Name" prefix.
        // Guard: barename must not be a game type/element keyword that belongs in the standard branch.
        Matcher bareM = DULL_BARE_NAME_COST_PATTERN.matcher(raw.trim());
        if (bareM.matches()) {
            String barename = bareM.group("barename");
            boolean isKeyword = barename.matches(
                    "(?i)Forwards?|Backups?|Monsters?|Characters?|Summons?|Category|Job" +
                    "|Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark");
            if (!isKeyword) {
                costs.add(new DullForwardCost(1,
                        bareM.group("cond") != null ? bareM.group("cond").toLowerCase() : null,
                        null, barename.trim(), null, null, null, null));
                String cont = bareM.group("continuation");
                if (cont != null && !cont.isBlank()) {
                    // Prepend "Dull " so the continuation matches DULL_COST_ITEM_PATTERN normally
                    Matcher contM = DULL_COST_ITEM_PATTERN.matcher("Dull " + cont.trim());
                    if (contM.find()) {
                        int    count     = contM.group("count") != null ? Integer.parseInt(contM.group("count")) : 1;
                        String cond      = contM.group("cond");
                        String cardName  = contM.group("cardname");
                        String elem      = contM.group("elem");
                        String job       = contM.group("job");
                        String category  = contM.group("category");
                        String jobOrName = contM.group("joborcardname") != null
                                         ? contM.group("joborcardname") : contM.group("cardorname");
                        boolean inclBkps = contM.group("orbackup") != null || contM.group("sameelembackup") != null;
                        boolean isChar   = contM.group("catchar") != null || contM.group("jobchar") != null
                                        || contM.group("stdchar") != null || inclBkps;
                        String except = contM.group("except") != null
                                ? contM.group("except") : contM.group("jobexcept");
                        costs.add(new DullForwardCost(count,
                                cond     != null ? cond.toLowerCase()              : null,
                                elem     != null ? elem.trim()                     : null,
                                cardName != null ? cardName.trim()                 : null,
                                job      != null ? job.trim()                      : null,
                                category != null ? category.trim()                 : null,
                                isChar           ? "Character"                     : null,
                                jobOrName != null ? stripTrailingType(jobOrName)   : null,
                                except   != null ? except.trim()                   : null));
                    }
                }
                return List.copyOf(costs);
            }
        }

        Matcher m = DULL_COST_ITEM_PATTERN.matcher(raw);
        while (m.find()) {
            int    count       = Integer.parseInt(m.group("count"));
            String cond        = m.group("cond");
            String cardName    = m.group("cardname");
            String elem        = m.group("elem");
            String job         = m.group("job");
            String category    = m.group("category");
            String jobOrName   = m.group("joborcardname") != null
                               ? m.group("joborcardname") : m.group("cardorname");
            // "Forwards or Backups" and plain "Backups [of the same Element]" forms include non-Forward characters
            boolean inclBackups = m.group("orbackup") != null || m.group("sameelembackup") != null;
            boolean isChar     = m.group("catchar") != null
                              || m.group("jobchar") != null
                              || m.group("stdchar") != null
                              || inclBackups;
            // Whichever branch carried the exclusion — the Job branch has its own group, for
            // the reason that pattern documents.
            String except      = m.group("except") != null ? m.group("except") : m.group("jobexcept");
            costs.add(new DullForwardCost(count,
                    cond      != null ? cond.toLowerCase()          : null,
                    elem      != null ? elem.trim()                 : null,
                    cardName  != null ? cardName.trim()             : null,
                    job       != null ? job.trim()                  : null,
                    category  != null ? category.trim()             : null,
                    isChar            ? "Character"                 : null,
                    jobOrName != null ? stripTrailingType(jobOrName) : null,
                    except    != null ? except.trim()               : null));
        }
        return costs.isEmpty() ? List.of() : List.copyOf(costs);
    }

    /**
     * Parses the raw condition text extracted from "You can only use this ability if you control [X]".
     * Returns {@code null} if the text is unrecognised.
     *
     * <p>Before parsing, trailing restriction clauses already captured elsewhere
     * ("and only once per turn", "during your turn") are stripped so they do not
     * contaminate the condition match.
     */
    private static String stripTrailingType(String name) {
        return name.trim().replaceAll("(?i)\\s+(Forwards?|Backups?|Monsters?|Characters?)$", "").trim();
    }

    /** "N or more &lt;filter&gt; and/or &lt;filter&gt;[ and/or …]" — the count applies to the union. */
    private static final Pattern CTRL_COND_UNION = Pattern.compile(
        "(?i)^(?<count>\\d+)\\s+or\\s+more\\s+(?<filters>.+?\\s+and/or\\s+.+)$"
    );

    /**
     * Parses the "and/or" union form of a control condition — "5 or more Fire Characters and/or
     * Category XIV Characters" (Hien 17-016L) — into a count condition whose members may satisfy
     * any one of the listed filters.
     *
     * <p>Only reached when {@link #parseControlCondition} cannot handle the text on its own, so the
     * "Job X and/or Card Name Y" wording keeps using the existing {@code orCardNames} handling.
     * Returns {@code null} unless every alternative parses as a filter.
     */
    static ControlCondition parseUnionControlCondition(String raw) {
        Matcher m = CTRL_COND_UNION.matcher(raw.trim());
        if (!m.matches()) return null;
        int minCount = Integer.parseInt(m.group("count"));
        List<ControlCondition> alternatives = new ArrayList<>();
        for (String part : m.group("filters").split("(?i)\\s+and/or\\s+")) {
            // Each alternative is a bare filter phrase; "a " makes it a singular condition the
            // existing parser understands, and only its filter fields are used from here on.
            ControlCondition alt = parseControlCondition("a " + part.trim());
            if (alt == null) return null;
            alternatives.add(alt);
        }
        return alternatives.size() < 2 ? null : ControlCondition.forAnyOfFilters(minCount, alternatives);
    }

    static ControlCondition parseControlCondition(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // Strip trailing ", during your turn" and "and only once per turn" clauses
        String cond = raw.replaceAll("(?i)\\s*,?\\s*during\\s+your\\s+turn\\b.*", "").trim();
        cond = cond.replaceAll("(?i)\\s*,?\\s*(?:and\\s+)?only\\s+once\\s+per\\s+turn\\b.*", "").trim();
        cond = TRAILING_OR_MORE_COUNT.matcher(cond).replaceAll("${count} or more ${noun}");

        // Named-card mode: "(a) Card Name X [and Card Name Y [and Card Name Z]]"
        // Must be checked before count mode to avoid "a Card Name X" being parsed as count=1
        Matcher namedM = CONTROL_NAMED_CARDS_PATTERN.matcher(cond);
        if (namedM.find()) {
            List<String> names = new ArrayList<>();
            if (namedM.group("n1") != null) names.add(stripTrailingType(namedM.group("n1")));
            if (namedM.group("n2") != null) names.add(stripTrailingType(namedM.group("n2")));
            if (namedM.group("n3") != null) names.add(stripTrailingType(namedM.group("n3")));
            boolean anyOf = "or".equalsIgnoreCase(namedM.group("conj1"));
            return new ControlCondition(names, 0, false, null, null, null, null, 0, List.of(), anyOf);
        }

        // Count mode: "[N or more | only N | a] [element] [Category X] [Job name] [type] [of power P or more] [or Card Name X]"
        Matcher countM = CONTROL_COUNT_CONDITION_PATTERN.matcher(cond);
        if (!countM.find()) return null;

        int     minCount;
        boolean exactCount;
        if (countM.group("count") != null) {
            minCount   = Integer.parseInt(countM.group("count"));
            exactCount = false;
        } else if (countM.group("exactn") != null) {
            minCount   = Integer.parseInt(countM.group("exactn"));
            exactCount = true;
        } else {
            minCount   = 1;   // "a / an"
            exactCount = false;
        }

        String element        = countM.group("element");
        String category       = countM.group("category");
        String job            = countM.group("job") != null ? countM.group("job").trim() : null;
        String rawType        = countM.group("type");
        String cardType       = rawType != null ? rawType.replaceAll("(?i)s$", "").trim() : null; // normalise "Forwards" → "Forward"
        if (cardType != null) cardType = Character.toUpperCase(cardType.charAt(0)) + cardType.substring(1).toLowerCase();
        int minPower          = countM.group("power") != null ? Integer.parseInt(countM.group("power")) : 0;
        String altRaw         = countM.group("altname");
        List<String> orCardNames = altRaw != null ? List.of(altRaw.trim()) : List.of();
        String excludeElement = countM.group("excludeelem");
        int minCost = 0, maxCost = 0;
        if (countM.group("cost") != null) {
            int cost = Integer.parseInt(countM.group("cost"));
            if ("less".equalsIgnoreCase(countM.group("costcmp"))) maxCost = cost; else minCost = cost;
        }

        return new ControlCondition(List.of(), minCount, exactCount, cardType, element, job, category,
                minPower, orCardNames, false, excludeElement, null, false, false, false, minCost,
                List.of(), false, maxCost, null);
    }

    /** Parses a "remove … from the game" cost phrase into a list of {@link RemoveFromGameCost} items. */
    private static List<RemoveFromGameCost> parseRemoveFromGameCosts(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        Matcher m = REMOVE_FROM_GAME_COST_PATTERN.matcher(raw.trim());
        if (!m.find()) return List.of();
        String content = m.group(1).trim();

        // Split compound costs like "<name> and 1 Backup" where second part starts with a digit or "all"
        String[] parts = content.split("(?i)\\s+and\\s+(?=\\d|all\\b)", 2);
        List<RemoveFromGameCost> result = new ArrayList<>();
        for (String part : parts) {
            RemoveFromGameCost cost = parseOneRemoveFromGameCost(part.trim());
            if (cost != null) result.add(cost);
        }
        return List.copyOf(result);
    }

    private static RemoveFromGameCost parseOneRemoveFromGameCost(String part) {
        // DECK: "the top N cards of your deck"
        Matcher deckM = Pattern.compile("(?i)the\\s+top\\s+(\\d+)\\s+cards?\\s+of\\s+your\\s+deck").matcher(part);
        if (deckM.find())
            return new RemoveFromGameCost("DECK", Integer.parseInt(deckM.group(1)), null, null, null, null);

        // Determine zone by trailing qualifier
        String zone;
        String inner;
        Matcher handM = Pattern.compile("(?i)(.+?)\\s+in\\s+(?:your|the)\\s+hand").matcher(part);
        Matcher bzM   = Pattern.compile("(?i)(.+?)\\s+in\\s+(?:your|the)\\s+Break\\s+Zone").matcher(part);
        if (handM.find()) {
            zone  = "HAND";
            inner = handM.group(1).trim();
        } else if (bzM.find()) {
            zone  = "BREAK_ZONE";
            inner = bzM.group(1).trim();
        } else {
            zone  = "FIELD";
            inner = part;
        }
        return parseRemoveInnerCost(zone, inner);
    }

    private static RemoveFromGameCost parseRemoveInnerCost(String zone, String inner) {
        // "all the <Type>s"
        Matcher allM = Pattern.compile("(?i)all\\s+the\\s+(\\w+)").matcher(inner);
        if (allM.find())
            return new RemoveFromGameCost(zone, -1, null, null, normalizeTypeSuffix(allM.group(1)), null);

        // "N Card Name <name>"
        Matcher cnM = Pattern.compile("(?i)(\\d+)\\s+Card\\s+Name\\s+(.+)").matcher(inner);
        if (cnM.find())
            return new RemoveFromGameCost(zone, Integer.parseInt(cnM.group(1)), cnM.group(2).trim(), null, null, null);

        // "N <type> other than <name>"
        Matcher otherM = Pattern.compile(
            "(?i)(\\d+)\\s+(Summons?|Forwards?|Backups?|Monsters?|Characters?)\\s+other\\s+than\\s+(.+)"
        ).matcher(inner);
        if (otherM.find())
            return new RemoveFromGameCost(zone, Integer.parseInt(otherM.group(1)), null, null,
                    normalizeTypeSuffix(otherM.group(2)), otherM.group(3).trim());

        // "N <element> cards?" (generic element, no type)
        Matcher elemCardM = Pattern.compile(
            "(?i)(\\d+)\\s+(Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+cards?"
        ).matcher(inner);
        if (elemCardM.find())
            return new RemoveFromGameCost(zone, Integer.parseInt(elemCardM.group(1)), null, elemCardM.group(2), null, null);

        // "N <element>? <type>s?" — covers typed and generic "card(s)"
        Matcher typedM = Pattern.compile(
            "(?i)(\\d+)\\s+(?:(Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
            "(Summons?|Forwards?|Backups?|Monsters?|Characters?|cards?)"
        ).matcher(inner);
        if (typedM.find()) {
            String elem     = typedM.group(2);
            String rawType  = typedM.group(3);
            String cardType = rawType.equalsIgnoreCase("card") || rawType.equalsIgnoreCase("cards")
                    ? null : normalizeTypeSuffix(rawType);
            return new RemoveFromGameCost(zone, Integer.parseInt(typedM.group(1)), null, elem, cardType, null);
        }

        // Fallback: treat entire string as a named card on field
        if (!inner.isBlank())
            return new RemoveFromGameCost(zone, 1, inner, null, null, null);

        return null;
    }

    private static final Pattern RETURN_TO_HAND_COST_PATTERN = Pattern.compile(
        "(?i)return\\s+(.+?)\\s+to\\s+(?:its|their)\\s+owner(?:'s|s')?\\s+hand"
    );

    /** Parses a "return … to its owner's hand" cost phrase into a list of {@link ReturnToHandCost} items. */
    private static List<ReturnToHandCost> parseReturnToHandCosts(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        Matcher m = RETURN_TO_HAND_COST_PATTERN.matcher(raw.trim());
        if (!m.find()) return List.of();
        String content = m.group(1).trim();

        // "N Category X Type other than <name>"
        Matcher catOtherM = Pattern.compile(
            "(?i)(\\d+)\\s+Category\\s+(\\S+)\\s+(Summons?|Forwards?|Backups?|Monsters?|Characters?)\\s+other\\s+than\\s+(.+)"
        ).matcher(content);
        if (catOtherM.find())
            return List.of(new ReturnToHandCost(Integer.parseInt(catOtherM.group(1)),
                    null, normalizeTypeSuffix(catOtherM.group(3)), catOtherM.group(2).trim(), catOtherM.group(4).trim()));

        // "N Category X Type"
        Matcher catM = Pattern.compile(
            "(?i)(\\d+)\\s+Category\\s+(\\S+)\\s+(Summons?|Forwards?|Backups?|Monsters?|Characters?)"
        ).matcher(content);
        if (catM.find())
            return List.of(new ReturnToHandCost(Integer.parseInt(catM.group(1)),
                    null, normalizeTypeSuffix(catM.group(3)), catM.group(2).trim(), null));

        // "N Type other than <name>"
        Matcher typeOtherM = Pattern.compile(
            "(?i)(\\d+)\\s+(Summons?|Forwards?|Backups?|Monsters?|Characters?)\\s+other\\s+than\\s+(.+)"
        ).matcher(content);
        if (typeOtherM.find())
            return List.of(new ReturnToHandCost(Integer.parseInt(typeOtherM.group(1)),
                    null, normalizeTypeSuffix(typeOtherM.group(2)), null, typeOtherM.group(3).trim()));

        // "N Type"
        Matcher typeM = Pattern.compile(
            "(?i)(\\d+)\\s+(Summons?|Forwards?|Backups?|Monsters?|Characters?)"
        ).matcher(content);
        if (typeM.find())
            return List.of(new ReturnToHandCost(Integer.parseInt(typeM.group(1)),
                    null, normalizeTypeSuffix(typeM.group(2)), null, null));

        // Fallback: named card
        return List.of(new ReturnToHandCost(1, content, null, null, null));
    }

    // -------------------------------------------------------------------------
    // Trait parsing
    // -------------------------------------------------------------------------

    /**
     * Parses {@code textEn} and returns the set of Special Traits present.
     *
     * <p>{@code cardName} is needed because {@link Trait#CANNOT_BE_BROKEN} is a statement about
     * this card and no other. It is read one {@code [[br]]} segment at a time and the subject is
     * checked against the name, so a card that merely mentions the protection — granting it to
     * other Forwards, or claiming it only under a condition — no longer picks it up itself.
     */
    public static Set<Trait> parseTraits(String textEn, String cardName) {
        if (textEn == null || textEn.isBlank()) return Set.of();
        EnumSet<Trait> found = EnumSet.noneOf(Trait.class);
        if (HASTE_PATTERN.matcher(textEn).find())        found.add(Trait.HASTE);
        if (BRAVE_PATTERN.matcher(textEn).find())        found.add(Trait.BRAVE);
        if (FIRST_STRIKE_PATTERN.matcher(textEn).find()) found.add(Trait.FIRST_STRIKE);
        if (BACK_ATTACK_PATTERN.matcher(textEn).find())  found.add(Trait.BACK_ATTACK);
        if (WARP_PATTERN.matcher(textEn).find())         found.add(Trait.WARP);
        if (PRIMING_PATTERN.matcher(textEn).find())      found.add(Trait.PRIMING);
        // Both break shields are unconditional-self-only here. Every other printing — a condition
        // on either side of the sentence, a damage gate, a grant made to somebody else — falls
        // through to FieldGrantCalculator, which re-evaluates it as the game state moves.
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (parseSelfCannotBeBroken(seg, cardName))            found.add(Trait.CANNOT_BE_BROKEN);
            if (parseSelfNonDmgBreakShieldDirect(seg, cardName))   found.add(Trait.CANNOT_BE_BROKEN_BY_NON_DMG);
            if (parseSelfCannotLeaveFieldByOpp(seg, cardName))     found.add(Trait.CANNOT_LEAVE_FIELD_BY_OPP);
        }
        return found;
    }

    /** Returns {@code true} if this card has the given Special Trait. */
    public boolean hasTrait(Trait t) { return traits.contains(t); }

    /**
     * Returns {@code true} if this card may be cast at the timing Summons and abilities use —
     * during either player's Main Phase or Attack Phase, at any point its controller holds
     * priority — rather than only in its controller's own Main Phase.
     *
     * <p>Summons have that timing by card type.  The Back Attack trait grants it to a Character,
     * as its reminder text says: "Like Summons and abilities, this card can be played during
     * either player's Attack Phase or Main Phase."  Everything else about the cast is unchanged —
     * a Back Attack Character still enters the field directly rather than using the stack, and is
     * still subject to name conflicts, backup slots, cast limits, and its own cast restrictions.
     */
    public boolean castsAtSummonSpeed() {
        return isSummon() || hasTrait(Trait.BACK_ATTACK);
    }

    /** Returns {@code true} if this card has the Warp trait (warpValue &gt; 0). */
    public boolean hasWarp() { return warpValue > 0; }

    /** Returns {@code true} if this card has the Priming trait. */
    public boolean hasPriming() { return !primingTarget.isEmpty(); }

    /** Returns {@code true} if any of this card's elements is Light or Dark (cannot be discarded for CP). */
    public boolean isLightOrDark() {
        for (String e : element.split("/"))
            if ("Light".equalsIgnoreCase(e) || "Dark".equalsIgnoreCase(e)) return true;
        return false;
    }

    /** Returns {@code true} if any of this card's elements matches {@code elem} (case-insensitive).
     *  The special value {@code "Multi-Element"} matches any card that has more than one element. */
    public boolean containsElement(String elem) {
        if (elem.contains("|")) {
            for (String e : elem.split("\\|")) if (containsElement(e.trim())) return true;
            return false;
        }
        if ("Multi-Element".equalsIgnoreCase(elem)) return element.split("/").length > 1;
        // "has all the Elements except X" field ability
        java.util.Set<String> excluded = allElementsExcept();
        if (excluded != null)
            return excluded.stream().noneMatch(ex -> ex.equalsIgnoreCase(elem));
        for (String e : element.split("/"))
            if (e.equalsIgnoreCase(elem)) return true;
        return false;
    }

    /**
     * Returns {@code true} if any field ability on this card matches
     * "[name] has all the jobs."
     */
    public boolean hasAllJobs() {
        for (FieldAbility fa : fieldAbilities())
            if (HAS_ALL_JOBS_PATTERN.matcher(fa.effectText()).matches()) return true;
        return false;
    }

    /**
     * Returns {@code true} if any field ability on this card matches
     * "[name] has the Jobs of the Forwards you control."
     */
    public boolean hasJobsOfControlledForwards() {
        for (FieldAbility fa : fieldAbilities())
            if (HAS_JOBS_OF_FORWARDS_PATTERN.matcher(fa.effectText()).matches()) return true;
        return false;
    }

    /** Returns {@code true} when {@code text} is a "[name] has the Jobs of the Forwards you control." ability. */
    public static boolean isHasJobsOfForwardsAbility(String text) {
        return HAS_JOBS_OF_FORWARDS_PATTERN.matcher(text.trim()).matches();
    }

    /**
     * Returns {@code true} when {@code text} is a "[name] has all the jobs." ability — the
     * text-level twin of {@link #hasAllJobs()}, which asks the same question of a whole card.
     *
     * <p>Bartz 1-081R's rule has been live for as long as {@code hasAllJobs} has had callers; what
     * was missing was a way for the field-ability coverage report to see it, since the rule is
     * answered by a query on the card rather than by anything {@link ActionResolver} parses.
     */
    public static boolean isHasAllJobsAbility(String text) {
        return text != null && HAS_ALL_JOBS_PATTERN.matcher(text.trim()).matches();
    }

    /**
     * Returns the job-count threshold for a "If [cardName] has N Jobs or more, gains [traits]" ability,
     * or -1 if the text does not match or the card name doesn't match.
     */
    public static int parseIfSelfJobCountTraitGrantThreshold(String text, String cardName) {
        Matcher m = IF_SELF_JOB_COUNT_TRAIT_GRANT.matcher(text.trim());
        if (!m.matches()) return -1;
        if (!m.group("n1").trim().equalsIgnoreCase(cardName)) return -1;
        if (!m.group("n2").trim().equalsIgnoreCase(cardName)) return -1;
        return Integer.parseInt(m.group("n"));
    }

    /**
     * Returns the traits granted by a "If [cardName] has N Jobs or more, gains [traits]" ability.
     * Assumes the text already matches {@link #IF_SELF_JOB_COUNT_TRAIT_GRANT}.
     */
    public static EnumSet<Trait> parseIfSelfJobCountTraitGrantTraits(String text) {
        Matcher m = IF_SELF_JOB_COUNT_TRAIT_GRANT.matcher(text.trim());
        if (!m.matches()) return EnumSet.noneOf(Trait.class);
        String traitsText = m.group("traits");
        EnumSet<Trait> result = EnumSet.noneOf(Trait.class);
        if (ICB_EFFECT_HASTE.matcher(traitsText).find())        result.add(Trait.HASTE);
        if (ICB_EFFECT_BRAVE.matcher(traitsText).find())        result.add(Trait.BRAVE);
        if (ICB_EFFECT_FIRST_STRIKE.matcher(traitsText).find()) result.add(Trait.FIRST_STRIKE);
        if (ICB_EFFECT_BACK_ATTACK.matcher(traitsText).find())  result.add(Trait.BACK_ATTACK);
        return result;
    }

    /**
     * Returns the power threshold for a "If [cardName] has N power or more, gains [traits]" ability,
     * or -1 if the text does not match or either name is not {@code cardName}.
     */
    public static int parseIfSelfPowerTraitGrantThreshold(String text, String cardName) {
        Matcher m = selfPowerTraitGrantMatcher(text, cardName);
        return m == null ? -1 : Integer.parseInt(m.group("n"));
    }

    /**
     * The matcher for whichever self-power trait grant {@code text} is — the "has N power"
     * spelling or Yang 2-090R's "the power of … is N" one — with both name captures already
     * checked against {@code cardName}, or {@code null} when it is neither.
     */
    private static Matcher selfPowerTraitGrantMatcher(String text, String cardName) {
        if (text == null || cardName == null) return null;
        String t = text.trim();
        for (Pattern p : new Pattern[]{ IF_SELF_POWER_TRAIT_GRANT, IF_SELF_POWER_IS_TRAIT_GRANT }) {
            Matcher m = p.matcher(t);
            if (!m.matches()) continue;
            if (!m.group("n1").trim().equalsIgnoreCase(cardName)) continue;
            if (!m.group("n2").trim().equalsIgnoreCase(cardName)) continue;
            return m;
        }
        return null;
    }

    /**
     * Returns the traits granted by a "If [cardName] has N power or more, gains [traits]" ability.
     * Assumes the text already matches {@link #IF_SELF_POWER_TRAIT_GRANT}.
     */
    public static EnumSet<Trait> parseIfSelfPowerTraitGrantTraits(String text) {
        Matcher m = IF_SELF_POWER_TRAIT_GRANT.matcher(text.trim());
        if (!m.matches()) m = IF_SELF_POWER_IS_TRAIT_GRANT.matcher(text.trim());
        if (!m.matches()) return EnumSet.noneOf(Trait.class);
        String traitsText = m.group("traits");
        EnumSet<Trait> result = EnumSet.noneOf(Trait.class);
        if (ICB_EFFECT_HASTE.matcher(traitsText).find())        result.add(Trait.HASTE);
        if (ICB_EFFECT_BRAVE.matcher(traitsText).find())        result.add(Trait.BRAVE);
        if (ICB_EFFECT_FIRST_STRIKE.matcher(traitsText).find()) result.add(Trait.FIRST_STRIKE);
        if (ICB_EFFECT_BACK_ATTACK.matcher(traitsText).find())  result.add(Trait.BACK_ATTACK);
        return result;
    }

    /**
     * Returns the LB face-up threshold for a "If there are N or more face-up cards in your LB deck,
     * [cardName] gains [traits]" ability, or -1 if the text doesn't match or the name doesn't match.
     */
    public static int parseIfSelfLbFaceUpCountTraitGrantThreshold(String text, String cardName) {
        Matcher m = IF_SELF_LB_FACEUP_COUNT_TRAIT_GRANT.matcher(text.trim());
        if (!m.matches()) return -1;
        if (!m.group("cardname").trim().equalsIgnoreCase(cardName)) return -1;
        return Integer.parseInt(m.group("n"));
    }

    /**
     * Returns the traits granted by a "If there are N or more face-up cards in your LB deck,
     * [cardName] gains [traits]" ability.
     * Assumes the text already matches {@link #IF_SELF_LB_FACEUP_COUNT_TRAIT_GRANT}.
     */
    public static EnumSet<Trait> parseIfSelfLbFaceUpCountTraitGrantTraits(String text) {
        Matcher m = IF_SELF_LB_FACEUP_COUNT_TRAIT_GRANT.matcher(text.trim());
        if (!m.matches()) return EnumSet.noneOf(Trait.class);
        String traitsText = m.group("traits");
        EnumSet<Trait> result = EnumSet.noneOf(Trait.class);
        if (ICB_EFFECT_HASTE.matcher(traitsText).find())        result.add(Trait.HASTE);
        if (ICB_EFFECT_BRAVE.matcher(traitsText).find())        result.add(Trait.BRAVE);
        if (ICB_EFFECT_FIRST_STRIKE.matcher(traitsText).find()) result.add(Trait.FIRST_STRIKE);
        if (ICB_EFFECT_BACK_ATTACK.matcher(traitsText).find())  result.add(Trait.BACK_ATTACK);
        return result;
    }

    /**
     * Returns all individual jobs for this card.  Multi-job cards store their jobs as a
     * slash-separated string (e.g. {@code "Warrior/Rebel"}); this method splits on {@code "/"}
     * and trims each component.  Returns an empty list when the card has no job.
     */
    public java.util.List<String> jobs() {
        if (job == null || job.isBlank()) return java.util.List.of();
        String[] parts = job.split("/");
        java.util.List<String> result = new java.util.ArrayList<>(parts.length);
        for (String p : parts) { String t = p.trim(); if (!t.isEmpty()) result.add(t); }
        return java.util.List.copyOf(result);
    }

    /**
     * Returns {@code true} if {@code jobName} matches any of this card's jobs (case-insensitive).
     * Handles slash-separated multi-job values such as {@code "Warrior/Rebel"}.
     */
    public boolean hasJob(String jobName) {
        if (jobName == null || job == null || job.isBlank()) return false;
        for (String j : job.split("/"))
            if (j.trim().equalsIgnoreCase(jobName)) return true;
        return false;
    }

    /**
     * Splits {@code textEn} on {@code [[br]]} and returns the markup-stripped, trimmed segments.
     * Used by static-property accessors that scan raw card text rather than parsed FieldAbility
     * entries (because those entries intentionally exclude static-property sentences).
     */
    private java.util.List<String> rawFieldSegments() {
        String text = EX_BURST_TAG.matcher(textEn).replaceAll(" ");
        java.util.List<String> segs = new java.util.ArrayList<>();
        for (String raw : text.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (!seg.isEmpty()) segs.add(seg);
        }
        return segs;
    }

    /**
     * Returns the set of alternate card names this card counts as, parsed from
     * "[name] is also Card Name X [and Card Name Y] in all situations." segments.
     * Returns an empty set when no such ability is present.
     */
    public java.util.Set<String> alsoCardNames() {
        java.util.Set<String> result = null;
        for (String seg : rawFieldSegments()) {
            Matcher m = IS_ALSO_CARD_NAME_PATTERN.matcher(seg);
            if (!m.matches()) continue;
            if (result == null) result = new java.util.LinkedHashSet<>();
            String raw = m.group("names");
            for (String part : raw.split("(?i)\\s+and\\s+Card\\s+Name\\s+")) {
                String n = part.replaceFirst("(?i)^Card\\s+Name\\s+", "").trim();
                if (!n.isEmpty()) result.add(n);
            }
        }
        return result != null ? java.util.Collections.unmodifiableSet(result) : java.util.Set.of();
    }

    /**
     * Returns the Card Name that pairs with this card in the
     * "You cannot play {name} or Card Name X while already in control of either Character"
     * restriction, or {@code null} if no such restriction exists.
     */
    public String aliasPlayRestrictionName() {
        for (String seg : rawFieldSegments()) {
            Matcher m = ALIAS_PLAY_RESTRICTION_PATTERN.matcher(seg);
            if (!m.matches()) continue;
            return m.group("name2").trim();
        }
        return null;
    }

    /**
     * Returns {@code true} if this card has a "is also a Monster in all situations" ability,
     * making it eligible for Monster-targeting effects regardless of zone.
     */
    public boolean alsoCountsAsMonster() {
        for (String seg : rawFieldSegments())
            if (IS_ALSO_MONSTER_PATTERN.matcher(seg).matches()) return true;
        return false;
    }

    /**
     * Grants use of {@code targetName}'s special ability by discarding a substitute card:
     * either a specific card name ({@code subCardName}), a typed card ({@code subType}),
     * or an element+type card ({@code subElement} + {@code subType}).
     *
     * <p>{@code requiresControlCardName} is Tifa 26-076H's board condition: the substitution is
     * live only while its controller has a card of that name on the field, and it is the caller's
     * job to check — a {@link CardData} cannot see the board. Every reader goes through
     * {@code MainWindow.effectiveSpecialAbilityProxy} rather than this record directly, so the
     * menu gate, the payment dialog and the CPU path cannot disagree about whether it applies.
     * {@code null} means unconditional, which is what the printed substitutions all are.
     */
    public record SpecialAbilityProxy(String targetName, String subElement, String subType,
            String subCardName, String requiresControlCardName) {
        /** The unconditional form — every printing but Tifa 26-076H's. */
        public SpecialAbilityProxy(String targetName, String subElement, String subType, String subCardName) {
            this(targetName, subElement, subType, subCardName, null);
        }
        public boolean meetsSubstitute(CardData handCard) {
            if (subCardName != null) return subCardName.equalsIgnoreCase(handCard.name());
            if (subType == null) return false;
            if (!CardFilters.matchesDiscardType(handCard, subType)) return false;
            return subElement == null || CardFilters.meetsElementFilter(handCard, subElement);
        }
        public String substituteDescription() {
            if (subCardName != null) return subCardName;
            if ("card".equalsIgnoreCase(subType) && subElement == null) return "any card";
            return (subElement != null ? subElement + " " : "") + subType;
        }
    }

    /**
     * Returns a {@link SpecialAbilityProxy} if this card grants use of another card's special
     * ability with an alternate substitute discard, or {@code null} if it has none.
     *
     * <p>Both spellings are read here: the unconditional "by discarding a[n] X instead of
     * discarding a Card Name Y" (Braska 16-133S, Rydia 2-094H, Duncan 8-014L) and Tifa 26-076H's
     * board-conditioned "If you control a Card Name Z, you can discard 1 card instead of 《S》".
     * The latter substitutes <em>any</em> card, carried as the {@code "card"} type so it runs
     * through the same {@link SpecialAbilityProxy#meetsSubstitute} every other printing uses.
     */
    public SpecialAbilityProxy specialAbilityProxy() {
        for (String seg : rawFieldSegments()) {
            SpecialAbilityProxy proxy = parseSpecialAbilityProxy(seg);
            if (proxy != null) return proxy;
        }
        return null;
    }

    /**
     * The {@link SpecialAbilityProxy} one field segment declares, or {@code null} when it declares
     * none. Split out from {@link #specialAbilityProxy()} so a caller holding a single segment can
     * ask the same question the card-wide lookup asks, rather than re-deriving the two patterns.
     */
    public static SpecialAbilityProxy parseSpecialAbilityProxy(String seg) {
        if (seg == null || seg.isBlank()) return null;
        Matcher m = SPECIAL_ABILITY_PROXY_PATTERN.matcher(seg);
        if (m.matches())
            return new SpecialAbilityProxy(
                m.group("target").trim(),
                m.group("subElem") != null ? m.group("subElem").trim() : null,
                m.group("subType") != null ? m.group("subType").trim() : null,
                m.group("subName") != null ? m.group("subName").trim() : null
            );
        Matcher c = SPECIAL_ABILITY_CONTROL_ANY_DISCARD_PATTERN.matcher(seg);
        if (c.matches())
            return new SpecialAbilityProxy(c.group("target").trim(), null, "card", null,
                    c.group("require").trim());
        return null;
    }

    /**
     * Returns {@code true} if this card has an "enters the field dull" ability,
     * meaning it enters the field in the dull state instead of the active state.
     */
    public boolean entersFieldDull() {
        for (String seg : rawFieldSegments())
            if (ENTERS_FIELD_DULL_PATTERN.matcher(seg).matches()) return true;
        return false;
    }

    /**
     * Carries the parsed "also becomes a Forward with [power]" ability.
     * {@code power} is the printed power value (e.g. 7000).
     * {@code damageThreshold} is the minimum damage zone size required; {@code 0} means the
     * "During your turn" variant (active only on the controlling player's turn, no damage requirement).
     */
    /**
     * @param power                  Forward power value
     * @param damageThreshold        0 = "During your turn"; >0 = active when owner's damage ≥ threshold
     * @param minControlledMonsters  >0 = active only while owner controls at least this many Monsters
     */
    public record BecomeForwardAbility(int power, int damageThreshold, int minControlledMonsters) {}

    /**
     * Returns the {@link BecomeForwardAbility} for this card, or {@code null} if it has none.
     * Recognises three forms:
     * <ul>
     *   <li>"If you control N or more Monsters, [name] also becomes a Forward with P power"
     *       → {@code minControlledMonsters=N}</li>
     *   <li>"During your turn, [name] also becomes a Forward with N power" → {@code damageThreshold=0}</li>
     *   <li>"Damage N -- [name] also becomes a Forward with M power" → {@code damageThreshold=N}</li>
     * </ul>
     */
    public BecomeForwardAbility becomeForwardAbility() {
        for (String seg : rawFieldSegments()) {
            Matcher mc = BECOME_FORWARD_IF_CONTROL_N_MONSTERS_PATTERN.matcher(seg);
            if (mc.find()) return new BecomeForwardAbility(
                    Integer.parseInt(mc.group("power")), 0, Integer.parseInt(mc.group("n")));

            Matcher m = BECOME_FORWARD_DURING_TURN_PATTERN.matcher(seg);
            if (m.find()) return new BecomeForwardAbility(Integer.parseInt(m.group("power")), 0, 0);

            int threshold = 0;
            String check = seg;
            Matcher dtM = DAMAGE_THRESHOLD_PREFIX.matcher(seg);
            if (dtM.find()) {
                threshold = Integer.parseInt(dtM.group(1));
                check = seg.substring(dtM.end()).trim();
            }
            if (threshold > 0) {
                Matcher m2 = BECOME_FORWARD_UNCONDITIONAL_PATTERN.matcher(check);
                if (m2.find()) return new BecomeForwardAbility(Integer.parseInt(m2.group("power")), threshold, 0);
            }
        }
        return null;
    }

    /**
     * Returns the set of elements excluded by a "has all the Elements except X" field ability,
     * an empty set if the ability grants all elements with no exceptions, or {@code null} if no
     * such ability is present on this card.
     */
    public java.util.Set<String> allElementsExcept() {
        for (FieldAbility fa : fieldAbilities()) {
            Matcher m = HAS_ALL_ELEMENTS_PATTERN.matcher(fa.effectText());
            if (!m.matches()) continue;
            String raw = m.group("exceptions");
            if (raw == null || raw.isBlank()) return java.util.Set.of();
            return java.util.Arrays.stream(raw.split(",\\s*|\\s+and\\s+"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toSet());
        }
        return null;
    }

    /**
     * Returns the element ("Light" or "Dark") for which this card carries a self-exception
     * ("X can be played onto the field even if you control other [Light|Dark] Characters"),
     * or {@code null} if no such ability is present.
     */
    public String selfLightDarkPlayException() {
        for (FieldAbility fa : fieldAbilities()) {
            Matcher m = SELF_LIGHT_DARK_PLAY_EXCEPTION_PATTERN.matcher(fa.effectText());
            if (m.matches() && m.group("name").equalsIgnoreCase(name())) return m.group("element");
        }
        return null;
    }

    /**
     * Returns the element ("Light" or "Dark") this card grants as a multi-play exception
     * ("You can play 2 or more [Light|Dark] Characters onto the field") while on the field,
     * or {@code null} if no such ability is present.
     */
    public String grantsMultiLightDarkPlay() {
        for (FieldAbility fa : fieldAbilities()) {
            Matcher m = MULTI_LIGHT_DARK_PLAY_PATTERN.matcher(fa.effectText());
            if (m.matches()) return m.group("element");
        }
        return null;
    }

    /**
     * Returns the card name for which this card grants a name-specific multi-play exception
     * ("You can play 2 or more Card Name X onto the field") while on the field,
     * or {@code null} if no such ability is present.
     */
    public String grantsMultiNamePlay() {
        for (FieldAbility fa : fieldAbilities()) {
            Matcher m = MULTI_NAME_PLAY_PATTERN.matcher(fa.effectText());
            if (m.matches()) return m.group("cardname").trim();
        }
        return null;
    }

    /**
     * Returns the Light/Dark elements whose cards this card allows discarding from hand to
     * produce CP ("You can discard [Light and Dark|Dark] Element cards from your hand to
     * produce CP") while on the field, or an empty list if no such ability is present.
     */
    public List<String> grantsLightDarkDiscardCp() {
        for (FieldAbility fa : fieldAbilities()) {
            Matcher m = LIGHT_DARK_DISCARD_CP_PATTERN.matcher(fa.effectText());
            if (!m.matches()) continue;
            return m.group("e2") == null
                ? List.of(m.group("e1"))
                : List.of(m.group("e1"), m.group("e2"));
        }
        return List.of();
    }

    /** Returns each element of this card as a separate string. */
    public String[] elements() { return element.split("/"); }

    /** Returns {@code true} if this card's type is Backup. */
    public boolean isBackup() {
        return "Backup".equalsIgnoreCase(type);
    }

    /** Returns {@code true} if this card's type is Forward. */
    public boolean isForward() {
        return "Forward".equalsIgnoreCase(type);
    }

    /** Returns {@code true} if this card's type is Monster. */
    public boolean isMonster() {
        return "Monster".equalsIgnoreCase(type);
    }

    /** Returns {@code true} if this card's type is Summon. */
    public boolean isSummon() {
        return "Summon".equalsIgnoreCase(type);
    }

    // -------------------------------------------------------------------------
    // SelfCostModifier parsing
    // -------------------------------------------------------------------------

    /**
     * Top-level pattern for self-cost modifiers.  Handles both:
     * <ul>
     *   <li>"The cost required to play &lt;name&gt; onto the field is (reduced|increased) by N [scaling] [(it cannot become 0)]."</li>
     *   <li>"The cost required to cast &lt;name&gt; is (reduced|increased) by N [scaling] [(it cannot become 0)]."</li>
     *   <li>"If &lt;condition&gt;, the cost required to play &lt;name&gt; onto the field is (reduced|increased) by N [(it cannot become 0)]."</li>
     *   <li>"If &lt;condition&gt;, the cost for playing &lt;name&gt; onto the field is (reduced|increased) by N."</li>
     * </ul>
     *
     * <p>An "…and can be paid with CP of any Element" tail is part of the same sentence rather than a
     * second ability, so it is captured here as {@code anyelem} instead of ending the match. Tifa
     * 11-071L prints it, and before it was accepted the end anchor rejected the whole sentence —
     * losing the reduction as well as the payment permission, and leaving the text in the
     * field-ability report as if nothing about it were handled.
     *
     * <p>The delta itself is optional, because Cloud 21-090R prints the permission <em>alone</em>:
     * "If you control a Card Name Lann or a Card Name Reynn, the cost required to cast Cloud can be
     * paid with CP of any Element." — a condition and a payment rule with no discount at all. That
     * is why {@code anyelem}'s "and" is optional too: with no delta in front of it there is nothing
     * for the conjunction to join.
     *
     * <p>Making it optional needs the lookahead that follows the name, which requires one of the
     * three real continuations to be there. Without it the pattern degenerates into "The cost
     * required to cast &lt;anything&gt;", and the lazy name group happily runs to the end of the
     * sentence — six cards print "Before paying the cost to cast X, you can remove … <em>to reduce
     * the cost required to cast X by N</em>", a pre-payment ability whose tail reads exactly like
     * that. They were silently claimed as self-cost modifiers and dropped from the field-ability
     * list. The guard in {@link #parseSelfCostModifiers} is not enough on its own: the
     * field-ability pass asks {@link #isSelfCostModifierText}, which only tests the pattern.
     */
    private static final Pattern SELF_COST_MAIN = Pattern.compile(
        "(?i)" +
        "(?:" +
          "(?:(?:During\\s+this\\s+turn,\\s+)?If\\s+(?<cond>[^,]+),\\s+)" +
          "|(?<yourturn>During\\s+your\\s+turn,\\s+)" +
        ")?" +
        "The\\s+cost\\s+" +
        "(?:" +
            "required\\s+to\\s+" +
            "(?:play\\s+(?<name1>.+?)\\s+onto\\s+the\\s+field|cast\\s+(?<name2>.+?))" +
            "|for\\s+playing\\s+(?<name3>.+?)\\s+onto\\s+the\\s+field" +
        ")" +
        // One of the three continuations must follow the name — see the note above.
        "(?=\\s+(?:is\\s+(?:reduced|increased)\\s+by\\s+\\d" +
            "|becomes\\s+\\d" +
            "|(?:and\\s+)?can\\s+be\\s+paid\\s+with\\s+CP\\s+of\\s+any\\s+Element))" +
        "(?:" +
            "\\s+is\\s+(?<dir>reduced|increased)\\s+by\\s+(?<amount>\\d+)" +
            "(?:\\s+(?<scaling>for\\s+.+?))?" +
            "(?:[.]?\\s+\\(it\\s+cannot\\s+become\\s+(?:0|1\\s+or\\s+less)\\))?" +
            // The replacement form — "becomes N" rather than a delta (Yuffie 3-069C). It takes no
            // scaling clause: a cost that becomes a fixed number has nothing to scale by.
            "|\\s+becomes\\s+(?<becomes>\\d+)" +
        ")?" +
        "(?<anyelem>\\s+(?:and\\s+)?can\\s+be\\s+paid\\s+with\\s+CP\\s+of\\s+any\\s+Element)?" +
        "\\s*\\.?$"
    );

    // Condition sub-patterns
    private static final Pattern SELF_COND_CAST_SUMMON = Pattern.compile(
        "(?i)^you\\s+have\\s+cast\\s+a\\s+Summon\\s+this\\s+turn$"
    );
    private static final Pattern SELF_COND_CAST_JOB_OR_NAME = Pattern.compile(
        "(?i)^you\\s+have\\s+cast\\s+a\\s+Job\\s+(?<job>.+?)\\s+or\\s+Card\\s+Name\\s+(?<name>.+?)\\s+this\\s+turn$"
    );
    /**
     * "you control [a] Card Name X" — the article is optional because the corpus prints it both
     * ways: Leo 15-034H omits it, Tifa 11-071L ("If you control a Card Name Cloud, …") does not.
     */
    private static final Pattern SELF_COND_CONTROL_NAME = Pattern.compile(
        "(?i)^you\\s+control\\s+(?:an?\\s+)?Card\\s+Name\\s+(?<name>.+?)\\s*$"
    );
    /**
     * "you control a Card Name X or a Card Name Y" — Cloud 21-090R, whose condition names two cards
     * and is met by either. Must be checked ahead of {@link #SELF_COND_CONTROL_NAME}, whose
     * {@code name} group would otherwise swallow the whole disjunction and look for a card called
     * "Lann or a Card Name Reynn".
     */
    private static final Pattern SELF_COND_CONTROL_NAME_OR_NAME = Pattern.compile(
        "(?i)^you\\s+control\\s+(?:an?\\s+)?Card\\s+Name\\s+(?<name1>.+?)" +
        "\\s+or\\s+(?:an?\\s+)?Card\\s+Name\\s+(?<name2>.+?)\\s*$"
    );
    private static final Pattern SELF_COND_RECEIVED_N_DAMAGE = Pattern.compile(
        "(?i)^you\\s+have\\s+received\\s+(?<n>\\d+)\\s+points?\\s+of\\s+damage\\s+or\\s+more$"
    );
    private static final Pattern SELF_COND_OPP_FWD_BROKEN = Pattern.compile(
        "(?i)^a\\s+Forward\\s+(?:your\\s+)?opponent\\s+controlled\\s+was\\s+put\\s+from\\s+the\\s+field\\s+into\\s+the\\s+Break\\s+Zone\\s+this\\s+turn$"
    );
    private static final Pattern SELF_COND_CONTROL_N_CATEGORY_TYPE = Pattern.compile(
        "(?i)^you\\s+control\\s+(?<n>\\d+)\\s+or\\s+more\\s+Category\\s+(?<cat>\\S+)\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)$"
    );
    private static final Pattern SELF_COND_OWN_JOB_BROKEN = Pattern.compile(
        "(?i)^a\\s+Job\\s+(?<job>.+?)\\s+you\\s+controlled\\s+has\\s+been\\s+put\\s+from\\s+the\\s+field\\s+into\\s+the\\s+Break\\s+Zone$"
    );
    private static final Pattern SELF_COND_CONTROL_NONE_OF_TYPE = Pattern.compile(
        "(?i)^you\\s+don'?t\\s+control\\s+any\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)$"
    );
    private static final Pattern SELF_COND_OPPONENT_DISCARDED_BY_ME = Pattern.compile(
        "(?i)^your\\s+opponent\\s+has\\s+discarded\\s+a\\s+card(?:\\s+from\\s+their\\s+hand)?\\s+due\\s+to\\s+your\\s+(?:Summons?|abilities?)(?:\\s+or\\s+(?:Summons?|abilities?))*$"
    );
    private static final Pattern SELF_COND_OPPONENT_DISCARDED = Pattern.compile(
        "(?i)^your\\s+opponent\\s+has\\s+discarded\\s+a\\s+card\\s+from\\s+their\\s+hand(?:\\s+due\\s+to\\s+(?:the\\s+)?(?:Summons?|abilities?)(?:\\s+or\\s+(?:the\\s+)?(?:Summons?|abilities?))*)?$"
    );
    private static final Pattern SELF_COND_DRAWN_N_OR_MORE = Pattern.compile(
        "(?i)^you\\s+have\\s+drawn\\s+(?<n>\\d+)\\s+or\\s+more\\s+cards$"
    );
    private static final Pattern SELF_COND_OPPONENT_CONTROLS_MORE_TYPE = Pattern.compile(
        "(?i)^the\\s+number\\s+of\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+your\\s+opponent\\s+controls\\s+is\\s+greater\\s+than\\s+the\\s+number\\s+of\\s+(?:Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control$"
    );
    private static final Pattern SELF_COND_CONTROL_A_CATEGORY_TYPE = Pattern.compile(
        "(?i)^you\\s+control\\s+a\\s+Category\\s+(?<cat>\\S+)\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)$"
    );
    private static final Pattern SELF_COND_CONTROL_CATEGORY_TYPE_NOT_ELEMENT = Pattern.compile(
        "(?i)^you\\s+control\\s+a\\s+Category\\s+(?<cat>\\S+)\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+of\\s+an\\s+Element\\s+other\\s+than\\s+(?<elem>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)$"
    );
    private static final Pattern SELF_COND_OWN_FORWARD_FORMED_PARTY = Pattern.compile(
        "(?i)^a\\s+Forward\\s+you\\s+controlled\\s+formed\\s+a\\s+party\\s+this\\s+turn$"
    );
    private static final Pattern SELF_COND_OPPONENT_HAND_N_OR_LESS = Pattern.compile(
        "(?i)^your\\s+opponent\\s+has\\s+(?<n>\\d+)\\s+cards?\\s+or\\s+less\\s+in\\s+their\\s+hand$"
    );
    private static final Pattern SELF_COND_N_OR_MORE_FORWARDS_LEFT_FIELD = Pattern.compile(
        "(?i)^(?<n>\\d+)\\s+or\\s+more\\s+Forwards\\s+have\\s+left\\s+the\\s+field$"
    );
    private static final Pattern SELF_COND_CONTROL_N_OR_MORE_JOB = Pattern.compile(
        "(?i)^you\\s+control\\s+(?<n>\\d+)\\s+or\\s+more\\s+Job\\s+(?<job>.+?)$"
    );
    private static final Pattern SELF_COND_ELEMENT_FORWARD_ENTERED_FIELD = Pattern.compile(
        "(?i)^an?\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Forward\\s+has\\s+entered\\s+your\\s+field\\s+this\\s+turn$"
    );
    private static final Pattern SELF_COND_OPPONENT_CONTROLS_N_OR_MORE_TYPE = Pattern.compile(
        "(?i)^your\\s+opponent\\s+controls\\s+(?<n>\\d+)\\s+or\\s+more\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)$"
    );
    private static final Pattern SELF_COND_FORWARD_ENTERED_VIA_WARP = Pattern.compile(
        "(?i)^a\\s+Forward\\s+has\\s+entered\\s+your\\s+field\\s+due\\s+to\\s+Warp\\s+this\\s+turn$"
    );
    private static final Pattern SELF_COND_N_OR_MORE_JOB_IN_BZ = Pattern.compile(
        "(?i)^you\\s+have\\s+(?<n>\\d+)\\s+or\\s+more\\s+Job\\s+(?<job>.+?)\\s+in\\s+your\\s+Break\\s+Zone$"
    );
    private static final Pattern SELF_COND_RECEIVED_EXACTLY_N_DAMAGE = Pattern.compile(
        "(?i)^you\\s+have\\s+received\\s+(?<n>\\d+)\\s+points?\\s+of\\s+damage$"
    );
    private static final Pattern SELF_COND_CONTROL_N_OR_MORE_ELEMENT_TYPE = Pattern.compile(
        "(?i)^you\\s+control\\s+(?<n>\\d+)\\s+or\\s+more\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)$"
    );
    private static final Pattern SELF_COND_CONTROL_N_OR_MORE_TYPE = Pattern.compile(
        "(?i)^you\\s+control\\s+(?<n>\\d+)\\s+or\\s+more\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)$"
    );
    private static final Pattern SELF_COND_BOTH_NAMES_IN_BZ = Pattern.compile(
        "(?i)^you\\s+have\\s+a\\s+Card\\s+Name\\s+(?<name1>.+?)\\s+and\\s+a\\s+Card\\s+Name\\s+(?<name2>.+?)\\s+in\\s+your\\s+Break\\s+Zone$"
    );
    private static final Pattern SELF_COND_OPPONENT_CONTROLS_N_MORE_THAN_ME = Pattern.compile(
        "(?i)^your\\s+opponent\\s+controls\\s+(?<n>\\d+)\\s+or\\s+more\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+more\\s+than\\s+you$"
    );
    private static final Pattern SELF_COND_CONTROL_JOB_OR_NAME = Pattern.compile(
        "(?i)^you\\s+control\\s+a\\s+Job\\s+(?<job>.+?)\\s+or\\s+Card\\s+Name\\s+(?<name>.+?)$"
    );
    /** Matches "you control a Job X" (no "or Card Name" — must be checked after CONTROL_JOB_OR_NAME). */
    private static final Pattern SELF_COND_CONTROL_A_JOB = Pattern.compile(
        "(?i)^you\\s+control\\s+a\\s+Job\\s+(?<job>.+?)$"
    );
    private static final Pattern SELF_COND_OPPONENT_CHAR_RETURNED_TO_HAND = Pattern.compile(
        "(?i)^a\\s+Character\\s+(?:your\\s+)?opponent\\s+controlled\\s+was\\s+returned\\s+from\\s+the\\s+field\\s+to\\s+its\\s+owner'?s\\s+hand\\s+this\\s+turn$"
    );
    private static final Pattern SELF_COND_CONTROL_N_OR_MORE_JOB_OR_NAME = Pattern.compile(
        "(?i)^you\\s+control\\s+(?<n>\\d+)\\s+or\\s+more\\s+Job\\s+(?<job>.+?)\\s+and/or\\s+Card\\s+Name\\s+(?<name>.+?)$"
    );
    private static final Pattern SELF_COND_N_OR_MORE_CATEGORY_BZ_AND_RFP = Pattern.compile(
        "(?i)^you\\s+have\\s+a\\s+total\\s+of\\s+(?<n>\\d+)\\s+or\\s+more\\s+Category\\s+(?<cat>\\S+)\\s+cards\\s+in\\s+your\\s+Break\\s+Zone\\s+and/or\\s+Category\\s+\\S+\\s+cards\\s+you\\s+own\\s+removed\\s+from\\s+the\\s+game$"
    );
    private static final Pattern SELF_COND_OWN_ELEMENT_OR_CATEGORY_BROKEN = Pattern.compile(
        "(?i)^a\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Characters?\\s+or\\s+Category\\s+(?<cat>\\S+)\\s+Characters?\\s+you\\s+controlled\\s+has\\s+been\\s+put\\s+from\\s+the\\s+field\\s+into\\s+the\\s+Break\\s+Zone\\s+this\\s+turn$"
    );
    private static final Pattern SELF_COND_CONTROL_A_ELEMENT_TYPE = Pattern.compile(
        "(?i)^you\\s+control\\s+a\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)$"
    );

    // Scaling sub-patterns
    private static final Pattern SELF_SCALE_EACH_FWD = Pattern.compile(
        "(?i)^for\\s+each\\s+Forward\\s+you\\s+control$"
    );
    private static final Pattern SELF_SCALE_EACH_BKP = Pattern.compile(
        "(?i)^for\\s+each\\s+Backup\\s+you\\s+control$"
    );
    private static final Pattern SELF_SCALE_EACH_CAT_FWD = Pattern.compile(
        "(?i)^for\\s+each\\s+\\[Category\\s+\\((?<cat>[^)]+)\\)\\]\\s+Forward\\s+you\\s+control$"
    );
    /** Matches "for each Category X Type you control" — plain (non-bracket) category form. */
    private static final Pattern SELF_SCALE_EACH_CAT_TYPE = Pattern.compile(
        "(?i)^for\\s+each\\s+Category\\s+(?<cat>\\S+)\\s+(?<type>Forwards?|Backups?|Monsters?|Summons?|Characters?)\\s+you\\s+control$"
    );
    private static final Pattern SELF_SCALE_EACH_DAMAGE = Pattern.compile(
        "(?i)^for\\s+each\\s+point\\s+of\\s+damage\\s+you\\s+have\\s+received$"
    );
    private static final Pattern SELF_SCALE_EACH_NAME_BZ = Pattern.compile(
        "(?i)^for\\s+each\\s+Card\\s+Name\\s+(?<name>.+?)\\s+in\\s+your\\s+Break\\s+Zone$"
    );
    /** Matches "for every N [Element] Types in your Break Zone" — element is optional. */
    private static final Pattern SELF_SCALE_PER_N_FILTERED_BZ = Pattern.compile(
        "(?i)^for\\s+every\\s+(?<n>\\d+)\\s+(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?(?<type>Forwards?|Backups?|Monsters?|Summons?|Characters?)\\s+in\\s+your\\s+Break\\s+Zone$"
    );
    private static final Pattern SELF_SCALE_PER_N_BZ = Pattern.compile(
        "(?i)^for\\s+every\\s+(?<n>\\d+)\\s+cards\\s+in\\s+your\\s+Break\\s+Zone$"
    );
    private static final Pattern SELF_SCALE_EACH_OPP_HAND = Pattern.compile(
        "(?i)^for\\s+each\\s+card\\s+in\\s+your\\s+opponent(?:'s|s')\\s+hand$"
    );
    private static final Pattern SELF_SCALE_EACH_CARD_CAST = Pattern.compile(
        "(?i)^for\\s+each\\s+card\\s+you\\s+have\\s+cast\\s+this\\s+turn$"
    );
    /** Matches "for each Element among [Type] opponent controls" — counts distinct elements. */
    private static final Pattern SELF_SCALE_EACH_DISTINCT_OPP_ELEM = Pattern.compile(
        "(?i)^for\\s+each\\s+Element\\s+among\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+(?:your\\s+)?opponent\\s+controls$"
    );
    /** Matches "for each 《C》you have" — scales by the controller's Crystal token count. */
    private static final Pattern SELF_SCALE_EACH_CRYSTAL = Pattern.compile(
        "(?i)^for\\s+each\\s+《C》\\s*you\\s+have$"
    );
    /** Matches "for each [Type] of cost N or more you control" — e.g. "for each Character of cost 5 or more you control". */
    private static final Pattern SELF_SCALE_EACH_TYPE_MIN_COST = Pattern.compile(
        "(?i)^for\\s+each\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+of\\s+cost\\s+(?<n>\\d+)\\s+or\\s+more\\s+you\\s+control$"
    );
    /** Matches "for each Backup of a different Element you control, other than Multi-Element". */
    private static final Pattern SELF_SCALE_EACH_DISTINCT_BACKUP_ELEM = Pattern.compile(
        "(?i)^for\\s+each\\s+Backup\\s+of\\s+a\\s+different\\s+Element\\s+you\\s+control,?\\s+other\\s+than\\s+Multi-Element$"
    );
    /** Matches "for each [Element] [Type] you control" — e.g. "for each Fire Backup you control". */
    private static final Pattern SELF_SCALE_EACH_ELEM_TYPE = Pattern.compile(
        "(?i)^for\\s+each\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control$"
    );
    /** Matches "for each Card Name X or Card Name Y you control". */
    private static final Pattern SELF_SCALE_EACH_NAME_OR_NAME = Pattern.compile(
        "(?i)^for\\s+each\\s+Card\\s+Name\\s+(?<name1>.+?)\\s+or\\s+Card\\s+Name\\s+(?<name2>.+?)\\s+you\\s+control$"
    );
    /** Matches "for each Job X and/or Element Type you control" (e.g. "Job Class Zero Cadet and/or Fire Character"). */
    private static final Pattern SELF_SCALE_EACH_JOB_OR_ELEM_TYPE = Pattern.compile(
        "(?i)^for\\s+each\\s+Job\\s+(?<job>.+?)\\s+and/or\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control$"
    );
    /** Matches "for each Job X [or Card Name Y] you control" — no "forward" keyword. */
    private static final Pattern SELF_SCALE_EACH_JOB = Pattern.compile(
        "(?i)^for\\s+each\\s+Job\\s+(?<job>.+?)(?:\\s+or\\s+Card\\s+Name\\s+(?<name>\\S+))?\\s+you\\s+control$"
    );
    /** Matches "for each CP required to cast the highest cost Element Forward you control". */
    private static final Pattern SELF_SCALE_HIGHEST_COST_ELEM_FWD = Pattern.compile(
        "(?i)^for\\s+each\\s+CP\\s+required\\s+to\\s+cast\\s+the\\s+highest\\s+cost\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Forward\\s+you\\s+control$"
    );
    /** Matches "for every N Element Type you control". */
    private static final Pattern SELF_SCALE_PER_N_ELEM_TYPE = Pattern.compile(
        "(?i)^for\\s+every\\s+(?<n>\\d+)\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control$"
    );
    private static final Pattern SELF_SCALE_EACH_MONSTER = Pattern.compile(
        "(?i)^for\\s+each\\s+Monster\\s+you\\s+control$"
    );
    private static final Pattern SELF_SCALE_EACH_CARD_DRAWN = Pattern.compile(
        "(?i)^for\\s+each\\s+card\\s+you\\s+have\\s+drawn\\s+this\\s+turn$"
    );

    /** Returns true if {@code seg} is a self-cost modifier sentence handled via {@link #parseSelfCostModifiers}. */
    public static boolean isSelfCostModifierText(String seg) {
        return SELF_COST_MAIN.matcher(seg).find();
    }

    /**
     * Returns true if {@code seg} actually yields a self-cost modifier — that is, if
     * {@link #parseSelfCostModifiers} builds one from it.
     *
     * <p>Prefer this over {@link #isSelfCostModifierText} wherever a true answer is taken to mean
     * "handled by the self-cost system, so no other handler need claim it". That predicate only
     * asks whether {@code SELF_COST_MAIN} matches <em>somewhere</em> in the segment, via
     * {@code find()}, so any larger ability ending in a cost-reduction clause trips it — "you may
     * cast 1 Summon from your hand. The cost required to cast <em>it</em> is reduced by 1" matches
     * with the name group capturing the pronoun. {@code parseSelfCostModifiers} correctly declines
     * to build a modifier from that, so the loose predicate reports a card as handled while nothing
     * handles it.
     */
    public static boolean yieldsSelfCostModifier(String seg) {
        return !parseSelfCostModifiers(seg).isEmpty();
    }

    /**
     * Parses self-referential cost modifiers from a card's own text.
     * These adjust the card's own play/cast cost based on game state at the time of play.
     */
    public static List<SelfCostModifier> parseSelfCostModifiers(String textEn) {
        if (textEn == null || textEn.isBlank()) return List.of();

        List<SelfCostModifier> result = new ArrayList<>();
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;

            Matcher m = SELF_COST_MAIN.matcher(seg);
            if (!m.find()) continue;

            String condRaw    = m.group("cond");
            String yourTurnRaw = m.group("yourturn");
            String scalingRaw = m.group("scaling");
            String becomesRaw = m.group("becomes");
            String amountRaw  = m.group("amount");
            // A sentence that adjusts nothing and permits nothing is not a cost modifier — it is a
            // fragment of some larger ability that happens to open the same way. Cloud 21-090R is
            // the case that makes the delta optional at all, and it always carries the permission.
            if (amountRaw == null && becomesRaw == null && m.group("anyelem") == null) continue;
            boolean isIncrease = "increased".equalsIgnoreCase(m.group("dir"));
            int amount = amountRaw != null ? Integer.parseInt(amountRaw) : 0;

            int minCost = 0;
            if (seg.contains("(it cannot become 0)"))          minCost = 1;
            else if (seg.contains("(it cannot become 1 or less)")) minCost = 2;

            SelfCostModifier mod = null;

            // --- Replacement form: "If you control Card Name X, the cost … becomes N." ---
            // Only the named-control condition prints this way, so it is read here rather than
            // threaded through every condition branch below as a second kind of amount.
            if (becomesRaw != null) {
                if (condRaw == null) continue;
                Matcher cn = SELF_COND_CONTROL_NAME.matcher(condRaw.trim());
                if (!cn.find()) continue;
                result.add(new SelfCostModifier(0, 0, false,
                        SelfCostModifier.ScalingType.IF_CONTROL_NAME,
                        cn.group("name").trim(), null, Integer.parseInt(becomesRaw)));
                continue;
            }

            // --- "During your turn" flat form ---
            if (yourTurnRaw != null && condRaw == null && scalingRaw == null) {
                mod = new SelfCostModifier(amount, minCost, isIncrease,
                        SelfCostModifier.ScalingType.IF_IS_YOUR_TURN, null, null);
            }

            // --- Condition prefix forms ---
            if (mod == null && condRaw != null && scalingRaw == null) {
                Matcher cm;
                cm = SELF_COND_CAST_SUMMON.matcher(condRaw.trim());
                if (cm.find()) {
                    mod = new SelfCostModifier(amount, minCost, isIncrease,
                            SelfCostModifier.ScalingType.IF_CAST_SUMMON_THIS_TURN, null, null);
                }
                if (mod == null) {
                    cm = SELF_COND_CAST_JOB_OR_NAME.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_CAST_JOB_OR_NAME_THIS_TURN,
                                cm.group("job").trim(), cm.group("name").trim());
                    }
                }
                // Must precede SELF_COND_CONTROL_NAME — see that pattern's note.
                if (mod == null) {
                    cm = SELF_COND_CONTROL_NAME_OR_NAME.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_CONTROL_NAME_OR_NAME,
                                cm.group("name1").trim(), cm.group("name2").trim());
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_CONTROL_NAME.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_CONTROL_NAME,
                                cm.group("name").trim(), null);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_RECEIVED_N_DAMAGE.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_RECEIVED_N_DAMAGE_OR_MORE,
                                cm.group("n").trim(), null);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_OPP_FWD_BROKEN.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_OPPONENT_FORWARD_BROKEN_THIS_TURN,
                                null, null);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_CONTROL_N_CATEGORY_TYPE.matcher(condRaw.trim());
                    if (cm.find()) {
                        String type = cm.group("type").replaceAll("(?i)s$", "");
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_CONTROL_N_OR_MORE_CATEGORY_TYPE,
                                cm.group("n").trim(),
                                cm.group("cat").trim() + "|" + type);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_OWN_JOB_BROKEN.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_OWN_JOB_BROKEN_THIS_TURN,
                                cm.group("job").trim(), null);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_CONTROL_NONE_OF_TYPE.matcher(condRaw.trim());
                    if (cm.find()) {
                        String type = cm.group("type").replaceAll("(?i)s$", "");
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_CONTROL_NONE_OF_TYPE,
                                type, null);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_OPPONENT_DISCARDED.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_OPPONENT_DISCARDED_THIS_TURN,
                                null, null);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_DRAWN_N_OR_MORE.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_DRAWN_N_OR_MORE_THIS_TURN,
                                cm.group("n").trim(), null);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_OPPONENT_CONTROLS_MORE_TYPE.matcher(condRaw.trim());
                    if (cm.find()) {
                        String type = cm.group("type").replaceAll("(?i)s$", "");
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_OPPONENT_CONTROLS_MORE_TYPE,
                                type, null);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_OPPONENT_DISCARDED_BY_ME.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_OPPONENT_DISCARDED_BY_ME_THIS_TURN,
                                null, null);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_CONTROL_CATEGORY_TYPE_NOT_ELEMENT.matcher(condRaw.trim());
                    if (cm.find()) {
                        String type = cm.group("type").replaceAll("(?i)s$", "");
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_CONTROL_CATEGORY_TYPE_NOT_ELEMENT,
                                cm.group("cat").trim() + "|" + type,
                                cm.group("elem").trim());
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_CONTROL_A_CATEGORY_TYPE.matcher(condRaw.trim());
                    if (cm.find()) {
                        String type = cm.group("type").replaceAll("(?i)s$", "");
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_CONTROL_N_OR_MORE_CATEGORY_TYPE,
                                "1", cm.group("cat").trim() + "|" + type);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_OWN_FORWARD_FORMED_PARTY.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_OWN_FORWARD_FORMED_PARTY_THIS_TURN,
                                null, null);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_OPPONENT_HAND_N_OR_LESS.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_OPPONENT_HAND_N_OR_LESS,
                                cm.group("n").trim(), null);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_N_OR_MORE_FORWARDS_LEFT_FIELD.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_N_OR_MORE_FORWARDS_LEFT_FIELD_THIS_TURN,
                                cm.group("n").trim(), null);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_CONTROL_N_OR_MORE_JOB_OR_NAME.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_CONTROL_N_OR_MORE_JOB_OR_NAME,
                                cm.group("n").trim(),
                                cm.group("job").trim() + "|" + cm.group("name").trim());
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_CONTROL_N_OR_MORE_JOB.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_CONTROL_N_OR_MORE_JOB,
                                cm.group("n").trim(), cm.group("job").trim());
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_CONTROL_JOB_OR_NAME.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_CONTROL_JOB_OR_NAME,
                                cm.group("job").trim(), cm.group("name").trim());
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_CONTROL_A_JOB.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_CONTROL_N_OR_MORE_JOB,
                                "1", cm.group("job").trim());
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_OPPONENT_CHAR_RETURNED_TO_HAND.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_OPPONENT_CHARACTER_RETURNED_TO_HAND_THIS_TURN,
                                null, null);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_N_OR_MORE_CATEGORY_BZ_AND_RFP.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_N_OR_MORE_CATEGORY_IN_BZ_AND_RFP,
                                cm.group("n").trim(), cm.group("cat").trim());
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_OWN_ELEMENT_OR_CATEGORY_BROKEN.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_OWN_ELEMENT_OR_CATEGORY_BROKEN_THIS_TURN,
                                cm.group("element").trim(), cm.group("cat").trim());
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_ELEMENT_FORWARD_ENTERED_FIELD.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_ELEMENT_FORWARD_ENTERED_FIELD_THIS_TURN,
                                cm.group("element").trim(), null);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_OPPONENT_CONTROLS_N_OR_MORE_TYPE.matcher(condRaw.trim());
                    if (cm.find()) {
                        String type = cm.group("type").replaceAll("(?i)s$", "");
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_OPPONENT_CONTROLS_N_OR_MORE_TYPE,
                                cm.group("n").trim(), type);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_FORWARD_ENTERED_VIA_WARP.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_FORWARD_ENTERED_VIA_WARP_THIS_TURN,
                                null, null);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_N_OR_MORE_JOB_IN_BZ.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_N_OR_MORE_JOB_IN_BZ,
                                cm.group("n").trim(), cm.group("job").trim());
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_RECEIVED_EXACTLY_N_DAMAGE.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_RECEIVED_EXACTLY_N_DAMAGE,
                                cm.group("n").trim(), null);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_CONTROL_N_OR_MORE_ELEMENT_TYPE.matcher(condRaw.trim());
                    if (cm.find()) {
                        String type = cm.group("type").replaceAll("(?i)s$", "");
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_CONTROL_N_OR_MORE_ELEMENT_TYPE,
                                cm.group("n").trim(),
                                cm.group("element").trim() + "|" + type);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_CONTROL_A_ELEMENT_TYPE.matcher(condRaw.trim());
                    if (cm.find()) {
                        String type = cm.group("type").replaceAll("(?i)s$", "");
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_CONTROL_N_OR_MORE_ELEMENT_TYPE,
                                "1",
                                cm.group("element").trim() + "|" + type);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_CONTROL_N_OR_MORE_TYPE.matcher(condRaw.trim());
                    if (cm.find()) {
                        String type = cm.group("type").replaceAll("(?i)s$", "");
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_CONTROL_N_OR_MORE_TYPE,
                                cm.group("n").trim(), type);
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_BOTH_NAMES_IN_BZ.matcher(condRaw.trim());
                    if (cm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_BOTH_NAMES_IN_BZ,
                                cm.group("name1").trim(), cm.group("name2").trim());
                    }
                }
                if (mod == null) {
                    cm = SELF_COND_OPPONENT_CONTROLS_N_MORE_THAN_ME.matcher(condRaw.trim());
                    if (cm.find()) {
                        String type = cm.group("type").replaceAll("(?i)s$", "");
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.IF_OPPONENT_CONTROLS_N_MORE_THAN_ME,
                                cm.group("n").trim(), type);
                    }
                }
            }

            // --- Scaling suffix forms ---
            if (mod == null && scalingRaw != null) {
                String sc = scalingRaw.trim();
                Matcher sm;

                sm = SELF_SCALE_EACH_FWD.matcher(sc);
                if (sm.find()) {
                    mod = new SelfCostModifier(amount, minCost, isIncrease,
                            SelfCostModifier.ScalingType.EACH_FORWARD, null, null);
                }

                if (mod == null) {
                    sm = SELF_SCALE_EACH_BKP.matcher(sc);
                    if (sm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.EACH_BACKUP, null, null);
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_EACH_CAT_FWD.matcher(sc);
                    if (sm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.EACH_FORWARD_WITH_CATEGORY,
                                sm.group("cat").trim(), null);
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_EACH_CAT_TYPE.matcher(sc);
                    if (sm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.EACH_CATEGORY_TYPE_CONTROLLED,
                                sm.group("cat").trim(), sm.group("type").trim());
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_EACH_DAMAGE.matcher(sc);
                    if (sm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.EACH_DAMAGE_RECEIVED, null, null);
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_EACH_NAME_BZ.matcher(sc);
                    if (sm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.EACH_NAME_IN_BZ,
                                sm.group("name").trim(), null);
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_PER_N_FILTERED_BZ.matcher(sc);
                    if (sm.find()) {
                        String elem = sm.group("element");
                        String type = sm.group("type");
                        // Normalize type to singular for consistent matching downstream
                        String normalizedType = type.replaceAll("(?i)s$", "");
                        String filter = (elem != null ? elem : "") + "|" + normalizedType;
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.PER_N_FILTERED_BZ_CARDS,
                                sm.group("n").trim(), filter);
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_PER_N_BZ.matcher(sc);
                    if (sm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.PER_N_BZ_CARDS,
                                sm.group("n").trim(), null);
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_EACH_OPP_HAND.matcher(sc);
                    if (sm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.EACH_OPPONENT_HAND_CARD, null, null);
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_EACH_CARD_CAST.matcher(sc);
                    if (sm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.EACH_CARD_CAST_THIS_TURN, null, null);
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_EACH_DISTINCT_OPP_ELEM.matcher(sc);
                    if (sm.find()) {
                        String type = sm.group("type").replaceAll("(?i)s$", "");
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.EACH_DISTINCT_OPPONENT_TYPE_ELEMENT,
                                type, null);
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_EACH_CRYSTAL.matcher(sc);
                    if (sm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.EACH_CRYSTAL_YOU_HAVE, null, null);
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_EACH_TYPE_MIN_COST.matcher(sc);
                    if (sm.find()) {
                        String type = sm.group("type").replaceAll("(?i)s$", "");
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.EACH_TYPE_WITH_MIN_COST,
                                sm.group("n").trim(), type);
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_EACH_DISTINCT_BACKUP_ELEM.matcher(sc);
                    if (sm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.EACH_DISTINCT_BACKUP_ELEMENT, null, null);
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_EACH_ELEM_TYPE.matcher(sc);
                    if (sm.find()) {
                        String type = sm.group("type").replaceAll("(?i)s$", "");
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.EACH_ELEMENT_TYPE_CONTROLLED,
                                sm.group("element").trim(), type);
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_EACH_NAME_OR_NAME.matcher(sc);
                    if (sm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.EACH_NAME_OR_NAME_CONTROLLED,
                                sm.group("name1").trim(), sm.group("name2").trim());
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_EACH_MONSTER.matcher(sc);
                    if (sm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.EACH_MONSTER, null, null);
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_EACH_CARD_DRAWN.matcher(sc);
                    if (sm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.EACH_CARD_DRAWN_THIS_TURN, null, null);
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_PER_N_ELEM_TYPE.matcher(sc);
                    if (sm.find()) {
                        String type = sm.group("type").replaceAll("(?i)s$", "");
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.PER_N_ELEMENT_TYPE_CONTROLLED,
                                sm.group("n").trim(),
                                sm.group("element").trim() + "|" + type);
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_HIGHEST_COST_ELEM_FWD.matcher(sc);
                    if (sm.find()) {
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.HIGHEST_COST_ELEMENT_FORWARD,
                                sm.group("element").trim(), null);
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_EACH_JOB_OR_ELEM_TYPE.matcher(sc);
                    if (sm.find()) {
                        String job  = sm.group("job").trim();
                        String elem = sm.group("element");
                        String type = sm.group("type");
                        String typeNorm = type.toLowerCase().startsWith("forward") ? "Forward"
                                        : type.toLowerCase().startsWith("backup")  ? "Backup"
                                        : type.toLowerCase().startsWith("monster") ? "Monster"
                                        : "Character";
                        mod = new SelfCostModifier(amount, minCost, isIncrease,
                                SelfCostModifier.ScalingType.EACH_JOB_OR_ELEMENT_TYPE_CONTROLLED,
                                job, elem + "|" + typeNorm);
                    }
                }

                if (mod == null) {
                    sm = SELF_SCALE_EACH_JOB.matcher(sc);
                    if (sm.find()) {
                        String job  = sm.group("job").trim();
                        String name = sm.group("name");
                        if (name != null) {
                            mod = new SelfCostModifier(amount, minCost, isIncrease,
                                    SelfCostModifier.ScalingType.EACH_FORWARD_WITH_JOB_OR_NAME,
                                    job, name.trim());
                        } else {
                            mod = new SelfCostModifier(amount, minCost, isIncrease,
                                    SelfCostModifier.ScalingType.EACH_FORWARD_WITH_JOB,
                                    job, null);
                        }
                    }
                }
            }

            // The "…and can be paid with CP of any Element" tail rides whichever branch above built
            // the modifier — it qualifies the same cost under the same condition, so it is applied
            // once here rather than threaded through every branch.
            if (mod != null && m.group("anyelem") != null) mod = mod.withAnyElement();
            if (mod != null) result.add(mod);
        }
        return List.copyOf(result);
    }
}
