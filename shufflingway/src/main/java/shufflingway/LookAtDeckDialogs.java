package shufflingway;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;

import static shufflingway.CardAnimation.CARD_H;
import static shufflingway.CardAnimation.CARD_W;

/**
 * Modal dialogs that implement the "Look at the top N cards of your deck" family of effects.
 *
 * <p>Constructed by {@code MainWindow} and invoked through the
 * {@link GameContext#lookAtTopDeck(LookConfig)} bridge.  All callbacks into the
 * main window (logging, zoom, UI refreshes) are supplied via the {@link Callbacks} record.
 */
class LookAtDeckDialogs {

    /**
     * Callbacks into MainWindow for UI side-effects.
     *
     * @param log           append a line to the game log
     * @param showZoom      show the card-zoom overlay for the given image URL
     * @param hideZoom      dismiss the card-zoom overlay
     * @param refreshP1Deck refresh P1's deck-count label
     * @param refreshP2Deck refresh P2's deck-count label
     * @param refreshP1Hand refresh P1's hand label / count
     * @param refreshP2Hand refresh P2's hand count label
     * @param refreshP1Break refresh P1's break-zone label
     * @param refreshP2Break refresh P2's break-zone label
     * @param cardbackImage supplier for the current cardback image
     * @param animateDraw   triggers a deck→hand slide animation for the given player (isP1)
     * @param animateMill   triggers a deck→break-zone slide animation for the given player (isP1)
     */
    record Callbacks(
            Consumer<String>  log,
            Consumer<String>  showZoom,
            Runnable          hideZoom,
            Runnable          refreshP1Deck,
            Runnable          refreshP2Deck,
            Runnable          refreshP1Hand,
            Runnable          refreshP2Hand,
            Runnable          refreshP1Break,
            Runnable          refreshP2Break,
            Supplier<Image>   cardbackImage,
            Consumer<Boolean> animateDraw,
            Consumer<Boolean> animateMill
    ) {}

    private final JFrame     frame;
    private final GameState  gameState;
    private final Callbacks  cb;

    LookAtDeckDialogs(JFrame frame, GameState gameState, Callbacks cb) {
        this.frame     = frame;
        this.gameState = gameState;
        this.cb        = cb;
    }

    // ── Convenience forwarders ──────────────────────────────────────────────────

    private void log(String msg)      { cb.log().accept(msg); }
    private void showZoom(String url) { cb.showZoom().accept(url); }
    private void hideZoom()           { cb.hideZoom().run(); }

    // ── Public entry point ──────────────────────────────────────────────────────

    void show(LookConfig config, boolean isP1) {
        Deque<CardData> deck = isP1 ? gameState.getP1MainDeck() : gameState.getP2MainDeck();
        int n = Math.min(config.count(), deck.size());
        if (n == 0) { log("Look at top: deck is empty."); return; }

        List<CardData> peeked = new ArrayList<>();
        for (CardData c : deck) { peeked.add(c); if (peeked.size() >= n) break; }
        log("Look at top " + n + " card(s): " +
                peeked.stream().map(CardData::name)
                      .collect(java.util.stream.Collectors.joining(", ")));

        switch (config.action()) {
            case PEEK               -> showPeek(peeked, deck, isP1);
            case BREAK_OR_KEEP      -> showBreakOrKeep(peeked.get(0), deck, isP1);
            case BOTTOM_OR_KEEP     -> showBottomOrKeep(peeked.get(0), deck, isP1);
            case RETURN_TOP_ORDERED -> showReturnTopOrdered(peeked, deck, isP1);
            case ADD_TO_HAND_REST_BOTTOM         -> showAddToHandRestBottom(peeked, deck, isP1);
            case ADD_TO_HAND_ONE_TO_BREAK_REST_BOTTOM -> showAddToHandOneToBreakRestBottom(peeked, deck, isP1);
            case ADD_TO_HAND_REST_BREAK          -> showAddToHandRestBreak(peeked, deck, isP1);
            case TOP_OR_BOTTOM_ORDERED           -> showTopOrBottom(peeked, deck, isP1);
            case PICK_ONE_TOP_REST_BOTTOM        -> showPickOneTopRestBottom(peeked, deck, isP1);
        }
    }

    // ── Dialog implementations ──────────────────────────────────────────────────

    private void showBreakOrKeep(CardData top, Deque<CardData> deck, boolean isP1) {
        JDialog dlg = new JDialog(frame, "Top of Deck — " + top.name(), true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel cardLbl = makeCardLabel(top.imageUrl());
        cardLbl.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { showZoom(top.imageUrl()); }
            @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
        });

