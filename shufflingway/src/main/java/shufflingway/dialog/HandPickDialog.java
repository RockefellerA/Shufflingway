package shufflingway.dialog;

import shufflingway.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.*;
import shufflingway.graphics.CardAnimation;
import static shufflingway.graphics.CardAnimation.*;

/**
 * Modal hand-selection dialogs that let P1 pick some subset of their hand cards.
 * Each static method handles one specific use case; the confirm callback is
 * invoked with the chosen indices before the dialog closes.
 */
public class HandPickDialog {

    // -------------------------------------------------------------------------
    // End-phase discard (undecorated overlay style)
    // -------------------------------------------------------------------------

    public static void showEndPhaseDiscard(JFrame owner, List<CardData> hand, int mustDiscard,
                                            Consumer<String> onZoom, Runnable onZoomHide,
                                            Consumer<List<Integer>> onConfirm) {
        JDialog dlg = new JDialog(owner, true);
        dlg.setUndecorated(true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        Set<Integer> selected = new HashSet<>();

        JLabel statusLabel = new JLabel("Select " + mustDiscard + " card(s) to discard.", SwingConstants.CENTER);
        statusLabel.setFont(FontLoader.loadPixelNESFont(10));

        List<JLabel> cardLabels = new ArrayList<>();
        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));

        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.setEnabled(false);

