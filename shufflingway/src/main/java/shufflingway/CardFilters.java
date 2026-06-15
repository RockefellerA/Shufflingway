package shufflingway;

/** Pure static predicates used throughout the targeting and payment systems. */
public final class CardFilters {
    private CardFilters() {}

    // -------------------------------------------------------------------------
    // Card-type matching
    // -------------------------------------------------------------------------

    public static boolean matchesAltBzType(CardData c, String type) {
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "forward"  -> c.isForward();
            case "backup"   -> c.isBackup();
            case "monster"  -> c.isMonster() || c.alsoCountsAsMonster();
            default         -> !c.isSummon(); // "Character" and empty = any non-Summon
        };
    }

    public static boolean matchesDiscardType(CardData c, String cardType) {
        return switch (cardType.toLowerCase()) {
            case "summon"    -> c.isSummon();
            case "forward"   -> c.isForward();
            case "backup"    -> c.isBackup();
            case "monster"   -> c.isMonster() || c.alsoCountsAsMonster();
            case "character" -> !c.isSummon();
            default          -> true;
        };
    }

    public static String discardTypeKey(CardData c) {
        if (c.isSummon())  return "summon";
        if (c.isForward()) return "forward";
        if (c.isBackup())  return "backup";
        if (c.isMonster()) return "monster";
        return "other";
    }

    // -------------------------------------------------------------------------
    // Cost / power constraints
    // -------------------------------------------------------------------------

    public static boolean meetsCostConstraint(int cardCost, int costVal, String costCmp) {
        if (costVal < 0) return true;
        if (costCmp == null) return cardCost == costVal;
        // "or_N[,M,…]" encodes a multi-value exact filter: cost == costVal OR cost == any listed value
        if (costCmp.startsWith("or_")) {
            if (cardCost == costVal) return true;
            for (String v : costCmp.substring(3).split(",")) {
                if (v.isEmpty()) continue;
                if (cardCost == Integer.parseInt(v.trim())) return true;
            }
            return false;
        }
        return switch (costCmp.toLowerCase()) {
            case "less" -> cardCost <= costVal;
            case "more" -> cardCost >= costVal;
            default     -> cardCost == costVal;
        };
    }

    /**
     * Renders {@code costVal}/{@code costCmp} as a human-readable filter suffix, e.g.
     * " of cost 3", " of cost 3 or less", " of cost 2 or 7", " of cost 2, 3, 5 or 7".
     * Returns {@code ""} when {@code costVal < 0}.
     */
    public static String formatCostFilterLabel(int costVal, String costCmp) {
        if (costVal < 0) return "";
        if (costCmp == null) return " of cost " + costVal;
        if (costCmp.startsWith("or_")) {
            String[] parts = costCmp.substring(3).split(",");
            StringBuilder sb = new StringBuilder(" of cost ").append(costVal);
            for (int i = 0; i < parts.length - 1; i++) sb.append(", ").append(parts[i].trim());
            sb.append(" or ").append(parts[parts.length - 1].trim());
            return sb.toString();
        }
        return " of cost " + costVal + " or " + costCmp;
    }

    public static boolean meetsPowerConstraint(int cardPower, int powerVal, String powerCmp) {
        if (powerVal < 0) return true;
        if (powerCmp == null)             return cardPower == powerVal;
        return switch (powerCmp.toLowerCase()) {
            case "less" -> cardPower <= powerVal;
            case "more" -> cardPower >= powerVal;
            default     -> cardPower == powerVal;
        };
    }

    // -------------------------------------------------------------------------
    // Card-attribute filters
    // -------------------------------------------------------------------------

    /** Returns {@code true} if the card's job matches any job in the bar-separated {@code jobFilter}, or if the filter is {@code null}. */
    public static boolean meetsJobFilter(CardData card, String jobFilter) {
        if (jobFilter == null) return true;
        if (card.hasAllJobs()) return true;
        for (String j : jobFilter.split("\\|")) {
            if (card.hasJob(j.trim())) return true;
        }
        return false;
    }

    /**
     * Like {@link #meetsJobFilter(CardData, String)}, but also considers {@code extraJob} as an
     * additional job the card has (e.g., granted permanently at runtime).
     */
    public static boolean meetsJobFilter(CardData card, String jobFilter, String extraJob) {
        if (meetsJobFilter(card, jobFilter)) return true;
        if (extraJob == null || jobFilter == null) return false;
        for (String j : jobFilter.split("\\|"))
            if (extraJob.equalsIgnoreCase(j.trim())) return true;
        return false;
    }

    /**
     * Like {@link #meetsJobFilter(CardData, String)}, but also matches when the card has
     * "has the Jobs of the Forwards you control" and any Forward in {@code controlledForwards}
     * carries the required job.
     */
    public static boolean meetsJobFilter(CardData card, String jobFilter,
            java.util.List<CardData> controlledForwards) {
        if (jobFilter == null) return true;
        if (card.hasAllJobs()) return true;
        if (card.hasJobsOfControlledForwards() && controlledForwards != null) {
            for (CardData fwd : controlledForwards) {
                for (String j : jobFilter.split("\\|")) {
                    if (fwd.hasJob(j.trim())) return true;
                }
            }
        }
        for (String j : jobFilter.split("\\|")) {
            if (card.hasJob(j.trim())) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if the card's primary name or any of its "is also Card Name X"
     * aliases match {@code cardNameFilter} (case-insensitive), or if the filter is {@code null}.
     * Pipe-separated values (e.g. {@code "Ovjang|Mnejing"}) are treated as OR.
     */
    public static boolean meetsCardNameFilter(CardData card, String cardNameFilter) {
        if (cardNameFilter == null) return true;
        for (String name : cardNameFilter.split("\\|")) {
            name = name.trim();
            if (name.equalsIgnoreCase(card.name())) return true;
            for (String alias : card.alsoCardNames())
                if (name.equalsIgnoreCase(alias)) return true;
        }
        return false;
    }

    /** Returns {@code true} if the card belongs to {@code categoryFilter} (case-insensitive contains), or if the filter is {@code null}. */
    public static boolean meetsCategoryFilter(CardData card, String categoryFilter) {
        if (categoryFilter == null) return true;
        String cf = categoryFilter.trim().toLowerCase();
        return card.category1().toLowerCase().contains(cf)
            || card.category2().toLowerCase().contains(cf);
    }

    /** Returns {@code true} if the card contains at least one element from the bar-separated {@code elementFilter}, or if the filter is {@code null}. */
    public static boolean meetsElementFilter(CardData card, String elementFilter) {
        if (elementFilter == null) return true;
        for (String e : elementFilter.split("\\|")) {
            if (card.containsElement(e.trim())) return true;
        }
        return false;
    }

    /**
     * Returns {@code false} if the card contains any element listed in {@code excludeElement}
     * (an "and"-separated list such as {@code "Ice"} or {@code "Ice and Water"}).
     * Returns {@code true} when {@code excludeElement} is {@code null} (no exclusion).
     */
    public static boolean meetsElementExclusion(CardData card, String excludeElement) {
        if (excludeElement == null) return true;
        for (String e : excludeElement.split("(?i)\\s+and\\s+")) {
            if (card.containsElement(e.trim())) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Target-state conditions
    // -------------------------------------------------------------------------

    public static boolean meetsTargetCondition(CardState state, int damage,
            boolean isAttacking, boolean isBlocking, String condition) {
        if (condition == null) return true;
        String lower = condition.toLowerCase();
        // "blocking:Name" and "blocking-job:Job" are handled by meetsBlockingTargetFilter;
        // if they reach here they should pass (the forward loop already applied the filter).
        if (lower.startsWith("blocking:") || lower.startsWith("blocking-job:")) return true;
        return switch (lower) {
            case "active"                   -> state == CardState.ACTIVE;
            case "dull"                     -> state == CardState.DULL;
            case "damaged"                  -> damage > 0;
            case "attacking"                -> isAttacking;
            case "blocking"                 -> isBlocking;
            // Handled inline by caller (requires turn-tracking state not available here)
            case "entered the field this turn" -> false;
            default                         -> true;
        };
    }

    /** Returns true when {@code condition} is a blocking-target filter ("blocking:..." or "blocking-job:..."). */
    public static boolean isBlockingTargetFilter(String condition) {
        if (condition == null) return false;
        String lower = condition.toLowerCase();
        return lower.startsWith("blocking:") || lower.startsWith("blocking-job:");
    }

    /** Returns true when {@code condition} requires checking which turn a card entered the field. */
    public static boolean isEnteredThisTurnCondition(String condition) {
        return "entered the field this turn".equals(condition);
    }

    // -------------------------------------------------------------------------
    // Card-name overlap (uniqueness rule)
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if cards {@code a} and {@code b} share any card name (including
     * "is also Card Name X" aliases), meaning they would trigger the uniqueness rule.
     */
    public static boolean cardNamesOverlap(CardData a, CardData b) {
        if (a.name().equalsIgnoreCase(b.name())) return true;
        for (String alias : a.alsoCardNames())
            if (alias.equalsIgnoreCase(b.name())) return true;
        for (String alias : b.alsoCardNames())
            if (alias.equalsIgnoreCase(a.name())) return true;
        for (String aa : a.alsoCardNames())
            for (String ba : b.alsoCardNames())
                if (aa.equalsIgnoreCase(ba)) return true;
        return false;
    }
}
