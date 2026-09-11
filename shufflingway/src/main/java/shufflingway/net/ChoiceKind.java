package shufflingway.net;

/**
 * What a {@link ActionType#CHOICE} answer means.
 *
 * <p>A CHOICE is one player's answer to a question the <em>other</em> client is parked on while an
 * effect resolves. Both clients run the same ability over the same board, so only the answer
 * crosses the wire — never the reasoning behind it, and never the state it produces. The kind is
 * how a waiting client tells whether the answer that just landed is the one it is holding for.
 *
 * <p>Every answer is a list of small integers; what those integers index is fixed per kind and
 * documented below. Two frames of reference are in play and the difference is load bearing. Hand
 * and slot indices are <em>shared</em> — both clients hold each zone in the same order, so index
 * <i>n</i> names the same card on both — while a field code names a <em>side</em>, and the
 * sender's own side is the receiver's opponent. Field codes are therefore always written from the
 * sender's point of view and flipped on arrival.
 *
 * <p><b>One question at a time.</b> A kind is identity enough only because neither client sends a
 * second question before the first is answered. Two questions of the same kind travelling in the
 * same direction within one effect would be indistinguishable, so an effect that needs that has to
 * introduce a kind for the second.
 */
public enum ChoiceKind {

	/** The cards the sender chose to show from their own hand; indices into that hand. */
	REVEAL_HAND,

	/**
	 * The one card the sender picked out of what they were shown. It indexes their opponent's hand
	 * — the receiver's own — because that is the hand the card is discarded from, not the subset it
	 * was chosen out of.
	 */
	SELECT_REVEALED,

	/**
	 * A card the sender picked from their own field: "each player selects 1 Forward", and the
	 * effects that make a player break something of their own. Carried as {@code ForwardTarget}
	 * choice codes, which name a side and so are flipped into the receiver's frame on arrival.
	 */
	OWN_FIELD_CARD,

	/**
	 * What the sender did with the cards they looked at on top of their own deck — a
	 * {@code DeckLookDecision}, flattened. The indices address the peeked cards, which both
	 * clients take off the same seeded deck in the same order, so they need no flip.
	 */
	DECK_LOOK,

	/**
	 * Whether the sender played the single card they just revealed off the top of their own deck:
	 * {@code [1]} for played, {@code [0]} for returned to the top.
	 *
	 * <p>Its own kind rather than a one-card {@link #DECK_LOOK} because it is a yes/no and not an
	 * arrangement: what makes an answer legal here is whether the card may be played at all, which
	 * no permutation check would catch.
	 *
	 * <p>The effect that asks it asks <em>both</em> seats in turn. That stays inside the
	 * one-question-at-a-time rule because each client sends exactly one of the two — its own
	 * player's — and waits for the other.
	 */
	REVEAL_MAY_PLAY,

	/**
	 * Whether the sender took up an optional effect their card offered them — "you may remove it
	 * from the game", "you may search your deck". {@code [1]} for yes, {@code [0]} for no.
	 *
	 * <p>Timing is part of the answer, not just the outcome. Searching a deck is a public event
	 * that other abilities react to, so a player who declines must be seen not to have searched —
	 * which is why the question is put, and crosses, <em>before</em> the effect happens rather
	 * than being reconstructed from what did.
	 */
	MAY,

	/**
	 * Which card in the sender's own LB deck they picked — a position in that deck. 23-118H Ardyn's
	 * "turn 1 face down card in your LB deck face up" is the one effect that asks.
	 *
	 * <p>No flip, for the reason hand indices need none: both clients load the same LB deck in the
	 * same order at setup ({@code initializeP2LbDeck} preserves it, and it is never shuffled), so
	 * position <em>n</em> is the same card on both. The deck is the sender's own either way — one
	 * client holds it as P1's and the other as P2's, and the index means the same thing in both.
	 *
	 * <p>What the answer changes is which card is face up, which is public: the LB viewer renders
	 * spent cards face up to either player. So the choice has to cross even though the zone it
	 * indexes is otherwise hidden.
	 */
	LB_DECK_CARD,

	/**
	 * Whether the sender triggered the EX Burst on a card that just reached their hand:
	 * {@code [1]} for triggered, {@code [0]} for declined.
	 *
	 * <p>Separate from {@link #MAY} only to keep them apart on the wire. One reveal can offer an
	 * optional effect and then turn up an EX Burst, which would put two questions of one kind in
	 * flight in the same direction — the one case the rule above does not cover.
	 */
	EX_BURST,

	/**
	 * What the sender named when an ability told them to name an Element, a Job or a Category —
	 * {@code NamedThing} pairs, flattened. The indices address the game's shared vocabularies
	 * rather than anything on the board, so like hand indices they need no flip.
	 */
	NAMED,

	/**
	 * Which of a handful of options written into the card text the sender picked, as a position in
	 * that list. The list is built from the ability's own words, so both clients have it in hand
	 * before the question is asked and neither has to be sent it.
	 */
	OPTION,

	/**
	 * The result of a roll the <em>game</em> made rather than a player — "your opponent discards 1
	 * card at random". Positions in the pool being drawn from, one per card taken.
	 *
	 * <p>Not a decision, but it travels for the same reason one does. Two clients each calling
	 * {@code Math.random()} over the same hand discard different cards and neither finds out until
	 * a checksum fails several turns later. The alternative — a seeded stream both clients advance
	 * in lockstep — needs them to draw from it the same number of times forever, and never
	 * recovers once they do not; sending the outcome is self-correcting.
	 *
	 * <p>The <b>controller's client rolls</b>, which is the seat convention every other kind
	 * already follows. Which client rolls does not matter as long as both agree.
	 *
	 * <p>Each roll is taken against a pool one smaller than the last, because the card the previous
	 * one picked is gone by then. So the indices are <em>not</em> independent positions in the
	 * original hand, and validating them means walking them in order.
	 */
	RANDOM,

	/**
	 * How the sender split their opponent's Forwards into groups — Kefka 15-071H. One entry per
	 * Forward on that row, in slot order, holding the group it was put in; a row of four split
	 * two-one-one reads {@code [0, 1, 2, 0]}.
	 *
	 * <p>Positions rather than slot indices, and so no flip: the list is built from a row both
	 * clients hold in the same order, and it is the sender's opponent's row on one client and
	 * the sender's own on the other — the same card either way. What crosses is which group each
	 * card went into, and a group number means the same thing on both sides.
	 */
	DIVIDE_GROUPS,

	/**
	 * Which group the sender kept out of the ones they were shown: a single group number, as
	 * numbered by the {@link #DIVIDE_GROUPS} answer that made them.
	 *
	 * <p>Its own kind rather than a second DIVIDE_GROUPS because the two are asked back to back
	 * within one effect — but of opposite seats, so each client sends exactly one of them and
	 * waits for the other, which is what keeps one question in flight at a time.
	 */
	SELECT_GROUP,

	/**
	 * The sender has finished with a combat priority window and passed. Carries nothing — the
	 * message is the whole answer.
	 *
	 * <p>It is sent <em>whenever</em> the window closes, including when this client passed
	 * automatically because its player had nothing to spend priority on. That auto-pass is a local
	 * determination and the two clients do not make it the same way — {@code p1HasActivatableAbilities}
	 * counts anything castable at Summon speed, its P2 counterpart counts only Summons — so a
	 * receiver deriving it instead of being told would wait for a pass that never came.
	 */
	PRIORITY_PASS
}
