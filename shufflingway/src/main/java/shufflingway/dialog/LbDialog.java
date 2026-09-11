package shufflingway.dialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;

import shufflingway.CardData;
import shufflingway.FontLoader;
import shufflingway.ImageCache;
import shufflingway.graphics.CardAnimation;
import static shufflingway.graphics.CardAnimation.CARD_H;
import static shufflingway.graphics.CardAnimation.CARD_W;

public class LbDialog {

    public interface Callbacks {
        boolean isSpent(int idx);
        /**
         * Returns true when this card cannot be cast right now — a uniqueness, Light/Dark or
         * cast-restriction rule, or a cast limit that stops the player casting anything at all.
         *
         * <p>Asked only of a card being considered as the <em>cast</em>, never of one being picked
         * as payment: paying with a face-down card turns it face up where it sits and is not a
         * cast, so none of these rules bear on it.
         */
        boolean isCastBlocked(CardData card);
        int effectiveCastCost(CardData card);
        void onConfirm(CardData cast, int castIdx, Set<Integer> paymentSet);
        void onZoom(String url);
        void onZoomHide();
    }

    public static void show(JFrame owner, List<CardData> lbDeck, Callbacks cb) {
        if (lbDeck.isEmpty()) return;

        JDialog dlg = new JDialog(owner, "Limit Break Deck", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        int[] castingIdx    = { -1 };
        Set<Integer> paymentSet = new HashSet<>();

        JLabel statusLabel = new JLabel(" ", SwingConstants.CENTER);
        statusLabel.setFont(FontLoader.loadPixelFont(10));

        JButton confirmCastBtn = new JButton("Confirm Cast");
        confirmCastBtn.setFont(FontLoader.loadPixelFont(11));
        confirmCastBtn.setVisible(false);

        JButton cancelCastBtn = new JButton("Cancel");
        cancelCastBtn.setFont(FontLoader.loadPixelFont(11));
        cancelCastBtn.setVisible(false);

        List<JLabel> cardLabels = new ArrayList<>();
        JPanel cardsPanel = new JPanel(new GridLayout(0, 4, 8, 8));
        cardsPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        Runnable refreshLabels = () -> {
            for (int i = 0; i < cardLabels.size(); i++) {
                JLabel lbl = cardLabels.get(i);
                CardData lcd = lbDeck.get(i);
                boolean spent         = cb.isSpent(i);
                boolean casting       = (castingIdx[0] == i);
                boolean payment       = paymentSet.contains(i);
                boolean inPaymentMode = castingIdx[0] >= 0;
                boolean castBlocked   = !inPaymentMode && !spent && cb.isCastBlocked(lcd);

                if (casting) {
                    lbl.setBorder(CardAnimation.createCardGlowBorder(new Color(255, 200, 0)));
                } else if (payment) {
                    lbl.setBorder(CardAnimation.createCardGlowBorder(Color.CYAN));
                } else if (spent) {
                    lbl.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 60), 1));
                } else if (castBlocked) {
                    lbl.setBorder(CardAnimation.createCardGlowBorder(Color.RED));
                } else {
                    lbl.setBorder(BorderFactory.createLineBorder(
                            inPaymentMode ? Color.GRAY : Color.LIGHT_GRAY, 1));
                }
                boolean canInteract = !spent && !castBlocked && !casting
                        && (castingIdx[0] < 0 || !paymentSet.contains(i) || payment);
                lbl.setCursor(canInteract
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
            if (castingIdx[0] >= 0) {
                int needed = lbDeck.get(castingIdx[0]).lbCost() - paymentSet.size();
                statusLabel.setText(needed > 0
                        ? "Choose " + needed + " more LB card(s) as payment"
                        : "Ready — click Confirm Cast");
                confirmCastBtn.setEnabled(needed <= 0);
            } else {
                statusLabel.setText(" ");
            }
        };

        confirmCastBtn.addActionListener(ae -> {
            CardData cast = lbDeck.get(castingIdx[0]);
            int capturedCastIdx = castingIdx[0];
            Set<Integer> capturedPayment = new HashSet<>(paymentSet);
            dlg.dispose();
            cb.onConfirm(cast, capturedCastIdx, capturedPayment);
        });

        cancelCastBtn.addActionListener(ae -> {
            cb.onZoomHide();
            castingIdx[0] = -1;
            paymentSet.clear();
            confirmCastBtn.setVisible(false);
            cancelCastBtn.setVisible(false);
            refreshLabels.run();
        });

        for (int i = 0; i < lbDeck.size(); i++) {
            final int idx = i;
            CardData  cd  = lbDeck.get(i);

            JPanel cardWrapper = new JPanel(new BorderLayout(0, 4));
            cardWrapper.setBackground(cardsPanel.getBackground());

            JLabel lbl = new JLabel("...", SwingConstants.CENTER);
            lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
            lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
            lbl.setOpaque(true);
            lbl.setBackground(Color.DARK_GRAY);
            lbl.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
            cardLabels.add(lbl);

            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    if (lbl.getIcon() != null) cb.onZoom(cd.imageUrl());
                }
                @Override public void mouseExited(MouseEvent e) { cb.onZoomHide(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (cb.isSpent(idx)) return;
                    boolean castBlocked = castingIdx[0] < 0 && cb.isCastBlocked(cd);
                    if (castBlocked) return;

                    if (castingIdx[0] < 0) {
                        castingIdx[0] = idx;
                        paymentSet.clear();
                        confirmCastBtn.setVisible(true);
                        cancelCastBtn.setVisible(true);
                        confirmCastBtn.setEnabled(cd.lbCost() == 0);
                    } else if (castingIdx[0] == idx) {
                        castingIdx[0] = -1;
                        paymentSet.clear();
                        confirmCastBtn.setVisible(false);
                        cancelCastBtn.setVisible(false);
                    } else {
                        if (paymentSet.contains(idx)) {
                            paymentSet.remove(idx);
                        } else if (paymentSet.size() < lbDeck.get(castingIdx[0]).lbCost()) {
                            paymentSet.add(idx);
                        }
                    }
                    refreshLabels.run();
                }
            });

