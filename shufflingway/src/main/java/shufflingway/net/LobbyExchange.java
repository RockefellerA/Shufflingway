package shufflingway.net;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import org.json.JSONArray;
import org.json.JSONObject;

import scraper.DeckDatabase;
import shufflingway.AppSettings;

/**
 * The post-handshake half of the lobby: swapping decks and agreeing on the shuffle seed and
 * who moves first.
 *
 * <p>Runs on the lobby's background thread using {@link GameConnection#receiveSync}, before
 * the reader thread starts, so it reads as straight-line code rather than a callback state
 * machine. Both sides send their own deck first and only then read the other's, so neither
 * blocks waiting for a message the peer has not been told to send yet.
 *
 * <p>Order on the wire:
 * <pre>
 *   host → joiner : LOBBY_SETTINGS (on connect, and again whenever the host changes one)
 *   host → joiner : DECK_LIST      (once the host has picked a deck and pressed Start)
 *   joiner → host : DECK_LIST      (as soon as the joiner connects)
 *   host → joiner : GAME_SETUP     (seed + coin flip + final settings; host-authored)
 * </pre>
 */
public final class LobbyExchange {

	private LobbyExchange() {}

	/**
	 * Reads {@code deckId} out of the local deck database as a wire-ready serial list, tagged with
	 * the local player's username so the peer can label us on their board.
	 */
	public static GameAction deckListAction(int deckId, String deckName) throws SQLException {
		List<String> serials;
		try (DeckDatabase db = new DeckDatabase()) {
			serials = db.getDeckSerials(deckId);
		}
		return GameAction.of(ActionType.DECK_LIST, new JSONObject()
				.put("deckName", deckName == null ? "Opponent's deck" : deckName)
				.put("username", AppSettings.getUsername())
				.put("serials", new JSONArray(serials)));
	}

	/**
	 * The opponent's deck as received off the wire.
	 *
	 * @param username the peer's chosen name, trimmed and length-clamped on arrival; {@code ""}
	 *                 when they set none, or when the peer is an older client that sends no name
	 */
	public record RemoteDeck(String name, String username, List<String> serials) {}

	/**
	 * Blocks for the peer's DECK_LIST.
	 *
	 * @throws IOException if the connection drops or the peer sends something else — including
	 *                     a DISCONNECT, whose reason is surfaced as the message
	 */
	public static RemoteDeck awaitDeckList(GameConnection conn) throws IOException {
		return awaitDeckList(conn, debug -> {});
	}

	/**
	 * Joiner side of {@link #awaitDeckList(GameConnection)}: the host's LOBBY_SETTINGS may arrive
	 * any number of times before its deck list, and each is handed to {@code onDebugSetting}
	 * (on this background thread) rather than taken for the deck list.
	 */
	public static RemoteDeck awaitDeckList(GameConnection conn, Consumer<Boolean> onDebugSetting)
			throws IOException {
		GameAction action = conn.receiveSync();
		while (action.type() == ActionType.LOBBY_SETTINGS) {
			onDebugSetting.accept(action.payload().optBoolean("debug", false));
			action = conn.receiveSync();
		}
		if (action.type() == ActionType.DISCONNECT) {
			throw new IOException(action.payload().optString("reason", "Opponent left the lobby"));
		}
		if (action.type() != ActionType.DECK_LIST) {
			throw new IOException("Expected a deck list, got " + action.type());
		}
		JSONArray arr = action.payload().optJSONArray("serials");
		if (arr == null || arr.isEmpty()) {
			throw new IOException("Opponent sent an empty deck");
		}
		List<String> serials = new ArrayList<>(arr.length());
		for (int i = 0; i < arr.length(); i++) serials.add(arr.getString(i));
		return new RemoteDeck(action.payload().optString("deckName", "Opponent's deck"),
				AppSettings.clampUsername(action.payload().optString("username", "")), serials);
	}

	/** The host's lobby options as they stand, for the joiner to display before Start. */
	public static GameAction lobbySettingsAction(boolean debugEnabled) {
		return GameAction.of(ActionType.LOBBY_SETTINGS, new JSONObject().put("debug", debugEnabled));
	}

	/** Host side: picks the seed and the coin flip, and tells the joiner along with the final settings. */
	public static long sendGameSetup(GameConnection conn, boolean hostGoesFirst, boolean debugEnabled) {
		long seed = new Random().nextLong();
		conn.send(GameAction.of(ActionType.GAME_SETUP, new JSONObject()
				.put("seed", seed)
				.put("hostGoesFirst", hostGoesFirst)
				.put("debug", debugEnabled)));
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
