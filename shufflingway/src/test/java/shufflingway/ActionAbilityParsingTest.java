package shufflingway;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

public class ActionAbilityParsingTest {

    @Test
    void reportActionAbilityParsingCoverage() throws Exception {
        File dbFile = new File("shufflingway.db");
        if (!dbFile.exists()) {
            System.out.println("[ActionAbilityParsingTest] shufflingway.db not found — skipping.");
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

                if (textEn == null || textEn.isBlank()) {
                    noAbilities++;
                    continue;
                }

                List<ActionAbility> abilities = CardData.parseActionAbilities(textEn);
                if (abilities.isEmpty()) {
                    noAbilities++;
                    continue;
                }

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
                        abilities, CardData.parseAutoAbilities(textEn),
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
                        rs.getString("category_1"), rs.getString("category_2"), textEn);

                int parsed = 0;
                boolean hasPartialDesc = false;
                for (ActionAbility ab : abilities) {
                    if (ActionResolver.parse(ab.effectText(), source) != null) parsed++;
                    String desc = ActionResolver.fullDescription(ab.effectText(), source);
                    if (desc != null && desc.contains("?")) hasPartialDesc = true;
                }

                if (parsed == abilities.size() && !hasPartialDesc) {
                    fullyParsed++;
                    reservoirAdd(examplesFully, formatExample(source.name(), abilities, source), fullyParsed, rng);
                } else if (parsed > 0 || hasPartialDesc) {
                    partiallyParsed++;
                    reservoirAdd(examplesPartial, formatExample(source.name(), abilities, source), partiallyParsed, rng);
                } else {
                    noneParsed++;
                    reservoirAdd(examplesNone, formatExample(source.name(), abilities, source), noneParsed, rng);
                }
            }
        }

        int withAbilities = fullyParsed + partiallyParsed + noneParsed;
        System.out.printf("%n=== Action Ability Parsing Coverage ===%n");
        System.out.printf("Total cards:          %5d%n", totalCards);
        System.out.printf("No action abilities:  %5d%n", noAbilities);
        System.out.printf("With action abilities:%5d%n", withAbilities);
        System.out.printf("  Fully parsed:       %5d  (%.1f%%)%n", fullyParsed,      pct(fullyParsed,      withAbilities));
        System.out.printf("  Partially parsed:   %5d  (%.1f%%)%n", partiallyParsed,  pct(partiallyParsed,  withAbilities));
        System.out.printf("  Nothing parsed:     %5d  (%.1f%%)%n", noneParsed,       pct(noneParsed,       withAbilities));
        System.out.println();
        printExamples("Fully parsed",    examplesFully);
        printExamples("Partially parsed", examplesPartial);
        printExamples("Unrecognized",     examplesNone);
    }

    private static String formatExample(String name, List<ActionAbility> abilities, CardData source) {
        StringBuilder sb = new StringBuilder();
        sb.append("  Card: ").append(name).append('\n');
        for (ActionAbility ab : abilities) {
            String desc = ActionResolver.fullDescription(ab.effectText(), source);
            boolean ok = ActionResolver.parse(ab.effectText(), source) != null;
            sb.append("  [").append(ok ? "OK" : "--").append("] ")
              .append(ab.effectText()).append(dmgTag(ab.damageThreshold())).append('\n');
            sb.append("       ").append(desc != null ? desc : "(none)").append(restrictionTags(ab)).append('\n');
        }
        return sb.toString();
    }

    private static String dmgTag(int threshold) {
        return threshold > 0 ? "  [Damage ≥" + threshold + "]" : "";
    }

    private static String restrictionTags(ActionAbility ab) {
        StringBuilder sb = new StringBuilder();
        if (ab.whileCardAttacking() != null)  sb.append("  [While ").append(ab.whileCardAttacking()).append(" attacking]");
        if (ab.whileCardBlocking() != null)   sb.append("  [While ").append(ab.whileCardBlocking()).append(" blocking]");
        if (ab.whilePartyAttacking())         sb.append("  [While party attacking]");
        if (ab.whileCardInHand())             sb.append("  [While in hand]");
        if (ab.sourceInBattle())              sb.append("  [While in battle]");
        if (ab.yourTurnOnly())                sb.append("  [Your turn only]");
        if (ab.oncePerTurn())                 sb.append("  [Once per turn]");
        if (ab.mainPhaseOnly())               sb.append("  [Main phase only]");
        return sb.toString();
    }

    /** Reservoir sampling (k=3): keeps up to 3 uniformly random items seen so far. */
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
