package shufflingway;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Per-card parsing report for every printing of a given card name.
 *
 * <p>Run with:
 * <pre>  mvn test -Dtest=CardNameReportTest -DcardName="Cecil"</pre>
 *
 * <p>The match is case-insensitive. Exact name matches are preferred; when no card has
 * exactly that name the report falls back to a substring match. For each matching card the
 * report prints the serial (card number), every parsed Action / Auto / Field ability with
 * its recognition status, and a per-card verdict:
 * <ul>
 *   <li><b>FULLY PARSED</b>    — every ability recognized, no partial descriptions.</li>
 *   <li><b>PARTIALLY PARSED</b> — some but not all abilities recognized, or an action
 *       ability parses with an incomplete ("?") description.</li>
 *   <li><b>UNRECOGNIZED</b>    — no ability recognized.</li>
 * </ul>
 *
 * <p>Recognition rules match the coverage tests: Action and Auto abilities are recognized
 * when {@link ActionResolver#parse} accepts their effect text; Field abilities route through
 * {@link FieldAbilityParsingTest#isFieldAbilityRecognized} (ActionResolver plus the
 * continuous-effect parsers).
 */
public class CardNameReportTest {

    private static final Pattern LIMIT_BREAK_PREFIX = Pattern.compile("(?i)^Limit\\s+Break\\s+--\\s+");

    private static final String SELECT_COLUMNS =
            "SELECT serial, name_en, element, cost, power, type_en, ex_burst, multicard, " +
            "limit_break, lb_cost, image_url, text_en, job_en, category_1, category_2 " +
            "FROM cards WHERE ";

    @Test
    void reportParsingForNamedCard() throws Exception {
        String cardName = System.getProperty("cardName");
        if (cardName == null || cardName.isBlank()) {
            System.out.println("[CardNameReportTest] No card name given — skipping.");
            System.out.println("Usage: mvn test -Dtest=CardNameReportTest -DcardName=\"Cecil\"");
            return;
        }
        File dbFile = new File("shufflingway.db");
        if (!dbFile.exists()) {
            System.out.println("[CardNameReportTest] shufflingway.db not found — skipping.");
            return;
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath())) {
            System.out.printf("%n=== Card Report: \"%s\" ===%n", cardName);
            int matches = report(conn, "name_en = ? COLLATE NOCASE ORDER BY serial", cardName);
            if (matches == 0) {
                System.out.println("(no exact name match — falling back to substring match)");
                matches = report(conn, "name_en LIKE ? COLLATE NOCASE ORDER BY serial", "%" + cardName + "%");
            }
            if (matches == 0) System.out.println("No cards found matching \"" + cardName + "\".");
            else              System.out.printf("%d card(s) reported.%n", matches);
        }
    }

    /** Runs one query variant and prints a report per matching card; returns the match count. */
    private static int report(Connection conn, String whereClause, String param) throws Exception {
        int matches = 0;
        try (PreparedStatement ps = conn.prepareStatement(SELECT_COLUMNS + whereClause)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    matches++;
                    reportCard(rs);
                }
            }
        }
        return matches;
    }

    private static void reportCard(ResultSet rs) throws Exception {
        String serial = rs.getString("serial");
        String name   = rs.getString("name_en");
        String typeEn = rs.getString("type_en");
        String textEn = rs.getString("text_en");

        System.out.printf("%n--- [%s] %s  (%s / %s / cost %d) ---%n",
                serial, name, typeEn, rs.getString("element"), rs.getInt("cost"));

        if (textEn == null || textEn.isBlank()) {
            System.out.println("  (no card text)");
            return;
        }

        CardData source = buildSource(rs, textEn);
        List<ActionAbility> actions = source.actionAbilities();
        List<AutoAbility>   autos   = source.autoAbilities();
        List<FieldAbility>  fields  = source.fieldAbilities().stream()
                .filter(fa -> !LIMIT_BREAK_PREFIX.matcher(fa.effectText()).find())
                .toList();

        int total = actions.size() + autos.size() + fields.size();
        if (total == 0) {
            System.out.println("  (no Action/Auto/Field abilities parsed from text)");
            System.out.println("  Text: " + textEn);
            return;
        }

        int recognized = 0;
        boolean hasPartialDesc = false;

        for (ActionAbility ab : actions) {
            boolean ok   = ActionResolver.parse(ab.effectText(), source) != null;
            String  desc = ActionResolver.fullDescription(ab.effectText(), source);
            boolean partial = ok && desc != null && desc.contains("?");
            if (ok) recognized++;
            if (partial) hasPartialDesc = true;
            System.out.printf("  Action [%s] %s%n", ok ? (partial ? "OK?" : "OK ") : "-- ", ab.effectText());
            System.out.println("         " + (desc != null ? desc : "(none)"));
        }

        for (AutoAbility fa : autos) {
            boolean ok   = ActionResolver.parse(fa.effectText(), source) != null;
            String  desc = ActionResolver.fullDescription(fa.effectText(), source);
            if (ok) recognized++;
            System.out.printf("  Auto   [%s] (%s%s) %s%n", ok ? "OK " : "-- ",
                    fa.triggerCard().isEmpty() ? "" : fa.triggerCard() + " ", fa.trigger(), fa.effectText());
            System.out.println("         " + (desc != null ? desc : "(none)"));
        }

        for (FieldAbility fa : fields) {
            boolean ok   = FieldAbilityParsingTest.isFieldAbilityRecognized(fa, source, typeEn);
            String  desc = FieldAbilityParsingTest.describeFieldAbility(fa, source, typeEn);
            if (ok) recognized++;
            System.out.printf("  Field  [%s] %s%n", ok ? "OK " : "-- ", fa.effectText());
            System.out.println("         " + (desc != null ? desc : "(none)"));
        }

        String verdict;
        if (recognized == total && !hasPartialDesc) verdict = "FULLY PARSED";
        else if (recognized > 0)                    verdict = "PARTIALLY PARSED";
        else                                        verdict = "UNRECOGNIZED";
        System.out.printf("  => %s (%d/%d abilities recognized: %d Action, %d Auto, %d Field)%n",
                verdict, recognized, total, actions.size(), autos.size(), fields.size());
    }

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
                CardData.parseCanAttackTwice(textEn, rs.getString("name_en")),
                rs.getString("job_en"),
                rs.getString("category_1"), rs.getString("category_2"), textEn);
    }
}
