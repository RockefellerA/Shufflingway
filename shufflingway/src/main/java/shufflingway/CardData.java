package shufflingway;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
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
        boolean canAttackTwice,                   // "cardName can attack twice in the same turn."
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
        CANNOT_BE_DULLED_BY_OPP
    }

    /** Defensive copy — collection fields are always immutable after construction. */
    public CardData {
        traits          = Set.copyOf(traits);
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

    /** Parses one "N Element Type" requirement phrase from a BZ-removal list. */
    private static final Pattern BZ_REMOVAL_ENTRY = Pattern.compile(
        "(?i)(\\d+)\\s+(Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
        "(?:\\s+(Character|Forward|Backup|Monster))?"
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
            int altCp = Math.max(0, cost - Integer.parseInt(m.group("reduction")));
            List<String> elems = new ArrayList<>();
            String[] cardElems = elements();
            for (int i = 0; i < altCp; i++) elems.add(cardElems.length > 0 ? cardElems[i % cardElems.length] : "");
            return List.copyOf(elems);
        }
        m = ALT_COST_NONSUMMON.matcher(textEn);
        if (m.find()) return List.copyOf(parseCostTokens(m.group("costs"), crystals));
        return List.of();
    }

    /** Convenience: total CP to pay for the alternate cast ({@code altCpElements().size()}). */
    public int altCpCost() { return altCpElements().size(); }

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
     * Returns the list of extra CP elements this backup can produce when on the field
     * (the "If [Name] is on the field, [Name] can produce X [or Y] CP." ability).
     * Returns an empty list if no such ability exists.
     */
    public List<String> backupCpExtraElements() {
        Matcher m = BACKUP_CP_EXTRA_ELEMENTS.matcher(textEn);
        if (!m.find()) return List.of();
        return List.of(m.group("elems").split("(?i)\\s+or\\s+"));
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

    /**
     * Returns a {@link BackupCpGrant} describing the field-ability grant on this card, or
     * {@code null} if no such ability is present.  All three filter fields are {@code null}
     * when the grant applies to every Backup (the unconditional form).
     */
    public BackupCpGrant backupCpGrant() {
        Matcher m = BACKUP_CP_GRANT.matcher(textEn);
        if (!m.find()) return null;
        return new BackupCpGrant(m.group("job"), m.group("category"), m.group("element"));
    }

    /** Returns {@code true} if {@code text} describes any "produce CP" backup ability (self or grant). */
    static boolean isBackupCpAbility(String text) {
        return BACKUP_CP_ANY_ELEM_ALWAYS.matcher(text).find()
            || BACKUP_CP_EXTRA_ELEMENTS.matcher(text).find();
    }

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
        "(?!\\s+in\\s+your\\s+Break\\s+Zone)[.!]?"
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
     * "You must control a Category X Forward to play [name] from your hand onto the field."
     * Group {@code cat} — the category token (e.g. "XIV").
     */
    private static final Pattern CAST_MUST_CONTROL_CATEGORY_FWD = Pattern.compile(
        "(?i)You\\s+must\\s+control\\s+a\\s+Category\\s+(?<cat>\\S+)\\s+Forward\\s+" +
        "to\\s+play\\s+\\S[^.]*?\\s+from\\s+your\\s+hand\\s+onto\\s+the\\s+field[.!]?"
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
     * Returns a {@link CastRestriction} describing any "You can only cast …" constraint on this
     * card, or {@code null} if no such restriction is present.
     */
    public CastRestriction castRestriction() {
        boolean yourTurnOnly     = CAST_YOUR_TURN_ONLY.matcher(textEn).find();
        boolean mainPhaseOnly    = CAST_MAIN_PHASE_ONLY.matcher(textEn).find();
        boolean opponentTurnOnly = CAST_OPPONENT_TURN_ONLY.matcher(textEn).find();
        boolean requiresNoFwds   = CAST_REQUIRES_NO_FORWARDS.matcher(textEn).find();
        boolean requiresAFwd     = CAST_REQUIRES_A_FORWARD.matcher(textEn).find();

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

        if (!yourTurnOnly && !mainPhaseOnly && !opponentTurnOnly && !requiresNoFwds
                && !requiresAFwd && requiredBZTypes.isEmpty()
                && minBZAndRfpSummons == 0 && maxOpponentHand < 0 && mustControl == null) {
            return null;
        }
        return new CastRestriction(yourTurnOnly, mainPhaseOnly, opponentTurnOnly,
                requiresNoFwds, requiresAFwd, requiredBZTypes, minBZAndRfpSummons,
                maxOpponentHand, mustControl);
    }

    /**
     * Returns cleaned effect text for a Summon: strips the {@code [[ex]]} exBurst prefix,
     * then (when an alternate cost exists) splits on {@code [[br]]} and skips segments that
     * are trait-only lines or alternate-cost blocks.  All remaining markup tags are removed
     * and whitespace is collapsed.
     */
    public String summonEffect() {
        String t = SUMMON_EX_PREFIX.matcher(textEn).replaceFirst("");
        if (altCrystalCost() > 0) {
            String[] parts = SUMMON_BR.split(t);
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                if (ALT_COST_SUMMON.matcher(trimmed).find())    continue; // alt-cost block
                if (ALT_COST_NONSUMMON.matcher(trimmed).find()) continue; // alt-cost block
                if (TRAIT_ONLY_SEGMENT.matcher(trimmed).matches()) continue; // trait-only line
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
            int brIdx = after.toLowerCase(java.util.Locale.ROOT).indexOf("[[br]]");
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

    // Permanent printed "cannot be broken" field ability (e.g. Cid (WOFF), Ardyn).
    // Matched against card text with quoted grant forms stripped, to avoid matching
    // temporary "gains 'This Forward cannot be broken'" text.
    private static final Pattern CANNOT_BE_BROKEN_TRAIT_PATTERN = Pattern.compile(
        "(?i)cannot\\s+be\\s+broken(?!\\s+(?:this\\s+turn|until|by))\\s*[.!]?"
    );

    // Permanent "cannot be broken by [opposing Summons or] abilities that don't deal damage" (e.g. Vincent, Wicked Thunder).
    private static final Pattern CANNOT_BE_BROKEN_BY_NON_DMG_TRAIT_PATTERN = Pattern.compile(
        "(?i)cannot\\s+be\\s+broken\\s+by\\s+(?:opposing\\s+)?(?:Summons?\\s+or\\s+)?abilit(?:y|ies)\\s+that\\s+don'?t\\s+deal\\s+damage[.!]?"
    );

    // Field-ability grants: "[CardName] gains '[CardName] cannot be broken by … abilities that don't deal damage.'"
    private static final Pattern SELF_NON_DMG_BREAK_SHIELD_GRANT = Pattern.compile(
        "(?i)^(?<name>.+?)\\s+gains?\\s+['\"][^'\"]*?cannot\\s+be\\s+broken\\s+by\\s+(?:opposing\\s+)?(?:Summons?\\s+or\\s+)?abilit(?:y|ies)\\s+that\\s+don'?t\\s+deal\\s+damage\\.?['\"][.!]?$"
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
        "((?i)(?:,\\s*)?put\\s+(?:(?!\\[\\[br\\]\\]).)+?\\s+into\\s+the\\s+Break\\s+Zone\\s*)?"  + // group 4: optional BZ cost phrase
        "((?i)(?:,\\s*)?discard(?:(?!,\\s*(?:remove|return)\\b)[^:])+)?"     +  // group 5: optional discard cost phrase
        "((?i)(?:,\\s*)?remove\\s+[^:]+?\\s+from\\s+(?:the\\s+)?game\\s*)?" + // group 6: optional remove-from-game cost phrase
        "((?i)(?:,\\s*)?return\\s+[^:]+?\\s+to\\s+(?:its|their)\\s+owner(?:'s|s')?\\s+hand\\s*)?" + // group 7: optional return-to-hand cost phrase
        "((?i)(?:,\\s*)?remove\\s+\\d+\\s+[^:]+?\\s+Counters?\\s+from\\s+[^:,]+?\\s*)?" +           // group 8: optional counter-removal cost phrase
        "(?<dullcost>(?i)(?:,\\s*)?Dull\\s+(?:a\\s+total\\s+of\\s+)?(?<dullcount>\\d+)\\s*(?<dullcond>active|dull|damaged)?\\s*" + // group 9 (named): optional Dull N [cond] Forward(s) cost — simple or Card Name form
        "(?:Card\\s+Name\\s+.+?\\s+Forwards?" +                                              // named-card branch: "Dull N [cond] Card Name X Forward [and N [cond] Card Name Y Forward]"
        "(?:\\s+and\\s+\\d+\\s*(?:active|dull|damaged)?\\s*Card\\s+Name\\s+.+?\\s+Forwards?)*" +
        "|Category\\s+(?<dullcat>[A-Za-z0-9][A-Za-z0-9\\s''\\-]*?)(?:\\s+(?:Forwards?|Backups?|Monsters?|Characters?))?" + // category branch
        "|Job\\s+(?<dulljob>[A-Za-z][A-Za-z''\\s\\-]*?)(?:\\s+(?:Forwards?|Backups?|Monsters?|Characters?))?(?:\\s+(?:and/)?or\\s+Card\\s+Name\\s+[^:]+?)?" + // job branch: "Dull N [cond] Job X [and/or Card Name Y]"
        "|(?<dullelem>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)?\\s*" + // standard branch
        "(?:Forwards?(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)?\\s*Backups?)?" + // Forwards [or Backups]
        "|Backups?(?:\\s+of\\s+the\\s+same\\s+Element)?(?:\\s+or\\s+\\d+\\s*(?:active|dull|damaged)?\\s*Backups?\\s+of\\s+the\\s+same\\s+Element(?:\\s+and\\s+[A-Za-z][A-Za-z\\s''\\-]*?)?)?" + // Backups [of the same Element] [or N Backups ... and Name]
        "|Characters?))\\s*)?" + // Characters
        ":\\s*"                                                              +  // colon separator
        "(?<effecttext>(?:[^\\[]|\\[(?!\\[))*)"                                // effect text (up to next [[markup]])
    );

    // Captures the content between "put " and " into the Break Zone"
    private static final Pattern BREAK_ZONE_COST_PATTERN = Pattern.compile(
        "(?i)put\\s+(.+?)\\s+into\\s+the\\s+Break\\s+Zone"
    );

    /** Matches a single dull cost item: Card Name, Category, Job, or element-filtered Forward/Backup/Character. */
    private static final Pattern DULL_COST_ITEM_PATTERN = Pattern.compile(
        "(?i)Dull\\s+(?:a\\s+total\\s+of\\s+)?(?<count>\\d+)\\s*(?<cond>active|dull|damaged)?\\s*" +
        "(?:Card\\s+Name\\s+(?<cardname>.+?)\\s+Forwards?" +
        "|Category\\s+(?<category>[A-Za-z0-9][A-Za-z0-9\\s''\\-]*?)(?:\\s+(?:Forwards?|Backups?|Monsters?|(?<catchar>Characters?)))?" +
        "|Job\\s+(?<job>[A-Za-z][A-Za-z''\\s\\-]*?)(?:\\s+(?:Forwards?|Backups?|Monsters?|(?<jobchar>Characters?)))?(?:\\s+(?:and/)?or\\s+Card\\s+Name\\s+(?<joborcardname>.+))?" +
        "|(?<elem>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)?\\s*" +
        "(?:Forwards?(?<orbackup>\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)?\\s*Backups?)?" + // Forwards [or Backups]
        "|(?<sameelembackup>Backups?(?:\\s+of\\s+the\\s+same\\s+Element)?)" + // Backups [of same element] — e.g. Yuri
        "|(?<stdchar>Characters?)))"
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

        Matcher m = ACTION_ABILITY_PATTERN.matcher(textEn);
        while (m.find()) {
            String thresholdStr  = m.group(1);
            int    damageThreshold = thresholdStr != null ? Integer.parseInt(thresholdStr) : 0;
            String rawName       = m.group(2);
            String costPart      = m.group(3);
            String bzRaw         = m.group(4);
            String discardRaw    = m.group(5);
            String removeRaw     = m.group(6);
            String returnRaw     = m.group(7);
            String counterRaw    = m.group(8);
            String dullCostRaw   = m.group("dullcost");
            String effectRaw     = DAMAGE_THRESHOLD_REMINDER_PAREN.matcher(m.group("effecttext").trim()).replaceAll("").trim();
            if (effectRaw.isEmpty()) continue;
            // Skip if there are no CP tokens or any non-CP cost phrase (spurious match)
            if ((costPart == null || costPart.isBlank()) && bzRaw == null && discardRaw == null
                    && removeRaw == null && returnRaw == null && counterRaw == null
                    && dullCostRaw == null) continue;

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
            Matcher blockerForAttackerM = BREAK_FORWARD_THAT_BLOCKS_NAMED_CARD.matcher(effectRaw);
            String  blockerForAttacker  = blockerForAttackerM.find() ? blockerForAttackerM.group("name").trim() : null;
            Matcher elemFwdM = ELEMENT_FORWARD_ENTERED_THIS_TURN_PATTERN.matcher(effectRaw);
            String  requiresElementForwardEnteredThisTurn = elemFwdM.find() ? elemFwdM.group("element").toLowerCase() : null;
            Matcher cardNameFwdM = CARD_NAME_ENTERED_THIS_TURN_PATTERN.matcher(effectRaw);
            String  requiresCardNameEnteredThisTurn = cardNameFwdM.find() ? cardNameFwdM.group("cardname").trim() : null;
            Matcher ownBzM  = OWN_BZ_NAME_REQUIRED_RESTRICTION.matcher(effectRaw);
            String  ownBzCard = ownBzM.find() ? ownBzM.group("card").trim() : null;
            String  breakZoneOnly = null;
            if (ownBzCard == null) {
                Matcher bzOnlyM = CARD_IN_BREAK_ZONE_PATTERN.matcher(effectRaw);
                breakZoneOnly = bzOnlyM.find() ? bzOnlyM.group("card").trim() : null;
            }
            ControlCondition controlCondition = null;
            if (!whileCardInHand && breakZoneOnly == null && ownBzCard == null) {
                Matcher compM = YOUR_TURN_AND_CONTROL_IF_PATTERN.matcher(effectRaw);
                if (compM.find()) {
                    controlCondition = parseControlCondition(compM.group("condition"));
                } else {
                    Matcher ctrlM = CONTROL_IF_PATTERN.matcher(effectRaw);
                    if (ctrlM.find()) {
                        controlCondition = parseControlCondition(ctrlM.group("condition"));
                    } else {
                        Matcher notM = CONTROL_IF_NOT_ANY_PATTERN.matcher(effectRaw);
                        if (notM.find()) {
                            String rawType = notM.group("type");
                            String cardType = rawType.replaceAll("(?i)s$", "").trim();
                            cardType = Character.toUpperCase(cardType.charAt(0)) + cardType.substring(1).toLowerCase();
                            controlCondition = new ControlCondition(List.of(), 0, true, cardType, null, null, null, 0, List.of());
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
            String counterScaleName = csrM.find() ? csrM.group("counterName").trim()
                                    : cfeM.find() ? cfeM.group("counterName").trim() : null;
            Matcher cminM = COUNTER_MINIMUM_RESTRICTION.matcher(effectRaw);
            int    minCounterRequired = 0;
            String minCounterType     = null;
            if (cminM.find()) {
                minCounterRequired = cminM.group("count") != null ? Integer.parseInt(cminM.group("count")) : 1;
                minCounterType     = cminM.group("type").trim();
            }
            Matcher oppHandM = OPP_HAND_AT_MOST_RESTRICTION.matcher(effectRaw);
            int maxOpponentHandSize = oppHandM.find() ? Integer.parseInt(oppHandM.group("count")) : -1;
            result.add(new ActionAbility(abilityName, requiresDull, isSpecial, crystalCost, selfMillCost, hasXCost, cpCost, breakZoneCosts, discardCosts, removeFromGameCosts, returnToHandCosts, counterCosts, dullForwardCosts, yourTurnOnly, opponentTurnOnly, oncePerTurn, mainPhaseOnly, whileCardAtk, whileCardBlk, whilePartyAtk, whileCardInHand, hasBlockingTarget, effectRaw, damageThreshold, controlCondition, cpBackupElement, cpAllowedElements, sourceInBattle, requiresOppDiscardedThisTurn, requiresCastSummonThisTurn, requiresElementForwardEnteredThisTurn, requiresCardNameEnteredThisTurn, breakZoneOnly, requiresOpponentEmptyHand, requiresSelfEmptyHand, requiresNamedCardTookDamageThisTurn, requiresSelfReceivedDamageThisTurn, requiresForwardPutToBZThisTurn, blockerForAttacker, ownBzCard, counterScaleName, minCounterRequired, minCounterType, maxOpponentHandSize, requiresSourceIsForward));
        }
        return List.copyOf(result);
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
                a.requiresForwardPutToBZThisTurn(), a.blockerForAttacker(), bzCard,
                a.counterScaleName(), a.minCounterRequired(), a.minCounterType(), a.maxOpponentHandSize(), a.requiresSourceIsForward());
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
     * [CardName] is in the Break Zone." — used for field abilities that require a named card to be in the
     * controller's Break Zone.  The "during your Main Phase and" prefix is optional (combined restriction).
     * Distinct from {@link #CARD_IN_BREAK_ZONE_PATTERN}, which is used for BZ-only abilities.
     */
    static final Pattern OWN_BZ_NAME_REQUIRED_RESTRICTION = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+(?:during\\s+your\\s+Main\\s+Phase\\s+and\\s+)?if\\s+" +
        "(?<card>\\S+(?:\\s+\\S+){0,2})\\s+is\\s+in\\s+the\\s+Break\\s+Zone[.!]?\\s*$"
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

    /** Captures the counter type name from "the same number of X as the [Name] Counters placed on [card]". */
    static final Pattern COUNTER_SCALE_REF_PATTERN = Pattern.compile(
        "(?i)the\\s+same\\s+number\\s+of.+?as\\s+the\\s+(?<counterName>.+?)\\s+Counters?\\s+placed\\s+on\\s+.+?(?:[,.]|\\s*$)"
    );

    /** Captures the counter type name from "for each [Name] Counter(s) placed on [card]". */
    static final Pattern FOR_EACH_COUNTER_PLACED_ON_PATTERN = Pattern.compile(
        "(?i)for\\s+each\\s+(?<counterName>.+?)\\s+Counters?\\s+placed\\s+on\\s+.+?(?:[,.]|\\s*$)"
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
    private static final Pattern CONTROL_COUNT_CONDITION_PATTERN = Pattern.compile(
        "(?i)" +
        "(?:(?<count>\\d+)\\s+or\\s+more|only\\s+(?<exactn>\\d+)|a(?:n)?\\s+)\\s*" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark|Multi-Element)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?:Job\\s+(?<job>.+?)(?=\\s+(?:Forwards?|Monsters?|Backups?|Characters?)(?:\\s|$)|\\s+or\\s+Card\\s+Name\\b|\\s*$))?" +
        "(?<type>Forwards?|Monsters?|Backups?|Characters?)?" +
        "(?:\\s+of\\s+an?\\s+Element\\s+other\\s+than\\s+(?<excludeelem>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))?" +
        "(?:\\s+of\\s+power\\s+(?<power>\\d+)\\s+or\\s+more)?" +
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
        "(?:When|Whenever)\\s+(?<card>[^,]+?)\\s+" +
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
            "|leaves?\\s+the\\s+field" +
            "|is\\s+put\\s+(?:from\\s+the\\s+field\\s+)?into\\s+the\\s+Break\\s+Zone" +
            "|casts?\\s+a\\s+Summon" +
            "|is\\s+put\\s+into\\s+(?:your\\s+)?Damage\\s+Zone" +
            "|is\\s+removed\\s+from\\s+the\\s+game\\s+due\\s+to\\s+Warp" +
            "|deals?\\s+damage\\s+to\\s+your\\s+opponent" +
            "|deals?\\s+damage\\s+to\\s+a\\s+Forward" +
            "|receives?\\s+a\\s+point\\s+of\\s+damage" +
            "|is\\s+chosen\\s+by\\s+your\\s+opponent's\\s+Summons?" +
            "|uses?\\s+an\\s+EX\\s+Burst" +
        ")\\s*,\\s+" +
        "(?<youmay>(?:you|your\\s+opponent)\\s+may\\s+)?" +
        "(?<effect>.+?)\\s*" +
        "(?=\\s*\\[\\[br\\]\\]|\\s*When\\s+[^,]+?\\s+(?:forms?\\s+a\\s+party\\s+and\\s+attacks?|attacks?|blocks?|enters?|leaves?|is\\s+(?:put|removed|blocked)|deals?|uses?)|\\s*(?:《[^》]+》)+\\s*:|\\s*$)",
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
        "\\s+(?=enters?|attacks?|blocks?|leaves?|is\\s+(?:put|blocked|removed)|deals?|forms?|casts?|receives?|primes?)"
    );

    /**
     * Joins "select N of M following actions" headers with their [[br]]-delimited quoted
     * action strings so that {@link #AUTO_ABILITY_PATTERN} captures the full effect as one unit.
     * Input: {@code ...select 1 of the 2 following actions.[[br]] "A."[[br]] "B."...}
     * Output: {@code ...select 1 of the 2 following actions. "A." "B."...}
     */
    private static final Pattern SELECT_ACTIONS_JOINER = Pattern.compile(
        "(?i)((?:[^.!?]*,\\s+)?select\\s+" +
        "(?:" +
          "(?:up\\s+to\\s+)?\\d+\\s+of\\s+the\\s+\\d+\\s+following\\s+actions?" +  // "select N of the M following actions"
          "|the\\s+following\\s+actions?[^.!?]*" +                                   // "select the following actions..."
        ")" +
        "[.!]?)((?:\\s*\\[\\[br\\]\\]\\s*\"[^\"]+\")+)",
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

    /** "a Job X [or a Card Name Y] you control" — subject of a filtered-forward attack trigger. */
    static final Pattern FILTER_FORWARD_SUBJECT = Pattern.compile(
        "(?i)^a\\s+(?:Job\\s+|Card\\s+Name\\s+).+?(?:\\s+or\\s+a\\s+(?:Job\\s+|Card\\s+Name\\s+).+?)?\\s+you\\s+control$"
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

    public static List<AutoAbility> parseAutoAbilities(String textEn) {
        if (textEn == null || textEn.isBlank()) return List.of();
        textEn = joinSelectActions(textEn);
        List<AutoAbility> result = new ArrayList<>();
        // Strip double-quoted substrings that contain a trigger word ("When") so that
        // quoted ability text inside action-ability grants (e.g. "When X attacks, ...")
        // is never incorrectly registered as a permanent auto-ability.
        // We intentionally leave bare quoted status text (e.g. "X cannot be blocked.")
        // in place so the surrounding ability effect is captured in full.
        String textForSearch = textEn.replaceAll("(?i)\"(?=[^\"]*\\bWhen\\b)[^\"]+\"", "");

        // First pass: "when [PrimerCard] primes into [TargetCard], [effect]"
        // Also handles "When [Target] [trigger] [, extra] or when [Primer] primes into [Target], [effect]"
        // Matched regions are stripped from textForSearch before the remaining passes run.
        Matcher pm = PRIMES_INTO_PATTERN.matcher(textForSearch);
        StringBuffer strippedBuf = new StringBuffer();
        while (pm.find()) {
            String primer    = pm.group("primer").trim();
            String youMayRaw = pm.group("youmay");
            boolean opponentMay = youMayRaw != null
                    && youMayRaw.trim().toLowerCase(java.util.Locale.ROOT).startsWith("your opponent");
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
                    String pretrigRaw = pretriggerRaw.trim().toLowerCase(java.util.Locale.ROOT);
                    boolean castOnly  = pretrigRaw.contains("due to your cast");
                    boolean warpOnly  = pretrigRaw.contains("enter") && pretrigRaw.contains("warp");
                    boolean preIsParty = pretarget.toLowerCase(java.util.Locale.ROOT).contains("party");
                    String preTrig = normalizePretrigger(pretrigRaw, preIsParty, warpOnly);
                    AutoAbility preFA = parseAutoAbilityRestrictions(
                            pretarget, preTrig, youMay, opponentMay, castOnly, warpOnly, effect, 0);
                    if (preFA != null) result.add(preFA);

                    // Extra trigger phrase between preceding comma and "or" (e.g. "deals damage to your opponent")
                    String preextra = pm.group("preextra");
                    if (preextra != null && !preextra.isBlank()) {
                        String extraTrig = normalizePretrigger(
                                preextra.trim().toLowerCase(java.util.Locale.ROOT), false, false);
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

        // Convert "If N or more [filter] Forwards form the party, also [effect]." into a second trigger sentence.
        textForSearch = expandPartyAttackFollowups(textForSearch);

        // Rewrite "When X, a Y or a Z [trigger]" into "When X or a Y or a Z [trigger]" so that
        // AUTO_ABILITY_PATTERN's (?<card>[^,]+?) group captures the full disjunction as one subject.
        textForSearch = expandMultiSubjectTriggers(textForSearch);

        Matcher m = AUTO_ABILITY_PATTERN.matcher(textForSearch);
        while (m.find()) {
            String card      = m.group("card").trim();
            String triggerRaw = m.group("trigger").trim().toLowerCase(java.util.Locale.ROOT);
            // Normalise trigger to a canonical form
            String trigger;
            boolean cardIsParty = card.toLowerCase(java.util.Locale.ROOT).contains("party");
            // triggerRaw contains "party" when the trigger phrase itself is "forms a party and attacks"
            boolean triggerHasParty = triggerRaw.contains("party");
            boolean warpOnly    = triggerRaw.contains("enter") && triggerRaw.contains("warp");
            if      (triggerRaw.contains("attack") && triggerRaw.contains("block"))                        trigger = "attacks or blocks";
            else if (triggerRaw.contains("attack") && (cardIsParty || triggerHasParty))                    trigger = "party attacks";
            else if (triggerRaw.contains("enter") && triggerRaw.contains("break zone"))                   trigger = "enters the field or put into break zone";
            else if (triggerRaw.contains("enter") && triggerRaw.contains("attack"))                        trigger = "enters the field or attacks";
            else if (triggerRaw.contains("enter") && triggerRaw.contains("other than from your hand"))     trigger = "enters your field not from hand";
            else if (triggerRaw.contains("enter") && triggerRaw.contains("your field"))                            trigger = "enters your field";
            else if (triggerRaw.contains("attack")
                    && FILTER_FORWARD_SUBJECT.matcher(card).matches())                                       trigger = "filtered forward attacks";
            else if (triggerRaw.contains("attack")
                    && OTHER_FORWARD_SUBJECT.matcher(card).matches())                                        trigger = "other forward attacks";
            else if (triggerRaw.contains("attack")
                    && card.toLowerCase(java.util.Locale.ROOT).matches("\\d+\\s+or\\s+more\\s+forwards?\\s+you\\s+control"))
                                                                                                             trigger = "attack";
            else if (triggerRaw.contains("attack"))                                                         trigger = "attacks";
            else if (triggerRaw.equals("is blocked"))                                                       trigger = "is blocked";
            else if (triggerRaw.contains("block") && triggerRaw.contains("is blocked"))                    trigger = "blocks or is blocked";
            else if (triggerRaw.contains("block"))                                                          trigger = "blocks";
            else if (triggerRaw.contains("break zone"))                                                     trigger = "put into break zone";
            else if (triggerRaw.contains("chosen"))                                                         trigger = "chosen by opponent's summon";
            else if (triggerRaw.contains("summon"))                                                         trigger = "cast summon";
            else if (triggerRaw.contains("damage zone"))                                                    trigger = "damage zone";
            else if (triggerRaw.contains("leaves"))                                                         trigger = "leaves the field";
            else if (warpOnly)                                                                               trigger = "enters the field";
            else if (triggerRaw.contains("warp"))                                                           trigger = "warp placed";
            else if (triggerRaw.contains("deals damage") && triggerRaw.contains("opponent"))                trigger = "deals damage to opponent";
            else if (triggerRaw.contains("deals damage"))                                                   trigger = "deals damage to forward";
            else if (triggerRaw.contains("receive") && triggerRaw.contains("a point of damage")) {
                if (card.equalsIgnoreCase("you"))   trigger = "you receive damage";
                else                                trigger = "either player receives damage";
            }
            else if (triggerRaw.contains("uses") && triggerRaw.contains("ex burst"))                        trigger = "opponent uses ex burst";
            else                                                                                             trigger = "enters the field";

            // For "warp placed", strip the " in your hand" suffix from the card name
            if (trigger.equals("warp placed")) {
                card = card.replaceAll("(?i)\\s+in\\s+your\\s+hand$", "").trim();
            }
            // "a X of your opponent enters the field" → reclassify so dispatch can watch the opponent's side
            if (trigger.equals("enters the field")
                    && card.toLowerCase(java.util.Locale.ROOT).contains("of your opponent")) {
                trigger = "enters opponent's field";
                card = card.replaceAll("(?i)\\s+of\\s+your\\s+opponent\\s*$", "").trim();
            }

            String  youMayRaw   = m.group("youmay");
            boolean opponentMay = youMayRaw != null
                    && youMayRaw.trim().toLowerCase(java.util.Locale.ROOT).startsWith("your opponent");
            boolean youMay      = youMayRaw != null && !opponentMay;

            boolean castOnly = triggerRaw.contains("due to your cast");
            String effect = SUMMON_MARKUP.matcher(m.group("effect").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;

            String thresholdStr = m.group("threshold");
            int damageThreshold = thresholdStr != null ? Integer.parseInt(thresholdStr) : 0;

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
                    partyCardName = card;   // e.g. "Morrow" in "When Morrow forms a party and attacks"
                }
            }

            AutoAbility fa = parseAutoAbilityRestrictions(card, trigger, youMay, opponentMay, castOnly, warpOnly,
                    effect, damageThreshold, partyMinCount, partyCategory, partyJob, partyCardName);
            if (fa != null) result.add(fa);
        }

        // Second pass: "When a Warp Counter is removed from [CardName], [effect]"
        Matcher wm = WARP_COUNTER_PATTERN.matcher(textForSearch);
        while (wm.find()) {
            String target     = wm.group("target").trim();
            String youMayRaw  = wm.group("youmay");
            boolean opponentMay = youMayRaw != null
                    && youMayRaw.trim().toLowerCase(java.util.Locale.ROOT).startsWith("your opponent");
            boolean youMay      = youMayRaw != null && !opponentMay;
            String effect = SUMMON_MARKUP.matcher(wm.group("effect").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            AutoAbility fa = parseAutoAbilityRestrictions(target, "warp counter removed", youMay, opponentMay, false, false, effect, 0);
            if (fa != null) result.add(fa);
        }

        // Third pass: "When [CardName] or your [Element] Summon deals damage to a Forward, [effect]"
        // Produces two AutoAbility entries: battle-damage trigger and element-summon trigger.
        Matcher sm = BREAKTOUCH_SUMMON_PATTERN.matcher(textForSearch);
        while (sm.find()) {
            String card    = sm.group("card").trim();
            String element = sm.group("element").trim();
            String elemCap = Character.toUpperCase(element.charAt(0)) + element.substring(1).toLowerCase(java.util.Locale.ROOT);
            String effect  = SUMMON_MARKUP.matcher(sm.group("effect").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            AutoAbility fa1 = parseAutoAbilityRestrictions(card, "deals damage to forward", false, false, false, false, effect, 0);
            if (fa1 != null) result.add(fa1);
            String summonTrigger = elemCap.toLowerCase(java.util.Locale.ROOT) + " summon deals damage to forward";
            AutoAbility fa2 = parseAutoAbilityRestrictions(card, summonTrigger, false, false, false, false, effect, 0);
            if (fa2 != null) result.add(fa2);
        }

        // Fourth pass: "At the beginning of the Attack Phase during each of your turns, [effect]"
        Matcher bam = AT_BEGINNING_OF_ATTACK_PHASE_PATTERN.matcher(textForSearch);
        while (bam.find()) {
            String effect = SUMMON_MARKUP.matcher(bam.group("effect").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            AutoAbility fa = parseAutoAbilityRestrictions("", "beginning of attack phase", false, false, false, false, effect, 0);
            if (fa != null) result.add(fa);
        }

        return List.copyOf(result);
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

        // Prefix condition: "if the cost to cast X was paid with CP of N or more different Elements, "
        Matcher pay = FA_CAST_PAYMENT_ELEMENTS.matcher(effect);
        if (pay.find()) {
            castPaymentMinElements = Integer.parseInt(pay.group("n"));
            effect = effect.substring(pay.end()).trim();
        }

        if (effect.isEmpty()) return null;
        return new AutoAbility(card, trigger, youMay, opponentMay, effect,
                oncePerTurn, yourTurnOnly, rfpConditionCard, bzConditionCard, castPaymentMinElements, castOnly, warpOnly, damageThreshold,
                partyMinCount, partyCategory, partyJob, partyCardName);
    }

    /** Normalises a raw trigger string (lower-cased) to a canonical trigger value. */
    private static String normalizePretrigger(String raw, boolean cardIsParty, boolean warpOnly) {
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
        if (r.contains("summon"))                                   return "cast summon";
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
     * "If you have a 《C》, [target] gains [effects]."
     * Groups: {@code target}, {@code effects}.
     */
    private static final Pattern IF_HAVE_CRYSTAL_BOOST = Pattern.compile(
        "(?i)^If\\s+you\\s+have\\s+a\\s*《C》,\\s+(?<target>.+?)\\s+gains?\\s+(?<effects>.+?)\\.?\\s*$"
    );

    /**
     * "If [CardName] is dull, [target] gains [effects]."
     * Groups: {@code condcard} (the card that must be dull), {@code target}, {@code effects}.
     */
    private static final Pattern IF_CARD_IS_DULL_BOOST = Pattern.compile(
        "(?i)^If\\s+(?<condcard>[A-Z][A-Za-z''\\-]+(?:\\s+[A-Za-z''\\-]+)*)\\s+is\\s+dull,\\s+" +
        "(?<target>.+?)\\s+gains?\\s+(?<effects>.+?)\\.?\\s*$"
    );

    /**
     * "If you control [raw], [target] loses [power] power[ instead]."
     * Groups: {@code raw} (condition text), {@code target}, {@code power} (bare number, stored negative),
     * {@code instead} (present when the word "instead" follows "power" — effect replaces a base field grant).
     */
    private static final Pattern IF_CTRL_LOSE_OUTER = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<raw>[^,]+),\\s+(?<target>.+?)\\s+loses?\\s+(?<power>\\d+)\\s+power(?:\\s+(?<instead>instead))?\\.?\\s*$"
    );

    /**
     * "The [Job X | Category Y] Forwards [other than Z] you control cannot be blocked by
     * a/Forwards of cost N or more/less."
     * Groups: {@code job} or {@code category}, {@code except} (optional), {@code costval}, {@code costcmp}.
     */
    private static final Pattern FIELD_GRANT_CNB_BY_COST_DIRECT = Pattern.compile(
        "(?i)^The\\s+(?:Job\\s+(?<job>.+?)|Category\\s+(?<category>.+?))\\s+Forwards?\\s+" +
        "(?:other\\s+than\\s+(?<except>.+?)\\s+)?you\\s+control\\s+" +
        "cannot\\s+be\\s+blocked\\s+by\\s+(?:a\\s+)?Forwards?\\s+of\\s+cost\\s+" +
        "(?<costval>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?\\s*\\.?\\s*$"
    );

    /**
     * "The [Job X | Category Y] Forwards [other than Z] you control gain
     * "This Forward cannot be blocked by a/Forwards of cost N or more/less.""
     * Groups: same as {@link #FIELD_GRANT_CNB_BY_COST_DIRECT}.
     */
    private static final Pattern FIELD_GRANT_CNB_BY_COST_QUOTED = Pattern.compile(
        "(?i)^The\\s+(?:Job\\s+(?<job>.+?)|Category\\s+(?<category>.+?))\\s+Forwards?\\s+" +
        "(?:other\\s+than\\s+(?<except>.+?)\\s+)?you\\s+control\\s+gain\\s+" +
        "[\"\\u201C]This\\s+Forward\\s+cannot\\s+be\\s+blocked\\s+by\\s+(?:a\\s+)?Forwards?\\s+of\\s+cost\\s+" +
        "(?<costval>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?[.][\"\\u201D]\\s*\\.?\\s*$"
    );

    /**
     * "[CardName] cannot be blocked." — unconditional permanent unblockability with no condition.
     * Group {@code name} captures the card name.
     */
    private static final Pattern UNCONDITIONAL_CNB_PATTERN = Pattern.compile(
        "(?i)^(?<name>[A-Z][A-Za-z''\\-\\s]+?)\\s+cannot\\s+be\\s+blocked\\.?\\s*$"
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
    private static final Pattern ICB_EFFECT_NO_CHOSEN_SUMMONS = Pattern.compile("(?i)cannot\\s+be\\s+chosen\\s+by\\s+(?:your\\s+opponent's\\s+)?Summons?");
    private static final Pattern ICB_EFFECT_NO_CHOSEN_ABILITS = Pattern.compile("(?i)cannot\\s+be\\s+chosen\\s+by\\s+(?:your\\s+opponent's\\s+)?abilities");

    /** Matches a self-targeted trait grant: "[CardName] gains [Trait(s)]." — no "until end of turn". */
    private static final Pattern SELF_TRAIT_GRANT = Pattern.compile(
        "(?i)^(?<name>.+?)\\s+gains?\\s+" +
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

            // "If [CardName] is dull, [target] gains [effects]."
            Matcher dullM = IF_CARD_IS_DULL_BOOST.matcher(seg);
            if (dullM.find()) {
                String condCard  = dullM.group("condcard").trim();
                String targetName = dullM.group("target").trim();
                String effectsStr = dullM.group("effects").trim();
                ControlCondition dullCond = new ControlCondition(
                        List.of(), 0, false, null, null, null, null, 0, List.of(), false, null, condCard);
                Matcher pwrM = IF_CTRL_EFFECT_POWER.matcher(effectsStr);
                int powerBonus = pwrM.find() ? Integer.parseInt(pwrM.group(1)) : 0;
                EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
                if (ICB_EFFECT_HASTE.matcher(effectsStr).find())        traits.add(Trait.HASTE);
                if (ICB_EFFECT_BRAVE.matcher(effectsStr).find())        traits.add(Trait.BRAVE);
                if (ICB_EFFECT_FIRST_STRIKE.matcher(effectsStr).find()) traits.add(Trait.FIRST_STRIKE);
                if (ICB_EFFECT_BACK_ATTACK.matcher(effectsStr).find())  traits.add(Trait.BACK_ATTACK);
                if (powerBonus != 0 || !traits.isEmpty()) {
                    FieldPowerGrant targetFilter = parseIcbTargetFilter(targetName);
                    result.add(new IfControlBoost(List.of(dullCond), "", targetName, targetFilter,
                            powerBonus, traits, "", false, false, false, null));
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
                if (powerBonus != 0 || !dmgTraits.isEmpty()) {
                    FieldPowerGrant targetFilter = parseIcbTargetFilter(targetName);
                    result.add(new IfControlBoost(List.of(), "", targetName, targetFilter,
                            powerBonus, dmgTraits, "", false, false, false, null, 0, minDmg, false));
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

            boolean noChooseSummons  = ICB_EFFECT_NO_CHOSEN_SUMMONS.matcher(effectsStr).find();
            boolean noChooseAbilits  = ICB_EFFECT_NO_CHOSEN_ABILITS.matcher(effectsStr).find();

            if (powerBonus == 0 && traits.isEmpty() && specialText.isEmpty()
                    && !noChooseSummons && !noChooseAbilits && !isCannotBeBlocked
                    && icbCostFilter == null) continue;

            FieldPowerGrant targetFilter = parseIcbTargetFilter(targetName);
            result.add(new IfControlBoost(conditions, exceptName, targetName, targetFilter,
                    powerBonus, traits, specialText, noChooseSummons, noChooseAbilits,
                    isCannotBeBlocked, icbCostFilter));
        }

        // Parse "The [Job X / Category Y] Forwards [other than Z] you control [gain "This Forward"]
        // cannot be blocked by a/Forwards of cost N or more/less." — always-active grants with no
        // "If you control..." condition, stored as ICBs with an empty conditions list.
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;
            Matcher dm = FIELD_GRANT_CNB_BY_COST_DIRECT.matcher(seg);
            Matcher qm = FIELD_GRANT_CNB_BY_COST_QUOTED.matcher(seg);
            Matcher fm = dm.find() ? dm : (qm.find() ? qm : null);
            if (fm == null) continue;
            String job      = fm.group("job");
            String category = fm.group("category");
            String except   = fm.group("except");
            int costVal     = Integer.parseInt(fm.group("costval"));
            boolean orMore  = !"less".equalsIgnoreCase(fm.group("costcmp"));
            FieldPowerGrant grantFilter = new FieldPowerGrant(
                    job      != null ? job.trim()      : null,
                    category != null ? category.trim() : null,
                    true, false, false,
                    except   != null ? except.trim()   : null,
                    0, java.util.Set.of());
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

        return List.copyOf(result);
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

    /**
     * Matches passive grants of the form:
     * "The (Job X | Category Y) [Forwards?|Backups?|Monsters?|Characters?]
     *  [other than Z] you control gain[s] [+N power] [and] [Trait...]"
     */
    private static final Pattern FIELD_GRANT_PATTERN = Pattern.compile(
        "(?i)^The\\s+" +
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
     * [other than Z] you control gain +N power" (no Job/Category prefix).
     * The optional {@code element} group captures an element name prefix (e.g. "Ice").
     * Companion to {@link #FIELD_GRANT_PATTERN}.
     */
    private static final Pattern FIELD_GRANT_BARE_PATTERN = Pattern.compile(
        "(?i)^The\\s+" +
        "(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)?\\s*" +
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+" +
        "(?:other\\s+than\\s+(?<except>[A-Z][A-Za-z''\\-]+(?:\\s+[A-Za-z''\\-]+)*)\\s+)?" +
        "you\\s+control\\s+gains?\\s+\\+(?<power>\\d+)\\s+power[.!]?$"
    );

    /**
     * Matches "The Card Name X you control gain +N power."
     * Groups: {@code cardname}, {@code power}.
     */
    private static final Pattern FIELD_GRANT_CARD_NAME_PATTERN = Pattern.compile(
        "(?i)^The\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+you\\s+control\\s+gains?\\s+\\+(?<power>\\d+)\\s+power[.!]?$"
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

    public static List<FieldPowerGrant> parseFieldPowerGrants(String textEn, String cardType) {
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
                String cardName = bzCnJobM.group("cardname").trim();
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
                        null, power, traits, false, -1, null, null, cardName, minBzSize, 0, null, false, false));
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
                        false, -1, null, null, null, 0, 0, null, false, false, 0, 0, 1,
                        0, threshold));
                // At-or-above-threshold grant: targets2 (no exclusion), boostpower, only when damage >= threshold
                result.add(new FieldPowerGrant(null, null, incl2[0] != 0, incl2[1] != 0, incl2[2] != 0,
                        null, boostpower, EnumSet.noneOf(Trait.class),
                        false, -1, null, null, null, 0, 0, null, false, false, 0, 0, 1,
                        threshold, 0));
                continue;
            }

            Matcher bareM = FIELD_GRANT_BARE_PATTERN.matcher(seg);
            if (bareM.matches()) {
                int[] incl = parseFieldGrantTargetFlags(bareM.group("targets"));
                String bareExcept = bareM.group("except");
                String bareElem   = bareM.group("element");
                result.add(new FieldPowerGrant(null, null, incl[0] != 0, incl[1] != 0, incl[2] != 0,
                        bareExcept != null ? bareExcept.trim() : null,
                        Integer.parseInt(bareM.group("power")),
                        EnumSet.noneOf(Trait.class), false, -1, null,
                        bareElem != null ? bareElem.trim() : null));
                continue;
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
                result.add(new FieldPowerGrant(null, null, true, true, true, null,
                        Integer.parseInt(cnM.group("power")),
                        EnumSet.noneOf(Trait.class), false, -1, null, null,
                        cnM.group("cardname").trim()));
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
            if (job != null) job = job.trim();

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
                    except, power, traits));
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

    /** "For each Card Name X in your Break Zone, [target] gains +N power." */
    private static final Pattern SCALING_SELF_BZ_CARD_NAME_PATTERN = Pattern.compile(
        "(?i)^For\\s+each\\s+Card\\s+Name\\s+(?<name>.+?)\\s+in\\s+your\\s+Break\\s+Zone,\\s+" +
        "(?<target>.+?)\\s+gains?\\s+\\+(?<power>\\d+)\\s+power[.!]?$"
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
     */
    private static final Pattern SCALING_SELF_FOR_EACH_PATTERN = Pattern.compile(
        "(?i)^For\\s+each\\s+(?<filter>.+?)" +
        "(?:\\s+other\\s+than\\s+(?<excA>[^,]+?))?" +
        "\\s+you\\s+control" +
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
            String t = tm.group("type").toLowerCase(java.util.Locale.ROOT);
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
                        null, null, bz.group("name").trim(), null, null, false));
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
                if (SCALING_ELEMENT_NAMES.contains(exclude.toLowerCase(java.util.Locale.ROOT))) {
                    excludeElement = exclude;
                } else if (!exclude.equalsIgnoreCase(cardName)) {
                    continue; // "other than X" with X not a self-name and not an element — not ours
                }
            }
            ScalingFilterParse sf = parseScalingForEachFilter(fe.group("filter"));
            result.add(new ScalingSelfPowerBoost(
                    sf.source(), perUnit,
                    sf.jobFilter(), sf.categoryFilter(), sf.cardNameFilter(),
                    sf.elementFilter(), excludeElement, sf.requireActive()));
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
            return new FieldCostReduction(amount, floorAtOne, ownerOnly, iF, iB, iM, iS,
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
            return new FieldCostReduction(amount, floorAtOne, ownerOnly, iF, iB, iM, iS,
                    null, job, null, null, scalingJob, false, null);
        }

        m = CAST_SPEC_BRACKETED.matcher(branch);
        if (m.find())
            return new FieldCostReduction(amount, floorAtOne, ownerOnly, true, true, true, true,
                    null, null, m.group("name").trim(), null, scalingJob, false, null);

        m = PLAY_SPEC_CARD_NAME.matcher(branch);
        if (m.find())
            return new FieldCostReduction(amount, floorAtOne, ownerOnly, true, true, true, true,
                    null, null, m.group("name").trim(), null, scalingJob, false, null);

        m = CAST_SPEC_CATEGORY_TYPE.matcher(branch);
        if (m.find()) {
            String cat  = m.group("cat");
            String tl   = m.group("type").toLowerCase();
            boolean iF  = tl.contains("forward")  || tl.contains("character");
            boolean iB  = tl.contains("backup")   || tl.contains("character");
            boolean iM  = tl.contains("monster")  || tl.contains("character");
            boolean iS  = tl.contains("summon");
            return new FieldCostReduction(amount, floorAtOne, ownerOnly, iF, iB, iM, iS,
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
            return new FieldCostReduction(amount, floorAtOne, ownerOnly, iF, iB, iM, iS,
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

                result.add(new FieldCostReduction(amount, floorAtOne, ownerOnly,
                        inclForwards, inclBackups, inclMonsters, inclSummons,
                        elementFilter, jobFilter, cardNameFilter, null, scalingJob, false, null));
                continue;
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
                continue;
            }

            // Conditional reduction ("If you control N or more X, the cost … is reduced by N")
            Matcher cm = FIELD_CONDITIONAL_COST_REDUCTION_PATTERN.matcher(seg);
            if (cm.find()) {
                int    amount   = Integer.parseInt(cm.group("amount"));
                String cardName = cm.group("cardname").trim();
                result.add(new FieldCostReduction(amount, false, false, true, true, true, true,
                        null, null, cardName, null, null, false, null));
                continue;
            }

            // Conditional BZ-job any-element grant
            Matcher bzJobM = FIELD_CONDITIONAL_BZ_JOB_ANY_ELEMENT_PATTERN.matcher(seg);
            if (bzJobM.find()) {
                String job      = bzJobM.group("job").trim();
                String cardName = bzJobM.group("cardname").trim();
                result.add(new FieldCostReduction(0, false, true, true, true, true, true,
                        null, null, cardName, null, null, true, job));
                continue;
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
        }
        return List.copyOf(result);
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
            return new FieldCostReduction(amount, false, ownerOnly, iF, iB, iM, iS,
                    null, job, null, null, null, true, null);
        }

        m = CAST_SPEC_BRACKETED.matcher(branch);
        if (m.find())
            return new FieldCostReduction(amount, false, ownerOnly, true, true, true, true,
                    null, null, m.group("name").trim(), null, null, true, null);

        m = CAST_SPEC_CARD_NAME.matcher(branch);
        if (m.find())
            return new FieldCostReduction(amount, false, ownerOnly, true, true, true, true,
                    null, null, m.group("name"), null, null, true, null);

        m = CAST_SPEC_CATEGORY_TYPE.matcher(branch);
        if (m.find()) {
            String cat  = m.group("cat");
            String tl   = m.group("type").toLowerCase();
            boolean iF  = tl.contains("forward")  || tl.contains("character");
            boolean iB  = tl.contains("backup")   || tl.contains("character");
            boolean iM  = tl.contains("monster")  || tl.contains("character");
            boolean iS  = tl.contains("summon");
            return new FieldCostReduction(amount, false, ownerOnly, iF, iB, iM, iS,
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
            return new FieldCostReduction(amount, false, ownerOnly, iF, iB, iM, iS,
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

    /** "[CardName] cannot attack or block." — absolute restriction on both attack and block. */
    static final Pattern FIELD_CANNOT_ATTACK_OR_BLOCK = Pattern.compile(
        "(?i)^(?<cardname>.+?)\\s+cannot\\s+attack\\s+or\\s+block[.!]?\\s*$"
    );

    static final Pattern FIELD_CAN_ATTACK_TWICE = Pattern.compile(
        "(?i)^(?<cardname>.+?)\\s+can\\s+attack\\s+twice\\s+in\\s+the\\s+same\\s+turn[.!]?\\s*$"
    );

    public static boolean parseCanAttackTwice(String textEn, String cardName) {
        if (textEn == null || textEn.isBlank()) return false;
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;
            Matcher m = FIELD_CAN_ATTACK_TWICE.matcher(seg);
            if (!m.matches()) continue;
            if (m.group("cardname").trim().equalsIgnoreCase(cardName)) return true;
        }
        return false;
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
     */
    private static final Pattern FA_AUTO_PREFIX = Pattern.compile("(?i)^(?:EX\\s+BURST\\s+)?When\\s+");

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
    private static final Pattern HAS_ALL_JOBS_PATTERN = Pattern.compile(
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

    /** Matches "If there are N or more face-up cards in your LB deck, [name] gains [traits]." */
    static final Pattern IF_SELF_LB_FACEUP_COUNT_TRAIT_GRANT = Pattern.compile(
        "(?i)^If\\s+there\\s+are\\s+(?<n>\\d+)\\s+or\\s+more\\s+face[- ]up\\s+cards?\\s+in\\s+your\\s+LB\\s+deck,\\s+(?<cardname>.+?)\\s+gains?\\s+" +
        "(?<traits>(?:(?:Haste|First\\s+Strike|Brave|Back\\s+Attack)(?:\\s*(?:,|and)\\s*)?)+)[.!]?\\s*$"
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

    /** Matches "[CardName] enters the field dull." */
    private static final Pattern ENTERS_FIELD_DULL_PATTERN = Pattern.compile(
        "(?i)^.+?\\s+enters\\s+the\\s+field\\s+dull[.!]?\\s*$"
    );

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
            if (ALT_COST_NONSUMMON.matcher(seg).find()) continue;

            // Auto abilities: "When [card/event] [trigger], [effect]" and "At the beginning of [Phase]…"
            if (FA_AUTO_PREFIX.matcher(seg).find()) continue;
            if (AT_BEGINNING_OF_ATTACK_PHASE_PATTERN.matcher(seg).find()) continue;

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

            // Scaling self power boost ("For each Forward opponent controls, X gains +N power")
            if (SCALING_SELF_OPP_FWD_PATTERN.matcher(seg).find())            continue;
            // Scaling self power boost ("For each Backup opponent controls, X gains +N power")
            if (SCALING_SELF_OPP_BACKUP_PATTERN.matcher(seg).find())         continue;
            // Scaling self power boost ("For each [filter] you control, X gains +N power")
            if (SCALING_SELF_FOR_EACH_PATTERN.matcher(seg).find())            continue;
            // Scaling self power boost ("For each point of damage you have received, X gains +N power")
            if (SCALING_SELF_DMG_PATTERN.matcher(seg).find())                 continue;
            // Scaling self power boost ("For each Card Name X in your Break Zone, X gains +N power")
            if (SCALING_SELF_BZ_CARD_NAME_PATTERN.matcher(seg).find())        continue;
            // Scaling self power boost ("For every N Summons in your Break Zone, X gains +P power")
            if (SCALING_SELF_BZ_SUMMON_EVERY_N_PATTERN.matcher(seg).find())   continue;
            // Scaling self power boost ("For each card in your hand, X gains +N power")
            if (SCALING_SELF_HAND_PATTERN.matcher(seg).find())                 continue;
            // BZ-conditional / distinct-element-conditional / EX Burst scaling passive grant — handled by parseFieldPowerGrants
            if (FIELD_GRANT_BZ_COND_CN_AND_JOB_PATTERN.matcher(seg).find())       continue;
            if (FIELD_EX_BURST_DMG_SCALING_GRANT.matcher(seg).matches())           continue;
            if (FIELD_GRANT_BZ_JOB_SELF_PATTERN.matcher(seg).matches())            continue;
            if (IF_DIFF_ELEMENTS_FIELD_GRANT.matcher(seg).matches())               continue;
            if (FIELD_GRANT_DAMAGE_THRESHOLD_PATTERN.matcher(seg).matches())       continue;

            // Cast/play restrictions — handled as static properties via castRestriction()
            if (CAST_REQUIRES_NO_FORWARDS.matcher(seg).find())                continue;
            if (CAST_MUST_CONTROL.matcher(seg).find())                        continue;
            if (CAST_MUST_CONTROL_CATEGORY_FWD.matcher(seg).find())          continue;

            // Field cost reduction / any-element declarations — handled as static card properties
            if (FIELD_COST_REDUCTION_PATTERN.matcher(seg).find())            continue;
            if (FIELD_PLAY_COST_REDUCTION_PATTERN.matcher(seg).find())       continue;
            if (FIELD_CONDITIONAL_COST_REDUCTION_PATTERN.matcher(seg).find()) continue;
            // Self-cost modifier ("If <cond>, the cost required to cast/play <cardName>…") — handled via parseSelfCostModifiers
            if (isSelfCostModifierText(seg))                                  continue;
            if (FIELD_CONDITIONAL_BZ_JOB_ANY_ELEMENT_PATTERN.matcher(seg).find()) continue;
            if (FIELD_CAST_ANY_ELEMENT_PATTERN.matcher(seg).find())          continue;
            if (FIELD_PRIMING_ANY_ELEMENT_PATTERN.matcher(seg).find())       continue;
            if (WARP_ANY_ELEMENT_PATTERN.matcher(seg).find())                continue;
            if (PARTY_ANY_ELEMENT_PATTERN.matcher(seg).find())               continue;
            if (FIELD_PARTY_ANY_ELEMENT_PATTERN.matcher(seg).find())        continue;

            // Name/type alias declarations and enter-dull — handled as static card properties
            if (IS_ALSO_CARD_NAME_PATTERN.matcher(seg).find())              continue;
            if (IS_ALSO_MONSTER_PATTERN.matcher(seg).find())                continue;
            if (ENTERS_FIELD_DULL_PATTERN.matcher(seg).matches())           continue;
            if (ALIAS_PLAY_RESTRICTION_PATTERN.matcher(seg).matches())      continue;
            if (SPECIAL_ABILITY_PROXY_PATTERN.matcher(seg).matches())       continue;
            if (BECOME_FORWARD_IF_CONTROL_N_MONSTERS_PATTERN.matcher(seg).find()) continue;
            if (BECOME_FORWARD_DURING_TURN_PATTERN.matcher(seg).find())       continue;
            if (BECOME_FORWARD_UNCONDITIONAL_PATTERN.matcher(seg).find())     continue;
            if (FIELD_CAN_ATTACK_TWICE.matcher(seg).matches())               continue;
            if (FIELD_GRANT_CARD_NAME_TRAIT_YOUR_TURN.matcher(seg).matches()) continue;
            if (FIELD_GRANT_CARD_NAME_TRAIT_ALWAYS.matcher(seg).matches())    continue;

            result.add(new FieldAbility(seg, damageThreshold));
        }
        return List.copyOf(result);
    }

    private static final Pattern COUNTER_COST_PATTERN = Pattern.compile(
        "(?i)remove\\s+(?<n>\\d+)\\s+(?<name>.+?)\\s+Counters?\\s+from\\s+(?<card>[^,:.]+?)\\s*$"
    );

    /** Parses "remove N [Name] Counter(s) from [CardName]" into a {@link CounterCost} list. */
    private static List<CounterCost> parseCounterCosts(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        Matcher m = COUNTER_COST_PATTERN.matcher(raw.trim());
        if (!m.find()) return List.of();
        int    count       = Integer.parseInt(m.group("n"));
        String counterName = m.group("name").trim();
        String cardName    = m.group("card").trim();
        return List.of(new CounterCost(cardName, counterName, count));
    }

    /** Parses one or more dull-forward cost items from the raw {@code dullcost} group string.
     *  Handles both "Dull N [cond] [elem] Forward(s)" and "Dull N [cond] Card Name X Forward [and ...]" forms. */
    private static List<DullForwardCost> parseDullForwardCosts(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<DullForwardCost> costs = new ArrayList<>();
        Matcher m = DULL_COST_ITEM_PATTERN.matcher(raw);
        while (m.find()) {
            int    count       = Integer.parseInt(m.group("count"));
            String cond        = m.group("cond");
            String cardName    = m.group("cardname");
            String elem        = m.group("elem");
            String job         = m.group("job");
            String category    = m.group("category");
            String jobOrName   = m.group("joborcardname");
            // "Forwards or Backups" and plain "Backups [of the same Element]" forms include non-Forward characters
            boolean inclBackups = m.group("orbackup") != null || m.group("sameelembackup") != null;
            boolean isChar     = m.group("catchar") != null
                              || m.group("jobchar") != null
                              || m.group("stdchar") != null
                              || inclBackups;
            costs.add(new DullForwardCost(count,
                    cond      != null ? cond.toLowerCase()          : null,
                    elem      != null ? elem.trim()                 : null,
                    cardName  != null ? cardName.trim()             : null,
                    job       != null ? job.trim()                  : null,
                    category  != null ? category.trim()             : null,
                    isChar            ? "Character"                 : null,
                    jobOrName != null ? stripTrailingType(jobOrName) : null));
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

    static ControlCondition parseControlCondition(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // Strip trailing ", during your turn" and "and only once per turn" clauses
        String cond = raw.replaceAll("(?i)\\s*,?\\s*during\\s+your\\s+turn\\b.*", "").trim();
        cond = cond.replaceAll("(?i)\\s*,?\\s*(?:and\\s+)?only\\s+once\\s+per\\s+turn\\b.*", "").trim();

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

        return new ControlCondition(List.of(), minCount, exactCount, cardType, element, job, category, minPower, orCardNames, false, excludeElement);
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

    /** Parses {@code textEn} and returns the set of Special Traits present. */
    public static Set<Trait> parseTraits(String textEn) {
        if (textEn == null || textEn.isBlank()) return Set.of();
        EnumSet<Trait> found = EnumSet.noneOf(Trait.class);
        if (HASTE_PATTERN.matcher(textEn).find())        found.add(Trait.HASTE);
        if (BRAVE_PATTERN.matcher(textEn).find())        found.add(Trait.BRAVE);
        if (FIRST_STRIKE_PATTERN.matcher(textEn).find()) found.add(Trait.FIRST_STRIKE);
        if (BACK_ATTACK_PATTERN.matcher(textEn).find())  found.add(Trait.BACK_ATTACK);
        if (WARP_PATTERN.matcher(textEn).find())         found.add(Trait.WARP);
        if (PRIMING_PATTERN.matcher(textEn).find())      found.add(Trait.PRIMING);
        // Strip quoted "gains '...'" grant text before checking permanent "cannot be broken"
        // so that temporary grant descriptions in action abilities are not matched.
        String strippedForCbb = textEn
                .replaceAll("(?i)gains\\s*'[^']*'", "")
                .replaceAll("(?i)gains\\s*\"[^\"]*\"", "");
        if (CANNOT_BE_BROKEN_TRAIT_PATTERN.matcher(strippedForCbb).find())
            found.add(Trait.CANNOT_BE_BROKEN);
        if (CANNOT_BE_BROKEN_BY_NON_DMG_TRAIT_PATTERN.matcher(strippedForCbb).find())
            found.add(Trait.CANNOT_BE_BROKEN_BY_NON_DMG);
        return found;
    }

    /** Returns {@code true} if this card has the given Special Trait. */
    public boolean hasTrait(Trait t) { return traits.contains(t); }

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
     */
    public record SpecialAbilityProxy(String targetName, String subElement, String subType, String subCardName) {
        public boolean meetsSubstitute(CardData handCard) {
            if (subCardName != null) return subCardName.equalsIgnoreCase(handCard.name());
            if (subType == null) return false;
            if (!CardFilters.matchesDiscardType(handCard, subType)) return false;
            return subElement == null || CardFilters.meetsElementFilter(handCard, subElement);
        }
        public String substituteDescription() {
            if (subCardName != null) return subCardName;
            return (subElement != null ? subElement + " " : "") + subType;
        }
    }

    /**
     * Returns a {@link SpecialAbilityProxy} if this card grants use of another card's special
     * ability with an alternate substitute discard, or {@code null} if it has none.
     */
    public SpecialAbilityProxy specialAbilityProxy() {
        for (String seg : rawFieldSegments()) {
            Matcher m = SPECIAL_ABILITY_PROXY_PATTERN.matcher(seg);
            if (!m.matches()) continue;
            return new SpecialAbilityProxy(
                m.group("target").trim(),
                m.group("subElem") != null ? m.group("subElem").trim() : null,
                m.group("subType") != null ? m.group("subType").trim() : null,
                m.group("subName") != null ? m.group("subName").trim() : null
            );
        }
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
        "\\s+is\\s+(?<dir>reduced|increased)\\s+by\\s+(?<amount>\\d+)" +
        "(?:\\s+(?<scaling>for\\s+.+?))?" +
        "(?:[.]?\\s+\\(it\\s+cannot\\s+become\\s+(?:0|1\\s+or\\s+less)\\))?" +
        "\\s*\\.?$"
    );

    // Condition sub-patterns
    private static final Pattern SELF_COND_CAST_SUMMON = Pattern.compile(
        "(?i)^you\\s+have\\s+cast\\s+a\\s+Summon\\s+this\\s+turn$"
    );
    private static final Pattern SELF_COND_CAST_JOB_OR_NAME = Pattern.compile(
        "(?i)^you\\s+have\\s+cast\\s+a\\s+Job\\s+(?<job>.+?)\\s+or\\s+Card\\s+Name\\s+(?<name>.+?)\\s+this\\s+turn$"
    );
    private static final Pattern SELF_COND_CONTROL_NAME = Pattern.compile(
        "(?i)^you\\s+control\\s+Card\\s+Name\\s+(?<name>.+?)\\s*$"
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
            boolean isIncrease = "increased".equalsIgnoreCase(m.group("dir"));
            int amount = Integer.parseInt(m.group("amount"));

            int minCost = 0;
            if (seg.contains("(it cannot become 0)"))          minCost = 1;
            else if (seg.contains("(it cannot become 1 or less)")) minCost = 2;

            SelfCostModifier mod = null;

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

            if (mod != null) result.add(mod);
        }
        return List.copyOf(result);
    }
}
