package shufflingway.menu;

import shufflingway.UpdateChecker;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.net.URL;

import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

public class About extends JDialog {

	private static final String LOGO = "/images/shufflingway.png";

	/**
	 * The logo is pixel art drawn on a 4-pixel grid, so a quarter-size scale lands each of its
	 * pixels on exactly one screen pixel. Nearest-neighbour keeps the edges hard; a smooth scale
	 * would blur them.
	 */
	private static final int LOGO_DOWNSCALE = 4;

	private final JPanel contentPanel = new JPanel();

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
		setBounds(0, 0, 350, 280);
		getContentPane().setLayout(new BorderLayout());
		ImageIcon logo = logo();
		if (logo != null) {
			JLabel logoLabel = new JLabel(logo);
			logoLabel.setBorder(new EmptyBorder(14, 0, 0, 0));
			getContentPane().add(logoLabel, BorderLayout.NORTH);
		}
		contentPanel.setLayout(new FlowLayout());
		contentPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
		getContentPane().add(contentPanel, BorderLayout.CENTER);
		{
			JPanel buttonPane = new JPanel();
			buttonPane.setLayout(new FlowLayout(FlowLayout.RIGHT));
			JLabel lblNewLabel = new JLabel("<html><div style='text-align: center;'>Author: Andrew Rockefeller © 2023-2026<br/>" +
			"Pixel NES font by Neale Davidson<br/>" +
			"Non-API card images sourced from materiahunter.com<br/><br/>" +
			"Version: " + UpdateChecker.currentVersion() + "<br/><br/>" +
			"This is an unofficial fan tool — it is not affiliated with Square Enix or Hobby Japan.</div></html>");
			lblNewLabel.setHorizontalAlignment(SwingConstants.CENTER);
			getContentPane().add(lblNewLabel, BorderLayout.CENTER);
		}
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
