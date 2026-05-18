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
        List<ActionAbility> actionAbilities,
        List<FieldAbility>  fieldAbilities,
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
        actionAbilities = List.copyOf(actionAbilities);
        fieldAbilities  = List.copyOf(fieldAbilities);
        job       = job       != null ? job       : "";
        category1 = category1 != null ? category1 : "";
        category2 = category2 != null ? category2 : "";
        textEn    = textEn    != null ? textEn    : "";
    }

    private static final Pattern SUMMON_EX_PREFIX =
            Pattern.compile("(?i)^\\s*\\[\\[ex\\]\\]\\s*");
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

    // Haste: start with [[br]] or (This descriptor, middle [[br]]…[[br]], or paired with other keywords
    private static final Pattern HASTE_PATTERN = Pattern.compile(
        "(?i)(?:^Haste\\s*(?:\\[\\[br\\]\\]|\\(This)|\\[\\[br\\]\\]Haste\\[\\[br\\]\\]|Haste\\s+First\\s+Strike)"
    );

    // Brave: start with [[br]] or (Attacking descriptor, middle [[br]]…[[br]], or paired with other keywords
    private static final Pattern BRAVE_PATTERN = Pattern.compile(
        "(?i)(?:^Brave\\s*(?:\\[\\[br\\]\\]|\\(Attacking)|\\[\\[br\\]\\]Brave\\[\\[br\\]\\]|Brave\\s*\\[\\[br\\]\\]|First\\s+Strike\\s+Brave|Haste\\s+Brave)"
    );

    // First Strike: start of card with (If, [[br]], or paired with Haste/Brave
    private static final Pattern FIRST_STRIKE_PATTERN = Pattern.compile(
        "(?i)(?:^First\\s+Strike\\s*(?:\\(If|\\[\\[br\\]\\])|Haste\\s+First\\s+Strike|First\\s+Strike\\s+Brave)"
    );

    // Back Attack: start of card with (Like, <p>, or [[br]]
    private static final Pattern BACK_ATTACK_PATTERN = Pattern.compile(
        "(?i)(?:^Back\\s+Attack\\s*(?:\\(Like|\\[\\[br\\]\\])|<p>Back\\s+Attack)"
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
        "(?:(?i)\\[\\[s\\]\\]\\s*([^\\[]+?)\\s*\\[\\[/\\]\\]\\s*)?"  +  // optional [[s]]Name[[/]]
        "(?=(?:《|(?i:put)\\b|(?i:discard)\\b|(?i:remove)\\b|(?i:return)\\b))" + // lookahead: must start with 《, put, discard, remove, or return
        "((?:《[^》]*》\\s*)*)"                                              +  // zero or more 《cost》 tokens
        "((?i)(?:,\\s*)?put\\s+.+?\\s+into\\s+the\\s+Break\\s+Zone\\s*)?"  + // optional BZ cost phrase
        "((?i)(?:,\\s*)?discard[^:]+)?"                                     +  // optional discard cost phrase
        "((?i)(?:,\\s*)?remove\\s+[^:]+?\\s+from\\s+(?:the\\s+)?game\\s*)?" + // optional remove-from-game cost phrase
        "((?i)(?:,\\s*)?return\\s+[^:]+?\\s+to\\s+(?:its|their)\\s+owner(?:'s|s')?\\s+hand\\s*)?" + // optional return-to-hand cost phrase
        ":\\s*"                                                              +  // colon separator
        "((?:[^\\[]|\\[(?!\\[))*)"                                              // effect text (up to next [[markup]])
    );

    // Captures the content between "put " and " into the Break Zone"
    private static final Pattern BREAK_ZONE_COST_PATTERN = Pattern.compile(
        "(?i)put\\s+(.+?)\\s+into\\s+the\\s+Break\\s+Zone"
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
            String rawName       = m.group(1);
            String costPart      = m.group(2);
            String bzRaw         = m.group(3);
            String discardRaw    = m.group(4);
            String removeRaw     = m.group(5);
            String returnRaw     = m.group(6);
            String effectRaw     = m.group(7).trim();
            if (effectRaw.isEmpty()) continue;
            // Skip if there are no CP tokens or any non-CP cost phrase (spurious match)
            if ((costPart == null || costPart.isBlank()) && bzRaw == null && discardRaw == null && removeRaw == null && returnRaw == null) continue;

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
            result.add(new ActionAbility(abilityName, requiresDull, isSpecial, crystalCost, hasXCost, cpCost, breakZoneCosts, discardCosts, removeFromGameCosts, returnToHandCosts, yourTurnOnly, oncePerTurn, mainPhaseOnly, whileCardAtk, whileCardBlk, whilePartyAtk, whileCardInHand, effectRaw));
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

    /**
     * Matches a single "When [card] [trigger], [optional you/opponent may] [effect]" block.
     * <ul>
     *   <li>{@code card}    — card name in the trigger (may contain spaces)</li>
     *   <li>{@code trigger} — "attack(s)", "block(s)", "attacks? or blocks?", or "enters? the field"</li>
     *   <li>{@code youmay}  — "you may " or "your opponent may " prefix (optional)</li>
     *   <li>{@code effect}  — remaining effect text</li>
     * </ul>
     * The effect capture ends at the next field-ability header, an action-ability cost sequence
     * ({@code 《token》:}), or end of input.
     */
    private static final Pattern FIELD_ABILITY_PATTERN = Pattern.compile(
        "(?i)When\\s+(?<card>[^,]+?)\\s+" +
        "(?<trigger>" +
            "attacks?(?:\\s+or\\s+blocks?)?|blocks?" +
            "|enters?\\s+the\\s+field" +
            "|leaves?\\s+the\\s+field" +
            "|is\\s+put\\s+(?:from\\s+the\\s+field\\s+)?into\\s+the\\s+Break\\s+Zone" +
            "|casts?\\s+a\\s+Summon" +
            "|is\\s+put\\s+into\\s+(?:your\\s+)?Damage\\s+Zone" +
            "|is\\s+removed\\s+from\\s+the\\s+game\\s+due\\s+to\\s+Warp" +
        ")\\s*,\\s+" +
        "(?<youmay>(?:you|your\\s+opponent)\\s+may\\s+)?" +
        "(?<effect>.+?)\\s*" +
        "(?=\\s*\\[\\[br\\]\\]|\\s*When\\s+[^,]+?\\s+(?:attacks?|blocks?|enters?|leaves?|is\\s+(?:put|removed))|\\s*(?:《[^》]+》)+\\s*:|\\s*$)",
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
        "(?=\\s*\\[\\[br\\]\\]|\\s*When\\s+[^,]+?\\s+(?:attacks?|blocks?|enters?|leaves?|is\\s+(?:put|removed))|\\s*(?:《[^》]+》)+\\s*:|\\s*$)",
        Pattern.DOTALL
    );

    /** Matches the restriction sentence appended to a field-ability effect, capturing flags. */
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
     * Parses all Field Abilities ("When X Y, Z") from {@code textEn}.
     * The returned list is immutable.
     */
    public static List<FieldAbility> parseFieldAbilities(String textEn) {
        if (textEn == null || textEn.isBlank()) return List.of();
        List<FieldAbility> result = new ArrayList<>();
        Matcher m = FIELD_ABILITY_PATTERN.matcher(textEn);
        while (m.find()) {
            String card      = m.group("card").trim();
            String triggerRaw = m.group("trigger").trim().toLowerCase(java.util.Locale.ROOT);
            // Normalise trigger to a canonical form
            String trigger;
            boolean cardIsParty = card.toLowerCase(java.util.Locale.ROOT).contains("party");
            if      (triggerRaw.contains("attack") && triggerRaw.contains("block")) trigger = "attacks or blocks";
            else if (triggerRaw.contains("attack") && cardIsParty)                  trigger = "party attacks";
            else if (triggerRaw.contains("attack"))                                 trigger = "attacks";
            else if (triggerRaw.contains("block"))                                  trigger = "blocks";
            else if (triggerRaw.contains("break zone"))                             trigger = "put into break zone";
            else if (triggerRaw.contains("summon"))                                 trigger = "cast summon";
            else if (triggerRaw.contains("damage zone"))                            trigger = "damage zone";
            else if (triggerRaw.contains("leaves"))                                   trigger = "leaves the field";
            else if (triggerRaw.contains("warp"))                                   trigger = "warp placed";
            else                                                                     trigger = "enters the field";

            // For "warp placed", strip the " in your hand" suffix from the card name
            if (trigger.equals("warp placed")) {
                card = card.replaceAll("(?i)\\s+in\\s+your\\s+hand$", "").trim();
            }

            String  youMayRaw   = m.group("youmay");
            boolean opponentMay = youMayRaw != null
                    && youMayRaw.trim().toLowerCase(java.util.Locale.ROOT).startsWith("your opponent");
            boolean youMay      = youMayRaw != null && !opponentMay;

            String effect = SUMMON_MARKUP.matcher(m.group("effect").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;

            FieldAbility fa = parseFieldAbilityRestrictions(card, trigger, youMay, opponentMay, effect);
            if (fa != null) result.add(fa);
        }

        // Second pass: "When a Warp Counter is removed from [CardName], [effect]"
        Matcher wm = WARP_COUNTER_PATTERN.matcher(textEn);
        while (wm.find()) {
            String target     = wm.group("target").trim();
            String youMayRaw  = wm.group("youmay");
            boolean opponentMay = youMayRaw != null
                    && youMayRaw.trim().toLowerCase(java.util.Locale.ROOT).startsWith("your opponent");
            boolean youMay      = youMayRaw != null && !opponentMay;
            String effect = SUMMON_MARKUP.matcher(wm.group("effect").trim()).replaceAll("").trim();
            if (effect.isEmpty()) continue;
            FieldAbility fa = parseFieldAbilityRestrictions(target, "warp counter removed", youMay, opponentMay, effect);
            if (fa != null) result.add(fa);
        }

        return List.copyOf(result);
    }

    /**
     * Strips trigger-restriction sentences from {@code effect}, records the resulting flags,
     * and returns a complete {@link FieldAbility}.  Returns {@code null} if the effect is empty
     * after stripping.
     */
    private static FieldAbility parseFieldAbilityRestrictions(
            String card, String trigger, boolean youMay, boolean opponentMay, String effect) {

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
        return new FieldAbility(card, trigger, youMay, opponentMay, effect,
                oncePerTurn, yourTurnOnly, rfpConditionCard, castPaymentMinElements);
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
        for (String e : element.split("/"))
            if (e.equalsIgnoreCase(elem)) return true;
        return false;
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
