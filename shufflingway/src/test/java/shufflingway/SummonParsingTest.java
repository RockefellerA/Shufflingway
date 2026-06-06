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
                        CardData.parseTraits(textEn),
                        CardData.parseWarpValue(textEn),
                        CardData.parseWarpCost(textEn),
                        CardData.parsePrimingTarget(textEn),
                        CardData.parsePrimingCost(textEn),
                        CardData.parseActionAbilities(textEn),
                        CardData.parseAutoAbilities(textEn),
                        CardData.parseFieldAbilities(textEn, rs.getString("type_en")),
                        CardData.parseIfControlBoosts(textEn, rs.getString("type_en")),
                        CardData.parseFieldPowerGrants(textEn, rs.getString("type_en")),
                        CardData.parseFieldCostReductions(textEn, rs.getString("type_en")),
                        CardData.parseSelfCostModifiers(textEn),
                        CardData.parseFieldPrimingAnyElements(textEn, rs.getString("type_en")),
                        CardData.parseFieldPartyAnyElements(textEn, rs.getString("type_en")),
                        CardData.parseWarpCostAnyElement(textEn),
                        CardData.parseCanFormPartyAnyElement(textEn),
                        rs.getString("job_en"),
                        rs.getString("category_1"),
                        rs.getString("category_2"),
                        textEn);

                String effectText = source.summonEffect();
                if (effectText == null || effectText.isBlank()) {
                    noEffect++;
                    continue;
                }

                boolean parsed = ActionResolver.parse(effectText, source) != null;
                String  desc   = ActionResolver.fullDescription(effectText, source);
                boolean partial = desc != null && desc.contains("?");

                if (parsed && !partial) {
                    fullyParsed++;
                    reservoirAdd(examplesFully, formatExample(source.name(), effectText, desc), fullyParsed, rng);
                } else if (parsed || partial) {
                    partiallyParsed++;
                    reservoirAdd(examplesPartial, formatExample(source.name(), effectText, desc), partiallyParsed, rng);
                } else {
                    noneParsed++;
                    reservoirAdd(examplesNone, formatExample(source.name(), effectText, desc), noneParsed, rng);
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

    private static String formatExample(String name, String effectText, String desc) {
        return "  Card: " + name + "\n" +
               "  Effect: " + effectText + "\n" +
               "  Desc:   " + (desc != null ? desc : "(none)") + "\n";
    }

    private static void reservoirAdd(List<String> reservoir, String item, int seen, java.util.Random rng) {
        if (reservoir.size() < 3) {
            reservoir.add(item);
        } else {
            int j = rng.nextInt(seen);
            if (j < 3) reservoir.set(j, item);
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