            final boolean spent = cb.isSpent(i);
            final int lbEffectiveCost = cb.effectiveCastCost(cd);
            final int lbCostDelta     = cd.cost() - lbEffectiveCost;
            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(cd.imageUrl());
                    if (img == null) return null;
                    BufferedImage buf = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g2 = buf.createGraphics();
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(img, 0, 0, CARD_W, CARD_H, null);
                    if (!spent && lbCostDelta != 0) {
                        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                        String text = String.valueOf(lbEffectiveCost);
                        g2.setFont(FontLoader.loadPixelFont(15));
                        FontMetrics fm = g2.getFontMetrics();
                        int x = 8, y = fm.getAscent() + 7;
                        g2.setColor(Color.BLACK);
                        g2.drawString(text, x + 1, y + 1);
                        g2.drawString(text, x + 2, y + 1);
                        g2.drawString(text, x + 1, y + 2);
                        g2.drawString(text, x + 2, y + 2);
                        g2.setColor(lbCostDelta > 0 ? new Color(0x44EE44) : new Color(0xFF8844));
                        g2.drawString(text, x, y);
                    }
                    g2.dispose();
                    if (spent) {
                        return new ImageIcon(new ColorConvertOp(
                                ColorSpace.getInstance(ColorSpace.CS_GRAY), null).filter(buf, null));
                    }
                    return new ImageIcon(buf);
                }
                @Override protected void done() {
                    try {
                        ImageIcon icon = get();
                        if (icon != null) { lbl.setIcon(icon); lbl.setText(null); }
                    } catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();

            JLabel nameLabel = new JLabel(cd.name() + " - LB " + cd.lbCost(), SwingConstants.CENTER);
            nameLabel.setFont(FontLoader.loadPixelFont(9));
            nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

            cardWrapper.add(lbl,       BorderLayout.CENTER);
            cardWrapper.add(nameLabel, BorderLayout.SOUTH);
            cardsPanel.add(cardWrapper);
        }

        refreshLabels.run();

        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 4));
        statusBar.add(statusLabel);
        statusBar.add(confirmCastBtn);
        statusBar.add(cancelCastBtn);

        JButton closeBtn = new JButton("Close");
        closeBtn.setFont(FontLoader.loadPixelFont(11));
        closeBtn.addActionListener(ae -> { cb.onZoomHide(); dlg.dispose(); });

        JPanel south = new JPanel(new BorderLayout());
        south.add(statusBar,  BorderLayout.CENTER);
        south.add(closeBtn,   BorderLayout.EAST);
        south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }

    /**
     * Asks the player which face-down LB card to turn face up, and returns its index in
     * {@code lbDeck}. 23-118H Ardyn is the one ability that asks.
     *
     * <p>Indices rather than cards, because an LB deck routinely holds several copies of a name and
     * turning one face up has to name which. {@code faceDown} is the pool of legal picks — the
     * caller has already excluded what is spent, including the card Ardyn has just played out of
     * the deck.
     *
     * <p>There is no decline. The turn-up is mandatory once the play it follows has happened, so
     * closing the window takes the first candidate rather than skipping the effect — the same
     * arrangement {@code BreakZoneDialog.choose} makes for the same reason.
     */
    public static Integer chooseFaceDown(JFrame owner, List<CardData> lbDeck, List<Integer> faceDown,
            Consumer<String> onZoom, Runnable onZoomHide) {
        if (faceDown.isEmpty()) return null;
        if (faceDown.size() == 1) return faceDown.get(0);

        JDialog dlg = new JDialog(owner, "Turn 1 face down LB card face up", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        int[] picked = { faceDown.get(0) };

        JPanel cardsPanel = new JPanel(new GridLayout(0, 4, 8, 8));
        cardsPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        for (int deckIdx : faceDown) {
            final int      idx = deckIdx;
            final CardData cd  = lbDeck.get(deckIdx);

            JPanel cardWrapper = new JPanel(new BorderLayout(0, 4));
            cardWrapper.setBackground(cardsPanel.getBackground());

            JLabel lbl = new JLabel("...", SwingConstants.CENTER);
            lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
            lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
            lbl.setOpaque(true);
            lbl.setBackground(Color.DARK_GRAY);
            lbl.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
            lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    if (lbl.getIcon() != null) onZoom.accept(cd.imageUrl());
                }
                @Override public void mouseExited(MouseEvent e) { onZoomHide.run(); }
                @Override public void mousePressed(MouseEvent e) {
                    picked[0] = idx;
                    onZoomHide.run();
                    dlg.dispose();
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
                    try {
                        ImageIcon icon = get();
                        if (icon != null) { lbl.setIcon(icon); lbl.setText(null); }
                    } catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();

            JLabel nameLabel = new JLabel(cd.name(), SwingConstants.CENTER);
            nameLabel.setFont(FontLoader.loadPixelFont(9));
            nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

            cardWrapper.add(lbl,       BorderLayout.CENTER);
            cardWrapper.add(nameLabel, BorderLayout.SOUTH);
            cardsPanel.add(cardWrapper);
        }

        JLabel hint = new JLabel("Click a card to turn it face up", SwingConstants.CENTER);
        hint.setFont(FontLoader.loadPixelFont(10));
        hint.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(hint,       BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
        return picked[0];
    }
}
