package shufflingway;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class AutoAbilityParsingTest {

    // -------------------------------------------------------------------------
    // Per-card coverage (mirrors reportCardParsingCoverage in CardParsingTest)
    // -------------------------------------------------------------------------

    @Test
    void reportAutoAbilityParsingCoverage() throws Exception {
        File dbFile = new File("fftcg_cards.db");
        if (!dbFile.exists()) {
            System.out.println("[AutoAbilityParsingTest] fftcg_cards.db not found — skipping.");
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
                    if (ActionResolver.parse(fa.effectText(), source) != null) parsed++;

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
        System.out.printf("  None parsed:          %5d  (%.1f%%)%n", noneParsed,      pct(noneParsed,      withAbilities));
        System.out.println();
        printExamples("Fully parsed",    examplesFully);
        printExamples("Partially parsed", examplesPartial);
        printExamples("Unrecognized",     examplesNone);
    }

    // -------------------------------------------------------------------------
    // Per-ability coverage with trigger-type breakdown
    // (mirrors reportFullPatternCoverage in CardParsingTest)
    // -------------------------------------------------------------------------

    @Test
    void reportAutoAbilityPatternCoverage() throws Exception {
        File dbFile = new File("fftcg_cards.db");
        if (!dbFile.exists()) {
            System.out.println("[AutoAbilityParsingTest] fftcg_cards.db not found — skipping.");
            return;
        }

        int totalAbilities = 0;
        int fullyCovered   = 0;
        int notCovered     = 0;

        // Per-trigger-type counters: total and covered
        Map<String, int[]> byTrigger = new LinkedHashMap<>();
        for (String t : new String[]{"attacks", "blocks", "attacks or blocks", "enters the field"})
            byTrigger.put(t, new int[2]); // [0]=total, [1]=covered

        List<String> examplesFull = new ArrayList<>();
        List<String> examplesNone = new ArrayList<>();
        java.util.Random rng      = new java.util.Random();

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement  stmt = conn.createStatement();
             ResultSet  rs   = stmt.executeQuery(
                     "SELECT name_en, element, cost, power, type_en, ex_burst, multicard, " +
                     "limit_break, lb_cost, image_url, text_en, job_en, category_1, category_2 " +
                     "FROM cards ORDER BY serial")) {

            while (rs.next()) {
                String textEn = rs.getString("text_en");
                if (textEn == null || textEn.isBlank()) continue;

                List<AutoAbility> abilities = CardData.parseAutoAbilities(textEn);
                if (abilities.isEmpty()) continue;

                CardData source = buildSource(rs, textEn);

                for (AutoAbility fa : abilities) {
                    totalAbilities++;
                    int[] tBucket = byTrigger.computeIfAbsent(fa.trigger(), k -> new int[2]);
                    tBucket[0]++;

                    boolean ok  = ActionResolver.parse(fa.effectText(), source) != null;
                    String  desc = ActionResolver.fullDescription(fa.effectText(), source);
                    String  entry = formatAbilityEntry(source.name(), fa, desc);

                    if (ok) {
                        fullyCovered++;
                        tBucket[1]++;
                        reservoirAdd(examplesFull, entry, fullyCovered, rng);
                    } else {
                        notCovered++;
                        reservoirAdd(examplesNone, entry, notCovered, rng);
                    }
                }
            }
        }

        System.out.printf("%n=== Auto Ability Pattern Coverage (per ability) ===%n");
        System.out.printf("Total auto abilities:  %5d%n", totalAbilities);
        System.out.printf("  Fully covered:        %5d  (%.1f%%)%n", fullyCovered, pct(fullyCovered, totalAbilities));
        System.out.printf("  Not covered:          %5d  (%.1f%%)%n", notCovered,   pct(notCovered,   totalAbilities));
        System.out.println();
        System.out.printf("--- By trigger type ---%n");
        byTrigger.forEach((trigger, counts) -> {
            if (counts[0] > 0)
                System.out.printf("  %-22s %4d total  %4d covered  (%.1f%%)%n",
                        trigger + ":", counts[0], counts[1], pct(counts[1], counts[0]));
        });
        System.out.println();
        printExamples("Fully covered", examplesFull);
        printExamples("Not covered",   examplesNone);
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
                CardData.parseTraits(textEn),
                CardData.parseWarpValue(textEn),
                CardData.parseWarpCost(textEn),
                CardData.parsePrimingTarget(textEn),
                CardData.parsePrimingCost(textEn),
                CardData.parseActionAbilities(textEn),
                CardData.parseAutoAbilities(textEn),
                rs.getString("job_en"),
                rs.getString("category_1"), rs.getString("category_2"), textEn);
    }

    private static String formatCardExample(String name, List<AutoAbility> abilities, CardData source) {
        StringBuilder sb = new StringBuilder();
        sb.append("  Card: ").append(name).append('\n');
        for (AutoAbility fa : abilities) {
            boolean ok   = ActionResolver.parse(fa.effectText(), source) != null;
            String  desc = ActionResolver.fullDescription(fa.effectText(), source);
            sb.append("  [").append(ok ? "OK" : "--").append("] ").append(autoAbilityText(fa)).append('\n');
            sb.append("       [").append(fa.trigger()).append("] ")
              .append(desc != null ? desc : "(none)").append('\n');
        }
        return sb.toString();
    }

    private static String formatAbilityEntry(String cardName, AutoAbility fa, String desc) {
        String status = desc == null ? "--" : desc.contains("?") ? "??" : "OK";
        return "  Card: " + cardName + '\n' +
               "  [" + status + "] " + autoAbilityText(fa) + '\n' +
               "       [" + fa.trigger() + "] " + (desc != null ? desc : "(none)") + '\n';
    }

    /** Reconstructs the original trigger line for display. */
    private static String autoAbilityText(AutoAbility fa) {
        StringBuilder sb = new StringBuilder("When ");
        sb.append(fa.triggerCard()).append(' ').append(fa.trigger()).append(", ");
        if (fa.youMay())       sb.append("you may ");
        else if (fa.opponentMay()) sb.append("your opponent may ");
        sb.append(fa.effectText());
        return sb.toString();
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
