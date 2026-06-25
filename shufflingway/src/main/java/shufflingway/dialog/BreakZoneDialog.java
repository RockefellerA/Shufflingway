package shufflingway.dialog;

import shufflingway.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.*;
import shufflingway.graphics.CardAnimation;
import static shufflingway.graphics.CardAnimation.*;

public class BreakZoneDialog {

    public interface Callbacks {
        boolean hasBzAbility(CardData card);
        boolean hasBzPlay(CardData card);
        int bzPlayCost(CardData card);
        boolean isAbilityEnabled(ActionAbility ability, CardData card);
        String abilityMenuHtml(ActionAbility ability);
        String abilityMenuText(ActionAbility ability);
        void onBzPlay(CardData card, int cost);
        void onBzAbility(ActionAbility ability, CardData card);
        void onZoom(String url);
        void onZoomHide();
    }

    public static void show(JFrame owner, List<CardData> zone, String title,
                             boolean isP1, Callbacks cb) {
        if (zone.isEmpty()) return;

        JDialog dlg = new JDialog(owner, title + " (" + zone.size() + " cards)", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

        for (CardData cd : zone) {
            final boolean hasBzAbility = cb.hasBzAbility(cd);
            final boolean hasBzPlay    = cb.hasBzPlay(cd);
            final int     bzPlayCost   = hasBzPlay ? cb.bzPlayCost(cd) : -1;
            final boolean interactive  = hasBzAbility || hasBzPlay;

            JPanel cardWrapper = new JPanel(new BorderLayout(0, 4));
            cardWrapper.setBackground(cardsPanel.getBackground());

            JLabel lbl = new JLabel("...", SwingConstants.CENTER);
            lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
            lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
            lbl.setOpaque(true);
            lbl.setBackground(Color.DARK_GRAY);
            lbl.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
            if (interactive) lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    if (lbl.getIcon() != null) cb.onZoom(cd.imageUrl());
                }
                @Override public void mouseExited(MouseEvent e) { cb.onZoomHide(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (!isP1 || !SwingUtilities.isLeftMouseButton(e)) return;
                    boolean anyBzAbility = cd.actionAbilities().stream()
                            .anyMatch(a -> a.breakZoneOnly() != null);
                    if (!anyBzAbility && !hasBzPlay) return;
                    cb.onZoomHide();
                    JPopupMenu menu = new JPopupMenu();
                    if (hasBzPlay) {
                        JMenuItem playItem = new JMenuItem("Play  (" + bzPlayCost + " CP)");
                        playItem.addActionListener(ae -> {
                            dlg.dispose();
                            cb.onBzPlay(cd, bzPlayCost);
                        });
                        menu.add(playItem);
                    }
                    for (ActionAbility ability : cd.actionAbilities()) {
                        if (ability.breakZoneOnly() == null) continue;
                        boolean abilityEnabled = cb.isAbilityEnabled(ability, cd);
                        JMenuItem item = new JMenuItem(abilityEnabled
                                ? cb.abilityMenuHtml(ability) : cb.abilityMenuText(ability));
                        item.setEnabled(abilityEnabled);
                        item.addActionListener(ae -> cb.onBzAbility(ability, cd));
                        menu.add(item);
                    }
                    if (menu.getComponentCount() > 0) menu.show(lbl, e.getX(), e.getY());
                }
            });

            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(cd.imageUrl());
                    if (img == null) return null;
                    BufferedImage buf = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g2 = buf.createGraphics();
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.drawImage(img, 0, 0, CARD_W, CARD_H, null);
                    if (hasBzAbility) CardAnimation.drawGlow(g2, new Color(30, 144, 255), 0, 0, CARD_W, CARD_H);
                    if (hasBzPlay) {
                        CardAnimation.drawGlow(g2, new Color(0, 220, 80), 0, 0, CARD_W, CARD_H);
                        int badgeW = 26, badgeH = 22;
                        int bx = 4, by = 4;
                        g2.setColor(new Color(0, 0, 0, 200));
                        g2.fillRoundRect(bx, by, badgeW, badgeH, 6, 6);
                        g2.setColor(new Color(0, 220, 80));
                        g2.drawRoundRect(bx, by, badgeW, badgeH, 6, 6);
                        g2.setColor(Color.WHITE);
                        g2.setFont(FontLoader.loadPixelNESFont(11));
                        String costStr = String.valueOf(bzPlayCost);
                        FontMetrics fm = g2.getFontMetrics();
                        int tx = bx + (badgeW - fm.stringWidth(costStr)) / 2;
                        int ty = by + (badgeH - fm.getHeight()) / 2 + fm.getAscent();
                        g2.drawString(costStr, tx, ty);
                    }
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
            nameLabel.setFont(FontLoader.loadPixelNESFont(9));
            nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

            cardWrapper.add(lbl,       BorderLayout.CENTER);
            cardWrapper.add(nameLabel, BorderLayout.SOUTH);
            cardsPanel.add(cardWrapper);
        }

        JScrollPane scrollPane = new JScrollPane(cardsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setPreferredSize(new Dimension(
                Math.min(zone.size() * (CARD_W + 16) + 16, 900),
                CARD_H + 60));

        JButton closeBtn = new JButton("Close");
        closeBtn.setFont(FontLoader.loadPixelNESFont(11));
        closeBtn.addActionListener(ae -> { cb.onZoomHide(); dlg.dispose(); });

        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        south.add(closeBtn);
        south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }

    /** Prompts the player to pick exactly one card from {@code candidates}; returns the choice.
     *  Returns the first candidate if the dialog is closed without confirming. */
    public static CardData choose(JFrame owner, List<CardData> candidates, String title,
                                   Consumer<String> onZoom, Runnable onZoomHide) {
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        JDialog dlg = new JDialog(owner, title, true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        CardData[] picked = { null };
        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.setEnabled(false);

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        JLabel[] cardLabels = new JLabel[candidates.size()];

        for (int i = 0; i < candidates.size(); i++) {
            final int idx = i;
            final CardData cd = candidates.get(i);
            JLabel lbl = new JLabel("...", SwingConstants.CENTER);
            lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
            lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
            lbl.setOpaque(true);
            lbl.setBackground(Color.DARK_GRAY);
            lbl.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
            lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            cardLabels[i] = lbl;

            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { onZoom.accept(cd.imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { onZoomHide.run(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return;
                    picked[0] = cd;
                    for (int j = 0; j < cardLabels.length; j++) {
                        cardLabels[j].setBorder(BorderFactory.createLineBorder(
                                j == idx ? new Color(0, 200, 80) : Color.LIGHT_GRAY,
                                j == idx ? 3 : 1));
                    }
                    confirmBtn.setEnabled(true);
                }
            });

            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(cd.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();

            JLabel nameLabel = new JLabel(cd.name(), SwingConstants.CENTER);
            nameLabel.setFont(FontLoader.loadPixelNESFont(9));
            nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

            JPanel wrapper = new JPanel(new BorderLayout(0, 4));
            wrapper.setBackground(cardsPanel.getBackground());
            wrapper.add(lbl,       BorderLayout.CENTER);
            wrapper.add(nameLabel, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        JScrollPane scrollPane = new JScrollPane(cardsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setPreferredSize(new Dimension(
                Math.min(candidates.size() * (CARD_W + 16) + 16, 900),
                CARD_H + 60));

        confirmBtn.addActionListener(ae -> { onZoomHide.run(); dlg.dispose(); });

        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        south.add(confirmBtn);
        south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);

        return picked[0] != null ? picked[0] : candidates.get(0);
    }
}
