package shufflingway.menu;

import shufflingway.UpdateChecker;

import scraper.AppPaths;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/**
 * Help menu for the main window.
 * Owns all guide links, the update checker, and the About item.
 */
public class HelpMenu extends JMenu {

    private final JFrame owner;

    public HelpMenu(JFrame owner) {
        super("Help");
        this.owner = owner;

        JMenu guides = new JMenu("Guides");
        add(guides);
        addGuideItem(guides, "How to Play (Basics)",
                "This will open the FFTCG Starter Guide in your browser. Continue?",
                0);
        addGuideItem(guides, "How to Play (Advanced)",
                "This will open the FFTCG Comprehensive Rules in your browser. Continue?",
                1);
        addGuideItem(guides, "Limit Break Rules Sheet",
                "This will open the FFTCG Limit Break Rules Sheet in your browser. Continue?",
                2);
        addGuideItem(guides, "Priming Rules Explanation",
                "This will open the FFTCG Priming Rules Explanation in your browser. Continue?",
                3);
        addGuideItem(guides, "Priming Rules Supplementary Explanation",
                "This will open the FFTCG Priming Rules Supplementary Explanation in your browser. Continue?",
                4);

        addSeparator();

        JMenuItem checkUpdates = new JMenuItem("Check for Updates...");
        add(checkUpdates);
        checkUpdates.addActionListener((ActionEvent e) -> {
            checkUpdates.setEnabled(false);
            new SwingWorker<UpdateChecker.ReleaseInfo, Void>() {
                @Override
                protected UpdateChecker.ReleaseInfo doInBackground() throws Exception {
                    return UpdateChecker.checkLatest();
                }

                @Override
                protected void done() {
                    checkUpdates.setEnabled(true);
                    try {
                        showUpdateResult(get());
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(owner,
                                "Could not check for updates:\n" + ex.getMessage(),
                                "Check for Updates", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        });

        addSeparator();

        JMenuItem errorReporting = new JMenuItem("Error Reporting...");
        add(errorReporting);
        errorReporting.addActionListener((ActionEvent e) -> showErrorLogDialog());

        addSeparator();

        JMenuItem about = new JMenuItem("About Shufflingway");
        add(about);
        about.addActionListener((ActionEvent e) -> {
            About dialog = new About();
            dialog.setLocationRelativeTo(owner);
            dialog.setVisible(true);
        });
    }

    private void showErrorLogDialog() {
        Path logPath = AppPaths.appDataDir().resolve("shufflingway.log");
        String content;
        try {
            content = Files.exists(logPath)
                    ? Files.readString(logPath)
                    : "(Log file not found at " + logPath + ")";
        } catch (IOException ex) {
            content = "(Could not read log: " + ex.getMessage() + ")";
        }

        JTextArea textArea = new JTextArea(content);
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setLineWrap(false);
        JScrollPane scroll = new JScrollPane(textArea);
        scroll.setPreferredSize(new java.awt.Dimension(800, 500));
        textArea.setCaretPosition(textArea.getDocument().getLength());

        JButton copyBtn = new JButton("Copy All");
        copyBtn.addActionListener(e -> {
            java.awt.datatransfer.StringSelection sel =
                    new java.awt.datatransfer.StringSelection(textArea.getText());
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
            copyBtn.setText("Copied!");
            Timer reset = new Timer(1500, ev -> copyBtn.setText("Copy All"));
            reset.setRepeats(false);
            reset.start();
        });
        JButton closeBtn = new JButton("Close");

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        buttons.add(new JLabel(logPath.toString()));
        buttons.add(copyBtn);
        buttons.add(closeBtn);

        JDialog dlg = new JDialog(owner, "Error Log", false);
        dlg.setLayout(new BorderLayout());
        dlg.add(scroll, BorderLayout.CENTER);
        dlg.add(buttons, BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        closeBtn.addActionListener(e -> dlg.dispose());
        dlg.setVisible(true);
    }

    private void showUpdateResult(UpdateChecker.ReleaseInfo info) {
        if (!info.updateAvailable()) {
            String msg = "dev".equals(info.currentVersion())
                    ? "Running a development build. Latest release is v" + info.latestVersion() + "."
                    : "You're up to date! (v" + info.currentVersion() + ")";
            JOptionPane.showMessageDialog(owner, msg, "Check for Updates", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String msg = String.format(
                "An update is available!\n\nCurrent version:  %s\nNew version:      %s\n\nDownload and install now?",
                info.currentVersion(), info.latestVersion());
        int choice = JOptionPane.showConfirmDialog(owner, msg, "Update Available",
                JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        if (info.installerUrl() == null || info.installerKind() == null) {
            openInBrowser("https://github.com/RockefellerA/shufflingway/releases/latest");
            return;
        }

        downloadUpdate(info.installerUrl(), info.installerKind());
    }

    private void downloadUpdate(String installerUrl, UpdateChecker.InstallerKind kind) {
        JDialog progress = new JDialog(owner, "Downloading Update...", true);
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setIndeterminate(true);
        bar.setStringPainted(true);
        bar.setString("Downloading...");
        progress.add(bar, BorderLayout.CENTER);
        progress.setSize(320, 75);
        progress.setLocationRelativeTo(owner);
        progress.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        SwingWorker<Void, Integer> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                UpdateChecker.downloadAndInstall(installerUrl, kind, pct -> publish(pct));
                return null;
            }

            @Override
            protected void process(List<Integer> chunks) {
                int pct = chunks.get(chunks.size() - 1);
                if (bar.isIndeterminate()) bar.setIndeterminate(false);
                bar.setValue(pct);
                bar.setString("Downloading... " + pct + "%");
            }

            @Override
            protected void done() {
                progress.dispose();
                try {
                    get();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(owner,
                            "Download failed:\n" + ex.getMessage(),
                            "Update Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        };

        worker.execute();
        progress.setVisible(true); // blocks (modal) until dispose() or System.exit()
    }

    private void addGuideItem(JMenu parent, String label, String prompt, int guideIndex) {
        JMenuItem item = new JMenuItem(label);
        parent.add(item);
        item.addActionListener((ActionEvent e) -> {
            int result = JOptionPane.showConfirmDialog(owner, prompt, label,
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (result == JOptionPane.YES_OPTION) openGuidePdf(guideIndex);
        });
    }

    private static void openGuidePdf(int guide) {
        switch (guide) {
            case 0 -> openInBrowser("https://fftcg.cdn.sewest.net/2024-03/fftcgrulesheet-en.pdf");
            case 1 -> openInBrowser("https://fftcg.cdn.sewest.net/2025-09/fftcg-comprules-v3.2.1.pdf");
            case 2 -> openInBrowser("https://fftcg.cdn.sewest.net/2024-03/lb-rule-explanation-eg.pdf");
            case 3 -> openInBrowser("https://fftcg.cdn.sewest.net/2024-11/priming-rules-explanation-en.pdf");
            case 4 -> openInBrowser("https://fftcg.cdn.sewest.net/2024-11/priming-supplementary-rules-en.pdf");
        }
    }

    private static void openInBrowser(String url) {
        if (!Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return;
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (IOException | URISyntaxException ex) {
            ex.printStackTrace();
        }
    }
}
