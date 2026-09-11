package shufflingway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Hashes the parts of a networked game that both clients must agree on, so a divergence is
 * caught where it happened instead of surfacing ten turns later as an impossible board.
 *
 * <p>Digests are taken from the <em>host/joiner</em> point of view rather than P1/P2: each
 * client shows itself as P1, so the two disagree about which player any given deck belongs to.
 * Feeding them in owner order makes the two digests directly comparable.
 *
 * <p>Cards are identified by the fields that a shuffle can reorder — name, cost, element, type.
 * {@link CardData} carries no serial, and this only has to detect ordering divergence, not
 * authenticate card identity; the lobby's card-database checksum already does that.
 */
final class MatchChecksum {

	private MatchChecksum() {}

	/**
	 * Digests both decks in their freshly shuffled order plus the agreed first player.
	 * Call before any card is drawn — this is the assertion that the deal itself matched.
	 *
	 * @param localIsHost   whether this client hosted, i.e. whether P1 here is the host
	 * @param hostGoesFirst the agreed coin flip
	 */
	static String ofOpeningDeal(GameState state, boolean localIsHost, boolean hostGoesFirst) {
		Collection<CardData> hostDeck   = localIsHost ? state.getP1MainDeck() : state.getP2MainDeck();
		Collection<CardData> joinerDeck = localIsHost ? state.getP2MainDeck() : state.getP1MainDeck();
		Collection<CardData> hostLb     = localIsHost ? state.getP1LbDeck()   : state.getP2LbDeck();
		Collection<CardData> joinerLb   = localIsHost ? state.getP2LbDeck()   : state.getP1LbDeck();

		StringBuilder sb = new StringBuilder();
		sb.append("first=").append(hostGoesFirst ? "host" : "joiner").append('\n');
		appendZone(sb, "hostDeck",   hostDeck);
		appendZone(sb, "joinerDeck", joinerDeck);
		appendZone(sb, "hostLb",     hostLb);
		appendZone(sb, "joinerLb",   joinerLb);
		return sha256(sb.toString());
	}

	/**
	 * Digests the whole board at the start of a turn: zone sizes on both sides plus the actual
	 * contents of the field. Cheap enough to run every turn, and catches drift as soon as it
	 * appears rather than at whatever later point it first becomes visible.
	 *
	 * @param localIsHost whether P1 on this client is the host
	 */
	static String ofTurnStart(MainWindow mw, boolean localIsHost, int turn) {
		return ofBoard(mw, localIsHost, "turn=" + turn);
	}

	/**
	 * Digests the board at a combat boundary — the end of one battle.
	 *
	 * <p>Combat is where the two clients do the most independent work: each resolves the same
	 * battle from its own side, off a declaration and an answer that crossed the wire. Waiting for
	 * the next turn to compare would let a mis-assigned point of damage sit undetected through
	 * every attack that followed it.
	 *
	 * @param battle the battle's sequence number, which is what pairs this digest with the
	 *               opponent's for the same combat — see {@code MainWindow.sendCombatChecksum}
	 */
	static String ofCombat(MainWindow mw, boolean localIsHost, int battle) {
		return ofBoard(mw, localIsHost, "battle=" + battle);
	}

	private static String ofBoard(MainWindow mw, boolean localIsHost, String header) {
		StringBuilder sb = new StringBuilder();
		sb.append(header).append('\n');
		appendSide(sb, "host",   mw, localIsHost);
		appendSide(sb, "joiner", mw, !localIsHost);
		return sha256(sb.toString());
	}

	/** Appends one player's zones, chosen by whether they sit in the P1 or P2 seat here. */
	private static void appendSide(StringBuilder sb, String label, MainWindow mw, boolean isP1Seat) {
		GameState state = mw.gameState;
		sb.append(label)
		  .append(" deck=").append(isP1Seat ? state.getP1MainDeck().size()  : state.getP2MainDeck().size())
		  .append(" hand=").append(isP1Seat ? state.getP1Hand().size()      : state.getP2Hand().size())
		  .append(" break=").append(isP1Seat ? state.getP1BreakZone().size(): state.getP2BreakZone().size())
		  .append(" damage=").append(isP1Seat ? state.getP1DamageZone().size() : state.getP2DamageZone().size())
		  .append('\n');
		appendField(sb, label + "Forwards",
				isP1Seat ? mw.p1ForwardCards : mw.p2ForwardCards,
				isP1Seat ? mw.p1ForwardDamage : mw.p2ForwardDamage,
				isP1Seat ? mw.p1ForwardStates : mw.p2ForwardStates);
		appendField(sb, label + "Monsters",
				isP1Seat ? mw.p1MonsterCards : mw.p2MonsterCards,
				isP1Seat ? mw.p1MonsterDamage : mw.p2MonsterDamage,
				isP1Seat ? mw.p1MonsterStates : mw.p2MonsterStates);
		CardData[] backups     = isP1Seat ? mw.p1BackupCards  : mw.p2BackupCards;
		CardState[] backupState = isP1Seat ? mw.p1BackupStates : mw.p2BackupStates;
		sb.append(label).append("Backups\n");
		for (int i = 0; i < backups.length; i++) {
			CardData c = backups[i];
			sb.append(c == null ? "-" : c.name())
			  .append('|').append(c == null ? "-" : String.valueOf(backupState[i]))
			  .append('\n');
		}
		// Which LB cards are face up, not the deck's contents — those never change and are already
		// digested at the opening deal. A card played out of the LB deck stays in the list and is
		// marked here instead, so without this a divergent payment is invisible: the two clients
		// would show identical LB decks while disagreeing about what is left to play from them.
		// Sorted, because the set's own iteration order is not something two clients need to share.
		Set<Integer> spent = isP1Seat ? mw.spentLbIndices : mw.p2SpentLbIndices;
		List<Integer> faceUp = new ArrayList<>(spent);
		Collections.sort(faceUp);
		sb.append(label).append("LbFaceUp").append(faceUp).append('\n');
	}

	/**
	 * A field zone, carrying the state a battle moves as well as which cards are standing.
	 *
	 * <p>Names alone would let two clients agree on a board where one had applied a battle's
	 * damage and the other had not — exactly the divergence a combat digest exists to catch, and
	 * one that stays invisible until the damaged card is broken by something else entirely. Dull
	 * state is in for the same reason: attacking dulls, and a missed dull is a free extra attack.
	 */
	private static void appendField(StringBuilder sb, String label, List<CardData> cards,
			List<Integer> damage, List<CardState> states) {
		sb.append(label).append('[').append(cards.size()).append("]\n");
		for (int i = 0; i < cards.size(); i++) {
			CardData c = cards.get(i);
			sb.append(c.name()).append('|').append(c.cost()).append('|')
			  .append(c.element()).append('|').append(c.type())
			  .append('|').append(i < damage.size() ? damage.get(i) : 0)
			  .append('|').append(i < states.size() ? states.get(i) : CardState.ACTIVE)
			  .append('\n');
		}
	}

	private static void appendZone(StringBuilder sb, String label, Collection<CardData> cards) {
		sb.append(label).append('[').append(cards.size()).append("]\n");
		for (CardData c : cards) {
			sb.append(c.name()).append('|').append(c.cost()).append('|')
			  .append(c.element()).append('|').append(c.type()).append('\n');
		}
	}

	private static String sha256(String input) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) hex.append(String.format("%02x", b));
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
