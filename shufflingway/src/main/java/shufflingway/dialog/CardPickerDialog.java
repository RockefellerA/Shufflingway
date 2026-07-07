package shufflingway.dialog;

import shufflingway.CardData;
import shufflingway.FontLoader;
import shufflingway.ForwardTarget;
import shufflingway.ImageCache;
import shufflingway.graphics.CardAnimation;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import static shufflingway.graphics.CardAnimation.CARD_H;
import static shufflingway.graphics.CardAnimation.CARD_W;
import static shufflingway.CardFilters.discardTypeKey;

/**
 * Modal card-selection dialogs that have no game-state side effects — they
 * receive card lists, show a picker UI, and return an index or selection.
 * Zoom callbacks are supplied at construction so MainWindow's zoom pane
 * continues to work during selections.
 */
public class CardPickerDialog {

    private final JFrame owner;
    private final Consumer<String> onZoom;
    private final Runnable onZoomHide;

    public CardPickerDialog(JFrame owner, Consumer<String> onZoom, Runnable onZoomHide) {
        this.owner = owner;
        this.onZoom = onZoom;
        this.onZoomHide = onZoomHide;
    }

    // -------------------------------------------------------------------------
    // Deck-search pickers
    // -------------------------------------------------------------------------

    /** Shows a click-to-pick grid of {@code matches}; returns the first card if only one. */
    public CardData pickFromDeckSearch(List<CardData> matches) {
        if (matches.size() == 1) return matches.get(0);
        JDialog dlg = new JDialog(owner, "Search — choose a card (" + matches.size() + " found)", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        CardData[] picked = { matches.get(0) };

        final int CARDS_PER_ROW = 10;
        JPanel cardsPanel = new JPanel();
        cardsPanel.setLayout(new BoxLayout(cardsPanel, BoxLayout.Y_AXIS));
        JPanel currentRow = null;
        for (int idx = 0; idx < matches.size(); idx++) {
            CardData candidate = matches.get(idx);
            if (idx % CARDS_PER_ROW == 0) {
                currentRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 12));
                currentRow.setAlignmentX(Component.LEFT_ALIGNMENT);
                cardsPanel.add(currentRow);
            }
            JPanel wrapper = new JPanel(new BorderLayout(0, 4));
            wrapper.setBackground(cardsPanel.getBackground());

            JLabel lbl = new JLabel("...", SwingConstants.CENTER);
            lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
            lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
            lbl.setOpaque(true);
            lbl.setBackground(Color.DARK_GRAY);
            lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2));
            lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    if (lbl.getIcon() != null) onZoom.accept(candidate.imageUrl());
                    lbl.setBorder(CardAnimation.createCardGlowBorder(Color.YELLOW));
                }
                @Override public void mouseExited(MouseEvent e) {
                    onZoomHide.run();
                    lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
                }
                @Override public void mousePressed(MouseEvent e) {
                    picked[0] = candidate;
                    dlg.dispose();
                }
            });

            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(candidate.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();

            JLabel nameLabel = new JLabel(candidate.name(), SwingConstants.CENTER);
            nameLabel.setFont(FontLoader.loadPixelNESFont(9));
            nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

            wrapper.add(lbl, BorderLayout.CENTER);
            wrapper.add(nameLabel, BorderLayout.SOUTH);
            currentRow.add(wrapper);
        }

        JLabel hint = new JLabel("Click a card to select it", SwingConstants.CENTER);
        hint.setFont(FontLoader.loadPixelNESFont(9));

        int rowHeight = 12 + CARD_H + 4 + 18 + 12;
        int rowsToShow = Math.min(2, (matches.size() + CARDS_PER_ROW - 1) / CARDS_PER_ROW);
        int colsInWidest = Math.min(matches.size(), CARDS_PER_ROW);
        int rowWidth = 12 + colsInWidest * CARD_W + (colsInWidest - 1) * 12 + 12;

        JScrollPane scroll = new JScrollPane(cardsPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(rowHeight);
        int scrollbarPad = matches.size() > rowsToShow * CARDS_PER_ROW
                ? scroll.getVerticalScrollBar().getPreferredSize().width : 0;
        scroll.setPreferredSize(new Dimension(rowWidth + scrollbarPad, rowsToShow * rowHeight));

        dlg.getContentPane().setLayout(new BorderLayout(0, 6));
        dlg.getContentPane().add(scroll, BorderLayout.CENTER);
        dlg.getContentPane().add(hint, BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);

        return picked[0];
    }

    /**
     * Multi-select deck-search picker for "up to N" searches (N > 1).
     * Returns the list of chosen cards in selection order, or an empty list if the player
     * clicks Skip.  Confirm is disabled until at least one card is selected.
     */
    public List<CardData> pickMultiFromDeckSearch(List<CardData> matches, int maxCount) {
        JDialog dlg = new JDialog(owner,
                "Search — choose up to " + maxCount + " cards (" + matches.size() + " found)", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        List<Integer> selected   = new ArrayList<>();
        List<JLabel>  cardLabels = new ArrayList<>(matches.size());
        boolean[]     confirmed  = { false };

        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.setEnabled(false);

        JButton skipBtn = new JButton("Skip");
        skipBtn.setFont(FontLoader.loadPixelNESFont(11));

        JLabel hint = new JLabel("", SwingConstants.CENTER);
        hint.setFont(FontLoader.loadPixelNESFont(9));

        Runnable refresh = () -> {
            int n = selected.size();
            hint.setText("Select up to " + maxCount + " card" + (maxCount > 1 ? "s" : "")
                    + " (" + n + "/" + maxCount + ")");
            confirmBtn.setEnabled(n >= 1);
            for (int i = 0; i < cardLabels.size(); i++) {
                cardLabels.get(i).setBorder(selected.contains(i)
                        ? CardAnimation.createCardGlowBorder(Color.GREEN)
                        : BorderFactory.createLineBorder(Color.GRAY, 2));
            }
        };

        final int CARDS_PER_ROW = 10;
        JPanel cardsPanel = new JPanel();
        cardsPanel.setLayout(new BoxLayout(cardsPanel, BoxLayout.Y_AXIS));
        JPanel currentRow = null;
        for (int idx = 0; idx < matches.size(); idx++) {
            CardData candidate = matches.get(idx);
            final int cardIdx = idx;
            if (idx % CARDS_PER_ROW == 0) {
                currentRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 12));
                currentRow.setAlignmentX(Component.LEFT_ALIGNMENT);
                cardsPanel.add(currentRow);
            }
            JPanel wrapper = new JPanel(new BorderLayout(0, 4));
            wrapper.setBackground(cardsPanel.getBackground());

            JLabel lbl = new JLabel("...", SwingConstants.CENTER);
            lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
            lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
            lbl.setOpaque(true);
            lbl.setBackground(Color.DARK_GRAY);
            lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2));
            lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            cardLabels.add(lbl);

            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    if (lbl.getIcon() != null) onZoom.accept(candidate.imageUrl());
                    if (!selected.contains(cardIdx))
                        lbl.setBorder(CardAnimation.createCardGlowBorder(Color.YELLOW));
                }
                @Override public void mouseExited(MouseEvent e) {
                    onZoomHide.run();
                    lbl.setBorder(selected.contains(cardIdx)
                            ? CardAnimation.createCardGlowBorder(Color.GREEN)
                            : BorderFactory.createLineBorder(Color.GRAY, 2));
                }
                @Override public void mousePressed(MouseEvent e) {
                    if (selected.contains(cardIdx)) {
                        selected.remove(Integer.valueOf(cardIdx));
                    } else if (selected.size() < maxCount) {
                        selected.add(cardIdx);
                    }
                    refresh.run();
                }
            });

            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(candidate.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();

            JLabel nameLabel = new JLabel(candidate.name(), SwingConstants.CENTER);
            nameLabel.setFont(FontLoader.loadPixelNESFont(9));
            nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
            wrapper.add(lbl,       BorderLayout.CENTER);
            wrapper.add(nameLabel, BorderLayout.SOUTH);
            currentRow.add(wrapper);
        }

        confirmBtn.addActionListener(ev -> { confirmed[0] = true; onZoomHide.run(); dlg.dispose(); });
        skipBtn.addActionListener(ev    -> {                       onZoomHide.run(); dlg.dispose(); });

        int rowHeight    = 12 + CARD_H + 4 + 18 + 12;
        int rowsToShow   = Math.min(2, (matches.size() + CARDS_PER_ROW - 1) / CARDS_PER_ROW);
        int colsInWidest = Math.min(matches.size(), CARDS_PER_ROW);
        int rowWidth     = 12 + colsInWidest * CARD_W + (colsInWidest - 1) * 12 + 12;

        JScrollPane scroll = new JScrollPane(cardsPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(rowHeight);
        int scrollbarPad = matches.size() > rowsToShow * CARDS_PER_ROW
                ? scroll.getVerticalScrollBar().getPreferredSize().width : 0;
        scroll.setPreferredSize(new Dimension(rowWidth + scrollbarPad, rowsToShow * rowHeight));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        btnRow.add(confirmBtn);
        btnRow.add(skipBtn);

        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.add(hint,   BorderLayout.CENTER);
        south.add(btnRow, BorderLayout.SOUTH);

        refresh.run();
        dlg.getContentPane().setLayout(new BorderLayout(0, 6));
        dlg.getContentPane().add(scroll, BorderLayout.CENTER);
        dlg.getContentPane().add(south,  BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);

        return confirmed[0] ? selected.stream().map(matches::get).collect(Collectors.toList()) : List.of();
    }

    /**
     * Single-window search picker for "up to 2 cards, each with a different cost".
     * Confirm is enabled only when 1 card is selected, or exactly 2 are selected with different costs.
     * Returns the chosen cards, or an empty list if skipped.
     */
    public List<CardData> pickTwoFromDeckSearchDifferentCost(List<CardData> matches) {
        JDialog dlg = new JDialog(owner,
                "Search — choose up to 2 cards (" + matches.size() + " found)", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        List<Integer> selected   = new ArrayList<>();
        List<JLabel>  cardLabels = new ArrayList<>(matches.size());
        boolean[]     confirmed  = { false };

        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.setEnabled(false);

        JButton skipBtn = new JButton("Skip");
        skipBtn.setFont(FontLoader.loadPixelNESFont(11));

        JLabel hint = new JLabel("", SwingConstants.CENTER);
        hint.setFont(FontLoader.loadPixelNESFont(9));

        Runnable refresh = () -> {
            int n = selected.size();
            boolean costsOk = n < 2 || matches.get(selected.get(0)).cost() != matches.get(selected.get(1)).cost();
            if (n == 0) {
                hint.setText("Select 1 or 2 cards (0/2)");
            } else if (n == 1) {
                hint.setText("Select 1 or 2 cards (1/2)");
            } else if (costsOk) {
                hint.setText("2 cards selected — different costs ✓");
            } else {
                hint.setText("Selected cards must have different costs");
            }
            confirmBtn.setEnabled(n >= 1 && costsOk);
            for (int i = 0; i < cardLabels.size(); i++) {
                boolean sel = selected.contains(i);
                boolean conflict = sel && n == 2 && !costsOk;
                cardLabels.get(i).setBorder(sel
                        ? CardAnimation.createCardGlowBorder(conflict ? Color.RED : Color.GREEN)
                        : BorderFactory.createLineBorder(Color.GRAY, 2));
            }
        };

        final int CARDS_PER_ROW = 10;
        JPanel cardsPanel = new JPanel();
        cardsPanel.setLayout(new BoxLayout(cardsPanel, BoxLayout.Y_AXIS));
        JPanel currentRow = null;
        for (int idx = 0; idx < matches.size(); idx++) {
            CardData candidate = matches.get(idx);
            final int cardIdx = idx;
            if (idx % CARDS_PER_ROW == 0) {
                currentRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 12));
                currentRow.setAlignmentX(Component.LEFT_ALIGNMENT);
                cardsPanel.add(currentRow);
            }
            JPanel wrapper = new JPanel(new BorderLayout(0, 4));
            wrapper.setBackground(cardsPanel.getBackground());

            JLabel lbl = new JLabel("...", SwingConstants.CENTER);
            lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
            lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
            lbl.setOpaque(true);
            lbl.setBackground(Color.DARK_GRAY);
            lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2));
            lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            cardLabels.add(lbl);

            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    if (lbl.getIcon() != null) onZoom.accept(candidate.imageUrl());
                    if (!selected.contains(cardIdx))
                        lbl.setBorder(CardAnimation.createCardGlowBorder(Color.YELLOW));
                }
                @Override public void mouseExited(MouseEvent e) {
                    onZoomHide.run();
                    refresh.run();
                }
                @Override public void mousePressed(MouseEvent e) {
                    if (selected.contains(cardIdx)) {
                        selected.remove(Integer.valueOf(cardIdx));
                    } else if (selected.size() < 2) {
                        selected.add(cardIdx);
                    }
                    refresh.run();
                }
            });

            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(candidate.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();

            JLabel nameLabel = new JLabel(
                    candidate.name() + " (cost " + candidate.cost() + ")", SwingConstants.CENTER);
            nameLabel.setFont(FontLoader.loadPixelNESFont(9));
            nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
            wrapper.add(lbl,       BorderLayout.CENTER);
            wrapper.add(nameLabel, BorderLayout.SOUTH);
            currentRow.add(wrapper);
        }

        confirmBtn.addActionListener(ev -> { confirmed[0] = true; onZoomHide.run(); dlg.dispose(); });
        skipBtn.addActionListener(ev    -> {                       onZoomHide.run(); dlg.dispose(); });

        int rowHeight    = 12 + CARD_H + 4 + 18 + 12;
        int rowsToShow   = Math.min(2, (matches.size() + CARDS_PER_ROW - 1) / CARDS_PER_ROW);
        int colsInWidest = Math.min(matches.size(), CARDS_PER_ROW);
        int rowWidth     = 12 + colsInWidest * CARD_W + (colsInWidest - 1) * 12 + 12;

        JScrollPane scroll = new JScrollPane(cardsPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(rowHeight);
        int scrollbarPad = matches.size() > rowsToShow * CARDS_PER_ROW
                ? scroll.getVerticalScrollBar().getPreferredSize().width : 0;
        scroll.setPreferredSize(new Dimension(rowWidth + scrollbarPad, rowsToShow * rowHeight));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        btnRow.add(confirmBtn);
        btnRow.add(skipBtn);

        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.add(hint,   BorderLayout.CENTER);
        south.add(btnRow, BorderLayout.SOUTH);

        refresh.run();
        dlg.getContentPane().setLayout(new BorderLayout(0, 6));
        dlg.getContentPane().add(scroll, BorderLayout.CENTER);
        dlg.getContentPane().add(south,  BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);

        return confirmed[0] ? selected.stream().map(matches::get).collect(Collectors.toList()) : List.of();
    }

    /**
     * Two-pool search picker where the two chosen cards must not share an element.
     * Returns {@code null} if the player dismisses the dialog; either slot may be {@code null}
     * if the player chose not to pick from that pool.
     */
    public CardData[] pickDualSearch(
            List<CardData> pool1, List<CardData> pool2, String label1, String label2) {
        CardData[] sel = { null, null };

        JDialog dlg = new JDialog(owner, "Search — " + label1 + " / " + label2, true);
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dlg.setResizable(false);

        JLabel hintLabel = new JLabel(" ", SwingConstants.CENTER);
        hintLabel.setFont(FontLoader.loadPixelNESFont(9));

        JButton okBtn = new JButton("OK");
        okBtn.addActionListener(e -> dlg.dispose());

        List<JLabel> labels1 = new ArrayList<>(pool1.size());
        List<JLabel> labels2 = new ArrayList<>(pool2.size());

        Runnable refresh = () -> {
            boolean ok;
            if (sel[0] == null || sel[1] == null) {
                ok = true;
                hintLabel.setText("Select up to 1 " + label1 + " and up to 1 " + label2 + ".");
            } else {
                boolean shares = dualSearchSharesElement(sel[0], sel[1]);
                ok = !shares;
                hintLabel.setText(shares ? "These cards share an element — choose cards with different elements."
                        : "Click OK to confirm.");
            }
            okBtn.setEnabled(ok);
            for (int i = 0; i < labels1.size(); i++)
                labels1.get(i).setBorder(pool1.get(i) == sel[0]
                        ? CardAnimation.createCardGlowBorder(Color.GREEN)
                        : BorderFactory.createLineBorder(Color.GRAY, 2));
            for (int i = 0; i < labels2.size(); i++)
                labels2.get(i).setBorder(pool2.get(i) == sel[1]
                        ? CardAnimation.createCardGlowBorder(Color.GREEN)
                        : BorderFactory.createLineBorder(Color.GRAY, 2));
        };

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        if (!pool1.isEmpty()) {
            JLabel hdr = new JLabel(label1, SwingConstants.LEFT);
            hdr.setFont(FontLoader.loadPixelNESFont(11));
            hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(hdr);
            content.add(buildDualSearchPoolPanel(pool1, labels1, 0, sel, refresh));
        }
        if (!pool1.isEmpty() && !pool2.isEmpty()) content.add(Box.createVerticalStrut(10));
        if (!pool2.isEmpty()) {
            JLabel hdr = new JLabel(label2, SwingConstants.LEFT);
            hdr.setFont(FontLoader.loadPixelNESFont(11));
            hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(hdr);
            content.add(buildDualSearchPoolPanel(pool2, labels2, 1, sel, refresh));
        }

        int colsInWidest = Math.min(Math.max(pool1.size(), pool2.size()), 10);
        int rowWidth     = 12 + colsInWidest * CARD_W + (colsInWidest - 1) * 12 + 12;
        int rowHeight    = 12 + CARD_H + 4 + 18 + 12;
        int sections     = (pool1.isEmpty() ? 0 : 1) + (pool2.isEmpty() ? 0 : 1);
        JScrollPane scroll = new JScrollPane(content,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setPreferredSize(new Dimension(rowWidth, sections * rowHeight + (sections > 1 ? 30 : 0)));

        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        bottom.add(hintLabel, BorderLayout.NORTH);
        bottom.add(okBtn, BorderLayout.SOUTH);

        refresh.run();
        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(scroll, BorderLayout.CENTER);
        dlg.getContentPane().add(bottom, BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
        return sel;
    }

    public static boolean dualSearchSharesElement(CardData a, CardData b) {
        for (String elem : List.of("fire", "ice", "wind", "earth", "lightning", "water", "light", "dark"))
            if (a.containsElement(elem) && b.containsElement(elem)) return true;
        return false;
    }

    private JPanel buildDualSearchPoolPanel(List<CardData> pool, List<JLabel> labels,
            int slot, CardData[] sel, Runnable refresh) {
        final int CARDS_PER_ROW = 10;
        JPanel cardsPanel = new JPanel();
        cardsPanel.setLayout(new BoxLayout(cardsPanel, BoxLayout.Y_AXIS));
        cardsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel currentRow = null;
        for (int idx = 0; idx < pool.size(); idx++) {
            CardData candidate = pool.get(idx);
            if (idx % CARDS_PER_ROW == 0) {
                currentRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 12));
                currentRow.setAlignmentX(Component.LEFT_ALIGNMENT);
                cardsPanel.add(currentRow);
            }
            JPanel wrapper = new JPanel(new BorderLayout(0, 4));
            wrapper.setBackground(cardsPanel.getBackground());

            JLabel lbl = new JLabel("...", SwingConstants.CENTER);
            lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
            lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
            lbl.setOpaque(true);
            lbl.setBackground(Color.DARK_GRAY);
            lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2));
            lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            labels.add(lbl);

            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    if (lbl.getIcon() != null) onZoom.accept(candidate.imageUrl());
                    if (candidate != sel[slot]) lbl.setBorder(CardAnimation.createCardGlowBorder(Color.YELLOW));
                }
                @Override public void mouseExited(MouseEvent e) {
                    onZoomHide.run();
                    lbl.setBorder(candidate == sel[slot]
                            ? CardAnimation.createCardGlowBorder(Color.GREEN)
                            : BorderFactory.createLineBorder(Color.GRAY, 2));
                }
                @Override public void mousePressed(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return;
                    sel[slot] = (sel[slot] == candidate) ? null : candidate;
                    refresh.run();
                }
            });

            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(candidate.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();

            JLabel nameLabel = new JLabel(candidate.name(), SwingConstants.CENTER);
            nameLabel.setFont(FontLoader.loadPixelNESFont(9));
            nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
            wrapper.add(lbl, BorderLayout.CENTER);
            wrapper.add(nameLabel, BorderLayout.SOUTH);
            currentRow.add(wrapper);
        }
        return cardsPanel;
    }

    // -------------------------------------------------------------------------
    // Single / multi image choosers
    // -------------------------------------------------------------------------

    /**
     * Shows cards as clickable images. Returns the chosen index, or {@code -1} if the
     * player closed the dialog (only possible when {@code allowCancel} is {@code true}).
     */
    public int pickCardImage(List<CardData> cards, String title, boolean allowCancel) {
        return pickCardImage(cards, title, allowCancel, true);
    }

    public int pickCardImage(List<CardData> cards, String title, boolean allowCancel, boolean showCost) {
        if (cards.isEmpty()) return -1;
        JDialog dlg = new JDialog(owner, title, true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(allowCancel ? JDialog.DISPOSE_ON_CLOSE : JDialog.DO_NOTHING_ON_CLOSE);

        int[] selection = { -1 };

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 12));
        for (int idx = 0; idx < cards.size(); idx++) {
            final int pos = idx;
            CardData candidate = cards.get(idx);
            JPanel wrapper = new JPanel(new BorderLayout(0, 4));
            wrapper.setBackground(cardsPanel.getBackground());

            JLabel lbl = new JLabel("...", SwingConstants.CENTER);
            lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
            lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
            lbl.setOpaque(true);
            lbl.setBackground(Color.DARK_GRAY);
            lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2));
            lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    if (lbl.getIcon() != null) onZoom.accept(candidate.imageUrl());
                    lbl.setBorder(CardAnimation.createCardGlowBorder(Color.YELLOW));
                }
                @Override public void mouseExited(MouseEvent e) {
                    onZoomHide.run();
                    lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
                }
                @Override public void mousePressed(MouseEvent e) {
                    selection[0] = pos;
                    onZoomHide.run();
                    dlg.dispose();
                }
            });

            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(candidate.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();

            JLabel nameLabel;
            if (showCost) {
                nameLabel = new JLabel("<html><div style='width:" + CARD_W + "px;text-align:center'>"
                        + candidate.name() + "<br>(Cost: " + candidate.cost() + ")" + "</div></html>",
                        SwingConstants.CENTER);
            } else {
                nameLabel = new JLabel(candidate.name(), SwingConstants.CENTER);
                nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
            }
            nameLabel.setFont(FontLoader.loadPixelNESFont(9));
            nameLabel.setPreferredSize(new Dimension(CARD_W, showCost ? 30 : 18));

            wrapper.add(lbl, BorderLayout.CENTER);
            wrapper.add(nameLabel, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        JLabel hint = new JLabel(allowCancel ? "Click a card to play it, or close to decline"
                : "Click a card to select it", SwingConstants.CENTER);
        hint.setFont(FontLoader.loadPixelNESFont(9));

        dlg.getContentPane().setLayout(new BorderLayout(0, 6));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(hint, BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);

        return selection[0];
    }

    /**
     * Multi-select image chooser. Returns the list of chosen indices in selection order,
     * or {@code null} if the player closed without confirming.
     */
    public List<Integer> pickMultiCardImage(List<CardData> cards, String title, int count,
            boolean eachDifferentType, boolean showCost) {
        if (cards.isEmpty() || count <= 0) return null;
        JDialog dlg = new JDialog(owner, title, true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        List<Integer> selected = new ArrayList<>();
        List<JLabel> labels = new ArrayList<>();
        boolean[] confirmed = { false };

        JButton selectBtn = new JButton("Select");
        selectBtn.setFont(FontLoader.loadPixelNESFont(11));
        selectBtn.setEnabled(false);

        JLabel hint = new JLabel("", SwingConstants.CENTER);
        hint.setFont(FontLoader.loadPixelNESFont(9));

        Runnable refresh = () -> {
            for (int i = 0; i < labels.size(); i++) {
                boolean sel = selected.contains(i);
                labels.get(i).setBorder(sel ? CardAnimation.createCardGlowBorder(Color.YELLOW)
                        : BorderFactory.createLineBorder(Color.GRAY, 2));
            }
            selectBtn.setEnabled(selected.size() == count);
            hint.setText("Select " + count + " card" + (count > 1 ? "s" : "")
                    + " (" + selected.size() + "/" + count + ")");
        };

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 12));
        for (int idx = 0; idx < cards.size(); idx++) {
            final int pos = idx;
            CardData candidate = cards.get(idx);
            JPanel wrapper = new JPanel(new BorderLayout(0, 4));
            wrapper.setBackground(cardsPanel.getBackground());

            JLabel lbl = new JLabel("...", SwingConstants.CENTER);
            lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
            lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
            lbl.setOpaque(true);
            lbl.setBackground(Color.DARK_GRAY);
            lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2));
            lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    if (lbl.getIcon() != null) onZoom.accept(candidate.imageUrl());
                }
                @Override public void mouseExited(MouseEvent e) { onZoomHide.run(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (!selected.remove(Integer.valueOf(pos)) && selected.size() < count) {
                        if (eachDifferentType) {
                            String key = discardTypeKey(candidate);
                            for (int s : selected)
                                if (discardTypeKey(cards.get(s)).equals(key)) return;
                        }
                        selected.add(pos);
                    }
                    refresh.run();
                }
            });

            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(candidate.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();

            JLabel nameLabel;
            if (showCost) {
                nameLabel = new JLabel("<html><div style='width:" + CARD_W + "px;text-align:center'>"
                        + candidate.name() + "<br>(Cost: " + candidate.cost() + ")" + "</div></html>",
                        SwingConstants.CENTER);
            } else {
                nameLabel = new JLabel(candidate.name(), SwingConstants.CENTER);
                nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
            }
            nameLabel.setFont(FontLoader.loadPixelNESFont(9));
            nameLabel.setPreferredSize(new Dimension(CARD_W, showCost ? 30 : 18));

            wrapper.add(lbl, BorderLayout.CENTER);
            wrapper.add(nameLabel, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
            labels.add(lbl);
        }

        selectBtn.addActionListener(ev -> {
            if (selected.size() != count) return;
            confirmed[0] = true;
            onZoomHide.run();
            dlg.dispose();
        });

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.setFont(FontLoader.loadPixelNESFont(11));
        cancelBtn.addActionListener(ev -> { onZoomHide.run(); dlg.dispose(); });

        refresh.run();

        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.add(hint, BorderLayout.CENTER);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER));
        btnRow.add(selectBtn);
        btnRow.add(cancelBtn);
        south.add(btnRow, BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new BorderLayout(0, 6));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south, BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);

        return confirmed[0] ? new ArrayList<>(selected) : null;
    }

    // -------------------------------------------------------------------------
    // Specialised pickers
    // -------------------------------------------------------------------------

    /**
     * EX Burst chooser. Returns the chosen card, or {@code null} if the player passes.
     */
    public CardData pickExBurst(List<CardData> eligible) {
        JDialog dlg = new JDialog(owner, "EX Burst — Choose 1 Card", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        CardData[] result = {null};
        int[] selectedIdx = {-1};

        JLabel statusLabel = new JLabel("Select 1 EX Burst card from your Damage Zone.", SwingConstants.CENTER);
        statusLabel.setFont(FontLoader.loadPixelNESFont(10));

        List<JLabel> cardLabels = new ArrayList<>();
        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

        JButton triggerBtn = new JButton("Trigger");
        triggerBtn.setFont(FontLoader.loadPixelNESFont(11));
        triggerBtn.setEnabled(false);
        JButton passBtn = new JButton("Pass");
        passBtn.setFont(FontLoader.loadPixelNESFont(11));

        Runnable refresh = () -> {
            triggerBtn.setEnabled(selectedIdx[0] >= 0);
            for (int j = 0; j < cardLabels.size(); j++) {
                boolean sel = selectedIdx[0] == j;
                cardLabels.get(j).setBorder(BorderFactory.createLineBorder(
                        sel ? Color.YELLOW : Color.LIGHT_GRAY, sel ? 3 : 1));
            }
        };

        for (int j = 0; j < eligible.size(); j++) {
            final int cardIdx = j;
            CardData cd = eligible.get(j);

            JPanel wrapper = new JPanel(new BorderLayout(0, 4));
            wrapper.setBackground(cardsPanel.getBackground());

            JLabel lbl = new JLabel("...", SwingConstants.CENTER);
            lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
            lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
            lbl.setOpaque(true);
            lbl.setBackground(Color.DARK_GRAY);
            lbl.setBorder(CardAnimation.createCardGlowBorder(Color.YELLOW));
            lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            cardLabels.add(lbl);

            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { if (lbl.getIcon() != null) onZoom.accept(cd.imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { onZoomHide.run(); }
                @Override public void mousePressed(MouseEvent e) {
                    selectedIdx[0] = selectedIdx[0] == cardIdx ? -1 : cardIdx;
                    refresh.run();
                }
            });

            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(cd.imageUrl());
                    if (img == null) return null;
                    BufferedImage buf = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g2 = buf.createGraphics();
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(img, 0, 0, CARD_W, CARD_H, null);
                    g2.dispose();
                    return new ImageIcon(buf);
                }
                @Override protected void done() {
                    try { ImageIcon icon = get(); if (icon != null) { lbl.setIcon(icon); lbl.setText(null); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();

            JLabel nameLabel = new JLabel(cd.name(), SwingConstants.CENTER);
            nameLabel.setFont(FontLoader.loadPixelNESFont(9));
            nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

            wrapper.add(lbl,       BorderLayout.CENTER);
            wrapper.add(nameLabel, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        triggerBtn.addActionListener(ae -> {
            onZoomHide.run();
            if (selectedIdx[0] >= 0) result[0] = eligible.get(selectedIdx[0]);
            dlg.dispose();
        });
        passBtn.addActionListener(ae -> { onZoomHide.run(); dlg.dispose(); });

        JScrollPane scroll = new JScrollPane(cardsPanel);
        scroll.setPreferredSize(new Dimension(Math.min(eligible.size() * (CARD_W + 16) + 20, 600), CARD_H + 60));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        btnPanel.add(triggerBtn);
        btnPanel.add(passBtn);

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

    /**
     * Single-select dialog. Returns the chosen index within {@code cards}, or {@code -1}
     * if the player cancels (only when {@code cancelable} is {@code true}).
     */
    public int pickOne(String title, String prompt, List<CardData> cards,
                       String confirmLabel, boolean cancelable) {
        if (cards.isEmpty()) return -1;
        JDialog dlg = new JDialog(owner, title, true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        int[] selectedIdx = {-1};
        int[] result      = {-1};

        JLabel statusLabel = new JLabel(prompt, SwingConstants.CENTER);
        statusLabel.setFont(FontLoader.loadPixelNESFont(10));

        List<JLabel> cardLabels = new ArrayList<>();
        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

        JButton confirmBtn = new JButton(confirmLabel);
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.setEnabled(false);

        Runnable refresh = () -> {
            confirmBtn.setEnabled(selectedIdx[0] >= 0);
            for (int j = 0; j < cardLabels.size(); j++) {
                boolean sel = selectedIdx[0] == j;
                cardLabels.get(j).setBorder(BorderFactory.createLineBorder(
                        sel ? Color.YELLOW : Color.LIGHT_GRAY, sel ? 3 : 1));
            }
        };

        for (int j = 0; j < cards.size(); j++) {
            final int cardIdx = j;
            CardData cd = cards.get(j);

            JPanel wrapper = new JPanel(new BorderLayout(0, 4));
            wrapper.setBackground(cardsPanel.getBackground());

            JLabel lbl = new JLabel("...", SwingConstants.CENTER);
            lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
            lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
            lbl.setOpaque(true);
            lbl.setBackground(Color.DARK_GRAY);
            lbl.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
            lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            cardLabels.add(lbl);

            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { if (lbl.getIcon() != null) onZoom.accept(cd.imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { onZoomHide.run(); }
                @Override public void mousePressed(MouseEvent e) {
                    selectedIdx[0] = selectedIdx[0] == cardIdx ? -1 : cardIdx;
                    refresh.run();
                }
            });

            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(cd.imageUrl());
                    if (img == null) return null;
                    BufferedImage buf = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g2 = buf.createGraphics();
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(img, 0, 0, CARD_W, CARD_H, null);
                    g2.dispose();
                    return new ImageIcon(buf);
                }
                @Override protected void done() {
                    try { ImageIcon icon = get(); if (icon != null) { lbl.setIcon(icon); lbl.setText(null); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();

            JLabel nameLabel = new JLabel(cd.name() + (cd.exBurst() ? " EX" : ""), SwingConstants.CENTER);
            nameLabel.setFont(FontLoader.loadPixelNESFont(9));
            nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

            wrapper.add(lbl,       BorderLayout.CENTER);
            wrapper.add(nameLabel, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        confirmBtn.addActionListener(ae -> {
            onZoomHide.run();
            result[0] = selectedIdx[0];
            dlg.dispose();
        });

        JScrollPane scroll = new JScrollPane(cardsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setPreferredSize(new Dimension(Math.min(cards.size() * (CARD_W + 16) + 20, 900), CARD_H + 60));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        btnPanel.add(confirmBtn);
        if (cancelable) {
            JButton cancelBtn = new JButton("Cancel");
            cancelBtn.setFont(FontLoader.loadPixelNESFont(11));
            cancelBtn.addActionListener(ae -> { onZoomHide.run(); dlg.dispose(); });
            btnPanel.add(cancelBtn);
        }

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
    // Non-card pickers
    // -------------------------------------------------------------------------

    /**
     * Party-attack damage assignment. Returns a map of attacker-slot-index → assigned damage,
     * or an empty map if the player dismissed the dialog.
     */
    public Map<Integer, Integer> assignPartyDamage(
            List<Integer> attackerIndices, List<CardData> attackerCards,
            int[] effectivePowers, int blockerPower) {
        int n = attackerIndices.size();
        int[] assigned = new int[n];

        JDialog dlg = new JDialog(owner, "Assign Blocker Damage", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel headerLabel = new JLabel(
                "Assign " + blockerPower + " damage across the party (multiples of 1000):",
                SwingConstants.CENTER);
        headerLabel.setFont(FontLoader.loadPixelNESFont(9));
        headerLabel.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));

        JLabel remainLabel = new JLabel("Remaining: " + blockerPower, SwingConstants.CENTER);
        remainLabel.setFont(FontLoader.loadPixelNESFont(10));
        remainLabel.setForeground(new Color(200, 80, 80));

        boolean[] confirmed = { false };
        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.setFocusPainted(false);
        confirmBtn.setEnabled(blockerPower == 0);

        final Runnable updateState = () -> {
            int total = 0;
            for (int a : assigned) total += a;
            int remaining = blockerPower - total;
            remainLabel.setText("Remaining: " + remaining);
            remainLabel.setForeground(remaining == 0 ? new Color(40, 160, 40) : new Color(200, 80, 80));
            confirmBtn.setEnabled(total == blockerPower);
        };

        confirmBtn.addActionListener(ae -> { confirmed[0] = true; dlg.dispose(); });

        JPanel attackersPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 8));
        JLabel[] valueLabels = new JLabel[n];

        for (int i = 0; i < n; i++) {
            final int slot = i;
            CardData card = attackerCards.get(i);
            int power = effectivePowers[i];

            JPanel cardPanel = new JPanel();
            cardPanel.setLayout(new BoxLayout(cardPanel, BoxLayout.Y_AXIS));
            cardPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

            JLabel nameLabel = new JLabel(
                    "<html><center>" + card.name() + "<br>(" + power + ")</center></html>",
                    SwingConstants.CENTER);
            nameLabel.setFont(FontLoader.loadPixelNESFont(8));
            nameLabel.setAlignmentX(0.5f);

            JLabel valueLabel = new JLabel("0", SwingConstants.CENTER);
            valueLabel.setFont(FontLoader.loadPixelNESFont(16));
            valueLabel.setPreferredSize(new Dimension(72, 40));
            valueLabel.setMinimumSize(new Dimension(72, 40));
            valueLabel.setOpaque(true);
            valueLabel.setBackground(Color.WHITE);
            valueLabel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color.DARK_GRAY, 2),
                    BorderFactory.createEmptyBorder(2, 6, 2, 6)));
            valueLabel.setAlignmentX(0.5f);
            valueLabels[i] = valueLabel;

            JButton leftBtn = new JButton("◄");
            leftBtn.setFont(FontLoader.loadPixelNESFont(11));
            leftBtn.setFocusPainted(false);
            leftBtn.addActionListener(ae -> {
                if (assigned[slot] >= 1000) {
                    assigned[slot] -= 1000;
                    valueLabels[slot].setText(String.valueOf(assigned[slot]));
                    updateState.run();
                }
            });

            JButton rightBtn = new JButton("►");
            rightBtn.setFont(FontLoader.loadPixelNESFont(11));
            rightBtn.setFocusPainted(false);
            rightBtn.addActionListener(ae -> {
                int total = 0;
                for (int a : assigned) total += a;
                if (total < blockerPower) {
                    assigned[slot] += 1000;
                    valueLabels[slot].setText(String.valueOf(assigned[slot]));
                    updateState.run();
                }
            });

            JPanel arrowRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
            arrowRow.setOpaque(false);
            arrowRow.add(leftBtn);
            arrowRow.add(valueLabel);
            arrowRow.add(rightBtn);

            cardPanel.add(nameLabel);
            cardPanel.add(javax.swing.Box.createVerticalStrut(4));
            cardPanel.add(arrowRow);
            attackersPanel.add(cardPanel);
        }

        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 8));
        south.add(confirmBtn);

        JPanel bottomArea = new JPanel(new BorderLayout());
        bottomArea.add(remainLabel, BorderLayout.NORTH);
        bottomArea.add(south,       BorderLayout.CENTER);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(headerLabel,    BorderLayout.NORTH);
        dlg.getContentPane().add(attackersPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(bottomArea,     BorderLayout.SOUTH);

        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);

        if (!confirmed[0]) return Map.of();
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < n; i++)
            if (assigned[i] > 0) result.put(attackerIndices.get(i), assigned[i]);
        return result;
    }

    /** Spinner dialog for choosing a number in {@code [min, max]}. */
    public int selectNumber(String prompt, int min, int max) {
        int[] value = { min };

        JDialog dlg = new JDialog(owner, "Select a Number", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel promptLabel = new JLabel(prompt, SwingConstants.CENTER);
        promptLabel.setFont(FontLoader.loadPixelNESFont(11));
        promptLabel.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));

        JLabel valueLabel = new JLabel(String.valueOf(value[0]), SwingConstants.CENTER);
        valueLabel.setFont(FontLoader.loadPixelNESFont(20));
        valueLabel.setPreferredSize(new Dimension(64, 48));
        valueLabel.setOpaque(true);
        valueLabel.setBackground(Color.WHITE);
        valueLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.DARK_GRAY, 2),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));

        JButton leftBtn = new JButton("◄");
        leftBtn.setFont(FontLoader.loadPixelNESFont(14));
        leftBtn.setFocusPainted(false);
        leftBtn.addActionListener(ae -> {
            if (value[0] > min) {
                value[0]--;
                valueLabel.setText(String.valueOf(value[0]));
            }
        });

        JButton rightBtn = new JButton("►");
        rightBtn.setFont(FontLoader.loadPixelNESFont(14));
        rightBtn.setFocusPainted(false);
        rightBtn.addActionListener(ae -> {
            if (value[0] < max) {
                value[0]++;
                valueLabel.setText(String.valueOf(value[0]));
            }
        });

        JPanel pickerRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        pickerRow.add(leftBtn);
        pickerRow.add(valueLabel);
        pickerRow.add(rightBtn);

        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.setFocusPainted(false);
        confirmBtn.addActionListener(ae -> dlg.dispose());

        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 8));
        south.add(confirmBtn);

        dlg.getContentPane().setLayout(new BorderLayout(0, 2));
        dlg.getContentPane().add(promptLabel, BorderLayout.NORTH);
        dlg.getContentPane().add(pickerRow,   BorderLayout.CENTER);
        dlg.getContentPane().add(south,       BorderLayout.SOUTH);

        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);

        return value[0];
    }

    /**
     * Shows a power-amount picker with ▲ / ▼ buttons, a 5-digit display, and an OK button.
     * Values step in increments of 1000 from 0 up to {@code maxAmount}, defaulting to the max.
     * Returns the chosen value.
     */
    public int selectPowerAmount(int maxAmount, String prompt) {
        int safeMax = Math.max(0, (maxAmount / 1000) * 1000);
        int[] value = { safeMax };

        JDialog dlg = new JDialog(owner, "Choose Power", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel promptLabel = new JLabel(prompt, SwingConstants.CENTER);
        promptLabel.setFont(FontLoader.loadPixelNESFont(11));
        promptLabel.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));

        JLabel valueLabel = new JLabel(String.format("%05d", value[0]), SwingConstants.CENTER);
        valueLabel.setFont(FontLoader.loadPixelNESFont(20));
        valueLabel.setPreferredSize(new Dimension(90, 48));
        valueLabel.setOpaque(true);
        valueLabel.setBackground(Color.WHITE);
        valueLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.DARK_GRAY, 2),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));

        JButton upBtn = new JButton("▲");
        upBtn.setFont(FontLoader.loadPixelNESFont(14));
        upBtn.setFocusPainted(false);
        upBtn.addActionListener(ae -> {
            if (value[0] < safeMax) {
                value[0] += 1000;
                valueLabel.setText(String.format("%05d", value[0]));
            }
        });

        JButton downBtn = new JButton("▼");
        downBtn.setFont(FontLoader.loadPixelNESFont(14));
        downBtn.setFocusPainted(false);
        downBtn.addActionListener(ae -> {
            if (value[0] > 0) {
                value[0] -= 1000;
                valueLabel.setText(String.format("%05d", value[0]));
            }
        });

        JButton okBtn = new JButton("OK");
        okBtn.setFont(FontLoader.loadPixelNESFont(11));
        okBtn.setFocusPainted(false);
        okBtn.addActionListener(ae -> dlg.dispose());

        JPanel pickerCol = new JPanel();
        pickerCol.setLayout(new BoxLayout(pickerCol, BoxLayout.Y_AXIS));
        pickerCol.setBorder(BorderFactory.createEmptyBorder(4, 20, 4, 20));
        upBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        valueLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        downBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        pickerCol.add(upBtn);
        pickerCol.add(Box.createVerticalStrut(6));
        pickerCol.add(valueLabel);
        pickerCol.add(Box.createVerticalStrut(6));
        pickerCol.add(downBtn);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 8));
        south.add(okBtn);

        dlg.getContentPane().setLayout(new BorderLayout(0, 2));
        dlg.getContentPane().add(promptLabel, BorderLayout.NORTH);
        dlg.getContentPane().add(pickerCol,   BorderLayout.CENTER);
        dlg.getContentPane().add(south,       BorderLayout.SOUTH);

        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);

        return value[0];
    }

    /** Returns the chosen per-card damage allocation, parallel to {@code cards}; entries sum to {@code damage}. */
    public List<Integer> selectDamageAmount(int damage, String prompt, List<CardData> cards) {
        JDialog dlg = new JDialog(owner, "Divide Damage", true);
        dlg.setResizable(false);
        // The full amount must be divided up before this can close — block the window's X button
        // (and Alt+F4) so it can't be used to bypass that requirement; only the OK button disposes it.
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel promptLabel = new JLabel(prompt, SwingConstants.CENTER);
        JLabel damageLabel = new JLabel(String.format(damage >= 10000 ? "%05d" : "%04d", damage));

        promptLabel.setFont(FontLoader.loadPixelNESFont(11));
        promptLabel.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        promptLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        damageLabel.setFont(FontLoader.loadPixelNESFont(11));
        damageLabel.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        damageLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        header.add(promptLabel);
        header.add(Box.createVerticalStrut(5));
        header.add(damageLabel);

        JPanel grid = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 10));

        // Total damage is tracked among all elements in values
        int[] totalDamage = {0};
        List<int[]> values = new ArrayList<>();

        // Declared before the per-card loop so the up/down listeners below can toggle it directly;
        // added to the layout later. Only allow confirming once the full amount has been divided up.
        JButton okBtn = new JButton("OK");
        okBtn.setFont(FontLoader.loadPixelNESFont(11));
        okBtn.setFocusPainted(false);
        okBtn.setEnabled(damage == 0);
        okBtn.addActionListener(e -> dlg.dispose());

        for (CardData c : cards) {
            int[] v = { 0 };
            values.add(v);

            JLabel valueLabel = new JLabel(String.format("%05d", v[0]), SwingConstants.CENTER);
            valueLabel.setFont(FontLoader.loadPixelNESFont(16));
            valueLabel.setOpaque(true);
            valueLabel.setBackground(Color.WHITE);
            valueLabel.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 2));
            valueLabel.setPreferredSize(new Dimension(80, 30));

            JButton upBtn = new JButton("▲");
            JButton downBtn = new JButton("▼");

            upBtn.setFont(FontLoader.loadPixelNESFont(12));
            downBtn.setFont(FontLoader.loadPixelNESFont(12));

            upBtn.setFocusPainted(false);
            downBtn.setFocusPainted(false);

            upBtn.addActionListener(e -> {
                if (v[0] < damage && totalDamage[0] < damage) {
                    v[0] += 1000;
                    totalDamage[0] += 1000;
                    damageLabel.setText(String.format("%05d", damage - totalDamage[0]));
                    valueLabel.setText(String.format("%05d", v[0]));
                    okBtn.setEnabled(totalDamage[0] == damage);
                }
            });

            downBtn.addActionListener(e -> {
                if (v[0] > 0 && totalDamage[0] > 0) {
                    v[0] -= 1000;
                    totalDamage[0] -= 1000;
                    damageLabel.setText(String.format("%05d", damage - totalDamage[0]));
                    valueLabel.setText(String.format("%05d", v[0]));
                    okBtn.setEnabled(totalDamage[0] == damage);
                }
            });

            JPanel cardPanel = new JPanel();
            cardPanel.setLayout(new BoxLayout(cardPanel, BoxLayout.Y_AXIS));
            cardPanel.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

            // Card image
            JLabel cardImage = new JLabel("...");
            cardImage.setAlignmentX(Component.CENTER_ALIGNMENT);

            upBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
            downBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
            valueLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(c.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try {
                        ImageIcon icon = get();
                        if (icon != null) { cardImage.setIcon(icon); cardImage.setText(null); }
                    } catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();

            cardPanel.add(cardImage);
            cardPanel.add(Box.createVerticalStrut(5));
            cardPanel.add(upBtn);
            cardPanel.add(valueLabel);
            cardPanel.add(downBtn);

            grid.add(cardPanel);
        }

        JScrollPane scroll = new JScrollPane(grid);
        scroll.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.BLACK, 1),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));

        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 8));
        south.add(okBtn);

        dlg.getContentPane().setLayout(new BorderLayout(0, 5));
        dlg.getContentPane().add(header, BorderLayout.NORTH);
        dlg.getContentPane().add(scroll, BorderLayout.CENTER);
        dlg.getContentPane().add(south, BorderLayout.SOUTH);

        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setSize(dlg.getSize().width + 125, dlg.getSize().getSize().height + 220);
        dlg.setVisible(true);

        List<Integer> result = new ArrayList<>();
        for (int[] v : values) result.add(v[0]);
        return result;
    }

    // -------------------------------------------------------------------------
    // Break-zone picker
    // -------------------------------------------------------------------------

    /**
     * Card selection from a break zone list. Callers are responsible for the early-exit
     * guards (empty list, auto-select when size ≤ maxCount) before invoking this.
     */
    public List<ForwardTarget> pickFromBreakZone(
            List<ForwardTarget> eligible, List<CardData> zone,
            int maxCount, boolean upTo, String title) {
        JDialog dlg = new JDialog(owner, title, true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        List<ForwardTarget> chosen = new ArrayList<>();
        Set<Integer> sel = new LinkedHashSet<>();
        List<JLabel> cardLabels = new ArrayList<>(eligible.size());

        JPanel opponentRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        JPanel selfRow     = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));

        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.setEnabled(false);

        javax.swing.border.Border normalBorder   = BorderFactory.createLineBorder(new Color(100, 100, 100), 2);
        javax.swing.border.Border selectedBorder = BorderFactory.createLineBorder(Color.YELLOW, 3);

        for (int i = 0; i < eligible.size(); i++) {
            ForwardTarget target = eligible.get(i);
            CardData card = zone.get(target.idx());
            final int fi = i;

            JLabel imgLbl = new JLabel("...", SwingConstants.CENTER);
            imgLbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
            imgLbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
            imgLbl.setOpaque(true);
            imgLbl.setBackground(Color.DARK_GRAY);
            imgLbl.setBorder(normalBorder);
            cardLabels.add(imgLbl);

            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(card.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try {
                        ImageIcon icon = get();
                        if (icon != null) { imgLbl.setIcon(icon); imgLbl.setText(null); }
                    } catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();

            MouseAdapter cardListener = new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { onZoom.accept(card.imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { onZoomHide.run(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (sel.contains(fi)) {
                        sel.remove(fi);
                        imgLbl.setBorder(normalBorder);
                    } else {
                        if (maxCount == 1) {
                            for (int si : sel) cardLabels.get(si).setBorder(normalBorder);
                            sel.clear();
                        } else if (sel.size() >= maxCount) {
                            return;
                        }
                        sel.add(fi);
                        imgLbl.setBorder(selectedBorder);
                    }
                    confirmBtn.setEnabled(upTo ? !sel.isEmpty() : sel.size() == maxCount);
                }
            };
            imgLbl.addMouseListener(cardListener);

            JLabel nameLbl = new JLabel(card.name(), SwingConstants.CENTER);
            nameLbl.setFont(FontLoader.loadPixelNESFont(9));
            nameLbl.setPreferredSize(new Dimension(CARD_W, 18));
            nameLbl.addMouseListener(cardListener);

            JPanel wrapper = new JPanel(new BorderLayout(0, 2));
            wrapper.setOpaque(false);
            wrapper.add(imgLbl,  BorderLayout.CENTER);
            wrapper.add(nameLbl, BorderLayout.SOUTH);

            if (!target.isP1()) opponentRow.add(wrapper);
            else                selfRow.add(wrapper);
        }

        confirmBtn.addActionListener(ae -> {
            for (int si : sel) chosen.add(eligible.get(si));
            dlg.dispose();
        });
        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        south.add(confirmBtn);
        if (upTo) {
            JButton skipBtn = new JButton("Skip");
            skipBtn.setFont(FontLoader.loadPixelNESFont(11));
            skipBtn.addActionListener(ae -> dlg.dispose());
            south.add(skipBtn);
        }

        JLabel hdr = new JLabel(title, SwingConstants.CENTER);
        hdr.setFont(FontLoader.loadPixelNESFont(11));
        hdr.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(hdr,                                        BorderLayout.NORTH);
        dlg.getContentPane().add(buildTwoRowTargetPanel(opponentRow, selfRow), BorderLayout.CENTER);
        dlg.getContentPane().add(south,                                        BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
        return List.copyOf(chosen);
    }

    // -------------------------------------------------------------------------
    // Hand-reveal pickers
    // -------------------------------------------------------------------------

    /** Shows {@code hand} and lets P1 optionally pick 1 card to remove from the game.
     *  Returns the chosen card, or {@code null} if the player clicks Skip. */
    public CardData pickOptional(List<CardData> hand) {
        JDialog dlg = new JDialog(owner, "Opponent's Hand — select 1 to remove from game (optional)", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        int[] selectedIdx = { -1 };
        CardData[] result = { null };

        java.util.List<JLabel> cardLabels = new ArrayList<>();
        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

        JLabel statusLabel = new JLabel("Click a card to select it, then Remove From Game — or Skip.", SwingConstants.CENTER);
        statusLabel.setFont(FontLoader.loadPixelNESFont(10));

        JButton confirmBtn = new JButton("Remove From Game");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.setEnabled(false);

        JButton skipBtn = new JButton("Skip");
        skipBtn.setFont(FontLoader.loadPixelNESFont(11));

        Runnable refresh = () -> {
            confirmBtn.setEnabled(selectedIdx[0] >= 0);
            for (int i = 0; i < cardLabels.size(); i++) {
                cardLabels.get(i).setBorder(BorderFactory.createLineBorder(
                        i == selectedIdx[0] ? new Color(0xff8800) : Color.LIGHT_GRAY,
                        i == selectedIdx[0] ? 3 : 1));
            }
        };

        for (int i = 0; i < hand.size(); i++) {
            final int idx = i;
            CardData cd = hand.get(i);

            JPanel wrapper = new JPanel(new BorderLayout(0, 4));
            wrapper.setBackground(cardsPanel.getBackground());

            JLabel lbl = HandPickDialog.makeCardLabel();
            lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            cardLabels.add(lbl);

            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { if (lbl.getIcon() != null) onZoom.accept(cd.imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { onZoomHide.run(); }
                @Override public void mousePressed(MouseEvent e) {
                    selectedIdx[0] = (selectedIdx[0] == idx) ? -1 : idx;
                    refresh.run();
                }
            });

            HandPickDialog.loadCardImage(lbl, cd.imageUrl());

            JLabel nameLabel = new JLabel(cd.name(), SwingConstants.CENTER);
            nameLabel.setFont(FontLoader.loadPixelNESFont(9));
            nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
            wrapper.add(lbl,       BorderLayout.CENTER);
            wrapper.add(nameLabel, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        confirmBtn.addActionListener(ae -> {
            onZoomHide.run();
            if (selectedIdx[0] >= 0 && selectedIdx[0] < hand.size())
                result[0] = hand.get(selectedIdx[0]);
            dlg.dispose();
        });
        skipBtn.addActionListener(ae -> { onZoomHide.run(); dlg.dispose(); });

        JScrollPane scrollPane = new JScrollPane(cardsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setPreferredSize(new Dimension(
                Math.min(hand.size() * (CARD_W + 16) + 16, 900),
                CARD_H + 60));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        btnRow.add(confirmBtn);
        btnRow.add(skipBtn);

        JPanel south = new JPanel(new BorderLayout(4, 4));
        south.add(statusLabel, BorderLayout.NORTH);
        south.add(btnRow,      BorderLayout.SOUTH);

        dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
        return result[0];
    }

    /** Shows {@code summons} from hand and lets P1 choose which ones to reveal (any number ≥ 0).
     *  Returns the selected cards. */
    public java.util.List<CardData> pickRevealSummons(java.util.List<CardData> summons,
                                                       String sourceName, int minForBonus) {
        if (summons.isEmpty()) return java.util.Collections.emptyList();

        Set<Integer> selected = new LinkedHashSet<>();
        java.util.List<CardData> result = new ArrayList<>();

        JDialog dlg = new JDialog(owner, sourceName + " — Reveal Summons", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        String hint = "Reveal 0 → " + sourceName + " breaks. Reveal " + minForBonus + "+ → bonus effect.";
        JLabel statusLabel = new JLabel(hint, SwingConstants.CENTER);
        statusLabel.setFont(FontLoader.loadPixelNESFont(9));

        java.util.List<JLabel> cardLabels = new ArrayList<>();
        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

        JButton confirmBtn = new JButton("Reveal 0");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));

        Runnable refresh = () -> {
            int n = selected.size();
            confirmBtn.setText(n == 0 ? "Reveal 0 (" + sourceName + " breaks)" : "Reveal " + n);
            for (int i = 0; i < cardLabels.size(); i++) {
                cardLabels.get(i).setBorder(BorderFactory.createLineBorder(
                        selected.contains(i) ? Color.CYAN : Color.LIGHT_GRAY,
                        selected.contains(i) ? 3 : 1));
            }
        };

        for (int i = 0; i < summons.size(); i++) {
            final int idx = i;
            CardData cd = summons.get(i);

            JPanel wrapper = new JPanel(new BorderLayout(0, 4));
            wrapper.setBackground(cardsPanel.getBackground());

            JLabel lbl = HandPickDialog.makeCardLabel();
            lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            cardLabels.add(lbl);

            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { if (lbl.getIcon() != null) onZoom.accept(cd.imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { onZoomHide.run(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (selected.contains(idx)) selected.remove(idx);
                    else selected.add(idx);
                    refresh.run();
                }
            });

            HandPickDialog.loadCardImage(lbl, cd.imageUrl());

            JLabel nameLabel = new JLabel(cd.name(), SwingConstants.CENTER);
            nameLabel.setFont(FontLoader.loadPixelNESFont(9));
            nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

            wrapper.add(lbl,       BorderLayout.CENTER);
            wrapper.add(nameLabel, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        confirmBtn.addActionListener(ae -> {
            onZoomHide.run();
            for (int i : selected) result.add(summons.get(i));
            dlg.dispose();
        });

        JScrollPane scrollPane = new JScrollPane(cardsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        south.add(statusLabel, BorderLayout.NORTH);
        south.add(confirmBtn,  BorderLayout.SOUTH);

        dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
        return result;
    }

    private static JPanel buildTwoRowTargetPanel(JPanel opponentRow, JPanel selfRow) {
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        if (opponentRow.getComponentCount() > 0) {
            JLabel lbl = new JLabel("— Opponent —", SwingConstants.CENTER);
            lbl.setFont(FontLoader.loadPixelNESFont(9));
            lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
            center.add(lbl);
            center.add(opponentRow);
        }
        if (selfRow.getComponentCount() > 0) {
            JLabel lbl = new JLabel("— Yours —", SwingConstants.CENTER);
            lbl.setFont(FontLoader.loadPixelNESFont(9));
            lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
            center.add(lbl);
            center.add(selfRow);
        }
        return center;
    }
}
