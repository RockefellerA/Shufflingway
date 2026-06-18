package shufflingway.dialog;

import shufflingway.*;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.colorchooser.AbstractColorChooserPanel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;


public class PreferencesDialog extends JDialog {

	public PreferencesDialog(Frame owner) {
		this(owner, null);
	}

	public PreferencesDialog(Frame owner, Runnable onLayoutChanged) {
		super(owner, "Preferences", true);
		setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		setResizable(false);

		JPanel contentPanel = new JPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBorder(new EmptyBorder(12, 16, 8, 16));

		// ── Layout ───────────────────────────────────────────────────────────
		JPanel layoutPanel = new JPanel();
		layoutPanel.setLayout(new BoxLayout(layoutPanel, BoxLayout.Y_AXIS));
		layoutPanel.setBorder(BorderFactory.createTitledBorder(
				BorderFactory.createEtchedBorder(), "Layout",
				TitledBorder.LEFT, TitledBorder.TOP));

		JPanel sidePanelRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		sidePanelRow.add(new JLabel("Side Panel:"));
		JComboBox<String> sidePanelCombo = new JComboBox<>(new String[]{"Left", "Right"});
		sidePanelCombo.setSelectedItem("right".equals(AppSettings.getSidePanelSide()) ? "Right" : "Left");
		sidePanelCombo.addActionListener(e -> {
			AppSettings.setSidePanelSide("Right".equals(sidePanelCombo.getSelectedItem()) ? "right" : "left");
			AppSettings.save();
			if (onLayoutChanged != null) onLayoutChanged.run();
		});
		sidePanelRow.add(sidePanelCombo);
		sidePanelRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		layoutPanel.add(sidePanelRow);

		layoutPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		contentPanel.add(layoutPanel);
		contentPanel.add(javax.swing.Box.createVerticalStrut(8));

		// ── Card Back ────────────────────────────────────────────────────────
		JPanel cardBackPanel = new JPanel();
		cardBackPanel.setLayout(new BoxLayout(cardBackPanel, BoxLayout.Y_AXIS));
		cardBackPanel.setBorder(BorderFactory.createTitledBorder(
				BorderFactory.createEtchedBorder(), "Card Back",
				TitledBorder.LEFT, TitledBorder.TOP));

		String existingPath = AppSettings.getCustomCardbackPath();
		String currentName = existingPath.isEmpty() ? "Default" : new File(existingPath).getName();
		JLabel currentLabel = new JLabel("Current: " + currentName);
		currentLabel.setBorder(new EmptyBorder(2, 4, 4, 4));
		currentLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		cardBackPanel.add(currentLabel);

		JButton resetButton = new JButton("Reset to Default");
		resetButton.setEnabled(!existingPath.isEmpty());

		JButton chooseButton = new JButton("Choose File…");
		chooseButton.addActionListener(e -> {
			JFileChooser chooser = new JFileChooser();
			chooser.setFileFilter(new FileNameExtensionFilter(
					"Image files (JPG, PNG, GIF, BMP)", "jpg", "jpeg", "png", "gif", "bmp"));
			if (chooser.showOpenDialog(cardBackPanel) != JFileChooser.APPROVE_OPTION) return;
			File selected = chooser.getSelectedFile();
			if (selected.length() > 5L * 1024 * 1024) {
				JOptionPane.showMessageDialog(cardBackPanel,
						"File must be under 5 MB.", "File Too Large", JOptionPane.WARNING_MESSAGE);
				return;
			}
			try {
				File destDir = new File(AppSettings.getCardbackCustomDir());
				destDir.mkdirs();
				File dest = new File(destDir, selected.getName());
				Files.copy(selected.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
				AppSettings.setCustomCardbackPath(dest.getAbsolutePath());
				AppSettings.save();
				currentLabel.setText("Current: " + dest.getName());
				resetButton.setEnabled(true);
			} catch (IOException ex) {
				JOptionPane.showMessageDialog(cardBackPanel,
						"Could not copy file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
			}
		});

		resetButton.addActionListener(e -> {
			AppSettings.setCustomCardbackPath("");
			AppSettings.save();
			currentLabel.setText("Current: Default");
			resetButton.setEnabled(false);
		});

		JPanel cardBackButtonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		cardBackButtonRow.add(chooseButton);
		cardBackButtonRow.add(resetButton);
		cardBackButtonRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		cardBackPanel.add(cardBackButtonRow);

		JLabel cardBackHint = new JLabel(
				"<html><font color='gray' size='2'>Max 5 MB. Supported: JPG, PNG, GIF, BMP.</font></html>");
		cardBackHint.setBorder(new EmptyBorder(2, 4, 4, 4));
		cardBackHint.setAlignmentX(Component.LEFT_ALIGNMENT);
		cardBackPanel.add(cardBackHint);

		cardBackPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		contentPanel.add(cardBackPanel);

		// ── Counter ──────────────────────────────────────────────────────────
		contentPanel.add(javax.swing.Box.createVerticalStrut(8));

		JPanel counterSection = new JPanel();
		counterSection.setLayout(new BoxLayout(counterSection, BoxLayout.Y_AXIS));
		counterSection.setBorder(BorderFactory.createTitledBorder(
				BorderFactory.createEtchedBorder(), "Counter",
				TitledBorder.LEFT, TitledBorder.TOP));

		// Preview: card-coloured background with the Counter orb on top
		int prevW = 52, prevH = 44;
		Counter counterPreview = new Counter(AppSettings.getCounterColor());
		int cw = 36, ch = 30;

		JPanel previewBackground = new JPanel(null) {
			@Override
			protected void paintComponent(Graphics g) {
				Graphics2D g2 = (Graphics2D) g;
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setPaint(new GradientPaint(0, 0, new Color(0x6b4a2f),
						getWidth(), getHeight(), new Color(0x3a2817)));
				g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
			}
		};
		previewBackground.setPreferredSize(new Dimension(prevW, prevH));
		previewBackground.setMinimumSize(new Dimension(prevW, prevH));
		previewBackground.setMaximumSize(new Dimension(prevW, prevH));

		counterPreview.setBounds((prevW - cw) / 2, (prevH - ch) / 2, cw, ch);
		previewBackground.add(counterPreview);

		JButton colorButton = new JButton("Counter Color…");
		colorButton.addActionListener(e -> {
			Color initial = hexToColor(AppSettings.getCounterColor());
			String savedHex = AppSettings.getCounterColor();
			JColorChooser chooser = new JColorChooser(initial);

			// Keep only the Swatches panel; remove everything else
			for (AbstractColorChooserPanel panel : chooser.getChooserPanels()) {
				if (!panel.getDisplayName().equalsIgnoreCase("Swatches"))
					chooser.removeChooserPanel(panel);
			}

			// Replace the default preview with the counter orb on a card background
			int dpW = 80, dpH = 70, dcw = 56, dch = 48;
			Counter dialogPreview = new Counter(AppSettings.getCounterColor());
			JPanel dialogPreviewBg = new JPanel(null) {
				@Override protected void paintComponent(Graphics g) {
					Graphics2D g2 = (Graphics2D) g;
					g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					g2.setPaint(new GradientPaint(0, 0, new Color(0x6b4a2f),
							getWidth(), getHeight(), new Color(0x3a2817)));
					g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
				}
			};
			dialogPreviewBg.setPreferredSize(new Dimension(dpW, dpH));
			dialogPreviewBg.setMinimumSize(new Dimension(dpW, dpH));
			dialogPreviewBg.setMaximumSize(new Dimension(dpW, dpH));
			dialogPreview.setBounds((dpW - dcw) / 2, (dpH - dch) / 2, dcw, dch);
			dialogPreviewBg.add(dialogPreview);
			chooser.setPreviewPanel(dialogPreviewBg);

			chooser.getSelectionModel().addChangeListener(ce -> {
				Color c = chooser.getColor();
				if (c != null) {
					String hex = colorToHex(c);
					counterPreview.setTint(hex);
					dialogPreview.setTint(hex);
				}
			});
			JDialog colorDialog = JColorChooser.createDialog(
					this, "Choose Counter Color", true, chooser,
					ok -> {
						Color chosen = chooser.getColor();
						if (chosen != null) {
							String hex = colorToHex(chosen);
							AppSettings.setCounterColor(hex);
							AppSettings.save();
							counterPreview.setTint(hex);
						}
					},
					cancel -> counterPreview.setTint(savedHex));
			colorDialog.setResizable(false);
			colorDialog.setVisible(true);
		});

		JPanel counterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
		counterRow.add(colorButton);
		counterRow.add(previewBackground);
		counterRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		counterSection.add(counterRow);

		counterSection.setAlignmentX(Component.LEFT_ALIGNMENT);
		contentPanel.add(counterSection);

		// ── Debug ────────────────────────────────────────────────────────────
		// Hidden unless `debug=1` is set manually in settings.ini.
		if (AppSettings.isDebugEnabled()) {
			contentPanel.add(javax.swing.Box.createVerticalStrut(8));

			JPanel debugPanel = new JPanel();
			debugPanel.setLayout(new BoxLayout(debugPanel, BoxLayout.Y_AXIS));
			debugPanel.setBorder(BorderFactory.createTitledBorder(
					BorderFactory.createEtchedBorder(), "Debug",
					TitledBorder.LEFT, TitledBorder.TOP));

			JCheckBox unlimitedMulliganBox = new JCheckBox("Unlimited Mulligan",
					AppSettings.isDebugUnlimitedMulligan());
			unlimitedMulliganBox.setToolTipText(
					"Keep the Mulligan button enabled across repeated mulligans (overrides once-per-game limit).");
			unlimitedMulliganBox.addActionListener(e -> {
				AppSettings.setDebugUnlimitedMulligan(unlimitedMulliganBox.isSelected());
				AppSettings.save();
			});
			unlimitedMulliganBox.setAlignmentX(Component.LEFT_ALIGNMENT);
			debugPanel.add(unlimitedMulliganBox);

			JCheckBox alwaysWinCoinFlipBox = new JCheckBox("Always Win Coin Flip",
					AppSettings.isDebugAlwaysWinCoinFlip());
			alwaysWinCoinFlipBox.setToolTipText(
					"P1 always goes first instead of a random 50/50 coin flip.");
			alwaysWinCoinFlipBox.addActionListener(e -> {
				AppSettings.setDebugAlwaysWinCoinFlip(alwaysWinCoinFlipBox.isSelected());
				AppSettings.save();
			});
			alwaysWinCoinFlipBox.setAlignmentX(Component.LEFT_ALIGNMENT);
			debugPanel.add(alwaysWinCoinFlipBox);

			debugPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
			contentPanel.add(debugPanel);
		}

		// ── Buttons ──────────────────────────────────────────────────────────
		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton closeButton = new JButton("Close");
		closeButton.addActionListener(e -> dispose());
		buttonPanel.add(closeButton);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(contentPanel, BorderLayout.CENTER);
		getContentPane().add(buttonPanel,  BorderLayout.SOUTH);

		getRootPane().registerKeyboardAction(
			e -> dispose(),
			KeyStroke.getKeyStroke("ESCAPE"),
			JComponent.WHEN_IN_FOCUSED_WINDOW
		);

		pack();
		setLocationRelativeTo(owner);
	}

	private static Color hexToColor(String hex) {
		if (hex == null) return new Color(0x36b06a);
		String s = hex.trim();
		if (s.startsWith("#")) s = s.substring(1);
		if (s.length() == 3) {
			StringBuilder b = new StringBuilder();
			for (char c : s.toCharArray()) b.append(c).append(c);
			s = b.toString();
		}
		if (s.length() != 6) return new Color(0x36b06a);
		try {
			return new Color(Integer.parseInt(s, 16));
		} catch (NumberFormatException e) {
			return new Color(0x36b06a);
		}
	}

	private static String colorToHex(Color c) {
		return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}
}
