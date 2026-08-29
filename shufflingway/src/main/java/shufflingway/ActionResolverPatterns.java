package shufflingway;

import java.util.regex.Pattern;

/**
 * Compiled regexes and literal constants for the {@code ActionResolver} family.
 *
 * <p>Split out of {@link ActionResolver} purely for size: these were already declared
 * far from the parsers that use them, so nothing here is more remote than it was.
 * Declaration order is preserved, because a few initialisers build on earlier ones.
 *
 * <p>The resolver classes pick these up through a wildcard static import, so they are
 * referenced by simple name exactly as before.
 */
final class ActionResolverPatterns {

    private ActionResolverPatterns() {}


    // =========================================================================================
    // Choose: target patterns and mixed-type selections
    // =========================================================================================
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
    static final Pattern CHOOSE_CHARACTER_PATTERN = Pattern.compile(
            "(?i)Choose\\s+" +
                    "(?:(?<anycount>any\\s+number)|(?<upto>up\\s+to\\s+)?(?<count>\\d+))\\s+(?:of\\s+)?" +
                    "(?:(?<condition>dull|damaged|attacking|blocking|active)\\s+)?" +
                    "(?:(?<element>(?:Multi-Element|Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*)\\s+)?" +
                    "(?:Category\\s+(?<category>.+?)(?=\\s+(?:cards?|Forwards?|Backups?|Characters?|Monsters?|Summons?))\\s+)?" +
                    // "Summon or Monster" (Citra 10-127H) is the Break Zone's own union, and the
                    // only one this alternation lacked: without the tail the phrase matched
                    // "Summon" alone, the leftover " or Monster" met no followup separator and the
                    // whole choice failed — leaving the trailing sentence for a find()-based parser
                    // below to claim on its own, selection and all silently dropped.
                    "(?<targets>cards?|Forwards?(?:\\s+(?:and/or|or)\\s+(?:Monsters?|Backups?))?|Monsters?|Backups?|Characters?" +
                    "|Summons?(?:\\s+(?:and/or|or)\\s+(?:Monsters?|Forwards?|Backups?))?" +
                    "|\\[Job\\s+\\([^)]+\\)\\]" +
                    "|\\[Card\\s+Name\\s+\\([^)]+\\)\\]" +
                    "|Card\\s+Name\\s+.+?\\s+Forwards?(?:\\s+or\\s+Job\\s+.+?\\s+Forwards?)*" +
                    "|Card\\s+Name\\s+\\S+(?:\\s+\\S+)*?(?:\\s+\\([^)]+\\))?(?:\\s+or\\s+Card\\s+Name\\s+\\S+(?:\\s+\\S+)*?(?:\\s+\\([^)]+\\))?)*" +
                    "|Job\\s+.+?\\s+(?:and/)?or\\s+Card\\s+Name\\s+\\S+" +
                    "|Job\\s+.+?\\s+Forwards?(?:\\s+or\\s+Job\\s+.+?\\s+Forwards?)*" +
                    // The control clause ends the job phrase too — "Job Class Zero Cadet Characters
                    // you control" (Eight 3-051R). Without it the phrase ran to the full stop, took
                    // "you control" into the Job name and matched nobody, and the control group it
                    // belongs to came back null.
                    "|Job\\s+.+?(?=\\s+(?:of\\s+|other\\s+than|in\\s+your|from\\s+your" +
                    "|you\\s+control|(?:your\\s+)?opponent\\s+controls)|[,.]))" +
                    "(?:\\s+Cards?)?" +
                    // "1 Monster that is also a Forward" (Lann 10-017R, Relm 24-107L and four
                    // others) — a Monster some effect has made a Forward for the turn, which is a
                    // pool and not a card kind. Without it the phrase left " that is also a
                    // Forward" unmatched before the followup separator and the whole choice
                    // failed, so the trailing sentence was claimed on its own: Relm's "It gains
                    // +2000 power" was read as a self-boost and lent the power to Relm.
                    "(?:\\s+that\\s+(?:is|are)\\s+also\\s+(?:an?\\s+)?(?<alsoforward>Forwards?))?" +
                    "(?:\\s+with\\s+(?<trait>Brave|Haste|First\\s+Strike))?" +
                    "(?:\\s+that\\s+(?<postcondition>entered\\s+the\\s+field\\s+this\\s+turn|entered\\s+this\\s+turn))?" +
                    "(?:\\s+without\\s+《(?<excludekw>[^》]+)》)?" +
                    // The inclusive counterpart of the arm above — "Choose 1 Forward with 《LB》 of
                    // cost 6 or less" (26-087R Odin). Without it the phrase left " with 《LB》"
                    // unmatched before the followup separator and the whole choice failed, so
                    // Odin's second action read as unimplemented.
                    "(?:\\s+with\\s+《(?<withkw>[^》]+)》)?" +
                    "(?:\\s+of\\s+(?:any|an)\\s+Element\\s+(?:except|other\\s+than)\\s+(?<excludeelem>" +
                    "(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
                    "(?:\\s+and\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*))?" +
                    "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)" +
                    "(?:,\\s*(?<costlist>\\d+(?:\\s*,\\s*\\d+)*))?" +
                    "(?:\\s+or\\s+(?<costcmp>less|more|higher|\\d+))?)?" +
                    // "with 9000 power or less" (Zodiark 23-016R) is the same constraint that
                    // "of power 9000 or less" states the other way round. Only the "of" spelling
                    // was here, so that card's opening choice matched nothing at all: the clause
                    // is optional, but what follows it is the mandatory followup separator, so the
                    // leftover "with 9000 power or less" sank the whole pattern. A digit has to
                    // come next either way, which keeps this clear of "with 《LB》" and of the
                    // trait clause above.
                    "(?:\\s+(?:of|with)\\s+(?:power\\s+)?(?<power>\\d+)(?:\\s+power)?(?:\\s+or\\s+(?<powercmp>less|more))?)?" +
                    "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls|you\\s+control))?" +
                    "(?:\\s+other\\s+than\\s+(?:Card\\s+Name\\s+)?(?<excludename>\\S(?:.*?\\S)?)" +
                    // "other than Card Name Leo, Light or Dark" (Leo 13-067L) — one "other than"
                    // governing a card name and then a list of Elements. Attached to the name
                    // rather than given a clause of its own because that is how it is printed;
                    // without it the lazy name group stopped at "Leo" and the comma became the
                    // followup separator, which swallowed the Elements *and* the "in your Break
                    // Zone" that followed them, so the choice read the field instead.
                    "(?:,\\s*(?<excludeelemlist>(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
                    "(?:\\s+(?:or|and)\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*))?)?" +
                    // Both orders are printed: "1 Forward you control other than X" and
                    // "1 Character other than X you control" (12-021R Necron and 16 others). Only
                    // the first has a place above, so without this the exclusion group — lazy, but
                    // bounded only by the followup separator — ran straight through the control
                    // clause and came back as "Necron you control", a name that matches nobody,
                    // while the control group stayed null and let the choice cross the table.
                    // Being optional and immediately after the lazy group, this is what the engine
                    // tries first, so the shorter exclusion wins whenever the clause is present.
                    "(?:\\s+(?<control2>(?:your\\s+)?opponent\\s+controls|you\\s+control))?" +
                    // "put in your Break Zone from the field during this turn" (Rydia 17-083C,
                    // Maenad 25-032C, Muraga Fennes 14-073R, Regis 12-122L) — a Break Zone choice
                    // narrowed to what arrived there from the field on this turn. It names the zone
                    // and a condition at once, which is why it is read here rather than through the
                    // "that …" post-condition slot further up: the words sit where the zone does.
                    //
                    // Ahead of the plain zone group and requiring the whole phrase, so it can only
                    // claim these four printings. Left to the plain group, the sentence matched as
                    // far as "Break Zone", the leftover " from the field during this turn" met no
                    // followup separator and the choice failed outright — which handed the trailing
                    // sentence to a find()-based parser below and dropped the selection.
                    "(?:\\s+put\\s+(?<bzfieldzone>(?:in|from)\\s+(?:your(?:\\s+opponent(?:'s)?)?|the)\\s+Break\\s+Zone)" +
                    "\\s+from\\s+the\\s+field\\s+during\\s+this\\s+turn)?" +
                    // "into" is a misprint for "in" on 2-049H Asura ("Choose 1 Character card of
                    // cost 2 or less into your Break Zone"), corrected on its Re-059H reprint.
                    // Safe to accept here because this group sits between the target descriptor and
                    // the followup separator: a card that puts something *into* the Break Zone says
                    // so after that separator, where this cannot reach it.
                    "(?:\\s+(?<zone>(?:in(?:to)?|from)\\s+(?:your(?:\\s+opponent(?:'s)?)?|the|either\\s+player'?s|any\\s+player'?s)\\s+Break\\s+Zone))?" +
                    "(?:\\s+blocking\\s+" +
                    "(?:(?:a\\s+(?:Job\\s+)?(?<blockingjob>[^.,]+?)(?=\\s*[.,]))" +
                    "|(?<blockingname>[^.,]+?)(?=\\s*[.,])))?" +
                    "(?:[.]\\s+|\\s+and\\s+|,\\s*)" +
                    "(?<followup>.+)"
    );
    /**
     * Matches "Choose N [Job X … or Card Name Y] [targets] you control and N [targets] opponent
     * controls. [followup]" — one selection from the active player's side and one from the
     * opponent's side.
     *
     * <p>The optional qualifier covers 14-074C / 12-070C Monk, whose own side is restricted to
     * "1 Job Monk Forward or Card Name Monk Forward". Groups {@code job1} and {@code name1} are an
     * <em>either/or</em>, which is how the selection layer already reads a job and a card name
     * supplied together. Both are null for the unqualified form, which is every other card here.
     */
    static final Pattern CHOOSE_ONE_EACH_PATTERN = Pattern.compile(
        "(?i)Choose\\s+(?<count1>\\d+)\\s+" +
        "(?:Job\\s+(?<job1>[^.]+?)\\s+(?:Forwards?|Backups?|Characters?|Monsters?)" +
        "\\s+or\\s+Card\\s+Name\\s+(?<name1>[^.]+?)\\s+)?" +
        "(?<targets1>Forwards?|Backups?|Characters?|Monsters?)\\s+" +
        "you\\s+control\\s+and\\s+(?<count2>\\d+)\\s+" +
        "(?<targets2>Forwards?|Backups?|Characters?|Monsters?)\\s+" +
        "(?:your\\s+)?opponent\\s+controls[.]?\\s+" +
        "(?<followup>.+)"
    );

    // =========================================================================================
    // "The former / the latter" pair effects
    // =========================================================================================
    /**
     * Matches "The former gains +N power until end of turn. Then, the former deals damage equal
     * to its power to the latter." — boost the former, then deal the (post-boost) power as damage to the latter.
     * Group {@code boost} = numeric power amount.
     */
    static final Pattern FORMER_BOOST_THEN_POWER_DAMAGE_TO_LATTER = Pattern.compile(
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
    static final Pattern CHOOSE_FORWARD_REDIRECT_TO_NAMED = Pattern.compile(
        "(?i)Choose\\s+1\\s+Forward\\s+you\\s+control\\s+other\\s+than\\s+(?<shield>[A-Za-z][^.]+?)[.!]\\s+" +
        "During\\s+this\\s+turn[,.]?\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+it\\s+" +
        "is\\s+(?:received\\s+by|dealt\\s+to)\\s+(?<redirect>[A-Za-z][^.!]+?)\\s+instead[.!]?"
    );
    /**
     * Matches "During this turn, the next damage dealt to the former is received by / dealt to the latter instead."
     * — one-shot damage redirect from former to latter, with an optional trailing bonus clause.
     * Group {@code suffix} = optional bonus text (e.g. BACKUP_CP_DRAW).
     */
    static final Pattern FORMER_LATTER_DAMAGE_REDIRECT = Pattern.compile(
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
    static final Pattern FORMER_BOOST_TRAITS_LATTER_DIRECT_DAMAGE = Pattern.compile(
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
    static final Pattern FORMER_LOSES_TRAITS_LATTER_GAINS = Pattern.compile(
        "(?i)Until\\s+the\\s+end\\s+of\\s+the\\s+turn[,.]?\\s+the\\s+former\\s+loses\\s+" +
        "(?<traits>[^.]+?)[.]\\s+Then[,.]?\\s+the\\s+latter\\s+gains\\s+all\\s+the\\s+abilities\\s+" +
        "lost\\s+by\\s+the\\s+previous\\s+effect\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn[.!]?"
    );
    /**
     * Matches escalating BZ-count conditionals for former/latter: always dull former; if ≥N1
     * Card Name X in BZ dull latter; if ≥N2 freeze both; if ≥N3 opponent discards.
     */
    static final Pattern FORMER_DULL_LATTER_BZ_NAME_ESCALATE = Pattern.compile(
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
    static final Pattern FORMER_BOOST_DULL_IMMUNITY_COND_DAMAGE_LATTER = Pattern.compile(
        "(?i)Until\\s+the\\s+end\\s+of\\s+the\\s+turn[,.]?\\s+the\\s+former\\s+gains\\s+" +
        // The quote closes on ." — the grant's own full stop and then the delimiter, two characters
        // where a single \W slot used to sit. 13-053R Alexander, the one card in this shape, was
        // declined on that alone and fell through to the plain choose parser, which read the
        // selection and then neither half of the effect.
        "\\+(?<boost>\\d+)\\s+power\\s+and\\s+[\"']?This\\s+Forward\\s+cannot\\s+become\\s+dull\\s+" +
        "by\\s+your\\s+opponent.s\\s+Summons?\\s+or\\s+abilities[.!]?[\"']?\\s+" +
        "If\\s+you\\s+have\\s+received\\s+(?<dmgthresh>\\d+)\\s+(?:points?\\s+of\\s+)?damage\\s+or\\s+more[,.]?\\s+" +
        "also\\s+deal\\s+the\\s+latter\\s+damage\\s+equal\\s+to\\s+the\\s+highest\\s+power\\s+" +
        "Forward\\s+you\\s+control[.!]?"
    );
    /**
     * Matches "The former deals damage equal to its power to the latter."
     * — former deals its current power as damage to the latter (no boost).
     *
     * <p>Two wordings, one effect. 23-069C Narasimha and 16-078C Demonolith name the recipient
     * last ("deals damage equal to its power <b>to the latter</b>"); 2-093H Raubahn uses the
     * ditransitive form and names it first ("deals <b>the second</b> damage equal to its power").
     * "first one"/"second" are the same two chosen targets "former"/"latter" refer to, and "its"
     * is the former's power in both.
     */
    static final Pattern FORMER_DEALS_POWER_DAMAGE_TO_LATTER = Pattern.compile(
        "(?i)The\\s+(?:former|first(?:\\s+one)?)\\s+deals?\\s+" +
        "(?:damage\\s+equal\\s+to\\s+its\\s+power\\s+to\\s+the\\s+(?:latter|second(?:\\s+one)?)" +
        "|the\\s+(?:latter|second(?:\\s+one)?)\\s+damage\\s+equal\\s+to\\s+its\\s+power)[.!]?"
    );
    /**
     * Matches "Break the former. If [card] enters the field due to Warp, also break the latter."
     * — always break the former; break the latter only when the source entered via Warp.
     */
    static final Pattern FORMER_BREAK_COND_WARP_LATTER_BREAK = Pattern.compile(
        "(?i)Break\\s+the\\s+former[.!]?\\s+If\\s+.+?\\s+enters\\s+the\\s+field\\s+due\\s+to\\s+Warp[,.]?\\s+" +
        "also\\s+break\\s+the\\s+latter[.!]?"
    );
    /**
     * Matches "Deal the former N damage. If you control M or more Backups, also deal the latter N damage."
     * Groups: {@code dmg1} = former damage; {@code n} = backup threshold; {@code dmg2} = latter damage.
     */
    static final Pattern FORMER_DAMAGE_COND_BACKUP_COUNT_LATTER_DAMAGE = Pattern.compile(
        "(?i)Deal\\s+the\\s+former\\s+(?<dmg1>\\d+)\\s+damage[.!]?\\s+" +
        "If\\s+you\\s+control\\s+(?<n>\\d+)\\s+or\\s+more\\s+Backups?[,.]?\\s+" +
        "also\\s+deal\\s+the\\s+latter\\s+(?<dmg2>\\d+)\\s+damage[.!]?"
    );
    /**
     * Matches desc2 text "Backup with a cost equal to or less than that Forward in your Break Zone"
     * — a relative cost constraint that depends on the first chosen target at execution time.
     */
    static final Pattern DESC_BZ_BACKUP_COST_RELATIVE = Pattern.compile(
        "(?i)Backup\\s+with\\s+a\\s+cost\\s+equal\\s+to\\s+or\\s+less\\s+than\\s+" +
        "(?:that\\s+Forward|the\\s+former)\\s+in\\s+(?:your|the)\\s+Break\\s+Zone"
    );
    /**
     * Matches "If you have cast a Card Name [X] other than [X] this turn, also [effect]."
     * Fires when the ability owner has cast another copy of the named card earlier this turn.
     * Group {@code name} = the card name; group {@code effect} = the bonus effect text.
     */
    static final Pattern CAST_CARD_NAME_OTHER_BONUS = Pattern.compile(
        "(?i)[.]?\\s*If\\s+you\\s+have\\s+cast\\s+(?:a\\s+)?Card\\s+Name\\s+(?<name>.+?)" +
        "\\s+other\\s+than\\s+.+?\\s+this\\s+turn[,.]?\\s+also\\s+(?<effect>.+)"
    );
    /**
     * Matches "Choose [up to] N [desc1] and [up to] N [desc2]. [effects]"
     * where the effects text uses "the former" and "the latter" as pronouns for the two target groups.
     */
    static final Pattern CHOOSE_FORMER_LATTER_PATTERN = Pattern.compile(
        "(?i)^Choose\\s+(?<upTo1>up\\s+to\\s+)?(?<count1>\\d+)\\s+(?<desc1>.+?)" +
        "\\s+and\\s+(?<upTo2>up\\s+to\\s+)?(?<count2>\\d+)\\s+(?<desc2>.+?)[.]\\s*" +
        "(?<effects>.+)$",
        Pattern.DOTALL
    );

    // =========================================================================================
    // Target descriptors and identity phrase fragments
    // =========================================================================================
    /**
     * Parses a single target description in a CHOOSE_FORMER_LATTER clause:
     * "[condition] [element] CardType [of cost N [or less|more]] [control] [zone]"
     */
    static final Pattern TARGET_DESC_PATTERN = Pattern.compile(
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
    static final Pattern CHOOSE_TWO_MIXED_TYPES_PATTERN = Pattern.compile(
        "(?i)Choose\\s+(?<count1>\\d+)\\s+(?<type1>Forwards?|Backups?|Characters?|Monsters?)\\s+" +
        "and\\s+(?<count2>\\d+)\\s+(?<type2>Forwards?|Backups?|Characters?|Monsters?)" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+controls?))?[.]?\\s+" +
        "(?<followup>.+)"
    );
    /**
     * Matches Mont Leonis 22-113L's Break Zone recursion:
     * "Choose 1 [Elem] Forward of cost N or less in your Break Zone and 1 [Elem] Forward of cost M
     * or less in your Break Zone. If you control [cond], play them onto the field. They gain
     * [trait] until the end of the turn. Then, put K [type] you control into the Break Zone."
     *
     * <p>The control condition governs the whole tail — the play, the trait grant and the
     * sacrifice alike (official FAQ: with too few Backups "you also do not put a Backup into the
     * Break Zone"). The closing sacrifice sentence is optional so the pattern still describes the
     * shape without it.
     *
     * <p>Deliberately narrow. Only one card in the corpus has this text, and the pieces it is
     * built from ("choose … and …", a control gate, "play them onto the field") each already have
     * their own parsers that would otherwise claim fragments of it.
     */
    static final Pattern CHOOSE_TWO_BZ_FWD_PLAY_IF_CONTROL = Pattern.compile(
        "(?i)Choose\\s+1\\s+(?<elem1>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Forward\\s+" +
        "of\\s+cost\\s+(?<cost1>\\d+)\\s+or\\s+less\\s+in\\s+your\\s+Break\\s+Zone\\s+and\\s+" +
        "1\\s+(?<elem2>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Forward\\s+" +
        "of\\s+cost\\s+(?<cost2>\\d+)\\s+or\\s+less\\s+in\\s+your\\s+Break\\s+Zone\\.\\s+" +
        "If\\s+you\\s+control\\s+(?<cond>[^,]+),\\s+play\\s+them\\s+onto\\s+the\\s+field\\.\\s+" +
        "They\\s+gain\\s+(?<trait>Haste|Brave|First\\s+Strike)\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn\\.?" +
        "(?:\\s+Then,\\s+put\\s+(?<sacn>\\d+)\\s+(?<sactype>Forwards?|Backups?|Monsters?|Characters?)\\s+" +
        "you\\s+control\\s+into\\s+the\\s+Break\\s+Zone\\.?)?"
    );
    /**
     * Matches "Choose up to N [type1], up to N [type2], and up to N [type3]. [followup]"
     * — up to one card of each of three different types.
     *
     * <p>The serial comma ahead of "and" is optional: 11-130L and B-039 Sephiroth print it,
     * 7-029H Kefka does not.
     *
     * <p>Each clause may carry its own "opponent controls" qualifier, and 14-102L Leviathan, Lord
     * of the Whorl repeats it on all three. Per clause rather than once for the sentence because
     * that is how the card is printed, and because the selection primitive takes the side filter
     * per call: nothing in the corpus mixes sides across the three, but reading the qualifier
     * where it appears is what lets the parser answer for the text rather than for a guess.
     * Groups {@code opp1} / {@code opp2} / {@code opp3} are non-null when their clause carries it.
     */
    static final Pattern CHOOSE_THREE_MIXED_TYPES_PATTERN = Pattern.compile(
        "(?i)Choose\\s+up\\s+to\\s+(?<count1>\\d+)\\s+(?<type1>Forwards?|Backups?|Characters?|Monsters?)" +
        "(?<opp1>\\s+(?:your\\s+)?opponent\\s+controls)?,\\s+" +
        "up\\s+to\\s+(?<count2>\\d+)\\s+(?<type2>Forwards?|Backups?|Characters?|Monsters?)" +
        "(?<opp2>\\s+(?:your\\s+)?opponent\\s+controls)?,?\\s+and\\s+" +
        "up\\s+to\\s+(?<count3>\\d+)\\s+(?<type3>Forwards?|Backups?|Characters?|Monsters?)" +
        "(?<opp3>\\s+(?:your\\s+)?opponent\\s+controls)?[.]?\\s+" +
        "(?<followup>.+)"
    );
    /**
     * Matches "Choose 1 Forward. [CardName] deals you N point(s) of damage.
     * If the cost of the Forward is equal to or less than the damage you have received, break it."
     * Groups: {@code name} — the card dealing the damage; {@code amount} — damage dealt.
     */
    static final Pattern CHOOSE_FORWARD_DEAL_SELF_DAMAGE_BREAK_IF_COST_LE_DAMAGE = Pattern.compile(
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
    static final Pattern CHOOSE_FORWARD_SHARED_POWER_LOSS_PATTERN = Pattern.compile(
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
    static final Pattern ELEM_TYPE_OR_ELEM_TYPE = Pattern.compile(
        "(?i)(Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+(Forwards?|Backups?|Monsters?|Characters?)" +
        "\\s+or\\s+(Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+\\2"
    );
    /** Matches {@code [Job (name)]} bracket notation; group 1 is the job name. */
    static final Pattern JOB_BRACKET_PATTERN = Pattern.compile(
        "(?i)\\[Job\\s+\\(([^)]+)\\)\\]"
    );
    /** Matches {@code [Card Name (name)]} bracket notation; group 1 is the card name. */
    static final Pattern CARD_NAME_BRACKET_PATTERN = Pattern.compile(
        "(?i)\\[Card\\s+Name\\s+\\(([^)]+)\\)\\]"
    );
    /** Matches one {@code Job name Forward(s)} segment in the written job-filter form; group 1 is the job name. */
    static final Pattern JOB_WRITTEN_SEGMENT = Pattern.compile(
        "(?i)Job\\s+(.+?)\\s+Forwards?"
    );
    /**
     * The card type a written job phrase ends on — the {@code Backups} of "Job Class Zero Cadet
     * Backups" (Queen 25-037H). Group 1 is the type word.
     *
     * <p>The counterpart to {@link #JOB_WRITTEN_SEGMENT}, which reads the Forward spelling only.
     * Anchored at the end so it takes the phrase's own type word rather than one belonging to a
     * later clause, and left out of {@code JOB_WRITTEN_SEGMENT} itself because that pattern's job
     * name is captured lazily against the type word that follows it.
     */
    static final Pattern JOB_PHRASE_TRAILING_TYPE = Pattern.compile(
        "(?i)\\s+(Forwards?|Backups?|Monsters?|Characters?)$"
    );

    // =========================================================================================
    // Cancelling and redirecting entries on the stack
    // =========================================================================================
    /** Matches "Cancel its effect." — used to counter a Summon on the stack. */
    static final Pattern FOLLOWUP_CANCEL_EFFECT = Pattern.compile(
        "(?i)Cancel\\s+its\\s+effect\\.?"
    );
    /** Matches Y'shtola-style "Choose 1 Summon or auto-ability. Cancel its effect." */
    static final Pattern STANDALONE_CANCEL_STACK_ENTRY_PATTERN = Pattern.compile(
        "(?i)Choose\\s+1\\s+Summon\\s+or\\s+auto-ability\\.\\s+Cancel\\s+its\\s+effect\\.?"
    );
    /**
     * Matches "Choose 1 Summon [or ability] targeting/choosing a Character/Forward/Backup you
     * control. Cancel its effect."
     *
     * <p>The zone/type noun is captured but not enforced in code — like the ability-on-stack
     * family, {@link GameContext#cancelFilteredAbilityOnStack}'s {@code requiresControllerTarget}
     * flag only restricts to entries whose stored targets include a card the canceller controls.
     * Group {@code orability} is present when non-Summon abilities are eligible too
     * (Y'shtola 10-063C); absent, only Summons are.
     */
    static final Pattern CANCEL_SUMMON_TARGETING_MY_CHARACTER = Pattern.compile(
        "(?i)Choose\\s+1\\s+Summon(?<orability>\\s+or\\s+ability)?\\s+(?:targeting|choosing)\\s+an?\\s+" +
        "(?:Character|Forward|Backup)\\s+you\\s+control\\.\\s+Cancel\\s+its\\s+effect\\.?"
    );
    /**
     * Matches the general "Choose 1 [ability type(s)] [optional target filter]. Cancel its effect."
     * family.  Handles any combination of auto-ability / action ability / special ability / ability
     * (two types joined by " or " also accepted).  An optional "that is choosing [filter] you control"
     * or "that has only one target" clause is captured but not enforced in code.
     * Group {@code types} — the raw ability-type string (e.g. "auto-ability", "special ability or auto ability").
     */
    static final Pattern CANCEL_ABILITY_ON_STACK = Pattern.compile(
        "(?i)Choose\\s+1\\s+" +
        "(?<types>(?:auto[- ]ability|action\\s+ability|special\\s+ability|ability)" +
        "(?:\\s+or\\s+(?:auto[- ]ability|action\\s+ability|special\\s+ability))?)" +
        "(?:\\s+that\\s+(?:is\\s+)?choosing\\s+(?<tgtFilter>[^.]+?))?" +
        "(?:\\s+that\\s+has\\s+only\\s+one\\s+target)?" +
        "\\.\\s*Cancel\\s+its\\s+effect[.!]?"
    );
    /**
     * Matches "choose 1 auto-ability triggered from [your opponent's] [type] [of cost N or
     * less/more]. [Self] triggers the same auto-ability." — Gogo 27-099H.
     *
     * <p>The copy of the cancel family rather than a member of it: everything else that reads the
     * Stack takes an entry away, and this one adds a second copy of it. Gogo has Back Attack and
     * can only be cast on the opponent's turn, which is what puts something on the Stack for him
     * to find.
     *
     * <p>Unlike {@link #CANCEL_ABILITY_ON_STACK}, whose "triggered from a Forward" qualifier is
     * captured and not enforced, the controller and cost here are enforced: a cancel that is
     * broader than the card says only ever declines to fire, where a copy that is broader hands
     * its controller an ability the card never offered.
     * Groups: {@code opponents}, {@code type}, {@code cost}, {@code cmp}, {@code name}.
     */
    static final Pattern COPY_CHOSEN_AUTO_ABILITY_ON_STACK = Pattern.compile(
        "(?i)choose\\s+1\\s+auto[- ]ability\\s+triggered\\s+from\\s+" +
        "(?<opponents>your\\s+opponent's\\s+)?(?:an?\\s+)?" +
        "(?<type>Forward|Backup|Monster|Character)" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+(?<cmp>less|more))?[.!]\\s*" +
        "(?<name>.+?)\\s+triggers\\s+the\\s+same\\s+auto[- ]ability[.!]?"
    );

    /**
     * The plural sibling of {@link #CANCEL_ABILITY_ON_STACK}: "Choose any number of [types].
     * Cancel their effects." — Jecht 14-108H's Jecht Block, whose list spans Summons and all three
     * kinds of ability, and Shelke 16-029R's Countertek, whose list is auto-abilities alone.
     *
     * <p>Group {@code types} is the raw list, comma- and "or"-separated. It is captured loosely and
     * validated term by term in the parser rather than enumerated here, so the pattern cannot claim
     * a "Choose any number of Forwards. …" text on the strength of its opening words alone.
     */
    static final Pattern CANCEL_ANY_NUMBER_ABILITIES_ON_STACK = Pattern.compile(
        "(?i)^Choose\\s+any\\s+number\\s+of\\s+(?<types>[^.!]+?)[.!]\\s*" +
        "Cancel\\s+their\\s+effects?[.!]?$");
    /** One entry of {@link #CANCEL_ANY_NUMBER_ABILITIES_ON_STACK}'s type list. */
    static final Pattern CANCELLABLE_ENTRY_TYPE = Pattern.compile(
        "(?i)^(?:Summons?|auto[- ]abilit(?:y|ies)|action\\s+abilit(?:y|ies)"
        + "|special\\s+abilit(?:y|ies)|abilit(?:y|ies))$");
    /**
     * Matches Faris 21-114L: "Choose 1 Summon or ability that is choosing only [Self].
     * You may choose another [Element] Forward you control to become the new target
     * (The newly chosen Forward must be a valid choice)."
     *
     * <p>Group {@code self} — the card the entry must be choosing, always the source's own name;
     * {@code newelem} — the Element the replacement Forward must be.
     *
     * <p>Distinct from {@link #REDIRECT_SINGLE_TARGET_TO_CHOSEN} (Aemo 11-109R), whose
     * eligibility is "has only one target" with no restriction on what that target is, and whose
     * replacement is any Character rather than one of your own Forwards of a named Element.
     */
    static final Pattern REDIRECT_CHOOSING_ONLY_SELF = Pattern.compile(
        "(?i)Choose\\s+1\\s+Summon(?:\\s+or\\s+ability)?\\s+that\\s+is\\s+choosing\\s+only\\s+" +
        "(?<self>[^.]+?)\\.\\s*" +
        "You\\s+may\\s+choose\\s+another\\s+" +
        "(?<newelem>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Forward\\s+you\\s+control" +
        "\\s+to\\s+become\\s+the\\s+new\\s+target(?:\\s*\\([^)]*\\))?[.!]?"
    );
    /**
     * Matches Edge 15-045H: "Choose 1 Summon or ability that is choosing only 1 [Element] Forward
     * you control. The Summon or ability is now choosing [Self] instead, if possible."
     *
     * <p>Group {@code elem} — the Element the currently chosen Forward must be; {@code newtarget}
     * — the card the entry is redirected onto, always the source's own name.
     *
     * <p>Kept separate from {@link #REDIRECT_ON_FIELD_TO_SELF} (Calbrena 20-024H) even though the
     * second sentence is identical: the eligibility clauses select genuinely different pools, and
     * folding both into one alternation would put two optional Element captures in a pattern
     * where exactly one must be present.
     */
    static final Pattern REDIRECT_MY_FORWARD_TO_SELF = Pattern.compile(
        "(?i)Choose\\s+1\\s+Summon(?:\\s+or\\s+ability)?\\s+that\\s+is\\s+choosing\\s+only\\s+1\\s+" +
        "(?<elem>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Forward\\s+you\\s+control\\.\\s*" +
        "The\\s+Summon\\s+or\\s+ability\\s+is\\s+now\\s+choosing\\s+(?<newtarget>[^.,]+?)" +
        "\\s+instead(?:,\\s*if\\s+possible)?[.!]?"
    );
    /**
     * Matches Calbrena 20-024H's enters-field trigger: "choose 1 ability that is choosing only 1
     * Character either player controls. The ability is now choosing [Self] instead, if possible."
     *
     * <p>Group {@code newtarget} — the card the entry is redirected onto, always the source.
     * Unanchored at the front because an auto-ability's effect text arrives with its trigger
     * clause already stripped, leaving a lower-case "choose".
     */
    static final Pattern REDIRECT_ON_FIELD_TO_SELF = Pattern.compile(
        "(?i)choose\\s+1\\s+(?:auto[- ]|action\\s+|special\\s+)?ability\\s+that\\s+is\\s+choosing\\s+" +
        "only\\s+1\\s+Character\\s+either\\s+player\\s+controls\\.\\s*" +
        "The\\s+ability\\s+is\\s+now\\s+choosing\\s+(?<newtarget>[^.,]+?)" +
        "\\s+instead(?:,\\s*if\\s+possible)?[.!]?"
    );
    /**
     * Matches the two "you pick the replacement freely" members of the redirect family:
     * Aemo 11-109R ("Choose 1 auto-ability or action ability that has only one target. You may
     * choose another target…") and Wicked Mask 20-038H ("choose 1 Summon that is choosing only 1
     * Character in any zone. You may choose another Character…").
     *
     * <p>Group {@code types} — the entry type(s) the effect may touch, which is the only thing
     * separating the two: Aemo is abilities-only, Wicked Mask is Summons-only. The differing
     * eligibility clauses ("has only one target" vs "is choosing only 1 Character in any zone")
     * mean the same thing once targets are stored per entry, so they share one alternation.
     */
    static final Pattern REDIRECT_SINGLE_TARGET_TO_CHOSEN = Pattern.compile(
        "(?i)choose\\s+1\\s+" +
        "(?<types>(?:Summon|auto[- ]ability|action\\s+ability|special\\s+ability|ability)" +
        "(?:\\s+or\\s+(?:auto[- ]ability|action\\s+ability|special\\s+ability|ability))?)" +
        "\\s+that\\s+(?:has\\s+only\\s+one\\s+target" +
            "|is\\s+choosing\\s+only\\s+1\\s+Character\\s+in\\s+any\\s+zone)\\.\\s*" +
        "You\\s+may\\s+choose\\s+another\\s+(?:target|Character)\\s+to\\s+become\\s+the\\s+new\\s+target" +
        "(?:\\s*\\([^)]*\\))?[.!]?"
    );
    /**
     * Matches the "Choose 1 [Summon/ability type(s)] [optional 'opponent's']. If your opponent
     * doesn't pay 《N》, cancel its effect." family — a conditional cancel gated on an unpaid CP cost
     * (Dull's active/action-ability cost form). Group {@code opponents} — present when the target
     * must belong to the opponent (e.g. "opponent's auto-ability"). Group {@code types} — same
     * vocabulary as {@link #CANCEL_ABILITY_ON_STACK} plus {@code Summon}. Group {@code cost} — the
     * CP amount that must be paid in full to prevent the cancellation.
     */
    static final Pattern CANCEL_STACK_ENTRY_UNLESS_PAY = Pattern.compile(
        "(?i)Choose\\s+(?:1\\s+|an?\\s+)?" +
        "(?<opponents>opponent's\\s+)?" +
        "(?<types>(?:Summon|auto[- ]ability|action\\s+ability|special\\s+ability|ability)" +
        "(?:\\s+or\\s+(?:Summon|auto[- ]ability|action\\s+ability|special\\s+ability))?)" +
        "(?:\\s+that\\s+(?:is\\s+)?choosing\\s+(?<tgtFilter>[^.]+?))?" +
        "\\.\\s*If\\s+your\\s+opponent\\s+doesn'?t\\s+pay\\s*《\\s*(?<cost>\\d+)\\s*》,?\\s*" +
        "cancel\\s+its\\s+effect[.!]?"
    );
    /**
     * Matches the standalone "If your opponent doesn't pay 《N》[ or 《C》…], cancel its/their effect(s)."
     * clause used as the body of a "chosen by opponent's Summons or abilities" auto-ability — the
     * target is implicit (whatever triggered the reactive ability), so there is no leading
     * "Choose 1..." clause. Group {@code cost} — the CP amount that must be paid in full. Group
     * {@code crystal} — the optional Crystal alternative (one 《C》 per Crystal, e.g. Zeromus's
     * "pay 《4》 or 《C》"); when present the opponent may instead pay that many Crystals.
     */
    static final Pattern CANCEL_CHOSEN_TARGET_UNLESS_PAY = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+doesn'?t\\s+pay\\s*《\\s*(?<cost>\\d+)\\s*》" +
        "(?:\\s+or\\s+(?<crystal>(?:《\\s*C\\s*》)+))?,?\\s*" +
        "cancel\\s+(?:its|their)\\s+effects?[.!]?$"
    );
    /**
     * Matches the reversed-clause-order variant of {@link #CANCEL_CHOSEN_TARGET_UNLESS_PAY}: "its/their
     * effect(s) is/are cancelled if your opponent doesn't pay 《N》." (e.g. White Tiger l'Cie Qun'mi's
     * "First Strike[[br]] When 1 or more Forwards you control are chosen by your opponent's Summon,
     * its effect is cancelled if your opponent doesn't pay 《3》.").
     */
    static final Pattern CANCEL_CHOSEN_TARGET_UNLESS_PAY_REVERSED = Pattern.compile(
        "(?i)^(?:its|their)\\s+effects?\\s+(?:is|are)\\s+cancelled\\s+if\\s+your\\s+opponent\\s+" +
        "doesn'?t\\s+pay\\s*《\\s*(?<cost>\\d+)\\s*》[.!]?$"
    );
    /**
     * Discard-cost sibling of {@link #CANCEL_CHOSEN_TARGET_UNLESS_PAY}: "If your opponent doesn't
     * discard N card(s), cancel its/their effect(s)." (e.g. Kuja, Charlotte). Same implicit-target
     * cancel mechanic, but the opponent must discard from hand instead of paying CP to prevent it.
     * Group {@code count} — the number of cards that must be discarded in full.
     */
    static final Pattern CANCEL_CHOSEN_TARGET_UNLESS_DISCARD = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+doesn'?t\\s+discard\\s+(?<count>\\d+)\\s+cards?,?\\s*" +
        "cancel\\s+(?:its|their)\\s+effects?[.!]?$"
    );
    /**
     * Matches a bare "Cancel its/their effect(s)." — the consequent of a reactive "chosen by opponent's
     * Summons or abilities" auto-ability whose cost was already paid upstream (e.g. Phantasmal Girl's
     * "you may pay 《2》. When you do so, cancel their effects.", or Regis/Tama/Yuna's "…put/discard…,
     * cancel its effect."). Since the paying/cost step is handled before this sub-effect runs, this
     * unconditionally cancels the in-progress selection. Anchored to the whole string so it never
     * matches the "Choose 1 Summon…" stack-cancel forms.
     */
    static final Pattern CANCEL_CHOSEN_TARGET_BARE = Pattern.compile(
        "(?i)^Cancel\\s+(?:its|their)\\s+effects?[.!]?$"
    );
    /**
     * Matches a bare "Cancel the Summon's effect." — the sub-effect half of Clione 4-125C's "put
     * Clione into the Break Zone. If you do so, cancel the Summon's effect."
     *
     * <p>Named rather than pronominal, which is what separates it from
     * {@link #CANCEL_CHOSEN_TARGET_BARE}: that one answers a selection in progress, while this names
     * the Summon whose casting triggered the ability. The Summon is still on the Stack, one entry
     * below the trigger, so nothing is chosen — see {@link GameContext#cancelTriggeringSummon}.
     *
     * <p>{@code AutoAbilityTriggers.executePutSelfIntoBzIfDoSoAutoAbility} splits the sentence and
     * parses this half on its own, so the whole two-sentence text never needs a parser of its own.
     */
    static final Pattern CANCEL_TRIGGERING_SUMMON = Pattern.compile(
        "(?i)^Cancel\\s+the\\s+Summon'?s\\s+effect[.!]?$"
    );
    /**
     * Standalone "If your opponent doesn't pay 《N》, [target action]." — the body of a reactive
     * auto-ability (e.g. Remedi: "…if your opponent doesn't pay 《2》, break it.") whose target is
     * supplied via {@link GameContext#consumePreloadedTargets()} (the entering card). The opponent
     * may pay {@code cost} in full to prevent it; otherwise the action ("break it", "dull it",
     * "Freeze it", …) runs against the preloaded target(s) — parsed by {@link #parseTargetAction}.
     * Groups: {@code cost} — CP amount; {@code effect} — the target action text.
     */
    static final Pattern IF_OPP_NOT_PAY_ACTION = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+doesn'?t\\s+pay\\s+《\\s*(?<cost>\\d+)\\s*》,?\\s+(?<effect>.+)$",
        Pattern.DOTALL
    );
    /**
     * Banon: "Reveal the top card of your deck. If it is a [Type], cancel all effects choosing [Name]."
     * Reveals (peeks) the top card of the controller's deck; if it is of the captured {@code type},
     * the in-progress selection is cancelled. Group {@code type} — Forward / Backup / Monster / Summon.
     */
    /**
     * "Reveal the top card of your deck. If it is a [Type], add it to your hand. If it is not a
     * [Type], put it at the top or bottom of your deck." — 16-115H Sarah (MOBIUS)'s crystal payoff.
     *
     * <p>Both halves must name the same type; the back-reference is what enforces it, so a text
     * that keeps one type and misses on another declines rather than resolving as this one.
     * Group {@code type} — Forward / Backup / Monster / Summon.
     */
    static final Pattern REVEAL_TOP_TO_HAND_IF_TYPE_ELSE_TOP_OR_BOTTOM = Pattern.compile(
        "(?i)^Reveal\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "If\\s+it\\s+is\\s+an?\\s+(?<type>Forward|Backup|Monster|Summon),\\s+" +
        "add\\s+it\\s+to\\s+your\\s+hand[.!]?\\s+" +
        "If\\s+it\\s+is\\s+not\\s+an?\\s+\\k<type>,\\s+" +
        "put\\s+it\\s+at\\s+the\\s+top\\s+or\\s+bottom\\s+of\\s+your\\s+deck[.!]?\\s*$");
    static final Pattern CANCEL_CHOSEN_REVEAL_TOP_IF_TYPE = Pattern.compile(
        "(?i)^Reveal\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "If\\s+it\\s+is\\s+an?\\s+(?<type>Forward|Backup|Monster|Summon)s?,\\s*" +
        "cancel\\s+all\\s+effects?\\s+choosing\\s+.+?[.!]?$"
    );
    /**
     * Siren (V): "Put the top card of your deck into the Break Zone. If the card put into the Break
     * Zone is not a [Type], cancel its/their effect(s)." Mills the top card of the controller's deck;
     * if that card is NOT of the captured {@code type}, the in-progress selection is cancelled.
     * Group {@code type} — Forward / Backup / Monster / Summon.
     */
    static final Pattern CANCEL_CHOSEN_MILL_TOP_IF_NOT_TYPE = Pattern.compile(
        "(?i)^Put\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck\\s+into\\s+the\\s+Break\\s+Zone[.!]?\\s+" +
        "If\\s+the\\s+card\\s+put\\s+into\\s+the\\s+Break\\s+Zone\\s+is\\s+not\\s+an?\\s+" +
        "(?<type>Forward|Backup|Monster|Summon)s?,\\s*cancel\\s+(?:its|their)\\s+effects?[.!]?$"
    );
    /**
     * Colkhab 18-041C: "Each player puts the top card of their deck into the Break Zone. If both
     * cards are of the same card type, cancel its/their effect(s)." The two-sided sibling of
     * {@link #CANCEL_CHOSEN_MILL_TOP_IF_NOT_TYPE} — both players mill, and the comparison is between
     * the two milled cards rather than against a printed type, so the pattern captures no type at all.
     */
    static final Pattern CANCEL_CHOSEN_MILL_BOTH_IF_SAME_TYPE = Pattern.compile(
        "(?i)^Each\\s+player\\s+puts\\s+the\\s+top\\s+card\\s+of\\s+their\\s+decks?\\s+into\\s+the\\s+" +
        "Break\\s+Zone[.!]?\\s+If\\s+both\\s+cards\\s+are\\s+of\\s+the\\s+same\\s+card\\s+type,\\s*" +
        "cancel\\s+(?:its|their)\\s+effects?[.!]?$"
    );
    /**
     * Matches "Choose 1 auto-ability. Cancel its effect. If the cancelled auto-ability triggered
     * from a Forward, deal that Forward N damage."
     * Group {@code amount} — damage to deal if the source was a Forward.
     */
    static final Pattern CANCEL_AUTO_ABILITY_DAMAGE_IF_FORWARD = Pattern.compile(
        "(?i)^Choose\\s+1\\s+auto-ability\\.\\s+Cancel\\s+its\\s+effect\\.\\s+" +
        "If\\s+the\\s+cancelled\\s+auto-ability\\s+triggered\\s+from\\s+a\\s+Forward,\\s+" +
        "deal\\s+that\\s+Forward\\s+(?<amount>\\d+)\\s+damage\\.?$"
    );

    // =========================================================================================
    // Damage followups
    // =========================================================================================
    /**
     * Matches "Deal it/them [and CardName] N damage".
     * <ul>
     *   <li>{@code also} — optional named Forward that also receives the damage</li>
     *   <li>{@code amount} — fixed damage value</li>
     * </ul>
     */
    /**
     * Matches "Deal it N damage, and deal M damage to all the other Forwards opponent controls." —
     * 4-145H Cloud's Blade Beam and 3-022H Machina, a single blow plus a splash over the rest of
     * the opponent's row.
     *
     * <p>Must be checked before {@link #FOLLOWUP_DAMAGE}, which is read with find() and matches the
     * first clause on its own: that is what claimed both cards, dealing the 8000 and silently
     * dropping the 4000. The two clauses are joined by a comma rather than a full stop, so the
     * choose chain's sentence split never separated them either and there was no secondary for the
     * splash to be parsed as.
     *
     * <p>Groups: {@code amount} — dealt to the chosen Forward; {@code splash} — dealt to each of
     * the others.
     */
    static final Pattern FOLLOWUP_DAMAGE_AND_SPLASH_OTHER_OPP_FORWARDS = Pattern.compile(
        "(?i)^Deal\\s+(?:it|them)\\s+(?<amount>\\d+)\\s+damage,?\\s+and\\s+deal\\s+(?<splash>\\d+)\\s+" +
        "damage\\s+to\\s+all\\s+the\\s+other\\s+Forwards\\s+(?:your\\s+)?opponent\\s+controls[.!]?$"
    );
    static final Pattern FOLLOWUP_DAMAGE = Pattern.compile(
        "(?i)deal\\s+(?:it|them)(?:\\s+and\\s+(?<also>.+?))?\\s+(?<amount>\\d+)\\s+damage"
    );
    /**
     * Matches Titan's unique damage clause:
     * "Deal it damage equal to the power of the Forward removed by the extra cost."
     * At runtime the damage value is read from {@link GameContext#extraCostRemovedCardPower()}.
     */
    static final Pattern FOLLOWUP_DAMAGE_EXTRA_COST_POWER = Pattern.compile(
        "(?i)deal\\s+it\\s+damage\\s+equal\\s+to\\s+the\\s+power\\s+of\\s+the\\s+Forward\\s+removed\\s+by\\s+the\\s+extra\\s+cost\\.?"
    );
    /**
     * "Deal it damage equal to the power of the Forward you revealed." — Rinoa 18-097R's Angelo
     * Cannon, whose cost reveals that Forward rather than spending it.
     *
     * <p>The twin of {@link #FOLLOWUP_DAMAGE_EXTRA_COST_POWER} in everything but where the figure
     * comes from: at runtime it is read from {@link GameContext#revealedForwardPower()} rather than
     * {@code extraCostRemovedCardPower()}. The two cannot be confused — each names its own cost.
     */
    static final Pattern FOLLOWUP_DAMAGE_REVEALED_FORWARD_POWER = Pattern.compile(
        "(?i)deal\\s+it\\s+damage\\s+equal\\s+to\\s+the\\s+power\\s+of\\s+the\\s+Forward\\s+you\\s+revealed\\.?"
    );
    /**
     * Matches Fenrir's conditional break-and-draw:
     * "If its cost is equal to the cost of the card discarded by the extra cost, break it and draw N card(s)."
     * Group: {@code draw} — number of cards to draw.
     */
    static final Pattern FOLLOWUP_IF_COST_EQUALS_DISCARD_BREAK_DRAW = Pattern.compile(
        "(?i)if\\s+its\\s+cost\\s+is\\s+equal\\s+to\\s+the\\s+cost\\s+of\\s+the\\s+card\\s+discarded\\s+by\\s+the\\s+extra\\s+cost,?\\s+break\\s+it\\s+and\\s+draw\\s+(?<draw>\\d+)\\s+cards?\\.?"
    );
    /**
     * Matches "Deal it/them N damage and M point(s) of damage to that Forward's controller."
     * Groups: {@code amount} — damage to the chosen Forward; {@code controllerdmg} — card damage dealt to its controller.
     */
    static final Pattern FOLLOWUP_DAMAGE_AND_CONTROLLER_DAMAGE = Pattern.compile(
        "(?i)deal\\s+(?:it|them)\\s+(?<amount>\\d+)\\s+damage\\s+and\\s+(?<controllerdmg>\\d+)\\s+points?\\s+of\\s+damage\\s+to\\s+that\\s+(?:Forward|Character|Monster|Backup)'?s?\\s+controller\\.?"
    );
    /**
     * Matches the "That Forward's controller discards N card(s) from (their|his/her) hand" secondary
     * clause that follows a Choose+followup primary (Physalis, Sephiroth, Hades, …). The discarder
     * is resolved at runtime from {@link GameContext#lastChosenTargets()}.
     * Group {@code count} — number of cards to discard.
     */
    static final Pattern FOLLOWUP_TARGET_CONTROLLER_DISCARDS = Pattern.compile(
        "(?i)^That\\s+Forward(?:'s|s)?\\s+controller\\s+discards?\\s+(?<count>\\d+)\\s+cards?\\s+" +
        "from\\s+(?:their|his/her|his|her)\\s+hand\\.?$"
    );
    /**
     * Matches "You may discard 1 [Card Name X | card | &lt;type&gt;] from your hand. If you do so,
     * deal it N damage. [If not, deal it M damage.]"
     * Groups: {@code cardname} or {@code cardtype} (exactly one is non-null), {@code amount}, and
     * {@code elseamount} for the declined branch.
     *
     * <p>The "If not" sentence is optional because both printings exist; 5-003C Ifrit has it, and
     * without it here the sentence fell past the whole followup chain and the card did nothing at
     * all — not even the smaller damage.
     *
     * <p>The unnamed alternative is 1-190S Bahamut Fury's "1 card", which is any card at all.
     * {@code cardtype} is spelled in the {@code CardFilters.matchesDiscardType} vocabulary so the
     * handler can pass it straight through.
     */
    /**
     * Matches "You may discard 1 [type] [from your hand]." standing alone as a choose followup —
     * 7-040C Yunalesca, whose payoff is spelled by the "If you do so, …" sentence that follows
     * rather than by this clause.
     *
     * <p>Its sibling {@link #FOLLOWUP_MAY_DISCARD_NAMED_DEAL_DAMAGE} carries its own payoff and so
     * cannot match this text; this one is anchored at both ends for the same reason, so it cannot
     * take a prefix of that one.
     */
    static final Pattern FOLLOWUP_MAY_DISCARD_TYPE_BARE = Pattern.compile(
        "(?i)^you\\s+may\\s+discard\\s+1\\s+" +
        "(?<cardtype>card|Forwards?|Backups?|Monsters?|Characters?|Summons?)" +
        "(?:\\s+from\\s+your\\s+hand)?[.!]?$"
    );
    static final Pattern FOLLOWUP_MAY_DISCARD_NAMED_DEAL_DAMAGE = Pattern.compile(
        "(?i)^you\\s+may\\s+discard\\s+1\\s+" +
        "(?:Card\\s+Name\\s+(?<cardname>.+?)" +
        "|(?<cardtype>card|Forwards?|Backups?|Monsters?|Characters?|Summons?))" +
        "\\s+from\\s+your\\s+hand\\.\\s+" +
        "If\\s+you\\s+do\\s+so,\\s+deal\\s+it\\s+(?<amount>\\d+)\\s+damage\\.?" +
        "(?:\\s+If\\s+not,\\s+deal\\s+it\\s+(?<elseamount>\\d+)\\s+damage\\.?)?$"
    );
    /**
     * Matches "If its/their power has become 0 or less by the previous effect, draw N card(s)." —
     * the payoff clause on 10-110C Cúchulainn, whose power reduction scales with the caster's hand.
     *
     * <p>Read together with the reduction it refers to rather than as a standalone secondary: by
     * the time a detached clause could look, {@code reduceTarget} has already run the 0-power rule
     * process and the Forward is in the Break Zone, so there is no power left to read.
     */
    static final Pattern FOLLOWUP_IF_POWER_BECAME_ZERO_DRAW = Pattern.compile(
        "(?i)^If\\s+(?:its|their)\\s+power\\s+(?:has\\s+)?becomes?\\s+0\\s+or\\s+less\\s+" +
        "by\\s+the\\s+previous\\s+effect,\\s+draw\\s+(?<draw>\\d+)\\s+cards?[.!]?$"
    );
    /**
     * Matches "You may search for 1 [Element] [Type] and remove it from the game. If you do so,
     * &lt;then&gt;. If not, &lt;else&gt;." where each branch acts on the Forward(s) already chosen —
     * 29-117H Ark (break, else 8000 damage) and 29-116H Madeen (remove from the game, else break).
     *
     * <p>Anchored whole, and matched against the entire followup rather than the primary half:
     * the three sentences are one effect, and the "." split hands the first of them to the generic
     * chain, where "remove it from the game" reads as removing the <em>chosen</em> Forwards. That
     * is what Ark did — it removed the opponent's Forwards from the game outright, skipping both
     * the search it is supposed to cost and the smaller "if not" outcome.
     *
     * <p>The branches are captured as text and read by {@link #CHOSEN_TARGETS_BREAK} and its
     * siblings rather than enumerated here, so an unrecognised verb fails the whole match and the
     * text falls through instead of resolving as half the card.
     */
    static final Pattern FOLLOWUP_MAY_SEARCH_RFG_THEN_ELSE = Pattern.compile(
        "(?i)^You\\s+may\\s+search\\s+for\\s+1\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+and\\s+remove\\s+it\\s+from\\s+the\\s+game\\.\\s+" +
        "If\\s+you\\s+do\\s+so,\\s+(?<thenact>[^.]+)\\.\\s+" +
        "If\\s+not,\\s+(?<elseact>[^.]+)\\.?$"
    );
    /** "break the chosen Forward(s)" — one branch verb of {@link #FOLLOWUP_MAY_SEARCH_RFG_THEN_ELSE}. */
    static final Pattern CHOSEN_TARGETS_BREAK = Pattern.compile(
        "(?i)^break\\s+the\\s+chosen\\s+(?:Forwards?|Characters?)$"
    );
    /** "remove the chosen Forward(s) from the game" — 29-116H Madeen's "if you do so" branch. */
    static final Pattern CHOSEN_TARGETS_REMOVE_FROM_GAME = Pattern.compile(
        "(?i)^remove\\s+the\\s+chosen\\s+(?:Forwards?|Characters?)\\s+from\\s+the\\s+game$"
    );
    /** "deal N damage to the chosen Forward(s)" — 29-117H Ark's "if not" branch. */
    static final Pattern CHOSEN_TARGETS_DEAL_DAMAGE = Pattern.compile(
        "(?i)^deal\\s+(?<amount>\\d+)\\s+damage\\s+to\\s+the\\s+chosen\\s+(?:Forwards?|Characters?)$"
    );
    /**
     * Matches "Deal it/them N damage. If &lt;condition&gt;, deal it/them M damage instead."
     * Groups: {@code base}, {@code cond}, {@code alt}.
     */
    static final Pattern FOLLOWUP_DAMAGE_INSTEAD = Pattern.compile(
        "(?i)deal\\s+(?:it|them)\\s+(?<base>\\d+)\\s+damage\\.\\s+If\\s+(?<cond>.+?),\\s+deal\\s+(?:it|them)\\s+(?<alt>\\d+)\\s+damage\\s+instead\\.?"
    );
    /**
     * Matches any "P. If [name] results from an EX Burst, A instead." followup.
     * Groups: {@code primary} (text before the period), {@code alt} (alternate action text).
     * The card name before "results from an EX Burst" is intentionally not captured.
     */
    static final Pattern FOLLOWUP_INSTEAD_EXBURST = Pattern.compile(
        "(?i)(?<primary>.+?)\\.\\s+If\\s+\\S+(?:\\s+\\S+)*?\\s+results\\s+from\\s+an\\s+EX\\s+Burst,\\s+(?<alt>.+?)\\s+instead[.!]?"
    );
    /**
     * The "If [name] results from an EX Burst, &lt;alt&gt; instead." sentence on its own — located
     * rather than matched whole, so the text either side of it can be kept.
     *
     * <p>Read by {@link ActionResolver#resolveExBurstInstead}, which is about what the player is
     * shown rather than about what resolves: only one of the two readings ever happens, and naming
     * both in the log and in the stack window described a card doing something it was not about to
     * do. {@link #FOLLOWUP_INSTEAD_EXBURST} stays the parser's route, where the split into
     * {@code primary} and {@code alt} is what the two branches are built from.
     * Group: {@code alt}.
     */
    static final Pattern EX_BURST_INSTEAD_SENTENCE = Pattern.compile(
        "(?i)If\\s+\\S+(?:\\s+\\S+)*?\\s+results\\s+from\\s+an\\s+EX\\s+Burst,\\s+(?<alt>.+?)\\s+instead[.!]?"
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
     *
     * <p>"deal each of them damage …" is the same effect distributed over a multi-target choose
     * (11-037L Barthandelus, the only card in the corpus wording it that way); every branch below
     * already applies its amount per selected target, and {@code itspower} recomputes the power
     * for each, so the plural reading needs nothing beyond the subject alternation.
     */
    static final Pattern FOLLOWUP_DAMAGE_EXPR = Pattern.compile(
        "(?i)deal\\s+(?:it|them|each\\s+of\\s+them)\\s+damage\\s+equal\\s+to\\s+" +
        "(?:" +
            "(?<highest>the\\s+highest(?:\\s+power)?\\s+Forward(?:\\s+you\\s+control)?(?:'s\\s+power)?)" +
            "|half\\s+of\\s+(?<halfcard>.+?)'s\\s+power(?:\\s*\\(\\s*round\\s+(?<halfrounding>up|down)[^)]*\\))?" +
            "|(?<halfitspower>half\\s+of\\s+(?:its|their)\\s+power)(?:\\s*\\(\\s*round\\s+(?<halfitsrounding>up|down)[^)]*\\))?" +
            "|(?<itspower>(?:its|their)\\s+power)(?:\\s+minus\\s+(?<minus>\\d+))?" +
            "|(?<dullforward>the\\s+power\\s+of\\s+the\\s+dull(?:ed)?\\s+Forward)" +
            "|(?<discardedfwd>the\\s+discarded\\s+Forward(?:'s\\s+power)?)" +
            "|(?<bzcostfwd>the\\s+power\\s+of\\s+the\\s+Forward\\s+put\\s+in(?:to)?\\s+the\\s+Break\\s+Zone)" +
            "|(?<card>.+?)'s?\\s+power" +
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
    static final Pattern FOLLOWUP_MUTUAL_POWER_DAMAGE = Pattern.compile(
        "(?i)(?<srcname>.+?)\\s+and\\s+the\\s+chosen\\s+Forward\\s+deal\\s+damage\\s+equal\\s+to\\s+their\\s+respective\\s+power\\s+to\\s+the\\s+other[.!]?"
    );
    /** Matches "Each Forward deals damage equal to its power to the other." (used in choose-one-each contexts). */
    static final Pattern FOLLOWUP_EACH_FORWARD_MUTUAL_POWER_DAMAGE = Pattern.compile(
        "(?i)Each\\s+Forward\\s+deals\\s+damage\\s+equal\\s+to\\s+its\\s+power\\s+to\\s+the\\s+other[.!]?"
    );
    /**
     * Matches "Deal it/them [base] damage [and [per] more damage] for each/every N [source]".
     * <ul>
     *   <li>{@code base}       — base damage per unit (or fixed base when {@code per} is set)</li>
     *   <li>{@code per}        — additional damage per each unit (the "and N more" form)</li>
     *   <li>{@code group}      — group size from the "for every N" form; absent for "for each",
     *       which is group size 1. The source count is divided by it, rounding down.</li>
     *   <li>{@code selfdmg}    — source is P1's damage-zone count</li>
     *   <li>{@code jobbname}   — bracket job: "[Job (name)] you control"</li>
     *   <li>{@code jobuname}/{@code jobuelement}/{@code jobutype} — the union source
     *       "Job Name or/and-or [Element] Type you control"; a card satisfying both halves
     *       counts once</li>
     *   <li>{@code jobcname}/{@code jobccard} — the union source "Job Name or/and-or Card Name
     *       Name you control"; a card satisfying both halves counts once</li>
     *   <li>{@code jobzname}/{@code jobzcard} — the same union over P1's Break Zone</li>
     *   <li>{@code jobxname}/{@code jobxexcl} — "Job Name other than CardName you control";
     *       every card carrying the name is excluded, not just the ability's source</li>
     *   <li>{@code jobrname}   — "Job Name in your Break Zone and/or Job Name you own removed
     *       from the game", counted across both zones. A backreference ties the two halves to
     *       the same job, since the count they feed takes a single job filter.</li>
     *   <li>{@code jobwname}   — written job: "Job Name you control"</li>
     *   <li>{@code chartype}   — type filter: "Forwards/Characters/etc. you control"</li>
     *   <li>{@code costfilter} — optional exact cost: "of cost N" appended to chartype</li>
     *   <li>{@code bzname}     — card name in P1's Break Zone</li>
     *   <li>{@code bztype}     — card type in P1's Break Zone: "Forwards in your Break Zone";
     *       the value "card" means the whole zone regardless of type</li>
     *   <li>{@code opphand}    — source is the opponent's hand size</li>
     *   <li>{@code xpaid}      — source is the X CP value paid for this ability</li>
     * </ul>
     */
    static final Pattern FOLLOWUP_DAMAGE_FOR_EACH = Pattern.compile(
        "(?i)deal\\s+(?:it|them)\\s+(?<base>\\d+)\\s+damage" +
        "(?:\\s+(?<op>and|minus)\\s+(?<per>\\d+)\\s+(?:more\\s+)?damage)?" +
        "\\s+for\\s+(?:each|every\\s+(?<group>\\d+))\\s+" +
        "(?:" +
            "(?<selfdmg>point\\s+of\\s+damage\\s+you\\s+have\\s+received)" +
            "|\\[Job\\s+\\((?<jobbname>[^)]+)\\)\\]\\s+you\\s+control" +
            // The three union branches must all precede the plain Job branch: its reluctant name
            // would otherwise swallow the whole "Warrior of Light or Fire Character" — or
            // "Dragoon and/or Card Name Dragoon" — as one job name and count nothing.
            // Their own order is free; the second half of each is anchored by a distinct literal
            // (an Element word, "Card Name ... you control", "Card Name ... in your Break Zone").
            "|Job\\s+(?<jobuname>.+?)\\s+(?:and/or|or)\\s+(?<jobuelement>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+(?<jobutype>Forwards?|Characters?|Backups?|Monsters?)\\s+you\\s+control" +
            "|Job\\s+(?<jobcname>.+?)\\s+(?:and/or|or)\\s+Card\\s+Name\\s+(?<jobccard>.+?)\\s+you\\s+control" +
            "|Job\\s+(?<jobzname>.+?)\\s+(?:and/or|or)\\s+Card\\s+Name\\s+(?<jobzcard>.+?)\\s+in\\s+your\\s+Break\\s+Zone" +
            // Same reason these two sit ahead of the plain Job branch: it would take
            // "Sky Pirate other than Fran" as one job name. Neither can be confused with a union
            // branch — "other than" holds no free-standing "or", and the cross-zone form names a
            // Job on both sides of its "and/or" where the unions require an Element or "Card Name".
            "|Job\\s+(?<jobxname>.+?)\\s+other\\s+than\\s+(?<jobxexcl>.+?)\\s+you\\s+control" +
            "|Job\\s+(?<jobrname>.+?)\\s+in\\s+your\\s+Break\\s+Zone\\s+and/or\\s+Job\\s+\\k<jobrname>\\s+you\\s+own\\s+removed\\s+from\\s+the\\s+game" +
            "|Job\\s+(?<jobwname>.+?)(?:\\s+(?<jobwtype>Forwards?|Backups?|Monsters?))?\\s+you\\s+control" +
            "|(?:Category\\s+(?<category>\\S+)\\s+)?(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?(?<chartype>Forwards?|Characters?|Backups?|Monsters?)(?:\\s+of\\s+cost\\s+(?<costfilter>\\d+))?\\s+you\\s+control" +
            "|Card\\s+Name\\s+(?<bzname>\\S+(?:\\s+\\([^)]+\\))?)\\s+in\\s+your\\s+Break\\s+Zone" +
            // Must follow the Card Name branch: a bare type noun would not match "Card Name X",
            // but keeping the specific zone phrasing first mirrors how the field branches are ordered.
            // "card" is the untyped whole-zone count (Atomos, Cyan), not another card type.
            "|(?<bztype>Forwards?|Characters?|Backups?|Monsters?|Summons?|Cards?)\\s+in\\s+your\\s+Break\\s+Zone" +
            "|(?<opphand>card\\s+in\\s+your\\s+opponent'?s?\\s+hand)" +
            "|(?<xpaid>CP\\s+paid\\s+as\\s+X)" +
            "|(?<crystal>《C》)\\s+you\\s+have" +
            "|(?<cpDiffElem>CP\\s+of\\s+a\\s+different\\s+Element\\s+you\\s+paid\\s+to\\s+cast\\s+\\S+)" +
        ")" +
        "[.!]?"
    );

    // =========================================================================================
    // Dull, freeze and activate followups
    // =========================================================================================
    /** Matches "Activate it" or "Activate them". */
    static final Pattern FOLLOWUP_ACTIVATE = Pattern.compile(
        "(?i)Activate\\s+(?:it|them)\\.?"
    );
    /** Matches "Dull it or activate it." / "Dull them or activate them." — toggle dull/active. */
    static final Pattern FOLLOWUP_DULL_OR_ACTIVATE = Pattern.compile(
        "(?i)Dulls?\\s+(?:it|them)\\s+or\\s+activates?\\s+(?:it|them)[.!]?"
    );
    /**
     * Matches "Dull it or freeze it." / "Dull them or freeze them." — dull if active,
     * freeze if already dulled. (Order-of-words variants like "dull or freeze it" are not used in card text.)
     */
    static final Pattern FOLLOWUP_DULL_OR_FREEZE = Pattern.compile(
        "(?i)Dulls?\\s+(?:it|them)\\s+or\\s+freezes?\\s+(?:it|them)[.!]?"
    );
    /** Matches "Dull or Freeze it/them" — compact imperative form used in former/latter effects. */
    static final Pattern FOLLOWUP_DULL_OR_FREEZE_COMPACT = Pattern.compile(
        "(?i)Dull\\s+or\\s+Freeze\\s+(?:it|them)[.!]?"
    );
    /** Matches "dull it/them" or "dulls it/them" (third-person form used in opponent-selects effects). */
    static final Pattern FOLLOWUP_DULL = Pattern.compile(
        "(?i)dulls?\\s+(?:it|them)"
    );
    /** Matches "freeze it" or "freeze them". */
    static final Pattern FOLLOWUP_FREEZE = Pattern.compile(
        "(?i)freeze\\s+(?:it|them)"
    );
    /**
     * Matches "dull it/them and freeze it/them" or compact "dull and freeze it/them"
     * (former/latter effects use a shared pronoun at the end).
     */
    static final Pattern FOLLOWUP_DULL_AND_FREEZE = Pattern.compile(
        "(?i)(?:dull\\s+(?:it|them)\\s+and\\s+freeze|dull\\s+and\\s+freeze)\\s+(?:it|them)"
    );

    // =========================================================================================
    // Delayed and compound followups
    // =========================================================================================
    /**
     * A followup action standing alone as an entire ability, where "it" is the card that fired the
     * trigger rather than one the player chooses — 26-032L Charlotte, "When a Character enters your
     * opponent's field, dull it and Freeze it."
     *
     * <p>{@link #FOLLOWUP_DULL_AND_FREEZE} matches this text, but only ever runs as the followup of
     * a Choose primary, so nothing in the dispatch chains reaches it and the ability resolves to
     * nothing. Anchored with {@code ^...$} on purpose: the followup patterns match with
     * {@code find()}, and a standalone hook on them would claim the tail of every Choose ability.
     *
     * <p>Singular "it" only, for every action listed. The plural "Dull them and Freeze them."
     * always refers to a set chosen in an earlier sentence (10-028L, 15-037L), and {@code parse()}'s
     * sentence-split fallback would otherwise reach this parser with that sentence in isolation —
     * leaving the ability reported as handled while it resolves against a target that was never
     * preloaded. Every action admitted here must be one {@link ActionResolver#parseTargetAction}
     * can build, since that is what actually applies it.
     *
     * <p>Deliberately limited to dull-and-freeze. Sweeping the corpus for a bare singular followup
     * used as an entire ability turns up exactly three: 26-032L Charlotte and 4-039R Rogue, both
     * this wording, and 28-043R Gi Nattak's "break it" — which is a delayed effect on a Forward
     * chosen by an earlier trigger, with nothing preloaded when it runs. Every other occurrence of
     * "break it", "freeze it" and the rest is a Choose followup, Breaktouch (handled in
     * {@code DamageResolver}) or a Remedi-style pay-or-else watcher, all already resolved
     * elsewhere. Admitting them here gains no card and costs accuracy: the shorter forms are
     * sub-clauses of larger abilities, and claiming them changed which parser won for 3-030L Kuja
     * and 26-096C Mini Fighter, degrading both descriptions.
     */
    /**
     * "Choose &lt;target&gt;. At the end of your opponent's turn, &lt;action&gt; it." — 28-043R Gi Nattak.
     *
     * <p>The choice happens now and the action lands later, so the two halves cannot be resolved
     * independently: the delayed clause has no target of its own, and the choose clause alone does
     * not parse — a choose with no action is not an effect. The target spec is therefore captured
     * here rather than delegated, and {@code action} goes through
     * {@link ActionResolver#parseTargetAction}.
     *
     * <p>Scoped to "[up to] N Forward(s) opponent controls", which is the whole of this family in
     * the corpus (Gi Nattak, both Azuls, Antlion, Dadaluma, Faris).
     */
    static final Pattern CHOOSE_THEN_END_OF_OPP_TURN_ACTION = Pattern.compile(
        "(?is)^choose\\s+(?<upto>up\\s+to\\s+)?(?<count>\\d+)\\s+Forwards?\\s+" +
        "(?:your\\s+)?opponent\\s+controls[.!]\\s+" +
        "At\\s+the\\s+end\\s+of\\s+your\\s+opponent'?s?\\s+turn,\\s*(?<action>.+?)\\s*[.!]?$"
    );
    /**
     * <p>"Break that Character" is admitted alongside the dull-and-freeze forms despite the
     * caution above, because the demonstrative is not the ambiguous "it" that made the earlier
     * widening unsafe: a corpus sweep finds no other bare "break that Character" sentence, and
     * every "that Forward" that does appear (20-102L, 28-010R, 4-035R, 14-038H) carries a
     * "When/If you do so" prefix binding it to a card named earlier in its own ability. The
     * anchors on both this pattern and {@link #FOLLOWUP_BREAK_DEMONSTRATIVE} keep those out, and
     * the Forward wording stays out entirely because it is Breaktouch's.
     */
    static final Pattern TRIGGERED_TARGET_ACTION_BARE = Pattern.compile(
        "(?i)^(?:dull\\s+it\\s+and\\s+freeze\\s+it|dull\\s+and\\s+freeze\\s+it" +
        "|break\\s+that\\s+Character)\\s*[.!]?$"
    );
    /** Matches "Dull it/them and deal it/them N damage". Group {@code amount} is the damage value. */
    static final Pattern FOLLOWUP_DULL_AND_DAMAGE = Pattern.compile(
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
    static final Pattern FOLLOWUP_FIRST_AND_OTHER = Pattern.compile(
        "(?i)(?<firstpfx>.+?)\\s+the\\s+first\\s+(?:Forward|Backup|Character|Monster|one)" +
        "(?<firstsfx>[^,]*?)[,.]?\\s+(?:and\\s+)?" +
        "(?<othereffect>dull\\s+and\\s+freeze|activate|break|dull|freeze" +
        "|remove\\s+from\\s+the\\s+game|return\\s+to\\s+its\\s+owner'?s\\s+hand)" +
        "\\s+the\\s+other\\.?$"
    );
    /**
     * Matches "Break [Self] as well as the Forward that blocks or is blocked by [Self]." —
     * 2-114C Ninja, which trades itself for whatever it is in Battle with.
     *
     * <p>Both names are captured so the parser can check them against the source: the sentence
     * only means the source card, and reading it off a text naming something else would break the
     * wrong pair.
     */
    static final Pattern BREAK_SELF_AND_BATTLE_PARTNER = Pattern.compile(
        "(?i)^Break\\s+(?<name>.+?)\\s+as\\s+well\\s+as\\s+the\\s+Forward\\s+that\\s+" +
        "blocks\\s+or\\s+is\\s+blocked\\s+by\\s+(?<name2>.+?)[.!]?$"
    );
    /**
     * Matches "Until the end of the turn, all the Forwards opponent controls lose &lt;traits&gt;." —
     * 15-022C Amidatelion, whose whole ability is stripping keywords off the opposing board.
     *
     * <p>Distinct from {@link #ALL_FIELD_POWER_BOOST_PATTERN}, which spells the same "all the
     * Forwards … lose" shape but requires a power figure: this one moves no power at all, so that
     * pattern could never reach it.
     */
    static final Pattern ALL_OPP_FORWARDS_LOSE_TRAITS_EOT = Pattern.compile(
        "(?i)^Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,\\s+all\\s+(?:the\\s+)?Forwards?\\s+" +
        "(?:your\\s+)?opponent\\s+controls\\s+lose\\s+" +
        "(?<traits>(?:Haste|First\\s+Strike|Brave)" +
        "(?:\\s*,\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*" +
        "(?:\\s+and\\s+(?:Haste|First\\s+Strike|Brave))?)[.!]?$"
    );
    /**
     * Matches "Remove [Self] from the game. At the beginning of your next Main Phase 1, play
     * [Self] onto the field." — 23-051L Hope's self-blink across the turn boundary.
     *
     * <p>Must be read as one sentence pair: on its own the first half is an ordinary
     * remove-from-game, which is exactly what used to happen — Hope left and never came back.
     */
    static final Pattern REMOVE_SELF_RETURN_NEXT_MAIN_PHASE_1 = Pattern.compile(
        "(?i)^Remove\\s+(?<name>.+?)\\s+from\\s+(?:the\\s+)?game\\.\\s+" +
        "At\\s+the\\s+beginning\\s+of\\s+your\\s+next\\s+Main\\s+Phase\\s+1,\\s+" +
        "play\\s+(?<name2>.+?)\\s+onto\\s+the\\s+field[.!]?$"
    );
    /**
     * Matches "When that Forward leaves the field this turn, put [Self] into the Break Zone." —
     * the drawback half of 7-055R Chocobo's power lend, attached to the Forward just chosen.
     */
    static final Pattern SECONDARY_WHEN_TARGET_LEAVES_PUT_SELF_TO_BZ = Pattern.compile(
        "(?i)^When\\s+that\\s+(?:Forward|Character)\\s+leaves\\s+the\\s+field\\s+this\\s+turn,\\s+" +
        "put\\s+(?<name>.+?)\\s+into\\s+the\\s+Break\\s+Zone[.!]?$"
    );
    /**
     * Matches "Choose 1 Summon or auto-ability. During this turn, if it deals damage to a Forward
     * or a player, the damage becomes 0 instead." — 29-012H Neon's Runic.
     *
     * <p>The sibling of {@link #STANDALONE_CANCEL_STACK_ENTRY_PATTERN}: same choice off the Stack,
     * a softer answer to it. Anchored whole so it cannot claim the cancel wording, which shares
     * its first sentence exactly.
     */
    static final Pattern CHOOSE_STACK_ENTRY_ZERO_ITS_DAMAGE = Pattern.compile(
        "(?is)^Choose\\s+1\\s+Summon\\s+or\\s+auto-ability[.!]?\\s+" +
        "During\\s+this\\s+turn,\\s+if\\s+it\\s+deals\\s+damage\\s+to\\s+a\\s+Forward\\s+or\\s+a\\s+player,\\s+" +
        "the\\s+damage\\s+becomes\\s+0\\s+instead[.!]?$"
    );
    /**
     * Matches "It gains +N power until the end of the turn. If you control &lt;condition&gt;, it gains
     * +M power until the end of the turn instead." — 4-090R Biggs, whose lend is bigger while it
     * has Wedge to work with.
     *
     * <p>"Instead" is load bearing: the two grants replace one another rather than stacking, so
     * the condition picks a single figure. Both halves are captured to keep the parser from
     * assuming the second is the larger.
     */
    static final Pattern FOLLOWUP_POWER_BOOST_CONTROL_GATED_INSTEAD = Pattern.compile(
        "(?is)^It\\s+gains\\s+\\+(?<base>\\d+)\\s+power\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?\\s+" +
        "If\\s+you\\s+control\\s+(?<cond>.+?),\\s+it\\s+gains\\s+\\+(?<alt>\\d+)\\s+power\\s+" +
        "until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s+instead[.!]?$"
    );
    /**
     * Matches "Deal it damage equal to [Self]'s power. If you discarded a Summon to pay this
     * ability's cost, deal it double the damage of the power of [Self] instead." — 29-107C Seer
     * (FFTA2), whose 《Dull》, discard 1 card cost pays double when the discard was a Summon.
     *
     * <p>Both names are captured so the parser can hold them against the source: the sentence is
     * about the card carrying it, and the doubled figure is its power, not the target's.
     */
    static final Pattern FOLLOWUP_DAMAGE_SELF_POWER_DOUBLED_IF_SUMMON_DISCARD = Pattern.compile(
        "(?is)^Deal\\s+it\\s+damage\\s+equal\\s+to\\s+(?<name>.+?)'s\\s+power[.!]?\\s+" +
        "If\\s+you\\s+discarded\\s+a\\s+Summon\\s+to\\s+pay\\s+this\\s+ability's\\s+cost,\\s+" +
        "deal\\s+it\\s+double\\s+the\\s+damage\\s+of\\s+the\\s+power\\s+of\\s+(?<name2>.+?)\\s+instead[.!]?$"
    );

    // =========================================================================================
    // Break, element change and ability loss
    // =========================================================================================
    /** Matches "Break it" or "Break them". */
    static final Pattern FOLLOWUP_BREAK = Pattern.compile(
        "(?i)Break\\s+(?:it|them)"
    );
    /**
     * Matches "Its/Their Element becomes [Element]." — the chosen-target element change printed on
     * 12-021R Necron, with its permanence reminder optionally trailing.
     *
     * <p>Anchored at the start so it cannot claim the tail of a longer sentence that merely ends
     * this way; the reminder is optional because the same effect is printed with and without it.
     */
    static final Pattern FOLLOWUP_ELEMENT_BECOMES = Pattern.compile(
        "(?i)^(?:Its|Their)\\s+Elements?\\s+becomes?\\s+" +
        "(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)[.!]?\\s*" +
        "(?:\\(This\\s+effect\\s+does\\s+not\\s+end\\s+at\\s+the\\s+end\\s+of\\s+the\\s+turn\\.?\\)[.!]?)?\\s*$"
    );
    /**
     * Matches "Break that Character." — the demonstrative form, 5-130R Tonberry.
     *
     * <p>"Character" only, deliberately. "Break that <b>Forward</b>" is Breaktouch's printed
     * wording, and {@code DamageResolver} keys its dedicated break path off
     * {@link ActionResolver#isTriggeredTargetAction}; admitting the Forward form here diverts
     * Breaktouch into the preloaded-target path and breaks it.
     *
     * <p>Anchored, unlike {@link #FOLLOWUP_BREAK}, because it feeds
     * {@link #TRIGGERED_TARGET_ACTION_BARE}: the corpus also carries "When you do so, break that
     * Forward" (20-102L) and similar, whose antecedent is a card named earlier in the same
     * ability rather than the one that fired the trigger.
     */
    static final Pattern FOLLOWUP_BREAK_DEMONSTRATIVE = Pattern.compile(
        "(?i)^Break\\s+that\\s+Character\\s*[.!]?$"
    );
    /**
     * Matches "As long as [CardName] is on the field, it loses all its abilities." — the standing
     * silence 25-035L Aerith and 20-116R Meliadoul lay on a Character as they enter.
     *
     * <p>Not a duration in turns: the effect lasts exactly as long as the card that made it stays
     * on the field, so it is answered as a live query rather than scheduled for cleanup. Checked
     * ahead of {@link #FOLLOWUP_LOSE_ALL_ABILITIES_EOT}, which reads the same "loses all its
     * abilities" phrase and would be wrong about when it ends — that pattern demands an explicit
     * "until the end of the turn", so today the two cannot both match, but the specific one goes
     * first regardless.
     */
    static final Pattern FOLLOWUP_LOSES_ABILITIES_WHILE_NAMED_ON_FIELD = Pattern.compile(
        "(?i)^As\\s+long\\s+as\\s+(?<name>.+?)\\s+is\\s+on\\s+the\\s+field,\\s+" +
        "(?:it|they)\\s+loses?\\s+all\\s+(?:its|their)\\s+abilities[.!]?$"
    );
    /** Matches "It loses all [its] abilities until the end of the turn." */
    static final Pattern FOLLOWUP_LOSE_ALL_ABILITIES_EOT = Pattern.compile(
        "(?i)It\\s+loses\\s+all\\s+(?:its\\s+)?abilities\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );
    /**
     * Matches both word orders of "it loses all its abilities and its power becomes N until the
     * end of the turn" — Wakka 1-216S's Status Reels, which wipes abilities and replaces base
     * power in one clause.
     *
     * <p>Must be tried before {@link #FOLLOWUP_LOSE_ALL_ABILITIES_EOT} and
     * {@link #FOLLOWUP_POWER_REDUCE_UNTIL}: with the duration clause leading, the former's
     * "abilities until end of turn" adjacency fails while the latter matches "Until …, it loses"
     * with empty amount and trait groups, so the ability wipe and the power change are both lost.
     *
     * <p>Group {@code power} — the new base power.
     */
    static final Pattern FOLLOWUP_LOSE_ABILITIES_AND_POWER_BECOMES = Pattern.compile(
        "(?i)(?:Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+)?" +
        "(?:it|they)\\s+loses?\\s+all\\s+(?:(?:its|their)\\s+)?abilities\\s+and\\s+" +
        "(?:its|their)\\s+power\\s+becomes?\\s+(?<power>\\d+)" +
        "(?:\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn)?[.!]?"
    );
    /** Matches "Remove it/them from the game". */
    static final Pattern FOLLOWUP_REMOVE_FROM_GAME = Pattern.compile(
        "(?i)Remove\\s+(?:it|them)\\s+from\\s+(?:the\\s+)?game"
    );

    // =========================================================================================
    // Remove from game; opponent hand disruption
    // =========================================================================================
    /**
     * Matches the secondary "Then, play the removed Forward onto the field [dull]."
     * Used after a RemoveFromGame primary to play the just-removed card back onto the field.
     * Group {@code dull} — present if the card enters dull.
     */
    static final Pattern SECONDARY_PLAY_REMOVED_ONTO_FIELD = Pattern.compile(
        "(?i)^(?:Then,?\\s+)?play\\s+the\\s+removed\\s+(?:Forward|Character)" +
        "\\s+onto\\s+(?:the\\s+)?field(?:\\s+(?<dull>dull))?[.!]?\\s*$"
    );
    /**
     * Matches "Remove it/them and [CardName] from the game" — chosen target(s) plus a named card.
     * Group {@code named} — the additional card name to remove.
     */
    static final Pattern FOLLOWUP_REMOVE_FROM_GAME_AND_NAMED = Pattern.compile(
        "(?i)Remove\\s+(?:it|them)\\s+and\\s+(?<named>.+?)\\s+from\\s+(?:the\\s+)?game[.!]?"
    );
    /**
     * Matches "Your opponent randomly removes N card(s) in his/her/their hand from the game."
     * Group 1 — count.
     */
    static final Pattern OPPONENT_RANDOM_HAND_RFP = Pattern.compile(
        "(?i)Your\\s+opponent\\s+randomly\\s+removes?\\s+(\\d+)\\s+cards?\\s+in\\s+" +
        "(?:his/her|his|her|their)\\s+hand\\s+from\\s+(?:the\\s+)?game[.!]?"
    );
    /**
     * Matches "Your opponent randomly places N card(s) from their hand at the bottom of their deck."
     * Group 1 — count.
     */
    static final Pattern OPPONENT_RANDOM_HAND_TO_BOTTOM_DECK = Pattern.compile(
        "(?i)Your\\s+opponent\\s+randomly\\s+places?\\s+(\\d+)\\s+cards?\\s+from\\s+" +
        "(?:his/her|his|her|their)\\s+hand\\s+at\\s+the\\s+bottom\\s+of\\s+(?:his/her|his|her|their)\\s+deck[.!]?"
    );
    /**
     * Matches the style "reveal and select from hand to remove from game":
     * "Your opponent reveals their hand. Select N card(s) in their hand.
     *  Your opponent removes it/them from the game."
     * Group 1 — count of cards to select.
     */
    static final Pattern REVEAL_SELECT_HAND_RFP = Pattern.compile(
        "(?i)Your\\s+opponent\\s+reveals?\\s+(?:his/her|his|her|their)\\s+hand[.!]\\s+" +
        "Select\\s+(\\d+)\\s+cards?\\s+in\\s+(?:his/her|his|her|their)\\s+hand[.!]\\s+" +
        "Your\\s+opponent\\s+removes?\\s+(?:it|them)\\s+from\\s+(?:the\\s+)?game[.!]?"
    );
    /**
     * Matches "Your opponent reveals their hand. Select up to N card(s) in their hand.
     * Your opponent removes them from the game. At the end of your opponent's turn, add them
     * to their owner's hand." — 29-054R Great Malboro.
     *
     * <p>The removal is temporary, which is what separates this from
     * {@link #REVEAL_SELECT_HAND_RFP}: the two share a three-sentence prefix, so this must be
     * tried first or the delayed return is silently dropped. Group {@code count} — how many.
     */
    static final Pattern REVEAL_SELECT_HAND_RFP_UNTIL_END_OF_OPP_TURN = Pattern.compile(
        "(?i)Your\\s+opponent\\s+reveals?\\s+(?:his/her|his|her|their)\\s+hand[.!]\\s+" +
        "Select\\s+(?:up\\s+to\\s+)?(?<count>\\d+)\\s+cards?\\s+(?:from|in)\\s+" +
        "(?:his/her|his|her|their)\\s+hand[.!]\\s+" +
        "Your\\s+opponent\\s+removes?\\s+(?:it|them)\\s+from\\s+(?:the\\s+)?game[.!]\\s+" +
        "At\\s+the\\s+end\\s+of\\s+your\\s+opponent's\\s+turn,?\\s+add\\s+(?:it|them)\\s+to\\s+" +
        "(?:its|their)\\s+owner's\\s+hand[.!]?"
    );
    /**
     * Matches "Your opponent reveals their hand. Select 1 [restriction] card from/in their hand.
     * Your opponent discards this card." — the discard sibling of {@link #REVEAL_SELECT_HAND_RFP}.
     *
     * <p>Group {@code count} — how many. The restriction on what may be selected appears in one
     * of three shapes across the corpus, at most one at a time:
     * {@code cardtype} ("Select 1 <b>Forward</b> from their hand", "1 <b>Character</b> card"),
     * {@code cost} ("Select 1 card <b>of cost 4 or more</b> in their hand") and
     * {@code excl} ("Select 1 card in their hand <b>other than a Backup</b>"). All three are
     * absent for the plain "Select 1 card from their hand".
     */
    static final Pattern REVEAL_SELECT_HAND_DISCARD = Pattern.compile(
        "(?i)Your\\s+opponent\\s+reveals?\\s+(?:his/her|his|her|their)\\s+hand[.!]\\s+" +
        "Select\\s+(?<count>\\d+)\\s+" +
        "(?:(?<cardtype>Forwards?|Backups?|Monsters?|Summons?|Characters?)(?:\\s+cards?)?" +
        "|cards?(?:\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+more)?)" +
        "\\s+(?:from|in)\\s+(?:his/her|his|her|their)\\s+hand" +
        "(?:\\s+other\\s+than\\s+an?\\s+(?<excl>Forward|Backup|Monster|Summon))?[.!]\\s+" +
        "Your\\s+opponent\\s+discards?\\s+(?:this|that|the\\s+selected)\\s+cards?[.!]?"
    );
    /**
     * Matches "Your opponent reveals their hand. You may select 1 card from their hand.
     * If you do so, your opponent discards it and draws 1 card."
     * (24-046R Leech Bat, 25-042C Zidane — the discard sibling of
     * {@link #REVEAL_HAND_OPT_PICK_RFP_OPP_DRAW}, which removes the card from the game instead.)
     */
    static final Pattern REVEAL_HAND_OPT_PICK_DISCARD_OPP_DRAW = Pattern.compile(
        "(?i)Your\\s+opponent\\s+reveals?\\s+(?:his/her|his|her|their)\\s+hand[.!]\\s+" +
        "You\\s+may\\s+select\\s+1\\s+card\\s+(?:from|in)\\s+(?:his/her|his|her|their)\\s+hand[.!]\\s+" +
        "If\\s+you\\s+do\\s+so,\\s+your\\s+opponent\\s+discards?\\s+it\\s+and\\s+draws\\s+1\\s+card[.!]?"
    );
    /**
     * Matches "Your opponent reveals their hand. You may select 1 card from their hand.
     * If you do so, remove it from the game and your opponent draws 1 card."
     * (Zidane-style: optional select, you remove it, opponent draws.)
     */
    static final Pattern REVEAL_HAND_OPT_PICK_RFP_OPP_DRAW = Pattern.compile(
        "(?i)Your\\s+opponent\\s+reveals?\\s+(?:his/her|his|her|their)\\s+hand[.!]\\s+" +
        "You\\s+may\\s+select\\s+1\\s+card\\s+from\\s+(?:his/her|his|her|their)\\s+hand[.!]\\s+" +
        "If\\s+you\\s+do\\s+so,\\s+remove\\s+it\\s+from\\s+(?:the\\s+)?game\\s+" +
        "and\\s+your\\s+opponent\\s+draws\\s+1\\s+card[.!]?"
    );
    /**
     * Matches "Your opponent removes N card(s) in his/her/their hand from the game."
     * (opponent chooses which cards — not random).  Group 1 — count.
     */
    static final Pattern OPPONENT_HAND_RFP = Pattern.compile(
        "(?i)Your\\s+opponent\\s+removes?\\s+(\\d+)\\s+cards?\\s+in\\s+" +
        "(?:his/her|his|her|their)\\s+hand\\s+from\\s+(?:the\\s+)?game[.!]?"
    );
    /** Matches "Remove all the cards in your opponent's Break Zone from the game." */
    static final Pattern REMOVE_ALL_OPP_BZ_FROM_GAME = Pattern.compile(
        "(?i)^remove\\s+all\\s+the\\s+cards\\s+in\\s+your\\s+opponent'?s\\s+Break\\s+Zone\\s+from\\s+(?:the\\s+)?game[.!]?\\s*$"
    );
    /**
     * Matches "Remove [CardName] from the game." The {@code the top …} guard keeps deck-top removals
     * ("Remove the top 4 cards of your deck from the game", Libroarian 8-084R) out: this pattern is
     * loose enough to read that phrase as a card name and would otherwise claim it first, quietly
     * removing nothing.
     */
    static final Pattern REMOVE_NAMED_FROM_GAME = Pattern.compile(
        "(?i)Remove\\s+(?!(?:it|them)\\b)(?!the\\s+top\\b)(?<named>.+?)\\s+from\\s+(?:the\\s+)?game[.!]?"
    );
    /** Matches "You may remove [CardName] from the game." — optional self-RFP. */
    static final Pattern YOU_MAY_REMOVE_NAMED_FROM_GAME = Pattern.compile(
        "(?i)^you\\s+may\\s+remove\\s+(?<name>.+?)\\s+from\\s+(?:the\\s+)?game[.!]?\\s*$"
    );
    /**
     * Matches "You may reveal 1 [Element] card from your hand."
     * Group {@code element} — the required element name.
     */
    static final Pattern YOU_MAY_REVEAL_ELEMENT_FROM_HAND = Pattern.compile(
        "(?i)^You\\s+may\\s+reveal\\s+1\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
        "\\s+card\\s+from\\s+your\\s+hand[.!]?\\s*$"
    );
    /** Matches "At the end of your opponent's turn, play [CardName] onto the field." */
    static final Pattern AT_END_OF_OPP_TURN_PLAY_NAMED_ONTO_FIELD = Pattern.compile(
        "(?i)^at\\s+the\\s+end\\s+of\\s+your\\s+opponent'?s\\s+turn,?\\s+play\\s+(?<name>.+?)\\s+onto\\s+the\\s+field[.!]?\\s*$"
    );

    // =========================================================================================
    // Source card to the field, Break Zone or deck
    // =========================================================================================
    /**
     * Matches "Play [CardName] onto the field at the end of the turn." — the delayed half of a
     * self-blink, where the card removed itself from the game in the preceding sentence
     * (Lightning 16-124H's Switch Schemata). The card is played back from the RFG zone, so this
     * must be reached ahead of {@link #PLAY_SOURCE_ONTO_FIELD_PATTERN}, whose Break-Zone-origin
     * reading would fire immediately and from the wrong zone.
     */
    static final Pattern PLAY_NAMED_ONTO_FIELD_AT_END_OF_TURN = Pattern.compile(
        "(?i)^play\\s+(?<name>.+?)\\s+onto\\s+the\\s+field\\s+at\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?\\s*$"
    );
    /**
     * Matches "Remove [Self] from the game. Then, play [Self] onto the field [dull]." — the
     * immediate self-blink of Lightning 4-115L, as against the delayed
     * {@link #PLAY_NAMED_ONTO_FIELD_AT_END_OF_TURN} form of Lightning 16-124H.
     *
     * <p>Both sentences must be claimed together. Split apart they compose cleanly — neither
     * refers back with a pronoun, so the independent-sentence rule accepts them — and the second
     * then resolves through {@link #PLAY_SOURCE_ONTO_FIELD_PATTERN}, which reads the Break Zone.
     * The card is in the RFG zone by then, so the replay silently finds nothing.
     *
     * <p>Groups: {@code name} and {@code name2} — both must equal the source's name;
     * {@code dull} — non-null when the card returns dull.
     */
    static final Pattern REMOVE_SELF_THEN_PLAY_SELF_ONTO_FIELD = Pattern.compile(
        "(?i)^Remove\\s+(?<name>.+?)\\s+from\\s+(?:the\\s+)?game[.!]\\s+(?:Then,?\\s+)?" +
        "play\\s+(?<name2>.+?)\\s+onto\\s+(?:the\\s+)?field(?:\\s+(?<dull>dull))?[.!]?\\s*$"
    );
    /** Matches "Break [CardName]." — used when the source card breaks itself. */
    static final Pattern BREAK_SOURCE_CARD = Pattern.compile(
        "(?i)^break\\s+(?<name>.+?)[.!]?$"
    );
    /** Matches "put [CardName] into the Break Zone[.!]?" where CardName is the source card. */
    static final Pattern PUT_SOURCE_INTO_BREAK_ZONE = Pattern.compile(
        "(?i)^put\\s+(?<name>.+?)\\s+into\\s+the\\s+Break\\s+Zone[.!]?$"
    );
    /**
     * "you may put [CardName] into the Break Zone. When you do so, [effect]"
     * Prompts the player; if they choose to break the source card, the follow-up effect fires.
     * Groups: {@code name} — card name (must equal source); {@code effect} — the conditional effect.
     */
    static final Pattern YOU_MAY_PUT_SELF_TO_BZ_WHEN_DO_SO = Pattern.compile(
        "(?i)^you\\s+may\\s+put\\s+(?<name>.+?)\\s+into\\s+the\\s+Break\\s+Zone[.!]?\\s+" +
        "When\\s+you\\s+do\\s+so,\\s+(?<effect>.+)$",
        Pattern.DOTALL
    );
    /**
     * Matches "If your opponent doesn't control [any] Forwards, put [CardName] into the Break Zone."
     * Group {@code name} — the card name that goes to the Break Zone (must equal source name).
     */
    static final Pattern IF_OPP_NO_FORWARDS_PUT_TO_BREAK_ZONE = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+(?:doesn'?t|does\\s+not)\\s+control\\s+(?:any\\s+)?Forwards?," +
        "\\s+put\\s+(?<name>.+?)\\s+into\\s+the\\s+Break\\s+Zone[.!]?$"
    );
    /**
     * Matches "If either player doesn't control [any] Forwards, put [CardName] into the Break Zone."
     * Fires if either the controller or their opponent has zero Forwards.
     * Group {@code name} — the card name that goes to the Break Zone (must equal source name).
     */
    static final Pattern IF_EITHER_PLAYER_NO_FORWARDS_PUT_SOURCE_TO_BZ = Pattern.compile(
        "(?i)^If\\s+either\\s+player\\s+(?:doesn'?t|does\\s+not)\\s+control\\s+(?:any\\s+)?Forwards?," +
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
    static final Pattern BREAK_BLOCKING_FORWARD = Pattern.compile(
        "(?i)^break\\s+the\\s+blocking\\s+Forward[.!]?$"
    );
    /** Matches "Break the Forward that blocks [Name][.!]?" — group {@code name}. */
    static final Pattern BREAK_FORWARD_THAT_BLOCKS_CARD = Pattern.compile(
        "(?i)^Break\\s+the\\s+Forward\\s+that\\s+blocks?\\s+(?<name>[^.!]+?)[.!]?$"
    );
    /**
     * Matches "Choose 1 card with EX Burst in your Damage Zone. You may trigger its EX Burst effect."
     * with an optional trailing parenthetical rules note.
     */
    static final Pattern CHOOSE_EX_BURST_FROM_DAMAGE_ZONE = Pattern.compile(
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
    static final Pattern DAMAGE_ZONE_SWAP_PATTERN = Pattern.compile(
        "(?i)^choose\\s+1\\s+card\\s+in\\s+your\\s+Damage\\s+Zone\\.\\s+" +
        "Add\\s+it\\s+to\\s+your\\s+hand(?<draw>\\s+and\\s+draw\\s+1\\s+card)?\\.\\s+" +
        "(?:Then,?\\s+)?Put\\s+1\\s+card\\s+from\\s+your\\s+hand\\s+into\\s+the\\s+Damage\\s+Zone" +
        "\\s*\\([^)]*\\)\\.?\\s*$"
    );
    /**
     * Matches "Remove the top [N cards / card] of your deck from the game."
     * Group {@code count} — number of cards (absent means 1).
     */
    static final Pattern REMOVE_TOP_OF_DECK_FROM_GAME = Pattern.compile(
        "(?i)Remove\\s+the\\s+top\\s+(?:(?<count>\\d+)\\s+cards?|card)\\s+of\\s+your\\s+deck\\s+from\\s+(?:the\\s+)?game\\.?"
    );

    // =========================================================================================
    // Reveal the top of the deck: remove, damage, play
    // =========================================================================================
    /**
     * Matches "Reveal the top N cards of your deck. Remove 1 [Category X] card among them from the
     * game and [return the other cards|put the other] to the bottom of your deck [in any order].
     * You can cast it at any time you could normally cast it this turn. [The cost required to cast
     * it is reduced by M.]" — Snow 18-109C (Category filter), Warrior of Light 20-004C (neither
     * option), Helena Leonis 22-052H (singular rest clause and a discount).
     *
     * <p>Helena's two-card reveal leaves exactly one card over, which is why her printing says "the
     * other" and skips "in any order" — with one card there is no order to choose. Both wordings
     * describe the same arrangement, so they share a parser rather than the singular form getting
     * one of its own.
     *
     * <p>Must precede {@code tryParseRemoveNamedFromGame} in all three chains. That parser matches
     * with {@code find()} on a lazy name group, so it claims this text off its middle clause with
     * {@code named = "1 Category XIII card among them"} — a card name that is on nobody's field, so
     * the ability parsed and then did nothing at all.
     * Groups: {@code reveal}, {@code category} (optional), {@code reduction} (optional).
     */
    static final Pattern REVEAL_TOP_N_RFG_ONE_CASTABLE_REST_BOTTOM = Pattern.compile(
        "(?i)^Reveal\\s+the\\s+top\\s+(?<reveal>\\d+)\\s+cards?\\s+of\\s+your\\s+deck\\.\\s+" +
        "Remove\\s+1\\s+(?:Category\\s+(?<category>[A-Za-z0-9\\-]+)\\s+)?card\\s+among\\s+them\\s+" +
        "from\\s+the\\s+game\\s+and\\s+(?:return\\s+the\\s+other\\s+cards?|put\\s+the\\s+other)\\s+" +
        "to\\s+the\\s+bottom\\s+of\\s+your\\s+deck(?:\\s+in\\s+any\\s+order)?\\.\\s+" +
        "You\\s+can\\s+cast\\s+it\\s+at\\s+any\\s+time\\s+you\\s+could\\s+normally\\s+cast\\s+it\\s+this\\s+turn[.!]?" +
        "(?:\\s+The\\s+cost\\s+required\\s+to\\s+cast\\s+it\\s+is\\s+reduced\\s+by\\s+(?<reduction>\\d+)[.!]?)?\\s*$"
    );
    /**
     * Matches the compound followup "Remove the top card of your deck from the game.
     * Deal it/them N damage for each CP required to play/cast the removed card."
     * Group {@code base} — damage per CP.
     */
    static final Pattern FOLLOWUP_RFP_TOP_DECK_AND_DAMAGE_PER_CP = Pattern.compile(
        "(?i)Remove\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck\\s+from\\s+(?:the\\s+)?game\\.\\s+" +
        "Deal\\s+(?:it|them)\\s+(?<base>\\d+)\\s+damage\\s+for\\s+each\\s+CP\\s+required\\s+to\\s+(?:play|cast)\\s+the\\s+removed\\s+card[.!]?"
    );
    /**
     * Matches the compound followup "Remove the top card of your deck from the game. If the removed
     * card is a Forward, break it. If not, deal it N damage." — the break / damage both apply to the
     * Forward chosen by the preceding "Choose 1 Forward" header ({@code it}).
     * Group {@code dmg} — damage dealt when the removed card is not a Forward.
     */
    static final Pattern FOLLOWUP_RFP_TOP_DECK_IF_FORWARD_BREAK_ELSE_DAMAGE = Pattern.compile(
        "(?i)Remove\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck\\s+from\\s+(?:the\\s+)?game\\.\\s+" +
        "If\\s+the\\s+removed\\s+card\\s+is\\s+a\\s+Forward,?\\s+break\\s+it\\.\\s+" +
        "If\\s+not,?\\s+deal\\s+it\\s+(?<dmg>\\d+)\\s+damage[.!]?"
    );
    /**
     * Matches the compound followup "Reveal the top N cards of your deck.
     * Deal it/them M damage for each CP required to play/cast the revealed cards.
     * Add all the revealed cards to your hand."
     * Groups: {@code n} — card count, {@code base} — damage per CP.
     */
    static final Pattern FOLLOWUP_REVEAL_TOP_N_DAMAGE_PER_CP_ADD_ALL_TO_HAND = Pattern.compile(
        "(?i)Reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck\\.\\s+" +
        "Deal\\s+(?:it|them)\\s+(?<base>\\d+)\\s+damage\\s+for\\s+each\\s+CP\\s+required\\s+to\\s+(?:play|cast)\\s+the\\s+revealed\\s+cards?\\.\\s+" +
        "Add\\s+all\\s+(?:the\\s+)?revealed\\s+cards?\\s+to\\s+your\\s+hand[.!]?"
    );
    /**
     * Matches the compound followup "Remove them from the game. If these cards are of the
     * same card type, also draw N card(s)."
     * Group {@code count} — number of cards to draw.
     */
    static final Pattern FOLLOWUP_RFP_IF_SAME_TYPE_DRAW = Pattern.compile(
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
    static final Pattern FOLLOWUP_REVEAL_TOP_N_JOB_DEAL_DMG_PLACE_BOTTOM = Pattern.compile(
        "(?i)Reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "For\\s+each\\s+(?:Job\\s+)?(?<job>.+?)\\s+revealed\\s+this\\s+way,?\\s+" +
        "deal\\s+it\\s+(?<dmg>\\d+)\\s+damage[.!]?\\s+" +
        "(?:Then,?\\s+)?[Pp]lace\\s+the\\s+revealed\\s+cards?\\s+at\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?"
    );
    /**
     * Matches the compound followup "Reveal the top N cards of your deck. Add 1 card among them
     * to your hand and return the other cards to the bottom of your deck in any order. If you
     * added a Forward to your hand, deal the chosen Forward damage equal to the power of the
     * added Forward." — 23-064R Golem.
     *
     * <p>Read whole rather than left to the followup's ". " split: the amount is a property of
     * the card the first two sentences put into hand, so neither half means anything alone. The
     * unqualified reveal in the middle is also exactly the wording
     * {@link #LOOK_TOP_DECK_ADD_TO_HAND_REST_BOTTOM} claims, which would run the reveal and drop
     * the burn.
     * Groups: {@code verb} — "Reveal" or "Look at"; {@code n} — how many cards.
     */
    static final Pattern FOLLOWUP_REVEAL_ADD_TO_HAND_IF_FORWARD_DAMAGE_ADDED_POWER = Pattern.compile(
        "(?i)^\\s*(?<verb>Look\\s+at|Reveal)\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+1\\s+card\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+and\\s+" +
        "return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+your\\s+deck\\s+in\\s+any\\s+order[.!]?\\s+" +
        "If\\s+you\\s+added\\s+a\\s+Forward\\s+to\\s+your\\s+hand,\\s+" +
        "deal\\s+the\\s+chosen\\s+Forward\\s+damage\\s+equal\\s+to\\s+the\\s+power\\s+of\\s+the\\s+added\\s+Forward[.!]?\\s*$"
    );
    /** Matches "Shuffle your deck." */
    static final Pattern SHUFFLE_DECK = Pattern.compile(
        "(?i)Shuffle\\s+your\\s+deck\\.?"
    );
    /** Matches "Its auto-ability will not trigger." — suppresses ETF auto-abilities for the played card. */
    static final Pattern ITS_AUTO_ABILITY_WILL_NOT_TRIGGER = Pattern.compile(
        "(?i)Its\\s+auto-ability\\s+will\\s+not\\s+trigger\\.?"
    );
    /** Matches "Play it onto the field" or "Play them onto the field". */
    static final Pattern FOLLOWUP_PLAY_ONTO_FIELD = Pattern.compile(
        "(?i)Play\\s+(?:it|them)\\s+onto\\s+(?:the\\s+)?field"
    );

    // =========================================================================================
    // Play onto the field; add to hand
    // =========================================================================================
    /** Matches "Play it onto the field dull" or "Play them onto the field dull". */
    static final Pattern FOLLOWUP_PLAY_ONTO_FIELD_DULL = Pattern.compile(
        "(?i)Play\\s+(?:it|them)\\s+onto\\s+(?:the\\s+)?field\\s+dull[.!]?"
    );
    /**
     * "Play the [Forward|Character|…] placed in the Break Zone onto the field dull" — Lunafreya
     * 8-132L's payoff, naming the card whose arrival in the Break Zone fired the very trigger this
     * effect hangs off.
     *
     * <p>No name and no choice: the definite article points back at the trigger's own event, which
     * is why the effect takes no target and reads {@code MainWindow.triggeringBrokenCard} instead.
     */
    static final Pattern PLAY_BROKEN_CARD_ONTO_FIELD_DULL = Pattern.compile(
        "(?i)^Play\\s+the\\s+(?:Forward|Backup|Monster|Character|card)\\s+placed\\s+in\\s+the\\s+" +
        "Break\\s+Zone\\s+onto\\s+(?:the\\s+)?field\\s+dull[.!]?$"
    );
    /**
     * "Add it to your hand." standing alone as a whole effect — Gogo 24-022H, whose "it" is the
     * Category VI Forward whose arrival in the Break Zone fired the trigger this effect hangs off.
     *
     * <p>The salvage twin of {@link #PLAY_BROKEN_CARD_ONTO_FIELD_DULL} and read the same way: the
     * pronoun points at the trigger's own event, so the effect takes no target.
     *
     * <p>Anchored end to end and matched with {@code matches()}, because the sentence is three
     * common words. Under {@code find()} it would claim the tail of every "choose … . Add it to
     * your hand." ability and resolve it with the selection dropped — which is how an earlier
     * attempt at this card broke 10-127H Citra and 14-073R Muraga Fennes. Anchoring is not enough
     * on its own: the guard is that both of those now parse as a whole through the choose chain,
     * which runs first, so this never sees their trailing sentence.
     */
    static final Pattern ADD_TRIGGERING_BROKEN_CARD_TO_HAND = Pattern.compile(
        "(?i)^Add\\s+it\\s+to\\s+your\\s+hand[.!]?$"
    );
    /**
     * Matches "When it enters the field, if it is [cond], [inner]" — a conditional secondary
     * for Play-onto-field that fires only when the played card satisfies the condition.
     * Group {@code cond} is fed to {@link #parseRevealCondition}; group {@code inner}
     * is parsed as a standalone effect via {@link #parse}.
     */
    static final Pattern FOLLOWUP_PLAY_ONTO_FIELD_WHEN_ENTERS_CONDITIONAL = Pattern.compile(
        "(?i)^When\\s+it\\s+enters\\s+(?:the\\s+)?field,?\\s+if\\s+it\\s+is\\s+(?<cond>.+?),\\s*(?<inner>.+?)[.!]?$",
        Pattern.DOTALL
    );
    /**
     * Matches "If its cost is equal to or less than the number of Job [job] you control, play it onto the field."
     * Group {@code job} captures the job name (without "Job " prefix).
     */
    static final Pattern FOLLOWUP_PLAY_IF_COST_LE_JOB_COUNT = Pattern.compile(
        "(?i)If\\s+its\\s+cost\\s+is\\s+equal\\s+to\\s+or\\s+less\\s+than\\s+the\\s+number\\s+of\\s+" +
        "Job\\s+(?<job>.+?)\\s+you\\s+control[,.]\\s+play\\s+it\\s+onto\\s+(?:the\\s+)?field[.!]?"
    );
    /**
     * Matches "If its cost is X, play it onto the field." — Leo 13-067L, where X is the number of
     * Kingdom Counters the activation removed. An exact match on the cost, not a ceiling, which is
     * what separates it from {@link #FOLLOWUP_PLAY_IF_COST_LE_JOB_COUNT} beside it.
     */
    static final Pattern FOLLOWUP_PLAY_IF_COST_IS_X = Pattern.compile(
        "(?i)If\\s+its\\s+cost\\s+is\\s+X[,.]\\s+play\\s+it\\s+onto\\s+(?:the\\s+)?field[.!]?"
    );
    /**
     * Matches "If its cost is equal to or less than the number of cards in your hand, return it to its owner's hand."
     * Used by Leviathan (5-139C) EX Burst.
     */
    static final Pattern FOLLOWUP_RETURN_IF_COST_LE_HAND = Pattern.compile(
        "(?i)If\\s+its\\s+cost\\s+is\\s+equal\\s+to\\s+or\\s+less\\s+than\\s+the\\s+number\\s+of\\s+" +
        "cards?\\s+in\\s+your\\s+hand,?\\s+return\\s+it\\s+to\\s+its\\s+owner'?s?\\s+hand[.!]?"
    );
    /** Matches "Add it to your hand" or "Add them to your hand". */
    static final Pattern FOLLOWUP_ADD_TO_HAND = Pattern.compile(
        "(?i)Add\\s+(?:it|them)\\s+to\\s+your\\s+hand"
    );
    /**
     * Matches a conditional secondary clause that depends on the card just added to hand:
     * "If (it|the added card) (is|has) [cond], [inner effect]".
     * Group {@code cond} is fed to {@link #parseRevealCondition}; group {@code inner}
     * is parsed as a standalone effect via {@link #parse}.
     */
    static final Pattern FOLLOWUP_ADD_TO_HAND_CONDITIONAL_SECONDARY = Pattern.compile(
        "(?i)^If\\s+(?:it|the\\s+added\\s+card)\\s+(?:is|has)\\s+(?<cond>[^,]+?)" +
        ",\\s*(?<inner>.+?)[.!]?$",
        Pattern.DOTALL
    );

    // =========================================================================================
    // Block and cannot-be-blocked restrictions
    // =========================================================================================
    /**
     * Matches "it cannot block this turn" or
     * "It gains 'This Forward cannot block.' until the end of the turn."
     */
    static final Pattern FOLLOWUP_CANNOT_BLOCK = Pattern.compile(
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
    static final Pattern FOLLOWUP_CANNOT_BE_BLOCKED = Pattern.compile(
        "(?i)it\\s+cannot\\s+be\\s+blocked" +
        "(?:\\s+by\\s+a\\s+Forward\\s+of\\s+cost\\s+(?<costval>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "\\s+this\\s+turn\\.?"
    );
    /**
     * Matches "It can only be blocked by a Forward of cost equal or inferior to its own this turn."
     */
    static final Pattern FOLLOWUP_ONLY_BLOCKED_BY_COST_LE_OWN = Pattern.compile(
        "(?i)it\\s+can\\s+only\\s+be\\s+blocked\\s+by\\s+a\\s+Forward\\s+of\\s+cost\\s+" +
        "(?:equal\\s+or\\s+inferior\\s+to|inferior\\s+or\\s+equal\\s+to|equal\\s+to\\s+or\\s+less\\s+than)\\s+" +
        "its\\s+own\\s+this\\s+turn[.!]?"
    );
    /** Matches "All Forwards cannot block this turn." — global block-prevention. */
    static final Pattern STANDALONE_ALL_FORWARDS_CANNOT_BLOCK = Pattern.compile(
        "(?i)All\\s+Forwards?\\s+cannot\\s+block\\s+this\\s+turn[.!]?"
    );
    /** Matches "All Forwards of cost N or less/more cannot block this turn." */
    static final Pattern STANDALONE_FORWARDS_OF_COST_CANNOT_BLOCK = Pattern.compile(
        "(?i)All\\s+Forwards?\\s+of\\s+cost\\s+(?<costval>\\d+)\\s+or\\s+(?<cmp>less|more)\\s+cannot\\s+block\\s+this\\s+turn[.!]?"
    );
    /**
     * Matches "At the end of your next turn, if [Name] is on the field, your opponent loses the game."
     */
    static final Pattern END_OF_NEXT_TURN_IF_CARD_ON_FIELD_OPP_LOSES = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+your\\s+next\\s+turn,?\\s+if\\s+(?<name>.+?)\\s+is\\s+on\\s+the\\s+field,?\\s+" +
        "your\\s+opponent\\s+loses\\s+the\\s+game[.!]?"
    );
    /**
     * Matches "All the Forwards opponent controls lose all abilities until the end of the turn."
     *
     * <p>The possessive is optional on either side of "all": printings say "lose all abilities"
     * (16-106R Andrea Rhodea), "lose all their abilities" (24-105R Malboro) and plain "lose their
     * abilities" (7-119H Halicarnassus) for one and the same effect. Its parser anchors with
     * matches(), so Malboro's longer "... and 3000 power" is not claimed off this prefix.
     */
    static final Pattern OPP_FWDS_LOSE_ALL_ABILITIES_EOT = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Forwards?\\s+(?:(?:your\\s+)?opponent\\s+controls?)\\s+" +
        "lose\\s+(?:all\\s+)?(?:their\\s+)?abilities\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );
    /**
     * Matches "All the Forwards opponent controls lose N power for each CP required to play them
     * until the end of the turn." (Flare Star / Ozma).
     * Group {@code amount} — power lost per CP of cost.
     */
    static final Pattern OPP_FWDS_LOSE_POWER_PER_PLAY_COST = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Forwards?\\s+(?:(?:your\\s+)?opponent\\s+controls?)\\s+" +
        "lose\\s+(?<amount>\\d+)\\s+power\\s+for\\s+each\\s+CP\\s+required\\s+to\\s+play\\s+them\\s+" +
        "until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );
    /**
     * Matches "All the Forwards opponent controls cannot block Forwards with a power inferior to their own this turn."
     */
    static final Pattern OPP_FWDS_CANNOT_BLOCK_INFERIOR_POWER_THIS_TURN = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Forwards?\\s+(?:(?:your\\s+)?opponent\\s+controls?)\\s+" +
        "cannot\\s+block\\s+Forwards?\\s+with\\s+a\\s+power\\s+inferior\\s+to\\s+their\\s+own\\s+this\\s+turn[.!]?"
    );
    /**
     * Matches "Each Forward can only be blocked by a Forward with a cost inferior or equal to
     * its own this turn." — global rule applying to all attackers on both sides.
     */
    static final Pattern ALL_FWDS_BLOCKED_ONLY_BY_LOWER_COST_THIS_TURN = Pattern.compile(
        "(?i)Each\\s+Forward\\s+can\\s+only\\s+be\\s+blocked\\s+by\\s+a\\s+Forward\\s+with\\s+a\\s+cost\\s+" +
        "inferior\\s+or\\s+equal\\s+to\\s+its\\s+own\\s+this\\s+turn[.!]?"
    );
    /**
     * Matches "During this turn, the power of Forwards opponent controls cannot be increased by Summons or abilities."
     * Action-ability counterpart to the persistent field effect FA_OPP_FORWARD_POWER_BOOST_SUPPRESSED.
     */
    static final Pattern OPP_FWD_POWER_BOOST_SUPPRESSED_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,?\\s+the\\s+power\\s+of\\s+Forwards?\\s+(?:your\\s+)?opponent\\s+controls?\\s+" +
        "cannot\\s+be\\s+increased\\s+by\\s+Summons?\\s+or\\s+abilit(?:y|ies)[.!]?"
    );
    /** Matches "[CardName] cannot be blocked this turn." — self-referential standalone form. */
    static final Pattern STANDALONE_SELF_CANNOT_BE_BLOCKED = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+cannot\\s+be\\s+blocked" +
        "(?:\\s+by\\s+a\\s+Forward\\s+of\\s+cost\\s+(?<costval>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "\\s+this\\s+turn[.!]?"
    );
    /** Matches "if possible, it must block this turn" or the gains-until-EOT equivalent. */
    static final Pattern FOLLOWUP_MUST_BLOCK = Pattern.compile(
        "(?i)(?:" +
            "if\\s+possible[,]?\\s+it\\s+must\\s+block\\s+this\\s+turn" +
            "|it\\s+gains\\s+[\"']If\\s+possible[,]?\\s+this\\s+Forward\\s+must\\s+block\\.?[\"']\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn" +
        ")[.!]?"
    );
    /**
     * Matches the attacker-specific must-block grant: "It gains &quot;This Forward must block
     * [CardName] if possible.&quot; until the end of the turn." (Dio 26-075C).
     *
     * <p>Distinct from {@link #FOLLOWUP_MUST_BLOCK}, whose quoted text is the unqualified
     * "If possible, this Forward must block." — that one names no attacker and compels the
     * chosen Forward to block whoever attacks. Here the compulsion only bites when
     * {@code cardname} is the attacker, so the two cannot share a handler.
     *
     * <p>Group {@code cardname} — the attacker the chosen Forward is compelled to block.
     */
    static final Pattern FOLLOWUP_GAINS_MUST_BLOCK_NAMED_UNTIL_EOT = Pattern.compile(
        "(?i)it\\s+gains\\s+[\"']This\\s+Forward\\s+must\\s+block\\s+(?<cardname>.+?)\\s+if\\s+possible[.!]?[\"']" +
        "\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn[.!]?"
    );
    /**
     * The same compulsion stated inline rather than as a quoted grant: "If it is possible, it must
     * block [CardName] this turn" (Lightning 1-141L's Army of One) and "it must block [CardName]
     * this turn if possible" (Galuf 7-067L). Group {@code cardname} — the attacker to block.
     *
     * <p>Kept apart from {@link #FOLLOWUP_MUST_BLOCK}, which reads the unqualified compulsion.
     * The two cannot overlap — that one requires "block" and "this turn" to be adjacent, and the
     * attacker's name sits between them here — but they must not share a handler either, since
     * the unqualified form compels the Forward against every attacker.
     */
    static final Pattern FOLLOWUP_MUST_BLOCK_NAMED_INLINE = Pattern.compile(
        "(?i)(?:If\\s+it\\s+is\\s+possible,\\s+)?it\\s+must\\s+block\\s+(?<cardname>.+?)\\s+" +
        "this\\s+turn(?:\\s+if\\s+possible)?[.!]?"
    );

    // =========================================================================================
    // Return to hand
    // =========================================================================================
    /** Matches "Return it to its owner's hand and draw N card(s)." — group {@code draw} is the count. */
    static final Pattern FOLLOWUP_RETURN_AND_DRAW = Pattern.compile(
        "(?i)Return\\s+it\\s+to\\s+its\\s+owner's\\s+hand\\s+and\\s+draw\\s+(?<draw>\\d+)\\s+cards?[.!]?"
    );
    /**
     * Matches "Return it and [CardName] to their owners' hand(s)." — chosen target plus a named card.
     * Group {@code named} — the additional card name to return.
     */
    static final Pattern FOLLOWUP_RETURN_AND_NAMED_TO_OWNERS_HAND = Pattern.compile(
        "(?i)Return\\s+it\\s+and\\s+(?<named>.+?)\\s+to\\s+their\\s+owners?'s?\\s+hands?[.!]?"
    );
    /** Matches "Return it/them to its/their owner's/owners' hand/hands." */
    static final Pattern FOLLOWUP_RETURN_TO_OWNERS_HAND = Pattern.compile(
        "(?i)Return\\s+(?:it|them)\\s+to\\s+(?:its|their)\\s+owners?'s?\\s+hands?\\.?"
    );
    /** Matches "Return it/them to your hand/hands." */
    static final Pattern FOLLOWUP_RETURN_TO_YOUR_HAND = Pattern.compile(
        "(?i)Return\\s+(?:it|them)\\s+to\\s+your\\s+hands?\\.?"
    );
    /**
     * Matches "Return all [the] [element] [targets] [control] to their owners' hands."
     * Named groups: {@code element}, {@code targets}, {@code control}.
     */
    static final Pattern ALL_RETURN_TO_HAND_PATTERN = Pattern.compile(
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
    static final Pattern CHOOSE_ANY_NUMBER_RETURN_TO_HAND = Pattern.compile(
        "(?i)Choose\\s+any\\s+number\\s+of\\s+" +
        "(?<types>Forwards?(?:\\s+and\\s+Monsters?)?|Monsters?(?:\\s+and\\s+Forwards?)?|Backups?|Characters?)" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "[.!]?(?:\\s*Return\\s+them\\s+to\\s+their\\s+owners?'?s?\\s+hands?[.!]?)?"
    );
    /** Matches "Return [name] to its owner's hand." — named card, not a pronoun. */
    static final Pattern RETURN_NAMED_TO_OWNERS_HAND = Pattern.compile(
        "(?i)Return\\s+(?!(?:it|them)\\b)(?<named>.+?)\\s+to\\s+its\\s+owner(?:'s|s')?\\s+hand[.!]?"
    );
    /**
     * Matches "Return [name] to your hand." — named card, not a pronoun.  The name is limited to
     * 1–5 words ("Good King Moggle Mog XII" is the longest there is); an unbounded name lets a
     * single "Return" swallow whole sentences up to a later "… to your hand", which is how
     * Schultz 27-100R's "Return these to the top and/or bottom … add it to your hand" used to be
     * claimed here instead of by the look-at-deck parsers.
     */
    static final Pattern RETURN_NAMED_TO_YOUR_HAND_STANDALONE = Pattern.compile(
        "(?i)Return\\s+(?!(?:it|them)\\b)(?<named>\\S+(?:\\s+\\S+){0,4})\\s+to\\s+your\\s+hand[.!]?"
    );
    /** Matches "Add [name] to your hand." — named card, not a pronoun or a count. Used for break-zone-origin abilities. */
    static final Pattern ADD_NAMED_TO_YOUR_HAND = Pattern.compile(
        "(?i)\\bAdd\\s+(?!(?:it|them|\\d)\\b)(?<named>.+?)\\s+to\\s+your\\s+hand[.!]?"
    );
    /**
     * Matches "Play [name] onto [the] field [dull]" without requiring a "from Break Zone" qualifier.
     * Used for break-zone-origin abilities where the card plays itself from the BZ.
     * The name is limited to 1–3 words to avoid matching non-source cards.
     *
     * <p>The trailing lookahead is what keeps this from claiming
     * {@link #PLAY_NAMED_ONTO_FIELD_AT_END_OF_TURN}: matched with {@code find()}, the expression
     * ends before "at the end of the turn" and would resolve a delayed RFG-origin play as an
     * immediate Break-Zone one — both the timing and the zone wrong, silently.
     */
    /**
     * Matches "Search for 1 Card Name X and remove it from the game. [If|When] you do so,
     * &lt;effect&gt;." — 1-093H Vanille, 20-047H Jenova Dreamweaver.
     *
     * <p>The search is mandatory but can still come up empty, which is what the "if you do so"
     * gates on: the deck may hold no copy of the named card. Group {@code effect} is handed back
     * to {@code parse()} rather than enumerated, so the payoff can be anything.
     */
    static final Pattern SEARCH_NAMED_RFG_THEN_IF_DO_SO = Pattern.compile(
        "(?i)^search\\s+for\\s+1\\s+Card\\s+Name\\s+(?<name>.+?)\\s+and\\s+remove\\s+it\\s+from\\s+the\\s+game\\.\\s+" +
        "(?:If|When)\\s+you\\s+do\\s+so,\\s+(?<effect>.+)$",
        Pattern.DOTALL
    );

    // =========================================================================================
    // Source onto the field; source to the deck
    // =========================================================================================
    /**
     * Matches "Return [CardName] onto the field [dull]" — the payoff half of
     * {@link #SEARCH_NAMED_RFG_THEN_IF_DO_SO} on 1-093H Vanille.
     *
     * <p>Deliberately <em>not</em> folded into {@link #PLAY_SOURCE_ONTO_FIELD_PATTERN} as another
     * verb: that parser plays the card back out of the owner's Break Zone, which is right for
     * Vanille (its trigger is being put there) but wrong for the corpus's only other "return …
     * onto the field" card, 9-106R Ghis, which has just removed itself from the game and would be
     * replayed from the wrong zone. Read only from the search parser, whose own gate keeps it off
     * every text but this family's.
     */
    static final Pattern RETURN_SOURCE_ONTO_FIELD = Pattern.compile(
        "(?i)^Return\\s+(?<name>.+?)\\s+onto\\s+(?:the\\s+)?field(?:\\s+(?<dull>dull))?[.!]?$"
    );
    /**
     * The anchored form of {@link #PLAY_SOURCE_ONTO_FIELD_PATTERN}: the clause is the whole
     * text, with nothing in front of it.
     *
     * <p>Read only by the naming chains. The loose pattern below is matched with find() and
     * so reports a hit inside every "search for 1 Forward ... and play it onto the field" in
     * the corpus, none of which reach its parser in parse() -- an earlier parser claims them.
     * Naming off it renamed 9 abilities away from the parser that really runs them; this form
     * fills the gap without moving any of them.
     */
    static final Pattern PLAY_SOURCE_ONTO_FIELD_BARE = Pattern.compile(
        "(?i)^Play\\s+(?<name>\\S+(?:\\s+\\S+){0,2})\\s+onto\\s+(?:the\\s+)?field(?:\\s+(?<dull>dull))?[.!]?$"
    );
    static final Pattern PLAY_SOURCE_ONTO_FIELD_PATTERN = Pattern.compile(
        "(?i)\\bPlay\\s+(?<name>\\S+(?:\\s+\\S+){0,2})\\s+onto\\s+(?:the\\s+)?field(?:\\s+(?<dull>dull))?" +
        "(?!\\s+at\\s+(?:the\\s+)?end\\s+of)[.!]?"
    );
    /**
     * Matches "If its power has become N or less/more, return [name] to your/its owner's hand."
     * Groups: {@code threshold} — power value; {@code cmp} — "less" or "more";
     * {@code name} — card name; {@code toowner} — non-null when "its owner's hand".
     */
    static final Pattern CONDITIONAL_POWER_RETURN = Pattern.compile(
        "(?i)If\\s+its?\\s+power\\s+has\\s+become\\s+(?<threshold>\\d+)\\s+or\\s+(?<cmp>less|more),\\s+" +
        "return\\s+(?<name>.+?)\\s+to\\s+(?:(?<toowner>its\\s+owner(?:'s|s')?)\\s+|your\\s+)hand[.!]?"
    );
    /**
     * Matches "Put [CardName] at the bottom of its owner's deck." — self-referential standalone,
     * used when a card sends itself to the bottom of the deck as part of an ability chain.
     * Group: {@code name} — the card name (must equal source.name()).
     */
    static final Pattern PUT_SOURCE_TO_BOTTOM_OF_DECK = Pattern.compile(
        "(?i)Put\\s+(?<name>.+?)\\s+at\\s+the\\s+bottom\\s+of\\s+its\\s+owner's\\s+deck[.!]?"
    );
    /**
     * Matches "Put [CardName] on top of its owner's deck." — the deck-top twin of
     * {@link #PUT_SOURCE_TO_BOTTOM_OF_DECK}, used when a card sends <em>itself</em> back to the top
     * of the deck (Fiona 16-118C, whose "chosen by your opponent's Summons or abilities" trigger
     * lets its controller pull it out of the way).
     * Group: {@code name} — the card name (must equal source.name()).
     *
     * <p>Distinct from {@link #FOLLOWUP_PUT_TOP_OF_DECK}, which spells its subject "it" and belongs
     * to a preceding choose. The two cannot collide: this one names a card, that one a pronoun.
     */
    static final Pattern PUT_SOURCE_ON_TOP_OF_DECK = Pattern.compile(
        "(?i)Put\\s+(?<name>.+?)\\s+on\\s+top\\s+of\\s+its\\s+owner's\\s+deck[.!]?"
    );

    // =========================================================================================
    // Reveal and play from the top of the deck
    // =========================================================================================
    /**
     * Matches "Reveal the top N cards of your deck. Play 1 Card Name X of cost M or less among
     * them onto the field and return the other cards to the bottom of your deck in any order."
     * Groups: {@code n}, {@code cardname}, {@code maxcost}.
     */
    static final Pattern REVEAL_PLAY_NAMED_MAX_COST_REST_BOTTOM = Pattern.compile(
        "(?i)reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Play\\s+1\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+of\\s+cost\\s+(?<maxcost>\\d+)\\s+or\\s+less\\s+" +
        "among\\s+them\\s+onto\\s+(?:the\\s+)?field\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?"
    );
    /**
     * Matches "Reveal the top N cards of your deck. Play up to M Card Name X or Job Y of cost C
     * or less among them onto the field and return the other cards to the bottom of your deck in
     * any order." — combined Card-Name-or-Job filter with a cost ceiling (e.g. Moogle (XIV)).
     * Groups: {@code n}, {@code max}, {@code cardname}, {@code job}, {@code maxcost}.
     */
    static final Pattern REVEAL_PLAY_NAMED_OR_JOB_MAX_COST_REST_BOTTOM = Pattern.compile(
        "(?i)reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Play\\s+(?:up\\s+to\\s+)?(?<max>\\d+)\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+or\\s+Job\\s+(?<job>.+?)\\s+" +
        "of\\s+cost\\s+(?<maxcost>\\d+)\\s+or\\s+less\\s+" +
        "among\\s+them\\s+onto\\s+(?:the\\s+)?field\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?"
    );
    /**
     * Matches "Select 1 card type. Turn over one card at a time from the top of your deck until
     * a selected type is revealed. Add it to your hand. Then, shuffle the other cards revealed
     * and return them to the bottom of your deck."
     */
    static final Pattern FLIP_UNTIL_TYPE_TO_HAND_REST_SHUFFLE_BOTTOM = Pattern.compile(
        "(?i)select\\s+1\\s+card\\s+type[.]?\\s+" +
        "Turn\\s+over\\s+one\\s+card\\s+at\\s+a\\s+time\\s+from\\s+the\\s+top\\s+of\\s+your\\s+deck\\s+" +
        "until\\s+a\\s+selected\\s+type\\s+is\\s+revealed[.]?\\s+" +
        "Add\\s+it\\s+to\\s+your\\s+hand[.]?\\s+" +
        "Then,?\\s+shuffle\\s+the\\s+other\\s+cards?\\s+revealed\\s+and\\s+return\\s+them\\s+to\\s+the\\s+bottom\\s+of\\s+your\\s+deck[.!]?"
    );
    /**
     * Matches "Turn over one card at a time from the top of your deck until a [Element] or
     * [Element] card is revealed. Add it to your hand. Then, shuffle the other cards [revealed]
     * and return them to the bottom of your deck."
     *
     * <p>The element sibling of {@link #FLIP_UNTIL_TYPE_TO_HAND_REST_SHUFFLE_BOTTOM}, which the
     * two cannot share: that one is gated on a "Select 1 card type." prefix and matches on the
     * type the player named, while these state both elements in the text and prompt for nothing.
     *
     * <p>Twelve cards in the corpus, one per adjacent element pair — the FFCC cycle (11-020C
     * Lilty through 11-112C Clavat) and the FFIII job cycle (13-005C Black Mage through 13-092C
     * Sage). The two printings differ by one word: the 11- cards say "shuffle the other cards",
     * the 13- cards "shuffle the other cards revealed", hence the optional group.
     *
     * <p>Groups: {@code elem1}, {@code elem2} — the two accepted elements.
     */
    static final Pattern FLIP_UNTIL_ELEMENT_TO_HAND_REST_SHUFFLE_BOTTOM = Pattern.compile(
        "(?i)Turn\\s+over\\s+one\\s+card\\s+at\\s+a\\s+time\\s+from\\s+the\\s+top\\s+of\\s+your\\s+deck\\s+" +
        "until\\s+an?\\s+(?<elem1>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+or\\s+" +
        "(?<elem2>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+card\\s+is\\s+revealed[.!]?\\s+" +
        "Add\\s+it\\s+to\\s+your\\s+hand[.!]?\\s+" +
        "Then,?\\s+shuffle\\s+the\\s+other\\s+cards?(?:\\s+revealed)?\\s+and\\s+" +
        "return\\s+them\\s+to\\s+the\\s+bottom\\s+of\\s+your\\s+deck[.!]?"
    );
    /**
     * Matches "Shuffle your deck. Then, reveal the top N cards of your deck.
     * Play 1 Card Name [name] among them onto the field and return the other cards to the
     * bottom of your deck in any order." — used as the 'when you do so' followup on self-bounce
     * abilities that search for a named card.
     * Groups: {@code n} (reveal count), {@code cardname} (card name to play).
     */
    static final Pattern SHUFFLE_THEN_REVEAL_PLAY_NAMED_REST_BOTTOM = Pattern.compile(
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
    static final Pattern REVEAL_PLAY_TYPE_ONTO_FIELD_REST_BOTTOM = Pattern.compile(
        "(?i)reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Play\\s+(?:up\\s+to\\s+)?(?<max>\\d+)\\s+" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?<type>Forward|Backup|Monster|Character)s?\\s+" +
        "among\\s+them\\s+onto\\s+(?:the\\s+)?field\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?$"
    );
    /** Matches "reveal 1 &lt;Element&gt; card from your hand. If you do so, draw N card(s)." */
    static final Pattern REVEAL_ELEMENT_CARD_FROM_HAND_IF_SO_DRAW = Pattern.compile(
        "(?i)^\\s*reveal\\s+1\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+card\\s+from\\s+your\\s+hand[.]?\\s+" +
        "If\\s+you\\s+do\\s+so,?\\s+draw\\s+(?<draw>\\d+)\\s+cards?[.]?\\s*$"
    );
    /**
     * "Reveal the top N cards of your deck. Play up to M [Element] Type of cost C or less among
     * them onto the field, and &lt;what happens to the rest&gt;."
     *
     * <p>The remainder goes to the bottom of the deck on every card in this family except 26-053L
     * Bartz, which adds it to hand instead — a strictly better outcome, so the two cannot share a
     * destination. Group {@code resthand} is non-null for the hand form and null for the rest.
     */
    static final Pattern REVEAL_PLAY_ELEMENT_TYPE_COST_ONTO_FIELD_REST_BOTTOM = Pattern.compile(
        "(?i)^\\s*reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Play\\s+(?:up\\s+to\\s+)?(?<max>\\d+)\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<type>Forward|Backup|Monster|Character)s?\\s+of\\s+cost\\s+(?<cost>\\d+|X)\\s+or\\s+less\\s+" +
        "among\\s+them\\s+onto\\s+(?:the\\s+)?field[,.]?\\s+" +
        "(?:" +
            "(?:Then,?\\s+shuffle\\s+the\\s+other\\s+cards?\\s+revealed\\s+and\\s+return\\s+them|" +
            "and\\s+return\\s+the\\s+other\\s+cards?)\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
            "(?:\\s+in\\s+any\\s+order)?" +
        "|" +
            "(?<resthand>and\\s+add\\s+the\\s+other\\s+cards?\\s+to\\s+your\\s+hand)" +
        "|" +
            // 15-130H Nox Suzaku. "the rest of the cards" rather than "the other cards" — the
            // only wording this disposal is printed with, so it is not shared with the two above.
            "(?<restbz>and\\s+put\\s+the\\s+rest\\s+of\\s+the\\s+cards?\\s+into\\s+the\\s+Break\\s+Zone)" +
        ")[.!]?\\s*$"
    );
    /**
     * "Reveal the top N cards of your deck. Play 1 [Type] of cost C or less [other than
     * Multi-Element] or 1 Card Name X of cost D or less among them onto the field and return the
     * other cards to the bottom of your deck in any order." (Syldra 29-101H.)
     *
     * <p>Two alternatives, each carrying its own cost ceiling — which is what keeps it apart from
     * {@link #REVEAL_PLAY_NAMED_OR_JOB_MAX_COST_REST_BOTTOM}, where the Card Name and the Job share
     * one. Exactly one card is played, whichever branch it comes from.
     *
     * <p>Both are fully anchored and both consume the whole sentence, so neither this nor
     * {@link #REVEAL_PLAY_ELEMENT_TYPE_COST_ONTO_FIELD_REST_BOTTOM} can claim the other's text:
     * that one ends at "among them", where this one is still reading a second alternative.
     */
    static final Pattern REVEAL_PLAY_TYPE_COST_OR_NAMED_COST_REST_BOTTOM = Pattern.compile(
        "(?i)^\\s*reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Play\\s+1\\s+(?<type>Forward|Backup|Monster|Character)s?\\s+" +
        "of\\s+cost\\s+(?<typecost>\\d+)\\s+or\\s+less" +
        "(?:\\s+other\\s+than\\s+(?<except>Multi-Element))?\\s+" +
        "or\\s+1\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+of\\s+cost\\s+(?<namecost>\\d+)\\s+or\\s+less\\s+" +
        "among\\s+them\\s+onto\\s+(?:the\\s+)?field\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );

    // =========================================================================================
    // Put on the top or bottom of the deck
    // =========================================================================================
    /** Matches "Put it at the top or bottom of its owner's deck." — player chooses placement. Also handles "Your opponent puts it…" */
    static final Pattern FOLLOWUP_PUT_TOP_OR_BOTTOM_OF_DECK = Pattern.compile(
        "(?i)(?:Your\\s+opponent\\s+puts?\\s+it|Put\\s+it)\\s+at\\s+the\\s+top\\s+or\\s+bottom\\s+of\\s+its\\s+owner's\\s+deck\\.?"
    );
    /** Matches "Put it at the bottom of its owner's deck." Also handles "Your opponent puts it…" */
    static final Pattern FOLLOWUP_PUT_BOTTOM_OF_DECK = Pattern.compile(
        "(?i)(?:Your\\s+opponent\\s+puts?\\s+it|Put\\s+it)\\s+at\\s+the\\s+bottom\\s+of\\s+its\\s+owner's\\s+deck\\.?"
    );
    /** Matches "Put it on top of its owner's deck." Also handles "Your opponent puts it…" */
    static final Pattern FOLLOWUP_PUT_TOP_OF_DECK = Pattern.compile(
        "(?i)(?:Your\\s+opponent\\s+puts?\\s+it|Put\\s+it)\\s+on\\s+top\\s+of\\s+its\\s+owner's\\s+deck\\.?"
    );
    /**
     * Matches "Put it/them on (the) top of your deck[ in any order]." — the followup of a choose
     * that reaches into a Break Zone, where "your deck" rather than "its owner's deck" is the
     * destination (26-077R Noctis, 3-118H Odin, 26-067H).
     *
     * <p>Distinct from {@link #FOLLOWUP_PUT_TOP_OF_DECK}, which returns a card already on the
     * field to whichever player owns it.  Group {@code may} is present for the optional form
     * ("You may put it on top of your deck").
     */
    static final Pattern FOLLOWUP_PUT_TOP_OF_YOUR_DECK = Pattern.compile(
        "(?i)(?<may>You\\s+may\\s+)?Put\\s+(?:it|them)\\s+on\\s+(?:the\\s+)?top\\s+of\\s+your\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?\\.?"
    );
    /**
     * Matches "Put/Place it/them at the bottom of your deck[ in any order]." — the bottom-of-deck
     * twin of {@link #FOLLOWUP_PUT_TOP_OF_YOUR_DECK}, and like it the followup of a choose that
     * reaches into a Break Zone rather than onto the field (11-123R Yunalesca, 24-094C Corsair).
     * Both "put" and "place" appear in the corpus for this destination.
     *
     * <p>Distinct from {@link #FOLLOWUP_PUT_BOTTOM_OF_DECK}, which sends a card already on the
     * field back to whichever player owns it.  Group {@code may} is present for the optional form.
     *
     * <p>The trailing {@code (?!\s+and\b)} keeps the compound wording out — see
     * {@link #FOLLOWUP_PUT_BOTTOM_OF_YOUR_DECK_AND_THEN}, which claims that instead.  Without the
     * lookahead this pattern would match under {@code find()} and silently drop the second effect.
     */
    static final Pattern FOLLOWUP_PUT_BOTTOM_OF_YOUR_DECK = Pattern.compile(
        "(?i)(?<may>You\\s+may\\s+)?(?:Put|Place)\\s+(?:it|them)\\s+(?:at|on)\\s+the\\s+bottom\\s+of\\s+your\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?\\.?(?!\\s+and\\b)"
    );
    /**
     * Matches "Put/Place it/them at the bottom of your deck and [effect]." — 15-065C Scholar's
     * "Place it at the bottom of your deck <em>and put the top card of your deck into the Break
     * Zone</em>", where a second effect rides in the same clause rather than in its own sentence,
     * so the resolver's ". " sentence split never separates the two.
     *
     * <p>{@code also} is handed to {@link ActionResolver#parse}, so this only takes effect when
     * that trailing effect has a parser of its own; anything else falls through to the
     * unimplemented-followup warning rather than being half-applied.
     *
     * <p>Order against {@link #FOLLOWUP_PUT_BOTTOM_OF_YOUR_DECK} is not load-bearing — that
     * pattern's lookahead already declines this text — but the two are kept adjacent at every
     * call site so the pair reads as one decision.
     */
    static final Pattern FOLLOWUP_PUT_BOTTOM_OF_YOUR_DECK_AND_THEN = Pattern.compile(
        "(?i)(?:Put|Place)\\s+(?:it|them)\\s+(?:at|on)\\s+the\\s+bottom\\s+of\\s+your\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?\\s+and\\s+(?<also>\\S.*?)[.!]?\\s*$"
    );
    /**
     * Matches "If its power is equal to or less/more than [SourceName]'s power, put it on top of
     * its owner's deck." — Wakka-style conditional bounce whose threshold is the source card's power.
     * Groups: {@code sourcename} — name of the card providing the power threshold;
     *         {@code cmp} — "less" or "more".
     */
    static final Pattern FOLLOWUP_IF_POWER_CMP_SOURCE_PUT_ON_DECK_TOP = Pattern.compile(
        "(?i)If\\s+its?\\s+power\\s+is\\s+equal\\s+to\\s+or\\s+(?<cmp>less|more)\\s+than\\s+" +
        "(?<sourcename>.+?)'s\\s+power[,.]?\\s+put\\s+it\\s+on\\s+top\\s+of\\s+its\\s+owner's\\s+deck[.!]?"
    );
    /**
     * Matches "Put it under the top [N] card(s) of its owner's/your deck."
     * Group {@code numword} — present only when a number word precedes "cards" (currently only "four").
     */
    static final Pattern FOLLOWUP_PUT_UNDER_TOP_OF_DECK = Pattern.compile(
        "(?i)Put\\s+it\\s+under\\s+the\\s+top\\s+(?<numword>four\\s+)?cards?\\s+of\\s+(?:its\\s+owner's|your)\\s+deck\\.?"
    );

    // =========================================================================================
    // Cannot attack / must attack; quoted grants
    // =========================================================================================
    /**
     * Matches "it/they cannot attack this turn", and the quoted-grant wording that says the
     * same thing — 17-078R The Night Dancer's {\\code It gains "This Forward cannot attack."
     * until the end of the turn.}
     *
     * <p>The quoted arm requires the period and closing quote immediately after "attack", so
     * it cannot claim the "cannot attack or block" grant handled by
     * {\\link #FOLLOWUP_CANNOT_ATTACK_OR_BLOCK}. That matters because this pattern is checked
     * first and both scan with {\\code find()}.
     */
    static final Pattern FOLLOWUP_CANNOT_ATTACK = Pattern.compile(
        "(?i)(?:it|they)\\s+(?:" +
            "cannot\\s+attack\\s+this\\s+turn" +
            "|gains\\s+\"This\\s+(?:Forward|Character|Backup|Monster)\\s+cannot\\s+attack\\.\"\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn" +
        ")\\.?"
    );
    /**
     * Gestahlian Empire Cid 11-026H's followup: "Select 1 Counter placed on it, and place 1
     * additional Counter of the same type as the selected Counter on that Monster."
     *
     * <p>Which counter is duplicated is a decision, not something the text names — the card may be
     * carrying several kinds — so this pattern captures nothing and the primitive it dispatches to
     * does the asking.
     */
    static final Pattern FOLLOWUP_SELECT_COUNTER_AND_ADD_SAME_TYPE = Pattern.compile(
        "(?i)^Select\\s+1\\s+Counter\\s+placed\\s+on\\s+it,?\\s+and\\s+place\\s+1\\s+additional\\s+" +
        "Counter\\s+of\\s+the\\s+same\\s+type\\s+as\\s+the\\s+selected\\s+Counter\\s+on\\s+that\\s+" +
        "(?:Monster|Forward|Backup|Character)[.!]?$"
    );
    /** Matches "it must attack this turn if possible". */
    static final Pattern FOLLOWUP_MUST_ATTACK = Pattern.compile(
        "(?i)it\\s+must\\s+attack\\s+this\\s+turn\\s+if\\s+possible\\.?"
    );
    /**
     * Matches Azul 23-077H's followup: "Until the end of the turn, it gains "[quoted]" and [Self]
     * gains +N power." — one clause hands the chosen Forward a quoted ability, the next pays the
     * activating card for it.
     *
     * <p>Kept as one pattern rather than a grant plus a secondary because the sentence never
     * breaks: the only ". " in it sits inside the quotation, so the choose chain's quote-aware
     * split leaves the whole thing as the primary followup. Groups: {@code quoted} — the granted
     * clause; {@code self} — the card being paid, checked against the source's own name by the
     * caller; {@code amount} — the power it gains.
     */
    static final Pattern FOLLOWUP_GAINS_QUOTED_EOT_AND_SELF_POWER_BOOST = Pattern.compile(
        "(?i)^Until\\s+the\\s+end\\s+of\\s+the\\s+turn,\\s+it\\s+gains\\s+" +
        "\"(?<quoted>[^\"]+)\"\\s+and\\s+(?<self>[^\"]+?)\\s+gains\\s+" +
        "\\+(?<amount>\\d+)\\s+power[.!]?$");
    /**
     * Tulien 21-072H's followup: "Until the end of the turn, it gains "[quoted]" and "[quoted]"."
     * — two compulsions handed to the chosen Forward in one sentence.
     *
     * <p>Shaped like {@link #FOLLOWUP_GAINS_QUOTED_EOT_AND_SELF_POWER_BOOST} above and split from
     * it for the same reason the sibling is split from the plain grants: what follows the "and"
     * decides which primitives run, and neither pattern can read the other's tail. The sentence
     * never breaks, because its only ". " sequences sit inside the quotations.
     *
     * <p>Groups {@code first} and {@code second} are the two quoted clauses, each checked by the
     * caller before either is applied.
     */
    static final Pattern FOLLOWUP_GAINS_TWO_QUOTED_EOT = Pattern.compile(
        "(?i)^Until\\s+the\\s+end\\s+of\\s+the\\s+turn,\\s+it\\s+gains\\s+" +
        "\"(?<first>[^\"]+)\"\\s+and\\s+\"(?<second>[^\"]+)\"[.!]?$");
    /**
     * The self-reference a granted clause uses for whatever received it — "This Forward", "This
     * Character", or a bare "It". Used to confirm that a quoted grant is talking about its new
     * carrier rather than naming some other card.
     */
    static final Pattern GRANTED_CLAUSE_SELF_SUBJECT = Pattern.compile(
        "(?i)^(?:this\\s+(?:Forward|Character|Backup|Monster)|it)$");
    /**
     * Matches "it/they cannot attack or block this turn", and the quoted-grant wording —
     * 17-044R Onion Knight and 17-096H Man in Black's {\\code It gains "This Forward cannot
     * attack or block." until the end of the turn.}
     *
     * <p>The quoted arm pins the duration to "the turn", so 20-072C Gigas's "until the end of
     * your opponent's turn" falls through to
     * {\\link #FOLLOWUP_CANNOT_ATTACK_OR_BLOCK_PERSISTENT} as it must — this pattern is
     * checked first and would otherwise shorten that grant to one turn.
     */
    static final Pattern FOLLOWUP_CANNOT_ATTACK_OR_BLOCK = Pattern.compile(
        "(?i)(?:it|they)\\s+(?:" +
            "cannot\\s+attack\\s+or\\s+block\\s+this\\s+turn" +
            // Either delimiter: 28-064H Cactuar quotes the granted sentence with ', every other
            // printing of it with ". Nothing else distinguishes them.
            "|gains\\s+[\"']This\\s+(?:Forward|Character|Backup|Monster)\\s+cannot\\s+attack\\s+or\\s+block\\.[\"']\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn" +
        ")\\.?"
    );
    /**
     * Matches "During this turn, it cannot attack or block, and if it is dealt damage, the damage
     * becomes 0 instead." — 5-081C Cockatrice, the corpus's only printing that pairs the combat
     * lock with a damage shield.
     *
     * <p>Must be checked ahead of both {@link #FOLLOWUP_CANNOT_ATTACK_OR_BLOCK} and
     * {@link #FOLLOWUP_NEGATE_DAMAGE}, for the reason its Kitone sibling below gives: each of them
     * finds its own half inside this sentence and would drop the other.
     */
    static final Pattern FOLLOWUP_CANNOT_ATTACK_OR_BLOCK_AND_NEGATE_DAMAGE = Pattern.compile(
        "(?i)^(?:During\\s+this\\s+turn[,.]?\\s+)?(?:it|they)\\s+cannot\\s+attack\\s+or\\s+block[,.]?\\s+" +
        "and\\s+if\\s+(?:it|they)\\s+(?:is|are)\\s+dealt\\s+damage,\\s+the\\s+damage\\s+becomes\\s+0\\s+instead[.!]?$"
    );
    /**
     * Matches "Dull it[ and Freeze it]. During this turn, if it &lt;is dealt damage | deals damage
     * to a Forward or a player&gt;, the damage becomes 0 instead." — 9-068H Mist Dragon's second
     * option, which shields what it dulls, and 23-024R Shiva, which blanks what it freezes.
     *
     * <p>Two sentences read as one, for the reason the Cockatrice pattern above gives: the dull
     * branches scan {@code primaryFollowup} with {@code find()} and would claim the first sentence
     * while the second fell to the secondary parser, where "it" names nothing.
     *
     * <p>Exactly one of {@code incoming} and {@code outgoing} is set, and they are opposite
     * directions of the same replacement — one shields the chosen card, the other disarms it.
     */
    static final Pattern FOLLOWUP_DULL_THEN_DAMAGE_SHIELD = Pattern.compile(
        "(?i)^Dulls?\\s+(?:it|them)(?:\\s+and\\s+Freeze\\s+(?:it|them))?[.!]\\s+" +
        "During\\s+this\\s+turn,\\s+if\\s+(?:it|they)\\s+" +
        "(?:(?<incoming>(?:is|are)\\s+dealt\\s+damage)" +
        "|(?<outgoing>deals?\\s+damage\\s+to\\s+a\\s+Forward\\s+or\\s+a\\s+player))," +
        "\\s+the\\s+damage\\s+becomes\\s+0\\s+instead[.!]?\\s*$"
    );
    /**
     * Matches the standalone outgoing-damage replacement "[During this turn,] if it deals
     * &lt;scope&gt;[ this turn], the damage becomes 0 instead." — the sentence on its own, where
     * {@link #FOLLOWUP_DULL_THEN_DAMAGE_SHIELD} wants a dull in front of it.
     *
     * <p>Two scopes, and they are not the same effect:
     * <ul>
     *   <li>{@code any} — "damage to a Forward or a player" (23-024R Shiva) and "damage to you or
     *       a Forward" (24-056C Cu Sith). Two spellings of every way the card can deal damage;
     *       "you" is the caster, and the only player a chosen Forward deals damage to.</li>
     *   <li>{@code nonbattle} — "damage other than battle damage to a Forward" (17-027R Shiva),
     *       which leaves combat damage and damage to a player alone.</li>
     * </ul>
     */
    static final Pattern FOLLOWUP_OUTGOING_DMG_ZERO_THIS_TURN = Pattern.compile(
        "(?i)^(?:During\\s+this\\s+turn,\\s+)?If\\s+(?:it|they)\\s+deals?\\s+" +
        "(?:(?<nonbattle>damage\\s+other\\s+than\\s+battle\\s+damage\\s+to\\s+a\\s+Forward)" +
        "|(?<any>damage\\s+to\\s+(?:a\\s+Forward\\s+or\\s+a\\s+player|you\\s+or\\s+a\\s+Forward)))" +
        "(?:\\s+this\\s+turn)?,\\s+the\\s+damage\\s+becomes\\s+0\\s+instead[.!]?\\s*$"
    );
    /**
     * Matches "Return it to its owner's hand. Until the end of the next turn, your opponent cannot
     * cast any copies of it." — 19-101R Leviathan, the corpus's only printing of a cast ban that
     * outlives the turn it is set in.
     *
     * <p>Read as one clause because "it" in the second sentence names the card the first has
     * already put in hand: split, the ban is left to the secondary parser with no referent, and
     * the bounce runs alone.
     */
    static final Pattern FOLLOWUP_RETURN_TO_HAND_THEN_BAN_COPIES = Pattern.compile(
        "(?i)^Return\\s+(?:it|them)\\s+to\\s+(?:its|their)\\s+owner'?s?'?\\s+hands?[.!]\\s+" +
        "Until\\s+the\\s+end\\s+of\\s+the\\s+next\\s+turn,\\s+your\\s+opponent\\s+cannot\\s+cast\\s+" +
        "any\\s+copies\\s+of\\s+(?:it|them)[.!]?\\s*$"
    );
    /**
     * Matches "Halve its power until the end of the turn (round down to the nearest 1000)." —
     * 5-133H Bismarck's third option, and the corpus's only printing of "halve".
     *
     * <p>The rounding note is part of the arithmetic, not a reminder, so it is required rather
     * than optional: a printing that halved without saying which way to round would be a
     * different effect and should not quietly borrow this one's answer.
     */
    static final Pattern FOLLOWUP_HALVE_POWER = Pattern.compile(
        "(?i)^Halve\\s+(?:its|their)\\s+power\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s+" +
        "\\(\\s*round\\s+down\\s+to\\s+the\\s+nearest\\s+1000\\s*\\)[.!]?\\s*$"
    );
    /**
     * Matches "During this turn, it cannot attack or block, and it cannot use action abilities."
     * — 14-064R Kitone.  Three restrictions in one sentence, and the only wording in the corpus
     * that shuts a single chosen Character out of action abilities (14-045H Sin's lock is a
     * field-wide auto-ability, not a chosen target).
     *
     * <p>Must be checked ahead of {@link #FOLLOWUP_CANNOT_ATTACK_OR_BLOCK}: that pattern currently
     * requires a trailing "this turn" and so does not claim this text, but both scan with
     * {@code find()}, and any future widening of it would take the first clause here and silently
     * drop the action-ability half.
     */
    static final Pattern FOLLOWUP_CANNOT_ATTACK_OR_BLOCK_AND_NO_ACTION_ABILITIES = Pattern.compile(
        "(?i)(?:During\\s+this\\s+turn[,.]?\\s+)?(?:it|they)\\s+cannot\\s+attack\\s+or\\s+block[,.]?\\s+" +
        "and\\s+(?:it|they)\\s+cannot\\s+use\\s+action\\s+abilities[.!]?"
    );
    /**
     * Matches "it cannot attack or block until the end of your opponent's turn" or
     * "…until the end of the next turn".
     *
     * <p>The quoted arm takes "gain" as well as "gains", because the plural subject takes the
     * plural verb: 20-072C Gigas (FFCC) chooses one Forward and says "It gains …", 29-070R Dragon
     * Zombie chooses up to three and says "They gain …". The verb is the only difference between
     * the two texts.
     *
     * <p>Only the persistent member of the family takes "gain" — the one-turn
     * {@link #FOLLOWUP_CANNOT_ATTACK_OR_BLOCK} deliberately does not. Its one "They gain" text
     * (21-092R Man in Black) puts the grant behind "If your opponent doesn't pay 《3》", which no
     * followup branch reads; widening that pattern would claim the grant off the tail of the
     * sentence under {@code find()} and apply it whether or not the toll was paid.
     */
    static final Pattern FOLLOWUP_CANNOT_ATTACK_OR_BLOCK_PERSISTENT = Pattern.compile(
        "(?i)(?:it|they)\\s+(?:" +
            "cannot\\s+attack\\s+or\\s+block\\s+until\\s+the\\s+end\\s+of\\s+" +
                "(?:your\\s+opponent's|the\\s+next)\\s+turn" +
            "|gains?\\s+\"This\\s+(?:Forward|Character|Backup|Monster)\\s+cannot\\s+attack\\s+or\\s+block\\.\"\\s+until\\s+the\\s+end\\s+of\\s+" +
                "(?:your\\s+opponent's|the\\s+next)\\s+turn" +
        ")\\.?"
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
     * "If your opponent doesn't control [any] Forwards, [CardName] cannot attack."
     * {@code subject} — the card that cannot attack.
     */
    static final Pattern IF_OPP_NO_FORWARDS_CANNOT_ATTACK = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+(?:doesn'?t|does\\s+not)\\s+control\\s+(?:any\\s+)?Forwards?," +
        "\\s+(?<subject>.+?)\\s+cannot\\s+attack[.!]?\\s*$"
    );

    // =========================================================================================
    // Turn-phase and delayed triggers
    // =========================================================================================
    /**
     * Matches "At the end of this turn, if you control &lt;cardName&gt;, deal it N damage."
     * Used as a Choose followup that queues conditional damage to fire at the end phase.
     * <ul>
     *   <li>Group {@code cardName} — the card the ability user must control</li>
     *   <li>Group {@code damage}   — fixed damage amount</li>
     * </ul>
     */
    static final Pattern FOLLOWUP_END_OF_TURN_COND_DAMAGE = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+this\\s+turn,\\s+if\\s+you\\s+control\\s+(?<cardName>.+?),\\s+deal\\s+it\\s+(?<damage>\\d+)\\s+damage\\.?"
    );
    /** Matches "At the end of this turn, &lt;rest&gt;" — any delayed standalone effect. */
    static final Pattern AT_END_OF_TURN_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+this\\s+turn,\\s+(?<rest>.+)"
    );
    /** Matches "At the end of the turn, break [CardName]." — a self-break rider on "becomes a Forward" abilities. */
    static final Pattern AT_END_OF_TURN_BREAK_SOURCE = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+(?:the|this)\\s+turn,\\s+break\\s+.+?[.!]?"
    );
    /**
     * Shared boundary lookahead for the "global" (card-less) phase-trigger patterns' {@code inner}
     * capture group below — stops before the next "[[br]]" marker, the next "When ..." trigger
     * sentence, or a cost-token action-ability header (or end of string). Mirrors the boundary
     * already used by {@code CardData.AUTO_ABILITY_PATTERN} and {@code CardData.WARP_COUNTER_PATTERN}
     * so a multi-ability card text (e.g. "At the beginning of your Main Phase 1, X.[[br]]   When Y,
     * Z.") doesn't have its first inner effect swallow the second ability's text too.
     */
    static final String GLOBAL_TRIGGER_INNER_BOUNDARY =
        "(?=\\s*\\[\\[br\\]\\]|\\s*When\\s+[^,]+?\\s+(?:forms?\\s+a\\s+party\\s+and\\s+attacks?" +
        "|attacks?|blocks?|enters?|leaves?|is\\s+(?:put|removed|blocked)|deals?|uses?|becomes?)" +
        "|\\s*(?:《[^》]+》)+\\s*:|\\s*$)";
    /**
     * Matches "At the end of [each of your turns | your turn], &lt;inner&gt;" — both wordings name the
     * same trigger, the controller's own end phase. The shorter form appears on Libroarian 8-084R,
     * Death Machine 8-102R and Rem 9-059R, and inside Vayne 9-022L's granted ability (which callers
     * must skip, since text quoted on a card is not that card's own ability).
     */
    static final Pattern AT_END_OF_EACH_TURN_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+(?:each\\s+of\\s+your\\s+turns?|your\\s+turn)\\s*,\\s+" +
        "(?<inner>.+?)\\s*" + GLOBAL_TRIGGER_INNER_BOUNDARY,
        Pattern.DOTALL
    );
    /** Matches "At the end of each player's turn, &lt;inner&gt;" — fires at both players' end phase. */
    static final Pattern AT_END_OF_EACH_PLAYERS_TURN_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+each\\s+player'?s\\s+turn,\\s+" +
        "(?<inner>.+?)\\s*" + GLOBAL_TRIGGER_INNER_BOUNDARY,
        Pattern.DOTALL
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
    static final Pattern IF_RFP_COUNT_INNER = Pattern.compile(
        "(?i)^If\\s+there\\s+are\\s+(?<count>\\d+)\\s+or\\s+more\\s+cards?\\s+removed\\s+from\\s+the\\s+game,\\s+(?<inner>.+)",
        Pattern.DOTALL
    );
    /**
     * The owner-scoped counterpart of {@link #IF_RFP_COUNT_INNER}: "If N or more of <b>your</b>
     * cards have been removed from the game, &lt;effect&gt;" and its relatives.
     *
     * <p>Disjoint from that pattern, which needs a literal "there are" and counts both players'
     * RFP zones. This one counts only the cards the ability user owns, and covers the three
     * printed wordings:
     * <ul>
     *   <li>"If 1 or more of your cards have been removed from the game, …" (20-107H Urianger)</li>
     *   <li>"If you have 2 or more Job Remnant you own removed from the game, …" (28-022L)</li>
     *   <li>"If any Job Eikon you own are removed from the game, …" (29-053R)</li>
     * </ul>
     * Groups: {@code count} — the threshold, absent when {@code any} is set (which means 1);
     * {@code job} — an optional Job restriction; {@code inner} — the gated effect.
     */
    static final Pattern IF_SELF_RFG_COUNT_INNER = Pattern.compile(
        "(?i)^If\\s+(?:you\\s+have\\s+)?" +
        "(?:(?<count>\\d+)\\s+or\\s+more|(?<any>any))\\s+" +
        "(?:of\\s+your\\s+)?" +
        "(?:Job\\s+(?<job>[^,]+?)|cards?)\\s+" +
        "(?:you\\s+own\\s+)?" +
        "(?:have\\s+been\\s+|are\\s+|is\\s+)?removed\\s+from\\s+the\\s+game,\\s+(?<inner>.+)",
        Pattern.DOTALL
    );
    /**
     * "At the beginning of your Main Phase 1[ each turn etc.], &lt;effect&gt;"
     * Group {@code inner} captures the effect text after the trigger comma.  Modeled on
     * {@link #AT_END_OF_EACH_TURN_PATTERN} — the inner effect is dispatched through
     * the full {@link #parse} chain so any supported effect can follow the trigger.
     */
    static final Pattern AT_BEGINNING_OF_MAIN_PHASE_1_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+beginning\\s+of\\s+your\\s+Main\\s+Phase\\s+1\\b[^,]*,\\s+" +
        "(?<inner>.+?)\\s*" + GLOBAL_TRIGGER_INNER_BOUNDARY,
        Pattern.DOTALL
    );
    /** Same as {@link #AT_BEGINNING_OF_MAIN_PHASE_1_PATTERN} but for Main Phase 2. */
    static final Pattern AT_BEGINNING_OF_MAIN_PHASE_2_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+beginning\\s+of\\s+your\\s+Main\\s+Phase\\s+2\\b[^,]*,\\s+" +
        "(?<inner>.+?)\\s*" + GLOBAL_TRIGGER_INNER_BOUNDARY,
        Pattern.DOTALL
    );
    /**
     * "At the beginning of Main Phase [1|2] during each of your turns, &lt;effect&gt;" — the other
     * spelling of {@link #AT_BEGINNING_OF_MAIN_PHASE_1_PATTERN} and its Main Phase 2 twin, and by
     * far the commoner one: ten printings say it this way against five for "your Main Phase 1".
     * Groups: {@code phase} — 1 or 2; {@code inner} — the effect after the trigger comma.
     *
     * <p>One pattern for both phases because the two wordings differ only in that digit, where the
     * "your Main Phase N" pair are separate constants only because they were written separately.
     *
     * <p>Deliberately not folded into those by making "your " optional: this wording is also the
     * tail of "When [X] enters the field or at the beginning of Main Phase 1 during each of your
     * turns" ({@link #ETF_OR_PHASE_TRIGGER_PATTERN}), whose pass registers both triggers and strips
     * its region first. A pass reading this constant must therefore run after that one — which the
     * Main Phase passes already do.
     */
    static final Pattern AT_BEGINNING_OF_MAIN_PHASE_EACH_YOUR_TURN_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+beginning\\s+of\\s+Main\\s+Phase\\s+(?<phase>[12])\\s+" +
        "during\\s+each\\s+of\\s+your\\s+turns,\\s+" +
        "(?<inner>.+?)\\s*" + GLOBAL_TRIGGER_INNER_BOUNDARY,
        Pattern.DOTALL
    );
    /**
     * "At the beginning of Main Phase 1 during each player's turn, &lt;effect&gt;" — Ardyn 28-002R,
     * the both-turns twin of {@link #AT_BEGINNING_OF_MAIN_PHASE_EACH_YOUR_TURN_PATTERN} and the
     * other spelling of {@link #AT_BEGINNING_OF_MAIN_PHASE_1_EACH_TURN_PATTERN}'s "Each turn, at
     * the beginning of Main Phase 1".
     *
     * <p>Main Phase 1 only: no printing says it of Main Phase 2, and the trigger key it feeds
     * exists for the first Main Phase alone. Group {@code inner} — the effect after the comma.
     */
    static final Pattern AT_BEGINNING_OF_MAIN_PHASE_1_EACH_PLAYERS_TURN_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+beginning\\s+of\\s+Main\\s+Phase\\s+1\\s+" +
        "during\\s+each\\s+player'?s\\s+turns?,\\s+" +
        "(?<inner>.+?)\\s*" + GLOBAL_TRIGGER_INNER_BOUNDARY,
        Pattern.DOTALL
    );
    /**
     * "Each turn, at the beginning of Main Phase 1, [inner]" — fires at BOTH players' Main Phase 1 starts.
     * Group {@code inner} — the conditional effect to evaluate.
     */
    static final Pattern AT_BEGINNING_OF_MAIN_PHASE_1_EACH_TURN_PATTERN = Pattern.compile(
        "(?i)Each\\s+turn,?\\s+at\\s+the\\s+beginning\\s+of\\s+Main\\s+Phase\\s+1,\\s+" +
        "(?<inner>.+?)\\s*" + GLOBAL_TRIGGER_INNER_BOUNDARY,
        Pattern.DOTALL
    );
    /**
     * "At the beginning of your opponent's Main Phase 1, [inner]" — fires at the start of the
     * opponent's Main Phase 1 (i.e., when the card controller's opponent begins their Main Phase 1).
     * Group {@code inner} — the effect to evaluate.
     */
    static final Pattern AT_BEGINNING_OF_OPP_MAIN_PHASE_1_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+beginning\\s+of\\s+your\\s+opponent'?s\\s+Main\\s+Phase\\s+1\\b[^,]*,\\s+" +
        "(?<inner>.+?)\\s*" + GLOBAL_TRIGGER_INNER_BOUNDARY,
        Pattern.DOTALL
    );
    /**
     * "At the end of your opponent's turn, [inner]" — fires at the end of the controlling player's
     * opponent's turn (i.e., whenever the opponent ends their turn).
     * Group {@code inner} — the effect to fire.
     */
    /**
     * Matches a leading "At the end of your opponent's turn, [effect]" on an effect that is being
     * resolved <em>now</em> by some other trigger — 20-057L The Goddess's "When The Goddess enters
     * the field, at the end of your opponent's turn, break …".  Group {@code inner} is the effect
     * to defer.
     *
     * <p>Distinct from {@link #AT_END_OF_OPP_TURN_PATTERN}, which describes an ability whose
     * <em>own</em> trigger is the end of the opponent's turn and whose caller therefore already
     * runs it at the right moment.  This one is a one-shot delay set up by a different trigger, so
     * the inner effect has to be queued rather than run.
     */
    static final Pattern AT_END_OF_OPP_TURN_DELAY_PREFIX = Pattern.compile(
        "(?i)^\\s*(?:and\\s+)?at\\s+the\\s+end\\s+of\\s+your\\s+opponent'?s\\s+turn,\\s*(?<inner>\\S.*)$",
        Pattern.DOTALL
    );
    /**
     * Matches an action whose only target is a bare pronoun — "break it", "return them".  Such a
     * clause cannot stand alone: whatever it refers to was named by the sentence before it.
     * Mirrors the identically-shaped guard in {@code CardData}, which decides whether a delayed
     * clause may be lifted into an ability of its own.
     */
    static final Pattern DELAYED_BARE_PRONOUN_ACTION = Pattern.compile(
        "(?i)^\\w+\\s+(?:it|them)\\b"
    );
    /**
     * Matches "Place N [Name] Counter(s) on all [the] Forwards [opponent controls|you control]."
     * (20-057L The Goddess.)  The singular "on it" form is a choose followup and lives in
     * {@link #FOLLOWUP_PLACE_COUNTER_ON_IT}.
     */
    static final Pattern PLACE_COUNTER_ON_ALL_FORWARDS = Pattern.compile(
        "(?i)^\\s*place\\s+(?<count>\\d+)\\s+(?<name>[A-Za-z][A-Za-z ]*?)\\s+Counters?\\s+on\\s+" +
        "all\\s+(?:the\\s+)?Forwards?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?[.!]?\\s*$"
    );
    static final Pattern AT_END_OF_OPP_TURN_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+(?:each\\s+of\\s+)?your\\s+opponent'?s\\s+turns?,\\s+" +
        "(?<inner>.+?)\\s*" + GLOBAL_TRIGGER_INNER_BOUNDARY,
        Pattern.DOTALL
    );

    // =========================================================================================
    // Naming an element, job or category
    // =========================================================================================
    /**
     * "Select 1 Element. &lt;CardName&gt; becomes that Element[ (this effect does not end at the
     * end of the turn)]." Group {@code name} is the card whose element changes; the
     * trailing parenthetical, when present, marks this as a permanent override.  Used by
     * {@link #tryParseElementChange}, which also checks {@code source.name()} matches
     * {@code name} so this parser cannot fire on an unrelated card.
     */
    static final Pattern ELEMENT_CHANGE_PATTERN = Pattern.compile(
        "(?i)^\\s*select\\s+1\\s+Element\\.\\s+" +
        "(?<name>[A-Z][A-Za-z''\\-\\s()]+?)\\s+becomes\\s+that\\s+Element" +
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
    static final Pattern GRANT_PARTY_ANY_ELEMENT_THIS_TURN = Pattern.compile(
        "(?i)The\\s+" +
        "(?:Job\\s+(?<job>.+?)\\s+|Category\\s+(?<category>\\S+)\\s+|Card\\s+Name\\s+(?<cardname>\\S+)\\s+)?" +
        "Forwards?\\s+you\\s+control\\s+can\\s+form\\s+a\\s+party\\s+with\\s+" +
        "(?:.+?\\s+)?Forwards?\\s+of\\s+any\\s+Element\\s+this\\s+turn\\s*\\.?"
    );
    /**
     * Matches "Name 1 Element[ other than X[ or Y]]. [CardName] becomes the named Element until the end of the turn."
     * — element-only self-becomes with optional exclusion.
     */
    static final Pattern NAME_ELEMENT_ONLY_SELF_BECOMES = Pattern.compile(
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
    static final Pattern NAME_ELEMENT_AND_JOB_SELF_BECOMES = Pattern.compile(
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
    static final Pattern NAME_JOB_AND_ELEMENT_SELF_GAINS_PERMANENT = Pattern.compile(
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
    static final Pattern NAME_JOB_OR_ELEMENT_ALL_FORWARDS_BOOST = Pattern.compile(
        "(?i)Name\\s+1\\s+(?:Job\\s+or\\s+1\\s+Element|Element\\s+or\\s+1\\s+Job)[.!]?\\s+" +
        "Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,?\\s+" +
        "all\\s+(?:the\\s+)?Forwards?\\s+you\\s+control\\s+gains?\\s+\\+?(?<amount>\\d+)\\s+[Pp]ower\\s+" +
        "and\\s+(?:the\\s+)?named\\s+(?:Job\\s+or\\s+(?:an?\\s+)?Element|(?:an?\\s+)?Element\\s+or\\s+Job)[.!]?"
    );
    /**
     * Matches "you may give control of [Self] to your opponent." — Leslie 16-084R, offered at the
     * end of each of her controller's turns.
     *
     * <p>The optional, self-offering sibling of {@link #STANDALONE_OPPONENT_GAINS_CONTROL}, which
     * is the same transfer stated as a fact rather than a choice. Group: {@code name}.
     */
    static final Pattern MAY_GIVE_SOURCE_CONTROL_TO_OPPONENT = Pattern.compile(
        "(?i)^you\\s+may\\s+give\\s+control\\s+of\\s+(?<name>.+?)" +
        "\\s+to\\s+your\\s+opponent[.!]?$"
    );

    /**
     * Matches "choose up to N [Element] [type] of cost X and up to M [Element] [type] of cost Y in
     * your Break Zone. Play them onto the field." — Xande 10-008L, the only printing that makes two
     * cost-specific picks out of one Break Zone in a single sentence.
     *
     * <p>Two pools, not one: a cost-1 Forward and a cost-3 Forward, each optional. The choose
     * grammar reads one filter per sentence, so it took the first half and left the rest to be
     * claimed off the tail — Xande played the cost-1 Forward and the cost-3 pick was never offered.
     * Groups: {@code count1}/{@code elem1}/{@code type1}/{@code cost1}, and the same numbered 2.
     */
    static final Pattern CHOOSE_TWO_COSTS_FROM_BZ_PLAY_BOTH = Pattern.compile(
        "(?i)choose\\s+up\\s+to\\s+(?<count1>\\d+)\\s+(?:(?<elem1>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<type1>Forwards?|Backups?|Monsters?|Characters?)\\s+of\\s+cost\\s+(?<cost1>\\d+)\\s+and\\s+" +
        "up\\s+to\\s+(?<count2>\\d+)\\s+(?:(?<elem2>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<type2>Forwards?|Backups?|Monsters?|Characters?)\\s+of\\s+cost\\s+(?<cost2>\\d+)\\s+" +
        "in\\s+your\\s+Break\\s+Zone[.!]\\s*Play\\s+them\\s+onto\\s+the\\s+field[.!]?"
    );

    /**
     * Matches "if you [don't ]have a 《C》, [effect]" — Kain 15-048L, who changes hands at the end
     * of any turn his controller finishes without a Crystal.
     * Groups: {@code negated} (present for "don't"), {@code inner}.
     */
    static final Pattern CRYSTAL_HELD_GATE = Pattern.compile(
        "(?i)^if\\s+you\\s+(?<negated>don'?t\\s+)?have\\s+(?:a\\s+)?《C》,\\s*(?<inner>.+)$"
    );

    /**
     * Matches "if N or more [X] Counters are placed on [Self], [effect]" — Number 24 20-036H,
     * whose Barrier Counters shut him down once he has three.
     * Groups: {@code count}, {@code counter}, {@code name}, {@code inner}.
     */
    static final Pattern COUNTERS_ON_SELF_GATE = Pattern.compile(
        "(?i)^if\\s+(?<count>\\d+)\\s+or\\s+more\\s+(?<counter>.+?)\\s+Counters?\\s+" +
        "(?:are|is)\\s+placed\\s+on\\s+(?<name>.+?),\\s*(?<inner>.+)$"
    );

    /**
     * Matches "all Characters [your ]opponent controls lose their Jobs until the end of the turn."
     * — Exdeath 3-100L, the corpus's only Job removal.
     * Group: {@code targets}.
     */
    static final Pattern ALL_OPP_LOSE_JOBS_UNTIL_EOT = Pattern.compile(
        "(?i)^all\\s+(?:the\\s+)?(?<targets>Characters?|Forwards?|Backups?|Monsters?)\\s+" +
        "(?:your\\s+)?opponent\\s+controls?\\s+lose\\s+(?:their|its)\\s+Jobs?\\s+" +
        "until\\s+the\\s+end\\s+of\\s+the\\s+turn[.!]?$"
    );

    /**
     * Matches "[Self] loses all its abilities until the end of the turn." standing alone —
     * Airborne Trooper 9-024C, whose own trigger strips him whenever another Forward joins him.
     *
     * <p>Every other printing of this sentence is a choose followup, where "it" is the card the
     * choice named. This one names its own card and has no choice in front of it.
     * Group: {@code name}.
     */
    static final Pattern STANDALONE_SELF_LOSES_ALL_ABILITIES = Pattern.compile(
        "(?i)^(?<name>.+?)\\s+loses\\s+all\\s+(?:its|their)\\s+abilities\\s+" +
        "until\\s+the\\s+end\\s+of\\s+the\\s+turn[.!]?$"
    );

    /** Matches the standalone "Name 1 Job" / "Select a Job" ETF effect. */
    static final Pattern NAME_JOB_STANDALONE = Pattern.compile(
        "(?i)^(?:name\\s+1|select\\s+a)\\s+Job[.!]?$"
    );

    /**
     * Matches a reference back to a Job named earlier — "&lt;N&gt; Forward(s) with the named Job",
     * Jack Garland 27-111L's "choose 1 Forward with the named Job. Remove it from the game."
     *
     * <p>Locates the phrase rather than the whole sentence, because it is used to rewrite the text
     * with the Job substituted in ("choose 1 Job Knight Forward. …") and hand the result back to
     * the ordinary chain. {@code noun} is what the Job qualifies, and is carried across the
     * rewrite so the target type survives it.
     *
     * <p>Only reads <em>back</em>. The other seven printings of "the named Job" name it in the
     * same sentence they spend it in — Shadow Lord 17-079L breaks with it, Xande 27-010L strips
     * abilities with it — and there is nothing recorded to read at the point those resolve, so
     * {@link #NAMES_A_JOB_ITSELF} keeps them out.
     */
    static final Pattern NAMED_JOB_REFERENCE = Pattern.compile(
        "(?i)(?<noun>Forwards?|Backups?|Monsters?|Characters?)\\s+with\\s+" +
        "(?:the\\s+)?named\\s+Job\\b"
    );

    /** Matches an ability that names a Job itself, rather than reading one named earlier. */
    static final Pattern NAMES_A_JOB_ITSELF = Pattern.compile(
        "(?i)\\bname\\s+1\\s+Job\\b"
    );

    // =========================================================================================
    // Reveal top N: add to hand
    // =========================================================================================
    /**
     * Matches "Name 1 Job or Category. Reveal the top N cards of your deck.
     * Add up to M Characters of the named Job or Category among them to your hand
     * and return the other cards to the bottom of your deck in any order."
     */
    static final Pattern NAME_JOB_OR_CATEGORY_REVEAL_ADD_TO_HAND = Pattern.compile(
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
    static final Pattern REVEAL_TOP_N_CATEGORY_TO_HAND = Pattern.compile(
        "(?i)^\\s*(?:you\\s+may\\s+)?reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+1\\s+Category\\s+(?<cat>\\S+)(?:\\s+(?:Forward|Backup|Character|Monster|card))?\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );
    /**
     * Matches "Reveal the top N cards of your deck. Add M [Type] [of cost C or less] among them
     * to your hand and return the other cards to the bottom of your deck in any order."
     * The "of cost C or less" clause is optional.
     * Groups: {@code n} (reveal count), {@code max} (max to add), {@code type} (card type),
     * {@code cost} (max cost; {@code null} when the clause is absent).
     *
     * <p>An untyped "Add M card(s) of cost C or less" (28-046C Zidane) selects on cost alone and
     * sets {@code anycard}/{@code anycost} instead of {@code type}/{@code cost}. The cost clause is
     * mandatory in that arm on purpose: a bare "Add 1 card among them …" restricts nothing and
     * belongs to {@code tryParseLookTopDeckAddToHandRestBottom}, which sits later in the chain and
     * would be shadowed if this pattern accepted it.
     */
    static final Pattern REVEAL_TOP_N_TYPE_TO_HAND = Pattern.compile(
        "(?i)^\\s*(?:you\\s+may\\s+)?reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+(?<max>\\d+)\\s+" +
        "(?:(?<type>Forwards?|Backups?|Monsters?|Characters?|Summons?)" +
            "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+less)?" +
        "|(?<anycard>cards?)\\s+of\\s+cost\\s+(?<anycost>\\d+)\\s+or\\s+less)" +
        "\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );
    /**
     * Matches "Reveal the top N cards of your deck. Add {\\code 1|all} [Job X] [or|and Card Name Y]
     * among them to your hand and return the other cards to the bottom of your deck in any order."
     *
     * <p>The {\\code all} arm covers 9-051R Fat Chocobo ("Add all Card Name Chocobo") and 28-093H
     * Lightning ("Add all the Card Name Odin and Card Name Lightning"). Without it both fell past
     * this pattern to the standalone return-to-hand rule, which read the whole phrase "all Card
     * Name Chocobo among them" as a card name and looked for it on the field.
     *
     * <p>"and" joins the two filter terms as an alternative to "or" for the same reason it reads as
     * a disjunction downstream: "add all the Odin and all the Lightning" takes every card matching
     * either term, which is exactly what the {\\code first}/{\\code second} filters already express.
     */
    static final Pattern REVEAL_TOP_N_JOB_OR_NAME_TO_HAND = Pattern.compile(
        "(?i)^\\s*(?:you\\s+may\\s+)?reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+(?:(?<all>all)(?:\\s+the)?|1)\\s+" +
        "(?<first>(?:Job|Card\\s+Name)\\s+.+?)" +
        "(?:\\s+(?:or|and)\\s+(?<second>(?:Job|Card\\s+Name)\\s+.+?))?" +
        "(?:\\s+(?:Forward|Backup|Character|Monster|card))?\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );
    /**
     * Matches "Reveal the top N cards of your deck. Add M [Element] [Type|card[s]] among them to
     * your hand and return the other cards to the bottom of your deck in any order", plus the
     * "Add 1 [Element] or Category [X] card …" variant (Wakka) where the optional {@code or Category}
     * clause makes the element and category <em>alternatives</em> (a card qualifies if it contains
     * the element OR belongs to the category).
     * Groups: {@code n} (reveal count), {@code max} (max to add), {@code element} (element name),
     * {@code type} (card type; only in the plain form), {@code cat} (category; only in the "or Category" form).
     */
    static final Pattern REVEAL_TOP_N_ELEMENT_TO_HAND = Pattern.compile(
        "(?i)^\\s*(?:you\\s+may\\s+)?reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+(?<max>\\d+)\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark|Multi-Element)\\s+" +
        "(?:" +
            "or\\s+Category\\s+(?<cat>\\S+)(?:\\s+(?:Forward|Backup|Character|Monster|card)s?)?" +
            "|" +
            "(?:(?<type>Forwards?|Backups?|Monsters?|Characters?)|cards?)" +
        ")\\s+" +
        "among\\s+them\\s+to\\s+your\\s+hand\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );
    /**
     * Matches "Reveal the top N cards of your deck. Add up to M cards other than Card Name [name]
     * among them to your hand, and put the rest of the cards into the Break Zone."
     * Groups: {@code n}, {@code max}, {@code name}.
     */
    static final Pattern REVEAL_TOP_N_ADD_UP_TO_EXCLUDING_NAME_REST_BZ = Pattern.compile(
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
    /**
     * "Reveal the top N cards of your deck. Play as many Job [J] [Type]s as you want with a total
     * cost of [C] or less among them onto the field and return the other cards to the bottom of
     * your deck in any order." — Warrior of Light 10-065L.
     *
     * <p>The budgeted member of the reveal-and-play family: every sibling caps how many cards are
     * played, this caps what they cost together.
     * Groups: {@code n}, {@code job}, {@code type}, {@code totalcost}.
     */
    static final Pattern REVEAL_PLAY_AS_MANY_JOB_TYPE_TOTAL_COST_REST_BOTTOM = Pattern.compile(
        "(?i)^\\s*reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Play\\s+as\\s+many\\s+Job\\s+(?<job>.+?)\\s+(?<type>Forward|Backup|Monster|Character)s?\\s+" +
        "as\\s+you\\s+want\\s+with\\s+a\\s+total\\s+cost\\s+of\\s+(?<totalcost>\\d+)\\s+or\\s+less\\s+" +
        "among\\s+them\\s+onto\\s+(?:the\\s+)?field,?\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );

    static final Pattern REVEAL_ADD_TYPE_TO_HAND_OR_PLAY_JOB_TYPE_ONTO_FIELD_REST_BOTTOM = Pattern.compile(
        "(?i)^\\s*reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+(?<handmax>\\d+)\\s+(?<handtype>Forward|Backup|Monster|Character)s?\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+" +
        "or\\s+play\\s+(?<fieldmax>\\d+)\\s+" +
        "(?:Job\\s+(?<fieldjob>.+?)(?=\\s+(?:Forward|Backup|Monster|Character)s?\\s+among)\\s+)?" +
        "(?<fieldtype>Forward|Backup|Monster|Character)s?\\s+among\\s+them\\s+onto\\s+(?:the\\s+)?field,?\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );


    // =========================================================================================
    // Damage shields, reductions and negation
    // =========================================================================================
    // ---- Damage-shield followup patterns (apply to selected "it/them" targets) --------
    /** Matches "During this turn, the next damage dealt to it/him becomes 0 instead." */
    static final Pattern FOLLOWUP_SHIELD_NEXT_DMG_ZERO = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+(?:it|him)\\s+becomes\\s+0\\s+instead\\.?"
    );
    /**
     * The source-scoped spelling of the shield above: "During this turn, the next damage dealt to
     * it by your opponent's Summons or abilities becomes 0 instead." — Auron 22-001R.
     *
     * <p>Read before the unqualified pattern, and the two cannot be folded together: that one ends
     * at "dealt to it", so under {@code find()} it would claim this sentence and hand Auron a
     * shield against combat damage he does not print.
     */
    static final Pattern FOLLOWUP_SHIELD_NEXT_OPP_EFFECT_DMG_ZERO = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+(?:it|him)\\s+by\\s+" +
        "your\\s+opponent's\\s+(?:Summons?\\s+or\\s+abilities|abilities\\s+or\\s+Summons?)\\s+" +
        "becomes\\s+0\\s+instead\\.?"
    );
    /** Matches "During this turn, the next damage dealt to you becomes 0 instead." */
    static final Pattern PLAYER_NEXT_DAMAGE_ZERO = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+you\\s+becomes\\s+0\\s+instead\\.?"
    );
    /**
     * Matches "During this turn, the next damage dealt to you becomes 0 and deal [Name] N damage
     * instead." (Auron) — the player shield plus a redirect to the named Forward on consumption.
     */
    static final Pattern PLAYER_NEXT_DAMAGE_ZERO_REDIRECT = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+you\\s+becomes\\s+0\\s+" +
        "and\\s+deal\\s+(?<name>.+?)\\s+(?<dmg>\\d+)\\s+damage\\s+instead\\.?"
    );
    /** Matches "During this turn, the next damage dealt to it by Summons or abilities is reduced by N instead." */
    static final Pattern FOLLOWUP_SHIELD_NEXT_ABILITY_DMG_REDUCTION = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+it\\s+by\\s+Summons?\\s+or\\s+abilities\\s+is\\s+reduced\\s+by\\s+(?<reduction>\\d+)\\s+instead\\.?"
    );
    /**
     * Matches "During this turn, the next time this Forward would take damage, reduce it by N
     * instead and deal [CardName] M damage." — 9-109H Cecil, who shields a Forward by taking the
     * hit himself.
     *
     * <p>Checked ahead of {@link #FOLLOWUP_SHIELD_NEXT_DMG_REDUCTION}. That one does not match this
     * wording today, but both are read with find() and both describe a one-shot reduction, so the
     * specific form goes first on principle: reaching the general one would leave Cecil unbilled.
     *
     * <p>Groups: {@code reduction} — the amount the shielded Forward's damage drops by;
     * {@code name} — the card the kickback is dealt to; {@code dmg} — how much it takes.
     */
    static final Pattern FOLLOWUP_SHIELD_NEXT_DMG_REDUCTION_KICKBACK = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+time\\s+this\\s+Forward\\s+would\\s+take\\s+damage,\\s+" +
        "reduce\\s+it\\s+by\\s+(?<reduction>\\d+)\\s+instead\\s+and\\s+deal\\s+(?<name>.+?)\\s+(?<dmg>\\d+)\\s+damage[.!]?"
    );
    /** Matches "During this turn, the next damage dealt to it is reduced by N instead." or "Reduce the next damage dealt to it this turn by N." */
    static final Pattern FOLLOWUP_SHIELD_NEXT_DMG_REDUCTION = Pattern.compile(
        "(?i)(?:During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+(?:it|him)\\s+is\\s+reduced\\s+by|Reduce\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+(?:it|him)\\s+this\\s+turn\\s+by)\\s+(?<reduction>\\d+)(?:\\s+instead)?\\.?"
    );
    /** Matches "During this turn, the damage dealt to it is increased by N instead." */
    static final Pattern FOLLOWUP_DEBUFF_INCOMING_DMG_INCREASE = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+damage\\s+dealt\\s+to\\s+it\\s+is\\s+increased\\s+by\\s+(?<amount>\\d+)\\s+instead\\.?"
    );
    /** Matches "During this turn, the next damage it deals to a Forward becomes 0 instead." */
    static final Pattern FOLLOWUP_SHIELD_NEXT_OUTGOING_ZERO = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+it\\s+deals\\s+to\\s+a\\s+Forward\\s+becomes\\s+0\\s+instead\\.?"
    );
    /**
     * Matches "During this turn, the next damage it deals to a Forward becomes double the damage
     * instead." — the followup form of {@link #CHOOSE_FORWARD_DOUBLE_NEXT_OUTGOING}, read from the
     * choose chain once the target filter has already been parsed.
     *
     * <p>This is the form that runs. The whole-text pattern spells its own choose clause and
     * requires the word "Forward" in it, which 9-078C Rinok's "Choose 1 Job Headhunter." does not
     * carry — and it sits behind tryParseChooseCharacter in parse() regardless, so no card in the
     * corpus reaches it.
     */
    static final Pattern FOLLOWUP_DOUBLE_NEXT_OUTGOING = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+it\\s+deals\\s+to\\s+a\\s+Forward\\s+" +
        "becomes\\s+double\\s+the\\s+damage\\s+instead[.!]?"
    );
    /** Matches "If it deals damage to a Forward [opponent controls] this turn, the damage increases by N instead." */
    static final Pattern FOLLOWUP_OUTGOING_DMG_BOOST_THIS_TURN = Pattern.compile(
        "(?i)If\\s+it\\s+deals\\s+damage\\s+to\\s+a\\s+Forward(?:\\s+opponent\\s+controls?)?\\s+this\\s+turn,?\\s+" +
        "(?:the\\s+damage\\s+increases?|increase\\s+the\\s+damage)\\s+by\\s+(?<amount>\\d+)(?:\\s+instead)?[.!]?"
    );
    /** Matches "If [CardName] deals damage to a Forward this turn, the damage increases by N instead." */
    static final Pattern SELF_OUTGOING_DMG_BOOST_THIS_TURN = Pattern.compile(
        "(?i)If\\s+(?<subject>.+?)\\s+deals\\s+damage\\s+to\\s+a\\s+Forward\\s+this\\s+turn,?\\s+" +
        "the\\s+damage\\s+increases?\\s+by\\s+(?<amount>\\d+)(?:\\s+instead)?[.!]?$"
    );
    /** Matches "During this turn, if it is dealt damage less than its power, the damage becomes 0 instead." */
    static final Pattern FOLLOWUP_SHIELD_NONLETHAL = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+it\\s+is\\s+dealt\\s+damage\\s+less\\s+than\\s+its\\s+power,\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?"
    );
    /**
     * "It gains 'If this Forward is dealt damage by your opponent's abilities, the damage becomes
     * 0 instead.' until the end of the turn."
     */
    static final Pattern FOLLOWUP_GAINS_SHIELD_ABILITY_ONLY = Pattern.compile(
        "(?i)(?:it|they)\\s+gains?\\s+['\"]If\\s+this\\s+Forward\\s+is\\s+dealt\\s+damage\\s+by\\s+your\\s+opponent's\\s+abilities,\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    // =========================================================================================
    // Quoted-ability grants and control changes
    // =========================================================================================
    /**
     * "It/They gains "[ability] (This effect does not end at the end of the turn.)"" — a choose
     * followup handing the chosen Character an ability for good (Lich 21-079R).
     *
     * <p>The permanence reminder is printed <em>inside</em> the quotes, so {@code quoted} carries
     * it and the parser strips it before the clause is read as an ability. Requiring it is what
     * separates this from the turn-scoped grants: without the parenthetical the grant would expire,
     * and no card in the corpus spells a permanent grant any other way in this position.
     */
    /**
     * "It/They gains "[anything]"[ until the end of the turn]" — the shape of every quoted-ability
     * grant in the choose followup position, used to claim one before the {@code find()}-based
     * parsers can reach a clause printed inside the quotation. Group {@code quoted} is the whole
     * quotation, so a caller can test how much text it spans.
     */
    static final Pattern FOLLOWUP_GAINS_QUOTED_ABILITY = Pattern.compile(
        "(?i)^(?:it|they)\\s+gains?\\s+\"(?<quoted>.+)\"" +
        "(?:\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn)?[.!]?\\s*$"
    );
    static final Pattern FOLLOWUP_GAINS_QUOTED_ABILITY_PERMANENT = Pattern.compile(
        "(?i)^(?:it|they)\\s+gains?\\s+\"(?<quoted>.+?" +
        "\\(This\\s+effect\\s+does\\s+not\\s+end\\s+at\\s+the\\s+end\\s+of\\s+the\\s+turn\\.?\\))\"[.!]?\\s*$"
    );
    /** The permanence reminder {@link #FOLLOWUP_GAINS_QUOTED_ABILITY_PERMANENT} strips off its quoted clause. */
    static final Pattern PERMANENCE_REMINDER = Pattern.compile(
        "(?i)\\s*\\(This\\s+effect\\s+does\\s+not\\s+end\\s+at\\s+the\\s+end\\s+of\\s+the\\s+turn\\.?\\)\\s*$"
    );
    /**
     * "It/They gains +N power and "[ability]" (This effect does not end at the end of the turn.)"
     * — Ellone 27-020R, which hands the chosen Forward two permanent halves in one sentence.
     *
     * <p>Separate from {@link #FOLLOWUP_GAINS_QUOTED_ABILITY_PERMANENT} because the reminder sits
     * somewhere else: Lich prints it <em>inside</em> the quotation, so that pattern's {@code quoted}
     * carries it and the caller strips it back off. Here it sits outside, governing both halves at
     * once, so it is matched in place and {@code quoted} is already the bare clause.
     *
     * <p>{@code quoted} excludes {@code "} so the quotation cannot run past its own closing mark and
     * swallow the reminder that follows it — without that the permanence would parse as part of the
     * granted ability and the power half would be handed out for the turn only.
     *
     * <p>Anchored at both ends against the whole followup, so a match proves there is no trailing
     * sentence left over. Callers strip the restriction sentences first, as
     * {@link ActionResolverChoose#isMustAttackAndMustBlockGrant} does: this card's followup ends with "You
     * can only use this ability during your turn." and never breaks on ". ", so that sentence rides
     * along in the followup rather than splitting off into a secondary.
     */
    static final Pattern FOLLOWUP_GAINS_POWER_AND_QUOTED_ABILITY_PERMANENT = Pattern.compile(
        "(?i)^(?:it|they)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+power\\s+and\\s+\"(?<quoted>[^\"]+)\"" +
        "\\s*\\(This\\s+effect\\s+does\\s+not\\s+end\\s+at\\s+the\\s+end\\s+of\\s+the\\s+turn\\.?\\)[.!]?\\s*$"
    );
    /** Matches "Negate all [the] damage dealt to it/them." — removes all existing damage immediately. */
    static final Pattern FOLLOWUP_NEGATE_DAMAGE = Pattern.compile(
        "(?i)Negate\\s+all\\s+(?:the\\s+)?damage\\s+dealt\\s+to\\s+(?:it|them)\\.?"
    );
    /**
     * Matches "Activate it/them and negate all [the] damage dealt to it/them."
     * Checked before {@link #FOLLOWUP_ACTIVATE} to prevent the simpler pattern from
     * consuming only the "Activate it" prefix.
     */
    static final Pattern FOLLOWUP_ACTIVATE_AND_NEGATE_DAMAGE = Pattern.compile(
        "(?i)Activate\\s+(?:it|them)\\s+and\\s+negate\\s+all\\s+(?:the\\s+)?damage\\s+dealt\\s+to\\s+(?:it|them)\\.?"
    );

    // ---- Gain-control followup patterns -----------------------------------------------
    /**
     * "Activate it/them and gain control of it/them until the end of the turn."
     * Checked before {@link #FOLLOWUP_ACTIVATE} and {@link #FOLLOWUP_GAIN_CONTROL_EOT}
     * to avoid partial matches on the "Activate" or plain "gain control" prefixes.
     */
    static final Pattern FOLLOWUP_ACTIVATE_AND_GAIN_CONTROL_EOT = Pattern.compile(
        "(?i)Activate\\s+(?:it|them)\\s+and\\s+(?:you\\s+)?gain\\s+control\\s+of\\s+(?:it|them)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );
    /**
     * "gain control of it/them for as long as [card] is on the field."
     * Checked before {@link #FOLLOWUP_GAIN_CONTROL} to avoid the shorter pattern matching first.
     * Group {@code condCard} captures the card name that must remain on the field.
     */
    static final Pattern FOLLOWUP_GAIN_CONTROL_WHILE_CARD = Pattern.compile(
        "(?i)(?:you\\s+)?gain\\s+control\\s+of\\s+(?:it|them)" +
        "\\s+for\\s+as\\s+long\\s+as\\s+(?<condCard>.+?)\\s+is\\s+on\\s+the\\s+field\\.?"
    );
    /** "gain control of it/them until the end of the turn." */
    static final Pattern FOLLOWUP_GAIN_CONTROL_EOT = Pattern.compile(
        "(?i)(?:you\\s+)?gain\\s+control\\s+of\\s+(?:it|them)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );
    /** "you gain control of it/them." — permanent, no duration qualifier. */
    static final Pattern FOLLOWUP_GAIN_CONTROL = Pattern.compile(
        "(?i)(?:you\\s+)?gain\\s+control\\s+of\\s+(?:it|them)\\.?"
    );
    /**
     * Standalone: "Your opponent gains control of [CardName]." — permanent control transfer of
     * the source card itself, away from its own controller, to their opponent (e.g. Leon). The
     * reverse direction of {@link #FOLLOWUP_GAIN_CONTROL}, which is always the ability user
     * gaining control of a chosen target.
     */
    static final Pattern STANDALONE_OPPONENT_GAINS_CONTROL = Pattern.compile(
        "(?i)^Your\\s+opponent\\s+gains?\\s+control\\s+of\\s+(?<name>[A-Z][A-Za-z''\\-\\s()]+?)\\.?\\s*$"
    );

    // ---- Cannot-be-chosen followup patterns -----------------------------------------
    /**
     * "It/They gains 'This Forward/Character cannot be chosen by your opponent's [Summons/abilities].'
     * until the end of the turn."  The grant form is semantically identical to a direct EOT shield.
     * Checked first so the simpler cannot-be-chosen patterns do not match inside the quoted text.
     * Group {@code scope} captures the scope string.
     */
    static final Pattern FOLLOWUP_GAINS_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)(?:it|they)\\s+gains?\\s+['\"]This\\s+(?:Forward|Character)\\s+cannot\\s+be\\s+chosen" +
        "\\s+by\\s+your\\s+opponent's\\s+(?<scope>Summons?(?:\\s+or\\s+abilities)?|abilities)\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    // =========================================================================================
    // Cannot-be-chosen and cannot-be-broken protections
    // =========================================================================================
    /**
     * "[Cardname] and it gain '[quote]' until the end of your opponent's turn."
     * Rydia-style: source card and chosen target both receive the quoted ability until opponent's EOT.
     */
    static final Pattern FOLLOWUP_SELF_AND_TARGET_GAIN_QUOTE_UNTIL_OPP_TURN = Pattern.compile(
        "(?i)\\S.*?\\s+and\\s+it\\s+gains?\\s+['\"].+?['\"]\\s+until\\s+the\\s+end\\s+of\\s+your\\s+opponent.s\\s+turn[.!]?"
    );
    /** "The next time you use its special ability this turn, you can do so without paying [cost]."
     *  Edgar-style: waives the special-ability cost for the chosen target once this turn. */
    static final Pattern FOLLOWUP_TARGET_NEXT_SPECIAL_FREE = Pattern.compile(
        "(?i)The\\s+next\\s+time\\s+you\\s+use\\s+its\\s+special\\s+ability\\s+this\\s+turn,\\s+" +
        "you\\s+can\\s+do\\s+so\\s+without\\s+paying\\s+.+?[.!]?"
    );
    /** "During this turn, you can cast it at any time you could normally cast it as long as you have
     *  no cards in hand."  Minwu (FFBE)-style: allows instant-casting the chosen BZ card this turn. */
    static final Pattern FOLLOWUP_CAST_IT_FROM_BZ_ANYTIME_NO_HAND = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+you\\s+can\\s+cast\\s+it\\s+at\\s+any\\s+time\\s+" +
        "you\\s+could\\s+normally\\s+cast\\s+it\\s+as\\s+long\\s+as\\s+you\\s+have\\s+no\\s+cards\\s+in\\s+hand[.!]?"
    );
    /**
     * "It/They cannot be chosen by your opponent's Summons or abilities [this turn]."
     * More specific than the Summons-only and abilities-only forms; checked first.
     */
    static final Pattern FOLLOWUP_CANNOT_BE_CHOSEN_BOTH = Pattern.compile(
        "(?i)(?:it|they)\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "Summons?\\s+or\\s+abilities\\.?"
    );
    /** "It/They cannot be chosen by your opponent's Summons [this turn]." */
    static final Pattern FOLLOWUP_CANNOT_BE_CHOSEN_SUMMONS = Pattern.compile(
        "(?i)(?:it|they)\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+Summons?\\.?"
    );
    /** "It/They cannot be chosen by your opponent's abilities [this turn]." */
    static final Pattern FOLLOWUP_CANNOT_BE_CHOSEN_ABILITIES = Pattern.compile(
        "(?i)(?:it|they)\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+abilities\\.?"
    );
    /**
     * "[During this turn,] it/they cannot be returned to its/their owner's hand by your
     * opponent's Summons or abilities [this turn]." — EOT return-to-hand protection for the
     * chosen target(s), enforced via {@link CardData.Trait#CANNOT_BE_RETURNED_TO_HAND_BY_OPP}.
     */
    static final Pattern FOLLOWUP_CANNOT_BE_RETURNED_TO_HAND = Pattern.compile(
        "(?i)(?:During\\s+this\\s+turn,\\s+)?(?:it|they)\\s+cannot\\s+be\\s+returned\\s+to\\s+" +
        "(?:its|their)\\s+owner's\\s+hand\\s+by\\s+(?:your\\s+)?opponent's\\s+" +
        "(?:Summons?(?:\\s+or\\s+abilities)?|abilities)\\.?"
    );
    /**
     * "[During this turn,] it/they cannot become dull by your opponent's Summons or abilities
     * [this turn]." — Black Mage 4-079C, the chosen-target form of the sentence Guy 1-097H and
     * friends print about themselves.  Enforced through {@link CardData.Trait#CANNOT_BE_DULLED_BY_OPP},
     * the trait the printed wording already grants permanently, so the dulling paths need nothing new.
     *
     * <p>Written alongside {@link #FOLLOWUP_CANNOT_BE_RETURNED_TO_HAND} because the two sentences
     * are the same shape. It cannot be confused with {@link #FOLLOWUP_DULL}, which wants the verb
     * ("dull it"), not the adjective this one ends on.
     */
    static final Pattern FOLLOWUP_CANNOT_BECOME_DULL_BY_OPP = Pattern.compile(
        "(?i)(?:During\\s+this\\s+turn,\\s+)?(?:it|they)\\s+cannot\\s+become\\s+dull\\s+by\\s+" +
        "(?:your\\s+)?opponent's\\s+(?:Summons?(?:\\s+or\\s+abilities)?|abilities)\\.?"
    );
    /**
     * "During this turn, if a Character enters the field by your opponent's Summons or abilities,
     * remove it from the game instead." — Alhanalem 18-018R.
     *
     * <p>A replacement effect, not a removal: the Character never arrives, so its
     * "enters the field" ability never fires either. What decides whether it bites is who owns the
     * Summon or ability doing the playing, not whose field the card was headed for — which is how
     * the sentence is worded, and it matters for an effect that plays a Character onto the other
     * side.
     */
    static final Pattern STANDALONE_OPP_FIELD_ENTRY_RFG_INSTEAD = Pattern.compile(
        "(?i)^During\\s+this\\s+turn,\\s+if\\s+a\\s+Character\\s+enters\\s+the\\s+field\\s+by\\s+" +
        "your\\s+opponent's\\s+Summons?\\s+or\\s+abilities,\\s+remove\\s+it\\s+from\\s+the\\s+game\\s+instead[.!]?$"
    );
    /** "It gains 'This Character/Forward/Monster cannot be broken.' until the end of the turn." Also matches the leading-Until form: "Until the end of the turn, it gains '...'." */
    static final Pattern FOLLOWUP_CANNOT_BE_BROKEN = Pattern.compile(
        "(?i)(?:Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,\\s+)?" +
        "(?:it|they)\\s+gains?\\s+['\"]This\\s+(?:Forward|Character|Monster)\\s+cannot\\s+be\\s+broken\\.?['\"]" +
        "(?:\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?)?"
    );
    /** "It cannot be broken this turn." */
    static final Pattern FOLLOWUP_CANNOT_BE_BROKEN_SIMPLE = Pattern.compile(
        "(?i)(?:it|they)\\s+cannot\\s+be\\s+broken\\s+this\\s+turn\\.?"
    );
    /** "During this turn, it cannot be broken by opposing Summons or abilities that don't deal damage." */
    static final Pattern FOLLOWUP_CANNOT_BE_BROKEN_BY_NON_DMG = Pattern.compile(
        "(?i)(?:During\\s+this\\s+turn,\\s+)?(?:it|they)\\s+cannot\\s+be\\s+broken\\s+by\\s+" +
        "(?:opposing|your\\s+opponent's)\\s+Summons\\s+or\\s+abilities\\s+that\\s+don'?t\\s+deal\\s+damage\\.?"
    );
    /**
     * "If it is put from the field into the Break Zone this turn, remove it from the game
     * instead." (Jet Bahamut-style) — marks the chosen target for redirect-to-RFG for the rest
     * of the turn, regardless of what later effect breaks it.
     */
    static final Pattern FOLLOWUP_IF_PUT_TO_BZ_THIS_TURN_RFG_INSTEAD = Pattern.compile(
        "(?i)If\\s+(?:it|they)\\s+(?:is|are)\\s+put\\s+from\\s+the\\s+field\\s+into\\s+the\\s+Break\\s+Zone\\s+this\\s+turn,\\s+" +
        "remove\\s+(?:it|them)\\s+from\\s+the\\s+game\\s+instead\\.?"
    );
    /**
     * "&lt;choose + primary&gt;. When it is put from the field into the Break Zone this turn, draw
     * N card(s)." (Brynhildr 15-014H, Ritz 20-062R) — a delayed trigger placed on the chosen
     * target, firing for the player who resolved the ability whenever that Forward later leaves
     * the field for the Break Zone, by combat or by any effect.
     *
     * <p>{@code head} is greedy so it runs up to the <em>last</em> occurrence of the trigger
     * clause, keeping the whole "Choose … . &lt;primary&gt;." prefix intact for the normal parser.
     * Groups: {@code head} — the choose-and-act text; {@code count} — cards drawn.
     */
    static final Pattern CHOOSE_THEN_WHEN_PUT_TO_BZ_DRAW = Pattern.compile(
        "(?is)^(?<head>.+)\\s+When\\s+(?:it|they)\\s+(?:is|are)\\s+put\\s+from\\s+the\\s+field\\s+" +
        "into\\s+the\\s+Break\\s+Zone\\s+this\\s+turn,\\s+draw\\s+(?<count>\\d+)\\s+cards?[.!]?$"
    );
    /** Standalone: "[CardName] gains '[...] cannot be broken.' until end of turn." */
    static final Pattern STANDALONE_SELF_SHIELD_CANNOT_BE_BROKEN = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+gains?\\s+['\"][^'\"]*?cannot\\s+be\\s+broken\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );
    /** Standalone: "[CardName] cannot be broken this turn." — bare form without 'gains' quoting. */
    static final Pattern STANDALONE_SELF_SHIELD_CANNOT_BE_BROKEN_SIMPLE = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+cannot\\s+be\\s+broken\\s+this\\s+turn\\.?"
    );
    /**
     * Standalone: "[CardName] gains "[CardName] cannot be broken by opposing Summons or abilities
     * that don't deal damage." until the end of the turn." — self-shield limited to non-damage
     * breaks (Maat-style), the quoted-gains form of {@link #FOLLOWUP_CANNOT_BE_BROKEN_BY_NON_DMG}.
     */
    static final Pattern STANDALONE_SELF_SHIELD_CANNOT_BE_BROKEN_BY_NON_DMG = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+gains?\\s+['\"].+?\\s+cannot\\s+be\\s+broken\\s+by\\s+" +
        "(?:opposing|your\\s+opponent's)\\s+Summons\\s+or\\s+abilities\\s+that\\s+don'?t\\s+deal\\s+damage\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );
    /**
     * Standalone: "Dull [CardName]." — dulls the source card with no other effect.
     * Must be tried after {@link #STANDALONE_SELF_DULL_AND_SHIELD_CANNOT_BE_BROKEN} so the
     * compound case is not shadowed.
     */
    /**
     * Matches the imperative "dull N active [filter] you control" — the self-dull <em>price</em> in
     * front of "When you do so, …" on 29-050C Chocobo, 12-033R Snow, 26-064R Ignis and six others.
     *
     * <p>Anchored over the whole clause with {\\code ^…$} so it claims only this bare imperative.
     * {\\link ActionResolver#tryParseWhenYouDoSoSequence} hands it exactly that: the primary half,
     * already split off the "When you do so" followup.
     *
     * <p>Groups: {\\code count} — how many to dull; {\\code filter} — the target phrase
     * ("Wind Forward", "Category XIII Forward", "Fire Backups"), passed through to the choose
     * chain rather than decoded here.
     */
    static final Pattern DULL_N_ACTIVE_YOU_CONTROL = Pattern.compile(
        "(?i)^\\s*dull\\s+(?<count>\\d+)\\s+active\\s+(?<filter>.+?)\\s+you\\s+control[.!]?\\s*$"
    );
    static final Pattern STANDALONE_SELF_DULL = Pattern.compile(
        "(?i)^dull\\s+(?<subject>.+?)\\.?\\s*$"
    );
    /**
     * Compound: "Dull [CardName]. [CardName] gains '[...] cannot be broken.' until end of turn."
     * Must be tried before the plain {@link #STANDALONE_SELF_SHIELD_CANNOT_BE_BROKEN} matcher so
     * the dull step is not silently dropped.
     */
    static final Pattern STANDALONE_SELF_DULL_AND_SHIELD_CANNOT_BE_BROKEN = Pattern.compile(
        "(?i)Dull\\s+(?<subject>.+?)\\.\\s+.+?\\s+gains?\\s+['\"][^'\"]*?cannot\\s+be\\s+broken\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );
    /** Standalone: "All [the] Forwards you control gain '[...] cannot be broken.' until end of turn." */
    static final Pattern STANDALONE_ALL_FORWARDS_SHIELD_CANNOT_BE_BROKEN = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Forwards?\\s+you\\s+control\\s+gains?\\s+" +
        "['\"][^'\"]*?cannot\\s+be\\s+broken\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    // =========================================================================================
    // Ability-damage nullification; self protections
    // =========================================================================================
    /**
     * "During this turn, if a Forward you control is dealt damage by a Summon[ or an ability],
     *  the damage becomes 0 instead."
     *
     * <p>The trailing clause is optional because the two printings differ by it and mean different
     * things: B-047 Leviathan stops Summons and abilities alike, 6-125R Leviathan stops Summons
     * only. Group {@code abilities} says which was printed, and the parser picks the matching
     * shield — reading them the same way would have the older card blanking damage it does not
     * mention.
     */
    static final Pattern ALL_OWN_FORWARDS_NULLIFY_ABILITY_DAMAGE_PATTERN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,?\\s+if\\s+(?:a\\s+)?Forwards?\\s+you\\s+control\\s+(?:is|are)\\s+dealt\\s+damage" +
        "\\s+by\\s+(?:a\\s+)?Summons?(?<abilities>\\s+or\\s+an?\\s+abilit(?:y|ies))?,?" +
        "\\s+the\\s+damage\\s+becomes?\\s+0\\s+instead[.!]?"
    );
    /**
     * Doublecast (Yuna): "When you cast a Summon this turn, you may cast 1 Summon from your hand
     * with a cost inferior to that of the Summon you cast without paying its cost." — turn-long
     * field effect; the free-cast threshold follows the printed cost of the last Summon cast.
     */
    static final Pattern DOUBLECAST_FREE_SUMMONS_PATTERN = Pattern.compile(
        "(?i)When\\s+you\\s+cast\\s+a\\s+Summon\\s+this\\s+turn,?\\s+you\\s+may\\s+cast\\s+1\\s+Summon\\s+" +
        "from\\s+your\\s+hand\\s+with\\s+a\\s+cost\\s+inferior\\s+to\\s+that\\s+of\\s+the\\s+Summon\\s+" +
        "you\\s+cast\\s+without\\s+paying\\s+its\\s+cost[.!]?"
    );
    /**
     * "During this turn, if a Job [X] or Card Name [Y] you control is dealt damage by a Summon
     *  or an ability, the damage becomes 0 instead." — job/card-name-filtered variant of
     * {@link #ALL_OWN_FORWARDS_NULLIFY_ABILITY_DAMAGE_PATTERN}.
     */
    static final Pattern OWN_JOB_OR_NAME_NULLIFY_ABILITY_DAMAGE_PATTERN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,?\\s+if\\s+a\\s+Job\\s+(?<job>.+?)\\s+or\\s+(?:a\\s+)?Card\\s+Name\\s+(?<cardname>.+?)" +
        "\\s+you\\s+control\\s+(?:is|are)\\s+dealt\\s+damage" +
        "\\s+by\\s+(?:a\\s+)?Summons?\\s+or\\s+an?\\s+abilit(?:y|ies),?\\s+the\\s+damage\\s+becomes?\\s+0\\s+instead[.!]?"
    );
    /**
     * "[Self] gains his/her action abilities until the end of the turn." — Gogo 9-107C, borrowing
     * the action abilities of a Forward the opponent controls.
     *
     * <p>Group {@code name} is checked against the source card by the parser: the sentence names
     * the borrower, and a printing that named someone else would be a different effect.
     */
    static final Pattern FOLLOWUP_SOURCE_GAINS_TARGET_ACTION_ABILITIES = Pattern.compile(
        "(?i)(?<name>[^.,]+?)\\s+gains?\\s+(?:his/her|his|her|their|its)\\s+action\\s+abilities" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );
    /**
     * "It gains 'When this Forward is dealt damage, break this Forward.' until the end of the
     * turn." — Vallaide 22-020R, and the same sentence inside Hades 16-079H's attack trigger.
     *
     * <p>Kept apart from {@link #FOLLOWUP_GAINS_BREAKTOUCH_BATTLE} below rather than folded into
     * one pattern: the two quotations differ by which side of the damage they watch, and reading
     * one as the other would break the wrong Forward.
     */
    static final Pattern FOLLOWUP_GAINS_BREAK_WHEN_DEALT_DAMAGE = Pattern.compile(
        "(?i)(?:it|they)\\s+gains?\\s+['\"]When\\s+this\\s+Forward\\s+is\\s+dealt\\s+damage,\\s+" +
        "break\\s+this\\s+Forward\\.?['\"]\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );
    /** "It gains 'When this Forward deals battle damage to a Forward, break that Forward.' until the end of the turn." */
    static final Pattern FOLLOWUP_GAINS_BREAKTOUCH_BATTLE = Pattern.compile(
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
    static final Pattern STANDALONE_ACTIVATE_AND_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)Activate\\s+all\\s+(?:the\\s+)?(?:Forwards?|Characters?)\\s+you\\s+control\\." +
        "\\s*They\\s+cannot\\s+be\\s+chosen\\s+by\\s+(?:your\\s+opponent's\\s+)?" +
        "(?<scope>Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*(?:this\\s+turn)?\\s*\\.?"
    );
    /**
     * "This Forward/Character cannot be chosen by your opponent's Summons/abilities."
     * Self-referential: applies protection to the {@code source} card itself.
     */
    static final Pattern STANDALONE_SELF_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)This\\s+(?:Forward|Character)\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "(?<scope>Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );
    /**
     * "[CardName] cannot be chosen by your opponent's Summons/abilities."
     * Only matches when {@code cardName} equals the {@code source} card's name.
     *
     * <p><b>Parentheses in the name class.</b> This is the first of the self-named family — the
     * patterns whose {@code name} group is the source card naming itself, and whose only right-hand
     * boundary is the literal that follows it ("cannot", "becomes", "is dealt"). The group has to
     * run right up to that literal, so a character it cannot cross kills the whole match rather
     * than truncating the capture: without {@code ()} in the class, "The Magus Sisters (XIV) cannot
     * be chosen …" matched nothing at all. 140 cards carry a parenthesised, disambiguated printing
     * name and 114 quote it in their own text, so every member of this family admits {@code ()}.
     *
     * <p>Widening is safe here specifically because each consumer re-checks the capture with
     * {@code equalsIgnoreCase(source.name())} (or anchors with {@code matches()}): the class is a
     * scanner, the equality check is the real filter, so a looser class cannot over-claim.
     * <b>That reasoning does not transfer</b> to the ~30 remaining name classes in this file and
     * {@code CardData} — job names, Counter names, "other than X" filters — which have no such
     * backstop and no parenthesised member in the corpus. Leave those narrow.
     */
    static final Pattern STANDALONE_NAMED_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)(?<name>[A-Z][A-Za-z''\\-\\s()]+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+(?:your\\s+)?opponent's\\s+" +
        "(?<scope>Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );


    // =========================================================================================
    // Field abilities: protection from choice and removal
    // =========================================================================================
    /**
     * The standing field-ability spelling of the sentence above: "[Self] cannot be chosen by [your]
     * opponent's Summons/abilities." — 25 printings, from Zidane 1-071L to Yuna 27-107R.
     *
     * <p>Anchored end to end, where {@link #STANDALONE_NAMED_CANNOT_BE_CHOSEN} scans with
     * {@code find()}. That is the whole difference and it is load bearing: two printings continue
     * past the keyword with a qualifier that narrows the immunity — Bartz 18-047H's "…that share
     * its Element" and Jack Garland 27-111L's "…of Characters with the named Job" — and a scanning
     * matcher would stop at "abilities" and hand both of them a blanket immunity they do not have.
     * Bartz's is read by {@link #STANDALONE_NAMED_CANNOT_BE_CHOSEN_BY_OWN_ELEMENT} instead; Jack
     * Garland's is unhandled, and stays visibly so.
     *
     * <p>Terra 1-046H is the one printing that omits "your", which is why the qualifier is optional
     * in both patterns.
     */
    static final Pattern FA_SELF_CANNOT_BE_CHOSEN_BY_OPP = Pattern.compile(
        "(?i)^(?<name>.+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+(?:your\\s+)?opponent's\\s+" +
        "(?<scope>Summons?\\s+or\\s+abilities|Summons?|abilities)\\s*[.!]?$"
    );
    /**
     * "[CardName] cannot be chosen by Summons [during this turn]." — no "your opponent's" qualifier,
     * meaning the protection applies to Summons from either player.
     * Only matches when {@code cardName} equals the {@code source} card's name.
     *
     * <p>The trailing lookahead is what keeps this to the unqualified printing. Matching with
     * {@code find()}, the pattern used to end at "Summons" and ignore whatever qualified it, so it
     * claimed every longer sentence sharing that prefix and replaced the real effect with a blanket
     * any-Summon shield: Kam'lanaut 5-148H's "…or abilities that share its Element" became immunity
     * to all Summons, and Rubicante 2-023H's and Hein 10-129L's "Name 1 Element" effects were
     * swallowed whole, never reaching the parsers below that read them. Requiring the sentence to
     * end here sends each of those on to its own branch.
     */
    /**
     * Matches "[Self] cannot be chosen by a Summon or an ability this turn and gains [traits] until
     * the end of the turn." — 2-065L Balthier's Fires of War, the corpus's only printing of the
     * unqualified both-halves immunity.
     *
     * <p>"a Summon or an ability", not "your opponent's Summons or abilities": this binds both
     * players, so it seeds the symmetric shields rather than the opponent-scoped ones. Nothing else
     * in the family spells it that way, which is why it is read here rather than folded into
     * {@link #STANDALONE_NAMED_CANNOT_BE_CHOSEN}.
     *
     * <p>Groups: {@code name} — the card shielding itself; {@code traits} — the keywords it gains.
     */
    static final Pattern SELF_CANNOT_BE_CHOSEN_BY_ANY_AND_GAINS_TRAITS = Pattern.compile(
        "(?i)^(?<name>[A-Z][A-Za-z''\\-\\s()]+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+a\\s+Summon\\s+or\\s+an\\s+" +
        "ability\\s+this\\s+turn\\s+and\\s+gains?\\s+(?<traits>.+?)\\s+until\\s+(?:the\\s+)?end\\s+of\\s+" +
        "(?:the\\s+)?turn[.!]?$"
    );
    static final Pattern STANDALONE_NAMED_CANNOT_BE_CHOSEN_ANY_SUMMON = Pattern.compile(
        "(?i)(?<name>[A-Z][A-Za-z''\\-\\s()]+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+(?!your\\s)Summons?" +
        "(?:\\s+during\\s+this\\s+turn)?\\s*(?=[.!\"]|$)"
    );
    /**
     * "Name 1 Element. During this turn, [CardName] cannot be chosen by Summons or abilities of the named
     * Element and if [CardName] is dealt damage by a Summon or an ability of the named Element, the damage
     * becomes 0 instead." — targeting immunity AND damage nullification for the named element.
     */
    static final Pattern STANDALONE_NAME_ELEMENT_IMMUNE_AND_NULLIFY_DAMAGE = Pattern.compile(
        "(?i)Name\\s+1\\s+Element\\.\\s+During\\s+this\\s+turn,\\s+" +
        "(?<name>[A-Z][A-Za-z''\\-\\s()]+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+Summons?\\s+or\\s+abilities\\s+of\\s+the\\s+named\\s+Element" +
        "\\s+and\\s+if\\s+[A-Za-z''\\-\\s()]+?is\\s+dealt\\s+damage\\s+by\\s+a\\s+Summon\\s+or\\s+an\\s+ability\\s+of\\s+the\\s+named\\s+Element,\\s+" +
        "the\\s+damage\\s+becomes\\s+0\\s+instead\\s*\\.?"
    );
    /**
     * "Name 1 Element. During this turn, if [CardName] is dealt damage by abilities of the named
     * Element, the damage becomes 0 instead." — (Rubicante-style) damage-only nullification,
     * scoped to abilities alone (Summons are not covered), with no targeting immunity.
     */
    static final Pattern STANDALONE_NAME_ELEMENT_NULLIFY_ABILITY_DAMAGE_ONLY = Pattern.compile(
        "(?i)Name\\s+1\\s+Element\\.\\s+During\\s+this\\s+turn,\\s+if\\s+" +
        "(?<name>[A-Z][A-Za-z''\\-\\s()]+?)\\s+is\\s+dealt\\s+damage\\s+by\\s+abilities\\s+of\\s+the\\s+named\\s+Element,\\s+" +
        "the\\s+damage\\s+becomes\\s+0\\s+instead\\s*\\.?"
    );
    /**
     * "Name 1 Element. [CardName] cannot be chosen by Summons or abilities of the named Element this turn."
     * Action ability: the player names an element, and the card gains immunity to that element this turn.
     */
    static final Pattern STANDALONE_NAME_ELEMENT_AND_IMMUNE = Pattern.compile(
        "(?i)Name\\s+1\\s+Element\\.\\s+" +
        "(?<name>[A-Z][A-Za-z''\\-\\s()]+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+Summons?\\s+or\\s+abilities\\s+of\\s+the\\s+named\\s+Element\\s+this\\s+turn\\s*\\.?"
    );
    /**
     * "[CardName] cannot be chosen by Summons or abilities that share its Element."
     * Passive field ability: immunity is checked dynamically against the resolving card's element.
     */
    static final Pattern STANDALONE_NAMED_CANNOT_BE_CHOSEN_BY_OWN_ELEMENT = Pattern.compile(
        "(?i)(?<name>[A-Z][A-Za-z''\\-\\s()]+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+Summons?\\s+or\\s+abilities\\s+that\\s+share\\s+its\\s+Element\\s*\\.?"
    );
    /**
     * "[CardName] cannot be chosen by [Element] Summons or [Element] abilities." (Royal Ripeness
     * 5-007H.) Passive field ability: the immunity is checked per resolution against the resolving
     * card's Elements.
     *
     * <p>The Element is captured rather than read off the carrier. Royal Ripeness names its own —
     * a Fire Monster shielded from Fire — but nothing in the wording requires that, and reading
     * the card instead would follow an Element override the text never mentions. That is exactly
     * the difference from {@link #STANDALONE_NAMED_CANNOT_BE_CHOSEN_BY_OWN_ELEMENT}, whose "share
     * its Element" does track the carrier, and from {@link #STANDALONE_NAME_ELEMENT_AND_IMMUNE},
     * where a player picks the Element on resolution.
     *
     * <p>The second Element is optional so "by Fire Summons or abilities" reads the same way, and
     * the backreference keeps a mismatched pair ("Fire Summons or Ice abilities") out — no such
     * printing exists, and one would mean two immunities rather than this one.
     *
     * <p>No player is named, so the shield binds whoever is choosing, the card's own controller
     * included.
     */
    static final Pattern STANDALONE_NAMED_CANNOT_BE_CHOSEN_BY_ELEMENT = Pattern.compile(
        "(?i)(?<name>[A-Z][A-Za-z''\\-\\s()]+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+" +
        "(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Summons?\\s+or\\s+" +
        "(?:\\k<element>\\s+)?abilit(?:y|ies)\\s*(?=[.!\"]|$)"
    );
    /**
     * "[CardName] cannot be chosen by a Multi-Element Forward's ability." (Kam'lanaut 18-072C.)
     *
     * <p>Passive field ability, and the narrowest immunity in the family: it reads the resolving
     * card rather than the target, and both halves of that reading matter — the source must be a
     * Forward and must carry more than one Element. A Summon is not a Forward, so it is never
     * blocked here no matter how many Elements it has.
     *
     * <p>No player is named, so the shield binds whoever is choosing, the card's own controller
     * included.
     */
    static final Pattern STANDALONE_NAMED_CANNOT_BE_CHOSEN_BY_MULTI_ELEMENT_FORWARD = Pattern.compile(
        "(?i)(?<name>[A-Z][A-Za-z''\\-\\s()]+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+" +
        "a\\s+Multi-Element\\s+Forward(?:'s|s')\\s+abilit(?:y|ies)\\s*\\.?"
    );
    /**
     * "The Job X [other than Y] Forwards/Characters you control cannot be chosen by
     * your opponent's Summons/abilities."
     * Group {@code job} is the job name; {@code excl} is the optional excluded card name.
     */
    static final Pattern STANDALONE_JOB_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)The\\s+Job\\s+(?<job>[^.]+?)(?:\\s+other\\s+than\\s+(?<excl>[^.]+?))?" +
        "\\s+(?:Forwards?|Characters?)\\s+you\\s+control\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "(?<scope>Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );
    /**
     * "Players cannot cast Summons." — global static restriction while this card is on the field.
     * Both players are prevented from casting Summons from hand or break zone.
     */
    static final Pattern PLAYERS_CANNOT_CAST_SUMMONS = Pattern.compile(
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
     * "All cards in your Break Zone cannot be removed from the game by your opponent's Summons or
     * abilities." — Lenna 18-100L, Ultimecia 22-073L.
     *
     * <p>{@link #FA_BZ_SUMMONS_PROTECTED_FROM_OPP_RFG} with the card type widened from Summons to
     * everything; the two are otherwise the same sentence and are enforced together, the narrow one
     * asking additionally whether the card being removed is a Summon.
     *
     * <p>Distinct from {@link #FA_BZ_CARDS_PROTECTED_FROM_OPP_CHOICE}, which covers the same cards
     * against a different verb. That one stops the opponent <em>choosing</em> a Break Zone card at
     * all, and so does nothing against a sweep that names no card; this one stops the removal
     * itself, chosen or not.
     */
    static final Pattern FA_BZ_CARDS_PROTECTED_FROM_OPP_RFG = Pattern.compile(
        "(?i)All\\s+cards\\s+in\\s+your\\s+Break\\s+Zone\\s+cannot\\s+be\\s+removed\\s+from\\s+the\\s+game\\s+" +
        "by\\s+your\\s+opponent.?s\\s+(?:Summons?\\s+or\\s+)?abilit(?:y|ies)[.!]?"
    );
    /**
     * "All cards in your Break Zone cannot be chosen by your opponent's Summons or abilities."
     * (Kalmia 18-090R.) Wider than {@link #FA_BZ_SUMMONS_PROTECTED_FROM_OPP_RFG} on both axes:
     * every card type rather than Summons alone, and every way an opponent's effect could choose
     * one rather than removal from the game specifically.
     *
     * <p>"Chosen" is the operative word. An effect that takes the whole zone without choosing —
     * "remove all cards in your opponent's Break Zone from the game" — is not stopped by this,
     * because it never chooses anything.
     */
    static final Pattern FA_BZ_CARDS_PROTECTED_FROM_OPP_CHOICE = Pattern.compile(
        "(?i)All\\s+cards\\s+in\\s+your\\s+Break\\s+Zone\\s+cannot\\s+be\\s+chosen\\s+by\\s+" +
        "your\\s+opponent.?s\\s+(?:Summons?\\s+or\\s+)?abilit(?:y|ies)[.!]?"
    );
    /**
     * "[CardName] cannot become dull by your opponent's Summons or abilities."
     * Permanent self-protection while this card is on the field.
     */
    static final Pattern STANDALONE_NAMED_CANNOT_BECOME_DULL_OPP = Pattern.compile(
        "(?i)(?<name>[A-Z][A-Za-z''\\-\\s()]+?)\\s+cannot\\s+become\\s+dull\\s+by\\s+your\\s+opponent's\\s+" +
        "(?:Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );
    /**
     * "[CardName] cannot be returned to its owner's hand by [your] opponent's Summons or abilities."
     * Permanent self-protection while this card is on the field (Gilgamesh).
     */
    static final Pattern STANDALONE_NAMED_CANNOT_BE_RETURNED_TO_HAND_OPP = Pattern.compile(
        "(?i)(?<name>[A-Z][A-Za-z''\\-\\s()]+?)\\s+cannot\\s+be\\s+returned\\s+to\\s+(?:its|their)\\s+owner's\\s+hand" +
        "\\s+by\\s+(?:your\\s+)?opponent's\\s+(?:Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );
    /**
     * "Characters you control cannot be returned to their owner's hand by your opponent's
     * Summons or abilities." — blanket protection for every character the controller controls
     * while this card is on the field.
     */
    static final Pattern STANDALONE_CHARACTERS_CANNOT_BE_RETURNED_TO_HAND_OPP = Pattern.compile(
        "(?i)Characters\\s+you\\s+control\\s+cannot\\s+be\\s+returned\\s+to\\s+their\\s+owner's\\s+hand" +
        "\\s+by\\s+(?:your\\s+)?opponent's\\s+(?:Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );
    /**
     * "[CardName] cannot be put into the Break Zone by [your] opponent's Summons or abilities."
     * Permanent self-protection while this card is on the field (Black Tortoise l'Cie Gilgamesh).
     */
    static final Pattern STANDALONE_NAMED_CANNOT_BE_PUT_INTO_BZ_OPP = Pattern.compile(
        "(?i)(?<name>[A-Z][A-Za-z''\\-\\s()]+?)\\s+cannot\\s+be\\s+put\\s+into\\s+the\\s+Break\\s+Zone" +
        "\\s+by\\s+(?:your\\s+)?opponent's\\s+(?:Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );
    /** "Negate all [the] damage dealt to all the Forwards/Characters you control." */
    static final Pattern STANDALONE_NEGATE_DAMAGE_OWN = Pattern.compile(
        "(?i)Negate\\s+all\\s+(?:the\\s+)?damage\\s+dealt\\s+to\\s+all\\s+the\\s+" +
        "(?:Forwards?|Characters?)\\s+you\\s+control\\.?"
    );
    /**
     * "Activate all the Forwards/Characters you control and negate all [the] damage dealt to them."
     * Handled by {@link #tryParseNegateAllDamage} before {@link #tryParseAllFieldEffect}
     * so that the "activate all" part does not consume the full text without the negate clause.
     */
    static final Pattern STANDALONE_ACTIVATE_AND_NEGATE_DAMAGE_OWN = Pattern.compile(
        "(?i)Activate\\s+all\\s+the\\s+(?:Forwards?|Characters?)\\s+you\\s+control" +
        "\\s+and\\s+negate\\s+all\\s+(?:the\\s+)?damage\\s+dealt\\s+to\\s+them\\.?"
    );
    /** "During this turn, if a Forward you control is dealt damage less than its power, the damage becomes 0 instead." */
    static final Pattern STANDALONE_NONLETHAL_PROTECTION = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+a\\s+Forward\\s+you\\s+control\\s+is\\s+dealt\\s+damage\\s+less\\s+than\\s+its\\s+power,\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?"
    );
    /** "During this turn, if a Forward you control is dealt damage, reduce the damage by N instead." */
    static final Pattern STANDALONE_GLOBAL_DMG_REDUCTION = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+a\\s+Forward\\s+you\\s+control\\s+is\\s+dealt\\s+damage,\\s+reduce\\s+the\\s+damage\\s+by\\s+(?<reduction>\\d+)\\s+instead\\.?"
    );
    /**
     * "During this turn, if &lt;cardName&gt; is dealt damage by your opponent's Summons or abilities,
     * the damage becomes 0 instead."
     */
    static final Pattern STANDALONE_NULLIFY_ABILITY_DAMAGE = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+(?<card>.+?)\\s+is\\s+dealt\\s+damage\\s+by\\s+your\\s+opponent's\\s+Summons?\\s+or\\s+abilities,\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?"
    );
    /**
     * "During this turn, the next damage dealt to [name] becomes 0 instead."
     * "The next damage dealt to Card Name [name] becomes 0 this turn."
     */
    static final Pattern STANDALONE_SHIELD_NEXT_DMG_ZERO_NAMED = Pattern.compile(
        "(?i)(?:During\\s+this\\s+turn,\\s+)?the\\s+next\\s+damage\\s+dealt\\s+to\\s+(?!(?:it|him|them)\\b)(?:Card\\s+Name\\s+)?(?<name>[A-Za-z][^.]+?)\\s+becomes\\s+0\\s+(?:instead|this\\s+turn)[.!]?"
    );
    /** "During this turn, the next damage dealt to [name] is reduced by N instead." — named card, not pronoun. */
    static final Pattern STANDALONE_SHIELD_NEXT_DMG_REDUCTION_NAMED = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+(?!(?:it|them)\\b)(?<name>[A-Za-z][^.]+?)\\s+is\\s+reduced\\s+by\\s+(?<reduction>\\d+)\\s+instead[.!]?"
    );
    /** "The damage dealt to Forwards opponent controls cannot be reduced this turn." */
    static final Pattern STANDALONE_DISABLE_OPPONENT_DMG_REDUCTION = Pattern.compile(
        "(?i)The\\s+damage\\s+dealt\\s+to\\s+Forwards?\\s+(?:your\\s+)?opponent\\s+controls\\s+cannot\\s+be\\s+reduced\\s+this\\s+turn\\.?"
    );
    /** "This damage cannot be reduced." — modifier on a preceding damage sentence. */
    static final Pattern CANNOT_BE_REDUCED_PATTERN = Pattern.compile(
        "(?i)This\\s+damage\\s+cannot\\s+be\\s+reduced[.!]?"
    );

    // =========================================================================================
    // Activation, extra attacks and search denial
    // =========================================================================================
    /**
     * Matches "Activate &lt;cardName&gt;[.]" as a standalone named-card activate effect.
     * Also handles "Activate Card Name X [and Card Name Y] [you control]" for
     * multi-target Card Name notation.
     * Excludes the pronoun forms ("Activate it/them") and the mass form ("Activate all …"),
     * which are handled separately.
     */
    static final Pattern ACTIVATE_NAMED_CARD = Pattern.compile(
        "(?i)Activate\\s+(?!(?:it|them|all)\\b)(?<card>[A-Za-z][^.]+?)\\.?\\s*$"
    );
    /** Matches "[name] can attack once more this turn." */
    static final Pattern ATTACK_ONCE_MORE = Pattern.compile(
        "(?i)^(?<name>[A-Za-z][^.]+?)\\s+can\\s+attack\\s+once\\s+more\\s+this\\s+turn[.!]?"
    );
    /** Matches "During this turn, your opponent may only declare attack once." */
    static final Pattern OPPONENT_ATTACK_ONCE_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,?\\s+your\\s+opponent\\s+may\\s+only\\s+declare\\s+attack\\s+once\\.?"
    );
    static final Pattern OPPONENT_CANNOT_SEARCH_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,?\\s+your\\s+opponent\\s+cannot\\s+search\\.?"
    );
    /**
     * "During this turn, your opponent cannot cast any cards." — Vayne 28-117H's parting shot, the
     * corpus's only total cast prohibition.
     *
     * <p>"any cards" is required rather than optional, which is what keeps this off the narrower
     * printing beside it: 18-106H's "During this turn, your opponent cannot cast Summons." bans one
     * card type and is a different, unimplemented effect. Reading it as this one would ban
     * everything, so it stays visibly unhandled instead.
     */
    static final Pattern OPPONENT_CANNOT_CAST_ANY_CARDS_THIS_TURN = Pattern.compile(
        "(?i)^During\\s+this\\s+turn,?\\s+your\\s+opponent\\s+cannot\\s+cast\\s+any\\s+cards\\s*[.!]?$"
    );
    /** Splits "and Card Name" within an activate target list. */
    static final Pattern ACTIVATE_AND_CARD_NAME_SPLIT = Pattern.compile(
        "(?i)\\s+and\\s+Card\\s+Name\\s+"
    );
    /** Matches "Remove &lt;cardName&gt; from [the] Battle." — Escape-type ability effect. */
    static final Pattern REMOVE_FROM_BATTLE = Pattern.compile(
        "(?i)Remove\\s+(?<card>.+?)\\s+from\\s+(?:the\\s+)?Battle\\.?\\s*$"
    );
    /**
     * Matches "name 1 card type. Then, your opponent discard 1 card.
     * If the discarded card is the named card type, you draw 1 card."
     */
    static final Pattern NAME_CARD_TYPE_OPP_DISCARD_DRAW_IF_MATCH = Pattern.compile(
        "(?i)name\\s+1\\s+card\\s+type[.!]?\\s+Then,?\\s+your\\s+opponent\\s+discards?\\s+1\\s+card[.!]?\\s+" +
        "If\\s+the\\s+discarded\\s+card\\s+is\\s+the\\s+named\\s+card\\s+type,\\s+you\\s+draw\\s+1\\s+card[.!]?"
    );

    // =========================================================================================
    // Each-player and opponent-directed effects
    // =========================================================================================
    static final Pattern OPPONENT_DISCARD = Pattern.compile(
        "(?i)Your\\s+opponent\\s+discards?\\s+(\\d+)\\s+cards?" +
        "(?:\\s+from\\s+(?:his/her|his|her|their)\\s+hand)?[.!]?"
    );
    /** Matches "Each player discards N card(s) [from his/her/their hand]". Group {@code count} = N. */
    static final Pattern EACH_PLAYER_DISCARD = Pattern.compile(
        "(?i)each\\s+player\\s+discards?\\s+(?<count>\\d+)\\s+cards?" +
        "(?:\\s+from\\s+(?:his/her|his|her|their)\\s+hand)?[.!]?"
    );
    /** Matches "Each player draws N card(s)." Group {@code count} = N. */
    static final Pattern EACH_PLAYER_DRAW = Pattern.compile(
        "(?i)each\\s+player\\s+draws?\\s+(?<count>\\d+)\\s+cards?[.!]?"
    );
    /**
     * Matches "Each player selects N [card|Forward|Backup|Monster|Character](s) from their Break
     * Zone and adds it/them to their hand." — Cu Chaspel 18-021R (any card), Serafie 1-109R
     * (Forwards only).
     * <ul>
     *   <li>Group {@code count} — N</li>
     *   <li>Group {@code type}  — the card-type filter; "card" means no restriction</li>
     * </ul>
     */
    static final Pattern EACH_PLAYER_SALVAGE_FROM_BREAK_ZONE = Pattern.compile(
        "(?i)each\\s+player\\s+selects?\\s+(?<count>\\d+)\\s+" +
        "(?<type>card|Forward|Backup|Monster|Character)s?\\s+from\\s+" +
        "(?:their|his/her|his|her)\\s+Break\\s+Zone\\s+and\\s+adds?\\s+(?:it|them)\\s+to\\s+" +
        "(?:their|his/her|his|her)\\s+hand[.!]?"
    );
    /**
     * Matches "select N [Forward|Backup|Monster|Character] in/from your Break Zone and add it to your hand."
     * Group {@code count} = N; {@code type} = card type word.
     */
    static final Pattern SELECT_CHARACTER_FROM_BZ_TO_HAND = Pattern.compile(
        "(?i)^select\\s+(?<count>\\d+)\\s+(?<type>Forward|Backup|Monster|Character)s?" +
        "\\s+(?:in|from)\\s+your\\s+Break\\s+Zone\\s+and\\s+add\\s+it\\s+to\\s+your\\s+hand[.!]?$"
    );
    /** Ceodore: "Choose 1 Card with Warp in your Break Zone. Add it to your hand." */
    static final Pattern CHOOSE_WARP_CARD_FROM_BZ_TO_HAND = Pattern.compile(
        "(?i)^choose\\s+1\\s+Card\\s+with\\s+Warp\\s+(?:in|from)\\s+your\\s+Break\\s+Zone[.!]?\\s+" +
        "Add\\s+it\\s+to\\s+your\\s+hand[.!]?$"
    );
    /**
     * Matches "Each player who doesn't control N or more Forwards discards M card(s) [from their hand]."
     * Groups: {@code min} — forward threshold; {@code count} — cards to discard.
     */
    static final Pattern EACH_PLAYER_WHO_DOESNT_CONTROL_FORWARDS_DISCARD = Pattern.compile(
        "(?i)each\\s+player\\s+who\\s+doesn't\\s+control\\s+(?<min>\\d+)\\s+or\\s+more\\s+Forwards?" +
        "\\s+discards?\\s+(?<count>\\d+)\\s+cards?" +
        "(?:\\s+from\\s+(?:his/her|his|her|their)\\s+hand)?[.!]?"
    );
    /**
     * Matches the compound form "Each player discards N cards. If you control [Card Name (X)] /
     * Card Name X, your opponent discards M more cards [from his/her/their hand]".
     * Groups: {@code count}, {@code bracketname} or {@code plainname}, {@code extra}.
     */
    static final Pattern EACH_PLAYER_DISCARD_WITH_CONDITIONAL = Pattern.compile(
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
    static final Pattern EACH_PLAYER_SELECT_FORWARD_DAMAGE = Pattern.compile(
        "(?i)each\\s+player\\s+selects?\\s+1\\s+Forward\\s+they\\s+control[.!]?\\s+" +
        "Deal\\s+them\\s+(?<amount>\\d+)\\s+damage[.!]?"
    );
    /**
     * Matches "Both players select 1 Forward they control and put it into the Break Zone."
     * Used for Famfrit-style EX Burst effects where each side simultaneously sends one Forward to the Break Zone.
     */
    static final Pattern BOTH_PLAYERS_SELECT_FORWARD_TO_BREAK_ZONE = Pattern.compile(
        "(?i)(?:Both|Each)\\s+players?\\s+selects?\\s+1\\s+Forward\\s+they\\s+control" +
        "\\s+and\\s+puts?\\s+it\\s+into\\s+the\\s+Break\\s+Zone[.!]?"
    );
    /**
     * Matches "select 1 [type] of cost N or less other than [name] you control. Put it into the Break Zone."
     * Groups: {@code type}, {@code costval}, {@code excludename}.
     */
    static final Pattern SELECT_1_CHAR_COST_LE_EXCL_TO_BZ = Pattern.compile(
        "(?i)^[Ss]elect\\s+1\\s+(?<type>Forward|Backup|Monster|Character)\\s+of\\s+cost\\s+(?<costval>\\d+)\\s+or\\s+less\\s+" +
        "other\\s+than\\s+(?<excludename>.+?)\\s+you\\s+control[.!]?\\s+Put\\s+it\\s+into\\s+the\\s+Break\\s+Zone[.!]?$"
    );
    static final Pattern SELECT_1_CHARACTER_YOU_CONTROL_TO_BZ = Pattern.compile(
        "(?i)^[Ss]elect\\s+1\\s+(?<type>Forward|Backup|Monster|Character)\\s+you\\s+control[.!]?\\s+Put\\s+it\\s+into\\s+the\\s+Break\\s+Zone[.!]?$"
    );
    /**
     * Matches "Each player selects up to N Forwards or Monsters he/she/they controls/control
     * (select as many as possible). Put them into the Break Zone."
     * Groups: {@code count} — max per player; {@code targets} — card type(s).
     */
    static final Pattern EACH_PLAYER_SELECT_UP_TO_N_TO_BREAK_ZONE = Pattern.compile(
        "(?i)Each\\s+player\\s+selects?\\s+up\\s+to\\s+(?<count>\\d+)\\s+" +
        "(?<targets>Forwards?(?:\\s+(?:and/or|or)\\s+(?:Monsters?|Backups?))?|Monsters?|Characters?)\\s+" +
        "(?:he/she|they)\\s+controls?\\s*" +
        "(?:\\(select\\s+as\\s+many\\s+as\\s+possible\\)[.!]?\\s*)?" +
        "Put\\s+them\\s+into\\s+the\\s+Break\\s+Zone[.!]?"
    );
    /**
     * The dull-and-Freeze sibling of {@link #EACH_PLAYER_SELECT_UP_TO_N_TO_BREAK_ZONE} — Cloud of
     * Darkness 10-028L's "each player selects up to 2 active Characters he/she controls (select as
     * many as possible). Dull them and Freeze them.", the only printing of this shape.
     *
     * <p>"up to N" with "(select as many as possible)" behind it is not a choice about how many:
     * both players take min(N, eligible), and the only choice is which. The parenthetical is what
     * says so, so it is required here rather than optional as it is in the sibling above.
     *
     * <p>"active" is a state filter on the pool, not decoration: a card already dull cannot be
     * selected, which is what keeps a second attack in the same turn from finding the same
     * Characters again. Groups: {@code count}, {@code targets}.
     */
    static final Pattern EACH_PLAYER_SELECT_UP_TO_N_ACTIVE_DULL_FREEZE = Pattern.compile(
        "(?i)Each\\s+player\\s+selects?\\s+up\\s+to\\s+(?<count>\\d+)\\s+active\\s+" +
        "(?<targets>Characters?|Forwards?|Backups?|Monsters?)\\s+" +
        "(?:he/she|they)\\s+controls?\\s*" +
        "\\(select\\s+as\\s+many\\s+as\\s+possible\\)[.!]?\\s*" +
        "Dull\\s+them\\s+and\\s+Freeze\\s+them[.!]?"
    );
    /**
     * Matches "Your opponent selects up to N Forwards they control. Then, put all the Forwards
     * opponent controls other than the selected Forwards into the Break Zone." — Cloud of Darkness
     * 25-092C, where the opponent's picks are the ones that <em>survive</em>.
     *
     * <p>Both sentences are read together rather than left to the sentence splitter, and it has to
     * be that way round: the second reads on its own as an unbounded "put all the Forwards opponent
     * controls into the Break Zone", so a split resolves it with no selection in front of it and
     * takes the row entire.
     *
     * <p>Kept apart from {@link #OPPONENT_SELECTS_PATTERN}, whose followup acts <em>on</em> what the
     * opponent picked. That one requires a bare count and so does not reach "up to N"; the
     * distinction is worth keeping anyway, because the two readings of this text are opposites.
     * Group: {@code count}.
     */
    static final Pattern OPP_SELECTS_UP_TO_N_FORWARDS_BREAK_REST = Pattern.compile(
        "(?is)^Your\\s+opponent\\s+selects?\\s+up\\s+to\\s+(?<count>\\d+)\\s+Forwards?\\s+" +
        "(?:they|he\\s*/\\s*she|he|she)\\s+controls?[.!]\\s*" +
        "(?:Then,?\\s+)?[Pp]ut\\s+all\\s+(?:the\\s+)?Forwards?\\s+" +
        "(?:(?:your\\s+)?opponent\\s+controls?\\s+)?" +
        "other\\s+than\\s+the\\s+selected\\s+Forwards?\\s+into\\s+the\\s+Break\\s+Zone[.!]?\\s*$"
    );
    /**
     * The two-sided sibling of {@link #OPP_SELECTS_UP_TO_N_FORWARDS_BREAK_REST}: "Each player
     * selects N Forwards they control." followed by a sentence sweeping every Forward that was not
     * selected — on <em>both</em> rows, the controller's included — into the Break Zone. Cloud of
     * Darkness 1-158H and 18-091R, the only two printings, and the whole of the family with its
     * opponent-only cousin above.
     *
     * <p>One pattern for both because they differ only in how the sweep is phrased: 18-091R gives
     * it as an imperative ("Then, put all the Forwards other than the selected Forwards into the
     * Break Zone.") and 1-158H in the passive ("All the Forwards that were not selected are put
     * into the Break Zone."). Neither says whose Forwards, and that silence is the effect — it is
     * what separates these two from 25-092C, which spells out "opponent controls".
     *
     * <p>Read as one pattern for the reason its cousin is: either sweep reads on its own as an
     * unbounded "every Forward into the Break Zone", so a sentence split resolves it with no
     * selection in front of it and empties the board.
     *
     * <p>{@code upto} is carried because the wording admits it, though neither printing uses it —
     * both say a plain "1", which is a selection the player must make rather than one they may
     * decline. Group: {@code count}.
     */
    static final Pattern EACH_PLAYER_SELECTS_FORWARDS_BREAK_REST = Pattern.compile(
        "(?is)^Each\\s+player\\s+selects?\\s+(?<upto>up\\s+to\\s+)?(?<count>\\d+)\\s+Forwards?\\s+" +
        "(?:they|he\\s*/\\s*she|he|she)\\s+controls?[.!]\\s*" +
        "(?:" +
            "(?:Then,?\\s+)?put\\s+all\\s+(?:the\\s+)?Forwards?\\s+" +
            "other\\s+than\\s+the\\s+selected\\s+Forwards?\\s+into\\s+the\\s+Break\\s+Zone" +
        "|" +
            "All\\s+(?:the\\s+)?Forwards?\\s+that\\s+(?:were|was)\\s+not\\s+selected\\s+" +
            "(?:are|is)\\s+put\\s+into\\s+the\\s+Break\\s+Zone" +
        ")[.!]?\\s*$"
    );
    /**
     * Matches "Each player reveals the top card of his/her deck. Each player who revealed a
     * [type] may play it onto the field." Group {@code type} = card type condition.
     */
    static final Pattern EACH_PLAYER_REVEAL_CHARACTER_MAY_PLAY = Pattern.compile(
        "(?i)^\\s*Each\\s+player\\s+reveals?\\s+the\\s+top\\s+card\\s+of\\s+" +
        "(?:his/her|his|her|their)\\s+deck[.!]?\\s+" +
        "Each\\s+player\\s+who\\s+revealed\\s+(?:a\\s+)?(?<type>Forward|Backup|Character|Monster)\\s+" +
        "may\\s+play\\s+it\\s+onto\\s+the\\s+field[.!]?\\s*$"
    );
    /**
     * Matches "each player may search for N Forward(s) of power X or more and add it/them to his/her hand."
     * Groups: {@code count}, {@code power}.
     */
    static final Pattern EACH_PLAYER_MAY_SEARCH_FORWARD_MIN_POWER = Pattern.compile(
        "(?i)^\\s*each\\s+player\\s+may\\s+search\\s+for\\s+(?<count>\\d+)\\s+Forwards?\\s+" +
        "of\\s+power\\s+(?<power>\\d+)\\s+or\\s+more\\s+and\\s+add\\s+it(?:/them|s)?\\s+to\\s+" +
        "(?:his/her|his|her|their)\\s+hand[.!]?\\s*$"
    );

    // =========================================================================================
    // Discard and mill
    // =========================================================================================
    /** Matches "Discard your hand. Then, draw N card(s)." Group 1 = draw count. */
    static final Pattern DISCARD_HAND_THEN_DRAW = Pattern.compile(
        "(?i)Discard\\s+your\\s+hand[.,]?\\s+[Tt]hen[,]?\\s+draw\\s+(\\d+)\\s+cards?[.!]?\\s*$"
    );
    /** Matches "Discard your hand." as a standalone effect. */
    static final Pattern DISCARD_HAND = Pattern.compile(
        "(?i)Discard\\s+your\\s+hand[.!]?\\s*$"
    );
    /**
     * Matches "discard 1 &lt;Type&gt;." — player discards one card of the named type from hand.
     * Used as the primary clause in "discard 1 X. When you do so, Y." sequences.
     * The "you may" qualifier is stripped by the AutoAbility parser before this is reached.
     */
    static final Pattern DISCARD_TYPE = Pattern.compile(
        "(?i)discard\\s+1\\s+(?<type>Summon|Forward|Backup|Monster|Character)[.!]?"
    );
    /**
     * Matches "[You may] discard 1 Job [X] [from your hand][.]" — a discard of one card carrying
     * the named Job.
     *
     * <p>Both halves are optional because the corpus prints all four combinations, and spelling
     * them out is what reaches the six "you may discard 1 Job …" printings: the Chaos cycle
     * (14-018C, 14-048C, 14-076C, 14-104C), 24-104R Mog (VI) and 29-112C Raz. Without the
     * "you may" alternative their opening clause did not parse, so the "When you do so, …"
     * sequence parser declined and the whole ability fell through to whichever later parser
     * matched its second sentence — the three Chaos cards with a target clause resolved that
     * clause with no discard demanded at all, and the other three did nothing.
     *
     * <p>{@code group("optional")} is non-null for the "you may" spelling, which is what decides
     * whether the discard is offered or required.
     */
    static final Pattern DISCARD_JOB_FROM_HAND = Pattern.compile(
        "(?i)^(?<optional>you\\s+may\\s+)?discard\\s+1\\s+Job\\s+(?<job>.+?)" +
        "(?:\\s+from\\s+your\\s+hand)?[.!]?$"
    );
    /** Matches "You may discard 1 &lt;element&gt; card" — player may optionally discard a card matching the element. */
    static final Pattern DISCARD_ELEMENT_FROM_HAND = Pattern.compile(
        "(?i)^(?:you\\s+may\\s+)?discard\\s+1\\s+(?<element>Multi-Element|Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+card(?:\\s+from\\s+your\\s+hand)?[.!]?$"
    );
    /** Matches "Your opponent randomly discards N card(s) [from his/her/their hand]". Group 1 = count. */
    static final Pattern OPPONENT_RANDOM_DISCARD = Pattern.compile(
        "(?i)Your\\s+opponent\\s+randomly\\s+discards?\\s+(\\d+)\\s+cards?" +
        "(?:\\s+from\\s+(?:his/her|his|her|their)\\s+hand)?[.!]?"
    );
    /**
     * Matches "Your opponent draws N card(s), then randomly discards M card(s)".
     * Group 1 = draw count, Group 2 = discard count.
     */
    static final Pattern OPPONENT_DRAW_THEN_RANDOM_DISCARD = Pattern.compile(
        "(?i)Your\\s+opponent\\s+draws?\\s+(\\d+)\\s+cards?[,.]?\\s+then\\s+randomly\\s+discards?\\s+(\\d+)\\s+cards?[.!]?"
    );
    /** Matches "Your opponent draws N card(s)." — simple opponent draw with no followup. */
    static final Pattern OPPONENT_DRAW = Pattern.compile(
        "(?i)Your\\s+opponent\\s+draws?\\s+(\\d+)\\s+cards?[.!]?$"
    );
    /**
     * Matches "Your opponent selects N [condition] [element] [type] [of cost C or less/more]
     * they control [sep] followup".
     * <ul>
     *   <li>Group {@code count}     — number of cards the opponent must select</li>
     *   <li>Group {@code condition} — optional state filter</li>
     *   <li>Group {@code element}   — optional element filter</li>
     *   <li>Group {@code targets}   — card type(s)</li>
     *   <li>Group {@code cost}      — optional cost threshold</li>
     *   <li>Group {@code costcmp}   — {@code less} or {@code more}; both are inclusive of {@code cost}</li>
     *   <li>Group {@code followup}  — action applied to the selected card(s)</li>
     * </ul>
     */
    static final Pattern OPPONENT_SELECTS_PATTERN = Pattern.compile(
        "(?i)^Your\\s+opponent\\s+selects?\\s+(?<count>\\d+)\\s+" +
        "(?:(?<condition>dull|damaged|attacking|blocking|active)\\s+)?" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<targets>(?:Forwards?|Backups?|Characters?|Monsters?)(?:\\s+(?:and/or|or|and)\\s+(?:Forwards?|Backups?|Characters?|Monsters?))?)" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+(?<costcmp>less|more))?" +
        "\\s+(?:they|he/she|he|she)\\s+controls?" +
        "(?:[.]\\s*|\\s+and\\s+)" +
        "(?<followup>.+)",
        Pattern.DOTALL
    );
    /**
     * Ardyn 8-068L: "Your opponent selects 1 Character he/she controls. He/she may put it into the
     * Break Zone. If he/she does so, [CardName] cannot block this turn."
     *
     * <p>Must precede {@link #OPPONENT_SELECTS_PATTERN} in every dispatch chain. That one matches
     * this text too — its {@code followup} group swallows all three remaining sentences and its
     * {@code FOLLOWUP_PUT_TO_BREAK_ZONE} check then finds "put it into the Break Zone" inside them —
     * and it would resolve as a <em>forced</em> break with the block restriction dropped entirely,
     * turning the opponent's option into the printing card's unconditional removal effect.
     * <ul>
     *   <li>Group {@code count}   — number of Characters selected</li>
     *   <li>Group {@code targets} — card type(s) the opponent selects from</li>
     *   <li>Group {@code card}    — the card that cannot block; checked against the carrier's name</li>
     * </ul>
     */
    /**
     * Ardyn 28-002R: "If that player doesn't put 1 Character they control into the Break Zone,
     * [CardName] deals that player 1 point of damage."
     *
     * <p>"That player" is the turn player, named by the trigger this effect hangs off — a Main
     * Phase 1 trigger that fires on both players' turns — so the chooser and the recipient are the
     * same seat and it may be either side of the table. That is what separates it from the
     * opponent-selects family, whose chooser is always the resolving player's opponent.
     * Groups: {@code count}, {@code targets}, {@code card}, {@code amount}.
     */
    static final Pattern TURN_PLAYER_BREAKS_OR_TAKES_DAMAGE = Pattern.compile(
        "(?i)^If\\s+that\\s+player\\s+doesn'?t\\s+put\\s+(?<count>\\d+)\\s+" +
        "(?<targets>Forwards?|Backups?|Monsters?|Characters?)\\s+" +
        "(?:they|he\\s*/\\s*she|he|she)\\s+controls?\\s+into\\s+the\\s+Break\\s+Zone,\\s+" +
        "(?<card>.+?)\\s+deals\\s+that\\s+player\\s+(?<amount>\\d+)\\s+points?\\s+of\\s+damage[.!]?$",
        Pattern.DOTALL
    );
    static final Pattern OPP_SELECTS_MAY_BREAK_ELSE_SELF_CANNOT_BLOCK = Pattern.compile(
        "(?i)^Your\\s+opponent\\s+selects?\\s+(?<count>\\d+)\\s+" +
        "(?<targets>Forwards?|Backups?|Characters?|Monsters?)\\s+" +
        "(?:they|he\\s*/\\s*she|he|she)\\s+controls?[.!]\\s*" +
        "(?:They|He\\s*/\\s*She|He|She)\\s+may\\s+put\\s+it\\s+into\\s+the\\s+Break\\s+Zone[.!]\\s*" +
        "If\\s+(?:they|he\\s*/\\s*she|he|she)\\s+(?:does|do)\\s+so,\\s+" +
        "(?<card>.+?)\\s+cannot\\s+block\\s+this\\s+turn[.!]?$",
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
    static final Pattern OPPONENT_PUTS_FORWARD_TO_BREAK_ZONE_PATTERN = Pattern.compile(
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
    static final Pattern BZ_FWD_TO_HAND_OPP_FWD_TO_BZ_BY_DAMAGE = Pattern.compile(
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
    static final Pattern OPPONENT_MILL_PATTERN = Pattern.compile(
        "(?i)Your\\s+opponent\\s+puts?\\s+" +
        "(?:the\\s+top\\s+(?:(?<count>\\d+)\\s+cards?|card)\\s+of" +
        "|(?<count2>\\d+)\\s+cards?\\s+from\\s+the\\s+top\\s+of)\\s+" +
        "(?:his/her|his|her|their)\\s+deck\\s+into\\s+the\\s+Break\\s+Zone" +
        "(?:[.!]?\\s*(?:You\\s+)?[Dd]raw\\s+(?<draw>\\d+)\\s+cards?[.!]?)?"
    );
    static final Pattern DIVIDE_DAMAGE_PATTERN = Pattern.compile(
            "(?i)Divide\\s+(?<amount>\\d+)\\s+damage\\b(?:.*?\\b(?<mode>equally)\\b)?"
    );
    /**
     * Matches the condition clause of "If &lt;cond&gt;, divide M damage among them [as you like|
     * equally] instead." — captures just {@code cond}; the alt amount is re-extracted separately
     * via {@link #DIVIDE_DAMAGE_PATTERN} against the same substring.
     */
    static final Pattern DIVIDE_DAMAGE_INSTEAD_COND = Pattern.compile(
            "(?i)^If\\s+(?<cond>.+?),\\s*(?=[Dd]ivide\\s+\\d+\\s+damage)"
    );
    /**
     * Matches "Divide N damage equally among all the Forwards/Backups/Characters [you control|
     * opponent controls][ (round up to the nearest 1000)]." — a blanket, no-choice variant of
     * the "Choose ... Divide N damage" pattern (e.g. Strago's "Grand Delta").
     * Groups: {@code amount}, {@code type}, {@code control}.
     */
    static final Pattern DIVIDE_DAMAGE_EQUALLY_AMONG_ALL = Pattern.compile(
            "(?i)^Divide\\s+(?<amount>\\d+)\\s+damage\\s+equally\\s+among\\s+all\\s+(?:the\\s+)?" +
            "(?<type>Forwards?|Backups?|Characters?)" +
            "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls|you\\s+control))?" +
            "(?:\\s*\\([^)]*\\))?[.!]?\\s*$"
    );
    /**
     * Matches "Your opponent puts the top N cards of his/her deck into the Break Zone.
     * If both [all] cards are of the same Element, draw M card(s)."
     * Groups: {@code count}, {@code draw}.
     */
    static final Pattern OPPONENT_MILL_IF_SAME_ELEMENT_DRAW = Pattern.compile(
        "(?i)Your\\s+opponent\\s+puts?\\s+" +
        "(?:the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of|(?<count2>\\d+)\\s+cards?\\s+from\\s+the\\s+top\\s+of)\\s+" +
        "(?:his/her|his|her|their)\\s+deck\\s+into\\s+the\\s+Break\\s+Zone[.!]?\\s+" +
        "If\\s+(?:both|all)\\s+(?:the\\s+)?cards?\\s+are\\s+of\\s+the\\s+same\\s+Element,?\\s+" +
        "draw\\s+(?<draw>\\d+)\\s+cards?[.!]?"
    );
    static final Pattern SELF_MILL_PATTERN = Pattern.compile(
        "(?i)Put\\s+the\\s+top\\s+(?:(?<count>\\d+)\\s+cards?|card)\\s+" +
        "of\\s+your\\s+deck\\s+into\\s+the\\s+Break\\s+Zone"
    );

    // =========================================================================================
    // Casting and playing from hand
    // =========================================================================================
    /**
     * "Cast 1 Summon [of cost N or less] from your hand without paying [its|the] cost[.
     * Then, return that Summon to your hand after use instead of putting it in the Break Zone.]"
     * Groups: {@code cost} — numeric cost cap or "X"; {@code returnToHand} — present for the
     * "return to hand after use" variant.
     *
     * <p>The cost cap also takes the counter-scaled wording "of cost equal to or less than the
     * number of [Name] Counters placed on [card]" (15-083L Rydia), captured as
     * {@code counterName}. That ceiling is only known at activation, so it resolves through the
     * same {@code xValue} channel as a literal "X" — {@code CardData.COST_AT_MOST_COUNTER_PATTERN}
     * is what makes the ability read its counter count into {@code xValue}.
     */
    static final Pattern CAST_SUMMON_FROM_HAND_FREE = Pattern.compile(
        "(?i)Cast\\s+1\\s+Summon" +
        "(?:\\s+of\\s+cost\\s+(?:(?<cost>\\d+|X)\\s+or\\s+less" +
            "|equal\\s+to\\s+or\\s+less\\s+than\\s+the\\s+number\\s+of\\s+" +
            "(?<counterName>.+?)\\s+Counters?\\s+placed\\s+on\\s+[^,.]+?))?" +
        "(?:\\s+other\\s+than\\s+(?<excludeelems>(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*))?" +
        "\\s+from\\s+your\\s+hand\\s+without\\s+paying\\s+(?:its|the)\\s+cost[.!]?" +
        "(?<returnToHand>\\s*Then,?\\s+return\\s+that\\s+Summon\\s+to\\s+your\\s+hand\\s+after\\s+use" +
        "\\s+instead\\s+of\\s+putting\\s+it\\s+in\\s+the\\s+Break\\s+Zone[.!]?)?"
    );
    /**
     * "Randomly reveal 1 card from your hand. If it is a Summon, you may cast it without paying the cost."
     */
    static final Pattern RANDOM_REVEAL_HAND_CAST_IF_SUMMON_FREE = Pattern.compile(
        "(?i)Randomly\\s+reveal\\s+1\\s+card\\s+from\\s+your\\s+hand[.!]?\\s+" +
        "If\\s+it\\s+is\\s+a\\s+Summon,?\\s+you\\s+may\\s+cast\\s+it\\s+without\\s+paying\\s+(?:its|the)\\s+cost[.!]?"
    );
    /**
     * "Cast a Summon from your hand. The cost required to cast it is reduced by N (it cannot become 0)."
     * Group {@code amount} — the reduction amount.
     *
     * <p>Both quantifier wordings appear: action abilities say "Cast a Summon", while the auto
     * ability on 5-047C says "cast 1 Summon" (its "you may" is consumed by the trigger parse).
     *
     * <p>The lookahead after {@code amount} keeps this off abilities that qualify the reduction
     * further — "reduced by 3 <em>and can be paid using CP of any Element</em>" is a distinct
     * effect with its own handling. Since this matcher runs with {@code find()}, without the
     * lookahead it would match those texts too and silently drop the extra clause.
     */
    static final Pattern CAST_SUMMON_FROM_HAND_DISCOUNTED = Pattern.compile(
        "(?i)Cast\\s+(?:a|1)\\s+Summon\\s+from\\s+your\\s+hand[.!]?\\s+" +
        "The\\s+cost\\s+required\\s+to\\s+cast\\s+it\\s+is\\s+reduced\\s+by\\s+(?<amount>\\d+)" +
        "(?!\\s+and\\b)" +
        "(?:\\s*\\(it\\s+cannot\\s+become\\s+0\\))?[.!]?"
    );
    /**
     * "Search for 1 [Element] Summon [of cost N or less] and cast it without paying [its|the] cost.
     * If you do not cast it, put the Summon into the Break Zone."
     * Groups: {@code element} — element name; {@code cost} — optional numeric cost cap.
     */
    static final Pattern SEARCH_AND_CAST_SUMMON_FREE_PATTERN = Pattern.compile(
        "(?i)search\\s+for\\s+1\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Summon" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+less)?" +
        "\\s+and\\s+cast\\s+it\\s+without\\s+paying\\s+(?:its|the)\\s+cost[.!]?" +
        "(?:\\s+If\\s+you\\s+do\\s+not\\s+cast\\s+it,\\s+put\\s+the\\s+Summon\\s+into\\s+the\\s+Break\\s+Zone[.!]?)?"
    );
    /**
     * Matches the "each player may" opening of "each player may play 1 … from their hand onto the
     * field" — 28-051R Black Cat, the corpus's only instance.
     *
     * <p>Two jobs, which is why it ends in a zero-width lookahead at "play". It keeps
     * {@link #PLAY_FROM_HAND_PATTERN} off the text: that pattern resolves a single play and runs
     * under {@code find()}, so it would match from "play 1 Character…" onward and quietly do the
     * controller's half while dropping the opponent's. And because the match ends exactly where
     * "play" begins, the tail is the ordinary single-player wording and can be handed straight to
     * that same pattern, so both readings share one set of filter groups.
     */
    static final Pattern EACH_PLAYER_MAY_PLAY_FROM_HAND = Pattern.compile(
        "(?i)each\\s+player\\s+may\\s+(?=play\\b)"
    );
    static final Pattern PLAY_FROM_HAND_PATTERN = Pattern.compile(
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
            "Card\\s+Name\\s+(?<cardname>.+?)\\s+(?=of\\s+cost|from\\s+(?:your|his/her|his|her|their)\\b|[.!])" +
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
            "Job\\s+(?<jobnmonly>.+?)\\s+(?=of\\s+cost|from\\s+(?:your|his/her|his|her|their)\\b|other\\s+than)" +
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
        "(?:with\\s+(?<trait>Warp)\\s+)?" +
        // Possessive is not a filter — it names whoever the effect is already resolving for.
        // "his/her|their" appears on the "your opponent may play 1 … from his/her hand" abilities
        // (1-060H Leon, 12-071R Shadow Lord), and those reach here with the execution context
        // already flipped to the opponent by AutoAbility.opponentMay, so the hand read is correct
        // without the parser knowing whose it is.
        "from\\s+(?:your|his/her|his|her|their)\\s+hand\\s+onto\\s+(?:the\\s+)?field" +
        // Dull modifier
        "(?:\\s+(?<dull>dull))?" +
        "[.!]?"
    );
    /** Matches "play any number of [Job X] [type] from your hand onto [the] field". */
    static final Pattern PLAY_ANY_NUMBER_FROM_HAND_PATTERN = Pattern.compile(
        "(?i)(?:Then,?\\s+)?(?:you\\s+may\\s+)?[Pp]lay\\s+any\\s+number\\s+of\\s+" +
        "(?:Job\\s+(?<jobnm>.+?)\\s+)?" +
        "(?<targets>Forwards?|Backups?|Monsters?|Characters?)?" +
        "\\s*from\\s+your\\s+hand\\s+onto\\s+(?:the\\s+)?field[.!]?"
    );

    // =========================================================================================
    // Deck search
    // =========================================================================================
    /**
     * Matches "Search for up to 1 Job [job] and up to 1 [Type] that don't share Elements, and add them to your hand."
     * Used by cards like Rydia that fetch one card from each of two overlapping pools with an element-disjointness constraint.
     */
    static final Pattern DUAL_SEARCH_JOB_AND_TYPE_DONT_SHARE_ELEMENTS = Pattern.compile(
        "(?i)search\\s+for\\s+up\\s+to\\s+1\\s+Job\\s+(?<job>.+?)(?=\\s+and\\s+up\\s+to\\b)" +
        "\\s+and\\s+up\\s+to\\s+1\\s+(?<type>Summon|Forward|Backup|Monster|Character)" +
        "\\s+that\\s+don.t\\s+share\\s+[Ee]lements,?\\s+and\\s+add\\s+them\\s+to\\s+your\\s+hand[.!]?"
    );
    /**
     * Matches "Search for up to 1 [half] and up to 1 [half] and play them onto the field", where
     * each half is either a card name or a type with an exact cost:
     * <ul>
     *   <li>"up to 1 Monster of cost 1 and up to 1 Monster of cost 2" — 11-124H Relm</li>
     *   <li>"up to 1 Card Name Kukki-Chebukki and up to 1 Card Name Makki-Chebukki" — 19-109H
     *       Cherukiki</li>
     * </ul>
     *
     * <p>Two searches of one deck in one sentence. Cannot go through {@link #SEARCH_DECK_PATTERN}:
     * that one describes a single pool, and on the name form it does something worse than dropping
     * the second half — its lazy name group runs straight through the conjunction and searches for
     * a card called "Kukki-Chebukki and up to 1 Card Name Makki-Chebukki", which matches nothing.
     *
     * <p>Groups per half: {@code name1} or ({@code type1}, {@code cost1}), and likewise for 2. The
     * halves are read independently rather than assumed alike — nothing in the phrasing pairs them,
     * and both printings happen to use the same form on both sides only by chance.
     */
    static final Pattern DUAL_SEARCH_PLAY_ONTO_FIELD = Pattern.compile(
        "(?i)^(?:you\\s+may\\s+)?search\\s+for\\s+up\\s+to\\s+1\\s+" +
        "(?:Card\\s+Name\\s+(?<name1>.+?)" +
            "|(?<type1>Forwards?|Backups?|Monsters?|Characters?|Summons?)\\s+of\\s+cost\\s+(?<cost1>\\d+))" +
        "\\s+and\\s+up\\s+to\\s+1\\s+" +
        "(?:Card\\s+Name\\s+(?<name2>.+?)" +
            "|(?<type2>Forwards?|Backups?|Monsters?|Characters?|Summons?)\\s+of\\s+cost\\s+(?<cost2>\\d+))" +
        "\\s+and\\s+play\\s+them\\s+onto\\s+(?:the\\s+)?field[.!]?\\s*$"
    );
    /**
     * Matches "Search for 2 [Element] Characters, 2 Category [X] Characters, or 1 of each,
     * each with a different cost, and add them to your hand."
     * Groups: {@code element}, {@code category}.
     */
    static final Pattern SEARCH_ELEMENT_OR_CATEGORY_CHARS_DIFF_COST = Pattern.compile(
        "(?i)Search\\s+for\\s+2\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Characters?,\\s+" +
        "2\\s+Category\\s+(?<category>\\S+)\\s+Characters?,\\s+or\\s+1\\s+of\\s+each,\\s+" +
        "each\\s+with\\s+a\\s+different\\s+cost,?\\s+and\\s+add\\s+them\\s+to\\s+your\\s+hand[.!]?"
    );
    /**
     * Matches "Search for N [Element] Summons each with a different cost and add them to your hand."
     * Groups: {@code count}, {@code element}.
     */
    static final Pattern SEARCH_N_ELEM_SUMMONS_DIFF_COST = Pattern.compile(
        "(?i)Search\\s+for\\s+(?<count>\\d+)\\s+" +
        "(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Summons?" +
        "\\s+each\\s+with\\s+a\\s+different\\s+cost\\s+and\\s+add\\s+them\\s+to\\s+your\\s+hand[.!]?"
    );
    /**
     * Matches a "You may" sitting at the very end of the text preceding a match — i.e. immediately
     * before the clause it makes optional.
     *
     * <p>Anchored with {@code $} on purpose. A search is optional only when the "you may" attaches
     * to the search itself; 12-106R Relm's "choose 1 Character … You may search for …" has one, but
     * an ability whose "you may" governs an earlier clause must not make a later mandatory search
     * skippable.
     */
    static final Pattern YOU_MAY_IMMEDIATELY_BEFORE = Pattern.compile("(?i)\\byou\\s+may\\s+$");
    /**
     * Followup used inside {@code tryParseChooseCharacter}: a deck search whose filter comes from
     * the card the player just chose rather than from the text.
     *
     * <ul>
     *   <li>12-106R Relm / 23-078C Alisaie — "search for 1 Character with the same name and add
     *       it to your hand" (filter: the chosen card's name)</li>
     *   <li>23-130H Luso — "search for 1 Job Standard Unit of the same Element as the chosen
     *       Character and add it to your hand" (filter: the chosen card's Element)</li>
     * </ul>
     *
     * <p>Cannot go through {@link #SEARCH_DECK_PATTERN}: every filter that pattern captures is
     * written in the text, but these are only known once a target has been chosen.
     *
     * <p>Groups: {@code count}, {@code job}, {@code category}, {@code searchtype},
     * {@code samename} / {@code sameelem} (exactly one is present, selecting which property is
     * copied off the chosen card), {@code destination}. Anchored end-to-end.
     */
    static final Pattern FOLLOWUP_SEARCH_MATCHING_CHOSEN = Pattern.compile(
        "(?i)^search\\s+for\\s+(?<count>\\d+)\\s+" +
        "(?:Job\\s+(?<job>[A-Za-z][A-Za-z\\s'\\-]*?)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?<searchtype>Forwards?|Backups?|Monsters?|Characters?|Summons?|cards?)?\\s*" +
        "(?:with\\s+the\\s+same\\s+(?<samename>name)" +
        "|of\\s+the\\s+same\\s+(?<sameelem>Element)\\s+as\\s+the\\s+chosen\\s+" +
        "(?:Character|Forward|Backup|Monster))" +
        "\\s+and\\s+(?<destination>add\\s+(?:it|them)\\s+to\\s+your\\s+hand" +
        "|play\\s+(?:it|them)\\s+onto\\s+the\\s+field)[.!]?$"
    );
    /**
     * The "with different names" constraint on a multi-card search — 23-008H Zidane, 18-138S
     * Glauca, 22-067L Nacht.
     *
     * <p>Stripped out of the text before {\\link #SEARCH_DECK_PATTERN} reads it, rather than being
     * expressed there. The identity groups in that pattern are lazy {\\code .+?} runs bounded by a
     * lookahead, and "with different names" is not one of the words that stops them: Glauca was
     * searching for a job literally called "Captain with different names", which matches nothing,
     * and the Category arm has no such lookahead at all so Zidane did not parse. Lifting the phrase
     * off first fixes both arms at once and leaves the constraint as a flag the search can act on.
     */
    static final Pattern SEARCH_WITH_DIFFERENT_NAMES = Pattern.compile(
        "(?i)\\s+with\\s+different\\s+names\\b"
    );
    static final Pattern SEARCH_DECK_PATTERN = Pattern.compile(
        // "for" is optional: 11-058H Bel Dat is the corpus's only "search 1 …" wording, every other
        // search text says "search for". The leading \b is what makes dropping it safe — "Research"
        // ends in "search", and the Chadley cards ("place 2 Research Counters") would otherwise be
        // one word away from matching.
        "(?i)\\bSearch\\s+(?:for\\s+)?(?:up\\s+to\\s+)?(?<count>\\d+)\\s+" +
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
            "(?=\\s+of\\s+cost|\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card)\\b|\\s+other\\b|\\s+and\\b)\\s*" +
        "|" +
            // "Card Name X with Job Y" — the one identity phrase that is a conjunction rather than
            // a union: the card named X that also carries the Job Y (20-075L and 28-032H Cecil
            // want the Paladin Cecil, not any Cecil and not any Paladin; 4-054L Onion Knight the
            // Sage one). Must precede the plain card-name alternative, whose lazy group ran to the
            // trailing "and" and took the whole phrase as a name — a search for a card called
            // "Cecil with Job Paladin".
            "Card\\s+Name\\s+(?<cnamewithjob>.+?)\\s+with\\s+Job\\s+(?<jobwithcname>.+?)" +
            "(?=\\s+of\\s+cost|\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card)\\b|\\s+other\\b|\\s+and\\b)\\s*" +
        "|" +
            // "Card Name A[, Card Name B][, or Card Name C]" — several names, OR'd together. Must
            // precede the single-name alternative, whose lazy group would otherwise run to the
            // trailing "and" and take the whole list as one (unmatchable) name.
            //
            // A "Job Y" term may sit anywhere in the list ("Card Name Chloe, Job Chocobo or Card
            // Name Chocobo" — Billy 29-048C); splitCardNameAndJobList sorts the two kinds out.
            // Admitting it here rather than in a fourth union alternative keeps the two-term
            // shapes on the alternatives above, which are tried first and already handle them.
            "Card\\s+Name\\s+(?<cardnames>.+?(?:(?:\\s*,\\s*|\\s+(?:and/)?or\\s+)(?:Card\\s+Name|Job)\\s+.+?)+)" +
            "(?=\\s+of\\s+cost|\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card)\\b|\\s+other\\b|\\s+and\\b)" +
            "\\s+" +
        "|" +
            // Written card name without brackets — ends at type word, "of cost", or "and"
            "Card\\s+Name\\s+(?<cardname>.+?)" +
            "(?=\\s+of\\s+cost|\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card)\\b|\\s+other\\b|\\s+and\\b)" +
            "\\s+" +
        "|" +
            // "Category X [Type] and/or Job Y" — OR logic. Must precede the plain Category
            // alternative below: that one's lazy group cannot stop at "FFL" here, because the
            // "or Job Warrior of Light Forwards" left over fits nothing later in the pattern, so
            // it backtracks and swallows the whole phrase as the category name. The search then
            // looks for a category literally called "FFL Forwards or Job Warrior of Light" and
            // finds nothing — 12-099R Sarah (FFL) searching an empty result.
            "Category\\s+(?<catjobor>.+?)" +
            "(?:\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card))?" +
            "\\s+(?:and/)?or\\s+Job\\s+(?<jobcator>.+?)" +
            "(?=\\s+of\\s+cost|\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card)\\b|\\s+other\\b|\\s+and\\b)\\s*" +
        "|" +
            // Category filter — lookahead keeps the type word in the targets group
            "Category\\s+(?<category>.+?)\\s+" +
            "(?=Forwards?|Backups?|Monsters?|Summons?|Characters?|card\\b)" +
        "|" +
            // "Job X [Type] or Card Name Y" — OR logic; must come before plain Job alternative
            "Job\\s+(?<jobnmor>.+?)" +
            "(?:\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card))?" +
            "\\s+(?:and/)?or\\s+Card\\s+Name\\s+(?<cnameor>.+?)" +
            "(?=\\s+of\\s+cost|\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card)\\b|\\s+other\\b|\\s+and\\b)\\s*" +
        "|" +
            // Written job — lookahead keeps the type word, "of cost", "other than", Category, or
            // "and" ahead.
            //
            // Deliberately does NOT stop at an element word. 19 job names contain one — "Warrior
            // of Light", "Dark Knight", "Oracle of Light" — and every search text in the corpus
            // that reads "Job … <Element> <Type>" is one of those names, not a job plus an element
            // filter. Stopping at the element truncated "Warrior of Light" to "Warrior of" and the
            // search matched nothing (7-114H Sarah, 5-123H Aria). An element stated before the job
            // is a separate group (preelems) and is unaffected.
            "Job\\s+(?<jobnm>.+?)(?=" +
            "\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card)\\b" +
            "|\\s+of\\s+cost\\b|\\s+other\\b|\\s+Category\\b|\\s+and\\b)\\s*" +
        ")?" +
        // Optional Category filter following a Job filter (e.g. "Job Standard Unit Category FFCC")
        "(?:Category\\s+(?<catafterjob>\\S+)\\s+)?" +
        "(?:(?<elements>(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*)\\s+)?" +
        "(?<targets>(?:Forwards?|Backups?|Monsters?|Summons?|Characters?)(?:\\s+or\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?))*|cards?)?\\s*" +
        "(?<withwarp>with\\s+Warp)?\\s*" +
        "(?:\\s+other\\s+than\\s+a(?:n)?\\s+(?<excludetype>Forward|Backup|Monster|Summon|Character))?\\s*" +
        // The trailing \s* completes this group's own "of cost" lookahead: without it the space the
        // lookahead stopped in front of was left unconsumed, the cost clause could not start, and
        // the group backtracked to the "and" instead — taking "Cyan of cost 3 or less" as a name.
        // No printing states the exclusion before a cost, so this path had never fired.
        "(?:\\s+other\\s+than\\s+Card\\s+Name\\s+(?<excludename>.+?)(?=\\s+of\\s+cost|\\s+and\\b))?\\s*" +
        // "of cost X" is the 《X》 the ability was paid with, not a printed number (25-051L Rem).
        // Without this alternative the whole cost clause failed to match and the Job group
        // backtracked straight across it, searching for a job called "Class Zero Cadet of cost X".
        "(?:of\\s+cost\\s+(?:(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more|\\d+))?" +
            "|(?<costx>X)(?:\\s+or\\s+(?<costxcmp>less|more))?)\\s*)?" +
        // The name exclusion may follow the cost as well as precede it — "of cost X other than
        // Card Name Rem" (25-051L). Only the element exclusion below was accepted after a cost, so
        // this order left "other than Card Name Rem" with nothing to match and the whole clause
        // backtracked away. Read into the same filter as the group above; the two orders are the
        // same sentence.
        "(?:\\s+other\\s+than\\s+Card\\s+Name\\s+(?<excludename2>.+?)(?=\\s+and\\b))?\\s*" +
        // "other than Light and Dark" as well as "... or Dark": both name a set to exclude, and
        // only "or" was accepted, so 7-114H Sarah (FFL)'s Job group backtracked across the
        // exclusion and searched for a job called "Warrior of Light Forward of cost 4 or less
        // other than Light and".
        "(?:\\s+other\\s+than\\s+(?<excludeelem>(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+(?:and|or)\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*))?\\s*" +
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

    // =========================================================================================
    // Opponent reveals; reveal-clause fragments
    // =========================================================================================
    /**
     * Matches "Your opponent reveals N cards from their hand. Select 1 card among them.
     * Your opponent discards this card." (14-035C Don Corneo, the only card in the corpus with
     * this shape.)
     *
     * <p>Kept distinct from {@link #OPPONENT_REVEAL_HAND_PATTERN}, which shows the <em>whole</em>
     * hand: here the opponent chooses which N to expose, so the ability user only ever selects
     * from cards the opponent was willing to show. Routing this text at the whole-hand pattern
     * would make the card strictly stronger than printed.
     */
    static final Pattern OPPONENT_REVEAL_N_SELECT_ONE_DISCARD_PATTERN = Pattern.compile(
        "(?i)Your\\s+opponent\\s+(?:shows?|reveals?)\\s+(?<count>\\d+)\\s+cards?\\s+from\\s+" +
        "(?:his/her|his|her|their)\\s+hand[.!]?\\s+" +
        "Select\\s+1\\s+card\\s+among\\s+them[.!]?\\s+" +
        "Your\\s+opponent\\s+discards\\s+this\\s+card[.!]?"
    );
    /** Matches "Your opponent shows/reveals his/her/their hand". */
    static final Pattern OPPONENT_REVEAL_HAND_PATTERN = Pattern.compile(
        "(?i)Your\\s+opponent\\s+(?:shows?|reveals?)\\s+(?:his/her|his|her|their)\\s+hand[.!]?"
    );
    /**
     * Matches "Choose 1 Forward. Reveal the top card of your deck. If the revealed card's
     * CP cost is an even number, [eveneffect]. Add the revealed card to your hand.
     * If the revealed card's CP cost is an odd number, [oddeffect]. Add the revealed card to your hand."
     */
    static final Pattern CHOOSE_FWD_REVEAL_COST_PARITY_PATTERN = Pattern.compile(
        "(?i)^Choose\\s+1\\s+Forward[.!]?\\s+" +
        "Reveal\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "If\\s+the\\s+revealed\\s+card's\\s+CP\\s+cost\\s+is\\s+an?\\s+even\\s+number,\\s+" +
        "(?<eveneffect>.+?)[.!]?\\s+Add\\s+the\\s+revealed\\s+card\\s+to\\s+your\\s+hand[.!]?\\s+" +
        "If\\s+the\\s+revealed\\s+card's\\s+CP\\s+cost\\s+is\\s+an?\\s+odd\\s+number,\\s+" +
        "(?<oddeffect>.+?)[.!]?\\s+Add\\s+the\\s+revealed\\s+card\\s+to\\s+your\\s+hand[.!]?$",
        Pattern.DOTALL
    );
    /**
     * Anchored prefix that confirms the effect text is a deck-reveal ability.
     * Group {@code who} captures the deck owner phrase so callers can tell
     * whether it is the ability user's own deck or the opponent's.
     * The clauses themselves are iterated with {@link #REVEAL_CLAUSE_PATTERN}.
     */
    static final Pattern REVEAL_TOP_DECK_HEADER = Pattern.compile(
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
    static final Pattern REVEAL_CLAUSE_PATTERN = Pattern.compile(
        "If\\s+it\\s+(?:is|has)\\s+(?<cond>[^,]+?)\\s*,\\s*(?<action>.+?)" +
        "(?=[.!]?\\s+If\\s+it\\s+(?:is|has)\\b|[.!]?\\s*$)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    /**
     * Matches "Put it into the Break Zone" — a forced send that bypasses
     * "cannot be broken" protections, unlike {@code FOLLOWUP_BREAK}.
     */
    static final Pattern FOLLOWUP_PUT_TO_BREAK_ZONE = Pattern.compile(
        "(?i)Put\\s+it\\s+into\\s+the\\s+Break\\s+Zone[.!]?"
    );

    // =========================================================================================
    // Self power boosts
    // =========================================================================================
    /**
     * Matches "&lt;subject&gt; gains [+N power] [, traits] until end of turn" where the subject
     * may be a card name (checked against the source at runtime) rather than "it"/"they".
     * <ul>
     *   <li>Group {@code selfsubject} — the word(s) before "gains"</li>
     *   <li>Group {@code selfamount}  — optional numeric power amount</li>
     *   <li>Group {@code selftraits}  — optional traits string</li>
     * </ul>
     */
    static final Pattern SELF_POWER_BOOST = Pattern.compile(
        "(?i)(?<selfsubject>.+?)\\s+gains?\\s+" +
        "(?:\\+(?<selfamount>\\d+)\\s+[Pp]ower)?" +
        "(?<selftraits>(?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );
    /**
     * The other word order of {@link #SELF_POWER_BOOST}: "Until the end of the turn[,] &lt;subject&gt;
     * gains [+N power][, traits]." — the duration leads instead of trailing (Tidus 1-163L, whose
     * printed text omits the comma).
     *
     * <p>Anchored end to end, unlike {@code SELF_POWER_BOOST}, because {@code subject} would
     * otherwise run backwards across a preceding clause under {@code find()} and match sentences
     * this parser has no business claiming.
     * <ul>
     *   <li>Group {@code subject} — the word(s) before "gains", checked against the source</li>
     *   <li>Group {@code amount}  — optional numeric power amount</li>
     *   <li>Group {@code traits}  — optional traits string</li>
     * </ul>
     */
    static final Pattern SELF_BOOST_EOT_PREFIX = Pattern.compile(
        "(?i)^Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,?\\s+" +
        "(?<subject>[^.]+?)\\s+gains?\\s+" +
        "(?:\\+(?<amount>\\d+)\\s+[Pp]ower)?" +
        "(?<traits>(?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "[.!]?\\s*$"
    );
    /**
     * Matches Tidus 1-163L's "Blitz Ace" second sentence: "&lt;subject&gt; can attack as many times
     * as your points of damage this turn." — a multi-attack permission whose count is the ability
     * user's own damage, read when the ability resolves.
     *
     * <p>Scoped to this wording rather than generalised. Tidus 29-105L carries the same idea as a
     * Damage-gated <em>field</em> ability ("as many times in the same turn as the points of damage
     * you have received"), which is continuous rather than resolved and does not belong on the
     * effect chain at all.
     */
    static final Pattern SELF_ATTACKS_PER_OWN_DAMAGE = Pattern.compile(
        "(?i)^(?<subject>[^.]+?)\\s+can\\s+attack\\s+as\\s+many\\s+times\\s+" +
        "as\\s+your\\s+points\\s+of\\s+damage\\s+this\\s+turn[.!]?\\s*$"
    );
    /**
     * Matches "if [CardName] has received N damage or more, draw M card(s)." —
     * the inner effect extracted from "At the end of each player's turn, …".
     * Groups: {@code cardname}, {@code damage}, {@code draw}.
     */
    static final Pattern IF_SELF_FWD_RECEIVED_DAMAGE_DRAW = Pattern.compile(
        "(?i)^if\\s+(?<cardname>.+?)\\s+has\\s+received\\s+(?<damage>\\d+)\\s+damage\\s+or\\s+more,\\s+" +
        "draw\\s+(?<draw>\\d+)\\s+cards?[.!]?\\s*$"
    );
    /**
     * Matches "if you have N or more cards in your hand, [subject] gains [+P power] [traits]
     * until end of turn[. If you have M or more cards, [subject] also gains +Q power until end of turn]."
     * Groups: {@code min1}, {@code subject}, {@code amount1}, {@code traits1}, {@code min2}, {@code amount2}.
     */
    static final Pattern IF_HAND_SIZE_SELF_BOOST = Pattern.compile(
        "(?i)if\\s+you\\s+have\\s+(?<min1>\\d+)\\s+or\\s+more\\s+cards?\\s+in\\s+your\\s+hand,\\s+" +
        "(?<subject>.+?)\\s+gains?\\s+" +
        "(?:\\+(?<amount1>\\d+)\\s+[Pp]ower)?" +
        "(?<traits1>(?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?" +
        "(?:\\s+If\\s+you\\s+have\\s+(?<min2>\\d+)\\s+or\\s+more\\s+cards?\\s+in\\s+your\\s+hand,\\s+" +
        ".+?also\\s+gains?\\s+\\+(?<amount2>\\d+)\\s+[Pp]ower\\s+" +
        "until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?)?"
    );
    /**
     * Matches "CardName gains +N power for each 《C》 you have until end of turn."
     * Groups: {@code subject}, {@code amount}.
     */
    static final Pattern SELF_POWER_BOOST_FOR_EACH_CRYSTAL = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower\\s+" +
        "for\\s+each\\s+《C》\\s+you\\s+have" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );
    /**
     * Matches the self-targeted "gains +N power for each [Element | Category X] [Type] you control"
     * boost in either word order:
     * <ul>
     *   <li>"Until the end of the turn, [Name] gains +N power for each Category XIII Character you
     *       control." — 19-136S Noel</li>
     *   <li>"[Name] gains +N power for each Earth Backup you control until the end of the turn."</li>
     * </ul>
     *
     * <p>The self-targeted twin of {@link #FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH}, which handles the
     * same counting clause when it lands on a chosen Forward instead of the source.
     *
     * <p>{@code subject} is checked against the source card by the parser, which is what keeps this
     * off a text naming some other card. Groups are doubled because a named group may not repeat
     * across alternatives: the {@code 2}-suffixed set belongs to the trailing-duration order.
     */
    static final Pattern SELF_POWER_BOOST_FOR_EACH_CONTROLLED = Pattern.compile(
        "(?i)(?:" +
            "^Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,?\\s+" +
            "(?<subject>[^.,]+?)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
            "(?:Category\\s+(?<category>\\S+)\\s+)?" +
            "(?<chartype>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control[.!]?\\s*$" +
        "|" +
            "^(?<subject2>[^.,]+?)\\s+gains?\\s+\\+(?<amount2>\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "(?:(?<element2>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
            "(?:Category\\s+(?<category2>\\S+)\\s+)?" +
            "(?<chartype2>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control" +
            "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?\\s*$" +
        ")"
    );
    /**
     * Matches the self-targeted "gains +N power for each different Element among [Type] you control"
     * boost in either word order — 16-002H Ace.
     *
     * <p>Sibling of {@link #SELF_POWER_BOOST_FOR_EACH_CONTROLLED}, but the multiplier is a count of
     * distinct Elements rather than of cards, so it reads a different counting primitive. The two
     * are mutually exclusive: that one needs the counted type to follow "for each" directly.
     *
     * <p>Groups are doubled for the same reason as its sibling — a named group may not repeat
     * across alternatives; the {@code 2}-suffixed set belongs to the trailing-duration order.
     */
    static final Pattern SELF_POWER_BOOST_FOR_EACH_DISTINCT_ELEMENT = Pattern.compile(
        "(?i)(?:" +
            "^Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,?\\s+" +
            "(?<subject>[^.,]+?)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "different\\s+Elements?\\s+among\\s+" +
            "(?<chartype>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control[.!]?\\s*$" +
        "|" +
            "^(?<subject2>[^.,]+?)\\s+gains?\\s+\\+(?<amount2>\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "different\\s+Elements?\\s+among\\s+" +
            "(?<chartype2>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control" +
            "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?\\s*$" +
        ")"
    );
    /**
     * Matches "[subject] gains +N power until the end of the turn and activate [activateName]."
     * Groups: {@code subject}, {@code amount}, {@code activateName}.
     */
    static final Pattern SELF_POWER_BOOST_AND_ACTIVATE = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower\\s+" +
        "until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s+and\\s+activate\\s+" +
        "(?<activateName>.+?)[.!]?\\s*$"
    );
    /**
     * Matches "[CardName]'s power becomes the same as that Forward's power until the end of the turn."
     * Used as a secondary effect after choosing and removing a Forward from the Break Zone.
     * Group {@code name} — the card whose power is set (should match the source card).
     */
    static final Pattern SOURCE_POWER_BECOMES_SAME_AS_REMOVED_FORWARD = Pattern.compile(
        "(?i)(?<name>.+?)'s\\s+power\\s+becomes\\s+the\\s+same\\s+as\\s+that\\s+Forward's\\s+power" +
        "\\s+until\\s+the\\s+end\\s+of\\s+(?:the\\s+)?turn[.!]?\\s*$"
    );
    /**
     * Matches "[CardName]'s power becomes the same as your opponent's weakest Forward until the
     * end of the turn." Group {@code name} — the card whose power is set (should match the source card).
     */
    static final Pattern SOURCE_POWER_BECOMES_OPPONENT_WEAKEST_FORWARD = Pattern.compile(
        "(?i)(?<name>.+?)'s\\s+power\\s+becomes\\s+the\\s+same\\s+as\\s+your\\s+opponent's\\s+weakest\\s+Forward" +
        "\\s+until\\s+the\\s+end\\s+of\\s+(?:the\\s+)?turn[.!]?\\s*$"
    );
    /**
     * Matches "During this turn, if [CardName] deals damage to a Forward, double the damage instead."
     * Groups: {@code subject} — the card name.
     */
    static final Pattern DOUBLE_OUTGOING_DAMAGE_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+(?<subject>.+?)\\s+deals?\\s+damage\\s+to\\s+a\\s+Forward," +
        "\\s+double\\s+the\\s+damage\\s+instead[.!]?"
    );
    /**
     * Matches "During this turn, if a Forward opponent controls is dealt damage, double the damage instead."
     */
    static final Pattern DOUBLE_OPPONENT_INCOMING_DAMAGE_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+a\\s+Forward\\s+(?:your\\s+)?opponent\\s+controls\\s+" +
        "is\\s+dealt\\s+damage,\\s+double\\s+the\\s+damage\\s+instead[.!]?"
    );
    /**
     * Matches "If a Forward receives damage this turn, the damage increases by N instead."
     */
    static final Pattern ALL_FORWARD_INCOMING_DMG_INCREASE_THIS_TURN = Pattern.compile(
        "(?i)If\\s+a\\s+Forward\\s+receives\\s+damage\\s+this\\s+turn,\\s+the\\s+damage\\s+increases?\\s+by\\s+(?<amount>\\d+)(?:\\s+instead)?[.!]?"
    );
    /**
     * Matches "If [subject] deals damage to a Forward this turn, double the damage instead."
     * (Ninja-style variant — "this turn" appears at the end rather than "During this turn" at the start.)
     */
    static final Pattern DOUBLE_OUTGOING_DAMAGE_THIS_TURN_ALT = Pattern.compile(
        "(?i)If\\s+(?<subject>.+?)\\s+deals?\\s+damage\\s+to\\s+a\\s+Forward\\s+this\\s+turn,\\s+double\\s+the\\s+damage\\s+instead[.!]?"
    );

    // =========================================================================================
    // Choosing by cost or power; opening choices
    // =========================================================================================
    /**
     * Matches "Choose 1 Forward opponent controls with a cost inferior or equal to the number of
     * [Element] [Backups/Forwards] you control. [followup]"
     * Groups: {@code element} — element name; {@code cardtype} — "Backups" or "Forwards";
     *         {@code followup} — effect sentence(s) to apply to the chosen targets.
     *
     * <p>Takes "of cost" alongside "with a cost": Edea's reprint (Re-122L/2-099L) rewords the
     * original 2-099L's "with a cost inferior or equal to" as "of cost equal to or less than",
     * and the two printings are the same card.
     */
    static final Pattern CHOOSE_OPP_FWD_DYN_COST_BREAK = Pattern.compile(
        "(?i)Choose\\s+1\\s+Forward\\s+(?:your\\s+)?opponent\\s+controls\\s+(?:with\\s+a\\s+cost|of\\s+cost)\\s+" +
        "(?:inferior\\s+or\\s+equal\\s+to|equal\\s+or\\s+inferior\\s+to|equal\\s+to\\s+or\\s+(?:less\\s+than|inferior))\\s+" +
        "the\\s+number\\s+of\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+" +
        "(?<cardtype>Backups?|Forwards?)\\s+you\\s+control[.,]?\\s+(?<followup>.+)"
    );
    /**
     * Vincent 2-077L's Death Penalty: "Choose as many Forwards as you want with a total cost of N
     * or less. Break them."
     *
     * <p>The only field-target selection in the corpus bounded by a <em>sum</em> rather than a
     * count or a per-card ceiling, which is why it gets a pattern and a selection primitive of its
     * own instead of another parameter on the choose chain: nothing else could use them.
     * Group {@code max} — the budget the chosen Forwards' printed costs must not exceed.
     */
    static final Pattern CHOOSE_FORWARDS_TOTAL_COST_BREAK = Pattern.compile(
        "(?i)^Choose\\s+as\\s+many\\s+Forwards\\s+as\\s+you\\s+want\\s+with\\s+a\\s+total\\s+cost\\s+of\\s+"
        + "(?<max>\\d+)\\s+or\\s+less[.!]?\\s*Break\\s+them[.!]?$");
    /**
     * Kefka 15-071H: "Divide all the Forwards opponent controls into N groups (You can make a group
     * of 0 Forwards). Your opponent selects 1 group among them. Put all the Forwards of the other
     * groups into the Break Zone."
     *
     * <p>Matched as one whole rather than three sentences because it is one decision made by two
     * players: the division only means anything alongside the selection that answers it, and the
     * removal only means anything alongside both. Group {@code groups} — how many groups the row is
     * divided into.
     *
     * <p>The parenthetical permitting an empty group is optional in the pattern and carries no
     * information the resolver needs — an empty group is what the dialog produces by default, and
     * nothing in the effect would forbid one if the reminder were absent.
     */
    /**
     * The opening "Choose N &lt;something&gt;." of an effect that must have a target, with the
     * described target in group {@code what}.
     *
     * <p>"Choose up to N" is excluded on purpose: choosing none is a legal choice there, so the
     * effect resolves whether or not anything is on the board.
     */
    static final Pattern OPENING_MANDATORY_CHOICE = Pattern.compile(
        "(?i)^Choose\\s+(?!up\\s+to\\b)\\d+\\s+(?<what>[^.!]*)[.!]");

    /**
     * The opening choice of {@link #OPENING_MANDATORY_CHOICE} naming something on the Stack
     * rather than a card in a zone. Matched against that pattern's {@code what}, so the ability
     * word has to be what the choice is <em>of</em> — a later mention of an ability cannot drag
     * a choice of Forwards onto the Stack.
     *
     * <p>Group {@code types} is filled for the ability spellings and feeds
     * {@link ActionResolver#parseAbilityTypeFilter}. The Summon alternative leaves it null: what
     * that choice can point at depends on the qualifier after it, which
     * {@link ActionResolver#stackCancelFilter} already reads.
     */
    static final Pattern OPENING_CHOICE_ON_STACK = Pattern.compile(
        "(?i)^(?:(?<types>auto[- ]ability|action\\s+ability|special\\s+ability|ability)\\b"
        + "|Summon\\s+(?:targeting|choosing)\\b)");

    /**
     * "Choose 1 card in your Damage Zone." (Ark 23-113R) — the one printing whose mandatory
     * choice is answered by the Damage Zone, which is empty until its owner has taken damage.
     *
     * <p>Its own pattern rather than a {@code zone} alternative in
     * {@link #CHOOSE_CHARACTER_PATTERN}: that group routes a choice to
     * {@code selectCharactersFromBreakZone}, and a Damage Zone card is not a Character to be
     * chosen off a board.
     */
    static final Pattern OPENING_CHOICE_FROM_DAMAGE_ZONE = Pattern.compile(
        "(?i)^Choose\\s+(?!up\\s+to\\b)\\d+\\s+cards?\\s+in\\s+your\\s+Damage\\s+Zone\\b");

    /**
     * A pronoun or phrase by which a sentence refers back to something already chosen. Used to tell
     * a follow-up clause ("Deal it 4000 damage") from an effect that stands on its own and happens
     * whether or not anything was chosen ("Draw 1 card").
     */
    static final Pattern REFERS_TO_CHOSEN = Pattern.compile(
        "(?i)\\b(?:it|its|them|their|that\\s+\\w+|those|the\\s+former|the\\s+latter)\\b");

    /** The Character kinds a choice can name. "Character" covers all three. */
    static final Pattern CHOSEN_CARD_KIND = Pattern.compile(
        "(?i)\\b(Forwards?|Characters?|Backups?|Monsters?)\\b");

    static final Pattern DIVIDE_OPP_FORWARDS_INTO_GROUPS = Pattern.compile(
        "(?i)^Divide\\s+all\\s+the\\s+Forwards\\s+(?:your\\s+)?opponent\\s+controls?\\s+into\\s+"
        + "(?<groups>\\d+)\\s+groups?(?:\\s*\\([^)]*\\))?[.!]?\\s+"
        + "Your\\s+opponent\\s+selects\\s+1\\s+group\\s+among\\s+them[.!]?\\s+"
        + "Put\\s+all\\s+the\\s+Forwards\\s+of\\s+the\\s+other\\s+groups\\s+into\\s+"
        + "the\\s+Break\\s+Zone[.!]?$");
    /**
     * Matches "Choose 1 Forward [control?] with a power inferior to [CardName]'s [power]. [followup]"
     * Groups: {@code control} — optional "opponent controls" / "you control";
     *         {@code sourcename} — name of the card whose power sets the ceiling;
     *         {@code followup} — effect sentence(s) to apply to the chosen targets.
     */
    static final Pattern CHOOSE_FWD_POWER_INFERIOR_TO_SOURCE = Pattern.compile(
        "(?i)Choose\\s+1\\s+Forward\\s+" +
        "(?:(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control)\\s+)?" +
        "with\\s+a\\s+power\\s+inferior\\s+to\\s+(?<sourcename>.+?)'s(?:\\s+power)?[.,]?\\s+(?<followup>.+)"
    );
    /**
     * Matches "Dull all [the] Forwards with a power [equal or inferior / inferior or equal /
     * equal to or less than] to [CardName]'s [your] opponent controls."
     * Groups: {@code sourcename} — name of the card whose power is the ceiling.
     */
    static final Pattern DULL_ALL_OPP_FWDS_POWER_LE_SOURCE = Pattern.compile(
        "(?i)Dull\\s+all\\s+(?:the\\s+)?Forwards?\\s+with\\s+a\\s+power\\s+" +
        "(?:equal\\s+or\\s+inferior\\s+to|inferior\\s+or\\s+equal\\s+to|equal\\s+to\\s+or\\s+less\\s+than)\\s+" +
        "(?<sourcename>.+?)'s\\s+(?:(?:your\\s+)?opponent\\s+controls?)[.!]?"
    );
    /**
     * Matches "Choose 1 Forward in your Break Zone with a cost inferior to that of the removed
     * Forward. Play it onto the field." — the follow-up half of a Hojo-style remove-then-play chain.
     */
    static final Pattern CHOOSE_FWD_BZ_COST_INFERIOR_TO_REMOVED_PLAY = Pattern.compile(
        "(?i)Choose\\s+1\\s+Forward\\s+in\\s+your\\s+Break\\s+Zone\\s+with\\s+a\\s+cost\\s+" +
        "inferior\\s+to\\s+that\\s+of\\s+the\\s+removed\\s+Forward[.,]?\\s+" +
        "Play\\s+it\\s+onto\\s+(?:the\\s+)?field[.!]?"
    );
    /**
     * Matches "Choose 1 Forward. During this turn, if it is dealt damage, double the damage instead."
     */
    static final Pattern CHOOSE_FORWARD_DOUBLE_INCOMING_THIS_TURN = Pattern.compile(
        "(?i)Choose\\s+1\\s+Forward[.,]?\\s+During\\s+this\\s+turn,\\s+if\\s+it\\s+is\\s+dealt\\s+damage,\\s+double\\s+the\\s+damage\\s+instead[.!]?"
    );
    /**
     * Matches "Choose 1 [Job X] Forward. During this turn, the next damage it deals to a Forward
     * becomes double the damage instead. [You can only use this ability once per turn.]"
     * <ul>
     *   <li>Group {@code job} — optional job filter (e.g. {@code "Headhunter"})</li>
     * </ul>
     */
    /**
     * Unreachable against the current corpus: tryParseChooseCharacter is called ahead of this
     * parser in {@code parse()} and claims every text this could match, so the wording is served by
     * {@link #FOLLOWUP_DOUBLE_NEXT_OUTGOING} in the choose chain instead. Kept rather than deleted
     * because the golden file can only show that nothing reaches it today.
     */
    static final Pattern CHOOSE_FORWARD_DOUBLE_NEXT_OUTGOING = Pattern.compile(
        "(?i)Choose\\s+1\\s+(?:Job\\s+(?<job>.+?)\\s+)?Forward[.,]?\\s+" +
        "During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+it\\s+deals\\s+to\\s+a\\s+Forward\\s+" +
        "becomes\\s+double\\s+the\\s+damage\\s+instead[.!]?" +
        "(?:\\s+You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+once\\s+per\\s+turn\\.?)?"
    );
    /**
     * Matches "During this turn, if your ability deals damage to a Forward, double the damage instead."
     */
    static final Pattern DOUBLE_PLAYER_ABILITY_OUTGOING_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+your\\s+ability\\s+deals?\\s+damage\\s+to\\s+a\\s+Forward,\\s+double\\s+the\\s+damage\\s+instead[.!]?"
    );

    // =========================================================================================
    // Power-boost followups and Counters on a target
    // =========================================================================================
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
    static final Pattern FIELD_SELF_POWER_BOOST = Pattern.compile(
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
    static final Pattern FOLLOWUP_POWER_BOOST = Pattern.compile(
        "(?i)(?:it|they)\\s+gains?\\s+\\+(\\d+)\\s+[Pp]ower" +
        "((?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:(?:the|your)\\s+)?turn"
    );
    /**
     * Matches either word order of the "gains +N power for each [Element | Category X] [Type] you
     * control" followup:
     * <ul>
     *   <li>"Until end of turn, it gains +N power for each [Element] Type you control."</li>
     *   <li>"It gains +N power for each Category VI Character you control until end of turn." —
     *       19-089H Gau</li>
     * </ul>
     * Groups: {@code amount} = per-unit amount, {@code element} = optional element,
     * {@code category} = optional category, {@code chartype} = card type. Groups are doubled
     * because a named group may not repeat across alternatives: the {@code 2}-suffixed set belongs
     * to the trailing-duration order.
     *
     * <p>Kept in step with {@link #SELF_POWER_BOOST_FOR_EACH_CONTROLLED}, the self-targeted twin.
     */
    static final Pattern FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH = Pattern.compile(
        "(?i)(?:" +
            "Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
            "(?:it|they)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
            "(?:Category\\s+(?<category>\\S+)\\s+)?" +
            "(?<chartype>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control" +
        "|" +
            "(?:it|they)\\s+gains?\\s+\\+(?<amount2>\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "(?:(?<element2>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
            "(?:Category\\s+(?<category2>\\S+)\\s+)?" +
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
    static final Pattern FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_COUNTER = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+gains?\\s+\\+(?<perunit>\\d+)\\s+[Pp]ower\\s+" +
        "for\\s+each\\s+(?<counterName>.+?)\\s+Counters?\\s+placed\\s+on\\s+.+?[.!]?$",
        Pattern.DOTALL
    );
    /**
     * Matches "Place N [Name] Counter(s) on it[/them]."
     * Groups: {@code count} — number of counters; {@code name} — counter type name.
     */
    static final Pattern FOLLOWUP_PLACE_COUNTER_ON_IT = Pattern.compile(
        "(?i)Place\\s+(?<count>\\d+)\\s+(?<name>.+?)\\s+Counters?\\s+on\\s+(?:it|them)[.!]?"
    );
    /**
     * Matches "Select 1 Counter placed on it, and remove the selected Counter."
     * The counter type is chosen by the player at resolution time (dialog if multiple types).
     */
    static final Pattern FOLLOWUP_REMOVE_ONE_COUNTER = Pattern.compile(
        "(?i)Select\\s+1\\s+Counter\\s+placed\\s+on\\s+(?:it|them)[,.]?\\s+" +
        "and\\s+remove\\s+the\\s+selected\\s+Counter[.!]?"
    );
    /**
     * Matches "Deal it N damage for each [Name] Counter(s) placed on [card]."
     * Groups: {@code perunit} = damage per counter; {@code counterName} = counter type name.
     * Uses {@code xValue} captured before any BZ-cost payment cleared the counters.
     * Must be checked before {@link #FOLLOWUP_DAMAGE_FOR_EACH}, which would match only the flat N damage.
     */
    static final Pattern FOLLOWUP_DAMAGE_FOR_EACH_COUNTER = Pattern.compile(
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
    static final Pattern FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_JOB = Pattern.compile(
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
    static final Pattern FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_SELF_DMG = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+gains?\\s+\\+(?<perunit>\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+point\\s+of\\s+damage\\s+you\\s+have\\s+received[.!]?"
    );
    static final Pattern FOLLOWUP_POWER_BOOST_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:(?:the|your)\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+gains?\\s+\\+(\\d+)\\s+[Pp]ower" +
        "((?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)"
    );
    /**
     * "It/they gains [TraitA] or [TraitB] until [the] end of [the] turn." — player picks one trait.
     * Groups {@code t1} and {@code t2} are the two trait names.  Must be checked before
     * {@link #FOLLOWUP_KEYWORD_GRANT} since the latter doesn't handle the "or" separator.
     */
    static final Pattern FOLLOWUP_KEYWORD_GRANT_CHOICE = Pattern.compile(
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
    static final Pattern FOLLOWUP_KEYWORD_GRANT = Pattern.compile(
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
    static final Pattern FOLLOWUP_KEYWORD_GRANT_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+gains?\\s+" +
        "((?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))+)" +
        "[.!]?"
    );

    // =========================================================================================
    // Standalone power and trait grants
    // =========================================================================================
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
    static final Pattern STANDALONE_POWER_BOOST_AND_ATTACK_TRIGGER = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?<subject>.+?)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower\\s+and\\s+" +
        "\"When\\s+[^\"]+?\\s+attacks?\\s*,\\s+(?<attackEffect>[^\"]+?)\"\\s*[.!]?\\s*$",
        Pattern.DOTALL
    );
    /**
     * Matches "Until the end of the turn, [Name] gains +N power and [Name]/it cannot be
     * chosen by your opponent's Summons/abilities." (Quina) — a self-buff granting a power
     * boost AND opponent-targeting protection simultaneously.
     * <ul>
     *   <li>Group {@code subject}  — card name before "gains"; must match {@code source.name()}</li>
     *   <li>Group {@code amount}   — power boost value</li>
     *   <li>Group {@code subject2} — card name (or "it") before "cannot be chosen"</li>
     *   <li>Group {@code scope}    — "Summons", "abilities", or "Summons or abilities"</li>
     * </ul>
     */
    static final Pattern STANDALONE_POWER_BOOST_AND_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?<subject>.+?)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower\\s+and\\s+" +
        "(?<subject2>.+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "(?<scope>Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );
    /**
     * Matches "Until the end of the turn, [name] gains [traits] and '[name] cannot be blocked.'"
     * Used when a self-buff grants keyword traits AND unblockable status simultaneously.
     * Groups: {@code subject} — card name; {@code traits} — keyword list.
     */
    static final Pattern STANDALONE_GAINS_TRAITS_AND_CANNOT_BE_BLOCKED = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?<subject>.+?)\\s+gains?\\s+" +
        "(?<traits>(?:Haste|First\\s+Strike|Brave)(?:\\s+and\\s+(?:Haste|First\\s+Strike|Brave))*)" +
        "\\s+and\\s+\".+?\\s+cannot\\s+be\\s+blocked\\.?\"[.!]?"
    );
    /**
     * Matches "[name] gains [traits] and '[name] cannot be blocked.' until the end of the turn."
     * — trailing-order sibling of {@link #STANDALONE_GAINS_TRAITS_AND_CANNOT_BE_BLOCKED} (e.g.
     * Queen's Speedrush: {@code Queen gains Haste and "Queen cannot be blocked" until the end of
     * the turn.}). Groups: {@code subject} — card name; {@code traits} — keyword list.
     */
    static final Pattern STANDALONE_GAINS_TRAITS_AND_CANNOT_BE_BLOCKED_TRAILING = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+gains?\\s+" +
        "(?<traits>(?:Haste|First\\s+Strike|Brave)(?:\\s+and\\s+(?:Haste|First\\s+Strike|Brave))*)" +
        "\\s+and\\s+\".+?\\s+cannot\\s+be\\s+blocked\\.?\"" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );
    /** Matches "Choose 1 card removed from the game. Remove 1 Warp Counter from it." */
    static final Pattern CHOOSE_WARP_CARD_REMOVE_COUNTER = Pattern.compile(
        "(?i)^Choose\\s+1\\s+card\\s+removed\\s+from\\s+the\\s+game\\.\\s*" +
        "Remove\\s+1\\s+Warp\\s+Counter\\s+from\\s+it[.!]?"
    );
    /**
     * Matches "Choose 1 card removed from the game with a Warp Counter on it. You may remove 1
     * Warp Counter from it." (Vayne) — the optional-removal variant: choosing the target is
     * mandatory, but the removal itself is a "you may" decision.
     */
    static final Pattern CHOOSE_WARP_CARD_MAY_REMOVE_COUNTER = Pattern.compile(
        "(?i)^Choose\\s+1\\s+card\\s+removed\\s+from\\s+the\\s+game\\s+with\\s+a\\s+Warp\\s+Counter\\s+on\\s+it\\.\\s*" +
        "You\\s+may\\s+remove\\s+1\\s+Warp\\s+Counter\\s+from\\s+it[.!]?"
    );
    /** Matches "[Name] gains '[Name] cannot be blocked.' until the end of the turn." */
    static final Pattern STANDALONE_GAINS_CANNOT_BE_BLOCKED = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+gains?\\s+\".+?\\s+cannot\\s+be\\s+blocked\\.?\"" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
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
    static final Pattern STANDALONE_POWER_BOOST_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?<subject>.+?)\\s+gains?\\s+" +
        "(?:\\+(?<amount>\\d+)\\s+[Pp]ower)?" +
        "(?<traits>(?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "[.\\s]*$"
    );
    /**
     * Matches "Until the end of the turn, &lt;subject&gt;['s power becomes N | gains traits and
     * &lt;subject&gt;'s power becomes N]" — e.g. Bartz 7-059L's Dual-Wield:
     * "Until the end of the turn, Bartz gains First Strike and Bartz's power becomes 10000."
     *
     * <p>Unlike the "its power becomes N" wording handled by {@link #FOLLOWUP_POWER_BECOMES},
     * this form replaces the card's <em>base</em> power, so boosts and reductions from other
     * effects still apply on top of the new value.
     * <ul>
     *   <li>Group {@code subject} — the card named before "gains" (absent when there is no trait clause)</li>
     *   <li>Group {@code traits}  — the granted keywords (absent when there is no trait clause)</li>
     *   <li>Group {@code powersubject} — the card whose power is set</li>
     *   <li>Group {@code power}   — the new base power</li>
     * </ul>
     */
    static final Pattern STANDALONE_SELF_BASE_POWER_BECOMES_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?:(?<subject>.+?)\\s+gains?\\s+" +
        "(?<traits>(?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))+)\\s+and\\s+)?" +
        "(?<powersubject>.+?)'s\\s+power\\s+becomes\\s+(?<power>\\d+)[.!]?\\s*$"
    );
    /**
     * Matches "Double the power of &lt;subject&gt; until [the] end of [the] turn".
     * <ul>
     *   <li>Group {@code subject} — card name before "until"</li>
     * </ul>
     */
    static final Pattern STANDALONE_DOUBLE_POWER_UNTIL = Pattern.compile(
        "(?i)Double\\s+the\\s+power\\s+of\\s+(?<subject>.+?)\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );
    /**
     * Matches "Until the end of the turn, &lt;subject&gt; doubles its power [and gains traits]".
     * <ul>
     *   <li>Group {@code subject} — card name before "doubles"</li>
     *   <li>Group {@code traits}  — optional trailing text (e.g. "and gains First Strike and Brave")</li>
     * </ul>
     */
    /**
     * Matches "[Self] can attack twice|N times this turn." standing as a sentence of its own —
     * 1-128R Gilgamesh's Morphing Time.
     *
     * <p>The only turn-scoped multi-attack grant in the corpus that is neither printed as a field
     * ability ({@code CardData.FIELD_CAN_ATTACK_TWICE}) nor quoted inside one
     * ({@link #GRANTED_CAN_ATTACK_TWICE}), and the only one spelled "this turn" rather than "in the
     * same turn". It is read from the doubling parser rather than from a dispatch of its own,
     * because that parser already claims the sentence in front of it and no card prints this one
     * alone; the constant is here so a card that ever does has something to reach for.
     *
     * <p>Anchored at both ends. It is tried against whatever follows the clause the doubling
     * pattern matched, and that tail is not always a sentence — see the note on the parser.
     */
    static final Pattern SELF_CAN_ATTACK_N_TIMES_THIS_TURN = Pattern.compile(
        "(?i)^(?<subj>[A-Z][A-Za-z''\\-\\s()]+?)\\s+can\\s+attack\\s+" +
        "(?:twice|(?<count>\\d+)\\s+times)\\s+this\\s+turn[.!]?$"
    );
    static final Pattern STANDALONE_DOUBLES_ITS_POWER_UNTIL = Pattern.compile(
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
    static final Pattern STANDALONE_DOUBLE_POWER_MAIN_PHASE_NEXT_TURN = Pattern.compile(
        "(?i)At\\s+the\\s+beginning\\s+of\\s+your\\s+next\\s+turn's\\s+Main\\s+Phase\\s+1" +
        "\\s+and\\s+until\\s+the\\s+end\\s+of\\s+the\\s+same\\s+turn\\s*,\\s+" +
        "(?<subject>.+?)'s\\s+power\\s+will\\s+double[.!]?"
    );

    // =========================================================================================
    // Power reduction
    // =========================================================================================
    /**
     * Matches "it/they loses/lose [N power] [, traits] until end of turn".
     * Both power and traits are optional, but at least one must be present in practice.
     * <ul>
     *   <li>Group 1 — optional numeric power amount (absent = traits-only)</li>
     *   <li>Group 2 — optional traits string</li>
     * </ul>
     */
    static final Pattern FOLLOWUP_POWER_REDUCE = Pattern.compile(
        "(?i)(?:it|they)\\s+loses?\\s+" +
        "(?:(\\d+)\\s+[Pp]ower)?" +
        "((?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn"
    );
    /** Matches "Its/Their power becomes N until the end of the turn." — group 1 is the target power. */
    /**
     * Matches "If N or more [X] Counters are placed on [CardName], its power also becomes P until
     * the end of the turn." — Porom 15-119L's second sentence.
     *
     * <p>A rider on the Forward the first sentence already chose, so it is built where
     * {@code lastChosenTargets()} can reach it rather than parsed as a standalone effect: "its"
     * names that Forward, while the counters counted are the ability source's own.
     *
     * <p>Groups: {@code count} — counters required; {@code countername} — which kind;
     * {@code name} — the card they sit on; {@code power} — the power the chosen Forward drops to.
     */
    static final Pattern SECONDARY_IF_SOURCE_COUNTERS_POWER_BECOMES = Pattern.compile(
        "(?i)^If\\s+(?<count>\\d+)\\s+or\\s+more\\s+(?<countername>\\S+)\\s+Counters?\\s+are\\s+placed\\s+on\\s+" +
        "(?<name>[^,]+),\\s+its\\s+power\\s+also\\s+becomes\\s+(?<power>\\d+)\\s+until\\s+" +
        "(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?$"
    );
    /**
     * "If its power has been increased or decreased, break it." — 12-049H Diabolos, the choose
     * followup that breaks only a Forward whose power is not the one printed on it.
     *
     * <p>Anchored end to end, and read ahead of the plain break followup: that one scans with
     * {@code find()} and matched "break it" inside this sentence, dropping the condition and
     * breaking whatever was chosen.
     */
    static final Pattern FOLLOWUP_BREAK_IF_POWER_CHANGED = Pattern.compile(
        "(?i)^If\\s+(?:its|their)\\s+power\\s+has\\s+been\\s+(?:increased\\s+or\\s+decreased" +
        "|decreased\\s+or\\s+increased),\\s+break\\s+(?:it|them)[.!]?$"
    );
    /**
     * "If it has N power or less, break it. If you control &lt;condition&gt;, break it regardless
     * of its power instead." — 3-102R Odin, the corpus's only printing of a power-gated break with
     * a control condition that lifts the gate.
     *
     * <p>Two sentences read as one clause, for the reason its sibling above gives and then some:
     * both act on the same chosen Forward, and "instead" makes the second a replacement for the
     * first's test rather than a second break. Left to the plain break followup, {@code find()}
     * matched "break it" in the first sentence and broke whatever was chosen — so the card ignored
     * its power gate <em>and</em> the condition that lifts it, and was strictly stronger than
     * printed against every Forward on the table.
     * Groups: {@code power}, {@code cond}.
     */
    static final Pattern FOLLOWUP_BREAK_IF_POWER_CONTROL_GATED_INSTEAD = Pattern.compile(
        "(?i)^If\\s+(?:it|they)\\s+(?:has|have)\\s+(?<power>\\d+)\\s+power\\s+or\\s+less,\\s+" +
        "break\\s+(?:it|them)[.!]\\s+" +
        "If\\s+you\\s+control\\s+(?<cond>.+?),\\s+break\\s+(?:it|them)\\s+regardless\\s+of\\s+" +
        "(?:its|their)\\s+power\\s+instead[.!]?\\s*$"
    );
    /**
     * The board-wide form of the followup below: "All the Forwards' power become N until the end
     * of the turn." — 15-053H Diabolos's upgraded branch, and the corpus's only printing of it.
     *
     * <p>Must be checked ahead of {@link #FOLLOWUP_POWER_BECOMES}: that one scans with
     * {@code find()} and neither of its arms matches this wording, but the two describe the same
     * act at different scopes and reading them in the wrong order would set one Forward's power
     * where the card sets every Forward's.
     * Group: {@code power}.
     */
    static final Pattern FOLLOWUP_ALL_FORWARDS_POWER_BECOMES = Pattern.compile(
        "(?i)^All\\s+(?:the\\s+)?Forwards?'?s?\\s+powers?\\s+becomes?\\s+(?<power>\\d+)\\s+" +
        "until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?$"
    );
    /**
     * "Its/their power becomes N until the end of the turn", in either word order: the duration
     * may trail the clause or lead it ("Until the end of the turn, its power becomes 1000." —
     * 5-062L Diabolos's second option and 3-066R Barbariccia, the corpus's only two printings of
     * the fronted form). Both arms are required to state the duration, because "its power becomes
     * N" with no duration is a permanent change and a different effect.
     *
     * <p>Group {@code power} for the trailing form, {@code powerFront} for the fronted one; read
     * them with {@link ActionResolver#powerBecomesAmount}, since only one can be set per match.
     */
    static final Pattern FOLLOWUP_POWER_BECOMES = Pattern.compile(
        "(?i)(?:" +
            "(?:its?|their)\\s+power\\s+becomes?\\s+(?<power>\\d+)\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn" +
        "|" +
            "until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,\\s+(?:its?|their)\\s+power\\s+becomes?\\s+(?<powerFront>\\d+)" +
        ")[.!]?"
    );
    /**
     * Matches "Until the end of the turn, it/they loses/lose [N power] [and traits]".
     * <ul>
     *   <li>Group 1 — optional numeric power amount</li>
     *   <li>Group 2 — optional traits string</li>
     * </ul>
     */
    static final Pattern FOLLOWUP_POWER_REDUCE_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+loses?\\s+" +
        "(?:(\\d+)\\s+[Pp]ower)?" +
        "((?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)"
    );
    /** Matches "Until [of] the end of [the] turn, it/they loses N power for each card in your hand." */
    /**
     * The same reduction with a draw in front of it: "Draw N card(s). Then, until the end of the
     * turn, it loses M power for each card in your hand." — 12-108C Remora.
     *
     * <p>Read off the whole followup rather than after the sentence split, and given a branch of
     * its own rather than left to the plain reduction plus a secondary, because the order is the
     * point: the card drawn is in hand before the hand is counted, so the draw is worth M power of
     * reduction on top of whatever the hand already held. Split apart, the reduction ran against
     * the smaller hand — when it ran at all.
     * Groups: {@code draw}, {@code amount}.
     */
    static final Pattern FOLLOWUP_DRAW_THEN_POWER_REDUCE_FOR_EACH_HAND = Pattern.compile(
        "(?i)^Draw\\s+(?<draw>\\d+)\\s+cards?[.!]\\s*Then\\s*,?\\s*" +
        "until\\s+(?:of\\s+)?(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+loses?\\s+(?<amount>\\d+)\\s+power\\s+for\\s+each\\s+card\\s+in\\s+your\\s+hand[.!]?\\s*$"
    );
    static final Pattern FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH_HAND = Pattern.compile(
        "(?i)Until\\s+(?:of\\s+)?(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+loses?\\s+(\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+card\\s+in\\s+your\\s+hand[.!]?"
    );
    /**
     * Matches either word order of the "loses N power for each [state] [Element] [Type]
     * you control / opponent controls" followup (the reduce counterpart of
     * {@link #FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH}):
     * <ul>
     *   <li>"Until end of turn, it loses N power for each [Element] Type you control."</li>
     *   <li>"It loses N power for each [Element] Type you control until end of turn."</li>
     *   <li>"It loses N power for each dull Character opponent controls until end of turn."</li>
     * </ul>
     * Groups: {@code amount}/{@code state}/{@code element}/{@code chartype}/{@code opp} for the
     * until-prefix order, the same names suffixed {@code 2} for the suffix order. All named —
     * {@code amount} and {@code amount2} used to be read by index, which broke the moment the
     * state and controller groups were added in front of them.
     *
     * <p>The state adjective and the opponent-side controller are only ever read together
     * (2-133R C&ucirc;chulainn, the Impure is the sole printing of either), because the counting
     * surface has no self-side call taking a card state — see the guard in the handlers.
     */
    /**
     * Matches "It loses N power for each attacking Forward until the end of the turn." — 12-105L
     * Yuna, whose party-attack trigger scales with the size of the party it fired on.
     *
     * <p>Separate from {@link #FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH} rather than another branch of
     * it: every alternative there counts a field the text names a controller for ("you control",
     * "opponent controls"), and what is counted here is the attacking party, which is neither.
     *
     * <p>Must be checked before {@link #FOLLOWUP_POWER_REDUCE_BARE}, which finds "it loses 4000
     * power" inside this sentence and would apply a flat reduction, dropping the multiplier.
     */
    static final Pattern FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH_ATTACKER = Pattern.compile(
        "(?i)^(?:it|they)\\s+loses?\\s+(?<amount>\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+attacking\\s+" +
        "Forward\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?$"
    );
    static final Pattern FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH = Pattern.compile(
        "(?i)(?:" +
            "Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
            "(?:it|they)\\s+loses?\\s+(?<amount>\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "(?:(?<state>dull|active|damaged)\\s+)?" +
            "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
            "(?<chartype>Forwards?|Backups?|Monsters?|Characters?)\\s+" +
            "(?:you\\s+control|(?<opp>(?:your\\s+)?opponent)\\s+controls)" +
        "|" +
            "(?:it|they)\\s+loses?\\s+(?<amount2>\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "(?:(?<state2>dull|active|damaged)\\s+)?" +
            "(?:(?<element2>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
            "(?<chartype2>Forwards?|Backups?|Monsters?|Characters?)\\s+" +
            "(?:you\\s+control|(?<opp2>(?:your\\s+)?opponent)\\s+controls)" +
            "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn" +
        ")[.!]?"
    );
    /** Matches "it/they loses N power" with no timing qualifier — implied EOT in former/latter context. */
    static final Pattern FOLLOWUP_POWER_REDUCE_BARE = Pattern.compile(
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
    static final Pattern STANDALONE_POWER_REDUCE_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?<subject>.+?)\\s+loses?\\s+" +
        "(?:(?<amount>\\d+)\\s+[Pp]ower)?" +
        "(?<traits>(?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "[.\\s]*$"
    );

    // =========================================================================================
    // Mass field effects
    // =========================================================================================
    /**
     * Matches a mass ACTIVATE whose payoff counts what it woke up: "&lt;sweep&gt;. When N or more
     * dull Characters are activated by this effect, draw M card(s)." — 19-102L Refia.
     *
     * <p>Group {@code sweep} is handed back to {@link ActionResolverFieldAbility#tryParseAllFieldEffect}
     * rather than re-parsed here, so the two sentences cannot describe different sweeps. Groups
     * {@code threshold} and {@code draw} are captured rather than fixed at 4 and 1 because nothing
     * about the wording makes those numbers special.
     */
    static final Pattern ALL_FIELD_ACTIVATE_THEN_DRAW = Pattern.compile(
        "(?i)^(?<sweep>Activate\\s+all\\s+.+?)[.!]\\s*" +
        "When\\s+(?<threshold>\\d+)\\s+or\\s+more\\s+dull\\s+Characters?\\s+are\\s+activated\\s+" +
        "by\\s+this\\s+effect,\\s*draw\\s+(?<draw>\\d+)\\s+cards?[.!]?$");

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
    static final Pattern ALL_FIELD_EFFECT_PATTERN = Pattern.compile(
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
        // Trails the control clause, unlike the trait filter above it: "break all the Forwards
        // opponent controls with a Doom Counter on them". Without this the regex ended at
        // "controls" and find() quietly discarded the restriction, breaking every Forward.
        "(?:\\s+with\\s+(?:a|an|\\d+)\\s+(?<counter>[A-Za-z][A-Za-z ]*?)\\s+Counters?\\s+on\\s+(?:it|them))?" +
        // Same reason as the counter arm, and the same consequence: without it the regex ended at
        // "Forwards" and find() discarded "with power less than Titan, Lord of Crags", turning
        // 14-062L's sweep of everything smaller than itself into a break of every Forward on the
        // table — its controller's included, which is the opposite of what the card does.
        // Captured so tryParseAllFieldEffect can decline rather than honour it: the mass-effect
        // primitive it calls has no power filter to pass this to.
        "(?:\\s+with\\s+power\\s+(?<powercmp>less|more)\\s+than\\s+(?<powercard>[^.!]+?))?" +
        "[.!]?"
    );
    /**
     * 14-062L Titan, Lord of Crags: "Break all the Forwards with power less than [Self]. When N or
     * more Forwards are put from the field into the Break Zone by this effect, [Self] deals your
     * opponent M point(s) of damage."
     *
     * <p>Anchored end to end, and both sentences read together, because the second one counts what
     * the first one did — splitting them would leave the payoff with no sweep to ask about. The
     * tail is optional so the sweep alone still parses if a printing ever states it without one.
     *
     * <p>Groups: {@code card} — the Forward whose power is the threshold, checked against the
     * carrier; {@code threshold}, {@code dmgcard}, {@code amount} — the payoff.
     */
    static final Pattern BREAK_FORWARDS_BELOW_SELF_POWER = Pattern.compile(
        "(?i)^Break\\s+all\\s+(?:the\\s+)?Forwards\\s+with\\s+power\\s+less\\s+than\\s+(?<card>[^.!]+?)[.!]" +
        "(?:\\s*When\\s+(?<threshold>\\d+)\\s+or\\s+more\\s+Forwards?\\s+are\\s+put\\s+from\\s+the\\s+field\\s+" +
        // Bounded on sentence punctuation rather than on a comma: the card naming itself here is
        // the same card whose name carries one ("Titan, Lord of Crags"), and [^,]+? cannot span it.
        "into\\s+the\\s+Break\\s+Zone\\s+by\\s+this\\s+effect,\\s*(?<dmgcard>[^.!]+?)\\s+deals?\\s+your\\s+" +
        "opponent\\s+(?<amount>\\d+)\\s+points?\\s+of\\s+damage[.!]?)?\\s*$"
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
    static final Pattern ALL_FIELD_POWER_BOOST_PATTERN = Pattern.compile(
        // "All the Forwards …", "All Forwards …" and the article-only "The Forwards …" are one
        // effect; 2-072C Reddas prints the third. The article is required rather than the whole
        // lead-in being optional — with it optional, find() would start on any bare "Forwards".
        "(?i)(?:All\\s+(?:the\\s+)?|The\\s+)" +
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
     * Matches the party-attack followup that boosts every Forward in the party that just formed
     * and attacked, in either of the two printed phrasings:
     * <ul>
     *   <li>"all Forwards in that party gain/lose +N power until [the] end of [the] turn."
     *       (Gippal +5000, Celestia / Chocobo 9-050C +1000)</li>
     *   <li>"[Self] and all the Forwards forming a party with it gain/lose +N power until [the]
     *       end of [the] turn." (Chocobo 1-075C / 4-062C +3000, Chocobo 1-076C +2000)</li>
     * </ul>
     * The two name the same set — the card forming the party is itself a member of it — so both
     * resolve through {@link GameContext#applyCurrentPartyForwardsPowerBoost} against the
     * recorded attacking party.  The subject of the second form is left unanchored rather than
     * matched against the card's name, so reprints and aliases are not excluded by a name that
     * no longer matches; the trigger has already established whose party attacked.
     * Groups: {@code verb}, {@code amount}.
     */
    static final Pattern PARTY_FORWARDS_POWER_BOOST_PATTERN = Pattern.compile(
        "(?i)(?:all\\s+Forwards?\\s+in\\s+that\\s+party" +
        "|[A-Za-z][^.,]*?\\s+and\\s+all\\s+the\\s+Forwards?\\s+forming\\s+a\\s+party\\s+with\\s+it)\\s+" +
        "(?<verb>gains?|loses?)\\s+\\+?(?<amount>\\d+)\\s+[Pp]ower" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );
    /**
     * Matches "All [the] Forwards of the same Element as [Card Name] X you control
     * gain +N power until [the] end of [the] turn."
     * Groups: {@code name}, {@code control}, {@code verb}, {@code amount}.
     */
    static final Pattern ALL_FORWARDS_SAME_ELEMENT_AS_NAMED_POWER_BOOST = Pattern.compile(
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
    static final Pattern ALL_FIELD_JOB_CARDNAME_POWER_BOOST_PATTERN = Pattern.compile(
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
    static final Pattern TWO_CARD_NAMES_POWER_BOOST_PATTERN = Pattern.compile(
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
    static final Pattern ALL_FIELD_JOB_POWER_BOOST_PATTERN = Pattern.compile(
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
    static final Pattern ALL_FIELD_JOB_KEYWORD_GRANT_PATTERN = Pattern.compile(
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
    static final Pattern ALL_FIELD_KEYWORD_GRANT_PATTERN = Pattern.compile(
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
     * Matches "[Until the end of the turn,] All [the] [element] [targets] [you control] gain
     * "&lt;quoted protection&gt;"[ and "&lt;another&gt;"] [until the end of the turn]." — the
     * board-wide twin of the single-target grant {@link #FOLLOWUP_CANNOT_BECOME_DULL_BY_OPP}
     * reads. 10-076H Titan's third option and 23-039R Asura print it in opposite word orders, so
     * the duration is accepted at either end; one of the two positions must be filled, which is
     * what keeps this off a permanent printed field ability of the same shape.
     *
     * <p>{@code grants} is the whole quoted blob, delimiters included, because the two printings
     * do not quote the same way — Titan uses single quotes, which are also the apostrophe in
     * "your opponent's" and so cannot be split on. {@link ActionResolver#grantedProtectionTraits}
     * takes it apart and declines any grant that is not a protection with a trait behind it.
     * Groups: {@code element}, {@code targets}, {@code control}, {@code grants}.
     */
    static final Pattern ALL_FIELD_QUOTED_PROTECTION_GRANT = Pattern.compile(
        "(?i)^(?<pre>Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,\\s+)?" +
        "All\\s+(?:the\\s+)?" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<targets>Forwards?|Characters?)" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+(?:also\\s+)?gains?\\s+(?<grants>[\"'].+[\"'])" +
        "(?<post>\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn)?[.!]?\\s*$"
    );
    /**
     * Matches "Until end of turn, all [the] [element] [Category X] [targets] [you control]
     * gain/lose +N power [and Keywords]."
     * Groups: {@code element}, {@code category}, {@code targets}, {@code cost}, {@code costcmp},
     * {@code control}, {@code verb}, {@code amount}, {@code keywords}.
     */
    static final Pattern UNTIL_EOT_ALL_FIELD_POWER_BOOST_PATTERN = Pattern.compile(
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
    static final Pattern UNTIL_EOT_DUAL_POWER_SHIFT_PATTERN = Pattern.compile(
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


    // =========================================================================================
    // Select a number
    // =========================================================================================
    // ---- "Select 1 number" patterns -------------------------------------------
    /** Matches the "Select 1 number." opening of an ability that lets the active player pick a cost. */
    static final Pattern SELECT_NUMBER_HEADER = Pattern.compile(
        "(?i)^Select\\s+1\\s+number\\.\\s*"
    );
    /** Matches "Your opponent selects 1 number." — appears as a second header in dual-selection abilities. */
    static final Pattern SELECT_NUMBER_OPPONENT_ALSO = Pattern.compile(
        "(?i)^Your\\s+opponent\\s+selects\\s+1\\s+number\\.\\s*"
    );
    /**
     * Inner effect: "All [the] Forwards of that cost cannot attack this turn."
     * Cannot be handled by the general substitution path since "cannot attack" is not
     * a MassAction in {@link GameContext.MassAction}.
     */
    static final Pattern SELECT_NUMBER_INNER_CANNOT_ATTACK = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Forwards?\\s+of\\s+that\\s+cost\\s+cannot\\s+attack\\s+this\\s+turn\\.?"
    );
    /**
     * Inner effect for the dual-number case: "Break all Forwards of cost equal to either number."
     * Both P1's and P2's chosen numbers are used as cost filters.
     */
    static final Pattern SELECT_NUMBER_INNER_EITHER_BREAK = Pattern.compile(
        "(?i)Break\\s+all\\s+Forwards?\\s+of\\s+cost\\s+equal\\s+to\\s+either\\s+number\\.?"
    );
    /**
     * Followup used inside {@link #tryParseChooseCharacter}:
     * "Select 1 number and reveal the top card of your deck.
     *  If the revealed card is of the same cost as the selected number, break it."
     * "It" refers to the previously chosen Forward, not the revealed card.
     */
    static final Pattern FOLLOWUP_SELECT_NUMBER_REVEAL_BREAK = Pattern.compile(
        "(?i)Select\\s+1\\s+number\\s+and\\s+reveal\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck\\.\\s+" +
        "If\\s+the\\s+revealed\\s+card\\s+is\\s+of\\s+the\\s+same\\s+cost\\s+as\\s+the\\s+selected\\s+number,\\s+break\\s+it\\.?"
    );
    /**
     * Followup used inside {@link #tryParseChooseCharacter}:
     * "Select a Job. It gains that Job until the end of the turn." or
     * "Name 1 Job. It gains the named Job until the end of the turn."
     * Matched against the full followup (before the dot-split) so both sentences are seen together.
     */
    static final Pattern FOLLOWUP_SELECT_JOB_GRANT = Pattern.compile(
        "(?i)^(?:Select\\s+a|Name\\s+1)\\s+Job[.!]?\\s+" +
        "It\\s+gains?\\s+(?:that|the\\s+named)\\s+Job\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?$"
    );

    // =========================================================================================
    // Look at the top of the deck
    // =========================================================================================
    /**
     * Matches "Look at the top card of your deck. You may put it into the Break Zone."
     */
    static final Pattern LOOK_TOP_DECK_OPTIONALLY_BREAK = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "You\\s+may\\s+put\\s+it\\s+into\\s+the\\s+Break\\s+Zone[.!]?"
    );
    /**
     * Matches "Look at the top card of your deck. You may place the card at the bottom of your deck."
     *
     * <p>28-102R Princess Sarah prints the second sentence as "You may put it at the bottom of your
     * deck", so both verbs and the pronoun object are admitted. The two readings are the same
     * effect — the card either goes to the bottom or stays where it is.
     */
    static final Pattern LOOK_TOP_DECK_BOTTOM_OR_KEEP = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "You\\s+may\\s+(?:place|put)\\s+(?:the\\s+card|it)\\s+at\\s+the\\s+bottom\\s+of\\s+your\\s+deck[.!]?"
    );
    /**
     * Matches "Look at the top N cards of your deck. Return them to the top of your deck in any order."
     * <ul>
     *   <li>Group {@code count} — number of cards to look at</li>
     * </ul>
     */
    static final Pattern LOOK_TOP_DECK_RETURN_TOP_ORDERED = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "Return\\s+them\\s+to\\s+the\\s+top\\s+of\\s+your\\s+deck\\s+in\\s+any\\s+order[.!]?"
    );
    /**
     * Matches "Look at / Reveal the top N cards of your deck. Add 1 card among them to your hand
     * and return the other cards to the bottom of your deck in any order."  Cards that continue
     * past this clause are handled by {@link #ADDED_CARD_EX_BURST_RIDER}.
     * <ul>
     *   <li>Group {@code count} — number of cards to look at / reveal</li>
     *   <li>Group {@code verb}  — which wording was used; "Reveal" makes the cards public</li>
     * </ul>
     */
    static final Pattern LOOK_TOP_DECK_ADD_TO_HAND_REST_BOTTOM = Pattern.compile(
        "(?i)(?<verb>Look\\s+at|Reveal)\\s+the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "Add\\s+1\\s+card\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+and\\s+" +
        "return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+your\\s+deck\\s+in\\s+any\\s+order[.!]?"
    );
    /**
     * Matches Lunafreya 23-129H's rider on the clause above: "If the card added to your hand has
     * an EX Burst, you may trigger its EX Burst effect." plus its parenthetical rules note.
     */
    static final Pattern ADDED_CARD_EX_BURST_RIDER = Pattern.compile(
        "(?i)^[\\s.!]*If\\s+the\\s+card\\s+added\\s+to\\s+your\\s+hand\\s+has\\s+an\\s+EX\\s+Burst,\\s*" +
        "you\\s+may\\s+trigger\\s+its\\s+EX\\s+Burst\\s+effect[.!]?" +
        "(?:\\s*\\([^)]*\\))?\\s*$"
    );
    /**
     * Matches "Look at the top N cards of your deck. Add 1 card among them to your hand,
     * put 1 card into the Break Zone and return the other cards to the bottom of your deck
     * in any order."
     * <ul>
     *   <li>Group {@code count} — number of cards to look at</li>
     * </ul>
     */
    static final Pattern LOOK_TOP_DECK_ADD_TO_HAND_ONE_TO_BREAK_REST_BOTTOM = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "Add\\s+1\\s+card\\s+among\\s+them\\s+to\\s+your\\s+hand[,.]?\\s*" +
        "put\\s+1\\s+card\\s+into\\s+the\\s+Break\\s+Zone\\s+and\\s+" +
        "return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+your\\s+deck\\s+in\\s+any\\s+order[.!]?"
    );
    /**
     * Matches "Look at / Reveal the top N cards of your deck. Add 1 [Element|Category X] card
     * among them to your hand and put the rest of the cards into the Break Zone."
     * <ul>
     *   <li>Group {@code count}    — number of cards to look at / reveal</li>
     *   <li>Group {@code verb}     — which wording was used; "Reveal" makes the cards public</li>
     *   <li>Group {@code element}  — optional element filter on the card added to hand</li>
     *   <li>Group {@code category} — optional category filter on the card added to hand; the two
     *       filters are alternatives, no card in the corpus states both</li>
     * </ul>
     * The category is captured non-greedily and anchored by the "card among them" that follows,
     * so multi-word categories are picked up whole rather than truncated at the first space.
     */
    static final Pattern LOOK_TOP_DECK_ADD_TO_HAND_REST_BREAK = Pattern.compile(
        "(?i)(?<verb>Look\\s+at|Reveal)\\s+the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "Add\\s+1\\s+(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+" +
        "|Category\\s+(?<category>.+?)\\s+)?card\\s+among\\s+them\\s+to\\s+your\\s+hand[,]?\\s+and\\s+" +
        "put\\s+the\\s+rest\\s+(?:of\\s+the\\s+cards?\\s+)?into\\s+the\\s+Break\\s+Zone[.!]?"
    );
    /**
     * Matches "Look at the top N cards of your deck. Return these to the top and/or bottom of
     * your deck in any order."  Anything the card adds after this clause is picked up separately
     * via {@link #TRAILING_THEN_CLAUSE} (Schultz 27-100R chains a reveal onto it).
     * <ul>
     *   <li>Group {@code count} — number of cards to look at</li>
     * </ul>
     */
    static final Pattern LOOK_TOP_DECK_TOP_OR_BOTTOM = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "Return\\s+(?:them|these)\\s+to\\s+the\\s+top\\s+and[/\\s]?(?:or\\s+)?bottom\\s+of\\s+your\\s+deck\\s+in\\s+any\\s+order[.!]?"
    );
    /**
     * Matches the text left over after a primary clause when the card continues with a
     * "Then, [effect]" sentence.  Group {@code rest} is the follow-on effect text, ready to be
     * handed back to {@link #parse}.
     */
    static final Pattern TRAILING_THEN_CLAUSE = Pattern.compile(
        "(?i)^[\\s.!]*Then,?\\s+(?<rest>\\S.*)$", Pattern.DOTALL
    );
    /**
     * Matches "Look at the top N cards of your deck. Put 1 card among them on top of your
     * deck and the other(s) to the bottom of your deck."
     * Strict 1-to-top, rest-to-bottom split.
     * <ul>
     *   <li>Group {@code count} — number of cards to look at</li>
     * </ul>
     */
    static final Pattern LOOK_TOP_DECK_PICK_ONE_TOP_REST_BOTTOM = Pattern.compile(
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
    static final Pattern LOOK_TOP_DECK_PEEK = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+(?:(?<count>\\d+)\\s+cards?|card)\\s+of\\s+your\\s+deck[.!]?"
    );
    /**
     * Matches "Look at the top X cards of your deck. Reveal 1 Summon of cost X or less among
     * them and cast it without paying the cost. Then, shuffle the other cards and return them
     * to the bottom of your deck."
     * Groups: {@code count} — card count (numeric or {@code X});
     *         {@code cost}  — cost cap (numeric or {@code X}).
     */
    static final Pattern LOOK_TOP_DECK_CAST_SUMMON_FREE_REST_BOTTOM = Pattern.compile(
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
    static final Pattern REVEAL_TOP_BREAK_SAME_COST_ADD_TO_HAND = Pattern.compile(
        "(?i)Reveal\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Break\\s+all\\s+Forwards?\\s+(?:your\\s+)?opponent\\s+controls?\\s+with\\s+the\\s+same\\s+cost\\s+" +
        "as\\s+the\\s+revealed\\s+card[.!]?\\s+" +
        "Add\\s+the\\s+revealed\\s+card\\s+to\\s+your\\s+hand[.!]?"
    );

    // =========================================================================================
    // Select following actions
    // =========================================================================================
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
     * The surcharge rider on a modal choice: "If you selected N actions, the cost required to cast
     * [Self] is increased by 《C》《C》." — Bahamut SIN 28-087H, the only card that prices its
     * options rather than fixing how many of them you get.
     *
     * <p>Read from both sides of the cast. {@code CardData.extraCost()} turns it into the optional
     * payment the play menu offers, and the modal parser turns it back into how many actions the
     * player may then take — one pattern, so the price on the menu and the count at resolution
     * cannot come apart.
     *
     * <p>Groups: {@code actions} — how many options the surcharge buys; {@code name} — the card
     * being cast, checked against the source's own name by the caller; {@code cost} — the raw cost
     * tokens, currently always Crystals.
     */
    static final Pattern SELECT_FOLLOWING_ACTIONS_COST_INCREASE = Pattern.compile(
        "(?i)If\\s+you\\s+selected\\s+(?<actions>\\d+)\\s+actions?,\\s+the\\s+cost\\s+required\\s+to\\s+cast\\s+"
        + "(?<name>[^.]+?)\\s+is\\s+increased\\s+by\\s+(?<cost>(?:《[^》]*》)+)[.!]?");
    /**
     * Matches an inline conditional upgrade sentence that may appear before the quoted actions:
     * "If you control N or more [Element] [Type], select [up to] M of the K following actions instead."
     * Groups: {@code condCount}, {@code condElement} (optional), {@code condType},
     *         {@code condUpTo} (optional), {@code condSelect}.
     */
    static final Pattern SELECT_FOLLOWING_ACTIONS_CONDITIONAL_UPGRADE = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<condCount>\\d+)\\s+or\\s+more\\s+" +
        "(?:(?<condElement>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<condType>Forwards?|Backups?|Monsters?|Characters?|Summons?),\\s+" +
        "select\\s+(?<condUpTo>up\\s+to\\s+)?(?<condSelect>\\d+)\\s+of\\s+the\\s+\\d+\\s+" +
        "following\\s+actions?\\s+instead[.!]?\\s*",
        Pattern.DOTALL
    );
    /**
     * Matches an inline conditional upgrade gated on the opponent's hand size, appearing before the
     * quoted actions: "If your opponent has [no|N cards or less] cards in their hand, select [up to]
     * M of the K following actions instead." (e.g. Physalis' empty-hand upgrade to select up to 2).
     * Groups: {@code handCount} (absent means "no cards" = 0), {@code handUpTo} (optional),
     *         {@code handSelect}.
     */
    static final Pattern SELECT_FOLLOWING_ACTIONS_HAND_UPGRADE = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+has\\s+" +
        "(?:no\\s+cards?|(?<handCount>\\d+)\\s+cards?\\s+or\\s+less)\\s+in\\s+" +
        "(?:his/her|his|her|their)\\s+hand,\\s+" +
        "select\\s+(?<handUpTo>up\\s+to\\s+)?(?<handSelect>\\d+)\\s+of\\s+the\\s+\\d+\\s+" +
        "following\\s+actions?\\s+instead[.!]?\\s*",
        Pattern.DOTALL
    );

    // =========================================================================================
    // Counters
    // =========================================================================================
    /**
     * Matches "Place N [Name] Counter(s) on [CardName][.]".
     * <ul>
     *   <li>Group {@code count} — number of counters to place</li>
     *   <li>Group {@code name}  — counter name (e.g. {@code "Shuriken"})</li>
     *   <li>Group {@code target} — card name the counters are placed on</li>
     * </ul>
     */
    /**
     * Matches "Place N [Name] Counter(s) on each Job [X] you control." — the end-of-turn tick that
     * grows 15-011L Palom and 15-119L Porom, and every other Apprentice Mage beside them.
     *
     * <p>Must precede {@link #PLACE_COUNTERS}, which is read with find() and would take
     * "each Job Apprentice Mage you control" as a card name. That parser's own source-name check
     * is all that stops it today, so this one is anchored at both ends and goes first.
     *
     * <p>Groups: {@code count}, {@code name} (the counter), {@code job}.
     */
    /**
     * Matches "If [Self] used [SpecialA] and [SpecialB] this turn, [effect]" — 7-059L Bartz's Rapid
     * Fire, which pays off only after both of his other Special abilities have been used.
     *
     * <p>The corpus's only ability that reads back <em>which named Special abilities</em> a card has
     * used, so the tracking behind it is deliberately minimal: a per-turn record of Special names
     * keyed by card, written where an activation's costs are paid.
     *
     * <p>Both names are captured lazily and separated on the last " and " before "this turn", which
     * is safe while the shape stays two names; a third would need a list form here and in the
     * handler.
     *
     * <p>Groups: {@code name} — the card, checked against the source; {@code first} and
     * {@code second} — the Special ability names; {@code effect} — what happens if both were used.
     */
    static final Pattern IF_SOURCE_USED_SPECIALS_THIS_TURN = Pattern.compile(
        "(?i)^If\\s+(?<name>[A-Z][A-Za-z''\\-\\s()]+?)\\s+used\\s+(?<first>.+?)\\s+and\\s+(?<second>.+?)\\s+" +
        "this\\s+turn,\\s+(?<effect>.+)$",
        Pattern.DOTALL
    );
    static final Pattern PLACE_COUNTERS_ON_EACH_JOB = Pattern.compile(
        "(?i)^Place\\s+(?<count>\\d+)\\s+(?<name>.+?)\\s+Counters?\\s+on\\s+each\\s+Job\\s+" +
        "(?<job>.+?)\\s+you\\s+control[.!]?$"
    );
    static final Pattern PLACE_COUNTERS = Pattern.compile(
        "(?i)Place\\s+(?<count>\\d+)\\s+(?<name>.+?)\\s+Counters?\\s+on\\s+(?<target>[^.!,]+)\\s*[.!]?"
    );
    /**
     * Matches "Remove all [Name] Counters from [CardName][.]".
     * <ul>
     *   <li>Group {@code name}   — counter name (e.g. {@code "Fortune"})</li>
     *   <li>Group {@code target} — card name the counters are removed from</li>
     * </ul>
     */
    static final Pattern REMOVE_ALL_COUNTERS = Pattern.compile(
        "(?i)Remove\\s+all\\s+(?<name>.+?)\\s+Counters?\\s+from\\s+(?<target>[^.!,]+)\\s*[.!]?"
    );
    /**
     * Matches "Place N [Name] Counter(s) on [CardName] for each [Type] you control."
     * Groups: {@code count}, {@code name}, {@code target}, {@code type}.
     */
    static final Pattern PLACE_COUNTERS_FOR_EACH = Pattern.compile(
        "(?i)^[Pp]lace\\s+(?<count>\\d+)\\s+(?<name>.+?)\\s+Counters?\\s+on\\s+(?<target>.+?)" +
        "\\s+for\\s+each\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control[.!]?$"
    );
    /**
     * Matches "Remove N Warp Counter(s) from [CardName][ for each [Element] [Category X]
     * [Job Y] [Type] you control]." — 21-007L Shadow, 23-050H Noel.
     *
     * <p>Both ends are anchored, and deliberately so. Unanchored at the front it would claim
     * 29-086H Shadow's "you may remove 2 Warp Counters from Shadow. If you do so, …", dropping the
     * cost prompt and the clause it pays for; unanchored at the back it would swallow the
     * "This effect will trigger only if …" restriction that {@code CardData} leaves in the effect
     * text of some of these cards, silently discarding a trigger condition.
     *
     * <p>Groups: {@code count}, {@code name}, and the optional multiplier's {@code element},
     * {@code category}, {@code job} and {@code type}. Without the "for each" clause the count is
     * flat, which is the far more common printing.
     */
    static final Pattern REMOVE_WARP_COUNTERS_FROM_NAMED = Pattern.compile(
        "(?i)^Remove\\s+(?<count>\\d+)\\s+Warp\\s+Counters?\\s+from\\s+(?<name>[^.,]+?)" +
        "(?:\\s+for\\s+each\\s+" +
            "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
            "(?:Category\\s+(?<category>\\S+)\\s+)?" +
            "(?:Job\\s+(?<job>.+?)\\s+)?" +
            "(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control" +
        ")?[.!]?$"
    );
    /**
     * Matches "If N or more Warp Counters are placed on [CardName], [effect]" — the resolution-time
     * gate on 21-007L Shadow's end-of-turn ability.
     *
     * <p>Warp Counters only, not counters in general. They are the one kind that does not live in
     * the counter map (see {@link GameContext#warpCountersOnNamed}), so the gate needs its own
     * lookup; the other four cards in the corpus printing this sentence shape count Weapon, EXP,
     * Reraise and Barrier Counters, and each is already claimed by a parser of its own.
     */
    static final Pattern WARP_COUNTER_COUNT_GATE = Pattern.compile(
        "(?i)^If\\s+(?<count>\\d+)\\s+or\\s+more\\s+Warp\\s+Counters?\\s+are\\s+placed\\s+on\\s+" +
        "(?<card>[^,]+?),\\s*(?<effect>.+)$",
        Pattern.DOTALL
    );

    // =========================================================================================
    // Choices scaled by a board count
    // =========================================================================================
    /**
     * Matches "Choose 1 Forward opponent controls. [Name] gains its Special Ability until the end of the turn.
     * You can use this ability without paying any cost but only once."
     * Group {@code sourceName} — card name that gains the ability (used for logging).
     */
    static final Pattern CHOOSE_OPP_FWD_GAINS_SPECIAL_ABILITY_FREE_ONCE = Pattern.compile(
        "(?i)^Choose\\s+1\\s+Forward\\s+(?:your\\s+)?opponent\\s+controls[,.]?\\s+" +
        "(?<sourceName>.+?)\\s+gains\\s+its\\s+Special\\s+Abilit(?:y|ies)\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn[.!]?\\s+" +
        "You\\s+can\\s+use\\s+this\\s+ability\\s+without\\s+paying\\s+any\\s+cost\\s+but\\s+only\\s+once[.!]?\\s*$"
    );
    /**
     * Matches "Choose 1 Forward opponent controls which has been dealt damage this turn.
     * If that Forward has a special ability or an action ability, break it."
     */
    static final Pattern CHOOSE_OPP_DAMAGED_FWD_IF_HAS_ABILITY_BREAK = Pattern.compile(
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
    static final Pattern CHOOSE_AS_MANY_AS_FIELD_COUNT = Pattern.compile(
        "(?i)^Choose\\s+(?:as\\s+many|up\\s+to\\s+the\\s+same\\s+number\\s+of)\\s+" +
        "(?<targetType>Forwards?|Characters?|Backups?|Monsters?)(?:\\s+Cards?)?\\s+" +
        "(?:(?<targetSide>(?:your\\s+)?opponent\\s+controls|you\\s+control)\\s+)?" +
        "as\\s+(?:the\\s+)?" +
        "(?<countSrc>\\[Job\\s*\\([^)]+\\)\\]|Category\\s+\\S+(?:\\s+(?:Forwards?|Characters?|Backups?|Monsters?))?|Job\\s+.+?(?=\\s+you\\s+control)|Forwards?|Backups?|Monsters?|Characters?)" +
        "\\s+you\\s+control[,.]?\\s+" +
        "(?<followup>.+)$"
    );
    /**
     * Matches "Choose up to the same number of Characters as the Job X in your Break Zone
     * and/or Job X you own removed from the game. [Dull/Activate/Freeze] them." (Jill 26-034L).
     * The count is computed at resolution time as (Job X in own Break Zone) + (Job X the acting
     * player owns removed from the game). Group {@code targetType}, {@code job}, {@code followup}.
     */
    static final Pattern CHOOSE_AS_MANY_AS_BZ_RFG_JOB = Pattern.compile(
        "(?i)^Choose\\s+(?:as\\s+many|up\\s+to\\s+the\\s+same\\s+number\\s+of)\\s+" +
        "(?<targetType>Forwards?|Characters?|Backups?|Monsters?)(?:\\s+Cards?)?\\s+" +
        "as\\s+(?:the\\s+)?Job\\s+(?<job>.+?)\\s+in\\s+your\\s+Break\\s+Zone\\s+and/or\\s+" +
        "Job\\s+.+?\\s+you\\s+own\\s+removed\\s+from\\s+the\\s+game[,.]?\\s+" +
        "(?<followup>.+)$"
    );
    /**
     * Matches "Choose up to the same number of Characters as the [Name] Counters placed on [card]. Activate them."
     * At resolution time {@code xValue} holds the counter count captured before the card was put into the Break Zone.
     * Group {@code counterName} — counter type (e.g. "Monster"); group {@code card} — source card name.
     */
    static final Pattern CHOOSE_COUNTER_SCALE_CHARS_ACTIVATE = Pattern.compile(
        "(?i)Choose\\s+up\\s+to\\s+the\\s+same\\s+number\\s+of\\s+Characters?\\s+as\\s+the\\s+(?<counterName>.+?)\\s+Counters?\\s+placed\\s+on\\s+(?<card>.+?)[,.]\\s*Activate\\s+them[.!]?"
    );
    /**
     * Matches "Look at the same number of cards from the top of your deck as the [Name] Counters placed on [card].
     * Add 1 card among them to your hand. Then, shuffle the other cards and return them to the bottom of your deck."
     * At resolution time {@code xValue} holds the counter count captured before the card was put into the Break Zone.
     * Group {@code counterName} — counter type (e.g. "Monster"); group {@code card} — source card name.
     */
    static final Pattern LOOK_COUNTER_SCALE_ADD_TO_HAND_REST_BOTTOM = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+same\\s+number\\s+of\\s+cards?\\s+from\\s+the\\s+top\\s+of\\s+your\\s+deck\\s+as\\s+the\\s+(?<counterName>.+?)\\s+Counters?\\s+placed\\s+on\\s+(?<card>.+?)[,.]" +
        ".+?Add\\s+1\\s+card.+?to\\s+your\\s+hand.+?(?:shuffle|return).+?bottom.+?deck[.!]?"
    );

    // =========================================================================================
    // Crystals; hand to the bottom of the deck
    // =========================================================================================
    /** Matches "Gain 《C》[《C》...]." — captures one or more consecutive Crystal symbols. */
    static final Pattern GAIN_CRYSTAL = Pattern.compile(
        "(?i)Gain\\s+(?<crystals>(?:《C》)+)[.!]?"
    );
    /** Matches "Gain 《C》 for each CP paid as X." — crystal count equals the X value paid. */
    static final Pattern GAIN_CRYSTAL_PER_X = Pattern.compile(
        "(?i)Gain\\s+《C》\\s+for\\s+each\\s+CP\\s+paid\\s+as\\s+X[.!]?"
    );
    /**
     * Matches "If your opponent has a 《C》, [also] gain 《C》."
     * Grants 1 Crystal only when the opponent currently holds at least one Crystal.
     */
    static final Pattern GAIN_CRYSTAL_IF_OPPONENT_HAS = Pattern.compile(
        "(?i)If\\s+your\\s+opponent\\s+has\\s+a\\s+《C》,\\s+(?:also\\s+)?gain\\s+《C》[.!]?"
    );
    /**
     * Matches "Draw N card(s), then place M card(s) from your hand at the bottom of your deck."
     * Group 1 = draw count, Group 2 = place count.
     */
    static final Pattern DRAW_THEN_PLACE_HAND_TO_BOTTOM = Pattern.compile(
        "(?i)Draw\\s+(\\d+)\\s+cards?[,.]?\\s+then\\s+place\\s+(\\d+)\\s+cards?\\s+from\\s+your\\s+hand\\s+at\\s+the\\s+bottom\\s+of\\s+your\\s+deck[.!]?"
    );
    /**
     * Matches "place up to N cards from your hand at the bottom of your deck [in any order]. Then,
     * draw the same number of cards as were returned to your deck." (Waltrill 8-047C) — the redraw
     * is sized by how many cards the player actually returned, which may be none.
     * Group {@code max} = the cap on cards returned.
     */
    static final Pattern PLACE_UP_TO_HAND_TO_BOTTOM_THEN_REDRAW = Pattern.compile(
        "(?i)place\\s+up\\s+to\\s+(?<max>\\d+)\\s+cards?\\s+from\\s+your\\s+hand\\s+at\\s+the\\s+bottom\\s+" +
        "of\\s+your\\s+deck(?:\\s+in\\s+any\\s+order)?[.,]?\\s+Then[,.]?\\s+draw\\s+the\\s+same\\s+number\\s+" +
        "of\\s+cards?\\s+as\\s+(?:were|was)\\s+returned\\s+to\\s+your\\s+deck[.!]?"
    );

    // =========================================================================================
    // Optional payments and "when you do so"
    // =========================================================================================
    /**
     * Matches "pay 《Element》[…]. When you do so, [followup]."
     * Used when an auto-ability's effect text begins with an explicit CP payment followed by
     * a conditional effect clause.
     * Groups: {@code cost} — the raw CP token(s); {@code followup} — the effect text after the condition.
     */
    static final Pattern PAY_CP_WHEN_DO_SO = Pattern.compile(
        "(?i)^\\s*pay\\s+(?<cost>(?:《[^》]+》\\s*)+)[.!]?\\s+When\\s+you\\s+do\\s+so[,.]?\\s+(?<followup>.+)$",
        Pattern.DOTALL
    );
    /**
     * Matches "[you may pay 《X》.] if you don't pay 《X》, [consequence]" — an optional cost the
     * ability's controller may pay to avert a consequence: Umaro 15-107H and Cecil 15-073H (《C》),
     * Umaro 8-024C (《Ice》), Leon 28-056C and Vincent 2-078R (《N》). An offer clause printed ahead
     * of the gate is absorbed, since it names the same cost the gate then tests.
     *
     * <p>The comma after the cost is required: it separates this gate from Ultimecia 27-092H's
     * "if you don't pay 《1》 for each CP required to cast chosen Forward…", whose per-CP cost this
     * pattern must not claim.
     * <ul>
     *   <li>Group {@code cost}        — the token inside 《》: a number, an element name, or "C"</li>
     *   <li>Group {@code consequence} — what happens when the cost goes unpaid</li>
     * </ul>
     */
    static final Pattern IF_NOT_PAY_OR_ELSE = Pattern.compile(
        "(?i)^(?:you\\s+may\\s+)?(?:pay\\s+《[^》]+》[.!]?\\s+)?" +
        "if\\s+you\\s+don'?t\\s+pay\\s+《(?<cost>[^》]+)》\\s*,\\s+(?<consequence>.+)$",
        Pattern.DOTALL
    );
    /**
     * Matches "You may pay 《Element》. If you do so, [effect]." — an optional CP payment followed
     * by a conditional target action, used as the followup inside {@link #tryParseChooseCharacter}.
     * Groups: {@code element} — the element name (e.g. "Ice"); {@code effect} — the conditional action text.
     */
    static final Pattern FOLLOWUP_YOU_MAY_PAY_ELEMENT_IF_DO_SO = Pattern.compile(
        "(?i)^You\\s+may\\s+pay\\s+《(?<element>[^》]+)》[.!]?\\s+If\\s+you\\s+do\\s+so[,.]?\\s+(?<effect>.+)$",
        Pattern.DOTALL
    );
    /**
     * Matches "[primary action]. Then, if you don't pay 《1》 for each CP required to cast chosen
     * [type], put it into the Break Zone." (Ultimecia 27-092H) — a followup whose cost is the
     * chosen card's own cost, so it can only be priced once the target is known.
     * Group {@code primary} — the action applied to the target before the cost is demanded.
     */
    static final Pattern FOLLOWUP_THEN_PAY_PER_TARGET_COST_OR_BREAK = Pattern.compile(
        "(?i)^(?<primary>.+?)[.!]\\s*Then[,.]?\\s+if\\s+you\\s+don'?t\\s+pay\\s+《1》\\s+for\\s+each\\s+CP\\s+" +
        "required\\s+to\\s+cast\\s+(?:the\\s+)?chosen\\s+\\w+\\s*,\\s+put\\s+it\\s+into\\s+the\\s+Break\\s+Zone[.!]?$",
        Pattern.DOTALL
    );
    /**
     * Matches "If your opponent doesn't pay 《N》, [target action]." — the followup inside
     * {@link #tryParseChooseCharacter} (e.g. Arkasodara: "choose 1 dull Forward. If your opponent
     * doesn't pay 《3》, break it."). The opponent may pay {@code cost} CP in full to prevent the
     * action; otherwise it runs against the chosen target(s).
     * Groups: {@code cost} — CP amount; {@code effect} — the target action text (e.g. "break it").
     */
    static final Pattern FOLLOWUP_IF_OPP_NOT_PAY_ACTION = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+doesn'?t\\s+pay\\s+《\\s*(?<cost>\\d+)\\s*》,?\\s+(?<effect>.+)$",
        Pattern.DOTALL
    );
    static final Pattern DRAW_DISCARD_RETRIGGER_IF_CARD_NAME = Pattern.compile(
        "(?i)^Draw\\s+(?<draw>\\d+)\\s+cards?\\s+then\\s+discard\\s+(?<discard>\\d+)\\s+cards?[.!]?\\s+" +
        "If\\s+you\\s+discard\\s+a\\s+Card\\s+Name\\s+(?<name>.+?)\\s+by\\s+this\\s+effect,\\s+" +
        "trigger\\s+this\\s+auto-ability\\s+again[.!]?\\s*$"
    );

    // =========================================================================================
    // Sentence splitting and trailing clauses
    // =========================================================================================
    /**
     * An ability ending in a standalone "[Then,] draw N card(s)." sentence, split into the leading
     * effect ({@code head}) and the draw ({@code draw}).
     *
     * <p>Exists because a pattern matching only the leading sentences claims the whole ability —
     * matchers run with {@code find()} — and {@code parse()} then returns without ever reaching its
     * sentence-splitting fallback, so the draw is silently discarded. 19-126C Shadow Lord's "your
     * opponent discards 1 card. Draw 1 card." draws nothing today.
     *
     * <p>{@code head} is greedy so the split is taken at the <em>last</em> sentence boundary. The
     * draw group is anchored to the end and admits nothing after the count, which keeps this off
     * "draw 1 card, then discard 1 card." and off conditional forms like "…, also draw 1 card."
     * — both are single effects with their own handling, not a trailing addition.
     */
    /**
     * "If the cost paid to cast [Self] included [Element] CP, [effect]" — Selkie 13-044C, who
     * prints one of these for Fire and another for Earth.
     *
     * <p>The per-Element sibling of {@link #CAST_PAYMENT_ELEMENTS_GATE}, which counts how many
     * distinct Elements paid rather than asking after a particular one.
     *
     * <p>Reads "cast" or "play": the two printings are the same rule a decade apart — Opus 7 says
     * "the cost paid to play", Opus 13 says "to cast" — and reading only the later wording left
     * the whole of the Opus 7 cycle ungated, 7-003C Red Mage discarding and 7-122C Mime granting
     * +2000 power whatever the cost was paid with.
     *
     * <p>Anchored end to end and read before anything else, because the gate is a prefix and every
     * parser below matches with {@code find()}: Selkie's Earth clause was already being claimed off
     * its tail by the board-wide power grant, applying to every Forward whatever the cast was paid
     * with. {@code inner} is greedy and so swallows any further gate clauses in a chain of them;
     * {@link #CAST_PAYMENT_ELEMENT_CP_GATE_CLAUSE} is what splits those apart.
     * Groups: {@code name}, {@code element}, {@code inner}.
     */
    static final Pattern CAST_PAYMENT_ELEMENT_CP_GATE = Pattern.compile(
        "(?i)^If\\s+the\\s+cost\\s+paid\\s+to\\s+(?:cast|play)\\s+(?<name>.+?)\\s+included\\s+" +
        "(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+CP,\\s+(?<inner>.+)$"
    );

    /**
     * The gate clause of {@link #CAST_PAYMENT_ELEMENT_CP_GATE} on its own — without the effect it
     * guards, and without anchors, so it can be located rather than only matched whole.
     *
     * <p>Two callers need that. 9-123L Chaos (MOBIUS) prints three gates in one ability, one per
     * Element, and the anchored pattern's greedy {@code inner} reads the second and third as part
     * of the first one's effect; scanning with this pattern splits the ability at each gate. And
     * both cycles also print the gate as a <em>choose followup</em> ("choose up to 2 Forwards. If
     * the cost paid to cast Clavat included Ice CP, Freeze them."), where it has to be recognised
     * at the head of the followup and stripped off it.
     * Groups: {@code name}, {@code element}.
     */
    static final Pattern CAST_PAYMENT_ELEMENT_CP_GATE_CLAUSE = Pattern.compile(
        "(?i)If\\s+the\\s+cost\\s+paid\\s+to\\s+(?:cast|play)\\s+(?<name>.+?)\\s+included\\s+" +
        "(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+CP,\\s+"
    );

    /**
     * A choose followup that hands the chosen card a quoted ability for the turn:
     * "Until the end of the turn, it gains "[ability]"." — Behemoth 24-084R.
     *
     * <p>Captures the quotation only. Whether the engine can honour what is inside it is the
     * branch's question, not this pattern's: a grant of an ability nothing reads has to keep
     * falling through the chain and be reported as unhandled, rather than being accepted here and
     * resolving as a silent no-op.
     */
    static final Pattern FOLLOWUP_GAINS_QUOTED_ABILITY_UNTIL_EOT = Pattern.compile(
        "(?i)Until\\s+the\\s+end\\s+of\\s+the\\s+turn,?\\s+it\\s+gains\\s+\"(?<granted>[^\"]+)\""
    );

    /**
     * The sentence boundary this family splits on: a period followed by a capitalised word.
     *
     * <p>Deliberately blind to the period inside a quoted granted ability, which never opens a new
     * sentence of the outer text and would break a grant in half. Every splitter here shares this
     * one constant so {@code parse()} and both reporting chains cut identically.
     */
    static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=\\.)\\s+(?=[A-Z])");

    /**
     * A sentence that depends on the one before it, and so must never be resolved on its own.
     *
     * <p>Sentence-level composition is only sound for independent clauses. "Choose 1 Forward. Deal
     * it 3000 damage." parses as two sentences that each match a pattern, but splitting them loses
     * the link — the damage would no longer land on the chosen Forward. Anything matching this is
     * carrying a reference backwards, whether a pronoun ("it", "them", "those"), a demonstrative
     * ("that Forward"), a conditional callback ("if you do so", "by this effect") or an override
     * ("instead"), and blocks composition for the whole ability.
     */
    static final Pattern DEPENDS_ON_PREVIOUS_SENTENCE = Pattern.compile(
        // "their" is deliberately absent: it is usually a within-sentence possessive ("your
        // opponent discards 1 card from their hand"), so treating it as a backward reference
        // blocks composition on independent text. A genuine backward "their" is nearly always
        // paired with "them" or "they" ("Return them to their owners' hands"), which are listed.
        "(?i)\\b(?:it|its|them|they|those|these|this\\s+way|instead" +
        "|that\\s+(?:Forward|Backup|Monster|Character|Summon|card|player)" +
        "|if\\s+you\\s+do(?:\\s+so)?|when\\s+you\\s+do(?:\\s+so)?|by\\s+this\\s+effect" +
        "|the\\s+(?:chosen|revealed|added|removed|discarded|selected))\\b"
    );
    static final Pattern TRAILING_DRAW_SUFFIX = Pattern.compile(
        "(?is)^(?<head>.*[.!])\\s+(?:Then,?\\s+)?(?<draw>draw\\s+\\d+\\s+cards?)[.!]?\\s*$"
    );
    /**
     * An ability ending in a standalone "Gain 《C》[《C》…]." sentence, split into the leading effect
     * ({@code head}) and the crystals ({@code crystals}).
     *
     * <p>The same shape as {@link #TRAILING_DRAW_SUFFIX} and it exists for the same reason: the
     * crystal gain is a separate sentence appended to a complete effect, and whichever of the two
     * a pattern happens to match claims the whole ability under {@code find()}. 28-102R Princess
     * Sarah — "look at the top card of your deck. You may put it at the bottom of your deck. Then,
     * draw 1 card. Gain 《C》." — gained the crystal and did nothing else, because GAIN_CRYSTAL
     * matched the last sentence and parse() returned.
     *
     * <p>{@code head} is greedy, so the split is taken at the last sentence boundary and the head
     * may itself carry a trailing draw — the two composers nest. The gain is anchored to the end
     * and must start its own sentence, which keeps this off the mid-sentence conditional form
     * ("If your opponent has a 《C》, also gain 《C》.") that {@link #GAIN_CRYSTAL_IF_OPPONENT_HAS}
     * owns.
     */
    static final Pattern TRAILING_GAIN_CRYSTAL_SUFFIX = Pattern.compile(
        "(?is)^(?<head>.*[.!])\\s+Gain\\s+(?<crystals>(?:《C》)+)[.!]?\\s*$"
    );

    // =========================================================================================
    // Draw, discard and player damage
    // =========================================================================================
    static final Pattern DRAW_CARDS = Pattern.compile(
        "(?i)^Draw\\s+(\\d+)\\s+cards?(?:\\s*[,.]?\\s*then\\s+discard\\s+(\\d+)\\s+cards?)?[.!]?"
    );
    /**
     * Matches "Discard N card(s)[,] then draw M card(s)".
     * <ul>
     *   <li>Group 1 — number of cards to discard</li>
     *   <li>Group 2 — number of cards to draw afterward</li>
     * </ul>
     */
    static final Pattern DISCARD_THEN_DRAW = Pattern.compile(
        "(?i)^Discard\\s+(\\d+)\\s+cards?[,.]?\\s+then\\s+draw\\s+(\\d+)\\s+cards?[.!]?"
    );
    /**
     * Matches "&lt;subject&gt; deals your opponent N point(s) of damage." and the bare imperative
     * spelling of the same effect, "Deal your opponent N point(s) of damage." (Palom 2-015H).
     * <ul>
     *   <li>Group {@code amount} — number of damage points dealt to the opponent player</li>
     * </ul>
     */
    static final Pattern DEAL_PLAYER_DAMAGE_TO_OPPONENT = Pattern.compile(
        "(?i)^(?:.+?\\s+deals?|Deal)\\s+your\\s+opponent\\s+(?<amount>\\d+)\\s+points?\\s+of\\s+damage[.!]?$"
    );
    /**
     * Matches "&lt;subject&gt; deals you N point(s) of damage." or "receive N point(s) of damage."
     * <ul>
     *   <li>Group {@code amount} — number of damage points dealt to the ability user</li>
     * </ul>
     */
    static final Pattern DEAL_PLAYER_DAMAGE_TO_SELF = Pattern.compile(
        "(?i)(?:.+?\\s+deals?\\s+you|receive)\\s+(?<amount>\\d+)\\s+points?\\s+of\\s+damage[.!]?"
    );

    // =========================================================================================
    // Board-wide damage
    // =========================================================================================
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
    static final Pattern DEAL_DAMAGE_TO_FORWARDS = Pattern.compile(
        "(?i)Deal\\s+(?<amount>\\d+)\\s+damage\\s+to\\s+all(?:\\s+the)?\\s+" +
        "(?:(?<condition>damaged|dull|attacking|blocking|active)\\s+)?" +
        "Forwards?" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+other\\s+than\\s+Job\\s+(?<excludejob>.+?)(?=\\s+(?:your\\s+)?opponent\\s+controls\\b|[.!]?$))?" +
        // Exclusion by card name, read after the Job arm so "other than Job X" still prefers it.
        // Without it find() ended at "Forwards" and dropped the clause, and 14-011H Susano, Lord of
        // the Revel dealt its own 9000 damage to itself along with everything else.
        "(?:\\s+other\\s+than\\s+(?<excludename>[^.!]+?)(?=\\s+(?:your\\s+)?opponent\\s+controls\\b|[.!]?$))?" +
        "(?:\\s+(?<opponent>(?:your\\s+)?opponent\\s+controls))?" +
        "[.!]?"
    );
    /**
     * Shantotto 4-083L: "deal the same amount of damage to all the Forwards other than [Self]."
     * with her remove-from-game rider optionally trailing it.
     *
     * <p>"The same amount" is the damage she has just been dealt — a number the text never states,
     * only the event knows, and which reaches the effect as the entry's {@code xValue}. That is
     * why this cannot go through the fixed-amount mass-damage family however its exclusion is
     * widened.
     *
     * <p>The rider is matched and discarded rather than left to trail: it is a continuous
     * replacement read as the damaged Forward leaves the field, not a step of this resolution, and
     * an unmatched tail would leave the anchor unsatisfied. Groups: {@code card}.
     */
    static final Pattern DEAL_SAME_AMOUNT_TO_ALL_FORWARDS_EXCEPT = Pattern.compile(
        "(?i)^deal\\s+the\\s+same\\s+amount\\s+of\\s+damage\\s+to\\s+all(?:\\s+the)?\\s+Forwards?\\s+" +
        "other\\s+than\\s+(?<card>[^.!]+?)[.!]" +
        "(?:\\s*If\\s+(?:a|the)\\s+Forward\\s+damaged\\s+by\\s+this\\s+ability\\s+is\\s+put\\s+" +
        "(?:from\\s+the\\s+field\\s+)?into\\s+the\\s+Break\\s+Zone\\s+" +
        "(?:this\\s+turn|(?:on|during)\\s+the\\s+same\\s+turn),\\s+" +
        "remove\\s+it\\s+from\\s+the\\s+game\\s+instead[.!]?)?\\s*$"
    );
    /** Matches "Deal N damage to [all] Forwards of all Elements except [Element]." */
    static final Pattern DEAL_DAMAGE_TO_FORWARDS_EXCEPT_ELEMENT = Pattern.compile(
        "(?i)Deal\\s+(?<amount>\\d+)\\s+damage\\s+to\\s+(?:all(?:\\s+the)?\\s+)?Forwards?\\s+" +
        "of\\s+all\\s+Elements?\\s+except\\s+(?<excludeelem>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)[.!]?"
    );
    /**
     * Matches "Remove from the game all the Forwards on the field other than [elem1] and [elem2].
     * Then, remove from the top of your deck twice the number of cards removed by the previous effect."
     * Groups: {@code elem1}, {@code elem2}.
     */
    static final Pattern RFP_ALL_FWD_EXCEPT_ELEMENTS_THEN_TWICE_DECK = Pattern.compile(
        "(?i)Remove\\s+from\\s+(?:the\\s+)?game\\s+all\\s+(?:the\\s+)?Forwards?\\s+on\\s+(?:the\\s+)?field\\s+" +
        "other\\s+than\\s+(?<elem1>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+and\\s+(?<elem2>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)[.!]?\\s*" +
        "Then,?\\s+remove\\s+from\\s+the\\s+top\\s+of\\s+your\\s+deck\\s+twice\\s+the\\s+number\\s+of\\s+cards\\s+removed\\s+by\\s+(?:the\\s+)?previous\\s+effect[.!]?"
    );
    /** Matches "No Forward of cost N or less/more can attack this turn." */
    static final Pattern NO_FORWARD_COST_CANNOT_ATTACK = Pattern.compile(
        "(?i)No\\s+Forward(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?\\s+can\\s+attack\\s+this\\s+turn[.!]?"
    );
    /** Matches "During this turn, the Forwards you control cannot be chosen by EX Bursts." */
    static final Pattern OWN_FORWARDS_CANNOT_BE_CHOSEN_BY_EX_BURST = Pattern.compile(
        "(?i)During\\s+this\\s+turn,?\\s+the\\s+Forwards?\\s+you\\s+control\\s+cannot\\s+be\\s+chosen\\s+by\\s+EX\\s+Bursts?[.!]?"
    );
    /**
     * Matches "EX Bursts of cards put into the Damage Zone due to this &lt;ability|damage&gt;
     * cannot be used."
     *
     * <p>6-017C Bahamut says "this damage", naming the point of player damage the sentence before
     * it deals; every other printing says "this ability". They come to the same suppression here,
     * because the ability {@code suppressExBurstsThisAbility} scopes to is the one resolution that
     * dealt the damage — nothing else in Bahamut's text puts a card into a Damage Zone.
     */
    static final Pattern EX_BURST_SUPPRESSION_PATTERN = Pattern.compile(
        "(?i)EX\\s+Bursts?\\s+of\\s+cards?\\s+put\\s+into\\s+the\\s+Damage\\s+Zone\\s+due\\s+to\\s+this\\s+" +
        "(?:ability|damage)\\s+cannot\\s+be\\s+used[.!]?"
    );
    /**
     * Alternate word order: "Deal all [the] [condition] Forwards [of cost N] [other than Job Y] [opponent controls] X damage."
     * Same named groups as {@link #DEAL_DAMAGE_TO_FORWARDS} so {@link #tryParseDealDamageToForwards} can share extraction logic.
     */
    static final Pattern DEAL_DAMAGE_TO_FORWARDS_ALT = Pattern.compile(
        "(?i)Deal\\s+all(?:\\s+the)?\\s+" +
        "(?:(?<condition>damaged|dull|attacking|blocking|active)\\s+)?" +
        "Forwards?" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+other\\s+than\\s+Job\\s+(?<excludejob>.+?)(?=\\s+(?:your\\s+)?opponent\\s+controls\\b|\\s+\\d+\\s+damage))?" +
        // Carried alongside its sibling's arm so the two patterns expose the same group set: the
        // parser reads whichever one matched, and a group missing from this one is not an absent
        // filter but an IllegalArgumentException on every card that reaches this wording.
        "(?:\\s+other\\s+than\\s+(?<excludename>.+?)(?=\\s+(?:your\\s+)?opponent\\s+controls\\b|\\s+\\d+\\s+damage))?" +
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
    static final Pattern DEAL_DAMAGE_TO_FORWARDS_FOR_EACH = Pattern.compile(
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
    static final Pattern FOR_EACH_JOB_AND_NAME_DEAL_DAMAGE_TO_FORWARDS = Pattern.compile(
        "(?i)^For\\s+each\\s+Job\\s+(?<job>.+?)\\s+and(?:/or)?\\s+Card\\s+[Nn]ame\\s+(?<cardname>.+?)\\s+you\\s+control,?\\s+" +
        "deal\\s+(?<amount>\\d+)\\s+damage\\s+to\\s+all(?:\\s+the)?\\s+Forwards?" +
        "(?:\\s+(?<opponent>(?:your\\s+)?opponent\\s+controls))?[.!]?$"
    );
    /**
     * Matches "deal N damage for each Job X [and]/or [a] Card Name Y you control to all [the]
     * Forwards opponent controls."
     * Groups: {@code amount}, {@code job}, {@code cardname}.
     *
     * <p>Both conjunctions are printed for the same effect — 11-003R Cyan says "or", its Re-007C
     * reprint says "and/or" — so the sibling {@link #FOR_EACH_JOB_AND_NAME_DEAL_DAMAGE_TO_FORWARDS}
     * spells it {@code and(?:/or)?} for the mirror-image word order.
     */
    static final Pattern DEAL_N_FOR_EACH_JOB_OR_NAME_TO_OPP_FORWARDS = Pattern.compile(
        "(?i)deal\\s+(?<amount>\\d+)\\s+damage\\s+for\\s+each\\s+" +
        "Job\\s+(?<job>.+?)\\s+(?:and/)?or\\s+(?:a\\s+)?Card\\s+[Nn]ame\\s+(?<cardname>.+?)\\s+you\\s+control\\s+" +
        "to\\s+all\\s+(?:the\\s+)?Forwards?(?:\\s+(?:your\\s+)?opponent\\s+controls)?[.!]?$"
    );
    /**
     * Matches "deal N damage and M more damage for each Card Name [name] in your Break Zone
     * to all [the] Forwards [opponent controls]."
     * Groups: {@code base} — fixed base damage; {@code per} — additional per copy; {@code cardname} — name filter;
     * {@code opponent} — present when "opponent controls" appears.
     */
    static final Pattern DEAL_BASE_PLUS_BZ_NAME_DAMAGE_TO_FORWARDS = Pattern.compile(
        "(?i)^deal\\s+(?<base>\\d+)\\s+damage\\s+and\\s+(?<per>\\d+)\\s+more\\s+damage\\s+" +
        "for\\s+each\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+in\\s+your\\s+Break\\s+Zone\\s+" +
        "to\\s+all(?:\\s+the)?\\s+Forwards?" +
        "(?:\\s+(?<opponent>(?:your\\s+)?opponent\\s+controls))?[.!]?$"
    );
    /**
     * Matches "Until the end of the turn, [CardName] gains [traits and] 'When [CardName] attacks,
     * [innerEffect]'" — grants the source card a temporary attack trigger for this turn, and
     * whatever keywords the same sentence hands it.
     * <ul>
     *   <li>Group {@code subject} — the card being granted, checked against the source by the parser</li>
     *   <li>Group {@code traits}  — optional keyword run ("Haste, First Strike and "), Lightning 1-141L</li>
     *   <li>Group {@code inner}   — the effect text inside the quoted auto-ability</li>
     * </ul>
     *
     * <p>The traits run ends in the "and" that joins it to the quotation, which is why it is part
     * of the group: without it the alternation could not tell "gains Haste and \"…\"" from a
     * subject that happens to end in a keyword name.
     */
    static final Pattern SELF_GAINS_WHEN_ATTACKS_EOT = Pattern.compile(
        "(?i)^Until\\s+the\\s+end\\s+of\\s+(?:the\\s+)?turn,?\\s+(?<subject>[^\"]+?)\\s+gains?\\s+" +
        "(?<traits>(?:Haste|First\\s+Strike|Brave)(?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*\\s+and\\s+)?" +
        "\"When\\s+.+?\\s+attacks?,\\s+(?<inner>.+?)\"[.!]?$"
    );
    /**
     * Matches "Deal [N] damage to the Forward that blocks [CardName][.]"
     * Used by "is blocked" auto-abilities and action abilities that target the current combat blocker.
     * <ul>
     *   <li>Group {@code amount} — fixed damage value</li>
     *   <li>Group {@code name}   — name of the card being blocked</li>
     * </ul>
     */
    static final Pattern DAMAGE_TO_COMBAT_BLOCKER = Pattern.compile(
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
    static final Pattern DEAL_HALF_POWER_DAMAGE_TO_FORWARDS = Pattern.compile(
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
    static final Pattern DEAL_POWER_MINUS_N_DAMAGE_TO_FORWARDS = Pattern.compile(
        "(?i)Deal\\s+each(?:\\s+the)?\\s+" +
        "(?:(?<condition>damaged|dull|attacking|blocking)\\s+)?" +
        "Forwards?\\s+" +
        "(?<opponent>(?:your\\s+)?opponent\\s+controls\\s+)?" +
        "damage\\s+equal\\s+to\\s+its\\s+power\\s+minus\\s+(?<amount>\\d+)" +
        "[.!]?"
    );
    /**
     * Matches "Choose up to N Forwards opponent controls. Deal 1 of them A damage, 1 of them B
     * damage, and 1 of them C damage." — Palom 3-016H's Meteor, the only printing that hands each
     * pick a <em>different</em> amount.
     *
     * <p>{@code tiers} is captured whole and re-scanned with {@link #TIERED_DAMAGE_ONE_OF_THEM}
     * rather than being spelled out as three groups, so the shape is not pinned to three amounts.
     * The count and the number of amounts are cross-checked by the parser, not here.
     */
    static final Pattern CHOOSE_TIERED_DAMAGE = Pattern.compile(
        "(?is)^Choose\\s+up\\s+to\\s+(?<count>\\d+)\\s+Forwards?\\s+" +
        "(?<opponent>(?:your\\s+)?opponent\\s+controls)\\s*[.!]\\s*" +
        "Deal\\s+(?<tiers>1\\s+of\\s+them\\s+\\d+\\s+damage" +
        "(?:\\s*,?\\s*(?:and\\s+)?1\\s+of\\s+them\\s+\\d+\\s+damage)+)\\s*[.!]?\\s*$"
    );
    /** One "1 of them N damage" term of {@link #CHOOSE_TIERED_DAMAGE}'s {@code tiers} group. */
    static final Pattern TIERED_DAMAGE_ONE_OF_THEM = Pattern.compile(
        "(?i)1\\s+of\\s+them\\s+(?<amount>\\d+)\\s+damage"
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
    static final Pattern DEAL_HALF_SOURCE_POWER_DAMAGE_TO_FORWARDS = Pattern.compile(
        "(?i)Deal\\s+damage\\s+equal\\s+to\\s+half\\s+of\\s+(?<sourcename>.+?)'s\\s+power\\s+" +
        "to\\s+all(?:\\s+the)?\\s+" +
        "(?:(?<condition>damaged|dull|attacking|blocking)\\s+)?" +
        "Forwards?\\s*" +
        "(?<opponent>(?:your\\s+)?opponent\\s+controls)?\\s*" +
        "(?:\\(\\s*round\\s+(?<rounding>up|down)[^)]*\\))?\\s*" +
        "[.!]?"
    );

    // =========================================================================================
    // Cost reductions
    // =========================================================================================
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
    static final Pattern COST_REDUCTION_THIS_TURN = Pattern.compile(
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
    static final Pattern PLAY_COST_REDUCTION_THIS_TURN = Pattern.compile(
        "(?i)The\\s+cost\\s+required\\s+to\\s+(?:play|cast)\\s+your\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?:Job\\s+(?<job>.+?)\\s+)?" +
        "(?:Card\\s+Name\\s+(?<cardname>\\S+)|(?<type>Forwards?|Backups?|Monsters?|Characters?))\\s+" +
        "(?:onto\\s+the\\s+field\\s+)?this\\s+turn\\s+is\\s+reduced\\s+by\\s+(?<amount>\\d+)" +
        "(?<floorone>\\s*\\(it\\s+cannot\\s+become\\s+0\\))?[.!]?"
    );

    // =========================================================================================
    // Break Zone and RFG: salvage and castable
    // =========================================================================================
    /**
     * Matches "Choose 1 Summon in your Break Zone. Add it to your hand. During this turn,
     * the cost required to cast your next Summon is reduced by N [(it cannot become 0)]."
     * <ul>
     *   <li>Group {@code amount}   — cost reduction</li>
     *   <li>Group {@code floorone} — present when "(it cannot become 0)" clause appears</li>
     * </ul>
     */
    static final Pattern CHOOSE_SUMMON_FROM_BZ_TO_HAND_WITH_COST_REDUCTION = Pattern.compile(
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
    static final Pattern CHOOSE_N_SUMMONS_BZ_PICK_ONE_HAND_REST_RFG = Pattern.compile(
        "(?i)Choose\\s+(?<total>\\d+)\\s+Summons?\\s+in\\s+your\\s+Break\\s+Zone[.!]?\\s+" +
        "Add\\s+1\\s+of\\s+them\\s+to\\s+your\\s+hand[,.]?(?:\\s+and)?\\s+remove\\s+the\\s+rest\\s+from\\s+the\\s+game[.!]?\\s*$"
    );
    /**
     * Matches "Select 1 of your Card Name X removed from the game. Add it to your hand."
     * (Feral Chaos B-010, salvaging a Chaos it had exiled).
     * Group {@code name} — the card name to look for in the acting player's RFG zone.
     *
     * <p>Anchored whole rather than matched with {@code find()}: the tail on its own ("Add it to
     * your hand") is a followup several choose parsers claim, and only the head names the zone.
     */
    static final Pattern SELECT_NAMED_FROM_RFG_TO_HAND = Pattern.compile(
        "(?i)^(?:Select|Choose)\\s+1\\s+of\\s+your\\s+Card\\s+Name\\s+(?<name>.+?)\\s+" +
        "removed\\s+from\\s+the\\s+game[,.!]?\\s+Add\\s+it\\s+to\\s+your\\s+hand[.!]?\\s*$"
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
    static final Pattern CHOOSE_SUMMON_IN_BZ_CASTABLE = Pattern.compile(
        "(?i)Choose\\s+1\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Summon\\s+in\\s+your\\s+Break\\s+Zone[.!]?\\s+" +
        "You\\s+can\\s+cast\\s+it\\s+at\\s+any\\s+time\\s+you\\s+could\\s+normally\\s+cast\\s+it\\s+this\\s+turn[.!]?\\s+" +
        "The\\s+cost\\s+required\\s+to\\s+cast\\s+it\\s+is\\s+reduced\\s+by\\s+(?<amount>\\d+)[.!]?"
    );
    /**
     * Aemo 23-022R: "Your opponent removes all their hand from the game face down. Your opponent
     * can look at these removed cards at any time. At the end of the turn, your opponent adds them
     * back to their hand."
     *
     * <p>Anchored and matched whole. The three sentences are one effect -- the second says who may
     * look at what the first removed, the third gives back what the first took -- and each refers
     * to the one before it only as "these removed cards" and "them", neither of which the sentence
     * splitter reads as a backward reference. Left to that rule the last sentence resolves alone,
     * and "your opponent adds them back to their hand" with no antecedent is a removal that never
     * happens followed by a return of nothing.
     *
     * <p>No groups: the effect has no numbers in it. Every quantity it deals with -- how many cards
     * are removed, which ones come back -- is whatever the opponent's hand held when it resolved.
     *
     * <p>The middle sentence is required rather than optional. It is what makes the removal face
     * down mean anything, and admitting the text without it would claim a differently-worded card
     * that does not yet exist.
     */
    static final Pattern OPP_RFG_WHOLE_HAND_FACE_DOWN_RETURN_EOT = Pattern.compile(
        "(?i)^Your\\s+opponent\\s+removes\\s+all\\s+(?:of\\s+)?their\\s+hand\\s+from\\s+the\\s+game\\s+face\\s+down[.!]?\\s+" +
        "Your\\s+opponent\\s+can\\s+look\\s+at\\s+these\\s+removed\\s+cards\\s+at\\s+any\\s+time[.!]?\\s+" +
        "At\\s+the\\s+end\\s+of\\s+the\\s+turn,?\\s+your\\s+opponent\\s+adds\\s+them\\s+back\\s+to\\s+their\\s+hand[.!]?$"
    );
    /**
     * "Your opponent removes the top card of their deck from the game [face down]. You can [look at
     * it and/or] cast it as though you owned it at any time you could normally cast it. The cost for
     * casting it [is reduced by N and] can be paid using CP of any Element." (Lani 12-018H, Zidane 16-048H)
     */
    static final Pattern OPP_RFP_TOPDECK_CASTABLE = Pattern.compile(
        "(?is)your\\s+opponent\\s+removes\\s+the\\s+top\\s+card\\s+of\\s+their\\s+deck\\s+from\\s+the\\s+game(?:\\s+face\\s+down)?[.!]?\\s+" +
        "You\\s+can\\s+(?:look\\s+at\\s+it\\s+and/or\\s+)?cast\\s+it\\s+as\\s+though\\s+you\\s+owned\\s+it\\s+at\\s+any\\s+time\\s+you\\s+could\\s+normally\\s+cast\\s+it[.!]?" +
        "(?<cost>.*)$"
    );
    /**
     * "Choose 1 [Forward|Backup|Monster|Character] in your opponent's Break Zone. Remove it from the
     * game. [During this game,] you can cast it as though you owned it at any time you could normally
     * cast it." (Bel Dat 20-056H — Forward; Zidane 24-044H — Character)
     */
    static final Pattern CHOOSE_FROM_OPP_BZ_CASTABLE = Pattern.compile(
        "(?is)Choose\\s+1\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+in\\s+your\\s+opponent'?s\\s+Break\\s+Zone[.!]?\\s+" +
        "Remove\\s+it\\s+from\\s+the\\s+game[.!]?\\s+" +
        "(?:During\\s+this\\s+game,?\\s+)?[Yy]ou\\s+can\\s+cast\\s+it\\s+as\\s+though\\s+you\\s+owned\\s+it\\s+at\\s+any\\s+time\\s+you\\s+could\\s+normally\\s+cast\\s+it[.!]?"
    );
    /**
     * "Choose N Summon(s) [in|from] [your and/or your opponent's|either player's|your] Break Zone.
     * Remove it/them from the game. During this game, you can cast it/them [as though you owned
     * it/them ]at any time you could normally cast it/them ..." (Shantotto 23-067R; also the plain
     * "you can cast it at any time you could normally cast it" phrasing without "as though you owned it").
     */
    static final Pattern CHOOSE_SUMMONS_FROM_BZ_GAME = Pattern.compile(
        "(?is)[Cc]hoose\\s+(?<count>\\d+)\\s+Summons?\\s+(?:in|from)\\s+(?<scope>your\\s+and/or\\s+your\\s+opponent'?s|either\\s+player'?s|your\\s+opponent'?s|your)\\s+Break\\s+Zone[.!]?\\s+" +
        "Remove\\s+(?:it|them)\\s+from\\s+the\\s+game[.!]?\\s+" +
        "During\\s+this\\s+game,?\\s+you\\s+can\\s+cast\\s+(?:it|them)\\s+" +
        "(?:as\\s+though\\s+you\\s+owned\\s+(?:it|them)\\s+)?.*"
    );
    /**
     * "Choose N Summon(s) from [either player's|your and/or your opponent's|your] Break Zone. You can
     * cast it as though you owned it this turn. [If you cast it, remove that Summon from the game after
     * use instead of putting it in the Break Zone.]" (Krile 12-061L)
     */
    /**
     * The same effect as {@link #CHOOSE_SUMMONS_FROM_BZ_GAME} on the printings that leave the
     * duration unsaid — Man in Black 17-096H, whose reprint adds the "During this game," the
     * original omits. Both mean the same thing: the permission has no end, so it lasts the game.
     *
     * <p>End-anchored, where the explicit form ends in {@code .*}. That is the whole safeguard:
     * "at any time you could normally cast it <em>this turn</em>" is a different card's effect,
     * and only the anchor keeps this off it.
     */
    static final Pattern CHOOSE_SUMMONS_FROM_BZ_GAME_IMPLICIT = Pattern.compile(
        "(?is)[Cc]hoose\\s+(?<count>\\d+)\\s+Summons?\\s+(?:in|from)\\s+" +
        "(?<scope>your\\s+and/or\\s+your\\s+opponent'?s|either\\s+player'?s|your\\s+opponent'?s|your)" +
        "\\s+Break\\s+Zone[.!]?\\s+" +
        "Remove\\s+(?:it|them)\\s+from\\s+the\\s+game[.!]?\\s+" +
        "[Yy]ou\\s+can\\s+cast\\s+(?:it|them)\\s+" +
        "(?:as\\s+though\\s+you\\s+owned\\s+(?:it|them)\\s+)?" +
        "at\\s+any\\s+time\\s+you\\s+could\\s+normally\\s+cast\\s+(?:it|them)\\s*[.!]?$"
    );

    static final Pattern CHOOSE_SUMMONS_FROM_BZ_TURN = Pattern.compile(
        "(?is)[Cc]hoose\\s+(?<count>\\d+)\\s+Summons?\\s+from\\s+(?<scope>your\\s+and/or\\s+your\\s+opponent'?s|either\\s+player'?s|your\\s+opponent'?s|your)\\s+Break\\s+Zone[.!]?\\s+" +
        "You\\s+can\\s+cast\\s+(?:it|them)\\s+as\\s+though\\s+you\\s+owned\\s+(?:it|them)\\s+this\\s+turn[.!]?" +
        "(?<rfg>.*)$"
    );
    /**
     * "Choose 1 Summon of cost N or less [other than &lt;Element&gt; or &lt;Element&gt;] in your
     * Break Zone. Cast it without paying the cost. [If you cast it,] remove that Summon from the
     * game after use instead of putting it in the Break Zone." — 9-103R Iedolas, 29-033L Terra.
     *
     * <p>Two optional pieces separate the printings: Terra's Element exclusion, and the "If you
     * cast it," hedging the removal. The hedge changes nothing at resolution — the removal has
     * something to act on only if the Summon was cast — so it is matched and discarded rather
     * than given a branch of its own.
     */
    static final Pattern CHOOSE_SUMMON_IN_BZ_MAX_COST_FREE_CAST_RFG = Pattern.compile(
        "(?is)Choose\\s+1\\s+Summon\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+less\\s+" +
        "(?:other\\s+than\\s+(?<exclude>(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
        "(?:\\s+(?:or|and)\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*)\\s+)?" +
        "in\\s+your\\s+Break\\s+Zone[.!]?\\s+" +
        "Cast\\s+it\\s+without\\s+paying\\s+the\\s+cost[.!]?\\s+" +
        "(?:If\\s+you\\s+cast\\s+it,\\s+)?" +
        "[Rr]emove\\s+that\\s+Summon\\s+from\\s+the\\s+game\\s+after\\s+use\\s+instead\\s+of\\s+" +
        "putting\\s+it\\s+in\\s+the\\s+Break\\s+Zone[.!]?"
    );
    /**
     * "Choose 1 Forward with N power or less and up to 1 Forward in your opponent's Break Zone.
     * Remove them from the game."
     */
    static final Pattern CHOOSE_FWD_POWER_LE_AND_OPT_OPP_BZ_FWD_RFP = Pattern.compile(
        "(?i)Choose\\s+1\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power\\s+or\\s+less" +
        "\\s+and\\s+up\\s+to\\s+1\\s+Forward\\s+in\\s+your\\s+opponent(?:'s)?\\s+Break\\s+Zone[.!]?\\s+" +
        "Remove\\s+them\\s+from\\s+(?:the\\s+)?game[.!]?"
    );
    /** Matches "Take 1 more turn after this one. At the end of that turn, you lose the game." */
    static final Pattern EXTRA_TURN_THEN_LOSE = Pattern.compile(
        "(?i)Take\\s+1\\s+more\\s+turn\\s+after\\s+this\\s+one[.!]?\\s+" +
        "At\\s+the\\s+end\\s+of\\s+that\\s+turn,\\s+you\\s+lose\\s+the\\s+game[.!]?"
    );

    // =========================================================================================
    // Monsters becoming Forwards
    // =========================================================================================
    /**
     * Matches "Until the end of the turn, all the Monsters you control also become Forwards with N power."
     * Group {@code power} captures the power value.
     */
    static final Pattern ALL_MONSTERS_BECOME_FORWARDS_UNTIL_EOT_PATTERN = Pattern.compile(
        "(?i)^Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,?\\s+" +
        "all\\s+(?:the\\s+)?Monsters?\\s+you\\s+control\\s+also\\s+become\\s+Forwards?\\s+with\\s+(?<power>\\d+)\\s+power[.!]?"
    );
    /**
     * Matches "Until the end of the turn, [CardName] also becomes a Forward with N power."
     * Used for action abilities on Monsters.  Group {@code power} captures the power value.
     */
    static final Pattern BECOME_FORWARD_UNTIL_EOT_PATTERN = Pattern.compile(
        "(?i)^Until\\s+the\\s+end\\s+of\\s+the\\s+turn,\\s+.+?\\s+also\\s+becomes?\\s+a\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power"
    );
    /**
     * Extended form: "…becomes a Forward with N power and "Put [name] into the Break Zone: [effect]"."
     * Groups: {@code power}, {@code bzName}, {@code bzEffect}.
     */
    static final Pattern BECOME_FORWARD_AND_BZ_ACTION = Pattern.compile(
        "(?i)^Until\\s+the\\s+end\\s+of\\s+the\\s+turn,\\s+.+?\\s+also\\s+becomes?\\s+a\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power" +
        "\\s+and\\s+\"Put\\s+(?<bzName>.+?)\\s+into\\s+the\\s+Break\\s+Zone:\\s+(?<bzEffect>[^\"]+?)\"\\s*[.!]?",
        Pattern.DOTALL
    );
    /**
     * Extended form: "…becomes a Forward with N power and "When [name] attacks, [effect]"."
     * Groups: {@code power}, {@code attackEffect}.
     */
    static final Pattern BECOME_FORWARD_AND_ATTACK_TRIGGER = Pattern.compile(
        "(?i)^Until\\s+the\\s+end\\s+of\\s+the\\s+turn,\\s+.+?\\s+also\\s+becomes?\\s+a\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power" +
        "\\s+and\\s+\"When\\s+[^\"]+?\\s+attacks?\\s*,\\s+(?<attackEffect>[^\"]+?)\"\\s*[.!]?",
        Pattern.DOTALL
    );
    /**
     * Extended form: "…becomes a Forward with N power and "When [name] blocks[ or is blocked],
     * [effect]"."
     *
     * <p>Group {@code isblocked} is non-null for the longer spelling, and the parser needs it: the
     * two halves are fired from different places, so a grant registered only as "blocks" never
     * reaches a Malboro that attacked and was blocked.
     *
     * <p>Groups: {@code power}, {@code isblocked}, {@code blockEffect}.
     */
    static final Pattern BECOME_FORWARD_AND_BLOCK_TRIGGER = Pattern.compile(
        "(?i)^Until\\s+the\\s+end\\s+of\\s+the\\s+turn,\\s+.+?\\s+also\\s+becomes?\\s+a\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power" +
        "\\s+and\\s+\"When\\s+[^\"]+?\\s+blocks?(?<isblocked>\\s+or\\s+is\\s+blocked)?\\s*,\\s+(?<blockEffect>[^\"]+?)\"\\s*[.!]?",
        Pattern.DOTALL
    );
    /**
     * Matches "If the CP paid to cast [Name] was only produced by Backups, [also] draw N card(s)."
     * Group {@code count} — number of cards to draw.
     */
    static final Pattern BACKUP_CP_DRAW = Pattern.compile(
        "(?i)If\\s+the\\s+CP\\s+paid\\s+to\\s+cast\\s+.+?\\s+was\\s+only\\s+produced\\s+by\\s+Backups?," +
        "\\s+(?:also\\s+)?draw\\s+(?<count>\\d+)\\s+cards?[.!]?"
    );

    // =========================================================================================
    // Conditional gates
    // =========================================================================================
    /**
     * Matches "If your opponent has [no | N cards or less] cards in his/her hand, [effect][ instead][.!]?"
     * <ul>
     *   <li>{@code n}       — numeric threshold; absent when the condition is "no cards" (threshold = 0)</li>
     *   <li>{@code effect}  — the conditional inner effect text</li>
     *   <li>{@code instead} — present when "instead" immediately follows the effect</li>
     * </ul>
     */
    static final Pattern OPPONENT_HAND_CONDITION_PATTERN = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+has\\s+" +
        // Each branch carries its own trailing noun: the "no" branch ("no cards in their hand")
        // needs it, while "N cards or less" already consumes one "cards" and the real card wording
        // ("2 cards or less in their hand") runs straight into "in" with no second "cards".
        "(?:no\\s+cards?|(?<n>\\d+)\\s+cards?\\s+or\\s+less)\\s+in\\s+" +
        "(?:his/her|his|her|their)\\s+hand,?\\s*" +
        "(?<effect>.+?)" +
        "(?<instead>\\s+instead)?[.!]?$"
    );
    /**
     * Matches "If your opponent has N cards or more in their hand, [effect]."
     * Fires the inner effect only when the opponent's hand meets the minimum threshold.
     * Groups: {@code n} — minimum hand size; {@code effect} — the conditional effect text.
     */
    static final Pattern OPPONENT_HAND_MIN_CONDITION_PATTERN = Pattern.compile(
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
    static final Pattern OPPONENT_HAND_DOUBLE_CONDITION_PATTERN = Pattern.compile(
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
    static final Pattern FOLLOWUP_IF_OPPONENT_CONTROLS_FORWARDS_DAMAGE = Pattern.compile(
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
    static final Pattern FOLLOWUP_IF_SELF_CONTROLS_N_ELEMENT_TYPE_DAMAGE = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<count>\\d+)\\s+or\\s+more\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<type>Forwards?|Backups?|Monsters?|Characters?|Summons?),?\\s+" +
        "deal\\s+(?:it|them)\\s+(?<amount>\\d+)\\s+damage[.!]?$"
    );
    /**
     * The general form of {@link #FOLLOWUP_IF_SELF_CONTROLS_N_ELEMENT_TYPE_DAMAGE}: the same
     * "If you control N or more [Element] [Type]" gate in front of any target action rather than
     * only "deal it X damage" — e.g. Cocytus 8-031R's "choose up to 2 Forwards. If you control 4
     * or more Ice Characters, Freeze them."  The condition gates the <em>action</em>, not the
     * choosing: the targets are picked either way.
     * <p>{@code action} is handed to {@link #parseTargetAction}, so this only takes effect for
     * actions that machinery recognises; anything else falls through to the handlers below.
     */
    static final Pattern FOLLOWUP_IF_SELF_CONTROLS_N_ELEMENT_TYPE_ACTION = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<count>\\d+)\\s+or\\s+more\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<type>Forwards?|Backups?|Monsters?|Characters?|Summons?),?\\s+" +
        "(?<action>.+?)[.!]?$"
    );
    /**
     * Followup wordings that only help the chosen target — power and keyword grants, and
     * activation.  Dragoon 6-104C ("It gains First Strike until the end of the turn") is the
     * motivating case: an AI controller pointing that at the human's Forward is never right.
     */
    static final Pattern CHOOSE_FOLLOWUP_BENEFITS_TARGET = Pattern.compile(
        "(?i)\\b(?:it|they)\\s+(?:gains?\\s+(?:\\+\\d+\\s+power|Haste|First\\s+Strike|Brave)"
        + "|becomes?\\s+active)\\b|\\bActivate\\s+(?:it|them)\\b");
    /**
     * Followup wordings that harm the chosen target.  Checked first so a mixed effect
     * ("Deal it 5000 damage … it gains …") is never treated as a pure buff.
     */
    static final Pattern CHOOSE_FOLLOWUP_HARMS_TARGET = Pattern.compile(
        "(?i)\\b(?:deal|break|dull|freeze|discard|loses?|cannot|removes?\\s+it|return\\s+it"
        + "|power\\s+becomes?|put\\s+it\\s+into)\\b");
    /**
     * Matches the boilerplate "(Units must be 1000.)" / "(damage must be in increments of 1000)"
     * clarification that appears after "divide/split damage among chosen targets as you like/wish"
     * effects (e.g. Yuffie, Faris) — purely restates the standard rounding rule.
     */
    static final Pattern DAMAGE_INCREMENT_CLARIFICATION = Pattern.compile(
        "(?i)\\(\\s*(?:Units?\\s+must\\s+be\\s+\\d+\\.?|damage\\s+must\\s+be\\s+in\\s+increments\\s+of\\s+\\d+)\\s*\\)\\.?"
    );
    /**
     * Matches "Divide N damage among them as you like/equally" or "...split [it] as you wish/like
     * among the chosen ..." — a chosen-target damage allocation left to the controller's discretion
     * (e.g. Yuffie, Faris). The actual allocation is handled by {@link GameContext#divideDamageAmount};
     * this is used only to name the followup for description purposes.
     */
    static final Pattern FOLLOWUP_DIVIDE_DAMAGE_AMONG_CHOSEN = Pattern.compile(
        "(?i)\\b(?:divide\\s+\\d+\\s+damage\\s+among\\s+them|split\\s+(?:it\\s+)?as\\s+you\\s+(?:like|wish))\\b"
    );

    // =========================================================================================
    // Granted keyword clauses
    // =========================================================================================
    /**
     * Matches an action ability that temporarily grants the source card its own "deals damage to a
     * Forward → damage increases" field ability:
     * "[Self] gains \"If [Self] deals damage to a Forward, the damage increases by N instead.\"
     * until the end of the turn." (Delita 16-014R). Both the card that "gains" the ability and the
     * subject named inside the quoted ability must be the source card. The granted ability lasts the
     * turn, which is exactly a self outgoing-flat-boost this turn.
     */
    static final Pattern GAINS_OUTGOING_DMG_BOOST_UNTIL_EOT = Pattern.compile(
        "(?i)^(?<subject>.+?)\\s+gains\\s+\"If\\s+(?<inner>.+?)\\s+deals\\s+damage\\s+to\\s+a\\s+Forward" +
        "(?:\\s+opponent\\s+controls?)?,?\\s+the\\s+damage\\s+increases?\\s+by\\s+(?<amount>\\d+)(?:\\s+instead)?\\.\"\\s+" +
        "until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?$");
    /**
     * A quoted "[Self] can attack twice/N times in the same turn." field ability being granted.
     * Group {@code count} is absent for the "twice" wording; "3 times" appears on Bartz Re-078H's
     * Rapid Fire and Gilgamesh (FFBE) 14-023L. Mirrors {@code CardData.FIELD_CAN_ATTACK_TWICE},
     * which reads the same sentence when it is printed rather than granted.
     */
    static final Pattern GRANTED_CAN_ATTACK_TWICE = Pattern.compile(
        "(?i)^(?<subj>.+?)\\s+can\\s+attack\\s+(?:twice|(?<count>\\d+)\\s+times)\\s+" +
        "(?:in\\s+the\\s+same\\s+turn|per\\s+turn)[.!]?$");
    /** A quoted "[Self] cannot be blocked by a Forward of cost N or more/less." field ability being granted. */
    static final Pattern GRANTED_CANNOT_BE_BLOCKED_BY_COST = Pattern.compile(
        "(?i)^(?<subj>.+?)\\s+cannot\\s+be\\s+blocked\\s+by\\s+a\\s+Forward\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+(?<cmp>more|less)[.!]?$");
    /**
     * A quoted "[Self] cannot be blocked by a Forward of power N or more/less." field ability being
     * granted — Iris 12-117R, whose second modal option hands itself the sentence Ark Angel MR
     * 8-045R prints permanently. Mirrors {@link CardData#parseFieldCannotBeBlockedByPower}, which
     * reads the printed form, and sits beside the cost twin above because the wordings differ only
     * in that noun.
     */
    static final Pattern GRANTED_CANNOT_BE_BLOCKED_BY_POWER = Pattern.compile(
        "(?i)^(?<subj>.+?)\\s+cannot\\s+be\\s+blocked\\s+by\\s+a\\s+Forward\\s+of\\s+power\\s+(?<power>\\d+)(?:\\s+or\\s+(?<cmp>more|less))?[.!]?$");
    /**
     * A quoted "[Self] cannot block." field ability being granted — the printed form of the same
     * sentence is {@code CardData.FIELD_CANNOT_BLOCK}, read there into {@code cannotBlockAtAll()}.
     *
     * <p>Anchored, so it cannot be reached by the "cannot be blocked" wordings above: "be blocked"
     * never leaves the subject group ending immediately before "cannot block".
     */
    static final Pattern GRANTED_CANNOT_BLOCK = Pattern.compile(
        "(?i)^(?<subj>.+?)\\s+cannot\\s+block[.!]?$");
    /**
     * Matches both printed wordings of source-scoped EX Burst suppression:
     * <ul>
     *   <li>"Any card [of cost N or less] put in the Damage Zone due to [Name] cannot use its
     *       EX Burst." — Exdeath 1-122H, Arborous Simulacrum 2-118C</li>
     *   <li>"EX Bursts of cards [of cost N or less] put into the Damage Zone due to [Name] cannot
     *       be used." — Shadow Lord B-007 as a printed field ability, and the clause Shadow Lord
     *       12-071R grants itself until end of turn</li>
     * </ul>
     *
     * <p>Distinct from {@link #EX_BURST_SUPPRESSION_PATTERN}, whose "due to this ability" wording
     * spans only one resolution — here the suppression is keyed to the named card, so it applies
     * to that card's combat damage too.
     */
    static final Pattern EX_BURST_SUPPRESSION_BY_SOURCE = Pattern.compile(
        "(?i)(?:" +
            "Any\\s+cards?(?:\\s+of\\s+cost\\s+(?<cost1>\\d+)\\s+or\\s+less)?\\s+put\\s+in(?:to)?\\s+" +
            "the\\s+Damage\\s+Zone\\s+due\\s+to\\s+(?<subj1>.+?)\\s+cannot\\s+use\\s+(?:its|their)\\s+EX\\s+Bursts?" +
        "|" +
            "EX\\s+Bursts?\\s+of\\s+cards?(?:\\s+of\\s+cost\\s+(?<cost2>\\d+)\\s+or\\s+less)?\\s+put\\s+in(?:to)?\\s+" +
            "the\\s+Damage\\s+Zone\\s+due\\s+to\\s+(?<subj2>.+?)\\s+cannot\\s+be\\s+used" +
        ")[.!]?");
    /**
     * "[Self] gains \"[quoted field ability]\" until the end of the turn." (e.g. Tsukinowa).
     *
     * <p>Either quote character is accepted: when this wording is itself nested inside a
     * "select 1 of the 2 following actions" option, the printed text uses single quotes for the
     * inner ability because the option already spent the double quotes (Caius 18-108H).
     */
    static final Pattern GAINS_QUOTED_FIELD_ABILITY_UNTIL_EOT = Pattern.compile(
        "(?i)^(?<subject>.+?)\\s+gains\\s+(?<q>[\"'])(?<quoted>.+?)\\k<q>\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?$");
    /**
     * "[Self] gains \"[ability]\"[ and \"[ability]\"] (This effect does not end at the end of the
     * turn.)" — the priming payoff on Odin (XVI) 29-118L and 24-112L.
     *
     * <p>The parenthetical is what separates this from
     * {@link #GAINS_QUOTED_FIELD_ABILITY_UNTIL_EOT}: the grant outlasts the turn, so it routes to
     * the permanent grant primitives rather than the end-of-turn ones. Up to two quoted abilities
     * may be joined by "and" (24-112L grants an attack trigger and a second-attack permission).
     */
    static final Pattern GAINS_QUOTED_ABILITIES_PERMANENT = Pattern.compile(
        "(?i)^(?<subject>.+?)\\s+gains\\s+\"(?<q1>.+?)\"(?:\\s+and\\s+\"(?<q2>.+?)\")?\\s*" +
        "\\(This\\s+effect\\s+does\\s+not\\s+end\\s+at\\s+the\\s+end\\s+of\\s+the\\s+turn\\.?\\)[.!]?$");
    /**
     * A granted "[CardName] must attack once per turn if possible." clause (Roche 29-076H).
     * Group {@code subj} — the compelled card, which must be the grant's own source.
     *
     * <p>Distinct from {@link #FOLLOWUP_MUST_ATTACK}'s "it must attack this turn if possible":
     * that one binds for the turn it is applied, this one re-arms every turn.
     */
    static final Pattern GRANTED_MUST_ATTACK_ONCE_PER_TURN = Pattern.compile(
        "(?i)^(?<subj>.+?)\\s+must\\s+attack\\s+once\\s+per\\s+turn\\s+if\\s+possible[.!]?$");
    /**
     * The block-side twin of {@link #GRANTED_MUST_ATTACK_ONCE_PER_TURN}, in the two orders it is
     * printed: "This Forward must block if possible." (Tulien 21-072H) and the older "If possible,
     * this Forward must block." Group {@code subj} or {@code subj2} carries the subject, whichever
     * branch matched — the caller puts both through {@link #GRANTED_CLAUSE_SELF_SUBJECT} to confirm
     * the clause is talking about its new carrier.
     */
    static final Pattern GRANTED_MUST_BLOCK_IF_POSSIBLE = Pattern.compile(
        "(?i)^(?:If\\s+possible,\\s+(?<subj2>.+?)\\s+must\\s+block" +
        "|(?<subj>.+?)\\s+must\\s+block\\s+if\\s+possible)[.!]?$");

    // =========================================================================================
    // Permanent self grants
    // =========================================================================================
    /**
     * "[Self] gains [traits | \"[quoted ability]\"] and [Self]'s power becomes N." — a grant that
     * states no duration, and so lasts as long as the card stays on the field (Hyoh 16-097H,
     * Ramza 16-017R). Some printings spell the same thing out in a trailing
     * "(This effect does not end at the end of the turn.)" (Roche 29-076H, Young Excenmille
     * 23-100L), which is reminder text rather than a different effect — hence the optional group.
     *
     * <p>Hyoh's own card carries the reminder as a separate card-level line ("These effects…",
     * plural, covering both abilities), and that line never reaches the resolver — which is why
     * permanence here comes from the <em>absence</em> of a stated duration rather than from the
     * parenthetical.
     *
     * <p>The leading anchor is what separates this from
     * {@link #STANDALONE_SELF_BASE_POWER_BECOMES_UNTIL}, whose otherwise identical wording opens
     * "Until the end of the turn, …". That prefix would be captured into {@code subject} and fail
     * the name check, but the lookahead states the intent and fails faster.
     *
     * <p>Groups: {@code subject} and {@code powersubject} — both must equal the source's name;
     * exactly one of {@code traits} / {@code quoted}; {@code power} — the new base power.
     */
    static final Pattern SELF_GAINS_AND_BASE_POWER_BECOMES_PERMANENT = Pattern.compile(
        "(?i)^(?!Until\\b)(?<subject>[^\"]+?)\\s+gains\\s+" +
        "(?:\"(?<quoted>.+?)\"|(?<traits>(?:Haste|First\\s+Strike|Brave)" +
        "(?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*))\\s+and\\s+" +
        "(?<powersubject>.+?)'s\\s+power\\s+becomes\\s+(?<power>\\d+)[.!]?" +
        "(?:\\s*\\(This\\s+effect\\s+does\\s+not\\s+end\\s+at\\s+the\\s+end\\s+of\\s+the\\s+turn\\.?\\))?" +
        "[.!]?\\s*$");
    /**
     * Matches "[Self] gains [traits] and "[quoted]"." — the trait-list sibling of
     * {@link #SELF_GAINS_AND_BASE_POWER_BECOMES_PERMANENT}, whose second half is a quoted
     * permission rather than a new base power (Ramza 16-017R: "Ramza gains First Strike, Brave and
     * "Ramza can attack twice in the same turn."").
     *
     * <p>No duration is printed, so the grant lasts while the card stays on the field — the same
     * reading its sibling takes, and the reason both are named "permanent". The reminder line some
     * printings add is accepted in either number ("This effect does not" / "These effects don't"),
     * since a sentence granting two things pluralises it.
     *
     * <p>The subject group excludes quote characters, and the negative lookahead refuses a leading
     * "Until the end of the turn, …", so that wording cannot be read as a subject — both for the
     * reason the sibling gives.
     */
    static final Pattern SELF_GAINS_TRAITS_AND_QUOTED_PERMANENT = Pattern.compile(
        "(?i)^(?!Until\\b)(?<subject>[^\"]+?)\\s+gains\\s+" +
        "(?<traits>(?:Haste|First\\s+Strike|Brave)" +
        "(?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)\\s+and\\s+" +
        "\"(?<quoted>.+?)\"[.!]?" +
        "(?:\\s*\\(Th(?:is|ese)\\s+effects?\\s+(?:does\\s+not|do\\s+not|don't)\\s+end\\s+" +
        "at\\s+the\\s+end\\s+of\\s+the\\s+turn\\.?\\))?" +
        "[.!]?\\s*$");
    /**
     * "[Self] gains [+N power][, Haste[, First Strike][ and Brave]] (This effect does not end at
     * the end of the turn.)" — 8-147S Fordola's payoff.
     *
     * <p>The parenthetical is what separates this from {@link #SELF_POWER_BOOST}, whose otherwise
     * identical wording ends in "until the end of the turn" and routes to the end-of-turn boost
     * primitive.  Carries no quoted clause on purpose: a grant that also hands over a quoted
     * ability is {@link #GAINS_QUOTED_ABILITIES_PERMANENT}'s business, and the {@code [^"]} guard
     * on the trait run keeps this pattern from claiming half of one.
     */
    static final Pattern SELF_POWER_BOOST_PERMANENT = Pattern.compile(
        "(?i)^(?<subject>[^\"]+?)\\s+gains?\\s+" +
        "(?:\\+(?<amount>\\d+)\\s+power)?" +
        "(?<traits>(?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "\\s*[.!]?\\s*" +
        "\\(This\\s+effect\\s+does\\s+not\\s+end\\s+at\\s+the\\s+end\\s+of\\s+the\\s+turn\\.?\\)[.!]?$");
    /**
     * Matches "You may remove it/them from the game" — the optional form of
     * {@link #FOLLOWUP_REMOVE_FROM_GAME}, whose pattern is a suffix of this one and would
     * otherwise claim it and remove the card without asking.
     */
    static final Pattern FOLLOWUP_MAY_REMOVE_FROM_GAME = Pattern.compile(
        "(?i)You\\s+may\\s+remove\\s+(?:it|them)\\s+from\\s+(?:the\\s+)?game[.!]?");
    /**
     * Matches a sentence opening with "When/If you do so, …" — one whose effect is contingent on
     * the step described before it.
     *
     * <p>Used by the compound-sentence fallback in {@code parse()}: that fallback drops sentences
     * it cannot resolve, which is safe only while the sentences are independent. A dropped
     * conditional means the surviving ones are the payoff of an unresolved step, and running them
     * grants the payoff for free.
     */
    static final Pattern DO_SO_CONDITIONAL_SENTENCE = Pattern.compile(
        "(?i)^(?:When|If)\\s+you\\s+do\\s+so,");
    /**
     * "Until the end of the turn, [Self] gains [+N power][, traits] and \"[quoted field ability]\"."
     * (e.g. Ace, Tifa). Applies the power/trait boost via {@link GameContext#boostSourceForward} and
     * routes the quoted ability to its grant primitive; returns {@code null} when the quoted ability
     * isn't a supported self-grant.
     */
    static final Pattern UNTIL_EOT_GAINS_POWER_TRAITS_AND_QUOTED = Pattern.compile(
        "(?i)^Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,\\s+(?<subject>.+?)\\s+gains\\s+" +
        "(?<boosts>.+?)\\s+and\\s+\"(?<quoted>.+?)\"[.!]?$");
    static final Pattern POWER_AMOUNT_PLUS = Pattern.compile("(?i)\\+(\\d+)\\s+power");
    /**
     * "Remove it/them from the game for as long as [Name] is on the field." (Necron ETB) —
     * temporary exile that ends when the named watcher leaves the field.
     */
    static final Pattern FOLLOWUP_REMOVE_FROM_GAME_WHILE_ON_FIELD = Pattern.compile(
        "(?i)Remove\\s+(?:it|them)\\s+from\\s+the\\s+game\\s+for\\s+as\\s+long\\s+as\\s+" +
        "(?<name>.+?)\\s+is\\s+on\\s+the\\s+field\\.?"
    );
    /**
     * "Choose 1 card removed by [Name]'s ability. Put it into the Break Zone." (Necron action
     * ability) — only targets cards exiled by this specific source instance's ETB ability;
     * moving one to the Break Zone cancels its pending return to the field.
     */
    static final Pattern CHOOSE_CARD_REMOVED_BY_SOURCE_TO_BZ = Pattern.compile(
        "(?i)Choose\\s+1\\s+card\\s+removed\\s+by\\s+(?<name>.+?)'s\\s+ability\\.\\s*" +
        "Put\\s+it\\s+into\\s+the\\s+Break\\s+Zone\\.?"
    );

    // =========================================================================================
    // Cost payment and sequencing
    // =========================================================================================
    /**
     * "Until the end of your turn, you can cast [CardName] removed by this ability's cost."
     * (Sephiroth) — registers the card instance(s) removed from the game while paying this
     * ability's costs as castable from the RFP zone for the rest of the turn.
     */
    static final Pattern CAST_RFG_COST_CARD_THIS_TURN = Pattern.compile(
        "(?i)Until\\s+the\\s+end\\s+of\\s+(?:your|the)\\s+turn,?\\s+you\\s+(?:can|may)\\s+cast\\s+" +
        "(?<name>.+?)\\s+removed\\s+by\\s+this\\s+ability(?:'s\\s+cost)?[.!]?"
    );
    /** Parses "Draw N card(s)[, then discard M card(s)]" as a standalone effect. */
    static final Pattern WHEN_YOU_DO_SO_SEQUENCE = Pattern.compile(
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
    static final Pattern MAY_COST_REPLAY_ABILITY = Pattern.compile(
        "(?i)You\\s+may\\s+(?:" +
            "pay\\s+《(?<payCost>[^》]+)》" +
            "|dull\\s+active\\s+(?<dullName>[^.,]+)" +
            "|discard\\s+1\\s+Card\\s+Name\\s+(?<discardName>[^.,]+)" +
        ")\\s*[.,]?\\s+(?:When|If)\\s+you\\s+do\\s+so,?\\s+" +
        "use\\s+this\\s+(?:special\\s+)?ability\\s+again\\s+without\\s+paying\\s+the\\s+cost[.!]?"
    );
    /**
     * Matches "[You may] pay 《cost》[《cost》…]. If/When you do so, [effect]." as a whole effect —
     * an optional cost that unlocks something, with no target selection in front of it
     * (Jed 24-096R: "When Jed attacks, you may pay 《C》. If you do so, draw 1 card.").
     *
     * <p>The "you may" is optional because an auto ability's parser lifts it into
     * {@link AutoAbility#youMay()} and hands the effect over starting at "pay". Distinct from
     * {@link #FOLLOWUP_YOU_MAY_PAY_ELEMENT_IF_DO_SO}, which is the same wording appearing
     * <em>after</em> a "Choose 1 …" primary and so applies to the chosen targets.
     * Groups: {@code costs} — the run of 《…》 tokens; {@code effect} — what paying buys.
     */
    static final Pattern MAY_PAY_COST_THEN_EFFECT = Pattern.compile(
        "(?is)^(?:you\\s+may\\s+)?pay\\s+(?<costs>(?:《[^》]+》)+)\\s*[.!]?\\s+" +
        "(?:If|When)\\s+you\\s+do\\s+so[,.]?\\s+(?<effect>.+)$"
    );
    /** One 《…》 token of a cost run. */
    static final Pattern COST_TOKEN = Pattern.compile("《([^》]+)》");
    /** Matches "If a Forward you controlled formed a party this turn, &lt;effect&gt;." */
    static final Pattern IF_OWN_FORWARD_FORMED_PARTY = Pattern.compile(
        "(?is)^if\\s+a\\s+Forward\\s+you\\s+controlled\\s+formed\\s+a\\s+party\\s+this\\s+turn,\\s+(?<effect>.+)$"
    );
    /**
     * Matches "if you control N or less/fewer [Forwards/Backups/Monsters/Characters], [effect]."
     * Groups: {@code max} — the maximum count; {@code type} — card type; {@code effect} — inner effect.
     */
    static final Pattern IF_CONTROL_AT_MOST = Pattern.compile(
        "(?is)^if\\s+you\\s+control\\s+(?<max>\\d+)\\s+or\\s+(?:less|fewer)\\s+" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?<type>Forwards?|Backups?|Monsters?|Characters?),\\s+(?<effect>.+)$"
    );
    /**
     * Matches "If all the [Type] you control have [Element] Element, [effect]."
     * Groups: {@code type}, {@code element}, {@code effect}.
     */
    static final Pattern IF_ALL_HAVE_ELEMENT_GATE = Pattern.compile(
        "(?is)^if\\s+all\\s+the\\s+(?<type>Forwards?|Backups?|Characters?|Monsters?)\\s+" +
        "you\\s+control\\s+have\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)(?:\\s+Element)?,\\s+" +
        "(?<effect>.+)$"
    );
    /** Matches a leading "If you [do not] control &lt;condition&gt;, &lt;effect&gt;" gate. */
    static final Pattern CONTROL_CONDITION_GATE = Pattern.compile(
        "(?is)^if\\s+you\\s+(?<neg>do\\s+not\\s+|don't\\s+)?control\\s+(?<cond>.+?),\\s+(?<effect>.+)$"
    );
    /**
     * Matches "&lt;base&gt;. If you control &lt;condition&gt;, &lt;alternative&gt; instead. [&lt;rest&gt;]" —
     * a replacement clause that swaps the whole base effect for a stronger one when the controller
     * meets a board condition (Black Mage 27-097C: the opponent breaks one of their Forwards of cost
     * 2 or less, or cost 4 or less while you control a Multi-Element Forward).
     *
     * <p>"instead" sits inside the alternative's first sentence, so any sentence after it
     * ({@code rest}) still belongs to the alternative and is re-joined before the branch is parsed.
     * <ul>
     *   <li>Group {@code base} — the effect that applies when the condition is not met</li>
     *   <li>Group {@code cond} — the "you control …" condition</li>
     *   <li>Group {@code alt}  — the replacement effect, up to the word "instead"</li>
     *   <li>Group {@code rest} — further sentences belonging to the replacement effect</li>
     * </ul>
     */
    static final Pattern CONTROL_GATED_INSTEAD_UPGRADE = Pattern.compile(
        "(?is)^(?<base>.+?[.!])\\s+If\\s+you\\s+control\\s+(?<cond>[^,]+?),\\s+" +
        "(?<alt>.+?)\\s+instead[.!]\\s*(?<rest>.*)$"
    );
    /**
     * The card-type noun a "Job X or Card Name Y" choose phrase may hang off either of its
     * branches, as a trailing-anchored replacement string. It names the rows to search rather than
     * part of the job or the name, so it comes off both before they become filters.
     */
    static final String TYPE_NOUN_SUFFIX = "(?i)\\s+(?:Forwards?|Backups?|Monsters?|Characters?)$";
    /** The same noun, found anywhere in such a phrase, to decide which rows the choose searches. */
    static final Pattern UNION_TYPE_NOUN = Pattern.compile(
        "(?i)\\b(Forwards?|Backups?|Monsters?|Characters?)\\b"
    );

    // =========================================================================================
    // Cast-payment and control gates
    // =========================================================================================
    /**
     * Matches "&lt;base&gt;. If the cost to cast &lt;name&gt; was paid with CP of &lt;n&gt; or
     * more/less different Elements, &lt;tail&gt;." — the Summon form of the cast-payment condition.
     *
     * <p>On a Character the same condition is a prefix on a triggered ability and never reaches
     * this family: {@code CardData}'s {@code FA_CAST_PAYMENT_ELEMENTS} strips it into
     * {@link AutoAbility#castPaymentMinElements()}, and {@code AutoAbilityTriggers} tests it
     * before the effect runs. A Summon carries its whole effect in one unnamed block with no
     * trigger to hang that flag on, so the condition arrives here as a trailing sentence over a
     * base that has already resolved.
     *
     * <p>{@code base} is non-greedy but unambiguous: "If the cost to cast" occurs once in every
     * printing of this family, so only one split point exists.
     */
    static final Pattern CAST_PAYMENT_ELEMENTS_GATE = Pattern.compile(
        "(?is)^(?<base>.+?[.!])\\s+If\\s+the\\s+cost\\s+to\\s+cast\\s+(?<card>[^,]+?)\\s+was\\s+paid\\s+" +
        "with\\s+CP\\s+of\\s+(?<count>\\d+)\\s+or\\s+(?<cmp>more|less)\\s+different\\s+Elements,\\s+" +
        "(?<tail>.+?)\\s*$"
    );
    /**
     * Splits an "&lt;alternative&gt; instead" tail off {@link #CAST_PAYMENT_ELEMENTS_GATE}: the
     * condition replaces the base's last clause rather than adding to it (16-016C Bahamut).
     */
    static final Pattern CAST_PAYMENT_ELEMENTS_TAIL_INSTEAD = Pattern.compile(
        "(?is)^(?<alt>.+?)\\s+instead[.!]?\\s*$"
    );
    /**
     * Matches "If the cost to play/cast &lt;name&gt; didn't include CP of &lt;n&gt; or more
     * different Elements, &lt;effect&gt;." — the negated member of the cast-payment family,
     * printed only by 9-099R Livia, whose payoff is putting herself back into the Break Zone.
     *
     * <p>A prefix over the whole effect rather than the trailing sentence
     * {@link #CAST_PAYMENT_ELEMENTS_GATE} reads, and negated, so neither that pattern nor
     * {@code CardData}'s {@code FA_CAST_PAYMENT_ELEMENTS} — which strips the affirmative wording
     * into {@link AutoAbility#castPaymentMinElements()}, a floor and so unable to express "fewer
     * than" — claims it. Groups: {@code card}, {@code count}, {@code effect}.
     */
    static final Pattern CAST_PAYMENT_ELEMENTS_NOT_INCLUDED_GATE = Pattern.compile(
        "(?is)^If\\s+the\\s+cost\\s+to\\s+(?:play|cast)\\s+(?<card>[^,]+?)\\s+" +
        "didn'?t\\s+include\\s+CP\\s+of\\s+(?<count>\\d+)\\s+or\\s+more\\s+different\\s+Elements,\\s+" +
        "(?<effect>.+?)\\s*$"
    );
    /**
     * Matches "If the cost to play/cast &lt;name&gt; was paid with CP of exactly &lt;n&gt;
     * different Elements, &lt;effect&gt;." — 7-029H Kefka and 9-021R Varis.
     *
     * <p>"Exactly" is a window, not the floor {@code CardData}'s {@code FA_CAST_PAYMENT_ELEMENTS}
     * strips into {@link AutoAbility#castPaymentMinElements()} — which is why that stripper does
     * not claim this wording and the condition survives as a prefix for this gate to read. A
     * fourth Element paid must fail it, and against a floor it would pass.
     *
     * <p>Groups: {@code card}, {@code count}, {@code effect}.
     */
    static final Pattern CAST_PAYMENT_EXACT_ELEMENTS_GATE = Pattern.compile(
        "(?is)^If\\s+the\\s+cost\\s+to\\s+(?:play|cast)\\s+(?<card>[^,]+?)\\s+was\\s+paid\\s+" +
        "with\\s+CP\\s+of\\s+exactly\\s+(?<count>\\d+)\\s+different\\s+Elements,\\s+" +
        "(?<effect>.+?)\\s*$"
    );
    /**
     * Matches "If the cost to play/cast &lt;name&gt; was only paid with &lt;Element&gt; CP,
     * &lt;effect&gt;." — 7-029H Kefka, 7-046R Vata and their siblings.
     *
     * <p>Distinct from {@link #CAST_PAYMENT_ELEMENT_CP_GATE}, which asks whether an Element was
     * <em>among</em> the CP spent ("included Lightning CP"): this one asks whether it was the only
     * one, so a payment mixing in a second Element fails it. Both are anchored prefixes over the
     * whole effect, and their wordings do not overlap.
     *
     * <p>Groups: {@code card}, {@code element}, {@code effect}.
     */
    static final Pattern CAST_PAYMENT_ONLY_ELEMENT_CP_GATE = Pattern.compile(
        "(?is)^If\\s+the\\s+cost\\s+to\\s+(?:play|cast)\\s+(?<card>[^,]+?)\\s+was\\s+only\\s+paid\\s+" +
        "with\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+CP,\\s+" +
        "(?<effect>.+?)\\s*$"
    );
    /**
     * The gate clause of {@link #CAST_PAYMENT_ONLY_ELEMENT_CP_GATE} on its own — without the effect
     * it guards, and without anchors, so it can be located rather than only matched whole.
     *
     * <p>Wanted for the same reason its "included [Element] CP" sibling
     * {@link #CAST_PAYMENT_ELEMENT_CP_GATE_CLAUSE} is: half of this family prints the gate as a
     * <em>choose followup</em> rather than ahead of the choose ("choose 1 Forward opponent
     * controls. If the cost to play Baugauven was only paid with Fire CP, deal it 7000 damage"),
     * where it has to be recognised at the head of the followup and stripped off it.
     * Groups: {@code name}, {@code element}.
     */
    static final Pattern CAST_PAYMENT_ONLY_ELEMENT_CP_GATE_CLAUSE = Pattern.compile(
        "(?i)If\\s+the\\s+cost\\s+to\\s+(?:play|cast)\\s+(?<name>.+?)\\s+was\\s+only\\s+paid\\s+" +
        "with\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+CP,\\s+"
    );
    /**
     * Matches "[Until the end of the turn, ]if the CP paid to play/cast &lt;name&gt; was only
     * produced by [&lt;Category X&gt; ]Backups, &lt;effect&gt;." — 7-092C Thancred.
     *
     * <p>The duration sits <em>ahead</em> of the condition on Thancred, which is the whole reason
     * this pattern captures it: the guarded effect ("Thancred gains +2000 power, Haste and First
     * Strike") only parses with the "until the end of the turn," it was printed with, so the
     * parser puts the captured prefix back on the front of the effect before resolving it.
     *
     * <p>Groups: {@code until} — the duration prefix, or null; {@code card}; {@code category} —
     * the Category every paying Backup must carry, or null for any Backup; {@code effect}.
     */
    static final Pattern CAST_CP_PRODUCED_BY_BACKUPS_GATE = Pattern.compile(
        "(?is)^(?<until>Until\\s+the\\s+end\\s+of\\s+the\\s+turn,\\s+)?" +
        "If\\s+the\\s+CP\\s+paid\\s+to\\s+(?:play|cast)\\s+(?<card>[^,]+?)\\s+was\\s+only\\s+produced\\s+" +
        "by\\s+(?:Category\\s+(?<category>\\S+)\\s+)?Backups,\\s+(?<effect>.+?)\\s*$"
    );
    /**
     * Matches "Your opponent may discard &lt;n&gt; cards. If he/she doesn't, &lt;effect&gt;."
     * — 7-029H Kefka, the corpus's one buy-out offer priced in cards from hand.
     *
     * <p>The opponent holds the decision and the effect is the price of declining, so the two
     * halves are read together: matching only the consequence sentence would apply it whether or
     * not the offer was taken. Groups: {@code count}, {@code effect}.
     */
    static final Pattern OPPONENT_MAY_DISCARD_ELSE_EFFECT = Pattern.compile(
        "(?is)^Your\\s+opponent\\s+may\\s+discard\\s+(?<count>\\d+)\\s+cards?[.!]\\s+" +
        "If\\s+(?:he/she|they|s?he)\\s+doesn'?t(?:\\s+do\\s+so)?,\\s+(?<effect>.+?)\\s*$"
    );
    /**
     * Matches "if you control [cond] other than [name], [effect]."
     * Used for abilities like "if you control a Category FFCC Forward other than Bel Dat, draw 1 card."
     * Tried before {@link #CONTROL_CONDITION_GATE} because it is more specific.
     */
    static final Pattern IF_CONTROL_COND_OTHER_THAN = Pattern.compile(
        "(?is)^if\\s+you\\s+(?<neg>don't\\s+|do\\s+not\\s+)?control\\s+(?<cond>.+?)\\s+other\\s+than\\s+(?<exclude>[^,]+?),\\s+(?<effect>.+)$"
    );
    /** Matches "If your opponent controls a(n) [cond] [type], [effect]" — e.g. "a damaged Forward". */
    static final Pattern OPP_CONTROL_CARD_GATE = Pattern.compile(
        "(?is)^if\\s+your\\s+opponent\\s+controls\\s+a(?:n)?\\s+" +
        "(?<cond>damaged|dull|active|attacking|blocking)\\s+" +
        "(?<type>Forwards?|Monsters?|Backups?|Characters?),\\s+" +
        "(?<effect>.+)$"
    );
    /** Matches "If your opponent controls N or more [cond] [type], [effect]." */
    static final Pattern IF_OPP_CONTROLS_N_OR_MORE_COND_TYPE_GATE = Pattern.compile(
        "(?i)^[Ii]f\\s+your\\s+opponent\\s+controls\\s+(?<count>\\d+)\\s+or\\s+more\\s+" +
        "(?<cond>dull|damaged|active|attacking|blocking)\\s+" +
        "(?<type>Forwards?|Monsters?|Backups?|Characters?),\\s+" +
        "(?<effect>.+)$"
    );
    /** Matches "if each player has no cards in their hand(s), [effect]." — both hands must be empty. */
    static final Pattern IF_EACH_PLAYER_EMPTY_HAND_GATE = Pattern.compile(
        "(?i)^[Ii]f\\s+each\\s+player\\s+has\\s+no\\s+cards?\\s+in\\s+" +
        "(?:their|his/her|his\\s+or\\s+her)\\s+hands?,\\s*(?<effect>.+)$",
        Pattern.DOTALL
    );
    /** Matches "if there are N or more different Elements among [type] you control, [effect]." */
    static final Pattern IF_N_DIFF_ELEMENTS_AMONG = Pattern.compile(
        "(?is)^if\\s+there\\s+are\\s+(?<min>\\d+)\\s+or\\s+more\\s+different\\s+Elements?\\s+among\\s+" +
        "(?<type>Forwards?|Backups?|Characters?|Monsters?)\\s+you\\s+control[,.]?\\s+(?<effect>.+)$"
    );
    /** Matches "If you have cast N or more cards this turn, &lt;effect&gt;". */
    static final Pattern IF_CAST_AT_LEAST = Pattern.compile(
        "(?is)^if\\s+you\\s+have\\s+cast\\s+(?<min>\\d+)\\s+or\\s+more\\s+cards?\\s+this\\s+turn,\\s+(?<effect>.+)$"
    );
    /**
     * The same condition stated as a <em>trailing</em> sentence over an effect that has already
     * resolved: "&lt;base&gt;. If you have cast &lt;n&gt; or more cards this turn, &lt;tail&gt;."
     * — 12-039C Alexander's "Draw 1 card. … draw 2 cards instead."
     *
     * <p>{@link #IF_CAST_AT_LEAST} is anchored at the head and so only claims the printings whose
     * whole effect is the gate. This one needs a sentence in front of it, so the two are disjoint
     * and neither can take the other's text.
     *
     * <p>Read like {@link #CAST_PAYMENT_ELEMENTS_GATE}, whose shape this is: the tail is added to
     * the base, or substituted for its last clause when it ends in "instead".
     * Groups: {@code base}, {@code count}, {@code tail}.
     */
    /**
     * The cast-count condition as a <em>choose followup</em>: "Choose N X. If you have cast M or
     * more cards this turn, &lt;followup&gt;." — 12-043C White Mage, 18-113H Cid Haze and 20-052C
     * Gnash.
     *
     * <p>Located rather than matched whole, so it can be recognised at the head of a followup and
     * stripped off it — the same job {@link #CAST_PAYMENT_ELEMENT_CP_GATE_CLAUSE} does for its own
     * family, and needed here for the same reason: every followup matcher scans with
     * {@code find()}, so each of these three found its verb inside the gate clause and ran it on
     * every cast. Gnash broke a Backup whatever had been played.
     * Group: {@code count}.
     */
    static final Pattern CAST_COUNT_GATE_CLAUSE = Pattern.compile(
        "(?i)If\\s+you\\s+have\\s+cast\\s+(?<count>\\d+)\\s+or\\s+more\\s+cards?\\s+this\\s+turn,\\s+"
    );
    static final Pattern CAST_COUNT_GATE = Pattern.compile(
        "(?is)^(?<base>.+?[.!])\\s+If\\s+you\\s+have\\s+cast\\s+(?<count>\\d+)\\s+or\\s+more\\s+" +
        "cards?\\s+this\\s+turn,\\s+(?<tail>.+?)\\s*$"
    );
    /**
     * Matches the two-branch element conditional on a cost discard:
     * "If the discarded card is of Elem1 Element, [eff1]. If the discarded card is of Elem2 Element, [eff2]."
     * Groups: {@code elem1}, {@code eff1}, {@code elem2}, {@code eff2}.
     */
    static final Pattern DISCARD_CONDITIONAL_ELEMENT = Pattern.compile(
        "(?i)If\\s+the\\s+discarded\\s+card\\s+is\\s+of\\s+(?<elem1>\\w+)\\s+Element\\s*,\\s*" +
        "(?<eff1>.+?)\\s+" +
        "If\\s+the\\s+discarded\\s+card\\s+is\\s+of\\s+(?<elem2>\\w+)\\s+Element\\s*,\\s*" +
        "(?<eff2>.+)$",
        Pattern.DOTALL
    );
    /**
     * Matches the single-branch, additive-only variant of the discard-element conditional:
     * "If the discarded card is of Elem Element, also &lt;effect&gt;." Unlike
     * {@link #DISCARD_CONDITIONAL_ELEMENT} (two branches covering the whole ability), this
     * appears as a lone secondary clause tacked on after another cost effect (e.g.
     * "Choose 3 cards in your opponent's Break Zone. Remove them from the game. If the discarded
     * card is of Water Element, also draw 1 card, then discard 1 card.") and only ever grants a
     * bonus — there is no "otherwise" branch.
     */
    static final Pattern DISCARD_CONDITIONAL_ELEMENT_SINGLE = Pattern.compile(
        "(?i)^If\\s+the\\s+discarded\\s+card\\s+is\\s+of\\s+(?<elem>\\w+)\\s+Element\\s*,\\s*" +
        "also\\s+(?<effect>.+)$",
        Pattern.DOTALL
    );
    /**
     * Matches the target-additive discard conditional that tacks an extra effect onto the Forward
     * the primary already chose: "If the discarded card is of Elem Element, it also loses all its
     * abilities until the end of the turn." (The "it/they" pronoun refers back to the chosen target,
     * so the effect is applied to {@link GameContext#lastChosenTargets()} rather than re-selected.)
     */
    static final Pattern DISCARD_CONDITIONAL_TARGET_LOSE_ABILITIES = Pattern.compile(
        "(?i)^If\\s+the\\s+discarded\\s+card\\s+is\\s+of\\s+(?<elem>\\w+)\\s+Element\\s*,\\s*" +
        "(?:it|they)\\s+(?:also\\s+)?loses?\\s+all\\s+(?:its|their)\\s+abilities\\s+" +
        "until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?$");
    /**
     * Matches the "instead" (replacement) discard conditional on a self power boost:
     * "[Self] gains +A power until the end of the turn. If the discarded card is a Card Name X,
     * [Self] gains +B power until the end of the turn instead." Applies the boosted (alt) branch
     * when the cost-discarded card is named X, otherwise the base branch — never both.
     */
    static final Pattern DISCARD_CONDITIONAL_SELF_BOOST_INSTEAD = Pattern.compile(
        "(?is)^(?<primary>.+?\\s+gains?\\s+\\+\\d+\\s+power\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn)\\.\\s+" +
        "If\\s+the\\s+discarded\\s+card\\s+is\\s+(?:a\\s+)?Card\\s+Name\\s+(?<name>.+?)\\s*,\\s*" +
        "(?<alt>.+?\\s+gains?\\s+\\+\\d+\\s+power\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn)\\s+instead[.!]?$");
    /**
     * Matches the no-target additive discard conditional gated on a Multi-Element discard:
     * "draw A card(s), then discard B card(s) from your hand. If the discarded card is a
     * Multi-Element card, draw C card(s), then discard D card(s) from your hand." (Corsair) —
     * repeats the draw/discard only when the first discard was a Multi-Element card.
     */
    static final Pattern DRAW_DISCARD_IF_MULTI_ELEMENT = Pattern.compile(
        "(?i)^draw\\s+(?<d1>\\d+)\\s+cards?,\\s+then\\s+discard\\s+(?<x1>\\d+)\\s+cards?\\s+from\\s+your\\s+hand\\.\\s+" +
        "If\\s+the\\s+discarded\\s+card\\s+is\\s+a\\s+Multi-Element\\s+card,\\s+" +
        "draw\\s+(?<d2>\\d+)\\s+cards?,\\s+then\\s+discard\\s+(?<x2>\\d+)\\s+cards?\\s+from\\s+your\\s+hand[.!]?$");
    /**
     * Matches "[Name] breaks after the attack or the block and doesn't deal any damage."
     * (Vincent 2-078R) — the source deals no damage for the rest of the battle and is broken once
     * that battle ends. Group {@code name} is checked against the ability's own source.
     */
    static final Pattern SOURCE_BREAKS_AFTER_COMBAT_NO_DAMAGE = Pattern.compile(
        "(?i)^(?<name>.+?)\\s+breaks?\\s+after\\s+the\\s+attack(?:\\s+or\\s+the\\s+block)?\\s+and\\s+" +
        "doesn'?t\\s+deal\\s+any\\s+damage[.!]?$"
    );

    // =========================================================================================
    // Field-ability grants
    // =========================================================================================
    /** Matches "All the Forwards [you control|opponent controls] gain "[ability]"." (Vayne 9-022L) */
    static final Pattern FIELD_GRANT_ABILITY_TO_FORWARDS = Pattern.compile(
        "(?i)^All\\s+the\\s+Forwards\\s+(?<who>opponent\\s+controls|you\\s+control)\\s+gains?\\s+" +
        "\"(?<ability>[^\"]+)\"[.!]?$"
    );
    /** Matches the granted ability's own trigger: "At the end of your turn, [effect]". */
    static final Pattern GRANTED_AT_END_OF_YOUR_TURN = Pattern.compile(
        "(?i)^At\\s+the\\s+end\\s+of\\s+your\\s+turn\\s*,\\s+(?<effect>.+)$",
        Pattern.DOTALL
    );
    /**
     * Matches "draw 1 card for each Forward you control. You can only draw up to N cards with this
     * ability." (Hilda 6-122H). Draws {@code min(Forwards you control, N)} — the cap is a hard limit
     * on the ability, not deck protection, so a too-small deck still mills the drawer out.
     */
    static final Pattern DRAW_ONE_PER_FORWARD_CAPPED = Pattern.compile(
        "(?i)^draw\\s+1\\s+card\\s+for\\s+each\\s+Forward\\s+you\\s+control\\.\\s+" +
        "You\\s+can\\s+only\\s+draw\\s+up\\s+to\\s+(?<cap>\\d+)\\s+cards?\\s+with\\s+this\\s+ability[.!]?$");
    static final Pattern DISCARD_N_CARDS = Pattern.compile(
        "(?i)^discard\\s+(?<count>\\d+)\\s+cards?(?:\\s+from\\s+your\\s+hand)?[.!]?$"
    );
    /** Matches "discard N cards" at the start of an effect text (may have more text after). */
    static final Pattern DISCARD_N_CARDS_PREFIX = Pattern.compile(
        "(?i)^discard\\s+(?<count>\\d+)\\s+cards?[.!]?(?:\\s|$)"
    );
    /** "The [targets] you control gain +N power." — companion to CardData's bare-grant pattern. */
    static final Pattern FIELD_GRANT_BARE_PASSIVE = Pattern.compile(
        "(?i)^The\\s+(?:Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+" +
        "you\\s+control\\s+gains?\\s+\\+\\d+\\s+power[.!]?$"
    );
    /** "The [Job (X)] / Job X / Category Y Forwards you control gain +N power." — bracket or plain form. */
    static final Pattern FIELD_GRANT_JOB_CAT_PASSIVE = Pattern.compile(
        "(?i)^The\\s+" +
        "(?:\\[Job\\s*\\([^)]+\\)\\]|Job\\s+[A-Za-z][A-Za-z\\s''\\-]+?|" +
        "\\[Category\\s*\\([^)]+\\)\\]|Category\\s+\\S+)\\s+" +
        "(?:Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+" +
        "you\\s+control\\s+gains?\\s+\\+\\d+\\s+power[.!]?$"
    );
    /** "The [targets] opponent controls lose N power." — companion to CardData's opponent-debuff pattern. */
    static final Pattern FIELD_OPPONENT_DEBUFF_PASSIVE = Pattern.compile(
        "(?i)^The\\s+(?:Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+" +
        "(?:your\\s+)?opponent\\s+controls?\\s+loses?\\s+\\d+\\s+power[.!]?$"
    );
    /** "If there are N or more cards in your Break Zone, ..." or "If you have N or more Job X ... in your Break Zone, ..." */
    static final Pattern FIELD_GRANT_BZ_COND_PASSIVE = Pattern.compile(
        "(?i)^If\\s+(?:there\\s+are|you\\s+have)\\s+\\d+\\s+or\\s+more\\s+.+?\\s+in\\s+your\\s+Break\\s+Zone,"
    );
    /** "If there are N or more different Elements among [type] you control, [grant]." */
    static final Pattern FIELD_GRANT_DIFF_ELEM_COND_PASSIVE = Pattern.compile(
        "(?i)^If\\s+there\\s+are\\s+\\d+\\s+or\\s+more\\s+different\\s+Elements?\\s+among\\s+" +
        "(?:Forwards?|Backups?|Characters?|Monsters?)\\s+you\\s+control[,.]"
    );

    // =========================================================================================
    // Counters on chosen Forwards; special-ability reuse
    // =========================================================================================
    /**
     * Matches "choose [up to] N Forwards. Until the end of the turn, they gain "&lt;ability&gt;"."
     * (Machinist) — grants the quoted action ability to each chosen Forward until end of turn.
     * Group {@code upto} present when "up to"; {@code count}; {@code ability} — the quoted grant text.
     */
    static final Pattern CHOOSE_FORWARDS_GAIN_ABILITY_EOT = Pattern.compile(
        "(?i)^choose\\s+(?<upto>up\\s+to\\s+)?(?<count>\\d+)\\s+Forwards?[.!]?\\s+" +
        "Until\\s+the\\s+end\\s+of\\s+the\\s+turn,?\\s+(?:they|it)\\s+gains?\\s+" +
        "\"(?<ability>[^\"]+)\"[.!]?\\s*$",
        Pattern.DOTALL
    );
    /**
     * Matches "choose 1 Forward. Place 1 Petrification Counter on it …" (Medusa). The chosen Forward
     * receives a Petrification Counter; the "cannot attack or block while petrified" restriction and
     * the "《5》: Remove all Petrification Counters" ability are driven off the counter's presence
     * (see {@code MainWindow#isFieldAbilityCannotAttackOrBlock} and {@code addAbilityMenuItems}).
     */
    static final Pattern CHOOSE_FORWARD_PLACE_PETRIFICATION = Pattern.compile(
        "(?i)^choose\\s+1\\s+Forward[.!]?\\s+Place\\s+1\\s+Petrification\\s+Counter\\s+on\\s+it\\b.*",
        Pattern.DOTALL
    );
    /**
     * Matches "Remove all &lt;Name&gt; Counters from this Forward." — removes every counter of the
     * named kind from the ability's own source card (used by Medusa's granted "《5》:" ability).
     */
    static final Pattern REMOVE_ALL_COUNTERS_FROM_SELF = Pattern.compile(
        "(?i)^Remove\\s+all\\s+(?<name>.+?)\\s+Counters\\s+from\\s+this\\s+Forward[.!]?\\s*$"
    );
    /**
     * Matches "Choose 1 Forward you control. Until the end of the turn, it gains +N power[,
     * keywords] and "&lt;quoted grant&gt;" [and "&lt;quoted grant&gt;"…]. If your opponent has
     * received M points of damage or more, all the Forwards you control gain all previous
     * effects instead."
     */
    static final Pattern CHOOSE_OWN_FWD_BOOST_PROTECTIONS_OR_ALL_IF_DMG = Pattern.compile(
        "(?i)^Choose\\s+1\\s+Forward\\s+you\\s+control\\.\\s+" +
        "Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,\\s+it\\s+gains\\s+\\+(?<amount>\\d+)\\s+power" +
        "(?:,\\s*(?<traits>(?:Haste|First\\s+Strike|Brave)(?:\\s*,\\s*(?:Haste|First\\s+Strike|Brave))*))?" +
        "(?<quotes>(?:,?\\s+and\\s+\"[^\"]*\")+)\\s*\\.?\\s+" +
        "If\\s+your\\s+opponent\\s+has\\s+received\\s+(?<dmg>\\d+)\\s+points?\\s+of\\s+damage\\s+or\\s+more,\\s+" +
        "all\\s+the\\s+Forwards\\s+you\\s+control\\s+gain\\s+all\\s+(?:the\\s+)?previous\\s+effects\\s+instead\\.?\\s*$"
    );
    /**
     * Matches "Activate all the Forwards you control. Until the end of the turn, all the
     * Forwards you control gain "&lt;quoted grant&gt;" [and "&lt;quoted grant&gt;"…]."
     */
    static final Pattern ACTIVATE_ALL_OWN_FWDS_GAIN_PROTECTIONS = Pattern.compile(
        "(?i)^Activate\\s+all\\s+(?:the\\s+)?Forwards\\s+you\\s+control\\.\\s+" +
        "Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,\\s+all\\s+(?:the\\s+)?Forwards\\s+you\\s+control\\s+gain\\s+" +
        "(?<quotes>\"[^\"]*\"(?:\\s+and\\s+\"[^\"]*\")*)\\s*\\.?\\s*$"
    );
    /** Extracts the contents of each "…" quote in a quoted-grant list. */
    static final Pattern QUOTED_GRANT = Pattern.compile("\"([^\"]*)\"");
    /**
     * Matches Gogo's "Mimic": "Use 1 special ability that a Character has used this turn
     * [other than Ability Name X] without paying the cost." Captures the excluded ability name.
     */
    static final Pattern USE_SPECIAL_ABILITY_USED_THIS_TURN = Pattern.compile(
            "(?i)^Use 1 special ability that a Character has used this turn"
            + "(?:\\s+other than Ability Name (?<excluded>.+?))?"
            + "\\s+without paying the cost\\.?$");
    /**
     * Matches Libroarian 8-084R's end-of-turn ability: "add N card(s) removed by the previous effect
     * to your hand." optionally followed by "Then, if there are no more cards removed by the previous
     * effect left, put [Self] into the Break Zone."
     * <ul>
     *   <li>Group {@code count} — how many removed cards to take back</li>
     *   <li>Group {@code name}  — the card broken once none are left; absent when there is no such clause</li>
     * </ul>
     */
    static final Pattern ADD_REMOVED_BY_PREVIOUS_EFFECT_TO_HAND = Pattern.compile(
        "(?i)^add\\s+(?<count>\\d+)\\s+cards?\\s+removed\\s+by\\s+the\\s+previous\\s+effect\\s+to\\s+your\\s+hand[.!]?" +
        "(?:\\s*Then[,.]?\\s+if\\s+there\\s+(?:are|is)\\s+no\\s+more\\s+cards?\\s+removed\\s+by\\s+the\\s+previous\\s+" +
        "effect\\s+left[,.]?\\s+put\\s+(?<name>.+?)\\s+into\\s+the\\s+Break\\s+Zone[.!]?)?$",
        Pattern.DOTALL
    );
    /**
     * Matches the "cards removed by [CardName]'s ability" family, which calls back the pile a card
     * built up with its own removal ability:
     * <ul>
     *   <li>"add all the cards removed by X's ability to your hand." — Gutsco 14-010H, Cloud of Darkness B-012</li>
     *   <li>"add 1 card removed by X's ability to your hand, and put the rest of the cards into the
     *       Break Zone." — Cloud of Darkness 10-140S</li>
     *   <li>"add the card removed by X's ability to your hand." — Wind Drake 29-121R, whose removal
     *       ability takes exactly one card, so the singular definite article stands in for "1"</li>
     * </ul>
     * Group {@code all} is set for the "all the cards" form; {@code rest} for the "put the rest into
     * the Break Zone" tail; {@code name} is checked against the ability's own source.
     */
    static final Pattern ADD_REMOVED_BY_SOURCE_ABILITY_TO_HAND = Pattern.compile(
        "(?i)^add\\s+(?:(?<all>all\\s+the)|1|the)\\s+cards?\\s+removed\\s+by\\s+(?<name>.+?)'s?\\s+ability\\s+" +
        "to\\s+your\\s+hand(?<rest>\\s*,?\\s*and\\s+put\\s+the\\s+rest\\s+of\\s+the\\s+cards?\\s+into\\s+" +
        "the\\s+Break\\s+Zone)?[.!]?$",
        Pattern.DOTALL
    );
    /**
     * Matches Anima 19-123H's end-of-turn compound: "remove the top card of your deck from the game.
     * Then, if there are N or more cards removed by [Self]'s ability, add them to your hand and break
     * all the Forwards opponent controls."  Parsed as one unit because the threshold counts the pile
     * <em>after</em> this turn's removal, and the payoff is gated on it.
     * <ul>
     *   <li>Group {@code removed} — how many cards this turn's removal takes off the deck</li>
     *   <li>Group {@code threshold} — the pile size that triggers the payoff</li>
     * </ul>
     */
    static final Pattern REMOVE_TOP_THEN_IF_PILE_AT_LEAST = Pattern.compile(
        "(?i)^remove\\s+the\\s+top\\s+(?:(?<removed>\\d+)\\s+cards?|card)\\s+of\\s+your\\s+deck\\s+from\\s+" +
        "(?:the\\s+)?game[.!]?\\s*Then[,.]?\\s+if\\s+there\\s+(?:are|is)\\s+(?<threshold>\\d+)\\s+or\\s+more\\s+" +
        "cards?\\s+removed\\s+by\\s+(?<name>.+?)'s?\\s+ability[,.]?\\s+add\\s+them\\s+to\\s+your\\s+hand\\s+and\\s+" +
        "break\\s+all\\s+the\\s+Forwards\\s+opponent\\s+controls[.!]?$",
        Pattern.DOTALL
    );

    // =========================================================================================
    // Break Zone play; self-benefit fragments
    // =========================================================================================
    /**
     * Matches a shield granted against the opponent's own effects — "cannot be returned to its
     * owner's hand / chosen / broken / dulled … by your opponent's Summons or abilities".
     */
    static final Pattern OWN_FORWARD_PROTECTION = Pattern.compile(
        "(?i)cannot\\s+be\\s+(?:returned\\s+to\\s+its\\s+owner's\\s+hand|chosen|broken|dulled" +
        "|removed\\s+from\\s+the\\s+game)[^.]*?\\bby\\s+your\\s+opponent's\\b");
    /**
     * Wordings that pay off the moment the ability resolves, independently of anything the
     * opponent does — a power boost, a keyword grant, or an activation.
     */
    static final Pattern IMMEDIATE_OWN_BENEFIT = Pattern.compile(
        "(?i)\\+\\d+\\s+power|\\bgains?\\s+(?:Haste|First\\s+Strike|Brave)\\b|\\bActivate\\b");
    /** Matches "play all the Card Name X from your Break Zone onto [the] field [dull]." */
    static final Pattern PLAY_ALL_FROM_BREAK_ZONE_PATTERN = Pattern.compile(
        "(?i)^play\\s+all\\s+the\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+from\\s+your\\s+Break\\s+Zone\\s+onto\\s+(?:the\\s+)?field(?:\\s+(?<dull>dull))?[.!]?$"
    );
    /** Matches "play [source card name] from [your/the] Break Zone onto [the] field [dull]." */
    static final Pattern PLAY_SOURCE_FROM_BREAK_ZONE = Pattern.compile(
        "(?i)^play\\s+(?<name>.+?)\\s+from\\s+(?:your\\s+|the\\s+)?Break\\s+Zone\\s+onto\\s+(?:the\\s+)?field(?:\\s+(?<dull>dull))?[.!]?$"
    );
}
