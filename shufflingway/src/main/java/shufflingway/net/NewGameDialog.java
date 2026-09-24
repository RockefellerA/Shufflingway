package shufflingway.net;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Random;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import org.json.JSONObject;

import shufflingway.dialog.DeckChooserPanel;

/**
 * Starting a new game on a multiplayer connection that is already open, without going back
 * through the lobby. One player asks from File → New Game; both then pick a deck and click
 * Ready, and once both are ready the host's Ready button becomes Start Game.
 *
 * <p>Order on the wire, over the connection's running reader (so every step arrives through
 * {@link #onAction} rather than a blocking read):
 * <pre>
 *   either → other : NEW_GAME_REQUEST (the asker opens this dialog as it sends)
 *   each   → other : NEW_GAME_READY   (that player's deck, sent on Ready)
 *   host   → joiner: GAME_SETUP       (seed + coin flip + debug setting, on Start Game)
 *   either → other : NEW_GAME_CANCEL  (at any point before Start; both go back to the game)
 * </pre>
 *
 * <p>Either player may be the one asking; the host is always the one who starts, exactly as in
 * the lobby, because the host authors the seed and the coin flip.
 *
 * <p>The new game starts from {@link #onStart} <em>inside</em> the handler for the message or
 * click that starts it, not later. The peer's first actions in the new game are already queued
 * behind GAME_SETUP, and deferring the start would let them run against the old game.
 */
public class NewGameDialog extends JDialog {

	private final MatchSetup           current;
	private final Consumer<GameAction> send;
	private final Consumer<MatchSetup> onStart;
	private final String               opponentName;

	private final DeckChooserPanel deckChooser;
	private final JLabel           statusLabel;
	private final JButton          readyBtn;

	/** The deck this player readied with, or -1 before Ready. */
	private int localDeckId = -1;
	/** The opponent's deck, once they have clicked Ready. */
	private LobbyExchange.RemoteDeck remoteDeck;
	/** Set once the dialog has started, cancelled or been cancelled, so nothing acts twice. */
	private boolean finished;

