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

public class RemovedFromPlayDialog {

    public static void show(JFrame owner, List<GameState.WarpEntry> warpZone,
                             List<CardData> permZone, String player,
                             Consumer<String> onZoom, Runnable onZoomHide) {
        if (warpZone.isEmpty() && permZone.isEmpty()) return;

        int total = warpZone.size() + permZone.size();
        JDialog dlg = new JDialog(owner, player + " — Removed From Play (" + total
                + " card" + (total != 1 ? "s" : "") + ")", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

        for (GameState.WarpEntry entry : warpZone) {
            JPanel wrapper = new JPanel(new BorderLayout(0, 4));
            wrapper.setBackground(cardsPanel.getBackground());
            JLabel lbl = makeRfpCardLabel(entry.card.imageUrl(), onZoom, onZoomHide);
            JLabel info = new JLabel(entry.card.name() + "  [" + entry.counters + "]", SwingConstants.CENTER);
            info.setFont(FontLoader.loadPixelNESFont(9));
            info.setPreferredSize(new Dimension(CARD_W, 18));
            wrapper.add(lbl,  BorderLayout.CENTER);
            wrapper.add(info, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        for (CardData card : permZone) {
            JPanel wrapper = new JPanel(new BorderLayout(0, 4));
            wrapper.setBackground(cardsPanel.getBackground());
            JLabel lbl = makeRfpCardLabel(card.imageUrl(), onZoom, onZoomHide);
            JLabel info = new JLabel(card.name() + "  [RFG]", SwingConstants.CENTER);
            info.setFont(FontLoader.loadPixelNESFont(9));
            info.setPreferredSize(new Dimension(CARD_W, 18));
            wrapper.add(lbl,  BorderLayout.CENTER);
            wrapper.add(info, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        dlg.getContentPane().add(new JScrollPane(cardsPanel));
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
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
