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
     *   <li>Group {@code costcmp}   — optional: "less", "more", or a second digit value for
     *                                 "cost N or M" two-value filters (absent = exact match)</li>
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
        "(?i)Choose\\s+(?<upto>up\\s+to\\s+)?(?<count>\\d+)\\s+" +
        "(?:(?<condition>dull|damaged|attacking|blocking|active)\\s+)?" +
        "(?:(?<element>Multi-Element|Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Category\\s+(?<category>.+?)(?=\\s+(?:cards?|Forwards?|Backups?|Characters?|Monsters?|Summons?))\\s+)?" +
        "(?<targets>cards?|Forwards?(?:\\s+or\\s+(?:Monsters?|Backups?))?|Monsters?|Backups?|Characters?|Summons?" +
            "|\\[Job\\s+\\([^)]+\\)\\]" +
            "|\\[Card\\s+Name\\s+\\([^)]+\\)\\]" +
            "|Card\\s+Name\\s+\\S+(?:\\s+\\([^)]+\\))?(?:\\s+or\\s+Card\\s+Name\\s+\\S+(?:\\s+\\([^)]+\\))?)*" +
            "|Job\\s+.+?\\s+(?:and/)?or\\s+Card\\s+Name\\s+\\S+" +
            "|Job\\s+.+?\\s+Forwards?(?:\\s+or\\s+Job\\s+.+?\\s+Forwards?)*" +
            "|Job\\s+.+?(?=\\s+(?:of\\s+|other\\s+than|in\\s+your|from\\s+your)|[,.]))" +
        "(?:\\s+that\\s+(?<postcondition>entered\\s+the\\s+field\\s+this\\s+turn|entered\\s+this\\s+turn))?" +
        "(?:\\s+without\\s+《(?<excludekw>[^》]+)》)?" +
        "(?:\\s+of\\s+any\\s+Element\\s+except\\s+(?<excludeelem>" +
            "(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+and\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*))?" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more|\\d+))?)?" +
        "(?:\\s+of\\s+power\\s+(?<power>\\d+)(?:\\s+or\\s+(?<powercmp>less|more))?)?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls|you\\s+control))?" +
        "(?:\\s+other\\s+than\\s+(?:Card\\s+Name\\s+)?(?<excludename>\\S+(?:\\s+\\([^)]+\\))?))?"+
        "(?:\\s+(?<zone>(?:in|from)\\s+your(?:\\s+opponent(?:'s)?)?\\s+Break\\s+Zone))?" +
        // "blocking [CardName]" or "blocking a [Job] [JobName]" — targets the blocker of the named card
        "(?:\\s+blocking\\s+" +
            "(?:(?:a\\s+(?:Job\\s+)?(?<blockingjob>[^.,]+?)(?=\\s*[.,]))" +
            "|(?<blockingname>[^.,]+?)(?=\\s*[.,])))?"+
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
            "|(?<itspower>(?:its|their)\\s+power)(?:\\s+minus\\s+(?<minus>\\d+))?" +
            "|(?<dullforward>the\\s+power\\s+of\\s+the\\s+dull(?:ed)?\\s+Forward)" +
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
            "|Job\\s+(?<jobwname>.+?)(?:\\s+(?<jobwtype>Forwards?|Backups?|Monsters?))?\\s+you\\s+control" +
            "|(?:Category\\s+(?<category>\\S+)\\s+)?(?<chartype>Forwards?|Characters?|Backups?|Monsters?)\\s+you\\s+control" +
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
     * Matches "Your opponent removes N card(s) in his/her/their hand from the game."
     * (opponent chooses which cards — not random).  Group 1 — count.
     */
    private static final Pattern OPPONENT_HAND_RFP = Pattern.compile(
        "(?i)Your\\s+opponent\\s+removes?\\s+(\\d+)\\s+cards?\\s+in\\s+" +
        "(?:his/her|his|her|their)\\s+hand\\s+from\\s+(?:the\\s+)?game[.!]?"
    );

    /**
     * Matches "Remove [CardName] from the game." as a standalone sentence.
     * Group {@code named} — the card name.  Does NOT match "Remove it/them …" (pronouns).
     */
    private static final Pattern REMOVE_NAMED_FROM_GAME = Pattern.compile(
        "(?i)Remove\\s+(?!(?:it|them)\\b)(?<named>.+?)\\s+from\\s+(?:the\\s+)?game[.!]?"
    );

    /**
     * Matches "Remove the top [N cards / card] of your deck from the game."
     * Group {@code count} — number of cards (absent means 1).
     */
    private static final Pattern REMOVE_TOP_OF_DECK_FROM_GAME = Pattern.compile(
        "(?i)Remove\\s+the\\s+top\\s+(?:(?<count>\\d+)\\s+cards?|card)\\s+of\\s+your\\s+deck\\s+from\\s+(?:the\\s+)?game\\.?"
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

    /** Matches "Add it to your hand" or "Add them to your hand". */
    private static final Pattern FOLLOWUP_ADD_TO_HAND = Pattern.compile(
        "(?i)Add\\s+(?:it|them)\\s+to\\s+your\\s+hand"
    );

    /** Matches "it cannot block this turn". */
    private static final Pattern FOLLOWUP_CANNOT_BLOCK = Pattern.compile(
        "(?i)it\\s+cannot\\s+block\\s+this\\s+turn\\.?"
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

    /** Matches "Return [name] to its owner's hand." — named card, not a pronoun. */
    private static final Pattern RETURN_NAMED_TO_OWNERS_HAND = Pattern.compile(
        "(?i)Return\\s+(?!(?:it|them)\\b)(?<named>.+?)\\s+to\\s+its\\s+owner(?:'s|s')?\\s+hand[.!]?"
    );

    /** Matches "Return [name] to your hand." — named card, not a pronoun. */
    private static final Pattern RETURN_NAMED_TO_YOUR_HAND_STANDALONE = Pattern.compile(
        "(?i)Return\\s+(?!(?:it|them)\\b)(?<named>.+?)\\s+to\\s+your\\s+hand[.!]?"
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

    // ---- Damage-shield followup patterns (apply to selected "it/them" targets) --------

    /** Matches "During this turn, the next damage dealt to it/him becomes 0 instead." */
    private static final Pattern FOLLOWUP_SHIELD_NEXT_DMG_ZERO = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+(?:it|him)\\s+becomes\\s+0\\s+instead\\.?"
    );

    /** Matches "During this turn, the next damage dealt to it by Summons or abilities is reduced by N instead." */
    private static final Pattern FOLLOWUP_SHIELD_NEXT_ABILITY_DMG_REDUCTION = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+it\\s+by\\s+Summons?\\s+or\\s+abilities\\s+is\\s+reduced\\s+by\\s+(?<reduction>\\d+)\\s+instead\\.?"
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

    /** "It gains 'This Character/Forward/Monster cannot be broken.' until the end of the turn." */
    private static final Pattern FOLLOWUP_CANNOT_BE_BROKEN = Pattern.compile(
        "(?i)(?:it|they)\\s+gains?\\s+['\"]This\\s+(?:Forward|Character|Monster)\\s+cannot\\s+be\\s+broken\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
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

    /** Standalone: "All [the] Forwards you control gain '[...] cannot be broken.' until end of turn." */
    private static final Pattern STANDALONE_ALL_FORWARDS_SHIELD_CANNOT_BE_BROKEN = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Forwards?\\s+you\\s+control\\s+gains?\\s+" +
        "['\"][^'\"]*?cannot\\s+be\\s+broken\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    /** "It gains 'When this Forward deals battle damage to a Forward, break that Forward.' until the end of the turn." */
    private static final Pattern FOLLOWUP_GAINS_BREAKTOUCH_BATTLE = Pattern.compile(
        "(?i)(?:it|they)\\s+gains?\\s+['\"]When\\s+this\\s+Forward\\s+deals\\s+battle\\s+damage\\s+to\\s+a\\s+Forward,\\s+break\\s+that\\s+Forward\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    // ---- Standalone cannot-be-chosen patterns ---------------------------------------

    /**
     * "Activate all the Forwards/Characters you control. They cannot be chosen by
     * your opponent's Summons [or abilities] [this turn]."
     * Registered before {@link #tryParseAllFieldEffect} to prevent the activate-all part
     * from consuming the text without the cannot-be-chosen clause.
     */
    private static final Pattern STANDALONE_ACTIVATE_AND_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)Activate\\s+all\\s+the\\s+(?:Forwards?|Characters?)\\s+you\\s+control\\." +
        "\\s*They\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "(?<scope>Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
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
     * "The Job X [other than Y] Forwards/Characters you control cannot be chosen by
     * your opponent's Summons/abilities."
     * Group {@code job} is the job name; {@code excl} is the optional excluded card name.
     */
    private static final Pattern STANDALONE_JOB_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)The\\s+Job\\s+(?<job>[^.]+?)(?:\\s+other\\s+than\\s+(?<excl>[^.]+?))?" +
        "\\s+(?:Forwards?|Characters?)\\s+you\\s+control\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "(?<scope>Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
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
        "(?i)(?<name>[A-Za-z][^.]+?)\\s+can\\s+attack\\s+once\\s+more\\s+this\\s+turn[.!]?"
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
    private static final Pattern OPPONENT_DISCARD = Pattern.compile(
        "(?i)Your\\s+opponent\\s+discards?\\s+(\\d+)\\s+cards?" +
        "(?:\\s+from\\s+(?:his/her|his|her|their)\\s+hand)?[.!]?"
    );

    /** Matches "Discard your hand. Then, draw N card(s)." Group 1 = draw count. */
    private static final Pattern DISCARD_HAND_THEN_DRAW = Pattern.compile(
        "(?i)Discard\\s+your\\s+hand[.,]?\\s+[Tt]hen[,]?\\s+draw\\s+(\\d+)\\s+cards?[.!]?\\s*$"
    );

    /** Matches "Discard your hand." as a standalone effect. */
    private static final Pattern DISCARD_HAND = Pattern.compile(
        "(?i)Discard\\s+your\\s+hand[.!]?\\s*$"
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
            // "Card Name X or Job Y" — OR logic; must come before plain Card Name alternative
            "Card\\s+Name\\s+(?<cnamejobnmor>.+?)\\s+or\\s+Job\\s+(?<jobnmcnameor>.+?)(?=\\s+of\\s+cost|\\s+and\\b)\\s*" +
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
        "(?:\\s+other\\s+than\\s+Card\\s+Name\\s+(?<excludename>.+?)(?=\\s+of\\s+cost|\\s+and\\b))?" +
        "(?:of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?\\s*)?" +
        "(?:\\s+other\\s+than\\s+(?<excludeelem>(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*))?\\s*" +
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
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn"
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
        "(?i)At\\s+the\\s+beginning\\s+of\\s+your\\s+next\\s+turn['’]s\\s+Main\\s+Phase\\s+1" +
        "\\s+and\\s+until\\s+the\\s+end\\s+of\\s+the\\s+same\\s+turn\\s*,\\s+" +
        "(?<subject>.+?)['’]s\\s+power\\s+will\\s+double[.!]?"
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
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?:Job\\s+(?<job>.+?)(?=\\s+(?:Forwards?|Backups?|Characters?|you\\b|opponent\\b)|\\s*[.!]?$))?" +
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?)?" +
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
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+(?<verb>gains?|loses?)\\s+\\+?(?<amount>\\d+)\\s+[Pp]ower" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
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
     * "Select a Job. It gains that Job until the end of the turn."
     * Matched against the full followup (before the dot-split) so both sentences are seen together.
     */
    private static final Pattern FOLLOWUP_SELECT_JOB_GRANT = Pattern.compile(
        "(?i)^Select\\s+a\\s+Job\\.\\s+It\\s+gains?\\s+that\\s+Job\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?$"
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
     * Matches "Look at / Reveal the top N cards of your deck. Add 1 [Element] card among them
     * to your hand and put the rest of the cards into the Break Zone."
     * <ul>
     *   <li>Group {@code count}   — number of cards to look at / reveal</li>
     *   <li>Group {@code element} — optional element filter on the card added to hand</li>
     * </ul>
     */
    private static final Pattern LOOK_TOP_DECK_ADD_TO_HAND_REST_BREAK = Pattern.compile(
        "(?i)(?:Look\\s+at|Reveal)\\s+the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "Add\\s+1\\s+(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?card\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+and\\s+" +
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
     * Detects "select [up to] N of the M following actions" — handled by MainWindow's
     * {@code executeSelectFollowingActionsAutoAbility}, not by ActionResolver's parse chain.
     * Used only for pattern-name reporting.
     */
    static final Pattern SELECT_FOLLOWING_ACTIONS_DETECT = Pattern.compile(
        "(?i)^(?:if\\s+[^,]+,\\s+)?select\\s+(?:up\\s+to\\s+)?\\d+\\s+of\\s+the\\s+\\d+\\s+following\\s+actions?"
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

    /**
     * Alternate word order: "Deal all [the] [condition] Forwards [of cost N] [other than Job Y] [opponent controls] X damage."
     * Same named groups as {@link #DEAL_DAMAGE_TO_FORWARDS} so {@link #tryParseDealDamageToForwards} can share extraction logic.
     */
    private static final Pattern DEAL_DAMAGE_TO_FORWARDS_ALT = Pattern.compile(
        "(?i)Deal\\s+all(?:\\s+the)?\\s+" +
        "(?:(?<condition>damaged|dull|attacking|blocking)\\s+)?" +
        "Forwards?" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+other\\s+than\\s+Job\\s+(?<excludejob>.+?)(?=\\s+(?:your\\s+)?opponent\\s+controls\\b|\\s+\\d+\\s+damage))?" +
        "(?:\\s+(?<opponent>(?:your\\s+)?opponent\\s+controls))?" +
        "\\s+(?<amount>\\d+)\\s+damage[.!]?"
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
        "(?:Job\\s+(?<job>.+?)\\s+)?" +
        "(?:Card\\s+Name\\s+(?<cardname>\\S+)|(?<type>Forwards?|Backups?|Monsters?|Summons?|card))\\s+" +
        "is\\s+reduced\\s+by\\s+(?<amount>\\d+)" +
        "(?<floorone>\\s*\\(it\\s+cannot\\s+become\\s+0\\))?[.!]?"
    );

    /** Matches "Take 1 more turn after this one. At the end of that turn, you lose the game." */
    private static final Pattern EXTRA_TURN_THEN_LOSE = Pattern.compile(
        "(?i)Take\\s+1\\s+more\\s+turn\\s+after\\s+this\\s+one[.!]?\\s+" +
        "At\\s+the\\s+end\\s+of\\s+that\\s+turn,\\s+you\\s+lose\\s+the\\s+game[.!]?"
    );

    /**
     * Matches "Until the end of the turn, [CardName] also becomes a Forward with N power."
     * Used for action abilities on Monsters.  Group {@code power} captures the power value.
     */
    private static final Pattern BECOME_FORWARD_UNTIL_EOT_PATTERN = Pattern.compile(
        "(?i)^Until\\s+the\\s+end\\s+of\\s+the\\s+turn,\\s+.+?\\s+also\\s+becomes?\\s+a\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power"
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

        result = tryParseWhenYouDoSoSequence(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseControlConditionGate(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseSelectNumber(effectText, source);
        if (result != null) return result;

        result = tryParseDealDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseDealHalfPowerDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseDealHalfSourcePowerDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseDamageToCombatBlocker(effectText);
        if (result != null) return result;

        result = tryParseChooseCharacter(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseEndOfEachTurnFieldAbility(effectText, source);
        if (result != null) return result;

        result = tryParseDelayedEffect(effectText);
        if (result != null) return result;

        result = tryParseCannotBeChosenStandalone(effectText, source);
        if (result != null) return result;

        result = tryParseNegateAllDamage(effectText);
        if (result != null) return result;

        result = tryParseAllFieldEffect(effectText);
        if (result != null) return result;

        result = tryParseAllFieldPowerBoost(effectText);
        if (result != null) return result;

        result = tryParseReturnAllToHand(effectText);
        if (result != null) return result;

        result = tryParseStandalonePowerBoostAndAttackTrigger(effectText, source);
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

        result = tryParseStandaloneSelfBoost(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneShieldCannotBeBroken(effectText, source);
        if (result != null) return result;

        result = tryParseRevealSelectHandRfp(effectText);
        if (result != null) return result;

        result = tryParseOpponentRandomHandRfp(effectText);
        if (result != null) return result;

        result = tryParseOpponentHandRfp(effectText);
        if (result != null) return result;

        result = tryParseReturnNamedToHand(effectText);
        if (result != null) return result;

        result = tryParseRemoveNamedFromGame(effectText, source);
        if (result != null) return result;

        result = tryParseOpponentDrawThenRandomDiscard(effectText);
        if (result != null) return result;

        result = tryParseOpponentRandomDiscard(effectText);
        if (result != null) return result;

        result = tryParseOpponentDiscard(effectText);
        if (result != null) return result;

        result = tryParseDrawCards(effectText);
        if (result != null) return result;

        result = tryParseDiscardHandThenDraw(effectText);
        if (result != null) return result;

        result = tryParseDiscardHand(effectText);
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

        result = tryParseOpponentPutsForwardToBreakZone(effectText);
        if (result != null) return result;

        result = tryParseOpponentMill(effectText);
        if (result != null) return result;

        result = tryParseSelfMill(effectText);
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

        result = tryParseAttackOnceMore(effectText);
        if (result != null) return result;

        result = tryParseRemoveFromBattle(effectText);
        if (result != null) return result;

        result = tryParseCostReductionThisTurn(effectText);
        if (result != null) return result;

        result = tryParseExtraTurnThenLose(effectText);
        if (result != null) return result;

        result = tryParseGainCrystal(effectText);
        if (result != null) return result;

        result = tryParsePlaceCounters(effectText, source);
        if (result != null) return result;

        result = tryParseLookTopDeckOptionallyBreak(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckBottomOrKeep(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckAddToHandRestBottom(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckAddToHandRestBreak(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckTopOrBottom(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckReturnTopOrdered(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckPeek(effectText);
        if (result != null) return result;

        result = tryParseRemoveTopOfDeckFromGame(effectText);
        if (result != null) return result;

        result = tryParseShuffleDeck(effectText);
        if (result != null) return result;

        result = tryParseBackupCpDraw(effectText);
        if (result != null) return result;

        result = tryParseBecomeForwardUntilEot(effectText, source);
        if (result != null) return result;

        // Compound-sentence fallback: split on ". " between sentences and compose effects.
        // Handles "Activate <cardName>. <cardName> gains +2000 power until the end of the turn." etc.
        String[] sentences = effectText.split("(?<=\\.)\\s+(?=[A-Z])");
        if (sentences.length > 1) {
            List<Consumer<GameContext>> consumers = new ArrayList<>();
            boolean allParsed = true;
            for (String s : sentences) {
                Consumer<GameContext> c = parse(s.trim(), source, xValue);
                if (c == null) { allParsed = false; break; }
                consumers.add(c);
            }
            if (allParsed) return ctx -> consumers.forEach(c -> c.accept(ctx));
        }

        result = tryParseConditionalOpponentHand(effectText, source, xValue);
        if (result != null) return result;

        if (CardData.HAS_ALL_ELEMENTS_PATTERN.matcher(effectText.trim()).matches()) return ctx -> {};

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

    /** Returns the name of the first pattern that matches {@code effectText}, or {@code null}. */
    public static String matchedPatternName(String effectText, CardData source) {
        if (tryParseSelectNumber(effectText, source)                    != null) return "SelectNumber";
        if (tryParseDealDamageToForwards(effectText)                    != null) return "DealDamageToForwards";
        if (tryParseDealHalfPowerDamageToForwards(effectText)           != null) return "DealHalfPowerDamageToForwards";
        if (tryParseDealHalfSourcePowerDamageToForwards(effectText)     != null) return "DealHalfSourcePowerDamageToForwards";
        if (tryParseDamageToCombatBlocker(effectText)                   != null) return "DamageToCombatBlocker";
        if (tryParseChooseCharacter(effectText, source, 0)              != null) return "ChooseCharacter";
        if (tryParseEndOfEachTurnFieldAbility(effectText, source) != null) return "EndOfEachTurnFieldAbility";
        if (tryParseDelayedEffect(effectText)                 != null) return "DelayedEffect";
        if (tryParseCannotBeChosenStandalone(effectText, source) != null) return "CannotBeChosen";
        if (tryParseNegateAllDamage(effectText)                != null) return "NegateDamage";
        if (tryParseSelectNumber(effectText, source)          != null) return "SelectNumber";
        if (tryParseAllFieldEffect(effectText)                != null) return "AllFieldEffect";
        if (tryParseStandalonePowerBoostAndAttackTrigger(effectText, source) != null) return "StandalonePowerBoostAndAttackTrigger";
        if (tryParseStandalonePowerBoostUntil(effectText, source) != null) return "StandalonePowerBoostUntil";
        if (tryParseStandaloneDoublePowerUntil(effectText, source) != null) return "StandaloneDoublePowerUntil";
        if (tryParseStandaloneDoublesItsPowerUntil(effectText, source) != null) return "StandaloneDoublesItsPowerUntil";
        if (tryParseStandaloneDoublePowerMainPhaseNextTurn(effectText, source) != null) return "StandaloneDoublePowerMainPhaseNextTurn";
        if (tryParseStandalonePowerReduceUntil(effectText, source) != null) return "StandalonePowerReduceUntil";
        if (tryParseFieldSelfPowerBoost(effectText, source)    != null) return "FieldSelfPowerBoost";
        if (tryParseStandaloneSelfBoost(effectText, source)   != null) return "StandaloneSelfBoost";
        if (tryParseStandaloneShieldCannotBeBroken(effectText, source) != null) return "StandaloneShieldCannotBeBroken";
        if (tryParseRevealSelectHandRfp(effectText)            != null) return "RevealSelectHandRfp";
        if (tryParseOpponentRandomHandRfp(effectText)         != null) return "OpponentRandomHandRfp";
        if (tryParseOpponentHandRfp(effectText)               != null) return "OpponentHandRfp";
        if (tryParseRemoveNamedFromGame(effectText, source)   != null) return "RemoveNamedFromGame";
        if (tryParseOpponentDrawThenRandomDiscard(effectText)  != null) return "OpponentDrawThenRandomDiscard";
        if (tryParseOpponentRandomDiscard(effectText)         != null) return "OpponentRandomDiscard";
        if (tryParseOpponentDiscard(effectText)               != null) return "OpponentDiscard";
        if (tryParseDrawCards(effectText)                     != null) return "DrawCards";
        if (tryParseDiscardHandThenDraw(effectText)           != null) return "DiscardHandThenDraw";
        if (tryParseDiscardHand(effectText)                   != null) return "DiscardHand";
        if (tryParseDiscardThenDraw(effectText)               != null) return "DiscardThenDraw";
        if (tryParseDealPlayerDamageToOpponent(effectText)    != null) return "DealPlayerDamageToOpponent";
        if (tryParseDealPlayerDamageToSelf(effectText)        != null) return "DealPlayerDamageToSelf";
        if (tryParsePlayFromHand(effectText, source, 0)       != null) return "PlayFromHand";
        if (tryParseOpponentSelects(effectText)               != null) return "OpponentSelects";
        if (tryParseOpponentPutsForwardToBreakZone(effectText) != null) return "OpponentPutsForwardToBreakZone";
        if (tryParseOpponentMill(effectText)                  != null) return "OpponentMill";
        if (tryParseSelfMill(effectText)                      != null) return "SelfMill";
        if (tryParseOpponentRevealHand(effectText)            != null) return "OpponentRevealHand";
        if (tryParseRevealTopDeck(effectText, source)         != null) return "RevealTopDeck";
        if (tryParseStandaloneDamageShields(effectText, source) != null) return "StandaloneDamageShields";
        if (tryParseSearchDeck(effectText, source, 0)           != null) return "SearchDeck";
        if (tryParseActivateNamedCard(effectText)               != null) return "ActivateNamedCard";
        if (tryParseAttackOnceMore(effectText)                  != null) return "AttackOnceMore";
        if (tryParseRemoveFromBattle(effectText)                != null) return "RemoveFromBattle";
        if (tryParseCostReductionThisTurn(effectText)            != null) return "CostReductionThisTurn";
        if (tryParseExtraTurnThenLose(effectText)               != null) return "ExtraTurnThenLose";
        if (tryParseGainCrystalPerX(effectText, 0)               != null) return "GainCrystalPerX";
        if (tryParseGainCrystal(effectText)                      != null) return "GainCrystal";
        if (tryParsePlaceCounters(effectText, source)            != null) return "PlaceCounters";
        if (tryParseLookTopDeckOptionallyBreak(effectText)        != null) return "LookTopDeckOptionallyBreak";
        if (tryParseLookTopDeckBottomOrKeep(effectText)           != null) return "LookTopDeckBottomOrKeep";
        if (tryParseLookTopDeckAddToHandRestBottom(effectText)    != null) return "LookTopDeckAddToHandRestBottom";
        if (tryParseLookTopDeckAddToHandRestBreak(effectText)     != null) return "LookTopDeckAddToHandRestBreak";
        if (tryParseLookTopDeckTopOrBottom(effectText)            != null) return "LookTopDeckTopOrBottom";
        if (tryParseLookTopDeckReturnTopOrdered(effectText)       != null) return "LookTopDeckReturnTopOrdered";
        if (tryParseLookTopDeckPeek(effectText)                   != null) return "LookTopDeckPeek";
        if (tryParseRemoveTopOfDeckFromGame(effectText)            != null) return "RemoveTopOfDeckFromGame";
        if (tryParseShuffleDeck(effectText)                        != null) return "ShuffleDeck";
        if (tryParseConditionalOpponentHand(effectText, source, 0) != null) return "ConditionalOpponentHand";
        if (SELECT_FOLLOWING_ACTIONS_DETECT.matcher(effectText).find())    return "SelectFollowingActions";
        if (CardData.HAS_ALL_ELEMENTS_PATTERN.matcher(effectText.trim()).matches()) return "HasAllElements";
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
        if (FOLLOWUP_ACTIVATE_AND_GAIN_CONTROL_EOT.matcher(followupText).find())        return "ActivateAndGainControlEOT";
        if (FOLLOWUP_ACTIVATE_AND_NEGATE_DAMAGE.matcher(followupText).find())          return "ActivateAndNegateDamage";
        if (FOLLOWUP_NEGATE_DAMAGE.matcher(followupText).find())                      return "NegateDamage";
        if (FOLLOWUP_GAIN_CONTROL_WHILE_CARD.matcher(followupText).find())            return "GainControlWhileCard";
        if (FOLLOWUP_GAIN_CONTROL_EOT.matcher(followupText).find())                   return "GainControlEOT";
        if (FOLLOWUP_GAIN_CONTROL.matcher(followupText).find())                       return "GainControl";
        if (FOLLOWUP_GAINS_CANNOT_BE_CHOSEN.matcher(followupText).find())             return "GainsCannotBeChosen";
        if (FOLLOWUP_CANNOT_BE_BROKEN.matcher(followupText).find())                  return "CannotBeBroken";
        if (FOLLOWUP_CANNOT_BE_BROKEN_SIMPLE.matcher(followupText).find())           return "CannotBeBrokenSimple";
        if (FOLLOWUP_CANNOT_BE_BROKEN_BY_NON_DMG.matcher(followupText).find())      return "CannotBeBrokenByNonDmg";
        if (FOLLOWUP_GAINS_BREAKTOUCH_BATTLE.matcher(followupText).find())           return "BreaktouchBattle";
        if (FOLLOWUP_CANNOT_BE_CHOSEN_BOTH.matcher(followupText).find())              return "CannotBeChosenBoth";
        if (FOLLOWUP_CANNOT_BE_CHOSEN_SUMMONS.matcher(followupText).find())           return "CannotBeChosenSummons";
        if (FOLLOWUP_CANNOT_BE_CHOSEN_ABILITIES.matcher(followupText).find())         return "CannotBeChosenAbilities";
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
        if (FOLLOWUP_CANNOT_BE_BLOCKED.matcher(followupText).find())                  return "CannotBeBlocked";
        if (FOLLOWUP_CANNOT_BE_BLOCKED_IF_ELEMENT_CP.matcher(followupText).find())   return "CannotBeBlockedIfElementCP";
        if (FOLLOWUP_MUST_BLOCK.matcher(followupText).find())                         return "MustBlock";
        if (FOLLOWUP_CANNOT_ATTACK.matcher(followupText).find())                      return "CannotAttack";
        if (FOLLOWUP_MUST_ATTACK.matcher(followupText).find())                        return "MustAttack";
        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK.matcher(followupText).find())             return "CannotAttackOrBlock";
        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK_PERSISTENT.matcher(followupText).find())  return "CannotAttackOrBlockPersistent";
        if (FOLLOWUP_POWER_BECOMES.matcher(followupText).find())                      return "PowerBecomes";
        if (FOLLOWUP_POWER_BOOST.matcher(followupText).find())                        return "PowerBoost";
        if (FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH.matcher(followupText).find())         return "PowerBoostUntilForEach";
        if (FOLLOWUP_POWER_BOOST_UNTIL.matcher(followupText).find())                  return "PowerBoostUntil";
        if (FOLLOWUP_KEYWORD_GRANT.matcher(followupText).find())                      return "KeywordGrant";
        if (FOLLOWUP_KEYWORD_GRANT_UNTIL.matcher(followupText).find())               return "KeywordGrant";
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
        if (FOLLOWUP_SHIELD_NEXT_ABILITY_DMG_REDUCTION.matcher(followupText).find())   return "ShieldNextAbilityDmgReduction";
        if (FOLLOWUP_SHIELD_NEXT_DMG_REDUCTION.matcher(followupText).find())          return "ShieldNextDmgReduction";
        if (FOLLOWUP_DEBUFF_INCOMING_DMG_INCREASE.matcher(followupText).find())       return "DebuffIncomingDmgIncrease";
        if (FOLLOWUP_SHIELD_NEXT_OUTGOING_ZERO.matcher(followupText).find())          return "ShieldNextOutgoingZero";
        if (FOLLOWUP_SHIELD_NONLETHAL.matcher(followupText).find())                   return "ShieldNonLethal";
        if (FOLLOWUP_GAINS_SHIELD_ABILITY_ONLY.matcher(followupText).find())          return "GainsShieldAbilityOnly";
        if (FOLLOWUP_PUT_TO_BREAK_ZONE.matcher(followupText).find())                  return "PutToBreakZone";
        if (FOLLOWUP_SELECT_NUMBER_REVEAL_BREAK.matcher(followupText).find())         return "SelectNumberRevealBreak";
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
        if (tryParseSelectNumber(effectText, source)          != null) return "SelectNumber";
        if (tryParseDealDamageToForwards(effectText)                != null) return "DealDamageToForwards";
        if (tryParseDealHalfPowerDamageToForwards(effectText)       != null) return "DealHalfPowerDamageToForwards";
        if (tryParseDealHalfSourcePowerDamageToForwards(effectText) != null) return "DealHalfSourcePowerDamageToForwards";
        if (tryParseDamageToCombatBlocker(effectText)               != null) return "DamageToCombatBlocker";

        Matcher chooseM = CHOOSE_CHARACTER_PATTERN.matcher(effectText);
        if (chooseM.find()) {
            String followup      = chooseM.group("followup").trim();
            // Check damage-instead on the full followup before the ". " split eats the condition clause.
            // This mirrors what tryParseChooseAndFollowup does.
            Matcher insteadM = FOLLOWUP_DAMAGE_INSTEAD.matcher(followup);
            if (insteadM.find() && parseDamageInsteadCondition(insteadM.group("cond").trim()) != null)
                return "ChooseCharacter / DamageInstead";
            int    dotIdx        = followup.indexOf(". ");
            String primaryPart   = dotIdx >= 0 ? followup.substring(0, dotIdx).trim() : followup;
            String secondaryTxt  = dotIdx >= 0 ? followup.substring(dotIdx + 2).trim() : null;
            String followupName  = matchedFollowupName(primaryPart, source);
            String secondaryDesc = (secondaryTxt != null && !secondaryTxt.isEmpty())
                    ? fullDescription(secondaryTxt, source) : null;
            if (secondaryDesc == null && secondaryTxt != null && !secondaryTxt.isEmpty())
                secondaryDesc = matchedFollowupName(secondaryTxt, source);
            StringBuilder sb = new StringBuilder("ChooseCharacter / ")
                    .append(followupName != null ? followupName : "?");
            if (secondaryDesc != null) sb.append(" + ").append(secondaryDesc);
            else if (secondaryTxt != null && !secondaryTxt.isEmpty()) sb.append(" + ?");
            return sb.toString();
        }

        if (tryParseCannotBeChosenStandalone(effectText, source) != null)      return "CannotBeChosen";
        if (tryParseNegateAllDamage(effectText) != null)                     return "NegateDamage";
        if (tryParseSelectNumber(effectText, source) != null)               return "SelectNumber";
        if (tryParseAllFieldEffect(effectText) != null)                     return "AllFieldEffect";
        if (tryParseAllFieldPowerBoost(effectText) != null)                 return "AllFieldPowerBoost";
        if (tryParseStandalonePowerBoostAndAttackTrigger(effectText, source) != null) return "StandalonePowerBoostAndAttackTrigger";
        if (tryParseStandalonePowerBoostUntil(effectText, source) != null)  return "StandalonePowerBoostUntil";
        if (tryParseStandaloneDoublePowerUntil(effectText, source) != null) return "StandaloneDoublePowerUntil";
        if (tryParseStandaloneDoublesItsPowerUntil(effectText, source) != null) return "StandaloneDoublesItsPowerUntil";
        if (tryParseStandaloneDoublePowerMainPhaseNextTurn(effectText, source) != null) return "StandaloneDoublePowerMainPhaseNextTurn";
        if (tryParseStandalonePowerReduceUntil(effectText, source) != null) return "StandalonePowerReduceUntil";
        if (tryParseStandaloneSelfBoost(effectText, source) != null)        return "StandaloneSelfBoost";
        if (tryParseStandaloneShieldCannotBeBroken(effectText, source) != null) return "StandaloneShieldCannotBeBroken";
        if (tryParseRevealSelectHandRfp(effectText) != null)               return "RevealSelectHandRfp";
        if (tryParseOpponentRandomHandRfp(effectText) != null)             return "OpponentRandomHandRfp";
        if (tryParseOpponentHandRfp(effectText) != null)                   return "OpponentHandRfp";
        if (tryParseReturnNamedToHand(effectText) != null)                   return "ReturnNamedToHand";
        if (tryParseRemoveNamedFromGame(effectText, source) != null)        return "RemoveNamedFromGame";
        if (tryParseOpponentDrawThenRandomDiscard(effectText) != null)      return "OpponentDrawThenRandomDiscard";
        if (tryParseOpponentRandomDiscard(effectText) != null)              return "OpponentRandomDiscard";
        if (tryParseOpponentDiscard(effectText) != null)                    return "OpponentDiscard";
        if (tryParseDrawCards(effectText) != null)                          return "DrawCards";
        if (tryParseDiscardHandThenDraw(effectText) != null)                return "DiscardHandThenDraw";
        if (tryParseDiscardHand(effectText) != null)                        return "DiscardHand";
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
        if (tryParseLookTopDeckOptionallyBreak(effectText)        != null) return "LookTopDeckOptionallyBreak";
        if (tryParseLookTopDeckBottomOrKeep(effectText)           != null) return "LookTopDeckBottomOrKeep";
        if (tryParseLookTopDeckAddToHandRestBottom(effectText)    != null) return "LookTopDeckAddToHandRestBottom";
        if (tryParseLookTopDeckAddToHandRestBreak(effectText)     != null) return "LookTopDeckAddToHandRestBreak";
        if (tryParseLookTopDeckTopOrBottom(effectText)            != null) return "LookTopDeckTopOrBottom";
        if (tryParseLookTopDeckReturnTopOrdered(effectText)       != null) return "LookTopDeckReturnTopOrdered";
        if (tryParseLookTopDeckPeek(effectText)                   != null) return "LookTopDeckPeek";
        if (tryParseRemoveTopOfDeckFromGame(effectText)            != null) return "RemoveTopOfDeckFromGame";
        if (tryParseShuffleDeck(effectText)                        != null) return "ShuffleDeck";
        if (tryParseBackupCpDraw(effectText)                       != null) return "BackupCpDraw";
        if (tryParseConditionalOpponentHand(effectText, source, 0) != null) return "ConditionalOpponentHand";
        if (SELECT_FOLLOWING_ACTIONS_DETECT.matcher(effectText).find())    return "SelectFollowingActions";
        if (CardData.HAS_ALL_ELEMENTS_PATTERN.matcher(effectText.trim()).matches()) return "HasAllElements";
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
        if (!m.find()) {
            m = DEAL_DAMAGE_TO_FORWARDS_ALT.matcher(text);
            if (!m.find()) return null;
        }

        int    damage        = Integer.parseInt(m.group("amount"));
        String condition     = m.group("condition");   // nullable
        String costStr       = m.group("cost");
        int    costVal       = costStr != null ? Integer.parseInt(costStr) : -1;
        String costCmp       = m.group("costcmp");
        String excludeJob    = m.group("excludejob") != null ? m.group("excludejob").trim() : null;
        boolean opponentOnly = m.group("opponent") != null;
        boolean unreduced    = CANNOT_BE_REDUCED_PATTERN.matcher(text).find();

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
                    if (excludeJob != null && excludeJob.equalsIgnoreCase(c.job())) continue;
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
                    if (excludeJob != null && excludeJob.equalsIgnoreCase(c.job())) continue;
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
        java.util.regex.Matcher oppDmgM = java.util.regex.Pattern
                .compile("(?i)your opponent has received (\\d+) points? of damage or more").matcher(s);
        if (oppDmgM.find())
            return new DamageInsteadCondition.OpponentDamageAtLeast(Integer.parseInt(oppDmgM.group(1)));

        // Opponent hand size: "your opponent has N cards or less in their hand"
        java.util.regex.Matcher oppHandM = java.util.regex.Pattern
                .compile("(?i)your opponent has (\\d+) cards? or (?:less|fewer) in their hand").matcher(s);
        if (oppHandM.find())
            return new DamageInsteadCondition.OpponentHandAtMost(Integer.parseInt(oppHandM.group(1)));

        // Cards cast this turn: "you have cast N or more cards this turn"
        java.util.regex.Matcher castM = java.util.regex.Pattern
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

        // Return + draw must precede plain return (draw extends the return text)
        java.util.regex.Matcher retDrawM = FOLLOWUP_RETURN_AND_DRAW.matcher(t);
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
        java.util.regex.Matcher reduceM = FOLLOWUP_POWER_REDUCE.matcher(t);
        if (reduceM.find()) {
            int reduction = reduceM.group(1) != null ? Integer.parseInt(reduceM.group(1)) : 0;
            EnumSet<CardData.Trait> traits = parseTraits(reduceM.group(2));
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.reduceTarget(ft, reduction, traits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.reduceTarget(ft, reduction, traits));
            };
        }
        java.util.regex.Matcher reduceUntilM = FOLLOWUP_POWER_REDUCE_UNTIL.matcher(t);
        if (reduceUntilM.find()) {
            int reduction = reduceUntilM.group(1) != null ? Integer.parseInt(reduceUntilM.group(1)) : 0;
            EnumSet<CardData.Trait> traits = parseTraits(reduceUntilM.group(2));
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.reduceTarget(ft, reduction, traits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.reduceTarget(ft, reduction, traits));
            };
        }

        // Deal N damage for each [Category X] Type you control
        java.util.regex.Matcher forEachM = FOLLOWUP_DAMAGE_FOR_EACH.matcher(t);
        if (forEachM.find() && forEachM.group("chartype") != null) {
            int    baseDmg  = Integer.parseInt(forEachM.group("base"));
            String charType = forEachM.group("chartype");
            String category = forEachM.group("category") != null ? forEachM.group("category").trim() : null;
            boolean fwd = charType.matches("(?i)Forwards?|Characters?");
            boolean bkp = charType.matches("(?i)Backups?|Characters?");
            boolean mon = charType.matches("(?i)Monsters?|Characters?");
            return (ctx, ts) -> {
                int n = ctx.countSelfFieldCards(fwd, bkp, mon, null, null, category);
                int damage = baseDmg * n;
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
    private static Consumer<GameContext> tryParseChooseCharacter(String text, CardData source, int xValue) {
        Matcher m = CHOOSE_CHARACTER_PATTERN.matcher(text);
        if (!m.find()) return null;

        boolean upTo         = m.group("upto") != null;
        int     maxCount     = Integer.parseInt(m.group("count"));
        String  element      = m.group("element");
        // Resolve condition: "blocking [Name]"/"blocking a Job [Job]" overrides the standard condition.
        // Post-target qualifiers ("that entered the field this turn") are normalized to the same string.
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
        boolean inclSummons  = tgtLower.contains("summon");
        String  categoryFilter = m.group("category");
        String  excludeName      = m.group("excludename");
        String  rawExcludeKw     = m.group("excludekw");
        boolean withoutMulticard = "Multicard".equalsIgnoreCase(rawExcludeKw != null ? rawExcludeKw.trim() : null);
        String  rawExcludeElem = m.group("excludeelem");
        final String fExcludeElem = rawExcludeElem != null ? rawExcludeElem.trim() : null;
        String  costStr      = m.group("cost");
        String  rawCostCmp   = m.group("costcmp");
        int     costVal      = costStr != null ? Integer.parseInt(costStr) : -1;
        // Convert "cost N or M" (digit costcmp) to the "or_M" sentinel understood by meetsCostConstraint
        String  costCmp      = (rawCostCmp != null && rawCostCmp.matches("\\d+"))
                ? "or_" + rawCostCmp : rawCostCmp;
        String  powerStr     = m.group("power");
        String  powerCmp     = m.group("powercmp");
        int     powerVal     = powerStr != null ? Integer.parseInt(powerStr) : -1;
        String  control      = m.group("control");
        boolean opponentOnly = control != null && !control.equalsIgnoreCase("you control");
        boolean selfOnly     = "you control".equalsIgnoreCase(control);
        String  zone         = m.group("zone");
        boolean opponentZone = zone != null && zone.toLowerCase().contains("opponent");

        String  followup     = m.group("followup").trim();
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
                secondaryText   = followup.substring(dotSpaceIdx + 2).trim();
                Consumer<GameContext> parsed = secondaryText.isEmpty() ? null : parse(secondaryText, source);
                secondary = (parsed != null) ? parsed
                        : ctx -> ctx.logEntry("[ActionResolver] Secondary followup not yet implemented: " + secondaryText);
            } else {
                primaryFollowup = followup;
                secondaryText   = null;
                secondary = null;
            }
        }

        // Shared log prefix helper (captured once, reused in all lambdas)
        String costCmpDisplay = costCmp != null && costCmp.startsWith("or_")
                ? " or " + costCmp.substring(3) : (costCmp != null ? " or " + costCmp : "");
        String costLabel     = costVal >= 0 ? " of cost " + costVal + costCmpDisplay : "";
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

        // --- "Deal it N damage for each [source]" followup ---
        Matcher forEachM = FOLLOWUP_DAMAGE_FOR_EACH.matcher(primaryFollowup);
        if (forEachM.find()) {
            int    baseDmg        = Integer.parseInt(forEachM.group("base"));
            String perStr         = forEachM.group("per");
            int    perDmg         = perStr != null ? Integer.parseInt(perStr) : 0;
            boolean srcSelfDmg    = forEachM.group("selfdmg")  != null;
            String  srcJobBracket = forEachM.group("jobbname") != null ? forEachM.group("jobbname").trim() : null;
            String  srcJobWritten = forEachM.group("jobwname") != null ? forEachM.group("jobwname").trim() : null;
            String  srcJobWType   = forEachM.group("jobwtype") != null ? forEachM.group("jobwtype").trim() : null;
            String  srcCharType   = forEachM.group("chartype");
            String  srcCategory   = srcCharType != null && forEachM.group("category") != null ? forEachM.group("category").trim() : null;
            String  srcBzName     = forEachM.group("bzname")   != null ? forEachM.group("bzname").trim()   : null;
            boolean srcOppHand    = forEachM.group("opphand")  != null;
            // if none of the above → xpaid
            boolean charFwd = srcCharType != null && (srcCharType.equalsIgnoreCase("forward")   || srcCharType.equalsIgnoreCase("forwards")   || srcCharType.equalsIgnoreCase("character") || srcCharType.equalsIgnoreCase("characters"));
            boolean charBkp = srcCharType != null && (srcCharType.equalsIgnoreCase("backup")    || srcCharType.equalsIgnoreCase("backups")    || srcCharType.equalsIgnoreCase("character") || srcCharType.equalsIgnoreCase("characters"));
            boolean charMon = srcCharType != null && (srcCharType.equalsIgnoreCase("monster")   || srcCharType.equalsIgnoreCase("monsters")   || srcCharType.equalsIgnoreCase("character") || srcCharType.equalsIgnoreCase("characters"));
            String sourceLabel;
            if      (srcSelfDmg)           sourceLabel = "P1 damage";
            else if (srcJobBracket != null) sourceLabel = "[Job (" + srcJobBracket + ")] you control";
            else if (srcJobWritten != null) sourceLabel = "Job " + srcJobWritten + (srcJobWType != null ? " " + srcJobWType : "") + " you control";
            else if (srcCharType   != null) sourceLabel = (srcCategory != null ? "Category " + srcCategory + " " : "") + srcCharType + " you control";
            else if (srcBzName     != null) sourceLabel = "Card Name " + srcBzName + " in BZ";
            else if (srcOppHand)           sourceLabel = "opponent hand";
            else                            sourceLabel = "X CP paid";
            String logLabel = perDmg > 0
                    ? baseDmg + " + " + perDmg + "×[" + sourceLabel + "]"
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
                else if (srcCharType   != null) n = ctx.countSelfFieldCards(charFwd, charBkp, charMon, null, null, srcCategory);
                else if (srcBzName     != null) n = ctx.countSelfBreakZoneCards(srcBzName, null);
                else if (srcOppHand)           n = ctx.opponentHandSize();
                else                            n = xValue;
                int damage = perDmg > 0 ? baseDmg + perDmg * n : baseDmg * n;
                ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (" + logLabel + ", n=" + n + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
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
                String unredSuffix = unreduced ? " (cannot be reduced)" : "";
                ctx.logEntry(alsoCard != null
                        ? choosePrefix + " — Deal " + damage + " damage (and to " + alsoCard + ")" + unredSuffix
                        : choosePrefix + " — Deal " + damage + " damage" + unredSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (unreduced) {
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTargetUnreduced(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTargetUnreduced(t, damage));
                } else {
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                }
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
            java.util.regex.Matcher gcM = FOLLOWUP_GAINS_CANNOT_BE_CHOSEN.matcher(fp);
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

        // --- Dull followup ---
        if (FOLLOWUP_DULL.matcher(primaryFollowup).find()
                && !FOLLOWUP_DULL_AND_FREEZE.matcher(primaryFollowup).find()) {
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
        if (FOLLOWUP_PLAY_ONTO_FIELD.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Play onto Field");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
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
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
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

    /** Returns a human-readable list of trait names, e.g. {@code "First Strike and Brave"}, or {@code ""}. */
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
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry(source.name() + " cannot be broken until end of turn");
            ctx.shieldSourceForward(source);
        };
    }

    /** Parses "Draw N card(s)[, then discard M card(s)]" as a standalone effect. */
    private static final Pattern WHEN_YOU_DO_SO_SEQUENCE = Pattern.compile(
        "(?is)(?<primary>.+?)\\.\\s+(?:When|If)\\s+you\\s+do\\s+so,?\\s+(?<followup>.+)"
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

    /** Matches a leading "If you [do not] control &lt;condition&gt;, &lt;effect&gt;" gate. */
    private static final Pattern CONTROL_CONDITION_GATE = Pattern.compile(
        "(?is)^if\\s+you\\s+(?<neg>do\\s+not\\s+|don't\\s+)?control\\s+(?<cond>.+?),\\s+(?<effect>.+)$"
    );

    /**
     * Parses "If you [do not] control X, Y" — resolves Y only when the control condition is
     * (un)met at resolution time. Returns {@code null} when the condition or inner effect cannot
     * be parsed so the text falls through to the regular matchers (preserving prior behaviour).
     */
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
        return null;
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
        String logMsg = actionLabel + " all " + tgtLabel + costLabel + exclLabel + controlLabel;

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldEffect(action, inclForwards, inclBackups, inclMonsters,
                    opponentOnly, selfOnly, element, costVal, costCmp, excludeCostVal, job, category);
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
        String logMsg = "All " + elemLabel + catLabel + targets + costLabel + controlLabel
                + " " + change + " power until end of turn";

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldPowerBoost(amount, inclForwards, inclMonsters,
                    opponentOnly, selfOnly, element, costVal, costCmp, category);
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

        return ctx -> ctx.logEntry(
                "[ActionResolver] Opponent selects — followup not yet implemented: " + followup);
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

        // 4. Named card: "[Name] cannot be chosen" — only when name matches source
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
        java.util.regex.Matcher m = LOOK_TOP_DECK_RETURN_TOP_ORDERED.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) — return to top in any order");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.RETURN_TOP_ORDERED));
        };
    }

    private static Consumer<GameContext> tryParseLookTopDeckAddToHandRestBottom(String text) {
        java.util.regex.Matcher m = LOOK_TOP_DECK_ADD_TO_HAND_REST_BOTTOM.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) — add 1 to hand, return rest to bottom");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.ADD_TO_HAND_REST_BOTTOM));
        };
    }

    private static Consumer<GameContext> tryParseLookTopDeckAddToHandRestBreak(String text) {
        java.util.regex.Matcher m = LOOK_TOP_DECK_ADD_TO_HAND_REST_BREAK.matcher(text);
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
        java.util.regex.Matcher m = LOOK_TOP_DECK_TOP_OR_BOTTOM.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) — return to top or bottom in any order");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.TOP_OR_BOTTOM_ORDERED));
        };
    }

    private static Consumer<GameContext> tryParseLookTopDeckPeek(String text) {
        java.util.regex.Matcher m = LOOK_TOP_DECK_PEEK.matcher(text);
        if (!m.find()) return null;
        String countStr = m.group("count");
        int count = (countStr != null) ? Integer.parseInt(countStr) : 1;
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) of deck");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.PEEK));
        };
    }

    private static Consumer<GameContext> tryParseRemoveTopOfDeckFromGame(String text) {
        java.util.regex.Matcher m = REMOVE_TOP_OF_DECK_FROM_GAME.matcher(text);
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

        Matcher m = BECOME_FORWARD_UNTIL_EOT_PATTERN.matcher(text);
        if (!m.find()) return null;
        int power = Integer.parseInt(m.group("power"));
        return ctx -> {
            ctx.logEntry(source.name() + " becomes a Forward with " + power + " power until end of turn");
            ctx.makeMonsterTemporaryForward(source, power);
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

    private static Consumer<GameContext> tryParseCostReductionThisTurn(String text) {
        Matcher m = COST_REDUCTION_THIS_TURN.matcher(text);
        if (!m.find()) return null;

        String elementRaw  = m.group("element");
        String categoryRaw = m.group("category");
        String jobRaw      = m.group("job");
        String cardnameRaw = m.group("cardname");
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
        final String typeDesc = cardname != null ? "Card Name " + cardname
                              : typeRaw  != null ? typeRaw : "card";

        CostReductionModifier modifier = new CostReductionModifier(
                amount, floorAtOne,
                inclForwards, inclBackups, inclMonsters, inclSummons,
                element, job, cardname, category);

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

    private static List<ForwardTarget> selectTargets(GameContext ctx,
            int maxCount, boolean upTo, boolean opponentOnly, boolean selfOnly,
            String condition, String element, String zone, boolean opponentZone,
            int costVal, String costCmp, int powerVal, String powerCmp,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons,
            String excludeElement, boolean withoutMulticard) {
        return zone != null
                ? ctx.selectCharactersFromBreakZone(maxCount, upTo, opponentZone, condition, element,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, excludeElement, withoutMulticard)
                : ctx.selectCharacters(maxCount, upTo, opponentOnly, selfOnly, condition, element,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, excludeElement, withoutMulticard);
    }
}
