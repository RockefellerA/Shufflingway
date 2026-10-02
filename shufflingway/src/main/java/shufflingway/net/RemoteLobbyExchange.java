package shufflingway.net;

import java.io.IOException;
import java.sql.SQLException;
import java.util.function.Consumer;

import org.json.JSONObject;

import shufflingway.AppSettings;

/**
 * The client's half of a lobby hosted on a dedicated server, where neither player hosts: the
 * server owns the lobby's settings, pairs the two players, picks the seed and the coin flip, and
 * then relays every in-game action between them unchanged.
 *
 * <p>The in-game protocol is the LAN one. The server assigns one client the host <em>seat</em>
 * ({@link MatchSetup#localIsHost()}), which keys the shuffle streams, the desync checksums and
 * who authors a later NEW_GAME's GAME_SETUP, exactly as hosting a LAN lobby does.
 *
 * <p>Order on the wire:
 * <pre>
 *   client → server : HELLO          (version, card checksum, username, lobby name)
 *   server → client : HELLO          (accepted) or DISCONNECT (rejected, with a reason)
 *   server → client : LOBBY_SETTINGS (on joining, and again whenever the server changes one)
 *   client → server : DECK_LIST      (the confirmed deck, plus the "resets" it was confirmed
 *                                     under; sent again after a reset voids it)
 *   server → client : DECK_LIST      (the opponent's deck, once both are in)
 *   server → client : GAME_SETUP     (seed, coin flip, final settings, plus "seat")
 * </pre>
 *
 * <p>There is no server yet; this is the contract one has to meet.
 */
public final class RemoteLobbyExchange {

	private RemoteLobbyExchange() {}

	/** GAME_SETUP's {@code "seat"} value for the client given the host seat. */
	static final String SEAT_HOST = "host";

	/**
	 * Client → server: who this client is and which lobby it wants.
	 *
	 * @param lobby the lobby to join, or {@code ""} for whichever the server pairs this client into
	 */
	public static GameAction helloAction(String version, String cardChecksum, String lobby) {
		return GameAction.of(ActionType.HELLO, new JSONObject()
				.put("version", version)
				.put("cardChecksum", cardChecksum)
				.put("username", AppSettings.getUsername())
				.put("lobby", lobby == null ? "" : lobby.trim()));
	}

	/**
	 * Client → server: the confirmed deck, as of the LOBBY_SETTINGS {@code resets} it was confirmed
	 * under. The server ignores one from before its latest reset.
	 */
	public static GameAction deckAction(int deckId, String deckName, int resets) throws SQLException {
		GameAction deck = LobbyExchange.deckListAction(deckId, deckName);
		deck.payload().put("resets", resets);
		return deck;
	}

	/**
	 * Reads the server's answer to {@link #helloAction}.
	 *
	 * @throws IOException carrying the server's reason if it rejected this client, or if it answered
	 *                     with anything else
	 */
	public static void awaitWelcome(GameConnection conn) throws IOException {
		GameAction response = conn.receiveSync();
		if (response.type() == ActionType.DISCONNECT) {
			throw new IOException(response.payload().optString("reason", "Rejected by server"));
		}
		if (response.type() != ActionType.HELLO) {
			throw new IOException("Unexpected " + response.type() + " from server");
		}
	}

	/** What the server sent to start the match: the opponent's deck and the GAME_SETUP. */
	public record RemoteMatch(LobbyExchange.RemoteDeck opponent, GameAction gameSetup) {

		/** The match as this client plays it, with {@code localDeckId} as the deck it confirmed. */
		public MatchSetup toSetup(int localDeckId) {
			JSONObject p = gameSetup.payload();
			return new MatchSetup(localDeckId, opponent.serials(), opponent.name(), opponent.username(),
					p.getLong("seed"),
					SEAT_HOST.equals(p.optString("seat")),
					p.getBoolean("hostGoesFirst"),
					p.optBoolean("debug", false),
					p.optBoolean("banlist", false));
		}
	}

	/**
	 * Reads the server until it starts the match. Each LOBBY_SETTINGS is handed to
	 * {@code onSettings} on this thread, in wire order; the deck is sent from outside, with
	 * {@link #deckAction}, whenever the player confirms one.
	 *
	 * @throws IOException if the connection drops, the server sends a DISCONNECT (its reason is the
	 *                     message), starts without having sent the opponent's deck, or sends
	 *                     anything else
	 */
	public static RemoteMatch awaitMatch(GameConnection conn, Consumer<LobbyExchange.LobbySettings> onSettings)
			throws IOException {
		LobbyExchange.RemoteDeck opponent = null;
		while (true) {
			GameAction action = conn.receiveSync();
			switch (action.type()) {
				case LOBBY_SETTINGS -> onSettings.accept(LobbyExchange.settingsOf(action));
				case DECK_LIST -> opponent = LobbyExchange.remoteDeckOf(action);
				case GAME_SETUP -> {
					if (opponent == null) throw new IOException("Server started the match without an opponent's deck");
					return new RemoteMatch(opponent, action);
				}
				case PING -> { }
				case DISCONNECT -> throw new IOException(
						action.payload().optString("reason", "Server closed the lobby"));
				default -> throw new IOException("Unexpected " + action.type() + " in the lobby");
			}
		}
	}
}
