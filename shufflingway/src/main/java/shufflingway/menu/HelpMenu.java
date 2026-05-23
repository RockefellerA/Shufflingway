package shufflingway.menu;

import shufflingway.UpdateChecker;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JProgressBar;
import javax.swing.SwingWorker;

/**
 * Help menu for the main window.
 * Owns all guide links, the update checker, and the About item.
 */
public class HelpMenu extends JMenu {

    private final JFrame owner;

    public HelpMenu(JFrame owner) {
        super("Help");
        this.owner = owner;

        addGuideItem("How to Play (Basics)",
                "This will open the FFTCG Starter Guide in your browser. Continue?",
                0);
        addGuideItem("How to Play (Advanced)",
                "This will open the FFTCG Comprehensive Rules in your browser. Continue?",
                1);
        addGuideItem("Limit Break Rules Sheet",
                "This will open the FFTCG Limit Break Rules Sheet in your browser. Continue?",
                2);
        addGuideItem("Priming Rules Explanation",
                "This will open the FFTCG Priming Rules Explanation in your browser. Continue?",
                3);
        addGuideItem("Priming Rules Supplementary Explanation",
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

        JMenuItem about = new JMenuItem("About Shufflingway");
        add(about);
        about.addActionListener((ActionEvent e) -> {
            About dialog = new About();
            dialog.setLocationRelativeTo(owner);
            dialog.setVisible(true);
        });
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

        if (info.msiUrl() == null) {
            openInBrowser("https://github.com/RockefellerA/shufflingway/releases/latest");
            return;
        }

        downloadUpdate(info.msiUrl());
    }

    private void downloadUpdate(String msiUrl) {
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
                UpdateChecker.downloadAndInstall(msiUrl, pct -> publish(pct));
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

    private void addGuideItem(String label, String prompt, int guideIndex) {
        JMenuItem item = new JMenuItem(label);
        add(item);
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
