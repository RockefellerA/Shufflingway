package shufflingway.server;

import org.json.JSONArray;
import org.json.JSONObject;
import shufflingway.net.ActionType;
import shufflingway.net.GameAction;
import shufflingway.net.LobbyExchange;
import shufflingway.net.RemoteLobbyExchange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Every lobby and match on the server, behind one lock.
 *
 * <p>A lobby is listed from the moment its creator opens it until a second player takes the other
 * seat; it is then unlisted, but keeps its name, until both players have confirmed a deck and the
 * match starts. From then on it is a {@link Match}, filed under its id, and the two sessions relay
 * to each other directly (see {@link ClientSession#peer()}) without consulting the registry.
 *
 * <p>State changes happen under the lock; the messages they produce are sent after it is released,
 * so one slow client cannot hold up every other lobby on the server.
 */
final class LobbyRegistry {

    static final int MAX_LOBBIES = 256;

    private static final class Lobby {
        final String name;
        final String password;
        final boolean banlist;
        final boolean debug;
        final ClientSession creator;
        ClientSession joiner;
        JSONObject creatorDeck;
        JSONObject joinerDeck;

        Lobby(String name, String password, boolean banlist, boolean debug, ClientSession creator) {
            this.name = name;
            this.password = password;
            this.banlist = banlist;
            this.debug = debug;
            this.creator = creator;
        }

        GameAction settings() {
            return LobbyExchange.lobbySettingsAction(new LobbyExchange.LobbySettings(debug, banlist, 0));
        }
    }

    /** A started game: the creator holds the host seat. */
    record Match(UUID id, String lobbyName, ClientSession host, ClientSession joiner) {}

    private final Map<String, Lobby> lobbies = new HashMap<>();
    private final Map<ClientSession, Lobby> lobbyOf = new HashMap<>();
    private final Map<UUID, Match> matches = new HashMap<>();
    private final Map<ClientSession, Match> matchOf = new HashMap<>();
    private final Random random = new SecureRandom();
    private final Consumer<String> log;

    LobbyRegistry(Consumer<String> log) {
        this.log = log;
    }

    private static String key(String lobbyName) {
        return lobbyName.toLowerCase(Locale.ROOT);
    }

    /**
     * Why {@code joiner} cannot play {@code creator}, or {@code null} if they can. The same rule a
     * LAN host applies: matching versions (unless either is a dev build) and card databases.
     */
    static String incompatibility(ClientSession creator, ClientSession joiner) {
        boolean dev = "dev".equals(creator.version()) || "dev".equals(joiner.version());
        if (!dev && !creator.version().equals(joiner.version())) {
            return "Version mismatch (lobby: " + creator.version() + ", you: " + joiner.version() + ")";
        }
        if (!creator.cardChecksum().equals(joiner.cardChecksum())) {
            return "Card database mismatch — re-sync card data and try again";
        }
        return null;
    }

    private static GameAction error(String reason) {
        return GameAction.of(ActionType.LOBBY_ERROR, new JSONObject().put("reason", reason));
    }

    // ---------------------------------------------------------------------------------------------
    // Browsing
    // ---------------------------------------------------------------------------------------------

    /** The lobbies {@code s} could join: waiting for a second player, and compatible with it. */
    GameAction listFor(ClientSession s) {
        JSONArray out = new JSONArray();
        synchronized (this) {
            lobbies.values().stream()
                    .filter(l -> l.joiner == null && l.creator != s && incompatibility(l.creator, s) == null)
                    .sorted((a, b) -> a.name.compareToIgnoreCase(b.name))
                    .forEach(l -> out.put(new JSONObject()
                            .put("name", l.name)
                            .put("creator", l.creator.username())
                            .put("password", !l.password.isEmpty())
                            .put("banlist", l.banlist)
                            .put("debug", l.debug)));
        }
        return GameAction.of(ActionType.LOBBY_LIST, new JSONObject().put("lobbies", out));
    }

    synchronized boolean inLobby(ClientSession s) {
        return lobbyOf.containsKey(s);
    }

    /** Opens a lobby with {@code s} in its first seat; answers LOBBY_SETTINGS or LOBBY_ERROR. */
    GameAction create(ClientSession s, String name, String password, boolean banlist, boolean debug) {
        Lobby lobby;
        synchronized (this) {
            if (lobbyOf.containsKey(s) || matchOf.containsKey(s)) return error("You are already in a lobby");
            if (lobbies.containsKey(key(name))) return error("A lobby called \"" + name + "\" already exists");
            if (lobbies.size() >= MAX_LOBBIES) return error("The server has too many open lobbies; try again later");
            lobby = new Lobby(name, password, banlist, debug, s);
            lobbies.put(key(name), lobby);
            lobbyOf.put(s, lobby);
        }
        log.accept(s + " opened lobby '" + name + "'" + (password.isEmpty() ? "" : " (password)"));
        return lobby.settings();
    }

    /**
     * Seats {@code s} opposite a lobby's creator, which unlists the lobby; answers LOBBY_SETTINGS
     * or LOBBY_ERROR. A wrong password counts against {@code s} (see
     * {@link ClientSession#MAX_PASSWORD_FAILURES}).
     */
    GameAction join(ClientSession s, String name, String password) {
        Lobby lobby;
        synchronized (this) {
            if (lobbyOf.containsKey(s) || matchOf.containsKey(s)) return error("You are already in a lobby");
            lobby = lobbies.get(key(name));
            if (lobby == null || lobby.creator == s) return error("No open lobby called \"" + name + "\"");
            if (lobby.joiner != null) return error("That lobby is full");
            if (!MessageDigest.isEqual(lobby.password.getBytes(StandardCharsets.UTF_8),
                    password.getBytes(StandardCharsets.UTF_8))) {
                s.passwordFailed();
                return error("Wrong password");
            }
            String why = incompatibility(lobby.creator, s);
            if (why != null) return error(why);
            lobby.joiner = s;
            lobbyOf.put(s, lobby);
        }
        log.accept(s + " joined lobby '" + lobby.name + "'");
        return lobby.settings();
    }

    // ---------------------------------------------------------------------------------------------
    // Starting a match
    // ---------------------------------------------------------------------------------------------

    /**
     * Records {@code s}'s deck, and starts the match once both are in. The deck replaces any
     * earlier one, so a player can change their mind until the opponent's deck arrives.
     */
    void submitDeck(ClientSession s, JSONObject deck) {
        Match match;
        JSONObject hostDeck;
        JSONObject joinerDeck;
        Lobby lobby;
        synchronized (this) {
            lobby = lobbyOf.get(s);
            if (lobby == null) {
                match = null; hostDeck = null; joinerDeck = null;
            } else {
                // The server never re-issues its settings, so every deck is confirmed under resets 0.
                if (lobby.creator == s) lobby.creatorDeck = deck;
                else lobby.joinerDeck = deck;
                if (lobby.joiner == null || lobby.creatorDeck == null || lobby.joinerDeck == null) return;

                match = new Match(UUID.randomUUID(), lobby.name, lobby.creator, lobby.joiner);
                hostDeck = lobby.creatorDeck;
                joinerDeck = lobby.joinerDeck;
                lobbies.remove(key(lobby.name));
                lobbyOf.remove(match.host());
                lobbyOf.remove(match.joiner());
                matches.put(match.id(), match);
                matchOf.put(match.host(), match);
                matchOf.put(match.joiner(), match);
                match.host().pairWith(match.joiner());
                match.joiner().pairWith(match.host());
            }
        }
        if (match == null) {
            s.send(error("Join or create a lobby before choosing a deck"));
            return;
        }
        long seed = random.nextLong();
        boolean hostGoesFirst = random.nextBoolean();
        ClientSession.sendToBoth(
                match.host(), List.of(
                        deckOf(joinerDeck),
                        setup(match, seed, hostGoesFirst, lobby, RemoteLobbyExchange.SEAT_HOST)),
                match.joiner(), List.of(
                        deckOf(hostDeck),
                        setup(match, seed, hostGoesFirst, lobby, RemoteLobbyExchange.SEAT_JOINER)));
        log.accept("match " + match.id() + " started in lobby '" + match.lobbyName() + "': "
                + match.host() + " (host) vs " + match.joiner());
    }

    private static GameAction deckOf(JSONObject deck) {
        JSONObject p = new JSONObject(deck.toString());
        p.remove("resets");
        return GameAction.of(ActionType.DECK_LIST, p);
    }

    private static GameAction setup(Match m, long seed, boolean hostGoesFirst, Lobby lobby, String seat) {
        return GameAction.of(ActionType.GAME_SETUP, new JSONObject()
                .put("seed", seed)
                .put("hostGoesFirst", hostGoesFirst)
                .put("debug", lobby.debug)
                .put("banlist", lobby.banlist)
                .put("seat", seat)
                .put("matchId", m.id().toString()));
    }

    // ---------------------------------------------------------------------------------------------
    // Leaving
    // ---------------------------------------------------------------------------------------------

    /**
     * Forgets {@code s}, which has disconnected, and hangs up whoever it leaves behind. A joiner
     * leaving a lobby before the start re-lists it for its creator; a creator leaving closes it.
     *
     * @param saidGoodbye whether {@code s} already told its opponent it was leaving, in which case
     *                    the opponent is not told a second time
     */
    void leave(ClientSession s, boolean saidGoodbye) {
        ClientSession hangUp = null;
        String reason = null;
        synchronized (this) {
            Match match = matchOf.remove(s);
            if (match != null) {
                matches.remove(match.id());
                hangUp = match.host() == s ? match.joiner() : match.host();
                matchOf.remove(hangUp);
                reason = "Opponent disconnected";
                log.accept("match " + match.id() + " ended: " + s + " left");
            }
            Lobby lobby = lobbyOf.remove(s);
            if (lobby != null) {
                if (lobby.creator == s) {
                    lobbies.remove(key(lobby.name));
                    if (lobby.joiner != null) {
                        lobbyOf.remove(lobby.joiner);
                        hangUp = lobby.joiner;
                        reason = "The lobby's creator left";
                    }
                    log.accept("lobby '" + lobby.name + "' closed: " + s + " left");
                } else {
                    lobby.joiner = null;
                    lobby.joinerDeck = null;
                    log.accept(s + " left lobby '" + lobby.name + "'; it is open again");
                }
            }
        }
        if (hangUp != null) hangUp.hangUp(saidGoodbye ? null : reason);
    }

    synchronized int lobbyCount() {
        return lobbies.size();
    }

    synchronized int matchCount() {
        return matches.size();
    }
}
