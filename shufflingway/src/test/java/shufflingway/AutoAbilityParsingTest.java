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

    /**
     * One card's entry in the report: the text as stored, then what each ability parsed to.
     *
     * <p>The card line is {@link CardData#textEn()} verbatim. It used to be rebuilt from the parsed
     * pieces — "When " + triggerCard + the trigger rendered back into prose + the effect — which
     * read as card text but was not. The rebuild round-trips through the normalised trigger, so
     * Cid (FFBE) 10-052L's stored "When a Forward <b>of your opponent enters the field</b>" came
     * back out as "When a Forward <b>enters your opponent's field</b>": same ruling, different
     * words. Anyone comparing the report against the database — or quoting it, as a reader
     * reasonably would — was comparing against a paraphrase.
     *
     * <p>Nothing is lost by printing the stored text: the restrictions the rebuild existed to
     * restore ({@code oncePerTurn} and its siblings, lifted off the effect by
     * {@link CardData#parseAutoAbilities}) are all present in the stored sentence already.
     */
    private static String formatCardExample(String name, List<AutoAbility> abilities, CardData source) {
        StringBuilder sb = new StringBuilder();
        sb.append("  Card: ").append(name).append('\n');
        sb.append("  Text: ").append(storedTextOneLine(source)).append('\n');
        for (AutoAbility fa : abilities) {
            String desc = ActionResolver.fullDescription(fa.effectText(), source);
            sb.append("  [").append(abilityStatus(fa, source)).append("] ")
              .append(fa.effectText()).append('\n');
            sb.append("       [").append(fa.trigger()).append("] ")
              .append(desc != null ? desc : "(none)").append('\n');
        }
        return sb.toString();
    }

    /** The stored card text on one line, with the source's line breaks shown as a separator. */
    private static String storedTextOneLine(CardData source) {
        String t = source.textEn();
        if (t == null) return "(no stored text)";
        return t.replace("[[br]]", " / ").replaceAll("\\s{2,}", " ").trim();
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
