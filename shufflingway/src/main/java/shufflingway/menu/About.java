package shufflingway.menu;

import shufflingway.UpdateChecker;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;

import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

public class About extends JDialog {

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
		setIconImage(Toolkit.getDefaultToolkit().getImage(About.class.getResource("/resources/shufflingway.png")));
		setBounds(0, 0, 350, 200);
		getContentPane().setLayout(new BorderLayout());
		contentPanel.setLayout(new FlowLayout());
		contentPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
		getContentPane().add(contentPanel, BorderLayout.CENTER);
		{
			JPanel buttonPane = new JPanel();
			buttonPane.setLayout(new FlowLayout(FlowLayout.RIGHT));
			JLabel lblNewLabel = new JLabel("<html><div style='text-align: center;'>Author: Andrew Rockefeller © 2023<br/>" +
			"Pixel NES font by Neale Davidson<br/><br/>" +
			"Version: " + UpdateChecker.currentVersion() + "<br/><br/>" +
			"This is an unofficial fan tool — it is not affiliated with Square Enix.</div></html>");
			lblNewLabel.setHorizontalAlignment(SwingConstants.CENTER);
			getContentPane().add(lblNewLabel, BorderLayout.CENTER);
		}
	}

}
