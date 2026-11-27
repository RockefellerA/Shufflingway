package shufflingway.menu;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.ExecutionException;

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

import scraper.AppPaths;
import shufflingway.Browser;
import shufflingway.UpdateChecker;
import shufflingway.dialog.WelcomeDialog;

/**
 * Help menu for the main window.
 * Owns Getting Started, all guide links, the update checker, and the About item.
 */
public class HelpMenu extends JMenu {

    private final JFrame owner;

    public HelpMenu(JFrame owner) {
        super("Help");
        this.owner = owner;

        JMenuItem gettingStarted = new JMenuItem("Getting Started");
        add(gettingStarted);
        gettingStarted.addActionListener((ActionEvent e) -> new WelcomeDialog(owner).setVisible(true));

        addSeparator();

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
        JButton clearBtn = new JButton("Clear Error Log");

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        buttons.add(new JLabel(logPath.toString()));
        buttons.add(copyBtn);
        buttons.add(closeBtn);

        JPanel clearPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        clearPanel.add(clearBtn);

        JPanel south = new JPanel(new BorderLayout());
        south.add(clearPanel, BorderLayout.WEST);
        south.add(buttons, BorderLayout.EAST);

        JDialog dlg = new JDialog(owner, "Error Log", false);
        dlg.setLayout(new BorderLayout());
        dlg.add(scroll, BorderLayout.CENTER);
        dlg.add(south, BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        closeBtn.addActionListener(e -> dlg.dispose());
        clearBtn.addActionListener(e -> {
            int choice = JOptionPane.showConfirmDialog(dlg,
                    "Are you sure you want to clear the error log?", "Clear Error Log",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
            try {
                clearLog(logPath);
                textArea.setText("");
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(dlg, "Could not clear the log:\n" + ex.getMessage(),
                        "Clear Error Log", JOptionPane.ERROR_MESSAGE);
            }
        });
        dlg.setVisible(true);
    }

    /**
     * Empties the log file in place. The app's {@code FileHandler} holds it open in append mode, so
     * its next write lands at the new end of the file rather than at its old offset.
     */
    private static void clearLog(Path logPath) throws IOException {
        if (!Files.exists(logPath)) return;
        try (FileChannel ch = FileChannel.open(logPath, StandardOpenOption.WRITE)) {
            ch.truncate(0);
        }
    }

    private void showUpdateResult(UpdateChecker.ReleaseInfo info) {
        if (!info.updateAvailable()) {
            String msg = "dev".equals(info.currentVersion())
                    ? "Running a development build. Latest release is v" + info.latestVersion() + "."
                    : "You're up to date! (v" + info.currentVersion() + ")";
            JOptionPane.showMessageDialog(owner, msg, "Check for Updates", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        UpdateChecker.InPlaceStatus inPlace = UpdateChecker.inPlaceStatus(info);
        boolean inPlaceReady = inPlace == UpdateChecker.InPlaceStatus.READY;
        String msg = String.format(
                "An update is available!\n\nCurrent version:  %s\nNew version:      %s\n\n%s",
                info.currentVersion(), info.latestVersion(),
                inPlaceReady ? "Download it and restart Shufflingway now?" : "Download and install now?");
        if (inPlace == UpdateChecker.InPlaceStatus.READ_ONLY
                && info.installerKind() == UpdateChecker.InstallerKind.MSI) {
            msg += "\n\nThis copy is installed in a folder only an administrator can change, so it"
                    + "\nupdates through the installer. Newer installers put Shufflingway in your user"
                    + "\nfolder, where it updates itself. To switch, uninstall this copy from"
                    + "\nSettings > Apps before installing the new version.";
        }
        int choice = JOptionPane.showConfirmDialog(owner, msg, "Update Available",
                JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        if (inPlaceReady) {
            downloadUpdate(info, true);
            return;
        }
        if (info.installerUrl() == null || info.installerKind() == null) {
            Browser.open("https://github.com/RockefellerA/shufflingway/releases/latest");
            return;
        }

        downloadUpdate(info, false);
    }

    /**
     * Downloads the update with a progress dialog. In place, the new jar replaces this copy's on the
     * next start, so this restarts the app; otherwise the installer launches and the app exits.
     */
    private void downloadUpdate(UpdateChecker.ReleaseInfo info, boolean inPlace) {
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
                if (inPlace)
                    UpdateChecker.downloadInPlace(info, pct -> publish(pct));
                else
                    UpdateChecker.downloadAndInstall(info.installerUrl(), info.installerKind(),
                            pct -> publish(pct));
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
                    Throwable cause = ex instanceof ExecutionException && ex.getCause() != null
                            ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(owner,
                            "Download failed:\n" + cause.getMessage(),
                            "Update Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                if (inPlace) restartAfterUpdate(info.latestVersion());
            }
        };

        worker.execute();
        progress.setVisible(true); // blocks (modal) until dispose() or System.exit()
    }

    /**
     * Starts the updated copy, then closes this window the way its close box does, so settings are
     * saved before the app exits. The new copy waits for this one to exit.
     */
    private void restartAfterUpdate(String version) {
        try {
            UpdateChecker.relaunch();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(owner,
                    "Version " + version + " is installed, but Shufflingway couldn't restart itself:\n"
                            + ex.getMessage() + "\n\nClose Shufflingway and start it again to use it.",
                    "Update Installed", JOptionPane.WARNING_MESSAGE);
            return;
        }
        owner.dispatchEvent(new WindowEvent(owner, WindowEvent.WINDOW_CLOSING));
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
            case 0 -> Browser.open("https://fftcg.cdn.sewest.net/2024-03/fftcgrulesheet-en.pdf");
            case 1 -> Browser.open("https://fftcg.cdn.sewest.net/2026-08/fftcg-comprules-v3.3.pdf");
            case 2 -> Browser.open("https://fftcg.cdn.sewest.net/2024-03/lb-rule-explanation-eg.pdf");
            case 3 -> Browser.open("https://fftcg.cdn.sewest.net/2024-11/priming-rules-explanation-en.pdf");
            case 4 -> Browser.open("https://fftcg.cdn.sewest.net/2024-11/priming-supplementary-rules-en.pdf");
        }
    }
}
