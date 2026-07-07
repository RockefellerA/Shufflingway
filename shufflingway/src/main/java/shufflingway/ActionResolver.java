package shufflingway;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Action Ability effect text into executable game effects and resolves
 * them against the live game state via a {@link GameContext}.
 *
 * <h3>Adding new effect types</h3>
 * <ol>
 *   <li>Add a {@code static final Pattern} for the new text pattern.</li>
 *   <li>Add a {@code tryParse*} method that returns a {@code Consumer<GameContext>}
 *       (or {@code null} if the text does not match).</li>
 *   <li>Call it from {@link #parse(String)}.</li>
 * </ol>
 */
public class ActionResolver {

    // -------------------------------------------------------------------------
    // Patterns
    // -------------------------------------------------------------------------

    /**
     * Matches the "Choose" targeted effect header:
     * "Choose [up to] N [condition] [element] [targets] [of cost X [or less|more]] [control] [zone]
     *  [separator] followup"
     * <ul>
     *   <li>Group {@code upto}      — present when "up to" precedes the count</li>
     *   <li>Group {@code count}     — number of cards to choose</li>
     *   <li>Group {@code condition} — optional: "dull", "damaged", "attacking", "blocking", or "active"</li>
     *   <li>Group {@code element}   — optional element name, e.g. "Fire", "Earth"</li>
     *   <li>Group {@code category}  — optional category filter, e.g. "VII" in "Category VII Forward"</li>
     *   <li>Group {@code targets}   — card type(s): "Forward(s)", "Forward(s) or Monster(s)",
     *                                 "Backup(s)", or "Character(s)"</li>
     *   <li>Group {@code cost}      — optional CP cost value, e.g. "3" in "of cost 3 or less"</li>
     *   <li>Group {@code costlist}  — optional comma-separated digits between the first cost and
     *                                 the final " or " term in "cost A, B, C or D" multi-value lists</li>
     *   <li>Group {@code costcmp}   — optional: "less", "more", "higher" (alias for "more"), or a digit value for
     *                                 "cost N or M" / "cost A, B, … or M" filters (absent = exact match)</li>
     *   <li>Group {@code control}   — optional: "opponent controls", "your opponent controls",
     *                                 or "you control"</li>
     *   <li>Group {@code excludekw}   — optional keyword to exclude, from "without 《Keyword》" (e.g. "Multicard")</li>
     *   <li>Group {@code excludename} — optional card name to exclude, from "other than Card Name X"</li>
     *   <li>Group {@code zone}      — optional zone, e.g. "in your Break Zone" or
     *                                 "in your opponent's Break Zone"</li>
     *   <li>Group {@code followup}  — the action to apply to chosen targets</li>
     * </ul>
     */
    private static final Pattern CHOOSE_CHARACTER_PATTERN = Pattern.compile(
            "(?i)Choose\\s+" +
                    "(?:(?<anycount>any\\s+number)|(?<upto>up\\s+to\\s+)?(?<count>\\d+))\\s+(?:of\\s+)?" +
                    "(?:(?<condition>dull|damaged|attacking|blocking|active)\\s+)?" +
                    "(?:(?<element>(?:Multi-Element|Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*)\\s+)?" +
                    "(?:Category\\s+(?<category>.+?)(?=\\s+(?:cards?|Forwards?|Backups?|Characters?|Monsters?|Summons?))\\s+)?" +
                    "(?<targets>cards?|Forwards?(?:\\s+(?:and/or|or)\\s+(?:Monsters?|Backups?))?|Monsters?|Backups?|Characters?|Summons?" +
                    "|\\[Job\\s+\\([^)]+\\)\\]" +
                    "|\\[Card\\s+Name\\s+\\([^)]+\\)\\]" +
                    "|Card\\s+Name\\s+.+?\\s+Forwards?(?:\\s+or\\s+Job\\s+.+?\\s+Forwards?)*" +
                    "|Card\\s+Name\\s+\\S+(?:\\s+\\S+)*?(?:\\s+\\([^)]+\\))?(?:\\s+or\\s+Card\\s+Name\\s+\\S+(?:\\s+\\S+)*?(?:\\s+\\([^)]+\\))?)*" +
                    "|Job\\s+.+?\\s+(?:and/)?or\\s+Card\\s+Name\\s+\\S+" +
                    "|Job\\s+.+?\\s+Forwards?(?:\\s+or\\s+Job\\s+.+?\\s+Forwards?)*" +
                    "|Job\\s+.+?(?=\\s+(?:of\\s+|other\\s+than|in\\s+your|from\\s+your)|[,.]))" +
                    "(?:\\s+Cards?)?" +
                    "(?:\\s+with\\s+(?<trait>Brave|Haste|First\\s+Strike))?" +
                    "(?:\\s+that\\s+(?<postcondition>entered\\s+the\\s+field\\s+this\\s+turn|entered\\s+this\\s+turn))?" +
                    "(?:\\s+without\\s+《(?<excludekw>[^》]+)》)?" +
                    "(?:\\s+of\\s+(?:any|an)\\s+Element\\s+(?:except|other\\s+than)\\s+(?<excludeelem>" +
                    "(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
                    "(?:\\s+and\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*))?" +
                    "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)" +
                    "(?:,\\s*(?<costlist>\\d+(?:\\s*,\\s*\\d+)*))?" +
                    "(?:\\s+or\\s+(?<costcmp>less|more|higher|\\d+))?)?" +
                    "(?:\\s+of\\s+(?:power\\s+)?(?<power>\\d+)(?:\\s+power)?(?:\\s+or\\s+(?<powercmp>less|more))?)?" +
                    "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls|you\\s+control))?" +
                    "(?:\\s+other\\s+than\\s+(?:Card\\s+Name\\s+)?(?<excludename>\\S(?:.*?\\S)?))?" +
                    "(?:\\s+(?<zone>(?:in|from)\\s+(?:your(?:\\s+opponent(?:'s)?)?|the|either\\s+player'?s|any\\s+player'?s)\\s+Break\\s+Zone))?" +
                    "(?:\\s+blocking\\s+" +
                    "(?:(?:a\\s+(?:Job\\s+)?(?<blockingjob>[^.,]+?)(?=\\s*[.,]))" +
                    "|(?<blockingname>[^.,]+?)(?=\\s*[.,])))?" +
                    "(?:[.]\\s+|\\s+and\\s+|,\\s*)" +
                    "(?<followup>.+)"
    );

    /**
     * Matches "Choose N [targets] you control and N [targets] opponent controls. [followup]"
     * — one selection from the active player's side and one from the opponent's side.
     */
    private static final Pattern CHOOSE_ONE_EACH_PATTERN = Pattern.compile(
        "(?i)Choose\\s+(?<count1>\\d+)\\s+" +
        "(?<targets1>Forwards?|Backups?|Characters?|Monsters?)\\s+" +
        "you\\s+control\\s+and\\s+(?<count2>\\d+)\\s+" +
        "(?<targets2>Forwards?|Backups?|Characters?|Monsters?)\\s+" +
        "(?:your\\s+)?opponent\\s+controls[.]?\\s+" +
        "(?<followup>.+)"
    );

    /**
     * Matches "The former gains +N power until end of turn. Then, the former deals damage equal
     * to its power to the latter." — boost the former, then deal the (post-boost) power as damage to the latter.
     * Group {@code boost} = numeric power amount.
     */
    private static final Pattern FORMER_BOOST_THEN_POWER_DAMAGE_TO_LATTER = Pattern.compile(
        "(?i)The\\s+former\\s+gains?\\s+\\+(?<boost>\\d+)\\s+power\\s+until\\s+(?:the\\s+)?end\\s+of\\s+" +
        "(?:(?:the|your)\\s+)?turn[.]\\s+Then[,]?\\s+the\\s+former\\s+deals?\\s+damage\\s+equal\\s+to\\s+" +
        "its\\s+power\\s+to\\s+the\\s+latter[.!]?"
    );

    /**
     * Matches "Choose 1 Forward you control other than [CardName]. During this turn, the next
     * damage dealt to it is dealt to [CardName] instead."
     * Groups: {@code shield} = excluded/redirect card name (first occurrence);
     *         {@code redirect} = redirect target name (second occurrence, should match {@code shield}).
     */
    private static final Pattern CHOOSE_FORWARD_REDIRECT_TO_NAMED = Pattern.compile(
        "(?i)Choose\\s+1\\s+Forward\\s+you\\s+control\\s+other\\s+than\\s+(?<shield>[A-Za-z][^.]+?)[.!]\\s+" +
        "During\\s+this\\s+turn[,.]?\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+it\\s+" +
        "is\\s+(?:received\\s+by|dealt\\s+to)\\s+(?<redirect>[A-Za-z][^.!]+?)\\s+instead[.!]?"
    );

    /**
     * Matches "During this turn, the next damage dealt to the former is received by / dealt to the latter instead."
     * — one-shot damage redirect from former to latter, with an optional trailing bonus clause.
     * Group {@code suffix} = optional bonus text (e.g. BACKUP_CP_DRAW).
     */
    private static final Pattern FORMER_LATTER_DAMAGE_REDIRECT = Pattern.compile(
        "(?i)During\\s+this\\s+turn[,.]?\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+the\\s+former\\s+" +
        "is\\s+(?:received\\s+by|dealt\\s+to)\\s+the\\s+latter\\s+instead[.!]?" +
        "(?<suffix>(?:\\s+.+)?)$",
        Pattern.DOTALL
    );

    /**
     * Matches "Until the end of the turn, the former gains +N power [and Traits]. Deal the latter N damage."
     * optionally followed by a bonus clause (e.g. BACKUP_CP_DRAW).
     * Groups: {@code boost} = power amount; {@code traits} = optional trait string;
     * {@code damage} = damage amount; {@code suffix} = optional trailing bonus text.
     */
    private static final Pattern FORMER_BOOST_TRAITS_LATTER_DIRECT_DAMAGE = Pattern.compile(
        "(?i)Until\\s+the\\s+end\\s+of\\s+the\\s+turn[,.]?\\s+the\\s+former\\s+gains?\\s+" +
        "\\+(?<boost>\\d+)\\s+[Pp]ower" +
        "(?<traits>(?:\\s*(?:and|,)\\s*(?:Haste|First\\s+Strike|Brave))*)\\s*[.]\\s+" +
        "Deal\\s+the\\s+latter\\s+(?<damage>\\d+)\\s+damage[.!]?" +
        "(?<suffix>(?:\\s+.+)?)$",
        Pattern.DOTALL
    );

    /**
     * Matches "Until the end of the turn, the former loses [traits]. Then, the latter gains all
     * the abilities lost by the previous effect until the end of the turn."
     * Group {@code traits} = the comma/and-separated trait list (Haste, First Strike, Brave, etc.).
     */
    private static final Pattern FORMER_LOSES_TRAITS_LATTER_GAINS = Pattern.compile(
        "(?i)Until\\s+the\\s+end\\s+of\\s+the\\s+turn[,.]?\\s+the\\s+former\\s+loses\\s+" +
        "(?<traits>[^.]+?)[.]\\s+Then[,.]?\\s+the\\s+latter\\s+gains\\s+all\\s+the\\s+abilities\\s+" +
        "lost\\s+by\\s+the\\s+previous\\s+effect\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn[.!]?"
    );

    /**
     * Matches escalating BZ-count conditionals for former/latter: always dull former; if ≥N1
     * Card Name X in BZ dull latter; if ≥N2 freeze both; if ≥N3 opponent discards.
     */
    private static final Pattern FORMER_DULL_LATTER_BZ_NAME_ESCALATE = Pattern.compile(
        "(?i)Dull\\s+the\\s+former[.]\\s+If\\s+you\\s+have\\s+(?<n1>\\d+)\\s+or\\s+more\\s+Card\\s+Name\\s+" +
        "(?<cardname>.+?)\\s+in\\s+your\\s+Break\\s+Zone[,.]?\\s+also\\s+dull\\s+the\\s+latter[.]\\s+" +
        "If\\s+you\\s+have\\s+(?<n2>\\d+)\\s+or\\s+more[,.]?\\s+also\\s+Freeze\\s+them[.]\\s+" +
        "If\\s+you\\s+have\\s+(?<n3>\\d+)\\s+or\\s+more[,.]?\\s+also\\s+your\\s+opponent\\s+discards\\s+" +
        "(?<discardN>\\d+)\\s+cards?\\s+from\\s+their\\s+hand[.!]?"
    );

    /**
     * Matches "Until the end of the turn, the former gains +N power and 'This Forward cannot
     * become dull by your opponent's Summons or abilities.' If you have received N damage or more,
     * also deal the latter damage equal to the highest power Forward you control."
     */
    private static final Pattern FORMER_BOOST_DULL_IMMUNITY_COND_DAMAGE_LATTER = Pattern.compile(
        "(?i)Until\\s+the\\s+end\\s+of\\s+the\\s+turn[,.]?\\s+the\\s+former\\s+gains\\s+" +
        "\\+(?<boost>\\d+)\\s+power\\s+and\\s+\\W?This\\s+Forward\\s+cannot\\s+become\\s+dull\\s+" +
        "by\\s+your\\s+opponent.s\\s+Summons?\\s+or\\s+abilities\\W?\\s+" +
        "If\\s+you\\s+have\\s+received\\s+(?<dmgthresh>\\d+)\\s+(?:points?\\s+of\\s+)?damage\\s+or\\s+more[,.]?\\s+" +
        "also\\s+deal\\s+the\\s+latter\\s+damage\\s+equal\\s+to\\s+the\\s+highest\\s+power\\s+" +
        "Forward\\s+you\\s+control[.!]?"
    );

    /**
     * Matches "The former deals damage equal to its power to the latter."
     * — former deals its current power as damage to the latter (no boost).
     */
    private static final Pattern FORMER_DEALS_POWER_DAMAGE_TO_LATTER = Pattern.compile(
        "(?i)The\\s+former\\s+deals?\\s+damage\\s+equal\\s+to\\s+its\\s+power\\s+to\\s+the\\s+latter[.!]?"
    );

    /**
     * Matches "Break the former. If [card] enters the field due to Warp, also break the latter."
     * — always break the former; break the latter only when the source entered via Warp.
     */
    private static final Pattern FORMER_BREAK_COND_WARP_LATTER_BREAK = Pattern.compile(
        "(?i)Break\\s+the\\s+former[.!]?\\s+If\\s+.+?\\s+enters\\s+the\\s+field\\s+due\\s+to\\s+Warp[,.]?\\s+" +
        "also\\s+break\\s+the\\s+latter[.!]?"
    );

    /**
     * Matches "Deal the former N damage. If you control M or more Backups, also deal the latter N damage."
     * Groups: {@code dmg1} = former damage; {@code n} = backup threshold; {@code dmg2} = latter damage.
     */
    private static final Pattern FORMER_DAMAGE_COND_BACKUP_COUNT_LATTER_DAMAGE = Pattern.compile(
        "(?i)Deal\\s+the\\s+former\\s+(?<dmg1>\\d+)\\s+damage[.!]?\\s+" +
        "If\\s+you\\s+control\\s+(?<n>\\d+)\\s+or\\s+more\\s+Backups?[,.]?\\s+" +
        "also\\s+deal\\s+the\\s+latter\\s+(?<dmg2>\\d+)\\s+damage[.!]?"
    );

    /**
     * Matches desc2 text "Backup with a cost equal to or less than that Forward in your Break Zone"
     * — a relative cost constraint that depends on the first chosen target at execution time.
     */
    private static final Pattern DESC_BZ_BACKUP_COST_RELATIVE = Pattern.compile(
        "(?i)Backup\\s+with\\s+a\\s+cost\\s+equal\\s+to\\s+or\\s+less\\s+than\\s+" +
        "(?:that\\s+Forward|the\\s+former)\\s+in\\s+(?:your|the)\\s+Break\\s+Zone"
    );

    /**
     * Matches "If you have cast a Card Name [X] other than [X] this turn, also [effect]."
     * Fires when the ability owner has cast another copy of the named card earlier this turn.
     * Group {@code name} = the card name; group {@code effect} = the bonus effect text.
     */
    private static final Pattern CAST_CARD_NAME_OTHER_BONUS = Pattern.compile(
        "(?i)[.]?\\s*If\\s+you\\s+have\\s+cast\\s+(?:a\\s+)?Card\\s+Name\\s+(?<name>.+?)" +
        "\\s+other\\s+than\\s+.+?\\s+this\\s+turn[,.]?\\s+also\\s+(?<effect>.+)"
    );

    /**
     * Matches "Choose [up to] N [desc1] and [up to] N [desc2]. [effects]"
     * where the effects text uses "the former" and "the latter" as pronouns for the two target groups.
     */
    private static final Pattern CHOOSE_FORMER_LATTER_PATTERN = Pattern.compile(
        "(?i)^Choose\\s+(?<upTo1>up\\s+to\\s+)?(?<count1>\\d+)\\s+(?<desc1>.+?)" +
        "\\s+and\\s+(?<upTo2>up\\s+to\\s+)?(?<count2>\\d+)\\s+(?<desc2>.+?)[.]\\s*" +
        "(?<effects>.+)$",
        Pattern.DOTALL
    );

    /**
     * Parses a single target description in a CHOOSE_FORMER_LATTER clause:
     * "[condition] [element] CardType [of cost N [or less|more]] [control] [zone]"
     */
    private static final Pattern TARGET_DESC_PATTERN = Pattern.compile(
        "(?i)^" +
        "(?:(?<condition>dull|damaged|attacking|blocking|active)\\s+)?" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<cardtype>Forwards?|Backups?|Characters?|Monsters?)" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "(?:\\s+other\\s+than\\s+(?<excludename>.+?))?" +
        "(?:\\s+(?<zone>(?:in|from)\\s+(?:your(?:\\s+opponent(?:'s)?)?|the)\\s+Break\\s+Zone))?" +
        "$"
    );

    /**
     * Matches "Choose N [type1] and N [type2] [control?]. [followup]"
     * — two cards of different types from the same pool.
     * Optional control qualifier ("opponent controls" / "you control"); if absent, any side is valid.
     */
    private static final Pattern CHOOSE_TWO_MIXED_TYPES_PATTERN = Pattern.compile(
        "(?i)Choose\\s+(?<count1>\\d+)\\s+(?<type1>Forwards?|Backups?|Characters?|Monsters?)\\s+" +
        "and\\s+(?<count2>\\d+)\\s+(?<type2>Forwards?|Backups?|Characters?|Monsters?)" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+controls?))?[.]?\\s+" +
        "(?<followup>.+)"
    );

    /**
     * Matches "Choose up to N [type1], up to N [type2], and up to N [type3]. [followup]"
     * — up to one card of each of three different types.
     */
    private static final Pattern CHOOSE_THREE_MIXED_TYPES_PATTERN = Pattern.compile(
        "(?i)Choose\\s+up\\s+to\\s+(?<count1>\\d+)\\s+(?<type1>Forwards?|Backups?|Characters?|Monsters?),\\s+" +
        "up\\s+to\\s+(?<count2>\\d+)\\s+(?<type2>Forwards?|Backups?|Characters?|Monsters?),\\s+and\\s+" +
        "up\\s+to\\s+(?<count3>\\d+)\\s+(?<type3>Forwards?|Backups?|Characters?|Monsters?)[.]?\\s+" +
        "(?<followup>.+)"
    );

    /**
     * Matches "Choose 1 Forward. [CardName] deals you N point(s) of damage.
     * If the cost of the Forward is equal to or less than the damage you have received, break it."
     * Groups: {@code name} — the card dealing the damage; {@code amount} — damage dealt.
     */
    private static final Pattern CHOOSE_FORWARD_DEAL_SELF_DAMAGE_BREAK_IF_COST_LE_DAMAGE = Pattern.compile(
        "(?i)^Choose\\s+1\\s+Forward\\." +
        "\\s+(?<name>.+?)\\s+deals?\\s+you\\s+(?<amount>\\d+)\\s+points?\\s+of\\s+damage\\." +
        "\\s+If\\s+the\\s+cost\\s+of\\s+the\\s+Forward\\s+is\\s+equal\\s+to\\s+or\\s+less\\s+than\\s+" +
        "the\\s+damage\\s+you\\s+have\\s+received,?\\s+break\\s+it\\.?"
    );

    /**
     * Matches "Choose 1 Forward other than [CardName]. Until the end of the turn,
     * [CardName] and the chosen Forward lose power of any value less than [CardName]'s power.
     * (Units must be 1000.)"
     * Groups: {@code card} — the named card (must match in all three positions).
     */
    private static final Pattern CHOOSE_FORWARD_SHARED_POWER_LOSS_PATTERN = Pattern.compile(
        "(?i)^Choose\\s+1\\s+Forward\\s+other\\s+than\\s+(?<card>[^.]+?)\\." +
        "\\s+Until\\s+the\\s+end\\s+of\\s+(?:the\\s+)?turn,?\\s+" +
        "(?<card2>[^.]+?)\\s+and\\s+the\\s+chosen\\s+Forward\\s+lose\\s+power\\s+of\\s+any\\s+value\\s+" +
        "less\\s+than\\s+(?<card3>[^.']+?)'s?\\s+power\\.?" +
        "(?:\\s*\\(Units?\\s+must\\s+be\\s+1000\\.?\\))?"
    );

    /**
     * Normalises "Element Type or Element Type" → "Element or Element Type" so that
     * CHOOSE_CHARACTER_PATTERN's element group can capture both elements.
     * E.g. "Light Character or Dark Character" → "Light or Dark Character".
     */
    private static final Pattern ELEM_TYPE_OR_ELEM_TYPE = Pattern.compile(
        "(?i)(Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+(Forwards?|Backups?|Monsters?|Characters?)" +
        "\\s+or\\s+(Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+\\2"
    );

    /** Matches {@code [Job (name)]} bracket notation; group 1 is the job name. */
    private static final Pattern JOB_BRACKET_PATTERN = Pattern.compile(
        "(?i)\\[Job\\s+\\(([^)]+)\\)\\]"
    );

    /** Matches {@code [Card Name (name)]} bracket notation; group 1 is the card name. */
    private static final Pattern CARD_NAME_BRACKET_PATTERN = Pattern.compile(
        "(?i)\\[Card\\s+Name\\s+\\(([^)]+)\\)\\]"
    );

    /** Matches one {@code Job name Forward(s)} segment in the written job-filter form; group 1 is the job name. */
    private static final Pattern JOB_WRITTEN_SEGMENT = Pattern.compile(
        "(?i)Job\\s+(.+?)\\s+Forwards?"
    );

    /** Matches "Cancel its effect." — used to counter a Summon on the stack. */
    private static final Pattern FOLLOWUP_CANCEL_EFFECT = Pattern.compile(
        "(?i)Cancel\\s+its\\s+effect\\.?"
    );

    /** Matches Y'shtola-style "Choose 1 Summon or auto-ability. Cancel its effect." */
    private static final Pattern STANDALONE_CANCEL_STACK_ENTRY_PATTERN = Pattern.compile(
        "(?i)Choose\\s+1\\s+Summon\\s+or\\s+auto-ability\\.\\s+Cancel\\s+its\\s+effect\\.?"
    );

    /** Matches "Choose 1 Summon targeting a Character you control. Cancel its effect." */
    private static final Pattern CANCEL_SUMMON_TARGETING_MY_CHARACTER = Pattern.compile(
        "(?i)Choose\\s+1\\s+Summon\\s+targeting\\s+a\\s+Character\\s+you\\s+control\\.\\s+Cancel\\s+its\\s+effect\\.?"
    );

    /**
     * Matches the general "Choose 1 [ability type(s)] [optional target filter]. Cancel its effect."
     * family.  Handles any combination of auto-ability / action ability / special ability / ability
     * (two types joined by " or " also accepted).  An optional "that is choosing [filter] you control"
     * or "that has only one target" clause is captured but not enforced in code.
     * Group {@code types} — the raw ability-type string (e.g. "auto-ability", "special ability or auto ability").
     */
    private static final Pattern CANCEL_ABILITY_ON_STACK = Pattern.compile(
        "(?i)Choose\\s+1\\s+" +
        "(?<types>(?:auto[- ]ability|action\\s+ability|special\\s+ability|ability)" +
        "(?:\\s+or\\s+(?:auto[- ]ability|action\\s+ability|special\\s+ability))?)" +
        "(?:\\s+that\\s+(?:is\\s+)?choosing\\s+(?<tgtFilter>[^.]+?))?" +
        "(?:\\s+that\\s+has\\s+only\\s+one\\s+target)?" +
        "\\.\\s*Cancel\\s+its\\s+effect[.!]?"
    );

    /**
     * Matches "Choose 1 [ability type(s)] [optional 'that has only one target'].
     * You may choose another target to become the new target (...)."
     * Group {@code types} — the raw ability-type string.
     */
    private static final Pattern REDIRECT_ABILITY_TARGET = Pattern.compile(
        "(?i)Choose\\s+1\\s+" +
        "(?<types>(?:auto[- ]ability|action\\s+ability|special\\s+ability|ability)" +
        "(?:\\s+or\\s+(?:auto[- ]ability|action\\s+ability|special\\s+ability))?)" +
        "(?:\\s+that\\s+has\\s+only\\s+one\\s+target)?" +
        "\\.\\s*You\\s+may\\s+choose\\s+another\\s+target\\s+to\\s+become\\s+the\\s+new\\s+target" +
        "(?:\\s*\\([^)]*\\))?" +
        "[.!]?"
    );

    /**
     * Matches "Choose 1 auto-ability. Cancel its effect. If the cancelled auto-ability triggered
     * from a Forward, deal that Forward N damage."
     * Group {@code amount} — damage to deal if the source was a Forward.
     */
    private static final Pattern CANCEL_AUTO_ABILITY_DAMAGE_IF_FORWARD = Pattern.compile(
        "(?i)^Choose\\s+1\\s+auto-ability\\.\\s+Cancel\\s+its\\s+effect\\.\\s+" +
        "If\\s+the\\s+cancelled\\s+auto-ability\\s+triggered\\s+from\\s+a\\s+Forward,\\s+" +
        "deal\\s+that\\s+Forward\\s+(?<amount>\\d+)\\s+damage\\.?$"
    );

    /** Matches "deal it/them N damage". */
    /**
     * Matches "Deal it/them [and CardName] N damage".
     * <ul>
     *   <li>{@code also} — optional named Forward that also receives the damage</li>
     *   <li>{@code amount} — fixed damage value</li>
     * </ul>
     */
    private static final Pattern FOLLOWUP_DAMAGE = Pattern.compile(
        "(?i)deal\\s+(?:it|them)(?:\\s+and\\s+(?<also>.+?))?\\s+(?<amount>\\d+)\\s+damage"
    );

    /**
     * Matches "Deal it/them N damage and M point(s) of damage to that Forward's controller."
     * Groups: {@code amount} — damage to the chosen Forward; {@code controllerdmg} — card damage dealt to its controller.
     */
    private static final Pattern FOLLOWUP_DAMAGE_AND_CONTROLLER_DAMAGE = Pattern.compile(
        "(?i)deal\\s+(?:it|them)\\s+(?<amount>\\d+)\\s+damage\\s+and\\s+(?<controllerdmg>\\d+)\\s+points?\\s+of\\s+damage\\s+to\\s+that\\s+(?:Forward|Character|Monster|Backup)'?s?\\s+controller\\.?"
    );

    /**
     * Matches the "That Forward's controller discards N card(s) from (their|his/her) hand" secondary
     * clause that follows a Choose+followup primary (Physalis, Sephiroth, Hades, …). The discarder
     * is resolved at runtime from {@link GameContext#lastChosenTargets()}.
     * Group {@code count} — number of cards to discard.
     */
    private static final Pattern FOLLOWUP_TARGET_CONTROLLER_DISCARDS = Pattern.compile(
        "(?i)^That\\s+Forward(?:'s|s)?\\s+controller\\s+discards?\\s+(?<count>\\d+)\\s+cards?\\s+" +
        "from\\s+(?:their|his/her|his|her)\\s+hand\\.?$"
    );

    /**
     * Matches "You may discard 1 Card Name X from your hand. If you do so, deal it N damage."
     * Groups: {@code cardname}, {@code amount}.
     */
    private static final Pattern FOLLOWUP_MAY_DISCARD_NAMED_DEAL_DAMAGE = Pattern.compile(
        "(?i)^you\\s+may\\s+discard\\s+1\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+from\\s+your\\s+hand\\.\\s+If\\s+you\\s+do\\s+so,\\s+deal\\s+it\\s+(?<amount>\\d+)\\s+damage\\.?$"
    );

    /**
     * Matches "Deal it/them N damage. If &lt;condition&gt;, deal it/them M damage instead."
     * Groups: {@code base}, {@code cond}, {@code alt}.
     */
    private static final Pattern FOLLOWUP_DAMAGE_INSTEAD = Pattern.compile(
        "(?i)deal\\s+(?:it|them)\\s+(?<base>\\d+)\\s+damage\\.\\s+If\\s+(?<cond>.+?),\\s+deal\\s+(?:it|them)\\s+(?<alt>\\d+)\\s+damage\\s+instead\\.?"
    );

    /**
     * Matches any "P. If [name] results from an EX Burst, A instead." followup.
     * Groups: {@code primary} (text before the period), {@code alt} (alternate action text).
     * The card name before "results from an EX Burst" is intentionally not captured.
     */
    private static final Pattern FOLLOWUP_INSTEAD_EXBURST = Pattern.compile(
        "(?i)(?<primary>.+?)\\.\\s+If\\s+\\S+(?:\\s+\\S+)*?\\s+results\\s+from\\s+an\\s+EX\\s+Burst,\\s+(?<alt>.+?)\\s+instead[.!]?"
    );

    /**
     * Matches "deal it/them damage equal to &lt;expr&gt;" where the amount is computed
     * from the game state at resolution time.  Exactly one named group will be set:
     * <ul>
     *   <li>{@code highest} — "the highest [power] Forward you control['s power]"</li>
     *   <li>{@code halfcard}     — card name in "half of &lt;name&gt;'s power [(round up/down…)]"</li>
     *   <li>{@code halfrounding} — "up" or "down" when an explicit rounding clause is present (absent = round down, matching legacy behaviour)</li>
     *   <li>{@code itspower} — "its/their power [minus &lt;minus&gt;]"</li>
     *   <li>{@code card}     — card name in "&lt;name&gt;'s power"</li>
     * </ul>
     * Group {@code minus} is set alongside {@code itspower} when a subtraction is present.
     */
    private static final Pattern FOLLOWUP_DAMAGE_EXPR = Pattern.compile(
        "(?i)deal\\s+(?:it|them)\\s+damage\\s+equal\\s+to\\s+" +
        "(?:" +
            "(?<highest>the\\s+highest(?:\\s+power)?\\s+Forward(?:\\s+you\\s+control)?(?:'s\\s+power)?)" +
            "|half\\s+of\\s+(?<halfcard>.+?)'s\\s+power(?:\\s*\\(\\s*round\\s+(?<halfrounding>up|down)[^)]*\\))?" +
            "|(?<halfitspower>half\\s+of\\s+(?:its|their)\\s+power)(?:\\s*\\(\\s*round\\s+(?<halfitsrounding>up|down)[^)]*\\))?" +
            "|(?<itspower>(?:its|their)\\s+power)(?:\\s+minus\\s+(?<minus>\\d+))?" +
            "|(?<dullforward>the\\s+power\\s+of\\s+the\\s+dull(?:ed)?\\s+Forward)" +
            "|(?<discardedfwd>the\\s+discarded\\s+Forward(?:'s\\s+power)?)" +
            "|(?<bzcostfwd>the\\s+power\\s+of\\s+the\\s+Forward\\s+put\\s+in(?:to)?\\s+the\\s+Break\\s+Zone)" +
            "|(?<card>.+?)'s\\s+power" +
        ")"
    );

    /**
     * Matches "&lt;SourceCardName&gt; and the chosen Forward deal damage equal to their respective power to the other."
     * Used as a followup after "Choose 1 Forward …" to apply simultaneous power-as-damage between
     * the source card and the selected target.
     * <ul>
     *   <li>{@code srcname} — the card name on the left side of "and the chosen Forward"; verified
     *       against the ability's source card at match time.</li>
     * </ul>
     */
    private static final Pattern FOLLOWUP_MUTUAL_POWER_DAMAGE = Pattern.compile(
        "(?i)(?<srcname>.+?)\\s+and\\s+the\\s+chosen\\s+Forward\\s+deal\\s+damage\\s+equal\\s+to\\s+their\\s+respective\\s+power\\s+to\\s+the\\s+other[.!]?"
    );

    /** Matches "Each Forward deals damage equal to its power to the other." (used in choose-one-each contexts). */
    private static final Pattern FOLLOWUP_EACH_FORWARD_MUTUAL_POWER_DAMAGE = Pattern.compile(
        "(?i)Each\\s+Forward\\s+deals\\s+damage\\s+equal\\s+to\\s+its\\s+power\\s+to\\s+the\\s+other[.!]?"
    );

    /**
     * Matches "Deal it/them [base] damage [and [per] more damage] for each [source]".
     * <ul>
     *   <li>{@code base}       — base damage per unit (or fixed base when {@code per} is set)</li>
     *   <li>{@code per}        — additional damage per each unit (the "and N more" form)</li>
     *   <li>{@code selfdmg}    — source is P1's damage-zone count</li>
     *   <li>{@code jobbname}   — bracket job: "[Job (name)] you control"</li>
     *   <li>{@code jobwname}   — written job: "Job Name you control"</li>
     *   <li>{@code chartype}   — type filter: "Forwards/Characters/etc. you control"</li>
     *   <li>{@code costfilter} — optional exact cost: "of cost N" appended to chartype</li>
     *   <li>{@code bzname}     — card name in P1's Break Zone</li>
     *   <li>{@code opphand}    — source is the opponent's hand size</li>
     *   <li>{@code xpaid}      — source is the X CP value paid for this ability</li>
     * </ul>
     */
    private static final Pattern FOLLOWUP_DAMAGE_FOR_EACH = Pattern.compile(
        "(?i)deal\\s+(?:it|them)\\s+(?<base>\\d+)\\s+damage" +
        "(?:\\s+(?<op>and|minus)\\s+(?<per>\\d+)\\s+(?:more\\s+)?damage)?" +
        "\\s+for\\s+each\\s+" +
        "(?:" +
            "(?<selfdmg>point\\s+of\\s+damage\\s+you\\s+have\\s+received)" +
            "|\\[Job\\s+\\((?<jobbname>[^)]+)\\)\\]\\s+you\\s+control" +
            "|Job\\s+(?<jobwname>.+?)(?:\\s+(?<jobwtype>Forwards?|Backups?|Monsters?))?\\s+you\\s+control" +
            "|(?:Category\\s+(?<category>\\S+)\\s+)?(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?(?<chartype>Forwards?|Characters?|Backups?|Monsters?)(?:\\s+of\\s+cost\\s+(?<costfilter>\\d+))?\\s+you\\s+control" +
            "|Card\\s+Name\\s+(?<bzname>\\S+(?:\\s+\\([^)]+\\))?)\\s+in\\s+your\\s+Break\\s+Zone" +
            "|(?<opphand>card\\s+in\\s+your\\s+opponent'?s?\\s+hand)" +
            "|(?<xpaid>CP\\s+paid\\s+as\\s+X)" +
            "|(?<crystal>《C》)\\s+you\\s+have" +
            "|(?<cpDiffElem>CP\\s+of\\s+a\\s+different\\s+Element\\s+you\\s+paid\\s+to\\s+cast\\s+\\S+)" +
        ")" +
        "[.!]?"
    );

    /** Matches "Activate it" or "Activate them". */
    private static final Pattern FOLLOWUP_ACTIVATE = Pattern.compile(
        "(?i)Activate\\s+(?:it|them)\\.?"
    );

    /** Matches "Dull it or activate it." / "Dull them or activate them." — toggle dull/active. */
    private static final Pattern FOLLOWUP_DULL_OR_ACTIVATE = Pattern.compile(
        "(?i)Dulls?\\s+(?:it|them)\\s+or\\s+activates?\\s+(?:it|them)[.!]?"
    );

    /**
     * Matches "Dull it or freeze it." / "Dull them or freeze them." — dull if active,
     * freeze if already dulled. (Order-of-words variants like "dull or freeze it" are not used in card text.)
     */
    private static final Pattern FOLLOWUP_DULL_OR_FREEZE = Pattern.compile(
        "(?i)Dulls?\\s+(?:it|them)\\s+or\\s+freezes?\\s+(?:it|them)[.!]?"
    );

    /** Matches "Dull or Freeze it/them" — compact imperative form used in former/latter effects. */
    private static final Pattern FOLLOWUP_DULL_OR_FREEZE_COMPACT = Pattern.compile(
        "(?i)Dull\\s+or\\s+Freeze\\s+(?:it|them)[.!]?"
    );

    /** Matches "dull it/them" or "dulls it/them" (third-person form used in opponent-selects effects). */
    private static final Pattern FOLLOWUP_DULL = Pattern.compile(
        "(?i)dulls?\\s+(?:it|them)"
    );

    /** Matches "freeze it" or "freeze them". */
    private static final Pattern FOLLOWUP_FREEZE = Pattern.compile(
        "(?i)freeze\\s+(?:it|them)"
    );

    /**
     * Matches "dull it/them and freeze it/them" or compact "dull and freeze it/them"
     * (former/latter effects use a shared pronoun at the end).
     */
    private static final Pattern FOLLOWUP_DULL_AND_FREEZE = Pattern.compile(
        "(?i)(?:dull\\s+(?:it|them)\\s+and\\s+freeze|dull\\s+and\\s+freeze)\\s+(?:it|them)"
    );

    /** Matches "Dull it/them and deal it/them N damage". Group {@code amount} is the damage value. */
    private static final Pattern FOLLOWUP_DULL_AND_DAMAGE = Pattern.compile(
        "(?i)dull\\s+(?:it|them)\\s+and\\s+deal\\s+(?:it|them)\\s+(?<amount>\\d+)\\s+damage"
    );

    /**
     * Matches split-target effects of the form:
     * "[action A] the first [type] [suffix] [sep] [action B] the other"
     * where action B is drawn from a known vocabulary.
     * <ul>
     *   <li>{@code firstpfx}    — verb phrase before "the first [type]"
     *                             (e.g. "Dull", "Remove", "Deal 8000 damage to")</li>
     *   <li>{@code firstsfx}    — optional non-comma text after "the first [type]"
     *                             (e.g. " from the game", " to its owner's hand")</li>
     *   <li>{@code othereffect} — effect for the second chosen target
     *                             (one of: dull and freeze, activate, break, dull, freeze,
     *                              remove from the game, return to its owner's hand)</li>
     * </ul>
     */
    private static final Pattern FOLLOWUP_FIRST_AND_OTHER = Pattern.compile(
        "(?i)(?<firstpfx>.+?)\\s+the\\s+first\\s+(?:Forward|Backup|Character|Monster|one)" +
        "(?<firstsfx>[^,]*?)[,.]?\\s+(?:and\\s+)?" +
        "(?<othereffect>dull\\s+and\\s+freeze|activate|break|dull|freeze" +
        "|remove\\s+from\\s+the\\s+game|return\\s+to\\s+its\\s+owner'?s\\s+hand)" +
        "\\s+the\\s+other\\.?$"
    );

    /** Matches "Break it" or "Break them". */
    private static final Pattern FOLLOWUP_BREAK = Pattern.compile(
        "(?i)Break\\s+(?:it|them)"
    );

    /** Matches "It loses all [its] abilities until the end of the turn." */
    private static final Pattern FOLLOWUP_LOSE_ALL_ABILITIES_EOT = Pattern.compile(
        "(?i)It\\s+loses\\s+all\\s+(?:its\\s+)?abilities\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /** Matches "Remove it/them from the game". */
    private static final Pattern FOLLOWUP_REMOVE_FROM_GAME = Pattern.compile(
        "(?i)Remove\\s+(?:it|them)\\s+from\\s+(?:the\\s+)?game"
    );

    /**
     * Matches the secondary "Then, play the removed Forward onto the field [dull]."
     * Used after a RemoveFromGame primary to play the just-removed card back onto the field.
     * Group {@code dull} — present if the card enters dull.
     */
    private static final Pattern SECONDARY_PLAY_REMOVED_ONTO_FIELD = Pattern.compile(
        "(?i)^(?:Then,?\\s+)?play\\s+the\\s+removed\\s+(?:Forward|Character)" +
        "\\s+onto\\s+(?:the\\s+)?field(?:\\s+(?<dull>dull))?[.!]?\\s*$"
    );

    /**
     * Matches "Remove it/them and [CardName] from the game" — chosen target(s) plus a named card.
     * Group {@code named} — the additional card name to remove.
     */
    private static final Pattern FOLLOWUP_REMOVE_FROM_GAME_AND_NAMED = Pattern.compile(
        "(?i)Remove\\s+(?:it|them)\\s+and\\s+(?<named>.+?)\\s+from\\s+(?:the\\s+)?game[.!]?"
    );

    /**
     * Matches "Your opponent randomly removes N card(s) in his/her/their hand from the game."
     * Group 1 — count.
     */
    private static final Pattern OPPONENT_RANDOM_HAND_RFP = Pattern.compile(
        "(?i)Your\\s+opponent\\s+randomly\\s+removes?\\s+(\\d+)\\s+cards?\\s+in\\s+" +
        "(?:his/her|his|her|their)\\s+hand\\s+from\\s+(?:the\\s+)?game[.!]?"
    );

    /**
     * Matches "Your opponent randomly places N card(s) from their hand at the bottom of their deck."
     * Group 1 — count.
     */
    private static final Pattern OPPONENT_RANDOM_HAND_TO_BOTTOM_DECK = Pattern.compile(
        "(?i)Your\\s+opponent\\s+randomly\\s+places?\\s+(\\d+)\\s+cards?\\s+from\\s+" +
        "(?:his/her|his|her|their)\\s+hand\\s+at\\s+the\\s+bottom\\s+of\\s+(?:his/her|his|her|their)\\s+deck[.!]?"
    );

    /**
     * Matches the style "reveal and select from hand to remove from game":
     * "Your opponent reveals their hand. Select N card(s) in their hand.
     *  Your opponent removes it/them from the game."
     * Group 1 — count of cards to select.
     */
    private static final Pattern REVEAL_SELECT_HAND_RFP = Pattern.compile(
        "(?i)Your\\s+opponent\\s+reveals?\\s+(?:his/her|his|her|their)\\s+hand[.!]\\s+" +
        "Select\\s+(\\d+)\\s+cards?\\s+in\\s+(?:his/her|his|her|their)\\s+hand[.!]\\s+" +
        "Your\\s+opponent\\s+removes?\\s+(?:it|them)\\s+from\\s+(?:the\\s+)?game[.!]?"
    );

    /**
     * Matches "Your opponent reveals their hand. You may select 1 card from their hand.
     * If you do so, remove it from the game and your opponent draws 1 card."
     * (Zidane-style: optional select, you remove it, opponent draws.)
     */
    private static final Pattern REVEAL_HAND_OPT_PICK_RFP_OPP_DRAW = Pattern.compile(
        "(?i)Your\\s+opponent\\s+reveals?\\s+(?:his/her|his|her|their)\\s+hand[.!]\\s+" +
        "You\\s+may\\s+select\\s+1\\s+card\\s+from\\s+(?:his/her|his|her|their)\\s+hand[.!]\\s+" +
        "If\\s+you\\s+do\\s+so,\\s+remove\\s+it\\s+from\\s+(?:the\\s+)?game\\s+" +
        "and\\s+your\\s+opponent\\s+draws\\s+1\\s+card[.!]?"
    );

    /**
     * Matches "Your opponent removes N card(s) in his/her/their hand from the game."
     * (opponent chooses which cards — not random).  Group 1 — count.
     */
    private static final Pattern OPPONENT_HAND_RFP = Pattern.compile(
        "(?i)Your\\s+opponent\\s+removes?\\s+(\\d+)\\s+cards?\\s+in\\s+" +
        "(?:his/her|his|her|their)\\s+hand\\s+from\\s+(?:the\\s+)?game[.!]?"
    );

    /** Matches "Remove all the cards in your opponent's Break Zone from the game." */
    private static final Pattern REMOVE_ALL_OPP_BZ_FROM_GAME = Pattern.compile(
        "(?i)^remove\\s+all\\s+the\\s+cards\\s+in\\s+your\\s+opponent'?s\\s+Break\\s+Zone\\s+from\\s+(?:the\\s+)?game[.!]?\\s*$"
    );

    /**
     * Matches "Remove [CardName] from the game." as a standalone sentence.
     * Group {@code named} — the card name.  Does NOT match "Remove it/them …" (pronouns).
     */
    private static final Pattern REMOVE_NAMED_FROM_GAME = Pattern.compile(
        "(?i)Remove\\s+(?!(?:it|them)\\b)(?<named>.+?)\\s+from\\s+(?:the\\s+)?game[.!]?"
    );

    /** Matches "You may remove [CardName] from the game." — optional self-RFP. */
    private static final Pattern YOU_MAY_REMOVE_NAMED_FROM_GAME = Pattern.compile(
        "(?i)^you\\s+may\\s+remove\\s+(?<name>.+?)\\s+from\\s+(?:the\\s+)?game[.!]?\\s*$"
    );

    /**
     * Matches "You may reveal 1 [Element] card from your hand."
     * Group {@code element} — the required element name.
     */
    private static final Pattern YOU_MAY_REVEAL_ELEMENT_FROM_HAND = Pattern.compile(
        "(?i)^You\\s+may\\s+reveal\\s+1\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
        "\\s+card\\s+from\\s+your\\s+hand[.!]?\\s*$"
    );

    /** Matches "At the end of your opponent's turn, play [CardName] onto the field." */
    private static final Pattern AT_END_OF_OPP_TURN_PLAY_NAMED_ONTO_FIELD = Pattern.compile(
        "(?i)^at\\s+the\\s+end\\s+of\\s+your\\s+opponent'?s\\s+turn,?\\s+play\\s+(?<name>.+?)\\s+onto\\s+the\\s+field[.!]?\\s*$"
    );

    /** Matches "Break [CardName]." — used when the source card breaks itself. */
    private static final Pattern BREAK_SOURCE_CARD = Pattern.compile(
        "(?i)^break\\s+(?<name>.+?)[.!]?$"
    );

    /** Matches "put [CardName] into the Break Zone[.!]?" where CardName is the source card. */
    private static final Pattern PUT_SOURCE_INTO_BREAK_ZONE = Pattern.compile(
        "(?i)^put\\s+(?<name>.+?)\\s+into\\s+the\\s+Break\\s+Zone[.!]?$"
    );

    /**
     * "you may put [CardName] into the Break Zone. When you do so, [effect]"
     * Prompts the player; if they choose to break the source card, the follow-up effect fires.
     * Groups: {@code name} — card name (must equal source); {@code effect} — the conditional effect.
     */
    private static final Pattern YOU_MAY_PUT_SELF_TO_BZ_WHEN_DO_SO = Pattern.compile(
        "(?i)^you\\s+may\\s+put\\s+(?<name>.+?)\\s+into\\s+the\\s+Break\\s+Zone[.!]?\\s+" +
        "When\\s+you\\s+do\\s+so,\\s+(?<effect>.+)$",
        Pattern.DOTALL
    );

    /**
     * Matches "If your opponent doesn't control Forwards, put [CardName] into the Break Zone."
     * Group {@code name} — the card name that goes to the Break Zone (must equal source name).
     */
    private static final Pattern IF_OPP_NO_FORWARDS_PUT_TO_BREAK_ZONE = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+(?:doesn'?t|does\\s+not)\\s+control\\s+Forwards?," +
        "\\s+put\\s+(?<name>.+?)\\s+into\\s+the\\s+Break\\s+Zone[.!]?$"
    );

    /**
     * Matches "If either player doesn't control Forwards, put [CardName] into the Break Zone."
     * Fires if either the controller or their opponent has zero Forwards.
     * Group {@code name} — the card name that goes to the Break Zone (must equal source name).
     */
    private static final Pattern IF_EITHER_PLAYER_NO_FORWARDS_PUT_SOURCE_TO_BZ = Pattern.compile(
        "(?i)^If\\s+either\\s+player\\s+(?:doesn'?t|does\\s+not)\\s+control\\s+Forwards?," +
        "\\s+put\\s+(?<name>.+?)\\s+into\\s+the\\s+Break\\s+Zone[.!]?$"
    );

    /**
     * "If you have received N points of damage, put [CardName] into the Break Zone."
     * Fires when the controlling player's damage zone reaches the threshold.
     * Group {@code points} — the damage count threshold; {@code name} — the card name (must equal source).
     */
    static final Pattern IF_SELF_DAMAGE_POINTS_PUT_TO_BREAK_ZONE = Pattern.compile(
        "(?i)^If\\s+you\\s+have\\s+received\\s+(?<points>\\d+)\\s+points?\\s+of\\s+damage," +
        "\\s+put\\s+(?<name>.+?)\\s+into\\s+the\\s+Break\\s+Zone[.!]?\\s*$"
    );

    /** Matches "break the blocking Forward[.!]?" — fires during "is blocked" triggers. */
    private static final Pattern BREAK_BLOCKING_FORWARD = Pattern.compile(
        "(?i)^break\\s+the\\s+blocking\\s+Forward[.!]?$"
    );

    /** Matches "Break the Forward that blocks [Name][.!]?" — group {@code name}. */
    private static final Pattern BREAK_FORWARD_THAT_BLOCKS_CARD = Pattern.compile(
        "(?i)^Break\\s+the\\s+Forward\\s+that\\s+blocks?\\s+(?<name>[^.!]+?)[.!]?$"
    );

    /**
     * Matches "Choose 1 card with EX Burst in your Damage Zone. You may trigger its EX Burst effect."
     * with an optional trailing parenthetical rules note.
     */
    private static final Pattern CHOOSE_EX_BURST_FROM_DAMAGE_ZONE = Pattern.compile(
        "(?i)choose\\s+1\\s+card\\s+with\\s+EX\\s+Burst\\s+in\\s+your\\s+Damage\\s+Zone[.,]?\\s+" +
        "You\\s+may\\s+trigger\\s+its\\s+EX\\s+Burst\\s+effect[.!]?" +
        "(?:\\s*\\([^)]+\\))?"
    );

    /**
     * Matches the Leviathan/Larsa/Strago Damage-Zone-swap pattern:
     * "Choose 1 card in your Damage Zone. Add it to your hand [and draw 1 card]. [Then,]
     *  Put 1 card from your hand into the Damage Zone (its EX Burst effect will not trigger)."
     * Group {@code draw} — present when the variant draws 1 card between the two halves.
     */
    private static final Pattern DAMAGE_ZONE_SWAP_PATTERN = Pattern.compile(
        "(?i)^choose\\s+1\\s+card\\s+in\\s+your\\s+Damage\\s+Zone\\.\\s+" +
        "Add\\s+it\\s+to\\s+your\\s+hand(?<draw>\\s+and\\s+draw\\s+1\\s+card)?\\.\\s+" +
        "(?:Then,?\\s+)?Put\\s+1\\s+card\\s+from\\s+your\\s+hand\\s+into\\s+the\\s+Damage\\s+Zone" +
        "\\s*\\([^)]*\\)\\.?\\s*$"
    );

    /**
     * Matches "Remove the top [N cards / card] of your deck from the game."
     * Group {@code count} — number of cards (absent means 1).
     */
    private static final Pattern REMOVE_TOP_OF_DECK_FROM_GAME = Pattern.compile(
        "(?i)Remove\\s+the\\s+top\\s+(?:(?<count>\\d+)\\s+cards?|card)\\s+of\\s+your\\s+deck\\s+from\\s+(?:the\\s+)?game\\.?"
    );

    /**
     * Matches the compound followup "Remove the top card of your deck from the game.
     * Deal it/them N damage for each CP required to play/cast the removed card."
     * Group {@code base} — damage per CP.
     */
    private static final Pattern FOLLOWUP_RFP_TOP_DECK_AND_DAMAGE_PER_CP = Pattern.compile(
        "(?i)Remove\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck\\s+from\\s+(?:the\\s+)?game\\.\\s+" +
        "Deal\\s+(?:it|them)\\s+(?<base>\\d+)\\s+damage\\s+for\\s+each\\s+CP\\s+required\\s+to\\s+(?:play|cast)\\s+the\\s+removed\\s+card[.!]?"
    );

    /**
     * Matches the compound followup "Reveal the top N cards of your deck.
     * Deal it/them M damage for each CP required to play/cast the revealed cards.
     * Add all the revealed cards to your hand."
     * Groups: {@code n} — card count, {@code base} — damage per CP.
     */
    private static final Pattern FOLLOWUP_REVEAL_TOP_N_DAMAGE_PER_CP_ADD_ALL_TO_HAND = Pattern.compile(
        "(?i)Reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck\\.\\s+" +
        "Deal\\s+(?:it|them)\\s+(?<base>\\d+)\\s+damage\\s+for\\s+each\\s+CP\\s+required\\s+to\\s+(?:play|cast)\\s+the\\s+revealed\\s+cards?\\.\\s+" +
        "Add\\s+all\\s+(?:the\\s+)?revealed\\s+cards?\\s+to\\s+your\\s+hand[.!]?"
    );

    /**
     * Matches the compound followup "Remove them from the game. If these cards are of the
     * same card type, also draw N card(s)."
     * Group {@code count} — number of cards to draw.
     */
    private static final Pattern FOLLOWUP_RFP_IF_SAME_TYPE_DRAW = Pattern.compile(
        "(?i)Remove\\s+them\\s+from\\s+(?:the\\s+)?game[.!]?\\s+" +
        "If\\s+these\\s+cards?\\s+are\\s+of\\s+the\\s+same\\s+card\\s+type,?\\s+" +
        "(?:also\\s+)?draw\\s+(?<count>\\d+)\\s+cards?[.!]?"
    );

    /**
     * Matches the compound followup "Reveal the top N cards of your deck.
     * For each Job [Job] revealed this way, deal it M damage.
     * Then, place the revealed cards at the bottom of your deck in any order."
     * Groups: {@code n} — card count, {@code job} — job name, {@code dmg} — damage per match.
     */
    private static final Pattern FOLLOWUP_REVEAL_TOP_N_JOB_DEAL_DMG_PLACE_BOTTOM = Pattern.compile(
        "(?i)Reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "For\\s+each\\s+(?:Job\\s+)?(?<job>.+?)\\s+revealed\\s+this\\s+way,?\\s+" +
        "deal\\s+it\\s+(?<dmg>\\d+)\\s+damage[.!]?\\s+" +
        "(?:Then,?\\s+)?[Pp]lace\\s+the\\s+revealed\\s+cards?\\s+at\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?"
    );

    /** Matches "Shuffle your deck." */
    private static final Pattern SHUFFLE_DECK = Pattern.compile(
        "(?i)Shuffle\\s+your\\s+deck\\.?"
    );

    /** Matches "Its auto-ability will not trigger." — suppresses ETF auto-abilities for the played card. */
    private static final Pattern ITS_AUTO_ABILITY_WILL_NOT_TRIGGER = Pattern.compile(
        "(?i)Its\\s+auto-ability\\s+will\\s+not\\s+trigger\\.?"
    );

    /** Matches "Play it onto the field" or "Play them onto the field". */
    private static final Pattern FOLLOWUP_PLAY_ONTO_FIELD = Pattern.compile(
        "(?i)Play\\s+(?:it|them)\\s+onto\\s+(?:the\\s+)?field"
    );

    /** Matches "Play it onto the field dull" or "Play them onto the field dull". */
    private static final Pattern FOLLOWUP_PLAY_ONTO_FIELD_DULL = Pattern.compile(
        "(?i)Play\\s+(?:it|them)\\s+onto\\s+(?:the\\s+)?field\\s+dull[.!]?"
    );

    /**
     * Matches "When it enters the field, if it is [cond], [inner]" — a conditional secondary
     * for Play-onto-field that fires only when the played card satisfies the condition.
     * Group {@code cond} is fed to {@link #parseRevealCondition}; group {@code inner}
     * is parsed as a standalone effect via {@link #parse}.
     */
    private static final Pattern FOLLOWUP_PLAY_ONTO_FIELD_WHEN_ENTERS_CONDITIONAL = Pattern.compile(
        "(?i)^When\\s+it\\s+enters\\s+(?:the\\s+)?field,?\\s+if\\s+it\\s+is\\s+(?<cond>.+?),\\s*(?<inner>.+?)[.!]?$",
        Pattern.DOTALL
    );

    /**
     * Matches "If its cost is equal to or less than the number of Job [job] you control, play it onto the field."
     * Group {@code job} captures the job name (without "Job " prefix).
     */
    private static final Pattern FOLLOWUP_PLAY_IF_COST_LE_JOB_COUNT = Pattern.compile(
        "(?i)If\\s+its\\s+cost\\s+is\\s+equal\\s+to\\s+or\\s+less\\s+than\\s+the\\s+number\\s+of\\s+" +
        "Job\\s+(?<job>.+?)\\s+you\\s+control[,.]\\s+play\\s+it\\s+onto\\s+(?:the\\s+)?field[.!]?"
    );

    /**
     * Matches "If its cost is equal to or less than the number of cards in your hand, return it to its owner's hand."
     * Used by Leviathan (5-139C) EX Burst.
     */
    private static final Pattern FOLLOWUP_RETURN_IF_COST_LE_HAND = Pattern.compile(
        "(?i)If\\s+its\\s+cost\\s+is\\s+equal\\s+to\\s+or\\s+less\\s+than\\s+the\\s+number\\s+of\\s+" +
        "cards?\\s+in\\s+your\\s+hand,?\\s+return\\s+it\\s+to\\s+its\\s+owner'?s?\\s+hand[.!]?"
    );

    /** Matches "Add it to your hand" or "Add them to your hand". */
    private static final Pattern FOLLOWUP_ADD_TO_HAND = Pattern.compile(
        "(?i)Add\\s+(?:it|them)\\s+to\\s+your\\s+hand"
    );

    /**
     * Matches a conditional secondary clause that depends on the card just added to hand:
     * "If (it|the added card) (is|has) [cond], [inner effect]".
     * Group {@code cond} is fed to {@link #parseRevealCondition}; group {@code inner}
     * is parsed as a standalone effect via {@link #parse}.
     */
    private static final Pattern FOLLOWUP_ADD_TO_HAND_CONDITIONAL_SECONDARY = Pattern.compile(
        "(?i)^If\\s+(?:it|the\\s+added\\s+card)\\s+(?:is|has)\\s+(?<cond>[^,]+?)" +
        ",\\s*(?<inner>.+?)[.!]?$",
        Pattern.DOTALL
    );

    /**
     * Matches "it cannot block this turn" or
     * "It gains 'This Forward cannot block.' until the end of the turn."
     */
    private static final Pattern FOLLOWUP_CANNOT_BLOCK = Pattern.compile(
        "(?i)(?:" +
            "(?:it|they)\\s+cannot\\s+block\\s+this\\s+turn" +
        "|" +
            "(?:it|they)\\s+gains?\\s+['\"]This\\s+Forward\\s+cannot\\s+block\\.['\"]" +
            "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn" +
        ")[.!]?"
    );

    /**
     * Matches "It cannot be blocked [by a Forward of cost N or more/less] this turn."
     * Groups: {@code costval} (optional), {@code costcmp} (optional: "more" or "less")
     */
    private static final Pattern FOLLOWUP_CANNOT_BE_BLOCKED = Pattern.compile(
        "(?i)it\\s+cannot\\s+be\\s+blocked" +
        "(?:\\s+by\\s+a\\s+Forward\\s+of\\s+cost\\s+(?<costval>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "\\s+this\\s+turn\\.?"
    );

    /**
     * Matches "It can only be blocked by a Forward of cost equal or inferior to its own this turn."
     */
    private static final Pattern FOLLOWUP_ONLY_BLOCKED_BY_COST_LE_OWN = Pattern.compile(
        "(?i)it\\s+can\\s+only\\s+be\\s+blocked\\s+by\\s+a\\s+Forward\\s+of\\s+cost\\s+" +
        "(?:equal\\s+or\\s+inferior\\s+to|inferior\\s+or\\s+equal\\s+to|equal\\s+to\\s+or\\s+less\\s+than)\\s+" +
        "its\\s+own\\s+this\\s+turn[.!]?"
    );

    /** Matches "All Forwards cannot block this turn." — global block-prevention. */
    private static final Pattern STANDALONE_ALL_FORWARDS_CANNOT_BLOCK = Pattern.compile(
        "(?i)All\\s+Forwards?\\s+cannot\\s+block\\s+this\\s+turn[.!]?"
    );

    /** Matches "All Forwards of cost N or less/more cannot block this turn." */
    private static final Pattern STANDALONE_FORWARDS_OF_COST_CANNOT_BLOCK = Pattern.compile(
        "(?i)All\\s+Forwards?\\s+of\\s+cost\\s+(?<costval>\\d+)\\s+or\\s+(?<cmp>less|more)\\s+cannot\\s+block\\s+this\\s+turn[.!]?"
    );

    /**
     * Matches "At the end of your next turn, if [Name] is on the field, your opponent loses the game."
     */
    private static final Pattern END_OF_NEXT_TURN_IF_CARD_ON_FIELD_OPP_LOSES = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+your\\s+next\\s+turn,?\\s+if\\s+(?<name>.+?)\\s+is\\s+on\\s+the\\s+field,?\\s+" +
        "your\\s+opponent\\s+loses\\s+the\\s+game[.!]?"
    );

    /**
     * Matches "All the Forwards opponent controls lose all abilities until the end of the turn."
     */
    private static final Pattern OPP_FWDS_LOSE_ALL_ABILITIES_EOT = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Forwards?\\s+(?:(?:your\\s+)?opponent\\s+controls?)\\s+" +
        "lose\\s+all\\s+abilities\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "All the Forwards opponent controls lose N power for each CP required to play them
     * until the end of the turn." (Flare Star / Ozma).
     * Group {@code amount} — power lost per CP of cost.
     */
    private static final Pattern OPP_FWDS_LOSE_POWER_PER_PLAY_COST = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Forwards?\\s+(?:(?:your\\s+)?opponent\\s+controls?)\\s+" +
        "lose\\s+(?<amount>\\d+)\\s+power\\s+for\\s+each\\s+CP\\s+required\\s+to\\s+play\\s+them\\s+" +
        "until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "All the Forwards opponent controls cannot block Forwards with a power inferior to their own this turn."
     */
    private static final Pattern OPP_FWDS_CANNOT_BLOCK_INFERIOR_POWER_THIS_TURN = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Forwards?\\s+(?:(?:your\\s+)?opponent\\s+controls?)\\s+" +
        "cannot\\s+block\\s+Forwards?\\s+with\\s+a\\s+power\\s+inferior\\s+to\\s+their\\s+own\\s+this\\s+turn[.!]?"
    );

    /**
     * Matches "Each Forward can only be blocked by a Forward with a cost inferior or equal to
     * its own this turn." — global rule applying to all attackers on both sides.
     */
    private static final Pattern ALL_FWDS_BLOCKED_ONLY_BY_LOWER_COST_THIS_TURN = Pattern.compile(
        "(?i)Each\\s+Forward\\s+can\\s+only\\s+be\\s+blocked\\s+by\\s+a\\s+Forward\\s+with\\s+a\\s+cost\\s+" +
        "inferior\\s+or\\s+equal\\s+to\\s+its\\s+own\\s+this\\s+turn[.!]?"
    );

    /**
     * Matches "During this turn, the power of Forwards opponent controls cannot be increased by Summons or abilities."
     * Action-ability counterpart to the persistent field effect FA_OPP_FORWARD_POWER_BOOST_SUPPRESSED.
     */
    private static final Pattern OPP_FWD_POWER_BOOST_SUPPRESSED_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,?\\s+the\\s+power\\s+of\\s+Forwards?\\s+(?:your\\s+)?opponent\\s+controls?\\s+" +
        "cannot\\s+be\\s+increased\\s+by\\s+Summons?\\s+or\\s+abilit(?:y|ies)[.!]?"
    );

    /** Matches "[CardName] cannot be blocked this turn." — self-referential standalone form. */
    private static final Pattern STANDALONE_SELF_CANNOT_BE_BLOCKED = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+cannot\\s+be\\s+blocked" +
        "(?:\\s+by\\s+a\\s+Forward\\s+of\\s+cost\\s+(?<costval>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "\\s+this\\s+turn[.!]?"
    );

    /**
     * Matches "If the cost paid to play [name] included [element] CP, it cannot be blocked
     * [by a Forward of cost N or more/less] this turn."
     * Groups: {@code element}, optional {@code costval}/{@code costcmp}
     */
    private static final Pattern FOLLOWUP_CANNOT_BE_BLOCKED_IF_ELEMENT_CP = Pattern.compile(
        "(?i)if\\s+the\\s+cost\\s+paid\\s+to\\s+play\\s+.+?\\s+included\\s+" +
        "(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+CP,?\\s+" +
        "it\\s+cannot\\s+be\\s+blocked" +
        "(?:\\s+by\\s+a\\s+Forward\\s+of\\s+cost\\s+(?<costval>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "\\s+this\\s+turn\\.?"
    );

    /** Matches "if possible, it must block this turn" or the gains-until-EOT equivalent. */
    private static final Pattern FOLLOWUP_MUST_BLOCK = Pattern.compile(
        "(?i)(?:" +
            "if\\s+possible[,]?\\s+it\\s+must\\s+block\\s+this\\s+turn" +
            "|it\\s+gains\\s+[\"']If\\s+possible[,]?\\s+this\\s+Forward\\s+must\\s+block\\.?[\"']\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn" +
        ")[.!]?"
    );

    /** Matches "Return it to its owner's hand and draw N card(s)." — group {@code draw} is the count. */
    private static final Pattern FOLLOWUP_RETURN_AND_DRAW = Pattern.compile(
        "(?i)Return\\s+it\\s+to\\s+its\\s+owner's\\s+hand\\s+and\\s+draw\\s+(?<draw>\\d+)\\s+cards?[.!]?"
    );

    /**
     * Matches "Return it and [CardName] to their owners' hand(s)." — chosen target plus a named card.
     * Group {@code named} — the additional card name to return.
     */
    private static final Pattern FOLLOWUP_RETURN_AND_NAMED_TO_OWNERS_HAND = Pattern.compile(
        "(?i)Return\\s+it\\s+and\\s+(?<named>.+?)\\s+to\\s+their\\s+owners?'s?\\s+hands?[.!]?"
    );

    /** Matches "Return it/them to its/their owner's/owners' hand/hands." */
    private static final Pattern FOLLOWUP_RETURN_TO_OWNERS_HAND = Pattern.compile(
        "(?i)Return\\s+(?:it|them)\\s+to\\s+(?:its|their)\\s+owners?'s?\\s+hands?\\.?"
    );

    /** Matches "Return it/them to your hand/hands." */
    private static final Pattern FOLLOWUP_RETURN_TO_YOUR_HAND = Pattern.compile(
        "(?i)Return\\s+(?:it|them)\\s+to\\s+your\\s+hands?\\.?"
    );

    /**
     * Matches "Return all [the] [element] [targets] [control] to their owners' hands."
     * Named groups: {@code element}, {@code targets}, {@code control}.
     */
    private static final Pattern ALL_RETURN_TO_HAND_PATTERN = Pattern.compile(
        "(?i)Return\\s+all\\s+(?:the\\s+)?" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?)?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+to\\s+(?:(?:its|their)\\s+owner(?:'s|s')?\\s+hands?|your\\s+hand)[.!]?"
    );

    /**
     * Matches "Choose any number of [Forwards[/and Monsters]/Backups/Characters]
     * [opponent controls | you control | &lt;none&gt;].
     * [Return them to their owners' hands.]"
     *
     * <p>The control clause and the return sentence are both optional so the pattern covers
     * abbreviated forms (e.g. Zell/Vivi ETF) as well as the full explicit version.
     */
    private static final Pattern CHOOSE_ANY_NUMBER_RETURN_TO_HAND = Pattern.compile(
        "(?i)Choose\\s+any\\s+number\\s+of\\s+" +
        "(?<types>Forwards?(?:\\s+and\\s+Monsters?)?|Monsters?(?:\\s+and\\s+Forwards?)?|Backups?|Characters?)" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "[.!]?(?:\\s*Return\\s+them\\s+to\\s+their\\s+owners?'?s?\\s+hands?[.!]?)?"
    );

    /** Matches "Return [name] to its owner's hand." — named card, not a pronoun. */
    private static final Pattern RETURN_NAMED_TO_OWNERS_HAND = Pattern.compile(
        "(?i)Return\\s+(?!(?:it|them)\\b)(?<named>.+?)\\s+to\\s+its\\s+owner(?:'s|s')?\\s+hand[.!]?"
    );

    /** Matches "Return [name] to your hand." — named card, not a pronoun. */
    private static final Pattern RETURN_NAMED_TO_YOUR_HAND_STANDALONE = Pattern.compile(
        "(?i)Return\\s+(?!(?:it|them)\\b)(?<named>.+?)\\s+to\\s+your\\s+hand[.!]?"
    );

    /** Matches "Add [name] to your hand." — named card, not a pronoun or a count. Used for break-zone-origin abilities. */
    private static final Pattern ADD_NAMED_TO_YOUR_HAND = Pattern.compile(
        "(?i)\\bAdd\\s+(?!(?:it|them|\\d)\\b)(?<named>.+?)\\s+to\\s+your\\s+hand[.!]?"
    );

    /**
     * Matches "Play [name] onto [the] field [dull]" without requiring a "from Break Zone" qualifier.
     * Used for break-zone-origin abilities where the card plays itself from the BZ.
     * The name is limited to 1–3 words to avoid matching non-source cards.
     */
    private static final Pattern PLAY_SOURCE_ONTO_FIELD_PATTERN = Pattern.compile(
        "(?i)\\bPlay\\s+(?<name>\\S+(?:\\s+\\S+){0,2})\\s+onto\\s+(?:the\\s+)?field(?:\\s+(?<dull>dull))?[.!]?"
    );

    /**
     * Matches "If its power has become N or less/more, return [name] to your/its owner's hand."
     * Groups: {@code threshold} — power value; {@code cmp} — "less" or "more";
     * {@code name} — card name; {@code toowner} — non-null when "its owner's hand".
     */
    private static final Pattern CONDITIONAL_POWER_RETURN = Pattern.compile(
        "(?i)If\\s+its?\\s+power\\s+has\\s+become\\s+(?<threshold>\\d+)\\s+or\\s+(?<cmp>less|more),\\s+" +
        "return\\s+(?<name>.+?)\\s+to\\s+(?:(?<toowner>its\\s+owner(?:'s|s')?)\\s+|your\\s+)hand[.!]?"
    );

    /**
     * Matches "Put [CardName] at the bottom of its owner's deck." — self-referential standalone,
     * used when a card sends itself to the bottom of the deck as part of an ability chain.
     * Group: {@code name} — the card name (must equal source.name()).
     */
    private static final Pattern PUT_SOURCE_TO_BOTTOM_OF_DECK = Pattern.compile(
        "(?i)Put\\s+(?<name>.+?)\\s+at\\s+the\\s+bottom\\s+of\\s+its\\s+owner's\\s+deck[.!]?"
    );

    /**
     * Matches "Reveal the top N cards of your deck. Play 1 Card Name X of cost M or less among
     * them onto the field and return the other cards to the bottom of your deck in any order."
     * Groups: {@code n}, {@code cardname}, {@code maxcost}.
     */
    private static final Pattern REVEAL_PLAY_NAMED_MAX_COST_REST_BOTTOM = Pattern.compile(
        "(?i)reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Play\\s+1\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+of\\s+cost\\s+(?<maxcost>\\d+)\\s+or\\s+less\\s+" +
        "among\\s+them\\s+onto\\s+(?:the\\s+)?field\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?"
    );

    /**
     * Matches "Select 1 card type. Turn over one card at a time from the top of your deck until
     * a selected type is revealed. Add it to your hand. Then, shuffle the other cards revealed
     * and return them to the bottom of your deck."
     */
    private static final Pattern FLIP_UNTIL_TYPE_TO_HAND_REST_SHUFFLE_BOTTOM = Pattern.compile(
        "(?i)select\\s+1\\s+card\\s+type[.]?\\s+" +
        "Turn\\s+over\\s+one\\s+card\\s+at\\s+a\\s+time\\s+from\\s+the\\s+top\\s+of\\s+your\\s+deck\\s+" +
        "until\\s+a\\s+selected\\s+type\\s+is\\s+revealed[.]?\\s+" +
        "Add\\s+it\\s+to\\s+your\\s+hand[.]?\\s+" +
        "Then,?\\s+shuffle\\s+the\\s+other\\s+cards?\\s+revealed\\s+and\\s+return\\s+them\\s+to\\s+the\\s+bottom\\s+of\\s+your\\s+deck[.!]?"
    );

    /**
     * Matches "Shuffle your deck. Then, reveal the top N cards of your deck.
     * Play 1 Card Name [name] among them onto the field and return the other cards to the
     * bottom of your deck in any order." — used as the 'when you do so' followup on self-bounce
     * abilities that search for a named card.
     * Groups: {@code n} (reveal count), {@code cardname} (card name to play).
     */
    private static final Pattern SHUFFLE_THEN_REVEAL_PLAY_NAMED_REST_BOTTOM = Pattern.compile(
        "(?i)shuffle\\s+your\\s+deck[.]?\\s+Then,?\\s+" +
        "reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.]?\\s+" +
        "Play\\s+1\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+among\\s+them\\s+onto\\s+(?:the\\s+)?field\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?"
    );

    /**
     * Matches "Reveal the top N cards of your deck. Play up to M [Type] among them onto the field
     * and return the other cards to the bottom of your deck in any order."
     * <ul>
     *   <li>{@code n}    — number of cards to reveal</li>
     *   <li>{@code max}  — maximum cards to play onto the field ("up to M")</li>
     *   <li>{@code type} — card type filter: Forward, Backup, Monster, or Character</li>
     * </ul>
     */
    private static final Pattern REVEAL_PLAY_TYPE_ONTO_FIELD_REST_BOTTOM = Pattern.compile(
        "(?i)reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Play\\s+(?:up\\s+to\\s+)?(?<max>\\d+)\\s+" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?<type>Forward|Backup|Monster|Character)s?\\s+" +
        "among\\s+them\\s+onto\\s+(?:the\\s+)?field\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?$"
    );

    /** Matches "reveal 1 &lt;Element&gt; card from your hand. If you do so, draw N card(s)." */
    private static final Pattern REVEAL_ELEMENT_CARD_FROM_HAND_IF_SO_DRAW = Pattern.compile(
        "(?i)^\\s*reveal\\s+1\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+card\\s+from\\s+your\\s+hand[.]?\\s+" +
        "If\\s+you\\s+do\\s+so,?\\s+draw\\s+(?<draw>\\d+)\\s+cards?[.]?\\s*$"
    );

    private static final Pattern REVEAL_PLAY_ELEMENT_TYPE_COST_ONTO_FIELD_REST_BOTTOM = Pattern.compile(
        "(?i)^\\s*reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Play\\s+(?:up\\s+to\\s+)?(?<max>\\d+)\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<type>Forward|Backup|Monster|Character)s?\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+less\\s+" +
        "among\\s+them\\s+onto\\s+(?:the\\s+)?field[,.]?\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );

    /** Matches "Put it at the top or bottom of its owner's deck." — player chooses placement. Also handles "Your opponent puts it…" */
    private static final Pattern FOLLOWUP_PUT_TOP_OR_BOTTOM_OF_DECK = Pattern.compile(
        "(?i)(?:Your\\s+opponent\\s+puts?\\s+it|Put\\s+it)\\s+at\\s+the\\s+top\\s+or\\s+bottom\\s+of\\s+its\\s+owner's\\s+deck\\.?"
    );

    /** Matches "Put it at the bottom of its owner's deck." Also handles "Your opponent puts it…" */
    private static final Pattern FOLLOWUP_PUT_BOTTOM_OF_DECK = Pattern.compile(
        "(?i)(?:Your\\s+opponent\\s+puts?\\s+it|Put\\s+it)\\s+at\\s+the\\s+bottom\\s+of\\s+its\\s+owner's\\s+deck\\.?"
    );

    /** Matches "Put it on top of its owner's deck." Also handles "Your opponent puts it…" */
    private static final Pattern FOLLOWUP_PUT_TOP_OF_DECK = Pattern.compile(
        "(?i)(?:Your\\s+opponent\\s+puts?\\s+it|Put\\s+it)\\s+on\\s+top\\s+of\\s+its\\s+owner's\\s+deck\\.?"
    );

    /**
     * Matches "If its power is equal to or less/more than [SourceName]'s power, put it on top of
     * its owner's deck." — Wakka-style conditional bounce whose threshold is the source card's power.
     * Groups: {@code sourcename} — name of the card providing the power threshold;
     *         {@code cmp} — "less" or "more".
     */
    private static final Pattern FOLLOWUP_IF_POWER_CMP_SOURCE_PUT_ON_DECK_TOP = Pattern.compile(
        "(?i)If\\s+its?\\s+power\\s+is\\s+equal\\s+to\\s+or\\s+(?<cmp>less|more)\\s+than\\s+" +
        "(?<sourcename>.+?)'s\\s+power[,.]?\\s+put\\s+it\\s+on\\s+top\\s+of\\s+its\\s+owner's\\s+deck[.!]?"
    );

    /**
     * Matches "Put it under the top [N] card(s) of its owner's/your deck."
     * Group {@code numword} — present only when a number word precedes "cards" (currently only "four").
     */
    private static final Pattern FOLLOWUP_PUT_UNDER_TOP_OF_DECK = Pattern.compile(
        "(?i)Put\\s+it\\s+under\\s+the\\s+top\\s+(?<numword>four\\s+)?cards?\\s+of\\s+(?:its\\s+owner's|your)\\s+deck\\.?"
    );

    /** Matches "it cannot attack this turn" or "they cannot attack this turn". */
    private static final Pattern FOLLOWUP_CANNOT_ATTACK = Pattern.compile(
        "(?i)(?:it|they)\\s+cannot\\s+attack\\s+this\\s+turn\\.?"
    );

    /** Matches "it must attack this turn if possible". */
    private static final Pattern FOLLOWUP_MUST_ATTACK = Pattern.compile(
        "(?i)it\\s+must\\s+attack\\s+this\\s+turn\\s+if\\s+possible\\.?"
    );

    /** Matches "it/they cannot attack or block this turn". */
    private static final Pattern FOLLOWUP_CANNOT_ATTACK_OR_BLOCK = Pattern.compile(
        "(?i)(?:it|they)\\s+cannot\\s+attack\\s+or\\s+block\\s+this\\s+turn\\.?"
    );

    /**
     * Matches "it cannot attack or block until the end of your opponent's turn" or
     * "…until the end of the next turn".
     */
    private static final Pattern FOLLOWUP_CANNOT_ATTACK_OR_BLOCK_PERSISTENT = Pattern.compile(
        "(?i)(?:it|they)\\s+cannot\\s+attack\\s+or\\s+block\\s+until\\s+the\\s+end\\s+of\\s+" +
        "(?:your\\s+opponent's|the\\s+next)\\s+turn\\.?"
    );

    /**
     * Standalone "[CardName] cannot attack or block." — permanent self-restriction.
     * {@code cardname} captures the subject name.
     */
    static final Pattern STANDALONE_CANNOT_ATTACK_OR_BLOCK = Pattern.compile(
        "(?i)^(?<cardname>.+?)\\s+cannot\\s+attack\\s+or\\s+block[.!]?\\s*$"
    );

    /**
     * Standalone "[CardName] cannot attack." — permanent attack-only restriction.
     * {@code cardname} captures the subject name.
     */
    static final Pattern STANDALONE_CANNOT_ATTACK = Pattern.compile(
        "(?i)^(?<cardname>.+?)\\s+cannot\\s+attack[.!]?\\s*$"
    );

    /**
     * "If you don't control a Card Name [X] Forward, [CardName] cannot attack or block."
     * {@code required} — the card name that must be controlled; {@code subject} — the card restricted.
     */
    static final Pattern IF_DONT_CONTROL_CARD_NAME_FWD_CANNOT_ATTACK_OR_BLOCK = Pattern.compile(
        "(?i)If\\s+you\\s+don(?:'t|not)\\s+control\\s+(?:a\\s+)?Card\\s+Name\\s+(?<required>\\S+(?:\\s+\\S+)*)\\s+Forward,?\\s+" +
        "(?<subject>\\S+(?:\\s+\\S+)*)\\s+cannot\\s+attack\\s+or\\s+block[.!]?\\s*$"
    );

    /**
     * "If [N] or less [CounterName] Counter(s) are placed on [CardName], [CardName] cannot attack or block."
     * {@code count} — the counter threshold; {@code countername} — counter type; {@code target} — the card checked;
     * {@code subject} — the card restricted.
     */
    static final Pattern IF_COUNTER_LIMIT_CANNOT_ATTACK_OR_BLOCK = Pattern.compile(
        "(?i)If\\s+(?<count>\\d+)\\s+or\\s+less\\s+(?<countername>\\S+)\\s+Counters?\\s+are\\s+placed\\s+on\\s+" +
        "(?<target>\\S+(?:\\s+\\S+)*),?\\s+(?<subject>\\S+(?:\\s+\\S+)*)\\s+cannot\\s+attack\\s+or\\s+block[.!]?\\s*$"
    );

    /**
     * "If your opponent doesn't control any Forwards, [CardName] cannot attack."
     * {@code subject} — the card that cannot attack.
     */
    static final Pattern IF_OPP_NO_FORWARDS_CANNOT_ATTACK = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+(?:doesn'?t|does\\s+not)\\s+control\\s+any\\s+Forwards?," +
        "\\s+(?<subject>.+?)\\s+cannot\\s+attack[.!]?\\s*$"
    );

    /**
     * Matches "At the end of this turn, if you control &lt;cardName&gt;, deal it N damage."
     * Used as a Choose followup that queues conditional damage to fire at the end phase.
     * <ul>
     *   <li>Group {@code cardName} — the card the ability user must control</li>
     *   <li>Group {@code damage}   — fixed damage amount</li>
     * </ul>
     */
    private static final Pattern FOLLOWUP_END_OF_TURN_COND_DAMAGE = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+this\\s+turn,\\s+if\\s+you\\s+control\\s+(?<cardName>.+?),\\s+deal\\s+it\\s+(?<damage>\\d+)\\s+damage\\.?"
    );

    /** Matches "At the end of this turn, &lt;rest&gt;" — any delayed standalone effect. */
    private static final Pattern AT_END_OF_TURN_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+this\\s+turn,\\s+(?<rest>.+)"
    );

    /** Matches "At the end of the turn, break [CardName]." — a self-break rider on "becomes a Forward" abilities. */
    private static final Pattern AT_END_OF_TURN_BREAK_SOURCE = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+(?:the|this)\\s+turn,\\s+break\\s+.+?[.!]?"
    );

    /**
     * Matches "At the end of each of your turns, &lt;inner&gt;" — a recurring end-of-turn
     * field-ability trigger.
     * <ul>
     *   <li>Group {@code inner} — the effect text that fires each end phase</li>
     * </ul>
     */
    static final Pattern AT_END_OF_EACH_TURN_FA_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+each\\s+of\\s+your\\s+turns?\\s*,\\s+(?<inner>.+)"
    );

    /**
     * "At the end of each player's turn, if [CardName] has received N damage or more, draw M card(s)."
     * Fires at the end of every player's turn (both P1 and P2).
     * Groups: {@code cardname} — the card name (must equal source); {@code damage} — minimum accumulated
     * combat damage; {@code draw} — number of cards to draw.
     */
    static final Pattern AT_END_OF_EACH_PLAYERS_TURN_IF_SELF_FWD_DAMAGE_DRAW = Pattern.compile(
        "(?i)^At\\s+the\\s+end\\s+of\\s+each\\s+player'?s\\s+turn,\\s+" +
        "if\\s+(?<cardname>.+?)\\s+has\\s+received\\s+(?<damage>\\d+)\\s+damage\\s+or\\s+more,\\s+" +
        "draw\\s+(?<draw>\\d+)\\s+cards?[.!]?\\s*$"
    );

    /**
     * "If there are N or more cards removed from the game, &lt;effect&gt;"
     * Group {@code count} is the threshold; {@code inner} is the conditional effect text.
     */
    private static final Pattern IF_RFP_COUNT_INNER = Pattern.compile(
        "(?i)^If\\s+there\\s+are\\s+(?<count>\\d+)\\s+or\\s+more\\s+cards?\\s+removed\\s+from\\s+the\\s+game,\\s+(?<inner>.+)",
        Pattern.DOTALL
    );

    /**
     * "At the beginning of your Main Phase 1[ each turn etc.], &lt;effect&gt;"
     * Group {@code inner} captures the effect text after the trigger comma.  Modeled on
     * {@link #AT_END_OF_EACH_TURN_FA_PATTERN} — the inner effect is dispatched through
     * the full {@link #parse} chain so any supported effect can follow the trigger.
     */
    static final Pattern AT_BEGINNING_OF_MAIN_PHASE_1_FA_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+beginning\\s+of\\s+your\\s+Main\\s+Phase\\s+1\\b[^,]*,\\s+(?<inner>.+)"
    );

    /** Same as {@link #AT_BEGINNING_OF_MAIN_PHASE_1_FA_PATTERN} but for Main Phase 2. */
    static final Pattern AT_BEGINNING_OF_MAIN_PHASE_2_FA_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+beginning\\s+of\\s+your\\s+Main\\s+Phase\\s+2\\b[^,]*,\\s+(?<inner>.+)"
    );

    /**
     * "Each turn, at the beginning of Main Phase 1, [inner]" — fires at BOTH players' Main Phase 1 starts.
     * Group {@code inner} — the conditional effect to evaluate.
     */
    static final Pattern AT_BEGINNING_OF_MAIN_PHASE_1_EACH_TURN_FA_PATTERN = Pattern.compile(
        "(?i)Each\\s+turn,?\\s+at\\s+the\\s+beginning\\s+of\\s+Main\\s+Phase\\s+1,\\s+(?<inner>.+)"
    );

    /**
     * "At the end of your opponent's turn, [inner]" — fires at the end of the controlling player's
     * opponent's turn (i.e., whenever the opponent ends their turn).
     * Group {@code inner} — the effect to fire.
     */
    static final Pattern AT_END_OF_OPP_TURN_FA_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+(?:each\\s+of\\s+)?your\\s+opponent'?s\\s+turns?,\\s+(?<inner>.+)"
    );

    /**
     * "Select 1 Element. &lt;CardName&gt; becomes that Element[ (this effect does not end at the
     * end of the turn)]." Group {@code name} is the card whose element changes; the
     * trailing parenthetical, when present, marks this as a permanent override.  Used by
     * {@link #tryParseElementChange}, which also checks {@code source.name()} matches
     * {@code name} so this parser cannot fire on an unrelated card.
     */
    static final Pattern ELEMENT_CHANGE_PATTERN = Pattern.compile(
        "(?i)^\\s*select\\s+1\\s+Element\\.\\s+" +
        "(?<name>[A-Z][A-Za-z''\\-\\s]+?)\\s+becomes\\s+that\\s+Element" +
        "(?:\\s*\\(this\\s+effect\\s+does\\s+not\\s+end\\s+at\\s+the\\s+end\\s+of\\s+the\\s+turn\\))?\\s*\\.?\\s*$"
    );

    /** All eight FFTCG element names, in standard order. */
    static final String[] ELEMENT_NAMES = {"Fire", "Ice", "Wind", "Earth", "Lightning", "Water", "Light", "Dark"};

    /**
     * Matches "The [optional filter] Forwards you control can form a party with [anything]
     * Forwards of any Element this turn." — turn-scoped party-element-wildcard grant.
     * Identical to the field-ability form in {@link CardData#FIELD_PARTY_ANY_ELEMENT_PATTERN}
     * except it requires "this turn" at the end.
     */
    private static final Pattern GRANT_PARTY_ANY_ELEMENT_THIS_TURN = Pattern.compile(
        "(?i)The\\s+" +
        "(?:Job\\s+(?<job>.+?)\\s+|Category\\s+(?<category>\\S+)\\s+|Card\\s+Name\\s+(?<cardname>\\S+)\\s+)?" +
        "Forwards?\\s+you\\s+control\\s+can\\s+form\\s+a\\s+party\\s+with\\s+" +
        "(?:.+?\\s+)?Forwards?\\s+of\\s+any\\s+Element\\s+this\\s+turn\\s*\\.?"
    );

    /**
     * Matches "Name 1 Element[ other than X[ or Y]]. [CardName] becomes the named Element until the end of the turn."
     * — element-only self-becomes with optional exclusion.
     */
    private static final Pattern NAME_ELEMENT_ONLY_SELF_BECOMES = Pattern.compile(
        "(?i)Name\\s+1\\s+Element" +
        "(?:\\s+other\\s+than\\s+(?<exclude>[^.]+))?" +
        "[.!]?\\s+" +
        "(?<name>.+?)\\s+becomes?\\s+the\\s+named\\s+Element" +
        "\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn[.!]?"
    );

    /**
     * Matches "Name 1 Element and 1 Job" / "Name 1 Job and 1 Element" with an optional
     * "other than X[ or Y]" element exclusion, where the source card becomes the named Element
     * and Job until end of turn.
     */
    private static final Pattern NAME_ELEMENT_AND_JOB_SELF_BECOMES = Pattern.compile(
        "(?i)Name\\s+1\\s+(?:Element\\s+and\\s+1\\s+Job|Job\\s+and\\s+1\\s+Element)" +
        "(?:\\s+other\\s+than\\s+(?<exclude>[^.]+))?" +
        "[.!]?\\s+" +
        "(?<name>.+?)\\s+becomes?\\s+the\\s+named\\s+(?:Element\\s+and\\s+Job|Job\\s+and\\s+Element)" +
        "\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn[.!]?"
    );

    /**
     * Matches "Name 1 Job and 1 Element[ other than X[ or Y]]. &lt;CardName&gt; gains named Job and
     * Element. [(This effect does not end at the end of the turn.)]" — a permanent element and job
     * grant (no EOT revert).
     */
    private static final Pattern NAME_JOB_AND_ELEMENT_SELF_GAINS_PERMANENT = Pattern.compile(
        "(?i)Name\\s+1\\s+(?:Job\\s+and\\s+1\\s+Element|Element\\s+and\\s+1\\s+Job)" +
        "(?:\\s+other\\s+than\\s+(?<exclude>[^.]+))?" +
        "[.!]?\\s+" +
        "(?<name>.+?)\\s+gains?\\s+(?:the\\s+)?named\\s+(?:Job\\s+and\\s+Element|Element\\s+and\\s+Job)[.!]?\\s*" +
        "(?:\\(This\\s+effect\\s+does\\s+not\\s+end\\s+at\\s+the\\s+end\\s+of\\s+the\\s+turn\\.?\\))?"
    );

    /**
     * Matches "Name 1 Job or 1 Element. Until the end of the turn, all Forwards you control
     * gain +N power and the named Job or Element."
     */
    private static final Pattern NAME_JOB_OR_ELEMENT_ALL_FORWARDS_BOOST = Pattern.compile(
        "(?i)Name\\s+1\\s+(?:Job\\s+or\\s+1\\s+Element|Element\\s+or\\s+1\\s+Job)[.!]?\\s+" +
        "Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,?\\s+" +
        "all\\s+(?:the\\s+)?Forwards?\\s+you\\s+control\\s+gains?\\s+\\+?(?<amount>\\d+)\\s+[Pp]ower\\s+" +
        "and\\s+(?:the\\s+)?named\\s+(?:Job\\s+or\\s+(?:an?\\s+)?Element|(?:an?\\s+)?Element\\s+or\\s+Job)[.!]?"
    );

    /** Matches the standalone "Name 1 Job" / "Select a Job" ETF effect. */
    private static final Pattern NAME_JOB_STANDALONE = Pattern.compile(
        "(?i)^(?:name\\s+1|select\\s+a)\\s+Job[.!]?$"
    );

    /**
     * Matches "Name 1 Job or Category. Reveal the top N cards of your deck.
     * Add up to M Characters of the named Job or Category among them to your hand
     * and return the other cards to the bottom of your deck in any order."
     */
    private static final Pattern NAME_JOB_OR_CATEGORY_REVEAL_ADD_TO_HAND = Pattern.compile(
        "(?i)Name\\s+1\\s+(?:Job\\s+or\\s+Category|Category\\s+or\\s+Job)[.!]?\\s+" +
        "Reveal\\s+the\\s+top\\s+(?<reveal>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+up\\s+to\\s+(?<maxAdd>\\d+)\\s+Characters?\\s+of\\s+the\\s+named\\s+" +
        "(?:Job\\s+or\\s+Category|Category\\s+or\\s+Job)\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+your\\s+deck\\s+" +
        "in\\s+any\\s+order[.!]?"
    );

    /**
     * Matches "reveal the top N cards of your deck. Add 1 Category X [Type] among them to your hand
     * and return the other cards to the bottom of your deck in any order."
     * Groups: {@code n} (card count), {@code cat} (category identifier, e.g. "MBM").
     */
    private static final Pattern REVEAL_TOP_N_CATEGORY_TO_HAND = Pattern.compile(
        "(?i)^\\s*(?:you\\s+may\\s+)?reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+1\\s+Category\\s+(?<cat>\\S+)(?:\\s+(?:Forward|Backup|Character|Monster|card))?\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );

    /**
     * Matches "reveal the top N cards of your deck. Add 1 Job X [or Card Name Y] [or Job Z ...]
     * among them to your hand and return the other cards to the bottom of your deck in any order."
     * Groups: {@code n} (card count); {@code first}/{@code second} each {@code "Job …"} or
     * {@code "Card Name …"} filter terms. The captured terms are split into a job filter and a
     * card-name filter at parse time.
     */
    /**
     * Matches "Reveal the top N cards of your deck. Add M [Type] among them to your hand
     * and return the other cards to the bottom of your deck in any order."
     * Groups: {@code n} (reveal count), {@code max} (max to add), {@code type} (card type).
     */
    private static final Pattern REVEAL_TOP_N_TYPE_TO_HAND = Pattern.compile(
        "(?i)^\\s*(?:you\\s+may\\s+)?reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+(?<max>\\d+)\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?|Summons?)\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );

    /**
     * Matches "Reveal the top N cards of your deck. Add M [Type] of cost C or less among them
     * to your hand and return the other cards to the bottom of your deck in any order."
     * Groups: {@code n} (reveal count), {@code max} (max to add), {@code type} (card type), {@code cost} (max cost).
     */
    private static final Pattern REVEAL_TOP_N_TYPE_COST_TO_HAND = Pattern.compile(
        "(?i)^\\s*(?:you\\s+may\\s+)?reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+(?<max>\\d+)\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?|Summons?)\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+less\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );

    private static final Pattern REVEAL_TOP_N_JOB_OR_NAME_TO_HAND = Pattern.compile(
        "(?i)^\\s*(?:you\\s+may\\s+)?reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+1\\s+" +
        "(?<first>(?:Job|Card\\s+Name)\\s+.+?)" +
        "(?:\\s+or\\s+(?<second>(?:Job|Card\\s+Name)\\s+.+?))?" +
        "(?:\\s+(?:Forward|Backup|Character|Monster|card))?\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );

    /**
     * Matches "Reveal the top N cards of your deck. Add M [Element] card[s] among them to your
     * hand and return the other cards to the bottom of your deck in any order."
     * Groups: {@code n} (reveal count), {@code max} (max to add), {@code element} (element name).
     */
    private static final Pattern REVEAL_TOP_N_ELEMENT_TO_HAND = Pattern.compile(
        "(?i)^\\s*(?:you\\s+may\\s+)?reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+(?<max>\\d+)\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark|Multi-Element)\\s+" +
        "(?:(?<type>Forwards?|Backups?|Monsters?|Characters?)|cards?)\\s+" +
        "among\\s+them\\s+to\\s+your\\s+hand\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );

    /**
     * Matches "Reveal the top N cards of your deck. Add up to M cards other than Card Name [name]
     * among them to your hand, and put the rest of the cards into the Break Zone."
     * Groups: {@code n}, {@code max}, {@code name}.
     */
    private static final Pattern REVEAL_TOP_N_ADD_UP_TO_EXCLUDING_NAME_REST_BZ = Pattern.compile(
        "(?i)^\\s*reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+up\\s+to\\s+(?<max>\\d+)\\s+cards?\\s+other\\s+than\\s+Card\\s+Name\\s+(?<name>.+?)\\s+" +
        "among\\s+them\\s+to\\s+your\\s+hand,?\\s+" +
        "and\\s+put\\s+the\\s+rest\\s+of\\s+the\\s+cards?\\s+into\\s+the\\s+Break\\s+Zone[.!]?\\s*$"
    );

    /**
     * Matches "Reveal the top N cards of your deck. Add M [Type] among them to your hand or
     * play M [Job] [Type] among them onto the field, and return the other cards to the bottom
     * of your deck in any order."
     * <ul>
     *   <li>{@code n}        — number of cards to reveal</li>
     *   <li>{@code handmax}  — max cards for the add-to-hand branch</li>
     *   <li>{@code handtype} — type filter for the hand branch (Forward/Backup/Monster/Character)</li>
     *   <li>{@code fieldmax} — max cards for the play-onto-field branch</li>
     *   <li>{@code fieldjob} — optional job filter for the field branch (e.g. "Moogle")</li>
     *   <li>{@code fieldtype}— type filter for the field branch</li>
     * </ul>
     */
    private static final Pattern REVEAL_ADD_TYPE_TO_HAND_OR_PLAY_JOB_TYPE_ONTO_FIELD_REST_BOTTOM = Pattern.compile(
        "(?i)^\\s*reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+(?<handmax>\\d+)\\s+(?<handtype>Forward|Backup|Monster|Character)s?\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+" +
        "or\\s+play\\s+(?<fieldmax>\\d+)\\s+" +
        "(?:Job\\s+(?<fieldjob>.+?)(?=\\s+(?:Forward|Backup|Monster|Character)s?\\s+among)\\s+)?" +
        "(?<fieldtype>Forward|Backup|Monster|Character)s?\\s+among\\s+them\\s+onto\\s+(?:the\\s+)?field,?\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );

    // ---- Damage-shield followup patterns (apply to selected "it/them" targets) --------

    /** Matches "During this turn, the next damage dealt to it/him becomes 0 instead." */
    private static final Pattern FOLLOWUP_SHIELD_NEXT_DMG_ZERO = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+(?:it|him)\\s+becomes\\s+0\\s+instead\\.?"
    );

    /** Matches "During this turn, the next damage dealt to you becomes 0 instead." */
    private static final Pattern PLAYER_NEXT_DAMAGE_ZERO = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+you\\s+becomes\\s+0\\s+instead\\.?"
    );

    /** Matches "During this turn, the next damage dealt to it by Summons or abilities is reduced by N instead." */
    private static final Pattern FOLLOWUP_SHIELD_NEXT_ABILITY_DMG_REDUCTION = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+it\\s+by\\s+Summons?\\s+or\\s+abilities\\s+is\\s+reduced\\s+by\\s+(?<reduction>\\d+)\\s+instead\\.?"
    );

    /** Matches "During this turn, the next damage dealt to it is reduced by N instead." or "Reduce the next damage dealt to it this turn by N." */
    private static final Pattern FOLLOWUP_SHIELD_NEXT_DMG_REDUCTION = Pattern.compile(
        "(?i)(?:During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+(?:it|him)\\s+is\\s+reduced\\s+by|Reduce\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+(?:it|him)\\s+this\\s+turn\\s+by)\\s+(?<reduction>\\d+)(?:\\s+instead)?\\.?"
    );

    /** Matches "During this turn, the damage dealt to it is increased by N instead." */
    private static final Pattern FOLLOWUP_DEBUFF_INCOMING_DMG_INCREASE = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+damage\\s+dealt\\s+to\\s+it\\s+is\\s+increased\\s+by\\s+(?<amount>\\d+)\\s+instead\\.?"
    );

    /** Matches "During this turn, the next damage it deals to a Forward becomes 0 instead." */
    private static final Pattern FOLLOWUP_SHIELD_NEXT_OUTGOING_ZERO = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+it\\s+deals\\s+to\\s+a\\s+Forward\\s+becomes\\s+0\\s+instead\\.?"
    );

    /** Matches "If it deals damage to a Forward [opponent controls] this turn, the damage increases by N instead." */
    private static final Pattern FOLLOWUP_OUTGOING_DMG_BOOST_THIS_TURN = Pattern.compile(
        "(?i)If\\s+it\\s+deals\\s+damage\\s+to\\s+a\\s+Forward(?:\\s+opponent\\s+controls?)?\\s+this\\s+turn,?\\s+" +
        "(?:the\\s+damage\\s+increases?|increase\\s+the\\s+damage)\\s+by\\s+(?<amount>\\d+)(?:\\s+instead)?[.!]?"
    );

    /** Matches "During this turn, if it is dealt damage less than its power, the damage becomes 0 instead." */
    private static final Pattern FOLLOWUP_SHIELD_NONLETHAL = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+it\\s+is\\s+dealt\\s+damage\\s+less\\s+than\\s+its\\s+power,\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?"
    );

    /**
     * "It gains 'If this Forward is dealt damage by your opponent's abilities, the damage becomes
     * 0 instead.' until the end of the turn."
     */
    private static final Pattern FOLLOWUP_GAINS_SHIELD_ABILITY_ONLY = Pattern.compile(
        "(?i)(?:it|they)\\s+gains?\\s+['\"]If\\s+this\\s+Forward\\s+is\\s+dealt\\s+damage\\s+by\\s+your\\s+opponent's\\s+abilities,\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    /** Matches "Negate all [the] damage dealt to it/them." — removes all existing damage immediately. */
    private static final Pattern FOLLOWUP_NEGATE_DAMAGE = Pattern.compile(
        "(?i)Negate\\s+all\\s+(?:the\\s+)?damage\\s+dealt\\s+to\\s+(?:it|them)\\.?"
    );

    /**
     * Matches "Activate it/them and negate all [the] damage dealt to it/them."
     * Checked before {@link #FOLLOWUP_ACTIVATE} to prevent the simpler pattern from
     * consuming only the "Activate it" prefix.
     */
    private static final Pattern FOLLOWUP_ACTIVATE_AND_NEGATE_DAMAGE = Pattern.compile(
        "(?i)Activate\\s+(?:it|them)\\s+and\\s+negate\\s+all\\s+(?:the\\s+)?damage\\s+dealt\\s+to\\s+(?:it|them)\\.?"
    );

    // ---- Gain-control followup patterns -----------------------------------------------

    /**
     * "Activate it/them and gain control of it/them until the end of the turn."
     * Checked before {@link #FOLLOWUP_ACTIVATE} and {@link #FOLLOWUP_GAIN_CONTROL_EOT}
     * to avoid partial matches on the "Activate" or plain "gain control" prefixes.
     */
    private static final Pattern FOLLOWUP_ACTIVATE_AND_GAIN_CONTROL_EOT = Pattern.compile(
        "(?i)Activate\\s+(?:it|them)\\s+and\\s+(?:you\\s+)?gain\\s+control\\s+of\\s+(?:it|them)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    /**
     * "gain control of it/them for as long as [card] is on the field."
     * Checked before {@link #FOLLOWUP_GAIN_CONTROL} to avoid the shorter pattern matching first.
     * Group {@code condCard} captures the card name that must remain on the field.
     */
    private static final Pattern FOLLOWUP_GAIN_CONTROL_WHILE_CARD = Pattern.compile(
        "(?i)(?:you\\s+)?gain\\s+control\\s+of\\s+(?:it|them)" +
        "\\s+for\\s+as\\s+long\\s+as\\s+(?<condCard>.+?)\\s+is\\s+on\\s+the\\s+field\\.?"
    );

    /** "gain control of it/them until the end of the turn." */
    private static final Pattern FOLLOWUP_GAIN_CONTROL_EOT = Pattern.compile(
        "(?i)(?:you\\s+)?gain\\s+control\\s+of\\s+(?:it|them)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    /** "you gain control of it/them." — permanent, no duration qualifier. */
    private static final Pattern FOLLOWUP_GAIN_CONTROL = Pattern.compile(
        "(?i)(?:you\\s+)?gain\\s+control\\s+of\\s+(?:it|them)\\.?"
    );

    // ---- Cannot-be-chosen followup patterns -----------------------------------------

    /**
     * "It/They gains 'This Forward/Character cannot be chosen by your opponent's [Summons/abilities].'
     * until the end of the turn."  The grant form is semantically identical to a direct EOT shield.
     * Checked first so the simpler cannot-be-chosen patterns do not match inside the quoted text.
     * Group {@code scope} captures the scope string.
     */
    private static final Pattern FOLLOWUP_GAINS_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)(?:it|they)\\s+gains?\\s+['\"]This\\s+(?:Forward|Character)\\s+cannot\\s+be\\s+chosen" +
        "\\s+by\\s+your\\s+opponent's\\s+(?<scope>Summons?(?:\\s+or\\s+abilities)?|abilities)\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    /**
     * "[Cardname] and it gain '[quote]' until the end of your opponent's turn."
     * Rydia-style: source card and chosen target both receive the quoted ability until opponent's EOT.
     */
    private static final Pattern FOLLOWUP_SELF_AND_TARGET_GAIN_QUOTE_UNTIL_OPP_TURN = Pattern.compile(
        "(?i)\\S.*?\\s+and\\s+it\\s+gains?\\s+['\"].+?['\"]\\s+until\\s+the\\s+end\\s+of\\s+your\\s+opponent.s\\s+turn[.!]?"
    );

    /** "The next time you use its special ability this turn, you can do so without paying [cost]."
     *  Edgar-style: waives the special-ability cost for the chosen target once this turn. */
    private static final Pattern FOLLOWUP_TARGET_NEXT_SPECIAL_FREE = Pattern.compile(
        "(?i)The\\s+next\\s+time\\s+you\\s+use\\s+its\\s+special\\s+ability\\s+this\\s+turn,\\s+" +
        "you\\s+can\\s+do\\s+so\\s+without\\s+paying\\s+.+?[.!]?"
    );

    /** "During this turn, you can cast it at any time you could normally cast it as long as you have
     *  no cards in hand."  Minwu (FFBE)-style: allows instant-casting the chosen BZ card this turn. */
    private static final Pattern FOLLOWUP_CAST_IT_FROM_BZ_ANYTIME_NO_HAND = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+you\\s+can\\s+cast\\s+it\\s+at\\s+any\\s+time\\s+" +
        "you\\s+could\\s+normally\\s+cast\\s+it\\s+as\\s+long\\s+as\\s+you\\s+have\\s+no\\s+cards\\s+in\\s+hand[.!]?"
    );

    /**
     * "It/They cannot be chosen by your opponent's Summons or abilities [this turn]."
     * More specific than the Summons-only and abilities-only forms; checked first.
     */
    private static final Pattern FOLLOWUP_CANNOT_BE_CHOSEN_BOTH = Pattern.compile(
        "(?i)(?:it|they)\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "Summons?\\s+or\\s+abilities\\.?"
    );

    /** "It/They cannot be chosen by your opponent's Summons [this turn]." */
    private static final Pattern FOLLOWUP_CANNOT_BE_CHOSEN_SUMMONS = Pattern.compile(
        "(?i)(?:it|they)\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+Summons?\\.?"
    );

    /** "It/They cannot be chosen by your opponent's abilities [this turn]." */
    private static final Pattern FOLLOWUP_CANNOT_BE_CHOSEN_ABILITIES = Pattern.compile(
        "(?i)(?:it|they)\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+abilities\\.?"
    );

    /** "It gains 'This Character/Forward/Monster cannot be broken.' until the end of the turn." Also matches the leading-Until form: "Until the end of the turn, it gains '...'." */
    private static final Pattern FOLLOWUP_CANNOT_BE_BROKEN = Pattern.compile(
        "(?i)(?:Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,\\s+)?" +
        "(?:it|they)\\s+gains?\\s+['\"]This\\s+(?:Forward|Character|Monster)\\s+cannot\\s+be\\s+broken\\.?['\"]" +
        "(?:\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?)?"
    );

    /** "It cannot be broken this turn." */
    private static final Pattern FOLLOWUP_CANNOT_BE_BROKEN_SIMPLE = Pattern.compile(
        "(?i)(?:it|they)\\s+cannot\\s+be\\s+broken\\s+this\\s+turn\\.?"
    );

    /** "During this turn, it cannot be broken by opposing Summons or abilities that don't deal damage." */
    private static final Pattern FOLLOWUP_CANNOT_BE_BROKEN_BY_NON_DMG = Pattern.compile(
        "(?i)(?:During\\s+this\\s+turn,\\s+)?(?:it|they)\\s+cannot\\s+be\\s+broken\\s+by\\s+" +
        "(?:opposing|your\\s+opponent's)\\s+Summons\\s+or\\s+abilities\\s+that\\s+don'?t\\s+deal\\s+damage\\.?"
    );

    /** Standalone: "[CardName] gains '[...] cannot be broken.' until end of turn." */
    private static final Pattern STANDALONE_SELF_SHIELD_CANNOT_BE_BROKEN = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+gains?\\s+['\"][^'\"]*?cannot\\s+be\\s+broken\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    /** Standalone: "[CardName] cannot be broken this turn." — bare form without 'gains' quoting. */
    private static final Pattern STANDALONE_SELF_SHIELD_CANNOT_BE_BROKEN_SIMPLE = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+cannot\\s+be\\s+broken\\s+this\\s+turn\\.?"
    );

    /**
     * Standalone: "Dull [CardName]." — dulls the source card with no other effect.
     * Must be tried after {@link #STANDALONE_SELF_DULL_AND_SHIELD_CANNOT_BE_BROKEN} so the
     * compound case is not shadowed.
     */
    private static final Pattern STANDALONE_SELF_DULL = Pattern.compile(
        "(?i)^dull\\s+(?<subject>.+?)\\.?\\s*$"
    );

    /**
     * Compound: "Dull [CardName]. [CardName] gains '[...] cannot be broken.' until end of turn."
     * Must be tried before the plain {@link #STANDALONE_SELF_SHIELD_CANNOT_BE_BROKEN} matcher so
     * the dull step is not silently dropped.
     */
    private static final Pattern STANDALONE_SELF_DULL_AND_SHIELD_CANNOT_BE_BROKEN = Pattern.compile(
        "(?i)Dull\\s+(?<subject>.+?)\\.\\s+.+?\\s+gains?\\s+['\"][^'\"]*?cannot\\s+be\\s+broken\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    /** Standalone: "All [the] Forwards you control gain '[...] cannot be broken.' until end of turn." */
    private static final Pattern STANDALONE_ALL_FORWARDS_SHIELD_CANNOT_BE_BROKEN = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Forwards?\\s+you\\s+control\\s+gains?\\s+" +
        "['\"][^'\"]*?cannot\\s+be\\s+broken\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    /**
     * "During this turn, if a Forward you control is dealt damage by a Summon or an ability,
     *  the damage becomes 0 instead."
     */
    private static final Pattern ALL_OWN_FORWARDS_NULLIFY_ABILITY_DAMAGE_PATTERN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,?\\s+if\\s+(?:a\\s+)?Forwards?\\s+you\\s+control\\s+(?:is|are)\\s+dealt\\s+damage" +
        "\\s+by\\s+(?:a\\s+)?Summons?\\s+or\\s+an?\\s+abilit(?:y|ies),?\\s+the\\s+damage\\s+becomes?\\s+0\\s+instead[.!]?"
    );

    /** "It gains 'When this Forward deals battle damage to a Forward, break that Forward.' until the end of the turn." */
    private static final Pattern FOLLOWUP_GAINS_BREAKTOUCH_BATTLE = Pattern.compile(
        "(?i)(?:it|they)\\s+gains?\\s+['\"]When\\s+this\\s+Forward\\s+deals\\s+battle\\s+damage\\s+to\\s+a\\s+Forward,\\s+break\\s+that\\s+Forward\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    // ---- Standalone cannot-be-chosen patterns ---------------------------------------

    /**
     * "Activate all [the] Forwards/Characters you control. They cannot be chosen by
     * [your opponent's] Summons [or abilities] [this turn]."
     * "your opponent's" and "the" are optional; treated as opponent-only either way.
     * Registered before {@link #tryParseAllFieldEffect} to prevent the activate-all part
     * from consuming the text without the cannot-be-chosen clause.
     */
    private static final Pattern STANDALONE_ACTIVATE_AND_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)Activate\\s+all\\s+(?:the\\s+)?(?:Forwards?|Characters?)\\s+you\\s+control\\." +
        "\\s*They\\s+cannot\\s+be\\s+chosen\\s+by\\s+(?:your\\s+opponent's\\s+)?" +
        "(?<scope>Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*(?:this\\s+turn)?\\s*\\.?"
    );

    /**
     * "This Forward/Character cannot be chosen by your opponent's Summons/abilities."
     * Self-referential: applies protection to the {@code source} card itself.
     */
    private static final Pattern STANDALONE_SELF_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)This\\s+(?:Forward|Character)\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "(?<scope>Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );

    /**
     * "[CardName] cannot be chosen by your opponent's Summons/abilities."
     * Only matches when {@code cardName} equals the {@code source} card's name.
     */
    private static final Pattern STANDALONE_NAMED_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)(?<name>[A-Z][A-Za-z''\\-\\s]+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "(?<scope>Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );

    /**
     * "[CardName] cannot be chosen by Summons [during this turn]." — no "your opponent's" qualifier,
     * meaning the protection applies to Summons from either player.
     * Only matches when {@code cardName} equals the {@code source} card's name.
     */
    private static final Pattern STANDALONE_NAMED_CANNOT_BE_CHOSEN_ANY_SUMMON = Pattern.compile(
        "(?i)(?<name>[A-Z][A-Za-z''\\-\\s]+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+(?!your\\s)Summons?" +
        "(?:\\s+during\\s+this\\s+turn)?\\s*\\.?"
    );

    /**
     * "Name 1 Element. During this turn, [CardName] cannot be chosen by Summons or abilities of the named
     * Element and if [CardName] is dealt damage by a Summon or an ability of the named Element, the damage
     * becomes 0 instead." — targeting immunity AND damage nullification for the named element.
     */
    private static final Pattern STANDALONE_NAME_ELEMENT_IMMUNE_AND_NULLIFY_DAMAGE = Pattern.compile(
        "(?i)Name\\s+1\\s+Element\\.\\s+During\\s+this\\s+turn,\\s+" +
        "(?<name>[A-Z][A-Za-z''\\-\\s]+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+Summons?\\s+or\\s+abilities\\s+of\\s+the\\s+named\\s+Element" +
        "\\s+and\\s+if\\s+[A-Za-z''\\-\\s]+?is\\s+dealt\\s+damage\\s+by\\s+a\\s+Summon\\s+or\\s+an\\s+ability\\s+of\\s+the\\s+named\\s+Element,\\s+" +
        "the\\s+damage\\s+becomes\\s+0\\s+instead\\s*\\.?"
    );

    /**
     * "Name 1 Element. [CardName] cannot be chosen by Summons or abilities of the named Element this turn."
     * Action ability: the player names an element, and the card gains immunity to that element this turn.
     */
    private static final Pattern STANDALONE_NAME_ELEMENT_AND_IMMUNE = Pattern.compile(
        "(?i)Name\\s+1\\s+Element\\.\\s+" +
        "(?<name>[A-Z][A-Za-z''\\-\\s]+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+Summons?\\s+or\\s+abilities\\s+of\\s+the\\s+named\\s+Element\\s+this\\s+turn\\s*\\.?"
    );

    /**
     * "[CardName] cannot be chosen by Summons or abilities that share its Element."
     * Passive field ability: immunity is checked dynamically against the resolving card's element.
     */
    private static final Pattern STANDALONE_NAMED_CANNOT_BE_CHOSEN_BY_OWN_ELEMENT = Pattern.compile(
        "(?i)(?<name>[A-Z][A-Za-z''\\-\\s]+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+Summons?\\s+or\\s+abilities\\s+that\\s+share\\s+its\\s+Element\\s*\\.?"
    );

    /**
     * "The Job X [other than Y] Forwards/Characters you control cannot be chosen by
     * your opponent's Summons/abilities."
     * Group {@code job} is the job name; {@code excl} is the optional excluded card name.
     */
    private static final Pattern STANDALONE_JOB_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)The\\s+Job\\s+(?<job>[^.]+?)(?:\\s+other\\s+than\\s+(?<excl>[^.]+?))?" +
        "\\s+(?:Forwards?|Characters?)\\s+you\\s+control\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "(?<scope>Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );

    /**
     * "Players cannot cast Summons." — global static restriction while this card is on the field.
     * Both players are prevented from casting Summons from hand or break zone.
     */
    private static final Pattern PLAYERS_CANNOT_CAST_SUMMONS = Pattern.compile(
        "(?i)^Players?\\s+cannot\\s+cast\\s+Summons?\\.?$"
    );

    /**
     * "All Summons in your Break Zone cannot be removed from the game by your opponent's
     * Summons or abilities." — protects the owner's BZ Summons from the opponent's RFG effects.
     */
    static final Pattern FA_BZ_SUMMONS_PROTECTED_FROM_OPP_RFG = Pattern.compile(
        "(?i)All\\s+Summons?\\s+in\\s+your\\s+Break\\s+Zone\\s+cannot\\s+be\\s+removed\\s+from\\s+the\\s+game\\s+" +
        "by\\s+your\\s+opponent.?s\\s+(?:Summons?\\s+or\\s+)?abilities[.!]?"
    );

    /**
     * "[CardName] cannot become dull by your opponent's Summons or abilities."
     * Permanent self-protection while this card is on the field.
     */
    private static final Pattern STANDALONE_NAMED_CANNOT_BECOME_DULL_OPP = Pattern.compile(
        "(?i)(?<name>[A-Z][A-Za-z''\\-\\s]+?)\\s+cannot\\s+become\\s+dull\\s+by\\s+your\\s+opponent's\\s+" +
        "(?:Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );

    // ---- Standalone damage-shield patterns (apply globally or to a named card) --------

    /** "Negate all [the] damage dealt to all the Forwards/Characters you control." */
    private static final Pattern STANDALONE_NEGATE_DAMAGE_OWN = Pattern.compile(
        "(?i)Negate\\s+all\\s+(?:the\\s+)?damage\\s+dealt\\s+to\\s+all\\s+the\\s+" +
        "(?:Forwards?|Characters?)\\s+you\\s+control\\.?"
    );

    /**
     * "Activate all the Forwards/Characters you control and negate all [the] damage dealt to them."
     * Handled by {@link #tryParseNegateAllDamage} before {@link #tryParseAllFieldEffect}
     * so that the "activate all" part does not consume the full text without the negate clause.
     */
    private static final Pattern STANDALONE_ACTIVATE_AND_NEGATE_DAMAGE_OWN = Pattern.compile(
        "(?i)Activate\\s+all\\s+the\\s+(?:Forwards?|Characters?)\\s+you\\s+control" +
        "\\s+and\\s+negate\\s+all\\s+(?:the\\s+)?damage\\s+dealt\\s+to\\s+them\\.?"
    );

    /** "During this turn, if a Forward you control is dealt damage less than its power, the damage becomes 0 instead." */
    private static final Pattern STANDALONE_NONLETHAL_PROTECTION = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+a\\s+Forward\\s+you\\s+control\\s+is\\s+dealt\\s+damage\\s+less\\s+than\\s+its\\s+power,\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?"
    );

    /** "During this turn, if a Forward you control is dealt damage, reduce the damage by N instead." */
    private static final Pattern STANDALONE_GLOBAL_DMG_REDUCTION = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+a\\s+Forward\\s+you\\s+control\\s+is\\s+dealt\\s+damage,\\s+reduce\\s+the\\s+damage\\s+by\\s+(?<reduction>\\d+)\\s+instead\\.?"
    );

    /**
     * "During this turn, if &lt;cardName&gt; is dealt damage by your opponent's Summons or abilities,
     * the damage becomes 0 instead."
     */
    private static final Pattern STANDALONE_NULLIFY_ABILITY_DAMAGE = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+(?<card>.+?)\\s+is\\s+dealt\\s+damage\\s+by\\s+your\\s+opponent's\\s+Summons?\\s+or\\s+abilities,\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?"
    );

    /**
     * "During this turn, the next damage dealt to [name] becomes 0 instead."
     * "The next damage dealt to Card Name [name] becomes 0 this turn."
     */
    private static final Pattern STANDALONE_SHIELD_NEXT_DMG_ZERO_NAMED = Pattern.compile(
        "(?i)(?:During\\s+this\\s+turn,\\s+)?the\\s+next\\s+damage\\s+dealt\\s+to\\s+(?!(?:it|him|them)\\b)(?:Card\\s+Name\\s+)?(?<name>[A-Za-z][^.]+?)\\s+becomes\\s+0\\s+(?:instead|this\\s+turn)[.!]?"
    );

    /** "During this turn, the next damage dealt to [name] is reduced by N instead." — named card, not pronoun. */
    private static final Pattern STANDALONE_SHIELD_NEXT_DMG_REDUCTION_NAMED = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+(?!(?:it|them)\\b)(?<name>[A-Za-z][^.]+?)\\s+is\\s+reduced\\s+by\\s+(?<reduction>\\d+)\\s+instead[.!]?"
    );

    /** "The damage dealt to Forwards opponent controls cannot be reduced this turn." */
    private static final Pattern STANDALONE_DISABLE_OPPONENT_DMG_REDUCTION = Pattern.compile(
        "(?i)The\\s+damage\\s+dealt\\s+to\\s+Forwards?\\s+(?:your\\s+)?opponent\\s+controls\\s+cannot\\s+be\\s+reduced\\s+this\\s+turn\\.?"
    );

    /** "This damage cannot be reduced." — modifier on a preceding damage sentence. */
    private static final Pattern CANNOT_BE_REDUCED_PATTERN = Pattern.compile(
        "(?i)This\\s+damage\\s+cannot\\s+be\\s+reduced[.!]?"
    );

    /**
     * Matches "Activate &lt;cardName&gt;[.]" as a standalone named-card activate effect.
     * Also handles "Activate Card Name X [and Card Name Y] [you control]" for
     * multi-target Card Name notation.
     * Excludes the pronoun forms ("Activate it/them") and the mass form ("Activate all …"),
     * which are handled separately.
     */
    private static final Pattern ACTIVATE_NAMED_CARD = Pattern.compile(
        "(?i)Activate\\s+(?!(?:it|them|all)\\b)(?<card>[A-Za-z][^.]+?)\\.?\\s*$"
    );

    /** Matches "[name] can attack once more this turn." */
    private static final Pattern ATTACK_ONCE_MORE = Pattern.compile(
        "(?i)^(?<name>[A-Za-z][^.]+?)\\s+can\\s+attack\\s+once\\s+more\\s+this\\s+turn[.!]?"
    );

    /** Matches "During this turn, your opponent may only declare attack once." */
    private static final Pattern OPPONENT_ATTACK_ONCE_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,?\\s+your\\s+opponent\\s+may\\s+only\\s+declare\\s+attack\\s+once\\.?"
    );

    private static final Pattern OPPONENT_CANNOT_SEARCH_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,?\\s+your\\s+opponent\\s+cannot\\s+search\\.?"
    );

    /** Splits "and Card Name" within an activate target list. */
    private static final Pattern ACTIVATE_AND_CARD_NAME_SPLIT = Pattern.compile(
        "(?i)\\s+and\\s+Card\\s+Name\\s+"
    );

    /** Matches "Remove &lt;cardName&gt; from [the] Battle." — Escape-type ability effect. */
    private static final Pattern REMOVE_FROM_BATTLE = Pattern.compile(
        "(?i)Remove\\s+(?<card>.+?)\\s+from\\s+(?:the\\s+)?Battle\\.?\\s*$"
    );

    /**
     * Matches "Your opponent discards N card(s) [from his/her/their hand]".
     * <ul>
     *   <li>Group 1 — number of cards to discard</li>
     * </ul>
     */
    /**
     * Matches "name 1 card type. Then, your opponent discard 1 card.
     * If the discarded card is the named card type, you draw 1 card."
     */
    private static final Pattern NAME_CARD_TYPE_OPP_DISCARD_DRAW_IF_MATCH = Pattern.compile(
        "(?i)name\\s+1\\s+card\\s+type[.!]?\\s+Then,?\\s+your\\s+opponent\\s+discards?\\s+1\\s+card[.!]?\\s+" +
        "If\\s+the\\s+discarded\\s+card\\s+is\\s+the\\s+named\\s+card\\s+type,\\s+you\\s+draw\\s+1\\s+card[.!]?"
    );

    private static final Pattern OPPONENT_DISCARD = Pattern.compile(
        "(?i)Your\\s+opponent\\s+discards?\\s+(\\d+)\\s+cards?" +
        "(?:\\s+from\\s+(?:his/her|his|her|their)\\s+hand)?[.!]?"
    );

    /** Matches "Each player discards N card(s) [from his/her/their hand]". Group {@code count} = N. */
    private static final Pattern EACH_PLAYER_DISCARD = Pattern.compile(
        "(?i)each\\s+player\\s+discards?\\s+(?<count>\\d+)\\s+cards?" +
        "(?:\\s+from\\s+(?:his/her|his|her|their)\\s+hand)?[.!]?"
    );

    /** Matches "Each player draws N card(s)." Group {@code count} = N. */
    private static final Pattern EACH_PLAYER_DRAW = Pattern.compile(
        "(?i)each\\s+player\\s+draws?\\s+(?<count>\\d+)\\s+cards?[.!]?"
    );

    /**
     * Matches "Each player selects N card(s) from their Break Zone and adds it/them to their hand."
     * Group {@code count} = N.
     */
    private static final Pattern EACH_PLAYER_SALVAGE_FROM_BREAK_ZONE = Pattern.compile(
        "(?i)each\\s+player\\s+selects?\\s+(?<count>\\d+)\\s+cards?\\s+from\\s+" +
        "(?:their|his/her|his|her)\\s+Break\\s+Zone\\s+and\\s+adds?\\s+(?:it|them)\\s+to\\s+" +
        "(?:their|his/her|his|her)\\s+hand[.!]?"
    );

    /**
     * Matches "select N [Forward|Backup|Monster|Character] in/from your Break Zone and add it to your hand."
     * Group {@code count} = N; {@code type} = card type word.
     */
    private static final Pattern SELECT_CHARACTER_FROM_BZ_TO_HAND = Pattern.compile(
        "(?i)^select\\s+(?<count>\\d+)\\s+(?<type>Forward|Backup|Monster|Character)s?" +
        "\\s+(?:in|from)\\s+your\\s+Break\\s+Zone\\s+and\\s+add\\s+it\\s+to\\s+your\\s+hand[.!]?$"
    );

    /**
     * Matches "Each player who doesn't control N or more Forwards discards M card(s) [from their hand]."
     * Groups: {@code min} — forward threshold; {@code count} — cards to discard.
     */
    private static final Pattern EACH_PLAYER_WHO_DOESNT_CONTROL_FORWARDS_DISCARD = Pattern.compile(
        "(?i)each\\s+player\\s+who\\s+doesn't\\s+control\\s+(?<min>\\d+)\\s+or\\s+more\\s+Forwards?" +
        "\\s+discards?\\s+(?<count>\\d+)\\s+cards?" +
        "(?:\\s+from\\s+(?:his/her|his|her|their)\\s+hand)?[.!]?"
    );

    /**
     * Matches the compound form "Each player discards N cards. If you control [Card Name (X)] /
     * Card Name X, your opponent discards M more cards [from his/her/their hand]".
     * Groups: {@code count}, {@code bracketname} or {@code plainname}, {@code extra}.
     */
    private static final Pattern EACH_PLAYER_DISCARD_WITH_CONDITIONAL = Pattern.compile(
        "(?i)each\\s+player\\s+discards?\\s+(?<count>\\d+)\\s+cards?" +
        "(?:\\s+from\\s+(?:his/her|his|her|their)\\s+hand)?[.!]?\\s+" +
        "if\\s+you\\s+control\\s+" +
        "(?:\\[Card\\s+Name\\s+\\((?<bracketname>[^)]+)\\)\\]|Card\\s+Name\\s+(?<plainname>\\S+))" +
        ",\\s+your\\s+opponent\\s+discards?\\s+(?<extra>\\d+)\\s+more\\s+cards?" +
        "(?:\\s+from\\s+(?:his/her|his|her|their)\\s+hand)?[.!]?"
    );

    /**
     * Matches "Each player selects 1 Forward they control. Deal them N damage."
     * Group {@code amount} — damage dealt to each selected Forward.
     */
    private static final Pattern EACH_PLAYER_SELECT_FORWARD_DAMAGE = Pattern.compile(
        "(?i)each\\s+player\\s+selects?\\s+1\\s+Forward\\s+they\\s+control[.!]?\\s+" +
        "Deal\\s+them\\s+(?<amount>\\d+)\\s+damage[.!]?"
    );

    /**
     * Matches "Both players select 1 Forward they control and put it into the Break Zone."
     * Used for Famfrit-style EX Burst effects where each side simultaneously sends one Forward to the Break Zone.
     */
    private static final Pattern BOTH_PLAYERS_SELECT_FORWARD_TO_BREAK_ZONE = Pattern.compile(
        "(?i)(?:Both|Each)\\s+players?\\s+selects?\\s+1\\s+Forward\\s+they\\s+control" +
        "\\s+and\\s+puts?\\s+it\\s+into\\s+the\\s+Break\\s+Zone[.!]?"
    );

    /** Matches "select 1 [Forward|Backup|Monster|Character] you control. Put it into the Break Zone." */
    /**
     * Matches "select 1 [type] of cost N or less other than [name] you control. Put it into the Break Zone."
     * Groups: {@code type}, {@code costval}, {@code excludename}.
     */
    private static final Pattern SELECT_1_CHAR_COST_LE_EXCL_TO_BZ = Pattern.compile(
        "(?i)^[Ss]elect\\s+1\\s+(?<type>Forward|Backup|Monster|Character)\\s+of\\s+cost\\s+(?<costval>\\d+)\\s+or\\s+less\\s+" +
        "other\\s+than\\s+(?<excludename>.+?)\\s+you\\s+control[.!]?\\s+Put\\s+it\\s+into\\s+the\\s+Break\\s+Zone[.!]?$"
    );

    private static final Pattern SELECT_1_CHARACTER_YOU_CONTROL_TO_BZ = Pattern.compile(
        "(?i)^[Ss]elect\\s+1\\s+(?<type>Forward|Backup|Monster|Character)\\s+you\\s+control[.!]?\\s+Put\\s+it\\s+into\\s+the\\s+Break\\s+Zone[.!]?$"
    );

    /**
     * Matches "Each player selects up to N Forwards or Monsters he/she/they controls/control
     * (select as many as possible). Put them into the Break Zone."
     * Groups: {@code count} — max per player; {@code targets} — card type(s).
     */
    private static final Pattern EACH_PLAYER_SELECT_UP_TO_N_TO_BREAK_ZONE = Pattern.compile(
        "(?i)Each\\s+player\\s+selects?\\s+up\\s+to\\s+(?<count>\\d+)\\s+" +
        "(?<targets>Forwards?(?:\\s+(?:and/or|or)\\s+(?:Monsters?|Backups?))?|Monsters?|Characters?)\\s+" +
        "(?:he/she|they)\\s+controls?\\s*" +
        "(?:\\(select\\s+as\\s+many\\s+as\\s+possible\\)[.!]?\\s*)?" +
        "Put\\s+them\\s+into\\s+the\\s+Break\\s+Zone[.!]?"
    );

    /**
     * Matches "Each player reveals the top card of his/her deck. Each player who revealed a
     * [type] may play it onto the field." Group {@code type} = card type condition.
     */
    private static final Pattern EACH_PLAYER_REVEAL_CHARACTER_MAY_PLAY = Pattern.compile(
        "(?i)^\\s*Each\\s+player\\s+reveals?\\s+the\\s+top\\s+card\\s+of\\s+" +
        "(?:his/her|his|her|their)\\s+deck[.!]?\\s+" +
        "Each\\s+player\\s+who\\s+revealed\\s+(?:a\\s+)?(?<type>Forward|Backup|Character|Monster)\\s+" +
        "may\\s+play\\s+it\\s+onto\\s+the\\s+field[.!]?\\s*$"
    );

    /**
     * Matches "each player may search for N Forward(s) of power X or more and add it/them to his/her hand."
     * Groups: {@code count}, {@code power}.
     */
    private static final Pattern EACH_PLAYER_MAY_SEARCH_FORWARD_MIN_POWER = Pattern.compile(
        "(?i)^\\s*each\\s+player\\s+may\\s+search\\s+for\\s+(?<count>\\d+)\\s+Forwards?\\s+" +
        "of\\s+power\\s+(?<power>\\d+)\\s+or\\s+more\\s+and\\s+add\\s+it(?:/them|s)?\\s+to\\s+" +
        "(?:his/her|his|her|their)\\s+hand[.!]?\\s*$"
    );

    /** Matches "Discard your hand. Then, draw N card(s)." Group 1 = draw count. */
    private static final Pattern DISCARD_HAND_THEN_DRAW = Pattern.compile(
        "(?i)Discard\\s+your\\s+hand[.,]?\\s+[Tt]hen[,]?\\s+draw\\s+(\\d+)\\s+cards?[.!]?\\s*$"
    );

    /** Matches "Discard your hand." as a standalone effect. */
    private static final Pattern DISCARD_HAND = Pattern.compile(
        "(?i)Discard\\s+your\\s+hand[.!]?\\s*$"
    );

    /**
     * Matches "discard 1 &lt;Type&gt;." — player discards one card of the named type from hand.
     * Used as the primary clause in "discard 1 X. When you do so, Y." sequences.
     * The "you may" qualifier is stripped by the AutoAbility parser before this is reached.
     */
    private static final Pattern DISCARD_TYPE = Pattern.compile(
        "(?i)discard\\s+1\\s+(?<type>Summon|Forward|Backup|Monster|Character)[.!]?"
    );

    /** Matches "Discard 1 Job [X] from your hand[.]" — player discards a card with the named job. */
    private static final Pattern DISCARD_JOB_FROM_HAND = Pattern.compile(
        "(?i)^discard\\s+1\\s+Job\\s+(?<job>.+?)\\s+from\\s+your\\s+hand[.!]?$"
    );

    /** Matches "You may discard 1 &lt;element&gt; card" — player may optionally discard a card matching the element. */
    private static final Pattern DISCARD_ELEMENT_FROM_HAND = Pattern.compile(
        "(?i)^(?:you\\s+may\\s+)?discard\\s+1\\s+(?<element>Multi-Element|Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+card(?:\\s+from\\s+your\\s+hand)?[.!]?$"
    );

    /** Matches "Your opponent randomly discards N card(s) [from his/her/their hand]". Group 1 = count. */
    private static final Pattern OPPONENT_RANDOM_DISCARD = Pattern.compile(
        "(?i)Your\\s+opponent\\s+randomly\\s+discards?\\s+(\\d+)\\s+cards?" +
        "(?:\\s+from\\s+(?:his/her|his|her|their)\\s+hand)?[.!]?"
    );

    /**
     * Matches "Your opponent draws N card(s), then randomly discards M card(s)".
     * Group 1 = draw count, Group 2 = discard count.
     */
    private static final Pattern OPPONENT_DRAW_THEN_RANDOM_DISCARD = Pattern.compile(
        "(?i)Your\\s+opponent\\s+draws?\\s+(\\d+)\\s+cards?[,.]?\\s+then\\s+randomly\\s+discards?\\s+(\\d+)\\s+cards?[.!]?"
    );

    /** Matches "Your opponent draws N card(s)." — simple opponent draw with no followup. */
    private static final Pattern OPPONENT_DRAW = Pattern.compile(
        "(?i)Your\\s+opponent\\s+draws?\\s+(\\d+)\\s+cards?[.!]?$"
    );

    /**
     * Matches "Your opponent selects N [condition] [element] [type] they control [sep] followup".
     * <ul>
     *   <li>Group {@code count}     — number of cards the opponent must select</li>
     *   <li>Group {@code condition} — optional state filter</li>
     *   <li>Group {@code element}   — optional element filter</li>
     *   <li>Group {@code targets}   — card type(s)</li>
     *   <li>Group {@code followup}  — action applied to the selected card(s)</li>
     * </ul>
     */
    private static final Pattern OPPONENT_SELECTS_PATTERN = Pattern.compile(
        "(?i)^Your\\s+opponent\\s+selects?\\s+(?<count>\\d+)\\s+" +
        "(?:(?<condition>dull|damaged|attacking|blocking|active)\\s+)?" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<targets>Forwards?|Backups?|Characters?)" +
        "\\s+(?:they|he/she|he|she)\\s+controls?" +
        "(?:[.]\\s*|\\s+and\\s+)" +
        "(?<followup>.+)",
        Pattern.DOTALL
    );

    /**
     * Matches both variants of the "opponent puts attacking Forward to Break Zone" effect:
     * <ul>
     *   <li>"Opponent puts 1 attacking Forward into the Break Zone."</li>
     *   <li>"Your opponent puts 1 attacking Forward he/she controls into the Break Zone."</li>
     * </ul>
     * The second variant is the precise reprint; both resolve identically — the opponent
     * chooses one of their own matching Forwards and sends it to the Break Zone.
     */
    private static final Pattern OPPONENT_PUTS_FORWARD_TO_BREAK_ZONE_PATTERN = Pattern.compile(
        "(?i)(?:Your\\s+)?[Oo]pponent\\s+puts?\\s+(?<count>\\d+)\\s+" +
        "(?:(?<condition>dull|damaged|attacking|blocking|active)\\s+)?" +
        "(?<targets>Forwards?|Characters?)" +
        "(?:\\s+(?:he|she|they)(?:\\s*/\\s*(?:he|she|they))?\\s+controls?)?" +
        "\\s+into\\s+the\\s+Break\\s+Zone[.]?"
    );

    /**
     * Matches the compound EX Burst effect:
     * "Choose up to 1 Forward from your Break Zone of cost equal to or less than the damage you
     *  have been dealt. Return it to your hand. Your opponent selects 1 Forward of cost equal to
     *  or less than the damage you have been dealt and puts it into the Break Zone."
     */
    private static final Pattern BZ_FWD_TO_HAND_OPP_FWD_TO_BZ_BY_DAMAGE = Pattern.compile(
        "(?i)Choose\\s+up\\s+to\\s+1\\s+Forward\\s+from\\s+your\\s+Break\\s+Zone\\s+of\\s+cost\\s+" +
        "equal\\s+to\\s+or\\s+less\\s+than\\s+the\\s+damage\\s+you\\s+have\\s+been\\s+dealt\\.\\s*" +
        "Return\\s+it\\s+to\\s+your\\s+hand\\.\\s*" +
        "Your\\s+opponent\\s+selects?\\s+1\\s+Forward\\s+of\\s+cost\\s+equal\\s+to\\s+or\\s+less\\s+than\\s+" +
        "the\\s+damage\\s+you\\s+have\\s+been\\s+dealt\\s+and\\s+puts?\\s+it\\s+into\\s+the\\s+Break\\s+Zone\\.?"
    );

    /**
     * Matches "Your opponent puts the top N card(s) of his/her/their deck into the Break Zone
     * [. Draw M card(s)]".
     * <ul>
     *   <li>Group {@code count} — number of cards to mill; absent means 1 ("the top card")</li>
     *   <li>Group {@code draw}  — optional number of cards to draw afterward</li>
     * </ul>
     */
    private static final Pattern OPPONENT_MILL_PATTERN = Pattern.compile(
        "(?i)Your\\s+opponent\\s+puts?\\s+" +
        "(?:the\\s+top\\s+(?:(?<count>\\d+)\\s+cards?|card)\\s+of" +
        "|(?<count2>\\d+)\\s+cards?\\s+from\\s+the\\s+top\\s+of)\\s+" +
        "(?:his/her|his|her|their)\\s+deck\\s+into\\s+the\\s+Break\\s+Zone" +
        "(?:[.!]?\\s*(?:You\\s+)?[Dd]raw\\s+(?<draw>\\d+)\\s+cards?[.!]?)?"
    );

    private static final Pattern DIVIDE_DAMAGE_PATTERN = Pattern.compile(
            "(?i)Divide\\s+(?<amount>\\d+)\\s+damage\\b(?:.*?\\b(?<mode>equally)\\b)?"
      
    /**
     * Matches "Your opponent puts the top N cards of his/her deck into the Break Zone.
     * If both [all] cards are of the same Element, draw M card(s)."
     * Groups: {@code count}, {@code draw}.
     */
    private static final Pattern OPPONENT_MILL_IF_SAME_ELEMENT_DRAW = Pattern.compile(
        "(?i)Your\\s+opponent\\s+puts?\\s+" +
        "(?:the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of|(?<count2>\\d+)\\s+cards?\\s+from\\s+the\\s+top\\s+of)\\s+" +
        "(?:his/her|his|her|their)\\s+deck\\s+into\\s+the\\s+Break\\s+Zone[.!]?\\s+" +
        "If\\s+(?:both|all)\\s+(?:the\\s+)?cards?\\s+are\\s+of\\s+the\\s+same\\s+Element,?\\s+" +
        "draw\\s+(?<draw>\\d+)\\s+cards?[.!]?"
    );

    private static final Pattern SELF_MILL_PATTERN = Pattern.compile(
        "(?i)Put\\s+the\\s+top\\s+(?:(?<count>\\d+)\\s+cards?|card)\\s+" +
        "of\\s+your\\s+deck\\s+into\\s+the\\s+Break\\s+Zone"
    );

    /**
     * Matches "Play 1 [elements] [filter] [type] of cost … from your hand onto the field [dull]".
     * <ul>
     *   <li>{@code preelems}   — element(s) appearing BEFORE the job/name filter (e.g. "Ice" in "Play 1 Ice Forward")</li>
     *   <li>Filter alternatives (all optional): {@code f1}/{@code f2} bracket filters,
     *       {@code cardname} written card name, {@code category}, {@code jobnm}</li>
     *   <li>{@code targets}    — card type (optional when {@code cardname} is set)</li>
     *   <li>Cost alternatives (all optional):
     *       {@code dynfilter} — "equal to or less than the number of X you control";
     *       {@code cost}/{@code costalt} — numeric cost with optional "less", "more", or a second value</li>
     *   <li>{@code excludename} — card name to exclude ("other than Card Name X")</li>
     *   <li>{@code dull}      — present when the card enters the field dulled</li>
     * </ul>
     */

    /**
     * "Cast 1 Summon [of cost N or less] from your hand without paying [its|the] cost[.
     * Then, return that Summon to your hand after use instead of putting it in the Break Zone.]"
     * Groups: {@code cost} — numeric cost cap or "X"; {@code returnToHand} — present for the
     * "return to hand after use" variant.
     */
    private static final Pattern CAST_SUMMON_FROM_HAND_FREE = Pattern.compile(
        "(?i)Cast\\s+1\\s+Summon" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+|X)\\s+or\\s+less)?" +
        "(?:\\s+other\\s+than\\s+(?<excludeelems>(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*))?" +
        "\\s+from\\s+your\\s+hand\\s+without\\s+paying\\s+(?:its|the)\\s+cost[.!]?" +
        "(?<returnToHand>\\s*Then,?\\s+return\\s+that\\s+Summon\\s+to\\s+your\\s+hand\\s+after\\s+use" +
        "\\s+instead\\s+of\\s+putting\\s+it\\s+in\\s+the\\s+Break\\s+Zone[.!]?)?"
    );

    /**
     * "Randomly reveal 1 card from your hand. If it is a Summon, you may cast it without paying the cost."
     */
    private static final Pattern RANDOM_REVEAL_HAND_CAST_IF_SUMMON_FREE = Pattern.compile(
        "(?i)Randomly\\s+reveal\\s+1\\s+card\\s+from\\s+your\\s+hand[.!]?\\s+" +
        "If\\s+it\\s+is\\s+a\\s+Summon,?\\s+you\\s+may\\s+cast\\s+it\\s+without\\s+paying\\s+(?:its|the)\\s+cost[.!]?"
    );

    /**
     * "Cast a Summon from your hand. The cost required to cast it is reduced by N (it cannot become 0)."
     * Group {@code amount} — the reduction amount.
     */
    private static final Pattern CAST_SUMMON_FROM_HAND_DISCOUNTED = Pattern.compile(
        "(?i)Cast\\s+a\\s+Summon\\s+from\\s+your\\s+hand[.!]?\\s+" +
        "The\\s+cost\\s+required\\s+to\\s+cast\\s+it\\s+is\\s+reduced\\s+by\\s+(?<amount>\\d+)" +
        "(?:\\s*\\(it\\s+cannot\\s+become\\s+0\\))?[.!]?"
    );

    /**
     * "Search for 1 [Element] Summon [of cost N or less] and cast it without paying [its|the] cost.
     * If you do not cast it, put the Summon into the Break Zone."
     * Groups: {@code element} — element name; {@code cost} — optional numeric cost cap.
     */
    private static final Pattern SEARCH_AND_CAST_SUMMON_FREE_PATTERN = Pattern.compile(
        "(?i)search\\s+for\\s+1\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Summon" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+less)?" +
        "\\s+and\\s+cast\\s+it\\s+without\\s+paying\\s+(?:its|the)\\s+cost[.!]?" +
        "(?:\\s+If\\s+you\\s+do\\s+not\\s+cast\\s+it,\\s+put\\s+the\\s+Summon\\s+into\\s+the\\s+Break\\s+Zone[.!]?)?"
    );

    private static final Pattern PLAY_FROM_HAND_PATTERN = Pattern.compile(
        "(?i)Play\\s+1\\s+" +
        // Element(s) before any filter (e.g. "Ice" in "Play 1 Ice Forward")
        "(?:(?<preelems>(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*)\\s+)?" +
        "(?:" +
            // Bracket filter(s): [Job (x)] and/or [Card Name (x)]
            "(?<f1>\\[(?:Job|Card\\s+Name)\\s+\\([^)]+\\)\\])" +
            "(?:\\s+or\\s+(?<f2>\\[(?:Job|Card\\s+Name)\\s+\\([^)]+\\)\\]))?" +
            "\\s+" +
        "|" +
            // Written card name — stops at cost or "from your"
            "Card\\s+Name\\s+(?<cardname>.+?)\\s+(?=of\\s+cost|from\\s+your|[.!])" +
        "|" +
            // Category filter: lookahead keeps the type in the targets group
            "Category\\s+(?<category>.+?)\\s+(?=Forwards?|Backups?|Monsters?|Characters?)" +
        "|" +
            // Written job OR card name: "Job X or Card Name Y" (no explicit type required)
            "Job\\s+(?<jobnmor>.+?)\\s+or\\s+Card\\s+Name\\s+(?<cnameor>\\S+(?:\\s+\\([^)]+\\))?)" +
        "|" +
            // Written job: lookahead keeps the type in the targets group
            "Job\\s+(?<jobnm>.+?)\\s+(?=Forwards?|Backups?|Monsters?|Characters?)" +
        "|" +
            // Written job with no explicit type (e.g. "Job Archfiend from your hand") — any character type
            "Job\\s+(?<jobnmonly>.+?)\\s+(?=of\\s+cost|from\\s+your|other\\s+than)" +
        ")?" +
        // Type is optional when a card-name filter is present
        "(?<targets>Forwards?|Backups?|Monsters?|Characters?(?:\\s+Cards?)?)?" +
        "\\s*" +
        // Element exclusion: "of any Element except Ice [and Water] [and ]"
        "(?:of\\s+any\\s+Element\\s+except\\s+(?<excludeelem>" +
            "(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+and\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*)\\s+(?:and\\s+)?)?" +
        "(?:" +
            // Dynamic cost: "of cost equal to or less than the number of X you control"
            "of\\s+cost\\s+equal\\s+to\\s+or\\s+less\\s+than\\s+the\\s+number\\s+of\\s+" +
            "(?<dynfilter>.+?)\\s+you\\s+control" +
        "|" +
            // Standard / two-value: "of cost N [or less|more|M]"
            "of\\s+cost\\s+(?<cost>\\d+|X)(?:\\s+or\\s+(?<costalt>less|more|\\d+))?" +
        ")?" +
        "\\s*" +
        // Exclusion
        "(?:other\\s+than\\s+Card\\s+Name\\s+(?<excludename>\\S+(?:\\s+\\([^)]+\\))?)\\s+)?" +
        "from\\s+your\\s+hand\\s+onto\\s+(?:the\\s+)?field" +
        // Dull modifier
        "(?:\\s+(?<dull>dull))?" +
        "[.!]?"
    );

    /** Matches "play any number of [Job X] [type] from your hand onto [the] field". */
    private static final Pattern PLAY_ANY_NUMBER_FROM_HAND_PATTERN = Pattern.compile(
        "(?i)(?:Then,?\\s+)?(?:you\\s+may\\s+)?[Pp]lay\\s+any\\s+number\\s+of\\s+" +
        "(?:Job\\s+(?<jobnm>.+?)\\s+)?" +
        "(?<targets>Forwards?|Backups?|Monsters?|Characters?)?" +
        "\\s*from\\s+your\\s+hand\\s+onto\\s+(?:the\\s+)?field[.!]?"
    );

    /**
     * Matches "Search for [up to] 1 [elements] [filter] [elements] [type] [other than Card Name X] [of cost N [or less|more]] and [destination]".
     * <ul>
     *   <li>Group {@code bracketname} — {@code [Card Name (name)]} bracket notation (older cards)</li>
     *   <li>Group {@code bracketjob}  — {@code [Job (name)]} bracket notation</li>
     *   <li>Group {@code cardname}    — written card name without brackets, e.g. {@code "Cait Sith"}</li>
     *   <li>Group {@code category}   — category substring, e.g. {@code "XV"}</li>
     *   <li>Group {@code jobnmor}    — job part of {@code "Job X or Card Name Y"} (OR logic with {@code cnameor})</li>
     *   <li>Group {@code cnameor}    — card name part of {@code "Job X or Card Name Y"}</li>
     *   <li>Group {@code jobnm}      — written job name without brackets, e.g. {@code "King"}</li>
     *   <li>Group {@code preelems}   — element(s) appearing BEFORE the job/name filter, e.g. {@code "Fire"} in {@code "Search for 1 Fire Job Knight"}</li>
     *   <li>Group {@code elements}   — element(s) appearing AFTER the job/name filter; {@code preelems} takes priority when both could apply</li>
     *   <li>Group {@code targets}    — card type word; absent or {@code "card"} means any type</li>
     *   <li>Group {@code excludename}— card name to exclude, from {@code "other than Card Name X"}</li>
     *   <li>Group {@code cost}       — optional cost number</li>
     *   <li>Group {@code costcmp}    — optional {@code "less"} or {@code "more"}</li>
     *   <li>Group {@code destination}— full destination phrase</li>
     * </ul>
     */
    /**
     * Matches "Search for up to 1 Job [job] and up to 1 [Type] that don't share Elements, and add them to your hand."
     * Used by cards like Rydia that fetch one card from each of two overlapping pools with an element-disjointness constraint.
     */
    private static final Pattern DUAL_SEARCH_JOB_AND_TYPE_DONT_SHARE_ELEMENTS = Pattern.compile(
        "(?i)search\\s+for\\s+up\\s+to\\s+1\\s+Job\\s+(?<job>.+?)(?=\\s+and\\s+up\\s+to\\b)" +
        "\\s+and\\s+up\\s+to\\s+1\\s+(?<type>Summon|Forward|Backup|Monster|Character)" +
        "\\s+that\\s+don.t\\s+share\\s+[Ee]lements,?\\s+and\\s+add\\s+them\\s+to\\s+your\\s+hand[.!]?"
    );

    /**
     * Matches "Search for 2 [Element] Characters, 2 Category [X] Characters, or 1 of each,
     * each with a different cost, and add them to your hand."
     * Groups: {@code element}, {@code category}.
     */
    private static final Pattern SEARCH_ELEMENT_OR_CATEGORY_CHARS_DIFF_COST = Pattern.compile(
        "(?i)Search\\s+for\\s+2\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Characters?,\\s+" +
        "2\\s+Category\\s+(?<category>\\S+)\\s+Characters?,\\s+or\\s+1\\s+of\\s+each,\\s+" +
        "each\\s+with\\s+a\\s+different\\s+cost,?\\s+and\\s+add\\s+them\\s+to\\s+your\\s+hand[.!]?"
    );

    /**
     * Matches "Search for N [Element] Summons each with a different cost and add them to your hand."
     * Groups: {@code count}, {@code element}.
     */
    private static final Pattern SEARCH_N_ELEM_SUMMONS_DIFF_COST = Pattern.compile(
        "(?i)Search\\s+for\\s+(?<count>\\d+)\\s+" +
        "(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Summons?" +
        "\\s+each\\s+with\\s+a\\s+different\\s+cost\\s+and\\s+add\\s+them\\s+to\\s+your\\s+hand[.!]?"
    );

    private static final Pattern SEARCH_DECK_PATTERN = Pattern.compile(
        "(?i)Search\\s+for\\s+(?:up\\s+to\\s+)?(?<count>\\d+)\\s+" +
        // Element(s) that precede the job/name filter (e.g. "Fire Job Knight")
        "(?:(?<preelems>(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*)\\s+)?" +
        "(?:" +
            // Bracket card name: [Card Name (name)]
            "(?<bracketname>\\[Card\\s+Name\\s+\\([^)]+\\)\\])\\s+" +
        "|" +
            // Bracket job: [Job (name)]
            "(?<bracketjob>\\[Job\\s+\\([^)]+\\)\\])\\s+" +
        "|" +
            // "Card Name X [Type] or Job Y" — OR logic; must come before plain Card Name alternative
            "Card\\s+Name\\s+(?<cnamejobnmor>.+?)" +
            "(?:\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card))?" +
            "\\s+(?:and/)?or\\s+Job\\s+(?<jobnmcnameor>.+?)" +
            "(?=\\s+of\\s+cost|\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card)\\b|\\s+and\\b)\\s*" +
        "|" +
            // Written card name without brackets — ends at type word, "of cost", or "and"
            "Card\\s+Name\\s+(?<cardname>.+?)" +
            "(?=\\s+of\\s+cost|\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card)\\b|\\s+and\\b)" +
            "\\s+" +
        "|" +
            // Category filter — lookahead keeps the type word in the targets group
            "Category\\s+(?<category>.+?)\\s+" +
            "(?=Forwards?|Backups?|Monsters?|Summons?|Characters?|card\\b)" +
        "|" +
            // "Job X [Type] or Card Name Y" — OR logic; must come before plain Job alternative
            "Job\\s+(?<jobnmor>.+?)" +
            "(?:\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card))?" +
            "\\s+(?:and/)?or\\s+Card\\s+Name\\s+(?<cnameor>.+?)" +
            "(?=\\s+of\\s+cost|\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card)\\b|\\s+and\\b)\\s*" +
        "|" +
            // Written job — lookahead keeps element, type word, "of cost", "other than", Category, or "and" ahead
            "Job\\s+(?<jobnm>.+?)(?=\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\b" +
            "|\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card)\\b" +
            "|\\s+of\\s+cost\\b|\\s+other\\b|\\s+Category\\b|\\s+and\\b)\\s*" +
        ")?" +
        // Optional Category filter following a Job filter (e.g. "Job Standard Unit Category FFCC")
        "(?:Category\\s+(?<catafterjob>\\S+)\\s+)?" +
        "(?:(?<elements>(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*)\\s+)?" +
        "(?<targets>(?:Forwards?|Backups?|Monsters?|Summons?|Characters?)(?:\\s+or\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?))*|cards?)?\\s*" +
        "(?:\\s+other\\s+than\\s+a(?:n)?\\s+(?<excludetype>Forward|Backup|Monster|Summon|Character))?\\s*" +
        "(?:\\s+other\\s+than\\s+Card\\s+Name\\s+(?<excludename>.+?)(?=\\s+of\\s+cost|\\s+and\\b))?" +
        "(?:of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more|\\d+))?\\s*)?" +
        "(?:\\s+other\\s+than\\s+(?<excludeelem>(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*))?\\s*" +
        "and\\s+" +
        "(?<destination>" +
            "add\\s+it\\s+to\\s+your\\s+hand" +
            "|add\\s+them\\s+to\\s+your\\s+hand" +
            "|play\\s+it\\s+onto\\s+(?:the\\s+)?field(?:\\s+dull)?" +
            "|play\\s+them\\s+onto\\s+(?:the\\s+)?field(?:\\s+dull)?" +
            "|put\\s+it\\s+on\\s+top\\s+of\\s+(?:your|its\\s+owner's)\\s+deck" +
            "|put\\s+it\\s+under\\s+the\\s+top\\s+card\\s+of\\s+(?:your|its\\s+owner's)\\s+deck" +
            "|put\\s+it\\s+into\\s+(?:the\\s+)?Break\\s+Zone" +
            "|put\\s+them\\s+into\\s+(?:the\\s+)?Break\\s+Zone" +
        ")" +
        "[.!]?"
    );

    /** Matches "Your opponent shows/reveals his/her/their hand". */
    private static final Pattern OPPONENT_REVEAL_HAND_PATTERN = Pattern.compile(
        "(?i)Your\\s+opponent\\s+(?:shows?|reveals?)\\s+(?:his/her|his|her|their)\\s+hand[.!]?"
    );

    /**
     * Anchored prefix that confirms the effect text is a deck-reveal ability.
     * Group {@code who} captures the deck owner phrase so callers can tell
     * whether it is the ability user's own deck or the opponent's.
     * The clauses themselves are iterated with {@link #REVEAL_CLAUSE_PATTERN}.
     */
    private static final Pattern REVEAL_TOP_DECK_HEADER = Pattern.compile(
        "(?i)^\\s*Reveal\\s+the\\s+top\\s+card\\s+of\\s+" +
        "(?<who>opponent's|your)\\s+deck[.!]?"
    );

    /**
     * Iteratively matches each "If it is/has [cond], [action]" clause within a
     * reveal-top-deck effect text.
     * <ul>
     *   <li>Group {@code cond}   — full condition text (passed to {@link #parseRevealCondition})</li>
     *   <li>Group {@code action} — full action text (card-op or standalone effect)</li>
     * </ul>
     * The lookahead stops each {@code action} capture before the next clause or end of text.
     */
    private static final Pattern REVEAL_CLAUSE_PATTERN = Pattern.compile(
        "If\\s+it\\s+(?:is|has)\\s+(?<cond>[^,]+?)\\s*,\\s*(?<action>.+?)" +
        "(?=[.!]?\\s+If\\s+it\\s+(?:is|has)\\b|[.!]?\\s*$)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    /**
     * Matches "Put it into the Break Zone" — a forced send that bypasses
     * "cannot be broken" protections, unlike {@code FOLLOWUP_BREAK}.
     */
    private static final Pattern FOLLOWUP_PUT_TO_BREAK_ZONE = Pattern.compile(
        "(?i)Put\\s+it\\s+into\\s+the\\s+Break\\s+Zone[.!]?"
    );

    /**
     * Matches "&lt;subject&gt; gains [+N power] [, traits] until end of turn" where the subject
     * may be a card name (checked against the source at runtime) rather than "it"/"they".
     * <ul>
     *   <li>Group {@code selfsubject} — the word(s) before "gains"</li>
     *   <li>Group {@code selfamount}  — optional numeric power amount</li>
     *   <li>Group {@code selftraits}  — optional traits string</li>
     * </ul>
     */
    private static final Pattern SELF_POWER_BOOST = Pattern.compile(
        "(?i)(?<selfsubject>.+?)\\s+gains?\\s+" +
        "(?:\\+(?<selfamount>\\d+)\\s+[Pp]ower)?" +
        "(?<selftraits>(?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "CardName gains +N power for each 《C》 you have until end of turn."
     * Groups: {@code subject}, {@code amount}.
     */
    private static final Pattern SELF_POWER_BOOST_FOR_EACH_CRYSTAL = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower\\s+" +
        "for\\s+each\\s+《C》\\s+you\\s+have" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "[subject] gains +N power until the end of the turn and activate [activateName]."
     * Groups: {@code subject}, {@code amount}, {@code activateName}.
     */
    private static final Pattern SELF_POWER_BOOST_AND_ACTIVATE = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower\\s+" +
        "until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s+and\\s+activate\\s+" +
        "(?<activateName>.+?)[.!]?\\s*$"
    );

    /**
     * Matches "[CardName]'s power becomes the same as that Forward's power until the end of the turn."
     * Used as a secondary effect after choosing and removing a Forward from the Break Zone.
     * Group {@code name} — the card whose power is set (should match the source card).
     */
    private static final Pattern SOURCE_POWER_BECOMES_SAME_AS_REMOVED_FORWARD = Pattern.compile(
        "(?i)(?<name>.+?)'s\\s+power\\s+becomes\\s+the\\s+same\\s+as\\s+that\\s+Forward's\\s+power" +
        "\\s+until\\s+the\\s+end\\s+of\\s+(?:the\\s+)?turn[.!]?\\s*$"
    );

    /**
     * Matches "During this turn, if [CardName] deals damage to a Forward, double the damage instead."
     * Groups: {@code subject} — the card name.
     */
    private static final Pattern DOUBLE_OUTGOING_DAMAGE_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+(?<subject>.+?)\\s+deals?\\s+damage\\s+to\\s+a\\s+Forward," +
        "\\s+double\\s+the\\s+damage\\s+instead[.!]?"
    );

    /**
     * Matches "During this turn, if a Forward opponent controls is dealt damage, double the damage instead."
     */
    private static final Pattern DOUBLE_OPPONENT_INCOMING_DAMAGE_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+a\\s+Forward\\s+(?:your\\s+)?opponent\\s+controls\\s+" +
        "is\\s+dealt\\s+damage,\\s+double\\s+the\\s+damage\\s+instead[.!]?"
    );

    /**
     * Matches "If a Forward receives damage this turn, the damage increases by N instead."
     */
    private static final Pattern ALL_FORWARD_INCOMING_DMG_INCREASE_THIS_TURN = Pattern.compile(
        "(?i)If\\s+a\\s+Forward\\s+receives\\s+damage\\s+this\\s+turn,\\s+the\\s+damage\\s+increases?\\s+by\\s+(?<amount>\\d+)(?:\\s+instead)?[.!]?"
    );

    /**
     * Matches "If [subject] deals damage to a Forward this turn, double the damage instead."
     * (Ninja-style variant — "this turn" appears at the end rather than "During this turn" at the start.)
     */
    private static final Pattern DOUBLE_OUTGOING_DAMAGE_THIS_TURN_ALT = Pattern.compile(
        "(?i)If\\s+(?<subject>.+?)\\s+deals?\\s+damage\\s+to\\s+a\\s+Forward\\s+this\\s+turn,\\s+double\\s+the\\s+damage\\s+instead[.!]?"
    );

    /**
     * Matches "Choose 1 Forward opponent controls with a cost inferior or equal to the number of
     * [Element] [Backups/Forwards] you control. [followup]"
     * Groups: {@code element} — element name; {@code cardtype} — "Backups" or "Forwards";
     *         {@code followup} — effect sentence(s) to apply to the chosen targets.
     */
    private static final Pattern CHOOSE_OPP_FWD_DYN_COST_BREAK = Pattern.compile(
        "(?i)Choose\\s+1\\s+Forward\\s+(?:your\\s+)?opponent\\s+controls\\s+with\\s+a\\s+cost\\s+" +
        "(?:inferior\\s+or\\s+equal\\s+to|equal\\s+or\\s+inferior\\s+to|equal\\s+to\\s+or\\s+(?:less\\s+than|inferior))\\s+" +
        "the\\s+number\\s+of\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+" +
        "(?<cardtype>Backups?|Forwards?)\\s+you\\s+control[.,]?\\s+(?<followup>.+)"
    );

    /**
     * Matches "Choose 1 Forward [control?] with a power inferior to [CardName]'s [power]. [followup]"
     * Groups: {@code control} — optional "opponent controls" / "you control";
     *         {@code sourcename} — name of the card whose power sets the ceiling;
     *         {@code followup} — effect sentence(s) to apply to the chosen targets.
     */
    private static final Pattern CHOOSE_FWD_POWER_INFERIOR_TO_SOURCE = Pattern.compile(
        "(?i)Choose\\s+1\\s+Forward\\s+" +
        "(?:(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control)\\s+)?" +
        "with\\s+a\\s+power\\s+inferior\\s+to\\s+(?<sourcename>.+?)'s(?:\\s+power)?[.,]?\\s+(?<followup>.+)"
    );

    /**
     * Matches "Dull all [the] Forwards with a power [equal or inferior / inferior or equal /
     * equal to or less than] to [CardName]'s [your] opponent controls."
     * Groups: {@code sourcename} — name of the card whose power is the ceiling.
     */
    private static final Pattern DULL_ALL_OPP_FWDS_POWER_LE_SOURCE = Pattern.compile(
        "(?i)Dull\\s+all\\s+(?:the\\s+)?Forwards?\\s+with\\s+a\\s+power\\s+" +
        "(?:equal\\s+or\\s+inferior\\s+to|inferior\\s+or\\s+equal\\s+to|equal\\s+to\\s+or\\s+less\\s+than)\\s+" +
        "(?<sourcename>.+?)'s\\s+(?:(?:your\\s+)?opponent\\s+controls?)[.!]?"
    );

    /**
     * Matches "Choose 1 Forward in your Break Zone with a cost inferior to that of the removed
     * Forward. Play it onto the field." — the follow-up half of a Hojo-style remove-then-play chain.
     */
    private static final Pattern CHOOSE_FWD_BZ_COST_INFERIOR_TO_REMOVED_PLAY = Pattern.compile(
        "(?i)Choose\\s+1\\s+Forward\\s+in\\s+your\\s+Break\\s+Zone\\s+with\\s+a\\s+cost\\s+" +
        "inferior\\s+to\\s+that\\s+of\\s+the\\s+removed\\s+Forward[.,]?\\s+" +
        "Play\\s+it\\s+onto\\s+(?:the\\s+)?field[.!]?"
    );

    /**
     * Matches "Choose 1 Forward. During this turn, if it is dealt damage, double the damage instead."
     */
    private static final Pattern CHOOSE_FORWARD_DOUBLE_INCOMING_THIS_TURN = Pattern.compile(
        "(?i)Choose\\s+1\\s+Forward[.,]?\\s+During\\s+this\\s+turn,\\s+if\\s+it\\s+is\\s+dealt\\s+damage,\\s+double\\s+the\\s+damage\\s+instead[.!]?"
    );

    /**
     * Matches "Choose 1 [Job X] Forward. During this turn, the next damage it deals to a Forward
     * becomes double the damage instead. [You can only use this ability once per turn.]"
     * <ul>
     *   <li>Group {@code job} — optional job filter (e.g. {@code "Headhunter"})</li>
     * </ul>
     */
    private static final Pattern CHOOSE_FORWARD_DOUBLE_NEXT_OUTGOING = Pattern.compile(
        "(?i)Choose\\s+1\\s+(?:Job\\s+(?<job>.+?)\\s+)?Forward[.,]?\\s+" +
        "During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+it\\s+deals\\s+to\\s+a\\s+Forward\\s+" +
        "becomes\\s+double\\s+the\\s+damage\\s+instead[.!]?" +
        "(?:\\s+You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+once\\s+per\\s+turn\\.?)?"
    );

    /**
     * Matches "During this turn, if your ability deals damage to a Forward, double the damage instead."
     */
    private static final Pattern DOUBLE_PLAYER_ABILITY_OUTGOING_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+your\\s+ability\\s+deals?\\s+damage\\s+to\\s+a\\s+Forward,\\s+double\\s+the\\s+damage\\s+instead[.!]?"
    );

    /**
     * Matches "&lt;subject&gt; gains +N power [and traits]." with no duration clause — a permanent
     * passive field-ability self-boost (e.g. "Gilgamesh gains +1000 power.",
     * "Cid Raines gains +1000 power and First Strike.").
     * <ul>
     *   <li>Group {@code subject} — card name before "gains"</li>
     *   <li>Group {@code amount}  — numeric power amount</li>
     *   <li>Group {@code traits}  — optional traits string (e.g. "and First Strike")</li>
     * </ul>
     */
    private static final Pattern FIELD_SELF_POWER_BOOST = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower" +
        "(?<traits>(?:\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "[.!]?\\s*$"
    );

    /**
     * Matches "it/they gains/gain +N power [, Haste[, First Strike[, and Brave]]] until end of turn".
     * <ul>
     *   <li>Group 1 — numeric power amount</li>
     *   <li>Group 2 — optional traits string, e.g. {@code ", Haste, and First Strike"}</li>
     * </ul>
     */
    private static final Pattern FOLLOWUP_POWER_BOOST = Pattern.compile(
        "(?i)(?:it|they)\\s+gains?\\s+\\+(\\d+)\\s+[Pp]ower" +
        "((?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:(?:the|your)\\s+)?turn"
    );

    /**
     * Matches "Until the end of the turn, it/they gains/gain +N power [and traits]".
     * <ul>
     *   <li>Group 1 — numeric power amount</li>
     *   <li>Group 2 — optional traits string</li>
     * </ul>
     */
    /**
     * Matches either word order of the "gains +N power for each [Element] [Type] you control" followup:
     * <ul>
     *   <li>"Until end of turn, it gains +N power for each [Element] Type you control."</li>
     *   <li>"It gains +N power for each [Element] Type you control until end of turn."</li>
     * </ul>
     * Groups: 1 = per-unit amount, {@code element} = optional element, {@code chartype} = card type.
     */
    private static final Pattern FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH = Pattern.compile(
        "(?i)(?:" +
            "Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
            "(?:it|they)\\s+gains?\\s+\\+(\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
            "(?<chartype>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control" +
        "|" +
            "(?:it|they)\\s+gains?\\s+\\+(\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "(?:(?<element2>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
            "(?<chartype2>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control" +
            "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn" +
        ")[.!]?"
    );

    /**
     * Matches "Until the end of the turn, it gains +N power for each [Name] Counter placed on [card]."
     * Groups: {@code perunit} = per-counter power boost; {@code counterName} = counter type name.
     * Uses {@code xValue} captured before any BZ-cost payment cleared the counters.
     * Must be checked before {@link #FOLLOWUP_POWER_BOOST_UNTIL}, which would match only the +N.
     */
    private static final Pattern FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_COUNTER = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+gains?\\s+\\+(?<perunit>\\d+)\\s+[Pp]ower\\s+" +
        "for\\s+each\\s+(?<counterName>.+?)\\s+Counters?\\s+placed\\s+on\\s+.+?[.!]?$",
        Pattern.DOTALL
    );

    /**
     * Matches "Place N [Name] Counter(s) on it[/them]."
     * Groups: {@code count} — number of counters; {@code name} — counter type name.
     */
    private static final Pattern FOLLOWUP_PLACE_COUNTER_ON_IT = Pattern.compile(
        "(?i)Place\\s+(?<count>\\d+)\\s+(?<name>.+?)\\s+Counters?\\s+on\\s+(?:it|them)[.!]?"
    );

    /**
     * Matches "Select 1 Counter placed on it, and remove the selected Counter."
     * The counter type is chosen by the player at resolution time (dialog if multiple types).
     */
    private static final Pattern FOLLOWUP_REMOVE_ONE_COUNTER = Pattern.compile(
        "(?i)Select\\s+1\\s+Counter\\s+placed\\s+on\\s+(?:it|them)[,.]?\\s+" +
        "and\\s+remove\\s+the\\s+selected\\s+Counter[.!]?"
    );

    /**
     * Matches "Deal it N damage for each [Name] Counter(s) placed on [card]."
     * Groups: {@code perunit} = damage per counter; {@code counterName} = counter type name.
     * Uses {@code xValue} captured before any BZ-cost payment cleared the counters.
     * Must be checked before {@link #FOLLOWUP_DAMAGE_FOR_EACH}, which would match only the flat N damage.
     */
    private static final Pattern FOLLOWUP_DAMAGE_FOR_EACH_COUNTER = Pattern.compile(
        "(?i)Deal\\s+it\\s+(?<perunit>\\d+)\\s+damage\\s+" +
        "for\\s+each\\s+(?<counterName>.+?)\\s+Counters?\\s+placed\\s+on\\s+.+?[.!]?$",
        Pattern.DOTALL
    );

    /**
     * Matches "it gains +N power for each [Job (name)] / Job name [Type] you control until end of turn"
     * in both word orders (until-prefix or until-suffix).
     * Groups: {@code amount}/{@code amount2} = per-unit amount; {@code jobb}/{@code jobb2} = bracket job name;
     * {@code jobw}/{@code jobw2} = written job name; {@code jobt}/{@code jobt2} = optional type qualifier.
     * Must be checked before {@link #FOLLOWUP_POWER_BOOST_UNTIL}, which would match the +N and drop the rest.
     */
    private static final Pattern FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_JOB = Pattern.compile(
        "(?i)(?:" +
            "Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
            "(?:it|they)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "(?:\\[Job\\s+\\((?<jobb>[^)]+)\\)\\]|Job\\s+(?<jobw>.+?)(?:\\s+(?<jobt>Forwards?|Backups?|Monsters?))?)" +
            "\\s+you\\s+control" +
        "|" +
            "(?:it|they)\\s+gains?\\s+\\+(?<amount2>\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "(?:\\[Job\\s+\\((?<jobb2>[^)]+)\\)\\]|Job\\s+(?<jobw2>.+?)(?:\\s+(?<jobt2>Forwards?|Backups?|Monsters?))?)" +
            "\\s+you\\s+control" +
            "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn" +
        ")[.!]?"
    );

    /**
     * Matches "Until the end of the turn, it gains +N power for each point of damage you have received."
     * Group {@code perunit} = per-damage power amount.
     * Must be checked before {@link #FOLLOWUP_POWER_BOOST_UNTIL}, which would match the +N and drop the rest.
     */
    private static final Pattern FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_SELF_DMG = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+gains?\\s+\\+(?<perunit>\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+point\\s+of\\s+damage\\s+you\\s+have\\s+received[.!]?"
    );

    private static final Pattern FOLLOWUP_POWER_BOOST_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:(?:the|your)\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+gains?\\s+\\+(\\d+)\\s+[Pp]ower" +
        "((?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)"
    );

    /**
     * "It/they gains [TraitA] or [TraitB] until [the] end of [the] turn." — player picks one trait.
     * Groups {@code t1} and {@code t2} are the two trait names.  Must be checked before
     * {@link #FOLLOWUP_KEYWORD_GRANT} since the latter doesn't handle the "or" separator.
     */
    private static final Pattern FOLLOWUP_KEYWORD_GRANT_CHOICE = Pattern.compile(
        "(?i)(?:it|they)\\s+gains?\\s+" +
        "(?<t1>Haste|First\\s+Strike|Brave)\\s+or\\s+(?<t2>Haste|First\\s+Strike|Brave)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn"
    );

    /**
     * Matches "it/they gains Haste/First Strike/Brave [and …] until end of turn" with no power amount.
     * <ul>
     *   <li>Group 1 — traits string, e.g. {@code "Haste"} or {@code "Haste and First Strike"}</li>
     * </ul>
     */
    private static final Pattern FOLLOWUP_KEYWORD_GRANT = Pattern.compile(
        "(?i)(?:it|they)\\s+gains?\\s+" +
        "((?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))+)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn"
    );

    /**
     * Alternate word order: "Until the end of the turn, it/they gains Haste/First Strike/Brave [and …]"
     * with no power amount (EOT prefix, keywords only).
     * <ul>
     *   <li>Group 1 — traits string, e.g. {@code "Haste and First Strike"}</li>
     * </ul>
     */
    private static final Pattern FOLLOWUP_KEYWORD_GRANT_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+gains?\\s+" +
        "((?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))+)" +
        "[.!]?"
    );

    /**
     * Matches standalone "Until the end of the turn, &lt;subject&gt; gains +N power [and traits]".
     * Used when the subject is a specific card name rather than "it"/"they".
     * <ul>
     *   <li>Group {@code subject} — card name or pronoun before "gains"</li>
     *   <li>Group {@code amount}  — numeric power amount</li>
     *   <li>Group {@code traits}  — optional traits string</li>
     * </ul>
     */
    /**
     * Matches "Until [the] end of [the] turn, &lt;subject&gt; gains +N power and
     * '<em>When &lt;subject&gt; attacks, &lt;attackEffect&gt;</em>'."
     * Used by action abilities that temporarily grant a power boost AND an attack auto-ability
     * (e.g. Black Mage's 《C》 ability).
     * <ul>
     *   <li>Group {@code subject}      — card name, must match {@code source.name()}</li>
     *   <li>Group {@code amount}       — power boost value</li>
     *   <li>Group {@code attackEffect} — the effect text that fires when the card attacks</li>
     * </ul>
     */
    private static final Pattern STANDALONE_POWER_BOOST_AND_ATTACK_TRIGGER = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?<subject>.+?)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower\\s+and\\s+" +
        "\"When\\s+[^\"]+?\\s+attacks?\\s*,\\s+(?<attackEffect>[^\"]+?)\"\\s*[.!]?\\s*$",
        Pattern.DOTALL
    );

    /**
     * Matches "Until the end of the turn, [name] gains [traits] and '[name] cannot be blocked.'"
     * Used when a self-buff grants keyword traits AND unblockable status simultaneously.
     * Groups: {@code subject} — card name; {@code traits} — keyword list.
     */
    private static final Pattern STANDALONE_GAINS_TRAITS_AND_CANNOT_BE_BLOCKED = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?<subject>.+?)\\s+gains?\\s+" +
        "(?<traits>(?:Haste|First\\s+Strike|Brave)(?:\\s+and\\s+(?:Haste|First\\s+Strike|Brave))*)" +
        "\\s+and\\s+\".+?\\s+cannot\\s+be\\s+blocked\\.?\"[.!]?"
    );

    private static final Pattern STANDALONE_POWER_BOOST_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?<subject>.+?)\\s+gains?\\s+" +
        "(?:\\+(?<amount>\\d+)\\s+[Pp]ower)?" +
        "(?<traits>(?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "[.\\s]*$"
    );

    /**
     * Matches "Double the power of &lt;subject&gt; until [the] end of [the] turn".
     * <ul>
     *   <li>Group {@code subject} — card name before "until"</li>
     * </ul>
     */
    private static final Pattern STANDALONE_DOUBLE_POWER_UNTIL = Pattern.compile(
        "(?i)Double\\s+the\\s+power\\s+of\\s+(?<subject>.+?)\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "Until the end of the turn, &lt;subject&gt; doubles its power [and gains traits]".
     * <ul>
     *   <li>Group {@code subject} — card name before "doubles"</li>
     *   <li>Group {@code traits}  — optional trailing text (e.g. "and gains First Strike and Brave")</li>
     * </ul>
     */
    private static final Pattern STANDALONE_DOUBLES_ITS_POWER_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?<subject>.+?)\\s+doubles?\\s+its\\s+power(?<traits>[^.!]*)"
    );

    /**
     * Matches "At the beginning of your next turn's Main Phase 1 and until the end of the same
     * turn, &lt;subject&gt;'s power will double."
     * <ul>
     *   <li>Group {@code subject} — card name before "'s power will double"</li>
     * </ul>
     */
    private static final Pattern STANDALONE_DOUBLE_POWER_MAIN_PHASE_NEXT_TURN = Pattern.compile(
        "(?i)At\\s+the\\s+beginning\\s+of\\s+your\\s+next\\s+turn's\\s+Main\\s+Phase\\s+1" +
        "\\s+and\\s+until\\s+the\\s+end\\s+of\\s+the\\s+same\\s+turn\\s*,\\s+" +
        "(?<subject>.+?)'s\\s+power\\s+will\\s+double[.!]?"
    );

    /**
     * Matches "it/they loses/lose [N power] [, traits] until end of turn".
     * Both power and traits are optional, but at least one must be present in practice.
     * <ul>
     *   <li>Group 1 — optional numeric power amount (absent = traits-only)</li>
     *   <li>Group 2 — optional traits string</li>
     * </ul>
     */
    private static final Pattern FOLLOWUP_POWER_REDUCE = Pattern.compile(
        "(?i)(?:it|they)\\s+loses?\\s+" +
        "(?:(\\d+)\\s+[Pp]ower)?" +
        "((?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn"
    );

    /** Matches "Its/Their power becomes N until the end of the turn." — group 1 is the target power. */
    private static final Pattern FOLLOWUP_POWER_BECOMES = Pattern.compile(
        "(?i)(?:its?|their)\\s+power\\s+becomes?\\s+(\\d+)\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "Until the end of the turn, it/they loses/lose [N power] [and traits]".
     * <ul>
     *   <li>Group 1 — optional numeric power amount</li>
     *   <li>Group 2 — optional traits string</li>
     * </ul>
     */
    private static final Pattern FOLLOWUP_POWER_REDUCE_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+loses?\\s+" +
        "(?:(\\d+)\\s+[Pp]ower)?" +
        "((?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)"
    );

    /** Matches "Until [of] the end of [the] turn, it/they loses N power for each card in your hand." */
    private static final Pattern FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH_HAND = Pattern.compile(
        "(?i)Until\\s+(?:of\\s+)?(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+loses?\\s+(\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+card\\s+in\\s+your\\s+hand[.!]?"
    );

    /**
     * Matches either word order of the "loses N power for each [Element] [Type] you control" followup
     * (the reduce counterpart of {@link #FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH}):
     * <ul>
     *   <li>"Until end of turn, it loses N power for each [Element] Type you control."</li>
     *   <li>"It loses N power for each [Element] Type you control until end of turn."</li>
     * </ul>
     * Groups: 1 = per-unit amount (until-prefix order), 4 = per-unit amount (suffix order);
     * {@code element}/{@code chartype} (until-prefix) or {@code element2}/{@code chartype2} (suffix).
     */
    private static final Pattern FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH = Pattern.compile(
        "(?i)(?:" +
            "Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
            "(?:it|they)\\s+loses?\\s+(\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
            "(?<chartype>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control" +
        "|" +
            "(?:it|they)\\s+loses?\\s+(\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "(?:(?<element2>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
            "(?<chartype2>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control" +
            "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn" +
        ")[.!]?"
    );

    /** Matches "it/they loses N power" with no timing qualifier — implied EOT in former/latter context. */
    private static final Pattern FOLLOWUP_POWER_REDUCE_BARE = Pattern.compile(
        "(?i)(?:it|they)\\s+loses?\\s+(\\d+)\\s+[Pp]ower[.!]?"
    );

    /**
     * Matches standalone "Until the end of the turn, &lt;subject&gt; loses [N power] [and traits]".
     * <ul>
     *   <li>Group {@code subject} — card name or pronoun before "loses"</li>
     *   <li>Group {@code amount}  — optional numeric power amount</li>
     *   <li>Group {@code traits}  — optional traits string</li>
     * </ul>
     */
    private static final Pattern STANDALONE_POWER_REDUCE_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?<subject>.+?)\\s+loses?\\s+" +
        "(?:(?<amount>\\d+)\\s+[Pp]ower)?" +
        "(?<traits>(?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "[.\\s]*$"
    );

    /**
     * Matches mass-effect actions on all field cards of a given type:
     * "[action] all [the] [element] [targets] [of cost X [or less|more]] [other than cost Y] [control]"
     * <ul>
     *   <li>Group {@code action}      — "Break", "dull", "freeze", "dull and freeze", or "Activate"</li>
     *   <li>Group {@code element}     — optional element name</li>
     *   <li>Group {@code targets}     — "Forwards", "Backups", "Forwards and Monsters", or "Characters"</li>
     *   <li>Group {@code cost}        — optional CP cost value (inclusive filter)</li>
     *   <li>Group {@code costcmp}     — optional comparison: "less" or "more"</li>
     *   <li>Group {@code excludecost} — optional exact cost to exclude, from "other than cost N"</li>
     *   <li>Group {@code control}     — optional: "opponent controls" or "you control"</li>
     * </ul>
     */
    private static final Pattern ALL_FIELD_EFFECT_PATTERN = Pattern.compile(
        "(?i)(?<action>Break|Activate|dull\\s+and\\s+freeze|dull|freeze)\\s+" +
        "all\\s+(?:the\\s+)?" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?:Job\\s+(?<job>.+?)(?=\\s+(?:Forwards?|Backups?|Characters?|you\\b|opponent\\b)|\\s*[.!]?$))?" +
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?)?" +
        "(?:\\s+with\\s+(?<trait>(?:Haste|First\\s+Strike|Brave)(?:\\s*(?:,\\s*(?:or\\s+)?|\\s+or\\s+)(?:Haste|First\\s+Strike|Brave))*))?" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+other\\s+than\\s+cost\\s+(?<excludecost>\\d+))?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "[.!]?"
    );

    /**
     * Matches "All [the] [element] Forwards/Backups/Characters [of cost N [or less|more]]
     * [you control | opponent controls] gain +N power until [the] end of [the] turn."
     * <ul>
     *   <li>Group {@code element}  — optional element name</li>
     *   <li>Group {@code targets}  — "Forwards", "Forwards and Monsters", etc.</li>
     *   <li>Group {@code cost}     — optional CP cost value</li>
     *   <li>Group {@code costcmp}  — optional comparison: "less" or "more"</li>
     *   <li>Group {@code control}  — optional: "opponent controls" or "you control"</li>
     *   <li>Group {@code amount}   — power amount to add</li>
     * </ul>
     */
    private static final Pattern ALL_FIELD_POWER_BOOST_PATTERN = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?)" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+other\\s+than\\s+(?<excludename>.+?))?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+(?<verb>gains?|loses?)\\s+\\+?(?<amount>\\d+)\\s+[Pp]ower" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "All [the] Forwards of the same Element as [Card Name] X you control
     * gain +N power until [the] end of [the] turn."
     * Groups: {@code name}, {@code control}, {@code verb}, {@code amount}.
     */
    private static final Pattern ALL_FORWARDS_SAME_ELEMENT_AS_NAMED_POWER_BOOST = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Forwards?\\s+of\\s+the\\s+same\\s+Element\\s+as\\s+" +
        "(?:Card\\s+Name\\s+)?(?<name>[A-Za-z][A-Za-z0-9\\s''\\-]*?)\\s+" +
        "(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control)\\s+" +
        "(?<verb>gains?|loses?)\\s+\\+?(?<amount>\\d+)\\s+[Pp]ower" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "All Job X and Card Name Y [you control | opponent controls]
     * gain +N power until [the] end of [the] turn."
     * Groups: {@code job}, {@code cardname}, {@code control}, {@code verb}, {@code amount}.
     */
    private static final Pattern ALL_FIELD_JOB_CARDNAME_POWER_BOOST_PATTERN = Pattern.compile(
        "(?i)All\\s+Job\\s+(?<job>[\\w][\\w\\s]*?)\\s+and\\s+Card\\s+Name\\s+(?<cardname>[\\w][\\w\\s]*?)\\s+" +
        "(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control)\\s+" +
        "(?<verb>gains?|loses?)\\s+\\+?(?<amount>\\d+)\\s+[Pp]ower" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "[The] Card Name X [Forward] and Card Name Y [Forward] [you control | opponent controls]
     * gain +N power until [the] end of [the] turn."
     * Groups: {@code name1}, {@code name2}, {@code control}, {@code verb}, {@code amount}.
     */
    private static final Pattern TWO_CARD_NAMES_POWER_BOOST_PATTERN = Pattern.compile(
        "(?i)(?:The\\s+)?Card\\s+Name\\s+(?<name1>[\\w][\\w\\s''\\-]*?)" +
        "(?:\\s+(?:Forwards?|Backups?|Monsters?|Characters?))?" +
        "\\s+and\\s+Card\\s+Name\\s+(?<name2>[\\w][\\w\\s''\\-]*?)" +
        "(?:\\s+(?:Forwards?|Backups?|Monsters?|Characters?))?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+(?<verb>gains?|loses?)\\s+\\+?(?<amount>\\d+)\\s+[Pp]ower" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "All [the] Job X Forwards/Backups/Characters [you control | opponent controls]
     * gain +N power until [the] end of [the] turn."
     * Groups: {@code job}, {@code targets}, {@code control}, {@code verb}, {@code amount}.
     */
    private static final Pattern ALL_FIELD_JOB_POWER_BOOST_PATTERN = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Job\\s+(?<job>[A-Za-z][A-Za-z\\s''\\-]*?)\\s+" +
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?)" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+(?<verb>gains?|loses?)\\s+\\+?(?<amount>\\d+)\\s+[Pp]ower" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "All [the] Job X [targets] [you control | opponent controls]
     * gain Keyword[, ...] until end of turn."
     * Groups: {@code job}, {@code targets} (optional), {@code control}, {@code keywords}.
     */
    private static final Pattern ALL_FIELD_JOB_KEYWORD_GRANT_PATTERN = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Job\\s+(?<job>[A-Za-z][A-Za-z\\s''\\-]*?)" +
        "(?:\\s+(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?))?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+gains?\\s+(?<keywords>(?:(?:Haste|First\\s+Strike|Brave)(?:\\s*[,]?\\s*(?:and\\s+)?)?)+)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "All [the] [element] [Category X] [targets] [of cost N [or less|more]]
     * [you control | opponent controls] gain Keyword[, Keyword2, ...] until end of turn."
     * Groups: {@code element}, {@code category}, {@code targets}, {@code cost}, {@code costcmp},
     * {@code control}, {@code keywords}.
     */
    private static final Pattern ALL_FIELD_KEYWORD_GRANT_PATTERN = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?)" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+gains?\\s+(?<keywords>(?:(?:Haste|First\\s+Strike|Brave)(?:\\s*[,]?\\s*(?:and\\s+)?)?)+)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "Until end of turn, all [the] [element] [Category X] [targets] [you control]
     * gain/lose +N power [and Keywords]."
     * Groups: {@code element}, {@code category}, {@code targets}, {@code cost}, {@code costcmp},
     * {@code control}, {@code verb}, {@code amount}, {@code keywords}.
     */
    private static final Pattern UNTIL_EOT_ALL_FIELD_POWER_BOOST_PATTERN = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,?\\s+" +
        "all\\s+(?:the\\s+)?" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?)" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+(?<verb>gains?|loses?)\\s+\\+?(?<amount>\\d+)\\s+[Pp]ower" +
        "(?:\\s+and\\s+(?<keywords>(?:(?:Haste|First\\s+Strike|Brave)(?:,?\\s+(?:and\\s+)?)?)+))?[.!]?"
    );

    /**
     * Matches "Until end of turn, all [the] [targets1] [you control] gain +N power
     * and all [the] [targets2] [opponent controls] lose N power."
     * Groups: {@code targets1}, {@code control1}, {@code amount1},
     *         {@code targets2}, {@code control2}, {@code amount2}.
     */
    private static final Pattern UNTIL_EOT_DUAL_POWER_SHIFT_PATTERN = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,?\\s+" +
        "all\\s+(?:the\\s+)?" +
        "(?:(?<element1>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<targets1>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?)" +
        "(?:\\s+(?<control1>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+gains?\\s+\\+?(?<amount1>\\d+)\\s+[Pp]ower" +
        "\\s+and\\s+all\\s+(?:the\\s+)?" +
        "(?:(?<element2>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<targets2>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?)" +
        "(?:\\s+(?<control2>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+loses?\\s+\\+?(?<amount2>\\d+)\\s+[Pp]ower[.!]?"
    );

    /**
     * Matches "Draw N card(s)[, then discard M card(s)]".
     * <ul>
     *   <li>Group 1 — number of cards to draw</li>
     *   <li>Group 2 — optional discard count afterward (absent = draw only)</li>
     * </ul>
     */
    // ---- "Select 1 number" patterns -------------------------------------------

    /** Matches the "Select 1 number." opening of an ability that lets the active player pick a cost. */
    private static final Pattern SELECT_NUMBER_HEADER = Pattern.compile(
        "(?i)^Select\\s+1\\s+number\\.\\s*"
    );

    /** Matches "Your opponent selects 1 number." — appears as a second header in dual-selection abilities. */
    private static final Pattern SELECT_NUMBER_OPPONENT_ALSO = Pattern.compile(
        "(?i)^Your\\s+opponent\\s+selects\\s+1\\s+number\\.\\s*"
    );

    /**
     * Inner effect: "All [the] Forwards of that cost cannot attack this turn."
     * Cannot be handled by the general substitution path since "cannot attack" is not
     * a MassAction in {@link GameContext.MassAction}.
     */
    private static final Pattern SELECT_NUMBER_INNER_CANNOT_ATTACK = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Forwards?\\s+of\\s+that\\s+cost\\s+cannot\\s+attack\\s+this\\s+turn\\.?"
    );

    /**
     * Inner effect for the dual-number case: "Break all Forwards of cost equal to either number."
     * Both P1's and P2's chosen numbers are used as cost filters.
     */
    private static final Pattern SELECT_NUMBER_INNER_EITHER_BREAK = Pattern.compile(
        "(?i)Break\\s+all\\s+Forwards?\\s+of\\s+cost\\s+equal\\s+to\\s+either\\s+number\\.?"
    );

    /**
     * Followup used inside {@link #tryParseChooseCharacter}:
     * "Select 1 number and reveal the top card of your deck.
     *  If the revealed card is of the same cost as the selected number, break it."
     * "It" refers to the previously chosen Forward, not the revealed card.
     */
    private static final Pattern FOLLOWUP_SELECT_NUMBER_REVEAL_BREAK = Pattern.compile(
        "(?i)Select\\s+1\\s+number\\s+and\\s+reveal\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck\\.\\s+" +
        "If\\s+the\\s+revealed\\s+card\\s+is\\s+of\\s+the\\s+same\\s+cost\\s+as\\s+the\\s+selected\\s+number,\\s+break\\s+it\\.?"
    );

    /**
     * Followup used inside {@link #tryParseChooseCharacter}:
     * "Select a Job. It gains that Job until the end of the turn." or
     * "Name 1 Job. It gains the named Job until the end of the turn."
     * Matched against the full followup (before the dot-split) so both sentences are seen together.
     */
    private static final Pattern FOLLOWUP_SELECT_JOB_GRANT = Pattern.compile(
        "(?i)^(?:Select\\s+a|Name\\s+1)\\s+Job[.!]?\\s+" +
        "It\\s+gains?\\s+(?:that|the\\s+named)\\s+Job\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?$"
    );

    /**
     * Matches "Look at the top card of your deck. You may put it into the Break Zone."
     */
    private static final Pattern LOOK_TOP_DECK_OPTIONALLY_BREAK = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "You\\s+may\\s+put\\s+it\\s+into\\s+the\\s+Break\\s+Zone[.!]?"
    );

    /**
     * Matches "Look at the top card of your deck. You may place the card at the bottom of your deck."
     */
    private static final Pattern LOOK_TOP_DECK_BOTTOM_OR_KEEP = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "You\\s+may\\s+place\\s+(?:the\\s+)?card\\s+at\\s+the\\s+bottom\\s+of\\s+your\\s+deck[.!]?"
    );

    /**
     * Matches "Look at the top N cards of your deck. Return them to the top of your deck in any order."
     * <ul>
     *   <li>Group {@code count} — number of cards to look at</li>
     * </ul>
     */
    private static final Pattern LOOK_TOP_DECK_RETURN_TOP_ORDERED = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "Return\\s+them\\s+to\\s+the\\s+top\\s+of\\s+your\\s+deck\\s+in\\s+any\\s+order[.!]?"
    );

    /**
     * Matches "Look at the top N cards of your deck. Add 1 card among them to your hand and
     * return the other cards to the bottom of your deck in any order."
     * <ul>
     *   <li>Group {@code count} — number of cards to look at</li>
     * </ul>
     */
    private static final Pattern LOOK_TOP_DECK_ADD_TO_HAND_REST_BOTTOM = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "Add\\s+1\\s+card\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+and\\s+" +
        "return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+your\\s+deck\\s+in\\s+any\\s+order[.!]?"
    );

    /**
     * Matches "Look at the top N cards of your deck. Add 1 card among them to your hand,
     * put 1 card into the Break Zone and return the other cards to the bottom of your deck
     * in any order."
     * <ul>
     *   <li>Group {@code count} — number of cards to look at</li>
     * </ul>
     */
    private static final Pattern LOOK_TOP_DECK_ADD_TO_HAND_ONE_TO_BREAK_REST_BOTTOM = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "Add\\s+1\\s+card\\s+among\\s+them\\s+to\\s+your\\s+hand[,.]?\\s*" +
        "put\\s+1\\s+card\\s+into\\s+the\\s+Break\\s+Zone\\s+and\\s+" +
        "return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+your\\s+deck\\s+in\\s+any\\s+order[.!]?"
    );

    /**
     * Matches "Look at / Reveal the top N cards of your deck. Add 1 [Element] card among them
     * to your hand and put the rest of the cards into the Break Zone."
     * <ul>
     *   <li>Group {@code count}   — number of cards to look at / reveal</li>
     *   <li>Group {@code element} — optional element filter on the card added to hand</li>
     * </ul>
     */
    private static final Pattern LOOK_TOP_DECK_ADD_TO_HAND_REST_BREAK = Pattern.compile(
        "(?i)(?:Look\\s+at|Reveal)\\s+the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "Add\\s+1\\s+(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?card\\s+among\\s+them\\s+to\\s+your\\s+hand[,]?\\s+and\\s+" +
        "put\\s+the\\s+rest\\s+(?:of\\s+the\\s+cards?\\s+)?into\\s+the\\s+Break\\s+Zone[.!]?"
    );

    /**
     * Matches "Look at the top N cards of your deck. Return these to the top and/or bottom of
     * your deck in any order."
     * <ul>
     *   <li>Group {@code count} — number of cards to look at</li>
     * </ul>
     */
    private static final Pattern LOOK_TOP_DECK_TOP_OR_BOTTOM = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "Return\\s+(?:them|these)\\s+to\\s+the\\s+top\\s+and[/\\s]?(?:or\\s+)?bottom\\s+of\\s+your\\s+deck\\s+in\\s+any\\s+order[.!]?"
    );

    /**
     * Matches "Look at the top N cards of your deck. Put 1 card among them on top of your
     * deck and the other(s) to the bottom of your deck."
     * Strict 1-to-top, rest-to-bottom split.
     * <ul>
     *   <li>Group {@code count} — number of cards to look at</li>
     * </ul>
     */
    private static final Pattern LOOK_TOP_DECK_PICK_ONE_TOP_REST_BOTTOM = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "Put\\s+1\\s+card\\s+among\\s+them\\s+on\\s+top\\s+of\\s+your\\s+deck\\s+and\\s+" +
        "the\\s+others?\\s+to\\s+the\\s+bottom\\s+of\\s+your\\s+deck[.!]?"
    );

    /**
     * Catch-all: matches any bare "Look at the top [N cards / card] of your deck" with no
     * further action clause — treated as a pure peek (card stays on top, player just sees it).
     * <ul>
     *   <li>Group {@code count} — number of cards, or absent for the singular "top card" form</li>
     * </ul>
     */
    private static final Pattern LOOK_TOP_DECK_PEEK = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+(?:(?<count>\\d+)\\s+cards?|card)\\s+of\\s+your\\s+deck[.!]?"
    );

    /**
     * Matches "Look at the top X cards of your deck. Reveal 1 Summon of cost X or less among
     * them and cast it without paying the cost. Then, shuffle the other cards and return them
     * to the bottom of your deck."
     * Groups: {@code count} — card count (numeric or {@code X});
     *         {@code cost}  — cost cap (numeric or {@code X}).
     */
    private static final Pattern LOOK_TOP_DECK_CAST_SUMMON_FREE_REST_BOTTOM = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+(?<count>\\d+|X)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Reveal\\s+1\\s+Summon\\s+of\\s+cost\\s+(?<cost>\\d+|X)\\s+or\\s+less\\s+among\\s+them\\s+" +
        "and\\s+cast\\s+it\\s+without\\s+paying\\s+(?:its|the)\\s+cost[.!]?\\s+" +
        "(?:Then,?\\s+)?shuffle\\s+the\\s+other\\s+cards?\\s+and\\s+return\\s+them\\s+" +
        "to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck[.!]?"
    );

    /**
     * "Reveal the top card of your deck. Break all Forwards opponent controls with the same cost
     * as the revealed card. Add the revealed card to your hand."
     */
    private static final Pattern REVEAL_TOP_BREAK_SAME_COST_ADD_TO_HAND = Pattern.compile(
        "(?i)Reveal\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Break\\s+all\\s+Forwards?\\s+(?:your\\s+)?opponent\\s+controls?\\s+with\\s+the\\s+same\\s+cost\\s+" +
        "as\\s+the\\s+revealed\\s+card[.!]?\\s+" +
        "Add\\s+the\\s+revealed\\s+card\\s+to\\s+your\\s+hand[.!]?"
    );

    /**
     * Detects "select [up to] N of the M following actions" — handled by MainWindow's
     * {@code executeSelectFollowingActionsAutoAbility}, not by ActionResolver's parse chain.
     * Used only for pattern-name reporting.
     */
    static final Pattern SELECT_FOLLOWING_ACTIONS_DETECT = Pattern.compile(
        "(?i)^(?:" +
        "(?:if\\s+[^,]+,\\s+)?select\\s+(?:up\\s+to\\s+)?\\d+\\s+of\\s+the\\s+\\d+\\s+following\\s+actions?" +
        "|select\\s+the\\s+following\\s+actions?\\s+from\\s+top\\s+to\\s+bottom\\b" +
        ")"
    );

    /**
     * Captures the components of "[if cond,] select [up to] N of the M following actions. "a" "b" ..."
     * so the action-ability parse chain can resolve it as a modal choice.
     */
    static final Pattern SELECT_FOLLOWING_ACTIONS = Pattern.compile(
        "(?i)^(?:if\\s+[^,]+,\\s+)?select\\s+(?<upTo>up\\s+to\\s+)?(?<select>\\d+)\\s+of\\s+the\\s+"
        + "(?<total>\\d+)\\s+following\\s+actions?[.!]?\\s*(?<actions>.+)$",
        Pattern.DOTALL
    );

    /** Extracts the individual quoted action strings from the {@code actions} capture group. */
    static final Pattern SELECT_FOLLOWING_QUOTED_ACTION = Pattern.compile("\"([^\"]+)\"");

    /**
     * Matches an inline conditional upgrade sentence that may appear before the quoted actions:
     * "If you control N or more [Element] [Type], select [up to] M of the K following actions instead."
     * Groups: {@code condCount}, {@code condElement} (optional), {@code condType},
     *         {@code condUpTo} (optional), {@code condSelect}.
     */
    private static final Pattern SELECT_FOLLOWING_ACTIONS_CONDITIONAL_UPGRADE = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<condCount>\\d+)\\s+or\\s+more\\s+" +
        "(?:(?<condElement>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<condType>Forwards?|Backups?|Monsters?|Characters?|Summons?),\\s+" +
        "select\\s+(?<condUpTo>up\\s+to\\s+)?(?<condSelect>\\d+)\\s+of\\s+the\\s+\\d+\\s+" +
        "following\\s+actions?\\s+instead[.!]?\\s*",
        Pattern.DOTALL
    );

    /**
     * Matches "Place N [Name] Counter(s) on [CardName][.]".
     * <ul>
     *   <li>Group {@code count} — number of counters to place</li>
     *   <li>Group {@code name}  — counter name (e.g. {@code "Shuriken"})</li>
     *   <li>Group {@code target} — card name the counters are placed on</li>
     * </ul>
     */
    private static final Pattern PLACE_COUNTERS = Pattern.compile(
        "(?i)Place\\s+(?<count>\\d+)\\s+(?<name>.+?)\\s+Counters?\\s+on\\s+(?<target>[^.!,]+)\\s*[.!]?"
    );

    /**
     * Matches "Place N [Name] Counter(s) on [CardName] for each [Type] you control."
     * Groups: {@code count}, {@code name}, {@code target}, {@code type}.
     */
    private static final Pattern PLACE_COUNTERS_FOR_EACH = Pattern.compile(
        "(?i)^[Pp]lace\\s+(?<count>\\d+)\\s+(?<name>.+?)\\s+Counters?\\s+on\\s+(?<target>.+?)" +
        "\\s+for\\s+each\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control[.!]?$"
    );

    /**
     * Matches "Choose 1 Forward opponent controls. [Name] gains its Special Ability until the end of the turn.
     * You can use this ability without paying any cost but only once."
     * Group {@code sourceName} — card name that gains the ability (used for logging).
     */
    private static final Pattern CHOOSE_OPP_FWD_GAINS_SPECIAL_ABILITY_FREE_ONCE = Pattern.compile(
        "(?i)^Choose\\s+1\\s+Forward\\s+(?:your\\s+)?opponent\\s+controls[,.]?\\s+" +
        "(?<sourceName>.+?)\\s+gains\\s+its\\s+Special\\s+Abilit(?:y|ies)\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn[.!]?\\s+" +
        "You\\s+can\\s+use\\s+this\\s+ability\\s+without\\s+paying\\s+any\\s+cost\\s+but\\s+only\\s+once[.!]?\\s*$"
    );

    /**
     * Matches "Choose 1 Forward opponent controls which has been dealt damage this turn.
     * If that Forward has a special ability or an action ability, break it."
     */
    private static final Pattern CHOOSE_OPP_DAMAGED_FWD_IF_HAS_ABILITY_BREAK = Pattern.compile(
        "(?i)^Choose\\s+1\\s+Forward\\s+(?:your\\s+)?opponent\\s+controls\\s+" +
        "which\\s+has\\s+been\\s+dealt\\s+damage\\s+this\\s+turn[,.]?\\s+" +
        "If\\s+that\\s+Forward\\s+has\\s+(?:a\\s+special\\s+ability|an?\\s+action\\s+ability)" +
        "(?:\\s+or\\s+(?:a\\s+special\\s+ability|an?\\s+action\\s+ability))*,?\\s+break\\s+it[.!]?\\s*$"
    );

    /**
     * Matches "Choose as many &lt;Type&gt; [opponent controls] as [the] &lt;CountSource&gt; you control. &lt;Followup&gt;"
     * where the count is derived at resolution time from the acting player's field.
     * Group {@code targetType} — card type to choose (Forward/Character/etc.).
     * Group {@code targetSide} — "opponent controls" if targeting the opponent's cards; null = self.
     * Group {@code countSrc} — job-bracket, "Category X Type", "Job X", or plain card-type count source.
     * Group {@code followup} — effect to apply (Dull/Activate/Freeze).
     */
    private static final Pattern CHOOSE_AS_MANY_AS_FIELD_COUNT = Pattern.compile(
        "(?i)^Choose\\s+(?:as\\s+many|up\\s+to\\s+the\\s+same\\s+number\\s+of)\\s+" +
        "(?<targetType>Forwards?|Characters?|Backups?|Monsters?)(?:\\s+Cards?)?\\s+" +
        "(?:(?<targetSide>(?:your\\s+)?opponent\\s+controls|you\\s+control)\\s+)?" +
        "as\\s+(?:the\\s+)?" +
        "(?<countSrc>\\[Job\\s*\\([^)]+\\)\\]|Category\\s+\\S+(?:\\s+(?:Forwards?|Characters?|Backups?|Monsters?))?|Job\\s+.+?(?=\\s+you\\s+control)|Forwards?|Backups?|Monsters?|Characters?)" +
        "\\s+you\\s+control[,.]?\\s+" +
        "(?<followup>.+)$"
    );

    /**
     * Matches "Choose up to the same number of Characters as the [Name] Counters placed on [card]. Activate them."
     * At resolution time {@code xValue} holds the counter count captured before the card was put into the Break Zone.
     * Group {@code counterName} — counter type (e.g. "Monster"); group {@code card} — source card name.
     */
    private static final Pattern CHOOSE_COUNTER_SCALE_CHARS_ACTIVATE = Pattern.compile(
        "(?i)Choose\\s+up\\s+to\\s+the\\s+same\\s+number\\s+of\\s+Characters?\\s+as\\s+the\\s+(?<counterName>.+?)\\s+Counters?\\s+placed\\s+on\\s+(?<card>.+?)[,.]\\s*Activate\\s+them[.!]?"
    );

    /**
     * Matches "Look at the same number of cards from the top of your deck as the [Name] Counters placed on [card].
     * Add 1 card among them to your hand. Then, shuffle the other cards and return them to the bottom of your deck."
     * At resolution time {@code xValue} holds the counter count captured before the card was put into the Break Zone.
     * Group {@code counterName} — counter type (e.g. "Monster"); group {@code card} — source card name.
     */
    private static final Pattern LOOK_COUNTER_SCALE_ADD_TO_HAND_REST_BOTTOM = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+same\\s+number\\s+of\\s+cards?\\s+from\\s+the\\s+top\\s+of\\s+your\\s+deck\\s+as\\s+the\\s+(?<counterName>.+?)\\s+Counters?\\s+placed\\s+on\\s+(?<card>.+?)[,.]" +
        ".+?Add\\s+1\\s+card.+?to\\s+your\\s+hand.+?(?:shuffle|return).+?bottom.+?deck[.!]?"
    );

    /** Matches "Gain 《C》[《C》...]." — captures one or more consecutive Crystal symbols. */
    private static final Pattern GAIN_CRYSTAL = Pattern.compile(
        "(?i)Gain\\s+(?<crystals>(?:《C》)+)[.!]?"
    );

    /** Matches "Gain 《C》 for each CP paid as X." — crystal count equals the X value paid. */
    private static final Pattern GAIN_CRYSTAL_PER_X = Pattern.compile(
        "(?i)Gain\\s+《C》\\s+for\\s+each\\s+CP\\s+paid\\s+as\\s+X[.!]?"
    );

    /**
     * Matches "If your opponent has a 《C》, [also] gain 《C》."
     * Grants 1 Crystal only when the opponent currently holds at least one Crystal.
     */
    private static final Pattern GAIN_CRYSTAL_IF_OPPONENT_HAS = Pattern.compile(
        "(?i)If\\s+your\\s+opponent\\s+has\\s+a\\s+《C》,\\s+(?:also\\s+)?gain\\s+《C》[.!]?"
    );

    /**
     * Matches "Draw N card(s), then place M card(s) from your hand at the bottom of your deck."
     * Group 1 = draw count, Group 2 = place count.
     */
    private static final Pattern DRAW_THEN_PLACE_HAND_TO_BOTTOM = Pattern.compile(
        "(?i)Draw\\s+(\\d+)\\s+cards?[,.]?\\s+then\\s+place\\s+(\\d+)\\s+cards?\\s+from\\s+your\\s+hand\\s+at\\s+the\\s+bottom\\s+of\\s+your\\s+deck[.!]?"
    );

    /**
     * Matches "pay 《Element》[…]. When you do so, [followup]."
     * Used when an auto-ability's effect text begins with an explicit CP payment followed by
     * a conditional effect clause.
     * Groups: {@code cost} — the raw CP token(s); {@code followup} — the effect text after the condition.
     */
    private static final Pattern PAY_CP_WHEN_DO_SO = Pattern.compile(
        "(?i)^\\s*pay\\s+(?<cost>(?:《[^》]+》\\s*)+)[.!]?\\s+When\\s+you\\s+do\\s+so[,.]?\\s+(?<followup>.+)$",
        Pattern.DOTALL
    );

    private static final Pattern DRAW_DISCARD_RETRIGGER_IF_CARD_NAME = Pattern.compile(
        "(?i)^Draw\\s+(?<draw>\\d+)\\s+cards?\\s+then\\s+discard\\s+(?<discard>\\d+)\\s+cards?[.!]?\\s+" +
        "If\\s+you\\s+discard\\s+a\\s+Card\\s+Name\\s+(?<name>.+?)\\s+by\\s+this\\s+effect,\\s+" +
        "trigger\\s+this\\s+auto-ability\\s+again[.!]?\\s*$"
    );

    private static final Pattern DRAW_CARDS = Pattern.compile(
        "(?i)^Draw\\s+(\\d+)\\s+cards?(?:\\s*[,.]?\\s*then\\s+discard\\s+(\\d+)\\s+cards?)?[.!]?"
    );

    /**
     * Matches "Discard N card(s)[,] then draw M card(s)".
     * <ul>
     *   <li>Group 1 — number of cards to discard</li>
     *   <li>Group 2 — number of cards to draw afterward</li>
     * </ul>
     */
    private static final Pattern DISCARD_THEN_DRAW = Pattern.compile(
        "(?i)^Discard\\s+(\\d+)\\s+cards?[,.]?\\s+then\\s+draw\\s+(\\d+)\\s+cards?[.!]?"
    );

    /**
     * Matches "&lt;subject&gt; deals your opponent N point(s) of damage."
     * <ul>
     *   <li>Group {@code amount} — number of damage points dealt to the opponent player</li>
     * </ul>
     */
    private static final Pattern DEAL_PLAYER_DAMAGE_TO_OPPONENT = Pattern.compile(
        "(?i).+?\\s+deals?\\s+your\\s+opponent\\s+(?<amount>\\d+)\\s+points?\\s+of\\s+damage[.!]?"
    );

    /**
     * Matches "&lt;subject&gt; deals you N point(s) of damage." or "receive N point(s) of damage."
     * <ul>
     *   <li>Group {@code amount} — number of damage points dealt to the ability user</li>
     * </ul>
     */
    private static final Pattern DEAL_PLAYER_DAMAGE_TO_SELF = Pattern.compile(
        "(?i)(?:.+?\\s+deals?\\s+you|receive)\\s+(?<amount>\\d+)\\s+points?\\s+of\\s+damage[.!]?"
    );

    /**
     * Matches: "Deal X damage to all [the] [condition] Forwards [of cost N [or less|more]] [other than Job Y] [opponent controls]"
     * <ul>
     *   <li>Group {@code amount}     — numeric damage value</li>
     *   <li>Group {@code condition}  — optional "damaged", "dull", "attacking", or "blocking"</li>
     *   <li>Group {@code cost}       — optional cost filter value</li>
     *   <li>Group {@code costcmp}    — optional comparison: "less" or "more"</li>
     *   <li>Group {@code excludejob} — optional job name to exclude, from "other than Job Y"</li>
     *   <li>Group {@code opponent}   — present when "opponent controls" appears</li>
     * </ul>
     */
    private static final Pattern DEAL_DAMAGE_TO_FORWARDS = Pattern.compile(
        "(?i)Deal\\s+(?<amount>\\d+)\\s+damage\\s+to\\s+all(?:\\s+the)?\\s+" +
        "(?:(?<condition>damaged|dull|attacking|blocking|active)\\s+)?" +
        "Forwards?" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+other\\s+than\\s+Job\\s+(?<excludejob>.+?)(?=\\s+(?:your\\s+)?opponent\\s+controls\\b|[.!]?$))?" +
        "(?:\\s+(?<opponent>(?:your\\s+)?opponent\\s+controls))?" +
        "[.!]?"
    );

    /** Matches "Deal N damage to [all] Forwards of all Elements except [Element]." */
    private static final Pattern DEAL_DAMAGE_TO_FORWARDS_EXCEPT_ELEMENT = Pattern.compile(
        "(?i)Deal\\s+(?<amount>\\d+)\\s+damage\\s+to\\s+(?:all(?:\\s+the)?\\s+)?Forwards?\\s+" +
        "of\\s+all\\s+Elements?\\s+except\\s+(?<excludeelem>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)[.!]?"
    );

    /**
     * Matches "Remove from the game all the Forwards on the field other than [elem1] and [elem2].
     * Then, remove from the top of your deck twice the number of cards removed by the previous effect."
     * Groups: {@code elem1}, {@code elem2}.
     */
    private static final Pattern RFP_ALL_FWD_EXCEPT_ELEMENTS_THEN_TWICE_DECK = Pattern.compile(
        "(?i)Remove\\s+from\\s+(?:the\\s+)?game\\s+all\\s+(?:the\\s+)?Forwards?\\s+on\\s+(?:the\\s+)?field\\s+" +
        "other\\s+than\\s+(?<elem1>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+and\\s+(?<elem2>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)[.!]?\\s*" +
        "Then,?\\s+remove\\s+from\\s+the\\s+top\\s+of\\s+your\\s+deck\\s+twice\\s+the\\s+number\\s+of\\s+cards\\s+removed\\s+by\\s+(?:the\\s+)?previous\\s+effect[.!]?"
    );

    /** Matches "No Forward of cost N or less/more can attack this turn." */
    private static final Pattern NO_FORWARD_COST_CANNOT_ATTACK = Pattern.compile(
        "(?i)No\\s+Forward(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?\\s+can\\s+attack\\s+this\\s+turn[.!]?"
    );

    /** Matches "During this turn, the Forwards you control cannot be chosen by EX Bursts." */
    private static final Pattern OWN_FORWARDS_CANNOT_BE_CHOSEN_BY_EX_BURST = Pattern.compile(
        "(?i)During\\s+this\\s+turn,?\\s+the\\s+Forwards?\\s+you\\s+control\\s+cannot\\s+be\\s+chosen\\s+by\\s+EX\\s+Bursts?[.!]?"
    );

    /** Matches "EX Bursts of cards put into the Damage Zone due to this ability cannot be used." */
    private static final Pattern EX_BURST_SUPPRESSION_PATTERN = Pattern.compile(
        "(?i)EX\\s+Bursts?\\s+of\\s+cards?\\s+put\\s+into\\s+the\\s+Damage\\s+Zone\\s+due\\s+to\\s+this\\s+ability\\s+cannot\\s+be\\s+used[.!]?"
    );

    /**
     * Alternate word order: "Deal all [the] [condition] Forwards [of cost N] [other than Job Y] [opponent controls] X damage."
     * Same named groups as {@link #DEAL_DAMAGE_TO_FORWARDS} so {@link #tryParseDealDamageToForwards} can share extraction logic.
     */
    private static final Pattern DEAL_DAMAGE_TO_FORWARDS_ALT = Pattern.compile(
        "(?i)Deal\\s+all(?:\\s+the)?\\s+" +
        "(?:(?<condition>damaged|dull|attacking|blocking|active)\\s+)?" +
        "Forwards?" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+other\\s+than\\s+Job\\s+(?<excludejob>.+?)(?=\\s+(?:your\\s+)?opponent\\s+controls\\b|\\s+\\d+\\s+damage))?" +
        "(?:\\s+(?<opponent>(?:your\\s+)?opponent\\s+controls))?" +
        "\\s+(?<amount>\\d+)\\s+damage[.!]?"
    );

    /**
     * Matches: "Deal X damage for each [Element]? [Category Y]? Type you control to all [the] Forwards [opponent controls]"
     * <ul>
     *   <li>Group {@code base}      — base damage per matching card</li>
     *   <li>Group {@code element}   — optional element filter ("Wind", "Fire", etc.)</li>
     *   <li>Group {@code category}  — optional category filter</li>
     *   <li>Group {@code chartype}  — Forwards/Backups/Monsters/Characters</li>
     *   <li>Group {@code condition} — optional "damaged"/"dull"/etc. target filter</li>
     *   <li>Group {@code opponent}  — present when "opponent controls" appears</li>
     * </ul>
     */
    private static final Pattern DEAL_DAMAGE_TO_FORWARDS_FOR_EACH = Pattern.compile(
        "(?i)Deal\\s+(?<base>\\d+)\\s+damage\\s+for\\s+each\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?<chartype>Forwards?|Characters?|Backups?|Monsters?)\\s+" +
        "(?:(?<oppcount>(?:your\\s+)?opponent\\s+controls)|you\\s+control)" +
        "\\s+to\\s+all(?:\\s+the)?\\s+" +
        "(?:(?<condition>damaged|dull|attacking|blocking|active)\\s+)?" +
        "Forwards?" +
        "(?:\\s+(?<opponent>(?:your\\s+)?opponent\\s+controls))?" +
        "[.!]?"
    );

    /**
     * Matches "For each Job [job] and[/or] Card [Nn]ame [name] you control, deal N damage to all Forwards [opponent controls]."
     * Groups: {@code job}, {@code cardname}, {@code amount}, {@code opponent}.
     */
    private static final Pattern FOR_EACH_JOB_AND_NAME_DEAL_DAMAGE_TO_FORWARDS = Pattern.compile(
        "(?i)^For\\s+each\\s+Job\\s+(?<job>.+?)\\s+and(?:/or)?\\s+Card\\s+[Nn]ame\\s+(?<cardname>.+?)\\s+you\\s+control,?\\s+" +
        "deal\\s+(?<amount>\\d+)\\s+damage\\s+to\\s+all(?:\\s+the)?\\s+Forwards?" +
        "(?:\\s+(?<opponent>(?:your\\s+)?opponent\\s+controls))?[.!]?$"
    );

    /**
     * Matches "deal N damage for each Job X or [a] Card Name Y you control to all [the] Forwards opponent controls."
     * Groups: {@code amount}, {@code job}, {@code cardname}.
     */
    private static final Pattern DEAL_N_FOR_EACH_JOB_OR_NAME_TO_OPP_FORWARDS = Pattern.compile(
        "(?i)deal\\s+(?<amount>\\d+)\\s+damage\\s+for\\s+each\\s+" +
        "Job\\s+(?<job>.+?)\\s+or\\s+(?:a\\s+)?Card\\s+[Nn]ame\\s+(?<cardname>.+?)\\s+you\\s+control\\s+" +
        "to\\s+all\\s+(?:the\\s+)?Forwards?(?:\\s+(?:your\\s+)?opponent\\s+controls)?[.!]?$"
    );

    /**
     * Matches "deal N damage and M more damage for each Card Name [name] in your Break Zone
     * to all [the] Forwards [opponent controls]."
     * Groups: {@code base} — fixed base damage; {@code per} — additional per copy; {@code cardname} — name filter;
     * {@code opponent} — present when "opponent controls" appears.
     */
    private static final Pattern DEAL_BASE_PLUS_BZ_NAME_DAMAGE_TO_FORWARDS = Pattern.compile(
        "(?i)^deal\\s+(?<base>\\d+)\\s+damage\\s+and\\s+(?<per>\\d+)\\s+more\\s+damage\\s+" +
        "for\\s+each\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+in\\s+your\\s+Break\\s+Zone\\s+" +
        "to\\s+all(?:\\s+the)?\\s+Forwards?" +
        "(?:\\s+(?<opponent>(?:your\\s+)?opponent\\s+controls))?[.!]?$"
    );

    /**
     * Matches "Until the end of the turn, [CardName] gains 'When [CardName] attacks, [innerEffect]'"
     * — grants the source card a temporary attack trigger for this turn.
     * Group {@code inner} — the effect text inside the quoted auto-ability.
     */
    private static final Pattern SELF_GAINS_WHEN_ATTACKS_EOT = Pattern.compile(
        "(?i)^Until\\s+the\\s+end\\s+of\\s+(?:the\\s+)?turn,?\\s+.+?\\s+gains?\\s+\"When\\s+.+?\\s+attacks?,\\s+(?<inner>.+?)\"[.!]?$"
    );

    /**
     * Matches "Deal [N] damage to the Forward that blocks [CardName][.]"
     * Used by "is blocked" auto-abilities and action abilities that target the current combat blocker.
     * <ul>
     *   <li>Group {@code amount} — fixed damage value</li>
     *   <li>Group {@code name}   — name of the card being blocked</li>
     * </ul>
     */
    private static final Pattern DAMAGE_TO_COMBAT_BLOCKER = Pattern.compile(
        "(?i)Deal\\s+(?<amount>\\d+)\\s+damage\\s+to\\s+the\\s+Forward\\s+that\\s+blocks?\\s+(?<name>.+?)[.!]?$"
    );

    /**
     * Matches "Deal each [condition] Forward[s] [opponent controls] damage equal to half of its power
     * [(round up to the nearest 1000)]."
     * <ul>
     *   <li>Group {@code condition} — optional "damaged", "dull", "attacking", or "blocking"</li>
     *   <li>Group {@code opponent}  — present when "opponent controls" appears</li>
     * </ul>
     */
    private static final Pattern DEAL_HALF_POWER_DAMAGE_TO_FORWARDS = Pattern.compile(
        "(?i)Deal\\s+each(?:\\s+the)?\\s+" +
        "(?:(?<condition>damaged|dull|attacking|blocking)\\s+)?" +
        "Forwards?\\s+" +
        "(?<opponent>(?:your\\s+)?opponent\\s+controls\\s+)?" +
        "damage\\s+equal\\s+to\\s+half\\s+of\\s+its\\s+power" +
        "(?:\\s*\\(\\s*round\\s+up\\s+to\\s+the\\s+nearest\\s+1000\\s*\\))?" +
        "[.!]?"
    );

    /**
     * Matches "Deal each [condition] Forward[s] [opponent controls] damage equal to its power minus N."
     * Groups: {@code condition}, {@code opponent}, {@code amount}.
     */
    private static final Pattern DEAL_POWER_MINUS_N_DAMAGE_TO_FORWARDS = Pattern.compile(
        "(?i)Deal\\s+each(?:\\s+the)?\\s+" +
        "(?:(?<condition>damaged|dull|attacking|blocking)\\s+)?" +
        "Forwards?\\s+" +
        "(?<opponent>(?:your\\s+)?opponent\\s+controls\\s+)?" +
        "damage\\s+equal\\s+to\\s+its\\s+power\\s+minus\\s+(?<amount>\\d+)" +
        "[.!]?"
    );

    /**
     * Matches "Deal damage equal to half of [name]'s power to all [the] [condition] Forward[s]
     * [opponent controls] [(round up/down to the nearest 1000)]."
     * <ul>
     *   <li>Group {@code sourcename} — name of the card whose power determines damage</li>
     *   <li>Group {@code condition}  — optional "damaged", "dull", "attacking", or "blocking"</li>
     *   <li>Group {@code opponent}   — present when "opponent controls" appears</li>
     *   <li>Group {@code rounding}   — "up" or "down" (absent defaults to round down)</li>
     * </ul>
     */
    private static final Pattern DEAL_HALF_SOURCE_POWER_DAMAGE_TO_FORWARDS = Pattern.compile(
        "(?i)Deal\\s+damage\\s+equal\\s+to\\s+half\\s+of\\s+(?<sourcename>.+?)'s\\s+power\\s+" +
        "to\\s+all(?:\\s+the)?\\s+" +
        "(?:(?<condition>damaged|dull|attacking|blocking)\\s+)?" +
        "Forwards?\\s*" +
        "(?<opponent>(?:your\\s+)?opponent\\s+controls)?\\s*" +
        "(?:\\(\\s*round\\s+(?<rounding>up|down)[^)]*\\))?\\s*" +
        "[.!]?"
    );

    /**
     * Matches "During this turn, the cost required to cast your next [filter] is reduced by N
     * [(it cannot become 0)][.]"
     * <ul>
     *   <li>{@code element}  — optional element qualifier (e.g. "Wind")</li>
     *   <li>{@code category} — optional Category qualifier (e.g. "XIII")</li>
     *   <li>{@code job}      — optional Job qualifier (e.g. "Knight")</li>
     *   <li>{@code cardname} — specific card name (alternative to {@code type})</li>
     *   <li>{@code type}     — card type: Forward(s)/Backup(s)/Monster(s)/Summon(s)/card</li>
     *   <li>{@code amount}   — numeric reduction</li>
     *   <li>{@code floorone} — present when "(it cannot become 0)" clause is present</li>
     * </ul>
     */
    private static final Pattern COST_REDUCTION_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+cost\\s+required\\s+to\\s+cast\\s+your\\s+next\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?:" +
            // Combined "Job X or Card Name Y" — captured with OR semantics in the modifier
            "Job\\s+(?<joborg>.+?)\\s+(?:and/)?or\\s+Card\\s+Name\\s+(?<cnameborg>\\S+)" +
            // Existing: optional job then card-name or type
            "|(?:Job\\s+(?<job>.+?)\\s+)?(?:Card\\s+Name\\s+(?<cardname>\\S+)|(?<type>Forwards?|Backups?|Monsters?|Summons?|card))" +
        ")\\s+" +
        "is\\s+reduced\\s+by\\s+(?<amount>\\d+)" +
        "(?<floorone>\\s*\\(it\\s+cannot\\s+become\\s+0\\))?[.!]?"
    );

    /**
     * Matches "The cost required to play your [filter] onto the field this turn is reduced by N
     * [(it cannot become 0)][.]" — applies to all matching plays this turn (not consumed on use).
     */
    private static final Pattern PLAY_COST_REDUCTION_THIS_TURN = Pattern.compile(
        "(?i)The\\s+cost\\s+required\\s+to\\s+(?:play|cast)\\s+your\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?:Job\\s+(?<job>.+?)\\s+)?" +
        "(?:Card\\s+Name\\s+(?<cardname>\\S+)|(?<type>Forwards?|Backups?|Monsters?|Characters?))\\s+" +
        "(?:onto\\s+the\\s+field\\s+)?this\\s+turn\\s+is\\s+reduced\\s+by\\s+(?<amount>\\d+)" +
        "(?<floorone>\\s*\\(it\\s+cannot\\s+become\\s+0\\))?[.!]?"
    );

    /**
     * Matches "Choose 1 Summon in your Break Zone. Add it to your hand. During this turn,
     * the cost required to cast your next Summon is reduced by N [(it cannot become 0)]."
     * <ul>
     *   <li>Group {@code amount}   — cost reduction</li>
     *   <li>Group {@code floorone} — present when "(it cannot become 0)" clause appears</li>
     * </ul>
     */
    private static final Pattern CHOOSE_SUMMON_FROM_BZ_TO_HAND_WITH_COST_REDUCTION = Pattern.compile(
        "(?i)Choose\\s+1\\s+Summon\\s+in\\s+your\\s+Break\\s+Zone[.!]?\\s+" +
        "Add\\s+it\\s+to\\s+your\\s+hand[.!]?\\s+" +
        "During\\s+this\\s+turn,?\\s+the\\s+cost\\s+required\\s+to\\s+cast\\s+your\\s+next\\s+Summon\\s+" +
        "is\\s+reduced\\s+by\\s+(?<amount>\\d+)" +
        "(?<floorone>\\s*\\(it\\s+cannot\\s+become\\s+0\\))?[.!]?\\s*$"
    );

    /**
     * Matches "Choose N Summons in your Break Zone. Add 1 of them to your hand, and remove the rest from the game."
     * Group {@code total} — number of Summons to choose.
     */
    private static final Pattern CHOOSE_N_SUMMONS_BZ_PICK_ONE_HAND_REST_RFG = Pattern.compile(
        "(?i)Choose\\s+(?<total>\\d+)\\s+Summons?\\s+in\\s+your\\s+Break\\s+Zone[.!]?\\s+" +
        "Add\\s+1\\s+of\\s+them\\s+to\\s+your\\s+hand[,.]?(?:\\s+and)?\\s+remove\\s+the\\s+rest\\s+from\\s+the\\s+game[.!]?\\s*$"
    );

    /**
     * Matches "Choose 1 [Element] Summon in your Break Zone. You can cast it at any time you
     * could normally cast it this turn. The cost required to cast it is reduced by N."
     * Used by abilities that "borrow" a Summon from the Break Zone for one extra cast.
     * <ul>
     *   <li>Group {@code element} — required element of the chosen Summon</li>
     *   <li>Group {@code amount}  — cost reduction applied to that Summon's next cast</li>
     * </ul>
     */
    private static final Pattern CHOOSE_SUMMON_IN_BZ_CASTABLE = Pattern.compile(
        "(?i)Choose\\s+1\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Summon\\s+in\\s+your\\s+Break\\s+Zone[.!]?\\s+" +
        "You\\s+can\\s+cast\\s+it\\s+at\\s+any\\s+time\\s+you\\s+could\\s+normally\\s+cast\\s+it\\s+this\\s+turn[.!]?\\s+" +
        "The\\s+cost\\s+required\\s+to\\s+cast\\s+it\\s+is\\s+reduced\\s+by\\s+(?<amount>\\d+)[.!]?"
    );

    /**
     * "Your opponent removes the top card of their deck from the game [face down]. You can [look at
     * it and/or] cast it as though you owned it at any time you could normally cast it. The cost for
     * casting it [is reduced by N and] can be paid using CP of any Element." (Lani 12-018H, Zidane 16-048H)
     */
    private static final Pattern OPP_RFP_TOPDECK_CASTABLE = Pattern.compile(
        "(?is)your\\s+opponent\\s+removes\\s+the\\s+top\\s+card\\s+of\\s+their\\s+deck\\s+from\\s+the\\s+game(?:\\s+face\\s+down)?[.!]?\\s+" +
        "You\\s+can\\s+(?:look\\s+at\\s+it\\s+and/or\\s+)?cast\\s+it\\s+as\\s+though\\s+you\\s+owned\\s+it\\s+at\\s+any\\s+time\\s+you\\s+could\\s+normally\\s+cast\\s+it[.!]?" +
        "(?<cost>.*)$"
    );

    /**
     * "Choose 1 [Forward|Backup|Monster|Character] in your opponent's Break Zone. Remove it from the
     * game. [During this game,] you can cast it as though you owned it at any time you could normally
     * cast it." (Bel Dat 20-056H — Forward; Zidane 24-044H — Character)
     */
    private static final Pattern CHOOSE_FROM_OPP_BZ_CASTABLE = Pattern.compile(
        "(?is)Choose\\s+1\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+in\\s+your\\s+opponent'?s\\s+Break\\s+Zone[.!]?\\s+" +
        "Remove\\s+it\\s+from\\s+the\\s+game[.!]?\\s+" +
        "(?:During\\s+this\\s+game,?\\s+)?[Yy]ou\\s+can\\s+cast\\s+it\\s+as\\s+though\\s+you\\s+owned\\s+it\\s+at\\s+any\\s+time\\s+you\\s+could\\s+normally\\s+cast\\s+it[.!]?"
    );

    /**
     * "Choose N Summon(s) from [your and/or your opponent's|either player's|your] Break Zone. Remove
     * them from the game. During this game, you can cast them as though you owned them ..." (Shantotto 23-067R)
     */
    private static final Pattern CHOOSE_SUMMONS_FROM_BZ_GAME = Pattern.compile(
        "(?is)[Cc]hoose\\s+(?<count>\\d+)\\s+Summons?\\s+from\\s+(?<scope>your\\s+and/or\\s+your\\s+opponent'?s|either\\s+player'?s|your\\s+opponent'?s|your)\\s+Break\\s+Zone[.!]?\\s+" +
        "Remove\\s+(?:it|them)\\s+from\\s+the\\s+game[.!]?\\s+" +
        "During\\s+this\\s+game,?\\s+you\\s+can\\s+cast\\s+(?:it|them)\\s+as\\s+though\\s+you\\s+owned\\s+(?:it|them).*"
    );

    /**
     * "Choose N Summon(s) from [either player's|your and/or your opponent's|your] Break Zone. You can
     * cast it as though you owned it this turn. [If you cast it, remove that Summon from the game after
     * use instead of putting it in the Break Zone.]" (Krile 12-061L)
     */
    private static final Pattern CHOOSE_SUMMONS_FROM_BZ_TURN = Pattern.compile(
        "(?is)[Cc]hoose\\s+(?<count>\\d+)\\s+Summons?\\s+from\\s+(?<scope>your\\s+and/or\\s+your\\s+opponent'?s|either\\s+player'?s|your\\s+opponent'?s|your)\\s+Break\\s+Zone[.!]?\\s+" +
        "You\\s+can\\s+cast\\s+(?:it|them)\\s+as\\s+though\\s+you\\s+owned\\s+(?:it|them)\\s+this\\s+turn[.!]?" +
        "(?<rfg>.*)$"
    );

    /**
     * "Choose 1 Summon of cost N or less in your Break Zone. Cast it without paying the cost.
     * Remove that Summon from the game after use instead of putting it in the Break Zone."
     */
    private static final Pattern CHOOSE_SUMMON_IN_BZ_MAX_COST_FREE_CAST_RFG = Pattern.compile(
        "(?is)Choose\\s+1\\s+Summon\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+less\\s+in\\s+your\\s+Break\\s+Zone[.!]?\\s+" +
        "Cast\\s+it\\s+without\\s+paying\\s+the\\s+cost[.!]?\\s+" +
        "Remove\\s+that\\s+Summon\\s+from\\s+the\\s+game\\s+after\\s+use\\s+instead\\s+of\\s+putting\\s+it\\s+in\\s+the\\s+Break\\s+Zone[.!]?"
    );

    /**
     * "Choose 1 Forward with N power or less and up to 1 Forward in your opponent's Break Zone.
     * Remove them from the game."
     */
    private static final Pattern CHOOSE_FWD_POWER_LE_AND_OPT_OPP_BZ_FWD_RFP = Pattern.compile(
        "(?i)Choose\\s+1\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power\\s+or\\s+less" +
        "\\s+and\\s+up\\s+to\\s+1\\s+Forward\\s+in\\s+your\\s+opponent(?:'s)?\\s+Break\\s+Zone[.!]?\\s+" +
        "Remove\\s+them\\s+from\\s+(?:the\\s+)?game[.!]?"
    );

    /** Matches "Take 1 more turn after this one. At the end of that turn, you lose the game." */
    private static final Pattern EXTRA_TURN_THEN_LOSE = Pattern.compile(
        "(?i)Take\\s+1\\s+more\\s+turn\\s+after\\s+this\\s+one[.!]?\\s+" +
        "At\\s+the\\s+end\\s+of\\s+that\\s+turn,\\s+you\\s+lose\\s+the\\s+game[.!]?"
    );

    /**
     * Matches "Until the end of the turn, all the Monsters you control also become Forwards with N power."
     * Group {@code power} captures the power value.
     */
    private static final Pattern ALL_MONSTERS_BECOME_FORWARDS_UNTIL_EOT_PATTERN = Pattern.compile(
        "(?i)^Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,?\\s+" +
        "all\\s+(?:the\\s+)?Monsters?\\s+you\\s+control\\s+also\\s+become\\s+Forwards?\\s+with\\s+(?<power>\\d+)\\s+power[.!]?"
    );

    /**
     * Matches "Until the end of the turn, [CardName] also becomes a Forward with N power."
     * Used for action abilities on Monsters.  Group {@code power} captures the power value.
     */
    private static final Pattern BECOME_FORWARD_UNTIL_EOT_PATTERN = Pattern.compile(
        "(?i)^Until\\s+the\\s+end\\s+of\\s+the\\s+turn,\\s+.+?\\s+also\\s+becomes?\\s+a\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power"
    );

    /**
     * Extended form: "…becomes a Forward with N power and "Put [name] into the Break Zone: [effect]"."
     * Groups: {@code power}, {@code bzName}, {@code bzEffect}.
     */
    private static final Pattern BECOME_FORWARD_AND_BZ_ACTION = Pattern.compile(
        "(?i)^Until\\s+the\\s+end\\s+of\\s+the\\s+turn,\\s+.+?\\s+also\\s+becomes?\\s+a\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power" +
        "\\s+and\\s+\"Put\\s+(?<bzName>.+?)\\s+into\\s+the\\s+Break\\s+Zone:\\s+(?<bzEffect>[^\"]+?)\"\\s*[.!]?",
        Pattern.DOTALL
    );

    /**
     * Extended form: "…becomes a Forward with N power and "When [name] attacks, [effect]"."
     * Groups: {@code power}, {@code attackEffect}.
     */
    private static final Pattern BECOME_FORWARD_AND_ATTACK_TRIGGER = Pattern.compile(
        "(?i)^Until\\s+the\\s+end\\s+of\\s+the\\s+turn,\\s+.+?\\s+also\\s+becomes?\\s+a\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power" +
        "\\s+and\\s+\"When\\s+[^\"]+?\\s+attacks?\\s*,\\s+(?<attackEffect>[^\"]+?)\"\\s*[.!]?",
        Pattern.DOTALL
    );

    /**
     * Extended form: "…becomes a Forward with N power and "When [name] blocks or is blocked, [effect]"."
     * Groups: {@code power}, {@code blockEffect}.
     */
    private static final Pattern BECOME_FORWARD_AND_BLOCK_TRIGGER = Pattern.compile(
        "(?i)^Until\\s+the\\s+end\\s+of\\s+the\\s+turn,\\s+.+?\\s+also\\s+becomes?\\s+a\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power" +
        "\\s+and\\s+\"When\\s+[^\"]+?\\s+blocks?(?:\\s+or\\s+is\\s+blocked)?\\s*,\\s+(?<blockEffect>[^\"]+?)\"\\s*[.!]?",
        Pattern.DOTALL
    );

    /**
     * Matches "If the CP paid to cast [Name] was only produced by Backups, [also] draw N card(s)."
     * Group {@code count} — number of cards to draw.
     */
    private static final Pattern BACKUP_CP_DRAW = Pattern.compile(
        "(?i)If\\s+the\\s+CP\\s+paid\\s+to\\s+cast\\s+.+?\\s+was\\s+only\\s+produced\\s+by\\s+Backups?," +
        "\\s+(?:also\\s+)?draw\\s+(?<count>\\d+)\\s+cards?[.!]?"
    );

    /**
     * Matches "If your opponent has [no | N cards or less] cards in his/her hand, [effect][ instead][.!]?"
     * <ul>
     *   <li>{@code n}       — numeric threshold; absent when the condition is "no cards" (threshold = 0)</li>
     *   <li>{@code effect}  — the conditional inner effect text</li>
     *   <li>{@code instead} — present when "instead" immediately follows the effect</li>
     * </ul>
     */
    private static final Pattern OPPONENT_HAND_CONDITION_PATTERN = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+has\\s+" +
        "(?:no|(?<n>\\d+)\\s+cards?\\s+or\\s+less)\\s+cards?\\s+in\\s+" +
        "(?:his/her|his|her|their)\\s+hand,?\\s*" +
        "(?<effect>.+?)" +
        "(?<instead>\\s+instead)?[.!]?$"
    );

    /**
     * Matches "If your opponent has N cards or more in their hand, [effect]."
     * Fires the inner effect only when the opponent's hand meets the minimum threshold.
     * Groups: {@code n} — minimum hand size; {@code effect} — the conditional effect text.
     */
    private static final Pattern OPPONENT_HAND_MIN_CONDITION_PATTERN = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+has\\s+(?<n>\\d+)\\s+cards?\\s+or\\s+more\\s+in\\s+" +
        "(?:his/her|his|her|their)\\s+hand,?\\s*" +
        "(?<effect>.+?)\\s*[.!]?$"
    );

    /**
     * Matches a two-clause hand condition used as a Choose followup:
     * "If your opponent has N cards or less …, [action1]. If your opponent has no cards …, [action2] instead."
     * <ul>
     *   <li>{@code n}       — upper threshold for the relaxed condition</li>
     *   <li>{@code effect1} — action applied when 0 &lt; handSize ≤ N</li>
     *   <li>{@code effect2} — action applied when handSize == 0 (overrides effect1)</li>
     * </ul>
     */
    private static final Pattern OPPONENT_HAND_DOUBLE_CONDITION_PATTERN = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+has\\s+(?<n>\\d+)\\s+cards?\\s+or\\s+less\\s+cards?\\s+in\\s+" +
        "(?:his/her|his|her|their)\\s+hand,?\\s*(?<effect1>.+?)[.!]\\s+" +
        "If\\s+your\\s+opponent\\s+has\\s+no\\s+cards?\\s+in\\s+" +
        "(?:his/her|his|her|their)\\s+hand,?\\s*(?<effect2>.+?)\\s+instead[.!]?$"
    );

    /**
     * Matches "If your opponent controls N or more Forwards, deal it/them X damage[.!]?"
     * as a choose-character followup or standalone conditional effect.
     * <ul>
     *   <li>{@code count}  — minimum number of opponent Forwards required</li>
     *   <li>{@code amount} — damage to deal when the condition is met</li>
     * </ul>
     */
    private static final Pattern FOLLOWUP_IF_OPPONENT_CONTROLS_FORWARDS_DAMAGE = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+controls\\s+(?<count>\\d+)\\s+or\\s+more\\s+Forwards?,\\s+" +
        "deal\\s+(?:it|them)\\s+(?<amount>\\d+)\\s+damage[.!]?$"
    );

    /**
     * Matches "If you control N or more [Element] [Type], deal it/them X damage[.!]?"
     * as a choose-character followup.
     * <ul>
     *   <li>{@code count}   — minimum number of own field cards required</li>
     *   <li>{@code element} — optional element filter (e.g. "Fire"); absent = any</li>
     *   <li>{@code type}    — card type: Forward(s), Backup(s), Monster(s), Character(s), Summon(s)</li>
     *   <li>{@code amount}  — damage to deal when the condition is met</li>
     * </ul>
     */
    private static final Pattern FOLLOWUP_IF_SELF_CONTROLS_N_ELEMENT_TYPE_DAMAGE = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<count>\\d+)\\s+or\\s+more\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<type>Forwards?|Backups?|Monsters?|Characters?|Summons?),?\\s+" +
        "deal\\s+(?:it|them)\\s+(?<amount>\\d+)\\s+damage[.!]?$"
    );

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Attempts to parse {@code effectText} into a ready-to-execute
     * {@link Consumer}{@code <GameContext>}.
     *
     * @return the effect consumer, or {@code null} if the text is not yet supported
     */
    public static Consumer<GameContext> parse(String effectText) {
        return parse(effectText, null, 0);
    }

    /**
     * Attempts to parse {@code effectText} into a ready-to-execute
     * {@link Consumer}{@code <GameContext>}.
     *
     * @param source the card that owns this ability; required for standalone self-buff effects
     * @return the effect consumer, or {@code null} if the text is not yet supported
     */
    public static Consumer<GameContext> parse(String effectText, CardData source) {
        return parse(effectText, source, 0);
    }

    /**
     * @param xValue the CP amount paid into {@code 《X》}; {@code 0} when the ability has no X cost
     */
    public static Consumer<GameContext> parse(String effectText, CardData source, int xValue) {
        // Strip leading "EX BURST" / "[[ex]]EX BURST[[/]]" prefix present on summon field ability texts.
        effectText = effectText.replaceFirst("(?i)^(?:\\[\\[ex\\]\\])?\\s*EX\\s+BURST(?:\\[\\[/\\]\\])?\\s*", "").trim();
        // Strip leading "Then, " connector that appears when this text is a secondary clause.
        effectText = effectText.replaceFirst("(?i)^Then,?\\s+", "").trim();
        Consumer<GameContext> result;

        // "Cast it as though you owned it" family — matched early because the highly specific
        // borrowed-cast phrasing would otherwise be intercepted by generic Choose/Remove matchers.
        result = tryParseOppRfpTopDeckCastable(effectText);
        if (result != null) return result;

        result = tryParseChooseFromOppBzCastable(effectText);
        if (result != null) return result;

        result = tryParseChooseSummonsFromBzCastable(effectText);
        if (result != null) return result;

        result = tryParseChooseSummonInBzMaxCostFreeCastRfg(effectText);
        if (result != null) return result;

        result = tryParseSelectFollowingActions(effectText, source);
        if (result != null) return result;

        // Must precede tryParseWhenYouDoSoSequence: Zidane-style text contains "If you do so"
        // which that parser would split, causing it to match first via OPPONENT_DRAW on the tail.
        result = tryParseRevealHandOptPickRfpOppDraw(effectText);
        if (result != null) return result;

        result = tryParseWhenYouDoSoSequence(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfOwnForwardFormedParty(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfControlAtMost(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfAllHaveElement(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfNDiffElements(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfControlCondOtherThan(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseControlConditionGate(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseOpponentControlsCardGate(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfOppControlsNOrMoreCondTypeGate(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseDiscardConditionalElement(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfCastAtLeast(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseSelectNumber(effectText, source);
        if (result != null) return result;

        result = tryParseAllMonstersTemporaryForward(effectText);
        if (result != null) return result;

        result = tryParseBecomeForwardUntilEot(effectText, source);
        if (result != null) return result;

        result = tryParseForEachJobAndNameDealDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseDealNForEachJobOrNameToOppForwards(effectText);
        if (result != null) return result;

        result = tryParseDealBasePlusBzNameDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseSelfGainsWhenAttacksEOT(effectText, source);
        if (result != null) return result;

        result = tryParseDealDamageToForwardsForEach(effectText);
        if (result != null) return result;

        result = tryParseDealDamageToForwardsExceptElement(effectText);
        if (result != null) return result;

        result = tryParseRfpAllFwdExceptElementsThenTwiceDeck(effectText);
        if (result != null) return result;

        result = tryParseDealDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseNoForwardCostCannotAttack(effectText);
        if (result != null) return result;

        result = tryParseOwnForwardsCannotBeChosenByExBurst(effectText);
        if (result != null) return result;

        result = tryParseExBurstSuppression(effectText);
        if (result != null) return result;

        result = tryParseDealHalfPowerDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseDealPowerMinusNDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseDealHalfSourcePowerDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseDamageToCombatBlocker(effectText);
        if (result != null) return result;

        result = tryParseChooseOneEach(effectText, source);
        if (result != null) return result;

        result = tryParseChooseForwardRedirectToNamed(effectText);
        if (result != null) return result;

        result = tryParseChooseFormerLatter(effectText, source);
        if (result != null) return result;

        result = tryParseChooseFwdPowerLeAndOptOppBzFwdRfp(effectText);
        if (result != null) return result;

        result = tryParseChooseThreeMixedTypes(effectText, source);
        if (result != null) return result;

        result = tryParseChooseTwoMixedTypes(effectText, source);
        if (result != null) return result;

        result = tryParseChooseForwardDealSelfDamageBreakIfCostLeDamage(effectText);
        if (result != null) return result;

        result = tryParseChooseForwardSharedPowerLoss(effectText, source);
        if (result != null) return result;

        result = tryParseChooseOppFwdDynCostBreak(effectText);
        if (result != null) return result;

        result = tryParseChooseFwdPowerInferiorToSource(effectText, source);
        if (result != null) return result;

        result = tryParseChooseFwdBzCostInferiorToRemovedPlay(effectText);
        if (result != null) return result;

        result = tryParseChooseOppFwdGainsSpecialAbilityFreeOnce(effectText, source);
        if (result != null) return result;

        result = tryParseChooseOppDamagedFwdIfHasAbilityBreak(effectText);
        if (result != null) return result;

        result = tryParseChooseAsManyAsFieldCount(effectText, source);
        if (result != null) return result;

        result = tryParseChooseCounterScaleCharsActivate(effectText, xValue);
        if (result != null) return result;

        result = tryParseChooseAnyNumberReturnToHand(effectText);
        if (result != null) return result;

        result = tryParseChooseCharacter(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseEndOfEachTurnFieldAbility(effectText, source);
        if (result != null) return result;

        result = tryParseEndOfEachPlayersTurnIfSelfFwdDamage(effectText, source);
        if (result != null) return result;

        result = tryParseBeginningOfMainPhase1FieldAbility(effectText, source);
        if (result != null) return result;

        result = tryParseBeginningOfMainPhase2FieldAbility(effectText, source);
        if (result != null) return result;

        result = tryParseBeginningOfMainPhase1EachTurnFieldAbility(effectText, source);
        if (result != null) return result;

        result = tryParseEndOfOpponentTurnFieldAbility(effectText, source);
        if (result != null) return result;

        result = tryParseElementChange(effectText, source);
        if (result != null) return result;

        result = tryParseDelayedEffect(effectText);
        if (result != null) return result;

        result = tryParsePlayerCannotCastSummons(effectText);
        if (result != null) return result;

        result = tryParseCannotBeChosenStandalone(effectText, source);
        if (result != null) return result;

        result = tryParseCannotBecomeDullOpp(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneCannotAttackOrBlock(effectText, source);
        if (result != null) return result;

        result = tryParseNegateAllDamage(effectText);
        if (result != null) return result;

        result = tryParsePlayerNextDamageZero(effectText);
        if (result != null) return result;

        result = tryParseCancelAutoAbilityAndDamageIfForward(effectText);
        if (result != null) return result;

        result = tryParseRedirectAbilityTarget(effectText);
        if (result != null) return result;

        result = tryParseCancelAbilityOnStack(effectText);
        if (result != null) return result;

        result = tryParseCancelSummonTargetingMyCharacter(effectText);
        if (result != null) return result;

        result = tryParseCancelStackEntry(effectText);
        if (result != null) return result;

        result = tryParseDullAllOppFwdsPowerLeSource(effectText, source);
        if (result != null) return result;

        result = tryParseRevealTopBreakSameCostAddToHand(effectText);
        if (result != null) return result;

        result = tryParseAllFieldEffect(effectText);
        if (result != null) return result;

        result = tryParseFieldPowerGrantPassive(effectText);
        if (result != null) return result;

        result = tryParseAllForwardsSameElementAsNamedPowerBoost(effectText);
        if (result != null) return result;

        result = tryParseAllFieldPowerBoost(effectText);
        if (result != null) return result;

        result = tryParseAllFieldJobCardNamePowerBoost(effectText);
        if (result != null) return result;

        result = tryParseTwoCardNamesPowerBoost(effectText);
        if (result != null) return result;

        result = tryParseAllFieldJobPowerBoost(effectText);
        if (result != null) return result;

        result = tryParseAllFieldJobKeywordGrant(effectText);
        if (result != null) return result;

        result = tryParseAllFieldKeywordGrant(effectText);
        if (result != null) return result;

        result = tryParseUntilEotDualPowerShift(effectText);
        if (result != null) return result;

        result = tryParseUntilEotAllFieldPowerBoost(effectText);
        if (result != null) return result;

        result = tryParseReturnAllToHand(effectText);
        if (result != null) return result;

        result = tryParseStandalonePowerBoostAndAttackTrigger(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneGainsTraitsAndCannotBeBlocked(effectText, source);
        if (result != null) return result;

        result = tryParseStandalonePowerBoostUntil(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneDoublePowerUntil(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneDoublesItsPowerUntil(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneDoublePowerMainPhaseNextTurn(effectText, source);
        if (result != null) return result;

        result = tryParseStandalonePowerReduceUntil(effectText, source);
        if (result != null) return result;

        result = tryParseFieldSelfPowerBoost(effectText, source);
        if (result != null) return result;

        result = tryParseDoubleOutgoingDamageThisTurn(effectText, source);
        if (result != null) return result;

        result = tryParseDoubleOutgoingDamageThisTurnAlt(effectText, source);
        if (result != null) return result;

        result = tryParseDoubleOpponentIncomingDamageThisTurn(effectText);
        if (result != null) return result;

        result = tryParseAllForwardIncomingDmgIncreaseThisTurn(effectText);
        if (result != null) return result;

        result = tryParseChooseForwardDoubleIncomingThisTurn(effectText);
        if (result != null) return result;

        result = tryParseChooseForwardDoubleNextOutgoing(effectText);
        if (result != null) return result;

        result = tryParseDoublePlayerAbilityOutgoingThisTurn(effectText);
        if (result != null) return result;

        result = tryParseStandaloneSelfBoostForEachCrystal(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneItPowerBoostUntil(effectText, source);
        if (result != null) return result;

        result = tryParseSelfPowerBoostAndActivate(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneSelfBoost(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneSelfDullAndShield(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneSelfDull(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneShieldCannotBeBroken(effectText, source);
        if (result != null) return result;

        result = tryParseAllOwnForwardsNullifyAbilityDamage(effectText);
        if (result != null) return result;

        result = tryParseAllForwardsCannotBlock(effectText);
        if (result != null) return result;

        result = tryParseForwardsOfCostCannotBlock(effectText);
        if (result != null) return result;

        result = tryParseEndOfNextTurnIfCardOnFieldOppLoses(effectText);
        if (result != null) return result;

        result = tryParseOppFwdsCannotBlockInferiorPower(effectText);
        if (result != null) return result;

        result = tryParseAllFwdsBlockedOnlyByLowerCostThisTurn(effectText);
        if (result != null) return result;

        result = tryParseOppFwdsLoseAllAbilitiesEot(effectText);
        if (result != null) return result;

        result = tryParseOppFwdPowerBoostSuppressedThisTurn(effectText);
        if (result != null) return result;

        result = tryParseOppFwdsLosePowerPerPlayCost(effectText);
        if (result != null) return result;

        result = tryParseStandaloneCannotBeBlocked(effectText, source);
        if (result != null) return result;

        result = tryParseRevealSelectHandRfp(effectText);
        if (result != null) return result;

        result = tryParseOpponentRandomHandRfp(effectText);
        if (result != null) return result;

        result = tryParseOpponentRandomHandToBottomDeck(effectText);
        if (result != null) return result;

        result = tryParseOpponentHandRfp(effectText);
        if (result != null) return result;

        result = tryParseRevealTopNAddUpToExcludingNameRestBz(effectText);
        if (result != null) return result;

        result = tryParseRevealTopNTypeCostToHand(effectText);
        if (result != null) return result;

        result = tryParseRevealTopNTypeToHand(effectText);
        if (result != null) return result;

        result = tryParseRevealTopNCategoryToHand(effectText);
        if (result != null) return result;

        result = tryParseRevealTopNJobOrNameToHand(effectText);
        if (result != null) return result;

        result = tryParseRevealTopNElementToHand(effectText);
        if (result != null) return result;

        result = tryParseRevealAddTypeToHandOrPlayJobTypeOntoFieldRestBottom(effectText);
        if (result != null) return result;

        result = tryParseReturnNamedToHand(effectText);
        if (result != null) return result;

        result = tryParseYouMayRemoveNamedFromGame(effectText, source);
        if (result != null) return result;

        result = tryParseEndOfOppTurnPlayNamedOntoField(effectText);
        if (result != null) return result;

        result = tryParseRemoveAllOppBzFromGame(effectText);
        if (result != null) return result;

        result = tryParseRemoveNamedFromGame(effectText, source);
        if (result != null) return result;

        result = tryParseBreakSourceCard(effectText, source);
        if (result != null) return result;

        result = tryParsePutSourceIntoBreakZone(effectText, source);
        if (result != null) return result;

        result = tryParseYouMayPutSelfToBZWhenDoSo(effectText, source);
        if (result != null) return result;

        result = tryParseIfOppNoForwardsPutToBreakZone(effectText, source);
        if (result != null) return result;

        result = tryParseIfEitherPlayerNoForwardsPutSourceToBz(effectText, source);
        if (result != null) return result;

        result = tryParseIfSelfDamagePointsPutToBreakZone(effectText, source);
        if (result != null) return result;

        result = tryParsePutSourceToBottomOfDeck(effectText, source);
        if (result != null) return result;

        result = tryParseBreakBlockingForward(effectText);
        if (result != null) return result;

        result = tryParseBreakForwardThatBlocksCard(effectText);
        if (result != null) return result;

        result = tryParseChooseExBurstFromDamageZone(effectText);
        if (result != null) return result;

        result = tryParseDamageZoneSwap(effectText);
        if (result != null) return result;

        result = tryParseOpponentDrawThenRandomDiscard(effectText);
        if (result != null) return result;

        result = tryParseOpponentDraw(effectText);
        if (result != null) return result;

        result = tryParseOpponentRandomDiscard(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerSelectForwardDamage(effectText);
        if (result != null) return result;

        result = tryParseBothPlayersSelectForwardToBreakZone(effectText);
        if (result != null) return result;

        result = tryParseSelectCharCostLeExclToBz(effectText);
        if (result != null) return result;

        result = tryParseSelectControlledCharacterToBz(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerSelectUpToNToBreakZone(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerDiscard(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerSalvageFromBreakZone(effectText);
        if (result != null) return result;

        result = tryParseSelectCharacterFromBzToHand(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerDraw(effectText);
        if (result != null) return result;

        result = tryParseNameCardTypeOpponentDiscardDrawIfMatch(effectText);
        if (result != null) return result;

        result = tryParseOpponentDiscard(effectText);
        if (result != null) return result;

        result = tryParseDiscardHandThenDraw(effectText);
        if (result != null) return result;

        result = tryParseDrawThenPlaceHandToBottom(effectText);
        if (result != null) return result;

        result = tryParsePayCpWhenDoSo(effectText, source);
        if (result != null) return result;

        result = tryParseDrawDiscardRetriggerIfCardName(effectText, source);
        if (result != null) return result;

        result = tryParseDrawCards(effectText);
        if (result != null) return result;

        result = tryParseYouMayDiscardType(effectText);
        if (result != null) return result;

        result = tryParseDiscardElementFromHand(effectText);
        if (result != null) return result;

        result = tryParseMayRevealElementFromHand(effectText);
        if (result != null) return result;

        result = tryParseDiscardHand(effectText);
        if (result != null) return result;

        result = tryParseDiscardNCards(effectText);
        if (result != null) return result;

        result = tryParseDiscardJobFromHand(effectText);
        if (result != null) return result;

        result = tryParseDiscardThenDraw(effectText);
        if (result != null) return result;

        result = tryParseDealPlayerDamageToOpponent(effectText);
        if (result != null) return result;

        result = tryParseDealPlayerDamageToSelf(effectText);
        if (result != null) return result;

        result = tryParseRandomRevealHandCastIfSummonFree(effectText);
        if (result != null) return result;

        result = tryParseCastSummonFromHandDiscounted(effectText);
        if (result != null) return result;

        result = tryParseCastSummonFromHandFree(effectText, xValue);
        if (result != null) return result;

        result = tryParseSearchAndCastSummonFree(effectText);
        if (result != null) return result;

        result = tryParsePlayAnyNumberFromHand(effectText, source);
        if (result != null) return result;

        result = tryParsePlayFromHand(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfRfpCount(effectText, source);
        if (result != null) return result;

        result = tryParseOpponentSelects(effectText);
        if (result != null) return result;

        result = tryParseBzFwdToHandOppFwdToBzByDamage(effectText);
        if (result != null) return result;

        result = tryParseOpponentPutsForwardToBreakZone(effectText);
        if (result != null) return result;

        result = tryParseOpponentMillIfSameElementDraw(effectText);
        if (result != null) return result;

        result = tryParseOpponentMill(effectText);
        if (result != null) return result;

        result = tryParseSelfMill(effectText);
        if (result != null) return result;

        result = tryParseOpponentRevealHand(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerRevealCharacterMayPlay(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerMaySearchForwardMinPower(effectText);
        if (result != null) return result;

        result = tryParseRevealTopDeck(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneDamageShields(effectText, source);
        if (result != null) return result;

        result = tryParseDualSearchJobAndTypeDontShareElements(effectText);
        if (result != null) return result;

        result = tryParseSearchElementOrCategoryCharsDiffCost(effectText);
        if (result != null) return result;

        result = tryParseSearchNElementSummonsDiffCost(effectText);
        if (result != null) return result;

        result = tryParseSearchDeck(effectText, source, xValue);
        if (result != null) return result;

        result = tryParsePlayAllByNameFromBreakZone(effectText);
        if (result != null) return result;

        result = tryParsePlaySourceFromBreakZone(effectText, source);
        if (result != null) return result;

        result = tryParsePlaySourceOntoField(effectText, source);
        if (result != null) return result;

        result = tryParseActivateNamedCard(effectText);
        if (result != null) return result;

        result = tryParseAttackOnceMore(effectText);
        if (result != null) return result;

        result = tryParseOpponentAttackOnceThisTurn(effectText);
        if (result != null) return result;

        result = tryParseOpponentCannotSearchThisTurn(effectText);
        if (result != null) return result;

        result = tryParseRemoveFromBattle(effectText);
        if (result != null) return result;

        result = tryParseChooseSummonFromBzToHandWithCostReduction(effectText);
        if (result != null) return result;

        result = tryParseChooseNSummonsBzPickOneHandRestRfg(effectText);
        if (result != null) return result;

        result = tryParseChooseSummonInBzCastable(effectText);
        if (result != null) return result;

        result = tryParseCostReductionThisTurn(effectText);
        if (result != null) return result;

        result = tryParsePlayCostReductionThisTurn(effectText);
        if (result != null) return result;

        result = tryParseExtraTurnThenLose(effectText);
        if (result != null) return result;

        result = tryParseGainCrystal(effectText);
        if (result != null) return result;

        result = tryParseGainCrystalIfOpponentHas(effectText);
        if (result != null) return result;

        result = tryParsePlaceCountersForEach(effectText, source);
        if (result != null) return result;

        result = tryParsePlaceCounters(effectText, source);
        if (result != null) return result;

        result = tryParseLookTopDeckOptionallyBreak(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckBottomOrKeep(effectText);
        if (result != null) return result;

        result = tryParseCounterScaleLookAddToHand(effectText, xValue);
        if (result != null) return result;

        result = tryParseLookTopDeckAddToHandRestBottom(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckAddToHandOneToBreakRestBottom(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckAddToHandRestBreak(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckTopOrBottom(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckReturnTopOrdered(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckPickOneTopRestBottom(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckCastSummonFreeRestBottom(effectText, xValue);
        if (result != null) return result;

        result = tryParseLookTopDeckPeek(effectText);
        if (result != null) return result;

        result = tryParseRemoveTopOfDeckFromGame(effectText);
        if (result != null) return result;

        result = tryParseRevealPlayNamedWithMaxCostRestBottom(effectText);
        if (result != null) return result;

        result = tryParseFlipUntilTypeToHandRestShuffleBottom(effectText);
        if (result != null) return result;

        result = tryParseShuffleThenRevealPlayNamedRestBottom(effectText, source);
        if (result != null) return result;

        result = tryParseRevealPlayTypeOntoFieldRestBottom(effectText);
        if (result != null) return result;

        result = tryParseRevealElementCardFromHandIfSoDraw(effectText);
        if (result != null) return result;

        result = tryParseRevealPlayElementTypeCostOntoFieldRestBottom(effectText);
        if (result != null) return result;

        result = tryParseShuffleDeck(effectText);
        if (result != null) return result;

        result = tryParseBackupCpDraw(effectText);
        if (result != null) return result;

        result = tryParseNameElementOnlySelfBecomes(effectText, source);
        if (result != null) return result;

        result = tryParseNameElementAndJobSelfBecomes(effectText, source);
        if (result != null) return result;

        result = tryParseNameJobAndElementSelfGainsPermanent(effectText, source);
        if (result != null) return result;

        result = tryParseNameJobOrElementAllForwardsBoost(effectText);
        if (result != null) return result;

        result = tryParseNameJobOrCategoryRevealAddToHand(effectText);
        if (result != null) return result;

        result = tryParseNameJob(effectText);
        if (result != null) return result;

        result = tryParseGrantPartyAnyElementThisTurn(effectText);
        if (result != null) return result;

        result = tryParseSourcePowerBecomesRemovedForwardPower(effectText, source);
        if (result != null) return result;

        // Compound-sentence fallback: split on ". " between sentences and compose effects.
        // Handles "Activate <cardName>. <cardName> gains +2000 power until the end of the turn." etc.
        // Sentences that don't parse are silently skipped so that implemented parts still fire.
        String[] sentences = effectText.split("(?<=\\.)\\s+(?=[A-Z])");
        if (sentences.length > 1) {
            List<Consumer<GameContext>> consumers = new ArrayList<>();
            for (String s : sentences) {
                String trimmed = s.trim().replaceAll("(?i)^Then\\s+", "");
                Consumer<GameContext> c = parse(trimmed, source, xValue);
                if (c != null) consumers.add(c);
            }
            if (!consumers.isEmpty()) return ctx -> consumers.forEach(c -> c.accept(ctx));
        }

        result = tryParseConditionalOpponentHand(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseConditionalOpponentHandMin(effectText, source, xValue);
        if (result != null) return result;

        if (CardData.HAS_ALL_ELEMENTS_PATTERN.matcher(effectText.trim()).matches()) return ctx -> {};

        result = tryParseMultiPlayGrant(effectText);
        if (result != null) return result;

        return null;
    }

    /**
     * Parses "If your opponent has [no | N cards or less] cards in his/her hand, [effect]."
     * The inner effect is parsed recursively; returns {@code null} if the inner effect is
     * not yet supported.
     */
    private static Consumer<GameContext> tryParseConditionalOpponentHand(
            String text, CardData source, int xValue) {
        Matcher m = OPPONENT_HAND_CONDITION_PATTERN.matcher(text.trim());
        if (!m.matches()) return null;
        String nStr      = m.group("n");
        int    threshold = nStr != null ? Integer.parseInt(nStr) : 0;
        String innerText = m.group("effect").trim();
        Consumer<GameContext> inner = parse(innerText, source, xValue);
        if (inner == null) return null;
        return ctx -> {
            int hs = ctx.opponentHandSize();
            boolean condMet = (nStr != null) ? hs <= threshold : hs == 0;
            if (!condMet) return;
            ctx.logEntry("[Hand condition] opponent has " + hs
                    + " card(s) — " + innerText);
            inner.accept(ctx);
        };
    }

    private static Consumer<GameContext> tryParseConditionalOpponentHandMin(
            String text, CardData source, int xValue) {
        Matcher m = OPPONENT_HAND_MIN_CONDITION_PATTERN.matcher(text.trim());
        if (!m.matches()) return null;
        int minThreshold = Integer.parseInt(m.group("n"));
        String innerText = m.group("effect").trim();
        Consumer<GameContext> inner = parse(innerText, source, xValue);
        if (inner == null) return null;
        return ctx -> {
            int hs = ctx.opponentHandSize();
            if (hs < minThreshold) return;
            ctx.logEntry("[Hand condition] opponent has " + hs + " card(s) — " + innerText);
            inner.accept(ctx);
        };
    }

    /** Returns the name of the first pattern that matches {@code effectText}, or {@code null}. */
    public static String matchedPatternName(String effectText, CardData source) {
        if (tryParseOppRfpTopDeckCastable(effectText)                   != null) return "OppRfpTopDeckCastable";
        if (tryParseChooseFromOppBzCastable(effectText)                 != null) return "ChooseFromOppBzCastable";
        if (tryParseChooseSummonsFromBzCastable(effectText)             != null) return "ChooseSummonsFromBzCastable";
        if (tryParseChooseSummonInBzMaxCostFreeCastRfg(effectText)      != null) return "ChooseSummonInBzMaxCostFreeCastRfg";
        if (tryParseSelectNumber(effectText, source)                    != null) return "SelectNumber";
        if (tryParseForEachJobAndNameDealDamageToForwards(effectText)   != null) return "ForEachJobAndNameDealDamageToForwards";
        if (tryParseDealNForEachJobOrNameToOppForwards(effectText)      != null) return "DealNForEachJobOrNameToOppForwards";
        if (tryParseSelfGainsWhenAttacksEOT(effectText, source)        != null) return "SelfGainsWhenAttacksEOT";
        if (tryParseDealDamageToForwardsForEach(effectText)             != null) return "DealDamageToForwardsForEach";
        if (tryParseDealDamageToForwardsExceptElement(effectText)       != null) return "DealDamageToForwardsExceptElement";
        if (tryParseDealDamageToForwards(effectText)                    != null) return "DealDamageToForwards";
        if (tryParseNoForwardCostCannotAttack(effectText)               != null) return "NoForwardCostCannotAttack";
        if (tryParseOwnForwardsCannotBeChosenByExBurst(effectText)      != null) return "OwnForwardsCannotBeChosenByExBurst";
        if (tryParseExBurstSuppression(effectText)                      != null) return "ExBurstSuppression";
        if (tryParseDealHalfPowerDamageToForwards(effectText)           != null) return "DealHalfPowerDamageToForwards";
        if (tryParseDealPowerMinusNDamageToForwards(effectText)         != null) return "DealPowerMinusNDamageToForwards";
        if (tryParseDealHalfSourcePowerDamageToForwards(effectText)     != null) return "DealHalfSourcePowerDamageToForwards";
        if (tryParseDamageToCombatBlocker(effectText)                   != null) return "DamageToCombatBlocker";
        // Trigger parsers checked before ChooseCharacter so coverage diagnostics report the
        // trigger name rather than the inner effect (ChooseCharacter uses find() and would
        // otherwise match the inner "choose 1 Forward..." through the trigger prefix).
        if (tryParseBeginningOfMainPhase1FieldAbility(effectText, source) != null) return "BeginningOfMainPhase1FieldAbility";
        if (tryParseBeginningOfMainPhase2FieldAbility(effectText, source) != null) return "BeginningOfMainPhase2FieldAbility";
        if (tryParseBeginningOfMainPhase1EachTurnFieldAbility(effectText, source) != null) return "BeginningOfMainPhase1EachTurnFieldAbility";
        if (tryParseEndOfOpponentTurnFieldAbility(effectText, source)     != null) return "EndOfOpponentTurnFieldAbility";
        if (tryParseChooseOppFwdDynCostBreak(effectText)                   != null) return "ChooseOppFwdDynCostBreak";
        if (tryParseChooseFwdPowerInferiorToSource(effectText, source)     != null) return "ChooseFwdPowerInferiorToSource";
        if (tryParseChooseFwdBzCostInferiorToRemovedPlay(effectText)       != null) return "ChooseFwdBzCostInferiorToRemovedPlay";
        if (tryParseChooseOppFwdGainsSpecialAbilityFreeOnce(effectText, source) != null) return "ChooseOppFwdGainsSpecialAbilityFreeOnce";
        if (tryParseChooseOppDamagedFwdIfHasAbilityBreak(effectText)     != null) return "ChooseOppDamagedFwdIfHasAbilityBreak";
        if (tryParseChooseAsManyAsFieldCount(effectText, source)         != null) return "ChooseAsManyAsFieldCount";
        if (tryParseChooseCounterScaleCharsActivate(effectText, 1)    != null) return "ChooseCounterScaleCharsActivate";
        if (tryParseChooseAnyNumberReturnToHand(effectText)    != null) return "ChooseAnyNumberReturnToHand";
        if (tryParseChooseCharacter(effectText, source, 0)              != null) return "ChooseCharacter";
        if (tryParseEndOfEachTurnFieldAbility(effectText, source)             != null) return "EndOfEachTurnFieldAbility";
        if (tryParseEndOfEachPlayersTurnIfSelfFwdDamage(effectText, source)  != null) return "EndOfEachPlayersTurnIfSelfFwdDamage";
        if (tryParseIfRfpCount(effectText, source)               != null) return "IfRfpCount";
        if (tryParseElementChange(effectText, source) != null) return "ElementChange";
        if (tryParseDelayedEffect(effectText)                 != null) return "DelayedEffect";
        if (tryParsePlayerCannotCastSummons(effectText)                != null) return "PlayerCannotCastSummons";
        if (tryParseCannotBeChosenStandalone(effectText, source) != null) return "CannotBeChosen";
        if (tryParseCannotBecomeDullOpp(effectText, source) != null)     return "CannotBecomeDullOpp";
        if (tryParseStandaloneCannotAttackOrBlock(effectText, source) != null) return "CannotAttackOrBlock";
        if (tryParseNegateAllDamage(effectText)                != null) return "NegateDamage";
        if (tryParsePlayerNextDamageZero(effectText)           != null) return "PlayerNextDamageZero";
        if (tryParseCancelAutoAbilityAndDamageIfForward(effectText) != null) return "CancelAutoAbilityAndDamageIfForward";
        if (tryParseCancelStackEntry(effectText)               != null) return "CancelSummonOrAutoAbility";
        if (tryParseRedirectAbilityTarget(effectText)          != null) return "RedirectAbilityTarget";
        if (tryParseCancelAbilityOnStack(effectText)           != null) return "CancelAbilityOnStack";
        if (tryParseCancelSummonTargetingMyCharacter(effectText) != null) return "CancelSummonTargetingMyCharacter";
        if (tryParseSelectNumber(effectText, source)          != null) return "SelectNumber";
        if (tryParseDullAllOppFwdsPowerLeSource(effectText, source)        != null) return "DullAllOppFwdsPowerLeSource";
        if (tryParseRevealTopBreakSameCostAddToHand(effectText)           != null) return "RevealTopBreakSameCostAddToHand";
        if (tryParseAllFieldEffect(effectText)                != null) return "AllFieldEffect";
        if (tryParseFieldPowerGrantPassive(effectText)        != null) {
            String trimmed = effectText.trim();
            return FIELD_OPPONENT_DEBUFF_PASSIVE.matcher(trimmed).matches()
                    ? "FieldOpponentPowerDebuff" : "FieldPowerGrant";
        }
        if (tryParseStandalonePowerBoostAndAttackTrigger(effectText, source) != null) return "StandalonePowerBoostAndAttackTrigger";
        if (tryParseStandaloneGainsTraitsAndCannotBeBlocked(effectText, source) != null) return "StandaloneGainsTraitsAndCannotBeBlocked";
        if (tryParseStandalonePowerBoostUntil(effectText, source) != null) return "StandalonePowerBoostUntil";
        if (tryParseStandaloneDoublePowerUntil(effectText, source) != null) return "StandaloneDoublePowerUntil";
        if (tryParseStandaloneDoublesItsPowerUntil(effectText, source) != null) return "StandaloneDoublesItsPowerUntil";
        if (tryParseStandaloneDoublePowerMainPhaseNextTurn(effectText, source) != null) return "StandaloneDoublePowerMainPhaseNextTurn";
        if (tryParseStandalonePowerReduceUntil(effectText, source) != null) return "StandalonePowerReduceUntil";
        if (tryParseFieldSelfPowerBoost(effectText, source)    != null) return "FieldSelfPowerBoost";
        if (tryParseDoubleOutgoingDamageThisTurn(effectText, source) != null)    return "DoubleOutgoingDamageThisTurn";
        if (tryParseDoubleOutgoingDamageThisTurnAlt(effectText, source) != null) return "DoubleOutgoingDamageThisTurnAlt";
        if (tryParseDoubleOpponentIncomingDamageThisTurn(effectText) != null)   return "DoubleOpponentIncomingDamageThisTurn";
        if (tryParseAllForwardIncomingDmgIncreaseThisTurn(effectText) != null)  return "AllForwardIncomingDmgIncreaseThisTurn";
        if (tryParseChooseForwardDoubleIncomingThisTurn(effectText) != null)    return "ChooseForwardDoubleIncomingThisTurn";
        if (tryParseChooseForwardDoubleNextOutgoing(effectText) != null)        return "ChooseForwardDoubleNextOutgoing";
        if (tryParseDoublePlayerAbilityOutgoingThisTurn(effectText) != null)   return "DoublePlayerAbilityOutgoingThisTurn";
        if (tryParseStandaloneSelfBoostForEachCrystal(effectText, source) != null) return "StandaloneSelfBoostForEachCrystal";
        if (tryParseStandaloneSelfBoost(effectText, source)   != null) return "StandaloneSelfBoost";
        if (tryParseStandaloneSelfDullAndShield(effectText, source) != null) return "StandaloneSelfDullAndShield";
        if (tryParseStandaloneSelfDull(effectText, source) != null)          return "StandaloneSelfDull";
        if (tryParseStandaloneShieldCannotBeBroken(effectText, source) != null) return "StandaloneShieldCannotBeBroken";
        if (tryParseAllOwnForwardsNullifyAbilityDamage(effectText)        != null) return "AllOwnForwardsNullifyAbilityDamage";
        if (tryParseAllForwardsCannotBlock(effectText)                    != null) return "AllForwardsCannotBlock";
        if (tryParseForwardsOfCostCannotBlock(effectText)                 != null) return "ForwardsOfCostCannotBlock";
        if (tryParseEndOfNextTurnIfCardOnFieldOppLoses(effectText)        != null) return "EndOfNextTurnIfCardOnFieldOppLoses";
        if (tryParseOppFwdsCannotBlockInferiorPower(effectText)           != null) return "OppFwdsCannotBlockInferiorPower";
        if (tryParseAllFwdsBlockedOnlyByLowerCostThisTurn(effectText)    != null) return "AllFwdsBlockedOnlyByLowerCost";
        if (tryParseOppFwdsLoseAllAbilitiesEot(effectText)         != null) return "OppFwdsLoseAllAbilitiesEot";
        if (tryParseOppFwdPowerBoostSuppressedThisTurn(effectText) != null) return "OppFwdPowerBoostSuppressedThisTurn";
        if (tryParseOppFwdsLosePowerPerPlayCost(effectText)        != null) return "OppFwdsLosePowerPerPlayCost";
        if (tryParseStandaloneCannotBeBlocked(effectText, source) != null) return "StandaloneCannotBeBlocked";
        if (tryParseRevealHandOptPickRfpOppDraw(effectText)    != null) return "RevealHandOptPickRfpOppDraw";
        if (tryParseRevealSelectHandRfp(effectText)            != null) return "RevealSelectHandRfp";
        if (tryParseOpponentRandomHandRfp(effectText)            != null) return "OpponentRandomHandRfp";
        if (tryParseOpponentRandomHandToBottomDeck(effectText)   != null) return "OpponentRandomHandToBottomDeck";
        if (tryParseOpponentHandRfp(effectText)               != null) return "OpponentHandRfp";
        if (tryParseYouMayRemoveNamedFromGame(effectText, source) != null) return "YouMayRemoveNamedFromGame";
        if (tryParseEndOfOppTurnPlayNamedOntoField(effectText) != null) return "EndOfOppTurnPlayNamedOntoField";
        if (tryParseRemoveAllOppBzFromGame(effectText)         != null) return "RemoveAllOppBzFromGame";
        if (tryParseRemoveNamedFromGame(effectText, source)   != null) return "RemoveNamedFromGame";
        if (tryParseBreakSourceCard(effectText, source)        != null) return "BreakSourceCard";
        if (tryParsePutSourceIntoBreakZone(effectText, source) != null) return "PutSourceIntoBreakZone";
        if (tryParseYouMayPutSelfToBZWhenDoSo(effectText, source)    != null) return "YouMayPutSelfToBZWhenDoSo";
        if (tryParseIfOppNoForwardsPutToBreakZone(effectText, source)          != null) return "IfOppNoForwardsPutToBreakZone";
        if (tryParseIfEitherPlayerNoForwardsPutSourceToBz(effectText, source)  != null) return "IfEitherPlayerNoForwardsPutSourceToBz";
        if (tryParseIfSelfDamagePointsPutToBreakZone(effectText, source)      != null) return "IfSelfDamagePointsPutToBreakZone";
        if (tryParsePutSourceToBottomOfDeck(effectText, source) != null) return "PutSourceToBottomOfDeck";
        if (tryParseBreakBlockingForward(effectText)           != null) return "BreakBlockingForward";
        if (tryParseBreakForwardThatBlocksCard(effectText)     != null) return "BreakForwardThatBlocksCard";
        if (tryParseChooseExBurstFromDamageZone(effectText)    != null) return "ChooseExBurstFromDamageZone";
        if (tryParseExBurstSuppression(effectText)             != null) return "ExBurstSuppression";
        if (tryParseDamageZoneSwap(effectText)                 != null) {
            Matcher m = DAMAGE_ZONE_SWAP_PATTERN.matcher(effectText.trim());
            return m.matches() && m.group("draw") != null ? "DamageZoneSwap + DrawCards" : "DamageZoneSwap";
        }
        if (tryParseOpponentDrawThenRandomDiscard(effectText)  != null) return "OpponentDrawThenRandomDiscard";
        if (tryParseOpponentDraw(effectText)                   != null) return "OpponentDraw";
        if (tryParseOpponentRandomDiscard(effectText)         != null) return "OpponentRandomDiscard";
        if (tryParseEachPlayerSelectForwardDamage(effectText)  != null) return "EachPlayerSelectForwardDamage";
        if (tryParseBothPlayersSelectForwardToBreakZone(effectText) != null) return "BothPlayersSelectForwardToBreakZone";
        if (tryParseSelectCharCostLeExclToBz(effectText)             != null) return "SelectCharCostLeExclToBz";
        if (tryParseSelectControlledCharacterToBz(effectText)        != null) return "SelectControlledCharacterToBz";
        if (tryParseEachPlayerSelectUpToNToBreakZone(effectText)   != null) return "EachPlayerSelectUpToNToBreakZone";
        if (tryParseEachPlayerDiscard(effectText)              != null) return "EachPlayerDiscard";
        if (tryParseEachPlayerSalvageFromBreakZone(effectText) != null) return "EachPlayerSalvageFromBreakZone";
        if (tryParseSelectCharacterFromBzToHand(effectText)    != null) return "SelectCharacterFromBzToHand";
        if (tryParseEachPlayerDraw(effectText)                 != null) return "EachPlayerDraw";
        if (tryParseNameCardTypeOpponentDiscardDrawIfMatch(effectText) != null) return "NameCardTypeOpponentDiscardDrawIfMatch";
        if (tryParseOpponentDiscard(effectText)               != null) return "OpponentDiscard";
        if (tryParseDiscardHandThenDraw(effectText)           != null) return "DiscardHandThenDraw";
        if (tryParseDrawThenPlaceHandToBottom(effectText)     != null) return "DrawThenPlaceHandToBottom";
        if (tryParsePayCpWhenDoSo(effectText, source)         != null) return "PayCpWhenDoSo";
        if (tryParseDrawDiscardRetriggerIfCardName(effectText, source) != null) return "DrawDiscardRetriggerIfCardName";
        if (tryParseDrawCards(effectText)                     != null) return "DrawCards";
        if (tryParseYouMayDiscardType(effectText)             != null) return "YouMayDiscardType";
        if (tryParseMayRevealElementFromHand(effectText)      != null) return "MayRevealElementFromHand";
        if (tryParseDiscardHand(effectText)                   != null) return "DiscardHand";
        if (tryParseDiscardNCards(effectText)                 != null) return "DiscardNCards";
        if (tryParseDiscardJobFromHand(effectText)            != null) return "DiscardJobFromHand";
        if (tryParseDiscardThenDraw(effectText)               != null) return "DiscardThenDraw";
        if (tryParseDealPlayerDamageToOpponent(effectText)    != null) return "DealPlayerDamageToOpponent";
        if (tryParseDealPlayerDamageToSelf(effectText)        != null) return "DealPlayerDamageToSelf";
        if (tryParseRandomRevealHandCastIfSummonFree(effectText) != null) return "RandomRevealHandCastIfSummonFree";
        if (tryParseCastSummonFromHandDiscounted(effectText)     != null) return "CastSummonFromHandDiscounted";
        if (tryParseCastSummonFromHandFree(effectText, 0)     != null) return "CastSummonFromHandFree";
        if (tryParseSearchAndCastSummonFree(effectText)       != null) return "SearchAndCastSummonFree";
        if (tryParsePlayAnyNumberFromHand(effectText, source) != null) return "PlayAnyNumberFromHand";
        if (tryParsePlayFromHand(effectText, source, 0)       != null) return "PlayFromHand";
        if (tryParseOpponentSelects(effectText)               != null) return "OpponentSelects";
        if (tryParseBzFwdToHandOppFwdToBzByDamage(effectText)  != null) return "BzFwdToHandOppFwdToBzByDamage";
        if (tryParseOpponentPutsForwardToBreakZone(effectText) != null) return "OpponentPutsForwardToBreakZone";
        if (tryParseOpponentMillIfSameElementDraw(effectText)  != null) return "OpponentMillIfSameElementDraw";
        if (tryParseOpponentMill(effectText)                  != null) return "OpponentMill";
        if (tryParseSelfMill(effectText)                      != null) return "SelfMill";
        if (tryParseOpponentRevealHand(effectText)            != null) return "OpponentRevealHand";
        if (tryParseEachPlayerRevealCharacterMayPlay(effectText)      != null) return "EachPlayerRevealMayPlay";
        if (tryParseEachPlayerMaySearchForwardMinPower(effectText)     != null) return "EachPlayerMaySearchForwardMinPower";
        if (tryParseRevealTopDeck(effectText, source)         != null) return "RevealTopDeck";
        if (tryParseStandaloneDamageShields(effectText, source) != null) return "StandaloneDamageShields";
        if (tryParseDualSearchJobAndTypeDontShareElements(effectText)      != null) return "DualSearchDontShareElements";
        if (tryParseSearchElementOrCategoryCharsDiffCost(effectText)       != null) return "SearchElementOrCategoryCharsDiffCost";
        if (tryParseSearchNElementSummonsDiffCost(effectText)              != null) return "SearchNElementSummonsDiffCost";
        if (tryParseSearchDeck(effectText, source, 0)                      != null) return "SearchDeck";
        if (tryParsePlayAllByNameFromBreakZone(effectText)      != null) return "PlayAllByNameFromBreakZone";
        if (tryParsePlaySourceFromBreakZone(effectText, source) != null) return "PlaySourceFromBreakZone";
        if (tryParseActivateNamedCard(effectText)               != null) return "ActivateNamedCard";
        if (tryParseAttackOnceMore(effectText)                  != null) return "AttackOnceMore";
        if (tryParseOpponentCannotSearchThisTurn(effectText)    != null) return "OpponentCannotSearch";
        if (tryParseRemoveFromBattle(effectText)                != null) return "RemoveFromBattle";
        if (tryParseChooseSummonFromBzToHandWithCostReduction(effectText) != null) return "ChooseSummonFromBzToHandWithCostReduction";
        if (tryParseChooseNSummonsBzPickOneHandRestRfg(effectText)        != null) return "ChooseNSummonsBzPickOneHandRestRfg";
        if (tryParseChooseSummonInBzCastable(effectText)              != null) return "ChooseSummonInBzCastable";
        if (tryParseChooseSummonInBzMaxCostFreeCastRfg(effectText)    != null) return "ChooseSummonInBzMaxCostFreeCastRfg";
        if (tryParseCostReductionThisTurn(effectText)                 != null) return "CostReductionThisTurn";
        if (tryParsePlayCostReductionThisTurn(effectText)        != null) return "PlayCostReductionThisTurn";
        if (CardData.isSelfCostModifierText(effectText))                  return "SelfCostModifier";
        if (tryParseExtraTurnThenLose(effectText)               != null) return "ExtraTurnThenLose";
        if (tryParseGainCrystalPerX(effectText, 0)               != null) return "GainCrystalPerX";
        if (tryParseGainCrystal(effectText)                      != null) return "GainCrystal";
        if (tryParseGainCrystalIfOpponentHas(effectText)         != null) return "GainCrystalIfOpponentHas";
        if (tryParsePlaceCountersForEach(effectText, source)     != null) return "PlaceCountersForEach";
        if (tryParsePlaceCounters(effectText, source)            != null) return "PlaceCounters";
        if (tryParseLookTopDeckOptionallyBreak(effectText)        != null) return "LookTopDeckOptionallyBreak";
        if (tryParseLookTopDeckBottomOrKeep(effectText)           != null) return "LookTopDeckBottomOrKeep";
        if (tryParseCounterScaleLookAddToHand(effectText, 1)               != null) return "CounterScaleLookAddToHand";
        if (tryParseLookTopDeckAddToHandRestBottom(effectText)          != null) return "LookTopDeckAddToHandRestBottom";
        if (tryParseLookTopDeckAddToHandOneToBreakRestBottom(effectText) != null) return "LookTopDeckAddToHandOneToBreakRestBottom";
        if (tryParseLookTopDeckAddToHandRestBreak(effectText)           != null) return "LookTopDeckAddToHandRestBreak";
        if (tryParseLookTopDeckTopOrBottom(effectText)                  != null) return "LookTopDeckTopOrBottom";
        if (tryParseLookTopDeckReturnTopOrdered(effectText)             != null) return "LookTopDeckReturnTopOrdered";
        if (tryParseLookTopDeckPickOneTopRestBottom(effectText)              != null) return "LookTopDeckPickOneTopRestBottom";
        if (tryParseLookTopDeckCastSummonFreeRestBottom(effectText, 0)       != null) return "LookTopDeckCastSummonFreeRestBottom";
        if (tryParseLookTopDeckPeek(effectText)                              != null) return "LookTopDeckPeek";
        if (tryParseRemoveTopOfDeckFromGame(effectText)                      != null) return "RemoveTopOfDeckFromGame";
        if (tryParseRevealPlayNamedWithMaxCostRestBottom(effectText)         != null) return "RevealPlayNamedWithMaxCostRestBottom";
        if (tryParseFlipUntilTypeToHandRestShuffleBottom(effectText)         != null) return "FlipUntilTypeToHandRestShuffleBottom";
        if (tryParseShuffleDeck(effectText)                                  != null) return "ShuffleDeck";
        if (tryParseIfOwnForwardFormedParty(effectText, source, 0)       != null) return "IfOwnForwardFormedParty";
        if (tryParseIfControlAtMost(effectText, source, 0)             != null) return "IfControlAtMost";
        if (tryParseIfCastAtLeast(effectText, source, 0)               != null) return "IfCastAtLeast";
        if (tryParseIfControlCondOtherThan(effectText, source, 0)      != null) return "IfControlCondOtherThan";
        if (tryParseIfOppControlsNOrMoreCondTypeGate(effectText, source, 0) != null) return "IfOppControlsNOrMoreCondType";
        if (tryParseDiscardConditionalElement(effectText, source, 0)   != null) return "DiscardConditionalElement";
        if (tryParseConditionalOpponentHand(effectText, source, 0)     != null) return "ConditionalOpponentHand";
        if (tryParseConditionalOpponentHandMin(effectText, source, 0) != null) return "ConditionalOpponentHandMin";
        if (tryParseYouMayPutSelfToBZWhenDoSo(effectText, source)    != null) return "YouMayPutSelfToBZWhenDoSo";
        if (SELECT_FOLLOWING_ACTIONS_DETECT.matcher(effectText).find())        return "SelectFollowingActions";
        if (CardData.HAS_ALL_ELEMENTS_PATTERN.matcher(effectText.trim()).matches()) return "HasAllElements";
        if (tryParseMultiPlayGrant(effectText) != null)                         return "MultiPlayGrant";
        return null;
    }

    /**
     * Returns the name of the first followup pattern that matches {@code followupText}, or
     * {@code null} if no followup pattern recognises it.  The ordering mirrors the precedence
     * used inside {@link #tryParseChooseCharacter}.
     */
    public static String matchedFollowupName(String followupText, CardData source) {
        // Strip leading "You may " so optional-followup effects are identified correctly
        if (followupText.toLowerCase(java.util.Locale.ROOT).startsWith("you may "))
            followupText = followupText.substring("You may ".length()).trim();
        if (FOLLOWUP_TARGET_CONTROLLER_DISCARDS.matcher(followupText).matches()) return "TargetControllerDiscards";
        if (source != null) {
            Matcher mutM = FOLLOWUP_MUTUAL_POWER_DAMAGE.matcher(followupText);
            if (mutM.find() && mutM.group("srcname").trim().equalsIgnoreCase(source.name())) return "MutualPowerDamage";
        }
        if (FOLLOWUP_DAMAGE_FOR_EACH_COUNTER.matcher(followupText).find())             return "DamageForEachCounter";
        if (FOLLOWUP_DAMAGE_FOR_EACH.matcher(followupText).find())                    return "DamageForEach";
        if (FOLLOWUP_DULL_AND_DAMAGE.matcher(followupText).find())                   return "DullAndDamage";
        if (FOLLOWUP_FIRST_AND_OTHER.matcher(followupText).find())                    return "FirstAndOther";
        if (FOLLOWUP_DAMAGE_AND_CONTROLLER_DAMAGE.matcher(followupText).find())       return "DamageAndControllerDamage";
        if (FOLLOWUP_DAMAGE.matcher(followupText).find())                             return "Damage";
        if (FOLLOWUP_DAMAGE_EXPR.matcher(followupText).find())                        return "DamageExpr";
        if (FOLLOWUP_ACTIVATE_AND_GAIN_CONTROL_EOT.matcher(followupText).find())        return "ActivateAndGainControlEOT";
        if (FOLLOWUP_ACTIVATE_AND_NEGATE_DAMAGE.matcher(followupText).find())          return "ActivateAndNegateDamage";
        if (FOLLOWUP_NEGATE_DAMAGE.matcher(followupText).find())                      return "NegateDamage";
        if (FOLLOWUP_GAIN_CONTROL_WHILE_CARD.matcher(followupText).find())            return "GainControlWhileCard";
        if (FOLLOWUP_GAIN_CONTROL_EOT.matcher(followupText).find())                   return "GainControlEOT";
        if (FOLLOWUP_GAIN_CONTROL.matcher(followupText).find())                       return "GainControl";
        if (FOLLOWUP_SELF_AND_TARGET_GAIN_QUOTE_UNTIL_OPP_TURN.matcher(followupText).find()) return "SelfAndTargetGainUntilOppTurn";
        if (FOLLOWUP_TARGET_NEXT_SPECIAL_FREE.matcher(followupText).find())              return "TargetNextSpecialFree";
        if (FOLLOWUP_CAST_IT_FROM_BZ_ANYTIME_NO_HAND.matcher(followupText).find())      return "CastItFromBzAnytime";
        if (FOLLOWUP_GAINS_CANNOT_BE_CHOSEN.matcher(followupText).find())             return "GainsCannotBeChosen";
        if (FOLLOWUP_CANNOT_BE_BROKEN.matcher(followupText).find())                  return "CannotBeBroken";
        if (FOLLOWUP_CANNOT_BE_BROKEN_SIMPLE.matcher(followupText).find())           return "CannotBeBrokenSimple";
        if (FOLLOWUP_CANNOT_BE_BROKEN_BY_NON_DMG.matcher(followupText).find())      return "CannotBeBrokenByNonDmg";
        if (FOLLOWUP_GAINS_BREAKTOUCH_BATTLE.matcher(followupText).find())           return "BreaktouchBattle";
        if (FOLLOWUP_CANNOT_BE_CHOSEN_BOTH.matcher(followupText).find())              return "CannotBeChosenBoth";
        if (FOLLOWUP_CANNOT_BE_CHOSEN_SUMMONS.matcher(followupText).find())           return "CannotBeChosenSummons";
        if (FOLLOWUP_CANNOT_BE_CHOSEN_ABILITIES.matcher(followupText).find())         return "CannotBeChosenAbilities";
        if (FOLLOWUP_DULL_OR_ACTIVATE.matcher(followupText).find())                   return "DullOrActivate";
        if (FOLLOWUP_DULL_OR_FREEZE.matcher(followupText).find())                     return "DullOrFreeze";
        if (FOLLOWUP_ACTIVATE.matcher(followupText).find())                           return "Activate";
        if (FOLLOWUP_DULL.matcher(followupText).find()
                && !FOLLOWUP_DULL_AND_FREEZE.matcher(followupText).find()
                && !FOLLOWUP_DULL_OR_FREEZE.matcher(followupText).find())             return "Dull";
        if (FOLLOWUP_DULL_AND_FREEZE.matcher(followupText).find())                    return "DullAndFreeze";
        if (FOLLOWUP_FREEZE.matcher(followupText).find())                             return "Freeze";
        if (FOLLOWUP_BREAK.matcher(followupText).find())                              return "Break";
        if (FOLLOWUP_LOSE_ALL_ABILITIES_EOT.matcher(followupText).find())              return "LoseAllAbilitiesEot";
        if (FOLLOWUP_REMOVE_FROM_GAME_AND_NAMED.matcher(followupText).find())          return "RemoveFromGameAndNamed";
        if (FOLLOWUP_REMOVE_FROM_GAME.matcher(followupText).find())                   return "RemoveFromGame";
        if (SECONDARY_PLAY_REMOVED_ONTO_FIELD.matcher(followupText).find())           return "PlayRemovedOntoField";
        if (FOLLOWUP_PLAY_IF_COST_LE_JOB_COUNT.matcher(followupText).matches())       return "PlayIfCostLeJobCount";
        if (FOLLOWUP_RETURN_IF_COST_LE_HAND.matcher(followupText).matches())          return "ReturnIfCostLeHand";
        if (FOLLOWUP_PLAY_ONTO_FIELD.matcher(followupText).find())                    return "PlayOntoField";
        if (FOLLOWUP_ADD_TO_HAND.matcher(followupText).find())                        return "AddToHand";
        if (FOLLOWUP_RETURN_AND_NAMED_TO_OWNERS_HAND.matcher(followupText).find())    return "ReturnAndNamedToOwnersHand";
        if (FOLLOWUP_RETURN_TO_OWNERS_HAND.matcher(followupText).find())              return "ReturnToOwnersHand";
        if (FOLLOWUP_RETURN_TO_YOUR_HAND.matcher(followupText).find())                return "ReturnToYourHand";
        if (FOLLOWUP_PUT_TOP_OR_BOTTOM_OF_DECK.matcher(followupText).find())          return "PutTopOrBottomOfDeck";
        if (FOLLOWUP_PUT_BOTTOM_OF_DECK.matcher(followupText).find())                 return "PutBottomOfDeck";
        if (FOLLOWUP_PUT_TOP_OF_DECK.matcher(followupText).find())                    return "PutTopOfDeck";
        if (FOLLOWUP_PUT_UNDER_TOP_OF_DECK.matcher(followupText).find())              return "PutUnderTopOfDeck";
        if (FOLLOWUP_CANNOT_BLOCK.matcher(followupText).find())                       return "CannotBlock";
        if (FOLLOWUP_ONLY_BLOCKED_BY_COST_LE_OWN.matcher(followupText).find())        return "OnlyBlockedByCostLeOwn";
        if (FOLLOWUP_CANNOT_BE_BLOCKED.matcher(followupText).find())                  return "CannotBeBlocked";
        if (FOLLOWUP_CANNOT_BE_BLOCKED_IF_ELEMENT_CP.matcher(followupText).find())   return "CannotBeBlockedIfElementCP";
        if (FOLLOWUP_MUST_BLOCK.matcher(followupText).find())                         return "MustBlock";
        if (FOLLOWUP_CANNOT_ATTACK.matcher(followupText).find())                      return "CannotAttack";
        if (FOLLOWUP_MUST_ATTACK.matcher(followupText).find())                        return "MustAttack";
        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK.matcher(followupText).find())             return "CannotAttackOrBlock";
        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK_PERSISTENT.matcher(followupText).find())  return "CannotAttackOrBlockPersistent";
        if (FOLLOWUP_POWER_BECOMES.matcher(followupText).find())                      return "PowerBecomes";
        if (FOLLOWUP_POWER_BOOST.matcher(followupText).find())                        return "PowerBoost";
        if (FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH.matcher(followupText).find())              return "PowerBoostUntilForEach";
        if (FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_JOB.matcher(followupText).find())         return "PowerBoostUntilForEachJob";
        if (FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_COUNTER.matcher(followupText).find())      return "PowerBoostUntilForEachCounter";
        if (FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_SELF_DMG.matcher(followupText).find())    return "PowerBoostUntilForEachSelfDmg";
        if (FOLLOWUP_POWER_BOOST_UNTIL.matcher(followupText).find())                      return "PowerBoostUntil";
        if (FOLLOWUP_KEYWORD_GRANT.matcher(followupText).find())                      return "KeywordGrant";
        if (FOLLOWUP_KEYWORD_GRANT_UNTIL.matcher(followupText).find())               return "KeywordGrant";
        if (FOLLOWUP_POWER_REDUCE.matcher(followupText).find())                       return "PowerReduce";
        if (FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH_HAND.matcher(followupText).find())  return "PowerReduceUntilForEachHand";
        if (FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH.matcher(followupText).find())       return "PowerReduceUntilForEach";
        if (FOLLOWUP_POWER_REDUCE_UNTIL.matcher(followupText).find())                 return "PowerReduceUntil";
        if (OPPONENT_DISCARD.matcher(followupText).find())                            return "OpponentDiscard";
        if (source != null) {
            Matcher selfM = SELF_POWER_BOOST.matcher(followupText);
            if (selfM.find() && selfM.group("selfsubject").trim().equalsIgnoreCase(source.name()))
                return "SelfPowerBoost";
        }
        if (FOLLOWUP_PLACE_COUNTER_ON_IT.matcher(followupText).find())                 return "PlaceCounterOnIt";
        if (FOLLOWUP_REMOVE_ONE_COUNTER.matcher(followupText).find())                  return "RemoveOneCounter";
        if (BECOME_FORWARD_UNTIL_EOT_PATTERN.matcher(followupText).find())             return "BecomeForwardUntilEot";
        if (FOLLOWUP_CANCEL_EFFECT.matcher(followupText).find())                      return "CancelEffect";
        if (FOLLOWUP_SHIELD_NEXT_DMG_ZERO.matcher(followupText).find())               return "ShieldNextDmgZero";
        if (FOLLOWUP_SHIELD_NEXT_ABILITY_DMG_REDUCTION.matcher(followupText).find())   return "ShieldNextAbilityDmgReduction";
        if (FOLLOWUP_SHIELD_NEXT_DMG_REDUCTION.matcher(followupText).find())          return "ShieldNextDmgReduction";
        if (FOLLOWUP_DEBUFF_INCOMING_DMG_INCREASE.matcher(followupText).find())       return "DebuffIncomingDmgIncrease";
        if (FOLLOWUP_SHIELD_NEXT_OUTGOING_ZERO.matcher(followupText).find())          return "ShieldNextOutgoingZero";
        if (FOLLOWUP_OUTGOING_DMG_BOOST_THIS_TURN.matcher(followupText).find())       return "OutgoingDmgBoostThisTurn";
        if (FOLLOWUP_SHIELD_NONLETHAL.matcher(followupText).find())                   return "ShieldNonLethal";
        if (FOLLOWUP_GAINS_SHIELD_ABILITY_ONLY.matcher(followupText).find())          return "GainsShieldAbilityOnly";
        if (FOLLOWUP_PUT_TO_BREAK_ZONE.matcher(followupText).find())                  return "PutToBreakZone";
        if (FOLLOWUP_SELECT_NUMBER_REVEAL_BREAK.matcher(followupText).find())         return "SelectNumberRevealBreak";
        if (FOLLOWUP_IF_OPPONENT_CONTROLS_FORWARDS_DAMAGE.matcher(followupText).matches()) return "IfOppControlsForwardsDamage";
        if (FOLLOWUP_IF_SELF_CONTROLS_N_ELEMENT_TYPE_DAMAGE.matcher(followupText).matches()) return "IfSelfControlsNElementTypeDamage";
        if (FOLLOWUP_REVEAL_TOP_N_DAMAGE_PER_CP_ADD_ALL_TO_HAND.matcher(followupText).find()) return "RevealTopNDamagePerCpAddAllToHand";
        if (FOLLOWUP_REVEAL_TOP_N_JOB_DEAL_DMG_PLACE_BOTTOM.matcher(followupText).find())    return "RevealTopNJobDealDmgPlaceBottom";
        return null;
    }

    /**
     * Returns a full description of which patterns cover {@code effectText}, including
     * primary, followup, and secondary layers.  A {@code "?"} in the result means that
     * layer has no matching pattern yet.  Returns {@code null} if no primary pattern matches.
     */
    public static String fullDescription(String effectText, CardData source) {
        effectText = effectText.replaceFirst("(?i)^(?:\\[\\[ex\\]\\])?\\s*EX\\s+BURST(?:\\[\\[/\\]\\])?\\s*", "").trim();
        effectText = effectText.replaceFirst("(?i)^Then,?\\s+", "").trim();
        // Strip trailing use-restriction sentences so they don't short-circuit before effect patterns match
        String noRestriction = stripRestrictionSentences(effectText);
        if (!noRestriction.isEmpty()) effectText = noRestriction;
        if (tryParseChooseSummonInBzCastable(effectText)        != null)    return "ChooseSummonInBzCastable";
        if (tryParseOppRfpTopDeckCastable(effectText)          != null)    return "OppRfpTopDeckCastable";
        if (tryParseChooseFromOppBzCastable(effectText)        != null)    return "ChooseFromOppBzCastable";
        if (tryParseChooseSummonsFromBzCastable(effectText)         != null)    return "ChooseSummonsFromBzCastable";
        if (tryParseChooseSummonInBzMaxCostFreeCastRfg(effectText)  != null)    return "ChooseSummonInBzMaxCostFreeCastRfg";
        if (CardData.isSelfCostModifierText(effectText))                        return "SelfCostModifier";
        if (CardData.YOUR_TURN_ONLY_PATTERN.matcher(effectText).matches())  return "YourTurnOnly";
        if (CardData.ONCE_PER_TURN_PATTERN.matcher(effectText).matches())   return "OncePerTurn";
        if (CardData.YOUR_TURN_ONLY_PATTERN.matcher(effectText).find()
                && CardData.ONCE_PER_TURN_PATTERN.matcher(effectText).find()) return "YourTurnOnly+OncePerTurn";
        if (CardData.MAIN_PHASE_ONLY_PATTERN.matcher(effectText).matches())        return "MainPhaseOnly";
        if (CardData.WHILE_PARTY_ATTACKING_PATTERN.matcher(effectText).matches()) return "WhilePartyAttacking";
        if (CardData.WHILE_CARD_ATTACKING_PATTERN.matcher(effectText).matches())  return "WhileCardAttacking";
        if (CardData.WHILE_CARD_BLOCKING_PATTERN.matcher(effectText).matches())   return "WhileCardBlocking";
        if (CardData.WHILE_CARD_IN_HAND_PATTERN.matcher(effectText).matches())   return "WhileCardInHand";
        if (CardData.CONTROL_IF_PATTERN.matcher(effectText).find())                  return "UseRestriction";
        if (CardData.YOUR_TURN_AND_CONTROL_IF_PATTERN.matcher(effectText).find())  return "UseRestriction";
        if (CardData.CONTROL_IF_NOT_ANY_PATTERN.matcher(effectText).find())        return "UseRestriction";
        if (CardData.OPPONENT_CONTROLS_N_OR_MORE_PATTERN.matcher(effectText).find()) return "UseRestriction";
        if (tryParseWhenYouDoSoSequence(effectText, source, 0)          != null) return "WhenYouDoSo";
        if (tryParseIfCastAtLeast(effectText, source, 0)                != null) return "IfCastAtLeast";
        if (tryParseIfControlCondOtherThan(effectText, source, 0)      != null) return "IfControlCondOtherThan";
        if (tryParseIfOppControlsNOrMoreCondTypeGate(effectText, source, 0) != null) return "IfOppControlsNOrMoreCondTypeDraw";
        if (tryParseDiscardConditionalElement(effectText, source, 0)    != null) return "DiscardConditionalElement";
        if (tryParseSelectNumber(effectText, source)          != null) return "SelectNumber";
        if (tryParseForEachJobAndNameDealDamageToForwards(effectText)   != null) return "ForEachJobAndNameDealDamageToForwards";
        if (tryParseSelfGainsWhenAttacksEOT(effectText, source)        != null) return "SelfGainsWhenAttacksEOT";
        if (tryParseDealDamageToForwardsForEach(effectText)         != null) return "DealDamageToForwardsForEach";
        if (tryParseDealDamageToForwardsExceptElement(effectText)          != null) return "DealDamageToForwardsExceptElement";
        if (tryParseRfpAllFwdExceptElementsThenTwiceDeck(effectText)       != null) return "RfpAllFwdExceptElementsThenTwiceDeck";
        if (tryParseDealDamageToForwards(effectText)                       != null) return "DealDamageToForwards";
        if (tryParseNoForwardCostCannotAttack(effectText)           != null) return "NoForwardCostCannotAttack";
        if (tryParseOwnForwardsCannotBeChosenByExBurst(effectText)  != null) return "OwnForwardsCannotBeChosenByExBurst";
        if (tryParseExBurstSuppression(effectText)                  != null) return "ExBurstSuppression";
        if (tryParseDealHalfPowerDamageToForwards(effectText)       != null) return "DealHalfPowerDamageToForwards";
        if (tryParseDealPowerMinusNDamageToForwards(effectText)     != null) return "DealPowerMinusNDamageToForwards";
        if (tryParseDealHalfSourcePowerDamageToForwards(effectText) != null) return "DealHalfSourcePowerDamageToForwards";
        if (tryParseDamageToCombatBlocker(effectText)               != null) return "DamageToCombatBlocker";
        if (MAY_COST_REPLAY_ABILITY.matcher(effectText).find())               return "MayReplayAbility";

        String normalizedEffectText = ELEM_TYPE_OR_ELEM_TYPE.matcher(effectText).replaceAll("$1 or $3 $2");
        String escapedEffectText = escapePeriodInName(normalizedEffectText, source);
        Matcher oneEachM = CHOOSE_ONE_EACH_PATTERN.matcher(normalizedEffectText);
        if (oneEachM.find()) {
            String followupName = matchedFollowupName(oneEachM.group("followup").trim(), source);
            if (followupName != null) return "ChooseOneEach / " + followupName;
            // followup not describable by matchedFollowupName — fall through to tryParseChooseFormerLatter
        }
        if (tryParseChooseForwardRedirectToNamed(normalizedEffectText) != null) return "ChooseForwardRedirectToNamed";
        if (tryParseChooseFormerLatter(normalizedEffectText, source) != null) return "ChooseFormerLatter";
        if (tryParseChooseForwardDealSelfDamageBreakIfCostLeDamage(normalizedEffectText) != null)
            return "ChooseForwardDealSelfDamageBreakIfCostLeDamage";
        if (tryParseChooseForwardSharedPowerLoss(normalizedEffectText, source) != null)
            return "ChooseForwardSharedPowerLoss";
        if (tryParseChooseFwdPowerLeAndOptOppBzFwdRfp(normalizedEffectText) != null)
            return "ChooseFwdPowerLeAndOptOppBzFwdRfp";
        if (tryParseChooseAnyNumberReturnToHand(normalizedEffectText) != null)
            return "ChooseAnyNumberReturnToHand";
        Matcher threeMixedM = CHOOSE_THREE_MIXED_TYPES_PATTERN.matcher(normalizedEffectText);
        if (threeMixedM.find()) {
            String followupName = matchedFollowupName(threeMixedM.group("followup").trim(), source);
            return "ChooseThreeMixedTypes / " + (followupName != null ? followupName : "?");
        }
        Matcher mixedM = CHOOSE_TWO_MIXED_TYPES_PATTERN.matcher(normalizedEffectText);
        if (mixedM.find()) {
            String followupName = matchedFollowupName(mixedM.group("followup").trim(), source);
            return "ChooseTwoMixedTypes / " + (followupName != null ? followupName : "?");
        }
        Matcher chooseM = CHOOSE_CHARACTER_PATTERN.matcher(escapedEffectText);
        if (chooseM.find()) {
            String followup      = restorePeriodInName(chooseM.group("followup").trim(), source);
            // Check damage-instead on the full followup before the ". " split eats the condition clause.
            // This mirrors what tryParseChooseAndFollowup does.
            Matcher insteadM = FOLLOWUP_DAMAGE_INSTEAD.matcher(followup);
            if (insteadM.find() && parseDamageInsteadCondition(insteadM.group("cond").trim()) != null)
                return "ChooseCharacter / DamageInstead";
            if (FOLLOWUP_SELECT_JOB_GRANT.matcher(followup).find())
                return "ChooseCharacter / SelectJobGrant";
            if (FOLLOWUP_MAY_DISCARD_NAMED_DEAL_DAMAGE.matcher(followup).matches())
                return "ChooseCharacter / MayDiscardNamedDealDamage";
            if (FOLLOWUP_RFP_TOP_DECK_AND_DAMAGE_PER_CP.matcher(followup).find())
                return "ChooseCharacter / RfpTopDeckDamagePerCp";
            if (FOLLOWUP_REVEAL_TOP_N_DAMAGE_PER_CP_ADD_ALL_TO_HAND.matcher(followup).find())
                return "ChooseCharacter / RevealTopNDamagePerCpAddAllToHand";
            if (FOLLOWUP_RFP_IF_SAME_TYPE_DRAW.matcher(followup).find())
                return "ChooseCharacter / RfpIfSameTypeDraw";
            if (FOLLOWUP_REVEAL_TOP_N_JOB_DEAL_DMG_PLACE_BOTTOM.matcher(followup).find())
                return "ChooseCharacter / RevealTopNJobDealDmgPlaceBottom";
            int    dotIdx        = followup.indexOf(". ");
            String primaryPart   = dotIdx >= 0 ? followup.substring(0, dotIdx).trim() : followup;
            String secondaryRaw  = dotIdx >= 0 ? followup.substring(dotIdx + 2).trim() : null;
            String secondaryTxt  = secondaryRaw != null ? stripRestrictionSentences(secondaryRaw) : null;
            if (secondaryTxt != null && secondaryTxt.isEmpty()) secondaryTxt = null;
            String followupName  = matchedFollowupName(primaryPart, source);
            String secondaryDesc = null;
            // For AddToHand primaries, prefer the conditional-on-added-card form
            // ("If (it|the added card) (is|has) X, Y") over the generic flat description,
            // because the inner effect would otherwise be reported as if it ran unconditionally.
            if ("AddToHand".equals(followupName) && secondaryTxt != null && !secondaryTxt.isEmpty()) {
                Matcher condM = FOLLOWUP_ADD_TO_HAND_CONDITIONAL_SECONDARY.matcher(secondaryTxt);
                if (condM.matches()
                        && parseRevealCondition(condM.group("cond").trim()) != null) {
                    String innerTxt  = condM.group("inner").trim();
                    String innerDesc = fullDescription(innerTxt, source);
                    if (innerDesc == null) innerDesc = matchedPatternName(innerTxt, source);
                    if (innerDesc == null) innerDesc = matchedFollowupName(innerTxt, source);
                    secondaryDesc = "IfAddedCard(" + (innerDesc != null ? innerDesc : "?") + ")";
                }
            }
            if ("PlayOntoField".equals(followupName) && secondaryTxt != null && !secondaryTxt.isEmpty()) {
                Matcher etfM = FOLLOWUP_PLAY_ONTO_FIELD_WHEN_ENTERS_CONDITIONAL.matcher(secondaryTxt);
                if (etfM.matches() && parseRevealCondition(etfM.group("cond").trim()) != null) {
                    String innerTxt  = etfM.group("inner").trim();
                    String innerDesc = fullDescription(innerTxt, source);
                    if (innerDesc == null) innerDesc = matchedPatternName(innerTxt, source);
                    if (innerDesc == null) innerDesc = matchedFollowupName(innerTxt, source);
                    secondaryDesc = "IfETF(" + (innerDesc != null ? innerDesc : "?") + ")";
                }
            }
            if (secondaryDesc == null && secondaryTxt != null && !secondaryTxt.isEmpty())
                secondaryDesc = fullDescription(secondaryTxt, source);
            if (secondaryDesc == null && secondaryTxt != null && !secondaryTxt.isEmpty())
                secondaryDesc = matchedFollowupName(secondaryTxt, source);
            // Compound-sentence fallback: split secondary on ". " and describe each sentence.
            if (secondaryDesc == null && secondaryTxt != null && !secondaryTxt.isEmpty()) {
                String[] secSentences = secondaryTxt.split("(?<=\\.)\\s+(?=[A-Z])");
                if (secSentences.length > 1) {
                    List<String> parts = new ArrayList<>();
                    for (String s : secSentences) {
                        String d = fullDescription(s.trim(), source);
                        if (d == null) d = matchedPatternName(s.trim(), source);
                        if (d == null) d = matchedFollowupName(s.trim(), source);
                        parts.add(d != null ? d : "?");
                    }
                    secondaryDesc = String.join("+", parts);
                }
            }
            StringBuilder sb = new StringBuilder("ChooseCharacter / ")
                    .append(followupName != null ? followupName : "?");
            if (secondaryDesc != null) sb.append(" + ").append(secondaryDesc);
            else if (secondaryTxt != null && !secondaryTxt.isEmpty()) sb.append(" + ?");
            return sb.toString();
        }

        if (tryParsePlayerCannotCastSummons(effectText)                != null) return "PlayerCannotCastSummons";
        if (tryParseCannotBeChosenStandalone(effectText, source) != null)       return "CannotBeChosen";
        if (tryParseCannotBecomeDullOpp(effectText, source) != null)            return "CannotBecomeDullOpp";
        if (tryParseStandaloneCannotAttackOrBlock(effectText, source) != null) return "CannotAttackOrBlock";
        if (tryParseNegateAllDamage(effectText) != null)                       return "NegateDamage";
        if (tryParsePlayerNextDamageZero(effectText) != null)                  return "PlayerNextDamageZero";
        if (tryParseCancelAutoAbilityAndDamageIfForward(effectText) != null) return "CancelAutoAbilityAndDamageIfForward";
        if (tryParseCancelStackEntry(effectText)              != null) return "CancelSummonOrAutoAbility";
        if (tryParseRedirectAbilityTarget(effectText)         != null) return "RedirectAbilityTarget";
        if (tryParseCancelAbilityOnStack(effectText)          != null) return "CancelAbilityOnStack";
        if (tryParseCancelSummonTargetingMyCharacter(effectText) != null) return "CancelSummonTargetingMyCharacter";
        if (tryParseSelectNumber(effectText, source) != null)               return "SelectNumber";
        if (tryParseChooseOppFwdDynCostBreak(effectText)               != null) return "ChooseOppFwdDynCostBreak";
        if (tryParseChooseFwdPowerInferiorToSource(effectText, source) != null) return "ChooseFwdPowerInferiorToSource";
        if (tryParseChooseFwdBzCostInferiorToRemovedPlay(effectText)   != null) return "ChooseFwdBzCostInferiorToRemovedPlay";
        if (tryParseDullAllOppFwdsPowerLeSource(effectText, source)    != null) return "DullAllOppFwdsPowerLeSource";
        if (tryParseRevealTopBreakSameCostAddToHand(effectText)       != null) return "RevealTopBreakSameCostAddToHand";
        if (tryParseEndOfEachTurnFieldAbility(effectText, source)             != null) return "EndOfEachTurnFieldAbility";
        if (tryParseEndOfEachPlayersTurnIfSelfFwdDamage(effectText, source)  != null) return "EndOfEachPlayersTurnIfSelfFwdDamage";
        if (tryParseBeginningOfMainPhase1EachTurnFieldAbility(effectText, source) != null) return "BeginningOfMainPhase1EachTurnFieldAbility";
        if (tryParseEndOfOpponentTurnFieldAbility(effectText, source)        != null) return "EndOfOpponentTurnFieldAbility";
        if (tryParseIfRfpCount(effectText, source)                     != null) return "IfRfpCount";
        if (tryParseAllFieldEffect(effectText) != null)                     return "AllFieldEffect";
        if (tryParseFieldPowerGrantPassive(effectText) != null) {
            String trimmed = effectText.trim();
            return FIELD_OPPONENT_DEBUFF_PASSIVE.matcher(trimmed).matches()
                    ? "FieldOpponentPowerDebuff" : "FieldPowerGrant";
        }
        {
            Matcher bm = ALL_FIELD_POWER_BOOST_PATTERN.matcher(effectText);
            if (bm.find()) {
                String trailing = effectText.substring(bm.end()).trim().replaceAll("^[.!,]+\\s*", "").trim();
                if (!trailing.isEmpty()) {
                    String secDesc = fullDescription(trailing, source);
                    return "AllFieldPowerBoost + " + (secDesc != null ? secDesc : "?");
                }
                return "AllFieldPowerBoost";
            }
        }
        if (tryParseAllForwardsSameElementAsNamedPowerBoost(effectText) != null) return "AllForwardsSameElementAsNamedPowerBoost";
        if (tryParseAllFieldJobCardNamePowerBoost(effectText) != null)       return "AllFieldJobCardNamePowerBoost";
        if (tryParseTwoCardNamesPowerBoost(effectText) != null)             return "TwoCardNamesPowerBoost";
        if (tryParseAllFieldJobPowerBoost(effectText) != null)              return "AllFieldJobPowerBoost";
        if (tryParseAllFieldJobKeywordGrant(effectText) != null)            return "AllFieldJobKeywordGrant";
        if (tryParseAllFieldKeywordGrant(effectText) != null)               return "AllFieldKeywordGrant";
        if (tryParseUntilEotDualPowerShift(effectText) != null)            return "UntilEotDualPowerShift";
        if (tryParseUntilEotAllFieldPowerBoost(effectText) != null)        return "UntilEotAllFieldPowerBoost";
        if (tryParseStandalonePowerBoostAndAttackTrigger(effectText, source) != null) return "StandalonePowerBoostAndAttackTrigger";
        if (tryParseStandaloneGainsTraitsAndCannotBeBlocked(effectText, source) != null) return "StandaloneGainsTraitsAndCannotBeBlocked";
        if (tryParseStandalonePowerBoostUntil(effectText, source) != null)  return "StandalonePowerBoostUntil";
        if (tryParseStandaloneDoublePowerUntil(effectText, source) != null) return "StandaloneDoublePowerUntil";
        if (tryParseStandaloneDoublesItsPowerUntil(effectText, source) != null) return "StandaloneDoublesItsPowerUntil";
        if (tryParseStandaloneDoublePowerMainPhaseNextTurn(effectText, source) != null) return "StandaloneDoublePowerMainPhaseNextTurn";
        if (tryParseStandalonePowerReduceUntil(effectText, source) != null) return "StandalonePowerReduceUntil";
        if (tryParseDoubleOutgoingDamageThisTurn(effectText, source) != null)    return "DoubleOutgoingDamageThisTurn";
        if (tryParseDoubleOutgoingDamageThisTurnAlt(effectText, source) != null) return "DoubleOutgoingDamageThisTurnAlt";
        if (tryParseDoubleOpponentIncomingDamageThisTurn(effectText) != null)   return "DoubleOpponentIncomingDamageThisTurn";
        if (tryParseAllForwardIncomingDmgIncreaseThisTurn(effectText) != null)  return "AllForwardIncomingDmgIncreaseThisTurn";
        if (tryParseChooseForwardDoubleIncomingThisTurn(effectText) != null)    return "ChooseForwardDoubleIncomingThisTurn";
        if (tryParseChooseForwardDoubleNextOutgoing(effectText) != null)        return "ChooseForwardDoubleNextOutgoing";
        if (tryParseDoublePlayerAbilityOutgoingThisTurn(effectText) != null)   return "DoublePlayerAbilityOutgoingThisTurn";
        if (tryParseStandaloneSelfBoostForEachCrystal(effectText, source) != null) return "StandaloneSelfBoostForEachCrystal";
        if (tryParseStandaloneSelfBoost(effectText, source) != null)        return "StandaloneSelfBoost";
        if (tryParseStandaloneSelfDullAndShield(effectText, source) != null) return "StandaloneSelfDullAndShield";
        if (tryParseStandaloneSelfDull(effectText, source) != null)          return "StandaloneSelfDull";
        if (tryParseStandaloneShieldCannotBeBroken(effectText, source) != null) return "StandaloneShieldCannotBeBroken";
        if (tryParseAllOwnForwardsNullifyAbilityDamage(effectText)        != null) return "AllOwnForwardsNullifyAbilityDamage";
        if (tryParseAllForwardsCannotBlock(effectText)                    != null) return "AllForwardsCannotBlock";
        if (tryParseForwardsOfCostCannotBlock(effectText)                 != null) return "ForwardsOfCostCannotBlock";
        if (tryParseEndOfNextTurnIfCardOnFieldOppLoses(effectText)        != null) return "EndOfNextTurnIfCardOnFieldOppLoses";
        if (tryParseOppFwdsCannotBlockInferiorPower(effectText)           != null) return "OppFwdsCannotBlockInferiorPower";
        if (tryParseAllFwdsBlockedOnlyByLowerCostThisTurn(effectText)    != null) return "AllFwdsBlockedOnlyByLowerCost";
        if (tryParseOppFwdsLoseAllAbilitiesEot(effectText)         != null) return "OppFwdsLoseAllAbilitiesEot";
        if (tryParseOppFwdPowerBoostSuppressedThisTurn(effectText) != null) return "OppFwdPowerBoostSuppressedThisTurn";
        if (tryParseOppFwdsLosePowerPerPlayCost(effectText)        != null) return "OppFwdsLosePowerPerPlayCost";
        if (tryParseStandaloneCannotBeBlocked(effectText, source) != null) return "StandaloneCannotBeBlocked";
        if (tryParseRevealHandOptPickRfpOppDraw(effectText) != null)        return "RevealHandOptPickRfpOppDraw";
        if (tryParseRevealSelectHandRfp(effectText) != null)               return "RevealSelectHandRfp";
        if (tryParseOpponentRandomHandRfp(effectText) != null)              return "OpponentRandomHandRfp";
        if (tryParseOpponentRandomHandToBottomDeck(effectText) != null)     return "OpponentRandomHandToBottomDeck";
        if (tryParseOpponentHandRfp(effectText) != null)                   return "OpponentHandRfp";
        if (tryParseRevealTopNAddUpToExcludingNameRestBz(effectText) != null)  return "RevealTopNAddUpToExcludingNameRestBz";
        if (tryParseRevealTopNTypeCostToHand(effectText)   != null)           return "RevealTopNTypeCostToHand";
        if (tryParseRevealTopNTypeToHand(effectText)       != null)           return "RevealTopNTypeToHand";
        if (tryParseRevealTopNCategoryToHand(effectText)   != null)          return "RevealTopNCategoryToHand";
        if (tryParseRevealTopNJobOrNameToHand(effectText)  != null)          return "RevealTopNJobOrNameToHand";
        if (tryParseRevealTopNElementToHand(effectText)    != null)           return "RevealTopNElementToHand";
        if (tryParseRevealAddTypeToHandOrPlayJobTypeOntoFieldRestBottom(effectText) != null) return "RevealAddTypeToHandOrPlayJobTypeOntoFieldRestBottom";
        if (tryParseReturnNamedToHand(effectText) != null)                   return "ReturnNamedToHand";
        if (tryParseYouMayRemoveNamedFromGame(effectText, source) != null)   return "YouMayRemoveNamedFromGame";
        if (tryParseEndOfOppTurnPlayNamedOntoField(effectText) != null)     return "EndOfOppTurnPlayNamedOntoField";
        if (tryParseRemoveAllOppBzFromGame(effectText)       != null)      return "RemoveAllOppBzFromGame";
        if (tryParseRemoveNamedFromGame(effectText, source) != null)        return "RemoveNamedFromGame";
        if (tryParseBreakSourceCard(effectText, source)        != null)     return "BreakSourceCard";
        if (tryParsePutSourceIntoBreakZone(effectText, source) != null)     return "PutSourceIntoBreakZone";
        if (tryParseYouMayPutSelfToBZWhenDoSo(effectText, source)    != null) return "YouMayPutSelfToBZWhenDoSo";
        if (tryParseIfOppNoForwardsPutToBreakZone(effectText, source)          != null) return "IfOppNoForwardsPutToBreakZone";
        if (tryParseIfEitherPlayerNoForwardsPutSourceToBz(effectText, source)  != null) return "IfEitherPlayerNoForwardsPutSourceToBz";
        if (tryParseIfSelfDamagePointsPutToBreakZone(effectText, source) != null) return "IfSelfDamagePointsPutToBreakZone";
        if (tryParsePutSourceToBottomOfDeck(effectText, source) != null)   return "PutSourceToBottomOfDeck";
        if (tryParseBreakBlockingForward(effectText)           != null)     return "BreakBlockingForward";
        if (tryParseBreakForwardThatBlocksCard(effectText)     != null)     return "BreakForwardThatBlocksCard";
        if (tryParseChooseExBurstFromDamageZone(effectText)    != null)     return "ChooseExBurstFromDamageZone";
        if (tryParseExBurstSuppression(effectText)             != null)     return "ExBurstSuppression";
        if (tryParseDamageZoneSwap(effectText)              != null) {
            Matcher m = DAMAGE_ZONE_SWAP_PATTERN.matcher(effectText.trim());
            return m.matches() && m.group("draw") != null ? "DamageZoneSwap + DrawCards" : "DamageZoneSwap";
        }
        if (tryParseOpponentDrawThenRandomDiscard(effectText) != null)      return "OpponentDrawThenRandomDiscard";
        if (tryParseOpponentDraw(effectText) != null)                       return "OpponentDraw";
        if (tryParseOpponentRandomDiscard(effectText) != null)              return "OpponentRandomDiscard";
        if (tryParseEachPlayerSelectForwardDamage(effectText) != null)      return "EachPlayerSelectForwardDamage";
        if (tryParseBothPlayersSelectForwardToBreakZone(effectText) != null) return "BothPlayersSelectForwardToBreakZone";
        if (tryParseSelectCharCostLeExclToBz(effectText)             != null)  return "SelectCharCostLeExclToBz";
        if (tryParseSelectControlledCharacterToBz(effectText)        != null)  return "SelectControlledCharacterToBz";
        if (tryParseEachPlayerSelectUpToNToBreakZone(effectText) != null)   return "EachPlayerSelectUpToNToBreakZone";
        if (tryParseEachPlayerDiscard(effectText) != null)                  return "EachPlayerDiscard";
        if (tryParseEachPlayerSalvageFromBreakZone(effectText) != null)     return "EachPlayerSalvageFromBreakZone";
        if (tryParseEachPlayerDraw(effectText) != null)                     return "EachPlayerDraw";
        if (tryParseNameCardTypeOpponentDiscardDrawIfMatch(effectText) != null) return "NameCardTypeOpponentDiscardDrawIfMatch";
        if (tryParseOpponentDiscard(effectText) != null)                    return "OpponentDiscard";
        if (tryParseDiscardHandThenDraw(effectText) != null)                return "DiscardHandThenDraw";
        if (tryParseDrawDiscardRetriggerIfCardName(effectText, source) != null) return "DrawDiscardRetriggerIfCardName";
        if (tryParseDrawCards(effectText) != null)                          return "DrawCards";
        if (tryParseYouMayDiscardType(effectText) != null)                  return "YouMayDiscardType";
        if (tryParseMayRevealElementFromHand(effectText) != null)           return "MayRevealElementFromHand";
        if (tryParseDiscardHand(effectText) != null)                        return "DiscardHand";
        if (tryParseDiscardNCards(effectText) != null)                      return "DiscardNCards";
        if (tryParseDiscardJobFromHand(effectText) != null)                 return "DiscardJobFromHand";
        if (tryParseDiscardThenDraw(effectText) != null)                    return "DiscardThenDraw";
        if (tryParseDealPlayerDamageToOpponent(effectText) != null)         return "DealPlayerDamageToOpponent";
        if (tryParseDealPlayerDamageToSelf(effectText) != null)             return "DealPlayerDamageToSelf";
        if (tryParseRandomRevealHandCastIfSummonFree(effectText) != null)   return "RandomRevealHandCastIfSummonFree";
        if (tryParseCastSummonFromHandDiscounted(effectText) != null)       return "CastSummonFromHandDiscounted";
        if (tryParseCastSummonFromHandFree(effectText, 0) != null)          return "CastSummonFromHandFree";
        if (tryParseSearchAndCastSummonFree(effectText) != null)            return "SearchAndCastSummonFree";
        if (tryParsePlayAnyNumberFromHand(effectText, source) != null)      return "PlayAnyNumberFromHand";
        if (tryParsePlayFromHand(effectText, source, 0) != null)            return "PlayFromHand";

        Matcher opSelM = OPPONENT_SELECTS_PATTERN.matcher(effectText);
        if (opSelM.find()) {
            String followup     = opSelM.group("followup").trim();
            String followupName = matchedFollowupName(followup, source);
            return "OpponentSelects / " + (followupName != null ? followupName : "?");
        }

        if (tryParseBzFwdToHandOppFwdToBzByDamage(effectText) != null)      return "BzFwdToHandOppFwdToBzByDamage";
        if (tryParseOpponentMillIfSameElementDraw(effectText) != null)      return "OpponentMillIfSameElementDraw";
        if (tryParseOpponentMill(effectText) != null)                       return "OpponentMill";
        if (tryParseSelfMill(effectText) != null)                           return "SelfMill";
        if (tryParseOpponentRevealHand(effectText) != null)                 return "OpponentRevealHand";
        if (tryParseEachPlayerRevealCharacterMayPlay(effectText) != null)   return "EachPlayerRevealMayPlay";
        if (tryParseEachPlayerMaySearchForwardMinPower(effectText) != null) return "EachPlayerMaySearchForwardMinPower";
        if (tryParseRevealTopDeck(effectText, source) != null)
            return revealTopDeckDescription(effectText, source) + restrictionDesc(effectText);
        if (tryParseStandaloneDamageShields(effectText, source) != null)    return "StandaloneDamageShields";
        if (tryParseDualSearchJobAndTypeDontShareElements(effectText) != null) return "DualSearchDontShareElements";
        if (tryParseSearchNElementSummonsDiffCost(effectText)         != null) return "SearchNElementSummonsDiffCost";
        if (tryParseSearchDeck(effectText, source, 0) != null)              return "SearchDeck";
        if (tryParsePlayAllByNameFromBreakZone(effectText) != null)         return "PlayAllByNameFromBreakZone";
        if (tryParsePlaySourceFromBreakZone(effectText, source) != null)    return "PlaySourceFromBreakZone";
        if (tryParseActivateNamedCard(effectText) != null)                  return "ActivateNamedCard";
        if (tryParseOpponentCannotSearchThisTurn(effectText) != null)       return "OpponentCannotSearch";
        if (tryParseExtraTurnThenLose(effectText) != null)                  return "ExtraTurnThenLose";
        if (tryParseGainCrystalPerX(effectText, 0) != null)                 return "GainCrystalPerX";
        if (tryParseGainCrystal(effectText)        != null)                  return "GainCrystal";
        if (tryParseGainCrystalIfOpponentHas(effectText) != null)            return "GainCrystalIfOpponentHas";
        if (tryParsePlaceCountersForEach(effectText, source) != null)        return "PlaceCountersForEach";
        if (tryParsePlaceCounters(effectText, source) != null)               return "PlaceCounters";
        if (tryParseLookTopDeckOptionallyBreak(effectText)        != null) return "LookTopDeckOptionallyBreak";
        if (tryParseLookTopDeckBottomOrKeep(effectText)           != null) return "LookTopDeckBottomOrKeep";
        if (tryParseChooseOppFwdGainsSpecialAbilityFreeOnce(effectText, source) != null) return "ChooseOppFwdGainsSpecialAbilityFreeOnce";
        if (tryParseChooseOppDamagedFwdIfHasAbilityBreak(effectText)       != null) return "ChooseOppDamagedFwdIfHasAbilityBreak";
        if (tryParseChooseAsManyAsFieldCount(effectText, source)           != null) return "ChooseAsManyAsFieldCount";
        if (tryParseChooseCounterScaleCharsActivate(effectText, 1)         != null) return "ChooseCounterScaleCharsActivate";
        if (tryParseCounterScaleLookAddToHand(effectText, 1)               != null) return "CounterScaleLookAddToHand";
        if (tryParseLookTopDeckAddToHandRestBottom(effectText)          != null) return "LookTopDeckAddToHandRestBottom";
        if (tryParseLookTopDeckAddToHandOneToBreakRestBottom(effectText) != null) return "LookTopDeckAddToHandOneToBreakRestBottom";
        if (tryParseLookTopDeckAddToHandRestBreak(effectText)           != null) return "LookTopDeckAddToHandRestBreak";
        if (tryParseLookTopDeckTopOrBottom(effectText)                  != null) return "LookTopDeckTopOrBottom";
        if (tryParseLookTopDeckReturnTopOrdered(effectText)             != null) return "LookTopDeckReturnTopOrdered";
        if (tryParseLookTopDeckPickOneTopRestBottom(effectText)              != null) return "LookTopDeckPickOneTopRestBottom";
        if (tryParseLookTopDeckCastSummonFreeRestBottom(effectText, 0)       != null) return "LookTopDeckCastSummonFreeRestBottom";
        if (tryParseLookTopDeckPeek(effectText)                              != null) return "LookTopDeckPeek";
        if (tryParseRemoveTopOfDeckFromGame(effectText)                      != null) return "RemoveTopOfDeckFromGame";
        if (tryParseRevealPlayNamedWithMaxCostRestBottom(effectText)           != null) return "RevealPlayNamedWithMaxCostRestBottom";
        if (tryParseFlipUntilTypeToHandRestShuffleBottom(effectText)           != null) return "FlipUntilTypeToHandRestShuffleBottom";
        if (tryParseShuffleThenRevealPlayNamedRestBottom(effectText, source) != null) return "ShuffleThenRevealPlayNamedRestBottom";
        if (tryParseRevealPlayTypeOntoFieldRestBottom(effectText)                != null) return "RevealPlayTypeOntoFieldRestBottom";
        if (tryParseRevealElementCardFromHandIfSoDraw(effectText)                != null) return "RevealElementCardFromHandIfSoDraw";
        if (tryParseRevealPlayElementTypeCostOntoFieldRestBottom(effectText)     != null) return "RevealPlayElementTypeCostOntoFieldRestBottom";
        if (tryParseShuffleDeck(effectText)                              != null) return "ShuffleDeck";
        if (tryParseBackupCpDraw(effectText)                             != null) return "BackupCpDraw";
        if (tryParseAllMonstersTemporaryForward(effectText)            != null) return "AllMonstersTemporaryForward";
        if (tryParseBecomeForwardUntilEot(effectText, source)         != null) return "BecomeForwardUntilEot";
        if (tryParseNameElementOnlySelfBecomes(effectText, source)      != null) return "NameElementOnlySelfBecomes";
        if (tryParseNameElementAndJobSelfBecomes(effectText, source)   != null) return "NameElementAndJobSelfBecomes";
        if (tryParseNameJob(effectText)                                != null) return "NameJob";
        if (tryParseGrantPartyAnyElementThisTurn(effectText)           != null) return "GrantPartyAnyElementThisTurn";
        if (tryParseSourcePowerBecomesRemovedForwardPower(effectText, source) != null) return "SourcePowerBecomesRemovedPower";
        if (tryParseConditionalOpponentHand(effectText, source, 0)    != null) return "ConditionalOpponentHand";
        if (tryParseConditionalOpponentHandMin(effectText, source, 0) != null) return "ConditionalOpponentHandMin";
        if (tryParseYouMayPutSelfToBZWhenDoSo(effectText, source)    != null) return "YouMayPutSelfToBZWhenDoSo";
        if (SELECT_FOLLOWING_ACTIONS_DETECT.matcher(effectText).find())    return "SelectFollowingActions";
        if (CardData.HAS_ALL_ELEMENTS_PATTERN.matcher(effectText.trim()).matches()) return "HasAllElements";
        if (tryParseMultiPlayGrant(effectText) != null)                     return "MultiPlayGrant";
        return null;
    }

    private static String revealTopDeckDescription(String text, CardData source) {
        Matcher m = REVEAL_CLAUSE_PATTERN.matcher(text);
        List<String> clauseDescs = new ArrayList<>();
        while (m.find()) {
            String action = m.group("action").trim();
            String op = normalizeRevealOp(action);
            if (op != null) {
                clauseDescs.add(op);
            } else {
                String effName = matchedPatternName(action, source);
                clauseDescs.add(effName != null ? effName : "?");
            }
        }
        return clauseDescs.isEmpty() ? "RevealTopDeck"
                : "RevealTopDeck / " + String.join(", ", clauseDescs);
    }

    private static String restrictionDesc(String effectText) {
        List<String> parts = new ArrayList<>();
        if (CardData.YOUR_TURN_ONLY_PATTERN.matcher(effectText).find())        parts.add("yourTurnOnly");
        if (CardData.ONCE_PER_TURN_PATTERN.matcher(effectText).find())         parts.add("oncePerTurn");
        if (CardData.MAIN_PHASE_ONLY_PATTERN.matcher(effectText).find())       parts.add("mainPhaseOnly");
        if (CardData.WHILE_PARTY_ATTACKING_PATTERN.matcher(effectText).find()) {
            parts.add("whilePartyAttacking");
        } else {
            Matcher wAtkM = CardData.WHILE_CARD_ATTACKING_PATTERN.matcher(effectText);
            if (wAtkM.find()) parts.add("whileCardAttacking:" + wAtkM.group("card"));
        }
        Matcher wBlkM = CardData.WHILE_CARD_BLOCKING_PATTERN.matcher(effectText);
        if (wBlkM.find()) parts.add("whileCardBlocking:" + wBlkM.group("card"));
        if (CardData.WHILE_CARD_IN_HAND_PATTERN.matcher(effectText).find()) parts.add("whileCardInHand");
        Matcher elemFwdM = CardData.ELEMENT_FORWARD_ENTERED_THIS_TURN_PATTERN.matcher(effectText);
        if (elemFwdM.find()) parts.add("elemFwdEntered:" + elemFwdM.group("element"));
        return parts.isEmpty() ? "" : " [" + String.join(", ", parts) + "]";
    }

    /**
     * Resolves an activated Action Ability:
     * <ol>
     *   <li>Logs the ability being pushed to the stack.</li>
     *   <li>AI (P2) automatically passes priority (no response implemented yet).</li>
     *   <li>Pops and executes the effect; logs an info message if unparsed.</li>
     * </ol>
     *
     * @param ability   the ability being activated
     * @param source    the card that used the ability
     * @param gameState current game state
     * @param ctx       live context for applying effects to the field
     */
    public static void resolve(ActionAbility ability, CardData source,
            GameState gameState, GameContext ctx) {
        resolve(ability, source, gameState, ctx, 0);
    }

    public static void resolve(ActionAbility ability, CardData source,
            GameState gameState, GameContext ctx, int xValue) {
        ctx.logEntry("[Stack] \"" + source.name() + "\" → " + ability.effectText());
        ctx.logEntry("[Stack] P2 passes — resolving");

        Consumer<GameContext> effect = parse(ability.effectText(), source, xValue);
        if (effect != null) {
            effect.accept(ctx);
        } else {
            ctx.logEntry("[ActionResolver] Effect not yet implemented: " + ability.effectText());
        }
    }

    // -------------------------------------------------------------------------
    // Effect parsers
    // -------------------------------------------------------------------------

    /**
     * Parses "Deal X damage to all [condition] Forwards [your opponent controls]".
     *
     * <ul>
     *   <li>No condition — all Forwards (P1 and P2, or opponent only if stated)</li>
     *   <li>condition=dull — only Dulled Forwards</li>
     *   <li>condition=damaged — only Forwards that have already taken damage</li>
     * </ul>
     *
     * Targets are collected before damage is applied.  Forwards are damaged in
     * reverse-index order so that breaks (which shift the list) do not corrupt
     * subsequent indices.
     */
    /**
     * Parses "[if cond,] Select N of the M following actions. "a" "b" ...".
     * Returns an effect that asks the player to choose {@code select} of the quoted
     * sub-actions (via {@link GameContext#chooseActions}), then re-parses and applies
     * each chosen sub-action. Returns {@code null} if the text is not this shape.
     */
    private static Consumer<GameContext> tryParseSelectFollowingActions(String text, CardData source) {
        Matcher m = SELECT_FOLLOWING_ACTIONS.matcher(text);
        if (!m.find()) return null;

        final boolean baseUpTo      = m.group("upTo") != null;
        final int     baseSelect    = Integer.parseInt(m.group("select"));
        String actionsRaw = m.group("actions");

        // Detect inline conditional upgrade:
        // "If you control N or more [E] [T], select [up to] M of the K following actions instead."
        final boolean hasCondUpgrade;
        final int     condMinCount;
        final String  condElem;
        final boolean condInclFwd, condInclBkp, condInclMon;
        final boolean condUpTo;
        final int     condSelect;

        Matcher upgradeM = SELECT_FOLLOWING_ACTIONS_CONDITIONAL_UPGRADE.matcher(actionsRaw);
        if (upgradeM.find()) {
            hasCondUpgrade = true;
            condMinCount   = Integer.parseInt(upgradeM.group("condCount"));
            condElem       = upgradeM.group("condElement");
            String ct      = upgradeM.group("condType").toLowerCase();
            condInclFwd    = ct.startsWith("forward") || ct.startsWith("character");
            condInclBkp    = ct.startsWith("backup")  || ct.startsWith("character");
            condInclMon    = ct.startsWith("monster")  || ct.startsWith("character");
            condUpTo       = upgradeM.group("condUpTo") != null;
            condSelect     = Integer.parseInt(upgradeM.group("condSelect"));
            actionsRaw     = actionsRaw.substring(upgradeM.end());
        } else {
            hasCondUpgrade = false;
            condMinCount   = 0; condElem = null;
            condInclFwd    = false; condInclBkp = false; condInclMon = false;
            condUpTo       = false; condSelect   = 0;
        }

        Matcher qm = SELECT_FOLLOWING_QUOTED_ACTION.matcher(actionsRaw);
        List<String> actions = new ArrayList<>();
        while (qm.find()) actions.add(qm.group(1).trim());
        if (actions.isEmpty()) return null;

        return ctx -> {
            int     effSelect = baseSelect;
            boolean effUpTo   = baseUpTo;
            if (hasCondUpgrade
                    && ctx.selfFieldCount(condElem, condInclFwd, condInclBkp, condInclMon) >= condMinCount) {
                effSelect = condSelect;
                effUpTo   = condUpTo;
            }
            List<String> chosen = ctx.chooseActions(source, actions, effSelect, effUpTo);
            if (chosen == null || chosen.isEmpty()) {
                ctx.logEntry("Select actions — none chosen");
                return;
            }
            for (String actionText : chosen) {
                Consumer<GameContext> effect = parse(actionText, source);
                if (effect == null) {
                    ctx.logEntry("Select actions — unrecognized: " + actionText);
                } else {
                    ctx.logEntry((ctx.isP1() ? "Selected: " : "AI selected ") + actionText);
                    effect.accept(ctx);
                }
            }
        };
    }

    private static Consumer<GameContext> tryParseDealDamageToForwards(String text) {
        Matcher m = DEAL_DAMAGE_TO_FORWARDS.matcher(text);
        if (!m.find() || m.start() != 0) {
            m = DEAL_DAMAGE_TO_FORWARDS_ALT.matcher(text);
            if (!m.find() || m.start() != 0) return null;
        }

        int    damage        = Integer.parseInt(m.group("amount"));
        String condition     = m.group("condition");   // nullable
        String costStr       = m.group("cost");
        int    costVal       = costStr != null ? Integer.parseInt(costStr) : -1;
        String costCmp       = m.group("costcmp");
        String excludeJob    = m.group("excludejob") != null ? m.group("excludejob").trim() : null;
        boolean opponentOnly = m.group("opponent") != null;
        boolean unreduced    = CANNOT_BE_REDUCED_PATTERN.matcher(text).find();

        // Chain any text after the damage clause (e.g. "Philia deals you 1 point of damage.")
        String remainingText = text.substring(m.end()).trim();
        Consumer<GameContext> afterDamage = remainingText.isEmpty() ? null : parse(remainingText, null);

        return ctx -> {
            String condLabel   = condition  != null ? (condition + " ")   : "";
            String costLabel   = costVal >= 0 ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
            String exclLabel   = excludeJob != null ? " [not Job " + excludeJob + "]" : "";
            boolean oppIsP2    = opponentOnly && ctx.isP1();   // ability owner is P1 → opponent is P2
            boolean oppIsP1    = opponentOnly && !ctx.isP1();  // ability owner is P2 → opponent is P1
            String scopeLabel  = opponentOnly ? "opponent's " : "all ";
            String unredLabel  = unreduced ? " (cannot be reduced)" : "";
            ctx.logEntry("Effect: Deal " + damage + " damage to "
                    + scopeLabel + condLabel + "Forwards" + costLabel + exclLabel + unredLabel);

            // --- P2 forwards (included when not opponent-only, or when opponent IS P2) ---
            if (!opponentOnly || oppIsP2) {
                List<Integer> p2Targets = new ArrayList<>();
                for (int i = 0; i < ctx.p2ForwardCount(); i++) {
                    CardData c = ctx.p2Forward(i);
                    if (!meetsCostFilter(c.cost(), costVal, costCmp)) continue;
                    if (excludeJob != null && c.hasJob(excludeJob)) continue;
                    if (meetsCondition(ctx.p2ForwardState(i), ctx.p2ForwardCurrentDamage(i),
                            ctx.isP2ForwardAttacking(i), ctx.isP2ForwardBlocking(i), condition))
                        p2Targets.add(i);
                }
                for (int i = p2Targets.size() - 1; i >= 0; i--) {
                    int idx = p2Targets.get(i);
                    if (idx < ctx.p2ForwardCount()) {
                        if (unreduced) ctx.damageP2ForwardUnreduced(idx, damage);
                        else           ctx.damageP2Forward(idx, damage);
                    }
                }
            }

            // --- P1 forwards (included when not opponent-only, or when opponent IS P1) ---
            if (!opponentOnly || oppIsP1) {
                List<Integer> p1Targets = new ArrayList<>();
                for (int i = 0; i < ctx.p1ForwardCount(); i++) {
                    CardData c = ctx.p1Forward(i);
                    if (!meetsCostFilter(c.cost(), costVal, costCmp)) continue;
                    if (excludeJob != null && c.hasJob(excludeJob)) continue;
                    if (meetsCondition(ctx.p1ForwardState(i), ctx.p1ForwardCurrentDamage(i),
                            ctx.isP1ForwardAttacking(i), ctx.isP1ForwardBlocking(i), condition))
                        p1Targets.add(i);
                }
                for (int i = p1Targets.size() - 1; i >= 0; i--) {
                    int idx = p1Targets.get(i);
                    if (idx < ctx.p1ForwardCount()) {
                        if (unreduced) ctx.damageP1ForwardUnreduced(idx, damage);
                        else           ctx.damageP1Forward(idx, damage);
                    }
                }
            }
            if (afterDamage != null) afterDamage.accept(ctx);
        };
    }

    private static Consumer<GameContext> tryParseDealDamageToForwardsExceptElement(String text) {
        Matcher m = DEAL_DAMAGE_TO_FORWARDS_EXCEPT_ELEMENT.matcher(text);
        if (!m.find() || m.start() != 0) return null;
        int    damage      = Integer.parseInt(m.group("amount"));
        String excludeElem = m.group("excludeelem").trim();
        return ctx -> {
            ctx.logEntry("Effect: Deal " + damage + " damage to all Forwards of all Elements except " + excludeElem);
            for (int i = ctx.p2ForwardCount() - 1; i >= 0; i--) {
                if (ctx.p2Forward(i).containsElement(excludeElem)) continue;
                ctx.damageP2Forward(i, damage);
            }
            for (int i = ctx.p1ForwardCount() - 1; i >= 0; i--) {
                if (ctx.p1Forward(i).containsElement(excludeElem)) continue;
                ctx.damageP1Forward(i, damage);
            }
        };
    }

    private static Consumer<GameContext> tryParseRfpAllFwdExceptElementsThenTwiceDeck(String text) {
        Matcher m = RFP_ALL_FWD_EXCEPT_ELEMENTS_THEN_TWICE_DECK.matcher(text);
        if (!m.find()) return null;
        String elem1 = m.group("elem1");
        String elem2 = m.group("elem2");
        return ctx -> {
            ctx.logEntry("Effect: Remove from game all Forwards other than " + elem1 + " and " + elem2);
            List<ForwardTarget> toRemove = new ArrayList<>();
            for (int i = 0; i < ctx.p1ForwardCount(); i++) {
                CardData fwd = ctx.p1Forward(i);
                if (!fwd.containsElement(elem1) && !fwd.containsElement(elem2))
                    toRemove.add(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD));
            }
            for (int i = 0; i < ctx.p2ForwardCount(); i++) {
                CardData fwd = ctx.p2Forward(i);
                if (!fwd.containsElement(elem1) && !fwd.containsElement(elem2))
                    toRemove.add(new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD));
            }
            sortedByIdxDesc(toRemove, true) .forEach(ctx::removeTargetFromGame);
            sortedByIdxDesc(toRemove, false).forEach(ctx::removeTargetFromGame);
            int deckRfp = toRemove.size() * 2;
            if (deckRfp > 0) {
                ctx.logEntry("Effect: Remove top " + deckRfp + " card(s) of deck from game (2 × " + toRemove.size() + " removed)");
                ctx.removeTopCardsOfDeckFromGame(deckRfp);
            }
        };
    }

    private static Consumer<GameContext> tryParseNoForwardCostCannotAttack(String text) {
        Matcher m = NO_FORWARD_COST_CANNOT_ATTACK.matcher(text.trim());
        if (!m.matches()) return null;
        String costStr = m.group("cost");
        int    costVal = costStr != null ? Integer.parseInt(costStr) : -1;
        String costCmp = m.group("costcmp");
        return ctx -> {
            String label = costVal >= 0
                    ? "cost " + costVal + (costCmp != null ? " or " + costCmp : "")
                    : "any cost";
            ctx.logEntry("Effect: No Forward of " + label + " can attack this turn");
            for (int i = 0; i < ctx.p1ForwardCount(); i++)
                if (meetsCostFilter(ctx.p1Forward(i).cost(), costVal, costCmp)) ctx.setP1ForwardCannotAttack(i);
            for (int i = 0; i < ctx.p2ForwardCount(); i++)
                if (meetsCostFilter(ctx.p2Forward(i).cost(), costVal, costCmp)) ctx.setP2ForwardCannotAttack(i);
        };
    }

    private static Consumer<GameContext> tryParseOwnForwardsCannotBeChosenByExBurst(String text) {
        if (!OWN_FORWARDS_CANNOT_BE_CHOSEN_BY_EX_BURST.matcher(text.trim()).matches()) return null;
        return ctx -> ctx.shieldAllOwnForwardsCannotBeChosen(true, false);
    }

    private static Consumer<GameContext> tryParseExBurstSuppression(String text) {
        if (!EX_BURST_SUPPRESSION_PATTERN.matcher(text.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: EX Bursts due to this ability are suppressed");
            ctx.suppressExBurstsThisAbility();
        };
    }

    private static Consumer<GameContext> tryParseDealDamageToForwardsForEach(String text) {
        Matcher m = DEAL_DAMAGE_TO_FORWARDS_FOR_EACH.matcher(text);
        if (!m.find()) return null;

        int    baseDmg       = Integer.parseInt(m.group("base"));
        String element       = m.group("element");
        String category      = m.group("category");
        String charType      = m.group("chartype");
        String condition     = m.group("condition");
        boolean countOpp     = m.group("oppcount") != null;
        boolean opponentOnly = m.group("opponent") != null;
        boolean unreduced    = CANNOT_BE_REDUCED_PATTERN.matcher(text).find();

        boolean fwd = charType.matches("(?i)Forwards?|Characters?");
        boolean bkp = charType.matches("(?i)Backups?|Characters?");
        boolean mon = charType.matches("(?i)Monsters?|Characters?");
        String elementFilter = element != null ? element.toLowerCase(java.util.Locale.ROOT) : null;

        return ctx -> {
            int n = countOpp
                    ? ctx.countOppFieldCards(fwd, bkp, mon, null, null, category, elementFilter)
                    : ctx.countSelfFieldCards(fwd, bkp, mon, null, null, category, elementFilter);
            int damage = baseDmg * n;
            String controller = countOpp ? "opponent controls" : "you control";
            String multLabel = (element != null ? element + " " : "")
                    + (category != null ? "Category " + category + " " : "")
                    + charType + " " + controller;
            String condLabel = condition != null ? (condition + " ") : "";
            String scopeLabel = opponentOnly ? "opponent's " : "all ";
            String unredLabel = unreduced ? " (cannot be reduced)" : "";
            ctx.logEntry("Effect: Deal " + damage + " damage (" + baseDmg + " x " + n + " "
                    + multLabel + ") to " + scopeLabel + condLabel + "Forwards" + unredLabel);
            if (damage <= 0) return;

            boolean oppIsP2 = opponentOnly && ctx.isP1();
            boolean oppIsP1 = opponentOnly && !ctx.isP1();

            if (!opponentOnly || oppIsP2) {
                List<Integer> p2Targets = new ArrayList<>();
                for (int i = 0; i < ctx.p2ForwardCount(); i++) {
                    if (meetsCondition(ctx.p2ForwardState(i), ctx.p2ForwardCurrentDamage(i),
                            ctx.isP2ForwardAttacking(i), ctx.isP2ForwardBlocking(i), condition))
                        p2Targets.add(i);
                }
                for (int i = p2Targets.size() - 1; i >= 0; i--) {
                    int idx = p2Targets.get(i);
                    if (idx < ctx.p2ForwardCount()) {
                        if (unreduced) ctx.damageP2ForwardUnreduced(idx, damage);
                        else           ctx.damageP2Forward(idx, damage);
                    }
                }
            }
            if (!opponentOnly || oppIsP1) {
                List<Integer> p1Targets = new ArrayList<>();
                for (int i = 0; i < ctx.p1ForwardCount(); i++) {
                    if (meetsCondition(ctx.p1ForwardState(i), ctx.p1ForwardCurrentDamage(i),
                            ctx.isP1ForwardAttacking(i), ctx.isP1ForwardBlocking(i), condition))
                        p1Targets.add(i);
                }
                for (int i = p1Targets.size() - 1; i >= 0; i--) {
                    int idx = p1Targets.get(i);
                    if (idx < ctx.p1ForwardCount()) {
                        if (unreduced) ctx.damageP1ForwardUnreduced(idx, damage);
                        else           ctx.damageP1Forward(idx, damage);
                    }
                }
            }
        };
    }

    private static Consumer<GameContext> tryParseForEachJobAndNameDealDamageToForwards(String text) {
        Matcher m = FOR_EACH_JOB_AND_NAME_DEAL_DAMAGE_TO_FORWARDS.matcher(text);
        if (!m.matches()) return null;
        String job           = m.group("job").trim();
        String cardName      = m.group("cardname").trim();
        int    baseDmg       = Integer.parseInt(m.group("amount"));
        boolean opponentOnly = m.group("opponent") != null;
        return ctx -> {
            int count = ctx.countSelfFieldCards(true, true, true, job, null)
                      + ctx.countSelfFieldCards(true, true, true, null, cardName);
            int damage = baseDmg * count;
            boolean oppIsP2 = opponentOnly && ctx.isP1();
            boolean oppIsP1 = opponentOnly && !ctx.isP1();
            String scopeLabel = opponentOnly ? "opponent's " : "all ";
            ctx.logEntry("Effect: For each Job " + job + " and Card name " + cardName
                    + " (" + count + "), deal " + damage + " damage to " + scopeLabel + "Forwards");
            if (damage <= 0) return;
            if (!opponentOnly || oppIsP2) {
                for (int i = ctx.p2ForwardCount() - 1; i >= 0; i--)
                    if (i < ctx.p2ForwardCount()) ctx.damageP2Forward(i, damage);
            }
            if (!opponentOnly || oppIsP1) {
                for (int i = ctx.p1ForwardCount() - 1; i >= 0; i--)
                    if (i < ctx.p1ForwardCount()) ctx.damageP1Forward(i, damage);
            }
        };
    }

    private static Consumer<GameContext> tryParseDealNForEachJobOrNameToOppForwards(String text) {
        Matcher m = DEAL_N_FOR_EACH_JOB_OR_NAME_TO_OPP_FORWARDS.matcher(text.trim());
        if (!m.matches()) return null;
        String job      = m.group("job").trim();
        String cardName = m.group("cardname").trim();
        int    baseDmg  = Integer.parseInt(m.group("amount"));
        return ctx -> {
            int count = ctx.countSelfFieldCards(true, true, true, job, null)
                      + ctx.countSelfFieldCards(true, true, true, null, cardName);
            int damage = baseDmg * count;
            ctx.logEntry("Effect: Deal " + baseDmg + " × " + count
                    + " (Job " + job + "/Name " + cardName + ") = " + damage + " to all opponent Forwards");
            if (damage <= 0) return;
            if (ctx.isP1()) {
                for (int i = ctx.p2ForwardCount() - 1; i >= 0; i--)
                    if (i < ctx.p2ForwardCount()) ctx.damageP2Forward(i, damage);
            } else {
                for (int i = ctx.p1ForwardCount() - 1; i >= 0; i--)
                    if (i < ctx.p1ForwardCount()) ctx.damageP1Forward(i, damage);
            }
        };
    }

    private static Consumer<GameContext> tryParseDealBasePlusBzNameDamageToForwards(String text) {
        Matcher m = DEAL_BASE_PLUS_BZ_NAME_DAMAGE_TO_FORWARDS.matcher(text);
        if (!m.matches()) return null;
        int    base          = Integer.parseInt(m.group("base"));
        int    per           = Integer.parseInt(m.group("per"));
        String cardName      = m.group("cardname").trim();
        boolean opponentOnly = m.group("opponent") != null;
        return ctx -> {
            int count  = ctx.countSelfBreakZoneCards(cardName, null);
            int damage = base + per * count;
            boolean oppIsP2 = opponentOnly && ctx.isP1();
            boolean oppIsP1 = opponentOnly && !ctx.isP1();
            String scopeLabel = opponentOnly ? "opponent's " : "all ";
            ctx.logEntry("Effect: Deal " + damage + " damage (" + base + " + " + per + "×" + count
                    + " [" + cardName + "] in BZ) to " + scopeLabel + "Forwards");
            if (damage <= 0) return;
            if (!opponentOnly || oppIsP2) {
                for (int i = ctx.p2ForwardCount() - 1; i >= 0; i--)
                    if (i < ctx.p2ForwardCount()) ctx.damageP2Forward(i, damage);
            }
            if (!opponentOnly || oppIsP1) {
                for (int i = ctx.p1ForwardCount() - 1; i >= 0; i--)
                    if (i < ctx.p1ForwardCount()) ctx.damageP1Forward(i, damage);
            }
        };
    }

    private static Consumer<GameContext> tryParseSelfGainsWhenAttacksEOT(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_GAINS_WHEN_ATTACKS_EOT.matcher(text);
        if (!m.matches()) return null;
        String innerText = m.group("inner").trim();
        Consumer<GameContext> innerEffect = tryParseDealDamageToForwards(innerText);
        if (innerEffect == null) return null;
        return ctx -> {
            ctx.logEntry("Effect: " + source.name() + " gains 'When attacks, [effect]' until EOT");
            ctx.addTempAttackTrigger(source, innerEffect);
        };
    }

    private static Consumer<GameContext> tryParseDealHalfPowerDamageToForwards(String text) {
        Matcher m = DEAL_HALF_POWER_DAMAGE_TO_FORWARDS.matcher(text);
        if (!m.find()) return null;

        String  condition    = m.group("condition");
        boolean opponentOnly = m.group("opponent") != null;

        return ctx -> {
            String  condLabel  = condition   != null ? (condition + " ")  : "";
            boolean oppIsP2    = opponentOnly && ctx.isP1();
            boolean oppIsP1    = opponentOnly && !ctx.isP1();
            String  scopeLabel = opponentOnly ? "opponent's " : "all ";
            ctx.logEntry("Effect: Deal each " + scopeLabel + condLabel
                    + "Forward damage equal to half power (round up to nearest 1000)");

            if (!opponentOnly || oppIsP2) {
                List<Integer> p2Targets = new ArrayList<>();
                for (int i = 0; i < ctx.p2ForwardCount(); i++) {
                    if (meetsCondition(ctx.p2ForwardState(i), ctx.p2ForwardCurrentDamage(i),
                            ctx.isP2ForwardAttacking(i), ctx.isP2ForwardBlocking(i), condition))
                        p2Targets.add(i);
                }
                for (int i = p2Targets.size() - 1; i >= 0; i--) {
                    int idx = p2Targets.get(i);
                    if (idx < ctx.p2ForwardCount())
                        ctx.damageP2Forward(idx, halfPowerDamage(ctx.p2Forward(idx).power()));
                }
            }

            if (!opponentOnly || oppIsP1) {
                List<Integer> p1Targets = new ArrayList<>();
                for (int i = 0; i < ctx.p1ForwardCount(); i++) {
                    if (meetsCondition(ctx.p1ForwardState(i), ctx.p1ForwardCurrentDamage(i),
                            ctx.isP1ForwardAttacking(i), ctx.isP1ForwardBlocking(i), condition))
                        p1Targets.add(i);
                }
                for (int i = p1Targets.size() - 1; i >= 0; i--) {
                    int idx = p1Targets.get(i);
                    if (idx < ctx.p1ForwardCount())
                        ctx.damageP1Forward(idx, halfPowerDamage(ctx.p1Forward(idx).power()));
                }
            }
        };
    }

    private static Consumer<GameContext> tryParseDealPowerMinusNDamageToForwards(String text) {
        Matcher m = DEAL_POWER_MINUS_N_DAMAGE_TO_FORWARDS.matcher(text);
        if (!m.find()) return null;

        String  condition    = m.group("condition");
        boolean opponentOnly = m.group("opponent") != null;
        int     reduction    = Integer.parseInt(m.group("amount"));

        return ctx -> {
            String  condLabel  = condition != null ? (condition + " ") : "";
            boolean oppIsP2    = opponentOnly && ctx.isP1();
            boolean oppIsP1    = opponentOnly && !ctx.isP1();
            String  scopeLabel = opponentOnly ? "opponent's " : "all ";
            ctx.logEntry("Effect: Deal each " + scopeLabel + condLabel
                    + "Forward damage equal to its power minus " + reduction);

            if (!opponentOnly || oppIsP2) {
                List<Integer> targets = new ArrayList<>();
                for (int i = 0; i < ctx.p2ForwardCount(); i++) {
                    if (meetsCondition(ctx.p2ForwardState(i), ctx.p2ForwardCurrentDamage(i),
                            ctx.isP2ForwardAttacking(i), ctx.isP2ForwardBlocking(i), condition))
                        targets.add(i);
                }
                for (int i = targets.size() - 1; i >= 0; i--) {
                    int idx = targets.get(i);
                    if (idx < ctx.p2ForwardCount())
                        ctx.damageP2Forward(idx, Math.max(0, ctx.p2Forward(idx).power() - reduction));
                }
            }

            if (!opponentOnly || oppIsP1) {
                List<Integer> targets = new ArrayList<>();
                for (int i = 0; i < ctx.p1ForwardCount(); i++) {
                    if (meetsCondition(ctx.p1ForwardState(i), ctx.p1ForwardCurrentDamage(i),
                            ctx.isP1ForwardAttacking(i), ctx.isP1ForwardBlocking(i), condition))
                        targets.add(i);
                }
                for (int i = targets.size() - 1; i >= 0; i--) {
                    int idx = targets.get(i);
                    if (idx < ctx.p1ForwardCount())
                        ctx.damageP1Forward(idx, Math.max(0, ctx.p1Forward(idx).power() - reduction));
                }
            }
        };
    }

    private static Consumer<GameContext> tryParseDealHalfSourcePowerDamageToForwards(String text) {
        Matcher m = DEAL_HALF_SOURCE_POWER_DAMAGE_TO_FORWARDS.matcher(text);
        if (!m.find()) return null;

        String  sourceName   = m.group("sourcename").trim();
        String  condition    = m.group("condition");
        boolean opponentOnly = m.group("opponent") != null;
        boolean roundUp      = "up".equalsIgnoreCase(m.group("rounding"));

        return ctx -> {
            int raw       = Math.max(0, ctx.fieldForwardPowerByName(sourceName));
            int damage    = roundUp ? halfPowerDamage(raw) : (raw / 2 / 1000) * 1000;
            String condLabel  = condition   != null ? (condition + " ")   : "";
            boolean oppIsP2   = opponentOnly && ctx.isP1();
            boolean oppIsP1   = opponentOnly && !ctx.isP1();
            String  scopeLabel = opponentOnly ? "opponent's " : "all ";
            String  dir        = roundUp ? "up" : "down";
            ctx.logEntry("Effect: Deal " + damage + " damage (half of " + sourceName
                    + "'s power, round " + dir + ") to " + scopeLabel + condLabel + "Forwards");

            if (!opponentOnly || oppIsP2) {
                List<Integer> p2Targets = new ArrayList<>();
                for (int i = 0; i < ctx.p2ForwardCount(); i++) {
                    if (meetsCondition(ctx.p2ForwardState(i), ctx.p2ForwardCurrentDamage(i),
                            ctx.isP2ForwardAttacking(i), ctx.isP2ForwardBlocking(i), condition))
                        p2Targets.add(i);
                }
                for (int i = p2Targets.size() - 1; i >= 0; i--) {
                    int idx = p2Targets.get(i);
                    if (idx < ctx.p2ForwardCount())
                        ctx.damageP2Forward(idx, damage);
                }
            }

            if (!opponentOnly || oppIsP1) {
                List<Integer> p1Targets = new ArrayList<>();
                for (int i = 0; i < ctx.p1ForwardCount(); i++) {
                    if (meetsCondition(ctx.p1ForwardState(i), ctx.p1ForwardCurrentDamage(i),
                            ctx.isP1ForwardAttacking(i), ctx.isP1ForwardBlocking(i), condition))
                        p1Targets.add(i);
                }
                for (int i = p1Targets.size() - 1; i >= 0; i--) {
                    int idx = p1Targets.get(i);
                    if (idx < ctx.p1ForwardCount())
                        ctx.damageP1Forward(idx, damage);
                }
            }
        };
    }

    private static Consumer<GameContext> tryParseDamageToCombatBlocker(String text) {
        Matcher m = DAMAGE_TO_COMBAT_BLOCKER.matcher(text);
        if (!m.find()) return null;
        int    damage = Integer.parseInt(m.group("amount"));
        String name   = m.group("name").trim();
        return ctx -> {
            int blockerIdx = ctx.combatBlockerIdxForAttacker(name, ctx.isP1());
            if (blockerIdx < 0) {
                ctx.logEntry("Effect: Deal " + damage + " damage to blocker of " + name + " — no blocker");
                return;
            }
            ctx.logEntry("Effect: Deal " + damage + " damage to Forward blocking " + name);
            if (ctx.isP1()) ctx.damageP2Forward(blockerIdx, damage);
            else            ctx.damageP1Forward(blockerIdx, damage);
        };
    }

    private static int halfPowerDamage(int power) {
        return (int)(Math.ceil(power / 2.0 / 1000) * 1000);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns {@code true} if {@code cardCost} satisfies the cost constraint, or if {@code costVal < 0} (no filter). */
    private static boolean meetsCostFilter(int cardCost, int costVal, String costCmp) {
        if (costVal < 0) return true;
        if (costCmp == null) return cardCost == costVal;
        return costCmp.equalsIgnoreCase("less") ? cardCost <= costVal : cardCost >= costVal;
    }

    /**
     * Returns {@code true} if a forward satisfies {@code condition}.
     *
     * @param condition {@code "active"}, {@code "dull"}, {@code "damaged"},
     *                  {@code "attacking"}, {@code "blocking"}, or {@code null} (any)
     */
    private static boolean meetsCondition(CardState state, int currentDamage,
            boolean isAttacking, boolean isBlocking, String condition) {
        if (condition == null) return true;
        return switch (condition.toLowerCase()) {
            case "active"         -> state == CardState.ACTIVE;
            case "dull"           -> state == CardState.DULL;
            case "damaged"        -> currentDamage > 0;
            case "attacking"      -> isAttacking;
            case "blocking"       -> isBlocking;
            default               -> true;
        };
    }

    // -------------------------------------------------------------------------
    // Damage-instead condition helpers
    // -------------------------------------------------------------------------

    private static DamageInsteadCondition parseDamageInsteadCondition(String cond) {
        String s = cond.trim();

        // Target-state conditions
        if (s.equalsIgnoreCase("it is active"))
            return new DamageInsteadCondition.TargetIsActive();
        if (s.matches("(?i)it is a Multi-Element (?:Forward|Monster|Character|Backup)?\\s*"))
            return new DamageInsteadCondition.TargetIsMultiElement();

        // Self-state conditions
        if (s.equalsIgnoreCase("you have received a point of damage this turn"))
            return new DamageInsteadCondition.YouReceivedDamageThisTurn();
        if (s.equalsIgnoreCase("you have a Summon in your Break Zone"))
            return new DamageInsteadCondition.YouHaveSummonInBreakZone();

        // Opponent damage count: "your opponent has received N points of damage or more"
        Matcher oppDmgM = java.util.regex.Pattern
                .compile("(?i)your opponent has received (\\d+) points? of damage or more").matcher(s);
        if (oppDmgM.find())
            return new DamageInsteadCondition.OpponentDamageAtLeast(Integer.parseInt(oppDmgM.group(1)));

        // Opponent hand size: "your opponent has N cards or less in their hand"
        Matcher oppHandM = java.util.regex.Pattern
                .compile("(?i)your opponent has (\\d+) cards? or (?:less|fewer) in their hand").matcher(s);
        if (oppHandM.find())
            return new DamageInsteadCondition.OpponentHandAtMost(Integer.parseInt(oppHandM.group(1)));

        // Cards cast this turn: "you have cast N or more cards this turn"
        Matcher castM = java.util.regex.Pattern
                .compile("(?i)you have cast (\\d+) or more cards this turn").matcher(s);
        if (castM.find())
            return new DamageInsteadCondition.YouCastAtLeast(Integer.parseInt(castM.group(1)));

        // Forward count comparison
        if (s.equalsIgnoreCase("the number of Forwards your opponent controls is greater than the number of Forwards you control"))
            return new DamageInsteadCondition.OpponentHasMoreForwards();

        // EX Burst: "<name> results from an EX Burst"
        if (s.matches("(?i).+ results from an EX Burst"))
            return new DamageInsteadCondition.IsExBurst();

        // "If you control …" — delegate to ControlCondition parser
        if (s.toLowerCase().startsWith("you control ")) {
            ControlCondition cc = CardData.parseControlCondition(s.substring("you control ".length()).trim());
            if (cc != null) return new DamageInsteadCondition.YouControl(cc);
        }
        return null;
    }

    /**
     * Parses an action-text string (a followup without target-selection) into a
     * {@code BiConsumer} that applies the action to an already-selected target list.
     * Returns {@code null} if the text is not recognised.
     * Handles: Freeze, Dull+Freeze, Break, Return-to-hand (+draw), Reduce power,
     * and "Deal N damage for each [Category X] Type you control".
     */
    private static java.util.function.BiConsumer<GameContext, List<ForwardTarget>>
            parseTargetAction(String text, int xValue) {
        String t = text.trim();

        // Dull+Freeze must precede plain Freeze (Freeze matches as a substring)
        if (FOLLOWUP_DULL_AND_FREEZE.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::dullAndFreezeTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::dullAndFreezeTarget);
            };

        if (FOLLOWUP_FREEZE.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::freezeTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::freezeTarget);
            };

        if (FOLLOWUP_BREAK.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::breakTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::breakTarget);
            };

        if (FOLLOWUP_ACTIVATE.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::activateTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::activateTarget);
            };

        // Return + draw must precede plain return (draw extends the return text)
        Matcher retDrawM = FOLLOWUP_RETURN_AND_DRAW.matcher(t);
        if (retDrawM.find()) {
            int draws = Integer.parseInt(retDrawM.group("draw"));
            return (ctx, ts) -> {
                for (ForwardTarget ft : ts) {
                    if (ft.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (ft.isP1()) ctx.returnP1ForwardToHand(ft.idx());
                    else           ctx.returnP2ForwardToHand(ft.idx());
                }
                ctx.drawCards(draws);
            };
        }

        if (FOLLOWUP_RETURN_TO_OWNERS_HAND.matcher(t).find())
            return (ctx, ts) -> {
                for (ForwardTarget ft : ts) {
                    if (ft.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (ft.isP1()) ctx.returnP1ForwardToHand(ft.idx());
                    else           ctx.returnP2ForwardToHand(ft.idx());
                }
            };

        // Power reduce — both word orders
        Matcher reduceM = FOLLOWUP_POWER_REDUCE.matcher(t);
        if (reduceM.find()) {
            int reduction = reduceM.group(1) != null ? Integer.parseInt(reduceM.group(1)) : 0;
            EnumSet<CardData.Trait> traits = parseTraits(reduceM.group(2));
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.reduceTarget(ft, reduction, traits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.reduceTarget(ft, reduction, traits));
            };
        }
        // Power reduce for each [element] [type] you control (must precede plain reduce-until)
        Matcher reduceForEachM = FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH.matcher(t);
        if (reduceForEachM.find()) {
            boolean untilPrefix = reduceForEachM.group(1) != null;
            int    perUnit = Integer.parseInt(untilPrefix ? reduceForEachM.group(1) : reduceForEachM.group(4));
            String srcElem = untilPrefix ? reduceForEachM.group("element") : reduceForEachM.group("element2");
            String srcType = (untilPrefix ? reduceForEachM.group("chartype") : reduceForEachM.group("chartype2")).toLowerCase();
            boolean cntFwd = srcType.startsWith("forward") || srcType.startsWith("character");
            boolean cntBkp = srcType.startsWith("backup")  || srcType.startsWith("character");
            boolean cntMon = srcType.startsWith("monster")  || srcType.startsWith("character");
            return (ctx, ts) -> {
                int n = ctx.countSelfFieldCards(cntFwd, cntBkp, cntMon, null, null, null, srcElem);
                int reduction = perUnit * n;
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.reduceTarget(ft, reduction, noTraits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.reduceTarget(ft, reduction, noTraits));
            };
        }
        Matcher reduceUntilM = FOLLOWUP_POWER_REDUCE_UNTIL.matcher(t);
        if (reduceUntilM.find()) {
            int reduction = reduceUntilM.group(1) != null ? Integer.parseInt(reduceUntilM.group(1)) : 0;
            EnumSet<CardData.Trait> traits = parseTraits(reduceUntilM.group(2));
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.reduceTarget(ft, reduction, traits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.reduceTarget(ft, reduction, traits));
            };
        }
        // Bare power reduce with no timing qualifier — used in former/latter splits (implied EOT)
        Matcher reduceBareM = FOLLOWUP_POWER_REDUCE_BARE.matcher(t);
        if (reduceBareM.find()) {
            int reduction = Integer.parseInt(reduceBareM.group(1));
            EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.reduceTarget(ft, reduction, noTraits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.reduceTarget(ft, reduction, noTraits));
            };
        }

        // Until EOT, it also becomes a Forward with N power
        Matcher becomeForwardM = BECOME_FORWARD_UNTIL_EOT_PATTERN.matcher(t);
        if (becomeForwardM.find()) {
            int power = Integer.parseInt(becomeForwardM.group("power"));
            return (ctx, ts) -> ts.forEach(ft -> ctx.makeTargetTemporaryForward(ft, power));
        }

        // Place N [Name] Counter(s) on it
        Matcher placeCounterM = FOLLOWUP_PLACE_COUNTER_ON_IT.matcher(t);
        if (placeCounterM.find()) {
            int    count       = Integer.parseInt(placeCounterM.group("count"));
            String counterName = placeCounterM.group("name").trim();
            return (ctx, ts) -> {
                for (ForwardTarget ft : ts) {
                    CardData card = ft.isP1() ? ctx.p1Forward(ft.idx()) : ctx.p2Forward(ft.idx());
                    ctx.placeCounters(card, counterName, count);
                }
            };
        }

        // Select and remove one counter from the chosen character (dialog if multiple types)
        if (FOLLOWUP_REMOVE_ONE_COUNTER.matcher(t).find()) {
            return (ctx, ts) -> ts.forEach(ctx::removeOneCounterFromTarget);
        }

        // Deal N damage [and/minus M [more] damage] for each [Category X] [Element] Type [of cost N] you control
        Matcher forEachM = FOLLOWUP_DAMAGE_FOR_EACH.matcher(t);
        if (forEachM.find() && forEachM.group("chartype") != null) {
            int    baseDmg  = Integer.parseInt(forEachM.group("base"));
            String perStr   = forEachM.group("per");
            int    perDmg   = perStr != null ? Integer.parseInt(perStr) : 0;
            boolean subtract = "minus".equalsIgnoreCase(forEachM.group("op"));
            String charType = forEachM.group("chartype");
            String category = forEachM.group("category") != null ? forEachM.group("category").trim() : null;
            String element  = forEachM.group("element") != null ? forEachM.group("element").toLowerCase(java.util.Locale.ROOT) : null;
            int    costFilter = forEachM.group("costfilter") != null ? Integer.parseInt(forEachM.group("costfilter")) : -1;
            boolean fwd = charType.matches("(?i)Forwards?|Characters?");
            boolean bkp = charType.matches("(?i)Backups?|Characters?");
            boolean mon = charType.matches("(?i)Monsters?|Characters?");
            return (ctx, ts) -> {
                int n = ctx.countSelfFieldCards(fwd, bkp, mon, null, null, category, element, costFilter);
                int damage = perDmg > 0
                        ? (subtract ? Math.max(0, baseDmg - perDmg * n) : baseDmg + perDmg * n)
                        : baseDmg * n;
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.damageTarget(ft, damage));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.damageTarget(ft, damage));
            };
        }

        return null;
    }

    private static int resolveInsteadDamage(GameContext ctx, ForwardTarget t,
            DamageInsteadCondition cond, int base, int alt) {
        boolean condMet = switch (cond) {
            case DamageInsteadCondition.TargetIsActive() ->
                (t.isP1() ? ctx.p1ForwardState(t.idx()) : ctx.p2ForwardState(t.idx())) == CardState.ACTIVE;
            case DamageInsteadCondition.TargetIsMultiElement() ->
                (t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx())).containsElement("Multi-Element");
            case DamageInsteadCondition.YouControl(ControlCondition cc) ->
                ctx.controlConditionMet(cc);
            case DamageInsteadCondition.YouReceivedDamageThisTurn() ->
                ctx.selfReceivedDamageThisTurn();
            case DamageInsteadCondition.YouHaveSummonInBreakZone() ->
                ctx.selfHasSummonInBreakZone();
            case DamageInsteadCondition.OpponentDamageAtLeast(int min) ->
                ctx.opponentDamageCount() >= min;
            case DamageInsteadCondition.OpponentHandAtMost(int max) ->
                ctx.opponentHandSize() <= max;
            case DamageInsteadCondition.YouCastAtLeast(int min) ->
                ctx.selfCardsCastThisTurn() >= min;
            case DamageInsteadCondition.OpponentHasMoreForwards() ->
                ctx.opponentForwardCount() > ctx.selfForwardCount();
            case DamageInsteadCondition.IsExBurst() ->
                ctx.isExBurst();
        };
        return condMet ? alt : base;
    }

    // -------------------------------------------------------------------------
    // Choose-character effect parser
    // -------------------------------------------------------------------------

    /**
     * Parses "Choose [up to] N [condition] [element] [targets] [of cost X] [control] [zone]
     * [sep] followup".
     *
     * <p>Supported target types: Forward(s), Forward(s) or Monster(s), Backup(s), Character(s).
     * <p>Supported followup actions:
     * <ul>
     *   <li>"Deal [it|them] N damage"                        — fixed damage to each chosen target</li>
     *   <li>"Deal it damage equal to the highest power Forward you control" — damage = highest P1 forward power</li>
     *   <li>"Deal it damage equal to &lt;name&gt;'s power"          — damage = named field card's power</li>
     *   <li>"Deal it damage equal to half of &lt;name&gt;'s power"  — damage = floor(named power / 2) to nearest 1000</li>
     *   <li>"Deal it damage equal to its power [minus N]"    — damage = target's own power (minus N)</li>
     *   <li>"Dull it/them"                 — dulls each chosen target</li>
     *   <li>"Freeze it/them"               — freezes each chosen target</li>
     *   <li>"Dull it/them and freeze…"     — dulls and freezes each chosen target</li>
     *   <li>"Break it/them"                — breaks each chosen target</li>
     *   <li>"Remove it/them from the game" — removes each chosen target from the game</li>
     *   <li>"Play it/them onto the field"  — moves chosen targets from their zone onto the field</li>
     *   <li>"Add it/them to your hand"     — moves chosen targets to P1's hand</li>
     *   <li>"Return it to its owner's hand" — returns chosen forward to its owner's hand</li>
     *   <li>"Return it to your hand"        — returns chosen forward to P1's hand</li>
     *   <li>"it cannot block this turn"    — marks chosen forward as ineligible to block this turn</li>
     *   <li>"If possible, it must block this turn" — marks chosen forward as required to block if eligible</li>
     *   <li>"Put it at the top or bottom of its owner's deck" — player chooses placement</li>
     * </ul>
     */
    private static Consumer<GameContext> tryParseChooseOneEach(String text, CardData source) {
        Matcher m = CHOOSE_ONE_EACH_PATTERN.matcher(text);
        if (!m.find()) return null;

        int    count1     = Integer.parseInt(m.group("count1"));
        String targets1   = m.group("targets1");
        String tgt1Lower  = targets1.toLowerCase();
        boolean fwd1 = tgt1Lower.contains("forward") || tgt1Lower.contains("character");
        boolean bak1 = tgt1Lower.contains("backup")  || tgt1Lower.contains("character");
        boolean mon1 = tgt1Lower.contains("monster") || tgt1Lower.contains("character");

        int    count2     = Integer.parseInt(m.group("count2"));
        String targets2   = m.group("targets2");
        String tgt2Lower  = targets2.toLowerCase();
        boolean fwd2 = tgt2Lower.contains("forward") || tgt2Lower.contains("character");
        boolean bak2 = tgt2Lower.contains("backup")  || tgt2Lower.contains("character");
        boolean mon2 = tgt2Lower.contains("monster") || tgt2Lower.contains("character");

        String followup  = m.group("followup").trim();
        String logPrefix = "Choose " + count1 + " " + targets1 + " (yours) and "
                + count2 + " " + targets2 + " (opponent)";

        if (FOLLOWUP_RETURN_TO_OWNERS_HAND.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(logPrefix + " — Return to owner's hand");
                List<ForwardTarget> selfTs = selectTargets(ctx, count1, false,
                        false, true, null, null, null, false, -1, null, -1, null,
                        fwd1, bak1, mon1, null, null, null, null, false, null, false);
                List<ForwardTarget> oppTs = selectTargets(ctx, count2, false,
                        true, false, null, null, null, false, -1, null, -1, null,
                        fwd2, bak2, mon2, null, null, null, null, false, null, false);
                for (ForwardTarget t : selfTs) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.returnP1ForwardToHand(t.idx());
                    else          ctx.returnP2ForwardToHand(t.idx());
                }
                for (ForwardTarget t : oppTs) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.returnP1ForwardToHand(t.idx());
                    else          ctx.returnP2ForwardToHand(t.idx());
                }
            };
        }

        if (FOLLOWUP_EACH_FORWARD_MUTUAL_POWER_DAMAGE.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(logPrefix + " — Each deals damage equal to its power to the other");
                List<ForwardTarget> selfTs = selectTargets(ctx, count1, false,
                        false, true, null, null, null, false, -1, null, -1, null,
                        fwd1, bak1, mon1, null, null, null, null, false, null, false);
                List<ForwardTarget> oppTs = selectTargets(ctx, count2, false,
                        true, false, null, null, null, false, -1, null, -1, null,
                        fwd2, bak2, mon2, null, null, null, null, false, null, false);
                if (selfTs.isEmpty() || oppTs.isEmpty()) return;
                ForwardTarget selfT = selfTs.get(0);
                ForwardTarget oppT  = oppTs.get(0);
                // Snapshot both powers before either damage is applied
                int selfPower = Math.max(0, ctx.effectiveTargetPower(selfT));
                int oppPower  = Math.max(0, ctx.effectiveTargetPower(oppT));
                ctx.logEntry("Mutual damage: self Forward (" + selfPower + ") ↔ opp Forward (" + oppPower + ")");
                ctx.damageTarget(selfT, oppPower);
                ctx.damageTarget(oppT,  selfPower);
            };
        }

        Matcher btpM = FORMER_BOOST_THEN_POWER_DAMAGE_TO_LATTER.matcher(followup);
        if (btpM.find()) {
            int boost = Integer.parseInt(btpM.group("boost"));
            EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
            return ctx -> {
                ctx.logEntry(logPrefix + " — boost former +" + boost + ", deal its power to latter");
                List<ForwardTarget> selfTs = selectTargets(ctx, count1, false,
                        false, true, null, null, null, false, -1, null, -1, null,
                        fwd1, bak1, mon1, null, null, null, null, false, null, false);
                List<ForwardTarget> oppTs = selectTargets(ctx, count2, false,
                        true, false, null, null, null, false, -1, null, -1, null,
                        fwd2, bak2, mon2, null, null, null, null, false, null, false);
                if (selfTs.isEmpty() || oppTs.isEmpty()) return;
                ctx.boostTarget(selfTs.get(0), boost, noTraits);
                int power = Math.max(0, ctx.effectiveTargetPower(selfTs.get(0)));
                ctx.logEntry("Former power after boost: " + power + " → dealing to latter");
                ctx.damageTarget(oppTs.get(0), power);
            };
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Former/Latter dual-selection parser
    // -------------------------------------------------------------------------

    private record TargetDesc(
            boolean fwd, boolean bkp, boolean mon,
            boolean opponentOnly, boolean selfOnly,
            String condition, String element,
            int costVal, String costCmp,
            String excludeName,
            boolean fromBreakZone, boolean opponentBz) {}

    private static TargetDesc parseTargetDesc(String desc) {
        Matcher m = TARGET_DESC_PATTERN.matcher(desc.trim());
        if (!m.matches()) return null;

        String ct = m.group("cardtype").toLowerCase(java.util.Locale.ROOT);
        boolean fwd = ct.startsWith("forward") || ct.startsWith("character");
        boolean bkp = ct.startsWith("backup")  || ct.startsWith("character");
        boolean mon = ct.startsWith("monster") || ct.startsWith("character");

        String control      = m.group("control");
        boolean opponentOnly = control != null && control.toLowerCase(java.util.Locale.ROOT).contains("opponent");
        boolean selfOnly     = control != null && control.toLowerCase(java.util.Locale.ROOT).contains("you control");

        int    costVal = m.group("cost") != null ? Integer.parseInt(m.group("cost")) : -1;
        String costCmp = m.group("costcmp");

        String  zone       = m.group("zone");
        boolean fromBz     = zone != null;
        boolean opponentBz = fromBz && zone.toLowerCase(java.util.Locale.ROOT).contains("opponent");

        return new TargetDesc(fwd, bkp, mon, opponentOnly, selfOnly,
                m.group("condition"), m.group("element"),
                costVal, costCmp, m.group("excludename"),
                fromBz, opponentBz);
    }

    private static java.util.function.BiConsumer<GameContext, List<ForwardTarget>>
            parseFormerLatterGroupAction(String text) {
        String t = text.trim();

        // "Play it onto the field dull" must precede plain "Play it onto the field"
        if (FOLLOWUP_PLAY_ONTO_FIELD_DULL.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::playTargetOntoFieldDull);
                sortedByIdxDesc(ts, false).forEach(ctx::playTargetOntoFieldDull);
            };

        if (FOLLOWUP_PLAY_ONTO_FIELD.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::playTargetOntoField);
                sortedByIdxDesc(ts, false).forEach(ctx::playTargetOntoField);
            };

        // "Dull or Freeze it" — compact form must precede plain FOLLOWUP_DULL
        if (FOLLOWUP_DULL_OR_FREEZE_COMPACT.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::dullOrFreezeTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::dullOrFreezeTarget);
            };

        if (FOLLOWUP_DULL.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::dullTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::dullTarget);
            };

        // Power boost variants (UNTIL must precede plain BOOST since text may omit the trailing "until")
        Matcher boostUntilM = FOLLOWUP_POWER_BOOST_UNTIL.matcher(t);
        if (boostUntilM.find()) {
            int boost = Integer.parseInt(boostUntilM.group(1));
            EnumSet<CardData.Trait> traits = parseTraits(boostUntilM.group(2));
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.boostTarget(ft, boost, traits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.boostTarget(ft, boost, traits));
            };
        }

        Matcher boostM = FOLLOWUP_POWER_BOOST.matcher(t);
        if (boostM.find()) {
            int boost = Integer.parseInt(boostM.group(1));
            EnumSet<CardData.Trait> traits = parseTraits(boostM.group(2));
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.boostTarget(ft, boost, traits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.boostTarget(ft, boost, traits));
            };
        }

        // "Deal it N damage" — check for a "If you have cast Card Name X other than X this turn" bonus
        Matcher dmgM = FOLLOWUP_DAMAGE.matcher(t);
        if (dmgM.find()) {
            int damage = Integer.parseInt(dmgM.group("amount"));
            Consumer<GameContext> bonus = parseCardNameCastOtherBonusEffect(t.substring(dmgM.end()));
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.damageTarget(ft, damage));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.damageTarget(ft, damage));
                if (bonus != null) bonus.accept(ctx);
            };
        }

        return parseTargetAction(t, 0);
    }

    private static Consumer<GameContext> parseCardNameCastOtherBonusEffect(String suffix) {
        if (suffix == null || suffix.isBlank()) return null;
        Matcher m = CAST_CARD_NAME_OTHER_BONUS.matcher(suffix.trim());
        if (!m.find()) return null;
        String cardName  = m.group("name").trim();
        String bonusText = m.group("effect").trim().replaceAll("\\.$", "");
        Consumer<GameContext> bonusEffect = parse(bonusText, null);
        if (bonusEffect == null) return null;
        return ctx -> {
            if (ctx.countCardsNamedCastThisTurn(cardName) > 1)
                bonusEffect.accept(ctx);
        };
    }

    private static String getTargetCardName(GameContext ctx, ForwardTarget t) {
        if (t.zone() == ForwardTarget.CardZone.FORWARD)
            return (t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx())).name();
        return null;
    }

    private static Consumer<GameContext> tryParseChooseFormerLatter(String text, CardData source) {
        Matcher m = CHOOSE_FORMER_LATTER_PATTERN.matcher(text);
        if (!m.find()) return null;

        String effects      = m.group("effects").trim();
        String effectsLower = effects.toLowerCase(java.util.Locale.ROOT);
        if (!effectsLower.contains("the former") || !effectsLower.contains("the latter")) return null;

        // Parse target descriptors (shared for all effect paths below)
        boolean upTo1  = m.group("upTo1") != null;
        int     count1 = Integer.parseInt(m.group("count1"));
        String  desc1  = m.group("desc1").trim();

        boolean upTo2    = m.group("upTo2") != null;
        int     count2   = Integer.parseInt(m.group("count2"));
        String  desc2Raw = m.group("desc2").trim();

        boolean excludeFirstChosen = false;
        String  desc2 = desc2Raw;
        if (desc2Raw.toLowerCase(java.util.Locale.ROOT).startsWith("other ")) {
            excludeFirstChosen = true;
            desc2 = desc2Raw.substring(6).trim();
        }

        TargetDesc td1 = parseTargetDesc(desc1);
        TargetDesc td2 = parseTargetDesc(desc2);

        // Special case: desc2 has a dynamic cost constraint on a BZ Backup that TARGET_DESC_PATTERN
        // cannot represent (e.g. "Backup with a cost equal to or less than that Forward in your BZ").
        // Parse effects normally and supply the cost filter at execution time.
        if (td2 == null && td1 != null && DESC_BZ_BACKUP_COST_RELATIVE.matcher(desc2).matches()) {
            String kLabel = "Choose " + (upTo1 ? "up to " : "") + count1 + " " + desc1
                          + " and " + (upTo2 ? "up to " : "") + count2 + " " + desc2Raw;
            int kLatterIdx = effectsLower.indexOf("the latter");
            int kAndIdx    = effects.lastIndexOf(" and ", kLatterIdx);
            if (kAndIdx >= 0) {
                String kFmrEff = effects.substring(0, kAndIdx).trim()
                        .replaceAll("(?i)\\bthe\\s+former\\b", "it").replaceAll("\\.$", "").trim();
                String kLtrEff = effects.substring(kAndIdx + 5).trim()
                        .replaceAll("(?i)\\bthe\\s+latter\\b", "it").replaceAll("\\.$", "").trim();
                java.util.function.BiConsumer<GameContext, List<ForwardTarget>> kFmrAct =
                        parseFormerLatterGroupAction(kFmrEff);
                java.util.function.BiConsumer<GameContext, List<ForwardTarget>> kLtrAct =
                        parseFormerLatterGroupAction(kLtrEff);
                if (kFmrAct != null && kLtrAct != null) {
                    final TargetDesc kTd1 = td1;
                    final java.util.function.BiConsumer<GameContext, List<ForwardTarget>>
                            fkFmr = kFmrAct, fkLtr = kLtrAct;
                    return ctx -> {
                        ctx.logEntry(kLabel);
                        List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                                kTd1.opponentOnly(), kTd1.selfOnly(),
                                kTd1.condition(), kTd1.element(), null, false,
                                kTd1.costVal(), kTd1.costCmp(), -1, null,
                                kTd1.fwd(), kTd1.bkp(), kTd1.mon(),
                                null, null, null, kTd1.excludeName(), false, null, false);
                        if (ts1.isEmpty()) return;
                        ForwardTarget fwdTgt = ts1.get(0);
                        CardData fwdCard = fwdTgt.isP1()
                                ? ctx.p1Forward(fwdTgt.idx()) : ctx.p2Forward(fwdTgt.idx());
                        int formerCost = fwdCard.cost();
                        List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                                false, true, null, null, "in your Break Zone", false,
                                formerCost, "less", -1, null,
                                false, true, false,
                                null, null, null, null, false, null, false);
                        fkFmr.accept(ctx, ts1);
                        fkLtr.accept(ctx, ts2);
                    };
                }
            }
            return null;
        }

        if (td1 == null || td2 == null) return null;

        boolean fExcludeFirst = excludeFirstChosen;
        String  fDesc2Static  = td2.excludeName();
        String label = "Choose " + (upTo1 ? "up to " : "") + count1 + " " + desc1
                     + " and " + (upTo2 ? "up to " : "") + count2 + " " + desc2Raw;

        // Special case: "The former gains +N power until end of turn. Then, the former deals
        // damage equal to its power to the latter." — boost, then deal boosted power as damage.
        Matcher btpM = FORMER_BOOST_THEN_POWER_DAMAGE_TO_LATTER.matcher(effects);
        if (btpM.find()) {
            int boost = Integer.parseInt(btpM.group("boost"));
            EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
            return ctx -> {
                ctx.logEntry(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excludeForTs2a = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excludeForTs2a, false, null, false);

                ts1.forEach(t -> ctx.boostTarget(t, boost, noTraits));
                if (!ts1.isEmpty() && !ts2.isEmpty()) {
                    int formerPower = ctx.effectiveTargetPower(ts1.get(0));
                    ts2.forEach(t -> ctx.damageTarget(t, formerPower));
                }
            };
        }

        // Special case: "During this turn, the next damage dealt to the former is [received by|dealt to] the latter instead."
        Matcher redirectM = FORMER_LATTER_DAMAGE_REDIRECT.matcher(effects);
        if (redirectM.find()) {
            String redirectSuffix = redirectM.group("suffix").trim();
            Consumer<GameContext> redirectBonus = redirectSuffix.isEmpty() ? null : parse(redirectSuffix, source);
            return ctx -> {
                ctx.logEntry(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excludeForTs2r = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excludeForTs2r, false, null, false);

                if (!ts1.isEmpty() && !ts2.isEmpty())
                    ctx.redirectNextIncomingDamage(ts1.get(0), ts2.get(0));
                if (redirectBonus != null) redirectBonus.accept(ctx);
            };
        }

        // Special case: "Until the end of the turn, the former gains +N power [and Traits]. Deal the latter N damage."
        Matcher fbtldM = FORMER_BOOST_TRAITS_LATTER_DIRECT_DAMAGE.matcher(effects);
        if (fbtldM.matches()) {
            int boost = Integer.parseInt(fbtldM.group("boost"));
            EnumSet<CardData.Trait> boostTraits = parseTraits(fbtldM.group("traits"));
            int damage = Integer.parseInt(fbtldM.group("damage"));
            String fbtldSuffix = fbtldM.group("suffix").trim();
            Consumer<GameContext> fbtldBonus = fbtldSuffix.isEmpty() ? null : parse(fbtldSuffix, source);
            return ctx -> {
                ctx.logEntry(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excl2fbtld = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excl2fbtld, false, null, false);

                ts1.forEach(t -> ctx.boostTarget(t, boost, boostTraits));
                ts2.forEach(t -> ctx.damageTarget(t, damage));
                if (fbtldBonus != null) fbtldBonus.accept(ctx);
            };
        }

        // Special case: "Until the end of the turn, the former loses [traits]. Then, the latter
        // gains all the abilities lost by the previous effect until the end of the turn."
        Matcher fltgM = FORMER_LOSES_TRAITS_LATTER_GAINS.matcher(effects);
        if (fltgM.matches()) {
            EnumSet<CardData.Trait> traitsToLose = parseTraits(fltgM.group("traits"));
            if (!traitsToLose.isEmpty()) {
                return ctx -> {
                    ctx.logEntry(label);
                    String zone1 = td1.fromBreakZone()
                            ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                    List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                            td1.opponentOnly(), td1.selfOnly(),
                            td1.condition(), td1.element(), zone1, td1.opponentBz(),
                            td1.costVal(), td1.costCmp(), -1, null,
                            td1.fwd(), td1.bkp(), td1.mon(),
                            null, null, null, td1.excludeName(), false, null, false);

                    String excl2flt = fExcludeFirst && !ts1.isEmpty()
                            ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                    String zone2 = td2.fromBreakZone()
                            ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                    List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                            td2.opponentOnly(), td2.selfOnly(),
                            td2.condition(), td2.element(), zone2, td2.opponentBz(),
                            td2.costVal(), td2.costCmp(), -1, null,
                            td2.fwd(), td2.bkp(), td2.mon(),
                            null, null, null, excl2flt, false, null, false);

                    if (!ts1.isEmpty()) {
                        ForwardTarget former = ts1.get(0);
                        EnumSet<CardData.Trait> actuallyLost = EnumSet.noneOf(CardData.Trait.class);
                        for (CardData.Trait tr : traitsToLose)
                            if (ctx.effectiveTargetHasTrait(former, tr)) actuallyLost.add(tr);
                        ctx.removeTraitsUntilEotFromTarget(former, traitsToLose);
                        if (!ts2.isEmpty() && !actuallyLost.isEmpty())
                            ctx.boostTarget(ts2.get(0), 0, actuallyLost);
                    }
                };
            }
        }

        // Special case: escalating BZ-count conditionals (dull former; ≥N1 dull latter; ≥N2 freeze; ≥N3 discard).
        Matcher bzEscM = FORMER_DULL_LATTER_BZ_NAME_ESCALATE.matcher(effects);
        if (bzEscM.matches()) {
            int n1 = Integer.parseInt(bzEscM.group("n1"));
            String bzCardName = bzEscM.group("cardname").trim();
            int n2 = Integer.parseInt(bzEscM.group("n2"));
            int n3 = Integer.parseInt(bzEscM.group("n3"));
            int discardN = Integer.parseInt(bzEscM.group("discardN"));
            return ctx -> {
                ctx.logEntry(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excl2bz = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excl2bz, false, null, false);

                ts1.forEach(ctx::dullTarget);
                int bzCount = ctx.countSelfBreakZoneCards(bzCardName, null);
                if (bzCount >= n1) ts2.forEach(ctx::dullTarget);
                if (bzCount >= n2) {
                    ts1.forEach(ctx::freezeTarget);
                    ts2.forEach(ctx::freezeTarget);
                }
                if (bzCount >= n3) ctx.forceOpponentDiscard(discardN);
            };
        }

        // Special case: "+N power and cannot-dull-by-opp; conditional damage to latter = highest own Forward power."
        Matcher bdicM = FORMER_BOOST_DULL_IMMUNITY_COND_DAMAGE_LATTER.matcher(effects);
        if (bdicM.matches()) {
            int boost = Integer.parseInt(bdicM.group("boost"));
            int dmgThresh = Integer.parseInt(bdicM.group("dmgthresh"));
            EnumSet<CardData.Trait> dullImmunity = EnumSet.of(CardData.Trait.CANNOT_BE_DULLED_BY_OPP);
            return ctx -> {
                ctx.logEntry(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excl2di = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excl2di, false, null, false);

                ts1.forEach(t -> ctx.boostTarget(t, boost, dullImmunity));
                if (ctx.selfDamageCount() >= dmgThresh && !ts2.isEmpty()) {
                    int highestPower = ctx.selfHighestForwardPower();
                    ctx.damageTarget(ts2.get(0), highestPower);
                }
            };
        }

        // Special case: "Break the former. If [card] enters the field due to Warp, also break the latter."
        if (FORMER_BREAK_COND_WARP_LATTER_BREAK.matcher(effects).matches()) {
            return ctx -> {
                ctx.logEntry(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excl2bw = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excl2bw, false, null, false);

                sortedByIdxDesc(ts1, true) .forEach(ctx::breakTarget);
                sortedByIdxDesc(ts1, false).forEach(ctx::breakTarget);
                if (ctx.sourceEnteredViaWarp()) {
                    sortedByIdxDesc(ts2, true) .forEach(ctx::breakTarget);
                    sortedByIdxDesc(ts2, false).forEach(ctx::breakTarget);
                }
            };
        }

        // Special case: "Deal the former N damage. If you control M or more Backups, also deal the latter N damage."
        Matcher bkpDmgM = FORMER_DAMAGE_COND_BACKUP_COUNT_LATTER_DAMAGE.matcher(effects);
        if (bkpDmgM.matches()) {
            int dmg1 = Integer.parseInt(bkpDmgM.group("dmg1"));
            int bkpThresh = Integer.parseInt(bkpDmgM.group("n"));
            int dmg2 = Integer.parseInt(bkpDmgM.group("dmg2"));
            return ctx -> {
                ctx.logEntry(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excl2bd = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excl2bd, false, null, false);

                ts1.forEach(t -> ctx.damageTarget(t, dmg1));
                if (ctx.countSelfFieldCards(false, true, false, null, null) >= bkpThresh)
                    ts2.forEach(t -> ctx.damageTarget(t, dmg2));
            };
        }

        // Special case: "The former deals damage equal to its power to the latter."
        if (FORMER_DEALS_POWER_DAMAGE_TO_LATTER.matcher(effects).matches()) {
            return ctx -> {
                ctx.logEntry(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excl2fp = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excl2fp, false, null, false);

                if (!ts1.isEmpty() && !ts2.isEmpty()) {
                    int formerPower = ctx.effectiveTargetPower(ts1.get(0));
                    ctx.damageTarget(ts2.get(0), formerPower);
                }
            };
        }

        // Generic split: prefer comma-after-former when it precedes the " and " split point,
        // since some cards use ", Action the latter" instead of "and Action the latter".
        // (e.g. "Break the former, dull and Freeze the latter.")
        int latterIdx = effectsLower.indexOf("the latter");
        int andIdx    = effects.lastIndexOf(" and ", latterIdx);
        int formerIdx = effectsLower.indexOf("the former");

        int splitIdx = andIdx, splitLen = 5;
        if (formerIdx >= 0) {
            // Look for ", " after the end of the "the former" phrase
            int commaAfterFormer = effects.indexOf(", ", formerIdx + 10);
            if (commaAfterFormer >= 0 && commaAfterFormer < latterIdx
                    && (andIdx < 0 || commaAfterFormer < andIdx)) {
                // Guard: don't use comma split if the latter portion starts with "and "
                // (that's an Oxford comma before the real "and", not a true split point)
                String afterComma = effects.substring(commaAfterFormer + 2).trim().toLowerCase(java.util.Locale.ROOT);
                if (!afterComma.startsWith("and ")) {
                    splitIdx = commaAfterFormer;
                    splitLen = 2;
                }
            }
        }
        if (splitIdx < 0) return null;

        String formerRaw = effects.substring(0, splitIdx).trim();
        String latterRaw = effects.substring(splitIdx + splitLen).trim();

        // Substitute pronouns and strip any trailing period
        String formerEff = formerRaw.replaceAll("(?i)\\bthe\\s+former\\b", "it").replaceAll("\\.$", "").trim();
        String latterEff = latterRaw.replaceAll("(?i)\\bthe\\s+latter\\b", "it").replaceAll("\\.$", "").trim();

        java.util.function.BiConsumer<GameContext, List<ForwardTarget>> formerAction =
                parseFormerLatterGroupAction(formerEff);
        java.util.function.BiConsumer<GameContext, List<ForwardTarget>> latterAction =
                parseFormerLatterGroupAction(latterEff);
        if (formerAction == null || latterAction == null) return null;

        java.util.function.BiConsumer<GameContext, List<ForwardTarget>> fFormerAction = formerAction;
        java.util.function.BiConsumer<GameContext, List<ForwardTarget>> fLatterAction = latterAction;

        return ctx -> {
            ctx.logEntry(label);
            String zone1 = td1.fromBreakZone()
                    ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
            List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                    td1.opponentOnly(), td1.selfOnly(),
                    td1.condition(), td1.element(), zone1, td1.opponentBz(),
                    td1.costVal(), td1.costCmp(), -1, null,
                    td1.fwd(), td1.bkp(), td1.mon(),
                    null, null, null, td1.excludeName(), false, null, false);

            String excludeForTs2 = fExcludeFirst && !ts1.isEmpty()
                    ? getTargetCardName(ctx, ts1.get(0))
                    : fDesc2Static;

            String zone2 = td2.fromBreakZone()
                    ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
            List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                    td2.opponentOnly(), td2.selfOnly(),
                    td2.condition(), td2.element(), zone2, td2.opponentBz(),
                    td2.costVal(), td2.costCmp(), -1, null,
                    td2.fwd(), td2.bkp(), td2.mon(),
                    null, null, null, excludeForTs2, false, null, false);

            fFormerAction.accept(ctx, ts1);
            fLatterAction.accept(ctx, ts2);
        };
    }

    /**
     * Parses "Choose 1 Forward you control other than [CardName]. During this turn, the next
     * damage dealt to it is dealt to [CardName] instead." — one-shot damage redirect where the
     * player picks a Forward to shield and a named card on the field absorbs the damage.
     */
    private static Consumer<GameContext> tryParseChooseForwardRedirectToNamed(String text) {
        Matcher m = CHOOSE_FORWARD_REDIRECT_TO_NAMED.matcher(text);
        if (!m.find()) return null;

        String shieldName   = m.group("shield").trim();
        String redirectName = m.group("redirect").trim();
        if (!shieldName.equalsIgnoreCase(redirectName)) return null;

        String logMsg = "Choose 1 Forward you control other than " + shieldName
                + " → redirect next incoming damage to " + shieldName;

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            List<ForwardTarget> targets = selectTargets(ctx, 1, false,
                    false, true,
                    null, null, null, false,
                    -1, null, -1, null,
                    true, false, false,
                    null, null, null, shieldName,
                    false, null, false);
            if (targets.isEmpty()) return;

            List<ForwardTarget> redirectTargets = selectTargets(ctx, 1, false,
                    false, true,
                    null, null, null, false,
                    -1, null, -1, null,
                    true, false, false,
                    null, redirectName, null, null,
                    false, null, false);
            if (redirectTargets.isEmpty()) return;

            ctx.redirectNextIncomingDamage(targets.get(0), redirectTargets.get(0));
        };
    }

    private static Consumer<GameContext> tryParseChooseTwoMixedTypes(String text, CardData source) {
        Matcher m = CHOOSE_TWO_MIXED_TYPES_PATTERN.matcher(text);
        if (!m.find()) return null;

        int count1 = Integer.parseInt(m.group("count1"));
        String tgt1 = m.group("type1").toLowerCase();
        boolean fwd1 = tgt1.contains("forward") || tgt1.contains("character");
        boolean bak1 = tgt1.contains("backup")  || tgt1.contains("character");
        boolean mon1 = tgt1.contains("monster") || tgt1.contains("character");

        int count2 = Integer.parseInt(m.group("count2"));
        String tgt2 = m.group("type2").toLowerCase();
        boolean fwd2 = tgt2.contains("forward") || tgt2.contains("character");
        boolean bak2 = tgt2.contains("backup")  || tgt2.contains("character");
        boolean mon2 = tgt2.contains("monster") || tgt2.contains("character");

        String control = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null &&  control.toLowerCase().contains("you control");

        String followup = m.group("followup").trim();
        java.util.function.BiConsumer<GameContext, List<ForwardTarget>> action = parseTargetAction(followup, 0);
        if (action == null) return null;

        String label = "Choose " + count1 + " " + m.group("type1") + " and " + count2 + " " + m.group("type2");
        return ctx -> {
            ctx.logEntry(label);
            List<ForwardTarget> ts1 = selectTargets(ctx, count1, false, opponentOnly, selfOnly,
                    null, null, null, false, -1, null, -1, null,
                    fwd1, bak1, mon1, null, null, null, null, false, null, false);
            List<ForwardTarget> ts2 = selectTargets(ctx, count2, false, opponentOnly, selfOnly,
                    null, null, null, false, -1, null, -1, null,
                    fwd2, bak2, mon2, null, null, null, null, false, null, false);
            List<ForwardTarget> all = new ArrayList<>(ts1);
            all.addAll(ts2);
            action.accept(ctx, all);
        };
    }

    /**
     * Parses "Choose 1 Forward with N power or less and up to 1 Forward in your opponent's
     * Break Zone. Remove them from the game."
     * <p>
     * Selects one field Forward (either player) with power ≤ N, plus optionally one Forward
     * from the opponent's Break Zone, then removes both from the game.
     */
    private static Consumer<GameContext> tryParseChooseFwdPowerLeAndOptOppBzFwdRfp(String text) {
        Matcher m = CHOOSE_FWD_POWER_LE_AND_OPT_OPP_BZ_FWD_RFP.matcher(text);
        if (!m.find()) return null;

        final int powerCeil = Integer.parseInt(m.group("power"));

        return ctx -> {
            ctx.logEntry("Choose 1 Forward with power ≤ " + powerCeil
                    + " and up to 1 Forward from opponent's Break Zone — Remove from game");
            List<ForwardTarget> fieldTs = selectTargets(ctx, 1, false, false, false,
                    null, null, null, false,
                    -1, null, powerCeil, "less",
                    true, false, false, null, null, null, null, false, null, false);
            List<ForwardTarget> bzTs = selectTargets(ctx, 1, true, false, false,
                    null, null, "in your opponent's Break Zone", true,
                    -1, null, -1, null,
                    true, false, false, null, null, null, null, false, null, false);
            List<ForwardTarget> all = new ArrayList<>(fieldTs);
            all.addAll(bzTs);
            sortedByIdxDesc(all, true) .forEach(t -> ctx.removeTargetFromGame(t));
            sortedByIdxDesc(all, false).forEach(t -> ctx.removeTargetFromGame(t));
        };
    }

    private static Consumer<GameContext> tryParseChooseThreeMixedTypes(String text, CardData source) {
        Matcher m = CHOOSE_THREE_MIXED_TYPES_PATTERN.matcher(text);
        if (!m.find()) return null;

        int count1 = Integer.parseInt(m.group("count1"));
        String tgt1 = m.group("type1").toLowerCase();
        boolean fwd1 = tgt1.contains("forward") || tgt1.contains("character");
        boolean bak1 = tgt1.contains("backup")  || tgt1.contains("character");
        boolean mon1 = tgt1.contains("monster") || tgt1.contains("character");

        int count2 = Integer.parseInt(m.group("count2"));
        String tgt2 = m.group("type2").toLowerCase();
        boolean fwd2 = tgt2.contains("forward") || tgt2.contains("character");
        boolean bak2 = tgt2.contains("backup")  || tgt2.contains("character");
        boolean mon2 = tgt2.contains("monster") || tgt2.contains("character");

        int count3 = Integer.parseInt(m.group("count3"));
        String tgt3 = m.group("type3").toLowerCase();
        boolean fwd3 = tgt3.contains("forward") || tgt3.contains("character");
        boolean bak3 = tgt3.contains("backup")  || tgt3.contains("character");
        boolean mon3 = tgt3.contains("monster") || tgt3.contains("character");

        String followup = m.group("followup").trim();
        String label = "Choose up to " + count1 + " " + m.group("type1")
                + ", up to " + count2 + " " + m.group("type2")
                + ", and up to " + count3 + " " + m.group("type3");

        if (FOLLOWUP_REMOVE_FROM_GAME.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(label + " — Remove From Game");
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, true, false, false,
                        null, null, null, false, -1, null, -1, null,
                        fwd1, bak1, mon1, null, null, null, null, false, null, false);
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, true, false, false,
                        null, null, null, false, -1, null, -1, null,
                        fwd2, bak2, mon2, null, null, null, null, false, null, false);
                List<ForwardTarget> ts3 = selectTargets(ctx, count3, true, false, false,
                        null, null, null, false, -1, null, -1, null,
                        fwd3, bak3, mon3, null, null, null, null, false, null, false);
                List<ForwardTarget> all = new ArrayList<>(ts1);
                all.addAll(ts2);
                all.addAll(ts3);
                sortedByIdxDesc(all, true) .forEach(t -> ctx.removeTargetFromGame(t));
                sortedByIdxDesc(all, false).forEach(t -> ctx.removeTargetFromGame(t));
            };
        }

        java.util.function.BiConsumer<GameContext, List<ForwardTarget>> action = parseTargetAction(followup, 0);
        if (action == null) return null;

        return ctx -> {
            ctx.logEntry(label);
            List<ForwardTarget> ts1 = selectTargets(ctx, count1, true, false, false,
                    null, null, null, false, -1, null, -1, null,
                    fwd1, bak1, mon1, null, null, null, null, false, null, false);
            List<ForwardTarget> ts2 = selectTargets(ctx, count2, true, false, false,
                    null, null, null, false, -1, null, -1, null,
                    fwd2, bak2, mon2, null, null, null, null, false, null, false);
            List<ForwardTarget> ts3 = selectTargets(ctx, count3, true, false, false,
                    null, null, null, false, -1, null, -1, null,
                    fwd3, bak3, mon3, null, null, null, null, false, null, false);
            List<ForwardTarget> all = new ArrayList<>(ts1);
            all.addAll(ts2);
            all.addAll(ts3);
            action.accept(ctx, all);
        };
    }

    private static Consumer<GameContext> tryParseChooseCharacter(String text, CardData source, int xValue) {
        text = ELEM_TYPE_OR_ELEM_TYPE.matcher(text).replaceAll("$1 or $3 $2");
        text = escapePeriodInName(text, source);
        Matcher m = CHOOSE_CHARACTER_PATTERN.matcher(text);
        if (!m.find()) return null;

        boolean any          = m.group("anycount") != null;
        boolean upTo         = m.group("upto") != null;
        int     maxCount     = any ? Integer.MAX_VALUE : Integer.parseInt(m.group("count"));
        String  rawElement   = m.group("element");
        String  element      = rawElement != null && rawElement.contains(" or ")
                ? rawElement.replaceAll("(?i)\\s+or\\s+", "|") : rawElement;
        // Resolve condition: "blocking [Name]"/"blocking a Job [Job]" overrides the standard condition.
        // Post-target qualifiers ("that entered the field this turn") are normalized to the same string.
        String  rawCondition  = m.group("condition");
        String  postCondition = m.group("postcondition");
        String  blockingName  = m.group("blockingname");
        String  blockingJob   = m.group("blockingjob");
        String  traitGroup    = m.group("trait");
        String  condition     = blockingName  != null ? "blocking:"     + blockingName.trim()
                              : blockingJob   != null ? "blocking-job:" + blockingJob.trim()
                              : postCondition != null ? "entered the field this turn"
                              : traitGroup    != null ? "trait:"        + traitGroup.trim().replace(" ", "_").toUpperCase(java.util.Locale.ROOT)
                              : rawCondition;
        String  targets      = m.group("targets");
        String  tgtLower = targets.toLowerCase();
        String  jobFilter;
        String  cardNameFilter;
        boolean inclForwards;
        boolean inclBackups;
        boolean inclMonsters;

        if (tgtLower.startsWith("[job ")) {
            Matcher jm = JOB_BRACKET_PATTERN.matcher(targets);
            jobFilter      = jm.find() ? jm.group(1).trim() : null;
            cardNameFilter = null;
            inclForwards   = true;
            inclBackups    = false;
            inclMonsters   = false;
        } else if (tgtLower.startsWith("[card name ")) {
            Matcher nm = CARD_NAME_BRACKET_PATTERN.matcher(targets);
            cardNameFilter = nm.find() ? nm.group(1).trim() : null;
            jobFilter      = null;
            inclForwards   = true;
            inclBackups    = true;
            inclMonsters   = true;
        } else if (tgtLower.startsWith("card name ") && tgtLower.contains(" or job ")) {
            // "Card Name X Forward or Job Y Forward" — mixed card-name + job filter, both typed
            int orJobIdx = tgtLower.indexOf(" or job ");
            String cardNamePart = targets.substring("Card Name ".length(), orJobIdx).trim();
            cardNameFilter = cardNamePart.replaceAll("(?i)\\s+(?:Forwards?|Backups?|Monsters?|Characters?)$", "").trim();
            String jobPart = targets.substring(orJobIdx + " or job ".length()).trim();
            jobFilter    = jobPart.replaceAll("(?i)\\s+(?:Forwards?|Backups?|Monsters?|Characters?)$", "").trim();
            inclForwards = tgtLower.contains("forward");
            inclBackups  = tgtLower.contains("backup");
            inclMonsters = tgtLower.contains("monster");
        } else if (tgtLower.startsWith("card name ")) {
            // Support "Card Name X" and "Card Name X or Card Name Y [or …]"
            String rest = targets.substring("Card Name ".length());
            String[] nameParts = rest.split("(?i)\\s+or\\s+Card\\s+Name\\s+");
            cardNameFilter = String.join("|", nameParts).trim();
            jobFilter      = null;
            inclForwards   = true;
            inclBackups    = true;
            inclMonsters   = true;
        } else if (tgtLower.startsWith("job ") && tgtLower.contains("or card name ")) {
            int orCnIdx    = tgtLower.indexOf("or card name ");
            String rawJob  = targets.substring("Job ".length(), orCnIdx)
                                    .trim().replaceAll("(?i)\\s*and\\s*/\\s*$", "").trim();
            List<String> jobParts = new ArrayList<>();
            for (String p : rawJob.split("(?i)\\s+or\\s+Job\\s+")) jobParts.add(p.trim());
            jobFilter      = String.join("|", jobParts);
            cardNameFilter = targets.substring(orCnIdx + "or card name ".length()).trim();
            inclForwards   = true;
            inclBackups    = true;
            inclMonsters   = true;
        } else if (tgtLower.startsWith("job ")) {
            List<String> jobs = new ArrayList<>();
            Matcher wm = JOB_WRITTEN_SEGMENT.matcher(targets);
            while (wm.find()) jobs.add(wm.group(1).trim());
            boolean bareJob = jobs.isEmpty();
            if (bareJob)
                for (String p : targets.substring("Job ".length()).trim().split("(?i)\\s+or\\s+Job\\s+"))
                    jobs.add(p.trim());
            jobFilter      = String.join("|", jobs);
            cardNameFilter = null;
            inclForwards   = true;
            inclBackups    = bareJob;
            inclMonsters   = bareJob;
        } else {
            jobFilter      = null;
            cardNameFilter = null;
            boolean isGenericCard = tgtLower.equals("card") || tgtLower.equals("cards");
            inclForwards   = isGenericCard || tgtLower.contains("forward") || tgtLower.contains("character");
            inclBackups    = isGenericCard || tgtLower.contains("backup")  || tgtLower.contains("character");
            inclMonsters   = isGenericCard || tgtLower.contains("monster") || tgtLower.contains("character");
        }
        boolean inclSummons  = tgtLower.contains("summon")
                           || tgtLower.equals("card") || tgtLower.equals("cards");
        String  categoryFilter = m.group("category");
        String  excludeName      = restorePeriodInName(m.group("excludename") != null ? m.group("excludename").trim() : null, source);
        String  rawExcludeKw     = m.group("excludekw");
        boolean withoutMulticard = "Multicard".equalsIgnoreCase(rawExcludeKw != null ? rawExcludeKw.trim() : null);
        String  rawExcludeElem = m.group("excludeelem");
        final String fExcludeElem = rawExcludeElem != null ? rawExcludeElem.trim() : null;
        String  costStr      = m.group("cost");
        String  costListStr  = m.group("costlist");
        String  rawCostCmp   = m.group("costcmp");
        int     costVal      = costStr != null ? Integer.parseInt(costStr) : -1;
        // Convert digit-valued costcmp into the "or_…" sentinel understood by meetsCostConstraint.
        // Supports single ("cost N or M") and list ("cost A, B, … or Z") forms.
        String  costCmp;
        if (rawCostCmp != null && rawCostCmp.matches("\\d+")) {
            String tail = costListStr != null
                    ? costListStr.replaceAll("\\s+", "") + "," + rawCostCmp
                    : rawCostCmp;
            costCmp = "or_" + tail;
        } else {
            costCmp = rawCostCmp;
        }
        String  powerStr     = m.group("power");
        String  powerCmp     = m.group("powercmp");
        int     powerVal     = powerStr != null ? Integer.parseInt(powerStr) : -1;
        String  control      = m.group("control");
        boolean opponentOnly = control != null && !control.equalsIgnoreCase("you control");
        boolean selfOnly     = "you control".equalsIgnoreCase(control);
        String  zone         = m.group("zone");
        boolean bothZones    = zone != null && (zone.toLowerCase(java.util.Locale.ROOT).contains("either player")
                                             || zone.toLowerCase(java.util.Locale.ROOT).contains("any player"));
        boolean opponentZone = zone != null && !bothZones && zone.toLowerCase(java.util.Locale.ROOT).contains("opponent");

        String  followup     = restorePeriodInName(m.group("followup").trim(), source);
        boolean unreduced    = CANNOT_BE_REDUCED_PATTERN.matcher(followup).find();

        // If the followup contains ". " (sentence boundary), split into a primary effect
        // (applied to selected targets) and a secondary standalone effect that follows.
        // E.g. "Break it. <name> deals you 1 damage." → primary="Break it", secondary parsed separately.
        final String primaryFollowup;
        final String secondaryText;
        final Consumer<GameContext> secondary;
        {
            int dotSpaceIdx = followup.indexOf(". ");
            if (dotSpaceIdx >= 0) {
                primaryFollowup = followup.substring(0, dotSpaceIdx).trim();
                String stripped = stripRestrictionSentences(followup.substring(dotSpaceIdx + 2).trim());
                secondaryText = stripped.isEmpty() ? null : stripped;
                if (secondaryText == null) {
                    secondary = null;
                } else {
                    // Special case: "You may [cost]. When/If you do so, use this ability again."
                    // Captured here so the replay Consumer closes over the full original effect text.
                    Matcher replayM = MAY_COST_REPLAY_ABILITY.matcher(secondaryText);
                    if (replayM.find()) {
                        String payCost     = replayM.group("payCost");
                        String dullName    = replayM.group("dullName");
                        String discardName = replayM.group("discardName");
                        final String capturedText = text;
                        Consumer<GameContext> replayEffect =
                                ctx2 -> { Consumer<GameContext> inner = parse(capturedText, source, 0); if (inner != null) inner.accept(ctx2); };
                        if (payCost != null) {
                            final String elem = payCost.trim();
                            secondary = ctx -> ctx.mayPayToReplayAbility(elem, replayEffect);
                        } else if (dullName != null) {
                            final String name = dullName.trim();
                            secondary = ctx -> ctx.mayDullActiveCardToReplayAbility(name, replayEffect);
                        } else {
                            final String name = discardName.trim();
                            secondary = ctx -> ctx.mayDiscardCardNameToReplayAbility(name, replayEffect);
                        }
                    } else {
                        // Special case: "That Forward's controller discards N card(s) from their hand."
                        // The discarder depends on the chosen target's controller, which is read back
                        // from GameContext.lastChosenTargets() (populated by selectTargets).
                        Matcher ctrlDiscM = FOLLOWUP_TARGET_CONTROLLER_DISCARDS.matcher(secondaryText);
                        if (ctrlDiscM.matches()) {
                            final int discardCount = Integer.parseInt(ctrlDiscM.group("count"));
                            secondary = ctx -> {
                                List<ForwardTarget> chosen = ctx.lastChosenTargets();
                                for (ForwardTarget t : chosen) {
                                    if (t.isP1() == ctx.isP1()) ctx.selfDiscard(discardCount);
                                    else                        ctx.forceOpponentDiscard(discardCount);
                                }
                            };
                        } else if (FOLLOWUP_BREAK.matcher(secondaryText).find()) {
                            // "Break it." as a secondary applies to the same targets chosen for the primary.
                            secondary = ctx -> {
                                List<ForwardTarget> chosen = ctx.lastChosenTargets();
                                sortedByIdxDesc(chosen, true) .forEach(ctx::breakTarget);
                                sortedByIdxDesc(chosen, false).forEach(ctx::breakTarget);
                            };
                        } else if (FOLLOWUP_CANNOT_BE_BROKEN.matcher(secondaryText).find()
                                || FOLLOWUP_CANNOT_BE_BROKEN_SIMPLE.matcher(secondaryText).find()) {
                            secondary = ctx -> ctx.lastChosenTargets().forEach(ctx::shieldCannotBeBroken);
                        } else if (FOLLOWUP_CANNOT_BE_BROKEN_BY_NON_DMG.matcher(secondaryText).find()) {
                            secondary = ctx -> ctx.lastChosenTargets().forEach(ctx::shieldCannotBeBrokenByNonDmg);
                        } else {
                            Matcher rfpM = SECONDARY_PLAY_REMOVED_ONTO_FIELD.matcher(secondaryText);
                            if (rfpM.find()) {
                                boolean dullIt = rfpM.group("dull") != null;
                                secondary = ctx -> ctx.playLastRemovedFromRfpOntoField(dullIt);
                            } else {
                                Consumer<GameContext> parsed = parse(secondaryText, source);
                                secondary = (parsed != null) ? parsed
                                        : ctx -> ctx.logEntry("[ActionResolver] Secondary followup not yet implemented: " + secondaryText);
                            }
                        }
                    }
                }
            } else {
                primaryFollowup = followup;
                secondaryText   = null;
                secondary = null;
            }
        }

        // Detect "You may [followup]" — followup is optional; player may decline the action after choosing the target
        final boolean followupIsOptional = primaryFollowup.toLowerCase(java.util.Locale.ROOT).startsWith("you may ");
        final String strippedPrimaryFollowup = followupIsOptional
                ? primaryFollowup.substring("You may ".length()).trim() : primaryFollowup;

        // Shared log prefix helper (captured once, reused in all lambdas)
        String costLabel     = CardFilters.formatCostFilterLabel(costVal, costCmp);
        String powerLabel    = powerVal >= 0
                ? " of power " + powerVal + (powerCmp != null ? " or " + powerCmp : "") : "";
        String controlLabel  = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String categoryLabel = categoryFilter != null ? " Category " + categoryFilter : "";
        String excludeLabel  = excludeName != null ? " (excl. " + excludeName + ")" : "";
        String zoneLabel     = zone != null
                ? " in " + (bothZones ? "either player's" : opponentZone ? "opponent's" : "your") + " Break Zone" : "";
        String choosePrefix = "Choose " + (upTo ? "up to " : any ? "any number of " : "") + (maxCount < Integer.MAX_VALUE ? maxCount : "")
                + (condition != null ? " " + condition : "")
                + (element   != null ? " " + element   : "")
                + categoryLabel + " " + targets + costLabel + powerLabel + controlLabel + excludeLabel + zoneLabel;

        // --- "You may discard 1 Card Name X from your hand. If you do so, deal it N damage." ---
        // Checked against the full followup before the primary/secondary split.
        Matcher mayDiscardNamedM = FOLLOWUP_MAY_DISCARD_NAMED_DEAL_DAMAGE.matcher(followup);
        if (mayDiscardNamedM.matches()) {
            String discardName = mayDiscardNamedM.group("cardname").trim();
            int    damage      = Integer.parseInt(mayDiscardNamedM.group("amount"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — May discard Card Name " + discardName + ", if so deal " + damage + " damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ctx.mayDiscardCardNameFromHand(discardName, ctx2 -> {
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx2.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx2.damageTarget(t, damage));
                });
            };
        }

        // --- "Divide N damage" ---
        Matcher divideM = DIVIDE_DAMAGE_PATTERN.matcher(followup);
        if (divideM.find())
        {
            int baseDamage = Integer.parseInt(divideM.group("amount"));
            final boolean equally = divideM.group("mode") != null;

            int dotSpaceIdxCond = followup.indexOf(". ");
            String followup_cond = dotSpaceIdxCond >= 0 ? followup.substring(dotSpaceIdxCond + 2) : "";
            Matcher divideAmp = IF_YOU_CONTROL_CATEGORY.matcher(followup_cond);
            final String  condCategory;
            final boolean condInclFwd, condInclBkp, condInclMon;
            final String  condExcludeName;
            final int     altDamage;
            if (divideAmp.find()) {
                condCategory = divideAmp.group("category").trim();
                String condType = divideAmp.group("type").trim().toLowerCase(java.util.Locale.ROOT);
                condInclFwd = condType.startsWith("forward") || condType.startsWith("character");
                condInclBkp = condType.startsWith("backup")  || condType.startsWith("character");
                condInclMon = condType.startsWith("monster") || condType.startsWith("character");
                condExcludeName = divideAmp.group("name") != null ? divideAmp.group("name").trim()
                        : (source != null ? source.name() : null);

                // Anchored to "divide N damage" specifically — a bare \d+ search would wrongly
                // grab a digit embedded in the category name itself (e.g. "Category FFTA2").
                Matcher mAmp = DIVIDE_DAMAGE_PATTERN.matcher(followup_cond);
                altDamage = mAmp.find() ? Integer.parseInt(mAmp.group("amount")) : baseDamage;
            } else {
                condCategory = null;
                condInclFwd = condInclBkp = condInclMon = false;
                condExcludeName = null;
                altDamage = baseDamage;
            }

            final int fBaseDamage = baseDamage;
            return ctx -> {
                int fDamage = fBaseDamage;
                if (condCategory != null) {
                    int totalCat = ctx.isP1()
                            ? ctx.countP1FieldCards(condInclFwd, condInclBkp, condInclMon, null, null, condCategory)
                            : ctx.countP2FieldCards(condInclFwd, condInclBkp, condInclMon, null, null, condCategory);
                    int namedCat = condExcludeName == null ? 0
                            : ctx.isP1()
                                    ? ctx.countP1FieldCards(condInclFwd, condInclBkp, condInclMon, null, condExcludeName, condCategory)
                                    : ctx.countP2FieldCards(condInclFwd, condInclBkp, condInclMon, null, condExcludeName, condCategory);
                    // "a Category X Forward other than <name>" is met when a matching card exists
                    // besides the copies that are named <name> (usually just the source itself).
                    if (totalCat > namedCat) fDamage = altDamage;
                }

                List<ForwardTarget> ts = selectTargets(ctx, maxCount, any ? any : upTo,
                        opponentOnly, selfOnly, null, null, null, false,
                        -1, null, -1, null,
                        true, false, false,
                        null, null, null, null, false, null, false);
                if (ts.isEmpty()) return;

                if (equally) {
                    int n      = ts.size();
                    int base   = fDamage / n;
                    int extra  = fDamage % n;
                    Map<ForwardTarget, Integer> amountByTarget = new HashMap<>();
                    for (int i = 0; i < n; i++) amountByTarget.put(ts.get(i), base + (i < extra ? 1 : 0));
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, amountByTarget.get(t)));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, amountByTarget.get(t)));
                } else if (ts.size() == 1) {
                    // Nothing to divide — skip the allocation dialog and deal it all.
                    ctx.damageTarget(ts.get(0), fDamage);
                } else {
                    List<CardData> cards = new ArrayList<>();
                    for (ForwardTarget t : ts) {
                        cards.add(t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx()));
                    }
                    List<Integer> allocation = ctx.divideDamageAmount(fDamage, "Divide Damage: ", cards);
                    Map<ForwardTarget, Integer> amountByTarget = new HashMap<>();
                    for (int i = 0; i < ts.size(); i++) amountByTarget.put(ts.get(i), allocation.get(i));
                    sortedByIdxDesc(ts, true) .forEach(t -> { int amt = amountByTarget.get(t); if (amt > 0) ctx.damageTarget(t, amt); });
                    sortedByIdxDesc(ts, false).forEach(t -> { int amt = amountByTarget.get(t); if (amt > 0) ctx.damageTarget(t, amt); });
                }
            };
        }

        // --- "Deal it N damage. If <cond>, deal it M damage instead." ---
        // Matched against the full followup before the primary/secondary split to avoid losing the condition.
        Matcher insteadM = FOLLOWUP_DAMAGE_INSTEAD.matcher(followup);
        if (insteadM.find()) {
            int    baseDmg   = Integer.parseInt(insteadM.group("base"));
            int    altDmg    = Integer.parseInt(insteadM.group("alt"));
            String condText  = insteadM.group("cond").trim();
            DamageInsteadCondition insteadCond = parseDamageInsteadCondition(condText);
            if (insteadCond != null) {
                return ctx -> {
                    ctx.logEntry(choosePrefix + " — Deal " + baseDmg + "/" + altDmg + " damage (if " + condText + ")");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, resolveInsteadDamage(ctx, t, insteadCond, baseDmg, altDmg)));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, resolveInsteadDamage(ctx, t, insteadCond, baseDmg, altDmg)));
                };
            }
        }

        // --- General EX Burst instead ("P. If [name] results from an EX Burst, A instead.") ---
        // Checked before the for-each and fixed-damage handlers so the condition isn't lost.
        // FOLLOWUP_DAMAGE_INSTEAD already covers fixed-damage EX burst cases above; this handles
        // the for-each damage and non-damage EX burst instead variants.
        Matcher exBurstM = FOLLOWUP_INSTEAD_EXBURST.matcher(followup);
        if (exBurstM.find()) {
            String primaryText = exBurstM.group("primary").trim();
            String altText     = exBurstM.group("alt").trim();
            java.util.function.BiConsumer<GameContext, List<ForwardTarget>> primaryAction =
                    parseTargetAction(primaryText, xValue);
            java.util.function.BiConsumer<GameContext, List<ForwardTarget>> altAction =
                    parseTargetAction(altText, xValue);
            if (primaryAction != null && altAction != null) {
                return ctx -> {
                    ctx.logEntry(choosePrefix + " — EX Burst: " + primaryText + " / " + altText);
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    (ctx.isExBurst() ? altAction : primaryAction).accept(ctx, ts);
                };
            }
        }

        // --- "If opponent has N cards or less…, [action1]. If no cards…, [action2] instead." ---
        // Two-tier hand condition — checked against the full followup before the dot-split.
        Matcher dblHandM = OPPONENT_HAND_DOUBLE_CONDITION_PATTERN.matcher(followup);
        if (dblHandM.matches()) {
            int    threshold  = Integer.parseInt(dblHandM.group("n"));
            String eff1Text   = dblHandM.group("effect1").trim();
            String eff2Text   = dblHandM.group("effect2").trim();
            java.util.function.BiConsumer<GameContext, List<ForwardTarget>> action1 = parseTargetAction(eff1Text, xValue);
            java.util.function.BiConsumer<GameContext, List<ForwardTarget>> action2 = parseTargetAction(eff2Text, xValue);
            if (action1 != null && action2 != null) {
                return ctx -> {
                    ctx.logEntry(choosePrefix + " — hand condition (≤" + threshold + "/0)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                            jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    int hs = ctx.opponentHandSize();
                    if (hs == 0)           action2.accept(ctx, ts);
                    else if (hs <= threshold) action1.accept(ctx, ts);
                };
            }
        }

        // --- "If opponent has [no|N cards or less] cards in hand, [action]" as single followup ---
        Matcher handM = OPPONENT_HAND_CONDITION_PATTERN.matcher(primaryFollowup);
        if (handM.matches()) {
            String nStr      = handM.group("n");
            int    threshold = nStr != null ? Integer.parseInt(nStr) : 0;
            String effText   = handM.group("effect").trim();
            java.util.function.BiConsumer<GameContext, List<ForwardTarget>> action = parseTargetAction(effText, xValue);
            if (action != null) {
                return ctx -> {
                    ctx.logEntry(choosePrefix + " — hand condition");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                            jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    int hs = ctx.opponentHandSize();
                    boolean condMet = (nStr != null) ? hs <= threshold : hs == 0;
                    if (condMet) action.accept(ctx, ts);
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- "Select 1 number and reveal the top card of your deck.
        //      If the revealed card is of the same cost as the selected number, break it." ---
        // "it" = the chosen Forward selected in the choose step, not the revealed card.
        // Checked against the full followup (not primaryFollowup) so the compound text isn't split.
        if (FOLLOWUP_SELECT_NUMBER_REVEAL_BREAK.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Select number + reveal, break if cost matches");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ts.isEmpty()) return;
                ForwardTarget target = ts.get(0);
                int n = ctx.selectNumber(0, 11, "Select a number:");
                ctx.logEntry("Selected number: " + n);
                ctx.revealTopDeckCard(java.util.List.of(
                        new RevealClause(card -> card.cost() == n, null,
                                rCtx -> rCtx.breakTarget(target))), false);
            };
        }

        // --- "Remove the top card of your deck from the game. Deal it N damage for each CP required to play the removed card." ---
        Matcher rfpTopDeckPerCpM = FOLLOWUP_RFP_TOP_DECK_AND_DAMAGE_PER_CP.matcher(followup);
        if (rfpTopDeckPerCpM.find()) {
            int baseDmg = Integer.parseInt(rfpTopDeckPerCpM.group("base"));
            return ctx -> {
                int cpCost = ctx.removeTopCardOfDeckFromGameAndGetCost();
                int damage = baseDmg * cpCost;
                ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (RFP top of deck, " + baseDmg + "×CP=" + cpCost + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
            };
        }

        // --- "Reveal the top N cards of your deck. Deal it M damage for each CP required to play the revealed cards. Add all the revealed cards to your hand." ---
        Matcher revealDmgPerCpM = FOLLOWUP_REVEAL_TOP_N_DAMAGE_PER_CP_ADD_ALL_TO_HAND.matcher(followup);
        if (revealDmgPerCpM.find()) {
            int revealCount = Integer.parseInt(revealDmgPerCpM.group("n"));
            int baseDmg     = Integer.parseInt(revealDmgPerCpM.group("base"));
            return ctx -> {
                int totalCp = ctx.revealTopNAndAddAllToHandGetTotalCP(revealCount);
                int damage  = baseDmg * totalCp;
                ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (reveal top " + revealCount + ", " + baseDmg + "×totalCP=" + totalCp + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
            };
        }

        // --- "Reveal the top N cards of your deck. For each Job [Job] revealed this way, deal it M damage. Then, place the revealed cards at the bottom of your deck in any order." ---
        Matcher revealJobDmgM = FOLLOWUP_REVEAL_TOP_N_JOB_DEAL_DMG_PLACE_BOTTOM.matcher(followup);
        if (revealJobDmgM.find()) {
            int    revealCount  = Integer.parseInt(revealJobDmgM.group("n"));
            String revealJob    = revealJobDmgM.group("job").trim();
            int    dmgPerMatch  = Integer.parseInt(revealJobDmgM.group("dmg"));
            return ctx -> {
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                int matchCount = ctx.revealTopNCountJobPlaceAllAtBottom(revealCount, revealJob);
                if (ts.isEmpty() || matchCount == 0) {
                    ctx.logEntry(choosePrefix + " — 0 Job " + revealJob + " revealed, no damage");
                    return;
                }
                int totalDmg = matchCount * dmgPerMatch;
                ctx.logEntry(choosePrefix + " — Deal " + totalDmg + " damage (" + matchCount + "×" + dmgPerMatch + " for Job " + revealJob + ")");
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, totalDmg));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, totalDmg));
            };
        }

        // --- "Deal it N damage for each [Name] Counter placed on [card]." (counter-scaled xValue) ---
        // Must be checked before FOLLOWUP_DAMAGE_FOR_EACH, which would match on the flat N and drop the for-each.
        Matcher dmgForEachCounterM = FOLLOWUP_DAMAGE_FOR_EACH_COUNTER.matcher(primaryFollowup);
        if (dmgForEachCounterM.find()) {
            int perUnit = Integer.parseInt(dmgForEachCounterM.group("perunit"));
            String counterName = dmgForEachCounterM.group("counterName").trim();
            return ctx -> {
                int damage = perUnit * xValue;
                ctx.logEntry(choosePrefix + " — " + perUnit + " damage ×" + xValue + " " + counterName + " Counter(s) = " + damage + " damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Deal it N damage for each [source]" followup ---
        Matcher forEachM = FOLLOWUP_DAMAGE_FOR_EACH.matcher(primaryFollowup);
        if (forEachM.find()) {
            int    baseDmg        = Integer.parseInt(forEachM.group("base"));
            String perStr         = forEachM.group("per");
            int    perDmg         = perStr != null ? Integer.parseInt(perStr) : 0;
            boolean subtract      = "minus".equalsIgnoreCase(forEachM.group("op"));
            boolean srcSelfDmg    = forEachM.group("selfdmg")  != null;
            String  srcJobBracket = forEachM.group("jobbname") != null ? forEachM.group("jobbname").trim() : null;
            String  srcJobWritten = forEachM.group("jobwname") != null ? forEachM.group("jobwname").trim() : null;
            String  srcJobWType   = forEachM.group("jobwtype") != null ? forEachM.group("jobwtype").trim() : null;
            String  srcCharType   = forEachM.group("chartype");
            String  srcCategory   = srcCharType != null && forEachM.group("category") != null ? forEachM.group("category").trim() : null;
            String  srcElement    = srcCharType != null && forEachM.group("element")  != null ? forEachM.group("element").toLowerCase(java.util.Locale.ROOT) : null;
            int     srcCostFilter = srcCharType != null && forEachM.group("costfilter") != null ? Integer.parseInt(forEachM.group("costfilter")) : -1;
            String  srcBzName     = forEachM.group("bzname")   != null ? forEachM.group("bzname").trim()   : null;
            boolean srcOppHand    = forEachM.group("opphand")   != null;
            boolean srcCrystal    = forEachM.group("crystal")   != null;
            boolean srcCpDiffElem = forEachM.group("cpDiffElem") != null;
            // if none of the above → xpaid
            boolean charFwd = srcCharType != null && (srcCharType.equalsIgnoreCase("forward")   || srcCharType.equalsIgnoreCase("forwards")   || srcCharType.equalsIgnoreCase("character") || srcCharType.equalsIgnoreCase("characters"));
            boolean charBkp = srcCharType != null && (srcCharType.equalsIgnoreCase("backup")    || srcCharType.equalsIgnoreCase("backups")    || srcCharType.equalsIgnoreCase("character") || srcCharType.equalsIgnoreCase("characters"));
            boolean charMon = srcCharType != null && (srcCharType.equalsIgnoreCase("monster")   || srcCharType.equalsIgnoreCase("monsters")   || srcCharType.equalsIgnoreCase("character") || srcCharType.equalsIgnoreCase("characters"));
            String sourceLabel;
            if      (srcSelfDmg)           sourceLabel = "P1 damage";
            else if (srcJobBracket != null) sourceLabel = "[Job (" + srcJobBracket + ")] you control";
            else if (srcJobWritten != null) sourceLabel = "Job " + srcJobWritten + (srcJobWType != null ? " " + srcJobWType : "") + " you control";
            else if (srcCharType   != null) sourceLabel = (srcCategory != null ? "Category " + srcCategory + " " : "") + (srcElement != null ? srcElement + " " : "") + srcCharType + (srcCostFilter != -1 ? " of cost " + srcCostFilter : "") + " you control";
            else if (srcBzName     != null) sourceLabel = "Card Name " + srcBzName + " in BZ";
            else if (srcOppHand)           sourceLabel = "opponent hand";
            else if (srcCrystal)           sourceLabel = "《C》 you have";
            else if (srcCpDiffElem)        sourceLabel = "CP of a different Element paid to cast";
            else                            sourceLabel = "X CP paid";
            String op = subtract ? " - " : " + ";
            String logLabel = perDmg > 0
                    ? baseDmg + op + perDmg + "×[" + sourceLabel + "]"
                    : baseDmg + "×[" + sourceLabel + "]";
            return ctx -> {
                int n;
                if      (srcSelfDmg)           n = ctx.p1DamageCount();
                else if (srcJobBracket != null) n = ctx.countSelfFieldCards(true, true, true, srcJobBracket, null);
                else if (srcJobWritten != null) {
                    boolean jwFwd = srcJobWType == null || srcJobWType.matches("(?i)Forwards?");
                    boolean jwBkp = srcJobWType == null || srcJobWType.matches("(?i)Backups?");
                    boolean jwMon = srcJobWType == null || srcJobWType.matches("(?i)Monsters?");
                    n = ctx.countSelfFieldCards(jwFwd, jwBkp, jwMon, srcJobWritten, null);
                }
                else if (srcCharType   != null) n = ctx.countSelfFieldCards(charFwd, charBkp, charMon, null, null, srcCategory, srcElement, srcCostFilter);
                else if (srcBzName     != null) n = ctx.countSelfBreakZoneCards(srcBzName, null);
                else if (srcOppHand)           n = ctx.opponentHandSize();
                else if (srcCrystal)           n = ctx.crystalCount();
                else if (srcCpDiffElem)        n = ctx.castPaymentDistinctElements();
                else                            n = xValue;
                int damage = perDmg > 0
                        ? (subtract ? Math.max(0, baseDmg - perDmg * n) : baseDmg + perDmg * n)
                        : baseDmg * n;
                ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (" + logLabel + ", n=" + n + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Dull + Damage followup ---
        Matcher dullDmgM = FOLLOWUP_DULL_AND_DAMAGE.matcher(primaryFollowup);
        if (dullDmgM.find()) {
            int damage = Integer.parseInt(dullDmgM.group("amount"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Dull & Deal " + damage + " damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> { ctx.dullTarget(t); ctx.damageTarget(t, damage); });
                sortedByIdxDesc(ts, false).forEach(t -> { ctx.dullTarget(t); ctx.damageTarget(t, damage); });
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "If your opponent controls N or more Forwards, deal it X damage" followup ---
        Matcher oppFwdCondM = FOLLOWUP_IF_OPPONENT_CONTROLS_FORWARDS_DAMAGE.matcher(primaryFollowup);
        if (oppFwdCondM.matches()) {
            int minCount = Integer.parseInt(oppFwdCondM.group("count"));
            int damage   = Integer.parseInt(oppFwdCondM.group("amount"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — If opponent controls ≥" + minCount + " Forwards, deal " + damage + " damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ctx.opponentForwardCount() >= minCount) {
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "If you control N or more [Element] [Type], deal it X damage" followup ---
        Matcher selfFieldCondM = FOLLOWUP_IF_SELF_CONTROLS_N_ELEMENT_TYPE_DAMAGE.matcher(primaryFollowup);
        if (selfFieldCondM.matches()) {
            int    minCount    = Integer.parseInt(selfFieldCondM.group("count"));
            int    damage      = Integer.parseInt(selfFieldCondM.group("amount"));
            String condElement  = selfFieldCondM.group("element");  // null if absent
            String condTypeRaw  = selfFieldCondM.group("type");
            String condType     = condTypeRaw.toLowerCase();
            boolean cFwd = condType.startsWith("forward") || condType.startsWith("character");
            boolean cBkp = condType.startsWith("backup")  || condType.startsWith("character");
            boolean cMon = condType.startsWith("monster")  || condType.startsWith("character");
            return ctx -> {
                String label = "If you control ≥" + minCount + " "
                        + (condElement != null ? condElement + " " : "")
                        + condTypeRaw + ", deal " + damage + " damage";
                ctx.logEntry(choosePrefix + " — " + label);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ctx.selfFieldCount(condElement, cFwd, cBkp, cMon) >= minCount) {
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Split effect: [action A] the first [type] … and [action B] the other ---
        Matcher foM = FOLLOWUP_FIRST_AND_OTHER.matcher(primaryFollowup);
        if (foM.find()) {
            final String firstpfx    = foM.group("firstpfx").trim();
            final String firstsfx    = foM.group("firstsfx").trim().toLowerCase();
            final String othereffect = foM.group("othereffect").trim().toLowerCase();
            Matcher dmgAmt = Pattern.compile("(?i)deal\\s+(?<n>\\d+)\\s+damage").matcher(firstpfx);
            final int firstDamage = dmgAmt.find() ? Integer.parseInt(dmgAmt.group("n")) : 0;
            return ctx -> {
                ctx.logEntry(choosePrefix + " — " + firstpfx + " first; " + othereffect + " other");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (!ts.isEmpty()) {
                    ForwardTarget first = ts.get(0);
                    if      (firstsfx.contains("from the game"))  ctx.removeTargetFromGame(first);
                    else if (firstsfx.contains("to its owner")) {
                        if (first.zone() == ForwardTarget.CardZone.FORWARD) {
                            if (first.isP1()) ctx.returnP1ForwardToHand(first.idx());
                            else              ctx.returnP2ForwardToHand(first.idx());
                        }
                    }
                    else if (firstDamage > 0)                          ctx.damageTarget(first, firstDamage);
                    else if (firstpfx.equalsIgnoreCase("dull"))        ctx.dullTarget(first);
                    else if (firstpfx.equalsIgnoreCase("break"))       ctx.breakTarget(first);
                    else if (firstpfx.equalsIgnoreCase("freeze"))      ctx.freezeTarget(first);
                    else if (firstpfx.equalsIgnoreCase("activate"))    ctx.activateTarget(first);
                }
                if (ts.size() > 1) {
                    ForwardTarget other = ts.get(1);
                    if      (othereffect.contains("freeze") && othereffect.contains("dull")) ctx.dullAndFreezeTarget(other);
                    else if (othereffect.equals("activate"))                                  ctx.activateTarget(other);
                    else if (othereffect.equals("break"))                                     ctx.breakTarget(other);
                    else if (othereffect.equals("dull"))                                      ctx.dullTarget(other);
                    else if (othereffect.equals("freeze"))                                    ctx.freezeTarget(other);
                    else if (othereffect.contains("from the game"))                           ctx.removeTargetFromGame(other);
                    else if (othereffect.contains("to its owner")) {
                        if (other.zone() == ForwardTarget.CardZone.FORWARD) {
                            if (other.isP1()) ctx.returnP1ForwardToHand(other.idx());
                            else              ctx.returnP2ForwardToHand(other.idx());
                        }
                    }
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Damage + controller damage followup ("Deal it N damage and M point(s) of damage to that Forward's controller") ---
        Matcher ctrlDmgM = FOLLOWUP_DAMAGE_AND_CONTROLLER_DAMAGE.matcher(strippedPrimaryFollowup);
        if (ctrlDmgM.find()) {
            int damage        = Integer.parseInt(ctrlDmgM.group("amount"));
            int controllerDmg = Integer.parseInt(ctrlDmgM.group("controllerdmg"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Deal " + damage + " damage + " + controllerDmg + " to controller");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                for (ForwardTarget t : ts) {
                    if (t.isP1()) ctx.dealDamageToSelf(controllerDmg);
                    else          ctx.dealDamageToOpponent(controllerDmg);
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Damage followup (fixed amount) ---
        Matcher dmgM = FOLLOWUP_DAMAGE.matcher(strippedPrimaryFollowup);
        if (dmgM.find()) {
            int damage = Integer.parseInt(dmgM.group("amount"));
            String alsoCard = dmgM.group("also") != null ? dmgM.group("also").trim() : null;
            return ctx -> {
                String unredSuffix = unreduced ? " (cannot be reduced)" : "";
                ctx.logEntry(alsoCard != null
                        ? choosePrefix + " — Deal " + damage + " damage (and to " + alsoCard + ")" + unredSuffix
                        : choosePrefix + " — Deal " + damage + " damage" + unredSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                Consumer<GameContext> doDamage = ctx2 -> {
                    if (unreduced) {
                        sortedByIdxDesc(ts, true) .forEach(t -> ctx2.damageTargetUnreduced(t, damage));
                        sortedByIdxDesc(ts, false).forEach(t -> ctx2.damageTargetUnreduced(t, damage));
                    } else {
                        sortedByIdxDesc(ts, true) .forEach(t -> ctx2.damageTarget(t, damage));
                        sortedByIdxDesc(ts, false).forEach(t -> ctx2.damageTarget(t, damage));
                    }
                    if (alsoCard != null) ctx2.damageFieldForwardByName(alsoCard, damage);
                    if (secondary != null) secondary.accept(ctx2);
                };
                if (followupIsOptional && !ts.isEmpty()) ctx.playerMayDoEffect("Deal it " + damage + " damage?", doDamage);
                else if (!followupIsOptional) doDamage.accept(ctx);
            };
        }

        // --- Mutual power-as-damage between source and chosen Forward ---
        if (source != null) {
            Matcher mutM = FOLLOWUP_MUTUAL_POWER_DAMAGE.matcher(primaryFollowup);
            if (mutM.find() && mutM.group("srcname").trim().equalsIgnoreCase(source.name())) {
                String srcName = source.name();
                return ctx -> {
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    if (ts.isEmpty()) { if (secondary != null) secondary.accept(ctx); return; }
                    int srcPower = Math.max(0, ctx.fieldForwardPowerByName(srcName));
                    for (ForwardTarget t : ts) {
                        int tgtPower = Math.max(0, ctx.effectiveTargetPower(t));
                        ctx.logEntry(choosePrefix + " — Mutual power damage: " + srcName + " (" + srcPower
                                + ") ↔ chosen Forward (" + tgtPower + ")");
                        ctx.damageTarget(t, srcPower);
                        ctx.damageFieldForwardByName(srcName, tgtPower);
                    }
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- Damage followup (computed amount) ---
        Matcher exprM = FOLLOWUP_DAMAGE_EXPR.matcher(primaryFollowup);
        if (exprM.find()) {
            if (exprM.group("highest") != null) {
                return ctx -> {
                    int damage = ctx.highestP1ForwardPower();
                    ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (highest Forward power)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("halfcard") != null) {
                String  cardName = exprM.group("halfcard").trim();
                boolean roundUp  = "up".equalsIgnoreCase(exprM.group("halfrounding"));
                return ctx -> {
                    int raw    = Math.max(0, ctx.fieldForwardPowerByName(cardName));
                    int damage = roundUp ? halfPowerDamage(raw) : (raw / 2 / 1000) * 1000;
                    String dir = roundUp ? "up" : "down";
                    ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (half of " + cardName + "'s power, round " + dir + ")");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("halfitspower") != null) {
                boolean roundUp = "up".equalsIgnoreCase(exprM.group("halfitsrounding"));
                String dir = roundUp ? "up" : "down";
                return ctx -> {
                    ctx.logEntry(choosePrefix + " — Deal damage equal to half of its power (round " + dir + ")");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> {
                        int raw = Math.max(0, ctx.effectiveTargetPower(t));
                        ctx.damageTarget(t, roundUp ? halfPowerDamage(raw) : (raw / 2 / 1000) * 1000);
                    });
                    sortedByIdxDesc(ts, false).forEach(t -> {
                        int raw = Math.max(0, ctx.effectiveTargetPower(t));
                        ctx.damageTarget(t, roundUp ? halfPowerDamage(raw) : (raw / 2 / 1000) * 1000);
                    });
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("itspower") != null) {
                int subtract = exprM.group("minus") != null ? Integer.parseInt(exprM.group("minus")) : 0;
                String logSuffix = subtract > 0 ? " — Deal damage equal to its power minus " + subtract
                                                 : " — Deal damage equal to its power";
                return ctx -> {
                    ctx.logEntry(choosePrefix + logSuffix);
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, Math.max(0, ctx.effectiveTargetPower(t) - subtract)));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, Math.max(0, ctx.effectiveTargetPower(t) - subtract)));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("dullforward") != null) {
                return ctx -> {
                    int damage = Math.max(0, ctx.dullForwardCostPower());
                    ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (dull Forward cost power)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("discardedfwd") != null) {
                return ctx -> {
                    int damage = Math.max(0, ctx.lastDiscardedForwardPower());
                    ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (discarded Forward's power)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("bzcostfwd") != null) {
                return ctx -> {
                    int damage = Math.max(0, ctx.bzCostForwardPower());
                    ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (BZ-cost Forward's power)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("card") != null) {
                String cardName = exprM.group("card").trim();
                return ctx -> {
                    int damage = Math.max(0, ctx.fieldForwardPowerByName(cardName));
                    ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (" + cardName + "'s power)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- Activate + Gain control (EOT) followup (must precede plain Activate) ---
        if (FOLLOWUP_ACTIVATE_AND_GAIN_CONTROL_EOT.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Activate & Gain control until EOT");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.activateTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.activateTarget(t));
                ts.forEach(t -> ctx.gainControlOfForward(t, "endOfTurn", true));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Gain control while named card on field ---
        Matcher gcWhileM = FOLLOWUP_GAIN_CONTROL_WHILE_CARD.matcher(primaryFollowup);
        if (gcWhileM.find()) {
            String condCard = gcWhileM.group("condCard").trim();
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Gain control while " + condCard + " is on field");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.gainControlOfForward(t, "whileCardOnField:" + condCard, false));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Gain control until EOT ---
        if (FOLLOWUP_GAIN_CONTROL_EOT.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Gain control until EOT");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.gainControlOfForward(t, "endOfTurn", false));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Gain control (permanent) ---
        if (FOLLOWUP_GAIN_CONTROL.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Gain control");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.gainControlOfForward(t, "permanent", false));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot-be-chosen followups (gains form, then both, Summons, abilities) ---
        {   // scoped block so scope-parsing locals don't leak
            String fp = primaryFollowup;
            Matcher gcM = FOLLOWUP_GAINS_CANNOT_BE_CHOSEN.matcher(fp);
            if (!gcM.find()) gcM = null;
            boolean chosenBoth      = gcM != null || FOLLOWUP_CANNOT_BE_CHOSEN_BOTH.matcher(fp).find();
            boolean chosenSummons   = chosenBoth  || (gcM == null && FOLLOWUP_CANNOT_BE_CHOSEN_SUMMONS.matcher(fp).find());
            boolean chosenAbilities = chosenBoth  || (gcM == null && FOLLOWUP_CANNOT_BE_CHOSEN_ABILITIES.matcher(fp).find());
            if (chosenSummons || chosenAbilities) {
                final boolean bs = chosenSummons, ba = chosenAbilities;
                return ctx -> {
                    ctx.logEntry(choosePrefix + " — Cannot be chosen by opponent's"
                            + (bs && ba ? " Summons or abilities" : bs ? " Summons" : " abilities"));
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    ts.forEach(t -> ctx.shieldCannotBeChosen(t, bs, ba));
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- Activate + Negate damage followup (must precede plain Activate to avoid partial match) ---
        if (FOLLOWUP_ACTIVATE_AND_NEGATE_DAMAGE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Activate & Negate damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.activateTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.activateTarget(t));
                ts.forEach(ctx::negateAllDamage);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Negate all damage followup ---
        if (FOLLOWUP_NEGATE_DAMAGE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Negate damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::negateAllDamage);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Dull-or-Activate toggle followup (must precede FOLLOWUP_ACTIVATE/DULL since it contains both) ---
        if (FOLLOWUP_DULL_OR_ACTIVATE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Dull or Activate (toggle)");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.toggleTargetDullActivate(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.toggleTargetDullActivate(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Activate followup ---
        if (FOLLOWUP_ACTIVATE.matcher(primaryFollowup).find()) {
            // Detect "It gains +N power [traits] until end of turn" secondary and apply inline.
            final int activateBoost;
            final EnumSet<CardData.Trait> activateTraits;
            final Consumer<GameContext> activateSecondary;
            {
                Matcher bm = secondaryText != null ? FOLLOWUP_POWER_BOOST.matcher(secondaryText) : null;
                if (bm == null) { bm = secondaryText != null ? FOLLOWUP_POWER_BOOST_UNTIL.matcher(secondaryText) : null; }
                if (bm != null && bm.find()) {
                    activateBoost      = Integer.parseInt(bm.group(1));
                    activateTraits     = parseTraits(bm.group(2));
                    activateSecondary  = null;
                } else {
                    activateBoost      = 0;
                    activateTraits     = EnumSet.noneOf(CardData.Trait.class);
                    activateSecondary  = secondary;
                }
            }
            String activateLogSuffix = activateBoost > 0 ? boostLogSuffix(activateBoost, activateTraits) : "";
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Activate" + activateLogSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.activateTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.activateTarget(t));
                if (activateBoost > 0) {
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, activateBoost, activateTraits));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, activateBoost, activateTraits));
                } else if (activateSecondary != null) {
                    activateSecondary.accept(ctx);
                }
            };
        }

        // --- Dull-or-Freeze followup (must precede FOLLOWUP_DULL since it contains "Dull it") ---
        if (FOLLOWUP_DULL_OR_FREEZE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Dull or Freeze");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.dullOrFreezeTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.dullOrFreezeTarget(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Dull followup ---
        if (FOLLOWUP_DULL.matcher(primaryFollowup).find()
                && !FOLLOWUP_DULL_AND_FREEZE.matcher(primaryFollowup).find()
                && !FOLLOWUP_DULL_OR_FREEZE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Dull");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.dullTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.dullTarget(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Dull + Freeze followup ---
        if (FOLLOWUP_DULL_AND_FREEZE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Dull & Freeze");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.dullAndFreezeTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.dullAndFreezeTarget(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Freeze followup ---
        if (FOLLOWUP_FREEZE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Freeze");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.freezeTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.freezeTarget(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Break followup ---
        if (FOLLOWUP_BREAK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Break");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.breakTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.breakTarget(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Lose all abilities until end of turn followup ---
        if (FOLLOWUP_LOSE_ALL_ABILITIES_EOT.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Lose all abilities until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(ctx::targetLoseAllAbilitiesUntilEndOfTurn);
                sortedByIdxDesc(ts, false).forEach(ctx::targetLoseAllAbilitiesUntilEndOfTurn);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Remove them from the game. If these cards are of the same card type, also draw N card(s)." ---
        Matcher rfpSameTypeDrawM = FOLLOWUP_RFP_IF_SAME_TYPE_DRAW.matcher(followup);
        if (rfpSameTypeDrawM.find()) {
            int drawCount = Integer.parseInt(rfpSameTypeDrawM.group("count"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Remove From Game (if same type, draw " + drawCount + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                java.util.Set<String> typesSeen = new java.util.HashSet<>();
                for (ForwardTarget t : ts) {
                    CardData card = t.isP1() ? ctx.p1BreakZoneCard(t.idx()) : ctx.p2BreakZoneCard(t.idx());
                    if (card != null) typesSeen.add(card.type().toLowerCase(java.util.Locale.ROOT));
                }
                sortedByIdxDesc(ts, true) .forEach(ctx::removeTargetFromGame);
                sortedByIdxDesc(ts, false).forEach(ctx::removeTargetFromGame);
                if (!ts.isEmpty() && typesSeen.size() == 1) ctx.drawCards(drawCount);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Remove from game + named card followup (e.g. "Remove it and Shuyin from the game") ---
        Matcher rfgNamedM = FOLLOWUP_REMOVE_FROM_GAME_AND_NAMED.matcher(primaryFollowup);
        if (rfgNamedM.find()) {
            String alsoNamed = rfgNamedM.group("named").trim();
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Remove From Game (+ " + alsoNamed + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.removeTargetFromGame(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.removeTargetFromGame(t));
                ctx.removeNamedCardFromGame(alsoNamed);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Remove from game followup ---
        if (FOLLOWUP_REMOVE_FROM_GAME.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Remove From Game");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.removeTargetFromGame(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.removeTargetFromGame(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Play onto field followup ---
        // --- "If its cost is equal to or less than the number of Job X you control, play it onto the field." ---
        // Must be checked before the generic PlayOntoField handler so the condition is enforced.
        Matcher costLeJobM = FOLLOWUP_PLAY_IF_COST_LE_JOB_COUNT.matcher(primaryFollowup);
        if (costLeJobM.matches()) {
            String condJob = costLeJobM.group("job").trim();
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Play onto Field if cost ≤ count of Job " + condJob + " you control");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                int jobCount = ctx.countSelfFieldCards(true, true, true, condJob, null);
                for (ForwardTarget t : sortedByIdxDesc(ts, true) .collect(java.util.stream.Collectors.toList())) {
                    CardData card = t.isP1() ? ctx.p1BreakZoneCard(t.idx()) : ctx.p2BreakZoneCard(t.idx());
                    if (card != null && card.cost() <= jobCount) ctx.playTargetOntoField(t);
                }
                for (ForwardTarget t : sortedByIdxDesc(ts, false).collect(java.util.stream.Collectors.toList())) {
                    CardData card = t.isP1() ? ctx.p1BreakZoneCard(t.idx()) : ctx.p2BreakZoneCard(t.idx());
                    if (card != null && card.cost() <= jobCount) ctx.playTargetOntoField(t);
                }
            };
        }

        if (FOLLOWUP_PLAY_ONTO_FIELD.matcher(primaryFollowup).find()) {
            // Check for "When it enters the field, if it is [cond], [inner]" conditional secondary.
            // Peek at the chosen card's data before playing so we can evaluate the condition after.
            final Predicate<CardData> etfCond;
            final Consumer<GameContext> etfInner;
            final String etfInnerText;
            if (secondaryText != null) {
                Matcher etfM = FOLLOWUP_PLAY_ONTO_FIELD_WHEN_ENTERS_CONDITIONAL.matcher(secondaryText);
                if (etfM.matches()) {
                    Predicate<CardData> parsedCond = parseRevealCondition(etfM.group("cond").trim());
                    String innerTxt = etfM.group("inner").trim();
                    Consumer<GameContext> inner = parsedCond != null ? parse(innerTxt, source) : null;
                    etfCond      = (parsedCond != null && inner != null) ? parsedCond : null;
                    etfInner     = (parsedCond != null && inner != null) ? inner      : null;
                    etfInnerText = (parsedCond != null && inner != null) ? innerTxt   : null;
                } else {
                    etfCond = null; etfInner = null; etfInnerText = null;
                }
            } else {
                etfCond = null; etfInner = null; etfInnerText = null;
            }
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Play onto Field");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                List<CardData> chosenCards = new ArrayList<>();
                if (etfCond != null) {
                    for (ForwardTarget t : ts) {
                        CardData c = zone != null
                                ? (t.isP1() ? ctx.p1BreakZoneCard(t.idx()) : ctx.p2BreakZoneCard(t.idx()))
                                : null;
                        if (c != null) chosenCards.add(c);
                    }
                }
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.playTargetOntoField(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.playTargetOntoField(t));
                if (etfCond != null && etfInner != null) {
                    boolean anyMatched = chosenCards.stream().anyMatch(etfCond);
                    if (anyMatched) {
                        ctx.logEntry("ETF Condition met — " + etfInnerText);
                        etfInner.accept(ctx);
                    }
                } else if (secondary != null) {
                    secondary.accept(ctx);
                }
            };
        }

        // --- Add to hand followup ---
        if (FOLLOWUP_ADD_TO_HAND.matcher(primaryFollowup).find()) {
            // Detect a conditional secondary that depends on the added card, e.g.
            // "If it is a Card Name Tifa, …" or "If the added card is not a Category II card, …".
            // When matched, the inner effect runs only if the chosen card satisfies the condition,
            // and the generic secondary parse is suppressed.
            final Predicate<CardData> addedCardCond;
            final Consumer<GameContext> conditionalInner;
            final String conditionalInnerText;
            if (secondaryText != null) {
                Matcher condM = FOLLOWUP_ADD_TO_HAND_CONDITIONAL_SECONDARY.matcher(secondaryText);
                if (condM.matches()) {
                    Predicate<CardData> cond = parseRevealCondition(condM.group("cond").trim());
                    String innerTxt = condM.group("inner").trim();
                    Consumer<GameContext> inner = cond != null ? parse(innerTxt, source) : null;
                    addedCardCond       = (cond != null && inner != null) ? cond  : null;
                    conditionalInner    = (cond != null && inner != null) ? inner : null;
                    conditionalInnerText = (cond != null && inner != null) ? innerTxt : null;
                } else {
                    addedCardCond        = null;
                    conditionalInner     = null;
                    conditionalInnerText = null;
                }
            } else {
                addedCardCond        = null;
                conditionalInner     = null;
                conditionalInnerText = null;
            }

            return ctx -> {
                ctx.logEntry(choosePrefix + " — Add to Hand");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                // Peek at chosen cards before they leave the Break Zone so the conditional
                // secondary can inspect them.
                List<CardData> chosenCards = new ArrayList<>();
                if (addedCardCond != null) {
                    for (ForwardTarget t : ts) {
                        CardData c = t.isP1() ? ctx.p1BreakZoneCard(t.idx()) : ctx.p2BreakZoneCard(t.idx());
                        if (c != null) chosenCards.add(c);
                    }
                }
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.addTargetToHand(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.addTargetToHand(t));

                if (addedCardCond != null && conditionalInner != null) {
                    boolean anyMatched = chosenCards.stream().anyMatch(addedCardCond);
                    if (anyMatched) {
                        ctx.logEntry("Condition met (added card) — " + conditionalInnerText);
                        conditionalInner.accept(ctx);
                    }
                } else if (secondary != null) {
                    secondary.accept(ctx);
                }
            };
        }

        // --- Return it and [NamedCard] to their owners' hands ---
        Matcher retNamedM = FOLLOWUP_RETURN_AND_NAMED_TO_OWNERS_HAND.matcher(primaryFollowup);
        if (retNamedM.find()) {
            String alsoNamed = retNamedM.group("named").trim();
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Return to owner's hand (+ " + alsoNamed + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.returnP1ForwardToHand(t.idx());
                    else          ctx.returnP2ForwardToHand(t.idx());
                }
                ctx.returnNamedCardToOwnersHand(alsoNamed);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "If its cost ≤ number of cards in your hand, return to owner's hand" (Leviathan EX Burst) ---
        if (FOLLOWUP_RETURN_IF_COST_LE_HAND.matcher(strippedPrimaryFollowup).matches()) {
            return ctx -> {
                int handSize = ctx.yourHandSize();
                ctx.logEntry(choosePrefix + " — Return to owner's hand if cost ≤ hand size (" + handSize + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    CardData card = t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx());
                    if (card == null || card.cost() > handSize) {
                        if (card != null) ctx.logEntry("Cost " + card.cost() + " > hand size " + handSize + " — condition not met");
                        continue;
                    }
                    ctx.logEntry("Cost " + card.cost() + " ≤ hand size " + handSize + " — returning to hand");
                    if (t.isP1()) ctx.returnP1ForwardToHand(t.idx());
                    else          ctx.returnP2ForwardToHand(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Return to owner's hand followup ---
        if (FOLLOWUP_RETURN_TO_OWNERS_HAND.matcher(strippedPrimaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Return to owner's hand");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                Consumer<GameContext> doReturn = ctx2 -> {
                    for (ForwardTarget t : ts) {
                        if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                        if (t.isP1()) ctx2.returnP1ForwardToHand(t.idx());
                        else          ctx2.returnP2ForwardToHand(t.idx());
                    }
                    if (secondary != null) secondary.accept(ctx2);
                };
                if (followupIsOptional && !ts.isEmpty()) ctx.playerMayDoEffect("Return it to its owner's hand?", doReturn);
                else if (!followupIsOptional) doReturn.accept(ctx);
            };
        }

        // --- Return to your hand followup ---
        if (FOLLOWUP_RETURN_TO_YOUR_HAND.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Return to your hand");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.returnP1ForwardToHand(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Put at top or bottom of owner's deck followup (player chooses) ---
        if (FOLLOWUP_PUT_TOP_OR_BOTTOM_OF_DECK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Put at top or bottom of owner's deck (player chooses)");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) {
                        String cardName = ctx.p1Forward(t.idx()).name();
                        boolean toTop = ctx.askTopOrBottom(cardName);
                        if (toTop) ctx.returnP1ForwardToDeckTop(t.idx());
                        else       ctx.returnP1ForwardToDeckBottom(t.idx());
                    } else {
                        String cardName = ctx.p2Forward(t.idx()).name();
                        boolean toTop = ctx.askTopOrBottom(cardName);
                        if (toTop) ctx.returnP2ForwardToDeckTop(t.idx());
                        else       ctx.returnP2ForwardToDeckBottom(t.idx());
                    }
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Put at bottom of owner's deck followup ---
        if (FOLLOWUP_PUT_BOTTOM_OF_DECK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Put at bottom of owner's deck");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.returnP1ForwardToDeckBottom(t.idx());
                    else          ctx.returnP2ForwardToDeckBottom(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Conditional power-vs-source "put on top of deck" followup (e.g. Wakka) ---
        Matcher ifPowerCmpSourceM = FOLLOWUP_IF_POWER_CMP_SOURCE_PUT_ON_DECK_TOP.matcher(primaryFollowup);
        if (ifPowerCmpSourceM.find()) {
            boolean wantLessOrEqual = "less".equalsIgnoreCase(ifPowerCmpSourceM.group("cmp"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Conditional power check vs source, put on top of owner's deck");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                // Find source card's current effective power on the field
                int sourcePower = source.power();
                outer:
                for (int pi = 0; pi <= 1; pi++) {
                    boolean p1 = pi == 0;
                    int cnt = p1 ? ctx.p1ForwardCount() : ctx.p2ForwardCount();
                    for (int i = 0; i < cnt; i++) {
                        if ((p1 ? ctx.p1Forward(i) : ctx.p2Forward(i)) == source) {
                            sourcePower = ctx.effectiveTargetPower(
                                    new ForwardTarget(p1, i, ForwardTarget.CardZone.FORWARD));
                            break outer;
                        }
                    }
                }
                final int sp = sourcePower;
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    int targetPower = ctx.effectiveTargetPower(t);
                    boolean condMet = wantLessOrEqual ? targetPower <= sp : targetPower >= sp;
                    if (condMet) {
                        ctx.logEntry("  power " + targetPower + (wantLessOrEqual ? " ≤ " : " ≥ ") + sp + " — bounced to deck top");
                        if (t.isP1()) ctx.returnP1ForwardToDeckTop(t.idx());
                        else          ctx.returnP2ForwardToDeckTop(t.idx());
                    } else {
                        ctx.logEntry("  power " + targetPower + (wantLessOrEqual ? " > " : " < ") + sp + " — condition not met, no effect");
                    }
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Put on top of owner's deck followup ---
        if (FOLLOWUP_PUT_TOP_OF_DECK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Put on top of owner's deck");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.returnP1ForwardToDeckTop(t.idx());
                    else          ctx.returnP2ForwardToDeckTop(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Put under top N cards of owner's deck followup ---
        Matcher underTopM = FOLLOWUP_PUT_UNDER_TOP_OF_DECK.matcher(primaryFollowup);
        if (underTopM.find()) {
            int underPos = underTopM.group("numword") != null ? 4 : 1;
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Put under top " + underPos + " card(s) of owner's deck");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.returnP1ForwardUnderDeckTop(t.idx(), underPos);
                    else          ctx.returnP2ForwardUnderDeckTop(t.idx(), underPos);
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot block followup ---
        if (FOLLOWUP_CANNOT_BLOCK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Cannot block this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.setP1ForwardCannotBlock(t.idx());
                    else          ctx.setP2ForwardCannotBlock(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot be blocked followup ---
        if (FOLLOWUP_CANNOT_BE_BLOCKED.matcher(primaryFollowup).find()) {
            Matcher bm = FOLLOWUP_CANNOT_BE_BLOCKED.matcher(primaryFollowup);
            bm.find();
            String bCostStr  = bm.group("costval");
            String bCostCmp  = bm.group("costcmp");
            final int   bCostVal = bCostStr != null ? Integer.parseInt(bCostStr) : -1;
            final boolean bIsMore = "more".equalsIgnoreCase(bCostCmp);
            String bCostLabel = bCostVal >= 0 ? " by cost " + bCostVal + " or " + bCostCmp : "";
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Cannot be blocked" + bCostLabel + " this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (bCostVal >= 0) {
                        if (t.isP1()) ctx.setP1ForwardCannotBeBlockedByCost(t.idx(), bCostVal, bIsMore);
                        else          ctx.setP2ForwardCannotBeBlockedByCost(t.idx(), bCostVal, bIsMore);
                    } else {
                        if (t.isP1()) ctx.setP1ForwardCannotBeBlocked(t.idx());
                        else          ctx.setP2ForwardCannotBeBlocked(t.idx());
                    }
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Only blocked by Forward of cost ≤ own cost followup ---
        if (FOLLOWUP_ONLY_BLOCKED_BY_COST_LE_OWN.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Can only be blocked by a Forward of cost ≤ its own this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    int ownCost = (t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx())).cost();
                    if (t.isP1()) ctx.setP1ForwardCannotBeBlockedByCost(t.idx(), ownCost + 1, true);
                    else          ctx.setP2ForwardCannotBeBlockedByCost(t.idx(), ownCost + 1, true);
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot be blocked if element CP was paid followup ---
        if (FOLLOWUP_CANNOT_BE_BLOCKED_IF_ELEMENT_CP.matcher(primaryFollowup).find()) {
            Matcher bm = FOLLOWUP_CANNOT_BE_BLOCKED_IF_ELEMENT_CP.matcher(primaryFollowup);
            bm.find();
            final String elem    = bm.group("element");
            String eCostStr      = bm.group("costval");
            String eCostCmp      = bm.group("costcmp");
            final int   bCostVal = eCostStr != null ? Integer.parseInt(eCostStr) : -1;
            final boolean bIsMore = "more".equalsIgnoreCase(eCostCmp);
            String bCostLabel    = bCostVal >= 0 ? " by cost " + bCostVal + " or " + eCostCmp : "";
            return ctx -> {
                if (!ctx.wasElementCpPaid(elem)) {
                    ctx.logEntry(choosePrefix + " — " + elem + " CP not paid, skipping cannot-be-blocked bonus");
                    return;
                }
                ctx.logEntry(choosePrefix + " — Cannot be blocked" + bCostLabel + " this turn (" + elem + " CP paid)");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (bCostVal >= 0) {
                        if (t.isP1()) ctx.setP1ForwardCannotBeBlockedByCost(t.idx(), bCostVal, bIsMore);
                        else          ctx.setP2ForwardCannotBeBlockedByCost(t.idx(), bCostVal, bIsMore);
                    } else {
                        if (t.isP1()) ctx.setP1ForwardCannotBeBlocked(t.idx());
                        else          ctx.setP2ForwardCannotBeBlocked(t.idx());
                    }
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Must block followup ---
        if (FOLLOWUP_MUST_BLOCK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Must block if possible this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.setP1ForwardMustBlock(t.idx());
                    else          ctx.setP2ForwardMustBlock(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot attack (this turn) followup ---
        if (FOLLOWUP_CANNOT_ATTACK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Cannot attack this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.setP1ForwardCannotAttack(t.idx());
                    else          ctx.setP2ForwardCannotAttack(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Must attack (this turn) followup ---
        if (FOLLOWUP_MUST_ATTACK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Must attack if possible this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.setP1ForwardMustAttack(t.idx());
                    else          ctx.setP2ForwardMustAttack(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot attack or block (this turn) followup ---
        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Cannot attack or block this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) { ctx.setP1ForwardCannotAttack(t.idx()); ctx.setP1ForwardCannotBlock(t.idx()); }
                    else          { ctx.setP2ForwardCannotAttack(t.idx()); ctx.setP2ForwardCannotBlock(t.idx()); }
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot attack or block until end of opponent's/next turn (persistent) followup ---
        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK_PERSISTENT.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Cannot attack or block until end of next turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.setP1ForwardCannotAttackOrBlockPersistent(t.idx());
                    else          ctx.setP2ForwardCannotAttackOrBlockPersistent(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power-becomes followup: "Its power becomes N until end of turn" ---
        Matcher becomesM = FOLLOWUP_POWER_BECOMES.matcher(primaryFollowup);
        if (becomesM.find()) {
            int targetPower = Integer.parseInt(becomesM.group(1));
            return ctx -> {
                ctx.logEntry(choosePrefix + " → power becomes " + targetPower);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.setTargetPower(t, targetPower));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power boost followup (standard order: "it/they gains +N power [, traits] until…") ---
        Matcher boostM = FOLLOWUP_POWER_BOOST.matcher(primaryFollowup);
        if (boostM.find()) {
            int boost = Integer.parseInt(boostM.group(1));
            EnumSet<CardData.Trait> traits = parseTraits(boostM.group(2));
            String logSuffix = boostLogSuffix(boost, traits);
            return ctx -> {
                ctx.logEntry(choosePrefix + logSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, traits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power boost for each [element] [type] you control (must precede plain UNTIL boost) ---
        Matcher boostForEachM = FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH.matcher(primaryFollowup);
        if (boostForEachM.find()) {
            boolean untilPrefix = boostForEachM.group(1) != null;
            int    perUnit    = Integer.parseInt(untilPrefix ? boostForEachM.group(1) : boostForEachM.group(4));
            String srcElem    = untilPrefix ? boostForEachM.group("element") : boostForEachM.group("element2");
            String srcType    = (untilPrefix ? boostForEachM.group("chartype") : boostForEachM.group("chartype2")).toLowerCase();
            boolean cntFwd    = srcType.startsWith("forward") || srcType.startsWith("character");
            boolean cntBkp    = srcType.startsWith("backup")  || srcType.startsWith("character");
            boolean cntMon    = srcType.startsWith("monster")  || srcType.startsWith("character");
            String logSuffix  = " +" + perUnit + "×[" + (srcElem != null ? srcElem + " " : "") + boostForEachM.group("chartype") + " you control] until EOT";
            return ctx -> {
                int n      = ctx.countSelfFieldCards(cntFwd, cntBkp, cntMon, null, null, null, srcElem);
                int boost  = perUnit * n;
                ctx.logEntry(choosePrefix + logSuffix + " (n=" + n + ", boost=" + boost + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, noTraits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power boost for each Job [name] you control (must precede plain UNTIL boost) ---
        Matcher boostForEachJobM = FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_JOB.matcher(primaryFollowup);
        if (boostForEachJobM.find()) {
            boolean untilPrefixJ = boostForEachJobM.group("amount") != null;
            int    perUnitJ  = Integer.parseInt(untilPrefixJ ? boostForEachJobM.group("amount") : boostForEachJobM.group("amount2"));
            String jobBracket = untilPrefixJ ? boostForEachJobM.group("jobb") : boostForEachJobM.group("jobb2");
            String jobWritten = untilPrefixJ ? boostForEachJobM.group("jobw") : boostForEachJobM.group("jobw2");
            String jobTypeStr = untilPrefixJ ? boostForEachJobM.group("jobt") : boostForEachJobM.group("jobt2");
            String jobNameJ   = (jobBracket != null ? jobBracket : jobWritten).trim();
            boolean jwFwd = jobTypeStr == null || jobTypeStr.matches("(?i)Forwards?");
            boolean jwBkp = jobTypeStr == null || jobTypeStr.matches("(?i)Backups?");
            boolean jwMon = jobTypeStr == null || jobTypeStr.matches("(?i)Monsters?");
            String logSuffixJ = " +" + perUnitJ + "×[Job " + jobNameJ + " you control] until EOT";
            return ctx -> {
                int n     = ctx.countSelfFieldCards(jwFwd, jwBkp, jwMon, jobNameJ, null);
                int boost = perUnitJ * n;
                ctx.logEntry(choosePrefix + logSuffixJ + " (n=" + n + ", boost=" + boost + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, noTraits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Until…, it gains +N power for each [Name] Counter placed on [card]." (counter-scaled xValue) ---
        // Must be checked before FOLLOWUP_POWER_BOOST_UNTIL, which would match only the +N and drop the for-each.
        Matcher boostForEachCounterM = FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_COUNTER.matcher(primaryFollowup);
        if (boostForEachCounterM.find()) {
            int perUnit = Integer.parseInt(boostForEachCounterM.group("perunit"));
            String counterName = boostForEachCounterM.group("counterName").trim();
            return ctx -> {
                int boost = perUnit * xValue;
                ctx.logEntry(choosePrefix + " — +" + perUnit + " power ×" + xValue + " " + counterName + " Counter(s) = +" + boost + " until EOT");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, noTraits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Until…, it gains +N power for each point of damage you have received." ---
        // Must be checked before FOLLOWUP_POWER_BOOST_UNTIL, which matches on the +N and drops the for-each.
        Matcher boostUntilSelfDmgM = FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_SELF_DMG.matcher(primaryFollowup);
        if (boostUntilSelfDmgM.find()) {
            int perUnit = Integer.parseInt(boostUntilSelfDmgM.group("perunit"));
            return ctx -> {
                int dmgCount = ctx.p1DamageCount();
                int boost    = perUnit * dmgCount;
                ctx.logEntry(choosePrefix + " — +"+perUnit+" power ×" + dmgCount + " damage = +" + boost + " power until EOT");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, noTraits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power boost followup (until-prefix order: "Until…, it/they gains +N power [and traits]") ---
        Matcher boostUntilM = FOLLOWUP_POWER_BOOST_UNTIL.matcher(primaryFollowup);
        if (boostUntilM.find()) {
            int boost = Integer.parseInt(boostUntilM.group(1));
            EnumSet<CardData.Trait> traits = parseTraits(boostUntilM.group(2));
            String logSuffix = boostLogSuffix(boost, traits);

            // Detect "If its power has become N or less/more, return [name] to hand" secondary
            // and handle it inline so we have access to the target list for the power check.
            final String    crCard;
            final int       crThreshold;
            final boolean   crOrLess;
            final boolean   crToOwner;
            final Consumer<GameContext> boostSecondary;
            {
                Matcher crM = secondaryText != null ? CONDITIONAL_POWER_RETURN.matcher(secondaryText) : null;
                if (crM != null && crM.find()) {
                    crCard       = crM.group("name").trim();
                    crThreshold  = Integer.parseInt(crM.group("threshold"));
                    crOrLess     = "less".equalsIgnoreCase(crM.group("cmp"));
                    crToOwner    = crM.group("toowner") != null;
                    boostSecondary = null;
                } else {
                    crCard       = null;
                    crThreshold  = 0;
                    crOrLess     = false;
                    crToOwner    = false;
                    boostSecondary = secondary;
                }
            }

            return ctx -> {
                ctx.logEntry(choosePrefix + logSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, traits));
                if (crCard != null) {
                    boolean condMet = ts.stream().anyMatch(t -> {
                        int p = ctx.effectiveTargetPower(t);
                        return crOrLess ? p <= crThreshold : p >= crThreshold;
                    });
                    if (condMet) {
                        ctx.logEntry("Condition met (power " + (crOrLess ? "≤ " : "≥ ") + crThreshold + "): return " + crCard + " to " + (crToOwner ? "owner's" : "your") + " hand");
                        if (crToOwner) ctx.returnNamedCardToOwnersHand(crCard);
                        else           ctx.returnNamedCardToYourHand(crCard);
                    } else {
                        ctx.logEntry("Condition not met: " + crCard + " stays (power " + (crOrLess ? "> " : "< ") + crThreshold + ")");
                    }
                } else if (boostSecondary != null) {
                    boostSecondary.accept(ctx);
                }
            };
        }

        // --- Trait-choice grant followup: "it gains [T1] or [T2] until end of turn" ---
        Matcher choiceM = FOLLOWUP_KEYWORD_GRANT_CHOICE.matcher(primaryFollowup);
        if (choiceM.find()) {
            String t1Name = choiceM.group("t1").trim();
            String t2Name = choiceM.group("t2").trim();
            EnumSet<CardData.Trait> t1Traits = parseTraits(t1Name);
            EnumSet<CardData.Trait> t2Traits = parseTraits(t2Name);
            return ctx -> {
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ts.isEmpty()) return;
                String chosen = ctx.selectOption("Grant " + t1Name + " or " + t2Name + "?",
                        new String[]{t1Name, t2Name});
                EnumSet<CardData.Trait> traits = (chosen != null && chosen.equalsIgnoreCase(t2Name)) ? t2Traits : t1Traits;
                String logLabel = chosen != null ? chosen : t1Name;
                ctx.logEntry(choosePrefix + " — grants " + logLabel);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, 0, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, 0, traits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Keyword-only grant followup: "it/they gains Haste [and …] until end of turn" ---
        Matcher keywordM = FOLLOWUP_KEYWORD_GRANT.matcher(primaryFollowup);
        if (keywordM.find()) {
            EnumSet<CardData.Trait> traits = parseTraits(keywordM.group(1));
            String logSuffix = boostLogSuffix(0, traits);
            return ctx -> {
                ctx.logEntry(choosePrefix + logSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, 0, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, 0, traits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Keyword-only grant followup (EOT prefix: "Until end of turn, it gains Haste [and …]") ---
        Matcher keywordUntilM = FOLLOWUP_KEYWORD_GRANT_UNTIL.matcher(primaryFollowup);
        if (keywordUntilM.find()) {
            EnumSet<CardData.Trait> traits = parseTraits(keywordUntilM.group(1));
            String logSuffix = boostLogSuffix(0, traits);
            return ctx -> {
                ctx.logEntry(choosePrefix + logSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, 0, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, 0, traits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power / trait reduce followup (standard order: "it/they loses N power [, traits] until…") ---
        Matcher reduceM = FOLLOWUP_POWER_REDUCE.matcher(primaryFollowup);
        if (reduceM.find()) {
            int reduction = reduceM.group(1) != null ? Integer.parseInt(reduceM.group(1)) : 0;
            EnumSet<CardData.Trait> traits = parseTraits(reduceM.group(2));
            String logSuffix = reduceLogSuffix(reduction, traits);
            return ctx -> {
                ctx.logEntry(choosePrefix + logSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.reduceTarget(t, reduction, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.reduceTarget(t, reduction, traits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power reduce for each card in your hand ("Until…, it/they loses N power for each card in your hand") ---
        Matcher reduceForEachHandM = FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH_HAND.matcher(primaryFollowup);
        if (reduceForEachHandM.find()) {
            int perCard = Integer.parseInt(reduceForEachHandM.group(1));
            return ctx -> {
                int n = ctx.yourHandSize();
                int reduction = perCard * n;
                ctx.logEntry(choosePrefix + " -" + perCard + "×[your hand] until EOT (n=" + n + ", reduction=" + reduction + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.reduceTarget(t, reduction, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.reduceTarget(t, reduction, noTraits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power reduce for each [element] [type] you control (must precede plain UNTIL reduce) ---
        Matcher reduceForEachM = FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH.matcher(primaryFollowup);
        if (reduceForEachM.find()) {
            boolean untilPrefix = reduceForEachM.group(1) != null;
            int    perUnit    = Integer.parseInt(untilPrefix ? reduceForEachM.group(1) : reduceForEachM.group(4));
            String srcElem    = untilPrefix ? reduceForEachM.group("element") : reduceForEachM.group("element2");
            String srcType    = (untilPrefix ? reduceForEachM.group("chartype") : reduceForEachM.group("chartype2")).toLowerCase();
            boolean cntFwd    = srcType.startsWith("forward") || srcType.startsWith("character");
            boolean cntBkp    = srcType.startsWith("backup")  || srcType.startsWith("character");
            boolean cntMon    = srcType.startsWith("monster")  || srcType.startsWith("character");
            String typeLabel  = untilPrefix ? reduceForEachM.group("chartype") : reduceForEachM.group("chartype2");
            String logSuffix  = " -" + perUnit + "×[" + (srcElem != null ? srcElem + " " : "") + typeLabel + " you control] until EOT";
            return ctx -> {
                int n         = ctx.countSelfFieldCards(cntFwd, cntBkp, cntMon, null, null, null, srcElem);
                int reduction = perUnit * n;
                ctx.logEntry(choosePrefix + logSuffix + " (n=" + n + ", reduction=" + reduction + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.reduceTarget(t, reduction, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.reduceTarget(t, reduction, noTraits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power / trait reduce followup (until-prefix order: "Until…, it/they loses N power [and traits]") ---
        Matcher reduceUntilM = FOLLOWUP_POWER_REDUCE_UNTIL.matcher(primaryFollowup);
        if (reduceUntilM.find()) {
            int reduction = reduceUntilM.group(1) != null ? Integer.parseInt(reduceUntilM.group(1)) : 0;
            EnumSet<CardData.Trait> traits = parseTraits(reduceUntilM.group(2));
            String logSuffix = reduceLogSuffix(reduction, traits);
            return ctx -> {
                ctx.logEntry(choosePrefix + logSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.reduceTarget(t, reduction, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.reduceTarget(t, reduction, traits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Opponent discard followup ---
        Matcher discardM = OPPONENT_DISCARD.matcher(primaryFollowup);
        if (discardM.find()) {
            int count = Integer.parseInt(discardM.group(1));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Opponent discards " + count);
                ctx.forceOpponentDiscard(count);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Self-referential boost followup: "<cardName> gains [+N power] [traits] until end of turn" ---
        if (source != null) {
            Matcher selfM = SELF_POWER_BOOST.matcher(primaryFollowup);
            if (selfM.find() && selfM.group("selfsubject").trim().equalsIgnoreCase(source.name())) {
                int boost = selfM.group("selfamount") != null ? Integer.parseInt(selfM.group("selfamount")) : 0;
                EnumSet<CardData.Trait> traits = parseTraits(selfM.group("selftraits"));
                String logSuffix = boostLogSuffix(boost, traits);
                return ctx -> {
                    ctx.logEntry(choosePrefix + " — " + source.name() + logSuffix);
                    ctx.boostSourceForward(source, boost, traits);
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- Cancel effect followup (counters a Summon on the stack) ---
        if (FOLLOWUP_CANCEL_EFFECT.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Cancel its effect");
                ctx.cancelStackEntry();
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Next incoming damage = 0 followup ---
        if (FOLLOWUP_SHIELD_NEXT_DMG_ZERO.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Shield: next damage becomes 0");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldNextIncomingDamage);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Next ability/summon damage reduced by N followup ---
        Matcher shieldAbilRedM = FOLLOWUP_SHIELD_NEXT_ABILITY_DMG_REDUCTION.matcher(primaryFollowup);
        if (shieldAbilRedM.find()) {
            int reduction = Integer.parseInt(shieldAbilRedM.group("reduction"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Shield: next ability/summon damage reduced by " + reduction);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.shieldNextAbilityIncomingDamageReduction(t, reduction));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Next incoming damage reduced by N followup ---
        Matcher shieldRedM = FOLLOWUP_SHIELD_NEXT_DMG_REDUCTION.matcher(primaryFollowup);
        if (shieldRedM.find()) {
            int reduction = Integer.parseInt(shieldRedM.group("reduction"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Shield: next damage reduced by " + reduction);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.shieldNextIncomingDamageReduction(t, reduction));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Incoming damage increased by N followup ---
        Matcher dmgIncM = FOLLOWUP_DEBUFF_INCOMING_DMG_INCREASE.matcher(primaryFollowup);
        if (dmgIncM.find()) {
            int amount = Integer.parseInt(dmgIncM.group("amount"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Debuff: incoming damage increased by " + amount);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.debuffIncomingDamageIncrease(t, amount));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Next outgoing damage = 0 followup ---
        if (FOLLOWUP_SHIELD_NEXT_OUTGOING_ZERO.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Shield: next outgoing damage becomes 0");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldNextOutgoingDamage);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Per-card non-lethal protection followup ---
        if (FOLLOWUP_SHIELD_NONLETHAL.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Shield: damage less than power becomes 0 this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldNonLethal);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "It gains ability-damage shield" followup ---
        if (FOLLOWUP_GAINS_SHIELD_ABILITY_ONLY.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Shield: gains ability-damage nullification until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldAbilityOnlyDamage);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Cannot be broken" until end of turn ---
        if (FOLLOWUP_CANNOT_BE_BROKEN.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Shield: cannot be broken until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldCannotBeBroken);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "It cannot be broken this turn." (simple form) ---
        if (FOLLOWUP_CANNOT_BE_BROKEN_SIMPLE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Shield: cannot be broken this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldCannotBeBroken);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Cannot be broken by opposing Summons or abilities that don't deal damage" ---
        if (FOLLOWUP_CANNOT_BE_BROKEN_BY_NON_DMG.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Shield: cannot be broken by opposing non-damage effects this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldCannotBeBrokenByNonDmg);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Breaktouch battle: "When this Forward deals battle damage to a Forward, break that Forward" until EOT ---
        if (FOLLOWUP_GAINS_BREAKTOUCH_BATTLE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Breaktouch (battle damage) until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldBreaktouchBattle);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- End-of-turn conditional damage followup ---
        // e.g. "At the end of this turn, if you control <name>, deal it N damage."
        Matcher eotDmgM = FOLLOWUP_END_OF_TURN_COND_DAMAGE.matcher(primaryFollowup);
        if (eotDmgM.find()) {
            String condCard = eotDmgM.group("cardName").trim();
            int damage      = Integer.parseInt(eotDmgM.group("damage"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — End of turn: if you control " + condCard + ", deal " + damage + " damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (!ts.isEmpty()) {
                    ctx.addEndOfTurnEffect(endCtx -> {
                        if (endCtx.abilityUserControlsCard(condCard)) {
                            sortedByIdxDesc(ts, true) .forEach(t -> endCtx.damageTarget(t, damage));
                            sortedByIdxDesc(ts, false).forEach(t -> endCtx.damageTarget(t, damage));
                        } else {
                            endCtx.logEntry("End-of-turn damage skipped: " + condCard + " not on field");
                        }
                    });
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Select a Job. It gains that Job until the end of the turn." ---
        // Checked against the full followup (before dot-split) so both sentences are seen together.
        if (FOLLOWUP_SELECT_JOB_GRANT.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Select a Job, grant until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ts.isEmpty()) return;
                String job = ctx.selectJobFromDatabase();
                if (job == null || job.isBlank()) return;
                ts.forEach(t -> ctx.grantJobUntilEndOfTurn(t, job));
            };
        }

        // --- "If it deals damage to a Forward this turn, the damage increases by N instead." ---
        Matcher outBoostM = FOLLOWUP_OUTGOING_DMG_BOOST_THIS_TURN.matcher(primaryFollowup);
        if (outBoostM.find()) {
            int amount = Integer.parseInt(outBoostM.group("amount"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Outgoing damage +" + amount + " to Forwards this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.boostForwardOutgoingDamageThisTurn(t, amount));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // Recognised "Choose" header but followup not yet implemented
        Consumer<GameContext> warnEffect = ctx -> ctx.logEntry(
                "[ActionResolver] Choose effect — followup not yet implemented: " + followup);
        return secondary == null ? warnEffect : warnEffect.andThen(secondary);
    }

    /** Returns targets belonging to {@code isP1} sorted by descending index (safe for list removal). */
    private static java.util.stream.Stream<ForwardTarget> sortedByIdxDesc(
            List<ForwardTarget> targets, boolean isP1) {
        return targets.stream()
                .filter(t -> t.isP1() == isP1)
                .sorted((a, b) -> Integer.compare(b.idx(), a.idx()));
    }

    /** Builds a log suffix like " — Gain +1000 power, Haste, and First Strike until end of turn". */
    private static String boostLogSuffix(int amount, EnumSet<CardData.Trait> traits) {
        List<String> parts = new ArrayList<>();
        if (amount != 0)                                  parts.add("+" + amount + " power");
        if (traits.contains(CardData.Trait.HASTE))        parts.add("Haste");
        if (traits.contains(CardData.Trait.FIRST_STRIKE)) parts.add("First Strike");
        if (traits.contains(CardData.Trait.BRAVE))        parts.add("Brave");
        StringBuilder sb = new StringBuilder(" — Gain ");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                if (parts.size() == 2)            sb.append(" and ");
                else if (i == parts.size() - 1)   sb.append(", and ");
                else                              sb.append(", ");
            }
            sb.append(parts.get(i));
        }
        sb.append(" until end of turn");
        return sb.toString();
    }

    /** Returns a human-readable list of trait names, e.g. {@code "First Strike and Brave"}, or {@code ""}. */
    /**
     * Replaces literal periods in {@code source}'s name with the middle-dot character (·) so that
     * lazy regex quantifiers inside CHOOSE_CHARACTER_PATTERN do not mistake a mid-name period-space
     * sequence (e.g. "Dr. Mog") for the sentence delimiter ". ".  Restore with
     * {@link #restorePeriodInName}.
     */
    private static String escapePeriodInName(String text, CardData source) {
        if (source == null || !source.name().contains(".")) return text;
        return text.replace(source.name(), source.name().replace('.', '·'));
    }

    /** Inverse of {@link #escapePeriodInName}: restores middle-dots back to periods. */
    private static String restorePeriodInName(String text, CardData source) {
        if (source == null || !source.name().contains(".")) return text;
        return text.replace(source.name().replace('.', '·'), source.name());
    }

    /**
     * Removes any trailing/embedded restriction-only sentences already captured as boolean flags
     * (once-per-turn, main-phase-only, your-turn-only, while-attacking, etc.) from {@code text},
     * then strips leftover leading/trailing punctuation.  Returns an empty string if nothing
     * remains after stripping.
     */
    private static String stripRestrictionSentences(String text) {
        if (text == null || text.isBlank()) return "";
        String s = text;
        s = CardData.ONCE_PER_TURN_PATTERN               .matcher(s).replaceAll("").trim();
        // Strip the combined "during your Main Phase and if X is in the Break Zone" form before
        // MAIN_PHASE_ONLY_PATTERN so the whole sentence is removed as a unit rather than leaving
        // "and if X is in the Break Zone." as an unparsed secondary fragment.
        s = CardData.OWN_BZ_NAME_REQUIRED_RESTRICTION  .matcher(s).replaceAll("").trim();
        s = CardData.MAIN_PHASE_ONLY_PATTERN              .matcher(s).replaceAll("").trim();
        s = CardData.YOUR_TURN_AND_CONTROL_IF_PATTERN    .matcher(s).replaceAll("").trim();
        s = CardData.YOUR_TURN_ONLY_PATTERN               .matcher(s).replaceAll("").trim();
        s = CardData.OPP_TURN_ONLY_PATTERN                .matcher(s).replaceAll("").trim();
        s = CardData.OPP_NO_CARDS_IN_HAND_RESTRICTION     .matcher(s).replaceAll("").trim();
        s = CardData.WHILE_PARTY_ATTACKING_PATTERN.matcher(s).replaceAll("").trim();
        s = CardData.WHILE_CARD_ATTACKING_PATTERN .matcher(s).replaceAll("").trim();
        s = CardData.WHILE_CARD_BLOCKING_PATTERN  .matcher(s).replaceAll("").trim();
        s = CardData.WHILE_CARD_IN_HAND_PATTERN   .matcher(s).replaceAll("").trim();
        s = CardData.SOURCE_IN_BATTLE_PATTERN     .matcher(s).replaceAll("").trim();
        s = CardData.OPP_DISCARD_THIS_TURN_PATTERN .matcher(s).replaceAll("").trim();
        s = CardData.CAST_SUMMON_THIS_TURN_PATTERN .matcher(s).replaceAll("").trim();
        s = CardData.OWN_DAMAGE_THRESHOLD_RESTRICTION.matcher(s).replaceAll("").trim();
        s = CardData.NAMED_CARD_TOOK_DAMAGE_THIS_TURN_RESTRICTION.matcher(s).replaceAll("").trim();
        s = CardData.SELF_RECEIVED_DAMAGE_THIS_TURN_RESTRICTION   .matcher(s).replaceAll("").trim();
        s = CardData.FORWARD_PUT_TO_BZ_THIS_TURN_RESTRICTION      .matcher(s).replaceAll("").trim();
        s = CardData.ELEMENT_FORWARD_ENTERED_THIS_TURN_PATTERN.matcher(s).replaceAll("").trim();
        s = CardData.COUNTER_MINIMUM_RESTRICTION              .matcher(s).replaceAll("").trim();
        s = CardData.OPP_HAND_AT_MOST_RESTRICTION             .matcher(s).replaceAll("").trim();
        s = CardData.SELF_NO_CARDS_IN_HAND_RESTRICTION        .matcher(s).replaceAll("").trim();
        s = CardData.CP_BACKUP_ONLY_ABILITY                   .matcher(s).replaceAll("").trim();
        s = CardData.CP_ELEMENTS_ONLY_ABILITY                 .matcher(s).replaceAll("").trim();
        s = CardData.CONTROL_IF_PATTERN                    .matcher(s).replaceAll("").trim();
        s = CardData.CONTROL_IF_NOT_ANY_PATTERN            .matcher(s).replaceAll("").trim();
        s = CardData.OPPONENT_CONTROLS_N_OR_MORE_PATTERN   .matcher(s).replaceAll("").trim();
        s = CardData.COUNTER_ZERO_RESTRICTION              .matcher(s).replaceAll("").trim();
        // Strip leftover leading/trailing ", and" / "," / "." artifacts
        s = s.replaceAll("^[,.;\\s]+|[,.;\\s]+$", "").trim();
        return s;
    }

    private static String traitNamesOnly(EnumSet<CardData.Trait> traits) {
        List<String> names = new ArrayList<>();
        if (traits.contains(CardData.Trait.HASTE))        names.add("Haste");
        if (traits.contains(CardData.Trait.FIRST_STRIKE)) names.add("First Strike");
        if (traits.contains(CardData.Trait.BRAVE))        names.add("Brave");
        return switch (names.size()) {
            case 0  -> "";
            case 1  -> names.get(0);
            case 2  -> names.get(0) + " and " + names.get(1);
            default -> names.get(0) + ", " + names.get(1) + ", and " + names.get(2);
        };
    }

    /** Parses a traits string (e.g. {@code ", Haste, and First Strike"}) into a set of traits. */
    private static EnumSet<CardData.Trait> parseTraits(String traitStr) {
        EnumSet<CardData.Trait> traits = EnumSet.noneOf(CardData.Trait.class);
        if (traitStr == null || traitStr.isEmpty()) return traits;
        String s = traitStr.toLowerCase();
        if (s.contains("haste"))         traits.add(CardData.Trait.HASTE);
        if (s.contains("first strike"))  traits.add(CardData.Trait.FIRST_STRIKE);
        if (s.contains("brave"))         traits.add(CardData.Trait.BRAVE);
        return traits;
    }

    /**
     * Parses "Until the end of the turn, &lt;cardName&gt; gains +N power [and traits]" as a
     * standalone self-buff.  The subject must match {@code source.name()} (case-insensitive);
     * pronoun subjects ("it", "they") are ignored here — they are handled as Choose followups.
     */
    /**
     * Parses "Until end of turn, &lt;subject&gt; gains +N power and
     * 'When &lt;subject&gt; attacks, &lt;effect&gt;.'"
     * Applies the power boost and registers a temporary one-turn attack trigger.
     * Must be tried before {@link #tryParseStandalonePowerBoostUntil} because it is more specific.
     */
    private static Consumer<GameContext> tryParseStandalonePowerBoostAndAttackTrigger(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_POWER_BOOST_AND_ATTACK_TRIGGER.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        int boost = Integer.parseInt(m.group("amount"));
        String attackEffectText = m.group("attackEffect").trim();
        Consumer<GameContext> attackEffect = parse(attackEffectText, source);
        if (attackEffect == null) return null;
        return ctx -> {
            ctx.logEntry(source.name() + " — +" + boost + " power until end of turn"
                    + " and gains 'When attacks: " + attackEffectText + "'");
            ctx.boostSourceForward(source, boost, EnumSet.noneOf(CardData.Trait.class));
            ctx.addTempAttackTrigger(source, attackEffect);
        };
    }

    private static Consumer<GameContext> tryParseStandaloneGainsTraitsAndCannotBeBlocked(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_GAINS_TRAITS_AND_CANNOT_BE_BLOCKED.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));
        String logSuffix = boostLogSuffix(0, traits) + " and cannot be blocked until end of turn";
        return ctx -> {
            ctx.logEntry(source.name() + logSuffix);
            ctx.boostSourceForward(source, 0, traits);
            ctx.setSourceForwardCannotBeBlocked(source);
        };
    }

    private static Consumer<GameContext> tryParseStandalonePowerBoostUntil(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_POWER_BOOST_UNTIL.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (subject.equalsIgnoreCase("it") || subject.equalsIgnoreCase("they")) return null;
        if (!subject.equalsIgnoreCase(source.name())) return null;
        int boost = m.group("amount") != null ? Integer.parseInt(m.group("amount")) : 0;
        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));
        if (boost == 0 && traits.isEmpty()) return null;
        String logSuffix = boostLogSuffix(boost, traits);
        return ctx -> {
            ctx.logEntry(source.name() + logSuffix);
            ctx.boostSourceForward(source, boost, traits);
        };
    }

    /**
     * Parses "Double the power of &lt;cardName&gt; until end of turn" as a standalone self-buff.
     * The subject must match {@code source.name()} (case-insensitive).
     */
    private static Consumer<GameContext> tryParseStandaloneDoublePowerUntil(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_DOUBLE_POWER_UNTIL.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry(source.name() + " — power doubled until end of turn");
            ctx.doubleSourceForwardPower(source, EnumSet.noneOf(CardData.Trait.class));
        };
    }

    /**
     * Parses "Until the end of the turn, &lt;cardName&gt; doubles its power [and gains traits]".
     * Subject must match {@code source.name()}.
     */
    private static Consumer<GameContext> tryParseStandaloneDoublesItsPowerUntil(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_DOUBLES_ITS_POWER_UNTIL.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));
        String trailPart = traitNamesOnly(traits);
        String logSuffix = " — power doubled" + (trailPart.isEmpty() ? "" : ", gains " + trailPart) + " until end of turn";
        return ctx -> {
            ctx.logEntry(source.name() + logSuffix);
            ctx.doubleSourceForwardPower(source, traits);
        };
    }

    /**
     * Parses "At the beginning of your next turn's Main Phase 1 and until the end of the same
     * turn, &lt;cardName&gt;'s power will double." — defers doubling to the start of next Main Phase 1.
     * Subject must match {@code source.name()}.
     */
    private static Consumer<GameContext> tryParseStandaloneDoublePowerMainPhaseNextTurn(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_DOUBLE_POWER_MAIN_PHASE_NEXT_TURN.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry(source.name() + " — power will double at the start of next Main Phase 1");
            ctx.addPendingMainPhase1Effect(innerCtx -> {
                innerCtx.logEntry(source.name() + " — power doubled until end of turn (deferred)");
                innerCtx.doubleSourceForwardPower(source, EnumSet.noneOf(CardData.Trait.class));
            });
        };
    }

    /**
     * Builds a log suffix like " — Lose 1000 power, Haste, and First Strike until end of turn".
     * Power and traits are listed in order; either may be absent.
     */
    private static String reduceLogSuffix(int amount, EnumSet<CardData.Trait> traits) {
        List<String> parts = new ArrayList<>();
        if (amount > 0) parts.add(amount + " power");
        if (traits.contains(CardData.Trait.HASTE))        parts.add("Haste");
        if (traits.contains(CardData.Trait.FIRST_STRIKE)) parts.add("First Strike");
        if (traits.contains(CardData.Trait.BRAVE))        parts.add("Brave");
        StringBuilder sb = new StringBuilder(" — Lose ");
        if (parts.size() == 1) {
            sb.append(parts.get(0));
        } else if (parts.size() == 2) {
            sb.append(parts.get(0)).append(" and ").append(parts.get(1));
        } else if (parts.size() >= 3) {
            for (int i = 0; i < parts.size() - 1; i++) sb.append(parts.get(i)).append(", ");
            sb.append("and ").append(parts.get(parts.size() - 1));
        }
        return sb.append(" until end of turn").toString();
    }

    /**
     * Parses "Until the end of the turn, &lt;cardName&gt; loses [N power] [and traits]" as a
     * standalone self-debuff on the source card.  Pronoun subjects are ignored here.
     */
    private static Consumer<GameContext> tryParseStandalonePowerReduceUntil(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_POWER_REDUCE_UNTIL.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (subject.equalsIgnoreCase("it") || subject.equalsIgnoreCase("they")) return null;
        if (!subject.equalsIgnoreCase(source.name())) return null;
        String amountStr = m.group("amount");
        int reduction = amountStr != null ? Integer.parseInt(amountStr) : 0;
        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));
        String logSuffix = reduceLogSuffix(reduction, traits);
        return ctx -> {
            ctx.logEntry(source.name() + logSuffix);
            ctx.reduceSourceForward(source, reduction, traits);
        };
    }

    /**
     * Parses "&lt;cardName&gt; gains +N power." (no duration clause) as a permanent passive
     * field-ability self-boost.  Subject must match {@code source.name()}.
     */
    private static Consumer<GameContext> tryParseFieldSelfPowerBoost(String text, CardData source) {
        if (source == null) return null;
        Matcher m = FIELD_SELF_POWER_BOOST.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        int boost = Integer.parseInt(m.group("amount"));
        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));
        return ctx -> {
            String traitDesc = traits.isEmpty() ? "" : " and " + traitNamesOnly(traits);
            ctx.logEntry(source.name() + " — Gain +" + boost + " power" + traitDesc + " (field)");
            ctx.boostSourceForward(source, boost, traits);
        };
    }

    private static Consumer<GameContext> tryParseDoubleOutgoingDamageThisTurn(String text, CardData source) {
        if (source == null) return null;
        Matcher m = DOUBLE_OUTGOING_DAMAGE_THIS_TURN.matcher(text);
        if (!m.find()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> ctx.doubleOutgoingDamage(source);
    }

    private static Consumer<GameContext> tryParseDoubleOpponentIncomingDamageThisTurn(String text) {
        if (!DOUBLE_OPPONENT_INCOMING_DAMAGE_THIS_TURN.matcher(text).find()) return null;
        return ctx -> ctx.doubleOpponentForwardIncomingDamage();
    }

    /**
     * Edea: "Choose 1 Forward opponent controls with a cost inferior or equal to the number
     * of [Element] [Backups/Forwards] you control. Break it."
     */
    private static Consumer<GameContext> tryParseChooseOppFwdDynCostBreak(String text) {
        Matcher m = CHOOSE_OPP_FWD_DYN_COST_BREAK.matcher(text);
        if (!m.find()) return null;
        String element  = m.group("element");
        String cardtype = m.group("cardtype").toLowerCase();
        boolean inclFwd = cardtype.startsWith("forward");
        boolean inclBkp = !inclFwd;
        String followupText = m.group("followup").trim();
        if (!followupText.toLowerCase().contains("break it")) return null;
        return ctx -> {
            int ceiling = ctx.selfFieldCount(element, inclFwd, inclBkp, false);
            ctx.logEntry("Choose 1 Forward opponent controls with cost ≤ " + ceiling
                    + " (# " + element + " " + cardtype + " you control)");
            List<ForwardTarget> ts = ctx.selectCharacters(1, false, true, false,
                    null, null, ceiling, "less", -1, null,
                    true, false, false, null, null, null, null, false, null, false);
            ts.forEach(ctx::breakTarget);
        };
    }

    /**
     * Shuyin: "Choose 1 Forward [control?] with a power inferior to [source]'s. [followup]"
     * The power ceiling is computed at runtime as sourcePower − 1000 (strictly inferior,
     * and FFTCG powers step in multiples of 1000).
     */
    private static Consumer<GameContext> tryParseChooseFwdPowerInferiorToSource(String text, CardData source) {
        if (source == null) return null;
        Matcher m = CHOOSE_FWD_POWER_INFERIOR_TO_SOURCE.matcher(text);
        if (!m.find()) return null;
        if (!m.group("sourcename").trim().equalsIgnoreCase(source.name())) return null;
        String control   = m.group("control");
        boolean oppOnly  = control != null && !control.equalsIgnoreCase("you control");
        boolean selfOnly = "you control".equalsIgnoreCase(control);
        String followupText = m.group("followup").trim();
        // Detect gain-control-EOT as the followup (handles "this Forward" phrasing)
        boolean gainControlEot = followupText.toLowerCase().contains("gain control")
                && followupText.toLowerCase().contains("end of");
        Consumer<GameContext> parsedFollowup = gainControlEot ? null : parse(followupText, source);
        if (!gainControlEot && parsedFollowup == null) return null;
        return ctx -> {
            int sp = ctx.fieldForwardPowerByName(source.name());
            if (sp <= 0) sp = source.power();
            int powerCeiling = sp - 1000;
            ctx.logEntry("Choose 1 Forward with power < " + sp);
            if (powerCeiling <= 0) { ctx.logEntry("No eligible targets — source power too low."); return; }
            List<ForwardTarget> ts = ctx.selectCharacters(1, false, oppOnly, selfOnly,
                    null, null, -1, null, powerCeiling, "less",
                    true, false, false, null, null, null, null, false, null, false);
            if (gainControlEot) ts.forEach(t -> ctx.gainControlOfForward(t, "endOfTurn", true));
            else { ctx.recordChosenTargets(ts); parsedFollowup.accept(ctx); }
        };
    }

    /**
     * Alphinaud: "Dull all the Forwards with a power equal or inferior to [source]'s
     * opponent controls."
     */
    private static Consumer<GameContext> tryParseDullAllOppFwdsPowerLeSource(String text, CardData source) {
        if (source == null) return null;
        Matcher m = DULL_ALL_OPP_FWDS_POWER_LE_SOURCE.matcher(text);
        if (!m.find()) return null;
        if (!m.group("sourcename").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> ctx.dullOpponentForwardsByPowerAtMost(source);
    }

    /**
     * Hojo followup: "Choose 1 Forward in your Break Zone with a cost inferior to that of the
     * removed Forward. Play it onto the field."
     * Reads {@link GameContext#lastRemovedFromGameCardCost()} to determine the cost ceiling.
     */
    private static Consumer<GameContext> tryParseChooseFwdBzCostInferiorToRemovedPlay(String text) {
        if (!CHOOSE_FWD_BZ_COST_INFERIOR_TO_REMOVED_PLAY.matcher(text).find()) return null;
        return ctx -> {
            int removedCost = ctx.lastRemovedFromGameCardCost();
            if (removedCost <= 0) { ctx.logEntry("No removed Forward cost tracked — cannot play from BZ"); return; }
            int costCeiling = removedCost - 1; // "inferior to N" = cost ≤ N-1
            ctx.logEntry("Choose 1 Forward from own Break Zone with cost < " + removedCost);
            List<ForwardTarget> ts = ctx.selectCharactersFromBreakZone(1, false, false, false,
                    null, null, costCeiling, "less", -1, null,
                    true, false, false, null, null, null, null, false, null, false);
            ts.forEach(ctx::playTargetOntoField);
        };
    }

    private static Consumer<GameContext> tryParseAllForwardIncomingDmgIncreaseThisTurn(String text) {
        Matcher m = ALL_FORWARD_INCOMING_DMG_INCREASE_THIS_TURN.matcher(text);
        if (!m.find()) return null;
        int amount = Integer.parseInt(m.group("amount"));
        return ctx -> ctx.increaseAllForwardIncomingDamage(amount);
    }

    private static Consumer<GameContext> tryParseDoubleOutgoingDamageThisTurnAlt(String text, CardData source) {
        if (source == null) return null;
        Matcher m = DOUBLE_OUTGOING_DAMAGE_THIS_TURN_ALT.matcher(text);
        if (!m.find()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> ctx.doubleOutgoingDamage(source);
    }

    private static Consumer<GameContext> tryParseChooseForwardDoubleIncomingThisTurn(String text) {
        if (!CHOOSE_FORWARD_DOUBLE_INCOMING_THIS_TURN.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Choose 1 Forward — incoming damage doubled this turn");
            List<ForwardTarget> ts = ctx.selectCharacters(1, false, false, false,
                    null, null, -1, null, -1, null, true, false, false,
                    null, null, null, null, false, null, false);
            if (!ts.isEmpty()) ctx.doubleForwardIncomingDamageThisTurn(ts.get(0));
        };
    }

    private static Consumer<GameContext> tryParseChooseForwardDoubleNextOutgoing(String text) {
        Matcher m = CHOOSE_FORWARD_DOUBLE_NEXT_OUTGOING.matcher(text);
        if (!m.find()) return null;
        String rawJob = m.group("job");
        final String jobFilter = rawJob != null ? rawJob.trim() : null;
        return ctx -> {
            String label = jobFilter != null ? "Job " + jobFilter + " " : "";
            ctx.logEntry("Choose 1 " + label + "Forward — next outgoing damage doubled this turn");
            List<ForwardTarget> ts = ctx.selectCharacters(1, false, false, false,
                    null, null, -1, null, -1, null, true, false, false,
                    jobFilter, null, null, null, false, null, false);
            if (!ts.isEmpty()) ctx.doubleForwardNextOutgoingDamage(ts.get(0));
        };
    }

    private static Consumer<GameContext> tryParseDoublePlayerAbilityOutgoingThisTurn(String text) {
        if (!DOUBLE_PLAYER_ABILITY_OUTGOING_THIS_TURN.matcher(text).find()) return null;
        return ctx -> ctx.doublePlayerAbilityOutgoingDamage();
    }

    /**
     * Parses "it [gains +N power] [traits] until end of turn" as a standalone boost applied to
     * {@code source}. Used when "it" refers to the source card — e.g. in watcher attack abilities
     * where the source is the attacking Forward, not the card that owns the ability.
     */
    private static Consumer<GameContext> tryParseStandaloneItPowerBoostUntil(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_POWER_BOOST.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("selfsubject").trim();
        if (!subject.equalsIgnoreCase("it") && !subject.equalsIgnoreCase("they")) return null;
        int boost = m.group("selfamount") != null ? Integer.parseInt(m.group("selfamount")) : 0;
        EnumSet<CardData.Trait> traits = parseTraits(m.group("selftraits"));
        if (boost == 0 && traits.isEmpty()) return null;
        String logSuffix = boostLogSuffix(boost, traits);
        return ctx -> {
            ctx.logEntry(source.name() + logSuffix);
            ctx.boostSourceForward(source, boost, traits);
        };
    }

    /**
     * Parses "&lt;cardName&gt; gains [+N power] [, traits] until end of turn" as a standalone
     * self-boost on the source card (standard order, no "Until" prefix).
     * Pronoun subjects ("it", "they") are skipped — they are followup pronouns.
     */
    private static Consumer<GameContext> tryParseStandaloneSelfBoost(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_POWER_BOOST.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("selfsubject").trim();
        if (subject.equalsIgnoreCase("it") || subject.equalsIgnoreCase("they")) return null;
        if (!subject.equalsIgnoreCase(source.name())) return null;
        int boost = m.group("selfamount") != null ? Integer.parseInt(m.group("selfamount")) : 0;
        EnumSet<CardData.Trait> traits = parseTraits(m.group("selftraits"));
        String logSuffix = boostLogSuffix(boost, traits);
        return ctx -> {
            ctx.logEntry(source.name() + logSuffix);
            ctx.boostSourceForward(source, boost, traits);
        };
    }

    private static Consumer<GameContext> tryParseStandaloneSelfBoostForEachCrystal(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_POWER_BOOST_FOR_EACH_CRYSTAL.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        int perCrystal = Integer.parseInt(m.group("amount"));
        return ctx -> {
            int n = ctx.crystalCount();
            int boost = perCrystal * n;
            ctx.logEntry(source.name() + " gains +" + boost + " power (" + perCrystal + "×" + n + " 《C》) until end of turn");
            ctx.boostSourceForward(source, boost, EnumSet.noneOf(CardData.Trait.class));
        };
    }

    /** Parses "[subject] gains +N power until the end of the turn and activate [name]." */
    private static Consumer<GameContext> tryParseSelfPowerBoostAndActivate(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_POWER_BOOST_AND_ACTIVATE.matcher(text.trim());
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        int boost = Integer.parseInt(m.group("amount"));
        String activateName = m.group("activateName").trim();
        return ctx -> {
            ctx.logEntry(source.name() + " gains +" + boost + " power until end of turn");
            ctx.boostSourceForward(source, boost, EnumSet.noneOf(CardData.Trait.class));
            ctx.logEntry("Effect: Activate " + activateName);
            List<ForwardTarget> ts = ctx.selectCharacters(
                    1, false, false, true, null, null, -1, null, -1, null,
                    true, true, true, null, activateName, null, null, false, null, false);
            ts.forEach(ctx::activateTarget);
        };
    }

    /**
     * Parses "[CardName]'s power becomes the same as that Forward's power until the end of the turn."
     * Sets the source card's power to the power of the Forward most recently removed from the game.
     */
    private static Consumer<GameContext> tryParseSourcePowerBecomesRemovedForwardPower(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = SOURCE_POWER_BECOMES_SAME_AS_REMOVED_FORWARD.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            int power = ctx.lastRemovedFromGameCardPower();
            ctx.logEntry(source.name() + " — power becomes " + power + " (removed Forward's power) until end of turn");
            ctx.setSourceForwardPower(source, power);
        };
    }

    /** Parses "Dull [CardName]." — dulls the source card with no other effect. */
    private static Consumer<GameContext> tryParseStandaloneSelfDull(String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_SELF_DULL.matcher(text.trim());
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry(source.name() + " — dulled");
            ctx.dullSourceForward(source);
        };
    }

    /**
     * Parses "Dull [CardName]. [CardName] gains '[...] cannot be broken.' until end of turn."
     * Dulls the source then shields it. Must be tried before {@link #tryParseStandaloneShieldCannotBeBroken}
     * so the dull step is not silently dropped.
     */
    private static Consumer<GameContext> tryParseStandaloneSelfDullAndShield(String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_SELF_DULL_AND_SHIELD_CANNOT_BE_BROKEN.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry(source.name() + " — Dull self and cannot be broken until end of turn");
            ctx.dullSourceForward(source);
            ctx.shieldSourceForward(source);
        };
    }

    /**
     * Parses standalone "cannot be broken until end of turn" grants:
     * <ul>
     *   <li>"[CardName] gains '[...] cannot be broken.' until end of turn." — self-shield</li>
     *   <li>"All [the] Forwards you control gain '[...] cannot be broken.' until end of turn." — all own</li>
     * </ul>
     */
    private static Consumer<GameContext> tryParseStandaloneShieldCannotBeBroken(
            String text, CardData source) {
        if (STANDALONE_ALL_FORWARDS_SHIELD_CANNOT_BE_BROKEN.matcher(text).find()) {
            return ctx -> {
                ctx.logEntry("Effect: All own Forwards cannot be broken until end of turn");
                ctx.shieldAllOwnForwards();
            };
        }
        if (source == null) return null;
        Matcher m = STANDALONE_SELF_SHIELD_CANNOT_BE_BROKEN.matcher(text);
        if (!m.find()) {
            m = STANDALONE_SELF_SHIELD_CANNOT_BE_BROKEN_SIMPLE.matcher(text);
            if (!m.find()) return null;
        }
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry(source.name() + " cannot be broken until end of turn");
            ctx.shieldSourceForward(source);
        };
    }

    private static Consumer<GameContext> tryParseAllOwnForwardsNullifyAbilityDamage(String text) {
        if (!ALL_OWN_FORWARDS_NULLIFY_ABILITY_DAMAGE_PATTERN.matcher(text.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: All own Forwards — damage from Summons/abilities becomes 0 this turn");
            boolean p1 = ctx.isP1();
            int count = p1 ? ctx.p1ForwardCount() : ctx.p2ForwardCount();
            for (int i = 0; i < count; i++)
                ctx.shieldAbilityDamage(new ForwardTarget(p1, i, ForwardTarget.CardZone.FORWARD));
        };
    }

    private static Consumer<GameContext> tryParseAllForwardsCannotBlock(String text) {
        if (!STANDALONE_ALL_FORWARDS_CANNOT_BLOCK.matcher(text).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: All Forwards cannot block this turn");
            for (int i = 0; i < ctx.p1ForwardCount(); i++) ctx.setP1ForwardCannotBlock(i);
            for (int i = 0; i < ctx.p2ForwardCount(); i++) ctx.setP2ForwardCannotBlock(i);
        };
    }

    private static Consumer<GameContext> tryParseForwardsOfCostCannotBlock(String text) {
        Matcher m = STANDALONE_FORWARDS_OF_COST_CANNOT_BLOCK.matcher(text);
        if (!m.matches()) return null;
        int costVal = Integer.parseInt(m.group("costval"));
        boolean orLess = m.group("cmp").equalsIgnoreCase("less");
        return ctx -> {
            ctx.logEntry("Effect: All Forwards of cost " + costVal + " or " + (orLess ? "less" : "more") + " cannot block this turn");
            for (int i = 0; i < ctx.p1ForwardCount(); i++)
                if (orLess ? ctx.p1Forward(i).cost() <= costVal : ctx.p1Forward(i).cost() >= costVal)
                    ctx.setP1ForwardCannotBlock(i);
            for (int i = 0; i < ctx.p2ForwardCount(); i++)
                if (orLess ? ctx.p2Forward(i).cost() <= costVal : ctx.p2Forward(i).cost() >= costVal)
                    ctx.setP2ForwardCannotBlock(i);
        };
    }

    private static Consumer<GameContext> tryParseEndOfNextTurnIfCardOnFieldOppLoses(String text) {
        Matcher m = END_OF_NEXT_TURN_IF_CARD_ON_FIELD_OPP_LOSES.matcher(text);
        if (!m.matches()) return null;
        String cardName = m.group("name").trim();
        return ctx -> {
            ctx.logEntry("Effect: Scheduled — at end of next turn, if " + cardName + " is on field, opponent loses");
            ctx.scheduleAtEndOfControllerNextTurn(innerCtx -> {
                if (innerCtx.isNamedCardOnField(cardName)) {
                    innerCtx.logEntry(cardName + " is on the field — opponent loses the game");
                    innerCtx.causeOpponentToLose();
                } else {
                    innerCtx.logEntry(cardName + " is NOT on the field — Sin condition not met");
                }
            });
        };
    }

    private static Consumer<GameContext> tryParseOppFwdsCannotBlockInferiorPower(String text) {
        if (!OPP_FWDS_CANNOT_BLOCK_INFERIOR_POWER_THIS_TURN.matcher(text).matches()) return null;
        return ctx -> ctx.setOppForwardsCannotBlockInferiorPowerThisTurn();
    }

    private static Consumer<GameContext> tryParseAllFwdsBlockedOnlyByLowerCostThisTurn(String text) {
        if (!ALL_FWDS_BLOCKED_ONLY_BY_LOWER_COST_THIS_TURN.matcher(text).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Each Forward can only be blocked by a Forward with cost ≤ its own this turn");
            ctx.setAllForwardsCannotBeBlockedByHigherCostThisTurn();
        };
    }

    private static Consumer<GameContext> tryParseOppFwdsLoseAllAbilitiesEot(String text) {
        if (!OPP_FWDS_LOSE_ALL_ABILITIES_EOT.matcher(text).matches()) return null;
        return ctx -> ctx.oppForwardsLoseAllAbilitiesUntilEndOfTurn();
    }

    private static Consumer<GameContext> tryParseOppFwdPowerBoostSuppressedThisTurn(String text) {
        if (!OPP_FWD_POWER_BOOST_SUPPRESSED_THIS_TURN.matcher(text).matches()) return null;
        return ctx -> ctx.setOppFwdPowerBoostSuppressedThisTurn();
    }

    private static Consumer<GameContext> tryParseOppFwdsLosePowerPerPlayCost(String text) {
        Matcher m = OPP_FWDS_LOSE_POWER_PER_PLAY_COST.matcher(text);
        if (!m.find()) return null;
        int powerPerCp = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: All opponent Forwards lose " + powerPerCp + "×cost power until end of turn");
            ctx.applyOppFwdsCostScaledPowerDebuff(powerPerCp);
        };
    }

    private static Consumer<GameContext> tryParseStandaloneCannotBeBlocked(String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_SELF_CANNOT_BE_BLOCKED.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (subject.equalsIgnoreCase("it") || subject.equalsIgnoreCase("they")) return null;
        if (!subject.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry(source.name() + " cannot be blocked this turn");
            ctx.setSourceForwardCannotBeBlocked(source);
        };
    }

    /** Parses "Draw N card(s)[, then discard M card(s)]" as a standalone effect. */
    private static final Pattern WHEN_YOU_DO_SO_SEQUENCE = Pattern.compile(
        "(?is)(?<primary>.+?)\\.\\s+(?:When|If)\\s+you\\s+do\\s+so,?\\s+(?<followup>.+)"
    );

    /**
     * Matches the optional-cost replay clause appended to Special abilities:
     * "You may [cost]. When/If you do so, use this (special) ability again without paying the cost."
     * Three cost variants:
     * <ul>
     *   <li>{@code payCost}     — element name from "pay 《Earth》"</li>
     *   <li>{@code dullName}    — card name from "dull active &lt;cardName&gt;"</li>
     *   <li>{@code discardName} — card name from "discard 1 Card Name &lt;cardName&gt;"</li>
     * </ul>
     */
    private static final Pattern MAY_COST_REPLAY_ABILITY = Pattern.compile(
        "(?i)You\\s+may\\s+(?:" +
            "pay\\s+《(?<payCost>[^》]+)》" +
            "|dull\\s+active\\s+(?<dullName>[^.,]+)" +
            "|discard\\s+1\\s+Card\\s+Name\\s+(?<discardName>[^.,]+)" +
        ")\\s*[.,]?\\s+(?:When|If)\\s+you\\s+do\\s+so,?\\s+" +
        "use\\s+this\\s+(?:special\\s+)?ability\\s+again\\s+without\\s+paying\\s+the\\s+cost[.!]?"
    );

    /**
     * Parses "X. When/If you do so, Y." into a sequence: resolve X, then resolve Y only if
     * X made progress (see {@link GameContext#effectMadeProgress()}). Returns {@code null} if
     * either half cannot be parsed, so non-sequence text falls through to the regular matchers.
     */
    private static Consumer<GameContext> tryParseWhenYouDoSoSequence(String text, CardData source, int xValue) {
        Matcher m = WHEN_YOU_DO_SO_SEQUENCE.matcher(text);
        if (!m.find()) return null;
        Consumer<GameContext> primary  = parse(m.group("primary").trim(),  source, xValue);
        Consumer<GameContext> followup = parse(m.group("followup").trim(), source, xValue);
        if (primary == null || followup == null) return null;
        return ctx -> {
            ctx.resetEffectProgress();
            primary.accept(ctx);
            if (ctx.effectMadeProgress()) followup.accept(ctx);
        };
    }

    /** Matches "If a Forward you controlled formed a party this turn, &lt;effect&gt;." */
    private static final Pattern IF_OWN_FORWARD_FORMED_PARTY = Pattern.compile(
        "(?is)^if\\s+a\\s+Forward\\s+you\\s+controlled\\s+formed\\s+a\\s+party\\s+this\\s+turn,\\s+(?<effect>.+)$"
    );

    /**
     * Matches "if you control N or less/fewer [Forwards/Backups/Monsters/Characters], [effect]."
     * Groups: {@code max} — the maximum count; {@code type} — card type; {@code effect} — inner effect.
     */
    private static final Pattern IF_CONTROL_AT_MOST = Pattern.compile(
        "(?is)^if\\s+you\\s+control\\s+(?<max>\\d+)\\s+or\\s+(?:less|fewer)\\s+" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?<type>Forwards?|Backups?|Monsters?|Characters?),\\s+(?<effect>.+)$"
    );

    /**
     * Match condition if you control a Category, of any type. Also takes into account multiple spaces (e.g. "Crystal Hunt" category)
     * Also checks if it requires it to be a card other than itself
     * Groups: <category>, <type, <other>
     */
    private static final Pattern IF_YOU_CONTROL_CATEGORY = Pattern.compile(
            "(?i)If\\s+you\\s+control\\s+a\\s+Category\\s+(?<category>.+?)\\s+(?<type>Forward|Monster|Backup|Character)(?:\\s+other\\s+than\\s+(?<name>\\w+))?"
    );

    /**
     * Matches "If all the [Type] you control have [Element] Element, [effect]."
     * Groups: {@code type}, {@code element}, {@code effect}.
     */
    private static final Pattern IF_ALL_HAVE_ELEMENT_GATE = Pattern.compile(
        "(?is)^if\\s+all\\s+the\\s+(?<type>Forwards?|Backups?|Characters?|Monsters?)\\s+" +
        "you\\s+control\\s+have\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)(?:\\s+Element)?,\\s+" +
        "(?<effect>.+)$"
    );

    /** Matches a leading "If you [do not] control &lt;condition&gt;, &lt;effect&gt;" gate. */
    private static final Pattern CONTROL_CONDITION_GATE = Pattern.compile(
        "(?is)^if\\s+you\\s+(?<neg>do\\s+not\\s+|don't\\s+)?control\\s+(?<cond>.+?),\\s+(?<effect>.+)$"
    );

    /**
     * Matches "if you control [cond] other than [name], [effect]."
     * Used for abilities like "if you control a Category FFCC Forward other than Bel Dat, draw 1 card."
     * Tried before {@link #CONTROL_CONDITION_GATE} because it is more specific.
     */
    private static final Pattern IF_CONTROL_COND_OTHER_THAN = Pattern.compile(
        "(?is)^if\\s+you\\s+(?<neg>don't\\s+|do\\s+not\\s+)?control\\s+(?<cond>.+?)\\s+other\\s+than\\s+(?<exclude>[^,]+?),\\s+(?<effect>.+)$"
    );

    /** Matches "If your opponent controls a(n) [cond] [type], [effect]" — e.g. "a damaged Forward". */
    private static final Pattern OPP_CONTROL_CARD_GATE = Pattern.compile(
        "(?is)^if\\s+your\\s+opponent\\s+controls\\s+a(?:n)?\\s+" +
        "(?<cond>damaged|dull|active|attacking|blocking)\\s+" +
        "(?<type>Forwards?|Monsters?|Backups?|Characters?),\\s+" +
        "(?<effect>.+)$"
    );

    /** Matches "If your opponent controls N or more [cond] [type], [effect]." */
    private static final Pattern IF_OPP_CONTROLS_N_OR_MORE_COND_TYPE_GATE = Pattern.compile(
        "(?i)^[Ii]f\\s+your\\s+opponent\\s+controls\\s+(?<count>\\d+)\\s+or\\s+more\\s+" +
        "(?<cond>dull|damaged|active|attacking|blocking)\\s+" +
        "(?<type>Forwards?|Monsters?|Backups?|Characters?),\\s+" +
        "(?<effect>.+)$"
    );

    /** Matches "if there are N or more different Elements among [type] you control, [effect]." */
    private static final Pattern IF_N_DIFF_ELEMENTS_AMONG = Pattern.compile(
        "(?is)^if\\s+there\\s+are\\s+(?<min>\\d+)\\s+or\\s+more\\s+different\\s+Elements?\\s+among\\s+" +
        "(?<type>Forwards?|Backups?|Characters?|Monsters?)\\s+you\\s+control[,.]?\\s+(?<effect>.+)$"
    );

    /** Matches "If you have cast N or more cards this turn, &lt;effect&gt;". */
    private static final Pattern IF_CAST_AT_LEAST = Pattern.compile(
        "(?is)^if\\s+you\\s+have\\s+cast\\s+(?<min>\\d+)\\s+or\\s+more\\s+cards?\\s+this\\s+turn,\\s+(?<effect>.+)$"
    );

    /**
     * Matches the two-branch element conditional on a cost discard:
     * "If the discarded card is of Elem1 Element, [eff1]. If the discarded card is of Elem2 Element, [eff2]."
     * Groups: {@code elem1}, {@code eff1}, {@code elem2}, {@code eff2}.
     */
    private static final Pattern DISCARD_CONDITIONAL_ELEMENT = Pattern.compile(
        "(?i)If\\s+the\\s+discarded\\s+card\\s+is\\s+of\\s+(?<elem1>\\w+)\\s+Element\\s*,\\s*" +
        "(?<eff1>.+?)\\s+" +
        "If\\s+the\\s+discarded\\s+card\\s+is\\s+of\\s+(?<elem2>\\w+)\\s+Element\\s*,\\s*" +
        "(?<eff2>.+)$",
        Pattern.DOTALL
    );

    private static Consumer<GameContext> tryParseDiscardConditionalElement(String text, CardData source, int xValue) {
        Matcher m = DISCARD_CONDITIONAL_ELEMENT.matcher(text.trim());
        if (!m.find()) return null;
        String elem1 = m.group("elem1").trim();
        String eff1  = m.group("eff1").trim();
        String elem2 = m.group("elem2").trim();
        String eff2  = m.group("eff2").trim();
        Consumer<GameContext> effect1 = parse(eff1, source, xValue);
        Consumer<GameContext> effect2 = parse(eff2, source, xValue);
        if (effect1 == null && effect2 == null) return null;
        final Consumer<GameContext> e1 = effect1;
        final Consumer<GameContext> e2 = effect2;
        return ctx -> {
            String discardedElem = ctx.lastDiscardedCostCardElement();
            if (discardedElem == null) {
                ctx.logEntry("Discard conditional: no cost card recorded");
                return;
            }
            if (discardedElem.equalsIgnoreCase(elem1)) {
                if (e1 != null) e1.accept(ctx);
                else ctx.logEntry("Discard conditional: " + elem1 + " branch not implemented");
            } else if (discardedElem.equalsIgnoreCase(elem2)) {
                if (e2 != null) e2.accept(ctx);
                else ctx.logEntry("Discard conditional: " + elem2 + " branch not implemented");
            } else {
                ctx.logEntry("Discard conditional: element " + discardedElem + " matches neither branch");
            }
        };
    }

    private static Consumer<GameContext> tryParseIfOwnForwardFormedParty(String text, CardData source, int xValue) {
        Matcher m = IF_OWN_FORWARD_FORMED_PARTY.matcher(text.trim());
        if (!m.matches()) return null;
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            if (ctx.ownForwardFormedPartyThisTurn()) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: no party formed this turn — skipped");
            }
        };
    }

    /**
     * Parses "If all the [Type] you control have [Element] Element, [effect]." —
     * resolves the inner effect only when every controlled card of that type has the element.
     */
    private static Consumer<GameContext> tryParseIfAllHaveElement(String text, CardData source, int xValue) {
        Matcher m = IF_ALL_HAVE_ELEMENT_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        String typeRaw  = m.group("type").trim();
        String element  = m.group("element").trim();
        String normType = typeRaw.replaceAll("(?i)s$", "");
        normType = Character.toUpperCase(normType.charAt(0)) + normType.substring(1).toLowerCase();
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        ControlCondition cc = ControlCondition.forAllHave(normType, element, null);
        String logType = typeRaw;
        String logElem = element;
        return ctx -> {
            if (ctx.controlConditionMet(cc)) {
                ctx.logEntry("Effect: all " + logType + " have " + logElem + " Element — condition met");
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: not all " + logType + " have " + logElem + " Element — skipped");
            }
        };
    }

    /** Parses "if there are N or more different Elements among [type] you control, [effect]." */
    private static Consumer<GameContext> tryParseIfNDiffElements(String text, CardData source, int xValue) {
        Matcher m = IF_N_DIFF_ELEMENTS_AMONG.matcher(text.trim());
        if (!m.matches()) return null;
        int    min     = Integer.parseInt(m.group("min"));
        String typeRaw = m.group("type").trim();
        String typeLow = typeRaw.toLowerCase(java.util.Locale.ROOT);
        boolean inclFwd = typeLow.startsWith("forward") || typeLow.startsWith("character");
        boolean inclBkp = typeLow.startsWith("backup")  || typeLow.startsWith("character");
        boolean inclMon = typeLow.startsWith("monster")  || typeLow.startsWith("character");
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            int distinct = ctx.selfDistinctElementCount(inclFwd, inclBkp, inclMon);
            if (distinct >= min) {
                ctx.logEntry("Effect: " + distinct + " distinct element(s) among " + typeRaw + "s — condition met");
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: only " + distinct + " distinct element(s) among " + typeRaw + "s (need " + min + ") — skipped");
            }
        };
    }

    /**
     * Parses "If you [do not] control X, Y" — resolves Y only when the control condition is
     * (un)met at resolution time. Returns {@code null} when the condition or inner effect cannot
     * be parsed so the text falls through to the regular matchers (preserving prior behaviour).
     */
    private static Consumer<GameContext> tryParseIfControlCondOtherThan(String text, CardData source, int xValue) {
        Matcher m = IF_CONTROL_COND_OTHER_THAN.matcher(text.trim());
        if (!m.matches()) return null;
        ControlCondition cc = CardData.parseControlCondition(m.group("cond").trim());
        if (cc == null) return null;
        boolean negated    = m.group("neg") != null;
        String excludeName = m.group("exclude").trim();
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            boolean met = ctx.controlConditionMetExcluding(cc, excludeName);
            if (met != negated) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: control condition (excl. " + excludeName + ") not met — skipped");
            }
        };
    }

    private static Consumer<GameContext> tryParseControlConditionGate(String text, CardData source, int xValue) {
        Matcher m = CONTROL_CONDITION_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        ControlCondition cc = CardData.parseControlCondition(m.group("cond").trim());
        if (cc == null) return null;
        boolean negated = m.group("neg") != null;
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            if (ctx.controlConditionMet(cc) != negated) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: control condition not met — skipped");
            }
        };
    }

    private static Consumer<GameContext> tryParseOpponentControlsCardGate(String text, CardData source, int xValue) {
        Matcher m = OPP_CONTROL_CARD_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        String cond    = m.group("cond").toLowerCase();
        String typeRaw = m.group("type");
        String normType = Character.toUpperCase(typeRaw.charAt(0))
                + typeRaw.substring(1).toLowerCase().replaceAll("s$", "");
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            if (ctx.opponentControlsCard(normType, cond)) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: opponent has no " + cond + " " + normType + " — skipped");
            }
        };
    }

    private static Consumer<GameContext> tryParseIfOppControlsNOrMoreCondTypeGate(String text, CardData source, int xValue) {
        Matcher m = IF_OPP_CONTROLS_N_OR_MORE_COND_TYPE_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        int    threshold = Integer.parseInt(m.group("count"));
        String cond      = m.group("cond").toLowerCase();
        String typeRaw   = m.group("type");
        String normType  = Character.toUpperCase(typeRaw.charAt(0))
                + typeRaw.substring(1).toLowerCase().replaceAll("s$", "");
        boolean inclFwds = normType.equals("Forward")   || normType.equals("Character");
        boolean inclBkps = normType.equals("Backup")    || normType.equals("Character");
        boolean inclMons = normType.equals("Monster")   || normType.equals("Character");
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            int cnt = ctx.countOppFieldCardsWithCondition(inclFwds, inclBkps, inclMons, cond);
            if (cnt >= threshold) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: " + threshold + "+ " + cond + " " + normType + "(s) required, opponent has " + cnt + " — skipped");
            }
        };
    }

    private static Consumer<GameContext> tryParseIfControlAtMost(String text, CardData source, int xValue) {
        Matcher m = IF_CONTROL_AT_MOST.matcher(text.trim());
        if (!m.matches()) return null;
        int max          = Integer.parseInt(m.group("max"));
        String category  = m.group("category");
        String type      = m.group("type").trim();
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        String label = (category != null ? "Category " + category + " " : "") + type;
        return ctx -> {
            int count = category != null
                    ? ctx.ownFieldCountByCategory(category, type)
                    : ctx.ownFieldCount(type);
            if (count <= max) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: control " + count + " " + label + " (max " + max + ") — skipped");
            }
        };
    }

    private static Consumer<GameContext> tryParseIfCastAtLeast(String text, CardData source, int xValue) {
        Matcher m = IF_CAST_AT_LEAST.matcher(text.trim());
        if (!m.matches()) return null;
        int min = Integer.parseInt(m.group("min"));
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            int cast = ctx.selfCardsCastThisTurn();
            if (cast >= min) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: only cast " + cast + " card(s) this turn (need " + min + ") — skipped");
            }
        };
    }

    private static Consumer<GameContext> tryParseDrawThenPlaceHandToBottom(String text) {
        Matcher m = DRAW_THEN_PLACE_HAND_TO_BOTTOM.matcher(text);
        if (!m.find()) return null;
        int drawCount  = Integer.parseInt(m.group(1));
        int placeCount = Integer.parseInt(m.group(2));
        return ctx -> {
            ctx.logEntry("Effect: Draw " + drawCount + " card(s), then place " + placeCount + " card(s) at bottom of deck");
            ctx.drawCards(drawCount);
            ctx.placeFromHandToBottomOfDeck(placeCount);
        };
    }

    private static Consumer<GameContext> tryParsePayCpWhenDoSo(String text, CardData source) {
        Matcher m = PAY_CP_WHEN_DO_SO.matcher(text);
        if (!m.find()) return null;
        String costDesc    = m.group("cost").trim();
        String followupText = m.group("followup").trim();
        Consumer<GameContext> followup = parse(followupText, source);
        if (followup == null) return null;
        return ctx -> {
            ctx.logEntry("Effect: Pay " + costDesc + " CP, then: " + followupText);
            followup.accept(ctx);
        };
    }

    private static Consumer<GameContext> tryParseDrawDiscardRetriggerIfCardName(String text, CardData source) {
        Matcher m = DRAW_DISCARD_RETRIGGER_IF_CARD_NAME.matcher(text);
        if (!m.find()) return null;
        int drawCount    = Integer.parseInt(m.group("draw"));
        int discardCount = Integer.parseInt(m.group("discard"));
        String cardName  = m.group("name").trim();
        return ctx -> {
            ctx.logEntry("Effect: Draw " + drawCount + ", then discard " + discardCount);
            ctx.drawCards(drawCount);
            ctx.selfDiscard(discardCount);
            if (cardName.equalsIgnoreCase(ctx.lastDiscardedCardName())) {
                ctx.logEntry("Effect: Discarded Card Name " + cardName + " — triggering auto-ability again");
                ctx.retriggerAutoAbility(source, "beginning of attack phase");
            }
        };
    }

    private static Consumer<GameContext> tryParseDrawCards(String text) {
        Matcher m = DRAW_CARDS.matcher(text);
        if (!m.find()) return null;
        int drawCount = Integer.parseInt(m.group(1));
        String discardStr = m.group(2);
        if (discardStr == null) {
            return ctx -> {
                ctx.logEntry("Effect: Draw " + drawCount + " card(s)");
                ctx.drawCards(drawCount);
            };
        }
        int discardCount = Integer.parseInt(discardStr);
        return ctx -> {
            ctx.logEntry("Effect: Draw " + drawCount + ", then discard " + discardCount);
            ctx.drawCards(drawCount);
            ctx.selfDiscard(discardCount);
        };
    }

    /** Parses "Discard N card(s), then draw M card(s)" as a standalone effect. */
    private static Consumer<GameContext> tryParseDiscardThenDraw(String text) {
        Matcher m = DISCARD_THEN_DRAW.matcher(text);
        if (!m.find()) return null;
        int discardCount = Integer.parseInt(m.group(1));
        int drawCount    = Integer.parseInt(m.group(2));
        return ctx -> {
            ctx.logEntry("Effect: Discard " + discardCount + ", then draw " + drawCount);
            ctx.selfDiscard(discardCount);
            ctx.drawCards(drawCount);
        };
    }

    /** Parses "Discard your hand. Then, draw N card(s)" as a standalone effect. */
    private static Consumer<GameContext> tryParseDiscardHandThenDraw(String text) {
        Matcher m = DISCARD_HAND_THEN_DRAW.matcher(text);
        if (!m.find()) return null;
        int drawCount = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Discard hand, then draw " + drawCount);
            ctx.selfDiscardEntireHand();
            ctx.drawCards(drawCount);
        };
    }

    /** Parses "Discard your hand." as a standalone effect. */
    private static Consumer<GameContext> tryParseDiscardHand(String text) {
        if (!DISCARD_HAND.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Discard hand");
            ctx.selfDiscardEntireHand();
        };
    }

    /**
     * Parses "discard 1 &lt;Type&gt;." — player must discard one card of that type from hand.
     * Fizzles (marks no progress) when no eligible card is available.
     * The "you may" qualifier is handled at the AutoAbility layer before this is reached.
     */
    private static Consumer<GameContext> tryParseYouMayDiscardType(String text) {
        Matcher m = DISCARD_TYPE.matcher(text);
        if (!m.find()) return null;
        String type = m.group("type");
        return ctx -> {
            ctx.logEntry("Effect: Discard 1 " + type);
            ctx.selfDiscardByType(type);
        };
    }

    /** Parses "Discard 1 Job [X] from your hand." — player must discard one card of that job. */
    private static Consumer<GameContext> tryParseDiscardJobFromHand(String text) {
        Matcher m = DISCARD_JOB_FROM_HAND.matcher(text.trim());
        if (!m.matches()) return null;
        String job = m.group("job").trim();
        return ctx -> {
            ctx.logEntry("Effect: Discard 1 Job " + job + " from hand");
            ctx.selfDiscardByJob(job);
        };
    }

    /** Parses "You may discard 1 &lt;element&gt; card" — player may optionally discard a card matching the element. */
    private static Consumer<GameContext> tryParseDiscardElementFromHand(String text) {
        Matcher m = DISCARD_ELEMENT_FROM_HAND.matcher(text.trim());
        if (!m.matches()) return null;
        String element = m.group("element");
        return ctx -> {
            ctx.logEntry("Effect: May discard 1 " + element + " card from hand");
            ctx.selfDiscardByElement(element);
        };
    }

    /** Parses "You may reveal 1 [Element] card from your hand." */
    private static Consumer<GameContext> tryParseMayRevealElementFromHand(String text) {
        Matcher m = YOU_MAY_REVEAL_ELEMENT_FROM_HAND.matcher(text.trim());
        if (!m.matches()) return null;
        String element = m.group("element");
        return ctx -> {
            ctx.logEntry("Effect: May reveal 1 " + element + " card from hand");
            ctx.mayRevealCardByElementFromHand(element);
        };
    }

    /**
     * Returns the card type (e.g. "Summon") when the effect text begins with a
     * "discard 1 &lt;Type&gt;" clause, or {@code null} if no such clause is present.
     * Used by {@code executeAutoAbility} to skip offering the "you may?" dialog
     * when the player has no eligible cards in hand.
     */
    public static String youMayDiscardType(String effectText) {
        Matcher m = DISCARD_TYPE.matcher(effectText);
        if (!m.find()) return null;
        return m.group("type");
    }

    private static final Pattern DISCARD_N_CARDS = Pattern.compile(
        "(?i)^discard\\s+(?<count>\\d+)\\s+cards?(?:\\s+from\\s+your\\s+hand)?[.!]?$"
    );

    /** Parses "Discard N cards." as a standalone effect. */
    private static Consumer<GameContext> tryParseDiscardNCards(String text) {
        Matcher m = DISCARD_N_CARDS.matcher(text.trim());
        if (!m.matches()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Discard " + count + " card(s)");
            ctx.selfDiscard(count);
        };
    }

    /** Matches "discard N cards" at the start of an effect text (may have more text after). */
    private static final Pattern DISCARD_N_CARDS_PREFIX = Pattern.compile(
        "(?i)^discard\\s+(?<count>\\d+)\\s+cards?[.!]?(?:\\s|$)"
    );

    /**
     * Returns the discard count when the effect text begins with "discard N cards",
     * or -1 if it doesn't match.
     * Used by {@code executeAutoAbility} to skip offering the "you may?" dialog
     * when the player has fewer cards in hand than required.
     */
    public static int youMayDiscardCount(String effectText) {
        Matcher m = DISCARD_N_CARDS_PREFIX.matcher(effectText.trim());
        if (!m.find()) return -1;
        return Integer.parseInt(m.group("count"));
    }

    /** Parses "&lt;name&gt; deals your opponent N point(s) of damage." — flips from opponent's deck to their damage zone. */
    private static Consumer<GameContext> tryParseDealPlayerDamageToOpponent(String text) {
        Matcher m = DEAL_PLAYER_DAMAGE_TO_OPPONENT.matcher(text);
        if (!m.matches()) return null;
        int amount = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: Deal " + amount + " damage to opponent");
            ctx.dealDamageToOpponent(amount);
        };
    }

    /** Parses "&lt;name&gt; deals you N point(s) of damage." — flips from ability user's deck to their damage zone. */
    private static Consumer<GameContext> tryParseDealPlayerDamageToSelf(String text) {
        Matcher m = DEAL_PLAYER_DAMAGE_TO_SELF.matcher(text);
        if (!m.matches()) return null;
        int amount = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: Deal " + amount + " damage to self");
            ctx.dealDamageToSelf(amount);
        };
    }

    /** Parses "Your opponent discards N card(s) [from his/her/their hand]" as a standalone effect. */
    private static Consumer<GameContext> tryParseNameCardTypeOpponentDiscardDrawIfMatch(String text) {
        if (!NAME_CARD_TYPE_OPP_DISCARD_DRAW_IF_MATCH.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Name 1 card type, opponent discards 1, draw 1 if type matches");
            ctx.nameCardTypeOpponentDiscardDrawIfMatch();
        };
    }

    private static Consumer<GameContext> tryParseOpponentDiscard(String text) {
        Matcher m = OPPONENT_DISCARD.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Opponent discards " + count + " card(s)");
            ctx.forceOpponentDiscard(count);
        };
    }

    /** Parses "Each player discards N card(s) [from his/her/their hand]" — both players discard. */
    private static Consumer<GameContext> tryParseEachPlayerDiscard(String text) {
        String stripped = stripRestrictionSentences(text);
        if (stripped.isEmpty()) return null;

        // Conditional per-player form: "each player who doesn't control N or more Forwards discards M card(s)"
        Matcher condFwdM = EACH_PLAYER_WHO_DOESNT_CONTROL_FORWARDS_DISCARD.matcher(stripped);
        if (condFwdM.matches()) {
            int min   = Integer.parseInt(condFwdM.group("min"));
            int count = Integer.parseInt(condFwdM.group("count"));
            return ctx -> {
                if (ctx.selfForwardCount() < min) {
                    ctx.logEntry("Effect: Self discards " + count + " (controls fewer than " + min + " Forwards)");
                    ctx.selfDiscard(count);
                }
                if (ctx.opponentForwardCount() < min) {
                    ctx.logEntry("Effect: Opponent discards " + count + " (controls fewer than " + min + " Forwards)");
                    ctx.forceOpponentDiscard(count);
                }
            };
        }

        // Compound form: "each player discards N. If you control [Card Name (X)], opponent discards M more."
        Matcher compM = EACH_PLAYER_DISCARD_WITH_CONDITIONAL.matcher(stripped);
        if (compM.matches()) {
            int count        = Integer.parseInt(compM.group("count"));
            String cardName  = compM.group("bracketname") != null
                               ? compM.group("bracketname") : compM.group("plainname");
            ControlCondition cc = new ControlCondition(
                    List.of(cardName), 0, false, null, null, null, null, 0, List.of());
            int extra = Integer.parseInt(compM.group("extra"));
            return ctx -> {
                ctx.logEntry("Effect: Each player discards " + count + " card(s)");
                ctx.selfDiscard(count);
                ctx.forceOpponentDiscard(count);
                if (ctx.controlConditionMet(cc)) {
                    ctx.logEntry("Effect: Opponent discards " + extra + " more (controlling " + cardName + ")");
                    ctx.forceOpponentDiscard(extra);
                }
            };
        }

        // Simple form: "each player discards N card(s) [from his/her/their hand]"
        Matcher m = EACH_PLAYER_DISCARD.matcher(stripped);
        if (!m.matches()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Each player discards " + count + " card(s)");
            ctx.selfDiscard(count);
            ctx.forceOpponentDiscard(count);
        };
    }

    /** Parses "Each player draws N card(s)." — both players draw. */
    private static Consumer<GameContext> tryParseEachPlayerDraw(String text) {
        Matcher m = EACH_PLAYER_DRAW.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Each player draws " + count + " card(s)");
            ctx.drawCards(count);
            ctx.drawCardsForOpponent(count);
        };
    }

    /** Parses "Each player selects N card(s) from their Break Zone and adds it/them to their hand." */
    private static Consumer<GameContext> tryParseEachPlayerSalvageFromBreakZone(String text) {
        Matcher m = EACH_PLAYER_SALVAGE_FROM_BREAK_ZONE.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Each player salvages " + count + " card(s) from their Break Zone to hand");
            ctx.eachPlayerSalvageFromBreakZone(count);
        };
    }

    /** Parses "select N [type] in/from your Break Zone and add it to your hand." */
    private static Consumer<GameContext> tryParseSelectCharacterFromBzToHand(String text) {
        Matcher m = SELECT_CHARACTER_FROM_BZ_TO_HAND.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        String tl = m.group("type").toLowerCase(java.util.Locale.ROOT);
        boolean fwds = tl.contains("forward")   || tl.contains("character");
        boolean bkps = tl.contains("backup")    || tl.contains("character");
        boolean mons = tl.contains("monster")   || tl.contains("character");
        return ctx -> {
            ctx.logEntry("Effect: Select " + count + " " + m.group("type") + "(s) from own Break Zone → hand");
            ctx.salvageCharacterFromOwnBreakZone(count, fwds, bkps, mons);
        };
    }

    /** Parses "Each player selects 1 Forward they control. Deal them N damage." */
    private static Consumer<GameContext> tryParseEachPlayerSelectForwardDamage(String text) {
        Matcher m = EACH_PLAYER_SELECT_FORWARD_DAMAGE.matcher(text);
        if (!m.find()) return null;
        int amount = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: Each player selects 1 Forward they control. Deal them " + amount + " damage");
            ctx.eachPlayerSelectForwardAndDamage(amount);
        };
    }

    /** Parses "select 1 [type] of cost N or less other than [name] you control. Put it into the Break Zone." */
    private static Consumer<GameContext> tryParseSelectCharCostLeExclToBz(String text) {
        Matcher m = SELECT_1_CHAR_COST_LE_EXCL_TO_BZ.matcher(text.trim());
        if (!m.matches()) return null;
        String type        = m.group("type");
        int    costVal     = Integer.parseInt(m.group("costval"));
        String excludeName = m.group("excludename").trim();
        boolean inclFwd = type.matches("(?i)Forward|Character");
        boolean inclBkp = type.matches("(?i)Backup|Character");
        boolean inclMon = type.matches("(?i)Monster|Character");
        return ctx -> {
            ctx.logEntry("Effect: select 1 " + type + " of cost ≤ " + costVal + " other than " + excludeName + " you control → Break Zone");
            List<ForwardTarget> targets = ctx.selectCharacters(1, false, false, true,
                    null, null, costVal, "less", -1, null,
                    inclFwd, inclBkp, inclMon,
                    null, null, null, excludeName, false, null, false);
            for (ForwardTarget t : targets) ctx.breakTarget(t);
        };
    }

    /** Parses "select 1 [Forward|Backup|Monster|Character] you control. Put it into the Break Zone." */
    private static Consumer<GameContext> tryParseSelectControlledCharacterToBz(String text) {
        Matcher m = SELECT_1_CHARACTER_YOU_CONTROL_TO_BZ.matcher(text.trim());
        if (!m.matches()) return null;
        String type    = m.group("type");
        boolean inclFwd = type.matches("(?i)Forward|Character");
        boolean inclBkp = type.matches("(?i)Backup|Character");
        boolean inclMon = type.matches("(?i)Monster|Character");
        return ctx -> {
            ctx.logEntry("Effect: select 1 " + type + " you control → Break Zone");
            ctx.selectControlledTypeAndBreak(inclFwd, inclBkp, inclMon);
        };
    }

    /** Parses "Both players select 1 Forward they control and put it into the Break Zone." */
    private static Consumer<GameContext> tryParseBothPlayersSelectForwardToBreakZone(String text) {
        Matcher m = BOTH_PLAYERS_SELECT_FORWARD_TO_BREAK_ZONE.matcher(text);
        if (!m.find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Both players select 1 Forward they control and put it into the Break Zone");
            ctx.eachPlayerSelectForwardAndBreak();
        };
    }

    /** Parses "Each player selects up to N Forwards or Monsters they control (select as many as possible). Put them into the Break Zone." */
    private static Consumer<GameContext> tryParseEachPlayerSelectUpToNToBreakZone(String text) {
        Matcher m = EACH_PLAYER_SELECT_UP_TO_N_TO_BREAK_ZONE.matcher(text);
        if (!m.find()) return null;
        int    count    = Integer.parseInt(m.group("count"));
        String tgtLower = m.group("targets").toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");
        return ctx -> {
            ctx.logEntry("Effect: Each player selects up to " + count + " Forwards/Monsters and puts them in Break Zone");
            ctx.eachPlayerSelectUpToNAndBreak(count, inclForwards, inclMonsters);
        };
    }

    /** Parses "Your opponent randomly removes N card(s) in their hand from the game." */
    private static Consumer<GameContext> tryParseOpponentRandomHandRfp(String text) {
        Matcher m = OPPONENT_RANDOM_HAND_RFP.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Opponent randomly removes " + count + " hand card(s) from the game");
            ctx.forceOpponentRandomHandRfp(count);
        };
    }

    /** Parses "Your opponent randomly places N card(s) from their hand at the bottom of their deck." */
    private static Consumer<GameContext> tryParseOpponentRandomHandToBottomDeck(String text) {
        Matcher m = OPPONENT_RANDOM_HAND_TO_BOTTOM_DECK.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Opponent randomly places " + count + " hand card(s) at bottom of their deck");
            ctx.forceOpponentRandomHandToBottomOfDeck(count);
        };
    }

    /**
     * Parses "Your opponent reveals their hand. Select N card(s) in their hand.
     * Your opponent removes it from the game."
     */
    private static Consumer<GameContext> tryParseRevealSelectHandRfp(String text) {
        Matcher m = REVEAL_SELECT_HAND_RFP.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Opponent reveals hand — select " + count + " to remove from game");
            ctx.selectFromOpponentHandAndRfp(count);
        };
    }

    /** Parses "Opponent reveals hand. You may select 1 → remove from game, opponent draws 1." */
    private static Consumer<GameContext> tryParseRevealHandOptPickRfpOppDraw(String text) {
        if (!REVEAL_HAND_OPT_PICK_RFP_OPP_DRAW.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Opponent reveals hand — optionally select 1 to RFP, opponent draws 1");
            ctx.revealHandOptPickRfpOpponentDraws();
        };
    }

    /**
     * Parses "Your opponent removes N card(s) in their hand from the game."
     * (opponent chooses which cards, not random).
     */
    private static Consumer<GameContext> tryParseOpponentHandRfp(String text) {
        Matcher m = OPPONENT_HAND_RFP.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Opponent removes " + count + " hand card(s) from the game");
            ctx.forceOpponentHandRfp(count);
        };
    }

    /** Parses "Return [name] to its owner's hand." or "Return [name] to your hand." */
    private static Consumer<GameContext> tryParseReturnNamedToHand(String text) {
        Matcher m = RETURN_NAMED_TO_OWNERS_HAND.matcher(text);
        if (m.find()) {
            String named = m.group("named").trim();
            return ctx -> {
                ctx.logEntry("Effect: Return " + named + " to its owner's hand");
                ctx.returnNamedCardToOwnersHand(named);
            };
        }
        m = RETURN_NAMED_TO_YOUR_HAND_STANDALONE.matcher(text);
        if (m.find()) {
            String named = m.group("named").trim();
            return ctx -> {
                ctx.logEntry("Effect: Return " + named + " to your hand");
                ctx.returnNamedCardToYourHand(named);
            };
        }
        // Also handle "Add [name] to your hand" — used by break-zone-origin abilities.
        m = ADD_NAMED_TO_YOUR_HAND.matcher(text);
        if (m.find()) {
            String named = m.group("named").trim();
            return ctx -> {
                ctx.logEntry("Effect: Add " + named + " to your hand");
                ctx.returnNamedCardToYourHand(named);
            };
        }
        return null;
    }

    /** Parses "Remove all the cards in your opponent's Break Zone from the game." */
    private static Consumer<GameContext> tryParseRemoveAllOppBzFromGame(String text) {
        if (!REMOVE_ALL_OPP_BZ_FROM_GAME.matcher(text.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Remove all cards in opponent's Break Zone from the game");
            ctx.removeAllOpponentBzFromGame();
        };
    }

    /** Parses "Remove [CardName] from the game." — removes a named card from the field. */
    private static Consumer<GameContext> tryParseRemoveNamedFromGame(String text, CardData source) {
        Matcher m = REMOVE_NAMED_FROM_GAME.matcher(text);
        if (!m.find()) return null;
        String named = m.group("named").trim();
        return ctx -> {
            ctx.logEntry("Effect: Remove " + named + " from the game");
            ctx.removeNamedCardFromGame(named);
        };
    }

    /**
     * Parses "You may remove [CardName] from the game." — shows a yes/no prompt; if accepted,
     * calls {@link GameContext#removeNamedCardFromGame}; if declined, calls
     * {@link GameContext#markEffectFizzled()} so any "If you do so" followup is suppressed.
     */
    private static Consumer<GameContext> tryParseYouMayRemoveNamedFromGame(String text, CardData source) {
        if (source == null) return null;
        Matcher m = YOU_MAY_REMOVE_NAMED_FROM_GAME.matcher(text.trim());
        if (!m.matches()) return null;
        String name = m.group("name").trim();
        if (!name.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            if (!ctx.promptYouMay("Remove " + name + " from the game?")) {
                ctx.markEffectFizzled();
                return;
            }
            ctx.logEntry("Effect: Remove " + name + " from the game");
            ctx.removeNamedCardFromGame(name);
        };
    }

    /**
     * Parses "At the end of your opponent's turn, play [CardName] onto the field." — schedules
     * {@link GameContext#playNamedFromRfpOntoField} to fire at the end of the opponent's next turn.
     */
    private static Consumer<GameContext> tryParseEndOfOppTurnPlayNamedOntoField(String text) {
        Matcher m = AT_END_OF_OPP_TURN_PLAY_NAMED_ONTO_FIELD.matcher(text.trim());
        if (!m.matches()) return null;
        String name = m.group("name").trim();
        return ctx -> ctx.addEndOfOpponentTurnEffect(ctx2 -> ctx2.playNamedFromRfpOntoField(name));
    }

    /** Parses "Break [CardName]." when CardName is the source card — breaks the source forward/monster. */
    private static Consumer<GameContext> tryParseBreakSourceCard(String text, CardData source) {
        Matcher m = BREAK_SOURCE_CARD.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry("Effect: Break " + source.name());
            ctx.breakSourceCard(source);
        };
    }

    private static Consumer<GameContext> tryParseBreakBlockingForward(String text) {
        if (!BREAK_BLOCKING_FORWARD.matcher(text.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Break the blocking Forward");
            ctx.breakBlockingForward();
        };
    }

    private static Consumer<GameContext> tryParseBreakForwardThatBlocksCard(String text) {
        Matcher m = BREAK_FORWARD_THAT_BLOCKS_CARD.matcher(text.trim());
        if (!m.matches()) return null;
        String attackerName = m.group("name").trim();
        return ctx -> {
            ctx.logEntry("Effect: Break the Forward that blocks " + attackerName);
            ctx.breakForwardBlockingAttacker(attackerName);
        };
    }

    private static Consumer<GameContext> tryParsePutSourceIntoBreakZone(String text, CardData source) {
        Matcher m = PUT_SOURCE_INTO_BREAK_ZONE.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry("Effect: Break " + source.name());
            ctx.breakSourceCard(source);
        };
    }

    private static Consumer<GameContext> tryParseYouMayPutSelfToBZWhenDoSo(String text, CardData source) {
        if (source == null) return null;
        Matcher m = YOU_MAY_PUT_SELF_TO_BZ_WHEN_DO_SO.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        String followupText = m.group("effect").trim();
        Consumer<GameContext> followup = parse(followupText, source);
        if (followup == null) return null;
        return ctx -> ctx.mayBreakSourceWhenDoSo(source, followup);
    }

    static Consumer<GameContext> tryParseIfOppNoForwardsPutToBreakZone(String text, CardData source) {
        if (source == null) return null;
        Matcher m = IF_OPP_NO_FORWARDS_PUT_TO_BREAK_ZONE.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            if (ctx.opponentForwardCount() > 0) return;
            ctx.logEntry("Effect: opponent controls no Forwards — Break " + source.name());
            ctx.breakSourceCard(source);
        };
    }

    static Consumer<GameContext> tryParseIfEitherPlayerNoForwardsPutSourceToBz(String text, CardData source) {
        if (source == null) return null;
        Matcher m = IF_EITHER_PLAYER_NO_FORWARDS_PUT_SOURCE_TO_BZ.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            if (ctx.selfForwardCount() > 0 && ctx.opponentForwardCount() > 0) return;
            ctx.logEntry("Effect: a player controls no Forwards — Break " + source.name());
            ctx.breakSourceCard(source);
        };
    }

    private static Consumer<GameContext> tryParsePutSourceToBottomOfDeck(String text, CardData source) {
        if (source == null) return null;
        Matcher m = PUT_SOURCE_TO_BOTTOM_OF_DECK.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry("Effect: " + source.name() + " → bottom of its owner's deck");
            ctx.putSourceToBottomOfDeck(source);
        };
    }

    private static Consumer<GameContext> tryParseShuffleThenRevealPlayNamedRestBottom(String text, CardData source) {
        Matcher m = SHUFFLE_THEN_REVEAL_PLAY_NAMED_REST_BOTTOM.matcher(text.trim());
        if (!m.matches()) return null;
        int n           = Integer.parseInt(m.group("n"));
        String cardName = m.group("cardname").trim();
        if (source != null && !cardName.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.shuffleDeck();
            ctx.revealTopNPlayNamedOntoFieldRestBottom(n, cardName);
        };
    }

    private static Consumer<GameContext> tryParseRevealPlayNamedWithMaxCostRestBottom(String text) {
        Matcher m = REVEAL_PLAY_NAMED_MAX_COST_REST_BOTTOM.matcher(text.trim());
        if (!m.matches()) return null;
        int n           = Integer.parseInt(m.group("n"));
        String cardName = m.group("cardname").trim();
        int maxCost     = Integer.parseInt(m.group("maxcost"));
        return ctx -> ctx.revealTopNPlayNamedWithMaxCostOntoFieldRestBottom(n, cardName, maxCost);
    }

    private static Consumer<GameContext> tryParseFlipUntilTypeToHandRestShuffleBottom(String text) {
        if (!FLIP_UNTIL_TYPE_TO_HAND_REST_SHUFFLE_BOTTOM.matcher(text.trim()).matches()) return null;
        return GameContext::flipUntilTypeToHandRestShuffleBottom;
    }

    private static Consumer<GameContext> tryParseRevealPlayTypeOntoFieldRestBottom(String text) {
        String s = stripRestrictionSentences(text);
        Matcher m = REVEAL_PLAY_TYPE_ONTO_FIELD_REST_BOTTOM.matcher((s.isEmpty() ? text : s).trim());
        if (!m.matches()) return null;
        int n      = Integer.parseInt(m.group("n"));
        int max    = Integer.parseInt(m.group("max"));
        String typeRaw  = m.group("type");
        String normType = Character.toUpperCase(typeRaw.charAt(0))
                + typeRaw.substring(1).toLowerCase();
        String category = m.group("category");
        return ctx -> ctx.revealTopNPlayUpToTypeOntoFieldRestBottom(n, max, normType, category);
    }

    private static Consumer<GameContext> tryParseRevealElementCardFromHandIfSoDraw(String text) {
        Matcher m = REVEAL_ELEMENT_CARD_FROM_HAND_IF_SO_DRAW.matcher(text.trim());
        if (!m.matches()) return null;
        String elementRaw = m.group("element");
        String element    = Character.toUpperCase(elementRaw.charAt(0)) + elementRaw.substring(1).toLowerCase();
        int drawCount     = Integer.parseInt(m.group("draw"));
        return ctx -> ctx.revealElementCardFromHandDraw(element, drawCount);
    }

    private static Consumer<GameContext> tryParseRevealPlayElementTypeCostOntoFieldRestBottom(String text) {
        Matcher m = REVEAL_PLAY_ELEMENT_TYPE_COST_ONTO_FIELD_REST_BOTTOM.matcher(text.trim());
        if (!m.matches()) return null;
        int n           = Integer.parseInt(m.group("n"));
        int max         = Integer.parseInt(m.group("max"));
        String elementRaw = m.group("element");
        String element    = elementRaw != null ? Character.toUpperCase(elementRaw.charAt(0)) + elementRaw.substring(1).toLowerCase() : null;
        String typeRaw  = m.group("type");
        String normType = Character.toUpperCase(typeRaw.charAt(0)) + typeRaw.substring(1).toLowerCase();
        int maxCost     = Integer.parseInt(m.group("cost"));
        return ctx -> ctx.revealTopNPlayUpToElementTypeCostOntoFieldRestBottom(n, max, element, normType, maxCost);
    }

    /** Parses "Choose 1 card with EX Burst in your Damage Zone. You may trigger its EX Burst effect." */
    private static Consumer<GameContext> tryParseChooseExBurstFromDamageZone(String text) {
        if (!CHOOSE_EX_BURST_FROM_DAMAGE_ZONE.matcher(text.trim()).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Choose EX Burst from Damage Zone — trigger on stack");
            ctx.triggerExBurstFromDamageZone();
        };
    }

    /**
     * Parses the Leviathan/Larsa/Strago Damage-Zone-swap pattern. Pulls one card from the ability
     * user's Damage Zone to their hand, optionally draws 1 (Leviathan), then returns one card
     * from hand to the Damage Zone with its EX Burst suppressed.
     */
    private static Consumer<GameContext> tryParseDamageZoneSwap(String text) {
        Matcher m = DAMAGE_ZONE_SWAP_PATTERN.matcher(text.trim());
        if (!m.matches()) return null;
        boolean drawBetween = m.group("draw") != null;
        return ctx -> {
            ctx.logEntry("Effect: Damage Zone swap" + (drawBetween ? " (+ draw 1)" : ""));
            ctx.swapDamageZoneCardWithHandCard(drawBetween);
        };
    }

    /** Parses "Your opponent randomly discards N card(s)" as a standalone effect. */
    private static Consumer<GameContext> tryParseOpponentRandomDiscard(String text) {
        Matcher m = OPPONENT_RANDOM_DISCARD.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Opponent randomly discards " + count + " card(s)");
            ctx.forceOpponentRandomDiscard(count);
        };
    }

    /** Parses "Your opponent draws N card(s), then randomly discards M card(s)" as a standalone effect. */
    private static Consumer<GameContext> tryParseOpponentDrawThenRandomDiscard(String text) {
        Matcher m = OPPONENT_DRAW_THEN_RANDOM_DISCARD.matcher(text);
        if (!m.find()) return null;
        int drawCount    = Integer.parseInt(m.group(1));
        int discardCount = Integer.parseInt(m.group(2));
        return ctx -> {
            ctx.logEntry("Effect: Opponent draws " + drawCount + ", then randomly discards " + discardCount);
            ctx.drawCardsForOpponent(drawCount);
            ctx.forceOpponentRandomDiscard(discardCount);
        };
    }

    /** Parses "Your opponent draws N card(s)." as a standalone effect. */
    private static Consumer<GameContext> tryParseOpponentDraw(String text) {
        Matcher m = OPPONENT_DRAW.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Opponent draws " + count);
            ctx.drawCardsForOpponent(count);
        };
    }

    /** No-op recogniser for multi-play grant field abilities handled as static card properties. */
    private static Consumer<GameContext> tryParseMultiPlayGrant(String text) {
        if (CardData.MULTI_LIGHT_DARK_PLAY_PATTERN.matcher(text).matches()) return ctx -> {};
        if (CardData.MULTI_NAME_PLAY_PATTERN.matcher(text).matches())       return ctx -> {};
        return null;
    }

    // -------------------------------------------------------------------------
    // Delayed ("at the end of this turn") and recurring end-of-turn field parsers
    // -------------------------------------------------------------------------

    /**
     * Parses "At the end of each of your turns, &lt;effect&gt;" — a recurring field-ability
     * trigger.  Returns a consumer that executes the inner effect directly; the caller
     * ({@code fireFieldEndOfTurnAbilities}) is responsible for invoking it each end phase.
     * The inner effect is resolved via the full {@link #parse} dispatcher so all supported
     * effect types work.
     */
    static Consumer<GameContext> tryParseEndOfEachTurnFieldAbility(String text, CardData source) {
        Matcher m = AT_END_OF_EACH_TURN_FA_PATTERN.matcher(text);
        if (!m.find()) return null;
        String inner = m.group("inner").trim();
        Consumer<GameContext> innerEffect = parse(inner, source);
        if (innerEffect == null) return null;
        return innerEffect;
    }

    /**
     * Parses "At the end of each player's turn, if [CardName] has received N damage or more, draw M card(s)."
     * Fires at the end of every player's turn (both P1's and P2's end phase).
     * The source card must be on the field; the check is against accumulated combat damage on that forward.
     */
    static Consumer<GameContext> tryParseEndOfEachPlayersTurnIfSelfFwdDamage(String text, CardData source) {
        if (source == null) return null;
        Matcher m = AT_END_OF_EACH_PLAYERS_TURN_IF_SELF_FWD_DAMAGE_DRAW.matcher(text.trim());
        if (!m.matches()) return null;
        String targetName = m.group("cardname").trim();
        if (!targetName.equalsIgnoreCase(source.name())) return null;
        int minDamage = Integer.parseInt(m.group("damage"));
        int drawCount = Integer.parseInt(m.group("draw"));
        return ctx -> {
            int fwdCount = ctx.isP1() ? ctx.p1ForwardCount() : ctx.p2ForwardCount();
            for (int i = 0; i < fwdCount; i++) {
                CardData fwd = ctx.isP1() ? ctx.p1Forward(i) : ctx.p2Forward(i);
                if (fwd.name().equalsIgnoreCase(targetName)) {
                    int dmg = ctx.isP1() ? ctx.p1ForwardCurrentDamage(i) : ctx.p2ForwardCurrentDamage(i);
                    if (dmg >= minDamage) {
                        ctx.logEntry("Field: " + source.name() + " — draw " + drawCount + " (" + dmg + " damage)");
                        ctx.drawCards(drawCount);
                    }
                    return;
                }
            }
        };
    }

    /**
     * Parses "If you have received N points of damage, put [CardName] into the Break Zone."
     * The returned consumer checks {@link GameContext#selfDamageCount()} at fire time against the threshold;
     * callers should invoke it whenever the controlling player's damage zone grows.
     */
    static Consumer<GameContext> tryParseIfSelfDamagePointsPutToBreakZone(String text, CardData source) {
        if (source == null) return null;
        Matcher m = IF_SELF_DAMAGE_POINTS_PUT_TO_BREAK_ZONE.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        int threshold = Integer.parseInt(m.group("points"));
        return ctx -> {
            if (ctx.selfDamageCount() < threshold) return;
            ctx.logEntry("Effect: " + source.name() + " — Break Zone (received " + threshold + " damage)");
            ctx.breakSourceCard(source);
        };
    }

    /**
     * Parses "If there are N or more cards removed from the game, &lt;effect&gt;".
     * The inner effect only fires when the combined permanent-RFP count of both players meets the threshold.
     */
    private static Consumer<GameContext> tryParseIfRfpCount(String text, CardData source) {
        Matcher m = IF_RFP_COUNT_INNER.matcher(text.trim());
        if (!m.find()) return null;
        int minRfp = Integer.parseInt(m.group("count"));
        String innerText = m.group("inner").trim();
        Consumer<GameContext> innerEffect = parse(innerText, source);
        if (innerEffect == null) return null;
        return ctx -> {
            int totalRfp = ctx.countRemovedFromGame();
            if (totalRfp >= minRfp) innerEffect.accept(ctx);
            else ctx.logEntry("Condition not met: need " + minRfp + "+ cards RFP, have " + totalRfp);
        };
    }

    /**
     * Parses "At the end of this turn, &lt;effect&gt;" — wraps any supported mass-field
     * effect so it fires at the beginning of the end phase instead of immediately.
     */
    private static Consumer<GameContext> tryParseDelayedEffect(String text) {
        Matcher m = AT_END_OF_TURN_PATTERN.matcher(text);
        if (!m.find()) return null;
        String rest = m.group("rest");
        Consumer<GameContext> inner = tryParseAllFieldEffect(rest);
        if (inner == null) return null;
        return ctx -> {
            ctx.logEntry("End-of-turn effect queued: " + rest);
            ctx.addEndOfTurnEffect(inner);
        };
    }

    // -------------------------------------------------------------------------
    // All-field-cards effect parser
    // -------------------------------------------------------------------------

    /**
     * Parses "[action] all [the] [element] [targets] [of cost X] [control]".
     *
     * <p>Supported actions: Break, dull, freeze, dull and freeze, Activate.
     * <p>Supported targets: Forwards, Backups, Forwards and Monsters, Characters.
     */
    private static Consumer<GameContext> tryParseAllFieldEffect(String text) {
        Matcher m = ALL_FIELD_EFFECT_PATTERN.matcher(text);
        if (!m.find()) return null;

        String rawAction = m.group("action").toLowerCase().replaceAll("\\s+", " ");
        GameContext.MassAction action = switch (rawAction) {
            case "break"          -> GameContext.MassAction.BREAK;
            case "dull"           -> GameContext.MassAction.DULL;
            case "freeze"         -> GameContext.MassAction.FREEZE;
            case "dull and freeze"-> GameContext.MassAction.DULL_AND_FREEZE;
            case "activate"       -> GameContext.MassAction.ACTIVATE;
            default               -> null;
        };
        if (action == null) return null;

        String element   = m.group("element");
        String job       = m.group("job") != null ? m.group("job").trim() : null;
        String category  = m.group("category");
        String targets   = m.group("targets");
        // When no explicit type is given (job-only or category-only), sweep all card types
        boolean inclForwards, inclBackups, inclMonsters;
        if (targets == null) {
            inclForwards = true; inclBackups = true; inclMonsters = (job == null && category == null);
        } else {
            String tgtLower  = targets.toLowerCase();
            inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
            inclBackups  = tgtLower.contains("backup")  || tgtLower.contains("character");
            inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");
        }

        String costStr = m.group("cost");
        String costCmp = m.group("costcmp");
        int    costVal = costStr != null ? Integer.parseInt(costStr) : -1;

        String excludeCostStr = m.group("excludecost");
        int    excludeCostVal = excludeCostStr != null ? Integer.parseInt(excludeCostStr) : -1;

        String control      = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null && control.toLowerCase().contains("you control");

        String traitStr     = m.group("trait");
        EnumSet<CardData.Trait> traitFilter = parseTraits(traitStr);

        String actionLabel = switch (action) {
            case BREAK           -> "Break";
            case DULL            -> "Dull";
            case FREEZE          -> "Freeze";
            case DULL_AND_FREEZE -> "Dull & Freeze";
            case ACTIVATE        -> "Activate";
            case RETURN_TO_HAND  -> "Return to hand";
        };
        String tgtLabel     = targets != null ? targets : (job != null ? "Job " + job : category != null ? "Cat " + category : "all");
        String costLabel    = costVal >= 0
                ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
        String exclLabel    = excludeCostVal >= 0 ? " [not cost " + excludeCostVal + "]" : "";
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String traitLabel   = traitStr != null ? " with " + traitStr.trim() : "";
        String logMsg = actionLabel + " all " + tgtLabel + traitLabel + costLabel + exclLabel + controlLabel;

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldEffect(action, inclForwards, inclBackups, inclMonsters,
                    opponentOnly, selfOnly, element, costVal, costCmp, excludeCostVal, job, category, traitFilter);
        };
    }

    /**
     * Parses "Return all [the] [element] [targets] [control] to their owners' hands."
     */
    private static Consumer<GameContext> tryParseReturnAllToHand(String text) {
        Matcher m = ALL_RETURN_TO_HAND_PATTERN.matcher(text);
        if (!m.find()) return null;

        String element  = m.group("element");
        String targets  = m.group("targets");
        boolean inclForwards, inclBackups, inclMonsters;
        if (targets == null) {
            inclForwards = true; inclBackups = true; inclMonsters = true;
        } else {
            String tgtLower = targets.toLowerCase();
            inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
            inclBackups  = tgtLower.contains("backup")  || tgtLower.contains("character");
            inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");
        }

        String control       = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null && control.toLowerCase().contains("you control");

        String elemLabel    = element != null ? element + " " : "";
        String tgtLabel     = targets != null ? targets : "all";
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String logMsg       = "Return all " + elemLabel + tgtLabel + controlLabel + " to hand";

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldEffect(GameContext.MassAction.RETURN_TO_HAND,
                    inclForwards, inclBackups, inclMonsters,
                    opponentOnly, selfOnly, element, -1, null, -1, null, null);
        };
    }

    private static Consumer<GameContext> tryParseChooseAnyNumberReturnToHand(String text) {
        Matcher m = CHOOSE_ANY_NUMBER_RETURN_TO_HAND.matcher(text);
        if (!m.matches()) return null;
        String typesRaw = m.group("types").toLowerCase(java.util.Locale.ROOT);
        boolean inclForwards = typesRaw.contains("forward") || typesRaw.contains("character");
        boolean inclBackups  = typesRaw.contains("backup")  || typesRaw.contains("character");
        boolean inclMonsters = typesRaw.contains("monster") || typesRaw.contains("character");
        String controlRaw    = m.group("control");
        boolean opponentOnly = controlRaw != null && !controlRaw.toLowerCase(java.util.Locale.ROOT).contains("you control");
        boolean selfOnly     = controlRaw != null &&  controlRaw.toLowerCase(java.util.Locale.ROOT).contains("you control");
        String typeLabel     = m.group("types");
        String controlLabel  = opponentOnly ? " (opponent's)" : selfOnly ? " (yours)" : "";
        return ctx -> {
            ctx.logEntry("Effect: Choose any number of " + typeLabel + controlLabel + " — return to hand");
            ctx.chooseAnyNumberReturnToHand(inclForwards, inclBackups, inclMonsters, opponentOnly, selfOnly);
        };
    }

    /**
     * Parses "Choose 1 auto-ability. Cancel its effect. If the cancelled auto-ability triggered
     * from a Forward, deal that Forward N damage."
     */
    private static Consumer<GameContext> tryParseCancelAutoAbilityAndDamageIfForward(String text) {
        Matcher m = CANCEL_AUTO_ABILITY_DAMAGE_IF_FORWARD.matcher(text);
        if (!m.find()) return null;
        int damage = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 auto-ability — cancel it; if triggered from a Forward, deal " + damage + " damage");
            ctx.cancelAutoAbilityAndDamageSourceIfForward(damage);
        };
    }

    /**
     * Parses "Choose 1 Summon targeting a Character you control. Cancel its effect."
     * Only Summons whose pre-selected targets include a card the canceler controls are eligible.
     */
    private static Consumer<GameContext> tryParseCancelSummonTargetingMyCharacter(String text) {
        if (!CANCEL_SUMMON_TARGETING_MY_CHARACTER.matcher(text).find()) return null;
        java.util.function.Predicate<StackEntry> filter = StackEntry::isSummon;
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 Summon targeting your Character — cancel its effect");
            ctx.cancelFilteredAbilityOnStack(filter, "Choose 1 Summon targeting your Character to cancel:", true);
        };
    }

    /**
     * Parses "Choose 1 Summon or auto-ability. Cancel its effect." (Y'shtola).
     * The player selects a stack entry; its effect is suppressed when it resolves.
     */
    private static Consumer<GameContext> tryParseCancelStackEntry(String text) {
        if (!STANDALONE_CANCEL_STACK_ENTRY_PATTERN.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 Summon or auto-ability — cancel its effect");
            ctx.cancelStackEntry();
        };
    }

    /**
     * Parses the general "Choose 1 [ability type(s)] [optional filter]. Cancel its effect." family.
     * Builds a {@link java.util.function.Predicate} over {@link StackEntry} from the parsed type string.
     */
    private static Consumer<GameContext> tryParseCancelAbilityOnStack(String text) {
        Matcher m = CANCEL_ABILITY_ON_STACK.matcher(text.trim());
        if (!m.find()) return null;
        String types = m.group("types").trim();
        String tgtFilterText = m.group("tgtFilter");
        boolean requiresControllerTarget = tgtFilterText != null
                && tgtFilterText.toLowerCase(java.util.Locale.ROOT).contains("you control");
        java.util.function.Predicate<StackEntry> filter = parseAbilityTypeFilter(types);
        String prompt = "Choose 1 " + types + " to cancel:";
        return ctx -> {
            ctx.logEntry("Effect: Cancel " + types + " on stack");
            ctx.cancelFilteredAbilityOnStack(filter, prompt, requiresControllerTarget);
        };
    }

    /**
     * Parses "Choose 1 [ability type(s)] [optional 'that has only one target']. You may choose
     * another target to become the new target (...)."
     */
    private static Consumer<GameContext> tryParseRedirectAbilityTarget(String text) {
        Matcher m = REDIRECT_ABILITY_TARGET.matcher(text.trim());
        if (!m.find()) return null;
        String types = m.group("types").trim();
        java.util.function.Predicate<StackEntry> filter = parseAbilityTypeFilter(types);
        String prompt = "Choose 1 " + types + " to redirect:";
        return ctx -> {
            ctx.logEntry("Effect: Redirect target of " + types + " on stack");
            ctx.redirectAbilityTarget(filter, prompt);
        };
    }

    /**
     * Converts an ability-type string captured by {@link #CANCEL_ABILITY_ON_STACK} or
     * {@link #REDIRECT_ABILITY_TARGET} into a predicate over stack entries.
     * <ul>
     *   <li>"auto-ability" / "auto ability" → auto-abilities only</li>
     *   <li>"action ability" → action abilities (regular and special)</li>
     *   <li>"special ability" → special (named) action abilities only</li>
     *   <li>"ability" → any non-summon, non-EX-burst entry</li>
     *   <li>Two types joined by " or " → union of the two individual predicates</li>
     * </ul>
     */
    private static java.util.function.Predicate<StackEntry> parseAbilityTypeFilter(String types) {
        String t = types.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.equals("ability")) return e -> !e.isSummon() && !e.isExBurstEntry();
        boolean wantsAuto    = t.contains("auto");
        boolean wantsSpecial = t.contains("special");
        boolean wantsAction  = t.contains("action");
        return e -> (wantsAuto    && e.isAutoAbility())
                 || (wantsSpecial && e.isSpecialAbility())
                 || (wantsAction  && e.isActionAbility());
    }

    /** "The [targets] you control gain +N power." — companion to CardData's bare-grant pattern. */
    private static final Pattern FIELD_GRANT_BARE_PASSIVE = Pattern.compile(
        "(?i)^The\\s+(?:Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+" +
        "you\\s+control\\s+gains?\\s+\\+\\d+\\s+power[.!]?$"
    );

    /** "The [Job (X)] / Job X / Category Y Forwards you control gain +N power." — bracket or plain form. */
    private static final Pattern FIELD_GRANT_JOB_CAT_PASSIVE = Pattern.compile(
        "(?i)^The\\s+" +
        "(?:\\[Job\\s*\\([^)]+\\)\\]|Job\\s+[A-Za-z][A-Za-z\\s''\\-]+?|" +
        "\\[Category\\s*\\([^)]+\\)\\]|Category\\s+\\S+)\\s+" +
        "(?:Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+" +
        "you\\s+control\\s+gains?\\s+\\+\\d+\\s+power[.!]?$"
    );

    /** "The [targets] opponent controls lose N power." — companion to CardData's opponent-debuff pattern. */
    private static final Pattern FIELD_OPPONENT_DEBUFF_PASSIVE = Pattern.compile(
        "(?i)^The\\s+(?:Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+" +
        "(?:your\\s+)?opponent\\s+controls?\\s+loses?\\s+\\d+\\s+power[.!]?$"
    );

    /** "If there are N or more cards in your Break Zone, ..." or "If you have N or more Job X ... in your Break Zone, ..." */
    private static final Pattern FIELD_GRANT_BZ_COND_PASSIVE = Pattern.compile(
        "(?i)^If\\s+(?:there\\s+are|you\\s+have)\\s+\\d+\\s+or\\s+more\\s+.+?\\s+in\\s+your\\s+Break\\s+Zone,"
    );

    /** "If there are N or more different Elements among [type] you control, [grant]." */
    private static final Pattern FIELD_GRANT_DIFF_ELEM_COND_PASSIVE = Pattern.compile(
        "(?i)^If\\s+there\\s+are\\s+\\d+\\s+or\\s+more\\s+different\\s+Elements?\\s+among\\s+" +
        "(?:Forwards?|Backups?|Characters?|Monsters?)\\s+you\\s+control[,.]"
    );

    /**
     * Recognises passive field grants applied by the engine via {@link CardData#fieldPowerGrants()};
     * returns a no-op lambda so that {@link #parse} does not report these as unrecognised.
     */
    private static Consumer<GameContext> tryParseFieldPowerGrantPassive(String text) {
        String trimmed = text.trim();
        if (FIELD_GRANT_BARE_PASSIVE.matcher(trimmed).matches()
                || FIELD_GRANT_JOB_CAT_PASSIVE.matcher(trimmed).matches()
                || FIELD_OPPONENT_DEBUFF_PASSIVE.matcher(trimmed).matches()
                || FIELD_GRANT_BZ_COND_PASSIVE.matcher(trimmed).find()
                || FIELD_GRANT_DIFF_ELEM_COND_PASSIVE.matcher(trimmed).find()) {
            return ctx -> { /* passive field grant — applied via fieldPowerGrants() */ };
        }
        return null;
    }

    /**
     * Parses "All [the] [element] [targets] [of cost N] [control] gain +N power until end of turn."
     */
    private static Consumer<GameContext> tryParseAllFieldPowerBoost(String text) {
        Matcher m = ALL_FIELD_POWER_BOOST_PATTERN.matcher(text);
        if (!m.find()) return null;

        String element  = m.group("element");
        String category = m.group("category");
        String targets  = m.group("targets");
        String tgtLower = targets.toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");

        String costStr = m.group("cost");
        String costCmp = m.group("costcmp");
        int    costVal = costStr != null ? Integer.parseInt(costStr) : -1;

        String control       = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null && control.toLowerCase().contains("you control");

        boolean isLose = m.group("verb").toLowerCase().startsWith("lose");
        int amount = Integer.parseInt(m.group("amount")) * (isLose ? -1 : 1);

        String elemLabel    = element != null ? element + " " : "";
        String catLabel     = category != null ? "Category " + category + " " : "";
        String costLabel    = costVal >= 0 ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String change       = isLose ? "-" + Math.abs(amount) : "+" + amount;
        String excludeName = m.group("excludename") != null ? m.group("excludename").trim() : null;
        String excludeLabel = excludeName != null ? " other than " + excludeName : "";

        String trailingRaw = text.substring(m.end()).trim().replaceAll("^[.!,]+\\s*", "").trim();
        Consumer<GameContext> secondary = trailingRaw.isEmpty() ? null : parse(trailingRaw, null);

        String logMsg = "All " + elemLabel + catLabel + targets + costLabel + excludeLabel + controlLabel
                + " " + change + " power until end of turn";

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldPowerBoost(amount, inclForwards, inclMonsters,
                    opponentOnly, selfOnly, element, costVal, costCmp, category, excludeName);
            if (secondary != null) secondary.accept(ctx);
        };
    }

    private static Consumer<GameContext> tryParseAllForwardsSameElementAsNamedPowerBoost(String text) {
        Matcher m = ALL_FORWARDS_SAME_ELEMENT_AS_NAMED_POWER_BOOST.matcher(text);
        if (!m.find()) return null;
        String name    = m.group("name").trim();
        boolean isLose = m.group("verb").toLowerCase().startsWith("lose");
        int amount     = Integer.parseInt(m.group("amount")) * (isLose ? -1 : 1);
        String control = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null &&  control.toLowerCase().contains("you control");
        return ctx -> {
            ctx.logEntry("Effect: All Forwards same element as " + name
                    + (selfOnly ? " (yours)" : opponentOnly ? " (opponent's)" : "")
                    + " " + (isLose ? "-" : "+") + Math.abs(amount) + " power until end of turn");
            ctx.allForwardsSameElementAsNamedGainPowerUntilEOT(name, amount, opponentOnly, selfOnly);
        };
    }

    /**
     * Parses "All Job X and Card Name Y [you control | opponent controls] gain +N power
     * until end of turn." — matches cards that have Job X OR are Card Name Y.
     */
    private static Consumer<GameContext> tryParseAllFieldJobCardNamePowerBoost(String text) {
        Matcher m = ALL_FIELD_JOB_CARDNAME_POWER_BOOST_PATTERN.matcher(text);
        if (!m.find()) return null;

        String job      = m.group("job").trim();
        String cardName = m.group("cardname").trim();
        String control  = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null &&  control.toLowerCase().contains("you control");

        boolean isLose = m.group("verb").toLowerCase().startsWith("lose");
        int amount = Integer.parseInt(m.group("amount")) * (isLose ? -1 : 1);
        String change = isLose ? "-" + Math.abs(amount) : "+" + amount;
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String logMsg = "All Job " + job + " and Card Name " + cardName + controlLabel
                + " " + change + " power until end of turn";

        String trailingRaw = text.substring(m.end()).trim().replaceAll("^[.!,]+\\s*", "").trim();
        Consumer<GameContext> secondary = trailingRaw.isEmpty() ? null : parse(trailingRaw, null);

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldJobCardNamePowerBoost(amount, true, true,
                    opponentOnly, selfOnly, job, cardName);
            if (secondary != null) secondary.accept(ctx);
        };
    }

    /**
     * Parses "[The] Card Name X [Forward] and Card Name Y [Forward] [you control] gain +N power
     * until end of turn." — boosts both named cards (OR logic via pipe-separated filter).
     */
    private static Consumer<GameContext> tryParseTwoCardNamesPowerBoost(String text) {
        Matcher m = TWO_CARD_NAMES_POWER_BOOST_PATTERN.matcher(text.trim());
        if (!m.find()) return null;

        String name1   = m.group("name1").trim();
        String name2   = m.group("name2").trim();
        String cardNameFilter = name1 + "|" + name2;
        String control = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null &&  control.toLowerCase().contains("you control");
        boolean isLose = m.group("verb").toLowerCase().startsWith("lose");
        int amount = Integer.parseInt(m.group("amount")) * (isLose ? -1 : 1);
        String change = isLose ? "-" + Math.abs(amount) : "+" + amount;
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String logMsg = "Card Name " + name1 + " and Card Name " + name2 + controlLabel
                + " " + change + " power until end of turn";

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldJobCardNamePowerBoost(amount, true, true,
                    opponentOnly, selfOnly, null, cardNameFilter);
        };
    }

    /**
     * Parses "All [the] Job X Forwards [you control] gain +N power until end of turn."
     */
    private static Consumer<GameContext> tryParseAllFieldJobPowerBoost(String text) {
        Matcher m = ALL_FIELD_JOB_POWER_BOOST_PATTERN.matcher(text);
        if (!m.find()) return null;

        String job      = m.group("job").trim();
        String targets  = m.group("targets");
        String tgtLower = targets.toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");

        String control       = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null &&  control.toLowerCase().contains("you control");

        boolean isLose = m.group("verb").toLowerCase().startsWith("lose");
        int amount = Integer.parseInt(m.group("amount")) * (isLose ? -1 : 1);

        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String change       = isLose ? "-" + Math.abs(amount) : "+" + amount;
        String logMsg       = "All Job " + job + " " + targets + controlLabel + " " + change + " power until end of turn";

        String trailingRaw = text.substring(m.end()).trim().replaceAll("^[.!,]+\\s*", "").trim();
        Consumer<GameContext> secondary = trailingRaw.isEmpty() ? null : parse(trailingRaw, null);

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldJobCardNamePowerBoost(amount, inclForwards, inclMonsters,
                    opponentOnly, selfOnly, job, null);
            if (secondary != null) secondary.accept(ctx);
        };
    }

    /**
     * Parses "All [the] Job X [targets] [you control] gain Keyword[, ...] until end of turn."
     */
    private static Consumer<GameContext> tryParseAllFieldJobKeywordGrant(String text) {
        Matcher m = ALL_FIELD_JOB_KEYWORD_GRANT_PATTERN.matcher(text);
        if (!m.find()) return null;

        String job      = m.group("job").trim();
        String targets  = m.group("targets");
        boolean inclForwards = targets == null || targets.toLowerCase().contains("forward")
                            || targets.toLowerCase().contains("character");
        boolean inclMonsters = targets == null || targets.toLowerCase().contains("monster")
                            || targets.toLowerCase().contains("character");

        String control       = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null &&  control.toLowerCase().contains("you control");

        EnumSet<CardData.Trait> traits = parseTraits(m.group("keywords"));
        if (traits.isEmpty()) return null;

        String traitNames = traitNamesOnly(traits);
        String typeLabel  = targets != null ? " " + targets : "";
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String logMsg = "All Job " + job + typeLabel + controlLabel + " gain " + traitNames + " until end of turn";

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldJobKeywordGrant(traits, inclForwards, inclMonsters,
                    opponentOnly, selfOnly, job);
        };
    }

    /**
     * Parses "All [the] [element] [targets] [of cost N or less/more] [you control] gain
     * Keyword[, Keyword2, ...] until end of turn."
     */
    private static Consumer<GameContext> tryParseAllFieldKeywordGrant(String text) {
        Matcher m = ALL_FIELD_KEYWORD_GRANT_PATTERN.matcher(text);
        if (!m.find()) return null;

        String element  = m.group("element");
        String category = m.group("category");
        String targets  = m.group("targets");
        String tgtLower = targets.toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");

        String costStr = m.group("cost");
        String costCmp = m.group("costcmp");
        int    costVal = costStr != null ? Integer.parseInt(costStr) : -1;

        String control       = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null && control.toLowerCase().contains("you control");

        EnumSet<CardData.Trait> traits = parseTraits(m.group("keywords"));
        if (traits.isEmpty()) return null;

        String elemLabel    = element != null ? element + " " : "";
        String catLabel     = category != null ? "Category " + category + " " : "";
        String costLabel    = costVal >= 0 ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String traitNames   = traitNamesOnly(traits);
        String logMsg = "All " + elemLabel + catLabel + targets + costLabel + controlLabel + " gain " + traitNames + " until end of turn";

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldKeywordGrant(traits, inclForwards, inclMonsters,
                    opponentOnly, selfOnly, element, costVal, costCmp, category);
        };
    }

    /**
     * Parses "Until end of turn, all [the] [element] [Category X] [targets] [you control]
     * gain +N power [and Keywords]."
     * Must be tried AFTER {@link #tryParseUntilEotDualPowerShift} to avoid partial matches.
     */
    private static Consumer<GameContext> tryParseUntilEotAllFieldPowerBoost(String text) {
        if (UNTIL_EOT_DUAL_POWER_SHIFT_PATTERN.matcher(text).find()) return null;

        Matcher m = UNTIL_EOT_ALL_FIELD_POWER_BOOST_PATTERN.matcher(text);
        if (!m.find()) return null;

        String element  = m.group("element");
        String category = m.group("category");
        String targets  = m.group("targets");
        String tgtLower = targets.toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");

        String costStr = m.group("cost");
        String costCmp = m.group("costcmp");
        int    costVal = costStr != null ? Integer.parseInt(costStr) : -1;

        String control       = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null && control.toLowerCase().contains("you control");

        String verb = m.group("verb");
        boolean isLoss = verb != null && verb.toLowerCase().startsWith("lose");
        int amount = Integer.parseInt(m.group("amount"));
        int signedAmount = isLoss ? -amount : amount;

        String keywordsStr = m.group("keywords");
        EnumSet<CardData.Trait> traits = keywordsStr != null
                ? parseTraits(keywordsStr) : EnumSet.noneOf(CardData.Trait.class);

        String elemLabel    = element != null ? element + " " : "";
        String catLabel     = category != null ? "Category " + category + " " : "";
        String costLabel    = costVal >= 0 ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String traitStr     = traits.isEmpty() ? "" : " and " + traitNamesOnly(traits);
        String sign         = isLoss ? "-" : "+";
        String logMsg = "Until EOT all " + elemLabel + catLabel + targets + costLabel
                + controlLabel + " " + sign + amount + " power" + traitStr;

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldPowerBoost(signedAmount, inclForwards, inclMonsters,
                    opponentOnly, selfOnly, element, costVal, costCmp, category, null);
            if (!traits.isEmpty())
                ctx.applyMassFieldKeywordGrant(traits, inclForwards, inclMonsters,
                        opponentOnly, selfOnly, element, costVal, costCmp, category);
        };
    }

    /**
     * Parses "Until end of turn, all [the] [targets] [you control] gain +N power
     * and all [the] [targets] [opponent controls] lose N power."
     */
    private static Consumer<GameContext> tryParseUntilEotDualPowerShift(String text) {
        Matcher m = UNTIL_EOT_DUAL_POWER_SHIFT_PATTERN.matcher(text);
        if (!m.find()) return null;

        String targets1  = m.group("targets1");
        String tgt1Lower = targets1.toLowerCase();
        boolean inclFwd1 = tgt1Lower.contains("forward") || tgt1Lower.contains("character");
        boolean inclMon1 = tgt1Lower.contains("monster") || tgt1Lower.contains("character");

        String control1  = m.group("control1");
        boolean opp1     = control1 != null && !control1.toLowerCase().contains("you control");
        boolean self1    = control1 != null && control1.toLowerCase().contains("you control");
        int amount1      = Integer.parseInt(m.group("amount1"));

        String targets2  = m.group("targets2");
        String tgt2Lower = targets2.toLowerCase();
        boolean inclFwd2 = tgt2Lower.contains("forward") || tgt2Lower.contains("character");
        boolean inclMon2 = tgt2Lower.contains("monster") || tgt2Lower.contains("character");

        String control2  = m.group("control2");
        boolean opp2     = control2 != null && !control2.toLowerCase().contains("you control");
        boolean self2    = control2 != null && control2.toLowerCase().contains("you control");
        int amount2      = Integer.parseInt(m.group("amount2"));

        String ctrl1Label = opp1 ? " (opponent)" : self1 ? " (yours)" : "";
        String ctrl2Label = opp2 ? " (opponent)" : self2 ? " (yours)" : "";
        String logMsg = "Until EOT all " + targets1 + ctrl1Label + " +" + amount1
                + " power, all " + targets2 + ctrl2Label + " -" + amount2 + " power";

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldPowerBoost( amount1, inclFwd1, inclMon1, opp1, self1, null, -1, null, null, null);
            ctx.applyMassFieldPowerBoost(-amount2, inclFwd2, inclMon2, opp2, self2, null, -1, null, null, null);
        };
    }

    /**
     * Parses "Select 1 number." abilities where the selected number is used as a cost filter
     * for a follow-on mass-field effect, damage sweep, or attack restriction.
     *
     * <p>Supported inner effects (appearing after "Select 1 number."):
     * <ul>
     *   <li>Any mass field action (Break/Dull/Freeze/Dull and Freeze) "of that cost" or
     *       "of the same cost as the selected number" — delegates to
     *       {@link GameContext#applyMassFieldEffect} with the chosen number as {@code costVal}.</li>
     *   <li>"All Forwards of that cost cannot attack this turn."</li>
     *   <li>"Deal N damage to all the Forwards of the same cost as the selected number [opponent controls]."</li>
     * </ul>
     * <p>Dual-selection variant: when "Your opponent selects 1 number." follows immediately,
     * both P1's and P2's numbers are obtained and the inner "Break all Forwards of cost equal
     * to either number." is applied for each.
     */
    private static Consumer<GameContext> tryParseSelectNumber(String text, CardData source) {
        Matcher hm = SELECT_NUMBER_HEADER.matcher(text);
        if (!hm.find()) return null;

        String rest = text.substring(hm.end()).trim();

        // Dual-selection variant: "Your opponent selects 1 number."
        Matcher om = SELECT_NUMBER_OPPONENT_ALSO.matcher(rest);
        boolean dualSelect = om.find();
        if (dualSelect) rest = rest.substring(om.end()).trim();

        final String innerText = rest;

        // --- Dual variant: "Break all Forwards of cost equal to either number." ---
        // P1 selects via dialog; the opponent AI picks the cost most common among P1's forwards.
        if (dualSelect && SELECT_NUMBER_INNER_EITHER_BREAK.matcher(innerText).find()) {
            return ctx -> {
                int n1 = ctx.selectNumber(0, 11, "Select a number:");
                ctx.logEntry("Effect: Player selects number " + n1);
                int n2 = aiMostCommonP1ForwardCost(ctx);
                ctx.logEntry("Effect: Opponent selects number " + n2 + " (AI)");
                ctx.logEntry("Effect: Break all Forwards of cost " + n1
                        + (n1 != n2 ? " or " + n2 : ""));
                ctx.applyMassFieldEffect(GameContext.MassAction.BREAK,
                        true, false, false, false, false, null, n1, null, -1, null, null);
                if (n1 != n2)
                    ctx.applyMassFieldEffect(GameContext.MassAction.BREAK,
                            true, false, false, false, false, null, n2, null, -1, null, null);
            };
        }

        // --- "All Forwards of that cost cannot attack this turn." ---
        if (SELECT_NUMBER_INNER_CANNOT_ATTACK.matcher(innerText).find()) {
            return ctx -> {
                int n = ctx.selectNumber(0, 11, "Select a number:");
                ctx.logEntry("Effect: Select number " + n
                        + " — all Forwards of cost " + n + " cannot attack this turn");
                for (int i = 0; i < ctx.p1ForwardCount(); i++)
                    if (ctx.p1Forward(i).cost() == n) ctx.setP1ForwardCannotAttack(i);
                for (int i = 0; i < ctx.p2ForwardCount(); i++)
                    if (ctx.p2Forward(i).cost() == n) ctx.setP2ForwardCannotAttack(i);
            };
        }

        // --- General case: substitute the selected number into the inner text and re-parse. ---
        // Supported placeholders:
        //   "of that cost"                         → "of cost N"
        //   "the same cost as the selected number" → "cost N"  (e.g. inside DEAL_DAMAGE_TO_FORWARDS)
        String probeText = innerText
                .replaceAll("(?i)of\\s+that\\s+cost\\b", "of cost 3")
                .replaceAll("(?i)the\\s+same\\s+cost\\s+as\\s+the\\s+selected\\s+number", "cost 3");
        if (parse(probeText, source) == null) return null;  // inner effect not yet supported

        return ctx -> {
            int n = ctx.selectNumber(0, 11, "Select a number:");
            ctx.logEntry("Effect: Select number " + n);
            String resolved = innerText
                    .replaceAll("(?i)of\\s+that\\s+cost\\b", "of cost " + n)
                    .replaceAll("(?i)the\\s+same\\s+cost\\s+as\\s+the\\s+selected\\s+number",
                            "cost " + n);
            Consumer<GameContext> effect = parse(resolved, source);
            if (effect != null) {
                effect.accept(ctx);
            } else {
                ctx.logEntry("[ActionResolver] SelectNumber: inner effect not parseable: " + resolved);
            }
        };
    }

    private static Consumer<GameContext> tryParseRandomRevealHandCastIfSummonFree(String text) {
        if (!RANDOM_REVEAL_HAND_CAST_IF_SUMMON_FREE.matcher(text.trim()).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Randomly reveal 1 card from hand — cast it for free if it is a Summon");
            ctx.randomRevealHandCastIfSummonFree();
        };
    }

    private static Consumer<GameContext> tryParseCastSummonFromHandDiscounted(String text) {
        Matcher m = CAST_SUMMON_FROM_HAND_DISCOUNTED.matcher(text.trim());
        if (!m.find()) return null;
        final int amount = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: Cast a Summon from hand (cost reduced by " + amount + ", floor 1)");
            ctx.castSummonFromHandDiscounted(amount);
        };
    }

    private static Consumer<GameContext> tryParseCastSummonFromHandFree(String text, int xValue) {
        Matcher m = CAST_SUMMON_FROM_HAND_FREE.matcher(text.trim());
        if (!m.find()) return null;
        String costStr = m.group("cost");
        boolean returnToHand = m.group("returnToHand") != null;
        final int maxCost;
        if (costStr == null) {
            maxCost = -1;
        } else if (costStr.equalsIgnoreCase("X")) {
            maxCost = xValue;
        } else {
            maxCost = Integer.parseInt(costStr);
        }
        String excludeRaw = m.group("excludeelems");
        String excludeElements = excludeRaw != null
                ? excludeRaw.trim().replaceAll("(?i)\\s+or\\s+", "|") : null;
        return ctx -> {
            String costDesc = maxCost < 0 ? "any cost" : "cost " + maxCost + " or less";
            ctx.logEntry("Effect: Cast 1 Summon (" + costDesc + ") from hand for free"
                    + (excludeElements != null ? " (not " + excludeElements + ")" : "")
                    + (returnToHand ? " (return to hand after use)" : ""));
            ctx.castSummonFromHandFree(maxCost, returnToHand, excludeElements);
        };
    }

    private static Consumer<GameContext> tryParseSearchAndCastSummonFree(String text) {
        Matcher m = SEARCH_AND_CAST_SUMMON_FREE_PATTERN.matcher(text.trim());
        if (!m.find()) return null;
        String element = m.group("element");
        String costStr = m.group("cost");
        int maxCost = costStr != null ? Integer.parseInt(costStr) : -1;
        return ctx -> {
            ctx.logEntry("Effect: Search deck for " + element + " Summon"
                    + (maxCost >= 0 ? " (cost " + maxCost + " or less)" : "") + ", cast for free or Break Zone");
            ctx.searchAndCastSummonFreeFromDeck(maxCost, element);
        };
    }

    /**
     * Parses "play any number of Job X from your hand onto the field".
     */
    private static Consumer<GameContext> tryParsePlayAnyNumberFromHand(String text, CardData source) {
        Matcher m = PLAY_ANY_NUMBER_FROM_HAND_PATTERN.matcher(text.trim());
        if (!m.find()) return null;

        String jobFilter = m.group("jobnm") != null ? m.group("jobnm").trim() : null;
        String targets   = m.group("targets");
        boolean anyType  = targets == null;
        String tgtLower  = anyType ? "" : targets.toLowerCase();
        boolean inclForwards = anyType || tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclBackups  = anyType || tgtLower.contains("backup")  || tgtLower.contains("character");
        boolean inclMonsters = anyType || tgtLower.contains("monster") || tgtLower.contains("character");

        final String fJob = jobFilter;
        return ctx -> {
            ctx.logEntry("Effect: Play any number of" + (fJob != null ? " Job " + fJob : "") + " from hand → field");
            ctx.playAnyNumberFromHand(inclForwards, inclBackups, inclMonsters, fJob, null, null, null);
        };
    }

    /**
     * Parses "Play 1 [type] of cost N [or less|more] from your hand onto the field".
     */
    private static Consumer<GameContext> tryParsePlayFromHand(String text, CardData source, int xValue) {
        Matcher m = PLAY_FROM_HAND_PATTERN.matcher(text);
        if (!m.find()) return null;

        // --- Resolve filter groups ---
        String jobFilter      = null;
        String cardNameFilter = null;
        String categoryFilter = m.group("category") != null ? m.group("category").trim() : null;

        String writtenCardName = m.group("cardname");
        String writtenJob      = m.group("jobnm");
        String writtenJobOnly  = m.group("jobnmonly");
        String writtenJobOr    = m.group("jobnmor");
        String writtenCnameOr  = m.group("cnameor");
        if (writtenCardName != null) {
            cardNameFilter = writtenCardName.trim();
        } else if (writtenJobOr != null) {
            jobFilter      = writtenJobOr.trim();
            cardNameFilter = writtenCnameOr != null ? writtenCnameOr.trim() : null;
        } else if (writtenJob != null) {
            jobFilter = writtenJob.trim();
        } else if (writtenJobOnly != null) {
            jobFilter = writtenJobOnly.trim();
        } else {
            String f1 = m.group("f1");
            String f2 = m.group("f2");
            if (f1 != null) {
                Matcher jm = JOB_BRACKET_PATTERN.matcher(f1);
                Matcher nm = CARD_NAME_BRACKET_PATTERN.matcher(f1);
                if      (jm.find()) jobFilter      = jm.group(1).trim();
                else if (nm.find()) cardNameFilter = nm.group(1).trim();
            }
            if (f2 != null) {
                Matcher jm = JOB_BRACKET_PATTERN.matcher(f2);
                Matcher nm = CARD_NAME_BRACKET_PATTERN.matcher(f2);
                if (jm.find()) {
                    String j2 = jm.group(1).trim();
                    jobFilter = jobFilter != null ? jobFilter + "|" + j2 : j2;
                } else if (nm.find()) {
                    cardNameFilter = nm.group(1).trim();
                }
            }
        }

        // --- Resolve type ---
        String  targets      = m.group("targets");
        boolean hasFilter    = jobFilter != null || cardNameFilter != null || categoryFilter != null;
        if (targets == null && !hasFilter) return null;
        String  tgtLower     = targets != null ? targets.toLowerCase() : "";
        boolean inclForwards = tgtLower.isEmpty() || tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclBackups  = tgtLower.isEmpty() || tgtLower.contains("backup")  || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.isEmpty() || tgtLower.contains("monster") || tgtLower.contains("character");

        // --- Resolve cost ---
        String dynFilterRaw = m.group("dynfilter");
        boolean isDynamic   = dynFilterRaw != null;
        String dynJob = null, dynName = null;
        if (isDynamic) {
            Matcher djm = java.util.regex.Pattern.compile(
                "(?i)Job\\s+(.+?)(?:\\s+and/or\\s+|$)").matcher(dynFilterRaw);
            Matcher dnm = java.util.regex.Pattern.compile(
                "(?i)Card\\s+Name\\s+(\\S+(?:\\s+\\([^)]+\\))?)").matcher(dynFilterRaw);
            if (djm.find()) dynJob  = djm.group(1).trim();
            if (dnm.find()) dynName = dnm.group(1).trim();
        }

        String costStr  = m.group("cost");
        String costAlt  = m.group("costalt");
        int    costVal  = -1;
        String costCmp  = null;
        int    costVal2 = -1;
        if (!isDynamic && costStr != null) {
            costVal = costStr.equalsIgnoreCase("X") ? xValue : Integer.parseInt(costStr);
            if (costAlt != null) {
                if (costAlt.equalsIgnoreCase("less") || costAlt.equalsIgnoreCase("more"))
                    costCmp = costAlt.toLowerCase();
                else
                    costVal2 = Integer.parseInt(costAlt);  // "cost 3 or 4"
            }
        }

        String  excludeName = m.group("excludename") != null ? m.group("excludename").trim() : null;
        boolean entersDull  = m.group("dull") != null;

        // --- Element filter ---
        String elemsRaw     = m.group("preelems");
        String elementFilter = elemsRaw != null
                ? elemsRaw.trim().replaceAll("(?i)\\s+or\\s+", "|") : null;

        // Build log label
        StringBuilder filterDesc = new StringBuilder();
        if (elementFilter  != null) filterDesc.append(" [").append(elemsRaw).append("]");
        if (jobFilter      != null) filterDesc.append(" [Job ").append(jobFilter).append("]");
        if (cardNameFilter != null) filterDesc.append(" [Name ").append(cardNameFilter).append("]");
        if (categoryFilter != null) filterDesc.append(" [Cat ").append(categoryFilter).append("]");
        String tgtLabel  = targets != null ? " " + targets : "";
        String costLabel = isDynamic ? " of cost ≤count[" + dynFilterRaw + "]"
                         : costVal2 >= 0 ? " of cost " + costVal + " or " + costVal2
                         : costVal >= 0  ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
        String exclLabel = excludeName != null ? " excl." + excludeName : "";
        String dullLabel = entersDull ? " dull" : "";

        final String fJob = jobFilter, fName = cardNameFilter, fCat = categoryFilter;
        final String fElem = elementFilter;
        final String fExclude = excludeName, fDynJob = dynJob, fDynName = dynName;
        final int fCostVal = costVal, fCostVal2 = costVal2;
        final String fCostCmp = costCmp;
        final boolean fEntersDull = entersDull;
        String rawExcludeElem = m.group("excludeelem");
        final String fExcludeElem = rawExcludeElem != null ? rawExcludeElem.trim() : null;
        final boolean fSuppressAuto = ITS_AUTO_ABILITY_WILL_NOT_TRIGGER.matcher(text).find();

        return ctx -> {
            int resolvedCost = fCostVal;
            String resolvedCmp = fCostCmp;
            if (isDynamic) {
                int n;
                if (fDynJob != null && fDynName != null) {
                    n = ctx.countSelfFieldCards(true, true, true, fDynJob, null)
                      + ctx.countSelfFieldCards(true, true, true, null, fDynName)
                      - ctx.countSelfFieldCards(true, true, true, fDynJob, fDynName);
                } else {
                    n = ctx.countSelfFieldCards(true, true, true, fDynJob, fDynName);
                }
                resolvedCost = n;
                resolvedCmp  = "less";
            }
            ctx.logEntry("Effect: Play 1" + filterDesc + tgtLabel + costLabel + exclLabel + dullLabel + " from hand"
                    + (fSuppressAuto ? " (no ETF auto-ability)" : ""));
            ctx.playCharacterFromHand(inclForwards, inclBackups, inclMonsters,
                    resolvedCost, resolvedCmp, fCostVal2,
                    fJob, fName, fCat, fElem, fExclude, fEntersDull, fExcludeElem, fSuppressAuto);
        };
    }

    /**
     * Parses "Your opponent selects N [condition] [type] they control [sep] followup".
     * Supported followups: "Put it into the Break Zone" and "dull/dulls it".
     */
    private static Consumer<GameContext> tryParseOpponentSelects(String text) {
        Matcher m = OPPONENT_SELECTS_PATTERN.matcher(text);
        if (!m.find()) return null;

        int     count     = Integer.parseInt(m.group("count"));
        String  condition = m.group("condition");
        String  element   = m.group("element");
        String  targets   = m.group("targets");
        String  tgtLower  = targets.toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclBackups  = tgtLower.contains("backup")  || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");
        String  followup  = m.group("followup").trim();

        String prefix = "Opponent selects " + count
                + (condition != null ? " " + condition : "")
                + (element   != null ? " " + element   : "")
                + " " + targets + " (opponent)";

        if (FOLLOWUP_PUT_TO_BREAK_ZONE.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(prefix + " — Force to Break Zone");
                List<ForwardTarget> ts = ctx.selectCharacters(count, false, true, false,
                        condition, element, -1, null, -1, null,
                        inclForwards, inclBackups, inclMonsters, null, null, null, null, false, null, false);
                sortedByIdxDesc(ts, false).forEach(ctx::forceTargetToBreakZone);
            };
        }

        if (FOLLOWUP_DULL.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(prefix + " — Dull");
                List<ForwardTarget> ts = ctx.selectCharacters(count, false, true, false,
                        condition, element, -1, null, -1, null,
                        inclForwards, inclBackups, inclMonsters, null, null, null, null, false, null, false);
                sortedByIdxDesc(ts, false).forEach(ctx::dullTarget);
            };
        }

        if (FOLLOWUP_RETURN_TO_OWNERS_HAND.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(prefix + " — Return to owner's hand");
                List<ForwardTarget> ts = ctx.selectCharacters(count, false, true, false,
                        condition, element, -1, null, -1, null,
                        inclForwards, inclBackups, inclMonsters, null, null, null, null, false, null, false);
                sortedByIdxDesc(ts, false).forEach(t -> {
                    switch (t.zone()) {
                        case FORWARD -> { if (t.isP1()) ctx.returnP1ForwardToHand(t.idx());
                                          else          ctx.returnP2ForwardToHand(t.idx()); }
                        case BACKUP  -> { if (t.isP1()) ctx.returnP1BackupToHand(t.idx());
                                          else          ctx.returnP2BackupToHand(t.idx()); }
                        case MONSTER -> { if (t.isP1()) ctx.returnP1MonsterToHand(t.idx());
                                          else          ctx.returnP2MonsterToHand(t.idx()); }
                    }
                });
            };
        }

        return ctx -> ctx.logEntry(
                "[ActionResolver] Opponent selects — followup not yet implemented: " + followup);
    }

    /**
     * Parses the EX Burst compound effect:
     * "Choose up to 1 Forward from your Break Zone of cost ≤ damage dealt → hand;
     *  opponent selects 1 Forward of cost ≤ damage dealt → Break Zone."
     */
    private static Consumer<GameContext> tryParseBzFwdToHandOppFwdToBzByDamage(String text) {
        if (!BZ_FWD_TO_HAND_OPP_FWD_TO_BZ_BY_DAMAGE.matcher(text).find()) return null;
        return ctx -> {
            int dmg = ctx.selfDamageCount();
            ctx.logEntry("Effect: own BZ Forward cost ≤ " + dmg + " → hand; opponent Forward cost ≤ " + dmg + " → BZ");
            List<ForwardTarget> bzTs = ctx.selectCharactersFromBreakZone(
                    1, true, false, false, null, null, dmg, "less", -1, null,
                    true, false, false, null, null, null, null, false, null, false);
            sortedByIdxDesc(bzTs, true).forEach(ctx::addTargetToHand);
            List<ForwardTarget> oppTs = ctx.selectCharacters(
                    1, false, true, false, null, null, dmg, "less", -1, null,
                    true, false, false, null, null, null, null, false, null, false);
            sortedByIdxDesc(oppTs, false).forEach(ctx::forceTargetToBreakZone);
        };
    }

    private static Consumer<GameContext> tryParseOpponentPutsForwardToBreakZone(String text) {
        Matcher m = OPPONENT_PUTS_FORWARD_TO_BREAK_ZONE_PATTERN.matcher(text);
        if (!m.find()) return null;

        int     count     = Integer.parseInt(m.group("count"));
        String  condition = m.group("condition");
        String  targets   = m.group("targets");
        String  tgtLower  = targets.toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("character");

        String condLabel = condition != null ? " " + condition : "";
        String logLabel  = "Opponent puts " + count + condLabel + " " + targets
                         + " they control → Break Zone";

        return ctx -> {
            ctx.logEntry("Effect: " + logLabel);
            List<ForwardTarget> ts = ctx.selectCharacters(count, false, true, false,
                    condition, null, -1, null, -1, null,
                    inclForwards, false, inclMonsters, null, null, null, null, false, null, false);
            sortedByIdxDesc(ts, false).forEach(ctx::forceTargetToBreakZone);
        };
    }

    /**
     * Parses "Your opponent puts the top N card(s) of his/her deck into the Break Zone
     * [. Draw M card(s)]".
     */
    private static Consumer<GameContext> tryParseOpponentMill(String text) {
        Matcher m = OPPONENT_MILL_PATTERN.matcher(text);
        if (!m.find()) return null;

        String countStr = m.group("count");
        if (countStr == null) countStr = m.group("count2");
        int    mill     = countStr != null ? Integer.parseInt(countStr) : 1;
        String drawStr  = m.group("draw");
        int    draw     = drawStr  != null ? Integer.parseInt(drawStr)  : 0;

        return ctx -> {
            ctx.logEntry("Effect: Opponent mills " + mill + " card(s)"
                    + (draw > 0 ? ", draw " + draw : ""));
            ctx.opponentMillCards(mill);
            if (draw > 0) ctx.drawCards(draw);
        };
    }

    private static Consumer<GameContext> tryParseOpponentMillIfSameElementDraw(String text) {
        Matcher m = OPPONENT_MILL_IF_SAME_ELEMENT_DRAW.matcher(text);
        if (!m.find()) return null;
        String countStr = m.group("count");
        if (countStr == null) countStr = m.group("count2");
        int mill = countStr != null ? Integer.parseInt(countStr) : 2;
        int draw = Integer.parseInt(m.group("draw"));
        return ctx -> {
            ctx.logEntry("Effect: Opponent mills " + mill + " — draw " + draw + " if all same element");
            ctx.opponentMillIfSameElementDraw(mill, draw);
        };
    }

    /** Parses "Put the top N card(s) of your deck into the Break Zone." */
    private static Consumer<GameContext> tryParseSelfMill(String text) {
        Matcher m = SELF_MILL_PATTERN.matcher(text);
        if (!m.find()) return null;

        String countStr = m.group("count");
        int    mill     = countStr != null ? Integer.parseInt(countStr) : 1;

        return ctx -> {
            ctx.logEntry("Effect: Mill " + mill + " card(s) into own Break Zone");
            ctx.millCards(mill);
        };
    }

    /** Parses "Your opponent shows/reveals his/her hand". */
    private static Consumer<GameContext> tryParseOpponentRevealHand(String text) {
        Matcher m = OPPONENT_REVEAL_HAND_PATTERN.matcher(text);
        if (!m.find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Opponent reveals hand");
            ctx.revealOpponentHand();
        };
    }

    /**
     * Parses one or more "If it is/has [cond], [action]" clauses following a
     * "Reveal the top card of your deck" header.
     * Each action is either a card-referencing op code or a standalone effect
     * parsed by {@link #parse}.
     */
    private static Consumer<GameContext> tryParseRevealTopDeck(String text, CardData source) {
        Matcher header = REVEAL_TOP_DECK_HEADER.matcher(text);
        if (!header.find()) return null;
        boolean opponentDeck = header.group("who").toLowerCase(java.util.Locale.ROOT).contains("opponent");
        List<RevealClause> clauses = new ArrayList<>();
        Matcher m = REVEAL_CLAUSE_PATTERN.matcher(text);
        while (m.find()) {
            RevealClause clause = buildRevealClause(
                m.group("cond").trim(), m.group("action").trim(), source);
            if (clause == null) return null;
            clauses.add(clause);
        }
        if (clauses.isEmpty()) return null;
        String whose = opponentDeck ? "opponent's" : "your";
        return ctx -> {
            ctx.logEntry("Effect: Reveal top card of " + whose + " deck (" + clauses.size() + " clause(s))");
            ctx.revealTopDeckCard(clauses, opponentDeck);
        };
    }

    private static Consumer<GameContext> tryParseEachPlayerRevealCharacterMayPlay(String text) {
        Matcher m = EACH_PLAYER_REVEAL_CHARACTER_MAY_PLAY.matcher(text);
        if (!m.find()) return null;
        String typeStr = m.group("type").trim();
        java.util.function.Predicate<CardData> eligible = card -> meetsTypeCheck(card, typeStr);
        return ctx -> {
            ctx.logEntry("Effect: Each player reveals top card, may play if " + typeStr);
            ctx.revealEachPlayerTopDeckMayPlay(eligible);
        };
    }

    private static Consumer<GameContext> tryParseEachPlayerMaySearchForwardMinPower(String text) {
        Matcher m = EACH_PLAYER_MAY_SEARCH_FORWARD_MIN_POWER.matcher(text.trim());
        if (!m.matches()) return null;
        int count    = Integer.parseInt(m.group("count"));
        int minPower = Integer.parseInt(m.group("power"));
        return ctx -> {
            ctx.logEntry("Effect: Each player may search for " + count + " Forward(s) power " + minPower + "+");
            ctx.eachPlayerMaySearchForwardMinPowerToHand(count, minPower);
        };
    }

    /**
     * Builds a single {@link RevealClause} from a parsed condition string and
     * action string.  Returns {@code null} if either the condition or the action
     * is not recognised.
     */
    private static RevealClause buildRevealClause(String condText, String actionText, CardData source) {
        Predicate<CardData> condition = parseRevealCondition(condText);
        if (condition == null) return null;
        String cardOp = normalizeRevealOp(actionText);
        if (cardOp != null) return new RevealClause(condition, cardOp, null);
        Consumer<GameContext> effect = parse(actionText, source);
        if (effect != null) return new RevealClause(condition, null, effect);
        return null;
    }

    /**
     * Converts a raw condition string (captured from "If it is/has [cond],") into a
     * {@link Predicate} that tests a {@link CardData} against that condition.
     * Supported forms (article and negation handled first):
     * <ul>
     *   <li>"[not] a/an Forward|Backup|Character|Summon|Monster"</li>
     *   <li>"[not] a/an [Element] [type|card]" — element alone, element+type, element+card</li>
     *   <li>"[not] a/an Job X [or Card Name Y]"</li>
     *   <li>"[not] a/an Card Name X"</li>
     *   <li>"[not] a/an Category X [type]"</li>
     * </ul>
     * Returns {@code null} for unrecognised patterns.
     */
    private static Predicate<CardData> parseRevealCondition(String cond) {
        cond = cond.trim();
        boolean negated = false;

        Matcher negM = Pattern.compile("(?i)^not\\s+an?\\s+(.+)$").matcher(cond);
        if (negM.matches()) {
            negated = true;
            cond = negM.group(1).trim();
        } else {
            Matcher artM = Pattern.compile("(?i)^an?\\s+(.+)$").matcher(cond);
            if (artM.matches()) cond = artM.group(1).trim();
        }

        Predicate<CardData> pred;

        // 1. "Job X [or [a/an] Card Name Y]"
        Matcher jobM = Pattern.compile(
            "(?i)^Job\\s+(.+?)(?:\\s+or\\s+(?:an?\\s+)?Card\\s+Name\\s+(.+))?$"
        ).matcher(cond);
        if (jobM.matches()) {
            String job  = jobM.group(1).trim();
            String name = jobM.group(2) != null ? jobM.group(2).trim() : null;
            pred = card -> card.hasJob(job)
                    || (name != null && card.name().equalsIgnoreCase(name));
            return negated ? pred.negate() : pred;
        }

        // 2. "Card Name X"
        Matcher nameM = Pattern.compile("(?i)^Card\\s+Name\\s+(.+)$").matcher(cond);
        if (nameM.matches()) {
            String name = nameM.group(1).trim();
            pred = card -> card.name().equalsIgnoreCase(name);
            return negated ? pred.negate() : pred;
        }

        // 3. "Category X [type|card]"
        Matcher catM = Pattern.compile(
            "(?i)^Category\\s+(\\S+)(?:\\s+(Forward|Character|Backup|Summon|Monster|card))?$"
        ).matcher(cond);
        if (catM.matches()) {
            String cat     = catM.group(1).trim();
            String catType = catM.group(2);
            pred = card -> {
                String cl = cat.toLowerCase(java.util.Locale.ROOT);
                if (!card.category1().toLowerCase(java.util.Locale.ROOT).contains(cl)
                        && !card.category2().toLowerCase(java.util.Locale.ROOT).contains(cl))
                    return false;
                return catType == null || catType.equalsIgnoreCase("card")
                        || meetsTypeCheck(card, catType);
            };
            return negated ? pred.negate() : pred;
        }

        // 4. "[Element] [type|card]" — element alone, element+type, or element+"card"
        Matcher elemM = Pattern.compile(
            "(?i)^(Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+(Forward|Character|Backup|Summon|Monster|card))?$"
        ).matcher(cond);
        if (elemM.matches()) {
            String elem     = elemM.group(1);
            String elemType = elemM.group(2);
            pred = card -> {
                if (!card.containsElement(elem)) return false;
                return elemType == null || elemType.equalsIgnoreCase("card")
                        || meetsTypeCheck(card, elemType);
            };
            return negated ? pred.negate() : pred;
        }

        // 5. Simple type
        Matcher typeM = Pattern.compile(
            "(?i)^(Forward|Character|Backup|Summon|Monster)$"
        ).matcher(cond);
        if (typeM.matches()) {
            String type = typeM.group(1);
            pred = card -> meetsTypeCheck(card, type);
            return negated ? pred.negate() : pred;
        }

        return null;
    }

    private static boolean meetsTypeCheck(CardData card, String type) {
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "forward"   -> card.isForward();
            case "backup"    -> card.isBackup();
            case "character" -> card.isForward() || card.isBackup() || card.isMonster();
            case "summon"    -> card.isSummon();
            case "monster"   -> card.isMonster();
            default          -> false;
        };
    }

    /**
     * Returns a card-op code if {@code raw} is an action that directly places the
     * revealed card ("play it onto the field [dull]", "add it to your hand",
     * "put it into the Break Zone").  Returns {@code null} for all other actions
     * (standalone effects like "draw N cards", "deal X damage …"), which are then
     * parsed by the main {@link #parse} chain.
     */
    private static String normalizeRevealOp(String raw) {
        if (raw == null) return null;
        String lo = raw.trim().toLowerCase(java.util.Locale.ROOT);
        // Compound actions that involve selecting another card first are handled by parse(),
        // not treated as simple "place revealed card" ops.
        if (lo.contains("select") || lo.contains("choose") || lo.startsWith("your opponent")) return null;
        if (lo.contains("field") && lo.contains("dull")) return "playOntoFieldDull";
        if (lo.contains("field"))  return "playOntoField";
        if (lo.contains("hand"))   return "addToHand";
        if (lo.contains("break"))  return "putToBreakZone";
        if (lo.contains("cast") && lo.contains("cost")) return "castSummonFree";
        return null;
    }

    /**
     * Parses standalone "cannot be chosen" protection effects:
     * <ul>
     *   <li>"Activate all the Forwards you control. They cannot be chosen by your opponent's Summons."</li>
     *   <li>"This Forward/Character cannot be chosen by your opponent's Summons or abilities."</li>
     *   <li>"[CardName] cannot be chosen by your opponent's Summons." (name must match {@code source})</li>
     *   <li>"The Job X [other than Y] Forwards you control cannot be chosen by your opponent's Summons."</li>
     * </ul>
     * Registered before {@link #tryParseNegateAllDamage} and {@link #tryParseAllFieldEffect}.
     */
    private static Consumer<GameContext> tryParseCannotBeChosenStandalone(String text, CardData source) {
        // 1. Compound: "Activate all Forwards + They cannot be chosen"
        Matcher actM = STANDALONE_ACTIVATE_AND_CANNOT_BE_CHOSEN.matcher(text);
        if (actM.find()) {
            boolean bs = actM.group("scope").toLowerCase(java.util.Locale.ROOT).contains("summon");
            boolean ba = actM.group("scope").toLowerCase(java.util.Locale.ROOT).contains("abilit");
            return ctx -> {
                ctx.logEntry("Effect: Activate all own Forwards + cannot be chosen by opponent");
                ctx.applyMassFieldEffect(GameContext.MassAction.ACTIVATE, true, false, false, false, true, null, -1, null, -1, null, null);
                ctx.shieldAllOwnForwardsCannotBeChosen(bs, ba);
            };
        }

        // 2. Job filter: "The Job X [other than Y] Forwards cannot be chosen"
        Matcher jobM = STANDALONE_JOB_CANNOT_BE_CHOSEN.matcher(text);
        if (jobM.find()) {
            String job  = jobM.group("job").trim();
            String excl = jobM.group("excl") != null ? jobM.group("excl").trim() : null;
            boolean bs  = jobM.group("scope").toLowerCase(java.util.Locale.ROOT).contains("summon");
            boolean ba  = jobM.group("scope").toLowerCase(java.util.Locale.ROOT).contains("abilit");
            return ctx -> {
                ctx.logEntry("Effect: Job " + job + " Forwards cannot be chosen by opponent");
                ctx.shieldJobForwardsCannotBeChosen(job, excl, bs, ba);
            };
        }

        // 3. Self-referential: "This Forward/Character cannot be chosen"
        Matcher selfM = STANDALONE_SELF_CANNOT_BE_CHOSEN.matcher(text);
        if (selfM.find() && source != null) {
            boolean bs = selfM.group("scope").toLowerCase(java.util.Locale.ROOT).contains("summon");
            boolean ba = selfM.group("scope").toLowerCase(java.util.Locale.ROOT).contains("abilit");
            String  nm = source.name();
            return ctx -> {
                ctx.logEntry("Effect: " + nm + " cannot be chosen by opponent");
                ctx.shieldNamedCardCannotBeChosen(nm, bs, ba);
            };
        }

        // 4. Named card: "[Name] cannot be chosen by your opponent's Summons/abilities"
        Matcher nameM = STANDALONE_NAMED_CANNOT_BE_CHOSEN.matcher(text);
        if (nameM.find() && source != null) {
            String nm   = nameM.group("name").trim();
            boolean bs  = nameM.group("scope").toLowerCase(java.util.Locale.ROOT).contains("summon");
            boolean ba  = nameM.group("scope").toLowerCase(java.util.Locale.ROOT).contains("abilit");
            if (nm.equalsIgnoreCase(source.name()))
                return ctx -> {
                    ctx.logEntry("Effect: " + nm + " cannot be chosen by opponent");
                    ctx.shieldNamedCardCannotBeChosen(nm, bs, ba);
                };
        }

        // 5. Named card, no "your opponent's" qualifier: "[Name] cannot be chosen by Summons" — either player
        Matcher anyM = STANDALONE_NAMED_CANNOT_BE_CHOSEN_ANY_SUMMON.matcher(text);
        if (anyM.find() && source != null) {
            String nm = anyM.group("name").trim();
            if (nm.equalsIgnoreCase(source.name()))
                return ctx -> ctx.shieldNamedCardCannotBeChosenByAnySummon(nm);
        }

        // 6. "Name 1 Element. [Name] cannot be chosen … and if [Name] is dealt damage … becomes 0."
        //    (Hein-style: targeting immunity + damage nullification for the named element)
        Matcher heinM = STANDALONE_NAME_ELEMENT_IMMUNE_AND_NULLIFY_DAMAGE.matcher(text);
        if (heinM.find() && source != null) {
            String nm = heinM.group("name").trim();
            if (nm.equalsIgnoreCase(source.name()))
                return ctx -> {
                    String elem = ctx.selectElement("Name 1 Element (" + nm + " full protection):");
                    if (elem != null) {
                        ctx.logEntry("Effect: " + nm + " cannot be chosen by " + elem + " Summons/abilities; damage from them → 0 this turn");
                        ctx.shieldNamedCardCannotBeChosenByElement(nm, elem);
                        ctx.nullifyNamedCardDamageByElement(nm, elem);
                    }
                };
        }

        // 7. "Name 1 Element. [Name] cannot be chosen by Summons or abilities of the named Element this turn."
        Matcher elemM = STANDALONE_NAME_ELEMENT_AND_IMMUNE.matcher(text);
        if (elemM.find() && source != null) {
            String nm = elemM.group("name").trim();
            if (nm.equalsIgnoreCase(source.name()))
                return ctx -> {
                    String elem = ctx.selectElement("Name 1 Element (" + nm + " immunity):");
                    if (elem != null) {
                        ctx.logEntry("Effect: " + nm + " cannot be chosen by " + elem + " Summons/abilities this turn");
                        ctx.shieldNamedCardCannotBeChosenByElement(nm, elem);
                    }
                };
        }

        return null;
    }

    /**
     * Parses "[CardName] cannot become dull by your opponent's Summons or abilities."
     * Enforcement is handled in {@link GameContextImpl#dullP1Forward} / {@code dullP2Forward}
     * via {@link #hasCannotBeDulledByOppFieldAbility}.
     */
    private static Consumer<GameContext> tryParseCannotBecomeDullOpp(String text, CardData source) {
        Matcher m = STANDALONE_NAMED_CANNOT_BECOME_DULL_OPP.matcher(text);
        if (!m.find() || source == null) return null;
        String nm = m.group("name").trim();
        if (!nm.equalsIgnoreCase(source.name())) return null;
        return ctx -> ctx.logEntry("Field ability: " + nm + " cannot become dull by opponent's Summons or abilities");
    }

    /**
     * Returns {@code true} if the card has a permanent field ability of the form
     * "[CardName] cannot become dull by your opponent's Summons or abilities."
     */
    static boolean hasCannotBeDulledByOppFieldAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            Matcher m = STANDALONE_NAMED_CANNOT_BECOME_DULL_OPP.matcher(fa.effectText());
            if (m.find() && m.group("name").trim().equalsIgnoreCase(card.name())) return true;
        }
        return false;
    }

    /**
     * Parses permanent and conditional "cannot attack or block" field ability texts:
     * <ol>
     *   <li>"[CardName] cannot attack or block." — unconditional; enforced via
     *       {@link CardData#cannotAttackOrBlock()}.</li>
     *   <li>"[CardName] cannot attack." — unconditional attack-only; enforced via field-ability check.</li>
     *   <li>"If you don't control a Card Name [X] Forward, [CardName] cannot attack or block."</li>
     *   <li>"If [N] or less [Name] Counter(s) are placed on [CardName], [CardName] cannot attack or block."</li>
     * </ol>
     * The consumer only logs; enforcement for cases 2–5 is handled in the game loop.
     */
    private static Consumer<GameContext> tryParseStandaloneCannotAttackOrBlock(String text, CardData source) {
        if (source == null) return null;
        // 1. Simple: "[CardName] cannot attack or block."
        Matcher m1 = STANDALONE_CANNOT_ATTACK_OR_BLOCK.matcher(text);
        if (m1.find()) {
            String nm = m1.group("cardname").trim();
            if (nm.equalsIgnoreCase(source.name()))
                return ctx -> ctx.logEntry(nm + " — cannot attack or block (permanent field restriction)");
        }
        // 1b. Attack-only: "[CardName] cannot attack."
        Matcher m1b = STANDALONE_CANNOT_ATTACK.matcher(text);
        if (m1b.find()) {
            String nm = m1b.group("cardname").trim();
            if (nm.equalsIgnoreCase(source.name()))
                return ctx -> ctx.logEntry(nm + " — cannot attack (permanent field restriction)");
        }
        // 2. Conditional: "If you don't control Card Name X Forward, [subject] cannot attack or block."
        Matcher m2 = IF_DONT_CONTROL_CARD_NAME_FWD_CANNOT_ATTACK_OR_BLOCK.matcher(text);
        if (m2.find()) {
            String subject  = m2.group("subject").trim();
            String required = m2.group("required").trim();
            if (subject.equalsIgnoreCase(source.name()))
                return ctx -> ctx.logEntry(subject + " — cannot attack or block unless you control Card Name " + required + " Forward");
        }
        // 3. Counter-conditional: "If N or less [Name] Counters are placed on [target], [subject] cannot attack or block."
        Matcher m3 = IF_COUNTER_LIMIT_CANNOT_ATTACK_OR_BLOCK.matcher(text);
        if (m3.find()) {
            String subject     = m3.group("subject").trim();
            String counterName = m3.group("countername").trim();
            int    limit       = Integer.parseInt(m3.group("count"));
            if (subject.equalsIgnoreCase(source.name()))
                return ctx -> ctx.logEntry(subject + " — cannot attack or block if " + counterName + " Counters ≤ " + limit);
        }
        // 4. Opponent-no-forwards: "If your opponent doesn't control any Forwards, [CardName] cannot attack."
        Matcher m4 = IF_OPP_NO_FORWARDS_CANNOT_ATTACK.matcher(text);
        if (m4.find()) {
            String subject = m4.group("subject").trim();
            if (subject.equalsIgnoreCase(source.name()))
                return ctx -> ctx.logEntry(subject + " — cannot attack if opponent controls no Forwards");
        }
        return null;
    }

    /**
     * Recognizes "Players cannot cast Summons." as a known passive field ability.
     * Returns a no-op consumer (the restriction is enforced statically by {@link MainWindow}).
     */
    private static Consumer<GameContext> tryParsePlayerCannotCastSummons(String text) {
        if (!PLAYERS_CANNOT_CAST_SUMMONS.matcher(text.trim()).matches()) return null;
        return ctx -> ctx.logEntry("Static: Players cannot cast Summons");
    }

    /**
     * Returns {@code true} if the given card has a "Players cannot cast Summons." field ability,
     * meaning all Summon casting (hand or break zone) is prohibited while it is on the field.
     */
    /** Returns {@code true} if the effect text matches a "cancel 1 auto-ability" summon effect. */
    public static boolean cancelsAutoAbility(String effectText) {
        return CANCEL_AUTO_ABILITY_DAMAGE_IF_FORWARD.matcher(effectText.trim()).find();
    }

    public static boolean hasPlayerCannotCastSummonsFieldAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            if (PLAYERS_CANNOT_CAST_SUMMONS.matcher(fa.effectText().trim()).matches()) return true;
        }
        return false;
    }

    /** Returns {@code true} if the card has the "BZ Summons cannot be removed by opponent" field ability. */
    public static boolean hasBzSummonRfgProtection(CardData card) {
        for (FieldAbility fa : card.fieldAbilities())
            if (FA_BZ_SUMMONS_PROTECTED_FROM_OPP_RFG.matcher(fa.effectText()).find()) return true;
        return false;
    }

    /**
     * Returns {@code true} if the card has a field ability of the form
     * "[CardName] cannot be chosen by Summons." — i.e., a permanent self-targeting
     * immunity to any Summon while the card is on the field.
     */
    static boolean hasCannotBeChosenByAnySummonFieldAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            Matcher m = STANDALONE_NAMED_CANNOT_BE_CHOSEN_ANY_SUMMON.matcher(fa.effectText());
            if (m.find() && m.group("name").trim().equalsIgnoreCase(card.name())) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if the card has a field ability of the form
     * "[CardName] cannot be chosen by Summons or abilities that share its Element."
     * Immunity is evaluated dynamically against the resolving card's element.
     */
    static boolean hasCannotBeChosenByOwnElementFieldAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            Matcher m = STANDALONE_NAMED_CANNOT_BE_CHOSEN_BY_OWN_ELEMENT.matcher(fa.effectText());
            if (m.find() && m.group("name").trim().equalsIgnoreCase(card.name())) return true;
        }
        return false;
    }

    /**
     * Parses "At the beginning of your Main Phase 1, &lt;effect&gt;" — a recurring
     * field-ability trigger.  Strips the trigger prefix and dispatches the inner effect
     * through the full {@link #parse} chain so any supported effect can follow.
     * {@code fireFieldMainPhase1Abilities} is responsible for invoking it each Main Phase 1 start.
     */
    static Consumer<GameContext> tryParseBeginningOfMainPhase1FieldAbility(String text, CardData source) {
        Matcher m = AT_BEGINNING_OF_MAIN_PHASE_1_FA_PATTERN.matcher(text);
        if (!m.find()) return null;
        return parse(m.group("inner").trim(), source);
    }

    /**
     * Parses "At the beginning of your Main Phase 2, &lt;effect&gt;" — same as
     * {@link #tryParseBeginningOfMainPhase1FieldAbility} but for Main Phase 2.
     */
    static Consumer<GameContext> tryParseBeginningOfMainPhase2FieldAbility(String text, CardData source) {
        Matcher m = AT_BEGINNING_OF_MAIN_PHASE_2_FA_PATTERN.matcher(text);
        if (!m.find()) return null;
        return parse(m.group("inner").trim(), source);
    }

    /**
     * Parses "Each turn, at the beginning of Main Phase 1, &lt;effect&gt;" — fires at the start of
     * BOTH players' Main Phase 1 for all cards the controller has on the field.
     * {@code fireFieldMainPhase1EachTurnAbilities} is responsible for invoking it.
     */
    static Consumer<GameContext> tryParseBeginningOfMainPhase1EachTurnFieldAbility(String text, CardData source) {
        Matcher m = AT_BEGINNING_OF_MAIN_PHASE_1_EACH_TURN_FA_PATTERN.matcher(text);
        if (!m.find()) return null;
        return parse(m.group("inner").trim(), source);
    }

    /**
     * Parses "At the end of your opponent's turn, &lt;effect&gt;" — fires when the controlling
     * player's opponent ends their turn.
     * {@code fireFieldEndOfOpponentTurnAbilities} is responsible for invoking it.
     */
    static Consumer<GameContext> tryParseEndOfOpponentTurnFieldAbility(String text, CardData source) {
        Matcher m = AT_END_OF_OPP_TURN_FA_PATTERN.matcher(text);
        if (!m.find()) return null;
        return parse(m.group("inner").trim(), source);
    }

    /**
     * Parses "select 1 Element. &lt;CardName&gt; becomes that Element[.]" — the named card's
     * element is permanently overridden via {@link GameContext#setCardElement}.  Returns
     * {@code null} unless {@code source} is non-null and its name equals the captured name,
     * preventing accidental matches when this parser appears in the general {@link #parse} chain.
     */
    static Consumer<GameContext> tryParseElementChange(String text, CardData source) {
        Matcher m = ELEMENT_CHANGE_PATTERN.matcher(text);
        if (!m.find()) return null;
        String cardName = m.group("name").trim();
        if (source == null || !cardName.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            String elem = ctx.selectElement("Select 1 Element (" + cardName + " becomes that Element):");
            if (elem != null) ctx.setCardElement(cardName, elem);
        };
    }

    private static Consumer<GameContext> tryParseGrantPartyAnyElementThisTurn(String text) {
        if (!GRANT_PARTY_ANY_ELEMENT_THIS_TURN.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Forwards you control can form a party with Forwards of any Element this turn");
            ctx.grantForwardsPartyAnyElementThisTurn();
        };
    }

    private static Consumer<GameContext> tryParseNameJobOrElementAllForwardsBoost(String text) {
        Matcher m = NAME_JOB_OR_ELEMENT_ALL_FORWARDS_BOOST.matcher(text);
        if (!m.find()) return null;
        int amount = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: Name 1 Job or Element — all controlled Forwards +" + amount + " power and named until EOT");
            String[] choice = ctx.selectJobOrElement("Name 1 Job or 1 Element:");
            if (choice == null || choice[1] == null) return;
            ctx.applyMassFieldPowerBoost(amount, true, false, false, true, null, -1, null, null, null);
            if ("job".equalsIgnoreCase(choice[0]))
                ctx.grantAllControlledForwardsJobUntilEOT(choice[1]);
            else
                ctx.grantAllControlledForwardsElementUntilEOT(choice[1]);
        };
    }

    private static Consumer<GameContext> tryParseNameJobOrCategoryRevealAddToHand(String text) {
        Matcher m = NAME_JOB_OR_CATEGORY_REVEAL_ADD_TO_HAND.matcher(text);
        if (!m.find()) return null;
        int reveal = Integer.parseInt(m.group("reveal"));
        int maxAdd = Integer.parseInt(m.group("maxAdd"));
        return ctx -> {
            ctx.logEntry("Effect: Name 1 Job or Category — reveal top " + reveal + ", add up to " + maxAdd + " matching Characters to hand");
            String[] choice = ctx.selectJobOrCategory("Name 1 Job or Category:");
            if (choice == null || choice[1] == null || choice[1].isBlank()) return;
            ctx.logEntry("Named " + choice[0] + ": " + choice[1]);
            String jobFilter = "job".equalsIgnoreCase(choice[0]) ? choice[1] : null;
            String catFilter = "category".equalsIgnoreCase(choice[0]) ? choice[1] : null;
            ctx.revealTopAddUpToMatchingRestBottom(reveal, maxAdd, jobFilter, catFilter, null, null);
        };
    }

    private static Consumer<GameContext> tryParseRevealTopNCategoryToHand(String text) {
        String s = stripRestrictionSentences(text);
        Matcher m = REVEAL_TOP_N_CATEGORY_TO_HAND.matcher(s.isEmpty() ? text : s);
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group("n"));
        String cat = m.group("cat");
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — add 1 Category " + cat + " to hand, rest to bottom");
            ctx.revealTopAddUpToMatchingRestBottom(n, 1, null, cat, null, null);
        };
    }

    /**
     * Parses "reveal the top N cards … Add 1 Job X [or Card Name Y] … bottom of your deck."
     * Splits the captured filter terms into a job filter and a card-name filter (each
     * bar-separated when multiple terms of the same kind appear) and forwards them to
     * {@link GameContext#revealTopAddUpToMatchingRestBottom}.
     */
    private static Consumer<GameContext> tryParseRevealTopNJobOrNameToHand(String text) {
        String s = stripRestrictionSentences(text);
        Matcher m = REVEAL_TOP_N_JOB_OR_NAME_TO_HAND.matcher(s.isEmpty() ? text : s);
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group("n"));
        StringBuilder jobs  = new StringBuilder();
        StringBuilder names = new StringBuilder();
        appendFilterTerm(jobs, names, m.group("first"));
        appendFilterTerm(jobs, names, m.group("second"));
        String jobFilter      = jobs.length()  > 0 ? jobs.toString()  : null;
        String cardNameFilter = names.length() > 0 ? names.toString() : null;
        if (jobFilter == null && cardNameFilter == null) return null;
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — add 1 ("
                    + (jobFilter      != null ? "Job " + jobFilter           : "")
                    + (jobFilter != null && cardNameFilter != null ? " | " : "")
                    + (cardNameFilter != null ? "Card Name " + cardNameFilter : "")
                    + ") to hand, rest to bottom");
            ctx.revealTopAddUpToMatchingRestBottom(n, 1, jobFilter, null, cardNameFilter, null);
        };
    }

    private static Consumer<GameContext> tryParseRevealTopNTypeToHand(String text) {
        String s = stripRestrictionSentences(text);
        Matcher m = REVEAL_TOP_N_TYPE_TO_HAND.matcher(s.isEmpty() ? text : s);
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group("n"));
        int max = Integer.parseInt(m.group("max"));
        String rawType = m.group("type");
        // Normalise plural → singular (e.g. "Monsters" → "Monster")
        String typeFilter = rawType.replaceAll("(?i)s$", "");
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — add up to " + max + " " + typeFilter + " to hand, rest to bottom");
            ctx.revealTopAddUpToMatchingRestBottom(n, max, null, null, null, typeFilter);
        };
    }

    private static Consumer<GameContext> tryParseRevealTopNTypeCostToHand(String text) {
        String s = stripRestrictionSentences(text);
        Matcher m = REVEAL_TOP_N_TYPE_COST_TO_HAND.matcher(s.isEmpty() ? text : s);
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group("n"));
        int max = Integer.parseInt(m.group("max"));
        String typeFilter = m.group("type").replaceAll("(?i)s$", "");
        int maxCost = Integer.parseInt(m.group("cost"));
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — add up to " + max + " " + typeFilter
                    + " of cost " + maxCost + " or less to hand, rest to bottom");
            ctx.revealTopAddUpToMatchingRestBottom(n, max, null, null, null, typeFilter, maxCost);
        };
    }

    private static Consumer<GameContext> tryParseRevealTopNElementToHand(String text) {
        String s = stripRestrictionSentences(text);
        Matcher m = REVEAL_TOP_N_ELEMENT_TO_HAND.matcher(s.isEmpty() ? text : s);
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group("n"));
        int max = Integer.parseInt(m.group("max"));
        String normElement = cap(m.group("element"));
        String typeRaw = m.group("type");
        String typeFilter = typeRaw != null ? cap(typeRaw.replaceAll("(?i)s$", "")) : null;
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — add up to " + max + " " + normElement
                    + (typeFilter != null ? " " + typeFilter : " card") + "(s) to hand, rest to bottom");
            ctx.revealTopAddUpToMatchingRestBottom(n, max, null, null, null, typeFilter, -1, normElement);
        };
    }

    private static Consumer<GameContext> tryParseRevealTopNAddUpToExcludingNameRestBz(String text) {
        Matcher m = REVEAL_TOP_N_ADD_UP_TO_EXCLUDING_NAME_REST_BZ.matcher(text.trim());
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group("n"));
        int max = Integer.parseInt(m.group("max"));
        String name = m.group("name").trim();
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — add up to " + max
                    + " (excl. Card Name " + name + ") to hand, rest to Break Zone");
            ctx.revealTopAddUpToExcludingNameRestBz(n, max, name);
        };
    }

    private static Consumer<GameContext> tryParseRevealAddTypeToHandOrPlayJobTypeOntoFieldRestBottom(String text) {
        Matcher m = REVEAL_ADD_TYPE_TO_HAND_OR_PLAY_JOB_TYPE_ONTO_FIELD_REST_BOTTOM.matcher(text.trim());
        if (!m.matches()) return null;
        int n        = Integer.parseInt(m.group("n"));
        int handMax  = Integer.parseInt(m.group("handmax"));
        String handType  = cap(m.group("handtype"));
        int fieldMax = Integer.parseInt(m.group("fieldmax"));
        String fieldJob  = m.group("fieldjob") != null ? m.group("fieldjob").trim() : null;
        String fieldType = cap(m.group("fieldtype"));
        String logDesc = "Reveal top " + n + " — add up to " + handMax + " " + handType
                + " to hand OR play up to " + fieldMax
                + (fieldJob != null ? " Job " + fieldJob + " " : " ") + fieldType + " onto field; rest to bottom";
        return ctx -> {
            ctx.logEntry("Effect: " + logDesc);
            ctx.revealTopNAddTypeToHandOrPlayJobTypeOntoFieldRestBottom(n, handMax, handType, fieldMax, fieldJob, fieldType);
        };
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    /** Appends {@code term} ("Job X" or "Card Name X") to the appropriate pipe-separated list. */
    private static void appendFilterTerm(StringBuilder jobs, StringBuilder names, String term) {
        if (term == null || term.isBlank()) return;
        String trimmed = term.trim();
        Matcher jm = Pattern.compile("(?i)^Job\\s+(?<val>.+)$").matcher(trimmed);
        Matcher nm = Pattern.compile("(?i)^Card\\s+Name\\s+(?<val>.+)$").matcher(trimmed);
        if (jm.matches()) {
            if (jobs.length() > 0)  jobs.append('|');
            jobs.append(jm.group("val").trim());
        } else if (nm.matches()) {
            if (names.length() > 0) names.append('|');
            names.append(nm.group("val").trim());
        }
    }

    private static Consumer<GameContext> tryParseNameJob(String text) {
        if (!NAME_JOB_STANDALONE.matcher(text.trim()).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Name 1 Job");
            String job = ctx.selectJobFromDatabase();
            if (job != null && !job.isBlank()) ctx.logEntry("Named Job: " + job);
        };
    }

    private static Consumer<GameContext> tryParseNameElementOnlySelfBecomes(String text, CardData source) {
        if (source == null) return null;
        Matcher m = NAME_ELEMENT_ONLY_SELF_BECOMES.matcher(text);
        if (!m.find()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        java.util.Set<String> excluded = parseExcludeElements(m.group("exclude"));
        return ctx -> {
            ctx.logEntry("Effect: Name 1 Element — " + source.name() + " becomes named Element until end of turn");
            String elem = ctx.selectElement("Name 1 Element (" + source.name() + " becomes it):", excluded);
            if (elem == null) return;
            ctx.changeSourceCardElementUntilEOT(source, elem);
        };
    }

    private static Consumer<GameContext> tryParseNameElementAndJobSelfBecomes(String text, CardData source) {
        if (source == null) return null;
        Matcher m = NAME_ELEMENT_AND_JOB_SELF_BECOMES.matcher(text);
        if (!m.find()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        java.util.Set<String> excluded = parseExcludeElements(m.group("exclude"));
        return ctx -> {
            ctx.logEntry("Effect: Name 1 Element and 1 Job — " + source.name() + " becomes both until end of turn");
            String[] choice = ctx.selectElementAndJob("Name 1 Element and 1 Job (" + source.name() + " becomes both):", excluded);
            if (choice == null || choice[0] == null || choice[1] == null) return;
            ctx.changeSourceCardElementAndJobUntilEOT(source, choice[0], choice[1]);
        };
    }

    private static Consumer<GameContext> tryParseNameJobAndElementSelfGainsPermanent(String text, CardData source) {
        if (source == null) return null;
        Matcher m = NAME_JOB_AND_ELEMENT_SELF_GAINS_PERMANENT.matcher(text);
        if (!m.find()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        java.util.Set<String> excluded = parseExcludeElements(m.group("exclude"));
        return ctx -> {
            ctx.logEntry("Effect: Name 1 Job and 1 Element — " + source.name() + " gains both permanently");
            String[] choice = ctx.selectElementAndJob("Name 1 Job and 1 Element (" + source.name() + " gains both):", excluded);
            if (choice == null || choice[0] == null || choice[1] == null) return;
            ctx.setCardElement(source.name(), choice[0]);
            ctx.addCardJobPermanently(source.name(), choice[1]);
        };
    }

    private static java.util.Set<String> parseExcludeElements(String excludeStr) {
        if (excludeStr == null || excludeStr.isBlank()) return java.util.Collections.emptySet();
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String part : excludeStr.split("(?i)\\s+(?:or|and)\\s+|,\\s*"))
            out.add(part.trim());
        return out;
    }

    /**
     * Parses standalone "negate all damage" effects:
     * <ul>
     *   <li>"Negate all damage dealt to all the Forwards you control."</li>
     *   <li>"Activate all the Forwards you control and negate all damage dealt to them."</li>
     * </ul>
     * Must be tried before {@link #tryParseAllFieldEffect} so the compound activate+negate form
     * is not swallowed by the simpler activate-all matcher.
     */
    private static Consumer<GameContext> tryParseNegateAllDamage(String text) {
        if (STANDALONE_ACTIVATE_AND_NEGATE_DAMAGE_OWN.matcher(text).find()) {
            return ctx -> {
                ctx.logEntry("Effect: Activate all own Forwards and negate their damage");
                ctx.applyMassFieldEffect(GameContext.MassAction.ACTIVATE,
                        true, false, false, false, true, null, -1, null, -1, null, null);
                ctx.negateAllDamageOwnForwards();
            };
        }
        if (STANDALONE_NEGATE_DAMAGE_OWN.matcher(text).find()) {
            return ctx -> {
                ctx.logEntry("Effect: Negate all damage on own Forwards");
                ctx.negateAllDamageOwnForwards();
            };
        }
        return null;
    }

    private static Consumer<GameContext> tryParsePlayerNextDamageZero(String text) {
        if (!PLAYER_NEXT_DAMAGE_ZERO.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: next damage to you becomes 0 this turn");
            ctx.shieldPlayerNextDamage();
        };
    }

    /**
     * Parses the three standalone damage-shield effects that apply globally or to a named card:
     * <ul>
     *   <li>Non-lethal protection for all active-player Forwards.</li>
     *   <li>Global incoming-damage reduction for all active-player Forwards.</li>
     *   <li>Nullify ability/Summon damage for a specific named Forward.</li>
     * </ul>
     */
    private static Consumer<GameContext> tryParseStandaloneDamageShields(String text, CardData source) {
        // "During this turn, if a Forward you control is dealt damage less than its power, the damage becomes 0 instead."
        if (STANDALONE_NONLETHAL_PROTECTION.matcher(text).find()) {
            return ctx -> {
                ctx.logEntry("Effect: Non-lethal protection for your Forwards this turn");
                ctx.shieldActivePlayerNonLethal();
            };
        }

        // "During this turn, if a Forward you control is dealt damage, reduce the damage by N instead."
        Matcher globalRedM = STANDALONE_GLOBAL_DMG_REDUCTION.matcher(text);
        if (globalRedM.find()) {
            int reduction = Integer.parseInt(globalRedM.group("reduction"));
            return ctx -> {
                ctx.logEntry("Effect: All your Forwards take " + reduction + " less damage this turn");
                ctx.shieldActivePlayerDamageReduction(reduction);
            };
        }

        // "During this turn, if <cardName> is dealt damage by your opponent's Summons or abilities, the damage becomes 0 instead."
        Matcher nullifyM = STANDALONE_NULLIFY_ABILITY_DAMAGE.matcher(text);
        if (nullifyM.find()) {
            String cardName = nullifyM.group("card").trim();
            return ctx -> {
                ctx.logEntry("Effect: " + cardName + " — ability damage nullified this turn");
                // Find the named forward on the active player's field
                for (int i = 0; i < ctx.p1ForwardCount(); i++) {
                    if (ctx.p1Forward(i).name().equalsIgnoreCase(cardName))
                        ctx.shieldAbilityDamage(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD));
                }
            };
        }

        // "The damage dealt to Forwards opponent controls cannot be reduced this turn."
        if (STANDALONE_DISABLE_OPPONENT_DMG_REDUCTION.matcher(text).find()) {
            return ctx -> {
                ctx.logEntry("Effect: Opponent's Forwards cannot benefit from damage reduction this turn");
                ctx.disableOpponentDamageReduction();
            };
        }

        // "This damage cannot be reduced." — modifier on a preceding damage sentence.
        // The actual unreduced routing is handled at the damage call site; this entry
        // prevents the "not yet implemented" log when it appears as a secondary followup.
        if (CANNOT_BE_REDUCED_PATTERN.matcher(text).find()) {
            return ctx -> {};
        }

        // "During this turn, the next damage dealt to [name] becomes 0 instead."
        Matcher namedZeroM = STANDALONE_SHIELD_NEXT_DMG_ZERO_NAMED.matcher(text);
        if (namedZeroM.find()) {
            String cardName = namedZeroM.group("name").trim();
            return ctx -> {
                ctx.logEntry("Effect: " + cardName + " — next damage becomes 0");
                boolean actorIsP1 = ctx.isP1();
                int ownCount = actorIsP1 ? ctx.p1ForwardCount() : ctx.p2ForwardCount();
                int oppCount = actorIsP1 ? ctx.p2ForwardCount() : ctx.p1ForwardCount();
                for (int i = 0; i < ownCount; i++) {
                    CardData c = actorIsP1 ? ctx.p1Forward(i) : ctx.p2Forward(i);
                    if (c.name().equalsIgnoreCase(cardName)) {
                        ctx.shieldNextIncomingDamage(new ForwardTarget(actorIsP1, i, ForwardTarget.CardZone.FORWARD));
                        return;
                    }
                }
                for (int i = 0; i < oppCount; i++) {
                    CardData c = actorIsP1 ? ctx.p2Forward(i) : ctx.p1Forward(i);
                    if (c.name().equalsIgnoreCase(cardName)) {
                        ctx.shieldNextIncomingDamage(new ForwardTarget(!actorIsP1, i, ForwardTarget.CardZone.FORWARD));
                        return;
                    }
                }
                ctx.logEntry("[Warning] " + cardName + " not found on field for next-damage-zero shield");
            };
        }

        // "During this turn, the next damage dealt to [name] is reduced by N instead."
        Matcher namedRedM = STANDALONE_SHIELD_NEXT_DMG_REDUCTION_NAMED.matcher(text);
        if (namedRedM.find()) {
            String cardName = namedRedM.group("name").trim();
            int reduction   = Integer.parseInt(namedRedM.group("reduction"));
            return ctx -> {
                ctx.logEntry("Effect: " + cardName + " — next damage reduced by " + reduction);
                boolean actorIsP1 = ctx.isP1();
                int ownCount  = actorIsP1 ? ctx.p1ForwardCount() : ctx.p2ForwardCount();
                int oppCount  = actorIsP1 ? ctx.p2ForwardCount() : ctx.p1ForwardCount();
                for (int i = 0; i < ownCount; i++) {
                    CardData c = actorIsP1 ? ctx.p1Forward(i) : ctx.p2Forward(i);
                    if (c.name().equalsIgnoreCase(cardName)) {
                        ctx.shieldNextIncomingDamageReduction(
                                new ForwardTarget(actorIsP1, i, ForwardTarget.CardZone.FORWARD), reduction);
                        return;
                    }
                }
                for (int i = 0; i < oppCount; i++) {
                    CardData c = actorIsP1 ? ctx.p2Forward(i) : ctx.p1Forward(i);
                    if (c.name().equalsIgnoreCase(cardName)) {
                        ctx.shieldNextIncomingDamageReduction(
                                new ForwardTarget(!actorIsP1, i, ForwardTarget.CardZone.FORWARD), reduction);
                        return;
                    }
                }
                ctx.logEntry("[Warning] " + cardName + " not found on field for damage reduction");
            };
        }

        return null;
    }

    private static Consumer<GameContext> tryParseGainCrystal(String text) {
        Matcher m = GAIN_CRYSTAL.matcher(text);
        if (!m.find()) return null;
        String crystalRun = m.group("crystals");
        int count = (crystalRun.length()) / "《C》".length();
        return ctx -> {
            ctx.logEntry("Effect: Gain " + count + " Crystal(s)");
            ctx.gainCrystal(count);
        };
    }

    private static Consumer<GameContext> tryParseGainCrystalIfOpponentHas(String text) {
        if (!GAIN_CRYSTAL_IF_OPPONENT_HAS.matcher(text).find()) return null;
        return ctx -> {
            int opp = ctx.opponentCrystalCount();
            if (opp <= 0) return;
            ctx.logEntry("Effect: Opponent has " + opp + " 《C》 — gain 1 Crystal");
            ctx.gainCrystal(1);
        };
    }

    /**
     * Parses "Choose 1 Forward opponent controls. [Name] gains its Special Ability until the end of the turn.
     * You can use this ability without paying any cost but only once."
     * Copies every isSpecial() ability from the chosen Forward to {@code source} as a free, once-per-turn
     * temp ability (all costs removed) that expires at end of turn.
     */
    private static Consumer<GameContext> tryParseChooseOppFwdGainsSpecialAbilityFreeOnce(
            String text, CardData source) {
        Matcher m = CHOOSE_OPP_FWD_GAINS_SPECIAL_ABILITY_FREE_ONCE.matcher(text.trim());
        if (!m.matches()) return null;
        String logName = m.group("sourceName");
        return ctx -> {
            ctx.logEntry(logName + " — Choose 1 Forward opponent controls to copy its Special Ability");
            List<ForwardTarget> ts = selectTargets(ctx, 1, false, true, false,
                    null, null, null, false, -1, null, -1, null,
                    true, false, false, null, null, null, null, false, null, false);
            if (ts.isEmpty()) return;
            ForwardTarget t = ts.get(0);
            CardData chosen = t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx());
            if (chosen == null) return;
            List<ActionAbility> specials = chosen.actionAbilities().stream()
                    .filter(ActionAbility::isSpecial)
                    .collect(java.util.stream.Collectors.toList());
            if (specials.isEmpty()) {
                ctx.logEntry(chosen.name() + " has no Special Ability to copy");
                return;
            }
            for (ActionAbility original : specials)
                ctx.grantCopiedSpecialAbilityFreeOnce(source, original);
        };
    }

    /**
     * Parses "Choose 1 Forward opponent controls which has been dealt damage this turn.
     * If that Forward has a special ability or an action ability, break it."
     */
    private static Consumer<GameContext> tryParseChooseOppDamagedFwdIfHasAbilityBreak(String text) {
        if (!CHOOSE_OPP_DAMAGED_FWD_IF_HAS_ABILITY_BREAK.matcher(text.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Choose 1 damaged opponent Forward — break if has special/action ability");
            List<ForwardTarget> ts = selectTargets(ctx, 1, false, true, false,
                    "damaged", null, null, false, -1, null, -1, null,
                    true, false, false, null, null, null, null, false, null, false);
            if (ts.isEmpty()) return;
            ForwardTarget t = ts.get(0);
            CardData chosen = t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx());
            if (chosen == null) return;
            if (chosen.actionAbilities().isEmpty()) {
                ctx.logEntry(chosen.name() + " has no special/action ability — not broken");
            } else {
                ctx.breakTarget(t);
            }
        };
    }

    /**
     * Parses "Choose as many [Type] [opponent controls] as [the] [CountSource] you control. [Dull/Activate] them."
     * The count is computed at resolution time from the acting player's field cards matching the count source.
     */
    private static Consumer<GameContext> tryParseChooseAsManyAsFieldCount(String text, CardData source) {
        Matcher m = CHOOSE_AS_MANY_AS_FIELD_COUNT.matcher(text.trim());
        if (!m.matches()) return null;

        String targetTypeRaw = m.group("targetType").trim();
        String targetSide    = m.group("targetSide");
        String countSrc      = m.group("countSrc").trim();
        String followupText  = m.group("followup").trim();

        String tgtLow = targetTypeRaw.toLowerCase();
        boolean inclForwards = tgtLow.startsWith("forward") || tgtLow.startsWith("character");
        boolean inclBackups  = tgtLow.startsWith("backup")  || tgtLow.startsWith("character");
        boolean inclMonsters = tgtLow.startsWith("monster") || tgtLow.startsWith("character");

        boolean opponentOnly = targetSide != null && targetSide.toLowerCase().contains("opponent");
        boolean selfOnly     = !opponentOnly;

        String  countJobFilter = null;
        String  countCatFilter = null;
        boolean countFwds = true, countBkps = true, countMons = true;

        Matcher jbm = JOB_BRACKET_PATTERN.matcher(countSrc);
        if (jbm.find()) {
            countJobFilter = jbm.group(1).trim();
        } else if (countSrc.toLowerCase().startsWith("category ")) {
            String rest = countSrc.substring("category ".length()).trim();
            int sp = rest.indexOf(' ');
            if (sp >= 0) {
                countCatFilter = rest.substring(0, sp);
                String csType = rest.substring(sp + 1).trim().toLowerCase();
                countFwds = csType.startsWith("forward") || csType.startsWith("character");
                countBkps = csType.startsWith("backup")  || csType.startsWith("character");
                countMons = csType.startsWith("monster") || csType.startsWith("character");
            } else {
                countCatFilter = rest;
            }
        } else if (countSrc.toLowerCase().startsWith("job ")) {
            String rest = countSrc.substring("job ".length()).trim();
            countJobFilter = rest.replaceAll("(?i)\\s+(Forwards?|Backups?|Monsters?|Characters?)\\s*$", "").trim();
        } else {
            String csTypeLow = countSrc.toLowerCase().replaceAll("s$", "");
            if (csTypeLow.equals("forward") || csTypeLow.equals("backup")
                    || csTypeLow.equals("monster") || csTypeLow.equals("character")) {
                countFwds = csTypeLow.equals("forward") || csTypeLow.equals("character");
                countBkps = csTypeLow.equals("backup")  || csTypeLow.equals("character");
                countMons = csTypeLow.equals("monster") || csTypeLow.equals("character");
            } else {
                return null;
            }
        }

        boolean doActivate = FOLLOWUP_ACTIVATE.matcher(followupText).find();
        boolean doDull     = FOLLOWUP_DULL.matcher(followupText).find();
        boolean doFreeze   = !doActivate && !doDull && FOLLOWUP_FREEZE.matcher(followupText).find();
        if (!doActivate && !doDull && !doFreeze) return null;

        final String  fJob = countJobFilter, fCat = countCatFilter;
        final boolean fCFwds = countFwds, fCBkps = countBkps, fCMons = countMons;
        final boolean fOppOnly = opponentOnly, fSelfOnly = selfOnly;
        final boolean fFwds = inclForwards, fBkps = inclBackups, fMons = inclMonsters;
        final String  action = doActivate ? "Activate" : doDull ? "Dull" : "Freeze";
        final String  logPfx = "Choose up to as many " + targetTypeRaw
                + (targetSide != null ? " " + targetSide : " you control")
                + " as " + countSrc + " you control";

        return ctx -> {
            int count = ctx.countSelfFieldCards(fCFwds, fCBkps, fCMons, fJob, null, fCat);
            if (count <= 0) {
                ctx.logEntry(logPfx + " — count=0, nothing to choose");
                ctx.markEffectFizzled();
                return;
            }
            ctx.logEntry(logPfx + " (count=" + count + ") — " + action);
            List<ForwardTarget> ts = selectTargets(ctx, count, true,
                    fOppOnly, fSelfOnly, null, null, null, false,
                    -1, null, -1, null,
                    fFwds, fBkps, fMons, null, null, null, null, false, null, false);
            if (doActivate) {
                sortedByIdxDesc(ts, true) .forEach(ctx::activateTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::activateTarget);
            } else if (doDull) {
                sortedByIdxDesc(ts, true) .forEach(ctx::dullTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::dullTarget);
            } else {
                sortedByIdxDesc(ts, true) .forEach(ctx::freezeTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::freezeTarget);
            }
        };
    }

    private static Consumer<GameContext> tryParseChooseCounterScaleCharsActivate(String text, int xValue) {
        Matcher m = CHOOSE_COUNTER_SCALE_CHARS_ACTIVATE.matcher(text);
        if (!m.find()) return null;
        final int    count       = xValue;
        final String counterName = m.group("counterName").trim();
        return ctx -> {
            if (count <= 0) {
                ctx.logEntry("Effect: " + counterName + " Counter choose/activate — 0 counters, nothing to do");
                return;
            }
            ctx.logEntry("Effect: Choose up to " + count + " Characters (" + counterName + " Counters) — Activate");
            List<ForwardTarget> ts = selectTargets(ctx, count, true,
                    false, true, null, null, null, false,
                    -1, null, -1, null,
                    true, true, true, null, null, null, null, false, null, false);
            sortedByIdxDesc(ts, true) .forEach(t -> ctx.activateTarget(t));
            sortedByIdxDesc(ts, false).forEach(t -> ctx.activateTarget(t));
        };
    }

    private static Consumer<GameContext> tryParseCounterScaleLookAddToHand(String text, int xValue) {
        Matcher m = LOOK_COUNTER_SCALE_ADD_TO_HAND_REST_BOTTOM.matcher(text);
        if (!m.find()) return null;
        final int    count       = xValue;
        final String counterName = m.group("counterName").trim();
        return ctx -> {
            if (count <= 0) {
                ctx.logEntry("Effect: " + counterName + " Counter look — 0 counters, nothing to do");
                return;
            }
            ctx.logEntry("Effect: Look at top " + count + " card(s) (" + counterName + " Counters) — add 1 to hand, shuffle rest to bottom");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.ADD_TO_HAND_REST_BOTTOM));
        };
    }

    private static Consumer<GameContext> tryParsePlaceCounters(String text, CardData source) {
        Matcher m = PLACE_COUNTERS.matcher(text);
        if (!m.find()) return null;
        int    count      = Integer.parseInt(m.group("count"));
        String name       = m.group("name").trim();
        String target     = m.group("target").trim();
        // Only handle self-placement (target matches the source card's name)
        if (source == null || !source.name().equalsIgnoreCase(target)) return null;
        return ctx -> {
            ctx.logEntry("Effect: Place " + count + " " + name + " Counter(s) on " + source.name());
            ctx.placeCounters(source, name, count);
        };
    }

    private static Consumer<GameContext> tryParsePlaceCountersForEach(String text, CardData source) {
        Matcher m = PLACE_COUNTERS_FOR_EACH.matcher(text.trim());
        if (!m.matches()) return null;
        int    baseCount  = Integer.parseInt(m.group("count"));
        String name       = m.group("name").trim();
        String target     = m.group("target").trim();
        if (source == null || !source.name().equalsIgnoreCase(target)) return null;
        String typeRaw    = m.group("type");
        String cardType   = Character.toUpperCase(typeRaw.charAt(0))
                + typeRaw.substring(1).toLowerCase().replaceAll("s$", "");
        return ctx -> {
            int total = baseCount * ctx.ownFieldCount(cardType);
            ctx.logEntry("Effect: Place " + baseCount + " " + name + " Counter(s) per " + cardType
                    + " you control (" + total + " total) on " + source.name());
            if (total > 0) ctx.placeCounters(source, name, total);
        };
    }

    private static Consumer<GameContext> tryParseLookTopDeckOptionallyBreak(String text) {
        if (!LOOK_TOP_DECK_OPTIONALLY_BREAK.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Look at top of deck — may put into Break Zone");
            ctx.lookAtTopDeck(new LookConfig(1, LookConfig.LookAction.BREAK_OR_KEEP));
        };
    }

    private static Consumer<GameContext> tryParseLookTopDeckBottomOrKeep(String text) {
        if (!LOOK_TOP_DECK_BOTTOM_OR_KEEP.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Look at top of deck — may place at bottom");
            ctx.lookAtTopDeck(new LookConfig(1, LookConfig.LookAction.BOTTOM_OR_KEEP));
        };
    }

    private static Consumer<GameContext> tryParseLookTopDeckReturnTopOrdered(String text) {
        Matcher m = LOOK_TOP_DECK_RETURN_TOP_ORDERED.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) — return to top in any order");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.RETURN_TOP_ORDERED));
        };
    }

    private static Consumer<GameContext> tryParseLookTopDeckAddToHandRestBottom(String text) {
        Matcher m = LOOK_TOP_DECK_ADD_TO_HAND_REST_BOTTOM.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) — add 1 to hand, return rest to bottom");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.ADD_TO_HAND_REST_BOTTOM));
        };
    }

    private static Consumer<GameContext> tryParseLookTopDeckAddToHandOneToBreakRestBottom(String text) {
        Matcher m = LOOK_TOP_DECK_ADD_TO_HAND_ONE_TO_BREAK_REST_BOTTOM.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) — add 1 to hand, 1 to Break Zone, return rest to bottom");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.ADD_TO_HAND_ONE_TO_BREAK_REST_BOTTOM));
        };
    }

    private static Consumer<GameContext> tryParseLookTopDeckAddToHandRestBreak(String text) {
        Matcher m = LOOK_TOP_DECK_ADD_TO_HAND_REST_BREAK.matcher(text);
        if (!m.find()) return null;
        int    count   = Integer.parseInt(m.group("count"));
        String element = m.group("element");
        String elemLabel = element != null ? " (" + element + ")" : "";
        return ctx -> {
            ctx.logEntry("Effect: Look/Reveal top " + count + " card(s) — add 1" + elemLabel + " to hand, rest to Break Zone");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.ADD_TO_HAND_REST_BREAK, element));
        };
    }

    private static Consumer<GameContext> tryParseLookTopDeckTopOrBottom(String text) {
        Matcher m = LOOK_TOP_DECK_TOP_OR_BOTTOM.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) — return to top or bottom in any order");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.TOP_OR_BOTTOM_ORDERED));
        };
    }

    private static Consumer<GameContext> tryParseLookTopDeckPickOneTopRestBottom(String text) {
        Matcher m = LOOK_TOP_DECK_PICK_ONE_TOP_REST_BOTTOM.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) — pick 1 on top, rest to bottom");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.PICK_ONE_TOP_REST_BOTTOM));
        };
    }

    private static Consumer<GameContext> tryParseLookTopDeckPeek(String text) {
        Matcher m = LOOK_TOP_DECK_PEEK.matcher(text);
        if (!m.find()) return null;
        String countStr = m.group("count");
        int count = (countStr != null) ? Integer.parseInt(countStr) : 1;
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) of deck");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.PEEK));
        };
    }

    private static Consumer<GameContext> tryParseRevealTopBreakSameCostAddToHand(String text) {
        if (!REVEAL_TOP_BREAK_SAME_COST_ADD_TO_HAND.matcher(text.trim()).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Reveal top of deck — break all opponent Forwards with same cost, add revealed card to hand");
            ctx.revealTopBreakSameCostAddToHand();
        };
    }

    private static Consumer<GameContext> tryParseLookTopDeckCastSummonFreeRestBottom(String text, int xValue) {
        Matcher m = LOOK_TOP_DECK_CAST_SUMMON_FREE_REST_BOTTOM.matcher(text.trim());
        if (!m.find()) return null;
        String countStr = m.group("count");
        String costStr  = m.group("cost");
        final int count   = countStr.equalsIgnoreCase("X") ? xValue : Integer.parseInt(countStr);
        final int maxCost = costStr.equalsIgnoreCase("X")  ? xValue : Integer.parseInt(costStr);
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) — reveal/cast 1 Summon (cost " + maxCost + " or less) for free, shuffle rest to bottom");
            ctx.lookAtTopDeckCastSummonFreeRestBottom(count, maxCost);
        };
    }

    private static Consumer<GameContext> tryParseRemoveTopOfDeckFromGame(String text) {
        Matcher m = REMOVE_TOP_OF_DECK_FROM_GAME.matcher(text);
        if (!m.find()) return null;
        String countStr = m.group("count");
        int count = (countStr != null) ? Integer.parseInt(countStr) : 1;
        return ctx -> {
            ctx.logEntry("Effect: Remove top " + count + " card(s) of deck from game");
            ctx.removeTopCardsOfDeckFromGame(count);
        };
    }

    private static Consumer<GameContext> tryParseShuffleDeck(String text) {
        if (!SHUFFLE_DECK.matcher(text).find()) return null;
        return ctx -> ctx.shuffleDeck();
    }

    private static Consumer<GameContext> tryParseBackupCpDraw(String text) {
        Matcher m = BACKUP_CP_DRAW.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            if (ctx.castWasPaidByBackupsOnly()) {
                ctx.logEntry("BackupCpDraw — CP was only from Backups, draw " + count);
                ctx.drawCards(count);
            }
        };
    }

    private static Consumer<GameContext> tryParseAllMonstersTemporaryForward(String text) {
        Matcher m = ALL_MONSTERS_BECOME_FORWARDS_UNTIL_EOT_PATTERN.matcher(text.trim());
        if (!m.find()) return null;
        int power = Integer.parseInt(m.group("power"));
        return ctx -> {
            ctx.logEntry("Effect: All Monsters you control become Forwards with " + power + " power until end of turn");
            ctx.makeAllMonstersTemporaryForwards(power);
        };
    }

    private static Consumer<GameContext> tryParseBecomeForwardUntilEot(String text, CardData source) {
        if (source == null) return null;

        Matcher mAtk = BECOME_FORWARD_AND_ATTACK_TRIGGER.matcher(text);
        if (mAtk.find()) {
            int power = Integer.parseInt(mAtk.group("power"));
            String attackEffectText = mAtk.group("attackEffect").trim();
            Consumer<GameContext> attackEffect = parse(attackEffectText, source);
            if (attackEffect != null) {
                return ctx -> {
                    ctx.logEntry(source.name() + " becomes a Forward with " + power + " power until end of turn");
                    ctx.makeMonsterTemporaryForward(source, power);
                    ctx.logEntry(source.name() + " gains 'When attacks: " + attackEffectText + "'");
                    ctx.addTempAttackTrigger(source, attackEffect);
                };
            }
        }

        Matcher mBlk = BECOME_FORWARD_AND_BLOCK_TRIGGER.matcher(text);
        if (mBlk.find()) {
            int power = Integer.parseInt(mBlk.group("power"));
            String blockEffectText = mBlk.group("blockEffect").trim();
            Consumer<GameContext> blockEffect = parse(blockEffectText, source);
            if (blockEffect != null) {
                return ctx -> {
                    ctx.logEntry(source.name() + " becomes a Forward with " + power + " power until end of turn");
                    ctx.makeMonsterTemporaryForward(source, power);
                    ctx.logEntry(source.name() + " gains 'When blocks: " + blockEffectText + "'");
                    ctx.addTempBlockTrigger(source, blockEffect);
                };
            }
        }

        Matcher mBz = BECOME_FORWARD_AND_BZ_ACTION.matcher(text);
        if (mBz.find()) {
            int power = Integer.parseInt(mBz.group("power"));
            String bzName = mBz.group("bzName").trim();
            String bzEffectText = mBz.group("bzEffect").trim();
            if (parse(bzEffectText, source) != null) {
                return ctx -> {
                    ctx.logEntry(source.name() + " becomes a Forward with " + power + " power until end of turn");
                    ctx.makeMonsterTemporaryForward(source, power);
                    ctx.grantTempBzActionAbility(source, bzName, bzEffectText);
                };
            }
        }

        Matcher m = BECOME_FORWARD_UNTIL_EOT_PATTERN.matcher(text);
        if (!m.find()) return null;
        int power = Integer.parseInt(m.group("power"));
        boolean breakAtEot = AT_END_OF_TURN_BREAK_SOURCE.matcher(text).find();
        return ctx -> {
            ctx.logEntry(source.name() + " becomes a Forward with " + power + " power until end of turn");
            ctx.makeMonsterTemporaryForward(source, power);
            if (breakAtEot) ctx.breakSourceAtEndOfTurn(source);
        };
    }

    /** Returns {@code true} when {@code text} is an "until EOT, becomes a Forward" action-ability effect. */
    static boolean isBecomeForwardUntilEotEffect(String text, CardData source) {
        return tryParseBecomeForwardUntilEot(text, source) != null;
    }

    /**
     * Returns {@code true} when {@code text} is a standalone "source gains +N power until end of
     * turn" self-boost (named subject, not a pronoun like "it"/"they").  Used by the CPU to avoid
     * wasting hand cards on a power boost that provides no combat benefit.
     */
    static boolean isTempSelfPowerBoostEffect(String text, CardData source) {
        if (source == null) return false;
        Matcher m = SELF_POWER_BOOST.matcher(text);
        if (!m.find()) return false;
        String subject = m.group("selfsubject").trim();
        if (subject.equalsIgnoreCase("it") || subject.equalsIgnoreCase("they")) return false;
        return subject.equalsIgnoreCase(source.name());
    }

    /** Returns true when {@code text} is a "gain 《C》 for each CP paid as X" effect. */
    static boolean isGainCrystalPerX(String text) {
        return GAIN_CRYSTAL_PER_X.matcher(text).find();
    }

    private static Consumer<GameContext> tryParseGainCrystalPerX(String text, int xValue) {
        if (!GAIN_CRYSTAL_PER_X.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Gain " + xValue + " Crystal(s) (for each CP paid as X)");
            ctx.gainCrystal(xValue);
        };
    }

    /**
     * Parses "Choose 1 Summon in your Break Zone. Add it to your hand. During this turn,
     * the cost required to cast your next Summon is reduced by N [(it cannot become 0)]."
     */
    private static Consumer<GameContext> tryParseChooseSummonFromBzToHandWithCostReduction(String text) {
        Matcher m = CHOOSE_SUMMON_FROM_BZ_TO_HAND_WITH_COST_REDUCTION.matcher(text);
        if (!m.find()) return null;
        int amount = Integer.parseInt(m.group("amount"));
        boolean floorAtOne = m.group("floorone") != null;
        CostReductionModifier modifier = new CostReductionModifier(
                amount, floorAtOne, true,
                false, false, false, true,
                null, null, null, null, false);
        String logDesc = "Choose 1 Summon from own Break Zone → hand; next Summon costs "
                + amount + " less" + (floorAtOne ? " (min 1)" : "");
        return ctx -> {
            ctx.logEntry("Effect: " + logDesc);
            ctx.chooseSummonFromOwnBzToHand();
            ctx.applyNextCastCostReduction(modifier);
        };
    }

    /** Parses "Choose N Summons in your Break Zone. Add 1 of them to your hand, and remove the rest from the game." */
    private static Consumer<GameContext> tryParseChooseNSummonsBzPickOneHandRestRfg(String text) {
        Matcher m = CHOOSE_N_SUMMONS_BZ_PICK_ONE_HAND_REST_RFG.matcher(text);
        if (!m.find()) return null;
        int total = Integer.parseInt(m.group("total"));
        return ctx -> {
            ctx.logEntry("Effect: Choose " + total + " Summons from own BZ — add 1 to hand, remove rest");
            ctx.chooseSummonsFromBzPickOneToHandRestRfg(total);
        };
    }

    /**
     * Parses "Choose 1 [Element] Summon in your Break Zone. You can cast it at any time
     * you could normally cast it this turn. The cost required to cast it is reduced by N."
     * At resolution: shows a chooser, moves the picked Summon BZ→hand, and registers a
     * cardname-targeted CostReductionModifier so the existing hand-cast path discounts it.
     */
    private static Consumer<GameContext> tryParseChooseSummonInBzCastable(String text) {
        Matcher m = CHOOSE_SUMMON_IN_BZ_CASTABLE.matcher(text);
        if (!m.find()) return null;
        final String element = m.group("element").trim();
        final int    amount  = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 " + element + " Summon in BZ — castable this turn (cost -" + amount + ")");
            ctx.chooseSummonInBzMakeCastable(element, amount);
        };
    }

    private static Consumer<GameContext> tryParseOppRfpTopDeckCastable(String text) {
        Matcher m = OPP_RFP_TOPDECK_CASTABLE.matcher(text);
        if (!m.find()) return null;
        String costClause = m.group("cost") != null ? m.group("cost") : "";
        Matcher r = Pattern.compile("(?i)reduced\\s+by\\s+(\\d+)").matcher(costClause);
        final int reduction = r.find() ? Integer.parseInt(r.group(1)) : 0;
        final boolean anyElement = costClause.toLowerCase(java.util.Locale.ROOT).contains("any element");
        return ctx -> {
            ctx.logEntry("Effect: Opponent removes top deck card from game — you may cast it as your own"
                    + (reduction > 0 ? " (cost -" + reduction + ")" : "")
                    + (anyElement ? " [any Element]" : ""));
            ctx.opponentRfpTopDeckMakeCastable(reduction, anyElement);
        };
    }

    private static Consumer<GameContext> tryParseChooseFromOppBzCastable(String text) {
        Matcher m = CHOOSE_FROM_OPP_BZ_CASTABLE.matcher(text);
        if (!m.find()) return null;
        String t = m.group("type").toLowerCase(java.util.Locale.ROOT);
        final boolean inclForwards = t.startsWith("forward") || t.startsWith("character");
        final boolean inclBackups  = t.startsWith("backup")  || t.startsWith("character");
        final boolean inclMonsters = t.startsWith("monster") || t.startsWith("character");
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 " + t + " in opponent's BZ, remove from game — castable as your own");
            ctx.chooseFromOpponentBzMakeCastable(inclForwards, inclBackups, inclMonsters);
        };
    }

    private static Consumer<GameContext> tryParseChooseSummonsFromBzCastable(String text) {
        Matcher mg = CHOOSE_SUMMONS_FROM_BZ_GAME.matcher(text);
        if (mg.find()) {
            final int count = Integer.parseInt(mg.group("count"));
            final boolean eitherBz = !mg.group("scope").toLowerCase(java.util.Locale.ROOT).equals("your");
            return ctx -> {
                ctx.logEntry("Effect: Choose " + count + " Summon(s) from BZ, remove from game — castable as your own this game");
                ctx.chooseSummonsFromBzMakeCastable(count, eitherBz, false, false, false);
            };
        }
        Matcher mt = CHOOSE_SUMMONS_FROM_BZ_TURN.matcher(text);
        if (mt.find()) {
            final int count = Integer.parseInt(mt.group("count"));
            final boolean eitherBz = !mt.group("scope").toLowerCase(java.util.Locale.ROOT).equals("your");
            String rfgClause = mt.group("rfg") != null ? mt.group("rfg").toLowerCase(java.util.Locale.ROOT) : "";
            final boolean rfgAfterUse = rfgClause.contains("after use");
            return ctx -> {
                ctx.logEntry("Effect: Choose " + count + " Summon(s) from BZ — castable as your own this turn"
                        + (rfgAfterUse ? " (removed from game after use)" : ""));
                ctx.chooseSummonsFromBzMakeCastable(count, eitherBz, true, rfgAfterUse, false);
            };
        }
        return null;
    }

    private static Consumer<GameContext> tryParseChooseSummonInBzMaxCostFreeCastRfg(String text) {
        Matcher m = CHOOSE_SUMMON_IN_BZ_MAX_COST_FREE_CAST_RFG.matcher(text);
        if (!m.find()) return null;
        final int maxCost = Integer.parseInt(m.group("cost"));
        return ctx -> {
            ctx.logEntry("Effect: Choose Summon (cost ≤ " + maxCost + ") from BZ — cast free, RFG after use");
            ctx.chooseSummonInBzByMaxCostFreeCastRfgAfterUse(maxCost);
        };
    }

    private static Consumer<GameContext> tryParseCostReductionThisTurn(String text) {
        Matcher m = COST_REDUCTION_THIS_TURN.matcher(text);
        if (!m.find()) return null;

        String elementRaw  = m.group("element");
        String categoryRaw = m.group("category");
        // Combined "Job X or Card Name Y" case
        String jobOrRaw    = m.group("joborg");
        String cnameOrRaw  = m.group("cnameborg");
        boolean jobOrName  = jobOrRaw != null;
        String jobRaw      = jobOrName ? jobOrRaw    : m.group("job");
        String cardnameRaw = jobOrName ? cnameOrRaw  : m.group("cardname");
        String typeRaw     = m.group("type");
        int    amount      = Integer.parseInt(m.group("amount"));
        boolean floorAtOne = m.group("floorone") != null;

        boolean inclForwards, inclBackups, inclMonsters, inclSummons;
        if (cardnameRaw != null) {
            inclForwards = inclBackups = inclMonsters = inclSummons = true;
        } else {
            String t = typeRaw != null ? typeRaw.toLowerCase(java.util.Locale.ROOT) : "card";
            inclForwards = t.matches("forwards?|characters?|card");
            inclBackups  = t.matches("backups?|characters?|card");
            inclMonsters = t.matches("monsters?|characters?|card");
            inclSummons  = t.matches("summons?|card");
        }

        final String element  = elementRaw  != null ? elementRaw.trim()  : null;
        final String category = categoryRaw != null ? categoryRaw.trim() : null;
        final String job      = jobRaw      != null ? jobRaw.trim()      : null;
        final String cardname = cardnameRaw != null ? cardnameRaw.trim() : null;
        final String typeDesc = jobOrName   ? "or Card Name " + cardname
                              : cardname    != null ? "Card Name " + cardname
                              : typeRaw     != null ? typeRaw : "card";

        CostReductionModifier modifier = new CostReductionModifier(
                amount, floorAtOne, true,
                inclForwards, inclBackups, inclMonsters, inclSummons,
                element, job, cardname, category, jobOrName);

        String logDesc = "During this turn, next "
                + (element  != null ? element + " " : "")
                + (category != null ? "Category " + category + " " : "")
                + (job      != null ? "Job " + job + " " : "")
                + typeDesc + " costs " + amount + " less" + (floorAtOne ? " (min 1)" : "");

        return ctx -> {
            ctx.logEntry("Effect: " + logDesc);
            ctx.applyNextCastCostReduction(modifier);
        };
    }

    private static Consumer<GameContext> tryParsePlayCostReductionThisTurn(String text) {
        Matcher m = PLAY_COST_REDUCTION_THIS_TURN.matcher(text);
        if (!m.find()) return null;

        String elementRaw  = m.group("element");
        String categoryRaw = m.group("category");
        String jobRaw      = m.group("job");
        String cardnameRaw = m.group("cardname");
        String typeRaw     = m.group("type");
        int    amount      = Integer.parseInt(m.group("amount"));
        boolean floorAtOne = m.group("floorone") != null;

        boolean inclForwards, inclBackups, inclMonsters;
        if (cardnameRaw != null) {
            inclForwards = inclBackups = inclMonsters = true;
        } else {
            String t = typeRaw != null ? typeRaw.toLowerCase(java.util.Locale.ROOT) : "characters";
            inclForwards = t.matches("forwards?|characters?");
            inclBackups  = t.matches("backups?|characters?");
            inclMonsters = t.matches("monsters?|characters?");
        }

        final String element  = elementRaw  != null ? elementRaw.trim()  : null;
        final String category = categoryRaw != null ? categoryRaw.trim() : null;
        final String job      = jobRaw      != null ? jobRaw.trim()      : null;
        final String cardname = cardnameRaw != null ? cardnameRaw.trim() : null;
        final String typeDesc = cardname != null ? "Card Name " + cardname
                              : typeRaw  != null ? typeRaw : "Characters";

        CostReductionModifier modifier = new CostReductionModifier(
                amount, floorAtOne, false,
                inclForwards, inclBackups, inclMonsters, false,
                element, job, cardname, category, false);

        String logDesc = "This turn, your "
                + (element  != null ? element + " " : "")
                + (category != null ? "Category " + category + " " : "")
                + (job      != null ? "Job " + job + " " : "")
                + typeDesc + " cost " + amount + " less to play onto the field"
                + (floorAtOne ? " (min 1)" : "");

        return ctx -> {
            ctx.logEntry("Effect: " + logDesc);
            ctx.applyNextCastCostReduction(modifier);
        };
    }

    private static Consumer<GameContext> tryParseExtraTurnThenLose(String text) {
        if (!EXTRA_TURN_THEN_LOSE.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Take 1 more turn — you lose at the end of that turn");
            ctx.takeExtraTurnThenLose();
        };
    }

    /**
     * Parses "Activate &lt;cardName&gt;[.]" — activates named card(s) the ability user controls.
     * Handles single plain names ("Activate <cardName>"), "Card Name X" notation, and
     * "Card Name X and Card Name Y [you control]" multi-target form.
     */
    private static Consumer<GameContext> tryParseActivateNamedCard(String text) {
        Matcher m = ACTIVATE_NAMED_CARD.matcher(text);
        if (!m.find()) return null;

        String raw = m.group("card").trim();
        // Strip optional trailing "you control"
        raw = raw.replaceAll("(?i)\\s+you\\s+control$", "").trim();

        // Build list of card names, handling "Card Name X [and Card Name Y]" form
        List<String> names = new ArrayList<>();
        if (raw.matches("(?i)Card\\s+Name.*")) {
            String[] parts = ACTIVATE_AND_CARD_NAME_SPLIT.split(raw);
            for (String part : parts)
                names.add(part.replaceAll("(?i)^Card\\s+Name\\s+", "").trim());
        } else {
            names.add(raw);
        }

        return ctx -> {
            ctx.logEntry("Effect: Activate " + String.join(", ", names));
            for (String name : names) {
                List<ForwardTarget> ts = ctx.selectCharacters(
                        1, false, false, true, null, null, -1, null, -1, null,
                        true, true, true, null, name, null, null, false, null, false);
                ts.forEach(ctx::activateTarget);
            }
        };
    }

    /** Parses "[name] can attack once more this turn." */
    private static Consumer<GameContext> tryParseAttackOnceMore(String text) {
        Matcher m = ATTACK_ONCE_MORE.matcher(text);
        if (!m.find()) return null;
        String name = m.group("name").trim();
        return ctx -> {
            ctx.logEntry("Effect: " + name + " can attack once more this turn");
            ctx.grantAttackOnceMore(name);
        };
    }

    /** Parses "During this turn, your opponent may only declare attack once." */
    private static Consumer<GameContext> tryParseOpponentAttackOnceThisTurn(String text) {
        if (!OPPONENT_ATTACK_ONCE_THIS_TURN.matcher(text).find()) return null;
        return ctx -> ctx.limitOpponentAttackDeclarationsThisTurn(1);
    }

    private static Consumer<GameContext> tryParseOpponentCannotSearchThisTurn(String text) {
        if (!OPPONENT_CANNOT_SEARCH_THIS_TURN.matcher(text).find()) return null;
        return ctx -> ctx.setOpponentCannotSearchThisTurn();
    }

    /**
     * Parses "Remove &lt;cardName&gt; from [the] Battle." — removes the named card from the current
     * combat before damage resolves (Escape-type ability).
     */
    private static Consumer<GameContext> tryParseRemoveFromBattle(String text) {
        Matcher m = REMOVE_FROM_BATTLE.matcher(text);
        if (!m.find()) return null;
        String cardName = m.group("card").trim();
        return ctx -> {
            ctx.logEntry("Effect: " + cardName + " escapes from the Battle");
            ctx.removeFromBattle(cardName);
        };
    }

    private static Consumer<GameContext> tryParseDualSearchJobAndTypeDontShareElements(String text) {
        Matcher m = DUAL_SEARCH_JOB_AND_TYPE_DONT_SHARE_ELEMENTS.matcher(text);
        if (!m.find()) return null;
        String job  = m.group("job").trim();
        String type = m.group("type").trim();
        return ctx -> {
            ctx.logEntry("Effect: Dual search — Job " + job + " and " + type + " (don't share elements) → hand");
            ctx.searchDeckJobAndTypeDontShareElements(job, type);
        };
    }

    private static Consumer<GameContext> tryParseSearchElementOrCategoryCharsDiffCost(String text) {
        Matcher m = SEARCH_ELEMENT_OR_CATEGORY_CHARS_DIFF_COST.matcher(text);
        if (!m.find()) return null;
        String element  = m.group("element").trim();
        String category = m.group("category").trim();
        return ctx -> {
            ctx.logEntry("Effect: Search — 2 " + element + " Characters, 2 Category " + category
                    + " Characters, or 1 of each, each with a different cost → hand");
            ctx.searchDeckElementOrCategoryCharsDifferentCost(element, category);
        };
    }

    /** Parses "Search for N [Element] Summons each with a different cost and add them to your hand." */
    private static Consumer<GameContext> tryParseSearchNElementSummonsDiffCost(String text) {
        Matcher m = SEARCH_N_ELEM_SUMMONS_DIFF_COST.matcher(text);
        if (!m.find()) return null;
        int    count   = Integer.parseInt(m.group("count"));
        String element = m.group("element").trim();
        return ctx -> {
            ctx.logEntry("Effect: Search — " + count + " " + element + " Summons, each different cost → hand");
            ctx.searchDeckNElementSummonsDifferentCost(count, element);
        };
    }

    /**
     * Parses "Search for 1 [filter] [elements] [type] [other than Card Name X] [of cost N] and [destination]".
     * Supported destinations: "add it to your hand", "play it onto the field",
     * "put it under the top card of your/its owner's deck".
     */
    private static Consumer<GameContext> tryParseSearchDeck(String text, CardData source, int xValue) {
        Matcher m = SEARCH_DECK_PATTERN.matcher(text);
        if (!m.find()) return null;

        // --- Card name filter ---
        String cardNameFilter = null;
        String bracketName = m.group("bracketname");
        if (bracketName != null) {
            Matcher nm = CARD_NAME_BRACKET_PATTERN.matcher(bracketName);
            if (nm.find()) cardNameFilter = nm.group(1).trim();
        } else {
            String written = m.group("cardname");
            if (written != null) cardNameFilter = written.trim();
        }

        // --- Job filter ---
        String jobFilter = null;
        String bracketJob = m.group("bracketjob");
        if (bracketJob != null) {
            Matcher jm = JOB_BRACKET_PATTERN.matcher(bracketJob);
            if (jm.find()) jobFilter = jm.group(1).trim();
        } else {
            String writtenJob = m.group("jobnm");
            if (writtenJob != null) {
                // "Chocobo or Job Moogle or Job Ninja" → "Chocobo|Moogle|Ninja"
                String[] parts = writtenJob.trim().split("(?i)\\s+or\\s+Job\\s+");
                jobFilter = String.join("|", parts);
            }
        }

        // --- "Job X or Card Name Y" — sets both filters; OR logic applied at match time ---
        String jobnmOr = m.group("jobnmor");
        if (jobnmOr != null) {
            jobFilter = jobnmOr.trim();
            String cnameOr = m.group("cnameor");
            if (cnameOr != null) cardNameFilter = cnameOr.trim();
        }

        // --- "Card Name X or Job Y" — sets both filters; OR logic applied at match time ---
        String cnameJobnmOr = m.group("cnamejobnmor");
        if (cnameJobnmOr != null) {
            cardNameFilter = cnameJobnmOr.trim();
            String jobNmCnameOr = m.group("jobnmcnameor");
            if (jobNmCnameOr != null) jobFilter = jobNmCnameOr.trim();
        }

        // --- Category filter ---
        String categoryFilter = m.group("category") != null ? m.group("category").trim() : null;
        String catAfterJob = m.group("catafterjob");
        if (catAfterJob != null && categoryFilter == null) categoryFilter = catAfterJob.trim();

        // --- Element filter (e.g. "Fire or Earth" → "Fire|Earth") ---
        // preelems captures elements that precede a Job/Name filter (e.g. "Fire Job Knight");
        // elements captures elements that follow the filter (classic ordering).
        String preElemsRaw = m.group("preelems");
        String postElemsRaw = m.group("elements");
        String elementsRaw = preElemsRaw != null ? preElemsRaw : postElemsRaw;
        String elementFilter = elementsRaw != null
                ? elementsRaw.trim().replaceAll("(?i)\\s+or\\s+", "|") : null;

        // --- Exclude name (other than Card Name X) ---
        String excludeName = m.group("excludename") != null ? m.group("excludename").trim() : null;

        // --- Exclude element (other than Light or Dark) ---
        String excludeElemRaw = m.group("excludeelem");
        String excludeElem = excludeElemRaw != null ? excludeElemRaw.trim() : null;

        // --- Type flags ---
        String  targets  = m.group("targets");
        boolean anyType  = targets == null || targets.toLowerCase().startsWith("card");
        String  tgtLower;
        if (anyType || targets == null) { tgtLower = ""; }
        else                            { tgtLower = targets.toLowerCase(); }
        boolean inclForwards = anyType || tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclBackups  = anyType || tgtLower.contains("backup")  || tgtLower.contains("character");
        boolean inclMonsters = anyType || tgtLower.contains("monster") || tgtLower.contains("character");
        boolean inclSummons  = anyType || tgtLower.contains("summon");

        // --- Type exclusion (e.g. "card other than a Backup") ---
        String excludeTypeRaw = m.group("excludetype");
        if (excludeTypeRaw != null) {
            String etl = excludeTypeRaw.toLowerCase();
            if (etl.equals("forward")   || etl.equals("character")) inclForwards = false;
            if (etl.equals("backup")    || etl.equals("character")) inclBackups  = false;
            if (etl.equals("monster")   || etl.equals("character")) inclMonsters = false;
            if (etl.equals("summon"))                                inclSummons  = false;
        }

        // --- Cost filter ---
        String costStr = m.group("cost");
        int    costVal = costStr == null ? -1 : Integer.parseInt(costStr);
        String costCmpRaw = m.group("costcmp");
        // "of cost 5 or 6" — numeric second value → encode as "or_6" for meetsCostConstraint
        String costCmp = (costCmpRaw != null && costCmpRaw.matches("\\d+"))
                ? "or_" + costCmpRaw : costCmpRaw;

        // --- Count ---
        String countStr = m.group("count");
        int count = (countStr != null) ? Integer.parseInt(countStr) : 1;

        // --- Destination ---
        String destText   = m.group("destination").toLowerCase();
        boolean entersDull = destText.contains("dull");
        String destination = destText.contains("hand")     ? "hand"
                           : destText.contains("field")    ? "field"
                           : destText.contains("break")    ? "breakZone"
                           : destText.contains("on top")   ? "deckTop"
                           :                                 "underTop";

        // Build log label
        StringBuilder filterDesc = new StringBuilder();
        if (cardNameFilter  != null) filterDesc.append(" [Name ").append(cardNameFilter).append("]");
        if (jobFilter       != null) filterDesc.append(" [Job ").append(jobFilter).append("]");
        if (categoryFilter  != null) filterDesc.append(" [Cat ").append(categoryFilter).append("]");
        if (elementFilter   != null) filterDesc.append(" [").append(elementsRaw).append("]");
        if (excludeName     != null) filterDesc.append(" [not ").append(excludeName).append("]");
        if (excludeElem     != null) filterDesc.append(" [not ").append(excludeElem).append("]");
        String typeDesc  = (targets != null && !anyType) ? " " + targets : "";
        String costLabel = CardFilters.formatCostFilterLabel(costVal, costCmp);

        // Secondary effect: text following this search clause (e.g. ". Gain 《C》.")
        String afterSearch = text.substring(m.end()).trim().replaceAll("^[.!,]+\\s*", "").trim();
        Consumer<GameContext> secondary = afterSearch.isEmpty() ? null : parse(afterSearch, source, xValue);

        final String fName = cardNameFilter, fJob = jobFilter, fCat = categoryFilter;
        final String fElem = elementFilter, fExclude = excludeName, fExclElem = excludeElem;
        final boolean fwd = inclForwards, bk = inclBackups, mn = inclMonsters, sm = inclSummons;
        final int fCount = count;
        final boolean fDull = entersDull;
        return ctx -> {
            ctx.logEntry("Effect: Search deck for " + fCount + filterDesc + typeDesc + costLabel + " → " + destination + (fDull ? " dull" : ""));
            ctx.searchDeckForCard(fwd, bk, mn, sm, costVal, costCmp, fName, fJob, fCat, fElem, fExclude, fExclElem, destination, fCount, fDull);
            if (secondary != null) secondary.accept(ctx);
        };
    }

    /** Matches "play all the Card Name X from your Break Zone onto [the] field [dull]." */
    private static final Pattern PLAY_ALL_FROM_BREAK_ZONE_PATTERN = Pattern.compile(
        "(?i)^play\\s+all\\s+the\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+from\\s+your\\s+Break\\s+Zone\\s+onto\\s+(?:the\\s+)?field(?:\\s+(?<dull>dull))?[.!]?$"
    );

    private static Consumer<GameContext> tryParsePlayAllByNameFromBreakZone(String text) {
        Matcher m = PLAY_ALL_FROM_BREAK_ZONE_PATTERN.matcher(text.trim());
        if (!m.find()) return null;
        String cardName = m.group("cardname").trim();
        boolean dull = m.group("dull") != null;
        return ctx -> {
            ctx.logEntry("Effect: Play all Card Name " + cardName + " from Break Zone → field" + (dull ? " dull" : ""));
            ctx.playAllByNameFromOwnBreakZoneDull(cardName, dull);
        };
    }

    /** Matches "play [source card name] from [your/the] Break Zone onto [the] field [dull]." */
    private static final Pattern PLAY_SOURCE_FROM_BREAK_ZONE = Pattern.compile(
        "(?i)^play\\s+(?<name>.+?)\\s+from\\s+(?:your\\s+|the\\s+)?Break\\s+Zone\\s+onto\\s+(?:the\\s+)?field(?:\\s+(?<dull>dull))?[.!]?$"
    );

    private static Consumer<GameContext> tryParsePlaySourceFromBreakZone(String text, CardData source) {
        if (source == null) return null;
        Matcher m = PLAY_SOURCE_FROM_BREAK_ZONE.matcher(text.trim());
        if (!m.matches()) return null;
        String name = m.group("name").trim();
        if (!name.equalsIgnoreCase(source.name())) return null;
        boolean dull = m.group("dull") != null;
        return ctx -> {
            ctx.logEntry("Effect: Play " + name + " from Break Zone → field" + (dull ? " dull" : ""));
            ctx.playAllByNameFromOwnBreakZoneDull(name, dull);
        };
    }

    /**
     * Parses "Play [name] onto [the] field [dull]" for break-zone-origin abilities where
     * the card name matches the source.  Does not require a "from Break Zone" qualifier —
     * BZ-origin abilities say "Play [itself] onto the field" knowing they start in the BZ.
     */
    private static Consumer<GameContext> tryParsePlaySourceOntoField(String text, CardData source) {
        if (source == null) return null;
        Matcher m = PLAY_SOURCE_ONTO_FIELD_PATTERN.matcher(text);
        if (!m.find()) return null;
        String name = m.group("name").trim();
        // "it" is a self-referential pronoun (e.g. "play it onto the field" in pay-cost abilities)
        String resolvedName = name.equalsIgnoreCase("it") ? source.name() : name;
        if (!resolvedName.equalsIgnoreCase(source.name())) return null;
        boolean dull = m.group("dull") != null;
        return ctx -> {
            ctx.logEntry("Effect: Play " + resolvedName + " from Break Zone → field" + (dull ? " dull" : ""));
            ctx.playAllByNameFromOwnBreakZoneDull(resolvedName, dull);
        };
    }

    /**
     * Routes target selection to either the field or a Break Zone depending on
     * whether {@code zone} is non-null, and forwards all filter parameters.
     */
    /**
     * Returns the cost value that appears most frequently among P1's current Forwards.
     * Used by the opponent AI in dual-number selection to target the ability user's cards.
     * Returns 0 when P1 has no Forwards on the field.
     */
    private static int aiMostCommonP1ForwardCost(GameContext ctx) {
        java.util.Map<Integer, Integer> freq = new java.util.HashMap<>();
        for (int i = 0; i < ctx.p1ForwardCount(); i++)
            freq.merge(ctx.p1Forward(i).cost(), 1, Integer::sum);
        return freq.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse(0);
    }

    /**
     * Parses "Choose 1 Forward. [CardName] deals you N point(s) of damage.
     * If the cost of the Forward is equal to or less than the damage you have received, break it."
     *
     * <p>Chooses any Forward, deals N self-damage, then breaks the chosen Forward if its cost
     * is ≤ the ability user's damage count (measured after the damage is dealt).
     */
    private static Consumer<GameContext> tryParseChooseForwardDealSelfDamageBreakIfCostLeDamage(String text) {
        Matcher m = CHOOSE_FORWARD_DEAL_SELF_DAMAGE_BREAK_IF_COST_LE_DAMAGE.matcher(text.trim());
        if (!m.find()) return null;
        final String dealerName = m.group("name").trim();
        final int damageAmount  = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 Forward — " + dealerName + " deals you " + damageAmount + " damage, then break it if cost ≤ damage");
            List<ForwardTarget> ts = selectTargets(ctx, 1, false,
                    false, false, null, null, null, false,
                    -1, null, -1, null,
                    true, false, false,
                    null, null, null, null, false, null, false);
            if (ts.isEmpty()) return;
            ForwardTarget target = ts.get(0);
            ctx.dealDamageToSelf(damageAmount);
            CardData chosen = target.isP1() ? ctx.p1Forward(target.idx()) : ctx.p2Forward(target.idx());
            int chosenCost = chosen != null ? chosen.cost() : -1;
            int dmgCount   = ctx.p1DamageCount();
            ctx.logEntry(dealerName + " damage dealt — own damage zone: " + dmgCount
                    + ", chosen Forward cost: " + chosenCost);
            if (chosenCost >= 0 && chosenCost <= dmgCount) {
                ctx.breakTarget(target);
            }
        };
    }

    /**
     * Parses "Choose 1 Forward other than [CardName]. Until the end of the turn, [CardName]
     * and the chosen Forward lose power of any value less than [CardName]'s power. (Units must be 1000.)"
     *
     * <p>Shows a Forward picker (excluding the named card), then a power-amount picker
     * (0 … named card's current power − 1000, in 1000 steps, defaulting to the max).
     * Both the named card and the chosen Forward lose the selected amount until EOT.
     */
    private static Consumer<GameContext> tryParseChooseForwardSharedPowerLoss(String text, CardData source) {
        Matcher m = CHOOSE_FORWARD_SHARED_POWER_LOSS_PATTERN.matcher(text.trim());
        if (!m.find()) return null;
        String card1 = m.group("card").trim();
        String card2 = m.group("card2").trim();
        String card3 = m.group("card3").trim();
        if (!card1.equalsIgnoreCase(card2) || !card1.equalsIgnoreCase(card3)) return null;
        final String cardName = card1;
        final EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 Forward other than " + cardName + ", then choose shared power loss");
            List<ForwardTarget> ts = selectTargets(ctx, 1, false,
                    false, false, null, null, null, false,
                    -1, null, -1, null,
                    true, false, false,
                    null, null, null, cardName, false, null, false);
            if (ts.isEmpty()) return;
            int sourcePower = ctx.fieldForwardPowerByName(cardName);
            int maxLoss = sourcePower > 0 ? ((sourcePower - 1) / 1000) * 1000 : 0;
            int amount = ctx.selectPowerAmount(maxLoss,
                    "Power loss (0–" + maxLoss + ") for " + cardName + " and chosen Forward:");
            if (amount <= 0) return;
            ctx.reduceTarget(ts.get(0), amount, noTraits);
            if (source != null && source.name().equalsIgnoreCase(cardName))
                ctx.reduceSourceForward(source, amount, noTraits);
        };
    }

    /**
     * Prompts the activating player to choose targets for a "Choose N [targets]…" effect
     * <em>before</em> the ability is placed on the stack, so the selections can be stored in
     * {@link StackEntry#preSelectedTargets()} and later inspected (e.g. to enforce "that is
     * choosing a Forward you control" cancel filters).
     *
     * <p>Returns {@code null} when {@code effectText} does not match
     * {@link #CHOOSE_CHARACTER_PATTERN}, or when only break-zone selections would be needed
     * (those are deferred to resolution time since the zone state may change).
     */
    public static List<ForwardTarget> preSelectTargets(String effectText, CardData source, int xValue, GameContext ctx) {
        String text = ELEM_TYPE_OR_ELEM_TYPE.matcher(effectText).replaceAll("$1 or $3 $2");
        text = escapePeriodInName(text, source);
        Matcher m = CHOOSE_CHARACTER_PATTERN.matcher(text);
        if (!m.find()) return null;

        boolean upTo         = m.group("upto") != null;
        int     maxCount     = Integer.parseInt(m.group("count"));
        String  rawElement   = m.group("element");
        String  element      = rawElement != null && rawElement.contains(" or ")
                ? rawElement.replaceAll("(?i)\\s+or\\s+", "|") : rawElement;
        String  rawCondition  = m.group("condition");
        String  postCondition = m.group("postcondition");
        String  blockingName  = m.group("blockingname");
        String  blockingJob   = m.group("blockingjob");
        String  condition     = blockingName  != null ? "blocking:"     + blockingName.trim()
                              : blockingJob   != null ? "blocking-job:" + blockingJob.trim()
                              : postCondition != null ? "entered the field this turn"
                              : rawCondition;
        String  targets      = m.group("targets");
        String  tgtLower = targets.toLowerCase();
        String  jobFilter;
        String  cardNameFilter;
        boolean inclForwards;
        boolean inclBackups;
        boolean inclMonsters;

        if (tgtLower.startsWith("[job ")) {
            Matcher jm = JOB_BRACKET_PATTERN.matcher(targets);
            jobFilter      = jm.find() ? jm.group(1).trim() : null;
            cardNameFilter = null;
            inclForwards   = true;
            inclBackups    = false;
            inclMonsters   = false;
        } else if (tgtLower.startsWith("[card name ")) {
            Matcher nm = CARD_NAME_BRACKET_PATTERN.matcher(targets);
            cardNameFilter = nm.find() ? nm.group(1).trim() : null;
            jobFilter      = null;
            inclForwards   = true;
            inclBackups    = true;
            inclMonsters   = true;
        } else if (tgtLower.startsWith("card name ") && tgtLower.contains(" or job ")) {
            int orJobIdx = tgtLower.indexOf(" or job ");
            String cardNamePart = targets.substring("Card Name ".length(), orJobIdx).trim();
            cardNameFilter = cardNamePart.replaceAll("(?i)\\s+(?:Forwards?|Backups?|Monsters?|Characters?)$", "").trim();
            String jobPart = targets.substring(orJobIdx + " or job ".length()).trim();
            jobFilter    = jobPart.replaceAll("(?i)\\s+(?:Forwards?|Backups?|Monsters?|Characters?)$", "").trim();
            inclForwards = tgtLower.contains("forward");
            inclBackups  = tgtLower.contains("backup");
            inclMonsters = tgtLower.contains("monster");
        } else if (tgtLower.startsWith("card name ")) {
            String rest = targets.substring("Card Name ".length());
            String[] nameParts = rest.split("(?i)\\s+or\\s+Card\\s+Name\\s+");
            cardNameFilter = String.join("|", nameParts).trim();
            jobFilter      = null;
            inclForwards   = true;
            inclBackups    = true;
            inclMonsters   = true;
        } else if (tgtLower.startsWith("job ") && tgtLower.contains("or card name ")) {
            int orCnIdx    = tgtLower.indexOf("or card name ");
            String rawJob  = targets.substring("Job ".length(), orCnIdx)
                                    .trim().replaceAll("(?i)\\s*and\\s*/\\s*$", "").trim();
            List<String> jobParts = new ArrayList<>();
            for (String p : rawJob.split("(?i)\\s+or\\s+Job\\s+")) jobParts.add(p.trim());
            jobFilter      = String.join("|", jobParts);
            cardNameFilter = targets.substring(orCnIdx + "or card name ".length()).trim();
            inclForwards   = true;
            inclBackups    = true;
            inclMonsters   = true;
        } else if (tgtLower.startsWith("job ")) {
            List<String> jobs = new ArrayList<>();
            Matcher wm = JOB_WRITTEN_SEGMENT.matcher(targets);
            while (wm.find()) jobs.add(wm.group(1).trim());
            boolean bareJob = jobs.isEmpty();
            if (bareJob)
                for (String p : targets.substring("Job ".length()).trim().split("(?i)\\s+or\\s+Job\\s+"))
                    jobs.add(p.trim());
            jobFilter      = String.join("|", jobs);
            cardNameFilter = null;
            inclForwards   = true;
            inclBackups    = bareJob;
            inclMonsters   = bareJob;
        } else {
            jobFilter      = null;
            cardNameFilter = null;
            boolean isGenericCard = tgtLower.equals("card") || tgtLower.equals("cards");
            inclForwards   = isGenericCard || tgtLower.contains("forward") || tgtLower.contains("character");
            inclBackups    = isGenericCard || tgtLower.contains("backup")  || tgtLower.contains("character");
            inclMonsters   = isGenericCard || tgtLower.contains("monster") || tgtLower.contains("character");
        }
        boolean inclSummons    = tgtLower.contains("summon")
                              || tgtLower.equals("card") || tgtLower.equals("cards");
        String  categoryFilter = m.group("category");
        String  excludeName    = restorePeriodInName(m.group("excludename") != null ? m.group("excludename").trim() : null, source);
        String  rawExcludeKw   = m.group("excludekw");
        boolean withoutMulticard = "Multicard".equalsIgnoreCase(rawExcludeKw != null ? rawExcludeKw.trim() : null);
        String  rawExcludeElem = m.group("excludeelem");
        String  excludeElem    = rawExcludeElem != null ? rawExcludeElem.trim() : null;
        String  costStr        = m.group("cost");
        String  costListStr    = m.group("costlist");
        String  rawCostCmp     = m.group("costcmp");
        int     costVal2       = costStr != null ? Integer.parseInt(costStr) : -1;
        String  costCmp;
        if (rawCostCmp != null && rawCostCmp.matches("\\d+")) {
            String tail = costListStr != null
                    ? costListStr.replaceAll("\\s+", "") + "," + rawCostCmp
                    : rawCostCmp;
            costCmp = "or_" + tail;
        } else {
            costCmp = rawCostCmp;
        }
        String  powerStr    = m.group("power");
        String  powerCmp    = m.group("powercmp");
        int     powerVal    = powerStr != null ? Integer.parseInt(powerStr) : -1;
        String  control     = m.group("control");
        boolean opponentOnly = control != null && !control.equalsIgnoreCase("you control");
        boolean selfOnly     = "you control".equalsIgnoreCase(control);
        String  zone        = m.group("zone");
        if (zone != null) return null; // break-zone targets deferred to resolution time

        return ctx.selectCharacters(maxCount, upTo, opponentOnly, selfOnly, condition, element,
                costVal2, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, excludeElem, withoutMulticard);
    }

    private static List<ForwardTarget> selectTargets(GameContext ctx,
            int maxCount, boolean upTo, boolean opponentOnly, boolean selfOnly,
            String condition, String element, String zone, boolean opponentZone,
            int costVal, String costCmp, int powerVal, String powerCmp,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons,
            String excludeElement, boolean withoutMulticard) {
        return selectTargets(ctx, maxCount, upTo, opponentOnly, selfOnly, condition, element, zone, opponentZone, false,
                costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, excludeElement, withoutMulticard);
    }

    private static List<ForwardTarget> selectTargets(GameContext ctx,
            int maxCount, boolean upTo, boolean opponentOnly, boolean selfOnly,
            String condition, String element, String zone, boolean opponentZone, boolean bothZones,
            int costVal, String costCmp, int powerVal, String powerCmp,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons,
            String excludeElement, boolean withoutMulticard) {
        List<ForwardTarget> preloaded = ctx.consumePreloadedTargets();
        if (preloaded != null) {
            ctx.recordChosenTargets(preloaded);
            return preloaded;
        }
        List<ForwardTarget> result = zone != null
                ? ctx.selectCharactersFromBreakZone(maxCount, upTo, opponentZone, bothZones, condition, element,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, excludeElement, withoutMulticard)
                : ctx.selectCharacters(maxCount, upTo, opponentOnly, selfOnly, condition, element,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, excludeElement, withoutMulticard);
        ctx.recordChosenTargets(result);
        return result;
    }
}
