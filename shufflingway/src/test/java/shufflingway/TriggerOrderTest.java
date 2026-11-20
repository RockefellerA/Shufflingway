package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import shufflingway.dialog.StackOrderingDialog;
import shufflingway.net.ActionType;
import shufflingway.net.ChoiceKind;
import shufflingway.net.ConnectionListener;
import shufflingway.net.GameAction;
import shufflingway.net.GameConnection;
import shufflingway.net.MatchSetup;

/**
 * Simultaneous triggers go on the Stack in the order their controller numbered them in the
 * stack-ordering dialog: the one numbered 1st resolves first, and is also the first to make its
 * push-time choices. In a networked game that order crosses as {@link ChoiceKind#STACK_ORDER}, so
 * both clients put the batch on alike.
 *
 * <p>Every board here is the same two triggers from one arrival — a watcher Backup's and the
 * arriving Forward's own — neither of which asks anything as it goes on the Stack. They are named so
 * that the order the wire counts in (sorted by name) is the reverse of the order they are collected
 * in (the arrival's own first), so an answer read against the wrong one fails.
 */
class TriggerOrderTest {

	private static final String WATCHER = "Aardvark";
	private static final String ARRIVAL = "Zebra";

	private static CardData card(String name, String type, String text) {
		return new CardData(null, name, "Lightning", 2, "Forward".equals(type) ? 5000 : 0, type,
				false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), CardData.parseAutoAbilities(text), List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, text);
	}

	private static CardData watcher() {
		return card(WATCHER, "Backup", "When a Forward enters your field, draw 1 card.");
	}

	private static CardData arrival() {
		return card(ARRIVAL, "Forward", "When " + ARRIVAL + " enters the field, draw 1 card.");
	}

	private static MainWindow boardWithDecks() {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < 5; i++) {
			mw.gameState.getP1MainDeck().add(card("Filler " + i, "Forward", ""));
			mw.gameState.getP2MainDeck().add(card("Filler " + i, "Forward", ""));
		}
		return mw;
	}

	/** Has the local player order the batch as {@code resolveFirst}, recording what was offered. */
	private static void localOrder(MainWindow mw, List<String> offered, String... resolveFirst) {
		mw.autoAbilityTriggers.stackOrderChooser = items -> {
			items.forEach(it -> offered.add(it.source().name()));
			List<StackOrderingDialog.Item> ordered = new ArrayList<>();
			for (String name : resolveFirst)
				for (StackOrderingDialog.Item it : items)
					if (it.source().name().equals(name)) ordered.add(it);
			return ordered;
		};
	}

	private static List<String> stackTopFirst(MainWindow mw) {
		List<String> names = new ArrayList<>();
		for (StackEntry e : mw.gameState.getStack()) names.add(e.source().name());
		Collections.reverse(names);
		return names;
	}

	// =========================================================================================
	// The local player orders their own triggers
	// =========================================================================================

	@Test
	void theAbilityNumberedFirstGoesOnTheStackFirstAndResolvesFirst() {
		MainWindow mw = boardWithDecks();
		mw.placeCardInFirstBackupSlot(watcher());

		List<String> offered = new ArrayList<>();
		localOrder(mw, offered, WATCHER, ARRIVAL);
		mw.placeCardInForwardZone(arrival());
		while (!mw.gameState.getStack().isEmpty()) mw.resolveTopOfStack();

		String log = mw.gameLogText();
		assertEquals(List.of(ARRIVAL, WATCHER), offered,
				"offered in the order collected, which is the reverse of the order numbered\n" + log);

		int firstPushed  = log.indexOf(WATCHER + " — pushed to stack");
		int secondPushed = log.indexOf(ARRIVAL + " — pushed to stack");
		assertTrue(firstPushed >= 0 && secondPushed >= 0, log);
		assertTrue(firstPushed < secondPushed,
				"the one numbered 1st goes on the Stack, and so makes its choices, before the 2nd\n" + log);

		int firstResolved  = log.indexOf("Resolving \"" + WATCHER + "\"");
		int secondResolved = log.indexOf("Resolving \"" + ARRIVAL + "\"");
		assertTrue(firstResolved >= 0 && secondResolved >= 0, log);
		assertTrue(firstResolved < secondResolved, "and resolves before it\n" + log);
	}

	// =========================================================================================
	// Across the wire
	// =========================================================================================

	private static MatchSetup wireSetup() {
		return new MatchSetup(1, List.of(), "Deck", "Them", 1L, true, true);
	}

	private ServerSocket   listener;
	private Socket         hostSide, joinerSide;
	private GameConnection host,     joiner;
	private final BlockingQueue<GameAction> sent = new ArrayBlockingQueue<>(64);

	/** A remote seat on a loopback socket; what this client sends lands in {@link #sent}. */
	private RemoteOpponent connectedRemote(MainWindow mw) throws IOException {
		listener   = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		joinerSide = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
		hostSide   = listener.accept();
		host   = new GameConnection(hostSide);
		joiner = new GameConnection(joinerSide);
		joiner.addListener(new ConnectionListener() {
			@Override public void onActionReceived(GameAction action) { sent.add(action); }
			@Override public void onDisconnected(String reason) {}
		});
		joiner.start();
		RemoteOpponent remote = new RemoteOpponent(mw, host, wireSetup());
		mw.opponent = remote;
		return remote;
	}

	@AfterEach
	void disconnect() throws IOException {
		if (host     != null) host.close();
		if (joiner   != null) joiner.close();
		if (listener != null) listener.close();
	}

	/** The next {@link ChoiceKind#STACK_ORDER} this client sent, or a failure after 5s. */
	private List<Integer> nextStackOrderSent() throws InterruptedException {
		while (true) {
			GameAction action = sent.poll(5, TimeUnit.SECONDS);
			assertNotNull(action, "no STACK_ORDER was sent — the far client would still be waiting");
			if (action.type() != ActionType.CHOICE
					|| !ChoiceKind.STACK_ORDER.name().equals(action.payload().getString("kind"))) continue;
			JSONArray raw = action.payload().getJSONArray("indices");
			List<Integer> out = new ArrayList<>();
			for (int i = 0; i < raw.length(); i++) out.add(raw.getInt(i));
			return out;
		}
	}

	@Test
	void theLocalPlayersOrderIsSentCountedInWireOrder() throws Exception {
		MainWindow mw = boardWithDecks();
		connectedRemote(mw);
		mw.placeCardInFirstBackupSlot(watcher());

		localOrder(mw, new ArrayList<>(), WATCHER, ARRIVAL);
		mw.placeCardInForwardZone(arrival());

		// Wire order is by name: Aardvark 0, Zebra 1. Counted in collection order instead — Zebra
		// first — the same choice would read [1, 0].
		assertEquals(List.of(0, 1), nextStackOrderSent(),
				"Aardvark to resolve first, then Zebra, as positions in the order both clients sort to");
	}

	@Test
	void theRemoteControllersOrderIsReadFromTheWire() {
		MainWindow mw = boardWithDecks();
		RemoteOpponent remote = new RemoteOpponent(mw, null, wireSetup());
		mw.opponent = remote;
		mw.placeP2CardInFirstBackupSlot(watcher());

		// Zebra (wire position 1) to resolve first. Read as collection positions, the same answer
		// would put Aardvark on top.
		remote.onActionReceived(RemoteOpponent.choiceAction(ChoiceKind.STACK_ORDER, List.of(1, 0)));
		mw.placeP2CardInForwardZone(arrival());

		assertEquals(List.of(ARRIVAL, WATCHER), stackTopFirst(mw),
				"their Zebra is on top and resolves first, because that is the order they chose");
	}

	@Test
	void anOrderThatIsNotAPermutationIsRefusedAndTheBatchStillGoesOn() {
		MainWindow mw = boardWithDecks();
		// The refusal reports a desync, whose dialog would hold a headless run open.
		mw.desyncReported = true;
		RemoteOpponent remote = new RemoteOpponent(mw, null, wireSetup());
		mw.opponent = remote;
		mw.placeP2CardInFirstBackupSlot(watcher());

		remote.onActionReceived(RemoteOpponent.choiceAction(ChoiceKind.STACK_ORDER, List.of(1, 1)));
		mw.placeP2CardInForwardZone(arrival());

		List<String> stack = stackTopFirst(mw);
		assertEquals(2, stack.size(), "neither ability is lost to the refused answer: " + stack);
		assertTrue(stack.containsAll(List.of(ARRIVAL, WATCHER)), stack.toString());
	}
}
