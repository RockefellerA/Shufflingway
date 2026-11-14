package shufflingway.menu;

import shufflingway.UpdateChecker;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.net.URL;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

/** Help → About: the logo, author, credits, version and disclaimer, and the third-party notices. */
public class About extends JDialog {

	private static final String LOGO = "/images/shufflingway.png";

	/**
	 * The logo is pixel art drawn on a 4-pixel grid, so a quarter-size scale lands each of its
	 * pixels on exactly one screen pixel. Nearest-neighbour keeps the edges hard; a smooth scale
	 * would blur them.
	 */
	private static final int LOGO_DOWNSCALE = 4;

	/**
	 * Launch the application.
	 */
	public static void main(String[] args) {
		try {
			About dialog = new About();
			dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
			dialog.setVisible(true);
		} catch (Exception e) {
		}
	}

	/**
	 * Create the dialog.
	 */
	public About() {
		setTitle("About Shufflingway");
		setAlwaysOnTop(true);
		setResizable(false);
		getRootPane().registerKeyboardAction(
				e -> dispose(),
				KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
				JComponent.WHEN_IN_FOCUSED_WINDOW);
		setIconImage(Toolkit.getDefaultToolkit().getImage(About.class.getResource(LOGO)));
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);

		JPanel content = new JPanel(new BorderLayout(0, 12));
		content.setBorder(new EmptyBorder(14, 20, 10, 20));
		ImageIcon logo = logo();
		if (logo != null) {
			content.add(new JLabel(logo), BorderLayout.NORTH);
		}
		// Line breaks are explicit: pack() sizes the dialog to the longest line.
		JLabel text = new JLabel("<html><div style='text-align: center;'>Author: Andrew Rockefeller © 2023-2026<br/>" +
				"Pixel Emulator font by Neale Davidson<br/>" +
				"Final Fantasy 3/6 Font by Veylon<br/>" +
				"Non-API card images sourced from materiahunter.com<br/><br/>" +
				"Version: " + UpdateChecker.currentVersion() + "<br/><br/>" +
				"This is an unofficial fan tool — it is not affiliated with<br/>" +
				"Square Enix or Hobby Japan.</div></html>");
		text.setHorizontalAlignment(SwingConstants.CENTER);
		content.add(text, BorderLayout.CENTER);

		JButton notices = new JButton("Third-party notices…");
		notices.setToolTipText("The licences of the libraries and fonts Shufflingway ships with");
		notices.addActionListener(e -> new ThirdPartyNotices(this).setVisible(true));
		JButton ok = new JButton("OK");
		ok.addActionListener(e -> dispose());
		getRootPane().setDefaultButton(ok);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER));
		buttons.add(notices);
		buttons.add(ok);
		content.add(buttons, BorderLayout.SOUTH);

		setContentPane(content);
		pack();
	}

	/** The app logo at its native pixel-art size, or null if the image is missing. */
	private static ImageIcon logo() {
		URL url = About.class.getResource(LOGO);
		if (url == null) return null;
		ImageIcon full = new ImageIcon(url);
		return new ImageIcon(full.getImage().getScaledInstance(
				full.getIconWidth() / LOGO_DOWNSCALE, full.getIconHeight() / LOGO_DOWNSCALE,
				Image.SCALE_REPLICATE));
	}

}
