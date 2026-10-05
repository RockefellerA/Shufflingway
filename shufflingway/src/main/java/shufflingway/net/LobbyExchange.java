package shufflingway.net;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.json.JSONArray;
import org.json.JSONObject;

import scraper.DeckDatabase;
import shufflingway.AppSettings;
import shufflingway.CounterColors;
import shufflingway.DeckFormat;

/**
 * The post-handshake half of the lobby: swapping decks and agreeing on the shuffle seed and
 * who moves first.
 *
 * <p>Runs on each lobby's background thread using {@link GameConnection#receiveSync}, before
 * the game's reader thread starts. Each side's loop ends on the last message the peer sends
 * before the game, so nothing is left reading when that reader takes over.
 *
 * <p>Order on the wire:
 * <pre>
 *   host → joiner : LOBBY_SETTINGS (on connect, and again whenever the host changes one)
 *   joiner → host : LOBBY_READY    (once the joiner has seen the settings, and whenever its
 *                                   confirmed deck comes or goes)
 *   host → joiner : DECK_LIST      (the host pressed Start)
 *   joiner → host : DECK_LIST      (its confirmed deck — or LOBBY_READY false if it has none,
 *                                   and the host waits for another Start)
 *   host → joiner : GAME_SETUP     (seed + coin flip + final settings; host-authored)
 * </pre>
 */
public final class LobbyExchange {

	private LobbyExchange() {}

	/** Longest opponent deck name shown; a longer one is cut and ends in "…". */
	static final int MAX_DECK_NAME_LENGTH = 100;

	/**
	 * Reads {@code deckId} out of the local deck database as a wire-ready serial list, tagged with
	 * the local player's username and counter color, so the peer can label us on their board and
	 * draw the counters on our Characters in the color we chose.
	 */
	public static GameAction deckListAction(int deckId, String deckName) throws SQLException {
		return deckListAction(ActionType.DECK_LIST, deckId, deckName);
	}

	/** {@link #deckListAction(int, String)} under another type — NEW_GAME_READY carries the same payload. */
	public static GameAction deckListAction(ActionType type, int deckId, String deckName) throws SQLException {
		List<String> serials;
		try (DeckDatabase db = new DeckDatabase()) {
			serials = db.getDeckSerials(deckId);
		}
		return GameAction.of(type, new JSONObject()
				.put("deckName", deckName == null ? "Opponent's deck" : deckName)
				.put("username", AppSettings.getUsername())
				.put("counterColor", CounterColors.validOrDefault(AppSettings.getCounterColor()))
				.put("serials", new JSONArray(serials)));
	}

	/**
	 * The opponent's deck as received off the wire.
	 *
	 * @param username     the peer's chosen name, trimmed and length-clamped on arrival; {@code ""}
	 *                     when they set none, or when the peer is an older client that sends no name
	 * @param counterColor the peer's counter color as "#rrggbb", or {@code null} when they sent none
	 *                     or a malformed one; the opponent's counters are then drawn in the inverse
	 *                     of the local color (see {@link CounterColors#forOpponent})
	 */
	public record RemoteDeck(String name, String username, List<String> serials, String counterColor) {

		/** A deck from a peer that sent no counter color. */
		public RemoteDeck(String name, String username, List<String> serials) {
			this(name, username, serials, null);
		}
	}

	/**
	 * The host's lobby options.
	 *
	 * @param debug   whether the Debug menu will be usable during the match
	 * @param banlist whether decks breaking the banlist are refused, on both sides
	 * @param resets  how many times the host has tightened the deck rules — switched the banlist on
	 *                or narrowed the format; each one voids the deck either player had chosen, and a
	 *                LOBBY_READY from before it is stale
	 * @param format  the format both decks must be legal in
	 */
	public record LobbySettings(boolean debug, boolean banlist, int resets, DeckFormat format) {

		/** Settings in Standard, from a host that sends no format. */
		public LobbySettings(boolean debug, boolean banlist, int resets) {
			this(debug, banlist, resets, DeckFormat.STANDARD);
		}
	}

	public static GameAction lobbySettingsAction(LobbySettings s) {
		return GameAction.of(ActionType.LOBBY_SETTINGS, new JSONObject()
				.put("debug", s.debug())
				.put("banlist", s.banlist())
				.put("resets", s.resets())
				.put("format", s.format().id()));
	}

	public static LobbySettings settingsOf(GameAction action) {
		JSONObject p = action.payload();
		return new LobbySettings(p.optBoolean("debug", false), p.optBoolean("banlist", false),
				p.optInt("resets", 0), formatOf(p));
	}

	/** The format a payload names; one that names none — an older peer or server — is Standard. */
	public static DeckFormat formatOf(JSONObject payload) {
		return DeckFormat.parse(payload.optString("format", null));
	}