        boolean[] sendToBreak = { false };
        JButton breakBtn = new JButton("Break Zone");
        breakBtn.setFont(FontLoader.loadPixelNESFont(11));
        breakBtn.addActionListener(ae -> { sendToBreak[0] = true; hideZoom(); dlg.dispose(); });
        JButton keepBtn = new JButton("Keep on Top");
        keepBtn.setFont(FontLoader.loadPixelNESFont(11));
        keepBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        south.add(breakBtn); south.add(keepBtn);
        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardLbl, BorderLayout.CENTER);
        dlg.getContentPane().add(south,   BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        if (sendToBreak[0]) {
            deck.pollFirst();
            if (isP1) { gameState.getP1BreakZone().add(top); cb.refreshP1Break().run(); }
            else      { gameState.getP2BreakZone().add(top); cb.refreshP2Break().run(); }
            log(top.name() + " → Break Zone (from top of deck)");
        }
        if (isP1) cb.refreshP1Deck().run(); else cb.refreshP2Deck().run();
    }

    private void showBottomOrKeep(CardData top, Deque<CardData> deck, boolean isP1) {
        JDialog dlg = new JDialog(frame, "Top of Deck — " + top.name(), true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel cardLbl = makeCardLabel(top.imageUrl());
        cardLbl.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { showZoom(top.imageUrl()); }
            @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
        });

        boolean[] moveToBottom = { false };
        JButton bottomBtn = new JButton("Move to Bottom");
        bottomBtn.setFont(FontLoader.loadPixelNESFont(11));
        bottomBtn.addActionListener(ae -> { moveToBottom[0] = true; hideZoom(); dlg.dispose(); });
        JButton keepBtn = new JButton("Keep on Top");
        keepBtn.setFont(FontLoader.loadPixelNESFont(11));
        keepBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        south.add(bottomBtn); south.add(keepBtn);
        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardLbl, BorderLayout.CENTER);
        dlg.getContentPane().add(south,   BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        if (moveToBottom[0]) {
            deck.pollFirst();
            deck.addLast(top);
            log(top.name() + " → bottom of deck");
        }
        if (isP1) cb.refreshP1Deck().run(); else cb.refreshP2Deck().run();
    }

    private void showPeek(List<CardData> cards, Deque<CardData> deck, boolean isP1) {
        String title = cards.size() == 1
                ? "Top of Deck — " + cards.get(0).name()
                : "Top " + cards.size() + " Cards of Deck";
        JDialog dlg = new JDialog(frame, title, true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (CardData c : cards) {
            JLabel lbl = makeCardLabel(c.imageUrl());
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { showZoom(c.imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
            });
            cardsPanel.add(lbl);
        }

        JButton okBtn = new JButton("OK");
        okBtn.setFont(FontLoader.loadPixelNESFont(11));
        okBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });
        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        south.add(okBtn);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);
        if (isP1) cb.refreshP1Deck().run(); else cb.refreshP2Deck().run();
    }

    private void showReturnTopOrdered(List<CardData> cards, Deque<CardData> deck, boolean isP1) {
        int n = cards.size();
        JDialog dlg = new JDialog(frame, "Order Cards — Return to Top of Deck", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        List<CardData> order = new ArrayList<>(cards);
        Map<CardData, ImageIcon> imgCache = new LinkedHashMap<>();
        JLabel[] cardLabels = new JLabel[n];
        int[] selectedIdx = { -1 };

        Runnable updateLabels = () -> {
            for (int j = 0; j < n; j++) {
                ImageIcon ic = imgCache.get(order.get(j));
                if (ic != null) { cardLabels[j].setIcon(ic); cardLabels[j].setText(null); }
            }
        };

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            JLabel lbl = makeCardLabel(null);
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { showZoom(order.get(idx).imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (selectedIdx[0] == -1) {
                        selectedIdx[0] = idx;
                        cardLabels[idx].setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));
                    } else if (selectedIdx[0] == idx) {
                        selectedIdx[0] = -1;
                        cardLabels[idx].setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
                    } else {
                        int other = selectedIdx[0];
                        CardData tmp = order.get(idx); order.set(idx, order.get(other)); order.set(other, tmp);
                        updateLabels.run();
                        cardLabels[idx].setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
                        cardLabels[other].setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
                        selectedIdx[0] = -1;
                    }
                }
            });
            cardLabels[i] = lbl;
            cardsPanel.add(lbl);
        }

        for (CardData c : cards) {
            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(c.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try { ImageIcon ic = get(); if (ic != null) { imgCache.put(c, ic); updateLabels.run(); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();
        }

        JLabel instructions = new JLabel(
                "Click to select, click another to swap. Left = top of deck.", SwingConstants.CENTER);
        instructions.setFont(FontLoader.loadPixelNESFont(9));
        JButton confirmBtn = new JButton("Confirm Order");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(instructions, BorderLayout.NORTH);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        btnRow.add(confirmBtn);
        south.add(btnRow, BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        for (int i = 0; i < n; i++) deck.pollFirst();
        for (int i = n - 1; i >= 0; i--) deck.addFirst(order.get(i));
        log("Returned to top (topmost first): " +
                order.stream().map(CardData::name)
                      .collect(java.util.stream.Collectors.joining(", ")));
        if (isP1) cb.refreshP1Deck().run(); else cb.refreshP2Deck().run();
    }

    private void showAddToHandRestBottom(List<CardData> cards, Deque<CardData> deck, boolean isP1) {
        int n = cards.size();
        JDialog dlg = new JDialog(frame, "Look — Add to Hand, Return Rest to Bottom", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        List<CardData> order = new ArrayList<>(cards);
        Map<CardData, ImageIcon> imgCache = new LinkedHashMap<>();
        JLabel[] cardLabels = new JLabel[n];
        int[] handLblIdx    = { -1 };
        int[] selectedForSwap = { -1 };

        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.setEnabled(false);

        Runnable updateLabels = () -> {
            for (int j = 0; j < n; j++) {
                ImageIcon ic = imgCache.get(order.get(j));
                if (ic != null) { cardLabels[j].setIcon(ic); cardLabels[j].setText(null); }
            }
        };
        Runnable refreshBorders = () -> {
            for (int j = 0; j < n; j++) {
                if (j == handLblIdx[0])
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(new Color(0, 200, 80), 3));
                else if (j == selectedForSwap[0])
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));
                else
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
            }
        };

        javax.swing.JToggleButton[] handBtns = new javax.swing.JToggleButton[n];
        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            JLabel lbl = makeCardLabel(null);
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { showZoom(order.get(idx).imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (idx == handLblIdx[0]) return;
                    if (selectedForSwap[0] == -1) {
                        selectedForSwap[0] = idx;
                    } else if (selectedForSwap[0] == idx) {
                        selectedForSwap[0] = -1;
                    } else {
                        int other = selectedForSwap[0];
                        if (other == handLblIdx[0]) { selectedForSwap[0] = idx; refreshBorders.run(); return; }
                        CardData tmp = order.get(idx); order.set(idx, order.get(other)); order.set(other, tmp);
                        updateLabels.run();
                        selectedForSwap[0] = -1;
                    }
                    refreshBorders.run();
                }
            });
            cardLabels[i] = lbl;

            javax.swing.JToggleButton handBtn = new javax.swing.JToggleButton("→ Hand");
            handBtn.setFont(FontLoader.loadPixelNESFont(9));
            handBtns[i] = handBtn;
            handBtn.addItemListener(ie -> {
                if (ie.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                    for (int j = 0; j < n; j++) if (j != idx && handBtns[j].isSelected()) handBtns[j].setSelected(false);
                    handLblIdx[0] = idx;
                    selectedForSwap[0] = -1;
                    confirmBtn.setEnabled(true);
                } else {
                    handLblIdx[0] = -1;
                    confirmBtn.setEnabled(false);
                }
                refreshBorders.run();
            });

            JPanel wrapper = new JPanel(new BorderLayout(0, 2));
            wrapper.setOpaque(false);
            wrapper.add(lbl,     BorderLayout.CENTER);
            wrapper.add(handBtn, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        for (CardData c : cards) {
            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(c.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try { ImageIcon ic = get(); if (ic != null) { imgCache.put(c, ic); updateLabels.run(); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();
        }

        JLabel instructions = new JLabel(
                "Click '→ Hand' to pick the card for your hand. Swap the rest to order them (left = first at bottom).",
                SwingConstants.CENTER);
        instructions.setFont(FontLoader.loadPixelNESFont(9));
        confirmBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(instructions, BorderLayout.NORTH);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        btnRow.add(confirmBtn);
        south.add(btnRow, BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        for (int i = 0; i < n; i++) deck.pollFirst();
        CardData handCard = handLblIdx[0] >= 0 ? order.get(handLblIdx[0]) : order.get(0);
        if (isP1) { gameState.getP1Hand().add(handCard); cb.refreshP1Hand().run(); }
        else      { gameState.getP2Hand().add(handCard); cb.refreshP2Hand().run(); }
        log(handCard.name() + " → hand");
        for (CardData c : order) {
            if (c != handCard) { deck.addLast(c); log(c.name() + " → bottom of deck"); }
        }
        if (isP1) cb.refreshP1Deck().run(); else cb.refreshP2Deck().run();
    }

    private void showAddToHandOneToBreakRestBottom(List<CardData> cards, Deque<CardData> deck, boolean isP1) {
        int n = cards.size();
        // dest slot 0 = Hand, slot 1 = Break Zone, slots 2..n-1 = Deck Bottom (left = placed first = deeper)
        String[] destLabels = new String[n];
        destLabels[0] = "Hand";
        if (n > 1) destLabels[1] = "Break Zone";
        for (int i = 2; i < n; i++) destLabels[i] = "Deck Bottom";

        CardData[] destCards = new CardData[n];
        boolean[]  placed    = new boolean[n];
        int[]      selTop    = { -1 };

        JButton okBtn = new JButton("OK");
        okBtn.setFont(FontLoader.loadPixelNESFont(11));
        okBtn.setEnabled(false);

        Map<CardData, ImageIcon> imgCache = new LinkedHashMap<>();
        JLabel[] topLabels = new JLabel[n];
        JLabel[] botLabels = new JLabel[n];

        Runnable checkOk = () -> {
            for (CardData d : destCards) if (d == null) { okBtn.setEnabled(false); return; }
            okBtn.setEnabled(true);
        };

        Runnable refreshTopBorders = () -> {
            for (int j = 0; j < n; j++) {
                if (placed[j])
                    topLabels[j].setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
                else if (j == selTop[0])
                    topLabels[j].setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));
                else
                    topLabels[j].setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
        }};

        // build top row (source cards)
        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            JLabel lbl = makeCardLabel(null);
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { if (!placed[idx]) showZoom(cards.get(idx).imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (placed[idx]) return;
                    selTop[0] = (selTop[0] == idx) ? -1 : idx;
                    refreshTopBorders.run();
                }
            });
            topLabels[i] = lbl;
            topRow.add(lbl);
        }

        // build bottom row (destination slots)
        JPanel botRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (int i = 0; i < n; i++) {
            final int slotIdx = i;
            Color labelColor = slotIdx == 0 ? new Color(0, 200, 80)
                             : slotIdx == 1 ? new Color(220, 80, 80)
                             : new Color(100, 180, 255);

            JLabel slotCard = makeCardLabel(null);
            slotCard.setText("?");
            slotCard.setBackground(new Color(40, 40, 40));
            slotCard.setBorder(BorderFactory.createLineBorder(labelColor.darker(), 1));
            slotCard.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { if (destCards[slotIdx] != null) showZoom(destCards[slotIdx].imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (selTop[0] != -1) {
                        // place selected card into this slot
                        if (destCards[slotIdx] != null) {
                            // return the evicted card to top row
                            int evictIdx = cards.indexOf(destCards[slotIdx]);
                            if (evictIdx >= 0) placed[evictIdx] = false;
                        }
                        int topIdx = selTop[0];
                        destCards[slotIdx] = cards.get(topIdx);
                        placed[topIdx] = true;
                        selTop[0] = -1;
                        ImageIcon ic = imgCache.get(cards.get(topIdx));
                        slotCard.setIcon(ic);
                        slotCard.setText(ic != null ? null : cards.get(topIdx).name());
                        slotCard.setBorder(BorderFactory.createLineBorder(labelColor, 3));
                        refreshTopBorders.run();
                        checkOk.run();
                    } else if (destCards[slotIdx] != null) {
                        // unassign: return card to top row
                        int evictIdx = cards.indexOf(destCards[slotIdx]);
                        if (evictIdx >= 0) placed[evictIdx] = false;
                        destCards[slotIdx] = null;
                        slotCard.setIcon(null);
                        slotCard.setText("?");
                        slotCard.setBorder(BorderFactory.createLineBorder(labelColor.darker(), 1));
                        refreshTopBorders.run();
                        checkOk.run();
                    }
                }
            });
            botLabels[i] = slotCard;

            JLabel destNameLbl = new JLabel(destLabels[i], SwingConstants.CENTER);
            destNameLbl.setFont(FontLoader.loadPixelNESFont(9));
            destNameLbl.setForeground(labelColor);

            JPanel wrapper = new JPanel(new BorderLayout(0, 2));
            wrapper.setOpaque(false);
            wrapper.add(slotCard,    BorderLayout.CENTER);
            wrapper.add(destNameLbl, BorderLayout.SOUTH);
            botRow.add(wrapper);
        }

        // async image loading
        for (CardData c : cards) {
            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(c.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try {
                        ImageIcon ic = get();
                        if (ic == null) return;
                        imgCache.put(c, ic);
                        int j = cards.indexOf(c);
                        topLabels[j].setIcon(ic);
                        topLabels[j].setText(null);
                        for (int s = 0; s < n; s++) {
                            if (destCards[s] == c) { botLabels[s].setIcon(ic); botLabels[s].setText(null); }
                        }
                    } catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();
        }

        JDialog dlg = new JDialog(frame, "Look — Assign Cards to Destinations", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel instructions = new JLabel(
                "Select a card in the top row, then click a destination. Click it again to return it.",
                SwingConstants.CENTER);
        instructions.setFont(FontLoader.loadPixelNESFont(9));
        okBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel centerPanel = new JPanel(new BorderLayout(0, 8));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        centerPanel.add(topRow, BorderLayout.NORTH);
        centerPanel.add(botRow, BorderLayout.SOUTH);

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(instructions, BorderLayout.NORTH);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        btnRow.add(okBtn);
        south.add(btnRow, BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(centerPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,       BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        // apply results — fill any unassigned slots with remaining unplaced cards (safety fallback)
        List<CardData> unplaced = new ArrayList<>();
        for (int i = 0; i < n; i++) if (!placed[i]) unplaced.add(cards.get(i));
        int ui = 0;
        for (int s = 0; s < n; s++) if (destCards[s] == null && ui < unplaced.size()) destCards[s] = unplaced.get(ui++);

        for (int i = 0; i < n; i++) deck.pollFirst();

        CardData handCard  = destCards[0];
        CardData breakCard = n > 1 ? destCards[1] : null;
        if (handCard != null) {
            if (isP1) { gameState.getP1Hand().add(handCard); cb.refreshP1Hand().run(); }
            else      { gameState.getP2Hand().add(handCard); cb.refreshP2Hand().run(); }
            log(handCard.name() + " → hand");
        }
        if (breakCard != null) {
            if (isP1) { gameState.getP1BreakZone().add(breakCard); cb.refreshP1Break().run(); }
            else      { gameState.getP2BreakZone().add(breakCard); cb.refreshP2Break().run(); }
            log(breakCard.name() + " → Break Zone");
        }
        for (int i = 2; i < n; i++) {
            if (destCards[i] != null) { deck.addLast(destCards[i]); log(destCards[i].name() + " → bottom of deck"); }
        }
        if (handCard  != null) cb.animateDraw().accept(isP1);
        if (breakCard != null) cb.animateMill().accept(isP1);
        if (isP1) cb.refreshP1Deck().run(); else cb.refreshP2Deck().run();
    }

    private void showAddToHandRestBreak(List<CardData> cards, Deque<CardData> deck, boolean isP1) {
        int n = cards.size();
        JDialog dlg = new JDialog(frame, "Look — Add to Hand, Rest to Break Zone", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        int[] handLblIdx = { -1 };
        JLabel[] cardLabels = new JLabel[n];
        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.setEnabled(false);

        javax.swing.JToggleButton[] handBtns = new javax.swing.JToggleButton[n];
        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final CardData c = cards.get(i);
            JLabel lbl = makeCardLabel(c.imageUrl());
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { showZoom(c.imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
            });
            cardLabels[i] = lbl;

            javax.swing.JToggleButton handBtn = new javax.swing.JToggleButton("→ Hand");
            handBtn.setFont(FontLoader.loadPixelNESFont(9));
            handBtns[i] = handBtn;
            handBtn.addItemListener(ie -> {
                if (ie.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                    for (int j = 0; j < n; j++) if (j != idx && handBtns[j].isSelected()) handBtns[j].setSelected(false);
                    handLblIdx[0] = idx;
                    for (int j = 0; j < n; j++)
                        cardLabels[j].setBorder(BorderFactory.createLineBorder(
                                j == idx ? new Color(0, 200, 80) : new Color(160, 110, 220),
                                j == idx ? 3 : 1));
                    confirmBtn.setEnabled(true);
                } else {
                    handLblIdx[0] = -1;
                    for (JLabel l : cardLabels) l.setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
                    confirmBtn.setEnabled(false);
                }
            });

            JPanel wrapper = new JPanel(new BorderLayout(0, 2));
            wrapper.setOpaque(false);
            wrapper.add(lbl,     BorderLayout.CENTER);
            wrapper.add(handBtn, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        JLabel instructions = new JLabel(
                "Click '→ Hand' to choose a card. The rest go to the Break Zone.", SwingConstants.CENTER);
        instructions.setFont(FontLoader.loadPixelNESFont(9));
        confirmBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(instructions, BorderLayout.NORTH);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        btnRow.add(confirmBtn);
        south.add(btnRow, BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        for (int i = 0; i < n; i++) deck.pollFirst();
        int hi = handLblIdx[0] >= 0 ? handLblIdx[0] : 0;
        CardData handCard = cards.get(hi);
        if (isP1) { gameState.getP1Hand().add(handCard); cb.refreshP1Hand().run(); }
        else      { gameState.getP2Hand().add(handCard); cb.refreshP2Hand().run(); }
        log(handCard.name() + " → hand");
        for (int i = 0; i < n; i++) {
            if (i != hi) {
                CardData c = cards.get(i);
                if (isP1) gameState.getP1BreakZone().add(c);
                else      gameState.getP2BreakZone().add(c);
                log(c.name() + " → Break Zone");
            }
        }
        if (isP1) { cb.refreshP1Break().run(); cb.refreshP1Deck().run(); }
        else      { cb.refreshP2Break().run(); cb.refreshP2Deck().run(); }
    }

    /** Top/bottom ordering dialog — custom drag-and-drop canvas with 20-second countdown. */
    private void showTopOrBottom(List<CardData> cards, Deque<CardData> deck, boolean isP1) {
        final int TW = 80, TH = 117, GAP = 8, SEP_H = 30;
        final int n      = cards.size();
        final int panelW = Math.max(600, 2 * n * (TW + GAP) + TW + 4 * GAP);
        final int panelH = TH + GAP * 2 + SEP_H + TH + GAP;

        final List<CardData>              stagingList = new ArrayList<>(cards);
        final List<CardData>              topCards    = new ArrayList<>();
        final List<CardData>              bottomCards = new ArrayList<>();
        final Map<CardData, Rectangle>    cardBounds  = new LinkedHashMap<>();
        final Map<CardData, BufferedImage> thumbs     = new LinkedHashMap<>();
        final CardData[] dragging = { null };
        final int[] dragX = { 0 }, dragY = { 0 };
        final Image deckBack = cb.cardbackImage().get();

        final Runnable computeLayout = () -> {
            cardBounds.clear();
            int deckX = panelW / 2 - TW / 2;
            int zoneY = TH + GAP * 2 + SEP_H;

            int stagingVisible = 0;
            for (CardData c : stagingList) if (c != dragging[0]) stagingVisible++;
            int stagW = stagingVisible * TW + Math.max(0, stagingVisible - 1) * GAP;
            int sx0   = (panelW - stagW) / 2;
            int si    = 0;
            for (CardData c : stagingList) {
                if (c == dragging[0]) continue;
                cardBounds.put(c, new Rectangle(sx0 + si * (TW + GAP), GAP, TW, TH));
                si++;
            }

            int topVisible = 0;
            for (CardData c : topCards) if (c != dragging[0]) topVisible++;
            int ti = 0;
            for (CardData c : topCards) {
                if (c == dragging[0]) continue;
                cardBounds.put(c, new Rectangle(deckX - GAP - (topVisible - ti) * (TW + GAP), zoneY, TW, TH));
                ti++;
            }

            int bi = 0;
            for (CardData c : bottomCards) {
                if (c == dragging[0]) continue;
                cardBounds.put(c, new Rectangle(deckX + TW + GAP + bi * (TW + GAP), zoneY, TW, TH));
                bi++;
            }
        };

        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.setEnabled(false);

        JLabel timerLabel = new JLabel("20s", SwingConstants.CENTER);
        timerLabel.setFont(FontLoader.loadPixelNESFont(12));
        timerLabel.setForeground(new Color(255, 220, 0));

        JDialog dlg = new JDialog(frame, "Order Cards — Top or Bottom of Deck", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel canvas = new JPanel(null) {
            @Override public Dimension getPreferredSize() { return new Dimension(panelW, panelH); }
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                computeLayout.run();
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);

                int deckX = panelW / 2 - TW / 2;
                int zoneY = TH + GAP * 2 + SEP_H;

                g2.setColor(new Color(35, 35, 55));
                g2.fillRect(0, 0, panelW, TH + GAP * 2);
                g2.setColor(new Color(25, 45, 25));
                g2.fillRect(0, zoneY - 4, deckX - GAP / 2, TH + 8);
                g2.setColor(new Color(45, 25, 25));
                g2.fillRect(deckX + TW + GAP / 2, zoneY - 4, panelW - deckX - TW - GAP / 2, TH + 8);

                g2.setFont(FontLoader.loadPixelNESFont(9));
                FontMetrics fm = g2.getFontMetrics();

                g2.setColor(Color.LIGHT_GRAY);
                String stageLbl = stagingList.isEmpty() ? "All cards placed!" : "Drag cards to the Top or Bottom zone";
                g2.drawString(stageLbl, (panelW - fm.stringWidth(stageLbl)) / 2, TH + GAP * 2 + SEP_H / 2);

                g2.setColor(new Color(140, 210, 140));
                String topLbl = "← Top of Deck (left = topmost)";
                g2.drawString(topLbl, Math.max(4, (deckX - fm.stringWidth(topLbl)) / 2), zoneY - 6);

                g2.setColor(new Color(210, 140, 140));
                String botLbl = "Bottom of Deck (left = first below) →";
                int bZoneX = deckX + TW + GAP;
                g2.drawString(botLbl, bZoneX + (panelW - bZoneX - fm.stringWidth(botLbl)) / 2, zoneY - 6);

                if (deckBack != null) {
                    g2.drawImage(deckBack, deckX, zoneY, TW, TH, null);
                    g2.setColor(new Color(0, 0, 0, 70));
                    g2.fillRect(deckX, zoneY, TW, TH);
                } else {
                    g2.setColor(Color.GRAY); g2.fillRect(deckX, zoneY, TW, TH);
                }
                g2.setColor(new Color(160, 110, 220));
                g2.drawRect(deckX, zoneY, TW - 1, TH - 1);

                for (Map.Entry<CardData, Rectangle> entry : cardBounds.entrySet()) {
                    Rectangle r = entry.getValue();
                    BufferedImage img = thumbs.get(entry.getKey());
                    if (img != null) g2.drawImage(img, r.x, r.y, r.width, r.height, null);
                    else { g2.setColor(Color.DARK_GRAY); g2.fillRect(r.x, r.y, r.width, r.height); }
                    g2.setColor(new Color(160, 110, 220));
                    g2.drawRect(r.x, r.y, r.width - 1, r.height - 1);
                }

                if (dragging[0] != null) {
                    int gx = dragX[0] - TW / 2, gy = dragY[0] - TH / 2;
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f));
                    BufferedImage img = thumbs.get(dragging[0]);
                    if (img != null) g2.drawImage(img, gx, gy, TW, TH, null);
                    else { g2.setColor(new Color(160, 110, 220, 150)); g2.fillRect(gx, gy, TW, TH); }
                    g2.setComposite(AlphaComposite.SrcOver);
                }
                g2.dispose();
            }
        };
        canvas.setBackground(new Color(30, 30, 30));

        for (CardData c : cards) {
            new SwingWorker<BufferedImage, Void>() {
                @Override protected BufferedImage doInBackground() throws Exception {
                    Image img = ImageCache.load(c.imageUrl());
                    if (img == null) return null;
                    BufferedImage buf = new BufferedImage(TW, TH, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = buf.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(img, 0, 0, TW, TH, null);
                    g.dispose();
                    return buf;
                }
                @Override protected void done() {
                    try { BufferedImage bi = get(); if (bi != null) { thumbs.put(c, bi); canvas.repaint(); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();
        }

        canvas.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                computeLayout.run();
                for (Map.Entry<CardData, Rectangle> entry : cardBounds.entrySet()) {
                    if (entry.getValue().contains(e.getX(), e.getY())) {
                        dragging[0] = entry.getKey();
                        dragX[0] = e.getX(); dragY[0] = e.getY();
                        canvas.repaint(); return;
                    }
                }
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (dragging[0] == null) return;
                CardData card = dragging[0];
                dragging[0] = null;
                stagingList.remove(card); topCards.remove(card); bottomCards.remove(card);

                int deckX = panelW / 2 - TW / 2;
                int ex = e.getX(), ey = e.getY();

                if (ey < TH + GAP * 2) {
                    int sz = stagingList.size();
                    int tw = sz * TW + Math.max(0, sz - 1) * GAP;
                    int sx = (panelW - tw) / 2, idx = sz;
                    for (int i = 0; i < sz; i++) { if (ex < sx + i * (TW + GAP) + TW / 2) { idx = i; break; } }
                    stagingList.add(idx, card);
                } else if (ex < deckX) {
                    int sz = topCards.size(), idx = sz;
                    for (int i = 0; i < sz; i++) {
                        if (ex < deckX - GAP - (sz - i) * (TW + GAP) + TW / 2) { idx = i; break; }
                    }
                    topCards.add(idx, card);
                } else if (ex > deckX + TW) {
                    int bZoneX = deckX + TW + GAP, sz = bottomCards.size(), idx = sz;
                    for (int i = 0; i < sz; i++) {
                        if (ex < bZoneX + i * (TW + GAP) + TW / 2) { idx = i; break; }
                    }
                    bottomCards.add(idx, card);
                } else {
                    stagingList.add(card);
                }
                confirmBtn.setEnabled(stagingList.isEmpty());
                canvas.repaint();
            }
        });
        canvas.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (dragging[0] != null) { dragX[0] = e.getX(); dragY[0] = e.getY(); canvas.repaint(); }
            }
            @Override public void mouseMoved(MouseEvent e) {
                computeLayout.run();
                for (Map.Entry<CardData, Rectangle> entry : cardBounds.entrySet()) {
                    if (entry.getValue().contains(e.getX(), e.getY())) {
                        showZoom(entry.getKey().imageUrl()); return;
                    }
                }
                hideZoom();
            }
        });

        int[] timeLeft = { 20 };
        javax.swing.Timer[] timerHolder = { null };
        javax.swing.Timer countdown = new javax.swing.Timer(1000, ae -> {
            int t = --timeLeft[0];
            timerLabel.setText(t + "s");
            if (t <= 5) timerLabel.setForeground(Color.RED);
            if (t <= 0) {
                timerHolder[0].stop();
                topCards.addAll(stagingList);
                stagingList.clear();
                hideZoom();
                dlg.dispose();
            }
        });
        timerHolder[0] = countdown;

        confirmBtn.addActionListener(ae -> { countdown.stop(); hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 6));
        south.add(timerLabel);
        south.add(confirmBtn);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(canvas, BorderLayout.CENTER);
        dlg.getContentPane().add(south,  BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        countdown.start();
        dlg.setVisible(true);
        countdown.stop();

        for (int i = 0; i < n; i++) deck.pollFirst();
        for (int i = topCards.size() - 1; i >= 0; i--) deck.addFirst(topCards.get(i));
        for (CardData c : bottomCards) deck.addLast(c);
        if (!topCards.isEmpty())
            log("→ Top of deck: " +
                    topCards.stream().map(CardData::name)
                            .collect(java.util.stream.Collectors.joining(", ")));
        if (!bottomCards.isEmpty())
            log("→ Bottom of deck: " +
                    bottomCards.stream().map(CardData::name)
                            .collect(java.util.stream.Collectors.joining(", ")));
        if (isP1) cb.refreshP1Deck().run(); else cb.refreshP2Deck().run();
    }

    void showRevealAddUpToMatchingRestBottom(List<CardData> cards, Deque<CardData> deck,
            boolean isP1, int maxAdd, String jobFilter, String categoryFilter, String cardNameFilter, String typeFilter, int maxCost) {
        int n = cards.size();
        JDialog dlg = new JDialog(frame, "Reveal — Add to Hand, Rest to Bottom", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        List<CardData> order = new ArrayList<>(cards);
        Map<CardData, ImageIcon> imgCache = new LinkedHashMap<>();
        JLabel[] cardLabels = new JLabel[n];
        Set<CardData> handSet = new LinkedHashSet<>();
        int[] selectedForSwap = { -1 };
        boolean[] updating = { false };

        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.setEnabled(true);

        Runnable updateLabels = () -> {
            for (int j = 0; j < n; j++) {
                ImageIcon ic = imgCache.get(order.get(j));
                if (ic != null) { cardLabels[j].setIcon(ic); cardLabels[j].setText(null); }
            }
        };

        javax.swing.JToggleButton[] handBtns = new javax.swing.JToggleButton[n];

        Runnable refreshHandButtons = () -> {
            int count = handSet.size();
            for (int j = 0; j < n; j++) {
                CardData c = order.get(j);
                boolean isChar = c.isForward() || c.isBackup() || c.isMonster();
                boolean noFilters = jobFilter == null && categoryFilter == null
                        && cardNameFilter == null && typeFilter == null;
                boolean matches = noFilters
                        || (jobFilter      != null && CardFilters.meetsJobFilter(c, jobFilter))
                        || (categoryFilter != null && CardFilters.meetsCategoryFilter(c, categoryFilter))
                        || (cardNameFilter != null && CardFilters.meetsCardNameFilter(c, cardNameFilter))
                        || (typeFilter     != null && meetsRevealTypeFilter(c, typeFilter));
                boolean costOk = maxCost < 0 || c.cost() <= maxCost;
                boolean inHand = handSet.contains(c);
                handBtns[j].setEnabled(isChar && matches && costOk && (inHand || count < maxAdd));
            }
        };

        Runnable refreshBorders = () -> {
            for (int j = 0; j < n; j++) {
                CardData c = order.get(j);
                if (handSet.contains(c))
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(new Color(0, 200, 80), 3));
                else if (j == selectedForSwap[0])
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));
                else
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
            }
        };

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            JLabel lbl = makeCardLabel(null);
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { showZoom(order.get(idx).imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
                @Override public void mousePressed(MouseEvent e) {
                    CardData c = order.get(idx);
                    if (handSet.contains(c)) return;
                    if (selectedForSwap[0] == -1) {
                        selectedForSwap[0] = idx;
                    } else if (selectedForSwap[0] == idx) {
                        selectedForSwap[0] = -1;
                    } else {
                        int other = selectedForSwap[0];
                        if (handSet.contains(order.get(other))) { selectedForSwap[0] = idx; refreshBorders.run(); return; }
                        CardData tmp = order.get(idx); order.set(idx, order.get(other)); order.set(other, tmp);
                        updateLabels.run();
                        updating[0] = true;
                        for (int j = 0; j < n; j++) handBtns[j].setSelected(handSet.contains(order.get(j)));
                        updating[0] = false;
                        refreshHandButtons.run();
                        selectedForSwap[0] = -1;
                    }
                    refreshBorders.run();
                }
            });
            cardLabels[i] = lbl;

            javax.swing.JToggleButton handBtn = new javax.swing.JToggleButton("→ Hand");
            handBtn.setFont(FontLoader.loadPixelNESFont(9));
            handBtns[i] = handBtn;
            handBtn.addItemListener(ie -> {
                if (updating[0]) return;
                CardData c = order.get(idx);
                if (ie.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                    handSet.add(c);
                } else {
                    handSet.remove(c);
                }
                selectedForSwap[0] = -1;
                refreshHandButtons.run();
                refreshBorders.run();
            });

            JPanel wrapper = new JPanel(new BorderLayout(0, 2));
            wrapper.setOpaque(false);
            wrapper.add(lbl,     BorderLayout.CENTER);
            wrapper.add(handBtn, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        // Initial button enabled state
        refreshHandButtons.run();

        for (CardData c : cards) {
            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(c.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try { ImageIcon ic = get(); if (ic != null) { imgCache.put(c, ic); updateLabels.run(); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();
        }

        String filterDesc = typeFilter     != null ? typeFilter + "s"
                : jobFilter      != null ? "Job [" + jobFilter + "] Characters"
                : categoryFilter != null ? "Category [" + categoryFilter + "] Characters"
                : "Characters";
        if (maxCost >= 0) filterDesc += " of cost " + maxCost + " or less";
        JLabel instructions = new JLabel(
                "Toggle '→ Hand' on " + filterDesc + " (up to " + maxAdd + "). Swap the rest to order (left = first at bottom).",
                SwingConstants.CENTER);
        instructions.setFont(FontLoader.loadPixelNESFont(9));
        confirmBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(instructions, BorderLayout.NORTH);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        btnRow.add(confirmBtn);
        south.add(btnRow, BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        for (int i = 0; i < n; i++) deck.pollFirst();
        for (CardData c : handSet) {
            if (isP1) gameState.getP1Hand().add(c);
            else      gameState.getP2Hand().add(c);
            log(c.name() + " → hand");
        }
        if (!handSet.isEmpty()) {
            if (isP1) cb.refreshP1Hand().run();
            else      cb.refreshP2Hand().run();
        }
        for (CardData c : order) {
            if (!handSet.contains(c)) { deck.addLast(c); log(c.name() + " → bottom of deck"); }
        }
        if (isP1) cb.refreshP1Deck().run();
        else      cb.refreshP2Deck().run();
    }

    /**
     * Reveals {@code cards} (already peeked from the top of {@code deck}).
     * Player chooses exactly 1 card whose name matches {@code cardName} to play onto
     * the field; the remaining cards go to the bottom of the deck in any order.
     * If no matching card exists, all cards go to the bottom and nothing is played.
     */
    void showRevealPlayNamedOntoFieldRestBottom(List<CardData> cards, Deque<CardData> deck,
            boolean isP1, String cardName, Consumer<CardData> playOntoField) {
        int n = cards.size();
        JDialog dlg = new JDialog(frame, "Reveal — Play " + cardName + " onto Field, Rest to Bottom", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        List<CardData> order    = new ArrayList<>(cards);
        Map<CardData, ImageIcon> imgCache = new LinkedHashMap<>();
        JLabel[] cardLabels     = new JLabel[n];
        CardData[] chosenToPlay = { null };
        int[] selectedForSwap   = { -1 };
        boolean[] updating      = { false };

        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));

        Runnable updateLabels = () -> {
            for (int j = 0; j < n; j++) {
                ImageIcon ic = imgCache.get(order.get(j));
                if (ic != null) { cardLabels[j].setIcon(ic); cardLabels[j].setText(null); }
            }
        };

        javax.swing.JToggleButton[] fieldBtns = new javax.swing.JToggleButton[n];

        Runnable refreshFieldButtons = () -> {
            for (int j = 0; j < n; j++) {
                CardData c   = order.get(j);
                boolean eligible = c.name().equalsIgnoreCase(cardName);
                boolean chosen   = chosenToPlay[0] == c;
                fieldBtns[j].setEnabled(eligible && (chosen || chosenToPlay[0] == null));
            }
        };

        Runnable refreshBorders = () -> {
            for (int j = 0; j < n; j++) {
                CardData c = order.get(j);
                if (chosenToPlay[0] == c)
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(new Color(0, 200, 80), 3));
                else if (j == selectedForSwap[0])
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));
                else
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
            }
        };

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            JLabel lbl = makeCardLabel(null);
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { showZoom(order.get(idx).imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
                @Override public void mousePressed(MouseEvent e) {
                    CardData c = order.get(idx);
                    if (chosenToPlay[0] == c) return;
                    if (selectedForSwap[0] == -1) {
                        selectedForSwap[0] = idx;
                    } else if (selectedForSwap[0] == idx) {
                        selectedForSwap[0] = -1;
                    } else {
                        int other = selectedForSwap[0];
                        if (chosenToPlay[0] == order.get(other)) { selectedForSwap[0] = idx; refreshBorders.run(); return; }
                        CardData tmp = order.get(idx); order.set(idx, order.get(other)); order.set(other, tmp);
                        updateLabels.run();
                        updating[0] = true;
                        for (int j = 0; j < n; j++) fieldBtns[j].setSelected(chosenToPlay[0] == order.get(j));
                        updating[0] = false;
                        refreshFieldButtons.run();
                        selectedForSwap[0] = -1;
                    }
                    refreshBorders.run();
                }
            });
            cardLabels[i] = lbl;

            javax.swing.JToggleButton fieldBtn = new javax.swing.JToggleButton("→ Field");
            fieldBtn.setFont(FontLoader.loadPixelNESFont(9));
            fieldBtns[i] = fieldBtn;
            fieldBtn.addItemListener(ie -> {
                if (updating[0]) return;
                CardData c = order.get(idx);
                if (ie.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                    chosenToPlay[0] = c;
                } else {
                    if (chosenToPlay[0] == c) chosenToPlay[0] = null;
                }
                selectedForSwap[0] = -1;
                refreshFieldButtons.run();
                refreshBorders.run();
            });

            JPanel wrapper = new JPanel(new BorderLayout(0, 2));
            wrapper.setOpaque(false);
            wrapper.add(lbl,      BorderLayout.CENTER);
            wrapper.add(fieldBtn, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        refreshFieldButtons.run();

        for (CardData c : cards) {
            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(c.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try { ImageIcon ic = get(); if (ic != null) { imgCache.put(c, ic); updateLabels.run(); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();
        }

        JLabel instructions = new JLabel(
                "Click '→ Field' on 1 Card Name " + cardName + " to play. Swap the rest to order (left = first at bottom).",
                SwingConstants.CENTER);
        instructions.setFont(FontLoader.loadPixelNESFont(9));
        confirmBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(instructions, BorderLayout.NORTH);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        btnRow.add(confirmBtn);
        south.add(btnRow, BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        for (int i = 0; i < n; i++) deck.pollFirst();
        CardData played = chosenToPlay[0];
        for (CardData c : order) {
            if (c == played) continue;
            deck.addLast(c);
            log(c.name() + " → bottom of deck");
        }
        if (isP1) cb.refreshP1Deck().run(); else cb.refreshP2Deck().run();

        if (played != null) {
            log(played.name() + " played onto field");
            playOntoField.accept(played);
        } else {
            log("No Card Name " + cardName + " selected — all cards to bottom");
        }
    }

    /**
     * "Look at N cards. Put 1 on top of your deck and the other(s) to the bottom."
     *
     * <p>Player picks exactly one card to remain on top.  All other peeked cards go to the
     * bottom of the deck in the order they were peeked.  For the canonical N=2 form there is
     * one binary choice; for N=1 the only card stays on top with no UI prompt.
     */
    private void showPickOneTopRestBottom(List<CardData> cards, Deque<CardData> deck, boolean isP1) {
        int n = cards.size();

        // Pop the peeked cards off the deck up front; we'll push them back in the chosen order.
        for (int i = 0; i < n; i++) deck.pollFirst();

        // Trivial case: only one card peeked — it just stays on top.
        if (n == 1) {
            deck.addFirst(cards.get(0));
            log(cards.get(0).name() + " → top of deck");
            if (isP1) cb.refreshP1Deck().run(); else cb.refreshP2Deck().run();
            return;
        }

        JDialog dlg = new JDialog(frame, "Look — Pick 1 for Top of Deck", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.setEnabled(false);

        int[] topIdx = { -1 };
        javax.swing.JToggleButton[] topBtns = new javax.swing.JToggleButton[n];
        JLabel[] cardLabels = new JLabel[n];

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            JLabel lbl = makeCardLabel(cards.get(i).imageUrl());
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { showZoom(cards.get(idx).imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
            });
            cardLabels[i] = lbl;

            javax.swing.JToggleButton btn = new javax.swing.JToggleButton("→ Top");
            btn.setFont(FontLoader.loadPixelNESFont(9));
            topBtns[i] = btn;
            btn.addItemListener(ie -> {
                if (ie.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                    for (int j = 0; j < n; j++) if (j != idx && topBtns[j].isSelected()) topBtns[j].setSelected(false);
                    topIdx[0] = idx;
                    confirmBtn.setEnabled(true);
                } else if (topIdx[0] == idx) {
                    topIdx[0] = -1;
                    confirmBtn.setEnabled(false);
                }
                for (int j = 0; j < n; j++) {
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(
                            j == topIdx[0] ? new Color(0, 200, 80) : new Color(160, 110, 220),
                            j == topIdx[0] ? 3 : 1));
                }
            });

            JPanel wrapper = new JPanel(new BorderLayout(0, 2));
            wrapper.setOpaque(false);
            wrapper.add(lbl, BorderLayout.CENTER);
            wrapper.add(btn, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        JLabel instructions = new JLabel(
                "Pick exactly 1 card to put on top of your deck. The rest go to the bottom.",
                SwingConstants.CENTER);
        instructions.setFont(FontLoader.loadPixelNESFont(9));

        confirmBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(instructions, BorderLayout.NORTH);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        btnRow.add(confirmBtn);
        south.add(btnRow, BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        int chosen = topIdx[0] >= 0 ? topIdx[0] : 0;
        CardData topCard = cards.get(chosen);
        deck.addFirst(topCard);
        log(topCard.name() + " → top of deck");
        for (int i = 0; i < n; i++) {
            if (i == chosen) continue;
            deck.addLast(cards.get(i));
            log(cards.get(i).name() + " → bottom of deck");
        }
        if (isP1) cb.refreshP1Deck().run(); else cb.refreshP2Deck().run();
    }

    // ── Shared helpers ──────────────────────────────────────────────────────────

    /** Creates a standard card-image label with placeholder styling. Pass {@code null} url to skip async load. */
    private JLabel makeCardLabel(String imageUrl) {
        JLabel lbl = new JLabel("...", SwingConstants.CENTER);
        lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
        lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
        lbl.setOpaque(true);
        lbl.setBackground(Color.DARK_GRAY);
        lbl.setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
        if (imageUrl != null) {
            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(imageUrl);
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();
        }
        return lbl;
    }

    private static boolean meetsRevealTypeFilter(CardData c, String type) {
        return switch (type.toLowerCase()) {
            case "monster"   -> c.isMonster();
            case "forward"   -> c.isForward();
            case "backup"    -> c.isBackup();
            case "character" -> c.isForward() || c.isBackup() || c.isMonster();
            default          -> false;
        };
    }
}
