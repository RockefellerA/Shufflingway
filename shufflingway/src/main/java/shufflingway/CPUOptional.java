package shufflingway;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How the CPU answers an optional offer: a printed "you may", an optional cost, a chance to use an
 * ability again. Every answer the CPU gives one goes through {@link #ask}, so how it weighs an offer
 * is written in one place.
 *
 * <p>An offer carries what it costs ({@link Cost} — read off the text by {@link #costOf} /
 * {@link #costOfPrompt}, or named by the primitive making it), how many cards accepting it draws and
 * how much damage it deals the CPU, and the answer its call site gives. {@link #decide} applies the
 * rules written so far and falls back on that answer for anything they do not settle:
 * <ul>
 *   <li>an offer that would lose the game is declined — a draw the CPU's deck cannot cover
 *       ({@link MainWindow#drawCardsForPlayer}), or damage that takes it to
 *       {@link #LOSING_DAMAGE} — unless a "can't lose the game" effect protects it;</li>
 *   <li>an offer that costs nothing ({@link #FREE}) is taken.</li>
 * </ul>
 * {@link #acceptsEveryOffer}, a debug setting for playtesting, takes every offer instead. Paying is
 * still the payment's business — an offer with a price is checked for one before it is made, and a
 * CP payment the CPU accepts but cannot cover is paid by its own payment plan, which hands over
 * nothing it lacks.
 *
 * <p>Asked only for a CPU seat. A local player answers in a dialog and a remote one over the wire
 * ({@link MainWindow#decide}), so none of this reaches a networked game.
 */
final class CPUOptional {

	private CPUOptional() {}

	/** What an offer charges for what it gives. */
	enum Cost {
		/** Nothing — "you may search for …", "you may play …". */
		NONE,
		/** A price that is itself a gain: playing a card ("play 1 Monster … from your hand. When you do so, …"). */
		PLAY_CARD,
		/** Searching a card into the Break Zone for the payoff to use (12-029L, 29-033L). */
		SEARCH_TO_BZ,
		/**
		 * Searching cards out of the deck to remove them from the game — a price, the deck card being
		 * gone for good: 29-116H Madeen, 29-117H Ark (a better removal for it), 17-130L The Emperor.
		 */
		SEARCH_TO_RFG,
		CP,
		DISCARD,
		/** Putting the source itself into the Break Zone. */
		SACRIFICE_SELF,
		/** Putting another of the controller's own cards into the Break Zone. */
		SACRIFICE_OTHER,
		REMOVE_FROM_BZ,
		/** Removing the source itself from the game. */
		REMOVE_SELF,
		REMOVE_OTHER,
		REMOVE_COUNTERS,
		/** "receive N point(s) of damage". */
		SELF_DAMAGE,
		DULL,
		RETURN_TO_HAND,
		REVEAL,
		/** Putting a card on top of, at the bottom of, or under a deck. */
		DECK,
		GIVE_CONTROL,
		/**
		 * The offer is the payoff itself, on a card already chosen — "Deal it 7000 damage?",
		 * "Return it to its owner's hand?". Whether it helps turns on whose card that is.
		 */
		ON_CHOSEN,
		/** Nothing above names it. */
		OTHER
	}

	/** The costs that charge nothing a player would weigh: none, or a price that is itself a gain. */
	static final Set<Cost> FREE = EnumSet.of(Cost.NONE, Cost.PLAY_CARD, Cost.SEARCH_TO_BZ);

	/**
	 * One offer: what it costs, what it is (for the log), how many cards taking it draws and how many
	 * points of damage it deals the seat taking it, and the answer its call site gives.
	 */
	record Offer(Cost cost, String what, int draws, int selfDamage, BooleanSupplier current) {}

	/**
	 * What {@link #decide} reads of the board, for the seat answering — no more than its rules use.
	 *
	 * @param deckSize   cards left in that seat's deck
	 * @param damage     points of damage that seat has received
	 * @param cannotLose whether a "can't lose the game" effect protects that seat
	 */
	record Board(int deckSize, int damage, boolean cannotLose) {}

	/** The damage that loses the game — the check {@link MainWindow} makes on every point received. */
	static final int LOSING_DAMAGE = 7;

	/** An answer, and why; {@code reason} is {@code null} when it is the call site's own answer. */
	record Decision(boolean accept, String reason) {}

	/** The system property that sets {@link #acceptsEveryOffer} for a whole run. */
	static final String ACCEPTS_EVERY_OFFER_PROPERTY = "shufflingway.cpuAcceptsEveryOffer";

	/**
	 * Debug: the CPU takes every offer. Set from the Debug menu, or for a whole run with
	 * {@code -Dshufflingway.cpuAcceptsEveryOffer=true} — which is how the test suite shows the
	 * tests that lean on the CPU's usual answers.
	 */
	static volatile boolean acceptsEveryOffer = Boolean.getBoolean(ACCEPTS_EVERY_OFFER_PROPERTY);

	/**
	 * The CPU's answer to {@code offer}. The call site's own answer is asked for only when no rule
	 * settles it.
	 *
	 * @param board the answering seat's board, or {@code null} where the call site has none to give —
	 *              a rule that needs it then stands aside
	 */
	static Decision decide(Offer offer, Board board) {
		if (acceptsEveryOffer) return new Decision(true, "debug setting: the CPU takes every offer");
		String loss = board == null ? null : howItLoses(offer, board);
		if (loss != null) return new Decision(false, loss + " would lose the game");
		if (FREE.contains(offer.cost())) return new Decision(true, "it costs nothing");
		return new Decision(offer.current().getAsBoolean(), null);
	}

	/** How taking {@code offer} would lose the game on {@code board}, or {@code null} when it would not. */
	private static String howItLoses(Offer offer, Board board) {
		if (board.cannotLose()) return null;
		if (offer.draws() > board.deckSize())
			return "drawing " + offer.draws() + " from a deck of " + board.deckSize();
		if (offer.selfDamage() > 0 && board.damage() + offer.selfDamage() >= LOSING_DAMAGE)
			return "taking " + offer.selfDamage() + " damage at " + board.damage();
		return null;
	}

	/**
	 * The CPU's answer to an offer, as the supplier {@link MainWindow#decideYesNo} takes for its CPU
	 * seat — run only when the seat is the CPU's. An answer other than the call site's own is logged
	 * with its reason.
	 *
	 * @param board the answering seat's board, built only when the answer is asked for
	 */
	static BooleanSupplier ask(Offer offer, Supplier<Board> board, Consumer<String> log) {
		return () -> {
			Decision d = decide(offer, board.get());
			if (d.reason() != null)
				log.accept("[CPU] " + (d.accept() ? "accepts" : "declines") + " (" + d.reason() + "): " + offer.what());
			return d.accept();
		};
	}

	/** {@link #ask(Offer, Supplier, Consumer)} for an offer that draws nothing and deals no damage, with no board to give. */
	static BooleanSupplier ask(Cost cost, String what, BooleanSupplier current, Consumer<String> log) {
		return ask(new Offer(cost, what, 0, 0, current), () -> null, log);
	}

	/** "draw N card(s)" — an order, not "draw up to N", whose short deck protects the drawer. */
	private static final Pattern DRAW_N = Pattern.compile("(?i)\\bdraw\\s+(\\d+)\\s+cards?\\b");

	/** How many cards {@code effectText} draws in all, counting each "draw N card(s)" it prints. */
	static int drawsIn(String effectText) {
		return sum(DRAW_N, effectText);
	}

	/**
	 * Damage to the seat taking the offer: "receive N point(s) of damage" (13-060R Cecil's price) and
	 * "[Name] deals you N point(s) of damage" (19-008R Buffasaur's payoff). Counted whether or not a
	 * condition stands in front of it — 16-089H Zack's only lands on a play of cost 5 or more — so a
	 * seat it would kill declines the offer rather than gamble on the condition.
	 */
	private static final Pattern SELF_DAMAGE_N = Pattern.compile(
			"(?i)\\b(?:receive|deals?\\s+you)\\s+(\\d+)\\s+points?\\s+of\\s+damage\\b");

	/** How many points of damage {@code effectText} deals the seat taking it, in all. */
	static int selfDamageIn(String effectText) {
		return sum(SELF_DAMAGE_N, effectText);
	}

	private static int sum(Pattern counted, String text) {
		if (text == null) return 0;
		int n = 0;
		for (Matcher m = counted.matcher(text); m.find(); ) n += Integer.parseInt(m.group(1));
		return n;
	}

	// ---------------------------------------------------------------------------------------------
	// Reading an offer's cost
	// ---------------------------------------------------------------------------------------------

	/** "X. When/If you do so, Y" — X is the price, Y what it buys. */
	private static final Pattern DO_SO = Pattern.compile(
			"(?is)^(?<price>.+?)[.,]?\\s+(?:When|If)\\s+you\\s+do\\s+so[,.]?\\s+(?<payoff>.+)$");
	/** A leading condition: 15-061H Lehko Habhoka's "if you have cast 3 or 4 cards this turn, …". */
	private static final Pattern LEADING_IF = Pattern.compile("(?i)^if\\s+[^,]+,\\s+");
	private static final Pattern LEADING_YOU_MAY = Pattern.compile("(?i)^(?:you|your\\s+opponent)\\s+may\\s+");
	/** "choose 1 Forward. You may put …" (4-087R, 7-020C): the choice is the payoff's target; the price follows. */
	private static final Pattern LEADING_CHOICE = Pattern.compile("(?is)^choose\\b[^.]*\\.\\s+(?<rest>\\S.*)$");
	/** "search for … and remove it/them from the game" — {@link Cost#SEARCH_TO_RFG}. */
	private static final Pattern SEARCH_THEN_REMOVE = Pattern.compile(
			"(?is)^search\\b.*\\bremove\\s+(?:it|them)\\s+from\\s+the\\s+game\\b.*");
	/**
	 * A price with no "do so" after it: only these lead a cost as surely as they lead an effect —
	 * a pay, a discard, a "receive N points of damage", and a search that removes what it finds
	 * (17-130L The Emperor's "search for up to 2 Card Name The Emperor and remove them from the game.
	 * Then, …").
	 */
	private static final Pattern BARE_PRICE = Pattern.compile(
			"(?is)^(?:(?:pay|discard|receive)\\b.*|search\\b.*\\bremove\\s+(?:it|them)\\s+from\\s+the\\s+game\\b.*)");

	/**
	 * What the offer in {@code effectText} costs — the X of "X. When/If you do so, Y", read after a
	 * leading "if …," and "you may". A text with no "do so" is a cost only when it leads with one of
	 * {@link #BARE_PRICE}'s prices; anything else is an effect taken for nothing.
	 *
	 * @param source the card making the offer, whose own name marks a self-sacrifice or self-removal
	 */
	static Cost costOf(String effectText, CardData source) {
		if (effectText == null) return Cost.OTHER;
		Matcher m = DO_SO.matcher(effectText.trim());
		if (m.matches()) return priceOf(m.group("price"), source);
		return BARE_PRICE.matcher(stripLead(effectText)).matches() ? priceOf(effectText, source) : Cost.NONE;
	}

	/**
	 * What the offer a resolving effect puts in so many words costs — {@code GameContext.promptYouMay}'s
	 * "Search your deck?", "Give control of Leslie to your opponent?", "Remove Kadaj from the game?".
	 */
	static Cost costOfPrompt(String prompt, CardData source) {
		if (prompt == null) return Cost.OTHER;
		String p = prompt.trim().toLowerCase(Locale.ROOT);
		if (SEARCH_THEN_REMOVE.matcher(p).matches()) return Cost.SEARCH_TO_RFG;
		if (p.startsWith("search") || p.startsWith("play")) return Cost.NONE;
		if (p.startsWith("deal it") || p.startsWith("return it")) return Cost.ON_CHOSEN;
		if (p.startsWith("give control")) return Cost.GIVE_CONTROL;
		if (source != null && p.startsWith("remove " + source.name().toLowerCase(Locale.ROOT) + " ")) return Cost.REMOVE_SELF;
		if (p.contains("warp counter")) return Cost.REMOVE_COUNTERS;
		if (p.startsWith("remove the chosen card")) return Cost.REMOVE_OTHER;
		if (p.startsWith("put the chosen card")) return Cost.DECK;
		return Cost.OTHER;
	}

	private static String stripLead(String text) {
		String s = LEADING_IF.matcher(text.trim()).replaceFirst("");
		return LEADING_YOU_MAY.matcher(s).replaceFirst("").trim();
	}

	private static Cost priceOf(String price, CardData source) {
		String s = stripLead(price);
		Matcher choice = LEADING_CHOICE.matcher(s);
		if (choice.matches()) return priceOf(choice.group("rest"), source);
		String self = source != null ? Pattern.quote(source.name()) : null;
		if (s.matches("(?is)^play\\b.*\\bfrom\\s+your\\s+(?:hand|LB\\s+deck|Break\\s+Zone)\\b.*")) return Cost.PLAY_CARD;
		if (SEARCH_THEN_REMOVE.matcher(s).matches())                                                  return Cost.SEARCH_TO_RFG;
		if (s.matches("(?is)^search\\b.*\\binto\\s+the\\s+Break\\s+Zone\\b.*"))                   return Cost.SEARCH_TO_BZ;
		if (s.matches("(?is)^pay\\b.*"))                                                          return Cost.CP;
		if (s.matches("(?is)^receive\\s+\\d+\\s+points?\\s+of\\s+damage.*"))                       return Cost.SELF_DAMAGE;
		if (s.matches("(?is)^discard\\b.*"))                                                      return Cost.DISCARD;
		if (self != null && s.matches("(?is)^put\\s+" + self + "\\s+into\\s+the\\s+Break\\s+Zone.*")) return Cost.SACRIFICE_SELF;
		if (s.matches("(?is)^put\\s+.+?\\s+into\\s+the\\s+Break\\s+Zone.*"))                        return Cost.SACRIFICE_OTHER;
		if (s.matches("(?is)^remove\\s+.+?\\s+(?:in|from)\\s+your\\s+Break\\s+Zone.*"))              return Cost.REMOVE_FROM_BZ;
		if (s.matches("(?is)^remove\\s+\\d+\\s+.+?\\s+Counters?\\b.*"))                             return Cost.REMOVE_COUNTERS;
		if (self != null && s.matches("(?is)^remove\\s+" + self + "\\s+from\\s+the\\s+game.*"))       return Cost.REMOVE_SELF;
		if (s.matches("(?is)^remove\\b.*\\bfrom\\s+the\\s+game.*"))                                return Cost.REMOVE_OTHER;
		if (s.matches("(?is)^dull\\b.*"))                                                         return Cost.DULL;
		if (s.matches("(?is)^return\\s+.+?\\s+to\\s+(?:its\\s+owner's|your)\\s+hand.*"))             return Cost.RETURN_TO_HAND;
		if (s.matches("(?is)^reveal\\b.*"))                                                       return Cost.REVEAL;
		if (s.matches("(?is)^put\\s+.+?\\s+(?:on\\s+top\\s+of|at\\s+the\\s+bottom\\s+of|under)\\s+.*deck.*")) return Cost.DECK;
		return Cost.OTHER;
	}
}
