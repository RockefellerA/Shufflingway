package shufflingway;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

public class AutoAbilityParsingTest {

    /**
     * How many sampled cards each of the three buckets prints. Named rather than inline for the
     * reason {@code FieldAbilityParsingTest} names its own: the number appears in both halves of
     * the reservoir sampler, and the two drifting apart silently biases the sample.
     */
    private static final int SAMPLE_SIZE = 4;

    // -------------------------------------------------------------------------
    // Per-card coverage (mirrors reportCardParsingCoverage in CardParsingTest)
    // -------------------------------------------------------------------------

    @Test
    void reportAutoAbilityParsingCoverage() throws Exception {
        File dbFile = new File("shufflingway.db");
        if (!dbFile.exists()) {
            System.out.println("[AutoAbilityParsingTest] shufflingway.db not found — skipping.");
            return;
        }

        int totalCards      = 0;
        int noAbilities     = 0;
        int fullyParsed     = 0;
        int partiallyParsed = 0;
        int noneParsed      = 0;

        List<String> examplesFully   = new ArrayList<>();
        List<String> examplesPartial = new ArrayList<>();
        List<String> examplesNone    = new ArrayList<>();
        java.util.Random rng         = new java.util.Random();

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement  stmt = conn.createStatement();
             ResultSet  rs   = stmt.executeQuery(
                     "SELECT name_en, element, cost, power, type_en, ex_burst, multicard, " +
                     "limit_break, lb_cost, image_url, text_en, job_en, category_1, category_2 " +
                     "FROM cards ORDER BY serial")) {

            while (rs.next()) {
                totalCards++;
                String textEn = rs.getString("text_en");
                if (textEn == null || textEn.isBlank()) { noAbilities++; continue; }

                List<AutoAbility> abilities = CardData.parseAutoAbilities(textEn);
                if (abilities.isEmpty()) { noAbilities++; continue; }

                CardData source = buildSource(rs, textEn);

                int parsed = 0;
                for (AutoAbility fa : abilities)
                    if ("OK".equals(abilityStatus(fa, source))) parsed++;

                String example = formatCardExample(source.name(), abilities, source);
                if (parsed == abilities.size()) {
                    fullyParsed++;
                    reservoirAdd(examplesFully, example, fullyParsed, rng);
                } else if (parsed > 0) {
                    partiallyParsed++;
                    reservoirAdd(examplesPartial, example, partiallyParsed, rng);
                } else {
                    noneParsed++;
                    reservoirAdd(examplesNone, example, noneParsed, rng);
                }
            }
        }