        Runnable refresh = () -> {
            int remaining = mustDiscard - selected.size();
            statusLabel.setText(remaining > 0
                    ? "Select " + remaining + " more card(s) to discard."
                    : "Ready — click Confirm to discard.");
            confirmBtn.setEnabled(selected.size() == mustDiscard);
            for (int i = 0; i < cardLabels.size(); i++) {
                cardLabels.get(i).setBorder(selected.contains(i)
                        ? CardAnimation.createCardGlowBorder(Color.RED)
                        : BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
            }
        };

        buildCardGrid(hand, hand.size(), cardsPanel, cardLabels, selected, mustDiscard, refresh, onZoom, onZoomHide);

        confirmBtn.addActionListener(ae -> {
            onZoomHide.run();
            List<Integer> toDiscard = new ArrayList<>(selected);
            dlg.dispose();
            onConfirm.accept(toDiscard);
        });

        JScrollPane scrollPane = new JScrollPane(cardsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setPreferredSize(new Dimension(
                Math.min(hand.size() * (CARD_W + 16) + 16, 900),
                CARD_H + 60));

        JLabel titleLabel = new JLabel("End Phase — Discard to 5", SwingConstants.CENTER);
        titleLabel.setFont(FontLoader.loadPixelNESFont(14));

        JPanel south = new JPanel(new BorderLayout());
        south.add(statusLabel, BorderLayout.CENTER);
        south.add(confirmBtn,  BorderLayout.EAST);

        JPanel mainPanel = new JPanel(new BorderLayout(0, 6));
        mainPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createRaisedBevelBorder(),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        mainPanel.add(titleLabel,  BorderLayout.NORTH);
        mainPanel.add(scrollPane,  BorderLayout.CENTER);
        mainPanel.add(south,       BorderLayout.SOUTH);

        dlg.getContentPane().add(mainPanel);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }

    // -------------------------------------------------------------------------
    // Forced discard (effect-driven)
    // -------------------------------------------------------------------------

    public static void showForcedDiscard(JFrame owner, List<CardData> hand, int mustDiscard,
                                          Consumer<String> onZoom, Runnable onZoomHide,
                                          Consumer<List<Integer>> onConfirm) {
        JDialog dlg = new JDialog(owner, "Discard " + mustDiscard + " Card(s)", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        Set<Integer> selected = new HashSet<>();

        JLabel statusLabel = new JLabel("Select " + mustDiscard + " card(s) to discard.", SwingConstants.CENTER);
        statusLabel.setFont(FontLoader.loadPixelNESFont(10));

        List<JLabel> cardLabels = new ArrayList<>();
        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

        JButton confirmBtn = new JButton("Discard");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.setEnabled(false);

        Runnable refresh = () -> {
            int remaining = mustDiscard - selected.size();
            statusLabel.setText(remaining > 0
                    ? "Select " + remaining + " more card(s) to discard."
                    : "Ready — click Discard to confirm.");
            confirmBtn.setEnabled(selected.size() == mustDiscard);
            for (int i = 0; i < cardLabels.size(); i++) {
                cardLabels.get(i).setBorder(BorderFactory.createLineBorder(
                        selected.contains(i) ? Color.RED : Color.LIGHT_GRAY,
                        selected.contains(i) ? 3 : 1));
            }
        };

        buildCardGrid(hand, hand.size(), cardsPanel, cardLabels, selected, mustDiscard, refresh, onZoom, onZoomHide);

        confirmBtn.addActionListener(ae -> {
            onZoomHide.run();
            List<Integer> toDiscard = new ArrayList<>(selected);
            dlg.dispose();
            onConfirm.accept(toDiscard);
        });

        JScrollPane scrollPane = new JScrollPane(cardsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setPreferredSize(new Dimension(
                Math.min(hand.size() * (CARD_W + 16) + 16, 900),
                CARD_H + 60));

        JPanel south = new JPanel(new BorderLayout());
        south.add(statusLabel, BorderLayout.CENTER);
        south.add(confirmBtn,  BorderLayout.EAST);
        south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }

    // -------------------------------------------------------------------------
    // Discard by card type (exactly 1 eligible card)
    // -------------------------------------------------------------------------

    /** Shows eligible cards from hand; calls onConfirm with the chosen hand index.
     *  Returns true if the confirm button was clicked, false if the dialog was
     *  dismissed without selection (shouldn't happen in normal play). */
    public static boolean showDiscardByType(JFrame owner, List<CardData> hand, List<Integer> eligible,
                                             String cardType,
                                             Consumer<String> onZoom, Runnable onZoomHide,
                                             Consumer<Integer> onConfirm) {
        JDialog dlg = new JDialog(owner, "Discard 1 " + cardType, true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        boolean[] result = { false };
        int[] selectedIdx = { -1 };

        JLabel statusLabel = new JLabel("Select 1 " + cardType + " to discard.", SwingConstants.CENTER);
        statusLabel.setFont(FontLoader.loadPixelNESFont(10));

        List<JLabel> cardLabels = new ArrayList<>();
        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

        JButton confirmBtn = new JButton("Discard");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.setEnabled(false);

        Runnable refresh = () -> {
            confirmBtn.setEnabled(selectedIdx[0] >= 0);
            for (int j = 0; j < cardLabels.size(); j++) {
                boolean sel = eligible.get(j).equals(selectedIdx[0]);
                cardLabels.get(j).setBorder(BorderFactory.createLineBorder(
                        sel ? Color.RED : Color.LIGHT_GRAY, sel ? 3 : 1));
            }
        };

        for (int j = 0; j < eligible.size(); j++) {
            final int handIdx = eligible.get(j);
            CardData cd = hand.get(handIdx);

            JPanel wrapper = new JPanel(new BorderLayout(0, 4));
            wrapper.setBackground(cardsPanel.getBackground());

            JLabel lbl = makeCardLabel();
            lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            cardLabels.add(lbl);

            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { if (lbl.getIcon() != null) onZoom.accept(cd.imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { onZoomHide.run(); }
                @Override public void mousePressed(MouseEvent e) {
                    selectedIdx[0] = selectedIdx[0] == handIdx ? -1 : handIdx;
                    refresh.run();
                }
            });

            loadCardImage(lbl, cd.imageUrl());

            JLabel nameLabel = new JLabel(cd.name(), SwingConstants.CENTER);
            nameLabel.setFont(FontLoader.loadPixelNESFont(9));
            nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

            wrapper.add(lbl,       BorderLayout.CENTER);
            wrapper.add(nameLabel, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        confirmBtn.addActionListener(ae -> {
            onZoomHide.run();
            dlg.dispose();
            int idx = selectedIdx[0];
            if (idx >= 0) {
                result[0] = true;
                onConfirm.accept(idx);
            }
        });

        JScrollPane scroll = new JScrollPane(cardsPanel);
        scroll.setPreferredSize(new Dimension(Math.min(eligible.size() * (CARD_W + 16) + 20, 600), CARD_H + 60));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        btnPanel.add(confirmBtn);

        JPanel main = new JPanel(new BorderLayout(0, 6));
        main.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        main.add(statusLabel, BorderLayout.NORTH);
        main.add(scroll,      BorderLayout.CENTER);
        main.add(btnPanel,    BorderLayout.SOUTH);

        dlg.setContentPane(main);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
        return result[0];
    }

    // -------------------------------------------------------------------------
    // Place N cards at bottom of deck
    // -------------------------------------------------------------------------

    public static void showPlaceToBottom(JFrame owner, List<CardData> hand, int mustPlace,
                                          Consumer<String> onZoom, Runnable onZoomHide,
                                          Consumer<List<Integer>> onConfirm) {
        JDialog dlg = new JDialog(owner, "Place " + mustPlace + " Card(s) at Bottom of Deck", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        Set<Integer> selected = new HashSet<>();

        JLabel statusLabel = new JLabel("Select " + mustPlace + " card(s) to place at the bottom of your deck.",
                SwingConstants.CENTER);
        statusLabel.setFont(FontLoader.loadPixelNESFont(10));

        List<JLabel> cardLabels = new ArrayList<>();
        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

        JButton confirmBtn = new JButton("Place");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.setEnabled(false);

        Runnable refresh = () -> {
            int remaining = mustPlace - selected.size();
            statusLabel.setText(remaining > 0
                    ? "Select " + remaining + " more card(s)."
                    : "Ready — click Place to confirm.");
            confirmBtn.setEnabled(selected.size() == mustPlace);
            for (int i = 0; i < cardLabels.size(); i++) {
                cardLabels.get(i).setBorder(BorderFactory.createLineBorder(
                        selected.contains(i) ? Color.BLUE : Color.LIGHT_GRAY,
                        selected.contains(i) ? 3 : 1));
            }
        };

        buildCardGrid(hand, hand.size(), cardsPanel, cardLabels, selected, mustPlace, refresh, onZoom, onZoomHide);

        confirmBtn.addActionListener(ae -> {
            onZoomHide.run();
            List<Integer> toPlace = new ArrayList<>(selected);
            dlg.dispose();
            onConfirm.accept(toPlace);
        });

        JScrollPane scrollPane = new JScrollPane(cardsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setPreferredSize(new Dimension(
                Math.min(hand.size() * (CARD_W + 16) + 16, 900),
                CARD_H + 60));

        JPanel south = new JPanel(new BorderLayout());
        south.add(statusLabel, BorderLayout.CENTER);
        south.add(confirmBtn,  BorderLayout.EAST);
        south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }

    // -------------------------------------------------------------------------
    // Remove N cards from hand to the RFP zone
    // -------------------------------------------------------------------------

    public static void showHandRfp(JFrame owner, List<CardData> targetHand, int mustSelect,
                                    Consumer<String> onZoom, Runnable onZoomHide,
                                    Consumer<List<Integer>> onConfirm) {
        JDialog dlg = new JDialog(owner, "Remove " + mustSelect + " Card(s) From Game", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        Set<Integer> selected = new HashSet<>();

        JLabel statusLabel = new JLabel("Select " + mustSelect + " card(s) to remove from the game.",
                SwingConstants.CENTER);
        statusLabel.setFont(FontLoader.loadPixelNESFont(10));

        List<JLabel> cardLabels = new ArrayList<>();
        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

        JButton confirmBtn = new JButton("Remove From Game");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.setEnabled(false);

        Runnable refresh = () -> {
            int remaining = mustSelect - selected.size();
            statusLabel.setText(remaining > 0
                    ? "Select " + remaining + " more card(s) to remove."
                    : "Ready — click Remove From Game to confirm.");
            confirmBtn.setEnabled(selected.size() == mustSelect);
            for (int i = 0; i < cardLabels.size(); i++) {
                cardLabels.get(i).setBorder(BorderFactory.createLineBorder(
                        selected.contains(i) ? new Color(0xff8800) : Color.LIGHT_GRAY,
                        selected.contains(i) ? 3 : 1));
            }
        };

        buildCardGrid(targetHand, targetHand.size(), cardsPanel, cardLabels, selected, mustSelect, refresh, onZoom, onZoomHide);

        confirmBtn.addActionListener(ae -> {
            onZoomHide.run();
            List<Integer> toRemove = new ArrayList<>(selected);
            dlg.dispose();
            onConfirm.accept(toRemove);
        });

        JScrollPane scrollPane = new JScrollPane(cardsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setPreferredSize(new Dimension(
                Math.min(targetHand.size() * (CARD_W + 16) + 16, 900),
                CARD_H + 60));

        JPanel south = new JPanel(new BorderLayout(4, 4));
        south.add(statusLabel, BorderLayout.NORTH);
        south.add(confirmBtn,  BorderLayout.SOUTH);

        dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    /** Populates {@code cardsPanel} with one card thumbnail per entry in {@code cards}.
     *  Click toggles the card in/out of {@code selected} (up to {@code maxSelect}),
     *  then calls {@code refresh}. */
    private static void buildCardGrid(List<CardData> cards, int count,
                                       JPanel cardsPanel, List<JLabel> cardLabels,
                                       Set<Integer> selected, int maxSelect,
                                       Runnable refresh,
                                       Consumer<String> onZoom, Runnable onZoomHide) {
        for (int i = 0; i < count; i++) {
            final int idx = i;
            CardData cd = cards.get(i);

            JPanel wrapper = new JPanel(new BorderLayout(0, 4));
            wrapper.setBackground(cardsPanel.getBackground());

            JLabel lbl = makeCardLabel();
            lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            cardLabels.add(lbl);

            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { if (lbl.getIcon() != null) onZoom.accept(cd.imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { onZoomHide.run(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (selected.contains(idx)) selected.remove(idx);
                    else if (selected.size() < maxSelect) selected.add(idx);
                    refresh.run();
                }
            });

            loadCardImage(lbl, cd.imageUrl());

            JLabel nameLabel = new JLabel(cd.name(), SwingConstants.CENTER);
            nameLabel.setFont(FontLoader.loadPixelNESFont(9));
            nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

            wrapper.add(lbl,       BorderLayout.CENTER);
            wrapper.add(nameLabel, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }
    }

    static JLabel makeCardLabel() {
        JLabel lbl = new JLabel("...", SwingConstants.CENTER);
        lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
        lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
        lbl.setOpaque(true);
        lbl.setBackground(Color.DARK_GRAY);
        lbl.setForeground(Color.WHITE);
        lbl.setFont(FontLoader.loadPixelNESFont(10));
        lbl.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
        return lbl;
    }

    static void loadCardImage(JLabel lbl, String url) {
        new SwingWorker<ImageIcon, Void>() {
            @Override protected ImageIcon doInBackground() throws Exception {
                Image img = ImageCache.load(url);
                if (img == null) return null;
                BufferedImage buf = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = buf.createGraphics();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(img, 0, 0, CARD_W, CARD_H, null);
                g2.dispose();
                return new ImageIcon(buf);
            }
            @Override protected void done() {
                try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
                catch (InterruptedException | ExecutionException ignored) {}
            }
        }.execute();
    }
}
