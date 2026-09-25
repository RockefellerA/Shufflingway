package shufflingway;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The restrictions on using an action ability that are about the game rather than about where its
 * source stands — "You can only use this ability if …" clauses on turn history, hands, damage, the
 * Break Zone and the field.
 *
 * <p>One check for every place an ability is used from. The field, the Break Zone and the hand each
 * had their own activation test, and only the field's read these: 25-017R Red XIII and 21-134S
 * Bartz were usable from the Break Zone with no Category Forwards at all, and 21-057R Fran's
 * "if a Card Name Balthier has entered your field this turn" was parsed and never read anywhere.
 *
 * <p>Conditions on the source itself — its power, the damage it carries, its counters, whether it
 * is a Forward — stay in {@link MainWindow#canActivateAbility}, since they only mean anything for a
 * card on the field.
 *
 * <p>Four wordings have no {@link ActionAbility} field and are read off the text here instead:
 * each player's damage (11-130L), cards cast this turn (18-040H), Element cards in your Break Zone
 * (26-057C), and "up to N times per turn" (29-011H).
 */
final class UseConditions {

	private UseConditions() {}

	private static final Pattern EACH_PLAYER_DAMAGE = Pattern.compile(
			"(?i)\\bif\\s+each\\s+player\\s+has\\s+received\\s+(?<n>\\d+)\\s+points?\\s+of\\s+damage\\s+or\\s+more");

	private static final Pattern CAST_N_THIS_TURN = Pattern.compile(
			"(?i)\\bif\\s+you\\s+have\\s+cast\\s+(?<n>\\d+)\\s+or\\s+more\\s+cards\\s+this\\s+turn");

	private static final Pattern ELEMENT_CARDS_IN_BZ = Pattern.compile(
			"(?i)\\bif\\s+there\\s+are\\s+(?<n>\\d+)\\s+or\\s+more\\s+"
			+ "(?<elem>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+cards\\s+in\\s+your\\s+Break\\s+Zone");

	private static final Pattern USES_PER_TURN = Pattern.compile(
			"(?i)\\bup\\s+to\\s+(?<n>\\d+)\\s+times\\s+per\\s+turn");

	/** Whether {@code isP1} may use {@code ability} of {@code source} now, as far as these go. */
	static boolean met(MainWindow mw, ActionAbility ability, CardData source, boolean isP1) {
		GameState gs = mw.gameState;
		PlayerTurnState turn = mw.turn(isP1);

		if (ability.damageThreshold() > 0) {
			int dmg = isP1 ? gs.getP1DamageZone().size() : gs.getP2DamageZone().size();
			if (dmg < ability.damageThreshold()) return false;
		}
		if (ability.requiresOppDiscardedThisTurn() && !turn.causedOpponentDiscardThisTurn) return false;
		if (ability.requiresCastSummonThisTurn() && !turn.summonCastThisTurn) return false;
		List<CardData> oppHand = isP1 ? gs.getP2Hand() : gs.getP1Hand();
		if (ability.requiresOpponentEmptyHand() && !oppHand.isEmpty()) return false;
		if (ability.maxOpponentHandSize() >= 0 && oppHand.size() > ability.maxOpponentHandSize()) return false;
		if (ability.requiresOwnWarpCard()
				&& (isP1 ? gs.getP1WarpZone() : gs.getP2WarpZone()).isEmpty()) return false;
		if (ability.requiresSelfEmptyHand() && !(isP1 ? gs.getP1Hand() : gs.getP2Hand()).isEmpty()) return false;
		if (ability.requiresNamedCardTookDamageThisTurn() != null
				&& !turn.cardsTookDamageThisTurn.contains(ability.requiresNamedCardTookDamageThisTurn())) return false;
		if (ability.requiresSelfReceivedDamageThisTurn() && !turn.receivedDamageThisTurn) return false;
		if (ability.requiresForwardPutToBZThisTurn() && !turn.forwardPutToBZThisTurn) return false;
		if (ability.requiresJobPutToBZThisTurn() != null
				&& !turn.brokenJobsThisTurn.contains(ability.requiresJobPutToBZThisTurn())) return false;
		if (ability.requiresElementForwardEnteredThisTurn() != null
				&& !turn.elementForwardsEnteredThisTurn.contains(ability.requiresElementForwardEnteredThisTurn()))
			return false;
		if (ability.requiresCardNameEnteredThisTurn() != null
				&& turn.charactersEnteredThisTurn.stream().noneMatch(
						c -> CardFilters.meetsCardNameFilter(c, ability.requiresCardNameEnteredThisTurn())))
			return false;
		if (ability.controlCondition() != null && !mw.controlConditionMet(ability.controlCondition(), isP1))
			return false;

		String text = ability.effectText();
		Matcher m = EACH_PLAYER_DAMAGE.matcher(text);
		if (m.find()) {
			int n = Integer.parseInt(m.group("n"));
			if (gs.getP1DamageZone().size() < n || gs.getP2DamageZone().size() < n) return false;
		}
		m = CAST_N_THIS_TURN.matcher(text);
		if (m.find() && turn.cardsCastThisTurn < Integer.parseInt(m.group("n"))) return false;
		m = ELEMENT_CARDS_IN_BZ.matcher(text);
		if (m.find()) {
			String elem = m.group("elem");
			long count = (isP1 ? gs.getP1BreakZone() : gs.getP2BreakZone()).stream()
					.filter(c -> c.containsElement(elem)).count();
			if (count < Integer.parseInt(m.group("n"))) return false;
		}
		m = USES_PER_TURN.matcher(text);
		if (m.find()) {
			int used = mw.abilityUsesThisTurn.getOrDefault(source, Map.of()).getOrDefault(text, 0);
			if (used >= Integer.parseInt(m.group("n"))) return false;
		}
		return true;
	}
}
