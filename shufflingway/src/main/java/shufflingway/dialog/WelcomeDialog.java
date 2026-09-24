package shufflingway.dialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

import scraper.AppPaths;
import scraper.CardScraper;
import shufflingway.AppSettings;
import shufflingway.DeckManager;

/**
 * Shown at launch until the player ticks "Do not show this again". Walks a new player through the
 * two things a game needs: card data in the database, then a deck built from it.
 *
 * <p>Step 2 stays greyed out while the database is empty, since the Deck Manager has nothing to
 * build from until Step 1 has run.
 */
public class WelcomeDialog extends JDialog {

	private static final String DB_URL = AppPaths.dbUrl();

	private static final Color READY_COLOR   = new Color(0x2e, 0x9e, 0x4f);
	private static final Color MISSING_COLOR = new Color(0xc6, 0x3b, 0x3b);

	private final JLabel dbDot    = new JLabel("●");
	private final JLabel dbStatus = new JLabel();

	private final JButton      fetchButton = new JButton("Fetch Card Data");
	private final JProgressBar spinner     = new JProgressBar();
	private final JLabel       fetchResult = new JLabel(" ");

	private final JLabel  step2Title  = new JLabel("Step 2 — Build a deck");
	private final JLabel  step2Body   = new JLabel();
	private final JButton deckManager = new JButton("Open Deck Manager");

	public WelcomeDialog(JFrame owner) {
		super(owner, "Getting Started", true);
		setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		setResizable(false);

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBorder(new EmptyBorder(16, 20, 12, 20));

		JLabel title = new JLabel("Welcome to Shufflingway!");
		title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 6f));
		content.add(left(title));
		content.add(Box.createVerticalStrut(6));
		content.add(left(new JLabel("<html><div style='width:380px'>Shufflingway lets you play the "
				+ "Final Fantasy Trading Card Game against the computer or another player.</div></html>")));
		content.add(Box.createVerticalStrut(14));

		// Database status
		dbDot.setFont(dbDot.getFont().deriveFont(dbDot.getFont().getSize2D() + 4f));
		JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		statusRow.add(dbDot);
		statusRow.add(dbStatus);
		content.add(left(statusRow));
		content.add(Box.createVerticalStrut(10));
		content.add(left(new JSeparator()));
		content.add(Box.createVerticalStrut(10));

		// Step 1
		JLabel step1Title = new JLabel("Step 1 — Get card data");
		step1Title.setFont(step1Title.getFont().deriveFont(Font.BOLD));
		content.add(left(step1Title));
		content.add(Box.createVerticalStrut(4));
		content.add(left(new JLabel("<html><div style='width:380px'>Download every card from the "
				+ "Square Enix card database.</div></html>")));
		content.add(Box.createVerticalStrut(6));
		spinner.setIndeterminate(true);
		spinner.setVisible(false);
		spinner.setPreferredSize(new Dimension(120, fetchButton.getPreferredSize().height));
		fetchButton.addActionListener(e -> onFetch());
		JPanel fetchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		fetchRow.add(fetchButton);
		fetchRow.add(spinner);
		content.add(left(fetchRow));
		content.add(Box.createVerticalStrut(4));
		content.add(left(fetchResult));
		content.add(Box.createVerticalStrut(14));

		// Step 2
		step2Title.setFont(step2Title.getFont().deriveFont(Font.BOLD));
		content.add(left(step2Title));
		content.add(Box.createVerticalStrut(4));
		step2Body.setText("<html><div style='width:380px'>Open the Deck Manager (File → Deck "
				+ "Manager) to build a deck from your cards and save it. Then choose File → New "
				+ "Game to play it.</div></html>");
		content.add(left(step2Body));
		content.add(Box.createVerticalStrut(6));
		deckManager.addActionListener(e -> {
			// Closed first: this dialog is modal, so a Deck Manager opened beneath it could not be used.
			dispose();
			new DeckManager(owner).setVisible(true);
		});
		JPanel dmRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		dmRow.add(deckManager);
		content.add(left(dmRow));

		// Footer
		JCheckBox dontShow = new JCheckBox("Do not show this again", !AppSettings.isShowWelcome());
		JButton close = new JButton("Close");
		close.addActionListener(e -> dispose());
		JPanel footer = new JPanel(new BorderLayout());
		footer.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
				new EmptyBorder(8, 16, 8, 16)));
		footer.add(dontShow, BorderLayout.WEST);
		footer.add(close, BorderLayout.EAST);

		// Saved however the dialog goes away: Close, the title bar, Escape, or the Deck Manager button.
		addWindowListener(new WindowAdapter() {
			@Override public void windowClosed(WindowEvent e) {
				AppSettings.setShowWelcome(!dontShow.isSelected());
				AppSettings.save();
			}
		});
		getRootPane().registerKeyboardAction(e -> dispose(),
				KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
		getRootPane().setDefaultButton(close);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(content, BorderLayout.CENTER);
		getContentPane().add(footer, BorderLayout.SOUTH);

		showCardCount(countCards());
		pack();
		setLocationRelativeTo(owner);
	}

	/** Mirrors the Card Browser's Update Cards: confirm, then run the scraper off the EDT. */
	private void onFetch() {
		// Nothing to overwrite on a first fetch, so the re-fetch warning only applies once data exists.
		if (countCards() > 0) {
			int choice = JOptionPane.showConfirmDialog(this,
					"This will re-fetch all cards from the Square Enix API and update the database.\nContinue?",
					"Fetch Card Data", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (choice != JOptionPane.YES_OPTION) return;
		}
		fetchButton.setEnabled(false);
		spinner.setVisible(true);
		fetchResult.setForeground(UIManager.getColor("Label.foreground"));
		fetchResult.setText("Fetching card data…");
		pack();
		new SwingWorker<Integer, Void>() {
			@Override protected Integer doInBackground() throws Exception {
				CardScraper.main(new String[0]);
				return countCards();
			}
			@Override protected void done() {
				int count;
				try { count = get(); } catch (Exception e) { count = countCards(); }
				spinner.setVisible(false);
				fetchButton.setEnabled(true);
				if (count > 0) {
					fetchResult.setForeground(READY_COLOR);
					fetchResult.setText(String.format("Done — the database now holds %,d cards.", count));
				} else {
					fetchResult.setForeground(MISSING_COLOR);
					fetchResult.setText("No cards were fetched. Check your internet connection and try again.");
				}
				showCardCount(count);
				pack();
			}
		}.execute();
	}

	/** Updates the status indicator and enables Step 2 once the database has cards. */
	private void showCardCount(int count) {
		boolean ready = count > 0;
		dbDot.setForeground(ready ? READY_COLOR : MISSING_COLOR);
		dbStatus.setText(ready
				? String.format("Card database loaded (%,d cards)", count)
				: "No card data loaded");
		// Set as a colour rather than via setEnabled: a disabled HTML label does not grey its text.
		Color text = UIManager.getColor(ready ? "Label.foreground" : "Label.disabledForeground");
		step2Title.setForeground(text);
		step2Body.setForeground(text);
		deckManager.setEnabled(ready);
	}

	/** Cards in the database; 0 when it or its table does not exist yet. */
	private static int countCards() {
		try (Connection c = DriverManager.getConnection(DB_URL);
		     Statement s = c.createStatement();
		     ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM cards")) {
			return rs.next() ? rs.getInt(1) : 0;
		} catch (Exception e) {
			return 0;
		}
	}

	private static <T extends JComponent> T left(T c) {
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		return c;
	}
}
