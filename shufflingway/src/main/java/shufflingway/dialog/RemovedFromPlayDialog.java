package shufflingway.dialog;

import shufflingway.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.*;
import static shufflingway.graphics.CardAnimation.*;

public class RemovedFromPlayDialog {

    /**
     * @param hidden   cards in {@code permZone} that were removed face down and that this screen's
     *                 player may not look at -- Aemo 23-022R. They are shown as backs labelled
     *                 "???", the same way an unspent Limit Break card is, so the count and the
     *                 fact of the removal stay public while the cards themselves do not.
     * @param cardback supplies the raw cardback image, loaded only if something is actually hidden.
     */
    public static void show(JFrame owner, List<GameState.WarpEntry> warpZone,
                             List<CardData> permZone, String player,
                             Predicate<CardData> hidden, Supplier<Image> cardback,
                             Consumer<String> onZoom, Runnable onZoomHide) {
        if (warpZone.isEmpty() && permZone.isEmpty()) return;

        int total = warpZone.size() + permZone.size();
        JDialog dlg = new JDialog(owner, player + " — Removed From Play (" + total
                + " card" + (total != 1 ? "s" : "") + ")", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        List<Component> cells = new ArrayList<>();

        for (GameState.WarpEntry entry : warpZone) {
            JPanel wrapper = new JPanel(new BorderLayout(0, 4));
            JLabel lbl = makeRfpCardLabel(entry.card.imageUrl(), onZoom, onZoomHide);
            JLabel info = new JLabel(entry.card.name() + "  [" + entry.counters + "]", SwingConstants.CENTER);
            info.setFont(FontLoader.loadPixelFont(9));
            info.setPreferredSize(new Dimension(CARD_W, 18));
            wrapper.add(lbl,  BorderLayout.CENTER);
            wrapper.add(info, BorderLayout.SOUTH);
            cells.add(wrapper);
        }

        for (CardData card : permZone) {
            boolean faceDown = hidden != null && hidden.test(card);
            JPanel wrapper = new JPanel(new BorderLayout(0, 4));
            // A hidden card gets no url at all rather than a suppressed one: the hover zoom reads
            // whatever it is handed, so the only safe thing to hand it is nothing.
            JLabel lbl = faceDown ? makeCardbackLabel(cardback)
                                  : makeRfpCardLabel(card.imageUrl(), onZoom, onZoomHide);
            JLabel info = new JLabel(faceDown ? "???  [RFG]" : card.name() + "  [RFG]",
                    SwingConstants.CENTER);
            info.setFont(FontLoader.loadPixelFont(9));
            info.setPreferredSize(new Dimension(CARD_W, 18));
            wrapper.add(lbl,  BorderLayout.CENTER);
            wrapper.add(info, BorderLayout.SOUTH);
            cells.add(wrapper);
        }

        dlg.getContentPane().add(CardGridPane.of(cells));
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }

    /** A face-down card: the cardback, with no url and no hover zoom to give the face away. */
    private static JLabel makeCardbackLabel(Supplier<Image> cardback) {
        JLabel lbl = new JLabel("...", SwingConstants.CENTER);
        lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
        lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
        lbl.setOpaque(true);
        lbl.setBackground(Color.DARK_GRAY);
        lbl.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
        new SwingWorker<ImageIcon, Void>() {
            @Override protected ImageIcon doInBackground() throws Exception {
                Image img = cardback == null ? null : cardback.get();
                return img == null ? null : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
            }
            @Override protected void done() {
                try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
                catch (InterruptedException | ExecutionException ignored) {}
            }
        }.execute();
        return lbl;
    }

    private static JLabel makeRfpCardLabel(String imageUrl, Consumer<String> onZoom, Runnable onZoomHide) {
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