	/** Joiner → host: whether a deck is confirmed, as of the settings' {@code resets}. */
	public static GameAction lobbyReadyAction(int resets, boolean ready) {
		return GameAction.of(ActionType.LOBBY_READY, new JSONObject()
				.put("resets", resets)
				.put("ready", ready));
	}

	/**
	 * Host side of the lobby: reads the joiner until it answers Start with its deck. Each
	 * LOBBY_READY on the way is handed to {@code onReady} as {@code (resets, ready)}, on this
	 * thread; one with {@code ready} false after a Start means the joiner had no deck to send.
	 *
	 * @throws IOException if the connection drops or the joiner sends something else — including
	 *                     a DISCONNECT, whose reason is surfaced as the message
	 */
	public static RemoteDeck hostAwaitJoinerDeck(GameConnection conn,
			BiConsumer<Integer, Boolean> onReady) throws IOException {
		while (true) {
			GameAction action = conn.receiveSync();
			switch (action.type()) {
				case LOBBY_READY -> onReady.accept(action.payload().optInt("resets", 0),
						action.payload().optBoolean("ready", false));
				case DECK_LIST -> { return remoteDeckOf(action); }
				case DISCONNECT -> throw new IOException(
						action.payload().optString("reason", "Opponent left the lobby"));
				default -> throw new IOException("Unexpected " + action.type() + " in the lobby");
			}
		}
	}

	/**
	 * Joiner side of the lobby: reads the host until it presses Start and this side answers with
	 * a deck. Each LOBBY_SETTINGS is handed to {@code onSettings}; each host DECK_LIST asks
	 * {@code answer} for the reply — a DECK_LIST ends the lobby, anything else (LOBBY_READY
	 * false) declines and the wait goes on. Both run on this thread, in wire order.
	 *
	 * @return the host's deck
	 * @throws IOException as for {@link #hostAwaitJoinerDeck}
	 */
	public static RemoteDeck joinerAwaitStart(GameConnection conn, Consumer<LobbySettings> onSettings,
			Supplier<GameAction> answer) throws IOException {
		while (true) {
			GameAction action = conn.receiveSync();
			switch (action.type()) {
				case LOBBY_SETTINGS -> onSettings.accept(settingsOf(action));
				case DECK_LIST -> {
					RemoteDeck hostDeck = remoteDeckOf(action);
					GameAction reply = answer.get();
					conn.send(reply);
					if (reply.type() == ActionType.DECK_LIST) return hostDeck;
				}
				case DISCONNECT -> throw new IOException(
						action.payload().optString("reason", "Host left the lobby"));
				default -> throw new IOException("Unexpected " + action.type() + " in the lobby");
			}
		}
	}

	/**
	 * The deck a DECK_LIST-shaped payload carries (DECK_LIST, or NEW_GAME_READY between games).
	 *
	 * @throws IOException if it names no cards
	 */
	public static RemoteDeck remoteDeckOf(GameAction action) throws IOException {
		JSONArray arr = action.payload().optJSONArray("serials");
		if (arr == null || arr.isEmpty()) {
			throw new IOException("Opponent sent an empty deck");
		}
		List<String> serials = new ArrayList<>(arr.length());
		for (int i = 0; i < arr.length(); i++) serials.add(arr.getString(i));
		// Both go into the game log, so they are cleaned like chat: a line break in either would
		// start a forged log line.
		String deckName = ChatText.clean(action.payload().optString("deckName", ""), MAX_DECK_NAME_LENGTH);
		return new RemoteDeck(deckName.isEmpty() ? "Opponent's deck" : deckName,
				AppSettings.clampUsername(ChatText.clean(action.payload().optString("username", ""))), serials,
				CounterColors.validOrNull(action.payload().optString("counterColor", null)));
	}

	/** Host side: picks the seed and the coin flip, and tells the joiner along with the final settings. */
	public static long sendGameSetup(GameConnection conn, boolean hostGoesFirst, boolean debugEnabled,
			boolean banlistEnabled, DeckFormat format) {
		long seed = new Random().nextLong();
		conn.send(GameAction.of(ActionType.GAME_SETUP, new JSONObject()
				.put("seed", seed)
				.put("hostGoesFirst", hostGoesFirst)
				.put("debug", debugEnabled)
				.put("banlist", banlistEnabled)
				.put("format", format.id())));
		return seed;
	}

	/** Joiner side: blocks for the host's GAME_SETUP. */
	public static GameAction awaitGameSetup(GameConnection conn) throws IOException {
		GameAction action = conn.receiveSync();
		// A settings change sent just before Start can still be in flight; GAME_SETUP supersedes it.
		while (action.type() == ActionType.LOBBY_SETTINGS) action = conn.receiveSync();
		if (action.type() == ActionType.DISCONNECT) {
			throw new IOException(action.payload().optString("reason", "Host left the lobby"));
		}
		if (action.type() != ActionType.GAME_SETUP) {
			throw new IOException("Expected game setup, got " + action.type());
		}
		return action;
	}
}
