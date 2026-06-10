package shufflingway.dialog;

import shufflingway.*;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
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
}
