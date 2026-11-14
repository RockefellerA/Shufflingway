package shufflingway.menu;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;

/**
 * Help → About → Third-party notices: the licences of what Shufflingway ships with, which their
 * terms ask to go along with it. The texts are in {@code /licenses}, each a short header over the
 * licence as its project publishes it. FlatLaf's and the SQLite JDBC Driver's Apache License is
 * the jar's own and comes once, last; webp4j's natives carry libwebp and GIFLIB, whose notices
 * come from the source webp4j builds them from, since its jar has none.
 */
public final class ThirdPartyNotices extends JDialog {

	/** The notices, in the order shown. Apache-2.0 stays last: the others refer to it as such. */
	static final List<String> FILES = List.of(
			"/licenses/FlatLaf.txt",
			"/licenses/sqlite-jdbc.txt",
			"/licenses/webp4j.txt",
			"/licenses/libwebp.txt",
			"/licenses/giflib.txt",
			"/licenses/fonts.txt",
			"/licenses/Pixel_Emulator.txt",
			"/licenses/Apache-2.0.txt");

	/** Every notice's text, one after another. */
	public static String text() {
		StringBuilder out = new StringBuilder();
		for (String file : FILES) {
			try (InputStream in = ThirdPartyNotices.class.getResourceAsStream(file)) {
				if (in == null) {
					throw new IllegalStateException(file + " is missing from the build");
				}
				if (!out.isEmpty()) {
					out.append("\n\n========================================================================\n\n");
				}
				out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		return out.toString();
	}

	public ThirdPartyNotices(Window owner) {
		super(owner, "Third-party notices", ModalityType.APPLICATION_MODAL);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		getRootPane().registerKeyboardAction(e -> dispose(),
				KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

		JTextArea notices = new JTextArea(text());
		notices.setEditable(false);
		notices.setFont(new Font(Font.MONOSPACED, Font.PLAIN, notices.getFont().getSize()));
		notices.setCaretPosition(0);
		JScrollPane scroll = new JScrollPane(notices);
		scroll.setPreferredSize(new Dimension(680, 480));

		JButton close = new JButton("Close");
		close.addActionListener(e -> dispose());
		getRootPane().setDefaultButton(close);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.add(close);

		JPanel content = new JPanel(new BorderLayout(0, 8));
		content.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
		content.add(scroll, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);
		setContentPane(content);
		pack();
		setLocationRelativeTo(owner);
	}
}
