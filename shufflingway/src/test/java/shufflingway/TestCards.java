package shufflingway;

import java.util.List;
import java.util.Set;

/**
 * Card builders and board placement shared across behaviour test classes.
 *
 * <p>{@link CardBehaviorTest} still carries private copies of these; new test classes use this one
 * so they do not have to reach into a 65,000-line file for a Forward.
 */
final class TestCards {

	private TestCards() {}

	static CardData makeForward(String name, String element, int cost, int power) {
		return new CardData(null, name, element, cost, power, "Forward", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, "");
	}

	/** A plain Forward with a Job, for "Job X" filters and conditions. */
	static CardData makeForwardWithJob(String name, String element, int cost, int power, String job) {
		return new CardData(null, name, element, cost, power, "Forward", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				job, null, null, "");
	}

	/** A 3-cost, 7000-power card of {@code type} carrying a single {@code job}. */
	static CardData makeJobCard(String name, String element, String type, String job) {
		return new CardData(null, name, element, 3, 7000, type, false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				job, null, null, "");
	}

	/** A 3-cost, 7000-power Forward with an element and a category (no job). */
	static CardData makeCategoryForward(String name, String element, String category) {
		return new CardData(null, name, element, 3, 7000, "Forward", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, category, null, "");
	}

	/** Builds a Forward carrying the given innate traits (e.g. CANNOT_BE_BROKEN). */
	static CardData makeForwardWithTraits(String name, String element, int power,
			Set<CardData.Trait> traits) {
		return new CardData(null, name, element, 3, power, "Forward", false, 0, false, false,
				traits, 0, List.of(), null, List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, "");
	}

	/** A card of any type whose abilities are parsed from {@code text}, as the database would. */
	static CardData makeTextCard(String name, String element, String type, int cost, int power,
			String job, String text) {
		return new CardData(null, name, element, cost, power, type, false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				CardData.parseActionAbilities(text), CardData.parseAutoAbilities(text),
				CardData.parseFieldAbilities(text, type),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				job, null, null, text);
	}

	/** Builds a card whose field abilities and IfControlBoosts are both parsed from {@code text}. */
	static CardData makeIcbCard(String name, String element, String type, String text) {
		return new CardData(null, name, element, 3, 7000, type, false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), CardData.parseFieldAbilities(text, type),
				CardData.parseIfControlBoosts(text, type),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, text);
	}

	static CardData makeMonster(String name, String element, int cost) {
		return new CardData(null, name, element, cost, 0, "Monster", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, "");
	}

	/** Seats {@code card} on P1's Monster row with its owner recorded. */
	static void placeP1Monster(MainWindow mw, CardData card) {
		mw.gameState.getIdentity().put(card, true);
		mw.placeCardInMonsterZone(card);
	}

	static CardData makeSummon(String name, String element, int cost, String text) {
		return new CardData(null, name, element, cost, 0, "Summon", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, text);
	}

	static CardData makeBackup(String name, String element, int cost) {
		return new CardData(null, name, element, cost, 0, "Backup", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, "");
	}

	/** Seats {@code card} active in P1's first empty Backup slot, with its owner recorded. */
	static void placeP1Backup(MainWindow mw, CardData card) {
		mw.gameState.getIdentity().put(card, true);
		placeBackup(mw.p1BackupCards, mw.p1BackupStates, card);
	}

	/** Seats {@code card} active in P2's first empty Backup slot, with its owner recorded. */
	static void placeP2Backup(MainWindow mw, CardData card) {
		mw.gameState.getIdentity().put(card, false);
		placeBackup(mw.p2BackupCards, mw.p2BackupStates, card);
	}

	/** Seats {@code card} active in {@code isP1}'s first empty Backup slot, with its owner recorded. */
	static void placeBackup(MainWindow mw, CardData card, boolean isP1) {
		if (isP1) placeP1Backup(mw, card);
		else      placeP2Backup(mw, card);
	}

	/** A Forward-row target on {@code isP1}'s side at slot {@code idx}. */
	static ForwardTarget fwd(boolean isP1, int idx) {
		return new ForwardTarget(isP1, idx, ForwardTarget.CardZone.FORWARD);
	}

	private static void placeBackup(CardData[] slots, CardState[] states, CardData card) {
		for (int i = 0; i < slots.length; i++) {
			if (slots[i] == null) {
				slots[i]  = card;
				states[i] = CardState.ACTIVE;
				return;
			}
		}
		throw new IllegalStateException("no empty Backup slot for " + card.name());
	}

	/** Seats {@code card} on P1's Forward row with its owner recorded, as a real game would. */
	static void placeP1Forward(MainWindow mw, CardData card) {
		mw.gameState.getIdentity().put(card, true);
		mw.placeCardInForwardZone(card);
	}

	/** Seats {@code card} on P2's Forward row. Fires its enters-field triggers, as play would. */
	static void placeP2Forward(MainWindow mw, CardData card) {
		mw.gameState.getIdentity().put(card, false);
		mw.placeP2CardInForwardZone(card);
	}
}
