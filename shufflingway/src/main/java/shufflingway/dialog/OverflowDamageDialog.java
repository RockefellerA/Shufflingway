package shufflingway.dialog;

import shufflingway.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.*;
import static shufflingway.graphics.CardAnimation.*;

/**
 * The damage past the seventh point. A player who can't lose the game (PR-143 Garnet) keeps
 * taking damage, and the Damage Zone has only seven slots to show it in, so the cards flipped for
 * the eighth point onward are listed here, each under the damage point it was for.
 */
public class OverflowDamageDialog {

    /** The first damage point that has no slot of its own on the board. */
    public static final int FIRST_OVERFLOW_POINT = 8;

    /**
     * @param damageZone the player's whole Damage Zone, in the order the points were taken; the
     *                   cards from {@link #FIRST_OVERFLOW_POINT} on are the ones shown
     */
    public static void show(JFrame owner, List<CardData> damageZone, String player,
                            Consumer<String> onZoom, Runnable onZoomHide) {
        int first = FIRST_OVERFLOW_POINT - 1;
        if (damageZone.size() <= first) return;
        List<CardData> overflow = damageZone.subList(first, damageZone.size());

        JDialog dlg = new JDialog(owner, player + " — Damage past 7 (" + overflow.size()
                + " point" + (overflow.size() != 1 ? "s" : "") + ")", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        for (int i = 0; i < overflow.size(); i++) {
            CardData card = overflow.get(i);
            JPanel wrapper = new JPanel(new BorderLayout(0, 4));
            wrapper.setBackground(cardsPanel.getBackground());
            JLabel point = new JLabel("Damage " + (FIRST_OVERFLOW_POINT + i), SwingConstants.CENTER);
            point.setFont(FontLoader.loadPixelFont(11));
            point.setPreferredSize(new Dimension(CARD_W, 18));
            JLabel name = new JLabel(card.name() + (card.exBurst() ? "  [EX]" : ""), SwingConstants.CENTER);
            name.setFont(FontLoader.loadPixelFont(9));
            name.setPreferredSize(new Dimension(CARD_W, 18));
            wrapper.add(point, BorderLayout.NORTH);
            wrapper.add(makeCardLabel(card.imageUrl(), onZoom, onZoomHide), BorderLayout.CENTER);
            wrapper.add(name, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        dlg.getContentPane().add(new JScrollPane(cardsPanel));
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }

    private static JLabel makeCardLabel(String imageUrl, Consumer<String> onZoom, Runnable onZoomHide) {
        JLabel lbl = new JLabel("...", SwingConstants.CENTER);
        lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
        lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
        lbl.setOpaque(true);
        lbl.setBackground(Color.DARK_GRAY);
        lbl.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
        lbl.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { if (lbl.getIcon() != null) onZoom.accept(imageUrl); }
            @Override public void mouseExited(MouseEvent e)  { onZoomHide.run(); }
        });
        new SwingWorker<ImageIcon, Void>() {
            @Override protected ImageIcon doInBackground() throws Exception {
                Image img = ImageCache.load(imageUrl);
                return img == null ? null : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
            }
            @Override protected void done() {
                try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
                catch (InterruptedException | ExecutionException ignored) {}
            }
        }.execute();
        return lbl;
    }
}
