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

    /**
     * Examples printed per bucket. Every bucket samples the same width: the unrecognized list is
     * the worklist, and the other two are the regression surface, so a wider sample per run
     * surfaces more of both.
     */
    private static final int SAMPLE_SIZE = 5;

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
                    reservoirAdd(examplesFully, example, fullyParsed, rng, SAMPLE_SIZE);
                } else if (parsed > 0) {
                    partiallyParsed++;
                    reservoirAdd(examplesPartial, example, partiallyParsed, rng, SAMPLE_SIZE);
                } else {
                    noneParsed++;
                    reservoirAdd(examplesNone, example, noneParsed, rng, SAMPLE_SIZE);
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
    static boolean isFieldAbilityRecognized(FieldAbility fa, CardData source, String typeEn) {
        if (ActionResolver.parse(fa.effectText(), source) != null) return true;
        if (!CardData.parseFieldPowerGrants(fa.effectText(), typeEn).isEmpty()) return true;
        if (!CardData.parseIfControlBoosts(fa.effectText(), typeEn).isEmpty()) return true;
        // Clive 26-005H. Read by MainWindow.rfgJobSpecialAbilities against the removed-from-game
        // zone; name-checked against the carrier there, so it is name-checked here too.
        if (CardData.parseRfgJobSpecialAbilityGrant(fa.effectText(), source.name()) != null) return true;
        // Sherlotta 8-053H. Read by MainWindow.breakForCpBackupSlots while a CP cost is being paid;
        // self-named there, so it is name-checked here too.
        if (CardData.parseBreakSelfForCpAmount(fa.effectText(), source.name()) > 0) return true;
        if (CardData.SELF_LIGHT_DARK_PLAY_EXCEPTION_PATTERN.matcher(fa.effectText()).matches()) return true;
        if (CardData.MULTI_LIGHT_DARK_PLAY_PATTERN.matcher(fa.effectText()).matches()) return true;
        if (CardData.MULTI_NAME_PLAY_PATTERN.matcher(fa.effectText()).matches()) return true;
        if (CardData.LIGHT_DARK_DISCARD_CP_PATTERN.matcher(fa.effectText()).matches()) return true;
        if (CardData.COUNTER_GRANT_PATTERN.matcher(fa.effectText()).matches()) return true;
        // Palom 23-018R, Porom 23-110R. Read through the same builder CardData.counterGrants uses,
        // so a grant clause that produces no CounterGrant is not claimed as recognised.
        if (CardData.parseCounterThresholdGrant(fa.effectText()) != null) return true;
        // Number 24 20-036H. Both name captures are checked against the carrier exactly as
        // CardData.counterGrants does, so the report cannot claim a cross-card grant as this one.
        if (selfCounterPlacedGrant(fa, source)) return true;
        if (AutoAbilityTriggers.FA_CAST_SELF_FROM_BZ.matcher(fa.effectText().trim()).matches()) return true;
        // Passives the engine reads straight off the card rather than routing through ActionResolver.
        // Each is matched exactly as its AutoAbilityTriggers.has* counterpart does, so the report
        // cannot claim recognition for text the engine would reject.
        if (AutoAbilityTriggers.FA_SELF_CAST_LIMIT.matcher(fa.effectText().trim()).matches()) return true;
        if (AutoAbilityTriggers.FA_BOTH_CAST_LIMIT.matcher(fa.effectText().trim()).matches()) return true;
        if (AutoAbilityTriggers.FA_BZ_TO_RFG_ANY_SITUATION.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_CHARACTER_FIELD_TO_BZ_MAY_RFG.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_OPP_DAMAGED_FORWARD_FIELD_TO_BZ_RFG.matcher(fa.effectText()).find()) return true;
        // Susano, Lord of the Revel 14-011H. Read by MainWindow.addToBreakZone against the
        // per-turn damage record; self-named there, so it is name-checked here too.
        if (AutoAbilityTriggers.hasDamagedBySelfFieldToBzRfg(source)
                && AutoAbilityTriggers.FA_DAMAGED_BY_SELF_FIELD_TO_BZ_RFG
                        .matcher(fa.effectText().trim()).matches()) return true;
        if (AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_FIELD_DAMAGE_MODIFIER.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_FIELD_DAMAGE_EXACT_NULLIFY.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_ELEMENT_FORWARD_DAMAGE_BOOST.matcher(fa.effectText()).find()) return true;
        // Caetuna 6-010H. Read on the CASTER's side by DamageResolver.applyCasterSideElementSummon-
        // DamageBoosts, not on the damaged Forward's — it was live before it was listed here, so this
        // row closes a reporting gap rather than turning a rule on.
        if (AutoAbilityTriggers.FA_ELEMENT_SUMMON_DAMAGE_BOOST.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_PARTY_DAMAGE_PROTECTION.matcher(fa.effectText()).find()) return true;
        // White Mage 3-136C. Read per damage resolution by DamageResolver.selfOrPartyDamageReduction,
        // which name-checks both captures against the carrier exactly as this does.
        if (selfOrPartyDamageReduction(fa, source)) return true;
        // Lehftia 21-020C, Iroha 8-004R. Read on the dealing side by all three damage paths —
        // Summon, ability and combat — each taking the arm that applies to it.
        if (AutoAbilityTriggers.FA_ELEMENT_SUMMON_OR_CHARACTER_DAMAGE_BOOST
                .matcher(fa.effectText()).matches()) return true;
        // Chocobo 5-060C, Paladin 12-102C, Chelinka 20-049R. Read per damage resolution by
        // DamageResolver.partyDamageNullified, which matches it exactly this way.
        if (AutoAbilityTriggers.FA_PARTY_SELF_DAMAGE_NULLIFY.matcher(fa.effectText()).matches()) return true;
        // Cherukiki 19-109H, Zangan 26-070H. Read by canActivateAbility at the point the 《Dull》
        // cost's summoning-sickness check runs, which is the single gate the menu and the AI share.
        if (AutoAbilityTriggers.parseDullCostHasteGrant(fa.effectText()) != null) return true;
        if (AutoAbilityTriggers.FA_NULLIFY_SUMMON_DAMAGE.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_NULLIFY_ABILITY_DAMAGE.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_NULLIFY_OPPONENT_ABILITY_DAMAGE.matcher(fa.effectText()).find()) return true;
        // Matched with matches(), exactly as DamageResolver.abilityDamageUnreducibleByField does.
        if (AutoAbilityTriggers.FA_ABILITY_DAMAGE_TO_OPP_FORWARDS_UNREDUCIBLE
                .matcher(fa.effectText()).matches()) return true;
        if (AutoAbilityTriggers.FA_OPP_FORWARD_POWER_BOOST_SUPPRESSED.matcher(fa.effectText()).find()) return true;
        // Meltigemini 8-128R's unscoped twin, read by MainWindow.oppForwardPowerBoostSuppressedFor
        // from both sides of the table rather than only the opposing one.
        if (AutoAbilityTriggers.hasAllForwardPowerBoostSuppression(source)) return true;
        // Dion 29-106H. Read per Priming attempt by MainWindow.effectivePrimingCost, name-checked
        // against its carrier exactly as that caller reads it.
        if (CardData.parsePrimingCostDiscount(fa.effectText(), source.name()) != null) return true;
        // Black Chocobo 3-054C. Read per block-legality check by
        // MainWindow.hasSelfCannotBeBlockedFieldAbility, which additionally asks the board for a
        // declared party — the name here only says whose presence turns the shield on.
        if (CardData.isSelfPartyCannotBeBlocked(fa.effectText(), source.name())) return true;
        // Setzer 21-031H, Rinoa 21-038R. Read per playable-list refresh by
        // MainWindow.syncRfgRemovedPlayables, name-checked against its carrier as that caller does.
        if (AutoAbilityTriggers.castRemovedPermission(source) != null
                && AutoAbilityTriggers.FA_CAST_REMOVED_BY_SELF
                        .matcher(fa.effectText().trim()).matches()) return true;
        if (AutoAbilityTriggers.FA_OPP_FORWARD_SELF_BOOST_SUPPRESSED.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_OPP_FORWARD_ETF_SUPPRESSED.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_OUTGOING_FLAT_BOOST_VS_COST.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_OUTGOING_FLAT_BOOST.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_INCOMING_REDUCTION_VS_COST.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_DAMAGE_WHILE_DULL_REDUCTION.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_DAMAGE_ZERO_WHILE_DULL.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_NULLIFY_TRAIT_FORWARD_DAMAGE.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_OUTGOING_DAMAGE_DOUBLER.matcher(fa.effectText()).find()) return true;
        // Warrior of Light 1-005R. Read by MainWindow.combatDamageFieldAbilityMult for combat and
        // by GameContextImpl.applyOutgoingFieldAbilityMult for ability damage; both name-check it
        // against the carrier, so it is name-checked here too.
        if (selfDoublerVsCost(fa, source) != null) return true;
        // Bahamut (XVI) 29-115L. Read by MainWindow.cancelFirstOppForwardAuto as an auto ability
        // goes on the Stack; it names no card, so there is nothing to name-check.
        if (AutoAbilityTriggers.FA_CANCEL_FIRST_OPP_FORWARD_AUTO
                .matcher(fa.effectText().trim()).matches()) return true;
        // Wakka 16-138S. Read by MainWindow.specialCostCounterWaiver at ability activation;
        // self-named there, so it is name-checked here too.
        if (CardData.parseSpecialCostCounterWaiver(fa.effectText(), source.name()) != null) return true;
        // Sophie 16-076R. Read by GameContextImpl.applyOutgoingFieldAbilityMult on every ability
        // damage the carrier deals to a Forward — live well before it was listed here, so this row
        // closes a reporting gap rather than turning a rule on. Name-checked exactly as that caller
        // checks it, so a cross-card printing cannot be claimed as this one.
        if (namesItself(AutoAbilityTriggers.FA_DOUBLE_ABILITY_DAMAGE, fa, source)) return true;
        if (AutoAbilityTriggers.FA_RECV_PLAYER_DAMAGE_ACTIVE_DULL_ZERO.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_DISCARD_JOB_TO_CAST.matcher(fa.effectText()).find()) return true;
        // No row for the S-cost substitutions (Braska 16-133S, Rydia 2-094H, Duncan 8-014L,
        // Tifa 26-076H): parseFieldAbilities skips those segments as static card properties, so
        // they never reach this report as a FieldAbility. They are read straight off the card by
        // MainWindow.effectiveSpecialAbilityProxy instead.
        // Read per resolution by isProtectedFromChoice and by selectCharacters' immunity sets;
        // self-named, exactly as the engine checks it.
        if (namesItself(ActionResolverPatterns.STANDALONE_NAMED_CANNOT_BE_CHOSEN_BY_MULTI_ELEMENT_FORWARD,
                fa, source)) return true;
        // Kam'lanaut 5-148H. Recognised here rather than through ActionResolver because it is a
        // passive read per resolution, not an effect: the any-Summon parser used to claim it off
        // its prefix, which is exactly the bug that made it look wired.
        if (namesItself(ActionResolverPatterns.STANDALONE_NAMED_CANNOT_BE_CHOSEN_BY_OWN_ELEMENT,
                fa, source)) return true;
        // Royal Ripeness 5-007H. Read per resolution by isProtectedFromChoice and by
        // selectCharacters' immunity sets, against the resolving card's Elements.
        if (namesItself(ActionResolverPatterns.STANDALONE_NAMED_CANNOT_BE_CHOSEN_BY_ELEMENT,
                fa, source)) return true;
        // Sin 14-045H. Read by canActivateAbility, which is the single gate both the menu and the
        // AI go through, so the lock cannot be reached around.
        if (AutoAbilityTriggers.FA_OPP_FORWARDS_CANNOT_USE_ACTION_ABILITIES
                .matcher(fa.effectText().trim()).matches()) return true;
        // The Emperor 2-147L. Read by canActivateAbility alongside Sin's narrower lock, and matched
        // the same way its AutoAbilityTriggers.hasOppCharacterAbilityLock counterpart matches it.
        if (AutoAbilityTriggers.FA_OPP_CHARACTERS_CANNOT_USE_ABILITIES
                .matcher(fa.effectText().trim()).matches()) return true;
        // The Fiend 20-114L. Read by MainWindow.pushSummonOnStack as each Summon goes on the Stack,
        // which is the single point every casting path funnels through.
        if (AutoAbilityTriggers.FA_CANCEL_OPP_FIRST_SUMMON_EACH_TURN
                .matcher(fa.effectText().trim()).matches()) return true;
        // Kalmia 18-090R. Read by MainWindow.bzCardsProtectedFromOppChoice wherever an effect
        // would choose in a Break Zone. Not self-named like its neighbours — it speaks about a
        // zone rather than about its carrier, so there is no name to check.
        if (ActionResolverPatterns.FA_BZ_CARDS_PROTECTED_FROM_OPP_CHOICE
                .matcher(fa.effectText()).find()) return true;
        // Kimahri 1-103C. Resolved per lookup against the opposing board, so it lives on the card
        // as a query rather than as a parsed effect.
        if (namesItself(CardData.GAINS_OPP_CHARACTER_ELEMENTS_PATTERN, fa, source)) return true;
        // Exdeath 1-122H, Arborous Simulacrum 2-118C, Shadow Lord B-007. Read per damage-zone
        // reveal by MainWindow.exBurstSuppressionCostCap, straight off the source's field
        // abilities — self-named, exactly as that caller checks it.
        if (ActionResolver.exBurstSuppressionMaxCost(fa.effectText(), source.name()) != null) return true;
        if (ActionResolverFieldAbility.tryParseBeginningOfOppMainPhase1FieldAbility(fa.effectText(), source) != null) return true;
        // Vayne 9-022L. Read off the granter at the moment it fires by
        // MainWindow.fireGrantedEndOfTurnForwardAbilities, which walks both fields and hands each
        // grantee its own copy — live well before it was listed here, so this row closes a
        // reporting gap rather than turning a rule on. Names no carrier: the grant speaks about
        // the Forwards on one side of the table, not about the card printing it.
        if (ActionResolverFieldAbility.tryParseForwardAbilityGrant(fa.effectText()) != null) return true;
        if (AutoAbilityTriggers.FA_OPPONENT_MUST_BLOCK.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_OPPONENT_MUST_CHOOSE.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_FIELD_FORWARDS_MUST_BLOCK.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_FIELD_FORWARDS_MUST_ATTACK.matcher(fa.effectText()).find()) return true;
        if (CardData.COUNTER_SCALED_OPP_DEBUFF.matcher(fa.effectText()).matches()) return true;
        if (AutoAbilityTriggers.FA_FRIENDLY_FORWARD_BATTLE_DAMAGE_BOOST.matcher(fa.effectText()).find()) return true;
        // Read by DamageResolver.outgoingDamageToOpponentOverride; it was live well before it was
        // listed here, so this row closes a reporting gap rather than turning a rule on. The name
        // check is what that caller uses to reject the qualified printings, and it is not optional:
        // the pattern's own "card" group happily absorbs the qualifier, so "If Lightning forming a
        // party deals damage…" (26-098L) matches with card="Lightning forming a party".
        if (outgoingDamageToOpponentSetsTo(fa, source) != null) return true;
        // The self-named forms are only recognised when the text names their own carrier.
        if (selfNamedCompulsion(AutoAbilityTriggers.FA_SELF_MUST_BLOCK,  fa, source)) return true;
        if (selfNamedCompulsion(AutoAbilityTriggers.FA_SELF_MUST_ATTACK, fa, source)) return true;
        if (selfNamedCompulsion(AutoAbilityTriggers.FA_SELF_CANNOT_FORM_PARTIES, fa, source)) return true;
        if (selfNamedCompulsion(AutoAbilityTriggers.FA_CANNOT_BE_BLOCKED_BY_MONSTER_FORWARD, fa, source)) return true;
        if (selfNamedCompulsion(AutoAbilityTriggers.FA_SELF_ATTACK_REQUIRES_CONTROL, fa, source)) return true;
        if (AutoAbilityTriggers.FA_ALL_FORWARDS_LOSE_HASTE.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_FORWARDS_CANNOT_GAIN_HASTE.matcher(fa.effectText()).find()) return true;
        // One-sided twins of the two above; both read by FieldGrantCalculator.isHasteSuppressedFor.
        if (AutoAbilityTriggers.FA_OPP_FORWARDS_LOSE_HASTE.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_OPP_FORWARDS_CANNOT_GAIN_HASTE.matcher(fa.effectText()).find()) return true;
        // Titan (XVI) 29-068L. Read off the opposing field by MainWindow.backupCpSuppressed, which
        // gates every CP-source scan and the Backup row each payment dialog offers. Matched the
        // same way that caller matches it.
        if (AutoAbilityTriggers.FA_OPP_BACKUPS_CANNOT_PRODUCE_CP
                .matcher(fa.effectText().trim()).matches()) return true;
        // Gentiana 11-033R. Answered inside MainWindow.lostAbilitiesCards's own membership test,
        // matched exactly as that caller matches it.
        if (AutoAbilityTriggers.FA_OPP_DULL_FORWARDS_LOSE_ABILITIES
                .matcher(fa.effectText().trim()).matches()) return true;
        // Firion 21-099H. A gate over an ordinary self grant: the power, trait and multi-attack
        // halves are each read by the parser that owns them, so recognition asks only that the gate
        // splits and that the remainder is a grant one of them accepts.
        if (oppDullCharsGatedSelfGrant(fa, source)) return true;
        // Lakshmi, Lady of Bliss 14-111R. The hand-size twin of the row above: the gate is stripped
        // per lookup by MainWindow.handSizeGatedFieldAbilities and the remainder published as a
        // field ability, so recognition asks the same two questions that method asks.
        if (minHandSizeGatedSelfGrant(fa, source)) return true;
        // Glaciela Wezette 17-113L. Read by MainWindow.crystalMayPaySpecialCost as a board sweep,
        // so it is not name-checked: the grant covers a Category, not its own carrier.
        if (CardData.parseCrystalPaysSpecialCostCategory(fa.effectText()) != null) return true;
        // Edge 15-045H. Read per damage resolution by DamageResolver, self-named exactly as that
        // caller name-checks it.
        if (selfNamedCompulsion(AutoAbilityTriggers.FA_FIRST_OPP_EFFECT_DAMAGE_ZERO_EACH_TURN,
                fa, source)) return true;
        // Gogo 4-127H. Resolved per query by FieldGrantCalculator against the controller's Break
        // Zone; self-named, and the parser is the one that caller uses.
        if (!CardData.parseBreakZoneTraitGrantCandidates(fa.effectText(), source.name()).isEmpty())
            return true;
        if (!CardData.parseSelfTraitGrant(fa.effectText(), source.name()).isEmpty()) return true;
        if (CardData.parseSelfNonDmgBreakShield(fa.effectText(), source.name())) return true;
        if (CardData.parseSelfNonDmgBreakShieldDirect(fa.effectText(), source.name())) return true;
        // Applied as the printed CANNOT_BE_BROKEN trait rather than through a field-ability path,
        // and honoured by breakTarget for a card in any zone — Backups included.
        if (CardData.parseSelfCannotBeBroken(fa.effectText(), source.name())) return true;
        // Applied as the printed CANNOT_LEAVE_FIELD_BY_OPP trait, which every effect-driven field
        // exit consults — break, remove from game, and return to hand alike.
        if (CardData.parseSelfCannotLeaveFieldByOpp(fa.effectText(), source.name())) return true;
        // Applied by effectiveP1/P2HasTrait, which drops a granted trait the card may not gain.
        if (CardData.parseSelfCannotGainTrait(fa.effectText(), source.name()) != null) return true;
        // Conditional printings, re-evaluated per query by FieldGrantCalculator.
        if (CardData.parseIfControlNonDmgBreakShield(fa.effectText(), source.name()) != null) return true;
        if (CardData.parseFieldNonDmgBreakShieldGrant(fa.effectText()) != null) return true;
        if (selfGrantedBreakShield(fa, source)) return true;
        // Both are self-named: the engine only acts on them when the text names its own carrier.
        if (CardData.parseHandSizeSelfGrant(fa.effectText(), source.name()) != null) return true;
        // "[Self] gains [traits] and \"[quoted ability]\"" behind a Damage N gate — the traits go
        // through FieldGrantCalculator, the multi-attack permission through
        // MainWindow.maxAttacksPerTurn, and the quoted trigger through effectiveAutoAbilities.
        if (CardData.parseSelfGainsQuotedGrant(fa.effectText(), source.name()) != null) return true;
        if (CardData.parseDamageRedirectGrant(fa.effectText(), source.name()) != null) return true;
        if (CardData.parseSelfCannotBeBrokenDuringYourTurn(fa.effectText(), source.name())) return true;
        if (CardData.parseSelfCannotBeBrokenDuringAttackPhase(fa.effectText(), source.name())) return true;
        if (CardData.parseSelfCannotBeBrokenWithCounter(fa.effectText(), source.name()) != null) return true;
        if (CardData.parseIfOpponentHandSizeCannotBeBrokenThreshold(fa.effectText(), source.name()) >= 0) return true;
        if (CardData.TRAIT_ONLY_SEGMENT.matcher(fa.effectText()).matches()) return true;
        if (CardData.parseOpponentForwardsEnterDull(fa.effectText())) return true;
        // Ultimecia 1-152L. Both-sides twin of the row above, read by MainWindow.forwardEntersFieldDull
        // as each Forward is seated; the exception it names is matched by Card Name there.
        if (CardData.parseAllForwardsExceptEnterDull(fa.effectText()) != null) return true;
        // Edea 2-100H. Read per block-legality check by MainWindow.blockBarredByFieldCostLock, which
        // every path to declaring a blocker consults. Names no controller, so it is not name-checked.
        if (CardData.parseCostForwardsCannotBlock(fa.effectText()) != null) return true;
        if (CardData.parseFieldCannotBeBlockedByCost(fa.effectText(), source.name()) != null) return true;
        // Ark Angel MR 8-045R. Read per block-legality check by attackerBlockPowerFiltersExclude,
        // self-named exactly as that caller reads it.
        if (CardData.parseFieldCannotBeBlockedByPower(fa.effectText(), source.name()) != null) return true;
        // Cecil 2-129L prints a damage shield as a rider on a power grant. The grant half is
        // claimed by parseFieldPowerGrants above; this is the half DamageResolver reads.
        if (AutoAbilityTriggers.FA_FIELD_DAMAGE_MODIFIER
                .matcher(CardData.fieldDamageRiderText(fa.effectText())).find()) return true;
        if (CardData.parseCannotBeBlockedByHigherPower(fa.effectText(), source.name())) return true;
        if (CardData.parseCannotBlockAtAll(fa.effectText(), source.name())) return true;
        if (CardData.parseCannotBlockHigherPower(fa.effectText(), source.name())) return true;
        if (CardData.parseCannotBlockParty(fa.effectText(), source.name())) return true;
        if (CardData.parseCannotAttackOrBlock(fa.effectText(), source.name())) return true;
        if (CardData.parseMaxAttacksPerTurn(fa.effectText(), source.name()) > 1) return true;
        if (CardData.parseAttacksPerOwnDamage(fa.effectText(), source.name())) return true;
        if (CardData.isHasJobsOfForwardsAbility(fa.effectText())) return true;
        // Bartz 1-081R. Answered by CardData.hasAllJobs wherever a Job filter is resolved, so it
        // was live long before it was listed here — this row closes a reporting gap rather than
        // turning a rule on.
        if (CardData.isHasAllJobsAbility(fa.effectText())) return true;
        if (CardData.parseIfSelfJobCountTraitGrantThreshold(fa.effectText(), source.name()) >= 0) return true;
        if (CardData.parseIfSelfLbFaceUpCountTraitGrantThreshold(fa.effectText(), source.name()) >= 0) return true;
        if (CardData.parseIfSelfPowerTraitGrantThreshold(fa.effectText(), source.name()) >= 0) return true;
        // Terra 9-029C, the element-free Summon damage boost. Read on the CASTER's side by
        // DamageResolver.applyCasterSideElementSummonDamageBoosts, beside its element-scoped twin.
        if (AutoAbilityTriggers.FA_FRIENDLY_SUMMON_DAMAGE_BOOST.matcher(fa.effectText().trim()).matches()) return true;
        // Warrior of Light 2-145L. The imperative spelling of FA_FIELD_DAMAGE_MODIFIER's reduction,
        // applied alongside it in DamageResolver.applyFieldWideDamageModifiers.
        if (AutoAbilityTriggers.FA_REDUCE_DAMAGE_TO_FILTER.matcher(fa.effectText().trim()).matches()) return true;
        // The Emperor 20-092R. Read by MainWindow.actionAbilityCostIncreaseFor when an ability is paid for.
        if (AutoAbilityTriggers.FA_OPP_ACTION_ABILITY_COST_INCREASE.matcher(fa.effectText().trim()).matches()) return true;
        // Yoran-Oran 29-075H. Read per cancellation attempt by
        // MainWindow.stackEntryProtectedFromCancel, which every cancelling effect funnels
        // through, and matched the same way that caller matches it.
        if (AutoAbilityTriggers.FA_JOB_ABILITIES_CANNOT_BE_CANCELLED
                .matcher(fa.effectText().trim()).matches()) return true;
        // Machina 15-017H. A board-gated self grant; the gate comes off and the remainder is an
        // ordinary quoted grant, read by FieldGrantCalculator and MainWindow.effectiveAutoAbilities.
        if (maxForwardsGatedSelfGrant(fa, source)) return true;
        // The Night Dancer 17-078R. Read at declaration time by MainWindow.effectiveAttackDeclarationLimit.
        if (AutoAbilityTriggers.FA_OPP_ATTACKS_LIMITED_BY_OWN_BACKUPS.matcher(fa.effectText().trim()).matches()) return true;
        // Lenna 18-100L, Ultimecia 22-073L, and the Summons-only Terra 23-011L. Both read by
        // MainWindow.bzCardProtectedFromOppRfg; Terra's was live but unlisted, so that row closes
        // a reporting gap rather than turning a rule on.
        if (ActionResolverPatterns.FA_BZ_CARDS_PROTECTED_FROM_OPP_RFG
                .matcher(fa.effectText()).find()) return true;
        if (ActionResolverPatterns.FA_BZ_SUMMONS_PROTECTED_FROM_OPP_RFG
                .matcher(fa.effectText()).find()) return true;
        return CardData.isBackupCpAbility(fa.effectText());
    }

    /**
     * Whether {@code fa} is a Forward-count-gated self grant naming its own carrier — Machina
     * 15-017H. The gate comes off before the grant is checked, exactly as the runtime does it.
     */
    private static boolean maxForwardsGatedSelfGrant(FieldAbility fa, CardData source) {
        CardData.MaxForwardsGatedGrant g = CardData.parseMaxForwardsGatedGrant(fa.effectText());
        return g != null && CardData.parseSelfGainsQuotedGrant(g.remainder(), source.name()) != null;
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

    /**
     * True when {@code compulsion} matches {@code fa} <em>and</em> the card it names is the card
     * carrying it. The engine applies the same test, so the report cannot claim recognition for a
     * "This Forward must block if possible." grant that no self-named path would act on.
     */
    private static boolean selfNamedCompulsion(java.util.regex.Pattern compulsion, FieldAbility fa,
            CardData source) {
        java.util.regex.Matcher m = compulsion.matcher(fa.effectText());
        return m.find() && m.group("card").trim().equalsIgnoreCase(source.name());
    }

    /**
     * True when {@code fa} is Firion 21-099H's opponent-board-gated self grant: the gate splits off
     * and what remains is a grant the ordinary self-grant parsers read. Checked the way the three
     * readers check it — each strips the gate and hands the remainder on — so the report claims it
     * only when at least one of them would find something to apply.
     */
    /**
     * True when {@code fa} is a hand-size-gated self grant the engine would publish — the gate
     * splits off, and what is left is a quoted self grant naming the carrier.
     *
     * <p>The pair of questions {@code MainWindow.handSizeGatedFieldAbilities} asks, in the same
     * order, so the report cannot claim recognition for a gated sentence that method drops. The
     * traits/power spellings behind this gate belong to {@link CardData#parseIfControlBoosts} and
     * are claimed by that row instead.
     */
    /**
     * The description for a self-named "deals damage to your opponent, the damage becomes N
     * instead" printing, or {@code null} when {@code fa} is not one of {@code source}'s.
     *
     * <p>The name check is what rejects a qualifier the engine does not read — the pattern's own
     * {@code card} group absorbs one whole. The one qualifier it does read is the party clause
     * (Lightning 26-098L), taken off here exactly as {@code DamageResolver} takes it off, so the
     * report cannot claim a printing that caller would decline.
     */
    private static String outgoingDamageToOpponentSetsTo(FieldAbility fa, CardData source) {
        Matcher m = AutoAbilityTriggers.FA_OUTGOING_DAMAGE_TO_OPPONENT_SETS_TO.matcher(fa.effectText());
        if (!m.find()) return null;
        String subject = m.group("card").trim();
        Matcher partyM = AutoAbilityTriggers.FA_SUBJECT_FORMING_PARTY.matcher(subject);
        boolean party = partyM.matches();
        if (party) subject = partyM.group("name").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        return "OutgoingDmgToOpponentSetsTo[" + m.group("amount") + (party ? " in party" : "") + "]";
    }

    private static boolean minHandSizeGatedSelfGrant(FieldAbility fa, CardData source) {
        CardData.MinHandSizeGatedGrant gate =
                CardData.parseMinHandSizeGatedGrant(fa.effectText());
        return gate != null
                && CardData.parseSelfGainsQuotedGrant(gate.remainder(), source.name()) != null;
    }

    private static boolean oppDullCharsGatedSelfGrant(FieldAbility fa, CardData source) {
        CardData.OppDullCharsGatedGrant gate =
                CardData.parseOppDullCharsGatedGrant(fa.effectText());
        if (gate == null) return false;
        String rest = gate.remainder();
        if (CardData.parseSelfPowerGrant(rest, source.name()) != 0) return true;
        if (!CardData.parseSelfTraitGrant(rest, source.name()).isEmpty()) return true;
        CardData.SelfGainsQuotedGrant q = CardData.parseSelfGainsQuotedGrant(rest, source.name());
        return q != null && (q.maxAttacks() > 1 || !q.traits().isEmpty());
    }

    /**
     * True when {@code fa} is Number 24's self-named counter grant and both of its name captures are
     * the carrier — the same pair of checks {@link CardData#counterGrants()} makes before turning it
     * into a {@link CounterGrant}.
     */
    /**
     * The matcher for {@code fa} when it is the carrier's own cost-gated damage doubler ("If
     * [self] deals damage to a Forward of cost N or more, double the damage instead." — Warrior of
     * Light 1-005R), or {@code null} when it is not one. Name-checked against the carrier exactly
     * as both engine readers are.
     */
    private static Matcher selfDoublerVsCost(FieldAbility fa, CardData source) {
        Matcher m = AutoAbilityTriggers.FA_DOUBLE_DAMAGE_VS_COST_THRESHOLD.matcher(fa.effectText());
        if (!m.find() || !m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return m;
    }

    private static boolean selfCounterPlacedGrant(FieldAbility fa, CardData source) {
        Matcher m = CardData.SELF_COUNTER_PLACED_GAINS_PATTERN.matcher(fa.effectText());
        return m.matches()
                && m.group("placed").trim().equalsIgnoreCase(source.name())
                && m.group("subject").trim().equalsIgnoreCase(source.name());
    }

    /**
     * True when {@code fa} is a break shield the carrier grants itself in quotes — the shape
     * {@link FieldGrantCalculator} unwraps before reading. The unwrap is name-checked there, so it
     * is name-checked here too.
     */
    private static boolean selfGrantedBreakShield(FieldAbility fa, CardData source) {
        String inner = CardData.selfGrantedFieldAbility(fa.effectText(), source.name());
        return inner != null && CardData.parseFieldNonDmgBreakShieldGrant(inner) != null;
    }

    /**
     * True when {@code fa} is White Mage 3-136C's self-or-party damage reduction and both of its
     * name captures are the carrier — the same pair of checks
     * {@code DamageResolver.selfOrPartyDamageReduction} makes before applying it.
     */
    private static boolean selfOrPartyDamageReduction(FieldAbility fa, CardData source) {
        Matcher m = AutoAbilityTriggers.FA_SELF_OR_PARTY_DAMAGE_REDUCTION.matcher(fa.effectText());
        return m.matches()
                && m.group("card").trim().equalsIgnoreCase(source.name())
                && m.group("partner").trim().equalsIgnoreCase(source.name());
    }

    /** {@link #selfNamedCompulsion} for the patterns that call their card-name group {@code name}. */
    private static boolean namesItself(java.util.regex.Pattern p, FieldAbility fa, CardData source) {
        Matcher m = p.matcher(fa.effectText());
        return m.find() && m.group("name").trim().equalsIgnoreCase(source.name());
    }

    static String describeFieldAbility(FieldAbility fa, CardData source, String typeEn) {
        // The continuous-boost parsers come first because they are what actually runs for a field
        // ability: a continuous "If all the Characters you control have Ice Element, X gains +2000
        // power" is a standing modifier resolved by IfControlBoost, not a one-shot ActionResolver
        // effect. Asking ActionResolver first meant that whenever its chains happened to describe
        // the text — which is now the case for that wording — the record showed a gate description
        // and the boost data the engine uses went unrecorded.
        List<FieldPowerGrant> grants = CardData.parseFieldPowerGrants(fa.effectText(), typeEn);
        if (!grants.isEmpty()) return "FieldPowerGrant " + grants;
        List<IfControlBoost> boosts = CardData.parseIfControlBoosts(fa.effectText(), typeEn);
        if (!boosts.isEmpty()) return "IfControlBoost " + boosts;
        String desc = ActionResolver.fullDescription(fa.effectText(), source);
        if (desc != null) return desc;
        ActionResolver.ForwardAbilityGrant fwdGrant =
                ActionResolverFieldAbility.tryParseForwardAbilityGrant(fa.effectText());
        if (fwdGrant != null)
            return "ForwardAbilityGrant[" + (fwdGrant.affectsOpponent() ? "opponent's" : "your")
                    + " Forwards: " + fwdGrant.abilityText() + "]";
        String rfgJob = CardData.parseRfgJobSpecialAbilityGrant(fa.effectText(), source.name());
        if (rfgJob != null) return "RfgJobSpecialAbilityGrant[" + rfgJob + "]";
        int breakCp = CardData.parseBreakSelfForCpAmount(fa.effectText(), source.name());
        if (breakCp > 0) return "BreakSelfForCp[" + breakCp + " any Element]";
        Matcher m;
        m = CardData.SELF_LIGHT_DARK_PLAY_EXCEPTION_PATTERN.matcher(fa.effectText());
        if (m.matches()) return "SelfPlayException[" + m.group("element") + "]";
        m = CardData.MULTI_LIGHT_DARK_PLAY_PATTERN.matcher(fa.effectText());
        if (m.matches()) return "MultiLightDarkPlay[" + m.group("element") + "]";
        m = CardData.MULTI_NAME_PLAY_PATTERN.matcher(fa.effectText());
        if (m.matches()) return "MultiNamePlay[" + m.group("cardname") + "]";
        m = CardData.LIGHT_DARK_DISCARD_CP_PATTERN.matcher(fa.effectText());
        if (m.matches()) {
            return "LightDarkDiscardCp[" + m.group("e1")
                + (m.group("e2") != null ? ", " + m.group("e2") : "") + "]";
        }
        m = CardData.COUNTER_GRANT_PATTERN.matcher(fa.effectText());
        if (m.matches()) {
            String grant = m.group("grant").trim();
            return "CounterGrant[" + m.group("counter").trim() + ": "
                + (grant.startsWith("\"") ? "ability" : grant.replaceAll("[.!]$", "")) + "]";
        }
        CounterGrant tcg = CardData.parseCounterThresholdGrant(fa.effectText());
        if (tcg != null) {
            return "CounterThresholdGrant[" + tcg.minCount() + "+ " + tcg.counterName() + ": "
                + (tcg.grantedAbilityText() != null ? "ability" : "+" + tcg.powerBonus() + " power") + "]";
        }
        if (selfCounterPlacedGrant(fa, source)) {
            m = CardData.SELF_COUNTER_PLACED_GAINS_PATTERN.matcher(fa.effectText());
            m.matches();
            return "SelfCounterGrant[" + m.group("counter").trim() + ": ability]";
        }
        m = AutoAbilityTriggers.FA_CAST_SELF_FROM_BZ.matcher(fa.effectText().trim());
        if (m.matches()) return "CastFromBreakZone[" + m.group("name").trim() + "]";
        if (AutoAbilityTriggers.FA_SELF_CAST_LIMIT.matcher(fa.effectText().trim()).matches())
            return "SelfCastLimit[2 per turn]";
        if (AutoAbilityTriggers.FA_BOTH_CAST_LIMIT.matcher(fa.effectText().trim()).matches())
            return "BothCastLimit[2 per turn]";
        if (AutoAbilityTriggers.FA_BZ_TO_RFG_ANY_SITUATION.matcher(fa.effectText()).find())
            return "BzToRfgAnySituation";
        if (AutoAbilityTriggers.FA_CHARACTER_FIELD_TO_BZ_MAY_RFG.matcher(fa.effectText()).find())
            return "CharacterFieldToBzMayRfg";
        if (AutoAbilityTriggers.FA_OPP_DAMAGED_FORWARD_FIELD_TO_BZ_RFG.matcher(fa.effectText()).find())
            return "OppDamagedFwdFieldToBzRfg";
        if (AutoAbilityTriggers.hasDamagedBySelfFieldToBzRfg(source)
                && AutoAbilityTriggers.FA_DAMAGED_BY_SELF_FIELD_TO_BZ_RFG
                        .matcher(fa.effectText().trim()).matches())
            return "DamagedBySelfFieldToBzRfg";
        m = AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(fa.effectText());
        if (m.find()) {
            String src      = m.group("sourceclause");
            String reduceBy = m.group("reduceby");
            String setsTo   = m.group("setsto");
            String increase = m.group("increaseby");
            String dbl      = m.group("double");
            String half     = m.group("half");
            String effect   = dbl != null ? "×2"
                            : half != null ? "half↑1000"
                            : reduceBy != null ? "reduce " + reduceBy
                            : increase != null ? "+" + increase
                            : "becomes " + setsTo;
            String thresh   = m.group("threshold") == null ? ""
                            : ("less".equalsIgnoreCase(m.group("threshcmp")) ? " ≤" : " ≥") + m.group("threshold");
            return "DmgModifier[" + (src != null ? src.trim() : "any") + thresh + ": " + effect + "]";
        }
        m = AutoAbilityTriggers.FA_ELEMENT_FORWARD_DAMAGE_BOOST.matcher(fa.effectText());
        if (m.find()) {
            // The Job arm carries no Element, so the filter is named by whichever group is present
            // rather than by assuming the Element one is.
            String filter = m.group("element") != null
                    ? m.group("element") + (m.group("category") != null
                            ? " or Category " + m.group("category") : "")
                    : "Job " + m.group("job").trim();
            return "ElementFwdDmgBoost[" + filter + " Fwd +" + m.group("amount") + "]";
        }
        m = AutoAbilityTriggers.FA_ELEMENT_SUMMON_DAMAGE_BOOST.matcher(fa.effectText());
        if (m.find()) return "ElementSummonDmgBoost[" + m.group("element") + " Summon +" + m.group("amount") + "]";
        m = AutoAbilityTriggers.FA_FIELD_DAMAGE_MODIFIER.matcher(fa.effectText());
        if (m.find()) {
            String src      = m.group("sourceclause");
            String reduceBy = m.group("reduceby");
            String setsTo   = m.group("setsto");
            String cat      = m.group("category");
            String job      = AutoAbilityTriggers.fieldDamageModifierJob(m);
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
        if (selfOrPartyDamageReduction(fa, source)) {
            m = AutoAbilityTriggers.FA_SELF_OR_PARTY_DAMAGE_REDUCTION.matcher(fa.effectText());
            m.matches();
            return "SelfOrPartyDmgReduction[-" + m.group("amount") + "]";
        }
        m = AutoAbilityTriggers.FA_ELEMENT_SUMMON_OR_CHARACTER_DAMAGE_BOOST.matcher(fa.effectText());
        if (m.matches()) {
            // Either arm may be present, and either may drop its Element (Ifrit, Lord of the Inferno
            // 14-006R drops both), so each is named off its own marker group rather than off the
            // Element that used to stand in for it. The Character arm may filter by Element,
            // Category, Job or Card Name, and is named through the same label helper the damage
            // paths log with.
            boolean hasSummon = m.group("summonarm") != null;
            String summon = m.group("summonelement");
            String summonLabel = (summon == null ? "" : summon + " ") + "Summon";
            boolean hasChr = AutoAbilityTriggers.characterArmElement(m) != null
                    || m.group("category") != null || m.group("job") != null
                    || m.group("cardname") != null || m.group("anycharacter") != null;
            String chr = AutoAbilityTriggers.characterArmLabel(m);
            String who = hasSummon && hasChr ? summonLabel + " or " + chr
                       : hasSummon ? summonLabel
                       : chr;
            return "ElementSummonOrCharacterDmgBoost[" + who + " +" + m.group("amount") + "]";
        }
        m = AutoAbilityTriggers.FA_NULLIFY_SUMMON_DAMAGE.matcher(fa.effectText());
        if (m.find()) return "NullifySummonDmg";
        m = AutoAbilityTriggers.FA_NULLIFY_ABILITY_DAMAGE.matcher(fa.effectText());
        if (m.find()) return "NullifyAbilityDmg";
        m = AutoAbilityTriggers.FA_NULLIFY_OPPONENT_ABILITY_DAMAGE.matcher(fa.effectText());
        if (m.find()) return "NullifyOpponentAbilityDmg";
        if (AutoAbilityTriggers.FA_ABILITY_DAMAGE_TO_OPP_FORWARDS_UNREDUCIBLE
                .matcher(fa.effectText()).matches()) return "OwnAbilityDmgToOppFwdsUnreducible";
        if (AutoAbilityTriggers.FA_OPP_FORWARD_POWER_BOOST_SUPPRESSED.matcher(fa.effectText()).find())
            return "OppFwdPowerBoostSuppressed";
        if (AutoAbilityTriggers.FA_ALL_FORWARD_POWER_BOOST_SUPPRESSED.matcher(fa.effectText().trim()).matches())
            return "AllFwdPowerBoostSuppressed";
        CardData.PrimingCostDiscount primeDiscount =
                CardData.parsePrimingCostDiscount(fa.effectText(), source.name());
        if (primeDiscount != null)
            return "PrimingCostDiscount[" + primeDiscount.minCharacters() + "+ Characters: "
                    + primeDiscount.cost() + "]";
        if (AutoAbilityTriggers.FA_OPP_FORWARD_SELF_BOOST_SUPPRESSED.matcher(fa.effectText()).find())
            return "OppFwdSelfBoostSuppressed";
        if (AutoAbilityTriggers.FA_OPP_FORWARD_ETF_SUPPRESSED.matcher(fa.effectText()).find())
            return "OppForwardEtfSuppressed";
        m = AutoAbilityTriggers.FA_OUTGOING_FLAT_BOOST_VS_COST.matcher(fa.effectText());
        if (m.find()) return "OutgoingFlatBoostVsCost[cost≥" + m.group("cost") + " +" + m.group("amount") + "]";
        m = AutoAbilityTriggers.FA_OUTGOING_FLAT_BOOST.matcher(fa.effectText());
        if (m.find()) return "OutgoingFlatBoost[" + m.group("card") + " +" + m.group("amount") + "]";
        m = AutoAbilityTriggers.FA_INCOMING_REDUCTION_VS_COST.matcher(fa.effectText());
        if (m.find()) return "IncomingReductionVsCost[cost≥" + m.group("cost") + " -" + m.group("amount") + "]";
        m = AutoAbilityTriggers.FA_DAMAGE_WHILE_DULL_REDUCTION.matcher(fa.effectText());
        if (m.find()) return "DmgWhileDullReduction[-" + m.group("amount") + "]";
        m = AutoAbilityTriggers.FA_DAMAGE_ZERO_WHILE_DULL.matcher(fa.effectText());
        if (m.find()) return "DmgZeroWhileDull[" + m.group("card") + "]";
        m = AutoAbilityTriggers.FA_NULLIFY_TRAIT_FORWARD_DAMAGE.matcher(fa.effectText());
        if (m.find()) {
            String t2 = m.group("trait2");
            return "NullifyTraitFwdDmg[" + m.group("trait1").trim() + (t2 != null ? " or " + t2.trim() : "") + "]";
        }
        if (CardData.isSelfPartyCannotBeBlocked(fa.effectText(), source.name()))
            return "SelfPartyCannotBeBlocked";
        AutoAbilityTriggers.CastRemovedPermission castRemoved =
                AutoAbilityTriggers.castRemovedPermission(source);
        if (castRemoved != null
                && AutoAbilityTriggers.FA_CAST_REMOVED_BY_SELF
                        .matcher(fa.effectText().trim()).matches())
            return "CastRemovedByOwnAbilities[" + castRemoved.cardType()
                    + (castRemoved.oncePerTurn() ? ", once per turn" : "") + "]";
        if (AutoAbilityTriggers.FA_CANCEL_FIRST_OPP_FORWARD_AUTO
                .matcher(fa.effectText().trim()).matches())
            return "CancelFirstOppForwardAuto[each turn]";
        CardData.SpecialCostCounterWaiver waiver =
                CardData.parseSpecialCostCounterWaiver(fa.effectText(), source.name());
        if (waiver != null)
            return "SpecialCostCounterWaiver[" + waiver.count() + " " + waiver.counterName() + "]";
        Matcher costDoubler = selfDoublerVsCost(fa, source);
        if (costDoubler != null)
            return "DmgDoubler[vs Forward of cost " + costDoubler.group("cost") + "+]";
        m = AutoAbilityTriggers.FA_OUTGOING_DAMAGE_DOUBLER.matcher(fa.effectText());
        if (m.find()) return "OutgoingDmgDoubler[to " + m.group("target") + "]";
        if (namesItself(AutoAbilityTriggers.FA_DOUBLE_ABILITY_DAMAGE, fa, source))
            return "DoubleAbilityDmgToForward";
        m = AutoAbilityTriggers.FA_RECV_PLAYER_DAMAGE_ACTIVE_DULL_ZERO.matcher(fa.effectText());
        if (m.find()) return "RecvPlayerDmgActiveDullZero[" + m.group("card") + "]";
        m = AutoAbilityTriggers.FA_OPPONENT_MUST_BLOCK.matcher(fa.effectText());
        if (m.find()) return "OpponentMustBlock[" + m.group("cardname") + "]";
        m = AutoAbilityTriggers.FA_OPPONENT_MUST_CHOOSE.matcher(fa.effectText());
        if (m.find()) return "OpponentMustChoose[" + m.group("cardname")
                + (m.group("summons") != null ? " summons+abilities" : " abilities") + "]";
        m = AutoAbilityTriggers.FA_FIELD_FORWARDS_MUST_BLOCK.matcher(fa.effectText());
        if (m.find()) return "FieldForwardsMustBlock[" + (m.group("scope") != null ? m.group("scope") : "all") + "]";
        m = AutoAbilityTriggers.FA_FIELD_FORWARDS_MUST_ATTACK.matcher(fa.effectText());
        if (m.find()) return "FieldForwardsMustAttack[" + (m.group("scope") != null ? m.group("scope") : "all") + "]";
        if (selfNamedCompulsion(AutoAbilityTriggers.FA_SELF_MUST_BLOCK,  fa, source)) return "SelfMustBlock";
        if (selfNamedCompulsion(AutoAbilityTriggers.FA_SELF_MUST_ATTACK, fa, source)) return "SelfMustAttack";
        if (selfNamedCompulsion(AutoAbilityTriggers.FA_SELF_CANNOT_FORM_PARTIES, fa, source)) return "SelfCannotFormParties";
        if (selfNamedCompulsion(AutoAbilityTriggers.FA_CANNOT_BE_BLOCKED_BY_MONSTER_FORWARD, fa, source))
            return "CannotBeBlockedByMonsterForward";
        m = AutoAbilityTriggers.FA_SELF_ATTACK_REQUIRES_CONTROL.matcher(fa.effectText());
        if (m.find() && m.group("card").trim().equalsIgnoreCase(source.name()))
            return "SelfAttackRequiresControl[" + m.group("count") + "+ Forwards | Job " + m.group("job") + "]";
        m = AutoAbilityTriggers.FA_FRIENDLY_FORWARD_BATTLE_DAMAGE_BOOST.matcher(fa.effectText());
        if (m.find()) return "FriendlyForwardBattleDmgBoost[+" + m.group("amount") + "]";
        String setsTo = outgoingDamageToOpponentSetsTo(fa, source);
        if (setsTo != null) return setsTo;
        m = CardData.COUNTER_SCALED_OPP_DEBUFF.matcher(fa.effectText());
        if (m.matches()) return "CounterScaledOppDebuff[-" + m.group("power") + "/" + m.group("counter") + "]";
        if (AutoAbilityTriggers.FA_ALL_FORWARDS_LOSE_HASTE.matcher(fa.effectText()).find()) return "AllForwardsLoseHaste";
        if (AutoAbilityTriggers.FA_FORWARDS_CANNOT_GAIN_HASTE.matcher(fa.effectText()).find()) return "ForwardsCannotGainHaste";
        if (AutoAbilityTriggers.FA_OPP_FORWARDS_LOSE_HASTE.matcher(fa.effectText()).find()) return "OppForwardsLoseHaste";
        if (AutoAbilityTriggers.FA_OPP_FORWARDS_CANNOT_GAIN_HASTE.matcher(fa.effectText()).find()) return "OppForwardsCannotGainHaste";
        if (AutoAbilityTriggers.FA_OPP_BACKUPS_CANNOT_PRODUCE_CP
                .matcher(fa.effectText().trim()).matches()) return "OppBackupsCannotProduceCp";
        if (AutoAbilityTriggers.FA_OPP_DULL_FORWARDS_LOSE_ABILITIES
                .matcher(fa.effectText().trim()).matches()) return "OppDullForwardsLoseAbilities";
        if (oppDullCharsGatedSelfGrant(fa, source)) {
            CardData.OppDullCharsGatedGrant gate =
                    CardData.parseOppDullCharsGatedGrant(fa.effectText());
            String rest = gate.remainder();
            CardData.SelfGainsQuotedGrant q = CardData.parseSelfGainsQuotedGrant(rest, source.name());
            java.util.EnumSet<CardData.Trait> tr = CardData.parseSelfTraitGrant(rest, source.name());
            if (q != null) tr.addAll(q.traits());
            StringBuilder sb = new StringBuilder("OppDullCharsSelfGrant[≥")
                    .append(gate.minDullCharacters()).append(" dull");
            int pow = CardData.parseSelfPowerGrant(rest, source.name());
            if (pow != 0)        sb.append(": +").append(pow);
            if (!tr.isEmpty())   sb.append(' ').append(tr);
            if (q != null && q.maxAttacks() > 1) sb.append(" atk×").append(q.maxAttacks());
            return sb.append(']').toString();
        }
        if (minHandSizeGatedSelfGrant(fa, source)) {
            CardData.MinHandSizeGatedGrant gate = CardData.parseMinHandSizeGatedGrant(fa.effectText());
            return "HandSizeSelfGrant[≥" + gate.minCards() + " cards: " + gate.remainder() + "]";
        }
        String crystalSCategory = CardData.parseCrystalPaysSpecialCostCategory(fa.effectText());
        if (crystalSCategory != null) return "CrystalPaysSpecialCost[" + crystalSCategory + "]";
        if (selfNamedCompulsion(AutoAbilityTriggers.FA_FIRST_OPP_EFFECT_DAMAGE_ZERO_EACH_TURN, fa, source))
            return "FirstOppEffectDmgZeroEachTurn";
        java.util.EnumSet<CardData.Trait> bzTraits =
                CardData.parseBreakZoneTraitGrantCandidates(fa.effectText(), source.name());
        if (!bzTraits.isEmpty()) return "BreakZoneTraitGrant" + bzTraits;
        if (CardData.isBackupCpAbility(fa.effectText())) return "BackupCpAbility";
        int lbN = CardData.parseIfSelfLbFaceUpCountTraitGrantThreshold(fa.effectText(), source.name());
        if (lbN >= 0) return "LbFaceUpTraitGrant[n≥" + lbN + " " + CardData.parseIfSelfLbFaceUpCountTraitGrantTraits(fa.effectText()) + "]";
        Matcher noCancelM = AutoAbilityTriggers.FA_JOB_ABILITIES_CANNOT_BE_CANCELLED
                .matcher(fa.effectText().trim());
        if (noCancelM.matches()) return "AbilitiesCannotBeCancelled[Job " + noCancelM.group("job").trim() + "]";
        int powN = CardData.parseIfSelfPowerTraitGrantThreshold(fa.effectText(), source.name());
        if (powN >= 0) return "SelfPowerTraitGrant[pow≥" + powN + " " + CardData.parseIfSelfPowerTraitGrantTraits(fa.effectText()) + "]";
        if (CardData.parseSelfNonDmgBreakShieldDirect(fa.effectText(), source.name())) return "SelfNonDmgBreakShield";
        if (CardData.parseAttacksPerOwnDamage(fa.effectText(), source.name()))
            return "MaxAttacks[own damage]";
        if (CardData.parseSelfCannotBeBroken(fa.effectText(), source.name())) return "SelfCannotBeBroken";
        if (CardData.parseSelfCannotLeaveFieldByOpp(fa.effectText(), source.name()))
            return "SelfCannotLeaveFieldByOpp";
        CardData.Trait ungainable = CardData.parseSelfCannotGainTrait(fa.effectText(), source.name());
        if (ungainable != null) return "SelfCannotGain[" + ungainable.displayName() + "]";
        if (namesItself(ActionResolverPatterns.STANDALONE_NAMED_CANNOT_BE_CHOSEN_BY_MULTI_ELEMENT_FORWARD,
                fa, source)) return "CannotBeChosenByMultiElementForwardAbility";
        if (namesItself(ActionResolverPatterns.STANDALONE_NAMED_CANNOT_BE_CHOSEN_BY_OWN_ELEMENT,
                fa, source)) return "CannotBeChosenBySharedElement";
        String immuneElem = ActionResolver.cannotBeChosenByElementFieldAbility(source);
        if (immuneElem != null
                && ActionResolverPatterns.STANDALONE_NAMED_CANNOT_BE_CHOSEN_BY_ELEMENT
                        .matcher(fa.effectText()).find())
            return "CannotBeChosenByElement[" + immuneElem + "]";
        if (AutoAbilityTriggers.FA_OPP_FORWARDS_CANNOT_USE_ACTION_ABILITIES
                .matcher(fa.effectText().trim()).matches())
            return "OppForwardsCannotUseActionAbilities[their turn]";
        if (AutoAbilityTriggers.FA_OPP_CHARACTERS_CANNOT_USE_ABILITIES
                .matcher(fa.effectText().trim()).matches())
            return "OppCharactersCannotUseAbilities[special+action, always]";
        if (AutoAbilityTriggers.FA_CANCEL_OPP_FIRST_SUMMON_EACH_TURN
                .matcher(fa.effectText().trim()).matches())
            return "CancelOppFirstSummon[each turn]";
        if (ActionResolverPatterns.FA_BZ_CARDS_PROTECTED_FROM_OPP_CHOICE
                .matcher(fa.effectText()).find())
            return "BzCardsCannotBeChosenByOpp";
        if (ActionResolverPatterns.FA_BZ_CARDS_PROTECTED_FROM_OPP_RFG
                .matcher(fa.effectText()).find())
            return "BzCardsCannotBeRfgByOpp";
        if (ActionResolverPatterns.FA_BZ_SUMMONS_PROTECTED_FROM_OPP_RFG
                .matcher(fa.effectText()).find())
            return "BzSummonsCannotBeRfgByOpp";
        if (AutoAbilityTriggers.FA_OPP_ATTACKS_LIMITED_BY_OWN_BACKUPS
                .matcher(fa.effectText().trim()).matches())
            return "OppAttacksLimitedByOwnBackups";
        Matcher sdb = AutoAbilityTriggers.FA_FRIENDLY_SUMMON_DAMAGE_BOOST.matcher(fa.effectText().trim());
        if (sdb.matches()) return "FriendlySummonDamageBoost[+" + sdb.group("amount") + "]";
        Matcher rdf = AutoAbilityTriggers.FA_REDUCE_DAMAGE_TO_FILTER.matcher(fa.effectText().trim());
        if (rdf.matches()) return "ReduceDamageToFilter[-" + rdf.group("amount") + "]";
        Matcher aci = AutoAbilityTriggers.FA_OPP_ACTION_ABILITY_COST_INCREASE.matcher(fa.effectText().trim());
        if (aci.matches())
            return "OppActionAbilityCostIncrease[+"
                    + AutoAbilityTriggers.actionAbilityCostIncreaseAmount(aci) + "]";
        if (maxForwardsGatedSelfGrant(fa, source)) {
            CardData.MaxForwardsGatedGrant g = CardData.parseMaxForwardsGatedGrant(fa.effectText());
            return "MaxForwardsGatedSelfGrant[fwds<=" + g.maxForwards() + "]";
        }
        if (namesItself(CardData.GAINS_OPP_CHARACTER_ELEMENTS_PATTERN, fa, source))
            return "GainsOpponentCharacterElements";
        Integer exbCap = ActionResolver.exBurstSuppressionMaxCost(fa.effectText(), source.name());
        if (exbCap != null)
            return "ExBurstSuppression[" + (exbCap == Integer.MAX_VALUE ? "any cost" : "cost≤" + exbCap) + "]";
        if (CardData.parseIfControlNonDmgBreakShield(fa.effectText(), source.name()) != null)
            return "SelfNonDmgBreakShield[if control]";
        if (CardData.parseSelfNonDmgBreakShield(fa.effectText(), source.name()))
            return "SelfNonDmgBreakShield";
        // Tifa 11-071L prints the same shield inside a quoted ability she hands herself; the
        // unwrapped text is what FieldGrantCalculator reads, so it is what gets described.
        String selfGranted = CardData.selfGrantedFieldAbility(fa.effectText(), source.name());
        CardData.NonDmgBreakShieldGrant ndg = CardData.parseFieldNonDmgBreakShieldGrant(
                selfGranted != null ? selfGranted : fa.effectText());
        if (ndg != null) {
            // Auron 1-002R filters on type alone, so every attribute filter is null there and the
            // type is the only thing there is to name.
            String who = ndg.job()      != null ? "Job " + ndg.job()
                       : ndg.cardName() != null ? "Card Name " + ndg.cardName()
                       : ndg.category() != null ? "Category " + ndg.category()
                       : ndg.element()  != null ? ndg.element()
                       : ndg.inclForwards() && ndg.inclBackups() && ndg.inclMonsters() ? "Characters"
                       : ndg.inclForwards() ? "Forwards"
                       : ndg.inclBackups()  ? "Backups"
                       : "Monsters";
            return "FieldNonDmgBreakShield[" + who + "]";
        }
        Matcher partyNul = AutoAbilityTriggers.FA_PARTY_SELF_DAMAGE_NULLIFY.matcher(fa.effectText());
        if (partyNul.matches())
            return "PartyDamageNullify[" + (partyNul.group("wholeparty") != null ? "party" : "self") + "]";
        AutoAbilityTriggers.DullCostHasteGrant dch =
                AutoAbilityTriggers.parseDullCostHasteGrant(fa.effectText());
        if (dch != null) {
            String who = dch.cardName() != null ? "Card Name " + dch.cardName()
                       : dch.category() != null ? "Category " + dch.category()
                       : dch.job()      != null ? "Job " + dch.job()
                       : "any";
            return "DullCostHaste[" + (dch.selfName() != null ? "self+" : "") + who
                    + (dch.inclSpecial() ? ", incl. Special" : "") + "]";
        }
        CardData.SelfGainsQuotedGrant sgq =
                CardData.parseSelfGainsQuotedGrant(fa.effectText(), source.name());
        if (sgq != null) {
            int selfPower = CardData.parseSelfPowerGrant(fa.effectText(), source.name());
            return "SelfGainsQuotedGrant[" + sgq.traits()
                    + (selfPower > 0 ? " +" + selfPower + " power" : "")
                    + (sgq.maxAttacks() > 1 ? " attacks×" + sgq.maxAttacks() : "")
                    + (sgq.abilityTexts().isEmpty() ? "" : " +" + sgq.abilityTexts().size() + " ability")
                    + (sgq.passiveTexts().isEmpty() ? "" : " +" + sgq.passiveTexts().size() + " passive") + "]";
        }
        CardData.HandSizeSelfGrant hsg = CardData.parseHandSizeSelfGrant(fa.effectText(), source.name());
        if (hsg != null)
            return "HandSizeSelfGrant[" + (hsg.bothPlayers() ? "both" : "either") + "≤" + hsg.maxCards()
                    + " " + hsg.traits() + (hsg.maxAttacks() > 1 ? " attacks×" + hsg.maxAttacks() : "") + "]";
        CardData.DamageRedirectGrant drg = CardData.parseDamageRedirectGrant(fa.effectText(), source.name());
        if (drg != null)
            return "DamageRedirect[" + (drg.cardNameFilter() != null ? "Card Name " + drg.cardNameFilter() : "Forwards")
                    + (drg.exceptCardName() != null ? " excl." + drg.exceptCardName() : "") + " → " + source.name() + "]";
        if (CardData.parseSelfCannotBeBrokenDuringYourTurn(fa.effectText(), source.name()))
            return "SelfCannotBeBroken[your turn]";
        if (CardData.parseSelfCannotBeBrokenDuringAttackPhase(fa.effectText(), source.name()))
            return "SelfCannotBeBroken[Attack Phase]";
        String cbbCounter = CardData.parseSelfCannotBeBrokenWithCounter(fa.effectText(), source.name());
        if (cbbCounter != null) return "SelfCannotBeBroken[" + cbbCounter + " Counter]";
        int oppHst = CardData.parseIfOpponentHandSizeCannotBeBrokenThreshold(fa.effectText(), source.name());
        if (oppHst >= 0) return "IfOppHandSize≤" + oppHst + ":CannotBeBroken";
        int[] cbbPow = CardData.parseFieldCannotBeBlockedByPower(fa.effectText(), source.name());
        if (cbbPow != null)
            return "CannotBeBlockedByPower[" + (cbbPow[1] == 1 ? "≥" : "≤") + cbbPow[0] + "]";
        int[] blockLock = CardData.parseCostForwardsCannotBlock(fa.effectText());
        if (blockLock != null)
            return "FieldCostCannotBlock[cost" + (blockLock[1] > 0 ? "≥" : "≤") + blockLock[0] + "]";
        String dullExcept = CardData.parseAllForwardsExceptEnterDull(fa.effectText());
        if (dullExcept != null) return "AllForwardsEnterDull[excl." + dullExcept + "]";
        if (CardData.parseOpponentForwardsEnterDull(fa.effectText())) return "OppForwardsEnterDull";
        // The bare self grant, power and traits alike — Kain 28-081L behind its "Damage 3 --" gate.
        // Last of the self-grant branches, so the quoted and conditional spellings above name
        // themselves first and only the plain sentence lands here.
        //
        // Texts carrying a quoted clause are excluded outright. Lion 10-123R ("Lion gains +1000
        // power and \"If Lion receives damage …\"") is not recognised, and describing it by the
        // half that does parse would read as coverage the row does not have.
        java.util.Set<CardData.Trait> selfTraits = fa.effectText().contains("\"")
                ? java.util.Set.<CardData.Trait>of()
                : CardData.parseSelfTraitGrant(fa.effectText(), source.name());
        int selfPow = fa.effectText().contains("\"")
                ? 0 : CardData.parseSelfPowerGrant(fa.effectText(), source.name());
        if (!selfTraits.isEmpty() || selfPow > 0)
            return "SelfGrant[" + (selfPow > 0 ? "+" + selfPow + " power" : "")
                    + (selfPow > 0 && !selfTraits.isEmpty() ? " " : "")
                    + (selfTraits.isEmpty() ? "" : selfTraits.toString()) + "]";
        if (CardData.isHasAllJobsAbility(fa.effectText())) return "HasAllJobs";
        if (CardData.isHasJobsOfForwardsAbility(fa.effectText())) return "HasJobsOfForwardsYouControl";
        return null;
    }

    private static String dmgTag(int threshold) {
        return threshold > 0 ? "  [Damage ≥" + threshold + "]" : "";
    }

    /**
     * Reservoir-samples {@code item} into {@code reservoir}, keeping at most {@code capacity}
     * examples uniformly over the {@code seen} candidates offered so far.
     */
    private static void reservoirAdd(List<String> reservoir, String item, int seen,
            java.util.Random rng, int capacity) {
        if (reservoir.size() < capacity) {
            reservoir.add(item);
        } else {
            int j = rng.nextInt(seen);
            if (j < capacity) reservoir.set(j, item);
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