	/**
	 * @param current          the match being replaced: who hosts, the opponent's name, and the
	 *                         debug setting, which the new game keeps
	 * @param requestedLocally true when this player asked; false when the opponent did
	 * @param send             delivers a message to the opponent
	 * @param onStart          starts the new game; run once, if both players go through with it
	 */
	public NewGameDialog(Frame owner, MatchSetup current, boolean requestedLocally,
	                     Consumer<GameAction> send, Consumer<MatchSetup> onStart) {
		super(owner, "New Game", true);
		this.current      = current;
		this.send         = send;
		this.onStart      = onStart;
		String name       = current.remoteUsername();
		this.opponentName = name == null || name.isBlank() ? "Your opponent" : name;

		setResizable(false);
		setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override public void windowClosing(WindowEvent e) { cancel(); }
		});

		JPanel content = new JPanel(new BorderLayout(10, 10));
		content.setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));

		String message = requestedLocally
				? "Select a deck for the new game and click 'Ready'."
				: opponentName + " wants to start a new game. Select a deck and click 'Ready', "
						+ "or click 'Cancel' to go back to the game in progress.";
		JLabel messageLabel = new JLabel("<html><div style='width:280px'>" + message + "</div></html>");
		content.add(messageLabel, BorderLayout.NORTH);

		deckChooser = new DeckChooserPanel("Your Deck", this::refresh);
		statusLabel = new JLabel(" ", SwingConstants.CENTER);
		statusLabel.setFont(new Font("Dialog", Font.PLAIN, 12));

		JPanel centre = new JPanel(new BorderLayout(0, 6));
		centre.add(deckChooser, BorderLayout.CENTER);
		centre.add(statusLabel, BorderLayout.SOUTH);
		content.add(centre, BorderLayout.CENTER);

		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.addActionListener(e -> cancel());
		readyBtn = new JButton("Ready");
		readyBtn.addActionListener(e -> onReadyClicked());

		JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		btnRow.add(cancelBtn);
		btnRow.add(readyBtn);
		content.add(btnRow, BorderLayout.SOUTH);

		setContentPane(content);
		refresh();
		pack();
		setMinimumSize(new Dimension(360, 340));
		setLocationRelativeTo(owner);
	}

	/**
	 * Hands this dialog a message from the opponent. Returns whether it was one of the new-game
	 * messages this dialog owns; anything else is left to the caller.
	 */
	public boolean onAction(GameAction action) {
		switch (action.type()) {
			case NEW_GAME_READY -> {
				if (finished) return true;
				try {
					remoteDeck = LobbyExchange.remoteDeckOf(action);
				} catch (IOException ex) {
					statusLabel.setText(ex.getMessage());
					return true;
				}
				refresh();
			}
			case NEW_GAME_CANCEL -> {
				if (finished) return true;
				finished = true;
				dispose();
				JOptionPane.showMessageDialog(getOwner(),
						opponentName + " cancelled the new game. Back to the game in progress.",
						"New Game", JOptionPane.INFORMATION_MESSAGE);
			}
			// Both asked at once: each already has this dialog open, which is all a request asks for.
			case NEW_GAME_REQUEST -> { }
			case GAME_SETUP -> {
				if (finished || current.localIsHost() || localDeckId < 0 || remoteDeck == null) return true;
				JSONObject p = action.payload();
				finish(new MatchSetup(localDeckId, remoteDeck.serials(), remoteDeck.name(),
						remoteDeck.username(), p.getLong("seed"), false,
						p.getBoolean("hostGoesFirst"), p.optBoolean("debug", false)));
			}
			default -> { return false; }
		}
		return true;
	}

	/** The connection dropped: there is no one left to start a game with. */
	public void connectionLost() {
		finished = true;
		dispose();
	}

	private void onReadyClicked() {
		if (finished) return;
		if (localDeckId >= 0) {
			if (current.localIsHost() && remoteDeck != null) startAsHost();
			return;
		}
		int    deckId   = deckChooser.getSelectedDeckId();
		String deckName = deckChooser.getSelectedDeckName();
		if (deckId < 0) return;
		try {
			markReady(LobbyExchange.deckListAction(ActionType.NEW_GAME_READY, deckId, deckName), deckId);
		} catch (SQLException ex) {
			statusLabel.setText("Could not read the deck: " + ex.getMessage());
		}
	}

	/** Records this player as ready with {@code deckId} and tells the opponent, deck included. */
	void markReady(GameAction readyAction, int deckId) {
		localDeckId = deckId;
		send.accept(readyAction);
		refresh();
	}

	/** Host, both ready: authors the seed and the coin flip, tells the joiner, and starts. */
	void startAsHost() {
		boolean hostGoesFirst = new Random().nextBoolean();
		long    seed          = new Random().nextLong();
		boolean debug         = current.debugEnabled();
		send.accept(GameAction.of(ActionType.GAME_SETUP, new JSONObject()
				.put("seed", seed)
				.put("hostGoesFirst", hostGoesFirst)
				.put("debug", debug)));
		finish(new MatchSetup(localDeckId, remoteDeck.serials(), remoteDeck.name(),
				remoteDeck.username(), seed, true, hostGoesFirst, debug));
	}

	private void finish(MatchSetup setup) {
		finished = true;
		dispose();
		onStart.accept(setup);
	}

	void cancel() {
		if (finished) return;
		finished = true;
		send.accept(GameAction.of(ActionType.NEW_GAME_CANCEL));
		dispose();
	}

	/** Brings the button, the deck list and the status line in line with who is ready. */
	private void refresh() {
		boolean localReady  = localDeckId >= 0;
		boolean remoteReady = remoteDeck != null;
		deckChooser.setEnabled(!localReady);
		if (!localReady) {
			readyBtn.setText("Ready");
			readyBtn.setEnabled(deckChooser.getSelectedDeckId() >= 0);
			statusLabel.setText(remoteReady ? opponentName + " is ready." : " ");
		} else if (!remoteReady) {
			readyBtn.setText("Ready");
			readyBtn.setEnabled(false);
			statusLabel.setText("Waiting for " + opponentName + "…");
		} else if (current.localIsHost()) {
			readyBtn.setText("Start Game");
			readyBtn.setEnabled(true);
			statusLabel.setText("Both players are ready.");
		} else {
			readyBtn.setText("Ready");
			readyBtn.setEnabled(false);
			statusLabel.setText("Waiting for host to start…");
		}
	}

	/** Test hooks. */
	boolean isFinished()   { return finished; }
	String  readyLabel()   { return readyBtn.getText(); }
	boolean readyEnabled() { return readyBtn.isEnabled(); }
}
