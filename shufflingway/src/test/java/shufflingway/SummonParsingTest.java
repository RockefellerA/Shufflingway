package shufflingway;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

public class SummonParsingTest {

    @Test
    void reportSummonParsingCoverage() throws Exception {
        File dbFile = new File("shufflingway.db");
        if (!dbFile.exists()) {
            System.out.println("[SummonParsingTest] shufflingway.db not found — skipping.");
            return;
        }

        int totalSummons    = 0;
        int noEffect        = 0;
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
                     "FROM cards WHERE type_en = 'Summon' ORDER BY serial")) {

            while (rs.next()) {
                totalSummons++;
                String textEn = rs.getString("text_en");

                CardData source = new CardData(
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
                        rs.getString("category_1"),
                        rs.getString("category_2"),
                        textEn);

                String effectText = source.summonEffect();
                if (effectText == null || effectText.isBlank()) {
                    noEffect++;
                    continue;
                }

                List<String> branches = resolvedBranches(effectText);
                boolean parsed  = true;
                boolean partial = false;
                StringBuilder descOut = new StringBuilder();
                for (int i = 0; i < branches.size(); i++) {
                    String branch = branches.get(i);
                    String d      = ActionResolver.fullDescription(branch, source);
                    parsed  &= ActionResolver.parse(branch, source) != null;
                    partial |= d != null && d.contains("?");
                    if (branches.size() > 1) {
                        if (i > 0) descOut.append("\n          ");
                        descOut.append(i == 0 ? "[paid]   " : "[unpaid] ");
                    }
                    descOut.append(d != null ? d : "(none)");
                }
                String desc = descOut.toString();

                if (parsed && !partial) {
                    fullyParsed++;
                    reservoirAdd(examplesFully, formatExample(source, effectText, desc), fullyParsed, rng);
                } else if (parsed || partial) {
                    partiallyParsed++;
                    reservoirAdd(examplesPartial, formatExample(source, effectText, desc), partiallyParsed, rng);
                } else {
                    noneParsed++;
                    reservoirAdd(examplesNone, formatExample(source, effectText, desc), noneParsed, rng);
                }
            }
        }

        int withEffect = fullyParsed + partiallyParsed + noneParsed;
        System.out.printf("%n=== Summon Parsing Coverage ===%n");
        System.out.printf("Total Summons:        %5d%n", totalSummons);
        System.out.printf("No effect text:       %5d%n", noEffect);
        System.out.printf("With effect text:     %5d%n", withEffect);
        System.out.printf("  Fully parsed:       %5d  (%.1f%%)%n", fullyParsed,     pct(fullyParsed,     withEffect));
        System.out.printf("  Partially parsed:   %5d  (%.1f%%)%n", partiallyParsed, pct(partiallyParsed, withEffect));
        System.out.printf("  Nothing parsed:     %5d  (%.1f%%)%n", noneParsed,      pct(noneParsed,      withEffect));
        System.out.println();
        printExamples("Fully parsed",     examplesFully);
        printExamples("Partially parsed", examplesPartial);
        printExamples("Unrecognized",     examplesNone);
    }

    /**
     * {@code summonEffect()} strips the "If you cast X, you may … as an extra cost." sentence, so
     * a card built around that cost printed here as a bare effect with no sign of what pays for
     * it — 18-136S Titan read as "Choose 1 Forward. Deal it damage equal to the power of the
     * Forward removed by the extra cost." with the removal itself nowhere on screen. The clause is
     * restored on its own line rather than back into the effect text, which has to stay exactly
     * what the resolver was handed.
     */
    private static String formatExample(CardData source, String effectText, String desc) {
        ExtraCost ec = source.extraCost();
        return "  Card: " + source.name() + "\n" +
               (ec != null ? "  Extra cost: " + ec.description() + "\n" : "") +
               "  Effect: " + effectText + "\n" +
               "  Desc:   " + desc + "\n";
    }

    /**
     * The texts resolution actually hands the resolver. A card carrying an "If you paid the extra
     * cost" clause is never parsed as printed: the Stack entry records whether the cost was paid,
     * and {@code MainWindow} rewrites the text into the paid or the unpaid branch before parsing.
     * The printed wording deliberately leaves the clause unread — a {@code find()} matcher would
     * otherwise claim it and fire on every cast — so scoring the printed text marked fully wired
     * cards partial. 18-045C Dryad read "ChooseCharacter / DamageForEach + ?" for exactly that
     * reason.
     *
     * <p>When stripping the clause leaves nothing (Summoner-style, where the condition is the whole
     * ability) the unpaid branch has no effect to parse, so only the paid branch is scored.
     */
    private static List<String> resolvedBranches(String effectText) {
        String unpaid = ActionResolver.stripExtraCostClause(effectText);
        if (unpaid.equals(effectText.trim())) return List.of(effectText);
        String paid = ActionResolver.applyExtraCostPaid(effectText);
        return unpaid.isBlank() ? List.of(paid) : List.of(paid, unpaid);
    }

    private static void reservoirAdd(List<String> reservoir, String item, int seen, java.util.Random rng) {
        if (reservoir.size() < 5) {
            reservoir.add(item);
        } else {
            int j = rng.nextInt(seen);
            if (j < 5) reservoir.set(j, item);
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
