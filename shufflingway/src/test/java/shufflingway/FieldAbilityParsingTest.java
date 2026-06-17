package shufflingway;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

import org.junit.jupiter.api.Test;

public class FieldAbilityParsingTest {

    private static final java.util.regex.Pattern LIMIT_BREAK_PREFIX =
            java.util.regex.Pattern.compile("(?i)^Limit\\s+Break\\s+--\\s+");

    // -------------------------------------------------------------------------
    // Per-card coverage
    // -------------------------------------------------------------------------

    @Test
    void reportFieldAbilityParsingCoverage() throws Exception {
        File dbFile = new File("shufflingway.db");
        if (!dbFile.exists()) {
            System.out.println("[FieldAbilityParsingTest] shufflingway.db not found — skipping.");
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

                String typeEn = rs.getString("type_en");
                List<FieldAbility> abilities = CardData.parseFieldAbilities(textEn, typeEn).stream()
                        .filter(fa -> !LIMIT_BREAK_PREFIX.matcher(fa.effectText()).find())
                        .toList();
                if (abilities.isEmpty()) { noAbilities++; continue; }

                CardData source = buildSource(rs, textEn);

                int parsed = 0;
                for (FieldAbility fa : abilities)
                    if (isFieldAbilityRecognized(fa, source, typeEn)) parsed++;

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
        System.out.printf("%n=== Field Ability Parsing Coverage (per card) ===%n");
        System.out.printf("Total cards:             %5d%n", totalCards);
        System.out.printf("No field abilities:      %5d%n", noAbilities);
        System.out.printf("With field abilities:    %5d%n", withAbilities);
        System.out.printf("  Fully parsed:          %5d  (%.1f%%)%n", fullyParsed,     pct(fullyParsed,     withAbilities));
        System.out.printf("  Partially parsed:      %5d  (%.1f%%)%n", partiallyParsed, pct(partiallyParsed, withAbilities));
        System.out.printf("  Nothing parsed:        %5d  (%.1f%%)%n", noneParsed,      pct(noneParsed,      withAbilities));
        System.out.println();
        printExamples("Fully parsed",     examplesFully);
        printExamples("Partially parsed", examplesPartial);
        printExamples("Unrecognized",     examplesNone);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Continuous static effects ("The Forwards you control gain +N power", "If you control X,
     * Y gains Z") aren't handled by {@link ActionResolver} — they route through
     * {@link CardData#parseFieldPowerGrants} and {@link CardData#parseIfControlBoosts} during
     * card construction. So a field-ability is "recognized" if any of those three parsers
     * accept its text.
     */
    private static boolean isFieldAbilityRecognized(FieldAbility fa, CardData source, String typeEn) {
        if (ActionResolver.parse(fa.effectText(), source) != null) return true;
        if (!CardData.parseFieldPowerGrants(fa.effectText(), typeEn).isEmpty()) return true;
        if (!CardData.parseIfControlBoosts(fa.effectText(), typeEn).isEmpty()) return true;
        if (CardData.SELF_LIGHT_DARK_PLAY_EXCEPTION_PATTERN.matcher(fa.effectText()).matches()) return true;
        if (CardData.MULTI_LIGHT_DARK_PLAY_PATTERN.matcher(fa.effectText()).matches()) return true;
        if (CardData.MULTI_NAME_PLAY_PATTERN.matcher(fa.effectText()).matches()) return true;
        if (AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_FIELD_DAMAGE_MODIFIER.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_PARTY_DAMAGE_PROTECTION.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_NULLIFY_SUMMON_DAMAGE.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_NULLIFY_ABILITY_DAMAGE.matcher(fa.effectText()).find()) return true;
        return AutoAbilityTriggers.FA_REDUCE_ABILITY_DAMAGE.matcher(fa.effectText()).find();
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
                rs.getString("job_en"),
                rs.getString("category_1"), rs.getString("category_2"), textEn);
    }

    private static String formatCardExample(String name, List<FieldAbility> abilities, CardData source) {
        StringBuilder sb = new StringBuilder();
        sb.append("  Card: ").append(name).append('\n');
        String typeEn = source.type();
        for (FieldAbility fa : abilities) {
            boolean ok   = isFieldAbilityRecognized(fa, source, typeEn);
            String  desc = describeFieldAbility(fa, source, typeEn);
            sb.append("  [").append(ok ? "OK" : "--").append("] ")
              .append(fa.effectText()).append(dmgTag(fa.damageThreshold())).append('\n');
            sb.append("       ").append(desc != null ? desc : "(none)").append('\n');
        }
        return sb.toString();
    }

    private static String describeFieldAbility(FieldAbility fa, CardData source, String typeEn) {
        String desc = ActionResolver.fullDescription(fa.effectText(), source);
        if (desc != null) return desc;
        List<FieldPowerGrant> grants = CardData.parseFieldPowerGrants(fa.effectText(), typeEn);
        if (!grants.isEmpty()) return "FieldPowerGrant " + grants;
        List<IfControlBoost> boosts = CardData.parseIfControlBoosts(fa.effectText(), typeEn);
        if (!boosts.isEmpty()) return "IfControlBoost " + boosts;
        Matcher m;
        m = CardData.SELF_LIGHT_DARK_PLAY_EXCEPTION_PATTERN.matcher(fa.effectText());
        if (m.matches()) return "SelfPlayException[" + m.group("element") + "]";
        m = CardData.MULTI_LIGHT_DARK_PLAY_PATTERN.matcher(fa.effectText());
        if (m.matches()) return "MultiLightDarkPlay[" + m.group("element") + "]";
        m = CardData.MULTI_NAME_PLAY_PATTERN.matcher(fa.effectText());
        if (m.matches()) return "MultiNamePlay[" + m.group("cardname") + "]";
        m = AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(fa.effectText());
        if (m.find()) {
            String src     = m.group("sourceclause");
            String reduceBy = m.group("reduceby");
            String setsTo   = m.group("setsto");
            return "DmgModifier[" + (src != null ? src.trim() : "any") + ": "
                    + (reduceBy != null ? "reduce " + reduceBy : "becomes " + setsTo) + "]";
        }
        m = AutoAbilityTriggers.FA_FIELD_DAMAGE_MODIFIER.matcher(fa.effectText());
        if (m.find()) {
            String src      = m.group("sourceclause");
            String reduceBy = m.group("reduceby");
            String setsTo   = m.group("setsto");
            String cat      = m.group("category");
            String job      = m.group("job");
            String cost     = m.group("cost");
            String costcmp  = m.group("costcmp");
            String except   = m.group("except1") != null ? m.group("except1") : m.group("except2");
            StringBuilder tag = new StringBuilder("FieldDmgModifier[");
            if (cat  != null) tag.append("Cat.").append(cat).append(' ');
            if (job  != null) tag.append("Job.").append(job).append(' ');
            if (cost != null) tag.append("cost").append(cost).append(costcmp != null ? costcmp : "?").append(' ');
            tag.append("Fwds");
            if (except != null) tag.append(" excl.").append(except.trim());
            tag.append(": ").append(src != null ? src.trim() : "any");
            tag.append(" → ").append(reduceBy != null ? "reduce " + reduceBy : "becomes " + setsTo);
            tag.append(']');
            return tag.toString();
        }
        m = AutoAbilityTriggers.FA_PARTY_DAMAGE_PROTECTION.matcher(fa.effectText());
        if (m.find()) return "PartyDmgProtection[" + m.group("source") + "]";
        m = AutoAbilityTriggers.FA_NULLIFY_SUMMON_DAMAGE.matcher(fa.effectText());
        if (m.find()) return "NullifySummonDmg";
        m = AutoAbilityTriggers.FA_NULLIFY_ABILITY_DAMAGE.matcher(fa.effectText());
        if (m.find()) return "NullifyAbilityDmg";
        m = AutoAbilityTriggers.FA_REDUCE_ABILITY_DAMAGE.matcher(fa.effectText());
        if (m.find()) return "ReduceAbilityDmg[" + m.group("reduction") + "]";
        return null;
    }

    private static String dmgTag(int threshold) {
        return threshold > 0 ? "  [Damage ≥" + threshold + "]" : "";
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