        int withAbilities = fullyParsed + partiallyParsed + noneParsed;
        System.out.printf("%n=== Auto Ability Parsing Coverage (per card) ===%n");
        System.out.printf("Total cards:            %5d%n", totalCards);
        System.out.printf("No auto abilities:     %5d%n", noAbilities);
        System.out.printf("With auto abilities:   %5d%n", withAbilities);
        System.out.printf("  Fully parsed:         %5d  (%.1f%%)%n", fullyParsed,     pct(fullyParsed,     withAbilities));
        System.out.printf("  Partially parsed:     %5d  (%.1f%%)%n", partiallyParsed, pct(partiallyParsed, withAbilities));
        System.out.printf("  Nothing parsed:       %5d  (%.1f%%)%n", noneParsed,      pct(noneParsed,      withAbilities));
        System.out.println();
        printExamples("Fully parsed",    examplesFully);
        printExamples("Partially parsed", examplesPartial);
        printExamples("Unrecognized",     examplesNone);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static CardData buildSource(ResultSet rs, String textEn) throws Exception {
        return new CardData(
                rs.getString("image_url"),
                rs.getString("name_en"),
                rs.getString("element"),
                rs.getInt("cost"),
                rs.getInt("power"),
                rs.getString("type_en"),
                rs.getInt("limit_break") != 0,
                rs.getObject("lb_cost") != null ? rs.getInt("lb_cost") : 0,
                rs.getInt("ex_burst") != 0,
                rs.getInt("multicard") != 0,
                CardData.parseTraits(textEn, rs.getString("name_en")),
                CardData.parseWarpValue(textEn),
                CardData.parseWarpCost(textEn),
                CardData.parsePrimingTarget(textEn),
                CardData.parsePrimingCost(textEn),
                CardData.parseActionAbilities(textEn),
                CardData.parseAutoAbilities(textEn),
                CardData.parseFieldAbilities(textEn, rs.getString("type_en")),
                CardData.parseIfControlBoosts(textEn, rs.getString("type_en")),
                CardData.parseFieldPowerGrants(textEn, rs.getString("type_en")),
                CardData.parseScalingSelfPowerBoosts(textEn, rs.getString("type_en"), rs.getString("name_en")),
                CardData.parseFieldCostReductions(textEn, rs.getString("type_en")),
                CardData.parseSelfCostModifiers(textEn),
                CardData.parseFieldPrimingAnyElements(textEn, rs.getString("type_en")),
                CardData.parseFieldPartyAnyElements(textEn, rs.getString("type_en")),
                CardData.parseWarpCostAnyElement(textEn),
                CardData.parseCanFormPartyAnyElement(textEn),
                CardData.parseFieldCannotBeBlockedByCost(textEn, rs.getString("name_en")),
                CardData.parseCannotBeBlockedByHigherPower(textEn, rs.getString("name_en")),
                CardData.parseCannotBlockAtAll(textEn, rs.getString("name_en")),
                CardData.parseCannotBlockHigherPower(textEn, rs.getString("name_en")),
                CardData.parseCannotBlockParty(textEn, rs.getString("name_en")),
                CardData.parseCannotAttackOrBlock(textEn, rs.getString("name_en")),
                CardData.parseMaxAttacksPerTurn(textEn, rs.getString("name_en")),
                rs.getString("job_en"),
                rs.getString("category_1"), rs.getString("category_2"), textEn);
    }

    private static String formatCardExample(String name, List<AutoAbility> abilities, CardData source) {
        StringBuilder sb = new StringBuilder();
        sb.append("  Card: ").append(name).append('\n');
        for (AutoAbility fa : abilities) {
            String desc = ActionResolver.fullDescription(fa.effectText(), source);
            sb.append("  [").append(abilityStatus(fa, source)).append("] ")
              .append(autoAbilityText(fa, source)).append('\n');
            sb.append("       [").append(fa.trigger()).append("] ")
              .append(desc != null ? desc : "(none)").append('\n');
        }
        return sb.toString();
    }

    /**
     * How completely this ability is covered, as a two-character tag:
     * <ul>
     *   <li>{@code OK} — every layer of the effect is accounted for.</li>
     *   <li>{@code ??} — the effect resolves, but at least one layer of it has no implementation.
     *       {@link ActionResolver#parse} hands back a runnable effect as soon as the <em>primary</em>
     *       pattern matches, so a card whose followup is unimplemented still "parses": Scholar
     *       15-065C chooses a card in your Break Zone and then has no idea what to do with it.
     *       {@link ActionResolver#fullDescription} marks such a layer with {@code "?"}, and only
     *       {@code OK} counts toward the fully-parsed tally — a half-recognised card is not parsed.</li>
     *   <li>{@code --} — no pattern matches at all.</li>
     * </ul>
     *
     * <p>A {@code null} description is deliberately <em>not</em> treated as a gap. It means
     * {@code fullDescription}'s own dispatch has no entry for a route {@code parse} does handle —
     * a blind spot in this report rather than a missing effect, and demoting on it would understate
     * coverage instead of overstating it.
     */
    private static String abilityStatus(AutoAbility fa, CardData source) {
        // An ability AutoAbilityTriggers dispatches itself never reaches ActionResolver.parse, so
        // asking parse() alone reported working cards as unimplemented. The predicate mirrors what
        // each executor requires, so this cannot claim one the engine would reject.
        if (AutoAbilityTriggers.dispatchedByTriggers(fa, source)) return "OK";
        if (ActionResolver.parse(fa.effectText(), source) == null) return "--";
        String desc = ActionResolver.fullDescription(fa.effectText(), source);
        return (desc != null && desc.contains("?")) ? "??" : "OK";
    }

