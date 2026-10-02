package shufflingway.net;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import shufflingway.AppSettings;

/**
 * The client's half of a lobby on the relay server ({@code shufflingway.server.RelayServer}),
 * where neither player hosts: the server lists the open lobbies, lets a player create one (named,
 * optionally password-protected) or join one, picks the seed and the coin flip once both decks are
 * in, and then relays every in-game action between the two players unchanged.
 *
 * <p>The in-game protocol is the LAN one. The server gives the lobby's creator the host
 * <em>seat</em> ({@link MatchSetup#localIsHost()}), which keys the shuffle streams, the desync
 * checksums and who authors a later NEW_GAME's GAME_SETUP, exactly as hosting a LAN lobby does.
 *
 * <p>Order on the wire:
 * <pre>
 *   client → server : HELLO          (version, card checksum, username)
 *   server → client : HELLO          (accepted) or DISCONNECT (rejected, with a reason)
 *   client → server : LOBBY_LIST     (any number of times while not in a lobby)
 *   server → client : LOBBY_LIST     (the lobbies this client could join)
 *   client → server : LOBBY_CREATE or LOBBY_JOIN
 *   server → client : LOBBY_SETTINGS (now in the lobby) or LOBBY_ERROR (still browsing)
 *   client → server : DECK_LIST      (the confirmed deck, plus the "resets" it was confirmed under)
 *   server → client : DECK_LIST      (the opponent's deck, once both are in)
 *   server → client : GAME_SETUP     (seed, coin flip, final settings, "seat", "matchId")
 *   ...then each side's in-game actions, relayed to the other as they arrive.
 * </pre>
 */
public final class RemoteLobbyExchange {

	private RemoteLobbyExchange() {}

	/** GAME_SETUP's {@code "seat"} value for the client given the host seat. */
	public static final String SEAT_HOST   = "host";
	public static final String SEAT_JOINER = "joiner";

	public static final int LOBBY_NAME_MAX_LENGTH = 24;
	public static final int PASSWORD_MAX_LENGTH   = 32;

	/** Client → server: who this client is, so the server can list it and pair it with a match. */
	public static GameAction helloAction(String version, String cardChecksum) {
		return GameAction.of(ActionType.HELLO, new JSONObject()
				.put("version", version)
				.put("cardChecksum", cardChecksum)
				.put("username", AppSettings.getUsername()));
	}

	/** Client → server: asks for the lobbies this client could join. */
	public static GameAction listAction() {
		return GameAction.of(ActionType.LOBBY_LIST);
	}

	/** Client → server: opens a lobby; {@code password} may be blank for an open one. */
	public static GameAction createAction(String name, String password, boolean banlist, boolean debug) {
		return GameAction.of(ActionType.LOBBY_CREATE, new JSONObject()
				.put("name", name.trim())
				.put("password", password)
				.put("banlist", banlist)
				.put("debug", debug));
	}

	/** Client → server: takes the second seat in the lobby called {@code name}. */
	public static GameAction joinAction(String name, String password) {
		return GameAction.of(ActionType.LOBBY_JOIN, new JSONObject()
				.put("name", name)
				.put("password", password));
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

	/** One joinable lobby, as the server lists it. */
	public record LobbyInfo(String name, String creator, boolean hasPassword, boolean banlist, boolean debug) {}

	public static List<LobbyInfo> lobbiesOf(GameAction action) {
		JSONArray arr = action.payload().optJSONArray("lobbies");
		List<LobbyInfo> out = new ArrayList<>();
		if (arr == null) return out;
		for (int i = 0; i < arr.length(); i++) {
			JSONObject o = arr.optJSONObject(i);
			if (o == null) continue;
			out.add(new LobbyInfo(o.optString("name"), o.optString("creator"),
					o.optBoolean("password"), o.optBoolean("banlist"), o.optBoolean("debug")));
		}
		return out;
	}

	/** What the server says while this client browses and waits; each runs on the reading thread. */
	public interface LobbyListener {
		void onLobbies(List<LobbyInfo> lobbies);
		/** A create, join or deck was refused; the connection stays open. */
		void onError(String reason);
		/** The server's lobby options: the first one means this client is now in a lobby. */
		void onSettings(LobbyExchange.LobbySettings settings);
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

		/** The server's id for this game, for its logs; {@code ""} if it sent none. */
		public String matchId() {
			return gameSetup.payload().optString("matchId", "");
		}
	}

	/**
	 * Reads the server until it starts the match, handing each lobby list, refusal and settings
	 * change to {@code listener} on this thread, in wire order. Requests (list, create, join, deck)
	 * are sent from outside, whenever the player makes them.
	 *
	 * @throws IOException if the connection drops, the server sends a DISCONNECT (its reason is the
	 *                     message), starts without having sent the opponent's deck, or sends
	 *                     anything else
	 */
	public static RemoteMatch awaitMatch(GameConnection conn, LobbyListener listener) throws IOException {
		LobbyExchange.RemoteDeck opponent = null;
		while (true) {
			GameAction action = conn.receiveSync();
			switch (action.type()) {
				case LOBBY_LIST -> listener.onLobbies(lobbiesOf(action));
				case LOBBY_ERROR -> listener.onError(action.payload().optString("reason", "Refused by server"));
				case LOBBY_SETTINGS -> listener.onSettings(LobbyExchange.settingsOf(action));
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
