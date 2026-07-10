package shufflingway;

import java.util.List;

/**
 * Describes the optional extra cost for a card, above its normal CP cost.
 * Four variants exist:
 * <ul>
 *   <li>{@link Type#BZ_REMOVE} — remove {@link #count} cards from the Break Zone from the game.
 *       The eligible cards are filtered by {@link #element} (nullable), {@link #forwardOnly},
 *       or an exact {@link #cardName} (nullable).</li>
 *   <li>{@link Type#DISCARD_HAND} — discard {@link #count} cards from hand.</li>
 *   <li>{@link Type#CP_X} — pay 《X》 additional CP (player's choice of amount); {@link #count} is unused.</li>
 *   <li>{@link Type#CP_FIXED} — pay a fixed additional CP amount, e.g. {@code 《Wind》《2》}
 *       ("1 Wind + 2 generic"). {@link #cpElements} holds one entry per required CP
 *       (empty string {@code ""} = generic); {@link #count}/{@link #element} are unused.</li>
 * </ul>
 */
public record ExtraCost(Type type, int count, String element, boolean forwardOnly, String cardName,
        List<String> cpElements) {

    public enum Type { BZ_REMOVE, DISCARD_HAND, CP_X, CP_FIXED }

    public ExtraCost {
        cpElements = cpElements == null ? List.of() : List.copyOf(cpElements);
    }

    // ── Convenience factories ─────────────────────────────────────────────────

    public static ExtraCost bzRemoveElement(int count, String element) {
        return new ExtraCost(Type.BZ_REMOVE, count, element, false, null, List.of());
    }
    public static ExtraCost bzRemoveForward(int count) {
        return new ExtraCost(Type.BZ_REMOVE, count, null, true, null, List.of());
    }
    public static ExtraCost bzRemoveCardName(int count, String cardName) {
        return new ExtraCost(Type.BZ_REMOVE, count, null, false, cardName, List.of());
    }
    public static ExtraCost discardHand(int count) {
        return new ExtraCost(Type.DISCARD_HAND, count, null, false, null, List.of());
    }
    public static ExtraCost cpX() {
        return new ExtraCost(Type.CP_X, 0, null, false, null, List.of());
    }
    public static ExtraCost cpFixed(List<String> cpElements) {
        return new ExtraCost(Type.CP_FIXED, 0, null, false, null, cpElements);
    }

    // ── Descriptions ─────────────────────────────────────────────────────────

    /** Short label for menu items and dialog titles. */
    public String description() {
        return switch (type) {
            case BZ_REMOVE -> {
                String what = cardName != null ? "Card Name " + cardName
                        : forwardOnly ? "Forward" + (count != 1 ? "s" : "")
                        : count + " " + element;
                yield "Remove " + (cardName != null ? count + " " : "") + what + " from BZ";
            }
            case DISCARD_HAND -> "Discard " + count + " card" + (count != 1 ? "s" : "");
            case CP_X         -> "Pay 《X》 extra CP";
            case CP_FIXED     -> {
                List<String> parts = new java.util.ArrayList<>();
                for (String e : cpElements) if (!e.isEmpty()) parts.add(e + " CP");
                long generic = cpElements.stream().filter(String::isEmpty).count();
                if (generic > 0) parts.add(generic + " CP");
                yield "Pay " + String.join(" + ", parts) + " extra";
            }
        };
    }

    // ── Eligibility ───────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when {@code card} satisfies this extra cost's BZ-removal filter.
     * Always returns {@code false} for non-BZ_REMOVE types.
     */
    public boolean matches(CardData card) {
        if (type != Type.BZ_REMOVE) return false;
        if (cardName  != null) return cardName.equalsIgnoreCase(card.name());
        if (forwardOnly)       return card.isForward();
        return card.containsElement(element);
    }
}