    /**
     * Whether {@code fa} is accounted for at all — the question the characterization golden file
     * asks about each auto ability, and the same one {@code FieldAbilityParsingTest} answers for
     * field abilities.
     */
    static boolean isAutoAbilityRecognized(AutoAbility fa, CardData source) {
        return AutoAbilityTriggers.dispatchedByTriggers(fa, source)
                || ActionResolver.parse(fa.effectText(), source) != null;
    }

    /** Reconstructs the original trigger line for display. */
    private static String autoAbilityText(AutoAbility fa, CardData source) {
        StringBuilder sb = new StringBuilder();
        // The one firing restriction the card states ahead of its trigger rather than after its
        // effect, so it is restored ahead of it here and the trigger lead-in loses its capital.
        if (fa.opponentTurnOnly()) sb.append("During your opponent's turn, ");
        String phaseTrigger = phaseTriggerDisplayText(fa.trigger());
        if (phaseTrigger != null && fa.triggerCard().isEmpty()) {
            sb.append(phaseTrigger).append(", ");
        } else {
            sb.append(fa.opponentTurnOnly() ? "when " : "When ");
            String trigger = triggerDisplayText(fa.trigger());
            if (fa.warpOnly() && trigger.equals("enters the field"))
                trigger = "enters the field due to Warp";
            else if (fa.castOnly() && trigger.equals("enters the field"))
                trigger = "enters the field due to your cast";
            // "primed into" is stored from the primer's side — triggerCard is the card that primes
            // in, and the target is the card that owns the ability. Naming the target restores the
            // printed wording, "When Barnabas (XVI) primes into Odin (XVI), …".
            else if (fa.trigger().equals("primed into"))
                trigger = "primes into " + source.name();
            sb.append(fa.triggerCard()).append(' ').append(trigger).append(", ");
        }
        if (fa.castPaymentMinElements() > 0)
            sb.append("if the cost to cast ").append(source.name()).append(" was paid with CP of ")
              .append(fa.castPaymentMinElements()).append(" or more different Elements, ");
        if (fa.youMay())       sb.append("you may ");
        else if (fa.opponentMay()) sb.append("your opponent may ");
        sb.append(fa.effectText()).append(restrictionText(fa)).append(dmgTag(fa.damageThreshold()));
        return sb.toString();
    }

    /**
     * The trailing firing restrictions {@link CardData#parseAutoAbilities} lifts off the effect
     * text, restored as the sentences the card prints them as. Without them the report shows an
     * ability shorn of its own limits — Mira 11-122H's once-per-turn search reads as an
     * unconditional one, which is a different card.
     *
     * <p>The Break Zone condition is printed two ways — the trailing "This effect will trigger only
     * if [card] is in the Break Zone." and the leading "If you have a Card Name [card] … in your
     * Break Zone," — and {@link AutoAbility} keeps only the card, not which form it came from. Both
     * are rendered in the trailing form; the restriction is the same either way.
     */
    private static String restrictionText(AutoAbility fa) {
        StringBuilder sb = new StringBuilder();
        if (fa.yourTurnOnly() || fa.oncePerTurn()) {
            sb.append(". This effect will trigger only");
            if (fa.yourTurnOnly())                     sb.append(" during your turn");
            if (fa.yourTurnOnly() && fa.oncePerTurn()) sb.append(" and only");
            if (fa.oncePerTurn())                      sb.append(" once per turn");
            sb.append('.');
        }
        if (!fa.rfpConditionCard().isEmpty())
            sb.append(". This effect will trigger only if ").append(fa.rfpConditionCard())
              .append(" is removed from the game.");
        if (!fa.bzConditionCard().isEmpty())
            sb.append(". This effect will trigger only if ").append(fa.bzConditionCard())
              .append(fa.bzConditionJob().isEmpty() ? "" : " with Job " + fa.bzConditionJob())
              .append(" is in the Break Zone.");
        return sb.toString();
    }

