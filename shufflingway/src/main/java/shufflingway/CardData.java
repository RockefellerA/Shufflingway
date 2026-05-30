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
        List<FieldPowerGrant> fieldPowerGrants,
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
        PRIMING
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
        fieldPowerGrants = List.copyOf(fieldPowerGrants);
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
    private static final Pattern TRAIT_ONLY_SEGMENT = Pattern.compile(
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
            String type = em.group(3) != null ? em.group(3) : globalType;
            for (int i = 0; i < count; i++) result.add(elem + " " + type);
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

    // "While paying the cost to cast a Category X card, if Rikku is on the field, Rikku can produce CP of any Element."
    // Captures the category identifier only (e.g. "XI"), not the "Category" prefix.
    private static final Pattern BACKUP_CP_ANY_ELEM_CATEGORY = Pattern.compile(
        "(?i)While\\s+paying\\s+the\\s+cost\\s+to\\s+cast\\s+a\\s+Category\\s+(\\S+)\\s+card.*?can\\s+produce\\s+CP\\s+of\\s+any\\s+Element",
        Pattern.DOTALL
    );

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

        if (!yourTurnOnly && !mainPhaseOnly && !opponentTurnOnly && !requiresNoFwds
                && requiredBZTypes.isEmpty() && minBZAndRfpSummons == 0) {
            return null;
        }
        return new CastRestriction(yourTurnOnly, mainPhaseOnly, opponentTurnOnly,
                requiresNoFwds, requiredBZTypes, minBZAndRfpSummons);
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

    // Back Attack: start of card with (Like, <p>, [[br]], or after any [[br]]
    private static final Pattern BACK_ATTACK_PATTERN = Pattern.compile(
        "(?i)(?:^Back\\s+Attack\\s*(?:\\(Like|\\[\\[br\\]\\])|\\[\\[br\\]\\]Back\\s+Attack\\b|<p>Back\\s+Attack)"
    );

    private static final Pattern WARP_PATTERN = Pattern.compile(
        "(?i)Warp\\s+(\\d+)\\s*--\\s*((?:《[^》]*》\\s*)*)"
    );

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
        "((?:《[^》]*》\\s*)*)"                                              +  // group 3: zero or more 《cost》 tokens
        "((?i)(?:,\\s*)?put\\s+.+?\\s+into\\s+the\\s+Break\\s+Zone\\s*)?"  + // group 4: optional BZ cost phrase
        "((?i)(?:,\\s*)?discard[^:]+)?"                                     +  // group 5: optional discard cost phrase
        "((?i)(?:,\\s*)?remove\\s+[^:]+?\\s+from\\s+(?:the\\s+)?game\\s*)?" + // group 6: optional remove-from-game cost phrase
        "((?i)(?:,\\s*)?return\\s+[^:]+?\\s+to\\s+(?:its|their)\\s+owner(?:'s|s')?\\s+hand\\s*)?" + // group 7: optional return-to-hand cost phrase
        "((?i)(?:,\\s*)?remove\\s+\\d+\\s+[^:]+?\\s+Counters?\\s+from\\s+[^:,]+?\\s*)?" +           // group 8: optional counter-removal cost phrase
        "(?<dullcost>(?i)(?:,\\s*)?Dull\\s+(?<dullcount>\\d+)\\s*(?<dullcond>active|dull|damaged)?\\s*" + // group 9 (named): optional Dull N [cond] [elem] Forward cost
        "(?<dullelem>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)?\\s*Forwards?\\s*)?" +
        ":\\s*"                                                              +  // colon separator
        "((?:[^\\[]|\\[(?!\\[))*)"                                              // group 13: effect text (up to next [[markup]])
    );

    // Captures the content between "put " and " into the Break Zone"
    private static final Pattern BREAK_ZONE_COST_PATTERN = Pattern.compile(
        "(?i)put\\s+(.+?)\\s+into\\s+the\\s+Break\\s+Zone"
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
        List<ActionAbility> result = new ArrayList<>();
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
            String effectRaw     = m.group(13).trim();
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

            List<BreakZoneCost>      breakZoneCosts      = parseBreakZoneCosts(bzRaw);
            List<DiscardCost>        discardCosts        = parseDiscardCosts(discardRaw);
            List<RemoveFromGameCost> removeFromGameCosts = parseRemoveFromGameCosts(removeRaw);
            List<ReturnToHandCost>   returnToHandCosts   = parseReturnToHandCosts(returnRaw);
            List<CounterCost>        counterCosts        = parseCounterCosts(counterRaw);
            List<DullForwardCost>    dullForwardCosts    = parseDullForwardCosts(dullCostRaw, m);
            boolean yourTurnOnly      = YOUR_TURN_ONLY_PATTERN.matcher(effectRaw).find();
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
            ControlCondition controlCondition = null;
            if (!whileCardInHand) {
                Matcher ctrlM = CONTROL_IF_PATTERN.matcher(effectRaw);
                if (ctrlM.find()) controlCondition = parseControlCondition(ctrlM.group("condition"));
            }
            Matcher cpBkpM = CP_BACKUP_ONLY_ABILITY.matcher(effectRaw);
            String cpBackupElement = cpBkpM.find()
                    ? (cpBkpM.group("element") != null ? cpBkpM.group("element") : "")
                    : null;
            result.add(new ActionAbility(abilityName, requiresDull, isSpecial, crystalCost, hasXCost, cpCost, breakZoneCosts, discardCosts, removeFromGameCosts, returnToHandCosts, counterCosts, dullForwardCosts, yourTurnOnly, oncePerTurn, mainPhaseOnly, whileCardAtk, whileCardBlk, whilePartyAtk, whileCardInHand, hasBlockingTarget, effectRaw, damageThreshold, controlCondition, cpBackupElement, sourceInBattle));
        }
        return List.copyOf(result);
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
        "(?i)You\\s+can(?:\\s+only)?\\s+use\\s+this\\s+ability(?:\\s+only)?\\s+during\\s+your\\s+turn[.!]?"
    );

    static final Pattern ONCE_PER_TURN_PATTERN = Pattern.compile(
        "(?i)(?:You\\s+can(?:\\s+only)?\\s+use\\s+this\\s+ability(?:\\s+only)?\\s+|(?:and\\s+)?only\\s+)once\\s+per\\s+turn[.!]?"
    );

    static final Pattern MAIN_PHASE_ONLY_PATTERN = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+during\\s+your\\s+Main\\s+Phase[.!]?"
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
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+.+?\\s+is\\s+in\\s+your\\s+hand[.!]?"
    );

    static final Pattern SOURCE_IN_BATTLE_PATTERN = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+when\\s+.+?\\s+is\\s+in\\s+Battle[.!]?"
    );

    /** Captures the raw condition text from "You can only use this ability if you control [X]". */
    static final Pattern CONTROL_IF_PATTERN = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+if\\s+you\\s+control\\s+(?<condition>.+?)\\s*[.!]?\\s*$"
    );

    /**
     * Named-card mode: "Card Name X [and [a] Card Name Y [and [a] Card Name Z]]"
     * The optional "a" article is allowed before each name (including subsequent ones).
     */
    private static final Pattern CONTROL_NAMED_CARDS_PATTERN = Pattern.compile(
        "(?i)(?:a\\s+)?Card\\s+Name\\s+(?<n1>.+?)(?:\\s+and\\s+(?:a\\s+)?Card\\s+Name\\s+(?<n2>.+?))?(?:\\s+and\\s+(?:a\\s+)?Card\\s+Name\\s+(?<n3>.+?))?\\s*$"
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
        "(?:(?<count>\\d+)\\s+or\\s+more|only\\s+(?<exactn>\\d+)|a(?:n)?\\s+)" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?:Job\\s+(?<job>.+?)(?=\\s+(?:Forwards?|Monsters?|Backups?|Characters?)(?:\\s|$)|\\s+or\\s+Card\\s+Name\\b|\\s*$))?" +
        "(?<type>Forwards?|Monsters?|Backups?|Characters?)?" +
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
    private static final Pattern FIELD_ABILITY_PATTERN = Pattern.compile(
        "(?i)(?:Damage\\s+(?<threshold>\\d+)\\s+--\\s+)?" +
        "When\\s+(?<card>[^,]+?)\\s+" +
        "(?<trigger>" +
            "attacks?(?:\\s+or\\s+blocks?)?" +
            "|blocks?(?:\\s+or\\s+is\\s+blocked)?" +
            "|is\\s+blocked" +
            // "enters the field or attacks" must precede plain "enters the field"
            "|enters?\\s+the\\s+field\\s+or\\s+attacks?" +
            "|enters?\\s+the\\s+field(?:\\s+due\\s+to\\s+(?:your\\s+cast|Warp))?" +
            "|leaves?\\s+the\\s+field" +
            "|is\\s+put\\s+(?:from\\s+the\\s+field\\s+)?into\\s+the\\s+Break\\s+Zone" +
            "|casts?\\s+a\\s+Summon" +
            "|is\\s+put\\s+into\\s+(?:your\\s+)?Damage\\s+Zone" +
            "|is\\s+removed\\s+from\\s+the\\s+game\\s+due\\s+to\\s+Warp" +
            "|deals?\\s+damage\\s+to\\s+your\\s+opponent" +
            "|deals?\\s+damage\\s+to\\s+a\\s+Forward" +
        ")\\s*,\\s+" +
        "(?<youmay>(?:you|your\\s+opponent)\\s+may\\s+)?" +
        "(?<effect>.+?)\\s*" +
        "(?=\\s*\\[\\[br\\]\\]|\\s*When\\s+[^,]+?\\s+(?:attacks?|blocks?|enters?|leaves?|is\\s+(?:put|removed|blocked)|deals?)|\\s*(?:《[^》]+》)+\\s*:|\\s*$)",
        Pattern.DOTALL
    );

    /**
     * Joins "select N of M following actions" headers with their [[br]]-delimited quoted
     * action strings so that {@link #FIELD_ABILITY_PATTERN} captures the full effect as one unit.
     * Input: {@code ...select 1 of the 2 following actions.[[br]] "A."[[br]] "B."...}
     * Output: {@code ...select 1 of the 2 following actions. "A." "B."...}
     */
    private static final Pattern SELECT_ACTIONS_JOINER = Pattern.compile(
        "(?i)((?:[^.!?]*,\\s+)?select\\s+(?:up\\s+to\\s+)?\\d+\\s+of\\s+the\\s+\\d+\\s+following\\s+actions?[.!]?)" +
        "((?:\\s*\\[\\[br\\]\\]\\s*\"[^\"]+\")+)",
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

    /** Matches the restriction sentence appended to a auto-ability effect, capturing flags. */
    private static final Pattern FA_TRIGGER_RESTRICTION = Pattern.compile(
        "(?i)[.!,]?\\s*This\\s+effect\\s+will\\s+trigger\\s+only\\s+" +
        "(?:(?<yourTurn>during\\s+your\\s+turn)(?:\\s+and\\s+only\\s+)?)?(?<once>once\\s+per\\s+turn)?[.!]?\\s*$"
    );

    /** Matches "This effect will trigger only if [card] is removed from the game." */
    private static final Pattern FA_RFP_CONDITION = Pattern.compile(
        "(?i)[.!,]?\\s*This\\s+effect\\s+will\\s+trigger\\s+only\\s+if\\s+(?<rfpCard>[^.!]+?)\\s+is\\s+removed\\s+from\\s+the\\s+game[.!]?\\s*$"
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
     * action strings into a single line so {@link #FIELD_ABILITY_PATTERN} captures them together.
     */
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
        // Strip double-quoted substrings before pattern-matching so that
        // quoted ability text inside action-ability grants (e.g. "When X attacks, ...")
        // is never incorrectly registered as a permanent auto-ability.
        String textForSearch = textEn.replaceAll("\"[^\"]+\"", "");
        Matcher m = FIELD_ABILITY_PATTERN.matcher(textForSearch);
        while (m.find()) {
            String card      = m.group("card").trim();
            String triggerRaw = m.group("trigger").trim().toLowerCase(java.util.Locale.ROOT);
            // Normalise trigger to a canonical form
            String trigger;
            boolean cardIsParty = card.toLowerCase(java.util.Locale.ROOT).contains("party");
            boolean warpOnly    = triggerRaw.contains("enter") && triggerRaw.contains("warp");
            if      (triggerRaw.contains("attack") && triggerRaw.contains("block")) trigger = "attacks or blocks";
            else if (triggerRaw.contains("attack") && cardIsParty)                  trigger = "party attacks";
            else if (triggerRaw.contains("enter") && triggerRaw.contains("attack")) trigger = "enters the field or attacks";
            else if (triggerRaw.contains("attack"))                                 trigger = "attacks";
            else if (triggerRaw.contains("block") && triggerRaw.contains("is blocked")) trigger = "blocks or is blocked";
            else if (triggerRaw.equals("is blocked"))                               trigger = "is blocked";
            else if (triggerRaw.contains("block"))                                  trigger = "blocks";
            else if (triggerRaw.contains("break zone"))                             trigger = "put into break zone";
            else if (triggerRaw.contains("summon"))                                 trigger = "cast summon";
            else if (triggerRaw.contains("damage zone"))                            trigger = "damage zone";
            else if (triggerRaw.contains("leaves"))                                 trigger = "leaves the field";
            else if (warpOnly)                                                       trigger = "enters the field";
            else if (triggerRaw.contains("warp"))                                   trigger = "warp placed";
            else if (triggerRaw.contains("deals damage") && triggerRaw.contains("opponent")) trigger = "deals damage to opponent";
            else if (triggerRaw.contains("deals damage"))                          trigger = "deals damage to forward";
            else                                                                     trigger = "enters the field";

            // For "warp placed", strip the " in your hand" suffix from the card name
            if (trigger.equals("warp placed")) {
                card = card.replaceAll("(?i)\\s+in\\s+your\\s+hand$", "").trim();
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

            AutoAbility fa = parseAutoAbilityRestrictions(card, trigger, youMay, opponentMay, castOnly, warpOnly, effect, damageThreshold);
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

        return List.copyOf(result);
    }

    /**
     * Strips trigger-restriction sentences from {@code effect}, records the resulting flags,
     * and returns a complete {@link AutoAbility}.  Returns {@code null} if the effect is empty
     * after stripping.
     */
    private static AutoAbility parseAutoAbilityRestrictions(
            String card, String trigger, boolean youMay, boolean opponentMay, boolean castOnly, boolean warpOnly,
            String effect, int damageThreshold) {

        boolean oncePerTurn = false, yourTurnOnly = false;
        String  rfpConditionCard = "";
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

        // Prefix condition: "if the cost to cast X was paid with CP of N or more different Elements, "
        Matcher pay = FA_CAST_PAYMENT_ELEMENTS.matcher(effect);
        if (pay.find()) {
            castPaymentMinElements = Integer.parseInt(pay.group("n"));
            effect = effect.substring(pay.end()).trim();
        }

        if (effect.isEmpty()) return null;
        return new AutoAbility(card, trigger, youMay, opponentMay, effect,
                oncePerTurn, yourTurnOnly, rfpConditionCard, castPaymentMinElements, castOnly, warpOnly, damageThreshold);
    }

    // -------------------------------------------------------------------------
    // "If you control X, Y gains Z" conditional field-boost parsing
    // -------------------------------------------------------------------------

    /** Outer structure: "If you control <raw>, <target> gains <effects>[.]" */
    private static final Pattern IF_CTRL_BOOST_OUTER = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<raw>[^,]+),\\s+(?<target>.+?)\\s+gains?\\s+(?<effects>.+?)\\.?\\s*$"
    );

    /** Splits a single condition part on " other than ": group(1) = condition, group(2) = excluded name. */
    private static final Pattern IF_CTRL_BOOST_EXCEPT = Pattern.compile(
        "(?i)^(.+?)\\s+other\\s+than\\s+(\\S.*)$"
    );

    /** Extracts the +N power value from an effects substring. */
    private static final Pattern IF_CTRL_EFFECT_POWER = Pattern.compile("(?i)\\+(\\d+)\\s+power");

    /** Extracts quoted special ability text from an effects substring. */
    private static final Pattern IF_CTRL_EFFECT_QUOTED = Pattern.compile("\"([^\"]+)\"");

    // Simple keyword matchers for effect substrings (not positional like the card-text trait patterns)
    private static final Pattern ICB_EFFECT_HASTE        = Pattern.compile("(?i)\\bHaste\\b");
    private static final Pattern ICB_EFFECT_BRAVE        = Pattern.compile("(?i)\\bBrave\\b");
    private static final Pattern ICB_EFFECT_FIRST_STRIKE = Pattern.compile("(?i)\\bFirst\\s+Strike\\b");
    private static final Pattern ICB_EFFECT_BACK_ATTACK  = Pattern.compile("(?i)\\bBack\\s+Attack\\b");

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

            Matcher m = IF_CTRL_BOOST_OUTER.matcher(seg);
            if (!m.find()) continue;

            String rawCond    = m.group("raw").trim();
            String targetName = m.group("target").trim();
            String effectsStr = m.group("effects").trim();

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

            java.util.EnumSet<Trait> traits = java.util.EnumSet.noneOf(Trait.class);
            if (ICB_EFFECT_HASTE.matcher(effectsStr).find())        traits.add(Trait.HASTE);
            if (ICB_EFFECT_BRAVE.matcher(effectsStr).find())        traits.add(Trait.BRAVE);
            if (ICB_EFFECT_FIRST_STRIKE.matcher(effectsStr).find()) traits.add(Trait.FIRST_STRIKE);
            if (ICB_EFFECT_BACK_ATTACK.matcher(effectsStr).find())  traits.add(Trait.BACK_ATTACK);

            if (powerBonus == 0 && traits.isEmpty() && specialText.isEmpty()) continue;

            result.add(new IfControlBoost(conditions, exceptName, targetName, powerBonus, traits, specialText));
        }
        return List.copyOf(result);
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

    public static List<FieldPowerGrant> parseFieldPowerGrants(String textEn, String cardType) {
        if (textEn == null || textEn.isBlank()) return List.of();
        if ("Summon".equalsIgnoreCase(cardType)) return List.of();

        List<FieldPowerGrant> result = new ArrayList<>();
        for (String raw : textEn.split("(?i)\\[\\[br\\]\\]")) {
            String seg = SUMMON_MARKUP.matcher(raw.trim()).replaceAll("").trim();
            if (seg.isEmpty()) continue;

            Matcher m = FIELD_GRANT_PATTERN.matcher(seg);
            if (!m.find()) continue;

            String job      = m.group("job");
            String category = m.group("category");
            if (job != null) job = job.trim();

            String targets = m.group("targets");
            boolean inclForwards, inclBackups, inclMonsters;
            if (targets == null) {
                inclForwards = true; inclBackups = true; inclMonsters = true;
            } else {
                String tl = targets.toLowerCase();
                inclForwards = tl.contains("forward") || tl.contains("character");
                inclBackups  = tl.contains("backup")  || tl.contains("character");
                inclMonsters = tl.contains("monster")  || tl.contains("character");
            }

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
        "|This\\s+effect\\s+will\\s+trigger" +
        ")"
    );

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
     * Matches "You can only pay with CP produced by [Element] Backups to use this ability"
     * capturing the optional {@code element} group.
     */
    static final Pattern CP_BACKUP_ONLY_ABILITY = Pattern.compile(
        "(?i)You\\s+can\\s+only\\s+pay\\s+with\\s+CP\\s+produced\\s+by\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "Backups\\s+to\\s+use\\s+this\\s+ability[.!]?"
    );

    /** Matches "[CardName] has all the jobs." as a field ability. */
    private static final Pattern HAS_ALL_JOBS_PATTERN = Pattern.compile(
        "(?i)^.+?\\s+has\\s+all\\s+the\\s+jobs\\.?$"
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
     * Matches "During your turn, [CardName] also becomes a Forward with [X] power."
     * Group {@code power} captures the numeric power value.
     * Trailing text (e.g. embedded auto-abilities) is allowed after the power clause.
     */
    private static final Pattern BECOME_FORWARD_DURING_TURN_PATTERN = Pattern.compile(
        "(?i)^During\\s+your\\s+turn,\\s+.+?\\s+also\\s+becomes?\\s+a\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power"
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
    private static final Pattern HAS_ALL_ELEMENTS_PATTERN = Pattern.compile(
        "(?i)^.+?\\s+has\\s+all\\s+the\\s+Elements?(?:\\s+except\\s+(?<exceptions>[^.]+))?\\.?$"
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

            // Auto abilities: "When [card/event] [trigger], [effect]"
            if (FA_AUTO_PREFIX.matcher(seg).find()) continue;

            // Trait keyword segments (Haste, Brave, Warp N, Priming "…", etc.)
            if (FA_TRAIT_KEYWORD.matcher(seg).find()) continue;

            // Parenthetical trait descriptions like "(This Forward can attack…)"
            if (seg.startsWith("(")) continue;

            // Standalone restriction sentences that trail action/auto abilities
            if (FA_RESTRICTION_SENTENCE.matcher(seg).find()) continue;

            // Name/type alias declarations and enter-dull — handled as static card properties
            if (IS_ALSO_CARD_NAME_PATTERN.matcher(seg).find())              continue;
            if (IS_ALSO_MONSTER_PATTERN.matcher(seg).find())                continue;
            if (ENTERS_FIELD_DULL_PATTERN.matcher(seg).matches())           continue;
            if (ALIAS_PLAY_RESTRICTION_PATTERN.matcher(seg).matches())      continue;
            if (BECOME_FORWARD_DURING_TURN_PATTERN.matcher(seg).find())       continue;
            if (BECOME_FORWARD_UNCONDITIONAL_PATTERN.matcher(seg).find())     continue;

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

    /** Parses a "Dull N [condition] [element] Forward(s)" cost phrase into a {@link DullForwardCost} list. */
    private static List<DullForwardCost> parseDullForwardCosts(String raw, Matcher actionMatcher) {
        if (raw == null || raw.isBlank()) return List.of();
        String countStr = actionMatcher.group("dullcount");
        if (countStr == null) return List.of();
        int    count    = Integer.parseInt(countStr);
        String cond     = actionMatcher.group("dullcond");
        String elem     = actionMatcher.group("dullelem");
        return List.of(new DullForwardCost(count,
                cond != null ? cond.toLowerCase() : null,
                elem != null ? elem    : null));
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
            return new ControlCondition(names, 0, false, null, null, null, null, 0, List.of());
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

        String element  = countM.group("element");
        String category = countM.group("category");
        String job      = countM.group("job") != null ? countM.group("job").trim() : null;
        String rawType  = countM.group("type");
        String cardType = rawType != null ? rawType.replaceAll("(?i)s$", "").trim() : null; // normalise "Forwards" → "Forward"
        if (cardType != null) cardType = Character.toUpperCase(cardType.charAt(0)) + cardType.substring(1).toLowerCase();
        int minPower    = countM.group("power") != null ? Integer.parseInt(countM.group("power")) : 0;
        String altRaw   = countM.group("altname");
        List<String> orCardNames = altRaw != null ? List.of(altRaw.trim()) : List.of();

        return new ControlCondition(List.of(), minCount, exactCount, cardType, element, job, category, minPower, orCardNames);
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
    public record BecomeForwardAbility(int power, int damageThreshold) {}

    /**
     * Returns the {@link BecomeForwardAbility} for this card, or {@code null} if it has none.
     * Recognises two forms:
     * <ul>
     *   <li>"During your turn, [name] also becomes a Forward with N power" → threshold 0</li>
     *   <li>"Damage N -- [name] also becomes a Forward with M power" → threshold N</li>
     * </ul>
     */
    public BecomeForwardAbility becomeForwardAbility() {
        for (String seg : rawFieldSegments()) {
            Matcher m = BECOME_FORWARD_DURING_TURN_PATTERN.matcher(seg);
            if (m.find()) return new BecomeForwardAbility(Integer.parseInt(m.group("power")), 0);

            int threshold = 0;
            String check = seg;
            Matcher dtM = DAMAGE_THRESHOLD_PREFIX.matcher(seg);
            if (dtM.find()) {
                threshold = Integer.parseInt(dtM.group(1));
                check = seg.substring(dtM.end()).trim();
            }
            if (threshold > 0) {
                Matcher m2 = BECOME_FORWARD_UNCONDITIONAL_PATTERN.matcher(check);
                if (m2.find()) return new BecomeForwardAbility(Integer.parseInt(m2.group("power")), threshold);
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
}
