package shufflingway;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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
     *   <li>Group {@code costcmp}   — optional comparison: "less" or "more" (absent = exact match)</li>
     *   <li>Group {@code control}   — optional: "opponent controls", "your opponent controls",
     *                                 or "you control"</li>
     *   <li>Group {@code excludename} — optional card name to exclude, from "other than Card Name X"</li>
     *   <li>Group {@code zone}      — optional zone, e.g. "in your Break Zone" or
     *                                 "in your opponent's Break Zone"</li>
     *   <li>Group {@code followup}  — the action to apply to chosen targets</li>
     * </ul>
     */
    private static final Pattern CHOOSE_CHARACTER_PATTERN = Pattern.compile(
        "(?i)Choose\\s+(?<upto>up\\s+to\\s+)?(?<count>\\d+)\\s+" +
        "(?:(?<condition>dull|damaged|attacking|blocking|active)\\s+)?" +
        "(?:(?<element>Multi-Element|Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Category\\s+(?<category>.+?)(?=\\s+(?:Forwards?|Backups?|Characters?|Monsters?|Summons?))\\s+)?" +
        "(?<targets>Forwards?(?:\\s+or\\s+Monsters?)?|Monsters?|Backups?|Characters?|Summons?" +
            "|\\[Job\\s+\\([^)]+\\)\\]" +
            "|\\[Card\\s+Name\\s+\\([^)]+\\)\\]" +
            "|Card\\s+Name\\s+\\S+(?:\\s+\\([^)]+\\))?" +
            "|Job\\s+.+?\\s+Forwards?(?:\\s+or\\s+Job\\s+.+?\\s+Forwards?)*)" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+of\\s+power\\s+(?<power>\\d+)(?:\\s+or\\s+(?<powercmp>less|more))?)?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls|you\\s+control))?" +
        "(?:\\s+other\\s+than\\s+Card\\s+Name\\s+(?<excludename>\\S+(?:\\s+\\([^)]+\\))?))?"+
        "(?:\\s+(?<zone>(?:in|from)\\s+your(?:\\s+opponent(?:'s)?)?\\s+Break\\s+Zone))?" +
        "(?:[.]\\s*|\\s+and\\s+|,\\s*)" +
        "(?<followup>.+)"
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
     * Matches "deal it/them damage equal to &lt;expr&gt;" where the amount is computed
     * from the game state at resolution time.  Exactly one named group will be set:
     * <ul>
     *   <li>{@code highest} — "the highest [power] Forward you control['s power]"</li>
     *   <li>{@code halfcard} — card name in "half of &lt;name&gt;'s power [(round down…)]"</li>
     *   <li>{@code itspower} — "its/their power [minus &lt;minus&gt;]"</li>
     *   <li>{@code card}     — card name in "&lt;name&gt;'s power"</li>
     * </ul>
     * Group {@code minus} is set alongside {@code itspower} when a subtraction is present.
     */
    private static final Pattern FOLLOWUP_DAMAGE_EXPR = Pattern.compile(
        "(?i)deal\\s+(?:it|them)\\s+damage\\s+equal\\s+to\\s+" +
        "(?:" +
            "(?<highest>the\\s+highest(?:\\s+power)?\\s+Forward(?:\\s+you\\s+control)?(?:'s\\s+power)?)" +
            "|half\\s+of\\s+(?<halfcard>.+?)'s\\s+power(?:\\s*\\([^)]*\\))?" +
            "|(?<itspower>(?:its|their)\\s+power)(?:\\s+minus\\s+(?<minus>\\d+))?" +
            "|(?<card>.+?)'s\\s+power" +
        ")"
    );

    /**
     * Matches "Deal it/them [base] damage [and [per] more damage] for each [source]".
     * <ul>
     *   <li>{@code base}     — base damage per unit (or fixed base when {@code per} is set)</li>
     *   <li>{@code per}      — additional damage per each unit (the "and N more" form)</li>
     *   <li>{@code selfdmg}  — source is P1's damage-zone count</li>
     *   <li>{@code jobbname} — bracket job: "[Job (name)] you control"</li>
     *   <li>{@code jobwname} — written job: "Job Name you control"</li>
     *   <li>{@code chartype} — type filter: "Forwards/Characters/etc. you control"</li>
     *   <li>{@code bzname}   — card name in P1's Break Zone</li>
     *   <li>{@code opphand}  — source is the opponent's hand size</li>
     *   <li>{@code xpaid}    — source is the X CP value paid for this ability</li>
     * </ul>
     */
    private static final Pattern FOLLOWUP_DAMAGE_FOR_EACH = Pattern.compile(
        "(?i)deal\\s+(?:it|them)\\s+(?<base>\\d+)\\s+damage" +
        "(?:\\s+and\\s+(?<per>\\d+)\\s+more\\s+damage)?" +
        "\\s+for\\s+each\\s+" +
        "(?:" +
            "(?<selfdmg>point\\s+of\\s+damage\\s+you\\s+have\\s+received)" +
            "|\\[Job\\s+\\((?<jobbname>[^)]+)\\)\\]\\s+you\\s+control" +
            "|Job\\s+(?<jobwname>.+?)\\s+you\\s+control" +
            "|(?<chartype>Forwards?|Characters?|Backups?|Monsters?)\\s+you\\s+control" +
            "|Card\\s+Name\\s+(?<bzname>\\S+(?:\\s+\\([^)]+\\))?)\\s+in\\s+your\\s+Break\\s+Zone" +
            "|(?<opphand>card\\s+in\\s+your\\s+opponent'?s?\\s+hand)" +
            "|(?<xpaid>CP\\s+paid\\s+as\\s+X)" +
        ")" +
        "[.!]?"
    );

    /** Matches "Activate it" or "Activate them". */
    private static final Pattern FOLLOWUP_ACTIVATE = Pattern.compile(
        "(?i)Activate\\s+(?:it|them)\\.?"
    );

    /** Matches "dull it/them" or "dulls it/them" (third-person form used in opponent-selects effects). */
    private static final Pattern FOLLOWUP_DULL = Pattern.compile(
        "(?i)dulls?\\s+(?:it|them)"
    );

    /** Matches "freeze it" or "freeze them". */
    private static final Pattern FOLLOWUP_FREEZE = Pattern.compile(
        "(?i)freeze\\s+(?:it|them)"
    );

    /** Matches "dull it/them and freeze it/them". */
    private static final Pattern FOLLOWUP_DULL_AND_FREEZE = Pattern.compile(
        "(?i)dull\\s+(?:it|them)\\s+and\\s+freeze\\s+(?:it|them)"
    );

    /** Matches "Break it" or "Break them". */
    private static final Pattern FOLLOWUP_BREAK = Pattern.compile(
        "(?i)Break\\s+(?:it|them)"
    );

    /** Matches "Remove it/them from the game". */
    private static final Pattern FOLLOWUP_REMOVE_FROM_GAME = Pattern.compile(
        "(?i)Remove\\s+(?:it|them)\\s+from\\s+(?:the\\s+)?game"
    );

    /** Matches "Play it onto the field" or "Play them onto the field". */
    private static final Pattern FOLLOWUP_PLAY_ONTO_FIELD = Pattern.compile(
        "(?i)Play\\s+(?:it|them)\\s+onto\\s+(?:the\\s+)?field"
    );

    /** Matches "Add it to your hand" or "Add them to your hand". */
    private static final Pattern FOLLOWUP_ADD_TO_HAND = Pattern.compile(
        "(?i)Add\\s+(?:it|them)\\s+to\\s+your\\s+hand"
    );

    /** Matches "it cannot block this turn". */
    private static final Pattern FOLLOWUP_CANNOT_BLOCK = Pattern.compile(
        "(?i)it\\s+cannot\\s+block\\s+this\\s+turn\\.?"
    );

    /** Matches "if possible, it must block this turn" or the gains-until-EOT equivalent. */
    private static final Pattern FOLLOWUP_MUST_BLOCK = Pattern.compile(
        "(?i)(?:" +
            "if\\s+possible[,]?\\s+it\\s+must\\s+block\\s+this\\s+turn" +
            "|it\\s+gains\\s+[\"']If\\s+possible[,]?\\s+this\\s+Forward\\s+must\\s+block\\.?[\"']\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn" +
        ")[.!]?"
    );

    /** Matches "Return it to its owner's hand." */
    private static final Pattern FOLLOWUP_RETURN_TO_OWNERS_HAND = Pattern.compile(
        "(?i)Return\\s+it\\s+to\\s+its\\s+owner's\\s+hand\\.?"
    );

    /** Matches "Return it to your hand." */
    private static final Pattern FOLLOWUP_RETURN_TO_YOUR_HAND = Pattern.compile(
        "(?i)Return\\s+it\\s+to\\s+your\\s+hand\\.?"
    );

    /** Matches "Put it at the top or bottom of its owner's deck." — player chooses placement. */
    private static final Pattern FOLLOWUP_PUT_TOP_OR_BOTTOM_OF_DECK = Pattern.compile(
        "(?i)Put\\s+it\\s+at\\s+the\\s+top\\s+or\\s+bottom\\s+of\\s+its\\s+owner's\\s+deck\\.?"
    );

    /** Matches "Put it at the bottom of its owner's deck." */
    private static final Pattern FOLLOWUP_PUT_BOTTOM_OF_DECK = Pattern.compile(
        "(?i)Put\\s+it\\s+at\\s+the\\s+bottom\\s+of\\s+its\\s+owner's\\s+deck\\.?"
    );

    /** Matches "Put it on top of its owner's deck." */
    private static final Pattern FOLLOWUP_PUT_TOP_OF_DECK = Pattern.compile(
        "(?i)Put\\s+it\\s+on\\s+top\\s+of\\s+its\\s+owner's\\s+deck\\.?"
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
        "(?i)it\\s+cannot\\s+attack\\s+or\\s+block\\s+until\\s+the\\s+end\\s+of\\s+" +
        "(?:your\\s+opponent's|the\\s+next)\\s+turn\\.?"
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

    // ---- Damage-shield followup patterns (apply to selected "it/them" targets) --------

    /** Matches "During this turn, the next damage dealt to it/him becomes 0 instead." */
    private static final Pattern FOLLOWUP_SHIELD_NEXT_DMG_ZERO = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+(?:it|him)\\s+becomes\\s+0\\s+instead\\.?"
    );

    /** Matches "During this turn, the next damage dealt to it is reduced by N instead." */
    private static final Pattern FOLLOWUP_SHIELD_NEXT_DMG_REDUCTION = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+it\\s+is\\s+reduced\\s+by\\s+(?<reduction>\\d+)\\s+instead\\.?"
    );

    /** Matches "During this turn, the damage dealt to it is increased by N instead." */
    private static final Pattern FOLLOWUP_DEBUFF_INCOMING_DMG_INCREASE = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+damage\\s+dealt\\s+to\\s+it\\s+is\\s+increased\\s+by\\s+(?<amount>\\d+)\\s+instead\\.?"
    );

    /** Matches "During this turn, the next damage it deals to a Forward becomes 0 instead." */
    private static final Pattern FOLLOWUP_SHIELD_NEXT_OUTGOING_ZERO = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+it\\s+deals\\s+to\\s+a\\s+Forward\\s+becomes\\s+0\\s+instead\\.?"
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
     * Matches "Activate &lt;cardName&gt;[.]" as a standalone named-card activate effect.
     * Excludes the pronoun forms ("Activate it/them") and the mass form ("Activate all …"),
     * which are handled separately.
     */
    private static final Pattern ACTIVATE_NAMED_CARD = Pattern.compile(
        "(?i)Activate\\s+(?!(?:it|them|all)\\b)(?<card>[A-Za-z][^.]+?)\\.?\\s*$"
    );

    /**
     * Matches "Your opponent discards N card(s) [from his/her/their hand]".
     * <ul>
     *   <li>Group 1 — number of cards to discard</li>
     * </ul>
     */
    private static final Pattern OPPONENT_DISCARD = Pattern.compile(
        "(?i)Your\\s+opponent\\s+discards?\\s+(\\d+)\\s+cards?" +
        "(?:\\s+from\\s+(?:his/her|his|her|their)\\s+hand)?[.!]?"
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
        "(?i)Your\\s+opponent\\s+selects?\\s+(?<count>\\d+)\\s+" +
        "(?:(?<condition>dull|damaged|attacking|blocking|active)\\s+)?" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<targets>Forwards?|Backups?|Characters?)" +
        "\\s+(?:they|he|she)\\s+controls?" +
        "(?:[.]\\s*|\\s+and\\s+)" +
        "(?<followup>.+)"
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
        "(?i)Your\\s+opponent\\s+puts?\\s+the\\s+top\\s+(?:(?<count>\\d+)\\s+cards?|card)\\s+" +
        "of\\s+(?:his/her|his|her|their)\\s+deck\\s+into\\s+the\\s+Break\\s+Zone" +
        "(?:[.!]?\\s*Draw\\s+(?<draw>\\d+)\\s+cards?[.!]?)?"
    );

    /**
     * Matches "Play 1 [filter] [type] of cost … from your hand onto the field [dull]".
     * <ul>
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
    private static final Pattern PLAY_FROM_HAND_PATTERN = Pattern.compile(
        "(?i)Play\\s+1\\s+" +
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
            // Written job: lookahead keeps the type in the targets group
            "Job\\s+(?<jobnm>.+?)\\s+(?=Forwards?|Backups?|Monsters?|Characters?)" +
        ")?" +
        // Type is optional when a card-name filter is present
        "(?<targets>Forwards?|Backups?|Monsters?|Characters?(?:\\s+Cards?)?)?" +
        "\\s*" +
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
    private static final Pattern SEARCH_DECK_PATTERN = Pattern.compile(
        "(?i)Search\\s+for\\s+(?:up\\s+to\\s+)?1\\s+" +
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
            // Written card name without brackets — ends at "of cost" or "and"
            "Card\\s+Name\\s+(?<cardname>.+?)(?=\\s+of\\s+cost|\\s+and\\b)" +
            "\\s+" +
        "|" +
            // Category filter — lookahead keeps the type word in the targets group
            "Category\\s+(?<category>.+?)\\s+" +
            "(?=Forwards?|Backups?|Monsters?|Summons?|Characters?|card\\b)" +
        "|" +
            // "Job X or Card Name Y" — OR logic; must come before plain Job alternative
            "Job\\s+(?<jobnmor>.+?)\\s+or\\s+Card\\s+Name\\s+(?<cnameor>.+?)(?=\\s+of\\s+cost|\\s+and\\b)\\s*" +
        "|" +
            // Written job — lookahead keeps element, type word, "other than", or "and" ahead
            "Job\\s+(?<jobnm>.+?)(?=\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\b" +
            "|\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card)\\b" +
            "|\\s+other\\b|\\s+and\\b)\\s*" +
        ")?" +
        "(?:(?<elements>(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*)\\s+)?" +
        "(?<targets>Forwards?|Backups?|Monsters?|Summons?|Characters?|card)?\\s*" +
        "(?:\\s+other\\s+than\\s+(?:" +
            "Card\\s+Name\\s+(?<excludename>.+?)(?=\\s+of\\s+cost|\\s+and\\b)" +
            "|(?<excludeelem>(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
              "(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*)" +
        "))?" +
        "(?:of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?\\s*)?" +
        "and\\s+" +
        "(?<destination>" +
            "add\\s+it\\s+to\\s+your\\s+hand" +
            "|play\\s+it\\s+onto\\s+(?:the\\s+)?field" +
            "|put\\s+it\\s+under\\s+the\\s+top\\s+card\\s+of\\s+(?:your|its\\s+owner's)\\s+deck" +
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
     * Matches "it/they gains/gain +N power [, Haste[, First Strike[, and Brave]]] until end of turn".
     * <ul>
     *   <li>Group 1 — numeric power amount</li>
     *   <li>Group 2 — optional traits string, e.g. {@code ", Haste, and First Strike"}</li>
     * </ul>
     */
    private static final Pattern FOLLOWUP_POWER_BOOST = Pattern.compile(
        "(?i)(?:it|they)\\s+gains?\\s+\\+(\\d+)\\s+[Pp]ower" +
        "((?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn"
    );

    /**
     * Matches "Until the end of the turn, it/they gains/gain +N power [and traits]".
     * <ul>
     *   <li>Group 1 — numeric power amount</li>
     *   <li>Group 2 — optional traits string</li>
     * </ul>
     */
    private static final Pattern FOLLOWUP_POWER_BOOST_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+gains?\\s+\\+(\\d+)\\s+[Pp]ower" +
        "((?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)"
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
     * Matches standalone "Until the end of the turn, &lt;subject&gt; gains +N power [and traits]".
     * Used when the subject is a specific card name rather than "it"/"they".
     * <ul>
     *   <li>Group {@code subject} — card name or pronoun before "gains"</li>
     *   <li>Group {@code amount}  — numeric power amount</li>
     *   <li>Group {@code traits}  — optional traits string</li>
     * </ul>
     */
    private static final Pattern STANDALONE_POWER_BOOST_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?<subject>.+?)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower" +
        "(?<traits>(?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "[.\\s]*$"
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
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?)" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+other\\s+than\\s+cost\\s+(?<excludecost>\\d+))?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "[.]?"
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
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?)" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "Draw N card(s)[, then discard M card(s)]".
     * <ul>
     *   <li>Group 1 — number of cards to draw</li>
     *   <li>Group 2 — optional discard count afterward (absent = draw only)</li>
     * </ul>
     */
    /**
     * Matches "Look at the top card of your deck. You may put it into the Break Zone."
     * The optional sentence after the period allows for additional context text.
     */
    private static final Pattern LOOK_TOP_DECK_OPTIONALLY_BREAK = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "You\\s+may\\s+put\\s+it\\s+into\\s+the\\s+Break\\s+Zone[.!]?"
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
        "(?i)Place\\s+(?<count>\\d+)\\s+(?<name>.+?)\\s+Counters?\\s+on\\s+(?<target>.+?)[.!]?"
    );

    /** Matches "Gain 《C》." — the ability user gains one Crystal. */
    private static final Pattern GAIN_CRYSTAL = Pattern.compile(
        "(?i)Gain\\s+《C》[.!]?"
    );

    /** Matches "Gain 《C》 for each CP paid as X." — crystal count equals the X value paid. */
    private static final Pattern GAIN_CRYSTAL_PER_X = Pattern.compile(
        "(?i)Gain\\s+《C》\\s+for\\s+each\\s+CP\\s+paid\\s+as\\s+X[.!]?"
    );

    private static final Pattern DRAW_CARDS = Pattern.compile(
        "(?i)Draw\\s+(\\d+)\\s+cards?(?:\\s*[,.]?\\s*then\\s+discard\\s+(\\d+)\\s+cards?)?[.!]?"
    );

    /**
     * Matches "Discard N card(s)[,] then draw M card(s)".
     * <ul>
     *   <li>Group 1 — number of cards to discard</li>
     *   <li>Group 2 — number of cards to draw afterward</li>
     * </ul>
     */
    private static final Pattern DISCARD_THEN_DRAW = Pattern.compile(
        "(?i)Discard\\s+(\\d+)\\s+cards?[,.]?\\s+then\\s+draw\\s+(\\d+)\\s+cards?[.!]?"
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
     * Matches "&lt;subject&gt; deals you N point(s) of damage."
     * <ul>
     *   <li>Group {@code amount} — number of damage points dealt to the ability user</li>
     * </ul>
     */
    private static final Pattern DEAL_PLAYER_DAMAGE_TO_SELF = Pattern.compile(
        "(?i).+?\\s+deals?\\s+you\\s+(?<amount>\\d+)\\s+points?\\s+of\\s+damage[.!]?"
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
        "(?:(?<condition>damaged|dull|attacking|blocking)\\s+)?" +
        "Forwards?" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+other\\s+than\\s+Job\\s+(?<excludejob>.+?)(?=\\s+(?:your\\s+)?opponent\\s+controls\\b|[.!]?$))?" +
        "(?:\\s+(?<opponent>(?:your\\s+)?opponent\\s+controls))?" +
        "[.!]?"
    );

    /** Matches "Take 1 more turn after this one. At the end of that turn, you lose the game." */
    private static final Pattern EXTRA_TURN_THEN_LOSE = Pattern.compile(
        "(?i)Take\\s+1\\s+more\\s+turn\\s+after\\s+this\\s+one[.!]?\\s+" +
        "At\\s+the\\s+end\\s+of\\s+that\\s+turn,\\s+you\\s+lose\\s+the\\s+game[.!]?"
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
        Consumer<GameContext> result;

        result = tryParseDealDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseChooseCharacter(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseDelayedEffect(effectText);
        if (result != null) return result;

        result = tryParseNegateAllDamage(effectText);
        if (result != null) return result;

        result = tryParseAllFieldEffect(effectText);
        if (result != null) return result;

        result = tryParseAllFieldPowerBoost(effectText);
        if (result != null) return result;

        result = tryParseStandalonePowerBoostUntil(effectText, source);
        if (result != null) return result;

        result = tryParseStandalonePowerReduceUntil(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneSelfBoost(effectText, source);
        if (result != null) return result;

        result = tryParseOpponentDiscard(effectText);
        if (result != null) return result;

        result = tryParseDrawCards(effectText);
        if (result != null) return result;

        result = tryParseDiscardThenDraw(effectText);
        if (result != null) return result;

        result = tryParseDealPlayerDamageToOpponent(effectText);
        if (result != null) return result;

        result = tryParseDealPlayerDamageToSelf(effectText);
        if (result != null) return result;

        result = tryParsePlayFromHand(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseOpponentSelects(effectText);
        if (result != null) return result;

        result = tryParseOpponentMill(effectText);
        if (result != null) return result;

        result = tryParseOpponentRevealHand(effectText);
        if (result != null) return result;

        result = tryParseRevealTopDeck(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneDamageShields(effectText, source);
        if (result != null) return result;

        result = tryParseSearchDeck(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseActivateNamedCard(effectText);
        if (result != null) return result;

        result = tryParseExtraTurnThenLose(effectText);
        if (result != null) return result;

        result = tryParseGainCrystal(effectText);
        if (result != null) return result;

        result = tryParsePlaceCounters(effectText, source);
        if (result != null) return result;

        result = tryParseLookTopDeckOptionallyBreak(effectText);
        if (result != null) return result;

        return null;
    }

    /** Returns the name of the first pattern that matches {@code effectText}, or {@code null}. */
    public static String matchedPatternName(String effectText, CardData source) {
        if (tryParseDealDamageToForwards(effectText)          != null) return "DealDamageToForwards";
        if (tryParseChooseCharacter(effectText, source, 0)    != null) return "ChooseCharacter";
        if (tryParseDelayedEffect(effectText)                 != null) return "DelayedEffect";
        if (tryParseNegateAllDamage(effectText)                != null) return "NegateDamage";
        if (tryParseAllFieldEffect(effectText)                != null) return "AllFieldEffect";
        if (tryParseStandalonePowerBoostUntil(effectText, source) != null) return "StandalonePowerBoostUntil";
        if (tryParseStandalonePowerReduceUntil(effectText, source) != null) return "StandalonePowerReduceUntil";
        if (tryParseStandaloneSelfBoost(effectText, source)   != null) return "StandaloneSelfBoost";
        if (tryParseOpponentDiscard(effectText)               != null) return "OpponentDiscard";
        if (tryParseDrawCards(effectText)                     != null) return "DrawCards";
        if (tryParseDiscardThenDraw(effectText)               != null) return "DiscardThenDraw";
        if (tryParseDealPlayerDamageToOpponent(effectText)    != null) return "DealPlayerDamageToOpponent";
        if (tryParseDealPlayerDamageToSelf(effectText)        != null) return "DealPlayerDamageToSelf";
        if (tryParsePlayFromHand(effectText, source, 0)       != null) return "PlayFromHand";
        if (tryParseOpponentSelects(effectText)               != null) return "OpponentSelects";
        if (tryParseOpponentMill(effectText)                  != null) return "OpponentMill";
        if (tryParseOpponentRevealHand(effectText)            != null) return "OpponentRevealHand";
        if (tryParseRevealTopDeck(effectText, source)         != null) return "RevealTopDeck";
        if (tryParseStandaloneDamageShields(effectText, source) != null) return "StandaloneDamageShields";
        if (tryParseSearchDeck(effectText, source, 0)           != null) return "SearchDeck";
        if (tryParseActivateNamedCard(effectText)               != null) return "ActivateNamedCard";
        if (tryParseExtraTurnThenLose(effectText)               != null) return "ExtraTurnThenLose";
        if (tryParseGainCrystalPerX(effectText, 0)               != null) return "GainCrystalPerX";
        if (tryParseGainCrystal(effectText)                      != null) return "GainCrystal";
        if (tryParsePlaceCounters(effectText, source)            != null) return "PlaceCounters";
        if (tryParseLookTopDeckOptionallyBreak(effectText)       != null) return "LookTopDeckOptionallyBreak";
        return null;
    }

    /**
     * Returns the name of the first followup pattern that matches {@code followupText}, or
     * {@code null} if no followup pattern recognises it.  The ordering mirrors the precedence
     * used inside {@link #tryParseChooseCharacter}.
     */
    public static String matchedFollowupName(String followupText, CardData source) {
        if (FOLLOWUP_DAMAGE_FOR_EACH.matcher(followupText).find())                    return "DamageForEach";
        if (FOLLOWUP_DAMAGE.matcher(followupText).find())                             return "Damage";
        if (FOLLOWUP_DAMAGE_EXPR.matcher(followupText).find())                        return "DamageExpr";
        if (FOLLOWUP_ACTIVATE_AND_NEGATE_DAMAGE.matcher(followupText).find())          return "ActivateAndNegateDamage";
        if (FOLLOWUP_NEGATE_DAMAGE.matcher(followupText).find())                      return "NegateDamage";
        if (FOLLOWUP_ACTIVATE.matcher(followupText).find())                           return "Activate";
        if (FOLLOWUP_DULL.matcher(followupText).find()
                && !FOLLOWUP_DULL_AND_FREEZE.matcher(followupText).find())            return "Dull";
        if (FOLLOWUP_DULL_AND_FREEZE.matcher(followupText).find())                    return "DullAndFreeze";
        if (FOLLOWUP_FREEZE.matcher(followupText).find())                             return "Freeze";
        if (FOLLOWUP_BREAK.matcher(followupText).find())                              return "Break";
        if (FOLLOWUP_REMOVE_FROM_GAME.matcher(followupText).find())                   return "RemoveFromGame";
        if (FOLLOWUP_PLAY_ONTO_FIELD.matcher(followupText).find())                    return "PlayOntoField";
        if (FOLLOWUP_ADD_TO_HAND.matcher(followupText).find())                        return "AddToHand";
        if (FOLLOWUP_RETURN_TO_OWNERS_HAND.matcher(followupText).find())              return "ReturnToOwnersHand";
        if (FOLLOWUP_RETURN_TO_YOUR_HAND.matcher(followupText).find())                return "ReturnToYourHand";
        if (FOLLOWUP_PUT_TOP_OR_BOTTOM_OF_DECK.matcher(followupText).find())          return "PutTopOrBottomOfDeck";
        if (FOLLOWUP_PUT_BOTTOM_OF_DECK.matcher(followupText).find())                 return "PutBottomOfDeck";
        if (FOLLOWUP_PUT_TOP_OF_DECK.matcher(followupText).find())                    return "PutTopOfDeck";
        if (FOLLOWUP_PUT_UNDER_TOP_OF_DECK.matcher(followupText).find())              return "PutUnderTopOfDeck";
        if (FOLLOWUP_CANNOT_BLOCK.matcher(followupText).find())                       return "CannotBlock";
        if (FOLLOWUP_MUST_BLOCK.matcher(followupText).find())                         return "MustBlock";
        if (FOLLOWUP_CANNOT_ATTACK.matcher(followupText).find())                      return "CannotAttack";
        if (FOLLOWUP_MUST_ATTACK.matcher(followupText).find())                        return "MustAttack";
        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK.matcher(followupText).find())             return "CannotAttackOrBlock";
        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK_PERSISTENT.matcher(followupText).find())  return "CannotAttackOrBlockPersistent";
        if (FOLLOWUP_POWER_BECOMES.matcher(followupText).find())                      return "PowerBecomes";
        if (FOLLOWUP_POWER_BOOST.matcher(followupText).find())                        return "PowerBoost";
        if (FOLLOWUP_POWER_BOOST_UNTIL.matcher(followupText).find())                  return "PowerBoostUntil";
        if (FOLLOWUP_KEYWORD_GRANT.matcher(followupText).find())                      return "KeywordGrant";
        if (FOLLOWUP_POWER_REDUCE.matcher(followupText).find())                       return "PowerReduce";
        if (FOLLOWUP_POWER_REDUCE_UNTIL.matcher(followupText).find())                 return "PowerReduceUntil";
        if (OPPONENT_DISCARD.matcher(followupText).find())                            return "OpponentDiscard";
        if (source != null) {
            Matcher selfM = SELF_POWER_BOOST.matcher(followupText);
            if (selfM.find() && selfM.group("selfsubject").trim().equalsIgnoreCase(source.name()))
                return "SelfPowerBoost";
        }
        if (FOLLOWUP_CANCEL_EFFECT.matcher(followupText).find())                      return "CancelEffect";
        if (FOLLOWUP_SHIELD_NEXT_DMG_ZERO.matcher(followupText).find())               return "ShieldNextDmgZero";
        if (FOLLOWUP_SHIELD_NEXT_DMG_REDUCTION.matcher(followupText).find())          return "ShieldNextDmgReduction";
        if (FOLLOWUP_DEBUFF_INCOMING_DMG_INCREASE.matcher(followupText).find())       return "DebuffIncomingDmgIncrease";
        if (FOLLOWUP_SHIELD_NEXT_OUTGOING_ZERO.matcher(followupText).find())          return "ShieldNextOutgoingZero";
        if (FOLLOWUP_PUT_TO_BREAK_ZONE.matcher(followupText).find())                  return "PutToBreakZone";
        return null;
    }

    /**
     * Returns a full description of which patterns cover {@code effectText}, including
     * primary, followup, and secondary layers.  A {@code "?"} in the result means that
     * layer has no matching pattern yet.  Returns {@code null} if no primary pattern matches.
     */
    public static String fullDescription(String effectText, CardData source) {
        if (CardData.YOUR_TURN_ONLY_PATTERN.matcher(effectText).matches())  return "YourTurnOnly";
        if (CardData.ONCE_PER_TURN_PATTERN.matcher(effectText).matches())   return "OncePerTurn";
        if (CardData.YOUR_TURN_ONLY_PATTERN.matcher(effectText).find()
                && CardData.ONCE_PER_TURN_PATTERN.matcher(effectText).find()) return "YourTurnOnly+OncePerTurn";
        if (CardData.MAIN_PHASE_ONLY_PATTERN.matcher(effectText).matches())        return "MainPhaseOnly";
        if (CardData.WHILE_PARTY_ATTACKING_PATTERN.matcher(effectText).matches()) return "WhilePartyAttacking";
        if (CardData.WHILE_CARD_ATTACKING_PATTERN.matcher(effectText).matches())  return "WhileCardAttacking";
        if (CardData.WHILE_CARD_BLOCKING_PATTERN.matcher(effectText).matches())   return "WhileCardBlocking";
        if (CardData.WHILE_CARD_IN_HAND_PATTERN.matcher(effectText).matches())   return "WhileCardInHand";
        if (tryParseDealDamageToForwards(effectText) != null)               return "DealDamageToForwards";

        Matcher chooseM = CHOOSE_CHARACTER_PATTERN.matcher(effectText);
        if (chooseM.find()) {
            String followup      = chooseM.group("followup").trim();
            int    dotIdx        = followup.indexOf(". ");
            String primaryPart   = dotIdx >= 0 ? followup.substring(0, dotIdx).trim() : followup;
            String secondaryTxt  = dotIdx >= 0 ? followup.substring(dotIdx + 2).trim() : null;
            String followupName  = matchedFollowupName(primaryPart, source);
            String secondaryDesc = (secondaryTxt != null && !secondaryTxt.isEmpty())
                    ? fullDescription(secondaryTxt, source) : null;
            StringBuilder sb = new StringBuilder("ChooseCharacter / ")
                    .append(followupName != null ? followupName : "?");
            if (secondaryDesc != null) sb.append(" + ").append(secondaryDesc);
            else if (secondaryTxt != null && !secondaryTxt.isEmpty()) sb.append(" + ?");
            return sb.toString();
        }

        if (tryParseNegateAllDamage(effectText) != null)                     return "NegateDamage";
        if (tryParseAllFieldEffect(effectText) != null)                     return "AllFieldEffect";
        if (tryParseStandalonePowerBoostUntil(effectText, source) != null)  return "StandalonePowerBoostUntil";
        if (tryParseStandalonePowerReduceUntil(effectText, source) != null) return "StandalonePowerReduceUntil";
        if (tryParseStandaloneSelfBoost(effectText, source) != null)        return "StandaloneSelfBoost";
        if (tryParseOpponentDiscard(effectText) != null)                    return "OpponentDiscard";
        if (tryParseDrawCards(effectText) != null)                          return "DrawCards";
        if (tryParseDiscardThenDraw(effectText) != null)                    return "DiscardThenDraw";
        if (tryParseDealPlayerDamageToOpponent(effectText) != null)         return "DealPlayerDamageToOpponent";
        if (tryParseDealPlayerDamageToSelf(effectText) != null)             return "DealPlayerDamageToSelf";
        if (tryParsePlayFromHand(effectText, source, 0) != null)            return "PlayFromHand";

        Matcher opSelM = OPPONENT_SELECTS_PATTERN.matcher(effectText);
        if (opSelM.find()) {
            String followup     = opSelM.group("followup").trim();
            String followupName = matchedFollowupName(followup, source);
            return "OpponentSelects / " + (followupName != null ? followupName : "?");
        }

        if (tryParseOpponentMill(effectText) != null)                       return "OpponentMill";
        if (tryParseOpponentRevealHand(effectText) != null)                 return "OpponentRevealHand";
        if (tryParseRevealTopDeck(effectText, source) != null)
            return revealTopDeckDescription(effectText, source) + restrictionDesc(effectText);
        if (tryParseStandaloneDamageShields(effectText, source) != null)    return "StandaloneDamageShields";
        if (tryParseSearchDeck(effectText, source, 0) != null)              return "SearchDeck";
        if (tryParseActivateNamedCard(effectText) != null)                  return "ActivateNamedCard";
        if (tryParseExtraTurnThenLose(effectText) != null)                  return "ExtraTurnThenLose";
        if (tryParseGainCrystalPerX(effectText, 0) != null)                 return "GainCrystalPerX";
        if (tryParseGainCrystal(effectText)        != null)                  return "GainCrystal";
        if (tryParsePlaceCounters(effectText, source) != null)               return "PlaceCounters";
        if (tryParseLookTopDeckOptionallyBreak(effectText) != null)          return "LookTopDeckOptionallyBreak";
        return null;
    }

    private static String revealTopDeckDescription(String text, CardData source) {
        Matcher m = REVEAL_CLAUSE_PATTERN.matcher(text);
        List<String> clauseDescs = new ArrayList<>();
        while (m.find()) {
            String action = m.group("action").trim();
            String op = normalizeRevealOp(action);
            clauseDescs.add(op != null ? op : "?");
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
    private static Consumer<GameContext> tryParseDealDamageToForwards(String text) {
        Matcher m = DEAL_DAMAGE_TO_FORWARDS.matcher(text);
        if (!m.find()) return null;

        int    damage        = Integer.parseInt(m.group("amount"));
        String condition     = m.group("condition");   // nullable
        String costStr       = m.group("cost");
        int    costVal       = costStr != null ? Integer.parseInt(costStr) : -1;
        String costCmp       = m.group("costcmp");
        String excludeJob    = m.group("excludejob") != null ? m.group("excludejob").trim() : null;
        boolean opponentOnly = m.group("opponent") != null;

        return ctx -> {
            String condLabel   = condition  != null ? (condition + " ")   : "";
            String costLabel   = costVal >= 0 ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
            String exclLabel   = excludeJob != null ? " [not Job " + excludeJob + "]" : "";
            String scopeLabel  = opponentOnly ? "P2's " : "all ";
            ctx.logEntry("Effect: Deal " + damage + " damage to "
                    + scopeLabel + condLabel + "Forwards" + costLabel + exclLabel);

            // --- P2 forwards (always included) ---
            List<Integer> p2Targets = new ArrayList<>();
            for (int i = 0; i < ctx.p2ForwardCount(); i++) {
                CardData c = ctx.p2Forward(i);
                if (!meetsCostFilter(c.cost(), costVal, costCmp)) continue;
                if (excludeJob != null && excludeJob.equalsIgnoreCase(c.job())) continue;
                if (meetsCondition(ctx.p2ForwardState(i), ctx.p2ForwardCurrentDamage(i),
                        ctx.isP2ForwardAttacking(i), ctx.isP2ForwardBlocking(i), condition))
                    p2Targets.add(i);
            }
            for (int i = p2Targets.size() - 1; i >= 0; i--) {
                int idx = p2Targets.get(i);
                if (idx < ctx.p2ForwardCount())
                    ctx.damageP2Forward(idx, damage);
            }

            // --- P1 forwards (only when effect is not opponent-only) ---
            if (!opponentOnly) {
                List<Integer> p1Targets = new ArrayList<>();
                for (int i = 0; i < ctx.p1ForwardCount(); i++) {
                    CardData c = ctx.p1Forward(i);
                    if (!meetsCostFilter(c.cost(), costVal, costCmp)) continue;
                    if (excludeJob != null && excludeJob.equalsIgnoreCase(c.job())) continue;
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
    private static Consumer<GameContext> tryParseChooseCharacter(String text, CardData source, int xValue) {
        Matcher m = CHOOSE_CHARACTER_PATTERN.matcher(text);
        if (!m.find()) return null;

        boolean upTo         = m.group("upto") != null;
        int     maxCount     = Integer.parseInt(m.group("count"));
        String  condition    = m.group("condition");
        String  element      = m.group("element");
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
        } else if (tgtLower.startsWith("card name ")) {
            cardNameFilter = targets.substring("Card Name ".length()).trim();
            jobFilter      = null;
            inclForwards   = true;
            inclBackups    = true;
            inclMonsters   = true;
        } else if (tgtLower.startsWith("job ")) {
            List<String> jobs = new ArrayList<>();
            Matcher wm = JOB_WRITTEN_SEGMENT.matcher(targets);
            while (wm.find()) jobs.add(wm.group(1).trim());
            jobFilter      = jobs.isEmpty() ? null : String.join("|", jobs);
            cardNameFilter = null;
            inclForwards   = true;
            inclBackups    = false;
            inclMonsters   = false;
        } else {
            jobFilter      = null;
            cardNameFilter = null;
            inclForwards   = tgtLower.contains("forward") || tgtLower.contains("character");
            inclBackups    = tgtLower.contains("backup")  || tgtLower.contains("character");
            inclMonsters   = tgtLower.contains("monster") || tgtLower.contains("character");
        }
        boolean inclSummons  = tgtLower.contains("summon");
        String  categoryFilter = m.group("category");
        String  excludeName    = m.group("excludename");
        String  costStr      = m.group("cost");
        String  costCmp      = m.group("costcmp");
        int     costVal      = costStr != null ? Integer.parseInt(costStr) : -1;
        String  powerStr     = m.group("power");
        String  powerCmp     = m.group("powercmp");
        int     powerVal     = powerStr != null ? Integer.parseInt(powerStr) : -1;
        String  control      = m.group("control");
        boolean opponentOnly = control != null && !control.equalsIgnoreCase("you control");
        boolean selfOnly     = "you control".equalsIgnoreCase(control);
        String  zone         = m.group("zone");
        boolean opponentZone = zone != null && zone.toLowerCase().contains("opponent");

        String  followup     = m.group("followup").trim();

        // If the followup contains ". " (sentence boundary), split into a primary effect
        // (applied to selected targets) and a secondary standalone effect that follows.
        // E.g. "Break it. <name> deals you 1 damage." → primary="Break it", secondary parsed separately.
        final String primaryFollowup;
        final Consumer<GameContext> secondary;
        {
            int dotSpaceIdx = followup.indexOf(". ");
            if (dotSpaceIdx >= 0) {
                primaryFollowup = followup.substring(0, dotSpaceIdx).trim();
                String secondaryText = followup.substring(dotSpaceIdx + 2).trim();
                Consumer<GameContext> parsed = secondaryText.isEmpty() ? null : parse(secondaryText, source);
                secondary = (parsed != null) ? parsed
                        : ctx -> ctx.logEntry("[ActionResolver] Secondary followup not yet implemented: " + secondaryText);
            } else {
                primaryFollowup = followup;
                secondary = null;
            }
        }

        // Shared log prefix helper (captured once, reused in all lambdas)
        String costLabel     = costVal >= 0
                ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
        String powerLabel    = powerVal >= 0
                ? " of power " + powerVal + (powerCmp != null ? " or " + powerCmp : "") : "";
        String controlLabel  = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String categoryLabel = categoryFilter != null ? " Category " + categoryFilter : "";
        String excludeLabel  = excludeName != null ? " (excl. " + excludeName + ")" : "";
        String zoneLabel     = zone != null
                ? " in " + (opponentZone ? "opponent's" : "your") + " Break Zone" : "";
        String choosePrefix = "Choose " + (upTo ? "up to " : "") + maxCount
                + (condition != null ? " " + condition : "")
                + (element   != null ? " " + element   : "")
                + categoryLabel + " " + targets + costLabel + powerLabel + controlLabel + excludeLabel + zoneLabel;

        // --- "Deal it N damage for each [source]" followup ---
        Matcher forEachM = FOLLOWUP_DAMAGE_FOR_EACH.matcher(primaryFollowup);
        if (forEachM.find()) {
            int    baseDmg        = Integer.parseInt(forEachM.group("base"));
            String perStr         = forEachM.group("per");
            int    perDmg         = perStr != null ? Integer.parseInt(perStr) : 0;
            boolean srcSelfDmg    = forEachM.group("selfdmg")  != null;
            String  srcJobBracket = forEachM.group("jobbname") != null ? forEachM.group("jobbname").trim() : null;
            String  srcJobWritten = forEachM.group("jobwname") != null ? forEachM.group("jobwname").trim() : null;
            String  srcCharType   = forEachM.group("chartype");
            String  srcBzName     = forEachM.group("bzname")   != null ? forEachM.group("bzname").trim()   : null;
            boolean srcOppHand    = forEachM.group("opphand")  != null;
            // if none of the above → xpaid
            boolean charFwd = srcCharType != null && (srcCharType.equalsIgnoreCase("forward")   || srcCharType.equalsIgnoreCase("forwards")   || srcCharType.equalsIgnoreCase("character") || srcCharType.equalsIgnoreCase("characters"));
            boolean charBkp = srcCharType != null && (srcCharType.equalsIgnoreCase("backup")    || srcCharType.equalsIgnoreCase("backups")    || srcCharType.equalsIgnoreCase("character") || srcCharType.equalsIgnoreCase("characters"));
            boolean charMon = srcCharType != null && (srcCharType.equalsIgnoreCase("monster")   || srcCharType.equalsIgnoreCase("monsters")   || srcCharType.equalsIgnoreCase("character") || srcCharType.equalsIgnoreCase("characters"));
            String sourceLabel;
            if      (srcSelfDmg)           sourceLabel = "P1 damage";
            else if (srcJobBracket != null) sourceLabel = "[Job (" + srcJobBracket + ")] you control";
            else if (srcJobWritten != null) sourceLabel = "Job " + srcJobWritten + " you control";
            else if (srcCharType   != null) sourceLabel = srcCharType + " you control";
            else if (srcBzName     != null) sourceLabel = "Card Name " + srcBzName + " in BZ";
            else if (srcOppHand)           sourceLabel = "opponent hand";
            else                            sourceLabel = "X CP paid";
            String logLabel = perDmg > 0
                    ? baseDmg + " + " + perDmg + "×[" + sourceLabel + "]"
                    : baseDmg + "×[" + sourceLabel + "]";
            return ctx -> {
                int n;
                if      (srcSelfDmg)           n = ctx.p1DamageCount();
                else if (srcJobBracket != null) n = ctx.countP1FieldCards(true, true, true, srcJobBracket, null);
                else if (srcJobWritten != null) n = ctx.countP1FieldCards(true, true, true, srcJobWritten, null);
                else if (srcCharType   != null) n = ctx.countP1FieldCards(charFwd, charBkp, charMon, null, null);
                else if (srcBzName     != null) n = ctx.countP1BreakZoneCards(srcBzName, null);
                else if (srcOppHand)           n = ctx.opponentHandSize();
                else                            n = xValue;
                int damage = perDmg > 0 ? baseDmg + perDmg * n : baseDmg * n;
                ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (" + logLabel + ", n=" + n + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Damage followup (fixed amount) ---
        Matcher dmgM = FOLLOWUP_DAMAGE.matcher(primaryFollowup);
        if (dmgM.find()) {
            int damage = Integer.parseInt(dmgM.group("amount"));
            String alsoCard = dmgM.group("also") != null ? dmgM.group("also").trim() : null;
            return ctx -> {
                ctx.logEntry(alsoCard != null
                        ? choosePrefix + " — Deal " + damage + " damage (and to " + alsoCard + ")"
                        : choosePrefix + " — Deal " + damage + " damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                if (alsoCard != null) ctx.damageFieldForwardByName(alsoCard, damage);
                if (secondary != null) secondary.accept(ctx);
            };
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
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("halfcard") != null) {
                String cardName = exprM.group("halfcard").trim();
                return ctx -> {
                    int raw    = Math.max(0, ctx.fieldForwardPowerByName(cardName));
                    int damage = (raw / 2 / 1000) * 1000;
                    ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (half of " + cardName + "'s power)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
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
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, Math.max(0, ctx.effectiveTargetPower(t) - subtract)));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, Math.max(0, ctx.effectiveTargetPower(t) - subtract)));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("card") != null) {
                String cardName = exprM.group("card").trim();
                return ctx -> {
                    int damage = Math.max(0, ctx.fieldForwardPowerByName(cardName));
                    ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (" + cardName + "'s power)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                ts.forEach(ctx::negateAllDamage);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Activate followup ---
        if (FOLLOWUP_ACTIVATE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Activate");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.activateTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.activateTarget(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Dull followup ---
        if (FOLLOWUP_DULL.matcher(primaryFollowup).find()
                && !FOLLOWUP_DULL_AND_FREEZE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Dull");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.breakTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.breakTarget(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Remove from game followup ---
        if (FOLLOWUP_REMOVE_FROM_GAME.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Remove From Game");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.removeTargetFromGame(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.removeTargetFromGame(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Play onto field followup ---
        if (FOLLOWUP_PLAY_ONTO_FIELD.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Play onto Field");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.playTargetOntoField(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.playTargetOntoField(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Add to hand followup ---
        if (FOLLOWUP_ADD_TO_HAND.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Add to Hand");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.addTargetToHand(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.addTargetToHand(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Return to owner's hand followup ---
        if (FOLLOWUP_RETURN_TO_OWNERS_HAND.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Return to owner's hand");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.returnP1ForwardToHand(t.idx());
                    else          ctx.returnP2ForwardToHand(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Return to your hand followup ---
        if (FOLLOWUP_RETURN_TO_YOUR_HAND.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Return to your hand");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.returnP1ForwardToDeckBottom(t.idx());
                    else          ctx.returnP2ForwardToDeckBottom(t.idx());
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.setP1ForwardCannotBlock(t.idx());
                    else          ctx.setP2ForwardCannotBlock(t.idx());
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, traits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power boost followup (until-prefix order: "Until…, it/they gains +N power [and traits]") ---
        Matcher boostUntilM = FOLLOWUP_POWER_BOOST_UNTIL.matcher(primaryFollowup);
        if (boostUntilM.find()) {
            int boost = Integer.parseInt(boostUntilM.group(1));
            EnumSet<CardData.Trait> traits = parseTraits(boostUntilM.group(2));
            String logSuffix = boostLogSuffix(boost, traits);
            return ctx -> {
                ctx.logEntry(choosePrefix + logSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, traits));
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.reduceTarget(t, reduction, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.reduceTarget(t, reduction, traits));
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
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
                ctx.cancelSummonOnStack();
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Next incoming damage = 0 followup ---
        if (FOLLOWUP_SHIELD_NEXT_DMG_ZERO.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Shield: next damage becomes 0");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                ts.forEach(ctx::shieldNextIncomingDamage);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
                ts.forEach(ctx::shieldNextOutgoingDamage);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
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
        StringBuilder sb = new StringBuilder(" — Gain +").append(amount).append(" power");
        List<String> names = new ArrayList<>();
        if (traits.contains(CardData.Trait.HASTE))        names.add("Haste");
        if (traits.contains(CardData.Trait.FIRST_STRIKE)) names.add("First Strike");
        if (traits.contains(CardData.Trait.BRAVE))        names.add("Brave");
        switch (names.size()) {
            case 1 -> sb.append(" and ").append(names.get(0));
            case 2 -> sb.append(", ").append(names.get(0)).append(", and ").append(names.get(1));
            case 3 -> sb.append(", ").append(names.get(0))
                        .append(", ").append(names.get(1))
                        .append(", and ").append(names.get(2));
            default -> {
            }
        }
        sb.append(" until end of turn");
        return sb.toString();
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
    private static Consumer<GameContext> tryParseStandalonePowerBoostUntil(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_POWER_BOOST_UNTIL.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (subject.equalsIgnoreCase("it") || subject.equalsIgnoreCase("they")) return null;
        if (!subject.equalsIgnoreCase(source.name())) return null;
        int boost = Integer.parseInt(m.group("amount"));
        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));
        String logSuffix = boostLogSuffix(boost, traits);
        return ctx -> {
            ctx.logEntry(source.name() + logSuffix);
            ctx.boostSourceForward(source, boost, traits);
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

    /** Parses "Draw N card(s)[, then discard M card(s)]" as a standalone effect. */
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
    private static Consumer<GameContext> tryParseOpponentDiscard(String text) {
        Matcher m = OPPONENT_DISCARD.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Opponent discards " + count + " card(s)");
            ctx.forceOpponentDiscard(count);
        };
    }

    // -------------------------------------------------------------------------
    // Delayed ("at the end of this turn") effect parser
    // -------------------------------------------------------------------------

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

        String element  = m.group("element");
        String targets  = m.group("targets");
        String tgtLower = targets.toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclBackups  = tgtLower.contains("backup")  || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");

        String costStr = m.group("cost");
        String costCmp = m.group("costcmp");
        int    costVal = costStr != null ? Integer.parseInt(costStr) : -1;

        String excludeCostStr = m.group("excludecost");
        int    excludeCostVal = excludeCostStr != null ? Integer.parseInt(excludeCostStr) : -1;

        String control      = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null && control.toLowerCase().contains("you control");

        String actionLabel = switch (action) {
            case BREAK          -> "Break";
            case DULL           -> "Dull";
            case FREEZE         -> "Freeze";
            case DULL_AND_FREEZE -> "Dull & Freeze";
            case ACTIVATE       -> "Activate";
        };
        String costLabel    = costVal >= 0
                ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
        String exclLabel    = excludeCostVal >= 0 ? " [not cost " + excludeCostVal + "]" : "";
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String logMsg = actionLabel + " all " + targets + costLabel + exclLabel + controlLabel;

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldEffect(action, inclForwards, inclBackups, inclMonsters,
                    opponentOnly, selfOnly, element, costVal, costCmp, excludeCostVal);
        };
    }

    /**
     * Parses "All [the] [element] [targets] [of cost N] [control] gain +N power until end of turn."
     */
    private static Consumer<GameContext> tryParseAllFieldPowerBoost(String text) {
        Matcher m = ALL_FIELD_POWER_BOOST_PATTERN.matcher(text);
        if (!m.find()) return null;

        String element  = m.group("element");
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

        int amount = Integer.parseInt(m.group("amount"));

        String elemLabel    = element != null ? element + " " : "";
        String costLabel    = costVal >= 0 ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String logMsg = "All " + elemLabel + targets + costLabel + controlLabel
                + " gain +" + amount + " power until end of turn";

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldPowerBoost(amount, inclForwards, inclMonsters,
                    opponentOnly, selfOnly, element, costVal, costCmp);
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
        if (writtenCardName != null) {
            cardNameFilter = writtenCardName.trim();
        } else if (writtenJob != null) {
            jobFilter = writtenJob.trim();
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
            java.util.regex.Matcher djm = java.util.regex.Pattern.compile(
                "(?i)Job\\s+(.+?)(?:\\s+and/or\\s+|$)").matcher(dynFilterRaw);
            java.util.regex.Matcher dnm = java.util.regex.Pattern.compile(
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

        // Build log label
        StringBuilder filterDesc = new StringBuilder();
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
        final String fExclude = excludeName, fDynJob = dynJob, fDynName = dynName;
        final int fCostVal = costVal, fCostVal2 = costVal2;
        final String fCostCmp = costCmp;
        final boolean fEntersDull = entersDull;

        return ctx -> {
            int resolvedCost = fCostVal;
            String resolvedCmp = fCostCmp;
            if (isDynamic) {
                int n;
                if (fDynJob != null && fDynName != null) {
                    n = ctx.countP1FieldCards(true, true, true, fDynJob, null)
                      + ctx.countP1FieldCards(true, true, true, null, fDynName)
                      - ctx.countP1FieldCards(true, true, true, fDynJob, fDynName);
                } else {
                    n = ctx.countP1FieldCards(true, true, true, fDynJob, fDynName);
                }
                resolvedCost = n;
                resolvedCmp  = "less";
            }
            ctx.logEntry("Effect: Play 1" + filterDesc + tgtLabel + costLabel + exclLabel + dullLabel + " from hand");
            ctx.playCharacterFromHand(inclForwards, inclBackups, inclMonsters,
                    resolvedCost, resolvedCmp, fCostVal2,
                    fJob, fName, fCat, fExclude, fEntersDull);
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
                        inclForwards, inclBackups, inclMonsters, null, null, null, null, false);
                sortedByIdxDesc(ts, false).forEach(ctx::forceTargetToBreakZone);
            };
        }

        if (FOLLOWUP_DULL.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(prefix + " — Dull");
                List<ForwardTarget> ts = ctx.selectCharacters(count, false, true, false,
                        condition, element, -1, null, -1, null,
                        inclForwards, inclBackups, inclMonsters, null, null, null, null, false);
                sortedByIdxDesc(ts, false).forEach(ctx::dullTarget);
            };
        }

        return ctx -> ctx.logEntry(
                "[ActionResolver] Opponent selects — followup not yet implemented: " + followup);
    }

    /**
     * Parses "Your opponent puts the top N card(s) of his/her deck into the Break Zone
     * [. Draw M card(s)]".
     */
    private static Consumer<GameContext> tryParseOpponentMill(String text) {
        Matcher m = OPPONENT_MILL_PATTERN.matcher(text);
        if (!m.find()) return null;

        String countStr = m.group("count");
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

        // 1. "Job X [or Card Name Y]"
        Matcher jobM = Pattern.compile(
            "(?i)^Job\\s+(.+?)(?:\\s+or\\s+Card\\s+Name\\s+(.+))?$"
        ).matcher(cond);
        if (jobM.matches()) {
            String job  = jobM.group(1).trim();
            String name = jobM.group(2) != null ? jobM.group(2).trim() : null;
            pred = card -> card.job().equalsIgnoreCase(job)
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
        if (lo.contains("field") && lo.contains("dull")) return "playOntoFieldDull";
        if (lo.contains("field"))  return "playOntoField";
        if (lo.contains("hand"))   return "addToHand";
        if (lo.contains("break"))  return "putToBreakZone";
        return null;
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
                        true, false, false, false, true, null, -1, null, -1);
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

        return null;
    }

    private static Consumer<GameContext> tryParseGainCrystal(String text) {
        if (!GAIN_CRYSTAL.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Gain 1 Crystal");
            ctx.gainCrystal(1);
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

    private static Consumer<GameContext> tryParseLookTopDeckOptionallyBreak(String text) {
        if (!LOOK_TOP_DECK_OPTIONALLY_BREAK.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Look at top of deck — may put into Break Zone");
            ctx.lookAtTopDeckAndOptionallyBreak();
        };
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

    private static Consumer<GameContext> tryParseExtraTurnThenLose(String text) {
        if (!EXTRA_TURN_THEN_LOSE.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Take 1 more turn — you lose at the end of that turn");
            ctx.takeExtraTurnThenLose();
        };
    }

    /** Parses "Activate &lt;cardName&gt;[.]" — activates the named card the ability user controls. */
    private static Consumer<GameContext> tryParseActivateNamedCard(String text) {
        Matcher m = ACTIVATE_NAMED_CARD.matcher(text);
        if (!m.find()) return null;
        String cardName = m.group("card").trim();
        return ctx -> {
            ctx.logEntry("Effect: Activate " + cardName);
            List<ForwardTarget> ts = ctx.selectCharacters(
                    1, false, false, true, null, null, -1, null, -1, null,
                    true, true, true, null, cardName, null, null, false);
            ts.forEach(ctx::activateTarget);
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

        // --- Category filter ---
        String categoryFilter = m.group("category") != null ? m.group("category").trim() : null;

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
        boolean anyType  = targets == null || targets.equalsIgnoreCase("card");
        String  tgtLower;
        if (anyType || targets == null) { tgtLower = ""; }
        else                            { tgtLower = targets.toLowerCase(); }
        boolean inclForwards = anyType || tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclBackups  = anyType || tgtLower.contains("backup")  || tgtLower.contains("character");
        boolean inclMonsters = anyType || tgtLower.contains("monster") || tgtLower.contains("character");
        boolean inclSummons  = anyType || tgtLower.contains("summon");

        // --- Cost filter ---
        String costStr = m.group("cost");
        int    costVal = costStr == null ? -1 : Integer.parseInt(costStr);
        String costCmp = m.group("costcmp");

        // --- Destination ---
        String destText   = m.group("destination").toLowerCase();
        String destination = destText.contains("hand")    ? "hand"
                           : destText.contains("field")   ? "field"
                           :                                "underTop";

        // Build log label
        StringBuilder filterDesc = new StringBuilder();
        if (cardNameFilter  != null) filterDesc.append(" [Name ").append(cardNameFilter).append("]");
        if (jobFilter       != null) filterDesc.append(" [Job ").append(jobFilter).append("]");
        if (categoryFilter  != null) filterDesc.append(" [Cat ").append(categoryFilter).append("]");
        if (elementFilter   != null) filterDesc.append(" [").append(elementsRaw).append("]");
        if (excludeName     != null) filterDesc.append(" [not ").append(excludeName).append("]");
        if (excludeElem     != null) filterDesc.append(" [not ").append(excludeElem).append("]");
        String typeDesc  = (targets != null && !anyType) ? " " + targets : "";
        String costLabel = costVal >= 0 ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";

        // Secondary effect: text following this search clause (e.g. ". Gain 《C》.")
        String afterSearch = text.substring(m.end()).trim().replaceAll("^[.!,]+\\s*", "").trim();
        Consumer<GameContext> secondary = afterSearch.isEmpty() ? null : parse(afterSearch, source, xValue);

        final String fName = cardNameFilter, fJob = jobFilter, fCat = categoryFilter;
        final String fElem = elementFilter, fExclude = excludeName, fExclElem = excludeElem;
        final boolean fwd = inclForwards, bk = inclBackups, mn = inclMonsters, sm = inclSummons;
        return ctx -> {
            ctx.logEntry("Effect: Search deck for 1" + filterDesc + typeDesc + costLabel + " → " + destination);
            ctx.searchDeckForCard(fwd, bk, mn, sm, costVal, costCmp, fName, fJob, fCat, fElem, fExclude, fExclElem, destination);
            if (secondary != null) secondary.accept(ctx);
        };
    }

    /**
     * Routes target selection to either the field or a Break Zone depending on
     * whether {@code zone} is non-null, and forwards all filter parameters.
     */
    private static List<ForwardTarget> selectTargets(GameContext ctx,
            int maxCount, boolean upTo, boolean opponentOnly, boolean selfOnly,
            String condition, String element, String zone, boolean opponentZone,
            int costVal, String costCmp, int powerVal, String powerCmp,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons) {
        return zone != null
                ? ctx.selectCharactersFromBreakZone(maxCount, upTo, opponentZone, condition, element,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons)
                : ctx.selectCharacters(maxCount, upTo, opponentOnly, selfOnly, condition, element,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons);
    }
}