    /**
     * Maps a canonical global (card-less) phase/time trigger key back to its original,
     * grammatically correct lead-in phrase. Returns {@code null} for ordinary card-relative
     * triggers, which use the "When [card] [trigger]" form instead.
     */
    private static String phaseTriggerDisplayText(String trigger) {
        return switch (trigger) {
            case "beginning of attack phase"           -> "At the beginning of the Attack Phase during each of your turns";
            case "beginning of attack phase each turn" -> "At the beginning of the Attack Phase during each player's turn";
            case "end of your turn"                     -> "At the end of each of your turns";
            case "beginning of main phase 1"            -> "At the beginning of your Main Phase 1";
            case "beginning of main phase 2"            -> "At the beginning of your Main Phase 2";
            case "beginning of main phase 1 each turn"  -> "Each turn, at the beginning of Main Phase 1";
            case "beginning of opponent's main phase 1" -> "At the beginning of your opponent's Main Phase 1";
            case "end of opponent's turn"                -> "At the end of your opponent's turn";
            case "end of each player's turn"             -> "At the end of each player's turn";
            default                                      -> null;
        };
    }

    /** Maps a canonical trigger key back to a human-readable trigger phrase. */
    private static String triggerDisplayText(String trigger) {
        return switch (trigger) {
            case "attacks"                                  -> "attacks";
            case "attacks or blocks"                        -> "attacks or blocks";
            case "blocks"                                   -> "blocks";
            case "is blocked"                               -> "is blocked";
            case "blocks or is blocked"                     -> "blocks or is blocked";
            case "enters the field"                         -> "enters the field";
            case "enters your field"                        -> "enters your field";
            case "enters your field not from hand"          -> "enters your field other than from your hand";
            case "enters the field or attacks"              -> "enters the field or attacks";
            case "enters the field or put into break zone"  -> "enters the field or is put from the field into the Break Zone";
            case "put into break zone"                      -> "is put from the field into the Break Zone";
            case "other forward attacks"                    -> "attacks";
            case "filtered forward attacks"                 -> "attacks";
            case "party attacks"                            -> "forms a party and attacks";
            case "chosen by opponent's summon"              -> "is chosen by your opponent's Summon or ability";
            case "cast summon"                              -> "casts a Summon";
            case "damage zone"                              -> "is put into the Damage Zone";
            case "leaves the field"                         -> "leaves the field";
            case "warp placed"                              -> "Warp is placed in the field";
            case "deals damage to opponent"                 -> "deals damage to your opponent";
            case "deals damage to forward"                  -> "deals damage to a Forward";
            case "you receive damage"                       -> "you receive damage";
            case "either player receives damage"            -> "either player receives damage";
            case "enters opponent's field"                  -> "enters your opponent's field";
            case "attack"                                   -> "attacks";
            default                                         -> trigger;
        };
    }

    private static String dmgTag(int threshold) {
        return threshold > 0 ? "  [Damage ≥" + threshold + "]" : "";
    }

    private static void reservoirAdd(List<String> reservoir, String item, int seen, java.util.Random rng) {
        if (reservoir.size() < SAMPLE_SIZE) {
            reservoir.add(item);
        } else {
            int j = rng.nextInt(seen);
            if (j < SAMPLE_SIZE) reservoir.set(j, item);
        }
    }

    private static void printExamples(String label, List<String> examples) {
        System.out.printf("--- %s ---%n", label);
        if (examples.isEmpty()) {
            System.out.println("(none)");
        } else {
            for (int i = 0; i < examples.size(); i++) {
                if (i > 0) System.out.println();
                System.out.print(examples.get(i));
            }
        }
        System.out.println();
    }

    private static double pct(int n, int total) {
        return total == 0 ? 0.0 : n * 100.0 / total;
    }

}
