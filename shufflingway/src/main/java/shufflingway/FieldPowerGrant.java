package shufflingway;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * A passive always-on grant: "The [Element / Job X / Category Y / Card Name Z] [type] [with Trait]
 * [other than W] you control gain +N power [and Trait]." or its opponent-side counterpart
 * "The [filter] [type] opponent controls lose N power."
 *
 * <p>Active while the owning card is on the field. When {@link #affectsOpponent} is {@code false}
 * the grant scopes to the same player's side (the default); when {@code true} the grant scopes
 * to the opposing player's matching cards (in which case {@link #powerBonus} is typically negative).
 * When the owning card leaves the field the bonus ceases immediately.
 */
public record FieldPowerGrant(
        String  jobFilter,           // null = any job
        String  categoryFilter,      // null = any category
        boolean inclForwards,
        boolean inclBackups,
        boolean inclMonsters,
        String  exceptCardName,      // excluded target name ("other than X"); "" = none
        int     powerBonus,
        Set<CardData.Trait> grantedTraits,
        boolean affectsOpponent,
        int     costFilter,          // -1 = any cost; N = filter applies at value N (exact, or less/more per costCmp)
        String  costCmp,             // null = exact match; "less" = cost ≤ N; "more" = cost ≥ N
        String  elementFilter,       // null = any element; e.g. "Ice", "Fire|Water"
        String  inclCardName,        // null = any name; non-null = only cards whose name matches this
        int     minBzSize,           // 0 = no restriction; >0 = grant applies only when own BZ has ≥ this many total cards
        int     minBzFilterCount,    // 0 = no restriction; >0 = requires this many filtered BZ cards (see bzFilterJob/bzFilterFwds)
        String  bzFilterJob,         // job filter for BZ cards counted by minBzFilterCount; null = any job
        String  bzFilterCardName,    // if non-null, BZ must contain at least 1 card with this name
        boolean bzFilterFwds,        // true = only count Forwards when evaluating minBzFilterCount
        boolean yourTurnOnly,        // true = trait/power grant applies only during the controller's turn
        int     minDistinctElements, // 0 = no restriction; >0 = grant applies only when controller has ≥ this many distinct elements among the target type
        int     exBurstDmgPerGroup,  // 0 = unused; >0 = bonus per group of exBurstDmgGroupSize EX Burst cards in own damage zone
        int     exBurstDmgGroupSize, // group size for EX Burst damage zone scaling (e.g. 3 means +N per 3 EX Burst cards)
        int     minDamageThreshold,  // 0 = no restriction; >0 = grant applies only when controller's damage zone has ≥ this many cards
        int     maxDamageThreshold,  // 0 = no restriction; >0 = grant applies only when controller's damage zone has < this many cards
        Set<CardData.Trait> traitFilter, // empty = any card; non-empty = only cards having at least one of these traits
        boolean attackingOnly,       // true = applies only while the target is one of the declared attackers
        int     basePowerSet,        // 0 = unused; >0 = the target's base power becomes this instead of its printed value
        String  excludeElement,      // null = no exclusion; non-null = skip cards carrying this element
        String  partyWithCardName,   // null = no party condition; else the card the target must be partied with
        boolean oppTurnOnly          // true = trait/power grant applies only during the opposing player's turns
) {
    public FieldPowerGrant {
        // EnumSet, not Set.copyOf: the latter randomises iteration order per JVM run
        // (ImmutableCollections.SALT), which leaks into rendered trait lists.
        EnumSet<CardData.Trait> traitSet = EnumSet.noneOf(CardData.Trait.class);
        traitSet.addAll(grantedTraits);
        grantedTraits = Collections.unmodifiableSet(traitSet);
        EnumSet<CardData.Trait> filterSet = EnumSet.noneOf(CardData.Trait.class);
        if (traitFilter != null) filterSet.addAll(traitFilter);
        traitFilter = Collections.unmodifiableSet(filterSet);
        if (exceptCardName == null) exceptCardName = "";
    }

    /**
     * This grant gated on the controller having taken {@code threshold} points of damage — the
     * "Damage N -- [self] gains "…"" printings, whose quoted half is an ordinary passive grant
     * parsed on its own and then handed the gate its wrapper carried (Aranea 11-086L, Yang 13-064R,
     * Yuna 16-134S).
     *
     * <p>Returns {@code this} for a non-positive threshold, so an ungated caller can pipe every
     * grant through without branching.
     */
    FieldPowerGrant withMinDamageThreshold(int threshold) {
        if (threshold <= 0) return this;
        return new FieldPowerGrant(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, costCmp,
                elementFilter, inclCardName, minBzSize, minBzFilterCount, bzFilterJob,
                bzFilterCardName, bzFilterFwds, yourTurnOnly, minDistinctElements, exBurstDmgPerGroup,
                exBurstDmgGroupSize, threshold, maxDamageThreshold, traitFilter, attackingOnly,
                basePowerSet, excludeElement, partyWithCardName, oppTurnOnly);
    }

    /**
     * This grant restricted to one player's turns — Rydia 28-072L, "During your opponent's turn,
     * all the Forwards you control gain +2000 power."
     *
     * <p>A wither rather than another {@code sameSideFiltered} overload: the window is orthogonal to
     * every filter, and the parse site already has the finished grant in hand when it reads it.
     * One wither for both directions, since a sentence naming a window names exactly one.
     *
     * @param opponentsTurn {@code true} for "during your opponent's turn", {@code false} for
     *                      "during your turn" — "your" being the carrier's controller either way
     */
    FieldPowerGrant withTurnWindow(boolean opponentsTurn) {
        return new FieldPowerGrant(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, costCmp,
                elementFilter, inclCardName, minBzSize, minBzFilterCount, bzFilterJob,
                bzFilterCardName, bzFilterFwds, !opponentsTurn, minDistinctElements,
                exBurstDmgPerGroup, exBurstDmgGroupSize, minDamageThreshold, maxDamageThreshold,
                traitFilter, attackingOnly, basePowerSet, excludeElement, partyWithCardName,
                opponentsTurn);
    }

    /**
     * Same-side grant filtered only by target type, element and/or trait — the shape produced by
     * "The [Element] [type] [with Trait] [other than X] you control gain +N power [and Trait]."
     */
    static FieldPowerGrant sameSideFiltered(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            String elementFilter, Set<CardData.Trait> traitFilter) {
        return sameSideFiltered(inclForwards, inclBackups, inclMonsters, exceptCardName, powerBonus,
                grantedTraits, elementFilter, traitFilter, false);
    }

    /**
     * As {@link #sameSideFiltered(boolean, boolean, boolean, String, int, Set, String, Set)}, with
     * the "attacking" state filter of Lava Spider 8-022R
     * ("The attacking Forwards you control gain +3000 power.").
     */
    static FieldPowerGrant sameSideFiltered(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            String elementFilter, Set<CardData.Trait> traitFilter, boolean attackingOnly) {
        return new FieldPowerGrant(null, null, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, false, -1, null, elementFilter,
                null, 0, 0, null, null, false, false, 0, 0, 1, 0, 0, traitFilter, attackingOnly, 0);
    }

    /**
     * As {@link #sameSideFiltered(boolean, boolean, boolean, String, int, Set, String, Set)}, with a
     * Job and/or Category narrowing the set — "The Job Guardian other than Kimahri you control …"
     * and "The Category X Characters other than Kimahri you control …" (both Kimahri 7-108H).
     *
     * <p>The two filters are conjunctive in {@link #appliesToCard}, as they are everywhere else;
     * no corpus printing states both, so each arrives with the other null.
     */
    static FieldPowerGrant sameSideFiltered(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            String elementFilter, Set<CardData.Trait> traitFilter) {
        return new FieldPowerGrant(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, false, -1, null, elementFilter,
                null, 0, 0, null, null, false, false, 0, 0, 1, 0, 0, traitFilter, false, 0);
    }

    /**
     * Same-side "the power of [Job X | Card Name Y] Forwards [other than Z] you control becomes N" —
     * Faris 21-114L. Sets {@link #basePowerSet} rather than {@link #powerBonus}, so every other
     * boost and reduction lands on top of the replaced value rather than on the printed one.
     *
     * <p>{@code jobFilter} and {@code inclCardName} are conjunctive in {@link #appliesToCard}, so a
     * printing that ORs the two (Faris covers Job Pirate <em>and</em> Card Name Viking) produces one
     * grant per branch. Overlap is harmless: both set the same base power.
     */
    static FieldPowerGrant sameSideBasePower(String jobFilter, String inclCardName,
            String exceptCardName, int basePowerSet) {
        return new FieldPowerGrant(jobFilter, null, true, false, false,
                exceptCardName, 0, EnumSet.noneOf(CardData.Trait.class), false, -1, null, null,
                inclCardName, 0, 0, null, null, false, false, 0, 0, 1, 0, 0,
                EnumSet.noneOf(CardData.Trait.class), false, basePowerSet);
    }

    /**
     * Compatibility constructor preserving the prior 29-arg canonical form; defaults
     * {@code oppTurnOnly} to false.
     *
     * <p>A real component rather than a sentinel on an existing field, unlike {@link #ANY_PARTY}:
     * {@link #yourTurnOnly} is a boolean with no spare value to overload, and the two are read
     * together at both sites that gate on a turn window. Every other overload funnels through this
     * arity, so the twelve below did not have to change.
     */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent, int costFilter, String costCmp, String elementFilter,
            String inclCardName, int minBzSize, int minBzFilterCount, String bzFilterJob,
            String bzFilterCardName, boolean bzFilterFwds, boolean yourTurnOnly,
            int minDistinctElements, int exBurstDmgPerGroup, int exBurstDmgGroupSize,
            int minDamageThreshold, int maxDamageThreshold, Set<CardData.Trait> traitFilter,
            boolean attackingOnly, int basePowerSet, String excludeElement,
            String partyWithCardName) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, costCmp,
                elementFilter, inclCardName, minBzSize, minBzFilterCount, bzFilterJob,
                bzFilterCardName, bzFilterFwds, yourTurnOnly, minDistinctElements,
                exBurstDmgPerGroup, exBurstDmgGroupSize, minDamageThreshold, maxDamageThreshold,
                traitFilter, attackingOnly, basePowerSet, excludeElement, partyWithCardName, false);
    }

    /** Compatibility constructor preserving the prior 27-arg canonical form; defaults {@code excludeElement/partyWithSource} to null/false. */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent, int costFilter, String costCmp, String elementFilter,
            String inclCardName, int minBzSize, int minBzFilterCount, String bzFilterJob,
            String bzFilterCardName, boolean bzFilterFwds, boolean yourTurnOnly,
            int minDistinctElements, int exBurstDmgPerGroup, int exBurstDmgGroupSize,
            int minDamageThreshold, int maxDamageThreshold, Set<CardData.Trait> traitFilter,
            boolean attackingOnly, int basePowerSet) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, costCmp, elementFilter,
                inclCardName, minBzSize, minBzFilterCount, bzFilterJob, bzFilterCardName, bzFilterFwds, yourTurnOnly,
                minDistinctElements, exBurstDmgPerGroup, exBurstDmgGroupSize,
                minDamageThreshold, maxDamageThreshold, traitFilter, attackingOnly, basePowerSet, null, null);
    }

    /**
     * The both-sides debuff "The [type] of an Element other than [X] lose N power." — Tchakka
     * 18-092C. It names no controller, so it reaches every matching Character on the board; the
     * caller emits one of these per side, since {@link #affectsOpponent} picks one side or the other.
     */
    static FieldPowerGrant elementExcludedDebuff(boolean inclForwards, boolean inclBackups,
            boolean inclMonsters, int powerBonus, String excludeElement, boolean affectsOpponent) {
        return new FieldPowerGrant(null, null, inclForwards, inclBackups, inclMonsters,
                null, powerBonus, EnumSet.noneOf(CardData.Trait.class), affectsOpponent, -1, null, null,
                null, 0, 0, null, null, false, false, 0, 0, 1, 0, 0,
                EnumSet.noneOf(CardData.Trait.class), false, 0, excludeElement, null);
    }

    /**
     * The party-conditioned grant "The Forwards forming a party with [X] gain [traits]." —
     * Chocobo 2-060C. Party membership is board state rather than a property of the card, so
     * {@link #appliesToCard} cannot resolve it; {@code MainWindow} gates on
     * {@link #partyWithCardName} while summing contributions, exactly as it does for
     * {@link #attackingOnly}.
     *
     * <p>The name is carried rather than reduced to a "with me" flag so the check stays honest: the
     * only printing names its own carrier, and a future one naming somebody else must not silently
     * become a self-reference.
     */
    static FieldPowerGrant partyWithGrant(boolean inclForwards, boolean inclBackups,
            boolean inclMonsters, int powerBonus, Set<CardData.Trait> grantedTraits,
            String partyWithCardName) {
        return new FieldPowerGrant(null, null, inclForwards, inclBackups, inclMonsters,
                null, powerBonus, grantedTraits, false, -1, null, null,
                null, 0, 0, null, null, false, false, 0, 0, 1, 0, 0,
                EnumSet.noneOf(CardData.Trait.class), false, 0, null, partyWithCardName);
    }

    /**
     * The {@link #partyWithCardName} value standing for "any party its controller has declared",
     * used by the printings that condition on forming a party without naming a card to form it with
     * (Gippal 12-058C's "The Forwards forming a party you control gain Brave.").
     *
     * <p>A sentinel rather than a thirtieth record component: {@code partyWithCardName} is read in
     * exactly one place ({@code MainWindow.fpgPartyConditionMet}), so one branch there expresses the
     * whole difference, where a new field would mean editing the canonical constructor and all
     * twelve compatibility overloads for a single card. It is deliberately not a legal card name —
     * no printing contains a NUL — so the name comparison the other party grants make cannot
     * accidentally satisfy it, and a card called "Any Party" would still not collide.
     */
    static final String ANY_PARTY = "\0any-party";

    /**
     * The unnamed counterpart of {@link #partyWithGrant}: "The [type] forming a party you control
     * gain [+N power] [and] [Trait…]." — Gippal 12-058C.
     *
     * <p>The condition is on the <em>target</em> alone. Gippal need not be in the party himself, or
     * attacking at all, which is what separates this from the named form: there, the grant flows
     * from one specific partymate to the others.
     */
    static FieldPowerGrant partyAnyGrant(boolean inclForwards, boolean inclBackups,
            boolean inclMonsters, int powerBonus, Set<CardData.Trait> grantedTraits) {
        return partyWithGrant(inclForwards, inclBackups, inclMonsters, powerBonus, grantedTraits,
                ANY_PARTY);
    }

    /** Compatibility constructor preserving the prior 25-arg canonical form; defaults {@code attackingOnly/basePowerSet} to false/0. */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent, int costFilter, String costCmp, String elementFilter,
            String inclCardName, int minBzSize, int minBzFilterCount, String bzFilterJob,
            String bzFilterCardName, boolean bzFilterFwds, boolean yourTurnOnly,
            int minDistinctElements, int exBurstDmgPerGroup, int exBurstDmgGroupSize,
            int minDamageThreshold, int maxDamageThreshold, Set<CardData.Trait> traitFilter) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, costCmp, elementFilter,
                inclCardName, minBzSize, minBzFilterCount, bzFilterJob, bzFilterCardName, bzFilterFwds, yourTurnOnly,
                minDistinctElements, exBurstDmgPerGroup, exBurstDmgGroupSize,
                minDamageThreshold, maxDamageThreshold, traitFilter, false, 0);
    }

    /** Compatibility constructor preserving the prior 24-arg canonical form; defaults {@code traitFilter} to empty. */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent, int costFilter, String costCmp, String elementFilter,
            String inclCardName, int minBzSize, int minBzFilterCount, String bzFilterJob,
            String bzFilterCardName, boolean bzFilterFwds, boolean yourTurnOnly,
            int minDistinctElements, int exBurstDmgPerGroup, int exBurstDmgGroupSize,
            int minDamageThreshold, int maxDamageThreshold) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, costCmp, elementFilter,
                inclCardName, minBzSize, minBzFilterCount, bzFilterJob, bzFilterCardName, bzFilterFwds, yourTurnOnly,
                minDistinctElements, exBurstDmgPerGroup, exBurstDmgGroupSize,
                minDamageThreshold, maxDamageThreshold, EnumSet.noneOf(CardData.Trait.class));
    }

    /** Compatibility constructor preserving the prior 21-arg form; defaults {@code minDamageThreshold/maxDamageThreshold} to 0. */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent, int costFilter, String costCmp, String elementFilter,
            String inclCardName, int minBzSize, int minBzFilterCount, String bzFilterJob,
            boolean bzFilterFwds, boolean yourTurnOnly, int minDistinctElements,
            int exBurstDmgPerGroup, int exBurstDmgGroupSize) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, costCmp, elementFilter,
                inclCardName, minBzSize, minBzFilterCount, bzFilterJob, null, bzFilterFwds, yourTurnOnly,
                minDistinctElements, exBurstDmgPerGroup, exBurstDmgGroupSize, 0, 0);
    }

    /** Compatibility constructor preserving the prior 19-arg form; defaults {@code exBurstDmgPerGroup/GroupSize} to 0/1. */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent, int costFilter, String costCmp, String elementFilter,
            String inclCardName, int minBzSize, int minBzFilterCount, String bzFilterJob,
            boolean bzFilterFwds, boolean yourTurnOnly, int minDistinctElements) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, costCmp, elementFilter,
                inclCardName, minBzSize, minBzFilterCount, bzFilterJob, null, bzFilterFwds, yourTurnOnly,
                minDistinctElements, 0, 1, 0, 0);
    }

    /** Compatibility constructor preserving the prior 18-arg form; defaults {@code minDistinctElements} to 0. */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent, int costFilter, String costCmp, String elementFilter,
            String inclCardName, int minBzSize, int minBzFilterCount, String bzFilterJob,
            boolean bzFilterFwds, boolean yourTurnOnly) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, costCmp, elementFilter,
                inclCardName, minBzSize, minBzFilterCount, bzFilterJob, bzFilterFwds, yourTurnOnly, 0);
    }

    /** Convenience constructor without BZ conditions; defaults {@code minBzSize/minBzFilterCount} to 0. */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent, int costFilter, String costCmp, String elementFilter,
            String inclCardName) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, costCmp, elementFilter,
                inclCardName, 0, 0, null, false, false, 0);
    }

    /** Convenience constructor without {@code inclCardName} or any BZ conditions. */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent, int costFilter, String costCmp, String elementFilter) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, costCmp, elementFilter,
                null, 0, 0, null, false, false, 0);
    }

    /** Convenience constructor without {@code elementFilter} or {@code inclCardName}. */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent, int costFilter, String costCmp) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, costCmp, null, null,
                0, 0, null, false, false, 0);
    }

    /** Convenience constructor without {@code costCmp}, {@code elementFilter}, or {@code inclCardName}. */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent, int costFilter) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, null, null, null,
                0, 0, null, false, false, 0);
    }

    /** Convenience constructor for the common same-side ("you control") grant form (no cost filter). */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, false, -1, null, null, null,
                0, 0, null, false, false, 0);
    }

    /** Convenience constructor for the opponent-debuff form (no cost filter). */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, -1, null, null, null,
                0, 0, null, false, false, 0);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (affectsOpponent) sb.append("opp:");
        if (elementFilter  != null) sb.append(elementFilter).append(' ');
        if (jobFilter      != null) sb.append(jobFilter).append(' ');
        if (categoryFilter != null) sb.append(categoryFilter).append(' ');
        if (inclCardName   != null) sb.append("CardName:").append(inclCardName).append(' ');
        if (costFilter >= 0) {
            sb.append("cost").append(costFilter);
            if      ("less".equalsIgnoreCase(costCmp)) sb.append('-');
            else if ("more".equalsIgnoreCase(costCmp)) sb.append('+');
            sb.append(' ');
        }
        if      ( inclForwards && !inclBackups && !inclMonsters) sb.append("Fwds");
        else if (!inclForwards &&  inclBackups && !inclMonsters) sb.append("Bkps");
        else if (!inclForwards && !inclBackups &&  inclMonsters) sb.append("Mons");
        else if ( inclForwards &&  inclBackups &&  inclMonsters) sb.append("cards");
        else {
            if (inclForwards) sb.append("Fwd/");
            if (inclBackups)  sb.append("Bkp/");
            if (inclMonsters) sb.append("Mon/");
            if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '/') sb.deleteCharAt(sb.length() - 1);
        }
        if (!exceptCardName.isEmpty()) sb.append(" excl.").append(exceptCardName);
        if (powerBonus != 0) sb.append(" +").append(powerBonus);
        if (!grantedTraits.isEmpty()) sb.append(' ').append(grantedTraits);
        if (minBzSize > 0) sb.append(" ifBZ>=").append(minBzSize);
        if (bzFilterCardName != null) sb.append(" ifBZ(CN:").append(bzFilterCardName).append(')');
        if (minBzFilterCount > 0) {
            sb.append(" ifBZ(");
            if (bzFilterJob != null) sb.append("Job:").append(bzFilterJob).append(' ');
            if (bzFilterFwds) sb.append("Fwd");
            sb.append(">=").append(minBzFilterCount).append(')');
        }
        if (yourTurnOnly) sb.append(" yourTurnOnly");
        if (oppTurnOnly)  sb.append(" oppTurnOnly");
        if (minDistinctElements > 0) sb.append(" ifDistinctElem>=").append(minDistinctElements);
        if (exBurstDmgPerGroup > 0) sb.append(" +").append(exBurstDmgPerGroup).append("/").append(exBurstDmgGroupSize).append("EXBurstDmg");
        if (minDamageThreshold > 0) sb.append(" ifDmg>=").append(minDamageThreshold);
        if (maxDamageThreshold > 0) sb.append(" ifDmg<").append(maxDamageThreshold);
        if (!traitFilter.isEmpty()) sb.append(" with").append(traitFilter);
        if (attackingOnly) sb.append(" whileAttacking");
        if (basePowerSet > 0) sb.append(" base=").append(basePowerSet);
        return sb.toString();
    }

    /**
     * Returns {@code true} if this grant applies to {@code card}, resolving a {@link #traitFilter}
     * against the card's printed traits. Runtime callers should prefer
     * {@link #appliesToCard(CardData, Set)}, which sees granted and stripped traits too.
     * Does not check which side of the field the card is on — callers must ensure
     * the card and the grant source belong to the same player when {@link #affectsOpponent} is
     * {@code false}, or to opposing players when {@code true}. Nor does it check
     * {@link #attackingOnly}, which needs the board's declared attackers: {@code MainWindow} gates
     * on that separately, so a grant that passes this test may still be dormant.
     */
    public boolean appliesToCard(CardData card, boolean jobsStripped) {
        return appliesToCard(card, card.traits(), jobsStripped);
    }

    /**
     * As {@link #appliesToCard(CardData)}, but resolves a {@link #traitFilter} against
     * {@code currentTraits} — the traits the card has right now — instead of its printed set.
     * A trait-filtered grant ("The Forwards with Brave … you control gain +3000 power." —
     * Ash 21-062H) must see Brave an effect granted on the field, and must stop seeing printed
     * Brave that an effect has stripped, so the caller supplies the resolved set rather than a
     * delta over the printed one.
     */
    public boolean appliesToCard(CardData card, Set<CardData.Trait> currentTraits,
            boolean jobsStripped) {
        if (!exceptCardName.isEmpty() && CardFilters.meetsCardNameFilter(card, exceptCardName)) return false;
        if (!traitFilter.isEmpty() && Collections.disjoint(traitFilter, currentTraits)) return false;
        boolean typeOk = (inclForwards && card.isForward())
                      || (inclBackups  && card.isBackup())
                      || (inclMonsters && (card.isMonster() || card.alsoCountsAsMonster()));
        if (!typeOk) return false;
        if (costFilter >= 0) {
            int c = card.cost();
            if      (costCmp == null)                    { if (c != costFilter) return false; }
            else if ("less".equalsIgnoreCase(costCmp))   { if (c >  costFilter) return false; }
            else                                         { if (c <  costFilter) return false; }
        }
        // "of an Element other than X" — a Multi-Element card carrying X is spared, since it does
        // have that Element (Tchakka 18-092C spares a Water/Fire Forward, not just a mono-Water one).
        if (!CardFilters.meetsElementExclusion(card, excludeElement)) return false;
        // Job and card name are alternatives when a printing supplies both — "The Job Chocobo
        // Forwards and Card Name Chocobo Forwards you control gain +3000 power" (Billy 29-048C)
        // means either, and no card is both, so ANDing them granted nothing. The same rule the
        // board selections and the deck search use. A filter left null is "any", so the
        // single-filter and no-filter cases stay a plain conjunction.
        boolean jobOk  = CardFilters.meetsJobFilter(card, jobFilter, jobsStripped);
        boolean nameOk = CardFilters.meetsCardNameFilter(card, inclCardName);
        boolean identityOk = jobFilter != null && inclCardName != null ? jobOk || nameOk : jobOk && nameOk;
        return CardFilters.meetsElementFilter(card, elementFilter)
            && CardFilters.meetsCategoryFilter(card, categoryFilter)
            && identityOk;
    }
}
