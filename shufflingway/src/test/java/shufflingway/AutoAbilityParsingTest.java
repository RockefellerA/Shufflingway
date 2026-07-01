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

    /** Reconstructs the original trigger line for display. */
    private static String autoAbilityText(AutoAbility fa) {
        StringBuilder sb = new StringBuilder("When ");
        String trigger = triggerDisplayText(fa.trigger());
        if (fa.warpOnly() && trigger.equals("enters the field"))
            trigger = "enters the field due to Warp";
        else if (fa.castOnly() && trigger.equals("enters the field"))
            trigger = "enters the field due to your cast";
        sb.append(fa.triggerCard()).append(' ').append(trigger).append(", ");
        if (fa.youMay())       sb.append("you may ");
        else if (fa.opponentMay()) sb.append("your opponent may ");
        sb.append(fa.effectText()).append(dmgTag(fa.damageThreshold()));
        return sb.toString();
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

    // -------------------------------------------------------------------------
    // Per-ability unit tests (no DB required)
    // -------------------------------------------------------------------------

    @Test
    void ephemeralSummonerSearchPutOnTopOfDeck() {
        String effect = "search for 1 Water Summon of cost 4 or less and put it on top of your deck.";
        CardData source = minimalForward("Ephemeral Summoner");
        org.junit.jupiter.api.Assertions.assertNotNull(ActionResolver.parse(effect, source),
                "Ephemeral Summoner ETF search → top of deck should parse");
    }

    @Test
    void lennaPlaceCountersForEachBackup() {
        String effect = "place 1 Arise Counter on Lenna for each Backup you control.";
        CardData source = minimalForward("Lenna");
        org.junit.jupiter.api.Assertions.assertNotNull(ActionResolver.parse(effect, source),
                "Lenna ETF counter placement scaled by Backup count should parse");
    }

    @Test
    void belDatEnterFieldSearch() {
        String effect = "Search for 1 Job Standard Unit Category FFCC Forward and add it to your hand.";
        CardData source = minimalForward("Bel Dat");
        org.junit.jupiter.api.Assertions.assertNotNull(ActionResolver.parse(effect, source),
                "Bel Dat ETF search (Job + Category combined filter) should parse");
    }

    @Test
    void belDatDealsDamageDrawGate() {
        String effect = "if you control a Category FFCC Forward other than Bel Dat, draw 1 card.";
        CardData source = minimalForward("Bel Dat");
        org.junit.jupiter.api.Assertions.assertNotNull(ActionResolver.parse(effect, source),
                "Bel Dat deals-damage gate (Category Forward other than self) should parse");
    }

    @Test
    void golbezExBurstSearchCardOfCost2() {
        String effect = "search for 1 card of cost 2 and add it to your hand.";
        CardData source = minimalForward("Golbez");
        org.junit.jupiter.api.Assertions.assertNotNull(ActionResolver.parse(effect, source),
                "Golbez EX Burst search for 1 card of cost 2 should parse");
    }

    @Test
    void golbezDamage6SearchAndPlayArchfiends() {
        String effect = "search for up to 3 cards of cost 2 and add them to your hand. Then, you may play any number of Job Archfiend from your hand onto the field.";
        CardData source = minimalForward("Golbez");
        org.junit.jupiter.api.Assertions.assertNotNull(ActionResolver.parse(effect, source),
                "Golbez Damage 6 search + play any number of Archfiends should parse");
    }

    @Test
    void stiltzkinSearchForwardOrMonster() {
        String effect = "search for 1 Forward or Monster of cost 1 and add it to your hand.";
        CardData source = minimalForward("Stiltzkin");
        org.junit.jupiter.api.Assertions.assertNotNull(ActionResolver.parse(effect, source),
                "Stiltzkin ETF search for Forward or Monster of cost 1 should parse");
    }

    @Test
    void warriorOfLightSearchToBreakZone() {
        String effect = "search for 2 Job Standard Unit and put them into the Break Zone.";
        CardData source = minimalForward("Warrior of Light");
        org.junit.jupiter.api.Assertions.assertNotNull(ActionResolver.parse(effect, source),
                "Warrior of Light ETF search for 2 Job Standard Unit → Break Zone should parse");
    }

    @Test
    void samuraiAttackScaledAoe() {
        String effect = "deal 1000 damage for each Job Samurai or Card Name Samurai you control to all the Forwards opponent controls.";
        CardData source = minimalForward("Samurai");
        org.junit.jupiter.api.Assertions.assertNotNull(ActionResolver.parse(effect, source),
                "Samurai scaled AoE damage to all opponent Forwards should parse");
    }

    private static CardData minimalForward(String name) {
        String text = "";
        return new CardData(
                null, name, "Wind", 4, 7000, "Forward",
                false, 0, false, false,
                CardData.parseTraits(text),
                CardData.parseWarpValue(text),
                CardData.parseWarpCost(text),
                CardData.parsePrimingTarget(text),
                CardData.parsePrimingCost(text),
                CardData.parseActionAbilities(text),
                CardData.parseAutoAbilities(text),
                CardData.parseFieldAbilities(text, "Forward"),
                CardData.parseIfControlBoosts(text, "Forward"),
                CardData.parseFieldPowerGrants(text, "Forward"),
                CardData.parseScalingSelfPowerBoosts(text, "Forward", name),
                CardData.parseFieldCostReductions(text, "Forward"),
                CardData.parseSelfCostModifiers(text),
                CardData.parseFieldPrimingAnyElements(text, "Forward"),
                CardData.parseFieldPartyAnyElements(text, "Forward"),
                CardData.parseWarpCostAnyElement(text),
                CardData.parseCanFormPartyAnyElement(text),
                CardData.parseFieldCannotBeBlockedByCost(text, name),
                CardData.parseCannotBeBlockedByHigherPower(text, name),
                CardData.parseCannotBlockAtAll(text, name),
                CardData.parseCannotBlockHigherPower(text, name),
                CardData.parseCannotBlockParty(text, name),
                CardData.parseCannotAttackOrBlock(text, name),
                CardData.parseCanAttackTwice(text, name),
                "Standard Unit", "FFCC", null, text);
    }

}
